package com.example.data.fonts

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Untrusted ZIP archive scanner and selective custom-font importer.
 *
 * Security & resource invariants:
 * - Never blindly extracts archives or constructs destination paths from ZIP entry names.
 * - Rejects ZIP Slip / path traversal (`..`, backslashes, drive prefixes, leading `/`, null bytes).
 * - Enforces hard streaming byte limits for compressed archive size, per-font size,
 *   total extracted font bytes, discovered font count, entry scan count, and decompression ratios.
 * - Cleans up staged session temporary files on success, failure, and cancellation.
 */
class CustomFontZipImporter(
    private val repository: CustomFontRepository,
    private val stagingRootDir: File,
    private val typefaceVerifier: (File) -> Boolean = CustomFontValidator::verifyAndroidTypeface,
    private val maxArchiveBytes: Long = CustomFontLimits.MAX_ZIP_ARCHIVE_BYTES,
    private val maxFontBytes: Long = CustomFontLimits.MAX_FONT_FILE_BYTES,
    private val maxTotalExtractedFontBytes: Long = CustomFontLimits.MAX_TOTAL_EXTRACTED_FONT_BYTES,
    private val maxDiscoveredFonts: Int = CustomFontLimits.MAX_DISCOVERED_FONTS,
    private val maxScanEntries: Int = CustomFontLimits.MAX_ZIP_SCAN_ENTRIES
) {

    suspend fun scanZipFromUri(
        context: Context,
        uri: Uri,
        onProgress: ((ZipScanProgress) -> Unit)? = null
    ): ZipFontScanResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var archiveName = "archive.zip"
        var reportedSize: Long? = null

        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        archiveName = cursor.getString(nameIdx)?.takeIf { it.isNotBlank() } ?: archiveName
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        reportedSize = cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (reportedSize != null && reportedSize!! > maxArchiveBytes) {
            return@withContext ZipFontScanResult.Failure(
                reason = "ZIP archive is too large. Maximum allowed archive size is ${maxArchiveBytes / (1024 * 1024)} MB.",
                errorCode = FontImportErrorCode.ARCHIVE_TOO_LARGE
            )
        }

        val inputStream = try {
            resolver.openInputStream(uri)
        } catch (_: Exception) {
            null
        } ?: return@withContext ZipFontScanResult.Failure(
            reason = "Unable to open the selected ZIP archive.",
            errorCode = FontImportErrorCode.IO_ERROR
        )

        inputStream.use { stream ->
            scanZipStream(
                inputStream = stream,
                archiveName = CustomFontValidator.sanitizeOriginalFileName(archiveName, "zip"),
                onProgress = onProgress
            )
        }
    }

    suspend fun scanZipStream(
        inputStream: InputStream,
        archiveName: String = "fonts.zip",
        onProgress: ((ZipScanProgress) -> Unit)? = null
    ): ZipFontScanResult = withContext(Dispatchers.IO) {
        if (!stagingRootDir.exists()) stagingRootDir.mkdirs()
        val sessionId = "zip_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val sessionDir = File(stagingRootDir, sessionId).apply { mkdirs() }

        val discovered = mutableListOf<DiscoveredZipFontEntry>()
        val existingHashes = repository.customFontsFlow.value.map { it.sha256.lowercase(Locale.US) }.toSet()
        val seenArchiveHashes = mutableSetOf<String>()

        var totalScanEntries = 0
        var ignoredEntries = 0
        var totalExtractedBytes = 0L
        var limitReachedMsg: String? = null
        var sawAnyZipEntry = false

        val archiveLimiter = BoundedArchiveInputStream(inputStream, maxArchiveBytes)

        try {
            ZipInputStream(archiveLimiter).use { zis ->
                while (true) {
                    currentCoroutineContext().ensureActive()

                    val entry = zis.nextEntry ?: break
                    sawAnyZipEntry = true
                    totalScanEntries++
                    if (totalScanEntries > maxScanEntries) {
                        throw ZipSecurityOrLimitException(
                            "Archive exceeds the maximum scanned entry limit ($maxScanEntries).",
                            FontImportErrorCode.ARCHIVE_TOO_LARGE
                        )
                    }

                    val rawName = entry.name ?: throw ZipSecurityOrLimitException(
                        "Archive contains an unnamed entry.",
                        FontImportErrorCode.ARCHIVE_CORRUPTED
                    )

                    validateEntryPathSafety(rawName)

                    if (entry.isDirectory || shouldIgnoreMetadataOrNonFont(rawName)) {
                        ignoredEntries++
                        zis.closeEntry()
                        continue
                    }

                    val ext = CustomFontValidator.extractSupportedExtension(rawName)
                    if (ext == null) {
                        // Ignore images, text files, nested .zip archives, etc.
                        ignoredEntries++
                        zis.closeEntry()
                        continue
                    }

                    if (discovered.size >= maxDiscoveredFonts) {
                        limitReachedMsg = "Stopped after discovering the maximum of $maxDiscoveredFonts fonts in one archive."
                        zis.closeEntry()
                        break
                    }

                    val displayPath = rawName.trimStart('/')
                    val leafFileName = CustomFontValidator.sanitizeOriginalFileName(rawName, ext)
                    onProgress?.invoke(
                        ZipScanProgress(
                            entriesScanned = totalScanEntries,
                            fontsFound = discovered.size + 1,
                            currentEntryName = leafFileName
                        )
                    )

                    val entryIdx = discovered.size
                    val stagedFileName = "candidate_${entryIdx}.$ext"
                    val stagedFile = File(sessionDir, stagedFileName)

                    val copyOutcome = copyZipEntryBounded(
                        zipStream = zis,
                        destFile = stagedFile,
                        compressedSize = entry.compressedSize,
                        remainingTotalBytes = maxTotalExtractedFontBytes - totalExtractedBytes
                    )

                    if (copyOutcome.suspiciousRatio) {
                        stagedFile.delete()
                        throw ZipSecurityOrLimitException(
                            "Archive entry '$leafFileName' failed decompression-bomb safety checks.",
                            FontImportErrorCode.SECURITY_VIOLATION
                        )
                    }

                    if (copyOutcome.exceededTotalArchiveLimit) {
                        stagedFile.delete()
                        throw ZipSecurityOrLimitException(
                            "Extracted font data exceeds the ${maxTotalExtractedFontBytes / (1024 * 1024)} MB total limit.",
                            FontImportErrorCode.ARCHIVE_TOO_LARGE
                        )
                    }

                    totalExtractedBytes += copyOutcome.bytesWritten.coerceAtMost(maxFontBytes)

                    val candidateEntry = when {
                        copyOutcome.exceededSingleEntryLimit -> {
                            stagedFile.delete()
                            DiscoveredZipFontEntry(
                                entryId = "zip_$entryIdx",
                                entryDisplayPath = displayPath,
                                fileName = leafFileName,
                                extension = ext,
                                stagedTempFileName = null,
                                fileSizeBytes = copyOutcome.bytesWritten,
                                sha256 = "",
                                status = ZipFontValidationStatus.TOO_LARGE,
                                statusMessage = "Exceeds ${maxFontBytes / (1024 * 1024)} MB limit"
                            )
                        }
                        copyOutcome.bytesWritten <= 0L -> {
                            stagedFile.delete()
                            DiscoveredZipFontEntry(
                                entryId = "zip_$entryIdx",
                                entryDisplayPath = displayPath,
                                fileName = leafFileName,
                                extension = ext,
                                stagedTempFileName = null,
                                fileSizeBytes = 0L,
                                sha256 = "",
                                status = ZipFontValidationStatus.EMPTY,
                                statusMessage = "Empty file"
                            )
                        }
                        else -> {
                            val header = CustomFontValidator.readFileHeader(stagedFile)
                            val validFont = header != null &&
                                CustomFontValidator.hasValidSfntHeader(header) &&
                                typefaceVerifier(stagedFile)

                            if (!validFont) {
                                stagedFile.delete()
                                DiscoveredZipFontEntry(
                                    entryId = "zip_$entryIdx",
                                    entryDisplayPath = displayPath,
                                    fileName = leafFileName,
                                    extension = ext,
                                    stagedTempFileName = null,
                                    fileSizeBytes = copyOutcome.bytesWritten,
                                    sha256 = copyOutcome.sha256,
                                    status = ZipFontValidationStatus.UNSUPPORTED_OR_CORRUPTED,
                                    statusMessage = "Invalid or corrupted font"
                                )
                            } else {
                                val hashLower = copyOutcome.sha256.lowercase(Locale.US)
                                when {
                                    hashLower in existingHashes -> {
                                        stagedFile.delete()
                                        DiscoveredZipFontEntry(
                                            entryId = "zip_$entryIdx",
                                            entryDisplayPath = displayPath,
                                            fileName = leafFileName,
                                            extension = ext,
                                            stagedTempFileName = null,
                                            fileSizeBytes = copyOutcome.bytesWritten,
                                            sha256 = copyOutcome.sha256,
                                            status = ZipFontValidationStatus.DUPLICATE_IN_LIBRARY,
                                            statusMessage = "Already imported"
                                        )
                                    }
                                    !seenArchiveHashes.add(hashLower) -> {
                                        stagedFile.delete()
                                        DiscoveredZipFontEntry(
                                            entryId = "zip_$entryIdx",
                                            entryDisplayPath = displayPath,
                                            fileName = leafFileName,
                                            extension = ext,
                                            stagedTempFileName = null,
                                            fileSizeBytes = copyOutcome.bytesWritten,
                                            sha256 = copyOutcome.sha256,
                                            status = ZipFontValidationStatus.DUPLICATE_IN_ARCHIVE,
                                            statusMessage = "Duplicate in ZIP"
                                        )
                                    }
                                    else -> {
                                        DiscoveredZipFontEntry(
                                            entryId = "zip_$entryIdx",
                                            entryDisplayPath = displayPath,
                                            fileName = leafFileName,
                                            extension = ext,
                                            stagedTempFileName = stagedFileName,
                                            fileSizeBytes = copyOutcome.bytesWritten,
                                            sha256 = copyOutcome.sha256,
                                            status = ZipFontValidationStatus.READY,
                                            statusMessage = "Ready to import"
                                        )
                                    }
                                }
                            }
                        }
                    }

                    discovered.add(candidateEntry)
                    zis.closeEntry()
                }
            }

            if (archiveLimiter.exceeded) {
                throw ZipSecurityOrLimitException(
                    "ZIP archive exceeds the ${maxArchiveBytes / (1024 * 1024)} MB size limit.",
                    FontImportErrorCode.ARCHIVE_TOO_LARGE
                )
            }

            if (!sawAnyZipEntry && discovered.isEmpty() && ignoredEntries == 0) {
                cleanupSession(sessionId)
                return@withContext ZipFontScanResult.Failure(
                    reason = "The selected file is empty or not a valid ZIP archive.",
                    errorCode = FontImportErrorCode.ARCHIVE_CORRUPTED
                )
            }

            if (discovered.isEmpty()) {
                cleanupSession(sessionId)
                return@withContext ZipFontScanResult.NoFontsFound(
                    archiveName = archiveName,
                    message = "No supported .ttf or .otf font files were found in '$archiveName'."
                )
            }

            ZipFontScanResult.Success(
                sessionId = sessionId,
                archiveName = archiveName,
                discoveredFonts = discovered,
                ignoredEntriesCount = ignoredEntries,
                limitReachedMessage = limitReachedMsg
            )
        } catch (ce: CancellationException) {
            cleanupSession(sessionId)
            throw ce
        } catch (e: ZipSecurityOrLimitException) {
            cleanupSession(sessionId)
            ZipFontScanResult.Failure(reason = e.message ?: "Archive rejected for safety.", errorCode = e.errorCode)
        } catch (e: Exception) {
            cleanupSession(sessionId)
            val reason = if (archiveLimiter.exceeded) {
                "ZIP archive exceeds the ${maxArchiveBytes / (1024 * 1024)} MB size limit."
            } else {
                "Unable to read '$archiveName'. The archive appears corrupted or invalid."
            }
            val code = if (archiveLimiter.exceeded) FontImportErrorCode.ARCHIVE_TOO_LARGE else FontImportErrorCode.ARCHIVE_CORRUPTED
            ZipFontScanResult.Failure(reason = reason, errorCode = code)
        }
    }

    /**
     * Imports user-selected entries from a previously scanned [sessionId], continuing across
     * individual failures so valid fonts succeed cleanly, and cleaning up the session directory.
     */
    suspend fun importSelectedEntries(
        sessionId: String,
        selectedEntries: List<DiscoveredZipFontEntry>,
        onProgress: ((ZipImportProgress) -> Unit)? = null
    ): ZipBatchImportResult = withContext(Dispatchers.IO) {
        val sessionDir = File(stagingRootDir, sessionId)
        val imported = mutableListOf<CustomFontRecord>()
        val duplicates = mutableListOf<ZipFontItemOutcome.Duplicate>()
        val failed = mutableListOf<ZipFontItemOutcome.Failed>()

        try {
            val total = selectedEntries.size
            for ((idx, entry) in selectedEntries.withIndex()) {
                currentCoroutineContext().ensureActive()
                onProgress?.invoke(
                    ZipImportProgress(
                        completedCount = idx,
                        totalCount = total,
                        currentFileName = entry.fileName
                    )
                )

                if (!entry.isSelectable) {
                    failed.add(ZipFontItemOutcome.Failed(entry, entry.statusMessage))
                    continue
                }

                val stagedFileName = entry.stagedTempFileName
                if (stagedFileName == null) {
                    failed.add(ZipFontItemOutcome.Failed(entry, "Staged font file is unavailable."))
                    continue
                }
                val stagedFile = File(sessionDir, stagedFileName)
                if (!stagedFile.isFile || stagedFile.length() <= 0L) {
                    failed.add(ZipFontItemOutcome.Failed(entry, "Temporary font file is missing or empty."))
                    continue
                }

                // Re-verify header + Android Typeface before registration
                val header = CustomFontValidator.readFileHeader(stagedFile)
                if (header == null || !CustomFontValidator.hasValidSfntHeader(header) || !typefaceVerifier(stagedFile)) {
                    failed.add(ZipFontItemOutcome.Failed(entry, "Failed font validation check."))
                    continue
                }

                when (val reg = repository.registerValidatedTempFile(
                    tempFile = stagedFile,
                    sha256 = entry.sha256,
                    byteCount = entry.fileSizeBytes,
                    originalFileName = entry.fileName,
                    extension = entry.extension
                )) {
                    is SingleFontImportResult.Success -> imported.add(reg.record)
                    is SingleFontImportResult.Duplicate -> duplicates.add(ZipFontItemOutcome.Duplicate(entry, reg.message))
                    is SingleFontImportResult.Failure -> failed.add(ZipFontItemOutcome.Failed(entry, reg.reason))
                    SingleFontImportResult.Cancelled -> Unit
                }
            }

            ZipBatchImportResult(
                imported = imported,
                duplicates = duplicates,
                failed = failed,
                cancelled = false
            )
        } catch (ce: CancellationException) {
            throw ce
        } finally {
            cleanupSession(sessionId)
        }
    }

    fun cleanupSession(sessionId: String) {
        if (sessionId.isBlank()) return
        try {
            val dir = File(stagingRootDir, sessionId)
            if (dir.exists()) dir.deleteRecursively()
        } catch (_: Exception) {
        }
    }

    internal fun validateEntryPathSafety(rawName: String) {
        if (rawName.contains('\u0000') || rawName.contains('\\')) {
            throw ZipSecurityOrLimitException(
                "Archive contains unsafe path characters.",
                FontImportErrorCode.SECURITY_VIOLATION
            )
        }
        val normalized = rawName.trim()
        if (normalized.startsWith("/") || (normalized.length >= 2 && normalized[1] == ':')) {
            throw ZipSecurityOrLimitException(
                "Archive contains an unsafe absolute path.",
                FontImportErrorCode.SECURITY_VIOLATION
            )
        }
        val segments = normalized.split('/')
        if (segments.any { it == ".." }) {
            throw ZipSecurityOrLimitException(
                "Archive contains a path traversal entry (..).",
                FontImportErrorCode.SECURITY_VIOLATION
            )
        }
    }

    private fun shouldIgnoreMetadataOrNonFont(rawName: String): Boolean {
        val segments = rawName.split('/').filter { it.isNotEmpty() }
        if (segments.any { it.equals("__MACOSX", ignoreCase = true) }) return true
        val leaf = segments.lastOrNull() ?: return true
        if (leaf.startsWith("._") || leaf.equals(".DS_Store", ignoreCase = true)) return true
        if (leaf.endsWith(".zip", ignoreCase = true)) return true
        return false
    }

    private data class EntryCopyOutcome(
        val bytesWritten: Long,
        val sha256: String,
        val exceededSingleEntryLimit: Boolean,
        val exceededTotalArchiveLimit: Boolean,
        val suspiciousRatio: Boolean
    )

    private fun copyZipEntryBounded(
        zipStream: InputStream,
        destFile: File,
        compressedSize: Long,
        remainingTotalBytes: Long
    ): EntryCopyOutcome {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var written = 0L

        FileOutputStream(destFile).use { out ->
            while (true) {
                val read = zipStream.read(buffer)
                if (read == -1) break
                written += read

                if (written > CustomFontLimits.RATIO_CHECK_THRESHOLD_BYTES &&
                    compressedSize > 0L &&
                    (written.toDouble() / compressedSize.toDouble()) > CustomFontLimits.MAX_COMPRESSION_RATIO
                ) {
                    return EntryCopyOutcome(written, "", false, false, suspiciousRatio = true)
                }

                if (written > remainingTotalBytes) {
                    return EntryCopyOutcome(written, "", false, exceededTotalArchiveLimit = true, suspiciousRatio = false)
                }

                if (written > maxFontBytes) {
                    return EntryCopyOutcome(written, "", exceededSingleEntryLimit = true, false, suspiciousRatio = false)
                }

                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
            }
            out.fd.sync()
        }

        val sha = digest.digest().joinToString("") { String.format(Locale.US, "%02x", it) }
        return EntryCopyOutcome(
            bytesWritten = written,
            sha256 = sha,
            exceededSingleEntryLimit = false,
            exceededTotalArchiveLimit = false,
            suspiciousRatio = false
        )
    }

    private class BoundedArchiveInputStream(
        delegate: InputStream,
        private val maxBytes: Long
    ) : FilterInputStream(delegate) {
        private var bytesRead: Long = 0L
        var exceeded: Boolean = false
            private set

        override fun read(): Int {
            val b = super.read()
            if (b != -1) {
                bytesRead++
                if (bytesRead > maxBytes) {
                    exceeded = true
                    throw IOException("Archive size limit exceeded")
                }
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val count = super.read(b, off, len)
            if (count > 0) {
                bytesRead += count
                if (bytesRead > maxBytes) {
                    exceeded = true
                    throw IOException("Archive size limit exceeded")
                }
            }
            return count
        }
    }

    private class ZipSecurityOrLimitException(
        message: String,
        val errorCode: FontImportErrorCode
    ) : Exception(message)
}
