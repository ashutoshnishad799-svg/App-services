package com.ashuapps.lock

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Themed lock screen. Built ONCE; later it is only attached/detached, so it appears with minimum delay. */
class LockOverlay(private val svc: LockService) {
    private val d = svc.resources.displayMetrics.density
    private val store = LockStore(svc)
    private var entered = ""
    private var attached = false
    private var fails = 0
    private var baseHint = ""
    private val icon = ImageView(svc)
    private val name = txt(22f, true)
    private val subtitle = txt(14f)
    private val dotsRow = LinearLayout(svc).apply { gravity = Gravity.CENTER }
    private var dotColor = Color.WHITE
    private val card = LinearLayout(svc)
    private val header = LinearLayout(svc)
    private val keypad = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }
    private val keys = ArrayList<View>()
    private val digits = ArrayList<TextView>()
    private val glyphs = ArrayList<ImageView>()
    private val pat = PatternView(svc)
    private val pass = EditText(svc)
    private val passBox = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val unlockBtn = txt(18f, true)
    private val crashTitle = txt(18f)
    private val crash = FrameLayout(svc)

    private val root = object : FrameLayout(svc) {
        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e)
            if (e.action == KeyEvent.ACTION_UP) svc.goHome()
            return true
        }
    }

    private fun px(v: Int) = (v * d).toInt()
    private fun txt(size: Float, bold: Boolean = false) = TextView(svc).apply {
        textSize = size; gravity = Gravity.CENTER; if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun shape(r: Int, fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = r * d; setColor(fill); setStroke(px(1), stroke)
    }

    init {
        root.isFocusableInTouchMode = true
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.setPadding(px(18), px(18), px(18), px(14))
        header.orientation = LinearLayout.VERTICAL
        header.gravity = Gravity.CENTER_HORIZONTAL
        icon.setOnClickListener { svc.askBiometric() }
        header.addView(icon, LinearLayout.LayoutParams(px(76), px(76)))
        header.addView(name, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(12) })
        header.addView(subtitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(4) })
        header.addView(dotsRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(22) })
        // keypad = 4 rows of 3 round keys (plain LinearLayouts, no GridLayout quirks)
        listOf("123", "456", "789", "f0b").forEach { row ->
            val r = LinearLayout(svc)
            row.forEach { ch ->
                val k = ch.toString()
                val key: View = when (k) {
                    "f", "b" -> ImageView(svc).apply {
                        setImageResource(if (k == "f") R.drawable.ic_finger else R.drawable.ic_backspace)
                        setPadding(px(22), px(22), px(22), px(22)); glyphs += this
                    }
                    else -> txt(24f).apply { text = k; digits += this }
                }
                key.setOnClickListener { press(k) }
                key.setOnTouchListener { v, ev -> // press animation: shrink on touch, spring back on release
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            v.animate().scaleX(0.86f).scaleY(0.86f).alpha(0.7f).setDuration(70).start()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(OvershootInterpolator(2f)).start()
                    }
                    false
                }
                keys += key
                r.addView(key, LinearLayout.LayoutParams(px(76), px(76)).apply { setMargins(px(7), px(7), px(7), px(7)) })
            }
            keypad.addView(r)
        }
        // explicit WRAP_CONTENT: a MATCH_PARENT child is ignored when a wrap_content parent measures its width
        card.addView(keypad, LinearLayout.LayoutParams(-2, -2))
        pat.onDone = { s -> if (store.check(s)) svc.unlock() else fail() }
        card.addView(pat, LinearLayout.LayoutParams(px(240), px(240)))
        pass.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = "Password"; textSize = 20f; gravity = Gravity.CENTER
            setOnEditorActionListener { _, _, _ -> submitPass(); true }
        }
        passBox.addView(pass, LinearLayout.LayoutParams(px(240), -2))
        unlockBtn.apply { text = "Unlock"; setPadding(px(32), px(10), px(32), px(10)); setOnClickListener { submitPass() } }
        passBox.addView(unlockBtn, LinearLayout.LayoutParams(-2, -2).apply { topMargin = px(14) })
        card.addView(passBox, LinearLayout.LayoutParams(-2, -2))
        root.addView(header, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        // fake "app keeps stopping" dialog on top; long-press its title to reach the real lock
        val box = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL; setPadding(px(24), px(24), px(24), px(8))
            background = shape(20, Color.WHITE, 0x00FFFFFF)
        }
        crashTitle.setTextColor(Color.BLACK)
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

    private fun refresh() {
        for (i in 0 until dotsRow.childCount) dotsRow.getChildAt(i).background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (i < entered.length) setColor(dotColor) else { setColor(0); setStroke(px(2), dotColor) }
        }
    }

    private fun buildDots() {
        dotsRow.removeAllViews()
        repeat(store.pinLen) {
            dotsRow.addView(View(svc), LinearLayout.LayoutParams(px(14), px(14)).apply { setMargins(px(7), 0, px(7), 0) })
        }
        refresh()
    }

    private fun press(k: String) {
        subtitle.text = baseHint
        when (k) {
            "b" -> entered = entered.dropLast(1)
            "f" -> svc.askBiometric()
            else -> if (entered.length < store.pinLen) entered += k
        }
        refresh()
        if (entered.length == store.pinLen) { if (store.check(entered)) svc.unlock() else fail() }
    }

    private fun fail() {
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        header.animate().translationX(24f).setDuration(60).withEndAction {
            header.animate().translationX(0f).setDuration(60).start()
        }.start()
        entered = ""; refresh(); pass.setText(""); subtitle.text = "Wrong code, try again"
        if (++fails >= 3) { fails = 0; svc.intruder() }
    }

    private fun submitPass() {
        if (store.check(pass.text.toString())) svc.unlock() else fail()
    }

    /** Paints the active theme (shared with the app) onto every view. Returns the palette. */
    private fun applyTheme(blur: Boolean): Pal {
        val p = Themes.of(store.theme)
        val a = if (p.glass) (if (blur) 0xC8 else 0xF2) else 0xFF // never fully see-through, even if blur silently fails
        val cols = IntArray(p.bg.size) { (p.bg[it] and 0xFFFFFF) or (a shl 24) }
        root.background = if (cols.size > 1) GradientDrawable(GradientDrawable.Orientation.TL_BR, cols) else ColorDrawable(cols[0])
        card.background = shape(32, p.card, p.border)
        keys.forEach { it.background = shape(36, p.key, p.border) }
        name.setTextColor(p.text); subtitle.setTextColor(p.sub); dotColor = p.text
        digits.forEach { it.setTextColor(p.text) }; glyphs.forEach { it.setColorFilter(p.text) }; buildDots()
        pass.setTextColor(p.text); pass.setHintTextColor(p.sub)
        unlockBtn.setTextColor(p.text); unlockBtn.background = shape(24, p.key, p.border)
        pat.tint = p.text
        val lt = store.lockType.coerceIn(0, 2)
        keypad.visibility = if (lt == 0) View.VISIBLE else View.GONE
        dotsRow.visibility = keypad.visibility
        pat.visibility = if (lt == 1) View.VISIBLE else View.GONE
        passBox.visibility = if (lt == 2) View.VISIBLE else View.GONE
        baseHint = listOf("Enter PIN", "Draw your pattern", "Enter password")[lt]
        subtitle.text = baseHint
        val hDp = svc.resources.displayMetrics.heightPixels / d
        (header.layoutParams as FrameLayout.LayoutParams).topMargin = px(if (lt == 2) 56 else maxOf(28, ((hDp - 640) * 0.45f).toInt()))
        (card.layoutParams as FrameLayout.LayoutParams).apply { // pad sits low (thumb reach); password field stays above the keyboard
            gravity = (if (lt == 2) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            topMargin = if (lt == 2) px(270) else 0
            bottomMargin = if (lt == 2) 0 else px(40)
        }
        return p
    }

    /** @return true if the overlay was newly attached (false = it was already showing). */
    @Suppress("DEPRECATION")
    fun show(wm: WindowManager, target: String): Boolean {
        entered = ""; refresh()
        crash.visibility = if (store.fakeCrash) View.VISIBLE else View.GONE
        val fresh = !attached
        if (fresh) {
            val blur = Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled
            val p = applyTheme(blur)
            val lp = LP(-1, -1, LP.TYPE_ACCESSIBILITY_OVERLAY,
                LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS or (if (p.glass && blur) LP.FLAG_BLUR_BEHIND else 0),
                PixelFormat.TRANSLUCENT)
            if (Build.VERSION.SDK_INT >= 31 && p.glass && blur) lp.blurBehindRadius = 70
            // draw under the status bar + camera cutout (no black strip) and follow the theme's icon colour
            if (Build.VERSION.SDK_INT >= 30) lp.layoutInDisplayCutoutMode = LP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else if (Build.VERSION.SDK_INT >= 28) lp.layoutInDisplayCutoutMode = LP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                (if (p.lightBg) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
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
