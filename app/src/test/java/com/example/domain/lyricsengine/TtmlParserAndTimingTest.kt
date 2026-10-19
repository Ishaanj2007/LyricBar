package com.example.domain.lyricsengine

import com.example.data.model.LyricLine
import com.example.data.model.PlaybackStateInfo
import com.example.data.model.TrackMetadata
import com.example.data.model.WordTiming
import org.junit.Assert.*
import org.junit.Test

class TtmlParserAndTimingTest {

    private val sampleTtml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tt xmlns="http://www.w3.org/ns/ttml"
            xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
            xmlns:itunes="http://music.apple.com/lyric-ttml">
          <head>
            <metadata>
              <ttm:title>Bohemian Rhapsody</ttm:title>
              <ttm:agent type="person">Queen</ttm:agent>
              <itunes:album>A Night at the Opera</itunes:album>
            </metadata>
          </head>
          <body>
            <div>
              <p begin="00:00:01.000" end="00:00:05.000">
                <span begin="00:00:01.000" end="00:00:02.000">Is</span>
                <span begin="00:00:02.000" end="00:00:03.000">this</span>
                <span begin="00:00:03.000" end="00:00:04.000">the</span>
                <span begin="00:00:04.000" end="00:00:05.000">real</span>
                <span role="translation">¿Es esta la vida real?</span>
              </p>
              <p begin="00:00:06.000" end="00:00:10.000">
                <span begin="00:00:06.000" end="00:00:07.500">fantasy?</span>
              </p>
            </div>
          </body>
        </tt>
    """.trimIndent()

    @Test
    fun parse_validTtml_extractsMetadataLinesWordsAndTranslations() {
        val parsed = TtmlParser.parse(sampleTtml)

        assertEquals("Bohemian Rhapsody", parsed.title)
        assertEquals("Queen", parsed.artist)
        assertTrue(parsed.isSynced)
        assertTrue(parsed.isWordSynced)
        assertEquals("TTML", parsed.format)
        assertEquals(2, parsed.lines.size)

        val firstLine = parsed.lines[0]
        assertEquals(1000L, firstLine.timeMs)
        assertEquals(5000L, firstLine.endTimeMs)
        assertEquals("Is this the real", firstLine.text)
        assertEquals(4, firstLine.words.size)
        assertEquals("¿Es esta la vida real?", firstLine.translation)

        // Verify word timings
        val word1 = firstLine.words[0]
        assertEquals("Is", word1.word)
        assertEquals(1000L, word1.startMs)
        assertEquals(2000L, word1.endMs)

        val word4 = firstLine.words[3]
        assertEquals("real", word4.word)
        assertEquals(4000L, word4.startMs)
        assertEquals(5000L, word4.endMs)
    }

    @Test
    fun parseTimestampToMs_handlesVariousFormats() {
        // hh:mm:ss.ms
        assertEquals(3661500L, TtmlParser.parseTimestampToMs("01:01:01.500"))
        // mm:ss.ms
        assertEquals(61500L, TtmlParser.parseTimestampToMs("01:01.500"))
        // seconds with s
        assertEquals(12340L, TtmlParser.parseTimestampToMs("12.34s"))
        // milliseconds with ms
        assertEquals(54321L, TtmlParser.parseTimestampToMs("54321ms"))
    }

    @Test
    fun wordProgress_interpolatesCorrectly() {
        val word = WordTiming(word = "test", startMs = 1000L, endMs = 2000L)

        // Before start
        assertEquals(0f, LyricsTimingEngine.calculateWordProgress(word, 500L), 0.001f)
        assertEquals(0f, LyricsTimingEngine.calculateWordProgress(word, 1000L), 0.001f)

        // Mid point (50%)
        assertEquals(0.5f, LyricsTimingEngine.calculateWordProgress(word, 1500L), 0.001f)

        // Three quarters (75%)
        assertEquals(0.75f, LyricsTimingEngine.calculateWordProgress(word, 1750L), 0.001f)

        // At end or after
        assertEquals(1f, LyricsTimingEngine.calculateWordProgress(word, 2000L), 0.001f)
        assertEquals(1f, LyricsTimingEngine.calculateWordProgress(word, 3000L), 0.001f)
    }

    @Test
    fun playbackSeeking_updatesActiveLineAndWordWithoutDrift() {
        val parsed = TtmlParser.parse(sampleTtml)

        // At 1500ms -> active line index 0, active word index 0 ("Is")
        val activeLineAt1500 = parsed.getActiveLyricLine(1500L)
        assertNotNull(activeLineAt1500)
        assertEquals("Is this the real", activeLineAt1500!!.text)
        assertEquals(0.5f, LyricsTimingEngine.calculateWordProgress(activeLineAt1500.words[0], 1500L), 0.001f)

        // Seek forward to 7000ms -> jumps cleanly to line 1
        val activeLineAt7000 = parsed.getActiveLyricLine(7000L)
        assertNotNull(activeLineAt7000)
        assertEquals("fantasy?", activeLineAt7000!!.text)

        // Seek backward to 500ms (before any line) -> returns null without error or drift
        val activeLineBefore = parsed.getActiveLyricLine(500L)
        assertNull(activeLineBefore)

        // Seek to 4500ms -> line 0, 4th word ("real") is at 50%
        val lineAt4500 = parsed.getActiveLyricLine(4500L)
        assertNotNull(lineAt4500)
        assertEquals(0.5f, LyricsTimingEngine.calculateWordProgress(lineAt4500!!.words[3], 4500L), 0.001f)
    }

    @Test
    fun calculatedPositionMs_calculatesAccurately() {
        val state = PlaybackStateInfo(
            track = TrackMetadata("Title", "Artist"),
            isPlaying = true,
            positionMs = 10000L,
            playbackSpeed = 1.0f,
            lastUpdatedMs = System.currentTimeMillis() - 250L
        )

        val calculated = state.calculatedPositionMs
        assertTrue(calculated >= 10240L && calculated <= 10300L)
    }
}
