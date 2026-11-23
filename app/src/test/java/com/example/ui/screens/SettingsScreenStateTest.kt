package com.example.ui.screens

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.data.preferences.HudBackdropStyle
import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsScreenStateTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `all settings accordion sections start closed on initial open and reopen`() {
        val initialOpen = SettingsAccordionState.initialExpandedSections()
        assertTrue("Every Settings section must start closed", initialOpen.isEmpty())
        assertFalse(SettingsAccordionState.SECTION_OVERLAY in initialOpen)
        assertFalse(SettingsAccordionState.SECTION_HUD_BACKDROP in initialOpen)
        assertFalse(SettingsAccordionState.SECTION_THEME in initialOpen)
        assertFalse(SettingsAccordionState.SECTION_TYPOGRAPHY in initialOpen)
        assertFalse(SettingsAccordionState.SECTION_LYRICS_PROVIDER in initialOpen)

        // Simulating user opening sections then leaving Settings and reopening Settings:
        var activeSession = initialOpen
        activeSession = SettingsAccordionState.toggleSection(activeSession, SettingsAccordionState.SECTION_HUD_BACKDROP)
        assertEquals(setOf(SettingsAccordionState.SECTION_HUD_BACKDROP), activeSession)

        val reopenedSession = SettingsAccordionState.initialExpandedSections()
        assertTrue("Reopening Settings must not restore prior expanded sections", reopenedSession.isEmpty())
    }

    @Test
    fun `hud backdrop is registered as a dedicated settings section key`() {
        val keys = SettingsAccordionState.ALL_SECTION_KEYS
        assertEquals(12, keys.size)
        assertEquals(keys.size, keys.distinct().size)
        assertTrue(SettingsAccordionState.SECTION_OVERLAY in keys)
        assertTrue(SettingsAccordionState.SECTION_HUD_BACKDROP in keys)
        assertTrue(SettingsAccordionState.SECTION_BACKUP_RESTORE in keys)
        assertTrue(SettingsAccordionState.SECTION_FEEDBACK in keys)
        assertEquals(
            "HUD Backdrop section should sit right after Floating Overlay & Timeline",
            keys.indexOf(SettingsAccordionState.SECTION_OVERLAY) + 1,
            keys.indexOf(SettingsAccordionState.SECTION_HUD_BACKDROP)
        )
    }

    @Test
    fun `toggling any accordion section is strictly independent`() {
        var expanded = SettingsAccordionState.initialExpandedSections()

        expanded = SettingsAccordionState.toggleSection(expanded, SettingsAccordionState.SECTION_HUD_BACKDROP)
        assertEquals(setOf(SettingsAccordionState.SECTION_HUD_BACKDROP), expanded)
        assertFalse(SettingsAccordionState.SECTION_OVERLAY in expanded)
        assertFalse(SettingsAccordionState.SECTION_TYPOGRAPHY in expanded)

        expanded = SettingsAccordionState.toggleSection(expanded, SettingsAccordionState.SECTION_TYPOGRAPHY)
        assertEquals(
            setOf(SettingsAccordionState.SECTION_HUD_BACKDROP, SettingsAccordionState.SECTION_TYPOGRAPHY),
            expanded
        )

        expanded = SettingsAccordionState.toggleSection(expanded, SettingsAccordionState.SECTION_HUD_BACKDROP)
        assertEquals(setOf(SettingsAccordionState.SECTION_TYPOGRAPHY), expanded)
    }

    @Test
    fun `expand all and collapse all toggle cleanly across all sections including hud backdrop`() {
        var expanded = SettingsAccordionState.initialExpandedSections()
        assertFalse(SettingsAccordionState.isAllExpanded(expanded))

        expanded = SettingsAccordionState.toggleAllSections(expanded)
        assertTrue(SettingsAccordionState.isAllExpanded(expanded))
        assertEquals(SettingsAccordionState.ALL_SECTION_KEYS.toSet(), expanded)

        expanded = SettingsAccordionState.toggleAllSections(expanded)
        assertFalse(SettingsAccordionState.isAllExpanded(expanded))
        assertTrue(expanded.isEmpty())
    }

    @Test
    fun `dedicated hud backdrop controls preserve existing DataStore preference keys and values`() = runBlocking {
        val storeFile = tempFolder.newFile("settings_batch1_backdrop.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { storeFile }
        )
        val repo = UserPreferencesRepository(dataStore)

        repo.setHudBackdropEnabled(true)
        repo.setHudBackdropStyle(HudBackdropStyle.GREY)
        repo.setHudBackdropOpacity(0.50f)
        repo.setHudBackdropCornerRadiusDp(24)
        repo.setHudBackdropHorizontalPaddingDp(20)
        repo.setHudBackdropVerticalPaddingDp(14)

        val prefs = repo.userPreferencesFlow.first()
        assertTrue(prefs.hudBackdropEnabled)
        assertEquals(HudBackdropStyle.GREY, prefs.hudBackdropStyle)
        assertEquals(0.50f, prefs.hudBackdropOpacity, 0.001f)
        assertEquals(24, prefs.hudBackdropCornerRadiusDp)
        assertEquals(20, prefs.hudBackdropHorizontalPaddingDp)
        assertEquals(14, prefs.hudBackdropVerticalPaddingDp)

        val raw = dataStore.data.first()
        assertEquals(true, raw[booleanPreferencesKey("hud_backdrop_enabled")])
        assertEquals("GREY", raw[stringPreferencesKey("hud_backdrop_style")])
        assertEquals(0.50f, raw[floatPreferencesKey("hud_backdrop_opacity")] ?: 0f, 0.001f)
        assertEquals(24, raw[intPreferencesKey("hud_backdrop_corner_radius_dp")])
        assertEquals(20, raw[intPreferencesKey("hud_backdrop_horizontal_padding_dp")])
        assertEquals(14, raw[intPreferencesKey("hud_backdrop_vertical_padding_dp")])
    }
}
