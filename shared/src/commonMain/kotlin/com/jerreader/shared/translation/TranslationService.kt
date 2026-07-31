package com.jerreader.shared.translation

interface TranslationService {
    val identifier: String

    suspend fun translate(request: TranslationRequest): TranslationResult
}

object TranslationInputPolicy {
    const val MAXIMUM_TEXT_LENGTH = 2_000

    fun validate(text: String): String {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            throw TranslationFailure.EmptyInput
        }
        if (normalized.length > MAXIMUM_TEXT_LENGTH) {
            throw TranslationFailure.TextTooLong
        }
        return normalized
    }
}
