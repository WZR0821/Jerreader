package com.jerreader.unified.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TranslationInputPolicyTest {
    @Test
    fun trimsValidInput() {
        assertEquals("こんにちは", TranslationInputPolicy.validate("  こんにちは  "))
    }

    @Test
    fun rejectsEmptyAndOversizedInput() {
        assertFailsWith<TranslationFailure.EmptyInput> {
            TranslationInputPolicy.validate(" \n ")
        }
        assertFailsWith<TranslationFailure.TextTooLong> {
            TranslationInputPolicy.validate("a".repeat(2_001))
        }
    }

    @Test
    fun rejectsInvisibleUnicodeOnlyInput() {
        assertFailsWith<TranslationFailure.EmptyInput> {
            TranslationInputPolicy.validate("\u200B\u200D\uFEFF")
        }
        assertFailsWith<TranslationFailure.EmptyInput> {
            TranslationInputPolicy.validate("\u0301\u0308")
        }
    }
}
