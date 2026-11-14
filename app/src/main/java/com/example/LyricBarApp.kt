package com.example

import android.app.Application
import android.provider.Settings
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.updater.UpdateCheckScheduler
import com.example.data.updater.UpdateCheckTrigger
import com.example.data.updater.UpdateManager
import com.example.data.updater.UpdateNotifier
import com.example.service.MediaPlaybackManager
import com.example.service.OverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
        preferencesRepository = playbackManager.preferencesRepository

        // Run idempotent legacy preference migration and observe playback state for overlay
        appScope.launch {
            preferencesRepository.migrateLegacyPreferencesIfNeeded()
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

        // Ensure update notification channel, synchronize 48-hour WorkManager schedule,
        // and perform initial/catch-up update check when due.
        appScope.launch(Dispatchers.IO) {
            preferencesRepository.migrateLegacyPreferencesIfNeeded()
            val prefs = preferencesRepository.userPreferencesFlow.first()
            UpdateNotifier(this@LyricBarApp).ensureNotificationChannel()
            UpdateCheckScheduler.syncPeriodicSchedule(
                context = this@LyricBarApp,
                enabled = prefs.autoUpdateChecksEnabled
            )
            if (
                UpdateCheckScheduler.shouldCheckOnAppLaunch(
                    autoUpdateChecksEnabled = prefs.autoUpdateChecksEnabled,
                    lastSuccessfulCheckTimestampMs = prefs.lastUpdateCheckTimestampMs,
                    nowMs = System.currentTimeMillis()
                )
            ) {
                UpdateManager.getInstance(this@LyricBarApp)
                    .performBackgroundUpdateCheck(trigger = UpdateCheckTrigger.LAUNCH)
            }
        }
    }
}
