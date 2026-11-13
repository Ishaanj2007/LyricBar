package com.example.service

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.util.TypedValue
import android.view.WindowInsets
import android.view.WindowManager
import com.example.data.preferences.UserPreferences
import kotlin.math.roundToInt

enum class HudOrientation {
    PORTRAIT,
    LANDSCAPE
}

data class HudOverlayProfile(
    val x: Int,
    val y: Int,
    val widthPx: Int,
    val heightPx: Int
)

data class HudDisplayGeometry(
    val windowWidthPx: Int,
    val windowHeightPx: Int,
    val density: Float,
    val cutoutLeftPx: Int = 0,
    val cutoutTopPx: Int = 0,
    val cutoutRightPx: Int = 0,
    val cutoutBottomPx: Int = 0,
    val navLeftPx: Int = 0,
    val navTopPx: Int = 0,
    val navRightPx: Int = 0,
    val navBottomPx: Int = 0,
    val statusTopPx: Int = 0
) {
    val orientation: HudOrientation
        get() = if (windowWidthPx > windowHeightPx) HudOrientation.LANDSCAPE else HudOrientation.PORTRAIT

    val minSafeX: Int
        get() = maxOf(cutoutLeftPx, navLeftPx)
            .coerceIn(0, (windowWidthPx - 1).coerceAtLeast(0))

    val maxSafeRight: Int
        get() = (windowWidthPx - maxOf(cutoutRightPx, navRightPx))
            .coerceIn(minSafeX, windowWidthPx.coerceAtLeast(minSafeX))

    val safeWidthPx: Int
        get() = (maxSafeRight - minSafeX).coerceAtLeast(1)

    val safeTopInsetPx: Int
        get() = maxOf(cutoutTopPx, statusTopPx, navTopPx)
            .coerceIn(0, (windowHeightPx - 1).coerceAtLeast(0))

    val maxSafeBottom: Int
        get() = (windowHeightPx - maxOf(cutoutBottomPx, navBottomPx))
            .coerceIn(0, windowHeightPx.coerceAtLeast(0))

    val safeHeightBelowTopInsetPx: Int
        get() = (maxSafeBottom - safeTopInsetPx).coerceAtLeast(1)

    val usableHeightForClampingPx: Int
        get() = maxSafeBottom.coerceAtLeast(1)
}

data class HudBoundsConstraints(
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
    val minWidthPx: Int,
    val maxWidthPx: Int,
    val minHeightPx: Int,
    val maxHeightPx: Int,
    val minSafeRightPx: Int,
    val maxSafeBottomPx: Int
)

data class HudResolvedBounds(
    val x: Int,
    val y: Int,
    val widthPx: Int,
    val heightPx: Int,
    val effectiveHeightPx: Int,
    val constraints: HudBoundsConstraints
)

data class HudOrientationInfoSnapshot(
    val orientation: HudOrientation,
    val chipLabel: String,
    val modeTitle: String,
    val modeSubtitle: String,
    val explanation: String,
    val autoSwitchNote: String,
    val screenSizeDpLabel: String,
    val hudSizeDpLabel: String,
    val hudPositionDpLabel: String
)

object HudPositioningEngine {

    const val DEFAULT_PORTRAIT_X = 0
    const val DEFAULT_PORTRAIT_Y = 180
    const val DEFAULT_PORTRAIT_WIDTH_PX = 850
    const val DEFAULT_PORTRAIT_HEIGHT_PX = 130

    private const val MIN_WIDTH_DP = 100f
    private const val MIN_HEIGHT_DP = 24f
    private const val DEFAULT_LANDSCAPE_MIN_WIDTH_DP = 240f
    private const val DEFAULT_LANDSCAPE_MAX_WIDTH_DP = 440f
    private const val DEFAULT_LANDSCAPE_HEIGHT_DP = 52f
    private const val DEFAULT_LANDSCAPE_TOP_MARGIN_DP = 12f

    fun computeMinBackdropHeightPx(prefs: UserPreferences, density: Float): Int {
        if (!prefs.hudBackdropEnabled) return 0
        val safeDensity = density.coerceAtLeast(0.75f)
        val textPx = prefs.overlayTextSizeSp.coerceIn(6f, 36f) * safeDensity
        val vertPadPx = prefs.hudBackdropVerticalPaddingDp.coerceIn(0, 24) * safeDensity * 2f
        return kotlin.math.round(textPx * 2.2f + vertPadPx + 16f * safeDensity).toInt().coerceAtLeast(0)
    }

