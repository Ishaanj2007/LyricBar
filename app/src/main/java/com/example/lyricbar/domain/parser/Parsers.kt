package com.example.lyricbar.domain.parser

import com.example.lyricbar.data.model.LyricLine
import com.example.lyricbar.data.model.WordTimestamp
import java.util.regex.Pattern

object LrcParser {
    // Regex for standard LRC lines: [mm:ss.xx]Text or [mm:ss:xx]Text
    private val TIMESTAMP_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})[.:](\\d{2,3})\\]")
    // Regex for inline word timestamps: <mm:ss.xx>Word
    private val WORD_PATTERN = Pattern.compile("<(\\d{2}):(\\d{2})[.:](\\d{2,3})>([^<]*)")

    fun parse(lrcContent: String): List<LyricLine> {
        val lines = lrcContent.lines()
        val result = mutableListOf<LyricLine>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("[ti:") || trimmed.startsWith("[ar:") || trimmed.startsWith("[al:")) continue

            val matcher = TIMESTAMP_PATTERN.matcher(trimmed)
            if (matcher.find()) {
                val min = matcher.group(1)?.toLongOrNull() ?: 0L
                val sec = matcher.group(2)?.toLongOrNull() ?: 0L
                val msPart = matcher.group(3) ?: "0"
                val ms = if (msPart.length == 2) msPart.toLong() * 10 else msPart.toLong()
                val startTimeMs = (min * 60 + sec) * 1000 + ms

                val lyricText = trimmed.substring(matcher.end()).trim()
                
                // Parse word-level timestamps if present
                val words = mutableListOf<WordTimestamp>()
                val wordMatcher = WORD_PATTERN.matcher(lyricText)
                var plainText = lyricText
                
                if (wordMatcher.find()) {
                    wordMatcher.reset()
                    val cleanTextBuilder = StringBuilder()
                    while (wordMatcher.find()) {
                        val wMin = wordMatcher.group(1)?.toLongOrNull() ?: 0L
                        val wSec = wordMatcher.group(2)?.toLongOrNull() ?: 0L
                        val wMsPart = wordMatcher.group(3) ?: "0"
                        val wMs = if (wMsPart.length == 2) wMsPart.toLong() * 10 else wMsPart.toLong()
                        val wStartTime = (wMin * 60 + wSec) * 1000 + wMs
                        val wordStr = wordMatcher.group(4) ?: ""
                        
                        words.add(WordTimestamp(wStartTime, wordStr))
                        cleanTextBuilder.append(wordStr)
                    }
                    plainText = cleanTextBuilder.toString()
                }

                result.add(LyricLine(startTimeMs = startTimeMs, text = plainText, wordTimestamps = words))
            }
        }

        // Sort by start time and compute end times
        val sorted = result.sortedBy { it.startTimeMs }
        val finalLines = mutableListOf<LyricLine>()
        for (i in sorted.indices) {
            val current = sorted[i]
            val nextStart = if (i < sorted.size - 1) sorted[i + 1].startTimeMs else current.startTimeMs + 4000L
            finalLines.add(current.copy(endTimeMs = nextStart))
        }

        return finalLines
    }
}

object TtmlParser {
    fun parse(ttmlContent: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        // Simple XML tag regex for <p begin="00:01:23.456" end="...">Text</p>
        val pPattern = Pattern.compile("<p[^>]*begin=\"([^\"]+)\"[^>]*>(.*?)</p>", Pattern.DOTALL)
        val matcher = pPattern.matcher(ttmlContent)

        while (matcher.find()) {
            val beginStr = matcher.group(1) ?: ""
            val rawText = matcher.group(2) ?: ""
            val cleanText = rawText.replace(Regex("<[^>]*>"), "").trim()

            val startTimeMs = parseTimestampToMs(beginStr)
            if (cleanText.isNotEmpty()) {
                lines.add(LyricLine(startTimeMs = startTimeMs, text = cleanText))
            }
        }

        val sorted = lines.sortedBy { it.startTimeMs }
        val finalLines = mutableListOf<LyricLine>()
        for (i in sorted.indices) {
            val current = sorted[i]
            val nextStart = if (i < sorted.size - 1) sorted[i + 1].startTimeMs else current.startTimeMs + 4000L
            finalLines.add(current.copy(endTimeMs = nextStart))
        }
        return finalLines
    }

    private fun parseTimestampToMs(timeStr: String): Long {
        return try {
            if (timeStr.contains(":")) {
                val parts = timeStr.split(":")
                if (parts.size == 3) {
                    val hours = parts[0].toLong()
                    val minutes = parts[1].toLong()
                    val secParts = parts[2].split(".")
                    val seconds = secParts[0].toLong()
                    val ms = if (secParts.size > 1) secParts[1].take(3).padEnd(3, '0').toLong() else 0L
                    ((hours * 3600 + minutes * 60 + seconds) * 1000) + ms
                } else if (parts.size == 2) {
                    val minutes = parts[0].toLong()
                    val secParts = parts[1].split(".")
                    val seconds = secParts[0].toLong()
                    val ms = if (secParts.size > 1) secParts[1].take(3).padEnd(3, '0').toLong() else 0L
                    ((minutes * 60 + seconds) * 1000) + ms
                } else 0L
            } else {
                timeStr.replace("s", "").toDouble().toLong() * 1000L
            }
        } catch (e: Exception) {
            0L
        }
    }
}
