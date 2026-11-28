package com.example.service

import android.graphics.Bitmap
import androidx.annotation.ColorInt
import androidx.palette.graphics.Palette
import com.example.ui.components.HudColorSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure-Kotlin representation of a colour swatch candidate for deterministic selection & testing.
 */
data class AccentSwatchCandidate(
    @ColorInt val rgb: Int,
    val population: Int
)

/**
 * Separated artwork-derived colour pair for the floating HUD:
 * - [sourceAccentColor]: Original validated chromatic accent from artwork (preserves artwork identity).
 * - [activeTextColor]: Contrast-checked active lyric text ink (preserves [sourceAccentColor] when already legible).
 * - [glowColor]: Hue-preserved, perceptually normalized luminous colour dedicated to HUD glow rendering.
 */
data class DynamicAccentResult(
    @ColorInt val sourceAccentColor: Int,
    @ColorInt val activeTextColor: Int,
    @ColorInt val glowColor: Int
)

/**
 * Extracts vibrant, legible accent colours from album artwork off the main thread using AndroidX Palette
 * and pure-Kotlin OKLCH perceptual colour normalization.
 * Caches results in an LRU cache keyed by track/artwork identity key.
 */
object ArtworkAccentExtractor {

    private const val MAX_CACHE_ENTRIES = 32

    // Centralized perceptual thresholds (OKLCH + HSV + WCAG)
    // OKLCH L in [0f, 1f], C in [0f, ~0.37f], h in [0f, 360f)
    const val MIN_CHROMA: Float = 0.075f
    const val MIN_HIGH_LIGHTNESS_CHROMA: Float = 0.088f
    const val HIGH_LIGHTNESS_THRESHOLD: Float = 0.86f
    const val MIN_HSV_SATURATION: Float = 0.26f
    const val MIN_SOURCE_LIGHTNESS: Float = 0.22f
    const val MAX_SOURCE_LIGHTNESS: Float = 0.965f

    // Glow normalization targets in OKLCH
    const val TARGET_GLOW_MIN_LIGHTNESS: Float = 0.72f
    const val TARGET_GLOW_MAX_LIGHTNESS: Float = 0.85f
    const val TARGET_GLOW_MIN_CHROMA: Float = 0.12f
    const val MIN_GLOW_LUMINANCE: Double = 0.34

    // Active lyric text contrast floor against dark HUD backdrop (#0A0A0A)
    const val MIN_ACTIVE_TEXT_CONTRAST: Double = 4.5

