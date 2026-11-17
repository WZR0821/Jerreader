import Foundation

/// The bounded reading questions the in-reader AI lens offers.
///
/// Mirrors shared/src/commonMain/kotlin/com/jerreader/shared/translation/ReadingLens.kt;
/// the rendered prompt is checked against shared/parity/reading_companion.json.
enum AIReadingLensMode: String, CaseIterable, Codable, Sendable, Identifiable {
    case comprehension
    case contextMeaning = "context_meaning"
    case reference
    case grammar

    var id: String { rawValue }

    var title: String {
        switch self {
        case .comprehension: "读懂这段"
        case .contextMeaning: "语境词义"
        case .reference: "省略与指代"
        case .grammar: "日语语法"
        }
    }

    var detail: String {
        switch self {
        case .comprehension: "说明这段在当前语境中真正表达了什么"
        case .contextMeaning: "解释关键词为何在这里采用这个含义"
        case .reference: "还原省略成分、代词与人物指向"
        case .grammar: "拆解句子结构、修饰关系与重点语法"
        }
    }
}

/// How a lens request is asked; part of the request so each variant caches apart.
struct ReadingLensOptions: Equatable, Sendable {
    var mode: AIReadingLensMode
    var companionMode: ReadingCompanionMode = .fiction
    var assistLevel: ReadingAssistLevel = .guided
    var adaptiveInstruction: String?
    var focusTerms: [String] = []

    init(
        mode: AIReadingLensMode,
        companionMode: ReadingCompanionMode = .fiction,
        assistLevel: ReadingAssistLevel = .guided,
        adaptiveInstruction: String? = nil,
        focusTerms: [String] = []
    ) {
        self.mode = mode
        self.companionMode = companionMode
        self.assistLevel = assistLevel
        self.adaptiveInstruction = adaptiveInstruction
        self.focusTerms = focusTerms
    }

    init(mode: AIReadingLensMode, companionMode: ReadingCompanionMode, assist: AdaptiveReadingAssist) {
        self.init(
            mode: mode,
            companionMode: companionMode,
            assistLevel: assist.level,
            adaptiveInstruction: assist.instruction,
            focusTerms: assist.focusTerms
        )
    }

    /// Everything that changes the answer, for the explanation cache key.
    var cacheIdentity: String {
        [
            "lens-v1",
            mode.rawValue,
            companionMode.rawValue,
            assistLevel.rawValue,
            adaptiveInstruction ?? "",
            focusTerms.joined(separator: "\u{001D}"),
        ].joined(separator: "\u{001E}")
    }
}

/// The shared safety and output contract of the reading lens. The provider is
/// still the user's own configured model; every mode gets the same
/// source-bounded, no-spoiler constraints.
enum AIReadingLensPromptPolicy {
    static func rendered(
        _ options: ReadingLensOptions,
        sourceLanguage: LanguageCode?,
        responseLanguage: LanguageCode,
        grammarPrompt: String? = nil
    ) -> String {
        let task: String
        switch options.mode {
        case .comprehension:
            task = "先给出一句自然释义，再说明语气、言外之意以及理解这段所需的最少信息。"
        case .contextMeaning:
            task = "找出最影响理解的词或短语，说明其基本义、当前语境义以及采用该义的文本证据。"
        case .reference:
            task = "识别省略的主语、宾语、代词或人物指向；逐项给出依据，不能确认时明确写出不确定。"
        case .grammar:
            let configured = ReadingCompanionText.trimmed(grammarPrompt ?? "")
            task = configured.isEmpty
                ? "拆解句子主干、修饰关系、活用与重点日语语法，并说明这些结构如何影响句意。"
                : configured
        }
        let structure = switch options.companionMode {
        case .fiction: "这是小说伴读：优先辨认人物、动机、语气、指代和情节因果。"
        case .nonfiction: "这是非虚构伴读：优先辨认概念、定义、论点、证据和推理关系。"
        }
        let instruction = ReadingCompanionText.trimmed(options.adaptiveInstruction ?? "")
        let personalization = instruction.isEmpty
            ? "采用\(options.assistLevel.title)，帮助用户自己回到原文验证。"
            : instruction
        var focusTerms: [String] = []
        for term in options.focusTerms.map(ReadingCompanionText.trimmed)
        where !term.isEmpty && !focusTerms.contains(term) {
            focusTerms.append(term)
        }
        let focus = focusTerms.isEmpty
            ? nil
            : "用户近期薄弱点：\(focusTerms.prefix(5).joined(separator: "、"))。只在当前片段确有证据时提醒。"
        let source = sourceLanguage.map(languageName) ?? "自动识别"
        return [
            "你是 Jerreader 的 AI 阅读伴侣。请使用\(languageName(responseLanguage))回答，原文语言为\(source)。",
            task,
            structure,
            personalization,
            focus,
            "只允许依据用户提供的 SELECTED TEXT 和 READING CONTEXT。不要声称读过整本书，不要补写未提供的情节、人物设定或后续内容，不要剧透。证据不足时写“仅凭当前片段无法确认”，并列出最可能的解释。优先帮助用户回到原文自行理解，回答保持清晰、简洁、可核对。",
        ].compactMap { $0 }.joined(separator: "\n")
    }

    private static func languageName(_ language: LanguageCode) -> String {
        switch language {
        case .simplifiedChinese: "简体中文"
        case .english: "英语"
        case .japanese: "日语"
        }
    }
}
