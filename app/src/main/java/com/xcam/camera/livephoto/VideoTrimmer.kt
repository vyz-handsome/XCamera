package com.xcam.camera.livephoto

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Memotong awal video MP4 tanpa encode ulang (remux), dimulai dari keyframe terdekat sebelum titik potong. */
object VideoTrimmer {

    /**
     * @return waktu (mikrodetik) di video asli yang menjadi awal video hasil, atau -1 jika gagal.
     */
    fun trimStart(src: File, dst: File, startUs: Long): Long {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            val ex = MediaExtractor()
            extractor = ex
            ex.setDataSource(src.path)

            var rotation = 0
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(src.path)
                rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
            } finally {
                retriever.release()
            }

            val mx = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            mx.setOrientationHint(rotation)

            val trackMap = HashMap<Int, Int>()
            var videoTrack = -1
            for (i in 0 until ex.trackCount) {
                val format = ex.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    trackMap[i] = mx.addTrack(format)
                    if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i
                }
            }
            if (videoTrack < 0) return -1

            // Cari keyframe sebelum titik potong lewat jalur video saja.
            ex.selectTrack(videoTrack)
            ex.seekTo(startUs.coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val baseUs = ex.sampleTime
            if (baseUs < 0) return -1
            // Lalu aktifkan audio dan mulai dari titik itu.
            for (i in trackMap.keys) ex.selectTrack(i)
            ex.seekTo(baseUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            mx.start()
            muxerStarted = true

            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = ex.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = ex.sampleTime - baseUs
                val track = trackMap[ex.sampleTrackIndex]
                if (pts >= 0 && track != null) {
                    info.offset = 0
                    info.size = size
                    info.presentationTimeUs = pts
                    info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    mx.writeSampleData(track, buffer, info)
                }
                ex.advance()
            }
            return baseUs
        } catch (e: Exception) {
            return -1
        } finally {
            try {
                if (muxerStarted) muxer?.stop()
            } catch (e: Exception) {
            }
            try {
                muxer?.release()
            } catch (e: Exception) {
            }
            try {
                extractor?.release()
            } catch (e: Exception) {
            }
        }
    }
}
