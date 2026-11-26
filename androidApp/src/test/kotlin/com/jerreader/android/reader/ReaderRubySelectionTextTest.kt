package com.jerreader.android.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page side runs on a device in ReaderRubySelectionTest; this covers the Kotlin side. */
class ReaderRubySelectionTextTest {

    @Test
    fun decodesTheQuotedResultEvaluateJavascriptHandsBack() {
        assertEquals(
            ReaderRubySelectionText.Page("彼は扉を開けた。", "", ""),
            ReaderRubySelectionText.decode("\"{\\\"text\\\":\\\"彼は扉を開けた。\\\",\\\"before\\\":\\\"\\\",\\\"after\\\":\\\"\\\"}\"")
        )
        assertEquals("扉", ReaderRubySelectionText.decode("{\"text\":\"扉\"}")?.text)
    }

    @Test
    fun keepsReadiumTextWhenTheSelectionCrossesNoRuby() {
        assertEquals(
            ReaderRubySelectionText.Page(null, "彼は扉", "開けた。"),
            ReaderRubySelectionText.decode("{\"text\":null,\"before\":\"彼は扉\",\"after\":\"開けた。\"}")
        )
        assertEquals(null, ReaderRubySelectionText.decode("{\"text\":\" \",\"before\":\"a\",\"after\":null}")?.text)
    }

    @Test
    fun hasNothingToSayWithoutASelection() {
        assertNull(ReaderRubySelectionText.decode(null))
        assertNull(ReaderRubySelectionText.decode("null"))
        assertNull(ReaderRubySelectionText.decode("{\"text\":null,\"before\":null,\"after\":null}"))
        assertNull(ReaderRubySelectionText.decode("\"{\\\"text\\\":\\\" \\\"}\""))
        assertNull(ReaderRubySelectionText.decode("not json"))
    }

    @Test
    fun readsThePageWithoutChangingTheSelection() {
        val script = ReaderRubySelectionText.script()
        assertTrue(script.contains("closest('rt,rp,script,style,noscript')"))
        // The paragraph is found as iOS finds it (jerreaderReaderBlock).
        assertTrue(script.contains("'p,li,blockquote,dd,dt,figcaption,td,th,h1,h2,h3,h4,h5,h6,pre'"))
        assertFalse(script.contains("removeAllRanges"))
        assertFalse(script.contains("addRange"))
    }
}
