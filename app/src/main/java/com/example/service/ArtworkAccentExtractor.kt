package com.example.service

import android.graphics.Bitmap
import androidx.annotation.ColorInt
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts vibrant, legible accent colours from album artwork off the main thread using AndroidX Palette.
 * Designed for the floating lyrics HUD overlay active lyric highlight.
 * Caches results in an LRU cache keyed by track/artwork identity key.
 */
object ArtworkAccentExtractor {

    private const val MAX_CACHE_ENTRIES = 32

    private val colorCache = object : LinkedHashMap<String, Int>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    /**
     * Retrieves the cached accent colour for [trackKey], or null if not cached.
     */
    fun getCachedAccent(trackKey: String): Int? = synchronized(colorCache) {
        if (trackKey.isBlank()) null else colorCache[trackKey]
    }

    /**
     * Stores [colorArgb] in the cache for [trackKey].
     */
    fun putCachedAccent(trackKey: String, @ColorInt colorArgb: Int) = synchronized(colorCache) {
        if (trackKey.isNotBlank()) {
            colorCache[trackKey] = ensureOpaque(colorArgb)
        }
    }

    /**
     * Clears all cached accent colours.
     */
    fun clearCache() = synchronized(colorCache) {
        colorCache.clear()
    }

    /**
     * Asynchronously extracts an accent colour from [bitmap] for [trackKey] on [Dispatchers.Default].
     * Returns the cached colour if available, or the extracted colour, or null if artwork is missing or extraction fails.
     */
    suspend fun extractAccentColor(
        trackKey: String,
        bitmap: Bitmap?
    ): Int? = withContext(Dispatchers.Default) {
        if (trackKey.isBlank()) return@withContext null

        getCachedAccent(trackKey)?.let { return@withContext it }

        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return@withContext null
        }

        val extracted = try {
            val palette = Palette.from(bitmap).generate()
            pickSuitableAccent(palette)
        } catch (_: Throwable) {
            null
        }

        if (extracted != null) {
            putCachedAccent(trackKey, extracted)
        }

        extracted
    }

    /**
     * Inspects swatches in priority order (vibrant swatches first, then dominant/muted, then remaining by population)
     * and selects the first colour satisfying HUD legibility and contrast requirements.
     */
    fun pickSuitableAccent(palette: Palette): Int? {
        val primaryCandidates = listOfNotNull(
            palette.vibrantSwatch,
            palette.lightVibrantSwatch,
            palette.dominantSwatch,
            palette.mutedSwatch,
            palette.lightMutedSwatch,
            palette.darkVibrantSwatch
        )

        for (swatch in primaryCandidates) {
            val rgb = swatch.rgb
            if (isSuitableForHudAccent(rgb)) {
                return ensureOpaque(rgb)
            }
        }

        val remaining = palette.swatches
            .filter { it !in primaryCandidates }
            .sortedByDescending { it.population }

        for (swatch in remaining) {
            val rgb = swatch.rgb
            if (isSuitableForHudAccent(rgb)) {
                return ensureOpaque(rgb)
            }
        }

        return null
    }

    /**
     * Evaluates whether [color] has adequate contrast and saturation for floating HUD active lyrics against dark surfaces.
     * Rejects colours that are too dark (perceived brightness < 0.22), too pale/washed out (perceived brightness > 0.95),
     * or low-contrast washed-out grays.
     */
    fun isSuitableForHudAccent(@ColorInt color: Int): Boolean {
        val perceived = calculatePerceivedBrightness(color)
        if (perceived < 0.22 || perceived > 0.95) {
            return false
        }
        val saturation = calculateSaturation(color)
        if (perceived > 0.65 && saturation < 0.18f) {
            return false
        }
        return true
    }

    /**
     * Calculates perceived brightness using standard ITU-R BT.601 coefficients (0.0 to 1.0).
     */
    fun calculatePerceivedBrightness(@ColorInt color: Int): Double {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    }

    /**
     * Calculates relative luminance of [color] in sRGB according to WCAG 2.1 standard (0.0 = darkest black, 1.0 = brightest white).
     * Self-contained pure math implementation to guarantee deterministic execution across JVM tests and Android runtimes.
     */
    fun calculateLuminance(@ColorInt color: Int): Double {
        val r = ((color shr 16) and 0xFF) / 255.0
        val g = ((color shr 8) and 0xFF) / 255.0
        val b = (color and 0xFF) / 255.0

        val rL = if (r <= 0.04045) r / 12.92 else Math.pow((r + 0.055) / 1.055, 2.4)
        val gL = if (g <= 0.04045) g / 12.92 else Math.pow((g + 0.055) / 1.055, 2.4)
        val bL = if (b <= 0.04045) b / 12.92 else Math.pow((b + 0.055) / 1.055, 2.4)

        return 0.2126 * rL + 0.7152 * gL + 0.0722 * bL
    }

    /**
     * Calculates HSV saturation of [color] (0.0 to 1.0).
     */
    fun calculateSaturation(@ColorInt color: Int): Float {
        val r = ((color shr 16) and 0xFF) / 255.0f
        val g = ((color shr 8) and 0xFF) / 255.0f
        val b = (color and 0xFF) / 255.0f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        return if (max == 0f) 0f else delta / max
    }

    @ColorInt
    fun ensureOpaque(@ColorInt color: Int): Int {
        return color or 0xFF000000.toInt()
    }
}
