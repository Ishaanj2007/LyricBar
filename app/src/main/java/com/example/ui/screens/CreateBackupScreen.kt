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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.backup.BackupCategory
import com.example.data.backup.BackupExportResult
import com.example.data.backup.BackupRestoreManager
import com.example.data.backup.BackupZipCodec
import com.example.data.backup.LocalBackupDataCounts
import com.example.data.preferences.UserPreferencesRepository
import com.example.ui.components.FrostedCard
import kotlinx.coroutines.launch

@Composable
internal fun BackupRestoreSettingsCard(
    onOpenCreateBackup: () -> Unit,
    onOpenRestoreBackup: () -> Unit,
    modifier: Modifier = Modifier
) {
    FrostedCard(
        modifier = modifier
            .fillMaxWidth()
            .testTag("settings_backup_restore_card"),
        shape = RoundedCornerShape(18.dp),
        backgroundAlpha = 0.28f,
        shadowElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Backup,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.backup_restore_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.backup_restore_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenCreateBackup,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("settings_create_backup_button"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SaveAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.create_backup_title),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onOpenRestoreBackup,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("settings_restore_backup_button"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Restore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.restore_backup_title),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
internal fun CreateBackupScreen(
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

    var selectionState by remember { mutableStateOf(BackupCategorySelectionState.forNewBackup()) }
    var localCounts by remember { mutableStateOf<LocalBackupDataCounts?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var statusBanner by remember { mutableStateOf<BackupOperationBanner?>(null) }

    LaunchedEffect(manager) {
        localCounts = try {
            manager.getLocalDataCounts()
        } catch (_: Exception) {
            null
        }
    }

    BackHandler(enabled = !isExporting) {
        onNavigateBack()
    }

    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) {
            statusBanner = BackupOperationBanner.Info(
                title = "Backup Cancelled",
                message = "No destination was selected, so no backup file was created."
            )
            return@rememberLauncherForActivityResult
        }

        val chosen = selectionState.selectedCategories
        if (chosen.isEmpty()) {
            statusBanner = BackupOperationBanner.Error(
                title = "Nothing Selected",
                message = "Select at least one category before creating a backup."
            )
            return@rememberLauncherForActivityResult
        }

        isExporting = true
        statusBanner = null
        coroutineScope.launch {
            val result = manager.createBackupToUri(
                context = context,
                uri = uri,
                selectedCategories = chosen
            )
            isExporting = false
            statusBanner = when (result) {
                is BackupExportResult.Success -> BackupOperationBanner.Success(
                    title = "Backup Created",
                    message = "Saved ${result.fileName} (${BackupFormatters.formatByteSize(result.totalBytesWritten)}).",
                    details = BackupCategory.ALL_SUPPORTED
                        .filter { it in result.includedCategories }
                        .map { "Included: ${it.title}" }
                )
                is BackupExportResult.Failure -> BackupOperationBanner.Error(
                    title = "Backup Failed",
                    message = result.reason
                )
            }
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
                    title = stringResource(R.string.create_backup_title),
                    subtitle = stringResource(R.string.create_backup_subtitle),
                    backEnabled = !isExporting,
                    onBack = onNavigateBack
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Selection count + Select All / Deselect All control
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
                                .testTag("create_backup_selected_count")
                        )
                    }

                    TextButton(
                        onClick = {
                            selectionState = selectionState.toggleSelectAll()
                            statusBanner = null
                        },
                        enabled = !isExporting,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("create_backup_select_all_button")
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

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (category in selectionState.availableCategories) {
                        val metaBadge = when (category) {
                            BackupCategory.LYRICS_HISTORY -> localCounts?.let { counts ->
                                if (counts.historyCount == 1) "1 record" else "${counts.historyCount} records"
                            }
                            BackupCategory.IMPORTED_LYRICS -> localCounts?.let { counts ->
                                if (counts.importedLyricsCount == 1) "1 file" else "${counts.importedLyricsCount} files"
                            }
                            else -> null
                        }
                        BackupCategorySelectableCard(
                            category = category,
                            selected = category in selectionState.selectedCategories,
                            enabled = !isExporting,
                            metadataBadge = metaBadge,
                            onToggle = {
                                selectionState = selectionState.toggle(category)
                                statusBanner = null
                            },
                            modifier = Modifier.testTag("backup_category_${category.id.lowercase()}")
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Portable archive note
                FrostedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    backgroundAlpha = 0.22f,
                    shadowElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Only selected categories are exported into a portable ZIP file. Disposable online lyrics cache and temporary state are excluded automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (isExporting) {
                    Spacer(modifier = Modifier.height(14.dp))
                    BackupLoadingRow(message = "Creating and verifying backup archive...")
                }

                statusBanner?.let { banner ->
                    Spacer(modifier = Modifier.height(14.dp))
                    BackupStatusBannerCard(banner = banner)
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = {
                        if (selectionState.canProceed && !isExporting) {
                            createDocumentLauncher.launch(BackupZipCodec.defaultBackupFileName())
                        }
                    },
                    enabled = selectionState.canProceed && !isExporting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("create_backup_submit_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SaveAlt,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (selectionState.canProceed) {
                            "Create Backup (${selectionState.selectedCount})"
                        } else {
                            stringResource(R.string.create_backup_title)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
internal fun BackupSubScreenHeader(
    title: String,
    subtitle: String,
    backEnabled: Boolean,
    onBack: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            enabled = backEnabled,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .testTag("backup_subscreen_back_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to Settings",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun BackupCategorySelectableCard(
    category: BackupCategory,
    selected: Boolean,
    enabled: Boolean,
    metadataBadge: String?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    }

    FrostedCard(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                enabled = enabled,
                role = Role.Checkbox,
                onClick = onToggle
            ),
        shape = RoundedCornerShape(16.dp),
        backgroundAlpha = if (selected) 0.34f else 0.22f,
        shadowElevation = if (selected) 3.dp else 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        },
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = backupCategoryIcon(category),
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = category.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!metadataBadge.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = metadataBadge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = category.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Checkbox(
                checked = selected,
                onCheckedChange = null,
                enabled = enabled
            )
        }
    }
}

@Composable
internal fun BackupLoadingRow(message: String) {
    FrostedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        backgroundAlpha = 0.30f,
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
internal fun BackupStatusBannerCard(
    banner: BackupOperationBanner,
    modifier: Modifier = Modifier
) {
    val (icon, tint, borderColor) = when (banner) {
        is BackupOperationBanner.Success -> Triple(
            Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        )
        is BackupOperationBanner.Warning -> Triple(
            Icons.Default.WarningAmber,
            MaterialTheme.colorScheme.tertiary,
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.50f)
        )
        is BackupOperationBanner.Error -> Triple(
            Icons.Default.ErrorOutline,
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.error.copy(alpha = 0.55f)
        )
        is BackupOperationBanner.Info -> Triple(
            Icons.Default.Info,
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        )
    }

    val title = when (banner) {
        is BackupOperationBanner.Success -> banner.title
        is BackupOperationBanner.Warning -> banner.title
        is BackupOperationBanner.Error -> banner.title
        is BackupOperationBanner.Info -> banner.title
    }
    val message = when (banner) {
        is BackupOperationBanner.Success -> banner.message
        is BackupOperationBanner.Warning -> banner.message
        is BackupOperationBanner.Error -> banner.message
        is BackupOperationBanner.Info -> banner.message
    }
    val details = when (banner) {
        is BackupOperationBanner.Success -> banner.details
        is BackupOperationBanner.Warning -> banner.details
        else -> emptyList()
    }

    FrostedCard(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(14.dp))
            .testTag("backup_status_banner"),
        shape = RoundedCornerShape(14.dp),
        backgroundAlpha = 0.34f,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tint
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (details.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    for (line in details) {
                        Text(
                            text = "• $line",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

internal fun backupCategoryIcon(category: BackupCategory): ImageVector {
    return when (category) {
        BackupCategory.APP_SETTINGS -> Icons.Default.Tune
        BackupCategory.HUD_CONFIGURATION -> Icons.Default.Layers
        BackupCategory.LYRICS_HISTORY -> Icons.Default.History
        BackupCategory.IMPORTED_LYRICS -> Icons.Default.Description
    }
}
