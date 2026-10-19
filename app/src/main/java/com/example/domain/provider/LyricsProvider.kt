package com.example.domain.provider

import com.example.data.model.ParsedLyrics

interface LyricsProvider {
    val name: String
    suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSec: Int? = null
    ): Result<ParsedLyrics>
}