    private val colorCache = object : LinkedHashMap<String, DynamicAccentResult>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DynamicAccentResult>?): Boolean {
            return size > MAX_CACHE_ENTRIES
        }
    }

    /**
     * Retrieves the cached [DynamicAccentResult] for [trackKey], or null if not cached.
     */
    fun getCachedAccentResult(trackKey: String): DynamicAccentResult? = synchronized(colorCache) {
        if (trackKey.isBlank()) null else colorCache[trackKey]
    }

    /**
     * Retrieves the cached active text colour for [trackKey], or null if not cached.
     */
    fun getCachedAccent(trackKey: String): Int? = getCachedAccentResult(trackKey)?.activeTextColor

    /**
     * Stores [result] in the cache for [trackKey].
     */
    fun putCachedAccentResult(trackKey: String, result: DynamicAccentResult) = synchronized(colorCache) {
        if (trackKey.isNotBlank()) {
            colorCache[trackKey] = DynamicAccentResult(
                sourceAccentColor = ensureOpaque(result.sourceAccentColor),
                activeTextColor = ensureOpaque(result.activeTextColor),
                glowColor = ensureOpaque(result.glowColor)
            )
        }
    }

    /**
     * Stores [colorArgb] in the cache for [trackKey], deriving its glow colour.
     */
    fun putCachedAccent(trackKey: String, @ColorInt colorArgb: Int) {
        val opaque = ensureOpaque(colorArgb)
        putCachedAccentResult(
            trackKey = trackKey,
            result = DynamicAccentResult(
                sourceAccentColor = opaque,
                activeTextColor = resolveActiveTextColor(opaque),
                glowColor = generateGlowColor(opaque)
            )
        )
    }

    /**
     * Clears all cached accent colours.
     */
    fun clearCache() = synchronized(colorCache) {
        colorCache.clear()
    }

    /**
     * Asynchronously extracts [DynamicAccentResult] from [bitmap] for [trackKey] on [Dispatchers.Default].
     * Returns the cached result if available, or the extracted/normalized result, or null if artwork is
     * missing or contains no valid chromatic candidates.
     */
    suspend fun extractAccent(
        trackKey: String,
        bitmap: Bitmap?
    ): DynamicAccentResult? = withContext(Dispatchers.Default) {
        if (trackKey.isBlank()) return@withContext null

        getCachedAccentResult(trackKey)?.let { return@withContext it }

        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return@withContext null
        }

        val extracted = try {
            val palette = Palette.from(bitmap).generate()
            pickSuitableAccentResult(palette)
        } catch (_: Throwable) {
            null
        }

        if (extracted != null) {
            putCachedAccentResult(trackKey, extracted)
        }

        extracted
    }

    /**
     * Backward-compatible wrapper returning the active text colour, or null if extraction fails.
     */
    suspend fun extractAccentColor(
        trackKey: String,
        bitmap: Bitmap?
    ): Int? = extractAccent(trackKey, bitmap)?.activeTextColor

    /**
     * Evaluates all Palette swatches systematically, rejecting black, grey, near-grey, and near-white
     * candidates, ranking chromatic candidates by colour quality and population, and producing a
     * separated [DynamicAccentResult].
     */
    fun pickSuitableAccentResult(palette: Palette): DynamicAccentResult? {
        val sourceColor = pickSuitableAccent(palette) ?: return null
        return buildAccentResult(sourceColor)
    }

    /**
     * Builds a [DynamicAccentResult] from a validated [sourceColor].
     */
    fun buildAccentResult(@ColorInt sourceColor: Int): DynamicAccentResult {
        val opaqueSource = ensureOpaque(sourceColor)
        return DynamicAccentResult(
            sourceAccentColor = opaqueSource,
            activeTextColor = resolveActiveTextColor(opaqueSource),
            glowColor = generateGlowColor(opaqueSource)
        )
    }

    /**
     * Inspects Palette swatches in chromatic priority order (vibrant swatches first, including
     * darkVibrantSwatch ahead of muted/dominant, then muted/dominant, then remaining swatches),
     * scoring valid candidates so high-population neutral greys never override chromatic accents.
     */
    fun pickSuitableAccent(palette: Palette): Int? {
        return pickSuitableAccentFromCandidates(
            vibrant = palette.vibrantSwatch?.let { AccentSwatchCandidate(it.rgb, it.population) },
            lightVibrant = palette.lightVibrantSwatch?.let { AccentSwatchCandidate(it.rgb, it.population) },
            darkVibrant = palette.darkVibrantSwatch?.let { AccentSwatchCandidate(it.rgb, it.population) },
            muted = palette.mutedSwatch?.let { AccentSwatchCandidate(it.rgb, it.population) },
            lightMuted = palette.lightMutedSwatch?.let { AccentSwatchCandidate(it.rgb, it.population) },
            dominant = palette.dominantSwatch?.let { AccentSwatchCandidate(it.rgb, it.population) },
            allSwatches = palette.swatches.map { AccentSwatchCandidate(it.rgb, it.population) }
        )
    }

    /**
     * Pure-Kotlin swatch selection & ranking across Palette roles and remaining swatches.
     * Strongly prefers vibrant swatches over muted/dominant/low-chroma swatches, and rejects
     * washed-out, pale, grey, black, and near-white candidates.
     */
    fun pickSuitableAccentFromCandidates(
        vibrant: AccentSwatchCandidate? = null,
        lightVibrant: AccentSwatchCandidate? = null,
        darkVibrant: AccentSwatchCandidate? = null,
        muted: AccentSwatchCandidate? = null,
        lightMuted: AccentSwatchCandidate? = null,
        dominant: AccentSwatchCandidate? = null,
        allSwatches: List<AccentSwatchCandidate> = emptyList()
    ): Int? {
        val maxPop = max(1, allSwatches.maxOfOrNull { it.population } ?: 1).toDouble()

        fun scoreCandidate(swatch: AccentSwatchCandidate, roleWeight: Double): Double {
            val (lightness, chroma, _) = srgbToOklch(swatch.rgb)
            val hsvSat = calculateSaturation(swatch.rgb)
            val popNorm = (swatch.population.coerceAtLeast(1) / maxPop).coerceIn(0.0, 1.0)
            // Reward bright, saturated, visually distinguishable colours without blindly picking near-white
            val visibilityBonus = if (lightness in 0.45f..0.85f) 0.35 else 0.0
            return (chroma * 5.0) + (hsvSat * 1.2) + roleWeight + visibilityBonus + (popNorm * 0.25)
        }

        // Tier 1: Strongly prefer vibrant Palette roles over muted/dominant
        val vibrantRoles = listOfNotNull(
            vibrant?.let { it to 1.60 },
            lightVibrant?.let { it to 1.45 },
            darkVibrant?.let { it to 1.25 }
        )

        var bestVibrant: Int? = null
        var bestVibrantScore = Double.NEGATIVE_INFINITY
        for ((swatch, roleWeight) in vibrantRoles) {
            if (!isSuitableForHudAccent(swatch.rgb)) continue
            val score = scoreCandidate(swatch, roleWeight)
            if (score > bestVibrantScore) {
                bestVibrantScore = score
                bestVibrant = ensureOpaque(swatch.rgb)
            }
        }
        if (bestVibrant != null) {
            return bestVibrant
        }

        // Tier 2: Secondary roles (muted / lightMuted / dominant) only when no vibrant candidate passes
        val secondaryRoles = listOfNotNull(
            muted?.let { it to 0.90 },
            lightMuted?.let { it to 0.85 },
            dominant?.let { it to 0.75 }
        )
        var bestSecondary: Int? = null
        var bestSecondaryScore = Double.NEGATIVE_INFINITY
        for ((swatch, roleWeight) in secondaryRoles) {
            if (!isSuitableForHudAccent(swatch.rgb)) continue
            val score = scoreCandidate(swatch, roleWeight)
            if (score > bestSecondaryScore) {
                bestSecondaryScore = score
                bestSecondary = ensureOpaque(swatch.rgb)
            }
        }
        if (bestSecondary != null) {
            return bestSecondary
        }

        // Tier 3: Remaining swatches scored by chroma & visibility (never blindly by population alone)
        var bestRemaining: Int? = null
        var bestRemainingScore = Double.NEGATIVE_INFINITY
        for (swatch in allSwatches) {
            if (!isSuitableForHudAccent(swatch.rgb)) continue
            val score = scoreCandidate(swatch, 0.50)
            if (score > bestRemainingScore) {
                bestRemainingScore = score
                bestRemaining = ensureOpaque(swatch.rgb)
            }
        }
        return bestRemaining
    }

    /**
     * Evaluates whether [color] is a valid, visually distinguishable chromatic candidate for dynamic HUD accenting.
     * Rejects pure black, near-black, dark/mid/light greys, washed-out pastels, near-white, and low-chroma colours.
     */
    fun isSuitableForHudAccent(@ColorInt color: Int): Boolean {
        val (lightness, chroma, _) = srgbToOklch(color)
        if (lightness < MIN_SOURCE_LIGHTNESS || lightness > MAX_SOURCE_LIGHTNESS) {
            return false
        }
        val minRequiredChroma = if (lightness > HIGH_LIGHTNESS_THRESHOLD) {
            MIN_HIGH_LIGHTNESS_CHROMA
        } else {
            MIN_CHROMA
        }
        if (chroma < minRequiredChroma) {
            return false
        }
        if (calculateSaturation(color) < MIN_HSV_SATURATION) {
            return false
        }
        return true
    }

    /**
     * Produces a hue-preserving, perceptually luminous glow colour from [sourceColor].
     * Dark chromatic colours (navy, purple, burgundy, forest green) are lifted in OKLCH lightness
     * into [[TARGET_GLOW_MIN_LIGHTNESS], [TARGET_GLOW_MAX_LIGHTNESS]] while preserving hue and
     * guaranteeing relative luminance >= [MIN_GLOW_LUMINANCE].
     */
    @ColorInt
    fun generateGlowColor(@ColorInt sourceColor: Int): Int {
        val opaque = ensureOpaque(sourceColor)
        val (srcL, srcC, srcH) = srgbToOklch(opaque)
        var targetL = srcL.coerceIn(TARGET_GLOW_MIN_LIGHTNESS, TARGET_GLOW_MAX_LIGHTNESS)
        val targetC = max(srcC, TARGET_GLOW_MIN_CHROMA)

        var candidate = oklchToSrgbGamutMapped(targetL, targetC, srcH)
        var guard = 0
        while (calculateLuminance(candidate) < MIN_GLOW_LUMINANCE && targetL < 0.90f && guard < 12) {
            targetL = (targetL + 0.02f).coerceAtMost(0.90f)
            candidate = oklchToSrgbGamutMapped(targetL, targetC, srcH)
            guard++
        }
        return ensureOpaque(candidate)
    }

    /**
     * Returns [sourceColor] verbatim when it already achieves [MIN_ACTIVE_TEXT_CONTRAST] against the
     * dark HUD backdrop; otherwise lifts OKLCH lightness minimally (preserving hue) until legible.
     */
    @ColorInt
    fun resolveActiveTextColor(@ColorInt sourceColor: Int): Int {
        val opaque = ensureOpaque(sourceColor)
        if (HudColorSystem.contrastRatio(opaque, HudColorSystem.BLACK_BACKDROP_COLOR) >= MIN_ACTIVE_TEXT_CONTRAST) {
            return opaque
        }
        val (srcL, srcC, srcH) = srgbToOklch(opaque)
        var low = srcL.coerceIn(0f, 0.95f)
        var high = 0.95f
        var best = oklchToSrgbGamutMapped(high, srcC, srcH)
        repeat(16) {
            val mid = (low + high) * 0.5f
            val probe = oklchToSrgbGamutMapped(mid, srcC, srcH)
            if (HudColorSystem.contrastRatio(probe, HudColorSystem.BLACK_BACKDROP_COLOR) >= MIN_ACTIVE_TEXT_CONTRAST) {
                best = probe
                high = mid
            } else {
                low = mid
            }
        }
        return ensureOpaque(best)
    }

    /**
     * Converts an sRGB [color] to OKLCH `[L, C, hDegrees]`.
     */
    fun srgbToOklch(@ColorInt color: Int): FloatArray {
        val r = linearizeChannel((color ushr 16) and 0xFF)
        val g = linearizeChannel((color ushr 8) and 0xFF)
        val b = linearizeChannel(color and 0xFF)

        val l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
        val m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
        val s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b

        val lRoot = cbrt(l)
        val mRoot = cbrt(m)
        val sRoot = cbrt(s)

        val okL = 0.2104542553 * lRoot + 0.7936177850 * mRoot - 0.0040720468 * sRoot
        val okA = 1.9779984951 * lRoot - 2.4285922050 * mRoot + 0.4505937099 * sRoot
        val okB = 0.0259040371 * lRoot + 0.7827717662 * mRoot - 0.8086757660 * sRoot

        val chroma = hypot(okA, okB)
        var hue = Math.toDegrees(atan2(okB, okA))
        if (hue < 0.0) hue += 360.0

        return floatArrayOf(
            okL.toFloat().coerceIn(0f, 1f),
            chroma.toFloat().coerceAtLeast(0f),
            hue.toFloat().let { if (it >= 360f) it - 360f else it }
        )
    }

    /**
     * Converts OKLCH `[L, C, hDegrees]` back to an opaque sRGB colour, reducing chroma via binary search
     * if needed so hue [hDegrees] and lightness [lightness] stay intact within the sRGB gamut.
     */
    @ColorInt
    fun oklchToSrgbGamutMapped(lightness: Float, chroma: Float, hDegrees: Float): Int {
        val l = lightness.toDouble().coerceIn(0.0, 1.0)
        val hRad = Math.toRadians(hDegrees.toDouble())
        val cosH = cos(hRad)
        val sinH = sin(hRad)

        fun toLinearRgb(c: Double): Triple<Double, Double, Double> {
            val a = c * cosH
            val b = c * sinH
            val lRoot = l + 0.3963377774 * a + 0.2158037573 * b
            val mRoot = l - 0.1055613458 * a - 0.0638541728 * b
            val sRoot = l - 0.0894841775 * a - 1.2914855480 * b

            val lLin = lRoot * lRoot * lRoot
            val mLin = mRoot * mRoot * mRoot
            val sLin = sRoot * sRoot * sRoot

            val rLin = +4.0767416621 * lLin - 3.3077115913 * mLin + 0.2309699292 * sLin
            val gLin = -1.2684380046 * lLin + 2.6097574011 * mLin - 0.3413193965 * sLin
            val bLin = -0.0041960863 * lLin - 0.7034186147 * mLin + 1.7076147010 * sLin
            return Triple(rLin, gLin, bLin)
        }

        val initialC = chroma.toDouble().coerceAtLeast(0.0)
        val direct = toLinearRgb(initialC)
        val chosen = if (inGamut(direct)) {
            direct
        } else {
            var low = 0.0
            var high = initialC
            var best = toLinearRgb(0.0)
            repeat(16) {
                val mid = (low + high) * 0.5
                val probe = toLinearRgb(mid)
                if (inGamut(probe)) {
                    best = probe
                    low = mid
                } else {
                    high = mid
                }
            }
            best
        }

        val r = encodeSrgbChannel(chosen.first)
        val g = encodeSrgbChannel(chosen.second)
        val b = encodeSrgbChannel(chosen.third)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun inGamut(rgb: Triple<Double, Double, Double>): Boolean {
        val eps = -0.0005
        val maxEps = 1.0005
        return rgb.first in eps..maxEps && rgb.second in eps..maxEps && rgb.third in eps..maxEps
    }

    private fun linearizeChannel(channel: Int): Double {
        val c = channel.coerceIn(0, 255) / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun encodeSrgbChannel(linear: Double): Int {
        val clamped = linear.coerceIn(0.0, 1.0)
        val srgb = if (clamped <= 0.0031308) {
            12.92 * clamped
        } else {
            1.055 * clamped.pow(1.0 / 2.4) - 0.055
        }
        return (srgb * 255.0).roundToInt().coerceIn(0, 255)
    }

    /**
     * Calculates perceived brightness using standard ITU-R BT.601 coefficients (0.0 to 1.0).
     */
    fun calculatePerceivedBrightness(@ColorInt color: Int): Double {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
    }

    /**
     * Calculates relative luminance of [color] in sRGB according to WCAG 2.1 standard (0.0 = darkest black, 1.0 = brightest white).
     */
    fun calculateLuminance(@ColorInt color: Int): Double {
        val rL = linearizeChannel((color ushr 16) and 0xFF)
        val gL = linearizeChannel((color ushr 8) and 0xFF)
        val bL = linearizeChannel(color and 0xFF)
        return 0.2126 * rL + 0.7152 * gL + 0.0722 * bL
    }

    /**
     * Calculates HSV saturation of [color] (0.0 to 1.0).
     */
    fun calculateSaturation(@ColorInt color: Int): Float {
        val r = ((color ushr 16) and 0xFF) / 255.0f
        val g = ((color ushr 8) and 0xFF) / 255.0f
        val b = (color and 0xFF) / 255.0f
        val maxVal = maxOf(r, g, b)
        val minVal = minOf(r, g, b)
        val delta = maxVal - minVal
        return if (maxVal == 0f) 0f else delta / maxVal
    }

    @ColorInt
    fun ensureOpaque(@ColorInt color: Int): Int {
        return color or 0xFF000000.toInt()
    }
}