    fun computeMinBackdropHeightPx(
        prefs: UserPreferences,
        displayMetrics: android.util.DisplayMetrics
    ): Int {
        if (!prefs.hudBackdropEnabled) return 0
        val safeDensity = displayMetrics.density.coerceAtLeast(0.75f)
        val textPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            prefs.overlayTextSizeSp,
            displayMetrics
        )
        val vertPadPx = prefs.hudBackdropVerticalPaddingDp.coerceIn(0, 24) * safeDensity * 2f
        return kotlin.math.round(textPx * 2.2f + vertPadPx + 16f * safeDensity).toInt().coerceAtLeast(0)
    }

    fun computeConstraints(
        geometry: HudDisplayGeometry,
        currentWidthPx: Int,
        currentEffectiveHeightPx: Int,
        minBackdropHeightPx: Int = 0
    ): HudBoundsConstraints {
        val safeDensity = geometry.density.coerceAtLeast(0.75f)
        val maxWidthPx = geometry.safeWidthPx.coerceAtLeast(1)
        val minWidthPx = minOf(
            kotlin.math.round(MIN_WIDTH_DP * safeDensity).toInt().coerceAtLeast(120),
            maxWidthPx
        )

        val maxHeightPx = geometry.usableHeightForClampingPx.coerceAtLeast(1)
        val baseMinHeightPx = minOf(
            kotlin.math.round(MIN_HEIGHT_DP * safeDensity).toInt().coerceAtLeast(40),
            maxHeightPx
        )
        val minHeightPx = maxOf(baseMinHeightPx, minBackdropHeightPx).coerceAtMost(maxHeightPx)

        val clampedW = currentWidthPx.coerceIn(minWidthPx, maxWidthPx)
        val clampedH = currentEffectiveHeightPx.coerceIn(minHeightPx, maxHeightPx)

        val minX = geometry.minSafeX
        val maxX = (geometry.maxSafeRight - clampedW).coerceAtLeast(minX)
        val minY = 0
        val maxY = (geometry.maxSafeBottom - clampedH).coerceAtLeast(minY)

        return HudBoundsConstraints(
            minX = minX,
            maxX = maxX,
            minY = minY,
            maxY = maxY,
            minWidthPx = minWidthPx,
            maxWidthPx = maxWidthPx,
            minHeightPx = minHeightPx,
            maxHeightPx = maxHeightPx,
            minSafeRightPx = geometry.maxSafeRight,
            maxSafeBottomPx = geometry.maxSafeBottom
        )
    }

    fun resolveBounds(
        profile: HudOverlayProfile,
        geometry: HudDisplayGeometry,
        minBackdropHeightPx: Int = 0
    ): HudResolvedBounds {
        val safeDensity = geometry.density.coerceAtLeast(0.75f)
        val maxWidthPx = geometry.safeWidthPx.coerceAtLeast(1)
        val minWidthPx = minOf(
            kotlin.math.round(MIN_WIDTH_DP * safeDensity).toInt().coerceAtLeast(120),
            maxWidthPx
        )
        val resolvedWidth = profile.widthPx.coerceIn(minWidthPx, maxWidthPx)

        val maxHeightPx = geometry.usableHeightForClampingPx.coerceAtLeast(1)
        val baseMinHeightPx = minOf(
            kotlin.math.round(MIN_HEIGHT_DP * safeDensity).toInt().coerceAtLeast(40),
            maxHeightPx
        )
        val resolvedHeight = profile.heightPx.coerceIn(baseMinHeightPx, maxHeightPx)
        val effectiveHeight = maxOf(resolvedHeight, minBackdropHeightPx)
            .coerceIn(baseMinHeightPx, maxHeightPx)

        val constraints = computeConstraints(
            geometry = geometry,
            currentWidthPx = resolvedWidth,
            currentEffectiveHeightPx = effectiveHeight,
            minBackdropHeightPx = minBackdropHeightPx
        )

        val resolvedX = profile.x.coerceIn(constraints.minX, constraints.maxX)
        val resolvedY = profile.y.coerceIn(constraints.minY, constraints.maxY)

        return HudResolvedBounds(
            x = resolvedX,
            y = resolvedY,
            widthPx = resolvedWidth,
            heightPx = resolvedHeight,
            effectiveHeightPx = effectiveHeight,
            constraints = constraints
        )
    }

    fun createDefaultLandscapeProfile(
        geometry: HudDisplayGeometry,
        minBackdropHeightPx: Int = 0
    ): HudOverlayProfile {
        val safeDensity = geometry.density.coerceAtLeast(0.75f)
        val safeWidthDp = geometry.safeWidthPx / safeDensity
        val maxSafeWidthDp = (safeWidthDp - 16f).coerceAtLeast(140f)
        val targetWidthDp = (safeWidthDp * 0.56f)
            .coerceIn(DEFAULT_LANDSCAPE_MIN_WIDTH_DP, DEFAULT_LANDSCAPE_MAX_WIDTH_DP)
            .coerceAtMost(maxSafeWidthDp)

        val maxWidthPx = geometry.safeWidthPx.coerceAtLeast(1)
        val minWidthPx = minOf(
            kotlin.math.round(MIN_WIDTH_DP * safeDensity).toInt().coerceAtLeast(120),
            maxWidthPx
        )
        val widthPx = kotlin.math.round(targetWidthDp * safeDensity).toInt()
            .coerceIn(minWidthPx, maxWidthPx)

        val safeHeightBelowTopDp = geometry.safeHeightBelowTopInsetPx / safeDensity
        val targetHeightDp = DEFAULT_LANDSCAPE_HEIGHT_DP
            .coerceAtMost((safeHeightBelowTopDp * 0.30f).coerceAtLeast(40f))
        val rawHeightPx = kotlin.math.round(targetHeightDp * safeDensity).toInt()

        val maxAllowedHeightPx = geometry.usableHeightForClampingPx.coerceAtLeast(1)
        val minHeightPx = minOf(
            kotlin.math.round(MIN_HEIGHT_DP * safeDensity).toInt().coerceAtLeast(40),
            maxAllowedHeightPx
        )
        val heightPx = maxOf(rawHeightPx, minBackdropHeightPx).coerceIn(minHeightPx, maxAllowedHeightPx)

        val minX = geometry.minSafeX
        val maxX = (geometry.maxSafeRight - widthPx).coerceAtLeast(minX)
        val centeredX = (geometry.minSafeX + (geometry.safeWidthPx - widthPx) / 2)
            .coerceIn(minX, maxX)

        val topMarginPx = kotlin.math.round(DEFAULT_LANDSCAPE_TOP_MARGIN_DP * safeDensity).toInt()
        val maxY = (geometry.maxSafeBottom - heightPx).coerceAtLeast(0)
        val minSafeTopY = geometry.safeTopInsetPx.coerceAtMost(maxY)
        val safeY = (geometry.safeTopInsetPx + topMarginPx).coerceIn(minSafeTopY, maxY)

        return HudOverlayProfile(
            x = centeredX,
            y = safeY,
            widthPx = widthPx,
            heightPx = heightPx
        )
    }

    fun computeCenteredProfile(
        widthPx: Int,
        heightPx: Int,
        geometry: HudDisplayGeometry,
        minBackdropHeightPx: Int = 0
    ): HudOverlayProfile {
        val preResolved = resolveBounds(
            profile = HudOverlayProfile(0, 0, widthPx, heightPx),
            geometry = geometry,
            minBackdropHeightPx = minBackdropHeightPx
        )
        val minX = geometry.minSafeX
        val maxX = (geometry.maxSafeRight - preResolved.widthPx).coerceAtLeast(minX)
        val centeredX = (geometry.minSafeX + (geometry.safeWidthPx - preResolved.widthPx) / 2)
            .coerceIn(minX, maxX)

        val maxY = (geometry.maxSafeBottom - preResolved.effectiveHeightPx).coerceAtLeast(0)
        val minSafeTopY = geometry.safeTopInsetPx.coerceAtMost(maxY)
        val safeY = (geometry.safeTopInsetPx + kotlin.math.round(geometry.safeHeightBelowTopInsetPx * 0.14f).toInt())
            .coerceIn(minSafeTopY, maxY)

        return HudOverlayProfile(
            x = centeredX,
            y = safeY,
            widthPx = preResolved.widthPx,
            heightPx = preResolved.heightPx
        )
    }

    fun defaultPlaceholderForGeometry(geometry: HudDisplayGeometry): HudOverlayProfile {
        return if (geometry.orientation == HudOrientation.LANDSCAPE) {
            createDefaultLandscapeProfile(geometry)
        } else {
            val resolved = resolveBounds(
                HudOverlayProfile(
                    x = DEFAULT_PORTRAIT_X,
                    y = DEFAULT_PORTRAIT_Y,
                    widthPx = DEFAULT_PORTRAIT_WIDTH_PX,
                    heightPx = DEFAULT_PORTRAIT_HEIGHT_PX
                ),
                geometry
            )
            HudOverlayProfile(resolved.x, resolved.y, resolved.widthPx, resolved.heightPx)
        }
    }

    fun buildOrientationInfoSnapshot(
        orientation: HudOrientation,
        geometry: HudDisplayGeometry,
        resolvedBounds: HudResolvedBounds
    ): HudOrientationInfoSnapshot {
        val safeDensity = geometry.density.coerceAtLeast(0.75f)
        val isLandscape = orientation == HudOrientation.LANDSCAPE
        val orientationName = if (isLandscape) "Landscape" else "Portrait"
        val screenWDp = (geometry.windowWidthPx / safeDensity).roundToInt()
        val screenHDp = (geometry.windowHeightPx / safeDensity).roundToInt()
        val hudWDp = (resolvedBounds.widthPx / safeDensity).roundToInt()
        val hudHDp = (resolvedBounds.heightPx / safeDensity).roundToInt()
        val hudXDp = (resolvedBounds.x / safeDensity).roundToInt()
        val hudYDp = (resolvedBounds.y / safeDensity).roundToInt()

        return HudOrientationInfoSnapshot(
            orientation = orientation,
            chipLabel = "HUD • $orientationName",
            modeTitle = "$orientationName HUD Profile",
            modeSubtitle = if (isLandscape) "Horizontal / Wide layout" else "Vertical layout",
            explanation = "LyricBar saves separate floating lyrics size and position settings for Portrait and Landscape orientations. Adjustments made now apply only to your $orientationName profile.",
            autoSwitchNote = "Rotating your device or resizing its window switches profiles automatically and keeps the overlay within safe display bounds.",
            screenSizeDpLabel = "$screenWDp × $screenHDp dp",
            hudSizeDpLabel = "$hudWDp × $hudHDp dp",
            hudPositionDpLabel = "X: $hudXDp dp • Y: $hudYDp dp"
        )
    }
}

