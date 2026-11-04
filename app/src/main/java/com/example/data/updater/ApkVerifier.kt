package com.example.data.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

sealed interface ChecksumStatus {
    object Verified : ChecksumStatus
    object NotProvided : ChecksumStatus
    data class Mismatch(val expected: String, val actual: String) : ChecksumStatus
}

sealed interface VerificationResult {
    data class Success(
        val apkFile: File,
        val apkVersionName: String,
        val apkVersionCode: Long,
        val checksumStatus: ChecksumStatus,
        val certificateFingerprint: String
    ) : VerificationResult

    data class Error(val message: String) : VerificationResult
}

object ApkVerifier {

    /**
     * Comprehensive security verification of a downloaded APK:
     * 1. File existence and non-empty check.
     * 2. SHA-256 checksum verification against release metadata (if available).
     * 3. Package archive integrity parsing.
     * 4. Package identity matching against current installed application.
     * 5. Version verification (preventing downgrade attacks).
     * 6. Android signing certificate fingerprint match against installed app.
     */
    fun verify(
        context: Context,
        apkFile: File,
        expectedSha256: String?,
        installedVersionName: String,
        installedVersionCode: Long
    ): VerificationResult {
        // 1. Validate file exists and is not empty
        if (!apkFile.exists() || apkFile.length() <= 0) {
            return VerificationResult.Error("Downloaded APK file is missing or empty.")
        }

        // 2. SHA-256 Checksum Verification
        val actualSha256 = calculateSha256(apkFile)
        val checksumStatus = if (!expectedSha256.isNullOrBlank()) {
            val cleanExpected = expectedSha256.trim().lowercase()
            if (actualSha256.equals(cleanExpected, ignoreCase = true)) {
                ChecksumStatus.Verified
            } else {
                return VerificationResult.Error(
                    "SHA-256 checksum mismatch.\nExpected: $cleanExpected\nActual: $actualSha256\nThe download may be corrupted or compromised."
                )
            }
        } else {
            ChecksumStatus.NotProvided
        }

        // 3. Inspect APK archive with PackageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_META_DATA
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES or PackageManager.GET_META_DATA
        }

        val apkPackageInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: return VerificationResult.Error("Android Package Manager could not parse the downloaded APK. The file is corrupt or incomplete.")

        // 4. Verify package name matches installed LyricBar package
        val expectedPackageName = context.packageName
        if (apkPackageInfo.packageName != expectedPackageName) {
            return VerificationResult.Error(
                "Package name mismatch.\nExpected: $expectedPackageName\nFound: ${apkPackageInfo.packageName}\nThis APK belongs to a different application and cannot be installed."
            )
        }

        // 5. Version verification
        val apkVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            apkPackageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            apkPackageInfo.versionCode.toLong()
        }
        val apkVersionName = apkPackageInfo.versionName ?: ""

        // Check if APK is older than installed version
        if (apkVersionCode < installedVersionCode &&
            VersionComparator.compare(apkVersionName, installedVersionName) < 0
        ) {
            return VerificationResult.Error(
                "APK version is older than currently installed app (installed: $installedVersionName, apk: $apkVersionName). Downgrading is not permitted."
            )
        }

        // 6. Signing certificate verification
        val installedCertFingerprints = getInstalledSigningFingerprints(context)
        val apkCertFingerprints = getApkSigningFingerprints(apkPackageInfo)

        if (apkCertFingerprints.isEmpty()) {
            return VerificationResult.Error("Could not extract signing certificates from downloaded APK.")
        }

        if (installedCertFingerprints.isEmpty()) {
            return VerificationResult.Error("Could not extract signing certificates from installed app.")
        }

        // Verify that the APK contains at least one certificate that matches the installed app's signing certificates
        val matchingCert = apkCertFingerprints.firstOrNull { apkCert ->
            installedCertFingerprints.any { it.equals(apkCert, ignoreCase = true) }
        }

        if (matchingCert == null) {
            val installedDisplay = installedCertFingerprints.firstOrNull()?.take(16) ?: "unknown"
            val apkDisplay = apkCertFingerprints.firstOrNull()?.take(16) ?: "unknown"
            return VerificationResult.Error(
                "Signature mismatch detected.\nInstalled key: $installedDisplay...\nDownloaded key: $apkDisplay...\nAndroid cannot update an app signed with a different key. Update rejected."
            )
        }

        return VerificationResult.Success(
            apkFile = apkFile,
            apkVersionName = apkVersionName,
            apkVersionCode = apkVersionCode,
            checksumStatus = checksumStatus,
            certificateFingerprint = matchingCert
        )
    }

    fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val bytes = digest.digest()
        return bytes.joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    private fun getInstalledSigningFingerprints(context: Context): List<String> {
        return try {
            val packageManager = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = packageInfo.signingInfo ?: return emptyList()
                val certBytes = if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                } ?: return emptyList()

                certBytes.map { computeCertSha256(it.toByteArray()) }
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                val signatures = packageInfo.signatures ?: return emptyList()
                signatures.map { computeCertSha256(it.toByteArray()) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getApkSigningFingerprints(packageInfo: PackageInfo): List<String> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = packageInfo.signingInfo
                if (signingInfo != null) {
                    val certBytes = if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners
                    } else {
                        signingInfo.signingCertificateHistory
                    }
                    if (certBytes != null && certBytes.isNotEmpty()) {
                        return certBytes.map { computeCertSha256(it.toByteArray()) }
                    }
                }
            }
            @Suppress("DEPRECATION")
            val signatures = packageInfo.signatures
            if (signatures != null && signatures.isNotEmpty()) {
                return signatures.map { computeCertSha256(it.toByteArray()) }
            }
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun computeCertSha256(certBytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(certBytes)
        return digest.joinToString("") { String.format(Locale.US, "%02x", it) }
    }
}
