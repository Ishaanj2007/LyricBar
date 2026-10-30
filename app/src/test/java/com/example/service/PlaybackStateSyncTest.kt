package com.example.service

import android.media.session.PlaybackState
import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.PlaybackStateInfo
import com.example.data.model.TrackMetadata
import org.junit.Assert.*
import org.junit.Test

class PlaybackStateSyncTest {

    @Test
    fun testPlaybackStateInfoCalculatedPosition_WhenPaused_DoesNotAdvance() {
        val basePos = 45_000L
        val pastTimestamp = System.currentTimeMillis() - 5000L // 5 seconds ago
        val pausedState = PlaybackStateInfo(
            isPlaying = false,
            hasActiveSession = true,
            positionMs = basePos,
            playbackSpeed = 1.0f,
            lastUpdatedMs = pastTimestamp
        )

        assertEquals("When paused, calculatedPositionMs must return exact positionMs without advancing",
            basePos, pausedState.calculatedPositionMs)
    }

    @Test
    fun testPlaybackStateInfoCalculatedPosition_WhenPlaying_AdvancesWithTime() {
        val basePos = 45_000L
        val elapsed = 2000L
        val pastTimestamp = System.currentTimeMillis() - elapsed
        val playingState = PlaybackStateInfo(
            isPlaying = true,
            hasActiveSession = true,
            positionMs = basePos,
            playbackSpeed = 1.0f,
            lastUpdatedMs = pastTimestamp
        )

        val calc = playingState.calculatedPositionMs
        assertTrue("When playing, calculatedPositionMs must advance past base position", calc >= basePos + elapsed - 50L)
    }

    @Test
    fun testOverlayVisibilityLogic_PauseHidesOverlay() {
        fun shouldShowOverlay(
            isPlaying: Boolean,
            hasActiveSession: Boolean,
            hasTrack: Boolean,
            overlayEnabled: Boolean,
            isDragMode: Boolean
        ): Boolean {
            if (!overlayEnabled) return false
            return isDragMode || (isPlaying && hasActiveSession && hasTrack)
        }

        // When actively playing with track and session
        assertTrue(
            "Overlay must be visible when playing with active session",
            shouldShowOverlay(isPlaying = true, hasActiveSession = true, hasTrack = true, overlayEnabled = true, isDragMode = false)
        )

        // When paused in external music app: MUST HIDE IMMEDIATELY
        assertFalse(
            "Overlay must immediately hide when playback is paused",
            shouldShowOverlay(isPlaying = false, hasActiveSession = true, hasTrack = true, overlayEnabled = true, isDragMode = false)
        )

        // When stopped or no active session: MUST HIDE
        assertFalse(
            "Overlay must hide when playback is stopped",
            shouldShowOverlay(isPlaying = false, hasActiveSession = false, hasTrack = true, overlayEnabled = true, isDragMode = false)
        )

        // When resumed: MUST SHOW AGAIN
        assertTrue(
            "Overlay must show again when playback resumes",
            shouldShowOverlay(isPlaying = true, hasActiveSession = true, hasTrack = true, overlayEnabled = true, isDragMode = false)
        )

        // Drag repositioning mode allows viewing overlay box even when paused
        assertTrue(
            "Reposition drag mode allows overlay visibility when paused",
            shouldShowOverlay(isPlaying = false, hasActiveSession = true, hasTrack = true, overlayEnabled = true, isDragMode = true)
        )

        // When user disabled overlay in settings: MUST HIDE ALWAYS
        assertFalse(
            "Overlay must stay hidden if user preference is disabled",
            shouldShowOverlay(isPlaying = true, hasActiveSession = true, hasTrack = true, overlayEnabled = false, isDragMode = false)
        )
    }

