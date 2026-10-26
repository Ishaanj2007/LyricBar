package com.example.service

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
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

    var activeController: MediaController? = null
        private set

    var onSessionDestroyedListener: (() -> Unit)? = null

    private var currentSessionToken: MediaSession.Token? = null
    private var currentTrackSessionToken: MediaSession.Token? = null

    private var fetchJob: Job? = null
    private var tickerJob: Job? = null

    // Controller callback
    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateFromMetadata(metadata, force = false)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateFromPlaybackState(state)
        }

        override fun onSessionDestroyed() {
            handleSessionDestroyed()
        }
    }

    var autoFetchLyrics: Boolean = true
        private set

    init {
        startLyricsTicker()
        scope.launch {
            preferencesRepository.userPreferencesFlow.collect { prefs ->
                val previous = autoFetchLyrics
                autoFetchLyrics = prefs.autoFetchLyrics

                if (!previous && autoFetchLyrics) {
                    val currentTrack = _playbackState.value.track
                    if (currentTrack != null && _currentLyrics.value == null && !_isLoadingLyrics.value) {
                        fetchLyricsForTrack(currentTrack, forceNetwork = false)
                    }
                } else if (previous && !autoFetchLyrics) {
                    if (_isLoadingLyrics.value && _currentLyrics.value == null) {
                        fetchJob?.cancel()
                        _isLoadingLyrics.value = false
                    }
                }
            }
        }
    }

    fun attachMediaController(controller: MediaController?) {
        if (controller == null) {
            detachActiveController()
            return
        }

        val isSameSession = activeController?.sessionToken == controller.sessionToken
        if (isSameSession) {
            // Already attached to this controller. Refresh metadata and playback state without re-registering callbacks.
            updateFromMetadata(controller.metadata, force = false)
            updateFromPlaybackState(controller.playbackState)
            return
        }

        // Switching or attaching a new controller
        detachActiveControllerInternal()

        activeController = controller
        currentSessionToken = controller.sessionToken

        try {
            controller.registerCallback(controllerCallback)
        } catch (e: Exception) {
            Log.w("MediaPlaybackManager", "Error registering controller callback: ${e.message}")
        }

        // Force metadata update for a new controller so a new session/track is detected
        // even if it shares the same title/artist as the previous session.
        updateFromMetadata(controller.metadata, force = true)
        updateFromPlaybackState(controller.playbackState)
    }

    fun handleNoActiveSessions() {
        detachActiveController()
    }

    private fun handleSessionDestroyed() {
        detachActiveController()
        onSessionDestroyedListener?.invoke()
    }

    private fun handleSessionUnavailable() {
        detachActiveController()
        onSessionDestroyedListener?.invoke()
    }

    private fun detachActiveControllerInternal() {
        val old = activeController
        activeController = null
        currentSessionToken = null
        try {
            old?.unregisterCallback(controllerCallback)
        } catch (_: Exception) {}
    }

    private fun detachActiveController() {
        detachActiveControllerInternal()
        lastRecordedTrackKey = null
        // Mark playback as inactive; freeze position and preserve last lyric line & track metadata
        _playbackState.value = _playbackState.value.copy(
            isPlaying = false,
            hasActiveSession = false,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    private fun updateFromMetadata(metadata: MediaMetadata?, force: Boolean = false) {
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
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            ?: metadata.description?.iconUri?.toString()

        val artBitmap = try {
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: metadata.description?.iconBitmap
        } catch (_: Throwable) {
            null
        }

        val pkgName = activeController?.packageName ?: ""
        val appName = TrackMetadata.resolveAppName(pkgName, context.packageManager)

        val track = TrackMetadata(
            title = title,
            artist = artist,
            album = album,
            durationSec = durationSec,
            sourcePackage = pkgName,
            sourceAppName = appName,
            albumArtUri = artUri,
            albumArtBitmap = artBitmap
        )

        val sessionToken = activeController?.sessionToken
        val isNewSession = sessionToken != null && sessionToken != currentTrackSessionToken
        val currentTrack = _playbackState.value.track
        val isDifferentPlayer = currentTrack?.sourcePackage != track.sourcePackage

        if (force || isNewSession || isDifferentPlayer || currentTrack?.key != track.key) {
            currentTrackSessionToken = sessionToken
            _playbackState.value = _playbackState.value.copy(
                track = track,
                hasActiveSession = true
            )
            onTrackChanged(track)
        } else if (currentTrack != track) {
            _playbackState.value = _playbackState.value.copy(
                track = track,
                hasActiveSession = true
            )
        }
    }

    private fun updateFromPlaybackState(state: PlaybackState?) {
        if (state == null) {
            if (_playbackState.value.isPlaying) {
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    lastUpdatedMs = System.currentTimeMillis()
                )
            }
            return
        }

        val isPlaying = state.state == PlaybackState.STATE_PLAYING
        val position = state.position
        val speed = state.playbackSpeed
        val validPosition = if (position >= 0L) position else _playbackState.value.positionMs

        // If loop/repeat replayed the track from beginning after playing significantly into the track
        val isReplay = isPlaying &&
            _playbackState.value.positionMs > 20_000L &&
            validPosition < 3_000L
        if (isReplay) {
            lastRecordedTrackKey = null
        }

        _playbackState.value = _playbackState.value.copy(
            isPlaying = isPlaying,
            hasActiveSession = activeController != null,
            positionMs = validPosition,
            playbackSpeed = if (speed > 0f) speed else 1.0f,
            lastUpdatedMs = System.currentTimeMillis()
        )

        if (isPlaying) {
            recordSessionIfPlaying(_playbackState.value.track)
        }

        // Immediately sync active lyric line on playback/seek update so highlight is never stale
        val lyrics = _currentLyrics.value
        val track = _playbackState.value.track
        if (track != null && lyrics != null && lyrics.isSynced) {
            val offset = _trackOffsetMs.value
            val activeObj = lyrics.getActiveLyricLine(validPosition, offset)
            if (activeObj != null && _activeLyricObject.value != activeObj) {
                _activeLyricObject.value = activeObj
                _activeLyricLine.value = activeObj.text
            }
        }
    }

    private var offsetJob: Job? = null
    private var lastRecordedTrackKey: String? = null

    private fun recordSessionIfPlaying(track: TrackMetadata?) {
        if (track == null || track.key.isBlank()) return
        if (!_playbackState.value.isPlaying) return

        if (lastRecordedTrackKey == track.key) {
            return
        }

        lastRecordedTrackKey = track.key
        scope.launch {
            lyricsRepository.recordHistory(track)
        }
    }

    private fun onTrackChanged(track: TrackMetadata) {
        // Cancel any pending stale network requests and offset collectors
        fetchJob?.cancel()
        offsetJob?.cancel()

        _currentLyrics.value = null
        _activeLyricObject.value = null
        _activeLyricLine.value = null

        if (_playbackState.value.isPlaying) {
            recordSessionIfPlaying(track)
        }

        // Load persisted offset for this specific track
        offsetJob = scope.launch {
            preferencesRepository.getTrackOffsetFlow(track.key).collect { offset ->
                _trackOffsetMs.value = offset
            }
        }

        fetchLyricsForTrack(track, forceNetwork = false)
    }

    private fun fetchLyricsForTrack(track: TrackMetadata, forceNetwork: Boolean = false) {
        fetchJob?.cancel()
        _isLoadingLyrics.value = true

        fetchJob = scope.launch {
            val result = lyricsRepository.getOrFetchLyrics(
                track = track,
                forceNetwork = forceNetwork,
                allowNetwork = autoFetchLyrics || forceNetwork
            )
            _isLoadingLyrics.value = false
            if (result.isSuccess) {
                val lyrics = result.getOrNull()
                _currentLyrics.value = lyrics
                if (lyrics != null) {
                    val currentPos = _playbackState.value.calculatedPositionMs
                    val offset = _trackOffsetMs.value
                    val initialObj = lyrics.getActiveLyricLine(currentPos, offset)
                    if (initialObj != null) {
                        _activeLyricObject.value = initialObj
                        _activeLyricLine.value = initialObj.text
                    }
                }
            }
        }
    }

    private fun startLyricsTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                val state = _playbackState.value
                val lyrics = _currentLyrics.value

                // Only advance active lyric line when playback is actively playing and lyrics are synchronized
                if (state.isPlaying && state.track != null && lyrics != null && lyrics.isSynced) {
                    val currentPos = state.calculatedPositionMs
                    val offset = _trackOffsetMs.value
                    val activeObj = lyrics.getActiveLyricLine(currentPos, offset)
                    if (activeObj != null && _activeLyricObject.value != activeObj) {
                        _activeLyricObject.value = activeObj
                        _activeLyricLine.value = activeObj.text
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
        val currentPos = _playbackState.value.calculatedPositionMs
        val offset = _trackOffsetMs.value
        val initialObj = lyrics.getActiveLyricLine(currentPos, offset)
        _activeLyricObject.value = initialObj
        _activeLyricLine.value = initialObj?.text
    }

    private fun isControllerControllable(controller: MediaController?): Boolean {
        if (controller == null) return false
        return try {
            controller.playbackInfo != null
        } catch (_: Exception) {
            false
        }
    }

    fun togglePlayPause() {
        val controller = activeController
        if (controller == null || !isControllerControllable(controller)) {
            handleSessionUnavailable()
            return
        }
        try {
            val controls = controller.transportControls ?: run {
                handleSessionUnavailable()
                return
            }
            val state = controller.playbackState
            val isPlaying = state?.state == PlaybackState.STATE_PLAYING || _playbackState.value.isPlaying
            val actions = state?.actions ?: 0L

            if (isPlaying) {
                if (actions == 0L || (actions and PlaybackState.ACTION_PAUSE) != 0L || (actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L) {
                    controls.pause()
                } else if ((actions and PlaybackState.ACTION_STOP) != 0L) {
                    controls.stop()
                } else {
                    controls.pause()
                }
            } else {
                if (actions == 0L || (actions and PlaybackState.ACTION_PLAY) != 0L || (actions and PlaybackState.ACTION_PLAY_PAUSE) != 0L) {
                    controls.play()
                } else {
                    controls.play()
                }
            }
        } catch (e: Exception) {
            Log.w("MediaPlaybackManager", "Error in togglePlayPause: ${e.message}")
            handleSessionUnavailable()
        }
    }

    fun playPause() = togglePlayPause()

    fun next() {
        val controller = activeController
        if (controller == null || !isControllerControllable(controller)) {
            handleSessionUnavailable()
            return
        }
        try {
            val controls = controller.transportControls ?: run {
                handleSessionUnavailable()
                return
            }
            controls.skipToNext()
        } catch (e: Exception) {
            Log.w("MediaPlaybackManager", "Error in next: ${e.message}")
            handleSessionUnavailable()
        }
    }

    fun previous() {
        val controller = activeController
        if (controller == null || !isControllerControllable(controller)) {
            handleSessionUnavailable()
            return
        }
        try {
            val controls = controller.transportControls ?: run {
                handleSessionUnavailable()
                return
            }
            controls.skipToPrevious()
        } catch (e: Exception) {
            Log.w("MediaPlaybackManager", "Error in previous: ${e.message}")
            handleSessionUnavailable()
        }
    }

    fun seekTo(positionMs: Long) {
        val controller = activeController
        if (controller == null || !isControllerControllable(controller)) {
            handleSessionUnavailable()
            return
        }
        try {
            val controls = controller.transportControls ?: run {
                handleSessionUnavailable()
                return
            }
            controls.seekTo(positionMs)
        } catch (e: Exception) {
            Log.w("MediaPlaybackManager", "Error in seekTo: ${e.message}")
            handleSessionUnavailable()
        }
    }

    fun resyncLyrics(forceRefresh: Boolean = false) {
        val track = _playbackState.value.track ?: return

        // 1. Immediately re-query latest hardware/app playback state from active MediaController
        try {
            activeController?.let { controller ->
                updateFromPlaybackState(controller.playbackState)
            }
        } catch (e: Exception) {
            Log.w("MediaPlaybackManager", "Error querying playback state in resync: ${e.message}")
            handleSessionUnavailable()
        }

        // 2. Reset any accumulated offset
        _trackOffsetMs.value = 0L
        scope.launch {
            preferencesRepository.setTrackOffset(track.key, 0L)
            if (forceRefresh || _currentLyrics.value == null || _currentLyrics.value?.isSynced != true) {
                _isLoadingLyrics.value = true
                val result = lyricsRepository.getOrFetchLyrics(track, forceNetwork = true, allowNetwork = true)
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
        try {
            activeController?.let { controller ->
                updateFromPlaybackState(controller.playbackState)
            }
        } catch (e: Exception) {
            Log.w("MediaPlaybackManager", "Error querying playback state in reAnchor: ${e.message}")
            handleSessionUnavailable()
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
