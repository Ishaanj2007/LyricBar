package com.example.data

import com.example.data.model.TrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMetadataValidationTest {

    @Test
    fun testValidTrackMetadata() {
        val track = TrackMetadata(
            title = "Viva La Vida",
            artist = "Coldplay",
            album = "Viva la Vida or Death and All His Friends"
        )
        assertTrue(track.isValidTrack)
        assertEquals("coldplay - viva la vida", track.key)
    }

    @Test
    fun testBlankTitleIsInvalid() {
        val emptyTitleTrack = TrackMetadata(title = "", artist = "Queen")
        assertFalse(emptyTitleTrack.isValidTrack)

        val whitespaceTitleTrack = TrackMetadata(title = "   ", artist = "Queen")
        assertFalse(whitespaceTitleTrack.isValidTrack)
    }

    @Test
    fun testMissingOrBlankArtistFallsBackGracefully() {
        val track = TrackMetadata(title = "Unknown Song", artist = "")
        assertTrue(track.isValidTrack)
        assertEquals("unknown artist - unknown song", track.key)

        val whitespaceArtistTrack = TrackMetadata(title = "Unknown Song", artist = "   ")
        assertTrue(whitespaceArtistTrack.isValidTrack)
        assertEquals("unknown artist - unknown song", whitespaceArtistTrack.key)
    }

    @Test
    fun testCleanStringRemovalOfAudioMarkers() {
        val track = TrackMetadata(
            title = "Starboy (Official Music Video)",
            artist = "The Weeknd"
        )
        assertTrue(track.isValidTrack)
        assertEquals("the weeknd - starboy", track.key)

        val remasteredTrack = TrackMetadata(
            title = "In The End [Remastered 2020]",
            artist = "Linkin Park"
        )
        assertTrue(remasteredTrack.isValidTrack)
        assertEquals("linkin park - in the end", remasteredTrack.key)
    }

    @Test
    fun testTitleOnlyContainingMarkerDoesNotBecomeBlank() {
        // If a title only consists of a marker (pathological case), key shouldn't crash or be empty
        val track = TrackMetadata(
            title = "[Remastered]",
            artist = "Artist"
        )
        assertTrue(track.isValidTrack)
        assertEquals("artist - [remastered]", track.key)
    }

    @Test
    fun testCompletelyBlankTrackIsInvalid() {
        val track = TrackMetadata(
            title = "",
            artist = ""
        )
        assertFalse(track.isValidTrack)
    }
}
