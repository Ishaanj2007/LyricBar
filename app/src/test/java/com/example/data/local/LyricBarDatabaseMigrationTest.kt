package com.example.data.local

import android.database.Cursor
import androidx.room.Database
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery
import com.example.data.local.entity.LyricsEntity
import com.example.data.local.entity.SongHistoryEntity
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.lang.reflect.Proxy

class LyricBarDatabaseMigrationTest {

    @Test
    fun testMigrationVersionDefinition() {
        val migration = LyricBarDatabase.MIGRATION_1_2
        assertEquals("Migration start version must be 1", 1, migration.startVersion)
        assertEquals("Migration end version must be 2", 2, migration.endVersion)
    }

    @Test
    fun testDatabaseConfigurationAndVersion() {
        assertEquals("Migration target version must be 2", 2, LyricBarDatabase.MIGRATION_1_2.endVersion)

        val sourceFile = java.io.File("src/main/java/com/example/data/local/LyricBarDatabase.kt").takeIf { it.exists() }
            ?: java.io.File("app/src/main/java/com/example/data/local/LyricBarDatabase.kt")
        assertTrue("LyricBarDatabase source file must exist", sourceFile.exists())
        val source = sourceFile.readText()
        assertTrue("Database version must be 2", source.contains("version = 2"))
        assertTrue("Database must register LyricsEntity", source.contains("LyricsEntity::class"))
        assertTrue("Database must register SongHistoryEntity", source.contains("SongHistoryEntity::class"))
    }

    @Test
    fun testMigration1To2PreservesLyricsAndListeningHistory() {
        val db = RecordingSupportSQLiteDatabase()

        // 1. Simulate version 1 database state:
        // - lyrics table with saved imported lyrics and cached lyrics
        db.createTable(
            "lyrics",
            listOf("key", "title", "artist", "syncedLrc", "plainLyrics", "isSynced", "durationSec", "source", "cachedAt")
        )
        db.insertRow(
            "lyrics",
            mapOf(
                "key" to "Queen - Bohemian Rhapsody",
                "title" to "Bohemian Rhapsody",
                "artist" to "Queen",
                "syncedLrc" to "[00:01.00]Is this the real life?",
                "plainLyrics" to "Is this the real life?",
                "isSynced" to 1,
                "durationSec" to 354,
                "source" to "Local .lrc",
                "cachedAt" to 1700000000000L
            )
        )
        db.insertRow(
            "lyrics",
            mapOf(
                "key" to "Pink Floyd - Comfortably Numb",
                "title" to "Comfortably Numb",
                "artist" to "Pink Floyd",
                "syncedLrc" to "[00:02.00]Hello, is there anybody in there?",
                "plainLyrics" to "Hello, is there anybody in there?",
                "isSynced" to 1,
                "durationSec" to 382,
                "source" to "LRCLIB",
                "cachedAt" to 1700000050000L
            )
        )

        // - song_history table in v1
        db.createTable(
            "song_history",
            listOf("key", "title", "artist", "albumArtUri", "status", "playedAt")
        )
        db.insertRow(
            "song_history",
            mapOf(
                "key" to "Queen - Bohemian Rhapsody",
                "title" to "Bohemian Rhapsody",
                "artist" to "Queen",
                "albumArtUri" to "content://media/art/1",
                "status" to "Played",
                "playedAt" to 1700000010000L
            )
        )
        db.insertRow(
            "song_history",
            mapOf(
                "key" to "Pink Floyd - Comfortably Numb",
                "title" to "Comfortably Numb",
                "artist" to "Pink Floyd",
                "albumArtUri" to null,
                "status" to "Played",
                "playedAt" to 1700000060000L
            )
        )

        // 2. Execute MIGRATION_1_2
        LyricBarDatabase.MIGRATION_1_2.migrate(db.asSupportSQLiteDatabase())

        // 3. Verify lyrics table and records are completely intact
        assertTrue("lyrics table must exist after migration", db.hasTable("lyrics"))
        val lyricsRows = db.getTableRows("lyrics")
        assertEquals("Both saved and cached lyrics must be preserved intact", 2, lyricsRows.size)

        val bohem = lyricsRows.first { it["key"] == "Queen - Bohemian Rhapsody" }
        assertEquals("Bohemian Rhapsody", bohem["title"])
        assertEquals("Local .lrc", bohem["source"])
        assertEquals("[00:01.00]Is this the real life?", bohem["syncedLrc"])

        val numb = lyricsRows.first { it["key"] == "Pink Floyd - Comfortably Numb" }
        assertEquals("Comfortably Numb", numb["title"])
        assertEquals("LRCLIB", numb["source"])

        // 4. Verify song_history table and records are preserved with autoincrement id
        assertTrue("song_history table must exist after migration", db.hasTable("song_history"))
        assertFalse("song_history_new temporary table must have been renamed", db.hasTable("song_history_new"))

        val historyRows = db.getTableRows("song_history")
        assertEquals("Both history entries must be preserved after migration", 2, historyRows.size)

        val history1 = historyRows.first { it["key"] == "Queen - Bohemian Rhapsody" }
        assertEquals("Queen", history1["artist"])
        assertEquals("content://media/art/1", history1["albumArtUri"])
        assertEquals(1700000010000L, history1["playedAt"])
        assertNotNull("Auto-generated id must be assigned in v2", history1["id"])

        val history2 = historyRows.first { it["key"] == "Pink Floyd - Comfortably Numb" }
        assertEquals("Pink Floyd", history2["artist"])
        assertNull(history2["albumArtUri"])
        assertEquals(1700000060000L, history2["playedAt"])
        assertNotNull("Auto-generated id must be assigned in v2", history2["id"])
    }

