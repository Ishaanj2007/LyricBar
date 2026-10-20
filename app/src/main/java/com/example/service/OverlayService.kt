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

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
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
        // Observe lyrics and preferences
        serviceScope.launch {
            combine(
                playbackManager.activeLyricObject,
                playbackManager.preferencesRepository.userPreferencesFlow
            ) { lineObj, prefs ->
                Pair(lineObj, prefs)
            }.collect { (lineObj, prefs) ->
                val view = overlayView ?: return@collect
                val params = layoutParams ?: return@collect

                if (!prefs.overlayEnabled) {
                    stopSelf()
                    return@collect
                }

                // Update text and word-level data
                view.activeLyricLine = lineObj
                view.textSizeSp = prefs.overlayTextSizeSp
                view.textOpacity = prefs.overlayTextOpacity
                view.typewriterSpeed = prefs.typewriterSpeed
                view.smoothTransitionSpeed = prefs.smoothTransitionSpeed
                view.fadeSpeed = prefs.fadeSpeed
                view.blurRevealSpeed = prefs.blurRevealSpeed
                view.textShadowEnabled = prefs.textShadowEnabled
                view.textAlignMode = prefs.overlayTextAlign
                view.animationStyle = prefs.animationStyle
                view.singleLineMode = prefs.singleLineLyrics
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
}
