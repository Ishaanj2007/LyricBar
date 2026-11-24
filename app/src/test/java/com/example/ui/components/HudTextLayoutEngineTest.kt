package com.example.ui.components

import com.example.data.model.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudTextLayoutEngineTest {

    // Simple fixed-width text measurer: 10 pixels per character for predictable testing
    private val charWidthPx = 10f
    private val measureText: (String) -> Float = { str -> str.length * charWidthPx }
    private val spaceWidth = 10f
    private val fontAscent = -24f
    private val fontDescent = 8f

    @Test
    fun `computeLayout returns empty result for blank text or non-positive width`() {
        val emptyTextResult = HudTextLayoutEngine.computeLayout(
            rawText = "",
            words = null,
            isWordSynced = false,
            lineStartMs = 0L,
            lineEndMs = 0L,
            nextLyricTimestampMs = null,
            singleLineMode = false,
            adaptiveScrollingEnabled = false,
            textAlignMode = "CENTER",
            viewWidth = 400,
            viewHeight = 200,
            contentHorizontalPaddingPx = 20f,
            spaceWidth = spaceWidth,
            measureText = measureText,
            fontAscent = fontAscent,
            fontDescent = fontDescent,
            hudBackdropEnabled = false,
            backdropVerticalPaddingPx = 0f
        )
        assertTrue(emptyTextResult.lines.isEmpty())
        assertEquals(0, emptyTextResult.totalGraphemes)
        assertEquals(0, emptyTextResult.totalChars)
        assertEquals(0f, emptyTextResult.maxScrollX, 0.001f)
        assertEquals(0f, emptyTextResult.maxScrollY, 0.001f)

        val zeroWidthResult = HudTextLayoutEngine.computeLayout(
            rawText = "Hello World",
            words = null,
            isWordSynced = false,
            lineStartMs = 0L,
            lineEndMs = 0L,
            nextLyricTimestampMs = null,
            singleLineMode = false,
            adaptiveScrollingEnabled = false,
            textAlignMode = "CENTER",
            viewWidth = 0,
            viewHeight = 200,
            contentHorizontalPaddingPx = 20f,
            spaceWidth = spaceWidth,
            measureText = measureText,
            fontAscent = fontAscent,
            fontDescent = fontDescent,
            hudBackdropEnabled = false,
            backdropVerticalPaddingPx = 0f
        )
        assertTrue(zeroWidthResult.lines.isEmpty())
    }

    @Test
    fun `multi-line mode wraps words when exceeding available width`() {
        // availableWidth = 400 - 2 * 20 = 360px
        // Words: "One" (30px), "Two" (30px), "Three" (50px), "Four" (40px)
        // With space = 10px:
        // Line 1: "One" (30) + " Two" (40) + " Three" (60) = 130px <= 140px limit
        val result = HudTextLayoutEngine.computeLayout(
            rawText = "One Two Three Four Five Six Seven Eight Nine Ten",
            words = null,
            isWordSynced = false,
            lineStartMs = 1000L,
            lineEndMs = 5000L,
            nextLyricTimestampMs = null,
            singleLineMode = false,
            adaptiveScrollingEnabled = false,
            textAlignMode = "LEFT",
            viewWidth = 200, // availableWidth = 200 - 40 = 160px
            viewHeight = 400,
            contentHorizontalPaddingPx = 20f,
            spaceWidth = spaceWidth,
            measureText = measureText,
            fontAscent = fontAscent,
            fontDescent = fontDescent,
            hudBackdropEnabled = false,
            backdropVerticalPaddingPx = 0f
        )

        assertTrue("Expected multiple wrapped lines", result.lines.size > 1)
        for (line in result.lines) {
            assertTrue("Line width must not exceed available width 160px", line.lineWidth <= 160f)
        }
        assertEquals(0f, result.maxScrollX, 0.001f)
    }

    @Test
    fun `single-line mode keeps all words on one line and computes maxScrollX`() {
        val result = HudTextLayoutEngine.computeLayout(
            rawText = "The quick brown fox jumps over the lazy dog",
            words = null,
            isWordSynced = false,
            lineStartMs = 0L,
            lineEndMs = 5000L,
            nextLyricTimestampMs = null,
            singleLineMode = true,
            adaptiveScrollingEnabled = true,
            textAlignMode = "LEFT",
            viewWidth = 200, // available = 160px, text is 430px+
            viewHeight = 100,
            contentHorizontalPaddingPx = 20f,
            spaceWidth = spaceWidth,
            measureText = measureText,
            fontAscent = fontAscent,
            fontDescent = fontDescent,
            hudBackdropEnabled = false,
            backdropVerticalPaddingPx = 0f
        )

        assertEquals("Must produce exactly 1 line in single-line mode", 1, result.lines.size)
        assertTrue("maxScrollX should be positive when line exceeds available width", result.maxScrollX > 0f)
    }

    @Test
    fun `text alignment correctly sets word X coordinates`() {
        // availableWidth = 300 - 40 = 260px.
        // "Hello" is 50px wide. contentLeft = 20px, contentRight = 280px.
        val leftResult = HudTextLayoutEngine.computeLayout(
            rawText = "Hello",
            words = null,
            isWordSynced = false,
            lineStartMs = 0L,
            lineEndMs = 1000L,
            nextLyricTimestampMs = null,
            singleLineMode = false,
            adaptiveScrollingEnabled = false,
            textAlignMode = "LEFT",
            viewWidth = 300,
            viewHeight = 100,
            contentHorizontalPaddingPx = 20f,
            spaceWidth = spaceWidth,
            measureText = measureText,
            fontAscent = fontAscent,
            fontDescent = fontDescent,
            hudBackdropEnabled = false,
            backdropVerticalPaddingPx = 0f
        )
        assertEquals(20f, leftResult.lines[0].words[0].x, 0.001f)

        val rightResult = HudTextLayoutEngine.computeLayout(
            rawText = "Hello",
            words = null,
            isWordSynced = false,
            lineStartMs = 0L,
            lineEndMs = 1000L,
            nextLyricTimestampMs = null,
            singleLineMode = false,
            adaptiveScrollingEnabled = false,
            textAlignMode = "RIGHT",
            viewWidth = 300,
            viewHeight = 100,
            contentHorizontalPaddingPx = 20f,
            spaceWidth = spaceWidth,
            measureText = measureText,
            fontAscent = fontAscent,
            fontDescent = fontDescent,
            hudBackdropEnabled = false,
            backdropVerticalPaddingPx = 0f
        )
        // contentRight = 280, lineWidth = 50 -> startX = 230
        assertEquals(230f, rightResult.lines[0].words[0].x, 0.001f)

        val centerResult = HudTextLayoutEngine.computeLayout(
            rawText = "Hello",
            words = null,
            isWordSynced = false,
            lineStartMs = 0L,
            lineEndMs = 1000L,
            nextLyricTimestampMs = null,
            singleLineMode = false,
            adaptiveScrollingEnabled = false,
            textAlignMode = "CENTER",
            viewWidth = 300,
            viewHeight = 100,
            contentHorizontalPaddingPx = 20f,
            spaceWidth = spaceWidth,
            measureText = measureText,
            fontAscent = fontAscent,
            fontDescent = fontDescent,
            hudBackdropEnabled = false,
            backdropVerticalPaddingPx = 0f
        )
        // startX = 20 + (260 - 50) / 2 = 125
        assertEquals(125f, centerResult.lines[0].words[0].x, 0.001f)
    }

    @Test
    fun `vertical positioning centers text when it fits and calculates maxScrollY when it overflows`() {
        // Fits vertically:
        val fittingResult = HudTextLayoutEngine.computeLayout(
            rawText = "Short text",
            words = null,
            isWordSynced = false,
            lineStartMs = 0L,
            lineEndMs = 1000L,
            nextLyricTimestampMs = null,
            singleLineMode = false,
            adaptiveScrollingEnabled = false,
            textAlignMode = "CENTER",
            viewWidth = 300,
            viewHeight = 300,
            contentHorizontalPaddingPx = 20f,
            spaceWidth = spaceWidth,
            measureText = measureText,
            fontAscent = fontAscent,
            fontDescent = fontDescent,
            hudBackdropEnabled = false,
            backdropVerticalPaddingPx = 0f
        )
        assertEquals(0f, fittingResult.maxScrollY, 0.001f)

        // Overflowing text:
        val overflowingResult = HudTextLayoutEngine.computeLayout(
            rawText = "Word1 Word2 Word3 Word4 Word5 Word6 Word7 Word8 Word9 Word10 Word11 Word12",
            words = null,
            isWordSynced = false,
            lineStartMs = 0L,
            lineEndMs = 10000L,
            nextLyricTimestampMs = null,
            singleLineMode = false,
            adaptiveScrollingEnabled = false,
            textAlignMode = "CENTER",
            viewWidth = 120, // narrow view triggers many wrapped lines
            viewHeight = 80,  // short view triggers vertical overflow
            contentHorizontalPaddingPx = 10f,
            spaceWidth = spaceWidth,
            measureText = measureText,
            fontAscent = fontAscent,
            fontDescent = fontDescent,
            hudBackdropEnabled = false,
            backdropVerticalPaddingPx = 0f
        )
        assertTrue("Expected vertical scroll overflow", overflowingResult.maxScrollY > 0f)
    }

    @Test
    fun `calculateVerticalScrollTarget centers the active line within viewport`() {
        val layout = HudTextLayoutEngine.computeLayout(
            rawText = "Line1 Word2 Word3 Word4 Word5 Word6 Word7 Word8 Word9 Word10 Word11 Word12",
            words = null,
            isWordSynced = false,
            lineStartMs = 1000L,
            lineEndMs = 5000L,
            nextLyricTimestampMs = null,
            singleLineMode = false,
            adaptiveScrollingEnabled = false,
            textAlignMode = "LEFT",
            viewWidth = 120,
            viewHeight = 80,
            contentHorizontalPaddingPx = 10f,
            spaceWidth = spaceWidth,
            measureText = measureText,
            fontAscent = fontAscent,
            fontDescent = fontDescent,
            hudBackdropEnabled = false,
            backdropVerticalPaddingPx = 0f
        )

        // At beginning of line:
        val startScrollY = HudTextLayoutEngine.calculateVerticalScrollTarget(
            posMs = 1000L,
            lines = layout.lines,
            maxScrollY = layout.maxScrollY,
            viewHeight = 80,
            isWordSynced = false,
            words = null,
            lineStartMs = 1000L,
            lineEndMs = 5000L,
            nextLyricTimestampMs = null,
            fontAscent = fontAscent,
            fontDescent = fontDescent
        )
        assertTrue("startScrollY must be non-negative", startScrollY >= 0f)
        assertTrue("startScrollY must not exceed maxScrollY", startScrollY <= layout.maxScrollY)

        // At end of line:
        val endScrollY = HudTextLayoutEngine.calculateVerticalScrollTarget(
            posMs = 4900L,
            lines = layout.lines,
            maxScrollY = layout.maxScrollY,
            viewHeight = 80,
            isWordSynced = false,
            words = null,
            lineStartMs = 1000L,
            lineEndMs = 5000L,
            nextLyricTimestampMs = null,
            fontAscent = fontAscent,
            fontDescent = fontDescent
        )
        assertTrue("Scroll should advance over time", endScrollY >= startScrollY)
        assertTrue("endScrollY must not exceed maxScrollY", endScrollY <= layout.maxScrollY)
    }

    @Test
    fun `calculateHorizontalScrollTarget karaoke mode follows singing progress`() {
        val words = listOf(
            WordTiming("Hello", 1000L, 2000L),
            WordTiming("world", 2000L, 3000L),
            WordTiming("of", 3000L, 3500L),
            WordTiming("music", 3500L, 5000L)
        )
        val layout = HudTextLayoutEngine.computeLayout(
            rawText = "Hello world of music",
            words = words,
            isWordSynced = true,
            lineStartMs = 1000L,
            lineEndMs = 5000L,
            nextLyricTimestampMs = 6000L,
            singleLineMode = true,
            adaptiveScrollingEnabled = true,
            textAlignMode = "LEFT",
            viewWidth = 100, // force overflow
            viewHeight = 50,
            contentHorizontalPaddingPx = 10f,
            spaceWidth = spaceWidth,
            measureText = measureText,
            fontAscent = fontAscent,
            fontDescent = fontDescent,
            hudBackdropEnabled = false,
            backdropVerticalPaddingPx = 0f
        )

        // Before start
        val beforeStart = HudTextLayoutEngine.calculateHorizontalScrollTarget(
            posMs = 500L,
            line = layout.lines[0],
            singleLineMode = true,
            adaptiveScrollingEnabled = true,
            maxScrollX = layout.maxScrollX,
            animationStyle = "KARAOKE",
            isWordSynced = true,
            lineStartMs = 1000L,
            lineEndMs = 5000L,
            nextLyricTimestampMs = 6000L,
            viewWidth = 100,
            contentHorizontalPaddingPx = 10f,
            karaokeGlowSpeed = 1f,
            typewriterSpeed = 1f,
            scrollSpeedMultiplier = 1f
        )
        assertEquals(0f, beforeStart, 0.001f)

        // Past end
        val pastEnd = HudTextLayoutEngine.calculateHorizontalScrollTarget(
            posMs = 5500L,
            line = layout.lines[0],
            singleLineMode = true,
            adaptiveScrollingEnabled = true,
            maxScrollX = layout.maxScrollX,
            animationStyle = "KARAOKE",
            isWordSynced = true,
            lineStartMs = 1000L,
            lineEndMs = 5000L,
            nextLyricTimestampMs = 6000L,
            viewWidth = 100,
            contentHorizontalPaddingPx = 10f,
            karaokeGlowSpeed = 1f,
            typewriterSpeed = 1f,
            scrollSpeedMultiplier = 1f
        )
        assertEquals(layout.maxScrollX, pastEnd, 0.001f)
    }
}
