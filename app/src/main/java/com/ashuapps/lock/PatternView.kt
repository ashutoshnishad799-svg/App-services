package com.ashuapps.lock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** 3x3 pattern pad. Reports the dot order as digits "1".."9", so it reuses the PIN hash/check. */
class PatternView(c: Context) : View(c) {
    var onDone: (String) -> Unit = {}
    var tint = Color.WHITE // set from the theme
    private val d = resources.displayMetrics.density
    private val path = ArrayList<Int>()
    private var dragging = false
    private var fx = 0f
    private var fy = 0f
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255); strokeWidth = 6 * d; strokeCap = Paint.Cap.ROUND
    }

    private fun cx(i: Int) = width / 6f * (2 * (i % 3) + 1)
    private fun cy(i: Int) = height / 6f * (2 * (i / 3) + 1)

    override fun onDraw(cv: Canvas) {
        dot.color = tint; line.color = (tint and 0xFFFFFF) or (0xCC shl 24)
        for (k in 1 until path.size) cv.drawLine(cx(path[k - 1]), cy(path[k - 1]), cx(path[k]), cy(path[k]), line)
        if (dragging && path.isNotEmpty()) cv.drawLine(cx(path.last()), cy(path.last()), fx, fy, line)
        for (i in 0..8) cv.drawCircle(cx(i), cy(i), (if (i in path) 11 else 6) * d, dot)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        fx = e.x; fy = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); path.clear(); dragging = true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                val s = path.joinToString("") { (it + 1).toString() }
                path.clear(); invalidate()
                if (s.isNotEmpty()) onDone(s)
                return true
            }
        }
        for (i in 0..8) if (i !in path && hypot(fx - cx(i), fy - cy(i)) < width / 6f * 0.75f) path += i
        invalidate()
        return true
    }
}
