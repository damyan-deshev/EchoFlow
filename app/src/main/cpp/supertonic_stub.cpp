#include <jni.h>

namespace {
void unsupported(JNIEnv* env) {
    jclass error = env->FindClass("java/lang/UnsupportedOperationException");
    env->ThrowNew(error, "On-device Supertonic requires an arm64 Android device");
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_echoflow_data_LocalSupertonicNative_nativeCreate(
        JNIEnv* env, jobject, jstring, jstring) {
    unsupported(env);
    return 0;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_echoflow_data_LocalSupertonicNative_nativeSynthesize(
        JNIEnv* env, jobject, jlong, jintArray, jfloatArray, jfloatArray, jint, jint,
        jfloatArray, jint, jint, jint, jfloat, jlong) {
    unsupported(env);
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_echoflow_data_LocalSupertonicNative_nativeCloseAndSaveCache(
        JNIEnv*, jobject, jlong) {}

extern "C" JNIEXPORT void JNICALL
Java_com_echoflow_data_LocalSupertonicNative_nativeClose(
        JNIEnv*, jobject, jlong) {}
