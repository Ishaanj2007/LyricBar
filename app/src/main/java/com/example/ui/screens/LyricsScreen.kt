package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.example.data.model.TrackMetadata
import com.example.domain.lyricsengine.LyricsFileFormat
import com.example.domain.lyricsengine.LyricsSearchQueryBuilder
import com.example.domain.parser.LrcParser
import com.example.service.MediaPlaybackManager
import com.example.ui.components.FrostedCard
import com.example.ui.components.LocalHazeState
import com.example.ui.components.MinimalOverlayView
import com.example.ui.components.rememberArtworkBitmap
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

@Composable
fun LyricsScreen(
    playbackManager: MediaPlaybackManager,
    userPreferences: com.example.data.preferences.UserPreferences = com.example.data.preferences.UserPreferences(),
    modifier: Modifier = Modifier,
    hasBottomNavPill: Boolean = true
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val playbackState by playbackManager.playbackState.collectAsStateWithLifecycle()
    val currentLyrics by playbackManager.currentLyrics.collectAsStateWithLifecycle()
    val trackOffset by playbackManager.trackOffsetMs.collectAsStateWithLifecycle()
    val isLoading by playbackManager.isLoadingLyrics.collectAsStateWithLifecycle()
    val livePreferences by playbackManager.preferencesRepository.userPreferencesFlow
        .collectAsStateWithLifecycle(initialValue = userPreferences)
    val selectedProvider = livePreferences.lyricsProvider

    val listState = rememberLazyListState()
    var showManualSearchDialog by remember { mutableStateOf(false) }
    var isDelayExpanded by rememberSaveable { mutableStateOf(false) }
    var isProviderMenuExpanded by remember { mutableStateOf(false) }

    // SAF File Picker (.lrc files only)
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    val fileName = try {
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex != -1 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
                        }
                    } catch (_: Exception) {
                        null
                    } ?: uri.lastPathSegment ?: ""

                    if (fileName.contains(".") && !fileName.endsWith(".lrc", ignoreCase = true) && !fileName.endsWith(".txt", ignoreCase = true)) {
                        Toast.makeText(context, "Unsupported file format. Please select an .lrc file", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    // Safe, bounded read on IO dispatcher with file size protection & encoding detection
                    val text = withContext(Dispatchers.IO) {
                        com.example.domain.parser.LrcImporter.readLrcFromUri(context.contentResolver, uri)
                    }

                    val track = playbackState.track ?: run {
                        val ti = Regex("\\[ti:\\s*(.*?)\\]", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
                        val ar = Regex("\\[ar:\\s*(.*?)\\]", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
                        val cleanFileName = fileName.substringBeforeLast(".").trim()
                        TrackMetadata(
                            title = if (!ti.isNullOrBlank()) ti else if (cleanFileName.isNotBlank()) cleanFileName else "Imported Track",
                            artist = if (!ar.isNullOrBlank()) ar else "Local File"
                        )
                    }

                    playbackManager.saveImportedLyrics(track, text, "Local Import")
                    Toast.makeText(context, "Lyrics imported successfully", Toast.LENGTH_SHORT).show()
                } catch (e: com.example.domain.parser.LrcImportException) {
                    Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                } catch (e: SecurityException) {
                    Toast.makeText(context, "File access permission denied", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                    val errorMsg = e.localizedMessage?.takeIf { it.isNotBlank() } ?: "Failed to import lyric file"
                    Toast.makeText(context, errorMsg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Use MediaPlaybackManager's activeLyricIndex as the single source of truth for line synchronization
    val activeIndex by playbackManager.activeLyricIndex.collectAsStateWithLifecycle()

    // Manual scroll tracking and automatic follow state
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    var isProgrammaticScroll by remember { mutableStateOf(false) }
    var userScrolledAway by rememberSaveable { mutableStateOf(false) }

    // Suspend auto-following when the user manually drags the lyric list
    LaunchedEffect(isDragged) {
        if (isDragged) {
            userScrolledAway = true
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && isDragged && !isProgrammaticScroll) {
            userScrolledAway = true
        }
    }

    // Reset userScrolledAway when the track changes
    val currentTrackKey = playbackState.track?.identityKey ?: ""
    LaunchedEffect(currentTrackKey) {
        userScrolledAway = false
    }

    // Auto-scroll logic: smoothly centers the active line in reading view when following is enabled.
    // Does NOT reset userScrolledAway on line changes, allowing the user to read earlier/upcoming lyrics uninterrupted.
    LaunchedEffect(activeIndex, userScrolledAway) {
        if (!userScrolledAway && activeIndex in 0 until (currentLyrics?.lines?.size ?: 0)) {
            val targetScroll = (activeIndex - 2).coerceAtLeast(0)
            if (listState.firstVisibleItemIndex != targetScroll) {
                isProgrammaticScroll = true
                try {
                    listState.animateScrollToItem(targetScroll)
                } catch (_: Exception) {
                } finally {
                    isProgrammaticScroll = false
                }
            }
        }
    }

    val themePrimary = MaterialTheme.colorScheme.primary
    val themeSecondary = MaterialTheme.colorScheme.secondary
    val hazeState = LocalHazeState.current
    val density = LocalDensity.current

    val artworkBitmap = rememberArtworkBitmap(playbackState.track)

    var rootPositionInWindow by remember { mutableStateOf(Offset.Zero) }
    var rootSizePx by remember { mutableStateOf(IntSize.Zero) }
    var selectorPositionInWindow by remember { mutableStateOf(Offset.Zero) }
    var selectorSizePx by remember { mutableStateOf(IntSize.Zero) }

    val providerChevronRotation by animateFloatAsState(
        targetValue = if (isProviderMenuExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "provider_chevron_rotation"
    )
    val delayChevronRotation by animateFloatAsState(
        targetValue = if (isDelayExpanded) 180f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "delay_chevron_rotation"
    )

    val offsetSec = trackOffset / 1000.0
    val formattedOffset = if (trackOffset > 0) "+%.1fs".format(offsetSec) else if (trackOffset < 0) "%.1fs".format(offsetSec) else "0.0s (In Sync)"

    BackHandler(enabled = isProviderMenuExpanded) {
        isProviderMenuExpanded = false
    }

    val trackTitleText = playbackState.track?.title
        ?: currentLyrics?.title?.takeIf { it.isNotBlank() }
        ?: "No Track Playing"
    val trackArtistText = playbackState.track?.artist
        ?: currentLyrics?.artist?.takeIf { it.isNotBlank() }
        ?: "Start playback to view synchronized lyrics"
    val showResumeButton = userScrolledAway && currentLyrics?.isSynced == true && activeIndex in 0 until (currentLyrics?.lines?.size ?: 0)
    val onResyncClick: () -> Unit = { playbackManager.resyncLyrics(forceRefresh = true) }
    val onSearchClick: () -> Unit = { showManualSearchDialog = true }
    val onImportClick: () -> Unit = { filePicker.launch(arrayOf("text/*", "*/*")) }
    val onResumeLiveClick: () -> Unit = {
        userScrolledAway = false
        val targetScroll = (activeIndex - 2).coerceAtLeast(0)
        coroutineScope.launch {
            isProgrammaticScroll = true
            try {
                listState.animateScrollToItem(targetScroll)
            } catch (_: Exception) {
            } finally {
                isProgrammaticScroll = false
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (hasBottomNavPill) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
            .onGloballyPositioned { coords ->
                rootPositionInWindow = coords.positionInWindow()
                rootSizePx = coords.size
            }
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val availableWidth = maxWidth
            val availableHeight = maxHeight
            val isLandscape = availableWidth > availableHeight && (availableHeight < 540.dp || availableWidth >= 600.dp)
            val isTwoPaneLandscape = isLandscape && availableWidth >= 560.dp

            if (isTwoPaneLandscape) {
                val sidebarWidthFraction = if (availableWidth >= 900.dp) 0.34f else 0.37f
                val isShortHeight = availableHeight < 420.dp
                val isWidePane = availableWidth >= 900.dp
                val artSize = when {
                    availableHeight >= 600.dp -> 112.dp
                    availableHeight >= 400.dp -> 72.dp
                    else -> 58.dp
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 1120.dp)
                            .padding(
                                horizontal = if (isWidePane) 24.dp else 16.dp,
                                vertical = if (isShortHeight) 8.dp else 14.dp
                            ),
                        horizontalArrangement = Arrangement.spacedBy(if (isWidePane) 20.dp else 14.dp)
                    ) {
                        // Left Control & Metadata Sidebar
                        Column(
                            modifier = Modifier
                                .weight(sidebarWidthFraction)
                                .widthIn(min = 210.dp, max = 330.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(if (isShortHeight) 8.dp else 12.dp)
                        ) {
                            FrostedCard(
                                hazeState = hazeState,
                                shape = RoundedCornerShape(20.dp),
                                backgroundAlpha = 0.22f,
                                blurRadius = 24.dp,
                                borderWidth = 0.75.dp,
                                borderAlpha = 0.20f,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(if (isShortHeight) 12.dp else 14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        LyricsArtworkThumbnail(
                                            artworkBitmap = artworkBitmap,
                                            size = artSize,
                                            cornerRadius = 14.dp,
                                            themePrimary = themePrimary
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = trackTitleText,
                                                style = MaterialTheme.typography.titleMedium.copy(
                                                    fontSize = if (isShortHeight) 15.5.sp else 17.sp
                                                ),
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = trackArtistText,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                                                fontWeight = FontWeight.Medium,
                                                color = Color.White.copy(alpha = 0.72f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        LyricsActionButtonsRow(
                                            themePrimary = themePrimary,
                                            onResync = onResyncClick,
                                            onSearch = onSearchClick,
                                            onImport = onImportClick
                                        )
                                    }
                                }
                            }

                            LyricsProviderAndDelayCard(
                                stacked = true,
                                selectedProviderName = selectedProvider.displayName,
                                formattedOffset = formattedOffset,
                                providerChevronRotation = providerChevronRotation,
                                delayChevronRotation = delayChevronRotation,
                                themePrimary = themePrimary,
                                hazeState = hazeState,
                                onToggleProviderMenu = { isProviderMenuExpanded = !isProviderMenuExpanded },
                                onToggleDelay = { isDelayExpanded = !isDelayExpanded },
                                onSelectorPositioned = { pos, size ->
                                    selectorPositionInWindow = pos
                                    selectorSizePx = size
                                }
                            )

                            LyricsDelayAdjustmentBar(
                                visible = isDelayExpanded,
                                stacked = true,
                                trackOffset = trackOffset,
                                hazeState = hazeState,
                                onAdjustOffset = { playbackManager.adjustTrackOffset(it) },
                                onResetOffset = { playbackManager.setExplicitOffset(0L) }
                            )
                        }

                        // Right Lyrics Reading Pane + Dedicated Bottom Mini-Player Slot (Never overlaps lyrics)
                        Column(
                            modifier = Modifier
                                .weight(1f - sidebarWidthFraction)
                                .fillMaxHeight()
                                .widthIn(max = 680.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                val lyrics = currentLyrics
                                if (lyrics == null || !lyrics.hasValidLyrics) {
                                    LyricsEmptyOrLoadingState(
                                        hasTrack = playbackState.track != null,
                                        isLoading = isLoading,
                                        compact = isShortHeight,
                                        bottomPadding = 0.dp,
                                        themePrimary = themePrimary,
                                        themeSecondary = themeSecondary,
                                        hazeState = hazeState,
                                        onSearch = onSearchClick,
                                        onImport = onImportClick
                                    )
                                } else {
                                    LyricsLinesLazyColumn(
                                        lyrics = lyrics,
                                        listState = listState,
                                        activeIndex = activeIndex,
                                        playbackState = playbackState,
                                        trackOffset = trackOffset,
                                        userPreferences = userPreferences,
                                        themePrimary = themePrimary,
                                        textAlign = TextAlign.Start,
                                        contentPadding = PaddingValues(top = 4.dp, bottom = 14.dp),
                                        onLineClick = { timeMs ->
                                            userScrolledAway = false
                                            playbackManager.seekTo(timeMs)
                                        }
                                    )

                                    LyricsResumeLivePill(
                                        visible = showResumeButton,
                                        themePrimary = themePrimary,
                                        onClick = onResumeLiveClick,
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = 8.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(if (isShortHeight) 6.dp else 10.dp))

                            LyricsMiniPlayerCard(
                                artworkBitmap = artworkBitmap,
                                playbackState = playbackState,
                                currentLyrics = currentLyrics,
                                themePrimary = themePrimary,
                                hazeState = hazeState,
                                compact = isShortHeight,
                                onSeek = { playbackManager.seekTo(it) },
                                onTogglePlayPause = { playbackManager.togglePlayPause() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 600.dp)
                            )
                        }
                    }
                }
            } else if (isLandscape) {
                // Compact Single-Column Landscape (e.g., < 560dp usable width):
                // Tight top bar + weighted lyrics viewport + dedicated bottom mini-player slot
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 620.dp)
                    ) {
                        LyricsPortraitTopHeader(
                            artworkBitmap = artworkBitmap,
                            titleText = trackTitleText,
                            artistText = trackArtistText,
                            themePrimary = themePrimary,
                            compact = true,
                            onResync = onResyncClick,
                            onSearch = onSearchClick,
                            onImport = onImportClick
                        )

                        LyricsProviderAndDelayCard(
                            stacked = false,
                            selectedProviderName = selectedProvider.displayName,
                            formattedOffset = formattedOffset,
                            providerChevronRotation = providerChevronRotation,
                            delayChevronRotation = delayChevronRotation,
                            themePrimary = themePrimary,
                            hazeState = hazeState,
                            compact = true,
                            onToggleProviderMenu = { isProviderMenuExpanded = !isProviderMenuExpanded },
                            onToggleDelay = { isDelayExpanded = !isDelayExpanded },
                            onSelectorPositioned = { pos, size ->
                                selectorPositionInWindow = pos
                                selectorSizePx = size
                            }
                        )

                        LyricsDelayAdjustmentBar(
                            visible = isDelayExpanded,
                            stacked = false,
                            trackOffset = trackOffset,
                            hazeState = hazeState,
                            onAdjustOffset = { playbackManager.adjustTrackOffset(it) },
                            onResetOffset = { playbackManager.setExplicitOffset(0L) }
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            val lyrics = currentLyrics
                            if (lyrics == null || !lyrics.hasValidLyrics) {
                                LyricsEmptyOrLoadingState(
                                    hasTrack = playbackState.track != null,
                                    isLoading = isLoading,
                                    compact = true,
                                    bottomPadding = 0.dp,
                                    themePrimary = themePrimary,
                                    themeSecondary = themeSecondary,
                                    hazeState = hazeState,
                                    onSearch = onSearchClick,
                                    onImport = onImportClick
                                )
                            } else {
                                LyricsLinesLazyColumn(
                                    lyrics = lyrics,
                                    listState = listState,
                                    activeIndex = activeIndex,
                                    playbackState = playbackState,
                                    trackOffset = trackOffset,
                                    userPreferences = userPreferences,
                                    themePrimary = themePrimary,
                                    textAlign = TextAlign.End,
                                    contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                                    onLineClick = { timeMs ->
                                        userScrolledAway = false
                                        playbackManager.seekTo(timeMs)
                                    }
                                )

                                LyricsResumeLivePill(
                                    visible = showResumeButton,
                                    themePrimary = themePrimary,
                                    onClick = onResumeLiveClick,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 8.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        LyricsMiniPlayerCard(
                            artworkBitmap = artworkBitmap,
                            playbackState = playbackState,
                            currentLyrics = currentLyrics,
                            themePrimary = themePrimary,
                            hazeState = hazeState,
                            compact = true,
                            onSeek = { playbackManager.seekTo(it) },
                            onTogglePlayPause = { playbackManager.togglePlayPause() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else {
                // Portrait / Standard Composition (preserved intact)
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .widthIn(max = 680.dp)
                        ) {
                            LyricsPortraitTopHeader(
                                artworkBitmap = artworkBitmap,
                                titleText = playbackState.track?.title ?: currentLyrics?.title?.takeIf { it.isNotBlank() } ?: "Someone To Spend Time With",
                                artistText = playbackState.track?.artist ?: currentLyrics?.artist?.takeIf { it.isNotBlank() } ?: "Los Retros",
                                themePrimary = themePrimary,
                                compact = false,
                                onResync = onResyncClick,
                                onSearch = onSearchClick,
                                onImport = onImportClick
                            )

                            LyricsProviderAndDelayCard(
                                stacked = false,
                                selectedProviderName = selectedProvider.displayName,
                                formattedOffset = formattedOffset,
                                providerChevronRotation = providerChevronRotation,
                                delayChevronRotation = delayChevronRotation,
                                themePrimary = themePrimary,
                                hazeState = hazeState,
                                compact = false,
                                onToggleProviderMenu = { isProviderMenuExpanded = !isProviderMenuExpanded },
                                onToggleDelay = { isDelayExpanded = !isDelayExpanded },
                                onSelectorPositioned = { pos, size ->
                                    selectorPositionInWindow = pos
                                    selectorSizePx = size
                                }
                            )

                            LyricsDelayAdjustmentBar(
                                visible = isDelayExpanded,
                                stacked = false,
                                trackOffset = trackOffset,
                                hazeState = hazeState,
                                onAdjustOffset = { playbackManager.adjustTrackOffset(it) },
                                onResetOffset = { playbackManager.setExplicitOffset(0L) }
                            )

                            Spacer(modifier = Modifier.height(2.dp))

                            val lyrics = currentLyrics
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                if (lyrics == null || !lyrics.hasValidLyrics) {
                                    LyricsEmptyOrLoadingState(
                                        hasTrack = playbackState.track != null,
                                        isLoading = isLoading,
                                        compact = false,
                                        bottomPadding = if (hasBottomNavPill) 140.dp else 76.dp,
                                        themePrimary = themePrimary,
                                        themeSecondary = themeSecondary,
                                        hazeState = hazeState,
                                        onSearch = onSearchClick,
                                        onImport = onImportClick
                                    )
                                } else {
                                    LyricsLinesLazyColumn(
                                        lyrics = lyrics,
                                        listState = listState,
                                        activeIndex = activeIndex,
                                        playbackState = playbackState,
                                        trackOffset = trackOffset,
                                        userPreferences = userPreferences,
                                        themePrimary = themePrimary,
                                        textAlign = TextAlign.End,
                                        contentPadding = PaddingValues(
                                            top = 6.dp,
                                            bottom = if (hasBottomNavPill) 240.dp else 104.dp
                                        ),
                                        onLineClick = { timeMs ->
                                            userScrolledAway = false
                                            playbackManager.seekTo(timeMs)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Floating Resume Live Lyrics Control (Portrait)
                    val portraitResumeBottom = when {
                        hasBottomNavPill && playbackState.track != null -> 164.dp
                        hasBottomNavPill -> 84.dp
                        else -> 88.dp
                    }
                    LyricsResumeLivePill(
                        visible = showResumeButton,
                        themePrimary = themePrimary,
                        onClick = onResumeLiveClick,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .then(if (hasBottomNavPill) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)
                            .padding(bottom = portraitResumeBottom)
                    )

                    // Floating Frosted Bottom Mini-Player (Portrait)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .then(if (hasBottomNavPill) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier)
                            .padding(horizontal = 16.dp)
                            .padding(bottom = if (hasBottomNavPill) 76.dp else 12.dp)
                    ) {
                        LyricsMiniPlayerCard(
                            artworkBitmap = artworkBitmap,
                            playbackState = playbackState,
                            currentLyrics = currentLyrics,
                            themePrimary = themePrimary,
                            hazeState = hazeState,
                            compact = false,
                            onSeek = { playbackManager.seekTo(it) },
                            onTogglePlayPause = { playbackManager.togglePlayPause() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 520.dp)
                        )
                    }
                }
            }
        }

        // Outside-tap dismiss layer when floating provider menu is open
        if (isProviderMenuExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { isProviderMenuExpanded = false }
                    )
                    .testTag("lyrics_provider_menu_scrim")
            )
        }

        // In-window Frosted-Glass Floating Provider Menu (shares LocalHazeState for true backdrop blur)
        ProviderFloatingGlassMenu(
            expanded = isProviderMenuExpanded,
            selectedProvider = selectedProvider,
            onSelectProvider = { option ->
                isProviderMenuExpanded = false
                playbackManager.setLyricsProvider(option)
            },
            rootPositionInWindow = rootPositionInWindow,
            rootSizePx = rootSizePx,
            selectorPositionInWindow = selectorPositionInWindow,
            selectorSizePx = selectorSizePx,
            hasBottomNavPill = hasBottomNavPill,
            hazeState = hazeState
        )
    }

    if (showManualSearchDialog) {
        ManualSearchDialog(
            initialTitle = playbackState.track?.title ?: currentLyrics?.title ?: "",
            initialArtist = playbackState.track?.artist ?: currentLyrics?.artist ?: "",
            onDismiss = { showManualSearchDialog = false },
            onSearch = { title, artist ->
                showManualSearchDialog = false
                coroutineScope.launch {
                    val track = playbackState.track?.copy(title = title, artist = artist) ?: com.example.data.model.TrackMetadata(
                        title = title,
                        artist = artist
                    )
                    Toast.makeText(context, "Searching lyrics for \"$title\"…", Toast.LENGTH_SHORT).show()
                    val result = com.example.data.repository.CompositeLyricsRepository
                        .getInstance(context)
                        .getOrFetchLyrics(
                            track = track,
                            forceNetwork = true,
                            allowNetwork = true,
                            providerPreference = selectedProvider
                        )
                    if (result.isSuccess) {
                        playbackManager.setManualLyrics(result.getOrThrow())
                        Toast.makeText(context, "Lyrics found for \"$title\"", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "No lyrics found online for \"$title\"", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}

@Composable
private fun LyricsArtworkThumbnail(
    artworkBitmap: android.graphics.Bitmap?,
    size: androidx.compose.ui.unit.Dp,
    cornerRadius: androidx.compose.ui.unit.Dp,
    themePrimary: Color
) {
    Surface(
        shape = RoundedCornerShape(cornerRadius),
        color = Color.White.copy(alpha = 0.08f),
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
    ) {
        if (artworkBitmap != null && !artworkBitmap.isRecycled) {
            Image(
                bitmap = artworkBitmap.asImageBitmap(),
                contentDescription = "Album Art",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = "Album Art Placeholder",
                    tint = themePrimary,
                    modifier = Modifier.size(size * 0.48f)
                )
            }
        }
    }
}

@Composable
private fun LyricsActionButtonsRow(
    themePrimary: Color,
    onResync: () -> Unit,
    onSearch: () -> Unit,
    onImport: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            onClick = onResync,
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.10f),
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f)),
            modifier = Modifier
                .size(32.dp)
                .testTag("lyrics_resync_button")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Resync Lyrics",
                    tint = themePrimary,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        Surface(
            onClick = onSearch,
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.10f),
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f)),
            modifier = Modifier
                .size(32.dp)
                .testTag("search_lyrics_button")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search Lyrics",
                    tint = Color.White.copy(alpha = 0.90f),
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        Surface(
            onClick = onImport,
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.10f),
            border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.15f)),
            modifier = Modifier
                .size(32.dp)
                .testTag("upload_lrc_icon_button")
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.PostAdd,
                    contentDescription = "Import LRC",
                    tint = Color.White.copy(alpha = 0.90f),
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
private fun LyricsPortraitTopHeader(
    artworkBitmap: android.graphics.Bitmap?,
    titleText: String,
    artistText: String,
    themePrimary: Color,
    compact: Boolean,
    onResync: () -> Unit,
    onSearch: () -> Unit,
    onImport: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = if (compact) 2.dp else 6.dp,
                bottom = if (compact) 6.dp else 10.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LyricsArtworkThumbnail(
            artworkBitmap = artworkBitmap,
            size = if (compact) 44.dp else 56.dp,
            cornerRadius = if (compact) 11.dp else 14.dp,
            themePrimary = themePrimary
        )

        Spacer(modifier = Modifier.width(10.dp))

        Box(
            modifier = Modifier
                .height(if (compact) 26.dp else 32.dp)
                .width(1.dp)
                .background(Color.White.copy(alpha = 0.22f))
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titleText,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = if (compact) 16.sp else 18.sp),
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(if (compact) 2.dp else 3.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = artistText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = if (compact) 12.5.sp else 13.5.sp),
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.70f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(modifier = Modifier.width(6.dp))

                LyricsActionButtonsRow(
                    themePrimary = themePrimary,
                    onResync = onResync,
                    onSearch = onSearch,
                    onImport = onImport
                )
            }
        }
    }
}

@Composable
private fun LyricsProviderAndDelayCard(
    stacked: Boolean,
    selectedProviderName: String,
    formattedOffset: String,
    providerChevronRotation: Float,
    delayChevronRotation: Float,
    themePrimary: Color,
    hazeState: HazeState?,
    compact: Boolean = false,
    onToggleProviderMenu: () -> Unit,
    onToggleDelay: () -> Unit,
    onSelectorPositioned: (Offset, IntSize) -> Unit
) {
    FrostedCard(
        hazeState = hazeState,
        shape = RoundedCornerShape(if (stacked) 20.dp else 22.dp),
        backgroundAlpha = 0.22f,
        blurRadius = 24.dp,
        borderWidth = 0.75.dp,
        borderAlpha = 0.20f,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
    ) {
        if (stacked) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, color = themePrimary),
                            onClick = onToggleProviderMenu
                        )
                        .onGloballyPositioned { coords ->
                            onSelectorPositioned(coords.positionInWindow(), coords.size)
                        }
                        .padding(vertical = 4.dp, horizontal = 4.dp)
                        .testTag("lyrics_provider_selector"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Lyrics provider",
                        tint = themePrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Provider",
                            fontSize = 10.5.sp,
                            color = Color.White.copy(alpha = 0.65f)
                        )
                        Text(
                            text = selectedProviderName,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand provider menu",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = providerChevronRotation }
                    )
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = Color.White.copy(alpha = 0.14f)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, color = themePrimary),
                            onClick = onToggleDelay
                        )
                        .padding(vertical = 4.dp, horizontal = 4.dp)
                        .testTag("lyrics_delay_control_card"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = "Lyrics delay",
                        tint = themePrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Lyrics Delay",
                            fontSize = 10.5.sp,
                            color = Color.White.copy(alpha = 0.65f)
                        )
                        Text(
                            text = formattedOffset,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("lyrics_delay_display")
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand delay controls",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = delayChevronRotation }
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = if (compact) 7.dp else 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, color = themePrimary),
                            onClick = onToggleProviderMenu
                        )
                        .onGloballyPositioned { coords ->
                            onSelectorPositioned(coords.positionInWindow(), coords.size)
                        }
                        .padding(vertical = 2.dp, horizontal = 2.dp)
                        .testTag("lyrics_provider_selector"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = "Lyrics provider",
                        tint = themePrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Provider",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color.White.copy(alpha = 0.65f)
                        )
                        Text(
                            text = selectedProviderName,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand provider menu",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = providerChevronRotation }
                    )
                }

                Box(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .width(1.dp)
                        .height(24.dp)
                        .background(Color.White.copy(alpha = 0.18f))
                )

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, color = themePrimary),
                            onClick = onToggleDelay
                        )
                        .padding(vertical = 2.dp, horizontal = 2.dp)
                        .testTag("lyrics_delay_control_card"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = "Lyrics delay",
                        tint = themePrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Lyrics Delay",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color.White.copy(alpha = 0.65f)
                        )
                        Text(
                            text = formattedOffset,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("lyrics_delay_display")
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand delay controls",
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { rotationZ = delayChevronRotation }
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsDelayAdjustmentBar(
    visible: Boolean,
    stacked: Boolean,
    trackOffset: Long,
    hazeState: HazeState?,
    onAdjustOffset: (Long) -> Unit,
    onResetOffset: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(160)) + slideInVertically(tween(180)) { -it / 3 },
        exit = fadeOut(tween(120)) + slideOutVertically(tween(140)) { -it / 3 }
    ) {
        FrostedCard(
            hazeState = hazeState,
            shape = RoundedCornerShape(16.dp),
            backgroundAlpha = 0.20f,
            blurRadius = 20.dp,
            borderWidth = 0.75.dp,
            borderAlpha = 0.20f,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp)
        ) {
            if (stacked) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TimingAdjustPill(
                            text = "-0.5s",
                            onClick = { onAdjustOffset(-500L) },
                            modifier = Modifier.weight(1f).testTag("lyrics_delay_minus_500")
                        )
                        TimingAdjustPill(
                            text = "-0.1s",
                            onClick = { onAdjustOffset(-100L) },
                            modifier = Modifier.weight(1f).testTag("lyrics_delay_minus_100")
                        )
                        TimingAdjustPill(
                            text = "+0.1s",
                            onClick = { onAdjustOffset(100L) },
                            modifier = Modifier.weight(1f).testTag("lyrics_delay_plus_100")
                        )
                        TimingAdjustPill(
                            text = "+0.5s",
                            onClick = { onAdjustOffset(500L) },
                            modifier = Modifier.weight(1f).testTag("lyrics_delay_plus_500")
                        )
                    }
                    if (trackOffset != 0L) {
                        TimingAdjustPill(
                            text = "Reset",
                            isPrimary = true,
                            onClick = onResetOffset,
                            modifier = Modifier.fillMaxWidth().testTag("lyrics_delay_reset")
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TimingAdjustPill(
                        text = "-0.5s",
                        onClick = { onAdjustOffset(-500L) },
                        modifier = Modifier.weight(1f).testTag("lyrics_delay_minus_500")
                    )
                    TimingAdjustPill(
                        text = "-0.1s",
                        onClick = { onAdjustOffset(-100L) },
                        modifier = Modifier.weight(1f).testTag("lyrics_delay_minus_100")
                    )
                    if (trackOffset != 0L) {
                        TimingAdjustPill(
                            text = "Reset",
                            isPrimary = true,
                            onClick = onResetOffset,
                            modifier = Modifier.weight(1f).testTag("lyrics_delay_reset")
                        )
                    }
                    TimingAdjustPill(
                        text = "+0.1s",
                        onClick = { onAdjustOffset(100L) },
                        modifier = Modifier.weight(1f).testTag("lyrics_delay_plus_100")
                    )
                    TimingAdjustPill(
                        text = "+0.5s",
                        onClick = { onAdjustOffset(500L) },
                        modifier = Modifier.weight(1f).testTag("lyrics_delay_plus_500")
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsEmptyOrLoadingState(
    hasTrack: Boolean,
    isLoading: Boolean,
    compact: Boolean,
    bottomPadding: androidx.compose.ui.unit.Dp,
    themePrimary: Color,
    themeSecondary: Color,
    hazeState: HazeState?,
    onSearch: () -> Unit,
    onImport: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = bottomPadding),
        contentAlignment = Alignment.Center
    ) {
        FrostedCard(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp)
                .padding(horizontal = if (compact) 8.dp else 16.dp),
            hazeState = hazeState,
            shape = RoundedCornerShape(if (compact) 20.dp else 24.dp),
            backgroundAlpha = 0.32f,
            blurRadius = 24.dp,
            borderWidth = 0.75.dp,
            borderAlpha = 0.25f,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = if (compact) 18.dp else 24.dp,
                        vertical = if (compact) 16.dp else 28.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = themePrimary.copy(alpha = 0.15f),
                    modifier = Modifier.size(if (compact) 48.dp else 68.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = "No lyrics icon",
                            tint = themePrimary,
                            modifier = Modifier.size(if (compact) 24.dp else 34.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(if (compact) 10.dp else 16.dp))

                Text(
                    text = if (hasTrack) "No lyrics found for this track" else "No track currently playing",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = if (compact) 15.5.sp else 17.sp),
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Search for matching lyrics online or select an LRC file manually",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = if (compact) 12.sp else 13.sp),
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                    lineHeight = if (compact) 16.sp else 18.sp
                )

                if (isLoading) {
                    Spacer(modifier = Modifier.height(if (compact) 10.dp else 14.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = themePrimary
                        )
                        Text(
                            text = "Searching online providers…",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.80f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(if (compact) 14.dp else 20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onSearch,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = themePrimary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = if (compact) 14.dp else 18.dp, vertical = 10.dp),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 44.dp)
                            .testTag("empty_state_search_button")
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search for matching lyrics",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Search Lyrics",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    OutlinedButton(
                        onClick = onImport,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = if (compact) 14.dp else 18.dp, vertical = 10.dp),
                        border = BorderStroke(1.dp, themeSecondary.copy(alpha = 0.50f)),
                        modifier = Modifier
                            .defaultMinSize(minHeight = 44.dp)
                            .testTag("empty_state_import_button")
                    ) {
                        Icon(
                            Icons.Default.UploadFile,
                            contentDescription = "Select an LRC file manually",
                            tint = themeSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Import Lyrics",
                            color = themeSecondary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsLinesLazyColumn(
    lyrics: com.example.data.model.ParsedLyrics,
    listState: androidx.compose.foundation.lazy.LazyListState,
    activeIndex: Int,
    playbackState: com.example.data.model.PlaybackStateInfo,
    trackOffset: Long,
    userPreferences: com.example.data.preferences.UserPreferences,
    themePrimary: Color,
    textAlign: TextAlign,
    contentPadding: PaddingValues,
    onLineClick: (Long) -> Unit
) {
    val lineAlignment = if (textAlign == TextAlign.Start) Alignment.Start else Alignment.End
    val rowArrangement = if (textAlign == TextAlign.Start) Arrangement.Start else Arrangement.End

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding
    ) {
        itemsIndexed(
            items = lyrics.lines,
            key = { index, line -> "${line.timeMs}_${index}" }
        ) { index, line ->
            val isActive = index == activeIndex
            val isPast = activeIndex != -1 && index < activeIndex

            val targetAlpha = when {
                !lyrics.isSynced -> 0.92f
                isActive -> 1.0f
                isPast -> 0.56f
                else -> 0.72f
            }
            val alpha by animateFloatAsState(
                targetValue = targetAlpha,
                animationSpec = tween(durationMillis = 200),
                label = "lyric_alpha"
            )
            val lyricTextColor by animateColorAsState(
                targetValue = if (isActive) Color.White else Color.White.copy(alpha = alpha),
                animationSpec = tween(durationMillis = 220),
                label = "lyric_text_color"
            )
            val lineScale by animateFloatAsState(
                targetValue = if (isActive) 1.02f else 1.0f,
                animationSpec = tween(durationMillis = 200),
                label = "lyric_line_scale"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = if (isActive) 3.dp else 2.dp)
                    .graphicsLayer {
                        scaleX = lineScale
                        scaleY = lineScale
                    }
                    .clip(RoundedCornerShape(14.dp))
                    .then(
                        if (isActive) {
                            Modifier
                                .background(Color.White.copy(alpha = 0.10f))
                                .border(BorderStroke(0.75.dp, Color.White.copy(alpha = 0.18f)), RoundedCornerShape(14.dp))
                        } else {
                            Modifier
                        }
                    )
                    .clickable {
                        if (lyrics.isSynced) {
                            onLineClick(line.timeMs)
                        }
                    }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 14.dp,
                            vertical = if (isActive) 8.dp else 5.dp
                        ),
                    horizontalAlignment = lineAlignment
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = rowArrangement
                    ) {
                        if (lyrics.isSynced && isActive) {
                            val minutes = (line.timeMs / 1000) / 60
                            val seconds = (line.timeMs / 1000) % 60
                            Surface(
                                color = themePrimary.copy(alpha = 0.20f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.padding(end = 10.dp)
                            ) {
                                Text(
                                    text = "%02d:%02d".format(minutes, seconds),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = themePrimary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        if (isActive && line.isWordSynced && line.words.isNotEmpty()) {
                            WordSyncedLyricText(
                                line = line,
                                playbackState = playbackState,
                                trackOffset = trackOffset,
                                karaokeGlowSpeed = userPreferences.karaokeGlowSpeed,
                                fontSize = if (userPreferences.appleMusicStyle) 26.sp else 23.sp,
                                lineHeight = if (userPreferences.appleMusicStyle) 34.sp else 30.sp,
                                fontFamily = com.example.ui.theme.getFontFamily(userPreferences.selectedFont),
                                onSurfaceColor = Color.White,
                                themePrimary = themePrimary,
                                textAlign = textAlign,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        } else {
                            Text(
                                text = line.text,
                                fontSize = if (isActive) (if (userPreferences.appleMusicStyle) 26.sp else 23.sp) else 18.sp,
                                fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                                color = lyricTextColor,
                                lineHeight = if (isActive) 34.sp else 26.sp,
                                fontFamily = com.example.ui.theme.getFontFamily(userPreferences.selectedFont),
                                textAlign = textAlign,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                    }

                    if (line.translation != null) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = line.translation,
                            fontSize = if (isActive) 13.sp else 12.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            textAlign = textAlign,
                            color = if (isActive) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.50f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsResumeLivePill(
    visible: Boolean,
    themePrimary: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + slideInVertically(tween(250)) { it / 2 },
        exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it / 2 },
        modifier = modifier
    ) {
        val pillShape = RoundedCornerShape(20.dp)
        FrostedCard(
            shape = pillShape,
            backgroundAlpha = 0.35f,
            blurRadius = 20.dp,
            borderWidth = 0.75.dp,
            borderAlpha = 0.35f,
            shadowElevation = 8.dp,
            modifier = Modifier
                .clip(pillShape)
                .clickable(onClick = onClick)
                .testTag("resume_live_lyrics_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Navigation,
                    contentDescription = "Resume Live Lyrics",
                    tint = themePrimary,
                    modifier = Modifier
                        .size(15.dp)
                        .graphicsLayer { rotationZ = 180f }
                )
                Text(
                    text = "Resume Live Lyrics",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun LyricsMiniPlayerCard(
    artworkBitmap: android.graphics.Bitmap?,
    playbackState: com.example.data.model.PlaybackStateInfo,
    currentLyrics: com.example.data.model.ParsedLyrics?,
    themePrimary: Color,
    hazeState: HazeState?,
    compact: Boolean,
    onSeek: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    FrostedCard(
        hazeState = hazeState,
        shape = RoundedCornerShape(if (compact) 20.dp else 26.dp),
        backgroundAlpha = 0.24f,
        blurRadius = 24.dp,
        borderWidth = 0.75.dp,
        borderAlpha = 0.22f,
        shadowElevation = 8.dp,
        modifier = modifier.testTag("lyrics_bottom_mini_player")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 12.dp,
                    vertical = if (compact) 7.dp else 10.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(if (compact) 10.dp else 12.dp),
                color = Color.White.copy(alpha = 0.08f),
                modifier = Modifier
                    .size(if (compact) 40.dp else 50.dp)
                    .clip(RoundedCornerShape(if (compact) 10.dp else 12.dp))
            ) {
                if (artworkBitmap != null && !artworkBitmap.isRecycled) {
                    Image(
                        bitmap = artworkBitmap.asImageBitmap(),
                        contentDescription = "Mini player artwork",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = themePrimary,
                            modifier = Modifier.size(if (compact) 20.dp else 24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            val totalDurationMs = remember(playbackState.track?.durationSec, currentLyrics) {
                val dSec = playbackState.track?.durationSec ?: 0
                if (dSec > 0) {
                    dSec * 1000L
                } else {
                    currentLyrics?.lines?.lastOrNull()?.timeMs?.plus(5000L)?.coerceAtLeast(180000L) ?: 234000L
                }
            }

            var isDraggingSeek by remember { mutableStateOf(false) }
            var dragSeekFraction by remember { mutableFloatStateOf(0f) }
            var currentPlaybackPosMs by remember { mutableLongStateOf(playbackState.calculatedPositionMs) }

            LaunchedEffect(playbackState.isPlaying, playbackState.positionMs, playbackState.lastUpdatedMs, playbackState.playbackSpeed) {
                if (playbackState.isPlaying) {
                    while (isActive) {
                        if (!isDraggingSeek) {
                            currentPlaybackPosMs = playbackState.calculatedPositionMs
                        }
                        kotlinx.coroutines.delay(200)
                    }
                } else {
                    currentPlaybackPosMs = playbackState.calculatedPositionMs
                }
            }

            val currentFraction = if (isDraggingSeek) {
                dragSeekFraction
            } else {
                (currentPlaybackPosMs.toFloat() / totalDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                CustomMiniPlayerSlider(
                    progress = currentFraction,
                    onValueChange = { fraction ->
                        isDraggingSeek = true
                        dragSeekFraction = fraction
                    },
                    onValueChangeFinished = {
                        val targetMs = (dragSeekFraction * totalDurationMs).toLong()
                        onSeek(targetMs)
                        isDraggingSeek = false
                    },
                    themePrimary = themePrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .testTag("lyrics_mini_player_seek_bar")
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (compact) 1.dp else 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val displayPos = if (isDraggingSeek) (dragSeekFraction * totalDurationMs).toLong() else currentPlaybackPosMs
                    Text(
                        text = formatPlaybackTime(displayPos),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.testTag("lyrics_mini_player_position_text")
                    )
                    Text(
                        text = formatPlaybackTime(totalDurationMs),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.testTag("lyrics_mini_player_duration_text")
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Surface(
                onClick = onTogglePlayPause,
                shape = CircleShape,
                color = themePrimary,
                modifier = Modifier
                    .size(if (compact) 42.dp else 46.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("lyrics_mini_player_play_pause"),
                shadowElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                        tint = Color(0xFF380720),
                        modifier = Modifier.size(if (compact) 22.dp else 24.dp)
                    )
                }
            }
        }
    }
}

private fun com.example.data.preferences.LyricsProviderPreference.compactMenuDescription(): String = when (this) {
    com.example.data.preferences.LyricsProviderPreference.AUTO -> "Tries providers in priority order."
    com.example.data.preferences.LyricsProviderPreference.LRCLIB -> "Synchronized LRC catalog."
    com.example.data.preferences.LyricsProviderPreference.LRC_RED -> "ISRC-based lyrics."
    com.example.data.preferences.LyricsProviderPreference.KUGOU -> "Title and artist matching."
    com.example.data.preferences.LyricsProviderPreference.LYRICS_PLUS -> "Multi-mirror lyrics."
}

@Composable
private fun ProviderFloatingGlassMenu(
    expanded: Boolean,
    selectedProvider: com.example.data.preferences.LyricsProviderPreference,
    onSelectProvider: (com.example.data.preferences.LyricsProviderPreference) -> Unit,
    rootPositionInWindow: Offset,
    rootSizePx: IntSize,
    selectorPositionInWindow: Offset,
    selectorSizePx: IntSize,
    hasBottomNavPill: Boolean,
    hazeState: HazeState?
) {
    val density = LocalDensity.current
    val themePrimary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    var menuSizePx by remember { mutableStateOf(IntSize.Zero) }

    val horizontalMarginPx = with(density) { 16.dp.roundToPx() }
    val verticalGapPx = with(density) { 6.dp.roundToPx() }
    val topSafePaddingPx = with(density) { (if (hasBottomNavPill) 44.dp else 8.dp).roundToPx() }
    val bottomSafePaddingPx = with(density) { (if (hasBottomNavPill) 88.dp else 12.dp).roundToPx() }

    val rawX = (selectorPositionInWindow.x - rootPositionInWindow.x).roundToInt()
    val rawY = (selectorPositionInWindow.y - rootPositionInWindow.y + selectorSizePx.height + verticalGapPx).roundToInt()

    val clampedX = if (rootSizePx.width > 0 && menuSizePx.width > 0) {
        val maxX = (rootSizePx.width - menuSizePx.width - horizontalMarginPx).coerceAtLeast(horizontalMarginPx)
        rawX.coerceIn(horizontalMarginPx, maxX)
    } else {
        rawX.coerceAtLeast(horizontalMarginPx)
    }

    val maxAllowedY = if (rootSizePx.height > 0 && menuSizePx.height > 0) {
        (rootSizePx.height - bottomSafePaddingPx - menuSizePx.height).coerceAtLeast(topSafePaddingPx)
    } else {
        Int.MAX_VALUE
    }
    val clampedY = rawY.coerceIn(topSafePaddingPx, maxAllowedY)

    val maxMenuWidthDp = if (rootSizePx.width > 0) {
        with(density) {
            minOf(244.dp, (rootSizePx.width - horizontalMarginPx * 2).toDp().coerceAtLeast(196.dp))
        }
    } else {
        240.dp
    }

    val maxMenuHeightDp = if (rootSizePx.height > 0) {
        with(density) {
            val availablePx = (rootSizePx.height - clampedY - bottomSafePaddingPx).coerceAtLeast(140.dp.roundToPx())
            minOf(280.dp, availablePx.toDp())
        }
    } else {
        260.dp
    }

    val menuShape = RoundedCornerShape(16.dp)

    Box(
        modifier = Modifier
            .offset { IntOffset(clampedX, clampedY) }
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(animationSpec = tween(durationMillis = 150)) +
                scaleIn(
                    initialScale = 0.92f,
                    transformOrigin = TransformOrigin(0f, 0f),
                    animationSpec = spring(dampingRatio = 0.84f, stiffness = 540f)
                ),
            exit = fadeOut(animationSpec = tween(durationMillis = 110)) +
                scaleOut(
                    targetScale = 0.95f,
                    transformOrigin = TransformOrigin(0f, 0f),
                    animationSpec = tween(durationMillis = 110)
                )
        ) {
            FrostedCard(
                hazeState = hazeState,
                shape = menuShape,
                backgroundAlpha = 0.36f,
                blurRadius = 26.dp,
                borderWidth = 0.75.dp,
                borderAlpha = 0.22f,
                modifier = Modifier
                    .widthIn(min = 212.dp, max = maxMenuWidthDp)
                    .width(IntrinsicSize.Max)
                    .heightIn(max = maxMenuHeightDp)
                    .onGloballyPositioned { coords ->
                        menuSizePx = coords.size
                    }
                    .shadow(
                        elevation = 10.dp,
                        shape = menuShape,
                        ambientColor = Color.Black.copy(alpha = 0.32f),
                        spotColor = Color.Black.copy(alpha = 0.32f)
                    )
                    .testTag("lyrics_provider_dropdown_menu")
            ) {
                // Subtle artwork/palette-derived glass sheen over the Haze blurred backdrop
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    themePrimary.copy(alpha = 0.07f),
                                    Color.Black.copy(alpha = 0.10f)
                                )
                            )
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        com.example.data.preferences.LyricsProviderPreference.entries.forEach { option ->
                            val isSelected = option == selectedProvider
                            val rowShape = RoundedCornerShape(10.dp)
                            val rowBackground = if (isSelected) {
                                themePrimary.copy(alpha = 0.15f)
                            } else {
                                Color.Transparent
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 38.dp)
                                    .clip(rowShape)
                                    .background(rowBackground)
                                    .clickable(
                                        role = Role.RadioButton,
                                        onClick = { onSelectProvider(option) }
                                    )
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                                    .testTag("lyrics_provider_option_${option.name.lowercase()}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = option.displayName,
                                        fontSize = 12.5.sp,
                                        lineHeight = 15.sp,
                                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (isSelected) themePrimary else onSurface.copy(alpha = 0.95f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = option.compactMenuDescription(),
                                        fontSize = 10.5.sp,
                                        lineHeight = 13.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = if (isSelected) {
                                            onSurfaceVariant.copy(alpha = 0.90f)
                                        } else {
                                            onSurfaceVariant.copy(alpha = 0.72f)
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Box(
                                    modifier = Modifier.size(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = themePrimary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimingAdjustPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val backgroundColor = if (isPrimary) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    }
    val textColor = if (isPrimary) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, radius = 22.dp),
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(backgroundColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ManualSearchDialog(
    initialTitle: String,
    initialArtist: String,
    onDismiss: () -> Unit,
    onSearch: (title: String, artist: String) -> Unit
) {
    val context = LocalContext.current
    val cleanInitialTitle = remember(initialTitle) { LyricsSearchQueryBuilder.cleanMetadata(initialTitle) }
    val cleanInitialArtist = remember(initialArtist) { LyricsSearchQueryBuilder.cleanMetadata(initialArtist) }

    var title by remember { mutableStateOf(cleanInitialTitle) }
    var artist by remember { mutableStateOf(cleanInitialArtist) }
    var selectedFormat by remember { mutableStateOf(LyricsFileFormat.LRC) }

    fun launchGoogleSearch() {
        val query = LyricsSearchQueryBuilder.buildQuery(title, artist, selectedFormat)
        if (query.isBlank()) {
            Toast.makeText(context, "Please enter a song title to search", Toast.LENGTH_SHORT).show()
            return
        }
        val url = LyricsSearchQueryBuilder.buildGoogleSearchUrl(query)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            onDismiss()
        } catch (e: Exception) {
            Toast.makeText(context, "No web browser app found to open search", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Search Lyrics Online",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Track Title") },
                    placeholder = { Text("e.g. Cry") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_title_input")
                )

                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist Name") },
                    placeholder = { Text("e.g. Cigarettes After Sex") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_artist_input")
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "Desired File Format:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LyricsFileFormat.entries.forEach { format ->
                        val isSelected = selectedFormat == format
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedFormat = format },
                            label = { Text(format.displayName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ),
                            modifier = Modifier.testTag("format_chip_${format.name.lowercase()}")
                        )
                    }
                }

                Text(
                    text = selectedFormat.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanT = LyricsSearchQueryBuilder.cleanMetadata(title)
                    val cleanA = LyricsSearchQueryBuilder.cleanMetadata(artist)
                    if (cleanT.isBlank()) {
                        Toast.makeText(context, "Please enter a song title to search", Toast.LENGTH_SHORT).show()
                    } else {
                        onSearch(cleanT, cleanA)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.testTag("submit_search_button")
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Search Lyrics", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = { launchGoogleSearch() },
                    modifier = Modifier.testTag("submit_google_search_button")
                ) {
                    Text("Search Web")
                }

                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

/**
 * Isolated word-synced karaoke text renderer.
 * Keeps high-frequency progress ticking local to this composable only, preventing the parent
 * LyricsScreen from recomposing 30 times a second.
 */
@Composable
private fun WordSyncedLyricText(
    line: com.example.data.model.LyricLine,
    playbackState: com.example.data.model.PlaybackStateInfo,
    trackOffset: Long,
    karaokeGlowSpeed: Float,
    fontSize: androidx.compose.ui.unit.TextUnit,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    fontFamily: androidx.compose.ui.text.font.FontFamily,
    onSurfaceColor: Color,
    themePrimary: Color,
    textAlign: TextAlign,
    modifier: Modifier = Modifier
) {
    var currentPos by remember { mutableLongStateOf(playbackState.calculatedPositionMs + trackOffset) }

    LaunchedEffect(playbackState.isPlaying, playbackState.positionMs, playbackState.lastUpdatedMs, playbackState.playbackSpeed, trackOffset) {
        if (playbackState.isPlaying) {
            while (isActive) {
                currentPos = playbackState.calculatedPositionMs + trackOffset
                kotlinx.coroutines.delay(33)
            }
        } else {
            currentPos = playbackState.calculatedPositionMs + trackOffset
        }
    }

    val annotatedString = remember(line, currentPos, karaokeGlowSpeed, onSurfaceColor, themePrimary) {
        androidx.compose.ui.text.buildAnnotatedString {
            line.words.forEachIndexed { wordIdx, word ->
                val wordProg = com.example.domain.lyricsengine.LyricsTimingEngine.calculateWordProgress(
                    word = word,
                    currentPositionMs = currentPos,
                    speedMultiplier = karaokeGlowSpeed
                )
                val wordColor = if (wordProg >= 1f) {
                    onSurfaceColor
                } else if (wordProg <= 0f) {
                    onSurfaceColor.copy(alpha = MinimalOverlayView.INACTIVE_LYRIC_ALPHA_RATIO)
                } else {
                    themePrimary
                }
                val wordWeight = if (wordProg > 0f) FontWeight.ExtraBold else FontWeight.Bold
                pushStyle(androidx.compose.ui.text.SpanStyle(color = wordColor, fontWeight = wordWeight))
                append(word.word)
                pop()
                if (wordIdx < line.words.size - 1) {
                    append(" ")
                }
            }
        }
    }

    Text(
        text = annotatedString,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontFamily = fontFamily,
        textAlign = textAlign,
        modifier = modifier
    )
}

fun formatPlaybackTime(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
fun CustomMiniPlayerSlider(
    progress: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    themePrimary: Color,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { 6.dp.toPx() }
    val trackHeightPx = with(density) { 3.5.dp.toPx() }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { offset ->
                        val widthPx = size.width.toFloat()
                        if (widthPx > 0) {
                            val newProgress = (offset.x / widthPx).coerceIn(0f, 1f)
                            onValueChange(newProgress)
                            onValueChangeFinished()
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val widthPx = size.width.toFloat()
                        if (widthPx > 0) {
                            val newProgress = (offset.x / widthPx).coerceIn(0f, 1f)
                            onValueChange(newProgress)
                        }
                    },
                    onDragEnd = { onValueChangeFinished() },
                    onDragCancel = { onValueChangeFinished() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val widthPx = size.width.toFloat()
                        if (widthPx > 0) {
                            val newProgress = (change.position.x / widthPx).coerceIn(0f, 1f)
                            onValueChange(newProgress)
                        }
                    }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
        ) {
            val centerY = size.height / 2f
            // 1. Inactive background track
            drawLine(
                color = Color.White.copy(alpha = 0.22f),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = trackHeightPx,
                cap = StrokeCap.Round
            )

            // 2. Active pink progress track
            val activeEnd = (size.width * progress).coerceIn(0f, size.width)
            if (activeEnd > 0f) {
                drawLine(
                    color = themePrimary,
                    start = Offset(0f, centerY),
                    end = Offset(activeEnd, centerY),
                    strokeWidth = trackHeightPx,
                    cap = StrokeCap.Round
                )
            }

            // 3. Circular thumb in pure white
            drawCircle(
                color = Color.White,
                radius = thumbRadiusPx,
                center = Offset(activeEnd, centerY)
            )
        }
    }
}

