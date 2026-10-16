package com.xboost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * Service foreground selama XBoost in-game aktif:
 * - bubble melayang (bisa digeser) yang menampilkan FPS + suhu real time
 * - tap bubble = panel lengkap (semua info, tombol, daftar app floating)
 * - memantau game: kalau game ditutup, mode dihentikan dan semua dipulihkan
 */
class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private lateinit var reader: StatsReader
    private var bubble: TextView? = null
    private var scrim: FrameLayout? = null
    private var statsJob: Job? = null
    private var tickJob: Job? = null
    private var statsText: TextView? = null
    private var lastStats: Stats? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        reader = StatsReader(this)
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTORE) {
            scope.launch { shutdown() }
            return START_NOT_STICKY
        }
        if (!Prefs.isActive(this)) { stopSelf(); return START_NOT_STICKY }
        startFg()
        if (bubble == null) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Izin overlay belum diberikan", Toast.LENGTH_LONG).show()
            } else addBubble()
        }
        if (statsJob == null) startStats()
        if (tickJob == null) tickJob = scope.launch {
            while (isActive) {
                delay(2500)
                if (FocusEngine.tick(applicationContext)) { shutdown(); break }
            }
        }
        return START_STICKY
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    // ---------- bubble ----------
    private fun addBubble() {
        val size = dp(58)
        val screenW = resources.displayMetrics.widthPixels
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xDD171A21.toInt())
            setStroke(dp(2), 0xFF4CAF50.toInt())
        }
        val v = TextView(this).apply {
            background = bg
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            text = "XB"
        }
        val lp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - size; y = dp(120)
        }

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx; val dy = e.rawY - ty
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        lp.x = (sx + dx).toInt(); lp.y = (sy + dy).toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val left = lp.x + size / 2 < screenW / 2
                    if (!moved) showPanel(left)
                    else {
                        lp.x = if (left) 0 else screenW - size   // nempel ke sisi layar terdekat
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
            }
            true
        }
        wm.addView(v, lp)
        bubble = v
    }

    private fun updateBubble(s: Stats) {
        val b = bubble ?: return
        val temp = s.cpuTemp ?: s.battTemp
        b.text = "${s.fps ?: "-"} FPS\n${"%.0f".format(temp)}°C"
        val color = when {
            temp < 40f -> 0xFF4CAF50.toInt()
            temp < 46f -> 0xFFFFC107.toInt()
            else -> 0xFFF44336.toInt()
        }
        (b.background as? GradientDrawable)?.setStroke(dp(2), color)
    }

    private fun startStats() {
        statsJob = scope.launch {
            while (isActive) {
                val game = Prefs.target(this@OverlayService) ?: break
                val s = withContext(Dispatchers.IO) { runCatching { reader.read(game) }.getOrNull() }
                if (s != null) {
                    lastStats = s
                    updateBubble(s)
                    statsText?.text = fmt(s)
                }
                delay(if (scrim != null) 1000 else 3000)
            }
        }
    }

    // ---------- panel ----------
    private fun label(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun showPanel(left: Boolean) {
        if (scrim != null) return
        val game = Prefs.target(this) ?: return

        val sc = FrameLayout(this)
        sc.setBackgroundColor(0x66000000)
        sc.setOnClickListener { closePanel() }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xF2171A21.toInt()); cornerRadius = dp(20).toFloat() }
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
        }
        val clp = FrameLayout.LayoutParams(dp(300), MATCH_PARENT, if (left) Gravity.START else Gravity.END)
        clp.setMargins(dp(8), dp(28), dp(8), dp(12))

        card.addView(TextView(this).apply {
            text = "XBoost · ${label(game)}"; setTextColor(Color.WHITE); textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        val st = TextView(this).apply {
            text = lastStats?.let { fmt(it) } ?: "Memuat..."
            setTextColor(0xFFB8C0CC.toInt()); textSize = 13f
            typeface = Typeface.MONOSPACE; setPadding(0, dp(10), 0, dp(10))
        }
        statsText = st
        card.addView(st)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Kosongkan RAM"; textSize = 12f
            setOnClickListener { scope.launch { toast(FocusEngine.boostMemory(applicationContext)) } }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "Stop"; textSize = 12f
            setOnClickListener { scope.launch { shutdown() } }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        card.addView(row)

        card.addView(TextView(this).apply {
            text = "App Floating"; setTextColor(Color.WHITE); textSize = 14f
            setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(12), 0, dp(6))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val apps = Prefs.floatApps(this).filter { it != game }
        if (apps.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Belum ada app. Tambahkan di XBoost > tab Floating."
                setTextColor(0xFF8A93A0.toInt()); textSize = 12f
            })
        }
        for (p in apps) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener {
                    scope.launch { toast(FocusEngine.launchFloating(applicationContext, p)) }
                    closePanel()
                }
            }
            runCatching {
                r.addView(ImageView(this).apply { setImageDrawable(packageManager.getApplicationIcon(p)) },
                    LinearLayout.LayoutParams(dp(32), dp(32)))
            }
            r.addView(TextView(this).apply {
                text = label(p); setTextColor(Color.WHITE); textSize = 14f; setPadding(dp(10), 0, 0, 0)
            })
            list.addView(r)
        }
        card.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        sc.addView(card, clp)
        card.translationX = (if (left) -dp(320) else dp(320)).toFloat()
        card.animate().translationX(0f).setDuration(180).start()

        val lp = WindowManager.LayoutParams(
            MATCH_PARENT, MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(sc, lp)
        scrim = sc
        bubble?.visibility = View.GONE
    }

    private fun closePanel() {
        scrim?.let { runCatching { wm.removeView(it) } }
        scrim = null; statsText = null
        bubble?.visibility = View.VISIBLE
    }

    private fun f(v: Float?, suf: String) = if (v == null) "-" else "%.0f%s".format(v, suf)
    private fun fmt(s: Stats): String {
        val ghz = if (s.cpuGhz == null) "-" else "%.1fGHz".format(s.cpuGhz)
        return buildString {
            appendLine("FPS      ${s.fps ?: "-"}")
            appendLine("CPU      ${s.cpuUse?.toString() ?: "-"}% · $ghz · ${f(s.cpuTemp, "°C")}")
            appendLine("GPU      ${s.gpuUse?.toString() ?: "-"}% · ${f(s.gpuTemp, "°C")}")
            appendLine("RAM      %.1f / %.1f GB".format(s.ramUsedMb / 1024f, s.ramTotalMb / 1024f))
            appendLine("Baterai  ${s.battPct}% · ${"%.1f".format(s.battTemp)}°C${if (s.charging) " · charging" else ""}")
            append("Memori   %.1f GB bebas".format(s.storageFreeGb))
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---------- lifecycle ----------
    private fun removeViews() {
        scrim?.let { runCatching { wm.removeView(it) } }
        scrim = null; statsText = null
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    private suspend fun shutdown() {
        statsJob?.cancel(); statsJob = null
        removeViews()
        withContext(Dispatchers.IO) { FocusEngine.restoreAll(applicationContext) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("focus", "XBoost", NotificationManager.IMPORTANCE_LOW))
        val restore = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_RESTORE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, "focus")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("XBoost aktif")
            .setContentText("Ketuk bubble untuk buka panel")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop & pulihkan", restore).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    override fun onDestroy() {
        removeViews()
        scope.cancel()
        super.onDestroy()
    }

    companion object { const val ACTION_RESTORE = "com.xboost.RESTORE" }
}
