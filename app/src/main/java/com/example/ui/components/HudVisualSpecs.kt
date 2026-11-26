package com.example.ui.components

import kotlin.math.roundToInt

data class BackdropAppearanceSpec(
    val style: String,
    val fillColor: Int,
    val fillAlpha: Int
)

data class LyricColorHierarchy(
    val baseAlpha: Int,
    val activeAlpha: Int,
    val inactiveAlpha: Int,
    val outgoingAlpha: Int,
    val activeColor: Int = HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR,
    val inactiveColor: Int = HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR
)

data class BackdropRenderSpec(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cornerRadiusPx: Float,
    val style: String,
    val fillColor: Int,
    val fillAlpha: Int
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
}

object HudVisualSpecs {
    const val BLACK_BACKDROP_COLOR: Int = HudColorSystem.BLACK_BACKDROP_COLOR
    const val GREY_BACKDROP_COLOR: Int = HudColorSystem.GREY_BACKDROP_COLOR

    const val ACTIVE_LYRIC_COLOR: Int = HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR
    const val INACTIVE_LYRIC_COLOR: Int = HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR
    const val INACTIVE_LYRIC_ALPHA_RATIO: Float = 0.64f
    const val OUTGOING_LYRIC_ALPHA_RATIO: Float = 0.44f
    const val ACTIVE_SHADOW_ALPHA: Int = 215
    const val INACTIVE_SHADOW_ALPHA: Int = 95
    const val ACTIVE_GLOW_ALPHA: Int = 140
    const val PEAK_GLOW_ALPHA: Int = 200
    const val WORD_GLOW_SETTLE_MS: Long = 140L

    fun computeRestingGlowRadiusPx(textSizePx: Float): Float {
        return (textSizePx * 0.24f).coerceIn(5f, 12f)
    }

    fun computePeakGlowRadiusPx(textSizePx: Float): Float {
        return (textSizePx * 0.48f).coerceIn(10f, 20f)
    }

    /**
     * Computes a smooth 0f..1f luminous envelope for an active karaoke word or line:
     * ramps in smoothly during attack, blooms near mid-word, holds warm through completion,
     * and eases back to 0f over [settleMs] after [endMs].
     */
    fun computeWordLuminousEnvelope(
        startMs: Long,
        endMs: Long,
        currentPositionMs: Long,
        progress: Float,
        settleMs: Long = WORD_GLOW_SETTLE_MS
    ): Float {
        if (currentPositionMs < startMs || progress <= 0f) return 0f
        if (currentPositionMs < endMs) {
            val p = progress.coerceIn(0f, 1f)
            val attackT = (p / 0.22f).coerceIn(0f, 1f)
            val attackEase = 1f - (1f - attackT) * (1f - attackT)
            val bell = kotlin.math.sin(p * kotlin.math.PI).toFloat().coerceIn(0f, 1f)
            return maxOf(attackEase * 0.72f, bell).coerceIn(0f, 1f)
        }
        if (settleMs <= 0L) return 0f
        val elapsedSinceEnd = (currentPositionMs - endMs).coerceAtLeast(0L)
        if (elapsedSinceEnd >= settleMs) return 0f
        val settleRemaining = 1f - (elapsedSinceEnd.toFloat() / settleMs.toFloat())
        return (0.72f * settleRemaining * settleRemaining).coerceIn(0f, 1f)
    }

    fun computeGlowAlpha(baseActiveAlpha: Int, intensity: Float): Int {
        return (baseActiveAlpha.coerceIn(0, 255) * intensity.coerceIn(0f, 1f))
            .roundToInt()
            .coerceIn(0, 255)
    }

    fun normalizeBackdropStyle(style: String?): String {
        if (style.isNullOrBlank()) return "BLACK"
        return when (style.trim().uppercase(java.util.Locale.US)) {
            "GREY", "GRAY" -> "GREY"
            else -> "BLACK"
        }
    }

    fun computeBackdropAppearance(style: String, opacity: Float): BackdropAppearanceSpec {
        val normalizedStyle = normalizeBackdropStyle(style)
        val clampedOpacity = opacity.coerceIn(0f, 1f)
        val fillColor = if (normalizedStyle == "GREY") GREY_BACKDROP_COLOR else BLACK_BACKDROP_COLOR
        val fillAlpha = (clampedOpacity * 255f).roundToInt().coerceIn(0, 255)
        return BackdropAppearanceSpec(
            style = normalizedStyle,
            fillColor = fillColor,
            fillAlpha = fillAlpha
        )
    }

