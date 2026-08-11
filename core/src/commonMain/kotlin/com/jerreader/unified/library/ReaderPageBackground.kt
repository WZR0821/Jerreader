package com.jerreader.unified.library

/**
 * The colour of the page itself, as 0xAARRGGBB.
 *
 * This existed three times: iOS picked it in `EPUBReaderView.readerBackground`,
 * Android left it to Readium's own theme except for 冷灰, and `core` kept a
 * fourth private copy for the selection-contrast maths. The three sets of
 * numbers had already drifted apart — sepia was `#FAF5E8` on iOS and `#FAF2E0`
 * inside the contrast solver, so the highlight colour was being solved against a
 * page nobody was looking at.
 *
 * It matters beyond tidiness now: the Android reader paints the area *around*
 * the Readium page with this colour so the reader fills the screen. A band that
 * is one shade off the page is a visible seam down the top and bottom of every
 * book.
 */
object ReaderPageBackground {

    const val LIGHT_ARGB: Long = 0xFFFFFFFF
    const val SEPIA_ARGB: Long = 0xFFFAF4E8
    const val COOL_GRAY_ARGB: Long = 0xFFEEF3F6
    const val DARK_ARGB: Long = 0xFF000000

    /** The page colour for a theme, ignoring any custom background. */
    fun themeArgb(theme: ReaderThemeOption): Long = when (theme) {
        ReaderThemeOption.LIGHT -> LIGHT_ARGB
        ReaderThemeOption.SEPIA -> SEPIA_ARGB
        ReaderThemeOption.COOL_GRAY -> COOL_GRAY_ARGB
        ReaderThemeOption.DARK -> DARK_ARGB
    }

    /** The page colour actually in force: a valid custom background wins. */
    fun argb(appearance: ReaderAppearance): Long =
        parseRgbHex(appearance.customBackgroundHex) ?: themeArgb(appearance.theme)

    /**
     * The theme a page colour came from, or `null` if it is a custom colour.
     *
     * Android persists reader preferences through Readium's own serializer, so
     * a stored `backgroundColor` has to be read back as either "this theme" or
     * "the reader picked this themselves".
     */
    fun themeFor(argb: Long): ReaderThemeOption? = when (argb and 0xFFFFFFFFL) {
        LIGHT_ARGB -> ReaderThemeOption.LIGHT
        SEPIA_ARGB -> ReaderThemeOption.SEPIA
        COOL_GRAY_ARGB -> ReaderThemeOption.COOL_GRAY
        DARK_ARGB -> ReaderThemeOption.DARK
        else -> null
    }

    /** `#RRGGBB` for a 0xAARRGGBB value, the form both platforms store. */
    fun toRgbHex(argb: Long): String {
        val digits = (argb and 0xFFFFFFL).toString(16).uppercase()
        return "#" + "0".repeat(6 - digits.length) + digits
    }

    /** `#RRGGBB` or `RRGGBB` to 0xFFRRGGBB, or `null` when it is neither. */
    fun parseRgbHex(value: String): Long? {
        val digits = value.trim().removePrefix("#")
        if (digits.length != 6 || digits.any { it !in "0123456789abcdefABCDEF" }) return null
        val raw = digits.toLongOrNull(16) ?: return null
        return 0xFF000000L or raw
    }
}
