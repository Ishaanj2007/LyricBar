package com.example.domain.provider

import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata
import com.example.domain.parser.LrcParser
import com.google.gson.JsonElement
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
import kotlin.math.roundToInt

/**
 * Provider 5: Lyrica — Synchronized lyrics provider prioritized for Hindi, Bollywood,
 * Marathi, Punjabi, Tamil, and Telugu music catalogs with global coverage.
 *
 * Queries `GET /lyrics/?artist=...&song=...&timestamps=true&word=false&metadata=true&fast=true`,
 * enforces standard line-synchronized timing, rejects plain-text and instrumental responses,
 * and validates returned track/artist/version metadata.
 */
class LyricaProvider(
    okHttpClient: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL
) : LyricsProvider {

    override val name: String = "Lyrica"

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchLyrics(
        track: TrackMetadata,
        cleanTitle: String,
        cleanArtist: String
    ): Result<ParsedLyrics> {
        val rawTitle = TrackMetadata.cleanMediaClutter(track.title).ifBlank { cleanTitle }
        val rawArtist = TrackMetadata.cleanMediaClutter(track.artist).ifBlank { cleanArtist }
        return fetchLyrics(
            title = rawTitle,
            artist = rawArtist,
            durationSec = track.durationSec.takeIf { it > 0 }
        )
    }

    override suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSec: Int?
    ): Result<ParsedLyrics> = withContext(Dispatchers.IO) {
        try {
            val cleanedTitle = LyricsMatchingUtils.cleanTrackTitle(title)
            val cleanedArtist = LyricsMatchingUtils.cleanArtistName(artist)
            val queryTitle = LyricsMatchingUtils.stripLeadingArtistPrefix(cleanedTitle, cleanedArtist)
                .ifBlank { cleanedTitle }

            if (queryTitle.isBlank() || cleanedArtist.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Title and artist are required for Lyrica"))
            }

            val urlBuilder = "${baseUrl.trimEnd('/')}/lyrics/".toHttpUrlOrNull()?.newBuilder()
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid Lyrica URL"))

            urlBuilder.addQueryParameter("artist", cleanedArtist)
            urlBuilder.addQueryParameter("song", queryTitle)
            urlBuilder.addQueryParameter("timestamps", "true")
            urlBuilder.addQueryParameter("word", "false")
            urlBuilder.addQueryParameter("metadata", "true")
            urlBuilder.addQueryParameter("fast", "true")

            val request = Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()

            val httpResult = client.newCall(request).awaitHttpResponse()
                ?: return@withContext Result.failure(IOException("Lyrica network request failed"))

            if (httpResult.code == 429) {
                return@withContext Result.failure(IOException("Lyrica rate limit exceeded (HTTP 429)"))
            }
            if (httpResult.code !in 200..299) {
                return@withContext Result.failure(IOException("Lyrica returned HTTP ${httpResult.code}"))
            }

            parseResponse(
                jsonBody = httpResult.body,
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

    internal fun parseResponse(
        jsonBody: String,
        requestedTitle: String,
        requestedArtist: String,
        durationSec: Int? = null
    ): Result<ParsedLyrics> {
        val raw = jsonBody.trim()
        if (raw.isEmpty() || raw.equals("null", ignoreCase = true) || raw.equals("undefined", ignoreCase = true)) {
            return Result.failure(NoSuchElementException("Empty Lyrica response"))
        }
        if (raw.startsWith("<")) {
            return Result.failure(IllegalArgumentException("Unexpected HTML/XML response from Lyrica"))
        }

        return try {
            val rootEl = JsonParser.parseString(raw)
            if (!rootEl.isJsonObject) {
                return Result.failure(IllegalArgumentException("Invalid JSON object from Lyrica"))
            }
            val root = rootEl.asJsonObject
            val status = root.optCleanString("status")
            if (status != null && !status.equals("success", ignoreCase = true)) {
                return Result.failure(NoSuchElementException("Lyrica status=$status"))
            }

            val data = root.optJsonObject("data")
                ?: return Result.failure(NoSuchElementException("Missing data object in Lyrica response"))

            if (data.optBooleanOrNull("instrumental") == true) {
                return Result.failure(NoSuchElementException("Lyrica reported instrumental track"))
            }

            val hasTimestamps = data.optBooleanOrNull("hasTimestamps")
            if (hasTimestamps == false) {
                return Result.failure(IllegalArgumentException("Unsynchronized Lyrica response rejected"))
            }

            val metaObj = root.optJsonObject("metadata")
            val candTitle = data.optCleanString("title") ?: metaObj?.optCleanString("title")
            val candArtist = data.optCleanString("artist") ?: metaObj?.optCleanString("artist")
            val candDurationSec = data.optDoubleOrNull("duration")?.roundToInt()
                ?: metaObj?.optJsonObject("duration")?.optIntOrNull("seconds")

            if (!isTrackMatch(
                    requestedTitle = requestedTitle,
                    requestedArtist = requestedArtist,
                    requestedDurationSec = durationSec,
                    candTitle = candTitle,
                    candArtist = candArtist,
                    candDurationSec = candDurationSec
                )
            ) {
                return Result.failure(NoSuchElementException("Lyrica response failed track/version/artist match"))
            }

            val rawLyrics = data.optCleanString("lyrics").orEmpty()
            if (rawLyrics.isNotEmpty() && LrcParser.isTtml(rawLyrics)) {
                return Result.failure(IllegalArgumentException("Unsupported TTML payload from Lyrica"))
            }

            val extractedLines = mutableListOf<LyricLine>()

            // 1. Prefer standard LRC string when present with timestamps
            if (rawLyrics.isNotEmpty() && LrcParser.hasTimestampTags(rawLyrics)) {
                val parsedLrc = LrcParser.parse(
                    content = rawLyrics,
                    title = candTitle ?: requestedTitle,
                    artist = candArtist ?: requestedArtist,
                    source = name
                )
                if (parsedLrc.isSynced) {
                    parsedLrc.lines.forEach { line ->
                        val cleanText = LyricsMatchingUtils.stripInlineWordTags(line.text)
                        if (isValidLyricText(cleanText)) {
                            extractedLines.add(
                                LyricLine(
                                    timeMs = line.timeMs,
                                    text = cleanText,
                                    endTimeMs = 0L,
                                    words = emptyList(),
                                    isWordSynced = false
                                )
                            )
                        }
                    }
                }
            }

            // 2. Fallback to structured timed_lyrics array when LRC text has no timestamp tags
            if (extractedLines.isEmpty()) {
                val timedArray = data.getAsJsonArray("timed_lyrics")
                if (timedArray != null) {
                    for (el in timedArray) {
                        if (!el.isJsonObject) continue
                        val obj = el.asJsonObject
                        val startMs = obj.optLongOrNull("start_time") ?: continue
                        val rawLineText = obj.optCleanString("text") ?: continue
                        val cleanText = LyricsMatchingUtils.stripInlineWordTags(rawLineText)
                        if (isValidLyricText(cleanText)) {
                            extractedLines.add(
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
            }

            val sortedLines = extractedLines.sortedBy { it.timeMs }
            if (!LyricsMatchingUtils.hasValidSyncedLines(sortedLines, durationSec)) {
                return Result.failure(NoSuchElementException("Lyrica returned no valid synchronized lyric lines"))
            }

            val resolvedTitle = candTitle ?: requestedTitle
            val resolvedArtist = candArtist ?: requestedArtist
            val plainText = sortedLines.joinToString("\n") { it.text }
            val formattedLrc = LrcParser.formatLrc(sortedLines, offsetMs = 0L)

            Result.success(
                ParsedLyrics(
                    title = resolvedTitle,
                    artist = resolvedArtist,
                    isSynced = true,
                    lines = sortedLines,
                    plainText = plainText,
                    source = name,
                    offsetMs = 0L,
                    isWordSynced = false,
                    format = "LRC",
                    rawContent = formattedLrc
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
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
            val titleMatched = LyricsMatchingUtils.titlesMatch(cleanReqTitle, cleanCandTitle) ||
                LyricsMatchingUtils.titlesMatch(cleanReqTitle, candNoPrefix)
            if (!titleMatched) return false
        }

        if (!candArtist.isNullOrBlank() && requestedArtist.isNotBlank()) {
            if (!LyricsMatchingUtils.artistMatches(requestedArtist, candArtist)) return false
        }

        if (requestedDurationSec != null && requestedDurationSec > 0 && candDurationSec != null && candDurationSec > 0) {
            val toleranceSec = maxOf(25, requestedDurationSec / 2)
            if (abs(requestedDurationSec - candDurationSec) > toleranceSec) {
                return false
            }
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
        const val DEFAULT_BASE_URL = "https://wilooper-lyrica.hf.space"
        private const val USER_AGENT = "LyricBar/2.5.0 (Android)"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}

private data class LyricaHttpResult(
    val code: Int,
    val body: String
)

private suspend fun Call.awaitHttpResponse(): LyricaHttpResult? = suspendCancellableCoroutine { cont ->
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
                if (cont.isActive) cont.resume(LyricaHttpResult(resp.code, body))
            }
        }
    })
}

private fun JsonObject.optJsonObject(key: String): JsonObject? {
    val el: JsonElement = get(key) ?: return null
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

private fun JsonObject.optDoubleOrNull(key: String): Double? {
    val el = get(key) ?: return null
    if (el.isJsonNull || !el.isJsonPrimitive) return null
    return try {
        el.asDouble
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
