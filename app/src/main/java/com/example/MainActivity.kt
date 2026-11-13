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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.preferences.UserPreferences
import com.example.service.OverlayService
import com.example.ui.components.AdaptiveNavRail
import com.example.ui.components.FloatingBottomNavPill
import com.example.ui.components.LocalHazeState
import com.example.ui.screens.*
import com.example.ui.theme.MyApplicationTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

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
            val prefs by app.preferencesRepository.userPreferencesFlow.collectAsStateWithLifecycle(
                initialValue = UserPreferences()
            )

            MyApplicationTheme(
                useWallpaperDynamicColor = prefs.useWallpaperDynamicColor,
                themePreset = prefs.themePreset,
                themeMode = prefs.themeMode,
                fontOption = prefs.selectedFont
            ) {

                var hasCheckedPermissions by rememberSaveable { mutableStateOf(false) }
                var showPermissionsOnboarding by rememberSaveable { mutableStateOf(false) }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            val overlayGranted = Settings.canDrawOverlays(context)
                            val listenerGranted = checkNotificationListenerGranted(context)
                            showPermissionsOnboarding = !overlayGranted || !listenerGranted
                            hasCheckedPermissions = true
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
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
    var currentTab by rememberSaveable { mutableStateOf(AppTab.NOW_PLAYING) }
    val navHazeState = remember { HazeState() }
    val playbackState by playbackManager.playbackState.collectAsStateWithLifecycle()
    val artworkBitmap = com.example.ui.components.rememberArtworkBitmap(playbackState.track)

    // Intercept back button when on sub-tabs to return to Now Playing
    BackHandler(enabled = currentTab != AppTab.NOW_PLAYING) {
        currentTab = AppTab.NOW_PLAYING
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= 600.dp

        // Root Edge-to-Edge Ambient Background (covers entire window: status bar to gesture nav)
        if (currentTab == AppTab.NOW_PLAYING || currentTab == AppTab.LYRICS || currentTab == AppTab.SETTINGS || currentTab == AppTab.HISTORY) {
            com.example.ui.components.CinematicArtworkBackground(
                artworkBitmap = artworkBitmap,
                trackKey = playbackState.track?.key ?: "",
                hazeState = navHazeState,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            com.example.ui.components.CalmThemedBackground(
                hazeState = navHazeState,
                modifier = Modifier.fillMaxSize()
            )
        }

        CompositionLocalProvider(LocalHazeState provides navHazeState) {
            if (isExpanded) {
                // Adaptive Navigation Rail for Expanded / Tablet / Landscape mode
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .windowInsetsPadding(WindowInsets.displayCutout)
                ) {
                    AdaptiveNavRail(
                        currentTab = currentTab,
                        onTabSelected = { currentTab = it },
                        hazeState = navHazeState
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        TabContent(
                            currentTab = currentTab,
                            playbackManager = playbackManager,
                            userPreferences = userPreferences,
                            hasBottomNavPill = false,
                            onNavigateToLyrics = { currentTab = AppTab.LYRICS }
                        )
                    }
                }
            } else {
                // Compact / Phone layout: true edge-to-edge ambient container
                Box(modifier = Modifier.fillMaxSize()) {
                    // Content area: NowPlaying, Lyrics, Settings, and History handle status bars internally for seamless background
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (currentTab == AppTab.NOW_PLAYING || currentTab == AppTab.LYRICS || currentTab == AppTab.SETTINGS || currentTab == AppTab.HISTORY) {
                                    Modifier
                                } else {
                                    Modifier
                                        .windowInsetsPadding(WindowInsets.statusBars)
                                        .windowInsetsPadding(WindowInsets.navigationBars)
                                        .padding(bottom = 76.dp)
                                }
                            )
                    ) {
                        TabContent(
                            currentTab = currentTab,
                            playbackManager = playbackManager,
                            userPreferences = userPreferences,
                            hasBottomNavPill = true,
                            onNavigateToLyrics = { currentTab = AppTab.LYRICS }
                        )
                    }

                    // Floating Frosted Navigation Pill anchored above gesture bar over continuous ambient artwork
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        FloatingBottomNavPill(
                            currentTab = currentTab,
                            onTabSelected = { currentTab = it },
                            hazeState = navHazeState
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabContent(
    currentTab: AppTab,
    playbackManager: com.example.service.MediaPlaybackManager,
    userPreferences: UserPreferences,
    hasBottomNavPill: Boolean,
    onNavigateToLyrics: () -> Unit
) {
    AnimatedContent(targetState = currentTab, label = "tab_animation") { target ->
        when (target) {
            AppTab.NOW_PLAYING -> NowPlayingScreen(
                playbackManager = playbackManager,
                userPreferences = userPreferences,
                onNavigateToLyrics = onNavigateToLyrics,
                hasBottomNavPill = hasBottomNavPill
            )
            AppTab.LYRICS -> LyricsScreen(
                playbackManager = playbackManager,
                userPreferences = userPreferences,
                hasBottomNavPill = hasBottomNavPill
            )
            AppTab.SETTINGS -> SettingsScreen(
                playbackManager = playbackManager,
                userPreferences = userPreferences,
                hasBottomNavPill = hasBottomNavPill
            )
            AppTab.HISTORY -> HistoryScreen(
                hasBottomNavPill = hasBottomNavPill
            )
        }
    }
}
