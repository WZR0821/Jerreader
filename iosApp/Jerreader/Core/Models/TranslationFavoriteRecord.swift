import Foundation
import SwiftData

@Model
final class TranslationFavoriteRecord: Identifiable {
    @Attribute(.unique) var id: UUID
    @Attribute(.unique) var favoriteKey: String
    var sourceText: String
    var translatedText: String
    var sourceLanguageRawValue: String
    var targetLanguageRawValue: String
    var providerIdentifier: String
    var bookID: UUID?
    var bookTitle: String?
    var locatorJSON: String?
    var createdAt: Date
    var updatedAt: Date

    init(
        id: UUID = UUID(),
        favoriteKey: String,
        sourceText: String,
        translatedText: String,
        sourceLanguage: LanguageCode,
        targetLanguage: LanguageCode,
        providerIdentifier: String,
        bookID: UUID?,
        bookTitle: String?,
        locatorJSON: String?,
        createdAt: Date = Date(),
        updatedAt: Date = Date()
    ) {
        self.id = id
        self.favoriteKey = favoriteKey
        self.sourceText = sourceText
        self.translatedText = translatedText
        sourceLanguageRawValue = sourceLanguage.rawValue
        targetLanguageRawValue = targetLanguage.rawValue
        self.providerIdentifier = providerIdentifier
        self.bookID = bookID
        self.bookTitle = bookTitle
        self.locatorJSON = locatorJSON
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    var sourceLanguage: LanguageCode? {
        LanguageCode(rawValue: sourceLanguageRawValue)
    }

    var targetLanguage: LanguageCode? {
        LanguageCode(rawValue: targetLanguageRawValue)
    }
}