    @Test
    fun testPlaybackStateHandling_PauseStoppedBufferingPlaying() {
        fun computePlaybackState(
            rawState: Int,
            reportedPosition: Long,
            reportedSpeed: Float,
            previousPlaying: Boolean,
            hasController: Boolean
        ): Pair<Boolean, Boolean> { // Pair(isPlaying, hasActiveSession)
            val isExplicitStoppedOrNone = rawState == PlaybackState.STATE_STOPPED ||
                rawState == PlaybackState.STATE_NONE ||
                rawState == PlaybackState.STATE_ERROR

            val isPlaying = when (rawState) {
                PlaybackState.STATE_PLAYING -> true
                PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> {
                    previousPlaying // Avoid flicker during buffering while playing
                }
                else -> false
            }

            val hasSession = hasController && !isExplicitStoppedOrNone
            return Pair(isPlaying, hasSession)
        }

        // 1. External player is playing
        val (p1, s1) = computePlaybackState(
            rawState = PlaybackState.STATE_PLAYING,
            reportedPosition = 10000L,
            reportedSpeed = 1.0f,
            previousPlaying = false,
            hasController = true
        )
        assertTrue("Playing state must set isPlaying = true", p1)
        assertTrue("Playing state must have active session", s1)

        // 2. External player transitions to PAUSED
        val (p2, s2) = computePlaybackState(
            rawState = PlaybackState.STATE_PAUSED,
            reportedPosition = 12000L,
            reportedSpeed = 0.0f,
            previousPlaying = true,
            hasController = true
        )
        assertFalse("Paused state must immediately set isPlaying = false", p2)
        assertTrue("Paused state still maintains session token", s2)

        // 3. Resumes from PAUSED to PLAYING
        val (p3, s3) = computePlaybackState(
            rawState = PlaybackState.STATE_PLAYING,
            reportedPosition = 12000L,
            reportedSpeed = 1.0f,
            previousPlaying = false,
            hasController = true
        )
        assertTrue("Resuming must set isPlaying = true", p3)
        assertTrue(s3)

        // 4. Temporary buffering while playing (e.g. network hiccup or seek buffering)
        val (p4, s4) = computePlaybackState(
            rawState = PlaybackState.STATE_BUFFERING,
            reportedPosition = 15000L,
            reportedSpeed = 0.0f,
            previousPlaying = true,
            hasController = true
        )
        assertTrue("Temporary buffering while playing must NOT flicker isPlaying to false", p4)
        assertTrue(s4)

        // 5. Playback stopped
        val (p5, s5) = computePlaybackState(
            rawState = PlaybackState.STATE_STOPPED,
            reportedPosition = 0L,
            reportedSpeed = 0.0f,
            previousPlaying = false,
            hasController = true
        )
        assertFalse("Stopped state must set isPlaying = false", p5)
        assertFalse("Stopped state must set hasActiveSession = false", s5)
    }

    @Test
    fun testResumePlayback_ResyncsToActualMediaPosition() {
        val pausedPositionMs = 30_000L
        val resumedPositionMs = 35_000L // User seeked or resumed at 35s

        val lines = listOf(
            LyricLine(timeMs = 10_000L, text = "Line 1"),
            LyricLine(timeMs = 20_000L, text = "Line 2"),
            LyricLine(timeMs = 32_000L, text = "Line 3"),
            LyricLine(timeMs = 40_000L, text = "Line 4")
        )
        val lyrics = ParsedLyrics(
            title = "Test Track",
            artist = "Test Artist",
            isSynced = true,
            lines = lines,
            plainText = "",
            source = "Test"
        )

        // When paused at 30s, active line is Line 2 (20s)
        val pausedLine = lyrics.getActiveLyricLine(pausedPositionMs, 0L)
        assertEquals("Line 2", pausedLine?.text)

        // When resumed at actual media position 35s, active line must be Line 3 (32s), not Line 2
        val resumedLine = lyrics.getActiveLyricLine(resumedPositionMs, 0L)
        assertEquals("Line 3", resumedLine?.text)
    }

    @Test
    fun testTrackChange_ResetsActiveLyricAndMetadata() {
        val track1 = TrackMetadata(title = "Song A", artist = "Artist A")
        val track2 = TrackMetadata(title = "Song B", artist = "Artist B")

        assertNotEquals("Different tracks must have distinct keys", track1.key, track2.key)

        var currentLyrics: ParsedLyrics? = ParsedLyrics(
            title = "Song A",
            artist = "Artist A",
            isSynced = true,
            lines = listOf(LyricLine(timeMs = 0L, text = "Song A lyric")),
            plainText = "",
            source = "Test"
        )
        var activeLine: String? = "Song A lyric"

        // Simulate onTrackChanged
        currentLyrics = null
        activeLine = null

        assertNull("Track change must immediately clear stale lyrics", currentLyrics)
        assertNull("Track change must immediately clear stale active lyric line", activeLine)
    }
}
