import CryptoKit
import Foundation
import SwiftData

@MainActor
final class TranslationFavoriteStore {
    private let modelContext: ModelContext

    init(modelContext: ModelContext) {
        self.modelContext = modelContext
    }

    func isFavorite(
        bookID: UUID,
        sourceText: String,
        sourceLanguage: LanguageCode,
        targetLanguage: LanguageCode
    ) -> Bool {
        let key = Self.favoriteKey(
            bookID: bookID,
            sourceText: sourceText,
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage
        )
        return record(for: key) != nil
    }

    @discardableResult
    func setFavorite(
        _ isFavorite: Bool,
        bookID: UUID,
        bookTitle: String,
        locatorJSON: String?,
        result: TranslationResult
    ) throws -> Bool {
        let key = Self.favoriteKey(
            bookID: bookID,
            sourceText: result.sourceText,
            sourceLanguage: result.sourceLanguage,
            targetLanguage: result.targetLanguage
        )

        if isFavorite {
            if let record = record(for: key) {
                record.sourceText = result.sourceText
                record.translatedText = result.translatedText
                record.providerIdentifier = result.providerIdentifier
                record.bookTitle = bookTitle
                record.locatorJSON = locatorJSON
                record.updatedAt = Date()
            } else {
                modelContext.insert(
                    TranslationFavoriteRecord(
                        favoriteKey: key,
                        sourceText: result.sourceText,
                        translatedText: result.translatedText,
                        sourceLanguage: result.sourceLanguage,
                        targetLanguage: result.targetLanguage,
                        providerIdentifier: result.providerIdentifier,
                        bookID: bookID,
                        bookTitle: bookTitle,
                        locatorJSON: locatorJSON
                    )
                )
            }
        } else if let record = record(for: key) {
            modelContext.delete(record)
        }

        try modelContext.save()
        return isFavorite
    }

    nonisolated static func favoriteKey(
        bookID: UUID,
        sourceText: String,
        sourceLanguage: LanguageCode,
        targetLanguage: LanguageCode
    ) -> String {
        let value = [
            bookID.uuidString.lowercased(),
            sourceLanguage.rawValue,
            targetLanguage.rawValue,
            TranslationCacheStore.normalizedText(sourceText),
        ].joined(separator: "\u{001F}")
        let digest = SHA256.hash(data: Data(value.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    private func record(for key: String) -> TranslationFavoriteRecord? {
        let descriptor = FetchDescriptor<TranslationFavoriteRecord>(
            predicate: #Predicate { record in
                record.favoriteKey == key
            }
        )
        return try? modelContext.fetch(descriptor).first
    }
}
