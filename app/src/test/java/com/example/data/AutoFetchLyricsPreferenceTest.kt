package com.example.data

import com.example.data.local.dao.LyricsDao
import com.example.data.local.dao.SongHistoryDao
import com.example.data.local.entity.LyricsEntity
import com.example.data.local.entity.SongHistoryEntity
import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata
import com.example.data.preferences.UserPreferences
import com.example.data.repository.CompositeLyricsRepository
import com.example.domain.provider.LyricsProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFetchLyricsPreferenceTest {

    @Test
    fun testUserPreferencesDefaultAutoFetchIsEnabled() {
        val prefs = UserPreferences()
        assertTrue("autoFetchLyrics should be enabled by default", prefs.autoFetchLyrics)
    }

    @Test
    fun testAutoFetchDisabled_SkipsRemoteProvidersOnCacheMiss() = runBlocking {
        var remoteProviderCallCount = 0
        val fakeProvider = object : LyricsProvider {
            override val name = "FakeProvider"
            override suspend fun fetchLyrics(
                title: String,
                artist: String,
                durationSec: Int?
            ): Result<ParsedLyrics> {
                remoteProviderCallCount++
                return Result.success(
                    ParsedLyrics(
                        title = title,
                        artist = artist,
                        isSynced = false,
                        lines = emptyList(),
                        plainText = "Hello World",
                        source = name
                    )
                )
            }
        }

        val fakeLyricsDao = object : LyricsDao {
            private val cache = mutableMapOf<String, LyricsEntity>()
            override suspend fun getLyricsByKey(key: String): LyricsEntity? = cache[key]
            override fun observeLyricsByKey(key: String): Flow<LyricsEntity?> = flowOf(cache[key])
            override suspend fun insertLyrics(lyrics: LyricsEntity) { cache[lyrics.key] = lyrics }
            override suspend fun deleteLyricsByKey(key: String) { cache.remove(key) }
            override suspend fun clearAllLyrics() { cache.clear() }
        }

        val fakeHistoryDao = object : SongHistoryDao {
            override fun getRecentHistory(): Flow<List<SongHistoryEntity>> = flowOf(emptyList())
            override suspend fun insertHistory(entry: SongHistoryEntity) {}
            override suspend fun clearHistory() {}
        }

        val repository = CompositeLyricsRepository(
            lyricsDao = fakeLyricsDao,
            historyDao = fakeHistoryDao,
            providers = listOf(fakeProvider)
        )

        val track = TrackMetadata(title = "Test Title", artist = "Test Artist")

        // 1. With allowNetwork = false and no cache: should fail and NOT call provider
        val result = repository.getOrFetchLyrics(
            track = track,
            forceNetwork = false,
            allowNetwork = false
        )

        assertTrue("Should return failure when auto-fetch is disabled and not cached", result.isFailure)
        assertEquals("Remote provider should not be called when allowNetwork is false", 0, remoteProviderCallCount)
    }

    @Test
    fun testAutoFetchDisabled_ReturnsCachedLyricsWithoutRemoteCall() = runBlocking {
        var remoteProviderCallCount = 0
        val fakeProvider = object : LyricsProvider {
            override val name = "FakeProvider"
            override suspend fun fetchLyrics(
                title: String,
                artist: String,
                durationSec: Int?
            ): Result<ParsedLyrics> {
                remoteProviderCallCount++
                return Result.success(
                    ParsedLyrics(
                        title = title,
                        artist = artist,
                        isSynced = false,
                        lines = emptyList(),
                        plainText = "Remote Lyrics",
                        source = name
                    )
                )
            }
        }

        val track = TrackMetadata(title = "Cached Song", artist = "Cached Artist")
        val fakeLyricsDao = object : LyricsDao {
            private val cache = mutableMapOf<String, LyricsEntity>(
                track.key to LyricsEntity(
                    key = track.key,
                    title = track.title,
                    artist = track.artist,
                    syncedLrc = "[00:01.00] Cached Line 1\n[00:05.00] Cached Line 2",
                    plainLyrics = "Cached Line 1\nCached Line 2",
                    isSynced = true,
                    durationSec = 180,
                    source = "Local Test",
                    cachedAt = System.currentTimeMillis()
                )
            )
            override suspend fun getLyricsByKey(key: String): LyricsEntity? = cache[key]
            override fun observeLyricsByKey(key: String): Flow<LyricsEntity?> = flowOf(cache[key])
            override suspend fun insertLyrics(lyrics: LyricsEntity) { cache[lyrics.key] = lyrics }
            override suspend fun deleteLyricsByKey(key: String) { cache.remove(key) }
            override suspend fun clearAllLyrics() { cache.clear() }
        }

        val fakeHistoryDao = object : SongHistoryDao {
            override fun getRecentHistory(): Flow<List<SongHistoryEntity>> = flowOf(emptyList())
            override suspend fun insertHistory(entry: SongHistoryEntity) {}
            override suspend fun clearHistory() {}
        }

        val repository = CompositeLyricsRepository(
            lyricsDao = fakeLyricsDao,
            historyDao = fakeHistoryDao,
            providers = listOf(fakeProvider)
        )

        // With allowNetwork = false, should return cached lyrics without calling remote provider
        val result = repository.getOrFetchLyrics(
            track = track,
            forceNetwork = false,
            allowNetwork = false
        )

        assertTrue("Should succeed using cached lyrics", result.isSuccess)
        val lyrics = result.getOrNull()
        assertTrue("Parsed lyrics should be synced", lyrics?.isSynced == true)
        assertEquals("Should have 2 lines", 2, lyrics?.lines?.size)
        assertEquals("Cached Line 1", lyrics?.lines?.get(0)?.text)
        assertEquals("Remote provider must not be invoked when cache hit", 0, remoteProviderCallCount)
    }

    @Test
    fun testAutoFetchEnabled_FetchesFromRemoteAndCaches() = runBlocking {
        var remoteProviderCallCount = 0
        val fakeProvider = object : LyricsProvider {
            override val name = "FakeProvider"
            override suspend fun fetchLyrics(
                title: String,
                artist: String,
                durationSec: Int?
            ): Result<ParsedLyrics> {
                remoteProviderCallCount++
                return Result.success(
                    ParsedLyrics(
                        title = title,
                        artist = artist,
                        lines = emptyList(),
                        plainText = "Fetched remote lyrics",
                        rawContent = "Fetched remote lyrics",
                        isSynced = false,
                        source = name
                    )
                )
            }
        }

        val fakeLyricsDao = object : LyricsDao {
            val cache = mutableMapOf<String, LyricsEntity>()
            override suspend fun getLyricsByKey(key: String): LyricsEntity? = cache[key]
            override fun observeLyricsByKey(key: String): Flow<LyricsEntity?> = flowOf(cache[key])
            override suspend fun insertLyrics(lyrics: LyricsEntity) { cache[lyrics.key] = lyrics }
            override suspend fun deleteLyricsByKey(key: String) { cache.remove(key) }
            override suspend fun clearAllLyrics() { cache.clear() }
        }

        val fakeHistoryDao = object : SongHistoryDao {
            override fun getRecentHistory(): Flow<List<SongHistoryEntity>> = flowOf(emptyList())
            override suspend fun insertHistory(entry: SongHistoryEntity) {}
            override suspend fun clearHistory() {}
        }

        val repository = CompositeLyricsRepository(
            lyricsDao = fakeLyricsDao,
            historyDao = fakeHistoryDao,
            providers = listOf(fakeProvider)
        )

        val track = TrackMetadata(title = "New Track", artist = "New Artist")

        // 1. With allowNetwork = true: fetches from remote and caches
        val result = repository.getOrFetchLyrics(
            track = track,
            forceNetwork = false,
            allowNetwork = true
        )

        assertTrue(result.isSuccess)
        assertEquals(1, remoteProviderCallCount)
        assertTrue("Lyrics should now be cached in database", fakeLyricsDao.cache.containsKey(track.key))

        // 2. Subsequent call with allowNetwork = false uses cache without another remote call
        val cachedResult = repository.getOrFetchLyrics(
            track = track,
            forceNetwork = false,
            allowNetwork = false
        )
        assertTrue(cachedResult.isSuccess)
        assertEquals(1, remoteProviderCallCount) // Call count unchanged!
    }

    @Test
    fun testManualRefresh_BypassesCacheAndAlwaysAllowsNetwork() = runBlocking {
        var remoteProviderCallCount = 0
        val fakeProvider = object : LyricsProvider {
            override val name = "FakeProvider"
            override suspend fun fetchLyrics(
                title: String,
                artist: String,
                durationSec: Int?
            ): Result<ParsedLyrics> {
                remoteProviderCallCount++
                return Result.success(
                    ParsedLyrics(
                        title = title,
                        artist = artist,
                        lines = emptyList(),
                        plainText = "Fresh fetch $remoteProviderCallCount",
                        source = name,
                        isSynced = false
                    )
                )
            }
        }

        val track = TrackMetadata(title = "Refresh Song", artist = "Refresh Artist")
        val fakeLyricsDao = object : LyricsDao {
            val cache = mutableMapOf<String, LyricsEntity>(
                track.key to LyricsEntity(
                    key = track.key,
                    title = track.title,
                    artist = track.artist,
                    syncedLrc = null,
                    plainLyrics = "Old cached lyrics",
                    isSynced = false,
                    durationSec = 100,
                    source = "OldSource",
                    cachedAt = 1000L
                )
            )
            override suspend fun getLyricsByKey(key: String): LyricsEntity? = cache[key]
            override fun observeLyricsByKey(key: String): Flow<LyricsEntity?> = flowOf(cache[key])
            override suspend fun insertLyrics(lyrics: LyricsEntity) { cache[lyrics.key] = lyrics }
            override suspend fun deleteLyricsByKey(key: String) { cache.remove(key) }
            override suspend fun clearAllLyrics() { cache.clear() }
        }

        val fakeHistoryDao = object : SongHistoryDao {
            override fun getRecentHistory(): Flow<List<SongHistoryEntity>> = flowOf(emptyList())
            override suspend fun insertHistory(entry: SongHistoryEntity) {}
            override suspend fun clearHistory() {}
        }

        val repository = CompositeLyricsRepository(
            lyricsDao = fakeLyricsDao,
            historyDao = fakeHistoryDao,
            providers = listOf(fakeProvider)
        )

        // Manual refresh passes forceNetwork = true, allowNetwork = true
        val result = repository.getOrFetchLyrics(
            track = track,
            forceNetwork = true,
            allowNetwork = true
        )

        assertTrue(result.isSuccess)
        assertEquals(1, remoteProviderCallCount)
        assertEquals("Fresh fetch 1", result.getOrNull()?.plainText)
    }

    @Test
    fun testAutoFetchPreferenceToggleResumptionSimulation() {
        var autoFetchLyrics = false
        val networkCallLog = mutableListOf<String>()

        fun onTrackChanged(track: TrackMetadata) {
            val allowNetwork = autoFetchLyrics
            if (allowNetwork) {
                networkCallLog.add("fetch:${track.key}")
            }
        }

        val track1 = TrackMetadata(title = "Song One", artist = "Artist One")
        val track2 = TrackMetadata(title = "Song Two", artist = "Artist Two")

        // 1. Auto-fetch is OFF: track changes should not initiate network calls
        onTrackChanged(track1)
        assertTrue(networkCallLog.isEmpty())

        // 2. User toggles Auto-fetch ON
        autoFetchLyrics = true

        // If track is already loaded without lyrics, it fetches for current track
        networkCallLog.add("fetch:${track1.key}")
        assertEquals(listOf("fetch:${track1.key}"), networkCallLog)

        // Next track change now automatically fetches
        onTrackChanged(track2)
        assertEquals(listOf("fetch:${track1.key}", "fetch:${track2.key}"), networkCallLog)

        // 3. User toggles Auto-fetch back OFF
        autoFetchLyrics = false
        val track3 = TrackMetadata(title = "Song Three", artist = "Artist Three")
        onTrackChanged(track3)
        // track3 is not added to networkCallLog
        assertEquals(listOf("fetch:${track1.key}", "fetch:${track2.key}"), networkCallLog)
    }
}
