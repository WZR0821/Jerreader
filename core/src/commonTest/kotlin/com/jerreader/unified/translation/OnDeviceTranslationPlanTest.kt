package com.jerreader.unified.translation

import com.jerreader.unified.domain.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The on-device translator is only as good as what it is handed. These are the
 * cases that used to reach it intact and come back wrong: a paragraph in one
 * call, a page's line breaks still in the text, a word split by hyphenation,
 * and an English sentence mistaken for Chinese because it quoted one character.
 */
class OnDeviceTranslationPlanTest {

    // --- normalization -----------------------------------------------------

    @Test
    fun aWrappedLatinLineBecomesOneSpace() {
        assertEquals(
            "The cat sat on the mat.",
            TranslationTextNormalizer.normalized("The cat sat\n   on the mat.")
        )
    }

    @Test
    fun aWrappedCjkLineLosesTheBreakEntirely() {
        assertEquals(
            "他坐在垫子上。",
            TranslationTextNormalizer.normalized("他坐在\n垫子上。")
        )
    }

    @Test
    fun aHyphenatedWordIsPutBackTogether() {
        assertEquals(
            "an international agreement",
            TranslationTextNormalizer.normalized("an inter-\nnational agreement")
        )
    }

    @Test
    fun aRealHyphenSurvives() {
        assertEquals(
            "a state-of-the-art design",
            TranslationTextNormalizer.normalized("a state-of-the-art design")
        )
    }

    @Test
    fun aDashBetweenWordsIsNotAHyphenation() {
        assertEquals("a - b", TranslationTextNormalizer.normalized("a - b"))
    }

    @Test
    fun softHyphensAndZeroWidthCharactersAreDropped() {
        assertEquals(
            "international",
            TranslationTextNormalizer.normalized("inter­nation​al﻿")
        )
    }

    @Test
    fun typographicSpacesBecomeOrdinaryOnes() {
        assertEquals("a b c", TranslationTextNormalizer.normalized("a b c"))
    }

    @Test
    fun normalizingIsIdempotent() {
        val samples = listOf(
            "The cat sat\non the mat.",
            "他坐在\n垫子上。",
            "an inter-\nnational agreement",
            "  spaced   out  ",
            ""
        )
        for (sample in samples) {
            val once = TranslationTextNormalizer.normalized(sample)
            assertEquals(once, TranslationTextNormalizer.normalized(once), "sample=$sample")
        }
    }

    // --- segmentation ------------------------------------------------------

    @Test
    fun aParagraphIsCutIntoItsSentences() {
        val segments = OnDeviceTranslationPlan.segments(
            "The cat sat on the mat. It had been a long day for the animal. " +
                "Nobody disturbed it.",
            LanguageCode.ENGLISH
        )
        assertEquals(
            listOf(
                "The cat sat on the mat.",
                "It had been a long day for the animal.",
                "Nobody disturbed it."
            ),
            segments
        )
    }

    @Test
    fun theTypesettingIsGoneBeforeTheTextIsCut() {
        val segments = OnDeviceTranslationPlan.segments(
            "The cat sat on the\nmat quietly. It had been an inter-\nnational affair.",
            LanguageCode.ENGLISH
        )
        assertEquals(
            listOf("The cat sat on the mat quietly.", "It had been an international affair."),
            segments
        )
    }

    @Test
    fun aScrapRidesWithTheSentenceThatCanCarryIt() {
        val segments = OnDeviceTranslationPlan.segments(
            "No. The cat sat on the mat and stayed there.",
            LanguageCode.ENGLISH
        )
        assertEquals(listOf("No. The cat sat on the mat and stayed there."), segments)
    }

    @Test
    fun aTrailingScrapJoinsTheSentenceBeforeIt() {
        val segments = OnDeviceTranslationPlan.segments(
            "The cat sat on the mat and stayed there. No.",
            LanguageCode.ENGLISH
        )
        assertEquals(listOf("The cat sat on the mat and stayed there. No."), segments)
    }

    @Test
    fun textWithNoTerminatorIsStillOneSegment() {
        assertEquals(
            listOf("a fragment with no full stop"),
            OnDeviceTranslationPlan.segments("a fragment with no full stop", LanguageCode.ENGLISH)
        )
    }

    @Test
    fun emptyAndBlankInputPlanNothing() {
        assertEquals(emptyList(), OnDeviceTranslationPlan.segments("", LanguageCode.ENGLISH))
        assertEquals(
            emptyList(),
            OnDeviceTranslationPlan.segments("   \n\t   ", LanguageCode.ENGLISH)
        )
    }

