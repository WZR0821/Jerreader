package com.jerreader.shared.translation

import com.jerreader.shared.domain.LanguageCode

/** The tokens one answer took, as far as the provider said (2.1.0); null where it did not. */
data class AIUsage(val inputTokens: Int? = null, val outputTokens: Int? = null) {
    val isEmpty: Boolean get() = inputTokens == null && outputTokens == null
}

/**
 * A translation service that shows its answer while the provider writes it
 * (2.1.0). [onPartial] gets the whole text so far, never a fragment, and only
 * while the request runs, possibly on another thread. The returned result is
 * the complete, checked answer, the only one that may be cached. A provider
 * that cannot stream (the AI 代理, the on-device translator) answers once and
 * never calls [onPartial].
 */
interface StreamingTranslationService : TranslationService {
    suspend fun translateStreaming(request: TranslationRequest, onPartial: (String) -> Unit): TranslationResult
}

/** [ContextExplanationService] whose answer shows while it arrives, as [StreamingTranslationService]. */
interface StreamingContextExplanationService : ContextExplanationService {
    suspend fun explainStreaming(
        request: ContextExplanationRequest,
        onPartial: (String) -> Unit
    ): ContextExplanationResult
}

/** 直译 or 追问 for the sentence of a translation card (2.1.0). */
data class AIActionRequest(
    val action: AIReadingAction,
    val source: String,
    val translation: String?,
    val context: String?,
    val sourceLanguage: LanguageCode?,
    /** The follow-up conversation, oldest first; the last question has no answer yet. */
    val turns: List<AIFollowUpTurn> = emptyList(),
    val responseLanguage: LanguageCode = LanguageCode.CHINESE_SIMPLIFIED
)

data class AIActionResult(
    val text: String,
    val answerSource: AIAnswerSource? = null,
    val usage: AIUsage? = null
)

/**
 * Runs the chips with instructions of their own, 直译 and 追问; 结构, 读懂 and
 * 指代 are explanations. Streams like [StreamingTranslationService].
 */
interface AIReadingActionService {
    suspend fun perform(request: AIActionRequest, onPartial: (String) -> Unit): AIActionResult
}

/** Streams when the service can, otherwise answers once. */
suspend fun TranslationService.translateShowingPartials(
    request: TranslationRequest,
    onPartial: (String) -> Unit
): TranslationResult =
    (this as? StreamingTranslationService)?.translateStreaming(request, onPartial) ?: translate(request)

/** Streams when the service can, otherwise answers once. */
suspend fun ContextExplanationService.explainShowingPartials(
    request: ContextExplanationRequest,
    onPartial: (String) -> Unit
): ContextExplanationResult =
    (this as? StreamingContextExplanationService)?.explainStreaming(request, onPartial) ?: explain(request)

/** The 2.1.0 timing of streamed answers, the same numbers as iOS. */
object AIStreamingPolicy {
    /** Partial text reaches the screen at most this often. */
    const val PARTIAL_INTERVAL_MILLIS = 50L

    /**
     * How long a translation may go without new text. Before its first text
     * this is the whole wait, as it was before streaming.
     */
    const val TRANSLATION_IDLE_MILLIS = 30_000L

    /** The same for an AI result (结构, 读懂, 指代, 直译, 追问). */
    const val AI_IDLE_MILLIS = 70_000L

    /** No answer may take longer than this, however steadily it arrives. */
    const val TOTAL_MILLIS = 180_000L
}
