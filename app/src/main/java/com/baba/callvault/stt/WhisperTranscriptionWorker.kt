package com.baba.callvault.stt

import com.baba.callvault.transcription.WhisperNative
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

object WhisperTranscriptionWorker {


    private const val TAG = "CV:WhisperWorker"

    /*
     * AudioRecord / DirectAudioRecorderSession produit actuellement
     * du PCM mono 48 kHz après downmix.
     *
     * Whisper travaille avec du mono 16 kHz.
     */
    private const val INPUT_SAMPLE_RATE = 48_000
    private const val WHISPER_SAMPLE_RATE = 16_000

    /*
     * 5 secondes d'audio envoyées à Whisper.
     */
    private const val CHUNK_SECONDS = 5
    private const val CHUNK_SAMPLES =
        WHISPER_SAMPLE_RATE * CHUNK_SECONDS

    private val running =
        AtomicBoolean(false)

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    var onTranscript: ((String) -> Unit)? = null

    fun start() {

        if (!running.compareAndSet(false, true)) {

            AppLogger.i(
                TAG,
                "WhisperTranscriptionWorker already running"
            )

            return
        }

        AppLogger.i(
            TAG,
            "=================================================="
        )

        AppLogger.i(
            TAG,
            "WhisperTranscriptionWorker STARTING"
        )

        workerThread =
            Thread {

                AppLogger.i(
                    TAG,
                    "WHISPER WORKER THREAD ENTER"
                )

                val audioBuffer =
                    FloatArray(CHUNK_SAMPLES)

                var position = 0

                var receivedFrames = 0L
                var receivedBytes = 0L
                var resampledSamples = 0L
                var transcribedChunks = 0L

                try {

                    while (running.get()) {

                        /*
                         * AudioFrameBus.take() bloque uniquement
                         * le worker STT, jamais AudioRecord.
                         */
                        val frame =
                            AudioFrameBus.take()

                        if (!running.get()) {
                            break
                        }

                        receivedFrames++
                        receivedBytes += frame.size.toLong()

                        /*
                         * PCM 16-bit little-endian mono 48 kHz
                         * -> Float mono 48 kHz.
                         */
                        val inputSamples =
                            pcm16ToFloat(frame)

                        if (inputSamples.isEmpty()) {
                            continue
                        }

                        /*
                         * 48 kHz -> 16 kHz.
                         *
                         * Le rapport est exactement 3:1.
                         */
                        val samples =
                            downsample48kTo16k(
                                inputSamples
                            )

                        resampledSamples +=
                            samples.size.toLong()

                        var offset = 0

                        while (
                            offset < samples.size &&
                            running.get()
                        ) {

                            val remaining =
                                CHUNK_SAMPLES - position

                            val available =
                                samples.size - offset

                            val copyCount =
                                minOf(
                                    remaining,
                                    available
                                )

                            System.arraycopy(
                                samples,
                                offset,
                                audioBuffer,
                                position,
                                copyCount
                            )

                            position += copyCount
                            offset += copyCount

                            if (
                                position >= CHUNK_SAMPLES
                            ) {

                                transcribedChunks++

                                AppLogger.i(
                                    TAG,
                                    "Sending Whisper chunk #" +
                                            "$transcribedChunks " +
                                            "samples=$CHUNK_SAMPLES"
                                )

                                transcribe(
                                    audioBuffer.copyOf()
                                )

                                position = 0
                            }
                        }

                        if (
                            receivedFrames == 1L
                        ) {

                            AppLogger.i(
                                TAG,
                                "FIRST PCM FRAME CONSUMED: " +
                                        "bytes=${frame.size} " +
                                        "inputSamples=${inputSamples.size} " +
                                        "outputSamples=${samples.size}"
                            )
                        }

                        if (
                            receivedFrames % 100L == 0L
                        ) {

                            AppLogger.i(
                                TAG,
                                "WHISPER PCM FLOW: " +
                                        "frames=$receivedFrames " +
                                        "bytes=$receivedBytes " +
                                        "resampledSamples=$resampledSamples " +
                                        "chunks=$transcribedChunks " +
                                        "busQueue=${AudioFrameBus.queueSize()}"
                            )
                        }
                    }

                } catch (_: InterruptedException) {

                    AppLogger.i(
                        TAG,
                        "WHISPER WORKER INTERRUPTED"
                    )

                } catch (t: Throwable) {

                    AppLogger.w(
                        TAG,
                        "WHISPER WORKER ERROR: " +
                                "${t.javaClass.simpleName}: ${t.message}"
                    )

                } finally {

                    AppLogger.i(
                        TAG,
                        "WHISPER WORKER THREAD EXIT"
                    )
                }

            }.apply {

                name =
                    "whisper-transcription"

                isDaemon =
                    true

                start()
            }

        AppLogger.i(
            TAG,
            "WhisperTranscriptionWorker STARTED"
        )

        AppLogger.i(
            TAG,
            "=================================================="
        )
    }

