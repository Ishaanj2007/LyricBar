package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "song_history")
data class SongHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val key: String,
    val title: String,
    val artist: String,
    val albumArtUri: String? = null,
    val status: String = "Played",
    val playedAt: Long = System.currentTimeMillis()
)