object HudDisplayMetricsProvider {

    fun getOverlayDisplayGeometry(
        context: Context,
        windowManager: WindowManager? = null,
        rootWindowInsets: WindowInsets? = null
    ): HudDisplayGeometry {
        val wm = windowManager ?: (context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        val dm = context.resources.displayMetrics
        val density = dm.density.coerceAtLeast(0.75f)

        var windowWidthPx = dm.widthPixels.coerceAtLeast(1)
        var windowHeightPx = dm.heightPixels.coerceAtLeast(1)

        var cutoutLeft = 0
        var cutoutTop = 0
        var cutoutRight = 0
        var cutoutBottom = 0

        var navLeft = 0
        var navTop = 0
        var navRight = 0
        var navBottom = 0

        var statusTop = 0

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val metrics = wm.currentWindowMetrics
                val bounds = metrics.bounds
                if (bounds.width() > 0 && bounds.height() > 0) {
                    windowWidthPx = bounds.width()
                    windowHeightPx = bounds.height()
                }
                val insets = metrics.windowInsets
                val cutout = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
                val nav = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                val status = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())

                cutoutLeft = cutout.left
                cutoutTop = cutout.top
                cutoutRight = cutout.right
                cutoutBottom = cutout.bottom

                navLeft = nav.left
                navTop = nav.top
                navRight = nav.right
                navBottom = nav.bottom

                statusTop = status.top
            } catch (_: Throwable) {
                readLegacyDisplayDimensions(wm, context, dm).let { (w, h, navL, navR, navB, statT) ->
                    windowWidthPx = w
                    windowHeightPx = h
                    navLeft = maxOf(navLeft, navL)
                    navRight = maxOf(navRight, navR)
                    navBottom = maxOf(navBottom, navB)
                    statusTop = maxOf(statusTop, statT)
                }
            }
        } else {
            readLegacyDisplayDimensions(wm, context, dm).let { (w, h, navL, navR, navB, statT) ->
                windowWidthPx = w
                windowHeightPx = h
                navLeft = navL
                navRight = navR
                navBottom = navB
                statusTop = statT
            }
        }

        // Merge any attached WindowInsets (covers API 24-29 display cutouts and dynamic taskbar/nav updates)
        if (rootWindowInsets != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cutout = rootWindowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout())
                val nav = rootWindowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
                val status = rootWindowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
                cutoutLeft = maxOf(cutoutLeft, cutout.left)
                cutoutTop = maxOf(cutoutTop, cutout.top)
                cutoutRight = maxOf(cutoutRight, cutout.right)
                cutoutBottom = maxOf(cutoutBottom, cutout.bottom)
                navLeft = maxOf(navLeft, nav.left)
                navTop = maxOf(navTop, nav.top)
                navRight = maxOf(navRight, nav.right)
                navBottom = maxOf(navBottom, nav.bottom)
                statusTop = maxOf(statusTop, status.top)
            } else {
                @Suppress("DEPRECATION")
                run {
                    statusTop = maxOf(statusTop, rootWindowInsets.systemWindowInsetTop)
                    navLeft = maxOf(navLeft, rootWindowInsets.systemWindowInsetLeft)
                    navRight = maxOf(navRight, rootWindowInsets.systemWindowInsetRight)
                    navBottom = maxOf(navBottom, rootWindowInsets.systemWindowInsetBottom)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    rootWindowInsets.displayCutout?.let { dc ->
                        cutoutLeft = maxOf(cutoutLeft, dc.safeInsetLeft)
                        cutoutTop = maxOf(cutoutTop, dc.safeInsetTop)
                        cutoutRight = maxOf(cutoutRight, dc.safeInsetRight)
                        cutoutBottom = maxOf(cutoutBottom, dc.safeInsetBottom)
                    }
                }
            }
        }

        return HudDisplayGeometry(
            windowWidthPx = windowWidthPx,
            windowHeightPx = windowHeightPx,
            density = density,
            cutoutLeftPx = cutoutLeft,
            cutoutTopPx = cutoutTop,
            cutoutRightPx = cutoutRight,
            cutoutBottomPx = cutoutBottom,
            navLeftPx = navLeft,
            navTopPx = navTop,
            navRightPx = navRight,
            navBottomPx = navBottom,
            statusTopPx = statusTop
        )
    }

    @Suppress("DEPRECATION")
    private fun readLegacyDisplayDimensions(
        wm: WindowManager,
        context: Context,
        dm: android.util.DisplayMetrics
    ): LegacyDisplayMetrics {
        val realSize = Point()
        val usableSize = Point()
        var width = dm.widthPixels.coerceAtLeast(1)
        var height = dm.heightPixels.coerceAtLeast(1)
        var navLeft = 0
        var navRight = 0
        var navBottom = 0

        try {
            val display = wm.defaultDisplay
            if (display != null) {
                display.getRealSize(realSize)
                display.getSize(usableSize)
                if (realSize.x > 0 && realSize.y > 0) {
                    width = realSize.x
                    height = realSize.y
                    if (usableSize.x in 1 until realSize.x) {
                        navRight = realSize.x - usableSize.x
                    }
                    if (usableSize.y in 1 until realSize.y) {
                        navBottom = realSize.y - usableSize.y
                    }
                }
            }
        } catch (_: Throwable) {}

        val statusBarResId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusTop = if (statusBarResId > 0) {
            try {
                context.resources.getDimensionPixelSize(statusBarResId)
            } catch (_: Throwable) {
                0
            }
        } else {
            0
        }

        return LegacyDisplayMetrics(
            width = width,
            height = height,
            navLeft = navLeft,
            navRight = navRight,
            navBottom = navBottom,
            statusTop = statusTop
        )
    }

    private data class LegacyDisplayMetrics(
        val width: Int,
        val height: Int,
        val navLeft: Int,
        val navRight: Int,
        val navBottom: Int,
        val statusTop: Int
    )
}
