package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.preferences.UserPreferences
import com.example.service.MediaPlaybackManager
import com.example.service.OverlayService
import com.example.ui.components.ArtworkGlowPalette
import com.example.ui.components.CinematicArtworkBackground
import com.example.ui.components.FrostedCard
import com.example.ui.components.LocalHazeState
import com.example.ui.components.extractArtworkGlowPalette
import com.example.ui.components.extractDominantColor
import com.example.ui.components.rememberArtworkBitmap
import com.example.ui.components.rememberArtworkGlowBitmap
import com.example.ui.theme.LyricBarTypography
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NowPlayingScreen(
    playbackManager: MediaPlaybackManager,
    userPreferences: UserPreferences,
    onNavigateToLyrics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val state by playbackManager.playbackState.collectAsStateWithLifecycle()
    val activeLyric by playbackManager.activeLyricLine.collectAsStateWithLifecycle()
    val activeLyricObj by playbackManager.activeLyricObject.collectAsStateWithLifecycle()
    val currentLyrics by playbackManager.currentLyrics.collectAsStateWithLifecycle()
    val isLoadingLyrics by playbackManager.isLoadingLyrics.collectAsStateWithLifecycle()
    val trackOffset by playbackManager.trackOffsetMs.collectAsStateWithLifecycle()
    val track = state.track

    val activeIdx = remember(activeLyricObj, currentLyrics) {
        val lines = currentLyrics?.lines
        if (lines != null && activeLyricObj != null) {
            lines.indexOfFirst { it == activeLyricObj }
        } else -1
    }

    // Determine preceding and upcoming lyrics for polished 3-line contextual display
    val prevLyric = remember(activeIdx, currentLyrics) {
        val lines = currentLyrics?.lines
        if (lines != null && activeIdx > 0 && activeIdx < lines.size) {
            lines[activeIdx - 1].text.takeIf { it.isNotBlank() }
        } else null
    }

    val nextLyric = remember(activeIdx, currentLyrics) {
        val lines = currentLyrics?.lines
        if (lines != null && activeIdx in 0 until lines.size - 1) {
            lines[activeIdx + 1].text.takeIf { it.isNotBlank() }
        } else null
    }

    // Shared lifecycle-safe artwork decoding
    val rawArtworkBitmap = rememberArtworkBitmap(track)

    val artworkImageBitmap = remember(rawArtworkBitmap) {
        try {
            rawArtworkBitmap?.takeIf { !it.isRecycled }?.asImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val glowPalette = remember(rawArtworkBitmap, track?.key, primaryColor) {
        if (rawArtworkBitmap != null && !rawArtworkBitmap.isRecycled) {
            extractArtworkGlowPalette(rawArtworkBitmap, primaryColor)
        } else {
            ArtworkGlowPalette(primaryColor, primaryColor, primaryColor)
        }
    }
    val ambientGlowColor = glowPalette.primary
    val artworkGlowBitmap = rememberArtworkGlowBitmap(track, rawArtworkBitmap, glowPalette)

    var isDelayExpanded by rememberSaveable { mutableStateOf(false) }
    var pendingOverlayEnable by rememberSaveable { mutableStateOf(false) }
    val parentHazeState = LocalHazeState.current
    val fallbackHazeState = remember { HazeState() }

    // Reconcile overlay permission state upon returning from system settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val hasPermission = Settings.canDrawOverlays(context)
                if (pendingOverlayEnable) {
                    pendingOverlayEnable = false
                    if (hasPermission) {
                        coroutineScope.launch {
                            playbackManager.preferencesRepository.setOverlayEnabled(true)
                            OverlayService.start(context)
                        }
                    }
                } else if (userPreferences.overlayEnabled && !hasPermission) {
                    coroutineScope.launch {
                        playbackManager.preferencesRepository.setOverlayEnabled(false)
                        OverlayService.stop(context)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val hasSession = state.hasActiveSession
    val isPlaying = state.isPlaying
    val lyrics = currentLyrics
    val hasValidLyrics = lyrics != null && lyrics.hasValidLyrics
    val isSynced = hasValidLyrics && lyrics?.isSynced == true

    // Resolve semantic presentation states for the Live Lyrics card
    val lyricsBadgeText: String
    val lyricsBadgeColor: Color
    val prevLyricLine: String?
    val primaryLyricLine: String
    val secondaryLyricLine: String?

    when {
        !hasSession && track == null -> {
            lyricsBadgeText = "NO SESSION"
            lyricsBadgeColor = MaterialTheme.colorScheme.outline
            prevLyricLine = null
            primaryLyricLine = "No active music session"
            secondaryLyricLine = "Play music on Spotify, YouTube Music, or any media player"
        }
        !hasSession && track != null -> {
            lyricsBadgeText = "SESSION PAUSED"
            lyricsBadgeColor = MaterialTheme.colorScheme.outline
            prevLyricLine = null
            primaryLyricLine = activeLyric?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) } ?: track.title
            secondaryLyricLine = "Media player was closed or disconnected"
        }
        isLoadingLyrics -> {
            lyricsBadgeText = "SEARCHING LYRICS"
            lyricsBadgeColor = MaterialTheme.colorScheme.primary
            prevLyricLine = null
            primaryLyricLine = "Finding synchronized lyrics..."
            secondaryLyricLine = listOfNotNull(track?.artist, track?.title).filter { it.isNotBlank() }.joinToString(" — ")
        }
        lyrics == null || !hasValidLyrics -> {
            lyricsBadgeText = "NO LYRICS"
            lyricsBadgeColor = MaterialTheme.colorScheme.onSurfaceVariant
            prevLyricLine = null
            primaryLyricLine = "No lyrics found for this track"
            secondaryLyricLine = "Tap Full Lyrics to search or import .lrc"
        }
        isSynced -> {
            lyricsBadgeText = if (isPlaying) "LIVE LYRICS" else "LYRICS (PAUSED)"
            lyricsBadgeColor = MaterialTheme.colorScheme.primary
            val validActive = activeLyric?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            if (validActive != null) {
                prevLyricLine = prevLyric?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                primaryLyricLine = validActive
                secondaryLyricLine = nextLyric?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            } else {
                prevLyricLine = null
                primaryLyricLine = "♪ Instrumental ♪"
                secondaryLyricLine = nextLyric?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }?.let { "Up next: $it" } ?: "Lyrics will resume with vocals..."
            }
        }
        else -> {
            lyricsBadgeText = "PLAIN LYRICS"
            lyricsBadgeColor = MaterialTheme.colorScheme.primary
            prevLyricLine = null
            primaryLyricLine = lyrics.lines.firstOrNull { it.text.isNotBlank() && !it.text.trim().equals("null", ignoreCase = true) }?.text
                ?: lyrics.plainText.lineSequence().firstOrNull { it.isNotBlank() && !it.trim().equals("null", ignoreCase = true) }
                ?: "Unsynchronized lyrics available"
            secondaryLyricLine = "Tap Full Lyrics to read all lines"
        }
    }

    val content: @Composable () -> Unit = {
            // Centered scrollable foreground content with bottom clearance for floating nav pill
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .padding(bottom = 100.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Adaptive container width limiter for larger windows
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Top Header: Branding + HUD Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                                modifier = Modifier.size(34.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .graphicsLayer { translationX = 1.dp.toPx() }
                                    )
                                }
                            }
                            Text(
                                text = "LyricBar",
                                style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        // Quick toggle for overlay HUD with safe permission reconciliation
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (userPreferences.overlayEnabled) "HUD On" else "HUD Off",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (userPreferences.overlayEnabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Switch(
                                checked = userPreferences.overlayEnabled,
                                onCheckedChange = { enabled ->
                                    coroutineScope.launch {
                                        if (enabled) {
                                            if (Settings.canDrawOverlays(context)) {
                                                playbackManager.preferencesRepository.setOverlayEnabled(true)
                                                OverlayService.start(context)
                                            } else {
                                                pendingOverlayEnable = true
                                                val intent = Intent(
                                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                    Uri.parse("package:${context.packageName}")
                                                )
                                                context.startActivity(intent)
                                            }
                                        } else {
                                            pendingOverlayEnable = false
                                            playbackManager.preferencesRepository.setOverlayEnabled(false)
                                            OverlayService.stop(context)
                                        }
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    checkedBorderColor = Color.Transparent,
                                    uncheckedBorderColor = Color.Transparent
                                ),
                                modifier = Modifier.testTag("now_playing_overlay_switch")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Foreground Album Artwork with organic ambient glow radiating naturally from the artwork
                    val artworkShape = RoundedCornerShape(26.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.72f)
                            .widthIn(max = 280.dp)
                            .aspectRatio(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        // 1. Organic Artwork Ambient Glow Layer: Radiates outward matching the 26.dp rounded silhouette
                        // Inner bounds match the 280dp artwork (320 / 220 = 1.455f), diffusing 50px Gaussian halo outward
                        // Zero circular discs, zero black patches, and zero rectangular edge cuts.
                        if (artworkGlowBitmap != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        scaleX = 1.455f
                                        scaleY = 1.455f
                                        clip = false
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Crossfade(
                                    targetState = track?.key ?: "",
                                    animationSpec = tween(durationMillis = 280),
                                    label = "artwork_glow_crossfade"
                                ) { _ ->
                                    Image(
                                        bitmap = artworkGlowBitmap.asImageBitmap(),
                                        contentDescription = null,
                                        contentScale = ContentScale.FillBounds,
                                        alpha = 0.90f,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }

                        // 2. Sharp foreground album artwork container (floating over the ambient glow)
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(artworkShape)
                                .border(
                                    width = 1.dp,
                                    color = Color.White.copy(alpha = 0.14f),
                                    shape = artworkShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Crossfade(
                                targetState = track?.key ?: "",
                                animationSpec = tween(durationMillis = 220),
                                label = "artwork_crossfade"
                            ) { _ ->
                                if (artworkImageBitmap != null) {
                                    Image(
                                        bitmap = artworkImageBitmap,
                                        contentDescription = "Album art for ${track?.title ?: "current track"}",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .testTag("now_playing_album_art")
                                    )
                                } else {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f))
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                            modifier = Modifier.size(64.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = Icons.Default.MusicNote,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(32.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = if (hasSession) "Loading Art..." else "Ready to Sync",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Track Title & Artist
                    Text(
                        text = track?.title ?: "No Music Playing",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = track?.artist ?: "Play music on Spotify, YouTube Music, or any media player",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (hasSession && track != null && track.sourceAppName.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                            modifier = Modifier.testTag("now_playing_source_badge")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = track.sourceAppName,
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    } else {
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // Live Lyrics Card (Unified frosted glass card)
                    // Live Lyrics Card (Unified frosted glass card with 3-line contextual lyrics)
                    FrostedCard(
                        shape = RoundedCornerShape(20.dp),
                        backgroundAlpha = 0.20f,
                        blurRadius = 22.dp,
                        borderWidth = 0.75.dp,
                        shadowElevation = 0.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = lyricsBadgeColor,
                                        modifier = Modifier.size(6.dp)
                                    ) {}
                                    Text(
                                        text = lyricsBadgeText,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.2.sp,
                                        color = lyricsBadgeColor
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onNavigateToLyrics() }
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "Full Lyrics",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Icon(
                                        imageVector = Icons.Default.FormatQuote,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Preceding lyric line (subtle & dimmed for contextual continuity with smooth transition)
                            AnimatedContent(
                                targetState = prevLyricLine,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(200, easing = LinearOutSlowInEasing))
                                        .togetherWith(fadeOut(animationSpec = tween(150, easing = FastOutLinearInEasing)))
                                },
                                label = "prev_lyric_transition"
                            ) { prevText ->
                                if (!prevText.isNullOrBlank()) {
                                    Text(
                                        text = prevText,
                                        style = LyricBarTypography.contextualLyricStyle(
                                            fontOption = userPreferences.selectedFont,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                        ),
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                }
                            }

                            // Primary active lyric line with subtle, premium motion
                            // Animated only when the active lyric string actually changes value
                            AnimatedContent(
                                targetState = primaryLyricLine,
                                transitionSpec = {
                                    (fadeIn(animationSpec = tween(220, easing = LinearOutSlowInEasing)) +
                                        slideInVertically(animationSpec = tween(220, easing = LinearOutSlowInEasing)) { height -> height / 4 })
                                        .togetherWith(
                                            fadeOut(animationSpec = tween(180, easing = FastOutLinearInEasing)) +
                                            slideOutVertically(animationSpec = tween(180, easing = FastOutLinearInEasing)) { height -> -height / 4 }
                                        )
                                },
                                label = "primary_lyric_transition"
                            ) { lyricText ->
                                Text(
                                    text = lyricText,
                                    style = LyricBarTypography.activeLyricStyle(
                                        fontOption = userPreferences.selectedFont,
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // Upcoming lyric preview line with smooth transition
                            AnimatedContent(
                                targetState = secondaryLyricLine,
                                transitionSpec = {
                                    fadeIn(animationSpec = tween(200, easing = LinearOutSlowInEasing))
                                        .togetherWith(fadeOut(animationSpec = tween(150, easing = FastOutLinearInEasing)))
                                },
                                label = "secondary_lyric_transition"
                            ) { secText ->
                                if (!secText.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = secText,
                                        style = LyricBarTypography.contextualLyricStyle(
                                            fontOption = userPreferences.selectedFont,
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.70f)
                                        ),
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Compact Expandable Timing-Delay Card
                    val offsetSec = trackOffset / 1000.0
                    val formattedOffset = if (trackOffset > 0) "+%.1fs".format(offsetSec) else if (trackOffset < 0) "%.1fs".format(offsetSec) else "0.0s (In Sync)"

                    FrostedCard(
                        shape = RoundedCornerShape(18.dp),
                        backgroundAlpha = 0.18f,
                        blurRadius = 18.dp,
                        borderWidth = 0.75.dp,
                        shadowElevation = 0.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("now_playing_delay_card")
                            .animateContentSize(animationSpec = tween(durationMillis = 200))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                        ) {
                            // Header row acting as expandable chip
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { isDelayExpanded = !isDelayExpanded }
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Timer,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "TIMING OFFSET",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = formattedOffset,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (trackOffset != 0L) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                            modifier = Modifier.testTag("now_playing_delay_display")
                                        )
                                    }
                                    Icon(
                                        imageVector = if (isDelayExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                        contentDescription = if (isDelayExpanded) "Collapse offset controls" else "Expand offset controls",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // Compact expanded adjustment controls with accessible 48dp touch targets
                            if (isDelayExpanded) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TimingAdjustPill(
                                        text = "-0.5s",
                                        onClick = { playbackManager.adjustTrackOffset(-500L) },
                                        modifier = Modifier.weight(1f).testTag("now_playing_delay_minus_500")
                                    )
                                    TimingAdjustPill(
                                        text = "-0.1s",
                                        onClick = { playbackManager.adjustTrackOffset(-100L) },
                                        modifier = Modifier.weight(1f).testTag("now_playing_delay_minus_100")
                                    )
                                    if (trackOffset != 0L) {
                                        TimingAdjustPill(
                                            text = "Reset",
                                            isPrimary = true,
                                            onClick = { playbackManager.setExplicitOffset(0L) },
                                            modifier = Modifier.weight(1f).testTag("now_playing_delay_reset")
                                        )
                                    }
                                    TimingAdjustPill(
                                        text = "+0.1s",
                                        onClick = { playbackManager.adjustTrackOffset(100L) },
                                        modifier = Modifier.weight(1f).testTag("now_playing_delay_plus_100")
                                    )
                                    TimingAdjustPill(
                                        text = "+0.5s",
                                        onClick = { playbackManager.adjustTrackOffset(500L) },
                                        modifier = Modifier.weight(1f).testTag("now_playing_delay_plus_500")
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Tactile Hero Playback Controls with state indication
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { playbackManager.previous() },
                            enabled = hasSession,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "Previous Track",
                                tint = MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = if (hasSession) 0.90f else 0.38f
                                ),
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(96.dp)
                        ) {
                            // Ambient halo outer ring (96dp)
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary.copy(
                                    alpha = if (isPlaying) 0.20f else 0.08f
                                ),
                                modifier = Modifier.size(96.dp)
                            ) {}

                            // Center play/pause action button (82dp)
                            Surface(
                                onClick = { playbackManager.playPause() },
                                enabled = hasSession || track != null,
                                modifier = Modifier.size(82.dp),
                                shape = CircleShape,
                                color = if (hasSession || track != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                shadowElevation = if (isPlaying) 8.dp else 3.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = if (hasSession || track != null) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .graphicsLayer {
                                                translationX = if (!isPlaying) 2.dp.toPx() else 0f
                                            }
                                    )
                                }
                            }
                        }

                        IconButton(
                            onClick = { playbackManager.next() },
                            enabled = hasSession,
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next Track",
                                tint = MaterialTheme.colorScheme.onSurface.copy(
                                    alpha = if (hasSession) 0.90f else 0.38f
                                ),
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                }
            }
    }

    if (parentHazeState != null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            content()
        }
    } else {
        CinematicArtworkBackground(
            artworkBitmap = rawArtworkBitmap,
            trackKey = track?.key ?: "",
            hazeState = fallbackHazeState,
            modifier = modifier.fillMaxSize()
        ) {
            CompositionLocalProvider(LocalHazeState provides fallbackHazeState) {
                content()
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
            .defaultMinSize(minHeight = 48.dp)
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

/**
 * Safe artwork bitmap loader delegating to the shared GlassSurface implementation.
 */
private fun loadArtworkBitmapSafely(context: Context, uriString: String): Bitmap? =
    com.example.ui.components.loadArtworkBitmapSafely(context, uriString)
