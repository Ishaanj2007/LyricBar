package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.compositionLocalOf
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.example.data.model.TrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import java.io.InputStream

val LocalHazeState = compositionLocalOf<HazeState?> { null }

/**
 * Canvas-level path clipping helper ensuring child GraphicsLayers (such as Haze offscreen buffers)
 * strictly respect the container shape bounds without unclipped 90-degree square corners.
 */
fun Modifier.clipToShape(shape: Shape): Modifier = this.drawWithContent {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path().apply { addOutline(outline) }
    clipPath(path) {
        this@drawWithContent.drawContent()
    }
}

/**
 * Reusable frosted glass modifier for LyricBar V2.
 * Provides backdrop-sampled blur or translucent fallback, subtle theme-derived border, and soft depth.
 */
fun Modifier.frostedGlass(
    hazeState: HazeState? = null,
    shape: Shape = RoundedCornerShape(20.dp),
    backgroundColor: Color = Color.Unspecified,
    backgroundAlpha: Float = 0.50f,
    blurRadius: Dp = 20.dp,
    borderWidth: Dp = 1.dp,
    borderColor: Color = Color.Unspecified,
    borderAlpha: Float = 0.22f,
    shadowElevation: Dp = 4.dp
): Modifier {
    val hazeMod = if (hazeState != null) {
        val resolvedTint = if (backgroundColor.isSpecified) {
            backgroundColor.copy(alpha = backgroundAlpha)
        } else {
            Color(0xFF18181C).copy(alpha = backgroundAlpha)
        }
        Modifier.hazeEffect(
            state = hazeState,
            style = HazeStyle(
                backgroundColor = Color.Transparent,
                tint = HazeTint(resolvedTint),
                blurRadius = blurRadius,
                noiseFactor = 0.10f
            )
        )
    } else {
        Modifier.drawBehind {
            val resolvedBg = if (backgroundColor != Color.Unspecified) {
                backgroundColor.copy(alpha = backgroundAlpha)
            } else {
                Color.Transparent
            }
            if (resolvedBg != Color.Transparent) {
                drawRect(resolvedBg)
            }
        }
    }

    return this.then(
        Modifier
            .shadow(elevation = shadowElevation, shape = shape, clip = false)
            .clip(shape)
            .then(hazeMod)
            .graphicsLayer {
                this.shape = shape
                this.clip = true
            }
            .clipToShape(shape)
            .then(
                if (borderWidth > 0.dp && borderColor != Color.Unspecified) {
                    Modifier.border(
                        width = borderWidth,
                        color = borderColor.copy(alpha = borderAlpha),
                        shape = shape
                    )
                } else {
                    Modifier
                }
            )
    )
}

/**
 * Theme-derived glow modifier for active states (playing button, selected pill, active lyric).
 */
fun Modifier.themeGlow(
    color: Color = Color.Unspecified,
    alpha: Float = 0.65f,
    radius: Dp = 16.dp,
    shape: Shape = RoundedCornerShape(16.dp)
): Modifier = this.drawBehind {
    val glowColor = if (color != Color.Unspecified) color.copy(alpha = alpha) else Color.Transparent
    if (glowColor != Color.Transparent) {
        drawCircle(
            color = glowColor,
            radius = size.maxDimension / 2f + radius.toPx(),
            center = center
        )
    }
}

/**
 * Primary Frosted Card component with theme-derived translucency, true backdrop blur, soft outline, and gentle elevation.
 */
/**
 * Primary Frosted Card component with theme-derived translucency, true backdrop blur, soft outline, and gentle elevation.
 */
@Composable
fun FrostedCard(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = LocalHazeState.current,
    shape: Shape = RoundedCornerShape(20.dp),
    backgroundAlpha: Float = 0.20f,
    blurRadius: Dp = 22.dp,
    borderWidth: Dp = 0.75.dp,
    borderAlpha: Float = 0.25f,
    shadowElevation: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

    val hazeModifier = if (hazeState != null) {
        Modifier.hazeEffect(
            state = hazeState,
            style = HazeStyle(
                backgroundColor = Color.Transparent,
                tint = HazeTint(surfaceColor.copy(alpha = backgroundAlpha)),
                blurRadius = blurRadius,
                noiseFactor = 0f
            )
        )
    } else {
        Modifier.background(surfaceColor.copy(alpha = backgroundAlpha))
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                this.shape = shape
                this.clip = true
            }
            .clip(shape)
            .then(hazeModifier)
            .clipToShape(shape)
            .border(
                width = borderWidth,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        onSurface.copy(alpha = 0.22f),
                        primaryColor.copy(alpha = 0.12f),
                        onSurface.copy(alpha = 0.06f)
                    )
                ),
                shape = shape
            ),
        content = content
    )
}

/**
 * Tonal Surface for dense lists, history rows, and settings groups where readability is paramount.
 */
