package com.example.ui.components

import android.content.Intent
import com.example.service.HudDisplayGeometry
import com.example.service.HudOrientation
import com.example.service.HudOverlayProfile
import com.example.service.HudPositioningEngine
import com.example.service.OverlayService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudTripleTapTest {

    private fun createDetector(tapSlopPx: Float = 24f): HudTripleTapDetector {
        return HudTripleTapDetector(tapTouchSlopPx = tapSlopPx)
    }

    private fun performValidTap(
        detector: HudTripleTapDetector,
        downTimeMs: Long,
        upTimeMs: Long,
        rawX: Float = 200f,
        rawY: Float = 300f,
        pointerId: Int = 0
    ): Boolean {
        assertTrue(detector.onDown(rawX = rawX, rawY = rawY, pointerId = pointerId, timestampMs = downTimeMs))
        return detector.onUp(pointerId = pointerId, timestampMs = upTimeMs)
    }

    @Test
    fun `one tap and two taps never trigger launch, third valid tap triggers once`() {
        val detector = createDetector()
        var launchCount = 0

        // Tap 1
        if (performValidTap(detector, downTimeMs = 100L, upTimeMs = 150L, rawX = 120f, rawY = 260f)) {
            launchCount++
        }
        assertEquals(0, launchCount)
        assertEquals(1, detector.tapCount)

        // Tap 2 at another spot on the HUD surface
        if (performValidTap(detector, downTimeMs = 250L, upTimeMs = 300L, rawX = 520f, rawY = 280f)) {
            launchCount++
        }
        assertEquals(0, launchCount)
        assertEquals(2, detector.tapCount)

        // Tap 3 at a third spot on the HUD surface -> triggers launch once
        if (performValidTap(detector, downTimeMs = 420L, upTimeMs = 470L, rawX = 760f, rawY = 270f)) {
            launchCount++
        }
        assertEquals(1, launchCount)
        assertEquals(0, detector.tapCount)
    }

    @Test
    fun `four or more rapid taps do not create duplicate launches`() {
        val detector = createDetector()
        var launchCount = 0

        val tapTimestamps = listOf(
            100L to 140L,
            220L to 260L,
            340L to 380L, // triggers 1st launch
            460L to 500L, // within cooldown
            580L to 620L, // within cooldown
            700L to 740L  // within cooldown
        )
        for ((idx, pair) in tapTimestamps.withIndex()) {
            detector.onDown(rawX = 200f + idx * 10f, rawY = 300f, pointerId = 0, timestampMs = pair.first)
            if (detector.onUp(pointerId = 0, timestampMs = pair.second)) {
                launchCount++
            }
        }
        assertEquals("Rapid 4-6 taps must launch MainActivity only once", 1, launchCount)

        // After cooldown elapses, a fresh triple-tap sequence works normally
        var secondSequenceLaunches = 0
        if (performValidTap(detector, downTimeMs = 1300L, upTimeMs = 1340L)) secondSequenceLaunches++
        if (performValidTap(detector, downTimeMs = 1440L, upTimeMs = 1480L)) secondSequenceLaunches++
        if (performValidTap(detector, downTimeMs = 1580L, upTimeMs = 1620L)) secondSequenceLaunches++
        assertEquals(1, secondSequenceLaunches)
    }

    @Test
    fun `slow taps outside recognition interval reset sequence`() {
        val detector = createDetector()

        assertFalse(performValidTap(detector, downTimeMs = 100L, upTimeMs = 150L))
        assertFalse(performValidTap(detector, downTimeMs = 260L, upTimeMs = 310L))
        assertEquals(2, detector.tapCount)

        // Pause exceeds maxTapIntervalMs (450ms): 310 -> 780 is 470ms
        assertFalse(performValidTap(detector, downTimeMs = 780L, upTimeMs = 830L))
        assertEquals("Expired sequence must reset so slow 3rd tap becomes tap 1", 1, detector.tapCount)
    }

    @Test
    fun `long press does not count as tap and resets prior taps`() {
        val detector = createDetector()

        assertFalse(performValidTap(detector, downTimeMs = 100L, upTimeMs = 150L))
        assertFalse(performValidTap(detector, downTimeMs = 260L, upTimeMs = 310L))
        assertEquals(2, detector.tapCount)

        // 3rd touch held > maxTapDurationMs (e.g., 550ms)
        assertTrue(detector.onDown(rawX = 200f, rawY = 300f, pointerId = 0, timestampMs = 400L))
        assertFalse(detector.onUp(pointerId = 0, timestampMs = 950L))
        assertEquals(0, detector.tapCount)

        // Hold-to-reposition activation also cancels any partial tap sequence
        assertFalse(performValidTap(detector, downTimeMs = 1100L, upTimeMs = 1150L))
        detector.onDragOrHoldActivated()
        assertEquals(0, detector.tapCount)
    }

    @Test
    fun `dragging past touch slop or between taps cancels triple-tap sequence`() {
        val detector = createDetector(tapSlopPx = 24f)

        // Tap 1 succeeds
        assertFalse(performValidTap(detector, downTimeMs = 100L, upTimeMs = 150L, rawX = 200f, rawY = 300f))
        assertEquals(1, detector.tapCount)

        // Touch 2 moves 30px (> 24px tap slop) -> drag invalidates sequence
        assertTrue(detector.onDown(rawX = 200f, rawY = 300f, pointerId = 0, timestampMs = 240L))
        assertTrue(detector.onMove(rawX = 230f, rawY = 300f, pointerId = 0))
        assertFalse(detector.onUp(pointerId = 0, timestampMs = 290L))
        assertEquals(0, detector.tapCount)

        // Subsequent two taps must NOT launch because previous drag cleared tap 1
        assertFalse(performValidTap(detector, downTimeMs = 380L, upTimeMs = 420L))
        assertFalse(performValidTap(detector, downTimeMs = 500L, upTimeMs = 540L))
        assertEquals(2, detector.tapCount)
    }

    @Test
    fun `multi-pointer touch and lifecycle cancel reset triple-tap sequence`() {
        val detector = createDetector()

        assertFalse(performValidTap(detector, downTimeMs = 100L, upTimeMs = 140L))
        assertFalse(performValidTap(detector, downTimeMs = 220L, upTimeMs = 260L))
        assertEquals(2, detector.tapCount)

        // Multi-touch cancels sequence
        assertTrue(detector.onDown(rawX = 200f, rawY = 300f, pointerId = 0, timestampMs = 320L))
        detector.onMultiPointerDown()
        assertFalse(detector.onUp(pointerId = 0, timestampMs = 360L))
        assertEquals(0, detector.tapCount)

        // Lifecycle cancel (visibility/rotation/detach) resets incomplete sequence
        assertFalse(performValidTap(detector, downTimeMs = 500L, upTimeMs = 540L))
        assertEquals(1, detector.tapCount)
        detector.onCancel()
        assertEquals(0, detector.tapCount)
    }

    @Test
    fun `click-through disables triple-tap and disabling click-through restores it`() {
        val detector = createDetector()

        // Partial taps before click-through enabled
        assertFalse(performValidTap(detector, downTimeMs = 100L, upTimeMs = 140L))
        assertEquals(1, detector.tapCount)

        // Click-through ON -> clears partial state and ignores touches
        detector.setEnabled(false)
        assertEquals(0, detector.tapCount)
        assertFalse(detector.onDown(rawX = 200f, rawY = 300f, pointerId = 0, timestampMs = 200L))
        assertFalse(detector.onUp(pointerId = 0, timestampMs = 240L))
        assertFalse(detector.onDown(rawX = 200f, rawY = 300f, pointerId = 0, timestampMs = 300L))
        assertFalse(detector.onUp(pointerId = 0, timestampMs = 340L))
        assertFalse(detector.onDown(rawX = 200f, rawY = 300f, pointerId = 0, timestampMs = 400L))
        assertFalse(detector.onUp(pointerId = 0, timestampMs = 440L))

        // Click-through OFF -> triple-tap immediately works again
        detector.setEnabled(true)
        assertFalse(performValidTap(detector, downTimeMs = 600L, upTimeMs = 640L))
        assertFalse(performValidTap(detector, downTimeMs = 720L, upTimeMs = 760L))
        assertTrue(performValidTap(detector, downTimeMs = 840L, upTimeMs = 880L))
    }

    @Test
    fun `triple-tap never breaks existing tap resync or 1s hold-to-reposition`() {
        val holdController = HudHoldGestureController(touchSlopPx = 60f)
        val tripleTapDetector = createDetector(tapSlopPx = 24f)
        val initialBounds = HudWindowBounds(100, 260, 760, 140)
        var currentBounds = initialBounds
        var resyncTapCount = 0
        var commitCount = 0
        var launchCount = 0

        val geometry = HudDisplayGeometry(1080, 2400, 2.75f, navBottomPx = 132, statusTopPx = 84)
        val coordinator = HudDragTransactionCoordinator(
            holdController = holdController,
            readBounds = { currentBounds },
            applyDragBounds = { target ->
                val resolved = HudPositioningEngine.resolveBounds(
                    profile = HudOverlayProfile(target.x, target.y, target.width, target.height),
                    geometry = geometry
                )
                currentBounds = HudWindowBounds(resolved.x, resolved.y, resolved.widthPx, resolved.effectiveHeightPx)
            },
            restoreBounds = { currentBounds = it },
            commitBounds = { _, final ->
                currentBounds = final
                commitCount++
            },
            onTapAction = { resyncTapCount++ }
        )

        // Single tap triggers existing resync without launching app
        assertTrue(coordinator.onNormalDown(200f, 300f, 0))
        tripleTapDetector.onDown(200f, 300f, 0, 100L)
        assertEquals(HudHoldGestureController.UpResult.TRIGGER_TAP, coordinator.onNormalUp())
        assertFalse(tripleTapDetector.onUp(0, 140L))
        assertEquals(1, resyncTapCount)
        assertEquals(0, launchCount)

        // 1s hold-to-reposition works normally and clears triple-tap sequence
        assertTrue(coordinator.onNormalDown(200f, 300f, 0))
        tripleTapDetector.onDown(200f, 300f, 0, 250L)
        assertTrue(coordinator.onHoldTimeout(HudOrientation.PORTRAIT))
        tripleTapDetector.onDragOrHoldActivated()
        assertTrue(coordinator.onNormalDragMove(320f, 450f) { init, dx, dy ->
            HudWindowBounds(init.x + dx, init.y + dy, init.width, init.height)
        })
        assertEquals(HudHoldGestureController.UpResult.COMMIT_REPOSITION, coordinator.onNormalUp())
        assertEquals(1, commitCount)
        assertEquals(HudWindowBounds(220, 410, 760, 140), currentBounds)
        assertEquals(0, tripleTapDetector.tapCount)

        // Interactive control taps never enter hold or triple-tap
        assertFalse(coordinator.onNormalDown(200f, 300f, 0, isOnInteractiveControl = true))
        assertFalse(tripleTapDetector.onDown(200f, 300f, 0, 1500L, isOnInteractiveControl = true))

        // Clean triple-tap after reposition launches MainActivity once
        for ((down, up) in listOf(1600L to 1640L, 1720L to 1760L, 1840L to 1880L)) {
            assertTrue(coordinator.onNormalDown(250f, 320f, 0))
            tripleTapDetector.onDown(250f, 320f, 0, down)
            if (coordinator.onNormalUp() == HudHoldGestureController.UpResult.TRIGGER_TAP &&
                tripleTapDetector.onUp(0, up)
            ) {
                launchCount++
            }
        }
        assertEquals(1, launchCount)
    }

    @Test
    fun `main activity launch flags prevent duplicate instances and preserve state`() {
        val flags = OverlayService.MAIN_ACTIVITY_LAUNCH_FLAGS
        assertNotEquals(0, flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertNotEquals(0, flags and Intent.FLAG_ACTIVITY_SINGLE_TOP)
        assertNotEquals(0, flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
        assertEquals(0, flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
        assertEquals(0, flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }
}
