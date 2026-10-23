package com.example.lyricbar.domain.lyricsengine

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.lyricbar.data.model.LyricFormat
import com.example.lyricbar.data.model.LyricLine
import com.example.lyricbar.data.model.Track
import java.net.URLEncoder

object SearchQueryBuilder {

    fun buildQuery(title: String, artist: String, format: LyricFormat): String {
        val cleanTitle = title.trim()
        val cleanArtist = artist.trim()

        val queryParts = mutableListOf<String>()
        if (cleanTitle.isNotEmpty() && !cleanTitle.equals("Unknown Title", ignoreCase = true)) {
            queryParts.add(cleanTitle)
        }
        if (cleanArtist.isNotEmpty() && !cleanArtist.equals("Unknown Artist", ignoreCase = true)) {
            queryParts.add(cleanArtist)
        }

        queryParts.add(format.searchKeyword)

        return queryParts.joinToString(" ")
    }

    fun buildGoogleSearchUrl(query: String): String {
        val encodedQuery = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return "https://www.google.com/search?q=$encodedQuery"
    }

    fun openGoogleSearch(context: Context, track: Track, format: LyricFormat): Result<Unit> {
        val query = buildQuery(track.title, track.artist, format)
        if (query.isBlank()) {
            return Result.failure(IllegalArgumentException("Please enter a song title or artist before searching."))
        }

        val url = buildGoogleSearchUrl(query)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

object LyricsTimingEngine {

    fun getCurrentLineIndex(lyrics: List<LyricLine>, currentPositionMs: Long, timeOffsetMs: Long): Int {
        if (lyrics.isEmpty()) return -1
        val adjustedTime = currentPositionMs + timeOffsetMs

        for (i in lyrics.indices) {
            val line = lyrics[i]
            if (adjustedTime in line.startTimeMs..line.endTimeMs) {
                return i
            }
        }

        // If before first line
        if (adjustedTime < lyrics.first().startTimeMs) return -1

        // If after last line
        if (adjustedTime >= lyrics.last().endTimeMs) return lyrics.size - 1

        // Fallback: closest previous line
        for (i in lyrics.indices.reversed()) {
            if (adjustedTime >= lyrics[i].startTimeMs) {
                return i
            }
        }

        return -1
    }
}
