package com.example.ui.components

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
import kotlin.math.abs
import kotlin.math.roundToInt

class HudBackdropTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `default UserPreferences match Black backdrop and crisp default lyric colour specification`() {
        val prefs = UserPreferences()
        assertFalse("HUD Backdrop must default to OFF", prefs.hudBackdropEnabled)
        assertEquals(HudBackdropStyle.BLACK, prefs.hudBackdropStyle)
        assertEquals(0.65f, prefs.hudBackdropOpacity, 0.001f)
        assertEquals(18, prefs.hudBackdropCornerRadiusDp)
        assertEquals(16, prefs.hudBackdropHorizontalPaddingDp)
        assertEquals(10, prefs.hudBackdropVerticalPaddingDp)
        assertEquals(HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR, prefs.hudActiveLyricColor)
        assertEquals(HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR, prefs.hudInactiveLyricColor)
        assertTrue(prefs.hudCustomColors.isEmpty())
        assertEquals("Text opacity must remain 100% by default", 1.0f, prefs.overlayTextOpacity, 0.001f)
    }

    @Test
    fun `HUD Backdrop OFF returns null render spec while preserving text-only HUD`() {
        val spec = MinimalOverlayView.computeBackdropSpec(
            enabled = false,
            style = "BLACK",
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
    fun `Black and Grey backdrops use pure neutral RGB and single-alpha compositing`() {
        val black30 = MinimalOverlayView.computeBackdropAppearance(style = "BLACK", opacity = 0.30f)
        val black65 = MinimalOverlayView.computeBackdropAppearance(style = "BLACK", opacity = 0.65f)
        val black100 = MinimalOverlayView.computeBackdropAppearance(style = "BLACK", opacity = 1.00f)

        assertEquals("BLACK", black65.style)
        assertEquals(HudColorSystem.BLACK_BACKDROP_COLOR, black65.fillColor)
        assertEquals(77, black30.fillAlpha)
        assertEquals(166, black65.fillAlpha)
        assertEquals(255, black100.fillAlpha)

        val greyZero = MinimalOverlayView.computeBackdropAppearance(style = "GREY", opacity = 0.00f)
        val grey65 = MinimalOverlayView.computeBackdropAppearance(style = "GREY", opacity = 0.65f)
        val grey100 = MinimalOverlayView.computeBackdropAppearance(style = "GREY", opacity = 1.00f)

        assertEquals("GREY", grey65.style)
        assertEquals(HudColorSystem.GREY_BACKDROP_COLOR, grey65.fillColor)
        assertEquals(0, greyZero.fillAlpha)
        assertEquals(166, grey65.fillAlpha)
        assertEquals(255, grey100.fillAlpha)

        // Ensure neutral grey (R == G == B) without blue/brown/muddy tint
        val r = (grey65.fillColor ushr 16) and 0xFF
        val g = (grey65.fillColor ushr 8) and 0xFF
        val b = grey65.fillColor and 0xFF
        assertEquals(r, g)
        assertEquals(g, b)
        assertTrue("Grey backdrop should stay readable medium-light grey", r in 150..210)
    }

    @Test
    fun `Corner radius 0dp and 32dp scale accurately and clamp to half of min dimension`() {
        val spec0 = MinimalOverlayView.computeBackdropSpec(
            enabled = true,
            style = "BLACK",
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
            style = "GREY",
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
        assertEquals(HudColorSystem.GREY_BACKDROP_COLOR, spec32.fillColor)
    }

    @Test
    fun `Horizontal padding 0dp and 32dp and Vertical padding 0dp and 24dp wrap lyric content tightly`() {
        val density = 2.0f
        val specTight = MinimalOverlayView.computeBackdropSpec(
            enabled = true,
            style = "BLACK",
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
            style = "GREY",
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
            style = "BLACK",
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
            style = "GREY",
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
    fun `DataStore persists Black and Grey backdrop styles and independent custom lyric colours`() = runBlocking {
        val testFile = tempFolder.newFile("hud_backdrop_prefs.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { testFile }
        )
        val repository = UserPreferencesRepository(dataStore)

        val customCrimson = 0xFFE11D48.toInt()
        val customCobalt = 0xFF0EA5E9.toInt()
        val customLavender = 0xFFC4B5FD.toInt()
        val customDeepBlue = 0xFF1E40AF.toInt()
        val customMint = 0xFF6EE7B7.toInt()

        repository.setOverlayTextOpacity(0.85f)
        repository.setHudBackdropEnabled(true)
        repository.setHudBackdropStyle(HudBackdropStyle.GREY)
        repository.setHudBackdropOpacity(0.40f)
        repository.setHudBackdropCornerRadiusDp(26)
        repository.setHudBackdropHorizontalPaddingDp(24)
        repository.setHudBackdropVerticalPaddingDp(16)
        repository.setHudActiveLyricColor(customDeepBlue)
        repository.setHudInactiveLyricColor(customLavender)
        repository.saveCustomHudColor(customCrimson)
        repository.saveCustomHudColor(customCobalt)
        // Built-in presets are already in curatedPresets and should not duplicate into custom swatches
        repository.saveCustomHudColor(customMint)

        val saved = repository.userPreferencesFlow.first()
        assertTrue(saved.hudBackdropEnabled)
        assertEquals(HudBackdropStyle.GREY, saved.hudBackdropStyle)
        assertEquals(0.40f, saved.hudBackdropOpacity, 0.001f)
        assertEquals(26, saved.hudBackdropCornerRadiusDp)
        assertEquals(24, saved.hudBackdropHorizontalPaddingDp)
        assertEquals(16, saved.hudBackdropVerticalPaddingDp)
        assertEquals(customDeepBlue, saved.hudActiveLyricColor)
        assertEquals(customLavender, saved.hudInactiveLyricColor)
        assertEquals(listOf(customCobalt, customCrimson), saved.hudCustomColors)
        assertEquals("Changing backdrop opacity must NEVER alter text opacity", 0.85f, saved.overlayTextOpacity, 0.001f)

        // Switching style to BLACK and toggling backdrop OFF/ON must preserve all custom colours & slider values
        repository.setHudBackdropStyle(HudBackdropStyle.BLACK)
        repository.setHudBackdropEnabled(false)
        repository.setHudBackdropEnabled(true)

        val reloadedRepo = UserPreferencesRepository(dataStore)
        val afterRestart = reloadedRepo.userPreferencesFlow.first()
        assertEquals(HudBackdropStyle.BLACK, afterRestart.hudBackdropStyle)
        assertEquals(0.40f, afterRestart.hudBackdropOpacity, 0.001f)
        assertEquals(26, afterRestart.hudBackdropCornerRadiusDp)
        assertEquals(24, afterRestart.hudBackdropHorizontalPaddingDp)
        assertEquals(16, afterRestart.hudBackdropVerticalPaddingDp)
        assertEquals(customDeepBlue, afterRestart.hudActiveLyricColor)
        assertEquals(customLavender, afterRestart.hudInactiveLyricColor)
        assertEquals(listOf(customCobalt, customCrimson), afterRestart.hudCustomColors)
    }

    @Test
    fun `legacy GLASS and SOLID styles migrate idempotently to BLACK without touching opacity or unrelated prefs`() = runBlocking {
        // 1. Legacy GLASS
        val glassFile = tempFolder.newFile("legacy_glass.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val glassStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { glassFile })

        glassStore.edit { prefs ->
            prefs[booleanPreferencesKey("hud_backdrop_enabled")] = true
            prefs[stringPreferencesKey("hud_backdrop_style")] = "GLASS"
            prefs[floatPreferencesKey("hud_backdrop_opacity")] = 0.75f
            prefs[intPreferencesKey("hud_backdrop_corner_radius_dp")] = 22
            prefs[floatPreferencesKey("overlay_text_size")] = 18f
        }

        val glassRepo = UserPreferencesRepository(glassStore)
        glassRepo.migrateLegacyPreferencesIfNeeded()
        // Running migration twice must remain idempotent
        glassRepo.migrateLegacyPreferencesIfNeeded()

        val migratedFromGlass = glassRepo.userPreferencesFlow.first()
        assertEquals(HudBackdropStyle.BLACK, migratedFromGlass.hudBackdropStyle)
        assertTrue(migratedFromGlass.hudBackdropEnabled)
        assertEquals(0.75f, migratedFromGlass.hudBackdropOpacity, 0.001f)
        assertEquals(22, migratedFromGlass.hudBackdropCornerRadiusDp)
        assertEquals(18f, migratedFromGlass.overlayTextSizeSp, 0.001f)

        // 2. Legacy SOLID
        val solidFile = tempFolder.newFile("legacy_solid.preferences_pb")
        val solidStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { solidFile })
        solidStore.edit { prefs ->
            prefs[stringPreferencesKey("hud_backdrop_style")] = "SOLID"
            prefs[floatPreferencesKey("hud_backdrop_opacity")] = 0.55f
        }
        val solidRepo = UserPreferencesRepository(solidStore)
        solidRepo.migrateLegacyPreferencesIfNeeded()

        val migratedFromSolid = solidRepo.userPreferencesFlow.first()
        assertEquals(HudBackdropStyle.BLACK, migratedFromSolid.hudBackdropStyle)
        assertEquals(0.55f, migratedFromSolid.hudBackdropOpacity, 0.001f)

        // 3. Ambiguous / unknown future value falls back safely on read without overwriting on disk
        val unknownFile = tempFolder.newFile("unknown_style.preferences_pb")
        val unknownStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { unknownFile })
        unknownStore.edit { prefs ->
            prefs[stringPreferencesKey("hud_backdrop_style")] = "FUTURE_STYLE"
        }
        val unknownRepo = UserPreferencesRepository(unknownStore)
        unknownRepo.migrateLegacyPreferencesIfNeeded()

        val readPrefs = unknownRepo.userPreferencesFlow.first()
        assertEquals(HudBackdropStyle.BLACK, readPrefs.hudBackdropStyle)
        val rawStored = unknownStore.data.first()[stringPreferencesKey("hud_backdrop_style")]
        assertEquals("Unknown value must remain untouched on disk", "FUTURE_STYLE", rawStored)
    }

    @Test
    fun `hex parsing, opaque color sanitization, and HSV round-trip handle edge and invalid inputs safely`() {
        assertEquals(0xFF1E40AF.toInt(), HudColorSystem.parseHexColorOrNull("#1e40af"))
        assertEquals(0xFF1E40AF.toInt(), HudColorSystem.parseHexColorOrNull("1E40AF"))
        assertEquals(0xFF1E40AF.toInt(), HudColorSystem.parseHexColorOrNull("#FF1E40AF"))
        assertNull("Fully transparent 8-digit hex must be rejected", HudColorSystem.parseHexColorOrNull("#001E40AF"))
        assertNull("Malformed hex must return null", HudColorSystem.parseHexColorOrNull("#XYZ123"))
        assertNull("Short hex must return null", HudColorSystem.parseHexColorOrNull("#FFF"))

        // Transparent alpha in sanitizeOpaqueColor falls back safely
        assertEquals(
            HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR,
            HudColorSystem.sanitizeOpaqueColor(0x00123456, HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR)
        )
        // Semi-transparent color becomes crisp opaque RGB
        assertEquals(
            0xFF123456.toInt(),
            HudColorSystem.sanitizeOpaqueColor(0x80123456.toInt(), HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR)
        )

        // HSV round-trip fidelity
        val samples = listOf(0xFFC4B5FD.toInt(), 0xFF7DD3FC.toInt(), 0xFF1E40AF.toInt(), 0xFF141418.toInt())
        for (sample in samples) {
            val hsv = HudColorSystem.rgbToHsv(sample)
            val roundTripped = HudColorSystem.hsvToArgb(hsv[0], hsv[1], hsv[2])
            val dr = kotlin.math.abs(((sample ushr 16) and 0xFF) - ((roundTripped ushr 16) and 0xFF))
            val dg = kotlin.math.abs(((sample ushr 8) and 0xFF) - ((roundTripped ushr 8) and 0xFF))
            val db = kotlin.math.abs((sample and 0xFF) - (roundTripped and 0xFF))
            assertTrue("HSV round-trip error must be <= 2 per channel", dr <= 2 && dg <= 2 && db <= 2)
        }
    }

    @Test
    fun `lyric color hierarchy applies 0_64f inactive ratio and preserves custom active and inactive colours cleanly`() {
        val customActive = 0xFF7DD3FC.toInt()
        val customInactive = 0xFFD4D8E0.toInt()
        val fullOpacity = MinimalOverlayView.computeLyricColorHierarchy(
            activeColor = customActive,
            inactiveColor = customInactive,
            textOpacity = 1.0f,
            animProgress = 1.0f,
            isFadeStyle = false
        )
        val expectedFullInactive = (255 * MinimalOverlayView.INACTIVE_LYRIC_ALPHA_RATIO).roundToInt()
        assertEquals(customActive, fullOpacity.activeColor)
        assertEquals(customInactive, fullOpacity.inactiveColor)
        assertEquals(255, fullOpacity.activeAlpha)
        assertEquals(expectedFullInactive, fullOpacity.inactiveAlpha)
        assertTrue(
            "Active lyric must have strictly higher opacity than inactive lyric",
            fullOpacity.activeAlpha > fullOpacity.inactiveAlpha
        )

        // Semi-transparent custom inputs must normalize to opaque 0xFFRRGGBB so Paint.alpha is never multiplied twice
        val semiTransparentActive = 0x80C4B5FD.toInt()
        val semiTransparentInactive = 0x40A1A6B0.toInt()
        val halfOpacity = MinimalOverlayView.computeLyricColorHierarchy(
            activeColor = semiTransparentActive,
            inactiveColor = semiTransparentInactive,
            textOpacity = 0.5f,
            animProgress = 1.0f,
            isFadeStyle = false
        )
        assertEquals(0xFFC4B5FD.toInt(), halfOpacity.activeColor)
        assertEquals(0xFFA1A6B0.toInt(), halfOpacity.inactiveColor)
        assertTrue("User text opacity preference must scale alpha proportionally", halfOpacity.activeAlpha in 126..129)
        val expectedHalfInactive = (halfOpacity.activeAlpha * MinimalOverlayView.INACTIVE_LYRIC_ALPHA_RATIO).roundToInt()
        assertEquals(
            "Inactive alpha must apply INACTIVE_LYRIC_ALPHA_RATIO once against activeAlpha without double multiplication",
            expectedHalfInactive,
            halfOpacity.inactiveAlpha
        )

        val minOpacity = MinimalOverlayView.computeLyricColorHierarchy(
            activeColor = customActive,
            inactiveColor = customInactive,
            textOpacity = 0.15f,
            animProgress = 1.0f,
            isFadeStyle = false
        )
        val expectedMinInactive = (minOpacity.activeAlpha * MinimalOverlayView.INACTIVE_LYRIC_ALPHA_RATIO).roundToInt()
        assertEquals(38, minOpacity.activeAlpha)
        assertEquals(expectedMinInactive, minOpacity.inactiveAlpha)
        assertTrue("Inactive lyrics must remain non-zero even at minimum textOpacity", minOpacity.inactiveAlpha > 0)

        val midTransitionSlide = MinimalOverlayView.computeLyricColorHierarchy(
            activeColor = customActive,
            inactiveColor = customInactive,
            textOpacity = 1.0f,
            animProgress = 0.5f,
            isFadeStyle = false
        )
        val expectedMidInactive = (midTransitionSlide.activeAlpha * MinimalOverlayView.INACTIVE_LYRIC_ALPHA_RATIO).roundToInt()
        assertEquals(expectedMidInactive, midTransitionSlide.inactiveAlpha)
        val midTransitionFade = MinimalOverlayView.computeLyricColorHierarchy(
            activeColor = customActive,
            inactiveColor = customInactive,
            textOpacity = 1.0f,
            animProgress = 0.5f,
            isFadeStyle = true
        )
        assertTrue(midTransitionSlide.outgoingAlpha < midTransitionFade.outgoingAlpha)
    }

    @Test
    fun `active and inactive 0_64f hierarchy stays distinct and readable on Black and Grey backdrops across opacities`() {
        val backdropOpacities = listOf(0.0f, 0.30f, 0.65f, 1.0f)
        val darkWallpaperRef = 0xFF18181B.toInt()

        // 1. Black backdrop with default White active & Soft White inactive, plus identical #FFFFFF/#FFFFFF
        val blackColorPairs = listOf(
            HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR to HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR,
            0xFFFFFFFF.toInt() to 0xFFFFFFFF.toInt(),
            0xFFC4B5FD.toInt() to 0xFFA1A6B0.toInt()
        )
        val fullHierarchy = MinimalOverlayView.computeLyricColorHierarchy(
            activeColor = HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR,
            inactiveColor = HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR,
            textOpacity = 1.0f
        )
        assertEquals(255, fullHierarchy.activeAlpha)
        assertEquals(163, fullHierarchy.inactiveAlpha)

        for (backdropOpacity in backdropOpacities) {
            val appearance = MinimalOverlayView.computeBackdropAppearance("BLACK", backdropOpacity)
            val effectiveBg = if (backdropOpacity <= 0f) {
                darkWallpaperRef
            } else {
                HudColorSystem.blendOver(
                    foregroundArgb = appearance.fillColor,
                    alpha = appearance.fillAlpha / 255f,
                    backgroundArgb = darkWallpaperRef
                )
            }
            for ((activeCol, inactiveCol) in blackColorPairs) {
                val hierarchy = MinimalOverlayView.computeLyricColorHierarchy(
                    activeColor = activeCol,
                    inactiveColor = inactiveCol,
                    textOpacity = 1.0f
                )
                val activeComposite = HudColorSystem.blendOver(
                    foregroundArgb = hierarchy.activeColor,
                    alpha = hierarchy.activeAlpha / 255f,
                    backgroundArgb = effectiveBg
                )
                val inactiveComposite = HudColorSystem.blendOver(
                    foregroundArgb = hierarchy.inactiveColor,
                    alpha = hierarchy.inactiveAlpha / 255f,
                    backgroundArgb = effectiveBg
                )
                val activeContrast = HudColorSystem.contrastRatio(activeComposite, effectiveBg)
                val inactiveContrast = HudColorSystem.contrastRatio(inactiveComposite, effectiveBg)
                val separation = HudColorSystem.contrastRatio(activeComposite, inactiveComposite)
                assertTrue(
                    "Active lyric must be strongly readable on Black backdrop (opacity=$backdropOpacity): $activeContrast",
                    activeContrast >= 4.5
                )
                assertTrue(
                    "Inactive lyric at 0.64f must remain readable on Black backdrop (opacity=$backdropOpacity): $inactiveContrast",
                    inactiveContrast >= 2.2
                )
                assertTrue(
                    "Active lyric must stand out from inactive lyric on Black backdrop (opacity=$backdropOpacity): $separation",
                    separation >= 1.5
                )
            }
        }

        // 2. Grey backdrop at low, medium, and full backdrop opacity with recommended & identical dark inks
        val greyColorPairs = listOf(
            0xFF141418.toInt() to 0xFF4A4A50.toInt(),
            0xFF1E40AF.toInt() to 0xFF141418.toInt(),
            0xFF141418.toInt() to 0xFF141418.toInt()
        )
        // Low-opacity Grey backdrop (30%) sits over light/medium UI surfaces where dark ink is intended;
        // default (65%) and full (100%) Grey backdrops must also stay readable over neutral mid-grey surfaces.
        val greyScenarios = listOf(
            0.30f to 0xFFD4D6DC.toInt(),
            0.65f to 0xFF9E9EA4.toInt(),
            1.0f to 0xFF8C8C92.toInt()
        )
        for ((backdropOpacity, underlyingSurface) in greyScenarios) {
            val appearance = MinimalOverlayView.computeBackdropAppearance("GREY", backdropOpacity)
            val effectiveBg = HudColorSystem.blendOver(
                foregroundArgb = appearance.fillColor,
                alpha = appearance.fillAlpha / 255f,
                backgroundArgb = underlyingSurface
            )
            for ((activeCol, inactiveCol) in greyColorPairs) {
                val hierarchy = MinimalOverlayView.computeLyricColorHierarchy(
                    activeColor = activeCol,
                    inactiveColor = inactiveCol,
                    textOpacity = 1.0f
                )
                val activeComposite = HudColorSystem.blendOver(
                    foregroundArgb = hierarchy.activeColor,
                    alpha = hierarchy.activeAlpha / 255f,
                    backgroundArgb = effectiveBg
                )
                val inactiveComposite = HudColorSystem.blendOver(
                    foregroundArgb = hierarchy.inactiveColor,
                    alpha = hierarchy.inactiveAlpha / 255f,
                    backgroundArgb = effectiveBg
                )
                val activeContrast = HudColorSystem.contrastRatio(activeComposite, effectiveBg)
                val inactiveContrast = HudColorSystem.contrastRatio(inactiveComposite, effectiveBg)
                val separationContrast = HudColorSystem.contrastRatio(activeComposite, inactiveComposite)
                val rgbDelta = abs(((activeComposite ushr 16) and 0xFF) - ((inactiveComposite ushr 16) and 0xFF)) +
                    abs(((activeComposite ushr 8) and 0xFF) - ((inactiveComposite ushr 8) and 0xFF)) +
                    abs((activeComposite and 0xFF) - (inactiveComposite and 0xFF))
                assertTrue(
                    "Active lyric must stay clearly readable on Grey backdrop (opacity=$backdropOpacity): $activeContrast",
                    activeContrast >= 3.5
                )
                assertTrue(
                    "Active lyric contrast ($activeContrast) must exceed inactive contrast ($inactiveContrast) on Grey backdrop",
                    activeContrast > inactiveContrast
                )
                assertTrue(
                    "Inactive lyric at 0.64f must stay legible on Grey backdrop (opacity=$backdropOpacity): $inactiveContrast",
                    inactiveContrast >= 2.2
                )
                assertTrue(
                    "Active and inactive lyrics must remain visually distinguishable on Grey backdrop (contrast=$separationContrast, rgbDelta=$rgbDelta)",
                    separationContrast >= 1.35 || rgbDelta >= 45
                )
            }
        }

        // 3. Partial user textOpacity preserves the 0.64f active-to-inactive separation on both backdrops
        for (textOpacity in listOf(0.85f, 0.60f)) {
            val h = MinimalOverlayView.computeLyricColorHierarchy(
                activeColor = HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR,
                inactiveColor = HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR,
                textOpacity = textOpacity
            )
            assertEquals(
                (h.activeAlpha * MinimalOverlayView.INACTIVE_LYRIC_ALPHA_RATIO).roundToInt(),
                h.inactiveAlpha
            )
            assertTrue(h.activeAlpha > h.inactiveAlpha)
        }
    }

    @Test
    fun `readability evaluator accepts recommended combinations and emits non-blocking warnings for low contrast`() {
        // 1. Black backdrop + White active + Soft White inactive -> readable (no warning)
        assertNull(
            HudColorSystem.evaluateReadability(
                backdropEnabled = true,
                backdropStyle = HudBackdropStyle.BLACK,
                backdropOpacity = 0.65f,
                activeColor = 0xFFFFFFFF.toInt(),
                inactiveColor = 0xFFD4D8E0.toInt()
            )
        )

        // 2. Black backdrop + Lavender active + Soft Grey inactive -> readable
        assertNull(
            HudColorSystem.evaluateReadability(
                backdropEnabled = true,
                backdropStyle = HudBackdropStyle.BLACK,
                backdropOpacity = 0.65f,
                activeColor = 0xFFC4B5FD.toInt(),
                inactiveColor = 0xFFA1A6B0.toInt()
            )
        )

        // 3. Grey backdrop + Dark active + Medium Grey inactive -> readable
        assertNull(
            HudColorSystem.evaluateReadability(
                backdropEnabled = true,
                backdropStyle = HudBackdropStyle.GREY,
                backdropOpacity = 0.75f,
                activeColor = 0xFF141418.toInt(),
                inactiveColor = 0xFF4A4A50.toInt()
            )
        )

        // 4. Grey backdrop + Deep Blue active + Dark inactive -> readable
        assertNull(
            HudColorSystem.evaluateReadability(
                backdropEnabled = true,
                backdropStyle = HudBackdropStyle.GREY,
                backdropOpacity = 0.75f,
                activeColor = 0xFF1E40AF.toInt(),
                inactiveColor = 0xFF141418.toInt()
            )
        )

        // 5. Low-contrast combinations emit clear non-blocking warning + high-contrast defaults
        val darkOnBlackWarning = HudColorSystem.evaluateReadability(
            backdropEnabled = true,
            backdropStyle = HudBackdropStyle.BLACK,
            backdropOpacity = 0.80f,
            activeColor = 0xFF141418.toInt(),
            inactiveColor = 0xFF4A4A50.toInt()
        )
        assertNotNull("Dark text on Black backdrop should emit contrast warning", darkOnBlackWarning)
        assertEquals(HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR, darkOnBlackWarning!!.suggestedActiveColor)

        val whiteOnGreyWarning = HudColorSystem.evaluateReadability(
            backdropEnabled = true,
            backdropStyle = HudBackdropStyle.GREY,
            backdropOpacity = 0.80f,
            activeColor = 0xFFFFFFFF.toInt(),
            inactiveColor = 0xFFD4D8E0.toInt()
        )
        assertNotNull("Light text on solid Grey backdrop should emit contrast warning", whiteOnGreyWarning)
        assertEquals(0xFF141418.toInt(), whiteOnGreyWarning!!.suggestedActiveColor)
        assertEquals(0xFF4A4A50.toInt(), whiteOnGreyWarning.suggestedInactiveColor)
    }
}
