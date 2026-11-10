package com.example.domain.provider

import com.example.data.model.LyricLine
import java.util.Locale

/**
 * Shared recording validation, metadata normalization, and line-sync verification utilities
 * used across LyricBar's lyrics providers.
 */
object LyricsMatchingUtils {

    private val ISRC_FORMAT_REGEX = Regex("^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$")

    private val LIVE_REGEX = Regex("""(?i)(?:\b(?:live|concert)\b|演唱会|现场)""")
    private val REMIX_REGEX = Regex("""(?i)\b(?:remix|re-mix|rmx|bootleg|mashup|vip\s+mix|club\s+mix|extended\s+mix|dub\s+mix|dj\s+mix)\b""")
    private val ACOUSTIC_REGEX = Regex("""(?i)\b(?:acoustic|unplugged|stripped)\b""")
    private val COVER_REGEX = Regex("""(?i)(?:\b(?:cover|tribute)\b|翻唱)""")
    private val REMASTER_REGEX = Regex("""(?i)\b(?:remaster(?:ed)?)\b""")
    private val INSTRUMENTAL_REGEX = Regex("""(?i)(?:\b(?:instrumental|karaoke|off\s*vocal|backing\s+track)\b|伴奏|纯音乐)""")
    private val SPEED_REGEX = Regex("""(?i)\b(?:nightcore|sped\s+up|slowed|reverb|chopped)\b""")
    private val DEMO_REGEX = Regex("""(?i)\b(?:demo)\b""")
    private val ACAPELLA_REGEX = Regex("""(?i)\b(?:a\s*cappella|acapella)\b""")

    private val VERSION_MARKERS = listOf(
        LIVE_REGEX,
        REMIX_REGEX,
        ACOUSTIC_REGEX,
        COVER_REGEX,
        REMASTER_REGEX,
        INSTRUMENTAL_REGEX,
        SPEED_REGEX,
        DEMO_REGEX,
        ACAPELLA_REGEX
    )

