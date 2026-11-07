package com.example.domain.parser

import com.example.data.local.dao.LyricsDao
import com.example.data.local.dao.SongHistoryDao
import com.example.data.local.entity.LyricsEntity
import com.example.data.local.entity.SongHistoryEntity
import com.example.data.model.TrackMetadata
import com.example.data.repository.CompositeLyricsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

class LrcImportSafetyTest {

    @Test
    fun testValidUtf8Lrc() {
        val lrc = """
            [ti:Perfect]
            [ar:Ed Sheeran]
            [00:03.00]I found a love for me
            [00:11.50]Darling just dive right in
        """.trimIndent()

        val bytes = lrc.toByteArray(StandardCharsets.UTF_8)
        val decoded = LrcImporter.decodeBytesSafely(bytes)
        assertEquals(lrc, decoded)

        val parsed = LrcParser.parse(decoded)
        assertTrue(parsed.hasValidLyrics)
        assertTrue(parsed.isSynced)
        assertEquals(2, parsed.lines.size)
        assertEquals(3000L, parsed.lines[0].timeMs)
        assertEquals("I found a love for me", parsed.lines[0].text)
    }

    @Test
    fun testUtf8WithBomRemoval() {
        // UTF-8 BOM is 0xEF, 0xBB, 0xBF
        val content = "[00:01.00]First line with BOM\n[00:04.00]Second line"
        val rawBytes = content.toByteArray(StandardCharsets.UTF_8)
        val bomBytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + rawBytes

        val decoded = LrcImporter.decodeBytesSafely(bomBytes)
        assertFalse("Decoded text must not start with BOM character", decoded.startsWith("\uFEFF"))
        assertTrue("Decoded text must start with timestamp tag", decoded.startsWith("[00:01.00]"))

        val parsed = LrcParser.parse(decoded)
        assertTrue(parsed.hasValidLyrics)
        assertEquals(2, parsed.lines.size)
        assertEquals("First line with BOM", parsed.lines[0].text)
        assertEquals(1000L, parsed.lines[0].timeMs)
    }

    @Test
    fun testUtf16LittleEndianWithBom() {
        val content = "[00:02.50]UTF-16LE Encoded Lyric"
        val utf16LeBytes = content.toByteArray(StandardCharsets.UTF_16LE)
        val bomBytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + utf16LeBytes

        val decoded = LrcImporter.decodeBytesSafely(bomBytes)
        assertEquals(content, decoded)

        val parsed = LrcParser.parse(decoded)
        assertTrue(parsed.hasValidLyrics)
        assertEquals("UTF-16LE Encoded Lyric", parsed.lines[0].text)
        assertEquals(2500L, parsed.lines[0].timeMs)
    }

    @Test
    fun testUtf16BigEndianWithBom() {
        val content = "[00:05.00]UTF-16BE Encoded Lyric"
        val utf16BeBytes = content.toByteArray(StandardCharsets.UTF_16BE)
        val bomBytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + utf16BeBytes

        val decoded = LrcImporter.decodeBytesSafely(bomBytes)
        assertEquals(content, decoded)

        val parsed = LrcParser.parse(decoded)
        assertTrue(parsed.hasValidLyrics)
        assertEquals("UTF-16BE Encoded Lyric", parsed.lines[0].text)
        assertEquals(5000L, parsed.lines[0].timeMs)
    }

    @Test
    fun testHindiAndUnicodeCombiningCharactersPreserved() {
        // Hindi lyrics (Aashiqui 2 - Tum Hi Ho) with Devanagari combining marks & matras
        val hindiLrc = """
            [00:01.00]हम तेरे बिन अब रह नहीं सकते
            [00:05.50]तेरे बिना क्या वजूद मेरा
            [00:10.00]तुझसे जुदा गर हो जाएँगे
            [00:14.20]तो खुद से ही हो जाएँगे जुदा
            [00:18.00]क्योंकि तुम ही हो, अब तुम ही हो
        """.trimIndent()

        val bytes = hindiLrc.toByteArray(StandardCharsets.UTF_8)
        val decoded = LrcImporter.decodeBytesSafely(bytes)
        assertEquals(hindiLrc, decoded)

        val parsed = LrcParser.parse(decoded)
        assertTrue("Hindi lyrics must be detected as valid lyrics", parsed.hasValidLyrics)
        assertEquals(5, parsed.lines.size)
        assertEquals("हम तेरे बिन अब रह नहीं सकते", parsed.lines[0].text)
        assertEquals("क्योंकि तुम ही हो, अब तुम ही हो", parsed.lines[4].text)
    }