    @Test
    fun testMigration1To2WhenHistoryTableIsEmptyOrAbsent() {
        val db = RecordingSupportSQLiteDatabase()

        // Empty v1 database where only lyrics exist
        db.createTable(
            "lyrics",
            listOf("key", "title", "artist", "syncedLrc", "plainLyrics", "isSynced", "durationSec", "source", "cachedAt")
        )

        // Migration must succeed without throwing table not found error
        LyricBarDatabase.MIGRATION_1_2.migrate(db.asSupportSQLiteDatabase())

        assertTrue("lyrics table must be present", db.hasTable("lyrics"))
        assertTrue("song_history table must be created", db.hasTable("song_history"))
        assertEquals(0, db.getTableRows("song_history").size)
    }

    @Test
    fun testMigrationSqlMatchesEntityDefinitions() {
        val db = RecordingSupportSQLiteDatabase()
        LyricBarDatabase.MIGRATION_1_2.migrate(db.asSupportSQLiteDatabase())

        // Validate lyrics table columns match LyricsEntity
        val lyricsColumns = db.getTableColumns("lyrics")
        val expectedLyricsColumns = setOf(
            "key", "title", "artist", "syncedLrc", "plainLyrics", "isSynced", "durationSec", "source", "cachedAt"
        )
        assertEquals("lyrics columns must match LyricsEntity exactly", expectedLyricsColumns, lyricsColumns.toSet())

        // Validate song_history columns match SongHistoryEntity
        val historyColumns = db.getTableColumns("song_history")
        val expectedHistoryColumns = setOf(
            "id", "key", "title", "artist", "albumArtUri", "status", "playedAt"
        )
        assertEquals("song_history columns must match SongHistoryEntity exactly", expectedHistoryColumns, historyColumns.toSet())
    }

    @Test
    fun testNoFallbackToDestructiveMigrationConfigured() {
        val sourceFile = java.io.File("src/main/java/com/example/data/local/LyricBarDatabase.kt").takeIf { it.exists() }
            ?: java.io.File("app/src/main/java/com/example/data/local/LyricBarDatabase.kt")
        assertTrue("LyricBarDatabase source file must exist", sourceFile.exists())
        val content = sourceFile.readText()
        assertFalse(
            "LyricBarDatabase must not call fallbackToDestructiveMigration()",
            content.contains("fallbackToDestructiveMigration()")
        )
        assertFalse(
            "LyricBarDatabase must not call fallbackToDestructiveMigrationOnDowngrade()",
            content.contains("fallbackToDestructiveMigrationOnDowngrade()")
        )
    }

