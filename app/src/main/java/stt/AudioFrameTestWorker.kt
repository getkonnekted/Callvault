package com.baba.callvault.stt

import com.baba.callvault.utils.AppLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Worker de diagnostic temporaire.
 *
 * Il consomme les frames PCM depuis AudioFrameBus.
 *
 * Il ne fait volontairement AUCUN STT.
 *
 * Son objectif est uniquement de vérifier que:
 *
 * AudioRecord
 *      ↓
 * AudioFrameBus.publish()
 *      ↓
 * AudioFrameBus.take()
 *
 * fonctionne réellement.
 */
object AudioFrameTestWorker {

    private const val TAG =
        "CV:AudioFrameTest"

    private val running =
        AtomicBoolean(false)

    @Volatile
    private var workerThread: Thread? =
        null

    fun start() {

        if (!running.compareAndSet(false, true)) {

            AppLogger.i(
                TAG,
                "AudioFrameTestWorker already running"
            )

            return
        }

        AppLogger.i(
            TAG,
            "=================================================="
        )

        AppLogger.i(
            TAG,
            "AudioFrameTestWorker STARTING"
        )

        AudioFrameBus.clear()

        workerThread =
            Thread {

                AppLogger.i(
                    TAG,
                    "TEST WORKER THREAD ENTER"
                )

                var processedFrames =
                    0L

                var processedBytes =
                    0L

                try {

                    while (running.get()) {

                        val frame =
                            AudioFrameBus.take()

                        processedFrames++

                        processedBytes +=
                            frame.size

                        if (processedFrames == 1L) {

                            AppLogger.i(
                                TAG,
                                "FIRST PCM FRAME RECEIVED BY WORKER: " +
                                        "bytes=${frame.size}"
                            )
                        }

                        if (
                            processedFrames % 100L == 0L
                        ) {

                            AppLogger.i(
                                TAG,
                                "PCM TEST CONSUMED: " +
                                        "frames=$processedFrames " +
                                        "bytes=$processedBytes " +
                                        "lastFrame=${frame.size} " +
                                        "busReceived=${AudioFrameBus.receivedCount()} " +
                                        "busConsumed=${AudioFrameBus.consumedCount()} " +
                                        "queue=${AudioFrameBus.queueSize()} " +
                                        "dropped=${AudioFrameBus.droppedCount()}"
                            )
                        }
                    }

                } catch (_: InterruptedException) {

                    AppLogger.i(
                        TAG,
                        "TEST WORKER INTERRUPTED"
                    )

                } catch (t: Throwable) {

                    AppLogger.w(
                        TAG,
                        "TEST WORKER ERROR: ${t.message}"
                    )

                } finally {

                    AppLogger.i(
                        TAG,
                        "TEST WORKER THREAD EXIT: " +
                                "frames=$processedFrames " +
                                "bytes=$processedBytes " +
                                "busReceived=${AudioFrameBus.receivedCount()} " +
                                "busConsumed=${AudioFrameBus.consumedCount()} " +
                                "queue=${AudioFrameBus.queueSize()} " +
                                "dropped=${AudioFrameBus.droppedCount()}"
                    )
                }

            }.apply {

                name =
                    "audio-frame-test"

                isDaemon =
                    true

                start()
            }

        AppLogger.i(
            TAG,
            "AudioFrameTestWorker STARTED"
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
            "Stopping AudioFrameTestWorker"
        )

        val thread =
            workerThread

        thread?.interrupt()

        runCatching {

            thread?.join(1_000L)

        }.onFailure {

            AppLogger.w(
                TAG,
                "Worker join failed: ${it.message}"
            )
        }

        workerThread =
            null

        AppLogger.i(
            TAG,
            "AudioFrameTestWorker stopped"
        )
    }
}