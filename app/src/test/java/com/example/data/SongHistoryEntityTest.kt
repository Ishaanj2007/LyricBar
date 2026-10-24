package com.example.data

import com.example.data.local.entity.SongHistoryEntity
import com.example.data.model.TrackMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongHistoryEntityTest {

    @Test
    fun testSongHistoryEntityCreation() {
        val entry1 = SongHistoryEntity(
            id = 1L,
            key = "Bohemian Rhapsody - Queen",
            title = "Bohemian Rhapsody",
            artist = "Queen",
            playedAt = 1000L
        )
        val entry2 = SongHistoryEntity(
            id = 2L,
            key = "Bohemian Rhapsody - Queen",
            title = "Bohemian Rhapsody",
            artist = "Queen",
            playedAt = 2000L
        )

        // Multiple entries for the same song must be distinct by id
        assertNotEquals(entry1.id, entry2.id)
        assertEquals(entry1.key, entry2.key)
        assertEquals("Bohemian Rhapsody", entry1.title)
        assertTrue(entry2.playedAt > entry1.playedAt)
    }

    @Test
    fun testSessionDeduplicationLogic() {
        // Simulating MediaPlaybackManager's recordSessionIfPlaying behavior
        var lastRecordedTrackKey: String? = null
        val recordedHistory = mutableListOf<String>()

        fun recordSessionIfPlaying(track: TrackMetadata?, isPlaying: Boolean) {
            if (track == null || track.key.isBlank()) return
            if (!isPlaying) return
            if (lastRecordedTrackKey == track.key) return

            lastRecordedTrackKey = track.key
            recordedHistory.add(track.key)
        }

        val trackA = TrackMetadata(title = "Song A", artist = "Artist A")
        val trackB = TrackMetadata(title = "Song B", artist = "Artist B")

        // 1. Song A begins playing -> recorded
        recordSessionIfPlaying(trackA, isPlaying = true)
        assertEquals(listOf(trackA.key), recordedHistory)

        // 2. Repeated position/metadata callbacks for Song A -> ignored, no duplicate
        recordSessionIfPlaying(trackA, isPlaying = true)
        recordSessionIfPlaying(trackA, isPlaying = true)
        assertEquals(listOf(trackA.key), recordedHistory)

        // 3. User pauses and resumes Song A -> ignored, no duplicate
        recordSessionIfPlaying(trackA, isPlaying = false)
        recordSessionIfPlaying(trackA, isPlaying = true)
        assertEquals(listOf(trackA.key), recordedHistory)

        // 4. Song B begins playing -> recorded
        recordSessionIfPlaying(trackB, isPlaying = true)
        assertEquals(listOf(trackA.key, trackB.key), recordedHistory)

        // 5. Repeated callbacks for Song B -> ignored
        recordSessionIfPlaying(trackB, isPlaying = true)
        assertEquals(listOf(trackA.key, trackB.key), recordedHistory)

        // 6. Song A plays again -> recorded as a separate entry
        recordSessionIfPlaying(trackA, isPlaying = true)
        assertEquals(listOf(trackA.key, trackB.key, trackA.key), recordedHistory)

        // 7. Session stopped / detached -> lastRecordedTrackKey reset
        lastRecordedTrackKey = null

        // 8. Song A played in a new session -> recorded as a new entry
        recordSessionIfPlaying(trackA, isPlaying = true)
        assertEquals(
            listOf(trackA.key, trackB.key, trackA.key, trackA.key),
            recordedHistory
        )
    }
}
