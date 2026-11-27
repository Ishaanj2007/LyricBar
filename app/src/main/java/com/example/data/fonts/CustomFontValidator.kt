package com.example.data.fonts

import android.graphics.Typeface
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * Validates candidate custom font files without trusting filenames or extensions alone.
 */
object CustomFontValidator {

    val SUPPORTED_EXTENSIONS = setOf("ttf", "otf")

    fun extractSupportedExtension(fileName: String?): String? {
        if (fileName.isNullOrBlank()) return null
        val trimmed = fileName.trim()
        val dotIndex = trimmed.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex >= trimmed.length - 1) return null
        val ext = trimmed.substring(dotIndex + 1).lowercase(Locale.US)
        return ext.takeIf { it in SUPPORTED_EXTENSIONS }
    }

    /**
     * Checks OpenType / TrueType / OpenType-CFF / TTC SFNT magic bytes at offset 0.
     * Prevents renamed text/image/ZIP files from being treated as fonts.
     */
    fun hasValidSfntHeader(header: ByteArray, length: Int = header.size): Boolean {
        if (length < 4) return false
        val b0 = header[0].toInt() and 0xFF
        val b1 = header[1].toInt() and 0xFF
        val b2 = header[2].toInt() and 0xFF
        val b3 = header[3].toInt() and 0xFF

        val isTrueType = b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00
        val isAppleTrueType = b0 == 0x74 && b1 == 0x72 && b2 == 0x75 && b3 == 0x65 // "true"
        val isOpenTypeCff = b0 == 0x4F && b1 == 0x54 && b2 == 0x54 && b3 == 0x4F // "OTTO"
        val isTrueTypeCollection = b0 == 0x74 && b1 == 0x74 && b2 == 0x63 && b3 == 0x66 // "ttcf"
        return isTrueType || isAppleTrueType || isOpenTypeCff || isTrueTypeCollection
    }

    fun readFileHeader(file: File): ByteArray? {
        if (!file.exists() || !file.isFile) return null
        return try {
            FileInputStream(file).use { input ->
                val buf = ByteArray(4)
                var total = 0
                while (total < 4) {
                    val read = input.read(buf, total, 4 - total)
                    if (read == -1) break
                    total += read
                }
                if (total == 4) buf else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun verifyAndroidTypeface(file: File): Boolean {
        return try {
            if (!file.exists() || !file.isFile || file.length() <= 0L) return false
            Typeface.createFromFile(file) != null
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Builds a clean user-facing font display name from an uploaded filename.
     */
    fun sanitizeDisplayName(originalFileName: String): String {
        val leaf = originalFileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        val dotIdx = leaf.lastIndexOf('.')
        val stem = if (dotIdx > 0) leaf.substring(0, dotIdx) else leaf
        val cleaned = stem
            .replace(Regex("[_\\-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(64)
        return cleaned.ifBlank { "Custom Font" }
    }

    /**
     * Sanitizes a leaf filename strictly for display and metadata (never used directly as a filesystem path).
     */
    fun sanitizeOriginalFileName(rawName: String?, fallbackExt: String = "ttf"): String {
        val leaf = rawName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.replace('\u0000', '_')
            ?.trim()
            .orEmpty()
        return leaf.ifBlank { "custom_font.$fallbackExt" }
    }
}
