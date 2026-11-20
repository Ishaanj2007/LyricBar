package com.example.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.longPreferencesKey
import com.example.data.local.entity.LyricsEntity
import com.example.data.local.entity.SongHistoryEntity
import com.example.data.preferences.FontOption
import com.example.data.preferences.HudBackdropStyle
import com.example.data.preferences.LyricsProviderPreference
import com.example.data.preferences.OverlayAnimationStyle
import com.example.data.preferences.ThemeMode
import com.example.data.preferences.ThemePreset
import com.example.data.preferences.UserPreferencesRepository
import com.example.service.HudOrientation
import com.example.ui.screens.BackupCategorySelectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupRestoreSystemTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =========================================================================
    // Test Fakes & Builders (Real Jetpack DataStore + Transactional In-Memory DB)
    // =========================================================================

    private fun createRealPrefsRepo(name: String): UserPreferencesRepository {
        val file = tempFolder.newFile("$name.preferences_pb")
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { file }
        )
        return UserPreferencesRepository(ds)
    }

    private class InMemoryTransactionalDatabaseStore(
        initialHistory: List<SongHistoryEntity> = emptyList(),
        initialLyrics: List<LyricsEntity> = emptyList()
    ) : BackupDatabaseStore {
        val history = initialHistory.toMutableList()
        val lyrics = initialLyrics.associateBy { it.key }.toMutableMap()
        var failNextTransaction: Boolean = false
        var failTransactionCountRemaining: Int = 0

        override suspend fun getAllHistory(): List<SongHistoryEntity> =
            history.sortedWith(compareByDescending<SongHistoryEntity> { it.playedAt }.thenByDescending { it.id })

        override suspend fun getAllLyrics(): List<LyricsEntity> =
            lyrics.values.sortedByDescending { it.cachedAt }

        override suspend fun <T> runInTransaction(block: suspend BackupDatabaseTx.() -> T): T {
            // Snapshot-based atomic rollback just like SQLite/Room transactions
            val historyBackup = history.toList()
            val lyricsBackup = lyrics.toMap()
            var nextId = (history.maxOfOrNull { it.id } ?: 0L) + 1L

            val tx = object : BackupDatabaseTx {
                override suspend fun getAllHistory(): List<SongHistoryEntity> =
                    history.sortedWith(compareByDescending<SongHistoryEntity> { it.playedAt }.thenByDescending { it.id })

                override suspend fun replaceAllHistory(entries: List<SongHistoryEntity>) {
                    history.clear()
                    insertHistory(entries)
                }

                override suspend fun insertHistory(entries: List<SongHistoryEntity>) {
                    for (entry in entries) {
                        val id = if (entry.id > 0L) entry.id else nextId++
                        history.add(entry.copy(id = id))
                    }
                }

                override suspend fun getAllLyrics(): List<LyricsEntity> =
                    lyrics.values.sortedByDescending { it.cachedAt }

                override suspend fun upsertLyrics(entries: List<LyricsEntity>) {
                    for (entry in entries) {
                        lyrics[entry.key] = entry
                    }
                }

                override suspend fun deleteLyricsByKeys(keys: List<String>) {
                    for (k in keys) lyrics.remove(k)
                }
            }

            return try {
                val result = tx.block()
                if (failNextTransaction || failTransactionCountRemaining > 0) {
                    failNextTransaction = false
                    if (failTransactionCountRemaining > 0) failTransactionCountRemaining--
                    throw IOException("Simulated SQLite commit error")
                }
                result
            } catch (e: Throwable) {
                history.clear()
                history.addAll(historyBackup)
                lyrics.clear()
                lyrics.putAll(lyricsBackup)
                throw e
            }
        }
    }

    private suspend fun populateRichSourceState(
        prefsRepo: UserPreferencesRepository,
        db: InMemoryTransactionalDatabaseStore
    ) {
        // App Settings
        prefsRepo.setThemeMode(ThemeMode.DARK)
        prefsRepo.setThemePreset(ThemePreset.LAVENDER_MIST)
        prefsRepo.setUseWallpaperDynamicColor(true)
        prefsRepo.setSelectedFont(FontOption.MONTSERRAT)
        prefsRepo.setLyricsProvider(LyricsProviderPreference.UNISON)
        prefsRepo.setAutoShowOnPlayback(false)
        prefsRepo.setAutoFetchLyrics(false)
        prefsRepo.setAutoUpdateChecksEnabled(false)
        prefsRepo.setAnimationStyle(OverlayAnimationStyle.SMOOTH_SLIDE.name)
        prefsRepo.setUpwardAnimationEnabled(false)
        prefsRepo.setAppleMusicStyle(false)
        prefsRepo.setSingleLineLyrics(true)
        prefsRepo.setAdaptiveLyricScrolling(false)
        prefsRepo.setLyricScrollSpeedMultiplier(1.35f)
        prefsRepo.setTypewriterSpeed(1.6f)
        prefsRepo.setSmoothTransitionSpeed(1.25f)
        prefsRepo.setFadeSpeed(0.8f)
        prefsRepo.setBlurRevealSpeed(1.4f)
        prefsRepo.setKaraokeSpeed(1.2f)
        prefsRepo.setTrackOffset("queen - bohemian rhapsody", 500L)

        // HUD Configuration
        prefsRepo.setOverlayEnabled(true)
        prefsRepo.setOverlayTextSize(18f)
        prefsRepo.setOverlayTextOpacity(0.85f)
        prefsRepo.setTextShadowEnabled(false)
        prefsRepo.setOverlayTextAlign("LEFT")
        prefsRepo.setClickThroughOverlay(true)
        prefsRepo.setHudBackdropEnabled(true)
        prefsRepo.setHudBackdropStyle(HudBackdropStyle.GREY)
        prefsRepo.setHudBackdropOpacity(0.72f)
        prefsRepo.setHudBackdropCornerRadiusDp(22)
        prefsRepo.setHudBackdropHorizontalPaddingDp(20)
        prefsRepo.setHudBackdropVerticalPaddingDp(12)
        prefsRepo.applyHudColorPair(0xFFFFD166.toInt(), 0xFF94A3B8.toInt())
        prefsRepo.saveCustomHudColor(0xFF00E5FF.toInt())
        prefsRepo.setOverlayBounds(42, 180, 740, 220, HudOrientation.PORTRAIT)
        prefsRepo.setOverlayBounds(120, 64, 980, 170, HudOrientation.LANDSCAPE)

        // Excluded transient / internal updater keys (MUST NOT be backed up)
        prefsRepo.setDragRepositionMode(true)
        prefsRepo.setLastUpdateCheckTimestampMs(1700000000000L)
        prefsRepo.setLastNotifiedUpdateVersion("2.6.0")
        prefsRepo.saveCachedReleaseMetadata("W/\"etag-123\"", "{\"tag_name\":\"v2.6.0\"}")
        prefsRepo.setHasRequestedUpdateNotificationPermission(true)

        // History (including portable HTTPS art URI and non-portable private package URI)
        db.history.addAll(
            listOf(
                SongHistoryEntity(
                    id = 1,
                    key = "queen - bohemian rhapsody",
                    title = "Bohemian Rhapsody",
                    artist = "Queen",
                    albumArtUri = "https://cdn.example.com/queen.jpg",
                    status = "Played",
                    playedAt = 1_763_600_000_000L
                ),
                SongHistoryEntity(
                    id = 2,
                    key = "arijit singh - kesariya",
                    title = "Kesariya",
                    artist = "Arijit Singh",
                    albumArtUri = "file:///data/data/com.aistudio.lyricbar.zmvpk/cache/art_2.png",
                    status = "Played",
                    playedAt = 1_763_600_120_000L
                )
            )
        )

        // Lyrics table: mix of user-imported local LRC AND disposable online cache (which must be excluded)
        val lrcQueen = "[00:01.00]Is this the real life?\n[00:05.00]Is this just fantasy?"
        val lrcKesariya = "[00:02.50]Mujhko itna bataye koi\n[00:07.00]Kaise tujhse dil na lagaye koi"
        val onlineCachedLrc = "[00:01.00]Disposable online LRCLIB cache line"

        db.lyrics["queen - bohemian rhapsody"] = LyricsEntity(
            key = "queen - bohemian rhapsody",
            title = "Bohemian Rhapsody",
            artist = "Queen",
            syncedLrc = lrcQueen,
            plainLyrics = "Is this the real life?\nIs this just fantasy?",
            isSynced = true,
            durationSec = 354,
            source = "Local Import",
            cachedAt = 1_763_600_000_000L
        )
        db.lyrics["arijit singh - kesariya"] = LyricsEntity(
            key = "arijit singh - kesariya",
            title = "Kesariya",
            artist = "Arijit Singh",
            syncedLrc = lrcKesariya,
            plainLyrics = "Mujhko itna bataye koi\nKaise tujhse dil na lagaye koi",
            isSynced = true,
            durationSec = 268,
            source = "Local .lrc",
            cachedAt = 1_763_600_100_000L
        )
        // Disposable online caches — must NOT be exported in IMPORTED_LYRICS
        db.lyrics["the weeknd - blinding lights"] = LyricsEntity(
            key = "the weeknd - blinding lights",
            title = "Blinding Lights",
            artist = "The Weeknd",
            syncedLrc = onlineCachedLrc,
            plainLyrics = "Disposable online LRCLIB cache line",
            isSynced = true,
            durationSec = 200,
            source = "LRCLIB",
            cachedAt = 1_763_600_200_000L
        )
        db.lyrics["queen - bohemian rhapsody::provider::unison"] = LyricsEntity(
            key = "queen - bohemian rhapsody::provider::unison",
            title = "Bohemian Rhapsody",
            artist = "Queen",
            syncedLrc = onlineCachedLrc,
            plainLyrics = "Disposable online LRCLIB cache line",
            isSynced = true,
            durationSec = 354,
            source = "Unison",
            cachedAt = 1_763_600_250_000L
        )
    }

    private fun readZipEntriesMap(zipBytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    entries[entry.name] = zis.readBytes()
                }
                zis.closeEntry()
            }
        }
        return entries
    }

    // =========================================================================
    // PHASE 11 — BACKUP TESTS (1..10)
    // =========================================================================

    @Test
    fun backup1_and_10_backUpAllSupportedCategories_andVerifyManifestAndChecksums() = runBlocking {
        val prefs = createRealPrefsRepo("b1")
        val db = InMemoryTransactionalDatabaseStore()
        populateRichSourceState(prefs, db)

        val fixedTime = 1_763_628_000_000L
        val manager = BackupRestoreManager(
            preferencesStore = DataStoreBackupPreferencesStore(prefs),
            databaseStore = db,
            appVersion = "2.6.1",
            appVersionCode = 10,
            nowProvider = { fixedTime }
        )

        val out = ByteArrayOutputStream()
        val exportResult = manager.createBackupToStream(
            selectedCategories = BackupCategory.ALL_SUPPORTED.toSet(),
            outputStream = out,
            displayFileName = "LyricBar_Backup_2026-11-20.zip"
        )

        assertTrue(exportResult is BackupExportResult.Success)
        val success = exportResult as BackupExportResult.Success
        assertEquals(BackupCategory.ALL_SUPPORTED.toSet(), success.includedCategories)
        assertTrue(success.totalBytesWritten > 0L)

        val zipEntries = readZipEntriesMap(out.toByteArray())
        assertTrue(BackupZipCodec.MANIFEST_PATH in zipEntries)
        assertTrue(BackupZipCodec.SETTINGS_PATH in zipEntries)
        assertTrue(BackupZipCodec.HUD_PATH in zipEntries)
        assertTrue(BackupZipCodec.HISTORY_PATH in zipEntries)
        assertTrue(BackupZipCodec.IMPORTED_INDEX_PATH in zipEntries)
        val lrcEntries = zipEntries.keys.filter { it.startsWith("imported_lyrics/") && it.endsWith(".lrc") }
        assertEquals("Only the 2 user-imported LRC files should be exported, never online caches", 2, lrcEntries.size)

        // Inspect archive & verify manifest fields and checksums
        val inspected = manager.inspectBackupStream(
            ByteArrayInputStream(out.toByteArray()),
            "LyricBar_Backup_2026-11-20.zip"
        )
        assertTrue(inspected is BackupInspectionResult.Valid)
        val valid = (inspected as BackupInspectionResult.Valid).inspection
        assertEquals(BackupManifest.CURRENT_FORMAT_VERSION, valid.manifest.formatVersion)
        assertEquals("lyricbar", valid.manifest.appId)
        assertEquals("2.6.1", valid.manifest.appVersion)
        assertEquals(10, valid.manifest.appVersionCode)
        assertEquals(fixedTime, valid.manifest.createdAtEpochMs)
        assertEquals(BackupCategory.ALL_SUPPORTED, valid.availableCategories)
        assertEquals(2, valid.categoryItemCounts[BackupCategory.LYRICS_HISTORY])
        assertEquals(2, valid.categoryItemCounts[BackupCategory.IMPORTED_LYRICS])
        // Every non-manifest file has a verified SHA-256 checksum
        assertEquals(zipEntries.keys - BackupZipCodec.MANIFEST_PATH, valid.manifest.checksums.keys)
    }

    @Test
    fun backup2_and_9_backUpOnlyAppSettings_excludesAllOtherCategoryFiles() = runBlocking {
        val prefs = createRealPrefsRepo("b2")
        val db = InMemoryTransactionalDatabaseStore()
        populateRichSourceState(prefs, db)
        val manager = BackupRestoreManager(DataStoreBackupPreferencesStore(prefs), db, "2.6.1", 10)

        val out = ByteArrayOutputStream()
        val res = manager.createBackupToStream(setOf(BackupCategory.APP_SETTINGS), out)
        assertTrue(res is BackupExportResult.Success)

        val entries = readZipEntriesMap(out.toByteArray())
        assertEquals(setOf("manifest.json", "settings/preferences.json"), entries.keys)

        val inspected = (manager.inspectBackupStream(ByteArrayInputStream(out.toByteArray()), "settings.zip")
            as BackupInspectionResult.Valid).inspection
        assertEquals(listOf(BackupCategory.APP_SETTINGS), inspected.availableCategories)
        assertNotNull(inspected.payload.appSettings)
        assertNull(inspected.payload.hudConfig)
        assertNull(inspected.payload.lyricsHistory)
        assertNull(inspected.payload.importedLyrics)
    }

    @Test
    fun backup3_backUpOnlyHudConfiguration_excludesAllOtherCategoryFiles() = runBlocking {
        val prefs = createRealPrefsRepo("b3")
        val db = InMemoryTransactionalDatabaseStore()
        populateRichSourceState(prefs, db)
        val manager = BackupRestoreManager(DataStoreBackupPreferencesStore(prefs), db, "2.6.1", 10)

        val out = ByteArrayOutputStream()
        val res = manager.createBackupToStream(setOf(BackupCategory.HUD_CONFIGURATION), out)
        assertTrue(res is BackupExportResult.Success)

        val entries = readZipEntriesMap(out.toByteArray())
        assertEquals(setOf("manifest.json", "hud/configuration.json"), entries.keys)

        val inspected = (manager.inspectBackupStream(ByteArrayInputStream(out.toByteArray()), "hud.zip")
            as BackupInspectionResult.Valid).inspection
        assertEquals(listOf(BackupCategory.HUD_CONFIGURATION), inspected.availableCategories)
        assertNull(inspected.payload.appSettings)
        assertNotNull(inspected.payload.hudConfig)
        assertEquals(true, inspected.payload.hudConfig!!.clickThroughOverlay)
        assertEquals(120, inspected.payload.hudConfig!!.hudLandscapeX)
    }

    @Test
    fun backup4_backUpMultipleSelectedCategories_onlyIncludesChosenCategories() = runBlocking {
        val prefs = createRealPrefsRepo("b4")
        val db = InMemoryTransactionalDatabaseStore()
        populateRichSourceState(prefs, db)
        val manager = BackupRestoreManager(DataStoreBackupPreferencesStore(prefs), db, "2.6.1", 10)

        val chosen = setOf(BackupCategory.APP_SETTINGS, BackupCategory.IMPORTED_LYRICS)
        val out = ByteArrayOutputStream()
        val res = manager.createBackupToStream(chosen, out)
        assertTrue(res is BackupExportResult.Success)

        val entries = readZipEntriesMap(out.toByteArray())
        assertTrue("settings/preferences.json" in entries)
        assertTrue("imported_lyrics/index.json" in entries)
        assertFalse("hud/configuration.json" in entries)
        assertFalse("lyrics/history.json" in entries)
    }

    @Test
    fun backup5_and_6_deselectAllDisablesBackup_andLocalSelectionDoesNotMutatePrefs() = runBlocking {
        val prefs = createRealPrefsRepo("b5")
        prefs.setThemePreset(ThemePreset.SAGE_SERENITY)
        val db = InMemoryTransactionalDatabaseStore()
        val manager = BackupRestoreManager(DataStoreBackupPreferencesStore(prefs), db, "2.6.1", 10)

        // Default selection has all 4 supported categories selected
        var uiState = BackupCategorySelectionState.forNewBackup()
        assertEquals(4, uiState.selectedCount)
        assertTrue(uiState.isAllSelected)
        assertTrue(uiState.canProceed)

        // Deselect All disables Create Backup
        uiState = uiState.toggleSelectAll()
        assertEquals(0, uiState.selectedCount)
        assertFalse(uiState.isAllSelected)
        assertFalse(uiState.canProceed)
        assertEquals("0 of 4 selected", uiState.selectionSummaryLabel())

        // Backend also enforces non-empty selection
        val exportRes = manager.createBackupToStream(uiState.selectedCategories, ByteArrayOutputStream())
        assertTrue(exportRes is BackupExportResult.Failure)
        assertEquals(BackupErrorCode.EMPTY_SELECTION, (exportRes as BackupExportResult.Failure).errorCode)

        // Changing checkboxes never touched actual app preferences
        assertEquals(ThemePreset.SAGE_SERENITY, prefs.userPreferencesFlow.first().themePreset)
    }

    @Test
    fun backup7_handlesInsufficientStorageGracefully() = runBlocking {
        val prefs = createRealPrefsRepo("b7")
        val db = InMemoryTransactionalDatabaseStore()
        populateRichSourceState(prefs, db)

        val manager = BackupRestoreManager(
            preferencesStore = DataStoreBackupPreferencesStore(prefs),
            databaseStore = db,
            appVersion = "2.6.1",
            appVersionCode = 10,
            freeSpaceBytesProvider = { 1024L } // Only 1 KB free
        )

        val res = manager.createBackupToStream(BackupCategory.ALL_SUPPORTED.toSet(), ByteArrayOutputStream())
        assertTrue(res is BackupExportResult.Failure)
        assertEquals(BackupErrorCode.INSUFFICIENT_STORAGE, (res as BackupExportResult.Failure).errorCode)
    }

    @Test
    fun backup8_handlesFailedFileWrite_andNeverClaimsSuccess() = runBlocking {
        val prefs = createRealPrefsRepo("b8")
        val db = InMemoryTransactionalDatabaseStore()
        populateRichSourceState(prefs, db)
        val manager = BackupRestoreManager(DataStoreBackupPreferencesStore(prefs), db, "2.6.1", 10)

        val failingStream = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("Simulated disk write interruption")
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                throw IOException("Simulated disk write interruption")
            }
        }

        val res = manager.createBackupToStream(setOf(BackupCategory.APP_SETTINGS), failingStream)
        assertTrue(res is BackupExportResult.Failure)
        assertEquals(BackupErrorCode.WRITE_FAILED, (res as BackupExportResult.Failure).errorCode)
    }

    // =========================================================================
    // PHASE 11 — RESTORE TESTS (1..13)
    // =========================================================================

    @Test
    fun restore1_restoresAllSupportedCategoriesFaithfully() = runBlocking {
        val sourcePrefs = createRealPrefsRepo("r1_src")
        val sourceDb = InMemoryTransactionalDatabaseStore()
        populateRichSourceState(sourcePrefs, sourceDb)
        val sourceMgr = BackupRestoreManager(DataStoreBackupPreferencesStore(sourcePrefs), sourceDb, "2.6.1", 10)

        val archiveBytes = ByteArrayOutputStream().also {
            assertTrue(sourceMgr.createBackupToStream(BackupCategory.ALL_SUPPORTED.toSet(), it) is BackupExportResult.Success)
        }.toByteArray()

        val targetPrefs = createRealPrefsRepo("r1_dst")
        val targetDb = InMemoryTransactionalDatabaseStore()
        val targetMgr = BackupRestoreManager(DataStoreBackupPreferencesStore(targetPrefs), targetDb, "2.6.1", 10)

        val inspection = (targetMgr.inspectBackupStream(ByteArrayInputStream(archiveBytes), "all.zip")
            as BackupInspectionResult.Valid).inspection

        val result = targetMgr.restoreBackup(inspection, BackupCategory.ALL_SUPPORTED.toSet())
        assertTrue(result is BackupRestoreResult.Success)
        assertEquals(BackupCategory.ALL_SUPPORTED.toSet(), (result as BackupRestoreResult.Success).restoredCategories)

        val restoredPrefs = targetPrefs.userPreferencesFlow.first()
        assertEquals(ThemePreset.LAVENDER_MIST, restoredPrefs.themePreset)
        assertEquals(FontOption.MONTSERRAT, restoredPrefs.selectedFont)
        assertEquals(LyricsProviderPreference.UNISON, restoredPrefs.lyricsProvider)
        assertEquals(true, restoredPrefs.singleLineLyrics)
        assertEquals(500L, targetPrefs.getTrackOffsetFlow("queen - bohemian rhapsody").first())
        // HUD
        assertEquals(18f, restoredPrefs.overlayTextSizeSp, 0.01f)
        assertEquals(true, restoredPrefs.clickThroughOverlay)
        assertEquals(HudBackdropStyle.GREY, restoredPrefs.hudBackdropStyle)
        assertEquals(42, restoredPrefs.hudPortraitX)
        assertEquals(120, restoredPrefs.hudLandscapeX)
        // Excluded transient / internal keys must remain defaults
        assertFalse(restoredPrefs.isDragRepositionMode)
        assertEquals(0L, restoredPrefs.lastUpdateCheckTimestampMs)
        assertNull(restoredPrefs.lastNotifiedUpdateVersion)
        // DB
        assertEquals(2, targetDb.getAllHistory().size)
        assertEquals(2, targetDb.getAllLyrics().size)
    }

    @Test
    fun restore2_and_5_restoreOnlyAppSettings_preservesHudHistoryImportedAndInternalState() = runBlocking {
        val sourcePrefs = createRealPrefsRepo("r2_src")
        val sourceDb = InMemoryTransactionalDatabaseStore()
        populateRichSourceState(sourcePrefs, sourceDb)
        val sourceMgr = BackupRestoreManager(DataStoreBackupPreferencesStore(sourcePrefs), sourceDb, "2.6.1", 10)
        val zipBytes = ByteArrayOutputStream().also {
            sourceMgr.createBackupToStream(BackupCategory.ALL_SUPPORTED.toSet(), it)
        }.toByteArray()

        // Pre-existing target state that must remain untouched for unselected categories
        val targetPrefs = createRealPrefsRepo("r2_dst")
        targetPrefs.setThemePreset(ThemePreset.PEACH_EMBER)
        targetPrefs.setOverlayTextSize(22f)
        targetPrefs.setHudBackdropEnabled(false)
        targetPrefs.setOverlayBounds(300, 400, 600, 150, HudOrientation.PORTRAIT)
        targetPrefs.setLastUpdateCheckTimestampMs(999_999L)
        val existingHistory = SongHistoryEntity(id = 1, key = "existing - song", title = "Existing", artist = "Band", playedAt = 12345L)
        val existingImported = LyricsEntity(
            key = "existing - song",
            title = "Existing",
            artist = "Band",
            syncedLrc = "[00:01.00]Keep me",
            plainLyrics = "Keep me",
            isSynced = true,
            durationSec = 180,
            source = "Local Import"
        )
        val targetDb = InMemoryTransactionalDatabaseStore(listOf(existingHistory), listOf(existingImported))
        val targetMgr = BackupRestoreManager(DataStoreBackupPreferencesStore(targetPrefs), targetDb, "2.6.1", 10)

        val inspection = (targetMgr.inspectBackupStream(ByteArrayInputStream(zipBytes), "full.zip")
            as BackupInspectionResult.Valid).inspection

        // Restore ONLY App Settings from full backup
        val res = targetMgr.restoreBackup(inspection, setOf(BackupCategory.APP_SETTINGS))
        assertTrue(res is BackupRestoreResult.Success)

        val after = targetPrefs.userPreferencesFlow.first()
        // App Settings updated:
        assertEquals(ThemePreset.LAVENDER_MIST, after.themePreset)
        assertEquals(FontOption.MONTSERRAT, after.selectedFont)
        assertEquals(LyricsProviderPreference.UNISON, after.lyricsProvider)
        // HUD untouched:
        assertEquals(22f, after.overlayTextSizeSp, 0.01f)
        assertFalse(after.hudBackdropEnabled)
        assertEquals(300, after.hudPortraitX)
        assertEquals(400, after.hudPortraitY)
        assertFalse(after.clickThroughOverlay)
        // Internal updater timestamp untouched:
        assertEquals(999_999L, after.lastUpdateCheckTimestampMs)
        // DB untouched:
        assertEquals(1, targetDb.getAllHistory().size)
        assertEquals("existing - song", targetDb.getAllHistory().first().key)
        assertEquals(1, targetDb.getAllLyrics().size)
        assertEquals("existing - song", targetDb.getAllLyrics().first().key)
    }

    @Test
    fun restore3_restoreOnlyHudConfiguration_preservesAppSettingsHistoryAndImported() = runBlocking {
        val sourcePrefs = createRealPrefsRepo("r3_src")
        val sourceDb = InMemoryTransactionalDatabaseStore()
        populateRichSourceState(sourcePrefs, sourceDb)
        val sourceMgr = BackupRestoreManager(DataStoreBackupPreferencesStore(sourcePrefs), sourceDb, "2.6.1", 10)
        val zipBytes = ByteArrayOutputStream().also {
            sourceMgr.createBackupToStream(BackupCategory.ALL_SUPPORTED.toSet(), it)
        }.toByteArray()

        val targetPrefs = createRealPrefsRepo("r3_dst")
        targetPrefs.setThemePreset(ThemePreset.SAGE_SERENITY)
        targetPrefs.setSelectedFont(FontOption.LORA)
        targetPrefs.setLyricsProvider(LyricsProviderPreference.LYRICA)
        val targetDb = InMemoryTransactionalDatabaseStore()
        val targetMgr = BackupRestoreManager(DataStoreBackupPreferencesStore(targetPrefs), targetDb, "2.6.1", 10)

        val inspection = (targetMgr.inspectBackupStream(ByteArrayInputStream(zipBytes), "full.zip")
            as BackupInspectionResult.Valid).inspection

        val res = targetMgr.restoreBackup(inspection, setOf(BackupCategory.HUD_CONFIGURATION))
        assertTrue(res is BackupRestoreResult.Success)

        val after = targetPrefs.userPreferencesFlow.first()
        // HUD restored:
        assertEquals(true, after.clickThroughOverlay)
        assertEquals(HudBackdropStyle.GREY, after.hudBackdropStyle)
        assertEquals(42, after.hudPortraitX)
        assertEquals(120, after.hudLandscapeX)
        // App Settings untouched:
        assertEquals(ThemePreset.SAGE_SERENITY, after.themePreset)
        assertEquals(FontOption.LORA, after.selectedFont)
        assertEquals(LyricsProviderPreference.LYRICA, after.lyricsProvider)
        // History & Imported untouched (still empty):
        assertTrue(targetDb.getAllHistory().isEmpty())
        assertTrue(targetDb.getAllLyrics().isEmpty())
    }

    @Test
    fun restore4_restoreMultipleSelectedCategories_leavesUnselectedUntouched() = runBlocking {
        val sourcePrefs = createRealPrefsRepo("r4_src")
        val sourceDb = InMemoryTransactionalDatabaseStore()
        populateRichSourceState(sourcePrefs, sourceDb)
        val sourceMgr = BackupRestoreManager(DataStoreBackupPreferencesStore(sourcePrefs), sourceDb, "2.6.1", 10)
        val zipBytes = ByteArrayOutputStream().also {
            sourceMgr.createBackupToStream(BackupCategory.ALL_SUPPORTED.toSet(), it)
        }.toByteArray()

        val targetPrefs = createRealPrefsRepo("r4_dst")
        targetPrefs.setThemePreset(ThemePreset.PEACH_EMBER)
        val targetDb = InMemoryTransactionalDatabaseStore()
        val targetMgr = BackupRestoreManager(DataStoreBackupPreferencesStore(targetPrefs), targetDb, "2.6.1", 10)

        val inspection = (targetMgr.inspectBackupStream(ByteArrayInputStream(zipBytes), "full.zip")
            as BackupInspectionResult.Valid).inspection

        val res = targetMgr.restoreBackup(
            inspection,
            setOf(BackupCategory.HUD_CONFIGURATION, BackupCategory.LYRICS_HISTORY)
        )
        assertTrue(res is BackupRestoreResult.Success)

        val after = targetPrefs.userPreferencesFlow.first()
        assertEquals(ThemePreset.PEACH_EMBER, after.themePreset) // App Settings untouched
        assertEquals(42, after.hudPortraitX) // HUD restored
        assertEquals(2, targetDb.getAllHistory().size) // History restored
        assertTrue(targetDb.getAllLyrics().isEmpty()) // Imported Lyrics untouched
    }

    @Test
    fun restore6_corruptedZipAndTamperedChecksumAreRejected() = runBlocking {
        val prefs = createRealPrefsRepo("r6")
        val db = InMemoryTransactionalDatabaseStore()
        populateRichSourceState(prefs, db)
        val mgr = BackupRestoreManager(DataStoreBackupPreferencesStore(prefs), db, "2.6.1", 10)

        // 1. Garbage bytes (not a ZIP)
        val garbageRes = mgr.inspectBackupStream(ByteArrayInputStream("not a zip".toByteArray()), "bad.zip")
        assertTrue(garbageRes is BackupInspectionResult.Invalid)
        assertEquals(BackupErrorCode.CORRUPTED_ARCHIVE, (garbageRes as BackupInspectionResult.Invalid).errorCode)

        // 2. Valid backup with tampered JSON byte -> checksum mismatch
        val validZip = ByteArrayOutputStream().also {
            mgr.createBackupToStream(setOf(BackupCategory.APP_SETTINGS), it)
        }.toByteArray()
        val entries = readZipEntriesMap(validZip).toMutableMap()
        entries[BackupZipCodec.SETTINGS_PATH] = (String(entries[BackupZipCodec.SETTINGS_PATH]!!, StandardCharsets.UTF_8) + " ")
            .toByteArray(StandardCharsets.UTF_8)
        val tamperedZip = buildRawZip(entries)

        val tamperedRes = mgr.inspectBackupStream(ByteArrayInputStream(tamperedZip), "tampered.zip")
        assertTrue(tamperedRes is BackupInspectionResult.Invalid)
        assertEquals(BackupErrorCode.CHECKSUM_MISMATCH, (tamperedRes as BackupInspectionResult.Invalid).errorCode)
    }

    @Test
    fun restore7_invalidBackup_pathTraversal_andZipBombRejected() = runBlocking {
        val prefs = createRealPrefsRepo("r7")
        val db = InMemoryTransactionalDatabaseStore()
        val mgr = BackupRestoreManager(DataStoreBackupPreferencesStore(prefs), db, "2.6.1", 10)

        // 1. Valid ZIP without manifest.json
        val noManifestZip = buildRawZip(mapOf("settings/preferences.json" to "{}".toByteArray()))
        val noManifestRes = mgr.inspectBackupStream(ByteArrayInputStream(noManifestZip), "random.zip")
        assertTrue(noManifestRes is BackupInspectionResult.Invalid)
        assertEquals(BackupErrorCode.INVALID_BACKUP, (noManifestRes as BackupInspectionResult.Invalid).errorCode)

        // 2. Path traversal attempt
        val traversalZip = buildRawZip(mapOf("../evil.txt" to "pwn".toByteArray()))
        val traversalRes = mgr.inspectBackupStream(ByteArrayInputStream(traversalZip), "evil.zip")
        assertTrue(traversalRes is BackupInspectionResult.Invalid)
        assertEquals(BackupErrorCode.SECURITY_VIOLATION, (traversalRes as BackupInspectionResult.Invalid).errorCode)

        // 3. Unexpected executable/unknown file in archive
        val unexpectedZip = buildRawZip(mapOf("classes.dex" to "dex".toByteArray()))
        val unexpectedRes = mgr.inspectBackupStream(ByteArrayInputStream(unexpectedZip), "dex.zip")
        assertTrue(unexpectedRes is BackupInspectionResult.Invalid)
        assertEquals(BackupErrorCode.INVALID_BACKUP, (unexpectedRes as BackupInspectionResult.Invalid).errorCode)

        // 4. ZIP bomb exceeding max uncompressed bytes
        val hugePayload = ByteArray(100_000) { 'A'.code.toByte() }
        val bombZip = buildRawZip(mapOf("settings/preferences.json" to hugePayload))
        try {
            BackupZipCodec.readAndValidateBackupZip(
                inputStream = ByteArrayInputStream(bombZip),
                displayFileName = "bomb.zip",
                maxEntryBytes = 4_096L,
                maxTotalBytes = 8_192L
            )
            org.junit.Assert.fail("Expected ARCHIVE_TOO_LARGE exception")
        } catch (e: BackupException) {
            assertEquals(BackupErrorCode.ARCHIVE_TOO_LARGE, e.errorCode)
        }
    }

    @Test
    fun restore8_unsupportedFutureFormatVersionIsRejectedCleanly() = runBlocking {
        val prefs = createRealPrefsRepo("r8")
        val db = InMemoryTransactionalDatabaseStore()
        val mgr = BackupRestoreManager(DataStoreBackupPreferencesStore(prefs), db, "2.6.1", 10)

        val futureManifest = """
            {
              "formatVersion": 99,
              "minSupportedFormatVersion": 99,
              "appId": "lyricbar",
              "appVersion": "5.0.0",
              "createdAtEpochMs": 1800000000000,
              "includedCategories": ["APP_SETTINGS"],
              "checksums": {}
            }
        """.trimIndent()
        val futureZip = buildRawZip(mapOf("manifest.json" to futureManifest.toByteArray()))

        val res = mgr.inspectBackupStream(ByteArrayInputStream(futureZip), "future.zip")
        assertTrue(res is BackupInspectionResult.Invalid)
        assertEquals(BackupErrorCode.UNSUPPORTED_VERSION, (res as BackupInspectionResult.Invalid).errorCode)
    }

    @Test
    fun restore9_backupMissingOptionalCategoriesAndWithUnknownFutureCategoryHandledSafely() = runBlocking {
        val prefs = createRealPrefsRepo("r9")
        val db = InMemoryTransactionalDatabaseStore()
        val mgr = BackupRestoreManager(DataStoreBackupPreferencesStore(prefs), db, "2.6.1", 10)

        val hudJson = """{"schemaVersion":1,"overlayTextSizeSp":19.0,"clickThroughOverlay":true}""".toByteArray()
        val hudSha = BackupZipCodec.sha256Hex(hudJson)
        val manifestJson = """
            {
              "formatVersion": 1,
              "minSupportedFormatVersion": 1,
              "appId": "lyricbar",
              "appVersion": "2.8.0",
              "createdAtEpochMs": 1763628000000,
              "includedCategories": ["HUD_CONFIGURATION", "FUTURE_CLOUD_PLAYLISTS"],
              "checksums": {
                "hud/configuration.json": "$hudSha"
              }
            }
        """.trimIndent().toByteArray()

        val zip = buildRawZip(
            mapOf(
                "manifest.json" to manifestJson,
                "hud/configuration.json" to hudJson
            )
        )

        val inspected = (mgr.inspectBackupStream(ByteArrayInputStream(zip), "partial_future.zip")
            as BackupInspectionResult.Valid).inspection
        assertEquals(listOf(BackupCategory.HUD_CONFIGURATION), inspected.availableCategories)
        assertEquals(listOf("FUTURE_CLOUD_PLAYLISTS"), inspected.ignoredUnknownCategories)

        // Selection state only exposes available supported categories
        val sel = BackupCategorySelectionState.forRestore(inspected.availableCategories)
        assertEquals(setOf(BackupCategory.HUD_CONFIGURATION), sel.selectedCategories)
        // Attempting to toggle an unavailable category is a no-op
        assertEquals(sel, sel.toggle(BackupCategory.APP_SETTINGS))
    }

    @Test
    fun restore10_duplicateLyricsHistoryRecords_mergeSkipsDuplicates_replaceReplacesWhenConfirmed() = runBlocking {
        val srcPrefs = createRealPrefsRepo("r10_src")
        val srcDb = InMemoryTransactionalDatabaseStore()
        populateRichSourceState(srcPrefs, srcDb)
        val srcMgr = BackupRestoreManager(DataStoreBackupPreferencesStore(srcPrefs), srcDb, "2.6.1", 10)
        val zipBytes = ByteArrayOutputStream().also {
            srcMgr.createBackupToStream(setOf(BackupCategory.LYRICS_HISTORY), it)
        }.toByteArray()

        // Target already has 1 duplicate ("Bohemian Rhapsody" at same playedAt) + 1 unique local entry
        val targetDb = InMemoryTransactionalDatabaseStore(
            initialHistory = listOf(
                SongHistoryEntity(
                    id = 10,
                    key = "queen - bohemian rhapsody",
                    title = "Bohemian Rhapsody",
                    artist = "Queen",
                    playedAt = 1_763_600_000_000L
                ),
                SongHistoryEntity(
                    id = 11,
                    key = "coldplay - yellow",
                    title = "Yellow",
                    artist = "Coldplay",
                    playedAt = 1_763_500_000_000L
                )
            )
        )
        val targetMgr = BackupRestoreManager(
            DataStoreBackupPreferencesStore(createRealPrefsRepo("r10_dst")),
            targetDb,
            "2.6.1",
            10
        )
        val inspection = (targetMgr.inspectBackupStream(ByteArrayInputStream(zipBytes), "hist.zip")
            as BackupInspectionResult.Valid).inspection

        val conflicts = targetMgr.computeConflictSummary(inspection, setOf(BackupCategory.LYRICS_HISTORY))
        assertEquals(2, conflicts.existingHistoryCount)
        assertEquals(2, conflicts.incomingHistoryCount)
        assertEquals(1, conflicts.duplicateHistoryCount)
        assertEquals(1, conflicts.newHistoryCount)

        // 1. Default safe MERGE preserves existing Yellow + avoids duplicate Queen + adds Kesariya = 3 total
        val mergeRes = targetMgr.restoreBackup(
            inspection,
            setOf(BackupCategory.LYRICS_HISTORY),
            historyStrategy = HistoryConflictStrategy.MERGE_PRESERVE_EXISTING
        )
        assertTrue(mergeRes is BackupRestoreResult.Success)
        assertEquals(3, targetDb.getAllHistory().size)
        assertEquals(1, targetDb.getAllHistory().count { it.key == "queen - bohemian rhapsody" })
        assertTrue(targetDb.getAllHistory().any { it.key == "coldplay - yellow" })

        // 2. Explicit REPLACE replaces with exactly the 2 backup entries
        val replaceRes = targetMgr.restoreBackup(
            inspection,
            setOf(BackupCategory.LYRICS_HISTORY),
            historyStrategy = HistoryConflictStrategy.REPLACE_EXISTING
        )
        assertTrue(replaceRes is BackupRestoreResult.Success)
        assertEquals(2, targetDb.getAllHistory().size)
        assertFalse(targetDb.getAllHistory().any { it.key == "coldplay - yellow" })
    }

    @Test
    fun restore11_importedLyricsFilenameCollisionsAndExistingTrackConflictsHandledSafely() = runBlocking {
        // Two different track keys whose sanitized filenames collide ("Artist_-_Same_Title.lrc" vs "_2.lrc")
        val srcDb = InMemoryTransactionalDatabaseStore(
            initialLyrics = listOf(
                LyricsEntity(
                    key = "artist - same title|album1",
                    title = "Same Title",
                    artist = "Artist",
                    syncedLrc = "[00:01.00]Version One",
                    plainLyrics = "Version One",
                    isSynced = true,
                    durationSec = 200,
                    source = "Local Import"
                ),
                LyricsEntity(
                    key = "artist - same title|album2",
                    title = "Same Title",
                    artist = "Artist",
                    syncedLrc = "[00:01.00]Version Two",
                    plainLyrics = "Version Two",
                    isSynced = true,
                    durationSec = 240,
                    source = "Local Import"
                )
            )
        )
        val srcMgr = BackupRestoreManager(
            DataStoreBackupPreferencesStore(createRealPrefsRepo("r11_src")),
            srcDb,
            "2.6.1",
            10
        )
        val zipBytes = ByteArrayOutputStream().also {
            assertTrue(srcMgr.createBackupToStream(setOf(BackupCategory.IMPORTED_LYRICS), it) is BackupExportResult.Success)
        }.toByteArray()

        val zipEntries = readZipEntriesMap(zipBytes)
        val lrcPaths = zipEntries.keys.filter { it.endsWith(".lrc") }.sorted()
        assertEquals(2, lrcPaths.size)
        assertTrue("Colliding filename should be disambiguated with _2.lrc", lrcPaths.any { it.endsWith("_2.lrc") })

        // Target already has a customized local import for album1
        val targetDb = InMemoryTransactionalDatabaseStore(
            initialLyrics = listOf(
                LyricsEntity(
                    key = "artist - same title|album1",
                    title = "Same Title",
                    artist = "Artist",
                    syncedLrc = "[00:01.00]User Existing Custom Lyrics",
                    plainLyrics = "User Existing Custom Lyrics",
                    isSynced = true,
                    durationSec = 200,
                    source = "Local Import"
                )
            )
        )
        val targetMgr = BackupRestoreManager(
            DataStoreBackupPreferencesStore(createRealPrefsRepo("r11_dst")),
            targetDb,
            "2.6.1",
            10
        )
        val inspection = (targetMgr.inspectBackupStream(ByteArrayInputStream(zipBytes), "lrc.zip")
            as BackupInspectionResult.Valid).inspection

        val conflictSummary = targetMgr.computeConflictSummary(inspection, setOf(BackupCategory.IMPORTED_LYRICS))
        assertEquals(1, conflictSummary.conflictingImportedCount)
        assertEquals(1, conflictSummary.newImportedCount)

        // 1. Default SKIP_EXISTING preserves existing album1 and restores album2
        val skipRes = targetMgr.restoreBackup(
            inspection,
            setOf(BackupCategory.IMPORTED_LYRICS),
            importedLyricsStrategy = ImportedLyricsConflictStrategy.SKIP_EXISTING
        )
        assertTrue(skipRes is BackupRestoreResult.Success)
        assertEquals("[00:01.00]User Existing Custom Lyrics", targetDb.lyrics["artist - same title|album1"]?.syncedLrc)
        assertEquals("[00:01.00]Version Two", targetDb.lyrics["artist - same title|album2"]?.syncedLrc)

        // 2. Explicit REPLACE_EXISTING overwrites album1 with backup version
        val replaceRes = targetMgr.restoreBackup(
            inspection,
            setOf(BackupCategory.IMPORTED_LYRICS),
            importedLyricsStrategy = ImportedLyricsConflictStrategy.REPLACE_EXISTING
        )
        assertTrue(replaceRes is BackupRestoreResult.Success)
        assertEquals("[00:01.00]Version One", targetDb.lyrics["artist - same title|album1"]?.syncedLrc)
    }

    @Test
    fun restore13_failedRestorationRollsBackAndNeverSilentlyDestroysExistingData() = runBlocking {
        val srcPrefs = createRealPrefsRepo("r13_src")
        val srcDb = InMemoryTransactionalDatabaseStore()
        populateRichSourceState(srcPrefs, srcDb)
        val srcMgr = BackupRestoreManager(DataStoreBackupPreferencesStore(srcPrefs), srcDb, "2.6.1", 10)
        val zipBytes = ByteArrayOutputStream().also {
            srcMgr.createBackupToStream(BackupCategory.ALL_SUPPORTED.toSet(), it)
        }.toByteArray()

        // Case A: DB transaction fails -> existing DB and DataStore remain completely unchanged
        val targetPrefsA = createRealPrefsRepo("r13_dst_a")
        targetPrefsA.setThemePreset(ThemePreset.SAGE_SERENITY)
        val targetDbA = InMemoryTransactionalDatabaseStore(
            initialHistory = listOf(SongHistoryEntity(1, "keep - me", "Keep", "Me", playedAt = 1L))
        ).apply { failNextTransaction = true }
        val mgrA = BackupRestoreManager(DataStoreBackupPreferencesStore(targetPrefsA), targetDbA, "2.6.1", 10)
        val inspA = (mgrA.inspectBackupStream(ByteArrayInputStream(zipBytes), "full.zip") as BackupInspectionResult.Valid).inspection

        val resA = mgrA.restoreBackup(
            inspA,
            BackupCategory.ALL_SUPPORTED.toSet(),
            historyStrategy = HistoryConflictStrategy.REPLACE_EXISTING
        )
        assertTrue(resA is BackupRestoreResult.Failure)
        assertEquals(ThemePreset.SAGE_SERENITY, targetPrefsA.userPreferencesFlow.first().themePreset)
        assertEquals(1, targetDbA.getAllHistory().size)
        assertEquals("keep - me", targetDbA.getAllHistory().first().key)

        // Case B: Preferences write fails AFTER DB committed -> DB rolls back cleanly
        val targetDbB = InMemoryTransactionalDatabaseStore(
            initialHistory = listOf(SongHistoryEntity(1, "keep - me", "Keep", "Me", playedAt = 1L))
        )
        val failingPrefsStore = object : BackupPreferencesStore {
            override suspend fun readAppSettings() = BackupAppSettingsData()
            override suspend fun readHudConfig() = BackupHudConfigData()
            override suspend fun applySelective(appSettings: BackupAppSettingsData?, hudConfig: BackupHudConfigData?) {
                throw IOException("Simulated DataStore failure")
            }
        }
        val mgrB = BackupRestoreManager(failingPrefsStore, targetDbB, "2.6.1", 10)
        val inspB = (mgrB.inspectBackupStream(ByteArrayInputStream(zipBytes), "full.zip") as BackupInspectionResult.Valid).inspection

        val resB = mgrB.restoreBackup(
            inspB,
            setOf(BackupCategory.APP_SETTINGS, BackupCategory.LYRICS_HISTORY),
            historyStrategy = HistoryConflictStrategy.REPLACE_EXISTING
        )
        assertTrue(resB is BackupRestoreResult.Failure)
        assertEquals("DB must roll back when DataStore write fails", 1, targetDbB.getAllHistory().size)
        assertEquals("keep - me", targetDbB.getAllHistory().first().key)
    }

    // =========================================================================
    // PHASE 11 — MIGRATION TEST (v2.6.1 old package -> v2.7.0 new package)
    // =========================================================================

    @Test
    fun migration_v261BackupRestoresCleanlyIntoSeparateV270AppPackage() = runBlocking {
        // 1. Old v2.6.1 installation (old applicationId com.aistudio.lyricbar.zmvpk)
        val oldV261Prefs = createRealPrefsRepo("old_v261_pkg")
        val oldV261Db = InMemoryTransactionalDatabaseStore()
        populateRichSourceState(oldV261Prefs, oldV261Db)
        val oldV261Manager = BackupRestoreManager(
            preferencesStore = DataStoreBackupPreferencesStore(oldV261Prefs),
            databaseStore = oldV261Db,
            appVersion = "2.6.1",
            appVersionCode = 10,
            nowProvider = { 1_763_628_000_000L }
        )

        val portableBackupZip = ByteArrayOutputStream().also { out ->
            val exported = oldV261Manager.createBackupToStream(
                selectedCategories = BackupCategory.ALL_SUPPORTED.toSet(),
                outputStream = out,
                displayFileName = BackupZipCodec.defaultBackupFileName(1_763_628_000_000L)
            )
            assertTrue(exported is BackupExportResult.Success)
        }.toByteArray()

        // 2. Fresh v2.7.0 installation (separate permanent applicationId -> completely empty storage)
        val newV270Prefs = createRealPrefsRepo("new_v270_permanent_pkg")
        val newV270Db = InMemoryTransactionalDatabaseStore()
        val newV270Manager = BackupRestoreManager(
            preferencesStore = DataStoreBackupPreferencesStore(newV270Prefs),
            databaseStore = newV270Db,
            appVersion = "2.7.0",
            appVersionCode = 11,
            nowProvider = { 1_764_000_000_000L }
        )

        // Inspect v2.6.1 archive from v2.7.0
        val inspectionRes = newV270Manager.inspectBackupStream(
            ByteArrayInputStream(portableBackupZip),
            "LyricBar_Backup_2026-11-20.zip"
        )
        assertTrue(inspectionRes is BackupInspectionResult.Valid)
        val inspection = (inspectionRes as BackupInspectionResult.Valid).inspection
        assertEquals("2.6.1", inspection.manifest.appVersion)
        assertEquals(BackupManifest.CURRENT_FORMAT_VERSION, inspection.manifest.formatVersion)

        // Restore all categories into new v2.7.0 package
        val restoreRes = newV270Manager.restoreBackup(inspection, BackupCategory.ALL_SUPPORTED.toSet())
        assertTrue(restoreRes is BackupRestoreResult.Success)

        // Verify all user settings & HUD customisations survived package migration
        val migratedPrefs = newV270Prefs.userPreferencesFlow.first()
        assertEquals(ThemePreset.LAVENDER_MIST, migratedPrefs.themePreset)
        assertEquals(FontOption.MONTSERRAT, migratedPrefs.selectedFont)
        assertEquals(LyricsProviderPreference.UNISON, migratedPrefs.lyricsProvider)
        assertEquals(OverlayAnimationStyle.SMOOTH_SLIDE.name, migratedPrefs.animationStyle)
        assertEquals(500L, newV270Prefs.getTrackOffsetFlow("queen - bohemian rhapsody").first())
        assertEquals(true, migratedPrefs.clickThroughOverlay)
        assertEquals(HudBackdropStyle.GREY, migratedPrefs.hudBackdropStyle)
        assertEquals(0xFFFFD166.toInt(), migratedPrefs.hudActiveLyricColor)
        assertEquals(listOf(0xFF00E5FF.toInt()), migratedPrefs.hudCustomColors)
        assertEquals(42, migratedPrefs.hudPortraitX)
        assertEquals(120, migratedPrefs.hudLandscapeX)

        // Verify History migrated with portable URI preserved and old package-private path stripped
        val migratedHistory = newV270Db.getAllHistory()
        assertEquals(2, migratedHistory.size)
        val queenHist = migratedHistory.first { it.key == "queen - bohemian rhapsody" }
        assertEquals("https://cdn.example.com/queen.jpg", queenHist.albumArtUri)
        val kesariyaHist = migratedHistory.first { it.key == "arijit singh - kesariya" }
        assertNull("Package-private old file path must be stripped on export/restore", kesariyaHist.albumArtUri)

        // Verify Imported LRC files migrated cleanly without disposable online provider caches
        val migratedLyrics = newV270Db.getAllLyrics()
        assertEquals(2, migratedLyrics.size)
        assertTrue(migratedLyrics.all { it.source == "Local Import" })
        assertTrue(migratedLyrics.any { it.key == "queen - bohemian rhapsody" && it.syncedLrc!!.contains("Is this the real life?") })
        assertTrue(migratedLyrics.any { it.key == "arijit singh - kesariya" && it.syncedLrc!!.contains("Mujhko itna bataye koi") })
    }

    @Test
    fun backup_viaStagingFile_writesValidatedZipWithoutIoErrorAndCleansUpStagingFile() = runBlocking {
        val prefs = createRealPrefsRepo("staging_ok")
        val db = InMemoryTransactionalDatabaseStore()
        populateRichSourceState(prefs, db)
        val mgr = BackupRestoreManager(DataStoreBackupPreferencesStore(prefs), db, "2.6.1", 10)

        val stagingFile = tempFolder.newFile("staging_backup.zip").apply { delete() }
        val destinationFile = tempFolder.newFile("LyricBar_Backup_2026-11-20.zip")

        val exportResult = mgr.createBackupViaStagingFile(
            stagingFile = stagingFile,
            displayFileName = destinationFile.name,
            selectedCategories = BackupCategory.ALL_SUPPORTED.toSet(),
            openDestinationStream = { destinationFile.outputStream() }
        )

        assertTrue("Staging export must succeed without IOException: $exportResult", exportResult is BackupExportResult.Success)
        assertFalse("Staging temp file must be cleaned up after export", stagingFile.exists())
        assertTrue("Destination ZIP file must be non-empty", destinationFile.length() > 0L)

        val inspectionResult = destinationFile.inputStream().use { input ->
            mgr.inspectBackupStream(input, destinationFile.name)
        }
        assertTrue(inspectionResult is BackupInspectionResult.Valid)
        val valid = (inspectionResult as BackupInspectionResult.Valid).inspection
        assertEquals(BackupCategory.ALL_SUPPORTED, valid.availableCategories)
        assertEquals(2, valid.categoryItemCounts[BackupCategory.LYRICS_HISTORY])
        assertEquals(2, valid.categoryItemCounts[BackupCategory.IMPORTED_LYRICS])
    }

    @Test
    fun backup_viaStagingFile_destinationWriteFailureTriggersPartialCleanupAndDeletesStaging() = runBlocking {
        val prefs = createRealPrefsRepo("staging_fail")
        val db = InMemoryTransactionalDatabaseStore()
        populateRichSourceState(prefs, db)
        val mgr = BackupRestoreManager(DataStoreBackupPreferencesStore(prefs), db, "2.6.1", 10)

        val stagingFile = tempFolder.newFile("staging_fail.zip").apply { delete() }
        var partialCleanupCalled = false

        val failingDest = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("Simulated SAF provider write error")
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                throw IOException("Simulated SAF provider write error")
            }
        }

        val exportResult = mgr.createBackupViaStagingFile(
            stagingFile = stagingFile,
            displayFileName = "LyricBar_Backup_2026-11-20.zip",
            selectedCategories = setOf(BackupCategory.APP_SETTINGS),
            openDestinationStream = { failingDest },
            onPartialWriteCleanup = { partialCleanupCalled = true }
        )

        assertTrue(exportResult is BackupExportResult.Failure)
        assertEquals(BackupErrorCode.WRITE_FAILED, (exportResult as BackupExportResult.Failure).errorCode)
        assertTrue("Partial document cleanup must be attempted on destination write error", partialCleanupCalled)
        assertFalse("Staging temp file must still be deleted on failure", stagingFile.exists())
    }

    private fun buildRawZip(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in files) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
