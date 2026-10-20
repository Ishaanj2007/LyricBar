package com.example.service

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import com.example.data.model.ParsedLyrics
import com.example.data.model.PlaybackStateInfo
import com.example.data.model.TrackMetadata
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.CompositeLyricsRepository
import com.example.domain.parser.LrcParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaPlaybackManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val lyricsRepository = CompositeLyricsRepository.getInstance(context)
    val preferencesRepository = UserPreferencesRepository(context)

    private val _playbackState = MutableStateFlow(PlaybackStateInfo())
    val playbackState: StateFlow<PlaybackStateInfo> = _playbackState.asStateFlow()

    private val _currentLyrics = MutableStateFlow<ParsedLyrics?>(null)
    val currentLyrics: StateFlow<ParsedLyrics?> = _currentLyrics.asStateFlow()

    private val _activeLyricLine = MutableStateFlow<String?>(null)
    val activeLyricLine: StateFlow<String?> = _activeLyricLine.asStateFlow()

    private val _activeLyricObject = MutableStateFlow<com.example.data.model.LyricLine?>(null)
    val activeLyricObject: StateFlow<com.example.data.model.LyricLine?> = _activeLyricObject.asStateFlow()

    private val _trackOffsetMs = MutableStateFlow(0L)
    val trackOffsetMs: StateFlow<Long> = _trackOffsetMs.asStateFlow()

    private val _isLoadingLyrics = MutableStateFlow(false)
    val isLoadingLyrics: StateFlow<Boolean> = _isLoadingLyrics.asStateFlow()

    private var activeController: MediaController? = null
    private var fetchJob: Job? = null
    private var tickerJob: Job? = null

    // Controller callback
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateFromMetadata(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateFromPlaybackState(state)
        }
    }

    init {
        startLyricsTicker()
    }

    fun attachMediaController(controller: MediaController?) {
        if (activeController?.sessionToken == controller?.sessionToken) return

        activeController?.unregisterCallback(controllerCallback)
        activeController = controller

        if (controller != null) {
            controller.registerCallback(controllerCallback)
            updateFromMetadata(controller.metadata)
            updateFromPlaybackState(controller.playbackState)
        }
    }

    private fun updateFromMetadata(metadata: MediaMetadata?) {
        if (metadata == null) return

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return

        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR)
            ?: "Unknown Artist"

        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
        val durationSec = if (durationMs > 0) (durationMs / 1000).toInt() else 0
        val artUri = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)

        val track = TrackMetadata(
            title = title,
            artist = artist,
            album = album,
            durationSec = durationSec,
            sourcePackage = activeController?.packageName ?: "",
            albumArtUri = artUri
        )

        val currentTrack = _playbackState.value.track
        if (currentTrack?.key != track.key) {
            _playbackState.value = _playbackState.value.copy(track = track)
            onTrackChanged(track)
        }
    }

    private fun updateFromPlaybackState(state: PlaybackState?) {
        if (state == null) return
        val isPlaying = state.state == PlaybackState.STATE_PLAYING
        val position = state.position
        val speed = state.playbackSpeed

        _playbackState.value = _playbackState.value.copy(
            isPlaying = isPlaying,
            positionMs = position,
            playbackSpeed = if (speed > 0f) speed else 1.0f,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    private var offsetJob: Job? = null

    private fun onTrackChanged(track: TrackMetadata) {
        // Cancel any pending stale network requests and offset collectors
        fetchJob?.cancel()
        offsetJob?.cancel()

        _currentLyrics.value = null
        _activeLyricLine.value = null
        _isLoadingLyrics.value = true

        // Load persisted offset for this specific track
        offsetJob = scope.launch {
            preferencesRepository.getTrackOffsetFlow(track.key).collect { offset ->
                _trackOffsetMs.value = offset
            }
        }

        fetchJob = scope.launch {
            val result = lyricsRepository.getOrFetchLyrics(track)
            _isLoadingLyrics.value = false
            if (result.isSuccess) {
                _currentLyrics.value = result.getOrNull()
            }
        }
    }

    private fun startLyricsTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val state = _playbackState.value
                val lyrics = _currentLyrics.value

                if (state.track != null && lyrics != null) {
                    val currentPos = state.calculatedPositionMs
                    val offset = _trackOffsetMs.value
                    val activeObj = lyrics.getActiveLyricLine(currentPos, offset)
                    if (_activeLyricObject.value != activeObj) {
                        _activeLyricObject.value = activeObj
                        _activeLyricLine.value = activeObj?.text
                    }
                } else {
                    if (_activeLyricObject.value != null) {
                        _activeLyricObject.value = null
                        _activeLyricLine.value = null
                    }
                }

                if (!state.isPlaying) {
                    delay(300)
                } else {
                    delay(50)
                }
            }
        }
    }

    fun adjustTrackOffset(deltaMs: Long) {
        val currentTrack = _playbackState.value.track ?: return
        val newOffset = _trackOffsetMs.value + deltaMs
        _trackOffsetMs.value = newOffset
        scope.launch {
            preferencesRepository.setTrackOffset(currentTrack.key, newOffset)
        }
    }

    fun setExplicitOffset(offsetMs: Long) {
        val currentTrack = _playbackState.value.track ?: return
        _trackOffsetMs.value = offsetMs
        scope.launch {
            preferencesRepository.setTrackOffset(currentTrack.key, offsetMs)
        }
    }

    fun setManualLyrics(lyrics: ParsedLyrics) {
        _currentLyrics.value = lyrics
    }

    fun togglePlayPause() {
        activeController?.transportControls?.let { controls ->
            if (_playbackState.value.isPlaying) {
                controls.pause()
            } else {
                controls.play()
            }
        }
    }

    fun playPause() = togglePlayPause()

    fun next() {
        activeController?.transportControls?.skipToNext()
    }

    fun previous() {
        activeController?.transportControls?.skipToPrevious()
    }

    fun seekTo(positionMs: Long) {
        activeController?.transportControls?.seekTo(positionMs)
    }

    fun resyncLyrics(forceRefresh: Boolean = false) {
        val track = _playbackState.value.track ?: return

        // 1. Immediately re-query latest hardware/app playback state from active MediaController
        activeController?.let { controller ->
            updateFromPlaybackState(controller.playbackState)
        }

        // 2. Reset any accumulated offset
        _trackOffsetMs.value = 0L
        scope.launch {
            preferencesRepository.setTrackOffset(track.key, 0L)
            if (forceRefresh || _currentLyrics.value == null || _currentLyrics.value?.isSynced != true) {
                _isLoadingLyrics.value = true
                val result = lyricsRepository.getOrFetchLyrics(track, forceNetwork = true)
                _isLoadingLyrics.value = false
                if (result.isSuccess) {
                    _currentLyrics.value = result.getOrNull()
                }
            }
        }
    }

    fun reAnchorTimelineToCurrentPosition(targetLineIndex: Int? = null) {
        val lyrics = _currentLyrics.value ?: return
        val track = _playbackState.value.track ?: return

        // Refresh latest controller playback state
        activeController?.let { controller ->
            updateFromPlaybackState(controller.playbackState)
        }

        val curPos = _playbackState.value.calculatedPositionMs
        val targetIdx = targetLineIndex ?: lyrics.findActiveIndex(curPos, _trackOffsetMs.value).coerceAtLeast(0)

        if (targetIdx in lyrics.lines.indices) {
            val lineTime = lyrics.lines[targetIdx].timeMs
            val newOffset = lineTime - curPos
            _trackOffsetMs.value = newOffset
            scope.launch {
                preferencesRepository.setTrackOffset(track.key, newOffset)
            }
        }
    }


    companion object {
        @Volatile
        private var INSTANCE: MediaPlaybackManager? = null

        fun getInstance(context: Context): MediaPlaybackManager {
            return INSTANCE ?: synchronized(this) {
                val instance = MediaPlaybackManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
