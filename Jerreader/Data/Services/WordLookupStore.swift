import Foundation
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

    /// When the word was last looked up, before this lookup is recorded
    /// (2.1.0 §8): `record` moves `lastLookedUpAt` to now.
    static func previousLookup(
        for explanation: WordExplanation,
        in modelContext: ModelContext
    ) -> (count: Int, date: Date)? {
        let key = WordLookupRecord.makeLookupKey(for: explanation)
        var descriptor = FetchDescriptor<WordLookupRecord>(
            predicate: #Predicate { $0.lookupKey == key }
        )
        descriptor.fetchLimit = 1
        guard let existing = try? modelContext.fetch(descriptor).first else { return nil }
        return (existing.lookupCount, existing.lastLookedUpAt)
    }

    /// The lookup page opened from a translation card that already counted
    /// this word (2.1.0 §8): the dictionary's answer goes on the word's
    /// record, found by its key or else by its surface form or lemma,
    /// without counting a second lookup or moving 上次. A record found by
    /// its surface form takes the dictionary's key, so the two stay one
    /// word. A word with no record yet is recorded as usual.
    /// - Returns: the record, and whether this lookup was counted after all.
    @discardableResult
    static func recordWithoutCounting(
        _ explanation: WordExplanation,
        sourceBookID: UUID? = nil,
        sourceBookTitle: String? = nil,
        in modelContext: ModelContext
    ) throws -> (record: WordLookupRecord, counted: Bool) {
        let key = WordLookupRecord.makeLookupKey(for: explanation)
        let languageRawValue = explanation.language.rawValue
        let descriptor = FetchDescriptor<WordLookupRecord>(
            predicate: #Predicate { $0.languageRawValue == languageRawValue }
        )
        let surface = normalizedTerm(explanation.surfaceForm)
        let found: WordLookupRecord?
        do {
            let records = try modelContext.fetch(descriptor)
            found = records.first { $0.lookupKey == key }
                ?? records.first { normalizedTerm($0.surfaceForm) == surface }
                ?? records.first { $0.lemma.map { normalizedTerm($0) } == surface }
        } catch {
            throw WordLookupStoreError.persistenceFailed
        }
        guard let existing = found else {
            let inserted = try Self.record(
                explanation,
                sourceBookID: sourceBookID,
                sourceBookTitle: sourceBookTitle,
                in: modelContext
            )
            return (inserted, true)
        }
        let count = existing.lookupCount
        existing.recordLookup(
            explanation: explanation,
            sourceBookID: sourceBookID,
            sourceBookTitle: sourceBookTitle,
            at: existing.lastLookedUpAt
        )
        existing.lookupCount = count
        // Looked up by key first: no other record holds `key` when this
        // one was found by its surface form or lemma.
        if existing.lookupKey != key {
            existing.lookupKey = key
        }
        try save(modelContext)
        return (existing, false)
    }

    static func setFavorite(
        _ isFavorite: Bool,
        for record: WordLookupRecord,
        in modelContext: ModelContext
    ) throws {
        record.isFavorite = isFavorite
        if !isFavorite, !record.isInHistory {
            modelContext.delete(record)
        }
        try save(modelContext)
    }

    /// Stars the record a translation card has already counted, found by
    /// its key, without counting another lookup (2.1.0 §8), as Android's
    /// 加入生词本 does.
    /// - Returns: false when no record has that key any more.
    @discardableResult
    static func setFavorite(
        _ isFavorite: Bool,
        forKey key: String,
        in modelContext: ModelContext
    ) throws -> Bool {
        var descriptor = FetchDescriptor<WordLookupRecord>(
            predicate: #Predicate { $0.lookupKey == key }
        )
        descriptor.fetchLimit = 1
        let found: WordLookupRecord?
        do {
            found = try modelContext.fetch(descriptor).first
        } catch {
            throw WordLookupStoreError.persistenceFailed
        }
        guard let found else { return false }
        try setFavorite(isFavorite, for: found, in: modelContext)
        return true
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
