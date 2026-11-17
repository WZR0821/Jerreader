package com.jerreader.shared.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The layout choice and bar colours must read the same as iOS `ReaderInterfaceDefaults`. */
class ReaderLayoutModeTest {
    @Test
    fun storedValuesMatchIOSAndUnknownValuesFallBackToStandard() {
        assertEquals("standard", ReaderLayoutMode.STANDARD.storageValue)
        assertEquals("immersive", ReaderLayoutMode.IMMERSIVE.storageValue)
        assertEquals(ReaderLayoutMode.IMMERSIVE, ReaderLayoutMode.fromStorage("immersive"))
        assertEquals(ReaderLayoutMode.STANDARD, ReaderLayoutMode.fromStorage("standard"))
        assertEquals(ReaderLayoutMode.STANDARD, ReaderLayoutMode.fromStorage(null))
        assertEquals(ReaderLayoutMode.STANDARD, ReaderLayoutMode.fromStorage("fullscreen"))
        assertEquals(listOf("标准", "沉浸"), ReaderLayoutMode.entries.map { it.title })
    }

    @Test
    fun barOpacityIsTheRestOfTheTransparencyAndStaysInRange() {
        assertEquals(1f, ReaderInterfaceDefaults.backgroundOpacity(0f), 0.0001f)
        assertEquals(0.65f, ReaderInterfaceDefaults.backgroundOpacity(0.35f), 0.0001f)
        assertEquals(0f, ReaderInterfaceDefaults.backgroundOpacity(1.4f), 0.0001f)
        assertEquals(1f, ReaderInterfaceDefaults.backgroundOpacity(-0.2f), 0.0001f)
        assertEquals(0.85f, ReaderInterfaceDefaults.backgroundOpacity(Float.NaN), 0.0001f)
    }

    @Test
    fun barsTakeThePageColourAndItsLightness() {
        val light = ReaderAppearance(theme = ReaderThemeOption.LIGHT)
        val dark = ReaderAppearance(theme = ReaderThemeOption.DARK)
        assertEquals(0xFFFFFFFFL, ReaderInterfaceDefaults.pageColorArgb(light))
        assertFalse(ReaderInterfaceDefaults.prefersDarkChrome(light))
        assertTrue(ReaderInterfaceDefaults.prefersDarkChrome(dark))
        val customDark = light.copy(customBackgroundHex = "#1E2A36")
        assertEquals(0xFF1E2A36L, ReaderInterfaceDefaults.pageColorArgb(customDark))
        assertTrue(ReaderInterfaceDefaults.prefersDarkChrome(customDark))
        // A custom light colour wins over a dark theme.
        val customLight = dark.copy(customBackgroundHex = "#EEF3F8")
        assertEquals(0xFFEEF3F8L, ReaderInterfaceDefaults.pageColorArgb(customLight))
        assertFalse(ReaderInterfaceDefaults.prefersDarkChrome(customLight))
    }
}
