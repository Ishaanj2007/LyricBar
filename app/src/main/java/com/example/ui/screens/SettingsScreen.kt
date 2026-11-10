package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import com.example.data.preferences.LyricsProviderPreference
import com.example.data.preferences.OverlayAnimationStyle
import com.example.data.preferences.ThemeMode
import com.example.data.preferences.ThemePreset
import com.example.data.preferences.UserPreferences
import com.example.data.repository.CompositeLyricsRepository
import com.example.service.MediaPlaybackManager
import com.example.service.OverlayService
import com.example.ui.components.CalmThemedBackground
import com.example.ui.components.FrostedCard
import com.example.ui.components.LocalHazeState
import com.example.ui.theme.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    playbackManager: MediaPlaybackManager,
    userPreferences: UserPreferences,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val livePreferences by playbackManager.preferencesRepository.userPreferencesFlow
        .collectAsStateWithLifecycle(initialValue = userPreferences)
    val selectedProvider = livePreferences.lyricsProvider
    var showCacheClearedToast by remember { mutableStateOf(false) }
    var isFontListExpanded by rememberSaveable { mutableStateOf(false) }
    val updateManager = remember { com.example.data.updater.UpdateManager.getInstance(context) }
    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }

    val allSectionKeys = remember {
        listOf("overlay", "animation", "size_align", "position", "theme", "typography", "lyrics_provider", "data", "about")
    }
    var expandedSections by rememberSaveable {
        mutableStateOf(setOf("overlay", "theme", "lyrics_provider"))
    }
    val toggleSection: (String) -> Unit = { key ->
        expandedSections = if (expandedSections.contains(key)) {
            expandedSections - key
        } else {
            expandedSections + key
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .padding(bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
            ) {
                // Header with title, subtitle, and Expand/Collapse All
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Settings",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Preferences, appearance & overlays",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    TextButton(
                        onClick = {
                            expandedSections = if (expandedSections.size == allSectionKeys.size) {
                                emptySet()
                            } else {
                                allSectionKeys.toSet()
                            }
                        }
                    ) {
                        Text(
                            text = if (expandedSections.size == allSectionKeys.size) "Collapse All" else "Expand All",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                val configuration = LocalConfiguration.current
                val screenWidthDp = configuration.screenWidthDp
                val screenHeightDp = configuration.screenHeightDp
                val density = LocalDensity.current.density

                // SECTION 1: FLOATING OVERLAY & TIMELINE
                SettingsAccordionCard(
                    title = "Floating Overlay & Timeline",
                    subtitle = if (userPreferences.overlayEnabled) "Active • Pure white lyrics ON" else "Off • Floating overlay disabled",
                    icon = Icons.Default.Layers,
                    isExpanded = "overlay" in expandedSections,
                    onToggle = { toggleSection("overlay") }
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                // Toggle: Overlay Enabled
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Show Floating Overlay",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Draws pure white floating lyrics over other apps",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                        modifier = Modifier.testTag("settings_overlay_switch")
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Toggle: 2D Drag & Reposition Mode
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "2D Drag Reposition Mode",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (userPreferences.isDragRepositionMode) "Touch enabled: drag & resize overlay anywhere on screen" else "Tap-through: touches pass directly to underlying apps",
                            fontSize = 12.sp,
                            color = if (userPreferences.isDragRepositionMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = userPreferences.isDragRepositionMode,
                        onCheckedChange = { isDrag ->
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setDragRepositionMode(isDrag)
                            }
                        },
                        modifier = Modifier.testTag("settings_drag_mode_switch")
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Toggle: Contrast Text Shadow
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Text Contrast Shadow",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Subtle glyph shadow for readability over light backgrounds",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = userPreferences.textShadowEnabled,
                        onCheckedChange = { shadow ->
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setTextShadowEnabled(shadow)
                            }
                        },
                        modifier = Modifier.testTag("settings_shadow_switch")
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Toggle: Single-Line Lyrics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Single-Line Lyrics",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Keep each lyric on one line and scroll horizontally when needed.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = userPreferences.singleLineLyrics,
                        onCheckedChange = { enabled ->
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setSingleLineLyrics(enabled)
                            }
                        },
                        modifier = Modifier.testTag("settings_single_line_lyrics_switch")
                    )
                }

                if (userPreferences.singleLineLyrics) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateContentSize()
                            .padding(start = 12.dp, top = 8.dp, bottom = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Adaptive Lyric Scrolling",
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Scroll overflowing lyrics based on playback interval to next lyric",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = userPreferences.adaptiveLyricScrolling,
                                onCheckedChange = { enabled ->
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setAdaptiveLyricScrolling(enabled)
                                    }
                                },
                                modifier = Modifier.testTag("settings_adaptive_scrolling_switch")
                            )
                        }

                        if (userPreferences.adaptiveLyricScrolling) {
                            Spacer(modifier = Modifier.height(10.dp))
                            val speedMultiplier = userPreferences.lyricScrollSpeedMultiplier
                            val speedLabel = when {
                                speedMultiplier >= 1.4f -> "Fast"
                                speedMultiplier >= 1.15f -> "Brisk"
                                else -> "Standard"
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Scroll Speed Multiplier",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "${String.format(java.util.Locale.US, "%.1f×", speedMultiplier)} ($speedLabel)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            var localScrollSpeed by remember(userPreferences.lyricScrollSpeedMultiplier) {
                                mutableFloatStateOf(userPreferences.lyricScrollSpeedMultiplier)
                            }
                            Slider(
                                value = localScrollSpeed,
                                onValueChange = { localScrollSpeed = it },
                                onValueChangeFinished = {
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setLyricScrollSpeedMultiplier(localScrollSpeed)
                                    }
                                },
                                valueRange = 1.0f..1.5f,
                                steps = 4,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_scroll_speed_slider")
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Dedicated Resync Timeline Row & Button
                var resyncMessage by remember { mutableStateOf<String?>(null) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Lyric Timeline Synchronization",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = resyncMessage ?: "Re-anchor timeline to active playback and clear timing drift",
                            fontSize = 12.sp,
                            color = if (resyncMessage != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            playbackManager.resyncLyrics(forceRefresh = true)
                            resyncMessage = "Timeline calibrated & resynced!"
                            coroutineScope.launch {
                                kotlinx.coroutines.delay(2500)
                                resyncMessage = null
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier.testTag("settings_resync_button")
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Resync Lyric Timeline",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Resync", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 2: LYRICS ANIMATION STYLE
        SettingsAccordionCard(
            title = "Lyrics Animation Style",
            subtitle = "${userPreferences.animationStyle.replace('_', ' ')} • Transition engine",
            icon = Icons.Default.Animation,
            isExpanded = "animation" in expandedSections,
            onToggle = { toggleSection("animation") }
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Floating Overlay Animation",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Select your preferred lyric transition and word-highlighting engine",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                OverlayAnimationStyle.entries.forEach { style ->
                    val isSelected = userPreferences.animationStyle == style.name
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                coroutineScope.launch {
                                    playbackManager.preferencesRepository.setAnimationStyle(style.name)
                                }
                            },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setAnimationStyle(style.name)
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = style.title,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 13.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = style.description,
                                    fontSize = 11.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Apple-style Karaoke Glow Sweep Speed (Appears ONLY when Apple Karaoke style is selected)
                if (userPreferences.animationStyle == "KARAOKE") {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Glow Sweep Speed",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                val speedLabel = when {
                                    userPreferences.karaokeGlowSpeed <= 0.6f -> "0.5× — Slow"
                                    userPreferences.karaokeGlowSpeed <= 0.85f -> "0.7× — Relaxed"
                                    userPreferences.karaokeGlowSpeed in 0.86f..1.25f -> "1.0× — Normal"
                                    else -> "1.5× — Fast"
                                }
                                Text(
                                    text = speedLabel,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Adjust the speed of the white glowing highlight sweep across lyric text",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            var localKaraokeSpeed by remember(userPreferences.karaokeGlowSpeed) {
                                mutableFloatStateOf(userPreferences.karaokeGlowSpeed)
                            }

                            Slider(
                                value = localKaraokeSpeed,
                                onValueChange = { localKaraokeSpeed = it },
                                onValueChangeFinished = {
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setKaraokeGlowSpeed(localKaraokeSpeed)
                                    }
                                },
                                valueRange = 0.5f..1.5f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("karaoke_glow_speed_slider")
                                    .testTag("karaoke_speed_slider")
                            )

                            // Quick speed preset buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "0.5× — Slow" to 0.5f,
                                    "0.7× — Relaxed" to 0.7f,
                                    "1.0× — Normal" to 1.0f,
                                    "1.5× — Fast" to 1.5f
                                ).forEach { (label, speedVal) ->
                                    val isSelected = abs(userPreferences.karaokeGlowSpeed - speedVal) < 0.08f
                                    val tagKey = when (speedVal) {
                                        0.5f -> "0_5"
                                        0.7f -> "0_7"
                                        1.0f -> "1_0"
                                        else -> "1_5"
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                playbackManager.preferencesRepository.setKaraokeGlowSpeed(speedVal)
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("karaoke_glow_speed_preset_$tagKey")
                                            .testTag("karaoke_speed_preset_$tagKey"),
                                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Typewriter Reveal Speed (Appears ONLY when Typewriter style is selected)
                if (userPreferences.animationStyle == "TYPEWRITER") {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Typewriter Reveal Speed",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                val speedLabel = when {
                                    userPreferences.typewriterSpeed <= 0.55f -> "Slow (${String.format(java.util.Locale.US, "%.1fx", userPreferences.typewriterSpeed)})"
                                    userPreferences.typewriterSpeed <= 0.85f -> "Relaxed (${String.format(java.util.Locale.US, "%.1fx", userPreferences.typewriterSpeed)})"
                                    userPreferences.typewriterSpeed in 0.86f..1.15f -> "Normal (1.0x)"
                                    else -> "Fast (${String.format(java.util.Locale.US, "%.1fx", userPreferences.typewriterSpeed)})"
                                }
                                Text(
                                    text = speedLabel,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Slow down character typing reveal for a more dramatic pacing",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            var localSpeed by remember(userPreferences.typewriterSpeed) {
                                mutableFloatStateOf(userPreferences.typewriterSpeed)
                            }

                            Slider(
                                value = localSpeed,
                                onValueChange = { localSpeed = it },
                                onValueChangeFinished = {
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setTypewriterSpeed(localSpeed)
                                    }
                                },
                                valueRange = 0.4f..2.0f,
                                steps = 7,
                                modifier = Modifier.fillMaxWidth().testTag("typewriter_speed_slider")
                            )

                            // Quick speed preset buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "0.5x Slow" to 0.5f,
                                    "0.7x Relaxed" to 0.7f,
                                    "1.0x Normal" to 1.0f,
                                    "1.5x Fast" to 1.5f
                                ).forEach { (label, speedVal) ->
                                    val isSelected = abs(userPreferences.typewriterSpeed - speedVal) < 0.1f
                                    FilledTonalButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                playbackManager.preferencesRepository.setTypewriterSpeed(speedVal)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(text = label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // Smooth Line Transition Speed (Appears ONLY when Smooth Line Transition style is selected)
                if (userPreferences.animationStyle == "SMOOTH_SLIDE") {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Transition Speed",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                val speedLabel = when {
                                    userPreferences.smoothTransitionSpeed <= 0.6f -> "0.5× — Slow"
                                    userPreferences.smoothTransitionSpeed <= 0.85f -> "0.7× — Relaxed"
                                    userPreferences.smoothTransitionSpeed in 0.86f..1.25f -> "1.0× — Normal"
                                    else -> "1.5× — Fast"
                                }
                                Text(
                                    text = speedLabel,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Adjust how gently lyrics fade and slide during line transitions",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            var localSpeed by remember(userPreferences.smoothTransitionSpeed) {
                                mutableFloatStateOf(userPreferences.smoothTransitionSpeed)
                            }

                            Slider(
                                value = localSpeed,
                                onValueChange = { localSpeed = it },
                                onValueChangeFinished = {
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setSmoothTransitionSpeed(localSpeed)
                                    }
                                },
                                valueRange = 0.5f..1.5f,
                                modifier = Modifier.fillMaxWidth().testTag("transition_speed_slider")
                            )

                            // Quick speed preset buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "0.5× — Slow" to 0.5f,
                                    "0.7× — Relaxed" to 0.7f,
                                    "1.0× — Normal" to 1.0f,
                                    "1.5× — Fast" to 1.5f
                                ).forEach { (label, speedVal) ->
                                    val isSelected = abs(userPreferences.smoothTransitionSpeed - speedVal) < 0.08f
                                    val tagKey = when (speedVal) {
                                        0.5f -> "0_5"
                                        0.7f -> "0_7"
                                        1.0f -> "1_0"
                                        else -> "1_5"
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                playbackManager.preferencesRepository.setSmoothTransitionSpeed(speedVal)
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("transition_speed_preset_$tagKey"),
                                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Fade In / Fade Out Speed (Appears ONLY when Fade In / Fade Out style is selected)
                if (userPreferences.animationStyle == "FADE") {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Transition Speed",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                val speedLabel = when {
                                    userPreferences.fadeSpeed <= 0.6f -> "0.5× — Slow"
                                    userPreferences.fadeSpeed <= 0.85f -> "0.7× — Relaxed"
                                    userPreferences.fadeSpeed in 0.86f..1.25f -> "1.0× — Normal"
                                    else -> "1.5× — Fast"
                                }
                                Text(
                                    text = speedLabel,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Adjust how gently lyrics dissolve between previous and incoming lines",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            var localFadeSpeed by remember(userPreferences.fadeSpeed) {
                                mutableFloatStateOf(userPreferences.fadeSpeed)
                            }

                            Slider(
                                value = localFadeSpeed,
                                onValueChange = { localFadeSpeed = it },
                                onValueChangeFinished = {
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setFadeSpeed(localFadeSpeed)
                                    }
                                },
                                valueRange = 0.5f..1.5f,
                                modifier = Modifier.fillMaxWidth().testTag("fade_speed_slider")
                            )

                            // Quick speed preset buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "0.5× — Slow" to 0.5f,
                                    "0.7× — Relaxed" to 0.7f,
                                    "1.0× — Normal" to 1.0f,
                                    "1.5× — Fast" to 1.5f
                                ).forEach { (label, speedVal) ->
                                    val isSelected = abs(userPreferences.fadeSpeed - speedVal) < 0.08f
                                    val tagKey = when (speedVal) {
                                        0.5f -> "0_5"
                                        0.7f -> "0_7"
                                        1.0f -> "1_0"
                                        else -> "1_5"
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                playbackManager.preferencesRepository.setFadeSpeed(speedVal)
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("fade_speed_preset_$tagKey"),
                                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Blur Reveal Speed (Appears ONLY when Blur Reveal style is selected)
                if (userPreferences.animationStyle == "BLUR_REVEAL") {
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Transition Speed",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                val speedLabel = when {
                                    userPreferences.blurRevealSpeed <= 0.6f -> "0.5× — Slow"
                                    userPreferences.blurRevealSpeed <= 0.85f -> "0.7× — Relaxed"
                                    userPreferences.blurRevealSpeed in 0.86f..1.25f -> "1.0× — Normal"
                                    else -> "1.5× — Fast"
                                }
                                Text(
                                    text = speedLabel,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Adjust the duration of the gentle blur-to-sharp reveal motion",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            var localBlurSpeed by remember(userPreferences.blurRevealSpeed) {
                                mutableFloatStateOf(userPreferences.blurRevealSpeed)
                            }

                            Slider(
                                value = localBlurSpeed,
                                onValueChange = { localBlurSpeed = it },
                                onValueChangeFinished = {
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setBlurRevealSpeed(localBlurSpeed)
                                    }
                                },
                                valueRange = 0.5f..1.5f,
                                modifier = Modifier.fillMaxWidth().testTag("blur_speed_slider")
                            )

                            // Quick speed preset buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    "0.5× — Slow" to 0.5f,
                                    "0.7× — Relaxed" to 0.7f,
                                    "1.0× — Normal" to 1.0f,
                                    "1.5× — Fast" to 1.5f
                                ).forEach { (label, speedVal) ->
                                    val isSelected = abs(userPreferences.blurRevealSpeed - speedVal) < 0.08f
                                    val tagKey = when (speedVal) {
                                        0.5f -> "0_5"
                                        0.7f -> "0_7"
                                        1.0f -> "1_0"
                                        else -> "1_5"
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            coroutineScope.launch {
                                                playbackManager.preferencesRepository.setBlurRevealSpeed(speedVal)
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("blur_speed_preset_$tagKey"),
                                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Interactive Live Preview
                AnimationStylePreview(
                    styleName = userPreferences.animationStyle,
                    textOpacity = userPreferences.overlayTextOpacity,
                    typewriterSpeed = userPreferences.typewriterSpeed,
                    smoothTransitionSpeed = userPreferences.smoothTransitionSpeed,
                    fadeSpeed = userPreferences.fadeSpeed,
                    blurRevealSpeed = userPreferences.blurRevealSpeed,
                    karaokeSpeed = userPreferences.karaokeSpeed
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 3: OVERLAY SIZE & ALIGNMENT
        SettingsAccordionCard(
            title = "Overlay Size & Alignment",
            subtitle = "${(userPreferences.overlayWidthPx / density).roundToInt()} × ${(userPreferences.overlayHeightPx / density).roundToInt()} dp • ${userPreferences.overlayTextAlign.lowercase().replaceFirstChar { it.uppercase() }}",
            icon = Icons.Default.AspectRatio,
            isExpanded = "size_align" in expandedSections,
            onToggle = { toggleSection("size_align") }
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                val currentWidthDp = (userPreferences.overlayWidthPx / density).roundToInt()
                val currentHeightDp = (userPreferences.overlayHeightPx / density).roundToInt()

                var localWidthDp by remember(userPreferences.overlayWidthPx) {
                    mutableFloatStateOf(currentWidthDp.toFloat())
                }
                var localHeightDp by remember(userPreferences.overlayHeightPx) {
                    mutableFloatStateOf(currentHeightDp.toFloat())
                }

                // Width Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Container Width",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${localWidthDp.roundToInt()} dp (${(localWidthDp * density).roundToInt()} px)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Slider(
                    value = localWidthDp,
                    onValueChange = { localWidthDp = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            val newWPx = (localWidthDp * density).roundToInt()
                            val newHPx = (localHeightDp * density).roundToInt()
                            playbackManager.preferencesRepository.setOverlayDimensions(newWPx, newHPx)
                        }
                    },
                    valueRange = 180f..screenWidthDp.toFloat().coerceAtLeast(240f),
                    modifier = Modifier.fillMaxWidth().testTag("width_slider")
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Height Slider
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Container Height",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${localHeightDp.roundToInt()} dp (${(localHeightDp * density).roundToInt()} px)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Slider(
                    value = localHeightDp,
                    onValueChange = { localHeightDp = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            val newWPx = (localWidthDp * density).roundToInt()
                            val newHPx = (localHeightDp * density).roundToInt()
                            playbackManager.preferencesRepository.setOverlayDimensions(newWPx, newHPx)
                        }
                    },
                    valueRange = 40f..220f,
                    modifier = Modifier.fillMaxWidth().testTag("height_slider")
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Aspect Preview
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        val previewRatio = (localWidthDp / localHeightDp.coerceAtLeast(1f)).coerceIn(1.5f, 8f)
                        Box(
                            modifier = Modifier
                                .fillMaxHeight(0.65f)
                                .aspectRatio(previewRatio)
                                .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${localWidthDp.roundToInt()} × ${localHeightDp.roundToInt()} dp",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                // Lyrics Alignment inside the container
                Text(
                    text = "Lyrics Alignment",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Aligns text inside the invisible overlay container (independent of screen placement)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        "Left" to "LEFT",
                        "Centre" to "CENTER",
                        "Right" to "RIGHT"
                    ).forEach { (label, alignCode) ->
                        val isSelected = when (alignCode) {
                            "CENTER" -> userPreferences.overlayTextAlign.equals("CENTER", ignoreCase = true) ||
                                userPreferences.overlayTextAlign.equals("CENTRE", ignoreCase = true)
                            else -> userPreferences.overlayTextAlign.equals(alignCode, ignoreCase = true)
                        }
                        FilledTonalButton(
                            onClick = {
                                coroutineScope.launch {
                                    playbackManager.preferencesRepository.setOverlayTextAlign(alignCode)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Text(
                                text = label,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 4: OVERLAY POSITION (RESPONSIVE 2-ROW CONTROLLER WITH SLIDERS AND MICRO-NUDGES)
        SettingsAccordionCard(
            title = "Overlay Position",
            subtitle = "X: ${(userPreferences.overlayXPosition / density).roundToInt()} dp • Y: ${(userPreferences.overlayYPosition / density).roundToInt()} dp",
            icon = Icons.Default.OpenWith,
            isExpanded = "position" in expandedSections,
            onToggle = { toggleSection("position") }
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                val overlayWidthPx = userPreferences.overlayWidthPx
                val overlayHeightPx = userPreferences.overlayHeightPx

                val maxXPx = (screenWidthDp * density - overlayWidthPx).toInt().coerceAtLeast(0)
                val maxXDp = (maxXPx / density).roundToInt().coerceAtLeast(50)

                val maxYPx = (screenHeightDp * density - overlayHeightPx).toInt().coerceAtLeast(0)
                val maxYDp = (maxYPx / density).roundToInt().coerceAtLeast(100)

                val currentXDp = (userPreferences.overlayXPosition / density).roundToInt()
                val currentYDp = (userPreferences.overlayYPosition / density).roundToInt()

                var localXDp by remember(userPreferences.overlayXPosition) {
                    mutableFloatStateOf(currentXDp.toFloat().coerceIn(0f, maxXDp.toFloat()))
                }
                var localYDp by remember(userPreferences.overlayYPosition) {
                    mutableFloatStateOf(currentYDp.toFloat().coerceIn(0f, maxYDp.toFloat()))
                }

                // Row 1: Horizontal Position (X)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Horizontal Position (X)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${localXDp.roundToInt()} dp (${userPreferences.overlayXPosition} px)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Slider(
                    value = localXDp,
                    onValueChange = { localXDp = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            val newXPx = (localXDp * density).roundToInt()
                            playbackManager.preferencesRepository.setOverlayPosition(newXPx, userPreferences.overlayYPosition)
                        }
                    },
                    valueRange = 0f..maxXDp.toFloat(),
                    modifier = Modifier.fillMaxWidth().testTag("x_position_slider")
                )

                // Responsive Micro-Nudge Row for X
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(-20, -5, +5, +20).forEach { deltaDp ->
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    val deltaPx = (deltaDp * density).roundToInt()
                                    playbackManager.preferencesRepository.nudgeOverlayPosition(deltaPx, 0)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (deltaDp > 0) "+${deltaDp}dp" else "${deltaDp}dp",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(16.dp))

                // Row 2: Vertical Position (Y)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Vertical Position (Y)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${localYDp.roundToInt()} dp (${userPreferences.overlayYPosition} px)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Slider(
                    value = localYDp,
                    onValueChange = { localYDp = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            val newYPx = (localYDp * density).roundToInt()
                            playbackManager.preferencesRepository.setOverlayPosition(userPreferences.overlayXPosition, newYPx)
                        }
                    },
                    valueRange = 0f..maxYDp.toFloat(),
                    modifier = Modifier.fillMaxWidth().testTag("y_position_slider")
                )

                // Responsive Micro-Nudge Row for Y
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(-20, -5, +5, +20).forEach { deltaDp ->
                        OutlinedButton(
                            onClick = {
                                coroutineScope.launch {
                                    val deltaPx = (deltaDp * density).roundToInt()
                                    playbackManager.preferencesRepository.nudgeOverlayPosition(0, deltaPx)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp),
                            contentPadding = PaddingValues(0.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (deltaDp > 0) "+${deltaDp}dp" else "${deltaDp}dp",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Reset to Center
                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            val centerXPx = ((screenWidthDp * density - overlayWidthPx) / 2).toInt().coerceAtLeast(0)
                            val centerYPx = (screenHeightDp * density * 0.18f).toInt()
                            playbackManager.preferencesRepository.setOverlayPosition(centerXPx, centerYPx)
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reset to Center", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 5: THEME & MATERIAL STYLE
        SettingsAccordionCard(
            title = "Theme & Material Style",
            subtitle = if (userPreferences.useWallpaperDynamicColor) "Material You (Wallpaper)" else userPreferences.themePreset.title,
            icon = Icons.Default.Palette,
            isExpanded = "theme" in expandedSections,
            onToggle = { toggleSection("theme") }
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                // Appearance Mode: Dark-Only Experience
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("theme_mode_dark"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DarkMode,
                            contentDescription = "Dark Mode",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Appearance: Dark Experience",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "LyricBar is crafted for an immersive cinematic dark visual experience",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp))

                // Toggle: Wallpaper Dynamic Color (Material You)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Wallpaper Dynamic Colors (Material You)",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Derives theme colors from your device wallpaper" else "Requires Android 12+ (Falling back to curated palette)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = userPreferences.useWallpaperDynamicColor,
                        onCheckedChange = { dynamic ->
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setUseWallpaperDynamicColor(dynamic)
                            }
                        },
                        modifier = Modifier.testTag("wallpaper_dynamic_color_switch")
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Curated Pastel Palettes",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (userPreferences.useWallpaperDynamicColor) "Active when wallpaper dynamic color is turned off" else "Currently applied theme palette",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                val arePalettesEnabled = !userPreferences.useWallpaperDynamicColor

                // 2-Column Curated Themes Swatch Grid
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ThemePresetCard(
                        title = "Rose Quartz",
                        subtitle = "Signature Theme",
                        primaryColor = RoseQuartzPrimary,
                        secondaryColor = RoseQuartzSecondary,
                        surfaceColor = RoseQuartzDarkSurface,
                        isSelected = arePalettesEnabled && (userPreferences.themePreset == ThemePreset.ROSE_QUARTZ || userPreferences.themePreset == ThemePreset.OBSIDIAN_MINT),
                        isEnabled = arePalettesEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setThemePreset(ThemePreset.ROSE_QUARTZ)
                                playbackManager.preferencesRepository.setUseWallpaperDynamicColor(false)
                            }
                        }
                    )
                    ThemePresetCard(
                        title = "Lavender Mist",
                        subtitle = "Dreamy & Elegant",
                        primaryColor = LavenderMistPrimary,
                        secondaryColor = LavenderMistSecondary,
                        surfaceColor = LavenderMistDarkSurface,
                        isSelected = arePalettesEnabled && (userPreferences.themePreset == ThemePreset.LAVENDER_MIST || userPreferences.themePreset == ThemePreset.MIDNIGHT_AZURE),
                        isEnabled = arePalettesEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setThemePreset(ThemePreset.LAVENDER_MIST)
                                playbackManager.preferencesRepository.setUseWallpaperDynamicColor(false)
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ThemePresetCard(
                        title = "Peach Ember",
                        subtitle = "Warm Cinematic",
                        primaryColor = PeachEmberPrimary,
                        secondaryColor = PeachEmberSecondary,
                        surfaceColor = PeachEmberDarkSurface,
                        isSelected = arePalettesEnabled && (userPreferences.themePreset == ThemePreset.PEACH_EMBER || userPreferences.themePreset == ThemePreset.SUNSET_AMBER),
                        isEnabled = arePalettesEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setThemePreset(ThemePreset.PEACH_EMBER)
                                playbackManager.preferencesRepository.setUseWallpaperDynamicColor(false)
                            }
                        }
                    )
                    ThemePresetCard(
                        title = "Sage Serenity",
                        subtitle = "Calm Understated",
                        primaryColor = SageSerenityPrimary,
                        secondaryColor = SageSerenitySecondary,
                        surfaceColor = SageSerenityDarkSurface,
                        isSelected = arePalettesEnabled && (userPreferences.themePreset == ThemePreset.SAGE_SERENITY || userPreferences.themePreset == ThemePreset.CYBER_VIOLET),
                        isEnabled = arePalettesEnabled,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setThemePreset(ThemePreset.SAGE_SERENITY)
                                playbackManager.preferencesRepository.setUseWallpaperDynamicColor(false)
                            }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 6: TYPOGRAPHY
        SettingsAccordionCard(
            title = "Typography",
            subtitle = "${userPreferences.selectedFont.title} • ${userPreferences.overlayTextSizeSp.toInt()} sp",
            icon = Icons.Default.FontDownload,
            isExpanded = "typography" in expandedSections,
            onToggle = { toggleSection("typography") }
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Overlay Typography Scale (${userPreferences.overlayTextSizeSp.toInt()} sp)",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Granular small-unit scale (6sp to 24sp) for refined, subtle floating lyrics.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                var localFontSize by remember(userPreferences.overlayTextSizeSp) {
                    mutableFloatStateOf(userPreferences.overlayTextSizeSp)
                }

                Slider(
                    value = localFontSize,
                    onValueChange = { localFontSize = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            playbackManager.preferencesRepository.setOverlayTextSize(localFontSize)
                        }
                    },
                    valueRange = 6f..24f,
                    steps = 17,
                    modifier = Modifier.fillMaxWidth().testTag("font_size_slider")
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Granular Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(6, 8, 10, 12, 14, 18).forEach { spVal ->
                        val isSelected = userPreferences.overlayTextSizeSp.toInt() == spVal
                        FilledTonalButton(
                            onClick = {
                                coroutineScope.launch {
                                    playbackManager.preferencesRepository.setOverlayTextSize(spVal.toFloat())
                                }
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(text = "${spVal}sp", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(14.dp))

                // Text Opacity / Transparency
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Text Transparency & Opacity",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val opacityPct = (userPreferences.overlayTextOpacity * 100).roundToInt()
                    Text(
                        text = "$opacityPct% Opacity",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "Adjust text opacity for sheer, see-through elegance or vivid contrast",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                var localOpacity by remember(userPreferences.overlayTextOpacity) {
                    mutableFloatStateOf(userPreferences.overlayTextOpacity)
                }

                Slider(
                    value = localOpacity,
                    onValueChange = { localOpacity = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            playbackManager.preferencesRepository.setOverlayTextOpacity(localOpacity)
                        }
                    },
                    valueRange = 0.2f..1.0f,
                    steps = 15,
                    modifier = Modifier.fillMaxWidth().testTag("text_opacity_slider")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "30% Sheer" to 0.3f,
                        "50% Subtle" to 0.5f,
                        "75% Balanced" to 0.75f,
                        "100% Solid" to 1.0f
                    ).forEach { (label, opacityVal) ->
                        val isSelected = abs(userPreferences.overlayTextOpacity - opacityVal) < 0.08f
                        FilledTonalButton(
                            onClick = {
                                coroutineScope.launch {
                                    playbackManager.preferencesRepository.setOverlayTextOpacity(opacityVal)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(text = label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Font Family Selector
                Text(
                    text = "Font Family Style",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Choose from custom Google Fonts or System Default",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                val allFonts = com.example.data.preferences.FontOption.entries
                val visibleFonts = if (isFontListExpanded) allFonts else allFonts.take(4)
                val selectedFont = userPreferences.selectedFont
                val isSelectedFontHidden = !isFontListExpanded && selectedFont !in visibleFonts

                if (isSelectedFontHidden) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { isFontListExpanded = true },
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Selected: ${selectedFont.title}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Tap to reveal in list",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    visibleFonts.forEach { fontOption ->
                        val isSelected = selectedFont == fontOption
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    coroutineScope.launch {
                                        playbackManager.preferencesRepository.setSelectedFont(fontOption)
                                    }
                                }
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(10.dp)
                                ),
                            color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = fontOption.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                                        fontFamily = com.example.ui.theme.getFontFamily(fontOption)
                                    )
                                    Text(
                                        text = "The quick brown fox jumps over the lazy dog",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = com.example.ui.theme.getFontFamily(fontOption)
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { isFontListExpanded = !isFontListExpanded },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = if (isFontListExpanded) "Show less" else "Show more fonts",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = if (isFontListExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isFontListExpanded) "Show less fonts" else "Show more fonts",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 7: LYRICS PROVIDER
        SettingsAccordionCard(
            title = "Lyrics Provider",
            subtitle = "${selectedProvider.displayName} • Default lyrics source",
            icon = Icons.Default.LibraryMusic,
            isExpanded = "lyrics_provider" in expandedSections,
            onToggle = { toggleSection("lyrics_provider") },
            modifier = Modifier.testTag("settings_lyrics_provider_section")
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Default Lyrics Provider",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Choose how LyricBar searches for synchronized lyrics across online catalogs",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))

                LyricsProviderPreference.entries.forEach { option ->
                    val isSelected = selectedProvider == option
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                playbackManager.setLyricsProvider(option)
                            }
                            .testTag("settings_provider_option_${option.name.lowercase()}"),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    playbackManager.setLyricsProvider(option)
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = option.displayName,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 13.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (option == LyricsProviderPreference.AUTO) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                        ) {
                                            Text(
                                                text = "Default",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = option.description,
                                    fontSize = 11.sp,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 8: PLAYBACK & DATA MANAGEMENT
        SettingsAccordionCard(
            title = "Automation & Cache",
            subtitle = "Auto-sync, cache & storage",
            icon = Icons.Default.Storage,
            isExpanded = "data" in expandedSections,
            onToggle = { toggleSection("data") }
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Show On Playback",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Automatically opens overlay when music starts",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = userPreferences.autoShowOnPlayback,
                        onCheckedChange = { autoShow ->
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setAutoShowOnPlayback(autoShow)
                            }
                        },
                        modifier = Modifier.testTag("settings_auto_show_switch")
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Toggle: Auto-Fetch Lyrics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Fetch Lyrics",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Automatically search online lyrics when track changes",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = userPreferences.autoFetchLyrics,
                        onCheckedChange = { autoFetch ->
                            coroutineScope.launch {
                                playbackManager.preferencesRepository.setAutoFetchLyrics(autoFetch)
                            }
                        },
                        modifier = Modifier.testTag("settings_auto_fetch_switch")
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                CompositeLyricsRepository.getInstance(context).clearCache()
                                showCacheClearedToast = true
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("clear_cache_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear Cache", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                CompositeLyricsRepository.getInstance(context).clearHistory()
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("clear_history_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear History", color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                    }
                }

                if (showCacheClearedToast) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Local lyrics cache cleared successfully.",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 8: ABOUT & DEVELOPER
        SettingsAccordionCard(
            title = "About & Developer",
            subtitle = "Version ${com.example.BuildConfig.VERSION_NAME} • By ${DeveloperConfig.DEVELOPER_NAME}",
            icon = Icons.Default.Info,
            isExpanded = "about" in expandedSections,
            onToggle = { toggleSection("about") }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.drawable.lyricbarlogo),
                    contentDescription = "LyricBar Logo",
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "LyricBar",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Version ${com.example.BuildConfig.VERSION_NAME}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Made with ❤️ by ${DeveloperConfig.DEVELOPER_NAME}",
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Visit Github For More Projects",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DeveloperConfig.GITHUB_PROFILE_URL))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Developer", fontSize = 12.sp, maxLines = 1)
                    }

                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DeveloperConfig.SOURCE_CODE_URL))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Source Code", fontSize = 12.sp, maxLines = 1)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        showUpdateDialog = true
                        updateManager.checkForUpdates(isUserInitiated = true)
                    },
                    modifier = Modifier.fillMaxWidth().testTag("check_for_updates_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Check for Updates", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }

        if (showUpdateDialog) {
            UpdateDialog(
                updateManager = updateManager,
                onDismiss = { showUpdateDialog = false }
            )
        }
    }
}

@Composable
private fun AnimationStylePreview(
    styleName: String,
    textOpacity: Float = 1.0f,
    typewriterSpeed: Float = 1.0f,
    smoothTransitionSpeed: Float = 1.0f,
    fadeSpeed: Float = 1.0f,
    blurRevealSpeed: Float = 1.0f,
    karaokeSpeed: Float = 1.0f
) {
    var previewTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(styleName, typewriterSpeed, karaokeSpeed) {
        val baseDelay = when (styleName) {
            "TYPEWRITER" -> (120 / typewriterSpeed.coerceIn(0.3f, 3.0f)).toLong()
            "KARAOKE" -> (120 / karaokeSpeed.coerceIn(0.3f, 3.0f)).toLong()
            else -> 120L
        }
        while (true) {
            kotlinx.coroutines.delay(baseDelay)
            previewTick++
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "LIVE ANIMATION PREVIEW",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            val sampleWords = listOf("Caught", "in", "a", "landslide,", "no", "escape", "from", "reality")
            when (styleName) {
                "KARAOKE" -> {
                    val activeWordIndex = (previewTick / 5) % (sampleWords.size + 4)
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        sampleWords.forEachIndexed { idx, w ->
                            val isSung = idx < activeWordIndex
                            val isCurrent = idx == activeWordIndex
                            val wordColor = when {
                                isCurrent -> MaterialTheme.colorScheme.primary
                                isSung -> Color.White.copy(alpha = textOpacity)
                                else -> Color.White.copy(alpha = 0.35f * textOpacity)
                            }
                            Text(
                                text = "$w ",
                                color = wordColor,
                                fontWeight = if (isSung || isCurrent) FontWeight.ExtraBold else FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                "SMOOTH_SLIDE" -> {
                    val sampleLines = remember {
                        listOf(
                            "Caught in a landslide, no escape from reality",
                            "Open your eyes, look up to the skies and see",
                            "I'm just a poor boy, I need no sympathy",
                            "Because I'm easy come, easy go, little high, little low"
                        )
                    }
                    var currentLineIdx by remember { mutableIntStateOf(0) }
                    var prevLineIdx by remember { mutableIntStateOf(-1) }
                    val animProgress = remember { androidx.compose.animation.core.Animatable(1f) }

                    LaunchedEffect(styleName, smoothTransitionSpeed) {
                        while (true) {
                            kotlinx.coroutines.delay(2000L)
                            prevLineIdx = currentLineIdx
                            currentLineIdx = (currentLineIdx + 1) % sampleLines.size
                            val baseDuration = 300L
                            val speed = smoothTransitionSpeed.coerceIn(0.3f, 3.0f)
                            val transDuration = (baseDuration / speed).toInt().coerceIn(100, 2000)
                            animProgress.snapTo(0f)
                            animProgress.animateTo(
                                targetValue = 1f,
                                animationSpec = androidx.compose.animation.core.tween(
                                    durationMillis = transDuration,
                                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                                )
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (animProgress.value < 1f && prevLineIdx in sampleLines.indices) {
                            val outgoingOffsetY = (-animProgress.value * 20).dp
                            val outgoingAlpha = ((1f - animProgress.value) * (textOpacity * 0.35f)).coerceIn(0f, 1f)
                            Text(
                                text = sampleLines[prevLineIdx],
                                color = Color.White.copy(alpha = outgoingAlpha),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.offset(y = outgoingOffsetY)
                            )
                        }

                        val incomingOffsetY = ((1f - animProgress.value) * 16).dp
                        val incomingAlpha = (animProgress.value * textOpacity).coerceIn(0f, 1f)
                        Text(
                            text = sampleLines[currentLineIdx],
                            color = Color.White.copy(alpha = incomingAlpha),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.offset(y = incomingOffsetY)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    val durationMs = (300L / smoothTransitionSpeed.coerceIn(0.3f, 3.0f)).roundToInt()
                    Text(
                        text = "Smooth transition • ${String.format(java.util.Locale.US, "%.1f×", smoothTransitionSpeed)} speed (${durationMs}ms duration)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
                "FADE" -> {
                    val sampleLines = remember {
                        listOf(
                            "Caught in a landslide, no escape from reality",
                            "Open your eyes, look up to the skies and see",
                            "I'm just a poor boy, I need no sympathy",
                            "Because I'm easy come, easy go, little high, little low"
                        )
                    }
                    var currentLineIdx by remember { mutableIntStateOf(0) }
                    var prevLineIdx by remember { mutableIntStateOf(-1) }
                    val animProgress = remember { androidx.compose.animation.core.Animatable(1f) }

                    LaunchedEffect(styleName, fadeSpeed) {
                        while (true) {
                            kotlinx.coroutines.delay(2200L)
                            prevLineIdx = currentLineIdx
                            currentLineIdx = (currentLineIdx + 1) % sampleLines.size
                            val baseDuration = 360L
                            val speed = fadeSpeed.coerceIn(0.3f, 3.0f)
                            val transDuration = (baseDuration / speed).toInt().coerceIn(100, 2000)
                            animProgress.snapTo(0f)
                            animProgress.animateTo(
                                targetValue = 1f,
                                animationSpec = androidx.compose.animation.core.tween(
                                    durationMillis = transDuration,
                                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                                )
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (animProgress.value < 1f && prevLineIdx in sampleLines.indices) {
                            val outgoingAlpha = ((1f - animProgress.value) * textOpacity).coerceIn(0f, 1f)
                            Text(
                                text = sampleLines[prevLineIdx],
                                color = Color.White.copy(alpha = outgoingAlpha),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }

                        val incomingAlpha = (animProgress.value * textOpacity).coerceIn(0f, 1f)
                        Text(
                            text = sampleLines[currentLineIdx],
                            color = Color.White.copy(alpha = incomingAlpha),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    val durationMs = (360L / fadeSpeed.coerceIn(0.3f, 3.0f)).roundToInt()
                    Text(
                        text = "Pure fade transition • ${String.format(java.util.Locale.US, "%.1f×", fadeSpeed)} speed (${durationMs}ms duration)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
                "BLUR_REVEAL" -> {
                    val sampleLines = remember {
                        listOf(
                            "Caught in a landslide, no escape from reality",
                            "Open your eyes, look up to the skies and see",
                            "I'm just a poor boy, I need no sympathy",
                            "Because I'm easy come, easy go, little high, little low"
                        )
                    }
                    var currentLineIdx by remember { mutableIntStateOf(0) }
                    var prevLineIdx by remember { mutableIntStateOf(-1) }
                    val animProgress = remember { androidx.compose.animation.core.Animatable(1f) }

                    LaunchedEffect(styleName, blurRevealSpeed) {
                        while (true) {
                            kotlinx.coroutines.delay(2200L)
                            prevLineIdx = currentLineIdx
                            currentLineIdx = (currentLineIdx + 1) % sampleLines.size
                            val baseDuration = 400L
                            val speed = blurRevealSpeed.coerceIn(0.3f, 3.0f)
                            val transDuration = (baseDuration / speed).toInt().coerceIn(100, 2000)
                            animProgress.snapTo(0f)
                            animProgress.animateTo(
                                targetValue = 1f,
                                animationSpec = androidx.compose.animation.core.tween(
                                    durationMillis = transDuration,
                                    easing = androidx.compose.animation.core.FastOutSlowInEasing
                                )
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (animProgress.value < 1f && prevLineIdx in sampleLines.indices) {
                            val outgoingAlpha = ((1f - animProgress.value) * textOpacity).coerceIn(0f, 1f)
                            Text(
                                text = sampleLines[prevLineIdx],
                                color = Color.White.copy(alpha = outgoingAlpha),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }

                        val blurDp = ((1f - animProgress.value) * 6f).dp
                        val incomingAlpha = (animProgress.value * textOpacity).coerceIn(0f, 1f)
                        val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurDp > 0.5.dp) {
                            Modifier.blur(blurDp)
                        } else {
                            Modifier
                        }

                        Text(
                            text = sampleLines[currentLineIdx],
                            color = Color.White.copy(alpha = incomingAlpha),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = blurModifier
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    val durationMs = (400L / blurRevealSpeed.coerceIn(0.3f, 3.0f)).roundToInt()
                    Text(
                        text = "Blur to sharp reveal • ${String.format(java.util.Locale.US, "%.1f×", blurRevealSpeed)} speed (${durationMs}ms duration)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
                "TYPEWRITER" -> {
                    val fullText = "Caught in a landslide, no escape from reality"
                    val charIdx = ((previewTick * 2) % (fullText.length + 15)).coerceAtMost(fullText.length)
                    val typed = fullText.substring(0, charIdx)
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = typed,
                            color = Color.White.copy(alpha = textOpacity),
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        if ((previewTick / 3) % 2 == 0) {
                            Text(
                                text = "|",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                "INSTANT" -> {
                    Text(
                        text = "Caught in a landslide, no escape from reality",
                        color = Color.White.copy(alpha = textOpacity),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Immediate line replacement • Zero transition motion",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
    )
}

@Composable
private fun ThemePresetCard(
    title: String,
    subtitle: String,
    primaryColor: Color,
    secondaryColor: Color = primaryColor,
    surfaceColor: Color,
    isSelected: Boolean,
    isEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val cardAlpha = if (isEnabled) 1.0f else 0.45f
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = isEnabled, onClick = onClick)
            .border(
                width = if (isSelected && isEnabled) 2.dp else 1.dp,
                color = if (isSelected && isEnabled) primaryColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(14.dp)
            ),
        color = surfaceColor.copy(alpha = 0.88f),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(primaryColor.copy(alpha = cardAlpha), CircleShape)
                    )
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(secondaryColor.copy(alpha = cardAlpha), CircleShape)
                    )
                }

                if (isSelected && isEnabled) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(primaryColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Selected",
                            tint = Color.Black,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = cardAlpha)
            )
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = cardAlpha)
            )
        }
    }
}

@Composable
private fun SettingsAccordionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    FrostedCard(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = androidx.compose.animation.core.tween(220)),
        shape = RoundedCornerShape(18.dp),
        backgroundAlpha = 0.28f,
        shadowElevation = 3.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }

                val rotation by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isExpanded) 180f else 0f,
                    animationSpec = androidx.compose.animation.core.tween(220),
                    label = "accordion_chevron"
                )
                IconButton(
                    onClick = onToggle,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        modifier = Modifier.rotate(rotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isExpanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                )
                content()
            }
        }
    }
}
