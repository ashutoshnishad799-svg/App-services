package com.ashuapps.lock

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Color as AColor
import android.media.ExifInterface
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.res.painterResource
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private var tick by mutableIntStateOf(0)
    private var appLocked by mutableStateOf(false)
    private lateinit var store: LockStore

    override fun onResume() { super.onResume(); tick++ } // re-check service / Shizuku state when returning
    override fun onStop() { super.onStop(); if (store.lockSelf && store.hasPin()) appLocked = true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = LockStore(this)
        if (store.secureMain) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        appLocked = store.lockSelf && store.hasPin()
        setContent { App(store, tick, appLocked, { askFinger(it) }) { appLocked = false } }
    }
}

internal val LocalPal = staticCompositionLocalOf { Themes.all[0] }
private class NavItem(val icon: Int, val label: String)
private val NAV = listOf(
    NavItem(R.drawable.ic_lock, "Lock"), NavItem(R.drawable.ic_hide, "Hide"), NavItem(R.drawable.ic_key, "Passcode"),
    NavItem(R.drawable.ic_target, "Focus"), NavItem(R.drawable.ic_timer, "Timers"), NavItem(R.drawable.ic_reels, "Reels"),
    NavItem(R.drawable.ic_game, "Games"), NavItem(R.drawable.ic_camera, "Intruder"), NavItem(R.drawable.ic_theme, "Themes"),
    NavItem(R.drawable.ic_info, "About"))

private fun bgBrush(p: Pal): Brush =
    if (p.bg.size > 1) Brush.linearGradient(p.bg.map { Color(it) }) else SolidColor(Color(p.bg[0]))

private fun cardBrush(p: Pal): Brush =
    if (p.glass) Brush.linearGradient(listOf(Color(0x33FFFFFF), Color(0x14FFFFFF))) else SolidColor(Color(p.card))

private fun scheme(p: Pal): ColorScheme {
    val acc = Color(p.accent); val on = Color(p.onAccent); val txt = Color(p.text); val solid = Color(p.solid)
    val sub = Color(p.sub); val line = Color(p.border); val soft = acc.copy(alpha = 0.22f)
    return if (p.lightBg) lightColorScheme(
        primary = acc, onPrimary = on, surface = solid, onSurface = txt, surfaceContainerHigh = solid,
        secondaryContainer = soft, onSecondaryContainer = txt, outline = line, onSurfaceVariant = sub)
    else darkColorScheme(
        primary = acc, onPrimary = on, surface = solid, onSurface = txt, surfaceContainerHigh = solid,
        secondaryContainer = soft, onSecondaryContainer = txt, outline = line, onSurfaceVariant = sub)
}

/** Theme wrapper shared by the app and the vault: palette, status/nav bar icons, glass orbs. */
@Composable
internal fun Themed(pal: Pal, content: @Composable () -> Unit) {
    val act = LocalContext.current as ComponentActivity
    SideEffect { // status + navigation bar icons follow the selected theme
        val bar = if (pal.lightBg) SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT)
        else SystemBarStyle.dark(AColor.TRANSPARENT)
        act.enableEdgeToEdge(statusBarStyle = bar, navigationBarStyle = bar)
    }
    CompositionLocalProvider(LocalPal provides pal, LocalContentColor provides Color(pal.text)) {
        MaterialTheme(colorScheme = scheme(pal)) {
            Box(Modifier.fillMaxSize().background(bgBrush(pal))) {
                if (pal.glass) { // colour orbs give the frosted cards something to frost
                    Orb(Color(0x66FF5FA2), Modifier.align(Alignment.TopEnd).offset(80.dp, (-60).dp))
                    Orb(Color(0x6600D4FF), Modifier.align(Alignment.CenterStart).offset((-110).dp, 140.dp))
                }
                content()
            }
        }
    }
}

