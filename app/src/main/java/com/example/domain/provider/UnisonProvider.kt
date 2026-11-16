package com.example.domain.provider

import com.example.data.model.LyricLine
import com.example.data.model.LyricsAttribution
import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata
import com.example.domain.parser.LrcParser
import com.example.domain.parser.TtmlParser
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Provider 7: Unison (`https://docs.betterlyrics.org/unison`) — Community-contributed
 * synchronized LRC and TTML lyrics database.
 *
 * Features:
 * - Lookup via `GET /lyrics` (passing `isrc` when valid alongside `song` and `artist`, plus `album` and `duration`),
 *   followed by `GET /lyrics/search` + `GET /lyrics?v=<videoId>` discovery fallback when direct lookup misses.
 * - ETag / `If-None-Match` (`304 Not Modified`) caching per endpoint URL.
 * - Bounded `503 LOOKUP_PENDING` retry honoring `Retry-After`, plus graceful `429` handling.
 * - Safe TTML-to-line-synced conversion and LRC normalization (never accepts plain text).
 * - Preserves full Unison attribution (`videoId`, `submitter.displayName`, `submitter.keyId`) for UI credits & links.
 */
class UnisonProvider(
    okHttpClient: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL
) : LyricsProvider {

    override val name: String = "Unison"

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private data class EtagCachedResult(
        val etag: String,
        val parsedLyrics: ParsedLyrics
    )

    internal data class SearchCandidate(
        val videoId: String,
        val song: String,
        val artist: String,
        val durationSec: Int?,
        val format: String,
        val syncType: String,
        val confidenceRank: Int,
        val score: Double
    )

    private val etagCache = ConcurrentHashMap<String, EtagCachedResult>()

    override suspend fun fetchLyrics(
        track: TrackMetadata,
        cleanTitle: String,
        cleanArtist: String
    ): Result<ParsedLyrics> {
        val rawTitle = TrackMetadata.cleanMediaClutter(track.title).ifBlank { cleanTitle }
        val rawArtist = TrackMetadata.cleanMediaClutter(track.artist).ifBlank { cleanArtist }
        return fetchLyricsInternal(
            title = rawTitle,
            artist = rawArtist,
            durationSec = track.durationSec.takeIf { it > 0 },
            album = track.album.trim().takeIf { it.isNotBlank() },
            isrc = LyricsMatchingUtils.normalizeIsrc(track.isrc)
        )
    }

    override suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSec: Int?
    ): Result<ParsedLyrics> = fetchLyricsInternal(
        title = title,
        artist = artist,
        durationSec = durationSec,
        album = null,
        isrc = null
    )

    private suspend fun fetchLyricsInternal(
        title: String,
        artist: String,
        durationSec: Int?,
        album: String?,
        isrc: String?
    ): Result<ParsedLyrics> = withContext(Dispatchers.IO) {
        try {
            val cleanedTitle = LyricsMatchingUtils.cleanTrackTitle(title)
            val cleanedArtist = LyricsMatchingUtils.cleanArtistName(artist)
            val queryTitle = LyricsMatchingUtils.stripLeadingArtistPrefix(cleanedTitle, cleanedArtist)
                .ifBlank { cleanedTitle }

            if (queryTitle.isBlank() || cleanedArtist.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Title and artist are required for Unison"))
            }

            // 1. Direct lookup via GET /lyrics
            val directBuilder = "${baseUrl.trimEnd('/')}/lyrics".toHttpUrlOrNull()?.newBuilder()
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid Unison URL"))

            if (!isrc.isNullOrBlank()) {
                directBuilder.addQueryParameter("isrc", isrc)
            }
            directBuilder.addQueryParameter("song", queryTitle)
            directBuilder.addQueryParameter("artist", cleanedArtist)
            if (!album.isNullOrBlank()) {
                directBuilder.addQueryParameter("album", album)
            }
            if (durationSec != null && durationSec > 0) {
                directBuilder.addQueryParameter("duration", durationSec.toString())
            }

            val directResult = executeLyricsEndpointWithEtagAndRetry(
                url = directBuilder.build().toString(),
                requestedTitle = title,
                requestedArtist = artist,
                durationSec = durationSec
            )
            if (directResult.isSuccess) {
                return@withContext directResult
            }

            // Do not hammer search if rate-limited
            if (directResult.exceptionOrNull() is UnisonRateLimitedException) {
                return@withContext directResult
            }

            // 2. Fallback: Discovery search via GET /lyrics/search?q=... -> fetch by videoId
            val searchBuilder = "${baseUrl.trimEnd('/')}/lyrics/search".toHttpUrlOrNull()?.newBuilder()
                ?: return@withContext directResult
            searchBuilder.addQueryParameter("q", "$queryTitle $cleanedArtist")
            searchBuilder.addQueryParameter("limit", "10")

            val searchReq = Request.Builder()
                .url(searchBuilder.build())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            val searchHttp = client.newCall(searchReq).awaitUnisonResponse()
                ?: return@withContext directResult

            if (searchHttp.code == 429) {
                return@withContext Result.failure(UnisonRateLimitedException())
            }
            if (searchHttp.code !in 200..299) {
                return@withContext directResult
            }

            val bestCandidate = selectBestSearchCandidate(
                jsonBody = searchHttp.body,
                requestedTitle = title,
                requestedArtist = artist,
                durationSec = durationSec
            ) ?: return@withContext Result.failure(NoSuchElementException("No matching Unison search candidate"))

            val videoUrl = "${baseUrl.trimEnd('/')}/lyrics".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("v", bestCandidate.videoId)
                ?.build()
                ?.toString()
                ?: return@withContext directResult

            executeLyricsEndpointWithEtagAndRetry(
                url = videoUrl,
                requestedTitle = title,
                requestedArtist = artist,
                durationSec = durationSec
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun executeLyricsEndpointWithEtagAndRetry(
        url: String,
        requestedTitle: String,
        requestedArtist: String,
        durationSec: Int?
    ): Result<ParsedLyrics> {
        val maxAttempts = 2
        for (attempt in 1..maxAttempts) {
            val cached = etagCache[url]
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()

            if (cached != null) {
                requestBuilder.header("If-None-Match", cached.etag)
            }

            val http = client.newCall(requestBuilder.build()).awaitUnisonResponse()
                ?: return Result.failure(IOException("Unison network request failed"))

            when (http.code) {
                304 -> {
                    if (cached != null) {
                        return Result.success(cached.parsedLyrics)
                    }
                    return Result.failure(IOException("Unison returned 304 without cached entry"))
                }
                429 -> return Result.failure(UnisonRateLimitedException())
                503 -> {
                    val isPending = isLookupPending(http.body)
                    if (isPending && attempt < maxAttempts) {
                        val waitMs = ((http.retryAfterSec ?: 1L) * 1000L).coerceIn(300L, 1500L)
                        delay(waitMs)
                        continue
                    }
                    return Result.failure(IOException("Unison temporarily unavailable (HTTP 503)"))
                }
                !in 200..299 -> {
                    return Result.failure(NoSuchElementException("Unison returned HTTP ${http.code}"))
                }
                else -> {
                    val parsed = parseLyricsResponse(
                        jsonBody = http.body,
                        requestedTitle = requestedTitle,
                        requestedArtist = requestedArtist,
                        durationSec = durationSec
                    )
                    if (parsed.isSuccess && !http.etag.isNullOrBlank()) {
                        etagCache[url] = EtagCachedResult(http.etag, parsed.getOrThrow())
                    }
                    return parsed
                }
            }
        }
        return Result.failure(IOException("Unison lookup timed out"))
    }

    internal fun selectBestSearchCandidate(
        jsonBody: String,
        requestedTitle: String,
        requestedArtist: String,
        durationSec: Int?
    ): SearchCandidate? {
        val raw = jsonBody.trim()
        if (raw.isEmpty() || raw.startsWith("<")) return null
        return try {
            val root = JsonParser.parseString(raw).asJsonObject
            if (root.optBooleanOrNull("success") == false) return null
            val array = root.getAsJsonArray("data") ?: return null
            val candidates = mutableListOf<SearchCandidate>()

            for (el in array) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val videoId = obj.optCleanString("videoId") ?: continue
                val format = obj.optCleanString("format")?.lowercase() ?: continue
                val syncType = obj.optCleanString("syncType")?.lowercase().orEmpty()
                if (format == "plain" || syncType == "plain") continue
                if (format != "lrc" && format != "ttml") continue

                val candSong = obj.optCleanString("song") ?: continue
                val candArtist = obj.optCleanString("artist") ?: continue
                val candDuration = obj.optIntOrNull("duration")

                if (!isTrackMatch(requestedTitle, requestedArtist, durationSec, candSong, candArtist, candDuration)) {
                    continue
                }

                val confidenceRank = when (obj.optCleanString("confidence")?.lowercase()) {
                    "high" -> 3
                    "medium" -> 2
                    "low" -> 1
                    else -> 0
                }
                val score = (obj.optDoubleOrNull("matchScore") ?: 0.0) +
                    (obj.optDoubleOrNull("effectiveScore") ?: 0.0)

                candidates.add(
                    SearchCandidate(
                        videoId = videoId,
                        song = candSong,
                        artist = candArtist,
                        durationSec = candDuration,
                        format = format,
                        syncType = syncType,
                        confidenceRank = confidenceRank,
                        score = score
                    )
                )
            }

            candidates
                .sortedWith(
                    compareByDescending<SearchCandidate> { it.confidenceRank }
                        .thenByDescending { it.score }
                )
                .firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseLyricsResponse(
        jsonBody: String,
        requestedTitle: String,
        requestedArtist: String,
        durationSec: Int? = null
    ): Result<ParsedLyrics> {
        val raw = jsonBody.trim()
        if (raw.isEmpty() || raw.equals("null", ignoreCase = true) || raw.equals("undefined", ignoreCase = true)) {
            return Result.failure(NoSuchElementException("Empty Unison response"))
        }
        if (raw.startsWith("<")) {
            return Result.failure(IllegalArgumentException("Unexpected HTML/XML response from Unison"))
        }

        return try {
            val rootEl = JsonParser.parseString(raw)
            if (!rootEl.isJsonObject) {
                return Result.failure(IllegalArgumentException("Invalid JSON object from Unison"))
            }
            val root = rootEl.asJsonObject
            if (root.optBooleanOrNull("success") == false) {
                val errMsg = root.optCleanString("error") ?: "Lookup failed"
                return Result.failure(NoSuchElementException("Unison: $errMsg"))
            }

            val data = root.optJsonObject("data")
                ?: return Result.failure(NoSuchElementException("Missing data object in Unison response"))

            if (data.optBooleanOrNull("hidden") == true) {
                return Result.failure(NoSuchElementException("Unison entry is hidden"))
            }

            val format = data.optCleanString("format")?.lowercase().orEmpty()
            val syncType = data.optCleanString("syncType")?.lowercase().orEmpty()
            if (format == "plain" || syncType == "plain") {
                return Result.failure(IllegalArgumentException("Unsynchronized Unison response rejected"))
            }

            val candSong = data.optCleanString("song")
            val candArtist = data.optCleanString("artist")
            val candDuration = data.optIntOrNull("duration")
            if (!isTrackMatch(
                    requestedTitle = requestedTitle,
                    requestedArtist = requestedArtist,
                    requestedDurationSec = durationSec,
                    candTitle = candSong,
                    candArtist = candArtist,
                    candDurationSec = candDuration
                )
            ) {
                return Result.failure(NoSuchElementException("Unison response failed track/artist/version match"))
            }

            val lyricsPayload = data.optRawStringPreserveWhitespace("lyrics")
                ?.takeIf {
                    it.isNotBlank() &&
                        !it.trim().equals("null", ignoreCase = true) &&
                        !it.trim().equals("undefined", ignoreCase = true)
                }
                ?: return Result.failure(NoSuchElementException("Empty Unison lyrics content"))

            val videoId = data.optCleanString("videoId")
            val submitterObj = data.optJsonObject("submitter")
            val submitterDisplayName = submitterObj?.optCleanString("displayName")
            val submitterKeyId = submitterObj?.optCleanString("keyId")
            val attribution = LyricsAttribution.forUnison(
                videoId = videoId,
                submitterDisplayName = submitterDisplayName,
                submitterKeyId = submitterKeyId
            )

            val resolvedTitle = candSong ?: requestedTitle
            val resolvedArtist = candArtist ?: requestedArtist

            when {
                format == "ttml" || LrcParser.isTtml(lyricsPayload) -> {
                    TtmlParser.parseToLineSynced(
                        ttmlXml = lyricsPayload,
                        title = resolvedTitle,
                        artist = resolvedArtist,
                        source = name,
                        durationSec = durationSec,
                        attribution = attribution
                    )
                }
                format == "lrc" || LrcParser.hasTimestampTags(lyricsPayload) -> {
                    val parsedLrc = LrcParser.parse(
                        content = lyricsPayload,
                        title = resolvedTitle,
                        artist = resolvedArtist,
                        source = name
                    )
                    if (!parsedLrc.isSynced) {
                        return Result.failure(IllegalArgumentException("Unsynchronized LRC from Unison rejected"))
                    }
                    val cleanLines = parsedLrc.lines.mapNotNull { line ->
                        val cleanText = LyricsMatchingUtils.stripInlineWordTags(line.text)
                        if (isValidLyricText(cleanText)) {
                            LyricLine(
                                timeMs = line.timeMs,
                                text = cleanText,
                                endTimeMs = 0L,
                                words = emptyList(),
                                isWordSynced = false
                            )
                        } else {
                            null
                        }
                    }.sortedBy { it.timeMs }

                    if (!LyricsMatchingUtils.hasValidSyncedLines(cleanLines, durationSec)) {
                        return Result.failure(NoSuchElementException("Unison LRC failed timestamp plausibility check"))
                    }

                    Result.success(
                        ParsedLyrics(
                            title = resolvedTitle,
                            artist = resolvedArtist,
                            isSynced = true,
                            lines = cleanLines,
                            plainText = cleanLines.joinToString("\n") { it.text },
                            source = name,
                            offsetMs = parsedLrc.offsetMs,
                            isWordSynced = false,
                            format = "LRC",
                            rawContent = LrcParser.formatLrc(cleanLines, offsetMs = parsedLrc.offsetMs, attribution = attribution),
                            attribution = attribution
                        )
                    )
                }
                else -> {
                    Result.failure(IllegalArgumentException("Unsupported Unison format: $format"))
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isLookupPending(body: String): Boolean {
        return try {
            val obj = JsonParser.parseString(body.trim()).asJsonObject
            obj.optCleanString("code").equals("LOOKUP_PENDING", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun isTrackMatch(
        requestedTitle: String,
        requestedArtist: String,
        requestedDurationSec: Int?,
        candTitle: String?,
        candArtist: String?,
        candDurationSec: Int?
    ): Boolean {
        if (!candTitle.isNullOrBlank()) {
            if (!LyricsMatchingUtils.sameVersion(requestedTitle, candTitle)) return false
            val cleanReqTitle = LyricsMatchingUtils.cleanTrackTitle(requestedTitle)
            val cleanCandTitle = LyricsMatchingUtils.cleanTrackTitle(candTitle)
            val cleanCandArtist = LyricsMatchingUtils.cleanArtistName(candArtist.orEmpty())
            val candNoPrefix = LyricsMatchingUtils.stripLeadingArtistPrefix(cleanCandTitle, cleanCandArtist)
            val matched = LyricsMatchingUtils.titlesMatch(cleanReqTitle, cleanCandTitle) ||
                LyricsMatchingUtils.titlesMatch(cleanReqTitle, candNoPrefix)
            if (!matched) return false
        }

        if (!candArtist.isNullOrBlank() && requestedArtist.isNotBlank()) {
            if (!LyricsMatchingUtils.artistMatches(requestedArtist, candArtist)) return false
        }

        if (requestedDurationSec != null && requestedDurationSec > 0 && candDurationSec != null && candDurationSec > 0) {
            val toleranceSec = maxOf(25, requestedDurationSec / 2)
            if (abs(requestedDurationSec - candDurationSec) > toleranceSec) return false
        }
        return true
    }

    private fun isValidLyricText(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() &&
            !t.equals("null", ignoreCase = true) &&
            !t.equals("undefined", ignoreCase = true)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://unison.betterlyrics.org"
        private const val USER_AGENT = "LyricBar/2.5.0 (Android)"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}

private class UnisonRateLimitedException : IOException("Unison rate limit exceeded (HTTP 429)")

private data class UnisonHttpResult(
    val code: Int,
    val body: String,
    val etag: String?,
    val retryAfterSec: Long?
)

private suspend fun Call.awaitUnisonResponse(): UnisonHttpResult? = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation {
        try {
            cancel()
        } catch (_: Exception) {}
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resume(null)
        }

        override fun onResponse(call: Call, response: Response) {
            response.use { resp ->
                val body = try {
                    resp.body?.string().orEmpty()
                } catch (_: Exception) {
                    ""
                }
                val etag = resp.header("ETag")
                val retryAfter = resp.header("Retry-After")?.trim()?.toLongOrNull()
                if (cont.isActive) {
                    cont.resume(UnisonHttpResult(resp.code, body, etag, retryAfter))
                }
            }
        }
    })
}

private fun JsonObject.optJsonObject(key: String): JsonObject? {
    val el = get(key) ?: return null
    return if (el.isJsonObject) el.asJsonObject else null
}

private fun JsonObject.optRawStringPreserveWhitespace(key: String): String? {
    val el = get(key) ?: return null
    if (el.isJsonNull || !el.isJsonPrimitive) return null
    return try {
        el.asString
    } catch (_: Exception) {
        null
    }
}

private fun JsonObject.optCleanString(key: String): String? {
    val el = get(key) ?: return null
    if (el.isJsonNull || !el.isJsonPrimitive) return null
    val str = el.asString.trim()
    return if (str.isEmpty() || str.equals("null", ignoreCase = true) || str.equals("undefined", ignoreCase = true)) {
        null
    } else {
        str
    }
}

private fun JsonObject.optBooleanOrNull(key: String): Boolean? {
    val el = get(key) ?: return null
    if (el.isJsonNull || !el.isJsonPrimitive) return null
    return try {
        el.asBoolean
    } catch (_: Exception) {
        null
    }
}

private fun JsonObject.optIntOrNull(key: String): Int? {
    val el = get(key) ?: return null
    if (el.isJsonNull || !el.isJsonPrimitive) return null
    return try {
        el.asInt
    } catch (_: Exception) {
        null
    }
}

private fun JsonObject.optDoubleOrNull(key: String): Double? {
    val el = get(key) ?: return null
    if (el.isJsonNull || !el.isJsonPrimitive) return null
    return try {
        el.asDouble
    } catch (_: Exception) {
        null
    }
}
