package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ParsedLyrics
import com.example.data.model.TrackMetadata
import com.example.data.preferences.UserPreferences
import com.example.service.MediaPlaybackManager
import com.example.service.OverlayService
import com.example.ui.components.ArtworkGlowPalette
import com.example.ui.components.CinematicArtworkBackground
import com.example.ui.components.FrostedCard
import com.example.ui.components.LocalHazeState
import com.example.ui.components.extractArtworkGlowPalette
import com.example.ui.components.rememberArtworkBitmap
import com.example.ui.components.rememberArtworkGlowBitmap
import com.example.ui.theme.LyricBarTypography
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Semantic presentation model for the Now Playing Live Lyrics Card.
 */
internal data class NowPlayingLyricsCardState(
    val badgeText: String,
    val isBadgeAccent: Boolean,
    val prevLine: String?,
    val primaryLine: String,
    val secondaryLine: String?,
    val showInstrumentalWaveform: Boolean
)

/**
 * Responsive layout metrics computed dynamically from available window constraints and font scale.
 */
internal data class NowPlayingResponsiveMetrics(
    val isLandscape: Boolean,
    val isShortHeight: Boolean,
    val isNarrowWidth: Boolean,
    val requiresScrollFallback: Boolean,
    val artworkSizeDp: Float,
    val horizontalPaddingDp: Float,
    val headerTitleSp: Float,
    val titleFontSizeSp: Float,
    val artistFontSizeSp: Float,
    val activeLyricFontSizeSp: Float,
    val playButtonOuterDp: Float,
    val playButtonInnerDp: Float,
    val secondaryControlDp: Float,
    val sectionSpacingDp: Float,
    val badgeToLyricsPaddingDp: Float = 6f,
    val artworkToInfoPaddingDp: Float = 10f,
    val lyricsToProgressPaddingDp: Float = 12f,
    val progressToControlsPaddingDp: Float = 12f
)

/**
 * Computes responsive layout metrics for NowPlayingScreen across 320dp–480dp+ widths,
 * short/standard/tall heights, landscape orientations, and 1.0x–1.5x font scales.
 */
internal fun computeResponsiveNowPlayingMetrics(
    availableWidthDp: Float,
    availableHeightDp: Float,
    fontScale: Float
): NowPlayingResponsiveMetrics {
    val rawIsLandscape = availableWidthDp > availableHeightDp &&
        (availableHeightDp < 540f || availableWidthDp >= 640f)
    val safeW = availableWidthDp.coerceAtLeast(280f)
    val safeH = availableHeightDp.coerceAtLeast(if (rawIsLandscape) 260f else 360f)
    val safeFontScale = fontScale.coerceIn(0.85f, 2.0f)

    val isLandscape = rawIsLandscape
    val isNarrowWidth = safeW < 350f
    val isShortHeight = !isLandscape && (safeH < 660f || (safeH < 720f && safeFontScale >= 1.3f))

    val horizontalPaddingDp = when {
        isLandscape -> if (safeW >= 900f) 28f else 18f
        safeW < 340f -> 14f
        safeW < 380f -> 18f
        else -> 22f
    }

    val contentWidth = (safeW - horizontalPaddingDp * 2f).coerceAtMost(500f)

    // Width-based artwork proportion:
    // Compact devices: ~66-70% of available content width
    // Normal devices: ~74-75% of available content width
    // Constrained to a reasonable maximum dimension so it never dominates large screens.
    val widthFraction = when {
        isNarrowWidth -> 0.66f
        safeW <= 375f -> 0.70f
        safeW <= 430f -> 0.75f
        else -> 0.72f
    }
    val widthBasedArt = if (isLandscape) {
        ((safeW - horizontalPaddingDp * 2f) * 0.42f - 24f).coerceIn(96f, 280f)
    } else {
        (contentWidth * widthFraction).coerceIn(120f, 300f)
    }

    // Reserve vertical space for Header (~44dp), Song Info (~80dp), Live Lyrics Card (~112dp),
    // Seek Bar (~44dp), Playback Controls (~74dp), and core semantic spacings (~50dp).
    val nonArtworkVerticalEstimate = (404f + (safeFontScale - 1.0f).coerceAtLeast(0f) * 95f)
    val heightBasedArt = if (isLandscape) {
        if (safeH >= 540f) {
            (safeH * 0.34f).coerceIn(180f, 280f)
        } else {
            ((safeH - 180f) * 0.92f).coerceIn(96f, 220f)
        }
    } else {
        (safeH - nonArtworkVerticalEstimate).coerceIn(128f, 300f)
    }

    val artworkSizeDp = minOf(widthBasedArt, heightBasedArt)
    val requiresScrollFallback = !isLandscape && (safeH < 610f || (safeH - nonArtworkVerticalEstimate) < 136f)

    val headerTitleSp = when {
        isLandscape && safeH < 360f -> 17f
        isLandscape -> 19f
        isNarrowWidth -> 18f
        else -> 20f
    }
    val titleFontSizeSp = when {
        isLandscape && safeH < 360f -> 17.5f
        isLandscape && safeH < 500f -> 19.5f
        isShortHeight || isNarrowWidth -> 20f
        else -> 23f
    }
    val artistFontSizeSp = when {
        isLandscape && safeH < 360f -> 13f
        isLandscape && safeH < 500f -> 14f
        isShortHeight || isNarrowWidth -> 13.5f
        else -> 15f
    }
    val activeLyricFontSizeSp = when {
        isLandscape && safeH < 380f -> 17f
        isLandscape -> 19f
        isShortHeight -> 17f
        isNarrowWidth -> 18f
        else -> 19.5f
    }

    val playButtonOuterDp = when {
        isLandscape && safeH < 390f -> 62f
        isLandscape && safeH < 500f -> 68f
        isShortHeight || isNarrowWidth -> 70f
        else -> 78f
    }
    val playButtonInnerDp = when {
        isLandscape && safeH < 390f -> 52f
        isLandscape && safeH < 500f -> 58f
        isShortHeight || isNarrowWidth -> 58f
        else -> 66f
    }
    val secondaryControlDp = if ((isLandscape && safeH < 390f) || isShortHeight || isNarrowWidth) 40f else 44f
    val sectionSpacingDp = when {
        isLandscape && safeH < 380f -> 8f
        isLandscape -> 12f
        isShortHeight -> 8f
        safeH < 780f -> 10f
        else -> 12f
    }

    // Dynamic paddings proportional to available viewport height (safeH)
    val artworkToInfoPaddingDp = (safeH * 0.015f).coerceIn(8f, 13f)
    val badgeToLyricsPaddingDp = (safeH * 0.014f).coerceIn(7f, 12f)
    val lyricsToProgressPaddingDp = (safeH * 0.014f).coerceIn(8f, 12f)
    val progressToControlsPaddingDp = (safeH * 0.016f).coerceIn(9f, 14f)

    return NowPlayingResponsiveMetrics(
        isLandscape = isLandscape,
        isShortHeight = isShortHeight,
        isNarrowWidth = isNarrowWidth,
        requiresScrollFallback = requiresScrollFallback,
        artworkSizeDp = artworkSizeDp,
        horizontalPaddingDp = horizontalPaddingDp,
        headerTitleSp = headerTitleSp,
        titleFontSizeSp = titleFontSizeSp,
        artistFontSizeSp = artistFontSizeSp,
        activeLyricFontSizeSp = activeLyricFontSizeSp,
        playButtonOuterDp = playButtonOuterDp,
        playButtonInnerDp = playButtonInnerDp,
        secondaryControlDp = secondaryControlDp,
        sectionSpacingDp = sectionSpacingDp,
        badgeToLyricsPaddingDp = badgeToLyricsPaddingDp,
        artworkToInfoPaddingDp = artworkToInfoPaddingDp,
        lyricsToProgressPaddingDp = lyricsToProgressPaddingDp,
        progressToControlsPaddingDp = progressToControlsPaddingDp
    )
}

