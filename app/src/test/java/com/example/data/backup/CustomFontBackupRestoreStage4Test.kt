package com.example.data.backup

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.data.fonts.CustomFontRepository
import com.example.data.fonts.SingleFontImportResult
import com.example.data.local.entity.LyricsEntity
import com.example.data.local.entity.SongHistoryEntity
import com.example.data.preferences.FontOption
import com.example.data.preferences.UserPreferencesRepository
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
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class CustomFontBackupRestoreStage4Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createPrefsRepo(name: String): UserPreferencesRepository {
        val dir = tempFolder.newFolder("ds_$name")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { File(dir, "$name.preferences_pb") }
        )
        return UserPreferencesRepository(dataStore)
    }

    private fun createEmptyDbStore(): BackupDatabaseStore = object : BackupDatabaseStore {
        override suspend fun getAllHistory(): List<SongHistoryEntity> = emptyList()
        override suspend fun getAllLyrics(): List<LyricsEntity> = emptyList()
        override suspend fun <T> runInTransaction(block: suspend BackupDatabaseTx.() -> T): T {
            val tx = object : BackupDatabaseTx {
                override suspend fun getAllHistory(): List<SongHistoryEntity> = emptyList()
                override suspend fun replaceAllHistory(entries: List<SongHistoryEntity>) = Unit
                override suspend fun insertHistory(entries: List<SongHistoryEntity>) = Unit
                override suspend fun getAllLyrics(): List<LyricsEntity> = emptyList()
                override suspend fun upsertLyrics(entries: List<LyricsEntity>) = Unit
                override suspend fun deleteLyricsByKeys(keys: List<String>) = Unit
            }
            return tx.block()
        }
    }

    private fun createFontRepo(name: String, prefs: UserPreferencesRepository): Pair<CustomFontRepository, File> {
        val fontsDir = tempFolder.newFolder("fonts_$name")
        val tmpDir = tempFolder.newFolder("tmp_$name")
        return CustomFontRepository(
            fontsDir = fontsDir,
            tempDir = tmpDir,
            preferencesRepository = prefs,
            typefaceVerifier = { true }
        ) to fontsDir
    }

    private fun createBackupManager(
        prefs: UserPreferencesRepository,
        fontRepo: CustomFontRepository
    ): BackupRestoreManager {
        return BackupRestoreManager(
            preferencesStore = DataStoreBackupPreferencesStore(prefs),
            databaseStore = createEmptyDbStore(),
            appVersion = "2.7.2",
            appVersionCode = 13,
            nowProvider = { 1_780_000_000_000L },
            freeSpaceBytesProvider = { 100L * 1024L * 1024L },
            customFontStore = RepositoryBackupCustomFontStore(fontRepo)
        )
    }

    private fun sampleTtfBytes(tagByte: Byte = 0x11): ByteArray =
        byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x0C, tagByte, 0x22, 0x33)

    private fun sampleOtfBytes(tagByte: Byte = 0x44): ByteArray =
        byteArrayOf(0x4F, 0x54, 0x54, 0x4F, 0x00, 0x0C, tagByte, 0x55, 0x66)

    @Test
    fun `round-trip backup and restore preserves custom font files and selected custom font`() = runBlocking {
        val srcPrefs = createPrefsRepo("src")
        val (srcFontRepo, _) = createFontRepo("src", srcPrefs)
        val srcManager = createBackupManager(srcPrefs, srcFontRepo)

        val ttf = (srcFontRepo.importFontFromStream(ByteArrayInputStream(sampleTtfBytes(0x01)), "Noto_Sans_Devanagari.ttf") as SingleFontImportResult.Success).record
        val otf = (srcFontRepo.importFontFromStream(ByteArrayInputStream(sampleOtfBytes(0x02)), "Editorial-Display.otf") as SingleFontImportResult.Success).record
        srcPrefs.setSelectedFont(FontOption.ROBOTO_MONO)
        srcPrefs.setSelectedCustomFontId(otf.id)

        assertEquals(2, srcManager.getLocalDataCounts().customFontsCount)

        val out = ByteArrayOutputStream()
        val exportResult = srcManager.createBackupToStream(setOf(BackupCategory.APP_SETTINGS), out)
        assertTrue(exportResult is BackupExportResult.Success)
        val archiveBytes = out.toByteArray()

        // Verify ZIP structure contains custom_fonts/index.json and font binaries
        val entryNames = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                entryNames.add(entry.name)
                zis.closeEntry()
            }
        }
        assertTrue(BackupZipCodec.CUSTOM_FONTS_INDEX_PATH in entryNames)
        assertTrue(entryNames.any { it.startsWith(BackupZipCodec.CUSTOM_FONTS_DIR_PREFIX) && it.endsWith(".ttf") })
        assertTrue(entryNames.any { it.startsWith(BackupZipCodec.CUSTOM_FONTS_DIR_PREFIX) && it.endsWith(".otf") })

        val dstPrefs = createPrefsRepo("dst")
        val (dstFontRepo, dstFontsDir) = createFontRepo("dst", dstPrefs)
        val dstManager = createBackupManager(dstPrefs, dstFontRepo)

        val inspectionResult = dstManager.inspectBackupStream(ByteArrayInputStream(archiveBytes), "backup.zip")
        assertTrue(inspectionResult is BackupInspectionResult.Valid)
        val inspection = (inspectionResult as BackupInspectionResult.Valid).inspection
        assertEquals(2, inspection.payload.customFonts?.size)
        assertEquals(otf.id, inspection.payload.appSettings?.selectedCustomFontId)

        val restoreResult = dstManager.restoreBackup(
            inspection = inspection,
            selectedCategories = setOf(BackupCategory.APP_SETTINGS),
            historyStrategy = HistoryConflictStrategy.MERGE_PRESERVE_EXISTING,
            importedLyricsStrategy = ImportedLyricsConflictStrategy.SKIP_EXISTING
        )
        assertTrue(restoreResult is BackupRestoreResult.Success)

        val restoredFonts = dstFontRepo.customFontsFlow.value
        assertEquals(2, restoredFonts.size)
        val loadedDstPrefs = dstPrefs.userPreferencesFlow.first()
        assertEquals(otf.id, loadedDstPrefs.selectedCustomFontId)
        assertEquals(FontOption.ROBOTO_MONO, loadedDstPrefs.selectedFont)
        val restoredActiveFile = dstFontRepo.resolveValidFontFile(otf.id)
        assertNotNull(restoredActiveFile)
        assertEquals(dstFontsDir.absolutePath, restoredActiveFile!!.parentFile!!.absolutePath)
    }

    @Test
    fun `legacy backup without custom fonts clears selectedCustomFontId cleanly and keeps existing library`() = runBlocking {
        val dstPrefs = createPrefsRepo("legacy_dst")
        val (dstFontRepo, _) = createFontRepo("legacy_dst", dstPrefs)
        val dstManager = createBackupManager(dstPrefs, dstFontRepo)

        val localFont = (dstFontRepo.importFontFromStream(ByteArrayInputStream(sampleTtfBytes(0x77)), "ExistingLocal.ttf") as SingleFontImportResult.Success).record
        dstPrefs.setSelectedCustomFontId(localFont.id)

        // Simulate legacy v2.7.0 backup (no selectedCustomFontId, no custom_fonts/ dir)
        val legacySettings = BackupAppSettingsData(
            schemaVersion = 1,
            themeMode = "DARK",
            themePreset = "ROSE_QUARTZ",
            useWallpaperDynamicColor = false,
            selectedFont = FontOption.LORA.name,
            selectedCustomFontId = null,
            lyricsProvider = "LRCLIB",
            animationStyle = "KARAOKE",
            karaokeEnhancedGlow = true,
            trackOffsets = emptyMap()
        )
        val legacyZipOut = ByteArrayOutputStream()
        BackupZipCodec.writeBackupZip(
            outputStream = legacyZipOut,
            selectedCategories = setOf(BackupCategory.APP_SETTINGS),
            appVersion = "2.7.0",
            appVersionCode = 11,
            createdAtEpochMs = 1_770_000_000_000L,
            appSettings = legacySettings,
            customFonts = null
        )

        val inspection = (dstManager.inspectBackupStream(
            ByteArrayInputStream(legacyZipOut.toByteArray()),
            "v2.7.0_backup.zip"
        ) as BackupInspectionResult.Valid).inspection

        val result = dstManager.restoreBackup(
            inspection = inspection,
            selectedCategories = setOf(BackupCategory.APP_SETTINGS),
            historyStrategy = HistoryConflictStrategy.MERGE_PRESERVE_EXISTING,
            importedLyricsStrategy = ImportedLyricsConflictStrategy.SKIP_EXISTING
        )
        assertTrue(result is BackupRestoreResult.Success)
        val loadedDstPrefs = dstPrefs.userPreferencesFlow.first()
        assertNull(loadedDstPrefs.selectedCustomFontId)
        assertEquals(FontOption.LORA, loadedDstPrefs.selectedFont)
        assertEquals(1, dstFontRepo.customFontsFlow.value.size)
    }

    @Test
    fun `deduplicates identical restored fonts against local library and remaps selected id`() = runBlocking {
        val sharedBytes = sampleTtfBytes(0x5A)

        // Target already has the same font imported under a local generated id
        val dstPrefs = createPrefsRepo("dedup_dst")
        val (dstFontRepo, dstFontsDir) = createFontRepo("dedup_dst", dstPrefs)
        val dstManager = createBackupManager(dstPrefs, dstFontRepo)
        val existingLocal = (dstFontRepo.importFontFromStream(ByteArrayInputStream(sharedBytes), "LocalCopy.ttf") as SingleFontImportResult.Success).record

        // Source backup references the same content with a different backup id
        val sourceBackupOut = ByteArrayOutputStream()
        BackupZipCodec.writeBackupZip(
            outputStream = sourceBackupOut,
            selectedCategories = setOf(BackupCategory.APP_SETTINGS),
            appVersion = "2.7.2",
            appVersionCode = 13,
            createdAtEpochMs = 1_780_000_000_000L,
            appSettings = BackupAppSettingsData(
                selectedFont = FontOption.POPPINS.name,
                selectedCustomFontId = "cf_other_device_id"
            ),
            customFonts = listOf(
                BackupCustomFontRecord(
                    meta = BackupCustomFontMeta(
                        id = "cf_other_device_id",
                        displayName = "Remote Copy",
                        originalFileName = "RemoteCopy.ttf",
                        extension = "ttf",
                        storedFileName = "cf_other_device_id.ttf"
                    ),
                    fontBytes = sharedBytes
                )
            )
        )

        val inspection = (dstManager.inspectBackupStream(
            ByteArrayInputStream(sourceBackupOut.toByteArray()),
            "dedup_backup.zip"
        ) as BackupInspectionResult.Valid).inspection

        val restoreResult = dstManager.restoreBackup(
            inspection = inspection,
            selectedCategories = setOf(BackupCategory.APP_SETTINGS),
            historyStrategy = HistoryConflictStrategy.MERGE_PRESERVE_EXISTING,
            importedLyricsStrategy = ImportedLyricsConflictStrategy.SKIP_EXISTING
        )
        assertTrue(restoreResult is BackupRestoreResult.Success)
        assertEquals(1, dstFontRepo.customFontsFlow.value.size)
        assertEquals(existingLocal.id, dstPrefs.userPreferencesFlow.first().selectedCustomFontId)
        assertEquals(1, dstFontsDir.listFiles { _, name -> name.endsWith(".ttf") }?.size)
    }

    @Test
    fun `invalid custom font payload in backup is skipped and falls back to built-in font cleanly`() = runBlocking {
        val dstPrefs = createPrefsRepo("corrupt_dst")
        val (dstFontRepo, _) = createFontRepo("corrupt_dst", dstPrefs)
        val dstManager = createBackupManager(dstPrefs, dstFontRepo)

        val badBytes = "not-a-valid-sfnt-font".toByteArray(StandardCharsets.UTF_8)
        val badFontZipOut = ByteArrayOutputStream()
        BackupZipCodec.writeBackupZip(
            outputStream = badFontZipOut,
            selectedCategories = setOf(BackupCategory.APP_SETTINGS),
            appVersion = "2.7.2",
            appVersionCode = 13,
            createdAtEpochMs = 1_780_000_000_000L,
            appSettings = BackupAppSettingsData(
                selectedFont = FontOption.OUTFIT.name,
                selectedCustomFontId = "cf_corrupt_id"
            ),
            customFonts = listOf(
                BackupCustomFontRecord(
                    meta = BackupCustomFontMeta(
                        id = "cf_corrupt_id",
                        displayName = "Corrupt Font",
                        originalFileName = "Bad.ttf",
                        extension = "ttf",
                        storedFileName = "cf_corrupt_id.ttf"
                    ),
                    fontBytes = badBytes
                )
            )
        )

        val inspection = (dstManager.inspectBackupStream(
            ByteArrayInputStream(badFontZipOut.toByteArray()),
            "bad_font_backup.zip"
        ) as BackupInspectionResult.Valid).inspection

        val restoreResult = dstManager.restoreBackup(
            inspection = inspection,
            selectedCategories = setOf(BackupCategory.APP_SETTINGS),
            historyStrategy = HistoryConflictStrategy.MERGE_PRESERVE_EXISTING,
            importedLyricsStrategy = ImportedLyricsConflictStrategy.SKIP_EXISTING
        )
        assertTrue(restoreResult is BackupRestoreResult.Success)
        assertTrue(dstFontRepo.customFontsFlow.value.isEmpty())
        val loadedDstPrefs = dstPrefs.userPreferencesFlow.first()
        assertNull(loadedDstPrefs.selectedCustomFontId)
        assertEquals(FontOption.OUTFIT, loadedDstPrefs.selectedFont)
    }

    @Test
    fun `hud-only backup omits custom font entries`() = runBlocking {
        val srcPrefs = createPrefsRepo("hud_only")
        val (srcFontRepo, _) = createFontRepo("hud_only", srcPrefs)
        val srcManager = createBackupManager(srcPrefs, srcFontRepo)
        srcFontRepo.importFontFromStream(ByteArrayInputStream(sampleTtfBytes(0x33)), "OnlySettings.ttf")

        val out = ByteArrayOutputStream()
        val exportResult = srcManager.createBackupToStream(setOf(BackupCategory.HUD_CONFIGURATION), out)
        assertTrue(exportResult is BackupExportResult.Success)

        val entries = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
            while (true) {
                val entry: ZipEntry = zis.nextEntry ?: break
                entries.add(entry.name)
                zis.closeEntry()
            }
        }
        assertFalse(entries.any { it.startsWith(BackupZipCodec.CUSTOM_FONTS_DIR_PREFIX) })
    }
}
