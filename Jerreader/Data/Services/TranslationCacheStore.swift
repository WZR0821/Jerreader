import CryptoKit
import Foundation
import SwiftData

@MainActor
final class TranslationCacheStore {
    nonisolated static let appleProviderIdentifier = "apple-translation"
    nonisolated static let appleProviderVersion = "ios18-v1"

    private let modelContext: ModelContext

    init(modelContext: ModelContext) {
        self.modelContext = modelContext
    }

    func result(
        for text: String,
        contextText: String? = nil,
        sourceLanguage: LanguageCode,
        targetLanguage: LanguageCode,
        providerIdentifier: String = "apple-translation",
        providerVersion: String = "ios18-v1"
    ) -> TranslationResult? {
        let sourceText = Self.normalizedText(text)
        let key = Self.cacheKey(
            text: sourceText,
            contextText: contextText,
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            providerIdentifier: providerIdentifier,
            providerVersion: providerVersion
        )
        let descriptor = FetchDescriptor<TranslationCacheRecord>(
            predicate: #Predicate { record in
                record.cacheKey == key
            }
        )

        guard let record = try? modelContext.fetch(descriptor).first,
              let cachedSource = LanguageCode(rawValue: record.sourceLanguageRawValue),
              let cachedTarget = LanguageCode(rawValue: record.targetLanguageRawValue)
        else {
            return nil
        }

        let rawResult = TranslationResult(
            sourceText: record.sourceText,
            translatedText: record.translatedText,
            sourceLanguage: cachedSource,
            targetLanguage: cachedTarget,
            providerIdentifier: record.providerIdentifier,
            providerVersion: record.providerVersion,
            isFromCache: true
        )
        guard let result = TranslationOutputPolicy.validated(rawResult) else {
            // Heal cache rows written by older builds instead of repeatedly
            // presenting an empty success card for the same sentence.
            modelContext.delete(record)
            try? modelContext.save()
            return nil
        }

        record.lastAccessedAt = Date()
        record.accessCount += 1
        try? modelContext.save()
        return result
    }

    @discardableResult
    func store(_ result: TranslationResult, contextText: String? = nil) -> Bool {
        guard let result = TranslationOutputPolicy.validated(result) else {
            return false
        }
        let sourceText = Self.normalizedText(result.sourceText)
        let key = Self.cacheKey(
            text: sourceText,
            contextText: contextText,
            sourceLanguage: result.sourceLanguage,
            targetLanguage: result.targetLanguage,
            providerIdentifier: result.providerIdentifier,
            providerVersion: result.providerVersion
        )
        let descriptor = FetchDescriptor<TranslationCacheRecord>(
            predicate: #Predicate { record in
                record.cacheKey == key
            }
        )

        if let record = try? modelContext.fetch(descriptor).first {
            record.sourceText = sourceText
            record.translatedText = result.translatedText
            record.lastAccessedAt = Date()
            record.accessCount += 1
        } else {
            modelContext.insert(
                TranslationCacheRecord(
                    cacheKey: key,
                    sourceText: sourceText,
                    translatedText: result.translatedText,
                    sourceLanguage: result.sourceLanguage,
                    targetLanguage: result.targetLanguage,
                    providerIdentifier: result.providerIdentifier,
                    providerVersion: result.providerVersion
                )
            )
        }
        do {
            try modelContext.save()
            return true
        } catch {
            return false
        }
    }

    nonisolated static func normalizedText(_ text: String) -> String {
        text
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .replacingOccurrences(
                of: #"\s+"#,
                with: " ",
                options: .regularExpression
            )
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .precomposedStringWithCanonicalMapping
    }

    nonisolated static func cacheKey(
        text: String,
        contextText: String? = nil,
        sourceLanguage: LanguageCode,
        targetLanguage: LanguageCode,
        providerIdentifier: String = "apple-translation",
        providerVersion: String = "ios18-v1"
    ) -> String {
        var components = [
            providerIdentifier,
            providerVersion,
            sourceLanguage.rawValue,
            targetLanguage.rawValue,
            normalizedText(text)
        ]
        if let contextText {
            let context = normalizedText(contextText)
            if !context.isEmpty, context != normalizedText(text) {
                components.append("context:\(context)")
            }
        }
        let value = components.joined(separator: "\u{001F}")
        let digest = SHA256.hash(data: Data(value.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }
}
