package com.example.domain.provider

import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata

interface LyricsProvider {
    val name: String

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        durationSec: Int? = null
    ): Result<ParsedLyrics>

    suspend fun fetchLyrics(
        track: TrackMetadata,
        cleanTitle: String = TrackMetadata.cleanForLookup(track.title),
        cleanArtist: String = TrackMetadata.cleanForLookup(track.artist)
    ): Result<ParsedLyrics> = fetchLyrics(
        title = cleanTitle,
        artist = cleanArtist,
        durationSec = track.durationSec
    )
}
