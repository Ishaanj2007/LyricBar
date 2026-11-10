package com.example.domain.provider

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class BiniHit(
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Int? = null,
    val isrc: String? = null,
    val timingType: String? = null
)

/**
 * Internal metadata/ISRC resolver used by [LrcRedProvider] when track metadata
 * does not already carry an ISRC.
 *
 * Strictly restricted to ISRC identification (`identify()`). Does not fetch or
 * parse TTML documents and is never used as a standalone lyrics provider.
 */
class BiniLyricsApi(
    okHttpClient: OkHttpClient = defaultClient(),
    private val baseUrl: String = "https://lyrics-api.binimum.org/"
) {
    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val hitCache = ConcurrentHashMap<String, BiniHit>()
    private val negativeCache = ConcurrentHashMap.newKeySet<String>()

    fun getCachedIsrc(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null
    ): String? {
        val key = buildCacheKey(title, artist, album, durationSeconds)
        return hitCache[key]?.isrc
    }

    fun clearCache() {
        hitCache.clear()
        negativeCache.clear()
    }

    suspend fun identify(
        title: String,
        artist: String,
        durationSeconds: Int? = null,
        album: String? = null,
        isrc: String? = null
    ): BiniHit? = withContext(Dispatchers.IO) {
        val cleanDirectIsrc = LyricsMatchingUtils.normalizeIsrc(isrc)
        if (cleanDirectIsrc != null) {
            return@withContext query(listOf("isrc" to cleanDirectIsrc))
                .mapNotNull { hit ->
                    val normalized = LyricsMatchingUtils.normalizeIsrc(hit.isrc) ?: return@mapNotNull null
                    hit.copy(isrc = normalized)
                }
                .sortedByDescending { it.timingType.equals("word", ignoreCase = true) }
                .firstOrNull()
        }

        if (title.isBlank() || artist.isBlank()) return@withContext null

        val cacheKey = buildCacheKey(title, artist, album, durationSeconds)
        hitCache[cacheKey]?.let { return@withContext it }
        if (negativeCache.contains(cacheKey)) return@withContext null

        val shapedParams = buildList {
            add("track" to title.trim())
            add("artist" to artist.trim())
            if (!album.isNullOrBlank()) add("album" to album.trim())
            if (durationSeconds != null && durationSeconds > 0) {
                add("duration" to durationSeconds.toString())
            }
        }

        val bestHit = selectBest(query(shapedParams), title, artist, durationSeconds)
            ?: selectBest(
                query(
                    listOf(
                        "q" to "${artist.trim()} - ${title.trim()}".trim()
                    )
                ),
                title,
                artist,
                durationSeconds
            )

        if (bestHit != null) {
            hitCache[cacheKey] = bestHit
        } else {
            negativeCache.add(cacheKey)
        }
        bestHit
    }

    private suspend fun query(params: List<Pair<String, String>>): List<BiniHit> {
        val builder = baseUrl.toHttpUrlOrNull()?.newBuilder() ?: return emptyList()
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }

        val request = Request.Builder()
            .url(builder.build())
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val body = response.body?.string()
                if (body.isNullOrBlank()) return emptyList()
                parseSearchResponse(body)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun parseSearchResponse(jsonBody: String): List<BiniHit> {
        return try {
            val root = JsonParser.parseString(jsonBody)
            if (!root.isJsonObject) return emptyList()
            val resultsArray = root.asJsonObject.getAsJsonArray("results") ?: return emptyList()
            val hits = mutableListOf<BiniHit>()
            for (element in resultsArray) {
                if (!element.isJsonObject) continue
                val obj = element.asJsonObject
                hits.add(
                    BiniHit(
                        trackName = obj.optCleanString("track_name"),
                        artistName = obj.optCleanString("artist_name"),
                        albumName = obj.optCleanString("album_name"),
                        duration = obj.optIntOrNull("duration"),
                        isrc = obj.optCleanString("isrc"),
                        timingType = obj.optCleanString("timing_type")
                    )
                )
            }
            hits
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun selectBest(
        hits: List<BiniHit>,
        title: String,
        artist: String,
        durationSeconds: Int?
    ): BiniHit? {
        if (hits.isEmpty()) return null
        val reqTitle = LyricsMatchingUtils.cleanTrackTitle(title)
        val reqArtist = LyricsMatchingUtils.cleanArtistName(artist)
        if (reqTitle.isBlank() || reqArtist.isBlank()) return null

        return hits
            .mapNotNull { hit ->
                val validIsrc = LyricsMatchingUtils.normalizeIsrc(hit.isrc) ?: return@mapNotNull null
                val candTrackName = hit.trackName.orEmpty()
                val candArtistName = hit.artistName.orEmpty()
                if (candTrackName.isBlank() || candArtistName.isBlank()) return@mapNotNull null
                if (!LyricsMatchingUtils.sameVersion(title, candTrackName)) return@mapNotNull null

                val candTitle = LyricsMatchingUtils.cleanTrackTitle(candTrackName)
                val candArtist = LyricsMatchingUtils.cleanArtistName(candArtistName)
                if (!LyricsMatchingUtils.titlesMatch(candTitle, reqTitle)) return@mapNotNull null
                if (!LyricsMatchingUtils.artistMatches(candArtist, reqArtist)) return@mapNotNull null

                val score = scoreHit(hit, title, artist, durationSeconds)
                if (score < MIN_HIT_SCORE) return@mapNotNull null

                hit.copy(isrc = validIsrc) to score
            }
            .sortedWith(
                compareByDescending<Pair<BiniHit, Int>> { (_, score) -> score }
                    .thenByDescending { (hit, _) -> hit.timingType.equals("word", ignoreCase = true) }
                    .thenBy { (hit, _) -> durationDelta(hit, durationSeconds) }
            )
            .firstOrNull()?.first
    }

    private fun durationDelta(hit: BiniHit, durationSeconds: Int?): Long {
        if (durationSeconds == null || durationSeconds <= 0 || (hit.duration ?: 0) <= 0) return 0L
        return abs((hit.duration ?: 0) - durationSeconds).toLong()
    }

    private fun buildCacheKey(
        title: String,
        artist: String,
        album: String?,
        durationSeconds: Int?
    ): String {
        val normTitle = LyricsMatchingUtils.cleanTrackTitle(title).lowercase(Locale.ROOT)
        val normArtist = LyricsMatchingUtils.cleanArtistName(artist).lowercase(Locale.ROOT)
        val normAlbum = album?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val dur = durationSeconds?.takeIf { it > 0 } ?: 0
        return "$normArtist|$normTitle|$normAlbum|$dur"
    }

    companion object {
        private const val USER_AGENT = "LyricBar/2.1.1 (Android)"
        private const val MIN_HIT_SCORE = 5

        fun scoreHit(hit: BiniHit, title: String, artist: String, durationSeconds: Int?): Int {
            var score = 0
            val candTitle = LyricsMatchingUtils.cleanTrackTitle(hit.trackName.orEmpty())
            val reqTitle = LyricsMatchingUtils.cleanTrackTitle(title)
            if (candTitle.equals(reqTitle, ignoreCase = true)) {
                score += 3
            } else if (LyricsMatchingUtils.titlesMatch(candTitle, reqTitle)) {
                score += 1
            }

            val candArtist = LyricsMatchingUtils.cleanArtistName(hit.artistName.orEmpty())
            val reqArtist = LyricsMatchingUtils.cleanArtistName(artist)
            if (LyricsMatchingUtils.artistMatches(candArtist, reqArtist)) {
                score += 2
            }

            if (durationSeconds != null && durationSeconds > 0 && (hit.duration ?: 0) > 0) {
                score += when (abs((hit.duration ?: 0) - durationSeconds)) {
                    in 0..3 -> 3
                    in 4..10 -> 1
                    else -> 0
                }
            }
            return score
        }

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

private fun JsonObject.optIntOrNull(key: String): Int? {
    val element = get(key) ?: return null
    if (element.isJsonNull || !element.isJsonPrimitive) return null
    return try {
        element.asInt
    } catch (_: Exception) {
        null
    }
}
