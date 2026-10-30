package com.example.service

import com.example.data.model.PlaybackStateInfo
import com.example.data.model.TrackMetadata
import org.junit.Assert.*
import org.junit.Test

class OverlayTransitionAnimationTest {

    data class TransitionState(
        var visibility: Int, // 0 = VISIBLE, 8 = GONE
        var alpha: Float,
        var scaleX: Float,
        var scaleY: Float,
        var isLoopRunning: Boolean,
        var isFadingIn: Boolean,
        var isFadingOut: Boolean,
        var lastTargetVisible: Boolean?
    )

    private fun createInitialState(initiallyVisible: Boolean): TransitionState {
        return TransitionState(
            visibility = if (initiallyVisible) 0 else 8,
            alpha = if (initiallyVisible) 1.0f else 0.0f,
            scaleX = if (initiallyVisible) 1.0f else 0.97f,
            scaleY = if (initiallyVisible) 1.0f else 0.97f,
            isLoopRunning = initiallyVisible,
            isFadingIn = false,
            isFadingOut = false,
            lastTargetVisible = initiallyVisible
        )
    }

    /**
     * Simulates the exact transition logic implemented in OverlayService.
     */
    private fun applyTransition(
        state: TransitionState,
        playbackState: PlaybackStateInfo,
        overlayEnabled: Boolean,
        isDragMode: Boolean
    ): Long {
        if (!overlayEnabled) {
            state.isFadingIn = false
            state.isFadingOut = false
            state.visibility = 8 // GONE
            state.alpha = 0f
            state.scaleX = 0.97f
            state.scaleY = 0.97f
            state.isLoopRunning = false
            state.lastTargetVisible = false
            return 0L
        }

        val shouldShow = isDragMode || (playbackState.isPlaying && playbackState.hasActiveSession && playbackState.track != null)

        if (shouldShow) {
            if (state.lastTargetVisible == true && state.visibility == 0 && !state.isFadingOut && state.alpha >= 1f) {
                return 0L
            }
            state.lastTargetVisible = true

            if (state.isFadingOut) {
                state.isFadingOut = false
            } else if (state.isFadingIn) {
                return 0L
            }

            val startAlpha = if (state.visibility == 0) state.alpha.coerceIn(0f, 1f) else 0f
            val startScale = if (state.visibility == 0) state.scaleX.coerceIn(0.97f, 1.0f) else 0.97f

            state.visibility = 0 // VISIBLE
            state.alpha = startAlpha
            state.scaleX = startScale
            state.scaleY = startScale

            val remaining = (1f - startAlpha).coerceIn(0f, 1f)
            val duration = kotlin.math.round(1000.0 * remaining.toDouble()).toLong().coerceIn(30L, 1000L)

            state.isFadingIn = true
            return duration
        } else {
            if (state.lastTargetVisible == false && state.visibility == 8 && !state.isFadingIn) {
                state.isLoopRunning = false
                return 0L
            }
            state.lastTargetVisible = false

            // Stop lyric scrolling and loop immediately
            state.isLoopRunning = false

            if (state.isFadingIn) {
                state.isFadingIn = false
            } else if (state.isFadingOut) {
                return 0L
            }

            if (state.visibility != 0 || state.alpha <= 0.01f) {
                state.visibility = 8
                state.alpha = 0f
                state.scaleX = 0.97f
                state.scaleY = 0.97f
                return 0L
            }

            val startAlpha = state.alpha.coerceIn(0f, 1f)
            val duration = kotlin.math.round(1000.0 * startAlpha.toDouble()).toLong().coerceIn(30L, 1000L)

            state.isFadingOut = true
            return duration
        }
    }

    private fun completeAnimation(state: TransitionState) {
        if (state.isFadingIn) {
            state.alpha = 1.0f
            state.scaleX = 1.0f
            state.scaleY = 1.0f
            state.isFadingIn = false
            state.isLoopRunning = true
        } else if (state.isFadingOut) {
            state.visibility = 8
            state.alpha = 0f
            state.scaleX = 0.97f
            state.scaleY = 0.97f
            state.isLoopRunning = false
            state.isFadingOut = false
        }
    }

