package com.example.service

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionEligibilityTest {

    @Test
    fun `dedicated music apps with valid metadata are eligible`() {
        val spotify = MediaSessionSnapshot(
            packageName = "com.spotify.music",
            appName = "Spotify",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = true,
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "After Hours",
            durationMs = 200_000L
        )
        val ytm = MediaSessionSnapshot(
            packageName = "com.google.android.apps.youtube.music",
            appName = "YouTube Music",
            playbackState = PlaybackState.STATE_PAUSED,
            hasMetadata = true,
            title = "Starboy",
            artist = "The Weeknd",
            album = "Starboy",
            durationMs = 230_000L
        )

        val spotifyResult = MediaSessionEligibilityEvaluator.evaluate(spotify)
        val ytmResult = MediaSessionEligibilityEvaluator.evaluate(ytm)

        assertTrue(spotifyResult.isEligible)
        assertEquals(EligibilityReason.ELIGIBLE_DEDICATED_MUSIC, spotifyResult.reason)
        assertTrue(ytmResult.isEligible)
        assertEquals(MediaAppCategory.DEDICATED_MUSIC, ytmResult.category)
    }

    @Test
    fun `normal YouTube video without music metadata is ineligible`() {
        val techReview = MediaSessionSnapshot(
            packageName = "com.google.android.youtube",
            appName = "YouTube",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = true,
            title = "M4 MacBook Pro Review: Two Months Later",
            artist = "MKBHD",
            album = "",
            durationMs = 780_000L,
            hasArtwork = true
        )
        val ambiguousVlog = MediaSessionSnapshot(
            packageName = "com.google.android.youtube",
            appName = "YouTube",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = true,
            title = "A Rainy Weekend in Kyoto",
            artist = "Travel Diary",
            album = "",
            durationMs = 420_000L,
            hasArtwork = true
        )
        val shorts = MediaSessionSnapshot(
            packageName = "com.google.android.youtube",
            appName = "YouTube",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = true,
            title = "Wait for the plot twist #shorts",
            artist = "ShortsCreator",
            durationMs = 25_000L
        )

        val resReview = MediaSessionEligibilityEvaluator.evaluate(techReview)
        val resVlog = MediaSessionEligibilityEvaluator.evaluate(ambiguousVlog)
        val resShorts = MediaSessionEligibilityEvaluator.evaluate(shorts)

        assertFalse(resReview.isEligible)
        assertFalse(resVlog.isEligible)
        assertEquals(EligibilityReason.INELIGIBLE_AMBIGUOUS_VIDEO_SESSION, resVlog.reason)
        assertFalse(resShorts.isEligible)
    }

    @Test
    fun `YouTube session with strong music metadata is eligible rather than blindly blacklisted`() {
        val ytArtTrackWithAlbum = MediaSessionSnapshot(
            packageName = "com.google.android.youtube",
            appName = "YouTube",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = true,
            title = "Nightcall",
            artist = "Kavinsky",
            album = "OutRun",
            durationMs = 258_000L
        )
        val ytTopicChannel = MediaSessionSnapshot(
            packageName = "com.google.android.youtube",
            appName = "YouTube",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = true,
            title = "Midnight City",
            artist = "M83 - Topic",
            durationMs = 243_000L
        )
        val ytOfficialMusicVideo = MediaSessionSnapshot(
            packageName = "com.google.android.youtube",
            appName = "YouTube",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = true,
            title = "Bohemian Rhapsody (Official Music Video)",
            artist = "Queen",
            durationMs = 354_000L
        )
        val ytReactionToMusicVideo = MediaSessionSnapshot(
            packageName = "com.google.android.youtube",
            appName = "YouTube",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = true,
            title = "Vocal Coach Reaction to Bohemian Rhapsody (Official Music Video)",
            artist = "ReactChannel",
            durationMs = 450_000L
        )

        assertTrue(MediaSessionEligibilityEvaluator.evaluate(ytArtTrackWithAlbum).isEligible)
        assertTrue(MediaSessionEligibilityEvaluator.evaluate(ytTopicChannel).isEligible)
        assertTrue(MediaSessionEligibilityEvaluator.evaluate(ytOfficialMusicVideo).isEligible)
        assertFalse(MediaSessionEligibilityEvaluator.evaluate(ytReactionToMusicVideo).isEligible)
    }

    @Test
    fun `browser generic video playback is ineligible while web music with strong metadata is eligible`() {
        val chromeGenericVideo = MediaSessionSnapshot(
            packageName = "com.android.chrome",
            appName = "Chrome",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = true,
            title = "Funny Cats Compilation 2026",
            artist = "youtube.com",
            displaySubtitle = "youtube.com",
            durationMs = 310_000L
        )
        val chromeNoAlbumWebVideo = MediaSessionSnapshot(
            packageName = "com.android.chrome",
            appName = "Chrome",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = true,
            title = "Morning News Highlights",
            artist = "Daily News",
            album = "YouTube",
            durationMs = 240_000L
        )
        val chromeSpotifyWeb = MediaSessionSnapshot(
            packageName = "com.android.chrome",
            appName = "Chrome",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = true,
            title = "Let It Happen",
            artist = "Tame Impala",
            album = "Currents",
            mediaUri = "https://open.spotify.com/track/2X485T9Z5Ly0xyaghN73ed",
            durationMs = 467_000L
        )

        assertFalse(MediaSessionEligibilityEvaluator.evaluate(chromeGenericVideo).isEligible)
        assertFalse(MediaSessionEligibilityEvaluator.evaluate(chromeNoAlbumWebVideo).isEligible)
        assertTrue(MediaSessionEligibilityEvaluator.evaluate(chromeSpotifyWeb).isEligible)
    }

    @Test
    fun `unknown package with valid music metadata is eligible`() {
        val unknownMusicPlayer = MediaSessionSnapshot(
            packageName = "org.custom.foss.musicplayer",
            appName = "Musicplayer",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = true,
            title = "Instant Crush",
            artist = "Daft Punk",
            album = "Random Access Memories",
            durationMs = 337_000L
        )

        val result = MediaSessionEligibilityEvaluator.evaluate(unknownMusicPlayer)
        assertTrue(result.isEligible)
        assertEquals(EligibilityReason.ELIGIBLE_MUSIC_WITH_METADATA, result.reason)
    }

    @Test
    fun `podcast and audiobook apps and spoken genres are ineligible`() {
        val pocketCasts = MediaSessionSnapshot(
            packageName = "au.com.shiftyjelly.pocketcasts",
            appName = "Pocket Casts",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = true,
            title = "Episode 42: The Economics of Streaming",
            artist = "Tech Show",
            album = "Tech Podcast",
            durationMs = 2_400_000L
        )
        val spotifyPodcast = MediaSessionSnapshot(
            packageName = "com.spotify.music",
            appName = "Spotify",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = true,
            title = "EP #118 Deep Dive Conversation",
            artist = "Host Name",
            genre = "Podcast",
            durationMs = 3_000_000L
        )

        assertFalse(MediaSessionEligibilityEvaluator.evaluate(pocketCasts).isEligible)
        assertFalse(MediaSessionEligibilityEvaluator.evaluate(spotifyPodcast).isEligible)
    }

    @Test
    fun `generic sessions missing artist or with generic title are ineligible`() {
        val onlyTitle = MediaSessionSnapshot(
            packageName = "com.example.generic",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = true,
            title = "Some Clip",
            artist = ""
        )
        val unknownArtist = MediaSessionSnapshot(
            packageName = "com.example.generic",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = true,
            title = "Some Clip",
            artist = "Unknown Artist",
            durationMs = 180_000L
        )
        val genericTitle = MediaSessionSnapshot(
            packageName = "com.spotify.music",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = true,
            title = "Media Playback",
            artist = "System"
        )
        val noMetadata = MediaSessionSnapshot(
            packageName = "com.spotify.music",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = false
        )

        assertFalse(MediaSessionEligibilityEvaluator.evaluate(onlyTitle).isEligible)
        assertFalse(MediaSessionEligibilityEvaluator.evaluate(unknownArtist).isEligible)
        assertFalse(MediaSessionEligibilityEvaluator.evaluate(genericTitle).isEligible)
        assertFalse(MediaSessionEligibilityEvaluator.evaluate(noMetadata).isEligible)
    }

    @Test
    fun `stopped sessions and buffering sessions with insufficient metadata are ineligible`() {
        val stoppedSpotify = MediaSessionSnapshot(
            packageName = "com.spotify.music",
            playbackState = PlaybackState.STATE_STOPPED,
            hasMetadata = true,
            title = "Viva La Vida",
            artist = "Coldplay",
            durationMs = 242_000L
        )
        val bufferingIncomplete = MediaSessionSnapshot(
            packageName = "com.example.unknownplayer",
            playbackState = PlaybackState.STATE_BUFFERING,
            hasMetadata = true,
            title = "Buffering Track",
            artist = "Streamer",
            album = "",
            durationMs = 0L
        )
        val pausedSpotifyWithValidMetadata = MediaSessionSnapshot(
            packageName = "com.spotify.music",
            playbackState = PlaybackState.STATE_PAUSED,
            hasMetadata = true,
            title = "Viva La Vida",
            artist = "Coldplay",
            durationMs = 242_000L
        )

        assertFalse(MediaSessionEligibilityEvaluator.evaluate(stoppedSpotify).isEligible)
        assertFalse(MediaSessionEligibilityEvaluator.evaluate(bufferingIncomplete).isEligible)
        assertTrue(MediaSessionEligibilityEvaluator.evaluate(pausedSpotifyWithValidMetadata).isEligible)
    }

    @Test
    fun `toTrackMetadata never fabricates Unknown Artist`() {
        val incomplete = MediaSessionSnapshot(
            packageName = "com.example.app",
            playbackState = PlaybackState.STATE_PLAYING,
            hasMetadata = true,
            title = "Track Without Artist",
            artist = ""
        )
        assertNull(incomplete.toTrackMetadata())
    }

    @Test
    fun `selector never selects ineligible playing session and prefers eligible playing over eligible paused`() {
        val youtubePlaying = MediaControllerCandidate(
            token = "yt-token",
            snapshot = MediaSessionSnapshot(
                packageName = "com.google.android.youtube",
                playbackState = PlaybackState.STATE_PLAYING,
                hasMetadata = true,
                title = "Top 10 Keyboard Mods",
                artist = "KeyboardChannel",
                durationMs = 600_000L
            ),
            ref = "yt-controller"
        )
        val spotifyPaused = MediaControllerCandidate(
            token = "spotify-token",
            snapshot = MediaSessionSnapshot(
                packageName = "com.spotify.music",
                playbackState = PlaybackState.STATE_PAUSED,
                hasMetadata = true,
                title = "Clocks",
                artist = "Coldplay",
                album = "A Rush of Blood to the Head",
                durationMs = 307_000L
            ),
            ref = "spotify-controller"
        )
        val ytMusicPlaying = MediaControllerCandidate(
            token = "ytm-token",
            snapshot = MediaSessionSnapshot(
                packageName = "com.google.android.apps.youtube.music",
                playbackState = PlaybackState.STATE_PLAYING,
                hasMetadata = true,
                title = "Fix You",
                artist = "Coldplay",
                album = "X&Y",
                durationMs = 295_000L
            ),
            ref = "ytm-controller"
        )

        // 1. Only YouTube normal video exists -> selected must be null (no fallback)
        val onlyIneligible = MediaSessionSelector.selectBestController(
            candidates = listOf(youtubePlaying),
            currentToken = "yt-token"
        )
        assertNull(onlyIneligible.selected)
        assertTrue(onlyIneligible.hadIneligibleControllers)

        // 2. YouTube playing + Spotify paused -> YouTube skipped, Spotify paused preserved
        val ytOverPausedMusic = MediaSessionSelector.selectBestController(
            candidates = listOf(youtubePlaying, spotifyPaused),
            currentToken = "spotify-token"
        )
        assertEquals("spotify-token", ytOverPausedMusic.selected?.token)

        // 3. YouTube playing + Spotify paused + YT Music playing -> YT Music playing wins
        val eligiblePlayingWins = MediaSessionSelector.selectBestController(
            candidates = listOf(youtubePlaying, spotifyPaused, ytMusicPlaying),
            currentToken = "spotify-token"
        )
        assertEquals("ytm-token", eligiblePlayingWins.selected?.token)
    }
}
