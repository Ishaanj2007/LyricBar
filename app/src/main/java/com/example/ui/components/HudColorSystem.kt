package com.example.ui.components

import com.example.data.preferences.HudBackdropStyle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

enum class HudColorTarget(
    val title: String,
    val subtitle: String
) {
    ACTIVE_LYRIC(
        title = "Active Lyric Colour",
        subtitle = "Currently singing or highlighted lyric line"
    ),
    INACTIVE_LYRIC(
        title = "Inactive Lyric Colour",
        subtitle = "Surrounding or upcoming lyric words and lines"
    ),
    ACCENT(
        title = "Accent Colour (Optional)",
        subtitle = "Subtle selection ring and secondary HUD details"
    )
}

data class HudColorPreset(
    val id: String,
    val label: String,
    val argb: Int
)

data class HudContrastWarning(
    val message: String,
    val suggestedActiveColor: Int,
    val suggestedInactiveColor: Int
)

object HudColorSystem {

    const val BLACK_BACKDROP_COLOR: Int = 0xFF0A0A0A.toInt()
    const val GREY_BACKDROP_COLOR: Int = 0xFFB8B8B8.toInt()

    const val DEFAULT_ACTIVE_LYRIC_COLOR: Int = 0xFFFFFFFF.toInt()
    const val DEFAULT_INACTIVE_LYRIC_COLOR: Int = 0xFFD4D8E0.toInt()
    const val DEFAULT_ACCENT_COLOR: Int = 0xFFC4B5FD.toInt()

    const val MAX_SAVED_CUSTOM_COLORS: Int = 8

    val curatedPresets: List<HudColorPreset> = listOf(
        HudColorPreset(id = "white", label = "White", argb = 0xFFFFFFFF.toInt()),
        HudColorPreset(id = "soft_white", label = "Soft White", argb = 0xFFD4D8E0.toInt()),
        HudColorPreset(id = "lavender", label = "Lavender", argb = 0xFFC4B5FD.toInt()),
        HudColorPreset(id = "sky_blue", label = "Sky Blue", argb = 0xFF7DD3FC.toInt()),
        HudColorPreset(id = "mint", label = "Mint", argb = 0xFF6EE7B7.toInt()),
        HudColorPreset(id = "rose", label = "Rose", argb = 0xFFFDA4AF.toInt()),
        HudColorPreset(id = "amber", label = "Amber", argb = 0xFFFCD34D.toInt()),
        HudColorPreset(id = "peach", label = "Peach", argb = 0xFFFDBA74.toInt()),
        HudColorPreset(id = "cool_grey", label = "Grey", argb = 0xFFA1A6B0.toInt()),
        HudColorPreset(id = "medium_grey", label = "Medium Grey", argb = 0xFF4A4A50.toInt()),
        HudColorPreset(id = "deep_blue", label = "Deep Blue", argb = 0xFF1E40AF.toInt()),
        HudColorPreset(id = "charcoal_dark", label = "Dark", argb = 0xFF141418.toInt())
    )

    fun argbColor(alpha: Int, red: Int, green: Int, blue: Int): Int {
        return ((alpha.coerceIn(0, 255) shl 24) or
            (red.coerceIn(0, 255) shl 16) or
            (green.coerceIn(0, 255) shl 8) or
            blue.coerceIn(0, 255))
    }

    /**
     * Ensures stored or custom color values are valid opaque RGB integers (`0xFFRRGGBB`).
     * Fully transparent (`alpha == 0`) or null inputs fall back safely so lyrics never vanish silently.
     */
    fun sanitizeOpaqueColor(rawArgb: Int?, fallback: Int): Int {
        if (rawArgb == null) return (fallback or 0xFF000000.toInt())
        val alpha = (rawArgb ushr 24) and 0xFF
        if (alpha == 0) return (fallback or 0xFF000000.toInt())
        return (rawArgb or 0xFF000000.toInt())
    }

    fun formatHexColor(argb: Int): String {
        val rgb = argb and 0x00FFFFFF
        return String.format(java.util.Locale.US, "#%06X", rgb)
    }

    fun labelForColor(argb: Int): String {
        val opaque = sanitizeOpaqueColor(argb, DEFAULT_ACTIVE_LYRIC_COLOR)
        return curatedPresets.firstOrNull { it.argb == opaque }?.label ?: formatHexColor(opaque)
    }

    fun parseHexColorOrNull(rawInput: String?): Int? {
        if (rawInput.isNullOrBlank()) return null
        val cleaned = rawInput.trim().removePrefix("#").uppercase(java.util.Locale.US)
        val rgbHex = when (cleaned.length) {
            6 -> cleaned
            8 -> {
                val alphaByte = cleaned.substring(0, 2).toIntOrNull(16) ?: return null
                if (alphaByte == 0) return null
                cleaned.substring(2)
            }
            else -> return null
        }
        if (rgbHex.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
        val rgb = rgbHex.toIntOrNull(16) ?: return null
        return (0xFF000000.toInt() or rgb)
    }

    fun serializeCustomColors(colors: List<Int>): String {
        return colors
            .mapNotNull { raw ->
                val alpha = (raw ushr 24) and 0xFF
                if (alpha == 0) null else formatHexColor(raw)
            }
            .distinct()
            .take(MAX_SAVED_CUSTOM_COLORS)
            .joinToString(",")
    }

    fun deserializeCustomColors(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',')
            .mapNotNull { token -> parseHexColorOrNull(token) }
            .distinct()
            .take(MAX_SAVED_CUSTOM_COLORS)
    }

