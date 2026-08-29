/*

* CallVault: FOSS call recording, self-contained over embedded ADB
* Copyright (C) 2026-present The CallVault Authors
* This software is licensed under the GNU General Public License v3 or later,
* with additional terms as permitted under Section 7.
  */

package com.baba.callvault.server

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresPermission
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioCodec
import com.baba.callvault.integrations.scrcpy.ScrcpyAudioSource
import com.baba.callvault.integrations.scrcpy.androidAudioSource
import com.baba.callvault.stt.AudioFrameBus
import com.baba.callvault.stt.AudioFrameTestWorker
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.utils.PcmDownmix
import java.util.concurrent.atomic.AtomicBoolean

internal class DirectAudioRecorderSession(
    private val source: ScrcpyAudioSource,
    private val codec: ScrcpyAudioCodec,
    private val bitRate: Int,
    private val outFd: ParcelFileDescriptor,
) : RecordingSession {


    private val stopRequested = AtomicBoolean(false)

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var encoder: MediaCodec? = null

    @Volatile
    private var muxer: MediaMuxer? = null

    @Volatile
    private var readThread: Thread? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() {

        AppLogger.i(TAG, "==================================================")
        AppLogger.i(TAG, "DirectAudioRecorderSession.start() ENTER")

        try {

            stopRequested.set(false)

            startInternal()

            AppLogger.i(
                TAG,
                "DirectAudioRecorderSession.start() SUCCESS"
            )

        } catch (t: Throwable) {

            AppLogger.w(
                TAG,
                "DirectAudioRecorderSession.start() FAILED: " +
                        "${t.javaClass.simpleName}: ${t.message}"
            )

            stopRequested.set(true)

            /*
             * Stop AudioRecord first so a possible blocking read()
             * can be released.
             */
            runCatching {
                audioRecord?.stop()
            }

            runCatching {
                readThread?.join(READ_JOIN_MS)
            }

            AudioFrameTestWorker.stop()

            cleanupPartial()

            throw t
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startInternal() {

        AppLogger.i(TAG, "Direct capture initialization started")

        val androidSource =
            source.androidAudioSource
                ?: throw UnsupportedOperationException(
                    "source ${source.cliKey} is not a mic-type source"
                )

        val mime = encoderMimeFor(codec)

        AppLogger.i(
            TAG,
            "Resolved Android audio source: $androidSource"
        )

        AppLogger.i(
            TAG,
            "Encoder MIME: $mime"
        )

        val (record, captureChannels) =
            openAudioRecord(androidSource)

        audioRecord = record

        AppLogger.i(
            TAG,
            "AudioRecord initialized successfully: " +
                    "channels=$captureChannels " +
                    "sampleRate=$SAMPLE_RATE"
        )

        val effectiveBitRate =
            EncoderLimits.resolveBitRate(
                mime,
                bitRate,
                SAMPLE_RATE,
                ENCODE_CHANNELS
            )

        val format =
            MediaFormat.createAudioFormat(
                mime,
                SAMPLE_RATE,
                ENCODE_CHANNELS
            ).apply {

                setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    effectiveBitRate
                )

                if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {

                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                }

                setInteger(
                    MediaFormat.KEY_MAX_INPUT_SIZE,
                    MAX_INPUT_SIZE
                )
            }

        val enc =
            MediaCodec.createEncoderByType(mime).apply {

                configure(
                    format,
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE
                )
            }

        encoder = enc

        AppLogger.i(
            TAG,
            "MediaCodec configured successfully"
        )

        val mux =
            MediaMuxer(
                outFd.fileDescriptor,
                codec.outputFormat
            )

        muxer = mux

        enc.start()

        AppLogger.i(
            TAG,
            "MediaCodec started"
        )

        /*
         * Start the PCM consumer before capture starts.
         */
        AudioFrameBus.clear()

        AudioFrameTestWorker.start()

        AppLogger.i(
            TAG,
            "AudioFrameTestWorker started"
        )

        record.startRecording()

        if (
            record.recordingState !=
            AudioRecord.RECORDSTATE_RECORDING
        ) {

            throw IllegalStateException(
                "AudioRecord failed to enter RECORDING state"
            )
        }

        AppLogger.i(TAG, "==================================================")
        AppLogger.i(TAG, "REAL AUDIO CAPTURE IS NOW RUNNING")

        AppLogger.i(
            TAG,
            "source=${source.cliKey} " +
                    "captureCh=$captureChannels " +
                    "encodeCh=$ENCODE_CHANNELS " +
                    "rate=$SAMPLE_RATE"
        )

        AppLogger.i(
            TAG,
            "AudioFrameBus branch is ACTIVE"
        )

        AppLogger.i(TAG, "==================================================")

        readThread =
            Thread {

                AppLogger.i(
                    TAG,
                    "direct-capture thread ENTER"
                )

                runCatching {

                    captureLoop(
                        record,
                        enc,
                        mux,
                        captureChannels
                    )

                }.onFailure {

                    if (!stopRequested.get()) {

                        AppLogger.w(
                            TAG,
                            "Direct capture loop ended with error: " +
                                    "${it.javaClass.simpleName}: ${it.message}"
                        )
                    }
                }

                AppLogger.i(
                    TAG,
                    "direct-capture thread EXIT"
                )

            }.apply {

                isDaemon = true

                name = "direct-capture"

            }.also {

                it.start()
            }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun captureLoop(
        record: AudioRecord,
        enc: MediaCodec,
        mux: MediaMuxer,
        captureChannels: Int
    ) {

        AppLogger.i(
            TAG,
            "captureLoop() ENTER"
        )

        val pcm =
            ByteArray(
                READ_CHUNK_BYTES
            )

        val mono =
            ByteArray(
                READ_CHUNK_BYTES / 2
            )

        val downmix =
            captureChannels == 2

        val info =
            MediaCodec.BufferInfo()

        var muxerStarted =
            false

        var totalFrames =
            0L

        var totalPcmBytes =
            0L

        var totalPublishedBytes =
            0L

        var readCount =
            0L

        var errorReadCount =
            0L

        val bytesPerFrame =
            2 * ENCODE_CHANNELS

        while (!stopRequested.get()) {

            val read =
                record.read(
                    pcm,
                    0,
                    pcm.size
                )

            /*
             * AudioRecord.stop() can cause read() to return an error.
             * If shutdown was requested, exit cleanly.
             */
            if (stopRequested.get()) {
                break
            }

            readCount++

            if (read < 0) {

                errorReadCount++

                AppLogger.w(
                    TAG,
                    "AudioRecord.read() returned ERROR: $read"
                )

                continue
            }

            if (read == 0) {
                continue
            }

            totalPcmBytes +=
                read.toLong()

            /*
             * Convert stereo capture to mono when necessary.
             */
            val (buf, len) =
                if (downmix) {

                    mono to PcmDownmix.stereoToMono(
                        pcm,
                        read,
                        mono
                    )

                } else {

                    pcm to read
                }

            /*
             * =====================================================
             * REAL AudioRecord PCM -> AudioFrameBus
             * =====================================================
             */
            if (len > 0) {

                AudioFrameBus.publish(
                    buf,
                    len
                )

                totalPublishedBytes +=
                    len.toLong()
            }

            if (readCount == 1L) {

                AppLogger.i(
                    TAG,
                    "FIRST REAL PCM BUFFER RECEIVED: " +
                            "read=$read " +
                            "published=$len " +
                            "captureCh=$captureChannels"
                )
            }

            if (
                readCount % LOG_EVERY_READS == 0L
            ) {

                AppLogger.i(
                    TAG,
                    "REAL PCM flowing: " +
                            "reads=$readCount " +
                            "pcmBytes=$totalPcmBytes " +
                            "publishedBytes=$totalPublishedBytes " +
                            "busReceived=${AudioFrameBus.receivedCount()} " +
                            "busDropped=${AudioFrameBus.droppedCount()} " +
                            "errorReads=$errorReadCount"
                )
            }

            /*
             * Existing recording branch.
             */
            val inIdx =
                enc.dequeueInputBuffer(
                    DEQUEUE_TIMEOUT_US
                )

            if (inIdx >= 0) {

                val inBuf =
                    enc.getInputBuffer(inIdx)
                        ?: throw IllegalStateException(
                            "Encoder input buffer is null"
                        )

                inBuf.clear()

                if (len > 0) {

                    inBuf.put(
                        buf,
                        0,
                        len
                    )
                }

                val ptsUs =
                    totalFrames *
                            1_000_000L /
                            SAMPLE_RATE

                enc.queueInputBuffer(
                    inIdx,
                    0,
                    len,
                    ptsUs,
                    0
                )

                totalFrames +=
                    len / bytesPerFrame
            }

            /*
             * IMPORTANT:
             * Drain encoder continuously during capture.
             */
            muxerStarted =
                drainEncoder(
                    enc,
                    mux,
                    info,
                    muxerStarted
                )
        }

        AppLogger.i(
            TAG,
            "captureLoop stopping: " +
                    "reads=$readCount " +
                    "pcmBytes=$totalPcmBytes " +
                    "publishedBytes=$totalPublishedBytes"
        )

        /*
         * Flush encoder.
         */
        val inIdx =
            enc.dequeueInputBuffer(
                END_OF_STREAM_TIMEOUT_US
            )

        if (inIdx >= 0) {

            enc.queueInputBuffer(
                inIdx,
                0,
                0,
                0,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        }

        drainEncoder(
            enc,
            mux,
            info,
            muxerStarted,
            drainToEos = true
        )

        AppLogger.i(
            TAG,
            "captureLoop() EXIT"
        )
    }

    private fun drainEncoder(
        enc: MediaCodec,
        mux: MediaMuxer,
        info: MediaCodec.BufferInfo,
        muxerStartedIn: Boolean,
        drainToEos: Boolean = false,
    ): Boolean {

        var muxerStarted =
            muxerStartedIn

        var track =
            if (muxerStarted) 0 else -1

        while (true) {

            val outIdx =
                enc.dequeueOutputBuffer(
                    info,
                    if (drainToEos) {
                        END_OF_STREAM_TIMEOUT_US
                    } else {
                        0
                    }
                )

            when {

                outIdx ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                    if (!muxerStarted) {

                        track =
                            mux.addTrack(
                                enc.outputFormat
                            )

                        mux.start()

                        muxerStarted =
                            true

                        AppLogger.i(
                            TAG,
                            "Muxer started: track=$track"
                        )
                    }
                }

                outIdx ==
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {

                    return muxerStarted
                }

                outIdx >= 0 -> {

                    val outBuf =
                        enc.getOutputBuffer(outIdx)
                            ?: throw IllegalStateException(
                                "Encoder output buffer is null"
                            )

                    val isConfig =
                        info.flags and
                                MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0

                    if (
                        !isConfig &&
                        info.size > 0 &&
                        muxerStarted
                    ) {

                        outBuf.position(
                            info.offset
                        )

                        outBuf.limit(
                            info.offset +
                                    info.size
                        )

                        mux.writeSampleData(
                            track,
                            outBuf,
                            info
                        )
                    }

                    val eos =
                        info.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                    enc.releaseOutputBuffer(
                        outIdx,
                        false
                    )

                    if (eos) {

                        AppLogger.i(
                            TAG,
                            "Encoder EOS received"
                        )

                        return muxerStarted
                    }
                }
            }
        }
    }

    override fun stop() {

        AppLogger.i(TAG, "==================================================")
        AppLogger.i(TAG, "Stopping DirectAudioRecorderSession")

        /*
         * STEP 1:
         * Ask capture loop to terminate.
         */
        stopRequested.set(true)

        /*
         * STEP 2:
         * Stop AudioRecord BEFORE joining the thread.
         */
        AppLogger.i(
            TAG,
            "Stopping AudioRecord to unblock read()"
        )

        runCatching {

            audioRecord?.stop()

        }.onFailure {

            AppLogger.w(
                TAG,
                "AudioRecord.stop() failed: ${it.message}"
            )
        }

        /*
         * STEP 3:
         * Wait for capture thread.
         */
        AppLogger.i(
            TAG,
            "Waiting for capture thread to finish"
        )

        runCatching {

            readThread?.join(
                READ_JOIN_MS
            )

        }.onFailure {

            AppLogger.w(
                TAG,
                "Capture thread join failed: ${it.message}"
            )
        }

        if (readThread?.isAlive == true) {

            AppLogger.w(
                TAG,
                "Capture thread is still alive after timeout"
            )
        }

        /*
         * STEP 4:
         * Stop PCM consumer.
         */
        AudioFrameTestWorker.stop()

        /*
         * STEP 5:
         * Release AudioRecord.
         */
        runCatching {

            audioRecord?.release()

        }.onFailure {

            AppLogger.w(
                TAG,
                "AudioRecord.release() failed: ${it.message}"
            )
        }

        audioRecord = null

        /*
         * STEP 6:
         * Stop and release encoder.
         */
        runCatching {

            encoder?.stop()

        }.onFailure {

            AppLogger.w(
                TAG,
                "Encoder.stop() failed: ${it.message}"
            )
        }

        runCatching {

            encoder?.release()

        }.onFailure {

            AppLogger.w(
                TAG,
                "Encoder.release() failed: ${it.message}"
            )
        }

        encoder = null

        /*
         * STEP 7:
         * Stop and release muxer.
         */
        runCatching {

            muxer?.stop()

        }.onFailure {

            AppLogger.w(
                TAG,
                "Muxer.stop() failed: ${it.message}"
            )
        }

        runCatching {

            muxer?.release()

        }.onFailure {

            AppLogger.w(
                TAG,
                "Muxer.release() failed: ${it.message}"
            )
        }

        muxer = null

        /*
         * STEP 8:
         * Close output FD.
         */
        runCatching {

            outFd.close()

        }.onFailure {

            AppLogger.w(
                TAG,
                "outFd.close() failed: ${it.message}"
            )
        }

        readThread = null

        AppLogger.i(
            TAG,
            "DirectAudioRecorderSession stopped"
        )

        AppLogger.i(TAG, "==================================================")
    }

    private fun cleanupPartial() {

        stopRequested.set(true)

        runCatching {
            audioRecord?.stop()
        }

        runCatching {
            readThread?.join(READ_JOIN_MS)
        }

        AudioFrameTestWorker.stop()

        runCatching {
            audioRecord?.release()
        }

        runCatching {
            encoder?.stop()
        }

        runCatching {
            encoder?.release()
        }

        runCatching {
            muxer?.stop()
        }

        runCatching {
            muxer?.release()
        }

        audioRecord = null
        encoder = null
        muxer = null
        readThread = null
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun openAudioRecord(
        androidSource: Int
    ): Pair<AudioRecord, Int> {

        for (
        channelMask in intArrayOf(
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.CHANNEL_IN_MONO
        )
        ) {

            val channels =
                if (
                    channelMask ==
                    AudioFormat.CHANNEL_IN_STEREO
                ) {
                    2
                } else {
                    1
                }

            val channelName =
                if (channels == 2) {
                    "stereo"
                } else {
                    "mono"
                }

            AppLogger.i(
                TAG,
                "Trying AudioRecord: " +
                        "source=$androidSource " +
                        "channels=$channelName"
            )

            val minBuf =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT
                )

            if (minBuf <= 0) {

                AppLogger.w(
                    TAG,
                    "AudioRecord.getMinBufferSize() failed: " +
                            "channels=$channelName " +
                            "minBuf=$minBuf"
                )

                continue
            }

            val requestedBuffer =
                minBuf * BUFFER_FACTOR

            val rec =
                runCatching {

                    AudioRecord(
                        androidSource,
                        SAMPLE_RATE,
                        channelMask,
                        AudioFormat.ENCODING_PCM_16BIT,
                        requestedBuffer
                    )

                }.onFailure {

                    AppLogger.w(
                        TAG,
                        "AudioRecord constructor failed: " +
                                "${it.javaClass.simpleName}: ${it.message}"
                    )

                }.getOrNull()

            if (
                rec != null &&
                rec.state ==
                AudioRecord.STATE_INITIALIZED
            ) {

                AppLogger.i(
                    TAG,
                    "AudioRecord initialized: " +
                            "channels=$channelName"
                )

                return rec to channels
            }

            runCatching {
                rec?.release()
            }
        }

        throw IllegalStateException(
            "AudioRecord would not initialise for source $androidSource"
        )
    }

    companion object {

        private const val TAG =
            "CV:DirectCapture"

        private const val SAMPLE_RATE =
            48_000

        private const val ENCODE_CHANNELS =
            1

        private const val READ_CHUNK_BYTES =
            4096

        private const val MAX_INPUT_SIZE =
            16_384

        private const val BUFFER_FACTOR =
            4

        private const val DEQUEUE_TIMEOUT_US =
            10_000L

        private const val END_OF_STREAM_TIMEOUT_US =
            100_000L

        private const val READ_JOIN_MS =
            2_000L

        private const val LOG_EVERY_READS =
            100L

        fun supports(
            source: ScrcpyAudioSource,
            codec: ScrcpyAudioCodec
        ): Boolean {

            if (source.androidAudioSource == null) {
                return false
            }

            val mime =
                encoderMimeFor(codec)

            return runCatching {

                hasEncoder(mime) &&
                        EncoderLimits.supportsFormat(
                            mime,
                            SAMPLE_RATE,
                            ENCODE_CHANNELS
                        )

            }.getOrDefault(false)
        }

        private fun encoderMimeFor(
            codec: ScrcpyAudioCodec
        ): String =
            when (codec) {

                ScrcpyAudioCodec.OPUS ->
                    MediaFormat.MIMETYPE_AUDIO_OPUS

                ScrcpyAudioCodec.AAC ->
                    MediaFormat.MIMETYPE_AUDIO_AAC
            }

        private fun hasEncoder(
            mime: String
        ): Boolean =
            MediaCodecList(
                MediaCodecList.REGULAR_CODECS
            ).codecInfos.any { info ->

                info.isEncoder &&
                        info.supportedTypes.any {
                            it.equals(
                                mime,
                                ignoreCase = true
                            )
                        }
            }
    }


}