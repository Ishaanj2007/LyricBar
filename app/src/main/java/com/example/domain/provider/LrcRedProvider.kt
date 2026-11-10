package com.example.domain.provider

import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata
import com.example.domain.parser.LrcParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Provider 2: lrc.red — ISRC-based synchronized LRC fallback provider.
 *
 * Strictly fetches standard `.lrc` files (`/s/{ISRC}.lrc`) and never requests `.ttml`.
 * When [TrackMetadata.isrc] is absent, uses [BiniLyricsApi.identify] exclusively to
 * resolve a verified ISRC for the recording before querying `lrc.red`.
 */
class LrcRedProvider(
    okHttpClient: OkHttpClient = defaultClient(),
    private val biniApi: BiniLyricsApi? = BiniLyricsApi(okHttpClient),
    private val baseUrl: String = "https://lrc.red/"
) : LyricsProvider {

    override val name: String = "lrc.red"

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    override suspend fun fetchLyrics(
        track: TrackMetadata,
        cleanTitle: String,
        cleanArtist: String
    ): Result<ParsedLyrics> = withContext(Dispatchers.IO) {
        try {
            val rawTrackIsrc = track.isrc?.trim()
            val resolvedIsrc: String = if (!rawTrackIsrc.isNullOrEmpty()) {
                LyricsMatchingUtils.normalizeIsrc(rawTrackIsrc)
                    ?: return@withContext Result.failure(IllegalArgumentException("Malformed ISRC on track metadata"))
            } else {
                val queryTitle = TrackMetadata.cleanMediaClutter(track.title).ifBlank { cleanTitle }
                val queryArtist = TrackMetadata.cleanMediaClutter(track.artist).ifBlank { cleanArtist }
                if (queryTitle.isBlank() || queryArtist.isBlank()) {
                    return@withContext Result.failure(IllegalArgumentException("Missing title or artist for ISRC resolution"))
                }
                val hit = biniApi?.identify(
                    title = queryTitle,
                    artist = queryArtist,
                    durationSeconds = track.durationSec.takeIf { it > 0 },
                    album = track.album.takeIf { it.isNotBlank() }
                )
                LyricsMatchingUtils.normalizeIsrc(hit?.isrc)
                    ?: return@withContext Result.failure(NoSuchElementException("Unable to resolve ISRC for lrc.red"))
            }

            fetchLyricsForIsrc(
                isrc = resolvedIsrc,
                title = cleanTitle.ifBlank { track.title },
                artist = cleanArtist.ifBlank { track.artist },
                durationSec = track.durationSec.takeIf { it > 0 }
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSec: Int?
    ): Result<ParsedLyrics> = withContext(Dispatchers.IO) {
        try {
            if (title.isBlank() || artist.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Title and artist are required"))
            }
            val hit = biniApi?.identify(
                title = title,
                artist = artist,
                durationSeconds = durationSec?.takeIf { it > 0 }
            )
            val resolvedIsrc = LyricsMatchingUtils.normalizeIsrc(hit?.isrc)
                ?: return@withContext Result.failure(NoSuchElementException("Unable to resolve ISRC for lrc.red"))

            fetchLyricsForIsrc(
                isrc = resolvedIsrc,
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
     * Fetches synchronized LRC directly from `lrc.red` using a validated 12-character ISRC.
     * Requests ONLY `.lrc` and never requests `.ttml`.
     */
    suspend fun fetchLyricsForIsrc(
        isrc: String,
        title: String = "",
        artist: String = "",
        durationSec: Int? = null
    ): Result<ParsedLyrics> = withContext(Dispatchers.IO) {
        val cleanIsrc = LyricsMatchingUtils.normalizeIsrc(isrc)
            ?: return@withContext Result.failure(IllegalArgumentException("Invalid ISRC format"))

        val url = "${baseUrl.trimEnd('/')}/s/$cleanIsrc.lrc"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/plain, */*")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("lrc.red returned HTTP ${response.code}"))
                }
                val body = response.body?.string()
                parseLrcResponse(body, title, artist, durationSec)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    internal fun parseLrcResponse(
        body: String?,
        title: String,
        artist: String,
        durationSec: Int?
    ): Result<ParsedLyrics> {
        val raw = body?.trim().orEmpty()
        if (raw.isEmpty() || raw.equals("null", ignoreCase = true) || raw.equals("undefined", ignoreCase = true)) {
            return Result.failure(NoSuchElementException("Empty response from lrc.red"))
        }
        if (LrcParser.isTtml(raw)) {
            return Result.failure(IllegalArgumentException("TTML content is not supported by lrc.red provider"))
        }
        if (!LrcParser.hasTimestampTags(raw)) {
            return Result.failure(IllegalArgumentException("Unsynchronized plain text rejected from lrc.red"))
        }

        val parsed = LrcParser.parse(
            content = raw,
            title = title,
            artist = artist,
            source = name
        )

        if (!parsed.isSynced || !parsed.hasValidLyrics) {
            return Result.failure(NoSuchElementException("No valid synchronized lyrics in lrc.red response"))
        }

        // Normalize strictly to standard line-level LyricLine objects without word-level timings
        val cleanLines = parsed.lines
            .mapNotNull { line ->
                val cleanText = LyricsMatchingUtils.stripInlineWordTags(line.text)
                if (cleanText.isBlank() ||
                    cleanText.equals("null", ignoreCase = true) ||
                    cleanText.equals("undefined", ignoreCase = true)
                ) {
                    null
                } else {
                    LyricLine(
                        timeMs = line.timeMs,
                        text = cleanText,
                        endTimeMs = 0L,
                        words = emptyList(),
                        isWordSynced = false
                    )
                }
            }
            .sortedBy { it.timeMs }

        if (!LyricsMatchingUtils.hasValidSyncedLines(cleanLines, durationSec)) {
            return Result.failure(IllegalArgumentException("lrc.red lyrics failed timestamp plausibility validation"))
        }

        val plainText = cleanLines.joinToString("\n") { it.text }
        val normalizedRawLrc = LrcParser.formatLrc(cleanLines, offsetMs = 0L)

        return Result.success(
            ParsedLyrics(
                title = parsed.title.ifBlank { title },
                artist = parsed.artist.ifBlank { artist },
                lines = cleanLines,
                plainText = plainText,
                rawContent = normalizedRawLrc,
                isSynced = true,
                offsetMs = parsed.offsetMs,
                source = name
            )
        )
    }

    companion object {
        private const val USER_AGENT = "LyricBar/2.1.1 (Android)"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }
}
