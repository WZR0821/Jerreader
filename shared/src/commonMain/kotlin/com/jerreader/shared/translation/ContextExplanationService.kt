package com.jerreader.shared.translation

import com.jerreader.shared.domain.LanguageCode

data class ContextExplanationRequest(
    val focusedText: String,
    val contextText: String?,
    val sourceLanguage: LanguageCode?,
    val isWordLookup: Boolean = false,
    val dictionaryEntry: String? = null,
    /** Set for an AI reading lens question; null keeps the plain grammar analysis. */
    val lens: ReadingLensOptions? = null
)

data class ContextExplanationResult(
    val explanation: String,
    val providerIdentifier: String,
    /** Who answered, for the source badge (2.1.0); null when unknown. */
    val answerSource: AIAnswerSource? = null,
    val usage: AIUsage? = null
)

interface ContextExplanationService {
    suspend fun explain(request: ContextExplanationRequest): ContextExplanationResult
}
