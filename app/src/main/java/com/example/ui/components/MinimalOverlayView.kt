package com.example.ui.components

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.core.content.res.ResourcesCompat
import com.example.data.model.LyricLine
import kotlin.math.abs
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
class MinimalOverlayView(
    context: Context,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    private val onBoundsChanged: (x: Int, y: Int, width: Int, height: Int) -> Unit,
    var onResyncRequested: (() -> Unit)? = null
) : View(context) {

    private val containerRect = android.graphics.RectF()

    // Pre-allocated paints to guarantee zero object allocation during onDraw
    private val wordActivePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    private val wordInactivePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 255, 255, 255) // 45% opacity for readable un-sung words
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    private val previousTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    private val wordGlowPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        setShadowLayer(14f, 0f, 0f, Color.argb(220, 255, 255, 255))
    }

    private val blurRevealPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
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
        var y: Float = 0f
    )

    data class LayoutLine(
        val words: List<LayoutWord>,
        val lineWidth: Float,
        var baselineY: Float = 0f
    )

    private val currentLayoutLines = mutableListOf<LayoutLine>()
    private var previousLayoutLines = listOf<LayoutLine>()

    private var animProgress: Float = 1f
    private var transitionAnimator: ValueAnimator? = null

    // Vertical auto-scroll state
    private var maxScrollY: Float = 0f
    private var targetScrollY: Float = 0f
    private var currentScrollY: Float = 0f

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

    // 60 FPS frame animation loop
    private var isLoopRunning: Boolean = false

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || !isScreenOn) {
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
            if (singleLineMode && maxScrollX > 0f) {
                updateHorizontalScrollTarget(posMs)
                val diffX = targetScrollX - currentScrollX
                if (abs(diffX) > 0.3f) {
                    currentScrollX += diffX * 0.16f
                } else {
                    currentScrollX = targetScrollX
                }
            }

            invalidate()

            val currentStyle = when (animationStyle) {
                "KARAOKE", "APPLE_MUSIC" -> "KARAOKE"
                "SMOOTH_SLIDE", "SMOOTH_SLIDE_UP", "SOFT_FADE", "SCALE_POP" -> "SMOOTH_SLIDE"
                "FADE", "FADE_IN_OUT" -> "FADE"
                "BLUR_REVEAL", "BLUR" -> "BLUR_REVEAL"
                "TYPEWRITER" -> "TYPEWRITER"
                "INSTANT", "MINIMAL_STATIC" -> "INSTANT"
                else -> "KARAOKE"
            }

            // Run continuous 60 FPS animation loop whenever music is playing in KARAOKE style
            val isKaraoke = currentStyle == "KARAOKE"
            val needsContinuous = isPlaying && (
                isKaraoke ||
                (currentStyle == "TYPEWRITER") ||
                (transitionAnimator?.isRunning == true) ||
                (maxScrollY > 0f && abs(targetScrollY - currentScrollY) > 0.3f) ||
                (singleLineMode && maxScrollX > 0f && abs(targetScrollX - currentScrollX) > 0.3f)
            )

            if (needsContinuous) {
                postOnAnimation(this)
            } else {
                isLoopRunning = false
            }
        }
    }

    var activeLyricLine: LyricLine? = null
        set(value) {
            val oldLine = field
            val oldText = oldLine?.text ?: text
            val newText = value?.text ?: ""
            field = value

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
                val isNormalSequentialAdvance = oldLine != null && value != null &&
                    value.timeMs >= oldLine.timeMs &&
                    (value.timeMs - oldLine.timeMs) < 15000L

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
                currentScrollX = 0f
                targetScrollX = 0f
                text = newText
                recalculateLayout()
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
            if (field != value) {
                field = value
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
            updateShadow(wordActivePaint, value)
            updateShadow(wordInactivePaint, value)
            updateShadow(previousTextPaint, value)
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
                recalculateLayout()
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
            field = value
            invalidate()
        }

    // Touch and drag/resize tracking
    private enum class TouchTarget { NONE, MOVE, RESIZE_TOP, RESIZE_BOTTOM, RESIZE_LEFT, RESIZE_RIGHT, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }
    private var activeTouchTarget = TouchTarget.NONE
    private var initialX = 0
    private var initialY = 0
    private var initialW = 0
    private var initialH = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    init {
        setBackgroundColor(Color.TRANSPARENT)
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
        updateShadow(wordActivePaint, textShadowEnabled)
        updateShadow(wordInactivePaint, textShadowEnabled)
        updateShadow(previousTextPaint, textShadowEnabled)
    }

    private fun updateShadow(paint: Paint, enabled: Boolean) {
        if (enabled) {
            paint.setShadowLayer(6f, 0f, 2f, Color.argb(200, 0, 0, 0))
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
        val posDiff = abs(positionMs - lastPositionMs)
        val isSeeking = posDiff > 1500L

        isPlaying = playing
        lastPositionMs = positionMs
        playbackSpeed = if (speed > 0f) speed else 1.0f
        lastUpdatedTimestamp = updatedTimestamp

        if (isSeeking) {
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

        if (isPlaying) {
            startLoopIfNeeded()
        } else {
            stopLoop()
            invalidate()
        }
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
                "SMOOTH_SLIDE" -> 300L
                "FADE" -> 360L
                "BLUR_REVEAL" -> 400L
                else -> 240L
            }
            val speed = when (currentStyle) {
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

    private fun recalculateLayout() {
        currentLayoutLines.clear()
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

        val paddingHorizontal = 28f
        val contentLeft = paddingHorizontal
        val contentRight = (width - paddingHorizontal).coerceAtLeast(contentLeft + 60f)
        val availableWidth = (contentRight - contentLeft).coerceAtLeast(60f)
        val spaceWidth = wordActivePaint.measureText(" ")

        // 1. Prepare raw word list (either with TTML timestamps or single-word fallback)
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
            val lineEnd = currentLine?.endTimeMs ?: (lineStart + 3500L)
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
            maxScrollX = (curLineWidth - availableWidth + 24f).coerceAtLeast(0f)
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
            val startX = if (singleLineMode && line.lineWidth > availableWidth) {
                // When single line exceeds width, start at contentLeft so scrolling from right to left smoothly reveals the rest
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

        if (totalTextHeight <= availableHeight) {
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
            val startBaseline = 18f - fontMetrics.ascent
            lineList.forEachIndexed { idx, line ->
                val by = startBaseline + (idx * lineHeight)
                line.baselineY = by
                line.words.forEach { it.y = by }
            }
            maxScrollY = totalTextHeight - availableHeight + 36f
        }

        currentLayoutLines.addAll(lineList)
        startLoopIfNeeded()
        invalidate()
    }

    private fun updateScrollTarget(posMs: Long) {
        if (maxScrollY <= 0f || currentLayoutLines.isEmpty()) {
            targetScrollY = 0f
            return
        }

        var activeLineIdx = 0
        val isWordSynced = activeLyricLine?.isWordSynced == true
        if (isWordSynced && activeLyricLine?.words?.isNotEmpty() == true) {
            for ((lineIdx, line) in currentLayoutLines.withIndex()) {
                val lineStartMs = line.words.firstOrNull()?.startMs ?: 0L
                if (posMs >= lineStartMs) {
                    activeLineIdx = lineIdx
                }
            }
        } else {
            val lineStart = activeLyricLine?.timeMs ?: 0L
            val lineEnd = activeLyricLine?.endTimeMs ?: (lineStart + 3500L)
            val rawDuration = (lineEnd - lineStart).coerceIn(1500L, 8000L)
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
        if (maxScrollX <= 0f || currentLayoutLines.isEmpty()) {
            targetScrollX = 0f
            return
        }

        val line = currentLayoutLines.firstOrNull() ?: return
        val isWordSynced = activeLyricLine?.isWordSynced == true
        val currentStyle = when (animationStyle) {
            "KARAOKE", "APPLE_MUSIC" -> "KARAOKE"
            "SMOOTH_SLIDE", "SMOOTH_SLIDE_UP", "SOFT_FADE", "SCALE_POP" -> "SMOOTH_SLIDE"
            "TYPEWRITER" -> "TYPEWRITER"
            "INSTANT", "MINIMAL_STATIC" -> "INSTANT"
            else -> "KARAOKE"
        }

        val paddingHorizontal = 28f
        val contentLeft = paddingHorizontal
        val availableWidth = (width - paddingHorizontal * 2).coerceAtLeast(60f)

        if (currentStyle == "KARAOKE" && isWordSynced && line.words.isNotEmpty()) {
            // Apple-style Karaoke with TTML words: follow active singing focus point smoothly
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
                    val wDuration = (w.endMs - w.startMs).coerceAtLeast(1L)
                    val wProg = ((posMs - w.startMs).toFloat() / wDuration).coerceIn(0f, 1f)
                    focusX = w.x + (w.width * wProg)
                    break
                }
            }

            val desiredOffset = focusX - (contentLeft + availableWidth * 0.45f)
            targetScrollX = desiredOffset.coerceIn(0f, maxScrollX)
        } else if (currentStyle == "TYPEWRITER") {
            // Coordinate horizontal scrolling with typewriter character reveal
            val speed = typewriterSpeed.coerceIn(0.3f, 3.0f)
            val lineStart = activeLyricLine?.timeMs ?: 0L
            val lineEnd = activeLyricLine?.endTimeMs ?: (lineStart + 3500L)
            val baseDuration = (lineEnd - lineStart).coerceIn(1200L, 4000L)
            val duration = (baseDuration / speed).toLong().coerceIn(600L, 12000L)

            val frac = if (posMs < lineStart) 0f
                else if (posMs >= lineStart + duration) 1f
                else ((posMs - lineStart).toFloat() / duration).coerceIn(0f, 1f)

            val revealedWidth = line.lineWidth * frac
            val desiredOffset = (contentLeft + revealedWidth) - (contentLeft + availableWidth * 0.55f)
            targetScrollX = desiredOffset.coerceIn(0f, maxScrollX)
        } else {
            // SMOOTH_SLIDE, INSTANT, or LRC line-timed KARAOKE:
            // Proportional smooth scroll from start to end of line duration
            val lineStart = activeLyricLine?.timeMs ?: 0L
            val lineEnd = activeLyricLine?.endTimeMs ?: (lineStart + 3500L)
            val duration = (lineEnd - lineStart).coerceIn(1500L, 8000L)

            val leadIn = (duration * 0.12f).toLong().coerceIn(200L, 800L)
            val activeDuration = (duration - leadIn).coerceAtLeast(500L)

            val frac = if (posMs <= lineStart + leadIn) {
                0f
            } else if (posMs >= lineStart + duration) {
                1f
            } else {
                ((posMs - (lineStart + leadIn)).toFloat() / activeDuration).coerceIn(0f, 1f)
            }
            targetScrollX = (maxScrollX * frac).coerceIn(0f, maxScrollX)
        }
    }

    fun startLoopIfNeeded() {
        if (!isLoopRunning && isAttachedToWindow && isScreenOn) {
            isLoopRunning = true
            postOnAnimation(frameRunnable)
        }
    }

    fun stopLoop() {
        removeCallbacks(frameRunnable)
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
        transitionAnimator?.cancel()
        stopLoop()
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
        val minHeight = ((fontMetrics.descent - fontMetrics.ascent) * 2.8f).roundToInt().coerceIn(60, 200)

        val finalHeight = if (heightSpecMode == MeasureSpec.EXACTLY && heightSpecSize > 0) {
            heightSpecSize
        } else {
            minHeight
        }
        setMeasuredDimension(width, finalHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val paddingHorizontal = 24f

        // Clip container area so text wraps and scrolls cleanly within configured overlay boundaries
        canvas.save()
        canvas.clipRect(paddingHorizontal, 0f, width - paddingHorizontal, height.toFloat())

        val currentStyle = when (animationStyle) {
            "KARAOKE", "APPLE_MUSIC" -> "KARAOKE"
            "SMOOTH_SLIDE", "SMOOTH_SLIDE_UP", "SOFT_FADE", "SCALE_POP" -> "SMOOTH_SLIDE"
            "FADE", "FADE_IN_OUT" -> "FADE"
            "BLUR_REVEAL", "BLUR" -> "BLUR_REVEAL"
            "TYPEWRITER" -> "TYPEWRITER"
            "INSTANT", "MINIMAL_STATIC" -> "INSTANT"
            else -> "KARAOKE"
        }

        // 1. If transition is running, draw outgoing previous line
        val baseAlpha = (255 * textOpacity).toInt().coerceIn(0, 255)
        if (animProgress < 1f && previousLayoutLines.isNotEmpty() && currentStyle != "INSTANT" && currentStyle != "TYPEWRITER") {
            val outgoingOffsetY = if (currentStyle == "KARAOKE" || currentStyle == "SMOOTH_SLIDE") {
                -animProgress * 24f
            } else {
                0f
            }
            val outgoingAlpha = if (currentStyle == "FADE" || currentStyle == "BLUR_REVEAL") {
                ((1f - animProgress) * baseAlpha).toInt().coerceIn(0, 255)
            } else {
                ((1f - animProgress) * (baseAlpha * 0.35f)).toInt().coerceIn(0, 255)
            }
            previousTextPaint.alpha = outgoingAlpha
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
        val incomingAlpha = if (animProgress < 1f && (currentStyle == "KARAOKE" || currentStyle == "SMOOTH_SLIDE" || currentStyle == "FADE" || currentStyle == "BLUR_REVEAL")) {
            (animProgress * baseAlpha).toInt().coerceIn(0, 255)
        } else baseAlpha
        val posMs = getCalculatedPositionMs()
        val isWordSynced = activeLyricLine?.isWordSynced == true

        canvas.save()
        if (singleLineMode && currentScrollX > 0f) {
            canvas.translate(-currentScrollX, 0f)
        }

        when (currentStyle) {
            "KARAOKE" -> {
                if (isWordSynced && activeLyricLine?.words?.isNotEmpty() == true) {
                    // 1. TTML Word-synchronized lyrics: Apple-style progressive highlight
                    wordInactivePaint.alpha = (incomingAlpha * 0.35f).toInt().coerceIn(0, 255)
                    wordActivePaint.alpha = incomingAlpha
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
                                    val duration = (w.endMs - w.startMs).coerceAtLeast(1L)
                                    val progress = ((posMs - w.startMs).toFloat() / duration).coerceIn(0f, 1f)

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
                } else {
                    // 2. Synchronized word timestamps (TTML) unavailable (LRC line-timed or plain):
                    val lineStart = activeLyricLine?.timeMs ?: 0L
                    val lineEnd = activeLyricLine?.endTimeMs ?: (lineStart + 3500L)
                    val rawDuration = (lineEnd - lineStart).coerceIn(1500L, 8000L)

                    // A: Always display ALL lines with low opacity below (inactive/base layer)
                    wordInactivePaint.alpha = (incomingAlpha * 0.35f).toInt().coerceIn(0, 255)
                    for (line in currentLayoutLines) {
                        val drawY = line.baselineY - currentScrollY + incomingOffsetY
                        for (w in line.words) {
                            canvas.drawText(w.word, w.x, drawY, wordInactivePaint)
                        }
                    }

                    // B: Show high-opacity glowing text above that fades in slowly left to right
                    // CRITICAL: Only the single active visual line receives the highlight sweep!
                    if (currentLayoutLines.isNotEmpty()) {
                        val totalChars = currentLayoutLines.sumOf { l -> l.words.sumOf { it.word.length } }.coerceAtLeast(1)

                        var activeLineIdx = 0
                        var activeLineStart = lineStart
                        var activeLineDuration = rawDuration
                        var charAccumulator = 0

                        for ((idx, line) in currentLayoutLines.withIndex()) {
                            val lineChars = line.words.sumOf { it.word.length }.coerceAtLeast(1)
                            val startFrac = charAccumulator.toFloat() / totalChars
                            val endFrac = (charAccumulator + lineChars).toFloat() / totalChars
                            val lStart = lineStart + (rawDuration * startFrac).toLong()
                            val lEnd = lineStart + (rawDuration * endFrac).toLong()

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

                        val lineProgress = if (posMs < activeLineStart) {
                            if (activeLineStart == 0L) 1f else 0f
                        } else if (posMs >= activeLineStart + activeLineDuration) {
                            1f
                        } else {
                            ((posMs - activeLineStart).toFloat() / activeLineDuration).coerceIn(0f, 1f)
                        }

                        if (lineProgress > 0f) {
                            wordActivePaint.alpha = incomingAlpha
                            wordGlowPaint.alpha = incomingAlpha

                            val activeLine = currentLayoutLines[activeLineIdx]
                            val lineLeft = activeLine.words.firstOrNull()?.x ?: paddingHorizontal
                            val lineRight = (activeLine.words.lastOrNull()?.let { it.x + it.width }) ?: (width - paddingHorizontal)
                            val sweepX = lineLeft + ((lineRight - lineLeft) * lineProgress)
                            val drawY = activeLine.baselineY - currentScrollY + incomingOffsetY

                            canvas.save()
                            canvas.clipRect(0f, 0f, sweepX, height.toFloat())
                            for (w in activeLine.words) {
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
                    for (line in currentLayoutLines) {
                        val drawY = line.baselineY - currentScrollY
                        for (w in line.words) {
                            if (posMs < w.startMs) continue
                            if (posMs >= w.endMs) {
                                canvas.drawText(w.word, w.x, drawY, wordActivePaint)
                            } else {
                                val rawDuration = (w.endMs - w.startMs).coerceAtLeast(1L)
                                val duration = (rawDuration / speed).toLong().coerceAtLeast(1L)
                                val frac = ((posMs - w.startMs).toFloat() / duration).coerceIn(0f, 1f)
                                val chars = (w.word.length * frac).toInt().coerceIn(1, w.word.length)
                                val sub = w.word.substring(0, chars)
                                canvas.drawText(sub, w.x, drawY, wordActivePaint)
                            }
                        }
                    }
                } else {
                    val lineStart = activeLyricLine?.timeMs ?: 0L
                    val lineEnd = activeLyricLine?.endTimeMs ?: (lineStart + 3500L)
                    val baseDuration = (lineEnd - lineStart).coerceIn(1200L, 4000L)
                    val duration = (baseDuration / speed).toLong().coerceIn(600L, 12000L)

                    val totalChars = currentLayoutLines.sumOf { l -> l.words.sumOf { it.word.length } }
                    val revealedChars = if (posMs < lineStart) {
                        if (lineStart == 0L) totalChars else 0
                    } else if (posMs >= lineStart + duration) {
                        totalChars
                    } else {
                        val frac = ((posMs - lineStart).toFloat() / duration).coerceIn(0f, 1f)
                        (totalChars * frac).toInt().coerceIn(0, totalChars)
                    }

                    var charsRemaining = revealedChars
                    for (line in currentLayoutLines) {
                        val drawY = line.baselineY - currentScrollY
                        for (w in line.words) {
                            if (charsRemaining <= 0) break
                            if (charsRemaining >= w.word.length) {
                                canvas.drawText(w.word, w.x, drawY, wordActivePaint)
                                charsRemaining -= w.word.length
                            } else {
                                val sub = w.word.substring(0, charsRemaining)
                                canvas.drawText(sub, w.x, drawY, wordActivePaint)
                                charsRemaining = 0
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
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isDragMode) {
            if (event.action == MotionEvent.ACTION_UP) {
                onResyncRequested?.invoke()
            }
            return false
        }

        val x = event.x
        val y = event.y
        val touchMargin = 40f

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
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
                val dx = (event.rawX - initialTouchX).toInt()
                val dy = (event.rawY - initialTouchY).toInt()

                when (activeTouchTarget) {
                    TouchTarget.MOVE -> {
                        layoutParams.x = (initialX + dx).coerceIn(-2000, 2000)
                        layoutParams.y = (initialY + dy).coerceIn(0, 3000)
                    }
                    TouchTarget.RESIZE_RIGHT -> {
                        layoutParams.width = (initialW + dx).coerceIn(280, 2000)
                    }
                    TouchTarget.RESIZE_BOTTOM -> {
                        layoutParams.height = (initialH + dy).coerceIn(60, 800)
                    }
                    TouchTarget.RESIZE_BR -> {
                        layoutParams.width = (initialW + dx).coerceIn(280, 2000)
                        layoutParams.height = (initialH + dy).coerceIn(60, 800)
                    }
                    TouchTarget.RESIZE_LEFT -> {
                        val newW = (initialW - dx).coerceIn(280, 2000)
                        layoutParams.width = newW
                        layoutParams.x = (initialX + dx).coerceIn(-2000, 2000)
                    }
                    TouchTarget.RESIZE_TOP -> {
                        val newH = (initialH - dy).coerceIn(60, 800)
                        layoutParams.height = newH
                        layoutParams.y = (initialY + dy).coerceIn(0, 3000)
                    }
                    TouchTarget.RESIZE_TL -> {
                        val newW = (initialW - dx).coerceIn(280, 2000)
                        val newH = (initialH - dy).coerceIn(60, 800)
                        layoutParams.width = newW
                        layoutParams.height = newH
                        layoutParams.x = (initialX + dx).coerceIn(-2000, 2000)
                        layoutParams.y = (initialY + dy).coerceIn(0, 3000)
                    }
                    TouchTarget.RESIZE_TR -> {
                        val newW = (initialW + dx).coerceIn(280, 2000)
                        val newH = (initialH - dy).coerceIn(60, 800)
                        layoutParams.width = newW
                        layoutParams.height = newH
                        layoutParams.y = (initialY + dy).coerceIn(0, 3000)
                    }
                    TouchTarget.RESIZE_BL -> {
                        val newW = (initialW - dx).coerceIn(280, 2000)
                        val newH = (initialH + dy).coerceIn(60, 800)
                        layoutParams.width = newW
                        layoutParams.height = newH
                        layoutParams.x = (initialX + dx).coerceIn(-2000, 2000)
                        layoutParams.y = (initialY + dy).coerceIn(0, 3000)
                    }
                    TouchTarget.NONE -> {}
                }

                try {
                    windowManager.updateViewLayout(this, layoutParams)
                } catch (_: Exception) {}
                recalculateLayout()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val finalW = if (layoutParams.width > 0) layoutParams.width else width
                val finalH = if (layoutParams.height > 0) layoutParams.height else height
                onBoundsChanged(layoutParams.x, layoutParams.y, finalW, finalH)
                activeTouchTarget = TouchTarget.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
