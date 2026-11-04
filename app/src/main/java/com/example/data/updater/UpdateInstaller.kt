package com.example.data.updater

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

sealed interface InstallationResult {
    object Launched : InstallationResult
    data class PermissionRequired(val settingsIntent: Intent) : InstallationResult
    data class Failure(val message: String) : InstallationResult
}

object UpdateInstaller {

    /**
     * Checks whether the app currently has permission to install packages from unknown sources.
     * Returns true on pre-Oreo (where installation is handled globally by system prompt).
     */
    fun canRequestPackageInstalls(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * Returns the Intent to navigate to the system Settings screen allowing this app
     * to install unknown apps.
     */
    fun createManageUnknownAppSourcesIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Launches the official Android package installer for the verified APK file.
     */
    fun installApk(context: Context, apkFile: File): InstallationResult {
        if (!apkFile.exists() || apkFile.length() <= 0) {
            return InstallationResult.Failure("APK file is missing or empty.")
        }

        // On Android 8.0+, verify unknown app installation permission
        if (!canRequestPackageInstalls(context)) {
            val settingsIntent = createManageUnknownAppSourcesIntent(context)
            return InstallationResult.PermissionRequired(settingsIntent)
        }

        return try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = createInstallIntent(apkUri)
            context.startActivity(installIntent)
            InstallationResult.Launched
        } catch (e: ActivityNotFoundException) {
            InstallationResult.Failure("No application package installer found on this device.")
        } catch (e: Exception) {
            InstallationResult.Failure("Failed to launch package installer: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    const val INSTALL_ACTION = "android.intent.action.VIEW"
    const val INSTALL_MIME_TYPE = "application/vnd.android.package-archive"

    /**
     * Constructs the standard Android package archive installation Intent.
     */
    fun createInstallIntent(apkUri: Uri): Intent {
        return Intent(INSTALL_ACTION).apply {
            setDataAndType(apkUri, INSTALL_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
