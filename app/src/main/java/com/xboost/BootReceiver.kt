package com.xboost

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Setelah restart, coba pulihkan otomatis. Kalau Shizuku belum jalan, banner "Pulihkan" muncul saat app dibuka. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.isActive(c) && Prefs.suspended(c).isEmpty()) return
        val pr = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { FocusEngine.restoreAll(c.applicationContext) } finally { pr.finish() }
        }
    }
}
