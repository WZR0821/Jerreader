package com.jerreader.shared.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Compose port of the iOS `JerreaderTheme` tokens. The accent values, canvas
 * and surface formulas are copied from `Jerreader/App/JerreaderTheme.swift` so
 * both platforms render the same cool, paper-like hierarchy instead of the
 * stock Material palette.
 */
enum class JerreaderAccent(val title: String, val detail: String) {
    OCEAN("海蓝", "沉静的蓝灰色系"),
    FOREST("森林", "柔和的青绿色系"),
    WISTERIA("紫藤", "克制的紫罗兰色系"),
    AMBER("琥珀", "温暖的黄金与棕色系"),
    BERRY("莓红", "清晰的玫红色系");

    internal fun rgb(dark: Boolean): Triple<Float, Float, Float> = when (this) {
        OCEAN -> if (dark) Triple(0.47f, 0.70f, 0.88f) else Triple(0.20f, 0.39f, 0.56f)
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
        Color(0.028f + red * 0.025f, 0.038f + green * 0.025f, 0.048f + blue * 0.025f)
    } else {
        Color(0.955f + red * 0.015f, 0.955f + green * 0.015f, 0.955f + blue * 0.015f)
    }
    return JerreaderColors(
        accent = accentColor,
        accentFill = accentColor.copy(alpha = if (dark) 0.22f else 0.12f).compositeOver(canvas),
        canvas = canvas,
        paper = surface(red, green, blue, dark, level = 0),
        raisedPaper = surface(red, green, blue, dark, level = 1),
        mutedSurface = surface(red, green, blue, dark, level = -1),
        line = accentColor.copy(alpha = if (dark) 0.24f else 0.15f).compositeOver(canvas),
        onAccent = if (dark) Color(0.025f, 0.04f, 0.05f) else Color.White,
        secondaryText = if (dark) Color(0.72f, 0.75f, 0.79f) else Color(0.36f, 0.40f, 0.45f)
    )
}

private fun surface(
    red: Float,
    green: Float,
    blue: Float,
    dark: Boolean,
    level: Int
): Color = if (dark) {
    val base = if (level > 0) 0.09f else if (level < 0) 0.075f else 0.06f
    Color(base + red * 0.045f, base + green * 0.045f, base + blue * 0.045f)
} else {
    val base = if (level > 0) 1f else if (level < 0) 0.925f else 0.985f
    Color(
        minOf(base + red * 0.008f, 1f),
        minOf(base + green * 0.008f, 1f),
        minOf(base + blue * 0.008f, 1f)
    )
}

private fun Color.compositeOver(background: Color): Color = Color(
    red = red * alpha + background.red * (1 - alpha),
    green = green * alpha + background.green * (1 - alpha),
    blue = blue * alpha + background.blue * (1 - alpha)
)

fun jerreaderColorScheme(accent: JerreaderAccent, dark: Boolean): ColorScheme {
    val colors = jerreaderColors(accent, dark)
    val onSurface = if (dark) Color(0.92f, 0.94f, 0.96f) else Color(0.10f, 0.12f, 0.14f)
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

/** Matches the iOS 22pt continuous card radius. */
private val jerreaderShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun JerreaderTheme(
    accent: JerreaderAccent = JerreaderAccent.OCEAN,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = jerreaderColors(accent, dark)
    CompositionLocalProvider(LocalJerreaderColors provides colors) {
        MaterialTheme(
            colorScheme = jerreaderColorScheme(accent, dark),
            shapes = jerreaderShapes,
            content = content
        )
    }
}
