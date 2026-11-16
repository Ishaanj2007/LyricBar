package com.example.service

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.util.Log
import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.PlaybackStateInfo
import com.example.data.model.TrackMetadata
import com.example.data.preferences.LyricsProviderPreference
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.CompositeLyricsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MediaPlaybackManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val lyricsRepository = CompositeLyricsRepository.getInstance(context)
    val preferencesRepository = UserPreferencesRepository(context)

    private val pipeline = MediaSessionPipelineEngine(
        scope = scope,
        repository = CompositeLyricsPipelineRepository(lyricsRepository),
        offsetStore = PreferencesTrackOffsetStore(preferencesRepository)
    )

    val playbackState: StateFlow<PlaybackStateInfo> = pipeline.playbackState
    val currentLyrics: StateFlow<ParsedLyrics?> = pipeline.currentLyrics
    val activeLyricLine: StateFlow<String?> = pipeline.activeLyricLine
    val activeLyricObject: StateFlow<LyricLine?> = pipeline.activeLyricObject
    val activeLyricIndex: StateFlow<Int> = pipeline.activeLyricIndex
    val trackOffsetMs: StateFlow<Long> = pipeline.trackOffsetMs
    val isLoadingLyrics: StateFlow<Boolean> = pipeline.isLoadingLyrics

    var activeController: MediaController? = null
        private set

    var onSessionDestroyedListener: (() -> Unit)? = null

    private var tickerJob: Job? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            val controller = activeController ?: return
            val pkg = safePackageName(controller)
            val appName = TrackMetadata.resolveAppName(pkg, context.packageManager)
            val pbState = safePlaybackState(controller)
            val snapshot = MediaSessionEligibilityEvaluator.fromMediaMetadata(
                packageName = pkg,
                appName = appName,
                metadata = metadata,
                playbackState = pbState
            )
            pipeline.updateMetadata(controller.sessionToken, snapshot, force = false)
            if (pipeline.currentSessionToken == null) {
                detachControllerCallbackOnly()
                onSessionDestroyedListener?.invoke()
            }
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val controller = activeController ?: return
            updateFromPlaybackState(state)
            // Re-evaluate metadata + playback state when transitioning to an active state if needed
            if (pipeline.currentSessionToken == null) {
                detachControllerCallbackOnly()
                onSessionDestroyedListener?.invoke()
            }
        }

        override fun onSessionDestroyed() {
            handleSessionDestroyed()
        }
    }

    val autoFetchLyrics: Boolean
        get() = pipeline.autoFetchLyrics

    val selectedLyricsProvider: LyricsProviderPreference
        get() = pipeline.selectedLyricsProvider

    init {
        startLyricsTicker()
        scope.launch {
            preferencesRepository.userPreferencesFlow.collect { prefs ->
                pipeline.onPreferencesChanged(
                    newAutoFetch = prefs.autoFetchLyrics,
                    newProvider = prefs.lyricsProvider
                )
            }
        }
    }

    fun setLyricsProvider(provider: LyricsProviderPreference) {
        scope.launch {
            preferencesRepository.setLyricsProvider(provider)
        }
    }

    fun attachMediaController(controller: MediaController?) {
        if (controller == null) {
            detachActiveController()
            return
        }

        val snapshot = MediaSessionEligibilityEvaluator.fromMediaController(controller, context.packageManager)
        val evaluation = MediaSessionEligibilityEvaluator.evaluate(snapshot)
        if (!evaluation.isEligible) {
            detachActiveController()
            return
        }

        val isSameSession = activeController?.sessionToken == controller.sessionToken
        if (isSameSession) {
            pipeline.attachSession(controller.sessionToken, snapshot)
            if (pipeline.currentSessionToken == null) {
                detachControllerCallbackOnly()
            }
            return
        }

        detachControllerCallbackOnly()
        activeController = controller

        try {
            controller.registerCallback(controllerCallback, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.w("MediaPlaybackManager", "Error registering controller callback: ${e.message}")
        }

        val attached = pipeline.attachSession(controller.sessionToken, snapshot)
        if (!attached) {
            detachControllerCallbackOnly()
        }
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

    private fun detachControllerCallbackOnly() {
        val old = activeController
        activeController = null
        try {
            old?.unregisterCallback(controllerCallback)
        } catch (_: Exception) {}
    }

    private fun detachActiveController() {
        detachControllerCallbackOnly()
        pipeline.invalidateActiveSession(clearTrack = true)
    }

    fun updateFromPlaybackState(state: PlaybackState?) {
        val controller = activeController ?: return
        val speed = if (state == null) {
            1.0f
        } else if (state.state == PlaybackState.STATE_BUFFERING || state.state == PlaybackState.STATE_CONNECTING) {
            0.0f
        } else {
            state.playbackSpeed
        }
        pipeline.updatePlaybackState(
            token = controller.sessionToken,
            rawState = state?.state,
            positionMs = state?.position ?: -1L,
            speed = speed
        )
        if (pipeline.currentSessionToken == null) {
            detachControllerCallbackOnly()
        }
    }

    private fun startLyricsTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (isActive) {
                try {
                    val controller = activeController
                    val realState = controller?.playbackState
                    if (controller != null && realState != null) {
                        val realPlaying = realState.state == PlaybackState.STATE_PLAYING
                        val stateValue = playbackState.value
                        val isStoppedOrError = realState.state == PlaybackState.STATE_STOPPED ||
                            realState.state == PlaybackState.STATE_NONE ||
                            realState.state == PlaybackState.STATE_ERROR
                        if (realPlaying != stateValue.isPlaying ||
                            (isStoppedOrError && stateValue.hasActiveSession)
                        ) {
                            updateFromPlaybackState(realState)
                        }
                    }
                } catch (_: Exception) {}

                pipeline.tickActiveLyrics()

                if (!playbackState.value.isPlaying) {
                    delay(300)
                } else {
                    delay(50)
                }
            }
        }
    }

    fun adjustTrackOffset(deltaMs: Long) {
        pipeline.adjustTrackOffset(deltaMs)
    }

    fun setExplicitOffset(offsetMs: Long) {
        pipeline.setExplicitOffset(offsetMs)
    }

    suspend fun saveImportedLyrics(
        track: TrackMetadata,
        content: String,
        source: String = "Local Import"
    ): ParsedLyrics = pipeline.saveImportedLyrics(track, content, source)

    fun setManualLyrics(lyrics: ParsedLyrics) {
        pipeline.setManualLyrics(lyrics)
    }

    private fun isControllerControllable(controller: MediaController?): Boolean {
        if (controller == null) return false
        return try {
            controller.playbackState != null || controller.metadata != null
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
        val expectedToken = controller.sessionToken
        val expectedGeneration = pipeline.sessionGeneration
        try {
            val controls = controller.transportControls ?: run {
                handleSessionUnavailable()
                return
            }
            val state = controller.playbackState
            val isPlaying = state?.state == PlaybackState.STATE_PLAYING || playbackState.value.isPlaying
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
                controls.play()
            }

            scope.launch {
                delay(80)
                refreshIfSameGeneration(expectedToken, expectedGeneration, refreshMetadata = false)
                delay(180)
                refreshIfSameGeneration(expectedToken, expectedGeneration, refreshMetadata = false)
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
        val expectedToken = controller.sessionToken
        val expectedGeneration = pipeline.sessionGeneration
        try {
            val controls = controller.transportControls ?: run {
                handleSessionUnavailable()
                return
            }
            controls.skipToNext()
            scope.launch {
                delay(100)
                refreshIfSameGeneration(expectedToken, expectedGeneration, refreshMetadata = true)
            }
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
        val expectedToken = controller.sessionToken
        val expectedGeneration = pipeline.sessionGeneration
        try {
            val controls = controller.transportControls ?: run {
                handleSessionUnavailable()
                return
            }
            controls.skipToPrevious()
            scope.launch {
                delay(100)
                refreshIfSameGeneration(expectedToken, expectedGeneration, refreshMetadata = true)
            }
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
        val expectedToken = controller.sessionToken
        val expectedGeneration = pipeline.sessionGeneration
        try {
            val controls = controller.transportControls ?: run {
                handleSessionUnavailable()
                return
            }
            controls.seekTo(positionMs)
            pipeline.updateSeekPreviewPosition(positionMs)
            scope.launch {
                delay(80)
                refreshIfSameGeneration(expectedToken, expectedGeneration, refreshMetadata = false)
            }
        } catch (e: Exception) {
            Log.w("MediaPlaybackManager", "Error in seekTo: ${e.message}")
            handleSessionUnavailable()
        }
    }

    private fun refreshIfSameGeneration(
        expectedToken: Any?,
        expectedGeneration: Long,
        refreshMetadata: Boolean
    ) {
        val controller = activeController ?: return
        if (controller.sessionToken != expectedToken) return
        if (pipeline.currentSessionToken != expectedToken) return
        if (pipeline.sessionGeneration != expectedGeneration) return

        if (refreshMetadata) {
            val snapshot = MediaSessionEligibilityEvaluator.fromMediaController(controller, context.packageManager)
            pipeline.updateMetadata(expectedToken, snapshot, force = false)
            if (pipeline.currentSessionToken == null) {
                detachControllerCallbackOnly()
                onSessionDestroyedListener?.invoke()
                return
            }
        }
        safePlaybackState(controller)?.let { updateFromPlaybackState(it) }
    }

    fun resyncLyrics(forceRefresh: Boolean = false) {
        val controller = activeController ?: return
        val snapshot = try {
            MediaSessionEligibilityEvaluator.fromMediaController(controller, context.packageManager)
        } catch (e: Exception) {
            Log.w("MediaPlaybackManager", "Error querying playback state in resync: ${e.message}")
            handleSessionUnavailable()
            return
        }
        pipeline.resyncLyrics(forceRefresh = forceRefresh, latestSnapshot = snapshot)
        if (pipeline.currentSessionToken == null) {
            detachControllerCallbackOnly()
            onSessionDestroyedListener?.invoke()
        }
    }

    fun reAnchorTimelineToCurrentPosition(targetLineIndex: Int? = null) {
        val controller = activeController ?: return
        val snapshot = try {
            MediaSessionEligibilityEvaluator.fromMediaController(controller, context.packageManager)
        } catch (e: Exception) {
            Log.w("MediaPlaybackManager", "Error querying playback state in reAnchor: ${e.message}")
            handleSessionUnavailable()
            return
        }
        pipeline.reAnchorTimelineToCurrentPosition(
            targetLineIndex = targetLineIndex,
            latestSnapshot = snapshot
        )
    }

    private fun safePackageName(controller: MediaController): String = try {
        controller.packageName ?: ""
    } catch (_: Exception) {
        ""
    }

    private fun safePlaybackState(controller: MediaController): PlaybackState? = try {
        controller.playbackState
    } catch (_: Exception) {
        null
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
