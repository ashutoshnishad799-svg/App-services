package com.ashuapps.lock

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Shizuku bridge for shell-level actions (hide, unhide, force-stop). */
object Sh {
    @Volatile var last = "" // output of the last failed step, shown in a toast

    fun ready() = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun ask() { if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(1) }

    /** Runs a shell command through Shizuku (stdout + stderr). newProcess is private in API 13+, hence reflection. */
    fun run(cmd: String): String = runCatching {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", "$cmd 2>&1"), null, null) as Process
        p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }.getOrElse { "error: ${it.message ?: it.javaClass.simpleName}" }

    /** Installed and visible for user 0? Hidden apps are not. */
    fun visible(pkg: String) = run("pm path --user 0 $pkg").contains("package:")

    fun hide(pkg: String): Boolean {
        val a = run("pm hide --user 0 $pkg")
        if (!visible(pkg)) return true
        val b = run("pm disable-user --user 0 $pkg")
        if (b.contains("disabled")) return true
        last = (a + " " + b).trim(); return false
    }

    /** Only true when the app is really back (checked), so the list never forgets an app that is still hidden. */
    fun unhide(pkg: String): Boolean {
        val a = run("pm unhide --user 0 $pkg") + " " + run("pm enable --user 0 $pkg")
        if (visible(pkg)) return true
        last = a.trim(); return false
    }

    fun forceStop(pkg: String) = run("am force-stop $pkg")
}
