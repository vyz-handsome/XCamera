package com.xboost

import android.content.Context

/**
 * Ubah setting sistem dan ingat nilai aslinya untuk dikembalikan.
 * Key biasa = namespace global. Pakai "system:nama" atau "secure:nama" untuk namespace lain.
 */
object SettingsGuard {
    val ANIM_KEYS = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")

    private fun ns(key: String) = if (':' in key) key.substringBefore(':') else "global"
    private fun name(key: String) = key.substringAfter(':')

    fun set(ctx: Context, key: String, value: String) {
        val n = ns(key); val k = name(key)
        if (Prefs.orig(ctx, key) == null) {
            val cur = ShizukuShell.exec("settings get $n $k").out.trim()
            Prefs.setOrig(ctx, key, if (cur.isEmpty()) "null" else cur)
        }
        ShizukuShell.exec("settings put $n $k $value")
    }

    fun restoreAll(ctx: Context) {
        Prefs.origKeys(ctx).forEach { key ->
            val n = ns(key); val k = name(key)
            val v = Prefs.orig(ctx, key)
            if (v == null || v == "null") ShizukuShell.exec("settings delete $n $k")
            else ShizukuShell.exec("settings put $n $k $v")
        }
        Prefs.clearOrig(ctx)
    }
}
