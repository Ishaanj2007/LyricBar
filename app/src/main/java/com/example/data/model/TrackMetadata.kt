package com.example.data.model

data class TrackMetadata(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSec: Int = 0,
    val sourcePackage: String = "",
    val albumArtUri: String? = null
) {
    val key: String
        get() = "${cleanString(artist)} - ${cleanString(title)}".trim().lowercase()

    companion object {
        fun cleanString(str: String): String {
            // Remove audio markers like (Official Video), (Lyric Video), [Remastered], etc.
            return str
                .replace(Regex("(?i)\\s*[(\\[](?:[^\\])]*\\b(?:official|video|audio|lyrics?|visuali[sz]er|hd|hq|4k|remaster(?:ed)?|explicit|clean|full song|from\\s+[\"“].*)\\b[^\\])]*)[)\\]]"), "")
                .replace(Regex("(?i)\\s*-\\s*(?:official|video|audio|lyrics?|visuali[sz]er|remaster(?:ed)?|radio edit|bonus track)\\b.*$"), "")
                .trim()
        }
    }
}

data class PlaybackStateInfo(
    val track: TrackMetadata? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val lastUpdatedMs: Long = System.currentTimeMillis()
) {
    val calculatedPositionMs: Long
        get() {
            if (!isPlaying) return positionMs
            val elapsed = (System.currentTimeMillis() - lastUpdatedMs).coerceAtLeast(0L)
            return positionMs + (elapsed * playbackSpeed).toLong()
        }
}
