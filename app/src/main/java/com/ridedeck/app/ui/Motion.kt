package com.ridedeck.app.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import kotlin.math.sign

/** The four things the main area can show; the order is the dock's order, so it decides which way a change slides. */
enum class DeckScreen { HOME, MAP, MUSIC, PHONE }

/** Small, quick screen changes in one place. All of them are skipped when [reduce] is set (a hot or low phone). */
object DeckMotion {
    const val MS = 220

    /** -1, 0 or +1: which way the new screen comes from. */
    fun direction(from: DeckScreen, to: DeckScreen): Int = sign((to.ordinal - from.ordinal).toFloat()).toInt()

    /** Main area: a short fade with a slight slide; the map only fades (it is costly to move). */
    fun screen(from: DeckScreen, to: DeckScreen, reduce: Boolean): ContentTransform {
        if (reduce) return EnterTransition.None togetherWith ExitTransition.None
        if (from == DeckScreen.MAP || to == DeckScreen.MAP) {
            return fadeIn(tween(MS)) togetherWith fadeOut(tween(MS * 2 / 3))
        }
        val dir = direction(from, to).coerceAtLeast(-1).let { if (it == 0) 1 else it }
        return (slideInHorizontally(tween(MS)) { it * dir / 12 } + fadeIn(tween(MS))) togetherWith
            (slideOutHorizontally(tween(MS)) { -it * dir / 12 } + fadeOut(tween(MS * 2 / 3)))
    }

    /** Full-screen overlays (settings, apps, trips): rise a little from the bottom while fading in. */
    fun overlayEnter(reduce: Boolean, risePx: Int): EnterTransition =
        if (reduce) EnterTransition.None else slideInVertically(tween(MS)) { risePx } + fadeIn(tween(MS))

    fun overlayExit(reduce: Boolean, risePx: Int): ExitTransition =
        if (reduce) ExitTransition.None else slideOutVertically(tween(MS)) { risePx } + fadeOut(tween(MS * 2 / 3))
}
