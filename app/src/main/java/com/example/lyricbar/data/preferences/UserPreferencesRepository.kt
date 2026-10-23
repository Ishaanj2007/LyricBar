package com.example.lyricbar.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.lyricbar.data.model.AnimationType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

data class UserPreferences(
    val fontSize: Float = 18f,
    val opacity: Float = 0.85f,
    val fontIndex: Int = 0,
    val animationType: AnimationType = AnimationType.KARAOKE,
    val singleLineMode: Boolean = false,
    val overlayX: Int = 0,
    val overlayY: Int = 100,
    val timeOffsetMs: Long = 0L,
    val isOverlayEnabled: Boolean = false
)

class UserPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val FONT_SIZE = floatPreferencesKey("font_size")
        val OPACITY = floatPreferencesKey("opacity")
        val FONT_INDEX = intPreferencesKey("font_index")
        val ANIMATION_TYPE = stringPreferencesKey("animation_type")
        val SINGLE_LINE_MODE = booleanPreferencesKey("single_line_mode")
        val OVERLAY_X = intPreferencesKey("overlay_x")
        val OVERLAY_Y = intPreferencesKey("overlay_y")
        val TIME_OFFSET_MS = longPreferencesKey("time_offset_ms")
        val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
    }

    val userPreferencesFlow: Flow<UserPreferences> = context.dataStore.data
        .map { preferences ->
            UserPreferences(
                fontSize = preferences[PreferencesKeys.FONT_SIZE] ?: 18f,
                opacity = preferences[PreferencesKeys.OPACITY] ?: 0.85f,
                fontIndex = preferences[PreferencesKeys.FONT_INDEX] ?: 0,
                animationType = try {
                    AnimationType.valueOf(preferences[PreferencesKeys.ANIMATION_TYPE] ?: AnimationType.KARAOKE.name)
                } catch (e: Exception) {
                    AnimationType.KARAOKE
                },
                singleLineMode = preferences[PreferencesKeys.SINGLE_LINE_MODE] ?: false,
                overlayX = preferences[PreferencesKeys.OVERLAY_X] ?: 0,
                overlayY = preferences[PreferencesKeys.OVERLAY_Y] ?: 100,
                timeOffsetMs = preferences[PreferencesKeys.TIME_OFFSET_MS] ?: 0L,
                isOverlayEnabled = preferences[PreferencesKeys.OVERLAY_ENABLED] ?: false
            )
        }

    suspend fun updateFontSize(size: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FONT_SIZE] = size
        }
    }

    suspend fun updateOpacity(opacity: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.OPACITY] = opacity
        }
    }

    suspend fun updateFontIndex(index: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.FONT_INDEX] = index
        }
    }

    suspend fun updateAnimationType(type: AnimationType) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.ANIMATION_TYPE] = type.name
        }
    }

    suspend fun updateSingleLineMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SINGLE_LINE_MODE] = enabled
        }
    }

    suspend fun updateOverlayPosition(x: Int, y: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.OVERLAY_X] = x
            preferences[PreferencesKeys.OVERLAY_Y] = y
        }
    }

    suspend fun updateTimeOffset(offsetMs: Long) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TIME_OFFSET_MS] = offsetMs
        }
    }

    suspend fun updateOverlayEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.OVERLAY_ENABLED] = enabled
        }
    }
}
