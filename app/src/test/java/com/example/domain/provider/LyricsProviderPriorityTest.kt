package com.example.domain.provider

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.data.backup.BackupAppSettingsData
import com.example.data.backup.BackupCategory
import com.example.data.backup.BackupDatabaseStore
import com.example.data.backup.BackupDatabaseTx
import com.example.data.backup.BackupExportResult
import com.example.data.backup.BackupInspectionResult
import com.example.data.backup.BackupRestoreManager
import com.example.data.backup.BackupRestoreResult
import com.example.data.backup.BackupZipCodec
import com.example.data.backup.DataStoreBackupPreferencesStore
import com.example.data.backup.HistoryConflictStrategy
import com.example.data.backup.ImportedLyricsConflictStrategy
import com.example.data.local.dao.LyricsDao
import com.example.data.local.dao.SongHistoryDao
import com.example.data.local.entity.LyricsEntity
import com.example.data.local.entity.SongHistoryEntity
import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata
import com.example.data.preferences.LyricsProviderPreference
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.CompositeLyricsRepository
import com.example.service.CompositeLyricsPipelineRepository
import com.example.service.MediaSessionPipelineEngine
import com.example.service.MediaSessionSnapshot
import com.example.service.TrackOffsetStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.SocketTimeoutException

class LyricsProviderPriorityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    private class InMemoryLyricsDao : LyricsDao {
        private val map = linkedMapOf<String, LyricsEntity>()
        override suspend fun getLyricsByKey(key: String): LyricsEntity? = map[key]
        override fun observeLyricsByKey(key: String): Flow<LyricsEntity?> = flowOf(map[key])
        override suspend fun insertLyrics(lyrics: LyricsEntity) { map[lyrics.key] = lyrics }
        override suspend fun deleteLyricsByKey(key: String) { map.remove(key) }
        override suspend fun clearAllLyrics() { map.clear() }
    }

    private class NoOpHistoryDao : SongHistoryDao {
        override fun getRecentHistory(): Flow<List<SongHistoryEntity>> = flowOf(emptyList())
        override suspend fun insertHistory(entry: SongHistoryEntity) = Unit
        override suspend fun deleteHistoryById(id: Long) = Unit
        override suspend fun clearHistory() = Unit
    }

    private fun validSyncedLyrics(providerName: String, title: String = "Test Song", artist: String = "Test Artist"): ParsedLyrics {
        val lines = listOf(
            LyricLine(1000L, "First line from $providerName"),
            LyricLine(5000L, "Second line from $providerName"),
            LyricLine(9000L, "Third line from $providerName")
        )
        return ParsedLyrics(
            title = title,
            artist = artist,
            isSynced = true,
            lines = lines,
            plainText = lines.joinToString("\n") { it.text },
            rawContent = "[00:01.00]First line from $providerName\n[00:05.00]Second line from $providerName\n[00:09.00]Third line from $providerName",
            source = providerName
        )
    }

    private fun testTrack(title: String = "Test Song", artist: String = "Test Artist", durationSec: Int = 180) =
        TrackMetadata(title = title, artist = artist, durationSec = durationSec)

    private fun createDataStoreFile(name: String): File = File(tempFolder.newFolder("ds_$name"), "$name.preferences_pb")

    private fun createPrefsRepo(file: File): UserPreferencesRepository {
        val ds = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { file }
        )
        return UserPreferencesRepository(ds)
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

    // -------------------------------------------------------------------------
    // 1. Normalization Tests
    // -------------------------------------------------------------------------

    @Test
    fun `default provider order matches exact existing 7-provider waterfall`() {
        val expected = listOf(
            LyricsProviderPreference.LRCLIB,
            LyricsProviderPreference.LRC_RED,
            LyricsProviderPreference.LYRICS_PLUS,
            LyricsProviderPreference.KUGOU,
            LyricsProviderPreference.LYRICA,
            LyricsProviderPreference.LIRIQO,
            LyricsProviderPreference.UNISON
        )
        assertEquals(expected, LyricsProviderPreference.DEFAULT_PROVIDER_ORDER)
        assertEquals(expected, LyricsProviderPreference.normalizeProviderOrder(emptyList()))
        assertEquals(expected, LyricsProviderPreference.parseProviderOrder(null))
        assertEquals(expected, LyricsProviderPreference.parseProviderOrder("   "))
    }

    @Test
    fun `normalization preserves custom order, deduplicates, ignores unknown and AUTO, and appends missing`() {
        val input = listOf(
            "UNISON",
            "AUTO",
            "LYRICS_OVH",
            "unknown_provider",
            "kugou",
            "UNISON",
            "LRCLIB"
        )
        val normalized = LyricsProviderPreference.normalizeProviderOrder(input)
        assertEquals(
            listOf(
                LyricsProviderPreference.UNISON,
                LyricsProviderPreference.KUGOU,
                LyricsProviderPreference.LRCLIB,
                LyricsProviderPreference.LRC_RED,
                LyricsProviderPreference.LYRICS_PLUS,
                LyricsProviderPreference.LYRICA,
                LyricsProviderPreference.LIRIQO
            ),
            normalized
        )
    }

    @Test
    fun `malformed saved string normalizes gracefully without crashing`() {
        val malformed = " , ; | \"LIRIQO\" , invalid_id , AUTO , , lrclib ; "
        val parsed = LyricsProviderPreference.parseProviderOrder(malformed)
        assertEquals(
            listOf(
                LyricsProviderPreference.LIRIQO,
                LyricsProviderPreference.LRCLIB,
                LyricsProviderPreference.LRC_RED,
                LyricsProviderPreference.LYRICS_PLUS,
                LyricsProviderPreference.KUGOU,
                LyricsProviderPreference.LYRICA,
                LyricsProviderPreference.UNISON
            ),
            parsed
        )
    }

    // -------------------------------------------------------------------------
    // 2. Search Execution Tests
    // -------------------------------------------------------------------------

    @Test
    fun `AUTO mode executes providers sequentially in custom order and stops at first valid winner`() = runBlocking {
        val callOrder = mutableListOf<String>()
        fun provider(name: String) = object : LyricsProvider {
            override val name: String = name
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                callOrder.add(name)
                return Result.success(validSyncedLyrics(name))
            }
        }

        val repo = CompositeLyricsRepository(
            lyricsDao = InMemoryLyricsDao(),
            historyDao = NoOpHistoryDao(),
            providers = listOf(
                provider("LRCLIB"),
                provider("lrc.red"),
                provider("KuGou"),
                provider("Unison")
            )
        )

        val customOrder = listOf(
            LyricsProviderPreference.UNISON,
            LyricsProviderPreference.KUGOU,
            LyricsProviderPreference.LRCLIB
        )

        val result = repo.getOrFetchLyrics(
            track = testTrack(),
            providerPreference = LyricsProviderPreference.AUTO,
            providerOrder = customOrder
        )

        assertTrue(result.isSuccess)
        assertEquals("Unison", result.getOrNull()?.source)
        assertEquals(listOf("Unison"), callOrder)
    }

    @Test
    fun `failed, unsynchronized, and invalid responses fall back along custom order`() = runBlocking {
        val callOrder = mutableListOf<String>()

        val failingFirst = object : LyricsProvider {
            override val name: String = "Unison"
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                callOrder.add(name)
                return Result.failure(RuntimeException("Not found"))
            }
        }
        val plainTextSecond = object : LyricsProvider {
            override val name: String = "Lyrica"
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                callOrder.add(name)
                return Result.success(
                    ParsedLyrics(
                        title = "Test Song",
                        artist = "Test Artist",
                        isSynced = false,
                        lines = emptyList(),
                        plainText = "Plain text only",
                        rawContent = "Plain text only",
                        source = name
                    )
                )
            }
        }
        val winningThird = object : LyricsProvider {
            override val name: String = "KuGou"
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                callOrder.add(name)
                return Result.success(validSyncedLyrics(name))
            }
        }
        val uncalledFourth = object : LyricsProvider {
            override val name: String = "LRCLIB"
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                callOrder.add(name)
                return Result.success(validSyncedLyrics(name))
            }
        }

        val repo = CompositeLyricsRepository(
            lyricsDao = InMemoryLyricsDao(),
            historyDao = NoOpHistoryDao(),
            providers = listOf(uncalledFourth, winningThird, plainTextSecond, failingFirst)
        )

        val result = repo.getOrFetchLyrics(
            track = testTrack(),
            providerPreference = LyricsProviderPreference.AUTO,
            providerOrder = listOf(
                LyricsProviderPreference.UNISON,
                LyricsProviderPreference.LYRICA,
                LyricsProviderPreference.KUGOU,
                LyricsProviderPreference.LRCLIB
            )
        )

        assertTrue(result.isSuccess)
        assertEquals("KuGou", result.getOrNull()?.source)
        assertEquals(listOf("Unison", "Lyrica", "KuGou"), callOrder)
    }

    @Test
    fun `manual provider selection ignores custom AUTO order`() = runBlocking {
        val callOrder = mutableListOf<String>()
        fun provider(name: String) = object : LyricsProvider {
            override val name: String = name
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                callOrder.add(name)
                return Result.success(validSyncedLyrics(name))
            }
        }

        val repo = CompositeLyricsRepository(
            lyricsDao = InMemoryLyricsDao(),
            historyDao = NoOpHistoryDao(),
            providers = listOf(provider("LRCLIB"), provider("Unison"), provider("KuGou"))
        )

        val result = repo.getOrFetchLyrics(
            track = testTrack(),
            providerPreference = LyricsProviderPreference.KUGOU,
            providerOrder = listOf(
                LyricsProviderPreference.UNISON,
                LyricsProviderPreference.LRCLIB,
                LyricsProviderPreference.KUGOU
            )
        )

        assertTrue(result.isSuccess)
        assertEquals("KuGou", result.getOrNull()?.source)
        assertEquals(listOf("KuGou"), callOrder)
    }

    @Test
    fun `timeout triggers cooldown and falls back while external cancellation stops waterfall immediately`() = runBlocking {
        val callOrder = mutableListOf<String>()
        val slowProvider = object : LyricsProvider {
            override val name: String = "Unison"
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                callOrder.add(name)
                delay(1500L)
                return Result.success(validSyncedLyrics(name))
            }
        }
        val fastProvider = object : LyricsProvider {
            override val name: String = "LRCLIB"
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                callOrder.add(name)
                return Result.success(validSyncedLyrics(name))
            }
        }

        val repo = CompositeLyricsRepository(
            lyricsDao = InMemoryLyricsDao(),
            historyDao = NoOpHistoryDao(),
            providers = listOf(fastProvider, slowProvider)
        ).apply {
            providerTimeoutMs = 100L
        }

        val customOrder = listOf(LyricsProviderPreference.UNISON, LyricsProviderPreference.LRCLIB)
        val timeoutFallback = repo.getOrFetchLyrics(
            track = testTrack(),
            providerPreference = LyricsProviderPreference.AUTO,
            providerOrder = customOrder
        )

        assertTrue(timeoutFallback.isSuccess)
        assertEquals("LRCLIB", timeoutFallback.getOrNull()?.source)
        assertEquals(listOf("Unison", "LRCLIB"), callOrder)
        assertTrue(repo.isProviderInCooldown("Unison"))

        // External coroutine cancellation must abort without querying next provider
        callOrder.clear()
        repo.clearCooldowns()
        repo.providerTimeoutMs = 5_000L
        val job: Job = launch {
            repo.getOrFetchLyrics(
                track = testTrack("Another Song", "Another Artist"),
                providerPreference = LyricsProviderPreference.AUTO,
                providerOrder = customOrder
            )
        }
        delay(50L)
        job.cancel()
        job.join()
        assertEquals(listOf("Unison"), callOrder)
    }

    @Test
    fun `injected or test-only providers are preserved during AUTO ordering`() = runBlocking {
        val callOrder = mutableListOf<String>()
        val lrclib = object : LyricsProvider {
            override val name: String = "LRCLIB"
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                callOrder.add(name)
                return Result.failure(SocketTimeoutException("timeout"))
            }
        }
        val fakeProvider = object : LyricsProvider {
            override val name: String = "FakeProvider"
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                callOrder.add(name)
                return Result.success(
                    ParsedLyrics(
                        title = title,
                        artist = artist,
                        isSynced = false,
                        lines = emptyList(),
                        plainText = "Synthetic test line",
                        rawContent = "Synthetic test line",
                        source = name
                    )
                )
            }
        }

        val repo = CompositeLyricsRepository(
            lyricsDao = InMemoryLyricsDao(),
            historyDao = NoOpHistoryDao(),
            providers = listOf(lrclib, fakeProvider)
        )

        val result = repo.getOrFetchLyrics(
            track = testTrack(),
            providerPreference = LyricsProviderPreference.AUTO,
            providerOrder = listOf(LyricsProviderPreference.UNISON, LyricsProviderPreference.LRCLIB)
        )

        assertTrue(result.isSuccess)
        assertEquals("FakeProvider", result.getOrNull()?.source)
        assertEquals(listOf("LRCLIB", "FakeProvider"), callOrder)
    }

    // -------------------------------------------------------------------------
    // 3. Persistence, Pipeline Non-Churn & Backup/Restore Tests
    // -------------------------------------------------------------------------

    @Test
    fun `custom provider order persists across DataStore restart`() = runBlocking {
        val dsFile = createDataStoreFile("persist_order")
        val scope1 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val firstDs = PreferenceDataStoreFactory.create(
            scope = scope1,
            produceFile = { dsFile }
        )
        val firstRepo = UserPreferencesRepository(firstDs)

        val customOrder = listOf(
            LyricsProviderPreference.LYRICA,
            LyricsProviderPreference.UNISON,
            LyricsProviderPreference.LRCLIB,
            LyricsProviderPreference.LIRIQO,
            LyricsProviderPreference.KUGOU,
            LyricsProviderPreference.LYRICS_PLUS,
            LyricsProviderPreference.LRC_RED
        )
        firstRepo.setLyricsProviderOrder(customOrder)
        assertEquals(customOrder, firstRepo.userPreferencesFlow.first().lyricsProviderOrder)

        scope1.coroutineContext[Job]?.cancelAndJoin()
        val scope2 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val restartedDs = PreferenceDataStoreFactory.create(
            scope = scope2,
            produceFile = { dsFile }
        )
        val restartedRepo = UserPreferencesRepository(restartedDs)
        assertEquals(customOrder, restartedRepo.userPreferencesFlow.first().lyricsProviderOrder)
        scope2.coroutineContext[Job]?.cancelAndJoin()
        Unit
    }

    @Test
    fun `reordering provider priority in pipeline does not trigger network search until fetch occurs`() = runBlocking {
        val callOrder = mutableListOf<String>()
        val lrclib = object : LyricsProvider {
            override val name: String = "LRCLIB"
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                callOrder.add(name)
                return Result.success(validSyncedLyrics(name))
            }
        }
        val unison = object : LyricsProvider {
            override val name: String = "Unison"
            override suspend fun fetchLyrics(title: String, artist: String, durationSec: Int?): Result<ParsedLyrics> {
                callOrder.add(name)
                return Result.success(validSyncedLyrics(name))
            }
        }

        val repo = CompositeLyricsRepository(
            lyricsDao = InMemoryLyricsDao(),
            historyDao = NoOpHistoryDao(),
            providers = listOf(lrclib, unison)
        )
        val pipelineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val engine = MediaSessionPipelineEngine(
            scope = pipelineScope,
            repository = CompositeLyricsPipelineRepository(repo),
            offsetStore = object : TrackOffsetStore {
                override fun getTrackOffsetFlow(trackKey: String, fallbackKey: String?): Flow<Long> = flowOf(0L)
                override suspend fun setTrackOffset(trackKey: String, offsetMs: Long) = Unit
            }
        )

        val token = Any()
        engine.attachSession(
            token,
            MediaSessionSnapshot(
                packageName = "com.spotify.music",
                playbackState = android.media.session.PlaybackState.STATE_PLAYING,
                positionMs = 1000L,
                playbackSpeed = 1f,
                hasMetadata = true,
                title = "Test Song",
                artist = "Test Artist"
            )
        )
        repeat(50) {
            if (engine.currentLyrics.value != null) return@repeat
            delay(20L)
        }
        assertEquals(listOf("LRCLIB"), callOrder)

        // Changing priority order alone must NOT clear cache or trigger a redundant search
        callOrder.clear()
        engine.onPreferencesChanged(
            newAutoFetch = true,
            newProvider = LyricsProviderPreference.AUTO,
            newProviderOrder = listOf(LyricsProviderPreference.UNISON, LyricsProviderPreference.LRCLIB)
        )
        delay(50L)
        assertTrue(callOrder.isEmpty())

        // Subsequent manual refresh (forceNetwork = true) uses the new Unison-first order
        val track = engine.playbackState.value.track!!
        engine.fetchLyricsForTrack(track, forceNetwork = true)
        repeat(50) {
            if (engine.currentLyrics.value?.source == "Unison") return@repeat
            delay(20L)
        }
        assertEquals(listOf("Unison"), callOrder)
        assertEquals("Unison", engine.currentLyrics.value?.source)
    }

    @Test
    fun `backup and restore preserves custom provider order, handles legacy backups and unknown IDs`() = runBlocking {
        val srcPrefs = createPrefsRepo(createDataStoreFile("backup_src"))
        val customOrder = listOf(
            LyricsProviderPreference.UNISON,
            LyricsProviderPreference.LIRIQO,
            LyricsProviderPreference.LYRICA,
            LyricsProviderPreference.KUGOU,
            LyricsProviderPreference.LYRICS_PLUS,
            LyricsProviderPreference.LRC_RED,
            LyricsProviderPreference.LRCLIB
        )
        srcPrefs.setLyricsProviderOrder(customOrder)
        srcPrefs.setLyricsProvider(LyricsProviderPreference.AUTO)

        val srcManager = BackupRestoreManager(
            preferencesStore = DataStoreBackupPreferencesStore(srcPrefs),
            databaseStore = createEmptyDbStore()
        )
        val out = ByteArrayOutputStream()
        val export = srcManager.createBackupToStream(setOf(BackupCategory.APP_SETTINGS), out)
        assertTrue(export is BackupExportResult.Success)

        val dstPrefs = createPrefsRepo(createDataStoreFile("backup_dst"))
        val dstManager = BackupRestoreManager(
            preferencesStore = DataStoreBackupPreferencesStore(dstPrefs),
            databaseStore = createEmptyDbStore()
        )
        val inspection = (dstManager.inspectBackupStream(
            ByteArrayInputStream(out.toByteArray()),
            "provider_order.zip"
        ) as BackupInspectionResult.Valid).inspection

        assertEquals(customOrder.map { it.name }, inspection.payload.appSettings?.lyricsProviderOrder)

        val restore = dstManager.restoreBackup(
            inspection = inspection,
            selectedCategories = setOf(BackupCategory.APP_SETTINGS),
            historyStrategy = HistoryConflictStrategy.MERGE_PRESERVE_EXISTING,
            importedLyricsStrategy = ImportedLyricsConflictStrategy.SKIP_EXISTING
        )
        assertTrue(restore is BackupRestoreResult.Success)
        assertEquals(customOrder, dstPrefs.userPreferencesFlow.first().lyricsProviderOrder)

        // Legacy backup without lyricsProviderOrder restores cleanly to DEFAULT_PROVIDER_ORDER
        val legacyOut = ByteArrayOutputStream()
        BackupZipCodec.writeBackupZip(
            outputStream = legacyOut,
            selectedCategories = setOf(BackupCategory.APP_SETTINGS),
            appVersion = "2.7.1",
            appVersionCode = 12,
            createdAtEpochMs = 1_780_000_000_000L,
            appSettings = BackupAppSettingsData(
                lyricsProvider = LyricsProviderPreference.UNISON.name,
                lyricsProviderOrder = null
            )
        )
        val legacyInspection = (dstManager.inspectBackupStream(
            ByteArrayInputStream(legacyOut.toByteArray()),
            "legacy.zip"
        ) as BackupInspectionResult.Valid).inspection
        assertTrue(
            dstManager.restoreBackup(
                inspection = legacyInspection,
                selectedCategories = setOf(BackupCategory.APP_SETTINGS),
                historyStrategy = HistoryConflictStrategy.MERGE_PRESERVE_EXISTING,
                importedLyricsStrategy = ImportedLyricsConflictStrategy.SKIP_EXISTING
            ) is BackupRestoreResult.Success
        )
        val legacyPrefs = dstPrefs.userPreferencesFlow.first()
        assertEquals(LyricsProviderPreference.UNISON, legacyPrefs.lyricsProvider)
        assertEquals(LyricsProviderPreference.DEFAULT_PROVIDER_ORDER, legacyPrefs.lyricsProviderOrder)

        // Backup containing unknown/duplicate IDs normalizes safely
        val messyOut = ByteArrayOutputStream()
        BackupZipCodec.writeBackupZip(
            outputStream = messyOut,
            selectedCategories = setOf(BackupCategory.APP_SETTINGS),
            appVersion = "2.7.2",
            appVersionCode = 13,
            createdAtEpochMs = 1_780_000_000_000L,
            appSettings = BackupAppSettingsData(
                lyricsProvider = LyricsProviderPreference.AUTO.name,
                lyricsProviderOrder = listOf("UNKNOWN_ID", "LYRICS_PLUS", "LYRICS_PLUS", "AUTO", "LRCLIB")
            )
        )
        val messyInspection = (dstManager.inspectBackupStream(
            ByteArrayInputStream(messyOut.toByteArray()),
            "messy.zip"
        ) as BackupInspectionResult.Valid).inspection
        assertTrue(
            dstManager.restoreBackup(
                inspection = messyInspection,
                selectedCategories = setOf(BackupCategory.APP_SETTINGS),
                historyStrategy = HistoryConflictStrategy.MERGE_PRESERVE_EXISTING,
                importedLyricsStrategy = ImportedLyricsConflictStrategy.SKIP_EXISTING
            ) is BackupRestoreResult.Success
        )
        assertEquals(
            listOf(
                LyricsProviderPreference.LYRICS_PLUS,
                LyricsProviderPreference.LRCLIB,
                LyricsProviderPreference.LRC_RED,
                LyricsProviderPreference.KUGOU,
                LyricsProviderPreference.LYRICA,
                LyricsProviderPreference.LIRIQO,
                LyricsProviderPreference.UNISON
            ),
            dstPrefs.userPreferencesFlow.first().lyricsProviderOrder
        )
    }
}
