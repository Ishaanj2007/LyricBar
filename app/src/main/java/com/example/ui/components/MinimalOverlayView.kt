package com.example.ui.components

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
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
        color = ACTIVE_LYRIC_COLOR
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    private val wordInactivePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INACTIVE_LYRIC_COLOR
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    private val previousTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INACTIVE_LYRIC_COLOR
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    private val wordGlowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACTIVE_LYRIC_COLOR
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        setShadowLayer(10f, 0f, 0f, Color.argb(ACTIVE_GLOW_ALPHA, 255, 255, 255))
    }

    private val blurRevealPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACTIVE_LYRIC_COLOR
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    // Editable bounding box paints (displayed ONLY when isDragMode == true)
    private val editBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = androidx.core.graphics.ColorUtils.setAlphaComponent(Color.WHITE, 160)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val editHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = androidx.core.graphics.ColorUtils.setAlphaComponent(Color.WHITE, 220)
        style = Paint.Style.FILL
    }

    // Pre-allocated HUD Backdrop paints & RectF buffers (zero object allocations in onDraw)
    private val backdropGlassShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
    }

    private val backdropFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = SOLID_BACKDROP_COLOR
    }

    private val backdropGlassSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val backdropGlassInnerRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }

    private val backdropBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            1f,
            resources.displayMetrics
        )
    }

    // Subtle hold-to-reposition feedback paints (pre-allocated)
    private val holdHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
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

    // Cached Glass shader keys to prevent per-frame shader allocations
    private var cachedGlassTop = Float.NaN
    private var cachedGlassBottom = Float.NaN
    private var cachedGlassSheenAlpha = -1
    private var cachedGlassBorderTop = Float.NaN
    private var cachedGlassBorderBottom = Float.NaN
    private var cachedGlassBorderAlpha = -1
    private var cachedGlassInnerTop = Float.NaN
    private var cachedGlassInnerBottom = Float.NaN
    private var cachedGlassInnerAlpha = -1

    private val currentBackdropRect = android.graphics.RectF()
    private val previousBackdropRect = android.graphics.RectF()
    private val drawnBackdropRect = android.graphics.RectF()
    private val shadowBackdropRect = android.graphics.RectF()
    private val innerRimBackdropRect = android.graphics.RectF()
    private val borderBackdropRect = android.graphics.RectF()
    private val holdFeedbackRect = android.graphics.RectF()

    var primaryColor: Int = Color.WHITE
        set(value) {
            field = value
            editBorderPaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(value, 160)
            editHandlePaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(value, 220)
            invalidate()
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

    var hudBackdropStyle: String = "SOLID"
        set(value) {
            val normalized = if (value.trim().equals("GLASS", ignoreCase = true)) "GLASS" else "SOLID"
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
            updateShadow(wordActivePaint, value, isSubordinate = false)
            updateShadow(wordInactivePaint, value, isSubordinate = true)
            updateShadow(previousTextPaint, value, isSubordinate = true)
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
                field = value
                cancelHoldGesture(revertActivePosition = false)
                if (!value) {
                    activeTouchTarget = TouchTarget.NONE
                }
                invalidate()
            }
        }

    var activeOrientation: HudOrientation = HudOrientation.PORTRAIT
    var displayGeometry: HudDisplayGeometry? = null
    var minBackdropHeightPx: Int = 0
    var isPointOnInteractiveControl: ((x: Float, y: Float) -> Boolean)? = null

    // Touch and drag/resize tracking
    private enum class TouchTarget { NONE, MOVE, RESIZE_TOP, RESIZE_BOTTOM, RESIZE_LEFT, RESIZE_RIGHT, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }
    private var activeTouchTarget = TouchTarget.NONE
    private var dragOrientation: HudOrientation = HudOrientation.PORTRAIT
    private var initialX = 0
    private var initialY = 0
    private var initialW = 0
    private var initialH = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    // Two-second hold-to-reposition controller & subtle visual/haptic feedback
    // Use generous stationary tolerance for a 2000ms finger hold while still cancelling deliberate slides
    private val touchSlopPx = maxOf(
        ViewConfiguration.get(context).scaledTouchSlop * 2.5f,
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics)
    )
    private val holdGestureController = HudHoldGestureController(touchSlopPx = touchSlopPx)
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
            holdGestureController.reset()
            return@Runnable
        }
        if (holdGestureController.onHoldTimeout()) {
            dragOrientation = displayGeometry?.orientation ?: activeOrientation
            initialX = layoutParams.x
            initialY = layoutParams.y
            initialW = if (layoutParams.width > 0) layoutParams.width else width
            initialH = if (layoutParams.height > 0) layoutParams.height else height
            initialTouchX = holdGestureController.activationRawX
            initialTouchY = holdGestureController.activationRawY

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
        if (abs(holdFeedbackProgress - target) < 0.01f) {
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

    private fun cancelHoldGesture(revertActivePosition: Boolean) {
        cancelScheduledHoldCallback()
        val wasHoldActive = holdGestureController.onCancel()
        if (wasHoldActive && revertActivePosition && isAttachedToWindow) {
            layoutParams.x = initialX
            layoutParams.y = initialY
            try {
                windowManager.updateViewLayout(this, layoutParams)
            } catch (_: Exception) {}
        }
        if (wasHoldActive || holdFeedbackProgress > 0f) {
            animateHoldFeedback(active = false)
        }
    }

    fun cancelActiveDrag() {
        activeTouchTarget = TouchTarget.NONE
        cancelHoldGesture(revertActivePosition = false)
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
        updateShadow(wordActivePaint, textShadowEnabled, isSubordinate = false)
        updateShadow(wordInactivePaint, textShadowEnabled, isSubordinate = true)
        updateShadow(previousTextPaint, textShadowEnabled, isSubordinate = true)
    }

    private fun updateShadow(paint: Paint, enabled: Boolean, isSubordinate: Boolean = false) {
        if (enabled) {
            if (isSubordinate) {
                paint.setShadowLayer(3.5f, 0f, 1.5f, Color.argb(INACTIVE_SHADOW_ALPHA, 0, 0, 0))
            } else {
                paint.setShadowLayer(5.5f, 0f, 1.5f, Color.argb(ACTIVE_SHADOW_ALPHA, 0, 0, 0))
            }
        } else {
            paint.clearShadowLayer()
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

        if (appearance.isGlass && appearance.glassElevationShadowAlpha > 0) {
            val shadowStrokePx = (1.5f * density).coerceAtLeast(1.25f)
            val shadowOffset = shadowStrokePx / 2f
            shadowBackdropRect.set(
                (drawnBackdropRect.left - shadowOffset).coerceAtLeast(0f),
                (drawnBackdropRect.top - shadowOffset * 0.5f).coerceAtLeast(0f),
                (drawnBackdropRect.right + shadowOffset).coerceAtMost(width.toFloat()),
                (drawnBackdropRect.bottom + shadowOffset).coerceAtMost(height.toFloat())
            )
            backdropGlassShadowPaint.strokeWidth = shadowStrokePx
            backdropGlassShadowPaint.alpha = appearance.glassElevationShadowAlpha
            canvas.drawRoundRect(
                shadowBackdropRect,
                (cornerRadiusPx + shadowOffset).coerceAtLeast(0f),
                (cornerRadiusPx + shadowOffset).coerceAtLeast(0f),
                backdropGlassShadowPaint
            )
        }

        backdropFillPaint.color = appearance.fillColor
        backdropFillPaint.alpha = appearance.fillAlpha
        canvas.drawRoundRect(
            drawnBackdropRect,
            cornerRadiusPx,
            cornerRadiusPx,
            backdropFillPaint
        )

        if (appearance.isGlass) {
            val sheenAlpha = appearance.glassSheenAlpha
            if (sheenAlpha > 0) {
                if (cachedGlassTop != drawnBackdropRect.top ||
                    cachedGlassBottom != drawnBackdropRect.bottom ||
                    cachedGlassSheenAlpha != sheenAlpha ||
                    backdropGlassSheenPaint.shader == null
                ) {
                    cachedGlassTop = drawnBackdropRect.top
                    cachedGlassBottom = drawnBackdropRect.bottom
                    cachedGlassSheenAlpha = sheenAlpha
                    backdropGlassSheenPaint.shader = LinearGradient(
                        0f,
                        drawnBackdropRect.top,
                        0f,
                        drawnBackdropRect.bottom.coerceAtLeast(drawnBackdropRect.top + 1f),
                        buildGlassFrostGradientColors(sheenAlpha),
                        GLASS_FROST_STOPS,
                        Shader.TileMode.CLAMP
                    )
                }
                backdropGlassSheenPaint.alpha = 255
                canvas.drawRoundRect(
                    drawnBackdropRect,
                    cornerRadiusPx,
                    cornerRadiusPx,
                    backdropGlassSheenPaint
                )
            }

            val borderWidthPx = (1f * density).coerceAtLeast(1f)
            val innerAlpha = appearance.glassInnerHighlightAlpha
            if (innerAlpha > 0 && drawnBackdropRect.height() > borderWidthPx * 4f) {
                val innerInset = borderWidthPx + (0.65f * density).coerceAtLeast(0.75f)
                innerRimBackdropRect.set(
                    drawnBackdropRect.left + innerInset,
                    drawnBackdropRect.top + innerInset,
                    (drawnBackdropRect.right - innerInset).coerceAtLeast(drawnBackdropRect.left + innerInset),
                    (drawnBackdropRect.bottom - innerInset).coerceAtLeast(drawnBackdropRect.top + innerInset)
                )
                val innerRadius = (cornerRadiusPx - innerInset).coerceAtLeast(0f)
                if (cachedGlassInnerTop != innerRimBackdropRect.top ||
                    cachedGlassInnerBottom != innerRimBackdropRect.bottom ||
                    cachedGlassInnerAlpha != innerAlpha ||
                    backdropGlassInnerRimPaint.shader == null
                ) {
                    cachedGlassInnerTop = innerRimBackdropRect.top
                    cachedGlassInnerBottom = innerRimBackdropRect.bottom
                    cachedGlassInnerAlpha = innerAlpha
                    backdropGlassInnerRimPaint.shader = LinearGradient(
                        0f,
                        innerRimBackdropRect.top,
                        0f,
                        innerRimBackdropRect.bottom.coerceAtLeast(innerRimBackdropRect.top + 1f),
                        buildGlassInnerHighlightColors(innerAlpha),
                        GLASS_INNER_HIGHLIGHT_STOPS,
                        Shader.TileMode.CLAMP
                    )
                }
                backdropGlassInnerRimPaint.strokeWidth = (0.75f * density).coerceAtLeast(1f)
                backdropGlassInnerRimPaint.alpha = 255
                canvas.drawRoundRect(
                    innerRimBackdropRect,
                    innerRadius,
                    innerRadius,
                    backdropGlassInnerRimPaint
                )
            }

            val borderAlpha = appearance.borderAlpha
            if (borderAlpha > 0) {
                val halfStroke = borderWidthPx / 2f
                borderBackdropRect.set(
                    drawnBackdropRect.left + halfStroke,
                    drawnBackdropRect.top + halfStroke,
                    drawnBackdropRect.right - halfStroke,
                    drawnBackdropRect.bottom - halfStroke
                )
                val borderRadius = (cornerRadiusPx - halfStroke).coerceAtLeast(0f)
                if (cachedGlassBorderTop != borderBackdropRect.top ||
                    cachedGlassBorderBottom != borderBackdropRect.bottom ||
                    cachedGlassBorderAlpha != borderAlpha ||
                    backdropBorderPaint.shader == null
                ) {
                    cachedGlassBorderTop = borderBackdropRect.top
                    cachedGlassBorderBottom = borderBackdropRect.bottom
                    cachedGlassBorderAlpha = borderAlpha
                    backdropBorderPaint.shader = LinearGradient(
                        0f,
                        borderBackdropRect.top,
                        0f,
                        borderBackdropRect.bottom.coerceAtLeast(borderBackdropRect.top + 1f),
                        buildGlassBorderGradientColors(borderAlpha),
                        GLASS_BORDER_STOPS,
                        Shader.TileMode.CLAMP
                    )
                }
                backdropBorderPaint.strokeWidth = borderWidthPx
                backdropBorderPaint.alpha = 255
                canvas.drawRoundRect(
                    borderBackdropRect,
                    borderRadius,
                    borderRadius,
                    backdropBorderPaint
                )
            }
        } else {
            backdropGlassSheenPaint.shader = null
            backdropGlassInnerRimPaint.shader = null
            backdropBorderPaint.shader = null
        }
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

        // 1. Unified color & alpha hierarchy across active, subordinate, and outgoing lines
        val isFadeStyle = currentStyle == "FADE" || currentStyle == "BLUR_REVEAL"
        val isAnimatedTransition = currentStyle == "KARAOKE" || currentStyle == "SMOOTH_SLIDE" || isFadeStyle
        val hierarchy = computeLyricColorHierarchy(
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
            previousTextPaint.color = INACTIVE_LYRIC_COLOR
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

        when (currentStyle) {
            "KARAOKE" -> {
                if (isWordSynced && activeLyricLine?.words?.isNotEmpty() == true) {
                    // 1. Word-synchronized lyrics: crisp active highlight + readable soft-white un-sung words
                    wordInactivePaint.color = INACTIVE_LYRIC_COLOR
                    wordInactivePaint.alpha = inactiveAlpha
                    wordActivePaint.color = ACTIVE_LYRIC_COLOR
                    wordActivePaint.alpha = incomingAlpha
                    wordGlowPaint.color = ACTIVE_LYRIC_COLOR
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
                                    // Word not sung yet on active line: draw with low opacity below
                                    canvas.drawText(w.word, w.x, drawY, wordInactivePaint)
                                } else if (posMs >= w.endMs) {
                                    // Word fully sung on active line: draw with glowing high-opacity active text
                                    canvas.drawText(w.word, w.x, drawY, wordGlowPaint)
                                    canvas.drawText(w.word, w.x, drawY, wordActivePaint)
                                } else {
                                    // Word actively being sung on active line: progressive fill from left to right
                                    val progress = com.example.domain.lyricsengine.LyricsTimingEngine.calculateWordGlowProgress(
                                        startMs = w.startMs,
                                        endMs = w.endMs,
                                        currentPositionMs = posMs,
                                        speedMultiplier = karaokeGlowSpeed
                                    )

                                    // Low opacity below
                                    canvas.drawText(w.word, w.x, drawY, wordInactivePaint)

                                    // High-opacity glowing active text clipped progressively left to right
                                    val splitX = w.x + (w.width * progress)
                                    canvas.save()
                                    canvas.clipRect(w.x, 0f, splitX, height.toFloat())
                                    canvas.drawText(w.word, w.x, drawY, wordGlowPaint)
                                    canvas.drawText(w.word, w.x, drawY, wordActivePaint)
                                    canvas.restore()
                                }
                            }
                        }
                    }
                } else if (activeLyricLine?.timeMs == 0L && activeLyricLine?.endTimeMs == 0L && !isWordSynced) {
                    // 2. Untimed / plain lyrics fallback: display clearly as readable static text without fake karaoke sweep
                    wordActivePaint.alpha = incomingAlpha
                    for (line in currentLayoutLines) {
                        val drawY = line.baselineY - currentScrollY + incomingOffsetY
                        for (w in line.words) {
                            canvas.drawText(w.word, w.x, drawY, wordActivePaint)
                        }
                    }
                } else {
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

                    // A: Always display ALL lines with clean soft-white subordinate alpha (never muddy dark grey)
                    wordInactivePaint.color = INACTIVE_LYRIC_COLOR
                    wordInactivePaint.alpha = inactiveAlpha
                    for (line in currentLayoutLines) {
                        val drawY = line.baselineY - currentScrollY + incomingOffsetY
                        for (w in line.words) {
                            canvas.drawText(w.word, w.x, drawY, wordInactivePaint)
                        }
                    }

                    // B: Show high-opacity glowing text above that sweeps across the text
                    // Visual lines that have already been sung remain fully glowing and visible
                    if (currentLayoutLines.isNotEmpty()) {
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

                        wordActivePaint.alpha = incomingAlpha
                        wordGlowPaint.alpha = incomingAlpha

                        // Draw prior completed visual lines (0 until activeLineIdx) fully highlighted and glowing
                        for (prevIdx in 0 until activeLineIdx) {
                            val prevLine = currentLayoutLines[prevIdx]
                            val drawY = prevLine.baselineY - currentScrollY + incomingOffsetY
                            for (wIdx in 0 until prevLine.words.size) {
                                val w = prevLine.words[wIdx]
                                canvas.drawText(w.word, w.x, drawY, wordGlowPaint)
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

                        if (lineProgress > 0f) {
                            val activeLine = currentLayoutLines[activeLineIdx]
                            val lineLeft = activeLine.words.firstOrNull()?.x ?: paddingHorizontal
                            val lineRight = (activeLine.words.lastOrNull()?.let { it.x + it.width }) ?: (width - paddingHorizontal)
                            val sweepX = lineLeft + ((lineRight - lineLeft) * lineProgress)
                            val drawY = activeLine.baselineY - currentScrollY + incomingOffsetY

                            canvas.save()
                            canvas.clipRect(0f, 0f, sweepX, height.toFloat())
                            for (wIdx in 0 until activeLine.words.size) {
                                val w = activeLine.words[wIdx]
                                canvas.drawText(w.word, w.x, drawY, wordGlowPaint)
                                canvas.drawText(w.word, w.x, drawY, wordActivePaint)
                            }
                            canvas.restore()
                        }
                    }
                }
            }
            "SMOOTH_SLIDE" -> {
                wordActivePaint.alpha = incomingAlpha
                for (line in currentLayoutLines) {
                    val drawY = line.baselineY - currentScrollY + incomingOffsetY
                    for (w in line.words) {
                        canvas.drawText(w.word, w.x, drawY, wordActivePaint)
                    }
                }
            }
            "FADE" -> {
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
                        blurRevealPaint.alpha = ((1f - animProgress) * baseAlpha * 0.7f).toInt().coerceIn(0, 255)
                        blurRevealPaint.setShadowLayer(blurRadius, 0f, 0f, Color.WHITE)
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
                wordActivePaint.alpha = incomingAlpha
                for (line in currentLayoutLines) {
                    val drawY = line.baselineY - currentScrollY
                    for (w in line.words) {
                        canvas.drawText(w.word, w.x, drawY, wordActivePaint)
                    }
                }
            }
            "TYPEWRITER" -> {
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

        holdFillPaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(
            primaryColor,
            (42f * prog).roundToInt().coerceIn(0, 48)
        )
        canvas.drawRoundRect(holdFeedbackRect, radiusPx, radiusPx, holdFillPaint)

        holdHaloPaint.strokeWidth = strokePx + (2.5f * density)
        holdHaloPaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(
            primaryColor,
            (64f * prog).roundToInt().coerceIn(0, 80)
        )
        canvas.drawRoundRect(holdFeedbackRect, radiusPx, radiusPx, holdHaloPaint)

        holdOutlinePaint.strokeWidth = strokePx
        holdOutlinePaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(
            primaryColor,
            (235f * prog).roundToInt().coerceIn(0, 245)
        )
        canvas.drawRoundRect(holdFeedbackRect, radiusPx, radiusPx, holdOutlinePaint)

        val dotRadius = (3.5f * density).coerceAtLeast(3f) * prog
        val inset = maxOf(radiusPx * 0.42f, 8f * density)
        holdDotPaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(
            Color.WHITE,
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
                dragOrientation = displayGeometry?.orientation ?: activeOrientation
                initialX = layoutParams.x
                initialY = layoutParams.y
                initialW = if (layoutParams.width > 0) layoutParams.width else width
                initialH = if (layoutParams.height > 0) layoutParams.height else height
                initialTouchX = event.rawX
                initialTouchY = event.rawY

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
                if (activeTouchTarget == TouchTarget.NONE) return false
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()

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

                when (activeTouchTarget) {
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

                val sizeChanged = targetW != layoutParams.width || targetH != layoutParams.height
                applyUpdatedWindowBounds(targetX, targetY, targetW, targetH)
                if (sizeChanged) {
                    recalculateLayout()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (activeTouchTarget != TouchTarget.NONE) {
                    val finalW = if (layoutParams.width > 0) layoutParams.width else width
                    val finalH = if (layoutParams.height > 0) layoutParams.height else height
                    onBoundsChanged(dragOrientation, layoutParams.x, layoutParams.y, finalW, finalH)
                }
                activeTouchTarget = TouchTarget.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleNormalTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelScheduledHoldCallback()
                val pointerId = event.getPointerId(0)
                val onControl = isPointOnInteractiveControl?.invoke(event.x, event.y) == true
                val started = holdGestureController.onDown(
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
                if (holdGestureController.onMultiPointerDown()) {
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
                        if (holdGestureController.onMove(rawX, rawY)) {
                            cancelScheduledHoldCallback()
                        }
                        true
                    }
                    HudHoldGestureController.State.ACTIVE_REPOSITION -> {
                        val dx = (rawX - initialTouchX).toInt()
                        val dy = (rawY - initialTouchY).toInt()
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
                        applyUpdatedWindowBounds(targetX, targetY, initialW, initialH)
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
                cancelHoldGesture(revertActivePosition = true)
                return true
            }
        }
        return false
    }

    private fun finishHoldPointerUp() {
        cancelScheduledHoldCallback()
        when (holdGestureController.onUp()) {
            HudHoldGestureController.UpResult.TRIGGER_TAP -> {
                onResyncRequested?.invoke()
            }
            HudHoldGestureController.UpResult.COMMIT_REPOSITION -> {
                val finalW = if (layoutParams.width > 0) layoutParams.width else width
                val finalH = if (layoutParams.height > 0) layoutParams.height else height
                onBoundsChanged(dragOrientation, layoutParams.x, layoutParams.y, finalW, finalH)
                animateHoldFeedback(active = false)
            }
            HudHoldGestureController.UpResult.NONE -> {
                if (holdFeedbackProgress > 0f) {
                    animateHoldFeedback(active = false)
                }
            }
        }
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

        try {
            windowManager.updateViewLayout(this, layoutParams)
        } catch (_: Exception) {}
    }

    companion object {
        const val SOLID_BACKDROP_COLOR: Int = 0xFF121216.toInt()
        const val GLASS_BACKDROP_COLOR: Int = 0xFF161A24.toInt()
        const val GLASS_FILL_ALPHA_MAX: Float = 224f
        const val GLASS_SHEEN_ALPHA_MAX: Float = 44f
        const val GLASS_BORDER_ALPHA_MAX: Float = 118f
        const val GLASS_ELEVATION_SHADOW_ALPHA_MAX: Float = 52f
        const val GLASS_INNER_HIGHLIGHT_ALPHA_MAX: Float = 54f

        val GLASS_FROST_STOPS = floatArrayOf(0f, 0.38f, 0.72f, 1f)
        val GLASS_BORDER_STOPS = floatArrayOf(0f, 0.45f, 1f)
        val GLASS_INNER_HIGHLIGHT_STOPS = floatArrayOf(0f, 0.42f, 1f)

        const val ACTIVE_LYRIC_COLOR: Int = 0xFFFFFFFF.toInt()
        const val INACTIVE_LYRIC_COLOR: Int = 0xFFF2F5FA.toInt()
        const val INACTIVE_LYRIC_ALPHA_RATIO: Float = 0.64f
        const val OUTGOING_LYRIC_ALPHA_RATIO: Float = 0.44f
        const val ACTIVE_SHADOW_ALPHA: Int = 215
        const val INACTIVE_SHADOW_ALPHA: Int = 95
        const val ACTIVE_GLOW_ALPHA: Int = 185

        data class BackdropAppearanceSpec(
            val isGlass: Boolean,
            val fillColor: Int,
            val fillAlpha: Int,
            val glassSheenAlpha: Int,
            val borderAlpha: Int,
            val glassElevationShadowAlpha: Int,
            val glassInnerHighlightAlpha: Int
        )

        data class LyricColorHierarchy(
            val baseAlpha: Int,
            val activeAlpha: Int,
            val inactiveAlpha: Int,
            val outgoingAlpha: Int,
            val activeColor: Int = ACTIVE_LYRIC_COLOR,
            val inactiveColor: Int = INACTIVE_LYRIC_COLOR
        )

        fun computeBackdropAppearance(style: String, opacity: Float): BackdropAppearanceSpec {
            val clampedOpacity = opacity.coerceIn(0f, 1f)
            val isGlass = style.trim().equals("GLASS", ignoreCase = true)
            val fillColor = if (isGlass) GLASS_BACKDROP_COLOR else SOLID_BACKDROP_COLOR
            val fillAlpha = if (isGlass) {
                (clampedOpacity * GLASS_FILL_ALPHA_MAX).roundToInt().coerceIn(0, 255)
            } else {
                (clampedOpacity * 255f).roundToInt().coerceIn(0, 255)
            }
            val glassSheenAlpha = if (isGlass) {
                (clampedOpacity * GLASS_SHEEN_ALPHA_MAX).roundToInt().coerceIn(0, 44)
            } else {
                0
            }
            val borderAlpha = if (isGlass) {
                (clampedOpacity * GLASS_BORDER_ALPHA_MAX).roundToInt().coerceIn(0, 120)
            } else {
                0
            }
            val elevationShadowAlpha = if (isGlass) {
                (clampedOpacity * GLASS_ELEVATION_SHADOW_ALPHA_MAX).roundToInt().coerceIn(0, 64)
            } else {
                0
            }
            val innerHighlightAlpha = if (isGlass) {
                (clampedOpacity * GLASS_INNER_HIGHLIGHT_ALPHA_MAX).roundToInt().coerceIn(0, 68)
            } else {
                0
            }
            return BackdropAppearanceSpec(
                isGlass = isGlass,
                fillColor = fillColor,
                fillAlpha = fillAlpha,
                glassSheenAlpha = glassSheenAlpha,
                borderAlpha = borderAlpha,
                glassElevationShadowAlpha = elevationShadowAlpha,
                glassInnerHighlightAlpha = innerHighlightAlpha
            )
        }

        private fun argbColor(alpha: Int, red: Int, green: Int, blue: Int): Int {
            return ((alpha.coerceIn(0, 255) shl 24) or
                (red.coerceIn(0, 255) shl 16) or
                (green.coerceIn(0, 255) shl 8) or
                blue.coerceIn(0, 255))
        }

        fun buildGlassFrostGradientColors(sheenAlpha: Int): IntArray {
            val safe = sheenAlpha.coerceIn(0, 255)
            val upperMid = (safe * 0.34f).roundToInt().coerceIn(0, 255)
            val lowerMid = (safe * 0.14f).roundToInt().coerceIn(0, 255)
            val bottom = (safe * 0.30f).roundToInt().coerceIn(0, 255)
            return intArrayOf(
                argbColor(safe, 255, 255, 255),
                argbColor(upperMid, 238, 244, 255),
                argbColor(lowerMid, 218, 228, 248),
                argbColor(bottom, 206, 220, 245)
            )
        }

        fun buildGlassBorderGradientColors(borderAlpha: Int): IntArray {
            val safe = borderAlpha.coerceIn(0, 255)
            val mid = (safe * 0.38f).roundToInt().coerceIn(0, 255)
            val bottom = (safe * 0.56f).roundToInt().coerceIn(0, 255)
            return intArrayOf(
                argbColor(safe, 255, 255, 255),
                argbColor(mid, 230, 238, 252),
                argbColor(bottom, 202, 216, 242)
            )
        }

        fun buildGlassInnerHighlightColors(innerAlpha: Int): IntArray {
            val safe = innerAlpha.coerceIn(0, 255)
            val mid = (safe * 0.18f).roundToInt().coerceIn(0, 255)
            return intArrayOf(
                argbColor(safe, 255, 255, 255),
                argbColor(mid, 236, 244, 255),
                argbColor(0, 220, 232, 255)
            )
        }

        fun computeLyricColorHierarchy(
            textOpacity: Float,
            animProgress: Float = 1f,
            isFadeStyle: Boolean = false
        ): LyricColorHierarchy {
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
                outgoingAlpha = outgoingAlpha
            )
        }

        data class BackdropRenderSpec(
            val left: Float,
            val top: Float,
            val right: Float,
            val bottom: Float,
            val cornerRadiusPx: Float,
            val fillColor: Int,
            val fillAlpha: Int,
            val showBorder: Boolean,
            val borderColor: Int,
            val borderAlpha: Int,
            val borderWidthPx: Float,
            val glassSheenAlpha: Int,
            val glassElevationShadowAlpha: Int = 0,
            val glassInnerHighlightAlpha: Int = 0
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
            val borderWidthPx = (1f * safeDensity).coerceAtLeast(1f)

            return BackdropRenderSpec(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                cornerRadiusPx = cornerRadiusPx,
                fillColor = appearance.fillColor,
                fillAlpha = appearance.fillAlpha,
                showBorder = appearance.isGlass && appearance.borderAlpha > 0,
                borderColor = Color.WHITE,
                borderAlpha = appearance.borderAlpha,
                borderWidthPx = borderWidthPx,
                glassSheenAlpha = appearance.glassSheenAlpha,
                glassElevationShadowAlpha = appearance.glassElevationShadowAlpha,
                glassInnerHighlightAlpha = appearance.glassInnerHighlightAlpha
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

    fun onHoldTimeout(): Boolean {
        if (state != State.PENDING_HOLD) return false
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
        const val HOLD_REPOSITION_DELAY_MS = 2000L
        const val INVALID_POINTER_ID = -1
    }
}
