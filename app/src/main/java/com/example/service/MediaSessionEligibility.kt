package com.example.service

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import com.example.data.model.TrackMetadata
import java.util.Locale

/**
 * Coarse app classification used strictly as supporting context alongside metadata and state.
 * Video-capable apps and browsers are never blindly blacklisted; instead they require stronger
 * positive music-track evidence to avoid false positives from videos, podcasts, or streams.
 */
enum class MediaAppCategory {
    DEDICATED_MUSIC,
    VIDEO_CAPABLE,
    BROWSER,
    PODCAST_OR_AUDIOBOOK,
    UNKNOWN
}

enum class EligibilityReason {
    ELIGIBLE_DEDICATED_MUSIC,
    ELIGIBLE_MUSIC_WITH_METADATA,
    ELIGIBLE_VIDEO_APP_WITH_MUSIC_METADATA,
    ELIGIBLE_BROWSER_WITH_MUSIC_METADATA,
    INELIGIBLE_INACTIVE_OR_STOPPED_STATE,
    INELIGIBLE_MISSING_METADATA,
    INELIGIBLE_MISSING_OR_GENERIC_TITLE,
    INELIGIBLE_MISSING_OR_INVALID_ARTIST,
    INELIGIBLE_PODCAST_OR_AUDIOBOOK,
    INELIGIBLE_NON_MUSIC_CONTENT_SIGNAL,
    INELIGIBLE_DURATION_OUT_OF_RANGE,
    INELIGIBLE_AMBIGUOUS_VIDEO_SESSION,
    INELIGIBLE_AMBIGUOUS_BROWSER_SESSION,
    INELIGIBLE_INSUFFICIENT_MUSIC_SIGNALS
}

sealed class MediaEligibilityResult {
    abstract val reason: EligibilityReason
    abstract val category: MediaAppCategory

    val isEligible: Boolean
        get() = this is Eligible

    data class Eligible(
        override val reason: EligibilityReason,
        override val category: MediaAppCategory
    ) : MediaEligibilityResult()

    data class Ineligible(
        override val reason: EligibilityReason,
        override val category: MediaAppCategory,
        val detail: String = ""
    ) : MediaEligibilityResult()
}

/**
 * Platform-independent snapshot of a MediaController's metadata and playback state.
 * Enables deterministic validation across production MediaListenerService/MediaPlaybackManager
 * and pure-JVM unit/integration tests.
 */
data class MediaSessionSnapshot(
    val packageName: String = "",
    val appName: String = "",
    val playbackState: Int? = null,
    val positionMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val hasMetadata: Boolean = false,
    val title: String = "",
    val displayTitle: String = "",
    val artist: String = "",
    val albumArtist: String = "",
    val author: String = "",
    val album: String = "",
    val displaySubtitle: String = "",
    val displayDescription: String = "",
    val mediaId: String = "",
    val mediaUri: String = "",
    val genre: String = "",
    val durationMs: Long = 0L,
    val trackNumber: Long = 0L,
    val hasArtwork: Boolean = false,
    val artUri: String? = null,
    val artBitmap: Bitmap? = null,
    val lastUpdatedMs: Long = System.currentTimeMillis()
) {
    val effectiveTitle: String
        get() = title.trim().ifBlank { displayTitle.trim() }

    val effectiveArtist: String
        get() = artist.trim()
            .ifBlank { albumArtist.trim() }
            .ifBlank { author.trim() }

    val effectiveAlbum: String
        get() = album.trim().ifBlank { displaySubtitle.trim() }

    val resolvedAppName: String
        get() = appName.trim().ifBlank { TrackMetadata.resolveAppName(packageName) }

    val durationSec: Int
        get() = if (durationMs > 0L) (durationMs / 1000L).toInt() else 0

    /**
     * Converts this snapshot to [TrackMetadata] only when it carries real title and artist data.
     * Never fabricates an "Unknown Artist" placeholder.
     */
    fun toTrackMetadata(): TrackMetadata? {
        if (!hasMetadata) return null
        val t = effectiveTitle
        val a = effectiveArtist
        if (t.isBlank() || a.isBlank()) return null

        val normalizedAlbum = if (
            effectiveAlbum.isNotBlank() &&
            !MediaSessionEligibilityEvaluator.isNonAlbumPlatformLabel(
                effectiveAlbum,
                title = t,
                artist = a,
                appName = resolvedAppName
            )
        ) {
            effectiveAlbum
        } else {
            ""
        }

        return TrackMetadata(
            title = t,
            artist = a,
            album = normalizedAlbum,
            durationSec = durationSec,
            sourcePackage = packageName.trim(),
            sourceAppName = resolvedAppName,
            albumArtUri = artUri?.trim()?.takeIf { it.isNotBlank() },
            albumArtBitmap = artBitmap
        )
    }
}

