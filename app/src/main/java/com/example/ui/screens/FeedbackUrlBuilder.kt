package com.example.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import com.example.BuildConfig
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class FeedbackTab(val title: String) {
    BUG_REPORT("Report a Bug"),
    FEATURE_REQUEST("Request a Feature")
}

object FeedbackUrlBuilder {

    const val GITHUB_REPO_URL = DeveloperConfig.SOURCE_CODE_URL
    const val GITHUB_NEW_ISSUE_URL = "$GITHUB_REPO_URL/issues/new"

    val BUG_CATEGORIES = listOf(
        "Playback / Media Detection",
        "Lyrics / Synchronization",
        "HUD / Overlay",
        "UI / Appearance",
        "Performance",
        "Other"
    )

    val FEATURE_CATEGORIES = listOf(
        "UI / Customization",
        "Lyrics",
        "HUD / Overlay",
        "Playback",
        "Performance",
        "Other"
    )

    fun mapBugCategoryToYaml(category: String): String = when (category) {
        "Playback / Media Detection" -> "Music Detection / Playback"
        "Lyrics / Synchronization" -> "Lyrics Synchronization"
        "HUD / Overlay" -> "Floating HUD / Overlay"
        "UI / Appearance" -> "App UI / Navigation"
        "Performance" -> "Other"
        else -> "Other"
    }

    fun mapFeatureCategoryToYaml(category: String): String = when (category) {
        "UI / Customization" -> "UI / Customization"
        "Lyrics" -> "Live Lyrics"
        "HUD / Overlay" -> "Floating HUD / Overlay"
        "Playback" -> "Now Playing"
        "Performance" -> "Performance"
        else -> "Other"
    }

    fun getSafeDeviceInfo(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }
        val model = Build.MODEL.orEmpty()
        val androidRelease = Build.VERSION.RELEASE.orEmpty()
        val sdk = Build.VERSION.SDK_INT
        return "$manufacturer $model, Android $androidRelease (API $sdk)".trim()
    }

    fun getSafeDiagnostics(): String {
        return buildString {
            appendLine("LyricBar Version: ${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${getSafeDeviceInfo()}")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS?.firstOrNull() ?: "Unknown"}")
        }.trim()
    }

    fun buildBugReportUrl(
        title: String,
        category: String,
        description: String,
        stepsToReproduce: String,
        expectedBehaviour: String,
        actualBehaviour: String,
        includeDiagnostics: Boolean = true,
        appVersion: String = BuildConfig.VERSION_NAME,
        deviceInfo: String = getSafeDeviceInfo(),
        diagnosticsText: String = getSafeDiagnostics()
    ): String {
        val cleanTitle = title.trim()
        val issueTitle = if (cleanTitle.startsWith("[Bug]:", ignoreCase = true)) cleanTitle else "[Bug]: $cleanTitle"
        val yamlCategory = mapBugCategoryToYaml(category)

        val whatHappened = buildString {
            appendLine("### Description")
            appendLine(description.trim())
            appendLine()
            appendLine("### Steps to Reproduce")
            appendLine(stepsToReproduce.trim())
            appendLine()
            appendLine("### Expected Behavior")
            appendLine(expectedBehaviour.trim())
            appendLine()
            appendLine("### Actual Behavior")
            appendLine(actualBehaviour.trim())
        }.trim()

        val fullBody = buildString {
            appendLine("### LyricBar version")
            appendLine(appVersion)
            appendLine()
            appendLine("### Device and Android version")
            appendLine(deviceInfo)
            appendLine()
            appendLine("### Which feature is affected?")
            appendLine(yamlCategory)
            appendLine()
            appendLine("### Describe the bug")
            appendLine(whatHappened)
            if (includeDiagnostics && diagnosticsText.isNotBlank()) {
                appendLine()
                appendLine("### Diagnostic log")
                appendLine("```")
                appendLine(diagnosticsText.trim())
                appendLine("```")
            }
        }.trim()

        val params = mutableListOf<Pair<String, String>>()
        params.add("template" to "bug_report.yml")
        params.add("title" to issueTitle)
        params.add("app-version" to appVersion)
        params.add("device" to deviceInfo)
        params.add("affected-feature" to yamlCategory)
        params.add("what-happened" to whatHappened)
        if (includeDiagnostics && diagnosticsText.isNotBlank()) {
            params.add("log" to diagnosticsText.trim())
        }
        params.add("body" to fullBody)

        return buildQueryUrl(GITHUB_NEW_ISSUE_URL, params)
    }

    fun buildFeatureRequestUrl(
        title: String,
        category: String,
        description: String,
        whyUseful: String,
        expectedBehaviour: String
    ): String {
        val cleanTitle = title.trim()
        val issueTitle = if (cleanTitle.startsWith("[Feature]:", ignoreCase = true)) cleanTitle else "[Feature]: $cleanTitle"
        val yamlCategory = mapFeatureCategoryToYaml(category)

        val fullBody = buildString {
            appendLine("### Which area does this feature relate to?")
            appendLine(yamlCategory)
            appendLine()
            appendLine("### What would you like LyricBar to do?")
            appendLine(description.trim())
            appendLine()
            appendLine("### Why would this feature be useful?")
            appendLine(whyUseful.trim())
            appendLine()
            appendLine("### Expected behavior")
            appendLine(expectedBehaviour.trim())
        }.trim()

        val params = mutableListOf<Pair<String, String>>()
        params.add("template" to "feature_request.yml")
        params.add("title" to issueTitle)
        params.add("category" to yamlCategory)
        params.add("what" to description.trim())
        params.add("why" to whyUseful.trim())
        params.add("body" to fullBody)

        return buildQueryUrl(GITHUB_NEW_ISSUE_URL, params)
    }

    private fun buildQueryUrl(baseUrl: String, params: List<Pair<String, String>>): String {
        val queryString = params.joinToString("&") { (key, value) ->
            val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8.name())
            val encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            "$encodedKey=$encodedValue"
        }
        return if (queryString.isNotBlank()) "$baseUrl?$queryString" else baseUrl
    }

    fun isNetworkAvailable(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            true
        }
    }

    fun openFeedbackUrl(context: Context, url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
