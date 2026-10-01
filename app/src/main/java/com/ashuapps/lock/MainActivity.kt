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
        val apps = packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        setContent { App(store, apps, tick) }
    }
}

private val LocalPal = staticCompositionLocalOf { Themes.all[0] }
private class NavItem(val icon: String, val label: String)
private val NAV = listOf(
    NavItem("🔒", "Lock"), NavItem("🙈", "Hide"), NavItem("🔑", "Passcode"), NavItem("🎯", "Focus"),
    NavItem("\u23F1\uFE0F", "Timers"), NavItem("🎬", "Reels"), NavItem("🎮", "Games"),
    NavItem("📸", "Intruder"), NavItem("🎨", "Themes"), NavItem("\u2139\uFE0F", "About"))

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

@Composable
private fun App(store: LockStore, apps: List<Pair<String, String>>, tick: Int) {
    var themeId by remember { mutableStateOf(store.theme) }
    val pal = Themes.of(themeId)
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
                Shell(store, apps, tick, themeId) { themeId = it; store.theme = it }
            }
        }
    }
}

@Composable
private fun Orb(c: Color, m: Modifier) {
    Box(m.size(320.dp).background(Brush.radialGradient(listOf(c, Color.Transparent)), CircleShape))
}

/** Swipeable pages + a glass bottom bar that scrolls sideways and follows the pager. */
@Composable
private fun Shell(store: LockStore, apps: List<Pair<String, String>>, tick: Int, themeId: Int, setTheme: (Int) -> Unit) {
    val pal = LocalPal.current
    val pager = rememberPagerState { NAV.size }
    val scope = rememberCoroutineScope()
    val bar = rememberLazyListState()
    LaunchedEffect(pager.currentPage) { bar.animateScrollToItem((pager.currentPage - 1).coerceAtLeast(0)) }
    val shape = RoundedCornerShape(30.dp)
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { i ->
            when (i) {
                0 -> LockPage(store, apps, tick)
                1 -> HidePage(store, apps, tick)
                2 -> PasscodePage(store)
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
                    Text(t.icon, fontSize = 20.sp)
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
private fun Panel(pad: Int = 16, content: @Composable ColumnScope.() -> Unit) {
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
    var hidden by remember { mutableStateOf(store.hidden) }
    val ok = tick >= 0 && Sh.ready()
    PageList("Hide Apps", "Remove apps from the launcher using Shizuku.") {
        item {
            Panel {
                Text(if (ok) "Shizuku is connected." else "Start Shizuku and grant access to hide apps.")
                if (!ok) Button({ Sh.ask() }) { Text("Grant Shizuku access") }
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
            TextButton({ thread { if (Sh.hide(pkg)) { hidden = hidden + "$pkg|$name"; store.hidden = hidden } } }, enabled = ok) { Text("Hide") }
        }
    }
}

@Composable
private fun PasscodePage(store: LockStore) {
    val pal = LocalPal.current
    var setup by remember { mutableStateOf<Int?>(if (store.hasPin()) null else 0) }
    var type by remember { mutableStateOf(store.lockType) }
    var text by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") } // pattern waiting for confirmation
    var msg by remember { mutableStateOf(if (store.hasPin()) "" else "Set a passcode to start locking apps.") }
    var close by remember { mutableStateOf(store.relockOnClose) }
    var off by remember { mutableStateOf(store.relockOnScreenOff) }
    var bio by remember { mutableStateOf(store.autoBio) }
    fun save(t: Int, cred: String) {
        store.setPin(cred); store.lockType = t; type = t; setup = null; text = ""; first = ""; msg = "Passcode saved."
    }
    PageList("Passcode", "Choose how locked apps are unlocked.") {
        item {
            Panel {
                Text("Lock type", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("PIN", "Pattern", "Password").forEachIndexed { i, n ->
                        FilterChip((setup ?: type) == i, { setup = i; text = ""; first = ""; msg = "" }, { Text(n) })
                    }
                }
                when (setup) {
                    0, 2 -> {
                        OutlinedTextField(
                            text, { text = if (setup == 0) it.filter(Char::isDigit).take(6) else it.take(32) },
                            singleLine = true,
                            label = { Text(if (setup == 0) "New PIN (4-6 digits)" else "New password (min 4 characters)") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = if (setup == 0) KeyboardType.NumberPassword else KeyboardType.Password))
                        Button({ save(setup ?: 0, text) }, enabled = text.length >= 4) { Text("Save") }
                    }
                    1 -> {
                        Text(if (first.isEmpty()) "Draw a pattern (at least 4 dots)" else "Draw it again to confirm")
                        AndroidView({ c ->
                            PatternView(c).apply {
                                tint = pal.text
                                onDone = { s ->
                                    when {
                                        s.length < 4 -> msg = "Use at least 4 dots."
                                        first.isEmpty() -> { first = s; msg = "" }
                                        first == s -> save(1, s)
                                        else -> { first = ""; msg = "Patterns did not match. Try again." }
                                    }
                                }
                            }
                        }, Modifier.size(220.dp))
                    }
                }
                if (msg.isNotEmpty()) Text(msg, fontSize = 13.sp, color = Color(pal.sub))
            }
        }
        item {
            Panel {
                Toggle("Relock when the app closes", null, close) { close = it; store.relockOnClose = it }
                Toggle("Relock after the screen turns off", null, off) { off = it; store.relockOnScreenOff = it }
                Toggle("Fingerprint unlock", "Shows the fingerprint prompt as soon as the lock appears", bio) { bio = it; store.autoBio = it }
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
        if (selected) Text("✓", Modifier.align(Alignment.TopStart), color = Color(p.text), fontWeight = FontWeight.Bold)
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
