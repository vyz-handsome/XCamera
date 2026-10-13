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

    /**
     * Potong awal [a] mulai [startUs] (keyframe terdekat sebelumnya), lalu sambung [b] tepat di ujung [a], tanpa encode ulang.
     * Dipakai Live Photo "foto asli": [a] = rekaman sebelum shutter, [b] = rekaman sesudah foto diambil.
     * Kedua file direkam dengan pengaturan yang sama, jadi format trek-nya identik.
     *
     * @return waktu (mikrodetik) di video hasil tempat [b] dimulai (= saat foto diambil), atau -1 jika gagal.
     */
    fun trimAndJoin(a: File, b: File, dst: File, startUs: Long): Long {
        val exA = MediaExtractor()
        val exB = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            exA.setDataSource(a.path)
            exB.setDataSource(b.path)

            var rotation = 0
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(a.path)
                rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            } finally {
                retriever.release()
            }

            fun trackOf(ex: MediaExtractor, prefix: String): Int {
                for (i in 0 until ex.trackCount) {
                    if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith(prefix)) return i
                }
                return -1
            }
            val vA = trackOf(exA, "video/")
            val vB = trackOf(exB, "video/")
            if (vA < 0 || vB < 0) return -1
            // Audio hanya dipakai jika ada di kedua file (supaya trek audio tidak bolong).
            val aA = trackOf(exA, "audio/")
            val aB = trackOf(exB, "audio/")
            val useAudio = aA >= 0 && aB >= 0

            val mx = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            mx.setOrientationHint(rotation)
            val outV = mx.addTrack(exA.getTrackFormat(vA))
            val outA = if (useAudio) mx.addTrack(exA.getTrackFormat(aA)) else -1

            // Mulai dari keyframe sebelum titik potong.
            exA.selectTrack(vA)
            exA.seekTo(startUs.coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val baseA = exA.sampleTime
            if (baseA < 0) return -1
            if (useAudio) exA.selectTrack(aA)
            exA.seekTo(baseA, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            mx.start()
            muxerStarted = true

            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = android.media.MediaCodec.BufferInfo()
            var lastV = -1L
            var prevV = -1L
            var lastA = -1L
            fun write(ex: MediaExtractor, outTrack: Int, pts: Long, size: Int) {
                info.offset = 0
                info.size = size
                info.presentationTimeUs = pts
                info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                mx.writeSampleData(outTrack, buffer, info)
            }

            // Bagian A
            while (true) {
                buffer.clear()
                val size = exA.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = exA.sampleTime - baseA
                if (pts >= 0) {
                    if (exA.sampleTrackIndex == vA) {
                        write(exA, outV, pts, size)
                        prevV = lastV
                        lastV = pts
                    } else if (useAudio && exA.sampleTrackIndex == aA) {
                        write(exA, outA, pts, size)
                        lastA = pts
                    }
                }
                exA.advance()
            }
            if (lastV < 0) return -1
            val frameUs = if (prevV >= 0 && lastV > prevV) lastV - prevV else 33_333L
            val joinUs = lastV + frameUs

            // Bagian B, disambung di joinUs
            exB.selectTrack(vB)
            if (useAudio) exB.selectTrack(aB)
            exB.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            var baseB = -1L
            while (true) {
                buffer.clear()
                val size = exB.readSampleData(buffer, 0)
                if (size < 0) break
                val t = exB.sampleTime
                if (exB.sampleTrackIndex == vB) {
                    if (baseB < 0) baseB = t
                    write(exB, outV, joinUs + (t - baseB), size)
                } else if (useAudio && exB.sampleTrackIndex == aB) {
                    if (baseB < 0) {
                        // Audio datang lebih dulu dari video pertama: pakai waktunya sebagai acuan sementara.
                        exB.advance()
                        continue
                    }
                    val pts = maxOf(joinUs + (t - baseB), lastA + 1)
                    write(exB, outA, pts, size)
                    lastA = pts
                }
                exB.advance()
            }
            return joinUs
        } catch (e: Exception) {
            return -1
        } finally {
            try { if (muxerStarted) muxer?.stop() } catch (e: Exception) { }
            try { muxer?.release() } catch (e: Exception) { }
            try { exA.release() } catch (e: Exception) { }
            try { exB.release() } catch (e: Exception) { }
        }
    }
}
