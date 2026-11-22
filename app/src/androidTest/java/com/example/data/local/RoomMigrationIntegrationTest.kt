package com.example.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMigrationIntegrationTest {

    private val testDbName = "migration-test.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LyricBarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2_preservesData_andPassesSchemaValidation() {
        // 1. Create database in version 1 schema
        var db = helper.createDatabase(testDbName, 1)

        // 2. Insert representative records into v1 tables
        db.execSQL(
            """
            INSERT INTO lyrics (`key`, `title`, `artist`, `syncedLrc`, `plainLyrics`, `isSynced`, `durationSec`, `source`, `cachedAt`)
            VALUES ('artist-title-1', 'Test Title 1', 'Test Artist 1', '[00:01.00]Line 1', 'Line 1', 1, 180, 'LRCLIB', 1700000000000)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO song_history (`key`, `title`, `artist`, `albumArtUri`, `status`, `playedAt`)
            VALUES ('artist-title-1', 'Test Title 1', 'Test Artist 1', 'content://media/art/1', 'Played', 1700000005000)
            """.trimIndent()
        )

        db.close()

        // 3. Run migration 1 -> 2 and validate schema automatically against 2.json
        db = helper.runMigrationsAndValidate(testDbName, 2, true, LyricBarDatabase.MIGRATION_1_2)

        // 4. Verify lyrics table data is preserved
        val lyricsCursor = db.query("SELECT * FROM lyrics WHERE `key` = 'artist-title-1'")
        assertTrue("Lyrics record should exist after migration", lyricsCursor.moveToFirst())
        assertEquals("Test Title 1", lyricsCursor.getString(lyricsCursor.getColumnIndexOrThrow("title")))
        assertEquals("Test Artist 1", lyricsCursor.getString(lyricsCursor.getColumnIndexOrThrow("artist")))
        assertEquals("[00:01.00]Line 1", lyricsCursor.getString(lyricsCursor.getColumnIndexOrThrow("syncedLrc")))
        assertEquals(1, lyricsCursor.getInt(lyricsCursor.getColumnIndexOrThrow("isSynced")))
        assertEquals(180, lyricsCursor.getInt(lyricsCursor.getColumnIndexOrThrow("durationSec")))
        assertEquals("LRCLIB", lyricsCursor.getString(lyricsCursor.getColumnIndexOrThrow("source")))
        lyricsCursor.close()

        // 5. Verify song_history table data is preserved and new autoincrement ID is populated
        val historyCursor = db.query("SELECT * FROM song_history WHERE `key` = 'artist-title-1'")
        assertTrue("History record should exist after migration", historyCursor.moveToFirst())
        val id = historyCursor.getLong(historyCursor.getColumnIndexOrThrow("id"))
        assertTrue("Auto-increment ID should be >= 1", id >= 1)
        assertEquals("Test Title 1", historyCursor.getString(historyCursor.getColumnIndexOrThrow("title")))
        assertEquals("Test Artist 1", historyCursor.getString(historyCursor.getColumnIndexOrThrow("artist")))
        assertEquals("content://media/art/1", historyCursor.getString(historyCursor.getColumnIndexOrThrow("albumArtUri")))
        assertEquals("Played", historyCursor.getString(historyCursor.getColumnIndexOrThrow("status")))
        assertEquals(1700000005000L, historyCursor.getLong(historyCursor.getColumnIndexOrThrow("playedAt")))
        historyCursor.close()

        db.close()

        // 6. Confirm that the migrated database can be opened by the actual Room database implementation
        val roomDb = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            LyricBarDatabase::class.java,
            testDbName
        )
            .addMigrations(LyricBarDatabase.MIGRATION_1_2)
            .build()

        kotlinx.coroutines.runBlocking {
            val backupDao = roomDb.backupDao()
            val lyricsList = backupDao.getAllLyrics()
            assertEquals(1, lyricsList.size)
            assertEquals("Test Title 1", lyricsList[0].title)

            val historyList = backupDao.getAllHistory()
            assertEquals(1, historyList.size)
            assertEquals("Test Title 1", historyList[0].title)
        }

        roomDb.close()
    }
}
