package com.ashuapps.lock

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.biometric.BiometricManager

/** Engine: app lock, focus mode, daily limits, games limit, Reels/Shorts blocker, pause. */
class LockService : AccessibilityService() {
    private lateinit var store: LockStore
    private lateinit var wm: WindowManager
    private lateinit var overlay: LockOverlay
    private lateinit var blocker: BlockOverlay
    private val handler = Handler(Looper.getMainLooper())
    private val unlocked = HashSet<String>()
    private val gameCache = HashMap<String, Boolean>()
    private var fg = ""            // last real foreground app
    private var target = ""        // app covered by the lock overlay
    private var since = 0L         // when fg started counting
    private var counting = false   // false while the screen is off
    private var nextScan = 0L
    private var skip = setOf("android", "com.android.systemui", "com.google.android.permissioncontroller")

    // Best effort: app updates can rename these view ids (find new ones with Layout Inspector / uiautomator dump).
    private val reelIds = mapOf(
        "com.instagram.android" to listOf("clips_viewer_view_pager", "clips_swipe_refresh_container"),
        "com.google.android.youtube" to listOf("reel_recycler", "reel_player_page_container"))

    private val loop = object : Runnable { override fun run() { guard(fg); rearm() } }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            commit()
            if (i.action == Intent.ACTION_SCREEN_OFF) {
                counting = false; handler.removeCallbacks(loop)
                if (store.relockOnScreenOff) unlocked.clear()
            } else { counting = true; guard(fg); rearm() }
        }
    }

    override fun onServiceConnected() {
        instance = this
        store = LockStore(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlay = LockOverlay(this)
        blocker = BlockOverlay(this)
        counting = true; since = SystemClock.elapsedRealtime()
        skip = skip + getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) })
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (store.reels && pkg == fg && pkg in reelIds) blockReels(pkg)
            return
        }
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (pkg == packageName || pkg in skip) return
        if (pkg != fg) { commit(); fg = pkg }
        guard(pkg); rearm()
        if (store.reels && pkg in reelIds) blockReels(pkg)
    }

    private fun guard(pkg: String) {
        val why = blockReason(pkg)
        if (why != null) { overlay.hide(wm); blocker.show(wm, why); return }
        blocker.hide(wm)
        if (pkg in store.locked && pkg !in unlocked && store.hasPin()) {
            target = pkg
            if (overlay.show(wm, pkg) && store.autoBio) askBiometric()
        } else {
            overlay.hide(wm)
            if (store.relockOnClose) unlocked.retainAll(setOf(pkg)) // left the app => relock it
        }
    }

    // ---- usage, focus, limits ----
    private fun isGame(pkg: String) = pkg in store.games || gameCache.getOrPut(pkg) { autoGame(packageManager, pkg) }

    /** Adds the elapsed foreground time of `fg` to today's usage. */
    private fun commit() {
        val now = SystemClock.elapsedRealtime()
        if (counting && fg.isNotEmpty()) {
            val d = now - since
            if (store.limitOf(fg) > 0 || isGame(fg)) store.addUsed(fg, d)
            if (isGame(fg)) store.addUsed("@games", d)
        }
        since = now
    }

    private fun usedNow(k: String): Long {
        val live = counting && (k == fg || (k == "@games" && isGame(fg)))
        return store.used(k) + if (live) SystemClock.elapsedRealtime() - since else 0L
    }

    private fun blockReason(pkg: String): String? {
        val now = System.currentTimeMillis()
        if (now < store.pausedUntil) return null
        val game = isGame(pkg)
        if (now < store.focusUntil && (pkg in store.focusApps || game))
            return "Focus mode is on\n${(store.focusUntil - now) / 60000 + 1} min left"
        val lim = store.limitOf(pkg)
        if (lim > 0 && usedNow(pkg) >= lim * 60_000L) return "Daily limit of $lim min reached"
        if (game && store.gameLimit > 0 && usedNow("@games") >= store.gameLimit * 60_000L) return "Games daily limit reached"
        return null
    }

    private fun watched(pkg: String) = pkg.isNotEmpty() && (store.limitOf(pkg) > 0 || pkg in store.focusApps || isGame(pkg))

    /** While a watched app is on screen, re-check every 5s so limits / focus kick in mid-use. */
    private fun rearm() { handler.removeCallbacks(loop); if (counting && watched(fg)) handler.postDelayed(loop, 5000) }

    // ---- Reels / Shorts blocker ----
    private fun blockReels(pkg: String) {
        val t = SystemClock.elapsedRealtime()
        if (t < nextScan || System.currentTimeMillis() < store.pausedUntil) return
        nextScan = t + 400 // content events flood in; scan at most every 400ms
        val ids = reelIds[pkg] ?: return
        val root = rootInActiveWindow ?: return
        val insta = pkg == "com.instagram.android"
        val hit = ids.any { root.findAccessibilityNodeInfosByViewId("$pkg:id/$it").isNotEmpty() } ||
            (insta && root.findAccessibilityNodeInfosByViewId("$pkg:id/clips_tab").any { it.isSelected })
        if (!hit) return
        nextScan = t + 1200
        val feed = if (insta) root.findAccessibilityNodeInfosByViewId("$pkg:id/feed_tab").firstOrNull() else null
        val ok = generateSequence(feed) { it.parent }.firstOrNull { it.isClickable }
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (!ok) performGlobalAction(GLOBAL_ACTION_BACK)
        Toast.makeText(this, "Reels/Shorts blocked", Toast.LENGTH_SHORT).show()
    }

    fun unlock() { unlocked += target; overlay.hide(wm) }

    /** Called after 3 wrong tries: a transparent activity takes the front-camera photo. */
    fun intruder() {
        if (!store.selfie || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        startActivity(Intent(this, SelfieActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }

    fun goHome() { performGlobalAction(GLOBAL_ACTION_HOME) }

    fun askBiometric() {
        val ok = BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (ok != BiometricManager.BIOMETRIC_SUCCESS) return
        startActivity(Intent(this, BioActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(loop)
        runCatching { unregisterReceiver(screen); overlay.hide(wm); blocker.hide(wm) }
        super.onDestroy()
    }

    companion object { @Volatile var instance: LockService? = null }
}
