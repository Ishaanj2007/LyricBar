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
    }
}
