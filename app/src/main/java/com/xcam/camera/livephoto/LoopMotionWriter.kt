package com.xcam.camera.livephoto

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

/**
 * Menulis Motion Photo untuk hasil Loop dengan susunan XMP yang sama seperti script motion_photo.py
 * (elemen GCamera:MotionPhoto, Container:Directory, Item:Length = ukuran video). Video ditempel di akhir JPEG.
 */
object LoopMotionWriter {

    private val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)

    private fun xmp(videoLength: Long): ByteArray {
        val text = """<?xpacket begin="${'\uFEFF'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
        xmlns:Container="http://ns.google.com/photos/1.0/container/"
        xmlns:Item="http://ns.google.com/photos/1.0/container/item/">
      <GCamera:MotionPhoto>1</GCamera:MotionPhoto>
      <GCamera:MotionPhotoVersion>1</GCamera:MotionPhotoVersion>
      <GCamera:MotionPhotoPrimaryPresentationTimestampUs>0</GCamera:MotionPhotoPrimaryPresentationTimestampUs>
      <Container:Directory>
        <rdf:Seq>
          <rdf:li rdf:parseType="Resource">
            <Container:Item
                Item:Mime="image/jpeg"
                Item:Semantic="Primary"
                Item:Length="0"
                Item:Padding="0"/>
          </rdf:li>
          <rdf:li rdf:parseType="Resource">
            <Container:Item
                Item:Mime="video/mp4"
                Item:Semantic="MotionPhoto"
                Item:Length="$videoLength"
                Item:Padding="0"/>
          </rdf:li>
        </rdf:Seq>
      </Container:Directory>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
        return text.toByteArray(Charsets.UTF_8)
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun isXmp(jpeg: ByteArray, pos: Int, len: Int): Boolean {
        if (len - 2 < XMP_ID.size || pos + 4 + XMP_ID.size > jpeg.size) return false
        for (i in XMP_ID.indices) if (jpeg[pos + 4 + i] != XMP_ID[i]) return false
        return true
    }

    /**
     * Ambil bagian JPEG utama dari sebuah file; kalau itu Motion Photo, video di ujungnya dibuang.
     * Dibaca streaming, jadi file hasil Loop yang besar tidak dimuat utuh ke memori.
     */
    fun readPrimaryJpeg(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val ch = fis.channel
                    val size = ch.size()
                    if (size <= 4 || size > Int.MAX_VALUE) return@use null
                    val length = MotionPhotoWriter.embeddedVideoLength(ch)
                    var end = size
                    if (length > 16 && length < size) {
                        val start = size - length
                        // Motion Photo: video diawali kotak "ftyp" (kadang didahului header 24 byte trailer Samsung).
                        val region = readAt(ch, start, minOf(40L, size - start).toInt())
                        val text = if (region != null) String(region, Charsets.ISO_8859_1) else ""
                        if (text.contains("ftyp")) {
                            var cut = start + text.indexOf("ftyp") - 4
                            // Buang juga header trailer Samsung ("MotionPhoto_Data") jika ada tepat sebelum video.
                            val nameLen = "MotionPhoto_Data".length
                            if (cut >= 8 + nameLen) {
                                val nm = readAt(ch, cut - nameLen, nameLen)
                                if (nm != null && String(nm, Charsets.ISO_8859_1) == "MotionPhoto_Data") cut -= (8 + nameLen)
                            }
                            end = cut
                        }
                    }
                    val bytes = readAt(ch, 0, end.toInt()) ?: return@use null
                    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) bytes else null
                }
            }
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Tulis JPEG + XMP + video ke [out]. */
    fun write(jpeg: ByteArray, video: File, out: OutputStream): Boolean {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return false
        val segment = XMP_ID + xmp(video.length())
        val segLen = segment.size + 2
        if (segLen > 65535) return false

        out.write(0xFF); out.write(0xD8)
        var pos = 2
        // Pertahankan APP0/APP1 di awal (EXIF berisi orientasi foto), buang XMP lama.
        while (pos + 4 <= jpeg.size && jpeg[pos] == 0xFF.toByte()) {
            val marker = jpeg[pos + 1].toInt() and 0xFF
            if (marker != 0xE0 && marker != 0xE1) break
            val len = u16(jpeg, pos + 2)
            if (len < 2 || pos + 2 + len > jpeg.size) return false
            if (!(marker == 0xE1 && isXmp(jpeg, pos, len))) out.write(jpeg, pos, 2 + len)
            pos += 2 + len
        }
        out.write(0xFF); out.write(0xE1)
        out.write(segLen shr 8); out.write(segLen and 0xFF)
        out.write(segment)
        out.write(jpeg, pos, jpeg.size - pos)
        video.inputStream().use { it.copyTo(out, 256 * 1024) }
        out.flush()
        return true
    }
}
