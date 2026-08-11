package com.jerreader.unified.reader.selection

import com.jerreader.unified.library.ReaderAppearance
import com.jerreader.unified.library.ReaderPageBackground
import com.jerreader.unified.library.ReaderThemeOption
import kotlin.math.abs
import kotlin.math.pow

/**
 * The three colours a selection is painted with, as 0xAARRGGBB.
 *
 * `spokenFill` used to exist only on iOS, which meant Android's read-aloud
 * highlight had nothing to paint the currently spoken span with. It is part of
 * the shared palette now so both readers dim and brighten the same way.
 */
data class ReaderSelectionPalette(
    val fillArgb: Long,
    val strokeArgb: Long,
    val spokenFillArgb: Long
) {
    val fillCss: String get() = fillArgb.toCssRgba()
    val strokeCss: String get() = strokeArgb.toCssRgba()
    val spokenFillCss: String get() = spokenFillArgb.toCssRgba()
}

/**
 * Stroke and corner geometry for the painted selection.
 *
 * iOS drew a 0.75pt outline with a height-proportional corner and a half-point
 * outset; Android drew a 1px outline with a flat 4px corner and no outset, so
 * the same sentence looked visibly heavier there. The iOS numbers were the ones
 * tuned against real text, so they are the shared numbers.
 */
object ReaderSelectionHighlightMetrics {
    const val STROKE_WIDTH = 0.75
    const val OUTSET = 0.5
    const val MAXIMUM_CORNER_RADIUS = 3.0
    const val CORNER_RADIUS_RATIO = 0.16

    /** Corner radius for a tile of the given cross-axis extent (line height). */
    fun cornerRadius(tileHeight: Double): Double =
        minOf(MAXIMUM_CORNER_RADIUS, maxOf(tileHeight, 0.0) * CORNER_RADIUS_RATIO)

    /**
     * The same rule as [cornerRadius], as a JavaScript expression.
     *
     * Both readers paint their tap-to-translate marker with DOM elements rather
     * than with a canvas, so the rounding has to happen in the page. Writing it
     * out by hand in each script is how iOS ended up with a flat 3px corner on
     * every line height while Android scaled its own — the same selection,
     * visibly different. [heightExpression] is spliced in as-is, so pass a plain
     * JS expression that evaluates to the tile's cross-axis extent.
     */
    fun cssCornerRadiusExpression(heightExpression: String): String =
        "Math.min($MAXIMUM_CORNER_RADIUS, ($heightExpression) * $CORNER_RADIUS_RATIO)"
}

private data class Rgb(val red: Double, val green: Double, val blue: Double)

/**
 * Picks the selection colours for the page the reader is currently on.
 *
 * The fill is the theme accent at low alpha and the outline is the same hue
 * pushed towards black (or white on a dark page), so a marked sentence reads as
 * one shape rather than a flat wash. A custom accent is nudged until the colour
 * the reader actually sees *after compositing* clears a minimum contrast against
 * the page — checking the opaque accent alone accepts colours that disappear at
 * 30% alpha.
 */
object ReaderSelectionVisualStyle {

    /**
     * How much lighter or darker than the page the composited fill has to be
     * before it reads as a highlight at all.
     *
     * The old floor of 1.28 was cleared by every theme's own accent on its own
     * background with no adjustment at all — which sounds fine until you look
     * at the result: teal at 30% over the cool-grey page, or amber over sepia,
     * differs from the paper by so little that the selection colour reads as
     * simply not working.
     */
    private const val MINIMUM_VISIBLE_CONTRAST = 1.55
    private const val DARK_BACKGROUND_LUMINANCE = 0.38
    private const val ALPHA_ATTEMPTS = 12
    private const val ALPHA_STEP = 0.05

    /**
     * Bisection steps for the hue mix. 12 halvings resolve the mix ratio to
     * better than 1/4000, well under one 8-bit channel step, so the result is
     * the same colour a closed-form solution would give.
     */
    private const val MIX_BISECTIONS = 12

    /**
     * Head-room the hue mix aims for above the floor.
     *
     * Bisection converges on the exact threshold, and the colour is then
     * rounded to 8 bits per channel — which can put the delivered colour a
     * thousandth back under it. The margin only applies to the mix, so it
     * cannot disturb an accent that already clears on its own.
     */
    private const val MIX_CONTRAST_MARGIN = 0.02

    private val WHITE = Rgb(1.0, 1.0, 1.0)
    private val BLACK = Rgb(0.0, 0.0, 0.0)

