package com.jerreader.android.reader

import com.jerreader.shared.library.ReaderAppearance
import com.jerreader.shared.library.ReaderFontOption
import com.jerreader.shared.library.ReaderTextOrientation
import com.jerreader.shared.library.ReaderThemeOption
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.shared.ExperimentalReadiumApi

@OptIn(ExperimentalReadiumApi::class)
fun ReaderAppearance.toEpubPreferences(base: EpubPreferences = EpubPreferences()): EpubPreferences =
    base.copy(
        backgroundColor = appearanceBackgroundColor(),
        fontSize = fontScale,
        theme = theme.toReadiumTheme(),
        scroll = scroll,
        fontFamily = when (font) {
            ReaderFontOption.PUBLICATION -> null
            ReaderFontOption.SERIF -> FontFamily.SERIF
            ReaderFontOption.SANS_SERIF -> FontFamily.SANS_SERIF
            ReaderFontOption.OPEN_DYSLEXIC -> FontFamily.OPEN_DYSLEXIC
        },
        lineHeight = lineHeight,
        paragraphSpacing = paragraphSpacing,
        pageMargins = pageMargins,
        publisherStyles = false,
        readingProgression = when (orientation) {
            ReaderTextOrientation.HORIZONTAL -> ReadingProgression.LTR
            ReaderTextOrientation.PUBLICATION,
            ReaderTextOrientation.VERTICAL -> null
        },
        verticalText = when (orientation) {
            ReaderTextOrientation.PUBLICATION -> null
            ReaderTextOrientation.HORIZONTAL -> false
            ReaderTextOrientation.VERTICAL -> true
        }
    )

@OptIn(ExperimentalReadiumApi::class)
fun EpubPreferences.toReaderAppearance(): ReaderAppearance = ReaderAppearance(
    fontScale = fontSize ?: 1.0,
    theme = when {
        backgroundColor?.int == COOL_GRAY_ARGB -> ReaderThemeOption.COOL_GRAY
        theme == Theme.DARK -> ReaderThemeOption.DARK
        theme == Theme.SEPIA -> ReaderThemeOption.SEPIA
        else -> ReaderThemeOption.LIGHT
    },
    scroll = scroll ?: false,
    font = when (fontFamily) {
        FontFamily.SERIF -> ReaderFontOption.SERIF
        FontFamily.SANS_SERIF -> ReaderFontOption.SANS_SERIF
        FontFamily.OPEN_DYSLEXIC -> ReaderFontOption.OPEN_DYSLEXIC
        else -> ReaderFontOption.PUBLICATION
    },
    lineHeight = lineHeight ?: 1.4,
    paragraphSpacing = paragraphSpacing ?: 0.0,
    pageMargins = pageMargins ?: 1.0,
    orientation = when (verticalText) {
        true -> ReaderTextOrientation.VERTICAL
        false -> ReaderTextOrientation.HORIZONTAL
        null -> ReaderTextOrientation.PUBLICATION
    },
    customBackgroundHex = backgroundColor?.int
        ?.takeUnless { it == COOL_GRAY_ARGB }
        ?.toRgbHex()
        .orEmpty()
)

private fun ReaderThemeOption.toReadiumTheme(): Theme = when (this) {
    ReaderThemeOption.LIGHT -> Theme.LIGHT
    ReaderThemeOption.SEPIA -> Theme.SEPIA
    ReaderThemeOption.COOL_GRAY -> Theme.LIGHT
    ReaderThemeOption.DARK -> Theme.DARK
}

private fun ReaderAppearance.appearanceBackgroundColor(): ReadiumColor? {
    parseRgbHex(customBackgroundHex)?.let { return ReadiumColor(it) }
    return if (theme == ReaderThemeOption.COOL_GRAY) ReadiumColor(COOL_GRAY_ARGB) else null
}

private fun parseRgbHex(value: String): Int? {
    val digits = value.trim().removePrefix("#")
    if (digits.length != 6 || digits.any { it !in "0123456789abcdefABCDEF" }) return null
    return (0xFF000000L or (digits.toLongOrNull(16) ?: return null)).toInt()
}

private fun Int.toRgbHex(): String = "#%06X".format(this and 0x00FFFFFF)

private val COOL_GRAY_ARGB: Int = 0xFFEEF3F6.toInt()