@Composable
fun TonalSurface(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = LocalHazeState.current,
    shape: Shape = RoundedCornerShape(16.dp),
    backgroundAlpha: Float = 0.35f,
    blurRadius: Dp = 18.dp,
    borderWidth: Dp = 0.75.dp,
    borderAlpha: Float = 0.20f,
    shadowElevation: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainer
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

    val hazeModifier = if (hazeState != null) {
        Modifier.hazeEffect(
            state = hazeState,
            style = HazeStyle(
                backgroundColor = Color.Transparent,
                tint = HazeTint(containerColor.copy(alpha = backgroundAlpha)),
                blurRadius = blurRadius,
                noiseFactor = 0f
            )
        )
    } else {
        Modifier.background(containerColor.copy(alpha = backgroundAlpha))
    }

    Box(
        modifier = modifier
            .graphicsLayer {
                this.shape = shape
                this.clip = true
            }
            .clip(shape)
            .then(hazeModifier)
            .clipToShape(shape)
            .border(
                width = borderWidth,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        onSurface.copy(alpha = 0.20f),
                        primaryColor.copy(alpha = 0.10f),
                        onSurface.copy(alpha = 0.06f)
                    )
                ),
                shape = shape
            ),
        content = content
    )
}

/**
 * Calm themed background used on Lyrics, Settings, History, and Search screens.
 * Provides subtle ambient depth and serves as the backdrop blur source.
 */
@Composable
fun CalmThemedBackground(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    content: (@Composable BoxScope.() -> Unit)? = null
) {
    val backgroundColor = MaterialTheme.colorScheme.background
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // Atmospheric ambient glows (marked as haze source for backdrop sampling)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (hazeState != null) Modifier.hazeSource(state = hazeState) else Modifier)
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                primaryColor.copy(alpha = 0.18f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.85f, size.height * 0.18f),
                            radius = size.width * 0.85f
                        )
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                surfaceContainer.copy(alpha = 0.40f),
                                primaryColor.copy(alpha = 0.10f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.15f, size.height * 0.80f),
                            radius = size.width * 0.70f
                        )
                    )
                }
        )

        if (content != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                content = content
            )
        }
    }
}

/**
 * Fast downsample-bilinear blur fallback.
 * Produces a smooth, artifact-free, soft ambient texture from the artwork bitmap.
 * Guarantees rich, silky ambient backdrop blur across all Android versions and GPUs.
 */
fun createBlurredArtworkBitmap(source: Bitmap): Bitmap? {
    return try {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        val tinyW = 32
        val tinyH = (32 * (source.height.toFloat() / source.width.toFloat())).toInt().coerceIn(24, 64)
        val tiny = Bitmap.createScaledBitmap(source, tinyW, tinyH, true)
        val blurred = Bitmap.createScaledBitmap(tiny, 280, (280 * (tinyH.toFloat() / tinyW.toFloat())).toInt(), true)
        if (tiny != blurred && !tiny.isRecycled) {
            tiny.recycle()
        }
        blurred
    } catch (_: Throwable) {
        null
    }
}

data class ArtworkGlowPalette(
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val topColor: Color = primary,
    val bottomColor: Color = secondary
)

/**
 * Extracts a luminous, richly saturated color palette from the artwork.
 * Actively prioritizes saturated hues over muddy dark averages, ensuring dark artworks,
 * bright artworks, and black-background albums alike produce incandescent, complementary glows.
 * Handles monochromatic / black & white albums gracefully with sleek ambient slate tones.
 */
