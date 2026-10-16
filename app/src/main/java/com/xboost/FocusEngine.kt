package com.xboost

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

object FocusEngine {
    /** pkg floating yang sedang dibuka -> jumlah cek berturut-turut yang tidak terlihat */
    val floating = ConcurrentHashMap<String, Int>()

    private const val GRACE_MS = 15_000L   // jeda awal sebelum cek game masih jalan
    private const val MISS_LIMIT = 8       // 8 x 2,5 dtk = 20 dtk game tidak terlihat -> mode berhenti
    @Volatile private var startedAt = 0L
    @Volatile private var gameMiss = 0
    @Volatile private var tickCount = 0

    private val VISIBLE = Regex("visible(Requested)?=true", RegexOption.IGNORE_CASE)
    private val PKG_IN_HEAD = Regex(" ([A-Za-z0-9._]+)/")

    private fun freeRamMb(ctx: Context): Long {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.availMem / (1024 * 1024)
    }

    private fun freeze(ctx: Context, pkg: String) {
        if (!AppRepo.safe(pkg)) return
        Prefs.addSuspended(ctx, pkg) // dicatat dulu, baru dibekukan (aman kalau crash)
        ShizukuShell.exec("am force-stop $pkg; pm suspend --user 0 $pkg")
    }

    /**
     * Dorong performa game semaksimal yang bisa tanpa root (hp bisa lebih panas):
     * Game Mode performance, fixed performance mode, framework thermal status di-override,
     * battery saver dimatikan, refresh rate dikunci ke maksimum layar.
     * Frekuensi CPU/GPU langsung tidak bisa dikunci tanpa root.
     */
    private fun boostPerformance(ctx: Context, game: String) {
        val cmds = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            cmds += "cmd game mode performance $game"
            cmds += "cmd power set-fixed-performance-mode-enabled true"
        }
        if (Build.VERSION.SDK_INT >= 29) cmds += "cmd thermalservice override-status 0"
        if (cmds.isNotEmpty()) ShizukuShell.exec(cmds.joinToString("; "))
        SettingsGuard.set(ctx, "low_power", "0")
        val hz = runCatching {
            (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .getDisplay(Display.DEFAULT_DISPLAY).supportedModes.maxOf { it.refreshRate }.roundToInt()
        }.getOrNull()
        if (hz != null && hz > 0) {
            SettingsGuard.set(ctx, "system:min_refresh_rate", hz.toString())
            SettingsGuard.set(ctx, "system:peak_refresh_rate", hz.toString())
        }
    }

    /** Fokuskan game: bekukan semua app user lain, matikan animasi (opsional), Game Mode performance. */
    suspend fun start(ctx: Context, game: String, animOff: Boolean): String = withContext(Dispatchers.IO) {
        if (!ShizukuShell.isReady()) return@withContext "Shizuku belum siap"
        if (!AppRepo.safe(game)) return@withContext "Nama package tidak valid"
        val keep = AppRepo.protectedPackages(ctx) + game
        val before = freeRamMb(ctx)
        Prefs.setTarget(ctx, game)
        Prefs.setActive(ctx, true)
        startedAt = System.currentTimeMillis()
        gameMiss = 0
        tickCount = 0
        if (animOff) SettingsGuard.ANIM_KEYS.forEach { SettingsGuard.set(ctx, it, "0") }
        val victims = AppRepo.userPackages().filter { it !in keep }
        victims.forEach { freeze(ctx, it) }
        if (Prefs.maxPerf(ctx)) boostPerformance(ctx, game)
        else if (Build.VERSION.SDK_INT >= 31) ShizukuShell.exec("cmd game mode performance $game")
        delay(500)
        "XBoost aktif: ${victims.size} app dibekukan. RAM bebas ${before}MB -> ${freeRamMb(ctx)}MB"
    }

