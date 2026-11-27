package com.example.data.fonts

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.backup.BackupCustomFontMeta
import com.example.data.backup.BackupCustomFontRecord
import com.example.data.backup.CustomFontBackupRestoreSummary
import com.example.data.preferences.UserPreferencesRepository
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages imported custom TTF/OTF fonts in app-private storage, validates integrity/security,
 * persists registry metadata, deduplicates by SHA-256 content hash, and caches Typefaces.
 */
class CustomFontRepository(
    private val fontsDir: File,
    private val tempDir: File,
    private val preferencesRepository: UserPreferencesRepository,
    private val typefaceVerifier: (File) -> Boolean = CustomFontValidator::verifyAndroidTypeface,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val registryMutex = Mutex()
    private val registryFile: File get() = File(fontsDir, REGISTRY_FILENAME)

    private val _customFontsFlow = MutableStateFlow<List<CustomFontRecord>>(emptyList())
    val customFontsFlow: StateFlow<List<CustomFontRecord>> = _customFontsFlow.asStateFlow()

    private val normalTypefaceCache = ConcurrentHashMap<String, Typeface>()
    private val boldTypefaceCache = ConcurrentHashMap<String, Typeface>()

    init {
        ensureDirectories()
        cleanupTempDir()
        _customFontsFlow.value = readRegistryFromDisk().filter { File(fontsDir, it.storedFileName).isFile }
    }

    private fun ensureDirectories() {
        if (!fontsDir.exists()) fontsDir.mkdirs()
        if (!tempDir.exists()) tempDir.mkdirs()
    }

    private fun cleanupTempDir() {
        try {
            tempDir.listFiles()?.forEach { child ->
                if (child.isDirectory) child.deleteRecursively() else child.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun resolveFontFile(record: CustomFontRecord): File = File(fontsDir, record.storedFileName)

    fun resolveValidFontFile(fontId: String?): File? {
        if (fontId.isNullOrBlank()) return null
        val record = _customFontsFlow.value.firstOrNull { it.id == fontId } ?: return null
        val file = resolveFontFile(record)
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    fun getRecordById(fontId: String?): CustomFontRecord? {
        if (fontId.isNullOrBlank()) return null
        return _customFontsFlow.value.firstOrNull { it.id == fontId }
    }

    fun getCachedTypeface(record: CustomFontRecord): Typeface? {
        val file = resolveFontFile(record)
        if (!file.isFile || file.length() <= 0L) return null
        return normalTypefaceCache.getOrPut(record.id) {
            try {
                Typeface.createFromFile(file) ?: return null
            } catch (_: Throwable) {
                return null
            }
        }
    }

    fun getCachedBoldTypeface(record: CustomFontRecord): Typeface? {
        val base = getCachedTypeface(record) ?: return null
        return boldTypefaceCache.getOrPut(record.id) {
            try {
                Typeface.create(base, Typeface.BOLD)
            } catch (_: Throwable) {
                base
            }
        }
    }

    fun evictTypefaceCache(fontId: String) {
        normalTypefaceCache.remove(fontId)
        boldTypefaceCache.remove(fontId)
    }

    fun clearTypefaceCache() {
        normalTypefaceCache.clear()
        boldTypefaceCache.clear()
    }

    /**
     * Reloads the registry from disk on IO dispatcher, pruning any missing font files cleanly.
     */
    suspend fun refreshRegistry(): List<CustomFontRecord> = withContext(Dispatchers.IO) {
        registryMutex.withLock {
            ensureDirectories()
            val loaded = readRegistryFromDisk()
            val valid = loaded.filter { File(fontsDir, it.storedFileName).isFile }
            if (valid.size != loaded.size) {
                writeRegistryToDisk(valid)
            }
            _customFontsFlow.value = valid
            valid
        }
    }

    /**
     * Imports a single TTF/OTF font selected via Android's Storage Access Framework document picker.
     */
    suspend fun importFontFromUri(context: Context, uri: Uri): SingleFontImportResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var displayNameFromProvider: String? = null
        var reportedSize: Long? = null

        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        displayNameFromProvider = cursor.getString(nameIdx)
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        reportedSize = cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (_: Exception) {
        }

        val fallbackName = displayNameFromProvider
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "custom_font"
        val ext = CustomFontValidator.extractSupportedExtension(fallbackName)
            ?: return@withContext SingleFontImportResult.Failure(
                reason = "Unsupported file format. Please select a .ttf or .otf font file.",
                errorCode = FontImportErrorCode.UNSUPPORTED_EXTENSION
            )

        if (reportedSize != null && reportedSize!! > CustomFontLimits.MAX_FONT_FILE_BYTES) {
            return@withContext SingleFontImportResult.Failure(
                reason = "Font file is too large. Maximum allowed size is ${CustomFontLimits.MAX_FONT_FILE_BYTES / (1024 * 1024)} MB.",
                errorCode = FontImportErrorCode.FILE_TOO_LARGE
            )
        }

        val inputStream = try {
            resolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        } ?: return@withContext SingleFontImportResult.Failure(
            reason = "Unable to open the selected font file.",
            errorCode = FontImportErrorCode.IO_ERROR
        )

        inputStream.use { stream ->
            importFontFromStream(
                inputStream = stream,
                originalFileName = CustomFontValidator.sanitizeOriginalFileName(fallbackName, ext)
            )
        }
    }

    /**
     * Streams, validates, deduplicates, and registers a single font from [inputStream].
     * Always writes into an isolated temporary file first and cleans up temporary files in `finally`.
     */
    suspend fun importFontFromStream(
        inputStream: InputStream,
        originalFileName: String,
        maxFontBytes: Long = CustomFontLimits.MAX_FONT_FILE_BYTES
    ): SingleFontImportResult = withContext(Dispatchers.IO) {
        val ext = CustomFontValidator.extractSupportedExtension(originalFileName)
            ?: return@withContext SingleFontImportResult.Failure(
                reason = "Unsupported font extension. Only .ttf and .otf files are supported.",
                errorCode = FontImportErrorCode.UNSUPPORTED_EXTENSION
            )

        ensureDirectories()
        val tempFile = File(tempDir, "import_${nowProvider()}_${System.nanoTime()}.tmp")

        try {
            val streamResult = copyStreamBoundedWithSha256(
                inputStream = inputStream,
                destFile = tempFile,
                maxBytes = maxFontBytes
            )

            if (streamResult.exceededLimit) {
                return@withContext SingleFontImportResult.Failure(
                    reason = "Font file exceeds the ${maxFontBytes / (1024 * 1024)} MB size limit.",
                    errorCode = FontImportErrorCode.FILE_TOO_LARGE
                )
            }
            if (streamResult.byteCount <= 0L) {
                return@withContext SingleFontImportResult.Failure(
                    reason = "Selected font file is empty.",
                    errorCode = FontImportErrorCode.EMPTY_FILE
                )
            }

            val header = CustomFontValidator.readFileHeader(tempFile)
            if (header == null || !CustomFontValidator.hasValidSfntHeader(header)) {
                return@withContext SingleFontImportResult.Failure(
                    reason = "'$originalFileName' is not a valid TrueType or OpenType font file.",
                    errorCode = FontImportErrorCode.CORRUPTED_OR_INVALID_FONT
                )
            }

            if (!typefaceVerifier(tempFile)) {
                return@withContext SingleFontImportResult.Failure(
                    reason = "'$originalFileName' is corrupted or unsupported by Android.",
                    errorCode = FontImportErrorCode.CORRUPTED_OR_INVALID_FONT
                )
            }

            registerValidatedTempFile(
                tempFile = tempFile,
                sha256 = streamResult.sha256,
                byteCount = streamResult.byteCount,
                originalFileName = CustomFontValidator.sanitizeOriginalFileName(originalFileName, ext),
                extension = ext
            )
        } catch (e: IOException) {
            SingleFontImportResult.Failure(
                reason = "Failed to read or copy the font file: ${e.localizedMessage ?: "I/O error"}",
                errorCode = FontImportErrorCode.IO_ERROR
            )
        } finally {
            try {
                if (tempFile.exists()) tempFile.delete()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Registers a pre-validated temp font file under [registryMutex] with SHA-256 deduplication.
     */
    internal suspend fun registerValidatedTempFile(
        tempFile: File,
        sha256: String,
        byteCount: Long,
        originalFileName: String,
        extension: String,
        preferredId: String? = null,
        preferredDisplayName: String? = null
    ): SingleFontImportResult = registryMutex.withLock {
        ensureDirectories()
        val current = readRegistryFromDisk().filter { File(fontsDir, it.storedFileName).isFile }.toMutableList()

        val duplicate = current.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) }
        if (duplicate != null) {
            _customFontsFlow.value = current
            return@withLock SingleFontImportResult.Duplicate(
                existingRecord = duplicate,
                message = "Font already imported as '${duplicate.displayName}'."
            )
        }

        val id = resolveUniqueId(preferredId, sha256, current)
        val storedFileName = "$id.$extension"
        val finalFile = File(fontsDir, storedFileName)

        copyFileAtomic(tempFile, finalFile)
        if (!finalFile.isFile || finalFile.length() != byteCount) {
            finalFile.delete()
            return@withLock SingleFontImportResult.Failure(
                reason = "Failed to finish copying '$originalFileName' into app storage.",
                errorCode = FontImportErrorCode.IO_ERROR
            )
        }

        val baseDisplayName = preferredDisplayName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: CustomFontValidator.sanitizeDisplayName(originalFileName)
        val uniqueDisplayName = buildUniqueDisplayName(
            baseName = baseDisplayName,
            existing = current
        )
        val record = CustomFontRecord(
            id = id,
            displayName = uniqueDisplayName,
            originalFileName = originalFileName,
            extension = extension,
            storedFileName = storedFileName,
            sha256 = sha256,
            fileSizeBytes = byteCount,
            importedAtMs = nowProvider()
        )
        current.add(record)
        writeRegistryToDisk(current)
        _customFontsFlow.value = current
        SingleFontImportResult.Success(record)
    }

    /**
     * Deletes an imported custom font from registry & disk, switching active selection
     * back to the default built-in font if this custom font was currently active.
     */
    suspend fun deleteCustomFont(fontId: String): CustomFontDeleteResult {
        val batch = deleteCustomFonts(listOf(fontId))
        val removed = batch.deletedRecords.firstOrNull()
            ?: return CustomFontDeleteResult.NotFound
        return CustomFontDeleteResult.Deleted(
            removedRecord = removed,
            resetSelectionToDefault = batch.resetSelectionToDefault
        )
    }

    /**
     * Deletes multiple imported custom fonts atomically under [registryMutex], updating
     * the persisted registry once and resetting active custom font selection if any
     * deleted font was active.
     */
    suspend fun deleteCustomFonts(fontIds: Collection<String>): CustomFontBatchDeleteResult = withContext(Dispatchers.IO) {
        val targetIds = fontIds.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        if (targetIds.isEmpty()) {
            return@withContext CustomFontBatchDeleteResult(
                deletedRecords = emptyList(),
                notFoundIds = emptyList(),
                resetSelectionToDefault = false
            )
        }

        val removedRecords = registryMutex.withLock {
            ensureDirectories()
            val current = readRegistryFromDisk()
            val removed = current.filter { it.id in targetIds }
            if (removed.isEmpty()) {
                return@withLock emptyList()
            }
            removed.forEach { record ->
                val file = File(fontsDir, record.storedFileName)
                if (file.exists()) {
                    file.delete()
                }
                evictTypefaceCache(record.id)
            }
            val remaining = current
                .filter { it.id !in targetIds }
                .filter { File(fontsDir, it.storedFileName).isFile }
            writeRegistryToDisk(remaining)
            _customFontsFlow.value = remaining
            removed
        }

        val removedIdSet = removedRecords.map { it.id }.toSet()
        val notFoundIds = targetIds.filter { it !in removedIdSet }
        val resetToDefault = if (removedIdSet.isNotEmpty()) {
            preferencesRepository.clearCustomFontsIfSelected(removedIdSet)
        } else {
            false
        }

        CustomFontBatchDeleteResult(
            deletedRecords = removedRecords,
            notFoundIds = notFoundIds,
            resetSelectionToDefault = resetToDefault
        )
    }

    suspend fun getFontsCount(): Int = withContext(Dispatchers.IO) {
        registryMutex.withLock {
            ensureDirectories()
            val valid = readRegistryFromDisk().filter { File(fontsDir, it.storedFileName).isFile }
            _customFontsFlow.value = valid
            valid.size
        }
    }

    suspend fun resolveValidFontId(fontId: String?): String? = withContext(Dispatchers.IO) {
        if (fontId.isNullOrBlank()) return@withContext null
        registryMutex.withLock {
            val valid = readRegistryFromDisk().filter { File(fontsDir, it.storedFileName).isFile }
            _customFontsFlow.value = valid
            val record = valid.firstOrNull { it.id == fontId } ?: return@withLock null
            val file = resolveFontFile(record)
            record.id.takeIf { file.isFile && file.length() > 0L }
        }
    }

    suspend fun exportFontsForBackup(): List<BackupCustomFontRecord> = withContext(Dispatchers.IO) {
        registryMutex.withLock {
            ensureDirectories()
            val valid = readRegistryFromDisk().filter { File(fontsDir, it.storedFileName).isFile }
            _customFontsFlow.value = valid
            valid.mapNotNull { record ->
                val file = resolveFontFile(record)
                if (!file.isFile || file.length() <= 0L || file.length() > CustomFontLimits.MAX_FONT_FILE_BYTES) {
                    return@mapNotNull null
                }
                val bytes = try {
                    file.readBytes()
                } catch (_: IOException) {
                    return@mapNotNull null
                }
                if (bytes.isEmpty()) return@mapNotNull null
                val sha = record.sha256.ifBlank { sha256Hex(bytes) }
                BackupCustomFontRecord(
                    meta = BackupCustomFontMeta(
                        id = record.id,
                        displayName = record.displayName,
                        originalFileName = record.originalFileName,
                        extension = record.extension,
                        storedFileName = record.storedFileName,
                        sha256 = sha,
                        fileSizeBytes = bytes.size.toLong(),
                        importedAtMs = record.importedAtMs
                    ),
                    fontBytes = bytes
                )
            }
        }
    }

    suspend fun restoreFontsFromBackup(
        records: List<BackupCustomFontRecord>
    ): CustomFontBackupRestoreSummary = withContext(Dispatchers.IO) {
        if (records.isEmpty()) {
            return@withContext CustomFontBackupRestoreSummary()
        }
        ensureDirectories()

        var restoredCount = 0
        var skippedDuplicates = 0
        var skippedInvalid = 0
        val idMapping = linkedMapOf<String, String>()

        for ((index, record) in records.withIndex()) {
            val bytes = record.fontBytes
            if (bytes.isEmpty() || bytes.size > CustomFontLimits.MAX_FONT_FILE_BYTES) {
                skippedInvalid++
                continue
            }

            val ext = record.meta.extension.lowercase(Locale.US)
                .takeIf { it in CustomFontValidator.SUPPORTED_EXTENSIONS }
                ?: CustomFontValidator.extractSupportedExtension(record.meta.originalFileName)
                ?: CustomFontValidator.extractSupportedExtension(record.meta.storedFileName)
                ?: "ttf"

            val safeOriginalName = CustomFontValidator.sanitizeOriginalFileName(
                record.meta.originalFileName.ifBlank {
                    record.meta.displayName.ifBlank { "custom_font_${index + 1}.$ext" }
                },
                ext
            )

            val tempFile = File(tempDir, "restore_${System.nanoTime()}_$index.$ext")
            try {
                val streamResult = java.io.ByteArrayInputStream(bytes).use { input ->
                    copyStreamBoundedWithSha256(input, tempFile, CustomFontLimits.MAX_FONT_FILE_BYTES)
                }
                if (streamResult.exceededLimit || streamResult.byteCount <= 0L) {
                    skippedInvalid++
                    continue
                }
                if (record.meta.sha256.isNotBlank() &&
                    !streamResult.sha256.equals(record.meta.sha256.trim(), ignoreCase = true)
                ) {
                    skippedInvalid++
                    continue
                }
                val header = CustomFontValidator.readFileHeader(tempFile)
                if (header == null || !CustomFontValidator.hasValidSfntHeader(header) || !typefaceVerifier(tempFile)) {
                    skippedInvalid++
                    continue
                }

                when (val outcome = registerValidatedTempFile(
                    tempFile = tempFile,
                    sha256 = streamResult.sha256,
                    byteCount = streamResult.byteCount,
                    originalFileName = safeOriginalName,
                    extension = ext,
                    preferredId = record.meta.id.takeIf { it.isNotBlank() },
                    preferredDisplayName = record.meta.displayName
                )) {
                    is SingleFontImportResult.Success -> {
                        restoredCount++
                        if (record.meta.id.isNotBlank()) {
                            idMapping[record.meta.id] = outcome.record.id
                        }
                    }
                    is SingleFontImportResult.Duplicate -> {
                        skippedDuplicates++
                        if (record.meta.id.isNotBlank()) {
                            idMapping[record.meta.id] = outcome.existingRecord.id
                        }
                    }
                    is SingleFontImportResult.Failure,
                    SingleFontImportResult.Cancelled -> {
                        skippedInvalid++
                    }
                }
            } catch (_: Exception) {
                skippedInvalid++
            } finally {
                try {
                    if (tempFile.exists()) tempFile.delete()
                } catch (_: Exception) {
                }
            }
        }

        CustomFontBackupRestoreSummary(
            restoredCount = restoredCount,
            skippedDuplicates = skippedDuplicates,
            skippedInvalid = skippedInvalid,
            idMapping = idMapping
        )
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    private fun resolveUniqueId(preferredId: String?, sha256: String, existing: List<CustomFontRecord>): String {
        val candidateBase = preferredId?.takeIf { isValidFontId(it) } ?: "cf_${sha256.take(16)}"
        if (existing.none { it.id == candidateBase }) return candidateBase
        var idx = 2
        while (true) {
            val candidate = "${candidateBase}_$idx"
            if (existing.none { it.id == candidate }) return candidate
            idx++
        }
    }

    private fun isValidFontId(id: String): Boolean {
        return id.length in 3..64 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }
    }

    private fun buildUniqueDisplayName(baseName: String, existing: List<CustomFontRecord>): String {
        val used = existing.map { it.displayName.lowercase(Locale.US) }.toSet()
        if (baseName.lowercase(Locale.US) !in used) return baseName
        var idx = 2
        while (true) {
            val candidate = "$baseName ($idx)"
            if (candidate.lowercase(Locale.US) !in used) return candidate
            idx++
        }
    }

    private fun copyFileAtomic(source: File, target: File) {
        val stage = File(fontsDir, "${target.name}.stage")
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(stage).use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (target.exists()) target.delete()
            if (!stage.renameTo(target)) {
                stage.copyTo(target, overwrite = true)
                stage.delete()
            }
        } catch (e: Exception) {
            stage.delete()
            throw e
        }
    }

    private fun readRegistryFromDisk(): List<CustomFontRecord> {
        val file = registryFile
        if (!file.isFile) return emptyList()
        return try {
            val json = file.readText(StandardCharsets.UTF_8).trim()
            if (json.isBlank()) return emptyList()
            val payload = gson.fromJson(json, CustomFontRegistryPayload::class.java)
            payload?.fonts ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeRegistryToDisk(records: List<CustomFontRecord>) {
        val payload = CustomFontRegistryPayload(schemaVersion = 1, fonts = records)
        val jsonBytes = gson.toJson(payload).toByteArray(StandardCharsets.UTF_8)
        val stage = File(fontsDir, "$REGISTRY_FILENAME.tmp")
        try {
            FileOutputStream(stage).use { out ->
                out.write(jsonBytes)
                out.fd.sync()
            }
            if (registryFile.exists()) registryFile.delete()
            if (!stage.renameTo(registryFile)) {
                stage.copyTo(registryFile, overwrite = true)
                stage.delete()
            }
        } catch (e: Exception) {
            stage.delete()
            throw e
        }
    }

    internal data class StreamCopyResult(
        val byteCount: Long,
        val sha256: String,
        val exceededLimit: Boolean
    )

    internal fun copyStreamBoundedWithSha256(
        inputStream: InputStream,
        destFile: File,
        maxBytes: Long
    ): StreamCopyResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var total = 0L
        FileOutputStream(destFile).use { out ->
            while (true) {
                val read = inputStream.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxBytes) {
                    return StreamCopyResult(byteCount = total, sha256 = "", exceededLimit = true)
                }
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
            }
            out.fd.sync()
        }
        val hex = digest.digest().joinToString("") { String.format(Locale.US, "%02x", it) }
        return StreamCopyResult(byteCount = total, sha256 = hex, exceededLimit = false)
    }

    companion object {
        private const val REGISTRY_FILENAME = "registry.json"

        @Volatile
        private var INSTANCE: CustomFontRepository? = null

        fun getInstance(context: Context): CustomFontRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext
                    CustomFontRepository(
                        fontsDir = File(appContext.filesDir, "custom_fonts"),
                        tempDir = File(appContext.cacheDir, "custom_fonts_tmp"),
                        preferencesRepository = UserPreferencesRepository(appContext)
                    ).also { INSTANCE = it }
                }
            }
        }
    }
}
