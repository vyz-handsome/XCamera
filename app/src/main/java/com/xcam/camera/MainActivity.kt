package com.xcam.camera

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.ImageViewCompat
import com.xcam.camera.camera.XCameraController
import com.xcam.camera.camera.ZoomController
import com.xcam.camera.databinding.ActivityMainBinding
import com.xcam.camera.gallery.GalleryActivity
import com.xcam.camera.gallery.MediaStoreHelper
import com.xcam.camera.gallery.VideoPlayerActivity
import com.xcam.camera.livephoto.LivePhotoEngine
import com.xcam.camera.settings.Prefs
import com.xcam.camera.settings.SettingsActivity
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

enum class CameraMode { PHOTO, VIDEO, LIVE }

class MainActivity : AppCompatActivity(), XCameraController.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var controller: XCameraController
    private lateinit var zoomControls: ZoomController
    private var mode = CameraMode.PHOTO
    private var cameraStarted = false
    private var lastMediaUri: Uri? = null
    private var lastMediaIsVideo = false
    private var audioAsked = false
    private var lastSecondShown = -1L
    private var pendingLive = false
    private lateinit var liveEngine: LivePhotoEngine
    private var recBlink: ObjectAnimator? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private val requiredPermissions: Array<String> =
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasAllPermissions()) startCamera() else showPermissionHint()
        }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Toast.makeText(this, R.string.toast_audio_denied, Toast.LENGTH_SHORT).show()
            if (pendingLive) {
                if (::liveEngine.isInitialized) liveEngine.start(granted)
            } else {
                beginRecording(granted)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        mode = savedInstanceState?.getString(KEY_MODE)
            ?.let { runCatching { CameraMode.valueOf(it) }.getOrNull() }
            ?: CameraMode.PHOTO

        binding.previewContainer.clipToOutline = true
        binding.btnGallery.clipToOutline = true

        controller = XCameraController(this, binding.previewView, binding.previewFreeze)
            .also { it.listener = this }
        liveEngine = LivePhotoEngine(this, controller, liveCallback)
        zoomControls = ZoomController(
            this, controller, binding.zoomBar, binding.zoomSliderPanel, binding.zoomSlider
        )

        binding.modePhoto.setOnClickListener { setMode(CameraMode.PHOTO) }
        binding.modeVideo.setOnClickListener { setMode(CameraMode.VIDEO) }
        binding.modeLive.setOnClickListener { setMode(CameraMode.LIVE) }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnGallery.setOnClickListener { openGallery() }
        binding.shutterTouch.setOnClickListener { onShutter() }

        binding.btnFlash.setOnClickListener {
            if (mode == CameraMode.VIDEO) controller.toggleTorch() else controller.cycleFlash()
            updateFlashUi()
        }
        binding.btnQuality.setOnClickListener { controller.cycleVideoQuality() }
        binding.btnFps.setOnClickListener {
            if (controller.isRecording) return@setOnClickListener
            controller.cycleVideoFps()
            updateQualityUi()
        }
        binding.btnStab.setOnClickListener { toggleEisForCurrentMode() }
        // Tekan lama logo XCAM untuk melihat info lensa kamera (berguna kalau ultra-wide tidak terdeteksi).
        binding.tvBrand.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.cam_info_title)
                .setMessage(controller.debugInfo())
                .setPositiveButton("OK", null)
                .show()
            true
        }
        binding.btnSwitchCamera.setOnClickListener {
            if (controller.isRecording) return@setOnClickListener
            binding.btnSwitchCamera.animate().rotationBy(180f).setDuration(250).start()
            controller.switchCamera()
        }

        binding.previewHint.setOnClickListener { onHintClicked() }
        setupTapToFocus()
        applyMode()

        if (hasAllPermissions()) startCamera() else permissionLauncher.launch(requiredPermissions)
    }

    override fun onResume() {
        super.onResume()
        if (!cameraStarted && hasAllPermissions()) startCamera()
        applyUserSettings()
        refreshLastPhoto()
        if (mode == CameraMode.LIVE) ensureLiveBuffer()
    }

    override fun onPause() {
        super.onPause()
        // Buffer Live Photo ikut berhenti saat app ke latar (kamera dilepas Android).
        if (::liveEngine.isInitialized) liveEngine.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        recBlink?.cancel()
        liveEngine.release()
        zoomControls.release()
        controller.release()
        ioExecutor.shutdown()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_MODE, mode.name)
    }

    // ---- Izin

    private fun hasAllPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun showPermissionHint() {
        binding.previewHint.visibility = View.VISIBLE
    }

    private fun onHintClicked() {
        val canAsk = requiredPermissions.any {
            ActivityCompat.shouldShowRequestPermissionRationale(this, it)
        }
        if (canAsk) {
            permissionLauncher.launch(requiredPermissions)
        } else {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun startCamera() {
        if (cameraStarted) return
        cameraStarted = true
        binding.previewHint.visibility = View.GONE
        controller.start()
    }

    // ---- Listener kamera

    override fun onZoomRangeChanged() {
        zoomControls.onCameraReady()
    }

    override fun onCameraBound() {
        if (mode == CameraMode.LIVE) ensureLiveBuffer()
        zoomControls.onCameraReady()
        updateFlashUi()
        updateQualityUi()
        binding.btnSwitchCamera.visibility =
            if (controller.hasBothCameras) View.VISIBLE else View.INVISIBLE
    }

    /** Lensa ultra-wide/utama atau kualitas video berganti; zoom dan slider dibiarkan jalan. */
    override fun onLensChanged() {
        if (mode == CameraMode.LIVE) liveEngine.onCameraRebound()
        updateFlashUi()
        updateQualityUi()
    }

    override fun onCameraError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ---- Tap to focus

    private fun setupTapToFocus() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (zoomControls.recentlyScaled()) return true
                controller.focusAt(e.x, e.y)
                showFocusRing(e.x, e.y)
                return true
            }
        })
        binding.previewView.setOnTouchListener { v, event ->
            zoomControls.onTouchEvent(event)
            detector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun showFocusRing(x: Float, y: Float) {
        val ring = binding.focusRing
        ring.animate().cancel()
        ring.translationX = x - ring.width / 2f
        ring.translationY = y - ring.height / 2f
        ring.visibility = View.VISIBLE
        ring.alpha = 1f
        ring.scaleX = 1.4f
        ring.scaleY = 1.4f
        ring.animate().scaleX(1f).scaleY(1f).setDuration(180).withEndAction {
            ring.animate().alpha(0f).setStartDelay(700).setDuration(250).start()
        }.start()
    }

    // ---- Shutter

    private fun onShutter() {
        when (mode) {
            CameraMode.PHOTO -> takePhoto()
            CameraMode.VIDEO -> if (controller.isRecording) controller.stopRecording() else startVideo()
            CameraMode.LIVE -> captureLive()
        }
    }

    private fun takePhoto() {
        val started = controller.takePhoto(
            onSaved = { uri ->
                lastMediaUri = uri
                lastMediaIsVideo = false
                loadThumbnail(uri, false)
            },
            onError = {
                Toast.makeText(this, R.string.toast_photo_error, Toast.LENGTH_SHORT).show()
            }
        )
        if (started) animateShutter()
    }

    // ---- Video

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startVideo() {
        when {
            hasAudioPermission() -> beginRecording(true)
            !audioAsked -> {
                audioAsked = true
                pendingLive = false
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> beginRecording(false)
        }
    }

    private fun beginRecording(withAudio: Boolean) {
        val started = controller.startRecording(withAudio, object : XCameraController.RecordingCallback {
            private var softwareEisFallback = false

            override fun onProgress(durationMs: Long) {
                val sec = durationMs / 1000
                if (sec != lastSecondShown) {
                    lastSecondShown = sec
                    binding.recTimer.text = formatDuration(sec)
                }
            }

            override fun onStabilizationStarted() {
                if (isDestroyed) return
                setRecordingUi(false)
                Toast.makeText(this@MainActivity, "Video selesai; sedang distabilkan software…", Toast.LENGTH_SHORT).show()
            }

            override fun onStabilizationFallback() {
                softwareEisFallback = true
            }

            override fun onFinished(uri: Uri) {
                if (isDestroyed) return
                setRecordingUi(false)
                lastMediaUri = uri
                lastMediaIsVideo = true
                loadThumbnail(uri, true)
                Toast.makeText(
                    this@MainActivity,
                    if (softwareEisFallback) "Video tersimpan asli; stabilisasi software gagal" else getString(R.string.toast_video_saved),
                    if (softwareEisFallback) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                ).show()
            }

            override fun onFailed(message: String) {
                if (isDestroyed) return
                setRecordingUi(false)
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        })
        if (started) {
            setRecordingUi(true)
        } else {
            Toast.makeText(this, R.string.toast_video_error, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Live Photo (buffer bergulir, lihat livephoto/LivePhotoEngine)

    /** Mulai buffer bergulir saat mode LIVE aktif. Butuh mic untuk suara; tanpa mic tetap jalan tanpa suara. */
    private fun ensureLiveBuffer() {
        if (mode != CameraMode.LIVE || !::liveEngine.isInitialized) return
        when {
            hasAudioPermission() -> liveEngine.start(true)
            !audioAsked -> {
                audioAsked = true
                pendingLive = true
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> liveEngine.start(false)
        }
    }

    private fun captureLive() {
        if (!liveEngine.capture(LIVE_SECONDS)) {
            Toast.makeText(this, R.string.toast_live_busy, Toast.LENGTH_SHORT).show()
        }
    }

    private val liveCallback = object : LivePhotoEngine.Callback {
        private var softwareEisFallback = false

        override fun onCaptureStarted() {
            lockOrientation(true)
            animateShutter()
        }

        override fun onStillSaved(photoUri: Uri) {
            lastMediaUri = photoUri
            lastMediaIsVideo = false
            loadThumbnail(photoUri, false)
        }

        override fun onLiveSaved(photoUri: Uri) {
            if (isDestroyed) return
            lockOrientation(false)
            Toast.makeText(
                this@MainActivity,
                if (softwareEisFallback) "Live Photo tersimpan asli; stabilisasi software gagal" else getString(R.string.toast_live_saved),
                if (softwareEisFallback) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
            softwareEisFallback = false
        }

        override fun onStabilizationFallback() {
            softwareEisFallback = true
        }

        override fun onLiveFailed(message: String) {
            softwareEisFallback = false
            if (isDestroyed) return
            lockOrientation(false)
            Toast.makeText(
                this@MainActivity,
                getString(R.string.toast_live_photo_failed, message),
                Toast.LENGTH_LONG
            ).show()
        }

        override fun onUnsupported() {
            if (isDestroyed) return
            Toast.makeText(this@MainActivity, R.string.toast_live_unsupported, Toast.LENGTH_LONG).show()
        }
    }

    /** Terapkan preferensi dari Settings (grid, durasi Live, zoom, ukuran foto, kualitas video). */
    private fun applyUserSettings() {
        val prefs = Prefs.of(this)
        binding.gridOverlay.visibility =
            if (prefs.getBoolean(Prefs.SHOW_GRID, false)) View.VISIBLE else View.GONE
        updateLiveUi()
        if (::zoomControls.isInitialized) zoomControls.reloadPrefs()
        if (::controller.isInitialized) {
            controller.reloadSettings()
            updateQualityUi()
        }
    }

    private fun timerText(sec: Long): String = formatDuration(sec)

    private fun updateLiveUi() {
        binding.btnLive.text = "LIVE ${LIVE_SECONDS}s"
        val recording = ::controller.isInitialized && controller.isRecording
        binding.btnLive.visibility =
            if (mode == CameraMode.LIVE && !recording) View.VISIBLE else View.GONE
    }

    private fun shutterBg() = when (mode) {
        CameraMode.PHOTO -> R.drawable.bg_shutter_photo
        CameraMode.VIDEO -> R.drawable.bg_shutter_video
        CameraMode.LIVE -> R.drawable.bg_shutter_live
    }

    /**
     * Layar boleh diputar (potret/lanskap), tapi dikunci selama merekam video atau menangkap
     * Live Photo, karena memutar layar membuat ulang activity dan kamera dan akan memutus rekaman.
     */
    private fun lockOrientation(lock: Boolean) {
        requestedOrientation = if (lock) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun setRecordingUi(on: Boolean) {
        lockOrientation(on)
        lastSecondShown = -1
        binding.recTimer.text = timerText(0)
        binding.recIndicator.visibility = if (on) View.VISIBLE else View.GONE
        binding.root.keepScreenOn = on

        binding.shutterInner.setBackgroundResource(
            if (on) R.drawable.bg_shutter_stop else shutterBg()
        )
        val scale = if (on) 0.55f else 1f
        binding.shutterInner.animate().scaleX(scale).scaleY(scale).setDuration(160).start()

        val dim = if (on) 0.4f else 1f
        binding.modeBar.alpha = dim
        binding.btnSwitchCamera.alpha = dim
        listOf(
            binding.modePhoto, binding.modeVideo, binding.modeLive,
            binding.btnSwitchCamera, binding.btnSettings, binding.btnQuality, binding.btnFps, binding.btnStab,
            binding.btnLive
        ).forEach { it.isEnabled = !on }

        updateLiveUi()
        recBlink?.cancel()
        binding.recDot.alpha = 1f
        if (on) {
            recBlink = ObjectAnimator.ofFloat(binding.recDot, View.ALPHA, 1f, 0.25f).apply {
                duration = 700
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun formatDuration(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun animateShutter() {
        binding.shutterInner.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
            binding.shutterInner.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }.start()
        binding.flashOverlay.animate().cancel()
        binding.flashOverlay.alpha = 0.7f
        binding.flashOverlay.animate().alpha(0f).setDuration(180).start()
    }

    // ---- Flash UI

    private fun updateFlashUi() {
        binding.btnFlash.visibility = if (controller.hasFlash) View.VISIBLE else View.INVISIBLE
        val videoMode = mode == CameraMode.VIDEO
        val icon = when {
            videoMode -> if (controller.torchOn) R.drawable.ic_flash else R.drawable.ic_flash_off
            controller.flashMode == ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
            controller.flashMode == ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash
            else -> R.drawable.ic_flash_off
        }
        binding.btnFlash.setImageResource(icon)
        val active = if (videoMode) controller.torchOn else controller.flashMode != ImageCapture.FLASH_MODE_OFF
        val tint = if (active) R.color.xcam_cyan else R.color.xcam_text_dim
        ImageViewCompat.setImageTintList(
            binding.btnFlash,
            ColorStateList.valueOf(ContextCompat.getColor(this, tint))
        )
    }

    /** EIS dipakai oleh video biasa dan klip video di dalam Live Photo. */
    private fun toggleEisForCurrentMode() {
        if (!::controller.isInitialized) return
        if (mode == CameraMode.LIVE) {
            if (liveEngine.isBusy) {
                Toast.makeText(this, "Tunggu Live Photo selesai dulu", Toast.LENGTH_SHORT).show()
                return
            }
            val queued = liveEngine.stopForReconfiguration {
                val applied = controller.toggleStabilization()
                updateQualityUi()
                if (!applied) {
                    Toast.makeText(
                        this,
                        if (controller.stabilizationOn) "EIS software tidak dapat diaktifkan sekarang" else "EIS software tidak dapat diubah sekarang",
                        Toast.LENGTH_LONG
                    ).show()
                }
                ensureLiveBuffer()
            }
            if (!queued) {
                Toast.makeText(this, "Buffer Live Photo belum siap diubah", Toast.LENGTH_SHORT).show()
            }
        } else {
            val applied = controller.toggleStabilization()
            updateQualityUi()
            if (!applied) {
                Toast.makeText(
                    this,
                    if (controller.stabilizationOn) "EIS software tidak dapat diaktifkan sekarang" else "EIS software tidak dapat diubah sekarang",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateQualityUi() {
        val q = controller.effectiveQuality
        if (mode == CameraMode.VIDEO && q != null) {
            binding.btnQuality.text = controller.qualityLabel(q)
            binding.btnQuality.visibility = View.VISIBLE
        } else {
            binding.btnQuality.visibility = View.GONE
        }
        val fpsOk = mode == CameraMode.VIDEO && q != null && controller.supportedFps.isNotEmpty()
        binding.btnFps.visibility = if (fpsOk) View.VISIBLE else View.GONE
        if (fpsOk) binding.btnFps.text = controller.fpsLabel(controller.effectiveFps)
        val showStab = (mode == CameraMode.VIDEO || mode == CameraMode.LIVE) &&
            controller.stabilizationSupported
        binding.btnStab.visibility = if (showStab) View.VISIBLE else View.GONE
        binding.btnStab.text = if (controller.stabilizationActive) "EIS SW ON" else "EIS SW OFF"
        binding.btnStab.contentDescription = if (controller.stabilizationActive) {
            "Stabilisasi video software aktif. Ketuk untuk mematikan"
        } else {
            "Stabilisasi video software mati. Ketuk untuk mengaktifkan"
        }
        binding.btnStab.setTextColor(
            ContextCompat.getColor(
                this,
                if (controller.stabilizationActive) R.color.xcam_cyan else R.color.xcam_text_dim
            )
        )
    }

    // ---- Thumbnail / galeri

    private fun thumbSizePx() = (56 * resources.displayMetrics.density * 1.5f).toInt()

    private fun refreshLastPhoto() {
        val size = thumbSizePx()
        try {
            ioExecutor.execute {
                val media = MediaStoreHelper.latestMedia(this)
                val bmp = media?.let { MediaStoreHelper.loadThumbnail(this, it.uri, size, it.isVideo) }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    lastMediaUri = media?.uri
                    lastMediaIsVideo = media?.isVideo == true
                    if (bmp != null) setThumb(bmp) else resetThumb()
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun loadThumbnail(uri: Uri, isVideo: Boolean) {
        val size = thumbSizePx()
        try {
            ioExecutor.execute {
                val bmp = MediaStoreHelper.loadThumbnail(this, uri, size, isVideo)
                runOnUiThread { if (!isDestroyed && bmp != null) setThumb(bmp) }
            }
        } catch (_: Exception) {
        }
    }

    private fun setThumb(bmp: Bitmap) {
        binding.btnGallery.scaleType = ImageView.ScaleType.CENTER_CROP
        ImageViewCompat.setImageTintList(binding.btnGallery, null)
        binding.btnGallery.setImageBitmap(bmp)
    }

    private fun resetThumb() {
        binding.btnGallery.scaleType = ImageView.ScaleType.CENTER
        binding.btnGallery.setImageResource(R.drawable.ic_gallery)
        ImageViewCompat.setImageTintList(
            binding.btnGallery,
            ColorStateList.valueOf(ContextCompat.getColor(this, R.color.xcam_text))
        )
    }

    private fun openGallery() {
        startActivity(Intent(this, GalleryActivity::class.java))
    }

    // ---- Mode

    private fun setMode(newMode: CameraMode) {
        if (newMode == mode || controller.isRecording || liveEngine.isBusy) return
        if (mode == CameraMode.VIDEO) controller.setTorch(false)
        if (mode == CameraMode.LIVE) liveEngine.stop()
        mode = newMode
        applyMode()
        if (mode == CameraMode.LIVE) ensureLiveBuffer()
    }

    private fun applyMode() {
        mapOf(
            CameraMode.PHOTO to binding.modePhoto,
            CameraMode.VIDEO to binding.modeVideo,
            CameraMode.LIVE to binding.modeLive
        ).forEach { (m, tv) -> styleTab(tv, m == mode) }

        binding.shutterInner.setBackgroundResource(shutterBg())
        updateLiveUi()
        if (::controller.isInitialized) {
            controller.setFpsSuspended(mode != CameraMode.VIDEO)
            updateFlashUi()
            updateQualityUi()
        }
    }

    private fun styleTab(tv: TextView, selected: Boolean) {
        if (selected) {
            tv.setBackgroundResource(R.drawable.bg_mode_selected)
            tv.setTextColor(ContextCompat.getColor(this, R.color.xcam_cyan))
        } else {
            tv.background = null
            tv.setTextColor(ContextCompat.getColor(this, R.color.xcam_text_dim))
        }
    }

    companion object {
        private const val KEY_MODE = "camera_mode"
        /** Durasi Live Photo dikunci 3 detik (1,5 dtk sebelum + 1,5 dtk sesudah shutter). */
        private const val LIVE_SECONDS = 3
    }
}
