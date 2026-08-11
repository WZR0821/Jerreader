import Foundation
import SwiftData

enum ReadingAnnotationColor: String, CaseIterable, Codable, Identifiable, Sendable {
    case yellow
    case blue
    case mint
    case pink
    case purple

    var id: Self { self }

    var title: String {
        switch self {
        case .yellow: return "琥珀"
        case .blue: return "海蓝"
        case .mint: return "薄荷"
        case .pink: return "珊瑚"
        case .purple: return "紫罗兰"
        }
    }
}

@Model
final class ReadingAnnotationRecord: Identifiable {
    @Attribute(.unique) var id: UUID
    @Attribute(.unique) var annotationKey: String
    var bookID: UUID
    var bookTitle: String
    var locatorJSON: String
    /// Optional document-specific geometry. EPUB uses the Readium locator
    /// directly; PDF stores normalized page rectangles here.
    var anchorJSON: String?
    var selectedText: String
    var noteText: String
    var colorRawValue: String
    var chapterTitle: String
    var progress: Double
    var createdAt: Date
    var updatedAt: Date

    init(
        id: UUID = UUID(),
        annotationKey: String,
        bookID: UUID,
        bookTitle: String,
        locatorJSON: String,
        anchorJSON: String? = nil,
        selectedText: String,
        noteText: String = "",
        color: ReadingAnnotationColor = .yellow,
        chapterTitle: String,
        progress: Double,
        createdAt: Date = Date(),
        updatedAt: Date = Date()
    ) {
        self.id = id
        self.annotationKey = annotationKey
        self.bookID = bookID
        self.bookTitle = bookTitle
        self.locatorJSON = locatorJSON
        self.anchorJSON = anchorJSON
        self.selectedText = selectedText
        self.noteText = noteText
        colorRawValue = color.rawValue
        self.chapterTitle = chapterTitle
        self.progress = Self.clampedProgress(progress)
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    var color: ReadingAnnotationColor {
        get { ReadingAnnotationColor(rawValue: colorRawValue) ?? .yellow }
        set { colorRawValue = newValue.rawValue }
    }

    static func clampedProgress(_ progress: Double) -> Double {
        guard progress.isFinite else { return 0 }
        return min(max(progress, 0), 1)
    }
}
