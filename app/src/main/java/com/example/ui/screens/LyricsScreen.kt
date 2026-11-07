package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TrackMetadata
import com.example.domain.lyricsengine.LyricsFileFormat
import com.example.domain.lyricsengine.LyricsSearchQueryBuilder
import com.example.domain.parser.LrcParser
import com.example.service.MediaPlaybackManager
import com.example.ui.components.FrostedCard
import com.example.ui.components.LocalHazeState
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

@Composable
fun LyricsScreen(
    playbackManager: MediaPlaybackManager,
    userPreferences: com.example.data.preferences.UserPreferences = com.example.data.preferences.UserPreferences(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val playbackState by playbackManager.playbackState.collectAsStateWithLifecycle()
    val currentLyrics by playbackManager.currentLyrics.collectAsStateWithLifecycle()
    val trackOffset by playbackManager.trackOffsetMs.collectAsStateWithLifecycle()
    val isLoading by playbackManager.isLoadingLyrics.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var showManualSearchDialog by remember { mutableStateOf(false) }
    var isDelayExpanded by rememberSaveable { mutableStateOf(false) }

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

                    val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                        BufferedReader(InputStreamReader(stream)).readText()
                    } ?: ""

                    if (text.isBlank()) {
                        Toast.makeText(context, "Lyric file is empty", Toast.LENGTH_SHORT).show()
                        return@launch
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

                    playbackManager.saveImportedLyrics(track, text, "Local .lrc")
                    Toast.makeText(context, "Lyrics imported successfully", Toast.LENGTH_SHORT).show()
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
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
                // Top Bar: Track Details & Badges & Action Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playbackState.track?.title ?: currentLyrics?.title?.takeIf { it.isNotBlank() } ?: "Lyrics",
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = playbackState.track?.artist ?: currentLyrics?.artist?.takeIf { it.isNotBlank() } ?: "Synced lyrics view",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val lyrics = currentLyrics
                        if (lyrics != null) {
                            Surface(
                                color = if (lyrics.isSynced) themePrimary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(
                                    0.5.dp,
                                    if (lyrics.isSynced) themePrimary.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.12f)
                                ),
                                modifier = Modifier.padding(end = 4.dp)
                            ) {
                                Text(
                                    text = if (lyrics.isSynced) "LRC LINE-TIMED" else "PLAIN",
                                    color = if (lyrics.isSynced) themePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Surface(
                            onClick = { playbackManager.resyncLyrics(forceRefresh = true) },
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.08f),
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("lyrics_resync_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "Resync Lyric Timeline",
                                    tint = themePrimary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Surface(
                            onClick = { showManualSearchDialog = true },
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.08f),
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("search_lyrics_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "Search",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Surface(
                            onClick = { filePicker.launch(arrayOf("text/*", "*/*")) },
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.08f),
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("upload_lrc_icon_button")
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.UploadFile,
                                    contentDescription = "Import LRC",
                                    tint = themeSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // Compact Expandable Timing Offset Card
                val lyrics = currentLyrics
                if (lyrics != null && lyrics.isSynced) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val offsetSec = trackOffset / 1000.0
                    val formattedOffset = if (trackOffset > 0) "+%.1fs".format(offsetSec) else if (trackOffset < 0) "%.1fs".format(offsetSec) else "0.0s (In Sync)"

                    FrostedCard(
                        shape = RoundedCornerShape(18.dp),
                        backgroundAlpha = 0.18f,
                        blurRadius = 18.dp,
                        borderWidth = 0.75.dp,
                        shadowElevation = 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("lyrics_delay_control_card")
                            .animateContentSize(animationSpec = tween(durationMillis = 200))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { isDelayExpanded = !isDelayExpanded }
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Timer,
                                        contentDescription = null,
                                        tint = themePrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "TIMING OFFSET",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.8.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (trackOffset != 0L) themePrimary.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.08f),
                                        modifier = Modifier.padding(end = 2.dp)
                                    ) {
                                        Text(
                                            text = formattedOffset,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (trackOffset != 0L) themePrimary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                                .testTag("lyrics_delay_display")
                                        )
                                    }
                                    Icon(
                                        imageVector = if (isDelayExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = if (isDelayExpanded) "Collapse offset controls" else "Expand offset controls",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            if (isDelayExpanded) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TimingAdjustPill(
                                        text = "-0.5s",
                                        onClick = { playbackManager.adjustTrackOffset(-500L) },
                                        modifier = Modifier.weight(1f).testTag("lyrics_delay_minus_500")
                                    )
                                    TimingAdjustPill(
                                        text = "-0.1s",
                                        onClick = { playbackManager.adjustTrackOffset(-100L) },
                                        modifier = Modifier.weight(1f).testTag("lyrics_delay_minus_100")
                                    )
                                    if (trackOffset != 0L) {
                                        TimingAdjustPill(
                                            text = "Reset",
                                            isPrimary = true,
                                            onClick = { playbackManager.setExplicitOffset(0L) },
                                            modifier = Modifier.weight(1f).testTag("lyrics_delay_reset")
                                        )
                                    }
                                    TimingAdjustPill(
                                        text = "+0.1s",
                                        onClick = { playbackManager.adjustTrackOffset(100L) },
                                        modifier = Modifier.weight(1f).testTag("lyrics_delay_plus_100")
                                    )
                                    TimingAdjustPill(
                                        text = "+0.5s",
                                        onClick = { playbackManager.adjustTrackOffset(500L) },
                                        modifier = Modifier.weight(1f).testTag("lyrics_delay_plus_500")
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Lyrics Content Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        lyrics == null || lyrics.lines.isEmpty() -> {
                            // Centered empty state card in the middle of lyrics view
                            FrostedCard(
                                shape = RoundedCornerShape(24.dp),
                                backgroundAlpha = 0.22f,
                                blurRadius = 24.dp,
                                borderWidth = 0.75.dp,
                                borderAlpha = 0.28f,
                                shadowElevation = 6.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 480.dp)
                                    .padding(horizontal = 16.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = themePrimary.copy(alpha = 0.15f),
                                        modifier = Modifier.size(68.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.MusicNote,
                                                contentDescription = "No lyrics icon",
                                                tint = themePrimary,
                                                modifier = Modifier.size(34.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Text(
                                        text = if (playbackState.track != null) "No lyrics found for this track" else "No track currently playing",
                                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = "Search for matching lyrics online or select an LRC file manually",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center,
                                        lineHeight = 18.sp
                                    )

                                    if (isLoading) {
                                        Spacer(modifier = Modifier.height(14.dp))
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
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(20.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Button(
                                            onClick = { showManualSearchDialog = true },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = themePrimary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            ),
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
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
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "Search Lyrics",
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        Spacer(Modifier.width(12.dp))

                                        OutlinedButton(
                                            onClick = { filePicker.launch(arrayOf("text/*", "*/*")) },
                                            shape = RoundedCornerShape(12.dp),
                                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
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
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                "Import Lyrics",
                                                color = themeSecondary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            val onSurfaceColor = MaterialTheme.colorScheme.onSurface
                            val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant

                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(top = 10.dp, bottom = 180.dp)
                            ) {
                                itemsIndexed(
                                    items = lyrics.lines,
                                    key = { index, line -> "${line.timeMs}_${index}" }
                                ) { index, line ->
                                    val isActive = index == activeIndex
                                    val isPast = activeIndex != -1 && index < activeIndex

                                    val targetAlpha = when {
                                        !lyrics.isSynced -> 0.95f
                                        isActive -> 1.0f
                                        isPast -> 0.40f
                                        else -> 0.48f
                                    }
                                    val alpha by animateFloatAsState(
                                        targetValue = targetAlpha,
                                        animationSpec = tween(durationMillis = 200),
                                        label = "lyric_alpha"
                                    )
                                    val lyricTextColor by animateColorAsState(
                                        targetValue = if (isActive) onSurfaceColor else onSurfaceVariantColor.copy(alpha = alpha),
                                        animationSpec = tween(durationMillis = 220),
                                        label = "lyric_text_color"
                                    )
                                    val lineScale by animateFloatAsState(
                                        targetValue = if (isActive) 1.02f else 1.0f,
                                        animationSpec = tween(durationMillis = 200),
                                        label = "lyric_line_scale"
                                    )

                                    val lineModifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = if (isActive) 5.dp else 3.dp)
                                        .graphicsLayer {
                                            scaleX = lineScale
                                            scaleY = lineScale
                                        }
                                        .clickable {
                                            if (lyrics.isSynced) {
                                                userScrolledAway = false
                                                playbackManager.seekTo(line.timeMs)
                                            }
                                        }

                                    val lineContent: @Composable BoxScope.() -> Unit = {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(
                                                    horizontal = 14.dp,
                                                    vertical = if (isActive) 12.dp else 6.dp
                                                ),
                                            horizontalAlignment = Alignment.End
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                // Time badge on active line if synced
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

                                                val inAppTextAlign = TextAlign.End

                                                if (isActive && line.isWordSynced && line.words.isNotEmpty()) {
                                                    WordSyncedLyricText(
                                                        line = line,
                                                        playbackState = playbackState,
                                                        trackOffset = trackOffset,
                                                        karaokeGlowSpeed = userPreferences.karaokeGlowSpeed,
                                                        fontSize = if (userPreferences.appleMusicStyle) 26.sp else 23.sp,
                                                        lineHeight = if (userPreferences.appleMusicStyle) 34.sp else 30.sp,
                                                        fontFamily = com.example.ui.theme.getFontFamily(userPreferences.selectedFont),
                                                        onSurfaceColor = onSurfaceColor,
                                                        themePrimary = themePrimary,
                                                        textAlign = inAppTextAlign,
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
                                                        textAlign = inAppTextAlign,
                                                        modifier = Modifier.weight(1f, fill = false)
                                                    )
                                                }
                                            }

                                            // Translation line if available from lyric metadata
                                            if (line.translation != null) {
                                                Spacer(modifier = Modifier.height(3.dp))
                                                Text(
                                                    text = line.translation,
                                                    fontSize = if (isActive) 13.sp else 12.sp,
                                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                                    textAlign = TextAlign.End,
                                                    color = if (isActive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f)
                                                )
                                            }
                                        }
                                    }

                                    if (isActive) {
                                        FrostedCard(
                                            modifier = lineModifier,
                                            shape = RoundedCornerShape(16.dp),
                                            backgroundAlpha = 0.28f,
                                            blurRadius = 18.dp,
                                            borderWidth = 0.75.dp,
                                            borderAlpha = 0.30f,
                                            shadowElevation = 2.dp,
                                            content = lineContent
                                        )
                                    } else {
                                        Box(
                                            modifier = lineModifier.clip(RoundedCornerShape(16.dp)),
                                            content = lineContent
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating Resume Live Lyrics Control:
        // Appears when user has manually scrolled away from the active line in a synced lyrics track.
        // Tapping smoothly brings the viewport back to the current active line and re-enables auto-following.
        val showResumeButton = userScrolledAway && currentLyrics?.isSynced == true && activeIndex in 0 until (currentLyrics?.lines?.size ?: 0)
        AnimatedVisibility(
            visible = showResumeButton,
            enter = fadeIn(tween(200)) + slideInVertically(tween(250)) { it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(200)) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = if (playbackState.track != null) 140.dp else 80.dp)
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
                    .clickable {
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

        // Floating Frosted Playback Capsule at bottom, positioned above floating navigation
        if (playbackState.track != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 76.dp)
            ) {
                FrostedCard(
                    shape = RoundedCornerShape(22.dp),
                    backgroundAlpha = 0.22f,
                    blurRadius = 22.dp,
                    borderWidth = 0.75.dp,
                    borderAlpha = 0.24f,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playbackState.track?.title ?: "",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = playbackState.track?.artist ?: "",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Surface(
                            onClick = { playbackManager.togglePlayPause() },
                            shape = CircleShape,
                            color = themePrimary,
                            modifier = Modifier.size(42.dp),
                            shadowElevation = 4.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
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
                        .getOrFetchLyrics(track, forceNetwork = true, allowNetwork = true)
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
                    onSurfaceColor.copy(alpha = 0.35f)
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

