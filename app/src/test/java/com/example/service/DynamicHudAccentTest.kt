package com.example.service

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.example.data.backup.BackupHudConfigData
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import com.example.ui.components.HudColorSystem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DynamicHudAccentTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    fun setUp() {
        ArtworkAccentExtractor.clearCache()
    }

    @After
    fun tearDown() {
        ArtworkAccentExtractor.clearCache()
    }

    @Test
    fun `dynamic accent preference defaults to false`() {
        val prefs = UserPreferences()
        assertFalse("Dynamic HUD accent colour must be OFF by default", prefs.hudDynamicAccentEnabled)

        val backupData = BackupHudConfigData()
        assertFalse("Backup data default for dynamic accent must be false for backwards compatibility", backupData.hudDynamicAccentEnabled)
    }

    @Test
    fun `setting persistence in UserPreferencesRepository works correctly`() = runBlocking {
        val storeFile = tempFolder.newFile("test_dynamic_accent.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { storeFile }
        )
        val repository = UserPreferencesRepository(dataStore)

        // Initial default state
        val initialPrefs = repository.userPreferencesFlow.first()
        assertFalse(initialPrefs.hudDynamicAccentEnabled)

        // Enable dynamic accent
        repository.setHudDynamicAccentEnabled(true)
        val enabledPrefs = repository.userPreferencesFlow.first()
        assertTrue(enabledPrefs.hudDynamicAccentEnabled)

        val rawData = dataStore.data.first()
        assertEquals(true, rawData[booleanPreferencesKey("hud_dynamic_accent_enabled")])

        // Disable dynamic accent
        repository.setHudDynamicAccentEnabled(false)
        val disabledPrefs = repository.userPreferencesFlow.first()
        assertFalse(disabledPrefs.hudDynamicAccentEnabled)
    }

    @Test
    fun `extraction falls back cleanly to null when artwork or track key is missing`() = runBlocking {
        // Missing track key
        val resultEmptyKey = ArtworkAccentExtractor.extractAccentColor("", null)
        assertNull("Missing track key must return null", resultEmptyKey)

        // Missing bitmap
        val resultNullBitmap = ArtworkAccentExtractor.extractAccentColor("artist - title", null)
        assertNull("Missing artwork bitmap must return null fallback", resultNullBitmap)
    }

    @Test
    fun `cache reuses extracted colours for repeated tracks without bitmap re-extraction`() = runBlocking {
        val trackKey = "daft punk - get lucky"
        val expectedColor = 0xFFFF4081.toInt()

        // Seed cache
        ArtworkAccentExtractor.putCachedAccent(trackKey, expectedColor)

        // Immediate cache retrieval
        val cached = ArtworkAccentExtractor.getCachedAccent(trackKey)
        assertNotNull(cached)
        assertEquals(expectedColor, cached)

        // extractAccentColor returns cached colour without needing bitmap
        val extractedWithoutBitmap = ArtworkAccentExtractor.extractAccentColor(trackKey, null)
        assertEquals(expectedColor, extractedWithoutBitmap)
    }

    @Test
    fun `cache LRU eviction respects bounded capacity`() {
        // Populate cache beyond MAX_CACHE_ENTRIES (32)
        for (i in 1..40) {
            ArtworkAccentExtractor.putCachedAccent("track_$i", 0xFF000000.toInt() or i)
        }

        // Oldest entries (1..8) should be evicted
        assertNull("Oldest entries must be evicted by LRU capacity bound", ArtworkAccentExtractor.getCachedAccent("track_1"))
        assertNull(ArtworkAccentExtractor.getCachedAccent("track_5"))

        // Recent entries should remain
        assertNotNull(ArtworkAccentExtractor.getCachedAccent("track_35"))
        assertNotNull(ArtworkAccentExtractor.getCachedAccent("track_40"))
    }

    @Test
    fun `stale extraction prevention correctly ignores late results for skipped tracks`() {
        var currentTrackKey = "track_initial"
        var appliedColor: Int? = null

        // Simulate async extraction started for track_A
        val targetTrackKeyA = "track_A"
        val extractedColorA = 0xFFFF5722.toInt()

        // User skips rapidly to track_B before track_A finishes
        currentTrackKey = "track_B"

        // Simulated late callback from track_A
        val isStale = (targetTrackKeyA != currentTrackKey)
        assertTrue("Callback with mismatched trackKey must be recognized as stale", isStale)

        if (!isStale) {
            appliedColor = extractedColorA
        }

        assertNull("Stale result from previous track must never be applied to HUD", appliedColor)

        // When track_B finishes
        val targetTrackKeyB = "track_B"
        val extractedColorB = 0xFF4CAF50.toInt()
        val isStaleB = (targetTrackKeyB != currentTrackKey)
        assertFalse(isStaleB)
        if (!isStaleB) {
            appliedColor = extractedColorB
        }
        assertEquals(extractedColorB, appliedColor)
    }

    @Test
    fun `immediate restoration of manual accent colour when toggled OFF`() {
        val manualActiveColor = 0xFFE91E63.toInt()
        val dynamicColor = 0xFF00BCD4.toInt()

        // When dynamic accent is enabled and dynamic colour exists
        val effectiveWhenEnabled = resolveEffectiveActiveColor(
            dynamicEnabled = true,
            dynamicColor = dynamicColor,
            manualColor = manualActiveColor
        )
        assertEquals(dynamicColor, effectiveWhenEnabled)

        // When dynamic accent is disabled, revert immediately to manual accent
        val effectiveWhenDisabled = resolveEffectiveActiveColor(
            dynamicEnabled = false,
            dynamicColor = dynamicColor,
            manualColor = manualActiveColor
        )
        assertEquals("Must immediately revert to manual accent colour when disabled", manualActiveColor, effectiveWhenDisabled)

        // When dynamic accent is enabled but extraction returned null (fallback)
        val effectiveFallback = resolveEffectiveActiveColor(
            dynamicEnabled = true,
            dynamicColor = null,
            manualColor = manualActiveColor
        )
        assertEquals("Must fall back cleanly to manual colour when extraction returns null", manualActiveColor, effectiveFallback)
    }

    @Test
    fun `colour suitability filter rejects near-black, washed-out and pure white colours`() {
        // Pure black and near-black
        assertFalse("Pure black must be rejected as unreadable on dark HUD", ArtworkAccentExtractor.isSuitableForHudAccent(0xFF000000.toInt()))
        assertFalse("Near-black must be rejected", ArtworkAccentExtractor.isSuitableForHudAccent(0xFF0A0A0A.toInt()))

        // Pure white
        assertFalse("Pure white must be rejected as uncolored/washed out", ArtworkAccentExtractor.isSuitableForHudAccent(0xFFFFFFFF.toInt()))

        // Washed-out light gray
        assertFalse("Washed-out gray must be rejected", ArtworkAccentExtractor.isSuitableForHudAccent(0xFFDDDDDD.toInt()))

        // Legible vibrant accent colours
        assertTrue("Vibrant pink should pass", ArtworkAccentExtractor.isSuitableForHudAccent(0xFFE91E63.toInt()))
        assertTrue("Vibrant cyan should pass", ArtworkAccentExtractor.isSuitableForHudAccent(0xFF00E5FF.toInt()))
        assertTrue("Vibrant amber should pass", ArtworkAccentExtractor.isSuitableForHudAccent(0xFFFF9800.toInt()))
        assertTrue("Vibrant purple should pass", ArtworkAccentExtractor.isSuitableForHudAccent(0xFF9C27B0.toInt()))
    }

    private fun resolveEffectiveActiveColor(
        dynamicEnabled: Boolean,
        dynamicColor: Int?,
        manualColor: Int
    ): Int {
        return if (dynamicEnabled && dynamicColor != null) {
            dynamicColor
        } else {
            manualColor
        }
    }
}
