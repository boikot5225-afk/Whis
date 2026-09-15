#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <string>
#include "whisper.h"

#define TAG "WhisNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct ProgressCallbackData {
    JavaVM *jvm = nullptr;
    jobject callback = nullptr;
    jmethodID method = nullptr;
};

static void whisper_progress_bridge(
        whisper_context *,
        whisper_state *,
        int progress,
        void *user_data) {
    auto *data = static_cast<ProgressCallbackData *>(user_data);
    if (data == nullptr || data->jvm == nullptr || data->callback == nullptr || data->method == nullptr) {
        return;
    }

    JNIEnv *env = nullptr;
    bool detach = false;
    const jint state = data->jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if (data->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        detach = true;
    } else if (state != JNI_OK || env == nullptr) {
        return;
    }

    env->CallVoidMethod(data->callback, data->method, static_cast<jint>(progress));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }

    if (detach) {
        data->jvm->DetachCurrentThread();
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bulat_whis_NativeWhisper_initContext(
        JNIEnv *env,
        jclass,
        jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    whisper_context *ctx = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(model_path, path);

    if (ctx == nullptr) {
        LOGE("Failed to load Whisper model");
        return 0;
    }

    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_bulat_whis_NativeWhisper_freeContext(
        JNIEnv *,
        jclass,
        jlong context_ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(context_ptr);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bulat_whis_NativeWhisper_fullTranscribe(
        JNIEnv *env,
        jclass,
        jlong context_ptr,
        jint num_threads,
        jfloatArray audio_data,
        jstring language,
        jboolean translate,
        jobject progress_callback) {
    auto *ctx = reinterpret_cast<whisper_context *>(context_ptr);
    if (ctx == nullptr) {
        return -1;
    }

    jfloat *samples = env->GetFloatArrayElements(audio_data, nullptr);
    const jsize sample_count = env->GetArrayLength(audio_data);
    const char *language_chars = env->GetStringUTFChars(language, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = translate == JNI_TRUE;
    params.language = language_chars;
    params.n_threads = std::max(1, static_cast<int>(num_threads));
    params.offset_ms = 0;
    params.no_context = true;
    params.single_segment = false;
    params.suppress_blank = true;

    ProgressCallbackData callback_data;
    if (progress_callback != nullptr) {
        env->GetJavaVM(&callback_data.jvm);
        callback_data.callback = env->NewGlobalRef(progress_callback);
        jclass callback_class = env->GetObjectClass(progress_callback);
        callback_data.method = env->GetMethodID(callback_class, "onProgress", "(I)V");
        env->DeleteLocalRef(callback_class);

        if (callback_data.method != nullptr) {
            params.progress_callback = whisper_progress_bridge;
            params.progress_callback_user_data = &callback_data;
        } else if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }

    whisper_reset_timings(ctx);
    const int result = whisper_full(ctx, params, samples, static_cast<int>(sample_count));

    if (callback_data.callback != nullptr) {
        env->DeleteGlobalRef(callback_data.callback);
    }
    env->ReleaseStringUTFChars(language, language_chars);
    env->ReleaseFloatArrayElements(audio_data, samples, JNI_ABORT);

    if (result != 0) {
        LOGE("whisper_full failed with code %d", result);
    } else {
        LOGI("Transcription completed");
    }

    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bulat_whis_NativeWhisper_getSegmentCount(
        JNIEnv *,
        jclass,
        jlong context_ptr) {
    auto *ctx = reinterpret_cast<whisper_context *>(context_ptr);
    return ctx == nullptr ? 0 : whisper_full_n_segments(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bulat_whis_NativeWhisper_getSegmentText(
        JNIEnv *env,
        jclass,
        jlong context_ptr,
        jint index) {
    auto *ctx = reinterpret_cast<whisper_context *>(context_ptr);
    if (ctx == nullptr) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(whisper_full_get_segment_text(ctx, index));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bulat_whis_NativeWhisper_getSegmentT0(
        JNIEnv *,
        jclass,
        jlong context_ptr,
        jint index) {
    auto *ctx = reinterpret_cast<whisper_context *>(context_ptr);
    return ctx == nullptr ? 0 : whisper_full_get_segment_t0(ctx, index);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_bulat_whis_NativeWhisper_getSegmentT1(
        JNIEnv *,
        jclass,
        jlong context_ptr,
        jint index) {
    auto *ctx = reinterpret_cast<whisper_context *>(context_ptr);
    return ctx == nullptr ? 0 : whisper_full_get_segment_t1(ctx, index);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_bulat_whis_NativeWhisper_getSystemInfo(
        JNIEnv *env,
        jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}
