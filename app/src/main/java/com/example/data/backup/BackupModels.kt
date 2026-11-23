package com.example.data.backup

import com.example.data.preferences.FontOption
import com.example.data.preferences.HudBackdropStyle
import com.example.data.preferences.LyricsProviderPreference
import com.example.data.preferences.OverlayAnimationStyle
import com.example.data.preferences.ThemeMode
import com.example.data.preferences.ThemePreset
import com.example.service.HudPositioningEngine
import com.example.ui.components.HudColorSystem

/**
 * Logical user data categories supported by LyricBar's Selective Backup & Restore system.
 * Every category maps directly to real persisted data in DataStore or Room.
 */
enum class BackupCategory(
    val id: String,
    val title: String,
    val description: String,
    val archivePrimaryPath: String,
    val schemaVersion: Int = 1
) {
    APP_SETTINGS(
        id = "APP_SETTINGS",
        title = "App Settings",
        description = "Themes, fonts, colours, lyrics provider, animations and display preferences.",
        archivePrimaryPath = "settings/preferences.json"
    ),
    HUD_CONFIGURATION(
        id = "HUD_CONFIGURATION",
        title = "HUD Configuration",
        description = "Portrait & landscape position, size, opacity, backdrop, lyric colours and click-through behaviour.",
        archivePrimaryPath = "hud/configuration.json"
    ),
    LYRICS_HISTORY(
        id = "LYRICS_HISTORY",
        title = "Lyrics History",
        description = "Your recorded playback and lyrics history.",
        archivePrimaryPath = "lyrics/history.json"
    ),
    IMPORTED_LYRICS(
        id = "IMPORTED_LYRICS",
        title = "Imported Lyrics",
        description = "Your locally imported LRC lyric files and song associations.",
        archivePrimaryPath = "imported_lyrics/index.json"
    );

    companion object {
        val ALL_SUPPORTED: List<BackupCategory> = entries.toList()

        fun fromIdOrNull(id: String?): BackupCategory? {
            if (id.isNullOrBlank()) return null
            val trimmed = id.trim()
            return entries.firstOrNull { it.id.equals(trimmed, ignoreCase = true) }
        }
    }
}

/**
 * Strategy for handling existing Lyrics History entries during restore.
 */
enum class HistoryConflictStrategy {
    /** Keeps all existing history records and inserts only non-duplicate entries from backup. */
    MERGE_PRESERVE_EXISTING,

    /** Replaces all existing history records with the entries from the backup (requires confirmation). */
    REPLACE_EXISTING
}

/**
 * Strategy for handling conflicting user-imported LRC entries during restore.
 */
enum class ImportedLyricsConflictStrategy {
    /** Keeps existing user-imported lyrics when a song already has one; restores only new ones. */
    SKIP_EXISTING,

    /** Overwrites existing user-imported lyrics for conflicting songs (requires confirmation). */
    REPLACE_EXISTING
}

/**
 * Portable manifest stored as `manifest.json` at the root of every LyricBar backup ZIP.
 * Uses [formatVersion] independently of [appVersion] to govern compatibility across upgrades
 * and Application ID migrations (e.g., v2.6.1 -> v2.7.0).
 */
data class BackupManifest(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val minSupportedFormatVersion: Int = MIN_SUPPORTED_FORMAT_VERSION,
    val appId: String = PORTABLE_APP_ID,
    val appVersion: String = "",
    val appVersionCode: Int = 0,
    val createdAtEpochMs: Long = 0L,
    val createdAtIso: String = "",
    val databaseSchemaVersion: Int = 2,
    val includedCategories: List<String> = emptyList(),
    val categorySummaries: Map<String, CategorySummary> = emptyMap(),
    val checksums: Map<String, String> = emptyMap()
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 1
        const val MIN_SUPPORTED_FORMAT_VERSION = 1
        const val MAX_SUPPORTED_FORMAT_VERSION = 1
        const val PORTABLE_APP_ID = "lyricbar"
    }
}

data class CategorySummary(
    val itemCount: Int = 0,
    val schemaVersion: Int = 1
)

