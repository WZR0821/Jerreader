package com.jerreader.shared.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Compose port of the iOS `JerreaderTheme` tokens, kept in step with
 * `Jerreader/App/JerreaderTheme.swift` so both platforms render the same
 * hierarchy instead of the stock Material palette.
 *
 * 2.0「纸墨」: every theme sits on the same warm paper (dark: ink) and the
 * chosen accent only shows in controls, marks and hero cards. The values are
 * written as hex in docs/DESIGN_2.0.md and must match the Swift ones.
 */
enum class JerreaderAccent(val title: String, val detail: String) {
    OCEAN("海蓝", "沉静的蓝灰色系"),
    FOREST("森林", "柔和的青绿色系"),
    WISTERIA("紫藤", "克制的紫罗兰色系"),
    AMBER("琥珀", "温暖的黄金与棕色系"),
    BERRY("莓红", "清晰的玫红色系"),
    // 2.0.1. Appended, never reordered: the stored value is the name.
    TEAL("松石", "清透的青碧色系"),
    TERRACOTTA("赤陶", "沉稳的陶土红色系"),
    GRAPHITE("石墨", "中性的墨灰色系");

    /** `0xRRGGBB` of the accent in light or dark mode. */
    internal fun hex(dark: Boolean): Long = when (this) {
        OCEAN -> if (dark) 0x8DBCE8 else 0x2C5D8F
        FOREST -> if (dark) 0x7FC8A6 else 0x2E6B53
        WISTERIA -> if (dark) 0xB7A9F0 else 0x5B4C9C
        AMBER -> if (dark) 0xEBB56A else 0x94601C
        BERRY -> if (dark) 0xEE91B6 else 0x9A3A62
        TEAL -> if (dark) 0x7CCAD6 else 0x1F6E7A
        TERRACOTTA -> if (dark) 0xEFA088 else 0x9C4A32
        GRAPHITE -> if (dark) 0xB9C2D0 else 0x47505E
    }

    internal fun rgb(dark: Boolean): Triple<Float, Float, Float> {
        val value = hex(dark)
        return Triple(
            ((value shr 16) and 0xFF) / 255f,
            ((value shr 8) and 0xFF) / 255f,
            (value and 0xFF) / 255f
        )
    }
}

/**
 * 外观模式 (2.0.1): whether the app's own pages follow the system's light or
 * dark setting or hold one of them. The reader page is not governed by this —
 * it takes light or dark from the paper the book is read on.
 *
 * iOS stores the same raw values under `appearance.mode`.
 */
enum class JerreaderAppearanceMode(val title: String, val storageValue: String) {
    SYSTEM("跟随系统", "system"),
    LIGHT("浅色", "light"),
    DARK("深色", "dark");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStorage(raw: String?): JerreaderAppearanceMode =
            entries.firstOrNull { it.storageValue == raw } ?: SYSTEM
    }
}

/** Whether the canvas carries its faint accent wash (界面主题 → 背景光晕). */
val LocalJerreaderBackdropGlow = staticCompositionLocalOf { true }

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
    val secondaryText: Color,
    /** The light-mode accent in both modes: the deep end of a hero gradient. */
    val accentDeep: Color = accent,
    /** 朱: due reviews and reminders. */
    val vermilion: Color = Color(0xFFC8513A),
    /** 金: streaks and achievements. */
    val gold: Color = Color(0xFFB07F28),
    /** 青: mastered, done. */
    val jade: Color = Color(0xFF3C8A66),
    /** 墨: the dark end of a hero gradient. */
    val ink: Color = Color(0xFF1B2433),
    val isDark: Boolean = false
)

/** `0xRRGGBB` as an opaque colour. */
internal fun jerreaderHex(value: Long, alpha: Float = 1f): Color =
    Color(0xFF000000 or value).copy(alpha = alpha)

val LocalJerreaderColors = staticCompositionLocalOf {
    jerreaderColors(JerreaderAccent.OCEAN, dark = false)
}

fun jerreaderColors(accent: JerreaderAccent, dark: Boolean): JerreaderColors {
    val accentColor = jerreaderHex(accent.hex(dark))
    val canvas = jerreaderHex(if (dark) 0x101215 else 0xF4F1EA)
    return JerreaderColors(
        accent = accentColor,
        // Keep alpha, as SwiftUI does: the same token is drawn on canvas, paper, or a card.
        accentFill = accentColor.copy(alpha = if (dark) 0.22f else 0.12f),
        canvas = canvas,
        paper = surface(dark, level = 0),
        raisedPaper = surface(dark, level = 1),
        mutedSurface = surface(dark, level = -1),
        line = jerreaderHex(if (dark) 0x2B3038 else 0xE3DCCD),
        onAccent = if (dark) jerreaderHex(0x0B0F14) else Color.White,
        secondaryText = (if (dark) Color(0.922f, 0.922f, 0.961f) else Color(0.235f, 0.235f, 0.263f))
            .copy(alpha = 0.60f),
        accentDeep = jerreaderHex(accent.hex(dark = false)),
        vermilion = jerreaderHex(if (dark) 0xF08A6E else 0xC8513A),
        gold = jerreaderHex(if (dark) 0xE5BC62 else 0xB07F28),
        jade = jerreaderHex(if (dark) 0x72CC9E else 0x3C8A66),
        ink = jerreaderHex(if (dark) 0x0B0E13 else 0x1B2433),
        isDark = dark
    )
}

