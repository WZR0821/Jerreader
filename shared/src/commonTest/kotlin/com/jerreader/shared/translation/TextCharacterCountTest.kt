package com.jerreader.shared.translation

import com.jerreader.shared.domain.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TextCharacterCountTest {
    @Test
    fun composedCharactersMatchTheSwiftCharacterContract() {
        for (character in listOf("字", "𠮷", "😀", "🇯🇵", "e\u0301", "は\u3099", "👨‍👩‍👧‍👦")) {
            assertEquals(1, textCharacterCount(character), character)
            assertEquals(80, textCharacterCount(character.repeat(80)), character)
        }
        assertEquals(0, textCharacterCount(""))
    }

    @Test
    fun wordLimitDoesNotRejectSurrogatePairsOrCombiningMarksEarly() {
        for (character in listOf("𠮷", "e\u0301", "👨‍👩‍👧‍👦")) {
            fun prepare(count: Int) = StandaloneTranslationRequestPolicy.makeInput(
                character.repeat(count), TranslationSourceChoice.JAPANESE,
                LanguageCode.CHINESE_SIMPLIFIED, StandaloneTranslationMode.WORD
            )
            assertEquals(character.repeat(80), prepare(80).text)
            assertFailsWith<StandaloneTranslationValidationException> { prepare(81) }
        }
    }

    @Test
    fun serviceAndPageAgreeAtSentenceLimit() {
        val text = "𠮷".repeat(2_000)
        assertEquals(text, TranslationInputPolicy.validate(text))
        assertEquals(text, StandaloneTranslationRequestPolicy.makeInput(
            text, TranslationSourceChoice.JAPANESE,
            LanguageCode.CHINESE_SIMPLIFIED, StandaloneTranslationMode.SENTENCE
        ).text)
        assertFailsWith<TranslationFailure.TextTooLong> { TranslationInputPolicy.validate(text + "字") }
    }
}
