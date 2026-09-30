package com.ashuapps.lock

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalUriHandler
import java.io.File
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)
    override fun onResume() { super.onResume(); tick++ } // re-check accessibility status when coming back

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = LockStore(this)
        val apps = packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Home(store, apps, tick) } }
    }
}

private val bgBrush = Brush.linearGradient(listOf(Color(0xFF2A1060), Color(0xFF0077C8)))

@Composable
private fun Glass(content: @Composable ColumnScope.() -> Unit) {
    val s = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().background(Color.White.copy(0.13f), s)
            .border(1.dp, Color.White.copy(0.3f), s).padding(16.dp),
        content = content
    )
}

@Composable
private fun Home(store: LockStore, apps: List<Pair<String, String>>, tick: Int) {
    val ctx = LocalContext.current
    var setup by remember { mutableStateOf<Int?>(if (store.hasPin()) null else 0) } // null idle, 0 PIN, 1 Pattern
    var pin by remember { mutableStateOf("") }
    var first by remember { mutableStateOf("") } // pattern waiting for confirmation
    var msg by remember { mutableStateOf("") }
    var locked by remember { mutableStateOf(store.locked) }
    var hidden by remember { mutableStateOf(store.hidden) }
    var theme by remember { mutableStateOf(store.theme) }
    var close by remember { mutableStateOf(store.relockOnClose) }
    var off by remember { mutableStateOf(store.relockOnScreenOff) }
    var sel by remember { mutableStateOf<Pair<String, String>?>(null) } // app whose settings dialog is open
    var limits by remember { mutableStateOf(store.limits) }
    var focusApps by remember { mutableStateOf(store.focusApps) }
    var games by remember { mutableStateOf(store.games) }
    var focusUntil by remember { mutableStateOf(store.focusUntil) }
    var pausedUntil by remember { mutableStateOf(store.pausedUntil) }
    var reels by remember { mutableStateOf(store.reels) }
    var gameLimit by remember { mutableStateOf(store.gameLimit) }
    var selfie by remember { mutableStateOf(store.selfie) }
    var crash by remember { mutableStateOf(store.fakeCrash) }
    var version by remember { mutableStateOf(0) }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        selfie = ok; store.selfie = ok
    }
    val active = tick >= 0 && LockService.instance != null
    val shizuku = tick >= 0 && Sh.ready()
    val shown = apps.filter { a -> hidden.none { it.startsWith(a.first + "|") } }

    sel?.let { (pkg, name) ->
        AlertDialog(
            onDismissRequest = { sel = null },
            confirmButton = { TextButton({ sel = null }) { Text("Done") } },
            title = { Text(name) },
            text = {
                Column {
                    val cur = remember(limits) { store.limitOf(pkg) }
                    Text("Daily limit")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0, 15, 30, 60, 120).forEach { m ->
                            FilterChip(cur == m, { store.setLimit(pkg, m); limits = store.limits }, { Text(if (m == 0) "Off" else "${m}m") })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(pkg in focusApps, { focusApps = if (it) focusApps + pkg else focusApps - pkg; store.focusApps = focusApps })
                        Text("Focus mode me block")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(pkg in games, { games = if (it) games + pkg else games - pkg; store.games = games })
                        Text("Game hai (games limit me count)")
                    }
                    TextButton({
                        thread { if (Sh.hide(pkg)) { hidden = hidden + "$pkg|$name"; store.hidden = hidden; sel = null } }
                    }, enabled = shizuku) { Text("Hide (Shizuku)") }
                }
            }
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().background(bgBrush).statusBarsPadding().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(top = 12.dp)) {
            Text("Ashu AppLock", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Text("by Ashutosh  •  t.me/ashuapps_07x", color = Color.White.copy(0.7f), fontSize = 12.sp)
        }
        if (!active) Glass {
            Text("Accessibility ON karo, tabhi app khulte hi lock aayega.", color = Color.White)
            Button({ ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open Accessibility") }
        }
        Glass {
            Text("Theme", color = Color.White, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Glass", "Gradient", "Normal").forEachIndexed { i, n ->
                    FilterChip(theme == i, { theme = i; store.theme = i }, { Text(n) })
                }
            }
            Text("Lock type", color = Color.White, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("PIN", "Pattern", "Password").forEachIndexed { i, n ->
                    FilterChip((setup ?: store.lockType) == i,
                        { setup = i; pin = ""; first = ""; msg = "" }, { Text(n) })
                }
            }
            when (setup) {
                2 -> {
                    OutlinedTextField(
                        pin, { pin = it.take(32) }, singleLine = true, label = { Text("Password (min 4)") },
                        visualTransformation = PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Button({
                        store.setPin(pin); store.lockType = 2; setup = null; pin = ""; msg = "Password set ho gaya"
                    }, enabled = pin.length >= 4) { Text("Save password") }
                }
                0 -> {
                    OutlinedTextField(
                        pin, { pin = it.filter(Char::isDigit).take(6) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Button({
                        store.setPin(pin); store.lockType = 0; setup = null; pin = ""; msg = "PIN set ho gaya"
                    }, enabled = pin.length >= 4) { Text("Save PIN (4-6 digits)") }
                }
                1 -> {
                    Text(if (first.isEmpty()) "Pattern draw karo (min 4 dots)" else "Confirm: dobara draw karo", color = Color.White)
                    AndroidView({ c ->
                        PatternView(c).apply {
                            onDone = { s ->
                                if (s.length < 4) msg = "Kam se kam 4 dots"
                                else if (first.isEmpty()) { first = s; msg = "" }
                                else if (first == s) { store.setPin(s); store.lockType = 1; setup = null; first = ""; msg = "Pattern set ho gaya" }
                                else { first = ""; msg = "Match nahi hua, dobara try karo" }
                            }
                        }
                    }, Modifier.size(220.dp))
                }
            }
            if (msg.isNotEmpty()) Text(msg, color = Color.White.copy(0.8f))
        }
        Glass {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("App band hone par relock", color = Color.White)
                Switch(close, { close = it; store.relockOnClose = it })
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Screen lock ke baad relock", color = Color.White)
                Switch(off, { off = it; store.relockOnScreenOff = it })
            }
        }
        Glass {
            val now = System.currentTimeMillis()
            val on = focusUntil > now
            Text(if (on) "Focus mode ON, ${(focusUntil - now) / 60000 + 1} min baaki" else "Focus mode",
                color = Color.White, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (on) Button({ focusUntil = 0; store.focusUntil = 0 }) { Text("Stop") }
                else listOf(25, 45, 60).forEach { m ->
                    Button({ focusUntil = System.currentTimeMillis() + m * 60_000L; store.focusUntil = focusUntil }) { Text("$m min") }
                }
            }
            Text("Focus me chune hue apps + saare games block. Apps chunne ke liye list me ⋮ dabao.",
                color = Color.White.copy(0.7f), fontSize = 12.sp)
        }
        Glass {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Reels / Shorts blocker", color = Color.White)
                Switch(reels, { reels = it; store.reels = it })
            }
            Text("Pause (Reels, focus, limits):", color = Color.White.copy(0.8f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pausedUntil > System.currentTimeMillis()) Button({ pausedUntil = 0; store.pausedUntil = 0 }) { Text("Resume") }
                else listOf(5, 15, 30).forEach { m ->
                    OutlinedButton({ pausedUntil = System.currentTimeMillis() + m * 60_000L; store.pausedUntil = pausedUntil }) { Text("$m min") }
                }
            }
            Text("Games daily limit (sab games ka total):", color = Color.White.copy(0.8f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 30, 60, 120).forEach { m ->
                    FilterChip(gameLimit == m, { gameLimit = m; store.gameLimit = m }, { Text(if (m == 0) "Off" else "${m}m") })
                }
            }
        }
        Glass {
            Text("Hider (Shizuku)", color = Color.White, fontWeight = FontWeight.Bold)
            if (!shizuku) {
                Text("Shizuku start karo aur permission do, phir apps hide kar sakte ho.", color = Color.White.copy(0.8f))
                Button({ Sh.ask() }) { Text("Grant Shizuku") }
            }
            hidden.forEach { h ->
                val (pkg, label) = h.split("|", limit = 2)
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(label, color = Color.White)
                    TextButton({ thread { if (Sh.unhide(pkg)) { hidden = hidden - h; store.hidden = hidden } } }, enabled = shizuku) { Text("Unhide") }
                }
            }
        }
        Glass {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Intruder selfie (3 galat try)", color = Color.White)
                Switch(selfie, { on -> if (on) cam.launch(Manifest.permission.CAMERA) else { selfie = false; store.selfie = false } })
            }
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("Fake crash screen", color = Color.White)
                Switch(crash, { crash = it; store.fakeCrash = it })
            }
            Text("Fake crash me title ko long-press karo, asli lock khulega.", color = Color.White.copy(0.7f), fontSize = 12.sp)
            val pics = remember(tick, version) { File(ctx.filesDir, "intruders").listFiles()?.sortedByDescending { it.name }.orEmpty() }
            if (pics.isNotEmpty()) {
                Text("Intruder photos: ${pics.size}", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pics.take(3).forEach { f -> remember(f.name) { thumb(f) }?.let { Image(it, null, Modifier.size(72.dp)) } }
                }
                TextButton({ pics.forEach { it.delete() }; version++ }) { Text("Delete all") }
            }
        }
        Glass {
            val uri = LocalUriHandler.current
            Text("Ashu AppLock v0.1.0  •  MIT  •  by Ashutosh", color = Color.White.copy(0.8f), fontSize = 12.sp)
            Row {
                TextButton({ uri.openUri("https://t.me/ashuapps_07x") }) { Text("Telegram") }
                TextButton({ uri.openUri("https://t.me/ashutosh_07x") }) { Text("DM") }
                TextButton({ uri.openUri("https://github.com/ashutoshnishad799-svg") }) { Text("GitHub") }
            }
        }
        } }
            items(shown, key = { it.first }) { (pkg, name) ->
                val icon = remember(pkg) { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }
                Glass {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(icon, null, Modifier.size(40.dp))
                        Text((if (pkg in games || remember(pkg) { autoGame(ctx.packageManager, pkg) }) "🎮 " else "") + name,
                            color = Color.White, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        TextButton({ sel = pkg to name }) { Text("⋮") }
                        Switch(pkg in locked, { on ->
                            locked = if (on) locked + pkg else locked - pkg
                            store.locked = locked
                        })
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