    @Test
    fun testEmptyFile_ThrowsEmptyFileException() {
        val emptyStream = ByteArrayInputStream(ByteArray(0))
        try {
            LrcImporter.readLrcFromStream(emptyStream)
            fail("Empty stream must throw LrcImportException.EmptyFile")
        } catch (e: LrcImportException.EmptyFile) {
            assertEquals("Lyric file is empty", e.message)
        }
    }

    @Test
    fun testWhitespaceOnlyFile_ThrowsEmptyFileException() {
        val whitespaceBytes = "   \n\t  \r\n   ".toByteArray(StandardCharsets.UTF_8)
        val stream = ByteArrayInputStream(whitespaceBytes)
        try {
            LrcImporter.readLrcFromStream(stream)
            fail("Whitespace stream must throw LrcImportException.EmptyFile")
        } catch (e: LrcImportException.EmptyFile) {
            assertEquals("Lyric file is empty", e.message)
        }
    }

    @Test
    fun testMetadataOnlyFile_ThrowsMetadataOnlyException() = runBlocking {
        val fakeDao = InMemoryLyricsDao()
        val fakeHistory = InMemorySongHistoryDao()
        val repository = CompositeLyricsRepository(fakeDao, fakeHistory, emptyList())
        val track = TrackMetadata(title = "Track", artist = "Artist")

        val metadataOnly = """
            [ti:Song Title]
            [ar:Artist Name]
            [al:Album Name]
            [by:LRC Creator]
            [offset:0]
        """.trimIndent()

        try {
            repository.saveImportedLyrics(track, metadataOnly)
            fail("Metadata-only file must throw LrcImportException.MetadataOnly")
        } catch (e: LrcImportException.MetadataOnly) {
            assertTrue(e.message.contains("metadata tags", ignoreCase = true))
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("metadata tags", ignoreCase = true) || e.message!!.contains("No valid lyrics", ignoreCase = true))
        }

