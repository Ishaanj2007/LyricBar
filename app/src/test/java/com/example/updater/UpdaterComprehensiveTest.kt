package com.example.updater

import com.example.data.updater.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UpdaterComprehensiveTest {

    // SCENARIO 1: Stable release acceptance (published release with APK is accepted)
    @Test
    fun testStableReleaseAcceptance() {
        val json = JsonObject().apply {
            addProperty("tag_name", "v2.1.0")
            addProperty("name", "LyricBar v2.1.0 Stable")
            addProperty("published_at", "2026-11-06T10:00:00Z")
            addProperty("body", "Stable release with bug fixes.")
            addProperty("html_url", "https://github.com/Ishaanj2007/LyricBar/releases/tag/v2.1.0")
            addProperty("draft", false)
            addProperty("prerelease", false)
            val assets = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("name", "LyricBar-2.1.0.apk")
                    addProperty("browser_download_url", "https://github.com/Ishaanj2007/LyricBar/releases/download/v2.1.0/LyricBar-2.1.0.apk")
                    addProperty("size", 18500000L)
                    addProperty("content_type", "application/vnd.android.package-archive")
                    addProperty("digest", "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                })
            }
            add("assets", assets)
        }

        val repository = UpdateRepository()
        val release = repository.parseReleaseJson(json)

        assertFalse("Stable release must not be marked as draft", release.isDraft)
        assertFalse("Stable release must not be marked as prerelease", release.isPrerelease)
        assertNotNull("Stable release with APK asset must have non-null apkAsset", release.apkAsset)
        assertEquals("LyricBar-2.1.0.apk", release.apkAsset?.name)
        assertTrue("Asset must be identified as an APK", release.apkAsset?.isApk == true)
        assertEquals("2.1.0", release.cleanVersionName)
    }

    // SCENARIO 2: Draft release rejection
    @Test
    fun testDraftReleaseRejection() {
        val draftJson = JsonObject().apply {
            addProperty("tag_name", "v2.1.1-draft")
            addProperty("draft", true)
            addProperty("prerelease", false)
            val assets = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("name", "LyricBar-2.1.1.apk")
                    addProperty("browser_download_url", "https://example.com/draft.apk")
                    addProperty("size", 1000L)
                    addProperty("content_type", "application/vnd.android.package-archive")
                })
            }
            add("assets", assets)
        }

        val repository = UpdateRepository()
        val release = repository.parseReleaseJson(draftJson)
        assertTrue("Release must preserve draft flag so updater pipeline can reject it", release.isDraft)
    }

    // SCENARIO 3: Prerelease rejection
    @Test
    fun testPrereleaseRejection() {
        val prereleaseJson = JsonObject().apply {
            addProperty("tag_name", "v2.1.1-alpha.1")
            addProperty("draft", false)
            addProperty("prerelease", true)
            val assets = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("name", "LyricBar-2.1.1-alpha.apk")
                    addProperty("browser_download_url", "https://example.com/pre.apk")
                    addProperty("size", 1000L)
                    addProperty("content_type", "application/vnd.android.package-archive")
                })
            }
            add("assets", assets)
        }

        val repository = UpdateRepository()
        val release = repository.parseReleaseJson(prereleaseJson)
        assertTrue("Release must preserve prerelease flag so updater pipeline can reject it", release.isPrerelease)
    }

    // SCENARIO 4: Releases without APK assets are identified
    @Test
    fun testReleasesWithoutApkAssetsAreIdentified() {
        val sourceOnlyJson = JsonObject().apply {
            addProperty("tag_name", "v2.1.0")
            addProperty("name", "Source Only Release")
            addProperty("draft", false)
            addProperty("prerelease", false)
            val assets = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("name", "source.zip")
                    addProperty("browser_download_url", "https://example.com/source.zip")
                    addProperty("size", 1024L)
                    addProperty("content_type", "application/zip")
                })
            }
            add("assets", assets)
        }

        val repository = UpdateRepository()
        val release = repository.parseReleaseJson(sourceOnlyJson)
        assertNull("Release with no APK asset must result in null apkAsset", release.apkAsset)
    }

    // SCENARIO 5: Multi-segment numeric versioning (1.10.0 > 1.9.0)
    @Test
    fun testMultiSegmentNumericOrdering() {
        assertTrue("1.10.0 must be newer than 1.9.0", VersionComparator.isNewer("1.10.0", "1.9.0"))
        assertFalse("1.9.0 must not be newer than 1.10.0", VersionComparator.isNewer("1.9.0", "1.10.0"))
        assertTrue("2.0.0 must be newer than 1.9.9", VersionComparator.isNewer("2.0.0", "1.9.9"))
        assertTrue("2.1.0 must be newer than 2.0.0", VersionComparator.isNewer("2.1.0", "2.0.0"))
        assertTrue("2.1.1 must be newer than 2.1.0", VersionComparator.isNewer("2.1.1", "2.1.0"))
        assertFalse("2.1.0 must not be newer than 2.1.1", VersionComparator.isNewer("2.1.0", "2.1.1"))
    }

    // SCENARIO 6: Semantic tag variations (v2.1.0, 2.1.0, v.2.1.0, LyricBar-2.1.0, 2.1)
    @Test
    fun testSemanticTagVariations() {
        assertEquals(0, VersionComparator.compare("v2.1.0", "2.1.0"))
        assertEquals(0, VersionComparator.compare("V2.1.0", "v2.1.0"))
        assertEquals(0, VersionComparator.compare("v.2.1.0", "2.1.0"))
        assertEquals(0, VersionComparator.compare("LyricBar-2.1.0", "2.1.0"))
        assertEquals(0, VersionComparator.compare("2.1", "2.1.0"))

        assertTrue("v2.1.1 is newer than 2.1.0", VersionComparator.isNewer("v2.1.1", "2.1.0"))
        assertTrue("2.1.1 is newer than v2.1", VersionComparator.isNewer("2.1.1", "v2.1"))
        assertFalse("v2.1.0 is not newer than 2.1.0", VersionComparator.isNewer("v2.1.0", "2.1.0"))
    }

    // SCENARIO 7: Pre-release version comparison
    @Test
    fun testPreReleaseComparison() {
        // Final release is newer than release candidate of same base
        assertTrue("2.1.0 is newer than 2.1.0-rc1", VersionComparator.isNewer("2.1.0", "2.1.0-rc1"))
        assertTrue("2.1.0 is newer than 2.1.0-beta", VersionComparator.isNewer("2.1.0", "2.1.0-beta"))
        assertFalse("2.1.0-rc1 is not newer than 2.1.0", VersionComparator.isNewer("2.1.0-rc1", "2.1.0"))

        // Later minor rc is newer than earlier minor final
        assertTrue("2.2.0-rc1 is newer than 2.1.0", VersionComparator.isNewer("2.2.0-rc1", "2.1.0"))
    }

    // SCENARIO 8: Release notes SHA-256 pattern parsing (single APK generic hash extraction)
    @Test
    fun testGenericSha256InSingleApkReleaseBody() {
        val hash = "62a7c08a8a5f89dbdf6e6b02cd6ea749c1a0e9cbadd8f256a5b0d817d09b8b8f"
        val json = JsonObject().apply {
            addProperty("tag_name", "v2.1.0")
            addProperty("name", "LyricBar v2.1.0")
            addProperty("body", "## Release Notes\nBug fixes.\n\nSHA-256: $hash")
            val assets = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("name", "LyricBar-2.1.0.apk")
                    addProperty("browser_download_url", "https://example.com/app.apk")
                    addProperty("size", 1000L)
                    addProperty("content_type", "application/vnd.android.package-archive")
                })
            }
            add("assets", assets)
        }

        val repository = UpdateRepository()
        val release = repository.parseReleaseJson(json)
        assertEquals(hash, release.sha256Checksum)
        assertEquals(hash, release.getChecksumForAsset(release.apkAsset))
    }

    // SCENARIO 9: Asset-specific checksum association from release notes (table format)
    @Test
    fun testMarkdownTableChecksumAssociation() {
        val hashArm = "1111111111111111111111111111111111111111111111111111111111111111"
        val hashUniversal = "2222222222222222222222222222222222222222222222222222222222222222"

        val body = """
            | File | SHA-256 |
            | --- | --- |
            | LyricBar-2.1.0-arm64-v8a.apk | $hashArm |
            | LyricBar-2.1.0-universal.apk | $hashUniversal |
        """.trimIndent()

        val json = JsonObject().apply {
            addProperty("tag_name", "v2.1.0")
            addProperty("body", body)
            val assets = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("name", "LyricBar-2.1.0-arm64-v8a.apk")
                    addProperty("browser_download_url", "https://example.com/arm.apk")
                    addProperty("size", 1000L)
                    addProperty("content_type", "application/vnd.android.package-archive")
                })
                add(JsonObject().apply {
                    addProperty("name", "LyricBar-2.1.0-universal.apk")
                    addProperty("browser_download_url", "https://example.com/univ.apk")
                    addProperty("size", 2000L)
                    addProperty("content_type", "application/vnd.android.package-archive")
                })
            }
            add("assets", assets)
        }

        val repository = UpdateRepository()
        val release = repository.parseReleaseJson(json)

        val armAsset = release.allAssets.first { it.name.contains("arm64-v8a") }
        val univAsset = release.allAssets.first { it.name.contains("universal") }

        assertEquals(hashArm, release.getChecksumForAsset(armAsset))
        assertEquals(hashUniversal, release.getChecksumForAsset(univAsset))
    }

    // SCENARIO 10: Asset-specific checksum association with filename prefix (filename: hash)
    @Test
    fun testColonPrefixChecksumAssociation() {
        val hash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        val body = "Artifacts:\nLyricBar-2.1.1.apk: $hash"

        val json = JsonObject().apply {
            addProperty("tag_name", "v2.1.1")
            addProperty("body", body)
            val assets = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("name", "LyricBar-2.1.1.apk")
                    addProperty("browser_download_url", "https://example.com/app.apk")
                    addProperty("size", 1000L)
                    addProperty("content_type", "application/vnd.android.package-archive")
                })
            }
            add("assets", assets)
        }

        val repository = UpdateRepository()
        val release = repository.parseReleaseJson(json)
        assertEquals(hash, release.getChecksumForAsset(release.apkAsset))
    }

    // SCENARIO 11: Companion checksum file parsing (<hash> <filename> and multiline)
    @Test
    fun testCompanionChecksumTextParsing() {
        val hash1 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val hash2 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        val shaSumsText = """
            $hash1  LyricBar-2.1.0-arm64-v8a.apk
            $hash2 *LyricBar-2.1.0-universal.apk
        """.trimIndent()

        val repository = UpdateRepository()
        val parsedArm = repository.parseChecksumFromText(shaSumsText, "LyricBar-2.1.0-arm64-v8a.apk")
        val parsedUniv = repository.parseChecksumFromText(shaSumsText, "LyricBar-2.1.0-universal.apk")

        assertEquals(hash1, parsedArm)
        assertEquals(hash2, parsedUniv)

        // Single line standalone hash
        val singleHashOnly = hash1
        val parsedSingle = repository.parseChecksumFromText(singleHashOnly, null)
        assertEquals(hash1, parsedSingle)
    }

    // SCENARIO 12: Checksum isolation in multi-asset releases (unassociated variant receives null)
    @Test
    fun testChecksumIsolationInMultiAssetRelease() {
        val hashArm = "3333333333333333333333333333333333333333333333333333333333333333"

        // Body only specifies checksum for the arm64 variant
        val body = "arm64-v8a build checksum: $hashArm  LyricBar-2.1.0-arm64-v8a.apk"

        val json = JsonObject().apply {
            addProperty("tag_name", "v2.1.0")
            addProperty("body", body)
            val assets = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("name", "LyricBar-2.1.0-arm64-v8a.apk")
                    addProperty("browser_download_url", "https://example.com/arm.apk")
                    addProperty("size", 1000L)
                    addProperty("content_type", "application/vnd.android.package-archive")
                })
                add(JsonObject().apply {
                    addProperty("name", "LyricBar-2.1.0-x86_64.apk")
                    addProperty("browser_download_url", "https://example.com/x86.apk")
                    addProperty("size", 1000L)
                    addProperty("content_type", "application/vnd.android.package-archive")
                })
            }
            add("assets", assets)
        }

        val repository = UpdateRepository()
        val release = repository.parseReleaseJson(json)

        val armAsset = release.allAssets.first { it.name.contains("arm64-v8a") }
        val x86Asset = release.allAssets.first { it.name.contains("x86_64") }

        assertEquals("arm64 variant must resolve its explicit checksum", hashArm, release.getChecksumForAsset(armAsset))
        assertNull("x86_64 variant must NOT be assigned the arm64 checksum", release.getChecksumForAsset(x86Asset))
    }

    // SCENARIO 13: Direct asset JSON digest extraction (sha256:...)
    @Test
    fun testAssetJsonDigestExtraction() {
        val hash = "4444444444444444444444444444444444444444444444444444444444444444"
        val json = JsonObject().apply {
            addProperty("tag_name", "v2.1.0")
            val assets = JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("name", "LyricBar-2.1.0.apk")
                    addProperty("browser_download_url", "https://example.com/app.apk")
                    addProperty("size", 1000L)
                    addProperty("content_type", "application/vnd.android.package-archive")
                    addProperty("digest", "sha256:$hash")
                })
            }
            add("assets", assets)
        }

        val repository = UpdateRepository()
        val release = repository.parseReleaseJson(json)
        assertEquals(hash, release.apkAsset?.digest)
        assertEquals(hash, release.getChecksumForAsset(release.apkAsset))
    }

    // SCENARIO 14: SHA-256 calculation correctness and repeatability
    @Test
    fun testSha256CalculationCorrectness() {
        val tempFile = File.createTempFile("sha_test", ".apk").apply {
            deleteOnExit()
            writeText("LyricBar APK binary test content")
        }

        val digest1 = ApkVerifier.calculateSha256(tempFile)
        val digest2 = ApkVerifier.calculateSha256(tempFile)

        assertEquals("Calculated digest must be exactly 64 characters", 64, digest1.length)
        assertTrue("Digest must contain valid hex chars", digest1.all { it.isDigit() || it in 'a'..'f' })
        assertEquals("Calculation must be deterministic and repeatable", digest1, digest2)

        tempFile.delete()
    }

    // SCENARIO 15: ABI-aware APK asset selection
    @Test
    fun testAbiAwareApkSelection() {
        val repository = UpdateRepository()
        val armAsset = ReleaseAsset("LyricBar-2.1.0-arm64-v8a.apk", "https://example.com/arm", 1000L, "application/vnd.android.package-archive")
        val x86Asset = ReleaseAsset("LyricBar-2.1.0-x86_64.apk", "https://example.com/x86", 1000L, "application/vnd.android.package-archive")
        val universalAsset = ReleaseAsset("LyricBar-2.1.0-universal.apk", "https://example.com/univ", 2000L, "application/vnd.android.package-archive")

        // Device supporting arm64-v8a
        val armSelected = repository.selectBestApkAsset(listOf(x86Asset, armAsset, universalAsset), arrayOf("arm64-v8a", "armeabi-v7a"))
        assertEquals("arm64-v8a asset must be preferred on arm64 devices", armAsset.name, armSelected?.name)

        // Device supporting x86_64 (e.g. emulator)
        val x86Selected = repository.selectBestApkAsset(listOf(armAsset, x86Asset, universalAsset), arrayOf("x86_64", "x86"))
        assertEquals("x86_64 asset must be preferred on x86_64 devices", x86Asset.name, x86Selected?.name)

        // Device with unknown ABI falling back to universal
        val univSelected = repository.selectBestApkAsset(listOf(armAsset, x86Asset, universalAsset), arrayOf("mips"))
        assertEquals("Universal asset must be selected when specific ABI is missing", universalAsset.name, univSelected?.name)
    }

    // SCENARIO 16: Checksum status types
    @Test
    fun testChecksumStatusRepresentation() {
        val verified: ChecksumStatus = ChecksumStatus.Verified
        val notProvided: ChecksumStatus = ChecksumStatus.NotProvided
        val mismatch: ChecksumStatus = ChecksumStatus.Mismatch("expected_hash", "actual_hash")

        assertTrue(verified is ChecksumStatus.Verified)
        assertTrue(notProvided is ChecksumStatus.NotProvided)
        assertTrue(mismatch is ChecksumStatus.Mismatch)
        assertEquals("expected_hash", (mismatch as ChecksumStatus.Mismatch).expected)
        assertEquals("actual_hash", mismatch.actual)
    }

    // SCENARIO 17: Certificate fingerprint computation
    @Test
    fun testCertificateFingerprintComputation() {
        val certBytes = "LyricBar Signing Certificate Payload".toByteArray(Charsets.UTF_8)
        val fingerprint = ApkVerifier.computeCertSha256(certBytes)

        assertEquals("Certificate fingerprint must be 64 characters hex", 64, fingerprint.length)
        assertTrue(fingerprint.all { it.isDigit() || it in 'a'..'f' })

        // Determinism
        val fingerprint2 = ApkVerifier.computeCertSha256(certBytes)
        assertEquals(fingerprint, fingerprint2)
    }
}
