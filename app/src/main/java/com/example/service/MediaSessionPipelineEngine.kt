package com.example.service

import android.media.session.PlaybackState
import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.PlaybackStateInfo
import com.example.data.model.TrackMetadata
import com.example.data.preferences.LyricsProviderPreference
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.CompositeLyricsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

interface LyricsPipelineRepository {
    suspend fun getOrFetchLyrics(
        track: TrackMetadata,
        forceNetwork: Boolean = false,
        allowNetwork: Boolean = true,
        providerPreference: LyricsProviderPreference = LyricsProviderPreference.AUTO
    ): Result<ParsedLyrics>

    suspend fun saveImportedLyrics(
        track: TrackMetadata,
        content: String,
        source: String = "Local Import"
    ): ParsedLyrics

    suspend fun recordHistory(track: TrackMetadata, status: String = "Played")
}

class CompositeLyricsPipelineRepository(
    private val repository: CompositeLyricsRepository
) : LyricsPipelineRepository {
    override suspend fun getOrFetchLyrics(
        track: TrackMetadata,
        forceNetwork: Boolean,
        allowNetwork: Boolean,
        providerPreference: LyricsProviderPreference
    ): Result<ParsedLyrics> = repository.getOrFetchLyrics(
        track = track,
        forceNetwork = forceNetwork,
        allowNetwork = allowNetwork,
        providerPreference = providerPreference
    )

    override suspend fun saveImportedLyrics(
        track: TrackMetadata,
        content: String,
        source: String
    ): ParsedLyrics = repository.saveImportedLyrics(track, content, source)

    override suspend fun recordHistory(track: TrackMetadata, status: String) {
        repository.recordHistory(track, status)
    }
}

interface TrackOffsetStore {
    fun getTrackOffsetFlow(trackKey: String, fallbackKey: String? = null): Flow<Long>
    suspend fun setTrackOffset(trackKey: String, offsetMs: Long)
}

class PreferencesTrackOffsetStore(
    private val preferencesRepository: UserPreferencesRepository
) : TrackOffsetStore {
    override fun getTrackOffsetFlow(trackKey: String, fallbackKey: String?): Flow<Long> =
        preferencesRepository.getTrackOffsetFlow(trackKey, fallbackKey)

    override suspend fun setTrackOffset(trackKey: String, offsetMs: Long) {
        preferencesRepository.setTrackOffset(trackKey, offsetMs)
    }
}

/**
 * Pure-Kotlin state machine governing MediaSession eligibility enforcement, track identity
 * transitions, stale lyrics invalidation, async fetch/race cancellation, and history recording.
 */
