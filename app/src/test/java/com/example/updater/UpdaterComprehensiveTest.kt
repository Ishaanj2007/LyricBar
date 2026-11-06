package com.example.updater

import com.example.data.updater.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UpdaterComprehensiveTest {

    @Test
    fun testVersionComparisonNumericOrdering() {
        // "1.10.0" vs "1.9.0" - alphabetical comparison fails this, numerical comparison must succeed
        assertTrue("1.10.0 must be newer than 1.9.0", VersionComparator.isNewer("1.10.0", "1.9.0"))
        assertFalse("1.9.0 must not be newer than 1.10.0", VersionComparator.isNewer("1.9.0", "1.10.0"))

        // Prefix handling
        assertTrue("v2.0.0 must be newer than 1.9.0", VersionComparator.isNewer("v2.0.0", "1.9.0"))
        assertTrue("V2.0.0 must be newer than v1.9.0", VersionComparator.isNewer("V2.0.0", "v1.9.0"))

        // Identical versions
        assertFalse("1.9.0 is not newer than 1.9.0", VersionComparator.isNewer("1.9.0", "1.9.0"))
        assertFalse("v1.9.0 is not newer than 1.9.0", VersionComparator.isNewer("v1.9.0", "1.9.0"))
        assertEquals("v1.9.0 and 1.9.0 are equal", 0, VersionComparator.compare("v1.9.0", "1.9.0"))

        // Patch increments
        assertTrue("1.9.1 must be newer than 1.9.0", VersionComparator.isNewer("1.9.1", "1.9.0"))
        assertTrue("1.9.0.1 must be newer than 1.9.0", VersionComparator.isNewer("1.9.0.1", "1.9.0"))

        // Major increments
        assertTrue("2.0.0 must be newer than 1.9.9", VersionComparator.isNewer("2.0.0", "1.9.9"))
        assertTrue("2.1.0 must be newer than 2.0.0", VersionComparator.isNewer("2.1.0", "2.0.0"))
        assertTrue("v2.1.0 must be newer than 2.0.0", VersionComparator.isNewer("v2.1.0", "2.0.0"))

        // Older versions
        assertFalse("1.8.9 is not newer than 1.9.0", VersionComparator.isNewer("1.8.9", "1.9.0"))
        assertFalse("0.9.5 is not newer than 1.9.0", VersionComparator.isNewer("0.9.5", "1.9.0"))

        // Pre-release versions: final release is newer than pre-release
        assertTrue("1.9.0 is newer than 1.9.0-beta1", VersionComparator.isNewer("1.9.0", "1.9.0-beta1"))
        assertTrue("1.9.0 is newer than 1.9.0-rc2", VersionComparator.isNewer("1.9.0", "1.9.0-rc2"))
    }

    @Test
    fun testReleaseMetadataParsing() {
        val sampleJson = JsonObject().apply {
            addProperty("tag_name", "v2.0.0")
            addProperty("name", "LyricBar V2.0.0 — Major Refresh")
            addProperty("published_at", "2026-11-04T12:00:00Z")
            addProperty("body", "## New Features\n- Floating HUD optimizations\n- SHA-256: 62a7c08a8a5f89dbdf6e6b02cd6ea749c1a0e9cbadd8f256a5b0d817d09b8b8f")
            addProperty("html_url", "https://github.com/Ishaanj2007/LyricBar/releases/tag/v2.0.0")
            addProperty("draft", false)
            addProperty("prerelease", false)

            val assets = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("name", "LyricBar-2.0.0.apk")
                    addProperty("browser_download_url", "https://github.com/Ishaanj2007/LyricBar/releases/download/v2.0.0/LyricBar-2.0.0.apk")
                    addProperty("size", 19500000L)
                    addProperty("content_type", "application/vnd.android.package-archive")
                    addProperty("digest", "sha256:62a7c08a8a5f89dbdf6e6b02cd6ea749c1a0e9cbadd8f256a5b0d817d09b8b8f")
                })
            }
            add("assets", assets)
        }

        val repository = UpdateRepository()
        val release = repository.parseReleaseJson(sampleJson)

        assertEquals("v2.0.0", release.tagName)
        assertEquals("2.0.0", release.cleanVersionName)
        assertEquals("LyricBar V2.0.0 — Major Refresh", release.name)
        assertFalse(release.isDraft)
        assertFalse(release.isPrerelease)
        assertNotNull("APK asset must be extracted", release.apkAsset)
        assertEquals("LyricBar-2.0.0.apk", release.apkAsset?.name)
        assertTrue("Asset must be identified as APK", release.apkAsset?.isApk == true)
        assertEquals(
            "62a7c08a8a5f89dbdf6e6b02cd6ea749c1a0e9cbadd8f256a5b0d817d09b8b8f",
            release.sha256Checksum
        )
        assertEquals("18.6 MB", release.apkAsset?.formattedSize)
    }

    @Test
    fun testReleasesWithoutApkAssetsAreRejected() {
        val sampleJson = JsonObject().apply {
            addProperty("tag_name", "v1.9.5")
            addProperty("name", "Source Only Release")
            addProperty("published_at", "2026-11-01T00:00:00Z")
            addProperty("body", "Only source tarball available.")
            addProperty("draft", false)
            addProperty("prerelease", false)
            val assets = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("name", "source.tar.gz")
                    addProperty("browser_download_url", "https://github.com/Ishaanj2007/LyricBar/releases/download/v1.9.5/source.tar.gz")
                    addProperty("size", 1024L)
                    addProperty("content_type", "application/gzip")
                })
            }
            add("assets", assets)
        }

        val repository = UpdateRepository()
        val release = repository.parseReleaseJson(sampleJson)

        assertNull("Release with no APK asset must have null apkAsset", release.apkAsset)
    }

    @Test
    fun testDraftAndPrereleaseFlags() {
        val draftJson = JsonObject().apply {
            addProperty("tag_name", "v2.1.0-draft")
            addProperty("draft", true)
            addProperty("prerelease", false)
        }
        val prereleaseJson = JsonObject().apply {
            addProperty("tag_name", "v2.1.0-alpha")
            addProperty("draft", false)
            addProperty("prerelease", true)
        }

        val repository = UpdateRepository()
        val draftRelease = repository.parseReleaseJson(draftJson)
        val preRelease = repository.parseReleaseJson(prereleaseJson)

        assertTrue(draftRelease.isDraft)
        assertFalse(draftRelease.isPrerelease)

        assertFalse(preRelease.isDraft)
        assertTrue(preRelease.isPrerelease)
    }

    @Test
    fun testSha256ChecksumCalculation() {
        val tempFile = File.createTempFile("checksum_test", ".txt").apply {
            deleteOnExit()
            writeText("LyricBar Update Test Payload")
        }

        val calculatedSha256 = ApkVerifier.calculateSha256(tempFile)
        assertNotNull(calculatedSha256)
        assertEquals("SHA-256 hex string must be 64 characters", 64, calculatedSha256.length)

        // Verify repeatability
        val secondPass = ApkVerifier.calculateSha256(tempFile)
        assertEquals(calculatedSha256, secondPass)

        tempFile.delete()
    }

    @Test
    fun testCertSha256HexComputation() {
        val testBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val fingerprint = ApkVerifier.computeCertSha256(testBytes)
        assertEquals(64, fingerprint.length)
        assertTrue(fingerprint.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun testInstallationIntentConstants() {
        assertEquals("android.intent.action.VIEW", UpdateInstaller.INSTALL_ACTION)
        assertEquals("application/vnd.android.package-archive", UpdateInstaller.INSTALL_MIME_TYPE)
    }
}
