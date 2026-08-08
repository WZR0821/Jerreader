package com.jerreader.shared.lexical

import com.jerreader.shared.domain.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WordMorphologyAnalyzerTest {
    private val analyzer = WordMorphologyAnalyzer(SimpleTestTokenizer)

    @Test
    fun englishIrregularAndInflectedFormsResolveToUsefulCandidates() {
        assertAnalysis("went", "go", LemmaConfidence.IRREGULAR)
        assertAnalysis("studies", "study", LemmaConfidence.HEURISTIC)
        assertAnalysis("running", "run", LemmaConfidence.HEURISTIC)
    }

    @Test
    fun japanesePoliteAndAdjectiveFormsResolveConservatively() {
        assertAnalysis("食べました", "食べる", LemmaConfidence.HEURISTIC)
        assertAnalysis("読みました", "読む", LemmaConfidence.HEURISTIC)
        assertAnalysis("高かった", "高い", LemmaConfidence.HEURISTIC)
    }

    @Test
    fun punctuationOnlySelectionIsRejected() {
        assertNull(
            analyzer.analyze(
                WordAnalysisRequest(
                    text = "？！",
                    source = WordSelectionSource.NATIVE_SELECTION
                )
            )
        )
    }

    @Test
    fun focusChoosesTheOverlappingWordAndPreservesSourceAndRange() {
        val result = assertNotNull(
            analyzer.analyze(
                WordAnalysisRequest(
                    text = "She went home.",
                    focusStart = 5,
                    focusEndExclusive = 6,
                    languageHint = LanguageCode.ENGLISH,
                    source = WordSelectionSource.SHORT_TAP
                )
            )
        )
        assertEquals("went", result.surfaceForm)
        assertEquals(4, result.rangeStart)
        assertEquals(8, result.rangeEndExclusive)
        assertEquals(WordSelectionSource.SHORT_TAP, result.source)
    }

    private fun assertAnalysis(
        word: String,
        expectedLemma: String,
        expectedConfidence: LemmaConfidence
    ) {
        val result = assertNotNull(
            analyzer.analyze(
                WordAnalysisRequest(
                    text = word,
                    source = WordSelectionSource.SHORT_TAP
                )
            )
        )
        assertEquals(expectedLemma, result.lemma)
        assertEquals(expectedConfidence, result.confidence)
    }
}

private object SimpleTestTokenizer : WordBoundaryTokenizer {
    override fun tokenize(text: String, language: LanguageCode): List<TextToken> {
        val tokens = mutableListOf<TextToken>()
        var start = -1
        text.forEachIndexed { index, character ->
            val lexical = character.isLetter() || character == '\'' || character == '’'
            if (lexical && start < 0) start = index
            if (!lexical && start >= 0) {
                tokens += TextToken(text.substring(start, index), start, index)
                start = -1
            }
        }
        if (start >= 0) tokens += TextToken(text.substring(start), start, text.length)
        return tokens
    }
}
