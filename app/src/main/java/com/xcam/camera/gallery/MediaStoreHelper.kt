package com.xcam.camera.gallery

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface

object MediaStoreHelper {

    class MediaRef(val uri: Uri, val isVideo: Boolean)

    /**
     * [liveVideoUri] terisi untuk Live Photo: foto XCAM_LIVE_x.jpg yang punya video
     * pasangan XCAM_LIVE_x.mp4 (nama dasar sama). Ini format milik XCAM sendiri,
     * bukan Live Photos iOS atau Motion Photo Android.
     */
    class MediaItem(
        val uri: Uri,
        val isVideo: Boolean,
        val dateMs: Long,
        val durationMs: Long,
        val liveVideoUri: Uri? = null,
        /** Motion Photo: video tertanam di dalam file JPEG itu sendiri (nama berakhiran _MP). */
        val isMotion: Boolean = false,
        /** Hasil Loop Live Photo (nama XCAM_LIVE_LOOP_...). Tidak ditawari "Buat loop" lagi. */
        val isLoop: Boolean = false
    ) {
        val isLive: Boolean get() = isMotion || liveVideoUri != null
    }

    const val LIVE_PREFIX = "XCAM_LIVE_"

    private class RawRow(val uri: Uri, val dateMs: Long, val base: String, val durationMs: Long)

    private fun queryRows(context: Context, collection: Uri, video: Boolean): List<RawRow> {
        val out = mutableListOf<RawRow>()
        val projection = if (video) {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.Video.VideoColumns.DURATION
            )
        } else {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.DISPLAY_NAME
            )
        }
        try {
            context.contentResolver.query(
                collection,
                projection,
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("XCAM_%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(2) ?: ""
                    out.add(
                        RawRow(
                            ContentUris.withAppendedId(collection, c.getLong(0)),
                            c.getLong(1) * 1000L,
                            name.substringBeforeLast('.'),
                            if (video) c.getLong(3) else 0L
                        )
                    )
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** Semua foto, video, dan Live Photo XCAM, terbaru di atas. */
    fun queryAll(context: Context): List<MediaItem> {
        val images = queryRows(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
        val videos = queryRows(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)

        val liveVideos = videos.filter { it.base.startsWith(LIVE_PREFIX) }.associateBy { it.base }
        val imageBases = images.map { it.base }.toHashSet()

        val out = mutableListOf<MediaItem>()
        for (img in images) {
            val pair = if (img.base.startsWith(LIVE_PREFIX)) liveVideos[img.base] else null
            val motion = img.base.startsWith(LIVE_PREFIX) && img.base.endsWith("_MP")
            val loop = img.base.startsWith(LIVE_PREFIX + "LOOP_")
            out.add(MediaItem(img.uri, false, img.dateMs, pair?.durationMs ?: 0L, pair?.uri, motion, loop))
        }
        for (v in videos) {
            // Video Live Photo yang fotonya ada disembunyikan (sudah ikut di item fotonya).
            if (v.base.startsWith(LIVE_PREFIX) && v.base in imageBases) continue
            out.add(MediaItem(v.uri, true, v.dateMs, v.durationMs))
        }
        out.sortByDescending { it.dateMs }
        return out
    }

    /** Foto ukuran layar (sisi terpanjang maksimal [maxSize] px), orientasi sudah benar. */
    fun loadBitmap(context: Context, uri: Uri, maxSize: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = maxOf(w, h).toFloat() / maxSize
                if (scale > 1f) {
                    decoder.setTargetSize(
                        (w / scale).toInt().coerceAtLeast(1),
                        (h / scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            decodeLegacy(context, uri, maxSize / 2)
        }
    } catch (e: Exception) {
        null
    }

    /** Media XCAM terbaru (Live Photo dihitung sebagai fotonya). */
    fun latestMedia(context: Context): MediaRef? =
        queryAll(context).firstOrNull()?.let { MediaRef(it.uri, it.isVideo) }

    fun loadThumbnail(context: Context, uri: Uri, size: Int, isVideo: Boolean = false): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(size, size), null)
        } else if (isVideo) {
            @Suppress("DEPRECATION")
            MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver,
                ContentUris.parseId(uri),
                MediaStore.Video.Thumbnails.MINI_KIND,
                null
            )
        } else {
            decodeLegacy(context, uri, size)
        }
    } catch (e: Exception) {
        null
    }

    private fun decodeLegacy(context: Context, uri: Uri, size: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) {
            sample *= 2
        }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(degrees) }, true
        )
    }
}
