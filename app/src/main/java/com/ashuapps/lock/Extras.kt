package com.ashuapps.lock

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.io.File
import kotlin.concurrent.thread

/** Hidden-apps vault: passcode / fingerprint gate, then a folder of your hidden apps. */
class VaultActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        val store = LockStore(this)
        setContent { Themed(Themes.of(store.theme)) { Vault(store, ::finger) } }
    }

    private fun finger(onOk: () -> Unit) {
        val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) return
        val info = BiometricPrompt.PromptInfo.Builder().setTitle("Unlock hidden apps")
            .setNegativeButtonText("Use passcode").setAllowedAuthenticators(auth).build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
        }).authenticate(info)
    }
}

@Composable
internal fun PadKey(label: String?, icon: Int?, onClick: () -> Unit) {
    val p = LocalPal.current
    Box(
        Modifier.size(76.dp).clip(CircleShape).background(Color(p.key)).border(1.dp, Color(p.border), CircleShape)
            .clickable(onClick = onClick),
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
        val maxLen = if (autoLen > 0) autoLen else 6
        if (cur.length < maxLen) cur += c
        if (autoLen > 0 && cur.length == autoLen) { val s = cur; cur = ""; onSubmit(s) }
    }
    when (type) {
        0 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.height(28.dp), Arrangement.spacedBy(14.dp), Alignment.CenterVertically) {
                repeat(if (autoLen > 0) autoLen else maxOf(4, cur.length)) { i ->
                    Box(Modifier.size(14.dp).clip(CircleShape)
                        .background(if (i < cur.length) Color(p.text) else Color.Transparent)
                        .border(2.dp, Color(p.text), CircleShape))
                }
            }
            Spacer(Modifier.height(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                listOf("123", "456", "789").forEach { r ->
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        r.forEach { c -> PadKey(c.toString(), null) { press(c.toString()) } }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Spacer(Modifier.size(76.dp))
                    PadKey("0", null) { press("0") }
                    PadKey(null, R.drawable.ic_backspace) { cur = cur.dropLast(1) }
                }
            }
            if (autoLen == 0) {
                Spacer(Modifier.height(20.dp))
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

/** Full-screen "create + confirm" flow for a new PIN, pattern or password. */
@Composable
internal fun PasscodeSetup(type: Int, store: LockStore, onClose: () -> Unit) {
    val pal = LocalPal.current
    var first by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    val name = listOf("PIN", "Pattern", "Password")[type]
    val tip = listOf("Use 4 to 6 digits", "Connect at least 4 dots", "Use at least 4 characters")[type]
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(painterResource(R.drawable.ic_back), "Back", tint = Color(pal.text)) }
            Text("Set $name", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
        Text(if (first.isEmpty()) "Create your ${name.lowercase()}" else "Confirm your ${name.lowercase()}",
            fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(if (msg.isEmpty()) tip else msg, fontSize = 14.sp, color = Color(pal.sub),
            modifier = Modifier.padding(top = 6.dp, bottom = 24.dp))
        key(first) {
            Credential(type, 0) { s ->
                when {
                    first.isEmpty() -> { first = s; msg = "" }
                    first == s -> { store.setPin(s); store.lockType = type; onClose() }
                    else -> { first = ""; msg = "Did not match. Start again." }
                }
            }
        }
    }
}

@Composable
internal fun Vault(store: LockStore, askFinger: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val pal = LocalPal.current
    val sub = Color(pal.sub)
    var open by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val hidden = remember(open) { store.hidden.toList() }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(painterResource(R.drawable.ic_folder), null, Modifier.size(44.dp), tint = Color(pal.text))
        Text("Hidden apps", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        if (!store.hasPin()) {
            Text("Set a passcode in Ashu AppLock first.", color = sub, modifier = Modifier.padding(top = 12.dp))
        } else if (!open) {
            Text(if (msg.isEmpty()) "Enter your passcode to continue" else msg, color = sub,
                modifier = Modifier.padding(top = 6.dp, bottom = 24.dp))
            Credential(store.lockType, if (store.lockType == 0) store.pinLen else 0) { s ->
                if (store.check(s)) open = true else msg = "Wrong passcode, try again"
            }
            TextButton({ askFinger { open = true } }, Modifier.padding(top = 12.dp)) {
                Icon(painterResource(R.drawable.ic_finger), null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Use fingerprint")
            }
        } else {
            Text("Tap an app to open it. It hides again when you leave it.", fontSize = 13.sp, color = sub,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp))
            if (hidden.isEmpty()) Panel { Text("No hidden apps yet. Hide apps from the Hide tab.") }
            else Panel {
                LazyVerticalGrid(GridCells.Fixed(4), Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    gridItems(hidden) { h ->
                        val (pkg, label) = h.split("|", limit = 2)
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

/** Unhide via Shizuku, remember it, launch it. LockService hides it again when you leave. */
private fun reveal(ctx: Context, store: LockStore, pkg: String) {
    thread {
        val ok = Sh.ready() && Sh.unhide(pkg)
        (ctx as Activity).runOnUiThread {
            if (!ok) {
                Toast.makeText(ctx, "Start Shizuku and grant access first", Toast.LENGTH_LONG).show()
            } else {
                store.revealed = store.revealed + pkg
                store.revealAt = System.currentTimeMillis()
                ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { ctx.startActivity(it) }
            }
        }
    }
}
