package com.xcam.camera.hd

import android.content.Context
import android.net.Uri
import com.xcam.camera.livephoto.MotionPhotoMaker
import com.xcam.camera.livephoto.MotionPhotoWriter
import com.xcam.camera.settings.Prefs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Satu pekerjaan HD untuk satu foto di galeri (menimpa file aslinya kalau berhasil).
 * - Foto biasa / foto Live Photo lama (JPG + MP4 terpisah): JPEG dibesarkan 2x lalu menimpa foto.
 * - Motion Photo (_MP.jpg): bagian foto dibesarkan 2x, video tertanam dipertahankan apa adanya.
 * File asli baru ditimpa setelah semua langkah berhasil, jadi kalau gagal / dibatalkan foto lama aman.
 */
object HdProcessor {

    fun process(
        ctx: Context, uri: Uri, isMotion: Boolean, onProgress: (Int) -> Boolean
    ): HdUpscaler.Result {
        val cache = ctx.cacheDir
        val inFile = File(cache, "hd_in.jpg")
        val outFile = File(cache, "hd_out.jpg")
        val finalFile = File(cache, "hd_final.jpg")
        val videoFile = File(cache, "hd_video.mp4")
        val all = listOf(inFile, outFile, finalFile, videoFile)
        all.forEach { it.delete() }
        try {
            val useGpu = Prefs.of(ctx).getBoolean(Prefs.HD_GPU, true)
            var presentationUs = 0L

            if (isMotion) {
                val parts = splitMotion(ctx, uri, inFile, videoFile)
                    ?: return HdUpscaler.Result(HdUpscaler.Status.DECODE_FAILED, detail = "bukan Motion Photo")
                presentationUs = parts
            } else {
                ctx.contentResolver.openInputStream(uri)?.use { i -> FileOutputStream(inFile).use { i.copyTo(it) } }
                    ?: return HdUpscaler.Result(HdUpscaler.Status.DECODE_FAILED)
            }

            // 0-95% = pembesaran, sisanya menulis hasil.
            val r = HdUpscaler.enhance(ctx, inFile, outFile, useGpu) { p -> onProgress(p * 95 / 100) }
            if (r.status != HdUpscaler.Status.OK) return r

            val toWrite: File
            if (isMotion) {
                val jpeg = outFile.readBytes()
                val ok = FileOutputStream(finalFile).use { MotionPhotoMaker.write(jpeg, videoFile, presentationUs, it) }
                if (!ok) return HdUpscaler.Result(HdUpscaler.Status.FAILED, detail = "gagal menyusun Motion Photo")
                toWrite = finalFile
            } else {
                toWrite = outFile
            }
            if (!onProgress(97)) return HdUpscaler.Result(HdUpscaler.Status.CANCELLED)

            // Timpa file asli. Penulisan ke MediaStore sengaja dilakukan paling akhir.
            val out = ctx.contentResolver.openOutputStream(uri, "wt")
                ?: return HdUpscaler.Result(HdUpscaler.Status.FAILED, detail = "tidak bisa menulis ke galeri")
            out.use { o -> FileInputStream(toWrite).use { it.copyTo(o, 256 * 1024) } }
            onProgress(100)
            return r
        } catch (e: OutOfMemoryError) {
            return HdUpscaler.Result(HdUpscaler.Status.MEMORY)
        } catch (e: Throwable) {
            return HdUpscaler.Result(HdUpscaler.Status.FAILED, detail = e.javaClass.simpleName + ": " + (e.message ?: ""))
        } finally {
            all.forEach { it.delete() }
        }
    }

    /**
     * Pisahkan Motion Photo jadi JPEG ([jpegDest]) dan MP4 ([videoDest]).
     * @return presentationUs foto di dalam video, atau null kalau bukan Motion Photo.
     */
    private fun splitMotion(ctx: Context, uri: Uri, jpegDest: File, videoDest: File): Long? {
        if (MotionPhotoWriter.extractVideo(ctx, uri, videoDest) == null) return null
        val videoLen = videoDest.length()
        var presentationUs = 0L
        val ok = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { fis ->
                val ch = fis.channel
                val size = ch.size()
                val jpegLen = size - videoLen
                if (jpegLen <= 4) return@use false
                val head = readHead(ch, minOf(size, 80_000L).toInt())
                Regex("MotionPhotoPresentationTimestampUs=\"(-?\\d+)\"").find(head)?.let {
                    presentationUs = it.groupValues[1].toLongOrNull() ?: 0L
                }
                val buf = java.nio.ByteBuffer.allocate(256 * 1024)
                var pos = 0L
                FileOutputStream(jpegDest).use { out ->
                    while (pos < jpegLen) {
                        buf.clear()
                        buf.limit(minOf(buf.capacity().toLong(), jpegLen - pos).toInt())
                        val n = ch.read(buf, pos)
                        if (n <= 0) break
                        out.write(buf.array(), 0, n)
                        pos += n
                    }
                }
                pos == jpegLen
            }
        } ?: false
        return if (ok) presentationUs else null
    }

    private fun readHead(ch: java.nio.channels.FileChannel, len: Int): String {
        val buf = java.nio.ByteBuffer.allocate(len)
        var p = 0L
        while (buf.hasRemaining()) {
            val n = ch.read(buf, p)
            if (n <= 0) break
            p += n
        }
        return String(buf.array(), 0, buf.position(), Charsets.ISO_8859_1)
    }
}
