package com.example.domain.lyricsengine

import java.net.URLEncoder

enum class LyricsFileFormat(
    val extension: String,
    val displayName: String,
    val description: String
) {
    LRC("LRC", ".lrc", "Timestamped lyrics for synchronised playback"),
    TTML("TTML", ".ttml", "Timed-text lyrics with word-level sync"),
    SRT("SRT", ".srt", "Subtitle-style timed text"),
    TXT("TXT", ".txt", "Plain text lyrics")
}

object LyricsSearchQueryBuilder {

    private val PLACEHOLDER_TEXTS = setOf(
        "unknown artist",
        "unknown",
        "local file",
        "apple-style synced lyrics view",
        "no music playing",
        "imported track",
        "lyrics",
        "null"
    )

    fun cleanMetadata(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val trimmed = value.trim()
        if (PLACEHOLDER_TEXTS.contains(trimmed.lowercase())) return ""
        return trimmed
    }

    fun buildQuery(
        title: String?,
        artist: String?,
        format: LyricsFileFormat = LyricsFileFormat.LRC
    ): String {
        val cleanT = cleanMetadata(title)
        val cleanA = cleanMetadata(artist)

        if (cleanT.isEmpty() && cleanA.isEmpty()) {
            return ""
        }

        val baseInfo = if (cleanT.isNotEmpty() && cleanA.isNotEmpty()) {
            "$cleanT $cleanA"
        } else {
            cleanT.ifEmpty { cleanA }
        }

        return when (format) {
            LyricsFileFormat.TXT -> "$baseInfo TXT lyrics"
            else -> "$baseInfo ${format.extension} lyrics file download"
        }
    }

    fun buildGoogleSearchUrl(query: String): String {
        if (query.isBlank()) return ""
        val encodedQuery = try {
            URLEncoder.encode(query, "UTF-8")
        } catch (_: Exception) {
            query.replace(" ", "+")
        }
        return "https://www.google.com/search?q=$encodedQuery"
    }
}
