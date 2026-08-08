import Foundation

protocol TranslationService: Sendable {
    func translate(
        text: String,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult

    /// Translates a focused unit while optionally using nearby text only as
    /// disambiguating context. Services without contextual support fall back
    /// to translating `text`, so the UI never depends on provider details.
    func translate(
        text: String,
        context: String?,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult
}

extension TranslationService {
    func translate(
        text: String,
        context: String?,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode
    ) async throws -> TranslationResult {
        try await translate(
            text: text,
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage
        )
    }
}
