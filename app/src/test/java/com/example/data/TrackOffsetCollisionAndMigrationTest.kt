package com.example.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.example.data.backup.BackupAppSettingsData
import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TrackOffsetCollisionAndMigrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var storeFile: File
    private lateinit var repository: UserPreferencesRepository
    private lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        storeFile = tempFolder.newFile("track_offset_test.preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { storeFile }
        )
        repository = UserPreferencesRepository(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun testComputeTrackOffsetKey_determinismAndFormat() {
        val trackKey = "Pink Floyd - Comfortably Numb [The Wall]"
        val key1 = UserPreferencesRepository.computeTrackOffsetKey(trackKey)
        val key2 = UserPreferencesRepository.computeTrackOffsetKey(trackKey)

        // Determinism
        assertEquals(key1, key2)

        // Format: starts with prefix
        assertTrue("Key must start with offset_sha256_ prefix", key1.startsWith("offset_sha256_"))

        // Total length = 14 prefix + 64 hex characters = 78
        assertEquals(78, key1.length)

        // Hex digest content
        val hexPart = key1.removePrefix("offset_sha256_")
        assertTrue("Hex digest must match 64 lowercase hex characters", hexPart.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun testComputeTrackOffsetKey_resolvesKnownHashCodeCollisions() {
        // In Java / Kotlin, "FB" and "Ea" produce identical String.hashCode() == 2236
        val s1 = "FB"
        val s2 = "Ea"
        assertEquals(s1.hashCode(), s2.hashCode())

        // Legacy keys would collide
        val legacyKey1 = UserPreferencesRepository.computeLegacyTrackOffsetKey(s1)
        val legacyKey2 = UserPreferencesRepository.computeLegacyTrackOffsetKey(s2)
        assertEquals(legacyKey1, legacyKey2)

        // SHA-256 keys do NOT collide
        val shaKey1 = UserPreferencesRepository.computeTrackOffsetKey(s1)
        val shaKey2 = UserPreferencesRepository.computeTrackOffsetKey(s2)
        assertNotEquals(shaKey1, shaKey2)

        // Another pair: "Aa" and "BB" (hashCode == 2112)
        val s3 = "Aa"
        val s4 = "BB"
        assertEquals(s3.hashCode(), s4.hashCode())
        assertEquals(
            UserPreferencesRepository.computeLegacyTrackOffsetKey(s3),
            UserPreferencesRepository.computeLegacyTrackOffsetKey(s4)
        )
        assertNotEquals(
            UserPreferencesRepository.computeTrackOffsetKey(s3),
            UserPreferencesRepository.computeTrackOffsetKey(s4)
        )
    }

    @Test
    fun testSetTrackOffset_savesToSha256KeyAndRetrieves() = runBlocking {
        val track = "Queen - Bohemian Rhapsody"
        repository.setTrackOffset(track, 750L)

        // Retrieved value
        val retrieved = repository.getTrackOffsetFlow(track).first()
        assertEquals(750L, retrieved)

        // Verify it was stored in the new SHA-256 key
        val expectedShaKey = longPreferencesKey(UserPreferencesRepository.computeTrackOffsetKey(track))
        val legacyKey = longPreferencesKey(UserPreferencesRepository.computeLegacyTrackOffsetKey(track))

        val rawPrefs = dataStore.data.first()
        assertEquals(750L, rawPrefs[expectedShaKey])
        assertNull(rawPrefs[legacyKey])
    }

    @Test
    fun testCollisionResistance_twoTracksWithSameHashCodeRetainDistinctOffsets() = runBlocking {
        // "FB" and "Ea" have the same hashCode
        val trackA = "FB"
        val trackB = "Ea"

        repository.setTrackOffset(trackA, 1200L)
        repository.setTrackOffset(trackB, -800L)

        // Under the old system, trackB would have overwritten trackA's offset to -800L.
        // Under SHA-256, both distinct offsets are preserved.
        assertEquals(1200L, repository.getTrackOffsetFlow(trackA).first())
        assertEquals(-800L, repository.getTrackOffsetFlow(trackB).first())
    }

    @Test
    fun testFallbackToLegacyKeyWhenSha256KeyAbsent() = runBlocking {
        val track = "The Beatles - Hey Jude"
        val legacyKey = longPreferencesKey(UserPreferencesRepository.computeLegacyTrackOffsetKey(track))
        val shaKey = longPreferencesKey(UserPreferencesRepository.computeTrackOffsetKey(track))

        // Pre-populate only the legacy key
        dataStore.edit { prefs ->
            prefs[legacyKey] = 450L
        }

        // Verify SHA-256 key does not exist yet
        assertNull(dataStore.data.first()[shaKey])

        // getTrackOffsetFlow should seamlessly fall back and return the legacy value
        val offset = repository.getTrackOffsetFlow(track).first()
        assertEquals(450L, offset)
    }

    @Test
    fun testExplicitMigration_copiesLegacyToNewKeyAndIsIdempotent() = runBlocking {
        val track = "Daft Punk - Get Lucky"
        val legacyKey = longPreferencesKey(UserPreferencesRepository.computeLegacyTrackOffsetKey(track))
        val shaKey = longPreferencesKey(UserPreferencesRepository.computeTrackOffsetKey(track))

        // Seed legacy key
        dataStore.edit { prefs ->
            prefs[legacyKey] = 300L
        }

        // First migration should return true and set new key
        val migrated = repository.migrateTrackOffset(track)
        assertTrue(migrated)

        val prefsAfterMigration = dataStore.data.first()
        assertEquals(300L, prefsAfterMigration[shaKey])
        assertEquals(300L, prefsAfterMigration[legacyKey])

        // Second migration should return false (already migrated)
        val secondMigrated = repository.migrateTrackOffset(track)
        assertFalse(secondMigrated)
    }

    @Test
    fun testMigrationDoesNotOverwriteExistingNewFormatValue() = runBlocking {
        val track = "Fleetwood Mac - Dreams"
        val legacyKey = longPreferencesKey(UserPreferencesRepository.computeLegacyTrackOffsetKey(track))
        val shaKey = longPreferencesKey(UserPreferencesRepository.computeTrackOffsetKey(track))

        // Seed both: a new format value (999L) and an old legacy value (200L)
        dataStore.edit { prefs ->
            prefs[shaKey] = 999L
            prefs[legacyKey] = 200L
        }

        // migrateTrackOffset should NOT overwrite the new key
        val migrated = repository.migrateTrackOffset(track)
        assertFalse(migrated)

        val prefs = dataStore.data.first()
        assertEquals(999L, prefs[shaKey])
        assertEquals(999L, repository.getTrackOffsetFlow(track).first())
    }

    @Test
    fun testFallbackTrackKeyHierarchy() = runBlocking {
        val primaryTrack = "Primary Song"
        val fallbackTrack = "Fallback Song"

        // 1. When neither exists -> 0L
        assertEquals(0L, repository.getTrackOffsetFlow(primaryTrack, fallbackTrack).first())

        // 2. When only fallback exists -> returns fallback
        repository.setTrackOffset(fallbackTrack, 600L)
        assertEquals(600L, repository.getTrackOffsetFlow(primaryTrack, fallbackTrack).first())

        // 3. When primary exists -> returns primary
        repository.setTrackOffset(primaryTrack, -300L)
        assertEquals(-300L, repository.getTrackOffsetFlow(primaryTrack, fallbackTrack).first())
    }

    @Test
    fun testBackupAndRestore_handlesBothLegacyAndSha256KeysSafely() = runBlocking {
        val legacyKey = UserPreferencesRepository.computeLegacyTrackOffsetKey("Legacy Track")
        val shaKey = UserPreferencesRepository.computeTrackOffsetKey("New SHA-256 Track")
        val tooLongKey = "offset_" + "a".repeat(130) // length > 128
        val invalidPrefixKey = "wrong_prefix_123"

        val backupData = BackupAppSettingsData(
            trackOffsets = mapOf(
                legacyKey to 500L,
                shaKey to -350L,
                tooLongKey to 9999L,
                invalidPrefixKey to 8888L
            )
        )

        repository.applyBackupPreferences(appSettings = backupData)

        val restoredPrefs = dataStore.data.first()
        assertEquals(500L, restoredPrefs[longPreferencesKey(legacyKey)])
        assertEquals(-350L, restoredPrefs[longPreferencesKey(shaKey)])
        assertNull(restoredPrefs[longPreferencesKey(tooLongKey)])
        assertNull(restoredPrefs[longPreferencesKey(invalidPrefixKey)])

        assertEquals(500L, repository.getTrackOffsetFlow("Legacy Track").first())
        assertEquals(-350L, repository.getTrackOffsetFlow("New SHA-256 Track").first())
    }

    @Test
    fun testBlankTrackKey_safelyIgnored() = runBlocking {
        repository.setTrackOffset("", 1000L)
        repository.setTrackOffset("   ", 1000L)

        assertEquals(0L, repository.getTrackOffsetFlow("").first())
        assertEquals(0L, repository.getTrackOffsetFlow("   ").first())

        val rawPrefs = dataStore.data.first()
        assertTrue(rawPrefs.asMap().isEmpty())
    }
}
