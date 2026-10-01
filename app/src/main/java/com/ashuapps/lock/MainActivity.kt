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
import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.res.painterResource
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)
    override fun onResume() { super.onResume(); tick++ } // re-check service / Shizuku state when returning

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = LockStore(this)
        if (store.secureMain) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val apps = packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        setContent { App(store, apps, tick) }
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

@Composable
private fun App(store: LockStore, apps: List<Pair<String, String>>, tick: Int) {
    var themeId by remember { mutableStateOf(store.theme) }
    var setup by remember { mutableStateOf<Int?>(null) } // lock type being set up (full-screen flow)
    val pager = rememberPagerState { NAV.size }
    BackHandler(enabled = setup != null) { setup = null }
    Themed(Themes.of(themeId)) {
        val t = setup
        if (t == null) Shell(store, apps, tick, pager, themeId, { setup = it }) { themeId = it; store.theme = it }
        else PasscodeSetup(t, store) { setup = null }
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
    openSetup: (Int) -> Unit, setTheme: (Int) -> Unit
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
                1 -> HidePage(store, apps, tick)
                2 -> PasscodePage(store, openSetup)
                3 -> FocusPage(store, apps)
                4 -> TimersPage(store, apps)
                5 -> ReelsPage(store)
                6 -> GamesPage(store, apps)
                7 -> IntruderPage(store, tick)
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
        val ctx = LocalContext.current
        val icon = remember(pkg) { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }
        Panel(pad = 12) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(icon, null, Modifier.size(40.dp))
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
private fun HidePage(store: LockStore, apps: List<Pair<String, String>>, tick: Int) {
    val ctx = LocalContext.current
    val sub = Color(LocalPal.current.sub)
    var hidden by remember { mutableStateOf(store.hidden) }
    var vol by remember { mutableStateOf(store.volVault) }
    val ok = tick >= 0 && Sh.ready()
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
                Toggle("Volume shortcut", "Press Volume Up, Volume Up, Volume Down quickly to open the vault", vol) {
                    vol = it; store.volVault = it
                }
                Text("You can also dial *#*#2748#*#* in your phone app.", fontSize = 12.sp, color = sub)
            }
        }
        if (hidden.isNotEmpty()) item { Text("Hidden apps", fontWeight = FontWeight.Bold) }
        items(hidden.toList()) { h ->
            val (pkg, label) = h.split("|", limit = 2)
            Panel(pad = 12) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f))
                    TextButton({ thread { if (Sh.unhide(pkg)) { hidden = hidden - h; store.hidden = hidden } } }, enabled = ok) { Text("Unhide") }
                }
            }
        }
        item { Text("Visible apps", fontWeight = FontWeight.Bold) }
        appRows(apps.filter { a -> hidden.none { it.startsWith(a.first + "|") } }) { pkg, name ->
            TextButton({
                thread { saveIcon(ctx, pkg); if (Sh.hide(pkg)) { hidden = hidden + "$pkg|$name"; store.hidden = hidden } }
            }, enabled = ok) { Text("Hide") }
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
    var rec by remember { mutableStateOf(store.recentsBlur) }
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
            }
        }
        item {
            Panel {
                Toggle("Blur locked apps in Recents", "Covers their cards in the app switcher (best effort)", rec) { rec = it; store.recentsBlur = it }
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
    var until by remember { mutableStateOf(store.focusUntil) }
    var sel by remember { mutableStateOf(store.focusApps) }
    val now = System.currentTimeMillis()
    val on = until > now
    PageList("Focus Mode", "Block distracting apps (and every game) for a set time.") {
        item {
            Panel {
                Text(if (on) "Focus is on · ${(until - now) / 60000 + 1} min left" else "Start a focus session",
                    fontWeight = FontWeight.Bold)
                if (on) Button({ until = 0; store.focusUntil = 0 }) { Text("Stop") }
                else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(25, 45, 60, 90).forEach { m ->
                        Button({ until = System.currentTimeMillis() + m * 60_000L; store.focusUntil = until }) { Text("${m}m") }
                    }
                }
            }
        }
        item { Text("Apps blocked during focus", fontWeight = FontWeight.Bold) }
        appRows(apps) { pkg, _ ->
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
                    Text("Daily limit")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0, 15, 30, 60, 120).forEach { m ->
                            FilterChip(cur == m, { store.setLimit(pkg, m); limits = store.limits }, { Text(if (m == 0) "Off" else "${m}m") })
                        }
                    }
                }
            })
    }
    PageList("App Timers", "Set a daily time limit for any app.") {
        appRows(apps) { pkg, name ->
            val m = limits.firstOrNull { it.startsWith("$pkg|") }?.substringAfter('|')
            TextButton({ pick = pkg to name }) { Text(if (m == null) "Off" else "${m}m") }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 30, 60, 120).forEach { m ->
                        FilterChip(limit == m, { limit = m; store.gameLimit = m }, { Text(if (m == 0) "Off" else "${m}m") })
                    }
                }
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
private fun IntruderPage(store: LockStore, tick: Int) {
    val ctx = LocalContext.current
    var selfie by remember { mutableStateOf(store.selfie) }
    var crash by remember { mutableStateOf(store.fakeCrash) }
    var version by remember { mutableStateOf(0) }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        selfie = ok; store.selfie = ok
    }
    val pics = remember(tick, version) { File(ctx.filesDir, "intruders").listFiles()?.sortedByDescending { it.name }.orEmpty() }
    PageList("Intruder", "Catch snoopers and throw them off.") {
        item {
            Panel {
                Toggle("Intruder selfie", "Takes a front-camera photo after 3 wrong attempts", selfie) {
                    if (it) cam.launch(Manifest.permission.CAMERA) else { selfie = false; store.selfie = false }
                }
                Toggle("Fake crash screen", "Shows an \"app keeps stopping\" dialog. Long-press its title to reach the real lock.", crash) {
                    crash = it; store.fakeCrash = it
                }
            }
        }
        if (pics.isNotEmpty()) item {
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
