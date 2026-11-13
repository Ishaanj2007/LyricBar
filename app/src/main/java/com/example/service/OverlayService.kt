package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.ui.graphics.toArgb
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.example.MainActivity
import com.example.R
import com.example.data.preferences.ThemePreset
import com.example.ui.components.MinimalOverlayView
import com.example.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

class OverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var windowManager: WindowManager? = null
    private var overlayView: MinimalOverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private lateinit var playbackManager: MediaPlaybackManager

    private var displayManager: DisplayManager? = null
    private lateinit var displayGeometryFlow: MutableStateFlow<HudDisplayGeometry>

    private var currentX = 0
    private var currentY = 180
    private var isDragMode = false
    private var hasAppliedInitialPrefs = false
    private var isInitializingLandscapeProfile = false
    private var currentPrefs = com.example.data.preferences.UserPreferences()

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            refreshDisplayGeometry()
        }
    }

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> overlayView?.setScreenOn(false)
                Intent.ACTION_SCREEN_ON -> overlayView?.setScreenOn(true)
            }
        }
    }

    private fun readCurrentDisplayGeometry(
        insets: android.view.WindowInsets? = overlayView?.rootWindowInsets
    ): HudDisplayGeometry {
        return HudDisplayMetricsProvider.getOverlayDisplayGeometry(
            context = this,
            windowManager = windowManager,
            rootWindowInsets = insets
        )
    }

    private fun refreshDisplayGeometry(insets: android.view.WindowInsets? = overlayView?.rootWindowInsets) {
        if (!::displayGeometryFlow.isInitialized) return
        val newGeometry = readCurrentDisplayGeometry(insets)
        val prevGeometry = displayGeometryFlow.value
        if (prevGeometry != newGeometry) {
            if (prevGeometry.orientation != newGeometry.orientation) {
                overlayView?.cancelActiveDrag()
            }
            displayGeometryFlow.value = newGeometry
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshDisplayGeometry()
    }

    override fun onCreate() {
        super.onCreate()
        playbackManager = MediaPlaybackManager.getInstance(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        displayGeometryFlow = MutableStateFlow(readCurrentDisplayGeometry(null))

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("LyricBar Overlay Active"))

        val screenFilter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, screenFilter)
        displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

        if (Settings.canDrawOverlays(this)) {
            setupOverlayWindow()
            observeState()
        } else {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_DRAG_MODE -> {
                isDragMode = !isDragMode
                serviceScope.launch {
                    playbackManager.preferencesRepository.setDragRepositionMode(isDragMode)
                }
                updateOverlayFlags()
                updateNotification()
                updateOverlayVisibility(playbackManager.playbackState.value, true)
            }
            ACTION_STOP_OVERLAY -> {
                serviceScope.launch {
                    playbackManager.preferencesRepository.setOverlayEnabled(false)
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun setupOverlayWindow() {
        val wm = windowManager ?: return
        val initialGeometry = displayGeometryFlow.value
        val placeholderBounds = HudPositioningEngine.defaultPlaceholderForGeometry(initialGeometry)
        currentX = placeholderBounds.x
        currentY = placeholderBounds.y

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        val params = WindowManager.LayoutParams(
            placeholderBounds.widthPx,
            placeholderBounds.heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = currentX
            y = currentY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        layoutParams = params

        val view = MinimalOverlayView(
            context = this,
            windowManager = wm,
            layoutParams = params,
            onBoundsChanged = { dragOrientation, newX, newY, newW, newH ->
                currentX = newX
                currentY = newY
                serviceScope.launch {
                    playbackManager.preferencesRepository.setOverlayBounds(
                        x = newX,
                        y = newY,
                        widthPx = newW,
                        heightPx = newH,
                        orientation = dragOrientation
                    )
                }
            },
            onResyncRequested = {
                playbackManager.resyncLyrics(forceRefresh = false)
            }
        ).apply {
            activeOrientation = initialGeometry.orientation
            displayGeometry = initialGeometry
            setOnApplyWindowInsetsListener { _, insets ->
                refreshDisplayGeometry(insets)
                insets
            }
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.requestApplyInsets()
                    refreshDisplayGeometry(v.rootWindowInsets)
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
        overlayView = view

        // Keep initial window hidden until persisted portrait/landscape preferences are applied
        view.visibility = View.GONE
        view.alpha = 0.0f
        view.scaleX = 0.97f
        view.scaleY = 0.97f
        lastTargetVisible = null

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private var visibilityAnimator: ValueAnimator? = null
    private var lastTargetVisible: Boolean? = null
    private var isFadingIn = false
    private var isFadingOut = false

    private fun cancelVisibilityAnimation() {
        visibilityAnimator?.cancel()
        visibilityAnimator = null
        isFadingIn = false
        isFadingOut = false
    }

    private fun updateOverlayVisibility(playbackState: com.example.data.model.PlaybackStateInfo, overlayEnabled: Boolean) {
        val view = overlayView ?: return
        if (!overlayEnabled) {
            cancelVisibilityAnimation()
            view.cancelActiveDrag()
            view.visibility = android.view.View.GONE
            view.alpha = 0f
            view.scaleX = 0.97f
            view.scaleY = 0.97f
            view.stopLoop()
            lastTargetVisible = false
            return
        }

        val shouldShow = isDragMode || (playbackState.isPlaying && playbackState.hasActiveSession && playbackState.track != null)

        if (shouldShow) {
            if (lastTargetVisible == true && view.visibility == android.view.View.VISIBLE && !isFadingOut && view.alpha >= 1f) {
                return
            }
            lastTargetVisible = true

            // Cancel any exit animation safely
            if (isFadingOut) {
                visibilityAnimator?.cancel()
                isFadingOut = false
            } else if (isFadingIn) {
                return // already fading in
            }

            val startAlpha = if (view.visibility == android.view.View.VISIBLE) view.alpha.coerceIn(0f, 1f) else 0f
            val startScale = if (view.visibility == android.view.View.VISIBLE) view.scaleX.coerceIn(0.97f, 1.0f) else 0.97f

            view.visibility = android.view.View.VISIBLE
            val pivotW = if (view.width > 0) view.width else (layoutParams?.width ?: 0)
            val pivotH = if (view.height > 0) view.height else (layoutParams?.height ?: 0)
            view.pivotX = pivotW / 2f
            view.pivotY = pivotH / 2f
            view.alpha = startAlpha
            view.scaleX = startScale
            view.scaleY = startScale

            val remaining = (1f - startAlpha).coerceIn(0f, 1f)
            val animDuration = kotlin.math.round(FADE_IN_DURATION_MS.toDouble() * remaining.toDouble()).toLong().coerceIn(30L, FADE_IN_DURATION_MS)

            isFadingIn = true
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = animDuration
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { va ->
                    val frac = va.animatedFraction
                    val currentA = startAlpha + (1.0f - startAlpha) * frac
                    val currentS = startScale + (1.0f - startScale) * frac
                    view.alpha = currentA
                    view.scaleX = currentS
                    view.scaleY = currentS
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (isFadingIn) {
                            view.alpha = 1.0f
                            view.scaleX = 1.0f
                            view.scaleY = 1.0f
                            isFadingIn = false
                        }
                    }
                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        isFadingIn = false
                    }
                })
            }
            visibilityAnimator = anim
            anim.start()
        } else {
            if (lastTargetVisible == false && view.visibility == android.view.View.GONE && !isFadingIn) {
                view.stopLoop()
                return
            }
            lastTargetVisible = false

            // Ensure lyric scrolling, active drag, and playback-dependent animations stop immediately when playback is inactive
            view.cancelActiveDrag()
            view.stopLoop()

            // Cancel any entrance animation safely
            if (isFadingIn) {
                visibilityAnimator?.cancel()
                isFadingIn = false
            } else if (isFadingOut) {
                return // already fading out
            }

            if (view.visibility != android.view.View.VISIBLE || view.alpha <= 0.01f) {
                view.visibility = android.view.View.GONE
                view.alpha = 0f
                view.scaleX = 0.97f
                view.scaleY = 0.97f
                return
            }

            val startAlpha = view.alpha.coerceIn(0f, 1f)
            val startScale = view.scaleX.coerceIn(0.97f, 1.0f)

            val pivotW = if (view.width > 0) view.width else (layoutParams?.width ?: 0)
            val pivotH = if (view.height > 0) view.height else (layoutParams?.height ?: 0)
            view.pivotX = pivotW / 2f
            view.pivotY = pivotH / 2f

            val animDuration = kotlin.math.round(FADE_OUT_DURATION_MS.toDouble() * startAlpha.toDouble()).toLong().coerceIn(30L, FADE_OUT_DURATION_MS)

            isFadingOut = true
            val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = animDuration
                interpolator = DecelerateInterpolator(1.5f)
                addUpdateListener { va ->
                    val frac = va.animatedFraction
                    val currentA = startAlpha + (0.0f - startAlpha) * frac
                    val currentS = startScale + (0.97f - startScale) * frac
                    view.alpha = currentA
                    view.scaleX = currentS
                    view.scaleY = currentS
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (isFadingOut) {
                            view.visibility = android.view.View.GONE
                            view.alpha = 0f
                            view.scaleX = 0.97f
                            view.scaleY = 0.97f
                            view.stopLoop()
                            isFadingOut = false
                        }
                    }
                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        isFadingOut = false
                    }
                })
            }
            visibilityAnimator = anim
            anim.start()
        }
    }

    private fun updateOverlayFlags() {
        val wm = windowManager ?: return
        val view = overlayView ?: return
        val params = layoutParams ?: return

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        params.flags = flags
        view.isDragMode = isDragMode

        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {}
    }

    private fun observeState() {
        // Observe lyrics, playback state, preferences, and display geometry
        serviceScope.launch {
            var lastAppliedLine: com.example.data.model.LyricLine? = null
            var lastAppliedLyricsKey: String? = null
            var lastAppliedTrackKey: String? = null
            var lastAppliedPrefs: com.example.data.preferences.UserPreferences? = null
            var lastAppliedGeometry: HudDisplayGeometry? = null

            combine(
                playbackManager.activeLyricObject,
                playbackManager.currentLyrics,
                playbackManager.playbackState,
                playbackManager.preferencesRepository.userPreferencesFlow,
                displayGeometryFlow
            ) { lineObj, lyrics, playbackState, prefs, geometry ->
                OverlayStateBundle(lineObj, lyrics, playbackState, prefs, geometry)
            }.collect { bundle ->
                val view = overlayView ?: return@collect
                val params = layoutParams ?: return@collect
                val prefs = bundle.prefs
                val lineObj = bundle.lineObj
                val lyrics = bundle.lyrics
                val playbackState = bundle.playbackState
                val geometry = bundle.geometry

                currentPrefs = prefs
                if (!prefs.overlayEnabled) {
                    stopSelf()
                    return@collect
                }

                // Verify runtime overlay permission; stop gracefully if revoked
                if (!Settings.canDrawOverlays(this@OverlayService)) {
                    stopSelf()
                    return@collect
                }

                // 1. Update active lyric line and next timestamp ONLY when the line or lyrics change
                val currentLyricsKey = lyrics?.let { "${it.title}_${it.lines.size}" }
                if (lineObj != lastAppliedLine || currentLyricsKey != lastAppliedLyricsKey) {
                    lastAppliedLine = lineObj
                    lastAppliedLyricsKey = currentLyricsKey

                    val nextTimestampMs = if (lineObj != null && lyrics != null) {
                        lyrics.lines.firstOrNull { it.timeMs > lineObj.timeMs }?.timeMs
                    } else null

                    view.activeLyricLine = lineObj
                    view.nextLyricTimestampMs = nextTimestampMs
                }

                // 2. Update track duration ONLY when track changes
                val trackKey = playbackState.track?.key
                if (trackKey != lastAppliedTrackKey) {
                    lastAppliedTrackKey = trackKey
                    val trackDurationSec = playbackState.track?.durationSec ?: 0
                    view.trackDurationMs = if (trackDurationSec > 0) trackDurationSec * 1000L else null
                }

                // 3. Update styling properties, primary color, and orientation-aware bounds when preferences or display geometry change
                val prefsChanged = prefs != lastAppliedPrefs
                val geometryChanged = geometry != lastAppliedGeometry
                if (prefsChanged || geometryChanged || !hasAppliedInitialPrefs) {
                    if (prefsChanged) {
                        lastAppliedPrefs = prefs

                        view.textSizeSp = prefs.overlayTextSizeSp
                        view.textOpacity = prefs.overlayTextOpacity
                        view.hudBackdropEnabled = prefs.hudBackdropEnabled
                        view.hudBackdropStyle = prefs.hudBackdropStyle.name
                        view.hudBackdropOpacity = prefs.hudBackdropOpacity
                        view.hudBackdropCornerRadiusDp = prefs.hudBackdropCornerRadiusDp.toFloat()
                        view.hudBackdropHorizontalPaddingDp = prefs.hudBackdropHorizontalPaddingDp.toFloat()
                        view.hudBackdropVerticalPaddingDp = prefs.hudBackdropVerticalPaddingDp.toFloat()
                        view.typewriterSpeed = prefs.typewriterSpeed
                        view.smoothTransitionSpeed = prefs.smoothTransitionSpeed
                        view.fadeSpeed = prefs.fadeSpeed
                        view.blurRevealSpeed = prefs.blurRevealSpeed
                        view.karaokeSpeed = prefs.karaokeSpeed
                        view.karaokeGlowSpeed = prefs.karaokeGlowSpeed
                        view.textShadowEnabled = prefs.textShadowEnabled
                        view.textAlignMode = prefs.overlayTextAlign
                        view.animationStyle = prefs.animationStyle
                        view.singleLineMode = prefs.singleLineLyrics
                        view.adaptiveScrollingEnabled = prefs.adaptiveLyricScrolling
                        view.scrollSpeedMultiplier = prefs.lyricScrollSpeedMultiplier
                        view.fontResId = prefs.selectedFont.fontResId

                        val resolvedPrimary = if (prefs.useWallpaperDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            try {
                                dynamicDarkColorScheme(this@OverlayService).primary.toArgb()
                            } catch (_: Exception) {
                                android.graphics.Color.WHITE
                            }
                        } else {
                            when (prefs.themePreset) {
                                ThemePreset.ROSE_QUARTZ, ThemePreset.OBSIDIAN_MINT -> RoseQuartzPrimary.toArgb()
                                ThemePreset.LAVENDER_MIST, ThemePreset.MIDNIGHT_AZURE -> LavenderMistPrimary.toArgb()
                                ThemePreset.PEACH_EMBER, ThemePreset.SUNSET_AMBER -> PeachEmberPrimary.toArgb()
                                ThemePreset.SAGE_SERENITY, ThemePreset.CYBER_VIOLET -> SageSerenityPrimary.toArgb()
                            }
                        }
                        view.primaryColor = resolvedPrimary

                        // Update drag mode if changed externally from settings
                        if (isDragMode != prefs.isDragRepositionMode) {
                            isDragMode = prefs.isDragRepositionMode
                            updateOverlayFlags()
                            updateNotification()
                        }
                    }

                    lastAppliedGeometry = geometry
                    val minBackdropHeightPx = HudPositioningEngine.computeMinBackdropHeightPx(
                        prefs = prefs,
                        displayMetrics = resources.displayMetrics
                    )

                    if (geometry.orientation == HudOrientation.LANDSCAPE &&
                        !prefs.hasLandscapeProfile &&
                        !isInitializingLandscapeProfile
                    ) {
                        isInitializingLandscapeProfile = true
                        val defaultLandscape = HudPositioningEngine.createDefaultLandscapeProfile(
                            geometry = geometry,
                            minBackdropHeightPx = minBackdropHeightPx
                        )
                        serviceScope.launch {
                            try {
                                playbackManager.preferencesRepository.ensureLandscapeProfile(defaultLandscape)
                            } finally {
                                isInitializingLandscapeProfile = false
                            }
                        }
                    }

                    val activeProfile = prefs.resolveProfile(
                        orientation = geometry.orientation,
                        displayGeometry = geometry,
                        minBackdropHeightPx = minBackdropHeightPx
                    )
                    val resolvedBounds = HudPositioningEngine.resolveBounds(
                        profile = activeProfile,
                        geometry = geometry,
                        minBackdropHeightPx = minBackdropHeightPx
                    )

                    view.activeOrientation = geometry.orientation
                    view.displayGeometry = geometry
                    view.minBackdropHeightPx = minBackdropHeightPx

                    var layoutChanged = false
                    if (params.x != resolvedBounds.x || params.y != resolvedBounds.y) {
                        params.x = resolvedBounds.x
                        params.y = resolvedBounds.y
                        currentX = resolvedBounds.x
                        currentY = resolvedBounds.y
                        layoutChanged = true
                    }
                    if (resolvedBounds.widthPx > 0 && params.width != resolvedBounds.widthPx) {
                        params.width = resolvedBounds.widthPx
                        layoutChanged = true
                    }
                    if (resolvedBounds.effectiveHeightPx > 0 && params.height != resolvedBounds.effectiveHeightPx) {
                        params.height = resolvedBounds.effectiveHeightPx
                        layoutChanged = true
                    }

                    if (layoutChanged) {
                        try {
                            windowManager?.updateViewLayout(view, params)
                        } catch (_: Exception) {}
                    }
                }

                hasAppliedInitialPrefs = true
                updateOverlayVisibility(playbackState, prefs.overlayEnabled)
            }
        }

        // Observe playback position and speed for real-time word highlight sync
        serviceScope.launch {
            playbackManager.playbackState.collect { state ->
                val view = overlayView ?: return@collect
                if (hasAppliedInitialPrefs) {
                    updateOverlayVisibility(state, currentPrefs.overlayEnabled)
                }
                view.updatePlaybackState(
                    playing = state.isPlaying,
                    positionMs = state.positionMs,
                    speed = state.playbackSpeed,
                    updatedTimestamp = state.lastUpdatedMs
                )
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.lyrics_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.lyrics_channel_description)
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleDragIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_TOGGLE_DRAG_MODE
        }
        val toggleDragPendingIntent = PendingIntent.getService(
            this, 1, toggleDragIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP_OVERLAY
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dragTitle = if (isDragMode) "Save Position (Lock)" else getString(R.string.notification_reposition)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppPendingIntent)
            .addAction(0, dragTitle, toggleDragPendingIntent)
            .addAction(0, getString(R.string.notification_stop), stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val status = if (isDragMode) "Repositioning active — drag lyrics on screen" else "Minimal Floating Lyrics active"
        notificationManager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelVisibilityAnimation()
        serviceScope.cancel()
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        overlayView?.let {
            try {
                it.cancelActiveDrag()
                it.stopLoop()
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        overlayView = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "lyricbar_overlay_channel"
        const val NOTIFICATION_ID = 1001

        const val FADE_IN_DURATION_MS = 1000L
        const val FADE_OUT_DURATION_MS = 1000L

        const val ACTION_TOGGLE_DRAG_MODE = "com.example.lyricbar.action.TOGGLE_DRAG_MODE"
        const val ACTION_STOP_OVERLAY = "com.example.lyricbar.action.STOP_OVERLAY"

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            context.stopService(intent)
        }
    }

    private data class OverlayStateBundle(
        val lineObj: com.example.data.model.LyricLine?,
        val lyrics: com.example.data.model.ParsedLyrics?,
        val playbackState: com.example.data.model.PlaybackStateInfo,
        val prefs: com.example.data.preferences.UserPreferences,
        val geometry: HudDisplayGeometry
    )
}
