package com.ashuapps.lock

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.time.LocalDate
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** All settings + salted PBKDF2 PIN hash. SharedPreferences = instant sync reads for the service. */
class LockStore(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("lock", Context.MODE_PRIVATE)

    var locked: Set<String>
        get() = p.getStringSet("locked", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("locked", HashSet(v)).apply()
    var relockOnClose: Boolean
        get() = p.getBoolean("close", true)
        set(v) = p.edit().putBoolean("close", v).apply()
    var relockOnScreenOff: Boolean
        get() = p.getBoolean("off", true)
        set(v) = p.edit().putBoolean("off", v).apply()
    var autoBio: Boolean
        get() = p.getBoolean("bio", true)
        set(v) = p.edit().putBoolean("bio", v).apply()
    var theme: Int // 0 Glass, 1 Gradient, 2 Normal
        get() = p.getInt("theme", 0)
        set(v) = p.edit().putInt("theme", v).apply()
    var lockType: Int // 0 PIN, 1 Pattern, 2 Password
        get() = p.getInt("ltype", 0)
        set(v) = p.edit().putInt("ltype", v).apply()
    var selfie: Boolean // front-camera photo after 3 wrong tries
        get() = p.getBoolean("selfie", false)
        set(v) = p.edit().putBoolean("selfie", v).apply()
    var fakeCrash: Boolean
        get() = p.getBoolean("crash", false)
        set(v) = p.edit().putBoolean("crash", v).apply()
    var hidden: Set<String> // "pkg|label" (hidden apps vanish from PackageManager, so the label is kept here)
        get() = p.getStringSet("hid", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("hid", HashSet(v)).apply()
    var reels: Boolean // block Instagram Reels / YouTube Shorts
        get() = p.getBoolean("reels", false)
        set(v) = p.edit().putBoolean("reels", v).apply()
    var pausedUntil: Long // Reels blocker, focus and limits are all skipped until then
        get() = p.getLong("pause", 0)
        set(v) = p.edit().putLong("pause", v).apply()
    var focusUntil: Long
        get() = p.getLong("focus", 0)
        set(v) = p.edit().putLong("focus", v).apply()
    var focusApps: Set<String>
        get() = p.getStringSet("fapps", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("fapps", HashSet(v)).apply()
    var games: Set<String> // manually marked games (Play-installed games are auto-detected)
        get() = p.getStringSet("games", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("games", HashSet(v)).apply()
    var gameLimit: Int // total daily minutes for ALL games, 0 = off
        get() = p.getInt("glimit", 0)
        set(v) = p.edit().putInt("glimit", v).apply()
    var limits: Set<String> // "pkg|minutes" daily limits
        get() = p.getStringSet("limits", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("limits", HashSet(v)).apply()

    fun limitOf(pkg: String) = limits.firstOrNull { it.startsWith("$pkg|") }?.substringAfter('|')?.toIntOrNull() ?: 0
    fun setLimit(pkg: String, min: Int) {
        limits = limits.filterNot { it.startsWith("$pkg|") }.toSet() + (if (min > 0) setOf("$pkg|$min") else emptySet())
    }

    private fun roll() { // usage counters reset every day
        val t = LocalDate.now().toEpochDay()
        if (p.getLong("uday", -1) != t) {
            val e = p.edit()
            p.all.keys.filter { it.startsWith("u:") }.forEach { e.remove(it) }
            e.putLong("uday", t).apply()
        }
    }
    fun used(k: String): Long { roll(); return p.getLong("u:$k", 0) }
    fun addUsed(k: String, ms: Long) { roll(); p.edit().putLong("u:$k", used(k) + ms).apply() }

    var recentsBlur: Boolean // cover locked apps in the Recents screen
        get() = p.getBoolean("rblur", true)
        set(v) = p.edit().putBoolean("rblur", v).apply()
    var volVault: Boolean // Volume Up, Up, Down opens the hidden-apps vault
        get() = p.getBoolean("vol", true)
        set(v) = p.edit().putBoolean("vol", v).apply()
    var secureMain: Boolean // hide this app in Recents and screenshots
        get() = p.getBoolean("secure", false)
        set(v) = p.edit().putBoolean("secure", v).apply()
    var revealed: Set<String> // hidden apps temporarily unhidden from the vault
        get() = p.getStringSet("reveal", emptySet()) ?: emptySet()
        set(v) = p.edit().putStringSet("reveal", HashSet(v)).apply()
    var revealAt: Long
        get() = p.getLong("revat", 0)
        set(v) = p.edit().putLong("revat", v).apply()
    val pinLen get() = p.getInt("len", 4)
    fun hasPin() = p.contains("h")

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        p.edit().putString("s", salt).putString("h", hash(salt, pin)).putInt("len", pin.length).apply()
    }

    fun check(pin: String) = hash(p.getString("s", "")!!, pin) == p.getString("h", "")

    private fun hash(salt: String, pin: String) =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt.toByteArray(), 20_000, 256))
            .encoded.joinToString("") { "%02x".format(it) }
}

fun autoGame(pm: PackageManager, pkg: String) = runCatching {
    pm.getApplicationInfo(pkg, 0).category == ApplicationInfo.CATEGORY_GAME
}.getOrDefault(false)