    @Test
    fun everySegmentFitsTheCall() {
        val long = (1..80).joinToString(", ") { "clause number $it" } + "."
        val segments = OnDeviceTranslationPlan.segments(long, LanguageCode.ENGLISH)
        assertTrue(segments.isNotEmpty())
        for (segment in segments) {
            assertTrue(
                segment.length <= OnDeviceTranslationPlan.MAXIMUM_SEGMENT_LENGTH,
                "segment of ${segment.length} chars: $segment"
            )
        }
    }

    @Test
    fun anUnbrokenRunIsStillCutRatherThanRefused() {
        val wall = "x".repeat(1_000)
        val segments = OnDeviceTranslationPlan.segments(wall, LanguageCode.ENGLISH)
        assertTrue(segments.size >= 4)
        for (segment in segments) {
            assertTrue(segment.length <= OnDeviceTranslationPlan.MAXIMUM_SEGMENT_LENGTH)
        }
        assertEquals(wall, segments.joinToString(""))
    }

    /** Cutting must not invent or lose words. */
    @Test
    fun segmentingKeepsEveryWord() {
        val samples = listOf(
            "The cat sat on the mat. It had been a long day. Nobody disturbed it.",
            "The cat sat on the\nmat. It was an inter-\nnational affair, truly.",
            (1..60).joinToString(", ") { "clause number $it" } + "."
        )
        for (sample in samples) {
            val normalized = TranslationTextNormalizer.normalized(sample)
            val segmented = OnDeviceTranslationPlan.segments(sample, LanguageCode.ENGLISH)
            assertEquals(
                normalized.split(" ").filter { it.isNotEmpty() },
                segmented.flatMap { it.split(" ") }.filter { it.isNotEmpty() },
                "sample=$sample"
            )
        }
    }

    @Test
    fun japaneseSpeechStaysWithItsSentence() {
        val segments = OnDeviceTranslationPlan.segments(
            "「何だよ、それ。」と彼は言った。次の日は雨が降っていた。",
            LanguageCode.JAPANESE
        )
        assertEquals(2, segments.size, "segments=$segments")
        assertTrue(segments.first().startsWith("「何だよ"))
    }

    // --- rejoining ---------------------------------------------------------

    @Test
    fun chineseOutputIsJoinedWithoutSpaces() {
        assertEquals(
            "猫坐在垫子上。它睡着了。",
            OnDeviceTranslationPlan.joinedTranslation(
                listOf("猫坐在垫子上。", "它睡着了。"),
                LanguageCode.CHINESE_SIMPLIFIED
            )
        )
    }

    @Test
    fun englishOutputIsJoinedWithSpaces() {
        assertEquals(
            "The cat sat. It slept.",
            OnDeviceTranslationPlan.joinedTranslation(
                listOf("The cat sat.", " It slept. "),
                LanguageCode.ENGLISH
            )
        )
    }

    @Test
    fun aModelThatReturnsNothingForOneSentenceDoesNotLeaveAGap() {
        assertEquals(
            "The cat sat. It slept.",
            OnDeviceTranslationPlan.joinedTranslation(
                listOf("The cat sat.", "", "   ", "It slept."),
                LanguageCode.ENGLISH
            )
        )
    }

    // --- language detection ------------------------------------------------

    @Test
    fun anEnglishSentenceQuotingOneIdeographIsStillEnglish() {
        assertEquals(
            LanguageCode.ENGLISH,
            TranslationLanguageHeuristic.detect("The word 猫 means cat in Chinese.")
        )
    }

    @Test
    fun chineseProseIsChinese() {
        assertEquals(
            LanguageCode.CHINESE_SIMPLIFIED,
            TranslationLanguageHeuristic.detect("他坐在垫子上，一整天都没有动。")
        )
    }

    @Test
    fun aChineseSentenceWithABorrowedWordIsStillChinese() {
        assertEquals(
            LanguageCode.CHINESE_SIMPLIFIED,
            TranslationLanguageHeuristic.detect("我喜欢用 Python 写程序。")
        )
    }

    @Test
    fun kanaSettleTheQuestion() {
        assertEquals(
            LanguageCode.JAPANESE,
            TranslationLanguageHeuristic.detect("猫が畳の上に座っていた。")
        )
    }

    @Test
    fun plainLatinTextFallsBack() {
        assertEquals(
            LanguageCode.ENGLISH,
            TranslationLanguageHeuristic.detect("The cat sat on the mat.")
        )
        assertEquals(
            LanguageCode.JAPANESE,
            TranslationLanguageHeuristic.detect("12345", fallback = LanguageCode.JAPANESE)
        )
    }
}