fun extractArtworkGlowPalette(bitmap: Bitmap?, fallbackColor: Color = Color.Unspecified): ArtworkGlowPalette {
    if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
        val safeFallback = if (fallbackColor.isSpecified) fallbackColor else Color(0xFF6C5CE7)
        return ArtworkGlowPalette(
            primary = safeFallback,
            secondary = safeFallback,
            accent = safeFallback,
            topColor = safeFallback,
            bottomColor = safeFallback
        )
    }

    return try {
        val width = bitmap.width
        val height = bitmap.height
        val hsv = FloatArray(3)

        var bestVibrantScore = -1f
        var bestVibrantColor = 0

        var bestSecondaryScore = -1f
        var bestSecondaryColor = 0

        var topBestScore = -1f
        var topBestColor = 0

        var bottomBestScore = -1f
        var bottomBestColor = 0

        var maxSaturation = 0f
        var avgR = 0L
        var avgG = 0L
        var avgB = 0L
        var count = 0

        val stepX = (width / 14).coerceAtLeast(1)
        val stepY = (height / 14).coerceAtLeast(1)
        val halfY = height / 2

        for (x in stepX until width step stepX) {
            for (y in stepY until height step stepY) {
                val pixel = bitmap.getPixel(x, y)
                val a = android.graphics.Color.alpha(pixel)
                if (a < 100) continue

                val r = android.graphics.Color.red(pixel)
                val g = android.graphics.Color.green(pixel)
                val b = android.graphics.Color.blue(pixel)

                avgR += r
                avgG += g
                avgB += b
                count++

                android.graphics.Color.colorToHSV(pixel, hsv)
                val sat = hsv[1]
                val value = hsv[2]

                if (sat > maxSaturation) {
                    maxSaturation = sat
                }

                // Filter out near-black or muddy pixels while favoring luminous saturation
                if (value > 0.18f && sat > 0.10f) {
                    val score = sat * 1.8f + (if (value in 0.35f..0.85f) 1.0f else 0.4f)
                    if (score > bestVibrantScore) {
                        bestVibrantScore = score
                        bestVibrantColor = pixel
                    } else if (score > bestSecondaryScore) {
                        val bestHue = FloatArray(3).also { android.graphics.Color.colorToHSV(bestVibrantColor, it) }[0]
                        if (Math.abs(hsv[0] - bestHue) > 30f) {
                            bestSecondaryScore = score
                            bestSecondaryColor = pixel
                        }
                    }

                    // Spatial top vs bottom sampling
                    if (y < halfY) {
                        if (score > topBestScore) {
                            topBestScore = score
                            topBestColor = pixel
                        }
                    } else {
                        if (score > bottomBestScore) {
                            bottomBestScore = score
                            bottomBestColor = pixel
                        }
                    }
                }
            }
        }

        // Monochromatic / black & white artwork handling:
        // When artwork lacks color saturation, avoid forcing arbitrary purple.
        // Instead, provide a sophisticated, luminous slate-silver atmospheric tone.
        if (maxSaturation < 0.08f) {
            val slateTone = Color(0xFF8A99AD)
            val slateTop = Color(0xFFA0AEC0)
            val slateBottom = Color(0xFF718096)
            return ArtworkGlowPalette(
                primary = slateTone,
                secondary = slateTone,
                accent = slateTone.copy(alpha = 0.85f),
                topColor = slateTop,
                bottomColor = slateBottom
            )
        }

        val primaryColor = if (bestVibrantScore > 0f) {
            android.graphics.Color.colorToHSV(bestVibrantColor, hsv)
            hsv[1] = hsv[1].coerceIn(0.55f, 0.95f) // Rich saturation
            hsv[2] = hsv[2].coerceIn(0.70f, 0.92f) // Incandescent brightness (clamped to prevent overexposure)
            Color(android.graphics.Color.HSVToColor(hsv))
        } else if (count > 0) {
            val r = (avgR / count).toInt()
            val g = (avgG / count).toInt()
            val b = (avgB / count).toInt()
            android.graphics.Color.RGBToHSV(r, g, b, hsv)
            hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
            hsv[2] = hsv[2].coerceIn(0.65f, 0.90f)
            Color(android.graphics.Color.HSVToColor(hsv))
        } else {
            if (fallbackColor.isSpecified) fallbackColor else Color(0xFF6C5CE7)
        }

        val secondaryColor = if (bestSecondaryScore > 0f) {
            android.graphics.Color.colorToHSV(bestSecondaryColor, hsv)
            hsv[1] = hsv[1].coerceIn(0.50f, 0.90f)
            hsv[2] = hsv[2].coerceIn(0.65f, 0.90f)
            Color(android.graphics.Color.HSVToColor(hsv))
        } else {
            primaryColor
        }

        val topColor = if (topBestScore > 0f) {
            android.graphics.Color.colorToHSV(topBestColor, hsv)
            hsv[1] = hsv[1].coerceIn(0.55f, 0.95f)
            hsv[2] = hsv[2].coerceIn(0.70f, 0.92f)
            Color(android.graphics.Color.HSVToColor(hsv))
        } else {
            primaryColor
        }

        val bottomColor = if (bottomBestScore > 0f) {
            android.graphics.Color.colorToHSV(bottomBestColor, hsv)
            hsv[1] = hsv[1].coerceIn(0.50f, 0.90f)
            hsv[2] = hsv[2].coerceIn(0.65f, 0.90f)
            Color(android.graphics.Color.HSVToColor(hsv))
        } else {
            secondaryColor
        }

        ArtworkGlowPalette(
            primary = primaryColor,
            secondary = secondaryColor,
            accent = primaryColor.copy(alpha = 0.85f),
            topColor = topColor,
            bottomColor = bottomColor
        )
    } catch (_: Throwable) {
        val safeFallback = if (fallbackColor.isSpecified) fallbackColor else Color(0xFF6C5CE7)
        ArtworkGlowPalette(
            primary = safeFallback,
            secondary = safeFallback,
            accent = safeFallback,
            topColor = safeFallback,
            bottomColor = safeFallback
        )
    }
}

/**
 * Robust, lightweight dominant color extractor sampling from the artwork bitmap.
 * Avoids any external palette dependencies while delivering vibrant, track-adaptive tones.
 */
fun extractDominantColor(bitmap: Bitmap): Color {
    return extractArtworkGlowPalette(bitmap).primary
}

/**
 * Creates an authentic, organic artwork-shaped ambient glow bitmap.
 *
 * Architecture:
 * 1. Geometry & Color Separation:
 *    - The glow silhouette is constructed directly from the artwork's exact 26.dp rounded corner geometry.
 *    - Source RGB pixels are NEVER drawn onto the canvas, preventing dark album edges from baking
 *      muddy or black patches into the halo.
 * 2. True Software Gaussian Diffusion:
 *    - Applies Android's BlurMaskFilter on a software ARGB_8888 canvas with generous transparent padding (50px).
 *    - Dual-pass diffusion: a wide atmospheric pass (38px radius) for smooth ambient falloff and a contour
 *      pass (16px radius) for luminous silhouette proximity.
 *    - Because paddingPx (50px) > blurRadius (38px), the glow decays completely to alpha 0 before the canvas
 *      boundaries, guaranteeing ZERO rectangular cutoff edges or visible box seams.
 * 3. Color Grading:
 *    - Shaded via a smooth vertical LinearGradient from palette.topColor to palette.bottomColor.
 *    - Harmonizes with the artwork without ever creating a circular disc or dark backing patch.
 */