/**
 * Resolves the actual lyrics provider badge text for the current track.
 * - Returns the actual provider that supplied the lyrics (`LRCLIB`, `lrc.red`, `KuGou`, `LyricsPlus`, or `Local Import`).
 * - Never returns the user's selected provider preference (`AUTO`, etc.).
 * - Never fabricates a provider name when no valid lyrics are loaded.
 */
internal fun resolveNowPlayingSourceBadge(
    lyrics: ParsedLyrics?,
    hasValidLyrics: Boolean
): String? {
    if (lyrics == null || !hasValidLyrics) return null
    val source = lyrics.displaySource.trim()
    return source.takeIf {
        it.isNotBlank() &&
            !it.equals("null", ignoreCase = true) &&
            !it.equals("undefined", ignoreCase = true) &&
            !it.equals("Automatic", ignoreCase = true) &&
            !it.equals("AUTO", ignoreCase = true)
    }
}

/**
 * Resolves the Live Lyrics card semantic presentation state.
 * Ensures paused or disconnected sessions are clearly indicated and never present stale lyrics as live.
 */
internal fun resolveNowPlayingLyricsCardState(
    hasSession: Boolean,
    isPlaying: Boolean,
    track: TrackMetadata?,
    isLoadingLyrics: Boolean,
    lyrics: ParsedLyrics?,
    hasValidLyrics: Boolean,
    isSynced: Boolean,
    activeLyric: String?,
    prevLyric: String?,
    nextLyric: String?
): NowPlayingLyricsCardState {
    return when {
        !hasSession && track == null -> {
            NowPlayingLyricsCardState(
                badgeText = "NO SESSION",
                isBadgeAccent = false,
                prevLine = null,
                primaryLine = "No active music session",
                secondaryLine = "Play music on Spotify, YouTube Music, or any media player",
                showInstrumentalWaveform = false
            )
        }
        !hasSession && track != null -> {
            NowPlayingLyricsCardState(
                badgeText = "SESSION DISCONNECTED",
                isBadgeAccent = false,
                prevLine = null,
                primaryLine = "Media session disconnected",
                secondaryLine = "Resume playback in ${track.sourceAppName.ifBlank { "your music player" }} to sync lyrics",
                showInstrumentalWaveform = false
            )
        }
        isLoadingLyrics -> {
            NowPlayingLyricsCardState(
                badgeText = "SEARCHING LYRICS",
                isBadgeAccent = true,
                prevLine = null,
                primaryLine = "Finding synchronized lyrics…",
                secondaryLine = listOfNotNull(track?.artist, track?.title)
                    .filter { it.isNotBlank() }
                    .joinToString(" — ")
                    .ifBlank { "Checking online providers…" },
                showInstrumentalWaveform = false
            )
        }
        lyrics == null || !hasValidLyrics -> {
            NowPlayingLyricsCardState(
                badgeText = "NO LYRICS",
                isBadgeAccent = false,
                prevLine = null,
                primaryLine = "No lyrics found for this track",
                secondaryLine = "Tap Full Lyrics to search or import .lrc",
                showInstrumentalWaveform = false
            )
        }
        isSynced -> {
            val validActive = activeLyric?.trim()?.takeIf {
                it.isNotBlank() && !it.equals("null", ignoreCase = true) && !it.equals("undefined", ignoreCase = true)
            }
            val validPrev = prevLyric?.trim()?.takeIf {
                it.isNotBlank() && !it.equals("null", ignoreCase = true) && !it.equals("undefined", ignoreCase = true)
            }
            val validNext = nextLyric?.trim()?.takeIf {
                it.isNotBlank() && !it.equals("null", ignoreCase = true) && !it.equals("undefined", ignoreCase = true)
            }

            if (!isPlaying) {
                NowPlayingLyricsCardState(
                    badgeText = "LYRICS PAUSED",
                    isBadgeAccent = false,
                    prevLine = null,
                    primaryLine = validActive ?: "♪ Instrumental ♪",
                    secondaryLine = "Playback paused — resume to continue live sync",
                    showInstrumentalWaveform = validActive == null
                )
            } else if (validActive != null) {
                NowPlayingLyricsCardState(
                    badgeText = "LIVE LYRICS",
                    isBadgeAccent = true,
                    prevLine = validPrev,
                    primaryLine = validActive,
                    secondaryLine = validNext,
                    showInstrumentalWaveform = false
                )
            } else {
                NowPlayingLyricsCardState(
                    badgeText = "LIVE LYRICS",
                    isBadgeAccent = true,
                    prevLine = null,
                    primaryLine = "♪ Instrumental ♪",
                    secondaryLine = "Lyrics will resume with vocals...",
                    showInstrumentalWaveform = true
                )
            }
        }
        else -> {
            val firstLine = lyrics.lines.firstOrNull {
                val t = it.text.trim()
                t.isNotBlank() && !t.equals("null", ignoreCase = true) && !t.equals("undefined", ignoreCase = true)
            }?.text?.trim()
                ?: lyrics.plainText.lineSequence().map { it.trim() }.firstOrNull {
                    it.isNotBlank() && !it.equals("null", ignoreCase = true) && !it.equals("undefined", ignoreCase = true)
                }
                ?: "Unsynchronized lyrics available"

            NowPlayingLyricsCardState(
                badgeText = "PLAIN LYRICS",
                isBadgeAccent = true,
                prevLine = null,
                primaryLine = firstLine,
                secondaryLine = "Tap Full Lyrics to read all lines",
                showInstrumentalWaveform = false
            )
        }
    }
}

