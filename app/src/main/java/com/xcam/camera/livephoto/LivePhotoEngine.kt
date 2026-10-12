package com.xcam.camera.livephoto

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.xcam.camera.camera.XCameraController
import com.xcam.camera.gallery.MediaStoreHelper
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

    private enum class State { OFF, ROLLING, ROTATING, CAPTURING, FINALIZING, STOPPING }

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val afterBufferStopped = mutableListOf<() -> Unit>()

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

    val isBusy: Boolean get() = state == State.CAPTURING || state == State.FINALIZING

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

    /**
     * Hentikan segmen Live Photo yang sedang berjalan sebelum mengganti konfigurasi kamera.
     * Callback berjalan di main thread setelah file buffer ditutup dan controller bebas di-bind ulang.
     * Mengembalikan false jika Live Photo sedang menangkap/memfinalisasi foto.
     */
    fun stopForReconfiguration(afterStopped: () -> Unit): Boolean {
        if (isBusy) return false
        wantRolling = false
        handler.removeCallbacks(retryStart)
        afterBufferStopped.add(afterStopped)
        when (state) {
            State.OFF -> dispatchAfterBufferStopped()
            State.ROLLING -> {
                state = State.STOPPING
                controller.stopFileRecording()
            }
            // ROTATING/STOPPING: segmen sudah diminta berhenti, tunggu callback finalnya.
            State.ROTATING, State.STOPPING -> Unit
            else -> {
                afterBufferStopped.remove(afterStopped)
                return false
            }
        }
        return true
    }

    private fun dispatchAfterBufferStopped() {
        if (state != State.OFF || afterBufferStopped.isEmpty()) return
        val callbacks = afterBufferStopped.toList()
        afterBufferStopped.clear()
        callbacks.forEach { callback -> callback() }
    }

    fun release() {
        stop()
        handler.removeCallbacksAndMessages(null)
        io.shutdown()
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
                dispatchAfterBufferStopped()
            }
            State.CAPTURING -> {
                // Rekaman mati sebelum selesai (mis. kamera terganggu). Foto tetap ada sebagai foto biasa.
                handler.removeCallbacks(captureTimeout)
                file?.delete()
                state = State.OFF
                controller.lensLocked = false
                callback.onLiveFailed("rekaman terputus")
                if (wantRolling) handler.postDelayed(retryStart, 400)
                dispatchAfterBufferStopped()
            }
            State.ROTATING -> {
                file?.delete()
                state = State.OFF
                if (wantRolling) startSegment()
                dispatchAfterBufferStopped()
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
                dispatchAfterBufferStopped()
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

    private fun finishCapture() {
        if (state != State.CAPTURING) return
        handler.removeCallbacks(captureTimeout)
        state = State.FINALIZING
        controller.stopFileRecording()
    }

    private fun failCapture(message: String) {
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
