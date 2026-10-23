package com.example.lyricbar.data.repository

import com.example.lyricbar.data.model.LyricLine
import com.example.lyricbar.data.model.Track
import com.example.lyricbar.domain.parser.LrcParser
import com.example.lyricbar.domain.parser.TtmlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

data class LrclibResponse(
    val id: Long?,
    val trackName: String?,
    val artistName: String?,
    val albumName: String?,
    val duration: Double?,
    val syncedLyrics: String?,
    val plainLyrics: String?
)

data class LyricsOvhResponse(
    val lyrics: String?
)

interface LrclibService {
    @GET("api/get")
    suspend fun getLyrics(
        @Query("artist_name") artist: String,
        @Query("track_name") track: String
    ): LrclibResponse

    @GET("api/search")
    suspend fun searchLyrics(
        @Query("q") query: String
    ): List<LrclibResponse>
}

interface LyricsOvhService {
    @GET("v1/{artist}/{title}")
    suspend fun getPlainLyrics(
        @Path("artist") artist: String,
        @Path("title") title: String
    ): LyricsOvhResponse
}

class LyricsRepository {

    private val lrclibApi: LrclibService by lazy {
        Retrofit.Builder()
            .baseUrl("https://lrclib.net/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LrclibService::class.java)
    }

    private val lyricsOvhApi: LyricsOvhService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.lyrics.ovh/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LyricsOvhService::class.java)
    }

    suspend fun fetchOnlineLyrics(track: Track): Result<List<LyricLine>> = withContext(Dispatchers.IO) {
        if (track.title.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Track title is missing."))
        }

        try {
            // 1. Try LRCLIB exact get
            val lrclibResult = lrclibApi.getLyrics(track.artist, track.title)
            val synced = lrclibResult.syncedLyrics
            if (!synced.isNullOrBlank()) {
                val parsed = LrcParser.parse(synced)
                if (parsed.isNotEmpty()) return@withContext Result.success(parsed)
            }

            val plain = lrclibResult.plainLyrics
            if (!plain.isNullOrBlank()) {
                val lines = plain.lines().mapIndexed { index, text ->
                    LyricLine(startTimeMs = index * 4000L, text = text)
                }
                return@withContext Result.success(lines)
            }
        } catch (e: Exception) {
            // Fall back to search or Lyrics.ovh
        }

        try {
            // 2. Try LRCLIB search query
            val searchResults = lrclibApi.searchLyrics("${track.title} ${track.artist}")
            for (res in searchResults) {
                if (!res.syncedLyrics.isNullOrBlank()) {
                    val parsed = LrcParser.parse(res.syncedLyrics)
                    if (parsed.isNotEmpty()) return@withContext Result.success(parsed)
                }
            }
        } catch (e: Exception) {
            // Ignore search error
        }

        try {
            // 3. Fallback to Lyrics.ovh plain lyrics
            if (track.artist.isNotBlank()) {
                val ovhResult = lyricsOvhApi.getPlainLyrics(track.artist, track.title)
                val ovhText = ovhResult.lyrics
                if (!ovhText.isNullOrBlank()) {
                    val lines = ovhText.lines().filter { it.isNotBlank() }.mapIndexed { index, text ->
                        LyricLine(startTimeMs = index * 4000L, text = text)
                    }
                    return@withContext Result.success(lines)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }

        Result.failure(NoSuchElementException("No lyrics found online for '${track.title}'."))
    }

    fun parseLocalLyrics(content: String, isTtml: Boolean): List<LyricLine> {
        return if (isTtml) {
            TtmlParser.parse(content)
        } else {
            LrcParser.parse(content)
        }
    }
}
