package com.example.lyricbar.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Database
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playback_history")
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String,
    val timestamp: Long = System.currentTimeMillis(),
    val hasSyncedLyrics: Boolean = false
)

@Dao
interface PlaybackHistoryDao {
    @Query("SELECT * FROM playback_history ORDER BY timestamp DESC LIMIT 50")
    fun getRecentHistory(): Flow<List<PlaybackHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entity: PlaybackHistoryEntity)

    @Query("DELETE FROM playback_history")
    suspend fun clearHistory()
}

@Database(entities = [PlaybackHistoryEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): PlaybackHistoryDao
}
