package com.jerreader.shared.translation

import com.jerreader.shared.domain.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * English tap-to-sentence rules added in 2.1.0: titles such as "Mr." never end
 * a sentence, initials and "U.S." end one only before a word that clearly
 * starts a new sentence, and a terminator inside a long quotation ends its own
 * sentence instead of the whole quotation becoming one tap target.
 *
 * Each case taps the target word and the first, middle and last character of
 * the expected sentence. The two long passages are public-domain text from
 * Project Gutenberg (Stevenson, "Strange Case of Dr Jekyll and Mr Hyde";
 * Wilde, "The Picture of Dorian Gray"), shortened.
 */
class ReaderEnglishSentenceTest {
    private val enfield =
        "“I see you feel as I do,” said Mr. Enfield. “Yes, it’s a bad story. For my man " +
        "was a fellow that nobody could have to do with, a really damnable man; and the " +
        "person that drew the cheque is the very pink of the proprieties, celebrated " +
        "too, and (what makes it worse) one of your fellows who do what they call good. " +
        "Blackmail, I suppose.”"

    private val wotton =
        "“Not send it anywhere? My dear fellow, why? Have you any reason? What odd chaps " +
        "you painters are! You do anything in the world to gain a reputation. As soon as " +
        "you have one, you seem to want to throw it away. It is silly of you, for there " +
        "is only one thing in the world worse than being talked about, and that is not " +
        "being talked about. A portrait like this would set you far above all the young " +
        "men in England.”"

    private fun assertTaps(
        text: String,
        target: String,
        expected: String,
        language: LanguageCode? = LanguageCode.ENGLISH
    ) {
        val start = text.indexOf(expected)
        check(start >= 0) { "expected sentence is not in the text" }
        val targetOffset = if (target == LAST_CLOSING_QUOTE) text.lastIndexOf('”') else text.indexOf(target)
        check(targetOffset >= 0) { "target is not in the text" }
        val offsets = listOf(targetOffset, start, start + expected.length / 2, start + expected.length - 1)
        for (offset in offsets) {
            val range = ReaderSentenceSegmenter.sentenceRange(text, offset, language)
            assertEquals(expected, range?.let { text.substring(it) }, "tap at $offset")
        }
    }

    @Test
    fun titleBeforeANameDoesNotEndTheSentence() {
        assertTaps(
            "Mr. Bennet was odd. His wife was not.",
            "Bennet",
            "Mr. Bennet was odd."
        )
    }

    @Test
    fun sentenceAfterATitledNameStartsAfterItsPeriod() {
        assertTaps(
            "Mr. Bennet was odd. His wife was not.",
            "wife",
            "His wife was not."
        )
    }

    @Test
    fun pronounIBeforeAPeriodStillEndsTheSentence() {
        assertTaps(
            "I said so, and so did I. He laughed.",
            "laughed",
            "He laughed."
        )
    }

    @Test
    fun pronounIBeforeAPeriodKeepsItsOwnSentence() {
        assertTaps(
            "I said so, and so did I. He laughed.",
            "said",
            "I said so, and so did I."
        )
    }

    @Test
    fun initialismEndsTheSentenceBeforeAClearStarter() {
        assertTaps(
            "He lives in the U.S. He moved there.",
            "moved",
            "He moved there."
        )
    }

    @Test
    fun initialismInsideASentenceKeepsGoing() {
        assertTaps(
            "The U.S. Army arrived. It was late.",
            "Army",
            "The U.S. Army arrived."
        )
    }

    @Test
    fun lowerCaseAfterAPeriodContinues() {
        assertTaps(
            "It was 5 p.m. and dark. Nobody came.",
            "dark",
            "It was 5 p.m. and dark."
        )
    }

    @Test
    fun ellipsisBeforeALowerCaseWordContinues() {
        assertTaps(
            "Wait... what is it? Nothing.",
            "what",
            "Wait... what is it?"
        )
    }

    @Test
    fun ellipsisBeforeACapitalStartsANewSentence() {
        assertTaps(
            "Wait... What is it?",
            "What",
            "What is it?"
        )
    }

