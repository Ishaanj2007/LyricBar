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

class LyricsOvhProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
) : LyricsProvider {

    override val name: String = "Lyrics.ovh"

    override suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSec: Int?
    ): Result<ParsedLyrics> = withContext(Dispatchers.IO) {
        try {
            val encodedArtist = java.net.URLEncoder.encode(artist.trim(), "UTF-8")
            val encodedTitle = java.net.URLEncoder.encode(title.trim(), "UTF-8")
            val url = "https://api.lyrics.ovh/v1/$encodedArtist/$encodedTitle".toHttpUrlOrNull()
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid URL"))

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Auralis/1.0.6 (Android)")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string()
                    if (!bodyString.isNullOrBlank()) {
                        val json = JSONObject(bodyString)
                        val lyrics = json.optString("lyrics", "").trim()
                        if (lyrics.isNotEmpty()) {
                            return@withContext Result.success(
                                LrcParser.parse(lyrics, title = title, artist = artist, source = name)
                            )
                        }
                    }
                }
            }
            Result.failure(Exception("Lyrics not available on Lyrics.ovh"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
