package com.baba.callvault.stt

import android.util.Log
import kotlin.concurrent.thread

/**
 * Test autonome du pipeline :
 *
 * PCM synthétique
 *      ↓
 * AudioFrameBus
 *      ↓
 * AudioFrameTestWorker
 *
 * Aucun appel téléphonique nécessaire.
 */
object PcmBusSelfTest {

    private const val TAG = "CV:AudioFrameTest"

    private var testThread: Thread? = null

    fun run() {
        if (testThread?.isAlive == true) {
            Log.d(TAG, "PCM self-test already running")
            return
        }

        testThread = thread(
            name = "pcm-self-test",
            isDaemon = true
        ) {
            try {
                Log.d(TAG, "PCM self-test started")

                AudioFrameTestWorker.start()

                // 16-bit PCM mono, 16 kHz.
                // Une frame = 20 ms = 320 samples = 640 octets.
                val frameSize = 640

                // 400 frames = environ 8 secondes de PCM.
                repeat(400) { frameIndex ->

                    val pcm = ByteArray(frameSize)

                    // Génère un signal PCM synthétique simple.
                    // Ce n'est pas de la vraie voix : c'est uniquement
                    // pour vérifier le transport des buffers.
                    for (i in pcm.indices step 2) {
                        val sampleIndex = frameIndex * (frameSize / 2) + (i / 2)

                        val value = (
                                kotlin.math.sin(
                                    2.0 * Math.PI * 440.0 * sampleIndex / 16000.0
                                ) * 8000.0
                                ).toInt()

                        pcm[i] = (value and 0xFF).toByte()
                        pcm[i + 1] = ((value shr 8) and 0xFF).toByte()
                    }

                    AudioFrameBus.publish(
                        pcm = pcm,
                        length = pcm.size
                    )

                    // 20 ms entre chaque frame pour simuler
                    // approximativement un flux audio temps réel.
                    Thread.sleep(20)
                }

                Log.d(
                    TAG,
                    "PCM self-test producer finished: " +
                            "received=${AudioFrameBus.receivedCount()} " +
                            "dropped=${AudioFrameBus.droppedCount()}"
                )

                // Laisse le worker afficher son résultat final.
                Thread.sleep(500)

            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    "PCM self-test error",
                    t
                )
            } finally {
                AudioFrameTestWorker.stop()

                Log.d(TAG, "PCM self-test finished")
            }
        }
    }
}