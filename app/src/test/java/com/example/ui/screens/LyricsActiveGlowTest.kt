package com.example.ui.screens

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsActiveGlowTest {

    @Test
    fun `resolveActiveLyricGlowColor lifts dark and saturated accents cleanly with full base alpha`() {
        val darkAccent = Color(0xFF201030).copy(alpha = 0.5f)
        val resolvedDark = resolveActiveLyricGlowColor(darkAccent)
        assertEquals(1f, resolvedDark.alpha, 0.001f)
        assertTrue("Dark accent should be lifted slightly toward white for readability", resolvedDark.red > darkAccent.red)
        assertTrue(resolvedDark.green > darkAccent.green)
        assertTrue(resolvedDark.blue > darkAccent.blue)

        val blushPink = Color(0xFFF48FB1)
        val resolvedPink = resolveActiveLyricGlowColor(blushPink)
        assertEquals(1f, resolvedPink.alpha, 0.001f)
        assertTrue(resolvedPink.red >= blushPink.red)

        val pureWhite = Color.White
        val resolvedWhite = resolveActiveLyricGlowColor(pureWhite)
        assertEquals(1f, resolvedWhite.red, 0.001f)
        assertEquals(1f, resolvedWhite.green, 0.001f)
        assertEquals(1f, resolvedWhite.blue, 0.001f)
    }
}
