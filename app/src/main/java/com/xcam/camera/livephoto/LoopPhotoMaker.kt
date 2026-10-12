package com.xcam.camera.livephoto

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.xcam.camera.gallery.MediaStoreHelper
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Loop Live Photo ala iOS, hasilnya satu file Motion Photo (JPG berisi video loop), bukan file video biasa.
 *
 * Isi video loop: video (sekitar 3 bagian) -> transisi video-ke-foto -> foto diam (sekitar 2 bagian) -> transisi foto-ke-video -> video lagi,
 * diulang sampai mendekati [targetSeconds]. Perbandingan video:foto dibuat 3:2.
 * Kedua transisi sama persis: crossfade [FADE_FRAMES] frame (0,3 detik).
 *
 * Satu siklus di-render sekali di GPU ([GlCycleRenderer]) lalu diulang lewat remux, jadi 60 detik tidak
 * perlu render 60 detik. Jika jalur GPU gagal di HP tertentu, dipakai jalur Canvas yang lebih lambat dan lebih buram.
 */
object LoopPhotoMaker {

    private const val FPS = 30
    private const val FRAME_US = 33_333L
    /** Lama transisi memudar (0,3 detik). */
    private const val FADE_FRAMES = 9
    private const val MAX_CLIP_SECONDS = 12

    class Cancelled : Exception()

    class Result(val photoUri: Uri, val videoUri: Uri?, val seconds: Int)

    private class Cycle(val cycleUs: Long, val videoStartUs: Long, val videoDurUs: Long)