        assertEquals("No entity should be saved for metadata-only file", 0, fakeDao.size())
    }

    @Test
    fun testInvalidTimestamps_SecondsOutOfRange_SkippedSafely() {
        val lrc = """
            [00:10.00]Valid line 1
            [01:99.00]Invalid seconds line
            [00:20.00]Valid line 2
        """.trimIndent()

        val parsed = LrcParser.parse(lrc)
        assertTrue(parsed.hasValidLyrics)
        // [01:99.00] has second 99 >= 60, skipped from parsed timestamped lines
        assertEquals(2, parsed.lines.size)
        assertEquals("Valid line 1", parsed.lines[0].text)
        assertEquals(10000L, parsed.lines[0].timeMs)
        assertEquals("Valid line 2", parsed.lines[1].text)
        assertEquals(20000L, parsed.lines[1].timeMs)
    }

    @Test
    fun testMultipleTimestampsPerLine_CreatesMultipleLines() {
        val lrc = """
            [00:05.00][00:15.00][00:25.00]Repeated Chorus Line
        """.trimIndent()

        val parsed = LrcParser.parse(lrc)
        assertTrue(parsed.hasValidLyrics)
        assertTrue(parsed.isSynced)
        assertEquals(3, parsed.lines.size)
        assertEquals(5000L, parsed.lines[0].timeMs)
        assertEquals(15000L, parsed.lines[1].timeMs)
        assertEquals(25000L, parsed.lines[2].timeMs)
        assertEquals("Repeated Chorus Line", parsed.lines[0].text)
        assertEquals("Repeated Chorus Line", parsed.lines[1].text)
        assertEquals("Repeated Chorus Line", parsed.lines[2].text)
    }

    @Test
    fun testOffsetMetadata_ParsedCorrectly() {
        val positiveOffset = "[offset:+1200]\n[00:04.00]Offset lyric"
        val parsedPos = LrcParser.parse(positiveOffset)
        assertEquals(1200L, parsedPos.offsetMs)

        val negativeOffset = "[offset:-800]\n[00:04.00]Offset lyric"
        val parsedNeg = LrcParser.parse(negativeOffset)
        assertEquals(-800L, parsedNeg.offsetMs)
    }

    @Test
    fun testOversizedStream_ThrowsFileTooLargeException() {
        // Test bounded reader with a small artificial limit (e.g. 500 bytes)
        val oversizedData = ByteArray(600) { 'a'.code.toByte() }
        val stream = ByteArrayInputStream(oversizedData)

        try {
            LrcImporter.readLrcFromStream(stream, maxBytes = 500L)
            fail("Oversized stream must throw LrcImportException.FileTooLarge")
        } catch (e: LrcImportException.FileTooLarge) {
            assertTrue(e.message.contains("too large", ignoreCase = true))
        }
    }

    @Test
    fun testOversizedContentInRepository_ThrowsFileTooLargeException() = runBlocking {
        val fakeDao = InMemoryLyricsDao()
        val fakeHistory = InMemorySongHistoryDao()
        val repository = CompositeLyricsRepository(fakeDao, fakeHistory, emptyList())
        val track = TrackMetadata(title = "Track", artist = "Artist")

        // 1.1 MB string exceeding MAX_LRC_FILE_SIZE_BYTES
        val hugeContent = "[00:01.00]" + "x".repeat(1024 * 1024 + 100)

        try {
            repository.saveImportedLyrics(track, hugeContent)
            fail("Oversized string content must throw FileTooLarge")
        } catch (e: LrcImportException.FileTooLarge) {
            assertTrue(e.message.contains("too large", ignoreCase = true))
        }
        assertEquals(0, fakeDao.size())
    }

    @Test
    fun testStreamReadFailure_ThrowsFileAccessFailed() {
        val failingStream = object : InputStream() {
            override fun read(): Int = throw IOException("Simulated disk read error")
        }

        try {
            LrcImporter.readLrcFromStream(failingStream)
            fail("Stream error must throw LrcImportException.FileAccessFailed")
        } catch (e: LrcImportException.FileAccessFailed) {
            assertTrue(e.message.contains("reading lyric file", ignoreCase = true) || e.message.contains("access", ignoreCase = true))
        }
    }

    @Test
    fun testPartiallyMalformedLrc_ParsesValidLinesGracefully() {
        val partiallyMalformed = """
            [ti:Test Track]
            Malformed unbracketed header line
            [00:02.00]Valid first line
            [invalid:time] Malformed timestamp
            [00:06.50]Valid second line
        """.trimIndent()

        val parsed = LrcParser.parse(partiallyMalformed)
        assertTrue("File with valid timestamped lines must parse successfully", parsed.hasValidLyrics)
        assertTrue(parsed.isSynced)
        assertEquals(2, parsed.lines.size)
        assertEquals("Valid first line", parsed.lines[0].text)
        assertEquals(2000L, parsed.lines[0].timeMs)
        assertEquals("Valid second line", parsed.lines[1].text)
        assertEquals(6500L, parsed.lines[1].timeMs)
    }

    @Test
    fun testExistingImportedLyricsPreserved_AfterFailedReplacement() = runBlocking {
        val fakeDao = InMemoryLyricsDao()
        val fakeHistory = InMemorySongHistoryDao()
        val repository = CompositeLyricsRepository(fakeDao, fakeHistory, emptyList())
        val track = TrackMetadata(title = "Bohemian Rhapsody", artist = "Queen", durationSec = 354)

        // 1. Initial valid import
        val initialLrc = "[00:01.00]Is this the real life?\n[00:05.00]Is this just fantasy?"
        val initialParsed = repository.saveImportedLyrics(track, initialLrc, source = "Valid LRC")
        assertEquals(2, initialParsed.lines.size)
        assertEquals(1, fakeDao.size())

        val storedInitial = fakeDao.getLyricsByKey(track.identityKey)
        assertNotNull(storedInitial)
        assertEquals(initialLrc, storedInitial!!.syncedLrc)

        // 2. User attempts to replace with an empty file -> FAILS
        try {
            repository.saveImportedLyrics(track, "   ")
            fail("Empty replacement must fail")
        } catch (_: Exception) {}

        // Verify previous lyrics were preserved in database
        val afterEmptyAttempt = fakeDao.getLyricsByKey(track.identityKey)
        assertNotNull("Previous lyrics must not be deleted on empty replacement failure", afterEmptyAttempt)
        assertEquals(initialLrc, afterEmptyAttempt!!.syncedLrc)

        // 3. User attempts to replace with a binary file -> FAILS
        try {
            repository.saveImportedLyrics(track, "RIFF\u0000WAVE")
            fail("Binary replacement must fail")
        } catch (_: Exception) {}

        // Verify previous lyrics still preserved
        val afterBinaryAttempt = fakeDao.getLyricsByKey(track.identityKey)
        assertEquals(initialLrc, afterBinaryAttempt!!.syncedLrc)

        // 4. User attempts to replace with a metadata-only file -> FAILS
        try {
            repository.saveImportedLyrics(track, "[ti:Title]\n[ar:Artist]\n[al:Album]")
            fail("Metadata-only replacement must fail")
        } catch (_: Exception) {}

        // Verify previous lyrics still completely intact
        val afterMetadataAttempt = fakeDao.getLyricsByKey(track.identityKey)
        assertEquals(initialLrc, afterMetadataAttempt!!.syncedLrc)

        // 5. Query via getOrFetchLyrics confirms old valid lyrics still returned
        val loaded = repository.getOrFetchLyrics(track, forceNetwork = false).getOrThrow()
        assertEquals("Is this the real life?", loaded.lines[0].text)
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