fun createArtworkGlowBitmap(
    source: Bitmap,
    targetSize: Int = 320,
    paddingPx: Int = 50,
    palette: ArtworkGlowPalette? = null
): Bitmap? {
    return try {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null

        val glowPalette = palette ?: extractArtworkGlowPalette(source)

        // 1. Create a transparent canvas of targetSize x targetSize
        val glowCanvasBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(glowCanvasBitmap)
        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        // 2. Compute inner bounds with transparent padding
        val innerLeft = paddingPx.toFloat()
        val innerTop = paddingPx.toFloat()
        val innerRight = (targetSize - paddingPx).toFloat()
        val innerBottom = (targetSize - paddingPx).toFloat()
        val innerSize = innerRight - innerLeft

        // Corner radius scaled proportionally to 26dp / 280dp of inner artwork footprint (~20.4px)
        val cornerRadius = innerSize * (26f / 280f)
        val rectF = android.graphics.RectF(innerLeft, innerTop, innerRight, innerBottom)

        val topArgb = glowPalette.topColor.toArgb()
        val bottomArgb = glowPalette.bottomColor.toArgb()

        // 3. Wide atmospheric glow pass (large Gaussian spread for soft ambient halo)
        val widePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                innerLeft, innerTop,
                innerLeft, innerBottom,
                topArgb,
                bottomArgb,
                android.graphics.Shader.TileMode.CLAMP
            )
            maskFilter = android.graphics.BlurMaskFilter(38f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            alpha = 150 // ~0.59 opacity for soft, expansive atmospheric diffusion
        }
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, widePaint)

        // 4. Contour illumination pass (tighter Gaussian glow hugging the rounded silhouette)
        val contourPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(
                innerLeft, innerTop,
                innerLeft, innerBottom,
                topArgb,
                bottomArgb,
                android.graphics.Shader.TileMode.CLAMP
            )
            maskFilter = android.graphics.BlurMaskFilter(16f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            alpha = 200 // ~0.78 opacity for vibrant proximity illumination
        }
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, contourPaint)

        glowCanvasBitmap
    } catch (_: Throwable) {
        null
    }
}

/**
 * Record holding canonical artwork state for an active track.
 */
internal data class TrackArtworkRecord(
    val canonicalBitmap: Bitmap?,
    val uriStr: String,
    val revisionId: Long
)

internal var bitmapEqualityChecker: (Bitmap?, Bitmap?) -> Boolean = { a, b ->
    if (a === b) true
    else if (a == null || b == null) false
    else {
        try {
            val aRecycled = a.isRecycled
            val bRecycled = b.isRecycled
            if (aRecycled || bRecycled) false
            else if (a.width != b.width || a.height != b.height || a.config != b.config) false
            else a.sameAs(b)
        } catch (_: Throwable) {
            false
        }
    }
}

internal fun areBitmapsIdentical(a: Bitmap?, b: Bitmap?): Boolean = bitmapEqualityChecker(a, b)

/**
 * Lifecycle-safe, collision-free artwork canonicalizer.
 * Guarantees stable object identity across pause/seek/HUD recompositions,
 * while safely detecting genuinely updated artwork via background content comparison.
 */