/**
 * Formats playback milliseconds into `M:SS` or `H:MM:SS`.
 * Returns `"--:--"` when [timeMs] is negative (representing unknown/unavailable duration).
 */
internal fun formatNowPlayingTimestamp(timeMs: Long): String {
    if (timeMs < 0L) return "--:--"
    val totalSeconds = (timeMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Inspects the active [MediaController] for Previous, Next, and Seek capabilities
 * without fabricating state when the external player does not expose them.
 */
private data class TransportCapabilities(
    val canSkipPrevious: Boolean = false,
    val canSkipNext: Boolean = false,
    val canSeek: Boolean = false
)

private fun resolveTransportCapabilities(
    controller: MediaController?,
    hasSession: Boolean,
    totalDurationMs: Long
): TransportCapabilities {
    if (!hasSession || controller == null) {
        return TransportCapabilities()
    }
    return try {
        val pbState = controller.playbackState
        val actions = pbState?.actions ?: 0L
        val canPrev = actions == 0L || (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L
        val canNext = actions == 0L || (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L
        val canSeek = totalDurationMs > 0L && (actions == 0L || (actions and PlaybackState.ACTION_SEEK_TO) != 0L)

        TransportCapabilities(
            canSkipPrevious = canPrev,
            canSkipNext = canNext,
            canSeek = canSeek
        )
    } catch (_: Throwable) {
        TransportCapabilities(
            canSkipPrevious = true,
            canSkipNext = true,
            canSeek = totalDurationMs > 0L
        )
    }
}

@Composable
fun NowPlayingScreen(
    playbackManager: MediaPlaybackManager,
    userPreferences: UserPreferences,
    onNavigateToLyrics: () -> Unit,
    modifier: Modifier = Modifier,
    hasBottomNavPill: Boolean = true
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val state by playbackManager.playbackState.collectAsStateWithLifecycle()
    val activeLyric by playbackManager.activeLyricLine.collectAsStateWithLifecycle()
    val activeLyricObj by playbackManager.activeLyricObject.collectAsStateWithLifecycle()
    val currentLyrics by playbackManager.currentLyrics.collectAsStateWithLifecycle()
    val isLoadingLyrics by playbackManager.isLoadingLyrics.collectAsStateWithLifecycle()
    val track = state.track

    val activeIdx = remember(activeLyricObj, currentLyrics) {
        val lines = currentLyrics?.lines
        if (lines != null && activeLyricObj != null) {
            lines.indexOfFirst { it == activeLyricObj }
        } else -1
    }

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
    val artworkGlowBitmap = rememberArtworkGlowBitmap(track, rawArtworkBitmap, glowPalette)

    var pendingOverlayEnable by rememberSaveable { mutableStateOf(false) }
    var isScreenResumed by remember { mutableStateOf(true) }
    val parentHazeState = LocalHazeState.current
    val fallbackHazeState = remember { HazeState() }

    // Reconcile overlay permission state and track lifecycle for battery-safe progress updates
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isScreenResumed = true
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
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    isScreenResumed = false
                }
                else -> Unit
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

    // Actual track duration in milliseconds (or 0L if unknown/unavailable — never fabricated)
    val totalDurationMs = remember(track?.durationSec) {
        val durationSec = track?.durationSec ?: 0
        if (durationSec > 0) durationSec * 1000L else 0L
    }

    var isDraggingSeek by remember { mutableStateOf(false) }
    var dragSeekFraction by remember { mutableFloatStateOf(0f) }
    var currentPlaybackPosMs by remember(track?.key) {
        mutableLongStateOf(state.calculatedPositionMs.coerceAtLeast(0L))
    }

    // Lifecycle-aware playback progress ticker: runs ONLY when actively playing and screen is resumed
    LaunchedEffect(isPlaying, isScreenResumed, state.positionMs, state.lastUpdatedMs, state.playbackSpeed, totalDurationMs) {
        if (isPlaying && isScreenResumed) {
            while (isActive) {
                if (!isDraggingSeek) {
                    val rawPos = state.calculatedPositionMs.coerceAtLeast(0L)
                    currentPlaybackPosMs = if (totalDurationMs > 0L) rawPos.coerceAtMost(totalDurationMs) else rawPos
                }
                delay(200L)
            }
        } else if (!isDraggingSeek) {
            val rawPos = state.calculatedPositionMs.coerceAtLeast(0L)
            currentPlaybackPosMs = if (totalDurationMs > 0L) rawPos.coerceAtMost(totalDurationMs) else rawPos
        }
    }

    val transportCaps = remember(state, playbackManager.activeController, hasSession, totalDurationMs) {
        resolveTransportCapabilities(
            controller = playbackManager.activeController,
            hasSession = hasSession,
            totalDurationMs = totalDurationMs
        )
    }

    val sourceBadgeText = remember(lyrics, hasValidLyrics) {
        resolveNowPlayingSourceBadge(lyrics = lyrics, hasValidLyrics = hasValidLyrics)
    }

    val lyricsCardState = remember(
        hasSession,
        isPlaying,
        track,
        isLoadingLyrics,
        lyrics,
        hasValidLyrics,
        isSynced,
        activeLyric,
        prevLyric,
        nextLyric
    ) {
        resolveNowPlayingLyricsCardState(
            hasSession = hasSession,
            isPlaying = isPlaying,
            track = track,
            isLoadingLyrics = isLoadingLyrics,
            lyrics = lyrics,
            hasValidLyrics = hasValidLyrics,
            isSynced = isSynced,
            activeLyric = activeLyric,
            prevLyric = prevLyric,
            nextLyric = nextLyric
        )
    }

    val density = LocalDensity.current
    val activeHazeState = parentHazeState ?: fallbackHazeState

    val bottomClearance = if (hasBottomNavPill) {
        if (density.fontScale >= 1.25f) 86.dp else 82.dp
    } else {
        4.dp
    }

    val content: @Composable () -> Unit = {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (hasBottomNavPill) {
                        Modifier
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                    } else {
                        Modifier
                    }
                )
                .padding(bottom = bottomClearance),
            contentAlignment = Alignment.TopCenter
        ) {
            val availableWidth = maxWidth
            val availableHeight = maxHeight
            val metrics = remember(availableWidth, availableHeight, density.fontScale) {
                computeResponsiveNowPlayingMetrics(
                    availableWidthDp = availableWidth.value,
                    availableHeightDp = availableHeight.value,
                    fontScale = density.fontScale
                )
            }

            if (metrics.isLandscape) {
                // Intentional Two-Column Landscape Composition:
                // Left = Header (branding + HUD toggle) + Album Artwork + Song Title, Artist & Provider Badge
                // Right = Live Lyrics Card + Playback Progress Bar + Previous / Play-Pause / Next Controls
                val toggleOverlayAction: (Boolean) -> Unit = { enabled ->
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
                }
                val isUltraCompactLandscape = availableHeight < 305.dp || (density.fontScale >= 1.35f && availableHeight < 350.dp)
                val isShortLandscape = availableHeight < 380.dp
                val isCompactBadgeLandscape = availableHeight < 460.dp
                val isTightArtworkGap = availableHeight < 420.dp
                val isWideLandscape = availableWidth >= 840.dp
                val landscapeVerticalGap = if (isShortLandscape) 8.dp else 14.dp

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 1040.dp)
                            .padding(
                                horizontal = metrics.horizontalPaddingDp.dp,
                                vertical = if (isShortLandscape) 4.dp else 10.dp
                            ),
                        horizontalArrangement = Arrangement.spacedBy(if (isWideLandscape) 28.dp else 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left Column: Header pinned cleanly at top, Artwork + Track Info centered in remaining space
                        Column(
                            modifier = Modifier
                                .weight(0.44f)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            NowPlayingTopHeader(
                                overlayEnabled = userPreferences.overlayEnabled,
                                headerTitleSp = metrics.headerTitleSp,
                                primaryColor = primaryColor,
                                onToggleOverlay = toggleOverlayAction
                            )

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                if (isUltraCompactLandscape) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        NowPlayingArtworkHero(
                                            artworkSizeDp = minOf(metrics.artworkSizeDp, 100f),
                                            track = track,
                                            artworkImageBitmap = artworkImageBitmap,
                                            artworkGlowBitmap = artworkGlowBitmap,
                                            hasSession = hasSession,
                                            primaryColor = primaryColor
                                        )
                                        NowPlayingSongInfoSection(
                                            track = track,
                                            sourceBadgeText = sourceBadgeText,
                                            titleFontSizeSp = metrics.titleFontSizeSp,
                                            artistFontSizeSp = metrics.artistFontSizeSp,
                                            primaryColor = primaryColor,
                                            horizontalAlignment = Alignment.Start,
                                            compactBadge = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                } else {
                                    NowPlayingArtworkHero(
                                        artworkSizeDp = metrics.artworkSizeDp,
                                        track = track,
                                        artworkImageBitmap = artworkImageBitmap,
                                        artworkGlowBitmap = artworkGlowBitmap,
                                        hasSession = hasSession,
                                        primaryColor = primaryColor
                                    )

                                    Spacer(modifier = Modifier.height(if (isTightArtworkGap) 8.dp else 12.dp))

                                    NowPlayingSongInfoSection(
                                        track = track,
                                        sourceBadgeText = sourceBadgeText,
                                        titleFontSizeSp = metrics.titleFontSizeSp,
                                        artistFontSizeSp = metrics.artistFontSizeSp,
                                        primaryColor = primaryColor,
                                        compactBadge = isCompactBadgeLandscape
                                    )
                                }
                            }
                        }

                        // Right Column: Live Lyrics Card -> Progress Bar -> Playback Controls
                        Box(
                            modifier = Modifier
                                .weight(0.56f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 520.dp)
                                    .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(landscapeVerticalGap)
                            ) {
                                NowPlayingLiveLyricsPanel(
                                    cardState = lyricsCardState,
                                    userPreferences = userPreferences,
                                    activeLyricFontSizeSp = metrics.activeLyricFontSizeSp,
                                    isCompact = isCompactBadgeLandscape,
                                    primaryColor = primaryColor,
                                    hazeState = activeHazeState,
                                    onNavigateToLyrics = onNavigateToLyrics
                                )

                                NowPlayingProgressSection(
                                    currentPlaybackPosMs = currentPlaybackPosMs,
                                    totalDurationMs = totalDurationMs,
                                    hasTrackOrSession = hasSession || track != null,
                                    canSeek = transportCaps.canSeek,
                                    isDraggingSeek = isDraggingSeek,
                                    dragSeekFraction = dragSeekFraction,
                                    onDragChange = { fraction ->
                                        isDraggingSeek = true
                                        dragSeekFraction = fraction
                                    },
                                    onDragFinished = {
                                        if (totalDurationMs > 0L) {
                                            val targetMs = (dragSeekFraction * totalDurationMs).toLong()
                                            playbackManager.seekTo(targetMs)
                                        }
                                        isDraggingSeek = false
                                    },
                                    primaryColor = primaryColor,
                                    hazeState = activeHazeState
                                )

                                NowPlayingControlsRow(
                                    hasSession = hasSession,
                                    hasTrack = track != null,
                                    isPlaying = isPlaying,
                                    transportCaps = transportCaps,
                                    playButtonOuterDp = metrics.playButtonOuterDp,
                                    playButtonInnerDp = metrics.playButtonInnerDp,
                                    isNarrowWidth = metrics.isNarrowWidth,
                                    primaryColor = primaryColor,
                                    onPlayPause = { playbackManager.playPause() },
                                    onPrevious = { playbackManager.previous() },
                                    onNext = { playbackManager.next() }
                                )
                            }
                        }
                    }
                }
            } else {
                // Portrait / Standard / Foldable Responsive Hierarchy
                if (metrics.requiresScrollFallback) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 500.dp)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = metrics.horizontalPaddingDp.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        NowPlayingTopHeader(
                            overlayEnabled = userPreferences.overlayEnabled,
                            headerTitleSp = metrics.headerTitleSp,
                            primaryColor = primaryColor,
                            onToggleOverlay = { enabled ->
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
                            }
                        )

                        Spacer(modifier = Modifier.height(metrics.sectionSpacingDp.dp))

                        NowPlayingArtworkHero(
                            artworkSizeDp = metrics.artworkSizeDp,
                            track = track,
                            artworkImageBitmap = artworkImageBitmap,
                            artworkGlowBitmap = artworkGlowBitmap,
                            hasSession = hasSession,
                            primaryColor = primaryColor
                        )

                        Spacer(modifier = Modifier.height(metrics.artworkToInfoPaddingDp.dp))

                        NowPlayingSongInfoSection(
                            track = track,
                            sourceBadgeText = sourceBadgeText,
                            titleFontSizeSp = metrics.titleFontSizeSp,
                            artistFontSizeSp = metrics.artistFontSizeSp,
                            primaryColor = primaryColor
                        )

                        Spacer(modifier = Modifier.height(metrics.badgeToLyricsPaddingDp.dp))

                        NowPlayingLiveLyricsPanel(
                            cardState = lyricsCardState,
                            userPreferences = userPreferences,
                            activeLyricFontSizeSp = metrics.activeLyricFontSizeSp,
                            isCompact = true,
                            primaryColor = primaryColor,
                            hazeState = activeHazeState,
                            onNavigateToLyrics = onNavigateToLyrics
                        )

                        Spacer(modifier = Modifier.height(metrics.lyricsToProgressPaddingDp.dp))

                        NowPlayingProgressSection(
                            currentPlaybackPosMs = currentPlaybackPosMs,
                            totalDurationMs = totalDurationMs,
                            hasTrackOrSession = hasSession || track != null,
                            canSeek = transportCaps.canSeek,
                            isDraggingSeek = isDraggingSeek,
                            dragSeekFraction = dragSeekFraction,
                            onDragChange = { fraction ->
                                isDraggingSeek = true
                                dragSeekFraction = fraction
                            },
                            onDragFinished = {
                                if (totalDurationMs > 0L) {
                                    val targetMs = (dragSeekFraction * totalDurationMs).toLong()
                                    playbackManager.seekTo(targetMs)
                                }
                                isDraggingSeek = false
                            },
                            primaryColor = primaryColor,
                            hazeState = activeHazeState
                        )

                        Spacer(modifier = Modifier.height(metrics.progressToControlsPaddingDp.dp))

                        NowPlayingControlsRow(
                            hasSession = hasSession,
                            hasTrack = track != null,
                            isPlaying = isPlaying,
                            transportCaps = transportCaps,
                            playButtonOuterDp = metrics.playButtonOuterDp,
                            playButtonInnerDp = metrics.playButtonInnerDp,
                            isNarrowWidth = metrics.isNarrowWidth,
                            primaryColor = primaryColor,
                            onPlayPause = { playbackManager.playPause() },
                            onPrevious = { playbackManager.previous() },
                            onNext = { playbackManager.next() }
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                } else {
                    // Balanced Portrait Composition:
                    // Artwork & metadata stay tightly associated; lyrics, progress & controls stay visually grouped;
                    // outer cushions & minor badge-to-lyrics flex absorb surplus viewport height evenly.
                    Column(
                        modifier = Modifier
                            .widthIn(max = 500.dp)
                            .fillMaxSize()
                            .padding(horizontal = metrics.horizontalPaddingDp.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 1. Top Header: Branding + HUD Toggle
                        NowPlayingTopHeader(
                            overlayEnabled = userPreferences.overlayEnabled,
                            headerTitleSp = metrics.headerTitleSp,
                            primaryColor = primaryColor,
                            onToggleOverlay = { enabled ->
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
                            }
                        )

                        Spacer(modifier = Modifier.height(6.dp))
                        Spacer(modifier = Modifier.weight(0.45f))

                        // 2. Album Artwork Hero (intrinsic square size, not isolated in an oversized Box)
                        NowPlayingArtworkHero(
                            artworkSizeDp = metrics.artworkSizeDp,
                            track = track,
                            artworkImageBitmap = artworkImageBitmap,
                            artworkGlowBitmap = artworkGlowBitmap,
                            hasSession = hasSession,
                            primaryColor = primaryColor
                        )

                        Spacer(modifier = Modifier.height(metrics.artworkToInfoPaddingDp.dp))

                        // 3 & 4. Song Title, Artist & Lyrics Provider Badge
                        NowPlayingSongInfoSection(
                            track = track,
                            sourceBadgeText = sourceBadgeText,
                            titleFontSizeSp = metrics.titleFontSizeSp,
                            artistFontSizeSp = metrics.artistFontSizeSp,
                            primaryColor = primaryColor
                        )

                        Spacer(modifier = Modifier.height(metrics.badgeToLyricsPaddingDp.dp))
                        Spacer(modifier = Modifier.weight(0.25f))

                        // 5. Live Lyrics Card
                        NowPlayingLiveLyricsPanel(
                            cardState = lyricsCardState,
                            userPreferences = userPreferences,
                            activeLyricFontSizeSp = metrics.activeLyricFontSizeSp,
                            isCompact = metrics.isShortHeight,
                            primaryColor = primaryColor,
                            hazeState = activeHazeState,
                            onNavigateToLyrics = onNavigateToLyrics
                        )

                        Spacer(modifier = Modifier.height(metrics.lyricsToProgressPaddingDp.dp))

                        // 6. Playback Progress Bar & Timestamps
                        NowPlayingProgressSection(
                            currentPlaybackPosMs = currentPlaybackPosMs,
                            totalDurationMs = totalDurationMs,
                            hasTrackOrSession = hasSession || track != null,
                            canSeek = transportCaps.canSeek,
                            isDraggingSeek = isDraggingSeek,
                            dragSeekFraction = dragSeekFraction,
                            onDragChange = { fraction ->
                                isDraggingSeek = true
                                dragSeekFraction = fraction
                            },
                            onDragFinished = {
                                if (totalDurationMs > 0L) {
                                    val targetMs = (dragSeekFraction * totalDurationMs).toLong()
                                    playbackManager.seekTo(targetMs)
                                }
                                isDraggingSeek = false
                            },
                            primaryColor = primaryColor,
                            hazeState = activeHazeState
                        )

                        Spacer(modifier = Modifier.height(metrics.progressToControlsPaddingDp.dp))

                        // 7. Previous, Play/Pause & Next Controls
                        NowPlayingControlsRow(
                            hasSession = hasSession,
                            hasTrack = track != null,
                            isPlaying = isPlaying,
                            transportCaps = transportCaps,
                            playButtonOuterDp = metrics.playButtonOuterDp,
                            playButtonInnerDp = metrics.playButtonInnerDp,
                            isNarrowWidth = metrics.isNarrowWidth,
                            primaryColor = primaryColor,
                            onPlayPause = { playbackManager.playPause() },
                            onPrevious = { playbackManager.previous() },
                            onNext = { playbackManager.next() }
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                        Spacer(modifier = Modifier.weight(0.65f))
                    }
                }
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
private fun NowPlayingTopHeader(
    overlayEnabled: Boolean,
    headerTitleSp: Float,
    primaryColor: Color,
    onToggleOverlay: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.10f),
                border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.14f)),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = primaryColor,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { translationX = 1.dp.toPx() }
                    )
                }
            }
            Text(
                text = "LyricBar",
                fontSize = headerTitleSp.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val hudLabelColor by animateColorAsState(
                targetValue = if (overlayEnabled) primaryColor else Color.White.copy(alpha = 0.65f),
                animationSpec = tween(durationMillis = 180),
                label = "hud_label_color"
            )
            Text(
                text = if (overlayEnabled) "HUD On" else "HUD Off",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = hudLabelColor,
                maxLines = 1
            )
            Switch(
                checked = overlayEnabled,
                onCheckedChange = onToggleOverlay,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF381824),
                    checkedTrackColor = primaryColor,
                    uncheckedThumbColor = Color.White.copy(alpha = 0.75f),
                    uncheckedTrackColor = Color.White.copy(alpha = 0.14f),
                    checkedBorderColor = Color.Transparent,
                    uncheckedBorderColor = Color.White.copy(alpha = 0.20f)
                ),
                modifier = Modifier.testTag("now_playing_overlay_switch")
            )
        }
    }
}

