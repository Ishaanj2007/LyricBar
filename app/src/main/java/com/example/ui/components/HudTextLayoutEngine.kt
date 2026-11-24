package com.example.ui.components

import com.example.data.model.WordTiming
import com.example.domain.lyricsengine.LyricsTimingEngine

data class LayoutWord(
    val word: String,
    val width: Float,
    val startMs: Long,
    val endMs: Long,
    var x: Float = 0f,
    var y: Float = 0f,
    val graphemeBoundaries: IntArray = HudVisualSpecs.computeGraphemeBoundaries(word)
) {
    val graphemeCount: Int get() = graphemeBoundaries.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayoutWord) return false
        return word == other.word &&
            width == other.width &&
            startMs == other.startMs &&
            endMs == other.endMs &&
            x == other.x &&
            y == other.y &&
            graphemeBoundaries.contentEquals(other.graphemeBoundaries)
    }

    override fun hashCode(): Int {
        var result = word.hashCode()
        result = 31 * result + width.hashCode()
        result = 31 * result + startMs.hashCode()
        result = 31 * result + endMs.hashCode()
        result = 31 * result + x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + graphemeBoundaries.contentHashCode()
        return result
    }
}

data class LayoutLine(
    val words: List<LayoutWord>,
    val lineWidth: Float,
    var baselineY: Float = 0f,
    val totalGraphemes: Int = words.sumOf { it.graphemeCount },
    val totalChars: Int = words.sumOf { it.word.length }
)

data class HudTextLayoutResult(
    val lines: List<LayoutLine>,
    val totalGraphemes: Int,
    val totalChars: Int,
    val maxScrollX: Float,
    val maxScrollY: Float
)

object HudTextLayoutEngine {

    fun computeLayout(
        rawText: String,
        words: List<WordTiming>?,
        isWordSynced: Boolean,
        lineStartMs: Long,
        lineEndMs: Long,
        nextLyricTimestampMs: Long?,
        singleLineMode: Boolean,
        adaptiveScrollingEnabled: Boolean,
        textAlignMode: String,
        viewWidth: Int,
        viewHeight: Int,
        contentHorizontalPaddingPx: Float,
        spaceWidth: Float,
        measureText: (String) -> Float,
        fontAscent: Float,
        fontDescent: Float,
        hudBackdropEnabled: Boolean,
        backdropVerticalPaddingPx: Float
    ): HudTextLayoutResult {
        if (rawText.isBlank() || viewWidth <= 0) {
            return HudTextLayoutResult(
                lines = emptyList(),
                totalGraphemes = 0,
                totalChars = 0,
                maxScrollX = 0f,
                maxScrollY = 0f
            )
        }

        val paddingHorizontal = contentHorizontalPaddingPx
        val contentLeft = paddingHorizontal
        val contentRight = (viewWidth - paddingHorizontal).coerceAtLeast(contentLeft + 60f)
        val availableWidth = (contentRight - contentLeft).coerceAtLeast(60f)

        // 1. Prepare raw word list
        val wordsToWrap: List<LayoutWord>
        if (isWordSynced && words != null && words.isNotEmpty()) {
            wordsToWrap = words.map { wt ->
                LayoutWord(
                    word = wt.word,
                    width = measureText(wt.word),
                    startMs = wt.startMs,
                    endMs = wt.endMs
                )
            }
        } else {
            val tokens = rawText.trim().split(Regex("\\s+"))
            val lineEnd = if (lineEndMs > lineStartMs) {
                lineEndMs
            } else if (nextLyricTimestampMs != null && nextLyricTimestampMs > lineStartMs) {
                nextLyricTimestampMs
            } else {
                lineStartMs + 3500L
            }
            wordsToWrap = tokens.map { token ->
                LayoutWord(
                    word = token,
                    width = measureText(token),
                    startMs = lineStartMs,
                    endMs = lineEnd
                )
            }
        }

        if (wordsToWrap.isEmpty()) {
            return HudTextLayoutResult(
                lines = emptyList(),
                totalGraphemes = 0,
                totalChars = 0,
                maxScrollX = 0f,
                maxScrollY = 0f
            )
        }

        // 2. Wrap words into lines strictly respecting availableWidth (or keep single line if singleLineMode)
        val lineList = mutableListOf<LayoutLine>()
        var maxScrollX = 0f
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
        val fontHeight = fontDescent - fontAscent
        val lineSpacing = (fontHeight * 0.28f).coerceAtLeast(6f)
        val lineHeight = fontHeight + lineSpacing
        val totalTextHeight = (lineList.size * lineHeight) - lineSpacing
        val availableHeight = viewHeight.toFloat()
        val verticalInset = if (hudBackdropEnabled) (backdropVerticalPaddingPx + 4f) else 0f
        val effectiveTextViewportHeight = (availableHeight - verticalInset * 2f).coerceAtLeast(fontHeight)

        var maxScrollY = 0f
        if (totalTextHeight <= effectiveTextViewportHeight) {
            // Fits entirely inside overlay: center vertically
            val topY = (availableHeight - totalTextHeight) / 2f
            val startBaseline = topY - fontAscent
            lineList.forEachIndexed { idx, line ->
                val by = startBaseline + (idx * lineHeight)
                line.baselineY = by
                line.words.forEach { it.y = by }
            }
            maxScrollY = 0f
        } else {
            // Multi-line wrapped text exceeds overlay height: smooth vertical auto-scroll mode
            val topMargin = verticalInset.coerceAtLeast(18f)
            val startBaseline = topMargin - fontAscent
            lineList.forEachIndexed { idx, line ->
                val by = startBaseline + (idx * lineHeight)
                line.baselineY = by
                line.words.forEach { it.y = by }
            }
            maxScrollY = (totalTextHeight - effectiveTextViewportHeight + 16f).coerceAtLeast(0f)
        }

        return HudTextLayoutResult(
            lines = lineList,
            totalGraphemes = lineList.sumOf { it.totalGraphemes },
            totalChars = lineList.sumOf { it.totalChars },
            maxScrollX = maxScrollX,
            maxScrollY = maxScrollY
        )
    }