private fun loadApps(ctx: Context): List<Pair<String, String>> {
    val pm = ctx.packageManager
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .filter { it.first != ctx.packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
}

/** Full-screen layer above the pages that swallows touches (gate / setup screens). */
@Composable
private fun Cover(content: @Composable () -> Unit) {
    val pal = LocalPal.current
    Box(Modifier.fillMaxSize().background(bgBrush(pal)).pointerInput(Unit) { detectTapGestures { } }) { content() }
}

@Composable
private fun App(store: LockStore, tick: Int, appLocked: Boolean, askFinger: (() -> Unit) -> Unit, onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    var themeId by remember { mutableStateOf(store.theme) }
    var setup by remember { mutableStateOf<Int?>(null) }          // lock type being set up (full-screen flow)
    var gate by remember { mutableStateOf<(() -> Unit)?>(null) }   // action waiting for the passcode
    var apps by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var refresh by remember { mutableIntStateOf(0) }
    val pager = rememberPagerState { NAV.size }
    BackHandler(enabled = setup != null || gate != null) { setup = null; gate = null }
    LaunchedEffect(refresh, tick) { // app list is loaded off the main thread and refreshed on hide / unhide / install
        if (refresh > 0) delay(250)
        val fresh = withContext(Dispatchers.IO) { loadApps(ctx) }
        if (fresh != apps) apps = fresh
    }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { refresh++ } }
        ctx.registerReceiver(r, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        })
        onDispose { ctx.unregisterReceiver(r) }
    }
    val askPin: (() -> Unit) -> Unit = { action -> if (store.hasPin()) gate = action else action() }
    Themed(Themes.of(themeId)) {
        Shell(store, apps, tick, pager, themeId, askPin, { refresh++ }, { setup = it }) { themeId = it; store.theme = it }
        val g = gate
        val s = setup
        if (appLocked) Cover { GateScreen(store, "Ashu AppLock", askFinger, null) { onUnlocked() } }
        else if (g != null) Cover { GateScreen(store, "Enter passcode", askFinger, { gate = null }) { gate = null; g() } }
        else if (s != null) Cover { PasscodeSetup(s, store) { setup = null } }
    }
}

@Composable
private fun Orb(c: Color, m: Modifier) {
    Box(m.size(320.dp).background(Brush.radialGradient(listOf(c, Color.Transparent)), CircleShape))
}