/**
 * App Settings payload (`settings/preferences.json`).
 * Strictly excludes HUD-specific layout/appearance preferences, runtime flags,
 * updater caches, and credentials.
 */
data class BackupAppSettingsData(
    val schemaVersion: Int = 1,
    val themeMode: String = ThemeMode.FOLLOW_SYSTEM.name,
    val themePreset: String = ThemePreset.ROSE_QUARTZ.name,
    val useWallpaperDynamicColor: Boolean = false,
    val selectedFont: String = FontOption.POPPINS.name,
    val lyricsProvider: String = LyricsProviderPreference.AUTO.name,
    val autoShowOnPlayback: Boolean = true,
    val autoFetchLyrics: Boolean = true,
    val autoUpdateChecksEnabled: Boolean = true,
    val animationStyle: String = OverlayAnimationStyle.KARAOKE.name,
    val upwardAnimationEnabled: Boolean = true,
    val appleMusicStyle: Boolean = true,
    val singleLineLyrics: Boolean = false,
    val adaptiveLyricScrolling: Boolean = true,
    val lyricScrollSpeedMultiplier: Float = 1.0f,
    val typewriterSpeed: Float = 1.0f,
    val smoothTransitionSpeed: Float = 1.0f,
    val fadeSpeed: Float = 1.0f,
    val blurRevealSpeed: Float = 1.0f,
    val karaokeSpeed: Float = 1.0f,
    val karaokeGlowSpeed: Float = 1.0f,
    val globalOffsetMs: Long = 0L,
    val trackOffsets: Map<String, Long> = emptyMap()
)

/**
 * HUD Configuration payload (`hud/configuration.json`).
 * Strictly contains persistent floating HUD layout, visual appearance, backdrop,
 * lyric colours, and click-through settings; excludes transient drag-reposition mode.
 */
data class BackupHudConfigData(
    val schemaVersion: Int = 1,
    val overlayEnabled: Boolean = true,
    val overlayTextSizeSp: Float = 14f,
    val overlayTextOpacity: Float = 1.0f,
    val textShadowEnabled: Boolean = true,
    val overlayTextAlign: String = "CENTER",
    val clickThroughOverlay: Boolean = false,
    val hudBackdropEnabled: Boolean = false,
    val hudBackdropStyle: String = HudBackdropStyle.BLACK.name,
    val hudBackdropOpacity: Float = 0.65f,
    val hudBackdropCornerRadiusDp: Int = 18,
    val hudBackdropHorizontalPaddingDp: Int = 16,
    val hudBackdropVerticalPaddingDp: Int = 10,
    val hudDynamicAccentEnabled: Boolean = false,
    val hudActiveLyricColor: Int = HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR,
    val hudInactiveLyricColor: Int = HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR,
    val hudCustomColors: List<Int> = emptyList(),
    val hudPortraitX: Int = HudPositioningEngine.DEFAULT_PORTRAIT_X,
    val hudPortraitY: Int = HudPositioningEngine.DEFAULT_PORTRAIT_Y,
    val hudPortraitWidth: Int = HudPositioningEngine.DEFAULT_PORTRAIT_WIDTH_PX,
    val hudPortraitHeight: Int = HudPositioningEngine.DEFAULT_PORTRAIT_HEIGHT_PX,
    val hudLandscapeX: Int? = null,
    val hudLandscapeY: Int? = null,
    val hudLandscapeWidth: Int? = null,
    val hudLandscapeHeight: Int? = null
)

/**
 * Lyrics History payload (`lyrics/history.json`).
 */
data class BackupHistoryPayload(
    val schemaVersion: Int = 1,
    val entries: List<BackupHistoryEntry> = emptyList()
)

data class BackupHistoryEntry(
    val key: String = "",
    val title: String = "",
    val artist: String = "",
    val albumArtUri: String? = null,
    val status: String = "Played",
    val playedAt: Long = 0L
)

/**
 * Imported Lyrics index (`imported_lyrics/index.json`).
 * Maps each exported `.lrc` file inside `imported_lyrics/` to its persisted song association.
 */
