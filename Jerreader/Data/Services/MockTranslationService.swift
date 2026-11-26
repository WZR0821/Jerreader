import Foundation

struct MockTranslationService: StreamingTranslationService, StreamingContextExplanationService, AIActionService {
    /// 直译's fixed answer: the two lines the instruction asks for.
    static let literalText = "直译：这是 Mock 直译结果，按原文语序逐词对应。\n差别：Mock 直译保留原文语序，现有译文读起来更自然。"
    /// 追问's fixed answer.
    static let followUpText = "Mock 追问回答：先给结论，再指出原文依据。"
    /// 读懂's fixed answer, before the selected sentence.
    static let comprehensionText = "Mock 读懂：这段话的大意已在离线环境中给出。"
    /// 指代's fixed answer, before the selected sentence.
    static let referenceText = "Mock 指代：句中省略的主语指前文的人物。"
    /// The pieces a streamed answer arrives in.
    static let streamedPieceCount = 4

    /// Whether answers arrive in `streamedPieceCount` pieces, as a streaming
    /// model's would (2.1.0); off, each answer comes whole.
    var streamsPartialText = false
    /// The pause before each piece.
    var pieceDelay: Duration = .milliseconds(80)

    init(streamsPartialText: Bool = false, pieceDelay: Duration = .milliseconds(80)) {
        self.streamsPartialText = streamsPartialText
        self.pieceDelay = pieceDelay
    }

    func translate(
        text: String,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult {
        try await translate(
            text: text,
            context: nil,
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            onPartialText: nil
        )
    }

    func translate(
        text: String,
        context: String?,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult {
        try await translate(
            text: text,
            context: context,
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            onPartialText: nil
        )
    }

    func translate(
        text: String,
        context: String?,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode,
        onPartialText: AIPartialTextHandler?
    ) async throws -> TranslationResult {
        let result = try wholeTranslation(
            text: text, sourceLanguage: sourceLanguage, targetLanguage: targetLanguage
        )
        try await stream(result.translatedText, to: onPartialText)
        return result
    }

    func explain(
        _ request: ContextExplanationRequest
    ) async throws -> ContextExplanationResult {
        try await explain(request, onPartialText: nil)
    }

    func explain(
        _ request: ContextExplanationRequest,
        onPartialText: AIPartialTextHandler?
    ) async throws -> ContextExplanationResult {
        let result = try wholeExplanation(request)
        try await stream(result.explanation, to: onPartialText)
        return result
    }

    /// 直译 and 追问: a fixed answer for each, so UI tests know what to wait for.
    func respond(
        to request: AIActionRequest,
        onPartialText: AIPartialTextHandler?
    ) async throws -> AICompletion {
        guard request.conversation.contains(where: { $0.role == .user }) else {
            throw ServiceError.emptyText
        }
        let text = request.action == .literal ? Self.literalText : Self.followUpText
        try await stream(text, to: onPartialText)
        return AICompletion(text: text)
    }

    /// Hands `text` on in pieces, pausing before each; nothing when not streaming.
    private func stream(_ text: String, to handler: AIPartialTextHandler?) async throws {
        guard streamsPartialText, let handler else { return }
        let characters = Array(text)
        let size = max(1, (characters.count + Self.streamedPieceCount - 1) / Self.streamedPieceCount)
        var end = 0
        while end < characters.count {
            try await Task.sleep(for: pieceDelay)
            end = min(characters.count, end + size)
            await handler(String(characters[..<end]))
        }
    }

    private func wholeTranslation(
        text: String,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) throws -> TranslationResult {
        let normalizedText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedText.isEmpty else {
            throw ServiceError.emptyText
        }
        guard normalizedText.count <= 2_000 else {
            throw ServiceError.textTooLong
        }

        let detectedLanguage = sourceLanguage ?? detectLanguage(in: normalizedText)
        guard LanguageCode.allCases.contains(detectedLanguage),
              LanguageCode.allCases.contains(targetLanguage),
              detectedLanguage != targetLanguage
        else {
            throw ServiceError.unsupportedLanguage
        }

        let knownTranslations: [String: String] = [
            "ja|zh-Hans|彼は静かに本を閉じ、窓の外を見た。": "他静静地合上书，望向窗外。",
            "en|zh-Hans|The evening light faded behind the hills.": "暮色渐渐隐没在群山之后。",
            "zh-Hans|en|他打开了窗户。": "He opened the window.",
            "zh-Hans|ja|他打开了窗户。": "彼は窓を開けた。",
        ]
        let key = "\(detectedLanguage.rawValue)|\(targetLanguage.rawValue)|\(normalizedText)"

        return TranslationResult(
            sourceText: normalizedText,
            translatedText: knownTranslations[key] ?? "这是 Mock 翻译结果，用于离线开发与界面测试。",
            sourceLanguage: detectedLanguage,
            targetLanguage: targetLanguage,
            providerIdentifier: "mock",
            providerVersion: "1.0",
            isFromCache: false
        )
    }

    private func wholeExplanation(
        _ request: ContextExplanationRequest
    ) throws -> ContextExplanationResult {
        let focusedText = request.focusedText.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        guard !focusedText.isEmpty else {
            throw ServiceError.emptyText
        }
        // The reading lens behind 读懂 and 指代 has fixed answers of its own.
        switch request.lens?.mode {
        case .comprehension?:
            return ContextExplanationResult(
                explanation: Self.comprehensionText + "\n当前选句：\(focusedText)",
                providerIdentifier: "mock-explanation",
                providerVersion: "1.0"
            )
        case .reference?:
            return ContextExplanationResult(
                explanation: Self.referenceText + "\n当前选句：\(focusedText)",
                providerIdentifier: "mock-explanation",
                providerVersion: "1.0"
            )
        case .grammar?, .contextMeaning?, nil:
            break
        }
        return ContextExplanationResult(
            explanation: """
            句子主干
            Mock 服务会识别主语、谓语与核心补语。

            成分拆解
            当前选句：\(focusedText)

            语法要点
            测试环境保留真实异步流程，但不会访问外网。
            """,
            providerIdentifier: "mock-explanation",
            providerVersion: "1.0"
        )
    }

    private func detectLanguage(in text: String) -> LanguageCode {
        if text.unicodeScalars.contains(where: { (0x3040 ... 0x30FF).contains($0.value) }) {
            return .japanese
        }
        if text.unicodeScalars.contains(where: { (0x4E00 ... 0x9FFF).contains($0.value) }) {
            return .simplifiedChinese
        }
        return .english
    }
}
