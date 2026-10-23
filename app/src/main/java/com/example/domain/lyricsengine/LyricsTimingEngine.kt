package com.example.domain.lyricsengine

import com.example.data.model.LyricLine
import com.example.data.model.WordTiming

object LyricsTimingEngine {

    /**
     * Calculates progressive completion for an individual word (0.0f to 1.0f).
     * Returns 0f if the word hasn't started yet,
     * Returns 1f if the word has completed,
     * Returns smooth linear ratio between startMs and endMs while the word is being sung.
     */
    fun calculateWordProgress(word: WordTiming, currentPositionMs: Long): Float {
        if (currentPositionMs <= word.startMs) return 0f
        if (currentPositionMs >= word.endMs) return 1f
        val duration = (word.endMs - word.startMs).coerceAtLeast(1L)
        return ((currentPositionMs - word.startMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Calculates line completion progress based on line duration (0.0f to 1.0f).
     * Useful for LRC fallback or line progress bars.
     */
    fun calculateLineProgress(
        line: LyricLine,
        nextLine: LyricLine?,
        currentPositionMs: Long
    ): Float {
        val start = line.timeMs
        val end = if (line.endTimeMs > start) {
            line.endTimeMs
        } else if (nextLine != null && nextLine.timeMs > start) {
            nextLine.timeMs
        } else {
            start + 3500L
        }

        if (currentPositionMs <= start) return 0f
        if (currentPositionMs >= end) return 1f
        val duration = (end - start).coerceAtLeast(1L)
        return ((currentPositionMs - start).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * Calculates upward movement offset and crossfade alpha for line transitions.
     * When near the end of a line (within transitionWindowMs), returns progress 0f..1f.
     */
    fun calculateTransitionProgress(
        line: LyricLine,
        nextLine: LyricLine?,
        currentPositionMs: Long,
        transitionWindowMs: Long = 300L
    ): Float {
        val lineEnd = if (line.endTimeMs > line.timeMs) {
            line.endTimeMs
        } else if (nextLine != null) {
            nextLine.timeMs
        } else {
            line.timeMs + 3500L
        }

        val transitionStart = lineEnd - transitionWindowMs
        if (currentPositionMs < transitionStart) return 0f
        if (currentPositionMs >= lineEnd) return 1f
        return ((currentPositionMs - transitionStart).toFloat() / transitionWindowMs.toFloat()).coerceIn(0f, 1f)
    }
}
