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
    val format: String = if (isWordSynced) "TTML" else "LRC",
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
        return activeIndex
    }

    fun getActiveLine(currentPositionMs: Long, trackOffsetMs: Long = 0L): String? {
        val idx = findActiveIndex(currentPositionMs, trackOffsetMs)
        return if (idx in lines.indices) lines[idx].text else null
    }

    fun getActiveLyricLine(currentPositionMs: Long, trackOffsetMs: Long = 0L): LyricLine? {
        val idx = findActiveIndex(currentPositionMs, trackOffsetMs)
        return if (idx in lines.indices) lines[idx] else null
    }
}