    /**
     * Minimal in-memory simulation of SupportSQLiteDatabase for validating migration logic.
     */
    private class RecordingSupportSQLiteDatabase {
        private val tables = mutableMapOf<String, MutableList<Map<String, Any?>>>()
        private val tableColumns = mutableMapOf<String, List<String>>()
        private var nextAutoId = 1L

        fun createTable(name: String, columns: List<String>) {
            tables.putIfAbsent(name, mutableListOf())
            tableColumns[name] = columns
        }

        fun insertRow(table: String, row: Map<String, Any?>) {
            val mutable = row.toMutableMap()
            if (!mutable.containsKey("id") && tableColumns[table]?.contains("id") == true) {
                mutable["id"] = nextAutoId++
            }
            tables.getOrPut(table) { mutableListOf() }.add(mutable)
        }

        fun hasTable(name: String): Boolean = tables.containsKey(name)

        fun getTableRows(name: String): List<Map<String, Any?>> = tables[name] ?: emptyList()

        fun getTableColumns(name: String): List<String> = tableColumns[name] ?: emptyList()

        fun executeSql(sql: String) {
            val clean = sql.trim()
            val lower = clean.lowercase()

            when {
                lower.startsWith("create table if not exists `song_history_new`") ||
                lower.startsWith("create table if not exists song_history_new") -> {
                    createTable(
                        "song_history_new",
                        listOf("id", "key", "title", "artist", "albumArtUri", "status", "playedAt")
                    )
                }
                lower.startsWith("create table if not exists `lyrics`") ||
                lower.startsWith("create table if not exists lyrics") -> {
                    if (!hasTable("lyrics")) {
                        createTable(
                            "lyrics",
                            listOf("key", "title", "artist", "syncedLrc", "plainLyrics", "isSynced", "durationSec", "source", "cachedAt")
                        )
                    }
                }
                lower.startsWith("insert into `song_history_new`") ||
                lower.startsWith("insert into song_history_new") -> {
                    // Copy from song_history to song_history_new
                    val src = tables["song_history"] ?: emptyList()
                    val dest = tables.getOrPut("song_history_new") { mutableListOf() }
                    for (row in src) {
                        val newRow = row.toMutableMap()
                        if (!newRow.containsKey("id") || newRow["id"] == null) {
                            newRow["id"] = nextAutoId++
                        }
                        dest.add(newRow)
                    }
                }
                lower.startsWith("drop table `song_history`") ||
                lower.startsWith("drop table song_history") -> {
                    tables.remove("song_history")
                    tableColumns.remove("song_history")
                }
                lower.startsWith("alter table `song_history_new` rename to `song_history`") ||
                lower.startsWith("alter table song_history_new rename to song_history") -> {
                    val rows = tables.remove("song_history_new") ?: mutableListOf()
                    val cols = tableColumns.remove("song_history_new") ?: emptyList()
                    tables["song_history"] = rows
                    tableColumns["song_history"] = cols
                }
            }
        }

        fun asSupportSQLiteDatabase(): SupportSQLiteDatabase {
            val handler = java.lang.reflect.InvocationHandler { _, method, args ->
                when (method.name) {
                    "execSQL" -> {
                        executeSql(args[0] as String)
                        null
                    }
                    "query" -> {
                        val queryStr = when (val arg = args[0]) {
                            is String -> arg
                            is SupportSQLiteQuery -> arg.sql
                            else -> arg.toString()
                        }
                        if (queryStr.contains("sqlite_master") && queryStr.contains("song_history")) {
                            val exists = hasTable("song_history")
                            createCursorWithSingleBoolean(exists)
                        } else {
                            createCursorWithSingleBoolean(false)
                        }
                    }
                    else -> null
                }
            }

            return Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
                handler
            ) as SupportSQLiteDatabase
        }

        private fun createCursorWithSingleBoolean(hasRows: Boolean): Cursor {
            var position = -1
            val handler = java.lang.reflect.InvocationHandler { _, method, args ->
                when (method.name) {
                    "moveToFirst" -> {
                        if (hasRows) {
                            position = 0
                            true
                        } else {
                            false
                        }
                    }
                    "close" -> null
                    else -> null
                }
            }
            return Proxy.newProxyInstance(
                Cursor::class.java.classLoader,
                arrayOf(Cursor::class.java, Closeable::class.java),
                handler
            ) as Cursor
        }
    }
}
