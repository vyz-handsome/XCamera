package com.xcam.camera.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix as GlMatrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.core.util.Consumer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/*
 * EIS buatan sendiri (software) untuk XCAM.
 *
 * Cara kerja (mirip EIS hardware HP):
 *  1. Gyroscope dibaca terus-menerus dan diintegrasikan jadi orientasi kamera (quaternion) per sampel.
 *  2. Untuk tiap frame video, orientasi kamera saat frame itu diambil dicari dari timestamp frame.
 *  3. Orientasi "virtual camera" didapat dengan low-pass (2 tahap) dari orientasi asli: gerakan
 *     pelan/sengaja (pan) diikuti, getaran tangan dibuang.
 *  4. Selisih orientasi asli vs virtual dikoreksi di GPU dengan memutar sinar pandang tiap piksel
 *     (homografi rotasi), sambil sedikit di-crop supaya ada ruang koreksi.
 *
 * Dipasang sebagai CameraEffect pada VideoCapture, jadi otomatis dipakai oleh mode Video dan
 * klip video di dalam Live Photo. Preview tidak diubah.
 */

private const val TAG = "XCamSoftEis"

/** Operasi quaternion kecil (urutan w, x, y, z). */
internal object Quat {
    fun mul(a: DoubleArray, b: DoubleArray): DoubleArray = doubleArrayOf(
        a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
        a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
        a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
        a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0]
    )

    fun conj(a: DoubleArray) = doubleArrayOf(a[0], -a[1], -a[2], -a[3])

    fun normalize(a: DoubleArray): DoubleArray {
        val n = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2] + a[3] * a[3])
        if (n < 1e-12) return doubleArrayOf(1.0, 0.0, 0.0, 0.0)
        return doubleArrayOf(a[0] / n, a[1] / n, a[2] / n, a[3] / n)
    }

    fun slerp(a: DoubleArray, b0: DoubleArray, t: Double): DoubleArray {
        var dot = a[0] * b0[0] + a[1] * b0[1] + a[2] * b0[2] + a[3] * b0[3]
        val b = if (dot < 0) { dot = -dot; doubleArrayOf(-b0[0], -b0[1], -b0[2], -b0[3]) } else b0
        if (dot > 0.9995) {
            return normalize(
                doubleArrayOf(
                    a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t,
                    a[2] + (b[2] - a[2]) * t, a[3] + (b[3] - a[3]) * t
                )
            )
        }
        val theta = acos(dot.coerceIn(-1.0, 1.0))
        val sinT = sin(theta)
        val wa = sin((1 - t) * theta) / sinT
        val wb = sin(t * theta) / sinT
        return doubleArrayOf(
            a[0] * wa + b[0] * wb, a[1] * wa + b[1] * wb,
            a[2] * wa + b[2] * wb, a[3] * wa + b[3] * wb
        )
    }

    /** Vektor rotasi (sumbu * sudut, radian). */
    fun toRotVec(q0: DoubleArray): DoubleArray {
        val q = if (q0[0] < 0) doubleArrayOf(-q0[0], -q0[1], -q0[2], -q0[3]) else q0
        val vn = sqrt(q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (vn < 1e-12) return doubleArrayOf(0.0, 0.0, 0.0)
        val angle = 2.0 * Math.atan2(vn, q[0])
        val k = angle / vn
        return doubleArrayOf(q[1] * k, q[2] * k, q[3] * k)
    }

    fun fromRotVec(v: DoubleArray): DoubleArray {
        val angle = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (angle < 1e-12) return doubleArrayOf(1.0, 0.0, 0.0, 0.0)
        val s = sin(angle / 2) / angle
        return doubleArrayOf(cos(angle / 2), v[0] * s, v[1] * s, v[2] * s)
    }

    /** Matriks rotasi 3x3 kolom-utama (siap untuk glUniformMatrix3fv). */
    fun toMat3(q: DoubleArray, out: FloatArray) {
        val w = q[0]; val x = q[1]; val y = q[2]; val z = q[3]
        val r00 = 1 - 2 * (y * y + z * z); val r01 = 2 * (x * y - z * w); val r02 = 2 * (x * z + y * w)
        val r10 = 2 * (x * y + z * w); val r11 = 1 - 2 * (x * x + z * z); val r12 = 2 * (y * z - x * w)
        val r20 = 2 * (x * z - y * w); val r21 = 2 * (y * z + x * w); val r22 = 1 - 2 * (x * x + y * y)
        out[0] = r00.toFloat(); out[1] = r10.toFloat(); out[2] = r20.toFloat()
        out[3] = r01.toFloat(); out[4] = r11.toFloat(); out[5] = r21.toFloat()
        out[6] = r02.toFloat(); out[7] = r12.toFloat(); out[8] = r22.toFloat()
    }
}

