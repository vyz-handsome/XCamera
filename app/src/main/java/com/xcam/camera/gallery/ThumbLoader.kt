package com.xcam.camera.gallery

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

/** Pemuat thumbnail kecil dengan cache memori, dipakai grid galeri. */
object ThumbLoader {

    private val executor = Executors.newFixedThreadPool(3)
    private val cache = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 1024) / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun load(context: Context, item: MediaStoreHelper.MediaItem, size: Int, target: ImageView) {
        val key = item.uri.toString()
        target.tag = key
        val hit = cache.get(key)
        if (hit != null) {
            target.setImageBitmap(hit)
            return
        }
        target.setImageDrawable(null)
        val app = context.applicationContext
        executor.execute {
            val bmp = MediaStoreHelper.loadThumbnail(app, item.uri, size, item.isVideo)
            if (bmp != null) {
                cache.put(key, bmp)
                target.post { if (target.tag == key) target.setImageBitmap(bmp) }
            }
        }
    }
}
