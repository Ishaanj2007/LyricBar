package com.example.service

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class MediaListenerService : NotificationListenerService() {

    private var sessionManager: MediaSessionManager? = null
    private lateinit var playbackManager: MediaPlaybackManager

    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        findAndAttachActiveController(controllers)
    }

    override fun onCreate() {
        super.onCreate()
        playbackManager = MediaPlaybackManager.getInstance(this)
        sessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            val componentName = ComponentName(this, MediaListenerService::class.java)
            sessionManager?.addOnActiveSessionsChangedListener(sessionListener, componentName)
            val controllers = sessionManager?.getActiveSessions(componentName)
            findAndAttachActiveController(controllers)
        } catch (e: SecurityException) {
            Log.w("MediaListenerService", "SecurityException requesting active sessions: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        // Check if an active media session started with this notification
        refreshActiveSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        refreshActiveSessions()
    }

    private fun refreshActiveSessions() {
        try {
            val componentName = ComponentName(this, MediaListenerService::class.java)
            val controllers = sessionManager?.getActiveSessions(componentName)
            findAndAttachActiveController(controllers)
        } catch (_: Exception) {}
    }

    private fun findAndAttachActiveController(controllers: List<MediaController>?) {
        if (controllers.isNullOrEmpty()) return

        // Prefer playing session
        val playingController = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull()

        playbackManager.attachMediaController(playingController)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
    }
}