    fun calculateVerticalScrollTarget(
        posMs: Long,
        lines: List<LayoutLine>,
        maxScrollY: Float,
        viewHeight: Int,
        isWordSynced: Boolean,
        words: List<WordTiming>?,
        lineStartMs: Long,
        lineEndMs: Long,
        nextLyricTimestampMs: Long?,
        fontAscent: Float,
        fontDescent: Float
    ): Float {
        if (maxScrollY <= 0f || lines.isEmpty()) {
            return 0f
        }
        if (lineStartMs == 0L && lineEndMs == 0L && !isWordSynced) {
            return 0f
        }

        var activeLineIdx = 0
        if (isWordSynced && words?.isNotEmpty() == true) {
            for ((lineIdx, line) in lines.withIndex()) {
                val lineStart = line.words.firstOrNull()?.startMs ?: 0L
                if (posMs >= lineStart) {
                    activeLineIdx = lineIdx
                }
            }
        } else {
            val lineEnd = if (lineEndMs > lineStartMs) {
                lineEndMs
            } else if (nextLyricTimestampMs != null && nextLyricTimestampMs > lineStartMs) {
                nextLyricTimestampMs
            } else {
                lineStartMs + 3500L
            }
            val rawDuration = (lineEnd - lineStartMs).coerceIn(1200L, 12000L)
            val totalChars = lines.sumOf { l -> l.words.sumOf { it.word.length } }.coerceAtLeast(1)
            var charAcc = 0
            for ((lineIdx, line) in lines.withIndex()) {
                val lineChars = line.words.sumOf { it.word.length }.coerceAtLeast(1)
                val startFrac = charAcc.toFloat() / totalChars
                val lStart = lineStartMs + (rawDuration * startFrac).toLong()
                if (posMs >= lStart) {
                    activeLineIdx = lineIdx
                }
                charAcc += lineChars
            }
        }

        val fontHeight = fontDescent - fontAscent
        val activeLineCenterY = lines[activeLineIdx].baselineY + fontAscent + (fontHeight / 2f)

        // Center the active line vertically in the overlay window
        val idealScrollY = activeLineCenterY - (viewHeight / 2f)
        return idealScrollY.coerceIn(0f, maxScrollY)
    }

