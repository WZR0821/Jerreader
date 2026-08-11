package com.jerreader.unified.lexical

import com.jerreader.unified.domain.LanguageCode

enum class WordSelectionSource {
    SHORT_TAP,
    NATIVE_SELECTION
}

enum class LemmaConfidence {
    EXACT,
    IRREGULAR,
    HEURISTIC,
    UNKNOWN
}

data class TextToken(
    val text: String,
    val start: Int,
    val endExclusive: Int
)

data class WordAnalysisRequest(
    val text: String,
    val focusStart: Int = 0,
    val focusEndExclusive: Int = text.length,
    val languageHint: LanguageCode? = null,
    val source: WordSelectionSource
)

data class WordAnalysis(
    val surfaceForm: String,
    val normalizedForm: String,
    val lemma: String?,
    val lemmaCandidates: List<String>,
    val language: LanguageCode,
    val confidence: LemmaConfidence,
    val rangeStart: Int,
    val rangeEndExclusive: Int,
    val source: WordSelectionSource,
    val sentenceContext: String? = null
)

data class WordExample(
    val sourceText: String,
    val translatedText: String? = null,
    val sourceLabel: String? = null
)

data class WordExplanation(
    val surfaceForm: String,
    val lemma: String?,
    val reading: String?,
    val language: LanguageCode,
    val partOfSpeech: String?,
    val definitions: List<String>,
    val inflectionNote: String? = null,
    val examples: List<WordExample> = emptyList(),
    val usageNote: String? = null,
    val sentenceContext: String? = null,
    val providerIdentifier: String
)

sealed class LexicalLookupFailure(message: String) : Exception(message) {
    data object EmptyInput : LexicalLookupFailure("请选择需要查询的词语。")
    data object UnsupportedLanguage : LexicalLookupFailure("目前只支持日语和英语查词。")
    data object ResultNotFound : LexicalLookupFailure("没有找到该词的中文释义。")
    data object ServiceUnavailable : LexicalLookupFailure("词典服务暂时不可用，请检查网络后重试。")
}
