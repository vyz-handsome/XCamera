package com.xboost

import android.content.Context

/** Semua state disimpan di disk supaya aman kalau app crash / hp restart. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("xboost", Context.MODE_PRIVATE)

    fun isActive(c: Context) = sp(c).getBoolean("active", false)
    fun setActive(c: Context, v: Boolean) = sp(c).edit().putBoolean("active", v).commit()

    fun target(c: Context): String? = sp(c).getString("target", null)
    fun setTarget(c: Context, v: String?) = sp(c).edit().putString("target", v).apply()

    fun animOff(c: Context) = sp(c).getBoolean("anim_off", true)
    fun setAnimOff(c: Context, v: Boolean) = sp(c).edit().putBoolean("anim_off", v).apply()

    fun maxPerf(c: Context) = sp(c).getBoolean("max_perf", true)
    fun setMaxPerf(c: Context, v: Boolean) = sp(c).edit().putBoolean("max_perf", v).apply()

    @Synchronized fun suspended(c: Context): Set<String> =
        sp(c).getStringSet("suspended", emptySet())!!.toSet()
    @Synchronized fun addSuspended(c: Context, p: String) {
        sp(c).edit().putStringSet("suspended", suspended(c) + p).commit()
    }
    @Synchronized fun removeSuspended(c: Context, p: String) {
        sp(c).edit().putStringSet("suspended", suspended(c) - p).commit()
    }
    @Synchronized fun clearSuspended(c: Context) = sp(c).edit().remove("suspended").commit()

    private fun getSet(c: Context, k: String): Set<String> =
        sp(c).getStringSet(k, emptySet())!!.toSet()
    private fun toggle(c: Context, k: String, p: String) {
        val w = getSet(c, k)
        sp(c).edit().putStringSet(k, if (p in w) w - p else w + p).apply()
    }

    fun whitelist(c: Context) = getSet(c, "whitelist")
    fun toggleWhitelist(c: Context, p: String) = toggle(c, "whitelist", p)
    fun games(c: Context) = getSet(c, "games")
    fun toggleGame(c: Context, p: String) = toggle(c, "games", p)
    fun floatApps(c: Context) = getSet(c, "float_apps")
    fun toggleFloat(c: Context, p: String) = toggle(c, "float_apps", p)

    // nilai asli setting sistem (animasi, freeform) untuk dikembalikan
    fun orig(c: Context, key: String): String? = sp(c).getString("orig_$key", null)
    fun setOrig(c: Context, key: String, v: String) = sp(c).edit().putString("orig_$key", v).commit()
    fun origKeys(c: Context): List<String> =
        sp(c).all.keys.filter { it.startsWith("orig_") }.map { it.removePrefix("orig_") }
    fun clearOrig(c: Context) {
        val e = sp(c).edit()
        origKeys(c).forEach { e.remove("orig_$it") }
        e.commit()
    }
}
