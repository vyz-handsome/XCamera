package com.xcam.camera.livephoto

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Menulis dan membaca Motion Photo (format Google, "Motion Photo Format 1.0"):
 * satu file JPEG berisi XMP yang menunjuk ke video MP4 yang ditempel di akhir file.
 * Nama file sebaiknya berakhiran "MP.jpg" (mis. XCAM_LIVE_..._MP.jpg).
 */
object MotionPhotoWriter {

    /** Tulis JPEG + XMP + video ke [out]. Mengembalikan false jika JPEG tidak valid. */
    fun write(jpeg: ByteArray, video: File, presentationUs: Long, out: OutputStream): Boolean =
        MotionPhotoMaker.write(jpeg, video, presentationUs, out)

    /** Gabungkan video ke foto di [photoUri] (menimpa file foto itu). */
    fun combine(context: Context, photoUri: Uri, video: File, presentationUs: Long): Boolean {
        return try {
            val jpeg = context.contentResolver.openInputStream(photoUri)?.use { it.readBytes() } ?: return false
            // Bangun dulu di memori supaya foto asli tidak rusak kalau gagal di tengah jalan.
            val buffer = ByteArrayOutputStream(jpeg.size + video.length().toInt() + 70_000)
            if (!write(jpeg, video, presentationUs, buffer)) return false
            context.contentResolver.openOutputStream(photoUri, "wt")?.use { it.write(buffer.toByteArray()) }
                ?: return false
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Ambil video yang tertanam di Motion Photo ke [dest]. Null jika tidak ada video.
     * Dibaca streaming dari file (tidak memuat seluruh file ke memori), karena hasil Loop bisa puluhan MB.
     */
    fun extractVideo(context: Context, photoUri: Uri, dest: File): File? {
        return try {
            context.contentResolver.openFileDescriptor(photoUri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val ch = fis.channel
                    val size = ch.size()
                    val length = embeddedVideoLength(ch)
                    if (length <= 16 || length >= size) return@use null
                    val start = size - length
                    // Video MP4 selalu punya kotak "ftyp" di awal.
                    val probe = readAt(ch, start, 8) ?: return@use null
                    if (String(probe, 4, 4, Charsets.ISO_8859_1) != "ftyp") return@use null
                    var pos = start
                    FileOutputStream(dest).use { out ->
                        val buf = ByteBuffer.allocate(256 * 1024)
                        while (pos < size) {
                            buf.clear()
                            val n = ch.read(buf, pos)
                            if (n <= 0) break
                            out.write(buf.array(), 0, n)
                            pos += n
                        }
                    }
                    if (pos < size) null else dest
                }
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Panjang video tertanam menurut XMP di 80 KB pertama. 0 jika tidak ada. */
    internal fun embeddedVideoLength(ch: FileChannel): Long {
        val head = readAt(ch, 0, minOf(ch.size(), 80_000L).toInt()) ?: return 0L
        val text = String(head, Charsets.ISO_8859_1)
        return Regex("Item:Length=\"(\\d+)\"").findAll(text)
            .map { it.groupValues[1].toLong() }.maxOrNull() ?: 0L
    }
}

/** Baca tepat [len] byte mulai [pos]. Null kalau file lebih pendek. */
internal fun readAt(ch: FileChannel, pos: Long, len: Int): ByteArray? {
    val buf = ByteBuffer.allocate(len)
    var p = pos
    while (buf.hasRemaining()) {
        val n = ch.read(buf, p)
        if (n <= 0) return null
        p += n
    }
    return buf.array()
}