    fun computeLyricColorHierarchy(
        activeColor: Int = HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR,
        inactiveColor: Int = HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR,
        textOpacity: Float,
        animProgress: Float = 1f,
        isFadeStyle: Boolean = false
    ): LyricColorHierarchy {
        val safeActive = HudColorSystem.sanitizeOpaqueColor(activeColor, HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR)
        val safeInactive = HudColorSystem.sanitizeOpaqueColor(inactiveColor, HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR)
        val clampedOpacity = textOpacity.coerceIn(0.15f, 1.0f)
        val clampedProgress = animProgress.coerceIn(0f, 1f)
        val baseAlpha = (255f * clampedOpacity).roundToInt().coerceIn(0, 255)
        val activeAlpha = (baseAlpha * clampedProgress).roundToInt().coerceIn(0, 255)
        val inactiveAlpha = (activeAlpha * INACTIVE_LYRIC_ALPHA_RATIO).roundToInt().coerceIn(0, 255)
        val outgoingRatio = if (isFadeStyle) 1f else OUTGOING_LYRIC_ALPHA_RATIO
        val outgoingAlpha = (baseAlpha * (1f - clampedProgress) * outgoingRatio).roundToInt().coerceIn(0, 255)
        return LyricColorHierarchy(
            baseAlpha = baseAlpha,
            activeAlpha = activeAlpha,
            inactiveAlpha = inactiveAlpha,
            outgoingAlpha = outgoingAlpha,
            activeColor = safeActive,
            inactiveColor = safeInactive
        )
    }

    fun computeBackdropSpec(
        enabled: Boolean,
        style: String,
        opacity: Float,
        cornerRadiusDp: Float,
        horizontalPaddingDp: Float,
        verticalPaddingDp: Float,
        density: Float,
        viewWidth: Int,
        viewHeight: Int,
        textLeft: Float,
        textTop: Float,
        textRight: Float,
        textBottom: Float,
        hasVisibleText: Boolean = true,
        boundsAlreadyExpanded: Boolean = false
    ): BackdropRenderSpec? {
        val clampedOpacity = opacity.coerceIn(0f, 1f)
        if (!enabled || !hasVisibleText || clampedOpacity <= 0f || viewWidth <= 0 || viewHeight <= 0) {
            return null
        }
        if (textRight <= textLeft || textBottom <= textTop) {
            return null
        }

        val safeDensity = density.coerceAtLeast(0.5f)
        val horizPadPx = if (boundsAlreadyExpanded) 0f else horizontalPaddingDp.coerceIn(0f, 32f) * safeDensity
        val vertPadPx = if (boundsAlreadyExpanded) 0f else verticalPaddingDp.coerceIn(0f, 24f) * safeDensity

        val left = (textLeft - horizPadPx).coerceAtLeast(2f)
        val top = (textTop - vertPadPx).coerceAtLeast(2f)
        val right = (textRight + horizPadPx).coerceAtMost((viewWidth - 2f).coerceAtLeast(left))
        val bottom = (textBottom + vertPadPx).coerceAtMost((viewHeight - 2f).coerceAtLeast(top))

        if (right <= left || bottom <= top) {
            return null
        }

        val maxRadiusPx = (minOf(right - left, bottom - top) / 2f).coerceAtLeast(0f)
        val cornerRadiusPx = (cornerRadiusDp.coerceIn(0f, 32f) * safeDensity).coerceIn(0f, maxRadiusPx)
        val appearance = computeBackdropAppearance(style, clampedOpacity)

        return BackdropRenderSpec(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            cornerRadiusPx = cornerRadiusPx,
            style = appearance.style,
            fillColor = appearance.fillColor,
            fillAlpha = appearance.fillAlpha
        )
    }

    fun computeGraphemeBoundaries(text: String): IntArray {
        if (text.isEmpty()) return intArrayOf()
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(text)
        val boundaries = mutableListOf<Int>()
        var end = it.next()
        while (end != java.text.BreakIterator.DONE) {
            boundaries.add(end)
            end = it.next()
        }
        return boundaries.toIntArray()
    }
}
