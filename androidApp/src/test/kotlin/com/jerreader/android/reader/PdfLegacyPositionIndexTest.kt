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

    // 2.0.1: a PDF's last page reads (N−1)/N, so short PDFs never finished
    // and a one-page PDF never even started.
    @Test
    fun lastPdfPageReadsAsFinished() {
        assertEquals(1.0, PdfLocatorFormat.readingProgress(0.9, 10, 10), 0.0)
        assertEquals(1.0, PdfLocatorFormat.readingProgress(0.0, 1, 1), 0.0)
        assertEquals(0.8, PdfLocatorFormat.readingProgress(0.8, 9, 10), 0.0)
        assertEquals(0.0, PdfLocatorFormat.readingProgress(0.0, 1, 10), 0.0)
        // Not a PDF, or the position list is not loaded yet: left as reported.
        assertEquals(0.42, PdfLocatorFormat.readingProgress(0.42, 7, 0), 0.0)
        assertEquals(0.42, PdfLocatorFormat.readingProgress(0.42, null, 7), 0.0)
    }
}
