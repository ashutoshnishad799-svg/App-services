package com.ashuapps.lock

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Fingerprint prompt for any FragmentActivity (the passcode stays as the fallback). */
internal fun FragmentActivity.askFinger(onOk: () -> Unit) {
    val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG
    if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) return
    val info = BiometricPrompt.PromptInfo.Builder().setTitle("Unlock").setNegativeButtonText("Use passcode")
        .setAllowedAuthenticators(auth).build()
    BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
    }).authenticate(info)
}

/** Hidden-apps vault: passcode / fingerprint gate, then a folder of your hidden apps. */
class VaultActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val store = LockStore(this)
        val pre = store.vaultUntil > System.currentTimeMillis() // a verified dial code just opened us
        store.vaultUntil = 0
        setContent { Themed(Themes.of(store.theme)) { Vault(store, pre) { askFinger(it) } } }
    }

    override fun onStop() { super.onStop(); if (!isChangingConfigurations) finish() } // reopening asks again
}

// ---------- small helpers ----------

private val iconCache = android.util.LruCache<String, ImageBitmap>(200)

/** App icon loaded off the main thread and cached, so long lists scroll smoothly. */
@Composable
internal fun rememberIcon(pkg: String): ImageBitmap? {
    val ctx = LocalContext.current
    return produceState<ImageBitmap?>(iconCache.get(pkg), pkg) {
        if (value == null) {
            val b = withContext(Dispatchers.IO) {
                runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
            }
            if (b != null) { iconCache.put(pkg, b); value = b }
        }
    }.value
}

@Composable
internal fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1000) } }
    return now
}

internal fun fmtMin(m: Int) = if (m == 0) "Off" else if (m >= 60 && m % 60 == 0) "${m / 60}h" else "${m}m"

internal fun fmtClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

internal fun fmtTime(ms: Long) = android.text.format.DateFormat.format("hh:mm a", ms).toString()

internal fun toast(ctx: Context, m: String) { Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, m, Toast.LENGTH_LONG).show() } }

// ---------- pad, setup, gate ----------

