package com.example.data.backup

import com.example.domain.parser.LrcImporter
import com.example.domain.parser.LrcParser
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Pure ZIP archive encoder/decoder for LyricBar portable backups.
 *
 * Security & integrity invariants:
 * - Strict path-traversal rejection (`..`, absolute paths, backslashes, null bytes, unexpected paths).
 * - Duplicate ZIP entry rejection.
 * - Bounded streaming decompression against ZIP bombs (entry count, per-entry bytes, total bytes).
 * - Per-file SHA-256 checksums recorded in `manifest.json` and verified on read.
 * - Independent `formatVersion` validation decoupled from `appVersion`.
 * - Zero logging of user preferences, history, or lyrics content.
 */
object BackupZipCodec {

    const val MANIFEST_PATH = "manifest.json"
    const val SETTINGS_PATH = "settings/preferences.json"
    const val HUD_PATH = "hud/configuration.json"
    const val HISTORY_PATH = "lyrics/history.json"
    const val IMPORTED_INDEX_PATH = "imported_lyrics/index.json"
    const val IMPORTED_DIR_PREFIX = "imported_lyrics/"

    const val MAX_ZIP_ENTRIES = 500
    const val MAX_SINGLE_ENTRY_UNCOMPRESSED_BYTES = 5L * 1024L * 1024L // 5 MB
    const val MAX_TOTAL_UNCOMPRESSED_BYTES = 25L * 1024L * 1024L // 25 MB

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    fun formatIsoUtc(epochMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(epochMs))
    }

    fun defaultBackupFileName(epochMs: Long = System.currentTimeMillis()): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return "LyricBar_Backup_${formatter.format(Date(epochMs))}.zip"
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format(Locale.US, "%02x", b))
        }
        return sb.toString()
    }

    /**
     * Sanitizes a song title/artist pair into a safe, portable `.lrc` filename
     * with deterministic disambiguation against [usedLowercaseNames].
     */
    fun buildSafeUniqueLrcFileName(
        artist: String,
        title: String,
        fallbackKey: String,
        usedLowercaseNames: MutableSet<String>
    ): String {
        val rawBase = listOf(artist.trim(), title.trim())
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .ifBlank { fallbackKey.trim() }
            .ifBlank { "imported_lyrics" }

        val cleaned = rawBase
            .replace(Regex("[^A-Za-z0-9._\\- ]+"), "_")
            .replace(Regex("\\s+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_', '.', ' ')
            .take(80)
            .ifBlank { "imported_lyrics" }

        var candidate = "$cleaned.lrc"
        var suffix = 2
        while (candidate.lowercase(Locale.US) in usedLowercaseNames) {
            candidate = "${cleaned.take(72)}_$suffix.lrc"
            suffix++
        }
        usedLowercaseNames.add(candidate.lowercase(Locale.US))
        return candidate
    }

    /**
     * Writes ONLY the categories present in [payload] into a portable ZIP archive.
     * Returns the final [BackupManifest] (including computed SHA-256 checksums) that was written.
     */
    fun writeBackupZip(
        outputStream: OutputStream,
        selectedCategories: Set<BackupCategory>,
        appVersion: String,
        appVersionCode: Int,
        createdAtEpochMs: Long,
        databaseSchemaVersion: Int = 2,
        appSettings: BackupAppSettingsData? = null,
        hudConfig: BackupHudConfigData? = null,
        lyricsHistory: BackupHistoryPayload? = null,
        importedLyrics: List<BackupImportedLyricRecord>? = null
    ): BackupManifest {
        if (selectedCategories.isEmpty()) {
            throw BackupException(
                BackupErrorCode.EMPTY_SELECTION,
                "Select at least one category to create a backup."
            )
        }

        val orderedCategories = BackupCategory.ALL_SUPPORTED.filter { it in selectedCategories }
        val entriesToWrite = linkedMapOf<String, ByteArray>()
        val checksums = linkedMapOf<String, String>()
        val summaries = linkedMapOf<String, CategorySummary>()

        if (BackupCategory.APP_SETTINGS in orderedCategories) {
            val data = appSettings ?: throw BackupException(
                BackupErrorCode.WRITE_FAILED,
                "App Settings data was not collected for backup."
            )
            val bytes = gson.toJson(data).toByteArray(StandardCharsets.UTF_8)
            entriesToWrite[SETTINGS_PATH] = bytes
            checksums[SETTINGS_PATH] = sha256Hex(bytes)
            summaries[BackupCategory.APP_SETTINGS.id] = CategorySummary(
                itemCount = APP_SETTINGS_BASE_FIELD_COUNT + data.trackOffsets.size,
                schemaVersion = data.schemaVersion
            )
        }

        if (BackupCategory.HUD_CONFIGURATION in orderedCategories) {
            val data = hudConfig ?: throw BackupException(
                BackupErrorCode.WRITE_FAILED,
                "HUD Configuration data was not collected for backup."
            )
            val bytes = gson.toJson(data).toByteArray(StandardCharsets.UTF_8)
            entriesToWrite[HUD_PATH] = bytes
            checksums[HUD_PATH] = sha256Hex(bytes)
            summaries[BackupCategory.HUD_CONFIGURATION.id] = CategorySummary(
                itemCount = HUD_CONFIG_BASE_FIELD_COUNT + if (data.hudLandscapeX != null) 4 else 0,
                schemaVersion = data.schemaVersion
            )
        }

        if (BackupCategory.LYRICS_HISTORY in orderedCategories) {
            val data = lyricsHistory ?: throw BackupException(
                BackupErrorCode.WRITE_FAILED,
                "Lyrics History data was not collected for backup."
            )
            val bytes = gson.toJson(data).toByteArray(StandardCharsets.UTF_8)
            entriesToWrite[HISTORY_PATH] = bytes
            checksums[HISTORY_PATH] = sha256Hex(bytes)
            summaries[BackupCategory.LYRICS_HISTORY.id] = CategorySummary(
                itemCount = data.entries.size,
                schemaVersion = data.schemaVersion
            )
        }

        if (BackupCategory.IMPORTED_LYRICS in orderedCategories) {
            val records = importedLyrics ?: throw BackupException(
                BackupErrorCode.WRITE_FAILED,
                "Imported Lyrics data was not collected for backup."
            )
            val usedNames = mutableSetOf<String>()
            val indexEntries = mutableListOf<BackupImportedLyricMeta>()

            for (record in records) {
                val safeFileName = buildSafeUniqueLrcFileName(
                    artist = record.meta.artist,
                    title = record.meta.title,
                    fallbackKey = record.meta.key,
                    usedLowercaseNames = usedNames
                )
                val entryPath = "$IMPORTED_DIR_PREFIX$safeFileName"
                validateEntryPathForWrite(entryPath)

                val contentBytes = record.content.toByteArray(StandardCharsets.UTF_8)
                val fileSha = sha256Hex(contentBytes)
                entriesToWrite[entryPath] = contentBytes
                checksums[entryPath] = fileSha

                indexEntries.add(
                    record.meta.copy(
                        filename = safeFileName,
                        sha256 = fileSha
                    )
                )
            }

            val indexPayload = BackupImportedLyricsIndex(
                schemaVersion = 1,
                files = indexEntries
            )
            val indexBytes = gson.toJson(indexPayload).toByteArray(StandardCharsets.UTF_8)
            entriesToWrite[IMPORTED_INDEX_PATH] = indexBytes
            checksums[IMPORTED_INDEX_PATH] = sha256Hex(indexBytes)
            summaries[BackupCategory.IMPORTED_LYRICS.id] = CategorySummary(
                itemCount = indexEntries.size,
                schemaVersion = indexPayload.schemaVersion
            )
        }

        val manifest = BackupManifest(
            formatVersion = BackupManifest.CURRENT_FORMAT_VERSION,
            minSupportedFormatVersion = BackupManifest.MIN_SUPPORTED_FORMAT_VERSION,
            appId = BackupManifest.PORTABLE_APP_ID,
            appVersion = appVersion,
            appVersionCode = appVersionCode,
            createdAtEpochMs = createdAtEpochMs,
            createdAtIso = formatIsoUtc(createdAtEpochMs),
            databaseSchemaVersion = databaseSchemaVersion,
            includedCategories = orderedCategories.map { it.id },
            categorySummaries = summaries,
            checksums = checksums
        )
        val manifestBytes = gson.toJson(manifest).toByteArray(StandardCharsets.UTF_8)

        ZipOutputStream(outputStream).use { zos ->
            zos.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION)
            writeZipEntry(zos, MANIFEST_PATH, manifestBytes, createdAtEpochMs)
            for ((path, data) in entriesToWrite) {
                writeZipEntry(zos, path, data, createdAtEpochMs)
            }
            zos.finish()
            zos.flush()
        }

        return manifest
    }

    /**
     * Reads, validates, and parses a LyricBar backup ZIP from [inputStream] with full
     * security, size, structure, version, and SHA-256 checksum verification.
     */
    fun readAndValidateBackupZip(
        inputStream: InputStream,
        displayFileName: String,
        maxEntries: Int = MAX_ZIP_ENTRIES,
        maxEntryBytes: Long = MAX_SINGLE_ENTRY_UNCOMPRESSED_BYTES,
        maxTotalBytes: Long = MAX_TOTAL_UNCOMPRESSED_BYTES
    ): BackupArchiveInspection {
        val rawFiles = linkedMapOf<String, ByteArray>()
        var entryCount = 0
        var totalBytesRead = 0L

        try {
            ZipInputStream(inputStream).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    entryCount++
                    if (entryCount > maxEntries) {
                        throw BackupException(
                            BackupErrorCode.ARCHIVE_TOO_LARGE,
                            "Backup archive contains too many entries (limit: $maxEntries)."
                        )
                    }

                    val name = entry.name ?: throw BackupException(
                        BackupErrorCode.CORRUPTED_ARCHIVE,
                        "Backup archive contains an unnamed entry."
                    )

                    validateIncomingZipEntryName(name, entry.isDirectory)

                    if (entry.isDirectory) {
                        zis.closeEntry()
                        continue
                    }

                    if (name in rawFiles) {
                        throw BackupException(
                            BackupErrorCode.SECURITY_VIOLATION,
                            "Backup archive contains duplicate entry: $name"
                        )
                    }

                    val bytes = readBoundedEntryBytes(
                        stream = zis,
                        entryName = name,
                        maxEntryBytes = maxEntryBytes,
                        remainingTotalBytes = maxTotalBytes - totalBytesRead
                    )
                    totalBytesRead += bytes.size
                    rawFiles[name] = bytes
                    zis.closeEntry()
                }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException(
                BackupErrorCode.CORRUPTED_ARCHIVE,
                "This file is not a valid or readable ZIP backup archive.",
                e
            )
        }

        if (rawFiles.isEmpty()) {
            throw BackupException(
                BackupErrorCode.CORRUPTED_ARCHIVE,
                "The selected file is empty or not a valid ZIP archive."
            )
        }

        // 1. Validate manifest.json
        val manifestBytes = rawFiles[MANIFEST_PATH]
            ?: throw BackupException(
                BackupErrorCode.INVALID_BACKUP,
                "This ZIP is not a LyricBar backup (missing manifest.json)."
            )

        val manifest = parseJsonStrict<BackupManifest>(
            bytes = manifestBytes,
            entryPath = MANIFEST_PATH
        )

        if (!manifest.appId.equals(BackupManifest.PORTABLE_APP_ID, ignoreCase = true)) {
            throw BackupException(
                BackupErrorCode.INVALID_BACKUP,
                "This backup does not belong to LyricBar."
            )
        }

        if (manifest.formatVersion < BackupManifest.MIN_SUPPORTED_FORMAT_VERSION) {
            throw BackupException(
                BackupErrorCode.UNSUPPORTED_VERSION,
                "Invalid backup format version (${manifest.formatVersion})."
            )
        }

        if (manifest.formatVersion > BackupManifest.MAX_SUPPORTED_FORMAT_VERSION) {
            throw BackupException(
                BackupErrorCode.UNSUPPORTED_VERSION,
                "This backup uses a newer format (v${manifest.formatVersion}) that is not supported by this version of LyricBar. Please update LyricBar to restore it."
            )
        }

        if (manifest.minSupportedFormatVersion > BackupManifest.MAX_SUPPORTED_FORMAT_VERSION) {
            throw BackupException(
                BackupErrorCode.UNSUPPORTED_VERSION,
                "This backup requires format version ${manifest.minSupportedFormatVersion} or newer."
            )
        }

        if (manifest.includedCategories.isEmpty()) {
            throw BackupException(
                BackupErrorCode.NO_SUPPORTED_CATEGORIES,
                "This backup does not declare any restorable categories."
            )
        }

        // 2. Verify SHA-256 checksums for every data entry in the archive
        val dataFilePaths = rawFiles.keys.filter { it != MANIFEST_PATH }.toSet()
        for (filePath in dataFilePaths) {
            if (filePath !in manifest.checksums) {
                throw BackupException(
                    BackupErrorCode.CHECKSUM_MISMATCH,
                    "Backup integrity metadata is missing a checksum for $filePath."
                )
            }
        }
        for ((path, expectedHash) in manifest.checksums) {
            if (!isAllowedArchivePath(path) || path == MANIFEST_PATH) {
                throw BackupException(
                    BackupErrorCode.SECURITY_VIOLATION,
                    "Backup manifest references an unexpected path: $path"
                )
            }
            val fileBytes = rawFiles[path]
                ?: throw BackupException(
                    BackupErrorCode.CORRUPTED_ARCHIVE,
                    "Backup archive is incomplete (missing $path)."
                )
            val actualHash = sha256Hex(fileBytes)
            if (!actualHash.equals(expectedHash.trim(), ignoreCase = true)) {
                throw BackupException(
                    BackupErrorCode.CHECKSUM_MISMATCH,
                    "Backup integrity check failed for $path. The file may be corrupted."
                )
            }
        }

        // 3. Identify supported vs unknown categories
        val availableCategories = mutableListOf<BackupCategory>()
        val unknownCategories = mutableListOf<String>()
        for (rawCat in manifest.includedCategories.distinct()) {
            val mapped = BackupCategory.fromIdOrNull(rawCat)
            if (mapped != null) {
                availableCategories.add(mapped)
            } else if (rawCat.isNotBlank()) {
                unknownCategories.add(rawCat)
            }
        }

        if (availableCategories.isEmpty()) {
            throw BackupException(
                BackupErrorCode.NO_SUPPORTED_CATEGORIES,
                "This backup does not contain any categories supported by this version of LyricBar."
            )
        }

        // 4. Parse and validate each declared supported category payload
        val itemCounts = linkedMapOf<BackupCategory, Int>()

        val appSettings: BackupAppSettingsData? = if (BackupCategory.APP_SETTINGS in availableCategories) {
            val bytes = rawFiles[SETTINGS_PATH]
                ?: throw BackupException(
                    BackupErrorCode.CORRUPTED_ARCHIVE,
                    "Backup declares App Settings, but settings/preferences.json is missing."
                )
            val parsed = parseJsonStrict<BackupAppSettingsData>(bytes, SETTINGS_PATH)
            itemCounts[BackupCategory.APP_SETTINGS] =
                manifest.categorySummaries[BackupCategory.APP_SETTINGS.id]?.itemCount
                    ?: (APP_SETTINGS_BASE_FIELD_COUNT + parsed.trackOffsets.size)
            parsed
        } else null

        val hudConfig: BackupHudConfigData? = if (BackupCategory.HUD_CONFIGURATION in availableCategories) {
            val bytes = rawFiles[HUD_PATH]
                ?: throw BackupException(
                    BackupErrorCode.CORRUPTED_ARCHIVE,
                    "Backup declares HUD Configuration, but hud/configuration.json is missing."
                )
            val parsed = parseJsonStrict<BackupHudConfigData>(bytes, HUD_PATH)
            itemCounts[BackupCategory.HUD_CONFIGURATION] =
                manifest.categorySummaries[BackupCategory.HUD_CONFIGURATION.id]?.itemCount
                    ?: (HUD_CONFIG_BASE_FIELD_COUNT + if (parsed.hudLandscapeX != null) 4 else 0)
            parsed
        } else null

        val lyricsHistory: BackupHistoryPayload? = if (BackupCategory.LYRICS_HISTORY in availableCategories) {
            val bytes = rawFiles[HISTORY_PATH]
                ?: throw BackupException(
                    BackupErrorCode.CORRUPTED_ARCHIVE,
                    "Backup declares Lyrics History, but lyrics/history.json is missing."
                )
            val parsed = parseJsonStrict<BackupHistoryPayload>(bytes, HISTORY_PATH)
            val safeEntries = (parsed.entries ?: emptyList()).filter { entry ->
                entry.key.isNotBlank() && entry.title.isNotBlank() && entry.artist.isNotBlank()
            }
            val normalizedPayload = parsed.copy(entries = safeEntries)
            itemCounts[BackupCategory.LYRICS_HISTORY] = safeEntries.size
            normalizedPayload
        } else null

        val importedLyrics: List<BackupImportedLyricRecord>? = if (BackupCategory.IMPORTED_LYRICS in availableCategories) {
            val indexBytes = rawFiles[IMPORTED_INDEX_PATH]
                ?: throw BackupException(
                    BackupErrorCode.CORRUPTED_ARCHIVE,
                    "Backup declares Imported Lyrics, but imported_lyrics/index.json is missing."
                )
            val index = parseJsonStrict<BackupImportedLyricsIndex>(indexBytes, IMPORTED_INDEX_PATH)
            val metaFiles = index.files ?: emptyList()
            val records = mutableListOf<BackupImportedLyricRecord>()
            val seenPaths = mutableSetOf<String>()

            for (meta in metaFiles) {
                if (meta.key.isBlank() || meta.filename.isBlank()) {
                    throw BackupException(
                        BackupErrorCode.INVALID_BACKUP,
                        "Imported lyrics index contains an entry with missing key or filename."
                    )
                }
                if (!isSafeSingleLrcFilename(meta.filename)) {
                    throw BackupException(
                        BackupErrorCode.SECURITY_VIOLATION,
                        "Imported lyrics index contains an unsafe filename."
                    )
                }
                val entryPath = "$IMPORTED_DIR_PREFIX${meta.filename}"
                if (!seenPaths.add(entryPath.lowercase(Locale.US))) {
                    throw BackupException(
                        BackupErrorCode.INVALID_BACKUP,
                        "Imported lyrics index contains duplicate filename references."
                    )
                }
                val lrcBytes = rawFiles[entryPath]
                    ?: throw BackupException(
                        BackupErrorCode.CORRUPTED_ARCHIVE,
                        "Backup is missing imported lyric file: ${meta.filename}"
                    )
                if (lrcBytes.size > LrcImporter.MAX_LRC_FILE_SIZE_BYTES) {
                    throw BackupException(
                        BackupErrorCode.ARCHIVE_TOO_LARGE,
                        "Imported lyric file exceeds maximum size limit (1 MB)."
                    )
                }
                if (meta.sha256.isNotBlank()) {
                    val actualSha = sha256Hex(lrcBytes)
                    if (!actualSha.equals(meta.sha256.trim(), ignoreCase = true)) {
                        throw BackupException(
                            BackupErrorCode.CHECKSUM_MISMATCH,
                            "Checksum mismatch for imported lyric file: ${meta.filename}"
                        )
                    }
                }
                val decoded = LrcImporter.decodeBytesSafely(lrcBytes)
                if (decoded.isBlank() || decoded.contains('\u0000') || LrcParser.isTtml(decoded)) {
                    throw BackupException(
                        BackupErrorCode.INVALID_BACKUP,
                        "Imported lyric file ${meta.filename} is empty or contains unsupported content."
                    )
                }
                records.add(BackupImportedLyricRecord(meta = meta, content = decoded))
            }
            itemCounts[BackupCategory.IMPORTED_LYRICS] = records.size
            records
        } else null

        val payload = BackupArchivePayload(
            manifest = manifest,
            appSettings = appSettings,
            hudConfig = hudConfig,
            lyricsHistory = lyricsHistory,
            importedLyrics = importedLyrics
        )

        return BackupArchiveInspection(
            displayFileName = displayFileName,
            payload = payload,
            availableCategories = availableCategories,
            ignoredUnknownCategories = unknownCategories,
            categoryItemCounts = itemCounts
        )
    }

    private fun writeZipEntry(
        zos: ZipOutputStream,
        path: String,
        bytes: ByteArray,
        timestampMs: Long
    ) {
        val entry = ZipEntry(path).apply {
            time = timestampMs
        }
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun validateEntryPathForWrite(path: String) {
        if (!isAllowedArchivePath(path)) {
            throw BackupException(
                BackupErrorCode.SECURITY_VIOLATION,
                "Refusing to write unsafe archive entry path."
            )
        }
    }

    internal fun validateIncomingZipEntryName(name: String, isDirectory: Boolean) {
        if (name.isBlank() ||
            name.contains('\u0000') ||
            name.startsWith("/") ||
            name.startsWith("\\") ||
            name.contains("\\") ||
            name.contains("..")
        ) {
            throw BackupException(
                BackupErrorCode.SECURITY_VIOLATION,
                "Rejected backup archive containing unsafe path entry."
            )
        }
        val normalized = name.trimEnd('/')
        if (isDirectory) {
            val allowedDirs = setOf("settings", "hud", "lyrics", "imported_lyrics")
            if (normalized !in allowedDirs) {
                throw BackupException(
                    BackupErrorCode.INVALID_BACKUP,
                    "Unexpected directory in backup archive: $name"
                )
            }
            return
        }
        if (!isAllowedArchivePath(name)) {
            throw BackupException(
                BackupErrorCode.INVALID_BACKUP,
                "Unexpected file in backup archive: $name"
            )
        }
    }

    private fun isAllowedArchivePath(path: String): Boolean {
        if (path == MANIFEST_PATH ||
            path == SETTINGS_PATH ||
            path == HUD_PATH ||
            path == HISTORY_PATH ||
            path == IMPORTED_INDEX_PATH
        ) {
            return true
        }
        if (path.startsWith(IMPORTED_DIR_PREFIX)) {
            val leaf = path.removePrefix(IMPORTED_DIR_PREFIX)
            return isSafeSingleLrcFilename(leaf)
        }
        return false
    }

    private fun isSafeSingleLrcFilename(fileName: String): Boolean {
        if (fileName.isBlank() || fileName.length > 128) return false
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..") || fileName.contains('\u0000')) {
            return false
        }
        if (fileName.startsWith(".")) return false
        return fileName.endsWith(".lrc", ignoreCase = true) || fileName.endsWith(".txt", ignoreCase = true)
    }

    private fun readBoundedEntryBytes(
        stream: InputStream,
        entryName: String,
        maxEntryBytes: Long,
        remainingTotalBytes: Long
    ): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var entryRead = 0L
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) break
            entryRead += count
            if (entryRead > maxEntryBytes) {
                throw BackupException(
                    BackupErrorCode.ARCHIVE_TOO_LARGE,
                    "Backup entry '$entryName' exceeds the maximum allowed size."
                )
            }
            if (entryRead > remainingTotalBytes) {
                throw BackupException(
                    BackupErrorCode.ARCHIVE_TOO_LARGE,
                    "Backup archive exceeds the maximum allowed uncompressed size."
                )
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private inline fun <reified T> parseJsonStrict(bytes: ByteArray, entryPath: String): T {
        val text = String(bytes, StandardCharsets.UTF_8).removePrefix("\uFEFF").trim()
        if (text.isBlank()) {
            throw BackupException(
                BackupErrorCode.CORRUPTED_ARCHIVE,
                "Backup entry '$entryPath' is empty."
            )
        }
        return try {
            gson.fromJson(text, T::class.java)
                ?: throw BackupException(
                    BackupErrorCode.CORRUPTED_ARCHIVE,
                    "Backup entry '$entryPath' could not be parsed."
                )
        } catch (e: JsonSyntaxException) {
            throw BackupException(
                BackupErrorCode.CORRUPTED_ARCHIVE,
                "Backup entry '$entryPath' contains malformed JSON.",
                e
            )
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException(
                BackupErrorCode.CORRUPTED_ARCHIVE,
                "Failed to read backup entry '$entryPath'.",
                e
            )
        }
    }

    private const val APP_SETTINGS_BASE_FIELD_COUNT = 21
    private const val HUD_CONFIG_BASE_FIELD_COUNT = 20
}
