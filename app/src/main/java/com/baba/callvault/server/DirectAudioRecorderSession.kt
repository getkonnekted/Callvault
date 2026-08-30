/*

* CallVault: FOSS call recording, self-contained over embedded ADB
* Copyright (C) 2026-present The CallVault Authors
*
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
import com.baba.callvault.stt.WhisperTranscriptionWorker
import com.baba.callvault.utils.AppLogger
import com.baba.callvault.utils.PcmDownmix
import java.util.concurrent.atomic.AtomicBoolean

internal class DirectAudioRecorderSession(
    private val source: ScrcpyAudioSource,
    private val codec: ScrcpyAudioCodec,
    private val bitRate: Int,
    private val outFd: ParcelFileDescriptor,
) : RecordingSession {


    private val stopRequested =
        AtomicBoolean(false)

    @Volatile
    private var audioRecord: AudioRecord? =
        null

    @Volatile
    private var encoder: MediaCodec? =
        null

    @Volatile
    private var muxer: MediaMuxer? =
        null

    @Volatile
    private var readThread: Thread? =
        null

    /**
     * Etat du muxer partagé avec la boucle de capture.
     *
     * IMPORTANT :
     * Le numéro de piste ne doit pas être recréé à chaque appel
     * de drainEncoder().
     */
    private var muxerStarted =
        false

    private var muxerTrack =
        -1

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() {

        AppLogger.i(TAG, "==================================================")
        AppLogger.i(TAG, "DirectAudioRecorderSession.start() ENTER")

        try {

            stopRequested.set(false)

            muxerStarted = false
            muxerTrack = -1

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

            runCatching {
                audioRecord?.stop()
            }

            runCatching {
                readThread?.join(READ_JOIN_MS)
            }

            WhisperTranscriptionWorker.stop()

            cleanupPartial()

            throw t
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startInternal() {

        AppLogger.i(
            TAG,
            "Direct capture initialization started"
        )

        val androidSource =
            source.androidAudioSource
                ?: throw UnsupportedOperationException(
                    "source ${source.cliKey} is not a mic-type source"
                )

        val mime =
            encoderMimeFor(codec)

        AppLogger.i(
            TAG,
            "Resolved Android audio source: $androidSource"
        )

        AppLogger.i(
            TAG,
            "Encoder MIME: $mime"
        )

        /*
         * ---------------------------------------------------------
         * AudioRecord
         * ---------------------------------------------------------
         */

        val (record, captureChannels) =
            openAudioRecord(androidSource)

        audioRecord = record

        AppLogger.i(
            TAG,
            "AudioRecord initialized successfully: " +
                    "channels=$captureChannels " +
                    "sampleRate=$CAPTURE_SAMPLE_RATE"
        )

        /*
         * ---------------------------------------------------------
         * MediaCodec encoder
         * ---------------------------------------------------------
         */

        val effectiveBitRate =
            EncoderLimits.resolveBitRate(
                mime,
                bitRate,
                CAPTURE_SAMPLE_RATE,
                ENCODE_CHANNELS
            )

        val format =
            MediaFormat.createAudioFormat(
                mime,
                CAPTURE_SAMPLE_RATE,
                ENCODE_CHANNELS
            ).apply {

                setInteger(
                    MediaFormat.KEY_BIT_RATE,
                    effectiveBitRate
                )

                if (
                    mime ==
                    MediaFormat.MIMETYPE_AUDIO_AAC
                ) {

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

        /*
         * ---------------------------------------------------------
         * MediaMuxer
         * ---------------------------------------------------------
         */

        val localMuxer =
            MediaMuxer(
                outFd.fileDescriptor,
                codec.outputFormat
            )

        muxer = localMuxer

        /*
         * ---------------------------------------------------------
         * Start encoder
         * ---------------------------------------------------------
         */

        enc.start()

        AppLogger.i(
            TAG,
            "MediaCodec started"
        )

        /*
         * ---------------------------------------------------------
         * Start Whisper PCM pipeline
         * ---------------------------------------------------------
         *
         * IMPORTANT:
         *
         * WhisperTranscriptionWorker expects:
         *
         * - mono
         * - PCM 16-bit
         * - 16 kHz
         *
         * The recording pipeline uses 48 kHz mono.
         *
         * We therefore publish a downsampled copy to AudioFrameBus.
         */

        AudioFrameBus.clear()

        WhisperTranscriptionWorker.start()

        AppLogger.i(
            TAG,
            "WhisperTranscriptionWorker started"
        )

        /*
         * ---------------------------------------------------------
         * Start AudioRecord
         * ---------------------------------------------------------
         */

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
                    "captureRate=$CAPTURE_SAMPLE_RATE " +
                    "whisperRate=$WHISPER_SAMPLE_RATE"
        )

        AppLogger.i(
            TAG,
            "AudioFrameBus -> Whisper branch is ACTIVE"
        )

        AppLogger.i(TAG, "==================================================")

        /*
         * ---------------------------------------------------------
         * Start capture thread
         * ---------------------------------------------------------
         */

        readThread =
            Thread {

                AppLogger.i(
                    TAG,
                    "direct-capture thread ENTER"
                )

                runCatching {

                    captureLoop(
                        record = record,
                        enc = enc,
                        mux = localMuxer,
                        captureChannels = captureChannels
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

                name =
                    "direct-capture"

                isDaemon =
                    true

                start()
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

        /*
         * Raw AudioRecord PCM.
         */
        val pcm =
            ByteArray(
                READ_CHUNK_BYTES
            )

        /*
         * Mono PCM at 48 kHz.
         */
        val mono =
            ByteArray(
                READ_CHUNK_BYTES / 2
            )

        /*
         * Buffer for Whisper PCM at 16 kHz.
         *
         * 48 kHz -> 16 kHz = division by 3.
         */
        val whisperPcm =
            ByteArray(
                READ_CHUNK_BYTES / 3
            )

        val downmix =
            captureChannels == 2

        val info =
            MediaCodec.BufferInfo()

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
            PCM_BYTES_PER_SAMPLE *
                    ENCODE_CHANNELS

        while (!stopRequested.get()) {

            val read =
                record.read(
                    pcm,
                    0,
                    pcm.size
                )

            /*
             * AudioRecord.stop() can cause read() to fail.
             *
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
             * -----------------------------------------------------
             * Convert capture PCM to mono if necessary.
             * -----------------------------------------------------
             */

            val (encodeBuffer, encodeLength) =
                if (downmix) {

                    mono to PcmDownmix.stereoToMono(
                        pcm,
                        read,
                        mono
                    )

                } else {

                    pcm to read
                }

            if (encodeLength <= 0) {
                continue
            }

            /*
             * -----------------------------------------------------
             * STT branch
             *
             * Recording remains at 48 kHz.
             *
             * Whisper receives mono 16 kHz.
             * -----------------------------------------------------
             */

            val whisperLength =
                downsample48kTo16k(
                    input = encodeBuffer,
                    inputLength = encodeLength,
                    output = whisperPcm
                )

            if (whisperLength > 0) {

                AudioFrameBus.publish(
                    whisperPcm,
                    whisperLength
                )

                totalPublishedBytes +=
                    whisperLength.toLong()
            }

            /*
             * -----------------------------------------------------
             * Diagnostics
             * -----------------------------------------------------
             */

            if (readCount == 1L) {

                AppLogger.i(
                    TAG,
                    "FIRST REAL PCM BUFFER RECEIVED: " +
                            "read=$read " +
                            "encode=$encodeLength " +
                            "whisper=$whisperLength " +
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
                            "whisperBytes=$totalPublishedBytes " +
                            "busReceived=${AudioFrameBus.receivedCount()} " +
                            "busConsumed=${AudioFrameBus.consumedCount()} " +
                            "busDropped=${AudioFrameBus.droppedCount()} " +
                            "busQueue=${AudioFrameBus.queueSize()} " +
                            "errorReads=$errorReadCount"
                )
            }

            /*
             * -----------------------------------------------------
             * Recording / encoder branch
             * -----------------------------------------------------
             */

            queueEncoderInput(
                enc = enc,
                data = encodeBuffer,
                length = encodeLength,
                totalFrames = totalFrames
            )

            totalFrames +=
                encodeLength / bytesPerFrame

            /*
             * Drain encoder continuously.
             */

            drainEncoder(
                enc = enc,
                mux = mux,
                info = info,
                drainToEos = false
            )
        }

        AppLogger.i(
            TAG,
            "captureLoop stopping: " +
                    "reads=$readCount " +
                    "pcmBytes=$totalPcmBytes " +
                    "whisperBytes=$totalPublishedBytes"
        )

        /*
         * ---------------------------------------------------------
         * Signal EOS to encoder.
         * ---------------------------------------------------------
         */

        signalEncoderEos(
            enc
        )

        /*
         * ---------------------------------------------------------
         * Drain until EOS.
         * ---------------------------------------------------------
         */

        drainEncoder(
            enc = enc,
            mux = mux,
            info = info,
            drainToEos = true
        )

        AppLogger.i(
            TAG,
            "captureLoop() EXIT"
        )
    }

    private fun queueEncoderInput(
        enc: MediaCodec,
        data: ByteArray,
        length: Int,
        totalFrames: Long
    ) {

        val inputIndex =
            enc.dequeueInputBuffer(
                DEQUEUE_TIMEOUT_US
            )

        if (inputIndex < 0) {
            return
        }

        val inputBuffer =
            enc.getInputBuffer(inputIndex)
                ?: throw IllegalStateException(
                    "Encoder input buffer is null"
                )

        inputBuffer.clear()

        val writable =
            minOf(
                length,
                inputBuffer.remaining()
            )

        if (writable > 0) {

            inputBuffer.put(
                data,
                0,
                writable
            )
        }

        val ptsUs =
            totalFrames *
                    1_000_000L /
                    CAPTURE_SAMPLE_RATE

        enc.queueInputBuffer(
            inputIndex,
            0,
            writable,
            ptsUs,
            0
        )
    }

    private fun signalEncoderEos(
        enc: MediaCodec
    ) {

        val deadline =
            System.nanoTime() +
                    EOS_INPUT_TIMEOUT_MS * 1_000_000L

        while (System.nanoTime() < deadline) {

            val inputIndex =
                enc.dequeueInputBuffer(
                    END_OF_STREAM_TIMEOUT_US
                )

            if (inputIndex >= 0) {

                enc.queueInputBuffer(
                    inputIndex,
                    0,
                    0,
                    0,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )

                AppLogger.i(
                    TAG,
                    "Encoder EOS queued"
                )

                return
            }
        }

        AppLogger.w(
            TAG,
            "Unable to obtain encoder input buffer for EOS"
        )
    }

    private fun drainEncoder(
        enc: MediaCodec,
        mux: MediaMuxer,
        info: MediaCodec.BufferInfo,
        drainToEos: Boolean
    ) {

        while (true) {

            val timeoutUs =
                if (drainToEos) {
                    END_OF_STREAM_TIMEOUT_US
                } else {
                    0L
                }

            val outputIndex =
                enc.dequeueOutputBuffer(
                    info,
                    timeoutUs
                )

            when {

                outputIndex ==
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {

                    if (!muxerStarted) {

                        muxerTrack =
                            mux.addTrack(
                                enc.outputFormat
                            )

                        mux.start()

                        muxerStarted =
                            true

                        AppLogger.i(
                            TAG,
                            "Muxer started: track=$muxerTrack"
                        )
                    }
                }

                outputIndex ==
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {

                    if (!drainToEos) {
                        return
                    }

                    /*
                     * During EOS draining, keep waiting.
                     */
                    continue
                }

                outputIndex >= 0 -> {

                    val outputBuffer =
                        enc.getOutputBuffer(
                            outputIndex
                        )
                            ?: throw IllegalStateException(
                                "Encoder output buffer is null"
                            )

                    val isCodecConfig =
                        info.flags and
                                MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0

                    if (isCodecConfig) {

                        info.size = 0
                    }

                    if (
                        info.size > 0 &&
                        muxerStarted &&
                        muxerTrack >= 0
                    ) {

                        outputBuffer.position(
                            info.offset
                        )

                        outputBuffer.limit(
                            info.offset + info.size
                        )

                        mux.writeSampleData(
                            muxerTrack,
                            outputBuffer,
                            info
                        )
                    }

                    val endOfStream =
                        info.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

                    enc.releaseOutputBuffer(
                        outputIndex,
                        false
                    )

                    if (endOfStream) {

                        AppLogger.i(
                            TAG,
                            "Encoder EOS received"
                        )

                        return
                    }
                }
            }
        }
    }

    /**
     * Downsample simple 48 kHz mono PCM16 -> 16 kHz mono PCM16.
     *
     * Le ratio est exactement 3:1.
     *
     * Pour une qualité maximale, un filtre passe-bas devrait être ajouté
     * avant le sous-échantillonnage. Pour la transcription vocale,
     * cette version simple constitue un point de départ fiable.
     */
    private fun downsample48kTo16k(
        input: ByteArray,
        inputLength: Int,
        output: ByteArray
    ): Int {

        val inputSamples =
            inputLength / PCM_BYTES_PER_SAMPLE

        val outputSamples =
            minOf(
                inputSamples / WHISPER_DOWNSAMPLE_FACTOR,
                output.size / PCM_BYTES_PER_SAMPLE
            )

        var outputByteIndex =
            0

        var outputSample =
            0

        while (outputSample < outputSamples) {

            val inputSample =
                outputSample *
                        WHISPER_DOWNSAMPLE_FACTOR

            val inputByteIndex =
                inputSample *
                        PCM_BYTES_PER_SAMPLE

            if (
                inputByteIndex + 1 >= inputLength
            ) {
                break
            }

            /*
             * PCM little-endian:
             *
             * Copy one sample every 3 samples.
             */

            output[outputByteIndex] =
                input[inputByteIndex]

            output[outputByteIndex + 1] =
                input[inputByteIndex + 1]

            outputByteIndex +=
                PCM_BYTES_PER_SAMPLE

            outputSample++
        }

        return outputByteIndex
    }

    override fun stop() {

        AppLogger.i(TAG, "==================================================")
        AppLogger.i(TAG, "Stopping DirectAudioRecorderSession")

        /*
         * ---------------------------------------------------------
         * STEP 1:
         * Ask capture loop to terminate.
         * ---------------------------------------------------------
         */

        stopRequested.set(true)

        /*
         * ---------------------------------------------------------
         * STEP 2:
         * Stop AudioRecord to unblock read().
         * ---------------------------------------------------------
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
         * ---------------------------------------------------------
         * STEP 3:
         * Wait for capture thread.
         * ---------------------------------------------------------
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
         * ---------------------------------------------------------
         * STEP 4:
         * Stop Whisper worker.
         * ---------------------------------------------------------
         */

        WhisperTranscriptionWorker.stop()

        /*
         * ---------------------------------------------------------
         * STEP 5:
         * Release AudioRecord.
         * ---------------------------------------------------------
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
         * ---------------------------------------------------------
         * STEP 6:
         * Release encoder.
         *
         * The capture thread already attempted to drain EOS.
         * ---------------------------------------------------------
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
         * ---------------------------------------------------------
         * STEP 7:
         * Stop muxer only if it was actually started.
         * ---------------------------------------------------------
         */

        if (muxerStarted) {

            runCatching {

                muxer?.stop()

            }.onFailure {

                AppLogger.w(
                    TAG,
                    "Muxer.stop() failed: ${it.message}"
                )
            }
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

        muxerStarted = false
        muxerTrack = -1

        /*
         * ---------------------------------------------------------
         * STEP 8:
         * Close output FD.
         * ---------------------------------------------------------
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
            readThread?.join(
                READ_JOIN_MS
            )
        }

        WhisperTranscriptionWorker.stop()

        runCatching {
            audioRecord?.release()
        }

        runCatching {
            encoder?.stop()
        }

        runCatching {
            encoder?.release()
        }

        if (muxerStarted) {

            runCatching {
                muxer?.stop()
            }
        }

        runCatching {
            muxer?.release()
        }

        audioRecord = null
        encoder = null
        muxer = null
        readThread = null

        muxerStarted = false
        muxerTrack = -1
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

            val minBufferSize =
                AudioRecord.getMinBufferSize(
                    CAPTURE_SAMPLE_RATE,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT
                )

            if (minBufferSize <= 0) {

                AppLogger.w(
                    TAG,
                    "AudioRecord.getMinBufferSize() failed: " +
                            "channels=$channelName " +
                            "minBuf=$minBufferSize"
                )

                continue
            }

            val requestedBuffer =
                maxOf(
                    minBufferSize * BUFFER_FACTOR,
                    READ_CHUNK_BYTES * BUFFER_FACTOR
                )

            val record =
                runCatching {

                    AudioRecord(
                        androidSource,
                        CAPTURE_SAMPLE_RATE,
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
                record != null &&
                record.state ==
                AudioRecord.STATE_INITIALIZED
            ) {

                AppLogger.i(
                    TAG,
                    "AudioRecord initialized: " +
                            "channels=$channelName " +
                            "buffer=$requestedBuffer"
                )

                return record to channels
            }

            runCatching {
                record?.release()
            }
        }

        throw IllegalStateException(
            "AudioRecord would not initialise for source $androidSource"
        )
    }

    companion object {

        private const val TAG =
            "CV:DirectCapture"

        /*
         * Recording sample rate.
         */
        private const val CAPTURE_SAMPLE_RATE =
            48_000

        /*
         * Whisper worker sample rate.
         */
        private const val WHISPER_SAMPLE_RATE =
            16_000

        /*
         * 48 kHz -> 16 kHz.
         */
        private const val WHISPER_DOWNSAMPLE_FACTOR =
            CAPTURE_SAMPLE_RATE /
                    WHISPER_SAMPLE_RATE

        private const val ENCODE_CHANNELS =
            1

        private const val PCM_BYTES_PER_SAMPLE =
            2

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

        private const val EOS_INPUT_TIMEOUT_MS =
            2_000L

        private const val READ_JOIN_MS =
            2_000L

        private const val LOG_EVERY_READS =
            100L

        fun supports(
            source: ScrcpyAudioSource,
            codec: ScrcpyAudioCodec
        ): Boolean {

            if (
                source.androidAudioSource == null
            ) {
                return false
            }

            val mime =
                encoderMimeFor(codec)

            return runCatching {

                hasEncoder(mime) &&
                        EncoderLimits.supportsFormat(
                            mime,
                            CAPTURE_SAMPLE_RATE,
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
