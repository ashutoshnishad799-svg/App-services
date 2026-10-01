package com.ashuapps.lock

import android.graphics.Color

/** One palette drives both the app UI and the lock screen. */
class Pal(
    val name: String, val bg: IntArray, val card: Int, val border: Int, val key: Int,
    val text: Int, val sub: Int, val accent: Int, val onAccent: Int, val solid: Int,
    val lightBg: Boolean, val glass: Boolean = false
)

object Themes {
    private fun c(s: String) = Color.parseColor(s)
    private fun grad(name: String, a: String, b: String, solid: String) = Pal(
        name, intArrayOf(c(a), c(b)), c("#29FFFFFF"), c("#4DFFFFFF"), c("#2EFFFFFF"),
        c("#FFFFFF"), c("#D9FFFFFF"), c("#FFFFFF"), c(solid), c(solid), false)

    val all = listOf(
        Pal("Glass", intArrayOf(c("#2A1060"), c("#0077C8")), c("#26FFFFFF"), c("#59FFFFFF"), c("#2EFFFFFF"),
            c("#FFFFFF"), c("#B3FFFFFF"), c("#D0BCFF"), c("#381E72"), c("#1E1B4B"), false, true),
        grad("Aurora", "#6A11CB", "#2575FC", "#2B1B6B"),
        grad("Sunset", "#FF512F", "#DD2476", "#7A1B3A"),
        grad("Ocean", "#00C6A7", "#1E4DB7", "#0B3B6B"),
        Pal("White", intArrayOf(c("#FFFFFF")), c("#F3F4F6"), c("#E5E7EB"), c("#0F000000"),
            c("#111827"), c("#6B7280"), c("#2563EB"), c("#FFFFFF"), c("#FFFFFF"), true),
        Pal("Slate", intArrayOf(c("#0F172A")), c("#1E293B"), c("#334155"), c("#2B3A55"),
            c("#E2E8F0"), c("#94A3B8"), c("#38BDF8"), c("#0C4A6E"), c("#1E293B"), false),
        Pal("Dark", intArrayOf(c("#121212")), c("#1E1E1E"), c("#2C2C2C"), c("#2A2A2A"),
            c("#EEEEEE"), c("#9E9E9E"), c("#BB86FC"), c("#000000"), c("#2B2B2B"), false),
        Pal("Pure Dark", intArrayOf(c("#000000")), c("#0B0B0B"), c("#1F1F1F"), c("#161616"),
            c("#FFFFFF"), c("#8A8A8A"), c("#00E5FF"), c("#000000"), c("#0B0B0B"), false)
    )

    fun of(i: Int) = all[i.coerceIn(0, all.lastIndex)]
}