@Composable
private fun NowPlayingArtworkHero(
    artworkSizeDp: Float,
    track: TrackMetadata?,
    artworkImageBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    artworkGlowBitmap: Bitmap?,
    hasSession: Boolean,
    primaryColor: Color
) {
    val artworkShape = RoundedCornerShape(24.dp)
    Box(
        modifier = Modifier
            .size(artworkSizeDp.dp)
            .aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        // 1. Organic Artwork Ambient Glow Layer: Radiates softly around the rounded square silhouette
        if (artworkGlowBitmap != null && !artworkGlowBitmap.isRecycled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.38f
                        scaleY = 1.38f
                        clip = false
                    },
                contentAlignment = Alignment.Center
            ) {
                Crossfade(
                    targetState = track?.key ?: "",
                    animationSpec = tween(durationMillis = 260),
                    label = "artwork_glow_crossfade"
                ) { _ ->
                    Image(
                        bitmap = artworkGlowBitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        alpha = 0.85f,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // 2. Sharp foreground square album artwork with subtle glass border
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(artworkShape)
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.16f),
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
                            .background(Color(0xFF1A161E).copy(alpha = 0.65f))
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = primaryColor.copy(alpha = 0.16f),
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = null,
                                    tint = primaryColor,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (hasSession) "No Artwork" else "Ready to Sync",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.70f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingSongInfoSection(
    track: TrackMetadata?,
    sourceBadgeText: String?,
    titleFontSizeSp: Float,
    artistFontSizeSp: Float,
    primaryColor: Color,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    compactBadge: Boolean = false
) {
    val textAlign = if (horizontalAlignment == Alignment.Start) TextAlign.Start else TextAlign.Center
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (horizontalAlignment == Alignment.Start) 0.dp else 8.dp),
        horizontalAlignment = horizontalAlignment
    ) {
        Text(
            text = track?.title ?: "No Music Playing",
            fontSize = titleFontSizeSp.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("now_playing_track_title")
        )

        Spacer(modifier = Modifier.height(3.dp))

        Text(
            text = track?.artist ?: "Play music on Spotify, YouTube Music, or any media player",
            fontSize = artistFontSizeSp.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White.copy(alpha = 0.76f),
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("now_playing_track_artist")
        )

        if (!sourceBadgeText.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(if (compactBadge) 6.dp else 8.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.White.copy(alpha = 0.10f),
                border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.22f)),
                modifier = Modifier.testTag("now_playing_source_badge")
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = if (compactBadge) 10.dp else 12.dp,
                        vertical = if (compactBadge) 3.dp else 4.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayCircle,
                        contentDescription = null,
                        tint = primaryColor,
                        modifier = Modifier.size(if (compactBadge) 12.dp else 13.dp)
                    )
                    Text(
                        text = sourceBadgeText,
                        fontSize = if (compactBadge) 11.sp else 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.92f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun NowPlayingLiveLyricsPanel(
    cardState: NowPlayingLyricsCardState,
    userPreferences: UserPreferences,
    activeLyricFontSizeSp: Float,
    isCompact: Boolean,
    primaryColor: Color,
    hazeState: HazeState?,
    onNavigateToLyrics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val badgeColor = if (cardState.isBadgeAccent) {
        primaryColor
    } else {
        Color.White.copy(alpha = 0.68f)
    }

    FrostedCard(
        hazeState = hazeState,
        shape = RoundedCornerShape(22.dp),
        backgroundAlpha = 0.24f,
        blurRadius = 24.dp,
        borderWidth = 0.75.dp,
        borderAlpha = 0.22f,
        shadowElevation = 4.dp,
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 200))
            .testTag("now_playing_live_lyrics_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 18.dp,
                    vertical = if (isCompact) 9.dp else 11.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Row: LIVE LYRICS Indicator (left) + Full Lyrics Action (right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = badgeColor,
                        modifier = Modifier.size(7.dp)
                    ) {}
                    Text(
                        text = cardState.badgeText,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                        color = badgeColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            role = Role.Button,
                            onClick = onNavigateToLyrics
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("now_playing_full_lyrics_button")
                ) {
                    Text(
                        text = "Full Lyrics",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryColor,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.FormatQuote,
                        contentDescription = "Full Lyrics",
                        tint = primaryColor,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 6.dp))

            // Preceding lyric line (only composed when non-compact so empty states reserve zero space)
            if (!isCompact) {
                AnimatedContent(
                    targetState = cardState.prevLine,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(180, easing = LinearOutSlowInEasing))
                            .togetherWith(fadeOut(animationSpec = tween(140, easing = FastOutLinearInEasing))))
                            .using(SizeTransform(clip = false))
                    },
                    contentAlignment = Alignment.Center,
                    label = "prev_lyric_transition"
                ) { prevText ->
                    if (!prevText.isNullOrBlank()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = prevText,
                                style = LyricBarTypography.contextualLyricStyle(
                                    fontOption = userPreferences.selectedFont,
                                    fontSize = 12.5.sp,
                                    color = Color.White.copy(alpha = 0.50f)
                                ),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                        }
                    }
                }
            }

            // Primary active lyric or state headline
            AnimatedContent(
                targetState = cardState.primaryLine,
                transitionSpec = {
                    ((fadeIn(animationSpec = tween(220, easing = LinearOutSlowInEasing)) +
                        slideInVertically(animationSpec = tween(220, easing = LinearOutSlowInEasing)) { height -> height / 4 })
                        .togetherWith(
                            fadeOut(animationSpec = tween(160, easing = FastOutLinearInEasing)) +
                                slideOutVertically(animationSpec = tween(160, easing = FastOutLinearInEasing)) { height -> -height / 4 }
                        ))
                        .using(SizeTransform(clip = false))
                },
                contentAlignment = Alignment.Center,
                label = "primary_lyric_transition"
            ) { lyricText ->
                Text(
                    text = lyricText,
                    style = LyricBarTypography.activeLyricStyle(
                        fontOption = userPreferences.selectedFont,
                        fontSize = activeLyricFontSizeSp.sp,
                        color = Color.White
                    ),
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("now_playing_active_lyric_text")
                )
            }

            // Secondary supporting lyric or status line
            AnimatedContent(
                targetState = cardState.secondaryLine,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(200, easing = LinearOutSlowInEasing))
                        .togetherWith(fadeOut(animationSpec = tween(150, easing = FastOutLinearInEasing))))
                        .using(SizeTransform(clip = false))
                },
                contentAlignment = Alignment.Center,
                label = "secondary_lyric_transition"
            ) { secText ->
                if (!secText.isNullOrBlank()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = secText,
                            style = LyricBarTypography.contextualLyricStyle(
                                fontOption = userPreferences.selectedFont,
                                fontSize = 13.sp,
                                color = Color.White.copy(alpha = 0.68f)
                            ),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Subtle instrumental waveform indicator matching the reference design
            if (cardState.showInstrumentalWaveform) {
                Spacer(modifier = Modifier.height(if (isCompact) 6.dp else 8.dp))
                InstrumentalWaveformIndicator(primaryColor = primaryColor)
            }
        }
    }
}

