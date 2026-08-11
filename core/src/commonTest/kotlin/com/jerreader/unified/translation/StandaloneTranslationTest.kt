package com.jerreader.unified.translation

import com.jerreader.unified.domain.LanguageCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StandaloneTranslationTest {

    @Test
    fun `word mode pins its own dictionary prompt`() {
        val configured = "把下面的文字翻译成 {target_language}。"
        assertEquals(
            StandaloneTranslationMode.WORD_PROMPT,
            StandaloneTranslationMode.WORD.promptTemplate(configured)
        )
        assertEquals(
            configured,
            StandaloneTranslationMode.SENTENCE.promptTemplate(configured)
        )
    }

    @Test
    fun `sentence mode falls back to the default prompt when none is configured`() {
        assertNull(StandaloneTranslationMode.SENTENCE.promptTemplate("   "))
    }

    @Test
    fun `auto detection prefers Japanese when kana is present`() {
        val input = StandaloneTranslationRequestPolicy.makeInput(
            text = "  彼は窓を開けた。  ",
            sourceChoice = TranslationSourceChoice.AUTOMATIC,
            targetLanguage = LanguageCode.CHINESE_SIMPLIFIED,
            mode = StandaloneTranslationMode.SENTENCE
        )
        assertEquals(LanguageCode.JAPANESE, input.sourceLanguage)
        assertEquals("彼は窓を開けた。", input.text)
    }

    @Test
    fun `han without kana detects Chinese`() {
        assertEquals(LanguageCode.CHINESE_SIMPLIFIED, StandaloneLanguageDetector.detect("他打开了窗户"))
    }

    @Test
    fun `latin letters detect English`() {
        assertEquals(LanguageCode.ENGLISH, StandaloneLanguageDetector.detect("He opened the window."))
    }

    @Test
    fun `punctuation alone is not a language`() {
        assertNull(StandaloneLanguageDetector.detect("…… 。！"))
    }

    @Test
    fun `word mode rejects input past its own shorter limit`() {
        val error = assertFailsWith<StandaloneTranslationValidationException> {
            StandaloneTranslationRequestPolicy.makeInput(
                text = "a".repeat(81),
                sourceChoice = TranslationSourceChoice.ENGLISH,
                targetLanguage = LanguageCode.CHINESE_SIMPLIFIED,
                mode = StandaloneTranslationMode.WORD
            )
        }
        assertEquals(
            StandaloneTranslationValidationError.TextTooLong(80),
            error.error
        )
        // The same text is fine as a sentence.
        StandaloneTranslationRequestPolicy.makeInput(
            text = "a".repeat(81),
            sourceChoice = TranslationSourceChoice.ENGLISH,
            targetLanguage = LanguageCode.CHINESE_SIMPLIFIED,
            mode = StandaloneTranslationMode.SENTENCE
        )
    }

    @Test
    fun `matching source and target is rejected`() {
        val error = assertFailsWith<StandaloneTranslationValidationException> {
            StandaloneTranslationRequestPolicy.makeInput(
                text = "窗户",
                sourceChoice = TranslationSourceChoice.CHINESE_SIMPLIFIED,
                targetLanguage = LanguageCode.CHINESE_SIMPLIFIED,
                mode = StandaloneTranslationMode.WORD
            )
        }
        assertEquals(StandaloneTranslationValidationError.SameLanguage, error.error)
    }

    @Test
    fun `blank input is rejected before anything is sent`() {
        val error = assertFailsWith<StandaloneTranslationValidationException> {
            StandaloneTranslationRequestPolicy.makeInput(
                text = "   \n ",
                sourceChoice = TranslationSourceChoice.AUTOMATIC,
                targetLanguage = LanguageCode.CHINESE_SIMPLIFIED,
                mode = StandaloneTranslationMode.SENTENCE
            )
        }
        assertEquals(StandaloneTranslationValidationError.EmptyText, error.error)
    }

    @Test
    fun `dictionary enrichment only covers word mode in Japanese and English`() {
        assertTrue(
            StandaloneLexicalLookupPolicy.supports(
                StandaloneTranslationMode.WORD,
                LanguageCode.JAPANESE
            )
        )
        assertTrue(
            StandaloneLexicalLookupPolicy.supports(
                StandaloneTranslationMode.WORD,
                LanguageCode.ENGLISH
            )
        )
        assertFalse(
            StandaloneLexicalLookupPolicy.supports(
                StandaloneTranslationMode.WORD,
                LanguageCode.CHINESE_SIMPLIFIED
            )
        )
        assertFalse(
            StandaloneLexicalLookupPolicy.supports(
                StandaloneTranslationMode.SENTENCE,
                LanguageCode.JAPANESE
            )
        )
    }
}
