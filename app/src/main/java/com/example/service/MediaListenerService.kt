package com.example.service

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
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
        playbackManager.onSessionDestroyedListener = {
            refreshActiveSessions()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            val componentName = ComponentName(this, MediaListenerService::class.java)
            sessionManager?.addOnActiveSessionsChangedListener(sessionListener, componentName)
            val controllers = sessionManager?.getActiveSessions(componentName)
            findAndAttachActiveController(controllers)
        } catch (e: Exception) {
            Log.w("MediaListenerService", "Exception requesting active sessions: ${e.message}")
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
        playbackManager.handleNoActiveSessions()
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
        if (controllers.isNullOrEmpty()) {
            playbackManager.handleNoActiveSessions()
            return
        }

        val pm = packageManager
        val candidates = controllers.map { controller ->
            val token = try {
                controller.sessionToken
            } catch (_: Exception) {
                null
            }
            val snapshot = MediaSessionEligibilityEvaluator.fromMediaController(controller, pm)
            MediaControllerCandidate(
                token = token,
                snapshot = snapshot,
                ref = controller
            )
        }

        val currentToken = try {
            playbackManager.activeController?.sessionToken
        } catch (_: Exception) {
            null
        }

        val selection = MediaSessionSelector.selectBestController(
            candidates = candidates,
            currentToken = currentToken
        )

        val chosen = selection.selected?.ref
        if (chosen != null) {
            playbackManager.attachMediaController(chosen)
        } else {
            playbackManager.handleNoActiveSessions()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playbackManager.onSessionDestroyedListener = null
        try {
            sessionManager?.removeOnActiveSessionsChangedListener(sessionListener)
        } catch (_: Exception) {}
        playbackManager.handleNoActiveSessions()
    }
}
