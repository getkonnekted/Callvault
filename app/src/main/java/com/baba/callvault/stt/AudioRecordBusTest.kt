package com.baba.callvault.stt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
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
 */
object AudioRecordBusTest {

    private const val TAG = "CV:AudioRecordBusTest"

    private const val SAMPLE_RATE = 48_000

    private const val CHANNEL_MASK =
        AudioFormat.CHANNEL_IN_MONO

    private const val ENCODING =
        AudioFormat.ENCODING_PCM_16BIT

    private const val BUFFER_FACTOR = 4

    private const val READ_SIZE = 4096

    private const val LOG_EVERY_READS = 100L

    private val running = AtomicBoolean(false)

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var thread: Thread? = null

    /**
     * Vérifie que RECORD_AUDIO est accordée.
     */
    private fun hasRecordAudioPermission(
        context: Context
    ): Boolean {

        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Démarre le test AudioRecord -> AudioFrameBus.
     */
    fun start(context: Context) {

        if (!running.compareAndSet(false, true)) {

            AppLogger.w(
                TAG,
                "AudioRecordBusTest already running"
            )

            return
        }

        val appContext = context.applicationContext

        /*
         * Vérification de permission.
         */
        if (!hasRecordAudioPermission(appContext)) {

            AppLogger.w(
                TAG,
                "RECORD_AUDIO permission is NOT granted"
            )

            running.set(false)

            throw SecurityException(
                "RECORD_AUDIO permission is required"
            )
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
            "RECORD_AUDIO permission granted"
        )

        try {

            /*
             * ----------------------------------------------------------
             * Configuration AudioRecord
             * ----------------------------------------------------------
             */

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

            /*
             * ----------------------------------------------------------
             * Création AudioRecord
             * ----------------------------------------------------------
             */

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

            audioRecord = record

            AppLogger.i(
                TAG,
                "AudioRecord initialized successfully"
            )

            /*
             * ----------------------------------------------------------
             * AudioFrameBus
             * ----------------------------------------------------------
             */

            AudioFrameBus.clear()

            AppLogger.i(
                TAG,
                "AudioFrameBus cleared"
            )

            AudioFrameTestWorker.start()

            AppLogger.i(
                TAG,
                "AudioFrameTestWorker started"
            )

            /*
             * ----------------------------------------------------------
             * Démarrage de la capture
             * ----------------------------------------------------------
             */

            try {

                record.startRecording()

            } catch (security: SecurityException) {

                AppLogger.w(
                    TAG,
                    "AudioRecord.startRecording() denied: " +
                            security.message
                )

                record.release()

                throw security
            }

            if (
                record.recordingState !=
                AudioRecord.RECORDSTATE_RECORDING
            ) {

                record.release()

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
                "AudioRecord -> AudioFrameBus"
            )

            AppLogger.i(
                TAG,
                "=============================================="
            )

            /*
             * ----------------------------------------------------------
             * Thread de capture
             * ----------------------------------------------------------
             */

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

        } catch (security: SecurityException) {

            AppLogger.w(
                TAG,
                "AudioRecordBusTest.start() PERMISSION DENIED: " +
                        security.message
            )

            cleanup()

            throw security

        } catch (t: Throwable) {

            AppLogger.w(
                TAG,
                "AudioRecordBusTest.start() FAILED: " +
                        "${t.javaClass.simpleName}: ${t.message}"
            )

            cleanup()

            throw t
        }
    }

    /**
     * Boucle de capture PCM.
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

        var reads = 0L
        var pcmBytes = 0L
        var publishedBytes = 0L
        var errors = 0L
        var firstBufferLogged = false

        while (running.get()) {

            val read =
                try {

                    record.read(
                        pcm,
                        0,
                        pcm.size
                    )

                } catch (t: Throwable) {

                    errors++

                    AppLogger.w(
                        TAG,
                        "AudioRecord.read() exception: " +
                                "${t.javaClass.simpleName}: ${t.message}"
                    )

                    break
                }

            reads++

            if (read < 0) {

                errors++

                AppLogger.w(
                    TAG,
                    "AudioRecord.read() ERROR: read=$read"
                )

                continue
            }

            if (read == 0) {

                continue
            }

            pcmBytes +=
                read.toLong()

            /*
             * AudioFrameBus effectue sa propre copie.
             */
            AudioFrameBus.publish(
                pcm,
                read
            )

            publishedBytes +=
                read.toLong()

            /*
             * Premier buffer reçu.
             */
            if (!firstBufferLogged) {

                firstBufferLogged = true

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
                    "AudioRecord -> AudioFrameBus CONFIRMED"
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
     * Arrête proprement le test.
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
         * Arrêt du AudioRecord AVANT le join.
         *
         * Cela permet de débloquer read() plus rapidement
         * sur certains appareils.
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

            thread?.join(
                1_000L
            )

        }.onFailure {

            AppLogger.w(
                TAG,
                "Test thread join failed: ${it.message}"
            )
        }

        AudioFrameTestWorker.stop()

        runCatching {

            audioRecord?.release()

        }.onFailure {

            AppLogger.w(
                TAG,
                "AudioRecord.release() failed: ${it.message}"
            )
        }

        audioRecord = null
        thread = null

        AppLogger.i(
            TAG,
            "AudioRecordBusTest stopped"
        )
    }

    /**
     * Nettoyage utilisé lorsqu'un démarrage échoue.
     */
    private fun cleanup() {

        running.set(false)

        AudioFrameTestWorker.stop()

        runCatching {
            audioRecord?.stop()
        }

        runCatching {
            audioRecord?.release()
        }

        audioRecord = null
        thread = null
    }
}