package com.jerreader.shared.translation

import com.jerreader.shared.domain.LanguageCode

data class ContextExplanationRequest(
    val focusedText: String,
    val contextText: String?,
    val sourceLanguage: LanguageCode?,
    val isWordLookup: Boolean = false,
    val dictionaryEntry: String? = null
)

data class ContextExplanationResult(
    val explanation: String,
    val providerIdentifier: String
)

interface ContextExplanationService {
    suspend fun explain(request: ContextExplanationRequest): ContextExplanationResult
}
