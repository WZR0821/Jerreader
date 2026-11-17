package com.jerreader.android.reader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType

/** Readium's Url needs android.net.Uri, so the Locator half runs on the device. */
class PdfLocatorFormatTest {
    private val href = requireNotNull(Url("book.pdf"))

    private fun page(position: Int, count: Int) = Locator(
        href = href,
        mediaType = MediaType.PDF,
        locations = Locator.Locations(
            fragments = listOf("page=$position"),
            position = position,
            progression = (position - 1).toDouble() / count,
            totalProgression = (position - 1).toDouble() / count
        )
    )

    @Test
    fun legacyLocatorMovesBackOnePageAndKeepsItsText() {
        val positions = (1..5).map { page(it, 5) }
        val legacy = page(3, 5).copy(
            title = "第二章",
            text = Locator.Text(highlight = "吾輩は猫である")
        )

        val corrected = PdfLocatorFormat.normalize(legacy, positions)

        assertEquals(2, corrected.locations.position)
        assertEquals(listOf("page=2"), corrected.locations.fragments)
        assertEquals("第二章", corrected.title)
        assertEquals("吾輩は猫である", corrected.text.highlight)
        assertTrue(PdfLocatorFormat.isCurrent(corrected))
    }

    @Test
    fun markedLocatorSurvivesJsonAndIsNeverShiftedAgain() {
        val positions = (1..5).map { page(it, 5) }
        val current = PdfLocatorFormat.mark(page(4, 5))
        val restored = requireNotNull(Locator.fromJSON(JSONObject(current.toJSON().toString())))

        assertTrue(PdfLocatorFormat.isCurrent(restored))
        assertSame(restored, PdfLocatorFormat.normalize(restored, positions))
        assertEquals(4, PdfLocatorFormat.normalize(restored, positions).locations.position)
    }

    @Test
    fun firstPageAndUnknownPagesAreOnlyMarked() {
        val positions = (1..3).map { page(it, 3) }
        assertEquals(1, PdfLocatorFormat.normalize(page(1, 3), positions).locations.position)
        val beyond = PdfLocatorFormat.normalize(page(9, 3), positions)
        assertEquals(9, beyond.locations.position)
        assertTrue(PdfLocatorFormat.isCurrent(beyond))
    }
}
