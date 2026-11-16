package com.example.domain.provider

import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata
import com.example.domain.parser.LrcParser
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Provider 6: LiriQo — Synchronized lyrics provider enhancing international and Asian
 * music catalog coverage.
 *
 * Queries LiriQo's normalized `GET /lyrics` endpoint with `fast=1`, filters candidate tracks
 * (`primary` + `tracks[]`) strictly for matching title/artist/recording version/duration,
 * rejects plain-text responses, extracts clean line-synchronized timestamps from `timed[]`
 * (or inline-stripped `lrc`, with optional `/lrc?v=<videoId>` fallback when needed),
 * and returns standard line-synced [ParsedLyrics].
 */
class LiriQoProvider(
    okHttpClient: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL
) : LyricsProvider {

    override val name: String = "LiriQo"

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

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
                return@withContext Result.failure(IllegalArgumentException("Title and artist are required for LiriQo"))
            }

            val urlBuilder = "${baseUrl.trimEnd('/')}/lyrics".toHttpUrlOrNull()?.newBuilder()
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid LiriQo URL"))

            urlBuilder.addQueryParameter("title", queryTitle)
            urlBuilder.addQueryParameter("artist", cleanedArtist)
            if (durationSec != null && durationSec > 0) {
                urlBuilder.addQueryParameter("duration", durationSec.toString())
            }
            if (!album.isNullOrBlank()) {
                urlBuilder.addQueryParameter("album", album)
            }
            if (!isrc.isNullOrBlank()) {
                urlBuilder.addQueryParameter("isrc", isrc)
            }
            urlBuilder.addQueryParameter("fast", "1")

            val request = Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            val httpResult = client.newCall(request).awaitLiriQoResponse()
                ?: return@withContext Result.failure(IOException("LiriQo network request failed"))

            if (httpResult.code == 429) {
                return@withContext Result.failure(IOException("LiriQo rate limit exceeded (HTTP 429)"))
            }
            if (httpResult.code !in 200..299) {
                return@withContext Result.failure(IOException("LiriQo returned HTTP ${httpResult.code}"))
            }

            val parsedResult = parseResponse(
                jsonBody = httpResult.body,
                requestedTitle = title,
                requestedArtist = artist,
                durationSec = durationSec
            )
            if (parsedResult.isSuccess) {
                return@withContext parsedResult
            }

            // Fallback: if matched metadata includes a videoId but no inline line-synced track succeeded,
            // try LiriQo's dedicated /lrc?v=<videoId> line-synced endpoint when metadata validates cleanly.
            val fallbackVideoId = extractValidatedFallbackVideoId(
                jsonBody = httpResult.body,
                requestedTitle = title,
                requestedArtist = artist,
                durationSec = durationSec
            )
            if (fallbackVideoId != null) {
                return@withContext fetchDedicatedLrcByVideoId(
                    videoId = fallbackVideoId,
                    requestedTitle = title,
                    requestedArtist = artist,
                    durationSec = durationSec
                )
            }

            parsedResult
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchDedicatedLrcByVideoId(
        videoId: String,
        requestedTitle: String,
        requestedArtist: String,
        durationSec: Int?
    ): Result<ParsedLyrics> {
        val url = "${baseUrl.trimEnd('/')}/lrc".toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("v", videoId)
            ?.build()
            ?: return Result.failure(IllegalArgumentException("Invalid LiriQo /lrc URL"))

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/plain, */*")
            .get()
            .build()

        val httpResult = client.newCall(request).awaitLiriQoResponse()
            ?: return Result.failure(IOException("LiriQo /lrc request failed"))

        if (httpResult.code !in 200..299) {
            return Result.failure(IOException("LiriQo /lrc returned HTTP ${httpResult.code}"))
        }

        val rawLrc = httpResult.body.trim()
        if (rawLrc.isEmpty() || LrcParser.isTtml(rawLrc) || !LrcParser.hasTimestampTags(rawLrc)) {
            return Result.failure(NoSuchElementException("LiriQo /lrc returned no valid LRC"))
        }
        val lines = parseLrcToCleanLines(rawLrc, requestedTitle, requestedArtist)
        val sorted = lines.sortedBy { it.timeMs }
        if (!LyricsMatchingUtils.hasValidSyncedLines(sorted, durationSec)) {
            return Result.failure(NoSuchElementException("LiriQo /lrc failed timestamp validation"))
        }
        return Result.success(
            ParsedLyrics(
                title = requestedTitle,
                artist = requestedArtist,
                isSynced = true,
                lines = sorted,
                plainText = sorted.joinToString("\n") { it.text },
                source = name,
                offsetMs = 0L,
                isWordSynced = false,
                format = "LRC",
                rawContent = LrcParser.formatLrc(sorted)
            )
        )
    }

    internal fun parseResponse(
        jsonBody: String,
        requestedTitle: String,
        requestedArtist: String,
        durationSec: Int? = null
    ): Result<ParsedLyrics> {
        val raw = jsonBody.trim()
        if (raw.isEmpty() || raw.equals("null", ignoreCase = true) || raw.equals("undefined", ignoreCase = true)) {
            return Result.failure(NoSuchElementException("Empty LiriQo response"))
        }
        if (raw.startsWith("<")) {
            return Result.failure(IllegalArgumentException("Unexpected HTML/XML response from LiriQo"))
        }

        return try {
            val rootEl = JsonParser.parseString(raw)
            if (!rootEl.isJsonObject) {
                return Result.failure(IllegalArgumentException("Invalid JSON object from LiriQo"))
            }
            val root = rootEl.asJsonObject
            val errorMsg = root.optCleanString("error")
            if (!errorMsg.isNullOrBlank()) {
                return Result.failure(NoSuchElementException("LiriQo error: $errorMsg"))
            }

            val metaObj = root.optJsonObject("metadata")
            val envTitle = metaObj?.optCleanString("title")
            val envArtist = metaObj?.optCleanString("artist")
            val envDurationSec = parseDurationSeconds(metaObj?.get("duration"))

            val candidates = mutableListOf<JsonObject>()
            root.optJsonObject("primary")?.let { candidates.add(it) }
            root.getAsJsonArray("tracks")?.forEach { el ->
                if (el.isJsonObject) {
                    val obj = el.asJsonObject
                    if (obj != candidates.firstOrNull()) {
                        candidates.add(obj)
                    }
                }
            }

            if (candidates.isEmpty()) {
                return Result.failure(NoSuchElementException("No lyric candidates in LiriQo response"))
            }

            // Evaluate validated candidates, preferring native line-synced candidates first cleanly,
            // while safely accepting any synced candidate converted strictly to line-level timing.
            val scoredCandidates = candidates.mapIndexedNotNull { index, cand ->
                val syncLevel = cand.optCleanString("syncLevel")?.lowercase()
                if (syncLevel == "plain") return@mapIndexedNotNull null

                val candTitle = cand.optCleanString("title") ?: envTitle
                val candArtist = cand.optCleanString("artist") ?: envArtist
                val candDurationSec = parseDurationSeconds(cand.get("duration")) ?: envDurationSec

                if (!isTrackMatch(
                        requestedTitle = requestedTitle,
                        requestedArtist = requestedArtist,
                        requestedDurationSec = durationSec,
                        candTitle = candTitle,
                        candArtist = candArtist,
                        candDurationSec = candDurationSec
                    )
                ) {
                    return@mapIndexedNotNull null
                }

                val lines = extractSyncedLines(cand, candTitle ?: requestedTitle, candArtist ?: requestedArtist)
                val sortedLines = lines.sortedBy { it.timeMs }
                if (!LyricsMatchingUtils.hasValidSyncedLines(sortedLines, durationSec)) {
                    return@mapIndexedNotNull null
                }

                val priorityScore = when (syncLevel) {
                    "line" -> 2
                    "word", "syllable", "character" -> 1
                    else -> 1
                }
                Triple(priorityScore, index, Triple(sortedLines, candTitle ?: requestedTitle, candArtist ?: requestedArtist))
            }

            val best = scoredCandidates
                .sortedWith(compareByDescending<Triple<Int, Int, Triple<List<LyricLine>, String, String>>> { it.first }
                    .thenBy { it.second })
                .firstOrNull()
                ?: return Result.failure(NoSuchElementException("No valid synchronized LiriQo candidate matched track"))

            val (sortedLines, resolvedTitle, resolvedArtist) = best.third
            Result.success(
                ParsedLyrics(
                    title = resolvedTitle,
                    artist = resolvedArtist,
                    isSynced = true,
                    lines = sortedLines,
                    plainText = sortedLines.joinToString("\n") { it.text },
                    source = name,
                    offsetMs = 0L,
                    isWordSynced = false,
                    format = "LRC",
                    rawContent = LrcParser.formatLrc(sortedLines, offsetMs = 0L)
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractValidatedFallbackVideoId(
        jsonBody: String,
        requestedTitle: String,
        requestedArtist: String,
        durationSec: Int?
    ): String? {
        return try {
            val root = JsonParser.parseString(jsonBody.trim()).asJsonObject
            val videoId = root.optCleanString("videoId") ?: return null
            val metaObj = root.optJsonObject("metadata") ?: return null
            val envTitle = metaObj.optCleanString("title")
            val envArtist = metaObj.optCleanString("artist")
            val envDurationSec = parseDurationSeconds(metaObj.get("duration"))
            if (isTrackMatch(requestedTitle, requestedArtist, durationSec, envTitle, envArtist, envDurationSec)) {
                videoId
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractSyncedLines(
        candidate: JsonObject,
        title: String,
        artist: String
    ): List<LyricLine> {
        val result = mutableListOf<LyricLine>()

        // 1. Prefer normalized line-level `timed[]` array (ms timestamps + clean line text)
        val timedArray = candidate.getAsJsonArray("timed")
        if (timedArray != null && timedArray.size() > 0) {
            for (el in timedArray) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                if (obj.optBooleanOrNull("isInstrumental") == true) continue
                val startMs = obj.optLongOrNull("start") ?: continue
                val rawText = obj.optCleanString("text") ?: continue
                val cleanText = LyricsMatchingUtils.stripInlineWordTags(rawText)
                if (isValidLyricText(cleanText)) {
                    result.add(
                        LyricLine(
                            timeMs = startMs,
                            text = cleanText,
                            endTimeMs = 0L,
                            words = emptyList(),
                            isWordSynced = false
                        )
                    )
                }
            }
        }
        if (result.isNotEmpty()) return result

        // 2. Fallback to `lrc` string when `timed[]` is empty/absent (never accept TTML directly)
        val rawLrc = candidate.optCleanString("lrc").orEmpty()
        if (rawLrc.isNotEmpty() && !LrcParser.isTtml(rawLrc) && LrcParser.hasTimestampTags(rawLrc)) {
            return parseLrcToCleanLines(rawLrc, title, artist)
        }
        return emptyList()
    }

    private fun parseLrcToCleanLines(rawLrc: String, title: String, artist: String): List<LyricLine> {
        val parsed = LrcParser.parse(content = rawLrc, title = title, artist = artist, source = name)
        if (!parsed.isSynced) return emptyList()
        return parsed.lines.mapNotNull { line ->
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

    private fun parseDurationSeconds(el: com.google.gson.JsonElement?): Int? {
        if (el == null || el.isJsonNull || !el.isJsonPrimitive) return null
        val prim = el.asJsonPrimitive
        if (prim.isNumber) {
            return try {
                prim.asInt.takeIf { it > 0 }
            } catch (_: Exception) {
                null
            }
        }
        val str = prim.asString.trim()
        str.toIntOrNull()?.let { return it.takeIf { v -> v > 0 } }
        val parts = str.split(":")
        if (parts.size == 2) {
            val mins = parts[0].toIntOrNull() ?: return null
            val secs = parts[1].toIntOrNull() ?: return null
            return (mins * 60 + secs).takeIf { it > 0 }
        }
        return null
    }

    private fun isValidLyricText(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() &&
            !t.equals("null", ignoreCase = true) &&
            !t.equals("undefined", ignoreCase = true)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.liriqo-alfarrizi.my.id/v1"
        private const val USER_AGENT = "LyricBar/2.5.0 (Android)"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

private data class LiriQoHttpResult(
    val code: Int,
    val body: String
)

private suspend fun Call.awaitLiriQoResponse(): LiriQoHttpResult? = suspendCancellableCoroutine { cont ->
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
                if (cont.isActive) cont.resume(LiriQoHttpResult(resp.code, body))
            }
        }
    })
}

private fun JsonObject.optJsonObject(key: String): JsonObject? {
    val el = get(key) ?: return null
    return if (el.isJsonObject) el.asJsonObject else null
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

private fun JsonObject.optLongOrNull(key: String): Long? {
    val el = get(key) ?: return null
    if (el.isJsonNull || !el.isJsonPrimitive) return null
    return try {
        el.asLong
    } catch (_: Exception) {
        null
    }
}
