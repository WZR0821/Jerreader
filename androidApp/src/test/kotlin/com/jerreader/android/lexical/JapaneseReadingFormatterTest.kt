package com.jerreader.android.lexical

import org.junit.Assert.*
import org.junit.Test

class JapaneseReadingFormatterTest {
    private val dictionary = JapaneseChineseLexicon("""
        食べる	たべる	一段动词	吃	
        ご飯	ごはん	名词	米饭	
        毎朝	まいあさ	名词	每天早晨	
        今日	きょう	名词	今天	
        今日	こんにち	名词	今日	
    """.trimIndent())
    @Test fun exactWordsKeepKanaOutsideTheAnnotation() {
        val segments = JapaneseReadingFormatter.segments("ご飯", dictionary)
        assertEquals(listOf(JapaneseReadingFormatter.Segment("ご"), JapaneseReadingFormatter.Segment("飯", "はん")), segments)
    }
    @Test fun verifiedInflectionKeepsTheWrittenEnding() {
        val segments = JapaneseReadingFormatter.segments("食べました", dictionary)
        assertEquals("食べました", segments.joinToString("") { it.text })
        assertEquals(JapaneseReadingFormatter.Segment("食", "た"), segments.first())
        assertEquals("べました", segments.last().text)
    }
    @Test fun ambiguousWordsAreNotSplitIntoMisleadingReadings() {
        val segments = JapaneseReadingFormatter.segments("今日", dictionary)
        assertTrue(segments.all { it.reading == null })
        assertEquals("今日", segments.joinToString("") { it.text })
    }
    @Test fun punctuationNewlinesAndSupplementaryCharactersArePreserved() {
        val source = "毎朝、ご飯を食べました。\n𠮷野 ABC"
        val segments = JapaneseReadingFormatter.segments(source, dictionary)
        assertEquals(source, segments.joinToString("") { it.text })
        assertTrue(segments.any { it.reading == "まいあさ" })
    }
    @Test fun unknownTextDoesNotInventReadings() {
        val segments = JapaneseReadingFormatter.segments("未登録語", dictionary)
        assertTrue(segments.all { it.reading == null })
    }
    @Test fun emptyTextProducesNoSegments() {
        assertTrue(JapaneseReadingFormatter.segments("", dictionary).isEmpty())
    }
}
