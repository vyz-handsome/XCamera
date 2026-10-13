package com.xcam.camera.settings

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.CamcorderProfile
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.util.Size
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.xcam.camera.R
import com.xcam.camera.databinding.ActivitySettingsBinding
import com.xcam.camera.gallery.MediaStoreHelper
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Pengaturan XCAM. Semua pilihan disimpan lokal (SharedPreferences) dan hanya
 * pilihan yang benar-benar didukung HP yang ditampilkan.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { Prefs.of(this) }
    private val io = Executors.newSingleThreadExecutor()

    private class Option(val value: String, val label: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { finish() }
        buildRows()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun buildRows() {
        val c = binding.container

        // ---- Kamera
        section(c, "Kamera")
        if (packageManager.hasSystemFeature("android.hardware.camera.front")) {
            choiceRow(
                c, "Kamera saat app dibuka", Prefs.DEFAULT_CAMERA, "back",
                listOf(Option("back", "Belakang"), Option("front", "Depan")),
                "Berlaku saat XCAM dibuka berikutnya."
            )
        }
        choiceRow(
            c, "Flash awal", Prefs.DEFAULT_FLASH, "off",
            listOf(Option("off", "Mati"), Option("auto", "Otomatis"), Option("on", "Menyala")),
            "Hanya berlaku di kamera yang punya flash. Berlaku saat XCAM dibuka berikutnya."
        )
        val sizes = photoSizeOptions()
        choiceRow(
            c, "Resolusi foto", Prefs.PHOTO_SIZE, "auto", sizes,
            "Ukuran 4:3 yang didukung kamera belakang. Kamera depan memakai ukuran terdekat."
        )
        switchRow(
            c, "Cerminkan foto kamera depan", "Hasil selfie sama seperti yang terlihat di preview.",
            Prefs.MIRROR_FRONT, true
        )

        // ---- Video & Live Photo
        section(c, "Video dan Live Photo")
        choiceRow(
            c, "Kualitas video", Prefs.VIDEO_QUALITY, "FHD", videoQualityOptions(),
            "Jika HP tidak mampu di kualitas ini, XCAM otomatis turun ke yang terdekat."
        )
        choiceRow(
            c, "Frame rate video (FPS)", Prefs.VIDEO_FPS, "auto", videoFpsOptions(),
            "Hanya berlaku di mode Video. Mode Foto dan Live Photo selalu otomatis. " +
                "Jika kombinasi FPS dan kualitas tidak didukung, XCAM memakai yang terdekat."
        )

        choiceRow(
            c, "Mode EIS", Prefs.EIS_MODE, "auto",
            listOf(
                Option("auto", "Otomatis (hardware, lalu software)"),
                Option("soft", "Software saja (gyroscope)"),
                Option("hw", "Hardware saja")
            ),
            "Software = EIS buatan XCAM memakai gyroscope, untuk HP yang tidak mengizinkan EIS hardware " +
                "di app pihak ketiga. Pilih \"Software saja\" jika EIS hardware aktif tapi hasilnya tetap bergetar. " +
                "Berlaku setelah kamera dibuka ulang."
        )
        choiceRow(
            c, "Kekuatan EIS software", Prefs.EIS_STRENGTH, "normal",
            listOf(
                Option("normal", "Normal (crop ringan, lebih tajam)"),
                Option("strong", "Kuat (lebih halus, crop lebih besar)")
            ),
            "Kuat meredam getaran lebih banyak tetapi bidang pandang video lebih sempit."
        )

        switchRow(
            c, "Foto Live resolusi tinggi (uji)", "Foto di dalam Live Photo minta ukuran sebesar mungkin (jepretan shutter asli, bukan frame video). Kalau HP tidak mendukung bareng video, otomatis turun ke ukuran standar. Berlaku setelah ganti mode atau buka ulang kamera.",
            Prefs.LIVE_HI_STILL, false
        )
        switchRow(
            c, "Pratinjau EIS (uji)", "Preview ikut distabilkan EIS software supaya hasilnya bisa dilihat langsung tanpa merekam. Berlaku setelah kamera dibuka ulang.",
            Prefs.EIS_PREVIEW, false
        )
        choiceRow(
            c, "Kalibrasi arah EIS (uji)", Prefs.EIS_FLIP, "0",
            listOf(
                Option("0", "0: normal"),
                Option("1", "1: balik sumbu X"),
                Option("2", "2: balik sumbu Y"),
                Option("3", "3: balik X dan Y"),
                Option("4", "4: balik sumbu Z"),
                Option("5", "5: balik X dan Z"),
                Option("6", "6: balik Y dan Z"),
                Option("7", "7: balik X, Y, dan Z")
            ),
            "Untuk mencari arah yang benar: nyalakan Pratinjau EIS, goyang HP, pilih angka yang bikin gambar paling tenang."
        )

        // ---- Zoom
        section(c, "Zoom")
        choiceRow(
            c, "Kehalusan slider zoom", Prefs.ZOOM_SMOOTH, "normal",
            listOf(
                Option("fast", "Cepat (lebih sigap)"),
                Option("normal", "Halus (disarankan)"),
                Option("soft", "Sangat halus (lebih lembut)")
            ),
            "Mengatur seberapa lembut zoom mengikuti slider."
        )
        switchRow(
            c, "Tampilkan slider saat pinch", "Slider zoom muncul otomatis ketika mencubit layar.",
            Prefs.ZOOM_SLIDER_ON_PINCH, true
        )

        // ---- Tampilan
        section(c, "Tampilan")
        switchRow(
            c, "Garis bantu (grid 3x3)", "Membantu komposisi foto dan video.",
            Prefs.SHOW_GRID, false
        )

        // ---- Penyimpanan
        section(c, "Penyimpanan")
        infoRow(c, "Lokasi penyimpanan", "Foto: Pictures/XCamera\nVideo: Movies/XCamera")
        val storageValue = infoRow(c, "Media XCAM", "Menghitung...")
        try {
            io.execute {
                val count = MediaStoreHelper.queryAll(this).size
                val free = try {
                    val stat = StatFs(Environment.getExternalStorageDirectory().path)
                    stat.availableBytes
                } catch (e: Exception) {
                    -1L
                }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    val freeText = if (free >= 0) {
                        String.format(Locale.US, "%.1f GB", free / 1_073_741_824.0)
                    } else {
                        "tidak diketahui"
                    }
                    storageValue.text = "$count item (Live Photo dihitung satu)\nRuang kosong: $freeText"
                }
            }
        } catch (_: RejectedExecutionException) {
        }

        // ---- Tentang
        section(c, "Tentang")
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1"
        } catch (e: Exception) {
            "1"
        }
        infoRow(c, "XCAM V1", "Versi $version\ncom.xcam.camera")
    }

    // ---- Opsi yang bergantung HP

    private fun photoSizeOptions(): List<Option> {
        val options = mutableListOf(Option("auto", "Otomatis (disarankan)"))
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val backId = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
            if (backId != null) {
                val map = cm.getCameraCharacteristics(backId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes: List<Size> = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
                sizes.filter { it.width * 3 == it.height * 4 && it.width.toLong() * it.height >= 2_000_000L }
                    .sortedByDescending { it.width.toLong() * it.height }
                    .distinctBy { it.width.toLong() * it.height }
                    .take(6)
                    .forEach {
                        val mp = it.width.toLong() * it.height / 1_000_000.0
                        options.add(
                            Option(
                                "${it.width}x${it.height}",
                                String.format(Locale.US, "%.0f MP (%d x %d)", mp, it.width, it.height)
                            )
                        )
                    }
            }
        } catch (e: Exception) {
        }
        return options
    }

    /** Otomatis + FPS tetap (24/30/60) yang dilaporkan kamera belakang. Jika gagal dibaca, semua ditampilkan. */
    private fun videoFpsOptions(): List<Option> {
        val auto = Option("auto", "Otomatis")
        val all = listOf(24, 30, 60)
        val supported = try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK
            }
            val ranges = id?.let {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            }
            if (ranges == null) all else all.filter { f ->
                ranges.any { it.lower == f && it.upper == f } ||
                    (f == 30 && ranges.any { it.lower <= 30 && it.upper >= 30 })
            }
        } catch (e: Exception) {
            all
        }
        return listOf(auto) + supported.map { Option(it.toString(), "$it fps") }
    }

    private fun videoQualityOptions(): List<Option> {
        val all = listOf(
            Triple("UHD", "4K (2160p)", CamcorderProfile.QUALITY_2160P),
            Triple("FHD", "1080p (Full HD)", CamcorderProfile.QUALITY_1080P),
            Triple("HD", "720p (HD)", CamcorderProfile.QUALITY_720P),
            Triple("SD", "480p (SD)", CamcorderProfile.QUALITY_480P)
        )
        val supported = all.filter {
            try {
                CamcorderProfile.hasProfile(0, it.third)
            } catch (e: Exception) {
                false
            }
        }
        return (if (supported.isEmpty()) all else supported).map { Option(it.first, it.second) }
    }

    // ---- Baris UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.xcam_cyan))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(20), dp(22), dp(20), dp(6))
        })
    }

    private fun titleView(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, R.color.xcam_text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    }

    private fun subtitleView(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, R.color.xcam_text_dim))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(2), 0, 0)
    }

    private fun choiceRow(
        parent: LinearLayout,
        title: String,
        key: String,
        default: String,
        options: List<Option>,
        note: String
    ) {
        fun currentLabel(): String {
            val v = prefs.all[key]?.toString() ?: default
            return options.firstOrNull { it.value == v }?.label
                ?: options.firstOrNull { it.value == default }?.label
                ?: options.firstOrNull()?.label.orEmpty()
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            isClickable = true
            setBackgroundResource(android.R.drawable.list_selector_background)
        }
        row.addView(titleView(title))
        val value = TextView(this).apply {
            text = currentLabel()
            setTextColor(ContextCompat.getColor(context, R.color.xcam_cyan))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(2), 0, 0)
        }
        row.addView(value)
        row.addView(subtitleView(note))
        row.setOnClickListener {
            val current = prefs.all[key]?.toString() ?: default
            val checked = options.indexOfFirst { it.value == current }.coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options.map { it.label }.toTypedArray(), checked) { dialog, which ->
                    val picked = options[which].value
                    prefs.edit().putString(key, picked).apply()
                    value.text = options[which].label
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        parent.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun switchRow(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        key: String,
        default: Boolean
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(titleView(title))
        texts.addView(subtitleView(subtitle))
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = SwitchCompat(this).apply {
            isChecked = prefs.getBoolean(key, default)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        }
        row.addView(toggle)
        row.setOnClickListener { toggle.toggle() }
        parent.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun infoRow(parent: LinearLayout, title: String, value: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        row.addView(titleView(title))
        val v = TextView(this).apply {
            text = value
            setTextColor(ContextCompat.getColor(context, R.color.xcam_text_dim))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(2), 0, 0)
        }
        row.addView(v)
        parent.addView(row, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return v
    }
}
