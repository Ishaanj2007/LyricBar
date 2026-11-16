package com.example.service

import android.media.session.PlaybackState
import com.example.data.local.dao.LyricsDao
import com.example.data.local.dao.SongHistoryDao
import com.example.data.local.entity.LyricsEntity
import com.example.data.local.entity.SongHistoryEntity
import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata
import com.example.data.repository.CompositeLyricsRepository
import com.example.domain.provider.LyricsProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

class MediaSessionPipelineIntegrationTest {

    private class InMemoryLyricsDao : LyricsDao {
        private val entries = Collections.synchronizedMap(LinkedHashMap<String, LyricsEntity>())
        private val flow = MutableStateFlow<Map<String, LyricsEntity>>(emptyMap())

        override suspend fun getLyricsByKey(key: String): LyricsEntity? = entries[key]

        override fun observeLyricsByKey(key: String): Flow<LyricsEntity?> = flow.map { it[key] }

        override suspend fun insertLyrics(lyrics: LyricsEntity) {
            entries[lyrics.key] = lyrics
            flow.value = synchronized(entries) { LinkedHashMap(entries) }
        }

        override suspend fun deleteLyricsByKey(key: String) {
            entries.remove(key)
            flow.value = synchronized(entries) { LinkedHashMap(entries) }
        }

        override suspend fun clearAllLyrics() {
            entries.clear()
            flow.value = emptyMap()
        }

        fun allKeys(): Set<String> = synchronized(entries) { entries.keys.toSet() }
    }

    private class InMemorySongHistoryDao : SongHistoryDao {
        private val entries = Collections.synchronizedList(mutableListOf<SongHistoryEntity>())
        private val nextId = AtomicLong(1L)
        private val flow = MutableStateFlow<List<SongHistoryEntity>>(emptyList())

        override fun getRecentHistory(): Flow<List<SongHistoryEntity>> = flow

        override suspend fun insertHistory(entry: SongHistoryEntity) {
            val stored = if (entry.id == 0L) entry.copy(id = nextId.getAndIncrement()) else entry
            synchronized(entries) {
                entries.add(0, stored)
                flow.value = entries.toList()
            }
        }

        override suspend fun deleteHistoryById(id: Long) {
            synchronized(entries) {
                entries.removeAll { it.id == id }
                flow.value = entries.toList()
            }
        }

        override suspend fun clearHistory() {
            synchronized(entries) {
                entries.clear()
                flow.value = emptyList()
            }
        }

        fun snapshot(): List<SongHistoryEntity> = synchronized(entries) { entries.toList() }
    }

    private class FakeTrackOffsetStore : TrackOffsetStore {
        private val offsets = MutableStateFlow<Map<String, Long>>(emptyMap())

        override fun getTrackOffsetFlow(trackKey: String, fallbackKey: String?): Flow<Long> {
            return offsets.map { map ->
                map[trackKey] ?: fallbackKey?.let { map[it] } ?: 0L
            }
        }

        override suspend fun setTrackOffset(trackKey: String, offsetMs: Long) {
            offsets.value = offsets.value + (trackKey to offsetMs)
        }
    }

    private class ControllableLyricsProvider(
        override val name: String = "FakeLyricsProvider"
    ) : LyricsProvider {
        val fetchedQueries: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val gates = Collections.synchronizedMap(mutableMapOf<String, CompletableDeferred<Result<ParsedLyrics>>>())
        private val enteredSignals = Collections.synchronizedMap(mutableMapOf<String, CompletableDeferred<Unit>>())
        private val immediateResponses = Collections.synchronizedMap(mutableMapOf<String, Result<ParsedLyrics>>())

        fun setImmediateLyrics(title: String, lineText: String) {
            immediateResponses[title] = Result.success(
                ParsedLyrics(
                    title = title,
                    artist = "Artist",
                    isSynced = true,
                    lines = listOf(
                        LyricLine(1_000L, lineText),
                        LyricLine(20_000L, "$lineText (verse 2)")
                    ),
                    plainText = "$lineText\n$lineText (verse 2)",
                    rawContent = "[00:01.00]$lineText\n[00:20.00]$lineText (verse 2)",
                    source = name
                )
            )
        }

        fun deferLyricsFor(title: String): Pair<CompletableDeferred<Result<ParsedLyrics>>, CompletableDeferred<Unit>> {
            val responseGate = CompletableDeferred<Result<ParsedLyrics>>()
            val enteredSignal = CompletableDeferred<Unit>()
            gates[title] = responseGate
            enteredSignals[title] = enteredSignal
            return responseGate to enteredSignal
        }

        override suspend fun fetchLyrics(
            title: String,
            artist: String,
            durationSec: Int?
        ): Result<ParsedLyrics> {
            fetchedQueries.add("$artist - $title")
            enteredSignals[title]?.complete(Unit)
            gates[title]?.let { return it.await() }
            return immediateResponses[title] ?: Result.failure(Exception("Not found for $title"))
        }
    }

