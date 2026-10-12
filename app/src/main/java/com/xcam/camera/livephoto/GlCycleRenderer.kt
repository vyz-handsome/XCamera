package com.xcam.camera.livephoto

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Render satu siklus loop dengan jalur GPU: decoder video -> OpenGL (campur dengan foto) -> encoder.
 * Frame tidak pernah diubah jadi Bitmap, jadi tidak ada penurunan kualitas seperti di jalur Canvas.
 *
 * Urutan satu siklus: [foto memudar ke frame pertama] -> [video] -> [frame terakhir memudar ke foto] -> [foto diam].
 * Siklus diulang oleh pemanggil lewat remux.
 */
class GlCycleRenderer(
    private val videoFile: File,
    private val photo: Bitmap,
    private val outFile: File,
    private val fadeFrames: Int,
    private val holdFrames: Int,
    private val maxClipUs: Long,
    private val bitrate: Int,
    private val onProgress: (Int) -> Unit,
    private val isCancelled: () -> Boolean
) {

    class Result(val cycleUs: Long, val videoStartUs: Long, val videoDurUs: Long, val width: Int, val height: Int)

    private companion object {
        const val FRAME_US = 33_333L
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val VERTEX = """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            varying vec2 vVideoCoord;
            varying vec2 vPhotoCoord;
            void main() {
                gl_Position = aPosition;
                vVideoCoord = (uTexMatrix * aTexCoord).xy;
                vPhotoCoord = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
            }
        """
        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vVideoCoord;
            varying vec2 vPhotoCoord;
            uniform samplerExternalOES sVideo;
            uniform sampler2D sPhoto;
            uniform float uVideoAlpha;
            void main() {
                vec3 v = texture2D(sVideo, vVideoCoord).rgb;
                vec3 p = texture2D(sPhoto, vPhotoCoord).rgb;
                gl_FragColor = vec4(mix(p, v, uVideoAlpha), 1.0);
            }
        """
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var videoTex = 0
    private var photoTex = 0
    private val texMatrix = FloatArray(16)
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }

    private lateinit var encoder: MediaCodec
    private lateinit var muxer: MediaMuxer
    private var muxerTrack = -1
    private var muxerStarted = false
    private val encInfo = MediaCodec.BufferInfo()

    private var outW = 0
    private var outH = 0
    private var drawn = 0
    private var totalEst = 1

    fun render(): Result {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var surfaceTexture: SurfaceTexture? = null
        var decoderSurface: Surface? = null
        var encoderSurface: Surface? = null
        try {
            extractor.setDataSource(videoFile.path)
            var vTrack = -1
            for (i in 0 until extractor.trackCount) {
                if ((extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                    vTrack = i
                    break
                }
            }
            if (vTrack < 0) throw IllegalStateException("tidak ada track video")
            extractor.selectTrack(vTrack)
            val inFormat = extractor.getTrackFormat(vTrack)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val vw = inFormat.getInteger(MediaFormat.KEY_WIDTH)
            val vh = inFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rot = if (inFormat.containsKey(MediaFormat.KEY_ROTATION)) inFormat.getInteger(MediaFormat.KEY_ROTATION) else 0
            val swap = rot == 90 || rot == 270
            outW = (if (swap) vh else vw) and 1.inv()
            outH = (if (swap) vw else vh) and 1.inv()

            // ---- Encoder
            encoder = createEncoder(outW, outH)
            encoderSurface = encoder.createInputSurface()
            encoder.start()
            muxer = MediaMuxer(outFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // ---- EGL + GL
            setupEgl(encoderSurface)
            setupGl()

            // ---- Decoder keluar ke SurfaceTexture
            val frames = LinkedBlockingQueue<Boolean>()
            val st = SurfaceTexture(videoTex)
            surfaceTexture = st
            st.setOnFrameAvailableListener { frames.offer(true) }
            val dsurface = Surface(st)
            decoderSurface = dsurface
            val dec = MediaCodec.createDecoderByType(mime)
            decoder = dec
            dec.configure(inFormat, dsurface, null, 0)
            dec.start()

            val estClip = (maxClipUs / FRAME_US).toInt().coerceAtLeast(1)
            totalEst = estClip + fadeFrames * 2 + holdFrames

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var firstPts = -1L
            var firstIn = -1L
            var lastOutUs = 0L
            val videoStartUs = fadeFrames * FRAME_US

            while (!outputDone) {
                if (isCancelled()) throw LoopPhotoMaker.Cancelled()
                if (!inputDone) {
                    val ii = dec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = dec.getInputBuffer(ii)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val t = extractor.sampleTime
                            if (firstIn < 0) firstIn = t
                            if (t - firstIn > maxClipUs) {
                                dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                dec.queueInputBuffer(ii, 0, size, t, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    val render = info.size != 0
                    dec.releaseOutputBuffer(oi, render)
                    if (render) {
                        if (frames.poll(3, TimeUnit.SECONDS) == null) throw IllegalStateException("frame video tidak datang")
                        st.updateTexImage()
                        st.getTransformMatrix(texMatrix)
                        if (firstPts < 0) {
                            firstPts = info.presentationTimeUs
                            // Foto memudar ke frame pertama video.
                            for (j in 0 until fadeFrames) {
                                draw((j + 1f) / (fadeFrames + 1f), j * FRAME_US)
                            }
                        }
                        lastOutUs = videoStartUs + (info.presentationTimeUs - firstPts)
                        draw(1f, lastOutUs)
                    }
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
            if (firstPts < 0) throw IllegalStateException("video tidak menghasilkan frame")
            val videoDurUs = lastOutUs - videoStartUs

            // Frame terakhir memudar ke foto, lalu foto diam. (Tekstur video masih menyimpan frame terakhir.)
            var t = lastOutUs
            for (j in 1..fadeFrames) {
                t += FRAME_US
                draw(1f - j.toFloat() / (fadeFrames + 1f), t)
            }
            for (j in 0 until holdFrames) {
                t += FRAME_US
                draw(0f, t)
            }
            val cycleUs = t + FRAME_US

            encoder.signalEndOfInputStream()
            drainEncoder(true)
            return Result(cycleUs, videoStartUs, videoDurUs, outW, outH)
        } finally {
            try { decoder?.stop() } catch (e: Exception) { }
            try { decoder?.release() } catch (e: Exception) { }
            try { surfaceTexture?.release() } catch (e: Exception) { }
            try { decoderSurface?.release() } catch (e: Exception) { }
            try { extractor.release() } catch (e: Exception) { }
            try { if (::encoder.isInitialized) encoder.stop() } catch (e: Exception) { }
            try { if (::encoder.isInitialized) encoder.release() } catch (e: Exception) { }
            try { encoderSurface?.release() } catch (e: Exception) { }
            try { if (muxerStarted) muxer.stop() } catch (e: Exception) { }
            try { if (::muxer.isInitialized) muxer.release() } catch (e: Exception) { }
            releaseEgl()
        }
    }

    // ---- Menggambar satu frame

    private fun draw(videoAlpha: Float, ptsUs: Long) {
        GLES20.glViewport(0, 0, outW, outH)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoTex)

        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sVideo"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sPhoto"), 1)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uVideoAlpha"), videoAlpha)

        val pos = GLES20.glGetAttribLocation(program, "aPosition")
        val tex = GLES20.glGetAttribLocation(program, "aTexCoord")
        quad.position(0)
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(pos)
        quad.position(2)
        GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(tex)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, ptsUs * 1000L)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        drainEncoder(false)

        drawn++
        onProgress((drawn * 80 / totalEst).coerceAtMost(80))
    }

    // ---- Encoder

    private fun createEncoder(w: Int, h: Int): MediaCodec {
        fun build(withProfile: Boolean): MediaCodec {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                if (withProfile) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                }
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                codec.release()
                throw e
            }
            return codec
        }
        return try {
            build(true)
        } catch (e: Exception) {
            build(false)
        }
    }

    private fun drainEncoder(end: Boolean) {
        var idle = 0
        while (true) {
            val idx = encoder.dequeueOutputBuffer(encInfo, if (end) 10_000L else 0L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end) return
                    if (++idle > 1000) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    muxerTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    val buf = encoder.getOutputBuffer(idx)
                    if ((encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) encInfo.size = 0
                    if (buf != null && encInfo.size > 0 && muxerStarted) {
                        buf.position(encInfo.offset)
                        buf.limit(encInfo.offset + encInfo.size)
                        muxer.writeSampleData(muxerTrack, buf, encInfo)
                    }
                    encoder.releaseOutputBuffer(idx, false)
                    if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    // ---- EGL / GL

    private fun setupEgl(target: Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "EGL display tidak ada" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "EGL gagal init" }
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "EGL config tidak ada" }
        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], target, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "EGL makeCurrent gagal" }
    }

    private fun releaseEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "shader gagal: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    private fun setupGl() {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program gagal link" }

        val ids = IntArray(2)
        GLES20.glGenTextures(2, ids, 0)
        videoTex = ids[0]
        photoTex = ids[1]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, photoTex)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, photo, 0)

        Matrix.setIdentityM(texMatrix, 0)
    }
}
