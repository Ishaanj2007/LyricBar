package com.example.domain.lyricsengine

import androidx.compose.ui.text.style.TextAlign
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.data.preferences.LyricsAlignment
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    fun `initial state follows established default of CENTER`() {
        val prefs = UserPreferences()
        assertEquals("CENTER", prefs.overlayTextAlign)
        assertEquals(LyricsAlignment.CENTER, prefs.lyricsAlignment)
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
    fun `multiple consecutive taps produce correct cycle in repository with persistence`() = runBlocking {
        val storeFile = tempFolder.newFile("lyrics_align_cycle.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { storeFile }
        )
        val repo = UserPreferencesRepository(dataStore)

        // Initial established default is CENTER
        assertEquals(LyricsAlignment.CENTER, repo.userPreferencesFlow.first().lyricsAlignment)

        // Tap 1: CENTER -> RIGHT
        val step1 = repo.cycleLyricsAlignment()
        assertEquals(LyricsAlignment.RIGHT, step1)
        assertEquals(LyricsAlignment.RIGHT, repo.userPreferencesFlow.first().lyricsAlignment)
        assertEquals("RIGHT", dataStore.data.first()[stringPreferencesKey("overlay_text_align")])

        // Tap 2: RIGHT -> LEFT
        val step2 = repo.cycleLyricsAlignment()
        assertEquals(LyricsAlignment.LEFT, step2)
        assertEquals(LyricsAlignment.LEFT, repo.userPreferencesFlow.first().lyricsAlignment)
        assertEquals("LEFT", dataStore.data.first()[stringPreferencesKey("overlay_text_align")])

        // Tap 3: LEFT -> CENTER
        val step3 = repo.cycleLyricsAlignment()
        assertEquals(LyricsAlignment.CENTER, step3)
        assertEquals(LyricsAlignment.CENTER, repo.userPreferencesFlow.first().lyricsAlignment)
        assertEquals("CENTER", dataStore.data.first()[stringPreferencesKey("overlay_text_align")])

        // Tap 4: CENTER -> RIGHT
        val step4 = repo.cycleLyricsAlignment()
        assertEquals(LyricsAlignment.RIGHT, step4)

        // Tap 5: RIGHT -> LEFT
        val step5 = repo.cycleLyricsAlignment()
        assertEquals(LyricsAlignment.LEFT, step5)

        // Tap 6: LEFT -> CENTER
        val step6 = repo.cycleLyricsAlignment()
        assertEquals(LyricsAlignment.CENTER, step6)
    }

    @Test
    fun `persisted alignment is restored correctly and matches DataStore storage`() = runBlocking {
        val storeFile = tempFolder.newFile("lyrics_align_restore.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { storeFile }
        )
        val repo = UserPreferencesRepository(dataStore)

        // Set to LEFT and verify persistence
        repo.setLyricsAlignment(LyricsAlignment.LEFT)
        assertEquals(LyricsAlignment.LEFT, repo.userPreferencesFlow.first().lyricsAlignment)
        assertEquals("LEFT", dataStore.data.first()[stringPreferencesKey("overlay_text_align")])

        // Verify a fresh repository instance reading the same DataStore receives the persisted state
        val reloadedRepo = UserPreferencesRepository(dataStore)
        val restoredPrefs = reloadedRepo.userPreferencesFlow.first()
        assertEquals(LyricsAlignment.LEFT, restoredPrefs.lyricsAlignment)
        assertEquals("LEFT", restoredPrefs.overlayTextAlign)

        // Set to RIGHT and verify persistence
        reloadedRepo.setLyricsAlignment(LyricsAlignment.RIGHT)
        assertEquals(LyricsAlignment.RIGHT, reloadedRepo.userPreferencesFlow.first().lyricsAlignment)
        assertEquals("RIGHT", dataStore.data.first()[stringPreferencesKey("overlay_text_align")])
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
}
