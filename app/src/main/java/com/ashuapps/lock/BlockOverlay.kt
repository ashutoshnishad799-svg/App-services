package com.ashuapps.lock

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Full-screen "blocked" screen for focus mode, daily limits and the games limit. */
class BlockOverlay(private val svc: LockService) {
    private var attached = false
    private val msg = TextView(svc).apply { textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER }
    private val root = object : FrameLayout(svc) {
        override fun dispatchKeyEvent(e: KeyEvent): Boolean {
            if (e.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e)
            if (e.action == KeyEvent.ACTION_UP) svc.goHome()
            return true
        }
    }

    init {
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(42, 16, 96), Color.rgb(0, 119, 200)))
        root.isFocusableInTouchMode = true
        val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(64, 0, 64, 0) }
        col.addView(TextView(svc).apply { text = "⏳"; textSize = 56f; gravity = Gravity.CENTER })
        col.addView(msg)
        col.addView(Button(svc).apply { text = "Home"; setOnClickListener { svc.goHome() } })
        root.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
    }

    fun show(wm: WindowManager, text: String) {
        msg.text = text
        if (attached) return
        wm.addView(root, LP(-1, -1, LP.TYPE_ACCESSIBILITY_OVERLAY,
            LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS, PixelFormat.OPAQUE))
        attached = true; root.requestFocus()
    }

    fun hide(wm: WindowManager) {
        if (attached) { runCatching { wm.removeViewImmediate(root) }; attached = false }
    }
}
