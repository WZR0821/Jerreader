import CryptoKit
import Foundation
import SwiftData

@MainActor
final class ReadingAnnotationStore {
    private let modelContext: ModelContext

    init(modelContext: ModelContext) {
        self.modelContext = modelContext
    }

    func annotations(for bookID: UUID) -> [ReadingAnnotationRecord] {
        let descriptor = FetchDescriptor<ReadingAnnotationRecord>(
            predicate: #Predicate { $0.bookID == bookID },
            sortBy: [
                SortDescriptor(\.progress),
                SortDescriptor(\.createdAt),
            ]
        )
        return (try? modelContext.fetch(descriptor)) ?? []
    }

    func annotation(
        bookID: UUID,
        locatorJSON: String,
        selectedText: String
    ) -> ReadingAnnotationRecord? {
        let key = Self.key(
            bookID: bookID,
            locatorJSON: locatorJSON,
            selectedText: selectedText
        )
        let descriptor = FetchDescriptor<ReadingAnnotationRecord>(
            predicate: #Predicate { $0.annotationKey == key }
        )
        if let exact = try? modelContext.fetch(descriptor).first {
            return exact
        }

        // Annotation keys written by an older build can contain the same
        // Locator JSON with different whitespace or object-key ordering.
        // Compare the semantic identity as a compatibility fallback so an
        // existing highlight opens for editing instead of being duplicated.
        let bookDescriptor = FetchDescriptor<ReadingAnnotationRecord>(
            predicate: #Predicate { $0.bookID == bookID }
        )
        guard let records = try? modelContext.fetch(bookDescriptor) else {
            return nil
        }
        let locatorIdentity = Self.canonicalLocator(locatorJSON)
        let textIdentity = Self.normalizedIdentityText(selectedText)
        return records.first {
            Self.canonicalLocator($0.locatorJSON) == locatorIdentity
                && Self.normalizedIdentityText($0.selectedText) == textIdentity
        }
    }

    @discardableResult
    func save(
        book: BookRecord,
        locatorJSON: String,
        anchorJSON: String?,
        selectedText: String,
        noteText: String,
        color: ReadingAnnotationColor,
        chapterTitle: String,
        progress: Double
    ) throws -> ReadingAnnotationRecord {
        let displayText = Self.normalizedDisplayText(selectedText)
        let normalizedText = Self.normalizedIdentityText(displayText)
        let normalizedLocator = locatorJSON.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        guard !normalizedText.isEmpty, !normalizedLocator.isEmpty
        else {
            throw ReadingAnnotationError.invalidSelection
        }

        let key = Self.key(
            bookID: book.id,
            locatorJSON: normalizedLocator,
            selectedText: normalizedText
        )
        let record: ReadingAnnotationRecord
        if let existing = annotation(
            bookID: book.id,
            locatorJSON: normalizedLocator,
            selectedText: normalizedText
        ) {
            // Migrate a legacy, non-canonical key while the record is already
            // being edited. This avoids a separate store migration.
            existing.annotationKey = key
            existing.bookTitle = book.title
            existing.locatorJSON = normalizedLocator
            existing.anchorJSON = anchorJSON ?? existing.anchorJSON
            existing.selectedText = displayText
            existing.noteText = Self.normalizedUserText(noteText)
            existing.color = color
            existing.chapterTitle = Self.normalizedChapterTitle(
                chapterTitle,
                fallback: book.title
            )
            existing.progress = ReadingAnnotationRecord.clampedProgress(progress)
            existing.updatedAt = Date()
            record = existing
        } else {
            record = ReadingAnnotationRecord(
                annotationKey: key,
                bookID: book.id,
                bookTitle: book.title,
                locatorJSON: normalizedLocator,
                anchorJSON: anchorJSON,
                selectedText: displayText,
                noteText: Self.normalizedUserText(noteText),
                color: color,
                chapterTitle: Self.normalizedChapterTitle(
                    chapterTitle,
                    fallback: book.title
                ),
                progress: progress
            )
            modelContext.insert(record)
        }
        try modelContext.save()
        return record
    }

    func delete(_ annotation: ReadingAnnotationRecord) throws {
        modelContext.delete(annotation)
        try modelContext.save()
    }

    func deleteAll(for bookID: UUID) throws {
        let descriptor = FetchDescriptor<ReadingAnnotationRecord>(
            predicate: #Predicate { $0.bookID == bookID }
        )
        for annotation in try modelContext.fetch(descriptor) {
            modelContext.delete(annotation)
        }
        try modelContext.save()
    }

    nonisolated static func key(
        bookID: UUID,
        locatorJSON: String,
        selectedText: String
    ) -> String {
        let identity = [
            bookID.uuidString.lowercased(),
            canonicalLocator(locatorJSON),
            normalizedIdentityText(selectedText),
        ].joined(separator: "\u{001F}")
        let digest = SHA256.hash(data: Data(identity.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    nonisolated private static func canonicalLocator(_ locatorJSON: String) -> String {
        let trimmed = locatorJSON.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = trimmed.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              JSONSerialization.isValidJSONObject(object),
              let canonicalData = try? JSONSerialization.data(
                  withJSONObject: object,
                  options: [.sortedKeys]
              ),
              let canonical = String(data: canonicalData, encoding: .utf8)
        else {
            return trimmed
        }
        return canonical
    }

    nonisolated private static func normalizedIdentityText(_ text: String) -> String {
        TranslationCacheStore.normalizedText(text)
    }

    /// User-visible excerpts keep paragraph boundaries and intentional inner
    /// spacing. Cache-key normalization is deliberately separate because it
    /// collapses all whitespace.
    nonisolated private static func normalizedDisplayText(_ text: String) -> String {
        text
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .precomposedStringWithCanonicalMapping
    }

    nonisolated private static func normalizedUserText(_ text: String) -> String {
        normalizedDisplayText(text)
    }

    nonisolated private static func normalizedChapterTitle(
        _ chapterTitle: String,
        fallback: String
    ) -> String {
        let title = normalizedDisplayText(chapterTitle)
        return title.isEmpty ? fallback : title
    }
}

enum ReadingAnnotationError: LocalizedError {
    case invalidSelection

    var errorDescription: String? {
        "当前选区暂时无法保存，请重新选择文字后再试。"
    }
}
