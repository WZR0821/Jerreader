package com.jerreader.unified.ui

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
import com.jerreader.unified.design.JerreaderAccentPalette
import com.jerreader.unified.design.JerreaderAppPalette
import com.jerreader.unified.design.JerreaderRgb

/**
 * Compose side of the shared app palette.
 *
 * The accent table and the surface tones live in
 * `core/design/JerreaderAppPalette.kt` so SwiftUI resolves exactly the same
 * colours; this file only turns them into Material tokens. `JerreaderAccent`
 * stays as the Compose-facing name and is a thin alias over the shared enum.
 */
typealias JerreaderAccent = JerreaderAccentPalette

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

private fun JerreaderRgb.toColor(): Color =
    Color(red.toFloat(), green.toFloat(), blue.toFloat())

fun jerreaderColors(accent: JerreaderAccent, dark: Boolean): JerreaderColors {
    val shared = JerreaderAppPalette.colors(accent, dark)
    return JerreaderColors(
        accent = shared.accent.toColor(),
        accentFill = shared.accentFill.toColor(),
        canvas = shared.canvas.toColor(),
        paper = shared.paper.toColor(),
        raisedPaper = shared.raisedPaper.toColor(),
        mutedSurface = shared.mutedSurface.toColor(),
        line = shared.line.toColor(),
        onAccent = shared.onAccent.toColor(),
        secondaryText = shared.secondaryText.toColor()
    )
}

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

/**
 * Explicit type scale. Material's defaults render far larger than the iOS app
 * on a phone, which is what made the settings and reader panels feel loose and
 * disorganised, so every size used by Jerreader screens is pinned here.
 */
private val jerreaderTypography = Typography(
    // The shelf's large title. Left undefined this fell through to Material's
    // 36sp Regular while iOS drew a 34pt Bold large title, which is why the two
    // shelves never looked like the same app above the fold.
    displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp)
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
    CompositionLocalProvider(LocalJerreaderColors provides colors) {
        MaterialTheme(
            colorScheme = jerreaderColorScheme(accent, dark),
            shapes = jerreaderShapes,
            typography = jerreaderTypography,
            content = content
        )
    }
}
