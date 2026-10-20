package com.example.domain.lyricsengine

import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.WordTiming
import com.example.data.preferences.OverlayAnimationStyle
import com.example.data.preferences.UserPreferences
import org.junit.Assert.*
import org.junit.Test

class AnimationPerformanceAndConsistencyTest {

    @Test
    fun testAllAnimationStylesMaintainIndependentSpeedSettings() {
        val prefs = UserPreferences(
            typewriterSpeed = 0.5f,
            smoothTransitionSpeed = 0.7f,
            fadeSpeed = 1.0f,
            blurRevealSpeed = 1.5f
        )

        assertEquals(0.5f, prefs.typewriterSpeed, 0.001f)
        assertEquals(0.7f, prefs.smoothTransitionSpeed, 0.001f)
        assertEquals(1.0f, prefs.fadeSpeed, 0.001f)
        assertEquals(1.5f, prefs.blurRevealSpeed, 0.001f)

        // Switching animation style preserves all individual speed settings
        for (style in OverlayAnimationStyle.entries) {
            val updated = prefs.copy(animationStyle = style.name)
            assertEquals(style.name, updated.animationStyle)
            assertEquals(0.5f, updated.typewriterSpeed, 0.001f)
            assertEquals(0.7f, updated.smoothTransitionSpeed, 0.001f)
            assertEquals(1.0f, updated.fadeSpeed, 0.001f)
            assertEquals(1.5f, updated.blurRevealSpeed, 0.001f)
        }
    }

    @Test
    fun testLyricDelayAdjustmentOffsetsActiveLineSynchronisation() {
        val lines = listOf(
            LyricLine(timeMs = 1000L, text = "Line 1"),
            LyricLine(timeMs = 4000L, text = "Line 2"),
            LyricLine(timeMs = 7000L, text = "Line 3")
        )
        val lyrics = ParsedLyrics(
            title = "Test",
            artist = "Artist",
            isSynced = true,
            lines = lines,
            plainText = "",
            source = "Test"
        )

        // At 3500ms with 0ms offset -> Line 1 is active
        assertEquals(0, lyrics.findActiveIndex(3500L, trackOffsetMs = 0L))

        // Adding +600ms offset (3500 + 600 = 4100ms) -> Line 2 becomes active earlier
        assertEquals(1, lyrics.findActiveIndex(3500L, trackOffsetMs = 600L))

        // Negative -600ms offset (4200 - 600 = 3600ms) -> Line 1 stays active
        assertEquals(0, lyrics.findActiveIndex(4200L, trackOffsetMs = -600L))
        assertEquals(1, lyrics.findActiveIndex(4200L, trackOffsetMs = 0L))
    }

    @Test
    fun testWordProgressCalculationsRemainAccurateAcrossPlaybackPositions() {
        val word = WordTiming(word = "test", startMs = 2000L, endMs = 3000L)

        // Before word starts
        assertEquals(0.0f, LyricsTimingEngine.calculateWordProgress(word, 1500L), 0.001f)
        assertEquals(0.0f, LyricsTimingEngine.calculateWordProgress(word, 2000L), 0.001f)

        // Mid-word (halfway)
        assertEquals(0.5f, LyricsTimingEngine.calculateWordProgress(word, 2500L), 0.001f)

        // Word completed
        assertEquals(1.0f, LyricsTimingEngine.calculateWordProgress(word, 3000L), 0.001f)
        assertEquals(1.0f, LyricsTimingEngine.calculateWordProgress(word, 4000L), 0.001f)
    }

    @Test
    fun testSingleLineModeScrollBoundaryCalculation() {
        val availableWidth = 500f

        // Case A: Short text fits within available width -> maxScrollX must be 0 (no scrolling needed)
        val shortTextWidth = 350f
        val maxScrollXShort = (shortTextWidth - availableWidth + 24f).coerceAtLeast(0f)
        assertEquals(0f, maxScrollXShort, 0.001f)

        // Case B: Long text exceeds available width -> maxScrollX > 0 (scrolling activated)
        val longTextWidth = 800f
        val maxScrollXLong = (longTextWidth - availableWidth + 24f).coerceAtLeast(0f)
        assertTrue(maxScrollXLong > 0f)
        assertEquals(324f, maxScrollXLong, 0.001f)
    }

    @Test
    fun testPlaybackSeekingHandlesArbitraryJumpsCleanly() {
        val lines = (0..10).map { i ->
            LyricLine(timeMs = i * 2000L, text = "Lyric Line $i")
        }
        val lyrics = ParsedLyrics(
            title = "Test",
            artist = "Artist",
            isSynced = true,
            lines = lines,
            plainText = "",
            source = "Test"
        )

        // Seek forward to middle
        assertEquals(5, lyrics.findActiveIndex(10500L))

        // Seek backward to near beginning
        assertEquals(1, lyrics.findActiveIndex(2500L))

        // Seek to end
        assertEquals(10, lyrics.findActiveIndex(21000L))

        // Seek before start
        assertEquals(0, lyrics.findActiveIndex(500L))
        assertEquals(-1, lyrics.findActiveIndex(-100L))
    }
}
