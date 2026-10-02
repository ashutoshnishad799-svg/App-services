package com.ashuapps.lock

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.biometric.BiometricManager
import kotlin.concurrent.thread

/** Engine: app lock, focus mode, daily limits, games limit, Reels/Shorts blocker, pause. */
class LockService : AccessibilityService() {
    private lateinit var store: LockStore
    private lateinit var wm: WindowManager
    private lateinit var overlay: LockOverlay
    private lateinit var blocker: BlockOverlay
    private lateinit var recents: RecentsGuard
    private val handler = Handler(Looper.getMainLooper())
    private val unlocked = HashSet<String>()
    private val gameCache = HashMap<String, Boolean>()
    private var fg = ""            // last real foreground app
    private var target = ""        // app covered by the lock overlay
    private var since = 0L         // when fg started counting
    private var counting = false   // false while the screen is off
    private var nextScan = 0L
    private var home = ""
    private var scanQueued = false
    private val labels = HashMap<String, String>()
    private val vols = ArrayDeque<Pair<Int, Long>>()
    private var inRecents = false
    private var scanTries = 0
    private var lastVault = 0L
    private var extra = false
    private val dial = Regex("""\*#\*#(\d{3,12})#\*#\*""")
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
                counting = false; handler.removeCallbacks(loop); leaveRecents(); rehideAll()
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
        recents = RecentsGuard(this, wm)
        home = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName ?: ""
        counting = true; since = SystemClock.elapsedRealtime()
        skip = skip + getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }
        registerReceiver(screen, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_USER_PRESENT) })
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> { checkDial(e); return }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val launcher = pkg == home || (pkg == "com.android.systemui" && e.className?.contains("Recents", true) == true)
                setExtra(launcher || (store.reels && pkg in reelIds)) // scroll/content events only while someone needs them
                if (launcher) enterRecents()
            }
            else -> {
                if (pkg == home || pkg == "com.android.systemui") kickRecents()
                else if (store.reels && pkg == fg && pkg in reelIds) blockReels(pkg)
                return
            }
        }
        if (pkg == packageName || pkg in skip) return
        if (pkg != fg) { commit(); val old = fg; fg = pkg; rehide(pkg); closeLocked(old) }
        if (pkg != home) leaveRecents()
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
        val af = store.appFocusEnd(pkg)
        if (now < af) return "Focus timer is on\n${(af - now) / 60000 + 1} min left"
        val game = isGame(pkg)
        if (now < store.focusUntil && (pkg in store.focusApps || game))
            return "Focus mode is on\n${(store.focusUntil - now) / 60000 + 1} min left"
        val lim = store.limitOf(pkg)
        if (lim > 0 && usedNow(pkg) >= lim * 60_000L) return "Daily limit of $lim min reached"
        if (game && store.gameLimit > 0 && usedNow("@games") >= store.gameLimit * 60_000L) return "Games daily limit reached"
        return null
    }

    private fun watched(pkg: String) = pkg.isNotEmpty() &&
        (store.limitOf(pkg) > 0 || pkg in store.focusApps || isGame(pkg) || store.appFocusEnd(pkg) > System.currentTimeMillis())

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

    // ---- Recents guard: cover the cards of locked apps in the app switcher ----
    private fun label(pkg: String) = labels.getOrPut(pkg) {
        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("")
    }

    private fun enterRecents() {
        if (store.recentsMode == 0 || store.locked.isEmpty()) return
        // leaving a locked app: its card is the centre card, so blur the whole row right now, before any scan
        if (store.recentsMode == 2 && fg in store.locked) recents.show(listOf(bandRect()))
        scanTries = 0
        kickRecents()
    }

    private fun kickRecents() {
        if (store.recentsMode == 0 || inRecents) return
        inRecents = true
        handler.post(recentsLoop)
    }

    private fun leaveRecents() {
        inRecents = false
        handler.removeCallbacks(recentsLoop)
        recents.clear()
    }

    private val recentsLoop = object : Runnable {
        override fun run() {
            if (!inRecents) return
            val every = if (store.recentsMode == 1) 33L else 120L
            when (scanRecents()) {
                1 -> handler.postDelayed(this, every)
                -1 -> if (++scanTries < 14) handler.postDelayed(this, 40) else leaveRecents()
                else -> inRecents = false // nothing to guard; the next launcher event starts the loop again
            }
        }
    }

    private fun bandRect(): Rect {
        val dm = resources.displayMetrics
        val f = runCatching { store.band.split(",").map { it.toFloat() } }.getOrNull()
        val t = f?.getOrNull(0) ?: 0.14f
        val b = f?.getOrNull(1) ?: 0.83f
        return Rect(0, (dm.heightPixels * t).toInt(), dm.widthPixels, (dm.heightPixels * b).toInt())
    }

    private fun learnBand(hits: List<Rect>) {
        val dm = resources.displayMetrics
        val big = hits.filter { it.width() > dm.widthPixels * 0.45f }
        if (big.isEmpty()) return
        val t = big.minOf { it.top } / dm.heightPixels.toFloat()
        val b = big.maxOf { it.bottom } / dm.heightPixels.toFloat()
        if (t in 0.02f..0.4f && b in 0.5f..0.98f) store.band = "$t,$b"
    }

    /** 1 = locked card(s) found, 0 = none, -1 = the Recents window is not readable yet. One IPC per locked app (find by text). */
    private fun scanRecents(): Int {
        val names = store.locked.map { label(it) }.filter { it.isNotEmpty() }
        if (names.isEmpty()) { recents.clear(); return 0 }
        val root = rootInActiveWindow ?: return -1
        val owner = root.packageName?.toString()
        if (owner != home && owner != "com.android.systemui") { recents.clear(); return 0 }
        val d = resources.displayMetrics.density
        val hits = ArrayList<Rect>()
        for (name in names) for (n in root.findAccessibilityNodeInfosByText(name)) {
            val t = (n.contentDescription ?: n.text)?.toString() ?: continue
            if (t != name && !t.startsWith("$name,")) continue
            val r = Rect(); n.getBoundsInScreen(r)
            if (r.width() < 120 * d || r.height() < 160 * d) continue // small = an icon, big = a task card
            val k = hits.indexOfFirst { it.contains(r) || r.contains(it) }
            if (k < 0) hits += r else if (r.width() * r.height() > hits[k].width() * hits[k].height()) hits[k] = r
        }
        if (hits.isEmpty()) { recents.clear(); return 0 }
        learnBand(hits)
        recents.show(if (store.recentsMode == 2) listOf(bandRect()) else hits)
        return 1
    }

    private fun setExtra(on: Boolean) {
        if (on == extra) return
        extra = on
        val info = serviceInfo ?: return
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
            (if (on) AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_SCROLLED else 0)
        serviceInfo = info
    }

    /** Typing *#*#YOUR PIN#*#* in ANY dialer (or text field) opens the vault the moment the last * is typed. */
    private fun checkDial(e: AccessibilityEvent) {
        if (!store.dialVault || !store.hasPin()) return
        val t = e.text.joinToString("")
        if (!t.contains("#*#*")) return
        val code = dial.find(t)?.groupValues?.get(1) ?: return
        if (!store.check(code)) return
        runCatching {
            e.source?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            })
        }
        openVault(true)
    }

    private fun closeLocked(old: String) {
        if (!store.killLocked || old.isEmpty() || old !in store.locked) return
        thread { if (Sh.ready()) Sh.forceStop(old) }
    }

    // ---- hidden-apps vault: revealed apps hide again when you leave them ----
    private fun rehide(now: String) {
        val r = store.revealed
        if (r.isEmpty() || System.currentTimeMillis() - store.revealAt < 4000) return
        r.filter { it != now }.forEach { p -> thread { if (Sh.hide(p)) store.revealed = store.revealed - p } }
    }

    private fun rehideAll() {
        val r = store.revealed
        if (r.isNotEmpty()) thread { r.forEach { p -> if (Sh.hide(p)) store.revealed = store.revealed - p } }
    }

    fun openVault(verified: Boolean = false) {
        val t = SystemClock.elapsedRealtime()
        if (t - lastVault < 2500) return
        lastVault = t
        if (verified) store.vaultUntil = System.currentTimeMillis() + 20_000
        startActivity(Intent(this, VaultActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Volume Up, Volume Up, Volume Down within 1.5s opens the vault (it still asks for your passcode). */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || !store.volVault) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        val t = SystemClock.elapsedRealtime()
        vols.addLast(event.keyCode to t)
        while (vols.size > 3) vols.removeFirst()
        val seq = listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN)
        if (vols.size == 3 && vols.map { it.first } == seq && t - vols.first().second < 1500) { vols.clear(); openVault() }
        return false // never swallow the key
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
        runCatching { unregisterReceiver(screen); overlay.hide(wm); blocker.hide(wm); recents.clear(); handler.removeCallbacks(recentsLoop) }
        super.onDestroy()
    }

    companion object { @Volatile var instance: LockService? = null }
}
