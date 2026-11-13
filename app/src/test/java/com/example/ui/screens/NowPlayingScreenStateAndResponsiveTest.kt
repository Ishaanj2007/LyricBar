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
    fun `responsive metrics dynamic vertical paddings scale with height and close gap tightly`() {
        val heights = listOf(600f, 740f, 852f, 920f)
        for (h in heights) {
            val metrics = computeResponsiveNowPlayingMetrics(
                availableWidthDp = 393f,
                availableHeightDp = h,
                fontScale = 1.0f
            )
            assertTrue(
                "artworkToInfoPaddingDp must keep metadata closely tied to artwork (8..13dp) for height $h",
                metrics.artworkToInfoPaddingDp in 8f..13f
            )
            assertTrue(
                "badgeToLyricsPaddingDp must remain compact (7..12dp) for height $h",
                metrics.badgeToLyricsPaddingDp in 7f..12f
            )
            assertTrue(
                "lyricsToProgressPaddingDp must keep progress connected to lyrics (8..12dp) for height $h",
                metrics.lyricsToProgressPaddingDp in 8f..12f
            )
            assertTrue(
                "progressToControlsPaddingDp must keep controls connected to progress (9..14dp) for height $h",
                metrics.progressToControlsPaddingDp in 9f..14f
            )
        }

        val standardPhone = computeResponsiveNowPlayingMetrics(
            availableWidthDp = 393f,
            availableHeightDp = 716f,
            fontScale = 1.0f
        )
        assertFalse("Standard portrait viewport should not require scrolling", standardPhone.requiresScrollFallback)
        assertTrue("Standard portrait viewport preserves generous artwork size", standardPhone.artworkSizeDp >= 240f)
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
    fun `responsive metrics detect landscape orientation cleanly across phone and tablet sizes`() {
        val landscape800x393 = computeResponsiveNowPlayingMetrics(
            availableWidthDp = 800f,
            availableHeightDp = 393f,
            fontScale = 1.0f
        )
        assertTrue(landscape800x393.isLandscape)
        assertTrue(landscape800x393.artworkSizeDp in 120f..240f)
        assertTrue(landscape800x393.playButtonInnerDp >= 48f)

        val phoneLandscapeSizes = listOf(
            540f to 312f, // 640x360 usable after nav rail + insets
            700f to 312f, // 800x360 usable
            815f to 364f, // 915x412 usable
            860f to 384f  // 960x432 usable
        )
        val fontScales = listOf(1.0f, 1.3f, 1.5f)
        for ((w, h) in phoneLandscapeSizes) {
            for (scale in fontScales) {
                val m = computeResponsiveNowPlayingMetrics(
                    availableWidthDp = w,
                    availableHeightDp = h,
                    fontScale = scale
                )
                assertTrue("Expected landscape for ${w}x${h}", m.isLandscape)
                assertFalse("Should not treat landscape as portrait short height", m.isShortHeight)
                assertTrue("Artwork size stays bounded for ${w}x${h}: ${m.artworkSizeDp}", m.artworkSizeDp in 96f..240f)
                assertTrue("Play/Pause target must remain >= 48dp at fontScale $scale", m.playButtonInnerDp >= 48f)
            }
        }

        val tabletLandscape = computeResponsiveNowPlayingMetrics(
            availableWidthDp = 1170f,
            availableHeightDp = 752f,
            fontScale = 1.0f
        )
        assertTrue("1280x800 tablet landscape must use landscape layout", tabletLandscape.isLandscape)
        assertTrue("Tablet landscape scales artwork proportionally", tabletLandscape.artworkSizeDp >= 220f)
        assertTrue("Tablet landscape keeps full play button size", tabletLandscape.playButtonOuterDp >= 74f)
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
