package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.data.backup.BackupArchiveInspection
import com.example.data.backup.BackupCategory
import com.example.data.backup.BackupInspectionResult
import com.example.data.backup.BackupRestoreManager
import com.example.data.backup.BackupRestoreResult
import com.example.data.backup.HistoryConflictStrategy
import com.example.data.backup.ImportedLyricsConflictStrategy
import com.example.data.backup.RestoreConflictSummary
import com.example.data.preferences.UserPreferencesRepository
import com.example.ui.components.FrostedCard
import kotlinx.coroutines.launch

@Composable
internal fun RestoreBackupScreen(
    preferencesRepository: UserPreferencesRepository,
    hasBottomNavPill: Boolean,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val manager = remember(context, preferencesRepository) {
        BackupRestoreManager(context = context, preferencesRepository = preferencesRepository)
    }

    var inspection by remember { mutableStateOf<BackupArchiveInspection?>(null) }
    var selectionState by remember {
        mutableStateOf(BackupCategorySelectionState.forRestore(emptyList()))
    }
    var isInspecting by remember { mutableStateOf(false) }
    var isPreparingConfirmation by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<BackupOperationBanner?>(null) }

    var pendingConflictSummary by remember { mutableStateOf<RestoreConflictSummary?>(null) }
    var historyStrategy by remember { mutableStateOf(HistoryConflictStrategy.MERGE_PRESERVE_EXISTING) }
    var importedStrategy by remember { mutableStateOf(ImportedLyricsConflictStrategy.SKIP_EXISTING) }
    var hasAutoLaunchedPicker by rememberSaveable { mutableStateOf(false) }

    val isBusy = isInspecting || isPreparingConfirmation || isRestoring

    BackHandler(enabled = !isBusy) {
        if (pendingConflictSummary != null) {
            pendingConflictSummary = null
        } else {
            onNavigateBack()
        }
    }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            if (inspection == null) {
                statusBanner = BackupOperationBanner.Info(
                    title = "No File Selected",
                    message = "File selection was cancelled. Tap \"Select Backup File\" to pick a LyricBar backup ZIP."
                )
            }
            return@rememberLauncherForActivityResult
        }

        isInspecting = true
        statusBanner = null
        pendingConflictSummary = null
        coroutineScope.launch {
            val result = manager.inspectBackupUri(context, uri)
            isInspecting = false
            when (result) {
                is BackupInspectionResult.Valid -> {
                    inspection = result.inspection
                    selectionState = BackupCategorySelectionState.forRestore(
                        result.inspection.availableCategories
                    )
                    historyStrategy = HistoryConflictStrategy.MERGE_PRESERVE_EXISTING
                    importedStrategy = ImportedLyricsConflictStrategy.SKIP_EXISTING
                    if (result.inspection.ignoredUnknownCategories.isNotEmpty()) {
                        statusBanner = BackupOperationBanner.Info(
                            title = "Some Future Categories Skipped",
                            message = "Ignored unsupported categories from a newer backup: ${
                                result.inspection.ignoredUnknownCategories.joinToString(", ")
                            }."
                        )
                    }
                }
                is BackupInspectionResult.Invalid -> {
                    inspection = null
                    selectionState = BackupCategorySelectionState.forRestore(emptyList())
                    statusBanner = BackupOperationBanner.Error(
                        title = "Invalid Backup File",
                        message = result.reason
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasAutoLaunchedPicker) {
            hasAutoLaunchedPicker = true
            openDocumentLauncher.launch(BACKUP_ZIP_MIME_TYPES)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.06f))
    ) {
        val isWide = maxWidth >= 660.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (hasBottomNavPill) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (isWide) 24.dp else 20.dp, vertical = 12.dp)
                .padding(bottom = if (hasBottomNavPill) 120.dp else 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
            ) {
                BackupSubScreenHeader(
                    title = stringResource(R.string.restore_backup_title),
                    subtitle = stringResource(R.string.restore_backup_subtitle),
                    backEnabled = !isBusy,
                    onBack = onNavigateBack
                )

                Spacer(modifier = Modifier.height(14.dp))

                val currentInspection = inspection

                if (currentInspection == null) {
                    // Empty / Select Backup File card
                    FrostedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        backgroundAlpha = 0.28f,
                        shadowElevation = 2.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Choose a LyricBar Backup ZIP",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Select a previously exported backup file to inspect its contents and pick which categories to restore.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { openDocumentLauncher.launch(BACKUP_ZIP_MIME_TYPES) },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("restore_pick_file_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.backup_select_file),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                } else {
                    // Backup metadata summary card
                    FrostedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("restore_backup_metadata_card"),
                        shape = RoundedCornerShape(16.dp),
                        backgroundAlpha = 0.30f,
                        shadowElevation = 2.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = currentInspection.displayFileName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Created ${BackupFormatters.formatTimestamp(currentInspection.manifest.createdAtEpochMs)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val versionMeta = buildString {
                                        if (currentInspection.manifest.appVersion.isNotBlank()) {
                                            append("LyricBar v${currentInspection.manifest.appVersion} • ")
                                        }
                                        append("Format v${currentInspection.manifest.formatVersion}")
                                    }
                                    Text(
                                        text = versionMeta,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                OutlinedButton(
                                    onClick = { openDocumentLauncher.launch(BACKUP_ZIP_MIME_TYPES) },
                                    enabled = !isBusy,
                                    modifier = Modifier
                                        .heightIn(min = 44.dp)
                                        .testTag("restore_change_file_button"),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "Change",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Selection count + Select All / Deselect All
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        ) {
                            Text(
                                text = selectionState.selectionSummaryLabel(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .testTag("restore_backup_selected_count")
                            )
                        }

                        TextButton(
                            onClick = {
                                selectionState = selectionState.toggleSelectAll()
                                statusBanner = null
                            },
                            enabled = !isBusy && selectionState.totalCount > 0,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("restore_backup_select_all_button")
                        ) {
                            Text(
                                text = if (selectionState.isAllSelected) {
                                    stringResource(R.string.backup_deselect_all)
                                } else {
                                    stringResource(R.string.backup_select_all)
                                },
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Only available & supported categories from the backup are shown
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for (category in selectionState.availableCategories) {
                            val itemCount = currentInspection.categoryItemCounts[category]
                            val badge = when (category) {
                                BackupCategory.LYRICS_HISTORY -> itemCount?.let {
                                    if (it == 1) "1 record" else "$it records"
                                }
                                BackupCategory.IMPORTED_LYRICS -> itemCount?.let {
                                    if (it == 1) "1 file" else "$it files"
                                }
                                else -> "Included"
                            }
                            BackupCategorySelectableCard(
                                category = category,
                                selected = category in selectionState.selectedCategories,
                                enabled = !isBusy,
                                metadataBadge = badge,
                                onToggle = {
                                    selectionState = selectionState.toggle(category)
                                    statusBanner = null
                                },
                                modifier = Modifier.testTag("restore_category_${category.id.lowercase()}")
                            )
                        }
                    }
                }

                if (isInspecting) {
                    Spacer(modifier = Modifier.height(14.dp))
                    BackupLoadingRow(message = "Validating backup archive and checksums...")
                } else if (isPreparingConfirmation) {
                    Spacer(modifier = Modifier.height(14.dp))
                    BackupLoadingRow(message = "Checking existing data for conflicts...")
                } else if (isRestoring) {
                    Spacer(modifier = Modifier.height(14.dp))
                    BackupLoadingRow(message = "Restoring selected categories...")
                }

                statusBanner?.let { banner ->
                    Spacer(modifier = Modifier.height(14.dp))
                    BackupStatusBannerCard(banner = banner)
                }

                if (currentInspection != null) {
                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = {
                            if (selectionState.canProceed && !isBusy) {
                                isPreparingConfirmation = true
                                statusBanner = null
                                coroutineScope.launch {
                                    val summary = try {
                                        manager.computeConflictSummary(
                                            inspection = currentInspection,
                                            selectedCategories = selectionState.selectedCategories
                                        )
                                    } catch (_: Exception) {
                                        RestoreConflictSummary()
                                    }
                                    isPreparingConfirmation = false
                                    pendingConflictSummary = summary
                                }
                            }
                        },
                        enabled = selectionState.canProceed && !isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .testTag("restore_backup_submit_button"),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (selectionState.canProceed) {
                                "Restore Selected (${selectionState.selectedCount})"
                            } else {
                                "Restore Backup"
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        val activeInspection = inspection
        val conflictSummary = pendingConflictSummary
        if (activeInspection != null && conflictSummary != null) {
            RestoreConfirmationDialog(
                inspection = activeInspection,
                selectedCategories = selectionState.selectedCategories,
                conflictSummary = conflictSummary,
                historyStrategy = historyStrategy,
                onHistoryStrategyChange = { historyStrategy = it },
                importedStrategy = importedStrategy,
                onImportedStrategyChange = { importedStrategy = it },
                onDismiss = { pendingConflictSummary = null },
                onConfirm = {
                    val chosenCategories = selectionState.selectedCategories
                    val chosenHistoryStrategy = historyStrategy
                    val chosenImportedStrategy = importedStrategy
                    pendingConflictSummary = null
                    isRestoring = true
                    statusBanner = null
                    coroutineScope.launch {
                        val result = manager.restoreBackup(
                            inspection = activeInspection,
                            selectedCategories = chosenCategories,
                            historyStrategy = chosenHistoryStrategy,
                            importedLyricsStrategy = chosenImportedStrategy
                        )
                        isRestoring = false
                        statusBanner = when (result) {
                            is BackupRestoreResult.Success -> BackupOperationBanner.Success(
                                title = "Restore Complete",
                                message = "Successfully restored ${result.restoredCategories.size} selected ${
                                    if (result.restoredCategories.size == 1) "category" else "categories"
                                }. Unselected data was left untouched.",
                                details = result.details.map { "${it.category.title}: ${it.summary}" }
                            )
                            is BackupRestoreResult.PartialFailure -> BackupOperationBanner.Warning(
                                title = "Partial Restore",
                                message = result.reason,
                                details = buildList {
                                    add("Restored: ${result.restoredCategories.joinToString { it.title }}")
                                    add("Failed: ${result.failedCategories.joinToString { it.title }}")
                                }
                            )
                            is BackupRestoreResult.Failure -> BackupOperationBanner.Error(
                                title = "Restore Failed",
                                message = result.reason
                            )
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun RestoreConfirmationDialog(
    inspection: BackupArchiveInspection,
    selectedCategories: Set<BackupCategory>,
    conflictSummary: RestoreConflictSummary,
    historyStrategy: HistoryConflictStrategy,
    onHistoryStrategyChange: (HistoryConflictStrategy) -> Unit,
    importedStrategy: ImportedLyricsConflictStrategy,
    onImportedStrategyChange: (ImportedLyricsConflictStrategy) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val orderedSelected = BackupCategory.ALL_SUPPORTED.filter { it in selectedCategories }
    val showHistoryOptions = BackupCategory.LYRICS_HISTORY in selectedCategories &&
        conflictSummary.existingHistoryCount > 0
    val showImportedOptions = BackupCategory.IMPORTED_LYRICS in selectedCategories &&
        conflictSummary.conflictingImportedCount > 0

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        FrostedCard(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 520.dp)
                .padding(vertical = 24.dp)
                .testTag("restore_confirmation_dialog"),
            shape = RoundedCornerShape(22.dp),
            backgroundAlpha = 0.92f,
            borderAlpha = 0.35f
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.backup_confirm_restore_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "${inspection.displayFileName} • ${BackupFormatters.formatTimestamp(inspection.manifest.createdAtEpochMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Categories to restore",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(6.dp))

                for (category in orderedSelected) {
                    Text(
                        text = "• ${category.title}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Categories not listed above will remain untouched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (BackupCategory.APP_SETTINGS in selectedCategories || BackupCategory.HUD_CONFIGURATION in selectedCategories) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Selected preference categories will update their matching settings to the values stored in this backup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (showHistoryOptions) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Existing Lyrics History (${conflictSummary.existingHistoryCount} records)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    ConflictChoiceRow(
                        selected = historyStrategy == HistoryConflictStrategy.MERGE_PRESERVE_EXISTING,
                        title = "Merge (Keep existing & skip duplicates)",
                        subtitle = "Adds ${conflictSummary.newHistoryCount} new record(s) and skips ${conflictSummary.duplicateHistoryCount} duplicate(s).",
                        onClick = { onHistoryStrategyChange(HistoryConflictStrategy.MERGE_PRESERVE_EXISTING) }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    ConflictChoiceRow(
                        selected = historyStrategy == HistoryConflictStrategy.REPLACE_EXISTING,
                        title = "Replace existing history",
                        subtitle = "Deletes all ${conflictSummary.existingHistoryCount} existing record(s) and loads ${conflictSummary.incomingHistoryCount} from backup.",
                        isDestructive = true,
                        onClick = { onHistoryStrategyChange(HistoryConflictStrategy.REPLACE_EXISTING) }
                    )
                }

                if (showImportedOptions) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Imported Lyrics Conflicts (${conflictSummary.conflictingImportedCount} matching songs)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    ConflictChoiceRow(
                        selected = importedStrategy == ImportedLyricsConflictStrategy.SKIP_EXISTING,
                        title = "Keep existing imported files (Recommended)",
                        subtitle = "Restores ${conflictSummary.newImportedCount} new file(s) and keeps ${conflictSummary.conflictingImportedCount} existing file(s).",
                        onClick = { onImportedStrategyChange(ImportedLyricsConflictStrategy.SKIP_EXISTING) }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    ConflictChoiceRow(
                        selected = importedStrategy == ImportedLyricsConflictStrategy.REPLACE_EXISTING,
                        title = "Replace conflicting files",
                        subtitle = "Overwrites ${conflictSummary.conflictingImportedCount} existing imported lyric file(s) with backup versions.",
                        isDestructive = true,
                        onClick = { onImportedStrategyChange(ImportedLyricsConflictStrategy.REPLACE_EXISTING) }
                    )
                }

                val hasDestructiveReplacement =
                    (showHistoryOptions && historyStrategy == HistoryConflictStrategy.REPLACE_EXISTING) ||
                        (showImportedOptions && importedStrategy == ImportedLyricsConflictStrategy.REPLACE_EXISTING)

                if (hasDestructiveReplacement) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Replacement mode is selected. Existing conflicting records in those categories will be overwritten.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("cancel_restore_dialog_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = onConfirm,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("confirm_restore_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.backup_confirm_restore_action),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictChoiceRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    val activeColor = if (isDestructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val borderColor = if (selected) {
        activeColor.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(12.dp))
            .background(
                if (selected) activeColor.copy(alpha = 0.10f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = activeColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (selected && isDestructive) activeColor else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private val BACKUP_ZIP_MIME_TYPES = arrayOf(
    "application/zip",
    "application/x-zip-compressed",
    "application/octet-stream",
    "*/*"
)
