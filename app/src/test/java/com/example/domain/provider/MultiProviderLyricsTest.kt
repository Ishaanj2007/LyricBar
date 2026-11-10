package com.example.domain.provider

import com.example.data.local.dao.LyricsDao
import com.example.data.local.dao.SongHistoryDao
import com.example.data.local.entity.LyricsEntity
import com.example.data.local.entity.SongHistoryEntity
import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata
import com.example.data.model.WordTiming
import com.example.data.repository.CompositeLyricsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.DeflaterOutputStream

class MultiProviderLyricsTest {

    // =========================================================================
    // 1. Default Provider Chain Priority & LRCLIB Preservation
    // =========================================================================

    @Test
    fun `default provider priority is LRCLIB then lrc_red then KuGou then LyricsPlus`() {
        val providers = CompositeLyricsRepository.createDefaultProviders()
        assertEquals(4, providers.size)
        assertEquals("LRCLIB", providers[0].name)
        assertEquals("lrc.red", providers[1].name)
        assertEquals("KuGou", providers[2].name)
        assertEquals("LyricsPlus", providers[3].name)
        assertTrue(providers[0] is LrcLibProvider)
        assertTrue(providers[1] is LrcRedProvider)
        assertTrue(providers[2] is KugouProvider)
        assertTrue(providers[3] is LyricsPlusProvider)
    }

    // =========================================================================
    // 2. lrc.red + BiniLyricsApi Tests
    // =========================================================================

    @Test
    fun `lrc_red valid ISRC lookup requests only dot_lrc and returns standard line-synced lyrics`() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val client = mockHttpClient { request ->
            val url = request.url.toString()
            requestedUrls.add(url)
            if (url == "https://lrc.red/s/USUM71703861.lrc") {
                httpResponse(
                    request = request,
                    code = 200,
                    body = """
                        [00:05.00]<00:05.00>First <00:05.50>line of song
                        [00:10.50]Second line of song
                        [00:16.25]Third line of song
                    """.trimIndent(),
                    contentType = "text/plain"
                )
            } else {
                httpResponse(request, 404, "Not Found")
            }
        }

        val provider = LrcRedProvider(okHttpClient = client, biniApi = null)
        val track = TrackMetadata(
            title = "HUMBLE.",
            artist = "Kendrick Lamar",
            durationSec = 177,
            isrc = "us-um7-17-03861"
        )

        val result = provider.fetchLyrics(track)
        assertTrue("Expected lrc.red lookup to succeed", result.isSuccess)
        assertEquals(listOf("https://lrc.red/s/USUM71703861.lrc"), requestedUrls)
        assertFalse("Must never request .ttml", requestedUrls.any { it.endsWith(".ttml") })

