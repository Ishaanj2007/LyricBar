package com.example.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
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

        // 1. Prefer actively playing session
        val playingController = controllers.firstOrNull { controller ->
            try {
                controller.playbackState?.state == PlaybackState.STATE_PLAYING
            } catch (_: Exception) {
                false
            }
        }

        if (playingController != null) {
            playbackManager.attachMediaController(playingController)
            return
        }

        // 2. Preserve currently attached session if it's still present in the list and not stopped/error
        val current = playbackManager.activeController
        if (current != null) {
            val stillPresent = controllers.firstOrNull {
                it.sessionToken == current.sessionToken
            }
            if (stillPresent != null) {
                val state = try {
                    stillPresent.playbackState?.state
                } catch (_: Exception) {
                    null
                }
                val isStoppedOrError = state == PlaybackState.STATE_STOPPED ||
                    state == PlaybackState.STATE_NONE ||
                    state == PlaybackState.STATE_ERROR
                if (!isStoppedOrError) {
                    playbackManager.attachMediaController(stillPresent)
                    return
                }
            }
        }

        // 3. Search for a valid paused / buffering session (preferring one with title/artist metadata)
        val pausedWithMetadata = controllers.firstOrNull { controller ->
            try {
                val state = controller.playbackState?.state
                (state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_BUFFERING) &&
                    hasValidMetadata(controller)
            } catch (_: Exception) {
                false
            }
        }

        if (pausedWithMetadata != null) {
            playbackManager.attachMediaController(pausedWithMetadata)
            return
        }

        val anyPaused = controllers.firstOrNull { controller ->
            try {
                val state = controller.playbackState?.state
                state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_BUFFERING
            } catch (_: Exception) {
                false
            }
        }

        if (anyPaused != null) {
            playbackManager.attachMediaController(anyPaused)
            return
        }

        // 4. Any controller with valid metadata that is not stopped/none/error
        val anyActiveWithMetadata = controllers.firstOrNull { controller ->
            try {
                val state = controller.playbackState?.state
                state != PlaybackState.STATE_STOPPED &&
                    state != PlaybackState.STATE_NONE &&
                    state != PlaybackState.STATE_ERROR &&
                    hasValidMetadata(controller)
            } catch (_: Exception) {
                false
            }
        }

        if (anyActiveWithMetadata != null) {
            playbackManager.attachMediaController(anyActiveWithMetadata)
        } else {
            // No valid active or paused session exists
            playbackManager.handleNoActiveSessions()
        }
    }

    private fun hasValidMetadata(controller: MediaController): Boolean {
        return try {
            val metadata = controller.metadata ?: return false
            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            !title.isNullOrBlank()
        } catch (_: Exception) {
            false
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
