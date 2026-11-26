package com.jerreader.shared.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReaderTranslationContextTest {
    @Test
    fun windowSplitsWhatIsLeftEvenlyAroundTheSentence() {
        assertEquals("aaBcc", ReaderTranslationContext.window("B", "aaaa", "cccc", maximumCharacterCount = 5))
        // An odd remainder goes after the sentence, as on iOS.
        assertEquals("aBcc", ReaderTranslationContext.window("B", "aaaa", "cccc", maximumCharacterCount = 4))
        assertNull(ReaderTranslationContext.window("B", "", "", maximumCharacterCount = 5))
        assertNull(ReaderTranslationContext.window("  ", "aaaa", "cccc"))
    }

    @Test
    fun windowCountsComposedCharacters() {
        val family = "👨‍👩‍👧‍👦"
        assertEquals(
            "𠮷é字$family$family",
            ReaderTranslationContext.window("字", "𠮷𠮷é", "$family${family}x", maximumCharacterCount = 5)
        )
        assertEquals("", composedPrefix("𠮷", 0))
        assertEquals("𠮷é", composedSuffix("𠮷𠮷é", 2))
        assertEquals("𠮷𠮷é", composedSuffix("𠮷𠮷é", 9))
        assertEquals(family, composedPrefix("$family$family", 1))
    }

    @Test
    fun paragraphIsTheWholeParagraphWhileItFits() {
        assertEquals(
            "外は雨だった。彼は扉を閉めた。",
            ReaderTranslationContext.paragraph("外は雨だった。彼は扉を閉めた。", 7, 15)
        )
        // Whole, as on iOS, even where an even split around the sentence would cut the longer side.
        assertEquals("aaaaaaBc", ReaderTranslationContext.paragraph("aaaaaaBc", 6, 7, maximumCharacterCount = 8))
        // A paragraph of one sentence sends nothing else with it.
        assertNull(ReaderTranslationContext.paragraph(" 彼は扉を開けた。\n", 1, 9))
    }

    @Test
    fun paragraphTooLongIsTheWindowAroundTheSentence() {
        assertEquals("aaBcc", ReaderTranslationContext.paragraph("aaaaBcccc", 4, 5, maximumCharacterCount = 5))
        assertNull(ReaderTranslationContext.paragraph("aaaa", 3, 9))
        assertNull(ReaderTranslationContext.paragraph("aaaa", 2, 2))
        assertNull(ReaderTranslationContext.paragraph("a  b", 1, 3))
    }

    @Test
    fun normalizedTextCollapsesWhiteSpaceAndComposes() {
        assertEquals("a b c é", ReaderTranslationContext.normalizedText(" a\r\n\tb　c  é "))
    }

    @Test
    fun aroundFindsTheSentenceInsideALongerText() {
        assertEquals("xxBxx", ReaderTranslationContext.around("B", "xxBxx"))
        assertNull(ReaderTranslationContext.around("Z", "xxBxx"))
        assertNull(ReaderTranslationContext.around("B", null))
    }

    @Test
    fun meaningfulContextDropsEmptyOrRepeatedText() {
        assertNull(ReaderTranslationContext.meaningfulContext(" 吾輩は猫である。 ", "吾輩は猫である。"))
        assertNull(ReaderTranslationContext.meaningfulContext(" \n ", "x"))
        assertNull(ReaderTranslationContext.meaningfulContext(null, "x"))
        assertEquals("a b", ReaderTranslationContext.meaningfulContext("a \n b", "a"))
    }

    @Test
    fun literalAnswerNeedsBothLines() {
        assertEquals(
            AILiteralAnswer("我 是 猫。", "直译保留了自称。"),
            AIReadingActionPolicy.literalAnswer("直译：我 是 猫。\n差别：直译保留了自称。")
        )
        assertNull(AIReadingActionPolicy.literalAnswer("直译：我 是 猫。"))
        assertNull(AIReadingActionPolicy.literalAnswer("我是猫。"))
    }

    @Test
    fun guardIsAddedOnce() {
        val guarded = AIReadingActionPolicy.guarded("分析句子结构。")
        assertEquals("分析句子结构。\n${AIReadingActionPolicy.GUARD}", guarded)
        assertEquals(guarded, AIReadingActionPolicy.guarded(guarded))
    }
}
