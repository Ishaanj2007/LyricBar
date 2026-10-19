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
                .header("User-Agent", "Auralis/1.0.6 (Android)")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string()
                    if (!bodyString.isNullOrBlank()) {
                        val json = JSONObject(bodyString)
                        val syncedLrc = json.optString("syncedLyrics", "").trim()
                        val plainLyrics = json.optString("plainLyrics", "").trim()
                        val trackName = json.optString("trackName", title)
                        val artistName = json.optString("artistName", artist)

                        if (syncedLrc.isNotEmpty()) {
                            return@withContext Result.success(
                                LrcParser.parse(
                                    content = syncedLrc,
                                    title = trackName,
                                    artist = artistName,
                                    source = name
                                )
                            )
                        } else if (plainLyrics.isNotEmpty()) {
                            return@withContext Result.success(
                                LrcParser.parse(
                                    content = plainLyrics,
                                    title = trackName,
                                    artist = artistName,
                                    source = "$name (Plain)"
                                )
                            )
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
