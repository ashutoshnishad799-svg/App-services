package com.ashuapps.lock

import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.widget.FrameLayout
import android.widget.ImageView

/** Covers the Recents cards of locked apps with a real blur (Android 12+) or a solid tint. Touches pass through. */
class RecentsGuard(private val svc: LockService, private val wm: WindowManager) {
    private val store = LockStore(svc)
    private val d = svc.resources.displayMetrics.density
    private val live = ArrayList<View>()
    private val spare = ArrayList<View>()

    private fun cover() = FrameLayout(svc).apply {
        val s = (40 * d).toInt()
        addView(ImageView(svc).apply { setImageResource(R.drawable.ic_lock) }, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
    }

    fun show(rects: List<Rect>) {
        if (rects.isEmpty() && live.isEmpty()) return
        val p = Themes.of(store.theme)
        val blur = Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled
        val a = if (blur) 0x66 else 0xF2
        while (live.size > rects.size) {
            val v = live.removeAt(live.lastIndex)
            runCatching { wm.removeViewImmediate(v) }
            spare += v
        }
        rects.forEachIndexed { i, r ->
            val lp = LP(r.width(), r.height(), LP.TYPE_ACCESSIBILITY_OVERLAY,
                LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS or LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCHABLE or
                    (if (blur) LP.FLAG_BLUR_BEHIND else 0), PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START; lp.x = r.left; lp.y = r.top
            if (Build.VERSION.SDK_INT >= 31 && blur) lp.blurBehindRadius = 80
            if (i < live.size) runCatching { wm.updateViewLayout(live[i], lp) }
            else {
                val v = if (spare.isNotEmpty()) spare.removeAt(spare.lastIndex) else cover()
                v.background = GradientDrawable().apply { cornerRadius = 20 * d; setColor((p.bg[0] and 0xFFFFFF) or (a shl 24)) }
                ((v as FrameLayout).getChildAt(0) as ImageView).setColorFilter(p.text)
                runCatching { wm.addView(v, lp); live += v }
            }
        }
    }

    fun clear() = show(emptyList())
}
