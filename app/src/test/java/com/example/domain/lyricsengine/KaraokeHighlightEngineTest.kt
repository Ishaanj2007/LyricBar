package com.example.domain.lyricsengine

import com.example.data.model.LyricLine
import com.example.data.model.WordTiming
import org.junit.Assert.*
import org.junit.Test

class KaraokeHighlightEngineTest {

    @Test
    fun testWordProgressDuringNormalPlayback() {
        val word = WordTiming(word = "Hello", startMs = 1000L, endMs = 2000L)
        
        // Before word
        assertEquals(0f, LyricsTimingEngine.calculateWordProgress(word, 500L), 0.001f)
        
        // Word start
        assertEquals(0f, LyricsTimingEngine.calculateWordProgress(word, 1000L), 0.001f)
        
        // Mid word (smooth progression)
        assertEquals(0.5f, LyricsTimingEngine.calculateWordProgress(word, 1500L), 0.001f)
        
        // Word end
        assertEquals(1f, LyricsTimingEngine.calculateWordProgress(word, 2000L), 0.001f)
        
        // After word
        assertEquals(1f, LyricsTimingEngine.calculateWordProgress(word, 2500L), 0.001f)
    }

    @Test
    fun testPauseAndResumeMaintainsCorrectHighlightPosition() {
        val word = WordTiming(word = "Singing", startMs = 3000L, endMs = 5000L)
        
        // Playback reaches 4000ms (50% through the word)
        val progressAtPause = LyricsTimingEngine.calculateWordProgress(word, 4000L)
        assertEquals(0.5f, progressAtPause, 0.001f)
        
        // While paused, position stays at 4000ms
        val progressWhilePaused = LyricsTimingEngine.calculateWordProgress(word, 4000L)
        assertEquals(progressAtPause, progressWhilePaused, 0.001f)
        
        // Resume playback advancing to 4500ms (75%)
        val progressAfterResume = LyricsTimingEngine.calculateWordProgress(word, 4500L)
        assertEquals(0.75f, progressAfterResume, 0.001f)
    }

    @Test
    fun testSeekingForwardAndBackward() {
        val words = listOf(
            WordTiming("Caught", 1000L, 2000L),
            WordTiming("in", 2000L, 2500L),
            WordTiming("a", 2500L, 3000L),
            WordTiming("landslide", 3000L, 5000L)
        )
        val line = LyricLine(timeMs = 1000L, text = "Caught in a landslide", words = words)

        // At 2250ms: "Caught" is completed (1f), "in" is active (0.5f), "a" and "landslide" are 0f
        assertEquals(1f, LyricsTimingEngine.calculateWordProgress(words[0], 2250L), 0.001f)
        assertEquals(0.5f, LyricsTimingEngine.calculateWordProgress(words[1], 2250L), 0.001f)
        assertEquals(0f, LyricsTimingEngine.calculateWordProgress(words[2], 2250L), 0.001f)
        assertEquals(0f, LyricsTimingEngine.calculateWordProgress(words[3], 2250L), 0.001f)

        // Seek forward to 4000ms: only "landslide" is active
        assertEquals(1f, LyricsTimingEngine.calculateWordProgress(words[0], 4000L), 0.001f)
        assertEquals(1f, LyricsTimingEngine.calculateWordProgress(words[1], 4000L), 0.001f)
        assertEquals(1f, LyricsTimingEngine.calculateWordProgress(words[2], 4000L), 0.001f)
        assertEquals(0.5f, LyricsTimingEngine.calculateWordProgress(words[3], 4000L), 0.001f)

        // Seek backward to 1500ms: "Caught" is active (0.5f), subsequent words reset to 0f
        assertEquals(0.5f, LyricsTimingEngine.calculateWordProgress(words[0], 1500L), 0.001f)
        assertEquals(0f, LyricsTimingEngine.calculateWordProgress(words[1], 1500L), 0.001f)
        assertEquals(0f, LyricsTimingEngine.calculateWordProgress(words[2], 1500L), 0.001f)
        assertEquals(0f, LyricsTimingEngine.calculateWordProgress(words[3], 1500L), 0.001f)
    }

