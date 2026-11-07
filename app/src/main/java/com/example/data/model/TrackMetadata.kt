package com.example.data.model

import android.content.pm.PackageManager

data class TrackMetadata(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSec: Int = 0,
    val sourcePackage: String = "",
    val sourceAppName: String = "",
    val albumArtUri: String? = null,
    val albumArtBitmap: android.graphics.Bitmap? = null
) {
    /**
     * Base normalized identity based on artist and title, preserving version markers (Live, Remastered, Acoustic, etc.)
     * while stripping video/platform clutter.
     */
    val baseIdentityKey: String
        get() {
            val cArtist = cleanMediaClutter(artist).ifBlank { artist.trim().lowercase() }.ifBlank { "unknown artist" }.lowercase()
            val cTitle = cleanMediaClutter(title).ifBlank { title.trim().lowercase() }.ifBlank { "unknown title" }.lowercase()
            return "$cArtist - $cTitle".trim().lowercase()
        }

    /**
     * Track Identity Key:
     * Used for track-specific persistent data:
     * - Imported LRC associations
     * - Timing offsets
     * - Local caching & history
     * - Artwork cache differentiation
     *
     * Preserves recording distinctions:
     * - "Song Name"
     * - "Song Name (Remastered)"
     * - "Song Name [Live]"
     * - "Song Name (Radio Edit)"
     * - "Song Name (Acoustic)"
     * - Same title by different artists
     * - Same artist and title on different albums
     */
    val identityKey: String
        get() {
            val base = baseIdentityKey
            val cAlbum = cleanMediaClutter(album).trim().lowercase()
            val cTitle = cleanMediaClutter(title).trim().lowercase()
            return if (cAlbum.isNotBlank() && cAlbum != cTitle) {
                "$base [album: $cAlbum]"
            } else {
                base
            }
        }

    /**
     * Lyrics Lookup Key:
     * Used exclusively to search online lyric providers (LRCLIB, Lyrics.ovh) and query public databases.
     * Strips version and platform noise to maximize online search match rates.
     */
    val lookupKey: String
        get() {
            val cArtist = cleanForLookup(artist).ifBlank { artist.trim().lowercase() }.ifBlank { "unknown artist" }.lowercase()
            val cTitle = cleanForLookup(title).ifBlank { title.trim().lowercase() }.ifBlank { "unknown title" }.lowercase()
            return "$cArtist - $cTitle".trim().lowercase()
        }

    /**
     * Primary track key for backward compatibility across the codebase. Points to identityKey.
     */
    val key: String
        get() = identityKey

    val isValidTrack: Boolean
        get() = title.isNotBlank() && lookupKey.isNotBlank() && lookupKey != "unknown artist - unknown title"

    companion object {
        /**
         * Strips ONLY media wrapper clutter (YouTube / video tags) while strictly preserving
         * musical version indicators (Remastered, Live, Acoustic, Radio Edit, etc.).
         */
        fun cleanMediaClutter(str: String): String {
            return str
                .replace(Regex("(?i)\\s*[(\\[](?:[^\\])]*\\b(?:official\\s+(?:video|audio|music\\s+video)|video|audio|visuali[sz]er|hd|hq|4k|full\\s+song|from\\s+[\"“].*)\\b[^\\])]*)[)\\]]"), "")
                .replace(Regex("(?i)\\s*-\\s*(?:official\\s+(?:video|audio|music\\s+video)|video|audio|visuali[sz]er)\\b.*$"), "")
                .trim()
        }

        /**
         * Strips both media clutter AND audio version tags for broad online lyrics search matching.
         */
        fun cleanForLookup(str: String): String {
            val stripped = cleanMediaClutter(str)
                .replace(Regex("(?i)\\s*[(\\[](?:[^\\])]*\\b(?:remaster(?:ed)?|live|acoustic|unplugged|radio\\s+edit|single\\s+edit|bonus\\s+track|deluxe|explicit|clean)\\b[^\\])]*)[)\\]]"), "")
                .replace(Regex("(?i)\\s*-\\s*(?:remaster(?:ed)?|live|acoustic|unplugged|radio\\s+edit|single\\s+edit|bonus\\s+track)\\b.*$"), "")
                .trim()
            return stripped.ifBlank { cleanMediaClutter(str) }
        }

        /**
         * Legacy cleanString method preserved for backward compatibility.
         */
        fun cleanString(str: String): String = cleanForLookup(str)

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
