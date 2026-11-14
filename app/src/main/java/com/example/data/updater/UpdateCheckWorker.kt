package com.example.data.updater

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.first

class UpdateCheckWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefsRepo = UserPreferencesRepository(applicationContext)
        val prefs = prefsRepo.userPreferencesFlow.first()

        if (!prefs.autoUpdateChecksEnabled) {
            UpdateCheckScheduler.cancelPeriodicChecks(applicationContext)
            return Result.success()
        }

        val outcome = UpdateManager.getInstance(applicationContext)
            .performBackgroundUpdateCheck(trigger = UpdateCheckTrigger.WORKER)

        return mapOutcomeToWorkerResult(outcome, runAttemptCount)
    }

    companion object {
        const val MAX_RETRY_ATTEMPTS = 3

        fun mapOutcomeToWorkerResult(
            outcome: UpdateCheckOutcome,
            runAttemptCount: Int
        ): Result {
            return when (outcome) {
                is UpdateCheckOutcome.UpToDate,
                is UpdateCheckOutcome.NoStableRelease,
                UpdateCheckOutcome.SkippedDisabled,
                UpdateCheckOutcome.SkippedRecent -> Result.success()

                is UpdateCheckOutcome.UpdateAvailable -> {
                    when (outcome.notificationResult) {
                        is NotificationPostResult.Failed -> {
                            if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
                        }
                        NotificationPostResult.Posted,
                        NotificationPostResult.PermissionDenied,
                        null -> Result.success()
                    }
                }

                is UpdateCheckOutcome.Failure -> {
                    if (outcome.isRetryable && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                        Result.retry()
                    } else {
                        Result.failure()
                    }
                }
            }
        }
    }
}