/**
 * Subtle 11-bar blush-pink waveform indicator displayed during instrumental passages.
 * Static/state-driven with zero infinite animation loops for maximum battery and GPU efficiency.
 */
@Composable
private fun InstrumentalWaveformIndicator(primaryColor: Color) {
    val barHeightsDp = remember { listOf(4f, 4f, 7f, 10f, 6f, 14f, 8f, 11f, 6f, 4f, 4f) }
    val barAlphas = remember { listOf(0.32f, 0.42f, 0.65f, 0.85f, 0.72f, 0.95f, 0.78f, 0.88f, 0.62f, 0.42f, 0.32f) }

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(16.dp)
    ) {
        barHeightsDp.forEachIndexed { index, heightDp ->
            Box(
                modifier = Modifier
                    .width(3.5.dp)
                    .height(heightDp.dp)
                    .clip(RoundedCornerShape(50))
                    .background(primaryColor.copy(alpha = barAlphas[index]))
            )
        }
    }
}

@Composable
private fun NowPlayingProgressSection(
    currentPlaybackPosMs: Long,
    totalDurationMs: Long,
    hasTrackOrSession: Boolean,
    canSeek: Boolean,
    isDraggingSeek: Boolean,
    dragSeekFraction: Float,
    onDragChange: (Float) -> Unit,
    onDragFinished: () -> Unit,
    primaryColor: Color,
    hazeState: HazeState?,
    modifier: Modifier = Modifier
) {
    val currentFraction = when {
        isDraggingSeek -> dragSeekFraction.coerceIn(0f, 1f)
        totalDurationMs > 0L -> (currentPlaybackPosMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
        else -> 0f
    }

    val displayPosMs = if (isDraggingSeek && totalDurationMs > 0L) {
        (dragSeekFraction * totalDurationMs).toLong()
    } else if (hasTrackOrSession) {
        currentPlaybackPosMs.coerceAtLeast(0L)
    } else {
        0L
    }

    val leftTimeText = formatNowPlayingTimestamp(displayPosMs)
    val rightTimeText = if (totalDurationMs > 0L) {
        formatNowPlayingTimestamp(totalDurationMs)
    } else {
        "--:--"
    }

    FrostedCard(
        hazeState = hazeState,
        shape = RoundedCornerShape(20.dp),
        backgroundAlpha = 0.22f,
        blurRadius = 20.dp,
        borderWidth = 0.75.dp,
        borderAlpha = 0.18f,
        shadowElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("now_playing_progress_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = leftTimeText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.88f),
                modifier = Modifier
                    .widthIn(min = 34.dp)
                    .testTag("now_playing_position_text")
            )

            Spacer(modifier = Modifier.width(10.dp))

            NowPlayingSeekBar(
                progress = currentFraction,
                enabled = canSeek,
                onValueChange = onDragChange,
                onValueChangeFinished = onDragFinished,
                primaryColor = primaryColor,
                modifier = Modifier
                    .weight(1f)
                    .height(24.dp)
                    .testTag("now_playing_seek_bar")
            )

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = rightTimeText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.88f),
                textAlign = TextAlign.End,
                modifier = Modifier
                    .widthIn(min = 34.dp)
                    .testTag("now_playing_duration_text")
            )
        }
    }
}

