package com.xcam.camera.hd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

/**
 * Pembesar foto 2x dengan AI lokal (Real-ESRGAN "general x4v3" lewat ncnn).
 * Model keluaran 4x, lalu diperkecil 2x2 sehingga hasil akhirnya 2x dan lebih bersih.
 * Foto 12 MP jadi 48 MP, jadi proses ini berat: puluhan detik (GPU) sampai beberapa menit (CPU).
 */
object HdUpscaler {

    const val MARK = "XCAM_HD2X"
    private const val TILE = 256
    private const val PAD = 16
    private const val MODEL = "xcam_hd_x4"
    /** Batas piksel masukan: keluaran 4x lipat harus muat di memori (48 MP = 8000x6000 ARGB = 192 MB). */
    private const val MAX_INPUT_PIXELS = 14_000_000L

    enum class Status { OK, ALREADY_HD, TOO_BIG, NO_LIBRARY, NO_MODEL, DECODE_FAILED, MEMORY, CANCELLED, FAILED }

    class Result(val status: Status, val usedGpu: Boolean = false, val detail: String = "")

    /** Apakah foto ini sudah pernah di-HD-kan (ditandai di EXIF UserComment). */
    fun isHd(file: File): Boolean = try {
        ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_USER_COMMENT)?.contains(MARK) == true
    } catch (e: Exception) {
        false
    }

    private fun prepareModel(ctx: Context): Pair<File, File>? {
        return try {
            val dir = File(ctx.filesDir, "hd").apply { mkdirs() }
            val param = File(dir, "$MODEL.param")
            val bin = File(dir, "$MODEL.bin")
            for (f in listOf(param, bin)) {
                val asset = "hd/${f.name}"
                val size = ctx.assets.openFd(asset).use { it.length }
                if (!f.exists() || f.length() != size) {
                    ctx.assets.open(asset).use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
                }
            }
            param to bin
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Besarkan [input] (JPEG) 2x ke [output] (JPEG, kualitas 95, EXIF disalin).
     * [report] 0..100; kembalikan false di sana untuk membatalkan.
     */
    fun enhance(
        ctx: Context, input: File, output: File, useGpu: Boolean, report: (Int) -> Boolean
    ): Result {
        if (!HdNative.loaded) return Result(Status.NO_LIBRARY)
        if (isHd(input)) return Result(Status.ALREADY_HD)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return Result(Status.DECODE_FAILED)
        if (w.toLong() * h > MAX_INPUT_PIXELS) return Result(Status.TOO_BIG, detail = "${w}x$h")

        val model = prepareModel(ctx) ?: return Result(Status.NO_MODEL)

        var src: Bitmap? = null
        var dst: Bitmap? = null
        var handle = 0L
        try {
            src = BitmapFactory.decodeFile(
                input.absolutePath,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            ) ?: return Result(Status.DECODE_FAILED)
            dst = Bitmap.createBitmap(src.width * 2, src.height * 2, Bitmap.Config.ARGB_8888)
            dst.setHasAlpha(false)

            handle = HdNative.nativeCreate(model.first.absolutePath, model.second.absolutePath, useGpu, 0)
            if (handle == 0L) return Result(Status.NO_MODEL, detail = "model gagal dimuat")
            val gpuAtStart = HdNative.nativeGpuActive(handle)

            val r = HdNative.nativeUpscale(handle, src, dst, TILE, PAD, object : HdNative.Progress {
                override fun onProgress(percent: Int): Boolean = report(percent.coerceIn(0, 100))
            })
            val gpuAtEnd = HdNative.nativeGpuActive(handle)
            if (r == 1) return Result(Status.CANCELLED)
            if (r != 0) return Result(Status.FAILED, detail = "kode $r")

            src.recycle()
            src = null
            // Simpan referensi non-null yang immutable agar Kotlin tidak perlu
            // melakukan smart cast pada dst dari dalam lambda FileOutputStream.use.
            val outputBitmap = dst ?: return Result(Status.FAILED, detail = "bitmap output kosong")
            FileOutputStream(output).use { stream ->
                outputBitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            }
            val outW = outputBitmap.width
            val outH = outputBitmap.height
            outputBitmap.recycle()
            dst = null

            copyExif(input, output, outW, outH)
            return Result(Status.OK, usedGpu = gpuAtStart && gpuAtEnd)
        } catch (e: OutOfMemoryError) {
            return Result(Status.MEMORY)
        } catch (e: Throwable) {
            return Result(Status.FAILED, detail = e.javaClass.simpleName + ": " + (e.message ?: ""))
        } finally {
            if (handle != 0L) HdNative.nativeDestroy(handle)
            src?.recycle()
            dst?.recycle()
        }
    }

    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD
    )

    private fun copyExif(from: File, to: File, outW: Int, outH: Int) {
        try {
            val src = ExifInterface(from.absolutePath)
            val dst = ExifInterface(to.absolutePath)
            for (tag in EXIF_TAGS) {
                src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            }
            dst.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, outW.toString())
            dst.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, outH.toString())
            dst.setAttribute(ExifInterface.TAG_USER_COMMENT, MARK)
            dst.saveAttributes()
        } catch (e: Exception) {
            // Foto tetap sah tanpa EXIF lengkap.
        }
    }
}
