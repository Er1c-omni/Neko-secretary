package com.neko.secretary.overlay

import kotlin.math.abs
import kotlin.math.roundToInt

enum class DockSide { LEFT, RIGHT }

/** Usable screen area for the overlay (system bars excluded vertically). */
data class ScreenArea(val width: Int, val height: Int, val topInset: Int, val bottomInset: Int)

/**
 * Pure geometry for drag release -> edge snap -> peek / expand.
 * Kept free of Android types so it is covered by JVM unit tests.
 */
object EdgeDocking {
    /** Fraction of the companion that stays on-screen while peeking from the edge. */
    const val PEEK_VISIBLE_FRACTION = 0.42f

    /** Horizontal fling speed (px/s) above which the fling direction wins over the drop position. */
    const val FLING_VELOCITY_PX_PER_S = 1200f

    fun chooseSide(windowX: Int, windowWidth: Int, screenWidth: Int, velocityX: Float): DockSide {
        if (abs(velocityX) >= FLING_VELOCITY_PX_PER_S) {
            return if (velocityX < 0f) DockSide.LEFT else DockSide.RIGHT
        }
        val centerX = windowX + windowWidth / 2
        return if (centerX < screenWidth / 2) DockSide.LEFT else DockSide.RIGHT
    }

    /** X so that only [visibleFraction] of the window is on-screen (requires FLAG_LAYOUT_NO_LIMITS). */
    fun peekX(side: DockSide, windowWidth: Int, screenWidth: Int,
              visibleFraction: Float = PEEK_VISIBLE_FRACTION): Int {
        val visible = (windowWidth * visibleFraction.coerceIn(0.1f, 1f)).roundToInt()
        return when (side) {
            DockSide.LEFT -> visible - windowWidth
            DockSide.RIGHT -> screenWidth - visible
        }
    }

    /** X for the fully revealed companion hugging the chosen edge with [marginPx]. */
    fun expandedX(side: DockSide, windowWidth: Int, screenWidth: Int, marginPx: Int): Int = when (side) {
        DockSide.LEFT -> marginPx
        DockSide.RIGHT -> screenWidth - windowWidth - marginPx
    }

    /** X for the stealth badge: flush against the edge, never off-screen. */
    fun badgeX(side: DockSide, badgeWidth: Int, screenWidth: Int): Int = when (side) {
        DockSide.LEFT -> 0
        DockSide.RIGHT -> screenWidth - badgeWidth
    }

    fun clampY(y: Int, windowHeight: Int, area: ScreenArea): Int {
        val minY = area.topInset
        val maxY = (area.height - area.bottomInset - windowHeight).coerceAtLeast(minY)
        return y.coerceIn(minY, maxY)
    }

    /** Keep the stored vertical position meaningful across rotations by persisting it as a ratio. */
    fun yToRatio(y: Int, windowHeight: Int, area: ScreenArea): Float {
        val range = (area.height - area.bottomInset - windowHeight - area.topInset).coerceAtLeast(1)
        return ((y - area.topInset).toFloat() / range).coerceIn(0f, 1f)
    }

    fun ratioToY(ratio: Float, windowHeight: Int, area: ScreenArea): Int {
        val range = (area.height - area.bottomInset - windowHeight - area.topInset).coerceAtLeast(0)
        return area.topInset + (range * ratio.coerceIn(0f, 1f)).roundToInt()
    }

    /** Bubble anchor: beside the companion, on the screen-interior side. */
    fun bubbleX(side: DockSide, petX: Int, petWidth: Int, bubbleWidth: Int, screenWidth: Int, gapPx: Int): Int {
        val raw = when (side) {
            DockSide.LEFT -> petX + petWidth + gapPx
            DockSide.RIGHT -> petX - bubbleWidth - gapPx
        }
        return raw.coerceIn(0, (screenWidth - bubbleWidth).coerceAtLeast(0))
    }
}