@Composable
private fun NowPlayingSeekBar(
    progress: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    primaryColor: Color,
    modifier: Modifier = Modifier
) {
    val clampedProgress = progress.coerceIn(0f, 1f)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    val pointerModifier = if (enabled) {
        Modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val fraction = (offset.x / width).coerceIn(0f, 1f)
                    latestOnValueChange(fraction)
                    latestOnValueChangeFinished()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        latestOnValueChange((offset.x / width).coerceIn(0f, 1f))
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        latestOnValueChange((change.position.x / width).coerceIn(0f, 1f))
                    },
                    onDragEnd = {
                        latestOnValueChangeFinished()
                    },
                    onDragCancel = {
                        latestOnValueChangeFinished()
                    }
                )
            }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier.then(pointerModifier)
    ) {
        val trackStrokeWidth = 4.dp.toPx()
        val thumbRadius = 6.dp.toPx()
        val centerY = size.height / 2f
        val startX = thumbRadius
        val endX = (size.width - thumbRadius).coerceAtLeast(startX + 1f)
        val trackWidth = endX - startX
        val thumbX = startX + trackWidth * clampedProgress

        // Remaining muted track
        drawLine(
            color = Color.White.copy(alpha = 0.20f),
            start = Offset(startX, centerY),
            end = Offset(endX, centerY),
            strokeWidth = trackStrokeWidth,
            cap = StrokeCap.Round
        )

        // Active blush-pink progress fill
        if (clampedProgress > 0f) {
            drawLine(
                color = primaryColor,
                start = Offset(startX, centerY),
                end = Offset(thumbX, centerY),
                strokeWidth = trackStrokeWidth,
                cap = StrokeCap.Round
            )
        }

        // Circular white thumb
        drawCircle(
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.55f),
            radius = thumbRadius,
            center = Offset(thumbX, centerY)
        )
    }
}