    @Test
    fun testOnlyOneLineActiveAtAnyPlaybackPosition() {
        val line1 = LyricLine(
            timeMs = 1000L,
            endTimeMs = 4000L,
            text = "Line One",
            words = listOf(WordTiming("Line", 1000L, 2500L), WordTiming("One", 2500L, 4000L))
        )
        val line2 = LyricLine(
            timeMs = 4000L,
            endTimeMs = 8000L,
            text = "Line Two",
            words = listOf(WordTiming("Line", 4000L, 6000L), WordTiming("Two", 6000L, 8000L))
        )

        // At 2000ms: Line 1 word 0 is active, Line 2 words have not started (0f)
        assertEquals(0.666f, LyricsTimingEngine.calculateWordProgress(line1.words[0], 2000L), 0.01f)
        assertEquals(0f, LyricsTimingEngine.calculateWordProgress(line2.words[0], 2000L), 0.001f)

        // Transition point at 4000ms: Line 1 is finished, Line 2 word 0 begins
        assertEquals(1f, LyricsTimingEngine.calculateWordProgress(line1.words[1], 4000L), 0.001f)
        assertEquals(0f, LyricsTimingEngine.calculateWordProgress(line2.words[0], 4000L), 0.001f)

        // At 5000ms: Line 2 word 0 is active (50%). Line 1 words must never be actively animating
        assertEquals(0.5f, LyricsTimingEngine.calculateWordProgress(line2.words[0], 5000L), 0.001f)
    }

    @Test
    fun testMissingWordTimestampsLrcFallbackProgress() {
        val lrcLine = LyricLine(timeMs = 10000L, endTimeMs = 14000L, text = "Unsynced Line")
        val nextLine = LyricLine(timeMs = 14000L, endTimeMs = 18000L, text = "Next Line")

        // Before line
        assertEquals(0f, LyricsTimingEngine.calculateLineProgress(lrcLine, nextLine, 9000L), 0.001f)

        // Mid line: smooth linear ratio across line duration
        assertEquals(0.5f, LyricsTimingEngine.calculateLineProgress(lrcLine, nextLine, 12000L), 0.001f)

        // After line
        assertEquals(1f, LyricsTimingEngine.calculateLineProgress(lrcLine, nextLine, 15000L), 0.001f)
    }

    @Test
    fun testSingleLineUserPreferencesDefaultIsOff() {
        val defaultPrefs = com.example.data.preferences.UserPreferences()
        assertFalse("Single-Line Lyrics should be OFF by default", defaultPrefs.singleLineLyrics)
    }

    @Test
    fun testSingleLineScrollingShortLyricFitsWithoutScrolling() {
        val availableWidth = 800f
        val shortLineWidth = 450f
        val maxScrollX = (shortLineWidth - availableWidth + 24f).coerceAtLeast(0f)

        assertEquals("Short lyric must not scroll horizontally", 0f, maxScrollX, 0.001f)
    }

    @Test
    fun testSingleLineScrollingLongLyricComputesCorrectMaxScroll() {
        val availableWidth = 800f
        val longLineWidth = 1400f
        val maxScrollX = (longLineWidth - availableWidth + 24f).coerceAtLeast(0f)

        assertTrue("Long lyric exceeding width must have positive maxScrollX", maxScrollX > 0f)
        assertEquals(624f, maxScrollX, 0.001f)
    }

    @Test
    fun testAdaptiveScrollFourSecondIntervalSlowerThanOneSecondInterval() {
        val currentStartMs = 10000L
        val currentPositionMs = 10500L // 500ms elapsed

        val oneSecondNextMs = 11000L // 1000ms interval
        val fourSecondNextMs = 14000L // 4000ms interval

        val progressOneSec = LyricsTimingEngine.calculateAdaptiveScrollProgress(
            currentLineStartMs = currentStartMs,
            nextTimestampMs = oneSecondNextMs,
            trackDurationMs = 60000L,
            currentPositionMs = currentPositionMs,
            speedMultiplier = 1.0f
        )

        val progressFourSec = LyricsTimingEngine.calculateAdaptiveScrollProgress(
            currentLineStartMs = currentStartMs,
            nextTimestampMs = fourSecondNextMs,
            trackDurationMs = 60000L,
            currentPositionMs = currentPositionMs,
            speedMultiplier = 1.0f
        )

        assertEquals("500ms in 1000ms interval must be 50% scrolled", 0.5f, progressOneSec, 0.001f)
        assertEquals("500ms in 4000ms interval must be 12.5% scrolled", 0.125f, progressFourSec, 0.001f)
        assertTrue("Four-second interval must scroll much more slowly than one-second interval", progressFourSec < progressOneSec)
    }

