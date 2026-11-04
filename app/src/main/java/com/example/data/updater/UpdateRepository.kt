package com.example.data.updater

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class UpdateRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val owner: String = "Ishaanj2007",
    private val repo: String = "LyricBar"
) {

    private val latestReleaseUrl: String
        get() = "https://api.github.com/repos/$owner/$repo/releases/latest"

    private val allReleasesUrl: String
        get() = "https://api.github.com/repos/$owner/$repo/releases"

    suspend fun fetchLatestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            // First attempt: fetch /releases/latest (GitHub automatically points to the latest non-prerelease, non-draft)
            val request = Request.Builder()
                .url(latestReleaseUrl)
                .header("User-Agent", "LyricBar-Updater/1.9.0")
                .header("Accept", "application/vnd.github.v3+json")
                .build()

            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> {
                        val bodyString = response.body?.string()
                            ?: return@withContext Result.failure(IOException("Empty response from GitHub API"))
                        val json = JsonParser.parseString(bodyString).asJsonObject
                        val release = parseReleaseJson(json)
                        return@withContext Result.success(release)
                    }
                    403, 429 -> {
                        return@withContext Result.failure(
                            IOException("GitHub API rate limit exceeded. Please wait a few moments and try again.")
                        )
                    }
                    404 -> {
                        // Fallback to querying the releases list in case "latest" pointer is not set
                        return@withContext fetchFromReleasesList()
                    }
                    else -> {
                        return@withContext Result.failure(
                            IOException("GitHub server returned HTTP ${response.code}")
                        )
                    }
                }
            }
        } catch (e: IOException) {
            Result.failure(IOException("Network error checking for updates: ${e.localizedMessage ?: "Connection failed"}", e))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchFromReleasesList(): Result<ReleaseInfo> {
        val request = Request.Builder()
            .url(allReleasesUrl)
            .header("User-Agent", "LyricBar-Updater/1.9.0")
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return Result.failure(IOException("GitHub returned HTTP ${response.code} fetching releases list"))
            }
            val bodyString = response.body?.string()
                ?: return Result.failure(IOException("Empty response from GitHub releases list"))

            val jsonArray = JsonParser.parseString(bodyString).asJsonArray
            for (element in jsonArray) {
                if (element.isJsonObject) {
                    val item = element.asJsonObject
                    val isDraft = item.get("draft")?.asBoolean ?: false
                    val isPrerelease = item.get("prerelease")?.asBoolean ?: false
                    if (!isDraft && !isPrerelease) {
                        val release = parseReleaseJson(item)
                        if (release.apkAsset != null) {
                            return Result.success(release)
                        }
                    }
                }
            }
            return Result.failure(IllegalStateException("No published stable release with a compatible APK asset found"))
        }
    }

    internal fun parseReleaseJson(json: JsonObject): ReleaseInfo {
        val tagName = json.get("tag_name")?.asString ?: ""
        val name = json.get("name")?.asString ?: tagName
        val publishedAt = json.get("published_at")?.asString ?: ""
        val body = json.get("body")?.asString ?: ""
        val htmlUrl = json.get("html_url")?.asString ?: ""
        val isDraft = json.get("draft")?.asBoolean ?: false
        val isPrerelease = json.get("prerelease")?.asBoolean ?: false

        val assetsArray = json.getAsJsonArray("assets")
        val assetsList = mutableListOf<ReleaseAsset>()

        var extractedDigest: String? = null

        if (assetsArray != null) {
            for (element in assetsArray) {
                if (element.isJsonObject) {
                    val assetObj = element.asJsonObject
                    val assetName = assetObj.get("name")?.asString ?: ""
                    val downloadUrl = assetObj.get("browser_download_url")?.asString ?: ""
                    val sizeBytes = assetObj.get("size")?.asLong ?: 0L
                    val contentType = assetObj.get("content_type")?.asString ?: ""
                    val rawDigest = assetObj.get("digest")?.asString ?: ""

                    val cleanDigest = if (rawDigest.isNotBlank()) {
                        rawDigest.removePrefix("sha256:").trim().lowercase()
                    } else null

                    val asset = ReleaseAsset(
                        name = assetName,
                        downloadUrl = downloadUrl,
                        sizeBytes = sizeBytes,
                        contentType = contentType,
                        digest = cleanDigest
                    )
                    assetsList.add(asset)

                    if (asset.isApk && cleanDigest != null && extractedDigest == null) {
                        extractedDigest = cleanDigest
                    }
                }
            }
        }

        // Prefer compatible APK asset
        val apkAsset = assetsList.firstOrNull { it.isApk }

        // If asset digest is missing, check if SHA-256 hash was noted in markdown release body
        if (extractedDigest == null && body.isNotBlank()) {
            val sha256Pattern = Pattern.compile("(?i)(?:sha[-_]?256|checksum)[^a-f0-9]*([a-f0-9]{64})")
            val matcher = sha256Pattern.matcher(body)
            if (matcher.find()) {
                extractedDigest = matcher.group(1)?.lowercase()
            }
        }

        return ReleaseInfo(
            tagName = tagName,
            name = name,
            publishedAt = publishedAt,
            body = body,
            htmlUrl = htmlUrl,
            isDraft = isDraft,
            isPrerelease = isPrerelease,
            apkAsset = apkAsset,
            sha256Checksum = extractedDigest
        )
    }
}
