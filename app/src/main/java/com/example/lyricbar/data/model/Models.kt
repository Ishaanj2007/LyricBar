package com.example.lyricbar.data.model

data class Track(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val currentPositionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val albumArtUri: String? = null
) {
    val isAvailable: Boolean
        get() = title.isNotBlank() && title != "Unknown Title"
}

data class LyricLine(
    val startTimeMs: Long,
    val endTimeMs: Long = startTimeMs + 3000L,
    val text: String,
    val wordTimestamps: List<WordTimestamp> = emptyList()
)

data class WordTimestamp(
    val startTimeMs: Long,
    val text: String
)

enum class LyricFormat(val extension: String, val displayName: String, val searchKeyword: String) {
    LRC(".lrc", "LRC (Synced)", "LRC lyrics file download"),
    TTML(".ttml", "TTML (Timed Text)", "TTML lyrics file download"),
    SRT(".srt", "SRT (Subtitles)", "SRT lyrics file download"),
    TXT(".txt", "TXT (Plain Text)", "TXT lyrics")
}

enum class AnimationType(val displayName: String) {
    KARAOKE("Apple Karaoke"),
    SMOOTH_LINE("Smooth Line"),
    FADE_IN_OUT("Fade In / Out"),
    BLUR_REVEAL("Blur Reveal"),
    TYPEWRITER("Typewriter"),
    INSTANT("Instant")
}

data class FontOption(
    val id: String,
    val name: String,
    val sampleText: String = "Synchronized Floating Lyrics"
) {
    companion object {
        val ALL_FONTS = listOf(
            FontOption("sans_serif", "Sans Serif (Default)"),
            FontOption("serif", "Serif Elegant"),
            FontOption("monospace", "Monospace Tech"),
            FontOption("cursive", "Cursive Handwriting"),
            FontOption("rounded", "Rounded Soft"),
            FontOption("condensed", "Condensed Bold"),
            FontOption("headline", "Display Headline"),
            FontOption("retro", "Retro Vintage")
        )
    }
}
