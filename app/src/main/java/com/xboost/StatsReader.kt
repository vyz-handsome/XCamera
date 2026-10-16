package com.xboost

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs

data class Stats(
    val battPct: Int, val battTemp: Float, val charging: Boolean,
    val cpuTemp: Float?, val gpuTemp: Float?,
    val cpuUse: Int?, val cpuGhz: Float?, val gpuUse: Int?,
    val fps: Int?,
    val ramUsedMb: Long, val ramTotalMb: Long, val storageFreeGb: Float
)

/** Baca kondisi hp. Dipanggil dari thread background (pakai shell Shizuku). */
class StatsReader(private val ctx: Context) {
    private var lastTotal = 0L
    private var lastIdle = 0L
    private var layer: String? = null

    private val CMD = "head -1 /proc/stat; echo ---; " +
        "for z in /sys/class/thermal/thermal_zone*; do echo \"\$(cat \$z/type 2>/dev/null) \$(cat \$z/temp 2>/dev/null)\"; done; echo ---; " +
        "cat /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq 2>/dev/null; echo ---; " +
        "cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null"

    fun read(game: String): Stats {
        val bi = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bi?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (lvl >= 0 && scale > 0) lvl * 100 / scale else -1
        val bTemp = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val charging = (bi?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val total = mi.totalMem / (1024 * 1024)
        val used = total - mi.availMem / (1024 * 1024)
        val free = StatFs(Environment.getDataDirectory().path).availableBytes / 1024f / 1024f / 1024f

        val sec = ShizukuShell.exec(CMD).out.split(Regex("(?m)^---$"))

        // CPU usage dari delta /proc/stat
        var cpuUse: Int? = null
        sec.getOrNull(0)?.trim()?.split(Regex("\\s+"))?.drop(1)?.take(8)
            ?.map { it.toLongOrNull() ?: 0L }?.let { n ->
                if (n.size >= 5) {
                    val tot = n.sum(); val idle = n[3] + n[4]
                    if (lastTotal > 0 && tot > lastTotal) {
                        cpuUse = (100L * ((tot - lastTotal) - (idle - lastIdle)) / (tot - lastTotal)).toInt().coerceIn(0, 100)
                    }
                    lastTotal = tot; lastIdle = idle
                }
            }

        // suhu CPU / GPU dari thermal zone
        var cpuT: Float? = null
        var gpuT: Float? = null
        sec.getOrNull(1)?.lines()?.forEach { line ->
            val parts = line.trim().split(" ")
            val t = norm(parts.last().toFloatOrNull() ?: return@forEach) ?: return@forEach
            val type = parts.dropLast(1).joinToString(" ").lowercase()
            if (type.contains("gpu")) gpuT = maxOf(gpuT ?: 0f, t)
            else if (type.contains("cpu")) cpuT = maxOf(cpuT ?: 0f, t)
        }

        val ghz = sec.getOrNull(2)?.lines()?.mapNotNull { it.trim().toLongOrNull() }?.maxOrNull()
            ?.let { it / 1_000_000f }
        val gpuUse = sec.getOrNull(3)?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

        return Stats(pct, bTemp, charging, cpuT, gpuT, cpuUse, ghz, gpuUse, fps(game), used, total, free)
    }

    private fun norm(v: Float): Float? {
        val t = when {
            v > 1000f -> v / 1000f
            v > 150f -> v / 10f
            else -> v
        }
        return if (t in 1f..120f) t else null
    }

    /** FPS layer game dari SurfaceFlinger --latency (hasil bergantung versi Android/ROM). */
    private fun fps(game: String): Int? {
        layer?.let { l -> parseFps(latency(l))?.let { return it }; layer = null }
        val cands = ShizukuShell.exec("dumpsys SurfaceFlinger --list").out.lines().map { it.trim() }
            .filter { it.contains(game) && !it.contains("'") }
            .sortedByDescending { it.contains("SurfaceView") }
        for (c in cands) {
            val f = parseFps(latency(c))
            if (f != null) { layer = c; return f }
        }
        return null
    }

    private fun latency(l: String) = ShizukuShell.exec("dumpsys SurfaceFlinger --latency '$l'").out

    private fun parseFps(out: String): Int? {
        val ts = out.lines().drop(1).mapNotNull {
            val p = it.trim().split(Regex("\\s+"))
            if (p.size >= 3) p[1].toLongOrNull() else null
        }.filter { it > 0 && it != Long.MAX_VALUE }
        if (ts.size < 3) return null
        val last = ts.max()
        val n = ts.count { it > last - 1_000_000_000L }
        return if (n >= 2) n else null
    }
}
