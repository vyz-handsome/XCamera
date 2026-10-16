// Inti pembesar foto 2x XCAM (tanpa JNI/Android, supaya bisa diuji di komputer).
// Model: realesr-general-x4v3 (Real-ESRGAN, SRVGGNetCompact) lewat ncnn, keluaran 4x lalu diperkecil 2x2 -> hasil 2x.
#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Mengembalikan 0 untuk membatalkan.
typedef int (*hd_progress_fn)(void* user, int percent);

typedef struct HdEngine HdEngine;

// use_gpu: coba Vulkan; kalau tidak ada / gagal otomatis CPU. Mengembalikan NULL jika model gagal dimuat.
HdEngine* hd_create(const char* param_path, const char* bin_path, int use_gpu, int threads, char* err, int errlen);
int hd_gpu_active(HdEngine* e);
// src/dst: RGBA 8-bit. dst berukuran (2w x 2h). 0 = ok, 1 = dibatalkan, <0 = galat.
int hd_upscale2x(HdEngine* e, const uint8_t* src, int w, int h, int src_stride,
                 uint8_t* dst, int dst_stride, int tile, int pad, hd_progress_fn cb, void* user);
void hd_destroy(HdEngine* e);

#ifdef __cplusplus
}
#endif
