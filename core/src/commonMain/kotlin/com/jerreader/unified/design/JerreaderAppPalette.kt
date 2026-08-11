package com.jerreader.unified.design

/**
 * The five app colour schemes, and the surface tones each of them produces.
 *
 * Both platforms used to carry their own copy of the accent table and their own
 * copy of the neutral surface tones. That is the same split that let one
 * platform's selection fill be fixed while the other kept the bug, so the
 * numbers live here once and Compose and SwiftUI both read them.
 *
 * From 1.3.3 the surfaces are tinted by the chosen accent again. The 1.1.0
 * decision to fix them at a cool blue-grey was a reaction to the *first*
 * attempt, which derived the page tones straight from the accent and left amber
 * and berry looking warm and washed out. The tint is now a small wash of the
 * accent over that same neutral base — enough that 琥珀 and 莓红 read as
 * different apps rather than as the same grey app with different buttons,
 * light enough that covers and reader content still sit on paper.
 */
enum class JerreaderAccentPalette(
    val id: String,
    val title: String,
    val detail: String
) {
    OCEAN("ocean", "海蓝", "沉静的蓝灰色系"),
    FOREST("forest", "森林", "柔和的青绿色系"),
    WISTERIA("wisteria", "紫藤", "克制的紫罗兰色系"),
    AMBER("amber", "琥珀", "温暖的黄金与棕色系"),
    BERRY("berry", "莓红", "清晰的玫红色系");

    companion object {
        fun fromId(id: String): JerreaderAccentPalette =
            entries.firstOrNull { it.id == id } ?: OCEAN
    }
}

/**
 * Whether the app draws its light or dark surfaces, independently of the phone.
 *
 * A colour scheme was previously only ever the system's: the palette above
 * resolves against `dark`, and both apps passed whatever the OS reported. But a
 * reader is read in bed with the phone left on light, and read on a bright train
 * with the phone left on dark, and the 界面主题 page is where a user looks for
 * that switch — 「我希望可以在 APP 的『界面主题』那块单独调节，是白天、黑夜还是
 * 跟随系统」. The choice sits beside the accent because it is the other half of
 * the same question, and it is stored by `id` so either app restores what the
 * other saved.
 */
