package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt
import com.example.data.preferences.HudBackdropStyle
import com.example.data.preferences.LyricsProviderPreference
import com.example.data.preferences.OverlayAnimationStyle
import com.example.data.preferences.ThemeMode
import com.example.data.preferences.ThemePreset
import com.example.data.preferences.UserPreferences
import com.example.data.repository.CompositeLyricsRepository
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.example.service.HudDisplayMetricsProvider
import com.example.service.HudOrientation
import com.example.service.HudOrientationInfoSnapshot
import com.example.service.HudPositioningEngine
import com.example.service.MediaPlaybackManager
import com.example.ui.components.CalmThemedBackground
import com.example.ui.components.FrostedCard
import com.example.ui.components.HudColorPickerDialog
import com.example.ui.components.HudColorSystem
import com.example.ui.components.HudColorTarget
import com.example.ui.components.HudHoldGestureController
import com.example.ui.components.LocalHazeState
import com.example.ui.components.MinimalOverlayView
import com.example.ui.components.ProviderReorderLogic
import com.example.ui.components.ProviderSlotBounds
import com.example.ui.theme.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

internal object SettingsAccordionState {
    const val SECTION_OVERLAY = "overlay"
    const val SECTION_HUD_BACKDROP = "hud_backdrop"
    const val SECTION_ANIMATION = "animation"
    const val SECTION_SIZE_ALIGN = "size_align"
    const val SECTION_POSITION = "position"
    const val SECTION_THEME = "theme"
    const val SECTION_TYPOGRAPHY = "typography"
    const val SECTION_LYRICS_PROVIDER = "lyrics_provider"
    const val SECTION_DATA = "data"
    const val SECTION_BACKUP_RESTORE = "backup_restore"
    const val SECTION_FEEDBACK = "feedback"
    const val SECTION_ABOUT = "about"

    val ALL_SECTION_KEYS: List<String> = listOf(
        SECTION_OVERLAY,
        SECTION_HUD_BACKDROP,
        SECTION_ANIMATION,
        SECTION_SIZE_ALIGN,
        SECTION_POSITION,
        SECTION_THEME,
        SECTION_TYPOGRAPHY,
        SECTION_LYRICS_PROVIDER,
        SECTION_DATA,
        SECTION_BACKUP_RESTORE,
        SECTION_FEEDBACK,
        SECTION_ABOUT
    )

    fun initialExpandedSections(): Set<String> = emptySet()

    fun toggleSection(current: Set<String>, key: String): Set<String> {
        return if (key in current) current - key else current + key
    }

    fun isAllExpanded(current: Set<String>): Boolean {
        return current.size == ALL_SECTION_KEYS.size
    }

    fun toggleAllSections(current: Set<String>): Set<String> {
        return if (isAllExpanded(current)) emptySet() else ALL_SECTION_KEYS.toSet()
    }
}

