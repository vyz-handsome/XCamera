package com.xboost

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

data class ShellResult(val code: Int, val out: String, val err: String)

object ShizukuShell {
    fun isReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun isRunning(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun requestPermission() {
        try { Shizuku.requestPermission(1001) } catch (_: Throwable) {}
    }

    /** newProcess() private di Shizuku 13.x, jadi dipanggil lewat reflection. */
    fun exec(cmd: String): ShellResult {
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            m.isAccessible = true
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            ShellResult(p.waitFor(), out, err)
        } catch (t: Throwable) {
            ShellResult(-1, "", t.message ?: "error")
        }
    }
}
