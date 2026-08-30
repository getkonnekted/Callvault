#include <jni.h>
#include <android/log.h>
#include <vector>
#include <string>
#include <cstring>

#include <whisper.h>

#define LOG_TAG "CallVaultWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static whisper_context *g_ctx = nullptr;

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_baba_callvault_transcription_WhisperNative_nativeInit(
        JNIEnv *env,
        jobject /* thiz */,
        jstring modelPath) {

    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }

    const char *path = env->GetStringUTFChars(modelPath, nullptr);

    whisper_context_params cparams = whisper_context_default_params();

    g_ctx = whisper_init_from_file_with_params(path, cparams);

    env->ReleaseStringUTFChars(modelPath, path);

    if (g_ctx == nullptr) {
        LOGE("Unable to initialize Whisper");
        return JNI_FALSE;
    }

    LOGI("Whisper initialized successfully");
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_baba_callvault_transcription_WhisperNative_nativeFree(
        JNIEnv * /* env */,
        jobject /* thiz */) {

    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_baba_callvault_transcription_WhisperNative_nativeTranscribe(
        JNIEnv *env,
        jobject /* thiz */,
        jfloatArray audio) {

    if (g_ctx == nullptr) {
        LOGE("Whisper context is not initialized");
        return env->NewStringUTF("");
    }

    const jsize sampleCount = env->GetArrayLength(audio);

    if (sampleCount <= 0) {
        return env->NewStringUTF("");
    }

    jfloat *samples = env->GetFloatArrayElements(audio, nullptr);

    whisper_full_params params =
            whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;

    // Language detection/translation will be configured at the
    // higher transcription layer later.
    params.translate = false;

    const int result =
            whisper_full(
                    g_ctx,
                    params,
                    samples,
                    sampleCount
            );

    env->ReleaseFloatArrayElements(audio, samples, JNI_ABORT);

    if (result != 0) {
        LOGE("whisper_full() failed: %d", result);
        return env->NewStringUTF("");
    }

    const int segmentCount = whisper_full_n_segments(g_ctx);

    std::string resultText;

    for (int i = 0; i < segmentCount; ++i) {
        const char *text =
                whisper_full_get_segment_text(g_ctx, i);

        if (text != nullptr) {
            resultText += text;
        }
    }

    return env->NewStringUTF(resultText.c_str());
}