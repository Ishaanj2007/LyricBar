package com.example.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.data.local.dao.LyricsDao
import com.example.data.local.dao.SongHistoryDao
import com.example.data.local.entity.LyricsEntity
import com.example.data.local.entity.SongHistoryEntity
import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata
import com.example.data.preferences.LyricsProviderPreference
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.CompositeLyricsRepository
import com.example.domain.provider.LyricsProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LyricsProviderPreferenceAndCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =========================================================================
    // 1. Default State, Shared DataStore Flow Sync & Restart Persistence
    // =========================================================================

    @Test
    fun `both Lyrics and Settings screens initially show Automatic`() = runBlocking {
        val storeFile = tempFolder.newFile("prefs_initial.preferences_pb")
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
            val repository = UserPreferencesRepository(dataStore)

            val defaultUserPrefs = UserPreferences()
            assertEquals(LyricsProviderPreference.AUTO, defaultUserPrefs.lyricsProvider)
            assertEquals("Automatic", defaultUserPrefs.lyricsProvider.displayName)

            val lyricsScreenPref = repository.userPreferencesFlow.first().lyricsProvider
            val settingsScreenPref = repository.userPreferencesFlow.first().lyricsProvider

            assertEquals(LyricsProviderPreference.AUTO, lyricsScreenPref)
            assertEquals(LyricsProviderPreference.AUTO, settingsScreenPref)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `changing provider in Lyrics updates Settings and changing in Settings updates Lyrics`() = runBlocking {
        val storeFile = tempFolder.newFile("prefs_sync.preferences_pb")
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) { storeFile }
            val sharedRepository = UserPreferencesRepository(dataStore)

            var lyricsScreenObserved = sharedRepository.userPreferencesFlow.first().lyricsProvider
            var settingsScreenObserved = sharedRepository.userPreferencesFlow.first().lyricsProvider

            val collectorJob1 = scope.launch {
                sharedRepository.userPreferencesFlow.collect { prefs ->
                    lyricsScreenObserved = prefs.lyricsProvider
                }
            }
            val collectorJob2 = scope.launch {
                sharedRepository.userPreferencesFlow.collect { prefs ->
                    settingsScreenObserved = prefs.lyricsProvider
                }
            }

            assertEquals(LyricsProviderPreference.AUTO, lyricsScreenObserved)
            assertEquals(LyricsProviderPreference.AUTO, settingsScreenObserved)

            // 1. User changes provider on Lyrics screen to KuGou -> Settings screen immediately observes KUGOU
            sharedRepository.setLyricsProvider(LyricsProviderPreference.KUGOU)
            assertEquals(LyricsProviderPreference.KUGOU, lyricsScreenObserved)
            assertEquals(LyricsProviderPreference.KUGOU, settingsScreenObserved)

            // 2. User changes provider on Settings screen to lrc.red -> Lyrics screen immediately observes LRC_RED
            sharedRepository.setLyricsProvider(LyricsProviderPreference.LRC_RED)
            assertEquals(LyricsProviderPreference.LRC_RED, settingsScreenObserved)
            assertEquals(LyricsProviderPreference.LRC_RED, lyricsScreenObserved)

            // 3. User changes provider on Settings screen to LyricsPlus -> Lyrics screen immediately observes LYRICS_PLUS
            sharedRepository.setLyricsProvider(LyricsProviderPreference.LYRICS_PLUS)
            assertEquals(LyricsProviderPreference.LYRICS_PLUS, lyricsScreenObserved)
            assertEquals(LyricsProviderPreference.LYRICS_PLUS, settingsScreenObserved)

            collectorJob1.cancel()
            collectorJob2.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `preference survives app restart and safely falls back to AUTO on unknown persisted values`() = runBlocking {
        val storeFile = tempFolder.newFile("prefs_restart.preferences_pb")

        // Session 1: Set provider to KUGOU and close session
        val scope1 = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val dataStore1 = PreferenceDataStoreFactory.create(scope = scope1) { storeFile }
        val repo1 = UserPreferencesRepository(dataStore1)
        repo1.setLyricsProvider(LyricsProviderPreference.KUGOU)
        assertEquals(LyricsProviderPreference.KUGOU, repo1.userPreferencesFlow.first().lyricsProvider)
        scope1.cancel()

        // Session 2 (simulated app restart): Reopen DataStore on the same backing file
        val scope2 = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        try {
            val dataStore2 = PreferenceDataStoreFactory.create(scope = scope2) { storeFile }
            val repo2 = UserPreferencesRepository(dataStore2)
            assertEquals(
                "Persisted provider preference must survive app restart",
                LyricsProviderPreference.KUGOU,
                repo2.userPreferencesFlow.first().lyricsProvider
            )

            // Write an unknown / corrupted value directly to DataStore and verify safe fallback to AUTO
            dataStore2.edit { prefs ->
                prefs[stringPreferencesKey("lyrics_provider")] = "UNKNOWN_FUTURE_PROVIDER"
            }
            assertEquals(
                "Unknown persisted value must fall back safely to AUTO",
                LyricsProviderPreference.AUTO,
                repo2.userPreferencesFlow.first().lyricsProvider
            )
        } finally {
            scope2.cancel()
        }
    }

    // =========================================================================
    // 2. Provider Execution Logic (Manual vs Automatic)
    // =========================================================================

    @Test
    fun `manual selection invokes only the selected provider and failed manual provider does not invoke others`() = runBlocking {
        val callOrder = mutableListOf<String>()
        val lrclib = fakeProvider("LRCLIB", callOrder, shouldSucceed = true)
        val lrcRed = fakeProvider("lrc.red", callOrder, shouldSucceed = false)
        val kugou = fakeProvider("KuGou", callOrder, shouldSucceed = true)
        val lyricsPlus = fakeProvider("LyricsPlus", callOrder, shouldSucceed = true)

        val dao = InMemoryLyricsDao()
        val repo = CompositeLyricsRepository(
            lyricsDao = dao,
            historyDao = NoOpHistoryDao(),
            providers = listOf(lrclib, lrcRed, kugou, lyricsPlus)
        )

        val track = TrackMetadata(title = "Manual Test Song", artist = "Test Artist", durationSec = 200)

        // 1. Manual selection: KUGOU -> invokes ONLY KuGou (skips LRCLIB, lrc.red, LyricsPlus)
        val kugouResult = repo.getOrFetchLyrics(
            track = track,
            forceNetwork = true,
            allowNetwork = true,
            providerPreference = LyricsProviderPreference.KUGOU
        )
        assertTrue(kugouResult.isSuccess)
        assertEquals(listOf("KuGou"), callOrder)
        assertEquals("KuGou", kugouResult.getOrThrow().source)
        assertEquals("KuGou", kugouResult.getOrThrow().displaySource)

        // 2. Manual selection: LRC_RED (which fails) -> invokes ONLY lrc.red and does NOT fall back to LRCLIB, KuGou, or LyricsPlus
        callOrder.clear()
        val lrcRedResult = repo.getOrFetchLyrics(
            track = track,
            forceNetwork = true,
            allowNetwork = true,
            providerPreference = LyricsProviderPreference.LRC_RED
        )
        assertTrue("Failed manual provider must return failure", lrcRedResult.isFailure)
        assertEquals(
            "Failed manual provider must not invoke any other provider",
            listOf("lrc.red"),
            callOrder
        )
    }

    @Test
    fun `switching back to Automatic restores full fallback order LRCLIB to lrc_red to KuGou to LyricsPlus`() = runBlocking {
        val callOrder = mutableListOf<String>()
        val lrclib = fakeProvider("LRCLIB", callOrder, shouldSucceed = false)
        val lrcRed = fakeProvider("lrc.red", callOrder, shouldSucceed = false)
        val kugou = fakeProvider("KuGou", callOrder, shouldSucceed = true)
        val lyricsPlus = fakeProvider("LyricsPlus", callOrder, shouldSucceed = true)

        val dao = InMemoryLyricsDao()
        val repo = CompositeLyricsRepository(
            lyricsDao = dao,
            historyDao = NoOpHistoryDao(),
            providers = listOf(lrclib, lrcRed, kugou, lyricsPlus)
        )

        val track = TrackMetadata(title = "Fallback Song", artist = "Fallback Artist", durationSec = 190)

        // First run in manual LRCLIB mode (fails)
        val manualFail = repo.getOrFetchLyrics(
            track = track,
            forceNetwork = true,
            allowNetwork = true,
            providerPreference = LyricsProviderPreference.LRCLIB
        )
        assertTrue(manualFail.isFailure)
        assertEquals(listOf("LRCLIB"), callOrder)

        // Switch back to Automatic -> runs LRCLIB -> lrc.red -> KuGou (succeeds) and stops before LyricsPlus
        callOrder.clear()
        val autoResult = repo.getOrFetchLyrics(
            track = track,
            forceNetwork = true,
            allowNetwork = true,
            providerPreference = LyricsProviderPreference.AUTO
        )
        assertTrue(autoResult.isSuccess)
        assertEquals(listOf("LRCLIB", "lrc.red", "KuGou"), callOrder)
        // Selected preference is AUTO, while actual source is KuGou
        assertEquals("KuGou", autoResult.getOrThrow().source)
        assertEquals("KuGou", autoResult.getOrThrow().displaySource)
    }

    // =========================================================================
    // 3. Actual Lyrics Source & Local Import Source Separation
    // =========================================================================

    @Test
    fun `actual source matches returned lyrics and local imports show Local Import`() = runBlocking {
        val callOrder = mutableListOf<String>()
        val lrclib = fakeProvider("LRCLIB", callOrder, shouldSucceed = false)
        val kugou = fakeProvider("KuGou", callOrder, shouldSucceed = true)

        val dao = InMemoryLyricsDao()
        val repo = CompositeLyricsRepository(
            lyricsDao = dao,
            historyDao = NoOpHistoryDao(),
            providers = listOf(lrclib, kugou)
        )

        val track = TrackMetadata(title = "Source Test", artist = "Artist", durationSec = 180)

        // Selected provider is AUTO, actual source is KuGou
        val remoteLyrics = repo.getOrFetchLyrics(
            track = track,
            providerPreference = LyricsProviderPreference.AUTO
        ).getOrThrow()
        assertEquals(LyricsProviderPreference.AUTO.displayName, "Automatic")
        assertEquals("KuGou", remoteLyrics.source)
        assertEquals("KuGou", remoteLyrics.displaySource)

        // Local import shows "Local Import"
        val importedTrack = TrackMetadata(title = "Imported Song", artist = "Artist", durationSec = 180)
        val importedLyrics = repo.saveImportedLyrics(
            track = importedTrack,
            content = "[00:01.00]Imported line 1\n[00:05.00]Imported line 2"
        )
        assertEquals("Local Import", importedLyrics.source)
        assertEquals("Local Import", importedLyrics.displaySource)

        // Reloading imported track from Room cache in AUTO mode still shows "Local Import"
        val cachedImport = repo.getOrFetchLyrics(
            track = importedTrack,
            forceNetwork = false,
            allowNetwork = false,
            providerPreference = LyricsProviderPreference.AUTO
        ).getOrThrow()
        assertEquals("Local Import", cachedImport.displaySource)
    }

    // =========================================================================
    // 4. Cache Isolation in Manual Mode & Non-Destructive Cache Preservation
    // =========================================================================

    @Test
    fun `cache does not return lyrics from a different provider in manual mode and preserves valid cached lyrics`() = runBlocking {
        val callOrder = mutableListOf<String>()
        val lrclib = fakeProvider("LRCLIB", callOrder, shouldSucceed = true, linePrefix = "LRCLIB line")
        val lrcRed = fakeProvider("lrc.red", callOrder, shouldSucceed = false, linePrefix = "lrc.red line")
        val kugou = fakeProvider("KuGou", callOrder, shouldSucceed = true, linePrefix = "KuGou line")
        val lyricsPlus = fakeProvider("LyricsPlus", callOrder, shouldSucceed = true, linePrefix = "LyricsPlus line")

        val dao = InMemoryLyricsDao()
        val repo = CompositeLyricsRepository(
            lyricsDao = dao,
            historyDao = NoOpHistoryDao(),
            providers = listOf(lrclib, lrcRed, kugou, lyricsPlus)
        )

        val track = TrackMetadata(title = "Cache Isolation Track", artist = "Artist", durationSec = 200)

        // 1. Fetch in AUTO mode -> caches LRCLIB lyrics
        val autoFetched = repo.getOrFetchLyrics(
            track = track,
            forceNetwork = false,
            allowNetwork = true,
            providerPreference = LyricsProviderPreference.AUTO
        ).getOrThrow()
        assertEquals("LRCLIB", autoFetched.source)
        assertEquals("LRCLIB line 1", autoFetched.lines[0].text)
        assertEquals(listOf("LRCLIB"), callOrder)

        // 2. Switch to manual KUGOU mode with forceNetwork = false:
        // Must NOT return cached LRCLIB lyrics! Must fetch from KuGou instead.
        callOrder.clear()
        val manualKugou = repo.getOrFetchLyrics(
            track = track,
            forceNetwork = false,
            allowNetwork = true,
            providerPreference = LyricsProviderPreference.KUGOU
        ).getOrThrow()
        assertEquals("KuGou", manualKugou.source)
        assertEquals("KuGou line 1", manualKugou.lines[0].text)
        assertEquals(listOf("KuGou"), callOrder)

        // 3. Switch to manual LRC_RED mode (which fails):
        // Must NOT return cached LRCLIB or KuGou lyrics, and must NOT delete them!
        callOrder.clear()
        val manualLrcRed = repo.getOrFetchLyrics(
            track = track,
            forceNetwork = false,
            allowNetwork = true,
            providerPreference = LyricsProviderPreference.LRC_RED
        )
        assertTrue("Manual lrc.red must fail and not return another provider's cache", manualLrcRed.isFailure)
        assertEquals(listOf("lrc.red"), callOrder)

        // 4. Verify previously cached LRCLIB and KuGou entries were NOT deleted and can be read offline (allowNetwork = false)
        callOrder.clear()
        val cachedLrclibOffline = repo.getOrFetchLyrics(
            track = track,
            forceNetwork = false,
            allowNetwork = false,
            providerPreference = LyricsProviderPreference.LRCLIB
        )
        assertTrue("Cached LRCLIB lyrics must still be intact", cachedLrclibOffline.isSuccess)
        assertEquals("LRCLIB", cachedLrclibOffline.getOrThrow().source)
        assertEquals("LRCLIB line 1", cachedLrclibOffline.getOrThrow().lines[0].text)

        val cachedKugouOffline = repo.getOrFetchLyrics(
            track = track,
            forceNetwork = false,
            allowNetwork = false,
            providerPreference = LyricsProviderPreference.KUGOU
        )
        assertTrue("Cached KuGou lyrics must still be intact", cachedKugouOffline.isSuccess)
        assertEquals("KuGou", cachedKugouOffline.getOrThrow().source)
        assertEquals("KuGou line 1", cachedKugouOffline.getOrThrow().lines[0].text)
        assertTrue("No network calls should have occurred for cached hits", callOrder.isEmpty())
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun fakeProvider(
        providerName: String,
        callOrder: MutableList<String>,
        shouldSucceed: Boolean,
        linePrefix: String = "Line from $providerName"
    ): LyricsProvider = object : LyricsProvider {
        override val name: String = providerName
        override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
            callOrder.add(providerName)
            if (!shouldSucceed) {
                return Result.failure(NoSuchElementException("$providerName failed"))
            }
            val lines = listOf(
                LyricLine(1000L, "$linePrefix 1"),
                LyricLine(4000L, "$linePrefix 2")
            )
            return Result.success(
                ParsedLyrics(
                    title = title,
                    artist = artist,
                    isSynced = true,
                    lines = lines,
                    plainText = lines.joinToString("\n") { it.text },
                    source = providerName
                )
            )
        }
    }

    private class InMemoryLyricsDao : LyricsDao {
        private val map = mutableMapOf<String, LyricsEntity>()
        override suspend fun getLyricsByKey(key: String): LyricsEntity? = map[key]
        override fun observeLyricsByKey(key: String): Flow<LyricsEntity?> = flowOf(map[key])
        override suspend fun insertLyrics(lyrics: LyricsEntity) { map[lyrics.key] = lyrics }
        override suspend fun deleteLyricsByKey(key: String) { map.remove(key) }
        override suspend fun clearAllLyrics() { map.clear() }
    }

    private class NoOpHistoryDao : SongHistoryDao {
        override fun getRecentHistory(): Flow<List<SongHistoryEntity>> = flowOf(emptyList())
        override suspend fun insertHistory(entry: SongHistoryEntity) {}
        override suspend fun deleteHistoryById(id: Long) {}
        override suspend fun clearHistory() {}
    }
}
