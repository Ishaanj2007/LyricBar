package com.example.domain.lyricsengine

import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.WordTiming
import org.junit.Assert.*
import org.junit.Test

class LyricSelectionAndSeekingTest {

    private val sampleLyricsWithExplicitEndTimes = ParsedLyrics(
        title = "Synchronized Test Track",
        artist = "Test Artist",
        isSynced = true,
        lines = listOf(
            LyricLine(timeMs = 5000L, endTimeMs = 9000L, text = "First Lyric Line"),
            LyricLine(timeMs = 15000L, endTimeMs = 19000L, text = "Second Lyric Line"),
            LyricLine(timeMs = 30000L, endTimeMs = 35000L, text = "Third Lyric Line (Chorus)")
        ),
        plainText = "First Lyric Line\nSecond Lyric Line\nThird Lyric Line (Chorus)",
        source = "Test"
    )

    private val sampleLyricsWithBlankInterludes = ParsedLyrics(
        title = "LRC With Instrumental Breaks",
        artist = "Test Artist",
        isSynced = true,
        lines = listOf(
            LyricLine(timeMs = 10000L, text = "Intro Verse"),
            LyricLine(timeMs = 16000L, text = ""), // Empty LRC line representing instrumental break
            LyricLine(timeMs = 24000L, text = "Verse 2"),
            LyricLine(timeMs = 32000L, text = "")  // Outro clearing line
        ),
        plainText = "Intro Verse\nVerse 2",
        source = "Test"
    )

    private val sampleWordSyncedLyrics = ParsedLyrics(
        title = "Word Synced Track",
        artist = "Test Artist",
        isSynced = true,
        lines = listOf(
            LyricLine(
                timeMs = 4000L,
                text = "Hello world today",
                words = listOf(
                    WordTiming("Hello", 4000L, 5000L),
                    WordTiming("world", 5000L, 6000L),
                    WordTiming("today", 6000L, 7500L)
                )
            ),
            LyricLine(
                timeMs = 12000L,
                text = "Next line comes here",
                words = listOf(
                    WordTiming("Next", 12000L, 13000L),
                    WordTiming("line", 13000L, 14000L),
                    WordTiming("comes", 14000L, 14800L),
                    WordTiming("here", 14800L, 16000L)
                )
            )
        ),
        plainText = "Hello world today\nNext line comes here",
        source = "Test"
    )

    @Test
    fun testSeekForwardUpdatesToCorrectLyric() {
        val lyrics = sampleLyricsWithExplicitEndTimes

        // Initially at Line 1
        val initialLine = lyrics.getActiveLyricLine(6000L)
        assertNotNull(initialLine)
        assertEquals("First Lyric Line", initialLine?.text)

        // Seek forward to Line 2 (16000ms)
        val seekedLine2 = lyrics.getActiveLyricLine(16000L)
        assertNotNull(seekedLine2)
        assertEquals("Second Lyric Line", seekedLine2?.text)

        // Seek forward to Line 3 (32000ms)
        val seekedLine3 = lyrics.getActiveLyricLine(32000L)
        assertNotNull(seekedLine3)
        assertEquals("Third Lyric Line (Chorus)", seekedLine3?.text)
    }

    @Test
    fun testSeekBackwardImmediatelyDisplaysCorrectEarlierLyric() {
        val lyrics = sampleLyricsWithExplicitEndTimes

        // Start at Line 3 (33000ms)
        val line3 = lyrics.getActiveLyricLine(33000L)
        assertEquals("Third Lyric Line (Chorus)", line3?.text)

        // Seek backward to Line 2 (17000ms)
        val line2 = lyrics.getActiveLyricLine(17000L)
        assertNotNull("Seeking backward must immediately return Line 2", line2)
        assertEquals("Second Lyric Line", line2?.text)

        // Seek backward to Line 1 (7000ms)
        val line1 = lyrics.getActiveLyricLine(7000L)
        assertNotNull("Seeking backward must immediately return Line 1", line1)
        assertEquals("First Lyric Line", line1?.text)
    }

    @Test
    fun testSeekBeforeFirstTimestampClearsActiveLyric() {
        val lyrics = sampleLyricsWithExplicitEndTimes

        // First line starts at 5000ms. Seeking anywhere before 5000ms must return null.
        assertNull("Position at 0ms is before first lyric and must return null", lyrics.getActiveLyricLine(0L))
        assertNull("Position at 2500ms is before first lyric and must return null", lyrics.getActiveLyricLine(2500L))
        assertNull("Position at 4999ms is before first lyric and must return null", lyrics.getActiveLyricLine(4999L))
        assertEquals(-1, lyrics.findActiveIndex(0L))
        assertEquals(-1, lyrics.findActiveIndex(4999L))
        assertNull(lyrics.getActiveLine(2000L))
    }

