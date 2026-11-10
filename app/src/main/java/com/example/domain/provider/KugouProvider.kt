package com.example.domain.provider

import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata
import com.example.domain.parser.LrcParser
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.InflaterInputStream
import kotlin.math.abs

data class KugouCandidate(
    val id: String,
    val accesskey: String,
    val duration: Long = 0L,
    val singer: String = "",
    val song: String = ""
)

/**
 * Provider 3: KuGou — Title/artist-based fallback provider.
 *
 * Performs strict artist, title, and recording-version validation on search candidates,
 * downloads and decrypts KRC payloads (Base64 + XOR + ZLIB), and converts them strictly
 * into standard line-synchronized [LyricLine] objects without word or syllable timings.
 */
class KugouProvider(
    okHttpClient: OkHttpClient = defaultClient(),
    private val baseUrl: String = "https://lyrics.kugou.com"
) : LyricsProvider {

    override val name: String = "KuGou"

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
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
            val searchTitle = LyricsMatchingUtils.stripLeadingArtistPrefix(cleanedTitle, cleanedArtist)

            if (searchTitle.isBlank() || cleanedArtist.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Title and artist are required for KuGou search"))
            }

            val durationMs = (durationSec?.takeIf { it > 0 } ?: 0) * 1000L
            val candidates = searchCandidates(
                keyword = "$cleanedArtist - $searchTitle",
                durationMs = durationMs
            )

            val chosen = selectBestCandidate(
                candidates = candidates,
                requestedTitle = title,
                requestedArtist = artist,
                durationMs = durationMs
            ) ?: return@withContext Result.failure(NoSuchElementException("No matching KuGou candidate found"))

            val krcBase64 = downloadKrcBase64(chosen.id, chosen.accesskey)
                ?: return@withContext Result.failure(IOException("KuGou download returned empty content"))

            val decryptedKrc = decryptKrc(krcBase64)
                ?: return@withContext Result.failure(IllegalArgumentException("Failed to decrypt KuGou KRC payload"))

            parseKrc(
                krcText = decryptedKrc,
                title = cleanedTitle,
                artist = cleanedArtist,
                durationSec = durationSec
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun searchCandidates(keyword: String, durationMs: Long): List<KugouCandidate> {
        val urlBuilder = "${baseUrl.trimEnd('/')}/search".toHttpUrlOrNull()?.newBuilder()
            ?: return emptyList()
        urlBuilder.addQueryParameter("ver", "1")
        urlBuilder.addQueryParameter("man", "yes")
        urlBuilder.addQueryParameter("client", "pc")
        urlBuilder.addQueryParameter("keyword", keyword)
        urlBuilder.addQueryParameter("duration", if (durationMs > 0L) durationMs.toString() else "")
        urlBuilder.addQueryParameter("hash", "")

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string() ?: return emptyList()
                parseSearchResponse(body)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun parseSearchResponse(jsonBody: String): List<KugouCandidate> {
        return try {
            val root = JsonParser.parseString(jsonBody)
            if (!root.isJsonObject) return emptyList()
            val candidatesArray = root.asJsonObject.getAsJsonArray("candidates") ?: return emptyList()
            val list = mutableListOf<KugouCandidate>()
            for (el in candidatesArray) {
                if (!el.isJsonObject) continue
                val obj = el.asJsonObject
                val id = obj.optCleanString("id") ?: continue
                val accesskey = obj.optCleanString("accesskey") ?: continue
                list.add(
                    KugouCandidate(
                        id = id,
                        accesskey = accesskey,
                        duration = obj.optLongOrZero("duration"),
                        singer = obj.optCleanString("singer").orEmpty(),
                        song = obj.optCleanString("song").orEmpty()
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Selects the best matching candidate.
     *
     * Mandatory rules:
     * 1. Recording version compatibility (`sameVersion`) must match (rejects Live/Remix/Acoustic/Cover mismatches).
     * 2. Artist must match (`artistMatches`).
     * 3. Title must match (`titlesMatch`).
     * 4. Duration is used only to rank candidates that already passed 1–3; never accepts by duration alone.
     */
    internal fun selectBestCandidate(
        candidates: List<KugouCandidate>,
        requestedTitle: String,
        requestedArtist: String,
        durationMs: Long
    ): KugouCandidate? {
        if (candidates.isEmpty()) return null

        val cleanedTitle = LyricsMatchingUtils.cleanTrackTitle(requestedTitle)
        val cleanedArtist = LyricsMatchingUtils.cleanArtistName(requestedArtist)
        val searchTitle = LyricsMatchingUtils.stripLeadingArtistPrefix(cleanedTitle, cleanedArtist)
        if (cleanedTitle.isBlank() || cleanedArtist.isBlank()) return null

        val validCandidates = candidates.filter { cand ->
            if (cand.id.isBlank() || cand.accesskey.isBlank()) return@filter false
            if (cand.song.isBlank() || cand.singer.isBlank()) return@filter false

            // 1. Version match (Live, Remix, Acoustic, Cover, Remastered, Instrumental, etc.)
            if (!LyricsMatchingUtils.sameVersion(requestedTitle, cand.song)) {
                return@filter false
            }

            // 2. Artist match
            val candSinger = LyricsMatchingUtils.cleanArtistName(cand.singer)
            if (!LyricsMatchingUtils.artistMatches(candSinger, cleanedArtist)) {
                return@filter false
            }

            // 3. Title match
            val candSong = LyricsMatchingUtils.cleanTrackTitle(cand.song)
            val candSongWithoutPrefix = LyricsMatchingUtils.stripLeadingArtistPrefix(candSong, candSinger)
            val titleMatch = LyricsMatchingUtils.titlesMatch(candSong, cleanedTitle) ||
                LyricsMatchingUtils.titlesMatch(candSong, searchTitle) ||
                LyricsMatchingUtils.titlesMatch(candSongWithoutPrefix, cleanedTitle) ||
                LyricsMatchingUtils.titlesMatch(candSongWithoutPrefix, searchTitle)

            titleMatch
        }

        if (validCandidates.isEmpty()) return null

        return if (durationMs > 0L) {
            val withDuration = validCandidates.filter { it.duration > 0L }
            withDuration.filter { abs(it.duration - durationMs) <= 8_000L }
                .minByOrNull { abs(it.duration - durationMs) }
                ?: withDuration.filter { abs(it.duration - durationMs) <= 30_000L }
                    .minByOrNull { abs(it.duration - durationMs) }
                ?: withDuration.minByOrNull { abs(it.duration - durationMs) }
                ?: validCandidates.first()
        } else {
            validCandidates.first()
        }
    }

    private fun downloadKrcBase64(id: String, accesskey: String): String? {
        val urlBuilder = "${baseUrl.trimEnd('/')}/download".toHttpUrlOrNull()?.newBuilder()
            ?: return null
        urlBuilder.addQueryParameter("ver", "1")
        urlBuilder.addQueryParameter("client", "pc")
        urlBuilder.addQueryParameter("id", id)
        urlBuilder.addQueryParameter("accesskey", accesskey)
        urlBuilder.addQueryParameter("fmt", "krc")
        urlBuilder.addQueryParameter("charset", "utf8")

        val request = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val root = JsonParser.parseString(body)
                if (!root.isJsonObject) return null
                root.asJsonObject.optCleanString("content")
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Decrypts a Base64-encoded KuGou KRC payload:
     * 1. Base64 decode.
     * 2. Verify 4-byte `krc1` magic header (`0x6b, 0x72, 0x63, 0x31`).
     * 3. XOR remaining bytes with the 16-byte KRC key.
     * 4. ZLIB inflate and decode UTF-8 text.
     */
    internal fun decryptKrc(base64Content: String): String? {
        val trimmed = base64Content.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val encryptedBytes = Base64.getMimeDecoder().decode(trimmed)
            if (encryptedBytes.size <= 4) return null

            // Validate 'k', 'r', 'c', '1' header
            if (encryptedBytes[0] != 0x6b.toByte() ||
                encryptedBytes[1] != 0x72.toByte() ||
                encryptedBytes[2] != 0x63.toByte() ||
                encryptedBytes[3] != 0x31.toByte()
            ) {
                return null
            }

            val decryptedBytes = ByteArray(encryptedBytes.size - 4)
            for (i in 4 until encryptedBytes.size) {
                decryptedBytes[i - 4] = (encryptedBytes[i].toInt() xor KRC_KEY[(i - 4) % 16]).toByte()
            }

            InflaterInputStream(ByteArrayInputStream(decryptedBytes)).use { inflaterStream ->
                inflaterStream.readBytes().toString(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses decrypted KRC text into standard line-level [ParsedLyrics].
     *
     * Applies `[offset:...]` to line start timestamps, concatenates syllable fragments into
     * plain line text, skips KRC credit metadata lines, and strips all word/syllable timings.
     */
    internal fun parseKrc(
        krcText: String,
        title: String = "",
        artist: String = "",
        durationSec: Int? = null
    ): Result<ParsedLyrics> {
        if (krcText.isBlank()) {
            return Result.failure(NoSuchElementException("Empty KRC text"))
        }

        val globalOffsetMs = OFFSET_REGEX.find(krcText)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val rawLines = krcText.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val lyricLines = mutableListOf<LyricLine>()

        for (rawLine in rawLines) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            val lineMatch = LINE_REGEX.find(trimmed) ?: continue
            val rawLineStartMs = lineMatch.groupValues[1].toLongOrNull() ?: continue
            val lineStartMs = (rawLineStartMs + globalOffsetMs).coerceAtLeast(0L)
            val lineContent = lineMatch.groupValues[3]

            val wordMatches = WORD_REGEX.findAll(lineContent).toList()
            val fullLineText = if (wordMatches.isNotEmpty()) {
                val sb = StringBuilder()
                for (wm in wordMatches) {
                    sb.append(wm.groupValues[3])
                }
                sb.toString().trim()
            } else {
                lineContent.replace(ANY_ANGLE_TAG_REGEX, "").trim()
            }

            if (fullLineText.isEmpty() ||
                fullLineText.equals("null", ignoreCase = true) ||
                fullLineText.equals("undefined", ignoreCase = true)
            ) {
                continue
            }

            if (isCreditLine(fullLineText, rawLineStartMs)) {
                continue
            }

            lyricLines.add(
                LyricLine(
                    timeMs = lineStartMs,
                    text = fullLineText,
                    endTimeMs = 0L,
                    words = emptyList(),
                    isWordSynced = false
                )
            )
        }

        val sortedLines = lyricLines.sortedBy { it.timeMs }
        if (!LyricsMatchingUtils.hasValidSyncedLines(sortedLines, durationSec)) {
            return Result.failure(NoSuchElementException("KuGou KRC contained no valid synchronized lines"))
        }

        val plainText = sortedLines.joinToString("\n") { it.text }
        val formattedLrc = LrcParser.formatLrc(sortedLines, offsetMs = 0L)

        return Result.success(
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
    }

    private fun isCreditLine(text: String, lineStartMs: Long): Boolean {
        val trimmed = text.trim()
        if (CREDIT_PREFIX_REGEX.containsMatchIn(trimmed)) return true
        if (lineStartMs < 4_000L && EARLY_HEADER_CREDIT_REGEX.containsMatchIn(trimmed)) return true
        return false
    }

    companion object {
        private const val USER_AGENT = "LyricBar/2.1.1 (Android)"

        internal val KRC_KEY = intArrayOf(
            64, 71, 97, 119, 94, 50, 116, 71,
            81, 54, 49, 45, 206, 210, 110, 105
        )

        private val LINE_REGEX = Regex("""^\[(\d+),(\d+)\](.*)$""")
        private val WORD_REGEX = Regex("""<(\d+),(\d+),\d+>([^<]*)""")
        private val ANY_ANGLE_TAG_REGEX = Regex("""<[^>]*>""")
        private val OFFSET_REGEX = Regex("""\[offset:\s*(-?\d+)\s*\]""", RegexOption.IGNORE_CASE)

        private val CREDIT_PREFIX_REGEX = Regex(
            pattern = "^\\s*(?:作词|作詞|作曲|编曲|編曲|制作人|製作人|プロデューサー|监制|監製|混音|母带|母帶|吉他|贝斯|貝斯|鼓|弦乐|弦樂|和声|和聲|和音|录音|錄音|统筹|統籌|出品|发行|發行|OP|SP|词|詞|曲|编|編|唱|演唱|原唱|翻唱|歌|歌词编辑|歌詞編輯|LRC|Lyricist|Composer|Arranger|Producer|Produced\\s+by|Written\\s+by|Mixed\\s+by|Mastered\\s+by|Vocals?\\s+by)\\s*[:：]",
            option = RegexOption.IGNORE_CASE
        )

        private val EARLY_HEADER_CREDIT_REGEX = Regex(
            pattern = "^[^:：]{1,24}\\s*[-—–]\\s*[^:：]{1,30}$"
        )

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }
}

private fun JsonObject.optCleanString(key: String): String? {
    val element = get(key) ?: return null
    if (element.isJsonNull || !element.isJsonPrimitive) return null
    val str = element.asString.trim()
    return if (str.isEmpty() || str.equals("null", ignoreCase = true) || str.equals("undefined", ignoreCase = true)) {
        null
    } else {
        str
    }
}

private fun JsonObject.optLongOrZero(key: String): Long {
    val element = get(key) ?: return 0L
    if (element.isJsonNull || !element.isJsonPrimitive) return 0L
    return try {
        element.asLong
    } catch (_: Exception) {
        0L
    }
}
