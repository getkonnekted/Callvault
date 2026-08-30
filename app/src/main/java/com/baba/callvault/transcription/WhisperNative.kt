package com.baba.callvault.transcription

object WhisperNative {

    init {
        System.loadLibrary("callvault_whisper")
    }

    external fun nativeInit(modelPath: String): Boolean

    external fun nativeFree()

    external fun nativeTranscribe(audio: FloatArray): String
}