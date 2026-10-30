package com.example.domain.lyricsengine

import com.example.data.model.LyricLine
import com.example.data.model.WordTiming

object LyricsTimingEngine {

    /**
     * Calculates progressive completion for an individual word (0.0f to 1.0f).
     * Returns 0f if the word hasn't started yet,
     * Returns 1f if the word has completed,
     * Returns smooth linear ratio between startMs and endMs while the word is being sung,
     * scaled by speedMultiplier (0.5x to 1.5x, default 1.0x).
     */
    fun calculateWordProgress(
        word: WordTiming,
        currentPositionMs: Long,
        speedMultiplier: Float = 1.0f
    ): Float {
        return calculateWordGlowProgress(word.startMs, word.endMs, currentPositionMs, speedMultiplier)
    }

    /**
     * Calculates progressive fill for word bounds with glow sweep speed scaling.
     */
    fun calculateWordGlowProgress(
        startMs: Long,
        endMs: Long,
        currentPositionMs: Long,
        speedMultiplier: Float = 1.0f
    ): Float {
        if (currentPositionMs <= startMs) return 0f
        if (currentPositionMs >= endMs) return 1f
        val duration = (endMs - startMs).coerceAtLeast(1L)
        val speed = speedMultiplier.coerceIn(0.5f, 1.5f)
        val elapsed = (currentPositionMs - startMs).toFloat()
        return if (speed >= 1.0f) {
            val effectiveDuration = (duration / speed.toDouble()).toLong().coerceAtLeast(1L)
            (elapsed / effectiveDuration.toFloat()).coerceIn(0f, 1f)
        } else {
            // In Slow (0.5x) and Relaxed (0.7x) mode, smoothly pace across the entire word to reach 1.0f at endMs
            val rawFraction = (elapsed / duration.toFloat()).coerceIn(0f, 1f)
            val exponent = 1.0 + (1.0 - speed.toDouble()) * 0.7
            java.lang.Math.pow(rawFraction.toDouble(), exponent).toFloat().coerceIn(0f, 1f)
        }
    }

    /**
     * Calculates line completion progress based on line duration (0.0f to 1.0f).
     * Useful for LRC fallback or line progress bars, scaled by speedMultiplier (0.5x to 1.5x).
     */
    fun calculateLineProgress(
        line: LyricLine,
        nextLine: LyricLine?,
        currentPositionMs: Long,
        speedMultiplier: Float = 1.0f
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
        val speed = speedMultiplier.coerceIn(0.5f, 1.5f)
        val elapsed = (currentPositionMs - start).toFloat()
        return if (speed >= 1.0f) {
            val effectiveDuration = (duration / speed.toDouble()).toLong().coerceAtLeast(1L)
            (elapsed / effectiveDuration.toFloat()).coerceIn(0f, 1f)
        } else {
            // In Slow (0.5x) and Relaxed (0.7x) mode, smoothly pace across the line reaching 1.0f at the end
            val rawFraction = (elapsed / duration.toFloat()).coerceIn(0f, 1f)
            val exponent = 1.0 + (1.0 - speed.toDouble()) * 0.7
            java.lang.Math.pow(rawFraction.toDouble(), exponent).toFloat().coerceIn(0f, 1f)
        }
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

    /**
     * Calculates playback-synchronized horizontal scroll progress for long lyrics (0.0f to 1.0f).
     *
     * @param currentLineStartMs Timestamp of the currently playing lyric line.
     * @param nextTimestampMs Timestamp of the subsequent lyric line, or null if this is the final line.
     * @param trackDurationMs Total duration of the track in milliseconds, or null if unknown.
     * @param currentPositionMs Current media playback position in milliseconds.
     * @param speedMultiplier User speed multiplier between 0.5x and 1.5x (default 1.0x).
     * @return Normalized scroll fraction from 0f (start of line) to 1f (fully scrolled).
     */
    fun calculateAdaptiveScrollProgress(
        currentLineStartMs: Long,
        nextTimestampMs: Long?,
        trackDurationMs: Long?,
        currentPositionMs: Long,
        speedMultiplier: Float = 1.0f
    ): Float {
        if (currentPositionMs <= currentLineStartMs) return 0f

        // Compute available interval based on next valid timestamp or remaining track fallback
        val intervalMs = if (nextTimestampMs != null && nextTimestampMs > currentLineStartMs) {
            nextTimestampMs - currentLineStartMs
        } else if (trackDurationMs != null && trackDurationMs > currentLineStartMs) {
            val remainingMs = trackDurationMs - currentLineStartMs
            remainingMs.coerceIn(1000L, 4000L)
        } else {
            4000L
        }.coerceAtLeast(100L)

        val elapsedMs = (currentPositionMs - currentLineStartMs).coerceAtLeast(0L)
        val rawFraction = (elapsedMs.toFloat() / intervalMs.toFloat()).coerceIn(0f, 1f)
        val multiplier = speedMultiplier.coerceIn(1.0f, 1.5f)

        return (rawFraction * multiplier).coerceIn(0f, 1f)
    }
}
