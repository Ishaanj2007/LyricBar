package com.example.data

import com.example.data.model.TrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TrackArtworkTest {

    @Test
    fun testTrackMetadataArtworkFieldsDefaultNull() {
        val track = TrackMetadata(
            title = "Test Title",
            artist = "Test Artist"
        )
        assertNull(track.albumArtUri)
        assertNull(track.albumArtBitmap)
    }

    @Test
    fun testTrackMetadataWithAlbumArtUri() {
        val uri = "content://media/external/audio/albumart/123"
        val track = TrackMetadata(
            title = "Song with URI",
            artist = "Artist",
            albumArtUri = uri
        )
        assertEquals(uri, track.albumArtUri)
        assertNull(track.albumArtBitmap)
    }

    @Test
    fun testTrackMetadataWithHttpUri() {
        val webUri = "https://example.com/artwork/cover.jpg"
        val track = TrackMetadata(
            title = "Web Song",
            artist = "Web Artist",
            albumArtUri = webUri
        )
        assertEquals(webUri, track.albumArtUri)
    }
}
