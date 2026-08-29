/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording.handoff

import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.baba.callvault.utils.AppLogger
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * App-side receiver + controller for the audio-capture handoff ("Resilient recording", Option B).
 *
 * The recording engine calls [begin] with the output target BEFORE asking the daemon to start the
 * handoff. The daemon then pushes the `IAudioRecord` binder + cblk ashmem fd, which lands in [onReceived]
 * (routed by `RecorderBinderProvider`) on a binder thread. This object holds the binder — an independent
 * ref in the APP's binder_proc = keep-alive — and runs the full app-side capture: a native thread drains
 * the cblk ring to a pipe while [HandoffEncoder] reads the pipe and encodes into the engine's SAF output
 * fd. Killing the daemon mid-capture does NOT stop it: the track survives on the app's ref and the encode
 * is local. [stop] flips the stop flag and waits for the container to finalise.
 */
object HandoffReceiver {
    private const val T = "CV:HandoffRecv"

    private const val MAX_SECONDS = 4 * 60 * 60 // safety cap if a stop signal is ever lost
    private const val ENCODE_JOIN_MS = 8000L    // bound the wait for the encoder to finalise the container

    /** Output target the engine hands over (via [begin]) before starting the handoff. */
    data class Target(
        val outputFd: FileDescriptor,
        val mime: String,
        val muxerFormat: Int,
        val bitRate: Int,
        val downmixToMono: Boolean,
    )

    @Volatile private var pending: Target? = null
    // Strong ref so the app's binder_proc keeps the RecordHandle ref (keep-alive after the daemon dies).
    @Volatile private var held: IBinder? = null
    @Volatile private var cblk: ParcelFileDescriptor? = null
    @Volatile private var stopFlag: ByteBuffer? = null
    @Volatile private var encodeThread: Thread? = null

    /** True while a handoff capture is live in the app (survives daemon death). */
    @Volatile
    var isLive: Boolean = false
        private set

    /** Engine sets the output target BEFORE calling `IRecorderService.startHandoff`, so the push finds it. */
    fun begin(target: Target) {
        pending = target
        isLive = false
    }

    /** Clears a pending/failed handoff without a live capture (used when establishment fails → fall back). */
    fun abort() {
        pending = null
        releaseRefs()
        isLive = false
    }

    /**
     * Push entry point (called from `RecorderBinderProvider` on a binder thread when the daemon delivers).
     * Holds the binder + cblk and starts the drain + encode into the pending target's output fd.
     */
    fun onReceived(binder: IBinder?, cblkFd: ParcelFileDescriptor?, frameCount: Int, sampleRate: Int, channels: Int) {
        // A probe run borrows the delivery: it is measuring whether the app may START a handed-over
        // track, not recording anything, so the normal drain must not begin.
        if (TrackAProbe.isArmed && binder != null) {
            TrackAProbe.onHandoff(binder, cblkFd, frameCount, sampleRate, channels)
            return
        }

        val target = pending
        if (target == null) {
            AppLogger.w(T, "onReceived with no pending target — ignoring handoff")
            runCatching { cblkFd?.close() }
            return
        }
        // Idempotency: a spurious/duplicate delivery must NOT spin up a second drain+encode against the
        // same output fd (dual-writer corruption). Only the first delivery for this session captures.
        if (isLive) {
            AppLogger.w(T, "onReceived while already capturing — ignoring duplicate handoff")
            runCatching { cblkFd?.close() }
            return
        }
        // Lazily load libaudiohandoff.so on the app side the first time a handoff actually arrives (the
        // native drain + ashmem-size calls need it). Idempotent.
        if (!AudioHandoffNative.ensureLoaded()) {
            AppLogger.e(T, "libaudiohandoff.so not available — cannot capture handoff")
            runCatching { cblkFd?.close() }
            return
        }
        val cblkFdNum = cblkFd?.fd ?: -1
        val ping = runCatching { binder?.pingBinder() }.getOrNull()
        AppLogger.i(T, "handoff received ping(alive)=$ping cblkFd=$cblkFdNum frameCount=$frameCount rate=$sampleRate ch=$channels")

        // Validate the geometry BEFORE any native mmap/drain. The trusted daemon always sends valid
        // values; this rejects a malicious or corrupt payload that could drive an out-of-bounds native
        // read (frameCount/channels/cblk size are otherwise fed straight into pointer arithmetic).
        if (cblkFdNum < 0) { AppLogger.w(T, "handoff missing cblk fd — cannot capture"); runCatching { cblkFd?.close() }; return }
        val geometry = HandoffGeometry.of(
            frameCount = frameCount,
            sampleRate = sampleRate,
            channels = channels,
            cblkSize = AudioHandoffNative.nativeAshmemSize(cblkFdNum),
        )
        geometry.validationError()?.let { reason ->
            AppLogger.w(T, "handoff rejected: $reason")
            runCatching { cblkFd?.close() }
            return
        }

        // Validated — hold the refs (app keep-alive) and start the drain + encode into the target fd.
        held = binder
        cblk = cblkFd
        startCapture(target, cblkFdNum, geometry)
    }

