@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.jerreader.android.reader

import com.jerreader.shared.library.ReaderAppearance
import com.jerreader.shared.library.ReaderThemeOption
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.readium.r2.navigator.preferences.Theme

/**
 * 2.0.1: a custom page colour brings its own ink. The text used to keep the
 * selected theme's colour, so 自定义背景颜色 over the night theme gave
 * near-white text on a near-white page.
 */
class ReaderPreferencesMapperTest {
    @Test
    fun aLightCustomPageOverTheNightThemeGetsDarkInk() {
        val preferences = ReaderAppearance(
            theme = ReaderThemeOption.DARK,
            customBackgroundHex = "#EEF3F8"
        ).toEpubPreferences()

        assertEquals(0xFFEEF3F8.toInt(), preferences.backgroundColor?.int)
        assertEquals(0xFF17212B.toInt(), preferences.textColor?.int)
        assertEquals(Theme.LIGHT, preferences.theme)
    }

    @Test
    fun aDarkCustomPageOverALightThemeGetsLightInk() {
        for (theme in listOf(ReaderThemeOption.LIGHT, ReaderThemeOption.SEPIA, ReaderThemeOption.COOL_GRAY)) {
            val preferences = ReaderAppearance(
                theme = theme,
                customBackgroundHex = "#101820"
            ).toEpubPreferences()

            assertEquals(0xFF101820.toInt(), preferences.backgroundColor?.int)
            assertEquals(0xFFF4F7FA.toInt(), preferences.textColor?.int)
            assertEquals(Theme.DARK, preferences.theme)
        }
    }

    @Test
    fun withoutACustomPageTheThemeKeepsItsOwnInk() {
        val expected = mapOf(
            ReaderThemeOption.LIGHT to Theme.LIGHT,
            ReaderThemeOption.SEPIA to Theme.SEPIA,
            ReaderThemeOption.COOL_GRAY to Theme.LIGHT,
            ReaderThemeOption.DARK to Theme.DARK
        )
        for ((theme, readium) in expected) {
            val preferences = ReaderAppearance(theme = theme).toEpubPreferences()
            assertNull(preferences.textColor)
            assertEquals(readium, preferences.theme)
        }
        // An unusable colour is no colour.
        val broken = ReaderAppearance(
            theme = ReaderThemeOption.DARK,
            customBackgroundHex = "#12"
        ).toEpubPreferences()
        assertNull(broken.textColor)
        assertNull(broken.backgroundColor)
        assertEquals(Theme.DARK, broken.theme)
    }

    // The Readium preferences cannot name the paper under a custom colour,
    // so reopening the book used to read every such book back as 浅色 (and
    // 冷灰 was lost the same way).
    @Test
    fun theSelectedPaperSurvivesACustomPageColour() {
        for (theme in ReaderThemeOption.entries) {
            val stored = ReaderAppearance(theme = theme, customBackgroundHex = "#EEF3F8")
            val decoded = decodeStoredReaderPreferences(
                encodeStoredReaderPreferences(stored),
                ReaderAppearance()
            ).appearance

            assertEquals(theme, decoded.theme)
            assertEquals("#EEF3F8", decoded.customBackgroundHex)
        }
    }

    @Test
    fun preferencesSavedBeforeThePaperWasNamedStillRead() {
        val saved = JSONObject(
            encodeStoredReaderPreferences(ReaderAppearance(theme = ReaderThemeOption.SEPIA))
        )
        saved.remove("theme")
        val decoded = decodeStoredReaderPreferences(saved.toString(), ReaderAppearance()).appearance

        assertEquals(ReaderThemeOption.SEPIA, decoded.theme)
        assertEquals("", decoded.customBackgroundHex)
    }

    @Test
    fun switchingTheCustomPageOffClearsItsInk() {
        val custom = ReaderAppearance(
            theme = ReaderThemeOption.DARK,
            customBackgroundHex = "#EEF3F8"
        )
        val base = custom.toEpubPreferences()
        val restored = custom.copy(customBackgroundHex = "").toEpubPreferences(base)

        assertNull(restored.textColor)
        assertNull(restored.backgroundColor)
        assertEquals(Theme.DARK, restored.theme)
    }
}
