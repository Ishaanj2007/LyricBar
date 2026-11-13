package com.example.ui.screens

import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingScreenStateAndResponsiveTest {

    @Test
    fun `responsive metrics adapt across 320dp to 480dp widths and standard heights`() {
        val widths = listOf(320f, 360f, 393f, 412f, 430f, 480f)
        for (w in widths) {
            val metrics = computeResponsiveNowPlayingMetrics(
                availableWidthDp = w,
                availableHeightDp = 820f,
                fontScale = 1.0f
            )
            assertFalse("Portrait should not be landscape for width $w", metrics.isLandscape)
            assertTrue("Artwork size must be positive and bounded for width $w", metrics.artworkSizeDp in 128f..300f)
            assertTrue(
                "Artwork must not exceed available content width for width $w",
                metrics.artworkSizeDp <= (w - metrics.horizontalPaddingDp * 2f)
            )
        }
    }

    @Test
    fun `responsive metrics shrink artwork first on short-height devices and enable scroll fallback when tight`() {
        val tallMetrics = computeResponsiveNowPlayingMetrics(
            availableWidthDp = 393f,
            availableHeightDp = 852f,
            fontScale = 1.0f
        )
        val shortMetrics = computeResponsiveNowPlayingMetrics(
            availableWidthDp = 393f,
            availableHeightDp = 600f,
            fontScale = 1.0f
        )

        assertTrue("Short height device must be flagged as short", shortMetrics.isShortHeight)
        assertTrue(
            "Short height device must reduce artwork size before hiding controls",
            shortMetrics.artworkSizeDp < tallMetrics.artworkSizeDp
        )
        assertTrue("Very short height device enables scroll fallback", shortMetrics.requiresScrollFallback)
    }

    @Test
    fun `responsive metrics handle font scales 1_0 to 1_5 gracefully`() {
        val scales = listOf(1.0f, 1.15f, 1.3f, 1.5f)
        for (scale in scales) {
            val metrics = computeResponsiveNowPlayingMetrics(
                availableWidthDp = 360f,
                availableHeightDp = 740f,
                fontScale = scale
            )
            assertTrue("Artwork size remains valid at fontScale $scale", metrics.artworkSizeDp >= 128f)
            assertTrue("Play button remains accessible >= 48dp at fontScale $scale", metrics.playButtonInnerDp >= 48f)
        }
    }

    @Test
    fun `responsive metrics detect landscape orientation cleanly`() {
        val landscape = computeResponsiveNowPlayingMetrics(
            availableWidthDp = 800f,
            availableHeightDp = 393f,
            fontScale = 1.0f
        )
        assertTrue(landscape.isLandscape)
        assertTrue(landscape.artworkSizeDp in 120f..240f)
    }

    @Test
    fun `source badge displays actual provider or Local Import and never fabricates`() {
        val lrclibLyrics = ParsedLyrics(
            title = "From The Start",
            artist = "Laufey",
            isSynced = true,
            lines = listOf(LyricLine(1000L, "Don't you notice how")),
            plainText = "Don't you notice how",
            source = "LRCLIB (Cached)"
        )
        assertEquals("LRCLIB", resolveNowPlayingSourceBadge(lrclibLyrics, hasValidLyrics = true))

        val importedLyrics = lrclibLyrics.copy(source = "Imported")
        assertEquals("Local Import", resolveNowPlayingSourceBadge(importedLyrics, hasValidLyrics = true))

        val kugouLyrics = lrclibLyrics.copy(source = "KuGou")
        assertEquals("KuGou", resolveNowPlayingSourceBadge(kugouLyrics, hasValidLyrics = true))

        // Must return null when no valid lyrics exist
        assertNull(resolveNowPlayingSourceBadge(null, hasValidLyrics = false))
        assertNull(resolveNowPlayingSourceBadge(lrclibLyrics, hasValidLyrics = false))

        // Must never return "Automatic" or "AUTO" preference label as an actual provider
        val invalidAutoSource = lrclibLyrics.copy(source = "Automatic")
        assertNull(resolveNowPlayingSourceBadge(invalidAutoSource, hasValidLyrics = true))
    }

    @Test
    fun `live lyrics card state handles normal playback, instrumental, paused, and disconnected states`() {
        val track = TrackMetadata(
            title = "From The Start",
            artist = "Laufey",
            durationSec = 234,
            sourceAppName = "Spotify"
        )
        val syncedLyrics = ParsedLyrics(
            title = "From The Start",
            artist = "Laufey",
            isSynced = true,
            lines = listOf(
                LyricLine(10_000L, "Don't you notice how"),
                LyricLine(15_000L, "I get quiet when there's no one else around?")
            ),
            plainText = "Don't you notice how\nI get quiet when there's no one else around?",
            source = "LRCLIB"
        )

        // 1. Normal active synced playback
        val activeState = resolveNowPlayingLyricsCardState(
            hasSession = true,
            isPlaying = true,
            track = track,
            isLoadingLyrics = false,
            lyrics = syncedLyrics,
            hasValidLyrics = true,
            isSynced = true,
            activeLyric = "Don't you notice how",
            prevLyric = null,
            nextLyric = "I get quiet when there's no one else around?"
        )
        assertEquals("LIVE LYRICS", activeState.badgeText)
        assertTrue(activeState.isBadgeAccent)
        assertEquals("Don't you notice how", activeState.primaryLine)
        assertEquals("I get quiet when there's no one else around?", activeState.secondaryLine)
        assertFalse(activeState.showInstrumentalWaveform)

        // 2. Instrumental passage (activeLyric is null during intro/interlude)
        val instrumentalState = resolveNowPlayingLyricsCardState(
            hasSession = true,
            isPlaying = true,
            track = track,
            isLoadingLyrics = false,
            lyrics = syncedLyrics,
            hasValidLyrics = true,
            isSynced = true,
            activeLyric = null,
            prevLyric = null,
            nextLyric = "Don't you notice how"
        )
        assertEquals("LIVE LYRICS", instrumentalState.badgeText)
        assertEquals("♪ Instrumental ♪", instrumentalState.primaryLine)
        assertEquals("Lyrics will resume with vocals...", instrumentalState.secondaryLine)
        assertTrue(instrumentalState.showInstrumentalWaveform)

        // 3. Paused playback — must clearly indicate paused status
        val pausedState = resolveNowPlayingLyricsCardState(
            hasSession = true,
            isPlaying = false,
            track = track,
            isLoadingLyrics = false,
            lyrics = syncedLyrics,
            hasValidLyrics = true,
            isSynced = true,
            activeLyric = "Don't you notice how",
            prevLyric = null,
            nextLyric = "I get quiet when there's no one else around?"
        )
        assertEquals("LYRICS PAUSED", pausedState.badgeText)
        assertFalse(pausedState.isBadgeAccent)
        assertEquals("Playback paused — resume to continue live sync", pausedState.secondaryLine)

        // 4. Disconnected media session — must not present stale lyrics as live
        val disconnectedState = resolveNowPlayingLyricsCardState(
            hasSession = false,
            isPlaying = false,
            track = track,
            isLoadingLyrics = false,
            lyrics = syncedLyrics,
            hasValidLyrics = true,
            isSynced = true,
            activeLyric = "Don't you notice how",
            prevLyric = null,
            nextLyric = null
        )
        assertEquals("SESSION DISCONNECTED", disconnectedState.badgeText)
        assertFalse(disconnectedState.isBadgeAccent)
        assertEquals("Media session disconnected", disconnectedState.primaryLine)
    }

    @Test
    fun `timestamp formatting handles valid and missing durations accurately`() {
        assertEquals("0:00", formatNowPlayingTimestamp(0L))
        assertEquals("1:26", formatNowPlayingTimestamp(86_000L))
        assertEquals("3:54", formatNowPlayingTimestamp(234_000L))
        assertEquals("1:02:05", formatNowPlayingTimestamp(3_725_000L))
        assertEquals("--:--", formatNowPlayingTimestamp(-1L))
    }
}