@Composable
internal fun PadKey(label: String?, icon: Int?, onClick: () -> Unit) {
    val p = LocalPal.current
    val haptic = LocalHapticFeedback.current
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (down) 0.84f else 1f, spring(dampingRatio = 0.5f, stiffness = 700f), label = "keyScale")
    val fill by animateColorAsState(if (down) Color(p.accent).copy(alpha = 0.4f) else Color(p.key), label = "keyFill")
    Box(
        Modifier.size(78.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(fill)
            .border(1.dp, Color(p.border), CircleShape)
            .clickable(interactionSource = src, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        if (label != null) Text(label, fontSize = 26.sp)
        if (icon != null) Icon(painterResource(icon), null, Modifier.size(26.dp), tint = Color(p.text))
    }
}

/** PIN pad / pattern pad / password field. autoLen > 0 submits a PIN as soon as it is that long. */
@Composable
internal fun Credential(type: Int, autoLen: Int, onSubmit: (String) -> Unit) {
    val p = LocalPal.current
    var cur by remember { mutableStateOf("") }
    var warn by remember { mutableStateOf("") }
    fun press(c: String) {
        if (cur.length < (if (autoLen > 0) autoLen else 6)) cur += c
        if (autoLen > 0 && cur.length == autoLen) { val s = cur; cur = ""; onSubmit(s) }
    }
    when (type) {
        0 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.height(28.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
                repeat(if (autoLen > 0) autoLen else maxOf(4, cur.length)) { i ->
                    val fill by animateColorAsState(if (i < cur.length) Color(p.text) else Color.Transparent, label = "dot")
                    Box(Modifier.size(14.dp).clip(CircleShape).background(fill).border(2.dp, Color(p.text), CircleShape))
                }
            }
            Spacer(Modifier.height(28.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                listOf("123", "456", "789").forEach { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        r.forEach { c -> PadKey(c.toString(), null) { press(c.toString()) } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Spacer(Modifier.size(78.dp))
                    PadKey("0", null) { press("0") }
                    PadKey(null, R.drawable.ic_backspace) { cur = cur.dropLast(1) }
                }
            }
            if (autoLen == 0) {
                Spacer(Modifier.height(18.dp))
                Button({ val s = cur; cur = ""; onSubmit(s) }, enabled = cur.length >= 4) { Text("Continue") }
            }
        }
        1 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AndroidView({ c ->
                PatternView(c).apply {
                    tint = p.text
                    onDone = { s -> if (s.length < 4) warn = "Connect at least 4 dots" else { warn = ""; onSubmit(s) } }
                }
            }, Modifier.size(300.dp))
            if (warn.isNotEmpty()) Text(warn, color = Color(p.sub))
        }
        else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(
                cur, { cur = it.take(32) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Password") }, visualTransformation = PasswordVisualTransformation())
            Button({ val s = cur; cur = ""; onSubmit(s) }, enabled = cur.length >= 4) { Text("Continue") }
        }
    }
}

/** Full-screen "create + confirm" flow for a new PIN, pattern or password. The pad sits low, in thumb reach. */
@Composable
internal fun PasscodeSetup(type: Int, store: LockStore, onClose: () -> Unit) {
    val pal = LocalPal.current
    var first by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val name = listOf("PIN", "Pattern", "Password")[type]
    val tip = listOf("Use 4 to 6 digits", "Connect at least 4 dots", "Use at least 4 characters")[type]
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(painterResource(R.drawable.ic_back), "Back", tint = Color(pal.text)) }
            Text("Set $name", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(28.dp))
        Text(if (first.isEmpty()) "Create your ${name.lowercase()}" else "Confirm your ${name.lowercase()}",
            fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(if (msg.isEmpty()) tip else msg, fontSize = 14.sp, color = Color(pal.sub), modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.weight(1f))
        key(first) {
            Credential(type, 0) { s ->
                when {
                    first.isEmpty() -> { first = s; msg = "" }
                    first == s -> { store.setPin(s); store.lockType = type; onClose() }
                    else -> { first = ""; msg = "Did not match. Start again." }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Passcode (or fingerprint) check shown before anything private: the vault, unhide, photos, this app. */
@Composable
internal fun GateScreen(
    store: LockStore, title: String, askFinger: (() -> Unit) -> Unit, onCancel: (() -> Unit)?, onOk: () -> Unit
) {
    val pal = LocalPal.current
    var msg by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onCancel != null) IconButton(onCancel) { Icon(painterResource(R.drawable.ic_back), "Back", tint = Color(pal.text)) }
        }
        Spacer(Modifier.height(12.dp))
        Icon(painterResource(R.drawable.ic_lock), null, Modifier.size(40.dp), tint = Color(pal.text))
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Text(if (msg.isEmpty()) "Enter your passcode" else msg, fontSize = 14.sp, color = Color(pal.sub),
            modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.weight(1f))
        Credential(store.lockType, if (store.lockType == 0) store.pinLen else 0) { s ->
            if (store.check(s)) onOk() else msg = "Wrong passcode, try again"
        }
        TextButton({ askFinger { onOk() } }, Modifier.padding(top = 8.dp)) {
            Icon(painterResource(R.drawable.ic_finger), null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Use fingerprint")
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
internal fun Vault(store: LockStore, pre: Boolean, askFinger: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPal.current
    var open by remember { mutableStateOf(pre) }
    val hidden = remember(open) { store.hidden.toList() }
    if (!store.hasPin()) {
        Text("Set a passcode in Ashu AppLock first.", Modifier.statusBarsPadding().padding(24.dp))
    } else if (!open) {
        GateScreen(store, "Hidden apps", askFinger, null) { open = true }
    } else {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(30.dp), tint = Color(pal.text))
                Text("Hidden apps", Modifier.padding(start = 10.dp), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
            Text("Tap an app to open it. It hides again when you leave it.", fontSize = 13.sp, color = Color(pal.sub),
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            if (hidden.isEmpty()) Panel { Text("No hidden apps yet. Hide apps from the Hide tab.") }
            else Panel {
                LazyVerticalGrid(GridCells.Fixed(4), Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    gridItems(hidden) { h ->
                        val pkg = h.substringBefore('|')
                        val label = h.substringAfter('|')
                        val bmp = remember(pkg) { BitmapFactory.decodeFile(File(ctx.filesDir, "icons/$pkg.png").path)?.asImageBitmap() }
                        Column(
                            Modifier.clip(RoundedCornerShape(12.dp)).clickable { reveal(ctx, store, pkg) }.padding(4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (bmp != null) Image(bmp, label, Modifier.size(52.dp))
                            else Icon(painterResource(R.drawable.ic_lock), label, Modifier.size(52.dp))
                            Text(label, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Unhide via Shizuku (verified), remember it, launch it. LockService hides it again when you leave. */
private fun reveal(ctx: Context, store: LockStore, pkg: String) {
    thread {
        val ready = Sh.ready()
        val ok = ready && Sh.unhide(pkg)
        (ctx as Activity).runOnUiThread {
            if (!ok) toast(ctx, if (ready) "Could not open: ${Sh.last.take(120)}" else "Start Shizuku and grant access first")
            else {
                store.revealed = store.revealed + pkg
                store.revealAt = System.currentTimeMillis()
                ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it) }
            }
        }
    }
}

// ---------- timers ----------

/** Preset chips + a typed custom value, in minutes. 0 shows as "Off". */
@Composable
internal fun DurationPicker(presets: List<Int>, selected: Int, perRow: Int = 4, onPick: (Int) -> Unit) {
    var custom by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m -> FilterChip(selected == m, { onPick(m) }, { Text(fmtMin(m)) }) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                custom, { custom = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), singleLine = true,
                label = { Text("Custom minutes") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button({ onPick(custom.toInt()); custom = "" }, enabled = (custom.toIntOrNull() ?: 0) > 0) { Text("Set") }
        }
    }
}

/** Countdown clock: a ring that drains, big digits in the middle. */
@Composable
internal fun ClockRing(progress: Float, big: String, small: String) {
    val p = LocalPal.current
    val ring = Color(p.accent)
    val track = Color(p.border)
    Box(Modifier.size(224.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 14.dp.toPx()
            val s = Size(size.width - w, size.height - w)
            val o = Offset(w / 2, w / 2)
            drawArc(track, 0f, 360f, false, topLeft = o, size = s, style = Stroke(w))
            drawArc(ring, -90f, 360f * progress.coerceIn(0f, 1f), false, topLeft = o, size = s, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(big, fontSize = 46.sp, fontWeight = FontWeight.Bold)
            Text(small, fontSize = 13.sp, color = Color(p.sub))
        }
    }
}
