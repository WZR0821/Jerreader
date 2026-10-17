package com.jerreader.shared.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Compose port of the iOS `JerreaderTheme` tokens, kept in step with
 * `Jerreader/App/JerreaderTheme.swift` so both platforms render the same cool,
 * paper-like hierarchy instead of the stock Material palette.
 *
 * From 1.1.0 the canvas and paper tones are fixed cool blue-greys rather than
 * being tinted by the chosen accent. Deriving them from the accent made the
 * amber and berry themes read warm and washed out; the accent now only shows in
 * controls, outlines and marks, and every theme sits on the same cool page.
 */
enum class JerreaderAccent(val title: String, val detail: String) {
    OCEAN("海蓝", "沉静的蓝灰色系"),
    FOREST("森林", "柔和的青绿色系"),
    WISTERIA("紫藤", "克制的紫罗兰色系"),
    AMBER("琥珀", "温暖的黄金与棕色系"),
    BERRY("莓红", "清晰的玫红色系");

    internal fun rgb(dark: Boolean): Triple<Float, Float, Float> = when (this) {
        OCEAN -> if (dark) Triple(0.46f, 0.72f, 0.88f) else Triple(0.18f, 0.41f, 0.60f)
        FOREST -> if (dark) Triple(0.42f, 0.76f, 0.62f) else Triple(0.16f, 0.43f, 0.34f)
        WISTERIA -> if (dark) Triple(0.68f, 0.61f, 0.91f) else Triple(0.39f, 0.31f, 0.61f)
        AMBER -> if (dark) Triple(0.91f, 0.68f, 0.34f) else Triple(0.55f, 0.35f, 0.10f)
        BERRY -> if (dark) Triple(0.90f, 0.52f, 0.68f) else Triple(0.58f, 0.22f, 0.38f)
    }
}

/** Tokens that Material's `ColorScheme` has no slot for. */
data class JerreaderColors(
    val accent: Color,
    val accentFill: Color,
    val canvas: Color,
    val paper: Color,
    val raisedPaper: Color,
    val mutedSurface: Color,
    val line: Color,
    val onAccent: Color,
    val secondaryText: Color
)

val LocalJerreaderColors = staticCompositionLocalOf {
    jerreaderColors(JerreaderAccent.OCEAN, dark = false)
}

fun jerreaderColors(accent: JerreaderAccent, dark: Boolean): JerreaderColors {
    val (red, green, blue) = accent.rgb(dark)
    val accentColor = Color(red, green, blue)
    val canvas = if (dark) {
        Color(0.040f, 0.065f, 0.090f)
    } else {
        Color(0.941f, 0.961f, 0.975f)
    }
    return JerreaderColors(
        accent = accentColor,
        // Keep alpha, as SwiftUI does: the same token is drawn on canvas, paper, or a card.
        accentFill = accentColor.copy(alpha = if (dark) 0.22f else 0.12f),
        canvas = canvas,
        paper = surface(dark, level = 0),
        raisedPaper = surface(dark, level = 1),
        mutedSurface = surface(dark, level = -1),
        line = accentColor.copy(alpha = if (dark) 0.28f else 0.18f),
        onAccent = if (dark) Color(0.025f, 0.04f, 0.05f) else Color.White,
        secondaryText = (if (dark) Color(0.922f, 0.922f, 0.961f) else Color(0.235f, 0.235f, 0.263f))
            .copy(alpha = 0.60f)
    )
}

private fun surface(dark: Boolean, level: Int): Color = if (dark) {
    when {
        level > 0 -> Color(0.095f, 0.145f, 0.185f)
        level < 0 -> Color(0.028f, 0.055f, 0.080f)
        else -> Color(0.060f, 0.100f, 0.135f)
    }
} else {
    when {
        level > 0 -> Color(0.985f, 0.993f, 0.998f)
        level < 0 -> Color(0.885f, 0.925f, 0.953f)
        else -> Color(0.965f, 0.978f, 0.989f)
    }
}


fun jerreaderColorScheme(accent: JerreaderAccent, dark: Boolean): ColorScheme {
    val colors = jerreaderColors(accent, dark)
    val onSurface = if (dark) Color.White else Color.Black
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.accentFill,
        onPrimaryContainer = if (dark) colors.accent else colors.accent,
        inversePrimary = colors.accent,
        secondary = colors.accent,
        onSecondary = colors.onAccent,
        secondaryContainer = colors.accentFill,
        onSecondaryContainer = colors.accent,
        tertiary = colors.accent,
        onTertiary = colors.onAccent,
        tertiaryContainer = colors.accentFill,
        onTertiaryContainer = colors.accent,
        background = colors.canvas,
        onBackground = onSurface,
        surface = colors.paper,
        onSurface = onSurface,
        surfaceVariant = colors.mutedSurface,
        onSurfaceVariant = colors.secondaryText,
        surfaceTint = colors.accent,
        inverseSurface = onSurface,
        inverseOnSurface = colors.paper,
        outline = colors.line,
        outlineVariant = colors.line,
        scrim = Color.Black,
        surfaceBright = colors.raisedPaper,
        surfaceDim = colors.mutedSurface,
        surfaceContainerLowest = colors.raisedPaper,
        surfaceContainerLow = colors.paper,
        surfaceContainer = colors.paper,
        surfaceContainerHigh = colors.raisedPaper,
        surfaceContainerHighest = colors.raisedPaper
    )
}

/**
 * Explicit type scale. Material's defaults render far larger than the iOS app
 * on a phone, which is what made the settings and reader panels feel loose and
 * disorganised, so every size used by Jerreader screens is pinned here.
 */
private val jerreaderTypography = Typography(
    displayLarge = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
    displayMedium = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 22.sp, lineHeight = 26.5.sp, letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 26.5.sp, letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 20.5.sp, letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 18.sp, letterSpacing = 0.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 20.5.sp, letterSpacing = 0.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 18.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 14.5.sp, letterSpacing = 0.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 13.sp)
)

/** Matches the iOS 16pt card radius used from 1.1.0. */
private val jerreaderShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp)
)

@Composable
fun JerreaderTheme(
    accent: JerreaderAccent = JerreaderAccent.OCEAN,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = jerreaderColors(accent, dark)
    // Match iOS's extra-large cap (body 17 -> 19 pt). Publication text keeps
    // its own Readium font preference and is not limited by this UI policy.
    val systemDensity = LocalDensity.current
    val interfaceDensity = Density(systemDensity.density,
        systemDensity.fontScale.coerceIn(0.5f, 19f / 17f))
    CompositionLocalProvider(LocalJerreaderColors provides colors, LocalDensity provides interfaceDensity) {
        MaterialTheme(
            colorScheme = jerreaderColorScheme(accent, dark),
            shapes = jerreaderShapes,
            typography = jerreaderTypography,
            content = content
        )
    }
}
