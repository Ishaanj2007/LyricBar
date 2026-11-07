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

    @Test
    fun testArtworkCacheKeyGeneration() {
        val key = com.example.ui.components.buildArtworkCacheKey("artist - title", "content://art/1", "full_512")
        assertEquals("artist - title::content://art/1::full_512", key)
    }

    @Test
    fun testArtworkCacheKeyDifferentiatesUrisAndVariants() {
        val keyFull = com.example.ui.components.buildArtworkCacheKey("artist - title", "content://art/1", "full_512")
        val keyThumb = com.example.ui.components.buildArtworkCacheKey("artist - title", "content://art/1", "thumb_144")
        val keyDifferentUri = com.example.ui.components.buildArtworkCacheKey("artist - title", "content://art/2", "full_512")

        org.junit.Assert.assertNotEquals(keyFull, keyThumb)
        org.junit.Assert.assertNotEquals(keyFull, keyDifferentUri)
    }

    @Test
    fun testArtworkGlowCacheKeyGeneration() {
        val key = com.example.ui.components.buildArtworkCacheKey("artist - title", "content://art/1", "glow_240_12345")
        assertEquals("artist - title::content://art/1::glow_240_12345", key)
    }

    @Test
    fun testExtractArtworkGlowPaletteNullFallback() {
        val fallback = androidx.compose.ui.graphics.Color(0xFFE84393)
        val palette = com.example.ui.components.extractArtworkGlowPalette(null, fallback)
        assertEquals(fallback, palette.primary)
        assertEquals(fallback, palette.secondary)
        assertEquals(fallback, palette.topColor)
        assertEquals(fallback, palette.bottomColor)
    }

    @Test
    fun testArtworkGlowMask320CacheKeyGeneration() {
        val key = com.example.ui.components.buildArtworkCacheKey("artist - title", "content://art/1", "glow_mask_320_12345_6789")
        assertEquals("artist - title::content://art/1::glow_mask_320_12345_6789", key)
    }
}

