package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey
    val key: String,
    val title: String,
    val artist: String,
    val syncedLrc: String?,
    val plainLyrics: String?,
    val isSynced: Boolean,
    val durationSec: Int?,
    val source: String,
    val cachedAt: Long = System.currentTimeMillis()
)