    fun make(
        context: Context,
        videoFile: File,
        photoUri: Uri,
        targetSeconds: Int,
        withAudio: Boolean,
        saveMp4: Boolean,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    ): Result? {
        val stamp = System.nanoTime()
        val cycleFile = File(context.cacheDir, "live_loop_cycle_$stamp.mp4")
        val loopFile = File(context.cacheDir, "live_loop_final_$stamp.mp4")
        var photoOut: Uri? = null
        var videoOut: Uri? = null
        try {
            // ---- Info video
            val dims = videoSize(videoFile) ?: return null
            val w = dims.first
            val h = dims.second
            val durationMs = videoDurationMs(videoFile)
            if (durationMs <= 0) return null
            val clipSeconds = minOf(durationMs / 1000.0, MAX_CLIP_SECONDS.toDouble())
            // Perbandingan video : foto = 3 : 2
            val holdFrames = (clipSeconds * 2.0 / 3.0 * FPS).roundToInt().coerceIn(FPS, 8 * FPS)

            // ---- Foto
            val jpeg = LoopMotionWriter.readPrimaryJpeg(context, photoUri) ?: return null
            val photoRaw = MediaStoreHelper.loadBitmap(context, photoUri, max(w, h)) ?: return null
            val photoBmp = fit(photoRaw, w, h)

            // ---- Satu siklus
            val bitrate = (w.toLong() * h * FPS * 0.14).toLong().coerceIn(4_000_000L, 16_000_000L).toInt()
            var cycle: Cycle? = null
            try {
                val r = GlCycleRenderer(
                    videoFile, photoBmp, cycleFile, FADE_FRAMES, holdFrames,
                    (clipSeconds * 1_000_000L).toLong(), bitrate, onProgress, isCancelled
                ).render()
                cycle = Cycle(r.cycleUs, r.videoStartUs, r.videoDurUs)
            } catch (e: Cancelled) {
                throw e
            } catch (e: Exception) {
                cycle = null
            }
            if (cycle == null) {
                cycleFile.delete()
                cycle = renderCycleCanvas(videoFile, photoBmp, cycleFile, w, h, clipSeconds, holdFrames, bitrate, onProgress, isCancelled)
            }
            if (cycle == null || !cycleFile.exists() || cycleFile.length() == 0L) return null

            // ---- Ulangi siklus sampai mendekati target
            // Siklus terakhir dipotong tepat setelah bagian video, jadi loop berakhir di video, bukan di foto
            // (supaya saat diunggah ke TikTok foto tidak muncul dobel di ujung).
            val videoEndUs = cycle.videoStartUs + cycle.videoDurUs
            val repeats = max(1, (((targetSeconds * 1_000_000.0) - videoEndUs) / cycle.cycleUs).roundToInt() + 1)
            remuxRepeat(cycleFile, if (withAudio) videoFile else null, cycle, repeats, loopFile, isCancelled)
            onProgress(90)
            if (!loopFile.exists() || loopFile.length() == 0L) return null

            // ---- Simpan sebagai Motion Photo
            val resolver = context.contentResolver
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())
            val photoValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${MediaStoreHelper.LIVE_PREFIX}LOOP_${ts}_MP")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/XCamera")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val pUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoValues) ?: return null
            photoOut = pUri
            val ok = resolver.openOutputStream(pUri, "w")?.use { out ->
                LoopMotionWriter.write(jpeg, loopFile, out)
            } ?: false
            if (!ok) throw IllegalStateException("gagal menulis Motion Photo")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(pUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            onProgress(96)

            if (saveMp4) {
                val vValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "XCAM_LOOP_$ts")
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/XCamera")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                val vUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, vValues)
                if (vUri != null) {
                    videoOut = vUri
                    resolver.openOutputStream(vUri, "w")?.use { out -> loopFile.inputStream().use { it.copyTo(out, 256 * 1024) } }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        resolver.update(vUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    }
                }
            }
            onProgress(100)
            return Result(pUri, videoOut, (((repeats - 1) * cycle.cycleUs + videoEndUs) / 1_000_000L).toInt())
        } catch (e: Exception) {
            photoOut?.let { try { context.contentResolver.delete(it, null, null) } catch (_: Exception) { } }
            videoOut?.let { try { context.contentResolver.delete(it, null, null) } catch (_: Exception) { } }
            return null
        } finally {
            cycleFile.delete()
            loopFile.delete()
        }
    }

    // ---- Info video

    private fun videoSize(file: File): Pair<Int, Int>? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.path)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                    val vw = f.getInteger(MediaFormat.KEY_WIDTH)
                    val vh = f.getInteger(MediaFormat.KEY_HEIGHT)
                    val rot = if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
                    val swap = rot == 90 || rot == 270
                    return Pair((if (swap) vh else vw) and 1.inv(), (if (swap) vw else vh) and 1.inv())
                }
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            ex.release()
        }
    }

    private fun videoDurationMs(file: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            r.release()
        }
    }

    // ---- Ulangi siklus (remux, tanpa encode ulang)

    private fun remuxRepeat(
        cycleFile: File,
        audioSource: File?,
        cycle: Cycle,
        repeats: Int,
        dst: File,
        isCancelled: () -> Boolean
    ) {
        val exCycle = MediaExtractor()
        var exAudio: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var started = false
        try {
            exCycle.setDataSource(cycleFile.path)
            var cTrack = -1
            for (i in 0 until exCycle.trackCount) {
                if ((exCycle.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) cTrack = i
            }
            if (cTrack < 0) return
            exCycle.selectTrack(cTrack)
            val mx = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            val outVideo = mx.addTrack(exCycle.getTrackFormat(cTrack))

            var aTrack = -1
            var outAudio = -1
            if (audioSource != null) {
                val ea = MediaExtractor()
                exAudio = ea
                ea.setDataSource(audioSource.path)
                for (i in 0 until ea.trackCount) {
                    if ((ea.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                        aTrack = i
                        break
                    }
                }
                if (aTrack >= 0) {
                    ea.selectTrack(aTrack)
                    outAudio = mx.addTrack(ea.getTrackFormat(aTrack))
                }
            }
            mx.start()
            started = true

            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val bi = MediaCodec.BufferInfo()
            for (r in 0 until repeats) {
                val offset = r * cycle.cycleUs
                // Siklus terakhir berhenti di ujung bagian video (tanpa memudar ke foto dan foto diam).
                val limitUs = if (r == repeats - 1) cycle.videoStartUs + cycle.videoDurUs else Long.MAX_VALUE
                exCycle.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                while (true) {
                    buffer.clear()
                    val size = exCycle.readSampleData(buffer, 0)
                    if (size < 0) break
                    val sampleUs = exCycle.sampleTime
                    if (sampleUs > limitUs) break
                    bi.offset = 0
                    bi.size = size
                    bi.presentationTimeUs = sampleUs + offset
                    bi.flags = if ((exCycle.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    mx.writeSampleData(outVideo, buffer, bi)
                    exCycle.advance()
                }
                val ea = exAudio
                if (ea != null && outAudio >= 0) {
                    ea.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    while (true) {
                        buffer.clear()
                        val size = ea.readSampleData(buffer, 0)
                        if (size < 0) break
                        val t = ea.sampleTime
                        if (t > cycle.videoDurUs) break
                        bi.offset = 0
                        bi.size = size
                        bi.presentationTimeUs = t + offset + cycle.videoStartUs
                        bi.flags = 0
                        mx.writeSampleData(outAudio, buffer, bi)
                        ea.advance()
                    }
                }
                if (isCancelled()) throw Cancelled()
            }
        } finally {
            try { if (started) muxer?.stop() } catch (e: Exception) { }
            try { muxer?.release() } catch (e: Exception) { }
            exCycle.release()
            exAudio?.release()
        }
    }

    // ---- Jalur cadangan (Canvas): lebih lambat, kualitas lebih rendah. Dipakai hanya jika jalur GPU gagal.

    private fun renderCycleCanvas(
        videoFile: File,
        photoBmp: Bitmap,
        outFile: File,
        w: Int,
        h: Int,
        clipSeconds: Double,
        holdFrames: Int,
        bitrate: Int,
        onProgress: (Int) -> Unit,
        isCancelled: () -> Boolean
    ): Cycle? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.path)
            val clipFrames = (clipSeconds * FPS).toInt().coerceAtLeast(FPS / 2)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()
            val muxer = MediaMuxer(outFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val info = MediaCodec.BufferInfo()
            var track = -1
            var muxerStarted = false
            var written = 0L

            fun drain(end: Boolean) {
                var idle = 0
                while (true) {
                    val idx = encoder.dequeueOutputBuffer(info, if (end) 10_000L else 0L)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!end) return
                            if (++idle > 1000) return
                        }
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        idx >= 0 -> {
                            val buf = encoder.getOutputBuffer(idx)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                            if (buf != null && info.size > 0 && muxerStarted) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                info.presentationTimeUs = written * FRAME_US
                                muxer.writeSampleData(track, buf, info)
                                written++
                            }
                            encoder.releaseOutputBuffer(idx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                        }
                    }
                }
            }

            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            fun post(draw: (Canvas) -> Unit) {
                val canvas = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) surface.lockHardwareCanvas() else surface.lockCanvas(null)
                } catch (e: Exception) {
                    surface.lockCanvas(null)
                }
                try {
                    draw(canvas)
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                drain(false)
            }

            val total = clipFrames + FADE_FRAMES + holdFrames + FADE_FRAMES
            var done = 0
            fun step() {
                done++
                onProgress((done * 80 / total).coerceAtMost(80))
                if (isCancelled()) throw Cancelled()
            }

            try {
                val first = fit(frameAt(retriever, 0L) ?: return null, w, h)
                var last: Bitmap = first
                for (i in 0 until clipFrames) {
                    val bmp = if (i == 0) first else fit(frameAt(retriever, i * FRAME_US) ?: last, w, h)
                    post { it.drawBitmap(bmp, 0f, 0f, null) }
                    if (bmp !== last && last !== first) last.recycle()
                    last = bmp
                    step()
                }
                for (k in 1..FADE_FRAMES) {
                    paint.alpha = 255 * k / FADE_FRAMES
                    post {
                        it.drawBitmap(last, 0f, 0f, null)
                        it.drawBitmap(photoBmp, 0f, 0f, paint)
                    }
                    step()
                }
                for (k in 0 until holdFrames) {
                    post { it.drawBitmap(photoBmp, 0f, 0f, null) }
                    step()
                }
                for (k in 1..FADE_FRAMES) {
                    paint.alpha = 255 * k / FADE_FRAMES
                    post {
                        it.drawBitmap(photoBmp, 0f, 0f, null)
                        it.drawBitmap(first, 0f, 0f, paint)
                    }
                    step()
                }
                encoder.signalEndOfInputStream()
                drain(true)
            } finally {
                try { encoder.stop() } catch (e: Exception) { }
                encoder.release()
                surface.release()
                try { if (muxerStarted) muxer.stop() } catch (e: Exception) { }
                muxer.release()
            }
            if (written < total / 2) return null
            return Cycle(written * FRAME_US, 0L, clipFrames * FRAME_US)
        } catch (e: Cancelled) {
            throw e
        } catch (e: Exception) {
            return null
        } finally {
            retriever.release()
        }
    }

    private fun frameAt(r: MediaMetadataRetriever, tUs: Long): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
            r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST, params)
        } else {
            r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
        }
    } catch (e: Exception) {
        null
    }

    /** Isi penuh kanvas [w] x [h] (center-crop) supaya foto dan video selalu satu ukuran. */
    private fun fit(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val scale = max(w / src.width.toFloat(), h / src.height.toFloat())
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.translate((w - src.width * scale) / 2f, (h - src.height * scale) / 2f)
        c.scale(scale, scale)
        c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }
}
