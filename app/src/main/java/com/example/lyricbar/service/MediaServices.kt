package com.example.lyricbar.service

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import com.example.lyricbar.data.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MediaPlaybackManager {
    private val _currentTrack = MutableStateFlow(Track())
    val currentTrack: StateFlow<Track> = _currentTrack.asStateFlow()

    fun updateTrack(track: Track) {
        _currentTrack.value = track
    }

    fun updatePosition(positionMs: Long) {
        _currentTrack.value = _currentTrack.value.copy(currentPositionMs = positionMs)
    }
}

class MediaListenerService : NotificationListenerService() {

    private var mediaSessionManager: MediaSessionManager? = null
    private var activeControllers: List<MediaController> = emptyList()

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateFromMetadata(metadata, activeControllers.firstOrNull()?.playbackState)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateFromMetadata(activeControllers.firstOrNull()?.metadata, state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaSessionManager = getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        tryRegisterMediaSessions()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        tryRegisterMediaSessions()
    }

    private fun tryRegisterMediaSessions() {
        try {
            val componentName = ComponentName(this, MediaListenerService::class.java)
            val controllers = mediaSessionManager?.getActiveSessions(componentName) ?: emptyList()
            activeControllers = controllers

            if (controllers.isNotEmpty()) {
                val primaryController = controllers.first()
                primaryController.registerCallback(controllerCallback)
                updateFromMetadata(primaryController.metadata, primaryController.playbackState)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateFromMetadata(metadata: MediaMetadata?, state: PlaybackState?) {
        if (metadata == null) return

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val position = state?.position ?: 0L

        if (title.isNotBlank()) {
            MediaPlaybackManager.updateTrack(
                Track(
                    title = title,
                    artist = artist,
                    album = album,
                    durationMs = duration,
                    currentPositionMs = position,
                    isPlaying = isPlaying
                )
            )
        }
    }
}
