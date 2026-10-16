package com.xboost

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager

data class AppEntry(val label: String, val pkg: String)

object AppRepo {
    private val PKG_OK = Regex("^[A-Za-z0-9._]+$")
    fun safe(p: String) = PKG_OK.matches(p)

    fun launchable(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    /** Semua app non-sistem (pm list packages -3). */
    fun userPackages(): List<String> =
        ShizukuShell.exec("pm list packages -3").out.lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotEmpty() && safe(it) }
            .toList()

    /** App yang TIDAK boleh dibekukan: diri sendiri, Shizuku, launcher, keyboard, telepon, SMS, jam, whitelist. */
    fun protectedPackages(ctx: Context): Set<String> {
        val s = mutableSetOf(ctx.packageName, "moe.shizuku.privileged.api",
            "com.android.systemui", "com.android.settings")
        val pm = ctx.packageManager
        runCatching {
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                ?.activityInfo?.packageName?.let { s += it }
        }
        runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.let { ComponentName.unflattenFromString(it)?.packageName }?.let { s += it }
        }
        runCatching {
            (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager)
                .defaultDialerPackage?.let { s += it }
        }
        runCatching { Telephony.Sms.getDefaultSmsPackage(ctx)?.let { s += it } }
        runCatching {
            pm.resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)
                ?.activityInfo?.packageName?.let { s += it }
        }
        s += Prefs.whitelist(ctx)
        return s
    }
}
