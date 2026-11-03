package com.example.domain.lyricsengine

import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Test

class OptimizedActiveLyricLookupTest {

    @Test
    fun testEmptyLyricsReturnMinusOne() {
        val emptyLyrics = ParsedLyrics(
            title = "Empty",
            artist = "Empty",
            isSynced = true,
            lines = emptyList(),
            plainText = "",
            source = "Test"
        )
        assertEquals(-1, emptyLyrics.findActiveIndex(1000L))
        assertEquals(-1, emptyLyrics.findActiveIndex(0L))
    }

    @Test
    fun testUnsyncedLyricsReturnMinusOne() {
        val unsynced = ParsedLyrics(
            title = "Plain",
            artist = "Plain",
            isSynced = false,
            lines = listOf(LyricLine(timeMs = 0L, text = "First line")),
            plainText = "First line",
            source = "Test"
        )
        assertEquals(-1, unsynced.findActiveIndex(1000L))
    }

    @Test
    fun testSingleLineLyrics() {
        val lyrics = ParsedLyrics(
            title = "Single",
            artist = "Single",
            isSynced = true,
            lines = listOf(LyricLine(timeMs = 5000L, text = "Only line")),
            plainText = "Only line",
            source = "Test"
        )

        // Before timestamp -> -1
        assertEquals(-1, lyrics.findActiveIndex(4999L))
        // Exactly on timestamp -> 0
        assertEquals(0, lyrics.findActiveIndex(5000L))
        // After timestamp -> 0
        assertEquals(0, lyrics.findActiveIndex(10000L))
    }

    @Test
    fun testMultiLineBinarySearchAccuracy() {
        val lines = listOf(
            LyricLine(timeMs = 1000L, text = "Line 0"),
            LyricLine(timeMs = 3000L, text = "Line 1"),
            LyricLine(timeMs = 6000L, text = "Line 2"),
            LyricLine(timeMs = 10000L, text = "Line 3"),
            LyricLine(timeMs = 15000L, text = "Line 4")
        )

        val lyrics = ParsedLyrics(
            title = "Multi",
            artist = "Multi",
            isSynced = true,
            lines = lines,
            plainText = "...",
            source = "Test"
        )

        // Before first lyric
        assertEquals(-1, lyrics.findActiveIndex(500L))
        assertEquals(-1, lyrics.findActiveIndex(0L))

        // Exactly on boundaries
        assertEquals(0, lyrics.findActiveIndex(1000L))
        assertEquals(1, lyrics.findActiveIndex(3000L))
        assertEquals(2, lyrics.findActiveIndex(6000L))
        assertEquals(3, lyrics.findActiveIndex(10000L))
        assertEquals(4, lyrics.findActiveIndex(15000L))

        // In between timestamps
        assertEquals(0, lyrics.findActiveIndex(2000L))
        assertEquals(1, lyrics.findActiveIndex(4500L))
        assertEquals(2, lyrics.findActiveIndex(9999L))
        assertEquals(3, lyrics.findActiveIndex(12000L))

        // Way past the final lyric
        assertEquals(4, lyrics.findActiveIndex(100000L))
    }

    @Test
    fun testTrackOffsetHandling() {
        val lines = listOf(
            LyricLine(timeMs = 2000L, text = "Line 0"),
            LyricLine(timeMs = 5000L, text = "Line 1")
        )

        val lyrics = ParsedLyrics(
            title = "OffsetTest",
            artist = "Artist",
            isSynced = true,
            lines = lines,
            plainText = "...",
            source = "Test",
            offsetMs = 0L
        )

        // At position 1500, normally line 0 is not yet reached
        assertEquals(-1, lyrics.findActiveIndex(1500L, trackOffsetMs = 0L))

        // With +600ms offset: adjusted position = 2100ms -> Line 0 is active!
        assertEquals(0, lyrics.findActiveIndex(1500L, trackOffsetMs = 600L))

        // With -1000ms offset: at 2500ms, adjusted position = 1500ms -> line 0 not yet active (-1)
        assertEquals(-1, lyrics.findActiveIndex(2500L, trackOffsetMs = -1000L))

        // With -1000ms offset: at 3200ms, adjusted position = 2200ms -> line 0 is active
        assertEquals(0, lyrics.findActiveIndex(3200L, trackOffsetMs = -1000L))
    }

    @Test
    fun testEqualConsecutiveTimestamps() {
        // In case multiple lines share identical timestamps
        val lines = listOf(
            LyricLine(timeMs = 1000L, text = "Line A"),
            LyricLine(timeMs = 1000L, text = "Line B"),
            LyricLine(timeMs = 3000L, text = "Line C")
        )

        val lyrics = ParsedLyrics(
            title = "EqualTimes",
            artist = "Artist",
            isSynced = true,
            lines = lines,
            plainText = "...",
            source = "Test"
        )

        // At 1000ms, binary search must resolve to the latest line with timeMs <= adjustedPos (index 1)
        assertEquals(1, lyrics.findActiveIndex(1000L))
        assertEquals(1, lyrics.findActiveIndex(2000L))
        assertEquals(2, lyrics.findActiveIndex(3000L))
    }

    @Test
    fun testBlankLineAndEndTimeCutoff() {
        val lines = listOf(
            LyricLine(timeMs = 1000L, text = "Instrumental Break", endTimeMs = 3000L),
            LyricLine(timeMs = 5000L, text = "   "), // Blank text
            LyricLine(timeMs = 8000L, text = "Real Lyrics")
        )

        val lyrics = ParsedLyrics(
            title = "Cutoff",
            artist = "Artist",
            isSynced = true,
            lines = lines,
            plainText = "...",
            source = "Test"
        )

        // During instrumental break (1000ms to 2999ms) -> line 0 is active
        assertEquals(0, lyrics.findActiveIndex(2000L))

        // At or after endTimeMs (3000ms) -> line ends, returns -1
        assertEquals(-1, lyrics.findActiveIndex(3500L))

        // During blank lyric at 5000ms -> returns -1
        assertEquals(-1, lyrics.findActiveIndex(6000L))

        // Real lyrics starting at 8000ms -> returns 2
        assertEquals(2, lyrics.findActiveIndex(8000L))
    }

    @Test
    fun testWordSyncedEndTimeCutoff() {
        val words = listOf(
            WordTiming(word = "Hello", startMs = 1000L, endMs = 2000L),
            WordTiming(word = "World", startMs = 2000L, endMs = 4000L)
        )
        val line = LyricLine(timeMs = 1000L, text = "Hello World", words = words)

        val lyrics = ParsedLyrics(
            title = "WordCutoff",
            artist = "Artist",
            isSynced = true,
            lines = listOf(line),
            plainText = "Hello World",
            source = "Test"
        )

        assertEquals(0, lyrics.findActiveIndex(3000L))
        // Past the max word endMs (4000L) -> returns -1
        assertEquals(-1, lyrics.findActiveIndex(4000L))
        assertEquals(-1, lyrics.findActiveIndex(4500L))
    }

    @Test
    fun testUnsortedLinesSafetyFallback() {
        val unsortedLines = listOf(
            LyricLine(timeMs = 5000L, text = "Late Line"),
            LyricLine(timeMs = 1000L, text = "Early Line"),
            LyricLine(timeMs = 3000L, text = "Mid Line")
        )

        val lyrics = ParsedLyrics(
            title = "Unsorted",
            artist = "Artist",
            isSynced = true,
            lines = unsortedLines,
            plainText = "...",
            source = "Test"
        )

        // Should not crash and should fall back safely to linear evaluation
        assertEquals(-1, lyrics.findActiveIndex(500L))
    }
}
