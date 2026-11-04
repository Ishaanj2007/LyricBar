package com.example.data.updater

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val contentType: String,
    val digest: String? = null
) {
    val isApk: Boolean
        get() = name.endsWith(".apk", ignoreCase = true) ||
                contentType.equals("application/vnd.android.package-archive", ignoreCase = true)

    val formattedSize: String
        get() {
            if (sizeBytes <= 0) return ""
            val mb = sizeBytes / (1024.0 * 1024.0)
            return String.format(Locale.US, "%.1f MB", mb)
        }
}

data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val publishedAt: String,
    val body: String,
    val htmlUrl: String,
    val isDraft: Boolean,
    val isPrerelease: Boolean,
    val apkAsset: ReleaseAsset? = null,
    val sha256Checksum: String? = null
) {
    val cleanVersionName: String
        get() = tagName.trim().removePrefix("v").removePrefix("V")

    val formattedPublishDate: String
        get() {
            if (publishedAt.isBlank()) return ""
            return try {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val date = inputFormat.parse(publishedAt) ?: return publishedAt
                val outputFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                outputFormat.format(date)
            } catch (_: Exception) {
                publishedAt.take(10)
            }
        }
}
