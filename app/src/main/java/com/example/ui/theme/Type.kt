package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.preferences.FontOption

val InterFontFamily = FontFamily(Font(R.font.inter))
val PoppinsFontFamily = FontFamily(Font(R.font.poppins))
val MontserratFontFamily = FontFamily(Font(R.font.montserrat))
val LoraFontFamily = FontFamily(Font(R.font.lora))
val RobotoMonoFontFamily = FontFamily(Font(R.font.roboto_mono))
val PlayfairFontFamily = FontFamily(Font(R.font.playfair_display))
val OutfitFontFamily = FontFamily(Font(R.font.outfit))
val RubikFontFamily = FontFamily(Font(R.font.rubik))
val PlusJakartaFontFamily = FontFamily(Font(R.font.plus_jakarta_sans))
val SpaceGroteskFontFamily = FontFamily(Font(R.font.space_grotesk))
val ComfortaaFontFamily = FontFamily(Font(R.font.comfortaa))
val CinzelFontFamily = FontFamily(Font(R.font.cinzel))

/**
 * Returns the specific FontFamily for the lyrics bar according to user selection.
 * Note: App-wide UI typography remains standard system/inter for clean readability.
 */
fun getFontFamily(fontOption: FontOption): FontFamily {
    return when (fontOption) {
        FontOption.SYSTEM -> FontFamily.Default
        FontOption.INTER -> InterFontFamily
        FontOption.POPPINS -> PoppinsFontFamily
        FontOption.MONTSERRAT -> MontserratFontFamily
        FontOption.LORA -> LoraFontFamily
        FontOption.ROBOTO_MONO -> RobotoMonoFontFamily
        FontOption.PLAYFAIR -> PlayfairFontFamily
        FontOption.OUTFIT -> OutfitFontFamily
        FontOption.RUBIK -> RubikFontFamily
        FontOption.PLUS_JAKARTA_SANS -> PlusJakartaFontFamily
        FontOption.SPACE_GROTESK -> SpaceGroteskFontFamily
        FontOption.COMFORTAA -> ComfortaaFontFamily
        FontOption.CINZEL -> CinzelFontFamily
    }
}

fun getLyricsFontFamily(fontOption: FontOption): FontFamily = getFontFamily(fontOption)

/**
 * Dedicated LyricBar typography system providing clear visual hierarchy
 * between synchronized lyrics and standard application UI controls.
 */
object LyricBarTypography {
    fun activeLyricStyle(
        fontOption: FontOption,
        fontSize: androidx.compose.ui.unit.TextUnit = 18.sp,
        fontWeight: FontWeight = FontWeight.Bold,
        color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified
    ): TextStyle = TextStyle(
        fontFamily = getFontFamily(fontOption),
        fontWeight = fontWeight,
        fontSize = fontSize,
        lineHeight = (fontSize.value * 1.32f).sp,
        letterSpacing = 0.15.sp,
        color = color
    )

    fun inactiveLyricStyle(
        fontOption: FontOption,
        fontSize: androidx.compose.ui.unit.TextUnit = 16.sp,
        fontWeight: FontWeight = FontWeight.SemiBold,
        color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified
    ): TextStyle = TextStyle(
        fontFamily = getFontFamily(fontOption),
        fontWeight = fontWeight,
        fontSize = fontSize,
        lineHeight = (fontSize.value * 1.30f).sp,
        letterSpacing = 0.10.sp,
        color = color
    )

    fun contextualLyricStyle(
        fontOption: FontOption,
        fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
        fontWeight: FontWeight = FontWeight.Normal,
        color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified
    ): TextStyle = TextStyle(
        fontFamily = getFontFamily(fontOption),
        fontWeight = fontWeight,
        fontSize = fontSize,
        lineHeight = (fontSize.value * 1.28f).sp,
        letterSpacing = 0.20.sp,
        color = color
    )
}

/**
 * Standard app UI typography — kept clean, consistent, and legible across all screens
 * while the lyrics view customizes independently according to user preference.
 */
val AppStandardTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = (-0.25).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.1.sp
    ),
    titleLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.15.sp
    ),
    titleMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.25.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp
    ),
    bodySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    ),
    labelLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    )
)

val Typography = AppStandardTypography