    fun backdropColorForStyle(style: HudBackdropStyle): Int {
        return when (style) {
            HudBackdropStyle.BLACK -> BLACK_BACKDROP_COLOR
            HudBackdropStyle.GREY -> GREY_BACKDROP_COLOR
        }
    }

    fun relativeLuminance(argb: Int): Double {
        fun linearize(channel: Int): Double {
            val c = channel.coerceIn(0, 255) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        val r = linearize((argb ushr 16) and 0xFF)
        val g = linearize((argb ushr 8) and 0xFF)
        val b = linearize(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun isDarkColor(argb: Int): Boolean {
        return relativeLuminance(argb) < 0.32
    }

    fun contrastRatio(foregroundArgb: Int, backgroundArgb: Int): Double {
        val l1 = relativeLuminance(foregroundArgb)
        val l2 = relativeLuminance(backgroundArgb)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    fun blendOver(foregroundArgb: Int, alpha: Float, backgroundArgb: Int): Int {
        val a = alpha.coerceIn(0f, 1f)
        val inv = 1f - a
        val fgR = (foregroundArgb ushr 16) and 0xFF
        val fgG = (foregroundArgb ushr 8) and 0xFF
        val fgB = foregroundArgb and 0xFF
        val bgR = (backgroundArgb ushr 16) and 0xFF
        val bgG = (backgroundArgb ushr 8) and 0xFF
        val bgB = backgroundArgb and 0xFF
        return argbColor(
            alpha = 255,
            red = (fgR * a + bgR * inv).roundToInt(),
            green = (fgG * a + bgG * inv).roundToInt(),
            blue = (fgB * a + bgB * inv).roundToInt()
        )
    }

    fun rgbToHsv(argb: Int): FloatArray {
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val delta = maxC - minC

        val hue = when {
            delta <= 0.0001f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }.coerceIn(0f, 360f)

        val sat = if (maxC <= 0.0001f) 0f else (delta / maxC).coerceIn(0f, 1f)
        val value = maxC.coerceIn(0f, 1f)
        return floatArrayOf(hue, sat, value)
    }

    fun hsvToArgb(hue: Float, saturation: Float, value: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - abs(((h / 60f) % 2f) - 1f))
        val m = v - c
        val (rf, gf, bf) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return argbColor(
            alpha = 255,
            red = ((rf + m) * 255f).roundToInt(),
            green = ((gf + m) * 255f).roundToInt(),
            blue = ((bf + m) * 255f).roundToInt()
        )
    }

    /**
     * Evaluates readability without altering user selections. Returns a warning and suggested
     * high-contrast defaults only when the active/inactive colours risk disappearing against the backdrop.
     */
    fun evaluateReadability(
        backdropEnabled: Boolean,
        backdropStyle: HudBackdropStyle,
        backdropOpacity: Float,
        activeColor: Int,
        inactiveColor: Int
    ): HudContrastWarning? {
        val safeActive = sanitizeOpaqueColor(activeColor, DEFAULT_ACTIVE_LYRIC_COLOR)
        val safeInactive = sanitizeOpaqueColor(inactiveColor, DEFAULT_INACTIVE_LYRIC_COLOR)

        val suggestedActive = when (backdropStyle) {
            HudBackdropStyle.BLACK -> DEFAULT_ACTIVE_LYRIC_COLOR
            HudBackdropStyle.GREY -> 0xFF141418.toInt()
        }
        val suggestedInactive = when (backdropStyle) {
            HudBackdropStyle.BLACK -> DEFAULT_INACTIVE_LYRIC_COLOR
            HudBackdropStyle.GREY -> 0xFF4A4A50.toInt()
        }

        if (backdropEnabled && backdropOpacity >= 0.40f) {
            val backdropBg = backdropColorForStyle(backdropStyle)
            val activeContrast = contrastRatio(safeActive, backdropBg)
            val inactiveContrast = contrastRatio(safeInactive, backdropBg)

            if (activeContrast < 3.0) {
                val hint = if (backdropStyle == HudBackdropStyle.BLACK) {
                    "Active lyric colour is too dark against the Black backdrop."
                } else {
                    "Active lyric colour is too light or low-contrast against the Grey backdrop."
                }
                return HudContrastWarning(
                    message = "$hint Choose a higher-contrast colour or apply recommended defaults.",
                    suggestedActiveColor = suggestedActive,
                    suggestedInactiveColor = suggestedInactive
                )
            }

            if (inactiveContrast < 2.2) {
                val hint = if (backdropStyle == HudBackdropStyle.BLACK) {
                    "Inactive lyric colour may blend into the Black backdrop."
                } else {
                    "Inactive lyric colour may be hard to read against the Grey backdrop."
                }
                return HudContrastWarning(
                    message = "$hint Choose a more distinct tone or apply recommended defaults.",
                    suggestedActiveColor = suggestedActive,
                    suggestedInactiveColor = suggestedInactive
                )
            }
        }

        val activeInactiveContrast = contrastRatio(safeActive, safeInactive)
        val rgbDelta = abs(((safeActive ushr 16) and 0xFF) - ((safeInactive ushr 16) and 0xFF)) +
            abs(((safeActive ushr 8) and 0xFF) - ((safeInactive ushr 8) and 0xFF)) +
            abs((safeActive and 0xFF) - (safeInactive and 0xFF))

        if (activeInactiveContrast < 1.15 && rgbDelta < 45) {
            return HudContrastWarning(
                message = "Active and inactive lyric colours look very similar, so the active line may not stand out clearly.",
                suggestedActiveColor = suggestedActive,
                suggestedInactiveColor = suggestedInactive
            )
        }

        return null
    }
}
