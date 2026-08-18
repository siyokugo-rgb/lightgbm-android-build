#include <jni.h>
#include <string>
#include <LightGBM/c_api.h>

extern "C"
JNIEXPORT jstring JNICALL
Java_com_keiba_ai_LightGbmNative_nativeStatus(
        JNIEnv* env,
        jobject /* thiz */) {

    const char* error = LGBM_GetLastError();

    std::string result = "LightGBM C API linked successfully";

    if (error != nullptr && error[0] != '\0') {
        result += " / last_error: ";
        result += error;
    }

    return env->NewStringUTF(result.c_str());
}
