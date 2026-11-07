package com.example.domain.provider

import com.example.data.model.ParsedLyrics
import com.example.domain.parser.LrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LrcLibProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) : LyricsProvider {

    override val name: String = "LRCLIB"

    override suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSec: Int?
    ): Result<ParsedLyrics> = withContext(Dispatchers.IO) {
        try {
            val urlBuilder = "https://lrclib.net/api/get".toHttpUrlOrNull()?.newBuilder()
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid LRCLIB URL"))

            urlBuilder.addQueryParameter("track_name", title.trim())
            urlBuilder.addQueryParameter("artist_name", artist.trim())
            if (durationSec != null && durationSec > 0) {
                urlBuilder.addQueryParameter("duration", durationSec.toString())
            }

            val request = Request.Builder()
                .url(urlBuilder.build())
                .header("User-Agent", "LyricBar/1.0.6 (Android)")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string()
                    if (!bodyString.isNullOrBlank()) {
                        val json = JSONObject(bodyString)
                        val syncedLrc = json.optCleanLyricsString("syncedLyrics")
                        val plainLyrics = json.optCleanLyricsString("plainLyrics")
                        val trackName = json.optCleanLyricsString("trackName") ?: title
                        val artistName = json.optCleanLyricsString("artistName") ?: artist

                        if (!syncedLrc.isNullOrBlank()) {
                            val parsed = LrcParser.parse(
                                content = syncedLrc,
                                title = trackName,
                                artist = artistName,
                                source = name
                            )
                            if (parsed.hasValidLyrics) {
                                return@withContext Result.success(parsed)
                            }
                        }
                        if (!plainLyrics.isNullOrBlank()) {
                            val parsed = LrcParser.parse(
                                content = plainLyrics,
                                title = trackName,
                                artist = artistName,
                                source = "$name (Plain)"
                            )
                            if (parsed.hasValidLyrics) {
                                return@withContext Result.success(parsed)
                            }
                        }
                    }
                }
            }

            // Fallback: Query LRCLIB search endpoint when exact match is not found
            val searchQuery = listOf(title.trim(), artist.trim()).filter { it.isNotBlank() }.joinToString(" ")
            if (searchQuery.isNotBlank()) {
                val searchUrl = "https://lrclib.net/api/search".toHttpUrlOrNull()?.newBuilder()
                    ?.addQueryParameter("q", searchQuery)
                    ?.build()

                if (searchUrl != null) {
                    val searchRequest = Request.Builder()
                        .url(searchUrl)
                        .header("User-Agent", "LyricBar/1.0.6 (Android)")
                        .get()
                        .build()

                    client.newCall(searchRequest).execute().use { sResponse ->
                        if (sResponse.isSuccessful) {
                            val sBody = sResponse.body?.string()
                            if (!sBody.isNullOrBlank() && sBody.trim().startsWith("[")) {
                                val array = org.json.JSONArray(sBody)
                                var firstPlain: ParsedLyrics? = null
                                for (i in 0 until array.length().coerceAtMost(6)) {
                                    val item = array.optJSONObject(i) ?: continue
                                    val synced = item.optCleanLyricsString("syncedLyrics")
                                    val plain = item.optCleanLyricsString("plainLyrics")
                                    val tName = item.optCleanLyricsString("trackName") ?: title
                                    val aName = item.optCleanLyricsString("artistName") ?: artist

                                    if (!synced.isNullOrBlank()) {
                                        val parsed = LrcParser.parse(
                                            content = synced,
                                            title = tName,
                                            artist = aName,
                                            source = name
                                        )
                                        if (parsed.hasValidLyrics) {
                                            return@withContext Result.success(parsed)
                                        }
                                    } else if (!plain.isNullOrBlank() && firstPlain == null) {
                                        val parsed = LrcParser.parse(
                                            content = plain,
                                            title = tName,
                                            artist = aName,
                                            source = "$name (Plain)"
                                        )
                                        if (parsed.hasValidLyrics) {
                                            firstPlain = parsed
                                        }
                                    }
                                }
                                if (firstPlain != null && firstPlain.hasValidLyrics) {
                                    return@withContext Result.success(firstPlain)
                                }
                            }
                        }
                    }
                }
            }

            Result.failure(Exception("Lyrics not found on LRCLIB"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

private fun JSONObject.optCleanLyricsString(key: String): String? {
    if (isNull(key)) return null
    val raw = opt(key) ?: return null
    if (raw == JSONObject.NULL) return null
    val str = raw.toString().trim()
    return if (str.isEmpty() || str.equals("null", ignoreCase = true) || str.equals("undefined", ignoreCase = true)) {
        null
    } else {
        str
    }
}
