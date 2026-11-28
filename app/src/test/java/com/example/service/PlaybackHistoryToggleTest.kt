package com.example.service

import android.media.session.PlaybackState
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.data.backup.BackupAppSettingsData
import com.example.data.backup.BackupCategory
import com.example.data.backup.BackupDatabaseStore
import com.example.data.backup.BackupDatabaseTx
import com.example.data.backup.BackupExportResult
import com.example.data.backup.BackupInspectionResult
import com.example.data.backup.BackupManifest
import com.example.data.backup.BackupRestoreManager
import com.example.data.backup.BackupRestoreResult
import com.example.data.backup.BackupZipCodec
import com.example.data.backup.DataStoreBackupPreferencesStore
import com.example.data.backup.HistoryConflictStrategy
import com.example.data.backup.ImportedLyricsConflictStrategy
import com.example.data.local.entity.LyricsEntity
import com.example.data.local.entity.SongHistoryEntity
import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata
import com.example.data.preferences.LyricsProviderPreference
import com.example.data.preferences.UserPreferences
import com.example.data.preferences.UserPreferencesRepository
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PlaybackHistoryToggleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakePipelineRepository(
        private val onRecordDelay: (suspend () -> Unit)? = null
    ) : LyricsPipelineRepository {
        val recordedHistory = mutableListOf<SongHistoryEntity>()
        val fetchedTracks = mutableListOf<String>()

        override suspend fun getOrFetchLyrics(
            track: TrackMetadata,
            forceNetwork: Boolean,
            allowNetwork: Boolean,
            providerPreference: LyricsProviderPreference,
            providerOrder: List<LyricsProviderPreference>
        ): Result<ParsedLyrics> {
            fetchedTracks.add(track.identityKey)
            val lines = listOf(
                LyricLine(1000L, "Line 1"),
                LyricLine(5000L, "Line 2")
            )
            return Result.success(
                ParsedLyrics(
                    title = track.title,
                    artist = track.artist,
                    isSynced = true,
                    lines = lines,
                    plainText = "Line 1\nLine 2",
                    rawContent = "[00:01.00]Line 1\n[00:05.00]Line 2",
                    source = "LRCLIB"
                )
            )
        }

        override suspend fun saveImportedLyrics(
            track: TrackMetadata,
            content: String,
            source: String
        ): ParsedLyrics = throw UnsupportedOperationException()

        override suspend fun recordHistory(track: TrackMetadata, status: String) {
            onRecordDelay?.invoke()
            recordedHistory.add(
                SongHistoryEntity(
                    id = (recordedHistory.size + 1).toLong(),
                    key = track.identityKey,
                    title = track.title,
                    artist = track.artist,
                    status = status,
                    playedAt = 1000L + recordedHistory.size
                )
            )
        }
    }

    private fun noOpOffsetStore(): TrackOffsetStore = object : TrackOffsetStore {
        override fun getTrackOffsetFlow(trackKey: String, fallbackKey: String?): Flow<Long> = flowOf(0L)
        override suspend fun setTrackOffset(trackKey: String, offsetMs: Long) = Unit
    }

    private fun playingSnapshot(
        title: String = "Song One",
        artist: String = "Artist One",
        positionMs: Long = 1000L
    ) = MediaSessionSnapshot(
        packageName = "com.spotify.music",
        playbackState = PlaybackState.STATE_PLAYING,
        positionMs = positionMs,
        playbackSpeed = 1.0f,
        hasMetadata = true,
        title = title,
        artist = artist,
        durationMs = 180_000L
    )

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

    @Test
    fun `default preference is enabled and survives DataStore restart`() = runBlocking {
        assertTrue(UserPreferences().playbackHistoryEnabled)

        val dir = tempFolder.newFolder("ds_history_persist")
        val dsFile = File(dir, "prefs.preferences_pb")
        val scope1 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val repo1 = UserPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = scope1, produceFile = { dsFile })
        )

        assertTrue(repo1.userPreferencesFlow.first().playbackHistoryEnabled)
        repo1.setPlaybackHistoryEnabled(false)
        assertFalse(repo1.userPreferencesFlow.first().playbackHistoryEnabled)

        scope1.coroutineContext[Job]?.cancelAndJoin()
        val scope2 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val repo2 = UserPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = scope2, produceFile = { dsFile })
        )
        assertFalse(repo2.userPreferencesFlow.first().playbackHistoryEnabled)
        scope2.coroutineContext[Job]?.cancelAndJoin()
        Unit
    }

    @Test
    fun `enabled records tracks with deduplication, disabled records nothing while preserving playback, lyrics, and existing history`() = runBlocking {
        val repo = FakePipelineRepository()
        // Pre-existing history row before disabling
        repo.recordedHistory.add(
            SongHistoryEntity(id = 99L, key = "old - track", title = "Old", artist = "Track", playedAt = 500L)
        )
        val engineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val engine = MediaSessionPipelineEngine(
            scope = engineScope,
            repository = repo,
            offsetStore = noOpOffsetStore()
        )
        val token = Any()

        // Enabled by default: records Song One once across repeated state ticks
        engine.attachSession(token, playingSnapshot("Song One", "Artist One", positionMs = 1000L))
        engine.updatePlaybackState(token, PlaybackState.STATE_PLAYING, 5000L, 1.0f)
        assertEquals(2, repo.recordedHistory.size)
        assertEquals("Song One", repo.recordedHistory.last().title)

        // Disable history recording
        engine.onPreferencesChanged(
            newAutoFetch = true,
            newProvider = LyricsProviderPreference.AUTO,
            newPlaybackHistoryEnabled = false
        )

        // New track still attaches, updates playback state, and fetches lyrics normally, but records NO history
        engine.updateMetadata(token, playingSnapshot("Song Two", "Artist Two", positionMs = 1000L))
        engine.updatePlaybackState(token, PlaybackState.STATE_PLAYING, 3000L, 1.0f)

        assertEquals(2, repo.recordedHistory.size) // Old + Song One still intact; Song Two not added
        assertEquals("Song Two", engine.playbackState.value.track?.title)
        assertTrue(engine.playbackState.value.isPlaying)
        assertNotNull(engine.currentLyrics.value)
        assertEquals("Song Two", engine.currentLyrics.value?.title)
        assertTrue(repo.fetchedTracks.any { it.contains("song two") })
        engineScope.coroutineContext[Job]?.cancelAndJoin()
        Unit
    }

    @Test
    fun `re-enabling while same track is playing resets deduplication key and records immediately`() = runBlocking {
        val repo = FakePipelineRepository()
        val engineScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val engine = MediaSessionPipelineEngine(
            scope = engineScope,
            repository = repo,
            offsetStore = noOpOffsetStore()
        )
        val token = Any()

        // Start playing while history is disabled
        engine.onPreferencesChanged(
            newAutoFetch = true,
            newProvider = LyricsProviderPreference.AUTO,
            newPlaybackHistoryEnabled = false
        )
        engine.attachSession(token, playingSnapshot("Live Track", "Live Artist"))
        assertTrue(repo.recordedHistory.isEmpty())

        // Re-enable without changing tracks: records immediately
        engine.onPreferencesChanged(
            newAutoFetch = true,
            newProvider = LyricsProviderPreference.AUTO,
            newPlaybackHistoryEnabled = true
        )
        assertEquals(1, repo.recordedHistory.size)
        assertEquals("Live Track", repo.recordedHistory.first().title)

        // Continuous playback state tick still deduplicates
        engine.updatePlaybackState(token, PlaybackState.STATE_PLAYING, 4000L, 1.0f)
        assertEquals(1, repo.recordedHistory.size)

        // Disable and re-enable again on same playing track: allows recording again
        engine.onPreferencesChanged(
            newAutoFetch = true,
            newProvider = LyricsProviderPreference.AUTO,
            newPlaybackHistoryEnabled = false
        )
        engine.onPreferencesChanged(
            newAutoFetch = true,
            newProvider = LyricsProviderPreference.AUTO,
            newPlaybackHistoryEnabled = true
        )
        assertEquals(2, repo.recordedHistory.size)
        engineScope.coroutineContext[Job]?.cancelAndJoin()
        Unit
    }

    @Test
    fun `disabling during in-flight history recording cancels job and blocks late insertion`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val repo = FakePipelineRepository(
            onRecordDelay = {
                started.complete(Unit)
                gate.await()
            }
        )
        val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val engine = MediaSessionPipelineEngine(
            scope = engineScope,
            repository = repo,
            offsetStore = noOpOffsetStore()
        )
        val token = Any()

        engine.attachSession(token, playingSnapshot("Delayed Track", "Delayed Artist"))
        started.await()

        // Disable while historyJob is suspended inside recordHistory
        engine.onPreferencesChanged(
            newAutoFetch = true,
            newProvider = LyricsProviderPreference.AUTO,
            newPlaybackHistoryEnabled = false
        )
        gate.complete(Unit)
        delay(50L)

        assertTrue(repo.recordedHistory.isEmpty())
        engineScope.coroutineContext[Job]?.cancelAndJoin()
        Unit
    }

    @Test
    fun `new backups preserve playbackHistoryEnabled and older backups lacking field default to true`() = runBlocking {
        val dir = tempFolder.newFolder("ds_backup_hist")
        val srcScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val srcPrefs = UserPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = srcScope, produceFile = { File(dir, "src.preferences_pb") })
        )
        srcPrefs.setPlaybackHistoryEnabled(false)

        val srcManager = BackupRestoreManager(
            preferencesStore = DataStoreBackupPreferencesStore(srcPrefs),
            databaseStore = createEmptyDbStore()
        )
        val out = ByteArrayOutputStream()
        assertTrue(srcManager.createBackupToStream(setOf(BackupCategory.APP_SETTINGS), out) is BackupExportResult.Success)

        val dstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dstPrefs = UserPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = dstScope, produceFile = { File(dir, "dst.preferences_pb") })
        )
        val dstManager = BackupRestoreManager(
            preferencesStore = DataStoreBackupPreferencesStore(dstPrefs),
            databaseStore = createEmptyDbStore()
        )

        val inspection = (dstManager.inspectBackupStream(
            ByteArrayInputStream(out.toByteArray()),
            "history_toggle.zip"
        ) as BackupInspectionResult.Valid).inspection
        assertFalse(inspection.payload.appSettings!!.playbackHistoryEnabled)

        assertTrue(
            dstManager.restoreBackup(
                inspection = inspection,
                selectedCategories = setOf(BackupCategory.APP_SETTINGS),
                historyStrategy = HistoryConflictStrategy.MERGE_PRESERVE_EXISTING,
                importedLyricsStrategy = ImportedLyricsConflictStrategy.SKIP_EXISTING
            ) is BackupRestoreResult.Success
        )
        assertFalse(dstPrefs.userPreferencesFlow.first().playbackHistoryEnabled)

        // Simulate legacy backup JSON that omits "playbackHistoryEnabled" completely
        val legacySettingsJson = """{"schemaVersion":1,"themeMode":"DARK","selectedFont":"POPPINS","lyricsProvider":"AUTO"}"""
            .toByteArray(StandardCharsets.UTF_8)
        val sha = MessageDigest.getInstance("SHA-256").digest(legacySettingsJson)
            .joinToString("") { "%02x".format(it) }
        val manifest = BackupManifest(
            appVersion = "2.7.1",
            appVersionCode = 12,
            createdAtEpochMs = 1_780_000_000_000L,
            includedCategories = listOf(BackupCategory.APP_SETTINGS.id),
            checksums = mapOf(BackupZipCodec.SETTINGS_PATH to sha)
        )
        val legacyZipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                zos.putNextEntry(ZipEntry(BackupZipCodec.MANIFEST_PATH))
                zos.write(Gson().toJson(manifest).toByteArray(StandardCharsets.UTF_8))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry(BackupZipCodec.SETTINGS_PATH))
                zos.write(legacySettingsJson)
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        val legacyInspection = (dstManager.inspectBackupStream(
            ByteArrayInputStream(legacyZipBytes),
            "legacy_without_field.zip"
        ) as BackupInspectionResult.Valid).inspection
        assertTrue(legacyInspection.payload.appSettings!!.playbackHistoryEnabled)

        assertTrue(
            dstManager.restoreBackup(
                inspection = legacyInspection,
                selectedCategories = setOf(BackupCategory.APP_SETTINGS),
                historyStrategy = HistoryConflictStrategy.MERGE_PRESERVE_EXISTING,
                importedLyricsStrategy = ImportedLyricsConflictStrategy.SKIP_EXISTING
            ) is BackupRestoreResult.Success
        )
        assertTrue(dstPrefs.userPreferencesFlow.first().playbackHistoryEnabled)

        srcScope.coroutineContext[Job]?.cancelAndJoin()
        dstScope.coroutineContext[Job]?.cancelAndJoin()
        Unit
    }
}
