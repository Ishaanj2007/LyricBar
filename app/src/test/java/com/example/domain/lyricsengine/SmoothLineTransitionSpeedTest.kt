package com.example.domain.lyricsengine

import com.example.data.preferences.OverlayAnimationStyle
import com.example.data.preferences.UserPreferences
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class SmoothLineTransitionSpeedTest {

    private fun calculateTransitionDuration(baseDuration: Long, speed: Float): Long {
        val clampedSpeed = speed.coerceIn(0.3f, 3.0f)
        return kotlin.math.round(baseDuration / clampedSpeed.toDouble()).toLong().coerceIn(100L, 2000L)
    }

    @Test
    fun testDefaultSpeedIsNormalOnePointZero() {
        val prefs = UserPreferences()
        assertEquals(1.0f, prefs.smoothTransitionSpeed, 0.001f)
    }

    @Test
    fun testAllFourPresetsCalculateExpectedDurations() {
        val baseDuration = 300L

        // Preset 0.5x — Slow
        val slowDuration = calculateTransitionDuration(baseDuration, 0.5f)
        assertEquals(600L, slowDuration)

        // Preset 0.7x — Relaxed
        val relaxedDuration = calculateTransitionDuration(baseDuration, 0.7f)
        assertEquals(429L, relaxedDuration)

        // Preset 1.0x — Normal
        val normalDuration = calculateTransitionDuration(baseDuration, 1.0f)
        assertEquals(300L, normalDuration)

        // Preset 1.5x — Fast
        val fastDuration = calculateTransitionDuration(baseDuration, 1.5f)
        assertEquals(200L, fastDuration)
    }

    @Test
    fun testSpeedRelativeRelationships() {
        val baseDuration = 300L
        val slow = calculateTransitionDuration(baseDuration, 0.5f)
        val relaxed = calculateTransitionDuration(baseDuration, 0.7f)
        val normal = calculateTransitionDuration(baseDuration, 1.0f)
        val fast = calculateTransitionDuration(baseDuration, 1.5f)

        // Slower speed yields longer duration
        assertTrue(slow > relaxed)
        assertTrue(relaxed > normal)
        assertTrue(normal > fast)

        // 0.5x duration should be exactly 2x 1.0x duration
        assertEquals(slow, normal * 2)
    }

    @Test
    fun testIndependenceFromTypewriterSpeed() {
        var prefs = UserPreferences(
            typewriterSpeed = 0.5f,
            smoothTransitionSpeed = 1.5f
        )
        assertEquals(0.5f, prefs.typewriterSpeed, 0.001f)
        assertEquals(1.5f, prefs.smoothTransitionSpeed, 0.001f)

        // Changing typewriter speed does not affect smooth transition speed
        prefs = prefs.copy(typewriterSpeed = 2.0f)
        assertEquals(2.0f, prefs.typewriterSpeed, 0.001f)
        assertEquals(1.5f, prefs.smoothTransitionSpeed, 0.001f)

        // Changing smooth transition speed does not affect typewriter speed
        prefs = prefs.copy(smoothTransitionSpeed = 0.7f)
        assertEquals(2.0f, prefs.typewriterSpeed, 0.001f)
        assertEquals(0.7f, prefs.smoothTransitionSpeed, 0.001f)
    }

    @Test
    fun testPreservesSpeedPreferenceWhenSwitchingAnimations() {
        var prefs = UserPreferences(
            animationStyle = OverlayAnimationStyle.SMOOTH_SLIDE.name,
            smoothTransitionSpeed = 0.7f
        )
        assertEquals(0.7f, prefs.smoothTransitionSpeed, 0.001f)

        // Switch to Apple-style Karaoke
        prefs = prefs.copy(animationStyle = OverlayAnimationStyle.KARAOKE.name)
        assertEquals(OverlayAnimationStyle.KARAOKE.name, prefs.animationStyle)
        assertEquals(0.7f, prefs.smoothTransitionSpeed, 0.001f)

        // Switch to Typewriter
        prefs = prefs.copy(animationStyle = OverlayAnimationStyle.TYPEWRITER.name)
        assertEquals(OverlayAnimationStyle.TYPEWRITER.name, prefs.animationStyle)
        assertEquals(0.7f, prefs.smoothTransitionSpeed, 0.001f)

        // Switch back to Smooth Line Transition: speed setting is retained
        prefs = prefs.copy(animationStyle = OverlayAnimationStyle.SMOOTH_SLIDE.name)
        assertEquals(OverlayAnimationStyle.SMOOTH_SLIDE.name, prefs.animationStyle)
        assertEquals(0.7f, prefs.smoothTransitionSpeed, 0.001f)
    }

    @Test
    fun testExtremeValuesClamping() {
        val baseDuration = 300L
        // Extreme low speed clamped to 0.3f
        val tooLow = calculateTransitionDuration(baseDuration, 0.01f)
        assertEquals(1000L, tooLow)

        // Extreme high speed clamped to 3.0f
        val tooHigh = calculateTransitionDuration(baseDuration, 10.0f)
        assertEquals(100L, tooHigh)
    }
}