data class MediaControllerCandidate<T>(
    val token: Any?,
    val snapshot: MediaSessionSnapshot,
    val ref: T
)

data class MediaSelectionResult<T>(
    val selected: MediaControllerCandidate<T>?,
    val hadAnyControllers: Boolean,
    val hadIneligibleControllers: Boolean
)

object MediaSessionEligibilityEvaluator {

    private const val MIN_SONG_DURATION_MS = 15_000L
    private const val UNKNOWN_PLAYER_MAX_SONG_DURATION_MS = 1_200_000L // 20 min
    private const val VIDEO_OR_BROWSER_MAX_SONG_DURATION_MS = 1_200_000L // 20 min
    private const val DEDICATED_MUSIC_MAX_MIX_DURATION_MS = 2_700_000L // 45 min
    private const val DEDICATED_MUSIC_EXTENDED_DURATION_MS = 3_600_000L // 60 min
    private const val VIDEO_OFFICIAL_MARKER_MIN_DURATION_MS = 45_000L
    private const val VIDEO_OFFICIAL_MARKER_MAX_DURATION_MS = 600_000L // 10 min

    private val ACTIVE_PLAYBACK_STATES = setOf(
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM
    )

    private val DEDICATED_MUSIC_PACKAGES = setOf(
        "com.spotify.music",
        "com.spotify.lite",
        "com.google.android.apps.youtube.music",
        "app.revanced.android.apps.youtube.music",
        "com.vanced.android.apps.youtube.music",
        "com.apple.android.music",
        "com.amazon.mp3",
        "com.aspiro.tidal",
        "deezer.android.app",
        "com.pandora.android",
        "com.soundcloud.android",
        "com.qobuz.music",
        "com.bandcamp.android",
        "com.audiomack",
        "com.maxmpz.audioplayer",
        "com.foobar2000.foobar2000",
        "com.musicolet",
        "in.krosbits.musicolet",
        "com.jrtstudio.AnotherMusicPlayer",
        "com.neutroncode.mp",
        "com.hiby.music",
        "com.eoy.music",
        "ch.blinkenlights.android.medialibrary",
        "com.sec.android.app.music",
        "com.miui.player",
        "com.oppo.music",
        "com.oneplus.music",
        "com.sonyericsson.music",
        "com.google.android.music",
        "com.shazam.android",
        "org.lineageos.eleven",
        "com.jio.media.jiobeats",
        "com.gaana",
        "com.bsbportal.music",
        "com.anghami",
        "com.vk.music",
        "ru.yandex.music",
        "com.yandex.music",
        "com.napster",
        "com.plexamp",
        "org.jellyfin.mobile.finamp",
        "com.symfonik.music.player",
        "com.mixcloud.player"
    )

    private val VIDEO_CAPABLE_PACKAGES = setOf(
        "com.google.android.youtube",
        "app.revanced.android.youtube",
        "com.vanced.android.youtube",
        "org.schabi.newpipe",
        "org.videolan.vlc",
        "com.mxtech.videoplayer.ad",
        "com.mxtech.videoplayer.pro",
        "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient",
        "com.disney.disneyplus",
        "com.hulu.plus",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",
        "com.instagram.android",
        "tv.twitch.android.app",
        "com.vimeo.android.videoapp",
        "com.google.android.videos",
        "org.xbmc.kodi",
        "com.plexapp.android",
        "is.xyz.mpv"
    )

