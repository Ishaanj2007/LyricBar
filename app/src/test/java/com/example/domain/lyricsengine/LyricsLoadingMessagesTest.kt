package com.example.domain.lyricsengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsLoadingMessagesTest {

    @Test
    fun `message pool is non-empty, varied, and avoids naming specific providers or guaranteeing success`() {
        val pool = LyricsLoadingMessages.messages
        assertTrue("Message pool should contain multiple entries", pool.size >= 8)
        assertEquals("Message pool should not contain duplicates", pool.size, pool.distinct().size)

        val forbiddenTerms = listOf(
            "lrclib", "lrc.red", "lyricsplus", "kugou", "lyrica", "liriqo", "unison",
            "guaranteed", "will find", "always found"
        )
        for (msg in pool) {
            assertTrue("Message must be non-blank", msg.isNotBlank())
            val lower = msg.lowercase()
            for (term in forbiddenTerms) {
                assertFalse("Message '$msg' should not contain '$term'", lower.contains(term))
            }
        }
    }

    @Test
    fun `rotation interval is calm around 2_8 seconds`() {
        assertEquals(2800L, LyricsLoadingMessages.LOADING_MESSAGE_INTERVAL_MS)
    }

    @Test
    fun `pickInitialIndex is deterministic per trackKey and nextIndex never repeats immediately`() {
        val keyA = "laufey - from the start"
        val firstA = LyricsLoadingMessages.pickInitialIndex(keyA)
        val secondA = LyricsLoadingMessages.pickInitialIndex(keyA)
        assertEquals(firstA, secondA)
        assertTrue(firstA in LyricsLoadingMessages.messages.indices)

        var current = firstA
        repeat(LyricsLoadingMessages.messages.size) {
            val next = LyricsLoadingMessages.nextIndex(current)
            assertNotEquals("nextIndex must not repeat current index", current, next)
            assertTrue(next in LyricsLoadingMessages.messages.indices)
            current = next
        }
        assertEquals("Full cycle should return to initial index", firstA, current)
    }

    @Test
    fun `messageAt safely normalizes negative and out-of-bounds indices`() {
        val size = LyricsLoadingMessages.messages.size
        assertEquals(LyricsLoadingMessages.messageAt(0), LyricsLoadingMessages.messageAt(size))
        assertEquals(LyricsLoadingMessages.messageAt(size - 1), LyricsLoadingMessages.messageAt(-1))
    }
}
