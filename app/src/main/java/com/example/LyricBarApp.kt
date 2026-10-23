package com.example

import android.app.Application
import android.provider.Settings
import com.example.data.preferences.UserPreferencesRepository
import com.example.service.MediaPlaybackManager
import com.example.service.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class LyricBarApp : Application() {

    private val appScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    lateinit var playbackManager: MediaPlaybackManager
        private set
    lateinit var preferencesRepository: UserPreferencesRepository
        private set

    override fun onCreate() {
        super.onCreate()
        playbackManager = MediaPlaybackManager.getInstance(this)
        preferencesRepository = UserPreferencesRepository(this)

        // Observe playback state to auto-launch overlay when enabled
        appScope.launch {
            combine(
                playbackManager.playbackState,
                preferencesRepository.userPreferencesFlow
            ) { state, prefs ->
                Pair(state, prefs)
            }.collect { (state, prefs) ->
                if (prefs.overlayEnabled && prefs.autoShowOnPlayback && state.isPlaying) {
                    if (Settings.canDrawOverlays(this@LyricBarApp)) {
                        OverlayService.start(this@LyricBarApp)
                    }
                }
            }
        }
    }
}