    /** Ends the capture: flips the stop flag, waits for the encoder to finalise the container, releases refs. */
    fun stop() {
        AppLogger.i(T, "stop requested")
        stopFlag?.putInt(0, 1) // drain exits within one poll cycle → closes writeFd → encoder hits EOF
        val enc = encodeThread
        runCatching { enc?.join(ENCODE_JOIN_MS) }
        if (enc?.isAlive == true) {
            // The encoder didn't finish flushing/finalising the container in time. The caller is about to
            // close the output fd, so log it — a truncated recording is then diagnosable rather than
            // silently mis-attributed. (Expected only under severe load; normal finalise is sub-second.)
            AppLogger.e(T, "handoff encoder still running after ${ENCODE_JOIN_MS}ms — recording may be truncated")
        }
        releaseRefs()
        isLive = false
        pending = null
    }

    private fun releaseRefs() {
        val had = held != null
        held = null
        runCatching { cblk?.close() }
        cblk = null
        stopFlag = null
        encodeThread = null
        if (had) forceReleaseCaptureInput()
    }

    /**
     * Drops the handed-off capture for real.
     *
     * The `IAudioRecord` is kept alive purely by THIS process's binder REFCOUNT — that is precisely what
     * makes the capture survive the daemon's death. Nulling [held] is therefore NOT enough: the native
     * ref is only released when the `BinderProxy` is finalized, so without a collection the RecordTrack
     * stays alive and keeps the (exclusive) VOICE_CALL input open — silently starving the NEXT recording,
     * which then produces an empty file.
     *
     * In the normal flow the daemon's `stopHandoff` stops its own track and frees the input; but when the
     * daemon has DIED — the case this whole feature exists for — nothing else can release it. So force a
     * collection here, off the caller's thread (this runs once per recording, at call end).
     */
    private fun forceReleaseCaptureInput() {
        Thread {
            runCatching {
                System.gc()
                System.runFinalization()
                System.gc() // second pass: finalizer-enqueued proxies are reclaimed on the following cycle
            }
            AppLogger.i(T, "handoff capture input released (forced collection of the IAudioRecord ref)")
        }.apply { isDaemon = true; name = "cv-handoff-release" }.start()
    }

    /**
     * Wires the native cblk drain to [HandoffEncoder] through a pipe: native writes ordered interleaved
     * PCM to the pipe (its write end is detached to native ownership, close()=EOF), the encoder reads the
     * pipe, downmixes if asked, and muxes into [Target.outputFd]. The output fd is NOT closed here — the
     * engine owns its lifecycle and closes it after [stop] (once the container trailer is written).
     */
    private fun startCapture(target: Target, cblkFdNum: Int, geometry: HandoffGeometry) {
        val flag = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        stopFlag = flag
        val pipe = ParcelFileDescriptor.createPipe() // [0]=read, [1]=write
        val readPfd = pipe[0]
        val writeFd = pipe[1].detachFd()             // ownership → native (it close()s = EOF)
        AppLogger.i(T, "handoff capture → SAF fd (pipe writeFd=$writeFd frameSize=${geometry.frameSize} rate=${geometry.sampleRate} ch=${geometry.channels})")

        val encoder = Thread {
            runCatching {
                HandoffEncoder(
                    pcmIn = ParcelFileDescriptor.AutoCloseInputStream(readPfd),
                    outFd = target.outputFd,
                    sampleRate = geometry.sampleRate,
                    captureChannels = geometry.channels,
                    downmixToMono = target.downmixToMono,
                    mime = target.mime,
                    muxerFormat = target.muxerFormat,
                    bitRate = target.bitRate,
                ).encodeBlocking()
                AppLogger.i(T, "handoff encode DONE")
            }.onFailure { AppLogger.w(T, "handoff encode error: ${it.message}") }
        }.apply { isDaemon = true; name = "cv-handoff-encode" }
        encodeThread = encoder
        encoder.start()

        Thread {
            runCatching {
                AudioHandoffNative.nativeDrainToPipe(
                    cblkFdNum, geometry.cblkSize, geometry.wrapFrames, geometry.dataOff,
                    geometry.frameSize, HandoffGeometry.GUARD_FRAMES, writeFd, flag, MAX_SECONDS,
                )
            }.onFailure { AppLogger.w(T, "handoff drain error: ${it.message}") }
        }.apply { isDaemon = true; name = "cv-handoff-drain" }.start()

        isLive = true
    }
}
