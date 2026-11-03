package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.data.preferences.FontOption
import com.example.data.preferences.ThemeMode
import com.example.data.preferences.ThemePreset

// =========================================================================
// PALETTE 1: ROSE QUARTZ (Signature Theme)
// =========================================================================
private val RoseQuartzDarkScheme = darkColorScheme(
    primary = RoseQuartzPrimary,
    onPrimary = RoseQuartzOnPrimary,
    primaryContainer = RoseQuartzPrimaryContainer,
    onPrimaryContainer = RoseQuartzOnPrimaryContainer,
    secondary = RoseQuartzSecondary,
    onSecondary = RoseQuartzOnSecondary,
    secondaryContainer = RoseQuartzSecondaryContainer,
    onSecondaryContainer = RoseQuartzOnSecondaryContainer,
    tertiary = RoseQuartzTertiary,
    onTertiary = RoseQuartzOnTertiary,
    tertiaryContainer = RoseQuartzTertiaryContainer,
    onTertiaryContainer = RoseQuartzOnTertiaryContainer,
    background = RoseQuartzDarkBackground,
    onBackground = TextPrimary,
    surface = RoseQuartzDarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = RoseQuartzDarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = RoseQuartzDarkSurfaceContainer,
    outline = RoseQuartzDarkOutline,
    outlineVariant = RoseQuartzDarkOutlineVariant,
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
)

private val RoseQuartzLightScheme = lightColorScheme(
    primary = RoseQuartzLightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E4),
    onPrimaryContainer = Color(0xFF3B0720),
    secondary = RoseQuartzLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFCD8E6),
    onSecondaryContainer = Color(0xFF33091B),
    background = RoseQuartzLightBackground,
    onBackground = LightTextPrimary,
    surface = RoseQuartzLightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = RoseQuartzLightSurfaceContainer,
    onSurfaceVariant = LightTextSecondary,
    surfaceContainer = RoseQuartzLightSurfaceElevated,
    outline = Color(0xFF86727B),
    outlineVariant = Color(0xFFD7C2CC),
    error = Color(0xFFDC2626),
    onError = Color.White
)

// =========================================================================
// PALETTE 2: LAVENDER MIST (Dreamy, Elegant)
// =========================================================================
private val LavenderMistDarkScheme = darkColorScheme(
    primary = LavenderMistPrimary,
    onPrimary = LavenderMistOnPrimary,
    primaryContainer = LavenderMistPrimaryContainer,
    onPrimaryContainer = LavenderMistOnPrimaryContainer,
    secondary = LavenderMistSecondary,
    onSecondary = LavenderMistOnSecondary,
    secondaryContainer = LavenderMistSecondaryContainer,
    onSecondaryContainer = LavenderMistOnSecondaryContainer,
    tertiary = LavenderMistTertiary,
    onTertiary = LavenderMistOnTertiary,
    tertiaryContainer = LavenderMistTertiaryContainer,
    onTertiaryContainer = LavenderMistOnTertiaryContainer,
    background = LavenderMistDarkBackground,
    onBackground = TextPrimary,
    surface = LavenderMistDarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = LavenderMistDarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = LavenderMistDarkSurfaceContainer,
    outline = LavenderMistDarkOutline,
    outlineVariant = LavenderMistDarkOutlineVariant,
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
)

private val LavenderMistLightScheme = lightColorScheme(
    primary = LavenderMistLightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DEF8),
    onPrimaryContainer = Color(0xFF1E1139),
    secondary = LavenderMistLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECE4F7),
    onSecondaryContainer = Color(0xFF1A112E),
    background = LavenderMistLightBackground,
    onBackground = LightTextPrimary,
    surface = LavenderMistLightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LavenderMistLightSurfaceContainer,
    onSurfaceVariant = LightTextSecondary,
    surfaceContainer = LavenderMistLightSurfaceElevated,
    outline = Color(0xFF7A728A),
    outlineVariant = Color(0xFFCCC5DB),
    error = Color(0xFFDC2626),
    onError = Color.White
)

// =========================================================================
// PALETTE 3: PEACH EMBER (Cinematic, Atmospheric)
// =========================================================================
private val PeachEmberDarkScheme = darkColorScheme(
    primary = PeachEmberPrimary,
    onPrimary = PeachEmberOnPrimary,
    primaryContainer = PeachEmberPrimaryContainer,
    onPrimaryContainer = PeachEmberOnPrimaryContainer,
    secondary = PeachEmberSecondary,
    onSecondary = PeachEmberOnSecondary,
    secondaryContainer = PeachEmberSecondaryContainer,
    onSecondaryContainer = PeachEmberOnSecondaryContainer,
    tertiary = PeachEmberTertiary,
    onTertiary = PeachEmberOnTertiary,
    tertiaryContainer = PeachEmberTertiaryContainer,
    onTertiaryContainer = PeachEmberOnTertiaryContainer,
    background = PeachEmberDarkBackground,
    onBackground = TextPrimary,
    surface = PeachEmberDarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = PeachEmberDarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = PeachEmberDarkSurfaceContainer,
    outline = PeachEmberDarkOutline,
    outlineVariant = PeachEmberDarkOutlineVariant,
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
)