    @Test
    fun reportingClauseStaysWithTheQuoteBeforeIt() {
        assertTaps(
            "“Yes,” Holmes said. “I think so.”",
            "Holmes",
            "“Yes,” Holmes said."
        )
    }

    @Test
    fun quotedSentenceKeepsItsClosingQuote() {
        assertTaps(
            "“Yes,” Holmes said. “I think so.”",
            "think",
            "“I think so.”"
        )
    }

    @Test
    fun sentenceInsideAQuoteEndsWithItsReportingClause() {
        assertTaps(
            "“You go away. It is silly of you,” he said. Then.",
            "silly",
            "It is silly of you,” he said."
        )
    }

    @Test
    fun tapOnAClosingQuoteBelongsToTheSentenceItCloses() {
        assertTaps(
            "“You go away. It is silly.” Then he left.",
            LAST_CLOSING_QUOTE,
            "It is silly.”"
        )
    }

    @Test
    fun numberAbbreviationKeepsItsValue() {
        assertTaps(
            "No. 5 was missing. We searched every room.",
            "missing",
            "No. 5 was missing."
        )
    }

    @Test
    fun numberAbbreviationBeforeAWordStillEnds() {
        assertTaps(
            "He said no. Then he left.",
            "Then",
            "Then he left."
        )
    }

    @Test
    fun severalTitlesInOneSentence() {
        assertTaps(
            "Dr. Jekyll met Mrs. Poole on St. James Street.",
            "Poole",
            "Dr. Jekyll met Mrs. Poole on St. James Street."
        )
    }

    @Test
    fun titleAfterADashKeepsTheSentence() {
        assertTaps(
            "A perfect misanthropist’s Heaven—and Mr. Heathcliff and I are a pair.",
            "pair",
            "A perfect misanthropist’s Heaven—and Mr. Heathcliff and I are a pair."
        )
    }

    @Test
    fun longQuoteOpeningSentenceEndsAtItsReportingClause() {
        assertTaps(
            enfield,
            "feel",
            "“I see you feel as I do,” said Mr. Enfield."
        )
    }

    @Test
    fun longQuoteFirstSpokenSentence() {
        assertTaps(
            enfield,
            "bad story",
            "“Yes, it’s a bad story."
        )
    }

    @Test
    fun longQuoteMiddleSentenceIsSelectedAlone() {
        assertTaps(
            enfield,
            "damnable",
            "For my man was a fellow that nobody could have to do with, a really damnable " +
            "man; and the person that drew the cheque is the very pink of the proprieties, " +
            "celebrated too, and (what makes it worse) one of your fellows who do what they " +
            "call good."
        )
    }

    @Test
    fun longQuoteLastSentenceKeepsTheClosingQuote() {
        assertTaps(
            enfield,
            "suppose",
            "Blackmail, I suppose.”"
        )
    }

    @Test
    fun longQuoteQuestionIsSelectedAlone() {
        assertTaps(
            wotton,
            "why",
            "My dear fellow, why?"
        )
    }

    @Test
    fun longQuoteExclamationIsSelectedAlone() {
        assertTaps(
            wotton,
            "painters",
            "What odd chaps you painters are!"
        )
    }

    @Test
    fun longQuoteLongSentenceIsSelectedAlone() {
        assertTaps(
            wotton,
            "silly",
            "It is silly of you, for there is only one thing in the world worse than being " +
            "talked about, and that is not being talked about."
        )
    }

    @Test
    fun englishRulesApplyWithoutALanguageHint() {
        assertTaps(
            "Mr. Bennet was odd. His wife was not.",
            "Bennet",
            "Mr. Bennet was odd.",
            null
        )
    }

    @Test
    fun englishRulesApplyInAChineseBookWhenTheTextHasNoHan() {
        assertTaps(
            "She went home. Then she opened a book.",
            "went",
            "She went home.",
            LanguageCode.CHINESE_SIMPLIFIED
        )
    }

    private companion object {
        const val LAST_CLOSING_QUOTE = "<last closing quote>"
    }
}
