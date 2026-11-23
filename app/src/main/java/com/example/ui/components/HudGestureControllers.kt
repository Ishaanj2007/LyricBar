package com.example.ui.components

import com.example.service.HudBoundsConstraints
import com.example.service.HudOrientation
import kotlin.math.hypot

enum class TouchTarget {
    NONE,
    MOVE,
    RESIZE_TOP,
    RESIZE_BOTTOM,
    RESIZE_LEFT,
    RESIZE_RIGHT,
    RESIZE_TL,
    RESIZE_TR,
    RESIZE_BL,
    RESIZE_BR
}

data class HudWindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

data class HudDragSnapshot(
    val orientation: HudOrientation,
    val initialBounds: HudWindowBounds
)

class HudHoldGestureController(
    private val touchSlopPx: Float,
    val holdDurationMs: Long = HOLD_REPOSITION_DELAY_MS
) {
    enum class State {
        IDLE,
        PENDING_HOLD,
        CANCELLED_BEFORE_HOLD,
        ACTIVE_REPOSITION
    }

    enum class UpResult {
        NONE,
        TRIGGER_TAP,
        COMMIT_REPOSITION
    }

    var state: State = State.IDLE
        private set
    var activePointerId: Int = INVALID_POINTER_ID
        private set
    var downRawX: Float = 0f
        private set
    var downRawY: Float = 0f
        private set
    var activationRawX: Float = 0f
        private set
    var activationRawY: Float = 0f
        private set

    fun onDown(
        rawX: Float,
        rawY: Float,
        pointerId: Int,
        isOnInteractiveControl: Boolean = false
    ): Boolean {
        if (isOnInteractiveControl) {
            reset()
            return false
        }
        activePointerId = pointerId
        downRawX = rawX
        downRawY = rawY
        activationRawX = rawX
        activationRawY = rawY
        state = State.PENDING_HOLD
        return true
    }

    /** Returns true if finger movement beyond touch slop cancelled a pending hold. */
    fun onMove(rawX: Float, rawY: Float): Boolean {
        return when (state) {
            State.PENDING_HOLD -> {
                activationRawX = rawX
                activationRawY = rawY
                val dist = hypot(rawX - downRawX, rawY - downRawY)
                if (dist > touchSlopPx) {
                    state = State.CANCELLED_BEFORE_HOLD
                    true
                } else {
                    false
                }
            }
            State.ACTIVE_REPOSITION,
            State.IDLE,
            State.CANCELLED_BEFORE_HOLD -> false
        }
    }

    fun onHoldTimeout(elapsedMs: Long = holdDurationMs): Boolean {
        if (state != State.PENDING_HOLD) return false
        if (elapsedMs < holdDurationMs) return false
        state = State.ACTIVE_REPOSITION
        return true
    }

    /** Returns true if a secondary pointer cancelled a pending hold. */
    fun onMultiPointerDown(): Boolean {
        if (state == State.PENDING_HOLD) {
            state = State.CANCELLED_BEFORE_HOLD
            return true
        }
        return false
    }

    fun onUp(): UpResult {
        val result = when (state) {
            State.PENDING_HOLD -> UpResult.TRIGGER_TAP
            State.ACTIVE_REPOSITION -> UpResult.COMMIT_REPOSITION
            State.IDLE, State.CANCELLED_BEFORE_HOLD -> UpResult.NONE
        }
        reset()
        return result
    }

    /** Returns true if an active reposition was interrupted. */
    fun onCancel(): Boolean {
        val wasActive = state == State.ACTIVE_REPOSITION
        reset()
        return wasActive
    }

    fun reset() {
        state = State.IDLE
        activePointerId = INVALID_POINTER_ID
        downRawX = 0f
        downRawY = 0f
        activationRawX = 0f
        activationRawY = 0f
    }

    companion object {
        const val HOLD_REPOSITION_DELAY_MS = 1000L
        const val HOLD_REPOSITION_SECONDS = (HOLD_REPOSITION_DELAY_MS / 1000L).toInt()
        const val INVALID_POINTER_ID = -1
    }
}