private val PeachEmberLightScheme = lightColorScheme(
    primary = PeachEmberLightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBD1),
    onPrimaryContainer = Color(0xFF381308),
    secondary = PeachEmberLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0D7),
    onSecondaryContainer = Color(0xFF301107),
    background = PeachEmberLightBackground,
    onBackground = LightTextPrimary,
    surface = PeachEmberLightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = PeachEmberLightSurfaceContainer,
    onSurfaceVariant = LightTextSecondary,
    surfaceContainer = PeachEmberLightSurfaceElevated,
    outline = Color(0xFF86726B),
    outlineVariant = Color(0xFFDAC3BC),
    error = Color(0xFFDC2626),
    onError = Color.White
)

// =========================================================================
// PALETTE 4: SAGE SERENITY (Peaceful, Understated)
// =========================================================================
private val SageSerenityDarkScheme = darkColorScheme(
    primary = SageSerenityPrimary,
    onPrimary = SageSerenityOnPrimary,
    primaryContainer = SageSerenityPrimaryContainer,
    onPrimaryContainer = SageSerenityOnPrimaryContainer,
    secondary = SageSerenitySecondary,
    onSecondary = SageSerenityOnSecondary,
    secondaryContainer = SageSerenitySecondaryContainer,
    onSecondaryContainer = SageSerenityOnSecondaryContainer,
    tertiary = SageSerenityTertiary,
    onTertiary = SageSerenityOnTertiary,
    tertiaryContainer = SageSerenityTertiaryContainer,
    onTertiaryContainer = SageSerenityOnTertiaryContainer,
    background = SageSerenityDarkBackground,
    onBackground = TextPrimary,
    surface = SageSerenityDarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = SageSerenityDarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SageSerenityDarkSurfaceContainer,
    outline = SageSerenityDarkOutline,
    outlineVariant = SageSerenityDarkOutlineVariant,
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
)

private val SageSerenityLightScheme = lightColorScheme(
    primary = SageSerenityLightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCCE8D2),
    onPrimaryContainer = Color(0xFF072111),
    secondary = SageSerenityLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6EADA),
    onSecondaryContainer = Color(0xFF091F11),
    background = SageSerenityLightBackground,
    onBackground = LightTextPrimary,
    surface = SageSerenityLightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = SageSerenityLightSurfaceContainer,
    onSurfaceVariant = LightTextSecondary,
    surfaceContainer = SageSerenityLightSurfaceElevated,
    outline = Color(0xFF708074),
    outlineVariant = Color(0xFFBFCCC2),
    error = Color(0xFFDC2626),
    onError = Color.White
)

// Legacy alias accessors for backwards compatibility
val ObsidianMintDarkScheme = RoseQuartzDarkScheme
val ObsidianMintLightScheme = RoseQuartzLightScheme
val MidnightAzureDarkScheme = LavenderMistDarkScheme
val MidnightAzureLightScheme = LavenderMistLightScheme
val SunsetAmberDarkScheme = PeachEmberDarkScheme
val SunsetAmberLightScheme = PeachEmberLightScheme
val CyberVioletDarkScheme = SageSerenityDarkScheme
val CyberVioletLightScheme = SageSerenityLightScheme

fun getPreviewColorScheme(
    themePreset: ThemePreset,
    isDark: Boolean = true
): ColorScheme {
    return when (themePreset) {
        ThemePreset.ROSE_QUARTZ, ThemePreset.OBSIDIAN_MINT -> if (isDark) RoseQuartzDarkScheme else RoseQuartzLightScheme
        ThemePreset.LAVENDER_MIST, ThemePreset.MIDNIGHT_AZURE -> if (isDark) LavenderMistDarkScheme else LavenderMistLightScheme
        ThemePreset.PEACH_EMBER, ThemePreset.SUNSET_AMBER -> if (isDark) PeachEmberDarkScheme else PeachEmberLightScheme
        ThemePreset.SAGE_SERENITY, ThemePreset.CYBER_VIOLET -> if (isDark) SageSerenityDarkScheme else SageSerenityLightScheme
    }
}

@Composable
fun MyApplicationTheme(
    useWallpaperDynamicColor: Boolean = true,
    themePreset: ThemePreset = ThemePreset.ROSE_QUARTZ,
    themeMode: ThemeMode = ThemeMode.DARK,
    fontOption: FontOption = FontOption.POPPINS,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    
    // LyricBar V2 is strictly a dark-only experience across all system states.
    // The theme consistently resolves to dark appearance while preserving light scheme definitions for future use.
    val isDark = true

    val colorScheme: ColorScheme = if (useWallpaperDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        getPreviewColorScheme(themePreset, isDark = true)
    }

    // App-wide UI uses stable AppStandardTypography; fontOption applies to lyric displays
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppStandardTypography,
        content = content
    )
}
