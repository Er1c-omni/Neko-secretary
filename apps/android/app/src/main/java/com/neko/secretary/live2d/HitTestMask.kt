package com.neko.secretary.live2d

import java.nio.ByteBuffer

/** Axis-aligned pixel rect in view coordinates (top-left origin). */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/**
 * Coarse alpha occupancy grid of the last rendered frame, used to decide whether a touch landed
 * on the character (consume it) or on transparent canvas (let it fall through to the app below).
 *
 * Cells are [cellSize] px squares in *view* space (top-left origin). Built from a glReadPixels
 * RGBA buffer, whose origin is bottom-left, so rows are flipped during construction.
 */
class HitTestMask private constructor(
    val viewWidth: Int,
    val viewHeight: Int,
    val cellSize: Int,
    private val cols: Int,
    private val rows: Int,
    private val cells: BooleanArray,
    val opaqueBounds: PixelRect
) {
    fun isOpaqueAt(x: Float, y: Float): Boolean {
        if (x < 0f || y < 0f || x >= viewWidth || y >= viewHeight) return false
        val cx = (x.toInt() / cellSize).coerceIn(0, cols - 1)
        val cy = (y.toInt() / cellSize).coerceIn(0, rows - 1)
        return cells[cy * cols + cx]
    }

    /** Touch tolerance: opaque if the cell or any 8-neighbour is opaque (fingers are fat). */
    fun isNearOpaque(x: Float, y: Float): Boolean {
        if (x < 0f || y < 0f || x >= viewWidth || y >= viewHeight) return false
        val cx = x.toInt() / cellSize
        val cy = y.toInt() / cellSize
        for (dy in -1..1) for (dx in -1..1) {
            val nx = cx + dx
            val ny = cy + dy
            if (nx in 0 until cols && ny in 0 until rows && cells[ny * cols + nx]) return true
        }
        return false
    }

    val opaqueCellCount: Int get() = cells.count { it }

    companion object {
        const val DEFAULT_CELL_SIZE = 8
        const val DEFAULT_ALPHA_THRESHOLD = 24

        /** A mask that treats the whole canvas as hittable (used before the first readback). */
        fun fullyOpaque(width: Int, height: Int, cellSize: Int = DEFAULT_CELL_SIZE): HitTestMask {
            val cols = ceilDiv(width, cellSize).coerceAtLeast(1)
            val rows = ceilDiv(height, cellSize).coerceAtLeast(1)
            return HitTestMask(width, height, cellSize, cols, rows, BooleanArray(cols * rows) { true },
                PixelRect(0, 0, width, height))
        }

        /**
         * @param rgba tightly packed RGBA8888 pixels as returned by glReadPixels (bottom-left origin).
         * Samples a sparse lattice inside each cell (every [sampleStride] px) to keep the scan cheap.
         */
        fun fromGlReadPixels(
            rgba: ByteBuffer,
            width: Int,
            height: Int,
            cellSize: Int = DEFAULT_CELL_SIZE,
            alphaThreshold: Int = DEFAULT_ALPHA_THRESHOLD,
            sampleStride: Int = 2
        ): HitTestMask {
            require(width > 0 && height > 0) { "invalid size ${width}x$height" }
            require(rgba.capacity() >= width * height * 4) { "buffer too small" }
            val cols = ceilDiv(width, cellSize)
            val rows = ceilDiv(height, cellSize)
            val cells = BooleanArray(cols * rows)
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var maxY = Int.MIN_VALUE
            val stride = sampleStride.coerceAtLeast(1)
            var glY = 0
            while (glY < height) {
                val viewY = height - 1 - glY
                val cy = viewY / cellSize
                val rowBase = glY * width * 4
                var x = 0
                while (x < width) {
                    val cx = x / cellSize
                    val idx = cy * cols + cx
                    if (!cells[idx]) {
                        val alpha = rgba.get(rowBase + x * 4 + 3).toInt() and 0xFF
                        if (alpha >= alphaThreshold) {
                            cells[idx] = true
                            if (cx < minX) minX = cx
                            if (cy < minY) minY = cy
                            if (cx > maxX) maxX = cx
                            if (cy > maxY) maxY = cy
                        }
                    }
                    x += stride
                }
                glY += stride
            }
            val bounds = if (maxX < minX) {
                PixelRect(0, 0, 0, 0)
            } else {
                PixelRect(
                    left = minX * cellSize,
                    top = minY * cellSize,
                    right = ((maxX + 1) * cellSize).coerceAtMost(width),
                    bottom = ((maxY + 1) * cellSize).coerceAtMost(height)
                )
            }
            return HitTestMask(width, height, cellSize, cols, rows, cells, bounds)
        }

        private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b
    }
}