class HudDragTransactionCoordinator(
    val holdController: HudHoldGestureController,
    private val readBounds: () -> HudWindowBounds,
    private val applyDragBounds: (HudWindowBounds) -> Unit,
    private val restoreBounds: (HudWindowBounds) -> Unit,
    private val commitBounds: (HudOrientation, HudWindowBounds) -> Unit,
    private val onTapAction: () -> Unit = {}
) {
    var activeSnapshot: HudDragSnapshot? = null
        private set
    var isEditDragActive: Boolean = false
        private set
    var isClickThroughEnabled: Boolean = false
        private set
    var initialTouchX: Float = 0f
        private set
    var initialTouchY: Float = 0f
        private set

    val isTransactionActive: Boolean
        get() = activeSnapshot != null &&
            (isEditDragActive || holdController.state == HudHoldGestureController.State.ACTIVE_REPOSITION)

    fun setClickThroughEnabled(enabled: Boolean): Boolean {
        isClickThroughEnabled = enabled
        return if (enabled) cancelActiveDrag() else false
    }

    fun onNormalDown(
        rawX: Float,
        rawY: Float,
        pointerId: Int,
        isOnInteractiveControl: Boolean = false
    ): Boolean {
        if (isClickThroughEnabled) {
            cancelActiveDrag()
            return false
        }
        if (isTransactionActive) {
            cancelActiveDrag()
        }
        return holdController.onDown(
            rawX = rawX,
            rawY = rawY,
            pointerId = pointerId,
            isOnInteractiveControl = isOnInteractiveControl
        )
    }

    fun onNormalMultiPointerDown(): Boolean {
        if (isClickThroughEnabled) return false
        return holdController.onMultiPointerDown()
    }

    fun onNormalMovePending(rawX: Float, rawY: Float): Boolean {
        if (isClickThroughEnabled) return false
        return holdController.onMove(rawX, rawY)
    }

    fun onHoldTimeout(
        orientation: HudOrientation,
        elapsedMs: Long = holdController.holdDurationMs
    ): Boolean {
        if (isClickThroughEnabled) {
            cancelActiveDrag()
            return false
        }
        if (!holdController.onHoldTimeout(elapsedMs)) {
            return false
        }
        initialTouchX = holdController.activationRawX
        initialTouchY = holdController.activationRawY
        activeSnapshot = HudDragSnapshot(
            orientation = orientation,
            initialBounds = readBounds()
        )
        return true
    }

    fun onNormalDragMove(
        rawX: Float,
        rawY: Float,
        computeTarget: (initialBounds: HudWindowBounds, dx: Int, dy: Int) -> HudWindowBounds
    ): Boolean {
        if (isClickThroughEnabled || holdController.state != HudHoldGestureController.State.ACTIVE_REPOSITION) {
            return false
        }
        val snapshot = activeSnapshot ?: return false
        val dx = (rawX - initialTouchX).toInt()
        val dy = (rawY - initialTouchY).toInt()
        applyDragBounds(computeTarget(snapshot.initialBounds, dx, dy))
        return true
    }

    fun onNormalUp(): HudHoldGestureController.UpResult {
        if (isClickThroughEnabled) {
            cancelActiveDrag()
            return HudHoldGestureController.UpResult.NONE
        }
        val result = holdController.onUp()
        when (result) {
            HudHoldGestureController.UpResult.TRIGGER_TAP -> {
                activeSnapshot = null
                onTapAction()
            }
            HudHoldGestureController.UpResult.COMMIT_REPOSITION -> {
                val snapshot = activeSnapshot
                activeSnapshot = null
                if (snapshot != null) {
                    commitBounds(snapshot.orientation, readBounds())
                }
            }
            HudHoldGestureController.UpResult.NONE -> {
                activeSnapshot = null
            }
        }
        return result
    }

    fun onEditDown(orientation: HudOrientation, rawX: Float, rawY: Float): HudDragSnapshot? {
        if (isClickThroughEnabled) {
            cancelActiveDrag()
            return null
        }
        if (isTransactionActive || holdController.state != HudHoldGestureController.State.IDLE) {
            cancelActiveDrag()
        }
        isEditDragActive = true
        initialTouchX = rawX
        initialTouchY = rawY
        val snapshot = HudDragSnapshot(
            orientation = orientation,
            initialBounds = readBounds()
        )
        activeSnapshot = snapshot
        return snapshot
    }

    fun onEditMove(
        rawX: Float,
        rawY: Float,
        computeTarget: (initialBounds: HudWindowBounds, dx: Int, dy: Int) -> HudWindowBounds
    ): Boolean {
        if (!isEditDragActive) return false
        val snapshot = activeSnapshot ?: return false
        val dx = (rawX - initialTouchX).toInt()
        val dy = (rawY - initialTouchY).toInt()
        applyDragBounds(computeTarget(snapshot.initialBounds, dx, dy))
        return true
    }

    fun onEditUp(): Boolean {
        if (!isEditDragActive) return false
        isEditDragActive = false
        val snapshot = activeSnapshot
        activeSnapshot = null
        if (snapshot != null) {
            commitBounds(snapshot.orientation, readBounds())
        }
        return true
    }

    /**
     * Cancels any pending or active drag gesture.
     * Returns true if an active drag transaction was rolled back to its original bounds.
     */
    fun cancelActiveDrag(): Boolean {
        val wasHoldActive = holdController.onCancel()
        val wasEditActive = isEditDragActive
        isEditDragActive = false
        val snapshot = activeSnapshot
        activeSnapshot = null

        val shouldRollback = (wasHoldActive || wasEditActive) && snapshot != null
        if (shouldRollback) {
            restoreBounds(snapshot!!.initialBounds)
        }
        return shouldRollback
    }
}

