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
 * Standard app UI typography — kept stable so app buttons, tabs, titles and settings
 * maintain consistent system styling while lyrics bar customizes independently.
 */
val AppStandardTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 48.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 34.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 30.sp
    ),
    titleLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp
    )
)

val Typography = AppStandardTypography