    @Test
    fun testSeekBetweenLyricTimestampsClearsActiveLyricWhenNoLyricIsActive() {
        val lyrics = sampleLyricsWithExplicitEndTimes

        // Line 1 ends at 9000ms. Line 2 starts at 15000ms.
        // Between 9000ms and 14999ms, no lyric is active.
        assertNotNull(lyrics.getActiveLyricLine(8500L)) // Still singing Line 1
        assertNull("Position at 9000ms is past Line 1 endTime and before Line 2, must clear lyric", lyrics.getActiveLyricLine(9000L))
        assertNull("Position at 10000ms is in gap between lyrics, must return null", lyrics.getActiveLyricLine(10000L))
        assertNull("Position at 12500ms is in gap between lyrics, must return null", lyrics.getActiveLyricLine(12500L))
        assertNull("Position at 14999ms is just before Line 2, must return null", lyrics.getActiveLyricLine(14999L))
        assertEquals(-1, lyrics.findActiveIndex(11000L))
        assertNull(lyrics.getActiveLine(11000L))

        // Seeking to 15000ms immediately activates Line 2
        val line2 = lyrics.getActiveLyricLine(15000L)
        assertNotNull(line2)
        assertEquals("Second Lyric Line", line2?.text)
    }

    @Test
    fun testSeekBetweenLyricTimestampsWithBlankLrcLines() {
        val lyrics = sampleLyricsWithBlankInterludes

        // Intro Verse: 10000ms to 16000ms
        assertEquals("Intro Verse", lyrics.getActiveLyricLine(12000L)?.text)

        // At 16000ms, an empty LRC line denotes silence until Verse 2 at 24000ms
        assertNull("Empty LRC line at 16000ms must clear active lyric object", lyrics.getActiveLyricLine(16000L))
        assertNull("Position at 20000ms inside instrumental gap must return null", lyrics.getActiveLyricLine(20000L))
        assertEquals(-1, lyrics.findActiveIndex(20000L))
        assertNull(lyrics.getActiveLine(20000L))

        // Verse 2 starts at 24000ms
        assertEquals("Verse 2", lyrics.getActiveLyricLine(24000L)?.text)
        assertEquals("Verse 2", lyrics.getActiveLyricLine(28000L)?.text)
    }

    @Test
    fun testSeekBeyondLastTimestampClearsActiveLyric() {
        val lyrics = sampleLyricsWithExplicitEndTimes

        // Line 3 ends at 35000ms
        assertEquals("Third Lyric Line (Chorus)", lyrics.getActiveLyricLine(34000L)?.text)

        // Seeking past 35000ms (beyond the last timestamp + its duration) must return null
        assertNull("Position at 35000ms is past final lyric end time, must return null", lyrics.getActiveLyricLine(35000L))
        assertNull("Position at 45000ms is well past last lyric, must return null", lyrics.getActiveLyricLine(45000L))
        assertNull("Position at 120000ms is at end of track, must return null", lyrics.getActiveLyricLine(120000L))
        assertEquals(-1, lyrics.findActiveIndex(45000L))
        assertNull(lyrics.getActiveLine(45000L))
    }

    @Test
    fun testSeekBeyondLastTimestampWithOutroClearingLine() {
        val lyrics = sampleLyricsWithBlankInterludes

        // Verse 2 at 24000ms, outro blank at 32000ms
        assertEquals("Verse 2", lyrics.getActiveLyricLine(30000L)?.text)

        // At 32000ms and beyond: outro blank cleared
        assertNull("Outro blank line must clear active lyric", lyrics.getActiveLyricLine(32000L))
        assertNull("Seeking beyond outro line must return null", lyrics.getActiveLyricLine(50000L))
        assertEquals(-1, lyrics.findActiveIndex(50000L))
    }

    @Test
    fun testWordSyncedLineBoundariesAndSeeking() {
        val lyrics = sampleWordSyncedLyrics

        // Before first word starts (at 3000ms, first word starts at 4000ms)
        assertNull("Before first word starts, active lyric must be null", lyrics.getActiveLyricLine(3000L))

        // During Line 1 (4000ms - 7500ms)
        val activeLine1 = lyrics.getActiveLyricLine(5500L)
        assertNotNull(activeLine1)
        assertEquals("Hello world today", activeLine1?.text)

        // In gap between Line 1 (ended at 7500ms) and Line 2 (starts at 12000ms)
        assertNull("In gap between word-synced lines, active lyric must be null", lyrics.getActiveLyricLine(8000L))
        assertNull("In gap at 10000ms, active lyric must be null", lyrics.getActiveLyricLine(10000L))
        assertEquals(-1, lyrics.findActiveIndex(10000L))

        // At Line 2 (12000ms - 16000ms)
        val activeLine2 = lyrics.getActiveLyricLine(13500L)
        assertNotNull(activeLine2)
        assertEquals("Next line comes here", activeLine2?.text)

        // Past Line 2 end (after 16000ms)
        assertNull("Past final word of line 2, active lyric must be null", lyrics.getActiveLyricLine(17000L))
        assertEquals(-1, lyrics.findActiveIndex(17000L))
    }

