package com.jerreader.shared.translation

import com.jerreader.shared.domain.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderCrossPageExpansionTest {
    @Test
    fun expandsEnglishSentenceCutByPageBoundary() {
        val context = ReaderCrossPageContextBuilder.context(
            sourceText = "She read until",
            previousPageText = "The evening was quiet.",
            localContext = "The evening was quiet. She read until",
            nextPageText = "the rain stopped. Then she slept."
        )
        val expansion = ReaderCrossPageTranslationResolver.expansion(
            sourceText = "She read until",
            contextText = context,
            language = LanguageCode.ENGLISH
        )
        assertEquals("She read until the rain stopped.", expansion?.text)
    }

    @Test
    fun expandsJapaneseSentenceAcrossPages() {
        val context = ReaderCrossPageContextBuilder.context(
            sourceText = "彼女は家に",
            previousPageText = "雨が降っていた。",
            localContext = "雨が降っていた。彼女は家に",
            nextPageText = "帰りました。それから本を開きました。"
        )
        val expansion = ReaderCrossPageTranslationResolver.expansion(
            sourceText = "彼女は家に",
            contextText = context,
            language = LanguageCode.JAPANESE
        )
        // Only the sentence that actually contains the selection is completed;
        // the preceding sentence stays out of the request.
        assertEquals("彼女は家に帰りました。", expansion?.text)
    }

    @Test
    fun keepsQuotedJapaneseTerminatorWithItsQuotationParticle() {
        val range = ReaderSentenceSegmenter.sentenceRange(
            text = "「本当？」と彼は言った。次の話。",
            offset = 1,
            language = LanguageCode.JAPANESE
        )
        assertEquals("「本当？」と彼は言った。", range?.let { "「本当？」と彼は言った。次の話。".substring(it) })
    }

    @Test
    fun keepsAWholeSpokenLineTogetherWhenItContainsATerminator() {
        val text = "「何だよ、それ。手頃なあばらやって」敦也は翔太を見下ろした。"
        val range = ReaderSentenceSegmenter.sentenceRange(text, 3, LanguageCode.JAPANESE)
        assertEquals("「何だよ、それ。手頃なあばらやって」", range?.let { text.substring(it) })
    }

    @Test
    fun startsANewSentenceAfterTheSpokenLineEnds() {
        val text = "「何だよ、それ。手頃なあばらやって」敦也は翔太を見下ろした。"
        val range = ReaderSentenceSegmenter.sentenceRange(text, 20, LanguageCode.JAPANESE)
        assertEquals("敦也は翔太を見下ろした。", range?.let { text.substring(it) })
    }

    @Test
    fun keepsAQuotedTerminatorInsideTheSentenceThatReportsIt() {
        val text = "彼は「そうか。」と頷いた。次の話。"
        val range = ReaderSentenceSegmenter.sentenceRange(text, 0, LanguageCode.JAPANESE)
        assertEquals("彼は「そうか。」と頷いた。", range?.let { text.substring(it) })
    }

    @Test
    fun stillSplitsWhenTheQuoteNeverCloses() {
        // A half-quoted paragraph must not collapse into a single sentence.
        val text = "「何だよ、それ。手頃なあばらやって"
        val range = ReaderSentenceSegmenter.sentenceRange(text, 3, LanguageCode.JAPANESE)
        assertEquals("「何だよ、それ。", range?.let { text.substring(it) })
    }

    @Test
    fun doesNotEndTheSentenceAtAClosingParenthesis() {
        val text = "これは（たぶん）本当だ。次の話。"
        val range = ReaderSentenceSegmenter.sentenceRange(text, 0, LanguageCode.JAPANESE)
        assertEquals("これは（たぶん）本当だ。", range?.let { text.substring(it) })
    }

    @Test
    fun doesNotSplitEnglishAbbreviationsOrDecimals() {
        val text = "J. K. Rowling wrote it in 3.14 days. Next sentence."
        val range = ReaderSentenceSegmenter.sentenceRange(text, 0, LanguageCode.ENGLISH)
        assertEquals("J. K. Rowling wrote it in 3.14 days.", range?.let { text.substring(it) })
    }

    @Test
    fun keepsEnglishQuotedTerminatorWithItsReportingClause() {
        val text = "\"Stop!\" he said. Then he left."
        val range = ReaderSentenceSegmenter.sentenceRange(text, 2, LanguageCode.ENGLISH)
        assertEquals("\"Stop!\" he said.", range?.let { text.substring(it) })
    }

    @Test
    fun stillBreaksWhenTheNextSentenceStartsAfterAQuote() {
        val text = "\"Stop!\" Then he left."
        val range = ReaderSentenceSegmenter.sentenceRange(text, 2, LanguageCode.ENGLISH)
        assertEquals("\"Stop!\"", range?.let { text.substring(it) })
    }

    @Test
    fun returnsNullWithoutTrustworthyContext() {
        assertNull(
            ReaderCrossPageTranslationResolver.expansion(
                sourceText = "She read until",
                contextText = "She read until",
                language = LanguageCode.ENGLISH
            )
        )
        assertNull(
            ReaderCrossPageContextBuilder.context(
                sourceText = "She read until",
                previousPageText = null,
                localContext = null,
                nextPageText = null
            )
        )
    }

    @Test
    fun refusesNeighbouringTextFromAnotherChapter() {
        val context = ReaderCrossPageContextBuilder.context(
            sourceText = "She read until",
            currentResourceIdentifier = "ch1.xhtml",
            currentChapterIdentifier = "chapter-1",
            previousPage = ReaderCrossPageTextFragment("ch1.xhtml", "chapter-1", "It rained."),
            localContext = "It rained. She read until",
            nextPage = ReaderCrossPageTextFragment("ch2.xhtml", "chapter-2", "A new chapter began.")
        )
        assertTrue(context != null && !context.contains("A new chapter began"))
    }
}
