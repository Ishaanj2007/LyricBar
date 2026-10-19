package com.example.ui.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.data.preferences.FontOption
import com.example.data.preferences.ThemePreset

private val ObsidianMintScheme = darkColorScheme(
    primary = MintAccent,
    onPrimary = DarkBackground,
    primaryContainer = MintAccentDim,
    onPrimaryContainer = Color.White,
    secondary = SkyAccent,
    onSecondary = DarkBackground,
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = DarkSurfaceElevated,
    error = CoralAccent,
    onError = Color.White
)

private val MidnightAzureScheme = darkColorScheme(
    primary = AzurePrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color.White,
    secondary = AzureSecondary,
    onSecondary = Color.White,
    background = AzureBackground,
    onBackground = TextPrimary,
    surface = AzureSurface,
    onSurface = TextPrimary,
    surfaceVariant = AzureSurfaceContainer,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = Color(0xFF223152),
    error = CoralAccent,
    onError = Color.White
)

private val SunsetAmberScheme = darkColorScheme(
    primary = AmberPrimary,
    onPrimary = Color(0xFF1F1200),
    primaryContainer = Color(0xFF78350F),
    onPrimaryContainer = Color.White,
    secondary = AmberSecondary,
    onSecondary = Color(0xFF1F1200),
    background = AmberBackground,
    onBackground = TextPrimary,
    surface = AmberSurface,
    onSurface = TextPrimary,
    surfaceVariant = AmberSurfaceContainer,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = Color(0xFF3D2A1F),
    error = CoralAccent,
    onError = Color.White
)

private val CyberVioletScheme = darkColorScheme(
    primary = VioletPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF581C87),
    onPrimaryContainer = Color.White,
    secondary = VioletSecondary,
    onSecondary = Color.White,
    background = VioletBackground,
    onBackground = TextPrimary,
    surface = VioletSurface,
    onSurface = TextPrimary,
    surfaceVariant = VioletSurfaceContainer,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = Color(0xFF372654),
    error = CoralAccent,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    useWallpaperDynamicColor: Boolean = true,
    themePreset: ThemePreset = ThemePreset.OBSIDIAN_MINT,
    fontOption: FontOption = FontOption.POPPINS,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val colorScheme: ColorScheme = if (useWallpaperDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
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
