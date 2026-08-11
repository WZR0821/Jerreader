import Foundation
import class JerreaderCore.VocabularyStatus
import class JerreaderCore.VocabularyLearningPolicy
import class JerreaderCore.VocabularyReviewRating
import class JerreaderCore.VocabularyReviewScheduler
import SwiftData

enum WordLookupStoreError: LocalizedError, Equatable {
    case persistenceFailed

    var errorDescription: String? {
        "无法保存查词记录，请稍后重试。"
    }
}

@MainActor
enum WordLookupStore {
    static func isFavorite(
        word: String,
        language: LanguageCode,
        in modelContext: ModelContext
    ) -> Bool {
        let probe = WordExplanation(
            surfaceForm: word,
            lemma: nil,
            reading: nil,
            language: language,
            partOfSpeech: nil,
            definitions: [],
            usageNote: nil,
            sentenceContext: nil
        )
        let key = WordLookupRecord.makeLookupKey(for: probe)
        let languageRawValue = language.rawValue
        let descriptor = FetchDescriptor<WordLookupRecord>(
            predicate: #Predicate { $0.languageRawValue == languageRawValue }
        )
        let normalizedWord = normalizedTerm(word)
        return (try? modelContext.fetch(descriptor))?.contains { record in
            guard record.isFavorite else { return false }
            return record.lookupKey == key
                || normalizedTerm(record.surfaceForm) == normalizedWord
                || record.lemma.map { normalizedTerm($0) } == normalizedWord
        } == true
    }

    @discardableResult
    static func record(
        _ explanation: WordExplanation,
        sourceBookID: UUID? = nil,
        sourceBookTitle: String? = nil,
        at date: Date = Date(),
        in modelContext: ModelContext
    ) throws -> WordLookupRecord {
        let key = WordLookupRecord.makeLookupKey(for: explanation)
        var descriptor = FetchDescriptor<WordLookupRecord>(
            predicate: #Predicate { $0.lookupKey == key }
        )
        descriptor.fetchLimit = 1

        do {
            if let existing = try modelContext.fetch(descriptor).first {
                existing.recordLookup(
                    explanation: explanation,
                    sourceBookID: sourceBookID,
                    sourceBookTitle: sourceBookTitle,
                    at: date
                )
                try modelContext.save()
                return existing
            }

            let record = WordLookupRecord(
                explanation: explanation,
                sourceBookID: sourceBookID,
                sourceBookTitle: sourceBookTitle,
                createdAt: date,
                lastLookedUpAt: date
            )
            modelContext.insert(record)
            try modelContext.save()
            return record
        } catch {
            modelContext.rollback()
            throw WordLookupStoreError.persistenceFailed
        }
    }

    static func setFavorite(
        _ isFavorite: Bool,
        for record: WordLookupRecord,
        in modelContext: ModelContext
    ) throws {
        record.isFavorite = isFavorite
        if isFavorite {
            record.vocabularyStatusRawValue = VocabularyLearningPolicy.shared
                .statusAfterSaving(current: record.vocabularyStatus)
                .storageId
        }
        if !isFavorite, !record.isInHistory {
            modelContext.delete(record)
        }
        try save(modelContext)
    }

    static func setVocabularyStatus(
        _ status: VocabularyStatus,
        for record: WordLookupRecord,
        in modelContext: ModelContext
    ) throws {
        record.isFavorite = true
        record.vocabularyStatusRawValue = status.storageId
        try save(modelContext)
    }

    static func review(
        _ rating: VocabularyReviewRating,
        record: WordLookupRecord,
        at date: Date = Date(),
        in modelContext: ModelContext
    ) throws {
        let result = VocabularyReviewScheduler.shared.review(
            reviewCount: Int32(record.reviewCount),
            reviewStage: Int32(record.reviewStage),
            currentIntervalDays: Int32(record.reviewIntervalDays),
            currentLapseCount: Int32(record.reviewLapseCount),
            currentStatus: record.vocabularyStatus,
            rating: rating,
            reviewedAtEpochMillis: Int64(date.timeIntervalSince1970 * 1_000)
        )
        record.isFavorite = true
        record.vocabularyStatusRawValue = result.status.storageId
        record.reviewCount = Int(result.reviewCount)
        record.reviewStage = Int(result.reviewStage)
        record.reviewIntervalDays = Int(result.intervalDays)
        record.reviewLapseCount = Int(result.lapseCount)
        record.lastReviewedAt = Date(
            timeIntervalSince1970: Double(result.lastReviewedAtEpochMillis) / 1_000
        )
        record.nextReviewAt = Date(
            timeIntervalSince1970: Double(result.nextReviewAtEpochMillis) / 1_000
        )
        try save(modelContext)
    }

    static func removeFromHistory(
        _ record: WordLookupRecord,
        in modelContext: ModelContext
    ) throws {
        if record.isFavorite {
            record.isInHistory = false
        } else {
            modelContext.delete(record)
        }
        try save(modelContext)
    }

    static func clearHistory(
        _ records: [WordLookupRecord],
        in modelContext: ModelContext
    ) throws {
        for record in records {
            if record.isFavorite {
                record.isInHistory = false
            } else {
                modelContext.delete(record)
            }
        }
        try save(modelContext)
    }

    private static func save(_ modelContext: ModelContext) throws {
        do {
            try modelContext.save()
        } catch {
            modelContext.rollback()
            throw WordLookupStoreError.persistenceFailed
        }
    }

    private static func normalizedTerm(_ value: String) -> String {
        value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .precomposedStringWithCanonicalMapping
            .lowercased(with: Locale(identifier: "en_US_POSIX"))
    }
}