    @Test
    fun testAdaptiveScrollSpeedMultiplierScaling() {
        val currentStartMs = 10000L
        val nextTimestampMs = 12000L // 2000ms interval
        val currentPositionMs = 10500L // 500ms elapsed -> base fraction = 0.25f

        val normalSpeedProgress = LyricsTimingEngine.calculateAdaptiveScrollProgress(
            currentLineStartMs = currentStartMs,
            nextTimestampMs = nextTimestampMs,
            trackDurationMs = 60000L,
            currentPositionMs = currentPositionMs,
            speedMultiplier = 1.0f
        )
        // Sub-1.0x multipliers must be safely normalized to 1.0x
        val subOneSpeedProgress = LyricsTimingEngine.calculateAdaptiveScrollProgress(
            currentLineStartMs = currentStartMs,
            nextTimestampMs = nextTimestampMs,
            trackDurationMs = 60000L,
            currentPositionMs = currentPositionMs,
            speedMultiplier = 0.5f
        )
        val fastSpeedProgress = LyricsTimingEngine.calculateAdaptiveScrollProgress(
            currentLineStartMs = currentStartMs,
            nextTimestampMs = nextTimestampMs,
            trackDurationMs = 60000L,
            currentPositionMs = currentPositionMs,
            speedMultiplier = 1.5f
        )
        // Multipliers above 1.5x must be clamped to 1.5x
        val overSpeedProgress = LyricsTimingEngine.calculateAdaptiveScrollProgress(
            currentLineStartMs = currentStartMs,
            nextTimestampMs = nextTimestampMs,
            trackDurationMs = 60000L,
            currentPositionMs = currentPositionMs,
            speedMultiplier = 2.0f
        )

        assertEquals("1.0x multiplier produces base progress (0.25f)", 0.25f, normalSpeedProgress, 0.001f)
        assertEquals("Sub-1.0x values must be normalized to 1.0x (yielding 0.25f)", 0.25f, subOneSpeedProgress, 0.001f)
        assertEquals("1.5x multiplier scales progress (0.25 * 1.5 = 0.375f)", 0.375f, fastSpeedProgress, 0.001f)
        assertEquals("Multipliers exceeding 1.5x clamp to 1.5x (0.375f)", 0.375f, overSpeedProgress, 0.001f)
    }

    @Test
    fun testAdaptiveScrollFinalLyricFallbackUsesRemainingTrackTimeOrCeiling() {
        val currentStartMs = 50000L

        // Case A: Remaining track duration is 2.5 seconds (within 1.0s - 4.0s)
        val progressWithRemainingTrack = LyricsTimingEngine.calculateAdaptiveScrollProgress(
            currentLineStartMs = currentStartMs,
            nextTimestampMs = null,
            trackDurationMs = 52500L, // 2500ms remaining
            currentPositionMs = 51250L, // 1250ms elapsed
            speedMultiplier = 1.0f
        )
        assertEquals("Should use remaining track duration as interval (1250 / 2500 = 0.5)", 0.5f, progressWithRemainingTrack, 0.001f)

        // Case B: Remaining track duration is large (e.g. 30 seconds) -> should cap at 4.0s ceiling
        val progressWithLargeRemaining = LyricsTimingEngine.calculateAdaptiveScrollProgress(
            currentLineStartMs = currentStartMs,
            nextTimestampMs = null,
            trackDurationMs = 80000L, // 30000ms remaining
            currentPositionMs = 52000L, // 2000ms elapsed
            speedMultiplier = 1.0f
        )
        assertEquals("Should cap interval at 4000ms ceiling (2000 / 4000 = 0.5)", 0.5f, progressWithLargeRemaining, 0.001f)

        // Case C: Null track duration -> safe fallback to 4.0s without crashing
        val progressWithNullTrack = LyricsTimingEngine.calculateAdaptiveScrollProgress(
            currentLineStartMs = currentStartMs,
            nextTimestampMs = null,
            trackDurationMs = null,
            currentPositionMs = 52000L,
            speedMultiplier = 1.0f
        )
        assertEquals(0.5f, progressWithNullTrack, 0.001f)
    }

