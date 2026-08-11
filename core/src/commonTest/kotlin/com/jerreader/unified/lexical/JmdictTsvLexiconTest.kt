package com.jerreader.unified.lexical

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JmdictTsvLexiconTest {
    @Test
    fun indexesKanjiAndKanaFormsFromOneEntry() {
        val raw = "# Jerreader JMdict common subset\n" +
            "食べる\u001Fたべる\t食べる\tたべる\t一段动词\teat\u001Fconsume"

        val lexicon = JmdictTsvLexicon.parse(raw)
        assertEquals("食べる", lexicon.firstEntry(listOf("食べる"))?.lemma)
        assertEquals("たべる", lexicon.firstEntry(listOf("たべる"))?.reading)
        assertEquals(listOf("eat", "consume"), lexicon.entries("食べる").first().definitions)
        assertNull(lexicon.firstEntry(listOf("不存在")))
    }

    @Test
    fun malformedLinesAreIgnoredWithoutBreakingValidEntries() {
        val raw = "broken\n本\t本\tほん\t名词\tbook"
        assertEquals("ほん", JmdictTsvLexicon.parse(raw).firstEntry(listOf("本"))?.reading)
    }

    @Test
    fun exactLemmaWinsWhenAFormBelongsToSeveralEntries() {
        val raw = "元\u001F本\u001Fもと\t元\tもと\t名词\torigin\n" +
            "本\u001Fほん\t本\tほん\t名词\tbook"

        val lexicon = JmdictTsvLexicon.parse(raw)
        assertEquals("ほん", lexicon.firstEntry(listOf("本"))?.reading)
        assertEquals("もと", lexicon.firstEntry(listOf("元"))?.reading)
    }
}
