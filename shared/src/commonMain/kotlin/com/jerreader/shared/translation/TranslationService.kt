package com.jerreader.shared.translation

interface TranslationService {
    val identifier: String

    suspend fun translate(request: TranslationRequest): TranslationResult
}

object TranslationInputPolicy {
    const val MAXIMUM_TEXT_LENGTH = 2_000

    fun validate(text: String): String {
        val normalized = text.trim()
        if (!isVisiblyNonEmpty(normalized)) {
            throw TranslationFailure.EmptyInput
        }
        if (normalized.length > MAXIMUM_TEXT_LENGTH) {
            throw TranslationFailure.TextTooLong
        }
        return normalized
    }

    fun isVisiblyNonEmpty(text: String): Boolean = text.any { character ->
        !character.isWhitespace() &&
            !character.isISOControl() &&
            character !in '\u200B'..'\u200F' &&
            character !in '\u202A'..'\u202E' &&
            character !in '\u2060'..'\u206F' &&
            character != '\uFEFF' &&
            character.category !in setOf(
                CharCategory.NON_SPACING_MARK,
                CharCategory.COMBINING_SPACING_MARK,
                CharCategory.ENCLOSING_MARK,
                CharCategory.FORMAT
            )
    }
}
