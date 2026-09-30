package com.ashuapps.lock

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Shizuku bridge for shell-level actions (hider, force-stop). UI wiring comes in Phase 2. */
object Sh {
    fun ready() = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun ask() { if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(1) }

    /** Shizuku.newProcess is private in API 13+, reflection is the common workaround. */
    fun run(cmd: String): String {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        return p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }

    /** Tries `pm hide`, falls back to `pm disable-user`. True if either worked. */
    fun hide(pkg: String) = runCatching {
        run("pm hide --user 0 $pkg").contains("true") || run("pm disable-user --user 0 $pkg").contains("disabled")
    }.getOrDefault(false)

    fun unhide(pkg: String) = runCatching {
        run("pm unhide --user 0 $pkg"); run("pm enable --user 0 $pkg")
    }.isSuccess
    fun forceStop(pkg: String) = run("am force-stop $pkg")
}