    @Test
    fun testNormalPlaybackCrossingLyricBoundaries() {
        val lyrics = sampleLyricsWithExplicitEndTimes

        // Simulate a playback ticker advancing every 500ms from 4000ms to 16000ms
        val timelinePositions = listOf(
            4000L to null,
            4500L to null,
            5000L to "First Lyric Line",
            6000L to "First Lyric Line",
            8500L to "First Lyric Line",
            9000L to null, // line 1 ended
            10000L to null,
            14000L to null,
            15000L to "Second Lyric Line", // line 2 starts
            16000L to "Second Lyric Line"
        )

        for ((pos, expectedText) in timelinePositions) {
            val active = lyrics.getActiveLyricLine(pos)
            if (expectedText == null) {
                assertNull("At ${pos}ms expected no active lyric", active)
            } else {
                assertNotNull("At ${pos}ms expected active lyric '$expectedText'", active)
                assertEquals(expectedText, active?.text)
            }
        }
    }

    @Test
    fun testTrackOffsetPreservationDuringSeeking() {
        val lyrics = sampleLyricsWithExplicitEndTimes

        // With +2000ms track offset, line 1 (5000ms) activates at position 3000ms (3000 + 2000 = 5000ms)
        assertNull("At 2999ms with +2000ms offset, line hasn't started yet", lyrics.getActiveLyricLine(2999L, trackOffsetMs = 2000L))
        val activeWithPositiveOffset = lyrics.getActiveLyricLine(3000L, trackOffsetMs = 2000L)
        assertNotNull(activeWithPositiveOffset)
        assertEquals("First Lyric Line", activeWithPositiveOffset?.text)

        // Line 1 ends at 9000ms, so with +2000ms offset it ends at position 7000ms
        assertNull("At 7000ms with +2000ms offset, line 1 has ended", lyrics.getActiveLyricLine(7000L, trackOffsetMs = 2000L))

        // With -1000ms track offset, line 1 (5000ms) activates at position 6000ms (6000 - 1000 = 5000ms)
        assertNull("At 5500ms with -1000ms offset, line hasn't started yet", lyrics.getActiveLyricLine(5500L, trackOffsetMs = -1000L))
        val activeWithNegativeOffset = lyrics.getActiveLyricLine(6000L, trackOffsetMs = -1000L)
        assertNotNull(activeWithNegativeOffset)
        assertEquals("First Lyric Line", activeWithNegativeOffset?.text)
    }

    @Test
    fun testLyricStateTransitionsDoNotRetainStaleLyrics() {
        var activeLyricObject: LyricLine? = null
        var activeLyricLine: String? = null

        fun updateActiveLyric(lyrics: ParsedLyrics, pos: Long, offset: Long = 0L) {
            val activeObj = lyrics.getActiveLyricLine(pos, offset)
            if (activeLyricObject != activeObj) {
                activeLyricObject = activeObj
                activeLyricLine = activeObj?.text
            }
        }

        val lyrics = sampleLyricsWithExplicitEndTimes

        // 1. Play at 6000ms -> Line 1
        updateActiveLyric(lyrics, 6000L)
        assertEquals("First Lyric Line", activeLyricLine)
        assertEquals(lyrics.lines[0], activeLyricObject)

        // 2. Seek before first timestamp (1000ms) -> Must clear immediately
        updateActiveLyric(lyrics, 1000L)
        assertNull("Seeking before first timestamp must clear active lyric line to null", activeLyricLine)
        assertNull("Seeking before first timestamp must clear active lyric object to null", activeLyricObject)

        // 3. Seek forward to Line 3 (32000ms) -> Must update immediately
        updateActiveLyric(lyrics, 32000L)
        assertEquals("Third Lyric Line (Chorus)", activeLyricLine)
        assertEquals(lyrics.lines[2], activeLyricObject)

        // 4. Seek into gap between Line 1 and Line 2 (11000ms) -> Must clear immediately
        updateActiveLyric(lyrics, 11000L)
        assertNull("Seeking between lyric timestamps must clear active lyric line to null", activeLyricLine)
        assertNull("Seeking between lyric timestamps must clear active lyric object to null", activeLyricObject)

        // 5. Seek beyond final lyric (40000ms) -> Must clear immediately
        updateActiveLyric(lyrics, 40000L)
        assertNull("Seeking beyond last timestamp must clear active lyric line to null", activeLyricLine)
        assertNull("Seeking beyond last timestamp must clear active lyric object to null", activeLyricObject)

        // 6. Seek back to Line 2 (17000ms) -> Immediately displays Line 2
        updateActiveLyric(lyrics, 17000L)
        assertEquals("Second Lyric Line", activeLyricLine)
        assertEquals(lyrics.lines[1], activeLyricObject)
    }
}
