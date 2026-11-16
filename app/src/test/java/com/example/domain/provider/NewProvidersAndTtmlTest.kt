package com.example.domain.provider

import com.example.data.model.LyricLine
import com.example.data.model.LyricsAttribution
import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata
import com.example.domain.parser.LrcParser
import com.example.domain.parser.TtmlParser
import com.example.ui.screens.resolveUnisonAttributionDisplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class NewProvidersAndTtmlTest {

    // =========================================================================
    // 1. TtmlParser Tests
    // =========================================================================

    @Test
    fun `TtmlParser converts rich word-span TTML to clean line-synced ParsedLyrics preserving word joins and clock formats`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <head>
                <metadata>
                  <ttm:title>Leaked Title Must Be Ignored</ttm:title>
                </metadata>
              </head>
              <body dur="3:30.000">
                <div>
                  <p begin="0:12.500" end="0:16.200">
                    <span begin="0:12.500" end="0:13.000">No</span><span begin="0:13.000" end="0:13.500">thing</span>
                    <span begin="0:13.600" end="0:14.200">compares</span>
                    <span ttm:role="x-translation">Should be skipped</span>
                    <span ttm:role="x-roman">Skipped roman</span>
                  </p>
                  <p begin="45.2s" end="49s">Second line in seconds</p>
                  <p begin="62000ms" end="65000ms">Third line in milliseconds</p>
                  <p begin="01:02:05.25" end="01:02:08.00">Long clock line</p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val attribution = LyricsAttribution.forUnison("vid123", "CuratorOne", "key99")
        val result = TtmlParser.parseToLineSynced(
            ttmlXml = ttml,
            title = "Test Song",
            artist = "Test Artist",
            source = "Unison",
            attribution = attribution
        )

        assertTrue(result.isSuccess)
        val parsed = result.getOrThrow()
        assertTrue(parsed.isSynced)
        assertFalse(parsed.isWordSynced)
        assertEquals("Unison", parsed.source)
        assertEquals("LRC", parsed.format)
        assertEquals(4, parsed.lines.size)

        assertEquals(12_500L, parsed.lines[0].timeMs)
        assertEquals("Nothing compares", parsed.lines[0].text)
        assertFalse(parsed.lines[0].text.contains("Should be skipped"))
        assertFalse(parsed.lines[0].text.contains("Skipped roman"))

        assertEquals(45_200L, parsed.lines[1].timeMs)
        assertEquals("Second line in seconds", parsed.lines[1].text)

        assertEquals(62_000L, parsed.lines[2].timeMs)
        assertEquals("Third line in milliseconds", parsed.lines[2].text)

        assertEquals(((1 * 3600L) + (2 * 60L) + 5L) * 1000L + 250L, parsed.lines[3].timeMs)
        assertEquals("Long clock line", parsed.lines[3].text)

        parsed.lines.forEach { line ->
            assertTrue("Word timing must stay disabled", line.words.isEmpty())
            assertFalse(line.isWordSynced)
            assertEquals(0L, line.endTimeMs)
        }

        // Formatted rawContent must re-parse back into identical attribution and lines via LrcParser
        val reparsed = LrcParser.parse(parsed.rawContent, "Test Song", "Test Artist", "Unison")
        assertEquals(4, reparsed.lines.size)
        assertEquals("Nothing compares", reparsed.lines[0].text)
        assertEquals("https://unison.betterlyrics.org/song/vid123", reparsed.attribution?.songUrl)
        assertEquals("CuratorOne", reparsed.attribution?.submitterName)
        assertEquals("https://unison.betterlyrics.org/curator/key99", reparsed.attribution?.curatorUrl)
    }

    @Test
    fun `TtmlParser safely rejects malformed XML, missing timestamps, and non-ttml payloads`() {
        val malformed = "<tt><body><p begin=\"00:01.00\">Unclosed"
        assertTrue(TtmlParser.parseToLineSynced(malformed, "Song", "Artist", "Unison").isFailure)

        val unsyncedTtml = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p>Plain line without begin</p></div></body></tt>"
        assertTrue(TtmlParser.parseToLineSynced(unsyncedTtml, "Song", "Artist", "Unison").isFailure)

        assertTrue(TtmlParser.parseToLineSynced("   ", "Song", "Artist", "Unison").isFailure)
        assertTrue(TtmlParser.parseToLineSynced("<html><body>bad</body></html>", "Song", "Artist", "Unison").isFailure)
    }

    // =========================================================================
    // 2. LyricaProvider Tests
    // =========================================================================

    @Test
    fun `LyricaProvider parses synchronized LRC and timed_lyrics fallback while preserving Unicode scripts`() = runBlocking {
        val client = mockHttpClient { request ->
            val url = request.url.toString()
            assertTrue(url.contains("/lyrics/"))
            assertEquals("true", request.url.queryParameter("timestamps"))
            assertEquals("false", request.url.queryParameter("word"))

            httpResponse(
                request = request,
                code = 200,
                body = """
                    {
                      "status": "success",
                      "data": {
                        "title": "Tum Hi Ho",
                        "artist": "Arijit Singh",
                        "duration": 262,
                        "hasTimestamps": true,
                        "instrumental": false,
                        "lyrics": "[00:11.20]<00:11.20>Hum tere bin <00:13.00>ab reh nahi sakte\n[00:16.50]तुम ही हो",
                        "source": "lrclib"
                      }
                    }
                """.trimIndent()
            )
        }

        val provider = LyricaProvider(okHttpClient = client)
        val result = provider.fetchLyrics(
            TrackMetadata(title = "Tum Hi Ho (From \"Aashiqui 2\")", artist = "Arijit Singh", durationSec = 262)
        )

        assertTrue(result.isSuccess)
        val parsed = result.getOrThrow()
        assertEquals("Lyrica", parsed.source)
        assertTrue(parsed.isSynced)
        assertEquals(2, parsed.lines.size)
        assertEquals(11_200L, parsed.lines[0].timeMs)
        assertEquals("Hum tere bin ab reh nahi sakte", parsed.lines[0].text)
        assertEquals("तुम ही हो", parsed.lines[1].text)
        parsed.lines.forEach {
            assertTrue(it.words.isEmpty())
            assertFalse(it.isWordSynced)
        }
    }

    @Test
    fun `LyricaProvider parses timed_lyrics array fallback and rejects plain text, instrumental, 429, HTML, and mismatches`() {
        val provider = LyricaProvider()

        val timedFallbackJson = """
            {
              "status": "success",
              "data": {
                "title": "Kesariya",
                "artist": "Arijit Singh",
                "duration": 268,
                "hasTimestamps": true,
                "instrumental": false,
                "lyrics": null,
                "timed_lyrics": [
                  { "start_time": 12000, "end_time": 16000, "text": "Mujhko itna bataye koi" },
                  { "start_time": 16500, "end_time": 21000, "text": "Kaise tujhse dil na lagaye koi" }
                ]
              }
            }
        """.trimIndent()
        val timedResult = provider.parseResponse(timedFallbackJson, "Kesariya", "Arijit Singh", 268)
        assertTrue(timedResult.isSuccess)
        assertEquals(2, timedResult.getOrThrow().lines.size)
        assertEquals(12_000L, timedResult.getOrThrow().lines[0].timeMs)

        val plainJson = """
            {
              "status": "success",
              "data": {
                "title": "Kesariya",
                "artist": "Arijit Singh",
                "hasTimestamps": false,
                "lyrics": "Line 1 without timestamps\nLine 2 without timestamps"
              }
            }
        """.trimIndent()
        assertTrue(provider.parseResponse(plainJson, "Kesariya", "Arijit Singh", 268).isFailure)

        val instrumentalJson = """
            {
              "status": "success",
              "data": {
                "title": "Kesariya",
                "artist": "Arijit Singh",
                "instrumental": true,
                "hasTimestamps": true,
                "lyrics": "[00:10.00]Instrumental"
              }
            }
        """.trimIndent()
        assertTrue(provider.parseResponse(instrumentalJson, "Kesariya", "Arijit Singh", 268).isFailure)

        val wrongVersionJson = """
            {
              "status": "success",
              "data": {
                "title": "Kesariya (Live)",
                "artist": "Arijit Singh",
                "hasTimestamps": true,
                "lyrics": "[00:10.00]Live line\n[00:15.00]Second line"
              }
            }
        """.trimIndent()
        assertTrue(provider.parseResponse(wrongVersionJson, "Kesariya", "Arijit Singh", 268).isFailure)

        assertTrue(provider.parseResponse("<html>502 Bad Gateway</html>", "Kesariya", "Arijit Singh", 268).isFailure)

        val rateLimitedClient = mockHttpClient { req -> httpResponse(req, 429, """{"error":"rate limited"}""") }
        runBlocking {
            assertTrue(LyricaProvider(rateLimitedClient).fetchLyrics("Kesariya", "Arijit Singh", 268).isFailure)
        }
    }

    // =========================================================================
    // 3. LiriQoProvider Tests
    // =========================================================================

    @Test
    fun `LiriQoProvider skips mismatched primary remix and selects matching studio candidate from tracks`() = runBlocking {
        val requestedUrls = mutableListOf<String>()
        val client = mockHttpClient { request ->
            requestedUrls.add(request.url.toString())
            httpResponse(
                request = request,
                code = 200,
                body = """
                    {
                      "videoId": "abc123",
                      "metadata": { "title": "Tum Hi Ho", "artist": "Arijit Singh", "duration": 262 },
                      "primary": {
                        "provider": "lyricsplus",
                        "syncLevel": "line",
                        "title": "Tum Hi Ho (Remix)",
                        "artist": "Arijit Singh",
                        "duration": 260,
                        "timed": [
                          { "start": 1000, "text": "Remix line 1", "isInstrumental": false },
                          { "start": 4000, "text": "Remix line 2", "isInstrumental": false }
                        ]
                      },
                      "tracks": [
                        {
                          "provider": "musixmatch",
                          "syncLevel": "word",
                          "title": "Tum Hi Ho",
                          "artist": "Arijit Singh",
                          "duration": 262,
                          "timed": [
                            { "start": 10000, "text": "Studio instrumental", "isInstrumental": true },
                            { "start": 12000, "text": "Hum tere bin ab reh nahi sakte", "isInstrumental": false },
                            { "start": 17000, "text": "Tere bina kya wajood mera", "isInstrumental": false }
                          ]
                        }
                      ]
                    }
                """.trimIndent()
            )
        }

        val provider = LiriQoProvider(okHttpClient = client)
        val result = provider.fetchLyrics(
            TrackMetadata(title = "Tum Hi Ho", artist = "Arijit Singh", durationSec = 262)
        )

        assertTrue(result.isSuccess)
        val parsed = result.getOrThrow()
        assertEquals("LiriQo", parsed.source)
        assertEquals(2, parsed.lines.size)
        assertEquals("Hum tere bin ab reh nahi sakte", parsed.lines[0].text)
        assertEquals(12_000L, parsed.lines[0].timeMs)
        assertTrue(requestedUrls.first().contains("fast=1"))
    }

    @Test
    fun `LiriQoProvider rejects plain syncLevel, mismatched artist, and 429`() {
        val provider = LiriQoProvider()
        val plainOnlyJson = """
            {
              "primary": {
                "syncLevel": "plain",
                "title": "Lemon",
                "artist": "Kenshi Yonezu",
                "plain": "夢ならばどれほどよかったでしょう"
              },
              "tracks": []
            }
        """.trimIndent()
        assertTrue(provider.parseResponse(plainOnlyJson, "Lemon", "Kenshi Yonezu", 255).isFailure)

        val wrongArtistJson = """
            {
              "primary": {
                "syncLevel": "line",
                "title": "Lemon",
                "artist": "Completely Different Singer",
                "lrc": "[00:10.00]Line 1\n[00:15.00]Line 2"
              }
            }
        """.trimIndent()
        assertTrue(provider.parseResponse(wrongArtistJson, "Lemon", "Kenshi Yonezu", 255).isFailure)

        val rateClient = mockHttpClient { req -> httpResponse(req, 429, """{"error":"Too many requests"}""") }
        runBlocking {
            assertTrue(LiriQoProvider(rateClient).fetchLyrics("Lemon", "Kenshi Yonezu", 255).isFailure)
        }
    }

    // =========================================================================
    // 4. UnisonProvider Tests
    // =========================================================================

    @Test
    fun `UnisonProvider handles TTML response, attribution, 304 ETag cache hit, 503 pending retry, and search fallback`() = runBlocking {
        val hits = AtomicInteger(0)
        val client = mockHttpClient { request ->
            val path = request.url.encodedPath
            when {
                path == "/lyrics" && request.url.queryParameter("song") == "Idol" -> {
                    val attempt = hits.incrementAndGet()
                    when (attempt) {
                        1 -> httpResponse(
                            request = request,
                            code = 503,
                            body = """{"success":false,"error":"LOOKUP_PENDING","code":"LOOKUP_PENDING"}""",
                            headers = mapOf("Retry-After" to "0")
                        )
                        2 -> httpResponse(
                            request = request,
                            code = 200,
                            body = """
                                {
                                  "success": true,
                                  "data": {
                                    "videoId": "ZRtdQ81jPUQ",
                                    "song": "Idol",
                                    "artist": "YOASOBI",
                                    "duration": 213,
                                    "format": "ttml",
                                    "syncType": "word",
                                    "lyrics": "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"0:05.100\"><span begin=\"0:05.100\">無敵の</span><span begin=\"0:06.000\">笑顔で</span></p><p begin=\"0:09.200\">誰かを惹きつける</p></div></body></tt>",
                                    "submitter": {
                                      "keyId": "curator_yoasobi",
                                      "displayName": "Mina"
                                    }
                                  }
                                }
                            """.trimIndent(),
                            headers = mapOf("ETag" to "\"etag-idol-1\"")
                        )
                        else -> {
                            assertEquals("\"etag-idol-1\"", request.header("If-None-Match"))
                            httpResponse(request, 304, "")
                        }
                    }
                }
                path == "/lyrics" && request.url.queryParameter("song") == "Missing Direct" -> {
                    httpResponse(request, 404, """{"success":false,"error":"NOT_FOUND"}""")
                }
                path == "/lyrics/search" -> {
                    httpResponse(
                        request = request,
                        code = 200,
                        body = """
                            {
                              "success": true,
                              "data": [
                                {
                                  "videoId": "vid_search_99",
                                  "song": "Missing Direct",
                                  "artist": "Tester",
                                  "duration": 180,
                                  "format": "lrc",
                                  "syncType": "line",
                                  "confidence": "high",
                                  "matchScore": 0.95
                                }
                              ]
                            }
                        """.trimIndent()
                    )
                }
                path == "/lyrics" && request.url.queryParameter("v") == "vid_search_99" -> {
                    httpResponse(
                        request = request,
                        code = 200,
                        body = """
                            {
                              "success": true,
                              "data": {
                                "videoId": "vid_search_99",
                                "song": "Missing Direct",
                                "artist": "Tester",
                                "duration": 180,
                                "format": "lrc",
                                "syncType": "line",
                                "lyrics": "[00:04.00]Search hit line 1\n[00:08.50]Search hit line 2",
                                "submitter": {
                                  "keyId": null,
                                  "displayName": "null"
                                }
                              }
                            }
                        """.trimIndent()
                    )
                }
                else -> httpResponse(request, 404, "{}")
            }
        }

        val provider = UnisonProvider(okHttpClient = client)

        // First call: 503 LOOKUP_PENDING -> retried automatically -> 200 TTML with full attribution
        val firstResult = provider.fetchLyrics(
            TrackMetadata(title = "Idol", artist = "YOASOBI", durationSec = 213)
        )
        assertTrue(firstResult.isSuccess)
        val firstParsed = firstResult.getOrThrow()
        assertEquals("Unison", firstParsed.source)
        assertEquals(2, firstParsed.lines.size)
        assertEquals("無敵の笑顔で", firstParsed.lines[0].text)
        assertEquals("https://unison.betterlyrics.org/song/ZRtdQ81jPUQ", firstParsed.attribution?.songUrl)
        assertEquals("Mina", firstParsed.attribution?.submitterName)
        assertEquals("https://unison.betterlyrics.org/curator/curator_yoasobi", firstParsed.attribution?.curatorUrl)

        // Second call: sends If-None-Match ETag and handles 304 Not Modified transparently
        val cached304Result = provider.fetchLyrics(
            TrackMetadata(title = "Idol", artist = "YOASOBI", durationSec = 213)
        )
        assertTrue(cached304Result.isSuccess)
        assertEquals("無敵の笑顔で", cached304Result.getOrThrow().lines[0].text)

        // Fallback search -> ?v=vid_search_99, safely handling literal "null" submitter fields
        val searchFallback = provider.fetchLyrics("Missing Direct", "Tester", 180)
        assertTrue(searchFallback.isSuccess)
        val searchParsed = searchFallback.getOrThrow()
        assertEquals("Search hit line 1", searchParsed.lines[0].text)
        assertEquals("https://unison.betterlyrics.org/song/vid_search_99", searchParsed.attribution?.songUrl)
        assertNull("Literal 'null' submitter name must normalize to null", searchParsed.attribution?.submitterName)
        assertNull("Missing keyId must yield null curatorUrl", searchParsed.attribution?.curatorUrl)
    }

    @Test
    fun `UnisonProvider rejects plain format and handles coroutine cancellation`() = runBlocking {
        val plainProvider = UnisonProvider()
        val plainJson = """
            {
              "success": true,
              "data": {
                "videoId": "plain_1",
                "song": "Plain Song",
                "artist": "Plain Artist",
                "format": "plain",
                "syncType": "plain",
                "lyrics": "Just plain lines\nNo sync"
              }
            }
        """.trimIndent()
        assertTrue(plainProvider.parseLyricsResponse(plainJson, "Plain Song", "Plain Artist", 180).isFailure)

        // Cancellation test
        val slowClient = mockHttpClient { request ->
            for (i in 0 until 50) {
                if (Thread.currentThread().isInterrupted) break
                Thread.sleep(20)
            }
            httpResponse(request, 200, "{}")
        }
        val cancelProvider = UnisonProvider(okHttpClient = slowClient)
        val job = launch {
            try {
                cancelProvider.fetchLyrics("Song", "Artist", 180)
            } catch (_: CancellationException) {
                // Expected cancellation propagation
            }
        }
        job.cancel()
        job.join()
    }

    // =========================================================================
    // 5. Unison Attribution Display Resolution Tests
    // =========================================================================

    @Test
    fun `resolveUnisonAttributionDisplay handles Unison vs non-Unison and prevents literal null or undefined`() {
        val nonUnison = ParsedLyrics(
            title = "Song",
            artist = "Artist",
            isSynced = true,
            lines = listOf(LyricLine(1000L, "Hello")),
            plainText = "Hello",
            source = "LRCLIB"
        )
        assertNull(resolveUnisonAttributionDisplay(nonUnison))

        val fullUnison = ParsedLyrics(
            title = "Song",
            artist = "Artist",
            isSynced = true,
            lines = listOf(LyricLine(1000L, "Hello")),
            plainText = "Hello",
            source = "Unison",
            attribution = LyricsAttribution(
                sourceName = "Unison",
                songUrl = "https://unison.betterlyrics.org/song/xyz123",
                submitterName = "Rohan",
                curatorUrl = "https://unison.betterlyrics.org/curator/key7"
            )
        )
        val resolvedFull = resolveUnisonAttributionDisplay(fullUnison)
        assertNotNull(resolvedFull)
        assertEquals("https://unison.betterlyrics.org/song/xyz123", resolvedFull!!.songUrl)
        assertEquals("Rohan", resolvedFull.submitterName)
        assertEquals("https://unison.betterlyrics.org/curator/key7", resolvedFull.curatorUrl)

        val partialUnison = ParsedLyrics(
            title = "Song",
            artist = "Artist",
            isSynced = true,
            lines = listOf(LyricLine(1000L, "Hello")),
            plainText = "Hello",
            source = "Unison",
            attribution = LyricsAttribution(
                sourceName = "Unison",
                songUrl = "undefined",
                submitterName = "null",
                curatorUrl = "https://unison.betterlyrics.org/curator/ignored"
            )
        )
        val resolvedPartial = resolveUnisonAttributionDisplay(partialUnison)
        assertNotNull(resolvedPartial)
        assertEquals("https://unison.betterlyrics.org", resolvedPartial!!.songUrl)
        assertNull(resolvedPartial.submitterName)
        assertNull(resolvedPartial.curatorUrl)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap()
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "HTTP $code")
            .body(body.toResponseBody(contentType.toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }
}
