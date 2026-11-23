package com.example.ui.components

import com.example.service.HudBoundsConstraints
import org.junit.Assert.assertEquals
import org.junit.Test

class HudBoundsCalculatorTest {

    private val defaultConstraints = HudBoundsConstraints(
        minX = 0,
        maxX = 800,
        minY = 50,
        maxY = 1600,
        minWidthPx = 280,
        maxWidthPx = 1000,
        minHeightPx = 60,
        maxHeightPx = 800,
        minSafeRightPx = 1080,
        maxSafeBottomPx = 1920
    )

    @Test
    fun `computeNormalMoveBounds within constraints applies full dx and dy`() {
        val initial = HudWindowBounds(x = 100, y = 200, width = 400, height = 150)
        val result = HudBoundsCalculator.computeNormalMoveBounds(
            initialBounds = initial,
            dx = 50,
            dy = 80,
            constraints = defaultConstraints
        )
        assertEquals(150, result.x)
        assertEquals(280, result.y)
        assertEquals(400, result.width)
        assertEquals(150, result.height)
    }

    @Test
    fun `computeNormalMoveBounds clamps against screen boundaries`() {
        val initial = HudWindowBounds(x = 100, y = 200, width = 400, height = 150)

        // Drag off left and top
        val clampedMin = HudBoundsCalculator.computeNormalMoveBounds(
            initialBounds = initial,
            dx = -500,
            dy = -500,
            constraints = defaultConstraints
        )
        assertEquals(defaultConstraints.minX, clampedMin.x)
        assertEquals(defaultConstraints.minY, clampedMin.y)

        // Drag off right and bottom
        // moveMaxX = 1080 - 400 = 680
        // moveMaxY = 1920 - 150 = 1770
        val clampedMax = HudBoundsCalculator.computeNormalMoveBounds(
            initialBounds = initial,
            dx = 2000,
            dy = 3000,
            constraints = defaultConstraints
        )
        assertEquals(680, clampedMax.x)
        assertEquals(1770, clampedMax.y)
    }

    @Test
    fun `computeEditMoveBounds with MOVE target translates window with clamping`() {
        val initial = HudWindowBounds(x = 100, y = 200, width = 400, height = 150)
        val result = HudBoundsCalculator.computeEditMoveBounds(
            initialBounds = initial,
            dx = 30,
            dy = 40,
            target = TouchTarget.MOVE,
            constraints = defaultConstraints
        )
        assertEquals(130, result.x)
        assertEquals(240, result.y)
        assertEquals(400, result.width)
        assertEquals(150, result.height)
    }

    @Test
    fun `computeEditMoveBounds RESIZE_RIGHT expands width and clamps to maxWidthPx`() {
        val initial = HudWindowBounds(x = 100, y = 200, width = 400, height = 150)
        val resized = HudBoundsCalculator.computeEditMoveBounds(
            initialBounds = initial,
            dx = 100,
            dy = 0,
            target = TouchTarget.RESIZE_RIGHT,
            constraints = defaultConstraints
        )
        assertEquals(100, resized.x)
        assertEquals(200, resized.y)
        assertEquals(500, resized.width)
        assertEquals(150, resized.height)

        // Exceed maxWidthPx (1000)
        val clamped = HudBoundsCalculator.computeEditMoveBounds(
            initialBounds = initial,
            dx = 1500,
            dy = 0,
            target = TouchTarget.RESIZE_RIGHT,
            constraints = defaultConstraints
        )
        // maxSafeRightPx - initialX = 1080 - 100 = 980 (which is < 1000)
        assertEquals(980, clamped.width)
    }

    @Test
    fun `computeEditMoveBounds RESIZE_BOTTOM adjusts height and clamps to minHeightPx`() {
        val initial = HudWindowBounds(x = 100, y = 200, width = 400, height = 150)
        val shrunk = HudBoundsCalculator.computeEditMoveBounds(
            initialBounds = initial,
            dx = 0,
            dy = -200,
            target = TouchTarget.RESIZE_BOTTOM,
            constraints = defaultConstraints
        )
        assertEquals(defaultConstraints.minHeightPx, shrunk.height)
    }

    @Test
    fun `computeEditMoveBounds RESIZE_LEFT adjusts width and moves X while anchoring right edge`() {
        val initial = HudWindowBounds(x = 200, y = 200, width = 400, height = 150)
        val anchorRight = 600

        // Dragging left (dx = -50) expands width by 50 and moves X left by 50
        val expanded = HudBoundsCalculator.computeEditMoveBounds(
            initialBounds = initial,
            dx = -50,
            dy = 0,
            target = TouchTarget.RESIZE_LEFT,
            constraints = defaultConstraints
        )
        assertEquals(450, expanded.width)
        assertEquals(150, expanded.x)
        assertEquals(anchorRight, expanded.x + expanded.width)
    }

    @Test
    fun `computeEditMoveBounds RESIZE_TOP adjusts height and moves Y while anchoring bottom edge`() {
        val initial = HudWindowBounds(x = 200, y = 200, width = 400, height = 150)
        val anchorBottom = 350

        // Dragging up (dy = -30) expands height by 30 and moves Y up by 30
        val expanded = HudBoundsCalculator.computeEditMoveBounds(
            initialBounds = initial,
            dx = 0,
            dy = -30,
            target = TouchTarget.RESIZE_TOP,
            constraints = defaultConstraints
        )
        assertEquals(180, expanded.height)
        assertEquals(170, expanded.y)
        assertEquals(anchorBottom, expanded.y + expanded.height)
    }

    @Test
    fun `computeEditMoveBounds RESIZE_TL moves both X and Y while anchoring bottom-right`() {
        val initial = HudWindowBounds(x = 200, y = 200, width = 400, height = 150)
        val anchorRight = 600
        val anchorBottom = 350

        val resized = HudBoundsCalculator.computeEditMoveBounds(
            initialBounds = initial,
            dx = -40,
            dy = -20,
            target = TouchTarget.RESIZE_TL,
            constraints = defaultConstraints
        )
        assertEquals(440, resized.width)
        assertEquals(170, resized.height)
        assertEquals(160, resized.x)
        assertEquals(180, resized.y)
        assertEquals(anchorRight, resized.x + resized.width)
        assertEquals(anchorBottom, resized.y + resized.height)
    }

    @Test
    fun `computeEditMoveBounds NONE does not alter bounds`() {
        val initial = HudWindowBounds(x = 200, y = 200, width = 400, height = 150)
        val result = HudBoundsCalculator.computeEditMoveBounds(
            initialBounds = initial,
            dx = 50,
            dy = 50,
            target = TouchTarget.NONE,
            constraints = defaultConstraints
        )
        assertEquals(initial, result)
    }
}