/**
 * Membaca gyroscope dan menyimpan riwayat orientasi kamera (bingkai sensor kamera: X kanan, Y bawah,
 * Z ke depan) supaya bisa ditanyakan per timestamp frame.
 */
class GyroTracker(context: Context) : SensorEventListener {

    private val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    val available: Boolean get() = sensor != null

    private var thread: HandlerThread? = null
    private val lock = Any()
    private val times = LongArray(CAP)
    private val quats = DoubleArray(CAP * 4)
    private var head = 0
    private var count = 0
    private var q = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
    private var lastT = 0L
    private val axes = DoubleArray(9) // Xc, Yc, Zc dalam koordinat perangkat

    @Volatile var sampleCount = 0L
        private set

    /** @param cameraAxes 9 angka: sumbu X, Y, Z kamera dinyatakan dalam koordinat perangkat. */
    fun start(cameraAxes: DoubleArray): Boolean {
        val s = sensor ?: return false
        stop()
        synchronized(lock) {
            System.arraycopy(cameraAxes, 0, axes, 0, 9)
            head = 0; count = 0; lastT = 0L
            q = doubleArrayOf(1.0, 0.0, 0.0, 0.0)
            sampleCount = 0
        }
        val t = HandlerThread("xcam-gyro").also { it.start() }
        thread = t
        return try {
            sm.registerListener(this, s, SAMPLE_PERIOD_US, Handler(t.looper))
        } catch (e: Exception) {
            Log.w(TAG, "gyro gagal didaftarkan", e)
            false
        }
    }

    fun stop() {
        try { sm.unregisterListener(this) } catch (e: Exception) {}
        thread?.quitSafely()
        thread = null
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val t = e.timestamp
        synchronized(lock) {
            if (lastT == 0L) {
                lastT = t
                push(t)
                return
            }
            val dt = (t - lastT) * 1e-9
            lastT = t
            if (dt <= 0.0 || dt > 0.1) { push(t); return }
            val wx = e.values[0].toDouble(); val wy = e.values[1].toDouble(); val wz = e.values[2].toDouble()
            // Kecepatan sudut perangkat -> bingkai kamera.
            val cx = wx * axes[0] + wy * axes[1] + wz * axes[2]
            val cy = wx * axes[3] + wy * axes[4] + wz * axes[5]
            val cz = wx * axes[6] + wy * axes[7] + wz * axes[8]
            val mag = sqrt(cx * cx + cy * cy + cz * cz)
            val theta = mag * dt
            if (theta > 1e-9) {
                val s = sin(theta / 2) / mag
                val dq = doubleArrayOf(cos(theta / 2), cx * s, cy * s, cz * s)
                q = Quat.normalize(Quat.mul(q, dq)) // rotasi di bingkai badan: kanan-kali
            }
            push(t)
        }
    }

    private fun push(t: Long) {
        times[head] = t
        val b = head * 4
        quats[b] = q[0]; quats[b + 1] = q[1]; quats[b + 2] = q[2]; quats[b + 3] = q[3]
        head = (head + 1) % CAP
        if (count < CAP) count++
        sampleCount++
    }

    /**
     * Orientasi kamera pada waktu [tNs] (basis waktu elapsedRealtime, sama dengan SensorEvent).
     * @return 0 berhasil, -1 belum ada data, 1 timestamp di luar rentang data.
     */
    fun orientationAt(tNs: Long, out: DoubleArray): Int {
        synchronized(lock) {
            if (count < 2) return -1
            val newestIdx = (head - 1 + CAP) % CAP
            val newestT = times[newestIdx]
            val oldestT = times[(head - count + CAP) % CAP]
            if (tNs < oldestT) return 1
            if (tNs >= newestT) {
                if (tNs - newestT > MAX_AHEAD_NS) return 1
                copyQ(newestIdx, out)
                return 0
            }
            var k = 0
            while (k < count - 1) {
                val i = (head - 1 - k - 1 + 2 * CAP) % CAP
                val j = (i + 1) % CAP
                if (times[i] <= tNs) {
                    val span = (times[j] - times[i]).toDouble()
                    val f = if (span <= 0) 0.0 else (tNs - times[i]) / span
                    val a = DoubleArray(4); val b = DoubleArray(4)
                    copyQ(i, a); copyQ(j, b)
                    val r = Quat.slerp(a, b, f)
                    System.arraycopy(r, 0, out, 0, 4)
                    return 0
                }
                k++
            }
            return 1
        }
    }

