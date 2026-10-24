package com.example.domain.lyricsengine

import com.example.data.model.PlaybackStateInfo
import com.example.data.model.TrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerIdentificationTest {

    @Test
    fun testResolveBlankPackageReturnsEmptyString() {
        assertEquals("", TrackMetadata.resolveAppName(""))
        assertEquals("", TrackMetadata.resolveAppName("   "))
    }

    @Test
    fun testResolveKnownStreamingPlayers() {
        assertEquals("YouTube", TrackMetadata.resolveAppName("com.google.android.youtube"))
        assertEquals("YouTube Music", TrackMetadata.resolveAppName("com.google.android.apps.youtube.music"))
        assertEquals("Spotify", TrackMetadata.resolveAppName("com.spotify.music"))
        assertEquals("Spotify", TrackMetadata.resolveAppName("com.spotify.lite"))
        assertEquals("Apple Music", TrackMetadata.resolveAppName("com.apple.android.music"))
        assertEquals("SoundCloud", TrackMetadata.resolveAppName("com.soundcloud.android"))
        assertEquals("Amazon Music", TrackMetadata.resolveAppName("com.amazon.mp3"))
        assertEquals("TIDAL", TrackMetadata.resolveAppName("com.aspiro.tidal"))
        assertEquals("Deezer", TrackMetadata.resolveAppName("deezer.android.app"))
        assertEquals("Pandora", TrackMetadata.resolveAppName("com.pandora.android"))
        assertEquals("VLC", TrackMetadata.resolveAppName("org.videolan.vlc"))
        assertEquals("Poweramp", TrackMetadata.resolveAppName("com.maxmpz.audioplayer"))
        assertEquals("foobar2000", TrackMetadata.resolveAppName("com.foobar2000.foobar2000"))
        assertEquals("Musicolet", TrackMetadata.resolveAppName("com.musicolet"))
    }

    @Test
    fun testResolveUnknownPackageFallback() {
        // Fallback capitalizes last segment
        assertEquals("Mediaplayer", TrackMetadata.resolveAppName("com.custom.mediaplayer"))
        assertEquals("Audiostation", TrackMetadata.resolveAppName("org.foo.audiostation"))
    }

    @Test
    fun testPlaybackStateInfoHasActiveSession() {
        val defaultState = PlaybackStateInfo()
        assertFalse(defaultState.hasActiveSession)
        assertFalse(defaultState.isPlaying)

        val activeState = defaultState.copy(
            hasActiveSession = true,
            isPlaying = true,
            track = TrackMetadata(
                title = "Bohemian Rhapsody",
                artist = "Queen",
                sourcePackage = "com.spotify.music",
                sourceAppName = "Spotify"
            )
        )
        assertTrue(activeState.hasActiveSession)
        assertTrue(activeState.isPlaying)
        assertEquals("Spotify", activeState.track?.sourceAppName)
    }

    @Test
    fun testPlayerSwitchWithSameSongDistinguishedByPackage() {
        val trackA = TrackMetadata(
            title = "Starboy",
            artist = "The Weeknd",
            sourcePackage = "com.google.android.apps.youtube.music",
            sourceAppName = "YouTube Music"
        )

        val trackB = TrackMetadata(
            title = "Starboy",
            artist = "The Weeknd",
            sourcePackage = "com.spotify.music",
            sourceAppName = "Spotify"
        )

        // Song keys are identical because clean artist and title match
        assertEquals(trackA.key, trackB.key)
        // But source package and source app name differ
        assertFalse(trackA.sourcePackage == trackB.sourcePackage)
        assertEquals("YouTube Music", trackA.sourceAppName)
        assertEquals("Spotify", trackB.sourceAppName)
    }
}
