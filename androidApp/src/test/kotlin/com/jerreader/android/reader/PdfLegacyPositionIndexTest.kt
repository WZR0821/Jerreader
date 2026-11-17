package com.jerreader.android.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PdfLegacyPositionIndexTest {
    @Test
    fun oldPositionMeantThePreviousPage() {
        // Readium 3.3.0 reported visible page 1 as position 2; position k + 1
        // lives at index k of positions(), so page 1 is index 0.
        assertEquals(0, PdfLocatorFormat.legacyPositionIndex(2, 10))
        assertEquals(8, PdfLocatorFormat.legacyPositionIndex(10, 10))
    }

    @Test
    fun initialAndOutOfRangePositionsStayUnchanged() {
        assertNull(PdfLocatorFormat.legacyPositionIndex(1, 10))
        assertNull(PdfLocatorFormat.legacyPositionIndex(null, 10))
        assertNull(PdfLocatorFormat.legacyPositionIndex(12, 10))
        assertNull(PdfLocatorFormat.legacyPositionIndex(2, 0))
    }
}