    @Test
    fun testAdaptiveScrollPauseResumeSeekingBehavior() {
        val currentStartMs = 20000L
        val nextTimestampMs = 24000L // 4000ms interval

        // Paused at 22000ms
        val pausedPosMs = 22000L
        val progressAtPause = LyricsTimingEngine.calculateAdaptiveScrollProgress(
            currentLineStartMs = currentStartMs,
            nextTimestampMs = nextTimestampMs,
            trackDurationMs = 120000L,
            currentPositionMs = pausedPosMs
        )
        assertEquals(0.5f, progressAtPause, 0.001f)

        // While paused, progress remains completely stable
        val progressWhilePaused = LyricsTimingEngine.calculateAdaptiveScrollProgress(
            currentLineStartMs = currentStartMs,
            nextTimestampMs = nextTimestampMs,
            trackDurationMs = 120000L,
            currentPositionMs = pausedPosMs
        )
        assertEquals(progressAtPause, progressWhilePaused, 0.001f)

        // Seek forward to 23000ms (75%)
        val progressAfterSeekFwd = LyricsTimingEngine.calculateAdaptiveScrollProgress(
            currentLineStartMs = currentStartMs,
            nextTimestampMs = nextTimestampMs,
            trackDurationMs = 120000L,
            currentPositionMs = 23000L
        )
        assertEquals(0.75f, progressAfterSeekFwd, 0.001f)

        // Seek backward to 21000ms (25%)
        val progressAfterSeekBack = LyricsTimingEngine.calculateAdaptiveScrollProgress(
            currentLineStartMs = currentStartMs,
            nextTimestampMs = nextTimestampMs,
            trackDurationMs = 120000L,
            currentPositionMs = 21000L
        )
        assertEquals(0.25f, progressAfterSeekBack, 0.001f)

        // Seek to before start of line
        val progressBeforeStart = LyricsTimingEngine.calculateAdaptiveScrollProgress(
            currentLineStartMs = currentStartMs,
            nextTimestampMs = nextTimestampMs,
            trackDurationMs = 120000L,
            currentPositionMs = 19000L
        )
        assertEquals(0f, progressBeforeStart, 0.001f)
    }

    @Test
    fun testAdaptiveScrollPreferenceDefaults() {
        val prefs = com.example.data.preferences.UserPreferences()
        assertTrue("Adaptive lyric scrolling must be enabled by default", prefs.adaptiveLyricScrolling)
        assertEquals("Scroll speed multiplier must default to 1.0x", 1.0f, prefs.lyricScrollSpeedMultiplier, 0.001f)
    }

    @Test
    fun testKaraokeSpeedPreferenceDefaultsAndRange() {
        val prefs = com.example.data.preferences.UserPreferences()
        assertEquals("Apple Karaoke glow speed must default to 1.0x", 1.0f, prefs.karaokeGlowSpeed, 0.001f)
        assertEquals("Apple Karaoke speed alias must default to 1.0x", 1.0f, prefs.karaokeSpeed, 0.001f)

        // Test safe normalization / clamping
        val clampedUnder = 0.2f.coerceIn(0.5f, 1.5f)
        val clampedOver = 2.5f.coerceIn(0.5f, 1.5f)
        val clampedNormal = 1.0f.coerceIn(0.5f, 1.5f)

        assertEquals(0.5f, clampedUnder, 0.001f)
        assertEquals(1.5f, clampedOver, 0.001f)
        assertEquals(1.0f, clampedNormal, 0.001f)
    }

    @Test
    fun testWordProgressGlowSpeedMultiplierScaling() {
        val word = WordTiming("Hello", 1000L, 2000L) // 1000ms duration
        // At 1500ms (500ms elapsed, base fraction = 0.5f)
        val progressBaseline = LyricsTimingEngine.calculateWordProgress(word, 1500L, speedMultiplier = 1.0f)
        val progressFaster = LyricsTimingEngine.calculateWordProgress(word, 1500L, speedMultiplier = 1.5f)
        val progressSlower = LyricsTimingEngine.calculateWordProgress(word, 1500L, speedMultiplier = 0.5f)

        assertEquals("At 1.0x, baseline glow progress is 0.5f", 0.5f, progressBaseline, 0.001f)
        assertEquals("At 1.5x, white glow travels 1.5x faster (0.5 * 1.5 = 0.75f)", 0.75f, progressFaster, 0.001f)
        assertTrue("At 0.5x, white glow travels slower than baseline (< 0.5f)", progressSlower < progressBaseline)
        assertEquals(0.392f, progressSlower, 0.01f)
    }

