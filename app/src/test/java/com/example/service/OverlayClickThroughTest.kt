package com.example.service

import android.view.WindowManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OverlayClickThroughTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `clickThroughOverlay defaults to false on fresh preferences`() = runBlocking {
        assertFalse(UserPreferences().clickThroughOverlay)

        val file = tempFolder.newFile("fresh_click_through.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { file }
        )
        val repo = UserPreferencesRepository(dataStore)

        val prefs = repo.userPreferencesFlow.first()
        assertFalse("Fresh install must start with click-through OFF", prefs.clickThroughOverlay)
    }

    @Test
    fun `existing installation without clickThrough key defaults to false and keeps existing HUD settings`() = runBlocking {
        val file = tempFolder.newFile("legacy_prefs_click_through.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { file }
        )
        dataStore.edit { prefs ->
            prefs[booleanPreferencesKey("overlay_enabled")] = true
            prefs[booleanPreferencesKey("hud_backdrop_enabled")] = true
            prefs[stringPreferencesKey("hud_backdrop_style")] = HudBackdropStyle.GREY.name
            prefs[intPreferencesKey("hud_portrait_x")] = 140
            prefs[intPreferencesKey("hud_portrait_y")] = 380
            prefs[intPreferencesKey("hud_portrait_width")] = 780
            prefs[intPreferencesKey("hud_portrait_height")] = 150
            prefs[booleanPreferencesKey("is_drag_mode")] = true
        }

        val repo = UserPreferencesRepository(dataStore)
        val loaded = repo.userPreferencesFlow.first()

        assertFalse("Existing install without new key must default to OFF", loaded.clickThroughOverlay)
        assertTrue(loaded.overlayEnabled)
        assertTrue(loaded.hudBackdropEnabled)
        assertEquals(HudBackdropStyle.GREY, loaded.hudBackdropStyle)
        assertEquals(140, loaded.hudPortraitX)
        assertEquals(380, loaded.hudPortraitY)
        assertEquals(780, loaded.hudPortraitWidth)
        assertEquals(150, loaded.hudPortraitHeight)
        assertTrue(loaded.isDragRepositionMode)
    }

    @Test
    fun `clickThroughOverlay persists across repository restarts without altering HUD bounds`() = runBlocking {
        val file = tempFolder.newFile("persisted_click_through.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore1 = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val repo1 = UserPreferencesRepository(dataStore1)

        repo1.setOverlayBounds(
            x = 210,
            y = 460,
            widthPx = 740,
            heightPx = 145,
            orientation = HudOrientation.PORTRAIT
        )
        repo1.setHudBackdropEnabled(true)
        repo1.setDragRepositionMode(true)
        repo1.setClickThroughOverlay(true)

        val enabledPrefs = repo1.userPreferencesFlow.first()
        assertTrue(enabledPrefs.clickThroughOverlay)
        assertEquals(210, enabledPrefs.hudPortraitX)
        assertEquals(460, enabledPrefs.hudPortraitY)
        assertTrue(enabledPrefs.isDragRepositionMode)

        // Simulate app/service restart reading same persisted DataStore file
        val raw = dataStore1.data.first()
        assertEquals(true, raw[booleanPreferencesKey("click_through_overlay")])

        repo1.setClickThroughOverlay(false)
        val disabledPrefs = repo1.userPreferencesFlow.first()
        assertFalse(disabledPrefs.clickThroughOverlay)
        assertEquals(210, disabledPrefs.hudPortraitX)
        assertEquals(460, disabledPrefs.hudPortraitY)
        assertTrue("Stored drag preference remains intact when click-through turns OFF", disabledPrefs.isDragRepositionMode)
    }

    @Test
    fun `computeOverlayWindowFlags adds and clears FLAG_NOT_TOUCHABLE while preserving base flags`() {
        val requiredBaseFlags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        val touchableFlags = OverlayService.computeOverlayWindowFlags(clickThroughEnabled = false)
        assertEquals(requiredBaseFlags, touchableFlags and requiredBaseFlags)
        assertEquals(
            "Touch-through flag must NOT be set when click-through is OFF",
            0,
            touchableFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )

        val passThroughFlags = OverlayService.computeOverlayWindowFlags(clickThroughEnabled = true)
        assertEquals(requiredBaseFlags, passThroughFlags and requiredBaseFlags)
        assertNotEquals(
            "FLAG_NOT_TOUCHABLE must be set when click-through is ON",
            0,
            passThroughFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )

        // Turning click-through back OFF cleanly strips FLAG_NOT_TOUCHABLE and restores exact touchable flags
        val restoredFlags = OverlayService.computeOverlayWindowFlags(clickThroughEnabled = false)
        assertEquals(touchableFlags, restoredFlags)
    }

    @Test
    fun `resolveEffectiveDragMode pauses drag mode while clickThrough is enabled and restores on disable`() {
        assertFalse(OverlayService.resolveEffectiveDragMode(dragModeRequested = true, clickThroughEnabled = true))
        assertFalse(OverlayService.resolveEffectiveDragMode(dragModeRequested = false, clickThroughEnabled = true))
        assertTrue(OverlayService.resolveEffectiveDragMode(dragModeRequested = true, clickThroughEnabled = false))
        assertFalse(OverlayService.resolveEffectiveDragMode(dragModeRequested = false, clickThroughEnabled = false))
    }
}
