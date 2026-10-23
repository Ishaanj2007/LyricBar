package com.example.lyricbar.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.lyricbar.R
import com.example.lyricbar.data.model.LyricLine
import com.example.lyricbar.data.preferences.UserPreferencesRepository
import com.example.lyricbar.domain.lyricsengine.LyricsTimingEngine
import com.example.lyricbar.ui.theme.LyricBarTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class OverlayService : Service(), SavedStateRegistryOwner, ViewModelStoreOwner {

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var windowLayoutParams: WindowManager.LayoutParams? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val lifecycle = androidx.lifecycle.LifecycleRegistry(this)

    companion object {
        const val CHANNEL_ID = "lyricbar_overlay_channel"
        const val NOTIFICATION_ID = 1001

        var isServiceRunning = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycle.currentState = Lifecycle.State.STARTED

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        isServiceRunning = true

        setupOverlayWindow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isServiceRunning = false
        lifecycle.currentState = Lifecycle.State.DESTROYED
        if (overlayView != null && windowManager != null) {
            windowManager?.removeView(overlayView)
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LyricBar Floating Overlay",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LyricBar Overlay Active")
            .setContentText("Displaying synchronized floating lyrics over music.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    private fun setupOverlayWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val prefsRepo = UserPreferencesRepository(this)

        windowLayoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 150
        }

        overlayView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeViewModelStoreOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)

            setContent {
                LyricBarTheme {
                    val prefs by prefsRepo.userPreferencesFlow.collectAsState(initial = com.example.lyricbar.data.preferences.UserPreferences())
                    val track by MediaPlaybackManager.currentTrack.collectAsState()

                    var activeLyric by remember { mutableStateOf("🎵 LyricBar - Play a song...") }

                    LaunchedEffect(track) {
                        if (track.title.isNotBlank()) {
                            activeLyric = "${track.title} • ${track.artist}"
                        }
                    }

                    Box(
                        modifier = Modifier
                            .padding(16.dp)
                            .alpha(prefs.opacity)
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(24.dp)
                            )
                            .pointerInput(Unit) {
                                detectDragGestures(
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        val p = windowLayoutParams ?: return@detectDragGestures
                                        val (dx, dy) = dragAmount
                                        p.x += dx.toInt()
                                        p.y += dy.toInt()
                                        windowManager?.updateViewLayout(overlayView, p)
                                    }
                                )
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = activeLyric,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "LyricAnimation"
                        ) { currentText ->
                            Text(
                                text = currentText,
                                fontSize = prefs.fontSize.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                fontFamily = when (prefs.fontIndex) {
                                    1 -> FontFamily.Serif
                                    2 -> FontFamily.Monospace
                                    3 -> FontFamily.Cursive
                                    else -> FontFamily.Default
                                }
                            )
                        }
                    }
                }
            }
        }

        try {
            windowManager?.addView(overlayView, windowLayoutParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