@Composable
fun SettingsScreen(
    playbackManager: MediaPlaybackManager,
    userPreferences: UserPreferences,
    modifier: Modifier = Modifier,
    hasBottomNavPill: Boolean = true,
    openUpdaterSignal: Int = 0,
    onOpenUpdaterConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settingsScrollState = rememberScrollState()
    var viewportBoundsInRoot by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val livePreferences by playbackManager.preferencesRepository.userPreferencesFlow
        .collectAsStateWithLifecycle(initialValue = userPreferences)
    val selectedProvider = livePreferences.lyricsProvider
    var showCacheClearedToast by remember { mutableStateOf(false) }
    var showDynamicAccentInfoDialog by remember { mutableStateOf(false) }
    var dynamicAccentInfoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val updateManager = remember { com.example.data.updater.UpdateManager.getInstance(context) }
    val cachedAvailableRelease by updateManager.cachedAvailableRelease.collectAsStateWithLifecycle()
    var showUpdateDialog by remember { mutableStateOf(false) }
    var notificationsAllowed by remember {
        mutableStateOf(com.example.data.updater.UpdateNotifier(context).canPostNotifications())
    }
    var showOrientationInfoDialog by remember { mutableStateOf(false) }
    var selectedColorTarget by rememberSaveable { mutableStateOf(HudColorTarget.ACTIVE_LYRIC) }
    var activeColorPickerTarget by remember { mutableStateOf<HudColorTarget?>(null) }
    var activeBackupSubScreen by rememberSaveable { mutableStateOf<BackupSubScreen?>(null) }
    var activeFeedbackSubScreen by rememberSaveable { mutableStateOf<FeedbackTab?>(null) }

    val customFontRepo = remember(context) { com.example.data.fonts.CustomFontRepository.getInstance(context) }
    val customFonts by customFontRepo.customFontsFlow.collectAsStateWithLifecycle()
    val customFontZipImporter = remember(context, customFontRepo) {
        com.example.data.fonts.CustomFontZipImporter(
            repository = customFontRepo,
            stagingRootDir = java.io.File(context.applicationContext.cacheDir, "custom_fonts_tmp")
        )
    }
    var singleFontFeedback by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var isSingleFontImporting by remember { mutableStateOf(false) }
    var fontPendingDeletion by remember { mutableStateOf<com.example.data.fonts.CustomFontRecord?>(null) }
    var isFontMultiSelectMode by remember { mutableStateOf(false) }
    var selectedFontIdsForDeletion by remember { mutableStateOf<Set<String>>(emptySet()) }
    var fontsPendingBatchDeletion by remember { mutableStateOf<List<com.example.data.fonts.CustomFontRecord>>(emptyList()) }
    var zipDialogState by remember { mutableStateOf<com.example.ui.components.ZipFontDialogUiState?>(null) }
    var zipOperationJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    LaunchedEffect(customFonts) {
        val validIds = customFonts.mapTo(HashSet()) { it.id }
        val pruned = selectedFontIdsForDeletion.intersect(validIds)
        if (pruned != selectedFontIdsForDeletion) {
            selectedFontIdsForDeletion = pruned
        }
        if (customFonts.isEmpty() && isFontMultiSelectMode) {
            isFontMultiSelectMode = false
        }
    }

    val singleFontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isSingleFontImporting = true
            singleFontFeedback = null
            when (val res = customFontRepo.importFontFromUri(context, uri)) {
                is com.example.data.fonts.SingleFontImportResult.Success -> {
                    playbackManager.preferencesRepository.setSelectedCustomFontId(res.record.id)
                    singleFontFeedback = "Imported & selected '${res.record.displayName}'" to false
                }
                is com.example.data.fonts.SingleFontImportResult.Duplicate -> {
                    playbackManager.preferencesRepository.setSelectedCustomFontId(res.existingRecord.id)
                    singleFontFeedback = "${res.message} Switched selection to it." to false
                }
                is com.example.data.fonts.SingleFontImportResult.Failure -> {
                    singleFontFeedback = res.reason to true
                }
                com.example.data.fonts.SingleFontImportResult.Cancelled -> Unit
            }
            isSingleFontImporting = false
        }
    }

    val zipFontPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        zipOperationJob?.cancel()
        zipDialogState = com.example.ui.components.ZipFontDialogUiState.Scanning(progress = null)
        zipOperationJob = coroutineScope.launch {
            when (val scan = customFontZipImporter.scanZipFromUri(context, uri) { prog ->
                zipDialogState = com.example.ui.components.ZipFontDialogUiState.Scanning(prog)
            }) {
                is com.example.data.fonts.ZipFontScanResult.Success -> {
                    zipDialogState = com.example.ui.components.ZipFontDialogUiState.Selection(
                        scanResult = scan,
                        selectedEntryIds = emptySet()
                    )
                }
                is com.example.data.fonts.ZipFontScanResult.NoFontsFound -> {
                    zipDialogState = com.example.ui.components.ZipFontDialogUiState.EmptyOrError(
                        title = "No Supported Fonts Found",
                        message = scan.message,
                        isError = false
                    )
                }
                is com.example.data.fonts.ZipFontScanResult.Failure -> {
                    zipDialogState = com.example.ui.components.ZipFontDialogUiState.EmptyOrError(
                        title = "Unable to Scan ZIP",
                        message = scan.reason,
                        isError = true
                    )
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsAllowed = com.example.data.updater.UpdateNotifier(context).canPostNotifications()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val allSectionKeys = SettingsAccordionState.ALL_SECTION_KEYS
    var expandedSections by remember {
        mutableStateOf(SettingsAccordionState.initialExpandedSections())
    }

    LaunchedEffect(openUpdaterSignal) {
        if (openUpdaterSignal > 0) {
            activeBackupSubScreen = null
            activeFeedbackSubScreen = null
            expandedSections = expandedSections + SettingsAccordionState.SECTION_ABOUT
            showUpdateDialog = true
            updateManager.openUpdaterFromNotification()
            onOpenUpdaterConsumed()
        }
    }
    val toggleSection: (String) -> Unit = { key ->
        expandedSections = SettingsAccordionState.toggleSection(expandedSections, key)
    }

    val configuration = LocalConfiguration.current
    val view = LocalView.current
    val densityState = LocalDensity.current
    val density = densityState.density
    val fontScale = densityState.fontScale
    val displayGeometry = remember(
        configuration.orientation,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        configuration.densityDpi,
        density
    ) {
        HudDisplayMetricsProvider.getOverlayDisplayGeometry(
            context = context,
            rootWindowInsets = view.rootWindowInsets
        )
    }
    val activeOrientation = displayGeometry.orientation
    val activeProfileLabel = if (activeOrientation == HudOrientation.LANDSCAPE) "Landscape" else "Portrait"
    val minBackdropHeightPx = remember(
        livePreferences.hudBackdropEnabled,
        livePreferences.overlayTextSizeSp,
        livePreferences.hudBackdropVerticalPaddingDp,
        density
    ) {
        HudPositioningEngine.computeMinBackdropHeightPx(livePreferences, density)
    }
    val activeProfile = remember(activeOrientation, livePreferences, displayGeometry, minBackdropHeightPx) {
        livePreferences.resolveProfile(activeOrientation, displayGeometry, minBackdropHeightPx)
    }
    val resolvedBounds = remember(activeProfile, displayGeometry, minBackdropHeightPx) {
        HudPositioningEngine.resolveBounds(activeProfile, displayGeometry, minBackdropHeightPx)
    }
    val orientationInfo = remember(activeOrientation, displayGeometry, resolvedBounds) {
        HudPositioningEngine.buildOrientationInfoSnapshot(
            orientation = activeOrientation,
            geometry = displayGeometry,
            resolvedBounds = resolvedBounds
        )
    }

    LaunchedEffect(activeOrientation, livePreferences.hasLandscapeProfile, displayGeometry, minBackdropHeightPx) {
        if (activeOrientation == HudOrientation.LANDSCAPE && !livePreferences.hasLandscapeProfile) {
            val defaultLandscape = HudPositioningEngine.createDefaultLandscapeProfile(
                geometry = displayGeometry,
                minBackdropHeightPx = minBackdropHeightPx
            )
            playbackManager.preferencesRepository.ensureLandscapeProfile(defaultLandscape)
        }
    }

    val sectionOverlay: @Composable () -> Unit = {
        // SECTION 1: FLOATING OVERLAY & TIMELINE (Behaviour, lyric colours & timeline sync)
        val effectiveDragMode = livePreferences.isDragRepositionMode && !livePreferences.clickThroughOverlay
        val overlayBehaviorSummary = buildList {
            if (livePreferences.clickThroughOverlay) {
                add("Click-through on")
            } else if (effectiveDragMode) {
                add("Drag mode on")
            } else {
                add("Hold ${HudHoldGestureController.HOLD_REPOSITION_SECONDS}s to move")
            }
            if (livePreferences.singleLineLyrics) {
                add("Single-line")
            }
        }.joinToString(" • ")
        SettingsAccordionCard(
            title = "Floating Overlay & Timeline",
            subtitle = if (livePreferences.overlayEnabled) overlayBehaviorSummary else "Off • Floating overlay disabled",
            icon = Icons.Default.Layers,
            isExpanded = SettingsAccordionState.SECTION_OVERLAY in expandedSections,
            onToggle = { toggleSection(SettingsAccordionState.SECTION_OVERLAY) },
            modifier = Modifier.testTag("settings_overlay_section")
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                // Toggle: 2D Drag & Reposition Mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = "2D Drag Reposition Mode",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when {
                                livePreferences.clickThroughOverlay ->
                                    "Paused while Click-through Overlay is enabled"
                                effectiveDragMode ->
                                    "Touch enabled: drag & resize overlay anywhere on screen"
                                else ->
                                    "Hold HUD ${HudHoldGestureController.HOLD_REPOSITION_SECONDS}s to reposition; toggle ON for resize handles"
                            },
                            fontSize = 12.sp,
                            color = when {
                                livePreferences.clickThroughOverlay ->
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                                effectiveDragMode ->
                                    MaterialTheme.colorScheme.primary
                                else ->
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    Switch(
                        checked = effectiveDragMode,
                        enabled = !livePreferences.clickThroughOverlay,
                        onCheckedChange = { isDrag ->
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setDragRepositionMode(isDrag)
                            }
                        },
                        modifier = Modifier.testTag("settings_drag_mode_switch")
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Toggle: Click-through Overlay
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_click_through_row"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = "Click-through Overlay",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Allow touches to pass through the floating HUD to the app underneath.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (livePreferences.clickThroughOverlay) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            },
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (livePreferences.clickThroughOverlay) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                }
                            )
                        ) {
                            Text(
                                text = if (livePreferences.clickThroughOverlay) {
                                    "On • Touches pass through; HUD gestures disabled"
                                } else {
                                    "Off • HUD touch & hold gestures enabled"
                                },
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (livePreferences.clickThroughOverlay) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                    .testTag("settings_click_through_status")
                            )
                        }
                    }
                    Switch(
                        checked = livePreferences.clickThroughOverlay,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setClickThroughOverlay(enabled)
                            }
                        },
                        modifier = Modifier.testTag("settings_click_through_switch")
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Toggle: Contrast Text Shadow
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Text Contrast Shadow",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Subtle glyph shadow for readability over light backgrounds",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = livePreferences.textShadowEnabled,
                        onCheckedChange = { shadow ->
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setTextShadowEnabled(shadow)
                            }
                        },
                        modifier = Modifier.testTag("settings_shadow_switch")
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Toggle: Single-Line Lyrics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Single-Line Lyrics",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Keep each lyric on one line and scroll horizontally when needed.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = livePreferences.singleLineLyrics,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setSingleLineLyrics(enabled)
                            }
                        },
                        modifier = Modifier.testTag("settings_single_line_lyrics_switch")
                    )
                }

                if (livePreferences.singleLineLyrics) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize()
                            .padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Adaptive Lyric Scrolling",
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Scroll overflowing lyrics based on playback interval to next lyric",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = livePreferences.adaptiveLyricScrolling,
                                onCheckedChange = { enabled ->
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setAdaptiveLyricScrolling(enabled)
                                    }
                                },
                                modifier = Modifier.testTag("settings_adaptive_scrolling_switch")
                            )
                        }

                        if (livePreferences.adaptiveLyricScrolling) {
                            Spacer(modifier = Modifier.height(10.dp))
                            val speedMultiplier = livePreferences.lyricScrollSpeedMultiplier
                            val speedLabel = when {
                                speedMultiplier >= 1.4f -> "Fast"
                                speedMultiplier >= 1.15f -> "Brisk"
                                else -> "Standard"
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Scroll Speed Multiplier",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${String.format(java.util.Locale.US, "%.1f×", speedMultiplier)} ($speedLabel)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            var localScrollSpeed by remember(livePreferences.lyricScrollSpeedMultiplier) {
                                mutableFloatStateOf(livePreferences.lyricScrollSpeedMultiplier)
                            }
                            Slider(
                                value = localScrollSpeed,
                                onValueChange = { localScrollSpeed = it },
                                onValueChangeFinished = {
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setLyricScrollSpeedMultiplier(localScrollSpeed)
                                    }
                                },
                                valueRange = 1.0f..1.5f,
                                steps = 4,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_scroll_speed_slider")
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // HUD LYRIC COLOURS (Independent of backdrop toggle)
                val currentTargetColor = when (selectedColorTarget) {
                    HudColorTarget.ACTIVE_LYRIC -> livePreferences.hudActiveLyricColor
                    HudColorTarget.INACTIVE_LYRIC -> livePreferences.hudInactiveLyricColor
                }
                val hasCustomColorChanges = livePreferences.hudActiveLyricColor != HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR ||
                    livePreferences.hudInactiveLyricColor != HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Lyric Colours",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (hasCustomColorChanges) {
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    playbackManager.preferencesRepository.applyHudColorPair(
                                        activeColorArgb = HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR,
                                        inactiveColorArgb = HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR
                                    )
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier
                                .heightIn(min = 36.dp)
                                .testTag("hud_color_reset_defaults")
                        ) {
                            Text("Reset", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                Text(
                    text = "Customize active and inactive floating lyric colours independently.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Toggle: Dynamic Accent Colour
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = "Dynamic Accent Colour",
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Automatically adapt HUD accent colours to the currently playing album artwork.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = livePreferences.hudDynamicAccentEnabled,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setHudDynamicAccentEnabled(enabled)
                            }
                            if (enabled) {
                                dynamicAccentInfoJob?.cancel()
                                showDynamicAccentInfoDialog = true
                                dynamicAccentInfoJob = coroutineScope.launch {
                                    kotlinx.coroutines.delay(DYNAMIC_ACCENT_SNACKBAR_DURATION_MS)
                                    showDynamicAccentInfoDialog = false
                                }
                            } else {
                                dynamicAccentInfoJob?.cancel()
                                dynamicAccentInfoJob = null
                                showDynamicAccentInfoDialog = false
                            }
                        },
                        modifier = Modifier.testTag("settings_dynamic_accent_switch")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 2-target segmented selector: [ Active Lyric | Inactive Lyric ]
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val targets = listOf(
                            HudColorTarget.ACTIVE_LYRIC to "Active Lyric",
                            HudColorTarget.INACTIVE_LYRIC to "Inactive Lyric"
                        )
                        targets.forEach { (target, shortLabel) ->
                            val isSelected = selectedColorTarget == target
                            val targetArgb = when (target) {
                                HudColorTarget.ACTIVE_LYRIC -> livePreferences.hudActiveLyricColor
                                HudColorTarget.INACTIVE_LYRIC -> livePreferences.hudInactiveLyricColor
                            }
                            Surface(
                                onClick = { selectedColorTarget = target },
                                shape = RoundedCornerShape(9.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    Color.Transparent
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("hud_color_target_${target.name.lowercase()}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(Color(targetArgb))
                                            .border(
                                                width = 1.dp,
                                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
                                                shape = CircleShape
                                            )
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = shortLabel,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Active target details + Custom colour picker trigger
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = selectedColorTarget.title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${HudColorSystem.labelForColor(currentTargetColor)} • ${HudColorSystem.formatHexColor(currentTargetColor)}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { activeColorPickerTarget = selectedColorTarget },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("hud_color_custom_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Custom", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Curated colour preset swatches
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val columns = (maxWidth / 48.dp).toInt().coerceIn(4, 8)
                            val presetRows = remember(columns) {
                                HudColorSystem.curatedPresets.chunked(columns)
                            }
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                presetRows.forEach { rowPresets ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        rowPresets.forEach { preset ->
                                            val isSelected = currentTargetColor == preset.argb
                                            val isDarkSwatch = HudColorSystem.isDarkColor(preset.argb)
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .heightIn(min = 48.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .clickable {
                                                        coroutineScope.launch {
                                                            when (selectedColorTarget) {
                                                                HudColorTarget.ACTIVE_LYRIC ->
                                                                    playbackManager.preferencesRepository.setHudActiveLyricColor(preset.argb)
                                                                HudColorTarget.INACTIVE_LYRIC ->
                                                                    playbackManager.preferencesRepository.setHudInactiveLyricColor(preset.argb)
                                                            }
                                                        }
                                                    }
                                                    .semantics {
                                                        contentDescription = "${preset.label} colour preset"
                                                    }
                                                    .testTag("hud_color_preset_${preset.id}"),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(34.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(preset.argb))
                                                        .border(
                                                            width = if (isSelected) 2.5.dp else 1.dp,
                                                            color = if (isSelected) {
                                                                MaterialTheme.colorScheme.primary
                                                            } else {
                                                                MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                                                            },
                                                            shape = CircleShape
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = null,
                                                            tint = if (isDarkSwatch) Color.White else Color(0xFF141418),
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        repeat((columns - rowPresets.size).coerceAtLeast(0)) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }

                        // Saved custom colours (persisted in DataStore)
                        if (livePreferences.hudCustomColors.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Saved Custom Colours",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                val columns = (maxWidth / 48.dp).toInt().coerceIn(4, 8)
                                val customRows = remember(livePreferences.hudCustomColors, columns) {
                                    livePreferences.hudCustomColors.chunked(columns)
                                }
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    customRows.forEachIndexed { rowIdx, rowColors ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            rowColors.forEachIndexed { colIdx, colorArgb ->
                                                val globalIdx = rowIdx * columns + colIdx
                                                val isSelected = currentTargetColor == colorArgb
                                                val isDarkSwatch = HudColorSystem.isDarkColor(colorArgb)
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .heightIn(min = 48.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .clickable {
                                                            coroutineScope.launch {
                                                                when (selectedColorTarget) {
                                                                    HudColorTarget.ACTIVE_LYRIC ->
                                                                        playbackManager.preferencesRepository.setHudActiveLyricColor(colorArgb)
                                                                    HudColorTarget.INACTIVE_LYRIC ->
                                                                        playbackManager.preferencesRepository.setHudInactiveLyricColor(colorArgb)
                                                                }
                                                            }
                                                        }
                                                        .semantics {
                                                            contentDescription = "Custom colour ${HudColorSystem.formatHexColor(colorArgb)}"
                                                        }
                                                        .testTag("hud_color_custom_swatch_$globalIdx"),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(34.dp)
                                                            .clip(CircleShape)
                                                            .background(Color(colorArgb))
                                                            .border(
                                                                width = if (isSelected) 2.5.dp else 1.dp,
                                                                color = if (isSelected) {
                                                                    MaterialTheme.colorScheme.primary
                                                                } else {
                                                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                                                                },
                                                                shape = CircleShape
                                                            ),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        if (isSelected) {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = null,
                                                                tint = if (isDarkSwatch) Color.White else Color(0xFF141418),
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            repeat((columns - rowColors.size).coerceAtLeast(0)) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Non-blocking contrast warning + optional 1-tap readable defaults
                val contrastWarning = remember(
                    livePreferences.hudBackdropEnabled,
                    livePreferences.hudBackdropStyle,
                    livePreferences.hudBackdropOpacity,
                    livePreferences.hudActiveLyricColor,
                    livePreferences.hudInactiveLyricColor
                ) {
                    HudColorSystem.evaluateReadability(
                        backdropEnabled = livePreferences.hudBackdropEnabled,
                        backdropStyle = livePreferences.hudBackdropStyle,
                        backdropOpacity = livePreferences.hudBackdropOpacity,
                        activeColor = livePreferences.hudActiveLyricColor,
                        inactiveColor = livePreferences.hudInactiveLyricColor
                    )
                }

                if (contrastWarning != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("hud_color_contrast_warning"),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                        border = BorderStroke(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier
                                        .padding(top = 1.dp)
                                        .size(18.dp)
                                )
                                Text(
                                    text = contrastWarning.message,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        coroutineScope.launch {
                                            playbackManager.preferencesRepository.applyHudColorPair(
                                                activeColorArgb = contrastWarning.suggestedActiveColor,
                                                inactiveColorArgb = contrastWarning.suggestedInactiveColor
                                            )
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("hud_color_apply_readable_defaults")
                                ) {
                                    Text(
                                        text = "Apply readable defaults",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Dedicated Resync Timeline Row & Button
                var resyncMessage by remember { mutableStateOf<String?>(null) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Lyric Timeline Synchronization",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = resyncMessage ?: "Re-anchor timeline to active playback and clear timing drift",
                            fontSize = 12.sp,
                            color = if (resyncMessage != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            playbackManager.resyncLyrics(forceRefresh = true)
                            resyncMessage = "Timeline calibrated & resynced!"
                            coroutineScope.launch {
                                kotlinx.coroutines.delay(2500)
                                resyncMessage = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("settings_resync_button")
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Resync Lyric Timeline",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Resync", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    val sectionHudBackdrop: @Composable () -> Unit = {
        // SECTION 2: HUD BACKDROP
        val isBackdropEnabled = livePreferences.hudBackdropEnabled
        val backdropSubtitle = if (isBackdropEnabled) {
            "${livePreferences.hudBackdropStyle.title} • ${(livePreferences.hudBackdropOpacity * 100).roundToInt()}% opacity"
        } else {
            "Off • Transparent text-only HUD"
        }

        SettingsAccordionCard(
            title = "HUD Backdrop",
            subtitle = backdropSubtitle,
            icon = Icons.Default.RoundedCorner,
            isExpanded = SettingsAccordionState.SECTION_HUD_BACKDROP in expandedSections,
            onToggle = { toggleSection(SettingsAccordionState.SECTION_HUD_BACKDROP) },
            modifier = Modifier.testTag("settings_hud_backdrop_section")
        ) {
            var localBackdropOpacity by remember(livePreferences.hudBackdropOpacity) {
                mutableFloatStateOf(livePreferences.hudBackdropOpacity)
            }
            var localCornerRadiusDp by remember(livePreferences.hudBackdropCornerRadiusDp) {
                mutableFloatStateOf(livePreferences.hudBackdropCornerRadiusDp.toFloat())
            }
            var localHorizontalPaddingDp by remember(livePreferences.hudBackdropHorizontalPaddingDp) {
                mutableFloatStateOf(livePreferences.hudBackdropHorizontalPaddingDp.toFloat())
            }
            var localVerticalPaddingDp by remember(livePreferences.hudBackdropVerticalPaddingDp) {
                mutableFloatStateOf(livePreferences.hudBackdropVerticalPaddingDp.toFloat())
            }
            val controlsAlpha = if (isBackdropEnabled) 1f else 0.48f

            Column(modifier = Modifier.padding(18.dp)) {
                // Toggle: Enable HUD Backdrop
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Text(
                            text = "Enable Backdrop",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Add a clean background surface behind floating lyrics for better readability.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isBackdropEnabled,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setHudBackdropEnabled(enabled)
                            }
                        },
                        modifier = Modifier.testTag("settings_hud_backdrop_switch")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Live Preview Box
                Text(
                    text = "Live Preview",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 82.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF1E242E),
                                    Color(0xFF5D6573),
                                    Color(0xFFD5DCE6)
                                )
                            )
                        )
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .testTag("hud_backdrop_live_preview"),
                    contentAlignment = Alignment.Center
                ) {
                    val cornerDp = localCornerRadiusDp.roundToInt().coerceIn(0, 32)
                    val previewShape = RoundedCornerShape(cornerDp.dp)
                    val clampedPreviewOpacity = localBackdropOpacity.coerceIn(0f, 1f)
                    val appearance = remember(livePreferences.hudBackdropStyle, clampedPreviewOpacity) {
                        MinimalOverlayView.computeBackdropAppearance(
                            style = livePreferences.hudBackdropStyle.name,
                            opacity = clampedPreviewOpacity
                        )
                    }
                    val lyricColors = remember(
                        livePreferences.hudActiveLyricColor,
                        livePreferences.hudInactiveLyricColor,
                        livePreferences.overlayTextOpacity
                    ) {
                        MinimalOverlayView.computeLyricColorHierarchy(
                            activeColor = livePreferences.hudActiveLyricColor,
                            inactiveColor = livePreferences.hudInactiveLyricColor,
                            textOpacity = livePreferences.overlayTextOpacity
                        )
                    }
                    val baseFillColor = Color(appearance.fillColor).copy(
                        alpha = (appearance.fillAlpha / 255f).coerceIn(0f, 1f)
                    )
                    val isShowingBackdrop = isBackdropEnabled && clampedPreviewOpacity > 0f
                    val previewOuterModifier = if (isShowingBackdrop) {
                        Modifier
                            .clip(previewShape)
                            .background(baseFillColor, previewShape)
                    } else {
                        Modifier
                    }

                    val contentPaddingModifier = if (isShowingBackdrop) {
                        Modifier.padding(
                            horizontal = localHorizontalPaddingDp.roundToInt().coerceIn(0, 32).dp,
                            vertical = localVerticalPaddingDp.roundToInt().coerceIn(0, 24).dp
                        )
                    } else {
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    }

                    val activePreviewColor = Color(lyricColors.activeColor).copy(
                        alpha = (lyricColors.activeAlpha / 255f).coerceIn(0f, 1f)
                    )
                    val inactivePreviewColor = Color(lyricColors.inactiveColor).copy(
                        alpha = (lyricColors.inactiveAlpha / 255f).coerceIn(0f, 1f)
                    )
                    val activeShadow = if (livePreferences.textShadowEnabled) {
                        if (HudColorSystem.isDarkColor(lyricColors.activeColor)) {
                            androidx.compose.ui.graphics.Shadow(
                                color = Color.White.copy(alpha = 0.25f),
                                offset = androidx.compose.ui.geometry.Offset(0f, 1f),
                                blurRadius = 2.5f
                            )
                        } else {
                            androidx.compose.ui.graphics.Shadow(
                                color = Color.Black.copy(
                                    alpha = MinimalOverlayView.ACTIVE_SHADOW_ALPHA / 255f
                                ),
                                offset = androidx.compose.ui.geometry.Offset(0f, 1.5f),
                                blurRadius = 5.5f
                            )
                        }
                    } else {
                        null
                    }

                    Box(
                        modifier = previewOuterModifier,
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = contentPaddingModifier,
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(
                                        SpanStyle(
                                            color = activePreviewColor,
                                            fontWeight = FontWeight.Bold
                                        )
                                    ) {
                                        append("I thought I saw ")
                                    }
                                    withStyle(
                                        SpanStyle(
                                            color = inactivePreviewColor,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    ) {
                                        append("your face today")
                                    }
                                },
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                style = LocalTextStyle.current.copy(shadow = activeShadow)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Segmented Backdrop Style Selector: [ Black | Grey ]
                Text(
                    text = "Backdrop Style",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = controlsAlpha)
                )
                Text(
                    text = livePreferences.hudBackdropStyle.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = controlsAlpha)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        HudBackdropStyle.entries.forEach { styleOption ->
                            val isSelected = livePreferences.hudBackdropStyle == styleOption
                            Surface(
                                onClick = {
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setHudBackdropStyle(styleOption)
                                    }
                                },
                                enabled = isBackdropEnabled,
                                shape = RoundedCornerShape(9.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = controlsAlpha)
                                } else {
                                    Color.Transparent
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("hud_backdrop_style_${styleOption.name.lowercase()}")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = styleOption.title,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = (if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }).copy(alpha = controlsAlpha)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Opacity Slider (0% - 100%, step 5%, default 65%)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Opacity",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = controlsAlpha)
                    )
                    Text(
                        text = "${(localBackdropOpacity * 100).roundToInt()}%",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = controlsAlpha)
                    )
                }
                Slider(
                    value = localBackdropOpacity,
                    onValueChange = { raw ->
                        val snapped = ((raw * 20f).roundToInt() / 20f).coerceIn(0f, 1f)
                        localBackdropOpacity = snapped
                        coroutineScope.launch {
                            playbackManager.preferencesRepository.setHudBackdropOpacity(snapped)
                        }
                    },
                    enabled = isBackdropEnabled,
                    valueRange = 0f..1f,
                    steps = 19,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hud_backdrop_opacity_slider")
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Corner Radius Slider (0dp - 32dp, step 2dp, default 18dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Corner Radius",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = controlsAlpha)
                    )
                    Text(
                        text = "${localCornerRadiusDp.roundToInt()}dp",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = controlsAlpha)
                    )
                }
                Slider(
                    value = localCornerRadiusDp,
                    onValueChange = { raw ->
                        val snapped = ((raw / 2f).roundToInt() * 2).coerceIn(0, 32)
                        localCornerRadiusDp = snapped.toFloat()
                        coroutineScope.launch {
                            playbackManager.preferencesRepository.setHudBackdropCornerRadiusDp(snapped)
                        }
                    },
                    enabled = isBackdropEnabled,
                    valueRange = 0f..32f,
                    steps = 15,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hud_backdrop_corner_radius_slider")
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Horizontal Padding Slider (0dp - 32dp, step 2dp, default 16dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Horizontal Padding",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = controlsAlpha)
                    )
                    Text(
                        text = "${localHorizontalPaddingDp.roundToInt()}dp",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = controlsAlpha)
                    )
                }
                Slider(
                    value = localHorizontalPaddingDp,
                    onValueChange = { raw ->
                        val snapped = ((raw / 2f).roundToInt() * 2).coerceIn(0, 32)
                        localHorizontalPaddingDp = snapped.toFloat()
                        coroutineScope.launch {
                            playbackManager.preferencesRepository.setHudBackdropHorizontalPaddingDp(snapped)
                        }
                    },
                    enabled = isBackdropEnabled,
                    valueRange = 0f..32f,
                    steps = 15,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hud_backdrop_horizontal_padding_slider")
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Vertical Padding Slider (0dp - 24dp, step 2dp, default 10dp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Vertical Padding",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = controlsAlpha)
                    )
                    Text(
                        text = "${localVerticalPaddingDp.roundToInt()}dp",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = controlsAlpha)
                    )
                }
                Slider(
                    value = localVerticalPaddingDp,
                    onValueChange = { raw ->
                        val snapped = ((raw / 2f).roundToInt() * 2).coerceIn(0, 24)
                        localVerticalPaddingDp = snapped.toFloat()
                        coroutineScope.launch {
                            playbackManager.preferencesRepository.setHudBackdropVerticalPaddingDp(snapped)
                        }
                    },
                    enabled = isBackdropEnabled,
                    valueRange = 0f..24f,
                    steps = 11,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hud_backdrop_vertical_padding_slider")
                )
            }
        }
    }

    val sectionAnimation: @Composable () -> Unit = {
        // SECTION 2: LYRICS ANIMATION STYLE
        SettingsAccordionCard(
            title = "Lyrics Animation Style",
            subtitle = "${userPreferences.animationStyle.replace('_', ' ')} • Transition engine",
            icon = Icons.Default.Animation,
            isExpanded = "animation" in expandedSections,
            onToggle = { toggleSection("animation") }
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Floating Overlay Animation",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Select your preferred lyric transition and word-highlighting engine",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                OverlayAnimationStyle.entries.forEach { style ->
                    val isSelected = userPreferences.animationStyle == style.name
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                coroutineScope.launch {
                                    playbackManager.preferencesRepository.setAnimationStyle(style.name)
                                }
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setAnimationStyle(style.name)
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = style.title,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = style.description,
                                    fontSize = 11.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Apple-style Karaoke Glow Sweep Speed (Appears ONLY when Apple Karaoke style is selected)
                if (userPreferences.animationStyle == "KARAOKE") {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Glow Sweep Speed",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                val speedLabel = when {
                                    userPreferences.karaokeGlowSpeed <= 0.6f -> "0.5× — Slow"
                                    userPreferences.karaokeGlowSpeed <= 0.85f -> "0.7× — Relaxed"
                                    userPreferences.karaokeGlowSpeed in 0.86f..1.25f -> "1.0× — Normal"
                                    else -> "1.5× — Fast"
                                }
                                Text(
                                    text = speedLabel,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Adjust the speed of the white glowing highlight sweep across lyric text",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            var localKaraokeSpeed by remember(userPreferences.karaokeGlowSpeed) {
                                mutableFloatStateOf(userPreferences.karaokeGlowSpeed)
                            }

                            Slider(
                                value = localKaraokeSpeed,
                                onValueChange = { localKaraokeSpeed = it },
                                onValueChangeFinished = {
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setKaraokeGlowSpeed(localKaraokeSpeed)
                                    }
                                },
                                valueRange = 0.5f..1.5f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("karaoke_glow_speed_slider")
                                    .testTag("karaoke_speed_slider")
                            )

                            // Quick speed preset buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "0.5× — Slow" to 0.5f,
                                    "0.7× — Relaxed" to 0.7f,
                                    "1.0× — Normal" to 1.0f,
                                    "1.5× — Fast" to 1.5f
                                ).forEach { (label, speedVal) ->
                                    val isSelected = abs(userPreferences.karaokeGlowSpeed - speedVal) < 0.08f
                                    val tagKey = when (speedVal) {
                                        0.5f -> "0_5"
                                        0.7f -> "0_7"
                                        1.0f -> "1_0"
                                        else -> "1_5"
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                playbackManager.preferencesRepository.setKaraokeGlowSpeed(speedVal)
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("karaoke_glow_speed_preset_$tagKey")
                                            .testTag("karaoke_speed_preset_$tagKey"),
                                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp)
                                ) {
                                    Text(
                                        text = "Enhanced Glow",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Add a stronger luminous halo around active karaoke words.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = userPreferences.karaokeEnhancedGlow,
                                    onCheckedChange = { enabled ->
                                        coroutineScope.launch {
                                            playbackManager.preferencesRepository.setKaraokeEnhancedGlow(enabled)
                                        }
                                    },
                                    modifier = Modifier.testTag("settings_karaoke_enhanced_glow_switch")
                                )
                            }
                        }
                    }
                }

                // Typewriter Reveal Speed (Appears ONLY when Typewriter style is selected)
                if (userPreferences.animationStyle == "TYPEWRITER") {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Typewriter Reveal Speed",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                val speedLabel = when {
                                    userPreferences.typewriterSpeed <= 0.55f -> "Slow (${String.format(java.util.Locale.US, "%.1fx", userPreferences.typewriterSpeed)})"
                                    userPreferences.typewriterSpeed <= 0.85f -> "Relaxed (${String.format(java.util.Locale.US, "%.1fx", userPreferences.typewriterSpeed)})"
                                    userPreferences.typewriterSpeed in 0.86f..1.15f -> "Normal (1.0x)"
                                    else -> "Fast (${String.format(java.util.Locale.US, "%.1fx", userPreferences.typewriterSpeed)})"
                                }
                                Text(
                                    text = speedLabel,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Slow down character typing reveal for a more dramatic pacing",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            var localSpeed by remember(userPreferences.typewriterSpeed) {
                                mutableFloatStateOf(userPreferences.typewriterSpeed)
                            }

                            Slider(
                                value = localSpeed,
                                onValueChange = { localSpeed = it },
                                onValueChangeFinished = {
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setTypewriterSpeed(localSpeed)
                                    }
                                },
                                valueRange = 0.4f..2.0f,
                                steps = 7,
                                modifier = Modifier.fillMaxWidth().testTag("typewriter_speed_slider")
                            )

                            // Quick speed preset buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "0.5x Slow" to 0.5f,
                                    "0.7x Relaxed" to 0.7f,
                                    "1.0x Normal" to 1.0f,
                                    "1.5x Fast" to 1.5f
                                ).forEach { (label, speedVal) ->
                                    val isSelected = abs(userPreferences.typewriterSpeed - speedVal) < 0.1f
                                    FilledTonalButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                playbackManager.preferencesRepository.setTypewriterSpeed(speedVal)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(text = label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // Smooth Line Transition Speed (Appears ONLY when Smooth Line Transition style is selected)
                if (userPreferences.animationStyle == "SMOOTH_SLIDE") {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Transition Speed",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                val speedLabel = when {
                                    userPreferences.smoothTransitionSpeed <= 0.6f -> "0.5× — Slow"
                                    userPreferences.smoothTransitionSpeed <= 0.85f -> "0.7× — Relaxed"
                                    userPreferences.smoothTransitionSpeed in 0.86f..1.25f -> "1.0× — Normal"
                                    else -> "1.5× — Fast"
                                }
                                Text(
                                    text = speedLabel,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Adjust how gently lyrics fade and slide during line transitions",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            var localSpeed by remember(userPreferences.smoothTransitionSpeed) {
                                mutableFloatStateOf(userPreferences.smoothTransitionSpeed)
                            }

                            Slider(
                                value = localSpeed,
                                onValueChange = { localSpeed = it },
                                onValueChangeFinished = {
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setSmoothTransitionSpeed(localSpeed)
                                    }
                                },
                                valueRange = 0.5f..1.5f,
                                modifier = Modifier.fillMaxWidth().testTag("transition_speed_slider")
                            )

                            // Quick speed preset buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "0.5× — Slow" to 0.5f,
                                    "0.7× — Relaxed" to 0.7f,
                                    "1.0× — Normal" to 1.0f,
                                    "1.5× — Fast" to 1.5f
                                ).forEach { (label, speedVal) ->
                                    val isSelected = abs(userPreferences.smoothTransitionSpeed - speedVal) < 0.08f
                                    val tagKey = when (speedVal) {
                                        0.5f -> "0_5"
                                        0.7f -> "0_7"
                                        1.0f -> "1_0"
                                        else -> "1_5"
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                playbackManager.preferencesRepository.setSmoothTransitionSpeed(speedVal)
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("transition_speed_preset_$tagKey"),
                                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Fade In / Fade Out Speed (Appears ONLY when Fade In / Fade Out style is selected)
                if (userPreferences.animationStyle == "FADE") {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Transition Speed",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                val speedLabel = when {
                                    userPreferences.fadeSpeed <= 0.6f -> "0.5× — Slow"
                                    userPreferences.fadeSpeed <= 0.85f -> "0.7× — Relaxed"
                                    userPreferences.fadeSpeed in 0.86f..1.25f -> "1.0× — Normal"
                                    else -> "1.5× — Fast"
                                }
                                Text(
                                    text = speedLabel,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Adjust how gently lyrics dissolve between previous and incoming lines",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            var localFadeSpeed by remember(userPreferences.fadeSpeed) {
                                mutableFloatStateOf(userPreferences.fadeSpeed)
                            }

                            Slider(
                                value = localFadeSpeed,
                                onValueChange = { localFadeSpeed = it },
                                onValueChangeFinished = {
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setFadeSpeed(localFadeSpeed)
                                    }
                                },
                                valueRange = 0.5f..1.5f,
                                modifier = Modifier.fillMaxWidth().testTag("fade_speed_slider")
                            )

                            // Quick speed preset buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "0.5× — Slow" to 0.5f,
                                    "0.7× — Relaxed" to 0.7f,
                                    "1.0× — Normal" to 1.0f,
                                    "1.5× — Fast" to 1.5f
                                ).forEach { (label, speedVal) ->
                                    val isSelected = abs(userPreferences.fadeSpeed - speedVal) < 0.08f
                                    val tagKey = when (speedVal) {
                                        0.5f -> "0_5"
                                        0.7f -> "0_7"
                                        1.0f -> "1_0"
                                        else -> "1_5"
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                playbackManager.preferencesRepository.setFadeSpeed(speedVal)
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("fade_speed_preset_$tagKey"),
                                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Blur Reveal Speed (Appears ONLY when Blur Reveal style is selected)
                if (userPreferences.animationStyle == "BLUR_REVEAL") {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Transition Speed",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                val speedLabel = when {
                                    userPreferences.blurRevealSpeed <= 0.6f -> "0.5× — Slow"
                                    userPreferences.blurRevealSpeed <= 0.85f -> "0.7× — Relaxed"
                                    userPreferences.blurRevealSpeed in 0.86f..1.25f -> "1.0× — Normal"
                                    else -> "1.5× — Fast"
                                }
                                Text(
                                    text = speedLabel,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Adjust the duration of the gentle blur-to-sharp reveal motion",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            var localBlurSpeed by remember(userPreferences.blurRevealSpeed) {
                                mutableFloatStateOf(userPreferences.blurRevealSpeed)
                            }

                            Slider(
                                value = localBlurSpeed,
                                onValueChange = { localBlurSpeed = it },
                                onValueChangeFinished = {
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setBlurRevealSpeed(localBlurSpeed)
                                    }
                                },
                                valueRange = 0.5f..1.5f,
                                modifier = Modifier.fillMaxWidth().testTag("blur_speed_slider")
                            )

                            // Quick speed preset buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "0.5× — Slow" to 0.5f,
                                    "0.7× — Relaxed" to 0.7f,
                                    "1.0× — Normal" to 1.0f,
                                    "1.5× — Fast" to 1.5f
                                ).forEach { (label, speedVal) ->
                                    val isSelected = abs(userPreferences.blurRevealSpeed - speedVal) < 0.08f
                                    val tagKey = when (speedVal) {
                                        0.5f -> "0_5"
                                        0.7f -> "0_7"
                                        1.0f -> "1_0"
                                        else -> "1_5"
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                playbackManager.preferencesRepository.setBlurRevealSpeed(speedVal)
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("blur_speed_preset_$tagKey"),
                                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Interactive Live Preview
                AnimationStylePreview(
                    styleName = userPreferences.animationStyle,
                    textOpacity = userPreferences.overlayTextOpacity,
                    typewriterSpeed = userPreferences.typewriterSpeed,
                    smoothTransitionSpeed = userPreferences.smoothTransitionSpeed,
                    fadeSpeed = userPreferences.fadeSpeed,
                    blurRevealSpeed = userPreferences.blurRevealSpeed,
                    karaokeSpeed = userPreferences.karaokeSpeed
                )
            }
        }
    }

    val sectionSizeAlign: @Composable () -> Unit = {
        // SECTION 3: OVERLAY SIZE & ALIGNMENT
        SettingsAccordionCard(
            title = "Overlay Size & Alignment",
            subtitle = "$activeProfileLabel • ${(resolvedBounds.widthPx / density).roundToInt()} × ${(resolvedBounds.heightPx / density).roundToInt()} dp • ${livePreferences.overlayTextAlign.lowercase().replaceFirstChar { it.uppercase() }}",
            icon = Icons.Default.AspectRatio,
            isExpanded = "size_align" in expandedSections,
            onToggle = { toggleSection("size_align") }
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                val maxWidthDp = (resolvedBounds.constraints.maxWidthPx / density).coerceAtLeast(181f)
                val engineMinWidthDp = (resolvedBounds.constraints.minWidthPx / density).coerceAtLeast(100f)
                val minWidthDp = minOf(180f, (maxWidthDp - 16f).coerceAtLeast(engineMinWidthDp))
                    .coerceAtMost(maxWidthDp - 1f)
                val minHeightDp = (resolvedBounds.constraints.minHeightPx / density)
                    .coerceIn(40f, 200f)
                val maxHeightDp = minOf(220f, resolvedBounds.constraints.maxHeightPx / density)
                    .coerceAtLeast(minHeightDp + 1f)

                val currentWidthDp = (resolvedBounds.widthPx / density).roundToInt().toFloat()
                    .coerceIn(minWidthDp, maxWidthDp)
                val currentHeightDp = (resolvedBounds.heightPx / density).roundToInt().toFloat()
                    .coerceIn(minHeightDp, maxHeightDp)

                var localWidthDp by remember(activeOrientation, resolvedBounds.widthPx, minWidthDp, maxWidthDp) {
                    mutableFloatStateOf(currentWidthDp)
                }
                var localHeightDp by remember(activeOrientation, resolvedBounds.heightPx, minHeightDp, maxHeightDp) {
                    mutableFloatStateOf(currentHeightDp)
                }

                // Width Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Container Width",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${localWidthDp.roundToInt()} dp (${(localWidthDp * density).roundToInt()} px)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Slider(
                    value = localWidthDp.coerceIn(minWidthDp, maxWidthDp),
                    onValueChange = { localWidthDp = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            val newWPx = (localWidthDp * density).roundToInt()
                            val newHPx = (localHeightDp * density).roundToInt()
                            val nextConstraints = HudPositioningEngine.computeConstraints(
                                geometry = displayGeometry,
                                currentWidthPx = newWPx,
                                currentEffectiveHeightPx = maxOf(newHPx, minBackdropHeightPx),
                                minBackdropHeightPx = minBackdropHeightPx
                            )
                            playbackManager.preferencesRepository.setOverlayDimensions(
                                widthPx = newWPx,
                                heightPx = newHPx,
                                orientation = activeOrientation,
                                constraints = nextConstraints
                            )
                        }
                    },
                    valueRange = minWidthDp..maxWidthDp,
                    modifier = Modifier.fillMaxWidth().testTag("width_slider")
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Height Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Container Height",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${localHeightDp.roundToInt()} dp (${(localHeightDp * density).roundToInt()} px)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Slider(
                    value = localHeightDp.coerceIn(minHeightDp, maxHeightDp),
                    onValueChange = { localHeightDp = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            val newWPx = (localWidthDp * density).roundToInt()
                            val newHPx = (localHeightDp * density).roundToInt()
                            val nextConstraints = HudPositioningEngine.computeConstraints(
                                geometry = displayGeometry,
                                currentWidthPx = newWPx,
                                currentEffectiveHeightPx = maxOf(newHPx, minBackdropHeightPx),
                                minBackdropHeightPx = minBackdropHeightPx
                            )
                            playbackManager.preferencesRepository.setOverlayDimensions(
                                widthPx = newWPx,
                                heightPx = newHPx,
                                orientation = activeOrientation,
                                constraints = nextConstraints
                            )
                        }
                    },
                    valueRange = minHeightDp..maxHeightDp,
                    modifier = Modifier.fillMaxWidth().testTag("height_slider")
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Aspect Preview
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val previewRatio = (localWidthDp / localHeightDp.coerceAtLeast(1f)).coerceIn(1.5f, 8f)
                        Box(
                            modifier = Modifier
                                .fillMaxHeight(0.65f)
                                .aspectRatio(previewRatio)
                                .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${localWidthDp.roundToInt()} × ${localHeightDp.roundToInt()} dp",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                // HUD Lyrics Alignment inside the container
                Text(
                    text = "HUD Lyrics Alignment",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Aligns text inside the floating HUD overlay container (independent of screen placement)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "Left" to "LEFT",
                        "Centre" to "CENTER",
                        "Right" to "RIGHT"
                    ).forEach { (label, alignCode) ->
                        val isSelected = when (alignCode) {
                            "CENTER" -> livePreferences.overlayTextAlign.equals("CENTER", ignoreCase = true) ||
                                livePreferences.overlayTextAlign.equals("CENTRE", ignoreCase = true)
                            else -> livePreferences.overlayTextAlign.equals(alignCode, ignoreCase = true)
                        }
                        FilledTonalButton(
                            onClick = {
                                coroutineScope.launch {
                                    playbackManager.preferencesRepository.setOverlayTextAlign(alignCode)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }

    val sectionPosition: @Composable () -> Unit = {
        // SECTION 4: OVERLAY POSITION (ORIENTATION-AWARE CONTROLLER WITH SLIDERS AND MICRO-NUDGES)
        SettingsAccordionCard(
            title = "Overlay Position",
            subtitle = "$activeProfileLabel • X: ${(resolvedBounds.x / density).roundToInt()} dp • Y: ${(resolvedBounds.y / density).roundToInt()} dp",
            icon = Icons.Default.OpenWith,
            isExpanded = "position" in expandedSections,
            onToggle = { toggleSection("position") }
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                val minXDp = resolvedBounds.constraints.minX / density
                val maxXDp = (resolvedBounds.constraints.maxX / density).coerceAtLeast(minXDp + 1f)

                val minYDp = resolvedBounds.constraints.minY / density
                val maxYDp = (resolvedBounds.constraints.maxY / density).coerceAtLeast(minYDp + 1f)

                val currentXDp = (resolvedBounds.x / density).roundToInt().toFloat().coerceIn(minXDp, maxXDp)
                val currentYDp = (resolvedBounds.y / density).roundToInt().toFloat().coerceIn(minYDp, maxYDp)

                var localXDp by remember(activeOrientation, resolvedBounds.x, minXDp, maxXDp) {
                    mutableFloatStateOf(currentXDp)
                }
                var localYDp by remember(activeOrientation, resolvedBounds.y, minYDp, maxYDp) {
                    mutableFloatStateOf(currentYDp)
                }

                // Row 1: Horizontal Position (X)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Horizontal Position (X)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${localXDp.roundToInt()} dp (${(localXDp * density).roundToInt()} px)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Slider(
                    value = localXDp.coerceIn(minXDp, maxXDp),
                    onValueChange = { localXDp = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            val newXPx = (localXDp * density).roundToInt()
                                .coerceIn(resolvedBounds.constraints.minX, resolvedBounds.constraints.maxX)
                            playbackManager.preferencesRepository.setOverlayPosition(
                                x = newXPx,
                                y = resolvedBounds.y,
                                orientation = activeOrientation
                            )
                        }
                    },
                    valueRange = minXDp..maxXDp,
                    modifier = Modifier.fillMaxWidth().testTag("x_position_slider")
                )

                // Responsive Micro-Nudge Row for X
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(-20, -5, +5, +20).forEach { deltaDp ->
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    val deltaPx = (deltaDp * density).roundToInt()
                                    playbackManager.preferencesRepository.nudgeOverlayPosition(
                                        deltaX = deltaPx,
                                        deltaY = 0,
                                        orientation = activeOrientation,
                                        constraints = resolvedBounds.constraints,
                                        fallbackProfile = activeProfile
                                    )
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (deltaDp > 0) "+${deltaDp}dp" else "${deltaDp}dp",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(16.dp))

                // Row 2: Vertical Position (Y)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Vertical Position (Y)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${localYDp.roundToInt()} dp (${(localYDp * density).roundToInt()} px)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Slider(
                    value = localYDp.coerceIn(minYDp, maxYDp),
                    onValueChange = { localYDp = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            val newYPx = (localYDp * density).roundToInt()
                                .coerceIn(resolvedBounds.constraints.minY, resolvedBounds.constraints.maxY)
                            playbackManager.preferencesRepository.setOverlayPosition(
                                x = resolvedBounds.x,
                                y = newYPx,
                                orientation = activeOrientation
                            )
                        }
                    },
                    valueRange = minYDp..maxYDp,
                    modifier = Modifier.fillMaxWidth().testTag("y_position_slider")
                )

                // Responsive Micro-Nudge Row for Y
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(-20, -5, +5, +20).forEach { deltaDp ->
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    val deltaPx = (deltaDp * density).roundToInt()
                                    playbackManager.preferencesRepository.nudgeOverlayPosition(
                                        deltaX = 0,
                                        deltaY = deltaPx,
                                        orientation = activeOrientation,
                                        constraints = resolvedBounds.constraints,
                                        fallbackProfile = activeProfile
                                    )
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (deltaDp > 0) "+${deltaDp}dp" else "${deltaDp}dp",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Reset to Center
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            val centered = HudPositioningEngine.computeCenteredProfile(
                                widthPx = resolvedBounds.widthPx,
                                heightPx = resolvedBounds.heightPx,
                                geometry = displayGeometry,
                                minBackdropHeightPx = minBackdropHeightPx
                            )
                            playbackManager.preferencesRepository.setOverlayPosition(
                                x = centered.x,
                                y = centered.y,
                                orientation = activeOrientation
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reset to Center", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    val sectionTheme: @Composable () -> Unit = {
        // SECTION 5: THEME & MATERIAL STYLE
        SettingsAccordionCard(
            title = "Theme & Material Style",
            subtitle = if (userPreferences.useWallpaperDynamicColor) "Material You (Wallpaper)" else userPreferences.themePreset.title,
            icon = Icons.Default.Palette,
            isExpanded = "theme" in expandedSections,
            onToggle = { toggleSection("theme") }
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                // Appearance Mode: Dark-Only Experience
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("theme_mode_dark"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DarkMode,
                            contentDescription = "Dark Mode",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Appearance: Dark Experience",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "LyricBar is crafted for an immersive cinematic dark visual experience",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

                // Toggle: Wallpaper Dynamic Color (Material You)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Wallpaper Dynamic Colors (Material You)",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Derives theme colors from your device wallpaper" else "Requires Android 12+ (Falling back to curated palette)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = userPreferences.useWallpaperDynamicColor,
                        onCheckedChange = { dynamic ->
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setUseWallpaperDynamicColor(dynamic)
                            }
                        },
                        modifier = Modifier.testTag("wallpaper_dynamic_color_switch")
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Curated Pastel Palettes",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (userPreferences.useWallpaperDynamicColor) "Active when wallpaper dynamic color is turned off" else "Currently applied theme palette",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                val arePalettesEnabled = !userPreferences.useWallpaperDynamicColor

                // 2-Column Curated Themes Swatch Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ThemePresetCard(
                        title = "Rose Quartz",
                        subtitle = "Signature Theme",
                        primaryColor = RoseQuartzPrimary,
                        secondaryColor = RoseQuartzSecondary,
                        surfaceColor = RoseQuartzDarkSurface,
                        isSelected = arePalettesEnabled && (userPreferences.themePreset == ThemePreset.ROSE_QUARTZ || userPreferences.themePreset == ThemePreset.OBSIDIAN_MINT),
                        isEnabled = arePalettesEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setThemePreset(ThemePreset.ROSE_QUARTZ)
                                playbackManager.preferencesRepository.setUseWallpaperDynamicColor(false)
                            }
                        }
                    )
                    ThemePresetCard(
                        title = "Lavender Mist",
                        subtitle = "Dreamy & Elegant",
                        primaryColor = LavenderMistPrimary,
                        secondaryColor = LavenderMistSecondary,
                        surfaceColor = LavenderMistDarkSurface,
                        isSelected = arePalettesEnabled && (userPreferences.themePreset == ThemePreset.LAVENDER_MIST || userPreferences.themePreset == ThemePreset.MIDNIGHT_AZURE),
                        isEnabled = arePalettesEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setThemePreset(ThemePreset.LAVENDER_MIST)
                                playbackManager.preferencesRepository.setUseWallpaperDynamicColor(false)
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ThemePresetCard(
                        title = "Peach Ember",
                        subtitle = "Warm Cinematic",
                        primaryColor = PeachEmberPrimary,
                        secondaryColor = PeachEmberSecondary,
                        surfaceColor = PeachEmberDarkSurface,
                        isSelected = arePalettesEnabled && (userPreferences.themePreset == ThemePreset.PEACH_EMBER || userPreferences.themePreset == ThemePreset.SUNSET_AMBER),
                        isEnabled = arePalettesEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setThemePreset(ThemePreset.PEACH_EMBER)
                                playbackManager.preferencesRepository.setUseWallpaperDynamicColor(false)
                            }
                        }
                    )
                    ThemePresetCard(
                        title = "Sage Serenity",
                        subtitle = "Calm Understated",
                        primaryColor = SageSerenityPrimary,
                        secondaryColor = SageSerenitySecondary,
                        surfaceColor = SageSerenityDarkSurface,
                        isSelected = arePalettesEnabled && (userPreferences.themePreset == ThemePreset.SAGE_SERENITY || userPreferences.themePreset == ThemePreset.CYBER_VIOLET),
                        isEnabled = arePalettesEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setThemePreset(ThemePreset.SAGE_SERENITY)
                                playbackManager.preferencesRepository.setUseWallpaperDynamicColor(false)
                            }
                        }
                    )
                }
            }
        }
    }

    val sectionTypography: @Composable () -> Unit = {
        // SECTION 6: TYPOGRAPHY
        val activeCustomRecord = remember(livePreferences.selectedCustomFontId, customFonts) {
            val id = livePreferences.selectedCustomFontId
            if (id != null && customFontRepo.resolveValidFontFile(id) != null) {
                customFonts.firstOrNull { it.id == id }
            } else null
        }
        val activeFontDisplayTitle = activeCustomRecord?.displayName ?: userPreferences.selectedFont.title
        SettingsAccordionCard(
            title = "Typography",
            subtitle = if (userPreferences.hudBoldLyrics) {
                "$activeFontDisplayTitle • Bold • ${userPreferences.overlayTextSizeSp.toInt()} sp"
            } else {
                "$activeFontDisplayTitle • ${userPreferences.overlayTextSizeSp.toInt()} sp"
            },
            icon = Icons.Default.FontDownload,
            isExpanded = SettingsAccordionState.SECTION_TYPOGRAPHY in expandedSections,
            onToggle = { toggleSection(SettingsAccordionState.SECTION_TYPOGRAPHY) }
        ) {
            var isFontListExpanded by remember { mutableStateOf(false) }
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Overlay Typography Scale (${userPreferences.overlayTextSizeSp.toInt()} sp)",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Granular small-unit scale (6sp to 24sp) for refined, subtle floating lyrics.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                var localFontSize by remember(userPreferences.overlayTextSizeSp) {
                    mutableFloatStateOf(userPreferences.overlayTextSizeSp)
                }

                Slider(
                    value = localFontSize,
                    onValueChange = { localFontSize = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            playbackManager.preferencesRepository.setOverlayTextSize(localFontSize)
                        }
                    },
                    valueRange = 6f..24f,
                    steps = 17,
                    modifier = Modifier.fillMaxWidth().testTag("font_size_slider")
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Granular Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(6, 8, 10, 12, 14, 18).forEach { spVal ->
                        val isSelected = userPreferences.overlayTextSizeSp.toInt() == spVal
                        FilledTonalButton(
                            onClick = {
                                coroutineScope.launch {
                                    playbackManager.preferencesRepository.setOverlayTextSize(spVal.toFloat())
                                }
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 6.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "${spVal}sp",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Clip
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(14.dp))

                // Text Opacity / Transparency
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Text Transparency & Opacity",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val opacityPct = (userPreferences.overlayTextOpacity * 100).roundToInt()
                    Text(
                        text = "$opacityPct% Opacity",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "Adjust text opacity for sheer, see-through elegance or vivid contrast",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                var localOpacity by remember(userPreferences.overlayTextOpacity) {
                    mutableFloatStateOf(userPreferences.overlayTextOpacity)
                }

                Slider(
                    value = localOpacity,
                    onValueChange = { localOpacity = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            playbackManager.preferencesRepository.setOverlayTextOpacity(localOpacity)
                        }
                    },
                    valueRange = 0.2f..1.0f,
                    steps = 15,
                    modifier = Modifier.fillMaxWidth().testTag("text_opacity_slider")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "30% Sheer" to 0.3f,
                        "50% Subtle" to 0.5f,
                        "75% Balanced" to 0.75f,
                        "100% Solid" to 1.0f
                    ).forEach { (label, opacityVal) ->
                        val isSelected = abs(userPreferences.overlayTextOpacity - opacityVal) < 0.08f
                        FilledTonalButton(
                            onClick = {
                                coroutineScope.launch {
                                    playbackManager.preferencesRepository.setOverlayTextOpacity(opacityVal)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(14.dp))

                // Bold HUD Lyrics Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Bold Lyrics",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Use a bolder typeface for floating HUD lyrics.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = userPreferences.hudBoldLyrics,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setHudBoldLyrics(enabled)
                            }
                        },
                        modifier = Modifier.testTag("settings_hud_bold_lyrics_switch")
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(14.dp))

                // Font Family Selector
                Text(
                    text = "Font Family Style",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Choose from Google Fonts, System Default, or your imported TTF/OTF fonts",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Import Actions (Individual Font + ZIP Archive)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            singleFontPickerLauncher.launch(
                                arrayOf(
                                    "font/ttf",
                                    "font/otf",
                                    "application/x-font-ttf",
                                    "application/vnd.ms-opentype",
                                    "*/*"
                                )
                            )
                        },
                        enabled = !isSingleFontImporting,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_import_font_button"),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        if (isSingleFontImporting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.UploadFile,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Import Font",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            zipFontPickerLauncher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream",
                                    "*/*"
                                )
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("settings_import_zip_fonts_button"),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderZip,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Import ZIP",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                singleFontFeedback?.let { (message, isError) ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = if (isError) {
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = message,
                                fontSize = 11.5.sp,
                                color = if (isError) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { singleFontFeedback = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                if (customFonts.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    val allCustomFontIds = remember(customFonts) { customFonts.mapTo(HashSet()) { it.id } }
                    val allSelectedForDelete =
                        customFonts.isNotEmpty() && selectedFontIdsForDeletion.size == allCustomFontIds.size

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isFontMultiSelectMode) {
                                "${selectedFontIdsForDeletion.size} of ${customFonts.size} selected"
                            } else {
                                "Imported Fonts (${customFonts.size})"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(
                            onClick = {
                                if (isFontMultiSelectMode) {
                                    isFontMultiSelectMode = false
                                    selectedFontIdsForDeletion = emptySet()
                                } else {
                                    isFontMultiSelectMode = true
                                    selectedFontIdsForDeletion = emptySet()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier
                                .heightIn(min = 28.dp)
                                .testTag("custom_fonts_select_mode_btn")
                        ) {
                            Text(
                                text = if (isFontMultiSelectMode) "Cancel" else "Select",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (isFontMultiSelectMode) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    selectedFontIdsForDeletion = if (allSelectedForDelete) {
                                        emptySet()
                                    } else {
                                        allCustomFontIds
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .testTag("custom_fonts_select_all_btn")
                            ) {
                                Text(
                                    text = if (allSelectedForDelete) "Deselect All" else "Select All",
                                    fontSize = 11.5.sp,
                                    maxLines = 1
                                )
                            }
                            Button(
                                onClick = {
                                    val targets = customFonts.filter { it.id in selectedFontIdsForDeletion }
                                    if (targets.isNotEmpty()) {
                                        fontsPendingBatchDeletion = targets
                                    }
                                },
                                enabled = selectedFontIdsForDeletion.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .testTag("custom_fonts_delete_selected_btn")
                            ) {
                                Text(
                                    text = if (allSelectedForDelete && customFonts.size > 1) {
                                        "Delete All (${selectedFontIdsForDeletion.size})"
                                    } else {
                                        "Delete (${selectedFontIdsForDeletion.size})"
                                    },
                                    fontSize = 11.5.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        customFonts.forEach { customRecord ->
                            val isSelected = activeCustomRecord?.id == customRecord.id
                            val isCheckedForDelete = customRecord.id in selectedFontIdsForDeletion
                            val fontFile = customFontRepo.resolveFontFile(customRecord)
                            val previewFamily = remember(customRecord.id, fontFile.absolutePath) {
                                resolveLyricsFontFamily(
                                    com.example.data.preferences.FontOption.POPPINS,
                                    fontFile.absolutePath
                                )
                            }
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        if (isFontMultiSelectMode) {
                                            selectedFontIdsForDeletion = if (isCheckedForDelete) {
                                                selectedFontIdsForDeletion - customRecord.id
                                            } else {
                                                selectedFontIdsForDeletion + customRecord.id
                                            }
                                        } else {
                                            coroutineScope.launch {
                                                playbackManager.preferencesRepository.setSelectedCustomFontId(customRecord.id)
                                            }
                                        }
                                    }
                                    .border(
                                        width = if (isFontMultiSelectMode && isCheckedForDelete) 2.dp else if (!isFontMultiSelectMode && isSelected) 2.dp else 1.dp,
                                        color = when {
                                            isFontMultiSelectMode && isCheckedForDelete -> MaterialTheme.colorScheme.error
                                            !isFontMultiSelectMode && isSelected -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.outlineVariant
                                        },
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .testTag("custom_font_item_${customRecord.id}"),
                                color = when {
                                    isFontMultiSelectMode && isCheckedForDelete -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                                    isSelected -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> MaterialTheme.colorScheme.surfaceContainer
                                },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = if (isFontMultiSelectMode) 4.dp else 14.dp,
                                            end = 6.dp,
                                            top = 8.dp,
                                            bottom = 8.dp
                                        ),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isFontMultiSelectMode) {
                                        Checkbox(
                                            checked = isCheckedForDelete,
                                            onCheckedChange = { checked ->
                                                selectedFontIdsForDeletion = if (checked) {
                                                    selectedFontIdsForDeletion + customRecord.id
                                                } else {
                                                    selectedFontIdsForDeletion - customRecord.id
                                                }
                                            },
                                            modifier = Modifier.testTag("custom_font_checkbox_${customRecord.id}")
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = customRecord.displayName,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                                                fontFamily = previewFamily,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                            ) {
                                                Text(
                                                    text = "Custom • ${customRecord.extension.uppercase(java.util.Locale.US)}",
                                                    fontSize = 9.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = "The quick brown fox jumps over the lazy dog",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontFamily = previewFamily,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (!isFontMultiSelectMode) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.CheckCircle,
                                                    contentDescription = "Selected",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = { fontPendingDeletion = customRecord },
                                                modifier = Modifier.testTag("delete_custom_font_${customRecord.id}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.DeleteOutline,
                                                    contentDescription = "Delete custom font",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Built-in Fonts",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                } else {
                    Spacer(modifier = Modifier.height(10.dp))
                }

                val allFonts = com.example.data.preferences.FontOption.entries
                val visibleFonts = if (isFontListExpanded) allFonts else allFonts.take(4)
                val selectedFont = userPreferences.selectedFont
                val isSelectedFontHidden = activeCustomRecord == null && !isFontListExpanded && selectedFont !in visibleFonts

                if (isSelectedFontHidden) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { isFontListExpanded = true },
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Selected: ${selectedFont.title}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Tap to reveal in list",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    visibleFonts.forEach { fontOption ->
                        val isSelected = activeCustomRecord == null && selectedFont == fontOption
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setSelectedFont(fontOption)
                                    }
                                }
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(10.dp)
                                ),
                            color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = fontOption.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                                        fontFamily = com.example.ui.theme.getFontFamily(fontOption)
                                    )
                                    Text(
                                        text = "The quick brown fox jumps over the lazy dog",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = com.example.ui.theme.getFontFamily(fontOption)
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { isFontListExpanded = !isFontListExpanded },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = if (isFontListExpanded) "Show less" else "Show more fonts",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = if (isFontListExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isFontListExpanded) "Show less fonts" else "Show more fonts",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    val sectionLyricsProvider: @Composable () -> Unit = {
        // SECTION 7: LYRICS PROVIDER
        SettingsAccordionCard(
            title = "Lyrics Provider",
            subtitle = "${selectedProvider.displayName} • Default lyrics source",
            icon = Icons.Default.LibraryMusic,
            isExpanded = "lyrics_provider" in expandedSections,
            onToggle = { toggleSection("lyrics_provider") },
            modifier = Modifier.testTag("settings_lyrics_provider_section")
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                val savedProviderOrder = remember(livePreferences.lyricsProviderOrder) {
                    LyricsProviderPreference.normalizeProviderOrderEntries(livePreferences.lyricsProviderOrder)
                }
                val isDefaultProviderOrder =
                    savedProviderOrder == LyricsProviderPreference.DEFAULT_PROVIDER_ORDER

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Default Lyrics Provider",
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (!isDefaultProviderOrder) {
                        TextButton(
                            onClick = {
                                playbackManager.setLyricsProviderOrder(LyricsProviderPreference.DEFAULT_PROVIDER_ORDER)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier
                                .heightIn(min = 28.dp)
                                .testTag("settings_provider_restore_default_btn")
                        ) {
                            Text(
                                text = "Restore Default",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Text(
                    text = "Tap to choose a source, or long-press and drag providers to set Automatic search priority (#1 first)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                UnifiedLyricsProviderList(
                    selectedProvider = selectedProvider,
                    savedOrder = savedProviderOrder,
                    scrollState = settingsScrollState,
                    viewportBoundsInRoot = viewportBoundsInRoot,
                    onSelectProvider = { option ->
                        playbackManager.setLyricsProvider(option)
                    },
                    onCommitOrder = { updatedOrder ->
                        playbackManager.setLyricsProviderOrder(updatedOrder)
                    }
                )
            }
        }
    }

    val sectionData: @Composable () -> Unit = {
        // SECTION 8: PLAYBACK & DATA MANAGEMENT
        SettingsAccordionCard(
            title = "Automation & Cache",
            subtitle = "Auto-sync, cache & storage",
            icon = Icons.Default.Storage,
            isExpanded = "data" in expandedSections,
            onToggle = { toggleSection("data") }
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Show On Playback",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Automatically opens overlay when music starts",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = userPreferences.autoShowOnPlayback,
                        onCheckedChange = { autoShow ->
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setAutoShowOnPlayback(autoShow)
                            }
                        },
                        modifier = Modifier.testTag("settings_auto_show_switch")
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Toggle: Auto-Fetch Lyrics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Fetch Lyrics",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Automatically search online lyrics when track changes",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = userPreferences.autoFetchLyrics,
                        onCheckedChange = { autoFetch ->
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setAutoFetchLyrics(autoFetch)
                            }
                        },
                        modifier = Modifier.testTag("settings_auto_fetch_switch")
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Toggle: Playback History
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Playback History",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Record played tracks to your playback history",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = livePreferences.playbackHistoryEnabled,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setPlaybackHistoryEnabled(enabled)
                            }
                        },
                        modifier = Modifier.testTag("settings_playback_history_switch")
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                CompositeLyricsRepository.getInstance(context).clearCache()
                                showCacheClearedToast = true
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("clear_cache_button"),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Clear Cache",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                CompositeLyricsRepository.getInstance(context).clearHistory()
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("clear_history_button"),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Clear History",
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (showCacheClearedToast) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Local lyrics cache cleared successfully.",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }

    val sectionBackupRestore: @Composable () -> Unit = {
        SettingsAccordionCard(
            title = stringResource(R.string.backup_restore_title),
            subtitle = stringResource(R.string.backup_restore_subtitle),
            icon = Icons.Default.Backup,
            isExpanded = SettingsAccordionState.SECTION_BACKUP_RESTORE in expandedSections,
            onToggle = { toggleSection(SettingsAccordionState.SECTION_BACKUP_RESTORE) }
        ) {
            BackupRestoreSettingsContent(
                onOpenCreateBackup = { activeBackupSubScreen = BackupSubScreen.CREATE },
                onOpenRestoreBackup = { activeBackupSubScreen = BackupSubScreen.RESTORE }
            )
        }
    }

    val sectionFeedback: @Composable () -> Unit = {
        SettingsAccordionCard(
            title = stringResource(R.string.feedback_centre_title),
            subtitle = stringResource(R.string.feedback_centre_subtitle),
            icon = Icons.Default.Feedback,
            isExpanded = SettingsAccordionState.SECTION_FEEDBACK in expandedSections,
            onToggle = { toggleSection(SettingsAccordionState.SECTION_FEEDBACK) }
        ) {
            FeedbackSettingsContent(
                onOpenBugReport = { activeFeedbackSubScreen = FeedbackTab.BUG_REPORT },
                onOpenFeatureRequest = { activeFeedbackSubScreen = FeedbackTab.FEATURE_REQUEST }
            )
        }
    }

    val sectionAbout: @Composable () -> Unit = {
        // SECTION 9: ABOUT & DEVELOPER
        SettingsAccordionCard(
            title = "About & Developer",
            subtitle = "Version ${com.example.BuildConfig.VERSION_NAME} (Code ${com.example.BuildConfig.VERSION_CODE})",
            icon = Icons.Default.Info,
            isExpanded = "about" in expandedSections,
            onToggle = { toggleSection("about") }
        ) {
            AboutDeveloperContent(
                autoUpdateChecksEnabled = livePreferences.autoUpdateChecksEnabled,
                lastUpdateCheckTimestampMs = livePreferences.lastUpdateCheckTimestampMs,
                notificationsAllowed = notificationsAllowed,
                pendingReleaseVersionName = cachedAvailableRelease?.cleanVersionName,
                onAutoUpdateChecksChanged = { enabled ->
                    coroutineScope.launch {
                        playbackManager.preferencesRepository.setAutoUpdateChecksEnabled(enabled)
                        com.example.data.updater.UpdateCheckScheduler.syncPeriodicSchedule(
                            context = context,
                            enabled = enabled
                        )
                    }
                },
                onCheckForUpdatesClick = {
                    showUpdateDialog = true
                    updateManager.checkForUpdates(isUserInitiated = true)
                },
                onOpenFeedbackClick = { activeFeedbackSubScreen = FeedbackTab.BUG_REPORT }
            )
        }
    }

    when (val feedbackTab = activeFeedbackSubScreen) {
        FeedbackTab.BUG_REPORT, FeedbackTab.FEATURE_REQUEST -> {
            FeedbackCentreScreen(
                initialTab = feedbackTab,
                hasBottomNavPill = hasBottomNavPill,
                onNavigateBack = { activeFeedbackSubScreen = null },
                modifier = modifier
            )
            return
        }
        null -> Unit
    }

    when (activeBackupSubScreen) {
        BackupSubScreen.CREATE -> {
            CreateBackupScreen(
                preferencesRepository = playbackManager.preferencesRepository,
                hasBottomNavPill = hasBottomNavPill,
                onNavigateBack = { activeBackupSubScreen = null },
                modifier = modifier
            )
            return
        }
        BackupSubScreen.RESTORE -> {
            RestoreBackupScreen(
                preferencesRepository = playbackManager.preferencesRepository,
                hasBottomNavPill = hasBottomNavPill,
                onNavigateBack = { activeBackupSubScreen = null },
                modifier = modifier
            )
            return
        }
        null -> Unit
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.06f))
    ) {
        val isTwoColumn = maxWidth >= 560.dp
        val isWideTwoColumn = maxWidth >= 680.dp
        val isCompactLandscapeHeight = maxHeight < 480.dp && maxWidth > maxHeight

        Column(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    val top = coords.positionInRoot().y
                    viewportBoundsInRoot = top to (top + coords.size.height.toFloat())
                }
                .then(if (hasBottomNavPill) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
                .verticalScroll(settingsScrollState)
                .padding(
                    horizontal = when {
                        isWideTwoColumn -> 24.dp
                        isTwoColumn -> 16.dp
                        else -> 20.dp
                    },
                    vertical = if (isCompactLandscapeHeight) 6.dp else 12.dp
                )
                .padding(bottom = if (hasBottomNavPill) 120.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = if (isTwoColumn) 1080.dp else 640.dp)
            ) {
                val showChipInlineInHeader = isTwoColumn && fontScale < 1.35f

                // Header with title, subtitle, HUD orientation chip, and Expand/Collapse All
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = if (isTwoColumn) 2.dp else 8.dp,
                            bottom = when {
                                isCompactLandscapeHeight && showChipInlineInHeader -> 10.dp
                                showChipInlineInHeader -> 14.dp
                                else -> 4.dp
                            }
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Preferences, appearance & overlays",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (showChipInlineInHeader) {
                            HudOrientationModeChip(
                                snapshot = orientationInfo,
                                onClick = { showOrientationInfoDialog = true }
                            )
                        }

                        TextButton(
                            onClick = {
                                expandedSections = SettingsAccordionState.toggleAllSections(expandedSections)
                            }
                        ) {
                            Text(
                                text = if (SettingsAccordionState.isAllExpanded(expandedSections)) "Collapse All" else "Expand All",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                if (!showChipInlineInHeader) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        HudOrientationModeChip(
                            snapshot = orientationInfo,
                            onClick = { showOrientationInfoDialog = true }
                        )
                    }
                }

                if (isTwoColumn) {
                    // Two-column landscape/tablet composition (single shared vertical scroll)
                    val columnSpacing = if (isCompactLandscapeHeight) 10.dp else 14.dp
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(if (isWideTwoColumn) 16.dp else 12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(columnSpacing)
                        ) {
                            sectionOverlay()
                            sectionHudBackdrop()
                            sectionAnimation()
                            sectionSizeAlign()
                            sectionPosition()
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(columnSpacing)
                        ) {
                            sectionLyricsProvider()
                            sectionTheme()
                            sectionTypography()
                            sectionData()
                            sectionBackupRestore()
                            sectionFeedback()
                            sectionAbout()
                        }
                    }
                } else {
                    // Single-column composition (Portrait & compact landscape < 660dp)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        sectionOverlay()
                        sectionHudBackdrop()
                        sectionAnimation()
                        sectionSizeAlign()
                        sectionPosition()
                        sectionTheme()
                        sectionTypography()
                        sectionLyricsProvider()
                        sectionData()
                        sectionBackupRestore()
                        sectionFeedback()
                        sectionAbout()
                    }
                }

                Spacer(modifier = Modifier.height(if (hasBottomNavPill) 32.dp else 8.dp))
            }
        }

        if (showUpdateDialog) {
            UpdateDialog(
                updateManager = updateManager,
                onDismiss = { showUpdateDialog = false }
            )
        }

        if (showOrientationInfoDialog) {
            HudOrientationInfoDialog(
                snapshot = orientationInfo,
                onDismiss = { showOrientationInfoDialog = false }
            )
        }

        fontPendingDeletion?.let { pending ->
            val isCurrentlyActive = livePreferences.selectedCustomFontId == pending.id
            AlertDialog(
                onDismissRequest = { fontPendingDeletion = null },
                title = { Text("Delete Custom Font?") },
                text = {
                    Text(
                        if (isCurrentlyActive) {
                            "'${pending.displayName}' is currently selected. Deleting it will remove the imported file and switch lyrics back to '${livePreferences.selectedFont.title}'."
                        } else {
                            "Delete '${pending.displayName}' (${pending.originalFileName}) from LyricBar?"
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = pending
                            fontPendingDeletion = null
                            coroutineScope.launch {
                                val filePath = customFontRepo.resolveFontFile(target).absolutePath
                                MinimalOverlayView.evictFileTypefaceCache(filePath)
                                evictCustomFontFamilyCache(filePath)
                                customFontRepo.deleteCustomFont(target.id)
                            }
                        }
                    ) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { fontPendingDeletion = null }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (fontsPendingBatchDeletion.isNotEmpty()) {
            val pendingTargets = fontsPendingBatchDeletion
            val pendingIds = pendingTargets.mapTo(HashSet()) { it.id }
            val includesActive = livePreferences.selectedCustomFontId in pendingIds
            val previewNames = pendingTargets.take(5).joinToString(", ") { it.displayName }
            val overflowSuffix = if (pendingTargets.size > 5) {
                " and ${pendingTargets.size - 5} more"
            } else {
                ""
            }
            AlertDialog(
                onDismissRequest = { fontsPendingBatchDeletion = emptyList() },
                title = {
                    Text(
                        if (pendingTargets.size == 1) {
                            "Delete Custom Font?"
                        } else {
                            "Delete ${pendingTargets.size} Custom Fonts?"
                        }
                    )
                },
                text = {
                    Text(
                        buildString {
                            append("Delete $previewNames$overflowSuffix from LyricBar?")
                            if (includesActive) {
                                append(" Your currently selected custom font is included, so lyrics will switch back to '${livePreferences.selectedFont.title}'.")
                            }
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val targets = pendingTargets
                            fontsPendingBatchDeletion = emptyList()
                            isFontMultiSelectMode = false
                            selectedFontIdsForDeletion = emptySet()
                            coroutineScope.launch {
                                targets.forEach { target ->
                                    val filePath = customFontRepo.resolveFontFile(target).absolutePath
                                    MinimalOverlayView.evictFileTypefaceCache(filePath)
                                    evictCustomFontFamilyCache(filePath)
                                }
                                val res = customFontRepo.deleteCustomFonts(targets.map { it.id })
                                singleFontFeedback = if (res.deletedRecords.isNotEmpty()) {
                                    "Deleted ${res.deletedRecords.size} custom font(s)." to false
                                } else {
                                    "Selected custom fonts were already removed." to false
                                }
                            }
                        }
                    ) {
                        Text("Delete (${pendingTargets.size})", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { fontsPendingBatchDeletion = emptyList() }) {
                        Text("Cancel")
                    }
                }
            )
        }

        val currentZipState = zipDialogState
        if (currentZipState != null) {
            com.example.ui.components.CustomFontZipImportDialog(
                uiState = currentZipState,
                onToggleEntry = { entryId ->
                    val sel = zipDialogState as? com.example.ui.components.ZipFontDialogUiState.Selection ?: return@CustomFontZipImportDialog
                    val updatedIds = if (entryId in sel.selectedEntryIds) {
                        sel.selectedEntryIds - entryId
                    } else {
                        sel.selectedEntryIds + entryId
                    }
                    zipDialogState = sel.copy(selectedEntryIds = updatedIds)
                },
                onSelectAll = {
                    val sel = zipDialogState as? com.example.ui.components.ZipFontDialogUiState.Selection ?: return@CustomFontZipImportDialog
                    val allSelectableIds = sel.scanResult.discoveredFonts
                        .filter { it.isSelectable }
                        .map { it.entryId }
                        .toSet()
                    zipDialogState = sel.copy(selectedEntryIds = allSelectableIds)
                },
                onDeselectAll = {
                    val sel = zipDialogState as? com.example.ui.components.ZipFontDialogUiState.Selection ?: return@CustomFontZipImportDialog
                    zipDialogState = sel.copy(selectedEntryIds = emptySet())
                },
                onImportSelected = {
                    val sel = zipDialogState as? com.example.ui.components.ZipFontDialogUiState.Selection ?: return@CustomFontZipImportDialog
                    val chosen = sel.scanResult.discoveredFonts.filter { it.entryId in sel.selectedEntryIds }
                    if (chosen.isEmpty()) return@CustomFontZipImportDialog
                    zipOperationJob?.cancel()
                    zipDialogState = com.example.ui.components.ZipFontDialogUiState.Importing(
                        archiveName = sel.scanResult.archiveName,
                        progress = null
                    )
                    zipOperationJob = coroutineScope.launch {
                        val batchResult = customFontZipImporter.importSelectedEntries(
                            sessionId = sel.scanResult.sessionId,
                            selectedEntries = chosen
                        ) { prog ->
                            zipDialogState = com.example.ui.components.ZipFontDialogUiState.Importing(
                                archiveName = sel.scanResult.archiveName,
                                progress = prog
                            )
                        }
                        if (batchResult.imported.size == 1 && batchResult.failed.isEmpty()) {
                            playbackManager.preferencesRepository.setSelectedCustomFontId(batchResult.imported.first().id)
                        }
                        zipDialogState = com.example.ui.components.ZipFontDialogUiState.ImportSummary(
                            archiveName = sel.scanResult.archiveName,
                            result = batchResult
                        )
                    }
                },
                onCancelOrDismiss = {
                    zipOperationJob?.cancel()
                    zipOperationJob = null
                    val scanSessionId = (currentZipState as? com.example.ui.components.ZipFontDialogUiState.Selection)
                        ?.scanResult
                        ?.sessionId
                    if (scanSessionId != null) {
                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            customFontZipImporter.cleanupSession(scanSessionId)
                        }
                    }
                    zipDialogState = null
                }
            )
        }

        val pickerTarget = activeColorPickerTarget
        if (pickerTarget != null) {
            val initialColor = when (pickerTarget) {
                HudColorTarget.ACTIVE_LYRIC -> livePreferences.hudActiveLyricColor
                HudColorTarget.INACTIVE_LYRIC -> livePreferences.hudInactiveLyricColor
            }
            HudColorPickerDialog(
                target = pickerTarget,
                initialColorArgb = initialColor,
                onDismiss = { activeColorPickerTarget = null },
                onColorConfirmed = { pickedArgb ->
                    coroutineScope.launch {
                        when (pickerTarget) {
                            HudColorTarget.ACTIVE_LYRIC ->
                                playbackManager.preferencesRepository.setHudActiveLyricColor(pickedArgb)
                            HudColorTarget.INACTIVE_LYRIC ->
                                playbackManager.preferencesRepository.setHudInactiveLyricColor(pickedArgb)
                        }
                        playbackManager.preferencesRepository.saveCustomHudColor(pickedArgb)
                    }
                    activeColorPickerTarget = null
                }
            )
        }

        if (showDynamicAccentInfoDialog) {
            Dialog(
                onDismissRequest = {
                    dynamicAccentInfoJob?.cancel()
                    dynamicAccentInfoJob = null
                    showDynamicAccentInfoDialog = false
                },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                FrostedCard(
                    modifier = Modifier
                        .fillMaxWidth(0.86f)
                        .widthIn(max = 360.dp)
                        .testTag("settings_dynamic_accent_info_dialog"),
                    shape = RoundedCornerShape(20.dp),
                    backgroundAlpha = 0.90f,
                    borderAlpha = 0.35f
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Dynamic Accent Colour",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = DYNAMIC_ACCENT_INFO_MESSAGE,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

internal const val DYNAMIC_ACCENT_INFO_MESSAGE: String =
    "Dynamic colours are extracted from album artwork. Results may vary between songs, and some artwork may not provide a perfect colour."
internal const val DYNAMIC_ACCENT_SNACKBAR_DURATION_MS: Long = 3000L

@Composable
private fun AnimationStylePreview(
    styleName: String,
    textOpacity: Float = 1.0f,
    typewriterSpeed: Float = 1.0f,
    smoothTransitionSpeed: Float = 1.0f,
    fadeSpeed: Float = 1.0f,
    blurRevealSpeed: Float = 1.0f,
    karaokeSpeed: Float = 1.0f
) {
    var previewTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(styleName, typewriterSpeed, karaokeSpeed) {
        val baseDelay = when (styleName) {
            "TYPEWRITER" -> (120 / typewriterSpeed.coerceIn(0.3f, 3.0f)).toLong()
            "KARAOKE" -> (120 / karaokeSpeed.coerceIn(0.3f, 3.0f)).toLong()
            else -> 120L
        }
        while (true) {
            kotlinx.coroutines.delay(baseDelay)
            previewTick++
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "LIVE ANIMATION PREVIEW",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            val sampleWords = listOf("Caught", "in", "a", "landslide,", "no", "escape", "from", "reality")
            when (styleName) {
                "KARAOKE" -> {
                    val activeWordIndex = (previewTick / 5) % (sampleWords.size + 4)
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        sampleWords.forEachIndexed { idx, w ->
                            val isSung = idx < activeWordIndex
                            val isCurrent = idx == activeWordIndex
                            val wordColor = when {
                                isCurrent -> MaterialTheme.colorScheme.primary
                                isSung -> Color(MinimalOverlayView.ACTIVE_LYRIC_COLOR).copy(alpha = textOpacity)
                                else -> Color(MinimalOverlayView.INACTIVE_LYRIC_COLOR).copy(
                                    alpha = MinimalOverlayView.INACTIVE_LYRIC_ALPHA_RATIO * textOpacity
                                )
                            }
                            Text(
                                text = "$w ",
                                color = wordColor,
                                fontWeight = if (isSung || isCurrent) FontWeight.ExtraBold else FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                "SMOOTH_SLIDE" -> {
                    val sampleLines = remember {
                        listOf(
                            "Caught in a landslide, no escape from reality",
                            "Open your eyes, look up to the skies and see",
                            "I'm just a poor boy, I need no sympathy",
                            "Because I'm easy come, easy go, little high, little low"
                        )
                    }
                    var currentLineIdx by remember { mutableIntStateOf(0) }
                    var prevLineIdx by remember { mutableIntStateOf(-1) }
                    val animProgress = remember { androidx.compose.animation.core.Animatable(1f) }

                    LaunchedEffect(styleName, smoothTransitionSpeed) {
                        while (true) {
                            kotlinx.coroutines.delay(2000L)
                            prevLineIdx = currentLineIdx
                            currentLineIdx = (currentLineIdx + 1) % sampleLines.size
                            val baseDuration = 300L
                            val speed = smoothTransitionSpeed.coerceIn(0.3f, 3.0f)
                            val transDuration = (baseDuration / speed).toInt().coerceIn(100, 2000)
                            animProgress.snapTo(0f)
                            animProgress.animateTo(
                                targetValue = 1f,
                                animationSpec = androidx.compose.animation.core.tween(
                                    durationMillis = transDuration,
                                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                                )
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (animProgress.value < 1f && prevLineIdx in sampleLines.indices) {
                            val outgoingOffsetY = (-animProgress.value * 20).dp
                            val outgoingAlpha = ((1f - animProgress.value) * (textOpacity * MinimalOverlayView.OUTGOING_LYRIC_ALPHA_RATIO)).coerceIn(0f, 1f)
                            Text(
                                text = sampleLines[prevLineIdx],
                                color = Color(MinimalOverlayView.INACTIVE_LYRIC_COLOR).copy(alpha = outgoingAlpha),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.offset(y = outgoingOffsetY)
                            )
                        }

                        val incomingOffsetY = ((1f - animProgress.value) * 16).dp
                        val incomingAlpha = (animProgress.value * textOpacity).coerceIn(0f, 1f)
                        Text(
                            text = sampleLines[currentLineIdx],
                            color = Color.White.copy(alpha = incomingAlpha),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.offset(y = incomingOffsetY)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    val durationMs = (300L / smoothTransitionSpeed.coerceIn(0.3f, 3.0f)).roundToInt()
                    Text(
                        text = "Smooth transition • ${String.format(java.util.Locale.US, "%.1f×", smoothTransitionSpeed)} speed (${durationMs}ms duration)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
                "FADE" -> {
                    val sampleLines = remember {
                        listOf(
                            "Caught in a landslide, no escape from reality",
                            "Open your eyes, look up to the skies and see",
                            "I'm just a poor boy, I need no sympathy",
                            "Because I'm easy come, easy go, little high, little low"
                        )
                    }
                    var currentLineIdx by remember { mutableIntStateOf(0) }
                    var prevLineIdx by remember { mutableIntStateOf(-1) }
                    val animProgress = remember { androidx.compose.animation.core.Animatable(1f) }

                    LaunchedEffect(styleName, fadeSpeed) {
                        while (true) {
                            kotlinx.coroutines.delay(2200L)
                            prevLineIdx = currentLineIdx
                            currentLineIdx = (currentLineIdx + 1) % sampleLines.size
                            val baseDuration = 360L
                            val speed = fadeSpeed.coerceIn(0.3f, 3.0f)
                            val transDuration = (baseDuration / speed).toInt().coerceIn(100, 2000)
                            animProgress.snapTo(0f)
                            animProgress.animateTo(
                                targetValue = 1f,
                                animationSpec = androidx.compose.animation.core.tween(
                                    durationMillis = transDuration,
                                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                                )
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (animProgress.value < 1f && prevLineIdx in sampleLines.indices) {
                            val outgoingAlpha = ((1f - animProgress.value) * textOpacity).coerceIn(0f, 1f)
                            Text(
                                text = sampleLines[prevLineIdx],
                                color = Color.White.copy(alpha = outgoingAlpha),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }

                        val incomingAlpha = (animProgress.value * textOpacity).coerceIn(0f, 1f)
                        Text(
                            text = sampleLines[currentLineIdx],
                            color = Color.White.copy(alpha = incomingAlpha),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    val durationMs = (360L / fadeSpeed.coerceIn(0.3f, 3.0f)).roundToInt()
                    Text(
                        text = "Pure fade transition • ${String.format(java.util.Locale.US, "%.1f×", fadeSpeed)} speed (${durationMs}ms duration)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
                "BLUR_REVEAL" -> {
                    val sampleLines = remember {
                        listOf(
                            "Caught in a landslide, no escape from reality",
                            "Open your eyes, look up to the skies and see",
                            "I'm just a poor boy, I need no sympathy",
                            "Because I'm easy come, easy go, little high, little low"
                        )
                    }
                    var currentLineIdx by remember { mutableIntStateOf(0) }
                    var prevLineIdx by remember { mutableIntStateOf(-1) }
                    val animProgress = remember { androidx.compose.animation.core.Animatable(1f) }

                    LaunchedEffect(styleName, blurRevealSpeed) {
                        while (true) {
                            kotlinx.coroutines.delay(2200L)
                            prevLineIdx = currentLineIdx
                            currentLineIdx = (currentLineIdx + 1) % sampleLines.size
                            val baseDuration = 400L
                            val speed = blurRevealSpeed.coerceIn(0.3f, 3.0f)
                            val transDuration = (baseDuration / speed).toInt().coerceIn(100, 2000)
                            animProgress.snapTo(0f)
                            animProgress.animateTo(
                                targetValue = 1f,
                                animationSpec = androidx.compose.animation.core.tween(
                                    durationMillis = transDuration,
                                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                                )
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (animProgress.value < 1f && prevLineIdx in sampleLines.indices) {
                            val outgoingAlpha = ((1f - animProgress.value) * textOpacity).coerceIn(0f, 1f)
                            Text(
                                text = sampleLines[prevLineIdx],
                                color = Color.White.copy(alpha = outgoingAlpha),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }

                        val blurDp = ((1f - animProgress.value) * 6f).dp
                        val incomingAlpha = (animProgress.value * textOpacity).coerceIn(0f, 1f)
                        val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurDp > 0.5.dp) {
                            Modifier.blur(blurDp)
                        } else {
                            Modifier
                        }

                        Text(
                            text = sampleLines[currentLineIdx],
                            color = Color.White.copy(alpha = incomingAlpha),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = blurModifier
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    val durationMs = (400L / blurRevealSpeed.coerceIn(0.3f, 3.0f)).roundToInt()
                    Text(
                        text = "Blur to sharp reveal • ${String.format(java.util.Locale.US, "%.1f×", blurRevealSpeed)} speed (${durationMs}ms duration)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
                "TYPEWRITER" -> {
                    val fullText = "Caught in a landslide, no escape from reality"
                    val charIdx = ((previewTick * 2) % (fullText.length + 15)).coerceAtMost(fullText.length)
                    val typed = fullText.substring(0, charIdx)
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = typed,
                            color = Color.White.copy(alpha = textOpacity),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        if ((previewTick / 3) % 2 == 0) {
                            Text(
                                text = "|",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                "INSTANT" -> {
                    Text(
                        text = "Caught in a landslide, no escape from reality",
                        color = Color.White.copy(alpha = textOpacity),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Immediate line replacement • Zero transition motion",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
}

@Composable
private fun ThemePresetCard(
    title: String,
    subtitle: String,
    primaryColor: Color,
    secondaryColor: Color = primaryColor,
    surfaceColor: Color,
    isSelected: Boolean,
    isEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val cardAlpha = if (isEnabled) 1.0f else 0.45f
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = isEnabled, onClick = onClick)
            .border(
                width = if (isSelected && isEnabled) 2.dp else 1.dp,
                color = if (isSelected && isEnabled) primaryColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(14.dp)
            ),
        color = surfaceColor.copy(alpha = 0.88f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(primaryColor.copy(alpha = cardAlpha), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(secondaryColor.copy(alpha = cardAlpha), CircleShape)
                    )
                }

                if (isSelected && isEnabled) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(primaryColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.Black,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = cardAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = cardAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingsAccordionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    FrostedCard(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = androidx.compose.animation.core.tween(220)),
        shape = RoundedCornerShape(18.dp),
        backgroundAlpha = 0.28f,
        shadowElevation = 3.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
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
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                val rotation by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isExpanded) 180f else 0f,
                    animationSpec = androidx.compose.animation.core.tween(220),
                    label = "accordion_chevron"
                )
                IconButton(
                    onClick = onToggle,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.rotate(rotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isExpanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                )
                content()
            }
        }
    }
}

@Composable
private fun HudOrientationModeChip(
    snapshot: HudOrientationInfoSnapshot,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = if (snapshot.orientation == HudOrientation.LANDSCAPE) {
        Icons.Default.StayCurrentLandscape
    } else {
        Icons.Default.StayCurrentPortrait
    }
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "${snapshot.modeTitle} active. Tap for details."
            }
            .testTag("hud_orientation_mode_chip"),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = snapshot.chipLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun HudOrientationInfoDialog(
    snapshot: HudOrientationInfoSnapshot,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val orientationIcon = if (snapshot.orientation == HudOrientation.LANDSCAPE) {
        Icons.Default.StayCurrentLandscape
    } else {
        Icons.Default.StayCurrentPortrait
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        FrostedCard(
            modifier = modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 420.dp)
                .padding(vertical = 16.dp)
                .testTag("hud_orientation_info_dialog"),
            shape = RoundedCornerShape(24.dp),
            backgroundAlpha = 0.90f,
            borderAlpha = 0.35f
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = orientationIcon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = snapshot.modeTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = snapshot.modeSubtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("hud_orientation_info_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = snapshot.explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = snapshot.autoSwitchNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OrientationSnapshotRow(
                            label = "Window Bounds",
                            value = snapshot.screenSizeDpLabel
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                        )
                        OrientationSnapshotRow(
                            label = "Active HUD Size",
                            value = snapshot.hudSizeDpLabel
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                        )
                        OrientationSnapshotRow(
                            label = "Active HUD Position",
                            value = snapshot.hudPositionDpLabel
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                FilledTonalButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("hud_orientation_info_got_it_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "Got it",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun OrientationSnapshotRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End
        )
    }
}

@Composable
private fun UnifiedLyricsProviderList(
    selectedProvider: LyricsProviderPreference,
    savedOrder: List<LyricsProviderPreference>,
    scrollState: ScrollState,
    viewportBoundsInRoot: Pair<Float, Float>?,
    onSelectProvider: (LyricsProviderPreference) -> Unit,
    onCommitOrder: (List<LyricsProviderPreference>) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val autoScrollEdgePx = remember(density) { with(density) { 56.dp.toPx() } }
    val autoScrollMaxStepPx = remember(density) { with(density) { 12.dp.toPx() } }

    val normalizedSaved = remember(savedOrder) {
        LyricsProviderPreference.normalizeProviderOrderEntries(savedOrder)
    }
    var pendingCommittedOrder by remember { mutableStateOf<List<LyricsProviderPreference>?>(null) }
    LaunchedEffect(normalizedSaved) {
        if (pendingCommittedOrder == normalizedSaved) {
            pendingCommittedOrder = null
        }
    }

    var draggedProvider by remember { mutableStateOf<LyricsProviderPreference?>(null) }
    var temporaryDragOrder by remember { mutableStateOf<List<LyricsProviderPreference>?>(null) }
    var dragDesiredTopY by remember { mutableFloatStateOf(0f) }
    var grabOffsetInRowPx by remember { mutableFloatStateOf(0f) }
    var latestPointerRootY by remember { mutableStateOf<Float?>(null) }
    var listCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val slotBounds = remember { mutableStateMapOf<LyricsProviderPreference, ProviderSlotBounds>() }

    val displayedOrder = temporaryDragOrder ?: pendingCommittedOrder ?: normalizedSaved
    val currentDisplayedState by rememberUpdatedState(displayedOrder)
    val currentViewportBoundsState by rememberUpdatedState(viewportBoundsInRoot)
    val isDraggingAny = draggedProvider != null

    fun updateDragPosition(activeOption: LyricsProviderPreference, pointerLocalY: Float) {
        val currentList = temporaryDragOrder ?: currentDisplayedState
        val currentIdx = currentList.indexOf(activeOption)
        val activeSlot = slotBounds[activeOption] ?: return
        val firstTop = slotBounds[currentList.first()]?.topPx ?: 0f
        val lastBottom = slotBounds[currentList.last()]?.bottomPx ?: Float.MAX_VALUE
        val clampedTop = (pointerLocalY - grabOffsetInRowPx).coerceIn(
            minimumValue = firstTop - 12f,
            maximumValue = (lastBottom - activeSlot.heightPx).coerceAtLeast(firstTop) + 12f
        )
        dragDesiredTopY = clampedTop
        val desiredCenterY = clampedTop + (activeSlot.heightPx * 0.5f)
        val targetIdx = ProviderReorderLogic.computeSwapTargetIndex(
            order = currentList,
            currentIndex = currentIdx,
            draggedCenterY = desiredCenterY,
            slotBounds = slotBounds,
            hysteresisPx = 6f
        )
        if (targetIdx != currentIdx) {
            temporaryDragOrder = ProviderReorderLogic.moveItem(currentList, currentIdx, targetIdx)
        }
    }

    LaunchedEffect(draggedProvider) {
        val activeOption = draggedProvider ?: return@LaunchedEffect
        while (isActive && draggedProvider == activeOption) {
            withFrameMillis {
                val rootY = latestPointerRootY ?: return@withFrameMillis
                val bounds = currentViewportBoundsState ?: return@withFrameMillis
                val deltaPx = ProviderReorderLogic.computeAutoScrollDeltaPx(
                    pointerRootY = rootY,
                    viewportTopRootY = bounds.first,
                    viewportBottomRootY = bounds.second,
                    edgeZonePx = autoScrollEdgePx,
                    maxStepPx = autoScrollMaxStepPx
                )
                if (abs(deltaPx) > 0.5f) {
                    val scrolled = scrollState.dispatchRawDelta(deltaPx)
                    if (abs(scrolled) > 0.1f) {
                        val coords = listCoords
                        if (coords != null && coords.isAttached) {
                            val localY = rootY - coords.positionInRoot().y
                            updateDragPosition(activeOption, localY)
                        }
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Pinned AUTO option (not draggable)
        ProviderRadioRowCard(
            option = LyricsProviderPreference.AUTO,
            isSelected = selectedProvider == LyricsProviderPreference.AUTO,
            priorityIndex = null,
            isDragging = false,
            clickEnabled = !isDraggingAny,
            onSelect = { onSelectProvider(LyricsProviderPreference.AUTO) }
        )

        // Draggable selectable providers in current priority order inside the same list
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    listCoords = coords
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startOrder = currentDisplayedState
                        val hitOption = startOrder.firstOrNull { opt ->
                            val bounds = slotBounds[opt]
                            bounds != null && down.position.y in bounds.topPx..bounds.bottomPx
                        } ?: return@awaitEachGesture

                        var abortedBeforeLongPress = false
                        var latestLocalY = down.position.y
                        var latestRootY = listCoords?.takeIf { it.isAttached }?.localToRoot(down.position)?.y

                        val longPressTriggered = try {
                            withTimeout(viewConfiguration.longPressTimeoutMillis) {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) {
                                        abortedBeforeLongPress = true
                                        break
                                    }
                                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                        abortedBeforeLongPress = true
                                        break
                                    }
                                    latestLocalY = change.position.y
                                    latestRootY = listCoords?.takeIf { it.isAttached }?.localToRoot(change.position)?.y
                                }
                            }
                            false
                        } catch (_: PointerEventTimeoutCancellationException) {
                            true
                        }

                        if (!longPressTriggered || abortedBeforeLongPress) {
                            return@awaitEachGesture
                        }

                        val startSlot = slotBounds[hitOption] ?: return@awaitEachGesture
                        val snapshotOrder = currentDisplayedState
                        grabOffsetInRowPx = latestLocalY - startSlot.topPx
                        dragDesiredTopY = startSlot.topPx
                        temporaryDragOrder = snapshotOrder
                        draggedProvider = hitOption
                        latestPointerRootY = latestRootY
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)

                        try {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null) {
                                    // Stream interrupted/cancelled: restore original snapshot without persisting
                                    draggedProvider = null
                                    temporaryDragOrder = null
                                    latestPointerRootY = null
                                    break
                                }
                                change.consume()
                                if (!change.pressed) {
                                    val committedOrder = temporaryDragOrder ?: snapshotOrder
                                    if (committedOrder != snapshotOrder) {
                                        pendingCommittedOrder = committedOrder
                                        onCommitOrder(committedOrder)
                                    }
                                    draggedProvider = null
                                    temporaryDragOrder = null
                                    latestPointerRootY = null
                                    break
                                }
                                latestPointerRootY = listCoords?.takeIf { it.isAttached }?.localToRoot(change.position)?.y
                                updateDragPosition(hitOption, change.position.y)
                            }
                        } catch (ce: CancellationException) {
                            draggedProvider = null
                            temporaryDragOrder = null
                            latestPointerRootY = null
                            throw ce
                        }
                    }
                }
        ) {
            displayedOrder.forEachIndexed { idx, option ->
                key(option) {
                    val isDragging = draggedProvider == option
                    val currentSlotTop = slotBounds[option]?.topPx
                    var previousSlotTop by remember { mutableStateOf<Float?>(null) }
                    val neighborDisplacementAnim = remember { Animatable(0f) }

                    LaunchedEffect(currentSlotTop, isDragging) {
                        val newTop = currentSlotTop ?: return@LaunchedEffect
                        if (isDragging) {
                            previousSlotTop = newTop
                            neighborDisplacementAnim.snapTo(0f)
                            return@LaunchedEffect
                        }
                        val oldTop = previousSlotTop
                        previousSlotTop = newTop
                        if (oldTop != null && oldTop != newTop && isDraggingAny) {
                            val delta = oldTop - newTop
                            neighborDisplacementAnim.snapTo(neighborDisplacementAnim.value + delta)
                            neighborDisplacementAnim.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing)
                            )
                        }
                    }

                    val dragScale by animateFloatAsState(
                        targetValue = if (isDragging) 1.02f else 1f,
                        animationSpec = tween(durationMillis = 120),
                        label = "provider_drag_scale"
                    )
                    val dragTranslationY = if (isDragging) {
                        dragDesiredTopY - (currentSlotTop ?: dragDesiredTopY)
                    } else {
                        neighborDisplacementAnim.value
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragging) 2f else 0f)
                            .onGloballyPositioned { coords ->
                                slotBounds[option] = ProviderSlotBounds(
                                    topPx = coords.positionInParent().y,
                                    heightPx = coords.size.height.toFloat()
                                )
                            }
                    ) {
                        ProviderRadioRowCard(
                            option = option,
                            isSelected = selectedProvider == option,
                            priorityIndex = idx,
                            isDragging = isDragging,
                            clickEnabled = !isDraggingAny,
                            onSelect = { onSelectProvider(option) },
                            modifier = Modifier.graphicsLayer {
                                translationY = dragTranslationY
                                scaleX = dragScale
                                scaleY = dragScale
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderRadioRowCard(
    option: LyricsProviderPreference,
    isSelected: Boolean,
    priorityIndex: Int?,
    isDragging: Boolean,
    clickEnabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(enabled = clickEnabled) {
                onSelect()
            }
            .testTag("settings_provider_option_${option.name.lowercase()}"),
        shape = RoundedCornerShape(12.dp),
        shadowElevation = if (isDragging) 8.dp else 0.dp,
        color = when {
            isDragging -> MaterialTheme.colorScheme.secondaryContainer
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        border = when {
            isDragging -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            isSelected -> BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
            else -> null
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = if (clickEnabled) onSelect else null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = option.displayName,
                        fontWeight = if (isSelected || isDragging) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 13.sp,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                    if (option == LyricsProviderPreference.AUTO) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        ) {
                            Text(
                                text = "Default",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    } else if (priorityIndex != null) {
                        Text(
                            text = if (priorityIndex == 0) "#1 • Priority" else "#${priorityIndex + 1}",
                            fontSize = 10.sp,
                            fontWeight = if (priorityIndex == 0) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (priorityIndex == 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                            }
                        )
                    }
                }
                Text(
                    text = option.description,
                    fontSize = 11.sp,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
