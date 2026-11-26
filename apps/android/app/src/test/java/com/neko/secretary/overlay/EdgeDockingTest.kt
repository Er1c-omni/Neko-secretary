package com.neko.secretary.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeDockingTest {

    private val screen = ScreenArea(width = 1080, height = 2400, topInset = 100, bottomInset = 120)

    @Test
    fun `chooseSide uses nearest edge when velocity is below fling threshold`() {
        assertEquals(DockSide.LEFT, EdgeDocking.chooseSide(windowX = 100, windowWidth = 300, screenWidth = 1080, velocityX = 200f))
        assertEquals(DockSide.RIGHT, EdgeDocking.chooseSide(windowX = 600, windowWidth = 300, screenWidth = 1080, velocityX = -200f))
    }

    @Test
    fun `chooseSide respects fast fling direction even from opposite half`() {
        assertEquals(DockSide.RIGHT, EdgeDocking.chooseSide(windowX = 50, windowWidth = 300, screenWidth = 1080, velocityX = 1500f))
        assertEquals(DockSide.LEFT, EdgeDocking.chooseSide(windowX = 700, windowWidth = 300, screenWidth = 1080, velocityX = -1500f))
    }

    @Test
    fun `peekX tucks character partially off-screen while expandedX keeps it on-screen`() {
        val petWidth = 300
        val leftPeek = EdgeDocking.peekX(DockSide.LEFT, petWidth, screen.width)
        val rightPeek = EdgeDocking.peekX(DockSide.RIGHT, petWidth, screen.width)

        assertTrue(leftPeek < 0)
        assertTrue(leftPeek + petWidth > 0)
        assertTrue(rightPeek < screen.width)
        assertTrue(rightPeek + petWidth > screen.width)

        assertEquals(12, EdgeDocking.expandedX(DockSide.LEFT, petWidth, screen.width, marginPx = 12))
        assertEquals(1080 - 300 - 12, EdgeDocking.expandedX(DockSide.RIGHT, petWidth, screen.width, marginPx = 12))
    }

    @Test
    fun `badgeX stays flush inside screen bounds`() {
        assertEquals(0, EdgeDocking.badgeX(DockSide.LEFT, badgeWidth = 40, screenWidth = 1080))
        assertEquals(1040, EdgeDocking.badgeX(DockSide.RIGHT, badgeWidth = 40, screenWidth = 1080))
    }

    @Test
    fun `clampY and ratio round-trip stay within safe insets`() {
        val petHeight = 400
        assertEquals(100, EdgeDocking.clampY(-50, petHeight, screen))
        assertEquals(2400 - 120 - 400, EdgeDocking.clampY(3000, petHeight, screen))

        val ratio = EdgeDocking.yToRatio(940, petHeight, screen)
        val restored = EdgeDocking.ratioToY(ratio, petHeight, screen)
        assertEquals(940, restored)
    }

    @Test
    fun `bubbleX places bubble on screen-interior side of companion`() {
        val leftBubble = EdgeDocking.bubbleX(DockSide.LEFT, petX = 10, petWidth = 300, bubbleWidth = 400, screenWidth = 1080, gapPx = 8)
        assertEquals(318, leftBubble)

        val rightBubble = EdgeDocking.bubbleX(DockSide.RIGHT, petX = 770, petWidth = 300, bubbleWidth = 400, screenWidth = 1080, gapPx = 8)
        assertEquals(362, rightBubble)
    }
}
