package com.example.domain.lyricsengine

import com.example.data.preferences.OverlayAnimationStyle
import com.example.data.preferences.UserPreferences
import org.junit.Assert.*
import org.junit.Test

class FadeAndBlurRevealAnimationTest {

    private fun calculateTransitionDuration(baseDuration: Long, speed: Float): Long {
        val clampedSpeed = speed.coerceIn(0.3f, 3.0f)
        return kotlin.math.round(baseDuration / clampedSpeed.toDouble()).toLong().coerceIn(100L, 2000L)
    }

    @Test
    fun testAllSixAnimationStylesAreRegistered() {
        val styleNames = OverlayAnimationStyle.entries.map { it.name }
        assertTrue(styleNames.contains("KARAOKE"))
        assertTrue(styleNames.contains("SMOOTH_SLIDE"))
        assertTrue(styleNames.contains("FADE"))
        assertTrue(styleNames.contains("BLUR_REVEAL"))
        assertTrue(styleNames.contains("TYPEWRITER"))
        assertTrue(styleNames.contains("INSTANT"))
        assertEquals(6, OverlayAnimationStyle.entries.size)

        val fadeStyle = OverlayAnimationStyle.valueOf("FADE")
        assertEquals("Fade In / Fade Out", fadeStyle.title)

        val blurStyle = OverlayAnimationStyle.valueOf("BLUR_REVEAL")
        assertEquals("Blur Reveal", blurStyle.title)
    }

    @Test
    fun testDefaultSpeedsAreOnePointZero() {
        val prefs = UserPreferences()
        assertEquals(1.0f, prefs.fadeSpeed, 0.001f)
        assertEquals(1.0f, prefs.blurRevealSpeed, 0.001f)
    }

    @Test
    fun testFadeSpeedPresetsCalculateExpectedDurations() {
        val baseDuration = 360L

        // Preset 0.5x — Slow
        val slow = calculateTransitionDuration(baseDuration, 0.5f)
        assertEquals(720L, slow)

        // Preset 0.7x — Relaxed
        val relaxed = calculateTransitionDuration(baseDuration, 0.7f)
        assertEquals(514L, relaxed)

        // Preset 1.0x — Normal
        val normal = calculateTransitionDuration(baseDuration, 1.0f)
        assertEquals(360L, normal)

        // Preset 1.5x — Fast
        val fast = calculateTransitionDuration(baseDuration, 1.5f)
        assertEquals(240L, fast)

        // Relative ordering: slower speed yields longer fade
        assertTrue(slow > relaxed)
        assertTrue(relaxed > normal)
        assertTrue(normal > fast)
    }

    @Test
    fun testBlurRevealSpeedPresetsCalculateExpectedDurations() {
        val baseDuration = 400L

        // Preset 0.5x — Slow
        val slow = calculateTransitionDuration(baseDuration, 0.5f)
        assertEquals(800L, slow)

        // Preset 0.7x — Relaxed
        val relaxed = calculateTransitionDuration(baseDuration, 0.7f)
        assertEquals(571L, relaxed)

        // Preset 1.0x — Normal
        val normal = calculateTransitionDuration(baseDuration, 1.0f)
        assertEquals(400L, normal)

        // Preset 1.5x — Fast
        val fast = calculateTransitionDuration(baseDuration, 1.5f)
        assertEquals(267L, fast)

        // Relative ordering: slower speed yields longer blur-to-sharp transition
        assertTrue(slow > relaxed)
        assertTrue(relaxed > normal)
        assertTrue(normal > fast)
    }

    @Test
    fun testSpeedSettingsAreCompletelyIndependent() {
        var prefs = UserPreferences(
            typewriterSpeed = 0.5f,
            smoothTransitionSpeed = 0.7f,
            fadeSpeed = 1.0f,
            blurRevealSpeed = 1.5f
        )
        assertEquals(0.5f, prefs.typewriterSpeed, 0.001f)
        assertEquals(0.7f, prefs.smoothTransitionSpeed, 0.001f)
        assertEquals(1.0f, prefs.fadeSpeed, 0.001f)
        assertEquals(1.5f, prefs.blurRevealSpeed, 0.001f)

        // Modify fadeSpeed only
        prefs = prefs.copy(fadeSpeed = 0.5f)
        assertEquals(0.5f, prefs.typewriterSpeed, 0.001f)
        assertEquals(0.7f, prefs.smoothTransitionSpeed, 0.001f)
        assertEquals(0.5f, prefs.fadeSpeed, 0.001f)
        assertEquals(1.5f, prefs.blurRevealSpeed, 0.001f)

        // Modify blurRevealSpeed only
        prefs = prefs.copy(blurRevealSpeed = 0.7f)
        assertEquals(0.5f, prefs.typewriterSpeed, 0.001f)
        assertEquals(0.7f, prefs.smoothTransitionSpeed, 0.001f)
        assertEquals(0.5f, prefs.fadeSpeed, 0.001f)
        assertEquals(0.7f, prefs.blurRevealSpeed, 0.001f)
    }

    @Test
    fun testPreservesSpeedPreferencesWhenSwitchingStyles() {
        var prefs = UserPreferences(
            animationStyle = OverlayAnimationStyle.FADE.name,
            fadeSpeed = 0.5f,
            blurRevealSpeed = 0.7f,
            smoothTransitionSpeed = 1.5f
        )

        // Switch to Blur Reveal
        prefs = prefs.copy(animationStyle = OverlayAnimationStyle.BLUR_REVEAL.name)
        assertEquals(0.5f, prefs.fadeSpeed, 0.001f)
        assertEquals(0.7f, prefs.blurRevealSpeed, 0.001f)
        assertEquals(1.5f, prefs.smoothTransitionSpeed, 0.001f)

        // Switch to Karaoke
        prefs = prefs.copy(animationStyle = OverlayAnimationStyle.KARAOKE.name)
        assertEquals(0.5f, prefs.fadeSpeed, 0.001f)
        assertEquals(0.7f, prefs.blurRevealSpeed, 0.001f)

        // Switch to Instant
        prefs = prefs.copy(animationStyle = OverlayAnimationStyle.INSTANT.name)
        assertEquals(0.5f, prefs.fadeSpeed, 0.001f)
        assertEquals(0.7f, prefs.blurRevealSpeed, 0.001f)

        // Switch back to Fade
        prefs = prefs.copy(animationStyle = OverlayAnimationStyle.FADE.name)
        assertEquals(0.5f, prefs.fadeSpeed, 0.001f)

        // Switch back to Blur Reveal
        prefs = prefs.copy(animationStyle = OverlayAnimationStyle.BLUR_REVEAL.name)
        assertEquals(0.7f, prefs.blurRevealSpeed, 0.001f)
    }

    @Test
    fun testExtremeValuesClamping() {
        val baseDuration = 360L
        val minSpeedDuration = calculateTransitionDuration(baseDuration, 0.01f)
        assertEquals(1200L, minSpeedDuration)

        val maxSpeedDuration = calculateTransitionDuration(baseDuration, 10.0f)
        assertEquals(120L, maxSpeedDuration)
    }
}
