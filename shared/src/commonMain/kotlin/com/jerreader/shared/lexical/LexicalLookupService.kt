package com.jerreader.shared.lexical

import com.jerreader.shared.domain.LanguageCode

interface LexicalLookupService {
    suspend fun lookup(
        word: String,
        sentenceContext: String?,
        language: LanguageCode,
        candidates: List<String> = emptyList()
    ): WordExplanation
}

object LexicalLookupPolicy {
    const val MAXIMUM_WORD_LENGTH = 80

    fun validate(word: String, language: LanguageCode): String {
        val normalized = word.trim().trim { character -> character in EDGE_PUNCTUATION }
        if (normalized.isEmpty()) throw LexicalLookupFailure.EmptyInput
        if (normalized.length > MAXIMUM_WORD_LENGTH) throw LexicalLookupFailure.ResultNotFound
        if (language == LanguageCode.CHINESE_SIMPLIFIED) {
            throw LexicalLookupFailure.UnsupportedLanguage
        }
        return normalized
    }

    private val EDGE_PUNCTUATION = charArrayOf(
        '.', ',', '!', '?', ':', ';', '\'', '"', '(', ')', '[', ']', '{', '}',
        '。', '，', '！', '？', '：', '；', '「', '」', '『', '』', '（', '）', '【', '】'
    )
}
