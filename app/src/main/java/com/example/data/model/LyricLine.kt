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
    val hasValidLyrics: Boolean
        get() {
            val hasValidLines = lines.isNotEmpty() && lines.any { line ->
                val t = line.text.trim()
                t.isNotBlank() && !t.equals("null", ignoreCase = true) && !t.equals("undefined", ignoreCase = true)
            }
            if (hasValidLines) return true
            val pt = plainText.trim()
            return pt.isNotBlank() && !pt.equals("null", ignoreCase = true) && !pt.equals("undefined", ignoreCase = true)
        }

    private val isSorted: Boolean = if (lines.size <= 1) {
        true
    } else {
        var sorted = true
        for (i in 0 until lines.size - 1) {
            if (lines[i].timeMs > lines[i + 1].timeMs) {
                sorted = false
                break
            }
        }
        sorted
    }

    fun findActiveIndex(currentPositionMs: Long, trackOffsetMs: Long = 0L): Int {
        if (!isSynced || lines.isEmpty() || !hasValidLyrics) return -1
        val adjustedPos = currentPositionMs + offsetMs + trackOffsetMs
        var activeIndex = -1

        if (isSorted) {
            var low = 0
            var high = lines.size - 1
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (lines[mid].timeMs <= adjustedPos) {
                    activeIndex = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
        } else {
            for (i in lines.indices) {
                if (lines[i].timeMs <= adjustedPos) {
                    activeIndex = i
                } else {
                    break
                }
            }
        }

        if (activeIndex != -1) {
            val line = lines[activeIndex]
            val lineTextTrimmed = line.text.trim()
            if (lineTextTrimmed.isBlank() || lineTextTrimmed.equals("null", ignoreCase = true)) return -1
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
        if (idx in lines.indices) {
            val t = lines[idx].text.trim()
            return if (t.isNotBlank() && !t.equals("null", ignoreCase = true)) t else null
        }
        if (!isSynced && lines.isNotEmpty()) {
            val valid = lines.firstOrNull {
                val t = it.text.trim()
                t.isNotBlank() && !t.equals("null", ignoreCase = true)
            }?.text?.trim()
            return valid ?: plainText.trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
        }
        return null
    }

    fun getActiveLyricLine(currentPositionMs: Long, trackOffsetMs: Long = 0L): LyricLine? {
        val idx = findActiveIndex(currentPositionMs, trackOffsetMs)
        if (idx in lines.indices) {
            val line = lines[idx]
            val t = line.text.trim()
            return if (t.isNotBlank() && !t.equals("null", ignoreCase = true)) line else null
        }
        if (!isSynced && lines.isNotEmpty()) {
            val firstValid = lines.firstOrNull {
                val t = it.text.trim()
                t.isNotBlank() && !t.equals("null", ignoreCase = true)
            }
            return firstValid ?: if (plainText.isNotBlank() && !plainText.trim().equals("null", ignoreCase = true)) {
                LyricLine(timeMs = 0L, text = plainText.trim())
            } else null
        }
        return null
    }
}
