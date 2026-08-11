import Foundation

enum TranslationProviderChoice: String, CaseIterable, Codable, Identifiable, Sendable {
    case apple
    case directAPI
    case backendProxy

    var id: Self { self }

    var title: String {
        switch self {
        case .apple: return "Apple 系统翻译"
        case .directAPI: return "AI API（直接）"
        case .backendProxy: return "AI 代理"
        }
    }

    var shortTitle: String {
        switch self {
        case .apple: return "Apple"
        case .directAPI: return "AI API"
        case .backendProxy: return "AI 代理"
        }
    }

    var isNetworkProvider: Bool {
        self != .apple
    }
}

enum TranslationFallbackChoice: String, CaseIterable, Codable, Identifiable, Sendable {
    case none
    case apple
    case directAPI
    case backendProxy

    var id: Self { self }

    var title: String {
        switch self {
        case .none: return "不自动切换"
        case .apple: return "Apple 系统翻译"
        case .directAPI: return "AI API（直接）"
        case .backendProxy: return "AI 代理"
        }
    }

    var provider: TranslationProviderChoice? {
        switch self {
        case .none: return nil
        case .apple: return .apple
        case .directAPI: return .directAPI
        case .backendProxy: return .backendProxy
        }
    }
}

enum DirectAIProviderChoice: String, CaseIterable, Codable, Identifiable, Sendable {
    case openAI
    case anthropic
    case kimi
    case deepSeek
    case gemini
    case openAICompatible

    var id: Self { self }

    var title: String {
        switch self {
        case .openAI: return "GPT"
        case .anthropic: return "Claude"
        case .kimi: return "Kimi"
        case .deepSeek: return "DeepSeek"
        case .gemini: return "Gemini"
        case .openAICompatible: return "其他兼容服务"
        }
    }

    /// The asset name is intentionally stable so properly licensed vendor art
    /// can be supplied locally without changing settings code or downloading
    /// remote images. The UI always has a brand-neutral text fallback.
    var localLogoAssetName: String {
        switch self {
        case .openAI: return "ProviderLogoOpenAI"
        case .anthropic: return "ProviderLogoAnthropic"
        case .kimi: return "ProviderLogoKimi"
        case .deepSeek: return "ProviderLogoDeepSeek"
        case .gemini: return "ProviderLogoGemini"
        case .openAICompatible: return "ProviderLogoCompatible"
        }
    }

    var fallbackMark: String {
        switch self {
        case .openAI: return "GPT"
        case .anthropic: return "C"
        case .kimi: return "K"
        case .deepSeek: return "DS"
        case .gemini: return "G"
        case .openAICompatible: return "API"
        }
    }

    var defaultEndpointText: String {
        switch self {
        case .openAI:
            return "https://api.openai.com/v1/responses"
        case .anthropic:
            return "https://api.anthropic.com/v1/messages"
        case .kimi:
            return "https://api.moonshot.cn/v1/chat/completions"
        case .deepSeek:
            return "https://api.deepseek.com/chat/completions"
        case .gemini:
            return "https://generativelanguage.googleapis.com/v1beta"
        case .openAICompatible:
            return ""
        }
    }

    var defaultModel: String {
        switch self {
        case .openAI: return "gpt-5.6-luna"
        case .anthropic: return "claude-sonnet-5"
        case .kimi: return "kimi-k2.6"
        case .deepSeek: return "deepseek-v4-flash"
        case .gemini: return "gemini-3.5-flash"
        case .openAICompatible: return ""
        }
    }

    var usesCustomEndpoint: Bool {
        self == .openAICompatible
    }

    var keyPlaceholder: String {
        switch self {
        case .openAI: return "OpenAI API Key"
        case .anthropic: return "Claude API Key"
        case .kimi: return "Kimi API Key"
        case .deepSeek: return "DeepSeek API Key"
        case .gemini: return "Gemini API Key"
        case .openAICompatible: return "API Key"
        }
    }
}

enum TranslationDisplayMode: String, CaseIterable, Codable, Identifiable, Sendable {
    case nearSelection
    case topBanner

    var id: Self { self }

    var title: String {
        switch self {
        case .nearSelection: return "跟随选区"
        case .topBanner: return "顶部悬浮"
        }
    }

    var detail: String {
        switch self {
        case .nearSelection: return "译文显示在选中文字附近，自动避开当前选区。"
        case .topBanner: return "译文优先显示在页面上方；靠近顶部选词时会自动避让选区。"
        }
    }
}

