package com.jerreader.shared.translation

import com.jerreader.shared.domain.LanguageCode

data class TranslationRequest(
    val text: String,
    val sourceLanguage: LanguageCode?,
    val targetLanguage: LanguageCode = LanguageCode.CHINESE_SIMPLIFIED
)

data class TranslationResult(
    val translatedText: String,
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode,
    val providerIdentifier: String
)

sealed class TranslationFailure(message: String) : Exception(message) {
    data object EmptyInput : TranslationFailure("请选择需要翻译的文本。")
    data object TextTooLong : TranslationFailure("选中文本超过 2,000 个字符。")
    data object UnsupportedLanguage : TranslationFailure("暂不支持该语言方向。")
    data object ServiceUnavailable : TranslationFailure("翻译服务暂时不可用。")
}
