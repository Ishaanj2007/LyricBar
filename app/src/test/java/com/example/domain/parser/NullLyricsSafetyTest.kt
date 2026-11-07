package com.example.domain.parser

import com.example.data.local.dao.LyricsDao
import com.example.data.local.dao.SongHistoryDao
import com.example.data.local.entity.LyricsEntity
import com.example.data.local.entity.SongHistoryEntity
import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata
import com.example.data.repository.CompositeLyricsRepository
import com.example.domain.provider.LyricsProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NullLyricsSafetyTest {

    @Test
    fun testLrcParser_LiteralNullString_YieldsEmptyLyricsWithNoValidContent() {
        val parsed = LrcParser.parse("null", title = "Empty Track", artist = "Artist")
        assertFalse("Parsed lyrics must not have valid lyrics for literal 'null'", parsed.hasValidLyrics)
        assertTrue("Lines list must be empty for 'null' content", parsed.lines.isEmpty())
        assertEquals("Plain text must be empty string", "", parsed.plainText)
        assertEquals(-1, parsed.findActiveIndex(1000L))
        assertNull(parsed.getActiveLine(1000L))
        assertNull(parsed.getActiveLyricLine(1000L))
    }

    @Test
    fun testLrcParser_WhitespacePaddedNullAndUndefined_YieldsEmptyLyrics() {
        val variations = listOf("   null   ", "null\n", "\nnull\n", "undefined", "  undefined  ", "\tundefined\n")
        for (variant in variations) {
            val parsed = LrcParser.parse(variant)
            assertFalse("Variant '$variant' must have hasValidLyrics == false", parsed.hasValidLyrics)
            assertTrue("Variant '$variant' must produce empty lines", parsed.lines.isEmpty())
            assertEquals("", parsed.plainText)
        }
    }

    @Test
    fun testLrcParser_BlankAndEmptyString_YieldsEmptyLyrics() {
        val emptyParsed = LrcParser.parse("")
        assertFalse(emptyParsed.hasValidLyrics)
        assertTrue(emptyParsed.lines.isEmpty())
        assertEquals("", emptyParsed.plainText)

        val whitespaceParsed = LrcParser.parse("   \n\t  \n  ")
        assertFalse(whitespaceParsed.hasValidLyrics)
        assertTrue(whitespaceParsed.lines.isEmpty())
        assertEquals("", whitespaceParsed.plainText)
    }

    @Test
    fun testLrcParser_SyncedLinesWithNullText_Sanitized() {
        val lrc = """
            [00:01.00]null
            [00:03.00]undefined
            [00:05.00]Actual Sung Lyric Line
        """.trimIndent()

        val parsed = LrcParser.parse(lrc)
        assertTrue("Has valid lyrics because one line is valid", parsed.hasValidLyrics)
        assertTrue(parsed.isSynced)
        assertEquals(3, parsed.lines.size)
        // First two lines must not have literal "null" or "undefined" as text
        assertEquals("", parsed.lines[0].text)
        assertEquals("", parsed.lines[1].text)
        assertEquals("Actual Sung Lyric Line", parsed.lines[2].text)

        // Seeking to first two lines must not return "null" as active line
        assertNull(parsed.getActiveLine(1500L))
        assertNull(parsed.getActiveLine(3500L))
        assertEquals("Actual Sung Lyric Line", parsed.getActiveLine(6000L))
    }

    @Test
    fun testLrcParser_PlainLinesContainingNullAndUndefined_FilteredOut() {
        val plain = """
            null
            undefined
            First Real Lyric Line
            null
            Second Real Lyric Line
        """.trimIndent()

        val parsed = LrcParser.parse(plain)
        assertTrue(parsed.hasValidLyrics)
        assertFalse(parsed.isSynced)
        assertEquals(2, parsed.lines.size)
        assertEquals("First Real Lyric Line", parsed.lines[0].text)
        assertEquals("Second Real Lyric Line", parsed.lines[1].text)
    }

    @Test
    fun testParsedLyrics_ModelDirectNullSafety() {
        val corruptedLyrics = ParsedLyrics(
            title = "Test",
            artist = "Artist",
            isSynced = false,
            lines = listOf(LyricLine(0L, "null")),
            plainText = "null",
            source = "Corrupted"
        )

        assertFalse("corruptedLyrics must report hasValidLyrics == false", corruptedLyrics.hasValidLyrics)
        assertEquals(-1, corruptedLyrics.findActiveIndex(0L))
        assertNull(corruptedLyrics.getActiveLine(0L))
        assertNull(corruptedLyrics.getActiveLyricLine(0L))
    }

    @Test
    fun testCleanLyricsStringExtraction_NullAndUndefinedHandled() {
        fun cleanLyricsString(raw: Any?): String? {
            if (raw == null) return null
            val str = raw.toString().trim()
            return if (str.isEmpty() || str.equals("null", ignoreCase = true) || str.equals("undefined", ignoreCase = true)) {
                null
            } else {
                str
            }
        }

        assertNull(cleanLyricsString(null))
        assertNull(cleanLyricsString("null"))
        assertNull(cleanLyricsString("  null  "))
        assertNull(cleanLyricsString("NULL"))
        assertNull(cleanLyricsString("undefined"))
        assertNull(cleanLyricsString("  undefined  "))
        assertNull(cleanLyricsString(""))
        assertNull(cleanLyricsString("   "))
        assertEquals("Song", cleanLyricsString("Song"))
        assertEquals("Real lyrics line", cleanLyricsString("  Real lyrics line  "))
    }

    @Test
    fun testCompositeLyricsRepository_PoisonedCachePurgedAndRejected() = runBlocking {
        val fakeDao = InMemoryLyricsDao()
        val fakeHistory = InMemorySongHistoryDao()
        val track = TrackMetadata(title = "Yellow", artist = "Coldplay", durationSec = 269)

        // Seed database with poisoned cache entry where plainLyrics is literal "null"
        val poisonedEntity = LyricsEntity(
            key = track.identityKey,
            title = "Yellow",
            artist = "Coldplay",
            syncedLrc = null,
            plainLyrics = "null",
            isSynced = false,
            durationSec = 269,
            source = "PoisonedProvider"
        )
        fakeDao.insertLyrics(poisonedEntity)
        assertEquals(1, fakeDao.size())

        var networkCalled = false
        val provider = object : LyricsProvider {
            override val name: String = "TestProvider"
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                networkCalled = true
                return Result.failure(Exception("Lyrics not available"))
            }
        }

        val repository = CompositeLyricsRepository(
            lyricsDao = fakeDao,
            historyDao = fakeHistory,
            providers = listOf(provider)
        )

        val result = repository.getOrFetchLyrics(track, forceNetwork = false, allowNetwork = true)

        // Result must be failure, NOT returning literal "null" lyrics
        assertTrue("getOrFetchLyrics must fail when cached lyrics are poisoned and remote fails", result.isFailure)
        assertTrue("Network must be attempted because cached entry was rejected", networkCalled)
        // Poisoned entry must be purged from database
        assertNull("Poisoned cache entry must be deleted from database", fakeDao.getLyricsByKey(track.identityKey))
        assertEquals(0, fakeDao.size())
    }

    @Test
    fun testCompositeLyricsRepository_SaveImportedLyricsRejectsNullFile() = runBlocking {
        val fakeDao = InMemoryLyricsDao()
        val fakeHistory = InMemorySongHistoryDao()
        val track = TrackMetadata(title = "Song", artist = "Artist")

        val repository = CompositeLyricsRepository(
            lyricsDao = fakeDao,
            historyDao = fakeHistory,
            providers = emptyList()
        )

        try {
            repository.saveImportedLyrics(track, "null", source = "Imported")
            fail("Saving 'null' as imported lyrics must throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("No valid lyrics", ignoreCase = true))
        }

        try {
            repository.saveImportedLyrics(track, "   undefined   ", source = "Imported")
            fail("Saving 'undefined' as imported lyrics must throw IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("No valid lyrics", ignoreCase = true))
        }

        assertEquals("No invalid lyrics should be persisted in database", 0, fakeDao.size())
    }

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

    private class InMemorySongHistoryDao : SongHistoryDao {
        private val list = mutableListOf<SongHistoryEntity>()

        override fun getRecentHistory(): Flow<List<SongHistoryEntity>> = flowOf(list)

        override suspend fun insertHistory(entry: SongHistoryEntity) {
            list.add(entry)
        }

        override suspend fun deleteHistoryById(id: Long) {
            list.removeAll { it.id == id }
        }

        override suspend fun clearHistory() {
            list.clear()
        }
    }
}
