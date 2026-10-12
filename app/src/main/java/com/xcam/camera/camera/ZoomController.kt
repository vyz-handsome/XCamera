package com.xcam.camera.camera

import android.animation.ValueAnimator
import android.content.Context
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import com.xcam.camera.R
import com.xcam.camera.settings.Prefs
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Kontrol zoom XCAM: pinch, slider, dan preset, semuanya sinkron lewat
 * satu nilai (XCameraController.zoomRatio). Zoom memakai CameraControl,
 * jadi kamera tidak di-bind ulang dan preview tidak berubah ukuran.
 *
 * - Preset: dianimasikan di skala logaritmik (ValueAnimator).
 * - Slider: posisi slider jadi "target", zoom mengejar target tiap frame
 *   (smoothing eksponensial) sehingga tidak loncat.
 * - Pinch: mengikuti jari langsung.
 */
class ZoomController(
    private val context: Context,
    private val camera: XCameraController,
    private val presetRow: LinearLayout,
    private val sliderPanel: View,
    private val slider: SeekBar
) {

    private val density = context.resources.displayMetrics.density
    private val prefs = Prefs.of(context)
    private var smoothTau = SMOOTH_TAU
    private var sliderOnPinch = true
    private var presets: List<Float> = emptyList()
    private val chips = mutableListOf<TextView>()
    private var animator: ValueAnimator? = null
    private var userDraggingSlider = false
    private var sliderVisible = false
    private var lastScaleEnd = 0L
    private val hideRunnable = Runnable { hideSlider() }

    // Smoothing slider
    private var smoothing = false
    private var smoothTargetLn = 0f
    private var lastFrameNanos = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!smoothing) return
            val dt = if (lastFrameNanos == 0L) {
                1f / 60f
            } else {
                ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
            }
            lastFrameNanos = frameTimeNanos

            val cur = ln(camera.zoomRatio)
            val diff = smoothTargetLn - cur
            if (abs(diff) < SMOOTH_EPSILON) {
                finishSmoothing(exp(smoothTargetLn))
                return
            }
            val step = 1f - exp(-dt / smoothTau)
            applyRatio(exp(cur + diff * step), updateSlider = false)

            // Zoom tidak bergerak (mis. dikunci saat merekam): berhenti supaya tidak berputar terus.
            if (abs(ln(camera.zoomRatio) - cur) < 1e-6f) {
                finishSmoothing(camera.zoomRatio)
                return
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val canZoom: Boolean get() = camera.maxZoom > camera.minZoom + 0.01f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                cancelAnimation()
                return canZoom
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                applyRatio(camera.zoomRatio * detector.scaleFactor, updateSlider = true)
                if (sliderOnPinch) showSlider()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                lastScaleEnd = SystemClock.uptimeMillis()
                scheduleHide()
            }
        }
    )

    init {
        reloadPrefs()
        slider.max = SLIDER_STEPS
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                cancelPresetAnimation()
                smoothTo(progressToRatio(progress))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                userDraggingSlider = true
                cancelAnimation()
                sliderPanel.removeCallbacks(hideRunnable)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                userDraggingSlider = false
                scheduleHide()
            }
        })
    }

    // ---- Dipanggil dari Activity

    fun onTouchEvent(event: MotionEvent) {
        scaleDetector.onTouchEvent(event)
    }

    /** True jika baru saja ada pinch, supaya ketukan susulan tidak dianggap tap-to-focus. */
    fun recentlyScaled(): Boolean =
        scaleDetector.isInProgress || SystemClock.uptimeMillis() - lastScaleEnd < 250

    /** Baca preferensi zoom (kehalusan slider, slider otomatis saat pinch). */
    fun reloadPrefs() {
        smoothTau = when (prefs.getString(Prefs.ZOOM_SMOOTH, "normal")) {
            "fast" -> 0.03f
            "soft" -> 0.11f
            else -> SMOOTH_TAU
        }
        sliderOnPinch = prefs.getBoolean(Prefs.ZOOM_SLIDER_ON_PINCH, true)
    }

    /** Panggil setiap kamera selesai di-bind atau batas zoom berubah. */
    fun onCameraReady() {
        cancelAnimation()
        sliderPanel.removeCallbacks(hideRunnable)
        sliderVisible = false
        sliderPanel.animate().cancel()
        sliderPanel.alpha = 0f
        sliderPanel.visibility = View.INVISIBLE

        presets = buildPresets(camera.minZoom, camera.maxZoom)
        rebuildChips()
        presetRow.visibility = if (canZoom && presets.size > 1) View.VISIBLE else View.GONE
        slider.isEnabled = canZoom
        syncUi(updateSlider = true)
    }

    fun release() {
        cancelAnimation()
        sliderPanel.removeCallbacks(hideRunnable)
    }

    // ---- Logika zoom

    private fun applyRatio(ratio: Float, updateSlider: Boolean) {
        camera.setZoomRatio(ratio)
        syncUi(updateSlider && !userDraggingSlider)
    }

    private fun animateTo(rawTarget: Float) {
        cancelAnimation()
        val target = rawTarget.coerceIn(camera.minZoom, camera.maxZoom)
        val start = camera.zoomRatio
        val from = ln(start)
        val to = ln(target)
        if (abs(to - from) < 0.01f) {
            applyRatio(target, updateSlider = true)
            return
        }
        var cancelled = false
        animator = ValueAnimator.ofFloat(from, to).apply {
            // Waktu dan kurva dicocokkan ke video referensi: perpindahan 2x sampai 8x selesai dalam
            // sekitar 0,4-0,6 detik, mulai cepat lalu melambat di ujung.
            duration = (300 + 60 * abs(to - from) / ln(2f)).toLong().coerceIn(300L, 480L)
            interpolator = PathInterpolator(0.35f, 0.1f, 0.55f, 1f)
            addUpdateListener { applyRatio(exp(it.animatedValue as Float), updateSlider = true) }
            doOnCancel { cancelled = true }
            // Akhiri tepat di nilai target agar tidak ada selisih pembulatan.
            doOnEnd { if (!cancelled) applyRatio(target, updateSlider = true) }
            start()
        }
    }

    /** Slider: zoom mengejar target dengan halus (satu animasi aktif, tanpa tabrakan). */
    private fun smoothTo(ratio: Float) {
        smoothTargetLn = ln(ratio.coerceIn(camera.minZoom, camera.maxZoom))
        if (!smoothing) {
            smoothing = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private fun finishSmoothing(finalRatio: Float) {
        stopSmoothing()
        applyRatio(finalRatio, updateSlider = false)
        // Sinkronkan slider ke nilai akhir (mis. kalau zoom dibatasi), tanpa mengganggu jari user.
        if (!userDraggingSlider) syncUi(updateSlider = true)
    }

    private fun stopSmoothing() {
        smoothing = false
        lastFrameNanos = 0L
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun cancelPresetAnimation() {
        animator?.cancel()
        animator = null
    }

    private fun cancelAnimation() {
        cancelPresetAnimation()
        stopSmoothing()
    }

    // ---- Preset

    private fun buildPresets(min: Float, max: Float): List<Float> {
        if (max <= min + 0.01f) return emptyList()
        val list = mutableListOf<Float>()
        if (min < 0.95f) list.add(min)
        list.add(1f.coerceIn(min, max))
        for (candidate in listOf(2f, 5f, 10f)) {
            if (candidate <= max + 0.01f) list.add(candidate)
        }
        return list.fold(mutableListOf()) { acc, v ->
            if (acc.isEmpty() || abs(acc.last() - v) > 0.05f) acc.add(v)
            acc
        }
    }

    private fun rebuildChips() {
        presetRow.removeAllViews()
        chips.clear()
        presets.forEachIndexed { index, value ->
            val chip = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                }
                gravity = Gravity.CENTER
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                text = formatRatio(value)
                setOnClickListener { onPresetClicked(index) }
            }
            chips.add(chip)
            presetRow.addView(chip)
        }
    }

    private fun onPresetClicked(index: Int) {
        if (index == activeIndex(camera.zoomRatio)) {
            // Ketuk preset yang aktif untuk menampilkan atau menyembunyikan slider.
            if (sliderVisible) hideSlider() else showSlider()
        } else {
            animateTo(presets[index])
            if (sliderVisible) scheduleHide()
        }
    }

    private fun activeIndex(ratio: Float): Int {
        var active = 0
        presets.forEachIndexed { i, p -> if (p <= ratio * 1.01f) active = i }
        return active
    }

    private fun syncUi(updateSlider: Boolean) {
        val ratio = camera.zoomRatio
        if (chips.isNotEmpty()) {
            val active = activeIndex(ratio)
            chips.forEachIndexed { i, chip ->
                val isActive = i == active
                val label = if (isActive) formatRatio(ratio) else formatRatio(presets[i])
                if (chip.text.toString() != label) chip.text = label
                if ((chip.tag as? Boolean) != isActive) {
                    chip.tag = isActive
                    if (isActive) {
                        chip.setBackgroundResource(R.drawable.bg_zoom_chip_active)
                        chip.setTextColor(ContextCompat.getColor(context, R.color.xcam_cyan))
                    } else {
                        chip.background = null
                        chip.setTextColor(ContextCompat.getColor(context, R.color.xcam_text_dim))
                    }
                }
            }
        }
        if (updateSlider && canZoom) slider.progress = ratioToProgress(ratio)
    }

    // ---- Slider

    private fun ratioToProgress(ratio: Float): Int {
        val range = ln(camera.maxZoom / camera.minZoom)
        if (range <= 0f) return 0
        return (ln(ratio / camera.minZoom) / range * SLIDER_STEPS).roundToInt()
            .coerceIn(0, SLIDER_STEPS)
    }

    private fun progressToRatio(progress: Int): Float {
        val range = ln(camera.maxZoom / camera.minZoom)
        return camera.minZoom * exp(progress.toFloat() / SLIDER_STEPS * range)
    }

    private fun showSlider() {
        if (!canZoom) return
        sliderPanel.removeCallbacks(hideRunnable)
        if (!sliderVisible) {
            sliderVisible = true
            sliderPanel.animate().cancel()
            sliderPanel.visibility = View.VISIBLE
            sliderPanel.animate().alpha(1f).setDuration(150).start()
        }
        scheduleHide()
    }

    private fun hideSlider() {
        if (userDraggingSlider) {
            scheduleHide()
            return
        }
        sliderVisible = false
        sliderPanel.animate().cancel()
        sliderPanel.animate().alpha(0f).setDuration(200).withEndAction {
            if (!sliderVisible) sliderPanel.visibility = View.INVISIBLE
        }.start()
    }

    private fun scheduleHide() {
        sliderPanel.removeCallbacks(hideRunnable)
        if (sliderVisible) sliderPanel.postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    // ---- Util

    private fun formatRatio(value: Float): String =
        if (abs(value - value.roundToInt()) < 0.05f) {
            "${value.roundToInt()}x"
        } else {
            String.format(Locale.US, "%.1fx", value)
        }

    private fun dp(v: Int) = (v * density).toInt()

    companion object {
        private const val SLIDER_STEPS = 10000
        private const val HIDE_DELAY_MS = 2500L
        /** Konstanta waktu smoothing slider (detik). Lebih kecil = lebih sigap, lebih besar = lebih lembut. */
        private const val SMOOTH_TAU = 0.06f
        private const val SMOOTH_EPSILON = 0.002f
    }
}
