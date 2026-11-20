package com.example.data.backup

import androidx.room.withTransaction
import com.example.data.local.LyricBarDatabase
import com.example.data.local.entity.LyricsEntity
import com.example.data.local.entity.SongHistoryEntity
import com.example.data.preferences.UserPreferencesRepository

/**
 * Storage abstraction over DataStore preferences used by [BackupRestoreManager].
 */
interface BackupPreferencesStore {
    suspend fun readAppSettings(): BackupAppSettingsData
    suspend fun readHudConfig(): BackupHudConfigData
    suspend fun applySelective(
        appSettings: BackupAppSettingsData? = null,
        hudConfig: BackupHudConfigData? = null
    )
}

/**
 * Storage abstraction over Room tables used by [BackupRestoreManager], with explicit
 * transaction support to avoid partial database mutations on error.
 */
interface BackupDatabaseStore {
    suspend fun getAllHistory(): List<SongHistoryEntity>
    suspend fun getAllLyrics(): List<LyricsEntity>
    suspend fun <T> runInTransaction(block: suspend BackupDatabaseTx.() -> T): T
}

interface BackupDatabaseTx {
    suspend fun getAllHistory(): List<SongHistoryEntity>
    suspend fun replaceAllHistory(entries: List<SongHistoryEntity>)
    suspend fun insertHistory(entries: List<SongHistoryEntity>)
    suspend fun getAllLyrics(): List<LyricsEntity>
    suspend fun upsertLyrics(entries: List<LyricsEntity>)
    suspend fun deleteLyricsByKeys(keys: List<String>)
}

class DataStoreBackupPreferencesStore(
    private val repository: UserPreferencesRepository
) : BackupPreferencesStore {

    override suspend fun readAppSettings(): BackupAppSettingsData = repository.readBackupAppSettings()

    override suspend fun readHudConfig(): BackupHudConfigData = repository.readBackupHudConfig()

    override suspend fun applySelective(
        appSettings: BackupAppSettingsData?,
        hudConfig: BackupHudConfigData?
    ) {
        repository.applyBackupPreferences(
            appSettings = appSettings,
            hudConfig = hudConfig
        )
    }
}

class RoomBackupDatabaseStore(
    private val database: LyricBarDatabase
) : BackupDatabaseStore {

    private val dao = database.backupDao()

    override suspend fun getAllHistory(): List<SongHistoryEntity> = dao.getAllHistory()

    override suspend fun getAllLyrics(): List<LyricsEntity> = dao.getAllLyrics()

    override suspend fun <T> runInTransaction(block: suspend BackupDatabaseTx.() -> T): T {
        return database.withTransaction {
            val tx = object : BackupDatabaseTx {
                override suspend fun getAllHistory(): List<SongHistoryEntity> = dao.getAllHistory()

                override suspend fun replaceAllHistory(entries: List<SongHistoryEntity>) {
                    dao.clearAllHistory()
                    if (entries.isNotEmpty()) {
                        dao.insertHistoryEntries(entries)
                    }
                }

                override suspend fun insertHistory(entries: List<SongHistoryEntity>) {
                    if (entries.isNotEmpty()) {
                        dao.insertHistoryEntries(entries)
                    }
                }

                override suspend fun getAllLyrics(): List<LyricsEntity> = dao.getAllLyrics()

                override suspend fun upsertLyrics(entries: List<LyricsEntity>) {
                    if (entries.isNotEmpty()) {
                        dao.insertLyricsEntries(entries)
                    }
                }

                override suspend fun deleteLyricsByKeys(keys: List<String>) {
                    if (keys.isNotEmpty()) {
                        dao.deleteLyricsByKeys(keys)
                    }
                }
            }
            tx.block()
        }
    }
}
