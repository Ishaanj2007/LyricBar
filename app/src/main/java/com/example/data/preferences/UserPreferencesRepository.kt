package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.R
import com.example.service.HudBoundsConstraints
import com.example.service.HudDisplayGeometry
import com.example.service.HudOrientation
import com.example.service.HudOverlayProfile
import com.example.service.HudPositioningEngine
import com.example.ui.components.HudColorSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException
import java.security.MessageDigest

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lyricbar_prefs")

enum class LyricsProviderPreference(
    val displayName: String,
    val description: String,
    val providerName: String?
) {
    AUTO(
        displayName = "Automatic",
        description = "Tries LRCLIB → lrc.red → LyricsPlus → KuGou → Lyrica → LiriQo → Unison in order",
        providerName = null
    ),
    LRCLIB(
        displayName = "LRCLIB",
        description = "Primary community catalog of synchronized LRC lyrics",
        providerName = "LRCLIB"
    ),
    LRC_RED(
        displayName = "lrc.red",
        description = "ISRC-matched synchronized LRC lyrics catalog",
        providerName = "lrc.red"
    ),
    LYRICS_PLUS(
        displayName = "LyricsPlus",
        description = "Multi-mirror synchronized lyrics aggregator",
        providerName = "LyricsPlus"
    ),
    KUGOU(
        displayName = "KuGou",
        description = "Title and artist matched synchronized lyrics catalog",
        providerName = "KuGou"
    ),
    LYRICA(
        displayName = "Lyrica",
        description = "Hindi, Bollywood, regional Indian, and global synchronized lyrics",
        providerName = "Lyrica"
    ),
    LIRIQO(
        displayName = "LiriQo",
        description = "International and Asian synchronized lyrics catalog",
        providerName = "LiriQo"
    ),
    UNISON(
        displayName = "Unison",
        description = "Community-submitted synchronized LRC and TTML lyrics",
        providerName = "Unison"
    );

    companion object {
        val DEFAULT_PROVIDER_ORDER: List<LyricsProviderPreference> = listOf(
            LRCLIB,
            LRC_RED,
            LYRICS_PLUS,
            KUGOU,
            LYRICA,
            LIRIQO,
            UNISON
        )

        fun fromPersistedValue(value: String?): LyricsProviderPreference {
            if (value.isNullOrBlank()) return AUTO
            val trimmed = value.trim()
            return entries.firstOrNull { option ->
                option.name.equals(trimmed, ignoreCase = true) ||
                    option.displayName.equals(trimmed, ignoreCase = true) ||
                    (option.providerName != null && option.providerName.equals(trimmed, ignoreCase = true))
            } ?: AUTO
        }

        private fun resolveSelectableProviderOrNull(token: String?): LyricsProviderPreference? {
            if (token.isNullOrBlank()) return null
            val cleaned = token.trim().trim('"', '\'', '[', ']')
            if (cleaned.isBlank() || cleaned.equals(AUTO.name, ignoreCase = true)) return null
            val resolved = entries.firstOrNull { option ->
                option != AUTO && (
                    option.name.equals(cleaned, ignoreCase = true) ||
                        option.displayName.equals(cleaned, ignoreCase = true) ||
                        (option.providerName != null && option.providerName.equals(cleaned, ignoreCase = true))
                    )
            }
            return resolved
        }

        fun normalizeProviderOrder(rawIds: Iterable<String>): List<LyricsProviderPreference> {
            val ordered = LinkedHashSet<LyricsProviderPreference>(DEFAULT_PROVIDER_ORDER.size)
            for (raw in rawIds) {
                val resolved = resolveSelectableProviderOrNull(raw) ?: continue
                ordered.add(resolved)
            }
            for (fallback in DEFAULT_PROVIDER_ORDER) {
                ordered.add(fallback)
            }
            return ordered.toList()
        }

        fun normalizeProviderOrderEntries(entries: List<LyricsProviderPreference>): List<LyricsProviderPreference> {
            val ordered = LinkedHashSet<LyricsProviderPreference>(DEFAULT_PROVIDER_ORDER.size)
            for (entry in entries) {
                if (entry != AUTO) {
                    ordered.add(entry)
                }
            }
            for (fallback in DEFAULT_PROVIDER_ORDER) {
                ordered.add(fallback)
            }
            return ordered.toList()
        }

        fun parseProviderOrder(rawSavedValue: String?): List<LyricsProviderPreference> {
            if (rawSavedValue.isNullOrBlank()) return DEFAULT_PROVIDER_ORDER
            val tokens = rawSavedValue.split(',', ';', '|', '\n')
            return normalizeProviderOrder(tokens)
        }

        fun serializeProviderOrder(order: List<LyricsProviderPreference>): String {
            return normalizeProviderOrderEntries(order).joinToString(",") { it.name }
        }
    }
}

enum class ThemePreset(val title: String) {
    ROSE_QUARTZ("Rose Quartz"),
    LAVENDER_MIST("Lavender Mist"),
    PEACH_EMBER("Peach Ember"),
    SAGE_SERENITY("Sage Serenity"),

    // Legacy aliases mapped for backwards compatibility
    OBSIDIAN_MINT("Rose Quartz"),
    MIDNIGHT_AZURE("Lavender Mist"),
    SUNSET_AMBER("Peach Ember"),
    CYBER_VIOLET("Sage Serenity")
}

enum class ThemeMode(val title: String) {
    FOLLOW_SYSTEM("Follow System"),
    LIGHT("Light"),
    DARK("Dark")
}

enum class FontOption(val title: String, val fontResId: Int?) {
    SYSTEM("System Default", null),
    INTER("Inter Modern", R.font.inter),
    POPPINS("Poppins Rounded", R.font.poppins),
    MONTSERRAT("Montserrat Bold", R.font.montserrat),
    LORA("Lora Elegant", R.font.lora),
    ROBOTO_MONO("Roboto Mono Tech", R.font.roboto_mono),
    PLAYFAIR("Playfair Serif", R.font.playfair_display),
    OUTFIT("Outfit Neo-Clean", R.font.outfit),
    RUBIK("Rubik Soft Geometric", R.font.rubik),
    PLUS_JAKARTA_SANS("Plus Jakarta Sans", R.font.plus_jakarta_sans),
    SPACE_GROTESK("Space Grotesk", R.font.space_grotesk),
    COMFORTAA("Comfortaa Soft", R.font.comfortaa),
    CINZEL("Cinzel Elegant Serif", R.font.cinzel)
}

enum class OverlayAnimationStyle(val title: String, val description: String) {
    KARAOKE("Apple-style Karaoke", "Progressively highlights each word in sync with playback when word timestamps exist"),
    SMOOTH_SLIDE("Smooth Line Transition", "Gently fades and slides between lyric lines without fake word timing"),
    FADE("Fade In / Fade Out", "Gradually fades between lyric lines with pure opacity changes"),
    BLUR_REVEAL("Blur Reveal", "Gently transitions lyrics from a soft blur to sharp clarity"),
    TYPEWRITER("Typewriter", "Reveals text progressively from left to right"),
    INSTANT("Instant", "Replaces the current line immediately with no animation")
}

enum class HudBackdropStyle(val title: String, val description: String) {
    BLACK("Black", "Deep neutral black surface with crisp contrast"),
    GREY("Grey", "Clean balanced neutral grey surface");

    companion object {
        fun fromName(name: String?): HudBackdropStyle {
            if (name.isNullOrBlank()) return BLACK
            return when (name.trim().uppercase(java.util.Locale.US)) {
                "GREY", "GRAY" -> GREY
                // Legacy GLASS defaults to BLACK; legacy SOLID used deep dark #121216 -> maps reliably to BLACK
                "BLACK", "GLASS", "SOLID" -> BLACK
                else -> BLACK
            }
        }

        fun shouldMigrateLegacyValue(rawValue: String?): Boolean {
            if (rawValue.isNullOrBlank()) return false
            val normalized = rawValue.trim().uppercase(java.util.Locale.US)
            return normalized == "GLASS" || normalized == "SOLID"
        }
    }
}

enum class LyricsAlignment(val code: String, val displayName: String) {
    LEFT("LEFT", "Left"),
    CENTER("CENTER", "Center"),
    RIGHT("RIGHT", "Right");

    fun next(): LyricsAlignment = when (this) {
        LEFT -> CENTER
        CENTER -> RIGHT
        RIGHT -> LEFT
    }

    companion object {
        fun fromCode(code: String?): LyricsAlignment {
            if (code.isNullOrBlank()) return CENTER
            return when (code.trim().uppercase(java.util.Locale.US)) {
                "LEFT" -> LEFT
                "RIGHT" -> RIGHT
                else -> CENTER
            }
        }
    }
}

