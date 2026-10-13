package com.xcam.camera.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Range
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.xcam.camera.settings.Prefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

private class LensCandidate(val id: String, val eqFocal: Float, val megapixel: Float)

/**
 * Pengelola kamera internal XCAM (CameraX).
 * Tahap 2: preview, ganti kamera, flash, tap-to-focus, foto.
 * Tahap 3: zoom lewat CameraControl, batas 10x, lensa ultra-wide.
 * Tahap 4: perekaman video (Recorder/VideoCapture), kualitas video, torch.
 *
 * Zoom yang dilihat UI adalah zoom "virtual" relatif terhadap lensa utama.
 * Kalau HP punya ultra-wide sebagai kamera terpisah, zoom di bawah 1x
 * memakai lensa ultra-wide (butuh bind ulang sebentar = jeda hardware).
 */
class XCameraController(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
    private val freezeView: ImageView
) {

    interface Listener {
        fun onCameraBound()
        fun onCameraError(message: String)
        fun onZoomRangeChanged() {}
        /** Lensa (utama/ultra-wide) atau kualitas video berubah tanpa ganti kamera depan/belakang. */
        fun onLensChanged() {}
    }

    interface RecordingCallback {
        fun onProgress(durationMs: Long)
        fun onFinished(uri: Uri)
        fun onFailed(message: String)
    }

    enum class Lens { MAIN, ULTRAWIDE }

    var listener: Listener? = null

    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set
    var flashMode: Int = ImageCapture.FLASH_MODE_OFF
        private set
    var torchOn: Boolean = false
        private set
    var hasBothCameras: Boolean = false
        private set

    /** Batas zoom virtual (bisa < 1 jika ada ultra-wide), maksimum dibatasi 10x. */
    var minZoom: Float = 1f
        private set
    var maxZoom: Float = 1f
        private set

    /** Nilai zoom virtual yang sedang diminta; satu-satunya sumber kebenaran untuk UI zoom. */
    var zoomRatio: Float = 1f
        private set

    var isRecording: Boolean = false
        private set

    /** Dikunci Live Photo selama menangkap: pindah lensa (bind ulang) akan memutus rekaman. */
    var lensLocked: Boolean = false

    var videoQuality: Quality = Quality.FHD
        private set

    /** Target FPS video pilihan user. 0 = otomatis. */
    var videoFps: Int = 0
        private set
    /** FPS yang benar-benar ada di kamera aktif (tanpa "otomatis"). Kosong = tidak ada yang bisa dipilih. */
    var supportedFps: List<Int> = emptyList()
        private set
    /** FPS yang dipakai saat bind terakhir (0 = otomatis). */
    var effectiveFps: Int = 0
        private set
    /** FPS hanya berlaku di mode Video. Di mode Foto dan Live Photo kamera memakai FPS otomatis. */
    private var fpsSuspended = true
    var supportedQualities: List<Quality> = emptyList()
        private set
    var effectiveQuality: Quality? = null
        private set
    val videoAvailable: Boolean get() = videoCapture != null
    val isCameraBound: Boolean get() = camera != null

    /** EIS (stabilisasi digital video): didukung kamera aktif, pilihan user, dan benar-benar aktif. */
    var stabilizationSupported: Boolean = false
        private set
    var stabilizationOn: Boolean = false
        private set
    var stabilizationActive: Boolean = false
        private set
    /** EIS buatan XCAM (gyroscope + GPU) ikut terpasang pada video. */
    var softStabActive: Boolean = false
        private set
    /** EIS hardware diminta pada video. */
    var hwStabRequested: Boolean = false
        private set
    /** Hasil kamera melaporkan EIS hardware benar-benar menyala. */
    @Volatile var hwStabReported: Boolean = false
        private set

    /** Teks indikator EIS di layar. */
    val stabilizationLabel: String
        get() = when {
            !stabilizationActive -> "EIS OFF"
            softStabActive && !(hwStabRequested && hwStabReported) -> "EIS ON SW"
            else -> "EIS ON"
        }

    var activeLens: Lens = Lens.MAIN
        private set

    val hasFlash: Boolean
        get() = camera?.cameraInfo?.hasFlashUnit() == true || usesBorrowedFlash

    /**
     * Banyak HP tidak mendaftarkan flash di kamera ultra-wide (0,5x), padahal lampunya satu dan sama
     * dengan kamera utama. Di lensa itu flash "dipinjam" dari kamera utama lewat CameraManager.setTorchMode.
     */
    private val usesBorrowedFlash: Boolean
        get() = camera != null && camera?.cameraInfo?.hasFlashUnit() != true &&
            lensFacing == CameraSelector.LENS_FACING_BACK && findFlashCamId() != null

    private var flashCamId: String? = null
    private var flashCamSearched = false

    private fun findFlashCamId(): String? {
        if (flashCamSearched) return flashCamId
        flashCamSearched = true
        try {
            val cm = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                    ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) {
                    flashCamId = id
                    break
                }
            }
        } catch (e: Exception) {
        }
        return flashCamId
    }

    /** Nyalakan/matikan lampu: lewat CameraX, atau lewat kamera utama jika lensa aktif tidak punya flash sendiri. */
    private fun torchSwitch(on: Boolean) {
        try {
            if (usesBorrowedFlash) {
                val cm = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                findFlashCamId()?.let { cm.setTorchMode(it, on) }
            } else {
                camera?.cameraControl?.enableTorch(on)
            }
        } catch (e: Exception) {
        }
    }

    private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var isCapturing = false

    // Lensa belakang
    private var mainId: String? = null
    private var uwId: String? = null
    private var uwRatio = 1f
    private var uwCandidateFound = false
    private var hasUw = false
    private var mainMin = 1f
    private var mainMax = 1f
    private var uwMax = 1f
    private var lensReport = "Belum ada data kamera."
    private var lastLensSwitch = 0L
    private var boundPhotoSize = "auto"
    private var boundEisMode = "auto"
    /** Nilai CONTROL_VIDEO_STABILIZATION_MODE yang diminta: 1 = ON, 2 = PREVIEW_STABILIZATION (Android 13+). */
    private var hwModeValue = 1

    // EIS software: satu pemroses GL dipakai ulang di setiap bind.
    private var softProcessor: SoftEisProcessor? = null
    private var softEffect: SoftEisEffect? = null
    private fun eisPreview(): Boolean = prefs.getBoolean(Prefs.EIS_PREVIEW, false)
    private fun eisFlip(): Int = (prefs.getString(Prefs.EIS_FLIP, "0")?.toIntOrNull() ?: 0).coerceIn(0, 7)
    private fun eisCfgKey(): String = eisMode() + "/" + eisPreview() + "/" + eisFlip()
    private fun softProc(): SoftEisProcessor {
        val p = softProcessor ?: SoftEisProcessor(activity).also { softProcessor = it }
        return p
    }
    private fun eisMode(): String = prefs.getString(Prefs.EIS_MODE, "auto") ?: "auto"
    private fun gyroPresent(): Boolean = try {
        (activity.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager)
            .getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) != null
    } catch (e: Exception) {
        false
    }

    private var zoomLiveData: LiveData<ZoomState>? = null
    private val zoomObserver = Observer<ZoomState> { state ->
        if (refreshRange(state)) listener?.onZoomRangeChanged()
    }

    private var freezeActive = false
    /** Zoom virtual yang sedang tampil di layar (terakhir diterapkan ke kamera). */
    private var shownZoom = 1f
    /** Zoom virtual pada saat frame dibekukan; zoom lanjutan diteruskan sebagai skala pada frame beku. */
    private var freezeBaseZoom = 1f
    private val freezeTimeout = Runnable { releaseFreeze() }
    private val streamObserver = Observer<PreviewView.StreamState> { st ->
        if (st == PreviewView.StreamState.STREAMING && freezeActive) releaseFreeze()
    }
    private val retryZoomRunnable = Runnable { setZoomRatio(zoomRatio) }

    private var currentRotation = Surface.ROTATION_0
    private val mainExecutor = ContextCompat.getMainExecutor(activity)

    private val orientationListener = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val rotation = when (orientation) {
                in 45..134 -> Surface.ROTATION_270
                in 135..224 -> Surface.ROTATION_180
                in 225..314 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
            if (rotation != currentRotation) {
                currentRotation = rotation
                imageCapture?.targetRotation = rotation
                videoCapture?.targetRotation = rotation
            }
        }
    }

    init {
        videoQuality = qualityFromName(prefs.getString(KEY_VIDEO_QUALITY, "FHD"))
        videoFps = fpsFromPref(prefs.getString(Prefs.VIDEO_FPS, "auto"))
        stabilizationOn = prefs.getBoolean(KEY_VIDEO_EIS, false)
        lensFacing = if (prefs.getString(Prefs.DEFAULT_CAMERA, "back") == "front") {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        flashMode = when (prefs.getString(Prefs.DEFAULT_FLASH, "off")) {
            "auto" -> ImageCapture.FLASH_MODE_AUTO
            "on" -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    fun start() {
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        previewView.previewStreamState.observe(activity, streamObserver)
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            try {
                provider = future.get()
                provider?.let { detectBackLenses(it) }
                previewView.post { bind() }
            } catch (e: Exception) {
                listener?.onCameraError("Kamera tidak bisa dibuka")
            }
        }, mainExecutor)
    }

    fun release() {
        orientationListener.disable()
        previewView.removeCallbacks(retryZoomRunnable)
        freezeView.removeCallbacks(freezeTimeout)
        recording?.stop()
        softProcessor?.shutdown()
        softProcessor = null
        softEffect = null
    }

    fun switchCamera() {
        if (!hasBothCameras || isRecording) return
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        activeLens = Lens.MAIN
        setTorch(false)
        bind()
    }

    /** Siklus flash foto: OFF -> AUTO -> ON -> OFF. */
    fun cycleFlash() {
        if (!hasFlash) return
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
    }

    /** Lampu senter terus menyala, dipakai di mode video. */
    fun setTorch(on: Boolean) {
        val target = on && hasFlash
        torchOn = target
        torchSwitch(target)
    }

    fun toggleTorch() = setTorch(!torchOn)

    // ---- Zoom

    /** Set zoom virtual. Lensa ultra-wide dipilih otomatis jika perlu. */
    fun setZoomRatio(ratio: Float) {
        var v = ratio.coerceIn(minZoom, maxZoom)
        if (hasUw && (isRecording || lensLocked)) {
            // Ganti lensa berarti bind ulang dan akan memutus rekaman, jadi dikunci selama merekam.
            v = if (activeLens == Lens.MAIN) v.coerceAtLeast(1f) else v.coerceAtMost(1f)
        }
        zoomRatio = v
        updateFreezeScale()

        if (hasUw && lensFacing == CameraSelector.LENS_FACING_BACK && !isRecording && !lensLocked) {
            val want = when (activeLens) {
                Lens.MAIN -> if (v < UW_ENTER) Lens.ULTRAWIDE else Lens.MAIN
                Lens.ULTRAWIDE -> if (v >= UW_EXIT) Lens.MAIN else Lens.ULTRAWIDE
            }
            if (want != activeLens) {
                requestLens(want)
                return
            }
        }
        applyNativeZoom()
    }

    private fun applyNativeZoom() {
        val native = when (activeLens) {
            Lens.ULTRAWIDE -> (zoomRatio / uwRatio).coerceIn(1f, uwMax.coerceAtLeast(1f))
            Lens.MAIN -> zoomRatio.coerceIn(mainMin, mainMax.coerceAtLeast(mainMin))
        }
        camera?.cameraControl?.setZoomRatio(native)
        softProcessor?.setNativeZoom(native)
        shownZoom = zoomRatio
    }

    private fun requestLens(want: Lens) {
        val wait = LENS_COOLDOWN_MS - (SystemClock.uptimeMillis() - lastLensSwitch)
        if (wait > 0) {
            // Terlalu cepat setelah ganti lensa sebelumnya: coba lagi sebentar lagi.
            previewView.removeCallbacks(retryZoomRunnable)
            previewView.postDelayed(retryZoomRunnable, wait)
            applyNativeZoom()
            return
        }
        lastLensSwitch = SystemClock.uptimeMillis()
        activeLens = want
        bind(keepZoom = true)
    }

    /** @return true jika batas zoom virtual berubah. */
    private fun refreshRange(state: ZoomState?): Boolean {
        if (state == null) return false
        if (activeLens == Lens.MAIN) {
            mainMin = state.minZoomRatio
            mainMax = state.maxZoomRatio
        } else {
            uwMax = state.maxZoomRatio
        }
        hasUw = uwCandidateFound && lensFacing == CameraSelector.LENS_FACING_BACK && mainMin >= 0.95f
        val newMin = if (hasUw) uwRatio else mainMin
        val newMax = minOf(MAX_ZOOM, mainMax).coerceAtLeast(newMin)
        val changed = newMin != minZoom || newMax != maxZoom
        minZoom = newMin
        maxZoom = newMax
        zoomRatio = zoomRatio.coerceIn(minZoom, maxZoom)
        return changed
    }

    fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
        cam.cameraControl.startFocusAndMetering(action)
    }

    // ---- Foto

    /** @return false jika kamera belum siap atau masih memproses foto sebelumnya. */
    fun takePhoto(onSaved: (Uri) -> Unit, onError: (String) -> Unit, baseName: String? = null): Boolean {
        // Lensa tanpa flash sendiri (ultra-wide): flash foto bawaan CameraX tidak jalan, pakai urutan torch manual.
        if (usesBorrowedFlash) return takePhotoLiveFlash(onSaved, onError, baseName)
        return shoot(onSaved, onError, baseName)
    }

    private fun shoot(onSaved: (Uri) -> Unit, onError: (String) -> Unit, baseName: String? = null): Boolean {
        val capture = imageCapture ?: return false
        if (isCapturing) return false
        isCapturing = true

        val name = baseName ?: ("XCAM_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
            .format(System.currentTimeMillis()))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/XCamera")
            }
        }
        // Kamera depan: hasil foto dicerminkan supaya sama dengan yang terlihat di preview.
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT &&
                prefs.getBoolean(Prefs.MIRROR_FRONT, true)
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            activity.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ).setMetadata(metadata).build()

        capture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                isCapturing = false
                val uri = output.savedUri
                if (uri != null) onSaved(uri) else onError("Foto tersimpan tanpa alamat")
            }

            override fun onError(exception: ImageCaptureException) {
                isCapturing = false
                onError(exception.message ?: "Gagal mengambil foto")
            }
        })
        return true
    }

    // ---- Foto dengan flash saat merekam (mode LIVE)

    private val flashHandler = Handler(Looper.getMainLooper())
    private var flashSequenceActive = false

    private fun setTorchRaw(on: Boolean) {
        torchSwitch(on)
    }

    /**
     * Kunci/lepas exposure otomatis (AE). Dipakai selama urutan flash: exposure dikunci pada nilai
     * saat pra-flash menyala, supaya foto di flash utama tidak kelewat terang (AE yang sempat
     * "melebar" ke gelap waktu torch mati lalu kena flash penuh = foto putih).
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun setAeLock(lock: Boolean) {
        try {
            val c2 = Camera2CameraControl.from(camera?.cameraControl ?: return)
            if (lock) {
                c2.addCaptureRequestOptions(
                    CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                        .build()
                )
            } else {
                c2.clearCaptureRequestOptions()
            }
        } catch (e: Exception) {
        }
    }

    private var savedEvIndex: Int? = null

    /** Turunkan exposure selama urutan flash (torch kontinu jauh lebih terang dari flash foto biasa). */
    private fun applyFlashEv() {
        try {
            val cam = camera ?: return
            val state = cam.cameraInfo.exposureState
            if (!state.isExposureCompensationSupported) return
            val step = state.exposureCompensationStep.toFloat()
            if (step <= 0f) return
            val range = state.exposureCompensationRange
            savedEvIndex = state.exposureCompensationIndex
            val idx = Math.round(FLASH_EV / step).coerceIn(range.lower, range.upper)
            cam.cameraControl.setExposureCompensationIndex(idx)
        } catch (e: Exception) {
        }
    }

    private fun restoreEv() {
        val idx = savedEvIndex ?: return
        savedEvIndex = null
        try {
            camera?.cameraControl?.setExposureCompensationIndex(idx)
        } catch (e: Exception) {
        }
    }

    /** Perkiraan kasar: preview rata-rata gelap. Dipakai hanya untuk flash AUTO. */
    private fun isSceneDark(): Boolean {
        val bmp = try { previewView.bitmap } catch (e: Exception) { null } ?: return true
        return try {
            val small = Bitmap.createScaledBitmap(bmp, 16, 16, true)
            val px = IntArray(256)
            small.getPixels(px, 0, 16, 0, 0, 16, 16)
            var sum = 0L
            for (c in px) {
                sum += (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            }
            small.recycle()
            bmp.recycle()
            sum / 256 < DARK_LUMA
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Foto untuk Live Photo yang sedang merekam. Flash foto bawaan CameraX tidak bisa dipakai
     * sambil merekam video, jadi flash dibuat manual dengan torch seperti foto biasa:
     * kedip pra-flash yang agak lama, jeda, lalu flash utama yang singkat tepat saat foto diambil. Tanpa flash, sama dengan [takePhoto].
     */
    fun takePhotoLiveFlash(onSaved: (Uri) -> Unit, onError: (String) -> Unit, baseName: String? = null): Boolean {
        val capture = imageCapture ?: return false
        if (isCapturing || flashSequenceActive) return false
        val needFlash = hasFlash && when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> true
            ImageCapture.FLASH_MODE_AUTO -> isSceneDark()
            else -> false
        }
        if (!needFlash) return shoot(onSaved, onError, baseName)

        flashSequenceActive = true
        val savedMode = capture.flashMode
        capture.flashMode = ImageCapture.FLASH_MODE_OFF // flash diatur manual di sini

        fun end() {
            flashHandler.removeCallbacksAndMessages(null)
            setTorchRaw(false)
            setAeLock(false)
            restoreEv()
            if (flashSequenceActive) {
                flashSequenceActive = false
                try { capture.flashMode = savedMode } catch (e: Exception) {}
            }
        }

        applyFlashEv()
        setTorchRaw(true) // kedip 1: pra-flash, lama
        // Kunci exposure sesaat sebelum torch mati: nilai ini sudah menyesuaikan terangnya flash.
        flashHandler.postDelayed({ setAeLock(true) }, PRE_FLASH_MS - AE_LOCK_LEAD_MS)
        flashHandler.postDelayed({ setTorchRaw(false) }, PRE_FLASH_MS)
        flashHandler.postDelayed({ setTorchRaw(true) }, PRE_FLASH_MS + FLASH_GAP_MS) // kedip 2: flash utama, singkat
        flashHandler.postDelayed({
            val ok = shoot(
                onSaved = { uri -> end(); onSaved(uri) },
                onError = { msg -> end(); onError(msg) },
                baseName = baseName
            )
            if (!ok) {
                end()
                onError("kamera sedang sibuk")
            } else {
                // Flash utama singkat: mati sendiri kalau foto belum selesai disimpan.
                flashHandler.postDelayed({ setTorchRaw(false) }, FLASH_HOLD_MS)
            }
        }, PRE_FLASH_MS + FLASH_GAP_MS + FLASH_SETTLE_MS)
        // Pengaman: torch tidak boleh menyala terus kalau foto tidak pernah selesai.
        flashHandler.postDelayed({ end() }, FLASH_FAILSAFE_MS)
        return true
    }

    // ---- Video

    fun qualityLabel(q: Quality): String = when (q) {
        Quality.UHD -> "4K"
        Quality.FHD -> "1080p"
        Quality.HD -> "720p"
        Quality.SD -> "480p"
        else -> "AUTO"
    }

    /** Ganti ke kualitas berikutnya yang didukung (tinggi ke rendah, lalu kembali ke tertinggi). */
    fun cycleVideoQuality() {
        if (isRecording) return
        val list = supportedQualities
        if (list.size <= 1) return
        val cur = list.indexOf(effectiveQuality).coerceAtLeast(0)
        videoQuality = list[(cur + 1) % list.size]
        prefs.edit().putString(KEY_VIDEO_QUALITY, qualityName(videoQuality)).apply()
        bind(keepZoom = true)
    }

    /**
     * Nyalakan/matikan EIS pada Video dan video yang menjadi bagian Live Photo.
     * Pemanggil harus menghentikan buffer Live Photo lebih dulu agar bind ulang
     * tidak memutus file yang sedang direkam.
     * @return true bila status yang diminta berhasil diterapkan.
     */
    fun toggleStabilization(): Boolean {
        // recording mencakup rekaman video biasa maupun segmen buffer Live Photo.
        if (isRecording || recording != null || !stabilizationSupported) return false
        // Gunakan status yang benar-benar aktif agar ketukan berikutnya bisa mencoba ulang
        // bila perangkat melaporkan dukungan EIS, tetapi kombinasi stream sebelumnya gagal.
        stabilizationOn = !stabilizationActive
        prefs.edit().putBoolean(KEY_VIDEO_EIS, stabilizationOn).apply()
        bind(keepZoom = true)
        // Kamera kadang mendukung EIS secara umum tetapi tidak pada kombinasi stream aktif.
        return stabilizationActive == stabilizationOn
    }

    /** @return false jika video tidak tersedia atau gagal dimulai. */
    @SuppressLint("MissingPermission")
    fun startRecording(withAudio: Boolean, callback: RecordingCallback, baseName: String? = null): Boolean {
        val vc = videoCapture ?: return false
        if (isRecording) return false

        val name = baseName ?: ("XCAM_VID_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
            .format(System.currentTimeMillis()))
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/XCamera")
            }
        }
        val options = MediaStoreOutputOptions.Builder(
            activity.contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        return try {
            var pending = vc.output.prepareRecording(activity, options)
            if (withAudio) pending = pending.withAudioEnabled()
            isRecording = true
            recording = pending.start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Status ->
                        callback.onProgress(event.recordingStats.recordedDurationNanos / 1_000_000L)
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        recording = null
                        val uri = event.outputResults.outputUri
                        val valid = uri != Uri.EMPTY &&
                            event.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA
                        if (valid) {
                            callback.onFinished(uri)
                        } else {
                            callback.onFailed("Rekaman terlalu singkat atau gagal disimpan")
                        }
                        // Setelah rekaman selesai, lensa boleh ganti lagi sesuai zoom.
                        setZoomRatio(zoomRatio)
                    }
                    else -> {}
                }
            }
            true
        } catch (e: Exception) {
            isRecording = false
            recording = null
            false
        }
    }

    fun stopRecording() {
        recording?.stop()
    }

    /**
     * Rekam ke file sementara (dipakai buffer Live Photo). Tidak mengubah [isRecording]
     * sehingga UI rekam video biasa tidak ikut aktif.
     */
    @SuppressLint("MissingPermission")
    fun startFileRecording(file: File, withAudio: Boolean, callback: RecordingCallback): Boolean {
        val vc = videoCapture ?: return false
        if (recording != null) return false
        return try {
            var pending = vc.output.prepareRecording(activity, FileOutputOptions.Builder(file).build())
            if (withAudio) pending = pending.withAudioEnabled()
            recording = pending.start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Status ->
                        callback.onProgress(event.recordingStats.recordedDurationNanos / 1_000_000L)
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        val valid = event.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA &&
                            file.exists() && file.length() > 0
                        if (valid) callback.onFinished(Uri.fromFile(file)) else callback.onFailed("rekaman kosong")
                    }
                    else -> {}
                }
            }
            true
        } catch (e: Exception) {
            recording = null
            false
        }
    }

    fun stopFileRecording() {
        recording?.stop()
    }

    // ---- Info untuk debugging lensa

    fun debugInfo(): String {
        val cam = camera
        val state = cam?.cameraInfo?.zoomState?.value
        val sb = StringBuilder(lensReport)
        sb.append("\n\nLensa aktif: ").append(if (activeLens == Lens.MAIN) "utama" else "ultra-wide")
        sb.append("\nZoom di UI: ").append(String.format(Locale.US, "%.2fx - %.2fx", minZoom, maxZoom))
        if (state != null) {
            sb.append(String.format(Locale.US, "\nZoom asli lensa aktif: %.2fx - %.2fx", state.minZoomRatio, state.maxZoomRatio))
        }
        sb.append("\nVideo: ").append(if (videoAvailable) "tersedia" else "tidak tersedia bareng preview")
        val still = try { imageCapture?.resolutionInfo?.resolution } catch (e: Exception) { null }
        sb.append("\nUkuran foto saat ini: ").append(if (still != null) "${still.width}x${still.height}" else "belum diketahui")
        sb.append("\nEIS: ").append(
            when {
                !stabilizationSupported -> "tidak didukung kamera ini"
                stabilizationActive -> "aktif (" + (if (hwStabRequested) "hardware diminta" else "") +
                    (if (hwStabRequested && softStabActive) " + " else "") +
                    (if (softStabActive) "software" else "") + ")"
                else -> "didukung, sedang mati"
            }
        )
        sb.append("\nMode EIS: ").append(eisMode())
        try {
            val modes = Camera2CameraInfoModes(cam)
            sb.append("\nMode stabilisasi yang ditawarkan kamera: ").append(modes)
        } catch (e: Exception) {
        }
        if (hwStabRequested) sb.append("\nMode hardware yang diminta: ").append(if (hwModeValue == 2) "PREVIEW_STABILIZATION" else "ON")
        if (hwStabRequested) sb.append("\nEIS hardware dilaporkan kamera: ").append(if (hwStabReported) "ya" else "tidak")
        softProcessor?.let {
            sb.append("\nEIS software: ").append(it.status)
            sb.append("\nFrame diproses/dikoreksi/timestamp tak cocok: ").append(it.framesTotal).append(" / ")
                .append(it.framesCorrected).append(" / ").append(it.framesMismatch)
            sb.append("\nSampel gyro: ").append(it.gyroSamples)
            if (it.diag.isNotEmpty()) sb.append("\n").append(it.diag)
        }
        sb.append(cameraDiagnostics())
        return sb.toString()
    }

    /**
     * Info untuk menjawab: bisakah dua kamera belakang menyala bersamaan (transisi mulus ala flagship)?
     * Dibaca langsung dari Camera2, jadi tidak bergantung pada CameraX.
     */
    private fun cameraDiagnostics(): String {
        val sb = StringBuilder()
        try {
            val cm = activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            sb.append("\n\nKamera yang bisa jalan bersamaan: ")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sets = cm.concurrentCameraIds
                if (sets.isEmpty()) {
                    sb.append("tidak ada")
                } else {
                    sets.forEach { sb.append("\n- ").append(it.joinToString(" + ")) }
                }
            } else {
                sb.append("butuh Android 11+")
            }
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val physical = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    c.physicalCameraIds.joinToString().ifEmpty { "-" }
                } else {
                    "-"
                }
                val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                    else -> "?"
                }
                sb.append("\nBelakang id $id: level $level, kamera fisik di dalamnya: $physical")
            }
        } catch (e: Exception) {
            sb.append("\n(info kamera bersamaan tidak terbaca)")
        }
        return sb.toString()
    }

    // ---- Deteksi lensa

    @OptIn(ExperimentalCamera2Interop::class)
    private fun detectBackLenses(p: ProcessCameraProvider) {
        val sb = StringBuilder()
        mainId = null
        uwId = null
        uwRatio = 1f
        uwCandidateFound = false

        val backs = try {
            CameraSelector.DEFAULT_BACK_CAMERA.filter(p.availableCameraInfos)
        } catch (e: Exception) {
            emptyList()
        }
        sb.append("Kamera belakang yang dibuka Android untuk app: ").append(backs.size)

        val cands = backs.mapNotNull { info ->
            try {
                val c2 = Camera2CameraInfo.from(info)
                val focals = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val pix = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
                if (focals == null || focals.isEmpty() || size == null || size.width <= 0f) {
                    null
                } else {
                    val diag = hypot(size.width, size.height)
                    val eq = focals[0] * 43.27f / diag
                    val mp = if (pix != null) pix.width * pix.height / 1_000_000f else 0f
                    LensCandidate(c2.cameraId, eq, mp)
                }
            } catch (e: Exception) {
                null
            }
        }
        cands.forEach {
            sb.append(String.format(Locale.US, "\n- id %s: %.0fmm (setara 35mm), %.1fMP", it.id, it.eqFocal, it.megapixel))
        }

        val main = cands.firstOrNull()
        if (main != null) {
            mainId = main.id
            val uw = cands.drop(1)
                .filter { it.eqFocal <= main.eqFocal * 0.8f && it.megapixel >= 4f }
                .minByOrNull { it.eqFocal }
            if (uw != null) {
                val ratio = uw.eqFocal / main.eqFocal
                if (ratio in 0.3f..0.85f) {
                    uwId = uw.id
                    uwRatio = ratio
                    uwCandidateFound = true
                }
            }
        }
        sb.append(
            if (uwCandidateFound) {
                String.format(Locale.US, "\nUltra-wide terpisah: id %s (sekitar %.2fx)", uwId, uwRatio)
            } else {
                "\nUltra-wide terpisah: tidak ditemukan"
            }
        )
        lensReport = sb.toString()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun selectorFor(id: String): CameraSelector =
        CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
            .build()

    // ---- Freeze frame saat bind ulang

    private fun freezeFrame() {
        if (camera == null) return
        val bmp = try {
            previewView.bitmap
        } catch (e: Exception) {
            null
        }
        if (bmp == null) return
        freezeView.animate().cancel()
        freezeView.setImageBitmap(bmp)
        freezeView.alpha = 1f
        freezeView.scaleX = 1f
        freezeView.scaleY = 1f
        freezeView.visibility = View.VISIBLE
        freezeBaseZoom = shownZoom
        freezeActive = true
        updateFreezeScale()
        freezeView.removeCallbacks(freezeTimeout)
        freezeView.postDelayed(freezeTimeout, 1500)
    }

    /**
     * Selama lensa berganti (kamera dibuka ulang, ada jeda hardware), gestur zoom user tetap terasa
     * mengalir: frame beku diperbesar mengikuti zoom virtual (bidang pandang sama dengan lensa baru),
     * lalu dipudarkan ke gambar langsung. Saat zoom keluar (< zoom beku) frame ditahan, tidak diperkecil
     * supaya tidak muncul tepi kosong.
     */
    private fun updateFreezeScale() {
        if (!freezeActive) return
        val scale = (zoomRatio / freezeBaseZoom.coerceAtLeast(0.1f)).coerceIn(1f, 4f)
        freezeView.scaleX = scale
        freezeView.scaleY = scale
    }

    private fun releaseFreeze() {
        if (!freezeActive) return
        freezeActive = false
        freezeView.removeCallbacks(freezeTimeout)
        freezeView.animate().alpha(0f).setDuration(180).withEndAction {
            freezeView.scaleX = 1f
            freezeView.scaleY = 1f
            freezeView.visibility = View.INVISIBLE
            freezeView.setImageDrawable(null)
        }.start()
    }

    // ---- Bind

    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildVideo(q: Quality, rotation: Int, stabilize: Boolean, fps: Int): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q))
            )
            .build()
        val builder = VideoCapture.Builder(recorder)
        if (fps > 0) builder.setTargetFrameRate(Range(fps, fps))
        if (stabilize) {
            // EIS dipasang pada VideoCapture yang dipakai oleh Video biasa dan buffer Live Photo.
            // Ini meminta stabilisasi digital native dari kamera, bukan crop/post-processing manual.
            val ext = Camera2Interop.Extender(builder)
            ext.setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                hwModeValue
            )
            // Cek apakah kamera benar-benar menyalakan EIS hardware (banyak HP menolaknya untuk app pihak ketiga).
            ext.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    hwStabReported = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE) == hwModeValue
                }
            })
        }
        return builder.build().also { it.targetRotation = rotation }
    }

    private fun bind(keepZoom: Boolean = false) {
        val p = provider ?: return
        try {
            val hasBack = p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            val hasFront = p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            hasBothCameras = hasBack && hasFront

            if (lensFacing == CameraSelector.LENS_FACING_BACK && !hasBack && hasFront) {
                lensFacing = CameraSelector.LENS_FACING_FRONT
            } else if (lensFacing == CameraSelector.LENS_FACING_FRONT && !hasFront && hasBack) {
                lensFacing = CameraSelector.LENS_FACING_BACK
            }
            if (!hasBack && !hasFront) {
                listener?.onCameraError("Perangkat tidak punya kamera")
                return
            }
            if (!keepZoom) activeLens = Lens.MAIN

            val uwTarget = uwId
            val selector = if (
                lensFacing == CameraSelector.LENS_FACING_BACK &&
                activeLens == Lens.ULTRAWIDE && uwTarget != null
            ) {
                selectorFor(uwTarget)
            } else {
                CameraSelector.Builder().requireLensFacing(lensFacing).build()
            }
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            currentRotation = rotation

            freezeFrame()

            // Coba preview + foto + video sekaligus, lalu turunkan bila kombinasi tidak didukung.
            val lowerOk = videoQuality == Quality.UHD || videoQuality == Quality.FHD
            val mode = eisMode()
            hwModeValue = if (mode != "soft") hwEisModeFor(p, selector) else 0
            val hwOk = hwModeValue != 0
            val softOk = mode != "hw" && gyroPresent()
            val base = buildList<EisAttempt> {
                if (stabilizationOn && (hwOk || softOk)) {
                    // Hardware (jika ada) + software sebagai cadangan; software hanya bekerja bila
                    // kamera tidak melaporkan EIS hardware menyala.
                    add(EisAttempt(videoQuality, hwOk, softOk))
                    if (lowerOk) add(EisAttempt(Quality.HD, hwOk, softOk))
                    if (hwOk && softOk) {
                        add(EisAttempt(videoQuality, false, true))
                        if (lowerOk) add(EisAttempt(Quality.HD, false, true))
                    }
                }
                add(EisAttempt(videoQuality, false, false))
                if (lowerOk) add(EisAttempt(Quality.HD, false, false))
                add(EisAttempt(null, false, false))
            }
            // FPS dicoba dulu di kualitas yang sama; kalau kombinasi gagal, FPS dilepas sebelum kualitas diturunkan.
            val fpsTarget = if (videoFps > 0 && !fpsSuspended) videoFps else 0
            val attempts = buildList<EisAttempt> {
                for (b in base) {
                    if (fpsTarget > 0 && b.q != null) add(EisAttempt(b.q, b.hw, b.soft, fpsTarget))
                    add(EisAttempt(b.q, b.hw, b.soft, 0))
                }
            }
            var bound: Camera? = null
            var boundCapture: ImageCapture? = null
            var boundVideo: VideoCapture<Recorder>? = null
            var boundHw = false
            var boundSoft = false
            var boundFps = 0
            for (at in attempts) {
                val q = at.q
                val fps = at.fps
                val preview = Preview.Builder()
                    .setTargetRotation(rotation)
                    .build()
                    .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val captureBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setFlashMode(flashMode)
                    .setTargetRotation(rotation)
                photoSizeFromPrefs()?.let { size ->
                    captureBuilder.setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    size,
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                }
                val capture = captureBuilder.build()
                hwStabReported = false
                val video = q?.let { buildVideo(it, rotation, at.hw, fps) }
                val effect: SoftEisEffect? = if (at.soft && video != null) {
                    val wantPreview = eisPreview()
                    val cached = softEffect
                    if (cached != null && cached.withPreview == wantPreview) cached
                    else SoftEisEffect(softProc(), wantPreview).also { softEffect = it }
                } else {
                    null
                }
                val group = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(capture)
                    .apply {
                        video?.let { addUseCase(it) }
                        effect?.let { addEffect(it) }
                        previewView.viewPort?.let { setViewPort(it) }
                    }
                    .build()
                try {
                    p.unbindAll()
                    bound = p.bindToLifecycle(activity, selector, group)
                    boundCapture = capture
                    boundVideo = video
                    boundHw = at.hw && video != null
                    boundSoft = effect != null
                    boundFps = if (video != null) fps else 0
                    break
                } catch (e: Exception) {
                    bound = null
                }
            }

            val cam = bound
            if (cam == null) {
                listener?.onCameraError("Kamera tidak bisa dibuka")
                return
            }
            camera = cam
            boundPhotoSize = prefs.getString(Prefs.PHOTO_SIZE, "auto") ?: "auto"
            imageCapture = boundCapture
            videoCapture = boundVideo

            // Kualitas video yang didukung kamera ini.
            supportedQualities = if (boundVideo != null) {
                try {
                    QualitySelector.getSupportedQualities(cam.cameraInfo).filter { it in QUALITY_ORDER }
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            effectiveQuality = resolveQuality(supportedQualities)
            hwStabRequested = boundHw
            softStabActive = boundSoft
            stabilizationActive = boundHw || boundSoft
            boundEisMode = eisCfgKey()
            if (boundSoft) configureSoftEis(cam)
            supportedFps = if (boundVideo != null) queryFpsOptions(cam) else emptyList()
            effectiveFps = if (boundFps in supportedFps) boundFps else 0
            stabilizationSupported = boundVideo != null &&
                ((mode != "soft" && checkEisSupport(cam)) || (mode != "hw" && softOk))

            zoomLiveData?.removeObserver(zoomObserver)
            val zoomState = cam.cameraInfo.zoomState
            zoomLiveData = zoomState
            refreshRange(zoomState.value)
            if (!keepZoom) zoomRatio = 1f.coerceIn(minZoom, maxZoom)
            applyNativeZoom()
            zoomState.observe(activity, zoomObserver)

            if (!hasFlash) {
                flashMode = ImageCapture.FLASH_MODE_OFF
                boundCapture?.flashMode = flashMode
                torchOn = false
            } else if (torchOn) {
                torchSwitch(true)
            }

            if (keepZoom) listener?.onLensChanged() else listener?.onCameraBound()
        } catch (e: Exception) {
            listener?.onCameraError("Kamera tidak bisa dibuka")
        }
    }

    /** FPS tetap (24/30/60) yang tercantum di kemampuan kamera aktif. 30 selalu dianggap ada jika dalam jangkauan. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun queryFpsOptions(cam: Camera): List<Int> = try {
        val ranges = Camera2CameraInfo.from(cam.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        if (ranges == null) {
            emptyList()
        } else {
            FPS_CHOICES.filter { f ->
                ranges.any { it.lower == f && it.upper == f } ||
                    (f == 30 && ranges.any { it.lower <= 30 && it.upper >= 30 })
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun fpsFromPref(v: String?): Int = v?.toIntOrNull()?.takeIf { it in FPS_CHOICES } ?: 0

    fun fpsLabel(f: Int): String = if (f == 0) "AUTO" else "${f}fps"

    /** Ganti ke FPS berikutnya yang didukung: otomatis, lalu 24/30/60 sesuai kamera. */
    fun cycleVideoFps() {
        if (isRecording) return
        val options = listOf(0) + supportedFps
        if (options.size <= 1) return
        val cur = options.indexOf(effectiveFps).coerceAtLeast(0)
        videoFps = options[(cur + 1) % options.size]
        prefs.edit().putString(Prefs.VIDEO_FPS, if (videoFps == 0) "auto" else videoFps.toString()).apply()
        bind(keepZoom = true)
    }

    /**
     * Mode Foto dan Live Photo memakai FPS otomatis (Live Photo tetap ringan dan konsisten);
     * pilihan FPS hanya aktif di mode Video. Bind ulang hanya terjadi jika FPS dipilih (bukan otomatis).
     */
    fun setFpsSuspended(suspended: Boolean) {
        if (fpsSuspended == suspended) return
        fpsSuspended = suspended
        if (videoFps != 0 && camera != null && !isRecording) bind(keepZoom = true)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun Camera2CameraInfoModes(cam: Camera?): String {
        val m = cam?.let {
            Camera2CameraInfo.from(it.cameraInfo)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        } ?: return "tidak terbaca"
        return m.joinToString { when (it) { 0 -> "OFF"; 1 -> "ON"; 2 -> "PREVIEW"; else -> it.toString() } }
    }

    private class EisAttempt(val q: Quality?, val hw: Boolean, val soft: Boolean, val fps: Int = 0)

    /**
     * Mode EIS hardware yang bisa diminta app pihak ketiga pada kamera pilihan [selector]:
     * 2 (PREVIEW_STABILIZATION, Android 13+) diutamakan karena itu yang biasanya diizinkan HP,
     * lalu 1 (ON). 0 = tidak ada.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun hwEisModeFor(p: ProcessCameraProvider, selector: CameraSelector): Int = try {
        val info = selector.filter(p.availableCameraInfos).firstOrNull()
        val modes = info?.let {
            Camera2CameraInfo.from(it)
                .getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        }
        when {
            modes == null -> 0
            Build.VERSION.SDK_INT >= 33 && modes.contains(2) -> 2
            modes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) -> 1
            else -> 0
        }
    } catch (e: Exception) {
        0
    }

    /** Isi data kamera aktif (orientasi sensor, FOV, basis waktu) ke pemroses EIS software. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun configureSoftEis(cam: Camera) {
        val proc = softProcessor ?: return
        try {
            val c2 = Camera2CameraInfo.from(cam.cameraInfo)
            val orient = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
            val focals = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val size = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val tsSource = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            val focal = focals?.firstOrNull() ?: 0f
            // Tanpa data fokus/sensor, pakai perkiraan FOV horizontal ~ 70 derajat.
            val tanHalf = if (focal > 0f && size != null && size.width > 0f) {
                (size.width / (2f * focal)).toDouble()
            } else {
                0.70
            }
            proc.setCamera(
                SoftEisCamera(
                    sensorOrientation = orient,
                    facingBack = facing != CameraCharacteristics.LENS_FACING_FRONT,
                    tanHalfX = tanHalf,
                    timestampRealtime = tsSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                )
            )
        } catch (e: Exception) {
            proc.setCamera(null)
        }
        proc.setStrength(prefs.getString(Prefs.EIS_STRENGTH, "normal") == "strong")
        proc.setFlip(eisFlip())
        proc.bypassProvider = { hwStabRequested && hwStabReported }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun checkEisSupport(cam: Camera): Boolean = try {
        val modes = Camera2CameraInfo.from(cam.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        modes != null && (modes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) ||
            (Build.VERSION.SDK_INT >= 33 && modes.contains(2)))
    } catch (e: Exception) {
        false
    }

    /** Ukuran foto pilihan user ("WxH"), null = otomatis. */
    private fun photoSizeFromPrefs(): android.util.Size? {
        val raw = prefs.getString(Prefs.PHOTO_SIZE, "auto") ?: return null
        val parts = raw.split("x")
        if (parts.size != 2) return null
        val w = parts[0].toIntOrNull() ?: return null
        val h = parts[1].toIntOrNull() ?: return null
        return android.util.Size(w, h)
    }

    /** Dipanggil saat kembali dari Settings: bind ulang hanya jika ukuran foto atau kualitas video berubah. */
    fun reloadSettings() {
        if (isRecording || camera == null) return
        val now = prefs.getString(Prefs.PHOTO_SIZE, "auto") ?: "auto"
        val q = qualityFromName(prefs.getString(KEY_VIDEO_QUALITY, "FHD"))
        val qualityChanged = q != videoQuality
        if (qualityChanged) videoQuality = q
        val fps = fpsFromPref(prefs.getString(Prefs.VIDEO_FPS, "auto"))
        val fpsChanged = fps != videoFps
        if (fpsChanged) videoFps = fps
        softProcessor?.setStrength(prefs.getString(Prefs.EIS_STRENGTH, "normal") == "strong")
        val eisChanged = eisCfgKey() != boundEisMode && stabilizationOn && recording == null
        if (qualityChanged || now != boundPhotoSize || (fpsChanged && !fpsSuspended) || eisChanged) bind(keepZoom = true)
    }

    private fun resolveQuality(supported: List<Quality>): Quality? {
        if (supported.isEmpty()) return null
        if (videoQuality in supported) return videoQuality
        val want = QUALITY_ORDER.indexOf(videoQuality)
        return supported.firstOrNull { QUALITY_ORDER.indexOf(it) >= want } ?: supported.last()
    }

    private fun qualityName(q: Quality): String = when (q) {
        Quality.UHD -> "UHD"
        Quality.FHD -> "FHD"
        Quality.HD -> "HD"
        Quality.SD -> "SD"
        else -> "FHD"
    }

    private fun qualityFromName(name: String?): Quality = when (name) {
        "UHD" -> Quality.UHD
        "HD" -> Quality.HD
        "SD" -> Quality.SD
        else -> Quality.FHD
    }

    companion object {
        const val PREFS = "xcam_prefs"
        private const val KEY_VIDEO_QUALITY = "video_quality"
        private val FPS_CHOICES = listOf(24, 30, 60)
        private const val KEY_VIDEO_EIS = "video_eis"
        private const val MAX_ZOOM = 10f
        /** Lama kedip pra-flash (kedip pertama, lama). */
        private const val PRE_FLASH_MS = 450L
        /** Jeda gelap antara pra-flash dan flash utama. */
        private const val FLASH_GAP_MS = 100L
        /** Waktu flash utama menyala sebelum foto dijepret (supaya exposure menyesuaikan). */
        private const val FLASH_SETTLE_MS = 60L
        /** Flash utama dimatikan paling lama sekian ms setelah foto diminta (singkat). */
        private const val FLASH_HOLD_MS = 250L
        /** Kompensasi exposure (EV) selama flash. Makin negatif = foto flash makin gelap. */
        private const val FLASH_EV = -1.5f
        /** AE dikunci sekian ms sebelum pra-flash mati. */
        private const val AE_LOCK_LEAD_MS = 60L
        private const val FLASH_FAILSAFE_MS = 4000L
        /** Rata-rata kecerahan preview (0-255) di bawah ini dianggap gelap untuk flash AUTO. */
        private const val DARK_LUMA = 70
        private const val UW_ENTER = 0.98f
        private const val UW_EXIT = 1.0f
        private const val LENS_COOLDOWN_MS = 400L
        private val QUALITY_ORDER = listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD)
    }
}
