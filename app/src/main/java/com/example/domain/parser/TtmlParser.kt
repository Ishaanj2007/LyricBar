package com.example.domain.parser

import com.example.data.model.LyricLine
import com.example.data.model.LyricsAttribution
import com.example.data.model.ParsedLyrics
import com.example.domain.provider.LyricsMatchingUtils
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Safely parses compatible synchronized TTML documents (e.g., Unison community richsync/linesync TTML)
 * into LyricBar's standard line-synchronized [ParsedLyrics] / LRC representation.
 *
 * Rejects malformed XML, unsynchronized/missing timestamps, `<head>`/`<metadata>` leakage,
 * translation/romanization helper spans, and out-of-range timings without crashing.
 */
object TtmlParser {

    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val COLON_TIME_REGEX = Regex("""^(?:(\d+):)?(\d{1,3}):(\d{2})(?:\.(\d+))?$""")
    private val SECONDS_TIME_REGEX = Regex("""^(\d+(?:\.\d+)?)s$""", RegexOption.IGNORE_CASE)
    private val MILLIS_TIME_REGEX = Regex("""^(\d+(?:\.\d+)?)ms$""", RegexOption.IGNORE_CASE)
    private val MINUTES_TIME_REGEX = Regex("""^(\d+(?:\.\d+)?)m$""", RegexOption.IGNORE_CASE)

