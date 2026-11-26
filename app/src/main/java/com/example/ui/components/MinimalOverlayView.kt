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
    var onResyncRequested: (() -> Unit)? = null,
    var onTripleTapRequested: (() -> Unit)? = null
) : View(context) {

    private val containerRect = android.graphics.RectF()

    // Pre-allocated paints to guarantee zero object allocation during onDraw
    private val wordActivePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR
        textAlign = Paint.Align.LEFT
        isFakeBoldText = false
    }

    private val wordInactivePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR
        textAlign = Paint.Align.LEFT
        isFakeBoldText = false
    }

    private val previousTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR
        textAlign = Paint.Align.LEFT
        isFakeBoldText = false
    }

    private val wordGlowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR
        textAlign = Paint.Align.LEFT
        isFakeBoldText = false
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
        isFakeBoldText = false
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

    private val tripleTapOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }

    private val tripleTapFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val currentBackdropRect = android.graphics.RectF()
    private val previousBackdropRect = android.graphics.RectF()
    private val drawnBackdropRect = android.graphics.RectF()
    private val holdFeedbackRect = android.graphics.RectF()
    private val tripleTapRect = android.graphics.RectF()

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

    // Layout data structures are defined in HudTextLayoutEngine.kt

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
                applyTypographyToPaints()
                recalculateLayout()
            }
        }

    var boldLyricsEnabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                applyTypographyToPaints()
                recalculateLayout()
            }
        }

    private fun applyTypographyToPaints() {
        val resId = fontResId
        val isBold = boldLyricsEnabled
        val typeface = if (resId != null) {
            if (isBold) {
                getCachedBoldTypeface(context, resId)
            } else {
                getCachedTypeface(context, resId)
            }
        } else {
            if (isBold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
        wordActivePaint.typeface = typeface
        wordInactivePaint.typeface = typeface
        previousTextPaint.typeface = typeface
        wordGlowPaint.typeface = typeface
        blurRevealPaint.typeface = typeface

        wordActivePaint.isFakeBoldText = isBold
        wordInactivePaint.isFakeBoldText = isBold
        previousTextPaint.isFakeBoldText = isBold
        wordGlowPaint.isFakeBoldText = isBold
        blurRevealPaint.isFakeBoldText = isBold
    }

    var isDragMode: Boolean = false
        set(value) {
            if (field != value) {
                cancelActiveDrag()
                field = value
                syncTripleTapDetectorEnabled()
                invalidate()
            }
        }

    var isClickThroughEnabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                if (value) {
                    cancelActiveDrag()
                }
                dragCoordinator.setClickThroughEnabled(value)
                syncTripleTapDetectorEnabled()
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

    // Touch and drag/resize tracking (TouchTarget defined in HudGestureControllers.kt)
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
    private val tapTouchSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat().coerceAtLeast(8f)
    private val tripleTapDetector = HudTripleTapDetector(tapTouchSlopPx = tapTouchSlopPx)
    private var tripleTapFeedbackProgress: Float = 0f
    private var tripleTapFeedbackAnimator: ValueAnimator? = null

    private fun syncTripleTapDetectorEnabled() {
        tripleTapDetector.setEnabled(!isClickThroughEnabled && !isDragMode)
    }

    private fun triggerTripleTapFeedback() {
        tripleTapFeedbackAnimator?.cancel()
        if (!isAttachedToWindow || visibility != VISIBLE || isClickThroughEnabled) {
            tripleTapFeedbackProgress = 0f
            return
        }
        tripleTapFeedbackProgress = 1f
        invalidate()
        tripleTapFeedbackAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 220L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                tripleTapFeedbackProgress = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    tripleTapFeedbackProgress = 0f
                    invalidate()
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    tripleTapFeedbackProgress = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    private val holdHandler = Handler(Looper.getMainLooper())
    private var holdAttemptToken: Long = 0L
    private var scheduledHoldToken: Long = -1L
    private var holdFeedbackProgress: Float = 0f
    private var holdFeedbackAnimator: ValueAnimator? = null
    private val holdTimeoutRunnable = Runnable {
        if (scheduledHoldToken != holdAttemptToken) {
            return@Runnable
        }
        if (!isAttachedToWindow || visibility != VISIBLE || isDragMode || isClickThroughEnabled) {
            dragCoordinator.cancelActiveDrag()
            tripleTapDetector.reset()
            return@Runnable
        }
        val orientation = displayGeometry?.orientation ?: activeOrientation
        if (dragCoordinator.onHoldTimeout(orientation = orientation)) {
            tripleTapDetector.onDragOrHoldActivated()
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
        tripleTapDetector.reset()
        activeTouchTarget = TouchTarget.NONE
        val rolledBack = dragCoordinator.cancelActiveDrag()
        if (rolledBack || holdFeedbackProgress > 0f) {
            animateHoldFeedback(active = false)
        }
        if (tripleTapFeedbackProgress > 0f) {
            tripleTapFeedbackAnimator?.cancel()
            tripleTapFeedbackProgress = 0f
            invalidate()
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
        applyTypographyToPaints()
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

        val result = HudTextLayoutEngine.computeLayout(
            rawText = rawText,
            words = if (currentLine != null && currentLine.isWordSynced && currentLine.words.isNotEmpty()) currentLine.words else null,
            isWordSynced = currentLine?.isWordSynced == true,
            lineStartMs = currentLine?.timeMs ?: 0L,
            lineEndMs = currentLine?.endTimeMs ?: 0L,
            nextLyricTimestampMs = nextLyricTimestampMs,
            singleLineMode = singleLineMode,
            adaptiveScrollingEnabled = adaptiveScrollingEnabled,
            textAlignMode = textAlignMode,
            viewWidth = width,
            viewHeight = height,
            contentHorizontalPaddingPx = getEffectiveContentHorizontalInsetPx(),
            spaceWidth = wordActivePaint.measureText(" "),
            measureText = { wordActivePaint.measureText(it) },
            fontAscent = wordActivePaint.fontMetrics.ascent,
            fontDescent = wordActivePaint.fontMetrics.descent,
            hudBackdropEnabled = hudBackdropEnabled,
            backdropVerticalPaddingPx = getBackdropVerticalPaddingPx()
        )

        if (result.lines.isEmpty()) {
            invalidate()
            return
        }

        maxScrollX = result.maxScrollX
        maxScrollY = result.maxScrollY
        currentLayoutLines.addAll(result.lines)
        totalLayoutGraphemes = result.totalGraphemes
        totalLayoutChars = result.totalChars
        startLoopIfNeeded()
        invalidate()
    }

    private fun updateScrollTarget(posMs: Long) {
        val currentLine = activeLyricLine
        targetScrollY = HudTextLayoutEngine.calculateVerticalScrollTarget(
            posMs = posMs,
            lines = currentLayoutLines,
            maxScrollY = maxScrollY,
            viewHeight = height,
            isWordSynced = currentLine?.isWordSynced == true,
            words = if (currentLine != null && currentLine.isWordSynced && currentLine.words.isNotEmpty()) currentLine.words else null,
            lineStartMs = currentLine?.timeMs ?: 0L,
            lineEndMs = currentLine?.endTimeMs ?: 0L,
            nextLyricTimestampMs = nextLyricTimestampMs,
            fontAscent = wordActivePaint.fontMetrics.ascent,
            fontDescent = wordActivePaint.fontMetrics.descent
        )
    }

    private fun updateHorizontalScrollTarget(posMs: Long) {
        val currentLine = activeLyricLine
        targetScrollX = HudTextLayoutEngine.calculateHorizontalScrollTarget(
            posMs = posMs,
            line = currentLayoutLines.firstOrNull(),
            singleLineMode = singleLineMode,
            adaptiveScrollingEnabled = adaptiveScrollingEnabled,
            maxScrollX = maxScrollX,
            animationStyle = animationStyle,
            isWordSynced = currentLine?.isWordSynced == true,
            lineStartMs = currentLine?.timeMs ?: 0L,
            lineEndMs = currentLine?.endTimeMs ?: 0L,
            nextLyricTimestampMs = nextLyricTimestampMs,
            viewWidth = width,
            contentHorizontalPaddingPx = getEffectiveContentHorizontalInsetPx(),
            karaokeGlowSpeed = karaokeGlowSpeed,
            typewriterSpeed = typewriterSpeed,
            scrollSpeedMultiplier = scrollSpeedMultiplier,
            trackDurationMs = trackDurationMs
        )
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
        tripleTapFeedbackAnimator?.cancel()
        tripleTapFeedbackAnimator = null
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

        // 3. EDIT MODE BOUNDING BOX & HANDLES (Only visible during edit mode when click-through is disabled)
        if (isDragMode && !isClickThroughEnabled) {
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
        } else if (!isClickThroughEnabled && holdFeedbackProgress > 0.01f) {
            drawHoldRepositionFeedback(canvas)
        } else if (!isClickThroughEnabled && tripleTapFeedbackProgress > 0.01f) {
            drawTripleTapFeedback(canvas)
        }
    }

    private fun drawTripleTapFeedback(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val prog = tripleTapFeedbackProgress.coerceIn(0f, 1f)
        val strokePx = (1.75f * density).coerceAtLeast(1.5f)
        val halfStroke = strokePx / 2f

        val radiusPx: Float
        if (hudBackdropEnabled && hudBackdropOpacity > 0f && drawnBackdropRect.width() > 0f && drawnBackdropRect.height() > 0f) {
            val padOut = 2f * density
            tripleTapRect.set(
                (drawnBackdropRect.left - padOut).coerceAtLeast(halfStroke),
                (drawnBackdropRect.top - padOut).coerceAtLeast(halfStroke),
                (drawnBackdropRect.right + padOut).coerceAtMost(width - halfStroke),
                (drawnBackdropRect.bottom + padOut).coerceAtMost(height - halfStroke)
            )
            val maxR = (minOf(tripleTapRect.width(), tripleTapRect.height()) / 2f).coerceAtLeast(0f)
            radiusPx = (hudBackdropCornerRadiusDp.coerceIn(0f, 32f) * density + padOut).coerceIn(0f, maxR)
        } else {
            val padInset = halfStroke + 2f
            tripleTapRect.set(
                padInset,
                padInset,
                (width - padInset).coerceAtLeast(padInset),
                (height - padInset).coerceAtLeast(padInset)
            )
            val maxR = (minOf(tripleTapRect.width(), tripleTapRect.height()) / 2f).coerceAtLeast(0f)
            radiusPx = (16f * density).coerceIn(0f, maxR)
        }

        val useDarkNeutralIndicator = hudBackdropEnabled &&
            hudBackdropOpacity >= 0.35f &&
            hudBackdropStyle == "GREY"
        val primaryNeutral = if (useDarkNeutralIndicator) 0xFF141418.toInt() else Color.WHITE

        tripleTapFillPaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(
            primaryNeutral,
            (40f * prog).roundToInt().coerceIn(0, 44)
        )
        canvas.drawRoundRect(tripleTapRect, radiusPx, radiusPx, tripleTapFillPaint)

        tripleTapOutlinePaint.strokeWidth = strokePx
        tripleTapOutlinePaint.color = androidx.core.graphics.ColorUtils.setAlphaComponent(
            primaryNeutral,
            (185f * prog).roundToInt().coerceIn(0, 200)
        )
        canvas.drawRoundRect(tripleTapRect, radiusPx, radiusPx, tripleTapOutlinePaint)
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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (isClickThroughEnabled) {
            cancelActiveDrag()
            return false
        }
        return super.dispatchTouchEvent(event)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isClickThroughEnabled) {
            cancelActiveDrag()
            return false
        }
        if (!isDragMode) {
            return handleNormalTouchEvent(event)
        }

        val x = event.x
        val y = event.y
        val touchMargin = 40f

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelScheduledHoldCallback()
                tripleTapDetector.reset()
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
        val geom = displayGeometry
        val constraints = geom?.let {
            HudPositioningEngine.computeConstraints(
                geometry = it,
                currentWidthPx = initialBounds.width,
                currentEffectiveHeightPx = initialBounds.height,
                minBackdropHeightPx = minBackdropHeightPx
            )
        }
        return HudBoundsCalculator.computeEditMoveBounds(initialBounds, dx, dy, target, constraints)
    }

    private fun computeNormalMoveBounds(
        initialBounds: HudWindowBounds,
        dx: Int,
        dy: Int
    ): HudWindowBounds {
        val geom = displayGeometry
        val constraints = geom?.let {
            HudPositioningEngine.computeConstraints(
                geometry = it,
                currentWidthPx = initialBounds.width,
                currentEffectiveHeightPx = initialBounds.height,
                minBackdropHeightPx = minBackdropHeightPx
            )
        }
        return HudBoundsCalculator.computeNormalMoveBounds(initialBounds, dx, dy, constraints)
    }

    private fun handleNormalTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelScheduledHoldCallback()
                val pointerId = event.getPointerId(0)
                val rawX = getPointerRawX(event, 0)
                val rawY = getPointerRawY(event, 0)
                val onControl = isPointOnInteractiveControl?.invoke(event.x, event.y) == true
                val started = dragCoordinator.onNormalDown(
                    rawX = rawX,
                    rawY = rawY,
                    pointerId = pointerId,
                    isOnInteractiveControl = onControl
                )
                if (started) {
                    tripleTapDetector.onDown(
                        rawX = rawX,
                        rawY = rawY,
                        pointerId = pointerId,
                        timestampMs = event.eventTime,
                        isOnInteractiveControl = false
                    )
                    scheduledHoldToken = holdAttemptToken
                    holdHandler.postDelayed(holdTimeoutRunnable, holdGestureController.holdDurationMs)
                } else {
                    tripleTapDetector.reset()
                }
                return started
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                tripleTapDetector.onMultiPointerDown()
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
                val pointerId = event.getPointerId(ptrIndex)

                return when (holdGestureController.state) {
                    HudHoldGestureController.State.PENDING_HOLD -> {
                        tripleTapDetector.onMove(rawX, rawY, pointerId)
                        if (dragCoordinator.onNormalMovePending(rawX, rawY)) {
                            cancelScheduledHoldCallback()
                        }
                        true
                    }
                    HudHoldGestureController.State.ACTIVE_REPOSITION -> {
                        tripleTapDetector.onDragOrHoldActivated()
                        dragCoordinator.onNormalDragMove(rawX, rawY, ::computeNormalMoveBounds)
                        true
                    }
                    HudHoldGestureController.State.CANCELLED_BEFORE_HOLD -> {
                        tripleTapDetector.onDragOrHoldActivated()
                        true
                    }
                    HudHoldGestureController.State.IDLE -> false
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val liftedIndex = event.actionIndex
                val liftedId = event.getPointerId(liftedIndex)
                tripleTapDetector.onMultiPointerDown()
                if (liftedId == holdGestureController.activePointerId) {
                    finishHoldPointerUp(
                        pointerId = liftedId,
                        eventTimeMs = event.eventTime,
                        allowTripleTap = false
                    )
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val pointerId = event.getPointerId(event.actionIndex)
                finishHoldPointerUp(
                    pointerId = pointerId,
                    eventTimeMs = event.eventTime,
                    allowTripleTap = true
                )
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelActiveDrag()
                return true
            }
        }
        return false
    }

    private fun finishHoldPointerUp(
        pointerId: Int,
        eventTimeMs: Long,
        allowTripleTap: Boolean
    ) {
        cancelScheduledHoldCallback()
        when (dragCoordinator.onNormalUp()) {
            HudHoldGestureController.UpResult.TRIGGER_TAP -> {
                if (allowTripleTap &&
                    !isClickThroughEnabled &&
                    !isDragMode &&
                    tripleTapDetector.onUp(pointerId = pointerId, timestampMs = eventTimeMs)
                ) {
                    onTripleTapRequested?.invoke()
                    triggerTripleTapFeedback()
                }
            }
            HudHoldGestureController.UpResult.COMMIT_REPOSITION -> {
                tripleTapDetector.onDragOrHoldActivated()
                animateHoldFeedback(active = false)
            }
            HudHoldGestureController.UpResult.NONE -> {
                tripleTapDetector.reset()
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
        private val typefaceCache = java.util.concurrent.ConcurrentHashMap<Int, android.graphics.Typeface>()
        private val boldTypefaceCache = java.util.concurrent.ConcurrentHashMap<Int, android.graphics.Typeface>()

        /**
         * MED-03: Caches parsed font Typeface instances at process scope to prevent repeated
         * asset parsing and allocation on every overlay recreation/reattachment.
         * Uses context.applicationContext to guarantee no short-lived context leakage.
         */
        fun getCachedTypeface(context: Context, @androidx.annotation.FontRes fontResId: Int): android.graphics.Typeface {
            return typefaceCache.getOrPut(fontResId) {
                try {
                    ResourcesCompat.getFont(context.applicationContext, fontResId)
                        ?: android.graphics.Typeface.DEFAULT
                } catch (_: Exception) {
                    android.graphics.Typeface.DEFAULT
                }
            }
        }

        /**
         * Derives and caches a bold Typeface from the cached base Typeface without mutating
         * or overwriting the normal cached Typeface entry.
         */
        fun getCachedBoldTypeface(context: Context, @androidx.annotation.FontRes fontResId: Int): android.graphics.Typeface {
            return boldTypefaceCache.getOrPut(fontResId) {
                val base = getCachedTypeface(context, fontResId)
                try {
                    android.graphics.Typeface.create(base, android.graphics.Typeface.BOLD)
                } catch (_: Exception) {
                    android.graphics.Typeface.DEFAULT_BOLD
                }
            }
        }

        fun clearTypefaceCache() {
            typefaceCache.clear()
            boldTypefaceCache.clear()
        }

        fun isTypefaceCached(@androidx.annotation.FontRes fontResId: Int): Boolean {
            return typefaceCache.containsKey(fontResId)
        }

        fun putCachedTypefaceForTesting(@androidx.annotation.FontRes fontResId: Int, typeface: android.graphics.Typeface) {
            typefaceCache[fontResId] = typeface
        }

        fun cachedTypefaceCount(): Int = typefaceCache.size

        const val BLACK_BACKDROP_COLOR: Int = HudVisualSpecs.BLACK_BACKDROP_COLOR
        const val GREY_BACKDROP_COLOR: Int = HudVisualSpecs.GREY_BACKDROP_COLOR

        const val ACTIVE_LYRIC_COLOR: Int = HudVisualSpecs.ACTIVE_LYRIC_COLOR
        const val INACTIVE_LYRIC_COLOR: Int = HudVisualSpecs.INACTIVE_LYRIC_COLOR
        const val INACTIVE_LYRIC_ALPHA_RATIO: Float = HudVisualSpecs.INACTIVE_LYRIC_ALPHA_RATIO
        const val OUTGOING_LYRIC_ALPHA_RATIO: Float = HudVisualSpecs.OUTGOING_LYRIC_ALPHA_RATIO
        const val ACTIVE_SHADOW_ALPHA: Int = HudVisualSpecs.ACTIVE_SHADOW_ALPHA
        const val INACTIVE_SHADOW_ALPHA: Int = HudVisualSpecs.INACTIVE_SHADOW_ALPHA
        const val ACTIVE_GLOW_ALPHA: Int = HudVisualSpecs.ACTIVE_GLOW_ALPHA

        fun normalizeBackdropStyle(style: String?): String =
            HudVisualSpecs.normalizeBackdropStyle(style)

        fun computeBackdropAppearance(style: String, opacity: Float): BackdropAppearanceSpec =
            HudVisualSpecs.computeBackdropAppearance(style, opacity)

        fun computeLyricColorHierarchy(
            activeColor: Int = HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR,
            inactiveColor: Int = HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR,
            textOpacity: Float,
            animProgress: Float = 1f,
            isFadeStyle: Boolean = false
        ): LyricColorHierarchy = HudVisualSpecs.computeLyricColorHierarchy(
            activeColor = activeColor,
            inactiveColor = inactiveColor,
            textOpacity = textOpacity,
            animProgress = animProgress,
            isFadeStyle = isFadeStyle
        )

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
        ): BackdropRenderSpec? = HudVisualSpecs.computeBackdropSpec(
            enabled = enabled,
            style = style,
            opacity = opacity,
            cornerRadiusDp = cornerRadiusDp,
            horizontalPaddingDp = horizontalPaddingDp,
            verticalPaddingDp = verticalPaddingDp,
            density = density,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            textLeft = textLeft,
            textTop = textTop,
            textRight = textRight,
            textBottom = textBottom,
            hasVisibleText = hasVisibleText,
            boundsAlreadyExpanded = boundsAlreadyExpanded
        )

        fun computeGraphemeBoundaries(text: String): IntArray =
            HudVisualSpecs.computeGraphemeBoundaries(text)
    }
}