    private fun copyQ(idx: Int, out: DoubleArray) {
        val b = idx * 4
        out[0] = quats[b]; out[1] = quats[b + 1]; out[2] = quats[b + 2]; out[3] = quats[b + 3]
    }

    companion object {
        private const val CAP = 2048
        private const val SAMPLE_PERIOD_US = 2500 // 400 Hz diminta; HP membatasi sesuai kemampuannya
        private const val MAX_AHEAD_NS = 80_000_000L
    }
}

/** Data kamera aktif yang dibutuhkan stabilizer. */
class SoftEisCamera(
    sensorOrientation: Int,
    facingBack: Boolean,
    /** setengah lebar bidang pandang pada zoom 1x: tan(HFOV/2) sepanjang sumbu X sensor. */
    val tanHalfX: Double,
    /** true bila timestamp frame satu basis dengan sensor (REALTIME); false = MONOTONIC. */
    val timestampRealtime: Boolean
) {
    /** Sumbu X, Y, Z kamera dalam koordinat perangkat (x kanan, y atas, z keluar layar). */
    val axes: DoubleArray

    init {
        val th = Math.toRadians(sensorOrientation.toDouble())
        val c = cos(th); val s = sin(th)
        val rx = if (facingBack) 1.0 else -1.0 // "kanan" gambar tegak dalam sumbu perangkat
        val xc = doubleArrayOf(c * rx, -s, 0.0)
        val yc = doubleArrayOf(-s * rx, -c, 0.0)
        val zc = doubleArrayOf(
            xc[1] * yc[2] - xc[2] * yc[1],
            xc[2] * yc[0] - xc[0] * yc[2],
            xc[0] * yc[1] - xc[1] * yc[0]
        )
        axes = doubleArrayOf(xc[0], xc[1], xc[2], yc[0], yc[1], yc[2], zc[0], zc[1], zc[2])
    }
}

/**
 * SurfaceProcessor OpenGL: menerima frame kamera, memutar sudut pandang sesuai koreksi gyro,
 * lalu menggambar ke Surface encoder.
 */
class SoftEisProcessor(context: Context) : SurfaceProcessor {

    private val appContext = context.applicationContext
    private val glThread = HandlerThread("xcam-eis-gl").also { it.start() }
    private val glHandler = Handler(glThread.looper)
    val executor: Executor = Executor { r -> glHandler.post(r) }

    private val gyro = GyroTracker(appContext)
    val gyroAvailable: Boolean get() = gyro.available

    // ---- Konfigurasi (diatur dari luar, dibaca di thread GL)
    @Volatile private var cam: SoftEisCamera? = null
    @Volatile private var nativeZoom = 1f
    @Volatile private var cropZoom = 1.10f
    @Volatile private var tau = 0.10
    @Volatile var bypassProvider: (() -> Boolean)? = null

    // ---- Statistik untuk info debug
    @Volatile var framesTotal = 0L
        private set
    @Volatile var framesCorrected = 0L
        private set
    @Volatile var status = "belum dipakai"
        private set

    fun setCamera(c: SoftEisCamera?) { cam = c }
    fun setNativeZoom(z: Float) { nativeZoom = z.coerceAtLeast(0.1f) }
    fun setStrength(strong: Boolean) {
        cropZoom = if (strong) 1.20f else 1.10f
        tau = if (strong) 0.18 else 0.10
    }

    // ---- Keadaan GL (hanya dipakai di thread GL)
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var glReady = false
    private var program = 0
    private var texId = 0
    private var uA = 0
    private var uOrig = 0
    private var uRot = 0
    private var uTan = 0
    private var uCrop = 0
    private var uPass = 0
    private var uTexMat = 0
    private var aPos = 0
    private lateinit var quad: FloatBuffer

