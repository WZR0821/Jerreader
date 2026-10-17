package com.jerreader.android.lexical

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EnglishChineseLexiconTest {
    private fun bundled() = EnglishChineseLexicon(File("src/main/assets/en-zh-basic.tsv").readText())
    @Test fun productionDictionaryContainsEightThousandWordsAndCommonDefinitions() {
        val dictionary = bundled()
        assertEquals(8000, dictionary.headwordCount)
        listOf("book", "beautiful", "translate", "language", "read").forEach { term ->
            val result = dictionary.lookup(term, "context", emptyList())
            assertNotNull(term, result)
            assertEquals("context", result!!.sentenceContext)
            assertTrue(result.definitions.isNotEmpty())
        }
    }
    @Test fun handlesCaseWidthAndVerifiedInflectionsWithoutInventingUnknownWords() {
        val dictionary = bundled()
        assertEquals("book", dictionary.lookup("ＢＯＯＫ", null, emptyList())!!.lemma)
        assertEquals("go", dictionary.lookup("went", null, emptyList())!!.lemma)
        assertNull(dictionary.lookup("not-a-real-lexeme-qzx", null, emptyList()))
        assertNull(dictionary.lookup("This sentence is not a word", null, emptyList()))
    }
    @Test fun exactHeadwordPrecedesInflectedAlias() {
        val dictionary = EnglishChineseLexicon("see\t\tv\t看见\tsaw\nsaw\t\tn\t锯\tsaws")
        assertEquals("saw", dictionary.lookup("saw", null, emptyList())!!.lemma)
    }
}