data class BackupImportedLyricsIndex(
    val schemaVersion: Int = 1,
    val files: List<BackupImportedLyricMeta> = emptyList()
)

data class BackupImportedLyricMeta(
    val key: String = "",
    val title: String = "",
    val artist: String = "",
    val filename: String = "",
    val isSynced: Boolean = true,
    val durationSec: Int? = null,
    val source: String = "Local Import",
    val cachedAt: Long = 0L,
    val sha256: String = ""
)

data class BackupImportedLyricRecord(
    val meta: BackupImportedLyricMeta,
    val content: String
)

/**
 * Complete validated payload extracted from (or prepared for) a backup ZIP.
 * Only selected/present categories are non-null.
 */
data class BackupArchivePayload(
    val manifest: BackupManifest,
    val appSettings: BackupAppSettingsData? = null,
    val hudConfig: BackupHudConfigData? = null,
    val lyricsHistory: BackupHistoryPayload? = null,
    val importedLyrics: List<BackupImportedLyricRecord>? = null
)

/**
 * Snapshot of current local persisted counts displayed on the Create Backup screen.
 */
data class LocalBackupDataCounts(
    val historyCount: Int = 0,
    val importedLyricsCount: Int = 0
)

/**
 * Result of inspecting and validating a user-selected backup ZIP before restore.
 */
data class BackupArchiveInspection(
    val displayFileName: String,
    val payload: BackupArchivePayload,
    val availableCategories: List<BackupCategory>,
    val ignoredUnknownCategories: List<String> = emptyList(),
    val categoryItemCounts: Map<BackupCategory, Int> = emptyMap()
) {
    val manifest: BackupManifest get() = payload.manifest
}

/**
 * Conflict and impact analysis computed against live local data right before showing
 * the Restore Confirmation summary.
 */
data class RestoreConflictSummary(
    val existingHistoryCount: Int = 0,
    val incomingHistoryCount: Int = 0,
    val duplicateHistoryCount: Int = 0,
    val newHistoryCount: Int = 0,
    val existingImportedCount: Int = 0,
    val incomingImportedCount: Int = 0,
    val conflictingImportedCount: Int = 0,
    val newImportedCount: Int = 0
)

sealed class BackupExportResult {
    data class Success(
        val fileName: String,
        val includedCategories: Set<BackupCategory>,
        val totalBytesWritten: Long
    ) : BackupExportResult()

    data class Failure(
        val reason: String,
        val errorCode: BackupErrorCode = BackupErrorCode.IO_ERROR
    ) : BackupExportResult()
}

sealed class BackupInspectionResult {
    data class Valid(val inspection: BackupArchiveInspection) : BackupInspectionResult()

    data class Invalid(
        val reason: String,
        val errorCode: BackupErrorCode
    ) : BackupInspectionResult()
}

data class CategoryRestoreDetail(
    val category: BackupCategory,
    val summary: String
)

sealed class BackupRestoreResult {
    data class Success(
        val restoredCategories: Set<BackupCategory>,
        val details: List<CategoryRestoreDetail>
    ) : BackupRestoreResult()

    data class PartialFailure(
        val restoredCategories: Set<BackupCategory>,
        val failedCategories: Set<BackupCategory>,
        val reason: String
    ) : BackupRestoreResult()

    data class Failure(
        val reason: String,
        val errorCode: BackupErrorCode = BackupErrorCode.RESTORE_FAILED
    ) : BackupRestoreResult()
}

enum class BackupErrorCode {
    EMPTY_SELECTION,
    INSUFFICIENT_STORAGE,
    WRITE_FAILED,
    CORRUPTED_ARCHIVE,
    INVALID_BACKUP,
    UNSUPPORTED_VERSION,
    CHECKSUM_MISMATCH,
    SECURITY_VIOLATION,
    ARCHIVE_TOO_LARGE,
    NO_SUPPORTED_CATEGORIES,
    RESTORE_FAILED,
    PERMISSION_DENIED,
    IO_ERROR
}

class BackupException(
    val errorCode: BackupErrorCode,
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)
