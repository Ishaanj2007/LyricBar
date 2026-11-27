package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.fonts.DiscoveredZipFontEntry
import com.example.data.fonts.ZipBatchImportResult
import com.example.data.fonts.ZipFontScanResult
import com.example.data.fonts.ZipFontValidationStatus
import com.example.data.fonts.ZipImportProgress
import com.example.data.fonts.ZipScanProgress
import java.util.Locale

sealed class ZipFontDialogUiState {
    data class Scanning(val progress: ZipScanProgress?) : ZipFontDialogUiState()
    data class Selection(
        val scanResult: ZipFontScanResult.Success,
        val selectedEntryIds: Set<String>
    ) : ZipFontDialogUiState()
    data class Importing(
        val archiveName: String,
        val progress: ZipImportProgress?
    ) : ZipFontDialogUiState()
    data class EmptyOrError(
        val title: String,
        val message: String,
        val isError: Boolean
    ) : ZipFontDialogUiState()
    data class ImportSummary(
        val archiveName: String,
        val result: ZipBatchImportResult
    ) : ZipFontDialogUiState()
}

@Composable
fun CustomFontZipImportDialog(
    uiState: ZipFontDialogUiState,
    onToggleEntry: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onImportSelected: () -> Unit,
    onCancelOrDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancelOrDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 520.dp)
                .heightIn(max = 600.dp)
                .testTag("zip_font_import_dialog"),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderZip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "Import Fonts from ZIP",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                when (uiState) {
                    is ZipFontDialogUiState.Scanning -> {
                        Text(
                            text = "Scanning archive safely for .ttf and .otf fonts…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        uiState.progress?.let { prog ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Entries scanned: ${prog.entriesScanned} • Fonts found: ${prog.fontsFound}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = prog.currentEntryName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            OutlinedButton(onClick = onCancelOrDismiss) {
                                Text("Cancel")
                            }
                        }
                    }

                    is ZipFontDialogUiState.Importing -> {
                        Text(
                            text = "Importing selected fonts from ${uiState.archiveName}…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        val prog = uiState.progress
                        if (prog != null && prog.totalCount > 0) {
                            LinearProgressIndicator(
                                progress = { (prog.completedCount.toFloat() / prog.totalCount).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "${prog.completedCount + 1} / ${prog.totalCount}: ${prog.currentFileName}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            OutlinedButton(onClick = onCancelOrDismiss) {
                                Text("Cancel")
                            }
                        }
                    }

                    is ZipFontDialogUiState.Selection -> {
                        val scan = uiState.scanResult
                        val selectableCount = scan.discoveredFonts.count { it.isSelectable }
                        val selectedCount = uiState.selectedEntryIds.size

                        Text(
                            text = "${scan.archiveName} • Discovered: ${scan.discoveredFonts.size} • Selected: $selectedCount",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("zip_font_counts_label")
                        )

                        scan.limitReachedMessage?.let { msg ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(
                                onClick = onSelectAll,
                                enabled = selectableCount > 0 && selectedCount < selectableCount,
                                modifier = Modifier.testTag("zip_font_select_all")
                            ) {
                                Text("Select All")
                            }
                            TextButton(
                                onClick = onDeselectAll,
                                enabled = selectedCount > 0,
                                modifier = Modifier.testTag("zip_font_deselect_all")
                            ) {
                                Text("Deselect All")
                            }
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(scan.discoveredFonts, key = { it.entryId }) { entry ->
                                val isChecked = entry.entryId in uiState.selectedEntryIds
                                ZipDiscoveredFontRow(
                                    entry = entry,
                                    checked = isChecked,
                                    onToggle = { onToggleEntry(entry.entryId) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                        ) {
                            OutlinedButton(
                                onClick = onCancelOrDismiss,
                                modifier = Modifier.testTag("zip_font_cancel_button")
                            ) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = onImportSelected,
                                enabled = selectedCount > 0,
                                modifier = Modifier.testTag("zip_font_import_selected_button")
                            ) {
                                Text("Import Selected ($selectedCount)")
                            }
                        }
                    }

                    is ZipFontDialogUiState.EmptyOrError -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (uiState.isError) Icons.Default.ErrorOutline else Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = if (uiState.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                text = uiState.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uiState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = onCancelOrDismiss) {
                                Text("Close")
                            }
                        }
                    }

                    is ZipFontDialogUiState.ImportSummary -> {
                        val res = uiState.result
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (res.failed.isEmpty()) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = if (res.failed.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                            )
                            Text(
                                text = "Import Finished",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Imported: ${res.imported.size} • Duplicates skipped: ${res.duplicates.size} • Failed: ${res.failed.size}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (res.failed.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 180.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(res.failed) { failure ->
                                    Text(
                                        text = "• ${failure.entry.fileName}: ${failure.reason}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = onCancelOrDismiss) {
                                Text("Done")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZipDiscoveredFontRow(
    entry: DiscoveredZipFontEntry,
    checked: Boolean,
    onToggle: () -> Unit
) {
    val isSelectable = entry.isSelectable
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (isSelectable) Modifier.clickable(onClick = onToggle) else Modifier),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = if (isSelectable) { _ -> onToggle() } else null,
                enabled = isSelectable,
                modifier = Modifier.testTag("zip_font_checkbox_${entry.entryId}")
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSelectable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${entry.extension.uppercase(Locale.US)} • ${formatFontSizeKb(entry.fileSizeBytes)} • ${entry.statusMessage}",
                    fontSize = 11.sp,
                    color = when (entry.status) {
                        ZipFontValidationStatus.READY -> MaterialTheme.colorScheme.primary
                        ZipFontValidationStatus.DUPLICATE_IN_LIBRARY,
                        ZipFontValidationStatus.DUPLICATE_IN_ARCHIVE -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.error
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatFontSizeKb(bytes: Long): String {
    return if (bytes >= 1024L * 1024L) {
        String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
    } else {
        "${((bytes + 1023L) / 1024L).coerceAtLeast(1L)} KB"
    }
}
