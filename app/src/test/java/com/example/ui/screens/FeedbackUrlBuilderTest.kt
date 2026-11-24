package com.example.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class FeedbackUrlBuilderTest {

    @Test
    fun testBuildBugReportUrl_containsRequiredParameters() {
        val url = FeedbackUrlBuilder.buildBugReportUrl(
            title = "Overlay flickers on song change",
            category = "HUD / Overlay",
            description = "The HUD flickers violently when the next track begins.",
            stepsToReproduce = "1. Play a song\n2. Skip to next song",
            expectedBehaviour = "Smooth transition without flashing",
            actualBehaviour = "Screen blinks black",
            includeDiagnostics = true,
            appVersion = "2.7.0",
            deviceInfo = "Google Pixel 9, Android 15 (API 35)",
            diagnosticsText = "Sample Diagnostics Log"
        )

        assertTrue("URL must start with GitHub new issue endpoint", url.startsWith("https://github.com/ishaanj2007/LyricBar/issues/new?"))
        assertTrue("URL must specify bug_report.yml template", url.contains("template=bug_report.yml"))
        assertTrue("URL must contain properly mapped category", url.contains("affected-feature=Floating+HUD+%2F+Overlay") || url.contains("affected-feature=Floating%20HUD%20%2F%20Overlay"))
        assertTrue("URL must contain encoded app version", url.contains("app-version=2.7.0"))

        val decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8.name())
        assertTrue("Decoded URL must contain issue title with [Bug] tag", decodedUrl.contains("title=[Bug]: Overlay flickers on song change"))
        assertTrue("Decoded URL must contain steps to reproduce", decodedUrl.contains("1. Play a song\n2. Skip to next song"))
        assertTrue("Decoded URL must contain expected behaviour", decodedUrl.contains("Smooth transition without flashing"))
        assertTrue("Decoded URL must contain actual behaviour", decodedUrl.contains("Screen blinks black"))
        assertTrue("Decoded URL must contain diagnostics log", decodedUrl.contains("Sample Diagnostics Log"))
    }

    @Test
    fun testBuildBugReportUrl_withoutDiagnostics_omitsLog() {
        val url = FeedbackUrlBuilder.buildBugReportUrl(
            title = "Crash on launch",
            category = "Other",
            description = "App crashed immediately",
            stepsToReproduce = "Open app",
            expectedBehaviour = "Opens",
            actualBehaviour = "Crashes",
            includeDiagnostics = false,
            appVersion = "2.7.0",
            deviceInfo = "Test Device",
            diagnosticsText = "Secret Diagnostics"
        )

        val decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8.name())
        assertFalse("Must not contain diagnostics log when disabled", decodedUrl.contains("Secret Diagnostics"))
        assertFalse("Must not contain log parameter when disabled", url.contains("&log="))
    }

    @Test
    fun testBuildFeatureRequestUrl_containsRequiredParameters() {
        val url = FeedbackUrlBuilder.buildFeatureRequestUrl(
            title = "Add custom font upload",
            category = "UI / Customization",
            description = "Allow users to import TTF/OTF font files for the HUD.",
            whyUseful = "Personalization with localized typography.",
            expectedBehaviour = "File picker opens and font is previewed."
        )

        assertTrue("URL must start with GitHub new issue endpoint", url.startsWith("https://github.com/ishaanj2007/LyricBar/issues/new?"))
        assertTrue("URL must specify feature_request.yml template", url.contains("template=feature_request.yml"))

        val decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8.name())
        assertTrue("Decoded URL must contain issue title with [Feature] tag", decodedUrl.contains("title=[Feature]: Add custom font upload"))
        assertTrue("Decoded URL must contain description", decodedUrl.contains("Allow users to import TTF/OTF font files for the HUD."))
        assertTrue("Decoded URL must contain whyUseful", decodedUrl.contains("Personalization with localized typography."))
        assertTrue("Decoded URL must contain expected behaviour", decodedUrl.contains("File picker opens and font is previewed."))
    }

    @Test
    fun testCategoryMappings() {
        assertEquals("Music Detection / Playback", FeedbackUrlBuilder.mapBugCategoryToYaml("Playback / Media Detection"))
        assertEquals("Lyrics Synchronization", FeedbackUrlBuilder.mapBugCategoryToYaml("Lyrics / Synchronization"))
        assertEquals("Floating HUD / Overlay", FeedbackUrlBuilder.mapBugCategoryToYaml("HUD / Overlay"))
        assertEquals("App UI / Navigation", FeedbackUrlBuilder.mapBugCategoryToYaml("UI / Appearance"))
        assertEquals("Other", FeedbackUrlBuilder.mapBugCategoryToYaml("Performance"))
        assertEquals("Other", FeedbackUrlBuilder.mapBugCategoryToYaml("Other"))

        assertEquals("UI / Customization", FeedbackUrlBuilder.mapFeatureCategoryToYaml("UI / Customization"))
        assertEquals("Live Lyrics", FeedbackUrlBuilder.mapFeatureCategoryToYaml("Lyrics"))
        assertEquals("Floating HUD / Overlay", FeedbackUrlBuilder.mapFeatureCategoryToYaml("HUD / Overlay"))
        assertEquals("Now Playing", FeedbackUrlBuilder.mapFeatureCategoryToYaml("Playback"))
        assertEquals("Performance", FeedbackUrlBuilder.mapFeatureCategoryToYaml("Performance"))
        assertEquals("Other", FeedbackUrlBuilder.mapFeatureCategoryToYaml("Other"))
    }

    @Test
    fun testSpecialCharactersAndMultilineEncoding() {
        val trickyTitle = "Bug with & symbols, 'quotes', \"double quotes\" & <tags> 🎵"
        val trickyDesc = "Line 1: 100% test\nLine 2: a = b & c\nLine 3: emojis 🚀✨"

        val url = FeedbackUrlBuilder.buildBugReportUrl(
            title = trickyTitle,
            category = "Other",
            description = trickyDesc,
            stepsToReproduce = "Steps with #hashtags and ?params=1",
            expectedBehaviour = "Handles symbols",
            actualBehaviour = "Fails",
            includeDiagnostics = false,
            appVersion = "2.7.0",
            deviceInfo = "Device",
            diagnosticsText = ""
        )

        // URL must not contain raw unencoded newlines, ampersands in values, or spaces
        assertFalse("URL must not contain raw unencoded newlines", url.contains("\n"))
        assertFalse("URL must not contain raw unencoded spaces", url.contains(" "))

        val decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8.name())
        assertTrue("Decoded URL must preserve tricky title", decodedUrl.contains(trickyTitle))
        assertTrue("Decoded URL must preserve multiline description", decodedUrl.contains(trickyDesc))
    }

    @Test
    fun testPreExistingPrefixDoesNotDuplicate() {
        val urlBug = FeedbackUrlBuilder.buildBugReportUrl(
            title = "[Bug]: Already has prefix",
            category = "Other",
            description = "Desc",
            stepsToReproduce = "Steps",
            expectedBehaviour = "Exp",
            actualBehaviour = "Act",
            includeDiagnostics = false
        )
        val decodedBug = URLDecoder.decode(urlBug, StandardCharsets.UTF_8.name())
        assertTrue("Should not duplicate [Bug]: prefix", decodedBug.contains("title=[Bug]: Already has prefix"))
        assertFalse("Should not have double [Bug]: prefix", decodedBug.contains("[Bug]: [Bug]:"))

        val urlFeat = FeedbackUrlBuilder.buildFeatureRequestUrl(
            title = "[Feature]: Already has prefix",
            category = "Other",
            description = "Desc",
            whyUseful = "Why",
            expectedBehaviour = "Exp"
        )
        val decodedFeat = URLDecoder.decode(urlFeat, StandardCharsets.UTF_8.name())
        assertTrue("Should not duplicate [Feature]: prefix", decodedFeat.contains("title=[Feature]: Already has prefix"))
        assertFalse("Should not have double [Feature]: prefix", decodedFeat.contains("[Feature]: [Feature]:"))
    }
}
