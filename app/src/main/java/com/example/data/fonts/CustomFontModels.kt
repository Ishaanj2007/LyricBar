package com.example.data.fonts

import com.google.gson.annotations.SerializedName

/**
 * Security and resource limits shared by individual and ZIP custom-font imports.
 */
object CustomFontLimits {
    const val MAX_FONT_FILE_BYTES: Long = 10L * 1024L * 1024L // 10 MB per font
    const val MAX_ZIP_ARCHIVE_BYTES: Long = 50L * 1024L * 1024L // 50 MB compressed archive limit
    const val MAX_TOTAL_EXTRACTED_FONT_BYTES: Long = 40L * 1024L * 1024L // 40 MB extracted fonts limit
    const val MAX_DISCOVERED_FONTS: Int = 50
    const val MAX_ZIP_SCAN_ENTRIES: Int = 1000
    const val RATIO_CHECK_THRESHOLD_BYTES: Long = 1024L * 1024L // 1 MB
    const val MAX_COMPRESSION_RATIO: Double = 50.0
}

/**
 * Persistent metadata for a user-imported custom font stored in app-private storage.
 */
data class CustomFontRecord(
    @SerializedName("id") val id: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("originalFileName") val originalFileName: String,
    @SerializedName("extension") val extension: String,
    @SerializedName("storedFileName") val storedFileName: String,
    @SerializedName("sha256") val sha256: String,
    @SerializedName("fileSizeBytes") val fileSizeBytes: Long,
    @SerializedName("importedAtMs") val importedAtMs: Long
)

internal data class CustomFontRegistryPayload(
    @SerializedName("schemaVersion") val schemaVersion: Int = 1,
    @SerializedName("fonts") val fonts: List<CustomFontRecord> = emptyList()
)

sealed class SingleFontImportResult {
    data class Success(val record: CustomFontRecord) : SingleFontImportResult()
    data class Duplicate(val existingRecord: CustomFontRecord, val message: String) : SingleFontImportResult()
    data class Failure(val reason: String, val errorCode: FontImportErrorCode) : SingleFontImportResult()
    data object Cancelled : SingleFontImportResult()
}

enum class FontImportErrorCode {
    UNSUPPORTED_EXTENSION,
    EMPTY_FILE,
    FILE_TOO_LARGE,
    CORRUPTED_OR_INVALID_FONT,
    ARCHIVE_TOO_LARGE,
    ARCHIVE_CORRUPTED,
    ARCHIVE_EMPTY,
    TOO_MANY_FONTS,
    SECURITY_VIOLATION,
    IO_ERROR
}

sealed class CustomFontDeleteResult {
    data class Deleted(val removedRecord: CustomFontRecord, val resetSelectionToDefault: Boolean) : CustomFontDeleteResult()
    data object NotFound : CustomFontDeleteResult()
    data class Failure(val reason: String) : CustomFontDeleteResult()
}

data class CustomFontBatchDeleteResult(
    val deletedRecords: List<CustomFontRecord>,
    val notFoundIds: List<String>,
    val resetSelectionToDefault: Boolean
)

enum class ZipFontValidationStatus {
    READY,
    DUPLICATE_IN_LIBRARY,
    DUPLICATE_IN_ARCHIVE,
    UNSUPPORTED_OR_CORRUPTED,
    TOO_LARGE,
    EMPTY
}

data class DiscoveredZipFontEntry(
    val entryId: String,
    val entryDisplayPath: String,
    val fileName: String,
    val extension: String,
    val stagedTempFileName: String?,
    val fileSizeBytes: Long,
    val sha256: String,
    val status: ZipFontValidationStatus,
    val statusMessage: String
) {
    val isSelectable: Boolean
        get() = status == ZipFontValidationStatus.READY && !stagedTempFileName.isNullOrBlank()
}

data class ZipScanProgress(
    val entriesScanned: Int,
    val fontsFound: Int,
    val currentEntryName: String
)

data class ZipImportProgress(
    val completedCount: Int,
    val totalCount: Int,
    val currentFileName: String
)

sealed class ZipFontScanResult {
    data class Success(
        val sessionId: String,
        val archiveName: String,
        val discoveredFonts: List<DiscoveredZipFontEntry>,
        val ignoredEntriesCount: Int,
        val limitReachedMessage: String? = null
    ) : ZipFontScanResult()

    data class NoFontsFound(
        val archiveName: String,
        val message: String
    ) : ZipFontScanResult()

    data class Failure(
        val reason: String,
        val errorCode: FontImportErrorCode
    ) : ZipFontScanResult()
}

sealed class ZipFontItemOutcome {
    data class Imported(val entry: DiscoveredZipFontEntry, val record: CustomFontRecord) : ZipFontItemOutcome()
    data class Duplicate(val entry: DiscoveredZipFontEntry, val message: String) : ZipFontItemOutcome()
    data class Failed(val entry: DiscoveredZipFontEntry, val reason: String) : ZipFontItemOutcome()
}

data class ZipBatchImportResult(
    val imported: List<CustomFontRecord>,
    val duplicates: List<ZipFontItemOutcome.Duplicate>,
    val failed: List<ZipFontItemOutcome.Failed>,
    val cancelled: Boolean = false
)
