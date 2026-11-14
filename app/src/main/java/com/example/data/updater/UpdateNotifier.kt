package com.example.data.updater

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R

sealed interface NotificationPostResult {
    object Posted : NotificationPostResult
    object PermissionDenied : NotificationPostResult
    data class Failed(val cause: Throwable? = null) : NotificationPostResult
}

interface UpdateNotificationPoster {
    fun ensureNotificationChannel()
    fun canPostNotifications(): Boolean
    fun postUpdateAvailableNotification(release: ReleaseInfo): NotificationPostResult
}

class UpdateNotifier(private val context: Context) : UpdateNotificationPoster {

    private val appContext = context.applicationContext

    override fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = appContext.getString(R.string.update_channel_description)
                setShowBadge(true)
            }
            val manager = appContext.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun canPostNotifications(): Boolean {
        val runtimeGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        if (!runtimeGranted) return false
        return try {
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        } catch (_: Throwable) {
            false
        }
    }

    override fun postUpdateAvailableNotification(release: ReleaseInfo): NotificationPostResult {
        ensureNotificationChannel()
        if (!canPostNotifications()) {
            return NotificationPostResult.PermissionDenied
        }

        val launchIntent = createOpenUpdaterIntent(appContext, release.tagName)
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            NOTIFICATION_REQUEST_CODE,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = appContext.getString(R.string.update_notification_title)
        val message = formatNotificationMessage(appContext, release.cleanVersionName)

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_update)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()

        return try {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
            NotificationPostResult.Posted
        } catch (_: SecurityException) {
            NotificationPostResult.PermissionDenied
        } catch (t: Throwable) {
            NotificationPostResult.Failed(t)
        }
    }

    companion object {
        const val CHANNEL_ID = "lyricbar_app_updates"
        const val NOTIFICATION_ID = 4001
        const val NOTIFICATION_REQUEST_CODE = 4001
        const val ACTION_OPEN_UPDATER = "com.example.action.OPEN_UPDATER"
        const val EXTRA_OPEN_UPDATER = "extra_open_updater"
        const val EXTRA_UPDATE_VERSION_TAG = "extra_update_version_tag"

        fun formatNotificationMessage(versionName: String): String {
            val clean = versionName.trim().removePrefix("v").removePrefix("V")
            return "LyricBar v$clean is now available. Tap to see what's new."
        }

        fun formatNotificationMessage(context: Context, versionName: String): String {
            val clean = versionName.trim().removePrefix("v").removePrefix("V")
            return context.getString(R.string.update_notification_message, clean)
        }

        fun createOpenUpdaterIntent(context: Context, versionTag: String? = null): Intent {
            return Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_UPDATER
                putExtra(EXTRA_OPEN_UPDATER, true)
                if (!versionTag.isNullOrBlank()) {
                    putExtra(EXTRA_UPDATE_VERSION_TAG, versionTag)
                }
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }

        fun isOpenUpdaterIntent(intent: Intent?): Boolean {
            if (intent == null) return false
            return intent.action == ACTION_OPEN_UPDATER ||
                intent.getBooleanExtra(EXTRA_OPEN_UPDATER, false)
        }
    }
}
