package com.baba.callvault.stt

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.baba.callvault.utils.AppLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Test autonome :
 *
 * AudioRecord
 *      ↓
 * PCM
 *      ↓
 * AudioFrameBus
 *
 * Ce test ne dépend PAS d'un appel téléphonique.
 *
 * Il sert uniquement à vérifier que :
 *
 * 1. AudioRecord peut capturer du PCM
 * 2. la boucle read() reçoit effectivement des données
 * 3. AudioFrameBus reçoit ces données
 *
 * Aucun MediaCodec / MediaMuxer / scrcpy n'est utilisé ici.
 */
object AudioRecordBusTest {

    private const val TAG =
        "CV:AudioRecordBusTest"

    private const val SAMPLE_RATE =
        48_000

    private const val CHANNEL_MASK =
        AudioFormat.CHANNEL_IN_MONO

    private const val ENCODING =
        AudioFormat.ENCODING_PCM_16BIT

    private const val BUFFER_FACTOR =
        4

    private const val READ_SIZE =
        4096

    private const val LOG_EVERY_READS =
        100L

    private val running =
        AtomicBoolean(false)

    @Volatile
    private var audioRecord: AudioRecord? =
        null

    @Volatile
    private var thread: Thread? =
        null

    /**
     * Démarre la capture de test.
     *
     * Cette méthode ne doit jamais être appelée deux fois
     * simultanément.
     */
    fun start() {

        if (!running.compareAndSet(false, true)) {

            AppLogger.w(
                TAG,
                "AudioRecordBusTest already running"
            )

            return
        }

        AppLogger.i(
            TAG,
            "=============================================="
        )

        AppLogger.i(
            TAG,
            "AudioRecordBusTest.start()"
        )

        AppLogger.i(
            TAG,
            "Opening microphone AudioRecord"
        )

        try {

            val minBuffer =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_MASK,
                    ENCODING
                )

            if (minBuffer <= 0) {

                throw IllegalStateException(
                    "AudioRecord.getMinBufferSize() failed: $minBuffer"
                )
            }

            val bufferSize =
                maxOf(
                    minBuffer * BUFFER_FACTOR,
                    READ_SIZE * 2
                )

            AppLogger.i(
                TAG,
                "AudioRecord configuration: " +
                        "sampleRate=$SAMPLE_RATE " +
                        "channels=mono " +
                        "encoding=PCM_16BIT " +
                        "minBuffer=$minBuffer " +
                        "bufferSize=$bufferSize"
            )

            val record =
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_MASK,
                    ENCODING,
                    bufferSize
                )

            if (
                record.state !=
                AudioRecord.STATE_INITIALIZED
            ) {

                record.release()

                throw IllegalStateException(
                    "AudioRecord failed to initialize: " +
                            "state=${record.state}"
                )
            }

            audioRecord =
                record

            AppLogger.i(
                TAG,
                "AudioRecord initialized successfully"
            )

            AudioFrameBus.clear()

            AppLogger.i(
                TAG,
                "AudioFrameBus cleared"
            )

            /*
             * Le worker consommateur est démarré avant
             * AudioRecord.startRecording().
             */
            AppLogger.i(
                TAG,
                "Starting AudioFrameTestWorker"
            )

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

            AppLogger.i(
                TAG,
                "=============================================="
            )

            AppLogger.i(
                TAG,
                "TEST AUDIORECORD IS NOW RUNNING"
            )

            AppLogger.i(
                TAG,
                "AudioRecord → AudioFrameBus"
            )

            AppLogger.i(
                TAG,
                "=============================================="
            )