object ArtworkCanonicalizer {
    private val globalRevisionGen = java.util.concurrent.atomic.AtomicLong(1L)
    private val requestTokenGen = java.util.concurrent.atomic.AtomicLong(1L)
    private val trackRecords = object : LinkedHashMap<String, TrackArtworkRecord>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackArtworkRecord>?): Boolean {
            return size > 16
        }
    }

    @Synchronized
    fun getCanonicalBitmap(trackKey: String, incoming: Bitmap?): Bitmap? {
        val cleanKey = trackKey.trim()
        val incomingSafe = try { incoming?.takeIf { !it.isRecycled } } catch (_: Throwable) { incoming }
        if (cleanKey.isBlank()) return incomingSafe
        val record = trackRecords[cleanKey]
        val canonical = record?.canonicalBitmap
        if (canonical != null) {
            val isCanonicalRecycled = try { canonical.isRecycled } catch (_: Throwable) { false }
            if (!isCanonicalRecycled) {
                if (incoming == null) return canonical
                if (canonical === incoming) return canonical
                if (areBitmapsIdentical(canonical, incoming)) {
                    return canonical
                }
            }
        }
        return incomingSafe
    }

    @Synchronized
    fun getArtworkIdentity(trackKey: String, bitmap: Bitmap?): String {
        val cleanKey = trackKey.trim()
        val isBitmapRecycled = try { bitmap?.isRecycled ?: true } catch (_: Throwable) { false }
        if (cleanKey.isBlank() || bitmap == null || isBitmapRecycled) {
            return if (cleanKey.isBlank()) "idle_sunset" else "$cleanKey::none"
        }
        val record = trackRecords[cleanKey]
        if (record != null && record.canonicalBitmap === bitmap) {
            return "$cleanKey::r${record.revisionId}"
        }
        val rev = record?.revisionId ?: globalRevisionGen.incrementAndGet()
        trackRecords[cleanKey] = TrackArtworkRecord(bitmap, record?.uriStr ?: "", rev)
        return "$cleanKey::r$rev"
    }

    @Synchronized
    fun canonicalize(
        trackKey: String,
        uriStr: String,
        newBitmap: Bitmap?
    ): Pair<Bitmap?, String> {
        val cleanKey = trackKey.trim()
        val cleanUri = uriStr.trim()

        val isRecycled = try { newBitmap?.isRecycled ?: true } catch (_: Throwable) { false }

        if (cleanKey.isBlank() && cleanUri.isBlank() && (newBitmap == null || isRecycled)) {
            return Pair(null, "empty::none")
        }

        val existingRecord = trackRecords[cleanKey]
        val canonical = existingRecord?.canonicalBitmap

        if (newBitmap == null || isRecycled) {
            if (cleanUri.isBlank()) {
                val rev = existingRecord?.revisionId ?: globalRevisionGen.incrementAndGet()
                trackRecords[cleanKey] = TrackArtworkRecord(null, "", rev)
                return Pair(null, "$cleanKey::none")
            }
            val rev = existingRecord?.revisionId ?: globalRevisionGen.incrementAndGet()
            return Pair(null, "$cleanKey::$cleanUri::r$rev")
        }

        if (canonical != null && canonical === newBitmap && existingRecord.uriStr == cleanUri) {
            return Pair(canonical, "$cleanKey::r${existingRecord.revisionId}")
        }

        if (canonical != null && existingRecord.uriStr == cleanUri) {
            if (areBitmapsIdentical(canonical, newBitmap)) {
                return Pair(canonical, "$cleanKey::r${existingRecord.revisionId}")
            }
        }

        val newRev = globalRevisionGen.incrementAndGet()
        if (existingRecord != null) {
            val oldIdentity = "$cleanKey::r${existingRecord.revisionId}"
            ArtworkMemoryCache.remove("blur_$oldIdentity")
            ArtworkMemoryCache.removeMatching("glow_$oldIdentity")
        }

        trackRecords[cleanKey] = TrackArtworkRecord(newBitmap, cleanUri, newRev)
        return Pair(newBitmap, "$cleanKey::r$newRev")
    }

    suspend fun resolveAsync(
        trackKey: String,
        uriStr: String,
        incoming: Bitmap?
    ): Bitmap? {
        val cleanKey = trackKey.trim()
        val cleanUri = uriStr.trim()
        val isRecycled = try { incoming?.isRecycled ?: true } catch (_: Throwable) { false }
        if (cleanKey.isBlank() || incoming == null || isRecycled) {
            return incoming?.takeIf {
                try { !it.isRecycled } catch (_: Throwable) { true }
            }
        }

        val requestToken = requestTokenGen.incrementAndGet()

        val existingRecord = synchronized(this) { trackRecords[cleanKey] }
        val canonical = existingRecord?.canonicalBitmap

        if (canonical != null && canonical === incoming && existingRecord.uriStr == cleanUri) {
            return canonical
        }

        if (canonical != null && existingRecord.uriStr == cleanUri) {
            val isIdentical = withContext(Dispatchers.Default) {
                areBitmapsIdentical(canonical, incoming)
            }
            if (isIdentical) {
                return canonical
            }
        }

        return synchronized(this) {
            if (!isTokenValid(requestToken)) {
                return@synchronized canonical ?: incoming.takeIf {
                    try { !it.isRecycled } catch (_: Throwable) { true }
                }
            }

            val newRev = globalRevisionGen.incrementAndGet()
            if (existingRecord != null) {
                val oldIdentity = "$cleanKey::r${existingRecord.revisionId}"
                ArtworkMemoryCache.remove("blur_$oldIdentity")
                ArtworkMemoryCache.removeMatching("glow_$oldIdentity")
            }

            trackRecords[cleanKey] = TrackArtworkRecord(incoming, cleanUri, newRev)
            incoming
        }
    }

    @Synchronized
    fun isTokenValid(token: Long): Boolean = token <= requestTokenGen.get()

    @Synchronized
    fun clear() {
        requestTokenGen.incrementAndGet()
        trackRecords.clear()
        ArtworkMemoryCache.clear()
    }
}

/**
 * Shared, lifecycle-safe artwork glow bitmap remember helper.
 * Generates and caches the organic rounded-silhouette glow off the main thread.
 */
@Composable
fun rememberArtworkGlowBitmap(
    track: TrackMetadata?,
    artworkBitmap: Bitmap?,
    glowPalette: ArtworkGlowPalette? = null
): Bitmap? {
    val trackKey = track?.key ?: ""
    val paletteHash = glowPalette?.let { "${it.topColor.hashCode()}_${it.bottomColor.hashCode()}" } ?: ""
    // BUG-005: Key only on trackKey, not artworkBitmap. Each MediaSession callback may deliver
    // a new Bitmap allocation with identical pixels; using the Bitmap reference as a remember key
    // causes getArtworkIdentity to see a fresh reference → new revision → glow recomputed needlessly.
    val artworkIdentity = remember(trackKey) {
        ArtworkCanonicalizer.getArtworkIdentity(trackKey, artworkBitmap)
    }
    val glowCacheKey = remember(artworkIdentity, paletteHash) {
        if (artworkBitmap != null && !artworkBitmap.isRecycled && !artworkIdentity.endsWith("::none") && artworkIdentity != "idle_sunset") {
            "glow_${artworkIdentity}_$paletteHash"
        } else ""
    }

    val cached = if (glowCacheKey.isNotBlank()) ArtworkMemoryCache.get(glowCacheKey) else null

    return produceState<Bitmap?>(
        initialValue = cached,
        key1 = glowCacheKey,
        // BUG-005: Removed key2 = artworkBitmap. glowCacheKey already encodes artwork identity;
        // using artworkBitmap as an additional key restarts the coroutine on every new Bitmap
        // allocation with identical pixels, even when glowCacheKey (the stable cache key) is unchanged.
        key2 = glowPalette
    ) {
        val expectedKey = glowCacheKey
        if (artworkBitmap == null || artworkBitmap.isRecycled || expectedKey.isBlank()) {
            value = null
            return@produceState
        }

        val memCached = ArtworkMemoryCache.get(expectedKey)
        if (memCached != null && !memCached.isRecycled) {
            value = memCached
            return@produceState
        }

        val glow = withContext(Dispatchers.Default) {
            createArtworkGlowBitmap(artworkBitmap, targetSize = 320, paddingPx = 50, palette = glowPalette)
        }

        if (isActive && glow != null && !glow.isRecycled && glowCacheKey == expectedKey) {
            ArtworkMemoryCache.put(expectedKey, glow)
            value = glow
        }
    }.value
}