    @Test
    fun testWordProgressPlaybackSyncAndBoundaries() {
        val word = WordTiming("Hello", 1000L, 2000L)

        // Before word starts: progress is always 0f, never prematurely active
        assertEquals(0f, LyricsTimingEngine.calculateWordProgress(word, 500L, 1.5f), 0.001f)
        assertEquals(0f, LyricsTimingEngine.calculateWordProgress(word, 1000L, 1.5f), 0.001f)

        // At word end timestamp: always exactly 1f regardless of multiplier (never delays next lyric)
        assertEquals(1f, LyricsTimingEngine.calculateWordProgress(word, 2000L, 0.5f), 0.001f)
        assertEquals(1f, LyricsTimingEngine.calculateWordProgress(word, 2500L, 1.5f), 0.001f)

        // Word reach completion before end when fast (e.g., at 1700ms with 1.5x: 700/1000 * 1.5 = 1.05 -> clamped to 1.0f)
        assertEquals(1f, LyricsTimingEngine.calculateWordProgress(word, 1700L, 1.5f), 0.001f)

        // Seeking / position jump: recalculates immediately with no stale offset
        val progressSeek = LyricsTimingEngine.calculateWordProgress(word, 1200L, 1.0f)
        assertEquals(0.2f, progressSeek, 0.001f)
    }

    @Test
    fun testLineProgressGlowSpeedScaling() {
        val lrcLine = LyricLine(timeMs = 10000L, endTimeMs = 14000L, text = "Unsynced Line") // 4000ms duration
        val nextLine = LyricLine(timeMs = 14000L, endTimeMs = 18000L, text = "Next Line")

        // Mid line (12000ms, 2000ms elapsed, base fraction = 0.5f)
        val progressBase = LyricsTimingEngine.calculateLineProgress(lrcLine, nextLine, 12000L, 1.0f)
        val progressFast = LyricsTimingEngine.calculateLineProgress(lrcLine, nextLine, 12000L, 1.5f)
        val progressSlow = LyricsTimingEngine.calculateLineProgress(lrcLine, nextLine, 12000L, 0.5f)

        assertEquals("At 1.0x baseline, line progress is 0.5f", 0.5f, progressBase, 0.001f)
        assertEquals("At 1.5x, line glow sweep travels 1.5x faster (0.75f)", 0.75f, progressFast, 0.001f)
        assertTrue("At 0.5x, line glow sweep is slower than baseline (< 0.5f)", progressSlow < progressBase)
        assertEquals(0.392f, progressSlow, 0.01f)
    }

    @Test
    fun testGlowSweepNeverStuckInSlowAndRelaxedModes() {
        val word = WordTiming("Illumination", 1000L, 5000L) // 4000ms duration
        val slowSpeed = 0.5f
        val relaxedSpeed = 0.7f

        // 1. Slow mode (0.5x): glow must NOT stop at 50%
        val progSlow75Percent = LyricsTimingEngine.calculateWordProgress(word, 4000L, slowSpeed) // 75% elapsed
        assertTrue("In Slow mode at 75% elapsed, progress must exceed 0.5f (was getting stuck at 0.5f)", progSlow75Percent > 0.5f)

        val progSlowEnd = LyricsTimingEngine.calculateWordProgress(word, 5000L, slowSpeed)
        assertEquals("In Slow mode at word end, progress must reach 100% (1.0f)", 1.0f, progSlowEnd, 0.001f)

        // 2. Relaxed mode (0.7x): glow must NOT stop at 70%
        val progRelaxed85Percent = LyricsTimingEngine.calculateWordProgress(word, 4400L, relaxedSpeed) // 85% elapsed
        assertTrue("In Relaxed mode at 85% elapsed, progress must exceed 0.7f (was getting stuck at 0.7f)", progRelaxed85Percent > 0.7f)

        val progRelaxedEnd = LyricsTimingEngine.calculateWordProgress(word, 5000L, relaxedSpeed)
        assertEquals("In Relaxed mode at word end, progress must reach 100% (1.0f)", 1.0f, progRelaxedEnd, 0.001f)

        // 3. Line progress test for LRC lines in Slow mode
        val lrcLine = LyricLine(timeMs = 10000L, endTimeMs = 16000L, text = "Long Lyric Line")
        val nextLine = LyricLine(timeMs = 16000L, text = "Next Line")

        val lineProgSlow80Percent = LyricsTimingEngine.calculateLineProgress(lrcLine, nextLine, 14800L, slowSpeed) // 80% elapsed
        assertTrue("LRC line progress in Slow mode must exceed 0.5f past midpoint", lineProgSlow80Percent > 0.5f)

        val lineProgSlowEnd = LyricsTimingEngine.calculateLineProgress(lrcLine, nextLine, 16000L, slowSpeed)
        assertEquals("LRC line progress in Slow mode at line end must reach 1.0f", 1.0f, lineProgSlowEnd, 0.001f)
    }
}
