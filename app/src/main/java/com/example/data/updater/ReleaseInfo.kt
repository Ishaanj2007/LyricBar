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

    val isChecksumFile: Boolean
        get() = name.endsWith(".sha256", ignoreCase = true) ||
                name.endsWith(".sha256sum", ignoreCase = true) ||
                name.equals("SHA256SUMS", ignoreCase = true) ||
                name.equals("checksums.txt", ignoreCase = true)

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
    val sha256Checksum: String? = null,
    val allAssets: List<ReleaseAsset> = emptyList(),
    val assetChecksums: Map<String, String> = emptyMap()
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

    /**
     * Resolves the SHA-256 checksum strictly associated with the given [asset].
     * Never assigns another variant's or unrelated file's checksum.
     */
    fun getChecksumForAsset(asset: ReleaseAsset?): String? {
        if (asset == null) return null
        val directDigest = asset.digest?.trim()?.lowercase()
        if (directDigest != null && directDigest.length == 64 && directDigest.all { it in '0'..'9' || it in 'a'..'f' }) {
            return directDigest
        }
        val mapped = assetChecksums[asset.name.lowercase()]
        if (mapped != null && mapped.length == 64 && mapped.all { it in '0'..'9' || it in 'a'..'f' }) {
            return mapped
        }
        val apkAssets = allAssets.filter { it.isApk }
        if (apkAssets.size <= 1 && sha256Checksum != null && sha256Checksum.length == 64 && sha256Checksum.all { it in '0'..'9' || it in 'a'..'f' }) {
            return sha256Checksum
        }
        return null
    }
}