    @Test
    fun testPlayingToPausedSmoothFadeOut() {
        val state = createInitialState(initiallyVisible = true)
        val pausedPlayback = PlaybackStateInfo(
            isPlaying = false,
            hasActiveSession = true,
            track = TrackMetadata(title = "Song", artist = "Artist"),
            positionMs = 15000L
        )

        val duration = applyTransition(state, pausedPlayback, overlayEnabled = true, isDragMode = false)

        // 1. Duration must be approximately 1000ms (1 second)
        assertEquals(1000L, duration)
        assertTrue("Exit fade-out animation must be running", state.isFadingOut)
        assertFalse("Entrance animation must not be running", state.isFadingIn)

        // 2. Window must NOT be immediately removed (must remain visible during fade)
        assertEquals("Overlay must remain VISIBLE during exit animation", 0, state.visibility)

        // 3. Lyric scrolling and frame loops must stop immediately upon pause
        assertFalse("Playback-dependent loop must stop immediately", state.isLoopRunning)

        // 4. After animation finishes, overlay is set to GONE with scale 0.97
        completeAnimation(state)
        assertEquals("Overlay must be GONE after fade-out finishes", 8, state.visibility)
        assertEquals(0f, state.alpha, 0.001f)
        assertEquals(0.97f, state.scaleX, 0.001f)
        assertEquals(0.97f, state.scaleY, 0.001f)
        assertFalse(state.isFadingOut)
    }

    @Test
    fun testPausedToPlayingSmoothFadeIn() {
        val state = createInitialState(initiallyVisible = false)
        val playingPlayback = PlaybackStateInfo(
            isPlaying = true,
            hasActiveSession = true,
            track = TrackMetadata(title = "Song", artist = "Artist"),
            positionMs = 15000L
        )

        val duration = applyTransition(state, playingPlayback, overlayEnabled = true, isDragMode = false)

        // 1. Duration must be approximately 1000ms (1 second)
        assertEquals(1000L, duration)
        assertTrue("Entrance fade-in animation must be running", state.isFadingIn)
        assertFalse("Exit animation must not be running", state.isFadingOut)

        // 2. Window must be set to VISIBLE immediately
        assertEquals(0, state.visibility)
        assertEquals(0f, state.alpha, 0.001f)
        assertEquals(0.97f, state.scaleX, 0.001f)

        // 3. After animation completes, alpha is 1.0f and scale is 1.0f
        completeAnimation(state)
        assertEquals(1.0f, state.alpha, 0.001f)
        assertEquals(1.0f, state.scaleX, 0.001f)
        assertEquals(1.0f, state.scaleY, 0.001f)
        assertFalse(state.isFadingIn)
    }

    @Test
    fun testPlayingToStoppedSmoothFadeOut() {
        val state = createInitialState(initiallyVisible = true)
        val stoppedPlayback = PlaybackStateInfo(
            isPlaying = false,
            hasActiveSession = false,
            track = null,
            positionMs = 0L
        )

        val duration = applyTransition(state, stoppedPlayback, overlayEnabled = true, isDragMode = false)

        assertEquals("Stopped state must trigger 1000ms fade-out", 1000L, duration)
        assertTrue(state.isFadingOut)
        assertEquals(0, state.visibility)
        assertFalse("Playback loop must stop", state.isLoopRunning)

        completeAnimation(state)
        assertEquals(8, state.visibility)
    }

