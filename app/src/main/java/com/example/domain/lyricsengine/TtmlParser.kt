package com.example.domain.lyricsengine

import com.example.data.model.LyricLine
import com.example.data.model.ParsedLyrics
import com.example.data.model.WordTiming
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

object TtmlParser {

    fun parse(
        content: String,
        defaultTitle: String,
        defaultArtist: String,
        source: String
    ): ParsedLyrics {
        val lines = mutableListOf<LyricLine>()
        val title = defaultTitle
        val artist = defaultArtist

        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(content))

            var eventType = parser.eventType
            var currentLineStart = -1L
            var currentLineEnd = -1L
            val currentLineWords = mutableListOf<WordTiming>()
            val currentLineText = StringBuilder()

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name?.lowercase() ?: ""
                        when (name) {
                            "p" -> {
                                currentLineWords.clear()
                                currentLineText.clear()
                                val beginStr = parser.getAttributeValue(null, "begin")
                                val endStr = parser.getAttributeValue(null, "end")
                                currentLineStart = parseTimeToMs(beginStr)
                                currentLineEnd = parseTimeToMs(endStr)
                            }
                            "span" -> {
                                val beginStr = parser.getAttributeValue(null, "begin")
                                val endStr = parser.getAttributeValue(null, "end")
                                val startMs = if (beginStr != null) parseTimeToMs(beginStr) else currentLineStart
                                val endMs = if (endStr != null) parseTimeToMs(endStr) else currentLineEnd

                                val spanText = parser.nextText()?.trim() ?: ""
                                if (spanText.isNotEmpty()) {
                                    if (currentLineText.isNotEmpty()) currentLineText.append(" ")
                                    currentLineText.append(spanText)
                                    currentLineWords.add(WordTiming(spanText, startMs, endMs))
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val t = parser.text?.trim() ?: ""
                        if (t.isNotEmpty() && currentLineWords.isEmpty()) {
                            if (currentLineText.isNotEmpty()) currentLineText.append(" ")
                            currentLineText.append(t)
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name?.lowercase() ?: ""
                        if (name == "p" && currentLineText.isNotBlank()) {
                            val lineText = currentLineText.toString().trim()
                            val lineStart = if (currentLineStart >= 0) currentLineStart else 0L
                            val lineEnd = if (currentLineEnd >= 0) currentLineEnd else (lineStart + 3500L)
                            lines.add(
                                LyricLine(
                                    timeMs = lineStart,
                                    text = lineText,
                                    endTimeMs = lineEnd,
                                    words = currentLineWords.toList()
                                )
                            )
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {}

        lines.sortBy { it.timeMs }

        return ParsedLyrics(
            title = title,
            artist = artist,
            isSynced = lines.isNotEmpty(),
            lines = lines,
            plainText = lines.joinToString("\n") { it.text },
            source = source,
            rawContent = content
        )
    }

    private fun parseTimeToMs(timeStr: String?): Long {
        if (timeStr.isNullOrBlank()) return -1L
        try {
            val clean = timeStr.trim().removeSuffix("s")
            if (clean.contains(":")) {
                val parts = clean.split(":")
                if (parts.size == 2) {
                    val min = parts[0].toLong()
                    val sec = parts[1].toDouble()
                    return (min * 60_000L + (sec * 1000).toLong())
                } else if (parts.size == 3) {
                    val hr = parts[0].toLong()
                    val min = parts[1].toLong()
                    val sec = parts[2].toDouble()
                    return (hr * 3600_000L + min * 60_000L + (sec * 1000).toLong())
                }
            } else {
                return (clean.toDouble() * 1000).toLong()
            }
        } catch (_: Exception) {}
        return -1L
    }
}
