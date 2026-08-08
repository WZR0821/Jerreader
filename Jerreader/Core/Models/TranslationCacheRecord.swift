import Foundation
import SwiftData

@Model
final class TranslationCacheRecord {
    @Attribute(.unique) var cacheKey: String
    var sourceText: String
    var translatedText: String
    var sourceLanguageRawValue: String
    var targetLanguageRawValue: String
    var providerIdentifier: String
    var providerVersion: String
    var createdAt: Date
    var lastAccessedAt: Date
    var accessCount: Int

    init(
        cacheKey: String,
        sourceText: String,
        translatedText: String,
        sourceLanguage: LanguageCode,
        targetLanguage: LanguageCode,
        providerIdentifier: String,
        providerVersion: String,
        createdAt: Date = Date(),
        lastAccessedAt: Date = Date(),
        accessCount: Int = 1
    ) {
        self.cacheKey = cacheKey
        self.sourceText = sourceText
        self.translatedText = translatedText
        sourceLanguageRawValue = sourceLanguage.rawValue
        targetLanguageRawValue = targetLanguage.rawValue
        self.providerIdentifier = providerIdentifier
        self.providerVersion = providerVersion
        self.createdAt = createdAt
        self.lastAccessedAt = lastAccessedAt
        self.accessCount = accessCount
    }
}
