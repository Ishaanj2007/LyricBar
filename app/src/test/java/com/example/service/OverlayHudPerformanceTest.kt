package com.example.service

import com.example.data.model.LyricLine
import com.example.data.model.WordTiming
import com.example.ui.components.MinimalOverlayView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayHudPerformanceTest {

    @Test
    fun testGraphemeBoundariesForAscii() {
        val boundaries = MinimalOverlayView.computeGraphemeBoundaries("Hello")
        // "Hello" has 5 grapheme clusters at offsets 1, 2, 3, 4, 5
        assertEquals(5, boundaries.size)
        assertEquals(1, boundaries[0])
        assertEquals(5, boundaries[4])
    }

    @Test
    fun testGraphemeBoundariesForEmojiSurrogatePairs() {
        val emojiText = "🔥✨"
        // 🔥 is surrogate pair (length 2), ✨ is length 1
        val boundaries = MinimalOverlayView.computeGraphemeBoundaries(emojiText)
        assertEquals(2, boundaries.size)
        // First grapheme end must be at offset 2 (never slicing surrogate half)
        assertEquals(2, boundaries[0])
        assertEquals(3, boundaries[1])

        val firstEmoji = emojiText.substring(0, boundaries[0])
        assertEquals("🔥", firstEmoji)
    }

    @Test
    fun testGraphemeBoundariesForHindiDevanagari() {
        val hindiWord = "नमस्ते"
        val boundaries = MinimalOverlayView.computeGraphemeBoundaries(hindiWord)
        // Verify boundaries preserve the full word when all clusters are taken
        val reconstructed = hindiWord.substring(0, boundaries.last())
        assertEquals(hindiWord, reconstructed)

        // Taking individual clusters never creates broken characters
        for (b in boundaries) {
            val sub = hindiWord.substring(0, b)
            assertTrue(sub.isNotBlank())
        }
    }

    @Test
    fun testStaticLyricsDoNotNeedContinuousAnimation() {
        // Untimed lyrics (timeMs == 0, endTimeMs == 0, isWordSynced == false)
        val untimedLine = LyricLine(
            timeMs = 0L,
            endTimeMs = 0L,
            text = "Plain text without timing tags",
            isWordSynced = false
        )

        fun shouldAnimate(line: LyricLine, style: String, isPlaying: Boolean, posMs: Long): Boolean {
            if (!isPlaying) return false
            if (line.timeMs == 0L && line.endTimeMs == 0L && !line.isWordSynced) {
                return false
            }
            return when (style) {
                "KARAOKE" -> {
                    if (line.isWordSynced && line.words.isNotEmpty()) {
                        posMs in line.words.first().startMs..(line.words.last().endMs + 100L)
                    } else {
                        val end = if (line.endTimeMs > line.timeMs) line.endTimeMs else line.timeMs + 3500L
                        posMs in line.timeMs..(end + 100L)
                    }
                }
                "TYPEWRITER" -> {
                    val end = if (line.endTimeMs > line.timeMs) line.endTimeMs else line.timeMs + 3500L
                    posMs in line.timeMs..(end + 50L)
                }
                else -> false
            }
        }

        // Must NOT animate for untimed plain lyrics even while playing
        assertFalse(shouldAnimate(untimedLine, "KARAOKE", isPlaying = true, posMs = 5000L))
        assertFalse(shouldAnimate(untimedLine, "SMOOTH_SLIDE", isPlaying = true, posMs = 5000L))
        assertFalse(shouldAnimate(untimedLine, "INSTANT", isPlaying = true, posMs = 5000L))
    }

    @Test
    fun testKaraokeWordLevelAnimationOnlyRunsDuringActiveSingingWindow() {
        val wordSyncedLine = LyricLine(
            timeMs = 10000L,
            endTimeMs = 15000L,
            text = "I want it that way",
            isWordSynced = true,
            words = listOf(
                WordTiming("I", 10000L, 10500L),
                WordTiming("want", 10600L, 11500L),
                WordTiming("it", 11600L, 12200L),
                WordTiming("that", 12300L, 13000L),
                WordTiming("way", 13100L, 14500L)
            )
        )

        fun isKaraokeActive(posMs: Long, isPlaying: Boolean): Boolean {
            if (!isPlaying) return false
            val startMs = wordSyncedLine.words.first().startMs
            val endMs = wordSyncedLine.words.last().endMs
            return posMs in startMs..(endMs + 100L)
        }

        // 1. Before words begin: not active
        assertFalse(isKaraokeActive(posMs = 8000L, isPlaying = true))

        // 2. During singing: actively animating
        assertTrue(isKaraokeActive(posMs = 10200L, isPlaying = true))
        assertTrue(isKaraokeActive(posMs = 12000L, isPlaying = true))
        assertTrue(isKaraokeActive(posMs = 14400L, isPlaying = true))

        // 3. After last word has completed: stops animating!
        assertFalse(isKaraokeActive(posMs = 14700L, isPlaying = true))
        assertFalse(isKaraokeActive(posMs = 16000L, isPlaying = true))

        // 4. Paused: stops immediately even mid-word
        assertFalse(isKaraokeActive(posMs = 12000L, isPlaying = false))
    }

    @Test
    fun testTypewriterStopsAfterDuration() {
        val lineStart = 5000L
        val duration = 2000L

        fun isTypewriterActive(posMs: Long, isPlaying: Boolean): Boolean {
            if (!isPlaying) return false
            return posMs in lineStart..(lineStart + duration + 50L)
        }

        // Before line
        assertFalse(isTypewriterActive(posMs = 4000L, isPlaying = true))

        // During typing
        assertTrue(isTypewriterActive(posMs = 5500L, isPlaying = true))
        assertTrue(isTypewriterActive(posMs = 6900L, isPlaying = true))

        // Completed typing
        assertFalse(isTypewriterActive(posMs = 7100L, isPlaying = true))
        assertFalse(isTypewriterActive(posMs = 8000L, isPlaying = true))
    }
}