    fun calculateHorizontalScrollTarget(
        posMs: Long,
        line: LayoutLine?,
        singleLineMode: Boolean,
        adaptiveScrollingEnabled: Boolean,
        maxScrollX: Float,
        animationStyle: String,
        isWordSynced: Boolean,
        lineStartMs: Long,
        lineEndMs: Long,
        nextLyricTimestampMs: Long?,
        viewWidth: Int,
        contentHorizontalPaddingPx: Float,
        karaokeGlowSpeed: Float,
        typewriterSpeed: Float,
        scrollSpeedMultiplier: Float,
        trackDurationMs: Long? = null
    ): Float {
        if (!singleLineMode || !adaptiveScrollingEnabled || maxScrollX <= 0f || line == null) {
            return 0f
        }

        if (lineStartMs == 0L && lineEndMs == 0L && !isWordSynced) {
            return 0f
        }

        val currentStyle = when (animationStyle) {
            "KARAOKE", "APPLE_MUSIC" -> "KARAOKE"
            "SMOOTH_SLIDE", "SMOOTH_SLIDE_UP", "SOFT_FADE", "SCALE_POP" -> "SMOOTH_SLIDE"
            "TYPEWRITER" -> "TYPEWRITER"
            "INSTANT", "MINIMAL_STATIC" -> "INSTANT"
            else -> "KARAOKE"
        }

        val paddingHorizontal = contentHorizontalPaddingPx
        val contentLeft = paddingHorizontal
        val availableWidth = (viewWidth - paddingHorizontal * 2).coerceAtLeast(60f)

        if (currentStyle == "KARAOKE" && isWordSynced && line.words.isNotEmpty()) {
            val firstWordStart = line.words.first().startMs
            val lastWordEnd = line.words.last().endMs.coerceAtLeast(firstWordStart + 1L)

            if (posMs < firstWordStart) {
                return 0f
            }
            if (posMs >= lastWordEnd) {
                return maxScrollX
            }

            var focusX = contentLeft
            for (w in line.words) {
                if (posMs < w.startMs) {
                    break
                } else if (posMs >= w.endMs) {
                    focusX = w.x + w.width
                } else {
                    val wProg = LyricsTimingEngine.calculateWordGlowProgress(
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
            return desiredOffset.coerceIn(0f, maxScrollX)
        } else if (currentStyle == "TYPEWRITER") {
            val speed = (typewriterSpeed * scrollSpeedMultiplier).coerceIn(0.3f, 4.5f)
            val lineEnd = if (lineEndMs > lineStartMs) {
                lineEndMs
            } else if (nextLyricTimestampMs != null && nextLyricTimestampMs > lineStartMs) {
                nextLyricTimestampMs
            } else {
                lineStartMs + 3500L
            }
            val baseDuration = (lineEnd - lineStartMs).coerceIn(1200L, 8000L)
            val duration = (baseDuration / speed).toLong().coerceIn(600L, 12000L)

            val frac = if (posMs < lineStartMs) 0f
                else if (posMs >= lineStartMs + duration) 1f
                else ((posMs - lineStartMs).toFloat() / duration).coerceIn(0f, 1f)

            val revealedWidth = line.lineWidth * frac
            val desiredOffset = (contentLeft + revealedWidth) - (contentLeft + availableWidth * 0.55f)
            return desiredOffset.coerceIn(0f, maxScrollX)
        } else {
            val progress = LyricsTimingEngine.calculateAdaptiveScrollProgress(
                currentLineStartMs = lineStartMs,
                nextTimestampMs = nextLyricTimestampMs,
                trackDurationMs = trackDurationMs,
                currentPositionMs = posMs,
                speedMultiplier = scrollSpeedMultiplier
            )
            return (maxScrollX * progress).coerceIn(0f, maxScrollX)
        }
    }
}
