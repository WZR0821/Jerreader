package com.jerreader.shared.translation

import com.jerreader.shared.domain.LanguageCode
import kotlinx.coroutines.delay

/**
 * The offline service of tests and of mock mode. Its streamed answers
 * (2.1.0) arrive like a model's: [STREAM_PIECES] growing pieces
 * [STREAM_INTERVAL_MILLIS] apart, the whole text only as the result. Every AI
 * action has a fixed answer that UI tests look for; the plain [translate] and
 * [explain] still answer at once.
 */
class MockTranslationService :
    StreamingTranslationService,
    StreamingContextExplanationService,
    AIReadingActionService {
    override val identifier: String = "mock-v1"

    override suspend fun translate(request: TranslationRequest): TranslationResult = translation(request)

    override suspend fun translateStreaming(
        request: TranslationRequest,
        onPartial: (String) -> Unit
    ): TranslationResult = translation(request).also { stream(it.translatedText, onPartial) }

    override suspend fun explain(request: ContextExplanationRequest): ContextExplanationResult = explanation(request)

    override suspend fun explainStreaming(
        request: ContextExplanationRequest,
        onPartial: (String) -> Unit
    ): ContextExplanationResult = explanation(request).also { stream(it.explanation, onPartial) }

    override suspend fun perform(request: AIActionRequest, onPartial: (String) -> Unit): AIActionResult {
        val source = TranslationInputPolicy.validate(request.source)
        val text = when (request.action) {
            AIReadingAction.LITERAL -> LITERAL_TEXT
            AIReadingAction.FOLLOW_UP -> FOLLOW_UP_PREFIX + request.turns.lastOrNull()?.question?.trim().orEmpty()
            AIReadingAction.COMPREHENSION -> COMPREHENSION_TEXT
            AIReadingAction.REFERENCE -> REFERENCE_TEXT
            AIReadingAction.STRUCTURE, AIReadingAction.LOOKUP -> "[测试解析] $source"
        }
        stream(text, onPartial)
        return AIActionResult(text, answerSource(request.context, source))
    }

    private fun translation(request: TranslationRequest): TranslationResult {
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
            providerIdentifier = identifier,
            answerSource = answerSource(request.context, source)
        )
    }

    private fun explanation(request: ContextExplanationRequest): ContextExplanationResult {
        val focused = TranslationInputPolicy.validate(request.focusedText)
        val text = when (request.lens?.mode) {
            AIReadingLensMode.COMPREHENSION -> COMPREHENSION_TEXT
            AIReadingLensMode.REFERENCE -> REFERENCE_TEXT
            else -> "[测试解析] ${request.focusedText}"
        }
        return ContextExplanationResult(
            explanation = text,
            providerIdentifier = identifier,
            answerSource = answerSource(request.contextText, focused)
        )
    }

    /** Pieces of [text] that only grow and never split a character in two. */
    private suspend fun stream(text: String, onPartial: (String) -> Unit) {
        for (piece in 1 until STREAM_PIECES) {
            delay(STREAM_INTERVAL_MILLIS)
            var end = text.length * piece / STREAM_PIECES
            if (end in 1 until text.length && text[end - 1].isHighSurrogate()) end -= 1
            if (end > 0) onPartial(text.substring(0, end))
        }
        delay(STREAM_INTERVAL_MILLIS)
    }

    private fun answerSource(context: String?, source: String) = AIAnswerSource(
        modelName = MODEL_NAME,
        isAIModel = true,
        sentContext = context?.trim()?.takeIf { it.isNotEmpty() && it != source }
    )

    private fun detectLanguage(text: String): LanguageCode =
        if (text.any { character -> character in '\u3040'..'\u30ff' }) {
            LanguageCode.JAPANESE
        } else {
            LanguageCode.ENGLISH
        }

    companion object {
        /** The model name on the mock's source badges. */
        const val MODEL_NAME = "Mock 模型"
        const val COMPREHENSION_TEXT = "[测试读懂] 这句话交代了人物此刻的处境。"
        const val REFERENCE_TEXT = "[测试指代] 省略的主语是叙述者“我”。"
        const val LITERAL_TEXT = "直译：[测试直译] 逐词对应的译文。\n差别：[测试差别] 直译保留了原文的语序。"

        /** A follow-up answer is this, then the question. */
        const val FOLLOW_UP_PREFIX = "[测试追问] "
        const val STREAM_PIECES = 4
        const val STREAM_INTERVAL_MILLIS = 80L
    }
}
