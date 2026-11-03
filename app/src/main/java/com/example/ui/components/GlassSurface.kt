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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
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

/**
 * Robust, lightweight dominant color extractor sampling from the artwork bitmap.
 * Avoids any external palette dependencies while delivering vibrant, track-adaptive tones.
 */
fun extractDominantColor(bitmap: Bitmap): Color {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0 || bitmap.isRecycled) return Color.Unspecified
    var rSum = 0L
    var gSum = 0L
    var bSum = 0L
    var count = 0
    val stepX = (width / 6).coerceAtLeast(1)
    val stepY = (height / 6).coerceAtLeast(1)
    for (x in stepX until width step stepX) {
        for (y in stepY until height step stepY) {
            val pixel = bitmap.getPixel(x, y)
            val a = android.graphics.Color.alpha(pixel)
            if (a > 60) {
                rSum += android.graphics.Color.red(pixel)
                gSum += android.graphics.Color.green(pixel)
                bSum += android.graphics.Color.blue(pixel)
                count++
            }
        }
    }
    return if (count > 0) {
        Color(
            red = (rSum / count).toInt(),
            green = (gSum / count).toInt(),
            blue = (bSum / count).toInt()
        )
    } else {
        Color.Unspecified
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

    // Generate or cache the smooth pre-blurred ambient bitmap
    val blurredBitmap = remember(artworkBitmap, trackKey) {
        if (artworkBitmap != null && !artworkBitmap.isRecycled) {
            createBlurredArtworkBitmap(artworkBitmap)
        } else {
            null
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
            // Stable target key prevents re-triggering Crossfade when identical track is playing/paused
            val stableKey = if (artworkBitmap != null && !artworkBitmap.isRecycled) {
                "art_${trackKey.ifEmpty { artworkBitmap.hashCode().toString() }}"
            } else {
                "idle_sunset"
            }

            // 1. Softly blurred ambient artwork background (or warm sunset atmospheric canvas when idle)
            androidx.compose.animation.Crossfade(
                targetState = stableKey,
                animationSpec = tween(durationMillis = 260),
                label = "cinematic_bg_crossfade"
            ) { key ->
                if (key.startsWith("art_") && artworkBitmap != null && !artworkBitmap.isRecycled) {
                    val displayBitmap = blurredBitmap ?: artworkBitmap
                    Image(
                        bitmap = displayBitmap.asImageBitmap(),
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
            // Gently balanced so original artwork colors and ambient glow shine through vibrantly
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.16f), // Status bar contrast
                            Color.Black.copy(alpha = 0.04f), // Artwork zone - maximum original color vibrancy
                            Color.Black.copy(alpha = 0.10f), // Live Lyrics zone
                            Color.Black.copy(alpha = 0.16f), // Controls zone
                            Color.Black.copy(alpha = 0.22f)  // Navigation bar & gesture zone
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

// In-memory artwork cache preventing double-loading and play/pause flicker
private val sArtworkBitmapCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

/**
 * Shared, lifecycle-safe artwork bitmap remember helper.
 * Uses an in-memory cache and never drops state to null when the track identity is stable,
 * eliminating the double-flicker bug on playback state changes.
 */
@Composable
fun rememberArtworkBitmap(track: TrackMetadata?): Bitmap? {
    val context = LocalContext.current
    val trackKey = track?.key ?: ""
    val uriStr = track?.albumArtUri ?: ""
    val embedded = track?.albumArtBitmap

    // If an active embedded bitmap is provided, cache and return immediately
    if (embedded != null && !embedded.isRecycled) {
        if (trackKey.isNotBlank()) sArtworkBitmapCache[trackKey] = embedded
        return embedded
    }

    // Check memory cache synchronously to avoid any momentary null frame
    val cached = if (trackKey.isNotBlank()) sArtworkBitmapCache[trackKey] else null
    if (cached != null && !cached.isRecycled) {
        return cached
    }

    return produceState<Bitmap?>(
        initialValue = cached,
        key1 = trackKey,
        key2 = uriStr
    ) {
        if (track == null || (trackKey.isBlank() && uriStr.isBlank())) {
            value = null
            return@produceState
        }

        val memCached = sArtworkBitmapCache[trackKey]
        if (memCached != null && !memCached.isRecycled) {
            value = memCached
            return@produceState
        }

        if (uriStr.isNotBlank()) {
            val loaded = withContext(Dispatchers.IO) {
                loadArtworkBitmapSafely(context, uriStr)
            }
            if (loaded != null && !loaded.isRecycled) {
                if (trackKey.isNotBlank()) {
                    sArtworkBitmapCache[trackKey] = loaded
                }
                value = loaded
            }
        }
    }.value
}

/**
 * Robust, memory-bounded, and safe artwork bitmap loader from content, file, or network URIs.
 */
fun loadArtworkBitmapSafely(context: Context, uriString: String): Bitmap? {
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
                conn.setRequestProperty("User-Agent", "LyricBar/2.0")
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
        val reqSize = 512
        if (maxDim > reqSize) {
            while ((maxDim / (inSampleSize * 2)) >= reqSize) {
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

