package com.xcam.camera.livephoto

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.xcam.camera.camera.XCameraController
import com.xcam.camera.gallery.MediaStoreHelper
import com.xcam.camera.settings.Prefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Live Photo gaya iPhone: saat mode LIVE aktif, kamera merekam terus ke file sementara
 * (buffer bergulir). Saat shutter ditekan, foto diambil dan rekaman diteruskan sampai
 * durasi tercapai, lalu klip dipotong supaya mulai sekitar [PRE_MS] sebelum shutter.
 * Hasilnya satu file Motion Photo (JPEG + video MP4 tertanam).
 * Fotonya hasil jepret ImageCapture sungguhan (bukan frame yang diambil dari video).
 *
 * Batas yang jujur: pre-roll tidak selalu persis [PRE_MS]. Tepat setelah buffer diputar ulang
 * (tiap [ROLL_MS]) ada jeda singkat saat rekaman baru dimulai; kalau shutter ditekan di
 * saat itu, klip hanya berisi gerakan sesudah shutter.
 */
class LivePhotoEngine(
    private val activity: AppCompatActivity,
    private val controller: XCameraController,
    private val callback: Callback
) {

    interface Callback {
        /** Shutter diterima: UI boleh memberi efek. */
        fun onCaptureStarted()
        /** Foto dan video selesai disiapkan di latar belakang (atau gagal). */
        fun onLiveSaved(photoUri: Uri)
        fun onLiveFailed(message: String)
        /** Foto sudah tersimpan (belum digabung video); dipakai untuk thumbnail cepat. */
        fun onStillSaved(photoUri: Uri)
        fun onUnsupported()
    }

    private enum class State {
        OFF, ROLLING, ROTATING, CAPTURING, FINALIZING, STOPPING,
        // Mode "foto asli": rekaman dihentikan saat shutter, foto diambil tanpa rekaman, lalu rekaman dilanjutkan.
        REAL_STOP, REAL_PHOTO, REAL_POST, REAL_POST_STOP
    }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var state = State.OFF
    private var wantRolling = false
    private var withAudio = false
    private var currentFile: File? = null
    private var lastPosMs = 0L
    private var failedStarts = 0

    // Data satu pengambilan
    private var pendingCapture = false
    private var pendingTotalMs = 0L
    private var totalMs = 0L
    private var pivotMs = 0L
    private var savedPosMs = 0L
    private var photoUri: Uri? = null
    private var photoSaved = false
    private var endTargetMs = 0L
    private var discard = false

    // Data mode "foto asli"
    private var segA: File? = null
    private var segAEndMs = 0L
    private var postTargetMs = 0L

    private val captureTimeout = Runnable { failCapture("waktu habis") }
    private val retryStart = Runnable {
        if (state == State.OFF && wantRolling) startSegment()
    }

    init {
        // Bersihkan sisa file sementara dari sesi sebelumnya.
        activity.cacheDir.listFiles()?.forEach {
            if (it.name.startsWith("live_")) it.delete()
        }
    }

    val isBusy: Boolean get() = state == State.CAPTURING || state == State.FINALIZING ||
        state == State.REAL_STOP || state == State.REAL_PHOTO || state == State.REAL_POST || state == State.REAL_POST_STOP

    fun start(withAudio: Boolean) {
        this.withAudio = withAudio
        wantRolling = true
        failedStarts = 0
        if (state == State.OFF) startSegment()
    }

    /** Berhenti merekam buffer (keluar mode LIVE / app ke latar). Pengambilan yang berjalan dibiarkan selesai. */
    fun stop() {
        wantRolling = false
        handler.removeCallbacks(retryStart)
        if (state == State.ROLLING) {
            state = State.STOPPING
            controller.stopFileRecording()
        }
    }

    fun release() {
        stop()
        handler.removeCallbacksAndMessages(null)
        io.shutdown()
    }

    private val rotateRestart = Runnable {
        if (wantRolling && state == State.ROLLING) {
            state = State.ROTATING
            controller.stopFileRecording()
        }
    }

    /**
     * HP diputar: segmen yang sedang direkam masih membawa orientasi lama, jadi videonya bisa miring
     * dibanding foto yang diambil dengan orientasi baru. Buffer dimulai ulang (ditunda sebentar supaya
     * tidak terpancing goyangan di batas orientasi). Pengambilan yang sedang berjalan tidak disentuh.
     */
    fun onOrientationChanged() {
        handler.removeCallbacks(rotateRestart)
        handler.postDelayed(rotateRestart, 600L)
    }

    /** Kamera di-bind ulang (ganti lensa/kualitas): rekaman lama mati, mulai lagi jika perlu. */
    fun onCameraRebound() {
        if (wantRolling && state == State.OFF) {
            handler.removeCallbacks(retryStart)
            handler.postDelayed(retryStart, 400)
        }
    }

    // ---- Buffer bergulir

    private fun startSegment() {
        val file = File(activity.cacheDir, "live_roll_${System.nanoTime()}.mp4")
        val ok = controller.startFileRecording(file, withAudio, recordingCallback)
        if (!ok) {
            state = State.OFF
            failedStarts++
            if (controller.isCameraBound && !controller.videoAvailable) {
                wantRolling = false
                callback.onUnsupported()
            } else if (wantRolling && failedStarts < 5) {
                handler.removeCallbacks(retryStart)
                handler.postDelayed(retryStart, 500)
            }
            return
        }
        failedStarts = 0
        currentFile = file
        lastPosMs = 0L
        state = State.ROLLING
        if (pendingCapture) {
            pendingCapture = false
            beginCapture(pendingTotalMs)
        }
    }

    private val recordingCallback = object : XCameraController.RecordingCallback {
        override fun onProgress(durationMs: Long) {
            lastPosMs = durationMs
            when (state) {
                State.ROLLING -> if (durationMs >= ROLL_MS) {
                    state = State.ROTATING
                    controller.stopFileRecording()
                }
                State.CAPTURING -> if (photoSaved && durationMs >= endTargetMs) finishCapture()
                State.REAL_POST -> if (durationMs >= postTargetMs) {
                    state = State.REAL_POST_STOP
                    controller.stopFileRecording()
                }
                else -> {}
            }
        }

        override fun onFinished(uri: Uri) {
            val file = uri.path?.let { File(it) } ?: currentFile
            onSegmentEnded(file, true)
        }

        override fun onFailed(message: String) {
            onSegmentEnded(currentFile, false)
        }
    }

    private fun onSegmentEnded(file: File?, valid: Boolean) {
        when (state) {
            State.FINALIZING -> {
                val pivot = pivotMs
                val saved = savedPosMs
                val uri = photoUri
                state = State.OFF
                photoUri = null
                controller.lensLocked = false
                // Mulai buffer baru segera supaya shutter berikutnya tidak menunggu pemrosesan.
                if (wantRolling) startSegment()
                if (file != null && uri != null && valid) {
                    process(file, uri, pivot, saved)
                } else {
                    file?.delete()
                    callback.onLiveFailed("rekaman video tidak lengkap")
                }
            }
            State.CAPTURING -> {
                // Rekaman mati sebelum selesai (mis. kamera terganggu). Foto tetap ada sebagai foto biasa.
                handler.removeCallbacks(captureTimeout)
                file?.delete()
                state = State.OFF
                controller.lensLocked = false
                callback.onLiveFailed("rekaman terputus")
                if (wantRolling) handler.postDelayed(retryStart, 400)
            }
            State.REAL_STOP -> {
                // Buffer sebelum shutter sudah lengkap. Sekarang ambil foto asli (tanpa rekaman berjalan).
                segAEndMs = lastPosMs
                segA = if (valid) file else { file?.delete(); null }
                state = State.REAL_PHOTO
                takeRealPhoto()
            }
            State.REAL_POST, State.REAL_POST_STOP -> {
                handler.removeCallbacks(captureTimeout)
                val a = segA
                segA = null
                val aEnd = segAEndMs
                val uri = photoUri
                photoUri = null
                state = State.OFF
                controller.lensLocked = false
                if (wantRolling) startSegment()
                if (file != null && uri != null && valid) {
                    processReal(a, aEnd, file, uri)
                } else {
                    a?.delete()
                    file?.delete()
                    callback.onLiveFailed("rekaman sesudah foto tidak lengkap")
                }
            }
            State.ROTATING -> {
                file?.delete()
                state = State.OFF
                if (wantRolling) startSegment()
            }
            else -> {
                // ROLLING (mati sendiri), STOPPING, OFF
                file?.delete()
                val shouldRestart = (state == State.ROLLING || state == State.STOPPING) && wantRolling
                state = State.OFF
                if (shouldRestart) {
                    handler.removeCallbacks(retryStart)
                    handler.postDelayed(retryStart, 400)
                }
            }
        }
    }

    // ---- Pengambilan

    /** @param totalSeconds panjang klip total (sebelum + sesudah shutter). */
    fun capture(totalSeconds: Int): Boolean {
        val total = totalSeconds * 1000L
        return when (state) {
            State.ROLLING -> {
                beginCapture(total)
                true
            }
            State.ROTATING, State.OFF -> {
                if (!wantRolling) return false
                // Buffer sedang berputar: mulai pengambilan begitu rekaman baru berjalan.
                pendingCapture = true
                pendingTotalMs = total
                if (state == State.OFF) startSegment()
                true
            }
            else -> false
        }
    }

    private fun beginCapture(total: Long) {
        if (Prefs.of(activity).getBoolean(Prefs.LIVE_REAL_PHOTO, true)) {
            beginRealCapture(total)
            return
        }
        state = State.CAPTURING
        totalMs = total
        pivotMs = lastPosMs
        photoSaved = false
        photoUri = null
        discard = false
        controller.lensLocked = true
        callback.onCaptureStarted()

        val base = MediaStoreHelper.LIVE_PREFIX +
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis()) + "_MP"
        val ok = controller.takePhotoLiveFlash(
            onSaved = { uri ->
                if (state == State.CAPTURING) {
                    photoUri = uri
                    savedPosMs = lastPosMs
                    photoSaved = true
                    endTargetMs = maxOf(pivotMs + (totalMs - PRE_MS), savedPosMs + POST_MIN_MS)
                    callback.onStillSaved(uri)
                    if (lastPosMs >= endTargetMs) finishCapture()
                }
            },
            onError = { msg -> failCapture(msg) },
            baseName = base
        )
        if (!ok) {
            failCapture("kamera sedang sibuk")
            return
        }
        handler.removeCallbacks(captureTimeout)
        handler.postDelayed(captureTimeout, totalMs + 15_000L)
    }

    // ---- Mode "foto asli": rekaman berhenti sebentar supaya foto diambil lewat jalur foto biasa

    private fun beginRealCapture(total: Long) {
        state = State.REAL_STOP
        totalMs = total
        postTargetMs = maxOf(total - PRE_MS, POST_MIN_MS)
        photoSaved = false
        photoUri = null
        discard = false
        segA = null
        controller.lensLocked = true
        callback.onCaptureStarted()
        handler.removeCallbacks(captureTimeout)
        handler.postDelayed(captureTimeout, total + 20_000L)
        controller.stopFileRecording() // lanjut di onSegmentEnded(REAL_STOP)
    }

    private fun takeRealPhoto() {
        val base = MediaStoreHelper.LIVE_PREFIX +
            SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis()) + "_MP"
        val ok = controller.takePhotoLiveFlash(
            onSaved = { uri ->
                if (state == State.REAL_PHOTO) {
                    photoUri = uri
                    photoSaved = true
                    callback.onStillSaved(uri)
                    startPostRecording()
                }
            },
            onError = { msg -> failCapture(msg) },
            baseName = base
        )
        if (!ok) failCapture("kamera sedang sibuk")
    }

    private fun startPostRecording() {
        val f = File(activity.cacheDir, "live_post_${System.nanoTime()}.mp4")
        val ok = controller.startFileRecording(f, withAudio, recordingCallback)
        if (!ok) {
            failCapture("rekaman sesudah foto gagal dimulai")
            return
        }
        currentFile = f
        lastPosMs = 0L
        state = State.REAL_POST
    }

    private fun processReal(a: File?, aEndMs: Long, b: File, photo: Uri) {
        val job = Runnable {
            val joined = File(activity.cacheDir, "live_join_${System.nanoTime()}.mp4")
            var success = false
            try {
                val presentationUs: Long
                if (a != null) {
                    val startMs = maxOf(0L, aEndMs - PRE_MS)
                    presentationUs = VideoTrimmer.trimAndJoin(a, b, joined, startMs * 1000L)
                } else {
                    // Tidak ada rekaman sebelum shutter (buffer baru berputar): video hanya berisi bagian sesudah foto.
                    presentationUs = if (VideoTrimmer.trimStart(b, joined, 0L) >= 0) 0L else -1L
                }
                if (presentationUs >= 0 && joined.length() > 0) {
                    success = MotionPhotoWriter.combine(activity, photo, joined, presentationUs)
                }
            } catch (e: Exception) {
                success = false
            } finally {
                a?.delete()
                b.delete()
                joined.delete()
            }
            handler.post {
                if (success) callback.onLiveSaved(photo) else callback.onLiveFailed("gagal menggabungkan video")
            }
        }
        try {
            io.execute(job)
        } catch (e: RejectedExecutionException) {
            a?.delete()
            b.delete()
        }
    }

    private fun finishCapture() {
        if (state != State.CAPTURING) return
        handler.removeCallbacks(captureTimeout)
        state = State.FINALIZING
        controller.stopFileRecording()
    }

    private fun failCapture(message: String) {
        if (state == State.REAL_STOP || state == State.REAL_PHOTO || state == State.REAL_POST || state == State.REAL_POST_STOP) {
            handler.removeCallbacks(captureTimeout)
            callback.onLiveFailed(message)
            segA?.delete()
            segA = null
            val wasRecording = state == State.REAL_POST || state == State.REAL_POST_STOP || state == State.REAL_STOP
            state = State.STOPPING
            controller.lensLocked = false
            if (wasRecording) controller.stopFileRecording()
            handler.postDelayed({
                if (state == State.STOPPING) {
                    state = State.OFF
                    if (wantRolling) startSegment()
                }
            }, 2000)
            return
        }
        if (state != State.CAPTURING) return
        handler.removeCallbacks(captureTimeout)
        callback.onLiveFailed(message)
        // Hentikan rekaman; file dibuang di onSegmentEnded, lalu buffer dimulai lagi.
        state = State.STOPPING
        controller.lensLocked = false
        controller.stopFileRecording()
        handler.postDelayed({
            if (state == State.STOPPING) {
                state = State.OFF
                if (wantRolling) startSegment()
            }
        }, 2000)
    }

    // ---- Pemrosesan di latar belakang

    private fun process(file: File, photo: Uri, pivot: Long, saved: Long) {
        val job = Runnable {
            val trimmed = File(activity.cacheDir, "live_trim_${System.nanoTime()}.mp4")
            var success = false
            try {
                val startMs = maxOf(0L, pivot - PRE_MS)
                val baseUs = VideoTrimmer.trimStart(file, trimmed, startMs * 1000L)
                if (baseUs >= 0 && trimmed.length() > 0) {
                    // Perkiraan saat foto diambil: sedikit sebelum foto selesai disimpan.
                    val stillMs = maxOf(pivot, saved - STILL_LATENCY_MS)
                    val presentationUs = maxOf(0L, stillMs * 1000L - baseUs)
                    success = MotionPhotoWriter.combine(activity, photo, trimmed, presentationUs)
                }
            } catch (e: Exception) {
                success = false
            } finally {
                file.delete()
                trimmed.delete()
            }
            handler.post {
                if (success) callback.onLiveSaved(photo) else callback.onLiveFailed("gagal menggabungkan video")
            }
        }
        try {
            io.execute(job)
        } catch (e: RejectedExecutionException) {
            file.delete()
        }
    }

    companion object {
        /** Gerakan sebelum shutter yang ingin disimpan (seperti iPhone: 1,5 detik). */
        const val PRE_MS = 1500L
        /** Buffer diputar ulang tiap sekian milidetik. */
        private const val ROLL_MS = 5000L
        /** Minimal rekaman setelah foto selesai disimpan. */
        private const val POST_MIN_MS = 500L
        /** Perkiraan jeda antara foto diambil dan selesai disimpan. */
        private const val STILL_LATENCY_MS = 400L
    }
}
