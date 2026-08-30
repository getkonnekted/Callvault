package com.baba.callvault.stt

import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**

 * Bus asynchrone pour envoyer les frames PCM vers le futur moteur STT
 * sans jamais bloquer le thread AudioRecord.
 */
object AudioFrameBus {

    private const val TAG = "CV:AudioFrameBus"

    /**

     * Nombre maximum de buffers PCM en attente.
     *
     * Si le consommateur est trop lent, les nouvelles frames sont
     * abandonnées plutôt que de bloquer le thread AudioRecord.
     */
    private const val MAX_QUEUE_SIZE = 200

    private val queue =
        ArrayBlockingQueue<ByteArray>(MAX_QUEUE_SIZE)

    /**

     * Nombre de frames ajoutées avec succès dans la queue.
     */
    private val receivedFrames =
        AtomicLong(0)

    /**

     * Nombre de frames réellement retirées et consommées.
     */
    private val consumedFrames =
        AtomicLong(0)

    /**

     * Nombre de frames abandonnées car la queue était pleine.
     */
    private val droppedFrames =
        AtomicLong(0)

    /**

     * Publie une copie indépendante du PCM.
     *
     * IMPORTANT :
     * Le buffer reçu est généralement réutilisé par la boucle AudioRecord.
     * Nous devons donc faire une copie avant de l'envoyer à un autre thread.
     *
     * Cette méthode ne bloque jamais.
     */
    fun publish(
        pcm: ByteArray,
        length: Int
    ) {

        if (length <= 0) {


            Log.w(
                TAG,
                "publish() ignored: invalid length=$length"
            )

            return


        }

        if (length > pcm.size) {


            Log.e(
                TAG,
                "publish() ignored: " +
                        "length=$length > bufferSize=${pcm.size}"
            )

            return


        }

        /*

        * Copie indépendante du buffer AudioRecord.
          */
        val copy =
            pcm.copyOf(length)

        /*

        * offer() ne bloque jamais.
          */
        if (queue.offer(copy)) {

            val count =
                receivedFrames.incrementAndGet()

            /*

            * Confirmation immédiate que le bus reçoit réellement du PCM.
              */
            if (count == 1L) {

                Log.i(
                    TAG,
                    "First PCM frame received | length=$length"
                )
            }

            /*

            * Diagnostic périodique.
              */
            if (count % 100L == 0L) {

                Log.d(
                    TAG,
                    "PCM received=$count | " +
                            "consumed=${consumedFrames.get()} | " +
                            "dropped=${droppedFrames.get()} | " +
                            "queue=${queue.size}/$MAX_QUEUE_SIZE"
                )
            }

        } else {


            val dropped =
                droppedFrames.incrementAndGet()

            /*
             * Évite de spammer Logcat.
             */
            if (
                dropped == 1L ||
                dropped % 100L == 0L
            ) {

                Log.w(
                    TAG,
                    "PCM FRAME DROPPED! | " +
                            "dropped=$dropped | " +
                            "queue=${queue.size}/$MAX_QUEUE_SIZE"
                )
            }


        }
    }

    /**

     * Attend et récupère la prochaine frame PCM.
     *
     * Cette fonction est destinée au worker STT.
     *
     * Le compteur consumedFrames est incrémenté uniquement
     * lorsqu'une frame est réellement retirée de la queue.
     */
    fun take(): ByteArray {

        val frame =
            queue.take()

        consumedFrames.incrementAndGet()

        return frame
    }

    /**

     * Récupère immédiatement une frame si disponible.
     *
     * Retourne null si la queue est vide.
     */
    fun poll(): ByteArray? {

        val frame =
            queue.poll()

        if (frame != null) {


            consumedFrames.incrementAndGet()


        }

        return frame
    }

    /**

     * Nombre total de frames PCM reçues avec succès.
     */
    fun receivedCount(): Long {

        return receivedFrames.get()
    }

    /**

     * Nombre total de frames réellement consommées.
     */
    fun consumedCount(): Long {

        return consumedFrames.get()
    }

    /**

     * Nombre total de frames abandonnées.
     */
    fun droppedCount(): Long {

        return droppedFrames.get()
    }

    /**

     * Nombre actuel de frames en attente.
     */
    fun queueSize(): Int {

        return queue.size
    }

    /**

     * Indique si la queue contient des frames.
     */
    fun hasFrames(): Boolean {

        return queue.isNotEmpty()
    }

    /**

     * Vide la queue et remet les compteurs à zéro.
     */
    fun clear() {

        queue.clear()

        receivedFrames.set(0)
        consumedFrames.set(0)
        droppedFrames.set(0)

        Log.i(
            TAG,
            "AudioFrameBus cleared"
        )
    }
}