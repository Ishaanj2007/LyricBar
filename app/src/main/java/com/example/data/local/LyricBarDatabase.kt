package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.LyricsDao
import com.example.data.local.dao.SongHistoryDao
import com.example.data.local.entity.LyricsEntity
import com.example.data.local.entity.SongHistoryEntity

@Database(
    entities = [LyricsEntity::class, SongHistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class LyricBarDatabase : RoomDatabase() {

    abstract fun lyricsDao(): LyricsDao
    abstract fun songHistoryDao(): SongHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: LyricBarDatabase? = null

        fun getInstance(context: Context): LyricBarDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LyricBarDatabase::class.java,
                    "lyricbar_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