class HudTripleTapDetector(
    private val tapTouchSlopPx: Float,
    val maxTapDurationMs: Long = DEFAULT_MAX_TAP_DURATION_MS,
    val maxTapIntervalMs: Long = DEFAULT_MAX_TAP_INTERVAL_MS,
    val launchCooldownMs: Long = DEFAULT_LAUNCH_COOLDOWN_MS
) {
    var tapCount: Int = 0
        private set
    var isEnabled: Boolean = true
        private set
    private var activePointerId: Int = INVALID_POINTER_ID
    private var downRawX: Float = 0f
    private var downRawY: Float = 0f
    private var downTimestampMs: Long = 0L
    private var currentTouchInvalidated: Boolean = false
    private var lastTapUpTimestampMs: Long = Long.MIN_VALUE
    private var lastTriggerTimestampMs: Long = Long.MIN_VALUE

    fun setEnabled(enabled: Boolean) {
        if (isEnabled != enabled) {
            isEnabled = enabled
            if (!enabled) {
                reset()
            }
        }
    }

    fun onDown(
        rawX: Float,
        rawY: Float,
        pointerId: Int,
        timestampMs: Long,
        isOnInteractiveControl: Boolean = false
    ): Boolean {
        if (!isEnabled || isOnInteractiveControl) {
            reset()
            return false
        }
        if (lastTriggerTimestampMs != Long.MIN_VALUE &&
            (timestampMs - lastTriggerTimestampMs) in 0L until launchCooldownMs
        ) {
            tapCount = 0
            activePointerId = INVALID_POINTER_ID
            currentTouchInvalidated = true
            return false
        }
        if (tapCount > 0 &&
            (lastTapUpTimestampMs == Long.MIN_VALUE ||
                timestampMs < lastTapUpTimestampMs ||
                (timestampMs - lastTapUpTimestampMs) > maxTapIntervalMs)
        ) {
            tapCount = 0
            lastTapUpTimestampMs = Long.MIN_VALUE
        }
        activePointerId = pointerId
        downRawX = rawX
        downRawY = rawY
        downTimestampMs = timestampMs
        currentTouchInvalidated = false
        return true
    }

    fun onMove(rawX: Float, rawY: Float, pointerId: Int): Boolean {
        if (!isEnabled || currentTouchInvalidated || activePointerId == INVALID_POINTER_ID) {
            return false
        }
        if (pointerId != activePointerId) {
            return false
        }
        val dist = hypot(rawX - downRawX, rawY - downRawY)
        if (dist > tapTouchSlopPx) {
            invalidateSequence()
            return true
        }
        return false
    }

    fun onMultiPointerDown() {
        if (activePointerId != INVALID_POINTER_ID || tapCount > 0) {
            invalidateSequence()
        }
    }

    fun onDragOrHoldActivated() {
        invalidateSequence()
    }

    fun onUp(pointerId: Int, timestampMs: Long): Boolean {
        if (!isEnabled) {
            reset()
            return false
        }
        if (currentTouchInvalidated || activePointerId == INVALID_POINTER_ID || pointerId != activePointerId) {
            val wasInvalidated = currentTouchInvalidated
            resetActiveTouchOnly()
            if (wasInvalidated) {
                tapCount = 0
                lastTapUpTimestampMs = Long.MIN_VALUE
            }
            return false
        }
        val durationMs = timestampMs - downTimestampMs
        resetActiveTouchOnly()

        if (durationMs < 0L || durationMs > maxTapDurationMs) {
            tapCount = 0
            lastTapUpTimestampMs = Long.MIN_VALUE
            return false
        }

        tapCount += 1
        lastTapUpTimestampMs = timestampMs

        if (tapCount >= REQUIRED_TAP_COUNT) {
            tapCount = 0
            lastTapUpTimestampMs = Long.MIN_VALUE
            lastTriggerTimestampMs = timestampMs
            return true
        }
        return false
    }

    fun onCancel() {
        reset()
    }

    fun reset() {
        tapCount = 0
        activePointerId = INVALID_POINTER_ID
        downRawX = 0f
        downRawY = 0f
        downTimestampMs = 0L
        currentTouchInvalidated = false
        lastTapUpTimestampMs = Long.MIN_VALUE
    }

    private fun invalidateSequence() {
        tapCount = 0
        lastTapUpTimestampMs = Long.MIN_VALUE
        currentTouchInvalidated = true
        activePointerId = INVALID_POINTER_ID
    }

    private fun resetActiveTouchOnly() {
        activePointerId = INVALID_POINTER_ID
        downRawX = 0f
        downRawY = 0f
        downTimestampMs = 0L
        currentTouchInvalidated = false
    }

    companion object {
        const val REQUIRED_TAP_COUNT = 3
        const val DEFAULT_MAX_TAP_DURATION_MS = 400L
        const val DEFAULT_MAX_TAP_INTERVAL_MS = 450L
        const val DEFAULT_LAUNCH_COOLDOWN_MS = 800L
        const val INVALID_POINTER_ID = -1
    }
}

