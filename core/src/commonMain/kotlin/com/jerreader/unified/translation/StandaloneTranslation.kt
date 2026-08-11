package com.jerreader.unified.translation

import com.jerreader.unified.domain.LanguageCode

/**
 * Kotlin port of the iOS `StandaloneTranslationMode`. The translate page owns a
 * word mode and a sentence mode: word mode asks for a dictionary-style
 * equivalent and then enriches it from a real dictionary, sentence mode keeps
 * whatever prompt the user configured for reading.
 */
enum class StandaloneTranslationMode(
    val title: String,
    val placeholder: String,
    val maximumCharacterCount: Int
) {
    WORD(
        title = "词语",
        placeholder = "输入一个词或短语",
        maximumCharacterCount = 80
    ),
    SENTENCE(
        title = "句子",
        placeholder = "输入或粘贴要翻译的句子",
        maximumCharacterCount = 2_000
    );

    /**
     * Word mode pins its own prompt so a provider cannot answer a single word
     * with a paragraph of commentary; sentence mode respects the user's prompt.
     */
    fun promptTemplate(fallback: String): String? = when (this) {
        WORD -> WORD_PROMPT
        SENTENCE -> fallback.takeIf(String::isNotBlank)
    }

    companion object {
        const val WORD_PROMPT: String =
            "你是一部精确的中英日词典。把选中的 {source_language} 词语翻译成最贴合语境的 " +
                "{target_language} 词或短语。只输出译词，不要解释、例句、标题、引号或备选列表。"
    }
}

data class StandaloneTranslationInput(
    val text: String,
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode,
    val mode: StandaloneTranslationMode
)

sealed class StandaloneTranslationValidationError(val message: String) {
    data object EmptyText : StandaloneTranslationValidationError("请输入需要翻译的文字。")
    data class TextTooLong(val maximum: Int) :
        StandaloneTranslationValidationError("当前模式一次最多翻译 $maximum 个字符。")
    data object LanguageNotDetected :
        StandaloneTranslationValidationError("无法自动识别原文语言，请手动选择中文、英语或日语。")
    data object SameLanguage :
        StandaloneTranslationValidationError("原文和译文不能选择相同的语言。")
}

class StandaloneTranslationValidationException(
    val error: StandaloneTranslationValidationError
) : Exception(error.message)

object StandaloneTranslationRequestPolicy {
    fun makeInput(
        text: String,
        sourceChoice: TranslationSourceChoice,
        targetLanguage: LanguageCode,
        mode: StandaloneTranslationMode
    ): StandaloneTranslationInput {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            throw StandaloneTranslationValidationException(
                StandaloneTranslationValidationError.EmptyText
            )
        }
        if (normalized.length > mode.maximumCharacterCount) {
            throw StandaloneTranslationValidationException(
                StandaloneTranslationValidationError.TextTooLong(mode.maximumCharacterCount)
            )
        }
        val source = sourceChoice.language
            ?: StandaloneLanguageDetector.detect(normalized)
            ?: throw StandaloneTranslationValidationException(
                StandaloneTranslationValidationError.LanguageNotDetected
            )
        if (source == targetLanguage) {
            throw StandaloneTranslationValidationException(
                StandaloneTranslationValidationError.SameLanguage
            )
        }
        return StandaloneTranslationInput(normalized, source, targetLanguage, mode)
    }
}

/** Word mode only enriches languages the app actually has a dictionary for. */
object StandaloneLexicalLookupPolicy {
    fun supports(mode: StandaloneTranslationMode, sourceLanguage: LanguageCode): Boolean =
        mode == StandaloneTranslationMode.WORD &&
            (sourceLanguage == LanguageCode.JAPANESE || sourceLanguage == LanguageCode.ENGLISH)
}

/**
 * Kana wins over Han because Japanese prose mixes both, so a sentence holding a
 * single kana character cannot be Chinese.
 */
object StandaloneLanguageDetector {
    fun detect(text: String): LanguageCode? {
        if (text.isBlank()) return null
        return when {
            text.any { it in '぀'..'ヿ' } -> LanguageCode.JAPANESE
            text.any { it in '㐀'..'鿿' } -> LanguageCode.CHINESE_SIMPLIFIED
            text.any { it.isLetter() } -> LanguageCode.ENGLISH
            else -> null
        }
    }
}
