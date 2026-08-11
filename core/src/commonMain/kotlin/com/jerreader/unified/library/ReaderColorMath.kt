package com.jerreader.unified.library

import kotlin.math.abs
import kotlin.math.round

/**
 * A colour as the reader thinks about it while picking one: which colour, how
 * strong, how bright.
 *
 * [hue] is degrees in `0 until 360`; [saturation] and [value] are `0.0..1.0`.
 */
data class ReaderHsv(
    val hue: Double,
    val saturation: Double,
    val value: Double
)

/**
 * Conversion between `#RRGGBB` and [ReaderHsv].
 *
 * Android offered the custom background and selection colours as two
 * `#RRGGBB` text fields, which is a colour picker only in the sense that a
 * colour can be entered into it: iOS has had the system colour wheel there
 * since the beginning, and 「安卓自定义背景色那块不能做到跟 iOS 一样吗」 is the
 * predictable result. Compose Multiplatform has no colour picker of its own,
 * so the reader's picker is built from sliders — and the arithmetic those
 * sliders run on is a decision, not a drawing, so it lives here where it is
 * tested once for both platforms.
 *
 * Round-tripping is exact for every one of the 16.7 million `#RRGGBB` values:
 * a picker that shifted the colour by a shade each time the sheet was reopened
 * would be worse than no picker.
 */
object ReaderColorMath {

    /** The HSV form of a `#RRGGBB` colour, or `null` when it is not one. */
    fun hsvOf(hex: String): ReaderHsv? {
        val argb = ReaderPageBackground.parseRgbHex(hex) ?: return null
        val red = ((argb shr 16) and 0xFF) / 255.0
        val green = ((argb shr 8) and 0xFF) / 255.0
        val blue = (argb and 0xFF) / 255.0

        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val delta = max - min

        val hue = when {
            delta == 0.0 -> 0.0
            max == red -> 60.0 * (((green - blue) / delta) % 6.0)
            max == green -> 60.0 * (((blue - red) / delta) + 2.0)
            else -> 60.0 * (((red - green) / delta) + 4.0)
        }
        return ReaderHsv(
            hue = if (hue < 0) hue + 360.0 else hue,
            saturation = if (max == 0.0) 0.0 else delta / max,
            value = max
        )
    }

    /** `#RRGGBB` for an HSV triple, clamped and wrapped into range. */
    fun hexOf(hsv: ReaderHsv): String = hexOf(hsv.hue, hsv.saturation, hsv.value)

    fun hexOf(hue: Double, saturation: Double, value: Double): String {
        val h = wrappedHue(hue)
        val s = saturation.coerceIn(0.0, 1.0)
        val v = value.coerceIn(0.0, 1.0)

        val chroma = v * s
        val x = chroma * (1 - abs((h / 60.0) % 2.0 - 1))
        val m = v - chroma
        val (red, green, blue) = when ((h / 60.0).toInt()) {
            0 -> Triple(chroma, x, 0.0)
            1 -> Triple(x, chroma, 0.0)
            2 -> Triple(0.0, chroma, x)
            3 -> Triple(0.0, x, chroma)
            4 -> Triple(x, 0.0, chroma)
            else -> Triple(chroma, 0.0, x)
        }
        return ReaderPageBackground.toRgbHex(
            (channel(red + m) shl 16) or (channel(green + m) shl 8) or channel(blue + m)
        )
    }

    /** [hue] wrapped into `0.0 until 360.0`, including from negative values. */
    fun wrappedHue(hue: Double): Double {
        if (!hue.isFinite()) return 0.0
        val wrapped = hue % 360.0
        return if (wrapped < 0) wrapped + 360.0 else wrapped
    }

    private fun channel(value: Double): Long =
        round(value.coerceIn(0.0, 1.0) * 255).toLong()
}