/**
 * Immutable Crossfade state container for cinematic blurred background.
 */
private class CinematicBackgroundState(
    val identity: String,
    val bitmap: Bitmap?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CinematicBackgroundState) return false
        return identity == other.identity &&
            (bitmap != null) == (other.bitmap != null)
    }

    override fun hashCode(): Int {
        var result = identity.hashCode()
        result = 31 * result + (if (bitmap != null) 1 else 0)
        return result
    }
}

/**
 * Cinematic Now Playing Atmospheric Background.
 * Renders album artwork heavily blurred across the entire window, with natural smooth transitions,
 * zero dome lines or harsh boundaries, and an edge-to-edge luminance gradient.
 * Acts as the hazeSource for foreground frosted cards and the bottom navigation pill.
 */
@Composable
fun CinematicArtworkBackground(
    artworkBitmap: Bitmap?,
    modifier: Modifier = Modifier,
    trackKey: String = "",
    hazeState: HazeState? = null,
    content: (@Composable BoxScope.() -> Unit)? = null
) {
    val backgroundColor = MaterialTheme.colorScheme.background

    // BUG-005: Key on trackKey and whether artworkBitmap is present. Identical-pixel Bitmap re-allocations
    // from MediaSession must not restart the blur pipeline, but artwork arriving after metadata must update.
    val artworkIdentity = remember(trackKey, artworkBitmap != null) {
        ArtworkCanonicalizer.getArtworkIdentity(trackKey, artworkBitmap)
    }

    val blurCacheKey = remember(artworkIdentity) {
        if (artworkIdentity == "idle_sunset" || artworkIdentity.endsWith("::none")) "" else "blur_$artworkIdentity"
    }

    val cachedBlurred = remember(blurCacheKey) {
        if (blurCacheKey.isNotBlank()) ArtworkMemoryCache.get(blurCacheKey) else null
    }

    // Generate or cache the smooth pre-blurred ambient bitmap asynchronously off the main thread.
    // Pairs the generated bitmap with blurCacheKey to ensure stale blurred artwork from a previous track is never used.
    val blurredResult = produceState<Pair<String, Bitmap?>>(
        initialValue = Pair(blurCacheKey, cachedBlurred),
        key1 = blurCacheKey
    ) {
        val expectedKey = blurCacheKey
        value = Pair(expectedKey, cachedBlurred)
        if (artworkBitmap == null || artworkBitmap.isRecycled || expectedKey.isBlank()) {
            value = Pair(expectedKey, null)
            return@produceState
        }
        if (cachedBlurred != null && !cachedBlurred.isRecycled) {
            value = Pair(expectedKey, cachedBlurred)
            return@produceState
        }
        val blurred = withContext(Dispatchers.Default) {
            createBlurredArtworkBitmap(artworkBitmap)
        }
        if (isActive && blurred != null && !blurred.isRecycled && blurCacheKey == expectedKey) {
            ArtworkMemoryCache.put(expectedKey, blurred)
            value = Pair(expectedKey, blurred)
        }
    }.value

    val currentBlurred = if (blurredResult.first == blurCacheKey && blurCacheKey.isNotBlank() && blurredResult.second?.isRecycled == false) {
        blurredResult.second
    } else {
        cachedBlurred?.takeIf { !it.isRecycled }
    }

    val latestArtworkBitmap by rememberUpdatedState(artworkBitmap)
    val latestTrackKey by rememberUpdatedState(trackKey)
    val latestBlurredBitmap by rememberUpdatedState(currentBlurred)

    // Stable background state: holds the previous blurred background steady until
    // the new track's blurred bitmap is ready. Never transitions to raw artwork.
    var bgTargetState by remember {
        mutableStateOf(
            if (currentBlurred != null && !currentBlurred.isRecycled) {
                CinematicBackgroundState("art_$artworkIdentity", currentBlurred)
            } else {
                CinematicBackgroundState("idle_sunset", null)
            }
        )
    }

    LaunchedEffect(artworkIdentity, currentBlurred) {
        val targetIdentity = if (trackKey.isNotBlank() && !artworkIdentity.endsWith("::none") && artworkIdentity != "idle_sunset") {
            "art_$artworkIdentity"
        } else {
            "idle_sunset"
        }

        if (currentBlurred != null && !currentBlurred.isRecycled && targetIdentity != "idle_sunset") {
            if (bgTargetState.identity != targetIdentity || bgTargetState.bitmap !== currentBlurred) {
                bgTargetState = CinematicBackgroundState(targetIdentity, currentBlurred)
            }
        } else if (targetIdentity == "idle_sunset") {
            // Track has no artwork or idle -> immediately switch to idle_sunset (prevent previous track's blur lingering)
            if (bgTargetState.identity != "idle_sunset") {
                bgTargetState = CinematicBackgroundState("idle_sunset", null)
            }
        } else {
            // MED-02: Track is active and has artwork, but its blurred bitmap is still being generated.
            // If the current background belongs to a different track, clear it to idle_sunset immediately
            // to prevent the previous track's blur from appearing over the new track during rapid switching.
            val cleanKey = trackKey.trim()
            if (bgTargetState.identity != "idle_sunset" && !bgTargetState.identity.startsWith("art_$cleanKey::")) {
                bgTargetState = CinematicBackgroundState("idle_sunset", null)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // Backdrop Source Layer: Blurred artwork + smooth edge-to-edge scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (hazeState != null) Modifier.hazeSource(state = hazeState) else Modifier)
        ) {
            // 1. Softly blurred ambient artwork background (or warm sunset atmospheric canvas when idle)
            androidx.compose.animation.Crossfade(
                targetState = bgTargetState,
                animationSpec = tween(durationMillis = 260),
                label = "cinematic_bg_crossfade"
            ) { state ->
                val bm = state.bitmap
                if (state.identity.startsWith("art_") && bm != null && !bm.isRecycled) {
                    Image(
                        bitmap = bm.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = 1.0f,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.15f
                                scaleY = 1.15f
                            }
                            .blur(22.dp)
                    )
                } else {
                    // Atmospheric sunset ambient gradient matching the visual identity
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFF221526), // Top twilight plum
                                    Color(0xFF452433), // Sunset rose
                                    Color(0xFF723526), // Warm sunset amber
                                    Color(0xFF2C1824), // Silhouette tone
                                    Color(0xFF121016)  // Bottom near-black
                                )
                            )
                        )
                    }
                }
            }

            // 2. Continuous, seamless dark gradient scrim for contrast and legibility across all insets
            // Extends from absolute top (behind status bar) to absolute bottom (behind nav & gesture bar)
            // Carefully calibrated so artwork colors remain atmospheric while white typography stays crisp & readable
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.38f), // Status bar contrast
                            Color.Black.copy(alpha = 0.24f), // Artwork zone - vibrant ambient glow
                            Color.Black.copy(alpha = 0.34f), // Live Lyrics zone - high-contrast reading
                            Color.Black.copy(alpha = 0.42f), // Controls zone
                            Color.Black.copy(alpha = 0.50f)  // Navigation bar & gesture zone
                        ),
                        startY = 0f,
                        endY = size.height
                    )
                )
            }
        }

        // Foreground content container if provided
        if (content != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                content = content
            )
        }
    }
}

