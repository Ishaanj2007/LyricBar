package com.example.lyricbar.ui.screens

import android.content.Context
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.lyricbar.R
import com.example.lyricbar.data.model.Track
import com.example.lyricbar.service.OverlayService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    currentTrack: Track,
    isOverlayRunning: Boolean,
    onToggleOverlay: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hasOverlayPermission = Settings.canDrawOverlays(context)

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Now Playing") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Album Art Placeholder
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = "Album Art",
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Track Title & Artist
            Text(
                text = if (currentTrack.title.isBlank()) "No Music Playing" else currentTrack.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (currentTrack.artist.isBlank()) "Play music in Spotify, YouTube Music, or any media app" else currentTrack.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Overlay Toggle Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Floating Overlay Status",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = if (isOverlayRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(12.dp)
                        ) {}
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isOverlayRunning) "Active Floating Overlay" else "Overlay Inactive",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (!hasOverlayPermission) {
                        Button(
                            onClick = onNavigateToPermissions,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Shield, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Grant Overlay Permission")
                        }
                    } else {
                        Button(
                            onClick = onToggleOverlay,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("toggle_overlay_button")
                        ) {
                            Icon(
                                imageVector = if (isOverlayRunning) Icons.Default.Pause else Icons.Default.Visibility,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isOverlayRunning) stringResource(R.string.overlay_toggle_off) else stringResource(R.string.overlay_toggle_on))
                        }
                    }
                }
            }
        }
    }
}
