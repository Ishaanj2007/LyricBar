package com.example.ui.components

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.data.preferences.UserPreferencesRepository
import com.example.service.HudDisplayGeometry
import com.example.service.HudOrientation
import com.example.service.HudOverlayProfile
import com.example.service.HudPositioningEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HudHoldGestureTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TestHudHarness(
        initialBounds: HudWindowBounds,
        var geometry: HudDisplayGeometry,
        private val repository: UserPreferencesRepository
    ) {
        var windowBounds: HudWindowBounds = initialBounds
            private set
        var layoutUpdateCount: Int = 0
            private set
        var commitCount: Int = 0
            private set
        var tapCount: Int = 0
            private set

        val controller = HudHoldGestureController(touchSlopPx = 24f)
        val coordinator = HudDragTransactionCoordinator(
            holdController = controller,
            readBounds = { windowBounds },
            applyDragBounds = { target ->
                val resolved = HudPositioningEngine.resolveBounds(
                    profile = HudOverlayProfile(target.x, target.y, target.width, target.height),
                    geometry = geometry
                )
                windowBounds = HudWindowBounds(
                    x = resolved.x,
                    y = resolved.y,
                    width = resolved.widthPx,
                    height = resolved.effectiveHeightPx
                )
                layoutUpdateCount++
            },
            restoreBounds = { snapshot ->
                windowBounds = snapshot
                layoutUpdateCount++
            },
            commitBounds = { orientation, finalBounds ->
                commitCount++
                runBlocking {
                    repository.setOverlayBounds(
                        x = finalBounds.x,
                        y = finalBounds.y,
                        widthPx = finalBounds.width,
                        heightPx = finalBounds.height,
                        orientation = orientation
                    )
                }
            },
            onTapAction = { tapCount++ }
        )

        fun dragNormalBy(dx: Int, dy: Int, rawX: Float, rawY: Float): Boolean {
            return coordinator.onNormalDragMove(rawX, rawY) { initial, deltaX, deltaY ->
                val constraints = HudPositioningEngine.computeConstraints(
                    geometry = geometry,
                    currentWidthPx = initial.width,
                    currentEffectiveHeightPx = initial.height
                )
                HudWindowBounds(
                    x = (initial.x + deltaX).coerceIn(constraints.minX, constraints.maxX),
                    y = (initial.y + deltaY).coerceIn(constraints.minY, constraints.maxY),
                    width = initial.width,
                    height = initial.height
                )
            }
        }

        fun dragEditBy(rawX: Float, rawY: Float, resizeWidthDelta: Int = 0): Boolean {
            return coordinator.onEditMove(rawX, rawY) { initial, deltaX, deltaY ->
                val constraints = HudPositioningEngine.computeConstraints(
                    geometry = geometry,
                    currentWidthPx = initial.width,
                    currentEffectiveHeightPx = initial.height
                )
                val targetW = (initial.width + resizeWidthDelta).coerceIn(
                    constraints.minWidthPx,
                    constraints.maxWidthPx
                )
                HudWindowBounds(
                    x = (initial.x + deltaX).coerceIn(constraints.minX, constraints.maxX),
                    y = (initial.y + deltaY).coerceIn(constraints.minY, constraints.maxY),
                    width = targetW,
                    height = initial.height
                )
            }
        }
    }

    private fun createRepo(name: String): UserPreferencesRepository {
        val file = tempFolder.newFile("$name.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        return UserPreferencesRepository(dataStore)
    }

    private fun portraitGeometry() = HudDisplayGeometry(
        windowWidthPx = 1080,
        windowHeightPx = 2400,
        density = 2.75f,
        navBottomPx = 132,
        statusTopPx = 84
    )

    private fun landscapeGeometry() = HudDisplayGeometry(
        windowWidthPx = 2400,
        windowHeightPx = 1080,
        density = 2.75f,
        cutoutLeftPx = 88,
        navBottomPx = 66,
        statusTopPx = 66
    )

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

        // Just before 1000ms threshold (999ms) -> must not activate reposition yet
        assertFalse(
            "Hold before 1000ms threshold must remain pending",
            controller.onHoldTimeout(elapsedMs = HudHoldGestureController.HOLD_REPOSITION_DELAY_MS - 1L)
        )
        assertEquals(HudHoldGestureController.State.PENDING_HOLD, controller.state)

        // At 1000ms threshold -> activates reposition mode
        assertTrue(
            "At 1000ms threshold timeout activates active reposition",
            controller.onHoldTimeout(elapsedMs = HudHoldGestureController.HOLD_REPOSITION_DELAY_MS)
        )
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

    @Test
    fun `start drag then move HUD then cancel restores original position without persisting`() = runBlocking {
        val repo = createRepo("drag_cancel_rollback")
        val initialBounds = HudWindowBounds(x = 120, y = 320, width = 760, height = 140)
        repo.setOverlayBounds(
            x = initialBounds.x,
            y = initialBounds.y,
            widthPx = initialBounds.width,
            heightPx = initialBounds.height,
            orientation = HudOrientation.PORTRAIT
        )
        val harness = TestHudHarness(initialBounds, portraitGeometry(), repo)

        // 1. Hold-to-reposition path: start drag -> move -> ACTION_CANCEL
        assertTrue(harness.coordinator.onNormalDown(rawX = 300f, rawY = 400f, pointerId = 0))
        assertTrue(harness.coordinator.onHoldTimeout(HudOrientation.PORTRAIT))
        assertTrue(harness.dragNormalBy(dx = 160, dy = 420, rawX = 460f, rawY = 820f))
        assertEquals(HudWindowBounds(x = 280, y = 740, width = 760, height = 140), harness.windowBounds)

        assertTrue("Cancelling active hold drag must roll back", harness.coordinator.cancelActiveDrag())
        assertEquals("Original HUD bounds must be restored", initialBounds, harness.windowBounds)
        assertEquals("Cancelled drag must never commit", 0, harness.commitCount)
        assertEquals(HudHoldGestureController.State.IDLE, harness.controller.state)
        assertFalse(harness.coordinator.isTransactionActive)

        // Trailing move or up after cancellation must not move or commit
        assertFalse(harness.dragNormalBy(dx = 200, dy = 200, rawX = 500f, rawY = 600f))
        assertEquals(HudHoldGestureController.UpResult.NONE, harness.coordinator.onNormalUp())
        assertEquals(initialBounds, harness.windowBounds)
        assertEquals(0, harness.commitCount)

        // 2. Edit-mode drag/resize path: start drag -> move/resize -> ACTION_CANCEL
        harness.coordinator.onEditDown(HudOrientation.PORTRAIT, rawX = 250f, rawY = 350f)
        assertTrue(harness.dragEditBy(rawX = 400f, rawY = 650f, resizeWidthDelta = -120))
        assertEquals(HudWindowBounds(x = 270, y = 620, width = 640, height = 140), harness.windowBounds)

        assertTrue("Cancelling active edit drag/resize must roll back", harness.coordinator.cancelActiveDrag())
        assertEquals("Original HUD bounds and size must be restored", initialBounds, harness.windowBounds)
        assertEquals("Cancelled edit drag must never commit", 0, harness.commitCount)
        assertFalse(harness.coordinator.isEditDragActive)
        assertFalse(harness.coordinator.onEditUp())

        val persisted = repo.userPreferencesFlow.first()
        assertEquals(120, persisted.hudPortraitX)
        assertEquals(320, persisted.hudPortraitY)
        assertEquals(760, persisted.hudPortraitWidth)
        assertEquals(140, persisted.hudPortraitHeight)
    }

    @Test
    fun `start drag then move HUD then complete retains and persists final position`() = runBlocking {
        val repo = createRepo("drag_complete_retain")
        val initialBounds = HudWindowBounds(x = 100, y = 260, width = 760, height = 140)
        repo.setOverlayBounds(
            x = initialBounds.x,
            y = initialBounds.y,
            widthPx = initialBounds.width,
            heightPx = initialBounds.height,
            orientation = HudOrientation.PORTRAIT
        )
        val harness = TestHudHarness(initialBounds, portraitGeometry(), repo)

        assertTrue(harness.coordinator.onNormalDown(rawX = 300f, rawY = 400f, pointerId = 0))
        assertTrue(harness.coordinator.onHoldTimeout(HudOrientation.PORTRAIT))
        assertTrue(harness.dragNormalBy(dx = 140, dy = 520, rawX = 440f, rawY = 920f))

        val expectedFinal = HudWindowBounds(x = 240, y = 780, width = 760, height = 140)
        assertEquals(expectedFinal, harness.windowBounds)

        assertEquals(HudHoldGestureController.UpResult.COMMIT_REPOSITION, harness.coordinator.onNormalUp())
        assertEquals(1, harness.commitCount)
        assertEquals(expectedFinal, harness.windowBounds)
        assertFalse(harness.coordinator.isTransactionActive)

        // Subsequent lifecycle cleanup (e.g., playback pause) must NOT roll back a completed drag
        assertFalse(harness.coordinator.cancelActiveDrag())
        assertEquals(expectedFinal, harness.windowBounds)

        val persisted = repo.userPreferencesFlow.first()
        assertEquals(240, persisted.hudPortraitX)
        assertEquals(780, persisted.hudPortraitY)
        assertEquals(760, persisted.hudPortraitWidth)
        assertEquals(140, persisted.hudPortraitHeight)
    }

    @Test
    fun `cancel before hold threshold performs no repositioning or unintended rollback`() = runBlocking {
        val repo = createRepo("cancel_before_threshold")
        val initialBounds = HudWindowBounds(x = 180, y = 340, width = 720, height = 130)
        repo.setOverlayBounds(
            x = initialBounds.x,
            y = initialBounds.y,
            widthPx = initialBounds.width,
            heightPx = initialBounds.height,
            orientation = HudOrientation.PORTRAIT
        )
        val harness = TestHudHarness(initialBounds, portraitGeometry(), repo)

        assertTrue(harness.coordinator.onNormalDown(rawX = 300f, rawY = 400f, pointerId = 0))
        assertFalse(harness.coordinator.onHoldTimeout(HudOrientation.PORTRAIT, elapsedMs = 999L))
        assertNull("No drag snapshot may exist before 1s threshold", harness.coordinator.activeSnapshot)

        val updatesBeforeCancel = harness.layoutUpdateCount
        assertFalse("Pre-threshold cancel must not trigger rollback", harness.coordinator.cancelActiveDrag())
        assertEquals("Pre-threshold cancel must not rewrite window layout", updatesBeforeCancel, harness.layoutUpdateCount)
        assertEquals(initialBounds, harness.windowBounds)
        assertEquals(0, harness.commitCount)
        assertEquals(0, harness.tapCount)

        // Normal short tap still works cleanly without triggering rollback
        assertTrue(harness.coordinator.onNormalDown(rawX = 300f, rawY = 400f, pointerId = 0))
        assertEquals(HudHoldGestureController.UpResult.TRIGGER_TAP, harness.coordinator.onNormalUp())
        assertEquals(1, harness.tapCount)
        assertFalse(harness.coordinator.cancelActiveDrag())
        assertEquals(updatesBeforeCancel, harness.layoutUpdateCount)
        assertEquals(0, harness.commitCount)
    }

    @Test
    fun `lifecycle cancellation restores original position without committing`() = runBlocking {
        val repo = createRepo("lifecycle_cancel")
        val initialBounds = HudWindowBounds(x = 90, y = 240, width = 740, height = 140)
        repo.setOverlayBounds(
            x = initialBounds.x,
            y = initialBounds.y,
            widthPx = initialBounds.width,
            heightPx = initialBounds.height,
            orientation = HudOrientation.PORTRAIT
        )
        val harness = TestHudHarness(initialBounds, portraitGeometry(), repo)

        // Simulate overlay hidden / disabled / detached / service destroyed mid-drag
        assertTrue(harness.coordinator.onNormalDown(rawX = 200f, rawY = 300f, pointerId = 0))
        assertTrue(harness.coordinator.onHoldTimeout(HudOrientation.PORTRAIT))
        assertTrue(harness.dragNormalBy(dx = 150, dy = 600, rawX = 350f, rawY = 900f))
        assertEquals(HudWindowBounds(x = 240, y = 840, width = 740, height = 140), harness.windowBounds)

        // Lifecycle cleanup path calls cancelActiveDrag()
        assertTrue(harness.coordinator.cancelActiveDrag())
        assertEquals(initialBounds, harness.windowBounds)
        assertEquals(0, harness.commitCount)

        // Idempotent second cleanup call (e.g. view detach after service onDestroy) changes nothing
        val updatesAfterFirstRollback = harness.layoutUpdateCount
        assertFalse(harness.coordinator.cancelActiveDrag())
        assertEquals(updatesAfterFirstRollback, harness.layoutUpdateCount)
        assertEquals(initialBounds, harness.windowBounds)

        val persisted = repo.userPreferencesFlow.first()
        assertEquals(90, persisted.hudPortraitX)
        assertEquals(240, persisted.hudPortraitY)
    }

    @Test
    fun `rotation cancellation leaves original orientation profile unchanged`() = runBlocking {
        val repo = createRepo("rotation_cancel")
        val portraitBounds = HudWindowBounds(x = 140, y = 360, width = 760, height = 140)
        repo.setOverlayBounds(
            x = portraitBounds.x,
            y = portraitBounds.y,
            widthPx = portraitBounds.width,
            heightPx = portraitBounds.height,
            orientation = HudOrientation.PORTRAIT
        )
        val harness = TestHudHarness(portraitBounds, portraitGeometry(), repo)

        // Start dragging in Portrait
        assertTrue(harness.coordinator.onNormalDown(rawX = 300f, rawY = 450f, pointerId = 0))
        assertTrue(harness.coordinator.onHoldTimeout(HudOrientation.PORTRAIT))
        assertTrue(harness.dragNormalBy(dx = 120, dy = 900, rawX = 420f, rawY = 1350f))
        assertEquals(HudWindowBounds(x = 260, y = 1260, width = 760, height = 140), harness.windowBounds)

        // Screen rotates to Landscape during drag -> OverlayService cancels drag before switching geometry
        assertTrue(harness.coordinator.cancelActiveDrag())
        harness.geometry = landscapeGeometry()

        assertEquals(
            "Portrait window bounds must be rolled back on rotation cancellation",
            portraitBounds,
            harness.windowBounds
        )
        assertEquals("Interrupted portrait drag must never persist", 0, harness.commitCount)

        // Trailing touch events after rotation must not pollute portrait or landscape profiles
        assertFalse(harness.dragNormalBy(dx = 300, dy = -200, rawX = 720f, rawY = 1150f))
        assertEquals(HudHoldGestureController.UpResult.NONE, harness.coordinator.onNormalUp())
        assertEquals(0, harness.commitCount)

        val persisted = repo.userPreferencesFlow.first()
        assertEquals(140, persisted.hudPortraitX)
        assertEquals(360, persisted.hudPortraitY)
        assertEquals(760, persisted.hudPortraitWidth)
        assertEquals(140, persisted.hudPortraitHeight)
        assertNull("Uncommitted rotation drag must not create a landscape profile", persisted.landscapeProfileOrNull())
    }

    @Test
    fun `completed landscape repositioning does not overwrite portrait coordinates`() = runBlocking {
        val repo = createRepo("landscape_isolation")
        repo.setOverlayBounds(
            x = 155,
            y = 410,
            widthPx = 780,
            heightPx = 135,
            orientation = HudOrientation.PORTRAIT
        )

        val landscapeGeom = landscapeGeometry()
        val defaultLandscape = HudPositioningEngine.createDefaultLandscapeProfile(landscapeGeom)
        repo.ensureLandscapeProfile(defaultLandscape)
        val initialLandscapeBounds = HudWindowBounds(
            x = defaultLandscape.x,
            y = defaultLandscape.y,
            width = defaultLandscape.widthPx,
            height = defaultLandscape.heightPx
        )

        val harness = TestHudHarness(initialLandscapeBounds, landscapeGeom, repo)

        assertTrue(harness.coordinator.onNormalDown(rawX = 700f, rawY = 200f, pointerId = 0))
        assertTrue(harness.coordinator.onHoldTimeout(HudOrientation.LANDSCAPE))
        assertTrue(harness.dragNormalBy(dx = 220, dy = 180, rawX = 920f, rawY = 380f))

        val movedLandscape = harness.windowBounds
        assertEquals(HudHoldGestureController.UpResult.COMMIT_REPOSITION, harness.coordinator.onNormalUp())
        assertEquals(1, harness.commitCount)

        val persisted = repo.userPreferencesFlow.first()
        // Landscape updated to completed drag position
        assertEquals(movedLandscape.x, persisted.hudLandscapeX)
        assertEquals(movedLandscape.y, persisted.hudLandscapeY)
        assertEquals(movedLandscape.width, persisted.hudLandscapeWidth)
        assertEquals(movedLandscape.height, persisted.hudLandscapeHeight)

        // Portrait remains completely untouched
        assertEquals(155, persisted.hudPortraitX)
        assertEquals(410, persisted.hudPortraitY)
        assertEquals(780, persisted.hudPortraitWidth)
        assertEquals(135, persisted.hudPortraitHeight)
        assertEquals(155, persisted.overlayXPosition)
        assertEquals(410, persisted.overlayYPosition)
    }
}

