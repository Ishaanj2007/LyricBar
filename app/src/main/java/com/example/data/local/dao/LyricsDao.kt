package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.LyricsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LyricsDao {

    @Query("SELECT * FROM lyrics WHERE `key` = :key LIMIT 1")
    suspend fun getLyricsByKey(key: String): LyricsEntity?

    @Query("SELECT * FROM lyrics WHERE `key` = :key LIMIT 1")
    fun observeLyricsByKey(key: String): Flow<LyricsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLyrics(lyrics: LyricsEntity)

    @Query("DELETE FROM lyrics WHERE `key` = :key")
    suspend fun deleteLyricsByKey(key: String)

    @Query("DELETE FROM lyrics")
    suspend fun clearAllLyrics()
}
