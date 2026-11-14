package com.example.data.updater

import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class UpdateCheckTrigger {
    MANUAL,
    LAUNCH,
    WORKER
}

sealed interface UpdateCheckOutcome {
    data class UpdateAvailable(
        val release: ReleaseInfo,
        val installedVersion: String,
        val notificationResult: NotificationPostResult?,
        val notifiedNow: Boolean
    ) : UpdateCheckOutcome

    data class UpToDate(
        val installedVersion: String,
        val checkedAtMs: Long
    ) : UpdateCheckOutcome

    data class NoStableRelease(
        val reason: String,
        val checkedAtMs: Long
    ) : UpdateCheckOutcome

    data class Failure(
        val message: String,
        val isRetryable: Boolean
    ) : UpdateCheckOutcome

    object SkippedDisabled : UpdateCheckOutcome
    object SkippedRecent : UpdateCheckOutcome
}

class UpdateCheckEngine(
    private val repository: UpdateRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val notifier: UpdateNotificationPoster,
    private val installedVersionProvider: () -> String,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {

    private val checkMutex = Mutex()

    suspend fun performCheck(
        trigger: UpdateCheckTrigger,
        allowRecentSkip: Boolean = (trigger != UpdateCheckTrigger.MANUAL)
    ): UpdateCheckOutcome = checkMutex.withLock {
        val prefs = preferencesRepository.userPreferencesFlow.first()
        val now = clockMs()

        if (trigger != UpdateCheckTrigger.MANUAL && !prefs.autoUpdateChecksEnabled) {
            return@withLock UpdateCheckOutcome.SkippedDisabled
        }

        if (
            trigger != UpdateCheckTrigger.MANUAL &&
            allowRecentSkip &&
            prefs.lastUpdateCheckTimestampMs > 0L
        ) {
            val elapsed = now - prefs.lastUpdateCheckTimestampMs
            if (elapsed in 0 until RECENT_CHECK_DEBOUNCE_MS) {
                return@withLock UpdateCheckOutcome.SkippedRecent
            }
        }

        when (
            val fetchResult = repository.fetchLatestStableRelease(
                cachedEtag = prefs.lastReleaseEtag,
                cachedReleaseJson = prefs.cachedLatestReleaseJson
            )
        ) {
            is UpdateFetchResult.ReleaseFound -> {
                preferencesRepository.setLastUpdateCheckTimestampMs(now)
                if (!fetchResult.fromNotModifiedCache && fetchResult.rawJson != null) {
                    preferencesRepository.saveCachedReleaseMetadata(
                        etag = fetchResult.eTag,
                        rawJson = fetchResult.rawJson
                    )
                }

                val release = fetchResult.release
                val installed = installedVersionProvider()
                val isNewer = VersionComparator.isNewer(
                    remoteVersion = release.tagName,
                    installedVersion = installed
                )

                if (isNewer && release.apkAsset != null) {
                    val lastNotified = prefs.lastNotifiedUpdateVersion
                    val shouldNotify = shouldNotifyForVersion(
                        candidateVersion = release.cleanVersionName,
                        lastNotifiedVersion = lastNotified
                    )

                    val postResult = if (shouldNotify) {
                        notifier.postUpdateAvailableNotification(release).also { res ->
                            // Only record notification version AFTER confirming the notification posted.
                            if (res is NotificationPostResult.Posted) {
                                preferencesRepository.setLastNotifiedUpdateVersion(release.cleanVersionName)
                            }
                        }
                    } else {
                        null
                    }

                    UpdateCheckOutcome.UpdateAvailable(
                        release = release,
                        installedVersion = installed,
                        notificationResult = postResult,
                        notifiedNow = postResult is NotificationPostResult.Posted
                    )
                } else if (isNewer) {
                    UpdateCheckOutcome.Failure(
                        message = "A new version (${release.tagName}) was found on GitHub, but no compatible APK asset was provided in this release.",
                        isRetryable = false
                    )
                } else {
                    UpdateCheckOutcome.UpToDate(
                        installedVersion = installed,
                        checkedAtMs = now
                    )
                }
            }

            is UpdateFetchResult.NoStableRelease -> {
                preferencesRepository.setLastUpdateCheckTimestampMs(now)
                UpdateCheckOutcome.NoStableRelease(
                    reason = fetchResult.reason,
                    checkedAtMs = now
                )
            }

            is UpdateFetchResult.NetworkOrServerFailure -> {
                // Do not advance lastUpdateCheckTimestampMs on transient/network failure
                UpdateCheckOutcome.Failure(
                    message = fetchResult.message,
                    isRetryable = fetchResult.isRetryable
                )
            }
        }
    }

    suspend fun loadCachedAvailableReleaseOrNull(): ReleaseInfo? {
        val prefs = preferencesRepository.userPreferencesFlow.first()
        val cached = repository.parseCachedReleaseOrNull(prefs.cachedLatestReleaseJson) ?: return null
        val installed = installedVersionProvider()
        val isNewer = VersionComparator.isNewer(cached.tagName, installed)
        return if (isNewer && cached.apkAsset != null) cached else null
    }

    companion object {
        // Prevents immediate duplicate checks when app launch check and initial periodic worker run together
        const val RECENT_CHECK_DEBOUNCE_MS = 5L * 60L * 1000L

        fun shouldNotifyForVersion(candidateVersion: String, lastNotifiedVersion: String?): Boolean {
            if (candidateVersion.isBlank()) return false
            if (lastNotifiedVersion.isNullOrBlank()) return true
            return VersionComparator.isNewer(
                remoteVersion = candidateVersion,
                installedVersion = lastNotifiedVersion
            )
        }
    }
}
