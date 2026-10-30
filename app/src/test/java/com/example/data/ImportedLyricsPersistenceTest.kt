package com.example.data

import com.example.data.local.dao.LyricsDao
import com.example.data.local.dao.SongHistoryDao
import com.example.data.local.entity.LyricsEntity
import com.example.data.local.entity.SongHistoryEntity
import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata
import com.example.data.repository.CompositeLyricsRepository
import com.example.domain.provider.LyricsProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ImportedLyricsPersistenceTest {

    private lateinit var fakeLyricsDao: InMemoryLyricsDao
    private lateinit var fakeHistoryDao: InMemorySongHistoryDao
    private lateinit var repository: CompositeLyricsRepository
    private var remoteFetchCount = 0

    private val sampleLrcA = """
        [00:01.00]First line of Song A
        [00:05.50]Second line of Song A
        [00:10.00]Chorus of Song A
    """.trimIndent()

    private val sampleLrcB = """
        [00:02.00]First line of Song B
        [00:07.00]Second line of Song B
    """.trimIndent()

    private val sampleLrcUpdated = """
        [00:01.20]Updated first line of Song A
        [00:06.00]Updated second line of Song A
        [00:11.00]Updated chorus of Song A
    """.trimIndent()

    @Before
    fun setUp() {
        fakeLyricsDao = InMemoryLyricsDao()
        fakeHistoryDao = InMemorySongHistoryDao()
        remoteFetchCount = 0

        val fakeProvider = object : LyricsProvider {
            override val name: String = "TestRemoteProvider"
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                remoteFetchCount++
                return Result.failure(Exception("Remote provider called unexpectedly"))
            }
        }

        repository = CompositeLyricsRepository(
            lyricsDao = fakeLyricsDao,
            historyDao = fakeHistoryDao,
            providers = listOf(fakeProvider)
        )
    }

    @Test
    fun testImportAndSaveValidLrc_PersistsCorrectEntityAndReturnsParsedLyrics() = runBlocking {
        val track = TrackMetadata(title = "Bohemian Rhapsody", artist = "Queen", durationSec = 354)

        val parsed = repository.saveImportedLyrics(track, sampleLrcA, source = "Local .lrc")

        // 1. Verify returned parsed object
        assertTrue("Parsed lyrics should be timestamp-synced", parsed.isSynced)
        assertEquals(3, parsed.lines.size)
        assertEquals("First line of Song A", parsed.lines[0].text)
        assertEquals(1000L, parsed.lines[0].timeMs)
        assertEquals("Queen", parsed.artist)
        assertEquals("Bohemian Rhapsody", parsed.title)

        // 2. Verify Room database entity persistence
        val entity = fakeLyricsDao.getLyricsByKey(track.key)
        assertNotNull("Imported lyrics must be saved in database", entity)
        assertEquals(track.key, entity!!.key)
        assertEquals("Queen", entity.artist)
        assertEquals("Bohemian Rhapsody", entity.title)
        assertTrue(entity.isSynced)
        assertEquals(sampleLrcA, entity.syncedLrc)
        assertEquals("Local .lrc", entity.source)
    }

    @Test
    fun testRetrieveImportedLyricsAfterReloading_FromRoomCache() = runBlocking {
        val track = TrackMetadata(title = "Blinding Lights", artist = "The Weeknd", durationSec = 200)

        // Save imported lyrics
        repository.saveImportedLyrics(track, sampleLrcA, source = "Local .lrc")

        // Simulate track change or app reload: retrieve cached lyrics via getOrFetchLyrics with forceNetwork = false
        val result = repository.getOrFetchLyrics(track, forceNetwork = false, allowNetwork = true)

        assertTrue("Reloading track must succeed with cached lyrics", result.isSuccess)
        val loadedLyrics = result.getOrThrow()
        assertTrue("Reloaded lyrics must preserve synchronization", loadedLyrics.isSynced)
        assertEquals(3, loadedLyrics.lines.size)
        assertEquals("First line of Song A", loadedLyrics.lines[0].text)
        assertEquals(1000L, loadedLyrics.lines[0].timeMs)

        // Remote network provider should never be called when imported lyrics are cached
        assertEquals("Remote network provider must not be invoked when imported lyrics exist", 0, remoteFetchCount)
    }

    @Test
    fun testReplacePreviouslyImportedLyrics_UpdatesRecordWithoutDuplicates() = runBlocking {
        val track = TrackMetadata(title = "Hotel California", artist = "Eagles", durationSec = 390)

        // First import
        repository.saveImportedLyrics(track, sampleLrcA, source = "Local .lrc")
        val initialEntity = fakeLyricsDao.getLyricsByKey(track.key)
        assertNotNull(initialEntity)
        assertEquals(sampleLrcA, initialEntity!!.syncedLrc)
        assertEquals(1, fakeLyricsDao.size())

        // Re-import / replace with updated LRC file
        val updatedParsed = repository.saveImportedLyrics(track, sampleLrcUpdated, source = "Local .lrc")
        assertEquals("Updated first line of Song A", updatedParsed.lines[0].text)

        // Verify updated entity replaced the old one without duplicates
        assertEquals("Replacing lyrics must not create duplicate database records", 1, fakeLyricsDao.size())
        val updatedEntity = fakeLyricsDao.getLyricsByKey(track.key)
        assertNotNull(updatedEntity)
        assertEquals(sampleLrcUpdated, updatedEntity!!.syncedLrc)

        // Verify reloading retrieves the updated content
        val reloadResult = repository.getOrFetchLyrics(track, forceNetwork = false)
        assertTrue(reloadResult.isSuccess)
        assertEquals("Updated first line of Song A", reloadResult.getOrThrow().lines[0].text)
    }

    @Test
    fun testInvalidLrcFiles_EmptyOrTtmlOrBinary_ThrowsErrorWithoutCorruptingExistingLyrics() = runBlocking {
        val track = TrackMetadata(title = "Stay", artist = "The Kid LAROI", durationSec = 141)

        // Save initial valid lyrics
        repository.saveImportedLyrics(track, sampleLrcA, source = "Local .lrc")
        val originalEntity = fakeLyricsDao.getLyricsByKey(track.key)
        assertNotNull(originalEntity)
        assertEquals(sampleLrcA, originalEntity!!.syncedLrc)

        // 1. Empty file
        try {
            repository.saveImportedLyrics(track, "", source = "Local .lrc")
            fail("Empty file should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("empty", ignoreCase = true))
        }
        assertEquals("Existing lyrics must not be corrupted by empty file", sampleLrcA, fakeLyricsDao.getLyricsByKey(track.key)?.syncedLrc)

        // 2. Blank whitespace file
        try {
            repository.saveImportedLyrics(track, "   \n\t\n  ", source = "Local .lrc")
            fail("Blank file should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("empty", ignoreCase = true))
        }
        assertEquals("Existing lyrics must not be corrupted by blank file", sampleLrcA, fakeLyricsDao.getLyricsByKey(track.key)?.syncedLrc)

        // 3. TTML file (unsupported format)
        val ttmlContent = """
            <tt xmlns="http://www.w3.org/ns/ttml">
                <body><div><p begin="00:00:01.000">TTML line</p></div></body>
            </tt>
        """.trimIndent()
        try {
            repository.saveImportedLyrics(track, ttmlContent, source = "Local .lrc")
            fail("TTML file should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("TTML", ignoreCase = true))
        }
        assertEquals("Existing lyrics must not be corrupted by TTML file", sampleLrcA, fakeLyricsDao.getLyricsByKey(track.key)?.syncedLrc)

        // 4. Binary file (contains null byte)
        val binaryContent = "RIFF\u0000\u0000\u0000WAVEfmt "
        try {
            repository.saveImportedLyrics(track, binaryContent, source = "Local .lrc")
            fail("Binary file should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("binary", ignoreCase = true))
        }
        assertEquals("Existing lyrics must not be corrupted by binary file", sampleLrcA, fakeLyricsDao.getLyricsByKey(track.key)?.syncedLrc)

        // 5. File containing only metadata header tags with no lyrics
        val headersOnly = """
            [ti:Some Title]
            [ar:Some Artist]
            [offset:0]
        """.trimIndent()
        try {
            repository.saveImportedLyrics(track, headersOnly, source = "Local .lrc")
            fail("File without valid lyrics should throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("No valid lyrics", ignoreCase = true))
        }
        assertEquals("Existing lyrics must not be corrupted by headers-only file", sampleLrcA, fakeLyricsDao.getLyricsByKey(track.key)?.syncedLrc)
    }

    @Test
    fun testDifferentTracksDoNotOverwriteEachOther() = runBlocking {
        val trackA = TrackMetadata(title = "Track One", artist = "Artist One")
        val trackB = TrackMetadata(title = "Track Two", artist = "Artist Two")

        assertNotEquals(trackA.key, trackB.key)

        // Save Track A
        repository.saveImportedLyrics(trackA, sampleLrcA, source = "Local .lrc")

        // Save Track B
        repository.saveImportedLyrics(trackB, sampleLrcB, source = "Local .lrc")

        // Verify both exist separately in database
        assertEquals(2, fakeLyricsDao.size())

        val lyricsA = repository.getOrFetchLyrics(trackA, forceNetwork = false).getOrThrow()
        val lyricsB = repository.getOrFetchLyrics(trackB, forceNetwork = false).getOrThrow()

        assertEquals(3, lyricsA.lines.size)
        assertEquals("First line of Song A", lyricsA.lines[0].text)

        assertEquals(2, lyricsB.lines.size)
        assertEquals("First line of Song B", lyricsB.lines[0].text)
    }

    // In-memory test implementation of LyricsDao
    private class InMemoryLyricsDao : LyricsDao {
        private val storage = mutableMapOf<String, LyricsEntity>()

        override suspend fun getLyricsByKey(key: String): LyricsEntity? = storage[key]

        override fun observeLyricsByKey(key: String): Flow<LyricsEntity?> = flowOf(storage[key])

        override suspend fun insertLyrics(lyrics: LyricsEntity) {
            storage[lyrics.key] = lyrics
        }

        override suspend fun deleteLyricsByKey(key: String) {
            storage.remove(key)
        }

        override suspend fun clearAllLyrics() {
            storage.clear()
        }

        fun size(): Int = storage.size
    }

    // In-memory test implementation of SongHistoryDao
    private class InMemorySongHistoryDao : SongHistoryDao {
        private val list = mutableListOf<SongHistoryEntity>()

        override fun getRecentHistory(): Flow<List<SongHistoryEntity>> = flowOf(list)

        override suspend fun insertHistory(entry: SongHistoryEntity) {
            list.add(entry)
        }

        override suspend fun clearHistory() {
            list.clear()
        }
    }
}
