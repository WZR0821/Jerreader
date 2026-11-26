package com.jerreader.android.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLongPressAimTest {
    @Test
    fun readsThePagesAnswerAsEvaluateJavascriptHandsItBack() {
        // JSON.stringify's string comes back quoted once more.
        assertEquals(-20f to 0f, ReaderLongPressAim.decodeShift("\"{\\\"dx\\\":-20,\\\"dy\\\":0}\""))
        assertEquals(0f to -12.5f, ReaderLongPressAim.decodeShift("{\"dx\":0,\"dy\":-12.5}"))
        // A press in a ruby's blank room moves along the line and across onto it.
        assertEquals(3f to -12f, ReaderLongPressAim.decodeShift("{\"dx\":3,\"dy\":-12}"))
    }

    @Test
    fun leavesThePressAloneWithoutAUsableAnswer() {
        val answers = listOf(
            null, "null", "", "\"\"", "42", "{\"dx\":\"left\",\"dy\":0}", "{\"dx\":0}",
            // No move to speak of, or more than any glyph is wide.
            "{\"dx\":0,\"dy\":0}", "{\"dx\":0.2,\"dy\":-0.3}", "{\"dx\":-900,\"dy\":0}"
        )
        for (raw in answers) assertNull("answer $raw", ReaderLongPressAim.decodeShift(raw))
    }

    @Test
    fun theScriptCarriesThePointAndOnlyReadsThePage() {
        for (trace in listOf(false, true)) {
            val script = ReaderLongPressAim.aimScript(540.5f, 300.25f, 1080, trace)
            assertTrue(script.contains("const scale = 1080 /"))
            assertTrue(script.contains("const x = 540.5 / scale;"))
            assertTrue(script.contains("const y = 300.25 / scale;"))
            // Changing the selection from script would cost WebView its handles and menu.
            for (write in listOf("addRange", "removeAllRanges", "modify(", "appendChild", "dispatchEvent", "innerHTML")) {
                assertTrue("the aim script (trace $trace) calls $write", write !in script)
            }
        }
    }

    @Test
    fun onlyATracedScriptSaysWhichWayAPressWent() {
        // In the reader every way out stays null and a move carries only dx and dy.
        val plain = ReaderLongPressAim.aimScript(10f, 20f, 1080)
        assertTrue(plain.contains("const exit = (why) => null;"))
        assertTrue("via: via" !in plain)
        val traced = ReaderLongPressAim.aimScript(10f, 20f, 1080, trace = true)
        assertTrue(traced.contains("const exit = (why) => JSON.stringify(Object.assign({ exit: why }, facts));"))
        assertTrue(traced.contains("Object.assign(shift, { via: via }, facts);"))
        // A traced move still reads as a move.
        assertEquals(-42.25f to 0f, ReaderLongPressAim.decodeShift("{\"dx\":-42.25,\"dy\":0,\"via\":\"room\",\"hit\":\"p\"}"))
    }
}