enum TranslationSourceLanguageChoice: String, CaseIterable, Codable,
    Identifiable, Sendable
{
    case automatic
    case japanese
    case english
    case simplifiedChinese

    var id: Self { self }

    var title: String {
        switch self {
        case .automatic: return "自动识别"
        case .japanese: return LanguageCode.japanese.displayName
        case .english: return LanguageCode.english.displayName
        case .simplifiedChinese: return LanguageCode.simplifiedChinese.displayName
        }
    }

    var languageCode: LanguageCode? {
        switch self {
        case .automatic: return nil
        case .japanese: return .japanese
        case .english: return .english
        case .simplifiedChinese: return .simplifiedChinese
        }
    }
}

enum AIPromptTemplateDefaults {
    static let translation = """
    你是一名专业文学翻译。请把选中的 {source_language} 原文准确、自然地翻译成 {target_language}。保留语气、段落与专有名词，只输出译文，不要添加标题、解释、引号或备选版本。上下文只用于消歧，不要额外翻译上下文。
    """

    static let legacyGrammarAnalysis = """
    你是一名精确的语言教师。请使用 {response_language} 分析选中的 {source_language} 句子结构。说明句子主干、分句与修饰关系、时态/语态/体，以及能由上下文确认的省略成分。日语重点说明助词、活用和省略；英语重点说明从句、时态、语态和修饰；中文重点说明结构助词与成分关系。若选中的是单词或短语，补充原形、读音、词性、活用、语境义和一个简短例句。保持具体、简洁，不做无关的文学评论。
    """

    static let grammarAnalysis = """
    你是一名精确的语言教师，但回答必须简洁。请用 {response_language} 解释选中的 {source_language} 内容，上下文只用于消歧。

    句子按以下格式回答：
    句意：用一句话说明。
    句子主干：只列核心结构。
    关键语法：只解释最影响理解的 1–3 点。

    日语优先助词、活用和省略；英语优先从句、时态、语态和修饰。不影响理解的知识不展开。若只选中单词或短语，仅给出原形、词性、语境义和必要的活用。不重复原文，不写文学评论；通常控制在 220 个中文字以内，复杂句最多 350 字。
    """

    static func resolvedGrammarAnalysisTemplate(
        storedTemplate: String?
    ) -> String {
        guard let storedTemplate else { return grammarAnalysis }
        return storedTemplate == legacyGrammarAnalysis
            ? grammarAnalysis
            : storedTemplate
    }

    static func rendered(
        _ template: String,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode? = nil,
        responseLanguage: LanguageCode? = nil,
        fallback: String = translation
    ) -> String {
        let normalized = template.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        let resolvedTemplate = normalized.isEmpty ? fallback : normalized
        return resolvedTemplate
            .replacingOccurrences(
                of: "{source_language}",
                with: sourceLanguage?.displayName ?? "自动识别的语言"
            )
            .replacingOccurrences(
                of: "{target_language}",
                with: targetLanguage?.displayName ?? "目标语言"
            )
            .replacingOccurrences(
                of: "{response_language}",
                with: responseLanguage?.displayName
                    ?? targetLanguage?.displayName
                    ?? "简体中文"
            )
    }
}

struct BackendTranslationConfiguration: Equatable, Sendable {
    let endpoint: URL
    let model: String?
    let accessToken: String?
    let translationPromptTemplate: String
    let grammarAnalysisPromptTemplate: String

    init(
        endpoint: URL,
        model: String?,
        accessToken: String?,
        translationPromptTemplate: String = AIPromptTemplateDefaults.translation,
        grammarAnalysisPromptTemplate: String =
            AIPromptTemplateDefaults.grammarAnalysis
    ) {
        self.endpoint = endpoint
        self.model = model
        self.accessToken = accessToken
        self.translationPromptTemplate = translationPromptTemplate
        self.grammarAnalysisPromptTemplate = grammarAnalysisPromptTemplate
    }
}

struct DirectAITranslationConfiguration: Equatable, Sendable {
    let provider: DirectAIProviderChoice
    let endpoint: URL
    let model: String
    let apiKey: String
    let translationPromptTemplate: String
    let grammarAnalysisPromptTemplate: String

    init(
        provider: DirectAIProviderChoice,
        endpoint: URL,
        model: String,
        apiKey: String,
        translationPromptTemplate: String = AIPromptTemplateDefaults.translation,
        grammarAnalysisPromptTemplate: String =
            AIPromptTemplateDefaults.grammarAnalysis
    ) {
        self.provider = provider
        self.endpoint = endpoint
        self.model = model
        self.apiKey = apiKey
        self.translationPromptTemplate = translationPromptTemplate
        self.grammarAnalysisPromptTemplate = grammarAnalysisPromptTemplate
    }
}
