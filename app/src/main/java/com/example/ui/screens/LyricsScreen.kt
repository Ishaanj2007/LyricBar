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
    modifier: Modifier = Modifier
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .onGloballyPositioned { coords ->
                rootPositionInWindow = coords.positionInWindow()
                rootSizePx = coords.size
            }
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
                // Song Header — Single Title Only (Pixel-accurate to reference design)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left: Square Album Art Thumbnail with clean rounded corners
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
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
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    // Thin vertical divider line next to thumbnail
                    Box(
                        modifier = Modifier
                            .height(32.dp)
                            .width(1.dp)
                            .background(Color.White.copy(alpha = 0.22f))
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    // Title on top, and (Artist + 3 Circular Action Buttons) below
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playbackState.track?.title ?: currentLyrics?.title?.takeIf { it.isNotBlank() } ?: "Someone To Spend Time With",
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = playbackState.track?.artist ?: currentLyrics?.artist?.takeIf { it.isNotBlank() } ?: "Los Retros",
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp),
                                fontWeight = FontWeight.Medium,
                                color = Color.White.copy(alpha = 0.70f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )

                            Spacer(modifier = Modifier.width(6.dp))

                            // Top Action Buttons: Refresh, Search, Import LRC
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Action 1: Refresh / Resync Lyrics (Pink icon)
                                Surface(
                                    onClick = { playbackManager.resyncLyrics(forceRefresh = true) },
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

                                // Action 2: Search Lyrics (White magnifying glass icon)
                                Surface(
                                    onClick = { showManualSearchDialog = true },
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

                                // Action 3: Import LRC (Document with '+' icon)
                                Surface(
                                    onClick = { filePicker.launch(arrayOf("text/*", "*/*")) },
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
                    }
                }

                // Combined Provider and Lyrics Delay Panel in ONE horizontal frosted-glass surface
                FrostedCard(
                    hazeState = hazeState,
                    shape = RoundedCornerShape(22.dp),
                    backgroundAlpha = 0.22f,
                    blurRadius = 24.dp,
                    borderWidth = 0.75.dp,
                    borderAlpha = 0.20f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // LEFT SECTION — PROVIDER (Approximately 50% width)
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = true, color = themePrimary),
                                    onClick = { isProviderMenuExpanded = !isProviderMenuExpanded }
                                )
                                .onGloballyPositioned { coords ->
                                    selectorPositionInWindow = coords.positionInWindow()
                                    selectorSizePx = coords.size
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
                                    text = selectedProvider.displayName,
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

                        // Subtle vertical divider between sections
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .width(1.dp)
                                .height(24.dp)
                                .background(Color.White.copy(alpha = 0.18f))
                        )

                        // RIGHT SECTION — LYRICS DELAY (Approximately 50% width)
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = true, color = themePrimary),
                                    onClick = { isDelayExpanded = !isDelayExpanded }
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

                // Expandable timing offset adjustment pills
                AnimatedVisibility(
                    visible = isDelayExpanded,
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
                            .padding(bottom = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
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

                Spacer(modifier = Modifier.height(8.dp))

                // Lyrics Content Area
                val lyrics = currentLyrics
                val actualSourceLabel = if (lyrics != null && lyrics.hasValidLyrics) lyrics.displaySource else ""

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        lyrics == null || !lyrics.hasValidLyrics -> {
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

        // Floating Frosted Bottom Mini-Player: Exact reference design
        // Positioned above floating bottom navigation; Contains album art, seek bar with position & duration, and play/pause
        // Does not repeat song title/artist
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 16.dp)
                .padding(bottom = 76.dp)
        ) {
            FrostedCard(
                hazeState = hazeState,
                shape = RoundedCornerShape(26.dp),
                backgroundAlpha = 0.24f,
                blurRadius = 24.dp,
                borderWidth = 0.75.dp,
                borderAlpha = 0.22f,
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .testTag("lyrics_bottom_mini_player")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // LEFT: Album Art Thumbnail
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(12.dp))
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
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // CENTER: Playback Seek Bar + Elapsed Time (left) + Duration (right)
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
                                playbackManager.seekTo(targetMs)
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
                                .padding(top = 2.dp),
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

                    // RIGHT: Large Circular Pink Play/Pause Button
                    Surface(
                        onClick = { playbackManager.togglePlayPause() },
                        shape = CircleShape,
                        color = themePrimary,
                        modifier = Modifier
                            .size(46.dp)
                            .testTag("lyrics_mini_player_play_pause"),
                        shadowElevation = 4.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                                tint = Color(0xFF380720),
                                modifier = Modifier.size(24.dp)
                            )
                        }
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
    hazeState: HazeState?
) {
    val density = LocalDensity.current
    val themePrimary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    var menuSizePx by remember { mutableStateOf(IntSize.Zero) }

    val horizontalMarginPx = with(density) { 16.dp.roundToPx() }
    val verticalGapPx = with(density) { 6.dp.roundToPx() }
    val bottomSafePaddingPx = with(density) { 88.dp.roundToPx() }

    val rawX = (selectorPositionInWindow.x - rootPositionInWindow.x).roundToInt()
    val rawY = (selectorPositionInWindow.y - rootPositionInWindow.y + selectorSizePx.height + verticalGapPx).roundToInt()

    val clampedX = if (rootSizePx.width > 0 && menuSizePx.width > 0) {
        val maxX = (rootSizePx.width - menuSizePx.width - horizontalMarginPx).coerceAtLeast(horizontalMarginPx)
        rawX.coerceIn(horizontalMarginPx, maxX)
    } else {
        rawX.coerceAtLeast(horizontalMarginPx)
    }

    val clampedY = rawY.coerceAtLeast(with(density) { 44.dp.roundToPx() })

    val maxMenuWidthDp = if (rootSizePx.width > 0) {
        with(density) {
            minOf(244.dp, (rootSizePx.width - horizontalMarginPx * 2).toDp().coerceAtLeast(196.dp))
        }
    } else {
        240.dp
    }

    val maxMenuHeightDp = if (rootSizePx.height > 0) {
        with(density) {
            val availablePx = (rootSizePx.height - clampedY - bottomSafePaddingPx).coerceAtLeast(156.dp.roundToPx())
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