        val parsed = result.getOrThrow()
        assertTrue(parsed.isSynced)
        assertEquals("lrc.red", parsed.source)
        assertEquals(3, parsed.lines.size)
        assertEquals(5000L, parsed.lines[0].timeMs)
        assertEquals("First line of song", parsed.lines[0].text)
        assertEquals(10500L, parsed.lines[1].timeMs)
        assertEquals("Second line of song", parsed.lines[1].text)
        parsed.lines.forEach { line ->
            assertTrue("Word timing must not be populated", line.words.isEmpty())
            assertFalse("isWordSynced must be false", line.isWordSynced)
            assertEquals(0L, line.endTimeMs)
        }
    }

    @Test
    fun `lrc_red resolves missing ISRC via BiniLyricsApi identify and fetches dot_lrc`() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val client = mockHttpClient { request ->
            val url = request.url.toString()
            requestedUrls.add(url)
            when {
                url.startsWith("https://lyrics-api.binimum.org/") -> {
                    httpResponse(
                        request = request,
                        code = 200,
                        body = """
                            {
                              "results": [
                                {
                                  "track_name": "Blinding Lights",
                                  "artist_name": "The Weeknd",
                                  "album_name": "After Hours",
                                  "duration": 200,
                                  "isrc": "USUG11904206",
                                  "timing_type": "word"
                                }
                              ]
                            }
                        """.trimIndent()
                    )
                }
                url == "https://lrc.red/s/USUG11904206.lrc" -> {
                    httpResponse(
                        request = request,
                        code = 200,
                        body = "[00:12.00]I've been tryna call\n[00:15.00]I've been on my own for long enough",
                        contentType = "text/plain"
                    )
                }
                else -> httpResponse(request, 404, "")
            }
        }

        val biniApi = BiniLyricsApi(okHttpClient = client)
        val provider = LrcRedProvider(okHttpClient = client, biniApi = biniApi)
        val track = TrackMetadata(
            title = "Blinding Lights",
            artist = "The Weeknd",
            album = "After Hours",
            durationSec = 200,
            isrc = null
        )

        val result = provider.fetchLyrics(track)
        assertTrue(result.isSuccess)
        val parsed = result.getOrThrow()
        assertEquals(2, parsed.lines.size)
        assertEquals("I've been tryna call", parsed.lines[0].text)
        assertTrue(requestedUrls.any { it.startsWith("https://lyrics-api.binimum.org/") })
        assertTrue(requestedUrls.contains("https://lrc.red/s/USUG11904206.lrc"))
        assertFalse(requestedUrls.any { it.contains("ttml", ignoreCase = true) })
    }

    @Test
    fun `lrc_red rejects malformed ISRC without making network requests`() = runBlocking {
        val networkCalls = AtomicInteger(0)
        val client = mockHttpClient { request ->
            networkCalls.incrementAndGet()
            httpResponse(request, 200, "[00:01.00]Should not be reached")
        }

        val provider = LrcRedProvider(okHttpClient = client, biniApi = BiniLyricsApi(client))

        val directResult = provider.fetchLyricsForIsrc("INVALID_ISRC_123", "Title", "Artist", 180)
        assertTrue(directResult.isFailure)
        assertEquals(0, networkCalls.get())

        val trackWithMalformedIsrc = TrackMetadata(
            title = "Title",
            artist = "Artist",
            durationSec = 180,
            isrc = "BAD-ISRC"
        )
        val trackResult = provider.fetchLyrics(trackWithMalformedIsrc)
        assertTrue(trackResult.isFailure)
        assertEquals(0, networkCalls.get())
    }

    @Test
    fun `lrc_red rejects empty response, TTML content, plain text, and implausible timestamps`() = runBlocking {
        val provider = LrcRedProvider()

        // 1. Empty response
        assertTrue(provider.parseLrcResponse("", "Song", "Artist", 180).isFailure)
        assertTrue(provider.parseLrcResponse("   ", "Song", "Artist", 180).isFailure)
        assertTrue(provider.parseLrcResponse("null", "Song", "Artist", 180).isFailure)

        // 2. TTML response rejected
        val ttml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="00:01.00">Lyric</p></div></body></tt>"""
        assertTrue(provider.parseLrcResponse(ttml, "Song", "Artist", 180).isFailure)

        // 3. Unsynchronized plain text rejected
        val plain = "First line without timestamp\nSecond line without timestamp"
        assertTrue(provider.parseLrcResponse(plain, "Song", "Artist", 180).isFailure)

        // 4. Implausible timestamps exceeding track duration rejected
        val implausibleLrc = "[15:00.00]Line way past a 2-minute song\n[16:30.00]Another line"
        assertTrue(provider.parseLrcResponse(implausibleLrc, "Song", "Artist", durationSec = 120).isFailure)
    }

    // =========================================================================
    // 3. KuGou Provider Tests
    // =========================================================================

    @Test
    fun `KuGou candidate selection rejects wrong artist, wrong title, and Live or Remix version mismatches`() {
        val provider = KugouProvider()

        val candidates = listOf(
            // Wrong artist, exact title and duration
            KugouCandidate(id = "1", accesskey = "k1", duration = 200_000L, singer = "Other Artist", song = "Starboy"),
            // Wrong title, exact artist and duration
            KugouCandidate(id = "2", accesskey = "k2", duration = 200_000L, singer = "The Weeknd", song = "Different Song"),
            // Live version mismatch, exact artist and duration
            KugouCandidate(id = "3", accesskey = "k3", duration = 200_000L, singer = "The Weeknd", song = "Starboy (Live)"),
            // Remix version mismatch, exact artist and duration
            KugouCandidate(id = "4", accesskey = "k4", duration = 200_000L, singer = "The Weeknd", song = "Starboy (Kygo Remix)"),
            // Correct studio version with slightly different duration
            KugouCandidate(id = "5", accesskey = "k5", duration = 202_000L, singer = "The Weeknd", song = "Starboy")
        )

        val chosen = provider.selectBestCandidate(
            candidates = candidates,
            requestedTitle = "Starboy",
            requestedArtist = "The Weeknd",
            durationMs = 200_000L
        )
        assertNotNull(chosen)
        assertEquals("5", chosen!!.id)

        // If only wrong artist/title/version candidates exist, must return null (never accept by duration alone)
        val rejectedAll = provider.selectBestCandidate(
            candidates = candidates.take(4),
            requestedTitle = "Starboy",
            requestedArtist = "The Weeknd",
            durationMs = 200_000L
        )
        assertNull("Must reject candidates when artist, title, or version does not match", rejectedAll)

        // When requesting a Live track, must match the Live candidate and reject the studio candidate
        val chosenLive = provider.selectBestCandidate(
            candidates = candidates,
            requestedTitle = "Starboy (Live)",
            requestedArtist = "The Weeknd",
            durationMs = 200_000L
        )
        assertNotNull(chosenLive)
        assertEquals("3", chosenLive!!.id)
    }

    @Test
    fun `KuGou KRC decryption and decompression round-trip and malformed data rejection`() {
        val provider = KugouProvider()
        val rawKrc = """
            [ti:晴天]
            [ar:周杰伦]
            [offset:500]
            [0,1000]<0,500,0>作词：周杰伦
            [1000,3000]<0,800,0>故事<800,1200,0>的小黄花
            [4500,3500]<0,1000,0>从出生<1000,1500,0>那年就<2500,1000,0>飘着
        """.trimIndent()

        val encryptedBase64 = encryptToKrcBase64(rawKrc)
        val decrypted = provider.decryptKrc(encryptedBase64)
        assertEquals(rawKrc, decrypted)

        // Reject missing krc1 header
        val badHeaderBase64 = Base64.getEncoder().encodeToString("bad1somepayload".toByteArray())
        assertNull(provider.decryptKrc(badHeaderBase64))

        // Reject corrupted zlib stream
        val corruptedZlib = ByteArray(12).apply {
            this[0] = 0x6b.toByte()
            this[1] = 0x72.toByte()
            this[2] = 0x63.toByte()
            this[3] = 0x31.toByte()
        }
        assertNull(provider.decryptKrc(Base64.getEncoder().encodeToString(corruptedZlib)))
    }

    @Test
    fun `KuGou KRC line-level parsing applies offset, skips credits, preserves Unicode, and strips syllable timings`() {
        val provider = KugouProvider()
        val rawKrc = """
            [ti:晴天]
            [ar:周杰伦]
            [offset:500]
            [0,1000]<0,500,0>作词：周杰伦
            [500,1000]<0,500,0>Composer: Jay Chou
            [1000,3000]<0,800,0>故事<800,1200,0>的小黄花
            [4500,3500]<0,1000,0>从出生<1000,1500,0>那年就<2500,1000,0>飘着
            [9000,2500]<0,600,0>Hello <600,900,0>world <1500,1000,0>again
        """.trimIndent()

        val result = provider.parseKrc(rawKrc, title = "晴天", artist = "周杰伦", durationSec = 269)
        assertTrue(result.isSuccess)
        val parsed = result.getOrThrow()
        assertTrue(parsed.isSynced)
        assertEquals("KuGou", parsed.source)
        // Credit lines ("作词：周杰伦" and "Composer: Jay Chou") must be skipped
        assertEquals(3, parsed.lines.size)

        // Offset +500ms applied to [1000,3000], [4500,3500], [9000,2500]
        assertEquals(1500L, parsed.lines[0].timeMs)
        assertEquals("故事的小黄花", parsed.lines[0].text)

        assertEquals(5000L, parsed.lines[1].timeMs)
        assertEquals("从出生那年就飘着", parsed.lines[1].text)

        assertEquals(9500L, parsed.lines[2].timeMs)
        assertEquals("Hello world again", parsed.lines[2].text)

        // Verify no word/syllable timings exist on any returned LyricLine
        parsed.lines.forEach { line ->
            assertTrue("words must be empty", line.words.isEmpty())
            assertFalse("isWordSynced must be false", line.isWordSynced)
            assertEquals(0L, line.endTimeMs)
        }
    }

    // =========================================================================
    // 4. LyricsPlus Provider Tests
    // =========================================================================

    @Test
    fun `LyricsPlus parses line-level text and reconstructs syllable-only responses without syllable timings`() {
        val provider = LyricsPlusProvider()
        val jsonPayload = """
            {
              "type": "Syllable",
              "lyrics": [
                {
                  "time": 2000,
                  "duration": 3000,
                  "text": "Direct line text preferred",
                  "syllabus": [
                    { "time": 2000, "duration": 1000, "text": "Ignored " },
                    { "time": 3000, "duration": 2000, "text": "syllables" }
                  ]
                },
                {
                  "time": 6500,
                  "duration": 4000,
                  "text": "",
                  "syllabus": [
                    { "time": 6500, "duration": 400, "text": "e" },
                    { "time": 6900, "duration": 600, "text": "nough " },
                    { "time": 7500, "duration": 500, "text": "to " },
                    { "time": 8000, "duration": 800, "text": "say" }
                  ]
                }
              ]
            }
        """.trimIndent()

        val result = provider.parseResponse(jsonPayload, title = "Song", artist = "Artist", durationSec = 180)
        assertTrue(result.isSuccess)
        val parsed = result.getOrThrow()
        assertTrue(parsed.isSynced)
        assertEquals("LyricsPlus", parsed.source)
        assertEquals(2, parsed.lines.size)

        assertEquals(2000L, parsed.lines[0].timeMs)
        assertEquals("Direct line text preferred", parsed.lines[0].text)

        assertEquals(6500L, parsed.lines[1].timeMs)
        assertEquals("enough to say", parsed.lines[1].text)

        parsed.lines.forEach { line ->
            assertTrue("LyricsPlus must not populate words/syllables", line.words.isEmpty())
            assertFalse(line.isWordSynced)
            assertEquals(0L, line.endTimeMs)
        }
    }

    @Test
    fun `LyricsPlus rejects missing timestamps and empty responses`() {
        val provider = LyricsPlusProvider()

        // Missing timestamp on a line
        val missingTimestampJson = """
            {
              "lyrics": [
                { "text": "Line without timestamp" }
              ]
            }
        """.trimIndent()
        assertTrue(provider.parseResponse(missingTimestampJson, "Song", "Artist", 180).isFailure)

        // Empty lyrics array
        val emptyArrayJson = """{ "lyrics": [] }"""
        assertTrue(provider.parseResponse(emptyArrayJson, "Song", "Artist", 180).isFailure)

        // Empty body
        assertTrue(provider.parseResponse("", "Song", "Artist", 180).isFailure)
    }

    @Test
    fun `LyricsPlus concurrent mirror fallback, lastGood preference, and slow mirror cancellation`() = runBlocking {
        val cancelledMirrors = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                val host = "${request.url.scheme}://${request.url.host}"
                when (host) {
                    "https://mirror1.example.com" -> {
                        // Immediate 404 failure
                        httpResponse(request, 404, "Not found")
                    }
                    "https://mirror2.example.com" -> {
                        // Valid fast response
                        httpResponse(
                            request = request,
                            code = 200,
                            body = """
                                {
                                  "lyrics": [
                                    { "time": 1000, "text": "Line from mirror 2" },
                                    { "time": 4000, "text": "Second line from mirror 2" }
                                  ]
                                }
                            """.trimIndent()
                        )
                    }
                    "https://mirror3.example.com" -> {
                        // Slow mirror that should be cancelled via Call.cancel() when mirror2 wins
                        for (i in 0 until 100) {
                            if (chain.call().isCanceled()) {
                                synchronized(cancelledMirrors) {
                                    cancelledMirrors.add(host)
                                }
                                throw IOException("Canceled")
                            }
                            Thread.sleep(20)
                        }
                        httpResponse(request, 200, """{"lyrics":[{"time":1000,"text":"Late line"}]}""")
                    }
                    else -> httpResponse(request, 500, "Error")
                }
            })
            .build()

        val mirrors = listOf(
            "https://mirror1.example.com",
            "https://mirror2.example.com",
            "https://mirror3.example.com"
        )
        val provider = LyricsPlusProvider(okHttpClient = client, mirrors = mirrors)

        val result = provider.fetchLyrics(title = "My Song", artist = "My Artist", durationSec = 180)
        assertTrue("Expected fallback mirror2 to succeed", result.isSuccess)
        assertEquals("Line from mirror 2", result.getOrThrow().lines[0].text)

        // Verify lastGood mirror preference updated to mirror2
        assertEquals("https://mirror2.example.com", provider.getLastGoodMirror())
        assertEquals("https://mirror2.example.com", provider.orderedMirrors().first())

        // Allow brief moment for interceptor loop to observe Call.isCanceled()
        delay(60)
        synchronized(cancelledMirrors) {
            assertTrue("Slow mirror3 should have been cancelled", cancelledMirrors.contains("https://mirror3.example.com"))
        }
    }

    // =========================================================================
    // 5. CompositeLyricsRepository Fallback Chain & Caching Tests
    // =========================================================================

    @Test
    fun `LRCLIB success stops chain without calling fallback providers`() = runBlocking {
        val callOrder = mutableListOf<String>()
        val lrclib = fakeSyncedProvider("LRCLIB", callOrder, shouldSucceed = true)
        val lrcRed = fakeSyncedProvider("lrc.red", callOrder, shouldSucceed = true)
        val kugou = fakeSyncedProvider("KuGou", callOrder, shouldSucceed = true)
        val lyricsPlus = fakeSyncedProvider("LyricsPlus", callOrder, shouldSucceed = true)

        val dao = InMemoryLyricsDao()
        val repo = CompositeLyricsRepository(
            lyricsDao = dao,
            historyDao = NoOpHistoryDao(),
            providers = listOf(lrclib, lrcRed, kugou, lyricsPlus)
        )

        val track = TrackMetadata(title = "Song A", artist = "Artist A", durationSec = 180)
        val result = repo.getOrFetchLyrics(track)

        assertTrue(result.isSuccess)
        assertEquals("LRCLIB", result.getOrThrow().source)
        assertEquals(listOf("LRCLIB"), callOrder)
    }

    @Test
    fun `lrc_red success stops chain when LRCLIB fails`() = runBlocking {
        val callOrder = mutableListOf<String>()
        val lrclib = fakeSyncedProvider("LRCLIB", callOrder, shouldSucceed = false)
        val lrcRed = fakeSyncedProvider("lrc.red", callOrder, shouldSucceed = true)
        val kugou = fakeSyncedProvider("KuGou", callOrder, shouldSucceed = true)
        val lyricsPlus = fakeSyncedProvider("LyricsPlus", callOrder, shouldSucceed = true)

        val dao = InMemoryLyricsDao()
        val repo = CompositeLyricsRepository(
            lyricsDao = dao,
            historyDao = NoOpHistoryDao(),
            providers = listOf(lrclib, lrcRed, kugou, lyricsPlus)
        )

        val track = TrackMetadata(title = "Song B", artist = "Artist B", durationSec = 180)
        val result = repo.getOrFetchLyrics(track)

        assertTrue(result.isSuccess)
        assertEquals("lrc.red", result.getOrThrow().source)
        assertEquals(listOf("LRCLIB", "lrc.red"), callOrder)
    }

    @Test
    fun `KuGou success stops chain when LRCLIB and lrc_red fail`() = runBlocking {
        val callOrder = mutableListOf<String>()
        val lrclib = fakeSyncedProvider("LRCLIB", callOrder, shouldSucceed = false)
        val lrcRed = fakeSyncedProvider("lrc.red", callOrder, shouldSucceed = false)
        val kugou = fakeSyncedProvider("KuGou", callOrder, shouldSucceed = true)
        val lyricsPlus = fakeSyncedProvider("LyricsPlus", callOrder, shouldSucceed = true)

        val dao = InMemoryLyricsDao()
        val repo = CompositeLyricsRepository(
            lyricsDao = dao,
            historyDao = NoOpHistoryDao(),
            providers = listOf(lrclib, lrcRed, kugou, lyricsPlus)
        )

        val track = TrackMetadata(title = "Song C", artist = "Artist C", durationSec = 180)
        val result = repo.getOrFetchLyrics(track)

        assertTrue(result.isSuccess)
        assertEquals("KuGou", result.getOrThrow().source)
        assertEquals(listOf("LRCLIB", "lrc.red", "KuGou"), callOrder)
    }

    @Test
    fun `LyricsPlus succeeds as final fallback and caches formatted LRC for subsequent Room cache hits`() = runBlocking {
        val callOrder = mutableListOf<String>()
        val lrclib = fakeSyncedProvider("LRCLIB", callOrder, shouldSucceed = false)
        val lrcRed = fakeSyncedProvider("lrc.red", callOrder, shouldSucceed = false)
        val kugou = fakeSyncedProvider("KuGou", callOrder, shouldSucceed = false)
        val lyricsPlus = fakeSyncedProvider("LyricsPlus", callOrder, shouldSucceed = true)

        val dao = InMemoryLyricsDao()
        val repo = CompositeLyricsRepository(
            lyricsDao = dao,
            historyDao = NoOpHistoryDao(),
            providers = listOf(lrclib, lrcRed, kugou, lyricsPlus)
        )

        val track = TrackMetadata(title = "Song D", artist = "Artist D", durationSec = 180)
        val result = repo.getOrFetchLyrics(track)

        assertTrue(result.isSuccess)
        assertEquals("LyricsPlus", result.getOrThrow().source)
        assertEquals(listOf("LRCLIB", "lrc.red", "KuGou", "LyricsPlus"), callOrder)

        // Verify cached entity has valid synced LRC and round-trips without network calls
        val cachedResult = repo.getOrFetchLyrics(track, forceNetwork = false, allowNetwork = false)
        assertTrue(cachedResult.isSuccess)
        val cachedLyrics = cachedResult.getOrThrow()
        assertTrue(cachedLyrics.isSynced)
        assertEquals(2, cachedLyrics.lines.size)
        assertEquals("First synced line from LyricsPlus", cachedLyrics.lines[0].text)
        assertEquals("LyricsPlus", cachedLyrics.source)
        assertEquals("LyricsPlus", cachedLyrics.displaySource)
    }

    @Test
    fun `plain-text response is rejected in favor of synchronized fallback provider`() = runBlocking {
        val callOrder = mutableListOf<String>()
        val plainTextLrclib = object : LyricsProvider {
            override val name: String = "LRCLIB"
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                callOrder.add(name)
                return Result.success(
                    ParsedLyrics(
                        title = title,
                        artist = artist,
                        lines = listOf(
                            LyricLine(0L, "Unsynced line 1"),
                            LyricLine(0L, "Unsynced line 2")
                        ),
                        plainText = "Unsynced line 1\nUnsynced line 2",
                        rawContent = "Unsynced line 1\nUnsynced line 2",
                        isSynced = false,
                        source = name
                    )
                )
            }
        }
        val syncedKugou = fakeSyncedProvider("KuGou", callOrder, shouldSucceed = true)

        val dao = InMemoryLyricsDao()
        val repo = CompositeLyricsRepository(
            lyricsDao = dao,
            historyDao = NoOpHistoryDao(),
            providers = listOf(plainTextLrclib, syncedKugou)
        )

        val track = TrackMetadata(title = "Synced Needed", artist = "Artist", durationSec = 180)
        val result = repo.getOrFetchLyrics(track)

        assertTrue(result.isSuccess)
        val lyrics = result.getOrThrow()
        assertTrue(lyrics.isSynced)
        assertEquals("KuGou", lyrics.source)
        assertEquals(listOf("LRCLIB", "KuGou"), callOrder)
    }

    @Test
    fun `all providers failing or returning invalid results preserves no-lyrics state and caches nothing`() = runBlocking {
        val callOrder = mutableListOf<String>()
        val ttmlProvider = object : LyricsProvider {
            override val name: String = "lrc.red"
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                callOrder.add(name)
                return Result.success(
                    ParsedLyrics(
                        title = title,
                        artist = artist,
                        lines = listOf(LyricLine(1000L, "<tt>bad</tt>")),
                        plainText = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body></body></tt>",
                        rawContent = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body></body></tt>",
                        isSynced = true,
                        source = name
                    )
                )
            }
        }
        val failingProvider = fakeSyncedProvider("KuGou", callOrder, shouldSucceed = false)

        val dao = InMemoryLyricsDao()
        val repo = CompositeLyricsRepository(
            lyricsDao = dao,
            historyDao = NoOpHistoryDao(),
            providers = listOf(ttmlProvider, failingProvider)
        )

        val track = TrackMetadata(title = "Missing Song", artist = "Missing Artist", durationSec = 180)
        val result = repo.getOrFetchLyrics(track)

        assertTrue("Expected failure when all providers fail or return invalid formats", result.isFailure)
        assertNull("Nothing should be cached on failure", dao.getLyricsByKey(track.identityKey))
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun fakeSyncedProvider(
        providerName: String,
        callOrder: MutableList<String>,
        shouldSucceed: Boolean
    ): LyricsProvider = object : LyricsProvider {
        override val name: String = providerName
        override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
            callOrder.add(providerName)
            if (!shouldSucceed) {
                return Result.failure(NoSuchElementException("$providerName found no lyrics"))
            }
            val lines = listOf(
                LyricLine(
                    timeMs = 1500L,
                    text = "First synced line from $providerName",
                    endTimeMs = 3000L,
                    words = listOf(WordTiming("First", 1500L, 2000L)),
                    isWordSynced = true
                ),
                LyricLine(
                    timeMs = 5000L,
                    text = "Second synced line from $providerName"
                )
            )
            return Result.success(
                ParsedLyrics(
                    title = title,
                    artist = artist,
                    lines = lines,
                    plainText = lines.joinToString("\n") { it.text },
                    rawContent = "",
                    isSynced = true,
                    source = providerName
                )
            )
        }
    }

    private fun encryptToKrcBase64(plainKrc: String): String {
        val compressed = ByteArrayOutputStream().use { baos ->
            DeflaterOutputStream(baos).use { dos ->
                dos.write(plainKrc.toByteArray(Charsets.UTF_8))
            }
            baos.toByteArray()
        }
        val encrypted = ByteArray(compressed.size + 4)
        encrypted[0] = 0x6b.toByte() // 'k'
        encrypted[1] = 0x72.toByte() // 'r'
        encrypted[2] = 0x63.toByte() // 'c'
        encrypted[3] = 0x31.toByte() // '1'
        for (i in compressed.indices) {
            encrypted[i + 4] = (compressed[i].toInt() xor KugouProvider.KRC_KEY[i % 16]).toByte()
        }
        return Base64.getEncoder().encodeToString(encrypted)
    }

    private fun mockHttpClient(handler: (okhttp3.Request) -> Response): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                handler(chain.request())
            })
            .build()
    }

    private fun httpResponse(
        request: okhttp3.Request,
        code: Int,
        body: String,
        contentType: String = "application/json"
    ): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body(body.toResponseBody(contentType.toMediaType()))
            .build()
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
