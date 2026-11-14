package com.example.data.updater

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

interface UpdateScheduleBackend {
    fun enqueueUniquePeriodic(
        uniqueWorkName: String,
        policy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest
    )

    fun cancelUnique(uniqueWorkName: String)
}

class WorkManagerScheduleBackend(private val context: Context) : UpdateScheduleBackend {
    override fun enqueueUniquePeriodic(
        uniqueWorkName: String,
        policy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest
    ) {
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(uniqueWorkName, policy, request)
    }

    override fun cancelUnique(uniqueWorkName: String) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(uniqueWorkName)
    }
}

object UpdateCheckScheduler {

    const val UPDATE_CHECK_INTERVAL_HOURS = 48L
    const val LAUNCH_CHECK_MIN_INTERVAL_MS = UPDATE_CHECK_INTERVAL_HOURS * 60L * 60L * 1000L
    const val UPDATE_CHECK_WORK_NAME = "lyricbar_auto_update_check"
    const val UPDATE_CHECK_WORK_TAG = "lyricbar_auto_update_tag"

    fun buildConstraints(): Constraints {
        return Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }

    fun buildPeriodicWorkRequest(): PeriodicWorkRequest {
        return PeriodicWorkRequestBuilder<UpdateCheckWorker>(
            UPDATE_CHECK_INTERVAL_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(buildConstraints())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .addTag(UPDATE_CHECK_WORK_TAG)
            .build()
    }

    fun syncPeriodicSchedule(context: Context, enabled: Boolean) {
        syncPeriodicSchedule(WorkManagerScheduleBackend(context), enabled)
    }

    fun syncPeriodicSchedule(backend: UpdateScheduleBackend, enabled: Boolean) {
        if (enabled) {
            backend.enqueueUniquePeriodic(
                uniqueWorkName = UPDATE_CHECK_WORK_NAME,
                policy = ExistingPeriodicWorkPolicy.KEEP,
                request = buildPeriodicWorkRequest()
            )
        } else {
            backend.cancelUnique(UPDATE_CHECK_WORK_NAME)
        }
    }

    fun schedulePeriodicChecks(context: Context) {
        syncPeriodicSchedule(context, enabled = true)
    }

    fun cancelPeriodicChecks(context: Context) {
        syncPeriodicSchedule(context, enabled = false)
    }

    fun shouldCheckOnAppLaunch(
        autoUpdateChecksEnabled: Boolean,
        lastSuccessfulCheckTimestampMs: Long,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (!autoUpdateChecksEnabled) return false
        if (lastSuccessfulCheckTimestampMs <= 0L) return true
        val elapsed = nowMs - lastSuccessfulCheckTimestampMs
        return elapsed >= LAUNCH_CHECK_MIN_INTERVAL_MS || elapsed < 0L
    }
}