enum class JerreaderAppearanceMode(
    val id: String,
    val title: String,
    val detail: String
) {
    SYSTEM("system", "跟随系统", "随手机的深色模式一起切换"),
    LIGHT("light", "白天", "始终使用浅色界面"),
    DARK("dark", "黑夜", "始终使用深色界面");

    /** The only question the palette ever asks. */
    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemIsDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromId(id: String): JerreaderAppearanceMode =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/** One resolved scheme. Every channel is 0.0–1.0 so both toolkits can consume it. */
data class JerreaderAppColors(
    val accent: JerreaderRgb,
    val accentFill: JerreaderRgb,
    val canvas: JerreaderRgb,
    val paper: JerreaderRgb,
    val raisedPaper: JerreaderRgb,
    val mutedSurface: JerreaderRgb,
    val line: JerreaderRgb,
    val onAccent: JerreaderRgb,
    val secondaryText: JerreaderRgb
)

data class JerreaderRgb(val red: Double, val green: Double, val blue: Double)

object JerreaderAppPalette {
    /**
     * How much accent is washed into each surface.
     *
     * The canvas carries the most because it is the largest area and the one a
     * reader reads the theme off; paper carries least so cards keep reading as
     * paper laid on the canvas rather than as a slightly different wash of the
     * same colour. Dark mode uses smaller amounts: its accents are the light
     * variants, so the same fraction would visibly lift the page off black.
     */
    private const val CANVAS_TINT_LIGHT = 0.10
    private const val CANVAS_TINT_DARK = 0.07
    private const val PAPER_TINT_LIGHT = 0.045
    private const val PAPER_TINT_DARK = 0.035
    private const val RAISED_TINT_LIGHT = 0.030
    private const val RAISED_TINT_DARK = 0.055
    private const val MUTED_TINT_LIGHT = 0.130
    private const val MUTED_TINT_DARK = 0.055

    fun accentRgb(palette: JerreaderAccentPalette, dark: Boolean): JerreaderRgb =
        when (palette) {
            JerreaderAccentPalette.OCEAN ->
                if (dark) JerreaderRgb(0.46, 0.72, 0.88) else JerreaderRgb(0.18, 0.41, 0.60)
            JerreaderAccentPalette.FOREST ->
                if (dark) JerreaderRgb(0.42, 0.76, 0.62) else JerreaderRgb(0.16, 0.43, 0.34)
            JerreaderAccentPalette.WISTERIA ->
                if (dark) JerreaderRgb(0.68, 0.61, 0.91) else JerreaderRgb(0.39, 0.31, 0.61)
            JerreaderAccentPalette.AMBER ->
                if (dark) JerreaderRgb(0.91, 0.68, 0.34) else JerreaderRgb(0.55, 0.35, 0.10)
            JerreaderAccentPalette.BERRY ->
                if (dark) JerreaderRgb(0.90, 0.52, 0.68) else JerreaderRgb(0.58, 0.22, 0.38)
        }

    fun colors(palette: JerreaderAccentPalette, dark: Boolean): JerreaderAppColors {
        val accent = accentRgb(palette, dark)
        val canvasBase = if (dark) {
            JerreaderRgb(0.040, 0.065, 0.090)
        } else {
            JerreaderRgb(0.941, 0.961, 0.975)
        }
        val canvas = accent.washedOver(canvasBase, if (dark) CANVAS_TINT_DARK else CANVAS_TINT_LIGHT)
        return JerreaderAppColors(
            accent = accent,
            accentFill = accent.washedOver(canvas, if (dark) 0.22 else 0.12),
            canvas = canvas,
            paper = accent.washedOver(
                neutralSurface(dark, level = 0),
                if (dark) PAPER_TINT_DARK else PAPER_TINT_LIGHT
            ),
            raisedPaper = accent.washedOver(
                neutralSurface(dark, level = 1),
                if (dark) RAISED_TINT_DARK else RAISED_TINT_LIGHT
            ),
            mutedSurface = accent.washedOver(
                neutralSurface(dark, level = -1),
                if (dark) MUTED_TINT_DARK else MUTED_TINT_LIGHT
            ),
            line = accent.washedOver(canvas, if (dark) 0.28 else 0.18),
            onAccent = if (dark) JerreaderRgb(0.025, 0.04, 0.05) else JerreaderRgb(1.0, 1.0, 1.0),
            secondaryText = if (dark) {
                JerreaderRgb(0.72, 0.75, 0.79)
            } else {
                JerreaderRgb(0.36, 0.40, 0.45)
            }
        )
    }

    private fun neutralSurface(dark: Boolean, level: Int): JerreaderRgb = if (dark) {
        when {
            level > 0 -> JerreaderRgb(0.095, 0.145, 0.185)
            level < 0 -> JerreaderRgb(0.028, 0.055, 0.080)
            else -> JerreaderRgb(0.060, 0.100, 0.135)
        }
    } else {
        when {
            level > 0 -> JerreaderRgb(0.985, 0.993, 0.998)
            level < 0 -> JerreaderRgb(0.885, 0.925, 0.953)
            else -> JerreaderRgb(0.965, 0.978, 0.989)
        }
    }
}

/** This colour at [amount] alpha, composited over [background]. */
private fun JerreaderRgb.washedOver(background: JerreaderRgb, amount: Double): JerreaderRgb {
    val alpha = amount.coerceIn(0.0, 1.0)
    return JerreaderRgb(
        red * alpha + background.red * (1 - alpha),
        green * alpha + background.green * (1 - alpha),
        blue * alpha + background.blue * (1 - alpha)
    )
}
