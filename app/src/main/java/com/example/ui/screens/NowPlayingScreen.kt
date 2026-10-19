package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.preferences.UserPreferences
import com.example.service.MediaPlaybackManager
import com.example.service.OverlayService
import com.example.ui.theme.MintAccent
import kotlinx.coroutines.launch

@Composable
fun NowPlayingScreen(
    playbackManager: MediaPlaybackManager,
    userPreferences: UserPreferences,
    onNavigateToLyrics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val state by playbackManager.playbackState.collectAsState()
    val activeLyric by playbackManager.activeLyricLine.collectAsState()
    val track = state.track

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LyricBar",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            // Quick toggle for overlay
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (userPreferences.overlayEnabled) "HUD On" else "HUD Off",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (userPreferences.overlayEnabled) MintAccent else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = userPreferences.overlayEnabled,
                    onCheckedChange = { enabled ->
                        coroutineScope.launch {
                            playbackManager.preferencesRepository.setOverlayEnabled(enabled)
                            if (enabled) {
                                if (Settings.canDrawOverlays(context)) {
                                    OverlayService.start(context)
                                } else {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                }
                            } else {
                                OverlayService.stop(context)
                            }
                        }
                    },
                    modifier = Modifier.testTag("now_playing_overlay_switch")
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Center Album Art or Vinyl Placeholder
        Surface(
            modifier = Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 6.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = MintAccent,
                    modifier = Modifier.size(80.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Track Title & Artist
        Text(
            text = track?.title ?: "No Music Playing",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = track?.artist ?: "Play music on Spotify, YouTube Music, or any media player",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Active Lyric Snippet Box
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "LIVE LYRIC",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MintAccent
                    )
                    TextButton(
                        onClick = onNavigateToLyrics,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Full Lyrics", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = activeLyric ?: "Lyrics will sync automatically with audio...",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Media Action Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { playbackManager.previous() },
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "Previous Track",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp)
                )
            }

            Surface(
                onClick = { playbackManager.playPause() },
                modifier = Modifier.size(68.dp),
                shape = CircleShape,
                color = MintAccent,
                tonalElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            IconButton(
                onClick = { playbackManager.next() },
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "Next Track",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}
