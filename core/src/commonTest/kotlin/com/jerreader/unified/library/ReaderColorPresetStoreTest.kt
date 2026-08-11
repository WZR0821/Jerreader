package com.jerreader.unified.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReaderColorPresetStoreTest {

    @Test
    fun `encode and decode round trip`() {
        val presets = listOf(
            ReaderColorPreset("F5F0E1-C8A45B", "旧纸", "#F5F0E1", "#C8A45B"),
            ReaderColorPreset("101820-317DC2", "深夜", "#101820", "#317DC2")
        )
        assertEquals(presets, ReaderColorPresetStore.decode(ReaderColorPresetStore.encode(presets)))
    }

    @Test
    fun `decode ignores blank and malformed records`() {
        assertEquals(emptyList(), ReaderColorPresetStore.decode(""))
        assertEquals(emptyList(), ReaderColorPresetStore.decode("   "))
        // Too few fields, an unusable colour, and a duplicate id all drop out
        // rather than producing a swatch that paints nothing.
        val raw = listOf(
            "a\t名字\t#FFFFFF",
            "b\t名字\tnope\t#000000",
            "c\t好\t#FFFFFF\t#000000",
            "c\t重复\t#111111\t#222222"
        ).joinToString("\n")
        val decoded = ReaderColorPresetStore.decode(raw)
        assertEquals(1, decoded.size)
        assertEquals("c", decoded.single().id)
    }

    @Test
    fun `decode caps the list at the maximum`() {
        val raw = (1..ReaderColorPresetStore.MAXIMUM_PRESETS + 5).joinToString("\n") { index ->
            "id$index\t名字$index\t#FFFFFF\t#000000"
        }
        assertEquals(ReaderColorPresetStore.MAXIMUM_PRESETS, ReaderColorPresetStore.decode(raw).size)
    }

    @Test
    fun `added replaces an existing id in place`() {
        val first = ReaderColorPresetStore.added(
            emptyList(), "A-B", "旧纸", "#f5f0e1", "#c8a45b"
        )
        val second = ReaderColorPresetStore.added(first, "X-Y", "深夜", "#101820", "#317DC2")
        val renamed = ReaderColorPresetStore.added(second, "A-B", "米黄", "#f5f0e1", "#c8a45b")

        assertEquals(2, renamed.size)
        assertEquals("米黄", renamed[0].name)
        // The rename must not push the entry to the end of the swatch row.
        assertEquals("A-B", renamed[0].id)
        assertEquals("#F5F0E1", renamed[0].backgroundHex)
    }

    @Test
    fun `added refuses incomplete presets`() {
        val existing = listOf(ReaderColorPreset("a", "名字", "#FFFFFF", "#000000"))
        assertEquals(existing, ReaderColorPresetStore.added(existing, "b", "", "#FFFFFF", "#000000"))
        assertEquals(existing, ReaderColorPresetStore.added(existing, "b", "名", "", "#000000"))
        assertEquals(existing, ReaderColorPresetStore.added(existing, "b", "名", "#FFFFFF", ""))
        assertEquals(existing, ReaderColorPresetStore.added(existing, " ", "名", "#FFFFFF", "#000"))
    }

    @Test
    fun `added drops the oldest past the maximum`() {
        var presets = emptyList<ReaderColorPreset>()
        for (index in 1..ReaderColorPresetStore.MAXIMUM_PRESETS + 2) {
            presets = ReaderColorPresetStore.added(presets, "id$index", "名字$index", "#FFFFFF", "#000000")
        }
        assertEquals(ReaderColorPresetStore.MAXIMUM_PRESETS, presets.size)
        assertEquals("id3", presets.first().id)
    }

    @Test
    fun `sanitizedName strips separators control characters and excess length`() {
        assertEquals("深 夜", ReaderColorPresetStore.sanitizedName("  深\t夜 "))
        assertEquals("深夜", ReaderColorPresetStore.sanitizedName("深\u0000夜"))
        assertEquals("A B", ReaderColorPresetStore.sanitizedName("A\tB"))
        assertEquals("A B", ReaderColorPresetStore.sanitizedName(" A B "))
        assertEquals(
            ReaderColorPresetStore.MAXIMUM_NAME_LENGTH,
            ReaderColorPresetStore.sanitizedName("字".repeat(50)).length
        )
    }

    @Test
    fun `normalizedHex accepts only complete six digit colours`() {
        assertEquals("#FFAA00", ReaderColorPresetStore.normalizedHex("#ffaa00"))
        assertEquals("#FFAA00", ReaderColorPresetStore.normalizedHex(" ffaa00 "))
        assertEquals("", ReaderColorPresetStore.normalizedHex("#FA0"))
        assertEquals("", ReaderColorPresetStore.normalizedHex("#FFAA0G"))
        assertEquals("", ReaderColorPresetStore.normalizedHex(""))
    }

    @Test
    fun `idFor is derived from the colours so the same pair saves once`() {
        assertEquals("F5F0E1-C8A45B", ReaderColorPresetStore.idFor("#f5f0e1", "#C8A45B"))
        assertEquals(
            ReaderColorPresetStore.idFor("#f5f0e1", "#C8A45B"),
            ReaderColorPresetStore.idFor("F5F0E1", "c8a45b")
        )
        assertEquals("", ReaderColorPresetStore.idFor("#f5f0e1", ""))
    }

    @Test
    fun `applied writes both colours and leaves the theme alone`() {
        val preset = ReaderColorPreset("a", "旧纸", "#F5F0E1", "#C8A45B")
        val appearance = ReaderAppearance().copy(
            customBackgroundHex = "#000000",
            customSelectionColorHex = "#111111"
        )
        val applied = ReaderColorPresetStore.applied(appearance, preset)
        assertEquals("#F5F0E1", applied.customBackgroundHex)
        assertEquals("#C8A45B", applied.customSelectionColorHex)
        assertEquals(appearance.theme, applied.theme)
        assertTrue(ReaderColorPresetStore.matches(preset, applied))
        assertFalse(ReaderColorPresetStore.matches(preset, appearance))
    }

    @Test
    fun `a set can be saved without either custom colour switched on`() {
        // The report was 「为什么只能保存一个」: saving needed both 自定义背景颜色
        // and 自定义选区颜色 filled in, so after the first set the button went
        // dead and nothing said why.
        val sepia = ReaderAppearance().copy(theme = ReaderThemeOption.SEPIA)
        assertEquals(
            ReaderPageBackground.toRgbHex(ReaderPageBackground.SEPIA_ARGB),
            ReaderColorPresetStore.backgroundHexFor(sepia)
        )
        assertTrue(ReaderColorPresetStore.selectionHexFor(sepia).length == 7)
        assertTrue(ReaderColorPresetStore.idFor(sepia).isNotEmpty())

        val saved = ReaderColorPresetStore.added(emptyList(), sepia, "护眼")
        assertEquals(1, saved.size)
        assertEquals("护眼", saved.single().name)
        assertTrue(ReaderColorPresetStore.matches(saved.single(), sepia))
    }

    @Test
    fun `each theme saves as its own set`() {
        var presets = emptyList<ReaderColorPreset>()
        for (theme in ReaderThemeOption.entries) {
            presets = ReaderColorPresetStore.added(
                presets,
                ReaderAppearance().copy(theme = theme),
                ""
            )
        }
        assertEquals(ReaderThemeOption.entries.size, presets.size)
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
    }

    @Test
    fun `an unnamed set is stored under a generated name`() {
        // `added` drops a nameless set, which reads to the user as a save
        // button that does nothing at all.
        val appearance = ReaderAppearance().copy(customBackgroundHex = "#F5F0E1")
        val saved = ReaderColorPresetStore.added(emptyList(), appearance, "  ")
        assertEquals(1, saved.size)
        assertEquals(ReaderColorPresetStore.defaultName(emptyList()), saved.single().name)

        val second = ReaderColorPresetStore.added(
            saved,
            appearance.copy(customBackgroundHex = "#101820"),
            ""
        )
        assertEquals(2, second.size)
        assertEquals(2, second.map { it.name }.toSet().size)
    }

    @Test
    fun `a hand-picked selection colour still wins`() {
        val appearance = ReaderAppearance().copy(
            customBackgroundHex = "#F5F0E1",
            customSelectionColorHex = "#c8a45b"
        )
        assertEquals("#C8A45B", ReaderColorPresetStore.selectionHexFor(appearance))
        assertEquals("F5F0E1-C8A45B", ReaderColorPresetStore.idFor(appearance))
    }
}
