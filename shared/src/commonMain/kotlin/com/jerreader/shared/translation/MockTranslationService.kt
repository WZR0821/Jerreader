package com.jerreader.shared.translation

import com.jerreader.shared.domain.LanguageCode

class MockTranslationService : TranslationService, ContextExplanationService {
    override val identifier: String = "mock-v1"

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val source = TranslationInputPolicy.validate(request.text)
        val sourceLanguage = request.sourceLanguage ?: detectLanguage(source)

        val translated = when (source) {
            "Hello" -> "你好"
            "こんにちは" -> "你好"
            else -> "[测试译文] $source"
        }

        return TranslationResult(
            translatedText = translated,
            sourceLanguage = sourceLanguage,
            targetLanguage = request.targetLanguage,
            providerIdentifier = identifier
        )
    }

    override suspend fun explain(request: ContextExplanationRequest): ContextExplanationResult {
        TranslationInputPolicy.validate(request.focusedText)
        return ContextExplanationResult(
            explanation = "[测试解析] ${request.focusedText}",
            providerIdentifier = identifier
        )
    }

    private fun detectLanguage(text: String): LanguageCode =
        if (text.any { character -> character in '\u3040'..'\u30ff' }) {
            LanguageCode.JAPANESE
        } else {
            LanguageCode.ENGLISH
        }
}
