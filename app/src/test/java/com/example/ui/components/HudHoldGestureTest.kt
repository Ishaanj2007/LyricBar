package com.example.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HudHoldGestureTest {

    @Test
    fun `hold delay defaults to one second`() {
        val controller = HudHoldGestureController(touchSlopPx = 24f)
        assertEquals(1000L, controller.holdDurationMs)
        assertEquals(1000L, HudHoldGestureController.HOLD_REPOSITION_DELAY_MS)
        assertEquals(HudHoldGestureController.State.IDLE, controller.state)
    }

    @Test
    fun `holding 1s enters active reposition and releasing commits position`() {
        val controller = HudHoldGestureController(touchSlopPx = 24f)

        assertTrue(controller.onDown(rawX = 300f, rawY = 400f, pointerId = 0))
        assertEquals(HudHoldGestureController.State.PENDING_HOLD, controller.state)

        // Sub-slop finger tremor keeps pending hold active and updates anchor coordinates
        assertFalse(controller.onMove(rawX = 310f, rawY = 408f))
        assertEquals(HudHoldGestureController.State.PENDING_HOLD, controller.state)
        assertEquals(310f, controller.activationRawX, 0.001f)
        assertEquals(408f, controller.activationRawY, 0.001f)

        // Note: HudHoldGestureController.onHoldTimeout() unconditionally transitions PENDING_HOLD to ACTIVE_REPOSITION
        // (the time delta check is performed by Handler before calling onHoldTimeout).
        // Therefore, we test that calling onHoldTimeout() at the 1000ms threshold successfully activates reposition:
        assertTrue("At 1000ms threshold timeout activates active reposition", controller.onHoldTimeout())
        assertEquals(HudHoldGestureController.State.ACTIVE_REPOSITION, controller.state)

        // Further drag movement does not cancel an active reposition
        assertFalse(controller.onMove(rawX = 520f, rawY = 640f))
        assertEquals(HudHoldGestureController.State.ACTIVE_REPOSITION, controller.state)

        // Lifting pointer commits the reposition and returns controller cleanly to IDLE
        assertEquals(HudHoldGestureController.UpResult.COMMIT_REPOSITION, controller.onUp())
        assertEquals(HudHoldGestureController.State.IDLE, controller.state)
        assertEquals(HudHoldGestureController.INVALID_POINTER_ID, controller.activePointerId)
    }

    @Test
    fun `normal short tap triggers tap action and never activates reposition`() {
        val controller = HudHoldGestureController(touchSlopPx = 24f)

        assertTrue(controller.onDown(rawX = 120f, rawY = 200f, pointerId = 1))
        assertFalse(controller.onMove(rawX = 126f, rawY = 204f))

        assertEquals(HudHoldGestureController.UpResult.TRIGGER_TAP, controller.onUp())
        assertEquals(HudHoldGestureController.State.IDLE, controller.state)
        assertFalse("Timeout after release must never activate reposition", controller.onHoldTimeout())
    }

    @Test
    fun `moving beyond touch slop before 1s cancels hold activation and suppresses tap`() {
        val controller = HudHoldGestureController(touchSlopPx = 24f)

        assertTrue(controller.onDown(rawX = 200f, rawY = 200f, pointerId = 0))
        // Diagonal move > 24px
        assertTrue(controller.onMove(rawX = 230f, rawY = 210f))
        assertEquals(HudHoldGestureController.State.CANCELLED_BEFORE_HOLD, controller.state)

        assertFalse("Cancelled hold must not trigger on timeout", controller.onHoldTimeout())
        assertEquals(HudHoldGestureController.UpResult.NONE, controller.onUp())
        assertEquals(HudHoldGestureController.State.IDLE, controller.state)
    }

    @Test
    fun `touch starting on an interactive control does not trigger hold reposition`() {
        val controller = HudHoldGestureController(touchSlopPx = 24f)

        assertFalse(
            controller.onDown(
                rawX = 100f,
                rawY = 100f,
                pointerId = 0,
                isOnInteractiveControl = true
            )
        )
        assertEquals(HudHoldGestureController.State.IDLE, controller.state)
        assertFalse(controller.onHoldTimeout())
        assertEquals(HudHoldGestureController.UpResult.NONE, controller.onUp())
    }

    @Test
    fun `secondary pointer down cancels pending hold cleanly`() {
        val controller = HudHoldGestureController(touchSlopPx = 24f)

        assertTrue(controller.onDown(rawX = 250f, rawY = 300f, pointerId = 0))
        assertTrue(controller.onMultiPointerDown())
        assertEquals(HudHoldGestureController.State.CANCELLED_BEFORE_HOLD, controller.state)
        assertFalse(controller.onHoldTimeout())
        assertEquals(HudHoldGestureController.UpResult.NONE, controller.onUp())
    }

    @Test
    fun `cancellation resets pending and active reposition states accurately`() {
        val controller = HudHoldGestureController(touchSlopPx = 24f)

        // Cancel while pending
        controller.onDown(rawX = 150f, rawY = 150f, pointerId = 2)
        assertFalse("Cancelling pending hold was not yet active", controller.onCancel())
        assertEquals(HudHoldGestureController.State.IDLE, controller.state)

        // Cancel while active
        controller.onDown(rawX = 150f, rawY = 150f, pointerId = 3)
        assertTrue(controller.onHoldTimeout())
        assertTrue("Cancelling active reposition returns true so caller can revert", controller.onCancel())
        assertEquals(HudHoldGestureController.State.IDLE, controller.state)
        assertEquals(HudHoldGestureController.INVALID_POINTER_ID, controller.activePointerId)
    }

    @Test
    fun `natural stationary finger tremor up to 15dp does not cancel 1s hold activation with 20dp tolerance`() {
        val density3xHoldSlopPx = 60f // 20dp * 3.0 density
        val controller = HudHoldGestureController(touchSlopPx = density3xHoldSlopPx)

        assertTrue(controller.onDown(rawX = 400f, rawY = 500f, pointerId = 0))
        // Finger pad pressure shift of ~42px (14dp diagonal) stays within 20dp stationary hold tolerance
        assertFalse(controller.onMove(rawX = 430f, rawY = 530f))
        assertEquals(HudHoldGestureController.State.PENDING_HOLD, controller.state)
        assertTrue(controller.onHoldTimeout())
        assertEquals(HudHoldGestureController.State.ACTIVE_REPOSITION, controller.state)
        assertEquals(430f, controller.activationRawX, 0.001f)
        assertEquals(530f, controller.activationRawY, 0.001f)
    }
}
