import Foundation

enum TranslationProviderChoice: String, CaseIterable, Codable, Identifiable, Sendable {
    case apple
    case directAPI
    case backendProxy

    var id: Self { self }

    var title: String {
        switch self {
        case .apple: return "Apple 系统翻译"
        case .directAPI: return "AI 大模型"
        case .backendProxy: return "AI 代理"
        }
    }

    var shortTitle: String {
        switch self {
        case .apple: return "Apple"
        case .directAPI: return "AI 大模型"
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
        case .directAPI: return "AI 大模型"
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
    // Raw values are stored in settings and keychain accounts; keep them.
    case anthropic
    case openAI
    case deepSeek
    case qwen
    case doubao
    case kimi
    case zhipu
    case minimax
    case gemini
    case openAICompatible
    /// A ChatGPT account signed in with Sign in with ChatGPT: its plan pays,
    /// no API key. Added in 2.0; listed on the 套餐 page, not with the presets.
    case chatGPTPlan

    var id: Self { self }

    var title: String {
        switch self {
        case .anthropic: return "Claude"
        case .openAI: return "ChatGPT"
        case .deepSeek: return "DeepSeek"
        case .qwen: return "通义千问"
        case .doubao: return "豆包"
        case .kimi: return "Kimi"
        case .zhipu: return "智谱 GLM"
        case .minimax: return "MiniMax"
        case .gemini: return "Gemini"
        case .openAICompatible: return "自定义 API"
        case .chatGPTPlan: return "ChatGPT 套餐"
        }
    }

    /// The asset name is intentionally stable so properly licensed vendor art
    /// can be supplied locally without changing settings code or downloading
    /// remote images. The UI always has a brand-neutral text fallback.
    var localLogoAssetName: String {
        switch self {
        case .anthropic: return "ProviderLogoAnthropic"
        case .openAI: return "ProviderLogoOpenAI"
        case .deepSeek: return "ProviderLogoDeepSeek"
        case .qwen: return "ProviderLogoQwen"
        case .doubao: return "ProviderLogoDoubao"
        case .kimi: return "ProviderLogoKimi"
        case .zhipu: return "ProviderLogoZhipu"
        case .minimax: return "ProviderLogoMiniMax"
        case .gemini: return "ProviderLogoGemini"
        case .openAICompatible: return "ProviderLogoCompatible"
        case .chatGPTPlan: return "ProviderLogoChatGPTPlan"
        }
    }

    /// The letter on the provider's mark when no licensed logo is bundled.
    var fallbackMark: String {
        switch self {
        case .anthropic: return "C"
        case .openAI: return "G"
        case .deepSeek: return "D"
        case .qwen: return "通"
        case .doubao: return "豆"
        case .kimi: return "K"
        case .zhipu: return "智"
        case .minimax: return "M"
        case .gemini: return "✦"
        case .openAICompatible: return "API"
        case .chatGPTPlan: return "G+"
        }
    }

    /// The model a newly added provider uses first.
    var defaultModel: String {
        modelPresets.first?.id ?? ""
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
    /// The full request URL; for Gemini's own format, the address the model path is added to.
    let endpoint: URL
    let model: String
    let apiKey: String
    let style: AIAPIStyle
    /// `nil` is 自动.
    let reasoning: AIReasoningEffort?
    let translationPromptTemplate: String
    let grammarAnalysisPromptTemplate: String

    init(
        provider: DirectAIProviderChoice,
        endpoint: URL,
        model: String,
        apiKey: String,
        style: AIAPIStyle? = nil,
        reasoning: AIReasoningEffort? = nil,
        translationPromptTemplate: String = AIPromptTemplateDefaults.translation,
        grammarAnalysisPromptTemplate: String =
            AIPromptTemplateDefaults.grammarAnalysis
    ) {
        self.provider = provider
        self.endpoint = endpoint
        self.model = model
        self.apiKey = apiKey
        self.style = style ?? provider.legacyStyle
        self.reasoning = reasoning
        self.translationPromptTemplate = translationPromptTemplate
        self.grammarAnalysisPromptTemplate = grammarAnalysisPromptTemplate
    }
}
