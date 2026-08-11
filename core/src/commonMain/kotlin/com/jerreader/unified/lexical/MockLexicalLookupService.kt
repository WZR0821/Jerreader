package com.jerreader.unified.lexical

import com.jerreader.unified.domain.LanguageCode

class MockLexicalLookupService : LexicalLookupService {
    override suspend fun lookup(
        word: String,
        sentenceContext: String?,
        language: LanguageCode,
        candidates: List<String>
    ): WordExplanation {
        val surface = LexicalLookupPolicy.validate(word, language)
        return when (surface.lowercase() to language) {
            "went" to LanguageCode.ENGLISH -> WordExplanation(
                surfaceForm = surface,
                lemma = "go",
                reading = null,
                language = language,
                partOfSpeech = "动词",
                definitions = listOf("去", "前往"),
                inflectionNote = "go 的过去式。",
                usageNote = "离线 Mock 词条。",
                sentenceContext = sentenceContext,
                providerIdentifier = "mock-lexical-v1"
            )
            "食べました" to LanguageCode.JAPANESE -> WordExplanation(
                surfaceForm = surface,
                lemma = "食べる",
                reading = "たべました",
                language = language,
                partOfSpeech = "动词",
                definitions = listOf("吃", "食用"),
                inflectionNote = "礼貌体过去式；词典形为「食べる」。",
                usageNote = "离线 Mock 词条。",
                sentenceContext = sentenceContext,
                providerIdentifier = "mock-lexical-v1"
            )
            else -> throw LexicalLookupFailure.ResultNotFound
        }
    }
}
