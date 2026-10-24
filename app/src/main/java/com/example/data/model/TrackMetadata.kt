package com.example.data.model

import android.content.pm.PackageManager

data class TrackMetadata(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSec: Int = 0,
    val sourcePackage: String = "",
    val sourceAppName: String = "",
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

        fun resolveAppName(packageName: String, packageManager: PackageManager? = null): String {
            if (packageName.isBlank()) return ""

            val knownName = when (packageName) {
                "com.google.android.youtube" -> "YouTube"
                "com.google.android.apps.youtube.music" -> "YouTube Music"
                "com.spotify.music", "com.spotify.lite" -> "Spotify"
                "com.apple.android.music" -> "Apple Music"
                "com.soundcloud.android" -> "SoundCloud"
                "com.amazon.mp3" -> "Amazon Music"
                "com.aspiro.tidal" -> "TIDAL"
                "deezer.android.app" -> "Deezer"
                "com.pandora.android" -> "Pandora"
                "org.videolan.vlc" -> "VLC"
                "com.maxmpz.audioplayer" -> "Poweramp"
                "com.foobar2000.foobar2000" -> "foobar2000"
                "com.musicolet", "in.krosbits.musicolet" -> "Musicolet"
                "com.jrtstudio.AnotherMusicPlayer" -> "Rocket Player"
                "com.neutroncode.mp" -> "Neutron Player"
                "com.jangomobile.android" -> "Jango Radio"
                "tunein.player" -> "TuneIn"
                "com.audiomack" -> "Audiomack"
                "com.siriusxm" -> "SiriusXM"
                "com.mixcloud.player" -> "Mixcloud"
                "com.bandcamp.android" -> "Bandcamp"
                "com.hiby.music" -> "HiBy Music"
                "com.eoy.music" -> "Retro Music"
                "ch.blinkenlights.android.medialibrary" -> "Vanilla Music"
                "com.qobuz.music" -> "Qobuz"
                else -> null
            }
            if (knownName != null) return knownName

            if (packageManager != null) {
                try {
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    val label = packageManager.getApplicationLabel(appInfo)?.toString()
                    if (!label.isNullOrBlank()) {
                        return label.trim()
                    }
                } catch (_: Exception) {
                    // Ignore package lookup errors gracefully
                }
            }

            val lastSegment = packageName.substringAfterLast('.').ifBlank { packageName }
            return lastSegment.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}

data class PlaybackStateInfo(
    val track: TrackMetadata? = null,
    val isPlaying: Boolean = false,
    val hasActiveSession: Boolean = false,
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
