package com.jerreader.unified.translation

import com.jerreader.unified.domain.LanguageCode

data class TranslationRequest(
    val text: String,
    val sourceLanguage: LanguageCode?,
    val targetLanguage: LanguageCode = LanguageCode.CHINESE_SIMPLIFIED
)

data class TranslationResult(
    val translatedText: String,
    val sourceLanguage: LanguageCode,
    val targetLanguage: LanguageCode,
    val providerIdentifier: String,
    val isFromCache: Boolean = false
)

sealed class TranslationFailure(message: String) : Exception(message) {
    data object EmptyInput : TranslationFailure("请选择需要翻译的文本。")
    data object TextTooLong : TranslationFailure("选中文本超过 2,000 个字符。")
    data object UnsupportedLanguage : TranslationFailure("暂不支持该语言方向。")
    data object ConfigurationMissing : TranslationFailure("请先在“设置 → 翻译与 AI”中配置翻译服务。")
    data object InvalidConfiguration : TranslationFailure("翻译服务配置无效，请检查 HTTPS 地址、模型和密钥。")
    data class ProviderRejected(val detail: String) : TranslationFailure(detail)
    data object ServiceUnavailable : TranslationFailure("翻译服务暂时不可用。")
}

enum class QuickTranslationUnit {
    SENTENCE,
    PARAGRAPH
}

enum class TranslationSourceChoice(val language: LanguageCode?) {
    AUTOMATIC(null),
    JAPANESE(LanguageCode.JAPANESE),
    ENGLISH(LanguageCode.ENGLISH),
    CHINESE_SIMPLIFIED(LanguageCode.CHINESE_SIMPLIFIED)
}

enum class DirectAIProtocol {
    OPENAI_RESPONSES,
    ANTHROPIC_MESSAGES,
    OPENAI_CHAT_COMPLETIONS,
    GEMINI_GENERATE_CONTENT
}

enum class DirectAIProvider(
    val displayName: String,
    val defaultEndpoint: String,
    val defaultModel: String,
    val protocol: DirectAIProtocol,
    val usesCustomEndpoint: Boolean = false
) {
    OPENAI(
        displayName = "GPT",
        defaultEndpoint = "https://api.openai.com/v1/responses",
        defaultModel = "gpt-5.2",
        protocol = DirectAIProtocol.OPENAI_RESPONSES
    ),
    ANTHROPIC(
        displayName = "Claude",
        defaultEndpoint = "https://api.anthropic.com/v1/messages",
        defaultModel = "claude-sonnet-4-20250514",
        protocol = DirectAIProtocol.ANTHROPIC_MESSAGES
    ),
    KIMI(
        displayName = "Kimi",
        defaultEndpoint = "https://api.moonshot.cn/v1/chat/completions",
        defaultModel = "kimi-k2.6",
        protocol = DirectAIProtocol.OPENAI_CHAT_COMPLETIONS
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        defaultEndpoint = "https://api.deepseek.com/chat/completions",
        defaultModel = "deepseek-v4-flash",
        protocol = DirectAIProtocol.OPENAI_CHAT_COMPLETIONS
    ),
    GEMINI(
        displayName = "Gemini",
        defaultEndpoint = "https://generativelanguage.googleapis.com/v1beta",
        defaultModel = "gemini-3.6-flash",
        protocol = DirectAIProtocol.GEMINI_GENERATE_CONTENT
    ),
    OPENAI_COMPATIBLE(
        displayName = "其他兼容服务",
        defaultEndpoint = "",
        defaultModel = "",
        protocol = DirectAIProtocol.OPENAI_CHAT_COMPLETIONS,
        usesCustomEndpoint = true
    )
}

enum class TranslationProviderMode {
    ON_DEVICE,
    DIRECT_API,
    BACKEND_PROXY
}

enum class TranslationFallbackMode {
    NONE,
    ON_DEVICE,
    DIRECT_API,
    BACKEND_PROXY;

    val providerMode: TranslationProviderMode?
        get() = when (this) {
            NONE -> null
            ON_DEVICE -> TranslationProviderMode.ON_DEVICE
            DIRECT_API -> TranslationProviderMode.DIRECT_API
            BACKEND_PROXY -> TranslationProviderMode.BACKEND_PROXY
        }
}

