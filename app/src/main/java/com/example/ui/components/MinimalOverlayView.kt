package com.example.ui.components

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.TextPaint
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import com.example.data.model.LyricLine
import com.example.service.HudDisplayGeometry
import com.example.service.HudOrientation
import com.example.service.HudOverlayProfile
import com.example.service.HudPositioningEngine
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class MinimalOverlayView(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    private val onBoundsChanged: (orientation: HudOrientation, x: Int, y: Int, width: Int, height: Int) -> Unit,
    var onResyncRequested: (() -> Unit)? = null
) : View(context) {

    private val containerRect = android.graphics.RectF()

    // Pre-allocated paints to guarantee zero object allocation during onDraw
    private val wordActivePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    private val wordInactivePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    private val previousTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    private val wordGlowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        setShadowLayer(
            9f,
            0f,
            0f,
            androidx.core.graphics.ColorUtils.setAlphaComponent(
                HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR,
                ACTIVE_GLOW_ALPHA
            )
        )
    }

    private val blurRevealPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    // Editable bounding box paints (displayed ONLY when isDragMode == true; neutral high-contrast indicator)
    private val editBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 244, 244, 246)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val editHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 244, 244, 246)
        style = Paint.Style.FILL
    }

    // Pre-allocated HUD Backdrop paint & RectF buffers (single-alpha clean surface, zero allocation in onDraw)
    private val backdropFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BLACK_BACKDROP_COLOR
    }

    // Subtle neutral hold-to-reposition feedback paints (pre-allocated)
    private val holdHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
    }

    private val holdOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    private val holdFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val holdDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val currentBackdropRect = android.graphics.RectF()
    private val previousBackdropRect = android.graphics.RectF()
    private val drawnBackdropRect = android.graphics.RectF()
    private val holdFeedbackRect = android.graphics.RectF()

    var primaryColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    var activeLyricColor: Int = HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR
        set(value) {
            val safe = HudColorSystem.sanitizeOpaqueColor(value, HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR)
            if (field != safe) {
                field = safe
                wordActivePaint.color = safe
                wordGlowPaint.color = safe
                blurRevealPaint.color = safe
                updateShadow(wordActivePaint, textShadowEnabled, isSubordinate = false, inkColor = safe)
                updateWordGlowLayer(safe)
                invalidate()
            }
        }

    var inactiveLyricColor: Int = HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR
        set(value) {
            val safe = HudColorSystem.sanitizeOpaqueColor(value, HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR)
            if (field != safe) {
                field = safe
                wordInactivePaint.color = safe
                previousTextPaint.color = safe
                updateShadow(wordInactivePaint, textShadowEnabled, isSubordinate = true, inkColor = safe)
                updateShadow(previousTextPaint, textShadowEnabled, isSubordinate = true, inkColor = safe)
                invalidate()
            }
        }

    // Layout data structures for zero-allocation rendering & smooth multi-line wrapping
    data class LayoutWord(
        val word: String,
        val width: Float,
        val startMs: Long,
        val endMs: Long,
        var x: Float = 0f,
        var y: Float = 0f,
        val graphemeBoundaries: IntArray = computeGraphemeBoundaries(word)
    ) {
        val graphemeCount: Int get() = graphemeBoundaries.size
    }

    data class LayoutLine(
        val words: List<LayoutWord>,
        val lineWidth: Float,
        var baselineY: Float = 0f,
        val totalGraphemes: Int = words.sumOf { it.graphemeCount },
        val totalChars: Int = words.sumOf { it.word.length }
    )

    private val currentLayoutLines = mutableListOf<LayoutLine>()
    private var previousLayoutLines = listOf<LayoutLine>()

    private var totalLayoutGraphemes: Int = 0
    private var totalLayoutChars: Int = 0

    private var animProgress: Float = 1f
    private var transitionAnimator: ValueAnimator? = null

    // Vertical auto-scroll state
    private var maxScrollY: Float = 0f
    private var targetScrollY: Float = 0f
    private var currentScrollY: Float = 0f
    private var previousScrollY: Float = 0f

    // Horizontal auto-scroll state for single-line mode
    private var maxScrollX: Float = 0f
    private var targetScrollX: Float = 0f
    private var currentScrollX: Float = 0f
    private var previousScrollX: Float = 0f

    // Playback and synchronization state
    private var isPlaying: Boolean = false
    private var lastPositionMs: Long = 0L
    private var playbackSpeed: Float = 1.0f
    private var lastUpdatedTimestamp: Long = 0L
    private var isScreenOn: Boolean = true

    // Frame animation loop control
    private var isLoopRunning: Boolean = false

    private fun getNormalizedStyle(): String {
        return when (animationStyle) {
            "KARAOKE", "APPLE_MUSIC" -> "KARAOKE"
            "SMOOTH_SLIDE", "SMOOTH_SLIDE_UP", "SOFT_FADE", "SCALE_POP" -> "SMOOTH_SLIDE"
            "FADE", "FADE_IN_OUT" -> "FADE"
            "BLUR_REVEAL", "BLUR" -> "BLUR_REVEAL"
            "TYPEWRITER" -> "TYPEWRITER"
            "INSTANT", "MINIMAL_STATIC" -> "INSTANT"
            else -> "KARAOKE"
        }
    }

    /**
     * Determines whether the view's current visual state requires continuous frame invalidation.
     * Prevents continuous rendering loops when lyrics are static, untimed, or between active phrases.
     */
    fun isAnimationActive(posMs: Long = getCalculatedPositionMs()): Boolean {
        if (!isPlaying || !isAttachedToWindow || !isScreenOn || visibility != VISIBLE) {
            return false
        }

        // 1. Line transition animation is actively running
        if (transitionAnimator?.isRunning == true) {
            return true
        }

        // 2. Smooth vertical auto-scroll in motion
        if (maxScrollY > 0f && abs(targetScrollY - currentScrollY) > 0.3f) {
            return true
        }

        // 3. Smooth horizontal auto-scroll in motion
        if (singleLineMode && maxScrollX > 0f && adaptiveScrollingEnabled && abs(targetScrollX - currentScrollX) > 0.3f) {
            return true
        }

        val currentLine = activeLyricLine
        if (currentLine == null || currentLayoutLines.isEmpty()) {
            return false
        }

        return when (getNormalizedStyle()) {
            "KARAOKE" -> {
                if (currentLine.isWordSynced && currentLine.words.isNotEmpty()) {
                    val startMs = currentLine.words.first().startMs
                    val endMs = currentLine.words.last().endMs
                    // Animate only within the active singing phrase
                    posMs in startMs..(endMs + 100L)
                } else if (currentLine.timeMs > 0L || currentLine.endTimeMs > 0L) {
                    // Line-timed sweep: animate only during the active line duration
                    val lineStart = currentLine.timeMs
                    val rawEnd = currentLine.endTimeMs
                    val lineEnd = if (rawEnd > lineStart) {
                        rawEnd
                    } else if (nextLyricTimestampMs != null && nextLyricTimestampMs!! > lineStart) {
                        nextLyricTimestampMs!!
                    } else {
                        lineStart + 3500L
                    }
                    posMs in lineStart..(lineEnd + 100L)
                } else {
                    // Untimed / static lyrics: no continuous frames required
                    false
                }
            }
            "TYPEWRITER" -> {
                val speed = typewriterSpeed.coerceIn(0.3f, 3.0f)
                if (currentLine.isWordSynced && currentLine.words.isNotEmpty()) {
                    val startMs = currentLine.words.first().startMs
                    val endMs = currentLine.words.last().endMs
                    posMs in startMs..(endMs + 100L)
                } else {
                    val lineStart = currentLine.timeMs
                    val rawEnd = currentLine.endTimeMs
                    val lineEnd = if (rawEnd > lineStart) {
                        rawEnd
                    } else if (nextLyricTimestampMs != null && nextLyricTimestampMs!! > lineStart) {
                        nextLyricTimestampMs!!
                    } else {
                        lineStart + 3500L
                    }
                    val baseDuration = (lineEnd - lineStart).coerceIn(1200L, 4000L)
                    val duration = (baseDuration / speed).toLong().coerceIn(600L, 12000L)
                    // Animate until all characters finish typing
                    posMs in lineStart..(lineStart + duration + 50L)
                }
            }
            // Static line-timed styles only require frames while transitionAnimator runs
            else -> false
        }
    }

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !isScreenOn || visibility != VISIBLE) {
                isLoopRunning = false
                return
            }

            val posMs = getCalculatedPositionMs()

            // Update smooth vertical auto-scroll towards active line
            if (maxScrollY > 0f) {
                updateScrollTarget(posMs)
                val diff = targetScrollY - currentScrollY
                if (abs(diff) > 0.3f) {
                    currentScrollY += diff * 0.16f
                } else {
                    currentScrollY = targetScrollY
                }
            }

            // Update smooth horizontal auto-scroll for single-line mode
            if (singleLineMode && maxScrollX > 0f && adaptiveScrollingEnabled) {
                updateHorizontalScrollTarget(posMs)
                val diffX = targetScrollX - currentScrollX
                if (abs(diffX) > 0.3f) {
                    currentScrollX += diffX * 0.16f
                } else {
                    currentScrollX = targetScrollX
                }
            } else {
                currentScrollX = 0f
                targetScrollX = 0f
            }

            invalidate()

            if (isAnimationActive(posMs)) {
                postOnAnimation(this)
            } else {
                isLoopRunning = false
            }
        }
    }

    var activeLyricLine: LyricLine? = null
        set(value) {
            val sanitizedLine = if (value != null && (value.text.trim().equals("null", ignoreCase = true) || value.text.trim().equals("undefined", ignoreCase = true))) {
                null
            } else {
                value
            }
            val oldLine = field
            val oldText = oldLine?.text ?: text
            val newText = sanitizedLine?.text?.trim()?.takeIf { !it.equals("null", ignoreCase = true) && !it.equals("undefined", ignoreCase = true) } ?: ""
            field = sanitizedLine

            if (oldText != newText) {
                val currentStyle = when (animationStyle) {
                    "KARAOKE", "APPLE_MUSIC" -> "KARAOKE"
                    "SMOOTH_SLIDE", "SMOOTH_SLIDE_UP", "SOFT_FADE", "SCALE_POP" -> "SMOOTH_SLIDE"
                    "FADE", "FADE_IN_OUT" -> "FADE"
                    "BLUR_REVEAL", "BLUR" -> "BLUR_REVEAL"
                    "TYPEWRITER" -> "TYPEWRITER"
                    "INSTANT", "MINIMAL_STATIC" -> "INSTANT"
                    else -> "KARAOKE"
                }

                // Check for normal forward sequential lyric advance
                val isNormalSequentialAdvance = oldLine != null && sanitizedLine != null &&
                    sanitizedLine.timeMs >= oldLine.timeMs &&
                    (sanitizedLine.timeMs - oldLine.timeMs) < 15000L

                if (currentStyle != "INSTANT" && currentStyle != "TYPEWRITER" &&
                    currentLayoutLines.isNotEmpty() && newText.isNotBlank() && isNormalSequentialAdvance) {
                    previousLayoutLines = currentLayoutLines.toList()
                    startUpwardTransition()
                } else {
                    transitionAnimator?.cancel()
                    previousLayoutLines = emptyList()
                    animProgress = 1f
                }
                previousScrollX = currentScrollX
                previousScrollY = currentScrollY
                currentScrollX = 0f
                targetScrollX = 0f
                if (text != newText) {
                    text = newText
                } else {
                    recalculateLayout()
                }
                if (isPlaying) {
                    startLoopIfNeeded()
                }
            } else {
                invalidate()
                if (isPlaying) {
                    startLoopIfNeeded()
                }
            }
        }

    var text: String = ""
        set(value) {
            val sanitized = value.trim().takeIf { !it.equals("null", ignoreCase = true) && !it.equals("undefined", ignoreCase = true) } ?: ""
            if (field != sanitized) {
                field = sanitized
                recalculateLayout()
            }
        }

    var textOpacity: Float = 1.0f
        set(value) {
            val clamped = value.coerceIn(0.15f, 1.0f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var hudBackdropEnabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                recalculateLayout()
                invalidate()
            }
        }

    var hudBackdropStyle: String = "BLACK"
        set(value) {
            val normalized = normalizeBackdropStyle(value)
            if (field != normalized) {
                field = normalized
                invalidate()
            }
        }

    var hudBackdropOpacity: Float = 0.65f
        set(value) {
            val clamped = value.coerceIn(0.0f, 1.0f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var hudBackdropCornerRadiusDp: Float = 18f
        set(value) {
            val clamped = value.coerceIn(0f, 32f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var hudBackdropHorizontalPaddingDp: Float = 16f
        set(value) {
            val clamped = value.coerceIn(0f, 32f)
            if (field != clamped) {
                field = clamped
                recalculateLayout()
                invalidate()
            }
        }

    var hudBackdropVerticalPaddingDp: Float = 10f
        set(value) {
            val clamped = value.coerceIn(0f, 24f)
            if (field != clamped) {
                field = clamped
                recalculateLayout()
                invalidate()
            }
        }

    var typewriterSpeed: Float = 1.0f
        set(value) {
            val clamped = value.coerceIn(0.3f, 3.0f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var smoothTransitionSpeed: Float = 1.0f
        set(value) {
            val clamped = value.coerceIn(0.3f, 3.0f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var fadeSpeed: Float = 1.0f
        set(value) {
            val clamped = value.coerceIn(0.3f, 3.0f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var blurRevealSpeed: Float = 1.0f
        set(value) {
            val clamped = value.coerceIn(0.3f, 3.0f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var karaokeGlowSpeed: Float = 1.0f
        set(value) {
            val clamped = value.coerceIn(0.5f, 1.5f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var karaokeSpeed: Float
        get() = karaokeGlowSpeed
        set(value) {
            karaokeGlowSpeed = value
        }

    var textSizeSp: Float = 14f
        set(value) {
            if (field != value) {
                field = value
                val px = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    value,
                    resources.displayMetrics
                )
                wordActivePaint.textSize = px
                wordInactivePaint.textSize = px
                previousTextPaint.textSize = px
                wordGlowPaint.textSize = px
                blurRevealPaint.textSize = px
                recalculateLayout()
            }
        }

    var textShadowEnabled: Boolean = true
        set(value) {
            field = value
            updateShadow(wordActivePaint, value, isSubordinate = false, inkColor = activeLyricColor)
            updateShadow(wordInactivePaint, value, isSubordinate = true, inkColor = inactiveLyricColor)
            updateShadow(previousTextPaint, value, isSubordinate = true, inkColor = inactiveLyricColor)
            invalidate()
        }

    var textAlignMode: String = "CENTER" // "LEFT", "CENTER", "CENTRE", "RIGHT"
        set(value) {
            if (field != value) {
                field = value
                recalculateLayout()
            }
        }

    var animationStyle: String = "KARAOKE"
        set(value) {
            if (field != value) {
                field = value
                transitionAnimator?.cancel()
                previousLayoutLines = emptyList()
                animProgress = 1f
                blurRevealPaint.clearShadowLayer()
                recalculateLayout()
                startLoopIfNeeded()
                invalidate()
            }
        }

    var singleLineMode: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (!value) {
                    targetScrollX = 0f
                    currentScrollX = 0f
                }
                recalculateLayout()
            }
        }

    var adaptiveScrollingEnabled: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                if (!value) {
                    targetScrollX = 0f
                    currentScrollX = 0f
                }
                recalculateLayout()
            }
        }

    var scrollSpeedMultiplier: Float = 1.0f
        set(value) {
            val clamped = value.coerceIn(1.0f, 1.5f)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var nextLyricTimestampMs: Long? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var trackDurationMs: Long? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var fontResId: Int? = null
        set(value) {
            if (field != value) {
                field = value
                val tf = if (value != null) {
                    try {
                        ResourcesCompat.getFont(context, value)
                    } catch (_: Exception) {
                        null
                    }
                } else null
                val typeface = tf ?: android.graphics.Typeface.DEFAULT_BOLD
                wordActivePaint.typeface = typeface
                wordInactivePaint.typeface = typeface
                previousTextPaint.typeface = typeface
                wordGlowPaint.typeface = typeface
                blurRevealPaint.typeface = typeface
                recalculateLayout()
            }
        }

    var isDragMode: Boolean = false
        set(value) {
            if (field != value) {
                cancelActiveDrag()
                field = value
                invalidate()
            }
        }

    var activeOrientation: HudOrientation = HudOrientation.PORTRAIT
    var displayGeometry: HudDisplayGeometry? = null
        set(value) {
            val prev = field
            field = value
            if (prev != null && value != null &&
                (prev.orientation != value.orientation ||
                    prev.windowWidthPx != value.windowWidthPx ||
                    prev.windowHeightPx != value.windowHeightPx)
            ) {
                cancelActiveDrag()
            }
        }
    var minBackdropHeightPx: Int = 0
    var isPointOnInteractiveControl: ((x: Float, y: Float) -> Boolean)? = null

    // Touch and drag/resize tracking
    private enum class TouchTarget { NONE, MOVE, RESIZE_TOP, RESIZE_BOTTOM, RESIZE_LEFT, RESIZE_RIGHT, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }
    private var activeTouchTarget = TouchTarget.NONE

    // One-second hold-to-reposition controller & subtle neutral visual/haptic feedback
    // Use generous stationary tolerance for a 1000ms finger hold while still cancelling deliberate slides
    private val touchSlopPx = maxOf(
        ViewConfiguration.get(context).scaledTouchSlop * 2.5f,
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics)
    )
    private val holdGestureController = HudHoldGestureController(touchSlopPx = touchSlopPx)
    private val dragCoordinator = HudDragTransactionCoordinator(
        holdController = holdGestureController,
        readBounds = ::readCurrentWindowBounds,
        applyDragBounds = ::applyDragWindowBounds,
        restoreBounds = ::restoreRolledBackBounds,
        commitBounds = { orientation, bounds ->
            onBoundsChanged(orientation, bounds.x, bounds.y, bounds.width, bounds.height)
        },
        onTapAction = {
            onResyncRequested?.invoke()
        }
    )
    private val holdHandler = Handler(Looper.getMainLooper())
    private var holdAttemptToken: Long = 0L
    private var scheduledHoldToken: Long = -1L
    private var holdFeedbackProgress: Float = 0f
    private var holdFeedbackAnimator: ValueAnimator? = null
    private val holdTimeoutRunnable = Runnable {
        if (scheduledHoldToken != holdAttemptToken) {
            return@Runnable
        }
        if (!isAttachedToWindow || visibility != VISIBLE || isDragMode) {
            dragCoordinator.cancelActiveDrag()
            return@Runnable
        }
        val orientation = displayGeometry?.orientation ?: activeOrientation
        if (dragCoordinator.onHoldTimeout(orientation = orientation)) {
            triggerHoldActivatedHaptic()
            animateHoldFeedback(active = true)
        }
    }

    private fun triggerHoldActivatedHaptic() {
        val handledByView = try {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        } catch (_: Throwable) {
            false
        }
        if (!handledByView) {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }
                if (vibrator?.hasVibrator() == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(22L, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(22L)
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    private fun cancelScheduledHoldCallback() {
        holdAttemptToken++
        holdHandler.removeCallbacks(holdTimeoutRunnable)
    }

    private fun animateHoldFeedback(active: Boolean) {
        holdFeedbackAnimator?.cancel()
        val target = if (active) 1f else 0f
        if (!isAttachedToWindow || abs(holdFeedbackProgress - target) < 0.01f) {
            holdFeedbackProgress = target
            invalidate()
            return
        }
        holdFeedbackAnimator = ValueAnimator.ofFloat(holdFeedbackProgress, target).apply {
            duration = if (active) 180L else 140L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                holdFeedbackProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun cancelActiveDrag() {
        cancelScheduledHoldCallback()
        activeTouchTarget = TouchTarget.NONE
        val rolledBack = dragCoordinator.cancelActiveDrag()
        if (rolledBack || holdFeedbackProgress > 0f) {
            animateHoldFeedback(active = false)
        }
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isHapticFeedbackEnabled = true
        val initialPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            textSizeSp,
            resources.displayMetrics
        )
        wordActivePaint.textSize = initialPx
        wordInactivePaint.textSize = initialPx
        previousTextPaint.textSize = initialPx
        wordGlowPaint.textSize = initialPx
        blurRevealPaint.textSize = initialPx
        updateShadow(wordActivePaint, textShadowEnabled, isSubordinate = false, inkColor = activeLyricColor)
        updateShadow(wordInactivePaint, textShadowEnabled, isSubordinate = true, inkColor = inactiveLyricColor)
        updateShadow(previousTextPaint, textShadowEnabled, isSubordinate = true, inkColor = inactiveLyricColor)
        updateWordGlowLayer(activeLyricColor)
    }

    private fun updateShadow(
        paint: Paint,
        enabled: Boolean,
        isSubordinate: Boolean = false,
        inkColor: Int = if (isSubordinate) inactiveLyricColor else activeLyricColor
    ) {
        if (!enabled) {
            paint.clearShadowLayer()
            return
        }
        if (HudColorSystem.isDarkColor(inkColor)) {
            val lightHaloAlpha = if (isSubordinate) 40 else 65
            paint.setShadowLayer(2.5f, 0f, 1f, Color.argb(lightHaloAlpha, 255, 255, 255))
        } else {
            val darkShadowAlpha = if (isSubordinate) INACTIVE_SHADOW_ALPHA else ACTIVE_SHADOW_ALPHA
            val blurRadius = if (isSubordinate) 3.5f else 5.5f
            paint.setShadowLayer(blurRadius, 0f, 1.5f, Color.argb(darkShadowAlpha, 0, 0, 0))
        }
    }

    private fun updateWordGlowLayer(inkColor: Int = activeLyricColor) {
        if (HudColorSystem.isDarkColor(inkColor)) {
            wordGlowPaint.clearShadowLayer()
        } else {
            wordGlowPaint.setShadowLayer(
                9f,
                0f,
                0f,
                androidx.core.graphics.ColorUtils.setAlphaComponent(inkColor, ACTIVE_GLOW_ALPHA)
            )
        }
    }

    fun updatePlaybackState(
        playing: Boolean,
        positionMs: Long,
        speed: Float,
        updatedTimestamp: Long
    ) {
        val wasPlaying = isPlaying
        val posDiff = abs(positionMs - lastPositionMs)
        val isSeeking = posDiff > 1500L

        isPlaying = playing
        lastPositionMs = positionMs
        playbackSpeed = if (speed > 0f) speed else 1.0f
        lastUpdatedTimestamp = updatedTimestamp

        if (!playing) {
            transitionAnimator?.cancel()
            stopLoop()
            invalidate()
            return
        }

        if (!wasPlaying || isSeeking) {
            transitionAnimator?.cancel()
            previousLayoutLines = emptyList()
            animProgress = 1f
            blurRevealPaint.clearShadowLayer()
            if (singleLineMode && maxScrollX > 0f) {
                updateHorizontalScrollTarget(positionMs)
                currentScrollX = targetScrollX
            }
            if (maxScrollY > 0f) {
                updateScrollTarget(positionMs)
                currentScrollY = targetScrollY
            }
        }

        startLoopIfNeeded()
    }

    fun setScreenOn(screenOn: Boolean) {
        isScreenOn = screenOn
        if (screenOn && isPlaying) {
            startLoopIfNeeded()
        }
    }

    fun getCalculatedPositionMs(): Long {
        if (!isPlaying) return lastPositionMs
        val elapsed = (System.currentTimeMillis() - lastUpdatedTimestamp).coerceAtLeast(0L)
        return lastPositionMs + (elapsed * playbackSpeed).toLong()
    }

    private fun startUpwardTransition() {
        transitionAnimator?.cancel()
        val currentStyle = when (animationStyle) {
            "KARAOKE", "APPLE_MUSIC" -> "KARAOKE"
            "SMOOTH_SLIDE", "SMOOTH_SLIDE_UP", "SOFT_FADE", "SCALE_POP" -> "SMOOTH_SLIDE"
            "FADE", "FADE_IN_OUT" -> "FADE"
            "BLUR_REVEAL", "BLUR" -> "BLUR_REVEAL"
            "TYPEWRITER" -> "TYPEWRITER"
            "INSTANT", "MINIMAL_STATIC" -> "INSTANT"
            else -> "KARAOKE"
        }
        if (currentStyle == "INSTANT" || currentStyle == "TYPEWRITER") {
            animProgress = 1f
            invalidate()
            return
        }

        animProgress = 0f
        transitionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            val baseDuration = when (currentStyle) {
                "KARAOKE" -> 240L
                "SMOOTH_SLIDE" -> 300L
                "FADE" -> 360L
                "BLUR_REVEAL" -> 400L
                else -> 240L
            }
            val speed = when (currentStyle) {
                "KARAOKE" -> 1.0f // Keep fade-in/fade-out transition timing unchanged for KARAOKE
                "SMOOTH_SLIDE" -> smoothTransitionSpeed.coerceIn(0.3f, 3.0f)
                "FADE" -> fadeSpeed.coerceIn(0.3f, 3.0f)
                "BLUR_REVEAL" -> blurRevealSpeed.coerceIn(0.3f, 3.0f)
                else -> 1.0f
            }
            duration = kotlin.math.round(baseDuration / speed.toDouble()).toLong().coerceIn(100L, 2000L)
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { animator ->
                animProgress = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        )
    }

    private fun getBackdropHorizontalPaddingPx(): Float {
        return dpToPx(hudBackdropHorizontalPaddingDp.coerceIn(0f, 32f))
    }

    private fun getBackdropVerticalPaddingPx(): Float {
        return dpToPx(hudBackdropVerticalPaddingDp.coerceIn(0f, 24f))
    }

    private fun getEffectiveContentHorizontalInsetPx(): Float {
        return if (hudBackdropEnabled) {
            (getBackdropHorizontalPaddingPx() + 8f).coerceAtLeast(16f)
        } else {
            28f
        }
    }

    private fun populateBackdropRectForLines(
        lines: List<LayoutLine>,
        scrollY: Float,
        outRect: android.graphics.RectF
    ): Boolean {
        if (!hudBackdropEnabled || hudBackdropOpacity <= 0f || lines.isEmpty() || width <= 0 || height <= 0) {
            return false
        }
        val paddingHorizontal = getEffectiveContentHorizontalInsetPx()
        val contentLeft = paddingHorizontal
        val contentRight = (width - paddingHorizontal).coerceAtLeast(contentLeft + 60f)

        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        for (line in lines) {
            if (line.words.isEmpty()) continue
            val firstWordX = line.words.first().x
            val lineRight = firstWordX + line.lineWidth
            if (firstWordX < minX) minX = firstWordX
            if (lineRight > maxX) maxX = lineRight
        }
        if (minX > maxX) return false

        val textLeft = minX.coerceIn(contentLeft, contentRight)
        val textRight = maxX.coerceIn(textLeft, contentRight)

        val fontMetrics = wordActivePaint.fontMetrics
        val rawTop = lines.first().baselineY - scrollY + fontMetrics.ascent
        val rawBottom = lines.last().baselineY - scrollY + fontMetrics.descent

        val horizPadPx = getBackdropHorizontalPaddingPx()
        val vertPadPx = getBackdropVerticalPaddingPx()
        val minTopInset = (vertPadPx + 2f).coerceAtMost(height / 3f)
        val maxBottomInset = (height - vertPadPx - 2f).coerceAtLeast(minTopInset)

        val textTop = if (maxScrollY > 0f) rawTop.coerceAtLeast(minTopInset) else rawTop
        val textBottom = if (maxScrollY > 0f) rawBottom.coerceAtMost(maxBottomInset) else rawBottom
        if (textBottom <= textTop) return false

        val left = (textLeft - horizPadPx).coerceAtLeast(2f)
        val top = (textTop - vertPadPx).coerceAtLeast(2f)
        val right = (textRight + horizPadPx).coerceAtMost((width - 2f).coerceAtLeast(left))
        val bottom = (textBottom + vertPadPx).coerceAtMost((height - 2f).coerceAtLeast(top))

        outRect.set(left, top, right, bottom)
        return outRect.width() > 0f && outRect.height() > 0f
    }

    private fun drawHudBackdropIfNeeded(canvas: Canvas, currentStyle: String) {
        if (!hudBackdropEnabled || hudBackdropOpacity <= 0f) {
            drawnBackdropRect.setEmpty()
            return
        }

        val hasCurrent = populateBackdropRectForLines(currentLayoutLines, currentScrollY, currentBackdropRect)
        val isTransitioning = animProgress < 1f &&
            previousLayoutLines.isNotEmpty() &&
            currentStyle != "INSTANT" &&
            currentStyle != "TYPEWRITER"
        val hasPrevious = if (isTransitioning) {
            populateBackdropRectForLines(previousLayoutLines, previousScrollY, previousBackdropRect)
        } else {
            false
        }

        if (!hasCurrent && !hasPrevious) {
            drawnBackdropRect.setEmpty()
            return
        }

        if (hasCurrent && hasPrevious) {
            val t = animProgress.coerceIn(0f, 1f)
            drawnBackdropRect.set(
                previousBackdropRect.left + (currentBackdropRect.left - previousBackdropRect.left) * t,
                previousBackdropRect.top + (currentBackdropRect.top - previousBackdropRect.top) * t,
                previousBackdropRect.right + (currentBackdropRect.right - previousBackdropRect.right) * t,
                previousBackdropRect.bottom + (currentBackdropRect.bottom - previousBackdropRect.bottom) * t
            )
        } else if (hasCurrent) {
            drawnBackdropRect.set(currentBackdropRect)
        } else {
            drawnBackdropRect.set(previousBackdropRect)
        }

        val density = resources.displayMetrics.density
        val clampedOpacity = hudBackdropOpacity.coerceIn(0f, 1f)
        val appearance = computeBackdropAppearance(hudBackdropStyle, clampedOpacity)
        val maxRadiusPx = (minOf(drawnBackdropRect.width(), drawnBackdropRect.height()) / 2f).coerceAtLeast(0f)
        val cornerRadiusPx = (hudBackdropCornerRadiusDp.coerceIn(0f, 32f) * density).coerceIn(0f, maxRadiusPx)

        backdropFillPaint.color = appearance.fillColor
        backdropFillPaint.alpha = appearance.fillAlpha
        canvas.drawRoundRect(
            drawnBackdropRect,
            cornerRadiusPx,
            cornerRadiusPx,
            backdropFillPaint
        )
    }

    private fun recalculateLayout() {
        currentLayoutLines.clear()
        totalLayoutGraphemes = 0
        totalLayoutChars = 0
        maxScrollY = 0f
        targetScrollY = 0f
        currentScrollY = 0f
        maxScrollX = 0f
        targetScrollX = 0f
        currentScrollX = 0f

        val currentLine = activeLyricLine
        val rawText = currentLine?.text ?: text
        if (rawText.isBlank() || width <= 0) {
            invalidate()
            return
        }

        val paddingHorizontal = getEffectiveContentHorizontalInsetPx()
        val contentLeft = paddingHorizontal
        val contentRight = (width - paddingHorizontal).coerceAtLeast(contentLeft + 60f)
        val availableWidth = (contentRight - contentLeft).coerceAtLeast(60f)
        val spaceWidth = wordActivePaint.measureText(" ")

        // 1. Prepare raw word list (either with word-level timestamps or single-word fallback)
        val wordsToWrap: List<LayoutWord>
        if (currentLine != null && currentLine.isWordSynced && currentLine.words.isNotEmpty()) {
            wordsToWrap = currentLine.words.map { wt ->
                LayoutWord(
                    word = wt.word,
                    width = wordActivePaint.measureText(wt.word),
                    startMs = wt.startMs,
                    endMs = wt.endMs
                )
            }
        } else {
            val tokens = rawText.trim().split(Regex("\\s+"))
            val lineStart = currentLine?.timeMs ?: 0L
            val rawEnd = currentLine?.endTimeMs ?: 0L
            val lineEnd = if (rawEnd > lineStart) {
                rawEnd
            } else if (nextLyricTimestampMs != null && nextLyricTimestampMs!! > lineStart) {
                nextLyricTimestampMs!!
            } else {
                lineStart + 3500L
            }
            wordsToWrap = tokens.map { token ->
                LayoutWord(
                    word = token,
                    width = wordActivePaint.measureText(token),
                    startMs = lineStart,
                    endMs = lineEnd
                )
            }
        }

        if (wordsToWrap.isEmpty()) {
            invalidate()
            return
        }

        // 2. Wrap words into lines strictly respecting availableWidth (or keep single line if singleLineMode)
        val lineList = mutableListOf<LayoutLine>()
        if (singleLineMode) {
            var curLineWidth = 0f
            for ((idx, w) in wordsToWrap.withIndex()) {
                curLineWidth += w.width + (if (idx > 0) spaceWidth else 0f)
            }
            lineList.add(LayoutLine(wordsToWrap.toMutableList(), curLineWidth))
            maxScrollX = if (adaptiveScrollingEnabled && curLineWidth > availableWidth) {
                (curLineWidth - availableWidth + 24f).coerceAtLeast(0f)
            } else {
                0f
            }
        } else {
            var curLineWords = mutableListOf<LayoutWord>()
            var curLineWidth = 0f

            for (w in wordsToWrap) {
                val additionalWidth = if (curLineWords.isEmpty()) w.width else (spaceWidth + w.width)
                if (curLineWords.isNotEmpty() && (curLineWidth + additionalWidth > availableWidth)) {
                    lineList.add(LayoutLine(curLineWords, curLineWidth))
                    curLineWords = mutableListOf(w)
                    curLineWidth = w.width
                } else {
                    curLineWords.add(w)
                    curLineWidth += additionalWidth
                }
            }
            if (curLineWords.isNotEmpty()) {
                lineList.add(LayoutLine(curLineWords, curLineWidth))
            }
            maxScrollX = 0f
        }

        // 3. Compute paragraph alignment (LEFT, CENTER / CENTRE, RIGHT) for each line
        val alignUpper = textAlignMode.trim().uppercase()
        for (line in lineList) {
            val startX = if (singleLineMode && line.lineWidth > availableWidth && adaptiveScrollingEnabled) {
                // When single line exceeds width and scrolling is enabled, start at contentLeft
                contentLeft
            } else {
                when (alignUpper) {
                    "LEFT" -> contentLeft
                    "RIGHT" -> (contentRight - line.lineWidth).coerceAtLeast(contentLeft)
                    else -> contentLeft + ((availableWidth - line.lineWidth) / 2f).coerceAtLeast(0f)
                }
            }
            var curX = startX
            for (w in line.words) {
                w.x = curX
                curX += w.width + spaceWidth
            }
        }

        // 4. Compute vertical baseline coordinates & auto-scroll boundaries
        val fontMetrics = wordActivePaint.fontMetrics
        val fontHeight = fontMetrics.descent - fontMetrics.ascent
        val lineSpacing = (fontHeight * 0.28f).coerceAtLeast(6f)
        val lineHeight = fontHeight + lineSpacing
        val totalTextHeight = (lineList.size * lineHeight) - lineSpacing
        val availableHeight = height.toFloat()
        val verticalInset = if (hudBackdropEnabled) (getBackdropVerticalPaddingPx() + 4f) else 0f
        val effectiveTextViewportHeight = (availableHeight - verticalInset * 2f).coerceAtLeast(fontHeight)

        if (totalTextHeight <= effectiveTextViewportHeight) {
            // Fits entirely inside overlay: center vertically
            val topY = (availableHeight - totalTextHeight) / 2f
            val startBaseline = topY - fontMetrics.ascent
            lineList.forEachIndexed { idx, line ->
                val by = startBaseline + (idx * lineHeight)
                line.baselineY = by
                line.words.forEach { it.y = by }
            }
            maxScrollY = 0f
        } else {
            // Multi-line wrapped text exceeds overlay height: smooth vertical auto-scroll mode
            val topMargin = verticalInset.coerceAtLeast(18f)
            val startBaseline = topMargin - fontMetrics.ascent
            lineList.forEachIndexed { idx, line ->
                val by = startBaseline + (idx * lineHeight)
                line.baselineY = by
                line.words.forEach { it.y = by }
            }
            maxScrollY = (totalTextHeight - effectiveTextViewportHeight + 16f).coerceAtLeast(0f)
        }

        currentLayoutLines.addAll(lineList)
        totalLayoutGraphemes = currentLayoutLines.sumOf { it.totalGraphemes }
        totalLayoutChars = currentLayoutLines.sumOf { it.totalChars }
        startLoopIfNeeded()
        invalidate()
    }

    private fun updateScrollTarget(posMs: Long) {
        if (maxScrollY <= 0f || currentLayoutLines.isEmpty()) {
            targetScrollY = 0f
            return
        }

        val isWordSynced = activeLyricLine?.isWordSynced == true
        if (activeLyricLine?.timeMs == 0L && activeLyricLine?.endTimeMs == 0L && !isWordSynced) {
            targetScrollY = 0f
            return
        }

        var activeLineIdx = 0
        if (isWordSynced && activeLyricLine?.words?.isNotEmpty() == true) {
            for ((lineIdx, line) in currentLayoutLines.withIndex()) {
                val lineStartMs = line.words.firstOrNull()?.startMs ?: 0L
                if (posMs >= lineStartMs) {
                    activeLineIdx = lineIdx
                }
            }
        } else {
            val lineStart = activeLyricLine?.timeMs ?: 0L
            val rawEnd = activeLyricLine?.endTimeMs ?: 0L
            val lineEnd = if (rawEnd > lineStart) {
                rawEnd
            } else if (nextLyricTimestampMs != null && nextLyricTimestampMs!! > lineStart) {
                nextLyricTimestampMs!!
            } else {
                lineStart + 3500L
            }
            val rawDuration = (lineEnd - lineStart).coerceIn(1200L, 12000L)
            val totalChars = currentLayoutLines.sumOf { l -> l.words.sumOf { it.word.length } }.coerceAtLeast(1)
            var charAcc = 0
            for ((lineIdx, line) in currentLayoutLines.withIndex()) {
                val lineChars = line.words.sumOf { it.word.length }.coerceAtLeast(1)
                val startFrac = charAcc.toFloat() / totalChars
                val lStart = lineStart + (rawDuration * startFrac).toLong()
                if (posMs >= lStart) {
                    activeLineIdx = lineIdx
                }
                charAcc += lineChars
            }
        }

        val fontMetrics = wordActivePaint.fontMetrics
        val fontHeight = fontMetrics.descent - fontMetrics.ascent
        val activeLineCenterY = currentLayoutLines[activeLineIdx].baselineY + fontMetrics.ascent + (fontHeight / 2f)

        // Center the active line vertically in the overlay window
        val idealScrollY = activeLineCenterY - (height / 2f)
        targetScrollY = idealScrollY.coerceIn(0f, maxScrollY)
    }

    private fun updateHorizontalScrollTarget(posMs: Long) {
        if (!singleLineMode || !adaptiveScrollingEnabled || maxScrollX <= 0f || currentLayoutLines.isEmpty()) {
            targetScrollX = 0f
            return
        }

        val isWordSynced = activeLyricLine?.isWordSynced == true
        if (activeLyricLine?.timeMs == 0L && activeLyricLine?.endTimeMs == 0L && !isWordSynced) {
            targetScrollX = 0f
            return
        }

        val line = currentLayoutLines.firstOrNull() ?: return
        val currentStyle = when (animationStyle) {
            "KARAOKE", "APPLE_MUSIC" -> "KARAOKE"
            "SMOOTH_SLIDE", "SMOOTH_SLIDE_UP", "SOFT_FADE", "SCALE_POP" -> "SMOOTH_SLIDE"
            "TYPEWRITER" -> "TYPEWRITER"
            "INSTANT", "MINIMAL_STATIC" -> "INSTANT"
            else -> "KARAOKE"
        }

        val paddingHorizontal = getEffectiveContentHorizontalInsetPx()
        val contentLeft = paddingHorizontal
        val availableWidth = (width - paddingHorizontal * 2).coerceAtLeast(60f)

        if (currentStyle == "KARAOKE" && isWordSynced && line.words.isNotEmpty()) {
            // Apple-style Karaoke with word-level timing: follow active singing focus point smoothly
            val firstWordStart = line.words.first().startMs
            val lastWordEnd = line.words.last().endMs.coerceAtLeast(firstWordStart + 1L)

            if (posMs < firstWordStart) {
                targetScrollX = 0f
                return
            }
            if (posMs >= lastWordEnd) {
                targetScrollX = maxScrollX
                return
            }

            var focusX = contentLeft
            for (w in line.words) {
                if (posMs < w.startMs) {
                    break
                } else if (posMs >= w.endMs) {
                    focusX = w.x + w.width
                } else {
                    val wProg = com.example.domain.lyricsengine.LyricsTimingEngine.calculateWordGlowProgress(
                        startMs = w.startMs,
                        endMs = w.endMs,
                        currentPositionMs = posMs,
                        speedMultiplier = karaokeGlowSpeed
                    )
                    focusX = w.x + (w.width * wProg)
                    break
                }
            }

            val desiredOffset = (focusX - (contentLeft + availableWidth * 0.45f)) * scrollSpeedMultiplier.coerceIn(1.0f, 1.5f)
            targetScrollX = desiredOffset.coerceIn(0f, maxScrollX)
        } else if (currentStyle == "TYPEWRITER") {
            // Coordinate horizontal scrolling with typewriter character reveal
            val speed = (typewriterSpeed * scrollSpeedMultiplier).coerceIn(0.3f, 4.5f)
            val lineStart = activeLyricLine?.timeMs ?: 0L
            val rawEnd = activeLyricLine?.endTimeMs ?: 0L
            val lineEnd = if (rawEnd > lineStart) {
                rawEnd
            } else if (nextLyricTimestampMs != null && nextLyricTimestampMs!! > lineStart) {
                nextLyricTimestampMs!!
            } else {
                lineStart + 3500L
            }
            val baseDuration = (lineEnd - lineStart).coerceIn(1200L, 8000L)
            val duration = (baseDuration / speed).toLong().coerceIn(600L, 12000L)

            val frac = if (posMs < lineStart) 0f
                else if (posMs >= lineStart + duration) 1f
                else ((posMs - lineStart).toFloat() / duration).coerceIn(0f, 1f)

            val revealedWidth = line.lineWidth * frac
            val desiredOffset = (contentLeft + revealedWidth) - (contentLeft + availableWidth * 0.55f)
            targetScrollX = desiredOffset.coerceIn(0f, maxScrollX)
        } else {
            // Playback-synchronized adaptive horizontal scroll based on current and next lyric timestamps
            val lineStart = activeLyricLine?.timeMs ?: 0L
            val progress = com.example.domain.lyricsengine.LyricsTimingEngine.calculateAdaptiveScrollProgress(
                currentLineStartMs = lineStart,
                nextTimestampMs = nextLyricTimestampMs,
                trackDurationMs = trackDurationMs,
                currentPositionMs = posMs,
                speedMultiplier = scrollSpeedMultiplier
            )
            targetScrollX = (maxScrollX * progress).coerceIn(0f, maxScrollX)
        }
    }

    fun startLoopIfNeeded() {
        if (!isAttachedToWindow || !isScreenOn || visibility != VISIBLE) {
            isLoopRunning = false
            return
        }

        val posMs = getCalculatedPositionMs()
        if (isAnimationActive(posMs)) {
            if (!isLoopRunning) {
                isLoopRunning = true
                removeCallbacks(frameRunnable)
                postOnAnimation(frameRunnable)
            }
        } else {
            // Not in an active animation window: redraw once to render current state and do not loop
            if (isLoopRunning) {
                removeCallbacks(frameRunnable)
                isLoopRunning = false
            }
            invalidate()
        }
    }

    fun stopLoop() {
        removeCallbacks(frameRunnable)
        transitionAnimator?.cancel()
        isLoopRunning = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isPlaying) {
            startLoopIfNeeded()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelActiveDrag()
        stopLoop()
        transitionAnimator?.cancel()
        transitionAnimator = null
        holdFeedbackAnimator?.cancel()
        holdFeedbackAnimator = null
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            cancelActiveDrag()
            stopLoop()
        } else if (isPlaying) {
            startLoopIfNeeded()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            cancelActiveDrag()
            stopLoop()
        } else if (isPlaying) {
            startLoopIfNeeded()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            containerRect.set(4f, 4f, w - 4f, h - 4f)
        }
        recalculateLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val heightSpecMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSpecSize = MeasureSpec.getSize(heightMeasureSpec)

        val fontMetrics = wordActivePaint.fontMetrics
        val extraBackdropHeight = if (hudBackdropEnabled) (getBackdropVerticalPaddingPx() * 2f).roundToInt() else 0
        val minHeight = (((fontMetrics.descent - fontMetrics.ascent) * 2.8f).roundToInt() + extraBackdropHeight).coerceIn(60, 260)

        val finalHeight = if (heightSpecMode == MeasureSpec.EXACTLY && heightSpecSize > 0) {
            heightSpecSize
        } else {
            minHeight
        }
        setMeasuredDimension(width, finalHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (visibility != VISIBLE || alpha <= 0f) {
            return
        }

        val currentStyle = when (animationStyle) {
            "KARAOKE", "APPLE_MUSIC" -> "KARAOKE"
            "SMOOTH_SLIDE", "SMOOTH_SLIDE_UP", "SOFT_FADE", "SCALE_POP" -> "SMOOTH_SLIDE"
            "FADE", "FADE_IN_OUT" -> "FADE"
            "BLUR_REVEAL", "BLUR" -> "BLUR_REVEAL"
            "TYPEWRITER" -> "TYPEWRITER"
            "INSTANT", "MINIMAL_STATIC" -> "INSTANT"
            else -> "KARAOKE"
        }

        // 0. Draw optional content-wrapping HUD Backdrop behind the lyrics
        drawHudBackdropIfNeeded(canvas, currentStyle)

        val paddingHorizontal = if (hudBackdropEnabled) {
            (getEffectiveContentHorizontalInsetPx() - 2f).coerceAtLeast(4f)
        } else {
            24f
        }

        // Clip container area so text wraps and scrolls cleanly within configured overlay boundaries
        canvas.save()
        canvas.clipRect(paddingHorizontal, 0f, width - paddingHorizontal, height.toFloat())

        // 1. Unified customizable lyric colour & alpha hierarchy across active, inactive, and outgoing lines
        val isFadeStyle = currentStyle == "FADE" || currentStyle == "BLUR_REVEAL"
        val isAnimatedTransition = currentStyle == "KARAOKE" || currentStyle == "SMOOTH_SLIDE" || isFadeStyle
        val hierarchy = computeLyricColorHierarchy(
            activeColor = activeLyricColor,
            inactiveColor = inactiveLyricColor,
            textOpacity = textOpacity,
            animProgress = if (isAnimatedTransition) animProgress else 1f,
            isFadeStyle = isFadeStyle
        )
        val baseAlpha = hierarchy.baseAlpha

        if (animProgress < 1f && previousLayoutLines.isNotEmpty() && currentStyle != "INSTANT" && currentStyle != "TYPEWRITER") {
            val outgoingOffsetY = if (currentStyle == "KARAOKE" || currentStyle == "SMOOTH_SLIDE") {
                -animProgress * 24f
            } else {
                0f
            }
            previousTextPaint.color = hierarchy.inactiveColor
            previousTextPaint.alpha = hierarchy.outgoingAlpha
            canvas.save()
            if (singleLineMode && previousScrollX > 0f) {
                canvas.translate(-previousScrollX, 0f)
            }
            for (line in previousLayoutLines) {
                for (w in line.words) {
                    canvas.drawText(w.word, w.x, w.y + outgoingOffsetY, previousTextPaint)
                }
            }
            canvas.restore()
        }

        // 2. Draw incoming / active lyric line
        val incomingOffsetY = if (animProgress < 1f && (currentStyle == "KARAOKE" || currentStyle == "SMOOTH_SLIDE")) {
            (1f - animProgress) * 18f
        } else 0f
        val incomingAlpha = hierarchy.activeAlpha
        val inactiveAlpha = hierarchy.inactiveAlpha
        val posMs = getCalculatedPositionMs()
        val isWordSynced = activeLyricLine?.isWordSynced == true

        canvas.save()
        if (singleLineMode && currentScrollX > 0f) {
            canvas.translate(-currentScrollX, 0f)
        }

        val isDarkActiveInk = HudColorSystem.isDarkColor(hierarchy.activeColor)

        when (currentStyle) {
            "KARAOKE" -> {
                if (isWordSynced && activeLyricLine?.words?.isNotEmpty() == true) {
                    // 1. Word-synchronized lyrics: user's active & inactive lyric colours
                    wordInactivePaint.color = hierarchy.inactiveColor
                    wordInactivePaint.alpha = inactiveAlpha
                    wordActivePaint.color = hierarchy.activeColor
                    wordActivePaint.alpha = incomingAlpha
                    wordGlowPaint.color = hierarchy.activeColor
                    wordGlowPaint.alpha = incomingAlpha

                    // Determine the single active visual line index
                    var activeLineIdx = 0
                    for ((lineIdx, line) in currentLayoutLines.withIndex()) {
                        val lineStartMs = line.words.firstOrNull()?.startMs ?: 0L
                        if (posMs >= lineStartMs) {
                            activeLineIdx = lineIdx
                        }
                    }

                    for ((lineIdx, line) in currentLayoutLines.withIndex()) {
                        val drawY = line.baselineY - currentScrollY + incomingOffsetY

                        if (lineIdx != activeLineIdx) {
                            // Inactive lines (previous or upcoming): display dimmed, never highlighted or glowing
                            for (w in line.words) {
                                canvas.drawText(w.word, w.x, drawY, wordInactivePaint)
                            }
                        } else {
                            // Single active line: progressive word highlight
                            for (w in line.words) {
                                if (posMs < w.startMs) {
                                    // Word not sung yet on active line: draw with subordinate inactive opacity
                                    canvas.drawText(w.word, w.x, drawY, wordInactivePaint)
                                } else if (posMs >= w.endMs) {
                                    // Word fully sung on active line: draw with glowing high-opacity active text
                                    if (!isDarkActiveInk) {
                                        canvas.drawText(w.word, w.x, drawY, wordGlowPaint)
                                    }
                                    canvas.drawText(w.word, w.x, drawY, wordActivePaint)
                                } else {
                                    // Word actively being sung on active line: progressive fill from left to right
                                    val progress = com.example.domain.lyricsengine.LyricsTimingEngine.calculateWordGlowProgress(
                                        startMs = w.startMs,
                                        endMs = w.endMs,
                                        currentPositionMs = posMs,
                                        speedMultiplier = karaokeGlowSpeed
                                    )

                                    val splitX = w.x + (w.width * progress)

                                    // Unsung right portion at inactive colour/opacity (non-overlapping clip)
                                    canvas.save()
                                    canvas.clipRect(splitX, 0f, w.x + w.width + 16f, height.toFloat())
                                    canvas.drawText(w.word, w.x, drawY, wordInactivePaint)
                                    canvas.restore()

                                    // Sung left portion with glowing high-opacity active text
                                    if (progress > 0f) {
                                        canvas.save()
                                        canvas.clipRect(w.x - 16f, 0f, splitX, height.toFloat())
                                        if (!isDarkActiveInk) {
                                            canvas.drawText(w.word, w.x, drawY, wordGlowPaint)
                                        }
                                        canvas.drawText(w.word, w.x, drawY, wordActivePaint)
                                        canvas.restore()
                                    }
                                }
                            }
                        }
                    }
                } else if (activeLyricLine?.timeMs == 0L && activeLyricLine?.endTimeMs == 0L && !isWordSynced) {
                    // 2. Untimed / plain lyrics fallback: display clearly as readable static text without fake karaoke sweep
                    wordActivePaint.color = hierarchy.activeColor
                    wordActivePaint.alpha = incomingAlpha
                    for (line in currentLayoutLines) {
                        val drawY = line.baselineY - currentScrollY + incomingOffsetY
                        for (w in line.words) {
                            canvas.drawText(w.word, w.x, drawY, wordActivePaint)
                        }
                    }
                } else if (currentLayoutLines.isNotEmpty()) {
                    // 3. Synchronized line-timed LRC:
                    val lineStart = activeLyricLine?.timeMs ?: 0L
                    val rawEnd = activeLyricLine?.endTimeMs ?: 0L
                    val lineEnd = if (rawEnd > lineStart) {
                        rawEnd
                    } else if (nextLyricTimestampMs != null && nextLyricTimestampMs!! > lineStart) {
                        nextLyricTimestampMs!!
                    } else {
                        lineStart + 3500L
                    }
                    val totalDuration = (lineEnd - lineStart).coerceIn(1200L, 12000L)
                    val totalChars = totalLayoutChars.coerceAtLeast(1)

                    var activeLineIdx = 0
                    var activeLineStart = lineStart
                    var activeLineDuration = totalDuration
                    var charAccumulator = 0

                    for (idx in 0 until currentLayoutLines.size) {
                        val line = currentLayoutLines[idx]
                        val lineChars = line.totalChars.coerceAtLeast(1)
                        val startFrac = charAccumulator.toFloat() / totalChars
                        val endFrac = (charAccumulator + lineChars).toFloat() / totalChars
                        val lStart = lineStart + (totalDuration * startFrac).toLong()
                        val lEnd = lineStart + (totalDuration * endFrac).toLong()

                        if (posMs >= lStart && (idx == currentLayoutLines.lastIndex || posMs < lEnd)) {
                            activeLineIdx = idx
                            activeLineStart = lStart
                            activeLineDuration = (lEnd - lStart).coerceAtLeast(1L)
                            break
                        } else if (idx == currentLayoutLines.lastIndex && posMs >= lEnd) {
                            activeLineIdx = idx
                            activeLineStart = lStart
                            activeLineDuration = (lEnd - lStart).coerceAtLeast(1L)
                        }
                        charAccumulator += lineChars
                    }

                    wordInactivePaint.color = hierarchy.inactiveColor
                    wordInactivePaint.alpha = inactiveAlpha
                    wordActivePaint.color = hierarchy.activeColor
                    wordActivePaint.alpha = incomingAlpha
                    wordGlowPaint.color = hierarchy.activeColor
                    wordGlowPaint.alpha = incomingAlpha

                    // Draw prior completed visual lines (0 until activeLineIdx) fully highlighted
                    for (prevIdx in 0 until activeLineIdx) {
                        val prevLine = currentLayoutLines[prevIdx]
                        val drawY = prevLine.baselineY - currentScrollY + incomingOffsetY
                        for (wIdx in 0 until prevLine.words.size) {
                            val w = prevLine.words[wIdx]
                            if (!isDarkActiveInk) {
                                canvas.drawText(w.word, w.x, drawY, wordGlowPaint)
                            }
                            canvas.drawText(w.word, w.x, drawY, wordActivePaint)
                        }
                    }

                    val lineProgress = if (posMs < activeLineStart) {
                        0f
                    } else if (posMs >= activeLineStart + activeLineDuration) {
                        1f
                    } else {
                        com.example.domain.lyricsengine.LyricsTimingEngine.calculateWordGlowProgress(
                            startMs = activeLineStart,
                            endMs = activeLineStart + activeLineDuration,
                            currentPositionMs = posMs,
                            speedMultiplier = karaokeGlowSpeed
                        )
                    }

                    val activeLine = currentLayoutLines[activeLineIdx]
                    val activeDrawY = activeLine.baselineY - currentScrollY + incomingOffsetY
                    when {
                        lineProgress <= 0f -> {
                            for (wIdx in 0 until activeLine.words.size) {
                                val w = activeLine.words[wIdx]
                                canvas.drawText(w.word, w.x, activeDrawY, wordInactivePaint)
                            }
                        }
                        lineProgress >= 1f -> {
                            for (wIdx in 0 until activeLine.words.size) {
                                val w = activeLine.words[wIdx]
                                if (!isDarkActiveInk) {
                                    canvas.drawText(w.word, w.x, activeDrawY, wordGlowPaint)
                                }
                                canvas.drawText(w.word, w.x, activeDrawY, wordActivePaint)
                            }
                        }
                        else -> {
                            val lineLeft = activeLine.words.firstOrNull()?.x ?: paddingHorizontal
                            val lineRight = (activeLine.words.lastOrNull()?.let { it.x + it.width }) ?: (width - paddingHorizontal)
                            val sweepX = lineLeft + ((lineRight - lineLeft) * lineProgress)

                            // Unsung right half in inactive colour/opacity
                            canvas.save()
                            canvas.clipRect(sweepX, 0f, lineRight + 64f, height.toFloat())
                            for (wIdx in 0 until activeLine.words.size) {
                                val w = activeLine.words[wIdx]
                                canvas.drawText(w.word, w.x, activeDrawY, wordInactivePaint)
                            }
                            canvas.restore()

                            // Sung left half in active colour/opacity
                            canvas.save()
                            canvas.clipRect(lineLeft - 64f, 0f, sweepX, height.toFloat())
                            for (wIdx in 0 until activeLine.words.size) {
                                val w = activeLine.words[wIdx]
                                if (!isDarkActiveInk) {
                                    canvas.drawText(w.word, w.x, activeDrawY, wordGlowPaint)
                                }
                                canvas.drawText(w.word, w.x, activeDrawY, wordActivePaint)
                            }
                            canvas.restore()
                        }
                    }

                    // Draw remaining upcoming visual lines purely in inactive colour/opacity
                    for (nextIdx in (activeLineIdx + 1) until currentLayoutLines.size) {
                        val nextLine = currentLayoutLines[nextIdx]
                        val drawY = nextLine.baselineY - currentScrollY + incomingOffsetY
                        for (wIdx in 0 until nextLine.words.size) {
                            val w = nextLine.words[wIdx]
                            canvas.drawText(w.word, w.x, drawY, wordInactivePaint)
                        }
                    }
                }
            }
            "SMOOTH_SLIDE" -> {
                wordActivePaint.color = hierarchy.activeColor
                wordActivePaint.alpha = incomingAlpha
                for (line in currentLayoutLines) {
                    val drawY = line.baselineY - currentScrollY + incomingOffsetY
                    for (w in line.words) {
                        canvas.drawText(w.word, w.x, drawY, wordActivePaint)
                    }
                }
            }
            "FADE" -> {
                wordActivePaint.color = hierarchy.activeColor
                wordActivePaint.alpha = incomingAlpha
                for (line in currentLayoutLines) {
                    val drawY = line.baselineY - currentScrollY
                    for (w in line.words) {
                        canvas.drawText(w.word, w.x, drawY, wordActivePaint)
                    }
                }
            }
            "BLUR_REVEAL" -> {
                if (animProgress < 1f) {
                    val blurRadius = (1f - animProgress) * 14f
                    if (blurRadius > 0.8f) {
                        blurRevealPaint.color = hierarchy.activeColor
                        blurRevealPaint.alpha = ((1f - animProgress) * baseAlpha * 0.7f).toInt().coerceIn(0, 255)
                        blurRevealPaint.setShadowLayer(blurRadius, 0f, 0f, hierarchy.activeColor)
                        for (line in currentLayoutLines) {
                            val drawY = line.baselineY - currentScrollY
                            for (w in line.words) {
                                canvas.drawText(w.word, w.x, drawY, blurRevealPaint)
                            }
                        }
                    }
                } else {
                    blurRevealPaint.clearShadowLayer()
                }
                wordActivePaint.color = hierarchy.activeColor
                wordActivePaint.alpha = incomingAlpha
                for (line in currentLayoutLines) {
                    val drawY = line.baselineY - currentScrollY
                    for (w in line.words) {
                        canvas.drawText(w.word, w.x, drawY, wordActivePaint)
                    }
                }
            }
            "TYPEWRITER" -> {
                wordActivePaint.color = hierarchy.activeColor
                wordActivePaint.alpha = baseAlpha
                val speed = typewriterSpeed.coerceIn(0.3f, 3.0f)
                if (isWordSynced && activeLyricLine?.words?.isNotEmpty() == true) {
                    for (lineIdx in 0 until currentLayoutLines.size) {
                        val line = currentLayoutLines[lineIdx]
                        val drawY = line.baselineY - currentScrollY
                        for (wIdx in 0 until line.words.size) {
                            val w = line.words[wIdx]
                            if (posMs < w.startMs) continue
                            if (posMs >= w.endMs) {
                                canvas.drawText(w.word, w.x, drawY, wordActivePaint)
                            } else {
                                val rawDuration = (w.endMs - w.startMs).coerceAtLeast(1L)
                                val duration = (rawDuration / speed).toLong().coerceAtLeast(1L)
                                val frac = ((posMs - w.startMs).toFloat() / duration).coerceIn(0f, 1f)
                                val graphemesToReveal = (w.graphemeCount * frac).toInt().coerceIn(1, w.graphemeCount)
                                val endIdx = w.graphemeBoundaries.getOrElse(graphemesToReveal - 1) { w.word.length }
                                val sub = w.word.substring(0, endIdx)
                                canvas.drawText(sub, w.x, drawY, wordActivePaint)
                            }
                        }
                    }
                } else {
                    val lineStart = activeLyricLine?.timeMs ?: 0L
                    val rawEnd = activeLyricLine?.endTimeMs ?: 0L
                    val lineEnd = if (rawEnd > lineStart) {
                        rawEnd
                    } else if (nextLyricTimestampMs != null && nextLyricTimestampMs!! > lineStart) {
                        nextLyricTimestampMs!!
                    } else {
                        lineStart + 3500L
                    }
                    val baseDuration = (lineEnd - lineStart).coerceIn(1200L, 4000L)
                    val duration = (baseDuration / speed).toLong().coerceIn(600L, 12000L)

                    val totalGraphemes = totalLayoutGraphemes.coerceAtLeast(1)
                    val revealedGraphemes = if (posMs < lineStart) {
                        if (lineStart == 0L) totalGraphemes else 0
                    } else if (posMs >= lineStart + duration) {
                        totalGraphemes
                    } else {
                        val frac = ((posMs - lineStart).toFloat() / duration).coerceIn(0f, 1f)
                        (totalGraphemes * frac).toInt().coerceIn(0, totalGraphemes)
                    }

                    var graphemesRemaining = revealedGraphemes
                    for (lineIdx in 0 until currentLayoutLines.size) {
                        val line = currentLayoutLines[lineIdx]
                        val drawY = line.baselineY - currentScrollY
                        for (wIdx in 0 until line.words.size) {
                            val w = line.words[wIdx]
                            if (graphemesRemaining <= 0) break
                            if (graphemesRemaining >= w.graphemeCount) {
                                canvas.drawText(w.word, w.x, drawY, wordActivePaint)
                                graphemesRemaining -= w.graphemeCount
                            } else {
                                val endIdx = w.graphemeBoundaries.getOrElse(graphemesRemaining - 1) { w.word.length }
                                val sub = w.word.substring(0, endIdx)
                                canvas.drawText(sub, w.x, drawY, wordActivePaint)
                                graphemesRemaining = 0
                            }
                        }
                    }
                }
            }
            "INSTANT" -> {
                wordActivePaint.color = hierarchy.activeColor
                wordActivePaint.alpha = baseAlpha
                for (line in currentLayoutLines) {
                    val drawY = line.baselineY - currentScrollY
                    for (w in line.words) {
                        canvas.drawText(w.word, w.x, drawY, wordActivePaint)
                    }
                }
            }
        }

        canvas.restore() // Restore horizontal translation
        canvas.restore() // Restore container clipRect

        // 3. EDIT MODE BOUNDING BOX & HANDLES (Only visible during edit mode, hidden during normal playback)
        if (isDragMode) {
            val handleSize = 24f
            // Thin mint outline
            canvas.drawRect(2f, 2f, width - 2f, height - 2f, editBorderPaint)

            // 4 Corner handles
            canvas.drawRect(0f, 0f, handleSize, handleSize, editHandlePaint) // Top-Left
            canvas.drawRect(width - handleSize, 0f, width.toFloat(), handleSize, editHandlePaint) // Top-Right
            canvas.drawRect(0f, height - handleSize, handleSize, height.toFloat(), editHandlePaint) // Bottom-Left
            canvas.drawRect(width - handleSize, height - handleSize, width.toFloat(), height.toFloat(), editHandlePaint) // Bottom-Right

            // Edge handles (pills on borders)
            val edgePillW = 36f
            val edgePillH = 6f
            canvas.drawRect(width / 2f - edgePillW / 2f, 0f, width / 2f + edgePillW / 2f, edgePillH, editHandlePaint) // Top
            canvas.drawRect(width / 2f - edgePillW / 2f, height - edgePillH, width / 2f + edgePillW / 2f, height.toFloat(), editHandlePaint) // Bottom
            canvas.drawRect(0f, height / 2f - edgePillW / 2f, edgePillH, height / 2f + edgePillW / 2f, editHandlePaint) // Left
            canvas.drawRect(width - edgePillH, height / 2f - edgePillW / 2f, width.toFloat(), height / 2f + edgePillW / 2f, editHandlePaint) // Right
        } else if (holdFeedbackProgress > 0.01f) {
            drawHoldRepositionFeedback(canvas)
        }
    }

    private fun drawHoldRepositionFeedback(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val prog = holdFeedbackProgress.coerceIn(0f, 1f)
        val strokePx = (2.25f * density).coerceAtLeast(2f)
        val halfStroke = strokePx / 2f

        val radiusPx: Float
        if (hudBackdropEnabled && hudBackdropOpacity > 0f && drawnBackdropRect.width() > 0f && drawnBackdropRect.height() > 0f) {
            val padOut = 3f * density
            holdFeedbackRect.set(
                (drawnBackdropRect.left - padOut).coerceAtLeast(halfStroke),
                (drawnBackdropRect.top - padOut).coerceAtLeast(halfStroke),
                (drawnBackdropRect.right + padOut).coerceAtMost(width - halfStroke),
                (drawnBackdropRect.bottom + padOut).coerceAtMost(height - halfStroke)
            )
            val maxR = (minOf(holdFeedbackRect.width(), holdFeedbackRect.height()) / 2f).coerceAtLeast(0f)
            radiusPx = (hudBackdropCornerRadiusDp.coerceIn(0f, 32f) * density + padOut).coerceIn(0f, maxR)
        } else {
            val padInset = halfStroke + 2f
            holdFeedbackRect.set(
                padInset,
                padInset,
                (width - padInset).coerceAtLeast(padInset),
                (height - padInset).coerceAtLeast(padInset)
            )
            val maxR = (minOf(holdFeedbackRect.width(), holdFeedbackRect.height()) / 2f).coerceAtLeast(0f)
            radiusPx = (16f * density).coerceIn(0f, maxR)
        }

        // Neutral high-contrast visual indicator: dark ink on Grey backdrop, crisp white ink on Black/text-only HUD
        val useDarkNeutralIndicator = hudBackdropEnabled &&
            hudBackdropOpacity >= 0.35f &&
            hudBackdropStyle == "GREY"
        val primaryNeutral = if (useDarkNeutralIndicator) 0xFF141418.toInt() else Color.WHITE
        val contrastHaloNeutral = if (useDarkNeutralIndicator) Color.WHITE else 0xFF0A0A0A.toInt()

        holdFillPaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(
            primaryNeutral,
            (36f * prog).roundToInt().coerceIn(0, 44)
        )
        canvas.drawRoundRect(holdFeedbackRect, radiusPx, radiusPx, holdFillPaint)

        holdHaloPaint.strokeWidth = strokePx + (2.5f * density)
        holdHaloPaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(
            contrastHaloNeutral,
            (95f * prog).roundToInt().coerceIn(0, 110)
        )
        canvas.drawRoundRect(holdFeedbackRect, radiusPx, radiusPx, holdHaloPaint)

        holdOutlinePaint.strokeWidth = strokePx
        holdOutlinePaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(
            primaryNeutral,
            (235f * prog).roundToInt().coerceIn(0, 245)
        )
        canvas.drawRoundRect(holdFeedbackRect, radiusPx, radiusPx, holdOutlinePaint)

        val dotRadius = (3.5f * density).coerceAtLeast(3f) * prog
        val inset = maxOf(radiusPx * 0.42f, 8f * density)
        holdDotPaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(
            primaryNeutral,
            (235f * prog).roundToInt().coerceIn(0, 245)
        )
        canvas.drawCircle(holdFeedbackRect.left + inset, holdFeedbackRect.top + inset, dotRadius, holdDotPaint)
        canvas.drawCircle(holdFeedbackRect.right - inset, holdFeedbackRect.top + inset, dotRadius, holdDotPaint)
        canvas.drawCircle(holdFeedbackRect.left + inset, holdFeedbackRect.bottom - inset, dotRadius, holdDotPaint)
        canvas.drawCircle(holdFeedbackRect.right - inset, holdFeedbackRect.bottom - inset, dotRadius, holdDotPaint)
    }

    private fun getPointerRawX(event: MotionEvent, pointerIndex: Int): Float {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            event.getRawX(pointerIndex)
        } else {
            event.rawX + (event.getX(pointerIndex) - event.x)
        }
    }

    private fun getPointerRawY(event: MotionEvent, pointerIndex: Int): Float {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            event.getRawY(pointerIndex)
        } else {
            event.rawY + (event.getY(pointerIndex) - event.y)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDragMode) {
            return handleNormalTouchEvent(event)
        }

        val x = event.x
        val y = event.y
        val touchMargin = 40f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelScheduledHoldCallback()
                val orientation = displayGeometry?.orientation ?: activeOrientation
                dragCoordinator.onEditDown(
                    orientation = orientation,
                    rawX = event.rawX,
                    rawY = event.rawY
                )

                activeTouchTarget = when {
                    x <= touchMargin && y <= touchMargin -> TouchTarget.RESIZE_TL
                    x >= width - touchMargin && y <= touchMargin -> TouchTarget.RESIZE_TR
                    x <= touchMargin && y >= height - touchMargin -> TouchTarget.RESIZE_BL
                    x >= width - touchMargin && y >= height - touchMargin -> TouchTarget.RESIZE_BR
                    y <= touchMargin -> TouchTarget.RESIZE_TOP
                    y >= height - touchMargin -> TouchTarget.RESIZE_BOTTOM
                    x <= touchMargin -> TouchTarget.RESIZE_LEFT
                    x >= width - touchMargin -> TouchTarget.RESIZE_RIGHT
                    else -> TouchTarget.MOVE
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val target = activeTouchTarget
                if (target == TouchTarget.NONE) return false
                return dragCoordinator.onEditMove(event.rawX, event.rawY) { initialBounds, dx, dy ->
                    computeEditMoveBounds(initialBounds, dx, dy, target)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (activeTouchTarget != TouchTarget.NONE) {
                    dragCoordinator.onEditUp()
                }
                activeTouchTarget = TouchTarget.NONE
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelActiveDrag()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun computeEditMoveBounds(
        initialBounds: HudWindowBounds,
        dx: Int,
        dy: Int,
        target: TouchTarget
    ): HudWindowBounds {
        val initialX = initialBounds.x
        val initialY = initialBounds.y
        val initialW = initialBounds.width
        val initialH = initialBounds.height

        val geom = displayGeometry
        val constraints = geom?.let {
            HudPositioningEngine.computeConstraints(
                geometry = it,
                currentWidthPx = initialW,
                currentEffectiveHeightPx = initialH,
                minBackdropHeightPx = minBackdropHeightPx
            )
        }
        val minW = constraints?.minWidthPx ?: 280
        val maxW = constraints?.maxWidthPx ?: 2000
        val minH = constraints?.minHeightPx ?: 60
        val maxH = constraints?.maxHeightPx ?: 800
        val minX = constraints?.minX ?: -2000
        val maxRight = constraints?.minSafeRightPx ?: Int.MAX_VALUE
        val minY = constraints?.minY ?: 0
        val maxBottom = constraints?.maxSafeBottomPx ?: Int.MAX_VALUE

        var targetX = initialX
        var targetY = initialY
        var targetW = initialW
        var targetH = initialH

        when (target) {
            TouchTarget.MOVE -> {
                val moveMaxX = if (maxRight < Int.MAX_VALUE) (maxRight - initialW).coerceAtLeast(minX) else 2000
                val moveMaxY = if (maxBottom < Int.MAX_VALUE) (maxBottom - initialH).coerceAtLeast(minY) else 3000
                targetX = (initialX + dx).coerceIn(minX, moveMaxX)
                targetY = (initialY + dy).coerceIn(minY, moveMaxY)
            }
            TouchTarget.RESIZE_RIGHT -> {
                val availMaxW = if (maxRight < Int.MAX_VALUE) (maxRight - initialX).coerceIn(minW, maxW) else maxW
                targetW = (initialW + dx).coerceIn(minW, availMaxW)
            }
            TouchTarget.RESIZE_BOTTOM -> {
                val availMaxH = if (maxBottom < Int.MAX_VALUE) (maxBottom - initialY).coerceIn(minH, maxH) else maxH
                targetH = (initialH + dy).coerceIn(minH, availMaxH)
            }
            TouchTarget.RESIZE_BR -> {
                val availMaxW = if (maxRight < Int.MAX_VALUE) (maxRight - initialX).coerceIn(minW, maxW) else maxW
                val availMaxH = if (maxBottom < Int.MAX_VALUE) (maxBottom - initialY).coerceIn(minH, maxH) else maxH
                targetW = (initialW + dx).coerceIn(minW, availMaxW)
                targetH = (initialH + dy).coerceIn(minH, availMaxH)
            }
            TouchTarget.RESIZE_LEFT -> {
                val anchorRight = initialX + initialW
                val availMaxW = (anchorRight - minX).coerceIn(minW, maxW)
                val newW = (initialW - dx).coerceIn(minW, availMaxW)
                targetW = newW
                targetX = (anchorRight - newW).coerceIn(minX, (maxRight - newW).coerceAtLeast(minX))
            }
            TouchTarget.RESIZE_TOP -> {
                val anchorBottom = initialY + initialH
                val availMaxH = (anchorBottom - minY).coerceIn(minH, maxH)
                val newH = (initialH - dy).coerceIn(minH, availMaxH)
                targetH = newH
                targetY = (anchorBottom - newH).coerceIn(minY, (maxBottom - newH).coerceAtLeast(minY))
            }
            TouchTarget.RESIZE_TL -> {
                val anchorRight = initialX + initialW
                val anchorBottom = initialY + initialH
                val availMaxW = (anchorRight - minX).coerceIn(minW, maxW)
                val availMaxH = (anchorBottom - minY).coerceIn(minH, maxH)
                val newW = (initialW - dx).coerceIn(minW, availMaxW)
                val newH = (initialH - dy).coerceIn(minH, availMaxH)
                targetW = newW
                targetH = newH
                targetX = (anchorRight - newW).coerceIn(minX, (maxRight - newW).coerceAtLeast(minX))
                targetY = (anchorBottom - newH).coerceIn(minY, (maxBottom - newH).coerceAtLeast(minY))
            }
            TouchTarget.RESIZE_TR -> {
                val anchorBottom = initialY + initialH
                val availMaxW = if (maxRight < Int.MAX_VALUE) (maxRight - initialX).coerceIn(minW, maxW) else maxW
                val availMaxH = (anchorBottom - minY).coerceIn(minH, maxH)
                val newW = (initialW + dx).coerceIn(minW, availMaxW)
                val newH = (initialH - dy).coerceIn(minH, availMaxH)
                targetW = newW
                targetH = newH
                targetY = (anchorBottom - newH).coerceIn(minY, (maxBottom - newH).coerceAtLeast(minY))
            }
            TouchTarget.RESIZE_BL -> {
                val anchorRight = initialX + initialW
                val availMaxW = (anchorRight - minX).coerceIn(minW, maxW)
                val availMaxH = if (maxBottom < Int.MAX_VALUE) (maxBottom - initialY).coerceIn(minH, maxH) else maxH
                val newW = (initialW - dx).coerceIn(minW, availMaxW)
                val newH = (initialH + dy).coerceIn(minH, availMaxH)
                targetW = newW
                targetH = newH
                targetX = (anchorRight - newW).coerceIn(minX, (maxRight - newW).coerceAtLeast(minX))
            }
            TouchTarget.NONE -> {}
        }

        return HudWindowBounds(targetX, targetY, targetW, targetH)
    }

    private fun computeNormalMoveBounds(
        initialBounds: HudWindowBounds,
        dx: Int,
        dy: Int
    ): HudWindowBounds {
        val initialX = initialBounds.x
        val initialY = initialBounds.y
        val initialW = initialBounds.width
        val initialH = initialBounds.height

        val geom = displayGeometry
        val constraints = geom?.let {
            HudPositioningEngine.computeConstraints(
                geometry = it,
                currentWidthPx = initialW,
                currentEffectiveHeightPx = initialH,
                minBackdropHeightPx = minBackdropHeightPx
            )
        }
        val minX = constraints?.minX ?: -2000
        val maxRight = constraints?.minSafeRightPx ?: Int.MAX_VALUE
        val minY = constraints?.minY ?: 0
        val maxBottom = constraints?.maxSafeBottomPx ?: Int.MAX_VALUE
        val moveMaxX = if (maxRight < Int.MAX_VALUE) (maxRight - initialW).coerceAtLeast(minX) else 2000
        val moveMaxY = if (maxBottom < Int.MAX_VALUE) (maxBottom - initialH).coerceAtLeast(minY) else 3000

        val targetX = (initialX + dx).coerceIn(minX, moveMaxX)
        val targetY = (initialY + dy).coerceIn(minY, moveMaxY)
        return HudWindowBounds(targetX, targetY, initialW, initialH)
    }

    private fun handleNormalTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelScheduledHoldCallback()
                val pointerId = event.getPointerId(0)
                val onControl = isPointOnInteractiveControl?.invoke(event.x, event.y) == true
                val started = dragCoordinator.onNormalDown(
                    rawX = getPointerRawX(event, 0),
                    rawY = getPointerRawY(event, 0),
                    pointerId = pointerId,
                    isOnInteractiveControl = onControl
                )
                if (started) {
                    scheduledHoldToken = holdAttemptToken
                    holdHandler.postDelayed(holdTimeoutRunnable, holdGestureController.holdDurationMs)
                }
                return started
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (dragCoordinator.onNormalMultiPointerDown()) {
                    cancelScheduledHoldCallback()
                }
                return holdGestureController.state != HudHoldGestureController.State.IDLE
            }

            MotionEvent.ACTION_MOVE -> {
                val ptrIndex = event.findPointerIndex(holdGestureController.activePointerId)
                if (ptrIndex < 0) return false
                val rawX = getPointerRawX(event, ptrIndex)
                val rawY = getPointerRawY(event, ptrIndex)

                return when (holdGestureController.state) {
                    HudHoldGestureController.State.PENDING_HOLD -> {
                        if (dragCoordinator.onNormalMovePending(rawX, rawY)) {
                            cancelScheduledHoldCallback()
                        }
                        true
                    }
                    HudHoldGestureController.State.ACTIVE_REPOSITION -> {
                        dragCoordinator.onNormalDragMove(rawX, rawY, ::computeNormalMoveBounds)
                        true
                    }
                    HudHoldGestureController.State.CANCELLED_BEFORE_HOLD -> true
                    HudHoldGestureController.State.IDLE -> false
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val liftedIndex = event.actionIndex
                val liftedId = event.getPointerId(liftedIndex)
                if (liftedId == holdGestureController.activePointerId) {
                    finishHoldPointerUp()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                finishHoldPointerUp()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelActiveDrag()
                return true
            }
        }
        return false
    }

    private fun finishHoldPointerUp() {
        cancelScheduledHoldCallback()
        when (dragCoordinator.onNormalUp()) {
            HudHoldGestureController.UpResult.TRIGGER_TAP -> Unit
            HudHoldGestureController.UpResult.COMMIT_REPOSITION -> {
                animateHoldFeedback(active = false)
            }
            HudHoldGestureController.UpResult.NONE -> {
                if (holdFeedbackProgress > 0f) {
                    animateHoldFeedback(active = false)
                }
            }
        }
    }

    private fun readCurrentWindowBounds(): HudWindowBounds {
        val w = if (layoutParams.width > 0) layoutParams.width else width
        val h = if (layoutParams.height > 0) layoutParams.height else height
        return HudWindowBounds(
            x = layoutParams.x,
            y = layoutParams.y,
            width = w,
            height = h
        )
    }

    private fun applyDragWindowBounds(bounds: HudWindowBounds) {
        val prevW = layoutParams.width
        val prevH = layoutParams.height
        applyUpdatedWindowBounds(bounds.x, bounds.y, bounds.width, bounds.height)
        if (layoutParams.width != prevW || layoutParams.height != prevH) {
            recalculateLayout()
        }
    }

    private fun restoreRolledBackBounds(snapshotBounds: HudWindowBounds) {
        val prevW = layoutParams.width
        val prevH = layoutParams.height
        layoutParams.x = snapshotBounds.x
        layoutParams.y = snapshotBounds.y
        if (snapshotBounds.width > 0) {
            layoutParams.width = snapshotBounds.width
        }
        if (snapshotBounds.height > 0) {
            layoutParams.height = snapshotBounds.height
        }
        if (layoutParams.width != prevW || layoutParams.height != prevH) {
            recalculateLayout()
        }
        if (isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(this, layoutParams)
            } catch (_: Exception) {}
        }
        invalidate()
    }

    private fun applyUpdatedWindowBounds(targetX: Int, targetY: Int, targetW: Int, targetH: Int) {
        val geom = displayGeometry
        if (geom != null) {
            val safeResolved = HudPositioningEngine.resolveBounds(
                profile = HudOverlayProfile(targetX, targetY, targetW, targetH),
                geometry = geom,
                minBackdropHeightPx = minBackdropHeightPx
            )
            layoutParams.x = safeResolved.x
            layoutParams.y = safeResolved.y
            layoutParams.width = safeResolved.widthPx
            layoutParams.height = safeResolved.effectiveHeightPx
        } else {
            layoutParams.x = targetX
            layoutParams.y = targetY
            layoutParams.width = targetW
            layoutParams.height = targetH
        }

        if (isAttachedToWindow) {
            try {
                windowManager.updateViewLayout(this, layoutParams)
            } catch (_: Exception) {}
        }
    }

    companion object {
        const val BLACK_BACKDROP_COLOR: Int = HudColorSystem.BLACK_BACKDROP_COLOR
        const val GREY_BACKDROP_COLOR: Int = HudColorSystem.GREY_BACKDROP_COLOR

        const val ACTIVE_LYRIC_COLOR: Int = HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR
        const val INACTIVE_LYRIC_COLOR: Int = HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR
        const val INACTIVE_LYRIC_ALPHA_RATIO: Float = 0.64f
        const val OUTGOING_LYRIC_ALPHA_RATIO: Float = 0.44f
        const val ACTIVE_SHADOW_ALPHA: Int = 215
        const val INACTIVE_SHADOW_ALPHA: Int = 95
        const val ACTIVE_GLOW_ALPHA: Int = 140

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
}

class HudHoldGestureController(
    private val touchSlopPx: Float,
    val holdDurationMs: Long = HOLD_REPOSITION_DELAY_MS
) {
    enum class State {
        IDLE,
        PENDING_HOLD,
        CANCELLED_BEFORE_HOLD,
        ACTIVE_REPOSITION
    }

    enum class UpResult {
        NONE,
        TRIGGER_TAP,
        COMMIT_REPOSITION
    }

    var state: State = State.IDLE
        private set
    var activePointerId: Int = INVALID_POINTER_ID
        private set
    var downRawX: Float = 0f
        private set
    var downRawY: Float = 0f
        private set
    var activationRawX: Float = 0f
        private set
    var activationRawY: Float = 0f
        private set

    fun onDown(
        rawX: Float,
        rawY: Float,
        pointerId: Int,
        isOnInteractiveControl: Boolean = false
    ): Boolean {
        if (isOnInteractiveControl) {
            reset()
            return false
        }
        activePointerId = pointerId
        downRawX = rawX
        downRawY = rawY
        activationRawX = rawX
        activationRawY = rawY
        state = State.PENDING_HOLD
        return true
    }

    /** Returns true if finger movement beyond touch slop cancelled a pending hold. */
    fun onMove(rawX: Float, rawY: Float): Boolean {
        return when (state) {
            State.PENDING_HOLD -> {
                activationRawX = rawX
                activationRawY = rawY
                val dist = hypot(rawX - downRawX, rawY - downRawY)
                if (dist > touchSlopPx) {
                    state = State.CANCELLED_BEFORE_HOLD
                    true
                } else {
                    false
                }
            }
            State.ACTIVE_REPOSITION,
            State.IDLE,
            State.CANCELLED_BEFORE_HOLD -> false
        }
    }

    fun onHoldTimeout(elapsedMs: Long = holdDurationMs): Boolean {
        if (state != State.PENDING_HOLD) return false
        if (elapsedMs < holdDurationMs) return false
        state = State.ACTIVE_REPOSITION
        return true
    }

    /** Returns true if a secondary pointer cancelled a pending hold. */
    fun onMultiPointerDown(): Boolean {
        if (state == State.PENDING_HOLD) {
            state = State.CANCELLED_BEFORE_HOLD
            return true
        }
        return false
    }

    fun onUp(): UpResult {
        val result = when (state) {
            State.PENDING_HOLD -> UpResult.TRIGGER_TAP
            State.ACTIVE_REPOSITION -> UpResult.COMMIT_REPOSITION
            State.IDLE, State.CANCELLED_BEFORE_HOLD -> UpResult.NONE
        }
        reset()
        return result
    }

    /** Returns true if an active reposition was interrupted. */
    fun onCancel(): Boolean {
        val wasActive = state == State.ACTIVE_REPOSITION
        reset()
        return wasActive
    }

    fun reset() {
        state = State.IDLE
        activePointerId = INVALID_POINTER_ID
        downRawX = 0f
        downRawY = 0f
        activationRawX = 0f
        activationRawY = 0f
    }

    companion object {
        const val HOLD_REPOSITION_DELAY_MS = 1000L
        const val HOLD_REPOSITION_SECONDS = (HOLD_REPOSITION_DELAY_MS / 1000L).toInt()
        const val INVALID_POINTER_ID = -1
    }
}

data class HudWindowBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

data class HudDragSnapshot(
    val orientation: HudOrientation,
    val initialBounds: HudWindowBounds
)

class HudDragTransactionCoordinator(
    val holdController: HudHoldGestureController,
    private val readBounds: () -> HudWindowBounds,
    private val applyDragBounds: (HudWindowBounds) -> Unit,
    private val restoreBounds: (HudWindowBounds) -> Unit,
    private val commitBounds: (HudOrientation, HudWindowBounds) -> Unit,
    private val onTapAction: () -> Unit = {}
) {
    var activeSnapshot: HudDragSnapshot? = null
        private set
    var isEditDragActive: Boolean = false
        private set
    var initialTouchX: Float = 0f
        private set
    var initialTouchY: Float = 0f
        private set

    val isTransactionActive: Boolean
        get() = activeSnapshot != null &&
            (isEditDragActive || holdController.state == HudHoldGestureController.State.ACTIVE_REPOSITION)

    fun onNormalDown(
        rawX: Float,
        rawY: Float,
        pointerId: Int,
        isOnInteractiveControl: Boolean = false
    ): Boolean {
        if (isTransactionActive) {
            cancelActiveDrag()
        }
        return holdController.onDown(
            rawX = rawX,
            rawY = rawY,
            pointerId = pointerId,
            isOnInteractiveControl = isOnInteractiveControl
        )
    }

    fun onNormalMultiPointerDown(): Boolean = holdController.onMultiPointerDown()

    fun onNormalMovePending(rawX: Float, rawY: Float): Boolean = holdController.onMove(rawX, rawY)

    fun onHoldTimeout(
        orientation: HudOrientation,
        elapsedMs: Long = holdController.holdDurationMs
    ): Boolean {
        if (!holdController.onHoldTimeout(elapsedMs)) {
            return false
        }
        initialTouchX = holdController.activationRawX
        initialTouchY = holdController.activationRawY
        activeSnapshot = HudDragSnapshot(
            orientation = orientation,
            initialBounds = readBounds()
        )
        return true
    }

    fun onNormalDragMove(
        rawX: Float,
        rawY: Float,
        computeTarget: (initialBounds: HudWindowBounds, dx: Int, dy: Int) -> HudWindowBounds
    ): Boolean {
        if (holdController.state != HudHoldGestureController.State.ACTIVE_REPOSITION) {
            return false
        }
        val snapshot = activeSnapshot ?: return false
        val dx = (rawX - initialTouchX).toInt()
        val dy = (rawY - initialTouchY).toInt()
        applyDragBounds(computeTarget(snapshot.initialBounds, dx, dy))
        return true
    }

    fun onNormalUp(): HudHoldGestureController.UpResult {
        val result = holdController.onUp()
        when (result) {
            HudHoldGestureController.UpResult.TRIGGER_TAP -> {
                activeSnapshot = null
                onTapAction()
            }
            HudHoldGestureController.UpResult.COMMIT_REPOSITION -> {
                val snapshot = activeSnapshot
                activeSnapshot = null
                if (snapshot != null) {
                    commitBounds(snapshot.orientation, readBounds())
                }
            }
            HudHoldGestureController.UpResult.NONE -> {
                activeSnapshot = null
            }
        }
        return result
    }

    fun onEditDown(orientation: HudOrientation, rawX: Float, rawY: Float): HudDragSnapshot {
        if (isTransactionActive || holdController.state != HudHoldGestureController.State.IDLE) {
            cancelActiveDrag()
        }
        isEditDragActive = true
        initialTouchX = rawX
        initialTouchY = rawY
        val snapshot = HudDragSnapshot(
            orientation = orientation,
            initialBounds = readBounds()
        )
        activeSnapshot = snapshot
        return snapshot
    }

    fun onEditMove(
        rawX: Float,
        rawY: Float,
        computeTarget: (initialBounds: HudWindowBounds, dx: Int, dy: Int) -> HudWindowBounds
    ): Boolean {
        if (!isEditDragActive) return false
        val snapshot = activeSnapshot ?: return false
        val dx = (rawX - initialTouchX).toInt()
        val dy = (rawY - initialTouchY).toInt()
        applyDragBounds(computeTarget(snapshot.initialBounds, dx, dy))
        return true
    }

    fun onEditUp(): Boolean {
        if (!isEditDragActive) return false
        isEditDragActive = false
        val snapshot = activeSnapshot
        activeSnapshot = null
        if (snapshot != null) {
            commitBounds(snapshot.orientation, readBounds())
        }
        return true
    }

    /**
     * Cancels any pending or active drag gesture.
     * Returns true if an active drag transaction was rolled back to its original bounds.
     */
    fun cancelActiveDrag(): Boolean {
        val wasHoldActive = holdController.onCancel()
        val wasEditActive = isEditDragActive
        isEditDragActive = false
        val snapshot = activeSnapshot
        activeSnapshot = null

        val shouldRollback = (wasHoldActive || wasEditActive) && snapshot != null
        if (shouldRollback) {
            restoreBounds(snapshot!!.initialBounds)
        }
        return shouldRollback
    }
}
