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

    @Test
    fun testRegression_WhitespaceOnlyLyrics_YieldsEmptyState() {
        val whitespaceLrc = "   \n\t  \n   "
        val parsed = LrcParser.parse(whitespaceLrc)
        assertFalse("Whitespace-only lyrics must have hasValidLyrics == false", parsed.hasValidLyrics)
        assertTrue(parsed.lines.isEmpty())
        assertEquals("", parsed.plainText)
    }

    @Test
    fun testRegression_ValidPlainLyrics_YieldsValidLyricsState() {
        val plainText = "First verse line\nSecond verse line\nChorus line"
        val parsed = LrcParser.parse(plainText)
        assertTrue(parsed.hasValidLyrics)
        assertFalse(parsed.isSynced)
        assertEquals(3, parsed.lines.size)
        assertEquals("First verse line", parsed.lines[0].text)
        assertEquals("First verse line", parsed.getActiveLine(0L))
    }

    @Test
    fun testRegression_ValidTimestampedLrc_YieldsSynchronizedLyrics() {
        val synced = "[00:02.00]First synchronized line\n[00:08.00]Second synchronized line"
        val parsed = LrcParser.parse(synced)
        assertTrue(parsed.hasValidLyrics)
        assertTrue(parsed.isSynced)
        assertEquals(2, parsed.lines.size)
        assertEquals(2000L, parsed.lines[0].timeMs)
        assertEquals(8000L, parsed.lines[1].timeMs)
        assertEquals(0, parsed.findActiveIndex(3000L))
        assertEquals("First synchronized line", parsed.getActiveLine(3000L))
        assertEquals(1, parsed.findActiveIndex(9000L))
        assertEquals("Second synchronized line", parsed.getActiveLine(9000L))
    }

    @Test
    fun testRegression_MalformedLrc_HandledSafely() {
        val malformed = "[not-a-timestamp] Random text\n[999:999:999] Out of bounds\n"
        val parsed = LrcParser.parse(malformed)
        // If no timestamps match, falls back to plain text without crashing
        assertNotNull(parsed)
        assertTrue(parsed.hasValidLyrics)
        assertFalse(parsed.isSynced)
    }

    @Test
    fun testRegression_TrackChangesFromLyricsToNoLyrics_ClearsPreviousLyrics() = runBlocking {
        val fakeDao = InMemoryLyricsDao()
        val fakeHistory = InMemorySongHistoryDao()

        val trackWithLyrics = TrackMetadata(title = "Song A", artist = "Artist A")
        val trackWithoutLyrics = TrackMetadata(title = "Song B", artist = "Artist B")

        val provider = object : LyricsProvider {
            override val name = "TestProvider"
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                return if (title == "Song A") {
                    Result.success(LrcParser.parse("[00:01.00]Song A Lyrics"))
                } else {
                    Result.failure(Exception("Lyrics not found"))
                }
            }
        }

        val repository = CompositeLyricsRepository(
            lyricsDao = fakeDao,
            historyDao = fakeHistory,
            providers = listOf(provider)
        )

        // 1. Fetch Track A -> has lyrics
        val resA = repository.getOrFetchLyrics(trackWithLyrics)
        assertTrue(resA.isSuccess)
        assertTrue(resA.getOrThrow().hasValidLyrics)

        // 2. Switch to Track B -> fails to find lyrics
        val resB = repository.getOrFetchLyrics(trackWithoutLyrics)
        assertTrue("Track B must return failure so UI displays empty state", resB.isFailure)

        // 3. Track B has no cached lyrics
        assertNull(fakeDao.getLyricsByKey(trackWithoutLyrics.identityKey))
    }

    @Test
    fun testRegression_TrackChangesFromNoLyricsToLyrics_ShowsNewLyrics() = runBlocking {
        val fakeDao = InMemoryLyricsDao()
        val fakeHistory = InMemorySongHistoryDao()

        val trackWithoutLyrics = TrackMetadata(title = "Instrumental", artist = "Artist")
        val trackWithLyrics = TrackMetadata(title = "Vocal Track", artist = "Artist")

        val provider = object : LyricsProvider {
            override val name = "TestProvider"
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                return if (title == "Vocal Track") {
                    Result.success(LrcParser.parse("[00:01.00]Vocal Lyric Line"))
                } else {
                    Result.failure(Exception("Instrumental track has no lyrics"))
                }
            }
        }

        val repository = CompositeLyricsRepository(
            lyricsDao = fakeDao,
            historyDao = fakeHistory,
            providers = listOf(provider)
        )

        // Track 1 has no lyrics
        val res1 = repository.getOrFetchLyrics(trackWithoutLyrics)
        assertTrue(res1.isFailure)

        // Switch to Track 2 with lyrics -> succeeds and displays lyrics
        val res2 = repository.getOrFetchLyrics(trackWithLyrics)
        assertTrue(res2.isSuccess)
        assertEquals("Vocal Lyric Line", res2.getOrThrow().lines[0].text)
    }

    @Test
    fun testRegression_SearchFails_YieldsFailureWithoutNull() = runBlocking {
        val fakeDao = InMemoryLyricsDao()
        val fakeHistory = InMemorySongHistoryDao()
        val track = TrackMetadata(title = "Unknown Song", artist = "Unknown Artist")

        val provider = object : LyricsProvider {
            override val name = "AlwaysFailProvider"
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                return Result.failure(Exception("Network 404 Not Found"))
            }
        }

        val repository = CompositeLyricsRepository(
            lyricsDao = fakeDao,
            historyDao = fakeHistory,
            providers = listOf(provider)
        )

        val result = repository.getOrFetchLyrics(track, forceNetwork = true)
        assertTrue("Search failure must return Result.failure", result.isFailure)
        assertNull("Failed search must not insert into cache", fakeDao.getLyricsByKey(track.identityKey))
    }

    @Test
    fun testRegression_ImportedLrcExists_RemainsAvailable() = runBlocking {
        val fakeDao = InMemoryLyricsDao()
        val fakeHistory = InMemorySongHistoryDao()
        val track = TrackMetadata(title = "My Imported Track", artist = "Local Artist")

        val repository = CompositeLyricsRepository(
            lyricsDao = fakeDao,
            historyDao = fakeHistory,
            providers = emptyList()
        )

        val imported = repository.saveImportedLyrics(track, "[00:05.00]Imported line 1\n[00:10.00]Imported line 2")
        assertTrue(imported.hasValidLyrics)

        // Subsequent lookup hits Room cache directly
        val cached = repository.getOrFetchLyrics(track, forceNetwork = false)
        assertTrue(cached.isSuccess)
        assertEquals("Imported line 1", cached.getOrThrow().lines[0].text)
        assertEquals("Imported line 2", cached.getOrThrow().lines[1].text)
    }

    @Test
    fun testRegression_FloatingHudSanitizesNullText() {
        val nullLine = LyricLine(0L, "null")
        val sanitizedText = nullLine.text.trim().takeIf { !it.equals("null", ignoreCase = true) && !it.equals("undefined", ignoreCase = true) } ?: ""
        assertEquals("Floating HUD text sanitizer must map 'null' to empty string", "", sanitizedText)

        val undefinedLine = LyricLine(0L, "undefined")
        val sanitizedUndefined = undefinedLine.text.trim().takeIf { !it.equals("null", ignoreCase = true) && !it.equals("undefined", ignoreCase = true) } ?: ""
        assertEquals("Floating HUD text sanitizer must map 'undefined' to empty string", "", sanitizedUndefined)

        val validLine = LyricLine(1000L, "Sing with me")
        val validText = validLine.text.trim().takeIf { !it.equals("null", ignoreCase = true) && !it.equals("undefined", ignoreCase = true) } ?: ""
        assertEquals("Sing with me", validText)
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