// Shared bounded memory cache for artwork bitmaps across LyricBar
object ArtworkMemoryCache {
    private val maxMemory = Runtime.getRuntime().maxMemory()
    // Allocate 1/8th of available runtime heap, bounded between 16MB and 32MB
    private val cacheSizeBytes = (maxMemory / 8L).coerceIn(16L * 1024 * 1024, 32L * 1024 * 1024)
    // MIN-03: Cap maximum entries to prevent unbounded growth during long playlist sessions
    private const val MAX_ENTRIES = 32

    private var currentSizeBytes: Long = 0L
    private val map = LinkedHashMap<String, Bitmap>(32, 0.75f, true)

    @Synchronized
    fun get(key: String): Bitmap? {
        val bm = map[key] ?: return null
        val recycled = try { bm.isRecycled } catch (_: Throwable) { false }
        return if (!recycled) bm else {
            remove(key)
            null
        }
    }

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        val recycled = try { bitmap.isRecycled } catch (_: Throwable) { false }
        if (recycled) return
        remove(key)
        val bytes = try { bitmap.byteCount.toLong().coerceAtLeast(1L) } catch (_: Throwable) { 1L }
        map[key] = bitmap
        currentSizeBytes += bytes
        trimToSize()
    }

    @Synchronized
    fun remove(key: String): Bitmap? {
        val removed = map.remove(key) ?: return null
        val bytes = try { removed.byteCount.toLong().coerceAtLeast(1L) } catch (_: Throwable) { 1L }
        currentSizeBytes = (currentSizeBytes - bytes).coerceAtLeast(0L)
        return removed
    }

    @Synchronized
    fun removeMatching(prefix: String) {
        val toRemove = map.keys.filter { it.startsWith(prefix) || it.contains(prefix) }
        for (k in toRemove) {
            remove(k)
        }
    }

    @Synchronized
    fun clear() {
        map.clear()
        currentSizeBytes = 0L
    }

    private fun trimToSize() {
        val iterator = map.entries.iterator()
        while ((currentSizeBytes > cacheSizeBytes || map.size > MAX_ENTRIES) && iterator.hasNext()) {
            val entry = iterator.next()
            val bytes = try { entry.value.byteCount.toLong().coerceAtLeast(1L) } catch (_: Throwable) { 1L }
            currentSizeBytes = (currentSizeBytes - bytes).coerceAtLeast(0L)
            iterator.remove()
        }
    }
}

