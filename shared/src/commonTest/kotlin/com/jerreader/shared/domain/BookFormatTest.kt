package com.jerreader.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookFormatTest {
    @Test
    fun resolvesSupportedExtensionsCaseInsensitively() {
        assertEquals(BookFormat.EPUB, BookFormat.fromFileName("日文竖排.EPUB"))
        assertEquals(BookFormat.PDF, BookFormat.fromFileName("sample.pdf"))
        assertEquals(BookFormat.DOCX, BookFormat.fromFileName("notes.Docx"))
        assertEquals(BookFormat.TXT, BookFormat.fromFileName("plain.txt"))
    }

    @Test
    fun rejectsUnsupportedOrExtensionlessFiles() {
        assertNull(BookFormat.fromFileName("protected.azw3"))
        assertNull(BookFormat.fromFileName("README"))
    }
}
