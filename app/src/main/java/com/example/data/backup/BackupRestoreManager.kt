package com.example.data.backup

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.example.BuildConfig
import com.example.data.local.LyricBarDatabase
import com.example.data.local.entity.LyricsEntity
import com.example.data.local.entity.SongHistoryEntity
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.CompositeLyricsRepository
import com.example.domain.parser.LrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Coordinates Selective Backup & Restore across DataStore and Room.
 *
 * Guarantees:
 * - Only explicitly selected categories are read on export and written on restore.
 * - Completed backups are validated (manifest + checksums + structure) before reporting success.
 * - Restores validate the entire archive before modifying persisted state, run Room operations
 *   in a single database transaction, apply DataStore updates atomically per selected category,
 *   and attempt rollback (or report exact partial failure) if a later step fails.
 * - Never writes or depends on package-private paths so v2.6.1 backups restore cleanly on v2.7.0+.
 * - Never logs private user data or lyrics contents.
 */
class BackupRestoreManager(
    private val preferencesStore: BackupPreferencesStore,
    private val databaseStore: BackupDatabaseStore,
    private val appVersion: String = BuildConfig.VERSION_NAME,
    private val appVersionCode: Int = BuildConfig.VERSION_CODE,
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val freeSpaceBytesProvider: (() -> Long)? = null
) {

    constructor(
        context: Context,
        preferencesRepository: UserPreferencesRepository,
        database: LyricBarDatabase = LyricBarDatabase.getInstance(context)
    ) : this(
        preferencesStore = DataStoreBackupPreferencesStore(preferencesRepository),
        databaseStore = RoomBackupDatabaseStore(database),
        appVersion = BuildConfig.VERSION_NAME,
        appVersionCode = BuildConfig.VERSION_CODE,
        freeSpaceBytesProvider = {
            try {
                val stat = StatFs(context.cacheDir.absolutePath)
                stat.availableBytes
            } catch (_: Exception) {
                Long.MAX_VALUE
            }
        }
    )

    suspend fun getLocalDataCounts(): LocalBackupDataCounts = withContext(Dispatchers.IO) {
        val history = deduplicateHistoryEntities(databaseStore.getAllHistory())
        val imported = filterPortableImportedLyrics(databaseStore.getAllLyrics())
        LocalBackupDataCounts(
            historyCount = history.size,
            importedLyricsCount = imported.size
        )
    }

    /**
     * Builds a validated selective backup archive and writes it directly to [outputStream].
     * Stages into memory/temp validation first so a broken write never claims success.
     */
    suspend fun createBackupToStream(
        selectedCategories: Set<BackupCategory>,
        outputStream: OutputStream,
        displayFileName: String = BackupZipCodec.defaultBackupFileName(nowProvider())
    ): BackupExportResult = withContext(Dispatchers.IO) {
        if (selectedCategories.isEmpty()) {
            return@withContext BackupExportResult.Failure(
                reason = "Select at least one category to back up.",
                errorCode = BackupErrorCode.EMPTY_SELECTION
            )
        }

        try {
            val zipBytes = buildAndValidateBackupBytes(
                selectedCategories = selectedCategories,
                displayFileName = displayFileName
            )

            val available = freeSpaceBytesProvider?.invoke() ?: Long.MAX_VALUE
            val required = zipBytes.size.toLong() + MIN_FREE_SPACE_HEADROOM_BYTES
            if (available < required) {
                return@withContext BackupExportResult.Failure(
                    reason = "Not enough free storage space to save this backup.",
                    errorCode = BackupErrorCode.INSUFFICIENT_STORAGE
                )
            }

            try {
                outputStream.write(zipBytes)
                outputStream.flush()
            } catch (e: IOException) {
                val isSpace = isInsufficientSpaceError(e)
                return@withContext BackupExportResult.Failure(
                    reason = if (isSpace) {
                        "Not enough storage space to finish writing the backup file."
                    } else {
                        "Failed to write backup file. Please check the destination location and try again."
                    },
                    errorCode = if (isSpace) BackupErrorCode.INSUFFICIENT_STORAGE else BackupErrorCode.WRITE_FAILED
                )
            }

            BackupExportResult.Success(
                fileName = displayFileName,
                includedCategories = selectedCategories.toSet(),
                totalBytesWritten = zipBytes.size.toLong()
            )
        } catch (e: BackupException) {
            BackupExportResult.Failure(e.message, e.errorCode)
        } catch (e: IOException) {
            val isSpace = isInsufficientSpaceError(e)
            BackupExportResult.Failure(
                reason = if (isSpace) {
                    "Not enough storage space to create the backup."
                } else {
                    "Could not create backup file due to an I/O error."
                },
                errorCode = if (isSpace) BackupErrorCode.INSUFFICIENT_STORAGE else BackupErrorCode.IO_ERROR
            )
        } catch (_: Exception) {
            BackupExportResult.Failure(
                reason = "An unexpected error occurred while creating the backup.",
                errorCode = BackupErrorCode.WRITE_FAILED
            )
        }
    }

    /**
     * Creates a validated backup via Android Storage Access Framework (SAF) [uri].
     * Stages to `cacheDir/backup_staging/`, verifies integrity of the completed archive,
     * copies to the SAF destination, and removes partial documents if writing fails.
     */
    suspend fun createBackupToUri(
        context: Context,
        uri: Uri,
        selectedCategories: Set<BackupCategory>
    ): BackupExportResult = withContext(Dispatchers.IO) {
        if (selectedCategories.isEmpty()) {
            return@withContext BackupExportResult.Failure(
                reason = "Select at least one category to back up.",
                errorCode = BackupErrorCode.EMPTY_SELECTION
            )
        }

        val displayFileName = queryDisplayName(context, uri)
            ?: BackupZipCodec.defaultBackupFileName(nowProvider())
        val baseDir = context.cacheDir ?: context.filesDir
        val stagingDir = File(baseDir, "backup_staging").apply { mkdirs() }
        val stagingFile = File(stagingDir, "staging_${nowProvider()}_${System.nanoTime()}.zip")

        createBackupViaStagingFile(
            stagingFile = stagingFile,
            displayFileName = displayFileName,
            selectedCategories = selectedCategories,
            openDestinationStream = { openOutputStreamSafely(context, uri) },
            onPartialWriteCleanup = { cleanupPartialDocumentQuietly(context, uri) }
        )
    }

    internal suspend fun createBackupViaStagingFile(
        stagingFile: File,
        displayFileName: String,
        selectedCategories: Set<BackupCategory>,
        openDestinationStream: () -> OutputStream,
        onPartialWriteCleanup: () -> Unit = {}
    ): BackupExportResult = withContext(Dispatchers.IO) {
        if (selectedCategories.isEmpty()) {
            return@withContext BackupExportResult.Failure(
                reason = "Select at least one category to back up.",
                errorCode = BackupErrorCode.EMPTY_SELECTION
            )
        }

        try {
            val available = freeSpaceBytesProvider?.invoke() ?: Long.MAX_VALUE
            if (available < MIN_STAGING_FREE_BYTES) {
                return@withContext BackupExportResult.Failure(
                    reason = "Not enough free storage space to prepare the backup.",
                    errorCode = BackupErrorCode.INSUFFICIENT_STORAGE
                )
            }

            stagingFile.parentFile?.mkdirs()

            val gathered = gatherSelectedData(selectedCategories)
            FileOutputStream(stagingFile).use { fos ->
                BackupZipCodec.writeBackupZip(
                    outputStream = fos,
                    selectedCategories = selectedCategories,
                    appVersion = appVersion,
                    appVersionCode = appVersionCode,
                    createdAtEpochMs = nowProvider(),
                    databaseSchemaVersion = DATABASE_SCHEMA_VERSION,
                    appSettings = gathered.appSettings,
                    hudConfig = gathered.hudConfig,
                    lyricsHistory = gathered.lyricsHistory,
                    importedLyrics = gathered.importedLyrics
                )
                // Note: BackupZipCodec.writeBackupZip finishes, flushes, and closes ZipOutputStream
                // (and underlying FileOutputStream). Do NOT call fos.fd.sync() after close().
            }

            // Re-read and verify the completed staging archive before writing to the user's SAF destination
            FileInputStream(stagingFile).use { fis ->
                BackupZipCodec.readAndValidateBackupZip(fis, displayFileName)
            }

            val totalSize = stagingFile.length()
            val outStream = openDestinationStream()

            var writeCompleted = false
            try {
                outStream.use { dest ->
                    FileInputStream(stagingFile).use { src ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = src.read(buffer)
                            if (read == -1) break
                            dest.write(buffer, 0, read)
                        }
                        dest.flush()
                    }
                }
                writeCompleted = true
            } catch (e: IOException) {
                if (!writeCompleted) {
                    onPartialWriteCleanup()
                }
                val isSpace = isInsufficientSpaceError(e)
                return@withContext BackupExportResult.Failure(
                    reason = if (isSpace) {
                        "Insufficient storage space at the chosen destination."
                    } else {
                        "Failed while writing the backup file. No completed backup was saved."
                    },
                    errorCode = if (isSpace) BackupErrorCode.INSUFFICIENT_STORAGE else BackupErrorCode.WRITE_FAILED
                )
            }

            BackupExportResult.Success(
                fileName = displayFileName,
                includedCategories = selectedCategories.toSet(),
                totalBytesWritten = totalSize
            )
        } catch (e: BackupException) {
            BackupExportResult.Failure(e.message, e.errorCode)
        } catch (e: SecurityException) {
            BackupExportResult.Failure(
                reason = "Permission denied when accessing the selected location.",
                errorCode = BackupErrorCode.PERMISSION_DENIED
            )
        } catch (e: IOException) {
            val isSpace = isInsufficientSpaceError(e)
            BackupExportResult.Failure(
                reason = if (isSpace) {
                    "Not enough free storage space to save this backup."
                } else {
                    "Failed to save backup file due to an I/O error."
                },
                errorCode = if (isSpace) BackupErrorCode.INSUFFICIENT_STORAGE else BackupErrorCode.IO_ERROR
            )
        } catch (_: Exception) {
            BackupExportResult.Failure(
                reason = "Failed to create backup file.",
                errorCode = BackupErrorCode.WRITE_FAILED
            )
        } finally {
            try {
                if (stagingFile.exists()) {
                    stagingFile.delete()
                }
            } catch (_: Exception) {
                // Best-effort cleanup
            }
        }
    }

    private fun openOutputStreamSafely(context: Context, uri: Uri): OutputStream {
        val resolver = context.contentResolver
        val modes = listOf("wt", "w", null)
        var lastError: Throwable? = null
        for (mode in modes) {
            try {
                val stream = if (mode != null) {
                    resolver.openOutputStream(uri, mode)
                } else {
                    resolver.openOutputStream(uri)
                }
                if (stream != null) {
                    return stream
                }
            } catch (e: SecurityException) {
                throw BackupException(
                    BackupErrorCode.PERMISSION_DENIED,
                    "Permission denied when writing to the selected location.",
                    e
                )
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (lastError is IOException) {
            throw lastError
        }
        throw BackupException(
            BackupErrorCode.WRITE_FAILED,
            "Unable to open the selected destination file for writing.",
            lastError
        )
    }

    /**
     * Inspects and validates a backup archive from an [InputStream].
     */
    suspend fun inspectBackupStream(
        inputStream: InputStream,
        displayFileName: String
    ): BackupInspectionResult = withContext(Dispatchers.IO) {
        try {
            val inspection = BackupZipCodec.readAndValidateBackupZip(
                inputStream = inputStream,
                displayFileName = displayFileName
            )
            BackupInspectionResult.Valid(inspection)
        } catch (e: BackupException) {
            BackupInspectionResult.Invalid(e.message, e.errorCode)
        } catch (_: Exception) {
            BackupInspectionResult.Invalid(
                reason = "Unable to read the selected backup file.",
                errorCode = BackupErrorCode.CORRUPTED_ARCHIVE
            )
        }
    }

    /**
     * Inspects and validates a user-selected backup ZIP via SAF [uri].
     */
    suspend fun inspectBackupUri(
        context: Context,
        uri: Uri
    ): BackupInspectionResult = withContext(Dispatchers.IO) {
        val displayFileName = queryDisplayName(context, uri) ?: "Selected backup.zip"
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext BackupInspectionResult.Invalid(
                    reason = "Unable to open the selected backup file.",
                    errorCode = BackupErrorCode.IO_ERROR
                )
            stream.use { input ->
                inspectBackupStream(input, displayFileName)
            }
        } catch (e: SecurityException) {
            BackupInspectionResult.Invalid(
                reason = "Permission denied when opening the selected backup file.",
                errorCode = BackupErrorCode.PERMISSION_DENIED
            )
        } catch (_: Exception) {
            BackupInspectionResult.Invalid(
                reason = "Could not open the selected backup file.",
                errorCode = BackupErrorCode.IO_ERROR
            )
        }
    }

    /**
     * Analyses existing local data against the user's selected categories so the confirmation
     * dialog can show accurate duplicate / conflict counts before making any changes.
     */
    suspend fun computeConflictSummary(
        inspection: BackupArchiveInspection,
        selectedCategories: Set<BackupCategory>
    ): RestoreConflictSummary = withContext(Dispatchers.IO) {
        var existingHistoryCount = 0
        var incomingHistoryCount = 0
        var duplicateHistoryCount = 0
        var newHistoryCount = 0

        if (BackupCategory.LYRICS_HISTORY in selectedCategories) {
            val existing = databaseStore.getAllHistory()
            val incoming = inspection.payload.lyricsHistory?.entries ?: emptyList()
            existingHistoryCount = existing.size
            incomingHistoryCount = incoming.size
            val existingFingerprints = buildHistoryFingerprints(existing)
            val seenIncoming = mutableSetOf<String>()
            for (entry in incoming) {
                val fp = historyFingerprint(entry.key, entry.title, entry.artist, entry.playedAt)
                if (fp in existingFingerprints || !seenIncoming.add(fp)) {
                    duplicateHistoryCount++
                } else {
                    newHistoryCount++
                }
            }
        }

        var existingImportedCount = 0
        var incomingImportedCount = 0
        var conflictingImportedCount = 0
        var newImportedCount = 0

        if (BackupCategory.IMPORTED_LYRICS in selectedCategories) {
            val existingImported = filterPortableImportedLyrics(databaseStore.getAllLyrics())
            val existingKeys = existingImported.map { it.key.lowercase(Locale.US) }.toSet()
            val incoming = inspection.payload.importedLyrics ?: emptyList()
            existingImportedCount = existingImported.size
            incomingImportedCount = incoming.size
            for (record in incoming) {
                if (record.meta.key.lowercase(Locale.US) in existingKeys) {
                    conflictingImportedCount++
                } else {
                    newImportedCount++
                }
            }
        }

        RestoreConflictSummary(
            existingHistoryCount = existingHistoryCount,
            incomingHistoryCount = incomingHistoryCount,
            duplicateHistoryCount = duplicateHistoryCount,
            newHistoryCount = newHistoryCount,
            existingImportedCount = existingImportedCount,
            incomingImportedCount = incomingImportedCount,
            conflictingImportedCount = conflictingImportedCount,
            newImportedCount = newImportedCount
        )
    }

    /**
     * Selectively restores ONLY [selectedCategories] from a validated [inspection].
     *
     * Safety & transaction flow:
     * 1. Validates selection against available categories in the backup before writing anything.
     * 2. Captures pre-restore snapshots of affected DB rows so if a subsequent DataStore write
     *    fails, DB mutations can be rolled back cleanly (or partial failure reported accurately).
     * 3. Executes all selected Room mutations inside one atomic `runInTransaction` block.
     * 4. Executes all selected DataStore mutations inside one atomic `applySelective` edit.
     */
    suspend fun restoreBackup(
        inspection: BackupArchiveInspection,
        selectedCategories: Set<BackupCategory>,
        historyStrategy: HistoryConflictStrategy = HistoryConflictStrategy.MERGE_PRESERVE_EXISTING,
        importedLyricsStrategy: ImportedLyricsConflictStrategy = ImportedLyricsConflictStrategy.SKIP_EXISTING
    ): BackupRestoreResult = withContext(Dispatchers.IO) {
        val validSelection = selectedCategories.filter { it in inspection.availableCategories }.toSet()
        if (validSelection.isEmpty()) {
            return@withContext BackupRestoreResult.Failure(
                reason = "Select at least one available category to restore.",
                errorCode = BackupErrorCode.EMPTY_SELECTION
            )
        }

        val restoreAppSettings = if (BackupCategory.APP_SETTINGS in validSelection) {
            inspection.payload.appSettings ?: return@withContext BackupRestoreResult.Failure(
                reason = "App Settings payload is missing from this backup.",
                errorCode = BackupErrorCode.CORRUPTED_ARCHIVE
            )
        } else null

        val restoreHudConfig = if (BackupCategory.HUD_CONFIGURATION in validSelection) {
            inspection.payload.hudConfig ?: return@withContext BackupRestoreResult.Failure(
                reason = "HUD Configuration payload is missing from this backup.",
                errorCode = BackupErrorCode.CORRUPTED_ARCHIVE
            )
        } else null

        val restoreHistory = if (BackupCategory.LYRICS_HISTORY in validSelection) {
            inspection.payload.lyricsHistory ?: return@withContext BackupRestoreResult.Failure(
                reason = "Lyrics History payload is missing from this backup.",
                errorCode = BackupErrorCode.CORRUPTED_ARCHIVE
            )
        } else null

        val restoreImported = if (BackupCategory.IMPORTED_LYRICS in validSelection) {
            inspection.payload.importedLyrics ?: return@withContext BackupRestoreResult.Failure(
                reason = "Imported Lyrics payload is missing from this backup.",
                errorCode = BackupErrorCode.CORRUPTED_ARCHIVE
            )
        } else null

        // Pre-validate all imported lyrics BEFORE touching database or preferences
        val validatedImportedEntities: List<LyricsEntity> = if (restoreImported != null) {
            try {
                buildValidatedImportedEntities(restoreImported)
            } catch (e: BackupException) {
                return@withContext BackupRestoreResult.Failure(e.message, e.errorCode)
            }
        } else emptyList()

        val dbCategories = buildSet {
            if (restoreHistory != null) add(BackupCategory.LYRICS_HISTORY)
            if (restoreImported != null) add(BackupCategory.IMPORTED_LYRICS)
        }
        val prefCategories = buildSet {
            if (restoreAppSettings != null) add(BackupCategory.APP_SETTINGS)
            if (restoreHudConfig != null) add(BackupCategory.HUD_CONFIGURATION)
        }

        // Capture pre-restore DB snapshot for recoverability if DataStore fails afterwards
        val preRestoreHistorySnapshot: List<SongHistoryEntity>? =
            if (restoreHistory != null) databaseStore.getAllHistory() else null
        val preRestoreLyricsSnapshot: List<LyricsEntity>? =
            if (restoreImported != null) databaseStore.getAllLyrics() else null

        val details = mutableListOf<CategoryRestoreDetail>()

        // Step 1: Transactional Room database restore (only for selected DB categories)
        if (dbCategories.isNotEmpty()) {
            try {
                val dbOutcome = databaseStore.runInTransaction {
                    var historySummary: String? = null
                    var importedSummary: String? = null

                    if (restoreHistory != null) {
                        val incomingEntities = restoreHistory.entries.map { entry ->
                            SongHistoryEntity(
                                id = 0,
                                key = entry.key.trim(),
                                title = entry.title.trim(),
                                artist = entry.artist.trim(),
                                albumArtUri = sanitizePortableAlbumArtUri(entry.albumArtUri),
                                status = entry.status.ifBlank { "Played" },
                                playedAt = entry.playedAt.coerceAtLeast(0L)
                            )
                        }
                        when (historyStrategy) {
                            HistoryConflictStrategy.REPLACE_EXISTING -> {
                                val deduped = deduplicateHistoryEntities(incomingEntities)
                                replaceAllHistory(deduped)
                                historySummary = "Replaced with ${deduped.size} history record(s)"
                            }
                            HistoryConflictStrategy.MERGE_PRESERVE_EXISTING -> {
                                val existing = getAllHistory()
                                val existingFingerprints = buildHistoryFingerprints(existing).toMutableSet()
                                val toInsert = mutableListOf<SongHistoryEntity>()
                                var skippedDuplicates = 0
                                for (entity in incomingEntities) {
                                    val fp = historyFingerprint(entity.key, entity.title, entity.artist, entity.playedAt)
                                    if (existingFingerprints.add(fp)) {
                                        toInsert.add(entity)
                                    } else {
                                        skippedDuplicates++
                                    }
                                }
                                if (toInsert.isNotEmpty()) {
                                    insertHistory(toInsert)
                                }
                                historySummary = if (skippedDuplicates > 0) {
                                    "Added ${toInsert.size} new record(s), kept $skippedDuplicates existing duplicate(s)"
                                } else {
                                    "Added ${toInsert.size} history record(s)"
                                }
                            }
                        }
                    }

                    if (restoreImported != null) {
                        val existingAll = getAllLyrics()
                        val existingImportedKeys = filterPortableImportedLyrics(existingAll)
                            .map { it.key.lowercase(Locale.US) }
                            .toSet()

                        val toUpsert = mutableListOf<LyricsEntity>()
                        var skippedConflicts = 0
                        var replacedConflicts = 0

                        for (entity in validatedImportedEntities) {
                            val hasConflict = entity.key.lowercase(Locale.US) in existingImportedKeys
                            if (hasConflict) {
                                when (importedLyricsStrategy) {
                                    ImportedLyricsConflictStrategy.SKIP_EXISTING -> {
                                        skippedConflicts++
                                    }
                                    ImportedLyricsConflictStrategy.REPLACE_EXISTING -> {
                                        toUpsert.add(entity)
                                        replacedConflicts++
                                    }
                                }
                            } else {
                                toUpsert.add(entity)
                            }
                        }

                        if (toUpsert.isNotEmpty()) {
                            upsertLyrics(toUpsert)
                        }

                        importedSummary = when {
                            skippedConflicts > 0 ->
                                "Restored ${toUpsert.size} file(s), kept $skippedConflicts existing file(s)"
                            replacedConflicts > 0 ->
                                "Restored ${toUpsert.size} file(s) ($replacedConflicts replaced)"
                            else ->
                                "Restored ${toUpsert.size} imported lyric file(s)"
                        }
                    }

                    Pair(historySummary, importedSummary)
                }

                dbOutcome.first?.let {
                    details.add(CategoryRestoreDetail(BackupCategory.LYRICS_HISTORY, it))
                }
                dbOutcome.second?.let {
                    details.add(CategoryRestoreDetail(BackupCategory.IMPORTED_LYRICS, it))
                }
            } catch (_: Exception) {
                // Room rolled back the transaction automatically; DataStore has not been modified yet
                return@withContext BackupRestoreResult.Failure(
                    reason = "Failed while restoring database records. Your existing data was preserved.",
                    errorCode = BackupErrorCode.RESTORE_FAILED
                )
            }
        }

        // Step 2: Atomic DataStore selective restore (only for selected preference categories)
        if (prefCategories.isNotEmpty()) {
            try {
                preferencesStore.applySelective(
                    appSettings = restoreAppSettings,
                    hudConfig = restoreHudConfig
                )
                if (restoreAppSettings != null) {
                    details.add(
                        CategoryRestoreDetail(
                            BackupCategory.APP_SETTINGS,
                            "Restored themes, fonts, provider and display preferences"
                        )
                    )
                }
                if (restoreHudConfig != null) {
                    details.add(
                        CategoryRestoreDetail(
                            BackupCategory.HUD_CONFIGURATION,
                            "Restored HUD position, size, backdrop, colours and behaviour"
                        )
                    )
                }
            } catch (_: Exception) {
                // Preferences write failed; if DB categories were already applied, attempt rollback
                if (dbCategories.isNotEmpty()) {
                    val rollbackSucceeded = attemptDatabaseRollback(
                        preRestoreHistorySnapshot = preRestoreHistorySnapshot,
                        preRestoreLyricsSnapshot = preRestoreLyricsSnapshot
                    )
                    return@withContext if (rollbackSucceeded) {
                        BackupRestoreResult.Failure(
                            reason = "Failed to save restored preferences. Previous database state was rolled back.",
                            errorCode = BackupErrorCode.RESTORE_FAILED
                        )
                    } else {
                        BackupRestoreResult.PartialFailure(
                            restoredCategories = dbCategories,
                            failedCategories = prefCategories,
                            reason = "Database categories were restored, but saving preferences failed."
                        )
                    }
                }
                return@withContext BackupRestoreResult.Failure(
                    reason = "Failed to save restored preferences. Your existing preferences remain unchanged.",
                    errorCode = BackupErrorCode.RESTORE_FAILED
                )
            }
        }

        BackupRestoreResult.Success(
            restoredCategories = validSelection,
            details = details
        )
    }

    private suspend fun attemptDatabaseRollback(
        preRestoreHistorySnapshot: List<SongHistoryEntity>?,
        preRestoreLyricsSnapshot: List<LyricsEntity>?
    ): Boolean {
        return try {
            databaseStore.runInTransaction {
                if (preRestoreHistorySnapshot != null) {
                    replaceAllHistory(preRestoreHistorySnapshot)
                }
                if (preRestoreLyricsSnapshot != null) {
                    val currentKeys = getAllLyrics().map { it.key }.toSet()
                    val snapshotKeys = preRestoreLyricsSnapshot.map { it.key }.toSet()
                    val extraKeys = (currentKeys - snapshotKeys).toList()
                    if (extraKeys.isNotEmpty()) {
                        deleteLyricsByKeys(extraKeys)
                    }
                    upsertLyrics(preRestoreLyricsSnapshot)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun buildAndValidateBackupBytes(
        selectedCategories: Set<BackupCategory>,
        displayFileName: String
    ): ByteArray {
        val gathered = gatherSelectedData(selectedCategories)
        val out = ByteArrayOutputStream()
        BackupZipCodec.writeBackupZip(
            outputStream = out,
            selectedCategories = selectedCategories,
            appVersion = appVersion,
            appVersionCode = appVersionCode,
            createdAtEpochMs = nowProvider(),
            databaseSchemaVersion = DATABASE_SCHEMA_VERSION,
            appSettings = gathered.appSettings,
            hudConfig = gathered.hudConfig,
            lyricsHistory = gathered.lyricsHistory,
            importedLyrics = gathered.importedLyrics
        )
        val bytes = out.toByteArray()
        // Integrity self-check before reporting success or writing to final target
        BackupZipCodec.readAndValidateBackupZip(
            inputStream = ByteArrayInputStream(bytes),
            displayFileName = displayFileName
        )
        return bytes
    }

    private data class GatheredBackupData(
        val appSettings: BackupAppSettingsData? = null,
        val hudConfig: BackupHudConfigData? = null,
        val lyricsHistory: BackupHistoryPayload? = null,
        val importedLyrics: List<BackupImportedLyricRecord>? = null
    )

    private suspend fun gatherSelectedData(selectedCategories: Set<BackupCategory>): GatheredBackupData {
        val appSettings = if (BackupCategory.APP_SETTINGS in selectedCategories) {
            preferencesStore.readAppSettings()
        } else null

        val hudConfig = if (BackupCategory.HUD_CONFIGURATION in selectedCategories) {
            preferencesStore.readHudConfig()
        } else null

        val lyricsHistory = if (BackupCategory.LYRICS_HISTORY in selectedCategories) {
            val deduped = deduplicateHistoryEntities(databaseStore.getAllHistory())
            BackupHistoryPayload(
                schemaVersion = 1,
                entries = deduped.map { entity ->
                    BackupHistoryEntry(
                        key = entity.key,
                        title = entity.title,
                        artist = entity.artist,
                        albumArtUri = sanitizePortableAlbumArtUri(entity.albumArtUri),
                        status = entity.status,
                        playedAt = entity.playedAt
                    )
                }
            )
        } else null

        val importedLyrics = if (BackupCategory.IMPORTED_LYRICS in selectedCategories) {
            val entities = filterPortableImportedLyrics(databaseStore.getAllLyrics())
            entities.mapNotNull { entity ->
                val rawText = (entity.syncedLrc ?: entity.plainLyrics ?: "").trim()
                if (rawText.isBlank()) {
                    null
                } else {
                    BackupImportedLyricRecord(
                        meta = BackupImportedLyricMeta(
                            key = entity.key,
                            title = entity.title,
                            artist = entity.artist,
                            filename = "",
                            isSynced = entity.isSynced,
                            durationSec = entity.durationSec,
                            source = CompositeLyricsRepository.normalizeCachedSource(entity.source),
                            cachedAt = entity.cachedAt,
                            sha256 = ""
                        ),
                        content = rawText
                    )
                }
            }
        } else null

        return GatheredBackupData(
            appSettings = appSettings,
            hudConfig = hudConfig,
            lyricsHistory = lyricsHistory,
            importedLyrics = importedLyrics
        )
    }

    private fun buildValidatedImportedEntities(
        records: List<BackupImportedLyricRecord>
    ): List<LyricsEntity> {
        val result = mutableListOf<LyricsEntity>()
        val seenKeys = mutableSetOf<String>()
        for (record in records) {
            val key = record.meta.key.trim()
            if (key.isBlank() || !seenKeys.add(key.lowercase(Locale.US))) {
                continue
            }
            val cleanContent = record.content.removePrefix("\uFEFF").trim()
            if (cleanContent.isBlank() || cleanContent.contains('\u0000') || LrcParser.isTtml(cleanContent)) {
                throw BackupException(
                    BackupErrorCode.INVALID_BACKUP,
                    "Backup contains invalid or unsupported imported lyric content."
                )
            }
            val parsed = LrcParser.parse(
                content = cleanContent,
                title = record.meta.title.ifBlank { "Imported Track" },
                artist = record.meta.artist.ifBlank { "Local File" },
                source = "Local Import"
            )
            if (!parsed.hasValidLyrics) {
                throw BackupException(
                    BackupErrorCode.INVALID_BACKUP,
                    "Backup contains an imported lyric file with no valid lyrics."
                )
            }
            result.add(
                LyricsEntity(
                    key = key,
                    title = record.meta.title.ifBlank { parsed.title },
                    artist = record.meta.artist.ifBlank { parsed.artist },
                    syncedLrc = if (parsed.isSynced) cleanContent else null,
                    plainLyrics = parsed.plainText,
                    isSynced = parsed.isSynced,
                    durationSec = record.meta.durationSec,
                    source = "Local Import",
                    cachedAt = record.meta.cachedAt.takeIf { it > 0L } ?: nowProvider()
                )
            )
        }
        return result
    }

    private fun cleanupPartialDocumentQuietly(context: Context, uri: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        } catch (_: Exception) {
            // Some document providers do not support deleteDocument
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1 && cursor.moveToFirst() && !cursor.isNull(idx)) {
                    cursor.getString(idx)?.takeIf { it.isNotBlank() }
                } else null
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    private fun isInsufficientSpaceError(error: Throwable): Boolean {
        val msg = error.message?.lowercase(Locale.US) ?: ""
        return msg.contains("enospc") ||
            msg.contains("no space left") ||
            msg.contains("disk full") ||
            msg.contains("insufficient storage")
    }

    companion object {
        private const val DATABASE_SCHEMA_VERSION = 2
        private const val MIN_FREE_SPACE_HEADROOM_BYTES = 256L * 1024L // 256 KB headroom
        private const val MIN_STAGING_FREE_BYTES = 2L * 1024L * 1024L // 2 MB staging minimum

        /**
         * Filters Room `lyrics` rows strictly to user-imported local LRC entries,
         * excluding disposable online provider caches and provider-scoped duplicates.
         */
        internal fun filterPortableImportedLyrics(allLyrics: List<LyricsEntity>): List<LyricsEntity> {
            return allLyrics.filter { entity ->
                !entity.key.contains("::provider::", ignoreCase = true) &&
                    CompositeLyricsRepository.isLocalImportSource(entity.source)
            }
        }

        internal fun deduplicateHistoryEntities(entries: List<SongHistoryEntity>): List<SongHistoryEntity> {
            val seen = mutableSetOf<String>()
            val result = mutableListOf<SongHistoryEntity>()
            for (entry in entries) {
                val fp = historyFingerprint(entry.key, entry.title, entry.artist, entry.playedAt)
                if (seen.add(fp)) {
                    result.add(entry)
                }
            }
            return result
        }

        internal fun buildHistoryFingerprints(entries: List<SongHistoryEntity>): Set<String> {
            return entries.map { historyFingerprint(it.key, it.title, it.artist, it.playedAt) }.toSet()
        }

        internal fun historyFingerprint(
            key: String,
            title: String,
            artist: String,
            playedAt: Long
        ): String {
            val normKey = key.trim().lowercase(Locale.US)
            val normTitle = title.trim().lowercase(Locale.US)
            val normArtist = artist.trim().lowercase(Locale.US)
            return "$normKey|$normTitle|$normArtist|$playedAt"
        }

        /**
         * Strips package-private file paths or FileProvider URIs tied to the old Android
         * applicationId so backups remain portable across package migrations (v2.6.1 -> v2.7.0).
         */
        internal fun sanitizePortableAlbumArtUri(rawUri: String?): String? {
            val trimmed = rawUri?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val lower = trimmed.lowercase(Locale.US)
            if (lower.startsWith("file:///data/") ||
                lower.startsWith("/data/") ||
                lower.contains(".fileprovider/")
            ) {
                return null
            }
            return trimmed
        }
    }
}