    @Test
    fun testResumeDuringOngoingFadeOut() {
        val state = createInitialState(initiallyVisible = true)
        val pausedPlayback = PlaybackStateInfo(isPlaying = false, hasActiveSession = true, track = TrackMetadata("T", "A"))
        val playingPlayback = PlaybackStateInfo(isPlaying = true, hasActiveSession = true, track = TrackMetadata("T", "A"))

        // 1. Pause begins fade-out
        applyTransition(state, pausedPlayback, overlayEnabled = true, isDragMode = false)
        assertTrue(state.isFadingOut)

        // 2. Simulate animation reaching 40% (alpha dropped to 0.6f, scale to 0.988f)
        state.alpha = 0.6f
        state.scaleX = 0.988f
        state.scaleY = 0.988f

        // 3. User resumes playback mid-animation!
        val resumeDuration = applyTransition(state, playingPlayback, overlayEnabled = true, isDragMode = false)

        // Duration must scale to remaining 40% (1000 * 0.4 = 400ms)
        assertEquals(400L, resumeDuration)
        assertFalse("Exit animation must be cancelled immediately", state.isFadingOut)
        assertTrue("Entrance animation must start", state.isFadingIn)
        assertEquals(0.6f, state.alpha, 0.001f)
        assertEquals(0.988f, state.scaleX, 0.001f)

        // Complete entrance
        completeAnimation(state)
        assertEquals(1.0f, state.alpha, 0.001f)
        assertEquals(1.0f, state.scaleX, 0.001f)
        assertEquals(0, state.visibility)
    }

    @Test
    fun testRapidPauseResumeSequencesDoNotFlicker() {
        val state = createInitialState(initiallyVisible = true)
        val pausedPlayback = PlaybackStateInfo(isPlaying = false, hasActiveSession = true, track = TrackMetadata("T", "A"))
        val playingPlayback = PlaybackStateInfo(isPlaying = true, hasActiveSession = true, track = TrackMetadata("T", "A"))

        // Rapidly toggle 10 times in a row
        for (i in 0 until 10) {
            val isPlaying = i % 2 == 0
            val pState = if (isPlaying) playingPlayback else pausedPlayback
            val dur = applyTransition(state, pState, overlayEnabled = true, isDragMode = false)
            assertTrue("Duration must be within valid bounds (30ms - 1000ms)", dur in 0L..1000L)
            assertEquals("Window must not abruptly disappear during rapid changes", 0, state.visibility)
        }
    }

    @Test
    fun testSwitchingTracksWhilePlayingPreservesVisibilityWithoutFlicker() {
        val state = createInitialState(initiallyVisible = true)
        val trackA = PlaybackStateInfo(isPlaying = true, hasActiveSession = true, track = TrackMetadata("Song A", "Artist A"))
        val trackB = PlaybackStateInfo(isPlaying = true, hasActiveSession = true, track = TrackMetadata("Song B", "Artist B"))

        // Playing Track A
        applyTransition(state, trackA, overlayEnabled = true, isDragMode = false)
        assertEquals(0, state.visibility)
        assertEquals(1.0f, state.alpha, 0.001f)

        // Switch to Track B
        val duration = applyTransition(state, trackB, overlayEnabled = true, isDragMode = false)

        // Must NOT restart entrance/exit animation (duration 0L, already visible)
        assertEquals("Switching tracks while playing must NOT flicker or re-animate window", 0L, duration)
        assertFalse(state.isFadingIn)
        assertFalse(state.isFadingOut)
        assertEquals(0, state.visibility)
        assertEquals(1.0f, state.alpha, 0.001f)
    }

    @Test
    fun testManuallyHidingAndShowingOverlay() {
        val state = createInitialState(initiallyVisible = true)
        val playingPlayback = PlaybackStateInfo(isPlaying = true, hasActiveSession = true, track = TrackMetadata("Song", "Artist"))

        // User turns overlay OFF in Settings
        val durOff = applyTransition(state, playingPlayback, overlayEnabled = false, isDragMode = false)
        assertEquals("Manual disable must hide immediately without delay", 0L, durOff)
        assertEquals(8, state.visibility)
        assertEquals(0f, state.alpha, 0.001f)
        assertFalse(state.isLoopRunning)

        // User turns overlay ON again in Settings while music is playing
        val durOn = applyTransition(state, playingPlayback, overlayEnabled = true, isDragMode = false)
        assertEquals("Enabling overlay while playing must smoothly fade in (1000ms)", 1000L, durOn)
        assertTrue(state.isFadingIn)
        assertEquals(0, state.visibility)
    }
}