/** Swipeable pages + a glass bottom bar that scrolls sideways and follows the pager. */
@Composable
private fun Shell(
    store: LockStore, apps: List<Pair<String, String>>, tick: Int, pager: PagerState, themeId: Int,
    askPin: (() -> Unit) -> Unit, onChanged: () -> Unit, openSetup: (Int) -> Unit, setTheme: (Int) -> Unit
) {
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    val bar = rememberLazyListState()
    LaunchedEffect(pager.currentPage) { bar.animateScrollToItem((pager.currentPage - 1).coerceAtLeast(0)) }
    val shape = RoundedCornerShape(30.dp)
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { i ->
            when (i) {
                0 -> LockPage(store, apps, tick)
                1 -> HidePage(store, apps, tick, askPin, onChanged)
                2 -> PasscodePage(store, openSetup)
                3 -> FocusPage(store, apps)
                4 -> TimersPage(store, apps)
                5 -> ReelsPage(store)
                6 -> GamesPage(store, apps)
                7 -> IntruderPage(store, tick, askPin)
                8 -> ThemesPage(themeId, setTheme)
                else -> AboutPage()
            }
        }
        LazyRow(
            state = bar,
            modifier = Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth().clip(shape).background(cardBrush(pal), shape).border(1.dp, Color(pal.border), shape),
            contentPadding = PaddingValues(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(NAV) { i, t ->
                val sel = pager.currentPage == i
                val tabBg by animateColorAsState(
                    if (sel) Color(pal.accent).copy(alpha = 0.28f) else Color.Transparent, label = "tab")
                Column(
                    Modifier.width(76.dp).clip(RoundedCornerShape(24.dp)).background(tabBg)
                        .clickable { scope.launch { pager.animateScrollToPage(i) } }.padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(painterResource(t.icon), t.label, Modifier.size(22.dp), tint = Color(if (sel) pal.text else pal.sub))
                    Text(t.label, fontSize = 11.sp, color = Color(if (sel) pal.text else pal.sub),
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

// ---------- shared building blocks ----------

@Composable
private fun PageList(title: String, sub: String, content: LazyListScope.() -> Unit) {
    val pal = LocalPal.current
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column(Modifier.padding(bottom = 4.dp)) {
                Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text(sub, fontSize = 13.sp, color = Color(pal.sub))
            }
        }
        content()
    }
}

@Composable
internal fun Panel(pad: Int = 16, content: @Composable ColumnScope.() -> Unit) {
    val p = LocalPal.current
    val s = RoundedCornerShape(22.dp)
    Column(
        Modifier.fillMaxWidth().background(cardBrush(p), s).border(1.dp, Color(p.border), s).padding(pad.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}

@Composable
private fun Toggle(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, fontSize = 12.sp, color = Color(LocalPal.current.sub))
        }
        Switch(checked, onChange)
    }
}

/** One glass row per installed app, with a page-specific control on the right. */
private fun LazyListScope.appRows(apps: List<Pair<String, String>>, trailing: @Composable (String, String) -> Unit) {
    items(apps, key = { it.first }) { (pkg, name) ->
        val icon = rememberIcon(pkg)
        Panel(pad = 12) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) Image(icon, null, Modifier.size(40.dp)) else Spacer(Modifier.size(40.dp))
                Text(name, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1)
                trailing(pkg, name)
            }
        }
    }
}

// ---------- pages ----------

@Composable
private fun LockPage(store: LockStore, apps: List<Pair<String, String>>, tick: Int) {
    val ctx = LocalContext.current
    var locked by remember { mutableStateOf(store.locked) }
    val active = tick >= 0 && LockService.instance != null
    PageList("App Lock", "Pick the apps you want to protect.") {
        if (!active) item {
            Panel {
                Text("The accessibility service is off. Turn it on so the lock appears the moment an app opens.")
                Button({ ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open Accessibility") }
            }
        }
        if (!store.hasPin()) item { Panel { Text("No passcode yet. Set one in the Passcode tab first.") } }
        appRows(apps) { pkg, _ ->
            Switch(pkg in locked, { on ->
                locked = if (on) locked + pkg else locked - pkg
                store.locked = locked
            })
        }
    }
}

@Composable
private fun HidePage(
    store: LockStore, apps: List<Pair<String, String>>, tick: Int,
    askPin: (() -> Unit) -> Unit, onChanged: () -> Unit
) {
    val ctx = LocalContext.current
    val sub = Color(LocalPal.current.sub)
    var hidden by remember { mutableStateOf(store.hidden) }
    var vol by remember { mutableStateOf(store.volVault) }
    var dial by remember { mutableStateOf(store.dialVault) }
    val ok = tick >= 0 && Sh.ready()
    // an app only leaves the "hidden" list once it is verified visible again, so it can never get lost
    fun unhide(h: String) = thread {
        if (Sh.unhide(h.substringBefore('|'))) { hidden = hidden - h; store.hidden = hidden; onChanged() }
        else toast(ctx, "Could not unhide: ${Sh.last.take(140)}")
    }
    fun hide(pkg: String, name: String) = thread {
        saveIcon(ctx, pkg)
        if (Sh.hide(pkg)) { hidden = hidden + "$pkg|$name"; store.hidden = hidden; onChanged() }
        else toast(ctx, "Could not hide: ${Sh.last.take(140)}")
    }
    PageList("Hide Apps", "Remove apps from the launcher with Shizuku and open them from the vault.") {
        item {
            Panel {
                Text(if (ok) "Shizuku is connected." else "Start Shizuku and grant access to hide apps.")
                if (!ok) Button({ Sh.ask() }) { Text("Grant Shizuku access") }
            }
        }
        item {
            Panel {
                Text("Hidden apps vault", fontWeight = FontWeight.Bold)
                Text("Open hidden apps after your passcode. They hide again when you leave them.", fontSize = 12.sp, color = sub)
                Button({ ctx.startActivity(Intent(ctx, VaultActivity::class.java)) }) { Text("Open vault") }
                Toggle("Dial code", "Type *#*#YOUR PIN#*#* in any phone app. Works with PIN or pattern lock.", dial) {
                    dial = it; store.dialVault = it
                }
                Toggle("Volume shortcut", "Press Volume Up, Volume Up, Volume Down quickly", vol) { vol = it; store.volVault = it }
            }
        }
        if (hidden.isNotEmpty()) item { Text("Hidden apps", fontWeight = FontWeight.Bold) }
        items(hidden.toList()) { h ->
            Panel(pad = 12) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(h.substringAfter('|'), Modifier.weight(1f))
                    TextButton({ askPin { unhide(h) } }, enabled = ok) { Text("Unhide") }
                }
            }
        }
        item { Text("Visible apps", fontWeight = FontWeight.Bold) }
        appRows(apps.filter { a -> hidden.none { it.startsWith(a.first + "|") } }) { pkg, name ->
            TextButton({ hide(pkg, name) }, enabled = ok) { Text("Hide") }
        }
    }
}

