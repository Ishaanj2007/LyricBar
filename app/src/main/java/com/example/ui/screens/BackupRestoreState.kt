package com.example.ui.screens

import com.example.data.backup.BackupCategory
import java.text.DateFormat
import java.util.Date
import java.util.Locale

enum class BackupSubScreen {
    CREATE,
    RESTORE
}

/**
 * Pure, immutable selection state helper for both Create Backup and Restore Backup screens.
 * Selection state is strictly local to the active operation and never mutates app preferences.
 */
data class BackupCategorySelectionState(
    val availableCategories: List<BackupCategory>,
    val selectedCategories: Set<BackupCategory>
) {
    val selectedCount: Int get() = selectedCategories.size
    val totalCount: Int get() = availableCategories.size
    val isAllSelected: Boolean get() = availableCategories.isNotEmpty() && selectedCategories.size == availableCategories.size
    val canProceed: Boolean get() = selectedCategories.isNotEmpty()

    fun toggle(category: BackupCategory): BackupCategorySelectionState {
        if (category !in availableCategories) return this
        val next = if (category in selectedCategories) {
            selectedCategories - category
        } else {
            selectedCategories + category
        }
        return copy(selectedCategories = next)
    }

    fun toggleSelectAll(): BackupCategorySelectionState {
        val next = if (isAllSelected) emptySet() else availableCategories.toSet()
        return copy(selectedCategories = next)
    }

    fun selectionSummaryLabel(): String {
        return "$selectedCount of $totalCount selected"
    }

    companion object {
        fun forNewBackup(supported: List<BackupCategory> = BackupCategory.ALL_SUPPORTED): BackupCategorySelectionState {
            return BackupCategorySelectionState(
                availableCategories = supported,
                selectedCategories = supported.toSet()
            )
        }

        fun forRestore(availableInArchive: List<BackupCategory>): BackupCategorySelectionState {
            return BackupCategorySelectionState(
                availableCategories = availableInArchive,
                selectedCategories = availableInArchive.toSet()
            )
        }
    }
}

sealed class BackupOperationBanner {
    data class Info(val title: String, val message: String) : BackupOperationBanner()
    data class Success(val title: String, val message: String, val details: List<String> = emptyList()) : BackupOperationBanner()
    data class Warning(val title: String, val message: String, val details: List<String> = emptyList()) : BackupOperationBanner()
    data class Error(val title: String, val message: String) : BackupOperationBanner()
}

internal object BackupFormatters {
    fun formatTimestamp(epochMs: Long): String {
        if (epochMs <= 0L) return "Unknown date"
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(epochMs))
    }

    fun formatByteSize(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.2f MB", mb)
    }
}
