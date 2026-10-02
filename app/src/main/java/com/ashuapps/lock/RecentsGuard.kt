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

/** Opaque covers for the Recents screen (touches pass through). Blur sits behind them where the device supports it. */
class RecentsGuard(private val svc: LockService, private val wm: WindowManager) {
    private val store = LockStore(svc)
    private val d = svc.resources.displayMetrics.density
    private val live = ArrayList<View>()
    private val spare = ArrayList<View>()
    private val last = ArrayList<Rect>()
    val active get() = live.isNotEmpty()

    private fun cover() = FrameLayout(svc).apply {
        val s = (44 * d).toInt()
        addView(ImageView(svc).apply { setImageResource(R.drawable.ic_lock) }, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
    }

    fun show(rects: List<Rect>) {
        if (rects.isEmpty() && live.isEmpty()) return
        val p = Themes.of(store.theme)
        val blur = Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled
        while (live.size > rects.size) {
            val v = live.removeAt(live.lastIndex); last.removeAt(last.lastIndex)
            runCatching { wm.removeViewImmediate(v) }
            spare += v
        }
        val pad = (10 * d).toInt() // a little bigger than the card so small lag never shows an edge
        rects.forEachIndexed { i, r0 ->
            val r = Rect(r0.left - pad, r0.top - pad, r0.right + pad, r0.bottom + pad)
            if (i < live.size && last[i] == r) return@forEachIndexed
            val lp = LP(r.width(), r.height(), LP.TYPE_ACCESSIBILITY_OVERLAY,
                LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS or LP.FLAG_NOT_FOCUSABLE or LP.FLAG_NOT_TOUCHABLE or
                    (if (blur) LP.FLAG_BLUR_BEHIND else 0), PixelFormat.TRANSLUCENT)
            lp.gravity = Gravity.TOP or Gravity.START; lp.x = r.left; lp.y = r.top
            if (Build.VERSION.SDK_INT >= 31 && blur) lp.blurBehindRadius = 90
            if (i < live.size) { runCatching { wm.updateViewLayout(live[i], lp) }; last[i] = r }
            else {
                val v = if (spare.isNotEmpty()) spare.removeAt(spare.lastIndex) else cover()
                // never rely on blur for privacy: the tint stays ~95% opaque either way
                v.background = GradientDrawable().apply { cornerRadius = 24 * d; setColor((p.bg[0] and 0xFFFFFF) or (0xF2 shl 24)) }
                ((v as FrameLayout).getChildAt(0) as ImageView).setColorFilter(p.text)
                if (runCatching { wm.addView(v, lp) }.isSuccess) { live += v; last += r } else spare += v
            }
        }
    }

    fun clear() = show(emptyList())
}
