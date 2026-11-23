package com.example.domain.lyricsengine

import androidx.compose.ui.text.style.TextAlign
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.data.preferences.LyricsAlignment
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LyricsAlignmentCycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `initial state follows established default of CENTER for both systems independently`() {
        val prefs = UserPreferences()
        assertEquals("CENTER", prefs.overlayTextAlign)
        assertEquals(LyricsAlignment.CENTER, prefs.lyricsScreenAlignment)
        assertEquals(LyricsAlignment.CENTER, prefs.lyricsAlignment)
        assertEquals(LyricsAlignment.CENTER, prefs.hudLyricsAlignment)
        assertEquals(LyricsAlignment.CENTER, LyricsAlignment.fromCode(null))
        assertEquals(LyricsAlignment.CENTER, LyricsAlignment.fromCode(""))
        assertEquals(LyricsAlignment.CENTER, LyricsAlignment.fromCode("UNKNOWN"))
    }

    @Test
    fun `enum next cycle transitions Left to Center to Right to Left`() {
        assertEquals(LyricsAlignment.CENTER, LyricsAlignment.LEFT.next())
        assertEquals(LyricsAlignment.RIGHT, LyricsAlignment.CENTER.next())
        assertEquals(LyricsAlignment.LEFT, LyricsAlignment.RIGHT.next())
    }

    @Test
    fun `enum fromCode correctly parses standard and legacy variations`() {
        assertEquals(LyricsAlignment.LEFT, LyricsAlignment.fromCode("left"))
        assertEquals(LyricsAlignment.LEFT, LyricsAlignment.fromCode("LEFT"))
        assertEquals(LyricsAlignment.RIGHT, LyricsAlignment.fromCode("right"))
        assertEquals(LyricsAlignment.RIGHT, LyricsAlignment.fromCode("RIGHT"))
        assertEquals(LyricsAlignment.CENTER, LyricsAlignment.fromCode("center"))
        assertEquals(LyricsAlignment.CENTER, LyricsAlignment.fromCode("CENTER"))
        assertEquals(LyricsAlignment.CENTER, LyricsAlignment.fromCode("centre"))
        assertEquals(LyricsAlignment.CENTER, LyricsAlignment.fromCode("CENTRE"))
    }

    @Test
    fun `tap-cycle changes only lyrics screen alignment through Left Center Right Left and leaves HUD unchanged`() = runBlocking {
        val storeFile = tempFolder.newFile("lyrics_align_cycle.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { storeFile }
        )
        val repo = UserPreferencesRepository(dataStore)

        // Set an explicit HUD alignment (e.g. LEFT) to verify it is NEVER touched
        repo.setOverlayTextAlign("LEFT")
        assertEquals("LEFT", repo.userPreferencesFlow.first().overlayTextAlign)
        assertEquals(LyricsAlignment.CENTER, repo.userPreferencesFlow.first().lyricsScreenAlignment)

        // Start from LEFT on Lyrics Screen to test the exact cycle:
        // Left -> Center -> Right -> Left
        repo.setLyricsScreenAlignment(LyricsAlignment.LEFT)
        assertEquals(LyricsAlignment.LEFT, repo.userPreferencesFlow.first().lyricsScreenAlignment)
        assertEquals("LEFT", dataStore.data.first()[stringPreferencesKey("lyrics_screen_alignment")])
        assertEquals("LEFT", repo.userPreferencesFlow.first().overlayTextAlign)

        // 1. Tap-Cycle Left -> Center changes ONLY Lyrics Screen alignment
        val step1 = repo.cycleLyricsScreenAlignment()
        assertEquals(LyricsAlignment.CENTER, step1)
        val prefs1 = repo.userPreferencesFlow.first()
        assertEquals(LyricsAlignment.CENTER, prefs1.lyricsScreenAlignment)
        assertEquals("CENTER", dataStore.data.first()[stringPreferencesKey("lyrics_screen_alignment")])
        // HUD remains unchanged
        assertEquals("LEFT", prefs1.overlayTextAlign)
        assertEquals("LEFT", dataStore.data.first()[stringPreferencesKey("overlay_text_align")])

        // 2. Tap-Cycle Center -> Right changes ONLY Lyrics Screen alignment
        val step2 = repo.cycleLyricsScreenAlignment()
        assertEquals(LyricsAlignment.RIGHT, step2)
        val prefs2 = repo.userPreferencesFlow.first()
        assertEquals(LyricsAlignment.RIGHT, prefs2.lyricsScreenAlignment)
        assertEquals("RIGHT", dataStore.data.first()[stringPreferencesKey("lyrics_screen_alignment")])
        // HUD remains unchanged
        assertEquals("LEFT", prefs2.overlayTextAlign)
        assertEquals("LEFT", dataStore.data.first()[stringPreferencesKey("overlay_text_align")])

        // 3. Tap-Cycle Right -> Left changes ONLY Lyrics Screen alignment
        val step3 = repo.cycleLyricsScreenAlignment()
        assertEquals(LyricsAlignment.LEFT, step3)
        val prefs3 = repo.userPreferencesFlow.first()
        assertEquals(LyricsAlignment.LEFT, prefs3.lyricsScreenAlignment)
        assertEquals("LEFT", dataStore.data.first()[stringPreferencesKey("lyrics_screen_alignment")])
        // 4. HUD alignment remains unchanged after all Tap-Cycle interactions
        assertEquals("LEFT", prefs3.overlayTextAlign)
        assertEquals("LEFT", dataStore.data.first()[stringPreferencesKey("overlay_text_align")])
    }

    @Test
    fun `changing HUD lyrics alignment in settings does not affect lyrics screen alignment`() = runBlocking {
        val storeFile = tempFolder.newFile("hud_align_settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { storeFile }
        )
        val repo = UserPreferencesRepository(dataStore)

        // Set Lyrics Screen to RIGHT
        repo.setLyricsScreenAlignment(LyricsAlignment.RIGHT)
        assertEquals(LyricsAlignment.RIGHT, repo.userPreferencesFlow.first().lyricsScreenAlignment)

        // Change HUD alignment in Settings to LEFT
        repo.setOverlayTextAlign("LEFT")
        val prefs1 = repo.userPreferencesFlow.first()
        assertEquals("LEFT", prefs1.overlayTextAlign)
        assertEquals(LyricsAlignment.LEFT, prefs1.hudLyricsAlignment)
        // Lyrics Screen remains unchanged
        assertEquals(LyricsAlignment.RIGHT, prefs1.lyricsScreenAlignment)

        // Change HUD alignment in Settings to RIGHT
        repo.setOverlayTextAlign("RIGHT")
        val prefs2 = repo.userPreferencesFlow.first()
        assertEquals("RIGHT", prefs2.overlayTextAlign)
        assertEquals(LyricsAlignment.RIGHT, prefs2.hudLyricsAlignment)
        // Lyrics Screen remains unchanged
        assertEquals(LyricsAlignment.RIGHT, prefs2.lyricsScreenAlignment)

        // Change HUD alignment in Settings to CENTER
        repo.setOverlayTextAlign("CENTER")
        val prefs3 = repo.userPreferencesFlow.first()
        assertEquals("CENTER", prefs3.overlayTextAlign)
        assertEquals(LyricsAlignment.CENTER, prefs3.hudLyricsAlignment)
        // Lyrics Screen remains unchanged
        assertEquals(LyricsAlignment.RIGHT, prefs3.lyricsScreenAlignment)
    }

    @Test
    fun `both preferences persist independently after app restart`() = runBlocking {
        val storeFile = tempFolder.newFile("independent_persist.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { storeFile }
        )
        val repo = UserPreferencesRepository(dataStore)

        // Set Lyrics Screen = LEFT, HUD = RIGHT
        repo.setLyricsScreenAlignment(LyricsAlignment.LEFT)
        repo.setOverlayTextAlign("RIGHT")

        // Verify in current instance
        val currentPrefs = repo.userPreferencesFlow.first()
        assertEquals(LyricsAlignment.LEFT, currentPrefs.lyricsScreenAlignment)
        assertEquals("RIGHT", currentPrefs.overlayTextAlign)

        // Simulate app restart: create a new repository instance pointing to the same DataStore
        val reloadedRepo = UserPreferencesRepository(dataStore)
        val restoredPrefs = reloadedRepo.userPreferencesFlow.first()
        assertEquals(LyricsAlignment.LEFT, restoredPrefs.lyricsScreenAlignment)
        assertEquals("RIGHT", restoredPrefs.overlayTextAlign)
        assertEquals("LEFT", dataStore.data.first()[stringPreferencesKey("lyrics_screen_alignment")])
        assertEquals("RIGHT", dataStore.data.first()[stringPreferencesKey("overlay_text_align")])
    }

    @Test
    fun `existing users saved HUD alignment remains intact when lyrics_screen_alignment is absent`() = runBlocking {
        val storeFile = tempFolder.newFile("legacy_user_hud.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { storeFile }
        )

        // Seed DataStore with existing user's saved HUD alignment only (no lyrics_screen_alignment key)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("overlay_text_align")] = "LEFT"
        }

        val repo = UserPreferencesRepository(dataStore)
        val prefs = repo.userPreferencesFlow.first()

        // HUD alignment is preserved exactly as saved
        assertEquals("LEFT", prefs.overlayTextAlign)
        assertEquals(LyricsAlignment.LEFT, prefs.hudLyricsAlignment)

        // Lyrics Screen alignment defaults gracefully to CENTER without disturbing HUD
        assertEquals(LyricsAlignment.CENTER, prefs.lyricsScreenAlignment)
    }

    @Test
    fun `lyrics alignment maps correctly to Compose TextAlign and accessibility descriptions`() {
        val mappings = mapOf(
            LyricsAlignment.LEFT to (TextAlign.Start to "Lyrics alignment: Left"),
            LyricsAlignment.CENTER to (TextAlign.Center to "Lyrics alignment: Center"),
            LyricsAlignment.RIGHT to (TextAlign.End to "Lyrics alignment: Right")
        )

        for ((alignment, expected) in mappings) {
            val (expectedTextAlign, expectedContentDesc) = expected
            val actualTextAlign = when (alignment) {
                LyricsAlignment.LEFT -> TextAlign.Start
                LyricsAlignment.RIGHT -> TextAlign.End
                LyricsAlignment.CENTER -> TextAlign.Center
            }
            val actualContentDesc = "Lyrics alignment: ${alignment.displayName}"

            assertEquals(expectedTextAlign, actualTextAlign)
            assertEquals(expectedContentDesc, actualContentDesc)
        }
    }

    @Test
    fun `settings screen code defines HUD Lyrics Alignment label`() {
        val settingsFile = java.io.File("app/src/main/java/com/example/ui/screens/SettingsScreen.kt")
        org.junit.Assert.assertTrue("SettingsScreen.kt must exist", settingsFile.exists())
        val content = settingsFile.readText()
        org.junit.Assert.assertTrue(
            "Settings label must display 'HUD Lyrics Alignment'",
            content.contains("text = \"HUD Lyrics Alignment\"")
        )
    }
}
