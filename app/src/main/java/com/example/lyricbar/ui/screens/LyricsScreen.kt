package com.example.lyricbar.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lyricbar.data.model.LyricFormat
import com.example.lyricbar.data.model.LyricLine
import com.example.lyricbar.data.model.Track
import com.example.lyricbar.data.repository.LyricsRepository
import com.example.lyricbar.domain.lyricsengine.SearchQueryBuilder
import com.example.lyricbar.ui.components.FormatSelector
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsScreen(
    currentTrack: Track,
    lyrics: List<LyricLine>,
    onLyricsLoaded: (List<LyricLine>) -> Unit,
    timeOffsetMs: Long,
    onOffsetChanged: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { LyricsRepository() }

    var selectedFormat by remember { mutableStateOf(LyricFormat.LRC) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var searchTitle by remember { mutableStateOf(currentTrack.title) }
    var searchArtist by remember { mutableStateOf(currentTrack.artist) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(currentTrack) {
        searchTitle = currentTrack.title
        searchArtist = currentTrack.artist
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    val text = stream.bufferedReader().readText()
                    val isTtml = uri.toString().endsWith(".ttml", ignoreCase = true)
                    val parsed = repository.parseLocalLyrics(text, isTtml)
                    if (parsed.isNotEmpty()) {
                        onLyricsLoaded(parsed)
                        Toast.makeText(context, "Loaded ${parsed.size} lyric lines", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "No valid lyrics found in file", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to read file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Full Lyrics") },
                actions = {
                    IconButton(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.testTag("import_file_button")
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = "Import Lyric File")
                    }
                    IconButton(
                        onClick = { showSearchDialog = true },
                        modifier = Modifier.testTag("search_lyrics_button")
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search Google for Lyrics")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Track Info Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (currentTrack.title.isBlank()) "No Track Playing" else currentTrack.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (currentTrack.artist.isNotBlank()) {
                        Text(
                            text = currentTrack.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Offset Controls
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Sync Offset: ${timeOffsetMs}ms", style = MaterialTheme.typography.labelLarge)
                        Row {
                            OutlinedButton(onClick = { onOffsetChanged(timeOffsetMs - 500) }) {
                                Text("-0.5s")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            OutlinedButton(onClick = { onOffsetChanged(timeOffsetMs + 500) }) {
                                Text("+0.5s")
                            }
                        }
                    }
                }
            }

            // Online Fetch Button
            Button(
                onClick = {
                    if (currentTrack.title.isBlank()) {
                        Toast.makeText(context, "No song title available to fetch", Toast.LENGTH_SHORT).show()
                    } else {
                        isLoading = true
                        scope.launch {
                            val res = repository.fetchOnlineLyrics(currentTrack)
                            isLoading = false
                            res.onSuccess {
                                onLyricsLoaded(it)
                                Toast.makeText(context, "Lyrics fetched online!", Toast.LENGTH_SHORT).show()
                            }.onFailure { err ->
                                Toast.makeText(context, err.message ?: "Failed to fetch lyrics", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("fetch_online_lyrics_button")
            ) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Fetch Lyrics Online (LRCLIB)")
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Lyrics List
            if (lyrics.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No lyrics loaded.\nTap 'Search Lyrics' to find .lrc files on Google or 'Import' to load local files.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    itemsIndexed(lyrics) { idx, line ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier
                                    .padding(12.dp)
                                    .fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = line.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = formatTimestamp(line.startTimeMs),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Google Search Lyrics Dialog
    if (showSearchDialog) {
        AlertDialog(
            onDismissRequest = { showSearchDialog = false },
            title = { Text("Search Lyrics on Google") },
            text = {
                Column {
                    Text("Select desired file format and verify song metadata before searching:")
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = searchTitle,
                        onValueChange = { searchTitle = it },
                        label = { Text("Song Title") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = searchArtist,
                        onValueChange = { searchArtist = it },
                        label = { Text("Artist Name") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    FormatSelector(
                        selectedFormat = selectedFormat,
                        onFormatSelected = { selectedFormat = it }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    val searchPreview = SearchQueryBuilder.buildQuery(searchTitle, searchArtist, selectedFormat)
                    Text(
                        text = "Search Query: \"$searchPreview\"",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val tempTrack = Track(title = searchTitle, artist = searchArtist)
                        val result = SearchQueryBuilder.openGoogleSearch(context, tempTrack, selectedFormat)
                        result.onFailure { err ->
                            Toast.makeText(context, err.message ?: "Could not open browser", Toast.LENGTH_LONG).show()
                        }
                        showSearchDialog = false
                    },
                    modifier = Modifier.testTag("launch_google_search_button")
                ) {
                    Text("Search in Browser")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSearchDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun formatTimestamp(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%02d:%02d", min, sec)
}
