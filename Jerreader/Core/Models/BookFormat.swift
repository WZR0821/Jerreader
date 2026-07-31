import Foundation

enum BookFormat: String, CaseIterable, Codable, Identifiable, Sendable {
    case epub
    case pdf
    case docx
    case text

    var id: Self { self }

    init?(fileURL: URL) {
        switch fileURL.pathExtension.lowercased() {
        case "epub": self = .epub
        case "pdf": self = .pdf
        case "docx": self = .docx
        case "txt", "text": self = .text
        default: return nil
        }
    }

    var displayName: String {
        switch self {
        case .epub: return "EPUB"
        case .pdf: return "PDF"
        case .docx: return "DOCX"
        case .text: return "TXT"
        }
    }

    var systemImage: String {
        switch self {
        case .epub: return "books.vertical.fill"
        case .pdf: return "doc.richtext.fill"
        case .docx: return "doc.text.fill"
        case .text: return "text.document.fill"
        }
    }

    var usesReflowableReader: Bool {
        self != .pdf
    }

    var storedFileExtension: String {
        switch self {
        case .pdf: return "pdf"
        case .epub, .docx, .text: return "epub"
        }
    }
}

extension BookRecord {
    var format: BookFormat {
        get { BookFormat(rawValue: sourceFormat) ?? .epub }
        set { sourceFormat = newValue.rawValue }
    }
}