data class UserPreferences(
    val overlayEnabled: Boolean = true,
    val overlayTextSizeSp: Float = 14f,
    val overlayTextOpacity: Float = 1.0f, // 0.2f (20%) to 1.0f (100%)
    val typewriterSpeed: Float = 1.0f, // 0.4x (Slow) to 2.0x (Fast)
    val smoothTransitionSpeed: Float = 1.0f, // 0.5x (Slow) to 1.5x (Fast)
    val fadeSpeed: Float = 1.0f, // 0.5x (Slow) to 1.5x (Fast)
    val blurRevealSpeed: Float = 1.0f, // 0.5x (Slow) to 1.5x (Fast)
    val karaokeSpeed: Float = 1.0f, // 0.5x to 1.5x (Glow Sweep Speed)
    val karaokeGlowSpeed: Float = 1.0f, // 0.5x to 1.5x (Glow Sweep Speed)
    val karaokeEnhancedGlow: Boolean = false,
    val textShadowEnabled: Boolean = true,
    val hudBackdropEnabled: Boolean = false,
    val hudBackdropStyle: HudBackdropStyle = HudBackdropStyle.BLACK,
    val hudBackdropOpacity: Float = 0.65f, // 0.0f to 1.0f (0% to 100%, default 65%)
    val hudBackdropCornerRadiusDp: Int = 18, // 0 to 32 dp (default 18dp)
    val hudBackdropHorizontalPaddingDp: Int = 16, // 0 to 32 dp (default 16dp)
    val hudBackdropVerticalPaddingDp: Int = 10, // 0 to 24 dp (default 10dp)
    val hudDynamicAccentEnabled: Boolean = false,
    val hudActiveLyricColor: Int = HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR,
    val hudInactiveLyricColor: Int = HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR,
    val hudCustomColors: List<Int> = emptyList(),
    val overlayYPosition: Int = HudPositioningEngine.DEFAULT_PORTRAIT_Y, // Portrait Y (legacy mirror)
    val overlayXPosition: Int = HudPositioningEngine.DEFAULT_PORTRAIT_X, // Portrait X (legacy mirror)
    val overlayWidthPx: Int = HudPositioningEngine.DEFAULT_PORTRAIT_WIDTH_PX, // Portrait width (legacy mirror)
    val overlayHeightPx: Int = HudPositioningEngine.DEFAULT_PORTRAIT_HEIGHT_PX, // Portrait height (legacy mirror)
    val hudPortraitX: Int = HudPositioningEngine.DEFAULT_PORTRAIT_X,
    val hudPortraitY: Int = HudPositioningEngine.DEFAULT_PORTRAIT_Y,
    val hudPortraitWidth: Int = HudPositioningEngine.DEFAULT_PORTRAIT_WIDTH_PX,
    val hudPortraitHeight: Int = HudPositioningEngine.DEFAULT_PORTRAIT_HEIGHT_PX,
    val hudLandscapeX: Int? = null,
    val hudLandscapeY: Int? = null,
    val hudLandscapeWidth: Int? = null,
    val hudLandscapeHeight: Int? = null,
    val overlayTextAlign: String = "CENTER", // "LEFT", "CENTER", "RIGHT" (Dedicated to HUD Overlay)
    val lyricsScreenAlignment: LyricsAlignment = LyricsAlignment.CENTER, // Dedicated to Lyrics Screen
    val upwardAnimationEnabled: Boolean = true,
    val animationStyle: String = OverlayAnimationStyle.KARAOKE.name,
    val isDragRepositionMode: Boolean = false,
    val clickThroughOverlay: Boolean = false,
    val autoShowOnPlayback: Boolean = true,
    val autoFetchLyrics: Boolean = true,
    val lyricsProvider: LyricsProviderPreference = LyricsProviderPreference.AUTO,
    val lyricsProviderOrder: List<LyricsProviderPreference> = LyricsProviderPreference.DEFAULT_PROVIDER_ORDER,
    val useWallpaperDynamicColor: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    val themePreset: ThemePreset = ThemePreset.ROSE_QUARTZ,
    val selectedFont: FontOption = FontOption.POPPINS,
    val selectedCustomFontId: String? = null,
    val hudBoldLyrics: Boolean = false,
    val appleMusicStyle: Boolean = true,
    val singleLineLyrics: Boolean = false,
    val adaptiveLyricScrolling: Boolean = true,
    val lyricScrollSpeedMultiplier: Float = 1.0f, // 1.0x to 1.5x
    val globalOffsetMs: Long = 0L,
    val autoUpdateChecksEnabled: Boolean = true,
    val lastUpdateCheckTimestampMs: Long = 0L,
    val lastNotifiedUpdateVersion: String? = null,
    val lastReleaseEtag: String? = null,
    val cachedLatestReleaseJson: String? = null,
    val hasRequestedUpdateNotificationPermission: Boolean = false
) {
    val hasLandscapeProfile: Boolean
        get() = hudLandscapeX != null &&
            hudLandscapeY != null &&
            hudLandscapeWidth != null &&
            hudLandscapeHeight != null

    val lyricsAlignment: LyricsAlignment
        get() = lyricsScreenAlignment

    val hudLyricsAlignment: LyricsAlignment
        get() = LyricsAlignment.fromCode(overlayTextAlign)

    fun portraitProfile(): HudOverlayProfile = HudOverlayProfile(
        x = hudPortraitX,
        y = hudPortraitY,
        widthPx = hudPortraitWidth,
        heightPx = hudPortraitHeight
    )

    fun landscapeProfileOrNull(): HudOverlayProfile? {
        val lx = hudLandscapeX ?: return null
        val ly = hudLandscapeY ?: return null
        val lw = hudLandscapeWidth ?: return null
        val lh = hudLandscapeHeight ?: return null
        return HudOverlayProfile(
            x = lx,
            y = ly,
            widthPx = lw,
            heightPx = lh
        )
    }

    fun resolveProfile(
        orientation: HudOrientation,
        displayGeometry: HudDisplayGeometry,
        minBackdropHeightPx: Int = 0
    ): HudOverlayProfile {
        return when (orientation) {
            HudOrientation.PORTRAIT -> portraitProfile()
            HudOrientation.LANDSCAPE -> landscapeProfileOrNull()
                ?: HudPositioningEngine.createDefaultLandscapeProfile(displayGeometry, minBackdropHeightPx)
        }
    }
}

class UserPreferencesRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.dataStore)

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private object PreferencesKeys {
        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val OVERLAY_TEXT_SIZE = floatPreferencesKey("overlay_text_size")
        val OVERLAY_TEXT_OPACITY = floatPreferencesKey("overlay_text_opacity")
        val TYPEWRITER_SPEED = floatPreferencesKey("typewriter_speed")
        val SMOOTH_TRANSITION_SPEED = floatPreferencesKey("smooth_transition_speed")
        val FADE_SPEED = floatPreferencesKey("fade_speed")
        val BLUR_REVEAL_SPEED = floatPreferencesKey("blur_reveal_speed")
        val KARAOKE_SPEED = floatPreferencesKey("karaoke_speed")
        val KARAOKE_GLOW_SPEED = floatPreferencesKey("karaoke_glow_speed")
        val KARAOKE_ENHANCED_GLOW = booleanPreferencesKey("karaoke_enhanced_glow")
        val OVERLAY_TEXT_SHADOW = booleanPreferencesKey("overlay_text_shadow")
        val HUD_BACKDROP_ENABLED = booleanPreferencesKey("hud_backdrop_enabled")
        val HUD_BACKDROP_STYLE = stringPreferencesKey("hud_backdrop_style")
        val HUD_BACKDROP_OPACITY = floatPreferencesKey("hud_backdrop_opacity")
        val HUD_BACKDROP_CORNER_RADIUS_DP = intPreferencesKey("hud_backdrop_corner_radius_dp")
        val HUD_BACKDROP_HORIZONTAL_PADDING_DP = intPreferencesKey("hud_backdrop_horizontal_padding_dp")
        val HUD_BACKDROP_VERTICAL_PADDING_DP = intPreferencesKey("hud_backdrop_vertical_padding_dp")
        val HUD_DYNAMIC_ACCENT_ENABLED = booleanPreferencesKey("hud_dynamic_accent_enabled")
        val HUD_ACTIVE_LYRIC_COLOR = intPreferencesKey("hud_active_lyric_color")
        val HUD_INACTIVE_LYRIC_COLOR = intPreferencesKey("hud_inactive_lyric_color")
        val HUD_CUSTOM_COLORS = stringPreferencesKey("hud_custom_colors")
        val HUD_STYLE_MIGRATION_VERSION = intPreferencesKey("hud_style_migration_version")
        val OVERLAY_Y_POS = intPreferencesKey("overlay_y_position")
        val OVERLAY_X_POS = intPreferencesKey("overlay_x_position")
        val OVERLAY_WIDTH = intPreferencesKey("overlay_width")
        val OVERLAY_HEIGHT = intPreferencesKey("overlay_height")
        val HUD_PORTRAIT_X = intPreferencesKey("hud_portrait_x")
        val HUD_PORTRAIT_Y = intPreferencesKey("hud_portrait_y")
        val HUD_PORTRAIT_WIDTH = intPreferencesKey("hud_portrait_width")
        val HUD_PORTRAIT_HEIGHT = intPreferencesKey("hud_portrait_height")
        val HUD_LANDSCAPE_X = intPreferencesKey("hud_landscape_x")
        val HUD_LANDSCAPE_Y = intPreferencesKey("hud_landscape_y")
        val HUD_LANDSCAPE_WIDTH = intPreferencesKey("hud_landscape_width")
        val HUD_LANDSCAPE_HEIGHT = intPreferencesKey("hud_landscape_height")
        val OVERLAY_TEXT_ALIGN = stringPreferencesKey("overlay_text_align")
        val LYRICS_SCREEN_ALIGNMENT = stringPreferencesKey("lyrics_screen_alignment")
        val UPWARD_ANIMATION = booleanPreferencesKey("upward_animation_enabled")
        val ANIMATION_STYLE = stringPreferencesKey("animation_style")
        val APPLE_MUSIC_MESH_BLUR = booleanPreferencesKey("apple_music_mesh_blur")
        val IS_DRAG_MODE = booleanPreferencesKey("is_drag_mode")
        val CLICK_THROUGH_OVERLAY = booleanPreferencesKey("click_through_overlay")
        val AUTO_SHOW_ON_PLAYBACK = booleanPreferencesKey("auto_show_on_playback")
        val AUTO_FETCH_LYRICS = booleanPreferencesKey("auto_fetch_lyrics")
        val LYRICS_PROVIDER = stringPreferencesKey("lyrics_provider")
        val LYRICS_PROVIDER_ORDER = stringPreferencesKey("lyrics_provider_order")
        val USE_WALLPAPER_DYNAMIC_COLOR = booleanPreferencesKey("use_wallpaper_dynamic_color")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val THEME_PRESET = stringPreferencesKey("theme_preset")
        val SELECTED_FONT = stringPreferencesKey("selected_font")
        val SELECTED_CUSTOM_FONT_ID = stringPreferencesKey("selected_custom_font_id")
        val HUD_BOLD_LYRICS = booleanPreferencesKey("hud_bold_lyrics")
        val APPLE_MUSIC_STYLE = booleanPreferencesKey("apple_music_style")
        val SINGLE_LINE_LYRICS = booleanPreferencesKey("single_line_lyrics")
        val ADAPTIVE_LYRIC_SCROLLING = booleanPreferencesKey("adaptive_lyric_scrolling")
        val LYRIC_SCROLL_SPEED_MULTIPLIER = floatPreferencesKey("lyric_scroll_speed_multiplier")
        val GLOBAL_OFFSET_MS = longPreferencesKey("global_offset_ms")
        val AUTO_UPDATE_CHECKS_ENABLED = booleanPreferencesKey("auto_update_checks_enabled")
        val LAST_UPDATE_CHECK_TIMESTAMP_MS = longPreferencesKey("last_update_check_timestamp_ms")
        val LAST_NOTIFIED_UPDATE_VERSION = stringPreferencesKey("last_notified_update_version")
        val LAST_RELEASE_ETAG = stringPreferencesKey("last_release_etag")
        val CACHED_LATEST_RELEASE_JSON = stringPreferencesKey("cached_latest_release_json")
        val HAS_REQUESTED_UPDATE_NOTIFICATION_PERMISSION = booleanPreferencesKey("has_requested_update_notification_permission")
    }

    val userPreferencesFlow: Flow<UserPreferences> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val themeModeName = preferences[PreferencesKeys.THEME_MODE] ?: ThemeMode.FOLLOW_SYSTEM.name
            val themeMode = try {
                ThemeMode.valueOf(themeModeName)
            } catch (_: Exception) {
                ThemeMode.FOLLOW_SYSTEM
            }

            val presetName = preferences[PreferencesKeys.THEME_PRESET] ?: ThemePreset.ROSE_QUARTZ.name
            val preset = try {
                when (ThemePreset.valueOf(presetName)) {
                    ThemePreset.ROSE_QUARTZ, ThemePreset.OBSIDIAN_MINT -> ThemePreset.ROSE_QUARTZ
                    ThemePreset.LAVENDER_MIST, ThemePreset.MIDNIGHT_AZURE -> ThemePreset.LAVENDER_MIST
                    ThemePreset.PEACH_EMBER, ThemePreset.SUNSET_AMBER -> ThemePreset.PEACH_EMBER
                    ThemePreset.SAGE_SERENITY, ThemePreset.CYBER_VIOLET -> ThemePreset.SAGE_SERENITY
                }
            } catch (_: Exception) {
                ThemePreset.ROSE_QUARTZ
            }

            val fontName = preferences[PreferencesKeys.SELECTED_FONT] ?: FontOption.POPPINS.name
            val font = try {
                FontOption.valueOf(fontName)
            } catch (_: Exception) {
                FontOption.POPPINS
            }

            val rawPortraitY = preferences[PreferencesKeys.HUD_PORTRAIT_Y]
                ?: preferences[PreferencesKeys.OVERLAY_Y_POS]
                ?: HudPositioningEngine.DEFAULT_PORTRAIT_Y
            val portraitY = rawPortraitY.coerceIn(0, MAX_STORED_POS_PX)

            val rawPortraitX = preferences[PreferencesKeys.HUD_PORTRAIT_X]
                ?: preferences[PreferencesKeys.OVERLAY_X_POS]
                ?: HudPositioningEngine.DEFAULT_PORTRAIT_X
            val portraitX = rawPortraitX.coerceIn(MIN_STORED_X_PX, MAX_STORED_POS_PX)

            val portraitWidth = (preferences[PreferencesKeys.HUD_PORTRAIT_WIDTH]
                ?: preferences[PreferencesKeys.OVERLAY_WIDTH]
                ?: HudPositioningEngine.DEFAULT_PORTRAIT_WIDTH_PX)
                .coerceIn(MIN_STORED_WIDTH_PX, MAX_STORED_POS_PX)
            val portraitHeight = (preferences[PreferencesKeys.HUD_PORTRAIT_HEIGHT]
                ?: preferences[PreferencesKeys.OVERLAY_HEIGHT]
                ?: HudPositioningEngine.DEFAULT_PORTRAIT_HEIGHT_PX)
                .coerceIn(MIN_STORED_HEIGHT_PX, MAX_STORED_HEIGHT_PX)

            val landscapeX = preferences[PreferencesKeys.HUD_LANDSCAPE_X]
                ?.coerceIn(MIN_STORED_X_PX, MAX_STORED_POS_PX)
            val landscapeY = preferences[PreferencesKeys.HUD_LANDSCAPE_Y]
                ?.coerceIn(0, MAX_STORED_POS_PX)
            val landscapeWidth = preferences[PreferencesKeys.HUD_LANDSCAPE_WIDTH]
                ?.coerceIn(MIN_STORED_WIDTH_PX, MAX_STORED_POS_PX)
            val landscapeHeight = preferences[PreferencesKeys.HUD_LANDSCAPE_HEIGHT]
                ?.coerceIn(MIN_STORED_HEIGHT_PX, MAX_STORED_HEIGHT_PX)

            val rawStyle = preferences[PreferencesKeys.ANIMATION_STYLE] ?: OverlayAnimationStyle.KARAOKE.name
            val animStyle = when (rawStyle) {
                "APPLE_MUSIC", "KARAOKE" -> OverlayAnimationStyle.KARAOKE.name
                "SMOOTH_SLIDE_UP", "SOFT_FADE", "SCALE_POP", "SMOOTH_SLIDE" -> OverlayAnimationStyle.SMOOTH_SLIDE.name
                "FADE", "FADE_IN_OUT" -> OverlayAnimationStyle.FADE.name
                "BLUR_REVEAL", "BLUR" -> OverlayAnimationStyle.BLUR_REVEAL.name
                "TYPEWRITER" -> OverlayAnimationStyle.TYPEWRITER.name
                "MINIMAL_STATIC", "INSTANT" -> OverlayAnimationStyle.INSTANT.name
                else -> OverlayAnimationStyle.KARAOKE.name
            }

            val lyricsProvider = LyricsProviderPreference.fromPersistedValue(
                preferences[PreferencesKeys.LYRICS_PROVIDER]
            )

            UserPreferences(
                overlayEnabled = preferences[PreferencesKeys.OVERLAY_ENABLED] ?: true,
                overlayTextSizeSp = preferences[PreferencesKeys.OVERLAY_TEXT_SIZE] ?: 14f,
                overlayTextOpacity = (preferences[PreferencesKeys.OVERLAY_TEXT_OPACITY] ?: 1.0f).coerceIn(0.15f, 1.0f),
                typewriterSpeed = (preferences[PreferencesKeys.TYPEWRITER_SPEED] ?: 1.0f).coerceIn(0.3f, 3.0f),
                smoothTransitionSpeed = (preferences[PreferencesKeys.SMOOTH_TRANSITION_SPEED] ?: 1.0f).coerceIn(0.3f, 3.0f),
                fadeSpeed = (preferences[PreferencesKeys.FADE_SPEED] ?: 1.0f).coerceIn(0.3f, 3.0f),
                blurRevealSpeed = (preferences[PreferencesKeys.BLUR_REVEAL_SPEED] ?: 1.0f).coerceIn(0.3f, 3.0f),
                karaokeSpeed = (preferences[PreferencesKeys.KARAOKE_GLOW_SPEED] ?: preferences[PreferencesKeys.KARAOKE_SPEED] ?: 1.0f).coerceIn(0.5f, 1.5f),
                karaokeGlowSpeed = (preferences[PreferencesKeys.KARAOKE_GLOW_SPEED] ?: preferences[PreferencesKeys.KARAOKE_SPEED] ?: 1.0f).coerceIn(0.5f, 1.5f),
                karaokeEnhancedGlow = preferences[PreferencesKeys.KARAOKE_ENHANCED_GLOW] ?: false,
                textShadowEnabled = preferences[PreferencesKeys.OVERLAY_TEXT_SHADOW] ?: true,
                hudBackdropEnabled = preferences[PreferencesKeys.HUD_BACKDROP_ENABLED] ?: false,
                hudBackdropStyle = HudBackdropStyle.fromName(preferences[PreferencesKeys.HUD_BACKDROP_STYLE]),
                hudBackdropOpacity = (preferences[PreferencesKeys.HUD_BACKDROP_OPACITY] ?: 0.65f).coerceIn(0.0f, 1.0f),
                hudBackdropCornerRadiusDp = (preferences[PreferencesKeys.HUD_BACKDROP_CORNER_RADIUS_DP] ?: 18).coerceIn(0, 32),
                hudBackdropHorizontalPaddingDp = (preferences[PreferencesKeys.HUD_BACKDROP_HORIZONTAL_PADDING_DP] ?: 16).coerceIn(0, 32),
                hudBackdropVerticalPaddingDp = (preferences[PreferencesKeys.HUD_BACKDROP_VERTICAL_PADDING_DP] ?: 10).coerceIn(0, 24),
                hudDynamicAccentEnabled = preferences[PreferencesKeys.HUD_DYNAMIC_ACCENT_ENABLED] ?: false,
                hudActiveLyricColor = HudColorSystem.sanitizeOpaqueColor(
                    preferences[PreferencesKeys.HUD_ACTIVE_LYRIC_COLOR],
                    HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR
                ),
                hudInactiveLyricColor = HudColorSystem.sanitizeOpaqueColor(
                    preferences[PreferencesKeys.HUD_INACTIVE_LYRIC_COLOR],
                    HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR
                ),
                hudCustomColors = HudColorSystem.deserializeCustomColors(
                    preferences[PreferencesKeys.HUD_CUSTOM_COLORS]
                ),
                overlayYPosition = portraitY,
                overlayXPosition = portraitX,
                overlayWidthPx = portraitWidth,
                overlayHeightPx = portraitHeight,
                hudPortraitX = portraitX,
                hudPortraitY = portraitY,
                hudPortraitWidth = portraitWidth,
                hudPortraitHeight = portraitHeight,
                hudLandscapeX = landscapeX,
                hudLandscapeY = landscapeY,
                hudLandscapeWidth = landscapeWidth,
                hudLandscapeHeight = landscapeHeight,
                overlayTextAlign = preferences[PreferencesKeys.OVERLAY_TEXT_ALIGN] ?: "CENTER",
                lyricsScreenAlignment = LyricsAlignment.fromCode(preferences[PreferencesKeys.LYRICS_SCREEN_ALIGNMENT]),
                upwardAnimationEnabled = preferences[PreferencesKeys.UPWARD_ANIMATION] ?: true,
                animationStyle = animStyle,
                isDragRepositionMode = preferences[PreferencesKeys.IS_DRAG_MODE] ?: false,
                clickThroughOverlay = preferences[PreferencesKeys.CLICK_THROUGH_OVERLAY] ?: false,
                autoShowOnPlayback = preferences[PreferencesKeys.AUTO_SHOW_ON_PLAYBACK] ?: true,
                autoFetchLyrics = preferences[PreferencesKeys.AUTO_FETCH_LYRICS] ?: true,
                lyricsProvider = lyricsProvider,
                lyricsProviderOrder = LyricsProviderPreference.parseProviderOrder(
                    preferences[PreferencesKeys.LYRICS_PROVIDER_ORDER]
                ),
                useWallpaperDynamicColor = preferences[PreferencesKeys.USE_WALLPAPER_DYNAMIC_COLOR] ?: false,
                themeMode = themeMode,
                themePreset = preset,
                selectedFont = font,
                selectedCustomFontId = preferences[PreferencesKeys.SELECTED_CUSTOM_FONT_ID]?.takeIf { it.isNotBlank() },
                hudBoldLyrics = preferences[PreferencesKeys.HUD_BOLD_LYRICS] ?: false,
                appleMusicStyle = preferences[PreferencesKeys.APPLE_MUSIC_STYLE] ?: true,
                singleLineLyrics = preferences[PreferencesKeys.SINGLE_LINE_LYRICS] ?: false,
                adaptiveLyricScrolling = preferences[PreferencesKeys.ADAPTIVE_LYRIC_SCROLLING] ?: true,
                lyricScrollSpeedMultiplier = (preferences[PreferencesKeys.LYRIC_SCROLL_SPEED_MULTIPLIER] ?: 1.0f).coerceIn(1.0f, 1.5f),
                globalOffsetMs = preferences[PreferencesKeys.GLOBAL_OFFSET_MS] ?: 0L,
                autoUpdateChecksEnabled = preferences[PreferencesKeys.AUTO_UPDATE_CHECKS_ENABLED] ?: true,
                lastUpdateCheckTimestampMs = (preferences[PreferencesKeys.LAST_UPDATE_CHECK_TIMESTAMP_MS] ?: 0L).coerceAtLeast(0L),
                lastNotifiedUpdateVersion = preferences[PreferencesKeys.LAST_NOTIFIED_UPDATE_VERSION]?.takeIf { it.isNotBlank() },
                lastReleaseEtag = preferences[PreferencesKeys.LAST_RELEASE_ETAG]?.takeIf { it.isNotBlank() },
                cachedLatestReleaseJson = preferences[PreferencesKeys.CACHED_LATEST_RELEASE_JSON]?.takeIf { it.isNotBlank() },
                hasRequestedUpdateNotificationPermission = preferences[PreferencesKeys.HAS_REQUESTED_UPDATE_NOTIFICATION_PERMISSION] ?: false
            )
        }

    val lyricsProviderFlow: Flow<LyricsProviderPreference> = userPreferencesFlow
        .map { it.lyricsProvider }
        .distinctUntilChanged()

    suspend fun setLyricsProvider(provider: LyricsProviderPreference) {
        dataStore.edit { it[PreferencesKeys.LYRICS_PROVIDER] = provider.name }
    }

    suspend fun setLyricsProvider(rawProvider: String?) {
        val resolved = LyricsProviderPreference.fromPersistedValue(rawProvider)
        setLyricsProvider(resolved)
    }

    val lyricsProviderOrderFlow: Flow<List<LyricsProviderPreference>> = userPreferencesFlow
        .map { it.lyricsProviderOrder }
        .distinctUntilChanged()

    suspend fun setLyricsProviderOrder(order: List<LyricsProviderPreference>) {
        val serialized = LyricsProviderPreference.serializeProviderOrder(order)
        dataStore.edit { it[PreferencesKeys.LYRICS_PROVIDER_ORDER] = serialized }
    }

    suspend fun setAnimationStyle(style: String) {
        dataStore.edit { it[PreferencesKeys.ANIMATION_STYLE] = style }
    }

    suspend fun setOverlayTextOpacity(opacity: Float) {
        val bounded = opacity.coerceIn(0.15f, 1.0f)
        dataStore.edit { it[PreferencesKeys.OVERLAY_TEXT_OPACITY] = bounded }
    }

    suspend fun setTypewriterSpeed(speed: Float) {
        val bounded = speed.coerceIn(0.3f, 3.0f)
        dataStore.edit { it[PreferencesKeys.TYPEWRITER_SPEED] = bounded }
    }

    suspend fun setSmoothTransitionSpeed(speed: Float) {
        val bounded = speed.coerceIn(0.3f, 3.0f)
        dataStore.edit { it[PreferencesKeys.SMOOTH_TRANSITION_SPEED] = bounded }
    }

    suspend fun setFadeSpeed(speed: Float) {
        val bounded = speed.coerceIn(0.3f, 3.0f)
        dataStore.edit { it[PreferencesKeys.FADE_SPEED] = bounded }
    }

    suspend fun setBlurRevealSpeed(speed: Float) {
        val bounded = speed.coerceIn(0.3f, 3.0f)
        dataStore.edit { it[PreferencesKeys.BLUR_REVEAL_SPEED] = bounded }
    }

    suspend fun setKaraokeSpeed(speed: Float) {
        val bounded = speed.coerceIn(0.5f, 1.5f)
        dataStore.edit {
            it[PreferencesKeys.KARAOKE_SPEED] = bounded
            it[PreferencesKeys.KARAOKE_GLOW_SPEED] = bounded
        }
    }

    suspend fun setKaraokeGlowSpeed(speed: Float) {
        setKaraokeSpeed(speed)
    }

    suspend fun setKaraokeEnhancedGlow(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.KARAOKE_ENHANCED_GLOW] = enabled }
    }

    suspend fun setAppleMusicStyle(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.APPLE_MUSIC_STYLE] = enabled }
    }

    suspend fun setSingleLineLyrics(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.SINGLE_LINE_LYRICS] = enabled }
    }

    suspend fun setAdaptiveLyricScrolling(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.ADAPTIVE_LYRIC_SCROLLING] = enabled }
    }

    suspend fun setLyricScrollSpeedMultiplier(multiplier: Float) {
        val bounded = multiplier.coerceIn(1.0f, 1.5f)
        dataStore.edit { it[PreferencesKeys.LYRIC_SCROLL_SPEED_MULTIPLIER] = bounded }
    }

    suspend fun setOverlayEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.OVERLAY_ENABLED] = enabled }
    }

    suspend fun setOverlayTextSize(sizeSp: Float) {
        dataStore.edit { it[PreferencesKeys.OVERLAY_TEXT_SIZE] = sizeSp }
    }

    suspend fun setTextShadowEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.OVERLAY_TEXT_SHADOW] = enabled }
    }

    suspend fun setHudBackdropEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.HUD_BACKDROP_ENABLED] = enabled }
    }

    suspend fun setHudBackdropStyle(style: HudBackdropStyle) {
        dataStore.edit { it[PreferencesKeys.HUD_BACKDROP_STYLE] = style.name }
    }

    suspend fun setHudBackdropOpacity(opacity: Float) {
        val bounded = opacity.coerceIn(0.0f, 1.0f)
        dataStore.edit { it[PreferencesKeys.HUD_BACKDROP_OPACITY] = bounded }
    }

    suspend fun setHudBackdropCornerRadiusDp(cornerRadiusDp: Int) {
        val bounded = cornerRadiusDp.coerceIn(0, 32)
        dataStore.edit { it[PreferencesKeys.HUD_BACKDROP_CORNER_RADIUS_DP] = bounded }
    }

    suspend fun setHudBackdropHorizontalPaddingDp(horizontalPaddingDp: Int) {
        val bounded = horizontalPaddingDp.coerceIn(0, 32)
        dataStore.edit { it[PreferencesKeys.HUD_BACKDROP_HORIZONTAL_PADDING_DP] = bounded }
    }

    suspend fun setHudBackdropVerticalPaddingDp(verticalPaddingDp: Int) {
        val bounded = verticalPaddingDp.coerceIn(0, 24)
        dataStore.edit { it[PreferencesKeys.HUD_BACKDROP_VERTICAL_PADDING_DP] = bounded }
    }

    suspend fun setHudDynamicAccentEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.HUD_DYNAMIC_ACCENT_ENABLED] = enabled }
    }

    suspend fun setHudActiveLyricColor(colorArgb: Int) {
        val safe = HudColorSystem.sanitizeOpaqueColor(colorArgb, HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR)
        dataStore.edit { it[PreferencesKeys.HUD_ACTIVE_LYRIC_COLOR] = safe }
    }

    suspend fun setHudInactiveLyricColor(colorArgb: Int) {
        val safe = HudColorSystem.sanitizeOpaqueColor(colorArgb, HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR)
        dataStore.edit { it[PreferencesKeys.HUD_INACTIVE_LYRIC_COLOR] = safe }
    }

    suspend fun applyHudColorPair(activeColorArgb: Int, inactiveColorArgb: Int) {
        val safeActive = HudColorSystem.sanitizeOpaqueColor(activeColorArgb, HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR)
        val safeInactive = HudColorSystem.sanitizeOpaqueColor(inactiveColorArgb, HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR)
        dataStore.edit {
            it[PreferencesKeys.HUD_ACTIVE_LYRIC_COLOR] = safeActive
            it[PreferencesKeys.HUD_INACTIVE_LYRIC_COLOR] = safeInactive
        }
    }

    suspend fun saveCustomHudColor(colorArgb: Int) {
        val safe = HudColorSystem.sanitizeOpaqueColor(colorArgb, HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR)
        dataStore.edit { prefs ->
            val current = HudColorSystem.deserializeCustomColors(prefs[PreferencesKeys.HUD_CUSTOM_COLORS])
            val isBuiltInPreset = HudColorSystem.curatedPresets.any { it.argb == safe }
            if (!isBuiltInPreset) {
                val updated = listOf(safe) + current.filterNot { it == safe }
                prefs[PreferencesKeys.HUD_CUSTOM_COLORS] = HudColorSystem.serializeCustomColors(updated)
            }
        }
    }

    /**
     * Idempotent upgrade migration from Glass/Solid backdrop styles to Black/Grey.
     * - Legacy GLASS migrates to BLACK.
     * - Legacy SOLID (previously deep dark #121216) maps reliably to BLACK.
     * - Ambiguous/unknown strings are left untouched on disk and read via safe default BLACK.
     * - Preserves opacity, corner radius, padding, and all unrelated preferences.
     */
    suspend fun migrateLegacyPreferencesIfNeeded() {
        dataStore.edit { prefs ->
            val migratedVersion = prefs[PreferencesKeys.HUD_STYLE_MIGRATION_VERSION] ?: 0
            if (migratedVersion >= CURRENT_HUD_STYLE_MIGRATION_VERSION) {
                return@edit
            }
            val rawStyle = prefs[PreferencesKeys.HUD_BACKDROP_STYLE]
            if (HudBackdropStyle.shouldMigrateLegacyValue(rawStyle)) {
                prefs[PreferencesKeys.HUD_BACKDROP_STYLE] = HudBackdropStyle.BLACK.name
            }
            prefs[PreferencesKeys.HUD_STYLE_MIGRATION_VERSION] = CURRENT_HUD_STYLE_MIGRATION_VERSION
        }
    }

    suspend fun setOverlayPosition(
        x: Int,
        y: Int,
        orientation: HudOrientation = HudOrientation.PORTRAIT
    ) {
        val boundedX = x.coerceIn(MIN_STORED_X_PX, MAX_STORED_POS_PX)
        val boundedY = y.coerceIn(0, MAX_STORED_POS_PX)
        dataStore.edit {
            when (orientation) {
                HudOrientation.PORTRAIT -> {
                    it[PreferencesKeys.HUD_PORTRAIT_X] = boundedX
                    it[PreferencesKeys.HUD_PORTRAIT_Y] = boundedY
                    it[PreferencesKeys.OVERLAY_X_POS] = boundedX
                    it[PreferencesKeys.OVERLAY_Y_POS] = boundedY
                }
                HudOrientation.LANDSCAPE -> {
                    it[PreferencesKeys.HUD_LANDSCAPE_X] = boundedX
                    it[PreferencesKeys.HUD_LANDSCAPE_Y] = boundedY
                }
            }
        }
    }

    suspend fun setOverlayBounds(
        x: Int,
        y: Int,
        widthPx: Int,
        heightPx: Int,
        orientation: HudOrientation = HudOrientation.PORTRAIT
    ) {
        val boundedX = x.coerceIn(MIN_STORED_X_PX, MAX_STORED_POS_PX)
        val boundedY = y.coerceIn(0, MAX_STORED_POS_PX)
        val boundedW = widthPx.coerceIn(MIN_STORED_WIDTH_PX, MAX_STORED_POS_PX)
        val boundedH = heightPx.coerceIn(MIN_STORED_HEIGHT_PX, MAX_STORED_HEIGHT_PX)
        dataStore.edit {
            when (orientation) {
                HudOrientation.PORTRAIT -> {
                    it[PreferencesKeys.HUD_PORTRAIT_X] = boundedX
                    it[PreferencesKeys.HUD_PORTRAIT_Y] = boundedY
                    it[PreferencesKeys.HUD_PORTRAIT_WIDTH] = boundedW
                    it[PreferencesKeys.HUD_PORTRAIT_HEIGHT] = boundedH
                    it[PreferencesKeys.OVERLAY_X_POS] = boundedX
                    it[PreferencesKeys.OVERLAY_Y_POS] = boundedY
                    it[PreferencesKeys.OVERLAY_WIDTH] = boundedW
                    it[PreferencesKeys.OVERLAY_HEIGHT] = boundedH
                }
                HudOrientation.LANDSCAPE -> {
                    it[PreferencesKeys.HUD_LANDSCAPE_X] = boundedX
                    it[PreferencesKeys.HUD_LANDSCAPE_Y] = boundedY
                    it[PreferencesKeys.HUD_LANDSCAPE_WIDTH] = boundedW
                    it[PreferencesKeys.HUD_LANDSCAPE_HEIGHT] = boundedH
                }
            }
        }
    }

    suspend fun setOverlayDimensions(
        widthPx: Int,
        heightPx: Int,
        orientation: HudOrientation = HudOrientation.PORTRAIT,
        constraints: HudBoundsConstraints? = null
    ) {
        val boundedW = widthPx.coerceIn(MIN_STORED_WIDTH_PX, MAX_STORED_POS_PX)
        val boundedH = heightPx.coerceIn(MIN_STORED_HEIGHT_PX, MAX_STORED_HEIGHT_PX)
        dataStore.edit {
            when (orientation) {
                HudOrientation.PORTRAIT -> {
                    it[PreferencesKeys.HUD_PORTRAIT_WIDTH] = boundedW
                    it[PreferencesKeys.HUD_PORTRAIT_HEIGHT] = boundedH
                    it[PreferencesKeys.OVERLAY_WIDTH] = boundedW
                    it[PreferencesKeys.OVERLAY_HEIGHT] = boundedH
                    if (constraints != null) {
                        val curX = it[PreferencesKeys.HUD_PORTRAIT_X]
                            ?: it[PreferencesKeys.OVERLAY_X_POS]
                            ?: HudPositioningEngine.DEFAULT_PORTRAIT_X
                        val curY = it[PreferencesKeys.HUD_PORTRAIT_Y]
                            ?: it[PreferencesKeys.OVERLAY_Y_POS]
                            ?: HudPositioningEngine.DEFAULT_PORTRAIT_Y
                        val clampedX = curX.coerceIn(constraints.minX, constraints.maxX)
                        val clampedY = curY.coerceIn(constraints.minY, constraints.maxY)
                        it[PreferencesKeys.HUD_PORTRAIT_X] = clampedX
                        it[PreferencesKeys.HUD_PORTRAIT_Y] = clampedY
                        it[PreferencesKeys.OVERLAY_X_POS] = clampedX
                        it[PreferencesKeys.OVERLAY_Y_POS] = clampedY
                    }
                }
                HudOrientation.LANDSCAPE -> {
                    it[PreferencesKeys.HUD_LANDSCAPE_WIDTH] = boundedW
                    it[PreferencesKeys.HUD_LANDSCAPE_HEIGHT] = boundedH
                    if (constraints != null) {
                        val curX = it[PreferencesKeys.HUD_LANDSCAPE_X]
                        val curY = it[PreferencesKeys.HUD_LANDSCAPE_Y]
                        if (curX != null && curY != null) {
                            it[PreferencesKeys.HUD_LANDSCAPE_X] = curX.coerceIn(constraints.minX, constraints.maxX)
                            it[PreferencesKeys.HUD_LANDSCAPE_Y] = curY.coerceIn(constraints.minY, constraints.maxY)
                        }
                    }
                }
            }
        }
    }

    suspend fun nudgeOverlayPosition(
        deltaX: Int,
        deltaY: Int,
        orientation: HudOrientation = HudOrientation.PORTRAIT,
        constraints: HudBoundsConstraints? = null,
        fallbackProfile: HudOverlayProfile? = null
    ) {
        dataStore.edit {
            val minX = constraints?.minX ?: MIN_STORED_X_PX
            val maxX = constraints?.maxX ?: MAX_STORED_POS_PX
            val minY = constraints?.minY ?: 0
            val maxY = constraints?.maxY ?: MAX_STORED_POS_PX

            when (orientation) {
                HudOrientation.PORTRAIT -> {
                    val baseX = it[PreferencesKeys.HUD_PORTRAIT_X]
                        ?: it[PreferencesKeys.OVERLAY_X_POS]
                        ?: fallbackProfile?.x
                        ?: HudPositioningEngine.DEFAULT_PORTRAIT_X
                    val baseY = it[PreferencesKeys.HUD_PORTRAIT_Y]
                        ?: it[PreferencesKeys.OVERLAY_Y_POS]
                        ?: fallbackProfile?.y
                        ?: HudPositioningEngine.DEFAULT_PORTRAIT_Y
                    val nextX = (baseX + deltaX).coerceIn(minX, maxX)
                    val nextY = (baseY + deltaY).coerceIn(minY, maxY)
                    it[PreferencesKeys.HUD_PORTRAIT_X] = nextX
                    it[PreferencesKeys.HUD_PORTRAIT_Y] = nextY
                    it[PreferencesKeys.OVERLAY_X_POS] = nextX
                    it[PreferencesKeys.OVERLAY_Y_POS] = nextY
                }
                HudOrientation.LANDSCAPE -> {
                    val baseX = it[PreferencesKeys.HUD_LANDSCAPE_X] ?: fallbackProfile?.x ?: minX
                    val baseY = it[PreferencesKeys.HUD_LANDSCAPE_Y] ?: fallbackProfile?.y ?: minY
                    val nextX = (baseX + deltaX).coerceIn(minX, maxX)
                    val nextY = (baseY + deltaY).coerceIn(minY, maxY)
                    it[PreferencesKeys.HUD_LANDSCAPE_X] = nextX
                    it[PreferencesKeys.HUD_LANDSCAPE_Y] = nextY
                    if (it[PreferencesKeys.HUD_LANDSCAPE_WIDTH] == null && fallbackProfile != null) {
                        it[PreferencesKeys.HUD_LANDSCAPE_WIDTH] = fallbackProfile.widthPx.coerceIn(MIN_STORED_WIDTH_PX, MAX_STORED_POS_PX)
                    }
                    if (it[PreferencesKeys.HUD_LANDSCAPE_HEIGHT] == null && fallbackProfile != null) {
                        it[PreferencesKeys.HUD_LANDSCAPE_HEIGHT] = fallbackProfile.heightPx.coerceIn(MIN_STORED_HEIGHT_PX, MAX_STORED_HEIGHT_PX)
                    }
                }
            }
        }
    }

    suspend fun ensureLandscapeProfile(defaultProfile: HudOverlayProfile) {
        val boundedX = defaultProfile.x.coerceIn(MIN_STORED_X_PX, MAX_STORED_POS_PX)
        val boundedY = defaultProfile.y.coerceIn(0, MAX_STORED_POS_PX)
        val boundedW = defaultProfile.widthPx.coerceIn(MIN_STORED_WIDTH_PX, MAX_STORED_POS_PX)
        val boundedH = defaultProfile.heightPx.coerceIn(MIN_STORED_HEIGHT_PX, MAX_STORED_HEIGHT_PX)

        dataStore.edit { prefs ->
            val alreadyInitialized = prefs[PreferencesKeys.HUD_LANDSCAPE_X] != null &&
                prefs[PreferencesKeys.HUD_LANDSCAPE_Y] != null &&
                prefs[PreferencesKeys.HUD_LANDSCAPE_WIDTH] != null &&
                prefs[PreferencesKeys.HUD_LANDSCAPE_HEIGHT] != null
            if (!alreadyInitialized) {
                if (prefs[PreferencesKeys.HUD_LANDSCAPE_X] == null) {
                    prefs[PreferencesKeys.HUD_LANDSCAPE_X] = boundedX
                }
                if (prefs[PreferencesKeys.HUD_LANDSCAPE_Y] == null) {
                    prefs[PreferencesKeys.HUD_LANDSCAPE_Y] = boundedY
                }
                if (prefs[PreferencesKeys.HUD_LANDSCAPE_WIDTH] == null) {
                    prefs[PreferencesKeys.HUD_LANDSCAPE_WIDTH] = boundedW
                }
                if (prefs[PreferencesKeys.HUD_LANDSCAPE_HEIGHT] == null) {
                    prefs[PreferencesKeys.HUD_LANDSCAPE_HEIGHT] = boundedH
                }
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: UserPreferencesRepository? = null

        /**
         * MIN-02: Singleton accessor to ensure a single shared instance of UserPreferencesRepository
         * and DataStore flow collector across MediaPlaybackManager, UpdateManager, and workers.
         */
        fun getInstance(context: Context): UserPreferencesRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserPreferencesRepository(context.applicationContext).also { INSTANCE = it }
            }
        }

        private const val CURRENT_HUD_STYLE_MIGRATION_VERSION = 1
        private const val MIN_STORED_X_PX = -4000
        private const val MAX_STORED_POS_PX = 8000
        private const val MIN_STORED_WIDTH_PX = 120
        private const val MIN_STORED_HEIGHT_PX = 40
        private const val MAX_STORED_HEIGHT_PX = 4000

        const val TRACK_OFFSET_PREFIX_SHA256 = "offset_sha256_"
        const val TRACK_OFFSET_PREFIX_LEGACY = "offset_"

        fun computeTrackOffsetKey(trackKey: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(trackKey.toByteArray(Charsets.UTF_8))
            val sb = java.lang.StringBuilder(TRACK_OFFSET_PREFIX_SHA256.length + bytes.size * 2)
            sb.append(TRACK_OFFSET_PREFIX_SHA256)
            for (b in bytes) {
                val octet = b.toInt() and 0xFF
                if (octet < 16) sb.append('0')
                sb.append(java.lang.Integer.toHexString(octet))
            }
            return sb.toString()
        }

        fun computeLegacyTrackOffsetKey(trackKey: String): String {
            return "$TRACK_OFFSET_PREFIX_LEGACY${trackKey.hashCode()}"
        }
    }

    suspend fun cycleLyricsScreenAlignment(): LyricsAlignment {
        var nextAlignment: LyricsAlignment = LyricsAlignment.CENTER
        dataStore.edit { prefs ->
            val currentCode = prefs[PreferencesKeys.LYRICS_SCREEN_ALIGNMENT] ?: "CENTER"
            val current = LyricsAlignment.fromCode(currentCode)
            val next = current.next()
            prefs[PreferencesKeys.LYRICS_SCREEN_ALIGNMENT] = next.code
            nextAlignment = next
        }
        return nextAlignment
    }

    suspend fun setLyricsScreenAlignment(alignment: LyricsAlignment) {
        dataStore.edit { it[PreferencesKeys.LYRICS_SCREEN_ALIGNMENT] = alignment.code }
    }

    suspend fun cycleLyricsAlignment(): LyricsAlignment = cycleLyricsScreenAlignment()

    suspend fun setLyricsAlignment(alignment: LyricsAlignment) = setLyricsScreenAlignment(alignment)

    suspend fun setOverlayTextAlign(align: String) {
        val normalized = LyricsAlignment.fromCode(align).code
        dataStore.edit { it[PreferencesKeys.OVERLAY_TEXT_ALIGN] = normalized }
    }

    suspend fun setUpwardAnimationEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.UPWARD_ANIMATION] = enabled }
    }

    suspend fun setDragRepositionMode(dragMode: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_DRAG_MODE] = dragMode }
    }

    suspend fun setClickThroughOverlay(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.CLICK_THROUGH_OVERLAY] = enabled }
    }

    suspend fun setAutoShowOnPlayback(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.AUTO_SHOW_ON_PLAYBACK] = enabled }
    }

    suspend fun setAutoFetchLyrics(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.AUTO_FETCH_LYRICS] = enabled }
    }

    suspend fun setUseWallpaperDynamicColor(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.USE_WALLPAPER_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setThemePreset(preset: ThemePreset) {
        dataStore.edit { it[PreferencesKeys.THEME_PRESET] = preset.name }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[PreferencesKeys.THEME_MODE] = mode.name }
    }

    suspend fun setSelectedFont(font: FontOption) {
        dataStore.edit {
            it[PreferencesKeys.SELECTED_FONT] = font.name
            it.remove(PreferencesKeys.SELECTED_CUSTOM_FONT_ID)
        }
    }

    suspend fun setSelectedCustomFontId(fontId: String?) {
        dataStore.edit {
            val cleaned = fontId?.trim()?.takeIf { id -> id.isNotEmpty() }
            if (cleaned != null) {
                it[PreferencesKeys.SELECTED_CUSTOM_FONT_ID] = cleaned
            } else {
                it.remove(PreferencesKeys.SELECTED_CUSTOM_FONT_ID)
            }
        }
    }

    suspend fun clearCustomFontIfSelected(fontId: String): Boolean {
        return clearCustomFontsIfSelected(setOf(fontId))
    }

    suspend fun clearCustomFontsIfSelected(fontIds: Set<String>): Boolean {
        if (fontIds.isEmpty()) return false
        var reset = false
        dataStore.edit { prefs ->
            val current = prefs[PreferencesKeys.SELECTED_CUSTOM_FONT_ID]
            if (current != null && current in fontIds) {
                prefs.remove(PreferencesKeys.SELECTED_CUSTOM_FONT_ID)
                reset = true
            }
        }
        return reset
    }

    suspend fun setHudBoldLyrics(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.HUD_BOLD_LYRICS] = enabled }
    }

    suspend fun setTrackOffset(trackKey: String, offsetMs: Long) {
        if (trackKey.isBlank()) return
        val key = longPreferencesKey(computeTrackOffsetKey(trackKey))
        dataStore.edit { it[key] = offsetMs }
    }

    fun getTrackOffsetFlow(trackKey: String, fallbackKey: String? = null): Flow<Long> {
        if (trackKey.isBlank()) return flowOf(0L)
        val newPrimaryKey = longPreferencesKey(computeTrackOffsetKey(trackKey))
        val legacyPrimaryKey = longPreferencesKey(computeLegacyTrackOffsetKey(trackKey))
        val newAltKey = fallbackKey?.takeIf { it.isNotBlank() && it != trackKey }
            ?.let { longPreferencesKey(computeTrackOffsetKey(it)) }
        val legacyAltKey = fallbackKey?.takeIf { it.isNotBlank() && it != trackKey }
            ?.let { longPreferencesKey(computeLegacyTrackOffsetKey(it)) }

        return dataStore.data.map { preferences ->
            val newPrimaryVal = preferences[newPrimaryKey]
            val legacyPrimaryVal = preferences[legacyPrimaryKey]
            val newAltVal = newAltKey?.let { preferences[it] }
            val legacyAltVal = legacyAltKey?.let { preferences[it] }

            when {
                newPrimaryVal != null -> newPrimaryVal
                legacyPrimaryVal != null -> {
                    triggerOffsetMigration(newPrimaryKey, legacyPrimaryVal)
                    legacyPrimaryVal
                }
                newAltVal != null -> newAltVal
                legacyAltVal != null -> {
                    if (newAltKey != null) {
                        triggerOffsetMigration(newAltKey, legacyAltVal)
                    }
                    legacyAltVal
                }
                else -> 0L
            }
        }.distinctUntilChanged()
    }

    private fun triggerOffsetMigration(newKey: Preferences.Key<Long>, legacyValue: Long) {
        repositoryScope.launch {
            try {
                dataStore.edit { prefs ->
                    if (!prefs.contains(newKey)) {
                        prefs[newKey] = legacyValue
                    }
                }
            } catch (_: Exception) {
                // Non-critical background migration; in-memory fallback will continue serving legacy value
            }
        }
    }

    suspend fun migrateTrackOffset(trackKey: String): Boolean {
        if (trackKey.isBlank()) return false
        val newKey = longPreferencesKey(computeTrackOffsetKey(trackKey))
        val legacyKey = longPreferencesKey(computeLegacyTrackOffsetKey(trackKey))
        var migrated = false
        dataStore.edit { prefs ->
            val legacyVal = prefs[legacyKey]
            if (legacyVal != null && !prefs.contains(newKey)) {
                prefs[newKey] = legacyVal
                migrated = true
            }
        }
        return migrated
    }

    suspend fun setAutoUpdateChecksEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.AUTO_UPDATE_CHECKS_ENABLED] = enabled }
    }

    suspend fun setLastUpdateCheckTimestampMs(timestampMs: Long) {
        val safeTs = timestampMs.coerceAtLeast(0L)
        dataStore.edit { it[PreferencesKeys.LAST_UPDATE_CHECK_TIMESTAMP_MS] = safeTs }
    }

    suspend fun setLastNotifiedUpdateVersion(version: String?) {
        dataStore.edit { prefs ->
            val trimmed = version?.trim()
            if (trimmed.isNullOrEmpty()) {
                prefs.remove(PreferencesKeys.LAST_NOTIFIED_UPDATE_VERSION)
            } else {
                prefs[PreferencesKeys.LAST_NOTIFIED_UPDATE_VERSION] = trimmed
            }
        }
    }

    suspend fun saveCachedReleaseMetadata(etag: String?, rawJson: String?) {
        dataStore.edit { prefs ->
            val cleanEtag = etag?.trim()
            if (cleanEtag.isNullOrEmpty()) {
                prefs.remove(PreferencesKeys.LAST_RELEASE_ETAG)
            } else {
                prefs[PreferencesKeys.LAST_RELEASE_ETAG] = cleanEtag
            }
            if (rawJson.isNullOrBlank()) {
                prefs.remove(PreferencesKeys.CACHED_LATEST_RELEASE_JSON)
            } else {
                prefs[PreferencesKeys.CACHED_LATEST_RELEASE_JSON] = rawJson
            }
        }
    }

    suspend fun setHasRequestedUpdateNotificationPermission(requested: Boolean) {
        dataStore.edit { it[PreferencesKeys.HAS_REQUESTED_UPDATE_NOTIFICATION_PERMISSION] = requested }
    }

    /**
     * Reads only user-configurable App Settings keys (excluding HUD layout/appearance,
     * transient runtime state, internal migration flags, and updater caches).
     */
    suspend fun readBackupAppSettings(): com.example.data.backup.BackupAppSettingsData {
        val raw = readPreferencesSnapshotOnce()
        return extractBackupAppSettings(raw)
    }

    /**
     * Reads only persistent Floating HUD configuration keys (excluding transient drag mode
     * and all unrelated App Settings or updater runtime keys).
     */
    suspend fun readBackupHudConfig(): com.example.data.backup.BackupHudConfigData {
        val raw = readPreferencesSnapshotOnce()
        return extractBackupHudConfig(raw)
    }

    /**
     * Atomically applies ONLY the non-null backup categories to DataStore.
     * Keys belonging to unselected categories and all excluded internal/runtime keys
     * are strictly untouched.
     */
    suspend fun applyBackupPreferences(
        appSettings: com.example.data.backup.BackupAppSettingsData? = null,
        hudConfig: com.example.data.backup.BackupHudConfigData? = null
    ) {
        if (appSettings == null && hudConfig == null) return
        dataStore.edit { prefs ->
            if (appSettings != null) {
                val safeThemeMode = try {
                    ThemeMode.valueOf(appSettings.themeMode.trim())
                } catch (_: Exception) {
                    ThemeMode.FOLLOW_SYSTEM
                }
                val safeThemePreset = try {
                    when (ThemePreset.valueOf(appSettings.themePreset.trim())) {
                        ThemePreset.ROSE_QUARTZ, ThemePreset.OBSIDIAN_MINT -> ThemePreset.ROSE_QUARTZ
                        ThemePreset.LAVENDER_MIST, ThemePreset.MIDNIGHT_AZURE -> ThemePreset.LAVENDER_MIST
                        ThemePreset.PEACH_EMBER, ThemePreset.SUNSET_AMBER -> ThemePreset.PEACH_EMBER
                        ThemePreset.SAGE_SERENITY, ThemePreset.CYBER_VIOLET -> ThemePreset.SAGE_SERENITY
                    }
                } catch (_: Exception) {
                    ThemePreset.ROSE_QUARTZ
                }
                val safeFont = try {
                    FontOption.valueOf(appSettings.selectedFont.trim())
                } catch (_: Exception) {
                    FontOption.POPPINS
                }
                val safeProvider = LyricsProviderPreference.fromPersistedValue(appSettings.lyricsProvider)
                val safeAnimStyle = when (appSettings.animationStyle.trim()) {
                    "APPLE_MUSIC", "KARAOKE" -> OverlayAnimationStyle.KARAOKE.name
                    "SMOOTH_SLIDE_UP", "SOFT_FADE", "SCALE_POP", "SMOOTH_SLIDE" -> OverlayAnimationStyle.SMOOTH_SLIDE.name
                    "FADE", "FADE_IN_OUT" -> OverlayAnimationStyle.FADE.name
                    "BLUR_REVEAL", "BLUR" -> OverlayAnimationStyle.BLUR_REVEAL.name
                    "TYPEWRITER" -> OverlayAnimationStyle.TYPEWRITER.name
                    "MINIMAL_STATIC", "INSTANT" -> OverlayAnimationStyle.INSTANT.name
                    else -> OverlayAnimationStyle.KARAOKE.name
                }
                val safeKaraoke = appSettings.karaokeGlowSpeed.coerceIn(0.5f, 1.5f)

                prefs[PreferencesKeys.THEME_MODE] = safeThemeMode.name
                prefs[PreferencesKeys.THEME_PRESET] = safeThemePreset.name
                prefs[PreferencesKeys.USE_WALLPAPER_DYNAMIC_COLOR] = appSettings.useWallpaperDynamicColor
                prefs[PreferencesKeys.SELECTED_FONT] = safeFont.name
                val customFontId = appSettings.selectedCustomFontId?.trim()?.takeIf { it.isNotEmpty() }
                if (customFontId != null) {
                    prefs[PreferencesKeys.SELECTED_CUSTOM_FONT_ID] = customFontId
                } else {
                    prefs.remove(PreferencesKeys.SELECTED_CUSTOM_FONT_ID)
                }
                prefs[PreferencesKeys.LYRICS_PROVIDER] = safeProvider.name
                val incomingOrder = appSettings.lyricsProviderOrder
                if (incomingOrder != null) {
                    val normalizedOrder = LyricsProviderPreference.normalizeProviderOrder(incomingOrder)
                    prefs[PreferencesKeys.LYRICS_PROVIDER_ORDER] =
                        LyricsProviderPreference.serializeProviderOrder(normalizedOrder)
                } else {
                    prefs.remove(PreferencesKeys.LYRICS_PROVIDER_ORDER)
                }
                prefs[PreferencesKeys.AUTO_SHOW_ON_PLAYBACK] = appSettings.autoShowOnPlayback
                prefs[PreferencesKeys.AUTO_FETCH_LYRICS] = appSettings.autoFetchLyrics
                prefs[PreferencesKeys.AUTO_UPDATE_CHECKS_ENABLED] = appSettings.autoUpdateChecksEnabled
                prefs[PreferencesKeys.ANIMATION_STYLE] = safeAnimStyle
                prefs[PreferencesKeys.UPWARD_ANIMATION] = appSettings.upwardAnimationEnabled
                prefs[PreferencesKeys.APPLE_MUSIC_STYLE] = appSettings.appleMusicStyle
                prefs[PreferencesKeys.SINGLE_LINE_LYRICS] = appSettings.singleLineLyrics
                prefs[PreferencesKeys.ADAPTIVE_LYRIC_SCROLLING] = appSettings.adaptiveLyricScrolling
                prefs[PreferencesKeys.LYRIC_SCROLL_SPEED_MULTIPLIER] = appSettings.lyricScrollSpeedMultiplier.coerceIn(1.0f, 1.5f)
                prefs[PreferencesKeys.TYPEWRITER_SPEED] = appSettings.typewriterSpeed.coerceIn(0.3f, 3.0f)
                prefs[PreferencesKeys.SMOOTH_TRANSITION_SPEED] = appSettings.smoothTransitionSpeed.coerceIn(0.3f, 3.0f)
                prefs[PreferencesKeys.FADE_SPEED] = appSettings.fadeSpeed.coerceIn(0.3f, 3.0f)
                prefs[PreferencesKeys.BLUR_REVEAL_SPEED] = appSettings.blurRevealSpeed.coerceIn(0.3f, 3.0f)
                prefs[PreferencesKeys.KARAOKE_SPEED] = safeKaraoke
                prefs[PreferencesKeys.KARAOKE_GLOW_SPEED] = safeKaraoke
                prefs[PreferencesKeys.KARAOKE_ENHANCED_GLOW] = appSettings.karaokeEnhancedGlow
                prefs[PreferencesKeys.GLOBAL_OFFSET_MS] = appSettings.globalOffsetMs.coerceIn(-60_000L, 60_000L)

                // Restore per-track timing offsets safely (bounded and prefixed with "offset_")
                for ((rawKey, offsetMs) in appSettings.trackOffsets) {
                    if (rawKey.startsWith("offset_") && rawKey.length <= 128) {
                        prefs[longPreferencesKey(rawKey)] = offsetMs.coerceIn(-60_000L, 60_000L)
                    }
                }
            }

            if (hudConfig != null) {
                val safeAlign = when (hudConfig.overlayTextAlign.trim().uppercase(java.util.Locale.US)) {
                    "LEFT" -> "LEFT"
                    "RIGHT" -> "RIGHT"
                    else -> "CENTER"
                }
                val safeBackdropStyle = HudBackdropStyle.fromName(hudConfig.hudBackdropStyle)
                val safeActiveColor = HudColorSystem.sanitizeOpaqueColor(
                    hudConfig.hudActiveLyricColor,
                    HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR
                )
                val safeInactiveColor = HudColorSystem.sanitizeOpaqueColor(
                    hudConfig.hudInactiveLyricColor,
                    HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR
                )
                val safeCustomColors = hudConfig.hudCustomColors
                    .map { HudColorSystem.sanitizeOpaqueColor(it, HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR) }
                    .distinct()
                val safePortraitX = hudConfig.hudPortraitX.coerceIn(MIN_STORED_X_PX, MAX_STORED_POS_PX)
                val safePortraitY = hudConfig.hudPortraitY.coerceIn(0, MAX_STORED_POS_PX)
                val safePortraitW = hudConfig.hudPortraitWidth.coerceIn(MIN_STORED_WIDTH_PX, MAX_STORED_POS_PX)
                val safePortraitH = hudConfig.hudPortraitHeight.coerceIn(MIN_STORED_HEIGHT_PX, MAX_STORED_HEIGHT_PX)

                prefs[PreferencesKeys.OVERLAY_ENABLED] = hudConfig.overlayEnabled
                prefs[PreferencesKeys.OVERLAY_TEXT_SIZE] = hudConfig.overlayTextSizeSp.coerceIn(8f, 48f)
                prefs[PreferencesKeys.OVERLAY_TEXT_OPACITY] = hudConfig.overlayTextOpacity.coerceIn(0.15f, 1.0f)
                prefs[PreferencesKeys.HUD_BOLD_LYRICS] = hudConfig.hudBoldLyrics
                prefs[PreferencesKeys.OVERLAY_TEXT_SHADOW] = hudConfig.textShadowEnabled
                prefs[PreferencesKeys.OVERLAY_TEXT_ALIGN] = safeAlign
                prefs[PreferencesKeys.CLICK_THROUGH_OVERLAY] = hudConfig.clickThroughOverlay
                prefs[PreferencesKeys.HUD_BACKDROP_ENABLED] = hudConfig.hudBackdropEnabled
                prefs[PreferencesKeys.HUD_BACKDROP_STYLE] = safeBackdropStyle.name
                prefs[PreferencesKeys.HUD_BACKDROP_OPACITY] = hudConfig.hudBackdropOpacity.coerceIn(0.0f, 1.0f)
                prefs[PreferencesKeys.HUD_BACKDROP_CORNER_RADIUS_DP] = hudConfig.hudBackdropCornerRadiusDp.coerceIn(0, 32)
                prefs[PreferencesKeys.HUD_BACKDROP_HORIZONTAL_PADDING_DP] = hudConfig.hudBackdropHorizontalPaddingDp.coerceIn(0, 32)
                prefs[PreferencesKeys.HUD_BACKDROP_VERTICAL_PADDING_DP] = hudConfig.hudBackdropVerticalPaddingDp.coerceIn(0, 24)
                prefs[PreferencesKeys.HUD_DYNAMIC_ACCENT_ENABLED] = hudConfig.hudDynamicAccentEnabled
                prefs[PreferencesKeys.HUD_ACTIVE_LYRIC_COLOR] = safeActiveColor
                prefs[PreferencesKeys.HUD_INACTIVE_LYRIC_COLOR] = safeInactiveColor
                prefs[PreferencesKeys.HUD_CUSTOM_COLORS] = HudColorSystem.serializeCustomColors(safeCustomColors)

                // Portrait profile + legacy portrait mirror keys
                // MIN-05: Maintain dual-write to legacy OVERLAY_* keys explicitly for backwards compatibility
                // with older app components and widgets while preserving HUD_PORTRAIT_* as primary.
                prefs[PreferencesKeys.HUD_PORTRAIT_X] = safePortraitX
                prefs[PreferencesKeys.HUD_PORTRAIT_Y] = safePortraitY
                prefs[PreferencesKeys.HUD_PORTRAIT_WIDTH] = safePortraitW
                prefs[PreferencesKeys.HUD_PORTRAIT_HEIGHT] = safePortraitH
                prefs[PreferencesKeys.OVERLAY_X_POS] = safePortraitX
                prefs[PreferencesKeys.OVERLAY_Y_POS] = safePortraitY
                prefs[PreferencesKeys.OVERLAY_WIDTH] = safePortraitW
                prefs[PreferencesKeys.OVERLAY_HEIGHT] = safePortraitH

                // Landscape profile (only set when all four dimensions are present in backup)
                val lx = hudConfig.hudLandscapeX
                val ly = hudConfig.hudLandscapeY
                val lw = hudConfig.hudLandscapeWidth
                val lh = hudConfig.hudLandscapeHeight
                if (lx != null && ly != null && lw != null && lh != null) {
                    prefs[PreferencesKeys.HUD_LANDSCAPE_X] = lx.coerceIn(MIN_STORED_X_PX, MAX_STORED_POS_PX)
                    prefs[PreferencesKeys.HUD_LANDSCAPE_Y] = ly.coerceIn(0, MAX_STORED_POS_PX)
                    prefs[PreferencesKeys.HUD_LANDSCAPE_WIDTH] = lw.coerceIn(MIN_STORED_WIDTH_PX, MAX_STORED_POS_PX)
                    prefs[PreferencesKeys.HUD_LANDSCAPE_HEIGHT] = lh.coerceIn(MIN_STORED_HEIGHT_PX, MAX_STORED_HEIGHT_PX)
                } else {
                    prefs.remove(PreferencesKeys.HUD_LANDSCAPE_X)
                    prefs.remove(PreferencesKeys.HUD_LANDSCAPE_Y)
                    prefs.remove(PreferencesKeys.HUD_LANDSCAPE_WIDTH)
                    prefs.remove(PreferencesKeys.HUD_LANDSCAPE_HEIGHT)
                }
            }
        }
    }

    private suspend fun readPreferencesSnapshotOnce(): Preferences {
        var latest: Preferences = emptyPreferences()
        try {
            dataStore.data
                .catch { e ->
                    if (e is IOException) emit(emptyPreferences()) else throw e
                }
                .collect {
                    latest = it
                    throw StopAfterFirstException()
                }
        } catch (_: StopAfterFirstException) {
            // Expected single-emission short-circuit
        }
        return latest
    }

    private fun extractBackupAppSettings(raw: Preferences): com.example.data.backup.BackupAppSettingsData {
        val offsets = linkedMapOf<String, Long>()
        for ((key, value) in raw.asMap()) {
            if (key.name.startsWith("offset_") && value is Long && value != 0L) {
                offsets[key.name] = value
            }
        }
        val themeMode = try {
            ThemeMode.valueOf(raw[PreferencesKeys.THEME_MODE] ?: ThemeMode.FOLLOW_SYSTEM.name)
        } catch (_: Exception) {
            ThemeMode.FOLLOW_SYSTEM
        }
        val preset = try {
            when (ThemePreset.valueOf(raw[PreferencesKeys.THEME_PRESET] ?: ThemePreset.ROSE_QUARTZ.name)) {
                ThemePreset.ROSE_QUARTZ, ThemePreset.OBSIDIAN_MINT -> ThemePreset.ROSE_QUARTZ
                ThemePreset.LAVENDER_MIST, ThemePreset.MIDNIGHT_AZURE -> ThemePreset.LAVENDER_MIST
                ThemePreset.PEACH_EMBER, ThemePreset.SUNSET_AMBER -> ThemePreset.PEACH_EMBER
                ThemePreset.SAGE_SERENITY, ThemePreset.CYBER_VIOLET -> ThemePreset.SAGE_SERENITY
            }
        } catch (_: Exception) {
            ThemePreset.ROSE_QUARTZ
        }
        val font = try {
            FontOption.valueOf(raw[PreferencesKeys.SELECTED_FONT] ?: FontOption.POPPINS.name)
        } catch (_: Exception) {
            FontOption.POPPINS
        }
        val provider = LyricsProviderPreference.fromPersistedValue(raw[PreferencesKeys.LYRICS_PROVIDER])
        val rawStyle = raw[PreferencesKeys.ANIMATION_STYLE] ?: OverlayAnimationStyle.KARAOKE.name
        val animStyle = when (rawStyle) {
            "APPLE_MUSIC", "KARAOKE" -> OverlayAnimationStyle.KARAOKE.name
            "SMOOTH_SLIDE_UP", "SOFT_FADE", "SCALE_POP", "SMOOTH_SLIDE" -> OverlayAnimationStyle.SMOOTH_SLIDE.name
            "FADE", "FADE_IN_OUT" -> OverlayAnimationStyle.FADE.name
            "BLUR_REVEAL", "BLUR" -> OverlayAnimationStyle.BLUR_REVEAL.name
            "TYPEWRITER" -> OverlayAnimationStyle.TYPEWRITER.name
            "MINIMAL_STATIC", "INSTANT" -> OverlayAnimationStyle.INSTANT.name
            else -> OverlayAnimationStyle.KARAOKE.name
        }
        val karaoke = (raw[PreferencesKeys.KARAOKE_GLOW_SPEED] ?: raw[PreferencesKeys.KARAOKE_SPEED] ?: 1.0f).coerceIn(0.5f, 1.5f)
        return com.example.data.backup.BackupAppSettingsData(
            schemaVersion = 1,
            themeMode = themeMode.name,
            themePreset = preset.name,
            useWallpaperDynamicColor = raw[PreferencesKeys.USE_WALLPAPER_DYNAMIC_COLOR] ?: false,
            selectedFont = font.name,
            selectedCustomFontId = raw[PreferencesKeys.SELECTED_CUSTOM_FONT_ID]?.takeIf { it.isNotBlank() },
            lyricsProvider = provider.name,
            lyricsProviderOrder = LyricsProviderPreference.parseProviderOrder(
                raw[PreferencesKeys.LYRICS_PROVIDER_ORDER]
            ).map { it.name },
            autoShowOnPlayback = raw[PreferencesKeys.AUTO_SHOW_ON_PLAYBACK] ?: true,
            autoFetchLyrics = raw[PreferencesKeys.AUTO_FETCH_LYRICS] ?: true,
            autoUpdateChecksEnabled = raw[PreferencesKeys.AUTO_UPDATE_CHECKS_ENABLED] ?: true,
            animationStyle = animStyle,
            upwardAnimationEnabled = raw[PreferencesKeys.UPWARD_ANIMATION] ?: true,
            appleMusicStyle = raw[PreferencesKeys.APPLE_MUSIC_STYLE] ?: true,
            singleLineLyrics = raw[PreferencesKeys.SINGLE_LINE_LYRICS] ?: false,
            adaptiveLyricScrolling = raw[PreferencesKeys.ADAPTIVE_LYRIC_SCROLLING] ?: true,
            lyricScrollSpeedMultiplier = (raw[PreferencesKeys.LYRIC_SCROLL_SPEED_MULTIPLIER] ?: 1.0f).coerceIn(1.0f, 1.5f),
            typewriterSpeed = (raw[PreferencesKeys.TYPEWRITER_SPEED] ?: 1.0f).coerceIn(0.3f, 3.0f),
            smoothTransitionSpeed = (raw[PreferencesKeys.SMOOTH_TRANSITION_SPEED] ?: 1.0f).coerceIn(0.3f, 3.0f),
            fadeSpeed = (raw[PreferencesKeys.FADE_SPEED] ?: 1.0f).coerceIn(0.3f, 3.0f),
            blurRevealSpeed = (raw[PreferencesKeys.BLUR_REVEAL_SPEED] ?: 1.0f).coerceIn(0.3f, 3.0f),
            karaokeSpeed = karaoke,
            karaokeGlowSpeed = karaoke,
            karaokeEnhancedGlow = raw[PreferencesKeys.KARAOKE_ENHANCED_GLOW] ?: false,
            globalOffsetMs = raw[PreferencesKeys.GLOBAL_OFFSET_MS] ?: 0L,
            trackOffsets = offsets
        )
    }

    private fun extractBackupHudConfig(raw: Preferences): com.example.data.backup.BackupHudConfigData {
        val portraitY = (raw[PreferencesKeys.HUD_PORTRAIT_Y]
            ?: raw[PreferencesKeys.OVERLAY_Y_POS]
            ?: HudPositioningEngine.DEFAULT_PORTRAIT_Y).coerceIn(0, MAX_STORED_POS_PX)
        val portraitX = (raw[PreferencesKeys.HUD_PORTRAIT_X]
            ?: raw[PreferencesKeys.OVERLAY_X_POS]
            ?: HudPositioningEngine.DEFAULT_PORTRAIT_X).coerceIn(MIN_STORED_X_PX, MAX_STORED_POS_PX)
        val portraitWidth = (raw[PreferencesKeys.HUD_PORTRAIT_WIDTH]
            ?: raw[PreferencesKeys.OVERLAY_WIDTH]
            ?: HudPositioningEngine.DEFAULT_PORTRAIT_WIDTH_PX).coerceIn(MIN_STORED_WIDTH_PX, MAX_STORED_POS_PX)
        val portraitHeight = (raw[PreferencesKeys.HUD_PORTRAIT_HEIGHT]
            ?: raw[PreferencesKeys.OVERLAY_HEIGHT]
            ?: HudPositioningEngine.DEFAULT_PORTRAIT_HEIGHT_PX).coerceIn(MIN_STORED_HEIGHT_PX, MAX_STORED_HEIGHT_PX)

        val lx = raw[PreferencesKeys.HUD_LANDSCAPE_X]?.coerceIn(MIN_STORED_X_PX, MAX_STORED_POS_PX)
        val ly = raw[PreferencesKeys.HUD_LANDSCAPE_Y]?.coerceIn(0, MAX_STORED_POS_PX)
        val lw = raw[PreferencesKeys.HUD_LANDSCAPE_WIDTH]?.coerceIn(MIN_STORED_WIDTH_PX, MAX_STORED_POS_PX)
        val lh = raw[PreferencesKeys.HUD_LANDSCAPE_HEIGHT]?.coerceIn(MIN_STORED_HEIGHT_PX, MAX_STORED_HEIGHT_PX)
        val hasLandscape = lx != null && ly != null && lw != null && lh != null

        return com.example.data.backup.BackupHudConfigData(
            schemaVersion = 1,
            overlayEnabled = raw[PreferencesKeys.OVERLAY_ENABLED] ?: true,
            overlayTextSizeSp = raw[PreferencesKeys.OVERLAY_TEXT_SIZE] ?: 14f,
            overlayTextOpacity = (raw[PreferencesKeys.OVERLAY_TEXT_OPACITY] ?: 1.0f).coerceIn(0.15f, 1.0f),
            hudBoldLyrics = raw[PreferencesKeys.HUD_BOLD_LYRICS] ?: false,
            textShadowEnabled = raw[PreferencesKeys.OVERLAY_TEXT_SHADOW] ?: true,
            overlayTextAlign = raw[PreferencesKeys.OVERLAY_TEXT_ALIGN] ?: "CENTER",
            clickThroughOverlay = raw[PreferencesKeys.CLICK_THROUGH_OVERLAY] ?: false,
            hudBackdropEnabled = raw[PreferencesKeys.HUD_BACKDROP_ENABLED] ?: false,
            hudBackdropStyle = HudBackdropStyle.fromName(raw[PreferencesKeys.HUD_BACKDROP_STYLE]).name,
            hudBackdropOpacity = (raw[PreferencesKeys.HUD_BACKDROP_OPACITY] ?: 0.65f).coerceIn(0.0f, 1.0f),
            hudBackdropCornerRadiusDp = (raw[PreferencesKeys.HUD_BACKDROP_CORNER_RADIUS_DP] ?: 18).coerceIn(0, 32),
            hudBackdropHorizontalPaddingDp = (raw[PreferencesKeys.HUD_BACKDROP_HORIZONTAL_PADDING_DP] ?: 16).coerceIn(0, 32),
            hudBackdropVerticalPaddingDp = (raw[PreferencesKeys.HUD_BACKDROP_VERTICAL_PADDING_DP] ?: 10).coerceIn(0, 24),
            hudDynamicAccentEnabled = raw[PreferencesKeys.HUD_DYNAMIC_ACCENT_ENABLED] ?: false,
            hudActiveLyricColor = HudColorSystem.sanitizeOpaqueColor(
                raw[PreferencesKeys.HUD_ACTIVE_LYRIC_COLOR],
                HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR
            ),
            hudInactiveLyricColor = HudColorSystem.sanitizeOpaqueColor(
                raw[PreferencesKeys.HUD_INACTIVE_LYRIC_COLOR],
                HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR
            ),
            hudCustomColors = HudColorSystem.deserializeCustomColors(
                raw[PreferencesKeys.HUD_CUSTOM_COLORS]
            ),
            hudPortraitX = portraitX,
            hudPortraitY = portraitY,
            hudPortraitWidth = portraitWidth,
            hudPortraitHeight = portraitHeight,
            hudLandscapeX = if (hasLandscape) lx else null,
            hudLandscapeY = if (hasLandscape) ly else null,
            hudLandscapeWidth = if (hasLandscape) lw else null,
            hudLandscapeHeight = if (hasLandscape) lh else null
        )
    }

    private class StopAfterFirstException : Exception()
}
