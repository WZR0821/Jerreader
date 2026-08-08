import Foundation
import SwiftData

@Model
final class ReadingBookmarkRecord: Identifiable {
    @Attribute(.unique) var id: UUID
    @Attribute(.unique) var bookmarkKey: String
    var bookID: UUID
    var bookTitle: String
    var locatorJSON: String
    var chapterTitle: String
    var excerpt: String?
    var progress: Double
    var createdAt: Date

    init(
        id: UUID = UUID(),
        bookmarkKey: String,
        bookID: UUID,
        bookTitle: String,
        locatorJSON: String,
        chapterTitle: String,
        excerpt: String? = nil,
        progress: Double,
        createdAt: Date = Date()
    ) {
        self.id = id
        self.bookmarkKey = bookmarkKey
        self.bookID = bookID
        self.bookTitle = bookTitle
        self.locatorJSON = locatorJSON
        self.chapterTitle = chapterTitle
        self.excerpt = excerpt
        self.progress = min(max(progress, 0), 1)
        self.createdAt = createdAt
    }
}