    private var inputTexture: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var inW = 1
    private var inH = 1

    private class Out(val output: SurfaceOutput, val egl: EGLSurface)
    private val outputs = ArrayList<Out>()

    // ---- Keadaan filter
    private var qs1: DoubleArray? = null
    private var qs2: DoubleArray? = null
    private var lastFrameNs = 0L
    private val qa = DoubleArray(4)
    private val rot = FloatArray(9)
    private val texMat = FloatArray(16)
    private val invTex = FloatArray(16)
    private val upd = FloatArray(16)
    private val aMat = FloatArray(16)
    private var correctionOn = false
    private var invalidStreak = 0

    // ---- API SurfaceProcessor

    override fun onInputSurface(request: SurfaceRequest) {
        if (!ensureGl()) {
            status = "GL gagal dibuat"
            request.willNotProvideSurface()
            return
        }
        releaseInput()
        val res = request.resolution
        inW = res.width; inH = res.height
        val st = SurfaceTexture(texId)
        st.setDefaultBufferSize(inW, inH)
        val surface = Surface(st)
        inputTexture = st
        inputSurface = surface
        st.setOnFrameAvailableListener({ onFrame() }, glHandler)
        resetFilter()

        val c = cam
        if (c != null && gyro.available && gyro.start(c.axes)) {
            status = "aktif, menunggu frame"
        } else {
            status = if (!gyro.available) "gyroscope tidak ada" else "kamera/gyro belum siap"
        }
        request.provideSurface(surface, executor) { _ ->
            // Surface sudah tidak dipakai kamera.
            if (inputSurface === surface) releaseInput()
            else { try { surface.release() } catch (e: Exception) {}; try { st.release() } catch (e: Exception) {} }
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (!ensureGl()) { surfaceOutput.close(); return }
        val surface = surfaceOutput.getSurface(executor) { _ ->
            // Permintaan menutup output (misalnya saat unbind).
            val entry = outputs.firstOrNull { o -> o.output === surfaceOutput }
            if (entry != null) {
                outputs.remove(entry)
                if (eglDisplay != EGL14.EGL_NO_DISPLAY) EGL14.eglDestroySurface(eglDisplay, entry.egl)
            }
            surfaceOutput.close()
        }
        val es = try {
            EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
        } catch (e: Exception) { null }
        if (es == null || es == EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "EGL surface output gagal")
            surfaceOutput.close()
            return
        }
        outputs.add(Out(surfaceOutput, es))
    }

    /** Dipanggil saat controller dilepas. */
    fun shutdown() {
        glHandler.post {
            releaseInput()
            for (o in outputs.toList()) {
                try { o.output.close() } catch (e: Exception) {}
            }
            outputs.clear()
            releaseGl()
            glThread.quitSafely()
        }
    }

    // ---- Inti per frame

