package com.xcam.camera.settings

import android.content.Context
import android.content.SharedPreferences

/** Kunci preferensi XCAM. Semua disimpan lokal di SharedPreferences "xcam_prefs". */
object Prefs {
    const val FILE = "xcam_prefs"

    const val VIDEO_QUALITY = "video_quality"      // UHD | FHD | HD | SD
    const val LOOP_SECONDS = "loop_seconds"        // 15 | 30 | 60 | atau angka sendiri (5-180)
    const val VIDEO_FPS = "video_fps"              // auto | 24 | 30 | 60
    const val DEFAULT_CAMERA = "default_camera"    // back | front
    const val DEFAULT_FLASH = "default_flash"      // off | auto | on
    const val PHOTO_SIZE = "photo_size"            // auto | WxH
    const val MIRROR_FRONT = "mirror_front"        // boolean
    const val SHOW_GRID = "show_grid"              // boolean
    const val EIS_MODE = "eis_mode"                // auto | soft | hw
    const val EIS_STRENGTH = "eis_strength"        // normal | strong (EIS software)
    const val EIS_PREVIEW = "eis_preview"          // boolean: preview ikut diproses EIS software (uji)
    const val EIS_FLIP = "eis_flip"                // 0..7: kalibrasi arah sumbu EIS software (uji)
    const val ZOOM_SMOOTH = "zoom_smooth"          // fast | normal | soft
    const val ZOOM_SLIDER_ON_PINCH = "zoom_slider_pinch" // boolean

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
