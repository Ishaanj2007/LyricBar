package com.example.lyricbar

import android.app.Application
import androidx.room.Room
import com.example.lyricbar.data.local.AppDatabase

class LyricBarApp : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "lyricbar.db"
        ).fallbackToDestructiveMigration().build()
    }
}