    private val INLINE_WORD_TAG_REGEX = Regex("""<(?:\d{1,3}:\d{2}(?:[.:]\d{1,3})?|\d+,\d+(?:,\d+)?)>""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
    private val NON_ALPHANUMERIC_UNICODE_REGEX = Regex("""[^\p{L}\p{N}\s]""")
    private val BRACKETED_CONTENT_REGEX = Regex("""\s*[\(\[【].*?[\)\]】]""")
    private val ARTIST_SPLIT_REGEX = Regex("""(?i)(?:,|&|/|;|、|\bfeat\.?\b|\bft\.?\b|\bwith\b|\band\b|\bvs\.?\b|\bx\b)""")

    /**
     * Validates and normalizes an ISRC code (12 uppercase alphanumeric characters following ISO 3901:
     * 2 country letters + 3 alphanumeric registrant chars + 7 digits).
     */
    fun normalizeIsrc(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim()
            .replace("-", "")
            .replace(" ", "")
            .uppercase(Locale.US)
        if (cleaned.length != 12) return null
        if (!ISRC_FORMAT_REGEX.matches(cleaned)) return null
        return cleaned
    }

    /**
     * Checks that two track titles represent the same recording version, rejecting mismatches
     * across Live, Remix, Acoustic, Cover, Remastered, Instrumental, Sped Up/Slowed, Demo, or Acapella.
     */
    fun sameVersion(requestedTitle: String, candidateTitle: String): Boolean {
        for (marker in VERSION_MARKERS) {
            if (marker.containsMatchIn(requestedTitle) != marker.containsMatchIn(candidateTitle)) {
                return false
            }
        }
        return true
    }

    /**
     * Cleans track title for normalized text comparison after version compatibility has been verified.
     */
    fun cleanTrackTitle(title: String): String {
        if (title.isBlank()) return ""
        val stripped = title
            // Strip media/video wrapper tags
            .replace(
                Regex("""(?i)\s*[\(\[【](?:[^)\]]*\b(?:official\s+(?:video|audio|music\s+video|lyric\s+video)|lyric\s+video|video|audio|visuali[sz]er|hd|hq|4k|mv|full\s+song|explicit|clean|deluxe|bonus\s+track)\b[^)\]]*)[\)\]】]"""),
                ""
            )
            // Strip featuring blocks in brackets or trailing
            .replace(Regex("""(?i)\s*[\(\[【]\s*(?:feat\.?|ft\.?|featuring|with)\b[^)\]]*[\)\]】]"""), "")
            .replace(Regex("""(?i)\s+\b(?:feat\.?|ft\.?|featuring)\s+.*$"""), "")
            // Strip version brackets and dash suffixes so base titles compare cleanly once sameVersion passes
            .replace(
                Regex("""(?i)\s*[\(\[【](?:[^)\]]*\b(?:remaster(?:ed)?|live|concert|acoustic|unplugged|stripped|remix|re-mix|rmx|cover|tribute|radio\s+edit|single\s+edit|instrumental|karaoke|demo|nightcore|sped\s+up|slowed)\b[^)\]]*)[\)\]】]"""),
                ""
            )
            .replace(
                Regex("""(?i)\s*[-–—]\s*(?:\d{4}\s+)?(?:remaster(?:ed)?|live|acoustic|unplugged|remix|cover|radio\s+edit|single\s+edit|instrumental|demo|official\s+video|official\s+audio)\b.*$"""),
                ""
            )
            .replace(WHITESPACE_REGEX, " ")
            .trim()

        return stripped.ifBlank { title.trim() }
    }

    /**
     * Cleans artist name, removing platform suffixes such as YouTube's " - Topic".
     */
    fun cleanArtistName(artist: String): String {
        if (artist.isBlank()) return ""
        return artist
            .replace(Regex("""(?i)\s*[-–—]\s*topic$"""), "")
            .replace(Regex("""(?i)\s*[\(\[【]\s*official\s*[\)\]】]"""), "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    /**
     * Strips a leading "Artist - " prefix from a track title if present.
     */
    fun stripLeadingArtistPrefix(title: String, artist: String = ""): String {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return ""
        if (artist.isNotBlank()) {
            val cleanA = cleanArtistName(artist)
            val prefixRegex = Regex("""(?i)^\Q$cleanA\E\s*[-–—:]\s*(.+)$""")
            val match = prefixRegex.find(trimmed)
            if (match != null) {
                val remainder = match.groupValues[1].trim()
                if (remainder.isNotBlank()) return remainder
            }
        }
        val dashIdx = trimmed.indexOf(" - ")
        if (dashIdx > 0 && dashIdx + 3 < trimmed.length) {
            val remainder = trimmed.substring(dashIdx + 3).trim()
            if (remainder.isNotBlank()) return remainder
        }
        return trimmed
    }

    /**
     * Normalizes a string for alphanumeric comparison across all Unicode scripts.
     */
    private fun normalizeForComparison(input: String): String {
        return input
            .lowercase(Locale.ROOT)
            .replace(NON_ALPHANUMERIC_UNICODE_REGEX, " ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    /**
     * Evaluates whether two cleaned titles represent the same song title.
     */
    fun titlesMatch(title1: String, title2: String): Boolean {
        val norm1 = normalizeForComparison(cleanTrackTitle(title1))
        val norm2 = normalizeForComparison(cleanTrackTitle(title2))
        if (norm1.isBlank() || norm2.isBlank()) return false
        if (norm1 == norm2) return true

        val base1 = normalizeForComparison(cleanTrackTitle(title1).replace(BRACKETED_CONTENT_REGEX, ""))
        val base2 = normalizeForComparison(cleanTrackTitle(title2).replace(BRACKETED_CONTENT_REGEX, ""))
        if (base1.isNotBlank() && base2.isNotBlank() && base1 == base2) {
            return true
        }
        return false
    }

    /**
     * Evaluates whether two artist strings share at least one matching primary/collaborating artist.
     */
    fun artistMatches(artist1: String, artist2: String): Boolean {
        val clean1 = cleanArtistName(artist1)
        val clean2 = cleanArtistName(artist2)
        if (clean1.isBlank() || clean2.isBlank()) return false
        if (clean1.equals("unknown artist", ignoreCase = true) || clean2.equals("unknown artist", ignoreCase = true)) {
            return false
        }

        val fullNorm1 = normalizeForComparison(clean1)
        val fullNorm2 = normalizeForComparison(clean2)
        if (fullNorm1.isBlank() || fullNorm2.isBlank()) return false
        if (fullNorm1 == fullNorm2) return true

        val tokens1 = clean1.split(ARTIST_SPLIT_REGEX)
            .map { normalizeForComparison(it) }
            .filter { it.isNotBlank() }
        val tokens2 = clean2.split(ARTIST_SPLIT_REGEX)
            .map { normalizeForComparison(it) }
            .filter { it.isNotBlank() }

        for (t1 in tokens1) {
            for (t2 in tokens2) {
                if (t1 == t2) return true
                val minLen = minOf(t1.length, t2.length)
                if (minLen >= 4 && (t1.contains(t2) || t2.contains(t1))) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Removes any inline word-sync or syllable-sync angle-bracket tags from a lyric line's text.
     */
    fun stripInlineWordTags(text: String): String {
        if (!text.contains('<')) return text.trim()
        return text.replace(INLINE_WORD_TAG_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
    }

    /**
     * Validates that a list of [LyricLine] contains usable, chronologically ordered,
     * standard line-synchronized lyrics with plausible timestamps relative to [durationSec].
     */
    fun hasValidSyncedLines(lines: List<LyricLine>, durationSec: Int? = null): Boolean {
        if (lines.isEmpty()) return false

        val nonBlankLines = lines.filter { line ->
            val t = line.text.trim()
            t.isNotBlank() && !t.equals("null", ignoreCase = true) && !t.equals("undefined", ignoreCase = true)
        }
        if (nonBlankLines.isEmpty()) return false

        // All timestamps must be non-negative and within a sane upper bound (10 hours)
        for (line in lines) {
            if (line.timeMs < 0L || line.timeMs > 36_000_000L) {
                return false
            }
        }

        // Reject fabricated all-zero timestamps when multiple lyric lines exist
        if (nonBlankLines.size >= 2 && nonBlankLines.all { it.timeMs == 0L }) {
            return false
        }

        // Verify chronological ordering
        for (i in 0 until lines.size - 1) {
            if (lines[i].timeMs > lines[i + 1].timeMs) {
                return false
            }
        }

        // Validate timestamp plausibility against track duration when available
        if (durationSec != null && durationSec > 0) {
            val durationMs = durationSec * 1000L
            val firstTimeMs = nonBlankLines.first().timeMs
            val lastTimeMs = nonBlankLines.last().timeMs
            val maxAllowedMs = durationMs + maxOf(60_000L, durationMs / 2)

            if (firstTimeMs > durationMs + 5_000L || lastTimeMs > maxAllowedMs) {
                return false
            }
        }

        return true
    }
}