    fun stop() {

        if (!running.compareAndSet(true, false)) {
            return
        }

        AppLogger.i(
            TAG,
            "Stopping WhisperTranscriptionWorker"
        )

        workerThread?.interrupt()

        runCatching {

            workerThread?.join(
                1_000L
            )

        }.onFailure {

            AppLogger.w(
                TAG,
                "Worker join failed: ${it.message}"
            )
        }

        workerThread = null

        AppLogger.i(
            TAG,
            "WhisperTranscriptionWorker stopped"
        )
    }

    /**
     * Convertit du PCM 16-bit little-endian
     * en FloatArray [-1, +1].
     */
    private fun pcm16ToFloat(
        pcm: ByteArray
    ): FloatArray {

        val sampleCount =
            pcm.size / 2

        val output =
            FloatArray(sampleCount)

        var sampleIndex = 0
        var byteIndex = 0

        while (
            byteIndex + 1 < pcm.size
        ) {

            val low =
                pcm[byteIndex]
                    .toInt() and 0xFF

            val high =
                pcm[byteIndex + 1]
                    .toInt()

            val sample =
                (high shl 8) or low

            output[sampleIndex] =
                sample / 32768.0f

            sampleIndex++
            byteIndex += 2
        }

        return output
    }

    /**
     * Ré-échantillonnage simple 48 kHz -> 16 kHz.
     *
     * Le rapport étant exactement 3:1, on conserve un échantillon
     * sur trois. Pour une première intégration STT cela est suffisant
     * et évite de modifier la branche AudioRecord/encodeur.
     */
    private fun downsample48kTo16k(
        input: FloatArray
    ): FloatArray {

        if (input.isEmpty()) {
            return FloatArray(0)
        }

        val outputSize =
            (input.size + 2) / 3

        val output =
            FloatArray(outputSize)

        var inputIndex = 0
        var outputIndex = 0

        while (
            inputIndex < input.size &&
            outputIndex < output.size
        ) {

            output[outputIndex] =
                input[inputIndex]

            inputIndex += 3
            outputIndex++
        }

        return output
    }

    private fun transcribe(
        audio: FloatArray
    ) {

        try {

            AppLogger.i(
                TAG,
                "Calling Whisper nativeTranscribe(): " +
                        "samples=${audio.size} " +
                        "durationMs=${audio.size * 1000L / WHISPER_SAMPLE_RATE}"
            )

            val text =
                WhisperNative.nativeTranscribe(
                    audio
                ).trim()

            if (text.isNotEmpty()) {

                AppLogger.i(
                    TAG,
                    "WHISPER TRANSCRIPT: $text"
                )

                runCatching {
                    onTranscript?.invoke(text)
                }.onFailure {

                    AppLogger.w(
                        TAG,
                        "Transcript callback failed: " +
                                it.message
                    )
                }

            } else {

                AppLogger.i(
                    TAG,
                    "Whisper returned empty text"
                )
            }

        } catch (t: Throwable) {

            AppLogger.w(
                TAG,
                "Whisper transcription failed: " +
                        "${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }


}
