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
        val cleanContent = content.removePrefix("\uFEFF")
        val trimmedContent = cleanContent.trim()
        if (trimmedContent.isEmpty() ||
            trimmedContent.equals("null", ignoreCase = true) ||
            trimmedContent.equals("undefined", ignoreCase = true)
        ) {
            return ParsedLyrics(
                title = title,
                artist = artist,
                isSynced = false,
                lines = emptyList(),
                plainText = "",
                source = source,
                offsetMs = 0L,
                rawContent = ""
            )
        }

        if (isTtml(cleanContent)) {
            throw IllegalArgumentException("Unsupported format: TTML is not supported. Please use LRC.")
        }
        val lines = cleanContent.lines()
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
                val cleanText = if (text.equals("null", ignoreCase = true) || text.equals("undefined", ignoreCase = true)) "" else text

                for (match in timestampMatches) {
                    val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                    val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                    val fractionStr = match.groupValues[3]

                    // Range validation: seconds must be 0..59, minutes 0..599
                    if (seconds !in 0..59 || minutes !in 0..599) {
                        continue
                    }

                    val millis = when (fractionStr.length) {
                        0 -> 0L
                        1 -> fractionStr.toLong() * 100L
                        2 -> fractionStr.toLong() * 10L
                        3 -> fractionStr.toLong()
                        else -> fractionStr.take(3).toLong()
                    }

                    val totalTimeMs = (minutes * 60L + seconds) * 1000L + millis
                    parsedLines.add(LyricLine(timeMs = totalTimeMs, text = cleanText))
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
                    trimmed.isNotEmpty() &&
                            !trimmed.equals("null", ignoreCase = true) &&
                            !trimmed.equals("undefined", ignoreCase = true) &&
                            !(trimmed.startsWith("[") && trimmed.contains(":") && !trimmed.contains(TIMESTAMP_REGEX))
                }

            ParsedLyrics(
                title = title,
                artist = artist,
                isSynced = false,
                lines = plainLines.map { txt -> LyricLine(timeMs = 0L, text = txt) },
                plainText = if (plainLines.isNotEmpty()) plainLines.joinToString("\n") else "",
                source = source,
                offsetMs = 0L,
                rawContent = if (plainLines.isNotEmpty()) content else ""
            )
        }
    }

    fun hasTimestampTags(content: String): Boolean {
        return TIMESTAMP_REGEX.containsMatchIn(content)
    }

    fun formatLrc(lines: List<LyricLine>, offsetMs: Long = 0L): String {
        if (lines.isEmpty()) return ""
        val sb = StringBuilder()
        if (offsetMs != 0L) {
            sb.append("[offset:").append(offsetMs).append("]\n")
        }
        lines.forEachIndexed { index, line ->
            val totalMs = line.timeMs.coerceAtLeast(0L)
            val minutes = totalMs / 60_000L
            val seconds = (totalMs % 60_000L) / 1000L
            val millis = totalMs % 1000L
            sb.append(
                String.format(
                    java.util.Locale.US,
                    "[%02d:%02d.%03d]%s",
                    minutes,
                    seconds,
                    millis,
                    line.text
                )
            )
            if (index < lines.lastIndex) sb.append('\n')
        }
        return sb.toString()
    }
}
