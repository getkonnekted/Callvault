/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.utils.PcmDownmix
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records BOTH sides of a VoIP call (WhatsApp / Signal / Telegram / …), which the telephony capture
 * paths cannot reach because there is no carrier call involved.
 *
 * Two independent streams are combined:
 *  - **far party** — [VoipAudioPolicy]'s loopback-render sink, a duplicate of the remote voice the app
 *    is rendering. The policy must already be armed (see that class); the sink may be created after
 *    the call has started.
 *  - **near party** — a plain `MIC` capture. The source matters: `VOICE_COMMUNICATION` is zero-filled
 *    for the whole call, because the in-call policy silences a record client that cannot bypass it and
 *    that source is the very one the VoIP app itself holds. `MIC` is not silenced, and the VoIP app
 *    keeps working alongside it.
 *
 * The two are interleaved to stereo — LEFT near, RIGHT far — and then downmixed to mono for the
 * encoder, matching what [DirectAudioRecorderSession] does for a stereo carrier capture, so recordings
 * from both paths sound alike.
 *
 * Because the streams are separate free-running `AudioRecord`s, the muxer aligns them on wall-clock:
 * it pairs one chunk from each and substitutes silence for whichever side has nothing ready. A stalled
 * or silenced side therefore costs its own channel and never the timeline. Measured on real calls, no
 * substitution was needed over 40 s, so the two clocks track closely in practice.
 */
