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
import android.view.animation.DecelerateInterpolator
import com.example.MainActivity
import com.example.R
import com.example.data.preferences.ThemePreset
import com.example.ui.components.MinimalOverlayView
import com.example.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

class OverlayService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var windowManager: WindowManager? = null
    private var overlayView: MinimalOverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private lateinit var playbackManager: MediaPlaybackManager

    private var currentX = 0
    private var currentY = 140
    private var isDragMode = false
    private var currentPrefs = com.example.data.preferences.UserPreferences()

    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> overlayView?.setScreenOn(false)
                Intent.ACTION_SCREEN_ON -> overlayView?.setScreenOn(true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        playbackManager = MediaPlaybackManager.getInstance(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("LyricBar Overlay Active"))

        val screenFilter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, screenFilter)

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

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (isDragMode) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

        val params = WindowManager.LayoutParams(
            850,
            130,
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
            onBoundsChanged = { newX, newY, newW, newH ->
                currentX = newX
                currentY = newY
                serviceScope.launch {
                    playbackManager.preferencesRepository.setOverlayBounds(newX, newY, newW, newH)
                }
            },
            onResyncRequested = {
                playbackManager.resyncLyrics(forceRefresh = false)
            }
        )
        overlayView = view

        val currentPb = playbackManager.playbackState.value
        val shouldInitiallyShow = isDragMode || (currentPb.isPlaying && currentPb.hasActiveSession && currentPb.track != null)
        view.visibility = if (shouldInitiallyShow) android.view.View.VISIBLE else android.view.View.GONE
        view.alpha = if (shouldInitiallyShow) 1.0f else 0.0f
        view.scaleX = if (shouldInitiallyShow) 1.0f else 0.97f
        view.scaleY = if (shouldInitiallyShow) 1.0f else 0.97f
        lastTargetVisible = shouldInitiallyShow

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

            // Ensure lyric scrolling and playback-dependent animations stop immediately when playback is inactive
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

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL

        if (!isDragMode) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        params.flags = flags
        view.isDragMode = isDragMode

        try {
            wm.updateViewLayout(view, params)
        } catch (_: Exception) {}
    }

    private fun observeState() {
        // Observe lyrics, playback state, and preferences
        serviceScope.launch {
            combine(
                playbackManager.activeLyricObject,
                playbackManager.currentLyrics,
                playbackManager.playbackState,
                playbackManager.preferencesRepository.userPreferencesFlow
            ) { lineObj, lyrics, playbackState, prefs ->
                OverlayStateBundle(lineObj, lyrics, playbackState, prefs)
            }.collect { bundle ->
                val view = overlayView ?: return@collect
                val params = layoutParams ?: return@collect
                val prefs = bundle.prefs
                val lineObj = bundle.lineObj
                val lyrics = bundle.lyrics
                val playbackState = bundle.playbackState

                currentPrefs = prefs
                if (!prefs.overlayEnabled) {
                    stopSelf()
                    return@collect
                }

                updateOverlayVisibility(playbackState, prefs.overlayEnabled)

                // Compute next valid lyric timestamp after the current line
                val nextTimestampMs = if (lineObj != null && lyrics != null) {
                    lyrics.lines.firstOrNull { it.timeMs > lineObj.timeMs }?.timeMs
                } else null

                val trackDurationSec = playbackState.track?.durationSec ?: 0
                val trackDurationMs = if (trackDurationSec > 0) trackDurationSec * 1000L else null

                // Update text and word-level data
                view.activeLyricLine = lineObj
                view.nextLyricTimestampMs = nextTimestampMs
                view.trackDurationMs = trackDurationMs
                view.textSizeSp = prefs.overlayTextSizeSp
                view.textOpacity = prefs.overlayTextOpacity
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
                        ThemePreset.OBSIDIAN_MINT -> MintAccent.toArgb()
                        ThemePreset.MIDNIGHT_AZURE -> AzurePrimary.toArgb()
                        ThemePreset.SUNSET_AMBER -> AmberPrimary.toArgb()
                        ThemePreset.CYBER_VIOLET -> VioletPrimary.toArgb()
                    }
                }
                view.primaryColor = resolvedPrimary

                // Update drag mode if changed externally from settings
                if (isDragMode != prefs.isDragRepositionMode) {
                    isDragMode = prefs.isDragRepositionMode
                    updateOverlayFlags()
                    updateNotification()
                    updateOverlayVisibility(playbackState, prefs.overlayEnabled)
                }

                // Update bounds coordinates & size
                var layoutChanged = false
                if (params.y != prefs.overlayYPosition || params.x != prefs.overlayXPosition) {
                    params.x = prefs.overlayXPosition
                    params.y = prefs.overlayYPosition
                    currentX = prefs.overlayXPosition
                    currentY = prefs.overlayYPosition
                    layoutChanged = true
                }
                if (prefs.overlayWidthPx > 0 && params.width != prefs.overlayWidthPx) {
                    params.width = prefs.overlayWidthPx
                    layoutChanged = true
                }
                if (prefs.overlayHeightPx > 0 && params.height != prefs.overlayHeightPx) {
                    params.height = prefs.overlayHeightPx
                    layoutChanged = true
                }

                if (layoutChanged) {
                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (_: Exception) {}
                }
            }
        }

        // Observe playback position and speed for real-time word highlight sync
        serviceScope.launch {
            playbackManager.playbackState.collect { state ->
                val view = overlayView ?: return@collect
                updateOverlayVisibility(state, currentPrefs.overlayEnabled)
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
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {}
        overlayView?.let {
            try {
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

        const val ACTION_TOGGLE_DRAG_MODE = "com.aistudio.lyricbar.action.TOGGLE_DRAG_MODE"
        const val ACTION_STOP_OVERLAY = "com.aistudio.lyricbar.action.STOP_OVERLAY"

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
        val prefs: com.example.data.preferences.UserPreferences
    )
}
