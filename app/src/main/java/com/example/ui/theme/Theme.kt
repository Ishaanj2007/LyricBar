package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.data.preferences.FontOption
import com.example.data.preferences.ThemePreset

private val ObsidianMintScheme = darkColorScheme(
    primary = MintAccent,
    onPrimary = Color(0xFF003826),
    primaryContainer = MintAccentDim,
    onPrimaryContainer = Color(0xFF6FF7CC),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF003822),
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = Color(0xFFA7F3D0),
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = DarkSurfaceElevated,
    outline = Color(0xFF263C32),
    outlineVariant = Color(0xFF16251F),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
)

private val MidnightAzureScheme = darkColorScheme(
    primary = AzurePrimary,
    onPrimary = Color(0xFF002952),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFBFDBFE),
    secondary = AzureSecondary,
    onSecondary = Color(0xFF0A2540),
    secondaryContainer = Color(0xFF1E293B),
    onSecondaryContainer = Color(0xFFE2E8F0),
    background = AzureBackground,
    onBackground = TextPrimary,
    surface = AzureSurface,
    onSurface = TextPrimary,
    surfaceVariant = AzureSurfaceContainer,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = Color(0xFF223152),
    outline = Color(0xFF2D3F66),
    outlineVariant = Color(0xFF1E293B),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
)

private val SunsetAmberScheme = darkColorScheme(
    primary = AmberPrimary,
    onPrimary = Color(0xFF1F1200),
    primaryContainer = Color(0xFF78350F),
    onPrimaryContainer = Color(0xFFFDE68A),
    secondary = AmberSecondary,
    onSecondary = Color(0xFF1F1200),
    secondaryContainer = Color(0xFF451A03),
    onSecondaryContainer = Color(0xFFFED7AA),
    background = AmberBackground,
    onBackground = TextPrimary,
    surface = AmberSurface,
    onSurface = TextPrimary,
    surfaceVariant = AmberSurfaceContainer,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = Color(0xFF3D2A1F),
    outline = Color(0xFF523B28),
    outlineVariant = Color(0xFF2E1F14),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
)

private val CyberVioletScheme = darkColorScheme(
    primary = VioletPrimary,
    onPrimary = Color(0xFF2E0854),
    primaryContainer = Color(0xFF581C87),
    onPrimaryContainer = Color(0xFFE9D5FF),
    secondary = VioletSecondary,
    onSecondary = Color(0xFF2E0854),
    secondaryContainer = Color(0xFF3B0764),
    onSecondaryContainer = Color(0xFFF3E8FF),
    background = VioletBackground,
    onBackground = TextPrimary,
    surface = VioletSurface,
    onSurface = TextPrimary,
    surfaceVariant = VioletSurfaceContainer,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = Color(0xFF372654),
    outline = Color(0xFF4C2A75),
    outlineVariant = Color(0xFF281440),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
)

@Composable
fun MyApplicationTheme(
    useWallpaperDynamicColor: Boolean = true,
    themePreset: ThemePreset = ThemePreset.OBSIDIAN_MINT,
    fontOption: FontOption = FontOption.POPPINS,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()

    val colorScheme: ColorScheme = if (useWallpaperDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) {
            // Authentic native Material You dynamic dark scheme with tonal harmony
            dynamicDarkColorScheme(context)
        } else {
            // Authentic native Material You dynamic light scheme
            dynamicLightColorScheme(context)
        }
    } else {
        when (themePreset) {
            ThemePreset.OBSIDIAN_MINT -> ObsidianMintScheme
            ThemePreset.MIDNIGHT_AZURE -> MidnightAzureScheme
            ThemePreset.SUNSET_AMBER -> SunsetAmberScheme
            ThemePreset.CYBER_VIOLET -> CyberVioletScheme
        }
    }

    // App-wide UI uses stable AppStandardTypography; fontOption applies only to lyrics bar
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppStandardTypography,
        content = content
    )
}
