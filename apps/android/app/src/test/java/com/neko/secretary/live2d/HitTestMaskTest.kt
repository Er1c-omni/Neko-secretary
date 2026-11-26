package com.neko.secretary.live2d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class HitTestMaskTest {

    @Test
    fun `fromGlReadPixels flips Y axis from bottom-left GL origin to top-left view origin`() {
        val width = 32
        val height = 32
        val rgba = ByteBuffer.allocateDirect(width * height * 4)

        // Paint a single opaque pixel at GL (x=4, glY=28) -> View (x=4, viewY=3), i.e. top-left cell (0, 0).
        val glY = 28
        val x = 4
        val offset = (glY * width + x) * 4
        rgba.put(offset + 3, 255.toByte())

        val mask = HitTestMask.fromGlReadPixels(rgba, width, height, cellSize = 8, sampleStride = 1)
        assertTrue(mask.isOpaqueAt(4f, 3f))
        assertFalse(mask.isOpaqueAt(4f, 28f))
        assertEquals(1, mask.opaqueCellCount)
        assertEquals(PixelRect(0, 0, 8, 8), mask.opaqueBounds)
    }

    @Test
    fun `transparent pixels below threshold are ignored and 8-neighbor tolerance works`() {
        val width = 32
        val height = 32
        val rgba = ByteBuffer.allocateDirect(width * height * 4)

        // Sub-threshold alpha in top-left (ignored)
        val lowOffset = ((height - 1 - 2) * width + 2) * 4
        rgba.put(lowOffset + 3, 10.toByte())

        // Opaque pixel in cell (2, 2) -> view coords x=18, y=18
        val glY = height - 1 - 18
        val opaqueOffset = (glY * width + 18) * 4
        rgba.put(opaqueOffset + 3, 200.toByte())

        val mask = HitTestMask.fromGlReadPixels(rgba, width, height, cellSize = 8, alphaThreshold = 24, sampleStride = 1)
        assertFalse(mask.isOpaqueAt(2f, 2f))
        assertFalse(mask.isNearOpaque(2f, 2f))

        // Exact cell hit
        assertTrue(mask.isOpaqueAt(18f, 18f))
        // Adjacent cell (1, 1) -> view (12, 12) is not directly opaque, but isNearOpaque for fat fingers
        assertFalse(mask.isOpaqueAt(12f, 12f))
        assertTrue(mask.isNearOpaque(12f, 12f))
        // Out of bounds is always false
        assertFalse(mask.isNearOpaque(-1f, 18f))
        assertFalse(mask.isNearOpaque(40f, 18f))
    }

    @Test
    fun `fullyOpaque fallback covers entire viewport`() {
        val mask = HitTestMask.fullyOpaque(100, 120, cellSize = 10)
        assertTrue(mask.isOpaqueAt(0f, 0f))
        assertTrue(mask.isOpaqueAt(99f, 119f))
        assertEquals(PixelRect(0, 0, 100, 120), mask.opaqueBounds)
    }
}
