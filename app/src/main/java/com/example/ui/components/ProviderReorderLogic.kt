package com.example.ui.components

import com.example.data.preferences.LyricsProviderPreference

/**
 * Pure container-space bounds recorded for each draggable provider row (untranslated slot geometry).
 */
internal data class ProviderSlotBounds(
    val topPx: Float,
    val heightPx: Float
) {
    val bottomPx: Float get() = topPx + heightPx
    val centerPx: Float get() = topPx + (heightPx * 0.5f)
}

/**
 * Stateless, deterministic reorder calculation & session state helpers used by
 * SettingsScreen's unified Lyrics Provider list.
 */
internal object ProviderReorderLogic {

    /**
     * Given the dragged provider's current index, its visual center Y (in container coordinates),
     * and the untranslated slot bounds of each provider currently laid out, returns the new target
     * index if the dragged row has crossed another row's midpoint beyond [hysteresisPx],
     * or returns [currentIndex] when no swap should occur (e.g., holding without moving).
     */
    fun computeSwapTargetIndex(
        order: List<LyricsProviderPreference>,
        currentIndex: Int,
        draggedCenterY: Float,
        slotBounds: Map<LyricsProviderPreference, ProviderSlotBounds>,
        hysteresisPx: Float = 6f
    ): Int {
        if (currentIndex !in order.indices || order.size <= 1) return currentIndex

        // Moving downward: check subsequent items; pick the furthest crossed midpoint
        var targetIndex = currentIndex
        for (idx in (currentIndex + 1) until order.size) {
            val neighborBounds = slotBounds[order[idx]] ?: continue
            if (draggedCenterY > neighborBounds.centerPx + hysteresisPx) {
                targetIndex = idx
            } else {
                break
            }
        }
        if (targetIndex != currentIndex) return targetIndex

        // Moving upward: check preceding items; pick the furthest crossed midpoint
        for (idx in (currentIndex - 1) downTo 0) {
            val neighborBounds = slotBounds[order[idx]] ?: continue
            if (draggedCenterY < neighborBounds.centerPx - hysteresisPx) {
                targetIndex = idx
            } else {
                break
            }
        }
        return targetIndex
    }

    /**
     * Moves item at [fromIndex] to [toIndex] cleanly without allocating if indices match or are invalid.
     */
    fun moveItem(
        order: List<LyricsProviderPreference>,
        fromIndex: Int,
        toIndex: Int
    ): List<LyricsProviderPreference> {
        if (fromIndex == toIndex || fromIndex !in order.indices || toIndex !in order.indices) {
            return order
        }
        val mutable = order.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        return mutable
    }

    /**
     * Computes auto-scroll velocity (px per frame) when the pointer's root Y coordinate enters
     * the top or bottom [edgeZonePx] of the visible scroll viewport.
     */
    fun computeAutoScrollDeltaPx(
        pointerRootY: Float,
        viewportTopRootY: Float,
        viewportBottomRootY: Float,
        edgeZonePx: Float,
        maxStepPx: Float
    ): Float {
        if (edgeZonePx <= 0f || maxStepPx <= 0f || viewportBottomRootY <= viewportTopRootY) return 0f
        val topThreshold = viewportTopRootY + edgeZonePx
        val bottomThreshold = viewportBottomRootY - edgeZonePx
        return when {
            pointerRootY < topThreshold -> {
                val ratio = ((topThreshold - pointerRootY) / edgeZonePx).coerceIn(0f, 1f)
                -(maxStepPx * (0.25f + 0.75f * ratio))
            }
            pointerRootY > bottomThreshold -> {
                val ratio = ((pointerRootY - bottomThreshold) / edgeZonePx).coerceIn(0f, 1f)
                maxStepPx * (0.25f + 0.75f * ratio)
            }
            else -> 0f
        }
    }
}
