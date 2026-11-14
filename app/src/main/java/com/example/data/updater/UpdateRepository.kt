package com.example.data.updater

import android.os.Build
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

sealed interface UpdateFetchResult {
    data class ReleaseFound(
        val release: ReleaseInfo,
        val eTag: String?,
        val rawJson: String?,
        val fromNotModifiedCache: Boolean
    ) : UpdateFetchResult

    data class NoStableRelease(val reason: String) : UpdateFetchResult

    data class NetworkOrServerFailure(
        val message: String,
        val isRetryable: Boolean,
        val cause: Throwable? = null
    ) : UpdateFetchResult
}

open class UpdateRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val owner: String = "Ishaanj2007",
    private val repo: String = "LyricBar",
    private val baseApiUrl: String = "https://api.github.com"
) {

    private val trimmedBaseUrl: String
        get() = baseApiUrl.trimEnd('/')

    private val latestReleaseUrl: String
        get() = "$trimmedBaseUrl/repos/$owner/$repo/releases/latest"

    private val allReleasesUrl: String
        get() = "$trimmedBaseUrl/repos/$owner/$repo/releases"

    private val userAgentValue: String
        get() = try {
            "LyricBar-Updater/${com.example.BuildConfig.VERSION_NAME}"
        } catch (_: Throwable) {
            "LyricBar-Updater"
        }

    open suspend fun fetchLatestRelease(): Result<ReleaseInfo> {
        return when (val result = fetchLatestStableRelease()) {
            is UpdateFetchResult.ReleaseFound -> Result.success(result.release)
            is UpdateFetchResult.NoStableRelease -> Result.failure(IllegalStateException(result.reason))
            is UpdateFetchResult.NetworkOrServerFailure -> {
                Result.failure(result.cause ?: IOException(result.message))
            }
        }
    }

    open suspend fun fetchLatestStableRelease(
        cachedEtag: String? = null,
        cachedReleaseJson: String? = null
    ): UpdateFetchResult = withContext(Dispatchers.IO) {
        fetchLatestInternal(
            cachedEtag = cachedEtag,
            cachedReleaseJson = cachedReleaseJson,
            allowCacheFallbackRetry = true
        )
    }

    private fun fetchLatestInternal(
        cachedEtag: String?,
        cachedReleaseJson: String?,
        allowCacheFallbackRetry: Boolean
    ): UpdateFetchResult {
        return try {
            val usableEtag = cachedEtag?.trim()?.takeIf { it.isNotEmpty() && !cachedReleaseJson.isNullOrBlank() }
            val requestBuilder = Request.Builder()
                .url(latestReleaseUrl)
                .header("User-Agent", userAgentValue)
                .header("Accept", "application/vnd.github.v3+json")
            if (usableEtag != null) {
                requestBuilder.header("If-None-Match", usableEtag)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                when (response.code) {
                    304 -> {
                        val cachedRelease = parseCachedReleaseOrNull(cachedReleaseJson)
                        if (cachedRelease != null && isValidStableReleaseWithApk(cachedRelease)) {
                            UpdateFetchResult.ReleaseFound(
                                release = cachedRelease,
                                eTag = usableEtag,
                                rawJson = cachedReleaseJson,
                                fromNotModifiedCache = true
                            )
                        } else if (allowCacheFallbackRetry) {
                            // Cached JSON is missing or stale/corrupt; re-fetch unconditionally once
                            fetchLatestInternal(
                                cachedEtag = null,
                                cachedReleaseJson = null,
                                allowCacheFallbackRetry = false
                            )
                        } else {
                            UpdateFetchResult.NetworkOrServerFailure(
                                message = "Cached release metadata was invalid after 304 Not Modified.",
                                isRetryable = false
                            )
                        }
                    }
                    200 -> {
                        val bodyString = response.body?.string()
                        if (bodyString.isNullOrBlank()) {
                            return UpdateFetchResult.NetworkOrServerFailure(
                                message = "Empty response from GitHub API.",
                                isRetryable = true
                            )
                        }
                        val parsedObj = try {
                            val elem = JsonParser.parseString(bodyString)
                            if (!elem.isJsonObject) {
                                return UpdateFetchResult.NetworkOrServerFailure(
                                    message = "Malformed release metadata returned by GitHub.",
                                    isRetryable = false
                                )
                            }
                            elem.asJsonObject
                        } catch (e: Exception) {
                            return UpdateFetchResult.NetworkOrServerFailure(
                                message = "Invalid JSON received from GitHub Releases API.",
                                isRetryable = false,
                                cause = e
                            )
                        }

                        val release = parseReleaseJson(parsedObj)
                        val responseEtag = response.header("ETag")?.trim()?.takeIf { it.isNotEmpty() }

                        // Strict check: accept only stable (non-draft, non-prerelease) releases with valid metadata & compatible APK
                        if (isValidStableReleaseWithApk(release)) {
                            UpdateFetchResult.ReleaseFound(
                                release = release,
                                eTag = responseEtag,
                                rawJson = bodyString,
                                fromNotModifiedCache = false
                            )
                        } else {
                            // /releases/latest was draft, prerelease, malformed, or lacked an APK -> fall back to /releases
                            fetchFromReleasesList()
                        }
                    }
                    403, 429 -> {
                        UpdateFetchResult.NetworkOrServerFailure(
                            message = "GitHub API rate limit exceeded. Please wait a few moments and try again.",
                            isRetryable = true
                        )
                    }
                    404 -> {
                        // Fallback to querying the releases list in case "latest" pointer is not set
                        fetchFromReleasesList()
                    }
                    in 500..599 -> {
                        UpdateFetchResult.NetworkOrServerFailure(
                            message = "GitHub server returned HTTP ${response.code}.",
                            isRetryable = true
                        )
                    }
                    else -> {
                        UpdateFetchResult.NetworkOrServerFailure(
                            message = "GitHub server returned HTTP ${response.code}.",
                            isRetryable = false
                        )
                    }
                }
            }
        } catch (e: IOException) {
            UpdateFetchResult.NetworkOrServerFailure(
                message = "Network error checking for updates: ${e.localizedMessage ?: "Connection failed"}",
                isRetryable = true,
                cause = e
            )
        } catch (e: Exception) {
            UpdateFetchResult.NetworkOrServerFailure(
                message = e.localizedMessage?.takeIf { it.isNotBlank() } ?: "Unexpected error checking for updates.",
                isRetryable = false,
                cause = e
            )
        }
    }

    private fun fetchFromReleasesList(): UpdateFetchResult {
        val request = Request.Builder()
            .url(allReleasesUrl)
            .header("User-Agent", userAgentValue)
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val isRateLimit = response.code == 403 || response.code == 429
                val isRetryable = isRateLimit || response.code in 500..599
                val errorMsg = if (isRateLimit) {
                    "GitHub API rate limit exceeded. Please wait a few moments and try again."
                } else {
                    "GitHub returned HTTP ${response.code} fetching releases list"
                }
                return UpdateFetchResult.NetworkOrServerFailure(
                    message = errorMsg,
                    isRetryable = isRetryable
                )
            }
            val bodyString = response.body?.string()
            if (bodyString.isNullOrBlank()) {
                return UpdateFetchResult.NetworkOrServerFailure(
                    message = "Empty response from GitHub releases list",
                    isRetryable = true
                )
            }

            val jsonArray = try {
                val elem = JsonParser.parseString(bodyString)
                if (!elem.isJsonArray) {
                    return UpdateFetchResult.NetworkOrServerFailure(
                        message = "Malformed releases list returned by GitHub.",
                        isRetryable = false
                    )
                }
                elem.asJsonArray
            } catch (e: Exception) {
                return UpdateFetchResult.NetworkOrServerFailure(
                    message = "Invalid JSON received from GitHub releases list.",
                    isRetryable = false,
                    cause = e
                )
            }

            var bestPair: Pair<ReleaseInfo, String>? = null
            for (element in jsonArray) {
                if (!element.isJsonObject) continue
                val item = element.asJsonObject
                val release = parseReleaseJson(item)
                if (!isValidStableReleaseWithApk(release)) continue

                val currentBest = bestPair
                if (currentBest == null || VersionComparator.compare(release.tagName, currentBest.first.tagName) > 0) {
                    bestPair = release to item.toString()
                }
            }

            if (bestPair != null) {
                UpdateFetchResult.ReleaseFound(
                    release = bestPair.first,
                    eTag = null,
                    rawJson = bestPair.second,
                    fromNotModifiedCache = false
                )
            } else {
                UpdateFetchResult.NoStableRelease(
                    reason = "No published stable release with a compatible APK asset found"
                )
            }
        }
    }

    fun parseCachedReleaseOrNull(rawJson: String?): ReleaseInfo? {
        if (rawJson.isNullOrBlank()) return null
        return try {
            val elem = JsonParser.parseString(rawJson)
            if (!elem.isJsonObject) return null
            val parsed = parseReleaseJson(elem.asJsonObject)
            if (isValidStableReleaseWithApk(parsed)) parsed else null
        } catch (_: Exception) {
            null
        }
    }

    internal fun isValidStableReleaseWithApk(release: ReleaseInfo): Boolean {
        if (release.isDraft || release.isPrerelease) return false
        val tag = release.tagName.trim()
        if (tag.isEmpty() || tag.none { it.isDigit() }) return false
        val asset = release.apkAsset ?: return false
        val url = asset.downloadUrl.trim()
        return url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)
    }

    suspend fun fetchCompanionChecksum(companionAsset: ReleaseAsset, targetApkName: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(companionAsset.downloadUrl)
                .header("User-Agent", "LyricBar-Updater/${com.example.BuildConfig.VERSION_NAME}")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val text = response.body?.string() ?: return@withContext null
                parseChecksumFromText(text, targetApkName)
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseChecksumFromText(text: String, targetApkName: String? = null): String? {
        val hex64Pattern = Pattern.compile("([a-fA-F0-9]{64})")
        if (!targetApkName.isNullOrBlank()) {
            val cleanName = targetApkName.trim().lowercase()
            for (line in text.lines()) {
                val lowerLine = line.lowercase()
                if (lowerLine.contains(cleanName)) {
                    val matcher = hex64Pattern.matcher(line)
                    if (matcher.find()) {
                        return matcher.group(1)?.lowercase()
                    }
                }
            }
        }
        val trimmed = text.trim()
        val matcher = hex64Pattern.matcher(trimmed)
        if (matcher.find()) {
            return matcher.group(1)?.lowercase()
        }
        return null
    }

    fun selectBestApkAsset(apkAssets: List<ReleaseAsset>, supportedAbis: Array<String>? = null): ReleaseAsset? {
        if (apkAssets.isEmpty()) return null
        if (apkAssets.size == 1) return apkAssets.first()

        val resolvedAbis = supportedAbis ?: try {
            Build.SUPPORTED_ABIS
        } catch (_: Throwable) {
            null
        } ?: arrayOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

        // 1. Look for asset specifically matching primary supported ABI
        for (abi in resolvedAbis) {
            val abiMatch = apkAssets.firstOrNull { asset ->
                asset.name.contains(abi, ignoreCase = true)
            }
            if (abiMatch != null) return abiMatch
        }

        // 2. Look for universal or fat APK
        val universal = apkAssets.firstOrNull { asset ->
            asset.name.contains("universal", ignoreCase = true) ||
            asset.name.contains("fat", ignoreCase = true) ||
            asset.name.contains("all", ignoreCase = true)
        }
        if (universal != null) return universal

        // 3. Look for APK that does not contain incompatible foreign ABIs in its name
        val knownForeignAbis = listOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86", "mips")
        val neutralApk = apkAssets.firstOrNull { asset ->
            val lower = asset.name.lowercase()
            knownForeignAbis.none { lower.contains(it) }
        }
        if (neutralApk != null) return neutralApk

        // 4. Default to first APK
        return apkAssets.first()
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
        val checksumMap = mutableMapOf<String, String>()

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
                        val candidate = rawDigest.removePrefix("sha256:").trim().lowercase()
                        if (candidate.length == 64 && candidate.all { it in '0'..'9' || it in 'a'..'f' }) {
                            candidate
                        } else null
                    } else null

                    val asset = ReleaseAsset(
                        name = assetName,
                        downloadUrl = downloadUrl,
                        sizeBytes = sizeBytes,
                        contentType = contentType,
                        digest = cleanDigest
                    )
                    assetsList.add(asset)

                    if (cleanDigest != null) {
                        checksumMap[assetName.lowercase()] = cleanDigest
                    }
                }
            }
        }

        val apkAssets = assetsList.filter { it.isApk }
        val apkAsset = selectBestApkAsset(apkAssets)

        // Parse checksums from markdown release body
        if (body.isNotBlank()) {
            // Pattern 1: <sha256> <filename>
            val shaFirstPattern = Pattern.compile("(?i)([a-f0-9]{64})\\s+[*]?([a-zA-Z0-9._-]+)")
            val m1 = shaFirstPattern.matcher(body)
            while (m1.find()) {
                val hash = m1.group(1)?.lowercase()
                val filename = m1.group(2)?.lowercase()
                if (hash != null && filename != null) {
                    checksumMap[filename] = hash
                }
            }

            // Pattern 2: <filename> : <sha256> or <filename> | <sha256> or <filename> - <sha256>
            val fileFirstPattern = Pattern.compile("(?i)([a-zA-Z0-9._-]+\\.apk)\\s*[:\\-|]\\s*([a-f0-9]{64})")
            val m2 = fileFirstPattern.matcher(body)
            while (m2.find()) {
                val filename = m2.group(1)?.lowercase()
                val hash = m2.group(2)?.lowercase()
                if (filename != null && hash != null) {
                    checksumMap[filename] = hash
                }
            }

            // Pattern 3: Table row | <filename> | <sha256> |
            val tablePattern = Pattern.compile("(?i)\\|\\s*([a-zA-Z0-9._-]+\\.apk)\\s*\\|\\s*([a-f0-9]{64})\\s*\\|")
            val m3 = tablePattern.matcher(body)
            while (m3.find()) {
                val filename = m3.group(1)?.lowercase()
                val hash = m3.group(2)?.lowercase()
                if (filename != null && hash != null) {
                    checksumMap[filename] = hash
                }
            }
        }

        // Global checksum fallback: ONLY when there is exactly 1 APK asset and no conflicting variant checksums
        var globalChecksum: String? = null
        if (apkAsset != null) {
            globalChecksum = checksumMap[apkAsset.name.lowercase()]
        }
        if (globalChecksum == null && apkAssets.size == 1 && body.isNotBlank()) {
            val genericPattern = Pattern.compile("(?i)(?:sha[-_]?256|checksum)[^a-f0-9]*([a-f0-9]{64})")
            val m = genericPattern.matcher(body)
            if (m.find()) {
                val extracted = m.group(1)?.lowercase()
                globalChecksum = extracted
                if (extracted != null && apkAsset != null) {
                    checksumMap[apkAsset.name.lowercase()] = extracted
                }
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
            sha256Checksum = globalChecksum,
            allAssets = assetsList,
            assetChecksums = checksumMap
        )
    }
}
