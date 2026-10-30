package com.example.data.model

data class WordTiming(
    val word: String,
    val startMs: Long,
    val endMs: Long,
    val translation: String? = null
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val endTimeMs: Long = 0L,
    val words: List<WordTiming> = emptyList(),
    val translation: String? = null,
    val isWordSynced: Boolean = words.isNotEmpty()
)

data class ParsedLyrics(
    val title: String,
    val artist: String,
    val isSynced: Boolean,
    val lines: List<LyricLine>,
    val plainText: String,
    val source: String,
    val offsetMs: Long = 0L,
    val isWordSynced: Boolean = lines.any { it.isWordSynced },
    val format: String = if (isSynced) "LRC" else "PLAIN",
    val rawContent: String = ""
) {
    fun findActiveIndex(currentPositionMs: Long, trackOffsetMs: Long = 0L): Int {
        if (!isSynced || lines.isEmpty()) return -1
        val adjustedPos = currentPositionMs + offsetMs + trackOffsetMs
        var activeIndex = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= adjustedPos) {
                activeIndex = i
            } else {
                break
            }
        }
        if (activeIndex != -1) {
            val line = lines[activeIndex]
            if (line.text.isBlank()) return -1
            val effectiveEndMs = if (line.endTimeMs > line.timeMs) {
                line.endTimeMs
            } else if (line.words.isNotEmpty()) {
                line.words.maxOf { it.endMs }
            } else {
                0L
            }
            if (effectiveEndMs > line.timeMs && adjustedPos >= effectiveEndMs) {
                return -1
            }
        }
        return activeIndex
    }

    fun getActiveLine(currentPositionMs: Long, trackOffsetMs: Long = 0L): String? {
        val idx = findActiveIndex(currentPositionMs, trackOffsetMs)
        if (idx in lines.indices) return lines[idx].text.ifBlank { null }
        if (!isSynced && lines.isNotEmpty()) {
            return lines.firstOrNull { it.text.isNotBlank() }?.text ?: plainText.ifBlank { null }
        }
        return null
    }

    fun getActiveLyricLine(currentPositionMs: Long, trackOffsetMs: Long = 0L): LyricLine? {
        val idx = findActiveIndex(currentPositionMs, trackOffsetMs)
        if (idx in lines.indices) {
            val line = lines[idx]
            return if (line.text.isNotBlank()) line else null
        }
        if (!isSynced && lines.isNotEmpty()) {
            val firstNonBlank = lines.firstOrNull { it.text.isNotBlank() }
            return firstNonBlank ?: if (plainText.isNotBlank()) LyricLine(timeMs = 0L, text = plainText.trim()) else null
        }
        return null
    }
}