private fun surface(dark: Boolean, level: Int): Color = if (dark) {
    when {
        level > 0 -> jerreaderHex(0x20242A)
        level < 0 -> jerreaderHex(0x0B0D10)
        else -> jerreaderHex(0x181B20)
    }
} else {
    when {
        level > 0 -> jerreaderHex(0xFFFFFF)
        level < 0 -> jerreaderHex(0xEBE6DB)
        else -> jerreaderHex(0xFFFDF8)
    }
}


/**
 * SwiftUI's `.secondary` on text in the app's own rows and cards: the foreground
 * at half strength (127 on white, measured). System-styled text, such as form
 * headers and footers or a LabeledContent value, gets secondaryLabel instead,
 * which is `onSurfaceVariant`.
 */
val ColorScheme.plainSecondary: Color get() = onSurface.copy(alpha = .5f)

/** SwiftUI's `.tertiary` on the same text: the foreground at a quarter. */
val ColorScheme.plainTertiary: Color get() = onSurface.copy(alpha = .25f)

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

/**
 * SwiftUI's line height, kept even by a line on its own. The default style
 * pads a lone line of Chinese that is shorter than the CJK font back out to
 * the font's height (about 1.45 em), which leaves titles, labels, group titles
 * and two-line rows 2–4 points further apart than on iOS. The tight mode holds
 * the line height and lets the glyphs reach past it, as SwiftUI does.
 */
val ExactSystemLineHeight = LineHeightStyle(
    LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both, LineHeightStyle.Mode.Tight
)

/**
 * SwiftUI gives a line of Chinese the height of the system font's line, the
 * same as a line of Latin text. Compose grows a line to the taller CJK
 * fallback font unless the line height is enforced on every line, which is
 * what this does, so text-heavy pages stack up the way they do on iOS.
 *
 * With the default [style] the first and last lines of a block are still
 * padded out to the CJK font (about 1.45 em), so a line on its own keeps that
 * height; the pages tuned against iOS with that behaviour use it.
 * [ExactSystemLineHeight] holds every line, a lone one included, to the line height.
 */
fun Typography.withSystemLineHeights(
    style: LineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
): Typography {
    fun TextStyle.fixed() = copy(lineHeightStyle = style)
    return copy(
        displayLarge = displayLarge.fixed(), displayMedium = displayMedium.fixed(), displaySmall = displaySmall.fixed(),
        headlineLarge = headlineLarge.fixed(), headlineMedium = headlineMedium.fixed(), headlineSmall = headlineSmall.fixed(),
        titleLarge = titleLarge.fixed(), titleMedium = titleMedium.fixed(), titleSmall = titleSmall.fixed(),
        bodyLarge = bodyLarge.fixed(), bodyMedium = bodyMedium.fixed(), bodySmall = bodySmall.fixed(),
        labelLarge = labelLarge.fixed(), labelMedium = labelMedium.fixed(), labelSmall = labelSmall.fixed()
    )
}

/** Lays [content] out with [withSystemLineHeights] applied to the current type scale. */
@Composable
fun SystemLineHeights(content: @Composable () -> Unit) {
    val typography = MaterialTheme.typography
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = remember(typography) { typography.withSystemLineHeights() },
        shapes = MaterialTheme.shapes,
        content = content
    )
}

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
    backdropGlow: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = jerreaderColors(accent, dark)
    // Match iOS's extra-large cap (body 17 -> 19 pt). Publication text keeps
    // its own Readium font preference and is not limited by this UI policy.
    val systemDensity = LocalDensity.current
    val interfaceDensity = Density(systemDensity.density,
        systemDensity.fontScale.coerceIn(0.5f, 19f / 17f))
    val scheme = jerreaderColorScheme(accent, dark)
    CompositionLocalProvider(
        LocalJerreaderColors provides colors,
        LocalDensity provides interfaceDensity,
        LocalJerreaderBackdropGlow provides backdropGlow
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = jerreaderShapes,
            typography = jerreaderTypography
        ) {
            // Text with no colour of its own takes LocalContentColor, which is
            // black until a Surface sets it. The 2.0 pages paint their own
            // canvas behind transparent scaffolds (contentColorFor(Transparent)
            // falls back to LocalContentColor), so start from the theme's
            // foreground, as iOS `.primary` does; otherwise titles stay black
            // on the dark canvas.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
