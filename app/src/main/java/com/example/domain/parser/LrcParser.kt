package com.example.domain.parser

import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics

object LrcParser {

    private val TIMESTAMP_REGEX = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    private val OFFSET_REGEX = Regex("\\[offset:\\s*([+-]?\\d+)\\]", RegexOption.IGNORE_CASE)

    fun isTtml(content: String): Boolean {
        val trimmed = content.trim()
        return (trimmed.startsWith("<") && (trimmed.contains("<tt", ignoreCase = true) || trimmed.contains("xmlns", ignoreCase = true))) ||
                content.contains("http://www.w3.org/ns/ttml", ignoreCase = true) ||
                content.contains("xmlns:ttm=", ignoreCase = true)
    }

    fun parse(
        content: String,
        title: String = "",
        artist: String = "",
        source: String = "LRC"
    ): ParsedLyrics {
        if (isTtml(content)) {
            throw IllegalArgumentException("Unsupported format: TTML is not supported. Please use LRC.")
        }
        val lines = content.lines()
        val parsedLines = mutableListOf<LyricLine>()
        var offsetMs = 0L

        // First pass: look for offset tags
        for (line in lines) {
            val offsetMatch = OFFSET_REGEX.find(line)
            if (offsetMatch != null) {
                offsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
            }
        }

        // Second pass: extract lyrics lines
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // Skip metadata tags like [ti:], [ar:], [al:], [by:], [offset:]
            if (trimmed.startsWith("[") && trimmed.contains(":") && !trimmed.contains(TIMESTAMP_REGEX)) {
                continue
            }

            val timestampMatches = TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (timestampMatches.isNotEmpty()) {
                // Extract lyric text after stripping all timestamps
                val text = trimmed.replace(TIMESTAMP_REGEX, "").trim()

                for (match in timestampMatches) {
                    val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                    val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                    val fractionStr = match.groupValues[3]

                    val millis = when (fractionStr.length) {
                        0 -> 0L
                        1 -> fractionStr.toLong() * 100L
                        2 -> fractionStr.toLong() * 10L
                        3 -> fractionStr.toLong()
                        else -> fractionStr.take(3).toLong()
                    }

                    val totalTimeMs = (minutes * 60L + seconds) * 1000L + millis
                    parsedLines.add(LyricLine(timeMs = totalTimeMs, text = text))
                }
            }
        }

        return if (parsedLines.isNotEmpty()) {
            parsedLines.sortBy { it.timeMs }
            ParsedLyrics(
                title = title,
                artist = artist,
                isSynced = true,
                lines = parsedLines,
                plainText = parsedLines.joinToString("\n") { it.text },
                source = source,
                offsetMs = offsetMs,
                rawContent = content
            )
        } else {
            // Plain text lyrics fallback
            val plainLines = lines
                .map { it.trim() }
                .filter { trimmed ->
                    trimmed.isNotEmpty() && !(trimmed.startsWith("[") && trimmed.contains(":") && !trimmed.contains(TIMESTAMP_REGEX))
                }

            ParsedLyrics(
                title = title,
                artist = artist,
                isSynced = false,
                lines = plainLines.map { txt -> LyricLine(timeMs = 0L, text = txt) },
                plainText = if (plainLines.isNotEmpty()) plainLines.joinToString("\n") else content.trim(),
                source = source,
                offsetMs = 0L,
                rawContent = content
            )
        }
    }
}
