package com.ashuapps.lock

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Glass lock screen. Built ONCE; later only addView/removeView, so it appears with minimum delay. */
class LockOverlay(private val svc: LockService) {
    private val d = svc.resources.displayMetrics.density
    private val store = LockStore(svc)
    private var entered = ""
    private var attached = false
    private val icon = ImageView(svc)
    private val name = txt(20f)
    private val dots = txt(28f)
    private val card = LinearLayout(svc)
    private val grid = GridLayout(svc).apply { columnCount = 3 }
    private val keys = ArrayList<TextView>()
    private val pat = PatternView(svc)
    private var fails = 0
    private val passBox = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val pass = EditText(svc).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        imeOptions = EditorInfo.IME_ACTION_DONE
        hint = "Password"; textSize = 20f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE); setHintTextColor(0x99FFFFFF.toInt())
        setOnEditorActionListener { _, _, _ -> submitPass(); true }
    }
    private val crashTitle = txt(18f).apply { setTextColor(Color.BLACK) }
    private val crash = FrameLayout(svc)

    private fun px(v: Int) = (v * d).toInt()
    private fun txt(size: Float) = TextView(svc).apply { textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
    private fun glass(r: Int, fill: Int, stroke: Int = 0) = GradientDrawable().apply {
        cornerRadius = r * d; setColor(fill); if (stroke != 0) setStroke(px(1), stroke)
    }
    private fun bg(a: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(Color.argb(a, 42, 16, 96), Color.argb(a, 0, 119, 200)))
    private val bgBlur = bg(0x66)   // translucent: real blur shows through (Android 12+)
    private val bgGrad = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(Color.rgb(131, 58, 180), Color.rgb(253, 29, 89)))
    private val bgSolid = bg(0xF5)  // fallback: nearly opaque, never leaks the app behind

    private val root = object : FrameLayout(svc) {
        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e)
            if (e.action == KeyEvent.ACTION_UP) svc.goHome()
            return true
        }
    }

    init {
        root.isFocusableInTouchMode = true
        card.apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(24), px(28), px(24), px(20)); background = glass(32, 0x26FFFFFF)
        }
        card.addView(icon, LinearLayout.LayoutParams(px(64), px(64)))
        card.addView(name, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(8) })
        card.addView(dots, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(16); bottomMargin = px(16) })
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "☝", "0", "⌫").forEach { k ->
            val key = txt(24f).apply { text = k; background = glass(36, 0x22FFFFFF); setOnClickListener { press(k) } }
            keys += key
            grid.addView(key, GridLayout.LayoutParams().apply {
                width = px(72); height = px(72); setMargins(px(6), px(6), px(6), px(6))
            })
        }
        card.addView(grid)
        card.addView(pat, LinearLayout.LayoutParams(px(240), px(240)))
        pat.onDone = { s -> if (store.check(s)) svc.unlock() else fail() }
        icon.setOnClickListener { svc.askBiometric() }
        root.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        passBox.addView(pass, LinearLayout.LayoutParams(px(240), -2))
        passBox.addView(txt(18f).apply {
            text = "Unlock"; background = glass(24, 0x33FFFFFF); setPadding(px(28), px(10), px(28), px(10))
            setOnClickListener { submitPass() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(12) })
        card.addView(passBox)
        // fake "app keeps stopping" dialog on top; long-press its title to reach the real lock
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(24), px(24), px(24), px(8)); background = glass(20, Color.WHITE)
        }
        box.addView(crashTitle)
        box.addView(txt(16f).apply {
            text = "Close app"; setTextColor(Color.rgb(25, 118, 210)); gravity = Gravity.END
            setPadding(0, px(24), 0, px(12)); setOnClickListener { svc.goHome() }
        })
        crash.setBackgroundColor(Color.argb(215, 0, 0, 0))
        crash.addView(box, FrameLayout.LayoutParams(px(300), -2, Gravity.CENTER))
        crashTitle.setOnLongClickListener { crash.visibility = View.GONE; true }
        root.addView(crash, FrameLayout.LayoutParams(-1, -1))
    }

    private fun fail() {
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        card.animate().translationX(24f).setDuration(60).withEndAction {
            card.animate().translationX(0f).setDuration(60).start()
        }.start()
        entered = ""; refresh(); pass.setText("")
        if (++fails >= 3) { fails = 0; svc.intruder() }
    }

    private fun submitPass() {
        if (store.check(pass.text.toString())) svc.unlock() else fail()
    }

    /** Theme 0 = Glass (blur + frosted card), 1 = Gradient (opaque), 2 = Normal (plain dark). */
    private fun applyTheme(blur: Boolean) {
        val t = store.theme
        root.background = when {
            t == 0 -> if (blur) bgBlur else bgSolid
            t == 1 -> bgGrad
            else -> ColorDrawable(Color.rgb(18, 18, 18))
        }
        card.background = if (t == 0) glass(32, 0x26FFFFFF, 0x55FFFFFF) else null
        keys.forEach {
            it.background = when (t) {
                0 -> glass(36, 0x22FFFFFF, 0x55FFFFFF)
                1 -> glass(36, 0x33FFFFFF)
                else -> glass(36, Color.rgb(42, 42, 42))
            }
        }
        val lt = store.lockType
        grid.visibility = if (lt == 0) View.VISIBLE else View.GONE
        dots.visibility = grid.visibility
        pat.visibility = if (lt == 1) View.VISIBLE else View.GONE
        passBox.visibility = if (lt == 2) View.VISIBLE else View.GONE
        (card.layoutParams as FrameLayout.LayoutParams).apply { // keep the field above the keyboard
            gravity = if (lt == 2) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
            topMargin = if (lt == 2) px(72) else 0
        }
    }

    private fun refresh() {
        dots.text = (0 until store.pinLen).joinToString(" ") { if (it < entered.length) "●" else "○" }
    }

    private fun press(k: String) {
        when (k) {
            "⌫" -> entered = entered.dropLast(1)
            "☝" -> svc.askBiometric()
            else -> if (entered.length < store.pinLen) entered += k
        }
        refresh()
        if (entered.length == store.pinLen) {
            if (store.check(entered)) svc.unlock() else fail()
        }
    }

    /** @return true if the overlay was newly attached (false = it was already showing). */
    fun show(wm: WindowManager, target: String): Boolean {
        entered = ""; refresh()
        crash.visibility = if (store.fakeCrash) View.VISIBLE else View.GONE
        val fresh = !attached
        if (fresh) {
            val blur = Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled
            applyTheme(blur)
            val lp = LP(-1, -1, LP.TYPE_ACCESSIBILITY_OVERLAY,
                LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS or (if (blur) LP.FLAG_BLUR_BEHIND else 0),
                PixelFormat.TRANSLUCENT)
            if (Build.VERSION.SDK_INT >= 31 && blur) lp.blurBehindRadius = 70
            if (store.lockType == 2) lp.softInputMode = LP.SOFT_INPUT_STATE_VISIBLE or LP.SOFT_INPUT_ADJUST_PAN
            wm.addView(root, lp); attached = true
            root.requestFocus()
            if (store.lockType == 2) {
                pass.setText(""); pass.requestFocus()
                pass.postDelayed({ svc.getSystemService(InputMethodManager::class.java).showSoftInput(pass, 0) }, 250)
            }
        }
        // icon + label are loaded AFTER the overlay is already on screen
        runCatching {
            val pm = svc.packageManager
            icon.setImageDrawable(pm.getApplicationIcon(target))
            name.text = pm.getApplicationLabel(pm.getApplicationInfo(target, 0))
            crashTitle.text = "${name.text} keeps stopping"
        }
        return fresh
    }

    fun hide(wm: WindowManager) {
        if (attached) { runCatching { wm.removeViewImmediate(root) }; attached = false }
    }
}
