package com.example.domain.provider

import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata
import com.example.domain.parser.LrcParser
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Provider 4: LyricsPlus — Final synchronized lyrics fallback provider.
 *
 * Queries configured LyricsPlus mirrors concurrently, prefers the last known good
 * mirror first, cancels remaining in-flight mirror requests as soon as the first
 * valid synchronized response arrives, and converts both `line.text` and syllable-only
 * (`syllabus`) payloads into standard line-level [LyricLine] objects without word/syllable timings.
 */
class LyricsPlusProvider(
    okHttpClient: OkHttpClient = defaultClient(),
    private val mirrors: List<String> = DEFAULT_MIRRORS,
    private val biniApi: BiniLyricsApi? = null
) : LyricsProvider {

    override val name: String = "LyricsPlus"

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val lastGood = AtomicReference<String?>(null)

    internal fun getLastGoodMirror(): String? = lastGood.get()

    internal fun orderedMirrors(): List<String> {
        val preferred = lastGood.get() ?: return mirrors
        if (preferred !in mirrors) return mirrors
        return listOf(preferred) + mirrors.filterNot { it == preferred }
    }

    override suspend fun fetchLyrics(
        track: TrackMetadata,
        cleanTitle: String,
        cleanArtist: String
    ): Result<ParsedLyrics> {
        val title = cleanTitle.ifBlank { TrackMetadata.cleanForLookup(track.title) }
        val artist = cleanArtist.ifBlank { TrackMetadata.cleanForLookup(track.artist) }
        val durationSec = track.durationSec.takeIf { it > 0 }
        val album = track.album.trim().takeIf { it.isNotBlank() }

        // Reuse existing ISRC if already known on track or cached in BiniLyricsApi, without triggering a new Bini lookup
        val knownIsrc = LyricsMatchingUtils.normalizeIsrc(track.isrc)
            ?: biniApi?.getCachedIsrc(
                title = TrackMetadata.cleanMediaClutter(track.title).ifBlank { title },
                artist = TrackMetadata.cleanMediaClutter(track.artist).ifBlank { artist },
                album = album,
                durationSeconds = durationSec
            )

        return fetchLyrics(
            title = title,
            artist = artist,
            durationSec = durationSec,
            album = album,
            isrc = knownIsrc
        )
    }

    override suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSec: Int?
    ): Result<ParsedLyrics> = fetchLyrics(
        title = title,
        artist = artist,
        durationSec = durationSec,
        album = null,
        isrc = null
    )

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSec: Int? = null,
        album: String? = null,
        isrc: String? = null
    ): Result<ParsedLyrics> = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()
        if (cleanTitle.isEmpty() || cleanArtist.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Title and artist are required for LyricsPlus"))
        }

        val hosts = orderedMirrors()
        if (hosts.isEmpty()) {
            return@withContext Result.failure(NoSuchElementException("No LyricsPlus mirrors configured"))
        }

        try {
            val mirrorFailures = mutableListOf<Throwable>()
            val winner: ParsedLyrics? = coroutineScope {
                val pending = hosts.map { host ->
                    host to async(Dispatchers.IO) {
                        fetchFromMirror(
                            host = host,
                            title = cleanTitle,
                            artist = cleanArtist,
                            durationSec = durationSec,
                            album = album,
                            isrc = isrc
                        )
                    }
                }.toMutableList()

                try {
                    while (pending.isNotEmpty()) {
                        val (host, mirrorResult) = select<Pair<String, Result<ParsedLyrics>>> {
                            pending.forEach { (candidateHost, deferred) ->
                                deferred.onAwait { candidateHost to it }
                            }
                        }
                        pending.removeAll { it.first == host }
                        if (mirrorResult.isSuccess) {
                            lastGood.set(host)
                            return@coroutineScope mirrorResult.getOrNull()
                        } else {
                            mirrorResult.exceptionOrNull()?.let { mirrorFailures.add(it) }
                        }
                    }
                    null
                } finally {
                    pending.forEach { (_, deferred) -> deferred.cancel() }
                }
            }

            if (winner != null) {
                Result.success(winner)
            } else {
                // If any mirror hit rate limiting (HTTP 429), server error (HTTP 5xx), or network failure, propagate it
                val transientOrServerError = mirrorFailures.firstOrNull { err ->
                    val msg = err.message?.lowercase(Locale.US).orEmpty()
                    msg.contains("http 429") ||
                        msg.contains("http 502") ||
                        msg.contains("http 503") ||
                        msg.contains("http 504") ||
                        msg.contains("rate limit") ||
                        err is java.net.SocketTimeoutException ||
                        err is java.net.ConnectException ||
                        err is java.net.UnknownHostException
                }
                if (transientOrServerError != null) {
                    Result.failure(transientOrServerError)
                } else {
                    Result.failure(NoSuchElementException("Lyrics not found on any LyricsPlus mirror"))
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun fetchFromMirror(
        host: String,
        title: String,
        artist: String,
        durationSec: Int?,
        album: String?,
        isrc: String?
    ): Result<ParsedLyrics> {
        val base = host.trim().trimEnd('/')
        val builder = "$base/v2/lyrics/get".toHttpUrlOrNull()?.newBuilder()
            ?: return Result.failure(IllegalArgumentException("Invalid URL for host $host"))
        builder.addQueryParameter("title", title)
        builder.addQueryParameter("artist", artist)
        val validDuration = durationSec?.takeIf { it > 0 }
        if (validDuration != null) {
            builder.addQueryParameter("duration", validDuration.toString())
        }
        if (!album.isNullOrBlank()) {
            builder.addQueryParameter("album", album.trim())
        }
        val cleanIsrc = LyricsMatchingUtils.normalizeIsrc(isrc)
        if (cleanIsrc != null) {
            builder.addQueryParameter("isrc", cleanIsrc)
        }

        val request = Request.Builder()
            .url(builder.build())
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            val httpResult = client.newCall(request).awaitLyricsPlusResponse()
            if (httpResult.code == 429) {
                return Result.failure(IOException("LyricsPlus rate limit exceeded (HTTP 429)"))
            }
            if (httpResult.code in 500..599) {
                return Result.failure(IOException("LyricsPlus returned HTTP ${httpResult.code}"))
            }
            if (httpResult.code !in 200..299 || httpResult.body.isBlank()) {
                return Result.failure(NoSuchElementException("Lyrics not found on mirror $host (HTTP ${httpResult.code})"))
            }
            parseResponse(
                jsonBody = httpResult.body,
                title = title,
                artist = artist,
                durationSec = durationSec
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Parses a LyricsPlus `/v2/lyrics/get` JSON payload into standard line-level [ParsedLyrics].
     *
     * Rules:
     * - Every lyric line must have a valid non-negative `time` timestamp.
     * - Prefers `line.text.trim()` when non-blank.
     * - When `line.text` is blank or missing, reconstructs line text from `syllabus` using
     *   word-boundary spacing rules.
     * - Never populates syllable or word timing fields in [LyricLine] (`words` is always empty).
     */
    internal fun parseResponse(
        jsonBody: String,
        title: String = "",
        artist: String = "",
        durationSec: Int? = null
    ): Result<ParsedLyrics> {
        val raw = jsonBody.trim()
        if (raw.isEmpty() || raw.equals("null", ignoreCase = true) || raw.equals("undefined", ignoreCase = true)) {
            return Result.failure(NoSuchElementException("Empty LyricsPlus response"))
        }
        if (LrcParser.isTtml(raw)) {
            return Result.failure(IllegalArgumentException("TTML content is not supported"))
        }

        return try {
            val root = JsonParser.parseString(raw)
            if (!root.isJsonObject) {
                return Result.failure(IllegalArgumentException("Invalid JSON object from LyricsPlus"))
            }
            val lyricsArray = root.asJsonObject.getAsJsonArray("lyrics")
                ?: return Result.failure(NoSuchElementException("Missing lyrics array in LyricsPlus response"))

            if (lyricsArray.size() == 0) {
                return Result.failure(NoSuchElementException("Empty lyrics array in LyricsPlus response"))
            }

            val parsedLines = mutableListOf<LyricLine>()

            for (element in lyricsArray) {
                if (!element.isJsonObject) continue
                val lineObj = element.asJsonObject

                val startTimeMs = lineObj.optLongOrNull("time")
                if (startTimeMs == null || startTimeMs < 0L) {
                    // Require valid line start timestamp
                    return Result.failure(IllegalArgumentException("LyricsPlus line is missing a valid start timestamp"))
                }

                val directText = lineObj.optRawString("text")
                    ?.let { LyricsMatchingUtils.stripInlineWordTags(it) }
                    ?.takeIf {
                        it.isNotBlank() &&
                            !it.equals("null", ignoreCase = true) &&
                            !it.equals("undefined", ignoreCase = true)
                    }

                val finalLineText = directText ?: run {
                    val syllabusArray = lineObj.getAsJsonArray("syllabus")
                    if (syllabusArray == null || syllabusArray.size() == 0) {
                        ""
                    } else {
                        val rawSyllables = mutableListOf<String>()
                        for (sylEl in syllabusArray) {
                            if (!sylEl.isJsonObject) continue
                            val sylObj = sylEl.asJsonObject
                            val sylText = sylObj.optRawString("text") ?: continue
                            if (sylText.trim().equals("null", ignoreCase = true) ||
                                sylText.trim().equals("undefined", ignoreCase = true)
                            ) {
                                continue
                            }
                            rawSyllables.add(sylText)
                        }
                        reconstructTextFromSyllables(rawSyllables)
                    }
                }

                if (finalLineText.isBlank()) {
                    continue
                }

                parsedLines.add(
                    LyricLine(
                        timeMs = startTimeMs,
                        text = finalLineText,
                        endTimeMs = 0L,
                        words = emptyList(),
                        isWordSynced = false
                    )
                )
            }

            val sortedLines = parsedLines.sortedBy { it.timeMs }
            if (!LyricsMatchingUtils.hasValidSyncedLines(sortedLines, durationSec)) {
                return Result.failure(NoSuchElementException("LyricsPlus response contained no valid synchronized lines"))
            }

            val plainText = sortedLines.joinToString("\n") { it.text }
            val formattedLrc = LrcParser.formatLrc(sortedLines, offsetMs = 0L)

            Result.success(
                ParsedLyrics(
                    title = title,
                    artist = artist,
                    lines = sortedLines,
                    plainText = plainText,
                    rawContent = formattedLrc,
                    isSynced = true,
                    offsetMs = 0L,
                    source = name
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reconstructs standard line text from syllable tokens using the API's whitespace
     * word boundaries (and unspaced CJK/Thai script rules), then joins words with `" "`.
     */
    internal fun reconstructTextFromSyllables(syllableTexts: List<String>): String {
        if (syllableTexts.isEmpty()) return ""
        val words = mutableListOf<String>()
        val current = StringBuilder()

        fun flush() {
            if (current.isNotEmpty()) {
                words += current.toString()
                current.setLength(0)
            }
        }

        for (raw in syllableTexts) {
            val content = raw.trim()
            if (content.isEmpty()) {
                flush()
                continue
            }
            val joins = current.isNotEmpty() &&
                !raw.first().isWhitespace() &&
                !current.last().isUnspacedScript() &&
                !content.first().isUnspacedScript()
            if (!joins) flush()
            current.append(content)
            if (raw.last().isWhitespace()) flush()
        }
        flush()
        return words.joinToString(" ").trim()
    }

    companion object {
        private const val USER_AGENT = "LyricBar/2.1.1 (Android)"

        val DEFAULT_MIRRORS = listOf(
            "https://lyricsplus.prjktla.my.id",
            "https://lyricsplus.atomix.one",
            "https://lyricsplus.binimum.org",
            "https://lyricsplus.prjktla.workers.dev",
            "https://lyricsplus-seven.vercel.app",
            "https://lyrics-plus-backend.vercel.app"
        )

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }
}

private data class LyricsPlusHttpResult(
    val code: Int,
    val body: String
)

private suspend fun Call.awaitLyricsPlusResponse(): LyricsPlusHttpResult = suspendCancellableCoroutine { cont ->
    val isResumed = AtomicBoolean(false)
    cont.invokeOnCancellation {
        try {
            cancel()
        } catch (_: Exception) {}
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (isResumed.compareAndSet(false, true) && cont.isActive) {
                cont.resumeWithException(e)
            }
        }

        override fun onResponse(call: Call, response: Response) {
            response.use { resp ->
                try {
                    val body = resp.body?.string().orEmpty()
                    if (isResumed.compareAndSet(false, true) && cont.isActive) {
                        cont.resume(LyricsPlusHttpResult(resp.code, body))
                    }
                } catch (e: Throwable) {
                    if (isResumed.compareAndSet(false, true) && cont.isActive) {
                        cont.resumeWithException(e)
                    }
                }
            }
        }
    })
}

private fun JsonObject.optRawString(key: String): String? {
    val element = get(key) ?: return null
    if (element.isJsonNull || !element.isJsonPrimitive) return null
    return try {
        element.asString
    } catch (_: Exception) {
        null
    }
}

private fun JsonObject.optLongOrNull(key: String): Long? {
    val element = get(key) ?: return null
    if (element.isJsonNull || !element.isJsonPrimitive) return null
    return try {
        element.asLong
    } catch (_: Exception) {
        null
    }
}

private fun Char.isUnspacedScript(): Boolean =
    this in '\u3400'..'\u9FFF' ||
        this in '\u3040'..'\u30FF' ||
        this in '\u3000'..'\u303F' ||
        this in '\uFF00'..'\uFFEF' ||
        this in '\u0E00'..'\u0E7F'
