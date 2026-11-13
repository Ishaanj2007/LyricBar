package com.example.service

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
import kotlin.math.roundToInt

class HudOrientationPositioningTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun geometryDp(
        widthDp: Int,
        heightDp: Int,
        density: Float,
        cutoutLeftDp: Int = 0,
        cutoutTopDp: Int = 0,
        cutoutRightDp: Int = 0,
        cutoutBottomDp: Int = 0,
        navLeftDp: Int = 0,
        navTopDp: Int = 0,
        navRightDp: Int = 0,
        navBottomDp: Int = 0,
        statusTopDp: Int = 24
    ): HudDisplayGeometry {
        fun px(dp: Int) = (dp * density).roundToInt()
        return HudDisplayGeometry(
            windowWidthPx = px(widthDp),
            windowHeightPx = px(heightDp),
            density = density,
            cutoutLeftPx = px(cutoutLeftDp),
            cutoutTopPx = px(cutoutTopDp),
            cutoutRightPx = px(cutoutRightDp),
            cutoutBottomPx = px(cutoutBottomDp),
            navLeftPx = px(navLeftDp),
            navTopPx = px(navTopDp),
            navRightPx = px(navRightDp),
            navBottomPx = px(navBottomDp),
            statusTopPx = px(statusTopDp)
        )
    }

    @Test
    fun `orientation detection tracks effective window bounds including multi-window resize`() {
        val portrait360 = geometryDp(360, 800, 3.0f)
        val portrait393 = geometryDp(393, 873, 2.625f)
        val landscape640 = geometryDp(640, 360, 2.0f)
        val landscape800 = geometryDp(800, 360, 2.5f)
        val landscape915 = geometryDp(915, 412, 2.625f)
        val squareMultiWindow = geometryDp(500, 500, 2.0f)
        val wideMultiWindow = geometryDp(580, 420, 2.0f)

        assertEquals(HudOrientation.PORTRAIT, portrait360.orientation)
        assertEquals(HudOrientation.PORTRAIT, portrait393.orientation)
        assertEquals(HudOrientation.LANDSCAPE, landscape640.orientation)
        assertEquals(HudOrientation.LANDSCAPE, landscape800.orientation)
        assertEquals(HudOrientation.LANDSCAPE, landscape915.orientation)
        assertEquals(HudOrientation.PORTRAIT, squareMultiWindow.orientation)
        assertEquals(HudOrientation.LANDSCAPE, wideMultiWindow.orientation)
    }

    @Test
    fun `initial landscape profile avoids portrait coordinates, system bars, cutouts, and full width`() {
        val landscapeCases = listOf(
            geometryDp(640, 360, 2.0f, cutoutLeftDp = 28, navRightDp = 48, statusTopDp = 24),
            geometryDp(800, 360, 2.5f, cutoutLeftDp = 32, navBottomDp = 24, statusTopDp = 24),
            geometryDp(915, 412, 2.625f, cutoutRightDp = 36, navLeftDp = 48, statusTopDp = 28),
            geometryDp(1280, 800, 2.0f, cutoutTopDp = 28, navBottomDp = 48, statusTopDp = 28)
        )

        for (geom in landscapeCases) {
            val defaultLandscape = HudPositioningEngine.createDefaultLandscapeProfile(geom)
            val resolved = HudPositioningEngine.resolveBounds(defaultLandscape, geom)

            assertTrue("X must clear left cutout/nav (${geom.minSafeX})", resolved.x >= geom.minSafeX)
            assertTrue(
                "Right edge must clear right cutout/nav (${geom.maxSafeRight})",
                resolved.x + resolved.widthPx <= geom.maxSafeRight
            )
            assertTrue("Y must clear top status/cutout (${geom.safeTopInsetPx})", resolved.y >= geom.safeTopInsetPx)
            assertTrue(
                "Bottom edge must clear bottom nav/cutout (${geom.maxSafeBottom})",
                resolved.y + resolved.effectiveHeightPx <= geom.maxSafeBottom
            )
            assertTrue(
                "Landscape HUD must NOT stretch arbitrarily to full safe width",
                resolved.widthPx < geom.safeWidthPx
            )
            assertTrue("Landscape width must remain readable (>= 200dp)", resolved.widthPx >= (200f * geom.density).roundToInt())
        }
    }

    @Test
    fun `representative portrait and landscape geometries across densities clamp safely within usable bounds`() {
        val densities = listOf(1.0f, 1.5f, 2.0f, 2.625f, 3.0f, 3.5f)
        val portraitSizes = listOf(360 to 800, 393 to 873)
        val landscapeSizes = listOf(640 to 360, 800 to 360, 915 to 412)

        for (density in densities) {
            for ((w, h) in portraitSizes) {
                val geom = geometryDp(w, h, density, navBottomDp = 48, statusTopDp = 28)
                val prefs = UserPreferences(
                    hudPortraitX = 2000,
                    hudPortraitY = 2600,
                    hudPortraitWidth = 850,
                    hudPortraitHeight = 130
                )
                val resolved = HudPositioningEngine.resolveBounds(prefs.portraitProfile(), geom)
                assertTrue(resolved.x >= geom.minSafeX)
                assertTrue(resolved.x + resolved.widthPx <= geom.maxSafeRight)
                assertTrue(resolved.y >= 0)
                assertTrue(resolved.y + resolved.effectiveHeightPx <= geom.maxSafeBottom)
                assertTrue(resolved.widthPx <= geom.safeWidthPx)
            }

            for ((w, h) in landscapeSizes) {
                val geom = geometryDp(w, h, density, cutoutLeftDp = 30, navBottomDp = 24, statusTopDp = 24)
                val profile = HudPositioningEngine.createDefaultLandscapeProfile(geom)
                val resolved = HudPositioningEngine.resolveBounds(profile, geom)
                assertTrue(resolved.x >= geom.minSafeX)
                assertTrue(resolved.x + resolved.widthPx <= geom.maxSafeRight)
                assertTrue(resolved.y >= geom.safeTopInsetPx)
                assertTrue(resolved.y + resolved.effectiveHeightPx <= geom.maxSafeBottom)
            }
        }
    }

    @Test
    fun `portrait status-bar placement is preserved while bottom nav and cutouts are respected`() {
        // Simulates a user who deliberately placed LyricBar in the status-bar area (e.g., X: 242dp, Y: 16dp)
        val density = 3.0f
        val portraitGeom = geometryDp(412, 915, density, navBottomDp = 48, statusTopDp = 32)
        val statusBarPlacement = HudOverlayProfile(
            x = (242f * density).roundToInt(),
            y = (16f * density).roundToInt(),
            widthPx = (150f * density).roundToInt(),
            heightPx = (36f * density).roundToInt()
        )
        val resolved = HudPositioningEngine.resolveBounds(statusBarPlacement, portraitGeom)

        assertEquals(statusBarPlacement.x, resolved.x)
        assertEquals(statusBarPlacement.y, resolved.y)
        assertTrue(resolved.x + resolved.widthPx <= portraitGeom.maxSafeRight)
    }

    @Test
    fun `minBackdropHeightPx with multiline text clamps bottom inside three-button nav bar`() {
        val geom = geometryDp(640, 360, 2.0f, cutoutLeftDp = 24, navBottomDp = 48, statusTopDp = 24)
        val prefs = UserPreferences(
            hudBackdropEnabled = true,
            overlayTextSizeSp = 22f,
            hudBackdropVerticalPaddingDp = 20
        )
        val minBackdropHeightPx = HudPositioningEngine.computeMinBackdropHeightPx(prefs, geom.density)
        // User profile near bottom (would overlap 48dp 3-button nav bar if not clamped)
        val nearBottomProfile = HudOverlayProfile(x = 120, y = 700, widthPx = 560, heightPx = 120)

        val resolved = HudPositioningEngine.resolveBounds(nearBottomProfile, geom, minBackdropHeightPx)
        assertTrue(resolved.effectiveHeightPx >= minBackdropHeightPx)
        assertTrue(
            "Overlay bottom (${resolved.y + resolved.effectiveHeightPx}) must not intrude on nav bar (${geom.maxSafeBottom})",
            resolved.y + resolved.effectiveHeightPx <= geom.maxSafeBottom
        )
    }

    @Test
    fun `portrait and landscape profiles persist independently across repeated rotations and repo recreation`() = runBlocking {
        val testFile = tempFolder.newFile("orientation_profiles.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { testFile })
        val repo1 = UserPreferencesRepository(dataStore)

        // 1. Set portrait profile (user puts HUD on right side of portrait)
        repo1.setOverlayBounds(x = 220, y = 420, widthPx = 760, heightPx = 140, orientation = HudOrientation.PORTRAIT)
        val afterPortrait = repo1.userPreferencesFlow.first()
        assertFalse(afterPortrait.hasLandscapeProfile)
        assertEquals(220, afterPortrait.hudPortraitX)
        assertEquals(420, afterPortrait.hudPortraitY)
        assertEquals(760, afterPortrait.hudPortraitWidth)
        assertEquals(140, afterPortrait.hudPortraitHeight)

        // 2. Rotate to landscape -> initialize default landscape profile once
        val landscapeGeom = geometryDp(800, 360, 2.5f, cutoutLeftDp = 32, navBottomDp = 24, statusTopDp = 24)
        val defaultLandscape = HudPositioningEngine.createDefaultLandscapeProfile(landscapeGeom)
        repo1.ensureLandscapeProfile(defaultLandscape)

        val afterFirstLandscape = repo1.userPreferencesFlow.first()
        assertTrue(afterFirstLandscape.hasLandscapeProfile)
        assertEquals(defaultLandscape, afterFirstLandscape.landscapeProfileOrNull())
        // Portrait must remain untouched
        assertEquals(220, afterFirstLandscape.hudPortraitX)
        assertEquals(420, afterFirstLandscape.hudPortraitY)

        // 3. User customizes landscape position and dimensions
        repo1.setOverlayBounds(x = 980, y = 620, widthPx = 640, heightPx = 160, orientation = HudOrientation.LANDSCAPE)
        val afterCustomLandscape = repo1.userPreferencesFlow.first()
        assertEquals(980, afterCustomLandscape.hudLandscapeX)
        assertEquals(620, afterCustomLandscape.hudLandscapeY)
        assertEquals(640, afterCustomLandscape.hudLandscapeWidth)
        assertEquals(160, afterCustomLandscape.hudLandscapeHeight)
        // Portrait still untouched
        assertEquals(220, afterCustomLandscape.hudPortraitX)
        assertEquals(420, afterCustomLandscape.hudPortraitY)
        assertEquals(760, afterCustomLandscape.hudPortraitWidth)
        assertEquals(140, afterCustomLandscape.hudPortraitHeight)

        // 4. Subsequent rotation calling ensureLandscapeProfile must NOT overwrite custom landscape profile
        repo1.ensureLandscapeProfile(HudOverlayProfile(0, 0, 500, 100))
        val afterSecondRotation = repo1.userPreferencesFlow.first()
        assertEquals(980, afterSecondRotation.hudLandscapeX)
        assertEquals(620, afterSecondRotation.hudLandscapeY)
        assertEquals(640, afterSecondRotation.hudLandscapeWidth)
        assertEquals(160, afterSecondRotation.hudLandscapeHeight)

        // 5. Simulate process / service recreation with a new repository instance over the same DataStore
        val repo2 = UserPreferencesRepository(dataStore)
        val reloaded = repo2.userPreferencesFlow.first()
        assertEquals(HudOverlayProfile(220, 420, 760, 140), reloaded.portraitProfile())
        assertEquals(HudOverlayProfile(980, 620, 640, 160), reloaded.landscapeProfileOrNull())
    }

    @Test
    fun `legacy installations migrate seamlessly into portrait profile without losing values`() = runBlocking {
        val testFile = tempFolder.newFile("legacy_migration.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { testFile })

        // Pre-populate legacy keys ONLY (simulating upgrade from LyricBar v2.2/v2.5 Batch 1)
        dataStore.edit { prefs ->
            prefs[intPreferencesKey("overlay_x_position")] = 145
            prefs[intPreferencesKey("overlay_y_position")] = 510
            prefs[intPreferencesKey("overlay_width")] = 920
            prefs[intPreferencesKey("overlay_height")] = 155
        }

        val repository = UserPreferencesRepository(dataStore)
        val loaded = repository.userPreferencesFlow.first()

        assertEquals(145, loaded.hudPortraitX)
        assertEquals(510, loaded.hudPortraitY)
        assertEquals(920, loaded.hudPortraitWidth)
        assertEquals(155, loaded.hudPortraitHeight)
        assertEquals(145, loaded.overlayXPosition)
        assertEquals(510, loaded.overlayYPosition)
        assertEquals(920, loaded.overlayWidthPx)
        assertEquals(155, loaded.overlayHeightPx)
        assertNull("Legacy install should not pre-populate landscape profile", loaded.landscapeProfileOrNull())

        // Entering landscape initializes landscape independently while keeping legacy portrait intact
        val landscapeGeom = geometryDp(915, 412, 2.625f, cutoutLeftDp = 36, navBottomDp = 24)
        repository.ensureLandscapeProfile(HudPositioningEngine.createDefaultLandscapeProfile(landscapeGeom))

        val afterLandscapeInit = repository.userPreferencesFlow.first()
        assertEquals(145, afterLandscapeInit.hudPortraitX)
        assertEquals(510, afterLandscapeInit.hudPortraitY)
        assertEquals(920, afterLandscapeInit.hudPortraitWidth)
        assertEquals(155, afterLandscapeInit.hudPortraitHeight)
        assertNotNull(afterLandscapeInit.landscapeProfileOrNull())
    }

    @Test
    fun `repeated rapid orientation cycles and nudges keep each profile isolated and bounded`() = runBlocking {
        val testFile = tempFolder.newFile("rapid_rotations.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { testFile })
        val repo = UserPreferencesRepository(dataStore)

        val portraitGeom = geometryDp(393, 873, 2.625f, navBottomDp = 24)
        val landscapeGeom = geometryDp(873, 393, 2.625f, cutoutLeftDp = 32, navBottomDp = 24)

        repo.setOverlayBounds(120, 300, 720, 130, HudOrientation.PORTRAIT)
        val initLandscape = HudPositioningEngine.createDefaultLandscapeProfile(landscapeGeom)
        repo.ensureLandscapeProfile(initLandscape)
        repo.nudgeOverlayPosition(deltaX = 80, deltaY = 40, orientation = HudOrientation.LANDSCAPE)

        val expectedLandscapeX = initLandscape.x + 80
        val expectedLandscapeY = initLandscape.y + 40

        // Simulate 20 rapid rotations P -> L -> P -> L
        repeat(20) { cycle ->
            val currentPrefs = repo.userPreferencesFlow.first()
            val geom = if (cycle % 2 == 0) landscapeGeom else portraitGeom
            val resolved = HudPositioningEngine.resolveBounds(
                profile = currentPrefs.resolveProfile(geom.orientation, geom),
                geometry = geom
            )
            if (geom.orientation == HudOrientation.LANDSCAPE) {
                assertEquals(expectedLandscapeX, resolved.x)
                assertEquals(expectedLandscapeY, resolved.y)
            } else {
                assertEquals(120, resolved.x)
                assertEquals(300, resolved.y)
            }
        }

        val finalPrefs = repo.userPreferencesFlow.first()
        assertEquals(120, finalPrefs.hudPortraitX)
        assertEquals(300, finalPrefs.hudPortraitY)
        assertEquals(expectedLandscapeX, finalPrefs.hudLandscapeX)
        assertEquals(expectedLandscapeY, finalPrefs.hudLandscapeY)
    }

    @Test
    fun `orientation info snapshot reflects active portrait and landscape bounds accurately`() {
        val portraitGeom = geometryDp(393, 873, 2.0f, navBottomDp = 24)
        val portraitBounds = HudPositioningEngine.resolveBounds(
            profile = HudOverlayProfile(x = 40, y = 200, widthPx = 600, heightPx = 120),
            geometry = portraitGeom
        )
        val portraitInfo = HudPositioningEngine.buildOrientationInfoSnapshot(
            orientation = portraitGeom.orientation,
            geometry = portraitGeom,
            resolvedBounds = portraitBounds
        )

        assertEquals(HudOrientation.PORTRAIT, portraitInfo.orientation)
        assertEquals("HUD • Portrait", portraitInfo.chipLabel)
        assertEquals("Portrait HUD Profile", portraitInfo.modeTitle)
        assertEquals("393 × 873 dp", portraitInfo.screenSizeDpLabel)
        assertEquals("300 × 60 dp", portraitInfo.hudSizeDpLabel)
        assertEquals("X: 20 dp • Y: 100 dp", portraitInfo.hudPositionDpLabel)
        assertTrue(portraitInfo.explanation.contains("Portrait"))

        val landscapeGeom = geometryDp(873, 393, 2.0f, cutoutLeftDp = 32, navBottomDp = 24)
        val landscapeProfile = HudPositioningEngine.createDefaultLandscapeProfile(landscapeGeom)
        val landscapeBounds = HudPositioningEngine.resolveBounds(landscapeProfile, landscapeGeom)
        val landscapeInfo = HudPositioningEngine.buildOrientationInfoSnapshot(
            orientation = landscapeGeom.orientation,
            geometry = landscapeGeom,
            resolvedBounds = landscapeBounds
        )

        assertEquals(HudOrientation.LANDSCAPE, landscapeInfo.orientation)
        assertEquals("HUD • Landscape", landscapeInfo.chipLabel)
        assertEquals("Landscape HUD Profile", landscapeInfo.modeTitle)
        assertEquals("873 × 393 dp", landscapeInfo.screenSizeDpLabel)
        assertEquals(
            "${(landscapeBounds.widthPx / 2.0f).roundToInt()} × ${(landscapeBounds.heightPx / 2.0f).roundToInt()} dp",
            landscapeInfo.hudSizeDpLabel
        )
        assertEquals(
            "X: ${(landscapeBounds.x / 2.0f).roundToInt()} dp • Y: ${(landscapeBounds.y / 2.0f).roundToInt()} dp",
            landscapeInfo.hudPositionDpLabel
        )
        assertTrue(landscapeInfo.explanation.contains("Landscape"))
    }
}
