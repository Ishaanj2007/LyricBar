package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.LyricsEntity
import com.example.data.local.entity.SongHistoryEntity

/**
 * Dedicated DAO for Selective Backup & Restore operations.
 * Kept separate from [LyricsDao] and [SongHistoryDao] to preserve existing
 * DAO contracts and database schema (version = 2) untouched.
 */
@Dao
interface BackupDao {

    @Query("SELECT * FROM song_history ORDER BY playedAt DESC, id DESC")
    suspend fun getAllHistory(): List<SongHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryEntries(entries: List<SongHistoryEntity>)

    @Query("DELETE FROM song_history")
    suspend fun clearAllHistory()

    @Query("SELECT * FROM lyrics ORDER BY cachedAt DESC")
    suspend fun getAllLyrics(): List<LyricsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLyricsEntries(entries: List<LyricsEntity>)

    @Query("DELETE FROM lyrics WHERE `key` IN (:keys)")
    suspend fun deleteLyricsByKeys(keys: List<String>)
}