enum class TranslationDisplayMode {
    NEAR_SELECTION,
    TOP_BANNER
}

data class TranslationPreferences(
    // iOS ships Apple Translation as the key-free default. On Android the
    // equivalent no-key path is the on-device model, so quick translation works
    // before the user configures any AI provider.
    val providerMode: TranslationProviderMode = TranslationProviderMode.ON_DEVICE,
    /**
     * Android only: once an AI service has been configured, use it for reader
     * translations even though [providerMode] still reads ON_DEVICE.
     *
     * ML Kit translates a Japanese novel sentence into something 「几乎不可用」,
     * and the reader who has already entered an API key has said what they
     * would rather read. Leaving [providerMode] alone rather than rewriting it
     * on their behalf keeps the on-device model as the offline fallback and
     * keeps the setting screen honest about what they picked; turning this off
     * pins the reader back to the model on the phone.
     */
    val preferAIWhenConfigured: Boolean = true,
    val directProvider: DirectAIProvider = DirectAIProvider.DEEPSEEK,
    val directEndpoint: String = DirectAIProvider.DEEPSEEK.defaultEndpoint,
    val directModel: String = DirectAIProvider.DEEPSEEK.defaultModel,
    val backendEndpoint: String = "",
    val backendModel: String = "",
    val sourceChoice: TranslationSourceChoice = TranslationSourceChoice.AUTOMATIC,
    val targetLanguage: LanguageCode = LanguageCode.CHINESE_SIMPLIFIED,
    val quickTranslationEnabled: Boolean = true,
    val quickTranslationUnit: QuickTranslationUnit = QuickTranslationUnit.SENTENCE,
    val disablesTapPageTurnsDuringQuickTranslation: Boolean = true,
    val displayMode: TranslationDisplayMode = TranslationDisplayMode.NEAR_SELECTION,
    val translationHapticsEnabled: Boolean = true,
    val automaticRetryEnabled: Boolean = true,
    val fallbackMode: TranslationFallbackMode = TranslationFallbackMode.NONE,
    val translationPromptTemplate: String = DEFAULT_TRANSLATION_PROMPT,
    val grammarAnalysisPromptTemplate: String = DEFAULT_GRAMMAR_PROMPT,
    val directApiKeyPresent: Boolean = false,
    val backendAccessTokenPresent: Boolean = false
) {
    companion object {
        const val DEFAULT_TRANSLATION_PROMPT =
            "你是一名专业文学翻译。请把选中的 {source_language} 原文准确、自然地翻译成 {target_language}。保留语气、段落与专有名词，只输出译文，不要添加标题、解释、引号或备选版本。上下文只用于消歧，不要额外翻译上下文。"
        const val DEFAULT_GRAMMAR_PROMPT =
            "你是一名精确的语言教师，但回答必须简洁。请用 {response_language} 解释选中的 {source_language} 内容，上下文只用于消歧。句子按以下格式回答：句意；句子主干；关键语法。日语优先助词、活用和省略；英语优先从句、时态、语态和修饰。只解释最影响理解的 1–3 点，不写文学评论，通常控制在 220 个中文字以内。"
    }
}

fun TranslationPreferences.renderedPrompt(source: LanguageCode?): String =
    translationPromptTemplate
        .ifBlank { TranslationPreferences.DEFAULT_TRANSLATION_PROMPT }
        .replace("{source_language}", source?.displayName() ?: "自动识别的语言")
        .replace("{target_language}", targetLanguage.displayName())
        .replace("{response_language}", targetLanguage.displayName())

fun TranslationPreferences.renderedGrammarPrompt(source: LanguageCode?): String =
    grammarAnalysisPromptTemplate
        .ifBlank { TranslationPreferences.DEFAULT_GRAMMAR_PROMPT }
        .replace("{source_language}", source?.displayName() ?: "自动识别的语言")
        .replace("{target_language}", targetLanguage.displayName())
        .replace("{response_language}", LanguageCode.CHINESE_SIMPLIFIED.displayName())

fun LanguageCode.displayName(): String = when (this) {
    LanguageCode.JAPANESE -> "日语"
    LanguageCode.ENGLISH -> "英语"
    LanguageCode.CHINESE_SIMPLIFIED -> "简体中文"
}
