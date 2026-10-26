package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.domain.lyricsengine.LyricsFileFormat
import com.example.domain.lyricsengine.LyricsSearchQueryBuilder
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.parser.LrcParser
import com.example.service.MediaPlaybackManager
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

    val playbackState by playbackManager.playbackState.collectAsState()
    val currentLyrics by playbackManager.currentLyrics.collectAsState()
    val trackOffset by playbackManager.trackOffsetMs.collectAsState()
    val isLoading by playbackManager.isLoadingLyrics.collectAsState()

    val listState = rememberLazyListState()
    var showManualSearchDialog by remember { mutableStateOf(false) }

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

                    if (fileName.isNotBlank() && !fileName.endsWith(".lrc", ignoreCase = true)) {
                        Toast.makeText(context, "Unsupported file format. Please select an .lrc file", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        val text = BufferedReader(InputStreamReader(stream)).readText()
                        val track = playbackState.track
                        val parsed = LrcParser.parse(
                            content = text,
                            title = track?.title ?: "Imported Track",
                            artist = track?.artist ?: "Local File",
                            source = "Local .lrc"
                        )
                        playbackManager.setManualLyrics(parsed)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, "Failed to import lyric file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Continuously ticking playback position when playing for smooth UI rendering & active line calculation
    var currentPlaybackPosMs by remember { mutableLongStateOf(playbackState.calculatedPositionMs) }

    LaunchedEffect(playbackState.isPlaying, playbackState.positionMs, playbackState.lastUpdatedMs, playbackState.playbackSpeed) {
        if (playbackState.isPlaying) {
            while (isActive) {
                currentPlaybackPosMs = playbackState.calculatedPositionMs
                kotlinx.coroutines.delay(33) // ~30fps UI refresh
            }
        } else {
            currentPlaybackPosMs = playbackState.calculatedPositionMs
        }
    }

    val activeIndex = remember(currentPlaybackPosMs, currentLyrics, trackOffset) {
        currentLyrics?.findActiveIndex(currentPlaybackPosMs, trackOffset) ?: -1
    }

    // Smooth auto-scroll keeping active line vertically centered like Apple Music
    LaunchedEffect(activeIndex) {
        if (activeIndex in 0 until (currentLyrics?.lines?.size ?: 0)) {
            val targetScroll = (activeIndex - 2).coerceAtLeast(0)
            listState.animateScrollToItem(targetScroll)
        }
    }

    val themePrimary = MaterialTheme.colorScheme.primary
    val themeSecondary = MaterialTheme.colorScheme.secondary

    // Apple Music signature atmospheric gradient mesh background
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                // Top-left atmospheric radial glow (adaptive dynamic primary)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(themePrimary.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(size.width * 0.2f, size.height * 0.25f),
                        radius = size.width * 0.8f
                    )
                )
                // Bottom-right atmospheric radial glow (adaptive dynamic secondary)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(themeSecondary.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.75f),
                        radius = size.width * 0.9f
                    )
                )
            }
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // Top Bar: Track Details & Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playbackState.track?.title ?: "Lyrics",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = playbackState.track?.artist ?: "Apple-style synced lyrics view",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val lyrics = currentLyrics
                    if (lyrics != null) {
                        Surface(
                            color = if (lyrics.isSynced) themeSecondary.copy(alpha = 0.2f) else Color(0x33FFB74D),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text(
                                text = if (lyrics.isSynced) "LRC LINE-TIMED" else "PLAIN",
                                color = if (lyrics.isSynced) themeSecondary else Color(0xFFFFB74D),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = { playbackManager.resyncLyrics(forceRefresh = true) },
                        modifier = Modifier.testTag("lyrics_resync_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Resync Lyric Timeline", tint = themePrimary)
                    }
                    IconButton(
                        onClick = { showManualSearchDialog = true },
                        modifier = Modifier.testTag("search_lyrics_button")
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search", tint = Color.White)
                    }
                    IconButton(
                        onClick = { filePicker.launch(arrayOf("text/*", "*/*")) },
                        modifier = Modifier.testTag("upload_lrc_icon_button")
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = "Import LRC", tint = themeSecondary)
                    }
                }
            }

            // Lyrics Delay / Timing Adjustment Bar
            val lyrics = currentLyrics
            if (lyrics != null && lyrics.isSynced) {
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth().testTag("lyrics_delay_control_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Timing Delay:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            val offsetSec = trackOffset / 1000.0
                            val formattedOffset = if (trackOffset > 0) "+%.1fs".format(offsetSec) else if (trackOffset < 0) "%.1fs".format(offsetSec) else "0.0s"
                            Text(
                                text = formattedOffset,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (trackOffset != 0L) themePrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("lyrics_delay_display")
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // -0.5s
                            FilledTonalButton(
                                onClick = { playbackManager.adjustTrackOffset(-500L) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.height(28.dp).testTag("lyrics_delay_minus_500")
                            ) {
                                Text("-0.5s", fontSize = 11.sp)
                            }
                            // -0.1s
                            FilledTonalButton(
                                onClick = { playbackManager.adjustTrackOffset(-100L) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.height(28.dp).testTag("lyrics_delay_minus_100")
                            ) {
                                Text("-0.1s", fontSize = 11.sp)
                            }
                            // Reset
                            if (trackOffset != 0L) {
                                FilledTonalButton(
                                    onClick = { playbackManager.setExplicitOffset(0L) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    modifier = Modifier.height(28.dp).testTag("lyrics_delay_reset")
                                ) {
                                    Text("Reset", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                                }
                            }
                            // +0.1s
                            FilledTonalButton(
                                onClick = { playbackManager.adjustTrackOffset(100L) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.height(28.dp).testTag("lyrics_delay_plus_100")
                            ) {
                                Text("+0.1s", fontSize = 11.sp)
                            }
                            // +0.5s
                            FilledTonalButton(
                                onClick = { playbackManager.adjustTrackOffset(500L) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.height(28.dp).testTag("lyrics_delay_plus_500")
                            ) {
                                Text("+0.5s", fontSize = 11.sp)
                            }
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Lyrics List
            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoading -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = themePrimary)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Searching synchronized lyrics…",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    lyrics == null || lyrics.lines.isEmpty() -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "No lyrics available for this track",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Search for lyrics online or import your own .lrc file",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(24.dp))
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
                                    modifier = Modifier.testTag("empty_state_search_button")
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Search Online", color = MaterialTheme.colorScheme.onPrimary)
                                }
                                Spacer(Modifier.width(12.dp))
                                OutlinedButton(
                                    onClick = { filePicker.launch(arrayOf("text/*", "*/*")) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("empty_state_import_button")
                                ) {
                                    Icon(Icons.Default.UploadFile, contentDescription = null, tint = themeSecondary)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Import File", color = themeSecondary)
                                }
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 10.dp, bottom = 120.dp)
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
                                    isPast -> 0.32f
                                    else -> 0.45f
                                }
                                val alpha by animateFloatAsState(
                                    targetValue = targetAlpha,
                                    label = "lyric_alpha"
                                )
                                val scale by animateFloatAsState(
                                    targetValue = if (isActive) 1.02f else 1.0f,
                                    animationSpec = spring(dampingRatio = 0.7f),
                                    label = "lyric_scale"
                                )

                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = if (isActive) 8.dp else 4.dp)
                                        .scale(scale)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            if (lyrics.isSynced) {
                                                playbackManager.seekTo(line.timeMs)
                                            }
                                        },
                                    color = if (isActive) Color(0x24FFFFFF) else Color.Transparent,
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = if (isActive) 14.dp else 8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val currentPos = currentPlaybackPosMs + trackOffset
                                            val inAppTextAlign = when (userPreferences.overlayTextAlign.uppercase()) {
                                                "LEFT" -> TextAlign.Start
                                                "RIGHT" -> TextAlign.End
                                                else -> TextAlign.Center
                                            }
                                            if (isActive && line.isWordSynced && line.words.isNotEmpty()) {
                                                // Apple Music Word-Level Synchronized Progressive Highlighting
                                                val annotatedString = androidx.compose.ui.text.buildAnnotatedString {
                                                    line.words.forEachIndexed { wordIdx, word ->
                                                        val wordProg = com.example.domain.lyricsengine.LyricsTimingEngine.calculateWordProgress(word, currentPos)
                                                        val wordColor = if (wordProg >= 1f) {
                                                            Color.White
                                                        } else if (wordProg <= 0f) {
                                                            Color.White.copy(alpha = 0.35f)
                                                        } else {
                                                            // Currently sung word glowing sweep
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

                                                Text(
                                                    text = annotatedString,
                                                    fontSize = if (userPreferences.appleMusicStyle) 28.sp else 24.sp,
                                                    lineHeight = if (userPreferences.appleMusicStyle) 36.sp else 30.sp,
                                                    fontFamily = com.example.ui.theme.getFontFamily(userPreferences.selectedFont),
                                                    textAlign = inAppTextAlign,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            } else {
                                                Text(
                                                    text = line.text,
                                                    fontSize = if (isActive) (if (userPreferences.appleMusicStyle) 28.sp else 24.sp) else 19.sp,
                                                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Bold,
                                                    color = Color.White.copy(alpha = alpha),
                                                    lineHeight = if (isActive) 36.sp else 28.sp,
                                                    fontFamily = com.example.ui.theme.getFontFamily(userPreferences.selectedFont),
                                                    textAlign = inAppTextAlign,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }

                                            if (lyrics.isSynced && isActive) {
                                                val minutes = (line.timeMs / 1000) / 60
                                                val seconds = (line.timeMs / 1000) % 60
                                                Surface(
                                                    color = Color(0x33000000),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.padding(start = 8.dp)
                                                ) {
                                                    Text(
                                                        text = "%02d:%02d".format(minutes, seconds),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = themePrimary,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                        }

                                        // Translation line if available from lyric metadata
                                        if (line.translation != null) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = line.translation,
                                                fontSize = if (isActive) 15.sp else 12.sp,
                                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                                color = if (isActive) Color(0xFFCBD5E1) else Color(0xFF64748B)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Apple Music Floating Glass Playback Capsule at bottom
            if (playbackState.track != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(24.dp)),
                    color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 10.dp),
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
                            Text(
                                text = playbackState.track?.artist ?: "",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        IconButton(
                            onClick = { playbackManager.togglePlayPause() },
                            modifier = Modifier
                                .size(40.dp)
                                .background(themePrimary, CircleShape)
                        ) {
                            Icon(
                                imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
            }
        }
    }

    if (showManualSearchDialog) {
        ManualSearchDialog(
            initialTitle = playbackState.track?.title ?: "",
            initialArtist = playbackState.track?.artist ?: "",
            onDismiss = { showManualSearchDialog = false },
            onSearch = { title, artist ->
                showManualSearchDialog = false
                coroutineScope.launch {
                    val track = com.example.data.model.TrackMetadata(
                        title = title,
                        artist = artist
                    )
                    val result = com.example.data.repository.CompositeLyricsRepository
                        .getInstance(context)
                        .getOrFetchLyrics(track)
                    if (result.isSuccess) {
                        playbackManager.setManualLyrics(result.getOrThrow())
                    }
                }
            }
        )
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
                onClick = { launchGoogleSearch() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.testTag("submit_search_button")
            ) {
                Text("Search Google", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(
                    onClick = {
                        val cleanT = LyricsSearchQueryBuilder.cleanMetadata(title)
                        val cleanA = LyricsSearchQueryBuilder.cleanMetadata(artist)
                        if (cleanT.isBlank()) {
                            Toast.makeText(context, "Please enter a song title to search", Toast.LENGTH_SHORT).show()
                        } else {
                            onSearch(cleanT, cleanA)
                        }
                    },
                    modifier = Modifier.testTag("submit_in_app_search_button")
                ) {
                    Text("In-App Lookup")
                }

                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