    fun parseToLineSynced(
        ttmlXml: String,
        title: String,
        artist: String,
        source: String,
        durationSec: Int? = null,
        attribution: LyricsAttribution? = null
    ): Result<ParsedLyrics> {
        val raw = ttmlXml.removePrefix("\uFEFF").trim()
        if (raw.isEmpty() || !raw.startsWith("<")) {
            return Result.failure(IllegalArgumentException("Empty or non-XML TTML content"))
        }

        val document = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeatureSafely("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeatureSafely("http://xml.org/sax/features/external-general-entities", false)
                setFeatureSafely("http://xml.org/sax/features/external-parameter-entities", false)
                try {
                    isXIncludeAware = false
                } catch (_: Exception) {}
                try {
                    setExpandEntityReferences(false)
                } catch (_: Exception) {}
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(raw)))
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Malformed TTML document: ${e.message}", e))
        }

        val root = document.documentElement
            ?: return Result.failure(IllegalArgumentException("Missing root element in TTML document"))
        if (!localNodeName(root).equals("tt", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Root XML element is not <tt>"))
        }

        val bodyElements = collectElementsByLocalName(root, "body")
        if (bodyElements.isEmpty()) {
            return Result.failure(IllegalArgumentException("TTML document contains no <body> element"))
        }

        val lyricLines = mutableListOf<LyricLine>()
        for (body in bodyElements) {
            val pElements = collectElementsByLocalName(body, "p")
            for (pElement in pElements) {
                val beginAttr = findAttributeValue(pElement, "begin")
                    ?: findFirstTimedSpanBegin(pElement)
                    ?: continue
                val timeMs = parseTimeExpressionMs(beginAttr) ?: continue

                val extracted = extractVisibleLineText(pElement)
                    .replace(WHITESPACE_REGEX, " ")
                    .trim()

                if (extracted.isEmpty() ||
                    extracted.equals("null", ignoreCase = true) ||
                    extracted.equals("undefined", ignoreCase = true)
                ) {
                    continue
                }

                lyricLines.add(
                    LyricLine(
                        timeMs = timeMs,
                        text = extracted,
                        endTimeMs = 0L,
                        words = emptyList(),
                        isWordSynced = false
                    )
                )
            }
        }

        val sortedLines = lyricLines.sortedBy { it.timeMs }
        if (!LyricsMatchingUtils.hasValidSyncedLines(sortedLines, durationSec)) {
            return Result.failure(NoSuchElementException("TTML contained no valid synchronized lyric lines"))
        }

        val plainText = sortedLines.joinToString("\n") { it.text }
        val formattedLrc = LrcParser.formatLrc(
            lines = sortedLines,
            offsetMs = 0L,
            attribution = attribution
        )

        return Result.success(
            ParsedLyrics(
                title = title,
                artist = artist,
                isSynced = true,
                lines = sortedLines,
                plainText = plainText,
                source = source,
                offsetMs = 0L,
                isWordSynced = false,
                format = "LRC",
                rawContent = formattedLrc,
                attribution = attribution
            )
        )
    }

    internal fun parseTimeExpressionMs(rawExpr: String?): Long? {
        val expr = rawExpr?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        COLON_TIME_REGEX.matchEntire(expr)?.let { match ->
            val hoursGroup = match.groupValues[1]
            val minutesOrHours = match.groupValues[2].toLongOrNull() ?: return null
            val seconds = match.groupValues[3].toLongOrNull() ?: return null
            val fractionRaw = match.groupValues[4]

            if (seconds !in 0L..59L) return null
            val (hours, minutes) = if (hoursGroup.isNotEmpty()) {
                val h = hoursGroup.toLongOrNull() ?: return null
                if (minutesOrHours !in 0L..59L) return null
                h to minutesOrHours
            } else {
                0L to minutesOrHours
            }

            val millis = when (fractionRaw.length) {
                0 -> 0L
                1 -> (fractionRaw.toLongOrNull() ?: return null) * 100L
                2 -> (fractionRaw.toLongOrNull() ?: return null) * 10L
                else -> fractionRaw.take(3).toLongOrNull() ?: return null
            }

            val totalMs = ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L + millis
            return totalMs.takeIf { it in 0L..36_000_000L }
        }

        SECONDS_TIME_REGEX.matchEntire(expr)?.let { match ->
            val sec = match.groupValues[1].toDoubleOrNull() ?: return null
            val ms = Math.round(sec * 1000.0)
            return ms.takeIf { it in 0L..36_000_000L }
        }

        MILLIS_TIME_REGEX.matchEntire(expr)?.let { match ->
            val ms = match.groupValues[1].toDoubleOrNull()?.let { Math.round(it) } ?: return null
            return ms.takeIf { it in 0L..36_000_000L }
        }

        MINUTES_TIME_REGEX.matchEntire(expr)?.let { match ->
            val mins = match.groupValues[1].toDoubleOrNull() ?: return null
            val ms = Math.round(mins * 60_000.0)
            return ms.takeIf { it in 0L..36_000_000L }
        }

        return null
    }

    private fun extractVisibleLineText(node: Node): String {
        val sb = StringBuilder()
        val children = node.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            when (child.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    val value = child.nodeValue
                    if (!value.isNullOrEmpty()) {
                        // Preserve exact single spacing between sibling <span> tokens without collapsing internal spaces prematurely
                        sb.append(value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' '))
                    }
                }
                Node.ELEMENT_NODE -> {
                    val el = child as Element
                    val name = localNodeName(el).lowercase()
                    if (name == "br") {
                        sb.append(' ')
                    } else if (name == "metadata" || name == "head") {
                        continue
                    } else {
                        val role = findAttributeValue(el, "role")?.lowercase()
                        if (role == "x-translation" || role == "x-roman" || role == "x-metadata") {
                            continue
                        }
                        sb.append(extractVisibleLineText(el))
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun findFirstTimedSpanBegin(pElement: Element): String? {
        val spans = collectElementsByLocalName(pElement, "span")
        for (span in spans) {
            val role = findAttributeValue(span, "role")?.lowercase()
            if (role == "x-translation" || role == "x-roman" || role == "x-metadata") continue
            val begin = findAttributeValue(span, "begin")
            if (!begin.isNullOrBlank()) return begin
        }
        return null
    }

    private fun collectElementsByLocalName(root: Element, targetLocalName: String): List<Element> {
        val matches = mutableListOf<Element>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                val el = child as Element
                if (localNodeName(el).equals(targetLocalName, ignoreCase = true)) {
                    matches.add(el)
                }
                matches.addAll(collectElementsByLocalName(el, targetLocalName))
            }
        }
        return matches
    }

    private fun localNodeName(node: Node): String {
        return node.localName?.takeIf { it.isNotBlank() }
            ?: node.nodeName.substringAfter(':')
    }

    private fun findAttributeValue(element: Element, attrLocalName: String): String? {
        val attrs = element.attributes ?: return null
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i)
            val local = attr.localName?.takeIf { it.isNotBlank() }
                ?: attr.nodeName.substringAfter(':')
            if (local.equals(attrLocalName, ignoreCase = true)) {
                return attr.nodeValue
            }
        }
        return null
    }

    private fun DocumentBuilderFactory.setFeatureSafely(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: Exception) {
            // Some Android XML parser implementations ignore optional Xerces features cleanly
        }
    }
}
