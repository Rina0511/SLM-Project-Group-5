#include <jni.h>
#include <string>
#include <android/log.h>

// Tag for Logcat
#define LOG_TAG "NATIVE_LIB"

// Convenience macro
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jstring JNICALL
Java_edu_utem_ftmk_slm02_MainActivity_echoFromNative(
        JNIEnv* env,
        jobject /* this */,
        jstring input
) {
    LOGI("echoFromNative() called");

    // Convert jstring -> std::string
    const char* cstr = env->GetStringUTFChars(input, nullptr);
    if (cstr == nullptr) {
        LOGE("Failed to get UTF chars");
        return env->NewStringUTF("");
    }

    std::string text(cstr);
    env->ReleaseStringUTFChars(input, cstr);

    LOGI("Received input: %s", text.c_str());

    // Echo back
    return env->NewStringUTF(text.c_str());
}