@Composable
private fun PasscodePage(store: LockStore, openSetup: (Int) -> Unit) {
    val pal = LocalPal.current
    val act = LocalContext.current as ComponentActivity
    val has = store.hasPin()
    val type = store.lockType
    var close by remember { mutableStateOf(store.relockOnClose) }
    var off by remember { mutableStateOf(store.relockOnScreenOff) }
    var bio by remember { mutableStateOf(store.autoBio) }
    var self by remember { mutableStateOf(store.lockSelf) }
    var kill by remember { mutableStateOf(store.killLocked) }
    var mode by remember { mutableStateOf(store.recentsMode) }
    var secure by remember { mutableStateOf(store.secureMain) }
    PageList("Passcode", "Choose how locked apps are unlocked.") {
        item {
            Panel {
                Text("Lock type", fontWeight = FontWeight.Bold)
                listOf("PIN" to "4 to 6 digits", "Pattern" to "Connect at least 4 dots", "Password" to "Letters, numbers and symbols")
                    .forEachIndexed { i, (n, d) ->
                        val active = has && type == i
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(n, fontWeight = FontWeight.Medium)
                                Text(d, fontSize = 12.sp, color = Color(pal.sub))
                            }
                            if (active) Icon(painterResource(R.drawable.ic_check), "Active", Modifier.size(20.dp), tint = Color(pal.accent))
                            TextButton({ openSetup(i) }) { Text(if (active) "Change" else "Set up") }
                        }
                    }
            }
        }
        item {
            Panel {
                Toggle("Relock when the app closes", null, close) { close = it; store.relockOnClose = it }
                Toggle("Relock after the screen turns off", null, off) { off = it; store.relockOnScreenOff = it }
                Toggle("Fingerprint unlock", "Shows the fingerprint prompt as soon as the lock appears", bio) { bio = it; store.autoBio = it }
                Toggle("Lock this app too", "Ask for the passcode whenever Ashu AppLock opens", self) { self = it; store.lockSelf = it }
            }
        }
        item {
            Panel {
                Text("Recents privacy", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Off", "Smart", "Strict").forEachIndexed { i, n ->
                        FilterChip(mode == i, { mode = i; store.recentsMode = i }, { Text(n) })
                    }
                }
                Text(
                    listOf("Locked apps stay visible in Recents.",
                        "Covers each locked app card. Can lag a little while you swipe.",
                        "Blurs the whole card row the moment a locked app is in Recents. Nothing leaks.")[mode],
                    fontSize = 12.sp, color = Color(pal.sub))
                Toggle("Close locked apps when you leave them", "Removes them from Recents completely. Needs Shizuku.", kill) {
                    kill = it; store.killLocked = it
                }
                Toggle("Hide this app in Recents", "Also blocks screenshots of this app", secure) {
                    secure = it; store.secureMain = it
                    if (it) act.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                    else act.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }
}