internal class VoipCaptureSession(
    private val codec: ScrcpyAudioCodec,
    private val bitRate: Int,
    /** The daemon's received fd copy. The muxer writes through it; [stop] closes it after finalising. */
    private val outFd: ParcelFileDescriptor,
) : RecordingSession {

    private val stopRequested = AtomicBoolean(false)

    /**
     * Whether the FAR party was ever actually audible.
     *
     * An app can opt out of being captured (`ALLOW_CAPTURE_BY_NONE` sets a flag checked before any
     * permission and bypassable by nothing), and some OEM builds simply do not attach the call to our
     * mix. Both look identical from here: the mix delivers perfect digital silence while the near side
     * records normally — so the user would get a one-sided recording with no explanation.
     *
     * Reading the app's capture policy directly is not available to us: it needs an AudioManager, which
     * needs a Context, and obtaining one in this process poisons the attribution the capture depends on
     * (see VoipAudioPolicy). So judge the OUTCOME instead — which also catches the OEM cases a policy
     * read would miss.
     */
    @Volatile var farPartyHeard: Boolean = false
        private set
    @Volatile private var farRecord: AudioRecord? = null
    @Volatile private var nearRecord: AudioRecord? = null
    @Volatile private var encoder: MediaCodec? = null
    @Volatile private var muxer: MediaMuxer? = null
    @Volatile private var muxThread: Thread? = null

    override fun start() {
        try {
            startInternal()
        } catch (t: Throwable) {
            // Release our own resources but do NOT close outFd — the caller still owns it.
            cleanupPartial()
            throw t
        }
    }

    private fun startInternal() {
        val far = VoipAudioPolicy.createSink()
            ?: throw IllegalStateException("VoIP far-party sink unavailable (policy not armed?)")
        farRecord = far

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) throw IllegalStateException("VoIP mic minBufferSize=$minBuf")
        @Suppress("MissingPermission")
        val near = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * BUFFER_FACTOR,
        )
        if (near.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { near.release() }
            throw IllegalStateException("VoIP mic capture failed to initialise")
        }
        nearRecord = near

        val mime = when (codec) {
            ScrcpyAudioCodec.OPUS -> MediaFormat.MIMETYPE_AUDIO_OPUS
            ScrcpyAudioCodec.AAC -> MediaFormat.MIMETYPE_AUDIO_AAC
        }
        val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, ENCODE_CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
        }
        val enc = MediaCodec.createEncoderByType(mime).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        encoder = enc

        // Muxer last: everything riskier has succeeded, so the output fd is only consumed now.
        val mux = MediaMuxer(outFd.fileDescriptor, codec.outputFormat)
        muxer = mux

        enc.start()
        far.startRecording()
        near.startRecording()
        AppLogger.i(TAG, "VoIP capture started: codec=${codec.cliKey} rate=$SAMPLE_RATE bitRate=$bitRate")

        muxThread = Thread {
            runCatching { captureLoop(near, far, enc, mux) }
                .onFailure { AppLogger.w(TAG, "VoIP capture loop ended: ${it.message}") }
        }.apply { isDaemon = true; name = "voip-capture" }.also { it.start() }
    }

    /**
     * Pairs one chunk from each direction, downmixes to mono and drives the encoder. Each side is read
     * on its own thread so a slow or silent one cannot stall the other.
     */
    private fun captureLoop(near: AudioRecord, far: AudioRecord, enc: MediaCodec, mux: MediaMuxer) {
        val qNear: BlockingQueue<ByteArray> = ArrayBlockingQueue(QUEUE_CHUNKS)
        val qFar: BlockingQueue<ByteArray> = ArrayBlockingQueue(QUEUE_CHUNKS)
        val readers = listOf(feeder(near, qNear, "near"), feeder(far, qFar, "far"))
        readers.forEach { it.start() }

        val silence = ByteArray(CHUNK_BYTES)
        val stereo = ByteArray(CHUNK_BYTES * 2)
        val mono = ByteArray(CHUNK_BYTES)
        val info = MediaCodec.BufferInfo()
        var muxerStarted = false
        var totalFrames = 0L
        var substituted = 0L

        try {
            while (!stopRequested.get()) {
                val n = qNear.poll(CHUNK_WAIT_MS, TimeUnit.MILLISECONDS) ?: silence.also { substituted++ }
                val f = qFar.poll(CHUNK_WAIT_MS, TimeUnit.MILLISECONDS) ?: silence
                var o = 0
                var farPeak = 0
                for (i in 0 until CHUNK_BYTES step 2) {
                    stereo[o] = n[i]; stereo[o + 1] = n[i + 1]          // L = near
                    stereo[o + 2] = f[i]; stereo[o + 3] = f[i + 1]      // R = far
                    val fs = ((f[i].toInt() and 0xFF) or (f[i + 1].toInt() shl 8)).toShort().toInt()
                    val fa = if (fs < 0) -fs else fs
                    if (fa > farPeak) farPeak = fa
                    o += 4
                }
                // A silenced mix is EXACTLY zero, so any real signal clears the threshold easily; the
                // margin only guards against dither.
                if (!farPartyHeard && farPeak > FAR_SILENCE_THRESHOLD) farPartyHeard = true
                // Mono for the encoder, so the whole bitrate goes to one channel — the same reasoning
                // as the carrier path, where encoding stereo starved the far party.
                val len = PcmDownmix.stereoToMono(stereo, stereo.size, mono)

                var inIdx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                while (inIdx < 0 && !stopRequested.get()) {
                    muxerStarted = drainEncoder(enc, mux, info, muxerStarted)
                    inIdx = enc.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                }
                if (inIdx < 0) break
                enc.getInputBuffer(inIdx)!!.apply { clear(); put(mono, 0, len) }
                enc.queueInputBuffer(inIdx, 0, len, totalFrames * 1_000_000L / SAMPLE_RATE, 0)
                totalFrames += len / (2 * ENCODE_CHANNELS)
                muxerStarted = drainEncoder(enc, mux, info, muxerStarted)
            }

            val inIdx = enc.dequeueInputBuffer(END_OF_STREAM_TIMEOUT_US)
            if (inIdx >= 0) enc.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainEncoder(enc, mux, info, muxerStarted, drainToEos = true)
            AppLogger.i(TAG, "VoIP capture finished: ${totalFrames / SAMPLE_RATE}s, $substituted silence-filled chunks, farPartyHeard=$farPartyHeard")
            if (!farPartyHeard) {
                AppLogger.w(TAG, "Far party was never audible — this app blocks capture, or the OEM did not attach the call to our mix")
            }
        } finally {
            readers.forEach { it.interrupt() }
        }
    }

    /** Reads whole chunks from one direction into its queue; drops rather than blocks if the muxer lags. */
    private fun feeder(ar: AudioRecord, q: BlockingQueue<ByteArray>, tag: String) = Thread {
        val buf = ByteArray(CHUNK_BYTES)
        var record = ar
        var silentChunks = 0
        while (!stopRequested.get()) {
            var off = 0
            while (off < CHUNK_BYTES && !stopRequested.get()) {
                val r = record.read(buf, off, CHUNK_BYTES - off)
                if (r <= 0) { AppLogger.d(TAG, "$tag read=$r, feeder ending"); return@Thread }
                off += r
            }
            // Re-take the mic when the platform has silenced us.
            //
            // On One UI only one client gets the mic, and the most recent starter wins: when the VoIP
            // app restarts ITS capture mid-call, ours is silenced — it keeps delivering chunks, but
            // they are digital zeros, so nothing else notices. Measured on a Galaxy S24 FE: ~6 s of a
            // 21 s call lost, matching a flat -107 dB stretch in the output.
            //
            // Restarting our AudioRecord makes US the most recent starter, and the audio comes back.
            // Verified safe for the call itself: with the phones in separate rooms the far end still
            // heard everything while our capture held the mic, so the VoIP app keeps transmitting
            // regardless of what the arbitration reports. Only the NEAR source needs this — the far
            // party arrives through the policy submix, outside the mic arbitration entirely.
            if (tag == "near") {
                if (isAllZero(buf)) silentChunks++ else silentChunks = 0
                if (silentChunks >= SILENT_CHUNKS_BEFORE_RETAKE) {
                    silentChunks = 0
                    val fresh = retakeMic(record)
                    if (fresh != null) { record = fresh; nearRecord = fresh }
                }
            }
            q.offer(buf.copyOf())
        }
    }.apply { isDaemon = true; name = "voip-$tag" }

    /** True when every sample in the chunk is exactly zero — the fingerprint of a silenced capture. */
    private fun isAllZero(buf: ByteArray): Boolean {
        for (b in buf) if (b.toInt() != 0) return false
        return true
    }

    /**
     * Closes the silenced capture and opens a fresh one, which makes us the most recent starter.
     * Returns null (and leaves the old one running) if the new capture cannot be opened, so a failure
     * here degrades to today's behaviour rather than ending the recording.
     */
    private fun retakeMic(current: AudioRecord): AudioRecord? {
        AppLogger.i(TAG, "near capture silenced by the platform — re-taking the mic")
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        @Suppress("MissingPermission")
        val fresh = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * BUFFER_FACTOR,
            )
        }.getOrNull()
        if (fresh == null || fresh.state != AudioRecord.STATE_INITIALIZED) {
            AppLogger.w(TAG, "re-take failed to initialise; keeping the silenced capture")
            runCatching { fresh?.release() }
            return null
        }
        runCatching { fresh.startRecording() }
        runCatching { current.stop() }
        runCatching { current.release() }
        return fresh
    }

    /** Drains available encoder output into the muxer. Returns whether the muxer is (now) started. */
    private fun drainEncoder(
        enc: MediaCodec, mux: MediaMuxer, info: MediaCodec.BufferInfo,
        muxerStartedIn: Boolean, drainToEos: Boolean = false,
    ): Boolean {
        var muxerStarted = muxerStartedIn
        var track = if (muxerStarted) 0 else -1
        while (true) {
            val outIdx = enc.dequeueOutputBuffer(info, if (drainToEos) END_OF_STREAM_TIMEOUT_US else 0)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = mux.addTrack(enc.outputFormat)   // carries the codec-specific data
                    mux.start()
                    muxerStarted = true
                }
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (drainToEos) continue else return muxerStarted
                outIdx >= 0 -> {
                    val outBuf = enc.getOutputBuffer(outIdx)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0 && muxerStarted) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        mux.writeSampleData(track, outBuf, info)
                    }
                    enc.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return muxerStarted
                }
            }
        }
    }

    override fun stop() {
        if (!stopRequested.compareAndSet(false, true)) return
        runCatching { muxThread?.join(STOP_JOIN_MS) }
        runCatching { farRecord?.stop() }; runCatching { farRecord?.release() }
        runCatching { nearRecord?.stop() }; runCatching { nearRecord?.release() }
        runCatching { encoder?.stop() }; runCatching { encoder?.release() }
        runCatching { muxer?.stop() }    // writes the trailer — without it the file won't play
        runCatching { muxer?.release() }
        runCatching { outFd.close() }
        farRecord = null; nearRecord = null; encoder = null; muxer = null
        AppLogger.i(TAG, "VoIP capture stopped")
    }

    /** Releases what start() managed to build, leaving [outFd] open for the caller. */
    private fun cleanupPartial() {
        runCatching { farRecord?.release() }
        runCatching { nearRecord?.release() }
        runCatching { encoder?.release() }
        runCatching { muxer?.release() }
        farRecord = null; nearRecord = null; encoder = null; muxer = null
    }

    companion object {
        /**
         * Consecutive all-zero chunks before we conclude the platform has silenced us rather than the
         * room simply being quiet. A real mic never returns exact zeros — even silence carries a noise
         * floor — so this only needs to outlast a codec-aligned run of them, not real quiet.
         */
        private const val SILENT_CHUNKS_BEFORE_RETAKE = 15

        private const val TAG = "CV:VoipCapture"
        private const val SAMPLE_RATE = VoipAudioPolicy.SAMPLE_RATE
        private const val ENCODE_CHANNELS = 1
        private const val BUFFER_FACTOR = 4
        private const val CHUNK_FRAMES = 960                 // 20 ms at 48 kHz
        private const val CHUNK_BYTES = CHUNK_FRAMES * 2     // mono PCM-16
        private const val QUEUE_CHUNKS = 400                 // ~8 s of slack per direction
        private const val CHUNK_WAIT_MS = 120L
        private const val MAX_INPUT_SIZE = 16_384
        private const val DEQUEUE_TIMEOUT_US = 10_000L
        private const val END_OF_STREAM_TIMEOUT_US = 100_000L
        private const val STOP_JOIN_MS = 3_000L
        private const val FAR_SILENCE_THRESHOLD = 100

        /** True if this device can encode the chosen codec; the policy tap is checked when arming. */
        fun supports(codec: ScrcpyAudioCodec): Boolean = runCatching {
            val mime = when (codec) {
                ScrcpyAudioCodec.OPUS -> MediaFormat.MIMETYPE_AUDIO_OPUS
                ScrcpyAudioCodec.AAC -> MediaFormat.MIMETYPE_AUDIO_AAC
            }
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }
}