object HudBoundsCalculator {

    fun computeEditMoveBounds(
        initialBounds: HudWindowBounds,
        dx: Int,
        dy: Int,
        target: TouchTarget,
        constraints: HudBoundsConstraints?
    ): HudWindowBounds {
        val initialX = initialBounds.x
        val initialY = initialBounds.y
        val initialW = initialBounds.width
        val initialH = initialBounds.height

        val minW = constraints?.minWidthPx ?: 280
        val maxW = constraints?.maxWidthPx ?: 2000
        val minH = constraints?.minHeightPx ?: 60
        val maxH = constraints?.maxHeightPx ?: 800
        val minX = constraints?.minX ?: -2000
        val maxRight = constraints?.minSafeRightPx ?: Int.MAX_VALUE
        val minY = constraints?.minY ?: 0
        val maxBottom = constraints?.maxSafeBottomPx ?: Int.MAX_VALUE

        var targetX = initialX
        var targetY = initialY
        var targetW = initialW
        var targetH = initialH

        when (target) {
            TouchTarget.MOVE -> {
                val moveMaxX = if (maxRight < Int.MAX_VALUE) (maxRight - initialW).coerceAtLeast(minX) else 2000
                val moveMaxY = if (maxBottom < Int.MAX_VALUE) (maxBottom - initialH).coerceAtLeast(minY) else 3000
                targetX = (initialX + dx).coerceIn(minX, moveMaxX)
                targetY = (initialY + dy).coerceIn(minY, moveMaxY)
            }
            TouchTarget.RESIZE_RIGHT -> {
                val availMaxW = if (maxRight < Int.MAX_VALUE) (maxRight - initialX).coerceIn(minW, maxW) else maxW
                targetW = (initialW + dx).coerceIn(minW, availMaxW)
            }
            TouchTarget.RESIZE_BOTTOM -> {
                val availMaxH = if (maxBottom < Int.MAX_VALUE) (maxBottom - initialY).coerceIn(minH, maxH) else maxH
                targetH = (initialH + dy).coerceIn(minH, availMaxH)
            }
            TouchTarget.RESIZE_BR -> {
                val availMaxW = if (maxRight < Int.MAX_VALUE) (maxRight - initialX).coerceIn(minW, maxW) else maxW
                val availMaxH = if (maxBottom < Int.MAX_VALUE) (maxBottom - initialY).coerceIn(minH, maxH) else maxH
                targetW = (initialW + dx).coerceIn(minW, availMaxW)
                targetH = (initialH + dy).coerceIn(minH, availMaxH)
            }
            TouchTarget.RESIZE_LEFT -> {
                val anchorRight = initialX + initialW
                val availMaxW = (anchorRight - minX).coerceIn(minW, maxW)
                val newW = (initialW - dx).coerceIn(minW, availMaxW)
                targetW = newW
                targetX = (anchorRight - newW).coerceIn(minX, (maxRight - newW).coerceAtLeast(minX))
            }
            TouchTarget.RESIZE_TOP -> {
                val anchorBottom = initialY + initialH
                val availMaxH = (anchorBottom - minY).coerceIn(minH, maxH)
                val newH = (initialH - dy).coerceIn(minH, availMaxH)
                targetH = newH
                targetY = (anchorBottom - newH).coerceIn(minY, (maxBottom - newH).coerceAtLeast(minY))
            }
            TouchTarget.RESIZE_TL -> {
                val anchorRight = initialX + initialW
                val anchorBottom = initialY + initialH
                val availMaxW = (anchorRight - minX).coerceIn(minW, maxW)
                val availMaxH = (anchorBottom - minY).coerceIn(minH, maxH)
                val newW = (initialW - dx).coerceIn(minW, availMaxW)
                val newH = (initialH - dy).coerceIn(minH, availMaxH)
                targetW = newW
                targetH = newH
                targetX = (anchorRight - newW).coerceIn(minX, (maxRight - newW).coerceAtLeast(minX))
                targetY = (anchorBottom - newH).coerceIn(minY, (maxBottom - newH).coerceAtLeast(minY))
            }
            TouchTarget.RESIZE_TR -> {
                val anchorBottom = initialY + initialH
                val availMaxW = if (maxRight < Int.MAX_VALUE) (maxRight - initialX).coerceIn(minW, maxW) else maxW
                val availMaxH = (anchorBottom - minY).coerceIn(minH, maxH)
                val newW = (initialW + dx).coerceIn(minW, availMaxW)
                val newH = (initialH - dy).coerceIn(minH, availMaxH)
                targetW = newW
                targetH = newH
                targetY = (anchorBottom - newH).coerceIn(minY, (maxBottom - newH).coerceAtLeast(minY))
            }
            TouchTarget.RESIZE_BL -> {
                val anchorRight = initialX + initialW
                val availMaxW = (anchorRight - minX).coerceIn(minW, maxW)
                val availMaxH = if (maxBottom < Int.MAX_VALUE) (maxBottom - initialY).coerceIn(minH, maxH) else maxH
                val newW = (initialW - dx).coerceIn(minW, availMaxW)
                val newH = (initialH + dy).coerceIn(minH, availMaxH)
                targetW = newW
                targetH = newH
                targetX = (anchorRight - newW).coerceIn(minX, (maxRight - newW).coerceAtLeast(minX))
            }
            TouchTarget.NONE -> {}
        }

        return HudWindowBounds(targetX, targetY, targetW, targetH)
    }

    fun computeNormalMoveBounds(
        initialBounds: HudWindowBounds,
        dx: Int,
        dy: Int,
        constraints: HudBoundsConstraints?
    ): HudWindowBounds {
        val initialX = initialBounds.x
        val initialY = initialBounds.y
        val initialW = initialBounds.width
        val initialH = initialBounds.height

        val minX = constraints?.minX ?: -2000
        val maxRight = constraints?.minSafeRightPx ?: Int.MAX_VALUE
        val minY = constraints?.minY ?: 0
        val maxBottom = constraints?.maxSafeBottomPx ?: Int.MAX_VALUE
        val moveMaxX = if (maxRight < Int.MAX_VALUE) (maxRight - initialW).coerceAtLeast(minX) else 2000
        val moveMaxY = if (maxBottom < Int.MAX_VALUE) (maxBottom - initialH).coerceAtLeast(minY) else 3000

        val targetX = (initialX + dx).coerceIn(minX, moveMaxX)
        val targetY = (initialY + dy).coerceIn(minY, moveMaxY)
        return HudWindowBounds(targetX, targetY, initialW, initialH)
    }
}
