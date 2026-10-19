package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "auralis_prefs")

enum class ThemePreset(val title: String) {
    OBSIDIAN_MINT("Obsidian Mint"),
    MIDNIGHT_AZURE("Midnight Azure"),
    SUNSET_AMBER("Sunset Amber"),
    CYBER_VIOLET("Cyber Violet")
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
    TYPEWRITER("Typewriter", "Reveals text progressively from left to right"),
    INSTANT("Instant", "Replaces the current line immediately with no animation")
}

data class UserPreferences(
    val overlayEnabled: Boolean = true,
    val overlayTextSizeSp: Float = 14f,
    val overlayTextOpacity: Float = 1.0f, // 0.2f (20%) to 1.0f (100%)
    val typewriterSpeed: Float = 1.0f, // 0.4x (Slow) to 2.0x (Fast)
    val textShadowEnabled: Boolean = true,
    val overlayYPosition: Int = 180, // Full range
    val overlayXPosition: Int = 0, // Full range
    val overlayWidthPx: Int = 850, // Resizable overlay width
    val overlayHeightPx: Int = 130, // Resizable overlay height
    val overlayTextAlign: String = "CENTER", // "LEFT", "CENTER", "RIGHT"
    val upwardAnimationEnabled: Boolean = true,
    val animationStyle: String = OverlayAnimationStyle.KARAOKE.name,
    val isDragRepositionMode: Boolean = false,
    val autoShowOnPlayback: Boolean = true,
    val autoFetchLyrics: Boolean = true,
    val useWallpaperDynamicColor: Boolean = true,
    val themePreset: ThemePreset = ThemePreset.OBSIDIAN_MINT,
    val selectedFont: FontOption = FontOption.POPPINS,
    val appleMusicStyle: Boolean = true,
    val globalOffsetMs: Long = 0L
)

class UserPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val OVERLAY_TEXT_SIZE = floatPreferencesKey("overlay_text_size")
        val OVERLAY_TEXT_OPACITY = floatPreferencesKey("overlay_text_opacity")
        val TYPEWRITER_SPEED = floatPreferencesKey("typewriter_speed")
        val OVERLAY_TEXT_SHADOW = booleanPreferencesKey("overlay_text_shadow")
        val OVERLAY_Y_POS = intPreferencesKey("overlay_y_position")
        val OVERLAY_X_POS = intPreferencesKey("overlay_x_position")
        val OVERLAY_WIDTH = intPreferencesKey("overlay_width")
        val OVERLAY_HEIGHT = intPreferencesKey("overlay_height")
        val OVERLAY_TEXT_ALIGN = stringPreferencesKey("overlay_text_align")
        val UPWARD_ANIMATION = booleanPreferencesKey("upward_animation_enabled")
        val ANIMATION_STYLE = stringPreferencesKey("animation_style")
        val APPLE_MUSIC_MESH_BLUR = booleanPreferencesKey("apple_music_mesh_blur")
        val IS_DRAG_MODE = booleanPreferencesKey("is_drag_mode")
        val AUTO_SHOW_ON_PLAYBACK = booleanPreferencesKey("auto_show_on_playback")
        val AUTO_FETCH_LYRICS = booleanPreferencesKey("auto_fetch_lyrics")
        val USE_WALLPAPER_DYNAMIC_COLOR = booleanPreferencesKey("use_wallpaper_dynamic_color")
        val THEME_PRESET = stringPreferencesKey("theme_preset")
        val SELECTED_FONT = stringPreferencesKey("selected_font")
        val APPLE_MUSIC_STYLE = booleanPreferencesKey("apple_music_style")
        val GLOBAL_OFFSET_MS = longPreferencesKey("global_offset_ms")
    }

    val userPreferencesFlow: Flow<UserPreferences> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val presetName = preferences[PreferencesKeys.THEME_PRESET] ?: ThemePreset.OBSIDIAN_MINT.name
            val preset = try {
                ThemePreset.valueOf(presetName)
            } catch (_: Exception) {
                ThemePreset.OBSIDIAN_MINT
            }

            val fontName = preferences[PreferencesKeys.SELECTED_FONT] ?: FontOption.POPPINS.name
            val font = try {
                FontOption.valueOf(fontName)
            } catch (_: Exception) {
                FontOption.POPPINS
            }

            val rawY = preferences[PreferencesKeys.OVERLAY_Y_POS] ?: 180
            val clampedY = rawY.coerceIn(0, 3000)

            val rawX = preferences[PreferencesKeys.OVERLAY_X_POS] ?: 0
            val clampedX = rawX.coerceIn(-2000, 2000)

            val width = preferences[PreferencesKeys.OVERLAY_WIDTH] ?: 850
            val height = preferences[PreferencesKeys.OVERLAY_HEIGHT] ?: 130

            val rawStyle = preferences[PreferencesKeys.ANIMATION_STYLE] ?: OverlayAnimationStyle.KARAOKE.name
            val animStyle = when (rawStyle) {
                "APPLE_MUSIC", "KARAOKE" -> OverlayAnimationStyle.KARAOKE.name
                "SMOOTH_SLIDE_UP", "SOFT_FADE", "SCALE_POP", "SMOOTH_SLIDE" -> OverlayAnimationStyle.SMOOTH_SLIDE.name
                "TYPEWRITER" -> OverlayAnimationStyle.TYPEWRITER.name
                "MINIMAL_STATIC", "INSTANT" -> OverlayAnimationStyle.INSTANT.name
                else -> OverlayAnimationStyle.KARAOKE.name
            }

            UserPreferences(
                overlayEnabled = preferences[PreferencesKeys.OVERLAY_ENABLED] ?: true,
                overlayTextSizeSp = preferences[PreferencesKeys.OVERLAY_TEXT_SIZE] ?: 14f,
                overlayTextOpacity = (preferences[PreferencesKeys.OVERLAY_TEXT_OPACITY] ?: 1.0f).coerceIn(0.15f, 1.0f),
                typewriterSpeed = (preferences[PreferencesKeys.TYPEWRITER_SPEED] ?: 1.0f).coerceIn(0.3f, 3.0f),
                textShadowEnabled = preferences[PreferencesKeys.OVERLAY_TEXT_SHADOW] ?: true,
                overlayYPosition = clampedY,
                overlayXPosition = clampedX,
                overlayWidthPx = width.coerceIn(280, 2000),
                overlayHeightPx = height.coerceIn(60, 800),
                overlayTextAlign = preferences[PreferencesKeys.OVERLAY_TEXT_ALIGN] ?: "CENTER",
                upwardAnimationEnabled = preferences[PreferencesKeys.UPWARD_ANIMATION] ?: true,
                animationStyle = animStyle,
                isDragRepositionMode = preferences[PreferencesKeys.IS_DRAG_MODE] ?: false,
                autoShowOnPlayback = preferences[PreferencesKeys.AUTO_SHOW_ON_PLAYBACK] ?: true,
                autoFetchLyrics = preferences[PreferencesKeys.AUTO_FETCH_LYRICS] ?: true,
                useWallpaperDynamicColor = preferences[PreferencesKeys.USE_WALLPAPER_DYNAMIC_COLOR] ?: true,
                themePreset = preset,
                selectedFont = font,
                appleMusicStyle = preferences[PreferencesKeys.APPLE_MUSIC_STYLE] ?: true,
                globalOffsetMs = preferences[PreferencesKeys.GLOBAL_OFFSET_MS] ?: 0L
            )
        }

    suspend fun setAnimationStyle(style: String) {
        context.dataStore.edit { it[PreferencesKeys.ANIMATION_STYLE] = style }
    }

    suspend fun setOverlayTextOpacity(opacity: Float) {
        val bounded = opacity.coerceIn(0.15f, 1.0f)
        context.dataStore.edit { it[PreferencesKeys.OVERLAY_TEXT_OPACITY] = bounded }
    }

    suspend fun setTypewriterSpeed(speed: Float) {
        val bounded = speed.coerceIn(0.3f, 3.0f)
        context.dataStore.edit { it[PreferencesKeys.TYPEWRITER_SPEED] = bounded }
    }

    suspend fun setAppleMusicStyle(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.APPLE_MUSIC_STYLE] = enabled }
    }

    suspend fun setOverlayEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.OVERLAY_ENABLED] = enabled }
    }

    suspend fun setOverlayTextSize(sizeSp: Float) {
        context.dataStore.edit { it[PreferencesKeys.OVERLAY_TEXT_SIZE] = sizeSp }
    }

    suspend fun setTextShadowEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.OVERLAY_TEXT_SHADOW] = enabled }
    }

    suspend fun setOverlayPosition(x: Int, y: Int) {
        val boundedX = x.coerceIn(-2000, 2000)
        val boundedY = y.coerceIn(0, 3000)
        context.dataStore.edit {
            it[PreferencesKeys.OVERLAY_X_POS] = boundedX
            it[PreferencesKeys.OVERLAY_Y_POS] = boundedY
        }
    }

    suspend fun setOverlayBounds(x: Int, y: Int, widthPx: Int, heightPx: Int) {
        val boundedX = x.coerceIn(-2000, 2000)
        val boundedY = y.coerceIn(0, 3000)
        val boundedW = widthPx.coerceIn(280, 2000)
        val boundedH = heightPx.coerceIn(60, 800)
        context.dataStore.edit {
            it[PreferencesKeys.OVERLAY_X_POS] = boundedX
            it[PreferencesKeys.OVERLAY_Y_POS] = boundedY
            it[PreferencesKeys.OVERLAY_WIDTH] = boundedW
            it[PreferencesKeys.OVERLAY_HEIGHT] = boundedH
        }
    }

    suspend fun setOverlayDimensions(widthPx: Int, heightPx: Int) {
        val boundedW = widthPx.coerceIn(280, 2000)
        val boundedH = heightPx.coerceIn(60, 800)
        context.dataStore.edit {
            it[PreferencesKeys.OVERLAY_WIDTH] = boundedW
            it[PreferencesKeys.OVERLAY_HEIGHT] = boundedH
        }
    }

    suspend fun nudgeOverlayPosition(deltaX: Int, deltaY: Int) {
        context.dataStore.edit {
            val currentX = (it[PreferencesKeys.OVERLAY_X_POS] ?: 0) + deltaX
            val currentY = (it[PreferencesKeys.OVERLAY_Y_POS] ?: 180) + deltaY
            it[PreferencesKeys.OVERLAY_X_POS] = currentX.coerceIn(-2000, 2000)
            it[PreferencesKeys.OVERLAY_Y_POS] = currentY.coerceIn(0, 3000)
        }
    }

    suspend fun setOverlayTextAlign(align: String) {
        val normalized = when (align.trim().uppercase()) {
            "LEFT" -> "LEFT"
            "RIGHT" -> "RIGHT"
            else -> "CENTER"
        }
        context.dataStore.edit { it[PreferencesKeys.OVERLAY_TEXT_ALIGN] = normalized }
    }

    suspend fun setUpwardAnimationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.UPWARD_ANIMATION] = enabled }
    }

    suspend fun setDragRepositionMode(dragMode: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.IS_DRAG_MODE] = dragMode }
    }

    suspend fun setAutoShowOnPlayback(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.AUTO_SHOW_ON_PLAYBACK] = enabled }
    }

    suspend fun setAutoFetchLyrics(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.AUTO_FETCH_LYRICS] = enabled }
    }

    suspend fun setUseWallpaperDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[PreferencesKeys.USE_WALLPAPER_DYNAMIC_COLOR] = enabled }
    }

    suspend fun setThemePreset(preset: ThemePreset) {
        context.dataStore.edit { it[PreferencesKeys.THEME_PRESET] = preset.name }
    }

    suspend fun setSelectedFont(font: FontOption) {
        context.dataStore.edit { it[PreferencesKeys.SELECTED_FONT] = font.name }
    }

    suspend fun setTrackOffset(trackKey: String, offsetMs: Long) {
        val key = longPreferencesKey("offset_${trackKey.hashCode()}")
        context.dataStore.edit { it[key] = offsetMs }
    }

    fun getTrackOffsetFlow(trackKey: String): Flow<Long> {
        val key = longPreferencesKey("offset_${trackKey.hashCode()}")
        return context.dataStore.data.map { it[key] ?: 0L }
    }
}
