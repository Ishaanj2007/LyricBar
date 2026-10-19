package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.ui.theme.MintAccent
import com.example.ui.theme.SkyAccent
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

    // SAF File Picker
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
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
                }
            }
        }
    }

    val activeIndex = remember(playbackState.calculatedPositionMs, currentLyrics, trackOffset) {
        currentLyrics?.findActiveIndex(playbackState.calculatedPositionMs, trackOffset) ?: -1
    }

    // Smooth auto-scroll keeping active line vertically centered like Apple Music
    LaunchedEffect(activeIndex) {
        if (activeIndex in 0..(currentLyrics?.lines?.size ?: 0)) {
            val targetScroll = (activeIndex - 2).coerceAtLeast(0)
            listState.animateScrollToItem(targetScroll)
        }
    }

    // Apple Music signature atmospheric gradient mesh background
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                // Top-left atmospheric radial glow (deep twilight cyan)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x3338E8B0), Color.Transparent),
                        center = Offset(size.width * 0.2f, size.height * 0.25f),
                        radius = size.width * 0.8f
                    )
                )
                // Bottom-right atmospheric radial glow (deep royal purple)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x384E95FF), Color.Transparent),
                        center = Offset(size.width * 0.85f, size.height * 0.75f),
                        radius = size.width * 0.9f
                    )
                )
            }
            .background(Color(0xFF0A0D14))
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
                        color = Color(0xFF94A3B8),
                        maxLines = 1
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val lyrics = currentLyrics
                    if (lyrics != null) {
                        Surface(
                            color = if (lyrics.isWordSynced) Color(0x3338E8B0) else if (lyrics.isSynced) Color(0x334E95FF) else Color(0x33FFB74D),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text(
                                text = if (lyrics.isWordSynced) "TTML WORD-SYNC" else if (lyrics.isSynced) "LRC LINE-TIMED" else "PLAIN",
                                color = if (lyrics.isWordSynced) MintAccent else if (lyrics.isSynced) SkyAccent else Color(0xFFFFB74D),
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
                        Icon(Icons.Default.Refresh, contentDescription = "Resync Lyric Timeline", tint = MintAccent)
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
                        Icon(Icons.Default.UploadFile, contentDescription = "Import TTML/LRC", tint = SkyAccent)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Spacer(modifier = Modifier.height(6.dp))

            // Lyrics List
            val lyrics = currentLyrics
            Box(modifier = Modifier.weight(1f)) {
                when {
                    isLoading -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = MintAccent)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Searching synchronized lyrics…",
                                color = Color(0xFF94A3B8)
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
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Search for lyrics online or import your own .ttml or .lrc file",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF94A3B8),
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
                                    colors = ButtonDefaults.buttonColors(containerColor = MintAccent),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("empty_state_search_button")
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF0D0F14))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Search Online", color = Color(0xFF0D0F14))
                                }
                                Spacer(Modifier.width(12.dp))
                                OutlinedButton(
                                    onClick = { filePicker.launch(arrayOf("text/*", "*/*")) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.testTag("empty_state_import_button")
                                ) {
                                    Icon(Icons.Default.UploadFile, contentDescription = null, tint = SkyAccent)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Import File", color = SkyAccent)
                                }
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 20.dp, bottom = 120.dp)
                        ) {
                            itemsIndexed(lyrics.lines) { index, line ->
                                val isActive = index == activeIndex
                                val isPast = activeIndex != -1 && index < activeIndex

                                val targetAlpha = when {
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
                                            val currentPos = playbackState.calculatedPositionMs + trackOffset
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
                                                            MintAccent
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
                                                        color = MintAccent,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                        }

                                        // Translation line if available from TTML metadata
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
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(24.dp)),
                    color = Color(0xD91E2330),
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
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = playbackState.track?.artist ?: "",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8),
                                maxLines = 1
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        IconButton(
                            onClick = { playbackManager.togglePlayPause() },
                            modifier = Modifier
                                .size(40.dp)
                                .background(MintAccent, CircleShape)
                        ) {
                            Icon(
                                imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                                tint = Color(0xFF0D0F14)
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
    var title by remember { mutableStateOf(initialTitle) }
    var artist by remember { mutableStateOf(initialArtist) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search Lyrics Online", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Track Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("search_title_input")
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("search_artist_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSearch(title, artist) },
                colors = ButtonDefaults.buttonColors(containerColor = MintAccent),
                modifier = Modifier.testTag("submit_search_button")
            ) {
                Text("Search", color = Color(0xFF0D0F14))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
