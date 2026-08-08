import Foundation

struct MockTranslationService: TranslationService, ContextExplanationService {
    func translate(
        text: String,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult {
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

    func explain(
        _ request: ContextExplanationRequest
    ) async throws -> ContextExplanationResult {
        let focusedText = request.focusedText.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        guard !focusedText.isEmpty else {
            throw ServiceError.emptyText
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