@Composable
private fun FocusPage(store: LockStore, apps: List<Pair<String, String>>) {
    val sub = Color(LocalPal.current.sub)
    val now = rememberNow()
    var until by remember { mutableStateOf(store.focusUntil) }
    var total by remember { mutableStateOf(store.focusTotal) }
    var sel by remember { mutableStateOf(store.focusApps) }
    var mins by remember { mutableStateOf(25) }
    var own by remember { mutableStateOf(store.appFocus) }
    var pick by remember { mutableStateOf<Pair<String, String>?>(null) } // app whose own timer is being set
    val on = until > now
    pick?.let { (pkg, name) ->
        var m by remember(pkg) { mutableStateOf(15) }
        val end = store.appFocusEnd(pkg)
        AlertDialog(
            onDismissRequest = { pick = null },
            title = { Text(name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (end > now) "Blocked for ${fmtClock(end - now)} more" else "Block this app for", fontSize = 13.sp)
                    DurationPicker(listOf(5, 10, 15, 20, 30, 45, 60, 90, 120), m, 3) { m = it }
                }
            },
            confirmButton = {
                Button({ store.setAppFocus(pkg, System.currentTimeMillis() + m * 60_000L); own = store.appFocus; pick = null }) {
                    Text("Start ${fmtMin(m)}")
                }
            },
            dismissButton = {
                if (end > now) TextButton({ store.setAppFocus(pkg, 0); own = store.appFocus; pick = null }) { Text("Clear") }
                else TextButton({ pick = null }) { Text("Cancel") }
            })
    }
    PageList("Focus Mode", "Block apps for a set time. Games are blocked too.") {
        item {
            Panel {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ClockRing(
                        if (on && total > 0) (until - now).toFloat() / total else 1f,
                        if (on) fmtClock(until - now) else fmtClock(mins * 60_000L),
                        if (on) "ends ${fmtTime(until)}" else "session length")
                }
                if (on) Button({ until = 0; store.focusUntil = 0 }, Modifier.fillMaxWidth()) { Text("Stop focus") }
                else {
                    DurationPicker(listOf(5, 10, 15, 25, 30, 45, 60, 90), mins) { mins = it }
                    Button({
                        val t = mins * 60_000L
                        until = System.currentTimeMillis() + t; total = t
                        store.focusUntil = until; store.focusTotal = t
                    }, Modifier.fillMaxWidth()) { Text("Start focus") }
                }
            }
        }
        item { Text("Tick the apps this session blocks, or tap an app's timer to block just that app on its own clock.", fontSize = 12.sp, color = sub) }
        appRows(apps) { pkg, name ->
            val end = own.firstOrNull { it.startsWith("$pkg|") }?.substringAfter('|')?.toLongOrNull() ?: 0L
            TextButton({ pick = pkg to name }) { Text(if (end > now) fmtClock(end - now) else "Timer") }
            Checkbox(pkg in sel, { sel = if (it) sel + pkg else sel - pkg; store.focusApps = sel })
        }
    }
}

@Composable
private fun TimersPage(store: LockStore, apps: List<Pair<String, String>>) {
    var limits by remember { mutableStateOf(store.limits) }
    var pick by remember { mutableStateOf<Pair<String, String>?>(null) }
    pick?.let { (pkg, name) ->
        AlertDialog(
            onDismissRequest = { pick = null },
            confirmButton = { TextButton({ pick = null }) { Text("Done") } },
            title = { Text(name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val cur = remember(limits) { store.limitOf(pkg) }
                    Text("Daily limit", fontSize = 13.sp)
                    DurationPicker(listOf(0, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180), cur, 3) { store.setLimit(pkg, it); limits = store.limits }
                }
            })
    }
    PageList("App Timers", "Daily time limit per app. Pick a preset or type your own minutes.") {
        appRows(apps) { pkg, name ->
            val m = limits.firstOrNull { it.startsWith("$pkg|") }?.substringAfter('|')?.toIntOrNull() ?: 0
            TextButton({ pick = pkg to name }) { Text(fmtMin(m)) }
        }
    }
}

