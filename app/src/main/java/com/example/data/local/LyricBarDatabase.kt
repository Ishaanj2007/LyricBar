package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.LyricsDao
import com.example.data.local.dao.SongHistoryDao
import com.example.data.local.entity.LyricsEntity
import com.example.data.local.entity.SongHistoryEntity

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [LyricsEntity::class, SongHistoryEntity::class],
    version = 2,
    exportSchema = false
)
abstract class LyricBarDatabase : RoomDatabase() {

    abstract fun lyricsDao(): LyricsDao
    abstract fun songHistoryDao(): SongHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: LyricBarDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `song_history_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `key` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `albumArtUri` TEXT,
                        `status` TEXT NOT NULL,
                        `playedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `song_history_new` (`key`, `title`, `artist`, `albumArtUri`, `status`, `playedAt`)
                    SELECT `key`, `title`, `artist`, `albumArtUri`, `status`, `playedAt` FROM `song_history`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `song_history`")
                db.execSQL("ALTER TABLE `song_history_new` RENAME TO `song_history`")
            }
        }

        fun getInstance(context: Context): LyricBarDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LyricBarDatabase::class.java,
                    "lyricbar_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
