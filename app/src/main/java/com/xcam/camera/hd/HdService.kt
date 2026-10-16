package com.xcam.camera.hd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xcam.camera.R
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Antrean HD di latar belakang (foreground service, supaya proses lama tidak dimatikan Android
 * waktu app ditutup). Satu foto diproses satu per satu.
 */
class HdService : Service() {

    private val worker = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)
    private val ui = Handler(Looper.getMainLooper())
    private var total = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uriStr = intent?.getStringExtra(EXTRA_URI)
        if (uriStr == null) {
            if (pending.get() == 0) stopSelf()
            return START_NOT_STICKY
        }
        val isMotion = intent?.getBooleanExtra(EXTRA_MOTION, false) ?: false
        total++
        pending.incrementAndGet()
        startForegroundCompat(buildNotification(0, "Menunggu giliran…"))
        val uri = Uri.parse(uriStr)
        val app = applicationContext
        worker.execute {
            val key = uri.toString()
            progress[key] = 0
            var result: HdUpscaler.Result? = null
            try {
                var lastPct = -1
                result = HdProcessor.process(app, uri, isMotion) { p ->
                    progress[key] = p
                    if (p != lastPct) {
                        lastPct = p
                        postNotification(buildNotification(p, "Memproses HD ($p%)"))
                        listener?.let { l -> ui.post { l.onProgress(uri, p) } }
                    }
                    !cancelled.contains(key)
                }
            } catch (e: Throwable) {
                result = HdUpscaler.Result(HdUpscaler.Status.FAILED, detail = e.javaClass.simpleName)
            }
            progress.remove(key)
            cancelled.remove(key)
            val res = result ?: HdUpscaler.Result(HdUpscaler.Status.FAILED)
            ui.post {
                Toast.makeText(app, messageFor(res), Toast.LENGTH_LONG).show()
                listener?.onDone(uri, res.status == HdUpscaler.Status.OK)
            }
            if (pending.decrementAndGet() == 0) {
                total = 0
                ui.post {
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    // ---- Notifikasi

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "HD foto", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(percent: Int, text: String): Notification {
        ensureChannel()
        val waiting = pending.get().coerceAtLeast(1)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (waiting > 1) "XCAM HD ($waiting foto)" else "XCAM HD")
            .setContentText(text)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun postNotification(n: Notification) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
        } catch (e: Exception) {
        }
    }

    private fun startForegroundCompat(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            // Tidak boleh jadi foreground (mis. app di latar): tetap jalan sebagai service biasa.
        }
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else stopForeground(true)
        } catch (e: Exception) {
        }
    }

    interface Listener {
        fun onProgress(uri: Uri, percent: Int)
        fun onDone(uri: Uri, success: Boolean)
    }

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_MOTION = "motion"
        private const val CHANNEL = "xcam_hd"
        private const val NOTIF_ID = 4107

        /** Pendengar tunggal (penampil galeri yang sedang terbuka). */
        @Volatile var listener: Listener? = null

        private val progress = ConcurrentHashMap<String, Int>()
        private val cancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()

        fun isBusy(uri: Uri): Boolean = progress.containsKey(uri.toString())
        fun progressOf(uri: Uri): Int = progress[uri.toString()] ?: -1
        fun cancel(uri: Uri) {
            if (progress.containsKey(uri.toString())) cancelled.add(uri.toString())
        }

        /** @return false jika service tidak boleh dijalankan sekarang. */
        fun enqueue(ctx: Context, uri: Uri, isMotion: Boolean): Boolean {
            if (!HdNative.loaded) return false
            return try {
                // Foto yang sama sedang/antre diproses: tidak perlu dua kali.
                if (progress.putIfAbsent(uri.toString(), -1) != null) return true
                ContextCompat.startForegroundService(
                    ctx.applicationContext,
                    Intent(ctx.applicationContext, HdService::class.java)
                        .putExtra(EXTRA_URI, uri.toString())
                        .putExtra(EXTRA_MOTION, isMotion)
                )
                true
            } catch (e: Exception) {
                progress.remove(uri.toString())
                false
            }
        }

        fun messageFor(r: HdUpscaler.Result): String = when (r.status) {
            HdUpscaler.Status.OK -> "HD selesai" + if (r.usedGpu) " (GPU)" else " (CPU)"
            HdUpscaler.Status.ALREADY_HD -> "Foto ini sudah HD"
            HdUpscaler.Status.TOO_BIG -> "Foto terlalu besar untuk HD 2x (${r.detail})"
            HdUpscaler.Status.NO_LIBRARY -> "Pustaka HD tidak termuat di HP ini"
            HdUpscaler.Status.NO_MODEL -> "Model HD tidak bisa dimuat ${r.detail}".trim()
            HdUpscaler.Status.DECODE_FAILED -> "Foto tidak bisa dibaca untuk HD ${r.detail}".trim()
            HdUpscaler.Status.MEMORY -> "Memori kurang untuk HD 2x"
            HdUpscaler.Status.CANCELLED -> "HD dibatalkan, foto asli tetap"
            HdUpscaler.Status.FAILED -> "HD gagal, foto asli tetap ${r.detail}".trim()
        }
    }
}
