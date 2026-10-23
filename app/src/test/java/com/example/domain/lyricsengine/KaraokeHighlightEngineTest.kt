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
}