    /** Kembalikan semuanya: unsuspend, animasi, freeform. Aman dipanggil berkali-kali. */
    suspend fun restoreAll(ctx: Context): String = withContext(Dispatchers.IO) {
        if (!ShizukuShell.isReady()) return@withContext "Shizuku belum siap, nyalakan Shizuku lalu coba lagi"
        Prefs.suspended(ctx).forEach {
            if (AppRepo.safe(it)) ShizukuShell.exec("pm unsuspend --user 0 $it")
        }
        Prefs.clearSuspended(ctx)
        val resetGame = Prefs.target(ctx)?.takeIf { AppRepo.safe(it) }?.let { "; cmd game mode standard $it" } ?: ""
        ShizukuShell.exec("cmd power set-fixed-performance-mode-enabled false; cmd thermalservice reset$resetGame")
        SettingsGuard.restoreAll(ctx)
        floating.clear()
        Prefs.setActive(ctx, false)
        "Semua dipulihkan"
    }

    suspend fun boostMemory(ctx: Context): String = withContext(Dispatchers.IO) {
        if (!ShizukuShell.isReady()) return@withContext "Shizuku belum siap"
        val before = freeRamMb(ctx)
        val keep = AppRepo.protectedPackages(ctx) + (Prefs.target(ctx)?.let { setOf(it) } ?: emptySet())
        val victims = AppRepo.userPackages().filter { it !in keep }
        victims.forEach { ShizukuShell.exec("am force-stop $it") }
        ShizukuShell.exec("am kill-all")
        delay(800)
        "${victims.size} app dihentikan. RAM bebas ${before}MB -> ${freeRamMb(ctx)}MB"
    }

    /** Buka app dalam jendela mengambang; app dibebaskan sementara dari pembekuan. */
    suspend fun launchFloating(ctx: Context, pkg: String): String = withContext(Dispatchers.IO) {
        if (!ShizukuShell.isReady()) return@withContext "Shizuku belum siap"
        if (!AppRepo.safe(pkg)) return@withContext "Nama package tidak valid"
        val comp = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
            ?: return@withContext "App tidak punya launcher activity"
        SettingsGuard.set(ctx, "enable_freeform_support", "1")
        SettingsGuard.set(ctx, "force_resizable_activities", "1")
        ShizukuShell.exec("pm unsuspend --user 0 $pkg")
        Prefs.removeSuspended(ctx, pkg)
        floating[pkg] = 0
        val r = ShizukuShell.exec("am start --user 0 --windowingMode 5 -n $comp")
        if (r.code != 0) "Gagal buka floating: ${r.err.take(120)}" else "Floating dibuka"
    }

    /**
     * Dipanggil tiap 2,5 dtk oleh OverlayService.
     * - bekukan lagi app floating yang sudah ditutup
     * - return true kalau game sudah ditutup (mode harus dihentikan)
     */
    suspend fun tick(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val game = Prefs.target(ctx) ?: return@withContext true
        tickCount++
        if (tickCount % 24 == 0 && Prefs.maxPerf(ctx)) // ~tiap 1 menit: tegaskan ulang mode performa + bersihkan background
            ShizukuShell.exec("cmd power set-fixed-performance-mode-enabled true; am kill-all")
        val dump = ShizukuShell.exec("dumpsys activity activities").out
        if (dump.isBlank()) return@withContext false // Shizuku/dumpsys gagal, jangan ambil keputusan
        val vis = visiblePackages(dump)

        for ((pkg, miss) in floating.entries.toList()) {
            if (pkg in vis) { floating[pkg] = 0; continue }
            if (miss + 1 >= 2) { floating.remove(pkg); freeze(ctx, pkg) }
            else floating[pkg] = miss + 1
        }

        if (System.currentTimeMillis() - startedAt < GRACE_MS) return@withContext false
        if (game in vis) gameMiss = 0 else gameMiss++
        gameMiss >= MISS_LIMIT
    }

    /**
     * Kumpulkan package yang punya ActivityRecord visible.
     * Format dumpsys beda antar versi Android, bagian ini yang paling perlu dites di hp.
     */
    private fun visiblePackages(dump: String): Set<String> {
        val out = mutableSetOf<String>()
        for (seg in dump.split("ActivityRecord{").drop(1)) {
            val pkg = PKG_IN_HEAD.find(seg.take(200))?.groupValues?.get(1) ?: continue
            if (VISIBLE.containsMatchIn(seg.take(1500))) out += pkg
        }
        return out
    }
}
