package com.jerreader.shared.ui

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 界面主题 (2.0.1): the eight accents and the appearance mode. The accents are
 * stored by name and iOS keeps the same eight in the same order, so the order
 * is part of the contract; each has to stay readable on the paper it is drawn
 * on and under the text drawn on it.
 */
class JerreaderThemeTests {

    @Test
    fun accentsKeepTheirStoredNamesAndOrder() {
        assertEquals(
            listOf("OCEAN", "FOREST", "WISTERIA", "AMBER", "BERRY", "TEAL", "TERRACOTTA", "GRAPHITE"),
            JerreaderAccent.entries.map { it.name }
        )
        assertEquals(
            listOf("海蓝", "森林", "紫藤", "琥珀", "莓红", "松石", "赤陶", "石墨"),
            JerreaderAccent.entries.map { it.title }
        )
        assertEquals(JerreaderAccent.entries.size, JerreaderAccent.entries.map { it.hex(dark = false) }.toSet().size)
        assertEquals(JerreaderAccent.entries.size, JerreaderAccent.entries.map { it.hex(dark = true) }.toSet().size)
    }

    @Test
    fun everyAccentReadsOnItsPaperAndUnderItsText() {
        JerreaderAccent.entries.forEach { accent ->
            val light = accent.hex(dark = false)
            val dark = accent.hex(dark = true)
            // Accent text and glyphs on the canvas and on a card.
            assertTrue(contrast(light, LIGHT_CANVAS) >= 4.5, "${accent.name} on the light canvas")
            assertTrue(contrast(light, LIGHT_PAPER) >= 4.5, "${accent.name} on light paper")
            assertTrue(contrast(dark, DARK_CANVAS) >= 4.5, "${accent.name} on the dark canvas")
            assertTrue(contrast(dark, DARK_PAPER) >= 4.5, "${accent.name} on dark paper")
            // The label of a filled button: white in light mode, ink in dark.
            assertTrue(contrast(0xFFFFFF, light) >= 4.5, "white on ${accent.name}")
            assertTrue(contrast(0x0B0F14, dark) >= 4.5, "ink on ${accent.name} in dark mode")
        }
    }

    @Test
    fun appearanceModeFollowsTheSystemOnlyWhenAskedTo() {
        assertTrue(JerreaderAppearanceMode.SYSTEM.isDark(systemDark = true))
        assertFalse(JerreaderAppearanceMode.SYSTEM.isDark(systemDark = false))
        assertFalse(JerreaderAppearanceMode.LIGHT.isDark(systemDark = true))
        assertFalse(JerreaderAppearanceMode.LIGHT.isDark(systemDark = false))
        assertTrue(JerreaderAppearanceMode.DARK.isDark(systemDark = true))
        assertTrue(JerreaderAppearanceMode.DARK.isDark(systemDark = false))
    }

    @Test
    fun appearanceModeStorageRoundTripsAndFallsBackToTheSystem() {
        JerreaderAppearanceMode.entries.forEach { mode ->
            assertEquals(mode, JerreaderAppearanceMode.fromStorage(mode.storageValue))
        }
        // The raw values iOS stores under `appearance.mode`.
        assertEquals(
            listOf("system", "light", "dark"),
            JerreaderAppearanceMode.entries.map { it.storageValue }
        )
        assertEquals(JerreaderAppearanceMode.SYSTEM, JerreaderAppearanceMode.fromStorage(null))
        assertEquals(JerreaderAppearanceMode.SYSTEM, JerreaderAppearanceMode.fromStorage(""))
        assertEquals(JerreaderAppearanceMode.SYSTEM, JerreaderAppearanceMode.fromStorage("sepia"))
    }

    private fun contrast(first: Long, second: Long): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    /** WCAG relative luminance of `0xRRGGBB`. */
    private fun luminance(hex: Long): Double {
        fun channel(shift: Int): Double {
            val value = ((hex shr shift) and 0xFF) / 255.0
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private companion object {
        const val LIGHT_CANVAS = 0xF4F1EAL
        const val LIGHT_PAPER = 0xFFFDF8L
        const val DARK_CANVAS = 0x101215L
        const val DARK_PAPER = 0x181B20L
    }
}
