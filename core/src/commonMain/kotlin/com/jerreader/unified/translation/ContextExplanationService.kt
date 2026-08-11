package com.jerreader.unified.translation

import com.jerreader.unified.domain.LanguageCode

data class ContextExplanationRequest(
    val focusedText: String,
    val contextText: String?,
    val sourceLanguage: LanguageCode?
)

data class ContextExplanationResult(
    val explanation: String,
    val providerIdentifier: String
)

interface ContextExplanationService {
    suspend fun explain(request: ContextExplanationRequest): ContextExplanationResult
}
