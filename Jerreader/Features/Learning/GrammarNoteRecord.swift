import Foundation
import SwiftData

struct GrammarNoteDraft: Codable, Identifiable, Equatable, Sendable {
    var id = UUID()
    var title: String
    var sentence: String
    var explanation: String
    var language: String
    var tags = ""
    var bookID: UUID?
    var bookTitle: String?
    var locatorJSON: String?
    var updatedAt = Date()

    var isValid: Bool {
        [title, sentence, explanation].allSatisfy { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }
}

@Model
final class GrammarNoteRecord {
    @Attribute(.unique) var id: UUID
    var title: String
    var sentence: String
    var explanation: String
    var language: String
    var tags: String
    var bookID: UUID?
    var bookTitle: String?
    var locatorJSON: String?
    var updatedAt: Date

    init(_ draft: GrammarNoteDraft) {
        id = draft.id; title = draft.title; sentence = draft.sentence
        explanation = draft.explanation; language = draft.language; tags = draft.tags
        bookID = draft.bookID; bookTitle = draft.bookTitle; locatorJSON = draft.locatorJSON
        updatedAt = draft.updatedAt
    }
    var draft: GrammarNoteDraft {
        GrammarNoteDraft(id: id, title: title, sentence: sentence, explanation: explanation,
            language: language, tags: tags, bookID: bookID, bookTitle: bookTitle,
            locatorJSON: locatorJSON, updatedAt: updatedAt)
    }
    func apply(_ draft: GrammarNoteDraft) {
        title = draft.title; sentence = draft.sentence; explanation = draft.explanation
        language = draft.language; tags = draft.tags; updatedAt = Date()
    }
}