    private suspend fun awaitCondition(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Timed out after ${timeoutMs}ms waiting for expected pipeline state")
            }
            delay(10)
        }
    }

    private fun songSnapshot(
        pkg: String = "com.spotify.music",
        state: Int = PlaybackState.STATE_PLAYING,
        title: String,
        artist: String,
        album: String = "Test Album",
        durationMs: Long = 210_000L,
        positionMs: Long = 5_000L
    ) = MediaSessionSnapshot(
        packageName = pkg,
        appName = TrackMetadata.resolveAppName(pkg),
        playbackState = state,
        positionMs = positionMs,
        playbackSpeed = 1.0f,
        hasMetadata = true,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs
    )

    private fun youtubeNormalVideoSnapshot(
        title: String = "Building a Custom Mechanical Keyboard",
        channel: String = "TechBuilder",
        state: Int = PlaybackState.STATE_PLAYING
    ) = MediaSessionSnapshot(
        packageName = "com.google.android.youtube",
        appName = "YouTube",
        playbackState = state,
        positionMs = 15_000L,
        playbackSpeed = 1.0f,
        hasMetadata = true,
        title = title,
        artist = channel,
        album = "",
        durationMs = 720_000L,
        hasArtwork = true
    )

    @Test
    fun `transition from eligible music to ineligible YouTube session clears stale lyrics and blocks fetch and history`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val lyricsDao = InMemoryLyricsDao()
        val historyDao = InMemorySongHistoryDao()
        val provider = ControllableLyricsProvider().apply {
            setImmediateLyrics("Song A", "Lyrics for Song A")
        }
        val repo = CompositeLyricsRepository(lyricsDao, historyDao, listOf(provider))
        val engine = MediaSessionPipelineEngine(
            scope = scope,
            repository = CompositeLyricsPipelineRepository(repo),
            offsetStore = FakeTrackOffsetStore()
        )

        try {
            // 1. Music app plays Song A
            val songA = songSnapshot(title = "Song A", artist = "Artist A")
            val selection1 = MediaSessionSelector.selectBestController(
                listOf(MediaControllerCandidate("tokA", songA, songA)),
                engine.currentSessionToken
            )
            assertTrue(engine.attachSession(selection1.selected?.token, selection1.selected?.snapshot))
            awaitCondition { !engine.isLoadingLyrics.value && engine.activeLyricLine.value == "Lyrics for Song A" && historyDao.snapshot().size == 1 }

            assertTrue(engine.playbackState.value.hasActiveSession)
            assertTrue(engine.playbackState.value.isPlaying)
            assertEquals("Song A", engine.playbackState.value.track?.title)
            val cachedKeysBeforeYoutube = lyricsDao.allKeys()
            assertTrue(cachedKeysBeforeYoutube.isNotEmpty())

            // 2. Song A disappears, only normal YouTube video is active -> selector yields null, engine clears stale state
            val ytVideo = youtubeNormalVideoSnapshot()
            val selection2 = MediaSessionSelector.selectBestController(
                listOf(MediaControllerCandidate("tokYT", ytVideo, ytVideo)),
                engine.currentSessionToken
            )
            assertNull(selection2.selected)
            engine.invalidateActiveSession(clearTrack = true)

            assertFalse(engine.playbackState.value.hasActiveSession)
            assertFalse(engine.playbackState.value.isPlaying)
            assertNull(engine.playbackState.value.track)
            assertNull(engine.currentLyrics.value)
            assertNull(engine.activeLyricLine.value)
            assertEquals(1, historyDao.snapshot().size) // YouTube video NOT added to history
            assertEquals(1, provider.fetchedQueries.size) // YouTube video did NOT trigger lyrics fetch
            assertEquals(cachedKeysBeforeYoutube, lyricsDao.allKeys()) // Existing Song A cache preserved

            // Direct defensive call to attachSession with ineligible session is also rejected
            assertFalse(engine.attachSession("tokYT", ytVideo))
            assertNull(engine.playbackState.value.track)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `paused music ignores concurrent ineligible YouTube video and resumes cleanly without corrupting lyrics`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val lyricsDao = InMemoryLyricsDao()
        val historyDao = InMemorySongHistoryDao()
        val provider = ControllableLyricsProvider().apply {
            setImmediateLyrics("Song A", "Song A lyric")
        }
        val repo = CompositeLyricsRepository(lyricsDao, historyDao, listOf(provider))
        val engine = MediaSessionPipelineEngine(
            scope = scope,
            repository = CompositeLyricsPipelineRepository(repo),
            offsetStore = FakeTrackOffsetStore()
        )

        try {
            // 1. Song A playing
            val songAPlaying = songSnapshot(title = "Song A", artist = "Artist A", state = PlaybackState.STATE_PLAYING)
            engine.attachSession("tokA", songAPlaying)
            awaitCondition { engine.activeLyricLine.value == "Song A lyric" && historyDao.snapshot().size == 1 }

            // 2. Song A paused
            val songAPaused = songSnapshot(title = "Song A", artist = "Artist A", state = PlaybackState.STATE_PAUSED)
            engine.updatePlaybackState("tokA", PlaybackState.STATE_PAUSED, 10_000L, 0.0f)
            assertTrue(engine.playbackState.value.hasActiveSession)
            assertFalse(engine.playbackState.value.isPlaying)
            assertEquals("Song A", engine.playbackState.value.track?.title)
            assertNotNull(engine.currentLyrics.value)

            // 3. Ineligible YouTube video starts playing while Spotify remains paused
            val ytVideo = youtubeNormalVideoSnapshot()
            val selection = MediaSessionSelector.selectBestController(
                listOf(
                    MediaControllerCandidate("tokYT", ytVideo, ytVideo),
                    MediaControllerCandidate("tokA", songAPaused, songAPaused)
                ),
                engine.currentSessionToken
            )
            assertEquals("tokA", selection.selected?.token)
            engine.attachSession(selection.selected?.token, selection.selected?.snapshot)
            delay(40)

            assertFalse(engine.playbackState.value.isPlaying) // Overlay stays hidden
            assertEquals("Song A", engine.playbackState.value.track?.title)
            assertEquals(1, provider.fetchedQueries.size)
            assertEquals(1, historyDao.snapshot().size)

            // 4. Song A resumes -> resumes cleanly without duplicate history
            engine.updatePlaybackState("tokA", PlaybackState.STATE_PLAYING, 10_500L, 1.0f)
            delay(40)
            assertTrue(engine.playbackState.value.isPlaying)
            assertEquals("Song A", engine.playbackState.value.track?.title)
            assertEquals(1, historyDao.snapshot().size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `async race between Song A and Song B discards late Song A response`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val lyricsDao = InMemoryLyricsDao()
        val historyDao = InMemorySongHistoryDao()
        val provider = ControllableLyricsProvider()
        val (deferredA, enteredA) = provider.deferLyricsFor("Song A")
        provider.setImmediateLyrics("Song B", "Fresh Song B line")

        val repo = CompositeLyricsRepository(lyricsDao, historyDao, listOf(provider))
        val engine = MediaSessionPipelineEngine(
            scope = scope,
            repository = CompositeLyricsPipelineRepository(repo),
            offsetStore = FakeTrackOffsetStore()
        )

        try {
            // 1. Start Song A whose network fetch hangs in flight
            val songA = songSnapshot(title = "Song A", artist = "Artist A", positionMs = 2_000L)
            engine.attachSession("tokMusic", songA)
            enteredA.await()
            assertTrue(engine.isLoadingLyrics.value)

            // 2. Quickly skip to Song B before Song A's provider finishes
            val songB = songSnapshot(title = "Song B", artist = "Artist B", positionMs = 2_000L)
            engine.updateMetadata("tokMusic", songB, force = false)
            awaitCondition { !engine.isLoadingLyrics.value && engine.activeLyricLine.value == "Fresh Song B line" }

            assertEquals("Song B", engine.playbackState.value.track?.title)
            assertEquals("Fresh Song B line", engine.activeLyricLine.value)

            // 3. Stale Song A response finally completes
            deferredA.complete(
                Result.success(
                    ParsedLyrics(
                        title = "Song A",
                        artist = "Artist A",
                        isSynced = true,
                        lines = listOf(LyricLine(1_000L, "STALE SONG A LYRIC")),
                        plainText = "STALE SONG A LYRIC",
                        rawContent = "[00:01.00]STALE SONG A LYRIC",
                        source = provider.name
                    )
                )
            )
            delay(50)

            // Generation guard must keep Song B intact
            assertEquals("Song B", engine.playbackState.value.track?.title)
            assertEquals("Fresh Song B line", engine.activeLyricLine.value)
            assertEquals("Song B", engine.currentLyrics.value?.title)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `async fetch in flight is cancelled when ineligible session takes over or session is removed`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val lyricsDao = InMemoryLyricsDao()
        val historyDao = InMemorySongHistoryDao()
        val provider = ControllableLyricsProvider()
        val (deferredA, enteredA) = provider.deferLyricsFor("Song A")

        val repo = CompositeLyricsRepository(lyricsDao, historyDao, listOf(provider))
        val engine = MediaSessionPipelineEngine(
            scope = scope,
            repository = CompositeLyricsPipelineRepository(repo),
            offsetStore = FakeTrackOffsetStore()
        )

        try {
            val songA = songSnapshot(title = "Song A", artist = "Artist A")
            engine.attachSession("tokA", songA)
            enteredA.await()
            assertTrue(engine.isLoadingLyrics.value)

            // Ineligible session replaces Song A while Song A request is in flight
            val ytVideo = youtubeNormalVideoSnapshot()
            assertFalse(engine.attachSession("tokYT", ytVideo))

            assertFalse(engine.isLoadingLyrics.value)
            assertNull(engine.playbackState.value.track)
            assertNull(engine.currentLyrics.value)

            // Even if Song A's provider resolves afterwards, stale lyrics must NOT appear
            deferredA.complete(
                Result.success(
                    ParsedLyrics(
                        title = "Song A",
                        artist = "Artist A",
                        isSynced = true,
                        lines = listOf(LyricLine(1_000L, "Zombie lyric")),
                        plainText = "Zombie lyric",
                        rawContent = "[00:01.00]Zombie lyric",
                        source = provider.name
                    )
                )
            )
            delay(50)

            assertNull(engine.playbackState.value.track)
            assertNull(engine.currentLyrics.value)
            assertNull(engine.activeLyricLine.value)
            assertFalse(engine.playbackState.value.hasActiveSession)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `incomplete or null metadata update on active session clears track instead of returning early with stale lyrics`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val lyricsDao = InMemoryLyricsDao()
        val historyDao = InMemorySongHistoryDao()
        val provider = ControllableLyricsProvider().apply {
            setImmediateLyrics("Valid Song", "Real lyric line")
        }
        val repo = CompositeLyricsRepository(lyricsDao, historyDao, listOf(provider))
        val engine = MediaSessionPipelineEngine(
            scope = scope,
            repository = CompositeLyricsPipelineRepository(repo),
            offsetStore = FakeTrackOffsetStore()
        )

        try {
            val valid = songSnapshot(title = "Valid Song", artist = "Real Artist")
            engine.attachSession("tok1", valid)
            awaitCondition { engine.activeLyricLine.value == "Real lyric line" }

            // Controller metadata becomes empty/missing title or artist -> MUST clear old song instead of retaining it
            val missingMetadata = MediaSessionSnapshot(
                packageName = "com.spotify.music",
                playbackState = PlaybackState.STATE_PLAYING,
                hasMetadata = false
            )
            engine.updateMetadata("tok1", missingMetadata, force = false)

            assertNull(engine.playbackState.value.track)
            assertNull(engine.currentLyrics.value)
            assertNull(engine.activeLyricLine.value)
            assertFalse(engine.playbackState.value.hasActiveSession)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `normal video from idle triggers zero fetches cache entries or history inserts`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val lyricsDao = InMemoryLyricsDao()
        val historyDao = InMemorySongHistoryDao()
        val provider = ControllableLyricsProvider()
        val repo = CompositeLyricsRepository(lyricsDao, historyDao, listOf(provider))
        val engine = MediaSessionPipelineEngine(
            scope = scope,
            repository = CompositeLyricsPipelineRepository(repo),
            offsetStore = FakeTrackOffsetStore()
        )

        try {
            val ytVideo = youtubeNormalVideoSnapshot()
            val selection = MediaSessionSelector.selectBestController(
                listOf(MediaControllerCandidate("tokYT", ytVideo, ytVideo)),
                engine.currentSessionToken
            )
            assertNull(selection.selected)
            assertFalse(engine.attachSession("tokYT", ytVideo))
            delay(40)

            assertTrue(provider.fetchedQueries.isEmpty())
            assertTrue(lyricsDao.allKeys().isEmpty())
            assertTrue(historyDao.snapshot().isEmpty())
            assertFalse(engine.playbackState.value.hasActiveSession)

            // Also verify repository itself refuses placeholder/missing-artist records
            val garbageTrack = TrackMetadata(title = "Random Video", artist = "")
            val directResult = repo.getOrFetchLyrics(garbageTrack, forceNetwork = true, allowNetwork = true)
            repo.recordHistory(garbageTrack)
            assertTrue(directResult.isFailure)
            assertTrue(provider.fetchedQueries.isEmpty())
            assertTrue(lyricsDao.allKeys().isEmpty())
            assertTrue(historyDao.snapshot().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stopping session clears active state and playing eligible session supersedes paused eligible session`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val lyricsDao = InMemoryLyricsDao()
        val historyDao = InMemorySongHistoryDao()
        val provider = ControllableLyricsProvider().apply {
            setImmediateLyrics("Paused Track", "Paused track line")
            setImmediateLyrics("Playing Track", "Playing track line")
        }
        val repo = CompositeLyricsRepository(lyricsDao, historyDao, listOf(provider))
        val engine = MediaSessionPipelineEngine(
            scope = scope,
            repository = CompositeLyricsPipelineRepository(repo),
            offsetStore = FakeTrackOffsetStore()
        )

        try {
            val pausedA = songSnapshot(
                pkg = "com.spotify.music",
                state = PlaybackState.STATE_PAUSED,
                title = "Paused Track",
                artist = "Artist A"
            )
            val playingB = songSnapshot(
                pkg = "com.google.android.apps.youtube.music",
                state = PlaybackState.STATE_PLAYING,
                title = "Playing Track",
                artist = "Artist B"
            )

            // Attach paused track first (no playing tracks yet)
            val sel1 = MediaSessionSelector.selectBestController(
                listOf(MediaControllerCandidate("tokA", pausedA, pausedA)),
                engine.currentSessionToken
            )
            assertEquals("tokA", sel1.selected?.token)
            engine.attachSession("tokA", pausedA)
            awaitCondition { engine.playbackState.value.track?.title == "Paused Track" && engine.currentLyrics.value != null }
            assertTrue(historyDao.snapshot().isEmpty()) // Paused session must NOT record play history

            // Playing eligible track appears -> supersedes paused track
            val sel2 = MediaSessionSelector.selectBestController(
                listOf(
                    MediaControllerCandidate("tokA", pausedA, pausedA),
                    MediaControllerCandidate("tokB", playingB, playingB)
                ),
                engine.currentSessionToken
            )
            assertEquals("tokB", sel2.selected?.token)
            engine.attachSession("tokB", playingB)
            awaitCondition { engine.activeLyricLine.value == "Playing track line" && historyDao.snapshot().size == 1 }
            assertEquals("Playing Track", engine.playbackState.value.track?.title)

            // Repeated playback callbacks for same track do NOT duplicate history
            engine.updatePlaybackState("tokB", PlaybackState.STATE_PLAYING, 8_000L, 1.0f)
            engine.updatePlaybackState("tokB", PlaybackState.STATE_PLAYING, 12_000L, 1.0f)
            delay(40)
            assertEquals(1, historyDao.snapshot().size)

            // Stopping session clears stale track and lyrics
            engine.updatePlaybackState("tokB", PlaybackState.STATE_STOPPED, 0L, 0.0f)
            assertNull(engine.playbackState.value.track)
            assertNull(engine.currentLyrics.value)
            assertFalse(engine.playbackState.value.hasActiveSession)
        } finally {
            scope.cancel()
        }
    }
}
