package com.example.ui.components

import com.example.data.backup.BackupAppSettingsData
import com.example.data.preferences.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KaraokeLuminousGlowTest {

    @Test
    fun `luminous envelope ramps up smoothly, peaks mid-word, and settles cleanly after endMs`() {
        val startMs = 1000L
        val endMs = 1400L

        // Before word starts: zero glow envelope
        assertEquals(
            0f,
            HudVisualSpecs.computeWordLuminousEnvelope(startMs, endMs, 950L, 0f),
            0.0001f
        )

        // Attack phase: smoothly increasing
        val early = HudVisualSpecs.computeWordLuminousEnvelope(startMs, endMs, 1040L, 0.10f)
        val mid = HudVisualSpecs.computeWordLuminousEnvelope(startMs, endMs, 1200L, 0.50f)
        assertTrue("Expected early attack > 0, was $early", early > 0f)
        assertTrue("Expected mid-word peak ($mid) > early ($early)", mid > early)
        assertEquals(1.0f, mid, 0.01f)

        // Just before endMs: still warm, no abrupt drop
        val nearEnd = HudVisualSpecs.computeWordLuminousEnvelope(startMs, endMs, 1390L, 0.975f)
        assertTrue("Expected warm sustain near end, was $nearEnd", nearEnd >= 0.70f)

        // Settle phase after endMs: eases down smoothly to 0f by WORD_GLOW_SETTLE_MS
        val settleMid = HudVisualSpecs.computeWordLuminousEnvelope(
            startMs,
            endMs,
            endMs + (HudVisualSpecs.WORD_GLOW_SETTLE_MS / 2L),
            1f
        )
        assertTrue("Expected partial settle > 0 and < nearEnd", settleMid in 0.05f..nearEnd)

        val settled = HudVisualSpecs.computeWordLuminousEnvelope(
            startMs,
            endMs,
            endMs + HudVisualSpecs.WORD_GLOW_SETTLE_MS,
            1f
        )
        assertEquals(0f, settled, 0.0001f)
    }

    @Test
    fun `glow alpha respects text opacity and intensity bounds`() {
        assertEquals(0, HudVisualSpecs.computeGlowAlpha(baseActiveAlpha = 255, intensity = 0f))
        assertEquals(128, HudVisualSpecs.computeGlowAlpha(baseActiveAlpha = 255, intensity = 0.5f))
        // Low text opacity (~0.25 -> baseAlpha 64) scales glow proportionally
        val lowOpacityGlow = HudVisualSpecs.computeGlowAlpha(baseActiveAlpha = 64, intensity = 1f)
        assertEquals(64, lowOpacityGlow)
    }

    @Test
    fun `glow radii stay bounded across small and large font sizes`() {
        val smallRest = HudVisualSpecs.computeRestingGlowRadiusPx(20f)
        val smallPeak = HudVisualSpecs.computePeakGlowRadiusPx(20f)
        assertTrue(smallRest in 6f..11f)
        assertTrue(smallPeak in 10f..18f)
        assertTrue(smallPeak > smallRest)

        val hugeRest = HudVisualSpecs.computeRestingGlowRadiusPx(120f)
        val hugePeak = HudVisualSpecs.computePeakGlowRadiusPx(120f)
        assertEquals(11f, hugeRest, 0.001f)
        assertEquals(18f, hugePeak, 0.001f)
    }

    @Test
    fun `enhanced glow preference defaults to false in preferences and app settings backup`() {
        assertFalse(UserPreferences().karaokeEnhancedGlow)
        assertFalse(BackupAppSettingsData().karaokeEnhancedGlow)
    }
}