    fun palette(appearance: ReaderAppearance): ReaderSelectionPalette {
        val customBackground = parseHex(appearance.customBackgroundHex)
        val background = customBackground ?: defaultBackground(appearance.theme)
        val darkBackground = background.relativeLuminance() < DARK_BACKGROUND_LUMINANCE

        val chosen = parseHex(appearance.customSelectionColorHex)
        var accent = chosen ?: when {
            customBackground == null -> defaultAccent(appearance.theme)
            darkBackground -> Rgb(0.34, 0.74, 1.0)
            else -> Rgb(0.19, 0.49, 0.76)
        }

        var fillAlpha = if (darkBackground) 0.42 else 0.30
        val maximumAlpha = if (darkBackground) 0.62 else 0.55

        fun contrastOf(candidate: Rgb, alpha: Double): Double =
            contrastRatio(candidate.composited(over = background, alpha = alpha), background)

        fun clears(candidate: Rgb, alpha: Double): Boolean =
            contrastOf(candidate, alpha) >= MINIMUM_VISIBLE_CONTRAST

        // Reach for opacity before reaching for hue. Mixing towards black or
        // white is what makes a deliberately chosen custom colour arrive as
        // something else — a vivid red showing up as muddy brick. More alpha
        // makes the same colour more present without changing what it is, so
        // the hue is only touched once opacity has run out.
        var attempts = 0
        while (attempts < ALPHA_ATTEMPTS && fillAlpha < maximumAlpha && !clears(accent, fillAlpha)) {
            fillAlpha = (fillAlpha + ALPHA_STEP).coerceAtMost(maximumAlpha)
            attempts += 1
        }

        if (!clears(accent, fillAlpha)) {
            // Which pole actually helps is a property of *this* background, not
            // of whether it counts as dark. `DARK_BACKGROUND_LUMINANCE` is a cut
            // at 0.38, and a saturated mid-tone page — coral, azure — falls just
            // under it while still being far too bright for white to read
            // against. Picking the pole by that cut then mixed the accent
            // towards white until nothing of the user's colour was left, and on
            // 4267 of the 531441 background/accent pairs it *still* never
            // reached the floor: the selection came out invisible. That is the
            // "custom selection colour stops working when a custom background is
            // set" report. Measure both poles and take the one that gains more.
            val towardsWhite = contrastRatio(
                WHITE.composited(over = background, alpha = fillAlpha),
                background
            )
            val towardsBlack = contrastRatio(
                BLACK.composited(over = background, alpha = fillAlpha),
                background
            )
            val pole = when {
                towardsWhite > towardsBlack -> WHITE
                towardsBlack > towardsWhite -> BLACK
                else -> if (darkBackground) WHITE else BLACK
            }

            accent = if (maxOf(towardsWhite, towardsBlack) < MINIMUM_VISIBLE_CONTRAST) {
                // Nothing on this page clears the floor. Go as far as the page
                // allows rather than stopping part-way for no benefit.
                pole
            } else {
                // Then take the *smallest* step towards it that clears. The old
                // fixed 18% nudges overshot by design — eight of them travel 80%
                // of the way to the pole — so a colour that needed a touch of
                // darkening lost its hue entirely.
                val target = MINIMUM_VISIBLE_CONTRAST + MIX_CONTRAST_MARGIN
                var low = 0.0
                var high = 1.0
                repeat(MIX_BISECTIONS) {
                    val mid = (low + high) / 2
                    if (contrastOf(accent.mixed(pole, mid), fillAlpha) >= target) {
                        high = mid
                    } else {
                        low = mid
                    }
                }
                accent.mixed(pole, high)
            }
        }

        val strokePole = if (darkBackground) WHITE else BLACK
        val stroke = accent.mixed(strokePole, if (darkBackground) 0.26 else 0.24)
        return ReaderSelectionPalette(
            fillArgb = accent.toArgb(fillAlpha),
            strokeArgb = stroke.toArgb(if (darkBackground) 0.82 else 0.64),
            spokenFillArgb = accent.toArgb((fillAlpha + 0.28).coerceAtMost(0.85))
        )
    }

    private fun defaultAccent(theme: ReaderThemeOption): Rgb = when (theme) {
        ReaderThemeOption.LIGHT -> Rgb(0.25, 0.57, 0.82)
        ReaderThemeOption.SEPIA -> Rgb(0.79, 0.47, 0.13)
        ReaderThemeOption.COOL_GRAY -> Rgb(0.12, 0.52, 0.62)
        ReaderThemeOption.DARK -> Rgb(0.32, 0.70, 0.98)
    }

    // Solve the highlight against the page the reader is actually looking at.
    // This used to be a private fourth copy of the theme colours and had drifted
    // from both hosts; see `ReaderPageBackground`.
    private fun defaultBackground(theme: ReaderThemeOption): Rgb =
        rgbOf(ReaderPageBackground.themeArgb(theme))

    private fun rgbOf(argb: Long): Rgb = Rgb(
        ((argb shr 16) and 0xFF) / 255.0,
        ((argb shr 8) and 0xFF) / 255.0,
        (argb and 0xFF) / 255.0
    )

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
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}

/**
 * sRGB relative luminance. The 0.04045 knee is the sRGB spec value that the iOS
 * reader used; Android had drifted to the older 0.03928 draft constant, which
 * moved a handful of custom accent colours across the contrast threshold and
 * produced a different selection colour for the same user settings.
 */
private fun Rgb.relativeLuminance(): Double {
    fun channel(value: Double): Double =
        if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
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
    // Round, don't truncate. `toLong()` truncates, which biases every channel
    // downwards by up to one step — 0.25 * 255 = 63.75 became 63 where the iOS
    // reader's `rounded()` gives 64. Small, but it made the two platforms paint
    // measurably different colours from identical inputs.
    fun channel(value: Double): Long =
        kotlin.math.round(value.coerceIn(0.0, 1.0) * 255).toLong()
    return (channel(alpha) shl 24) or
        (channel(red) shl 16) or
        (channel(green) shl 8) or
        channel(blue)
}

/** `rgba(...)` for the CSS the web-view highlight painter injects. */
private fun Long.toCssRgba(): String {
    val alpha = ((this shr 24) and 0xFF) / 255.0
    val red = (this shr 16) and 0xFF
    val green = (this shr 8) and 0xFF
    val blue = this and 0xFF
    val rounded = (alpha * 1000).toInt() / 1000.0
    return "rgba($red, $green, $blue, ${if (abs(rounded - 1.0) < 1e-9) "1" else rounded.toString()})"
}
