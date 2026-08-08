import Foundation

protocol LexicalLookupService: Sendable {
    func lookup(
        word: String,
        sentenceContext: String?,
        language: LanguageCode
    ) async throws -> WordExplanation
}

