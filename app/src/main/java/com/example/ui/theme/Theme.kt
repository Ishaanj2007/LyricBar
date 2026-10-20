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
    secondary = SkyAccent,
    onSecondary = Color(0xFF003549),
    secondaryContainer = Color(0xFF004D69),
    onSecondaryContainer = Color(0xFFC3E8FF),
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = DarkSurfaceElevated,
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1E293B),
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
    val darkTheme = isSystemInDarkTheme()

    val colorScheme: ColorScheme = if (useWallpaperDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) {
            val dynamicDark = dynamicDarkColorScheme(context)
            dynamicDark.copy(
                background = DarkBackground,
                surface = DarkSurface,
                surfaceVariant = DarkSurfaceContainer,
                surfaceContainer = DarkSurfaceElevated,
                onBackground = TextPrimary,
                onSurface = TextPrimary,
                onSurfaceVariant = TextSecondary
            )
        } else {
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
