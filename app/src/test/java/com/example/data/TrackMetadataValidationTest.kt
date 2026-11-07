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
        assertEquals("coldplay - viva la vida", track.lookupKey)
        assertEquals("coldplay - viva la vida [album: viva la vida or death and all his friends]", track.identityKey)
        assertEquals(track.identityKey, track.key)
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
        assertEquals("unknown artist - unknown song", track.lookupKey)
        assertEquals("unknown artist - unknown song", track.identityKey)

        val whitespaceArtistTrack = TrackMetadata(title = "Unknown Song", artist = "   ")
        assertTrue(whitespaceArtistTrack.isValidTrack)
        assertEquals("unknown artist - unknown song", whitespaceArtistTrack.lookupKey)
        assertEquals("unknown artist - unknown song", whitespaceArtistTrack.identityKey)
    }

    @Test
    fun testCleanStringRemovalOfAudioMarkers() {
        val track = TrackMetadata(
            title = "Starboy (Official Music Video)",
            artist = "The Weeknd"
        )
        assertTrue(track.isValidTrack)
        assertEquals("the weeknd - starboy", track.lookupKey)
        assertEquals("the weeknd - starboy", track.identityKey)

        val remasteredTrack = TrackMetadata(
            title = "In The End [Remastered 2020]",
            artist = "Linkin Park"
        )
        assertTrue(remasteredTrack.isValidTrack)
        assertEquals("linkin park - in the end", remasteredTrack.lookupKey)
        assertEquals("linkin park - in the end [remastered 2020]", remasteredTrack.identityKey)
    }

    @Test
    fun testVersionCollisionPrevention() {
        val studio = TrackMetadata(title = "Hotel California", artist = "Eagles")
        val remastered = TrackMetadata(title = "Hotel California (Remastered)", artist = "Eagles")
        val live = TrackMetadata(title = "Hotel California [Live]", artist = "Eagles")
        val radioEdit = TrackMetadata(title = "Hotel California (Radio Edit)", artist = "Eagles")
        val acoustic = TrackMetadata(title = "Hotel California (Acoustic)", artist = "Eagles")

        // All share the same lookupKey for broad online lyrics search
        assertEquals("eagles - hotel california", studio.lookupKey)
        assertEquals("eagles - hotel california", remastered.lookupKey)
        assertEquals("eagles - hotel california", live.lookupKey)
        assertEquals("eagles - hotel california", radioEdit.lookupKey)
        assertEquals("eagles - hotel california", acoustic.lookupKey)

        // But have distinct identityKeys for local offsets, imported LRCs, and cache
        org.junit.Assert.assertNotEquals(studio.identityKey, remastered.identityKey)
        org.junit.Assert.assertNotEquals(studio.identityKey, live.identityKey)
        org.junit.Assert.assertNotEquals(studio.identityKey, radioEdit.identityKey)
        org.junit.Assert.assertNotEquals(studio.identityKey, acoustic.identityKey)
        org.junit.Assert.assertNotEquals(live.identityKey, acoustic.identityKey)
    }

    @Test
    fun testSameArtistAndTitleOnDifferentAlbums() {
        val studioTrack = TrackMetadata(title = "Comfortably Numb", artist = "Pink Floyd", album = "The Wall")
        val liveTrack = TrackMetadata(title = "Comfortably Numb", artist = "Pink Floyd", album = "Pulse")

        assertEquals("pink floyd - comfortably numb", studioTrack.lookupKey)
        assertEquals("pink floyd - comfortably numb", liveTrack.lookupKey)

        // Differentiated by album in identityKey
        assertEquals("pink floyd - comfortably numb [album: the wall]", studioTrack.identityKey)
        assertEquals("pink floyd - comfortably numb [album: pulse]", liveTrack.identityKey)
        org.junit.Assert.assertNotEquals(studioTrack.identityKey, liveTrack.identityKey)
    }

    @Test
    fun testSameTitleDifferentArtists() {
        val trackA = TrackMetadata(title = "Hello", artist = "Adele")
        val trackB = TrackMetadata(title = "Hello", artist = "Lionel Richie")

        org.junit.Assert.assertNotEquals(trackA.lookupKey, trackB.lookupKey)
        org.junit.Assert.assertNotEquals(trackA.identityKey, trackB.identityKey)
    }

    @Test
    fun testTitleOnlyContainingMarkerDoesNotBecomeBlank() {
        // If a title only consists of a marker (pathological case), key shouldn't crash or be empty
        val track = TrackMetadata(
            title = "[Remastered]",
            artist = "Artist"
        )
        assertTrue(track.isValidTrack)
        assertEquals("artist - [remastered]", track.identityKey)
        assertEquals("artist - [remastered]", track.lookupKey)
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
