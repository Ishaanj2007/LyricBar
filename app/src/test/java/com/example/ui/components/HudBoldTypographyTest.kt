package com.example.ui.components

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.example.data.backup.BackupAppSettingsData
import com.example.data.backup.BackupHudConfigData
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HudBoldTypographyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        MinimalOverlayView.clearTypefaceCache()
    }

    @After
    fun tearDown() {
        MinimalOverlayView.clearTypefaceCache()
    }

    @Test
    fun `bold lyrics preference defaults to false for existing users and backups`() {
        val prefs = UserPreferences()
        assertFalse("HUD bold lyrics must default to false", prefs.hudBoldLyrics)

        val backup = BackupHudConfigData()
        assertFalse("BackupHudConfigData bold lyrics must default to false", backup.hudBoldLyrics)
    }

    @Test
    fun `setHudBoldLyrics persists and restores cleanly across DataStore and backup`() = runBlocking {
        val storeFile = tempFolder.newFile("test_hud_bold_lyrics.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { storeFile }
        )
        val repository = UserPreferencesRepository(dataStore)

        // 1. Default state
        assertFalse(repository.userPreferencesFlow.first().hudBoldLyrics)

        // 2. Enable bold lyrics
        repository.setHudBoldLyrics(true)
        assertTrue(repository.userPreferencesFlow.first().hudBoldLyrics)
        assertEquals(true, dataStore.data.first()[booleanPreferencesKey("hud_bold_lyrics")])

        // 3. Export backup HUD config and verify hudBoldLyrics is captured
        val exportedHudConfig = repository.readBackupHudConfig()
        assertTrue(exportedHudConfig.hudBoldLyrics)

        // 4. Disable bold lyrics locally, then restore backup with bold=true
        repository.setHudBoldLyrics(false)
        assertFalse(repository.userPreferencesFlow.first().hudBoldLyrics)

        repository.applyBackupPreferences(
            appSettings = BackupAppSettingsData(),
            hudConfig = exportedHudConfig
        )
        assertTrue(repository.userPreferencesFlow.first().hudBoldLyrics)

        // 5. Restoring a legacy backup without hudBoldLyrics resets cleanly to false
        repository.applyBackupPreferences(
            appSettings = null,
            hudConfig = BackupHudConfigData()
        )
        assertFalse(repository.userPreferencesFlow.first().hudBoldLyrics)
    }
}