class MediaSessionPipelineEngine(
    private val scope: CoroutineScope,
    private val repository: LyricsPipelineRepository,
    private val offsetStore: TrackOffsetStore,
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    private val _playbackState = MutableStateFlow(PlaybackStateInfo())
    val playbackState: StateFlow<PlaybackStateInfo> = _playbackState.asStateFlow()

    private val _currentLyrics = MutableStateFlow<ParsedLyrics?>(null)
    val currentLyrics: StateFlow<ParsedLyrics?> = _currentLyrics.asStateFlow()

    private val _activeLyricLine = MutableStateFlow<String?>(null)
    val activeLyricLine: StateFlow<String?> = _activeLyricLine.asStateFlow()

    private val _activeLyricObject = MutableStateFlow<LyricLine?>(null)
    val activeLyricObject: StateFlow<LyricLine?> = _activeLyricObject.asStateFlow()

    private val _activeLyricIndex = MutableStateFlow(-1)
    val activeLyricIndex: StateFlow<Int> = _activeLyricIndex.asStateFlow()

    private val _trackOffsetMs = MutableStateFlow(0L)
    val trackOffsetMs: StateFlow<Long> = _trackOffsetMs.asStateFlow()

    private val _isLoadingLyrics = MutableStateFlow(false)
    val isLoadingLyrics: StateFlow<Boolean> = _isLoadingLyrics.asStateFlow()

    var currentSessionToken: Any? = null
        private set

    var currentTrackSessionToken: Any? = null
        private set

    /**
     * Monotonic generation incremented on every track change, session switch,
     * stop, or invalidation. Any async lyrics fetch or history insert must match
     * this generation before mutating live state.
     */
    var sessionGeneration: Long = 0L
        private set

    var autoFetchLyrics: Boolean = true
        private set

    var selectedLyricsProvider: LyricsProviderPreference = LyricsProviderPreference.AUTO
        private set

    private var fetchJob: Job? = null
    private var offsetJob: Job? = null
    private var historyJob: Job? = null
    private var lastRecordedTrackKey: String? = null

    /**
     * Attempts to attach an eligible session snapshot. If the session is null or
     * ineligible, invalidates active state and cancels any pending requests so stale
     * lyrics never leak onto an ineligible session.
     */
    fun attachSession(token: Any?, snapshot: MediaSessionSnapshot?): Boolean {
        if (token == null || snapshot == null) {
            invalidateActiveSession(clearTrack = true)
            return false
        }

        val evaluation = MediaSessionEligibilityEvaluator.evaluate(snapshot)
        if (!evaluation.isEligible) {
            invalidateActiveSession(clearTrack = true)
            return false
        }

        val isSameSession = currentSessionToken == token
        if (isSameSession) {
            updateMetadata(token, snapshot, force = false)
            if (currentSessionToken == token) {
                updatePlaybackState(
                    token = token,
                    rawState = snapshot.playbackState,
                    positionMs = snapshot.positionMs,
                    speed = snapshot.playbackSpeed
                )
            }
            return currentSessionToken == token
        }

        // Switching to a new eligible controller: invalidate prior fetch/offset jobs first
        fetchJob?.cancel()
        fetchJob = null
        offsetJob?.cancel()
        offsetJob = null
        historyJob?.cancel()
        historyJob = null
        _isLoadingLyrics.value = false
        lastRecordedTrackKey = null
        sessionGeneration++

        currentSessionToken = token
        updateMetadata(token, snapshot, force = true)
        if (currentSessionToken == token) {
            updatePlaybackState(
                token = token,
                rawState = snapshot.playbackState,
                positionMs = snapshot.positionMs,
                speed = snapshot.playbackSpeed
            )
        }
        return currentSessionToken == token
    }

    /**
     * Updates metadata for the currently attached session token.
     * Never silently returns early while leaving a stale track active if metadata becomes
     * null, blank, incomplete, or fails music eligibility.
     */
    fun updateMetadata(token: Any?, snapshot: MediaSessionSnapshot?, force: Boolean = false) {
        if (token == null || token != currentSessionToken) return

        if (snapshot == null || !snapshot.hasMetadata) {
            invalidateActiveSession(clearTrack = true)
            return
        }

        val evaluation = MediaSessionEligibilityEvaluator.evaluate(snapshot)
        if (!evaluation.isEligible) {
            invalidateActiveSession(clearTrack = true)
            return
        }

        val track = snapshot.toTrackMetadata()
        if (track == null || !track.isValidTrack || !track.hasRealMusicAttribution) {
            invalidateActiveSession(clearTrack = true)
            return
        }

        val isNewSession = token != currentTrackSessionToken
        val currentTrack = _playbackState.value.track
        val isDifferentPlayer = currentTrack?.sourcePackage != track.sourcePackage

        if (force || isNewSession || isDifferentPlayer || currentTrack?.key != track.key) {
            sessionGeneration++
            currentTrackSessionToken = token
            lastRecordedTrackKey = null
            _playbackState.value = _playbackState.value.copy(
                track = track,
                hasActiveSession = true,
                lastUpdatedMs = nowProvider()
            )
            onTrackChanged(track)
        } else if (currentTrack != track) {
            _playbackState.value = _playbackState.value.copy(
                track = track,
                hasActiveSession = true
            )
        }
    }

    fun updatePlaybackState(
        token: Any?,
        rawState: Int?,
        positionMs: Long,
        speed: Float
    ) {
        if (token == null || token != currentSessionToken) return

        if (rawState == null) {
            if (_playbackState.value.isPlaying || _playbackState.value.hasActiveSession) {
                _playbackState.value = _playbackState.value.copy(
                    isPlaying = false,
                    hasActiveSession = false,
                    lastUpdatedMs = nowProvider()
                )
            }
            return
        }

        val isExplicitStoppedOrNone = rawState == PlaybackState.STATE_STOPPED ||
            rawState == PlaybackState.STATE_NONE ||
            rawState == PlaybackState.STATE_ERROR

        if (isExplicitStoppedOrNone) {
            invalidateActiveSession(clearTrack = true)
            return
        }

        val isPlaying = when (rawState) {
            PlaybackState.STATE_PLAYING -> true
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> {
                // Avoid flickering overlay during transient buffering while already playing
                _playbackState.value.isPlaying
            }
            else -> false
        }

        val effectiveSpeed = if (rawState == PlaybackState.STATE_BUFFERING || rawState == PlaybackState.STATE_CONNECTING) {
            0.0f
        } else {
            speed
        }
        val validPosition = if (positionMs >= 0L) positionMs else _playbackState.value.positionMs

        // Detect replay of the same song from the start after substantial progress
        val isReplay = isPlaying &&
            _playbackState.value.positionMs > 20_000L &&
            validPosition < 3_000L
        if (isReplay) {
            lastRecordedTrackKey = null
        }

        _playbackState.value = _playbackState.value.copy(
            isPlaying = isPlaying,
            hasActiveSession = true,
            positionMs = validPosition,
            playbackSpeed = if (effectiveSpeed > 0f) effectiveSpeed else 1.0f,
            lastUpdatedMs = nowProvider()
        )

        val track = _playbackState.value.track
        if (isPlaying && track != null) {
            recordSessionIfPlaying(track)
        }

        if (track != null) {
            updateActiveLyrics(validPosition, _trackOffsetMs.value)
        }
    }

    fun invalidateActiveSession(clearTrack: Boolean = true) {
        sessionGeneration++
        fetchJob?.cancel()
        fetchJob = null
        offsetJob?.cancel()
        offsetJob = null
        historyJob?.cancel()
        historyJob = null
        _isLoadingLyrics.value = false
        currentSessionToken = null
        currentTrackSessionToken = null
        lastRecordedTrackKey = null

        if (clearTrack) {
            _currentLyrics.value = null
            _activeLyricIndex.value = -1
            _activeLyricObject.value = null
            _activeLyricLine.value = null
            _trackOffsetMs.value = 0L
            _playbackState.value = PlaybackStateInfo(
                track = null,
                isPlaying = false,
                hasActiveSession = false,
                positionMs = 0L,
                playbackSpeed = 1.0f,
                lastUpdatedMs = nowProvider()
            )
        } else {
            _playbackState.value = _playbackState.value.copy(
                isPlaying = false,
                hasActiveSession = false,
                lastUpdatedMs = nowProvider()
            )
        }
    }

    fun onPreferencesChanged(
        newAutoFetch: Boolean,
        newProvider: LyricsProviderPreference
    ) {
        val previousAutoFetch = autoFetchLyrics
        val previousProvider = selectedLyricsProvider
        autoFetchLyrics = newAutoFetch
        selectedLyricsProvider = newProvider

        val currentTrack = _playbackState.value.track
        val eligibleTrack = currentTrack?.takeIf {
            currentSessionToken != null &&
                _playbackState.value.hasActiveSession &&
                it.isValidTrack &&
                it.hasRealMusicAttribution
        }

        if (previousProvider != newProvider) {
            if (eligibleTrack != null) {
                fetchLyricsForTrack(eligibleTrack, forceNetwork = false)
            }
        } else if (!previousAutoFetch && newAutoFetch) {
            if (eligibleTrack != null && _currentLyrics.value == null && !_isLoadingLyrics.value) {
                fetchLyricsForTrack(eligibleTrack, forceNetwork = false)
            }
        } else if (previousAutoFetch && !newAutoFetch) {
            if (_isLoadingLyrics.value && _currentLyrics.value == null) {
                fetchJob?.cancel()
                fetchJob = null
                _isLoadingLyrics.value = false
            }
        }
    }

    fun adjustTrackOffset(deltaMs: Long) {
        val currentTrack = _playbackState.value.track ?: return
        if (currentSessionToken == null) return
        val newOffset = _trackOffsetMs.value + deltaMs
        _trackOffsetMs.value = newOffset
        scope.launch {
            offsetStore.setTrackOffset(currentTrack.key, newOffset)
        }
        val lyrics = _currentLyrics.value
        if (lyrics != null && lyrics.isSynced) {
            val currentPos = _playbackState.value.calculatedPositionMs
            updateActiveLyrics(currentPos, newOffset)
        }
    }

    fun setExplicitOffset(offsetMs: Long) {
        val currentTrack = _playbackState.value.track ?: return
        if (currentSessionToken == null) return
        _trackOffsetMs.value = offsetMs
        scope.launch {
            offsetStore.setTrackOffset(currentTrack.key, offsetMs)
        }
        val lyrics = _currentLyrics.value
        if (lyrics != null && lyrics.isSynced) {
            val currentPos = _playbackState.value.calculatedPositionMs
            updateActiveLyrics(currentPos, offsetMs)
        }
    }

    suspend fun saveImportedLyrics(
        track: TrackMetadata,
        content: String,
        source: String = "Local Import"
    ): ParsedLyrics {
        val parsed = repository.saveImportedLyrics(track, content, source)
        val currentTrack = _playbackState.value.track
        if (currentTrack != null && currentTrack.identityKey == track.identityKey) {
            setManualLyrics(parsed)
        }
        return parsed
    }

    fun setManualLyrics(lyrics: ParsedLyrics) {
        if (currentSessionToken == null || _playbackState.value.track == null) return
        if (lyrics.hasValidLyrics) {
            _currentLyrics.value = lyrics
            val currentPos = _playbackState.value.calculatedPositionMs
            val offset = _trackOffsetMs.value
            updateActiveLyrics(currentPos, offset)
        } else {
            _currentLyrics.value = null
            updateActiveLyrics(0L, 0L)
        }
    }

    fun resyncLyrics(
        forceRefresh: Boolean = false,
        latestSnapshot: MediaSessionSnapshot? = null
    ) {
        val token = currentSessionToken ?: return
        if (latestSnapshot != null) {
            val eval = MediaSessionEligibilityEvaluator.evaluate(latestSnapshot)
            if (!eval.isEligible) {
                invalidateActiveSession(clearTrack = true)
                return
            }
            updatePlaybackState(
                token = token,
                rawState = latestSnapshot.playbackState,
                positionMs = latestSnapshot.positionMs,
                speed = latestSnapshot.playbackSpeed
            )
        }

        val track = _playbackState.value.track ?: return
        if (!track.hasRealMusicAttribution) {
            invalidateActiveSession(clearTrack = true)
            return
        }

        val expectedGen = sessionGeneration
        val expectedIdentity = track.identityKey

        _trackOffsetMs.value = 0L
        scope.launch {
            offsetStore.setTrackOffset(track.key, 0L)
        }

        if (forceRefresh || _currentLyrics.value == null || _currentLyrics.value?.isSynced != true) {
            fetchJob?.cancel()
            _isLoadingLyrics.value = true
            fetchJob = scope.launch {
                val result = try {
                    repository.getOrFetchLyrics(
                        track = track,
                        forceNetwork = true,
                        allowNetwork = true,
                        providerPreference = selectedLyricsProvider
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Result.failure(e)
                }

                if (!isActive ||
                    sessionGeneration != expectedGen ||
                    currentSessionToken == null ||
                    currentSessionToken != token ||
                    _playbackState.value.track?.identityKey != expectedIdentity
                ) {
                    return@launch
                }

                _isLoadingLyrics.value = false
                if (result.isSuccess) {
                    val lyrics = result.getOrNull()
                    if (lyrics != null && lyrics.hasValidLyrics) {
                        _currentLyrics.value = lyrics
                        updateActiveLyrics(_playbackState.value.calculatedPositionMs, _trackOffsetMs.value)
                    } else {
                        _currentLyrics.value = null
                        updateActiveLyrics(0L, 0L)
                    }
                }
            }
        } else {
            updateActiveLyrics(_playbackState.value.calculatedPositionMs, 0L)
        }
    }

    fun reAnchorTimelineToCurrentPosition(
        targetLineIndex: Int? = null,
        latestSnapshot: MediaSessionSnapshot? = null
    ) {
        val token = currentSessionToken ?: return
        val lyrics = _currentLyrics.value ?: return
        val track = _playbackState.value.track ?: return

        if (latestSnapshot != null) {
            updatePlaybackState(
                token = token,
                rawState = latestSnapshot.playbackState,
                positionMs = latestSnapshot.positionMs,
                speed = latestSnapshot.playbackSpeed
            )
        }

        val curPos = _playbackState.value.calculatedPositionMs
        val targetIdx = targetLineIndex ?: lyrics.findActiveIndex(curPos, _trackOffsetMs.value).coerceAtLeast(0)

        if (targetIdx in lyrics.lines.indices) {
            val lineTime = lyrics.lines[targetIdx].timeMs
            val newOffset = lineTime - curPos
            _trackOffsetMs.value = newOffset
            scope.launch {
                offsetStore.setTrackOffset(track.key, newOffset)
            }
            updateActiveLyrics(curPos, newOffset)
        }
    }

    fun updateSeekPreviewPosition(positionMs: Long) {
        _playbackState.value = _playbackState.value.copy(
            positionMs = positionMs,
            lastUpdatedMs = nowProvider()
        )
        val lyrics = _currentLyrics.value
        if (lyrics != null && lyrics.isSynced) {
            updateActiveLyrics(positionMs, _trackOffsetMs.value)
        }
    }

    fun tickActiveLyrics() {
        val state = _playbackState.value
        val lyrics = _currentLyrics.value
        if (currentSessionToken != null &&
            state.hasActiveSession &&
            state.isPlaying &&
            state.track != null &&
            lyrics != null &&
            lyrics.isSynced
        ) {
            updateActiveLyrics(state.calculatedPositionMs, _trackOffsetMs.value)
        }
    }

    private fun onTrackChanged(track: TrackMetadata) {
        fetchJob?.cancel()
        fetchJob = null
        offsetJob?.cancel()
        offsetJob = null

        _currentLyrics.value = null
        _activeLyricIndex.value = -1
        _activeLyricObject.value = null
        _activeLyricLine.value = null
        _trackOffsetMs.value = 0L

        if (_playbackState.value.isPlaying) {
            recordSessionIfPlaying(track)
        }

        val expectedGen = sessionGeneration
        val expectedToken = currentSessionToken
        val expectedIdentity = track.identityKey

        offsetJob = scope.launch {
            offsetStore.getTrackOffsetFlow(track.identityKey, track.lookupKey).collect { offset ->
                if (sessionGeneration == expectedGen &&
                    currentSessionToken == expectedToken &&
                    _playbackState.value.track?.identityKey == expectedIdentity
                ) {
                    _trackOffsetMs.value = offset
                }
            }
        }

        fetchLyricsForTrack(track, forceNetwork = false)
    }

    fun fetchLyricsForTrack(track: TrackMetadata, forceNetwork: Boolean = false) {
        if (!track.isValidTrack || !track.hasRealMusicAttribution) {
            fetchJob?.cancel()
            fetchJob = null
            _isLoadingLyrics.value = false
            return
        }

        val expectedGen = sessionGeneration
        val expectedToken = currentSessionToken ?: return
        val expectedIdentity = track.identityKey

        fetchJob?.cancel()
        _isLoadingLyrics.value = true

        fetchJob = scope.launch {
            val result = try {
                repository.getOrFetchLyrics(
                    track = track,
                    forceNetwork = forceNetwork,
                    allowNetwork = autoFetchLyrics || forceNetwork,
                    providerPreference = selectedLyricsProvider
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                Result.failure(e)
            }

            if (!isActive ||
                sessionGeneration != expectedGen ||
                currentSessionToken == null ||
                currentSessionToken != expectedToken ||
                _playbackState.value.track?.identityKey != expectedIdentity
            ) {
                return@launch
            }

            _isLoadingLyrics.value = false
            if (result.isSuccess) {
                val lyrics = result.getOrNull()
                if (lyrics != null && lyrics.hasValidLyrics) {
                    _currentLyrics.value = lyrics
                    val currentPos = _playbackState.value.calculatedPositionMs
                    val offset = _trackOffsetMs.value
                    updateActiveLyrics(currentPos, offset)
                } else {
                    _currentLyrics.value = null
                    updateActiveLyrics(0L, 0L)
                }
            } else {
                _currentLyrics.value = null
                updateActiveLyrics(0L, 0L)
            }
        }
    }

    private fun recordSessionIfPlaying(track: TrackMetadata?) {
        if (track == null || !track.isValidTrack || !track.hasRealMusicAttribution) return
        val state = _playbackState.value
        val expectedToken = currentSessionToken ?: return
        if (!state.isPlaying || !state.hasActiveSession) return
        if (lastRecordedTrackKey == track.key) return

        val expectedGen = sessionGeneration
        val expectedKey = track.key
        lastRecordedTrackKey = expectedKey

        historyJob?.cancel()
        historyJob = scope.launch {
            if (!isActive ||
                sessionGeneration != expectedGen ||
                currentSessionToken != expectedToken ||
                !_playbackState.value.isPlaying ||
                _playbackState.value.track?.key != expectedKey
            ) {
                return@launch
            }
            repository.recordHistory(track, "Played")
        }
    }

    private fun updateActiveLyrics(currentPos: Long, offset: Long) {
        val lyrics = _currentLyrics.value
        if (lyrics != null && lyrics.hasValidLyrics && lyrics.isSynced) {
            val activeIdx = lyrics.findActiveIndex(currentPos, offset)
            val activeObj = if (activeIdx in lyrics.lines.indices) lyrics.lines[activeIdx] else null
            val lineText = activeObj?.text?.trim()
            val cleanText = sanitizeLyricLine(lineText)

            if (_activeLyricIndex.value != activeIdx) _activeLyricIndex.value = activeIdx
            if (_activeLyricObject.value != activeObj) _activeLyricObject.value = activeObj
            if (_activeLyricLine.value != cleanText) _activeLyricLine.value = cleanText
        } else if (lyrics != null && lyrics.hasValidLyrics && !lyrics.isSynced) {
            if (_activeLyricIndex.value != 0 && lyrics.lines.isNotEmpty()) {
                _activeLyricIndex.value = 0
            }
            val activeObj = lyrics.getActiveLyricLine(currentPos, offset)
            val cleanText = sanitizeLyricLine(activeObj?.text?.trim())

            if (_activeLyricObject.value != activeObj) _activeLyricObject.value = activeObj
            if (_activeLyricLine.value != cleanText) _activeLyricLine.value = cleanText
        } else {
            if (_activeLyricIndex.value != -1) _activeLyricIndex.value = -1
            if (_activeLyricObject.value != null) _activeLyricObject.value = null
            if (_activeLyricLine.value != null) _activeLyricLine.value = null
        }
    }

    private fun sanitizeLyricLine(lineText: String?): String? {
        if (lineText.isNullOrBlank()) return null
        if (lineText.equals("null", ignoreCase = true)) return null
        if (lineText.equals("undefined", ignoreCase = true)) return null
        return lineText
    }
}
