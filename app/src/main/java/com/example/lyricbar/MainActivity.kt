package com.example.lyricbar

import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.lyricbar.data.local.PlaybackHistoryEntity
import com.example.lyricbar.data.model.LyricLine
import com.example.lyricbar.data.model.Track
import com.example.lyricbar.data.preferences.UserPreferences
import com.example.lyricbar.data.preferences.UserPreferencesRepository
import com.example.lyricbar.service.MediaPlaybackManager
import com.example.lyricbar.service.OverlayService
import com.example.lyricbar.ui.screens.*
import com.example.lyricbar.ui.theme.LyricBarTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val title: String, val icon: @Composable () -> Unit) {
    object NowPlaying : Screen("now_playing", "Now Playing", { Icon(Icons.Default.MusicNote, contentDescription = "Now Playing") })
    object Lyrics : Screen("lyrics", "Lyrics", { Icon(Icons.Default.ShortText, contentDescription = "Lyrics") })
    object History : Screen("history", "History", { Icon(Icons.Default.History, contentDescription = "History") })
    object Settings : Screen("settings", "Settings", { Icon(Icons.Default.SettingsIcon, contentDescription = "Settings") })
    object Permissions : Screen("permissions", "Permissions", { Icon(Icons.Default.Shield, contentDescription = "Permissions") })
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as LyricBarApp
        val preferencesRepo = UserPreferencesRepository(this)

        setContent {
            LyricBarTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()

                var currentScreen by remember { mutableStateOf<Screen>(Screen.NowPlaying) }
                val currentTrack by MediaPlaybackManager.currentTrack.collectAsState()
                val userPrefs by preferencesRepo.userPreferencesFlow.collectAsState(initial = UserPreferences())

                var lyricsList by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
                var timeOffsetMs by remember { mutableStateOf(0L) }
                var historyList by remember { mutableStateOf<List<PlaybackHistoryEntity>>(emptyList()) }
                var isOverlayRunning by remember { mutableStateOf(OverlayService.isServiceRunning) }

                // Observe Room History
                LaunchedEffect(Unit) {
                    app.database.historyDao().getRecentHistory().collectLatest {
                        historyList = it
                    }
                }

                // Log track to Room history when changed
                LaunchedEffect(currentTrack) {
                    if (currentTrack.title.isNotBlank()) {
                        app.database.historyDao().insertHistory(
                            PlaybackHistoryEntity(
                                title = currentTrack.title,
                                artist = currentTrack.artist,
                                album = currentTrack.album
                            )
                        )
                    }
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            val items = listOf(Screen.NowPlaying, Screen.Lyrics, Screen.History, Screen.Settings, Screen.Permissions)
                            items.forEach { screen ->
                                NavigationBarItem(
                                    selected = currentScreen.route == screen.route,
                                    onClick = { currentScreen = screen },
                                    icon = screen.icon,
                                    label = { Text(screen.title) },
                                    modifier = Modifier.testTag("nav_item_${screen.route}")
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    val modifier = Modifier.padding(innerPadding)

                    when (currentScreen) {
                        is Screen.NowPlaying -> NowPlayingScreen(
                            currentTrack = currentTrack,
                            isOverlayRunning = isOverlayRunning,
                            onToggleOverlay = {
                                if (!Settings.canDrawOverlays(context)) {
                                    Toast.makeText(context, "Overlay permission is required", Toast.LENGTH_SHORT).show()
                                    currentScreen = Screen.Permissions
                                } else {
                                    if (isOverlayRunning) {
                                        OverlayService.stopService(context)
                                        isOverlayRunning = false
                                    } else {
                                        OverlayService.startService(context)
                                        isOverlayRunning = true
                                    }
                                }
                            },
                            onNavigateToPermissions = { currentScreen = Screen.Permissions },
                            modifier = modifier
                        )

                        is Screen.Lyrics -> LyricsScreen(
                            currentTrack = currentTrack,
                            lyrics = lyricsList,
                            onLyricsLoaded = { lyricsList = it },
                            timeOffsetMs = timeOffsetMs,
                            onOffsetChanged = { newOffset ->
                                timeOffsetMs = newOffset
                                scope.launch { preferencesRepo.updateTimeOffset(newOffset) }
                            },
                            modifier = modifier
                        )

                        is Screen.History -> HistoryScreen(
                            historyList = historyList,
                            onClearHistory = {
                                scope.launch { app.database.historyDao().clearHistory() }
                            },
                            modifier = modifier
                        )

                        is Screen.Settings -> SettingsScreen(
                            userPreferences = userPrefs,
                            onFontSizeChanged = { scope.launch { preferencesRepo.updateFontSize(it) } },
                            onOpacityChanged = { scope.launch { preferencesRepo.updateOpacity(it) } },
                            onFontIndexChanged = { scope.launch { preferencesRepo.updateFontIndex(it) } },
                            onAnimationTypeChanged = { scope.launch { preferencesRepo.updateAnimationType(it) } },
                            onSingleLineModeChanged = { scope.launch { preferencesRepo.updateSingleLineMode(it) } },
                            modifier = modifier
                        )

                        is Screen.Permissions -> PermissionsScreen(
                            modifier = modifier
                        )
                    }
                }
            }
        }
    }
}