    private val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "com.microsoft.emmx",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.brave.browser",
        "com.sec.android.app.sbrowser",
        "com.duckduckgo.mobile.android",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "org.chromium.webview_shell",
        "com.UCMobile.intl"
    )

    private val PODCAST_OR_AUDIOBOOK_PACKAGES = setOf(
        "com.google.android.apps.podcasts",
        "au.com.shiftyjelly.pocketcasts",
        "com.bambuna.podcastaddict",
        "fm.player",
        "com.audible.application",
        "com.overdrive.mobile.android.libby",
        "de.danoeh.antennapod",
        "com.stitcher.app",
        "com.podcast.podcasts"
    )

    private val GENERIC_PLACEHOLDER_TITLES = setOf(
        "unknown",
        "unknown title",
        "unknown track",
        "unknown song",
        "no title",
        "untitled",
        "title",
        "media",
        "audio",
        "video",
        "stream",
        "livestream",
        "live stream",
        "live",
        "advertisement",
        "ad",
        "buffering",
        "loading",
        "loading...",
        "connecting",
        "playing",
        "paused",
        "chrome",
        "firefox",
        "edge",
        "brave",
        "youtube",
        "browser",
        "notification",
        "media playback",
        "audio playback",
        "null",
        "undefined",
        "n/a",
        "none"
    )

    private val PLACEHOLDER_ARTISTS = setOf(
        "unknown",
        "unknown artist",
        "<unknown>",
        "various",
        "n/a",
        "none",
        "null",
        "undefined",
        "no artist",
        "youtube",
        "chrome",
        "firefox",
        "edge",
        "brave",
        "opera",
        "safari",
        "samsung internet",
        "browser",
        "vimeo",
        "twitch",
        "tiktok",
        "instagram",
        "netflix"
    )

    private val PLATFORM_LABELS = setOf(
        "youtube",
        "youtube.com",
        "m.youtube.com",
        "www.youtube.com",
        "youtu.be",
        "chrome",
        "firefox",
        "edge",
        "brave",
        "opera",
        "safari",
        "samsung internet",
        "browser",
        "vimeo",
        "vimeo.com",
        "twitch",
        "twitch.tv",
        "tiktok",
        "tiktok.com",
        "instagram",
        "reddit",
        "reddit.com",
        "facebook",
        "twitter",
        "x.com",
        "netflix",
        "hulu",
        "prime video",
        "disney+",
        "unknown",
        "unknown album",
        "<unknown>"
    )

    private val PODCAST_OR_SPOKEN_GENRE_REGEX = Regex(
        "(?i)\\b(podcast|audiobook|spoken\\s*word|talk\\s*show|talk\\s*radio|news|comedy\\s*special|lecture|sermon|speech|documentary|education|interview)\\b"
    )

    private val NON_MUSIC_CONTENT_REGEX = Regex(
        "(?i)(\\b(podcast|episode\\s*#?\\d+|ep\\.?\\s*#?\\d+|audiobook|chapter\\s+\\d+|full\\s+interview|roundtable|monologue|keynote|webinar|sermon|lecture|tutorial|walkthrough|how\\s+to\\s+|unboxing|review|gameplay|playthrough|let'?s\\s+play|speedrun|vlog|reaction\\s+video|\\breaction\\b|livestream|live\\s+stream|highlights\\b|highlight\\s+reel|trailer|teaser|full\\s+movie|full\\s+episode|stand[\\s-]up|news\\s+update|daily\\s+briefing|making\\s+of|behind\\s+the\\s+scenes)\\b|#shorts\\b|\\bshorts\\b)"
    )

    private val VIDEO_FILE_EXTENSION_REGEX = Regex("(?i).+\\.(mp4|mkv|webm|avi|mov|m4v|3gp)(\\?.*)?$")
    private val AUDIO_FILE_EXTENSION_REGEX = Regex("(?i).+\\.(mp3|flac|m4a|aac|ogg|opus|wav|alac|wma|aiff)(\\?.*)?$")
    private val CAMERA_FILENAME_REGEX = Regex("(?i)^(vid|pxl|img|mov|screen_?recording|scr)[_-]?\\d+.*")
    private val DOMAIN_OR_URL_REGEX = Regex(
        "(?i)^((https?|rtsp|content|file)://.*|([a-z0-9-]+\\.)+(com|org|net|tv|io|co|me|fm|gg|ly|be|dev|app)(/.*)?)$"
    )
    private val KNOWN_MUSIC_WEB_DOMAINS = listOf(
        "music.youtube.com",
        "open.spotify.com",
        "music.apple.com",
        "soundcloud.com",
        "bandcamp.com",
        "tidal.com",
        "deezer.com",
        "music.amazon."
    )
    private val VIDEO_WEB_DOMAINS = listOf(
        "youtube.com/watch",
        "youtube.com/shorts",
        "youtu.be/",
        "vimeo.com",
        "twitch.tv",
        "tiktok.com",
        "instagram.com/reel",
        "netflix.com"
    )
    private val OFFICIAL_MUSIC_VIDEO_MARKER_REGEX = Regex(
        "(?i)(\\[|\\()(?:official\\s+(?:music\\s+video|video|audio|lyric\\s+video)|music\\s+video|lyric\\s+video|audio\\s+only)(\\]|\\))"
    )
    private val MUSIC_GENRE_HINTS_REGEX = Regex(
        "(?i)\\b(pop|rock|hip[\\s-]?hop|rap|r&b|rnb|jazz|blues|classical|electronic|edm|dance|house|techno|metal|punk|indie|folk|country|soul|funk|reggae|latin|k[\\s-]?pop|j[\\s-]?pop|lo[\\s-]?fi|soundtrack|ost|alternative|synthpop|ambient|acoustic|singer[\\s-]?songwriter|music)\\b"
    )

    fun categorizePackage(packageName: String): MediaAppCategory {
        val pkg = packageName.trim().lowercase(Locale.US)
        if (pkg.isBlank()) return MediaAppCategory.UNKNOWN
        return when {
            pkg in DEDICATED_MUSIC_PACKAGES -> MediaAppCategory.DEDICATED_MUSIC
            pkg in VIDEO_CAPABLE_PACKAGES -> MediaAppCategory.VIDEO_CAPABLE
            pkg in BROWSER_PACKAGES -> MediaAppCategory.BROWSER
            pkg in PODCAST_OR_AUDIOBOOK_PACKAGES -> MediaAppCategory.PODCAST_OR_AUDIOBOOK
            pkg.contains("podcast") || pkg.contains("audiobook") -> MediaAppCategory.PODCAST_OR_AUDIOBOOK
            pkg.contains("browser") -> MediaAppCategory.BROWSER
            else -> MediaAppCategory.UNKNOWN
        }
    }

    fun isGenericOrPlaceholderTitle(rawTitle: String): Boolean {
        val clean = rawTitle.trim()
        if (clean.isBlank()) return true
        val lower = clean.lowercase(Locale.US)
        if (lower in GENERIC_PLACEHOLDER_TITLES) return true
        if (clean.all { it.isDigit() || !it.isLetterOrDigit() }) return true
        if (DOMAIN_OR_URL_REGEX.matches(lower)) return true
        if (VIDEO_FILE_EXTENSION_REGEX.matches(lower)) return true
        if (CAMERA_FILENAME_REGEX.matches(lower)) return true
        return false
    }

    fun isPlaceholderOrInvalidArtist(rawArtist: String): Boolean {
        val clean = rawArtist.trim()
        if (clean.isBlank()) return true
        val lower = clean.lowercase(Locale.US)
        if (lower in PLACEHOLDER_ARTISTS) return true
        if (clean.all { !it.isLetterOrDigit() }) return true
        if (DOMAIN_OR_URL_REGEX.matches(lower)) return true
        return false
    }

    internal fun isNonAlbumPlatformLabel(
        rawAlbum: String,
        title: String,
        artist: String,
        appName: String
    ): Boolean {
        val clean = rawAlbum.trim()
        if (clean.isBlank()) return true
        val lower = clean.lowercase(Locale.US)
        if (lower in PLATFORM_LABELS) return true
        if (DOMAIN_OR_URL_REGEX.matches(lower)) return true
        if (clean.equals(title.trim(), ignoreCase = true)) return true
        if (clean.equals(artist.trim(), ignoreCase = true)) return true
        if (appName.isNotBlank() && clean.equals(appName.trim(), ignoreCase = true)) return true
        return false
    }

    private fun hasGenuineAlbum(
        snapshot: MediaSessionSnapshot,
        strictExplicitAlbumOnly: Boolean
    ): Boolean {
        val candidate = if (strictExplicitAlbumOnly) {
            snapshot.album.trim()
        } else {
            snapshot.effectiveAlbum
        }
        return !isNonAlbumPlatformLabel(
            candidate,
            title = snapshot.effectiveTitle,
            artist = snapshot.effectiveArtist,
            appName = snapshot.resolvedAppName
        )
    }

    private fun isMusicGenre(genre: String): Boolean {
        val clean = genre.trim()
        if (clean.isBlank()) return false
        if (PODCAST_OR_SPOKEN_GENRE_REGEX.containsMatchIn(clean)) return false
        return MUSIC_GENRE_HINTS_REGEX.containsMatchIn(clean)
    }

    private fun hasNonMusicSignals(snapshot: MediaSessionSnapshot): Boolean {
        if (PODCAST_OR_SPOKEN_GENRE_REGEX.containsMatchIn(snapshot.genre)) return true

        val uriOrId = "${snapshot.mediaUri} ${snapshot.mediaId}".trim()
        if (uriOrId.isNotBlank()) {
            val lowerUri = uriOrId.lowercase(Locale.US)
            if (VIDEO_FILE_EXTENSION_REGEX.matches(snapshot.mediaUri.trim()) ||
                VIDEO_FILE_EXTENSION_REGEX.matches(snapshot.mediaId.trim()) ||
                lowerUri.contains("/shorts/") ||
                VIDEO_WEB_DOMAINS.any { lowerUri.contains(it) }
            ) {
                return true
            }
        }

        val descriptiveText = listOf(
            snapshot.effectiveTitle,
            snapshot.displaySubtitle,
            snapshot.displayDescription
        ).filter { it.isNotBlank() }.joinToString(" | ")

        return NON_MUSIC_CONTENT_REGEX.containsMatchIn(descriptiveText)
    }

    private fun hasStrongAudioOrMusicSignal(
        snapshot: MediaSessionSnapshot,
        category: MediaAppCategory
    ): Boolean {
        val strictAlbum = category == MediaAppCategory.VIDEO_CAPABLE || category == MediaAppCategory.BROWSER
        if (hasGenuineAlbum(snapshot, strictExplicitAlbumOnly = strictAlbum)) return true
        if (isMusicGenre(snapshot.genre)) return true
        if (snapshot.trackNumber > 0L) return true

        val uriOrId = snapshot.mediaUri.trim().ifBlank { snapshot.mediaId.trim() }
        val lowerUri = uriOrId.lowercase(Locale.US)
        if (lowerUri.isNotBlank()) {
            if (AUDIO_FILE_EXTENSION_REGEX.matches(lowerUri)) return true
            if (KNOWN_MUSIC_WEB_DOMAINS.any { lowerUri.contains(it) }) return true
        }

        if (category == MediaAppCategory.VIDEO_CAPABLE) {
            val artistLower = snapshot.effectiveArtist.lowercase(Locale.US)
            if (artistLower.endsWith(" - topic")) {
                return true
            }
            if (OFFICIAL_MUSIC_VIDEO_MARKER_REGEX.containsMatchIn(snapshot.effectiveTitle)) {
                val dur = snapshot.durationMs
                if (dur == 0L || dur in VIDEO_OFFICIAL_MARKER_MIN_DURATION_MS..VIDEO_OFFICIAL_MARKER_MAX_DURATION_MS) {
                    return true
                }
            }
        }

        return false
    }

    fun evaluate(snapshot: MediaSessionSnapshot): MediaEligibilityResult {
        val category = categorizePackage(snapshot.packageName)

        val state = snapshot.playbackState
        if (state == null || state !in ACTIVE_PLAYBACK_STATES) {
            return MediaEligibilityResult.Ineligible(
                EligibilityReason.INELIGIBLE_INACTIVE_OR_STOPPED_STATE,
                category,
                "Playback state $state is inactive"
            )
        }

        if (!snapshot.hasMetadata) {
            return MediaEligibilityResult.Ineligible(
                EligibilityReason.INELIGIBLE_MISSING_METADATA,
                category,
                "Session has no MediaMetadata"
            )
        }

        val title = snapshot.effectiveTitle
        if (isGenericOrPlaceholderTitle(title)) {
            return MediaEligibilityResult.Ineligible(
                EligibilityReason.INELIGIBLE_MISSING_OR_GENERIC_TITLE,
                category,
                "Title '$title' is blank or generic"
            )
        }

        val artist = snapshot.effectiveArtist
        if (isPlaceholderOrInvalidArtist(artist)) {
            return MediaEligibilityResult.Ineligible(
                EligibilityReason.INELIGIBLE_MISSING_OR_INVALID_ARTIST,
                category,
                "Artist '$artist' is missing, placeholder, or domain-like"
            )
        }

        val durationMs = snapshot.durationMs
        if (durationMs in 1L until MIN_SONG_DURATION_MS) {
            return MediaEligibilityResult.Ineligible(
                EligibilityReason.INELIGIBLE_DURATION_OUT_OF_RANGE,
                category,
                "Duration ${durationMs}ms is too short for a song"
            )
        }

        if (hasNonMusicSignals(snapshot)) {
            return MediaEligibilityResult.Ineligible(
                EligibilityReason.INELIGIBLE_NON_MUSIC_CONTENT_SIGNAL,
                category,
                "Detected podcast, episode, video, or livestream signal"
            )
        }

        return when (category) {
            MediaAppCategory.PODCAST_OR_AUDIOBOOK -> {
                val explicitMusicTrack = hasGenuineAlbum(snapshot, strictExplicitAlbumOnly = true) &&
                    isMusicGenre(snapshot.genre) &&
                    (durationMs == 0L || durationMs in MIN_SONG_DURATION_MS..600_000L)
                if (explicitMusicTrack) {
                    MediaEligibilityResult.Eligible(EligibilityReason.ELIGIBLE_MUSIC_WITH_METADATA, category)
                } else {
                    MediaEligibilityResult.Ineligible(
                        EligibilityReason.INELIGIBLE_PODCAST_OR_AUDIOBOOK,
                        category,
                        "Podcast or audiobook app session rejected"
                    )
                }
            }

            MediaAppCategory.DEDICATED_MUSIC -> {
                if (durationMs > DEDICATED_MUSIC_EXTENDED_DURATION_MS) {
                    MediaEligibilityResult.Ineligible(
                        EligibilityReason.INELIGIBLE_DURATION_OUT_OF_RANGE,
                        category,
                        "Duration exceeds 60 minutes without spoken cues"
                    )
                } else if (durationMs > DEDICATED_MUSIC_MAX_MIX_DURATION_MS &&
                    !isMusicGenre(snapshot.genre) &&
                    !hasGenuineAlbum(snapshot, strictExplicitAlbumOnly = false)
                ) {
                    MediaEligibilityResult.Ineligible(
                        EligibilityReason.INELIGIBLE_DURATION_OUT_OF_RANGE,
                        category,
                        "Extended duration (>45m) without music genre or album"
                    )
                } else {
                    MediaEligibilityResult.Eligible(EligibilityReason.ELIGIBLE_DEDICATED_MUSIC, category)
                }
            }

            MediaAppCategory.VIDEO_CAPABLE -> {
                if (durationMs > VIDEO_OR_BROWSER_MAX_SONG_DURATION_MS &&
                    !hasGenuineAlbum(snapshot, strictExplicitAlbumOnly = true)
                ) {
                    MediaEligibilityResult.Ineligible(
                        EligibilityReason.INELIGIBLE_DURATION_OUT_OF_RANGE,
                        category,
                        "Video app duration exceeds 20 minutes without album metadata"
                    )
                } else if (hasStrongAudioOrMusicSignal(snapshot, category)) {
                    MediaEligibilityResult.Eligible(
                        EligibilityReason.ELIGIBLE_VIDEO_APP_WITH_MUSIC_METADATA,
                        category
                    )
                } else {
                    MediaEligibilityResult.Ineligible(
                        EligibilityReason.INELIGIBLE_AMBIGUOUS_VIDEO_SESSION,
                        category,
                        "Video-capable app lacks strong positive music metadata"
                    )
                }
            }

            MediaAppCategory.BROWSER -> {
                if (durationMs > VIDEO_OR_BROWSER_MAX_SONG_DURATION_MS &&
                    !hasGenuineAlbum(snapshot, strictExplicitAlbumOnly = true)
                ) {
                    MediaEligibilityResult.Ineligible(
                        EligibilityReason.INELIGIBLE_DURATION_OUT_OF_RANGE,
                        category,
                        "Browser media duration exceeds 20 minutes"
                    )
                } else if (hasStrongAudioOrMusicSignal(snapshot, category)) {
                    MediaEligibilityResult.Eligible(
                        EligibilityReason.ELIGIBLE_BROWSER_WITH_MUSIC_METADATA,
                        category
                    )
                } else {
                    MediaEligibilityResult.Ineligible(
                        EligibilityReason.INELIGIBLE_AMBIGUOUS_BROWSER_SESSION,
                        category,
                        "Browser session lacks music album, genre, or music web domain evidence"
                    )
                }
            }

            MediaAppCategory.UNKNOWN -> {
                if (durationMs > UNKNOWN_PLAYER_MAX_SONG_DURATION_MS &&
                    !hasGenuineAlbum(snapshot, strictExplicitAlbumOnly = true) &&
                    !isMusicGenre(snapshot.genre)
                ) {
                    MediaEligibilityResult.Ineligible(
                        EligibilityReason.INELIGIBLE_DURATION_OUT_OF_RANGE,
                        category,
                        "Unknown player duration exceeds 20 minutes without album or genre"
                    )
                } else {
                    val hasDurationInSongRange = durationMs in MIN_SONG_DURATION_MS..UNKNOWN_PLAYER_MAX_SONG_DURATION_MS
                    val hasPositiveMusicSupport = hasStrongAudioOrMusicSignal(snapshot, category) || hasDurationInSongRange
                    if (hasPositiveMusicSupport) {
                        MediaEligibilityResult.Eligible(
                            EligibilityReason.ELIGIBLE_MUSIC_WITH_METADATA,
                            category
                        )
                    } else {
                        MediaEligibilityResult.Ineligible(
                            EligibilityReason.INELIGIBLE_INSUFFICIENT_MUSIC_SIGNALS,
                            category,
                            "Generic unknown session lacks album, genre, track number, audio URI, or song-range duration"
                        )
                    }
                }
            }
        }
    }

    fun fromMediaController(
        controller: MediaController,
        packageManager: PackageManager?
    ): MediaSessionSnapshot {
        val pkgName = try {
            controller.packageName ?: ""
        } catch (_: Throwable) {
            ""
        }

        val pbState = try {
            controller.playbackState
        } catch (_: Throwable) {
            null
        }

        val metadata = try {
            controller.metadata
        } catch (_: Throwable) {
            null
        }

        val appName = TrackMetadata.resolveAppName(pkgName, packageManager)

        if (metadata == null) {
            return MediaSessionSnapshot(
                packageName = pkgName,
                appName = appName,
                playbackState = pbState?.state,
                positionMs = pbState?.position ?: 0L,
                playbackSpeed = pbState?.playbackSpeed ?: 1.0f,
                hasMetadata = false
            )
        }

        return fromMediaMetadata(
            packageName = pkgName,
            appName = appName,
            metadata = metadata,
            playbackState = pbState
        )
    }

    fun fromMediaMetadata(
        packageName: String,
        appName: String,
        metadata: MediaMetadata?,
        playbackState: PlaybackState?
    ): MediaSessionSnapshot {
        if (metadata == null) {
            return MediaSessionSnapshot(
                packageName = packageName,
                appName = appName,
                playbackState = playbackState?.state,
                positionMs = playbackState?.position ?: 0L,
                playbackSpeed = playbackState?.playbackSpeed ?: 1.0f,
                hasMetadata = false
            )
        }

        fun safeStr(key: String): String = try {
            metadata.getString(key)?.trim() ?: ""
        } catch (_: Throwable) {
            ""
        }

        fun safeLong(key: String): Long = try {
            metadata.getLong(key)
        } catch (_: Throwable) {
            0L
        }

        val desc = try {
            metadata.description
        } catch (_: Throwable) {
            null
        }

        val title = safeStr(MediaMetadata.METADATA_KEY_TITLE)
        val displayTitle = safeStr(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            .ifBlank { desc?.title?.toString()?.trim() ?: "" }

        val artist = safeStr(MediaMetadata.METADATA_KEY_ARTIST)
        val albumArtist = safeStr(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val author = safeStr(MediaMetadata.METADATA_KEY_AUTHOR)

        val album = safeStr(MediaMetadata.METADATA_KEY_ALBUM)
        val displaySubtitle = safeStr(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            .ifBlank { desc?.subtitle?.toString()?.trim() ?: "" }
        val displayDescription = safeStr(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION)
            .ifBlank { desc?.description?.toString()?.trim() ?: "" }

        val mediaId = safeStr(MediaMetadata.METADATA_KEY_MEDIA_ID)
            .ifBlank { desc?.mediaId?.trim() ?: "" }
        val mediaUri = safeStr(MediaMetadata.METADATA_KEY_MEDIA_URI)
            .ifBlank { desc?.mediaUri?.toString()?.trim() ?: "" }
        val genre = safeStr(MediaMetadata.METADATA_KEY_GENRE)

        val durationMs = safeLong(MediaMetadata.METADATA_KEY_DURATION)
        val trackNumber = safeLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER)

        val artUri = listOf(
            safeStr(MediaMetadata.METADATA_KEY_ART_URI),
            safeStr(MediaMetadata.METADATA_KEY_ALBUM_ART_URI),
            safeStr(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
            desc?.iconUri?.toString()?.trim() ?: ""
        ).firstOrNull { it.isNotBlank() }

        val artBitmap = try {
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: desc?.iconBitmap
        } catch (_: Throwable) {
            null
        }

        val hasArtwork = artBitmap != null || !artUri.isNullOrBlank()

        return MediaSessionSnapshot(
            packageName = packageName.trim(),
            appName = appName.trim(),
            playbackState = playbackState?.state,
            positionMs = playbackState?.position ?: 0L,
            playbackSpeed = playbackState?.playbackSpeed ?: 1.0f,
            hasMetadata = true,
            title = title,
            displayTitle = displayTitle,
            artist = artist,
            albumArtist = albumArtist,
            author = author,
            album = album,
            displaySubtitle = displaySubtitle,
            displayDescription = displayDescription,
            mediaId = mediaId,
            mediaUri = mediaUri,
            genre = genre,
            durationMs = durationMs,
            trackNumber = trackNumber,
            hasArtwork = hasArtwork,
            artUri = artUri,
            artBitmap = artBitmap
        )
    }
}

object MediaSessionSelector {

    fun <T> selectBestController(
        candidates: List<MediaControllerCandidate<T>>,
        currentToken: Any?
    ): MediaSelectionResult<T> {
        if (candidates.isEmpty()) {
            return MediaSelectionResult(
                selected = null,
                hadAnyControllers = false,
                hadIneligibleControllers = false
            )
        }

        val eligibleWithEval = candidates.mapIndexedNotNull { index, candidate ->
            val eval = MediaSessionEligibilityEvaluator.evaluate(candidate.snapshot)
            if (eval is MediaEligibilityResult.Eligible) {
                Triple(candidate, eval, index)
            } else {
                null
            }
        }

        if (eligibleWithEval.isEmpty()) {
            return MediaSelectionResult(
                selected = null,
                hadAnyControllers = true,
                hadIneligibleControllers = true
            )
        }

        val ranked = eligibleWithEval.minWithOrNull(
            compareBy<Triple<MediaControllerCandidate<T>, MediaEligibilityResult.Eligible, Int>>(
                { (candidate, _, _) -> statePriority(candidate.snapshot.playbackState, candidate.token == currentToken) },
                { (_, eval, _) -> categoryPriority(eval.category) },
                { (candidate, _, _) -> if (currentToken != null && candidate.token == currentToken) 0 else 1 },
                { (_, _, index) -> index }
            )
        )

        return MediaSelectionResult(
            selected = ranked?.first,
            hadAnyControllers = true,
            hadIneligibleControllers = eligibleWithEval.size < candidates.size
        )
    }

    private fun statePriority(state: Int?, isCurrentToken: Boolean): Int {
        return when (state) {
            PlaybackState.STATE_PLAYING -> 0
            PlaybackState.STATE_PAUSED -> if (isCurrentToken) 1 else 3
            PlaybackState.STATE_BUFFERING, PlaybackState.STATE_CONNECTING -> if (isCurrentToken) 2 else 4
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> if (isCurrentToken) 2 else 5
            else -> 6
        }
    }

    private fun categoryPriority(category: MediaAppCategory): Int {
        return when (category) {
            MediaAppCategory.DEDICATED_MUSIC -> 0
            MediaAppCategory.UNKNOWN -> 1
            MediaAppCategory.BROWSER -> 2
            MediaAppCategory.VIDEO_CAPABLE -> 3
            MediaAppCategory.PODCAST_OR_AUDIOBOOK -> 4
        }
    }
}
