package com.example.ui.components

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.data.preferences.HudBackdropStyle
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HudBackdropTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `default UserPreferences match HUD Backdrop specification`() {
        val prefs = UserPreferences()
        assertFalse("HUD Backdrop must default to OFF", prefs.hudBackdropEnabled)
        assertEquals(HudBackdropStyle.SOLID, prefs.hudBackdropStyle)
        assertEquals(0.65f, prefs.hudBackdropOpacity, 0.001f)
        assertEquals(18, prefs.hudBackdropCornerRadiusDp)
        assertEquals(16, prefs.hudBackdropHorizontalPaddingDp)
        assertEquals(10, prefs.hudBackdropVerticalPaddingDp)
        assertEquals("Text opacity must remain 100% by default", 1.0f, prefs.overlayTextOpacity, 0.001f)
    }

    @Test
    fun `HUD Backdrop OFF returns null render spec so existing text-only HUD is preserved`() {
        val spec = MinimalOverlayView.computeBackdropSpec(
            enabled = false,
            style = "SOLID",
            opacity = 0.65f,
            cornerRadiusDp = 18f,
            horizontalPaddingDp = 16f,
            verticalPaddingDp = 10f,
            density = 3.0f,
            viewWidth = 850,
            viewHeight = 160,
            textLeft = 200f,
            textTop = 55f,
            textRight = 650f,
            textBottom = 105f
        )
        assertNull("When HUD Backdrop is disabled, no backdrop spec should be produced", spec)
    }

    @Test
    fun `HUD Backdrop ON Solid at 30, 65, and 100 percent opacity produces accurate alpha and no border`() {
        val spec30 = MinimalOverlayView.computeBackdropSpec(
            enabled = true,
            style = "SOLID",
            opacity = 0.30f,
            cornerRadiusDp = 18f,
            horizontalPaddingDp = 16f,
            verticalPaddingDp = 10f,
            density = 2.0f,
            viewWidth = 850,
            viewHeight = 160,
            textLeft = 200f,
            textTop = 50f,
            textRight = 650f,
            textBottom = 100f
        )
        val spec65 = MinimalOverlayView.computeBackdropSpec(
            enabled = true,
            style = "SOLID",
            opacity = 0.65f,
            cornerRadiusDp = 18f,
            horizontalPaddingDp = 16f,
            verticalPaddingDp = 10f,
            density = 2.0f,
            viewWidth = 850,
            viewHeight = 160,
            textLeft = 200f,
            textTop = 50f,
            textRight = 650f,
            textBottom = 100f
        )
        val spec100 = MinimalOverlayView.computeBackdropSpec(
            enabled = true,
            style = "SOLID",
            opacity = 1.00f,
            cornerRadiusDp = 18f,
            horizontalPaddingDp = 16f,
            verticalPaddingDp = 10f,
            density = 2.0f,
            viewWidth = 850,
            viewHeight = 160,
            textLeft = 200f,
            textTop = 50f,
            textRight = 650f,
            textBottom = 100f
        )

        assertNotNull(spec30)
        assertNotNull(spec65)
        assertNotNull(spec100)

        assertEquals(77, spec30!!.fillAlpha)
        assertEquals(166, spec65!!.fillAlpha)
        assertEquals(255, spec100!!.fillAlpha)

        assertFalse(spec30.showBorder)
        assertFalse(spec65.showBorder)
        assertFalse(spec100.showBorder)
        assertEquals(0, spec65.glassSheenAlpha)
        assertEquals(MinimalOverlayView.SOLID_BACKDROP_COLOR, spec65.fillColor)
    }

    @Test
    fun `HUD Backdrop ON Glass at 65 percent opacity enables frosted glass tint, sheen, and soft border`() {
        val specGlass = MinimalOverlayView.computeBackdropSpec(
            enabled = true,
            style = "GLASS",
            opacity = 0.65f,
            cornerRadiusDp = 18f,
            horizontalPaddingDp = 16f,
            verticalPaddingDp = 10f,
            density = 2.5f,
            viewWidth = 850,
            viewHeight = 160,
            textLeft = 180f,
            textTop = 52f,
            textRight = 670f,
            textBottom = 108f
        )

        val specSolid = MinimalOverlayView.computeBackdropSpec(
            enabled = true,
            style = "SOLID",
            opacity = 0.65f,
            cornerRadiusDp = 18f,
            horizontalPaddingDp = 16f,
            verticalPaddingDp = 10f,
            density = 2.5f,
            viewWidth = 850,
            viewHeight = 160,
            textLeft = 180f,
            textTop = 52f,
            textRight = 670f,
            textBottom = 108f
        )

        assertNotNull(specGlass)
        assertNotNull(specSolid)
        assertEquals(MinimalOverlayView.GLASS_BACKDROP_COLOR, specGlass!!.fillColor)
        assertTrue(
            "Refined Glass backdrop must stay more translucent than Solid at equal opacity",
            specGlass.fillAlpha < specSolid!!.fillAlpha
        )
        assertTrue("Glass style must display a subtle soft border", specGlass.showBorder)
        assertTrue("Glass border alpha should be positive and subtle", specGlass.borderAlpha in 20..80)
        assertTrue("Glass sheen alpha should be positive and subtle", specGlass.glassSheenAlpha in 8..30)
    }

    @Test
    fun `Corner radius 0dp and 32dp scale accurately and clamp to half of min dimension`() {
        val spec0 = MinimalOverlayView.computeBackdropSpec(
            enabled = true,
            style = "SOLID",
            opacity = 0.65f,
            cornerRadiusDp = 0f,
            horizontalPaddingDp = 16f,
            verticalPaddingDp = 10f,
            density = 2.0f,
            viewWidth = 850,
            viewHeight = 180,
            textLeft = 200f,
            textTop = 50f,
            textRight = 600f,
            textBottom = 110f
        )
        val spec32 = MinimalOverlayView.computeBackdropSpec(
            enabled = true,
            style = "SOLID",
            opacity = 0.65f,
            cornerRadiusDp = 32f,
            horizontalPaddingDp = 16f,
            verticalPaddingDp = 10f,
            density = 2.0f,
            viewWidth = 850,
            viewHeight = 180,
            textLeft = 200f,
            textTop = 50f,
            textRight = 600f,
            textBottom = 110f
        )

        assertNotNull(spec0)
        assertNotNull(spec32)
        assertEquals(0f, spec0!!.cornerRadiusPx, 0.01f)
        // Height is (110 - 50) + 2*(10*2) = 100px -> maxRadius = 50px; 32dp * 2 = 64px clamped to 50px
        assertEquals(50f, spec32!!.cornerRadiusPx, 0.01f)
    }

    @Test
    fun `Horizontal padding 0dp and 32dp and Vertical padding 0dp and 24dp wrap lyric content tightly`() {
        val density = 2.0f
        val specTight = MinimalOverlayView.computeBackdropSpec(
            enabled = true,
            style = "SOLID",
            opacity = 0.65f,
            cornerRadiusDp = 18f,
            horizontalPaddingDp = 0f,
            verticalPaddingDp = 0f,
            density = density,
            viewWidth = 900,
            viewHeight = 240,
            textLeft = 250f,
            textTop = 80f,
            textRight = 650f,
            textBottom = 130f
        )
        val specExpanded = MinimalOverlayView.computeBackdropSpec(
            enabled = true,
            style = "SOLID",
            opacity = 0.65f,
            cornerRadiusDp = 18f,
            horizontalPaddingDp = 32f,
            verticalPaddingDp = 24f,
            density = density,
            viewWidth = 900,
            viewHeight = 240,
            textLeft = 250f,
            textTop = 80f,
            textRight = 650f,
            textBottom = 130f
        )

        assertNotNull(specTight)
        assertNotNull(specExpanded)

        assertEquals(400f, specTight!!.width, 0.01f)
        assertEquals(50f, specTight.height, 0.01f)

        // Expanded by 32dp*2 on left/right (+128px total) and 24dp*2 on top/bottom (+96px total)
        assertEquals(400f + 128f, specExpanded!!.width, 0.01f)
        assertEquals(50f + 96f, specExpanded.height, 0.01f)
    }

    @Test
    fun `Content-wrapping backdrop adapts to single-line vs multiline lyrics without full-window box`() {
        val shortSingleLine = MinimalOverlayView.computeBackdropSpec(
            enabled = true,
            style = "GLASS",
            opacity = 0.65f,
            cornerRadiusDp = 18f,
            horizontalPaddingDp = 16f,
            verticalPaddingDp = 10f,
            density = 2.0f,
            viewWidth = 850,
            viewHeight = 200,
            textLeft = 320f,
            textTop = 80f,
            textRight = 530f,
            textBottom = 120f
        )
        val multiline = MinimalOverlayView.computeBackdropSpec(
            enabled = true,
            style = "GLASS",
            opacity = 0.65f,
            cornerRadiusDp = 18f,
            horizontalPaddingDp = 16f,
            verticalPaddingDp = 10f,
            density = 2.0f,
            viewWidth = 850,
            viewHeight = 200,
            textLeft = 140f,
            textTop = 50f,
            textRight = 710f,
            textBottom = 150f
        )

        assertNotNull(shortSingleLine)
        assertNotNull(multiline)
        assertTrue("Short single-line backdrop must be narrower than multiline backdrop", shortSingleLine!!.width < multiline!!.width)
        assertTrue("Single-line backdrop must be shorter than multiline backdrop", shortSingleLine.height < multiline.height)
        assertTrue("Backdrop must not fill the entire 850px overlay window when text is narrower", multiline.width < 850f)
    }

    @Test
    fun `DataStore persists HUD Backdrop settings and preserves custom values across toggle and style switch`() = runBlocking {
        val testFile = tempFolder.newFile("hud_backdrop_prefs.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { testFile }
        )
        val repository = UserPreferencesRepository(dataStore)

        // 1. Customize all HUD Backdrop values and verify persistence
        repository.setOverlayTextOpacity(0.85f)
        repository.setHudBackdropEnabled(true)
        repository.setHudBackdropStyle(HudBackdropStyle.GLASS)
        repository.setHudBackdropOpacity(0.40f)
        repository.setHudBackdropCornerRadiusDp(26)
        repository.setHudBackdropHorizontalPaddingDp(24)
        repository.setHudBackdropVerticalPaddingDp(16)

        val saved = repository.userPreferencesFlow.first()
        assertTrue(saved.hudBackdropEnabled)
        assertEquals(HudBackdropStyle.GLASS, saved.hudBackdropStyle)
        assertEquals(0.40f, saved.hudBackdropOpacity, 0.001f)
        assertEquals(26, saved.hudBackdropCornerRadiusDp)
        assertEquals(24, saved.hudBackdropHorizontalPaddingDp)
        assertEquals(16, saved.hudBackdropVerticalPaddingDp)
        assertEquals("Changing backdrop opacity must NEVER alter text opacity", 0.85f, saved.overlayTextOpacity, 0.001f)

        // 2. Switch style to SOLID -> slider values must remain intact
        repository.setHudBackdropStyle(HudBackdropStyle.SOLID)
        val afterStyleSwitch = repository.userPreferencesFlow.first()
        assertEquals(HudBackdropStyle.SOLID, afterStyleSwitch.hudBackdropStyle)
        assertEquals(0.40f, afterStyleSwitch.hudBackdropOpacity, 0.001f)
        assertEquals(26, afterStyleSwitch.hudBackdropCornerRadiusDp)
        assertEquals(24, afterStyleSwitch.hudBackdropHorizontalPaddingDp)
        assertEquals(16, afterStyleSwitch.hudBackdropVerticalPaddingDp)

        // 3. Disable and re-enable HUD Backdrop -> custom values must remain intact
        repository.setHudBackdropEnabled(false)
        val disabledState = repository.userPreferencesFlow.first()
        assertFalse(disabledState.hudBackdropEnabled)
        assertEquals(0.40f, disabledState.hudBackdropOpacity, 0.001f)

        repository.setHudBackdropEnabled(true)
        val reEnabledState = repository.userPreferencesFlow.first()
        assertTrue(reEnabledState.hudBackdropEnabled)
        assertEquals(0.40f, reEnabledState.hudBackdropOpacity, 0.001f)
        assertEquals(26, reEnabledState.hudBackdropCornerRadiusDp)
        assertEquals(24, reEnabledState.hudBackdropHorizontalPaddingDp)
        assertEquals(16, reEnabledState.hudBackdropVerticalPaddingDp)
    }

    @Test
    fun `shared Glass backdrop appearance includes inner highlight, elevation shadow, and matches computeBackdropSpec`() {
        val appearance = MinimalOverlayView.computeBackdropAppearance(style = "GLASS", opacity = 0.65f)
        val spec = MinimalOverlayView.computeBackdropSpec(
            enabled = true,
            style = "GLASS",
            opacity = 0.65f,
            cornerRadiusDp = 18f,
            horizontalPaddingDp = 16f,
            verticalPaddingDp = 10f,
            density = 2.0f,
            viewWidth = 850,
            viewHeight = 160,
            textLeft = 200f,
            textTop = 50f,
            textRight = 650f,
            textBottom = 100f
        )

        assertNotNull(spec)
        assertTrue(appearance.isGlass)
        assertEquals(appearance.fillColor, spec!!.fillColor)
        assertEquals(appearance.fillAlpha, spec.fillAlpha)
        assertEquals(appearance.glassSheenAlpha, spec.glassSheenAlpha)
        assertEquals(appearance.borderAlpha, spec.borderAlpha)
        assertEquals(appearance.glassElevationShadowAlpha, spec.glassElevationShadowAlpha)
        assertEquals(appearance.glassInnerHighlightAlpha, spec.glassInnerHighlightAlpha)
        assertTrue("Glass should add subtle depth shadow separation", appearance.glassElevationShadowAlpha > 0)
        assertTrue("Glass should add top inner highlight", appearance.glassInnerHighlightAlpha > 0)

        val frostColors = MinimalOverlayView.buildGlassFrostGradientColors(appearance.glassSheenAlpha)
        val borderColors = MinimalOverlayView.buildGlassBorderGradientColors(appearance.borderAlpha)
        val innerColors = MinimalOverlayView.buildGlassInnerHighlightColors(appearance.glassInnerHighlightAlpha)
        assertEquals(MinimalOverlayView.GLASS_FROST_STOPS.size, frostColors.size)
        assertEquals(MinimalOverlayView.GLASS_BORDER_STOPS.size, borderColors.size)
        assertEquals(MinimalOverlayView.GLASS_INNER_HIGHLIGHT_STOPS.size, innerColors.size)

        val solidAppearance = MinimalOverlayView.computeBackdropAppearance(style = "SOLID", opacity = 0.65f)
        assertFalse(solidAppearance.isGlass)
        assertEquals(0, solidAppearance.glassElevationShadowAlpha)
        assertEquals(0, solidAppearance.glassInnerHighlightAlpha)
    }

    @Test
    fun `lyric color hierarchy keeps active lyrics crisp white and subordinate words clearly readable`() {
        val fullOpacity = MinimalOverlayView.computeLyricColorHierarchy(
            textOpacity = 1.0f,
            animProgress = 1.0f,
            isFadeStyle = false
        )
        assertEquals(MinimalOverlayView.ACTIVE_LYRIC_COLOR, fullOpacity.activeColor)
        assertEquals(MinimalOverlayView.INACTIVE_LYRIC_COLOR, fullOpacity.inactiveColor)
        assertEquals(255, fullOpacity.activeAlpha)
        assertTrue(
            "Subordinate words must exceed 55% visibility at full text opacity so they never look muddy",
            fullOpacity.inactiveAlpha >= 150
        )
        assertTrue(
            "Active lyrics must remain noticeably brighter than subordinate words",
            fullOpacity.activeAlpha > fullOpacity.inactiveAlpha
        )
        assertTrue(
            "Subordinate shadow alpha must stay lighter than active shadow alpha",
            MinimalOverlayView.INACTIVE_SHADOW_ALPHA < MinimalOverlayView.ACTIVE_SHADOW_ALPHA
        )

        val halfOpacity = MinimalOverlayView.computeLyricColorHierarchy(
            textOpacity = 0.5f,
            animProgress = 1.0f,
            isFadeStyle = false
        )
        assertTrue("User text opacity preference must scale both active and subordinate alpha proportionally", halfOpacity.activeAlpha in 126..129)
        assertTrue(halfOpacity.inactiveAlpha < fullOpacity.inactiveAlpha)
        assertTrue(halfOpacity.activeAlpha > halfOpacity.inactiveAlpha)

        val midTransitionSlide = MinimalOverlayView.computeLyricColorHierarchy(
            textOpacity = 1.0f,
            animProgress = 0.5f,
            isFadeStyle = false
        )
        val midTransitionFade = MinimalOverlayView.computeLyricColorHierarchy(
            textOpacity = 1.0f,
            animProgress = 0.5f,
            isFadeStyle = true
        )
        assertTrue(midTransitionSlide.outgoingAlpha < midTransitionFade.outgoingAlpha)
    }
}

