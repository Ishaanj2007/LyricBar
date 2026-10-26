package com.example.domain.parser

import org.junit.Assert.*
import org.junit.Test

class LrcParserTest {

    @Test
    fun testStandardLrcParsing() {
        val lrc = """
            [ti:Test Song]
            [ar:Test Artist]
            [00:05.00]First line
            [00:12.50]Second line
            [01:02.34]Third line
        """.trimIndent()

        val parsed = LrcParser.parse(lrc, "Test Song", "Test Artist")

        assertTrue(parsed.isSynced)
        assertEquals(3, parsed.lines.size)
        assertEquals(5000L, parsed.lines[0].timeMs)
        assertEquals("First line", parsed.lines[0].text)
        assertEquals(12500L, parsed.lines[1].timeMs)
        assertEquals("Second line", parsed.lines[1].text)
        assertEquals(62340L, parsed.lines[2].timeMs)
        assertEquals("Third line", parsed.lines[2].text)
    }

    @Test
    fun testMultiTimestampOnSingleLine() {
        val lrc = """
            [00:10.00][00:20.00]Repeated Chorus
        """.trimIndent()

        val parsed = LrcParser.parse(lrc)

        assertTrue(parsed.isSynced)
        assertEquals(2, parsed.lines.size)
        assertEquals(10000L, parsed.lines[0].timeMs)
        assertEquals("Repeated Chorus", parsed.lines[0].text)
        assertEquals(20000L, parsed.lines[1].timeMs)
        assertEquals("Repeated Chorus", parsed.lines[1].text)
    }

    @Test
    fun testOffsetTagParsing() {
        val lrc = """
            [offset:+500]
            [00:04.00]Line with offset
        """.trimIndent()

        val parsed = LrcParser.parse(lrc)
        assertEquals(500L, parsed.offsetMs)
    }

    @Test
    fun testActiveLineLookup() {
        val lrc = """
            [00:05.00]Intro
            [00:10.00]Verse 1
            [00:20.00]Chorus
        """.trimIndent()

        val parsed = LrcParser.parse(lrc)

        // Before first line
        assertEquals(null, parsed.getActiveLine(2000L))

        // On first line
        assertEquals("Intro", parsed.getActiveLine(6000L))

        // Between second and third
        assertEquals("Verse 1", parsed.getActiveLine(15000L))

        // On third line
        assertEquals("Chorus", parsed.getActiveLine(25000L))

        // With manual offset
        assertEquals("Verse 1", parsed.getActiveLine(8000L, trackOffsetMs = 3000L))
    }

    @Test
    fun testPlainLyricsFallback() {
        val plainText = """
            Just a line without timestamp
            Another line of plain text
            Final line
        """.trimIndent()

        val parsed = LrcParser.parse(plainText)

        assertFalse(parsed.isSynced)
        assertEquals(3, parsed.lines.size)
        assertEquals("Just a line without timestamp", parsed.lines[0].text)

        // Verify NO fabricated timestamps (each line has timeMs == 0L)
        assertTrue(parsed.lines.all { it.timeMs == 0L })

        // findActiveIndex must return -1 (no fake playback position)
        assertEquals(-1, parsed.findActiveIndex(10000L))

        // getActiveLine must return readable static text instead of null/blank
        assertEquals("Just a line without timestamp", parsed.getActiveLine(10000L))

        // getActiveLyricLine must return readable static LyricLine
        val activeObj = parsed.getActiveLyricLine(10000L)
        assertNotNull(activeObj)
        assertEquals("Just a line without timestamp", activeObj?.text)
        assertEquals(0L, activeObj?.timeMs)
    }

    @Test
    fun testPlainLyricsWithBlankLinesAndSectionHeaders() {
        val plainText = """
            [ti:Ignore Tag]
            [ar:Ignore Artist]

            [Verse 1]
            First line of verse

            Second line of verse
            
            [Chorus]
            Chorus line
        """.trimIndent()

        val parsed = LrcParser.parse(plainText)

        assertFalse(parsed.isSynced)
        assertEquals(5, parsed.lines.size)
        assertEquals("[Verse 1]", parsed.lines[0].text)
        assertEquals("First line of verse", parsed.lines[1].text)
        assertEquals("Second line of verse", parsed.lines[2].text)
        assertEquals("[Chorus]", parsed.lines[3].text)
        assertEquals("Chorus line", parsed.lines[4].text)
        assertTrue(parsed.lines.all { it.timeMs == 0L })
    }

    @Test
    fun testEmptyLyricsSafeHandling() {
        val parsed = LrcParser.parse("")
        assertFalse(parsed.isSynced)
        assertTrue(parsed.lines.isEmpty())
        assertNull(parsed.getActiveLine(0L))
        assertNull(parsed.getActiveLyricLine(0L))
        assertEquals(-1, parsed.findActiveIndex(0L))
    }

    @Test
    fun testTtmlFormatIsRejectedAsUnsupported() {
        val ttmlContent = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <head>
                <metadata>
                  <ttm:title>Unsupported TTML</ttm:title>
                </metadata>
              </head>
              <body>
                <div>
                  <p begin="00:00:01.000" end="00:00:04.000">Line One</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        assertTrue("isTtml must return true for TTML content", LrcParser.isTtml(ttmlContent))

        try {
            LrcParser.parse(ttmlContent)
            fail("Expected IllegalArgumentException when parsing TTML format")
        } catch (e: IllegalArgumentException) {
            assertTrue(
                "Exception message should indicate TTML is unsupported",
                e.message?.contains("TTML is not supported", ignoreCase = true) == true
            )
        }
    }

    @Test
    fun testIsTtmlDetector() {
        val standardLrc = "[00:01.00]Normal line"
        val plainLyrics = "Just plain text without xml tags"
        val ttmlSnippet = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><p>Hello</p></body></tt>"

        assertFalse(LrcParser.isTtml(standardLrc))
        assertFalse(LrcParser.isTtml(plainLyrics))
        assertTrue(LrcParser.isTtml(ttmlSnippet))
    }

    @Test
    fun testLrcWithBlankLinesAndFractionalTimestamps() {
        val lrc = """
            [ti:Fractional & Blank Lines Test]

            [00:01.5]One digit fraction

            [00:03.25]Two digit fraction
            
            [00:05.123]Three digit millisecond fraction

            [00:07.00][00:09.50]Repeated lyric with multi-timestamps
        """.trimIndent()

        val parsed = LrcParser.parse(lrc)
        assertTrue(parsed.isSynced)
        assertEquals(5, parsed.lines.size)
        assertEquals(1500L, parsed.lines[0].timeMs)
        assertEquals("One digit fraction", parsed.lines[0].text)
        assertEquals(3250L, parsed.lines[1].timeMs)
        assertEquals("Two digit fraction", parsed.lines[1].text)
        assertEquals(5123L, parsed.lines[2].timeMs)
        assertEquals("Three digit millisecond fraction", parsed.lines[2].text)
        assertEquals(7000L, parsed.lines[3].timeMs)
        assertEquals("Repeated lyric with multi-timestamps", parsed.lines[3].text)
        assertEquals(9500L, parsed.lines[4].timeMs)
        assertEquals("Repeated lyric with multi-timestamps", parsed.lines[4].text)
    }
}
