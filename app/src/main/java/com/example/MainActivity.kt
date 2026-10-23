package com.example

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.preferences.UserPreferences
import com.example.service.OverlayService
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme

enum class AppTab(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    NOW_PLAYING("Now Playing", Icons.Default.PlayCircle),
    LYRICS("Lyrics", Icons.Default.FormatQuote),
    SETTINGS("Settings", Icons.Default.Tune),
    HISTORY("History", Icons.Default.History)
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as LyricBarApp
        val playbackManager = app.playbackManager

        setContent {
            val context = LocalContext.current
            val prefs by app.preferencesRepository.userPreferencesFlow.collectAsState(
                initial = UserPreferences()
            )

            MyApplicationTheme(
                useWallpaperDynamicColor = prefs.useWallpaperDynamicColor,
                themePreset = prefs.themePreset,
                fontOption = prefs.selectedFont
            ) {

                var hasCheckedPermissions by remember { mutableStateOf(false) }
                var showPermissionsOnboarding by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    val overlayGranted = Settings.canDrawOverlays(context)
                    val listenerGranted = checkNotificationListenerGranted(context)
                    showPermissionsOnboarding = !overlayGranted || !listenerGranted
                    hasCheckedPermissions = true
                }

                if (!hasCheckedPermissions) {
                    Box(modifier = Modifier.fillMaxSize())
                } else if (showPermissionsOnboarding) {
                    PermissionsScreen(
                        onContinue = {
                            showPermissionsOnboarding = false
                            if (prefs.overlayEnabled && Settings.canDrawOverlays(context)) {
                                OverlayService.start(context)
                            }
                        }
                    )
                } else {
                    MainAppScaffold(
                        playbackManager = playbackManager,
                        userPreferences = prefs
                    )
                }
            }
        }
    }
}

@Composable
fun MainAppScaffold(
    playbackManager: com.example.service.MediaPlaybackManager,
    userPreferences: UserPreferences
) {
    var currentTab by remember { mutableStateOf(AppTab.NOW_PLAYING) }

    // Intercept back button when on sub-tabs to return to Now Playing
    BackHandler(enabled = currentTab != AppTab.NOW_PLAYING) {
        currentTab = AppTab.NOW_PLAYING
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("main_navigation_bar")
            ) {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            AnimatedContent(targetState = currentTab, label = "tab_animation") { target ->
                when (target) {
                    AppTab.NOW_PLAYING -> NowPlayingScreen(
                        playbackManager = playbackManager,
                        userPreferences = userPreferences,
                        onNavigateToLyrics = { currentTab = AppTab.LYRICS }
                    )
                    AppTab.LYRICS -> LyricsScreen(
                        playbackManager = playbackManager,
                        userPreferences = userPreferences
                    )
                    AppTab.SETTINGS -> SettingsScreen(
                        playbackManager = playbackManager,
                        userPreferences = userPreferences
                    )
                    AppTab.HISTORY -> HistoryScreen()
                }
            }
        }
    }
}
