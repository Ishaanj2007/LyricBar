package com.example.ui.components

import com.example.data.preferences.LyricsProviderPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderReorderLogicTest {

    private val defaultOrder = LyricsProviderPreference.DEFAULT_PROVIDER_ORDER

    // Fixed untranslated slot map (each row is 60px tall, 8px spacing -> 68px pitch)
    private fun uniformSlots(order: List<LyricsProviderPreference>): Map<LyricsProviderPreference, ProviderSlotBounds> {
        return order.mapIndexed { idx, pref ->
            pref to ProviderSlotBounds(topPx = idx * 68f, heightPx = 60f)
        }.toMap()
    }

    @Test
    fun `holding without moving stays within hysteresis deadzone and does not swap`() {
        val slots = uniformSlots(defaultOrder)
        // Center of row 0 is 30f; center of row 1 is 98f
        val stayAtZero = ProviderReorderLogic.computeSwapTargetIndex(
            order = defaultOrder,
            currentIndex = 0,
            draggedCenterY = 34f,
            slotBounds = slots,
            hysteresisPx = 6f
        )
        assertEquals(0, stayAtZero)

        // Even touching row 1's exact center within hysteresis stays put
        val atEdgeOfHysteresis = ProviderReorderLogic.computeSwapTargetIndex(
            order = defaultOrder,
            currentIndex = 0,
            draggedCenterY = 102f,
            slotBounds = slots,
            hysteresisPx = 6f
        )
        assertEquals(0, atEdgeOfHysteresis)
    }

    @Test
    fun `dragging downward past neighbor midpoint swaps to exact target index including fast multi-row drag`() {
        val slots = uniformSlots(defaultOrder)
        // Cross past row 1 (center 98f + 6f = 104f)
        val singleStep = ProviderReorderLogic.computeSwapTargetIndex(
            order = defaultOrder,
            currentIndex = 0,
            draggedCenterY = 110f,
            slotBounds = slots,
            hysteresisPx = 6f
        )
        assertEquals(1, singleStep)

        // Fast drag directly past row 3's midpoint (3 * 68 + 30 = 234f)
        val fastJump = ProviderReorderLogic.computeSwapTargetIndex(
            order = defaultOrder,
            currentIndex = 0,
            draggedCenterY = 250f,
            slotBounds = slots,
            hysteresisPx = 6f
        )
        assertEquals(3, fastJump)

        val reordered = ProviderReorderLogic.moveItem(defaultOrder, fromIndex = 0, toIndex = fastJump)
        assertEquals(defaultOrder[0], reordered[3])
        assertEquals(defaultOrder[1], reordered[0])
        assertEquals(defaultOrder.toSet(), reordered.toSet())
    }

    @Test
    fun `dragging upward past neighbor midpoint moves provider to higher priority cleanly`() {
        val slots = uniformSlots(defaultOrder)
        // Last item (idx 6) dragged upward to idx 1 (center 98f -> 80f < 98 - 6)
        val target = ProviderReorderLogic.computeSwapTargetIndex(
            order = defaultOrder,
            currentIndex = 6,
            draggedCenterY = 80f,
            slotBounds = slots,
            hysteresisPx = 6f
        )
        assertEquals(1, target)

        val reordered = ProviderReorderLogic.moveItem(defaultOrder, 6, target)
        assertEquals(LyricsProviderPreference.UNISON, reordered[1])
        assertEquals(LyricsProviderPreference.LRCLIB, reordered[0])
    }

    @Test
    fun `repeated sequential drags preserve all 7 providers cleanly without dropping or duplicating`() {
        var order = defaultOrder
        order = ProviderReorderLogic.moveItem(order, 6, 0) // UNISON -> #1
        order = ProviderReorderLogic.moveItem(order, 4, 2) // KUGOU -> #3
        order = ProviderReorderLogic.moveItem(order, 0, 5) // UNISON -> #6
        assertEquals(7, order.size)
        assertEquals(defaultOrder.toSet(), order.toSet())
    }

    @Test
    fun `auto-scroll computes negative delta near top edge, positive near bottom edge, and zero in middle`() {
        val topDelta = ProviderReorderLogic.computeAutoScrollDeltaPx(
            pointerRootY = 110f,
            viewportTopRootY = 100f,
            viewportBottomRootY = 1000f,
            edgeZonePx = 56f,
            maxStepPx = 12f
        )
        assertTrue(topDelta < 0f)

        val middleDelta = ProviderReorderLogic.computeAutoScrollDeltaPx(
            pointerRootY = 500f,
            viewportTopRootY = 100f,
            viewportBottomRootY = 1000f,
            edgeZonePx = 56f,
            maxStepPx = 12f
        )
        assertEquals(0f, middleDelta, 0.001f)

        val bottomDelta = ProviderReorderLogic.computeAutoScrollDeltaPx(
            pointerRootY = 990f,
            viewportTopRootY = 100f,
            viewportBottomRootY = 1000f,
            edgeZonePx = 56f,
            maxStepPx = 12f
        )
        assertTrue(bottomDelta > 0f)
    }
}
