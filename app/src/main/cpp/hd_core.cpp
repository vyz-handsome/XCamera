#include "hd_core.h"

#include <algorithm>
#include <stdio.h>
#include <string.h>
#include <string>
#include <vector>

#include "cpu.h"
#include "net.h"
#if NCNN_VULKAN
#include "gpu.h"
#endif

struct HdEngine {
    ncnn::Net net;
    std::string param, bin;
    int threads = 0;
    bool gpu = false;
    bool gpu_instance = false;
};

static int load_net(HdEngine* e, bool gpu) {
    e->net.clear();
    int nt = e->threads > 0 ? e->threads : ncnn::get_big_cpu_count();
    if (nt <= 0) nt = ncnn::get_cpu_count();
    e->net.opt.num_threads = nt;
#if NCNN_VULKAN
    e->net.opt.use_vulkan_compute = gpu;
#else
    (void)gpu;
    e->net.opt.use_vulkan_compute = false;
#endif
    e->net.opt.use_fp16_arithmetic = false; // presisi lebih aman
    if (e->net.load_param(e->param.c_str()) != 0) return -1;
    if (e->net.load_model(e->bin.c_str()) != 0) return -2;
    e->gpu = gpu;
    return 0;
}

HdEngine* hd_create(const char* param_path, const char* bin_path, int use_gpu, int threads, char* err, int errlen) {
    HdEngine* e = new HdEngine();
    e->param = param_path;
    e->bin = bin_path;
    e->threads = threads;
    bool gpu = false;
#if NCNN_VULKAN
    if (use_gpu) {
        if (ncnn::create_gpu_instance() == 0) {
            e->gpu_instance = true;
            gpu = ncnn::get_gpu_count() > 0;
        }
    }
#else
    (void)use_gpu;
#endif
    int r = load_net(e, gpu);
    if (r != 0 && gpu) r = load_net(e, false);
    if (r != 0) {
        if (err && errlen > 0) snprintf(err, errlen, "gagal memuat model (%d)", r);
        hd_destroy(e);
        return NULL;
    }
    return e;
}

int hd_gpu_active(HdEngine* e) { return e && e->gpu ? 1 : 0; }

void hd_destroy(HdEngine* e) {
    if (!e) return;
    e->net.clear();
    bool inst = e->gpu_instance;
    delete e;
#if NCNN_VULKAN
    if (inst) ncnn::destroy_gpu_instance();
#else
    (void)inst;
#endif
}

// Satu tile: crop (RGBA, ex0..ex1) -> 4x -> tulis bagian inti (x0..x1) sebagai 2x lewat rata-rata 2x2.
static int run_tile(HdEngine* e, const uint8_t* src, int src_stride, int x0, int y0, int x1, int y1,
                    int ex0, int ey0, int ex1, int ey1, uint8_t* dst, int dst_stride) {
    const int cw = ex1 - ex0, ch = ey1 - ey0;
    ncnn::Mat in = ncnn::Mat::from_pixels(src + (size_t)ey0 * src_stride + (size_t)ex0 * 4,
                                          ncnn::Mat::PIXEL_RGBA2RGB, cw, ch, src_stride);
    const float norm[3] = {1.f / 255.f, 1.f / 255.f, 1.f / 255.f};
    in.substract_mean_normalize(0, norm);

    ncnn::Mat out;
    {
        ncnn::Extractor ex = e->net.create_extractor();
        if (ex.input("in0", in) != 0) return -10;
        if (ex.extract("out0", out) != 0) return -11;
    }
    if (out.w != cw * 4 || out.h != ch * 4 || out.c != 3) return -12;

    const float* p0 = out.channel(0);
    const float* p1 = out.channel(1);
    const float* p2 = out.channel(2);
    const int ow = out.w;
    for (int oy = y0 * 2; oy < y1 * 2; oy++) {
        const int v = oy * 2 - ey0 * 4; // pojok kiri-atas blok 2x2 di keluaran 4x
        uint8_t* d = dst + (size_t)oy * dst_stride + (size_t)(x0 * 2) * 4;
        for (int ox = x0 * 2; ox < x1 * 2; ox++) {
            const int u = ox * 2 - ex0 * 4;
            const int i = v * ow + u;
            float r = (p0[i] + p0[i + 1] + p0[i + ow] + p0[i + ow + 1]) * 0.25f;
            float g = (p1[i] + p1[i + 1] + p1[i + ow] + p1[i + ow + 1]) * 0.25f;
            float b = (p2[i] + p2[i + 1] + p2[i + ow] + p2[i + ow + 1]) * 0.25f;
            r = r < 0.f ? 0.f : (r > 1.f ? 1.f : r);
            g = g < 0.f ? 0.f : (g > 1.f ? 1.f : g);
            b = b < 0.f ? 0.f : (b > 1.f ? 1.f : b);
            d[0] = (uint8_t)(r * 255.f + 0.5f);
            d[1] = (uint8_t)(g * 255.f + 0.5f);
            d[2] = (uint8_t)(b * 255.f + 0.5f);
            d[3] = 255;
            d += 4;
        }
    }
    return 0;
}

int hd_upscale2x(HdEngine* e, const uint8_t* src, int w, int h, int src_stride,
                 uint8_t* dst, int dst_stride, int tile, int pad, hd_progress_fn cb, void* user) {
    if (!e || !src || !dst || w < 8 || h < 8) return -1;
    if (tile < 32) tile = 32;
    if (pad < 0) pad = 0;
    const int tx = (w + tile - 1) / tile, ty = (h + tile - 1) / tile;
    const int total = tx * ty;
    int done = 0;
    for (int j = 0; j < ty; j++) {
        for (int i = 0; i < tx; i++) {
            const int x0 = i * tile, y0 = j * tile;
            const int x1 = std::min(w, x0 + tile), y1 = std::min(h, y0 + tile);
            const int ex0 = std::max(0, x0 - pad), ey0 = std::max(0, y0 - pad);
            const int ex1 = std::min(w, x1 + pad), ey1 = std::min(h, y1 + pad);
            int r = run_tile(e, src, src_stride, x0, y0, x1, y1, ex0, ey0, ex1, ey1, dst, dst_stride);
            if (r != 0 && e->gpu) {
                // Vulkan bermasalah di HP ini: pindah ke CPU dan ulangi tile yang sama.
                if (load_net(e, false) == 0)
                    r = run_tile(e, src, src_stride, x0, y0, x1, y1, ex0, ey0, ex1, ey1, dst, dst_stride);
            }
            if (r != 0) return r;
            done++;
            if (cb && !cb(user, done * 100 / total)) return 1;
        }
    }
    return 0;
}
