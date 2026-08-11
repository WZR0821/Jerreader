package com.jerreader.unified.reader.selection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderSelectionDecoderTest {

    private val payload = """
        {"rawText":"漢字かんじ","normalizedText":"漢字",
         "rects":[{"x":10,"y":100,"w":40,"h":18},{"x":50,"y":100,"w":20,"h":18}],
         "vertical":false,"recoveredFromRuby":true}
    """.trimIndent()

    @Test
    fun `a snapshot decodes text and geometry`() {
        val snapshot = assertNotNull(ReaderSelectionDecoder.decodeSnapshot(payload))

        assertEquals("漢字", snapshot.baseText)
        assertEquals(2, snapshot.rects.size)
        assertTrue(snapshot.recoveredFromRuby)
        assertTrue(snapshot.isUsable)
    }

    @Test
    fun `the double-encoded form Android returns decodes too`() {
        // WebView.evaluateJavascript hands back a JSON *string literal*.
        val doubleEncoded = "\"{\\\"normalizedText\\\":\\\"hi\\\"," +
            "\\\"rects\\\":[{\\\"x\\\":1,\\\"y\\\":2,\\\"w\\\":30,\\\"h\\\":10}]}\""

        val snapshot = assertNotNull(ReaderSelectionDecoder.decodeSnapshot(doubleEncoded))

        assertEquals("hi", snapshot.baseText)
        assertEquals(1, snapshot.rects.size)
    }

    @Test
    fun `the reader's configured writing mode overrides the page's report`() {
        // Some WebView builds report -epub-writing-mode vertical-rl as horizontal.
        val snapshot = assertNotNull(
            ReaderSelectionDecoder.decodeSnapshot(payload, ReaderWritingMode.VERTICAL)
        )

        assertEquals(ReaderWritingMode.VERTICAL, snapshot.writingMode)
    }

    @Test
    fun `a snapshot with no usable rectangles is rejected`() {
        val empty = """{"normalizedText":"text","rects":[{"x":1,"y":2,"w":0.1,"h":0.1}]}"""

        assertNull(ReaderSelectionDecoder.decodeSnapshot(empty))
    }

    @Test
    fun `a blank selection is rejected rather than translated`() {
        val blank = """{"normalizedText":"   ","rects":[{"x":1,"y":2,"w":30,"h":10}]}"""

        assertNull(ReaderSelectionDecoder.decodeSnapshot(blank))
    }

    @Test
    fun `the literal null a probe returns for no hit decodes to null`() {
        assertNull(ReaderSelectionDecoder.decodeSnapshot("null"))
        assertNull(ReaderSelectionDecoder.decodeSnapshot(null))
        assertNull(ReaderSelectionDecoder.decodeSnapshot(""))
    }

    @Test
    fun `malformed json degrades to no selection instead of throwing`() {
        assertNull(ReaderSelectionDecoder.decodeSnapshot("{\"normalizedText\":"))
        assertNull(ReaderSelectionDecoder.decodeBlockText("]["))
    }

    @Test
    fun `a failed selection result is rejected`() {
        assertNull(ReaderSelectionDecoder.decodeSelectionResult("""{"ok":false}"""))
    }

    @Test
    fun `block text clamps a caret offset past the end of the text`() {
        val block = assertNotNull(
            ReaderSelectionDecoder.decodeBlockText("""{"text":"abc","offset":99}""")
        )

        assertEquals(2, block.caretOffset)
    }
}
