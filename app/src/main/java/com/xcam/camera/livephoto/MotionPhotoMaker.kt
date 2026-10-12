package com.xcam.camera.livephoto

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/**
 * Gabungin JPEG + MP4 jadi Google Motion Photo (1 file .jpg).
 * TANPA encode ulang: cuma nyisipin XMP ke header JPEG lalu nempel bytes MP4 di belakang.
 * Dipakai oleh [MotionPhotoWriter].
 */
object MotionPhotoMaker {

    private val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
    private val EXIF_ID = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)

    /**
     * Inti penggabungan (dipakai juga oleh [MotionPhotoWriter]): JPEG + XMP + MP4, tanpa encode ulang.
     * @return false kalau JPEG tidak valid atau XMP kebesaran.
     */
    fun write(jpeg: ByteArray, video: File, presentationUs: Long, out: OutputStream): Boolean {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return false
        val segment = try {
            buildXmpSegment(video.length(), presentationUs)
        } catch (e: IllegalArgumentException) {
            return false
        }
        out.write(injectXmp(jpeg, segment))
        video.inputStream().use { it.copyTo(out, 256 * 1024) } // stream copy, no re-encode
        out.flush()
        return true
    }

    private fun xmpXml(videoSize: Long, stillTimeUs: Long): String = """<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="XCamera">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
    xmlns:Container="http://ns.google.com/photos/1.0/container/"
    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
    GCamera:MotionPhoto="1"
    GCamera:MotionPhotoVersion="1"
    GCamera:MotionPhotoPresentationTimestampUs="$stillTimeUs"
    GCamera:MicroVideo="1"
    GCamera:MicroVideoVersion="1"
    GCamera:MicroVideoOffset="$videoSize"
    GCamera:MicroVideoPresentationTimestampUs="$stillTimeUs">
   <Container:Directory>
    <rdf:Seq>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/>
     </rdf:li>
     <rdf:li rdf:parseType="Resource">
      <Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="$videoSize" Item:Padding="0"/>
     </rdf:li>
    </rdf:Seq>
   </Container:Directory>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>""".trimIndent()

    private fun buildXmpSegment(videoSize: Long, stillTimeUs: Long): ByteArray {
        val xml = xmpXml(videoSize, stillTimeUs).toByteArray(Charsets.UTF_8)
        val len = 2 + XMP_ID.size + xml.size
        require(len <= 0xFFFF) { "XMP kebesaran" }
        val bo = ByteArrayOutputStream(len + 2)
        bo.write(0xFF); bo.write(0xE1)
        bo.write(len shr 8); bo.write(len and 0xFF)
        bo.write(XMP_ID)
        bo.write(xml)
        return bo.toByteArray()
    }

    private fun matches(b: ByteArray, at: Int, id: ByteArray): Boolean {
        if (at + id.size > b.size) return false
        for (i in id.indices) if (b[at + i] != id[i]) return false
        return true
    }

    /** Sisipkan XMP setelah APP0/Exif, buang XMP lama kalau ada. */
    private fun injectXmp(src: ByteArray, seg: ByteArray): ByteArray {
        require(src.size > 4 && src[0] == 0xFF.toByte() && src[1] == 0xD8.toByte()) { "Bukan file JPEG" }
        val out = ByteArrayOutputStream(src.size + seg.size)
        out.write(0xFF); out.write(0xD8)
        var pos = 2
        var inserted = false

        while (pos + 4 <= src.size && src[pos] == 0xFF.toByte()) {
            val marker = src[pos + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) break // mulai data gambar
            val len = ((src[pos + 2].toInt() and 0xFF) shl 8) or (src[pos + 3].toInt() and 0xFF)
            val end = pos + 2 + len
            if (end > src.size) break

            val isXmp = marker == 0xE1 && matches(src, pos + 4, XMP_ID)
            val isExif = marker == 0xE1 && matches(src, pos + 4, EXIF_ID)

            if (isXmp) { pos = end; continue } // drop XMP lama
            if (!inserted && marker != 0xE0 && !isExif) {
                out.write(seg); inserted = true
            }
            out.write(src, pos, end - pos)
            pos = end
        }
        if (!inserted) out.write(seg)
        out.write(src, pos, src.size - pos)
        return out.toByteArray()
    }
}
