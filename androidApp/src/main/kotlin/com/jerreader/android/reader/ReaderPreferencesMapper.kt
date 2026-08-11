package com.jerreader.android.reader

import com.jerreader.unified.library.ReaderAppearance
import com.jerreader.unified.library.ReaderFontOption
import com.jerreader.unified.library.ReaderPageBackground
import com.jerreader.unified.library.ReaderTextOrientation
import com.jerreader.unified.library.ReaderThemeOption
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
    // A stored background that is one of the theme colours came from the theme,
    // not from the colour picker. Reading it back as a custom colour is what
    // made a book reopen with 「自定义」 selected and the theme row blank.
    customBackgroundHex = backgroundColor?.int
        ?.let { it.toLong() and 0xFFFFFFFFL }
        ?.takeIf { ReaderPageBackground.themeFor(it) == null }
        ?.let(ReaderPageBackground::toRgbHex)
        .orEmpty()
)

private fun ReaderThemeOption.toReadiumTheme(): Theme = when (this) {
    ReaderThemeOption.LIGHT -> Theme.LIGHT
    ReaderThemeOption.SEPIA -> Theme.SEPIA
    ReaderThemeOption.COOL_GRAY -> Theme.LIGHT
    ReaderThemeOption.DARK -> Theme.DARK
}

/**
 * The colour the page is painted with, as an Android ARGB int.
 *
 * The reader also paints the area *around* the Readium page with this, so it
 * cannot be left to Readium's own theme defaults for some themes and set by us
 * for others: an unset value and our own value only have to differ by one shade
 * for a seam to show along the top and bottom of every page.
 */
fun ReaderAppearance.pageBackgroundArgb(): Int = ReaderPageBackground.argb(this).toInt()

private fun ReaderAppearance.appearanceBackgroundColor(): ReadiumColor =
    ReadiumColor(pageBackgroundArgb())

private val COOL_GRAY_ARGB: Int = ReaderPageBackground.COOL_GRAY_ARGB.toInt()
