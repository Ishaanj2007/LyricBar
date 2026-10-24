package com.example.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.local.entity.SongHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SongHistoryDao {

    @Query("SELECT * FROM song_history ORDER BY playedAt DESC, id DESC LIMIT 100")
    fun getRecentHistory(): Flow<List<SongHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entry: SongHistoryEntity)

    @Query("DELETE FROM song_history")
    suspend fun clearHistory()
}
