package com.baba.callvault.transcription

import android.content.Context
import com.baba.callvault.stt.WhisperTranscriptionWorker
import com.baba.callvault.utils.AppLogger
import java.io.File

object WhisperManager {

    private const val TAG =
        "CV:WhisperManager"

    private const val MODEL_NAME =
        "ggml-tiny.bin"

    @Volatile
    private var initialized =
        false

    @Synchronized
    fun initialize(
        context: Context
    ): Boolean {

        if (initialized) {

            AppLogger.i(
                TAG,
                "Whisper already initialized"
            )

            return true
        }

        return try {

            val modelFile =
                copyModelIfNeeded(
                    context
                )

            AppLogger.i(
                TAG,
                "Initializing Whisper: " +
                        modelFile.absolutePath
            )

            val success =
                WhisperNative.nativeInit(
                    modelFile.absolutePath
                )

            initialized = success

            if (success) {

                AppLogger.i(
                    TAG,
                    "Whisper initialized successfully"
                )

            } else {

                AppLogger.w(
                    TAG,
                    "Whisper initialization FAILED"
                )
            }

            success

        } catch (t: Throwable) {

            AppLogger.w(
                TAG,
                "Whisper initialization error: ${t.message}"
            )

            false
        }
    }

    private fun copyModelIfNeeded(
        context: Context
    ): File {

        val whisperDir =
            File(
                context.filesDir,
                "whisper"
            )

        if (!whisperDir.exists()) {

            whisperDir.mkdirs()
        }

        val modelFile =
            File(
                whisperDir,
                MODEL_NAME
            )

        if (
            modelFile.exists() &&
            modelFile.length() > 0
        ) {

            AppLogger.i(
                TAG,
                "Whisper model already exists"
            )

            return modelFile
        }

        AppLogger.i(
            TAG,
            "Copying Whisper model from assets"
        )

        context.assets.open(
            MODEL_NAME
        ).use { input ->

            modelFile.outputStream().use { output ->

                input.copyTo(output)
            }
        }

        AppLogger.i(
            TAG,
            "Whisper model copied successfully"
        )

        return modelFile
    }

    fun startTranscription(
        context: Context
    ): Boolean {

        if (!initialize(context)) {

            AppLogger.w(
                TAG,
                "Cannot start transcription"
            )

            return false
        }

        WhisperTranscriptionWorker.start()

        AppLogger.i(
            TAG,
            "Whisper transcription started"
        )

        return true
    }

    fun stopTranscription() {

        WhisperTranscriptionWorker.stop()
    }

    @Synchronized
    fun release() {

        WhisperTranscriptionWorker.stop()

        if (initialized) {

            WhisperNative.nativeFree()

            initialized = false

            AppLogger.i(
                TAG,
                "Whisper released"
            )
        }
    }
}