@Composable
private fun ReelsPage(store: LockStore) {
    val sub = Color(LocalPal.current.sub)
    var on by remember { mutableStateOf(store.reels) }
    var paused by remember { mutableStateOf(store.pausedUntil) }
    val now = System.currentTimeMillis()
    val isPaused = paused > now
    PageList("Reels & Shorts", "Keep yourself out of endless short videos.") {
        item {
            Panel {
                Toggle("Block Reels & Shorts", "Instagram Reels and YouTube Shorts send you back to the feed", on) { on = it; store.reels = it }
            }
        }
        item {
            Panel {
                Text(if (isPaused) "Paused · ${(paused - now) / 60000 + 1} min left" else "Pause blocking", fontWeight = FontWeight.Bold)
                Text("Pauses the Reels blocker, Focus Mode and app timers.", fontSize = 12.sp, color = sub)
                if (isPaused) Button({ paused = 0; store.pausedUntil = 0 }) { Text("Resume now") }
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 15, 30).forEach { m ->
                        OutlinedButton({ paused = System.currentTimeMillis() + m * 60_000L; store.pausedUntil = paused }) { Text("${m}m") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GamesPage(store: LockStore, apps: List<Pair<String, String>>) {
    val ctx = LocalContext.current
    var games by remember { mutableStateOf(store.games) }
    var limit by remember { mutableStateOf(store.gameLimit) }
    PageList("Games", "One shared daily limit for all games. Games are also blocked in Focus Mode.") {
        item {
            Panel {
                Text("Daily games limit", fontWeight = FontWeight.Bold)
                DurationPicker(listOf(0, 30, 60, 90, 120, 180), limit) { limit = it; store.gameLimit = it }
            }
        }
        item { Text("Mark your games. Play Store games are detected automatically.", fontSize = 12.sp, color = Color(LocalPal.current.sub)) }
        appRows(apps) { pkg, _ ->
            val auto = remember(pkg) { autoGame(ctx.packageManager, pkg) }
            Checkbox(auto || pkg in games, { games = if (it) games + pkg else games - pkg; store.games = games }, enabled = !auto)
        }
    }
}

@Composable
private fun IntruderPage(store: LockStore, tick: Int, askPin: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val sub = Color(LocalPal.current.sub)
    var selfie by remember { mutableStateOf(store.selfie) }
    var crash by remember { mutableStateOf(store.fakeCrash) }
    var version by remember { mutableStateOf(0) }
    var shown by remember { mutableStateOf(!store.hasPin()) } // photos stay hidden until the passcode is entered
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        selfie = ok; store.selfie = ok
    }
    val pics = remember(tick, version) { File(ctx.filesDir, "intruders").listFiles()?.sortedByDescending { it.name }.orEmpty() }
    val err = remember(tick, version) { store.selfieErr }
    PageList("Intruder", "Catch snoopers and throw them off.") {
        item {
            Panel {
                Toggle("Intruder selfie", "Takes a front-camera photo after 3 wrong attempts", selfie) {
                    if (it) cam.launch(Manifest.permission.CAMERA) else { selfie = false; store.selfie = false }
                }
                Toggle("Fake crash screen", "Shows an \"app keeps stopping\" dialog. Long-press its title to reach the real lock.", crash) {
                    crash = it; store.fakeCrash = it
                }
                OutlinedButton({ ctx.startActivity(Intent(ctx, SelfieActivity::class.java)); version++ }, Modifier.fillMaxWidth()) {
                    Text("Test selfie now")
                }
                if (err.isNotEmpty()) Text("Last camera error: $err", fontSize = 12.sp, color = sub)
            }
        }
        if (pics.isNotEmpty() && !shown) item {
            Panel {
                Text("${pics.size} photo(s) captured. Enter your passcode to view them.")
                Button({ askPin { shown = true } }) { Text("View photos") }
            }
        }
        if (pics.isNotEmpty() && shown) {
            item {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Photos (${pics.size})", fontWeight = FontWeight.Bold)
                    TextButton({ pics.forEach { it.delete() }; version++ }) { Text("Delete all") }
                }
            }
            items(pics, key = { it.name }) { f ->
                Panel(pad = 10) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        remember(f.name) { thumb(f) }?.let { Image(it, null, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp))) }
                        Text(
                            android.text.format.DateFormat.format("dd MMM, hh:mm a", f.nameWithoutExtension.toLongOrNull() ?: 0L).toString(),
                            Modifier.weight(1f).padding(horizontal = 12.dp))
                        TextButton({ f.delete(); version++ }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemesPage(current: Int, onPick: (Int) -> Unit) {
    PageList("Themes", "Applies to the whole app and to the lock screen.") {
        items(Themes.all.indices.chunked(2)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { i -> ThemeTile(Themes.all[i], i == current, Modifier.weight(1f)) { onPick(i) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ThemeTile(p: Pal, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val s = RoundedCornerShape(20.dp)
    val acc = Color(LocalPal.current.accent)
    Box(
        modifier.height(104.dp).clip(s).background(bgBrush(p), s)
            .border(if (selected) 3.dp else 1.dp, if (selected) acc else Color(p.border), s)
            .clickable(onClick = onClick).padding(12.dp)
    ) {
        Box(Modifier.align(Alignment.TopEnd).size(width = 46.dp, height = 28.dp)
            .background(Color(p.card), RoundedCornerShape(10.dp)).border(1.dp, Color(p.border), RoundedCornerShape(10.dp)))
        Text(p.name, Modifier.align(Alignment.BottomStart), color = Color(p.text), fontWeight = FontWeight.Bold)
        if (selected) Icon(painterResource(R.drawable.ic_check), null, Modifier.align(Alignment.TopStart).size(18.dp), tint = Color(p.text))
    }
}

@Composable
private fun AboutPage() {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val v = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    PageList("About", "Free and open source. Please keep the credits.") {
        item {
            Panel {
                Text("Ashu AppLock  v$v", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Made by Ashutosh. MIT licensed: keep the license and credit if you reuse the code.",
                    fontSize = 13.sp, color = Color(LocalPal.current.sub))
                listOf(
                    "Telegram channel" to "https://t.me/ashuapps_07x",
                    "Message me on Telegram" to "https://t.me/ashutosh_07x",
                    "GitHub profile" to "https://github.com/ashutoshnishad799-svg",
                    "Source code" to "https://github.com/ashutoshnishad799-svg/App-services"
                ).forEach { (label, url) ->
                    OutlinedButton({ uri.openUri(url) }, Modifier.fillMaxWidth()) { Text(label) }
                }
            }
        }
    }
}

/** Small thumbnail of an intruder photo, rotated upright using its EXIF tag. */
private fun thumb(f: File) = runCatching {
    val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 8 })
    val deg = when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true).asImageBitmap()
}.getOrNull()

/** Keeps the app icon so the vault can show it after the app is hidden (hidden apps vanish from PackageManager). */
private fun saveIcon(ctx: Context, pkg: String) = runCatching {
    File(ctx.filesDir, "icons").apply { mkdirs() }.resolve("$pkg.png").outputStream().use {
        ctx.packageManager.getApplicationIcon(pkg).toBitmap(128, 128).compress(Bitmap.CompressFormat.PNG, 100, it)
    }
}
