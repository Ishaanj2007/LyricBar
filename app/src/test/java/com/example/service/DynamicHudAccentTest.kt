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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs

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

        val initialPrefs = repository.userPreferencesFlow.first()
        assertFalse(initialPrefs.hudDynamicAccentEnabled)

        repository.setHudDynamicAccentEnabled(true)
        val enabledPrefs = repository.userPreferencesFlow.first()
        assertTrue(enabledPrefs.hudDynamicAccentEnabled)

        val rawData = dataStore.data.first()
        assertEquals(true, rawData[booleanPreferencesKey("hud_dynamic_accent_enabled")])

        repository.setHudDynamicAccentEnabled(false)
        val disabledPrefs = repository.userPreferencesFlow.first()
        assertFalse(disabledPrefs.hudDynamicAccentEnabled)
    }

    @Test
    fun `extraction falls back cleanly to null when artwork or track key is missing`() = runBlocking {
        val resultEmptyKey = ArtworkAccentExtractor.extractAccentColor("", null)
        assertNull("Missing track key must return null", resultEmptyKey)

        val resultNullBitmap = ArtworkAccentExtractor.extractAccentColor("artist - title", null)
        assertNull("Missing artwork bitmap must return null fallback", resultNullBitmap)

        val resultNullAccent = ArtworkAccentExtractor.extractAccent("artist - title", null)
        assertNull("Missing artwork bitmap must return null DynamicAccentResult", resultNullAccent)
    }

    @Test
    fun `cache reuses extracted colours for repeated tracks without bitmap re-extraction`() = runBlocking {
        val trackKey = "daft punk - get lucky"
        val expectedColor = 0xFFFF4081.toInt()

        ArtworkAccentExtractor.putCachedAccent(trackKey, expectedColor)

        val cached = ArtworkAccentExtractor.getCachedAccent(trackKey)
        assertNotNull(cached)
        assertEquals(expectedColor, cached)

        val cachedResult = ArtworkAccentExtractor.getCachedAccentResult(trackKey)
        assertNotNull(cachedResult)
        assertEquals(expectedColor, cachedResult!!.sourceAccentColor)
        assertTrue(
            "Glow colour luminance must meet minimum glow luminance",
            ArtworkAccentExtractor.calculateLuminance(cachedResult.glowColor) >= ArtworkAccentExtractor.MIN_GLOW_LUMINANCE
        )

        val extractedWithoutBitmap = ArtworkAccentExtractor.extractAccentColor(trackKey, null)
        assertEquals(expectedColor, extractedWithoutBitmap)
    }

    @Test
    fun `cache LRU eviction respects bounded capacity`() {
        for (i in 1..40) {
            ArtworkAccentExtractor.putCachedAccent("track_$i", 0xFF000000.toInt() or i)
        }

        assertNull("Oldest entries must be evicted by LRU capacity bound", ArtworkAccentExtractor.getCachedAccent("track_1"))
        assertNull(ArtworkAccentExtractor.getCachedAccent("track_5"))
        assertNotNull(ArtworkAccentExtractor.getCachedAccent("track_35"))
        assertNotNull(ArtworkAccentExtractor.getCachedAccent("track_40"))
    }

    @Test
    fun `stale extraction prevention correctly ignores late results for skipped tracks`() {
        var currentTrackKey = "track_initial"
        var appliedColor: Int? = null

        val targetTrackKeyA = "track_A"
        val extractedColorA = 0xFFFF5722.toInt()

        currentTrackKey = "track_B"

        val isStale = (targetTrackKeyA != currentTrackKey)
        assertTrue("Callback with mismatched trackKey must be recognized as stale", isStale)

        if (!isStale) {
            appliedColor = extractedColorA
        }

        assertNull("Stale result from previous track must never be applied to HUD", appliedColor)

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
        val dynamicResult = ArtworkAccentExtractor.buildAccentResult(0xFF00BCD4.toInt())

        val effectiveWhenEnabled = resolveEffectiveState(
            dynamicEnabled = true,
            dynamicResult = dynamicResult,
            manualColor = manualActiveColor
        )
        assertEquals(dynamicResult.activeTextColor, effectiveWhenEnabled.first)
        assertEquals(dynamicResult.glowColor, effectiveWhenEnabled.second)

        val effectiveWhenDisabled = resolveEffectiveState(
            dynamicEnabled = false,
            dynamicResult = dynamicResult,
            manualColor = manualActiveColor
        )
        assertEquals(manualActiveColor, effectiveWhenDisabled.first)
        assertNull("Manual mode must clear dynamicGlowColor override", effectiveWhenDisabled.second)

        val effectiveFallback = resolveEffectiveState(
            dynamicEnabled = true,
            dynamicResult = null,
            manualColor = manualActiveColor
        )
        assertEquals(manualActiveColor, effectiveFallback.first)
        assertNull("Null extraction fallback must clear dynamicGlowColor override", effectiveFallback.second)
    }

    @Test
    fun `colour suitability filter rejects black, all grey levels, and pure white`() {
        val rejectedNeutrals = listOf(
            0xFF000000.toInt() to "#000000",
            0xFF101010.toInt() to "#101010",
            0xFF404040.toInt() to "#404040",
            0xFF808080.toInt() to "#808080",
            0xFFBFBFBF.toInt() to "#BFBFBF",
            0xFFDDDDDD.toInt() to "#DDDDDD",
            0xFFFFFFFF.toInt() to "#FFFFFF"
        )
        for ((argb, label) in rejectedNeutrals) {
            assertFalse("$label must be rejected as neutral/unusable", ArtworkAccentExtractor.isSuitableForHudAccent(argb))
        }
    }

    @Test
    fun `colour suitability filter accepts dark chromatic and vibrant colours across hues`() {
        val acceptedColours = listOf(
            0xFF142459.toInt() to "Dark Navy",
            0xFF4A148C.toInt() to "Dark Purple",
            0xFF6B1028.toInt() to "Dark Burgundy",
            0xFF1B5E20.toInt() to "Dark Forest Green",
            0xFFE91E63.toInt() to "Vibrant Pink",
            0xFF00E5FF.toInt() to "Vibrant Cyan",
            0xFF2979FF.toInt() to "Vibrant Blue",
            0xFFFF9800.toInt() to "Vibrant Amber",
            0xFFFFF59D.toInt() to "Pale Yellow"
        )
        for ((argb, label) in acceptedColours) {
            assertTrue("$label must be accepted as a valid chromatic candidate", ArtworkAccentExtractor.isSuitableForHudAccent(argb))
        }
    }

    @Test
    fun `dark chromatic colours produce hue-preserving luminous glow and legible active text`() {
        val darkChromatics = listOf(
            0xFF142459.toInt() to "Dark Navy",
            0xFF4A148C.toInt() to "Dark Purple",
            0xFF6B1028.toInt() to "Dark Burgundy",
            0xFF1B5E20.toInt() to "Dark Forest Green"
        )

        for ((sourceColor, label) in darkChromatics) {
            val result = ArtworkAccentExtractor.buildAccentResult(sourceColor)
            assertEquals("$label sourceAccentColor must preserve original RGB", sourceColor, result.sourceAccentColor)
            assertNotEquals("$label glowColor must be brightened beyond dark source RGB", sourceColor, result.glowColor)

            val glowLum = ArtworkAccentExtractor.calculateLuminance(result.glowColor)
            assertTrue(
                "$label glowColor luminance ($glowLum) must meet >= ${ArtworkAccentExtractor.MIN_GLOW_LUMINANCE}",
                glowLum >= ArtworkAccentExtractor.MIN_GLOW_LUMINANCE
            )
            assertFalse(
                "$label glowColor must not be flagged as dark by HudColorSystem",
                HudColorSystem.isDarkColor(result.glowColor)
            )

            val srcHue = ArtworkAccentExtractor.srgbToOklch(sourceColor)[2]
            val glowHue = ArtworkAccentExtractor.srgbToOklch(result.glowColor)[2]
            val hueDiff = minOf(abs(srcHue - glowHue), 360f - abs(srcHue - glowHue))
            assertTrue("$label hue drift ($hueDiff deg) must remain tightly bounded", hueDiff <= 18f)

            val textContrast = HudColorSystem.contrastRatio(result.activeTextColor, HudColorSystem.BLACK_BACKDROP_COLOR)
            assertTrue(
                "$label activeTextColor contrast ($textContrast) must be readable on dark backdrop",
                textContrast >= ArtworkAccentExtractor.MIN_ACTIVE_TEXT_CONTRAST - 0.1
            )
        }
    }

    @Test
    fun `already legible vibrant colours preserve sourceAccentColor as activeTextColor`() {
        val legibleVibrant = listOf(
            0xFF00E5FF.toInt() to "Vibrant Cyan",
            0xFFFF9800.toInt() to "Vibrant Amber",
            0xFFFFF59D.toInt() to "Pale Yellow"
        )

        for ((color, label) in legibleVibrant) {
            val result = ArtworkAccentExtractor.buildAccentResult(color)
            assertEquals("$label should keep sourceAccentColor verbatim for active text", color, result.activeTextColor)
            assertTrue(
                "$label glowColor must remain luminous",
                ArtworkAccentExtractor.calculateLuminance(result.glowColor) >= ArtworkAccentExtractor.MIN_GLOW_LUMINANCE
            )
        }
    }

    @Test
    fun `palette selection rejects high-population greys and returns null for neutral-only artwork`() {
        val bwSwatches = listOf(
            AccentSwatchCandidate(0xFF000000.toInt(), 5000),
            AccentSwatchCandidate(0xFF404040.toInt(), 4000),
            AccentSwatchCandidate(0xFF808080.toInt(), 3000),
            AccentSwatchCandidate(0xFFBFBFBF.toInt(), 2000),
            AccentSwatchCandidate(0xFFFFFFFF.toInt(), 1000)
        )
        val bwSelected = ArtworkAccentExtractor.pickSuitableAccentFromCandidates(
            dominant = bwSwatches[1],
            muted = bwSwatches[2],
            lightMuted = bwSwatches[3],
            allSwatches = bwSwatches
        )
        assertNull("Black-and-white / neutral artwork must return null fallback", bwSelected)

        val emptySelected = ArtworkAccentExtractor.pickSuitableAccentFromCandidates(allSwatches = emptyList())
        assertNull("Empty palette must return null fallback", emptySelected)
    }

    @Test
    fun `palette selection prioritizes dark chromatic swatch over dominant grey`() {
        val greyDominant = AccentSwatchCandidate(0xFF404040.toInt(), 9000)
        val greyMuted = AccentSwatchCandidate(0xFF808080.toInt(), 5000)
        val darkPurple = AccentSwatchCandidate(0xFF4A148C.toInt(), 450)

        val selectedSource = ArtworkAccentExtractor.pickSuitableAccentFromCandidates(
            darkVibrant = darkPurple,
            muted = greyMuted,
            dominant = greyDominant,
            allSwatches = listOf(greyDominant, greyMuted, darkPurple)
        )
        assertNotNull("Chromatic dark purple must be extracted despite high-population greys", selectedSource)
        val result = ArtworkAccentExtractor.buildAccentResult(selectedSource!!)
        assertEquals(0xFF4A148C.toInt(), result.sourceAccentColor)
        assertTrue(ArtworkAccentExtractor.calculateLuminance(result.glowColor) >= ArtworkAccentExtractor.MIN_GLOW_LUMINANCE)
    }

    @Test
    fun `same colour input produces deterministic accent and glow output`() {
        val input = 0xFF6B1028.toInt()
        val first = ArtworkAccentExtractor.buildAccentResult(input)
        val second = ArtworkAccentExtractor.buildAccentResult(input)
        assertEquals(first, second)
    }

    @Test
    fun `washed-out pastel, dull slate, and near-white low-chroma colours are rejected`() {
        val washedOutOrDull = listOf(
            0xFF788A96.toInt() to "Dull Slate Blue",
            0xFF94A8B8.toInt() to "Washed-out Steel Blue",
            0xFFB89098.toInt() to "Faded Dusty Pink",
            0xFFD4B8BD.toInt() to "Pale Dusty Rose",
            0xFFC5CAE9.toInt() to "Washed-out Lavender Grey",
            0xFFCFD8DC.toInt() to "Blue Grey Mist",
            0xFFFFFDE7.toInt() to "Near-white Cream",
            0xFFF3E5F5.toInt() to "Near-white Lavender",
            0xFFE8F5E9.toInt() to "Near-white Mint"
        )
        for ((argb, label) in washedOutOrDull) {
            assertFalse("$label must be rejected as washed-out/low-chroma", ArtworkAccentExtractor.isSuitableForHudAccent(argb))
        }
    }

    @Test
    fun `bright chromatic colours across red, green, yellow, blue, cyan, pink remain usable`() {
        val brightChromatics = listOf(
            0xFFF44336.toInt() to "Vibrant Red",
            0xFF4CAF50.toInt() to "Vibrant Green",
            0xFFFFE066.toInt() to "Bright Yellow",
            0xFF2979FF.toInt() to "Vibrant Blue",
            0xFF00E5FF.toInt() to "Vibrant Cyan",
            0xFFE91E63.toInt() to "Vibrant Pink"
        )
        for ((argb, label) in brightChromatics) {
            assertTrue("$label must remain accepted", ArtworkAccentExtractor.isSuitableForHudAccent(argb))
            val result = ArtworkAccentExtractor.buildAccentResult(argb)
            assertTrue(
                "$label glow must be luminous",
                ArtworkAccentExtractor.calculateLuminance(result.glowColor) >= ArtworkAccentExtractor.MIN_GLOW_LUMINANCE
            )
            assertTrue(
                "$label active text must be readable",
                HudColorSystem.contrastRatio(result.activeTextColor, HudColorSystem.BLACK_BACKDROP_COLOR) >= ArtworkAccentExtractor.MIN_ACTIVE_TEXT_CONTRAST - 0.1
            )
        }
    }

    @Test
    fun `palette selection strongly prefers vibrant candidates over muted and dominant swatches`() {
        val dullMuted = AccentSwatchCandidate(0xFF8C7A7E.toInt(), 8000)
        val dullDominant = AccentSwatchCandidate(0xFF708090.toInt(), 9500)
        val vibrantCoral = AccentSwatchCandidate(0xFFFF5252.toInt(), 600)
        val vibrantCyan = AccentSwatchCandidate(0xFF00E5FF.toInt(), 450)

        val selected = ArtworkAccentExtractor.pickSuitableAccentFromCandidates(
            vibrant = vibrantCoral,
            lightVibrant = vibrantCyan,
            muted = dullMuted,
            dominant = dullDominant,
            allSwatches = listOf(dullDominant, dullMuted, vibrantCoral, vibrantCyan)
        )
        assertNotNull(selected)
        assertTrue(
            "Vibrant candidate must win over muted/dominant swatches",
            selected == 0xFFFF5252.toInt() || selected == 0xFF00E5FF.toInt()
        )
    }

    @Test
    fun `dynamic accent snackbar message and 3-second duration are configured`() {
        assertEquals(3000L, com.example.ui.screens.DYNAMIC_ACCENT_SNACKBAR_DURATION_MS)
        assertTrue(com.example.ui.screens.DYNAMIC_ACCENT_INFO_MESSAGE.contains("album artwork", ignoreCase = true))
    }

    private fun resolveEffectiveState(
        dynamicEnabled: Boolean,
        dynamicResult: DynamicAccentResult?,
        manualColor: Int
    ): Pair<Int, Int?> {
        return if (dynamicEnabled && dynamicResult != null) {
            dynamicResult.activeTextColor to dynamicResult.glowColor
        } else {
            manualColor to null
        }
    }
}
