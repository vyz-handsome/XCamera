package com.xcam.camera.hd

import android.graphics.Bitmap

/** Jembatan ke libxcamhd.so (cpp/hd_jni.cpp). Jangan ubah nama paket/kelas/fungsi tanpa mengubah JNI. */
object HdNative {
    interface Progress {
        /** Kembalikan false untuk membatalkan. */
        fun onProgress(percent: Int): Boolean
    }

    val loaded: Boolean by lazy {
        try {
            System.loadLibrary("xcamhd")
            true
        } catch (e: Throwable) {
            false
        }
    }

    @JvmStatic external fun nativeCreate(param: String, bin: String, gpu: Boolean, threads: Int): Long
    @JvmStatic external fun nativeGpuActive(handle: Long): Boolean
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeUpscale(
        handle: Long, src: Bitmap, dst: Bitmap, tile: Int, pad: Int, listener: Progress?
    ): Int
}
