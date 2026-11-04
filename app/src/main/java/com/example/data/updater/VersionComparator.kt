package com.example.data.updater

/**
 * Robust semantic version comparator for LyricBar.
 *
 * Avoids raw alphabetical comparison (which incorrectly evaluates "1.10.0" < "1.9.0").
 * Handles version prefixes ("v", "V", "v."), suffix identifiers ("-beta", "-rc", "+build"),
 * variable segment counts ("1.9" vs "1.9.0"), and malformed strings safely.
 */
object VersionComparator {

    /**
     * Returns true if [remoteVersion] is strictly newer than [installedVersion].
     */
    fun isNewer(remoteVersion: String, installedVersion: String): Boolean {
        return compare(remoteVersion, installedVersion) > 0
    }

    /**
     * Compares two version strings.
     * Returns:
     * - positive integer if [v1] is newer than [v2]
     * - negative integer if [v1] is older than [v2]
     * - 0 if [v1] and [v2] represent the same version
     */
    fun compare(v1: String, v2: String): Int {
        val parsed1 = parse(v1)
        val parsed2 = parse(v2)

        val maxSegments = maxOf(parsed1.numbers.size, parsed2.numbers.size)
        for (i in 0 until maxSegments) {
            val num1 = parsed1.numbers.getOrElse(i) { 0 }
            val num2 = parsed2.numbers.getOrElse(i) { 0 }
            if (num1 != num2) {
                return num1.compareTo(num2)
            }
        }

        // Both numeric components are equal. Check pre-release status:
        // A release without a pre-release tag is NEWER than a release with one (e.g. 1.9.0 > 1.9.0-beta)
        return when {
            parsed1.preRelease == null && parsed2.preRelease != null -> 1
            parsed1.preRelease != null && parsed2.preRelease == null -> -1
            parsed1.preRelease != null && parsed2.preRelease != null -> {
                parsed1.preRelease.compareTo(parsed2.preRelease, ignoreCase = true)
            }
            else -> 0
        }
    }

    private data class ParsedVersion(
        val numbers: List<Int>,
        val preRelease: String?
    )

    private fun parse(versionStr: String): ParsedVersion {
        val trimmed = versionStr.trim()
        if (trimmed.isEmpty()) {
            return ParsedVersion(listOf(0), null)
        }

        // Strip leading non-digit characters (e.g. "v1.9.0" -> "1.9.0", "release-2.0" -> "2.0")
        val digitStartIndex = trimmed.indexOfFirst { it.isDigit() }
        if (digitStartIndex == -1) {
            return ParsedVersion(listOf(0), null)
        }
        val cleanVersion = trimmed.substring(digitStartIndex)

        // Split off pre-release / build metadata (e.g., "1.9.0-beta.1+2026" -> base "1.9.0", preRelease "beta.1")
        val preReleaseDelimiter = cleanVersion.indexOfFirst { it == '-' || it == '+' }
        val basePart = if (preReleaseDelimiter != -1) {
            cleanVersion.substring(0, preReleaseDelimiter)
        } else {
            cleanVersion
        }
        val preReleasePart = if (preReleaseDelimiter != -1 && preReleaseDelimiter < cleanVersion.length - 1) {
            cleanVersion.substring(preReleaseDelimiter + 1)
        } else {
            null
        }

        // Split base into numeric segments separated by dots
        val segments = basePart.split('.').mapNotNull { segment ->
            segment.takeWhile { it.isDigit() }.toIntOrNull()
        }

        return ParsedVersion(
            numbers = if (segments.isEmpty()) listOf(0) else segments,
            preRelease = preReleasePart
        )
    }
}