    private fun onFrame() {
        val st = inputTexture ?: return
        try {
            st.updateTexImage()
        } catch (e: Exception) {
            return
        }
        if (outputs.isEmpty()) return
        st.getTransformMatrix(texMat)
        val ts = st.timestamp
        framesTotal++
        GlMatrix.invertM(invTex, 0, texMat, 0)

        var first = true
        for (o in outputs.toList()) {
            if (!EGL14.eglMakeCurrent(eglDisplay, o.egl, o.egl, eglContext)) continue
            o.output.updateTransformMatrix(upd, texMat)
            GlMatrix.multiplyMM(aMat, 0, invTex, 0, upd, 0)

            val c = cam
            val zoom = nativeZoom.toDouble()
            val tanX = if (c != null) c.tanHalfX / zoom else 1.0
            val tanY = tanX * inH.toDouble() / inW.toDouble()

            if (first) {
                first = false
                computeCorrection(ts, tanX, tanY)
            }

            val w = IntArray(1); val h = IntArray(1)
            EGL14.eglQuerySurface(eglDisplay, o.egl, EGL14.EGL_WIDTH, w, 0)
            EGL14.eglQuerySurface(eglDisplay, o.egl, EGL14.EGL_HEIGHT, h, 0)
            GLES20.glViewport(0, 0, max(w[0], 1), max(h[0], 1))
            draw(tanX, tanY)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, o.egl, ts)
            EGL14.eglSwapBuffers(eglDisplay, o.egl)
        }
    }

    /** Menghitung rotasi koreksi [rot] untuk frame ber-timestamp [tsNs]. Mengisi [correctionOn]. */
    private fun computeCorrection(tsNs: Long, tanX: Double, tanY: Double) {
        correctionOn = false
        val c = cam
        if (c == null || !gyro.available) { status = "kamera/gyro belum siap"; return }
        if (bypassProvider?.invoke() == true) { status = "EIS hardware aktif, software istirahat"; return }

        val clockShift = if (c.timestampRealtime) 0L else (SystemClock.elapsedRealtimeNanos() - System.nanoTime())
        val t = tsNs + clockShift + CENTER_DELAY_NS
        val r = gyro.orientationAt(t, qa)
        if (r != 0) {
            invalidStreak++
            status = if (r < 0) "menunggu data gyro" else "timestamp frame tidak cocok dengan gyro"
            if (invalidStreak > 15) resetFilter()
            return
        }
        invalidStreak = 0

        var s1 = qs1
        var s2 = qs2
        if (s1 == null || s2 == null || lastFrameNs == 0L) {
            s1 = qa.copyOf(); s2 = qa.copyOf()
        } else {
            val dt = ((tsNs - lastFrameNs) * 1e-9).coerceIn(0.001, 0.25)
            val alpha = 1.0 - exp(-dt / tau)
            s1 = Quat.slerp(s1, qa, alpha)
            s2 = Quat.slerp(s2, s1, alpha)
        }
        lastFrameNs = tsNs

        // Rotasi dari bingkai kamera virtual ke bingkai kamera asli.
        var rel = Quat.mul(Quat.conj(qa), s2)
        val rv = Quat.toRotVec(rel)

        // Batas koreksi dari ruang crop yang tersedia (pecahan lebar/tinggi buffer yang dipakai output).
        val crop = cropZoom.toDouble()
        val fx = abs(aMat[0]) + abs(aMat[4])
        val fy = abs(aMat[1]) + abs(aMat[5])
        val mx = max(0.0, 1.0 - fx / crop)
        val my = max(0.0, 1.0 - fy / crop)
        val limY = atan(mx * tanX) * 0.7 // putar sumbu Y = geser horizontal
        val limX = atan(my * tanY) * 0.7 // putar sumbu X = geser vertikal
        val limZ = 0.4 * min(mx * tanX / tanY, my * tanY / tanX) // roll

        // State dijepit di 1,5x batas (anti-windup), keluaran dilembutkan dengan tanh.
        val hx = limX * 1.5; val hy = limY * 1.5; val hz = limZ * 1.5
        val cx = rv[0].coerceIn(-hx, hx)
        val cy = rv[1].coerceIn(-hy, hy)
        val cz = rv[2].coerceIn(-hz, hz)
        if (cx != rv[0] || cy != rv[1] || cz != rv[2]) {
            rel = Quat.fromRotVec(doubleArrayOf(cx, cy, cz))
            s2 = Quat.normalize(Quat.mul(qa, rel))
            s1 = s2.copyOf()
        }
        qs1 = s1; qs2 = s2

        val ox = if (limX > 1e-6) limX * tanh(cx / limX) else 0.0
        val oy = if (limY > 1e-6) limY * tanh(cy / limY) else 0.0
        val oz = if (limZ > 1e-6) limZ * tanh(cz / limZ) else 0.0
        Quat.toMat3(Quat.fromRotVec(doubleArrayOf(ox, oy, oz)), rot)
        correctionOn = true
        framesCorrected++
        status = "aktif"
    }

    private fun resetFilter() {
        qs1 = null; qs2 = null; lastFrameNs = 0L; invalidStreak = 0
    }

    private fun draw(tanX: Double, tanY: Double) {
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        val on = correctionOn
        GLES20.glUniform1f(uPass, if (on) 0f else 1f)
        GLES20.glUniformMatrix4fv(uTexMat, 1, false, upd, 0)
        GLES20.glUniformMatrix4fv(uA, 1, false, aMat, 0)
        GLES20.glUniformMatrix4fv(uOrig, 1, false, texMat, 0)
        if (on) GLES20.glUniformMatrix3fv(uRot, 1, false, rot, 0)
        GLES20.glUniform2f(uTan, tanX.toFloat(), tanY.toFloat())
        GLES20.glUniform1f(uCrop, if (on) cropZoom else 1f)
        quad.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    // ---- GL setup

    private fun releaseInput() {
        gyro.stop()
        try { inputTexture?.setOnFrameAvailableListener(null) } catch (e: Exception) {}
        try { inputSurface?.release() } catch (e: Exception) {}
        try { inputTexture?.release() } catch (e: Exception) {}
        inputSurface = null
        inputTexture = null
    }

    private fun ensureGl(): Boolean {
        if (glReady) return true
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) return false
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, n, 0) || n[0] <= 0) return false
            eglConfig = configs[0]
            eglContext = EGL14.eglCreateContext(
                eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false
            pbuffer = EGL14.eglCreatePbufferSurface(
                eglDisplay, eglConfig,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
            )
            if (!EGL14.eglMakeCurrent(eglDisplay, pbuffer, pbuffer, eglContext)) return false

            program = buildProgram(VERTEX, FRAGMENT)
            if (program == 0) return false
            aPos = GLES20.glGetAttribLocation(program, "aPos")
            uA = GLES20.glGetUniformLocation(program, "uA")
            uOrig = GLES20.glGetUniformLocation(program, "uOrig")
            uRot = GLES20.glGetUniformLocation(program, "uRot")
            uTan = GLES20.glGetUniformLocation(program, "uTan")
            uCrop = GLES20.glGetUniformLocation(program, "uCrop")
            uPass = GLES20.glGetUniformLocation(program, "uPass")
            uTexMat = GLES20.glGetUniformLocation(program, "uTexMat")

            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            texId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
            }
            glReady = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "init GL gagal", e)
            return false
        }
    }

    private fun releaseGl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, pbuffer)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        pbuffer = EGL14.EGL_NO_SURFACE
        glReady = false
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        if (v == 0 || f == 0) return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "link gagal: " + GLES20.glGetProgramInfoLog(p))
            return 0
        }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "shader gagal: " + GLES20.glGetShaderInfoLog(s))
            return 0
        }
        return s
    }

    companion object {
        /** Pusat frame diambil sedikit setelah awal exposure (readout rolling shutter). */
        private const val CENTER_DELAY_NS = 8_000_000L

        private const val VERTEX = """
attribute vec2 aPos;
varying vec2 vQ;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vQ = aPos * 0.5 + 0.5;
}
"""

        // uTexMat: koordinat output -> koordinat tekstur (dari CameraX, sudah termasuk rotasi/crop/mirror).
        // uA = inv(matriks SurfaceTexture) * uTexMat: koordinat output -> koordinat gambar sensor (y ke atas).
        // uOrig: koordinat gambar sensor -> koordinat tekstur.
        private const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision highp float;
varying vec2 vQ;
uniform samplerExternalOES uTex;
uniform mat4 uTexMat;
uniform mat4 uA;
uniform mat4 uOrig;
uniform mat3 uRot;
uniform vec2 uTan;
uniform float uCrop;
uniform float uPass;
void main() {
    if (uPass > 0.5) {
        gl_FragColor = texture2D(uTex, (uTexMat * vec4(vQ, 0.0, 1.0)).xy);
        return;
    }
    vec2 q = (vQ - 0.5) / uCrop + 0.5;
    vec2 b = (uA * vec4(q, 0.0, 1.0)).xy;
    vec2 p = b * 2.0 - 1.0;
    vec3 d = vec3(p.x * uTan.x, -p.y * uTan.y, 1.0);
    d = uRot * d;
    float z = max(d.z, 0.05);
    vec2 pp = vec2(d.x / z / uTan.x, -d.y / z / uTan.y);
    vec2 s = (uOrig * vec4(pp * 0.5 + 0.5, 0.0, 1.0)).xy;
    gl_FragColor = texture2D(uTex, s);
}
"""
    }
}

/** Efek CameraX yang memasang [SoftEisProcessor] pada VideoCapture saja. */
class SoftEisEffect(processor: SoftEisProcessor) : CameraEffect(
    CameraEffect.VIDEO_CAPTURE,
    processor.executor,
    processor,
    Consumer<Throwable> { t -> Log.e(TAG, "efek EIS error", t) }
)