@Composable
private fun NowPlayingControlsRow(
    hasSession: Boolean,
    hasTrack: Boolean,
    isPlaying: Boolean,
    transportCaps: TransportCapabilities,
    playButtonOuterDp: Float,
    playButtonInnerDp: Float,
    isNarrowWidth: Boolean,
    primaryColor: Color,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(
            space = if (isNarrowWidth) 22.dp else 28.dp,
            alignment = Alignment.CenterHorizontally
        ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Previous
        IconButton(
            onClick = onPrevious,
            enabled = transportCaps.canSkipPrevious,
            modifier = Modifier
                .size(48.dp)
                .testTag("now_playing_previous_button")
        ) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "Previous Track",
                tint = Color.White.copy(
                    alpha = if (transportCaps.canSkipPrevious) 0.95f else 0.38f
                ),
                modifier = Modifier.size(32.dp)
            )
        }

        // 2. Large Central Play/Pause Button with Subtle Outer Halo Ring
        val playScale by animateFloatAsState(
            targetValue = if (isPlaying) 1.0f else 0.97f,
            animationSpec = tween(durationMillis = 180),
            label = "play_button_scale"
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(playButtonOuterDp.dp)
                .graphicsLayer {
                    scaleX = playScale
                    scaleY = playScale
                }
        ) {
            // Subtle outer ring matching reference design
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.05f),
                border = BorderStroke(
                    width = 1.dp,
                    color = primaryColor.copy(alpha = if (isPlaying) 0.28f else 0.16f)
                ),
                modifier = Modifier.fillMaxSize()
            ) {}

            // Inner blush-pink primary action circle
            val canPlayPause = hasSession || hasTrack
            Surface(
                onClick = onPlayPause,
                enabled = canPlayPause,
                shape = CircleShape,
                color = if (canPlayPause) primaryColor else Color.White.copy(alpha = 0.16f),
                shadowElevation = if (isPlaying) 6.dp else 2.dp,
                modifier = Modifier
                    .size(playButtonInnerDp.dp)
                    .testTag("now_playing_play_pause_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = if (canPlayPause) {
                            Color(0xFF2B101C)
                        } else {
                            Color.White.copy(alpha = 0.45f)
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .graphicsLayer {
                                translationX = if (!isPlaying) 1.5.dp.toPx() else 0f
                            }
                    )
                }
            }
        }

        // 3. Next
        IconButton(
            onClick = onNext,
            enabled = transportCaps.canSkipNext,
            modifier = Modifier
                .size(48.dp)
                .testTag("now_playing_next_button")
        ) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "Next Track",
                tint = Color.White.copy(
                    alpha = if (transportCaps.canSkipNext) 0.95f else 0.38f
                ),
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
