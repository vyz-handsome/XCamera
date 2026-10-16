#include <android/bitmap.h>
#include <jni.h>
#include <stdint.h>

#include "hd_core.h"

namespace {
struct CbCtx {
    JNIEnv* env;
    jobject listener;
    jmethodID mid;
};

int progress_thunk(void* user, int pct) {
    CbCtx* c = (CbCtx*)user;
    if (!c->listener || !c->mid) return 1;
    jboolean cont = c->env->CallBooleanMethod(c->listener, c->mid, (jint)pct);
    if (c->env->ExceptionCheck()) {
        c->env->ExceptionClear();
        return 1;
    }
    return cont ? 1 : 0;
}
} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_xcam_camera_hd_HdNative_nativeCreate(
    JNIEnv* env, jclass, jstring param, jstring bin, jboolean gpu, jint threads) {
    const char* p = env->GetStringUTFChars(param, nullptr);
    const char* b = env->GetStringUTFChars(bin, nullptr);
    char err[128] = {0};
    HdEngine* e = hd_create(p, b, gpu ? 1 : 0, threads, err, sizeof(err));
    env->ReleaseStringUTFChars(param, p);
    env->ReleaseStringUTFChars(bin, b);
    return (jlong)(intptr_t)e;
}

JNIEXPORT jboolean JNICALL Java_com_xcam_camera_hd_HdNative_nativeGpuActive(JNIEnv*, jclass, jlong h) {
    return hd_gpu_active((HdEngine*)(intptr_t)h) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_xcam_camera_hd_HdNative_nativeDestroy(JNIEnv*, jclass, jlong h) {
    hd_destroy((HdEngine*)(intptr_t)h);
}

// 0 ok, 1 dibatalkan, <0 galat. src: ARGB_8888 w x h, dst: ARGB_8888 2w x 2h.
JNIEXPORT jint JNICALL Java_com_xcam_camera_hd_HdNative_nativeUpscale(
    JNIEnv* env, jclass, jlong h, jobject srcBmp, jobject dstBmp, jint tile, jint pad, jobject listener) {
    HdEngine* e = (HdEngine*)(intptr_t)h;
    AndroidBitmapInfo si, di;
    if (AndroidBitmap_getInfo(env, srcBmp, &si) != 0 || AndroidBitmap_getInfo(env, dstBmp, &di) != 0) return -100;
    if (si.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || di.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return -101;
    if (di.width != si.width * 2 || di.height != si.height * 2) return -102;
    void* sp = nullptr;
    void* dp = nullptr;
    if (AndroidBitmap_lockPixels(env, srcBmp, &sp) != 0) return -103;
    if (AndroidBitmap_lockPixels(env, dstBmp, &dp) != 0) {
        AndroidBitmap_unlockPixels(env, srcBmp);
        return -104;
    }
    CbCtx ctx{env, listener, nullptr};
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        ctx.mid = env->GetMethodID(cls, "onProgress", "(I)Z");
    }
    int r = hd_upscale2x(e, (const uint8_t*)sp, (int)si.width, (int)si.height, (int)si.stride,
                         (uint8_t*)dp, (int)di.stride, tile, pad, progress_thunk, &ctx);
    AndroidBitmap_unlockPixels(env, dstBmp);
    AndroidBitmap_unlockPixels(env, srcBmp);
    return r;
}

} // extern "C"