            thread =
                Thread {

                    Process.setThreadPriority(
                        Process.THREAD_PRIORITY_AUDIO
                    )

                    captureLoop(record)

                }.apply {

                    name =
                        "audio-record-bus-test"

                    isDaemon =
                        true

                }.also {

                    it.start()
                }

        } catch (t: Throwable) {

            AppLogger.w(
                TAG,
                "AudioRecordBusTest.start() FAILED: " +
                        "${t.javaClass.simpleName}: ${t.message}"
            )

            running.set(false)

            AudioFrameTestWorker.stop()

            runCatching {
                audioRecord?.stop()
            }

            runCatching {
                audioRecord?.release()
            }

            audioRecord =
                null

            throw t
        }
    }

    /**
     * Boucle AudioRecord de test.
     */
    private fun captureLoop(
        record: AudioRecord
    ) {

        AppLogger.i(
            TAG,
            "captureLoop() ENTER"
        )

        val pcm =
            ByteArray(
                READ_SIZE
            )

        var reads =
            0L

        var pcmBytes =
            0L

        var publishedBytes =
            0L

        var errors =
            0L

        var firstBufferLogged =
            false

        while (running.get()) {

            val read =
                record.read(
                    pcm,
                    0,
                    pcm.size
                )

            reads++

            if (read < 0) {

                errors++

                AppLogger.w(
                    TAG,
                    "AudioRecord.read() ERROR: " +
                            "read=$read"
                )

                continue
            }

            if (read == 0) {

                continue
            }

            pcmBytes +=
                read.toLong()

            /*
             * IMPORTANT :
             *
             * AudioFrameBus.publish() fait lui-même
             * une copie du buffer.
             *
             * Le ByteArray 'pcm' peut donc être
             * réutilisé au prochain read().
             */
            AudioFrameBus.publish(
                pcm,
                read
            )

            publishedBytes +=
                read.toLong()

            if (!firstBufferLogged) {

                firstBufferLogged =
                    true

                AppLogger.i(
                    TAG,
                    "=============================================="
                )

                AppLogger.i(
                    TAG,
                    "FIRST TEST PCM BUFFER RECEIVED"
                )

                AppLogger.i(
                    TAG,
                    "read=$read"
                )

                AppLogger.i(
                    TAG,
                    "published=$read"
                )

                AppLogger.i(
                    TAG,
                    "AudioRecord → AudioFrameBus CONFIRMED"
                )

                AppLogger.i(
                    TAG,
                    "=============================================="
                )
            }

            if (
                reads % LOG_EVERY_READS == 0L
            ) {

                AppLogger.i(
                    TAG,
                    "TEST PCM flowing: " +
                            "reads=$reads " +
                            "pcmBytes=$pcmBytes " +
                            "publishedBytes=$publishedBytes " +
                            "busReceived=${AudioFrameBus.receivedCount()} " +
                            "busDropped=${AudioFrameBus.droppedCount()} " +
                            "errors=$errors"
                )
            }
        }

        AppLogger.i(
            TAG,
            "captureLoop() EXIT: " +
                    "reads=$reads " +
                    "pcmBytes=$pcmBytes " +
                    "publishedBytes=$publishedBytes " +
                    "errors=$errors"
        )
    }

    /**
     * Arrête le test.
     */
    fun stop() {

        if (!running.compareAndSet(true, false)) {

            AppLogger.i(
                TAG,
                "AudioRecordBusTest is not running"
            )

            return
        }

        AppLogger.i(
            TAG,
            "Stopping AudioRecordBusTest"
        )

        /*
         * Laisse la boucle sortir naturellement.
         */
        runCatching {

            thread?.join(
                1_000L
            )

        }.onFailure {

            AppLogger.w(
                TAG,
                "Test thread join failed: ${it.message}"
            )
        }

        /*
         * Arrête maintenant le consommateur.
         */
        AudioFrameTestWorker.stop()

        /*
         * Stop/release AudioRecord.
         */
        runCatching {

            audioRecord?.stop()

        }.onFailure {

            AppLogger.w(
                TAG,
                "AudioRecord.stop() failed: ${it.message}"
            )
        }

        runCatching {

            audioRecord?.release()

        }.onFailure {

            AppLogger.w(
                TAG,
                "AudioRecord.release() failed: ${it.message}"
            )
        }

        audioRecord =
            null

        thread =
            null

        AppLogger.i(
            TAG,
            "AudioRecordBusTest stopped"
        )
    }
}