package com.jerreader.shared.library

import kotlin.math.abs
import kotlin.math.pow

/**
 * Kotlin port of the iOS `ReaderSelectionVisualStyle`. The temporary selection
 * is filled with the theme accent and outlined with a darker (or, on a dark
 * page, lighter) version of the same hue, so the marked sentence reads as one
 * shape instead of a flat wash.
 */
data class ReaderSelectionPalette(
    val fillArgb: Long,
    val strokeArgb: Long
) {
    val fillCss: String get() = fillArgb.toCssRgba()
    val strokeCss: String get() = strokeArgb.toCssRgba()
}

private data class Rgb(val red: Double, val green: Double, val blue: Double)

object ReaderSelectionVisualStyle {
    fun palette(appearance: ReaderAppearance): ReaderSelectionPalette {
        val customBackground = parseHex(appearance.customBackgroundHex)
        val background = customBackground ?: defaultBackground(appearance.theme)
        val darkBackground = background.relativeLuminance() < 0.38

        val chosen = parseHex(appearance.customSelectionColorHex)
        var accent = chosen ?: if (customBackground == null) {
            defaultAccent(appearance.theme)
        } else if (darkBackground) {
            Rgb(0.34, 0.74, 1.0)
        } else {
            Rgb(0.19, 0.49, 0.76)
        }

        val fillAlpha = if (darkBackground) 0.42 else 0.30
        val readablePole = if (darkBackground) Rgb(1.0, 1.0, 1.0) else Rgb(0.0, 0.0, 0.0)

        // Judge the colour the reader actually sees once the translucent fill is
        // composited over the page: an opaque accent can pass on its own and
        // still vanish at 30% alpha.
        repeat(8) {
            val visible = accent.composited(over = background, alpha = fillAlpha)
            if (contrastRatio(visible, background) >= 1.28) return@repeat
            accent = accent.mixed(readablePole, 0.18)
        }

        val stroke = accent
            .mixed(readablePole, if (darkBackground) 0.26 else 0.24)
        return ReaderSelectionPalette(
            fillArgb = accent.toArgb(fillAlpha),
            strokeArgb = stroke.toArgb(if (darkBackground) 0.82 else 0.64)
        )
    }

    private fun defaultAccent(theme: ReaderThemeOption): Rgb = when (theme) {
        ReaderThemeOption.LIGHT -> Rgb(0.25, 0.57, 0.82)
        ReaderThemeOption.SEPIA -> Rgb(0.79, 0.47, 0.13)
        ReaderThemeOption.COOL_GRAY -> Rgb(0.12, 0.52, 0.62)
        ReaderThemeOption.DARK -> Rgb(0.32, 0.70, 0.98)
    }

    private fun defaultBackground(theme: ReaderThemeOption): Rgb = when (theme) {
        ReaderThemeOption.LIGHT -> Rgb(1.0, 1.0, 1.0)
        ReaderThemeOption.SEPIA -> Rgb(0.98, 0.95, 0.88)
        ReaderThemeOption.COOL_GRAY -> Rgb(0.93, 0.96, 0.98)
        ReaderThemeOption.DARK -> Rgb(0.08, 0.10, 0.13)
    }

    private fun parseHex(value: String): Rgb? {
        val digits = value.trim().removePrefix("#")
        if (digits.length != 6 || digits.any { it !in "0123456789abcdefABCDEF" }) return null
        val raw = digits.toLongOrNull(16) ?: return null
        return Rgb(
            ((raw shr 16) and 0xFF) / 255.0,
            ((raw shr 8) and 0xFF) / 255.0,
            (raw and 0xFF) / 255.0
        )
    }

    private fun contrastRatio(first: Rgb, second: Rgb): Double {
        val a = first.relativeLuminance()
        val b = second.relativeLuminance()
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }
}

private fun Rgb.relativeLuminance(): Double {
    fun channel(value: Double): Double =
        if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    return 0.2126 * channel(red) + 0.7152 * channel(green) + 0.0722 * channel(blue)
}

private fun Rgb.mixed(other: Rgb, amount: Double): Rgb {
    val ratio = amount.coerceIn(0.0, 1.0)
    return Rgb(
        red + (other.red - red) * ratio,
        green + (other.green - green) * ratio,
        blue + (other.blue - blue) * ratio
    )
}

private fun Rgb.composited(over: Rgb, alpha: Double): Rgb = Rgb(
    red * alpha + over.red * (1 - alpha),
    green * alpha + over.green * (1 - alpha),
    blue * alpha + over.blue * (1 - alpha)
)

private fun Rgb.toArgb(alpha: Double): Long {
    fun channel(value: Double): Long = (value.coerceIn(0.0, 1.0) * 255).toLong()
    return (channel(alpha) shl 24) or
        (channel(red) shl 16) or
        (channel(green) shl 8) or
        channel(blue)
}

private fun Long.toCssRgba(): String {
    val alpha = ((this shr 24) and 0xFF) / 255.0
    val red = (this shr 16) and 0xFF
    val green = (this shr 8) and 0xFF
    val blue = this and 0xFF
    val rounded = (alpha * 1000).toInt() / 1000.0
    return "rgba($red, $green, $blue, ${if (abs(rounded - 1.0) < 1e-9) "1" else rounded.toString()})"
}
