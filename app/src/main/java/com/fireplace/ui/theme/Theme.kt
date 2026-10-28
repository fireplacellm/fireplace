package com.fireplace.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Fireplace theme: a warm "glowing hearth" identity drawn from the logo (see
 * [Color.kt]). Dark is the default brand experience; a warm light palette mirrors it.
 * Not Material You dynamic color - we want one recognizable identity on every device.
 */

private val DarkColors = darkColorScheme(
    primary = Ember,
    onPrimary = Color(0xFF2A1400),
    primaryContainer = Color(0xFF4A2410),
    onPrimaryContainer = GoldSoft,
    secondary = Color(0xFFE8C98A),
    onSecondary = Color(0xFF2A1F08),
    secondaryContainer = Color(0xFF2B2822),
    onSecondaryContainer = Color(0xFFEBD9C0),
    tertiary = Grounded,
    onTertiary = Color(0xFF07271A),
    tertiaryContainer = Color(0xFF123A2B),
    onTertiaryContainer = GroundedSoft,
    background = Char1,
    onBackground = Warm0,
    surface = Char1,
    onSurface = Warm0,
    surfaceVariant = Char2,
    onSurfaceVariant = Warm1,
    surfaceContainerLowest = Char0,
    surfaceContainerLow = Color(0xFF17161B),
    surfaceContainer = Char2,
    surfaceContainerHigh = Char3,
    surfaceContainerHighest = Char4,
    outline = CharLine,
    outlineVariant = CharLineSoft,
    inverseSurface = Warm0,
    inverseOnSurface = Char1,
    error = BadEmber,
    onError = Color(0xFF2A0A05),
    errorContainer = Color(0xFF4A1810),
    onErrorContainer = Color(0xFFFFD6CC),
)

private val LightColors = lightColorScheme(
    primary = EmberDeep,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF6E3208),
    secondary = Color(0xFF8A6A2E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0E6D6),
    onSecondaryContainer = Color(0xFF4A3B22),
    tertiary = Color(0xFF2E9E6B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBFECD6),
    onTertiaryContainer = Color(0xFF07331F),
    background = Paper,
    onBackground = InkDark,
    surface = Paper,
    onSurface = InkDark,
    surfaceVariant = PaperCont,
    onSurfaceVariant = InkMid,
    surfaceContainerLowest = PaperCard,
    surfaceContainerLow = Color(0xFFF7F2EB),
    surfaceContainer = PaperCont,
    surfaceContainerHigh = PaperCont2,
    surfaceContainerHighest = Color(0xFFE2DACC),
    outline = PaperLine2,
    outlineVariant = PaperLine,
    inverseSurface = InkDark,
    inverseOnSurface = Paper,
    error = Color(0xFFCB4632),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD3),
    onErrorContainer = Color(0xFF5C1710),
)

// Rounder, friendlier shapes than Material defaults; pills come from CircleShape inline.
private val FireShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

private val Sans = FontFamily.SansSerif
private val Mono = FontFamily.Monospace   // technical accent for metadata (model name, counts, [n])

// Sans for reading; tight tracking + measured weights for a modern, crafted feel.
// The smallest label tier is monospace, giving metadata a precise, instrument-like look.
private val FireType = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp),
    headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 29.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.2.sp),
)

@Composable
fun FireplaceTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = FireType,
        shapes = FireShapes,
        content = content,
    )
}
