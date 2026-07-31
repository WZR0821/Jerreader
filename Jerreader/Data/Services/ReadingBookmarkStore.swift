import Foundation
@preconcurrency import ReadiumShared
import SwiftData

@MainActor
final class ReadingBookmarkStore {
    private let modelContext: ModelContext

    init(modelContext: ModelContext) {
        self.modelContext = modelContext
    }

    func bookmarks(for bookID: UUID) -> [ReadingBookmarkRecord] {
        let descriptor = FetchDescriptor<ReadingBookmarkRecord>(
            predicate: #Predicate { $0.bookID == bookID },
            sortBy: [SortDescriptor(\.progress), SortDescriptor(\.createdAt)]
        )
        return (try? modelContext.fetch(descriptor)) ?? []
    }

    func isBookmarked(bookID: UUID, locator: Locator) -> Bool {
        let key = Self.key(bookID: bookID, locator: locator)
        let descriptor = FetchDescriptor<ReadingBookmarkRecord>(
            predicate: #Predicate { $0.bookmarkKey == key }
        )
        return ((try? modelContext.fetchCount(descriptor)) ?? 0) > 0
    }

    @discardableResult
    func toggle(
        book: BookRecord,
        locator: Locator,
        chapterTitle: String
    ) throws -> Bool {
        let key = Self.key(bookID: book.id, locator: locator)
        let descriptor = FetchDescriptor<ReadingBookmarkRecord>(
            predicate: #Predicate { $0.bookmarkKey == key }
        )

        if let existing = try modelContext.fetch(descriptor).first {
            modelContext.delete(existing)
            try modelContext.save()
            return false
        }

        guard let locatorJSON = locator.readerJSONString else {
            throw ReadingBookmarkError.invalidLocator
        }
        let text = locator.text.sanitized()
        let excerpt = [text.before, text.highlight, text.after]
            .compactMap { $0 }
            .joined()
            .trimmingCharacters(in: .whitespacesAndNewlines)

        modelContext.insert(
            ReadingBookmarkRecord(
                bookmarkKey: key,
                bookID: book.id,
                bookTitle: book.title,
                locatorJSON: locatorJSON,
                chapterTitle: chapterTitle,
                excerpt: excerpt.isEmpty ? nil : excerpt,
                progress: locator.locations.totalProgression ?? book.lastReadProgress
            )
        )
        try modelContext.save()
        return true
    }

    func delete(_ bookmark: ReadingBookmarkRecord) throws {
        modelContext.delete(bookmark)
        try modelContext.save()
    }

    func deleteAll(for bookID: UUID) throws {
        for bookmark in bookmarks(for: bookID) {
            modelContext.delete(bookmark)
        }
        try modelContext.save()
    }

    nonisolated static func key(bookID: UUID, locator: Locator) -> String {
        let location: String
        if let position = locator.locations.position {
            location = "position:\(position)"
        } else if let progression = locator.locations.totalProgression {
            location = "progress:\(Int((progression * 100_000).rounded()))"
        } else if let progression = locator.locations.progression {
            location = "resource-progress:\(Int((progression * 100_000).rounded()))"
        } else {
            location = "fragments:\(locator.locations.fragments.joined(separator: ","))"
        }
        return "\(bookID.uuidString)|\(locator.href.string)|\(location)"
    }
}

enum ReadingBookmarkError: LocalizedError {
    case invalidLocator

    var errorDescription: String? {
        "当前位置无法保存为书签，请翻到下一页后重试。"
    }
}
