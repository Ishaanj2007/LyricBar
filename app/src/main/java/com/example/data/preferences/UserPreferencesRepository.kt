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
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lyricbar_prefs")

enum class LyricsProviderPreference(
    val displayName: String,
    val description: String,
    val providerName: String?
) {
    AUTO(
        displayName = "Automatic",
        description = "Tries LRCLIB → lrc.red → KuGou → LyricsPlus in order",
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
    KUGOU(
        displayName = "KuGou",
        description = "Title and artist matched synchronized lyrics catalog",
        providerName = "KuGou"
    ),
    LYRICS_PLUS(
        displayName = "LyricsPlus",
        description = "Multi-mirror synchronized lyrics aggregator",
        providerName = "LyricsPlus"
    );

    companion object {
        fun fromPersistedValue(value: String?): LyricsProviderPreference {
            if (value.isNullOrBlank()) return AUTO
            val trimmed = value.trim()
            return entries.firstOrNull { option ->
                option.name.equals(trimmed, ignoreCase = true) ||
                    option.displayName.equals(trimmed, ignoreCase = true) ||
                    (option.providerName != null && option.providerName.equals(trimmed, ignoreCase = true))
            } ?: AUTO
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
    val textShadowEnabled: Boolean = true,
    val hudBackdropEnabled: Boolean = false,
    val hudBackdropStyle: HudBackdropStyle = HudBackdropStyle.BLACK,
    val hudBackdropOpacity: Float = 0.65f, // 0.0f to 1.0f (0% to 100%, default 65%)
    val hudBackdropCornerRadiusDp: Int = 18, // 0 to 32 dp (default 18dp)
    val hudBackdropHorizontalPaddingDp: Int = 16, // 0 to 32 dp (default 16dp)
    val hudBackdropVerticalPaddingDp: Int = 10, // 0 to 24 dp (default 10dp)
    val hudActiveLyricColor: Int = HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR,
    val hudInactiveLyricColor: Int = HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR,
    val hudAccentColor: Int = HudColorSystem.DEFAULT_ACCENT_COLOR,
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
    val overlayTextAlign: String = "CENTER", // "LEFT", "CENTER", "RIGHT"
    val upwardAnimationEnabled: Boolean = true,
    val animationStyle: String = OverlayAnimationStyle.KARAOKE.name,
    val isDragRepositionMode: Boolean = false,
    val autoShowOnPlayback: Boolean = true,
    val autoFetchLyrics: Boolean = true,
    val lyricsProvider: LyricsProviderPreference = LyricsProviderPreference.AUTO,
    val useWallpaperDynamicColor: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.FOLLOW_SYSTEM,
    val themePreset: ThemePreset = ThemePreset.ROSE_QUARTZ,
    val selectedFont: FontOption = FontOption.POPPINS,
    val appleMusicStyle: Boolean = true,
    val singleLineLyrics: Boolean = false,
    val adaptiveLyricScrolling: Boolean = true,
    val lyricScrollSpeedMultiplier: Float = 1.0f, // 1.0x to 1.5x
    val globalOffsetMs: Long = 0L
) {
    val hasLandscapeProfile: Boolean
        get() = hudLandscapeX != null &&
            hudLandscapeY != null &&
            hudLandscapeWidth != null &&
            hudLandscapeHeight != null

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
        val OVERLAY_TEXT_SHADOW = booleanPreferencesKey("overlay_text_shadow")
        val HUD_BACKDROP_ENABLED = booleanPreferencesKey("hud_backdrop_enabled")
        val HUD_BACKDROP_STYLE = stringPreferencesKey("hud_backdrop_style")
        val HUD_BACKDROP_OPACITY = floatPreferencesKey("hud_backdrop_opacity")
        val HUD_BACKDROP_CORNER_RADIUS_DP = intPreferencesKey("hud_backdrop_corner_radius_dp")
        val HUD_BACKDROP_HORIZONTAL_PADDING_DP = intPreferencesKey("hud_backdrop_horizontal_padding_dp")
        val HUD_BACKDROP_VERTICAL_PADDING_DP = intPreferencesKey("hud_backdrop_vertical_padding_dp")
        val HUD_ACTIVE_LYRIC_COLOR = intPreferencesKey("hud_active_lyric_color")
        val HUD_INACTIVE_LYRIC_COLOR = intPreferencesKey("hud_inactive_lyric_color")
        val HUD_ACCENT_COLOR = intPreferencesKey("hud_accent_color")
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
        val UPWARD_ANIMATION = booleanPreferencesKey("upward_animation_enabled")
        val ANIMATION_STYLE = stringPreferencesKey("animation_style")
        val APPLE_MUSIC_MESH_BLUR = booleanPreferencesKey("apple_music_mesh_blur")
        val IS_DRAG_MODE = booleanPreferencesKey("is_drag_mode")
        val AUTO_SHOW_ON_PLAYBACK = booleanPreferencesKey("auto_show_on_playback")
        val AUTO_FETCH_LYRICS = booleanPreferencesKey("auto_fetch_lyrics")
        val LYRICS_PROVIDER = stringPreferencesKey("lyrics_provider")
        val USE_WALLPAPER_DYNAMIC_COLOR = booleanPreferencesKey("use_wallpaper_dynamic_color")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val THEME_PRESET = stringPreferencesKey("theme_preset")
        val SELECTED_FONT = stringPreferencesKey("selected_font")
        val APPLE_MUSIC_STYLE = booleanPreferencesKey("apple_music_style")
        val SINGLE_LINE_LYRICS = booleanPreferencesKey("single_line_lyrics")
        val ADAPTIVE_LYRIC_SCROLLING = booleanPreferencesKey("adaptive_lyric_scrolling")
        val LYRIC_SCROLL_SPEED_MULTIPLIER = floatPreferencesKey("lyric_scroll_speed_multiplier")
        val GLOBAL_OFFSET_MS = longPreferencesKey("global_offset_ms")
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
                textShadowEnabled = preferences[PreferencesKeys.OVERLAY_TEXT_SHADOW] ?: true,
                hudBackdropEnabled = preferences[PreferencesKeys.HUD_BACKDROP_ENABLED] ?: false,
                hudBackdropStyle = HudBackdropStyle.fromName(preferences[PreferencesKeys.HUD_BACKDROP_STYLE]),
                hudBackdropOpacity = (preferences[PreferencesKeys.HUD_BACKDROP_OPACITY] ?: 0.65f).coerceIn(0.0f, 1.0f),
                hudBackdropCornerRadiusDp = (preferences[PreferencesKeys.HUD_BACKDROP_CORNER_RADIUS_DP] ?: 18).coerceIn(0, 32),
                hudBackdropHorizontalPaddingDp = (preferences[PreferencesKeys.HUD_BACKDROP_HORIZONTAL_PADDING_DP] ?: 16).coerceIn(0, 32),
                hudBackdropVerticalPaddingDp = (preferences[PreferencesKeys.HUD_BACKDROP_VERTICAL_PADDING_DP] ?: 10).coerceIn(0, 24),
                hudActiveLyricColor = HudColorSystem.sanitizeOpaqueColor(
                    preferences[PreferencesKeys.HUD_ACTIVE_LYRIC_COLOR],
                    HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR
                ),
                hudInactiveLyricColor = HudColorSystem.sanitizeOpaqueColor(
                    preferences[PreferencesKeys.HUD_INACTIVE_LYRIC_COLOR],
                    HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR
                ),
                hudAccentColor = HudColorSystem.sanitizeOpaqueColor(
                    preferences[PreferencesKeys.HUD_ACCENT_COLOR],
                    HudColorSystem.DEFAULT_ACCENT_COLOR
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
                upwardAnimationEnabled = preferences[PreferencesKeys.UPWARD_ANIMATION] ?: true,
                animationStyle = animStyle,
                isDragRepositionMode = preferences[PreferencesKeys.IS_DRAG_MODE] ?: false,
                autoShowOnPlayback = preferences[PreferencesKeys.AUTO_SHOW_ON_PLAYBACK] ?: true,
                autoFetchLyrics = preferences[PreferencesKeys.AUTO_FETCH_LYRICS] ?: true,
                lyricsProvider = lyricsProvider,
                useWallpaperDynamicColor = preferences[PreferencesKeys.USE_WALLPAPER_DYNAMIC_COLOR] ?: false,
                themeMode = themeMode,
                themePreset = preset,
                selectedFont = font,
                appleMusicStyle = preferences[PreferencesKeys.APPLE_MUSIC_STYLE] ?: true,
                singleLineLyrics = preferences[PreferencesKeys.SINGLE_LINE_LYRICS] ?: false,
                adaptiveLyricScrolling = preferences[PreferencesKeys.ADAPTIVE_LYRIC_SCROLLING] ?: true,
                lyricScrollSpeedMultiplier = (preferences[PreferencesKeys.LYRIC_SCROLL_SPEED_MULTIPLIER] ?: 1.0f).coerceIn(1.0f, 1.5f),
                globalOffsetMs = preferences[PreferencesKeys.GLOBAL_OFFSET_MS] ?: 0L
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

    suspend fun setHudActiveLyricColor(colorArgb: Int) {
        val safe = HudColorSystem.sanitizeOpaqueColor(colorArgb, HudColorSystem.DEFAULT_ACTIVE_LYRIC_COLOR)
        dataStore.edit { it[PreferencesKeys.HUD_ACTIVE_LYRIC_COLOR] = safe }
    }

    suspend fun setHudInactiveLyricColor(colorArgb: Int) {
        val safe = HudColorSystem.sanitizeOpaqueColor(colorArgb, HudColorSystem.DEFAULT_INACTIVE_LYRIC_COLOR)
        dataStore.edit { it[PreferencesKeys.HUD_INACTIVE_LYRIC_COLOR] = safe }
    }

    suspend fun setHudAccentColor(colorArgb: Int) {
        val safe = HudColorSystem.sanitizeOpaqueColor(colorArgb, HudColorSystem.DEFAULT_ACCENT_COLOR)
        dataStore.edit { it[PreferencesKeys.HUD_ACCENT_COLOR] = safe }
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
        private const val CURRENT_HUD_STYLE_MIGRATION_VERSION = 1
        private const val MIN_STORED_X_PX = -4000
        private const val MAX_STORED_POS_PX = 8000
        private const val MIN_STORED_WIDTH_PX = 120
        private const val MIN_STORED_HEIGHT_PX = 40
        private const val MAX_STORED_HEIGHT_PX = 4000
    }

    suspend fun setOverlayTextAlign(align: String) {
        val normalized = when (align.trim().uppercase()) {
            "LEFT" -> "LEFT"
            "RIGHT" -> "RIGHT"
            else -> "CENTER"
        }
        dataStore.edit { it[PreferencesKeys.OVERLAY_TEXT_ALIGN] = normalized }
    }

    suspend fun setUpwardAnimationEnabled(enabled: Boolean) {
        dataStore.edit { it[PreferencesKeys.UPWARD_ANIMATION] = enabled }
    }

    suspend fun setDragRepositionMode(dragMode: Boolean) {
        dataStore.edit { it[PreferencesKeys.IS_DRAG_MODE] = dragMode }
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
        dataStore.edit { it[PreferencesKeys.SELECTED_FONT] = font.name }
    }

    suspend fun setTrackOffset(trackKey: String, offsetMs: Long) {
        val key = longPreferencesKey("offset_${trackKey.hashCode()}")
        dataStore.edit { it[key] = offsetMs }
    }

    fun getTrackOffsetFlow(trackKey: String, fallbackKey: String? = null): Flow<Long> {
        val primaryKey = longPreferencesKey("offset_${trackKey.hashCode()}")
        val altKey = fallbackKey?.takeIf { it.isNotBlank() && it != trackKey }?.let { longPreferencesKey("offset_${it.hashCode()}") }
        return dataStore.data.map { preferences ->
            preferences[primaryKey] ?: (if (altKey != null) preferences[altKey] else null) ?: 0L
        }
    }
}