/**
 * Creates a unique, stable artwork cache key combining track key, URI, and resolution variant.
 * This guarantees different releases/versions with distinct artwork URIs never reuse stale cached images.
 */
fun buildArtworkCacheKey(trackKey: String, uriStr: String, variant: String = "full"): String {
    val cleanKey = trackKey.trim().lowercase()
    val cleanUri = uriStr.trim()
    return "$cleanKey::$cleanUri::$variant"
}

/**
 * Shared, lifecycle-safe artwork bitmap remember helper.
 * Uses a bounded memory cache and canonicalizes identical bitmaps across MediaSession updates,
 * eliminating the double-flicker bug on playback state changes.
 */
@Composable
fun rememberArtworkBitmap(track: TrackMetadata?): Bitmap? {
    val context = LocalContext.current
    val trackKey = track?.key ?: ""
    val uriStr = track?.albumArtUri ?: ""
    val embedded = track?.albumArtBitmap

    val cacheKey = remember(trackKey, uriStr) {
        buildArtworkCacheKey(trackKey, uriStr, "full_512")
    }

    var canonicalBitmap by remember(trackKey) {
        mutableStateOf(ArtworkCanonicalizer.getCanonicalBitmap(trackKey, embedded))
    }

    LaunchedEffect(trackKey, uriStr, embedded) {
        if (embedded != null && !embedded.isRecycled) {
            val resolved = ArtworkCanonicalizer.resolveAsync(trackKey, uriStr, embedded)
            if (resolved != null && !resolved.isRecycled) {
                if (cacheKey.isNotBlank()) ArtworkMemoryCache.put(cacheKey, resolved)
                canonicalBitmap = resolved
            }
        }
    }

    // If an active embedded bitmap is provided, canonicalize and return stable reference
    if (embedded != null && !embedded.isRecycled) {
        val canonical = canonicalBitmap ?: ArtworkCanonicalizer.getCanonicalBitmap(trackKey, embedded) ?: embedded
        if (cacheKey.isNotBlank()) ArtworkMemoryCache.put(cacheKey, canonical)
        return canonical
    }

    // Check memory cache synchronously to avoid any momentary null frame
    val cached = if (cacheKey.isNotBlank()) ArtworkMemoryCache.get(cacheKey) else null
    if (cached != null && !cached.isRecycled) {
        return cached
    }

    return produceState<Bitmap?>(
        initialValue = cached,
        key1 = cacheKey
    ) {
        if (track == null || (trackKey.isBlank() && uriStr.isBlank())) {
            value = null
            return@produceState
        }

        val memCached = ArtworkMemoryCache.get(cacheKey)
        if (memCached != null && !memCached.isRecycled) {
            value = memCached
            return@produceState
        }

        if (uriStr.isNotBlank()) {
            val loaded = withContext(Dispatchers.IO) {
                loadArtworkBitmapSafely(context, uriStr, reqSize = 512)
            }
            if (loaded != null && !loaded.isRecycled) {
                val resolved = ArtworkCanonicalizer.resolveAsync(trackKey, uriStr, loaded) ?: loaded
                ArtworkMemoryCache.put(cacheKey, resolved)
                value = resolved
            }
        }
    }.value
}

/**
 * Robust, memory-bounded, and safe artwork bitmap loader from content, file, or network URIs.
 * Downsamples safely based on requested pixel dimension [reqSize] (default 512 for main views, 144 for thumbnails).
 */
fun loadArtworkBitmapSafely(context: Context, uriString: String, reqSize: Int = 512): Bitmap? {
    return try {
        val uri = Uri.parse(uriString)
        val maxByteLimit = 8 * 1024 * 1024 // 8 MB safety bound

        val bytes = when (uri.scheme?.lowercase()) {
            "content", "file", "android.resource" -> {
                context.contentResolver.openInputStream(uri)?.use { readBytesBounded(it, maxByteLimit) }
            }
            "http", "https" -> {
                val conn = java.net.URL(uriString).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "LyricBar/2.1")
                try {
                    conn.connect()
                    if (conn.responseCode in 200..299) {
                        conn.inputStream.use { readBytesBounded(it, maxByteLimit) }
                    } else {
                        null
                    }
                } finally {
                    conn.disconnect()
                }
            }
            else -> {
                val file = java.io.File(uriString)
                if (file.exists() && file.length() <= maxByteLimit) {
                    file.readBytes()
                } else {
                    context.contentResolver.openInputStream(uri)?.use { readBytesBounded(it, maxByteLimit) }
                }
            }
        } ?: return null

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        var inSampleSize = 1
        val maxDim = maxOf(options.outWidth, options.outHeight)
        val targetSize = reqSize.coerceAtLeast(64)
        if (maxDim > targetSize) {
            while ((maxDim / (inSampleSize * 2)) >= targetSize) {
                inSampleSize *= 2
            }
        }
        options.inJustDecodeBounds = false
        options.inSampleSize = inSampleSize
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (_: Throwable) {
        null
    }
}


private fun readBytesBounded(inputStream: InputStream, limit: Int): ByteArray? {
    val buffer = ByteArray(8192)
    val output = ByteArrayOutputStream()
    var total = 0
    var read: Int
    while (inputStream.read(buffer).also { read = it } != -1) {
        total += read
        if (total > limit) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

