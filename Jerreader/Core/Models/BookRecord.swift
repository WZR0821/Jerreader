import Foundation
import SwiftData

@Model
final class BookRecord: Identifiable {
    @Attribute(.unique) var id: UUID
    var publicationIdentifier: String?
    var title: String
    var author: String
    var language: String?
    var sourceFormat: String = BookFormat.epub.rawValue
    var localFileName: String
    var coverFileName: String?
    @Attribute(.unique) var fileFingerprint: String
    var importedAt: Date
    var lastOpenedAt: Date?
    var lastReadLocatorJSON: String?
    var lastReadProgress: Double = 0
    var totalReadingSeconds: Double = 0
    var category: String = ""
    var series: String = ""
    var tags: [String] = []
    var readerFontSize: Double = 1.0
    var readerTheme: String = "light"
    var readerScrollEnabled: Bool = false
    var readerFontFamily: String = "serif"
    var readerLineHeight: Double = 1.4
    var readerParagraphSpacing: Double = 0.0
    var readerPageMargins: Double = 1.0
    var readerCustomBackgroundHex: String = ""
    var readerCustomSelectionColorHex: String = ""
    var readerSpeechRate: Double = 1.0
    // Keep the declaration default aligned with the initializer. SwiftData can
    // materialize this value without going through our initializer when a new
    // schema property is added to an older store; using `publication` here
    // prevents an unexplained horizontal override on only some existing books.
    var readerTextOrientation: String = ReaderTextOrientationChoice.publication.rawValue
    /// Restricts automatic PDF sentence/paragraph recognition to the tapped
    /// paper column. Native PDFKit handle selection remains untouched.
    var readerPDFPaperModeEnabled: Bool = false
    /// Cached original layout inferred from EPUB metadata and CSS. This stays
    /// separate from the user's horizontal/original choice so toggling can
    /// always restore a metadata-poor vertical publication deterministically.
    var readerDetectedPublicationLayout: String = ""

    init(
        id: UUID = UUID(),
        publicationIdentifier: String? = nil,
        title: String,
        author: String,
        language: String? = nil,
        sourceFormat: String = BookFormat.epub.rawValue,
        localFileName: String,
        coverFileName: String? = nil,
        fileFingerprint: String,
        importedAt: Date = Date(),
        lastOpenedAt: Date? = nil,
        lastReadLocatorJSON: String? = nil,
        lastReadProgress: Double = 0,
        totalReadingSeconds: Double = 0,
        category: String = "",
        series: String = "",
        tags: [String] = [],
        readerFontSize: Double = 1.0,
        readerTheme: String = "light",
        readerScrollEnabled: Bool = false,
        readerFontFamily: String = "serif",
        readerLineHeight: Double = 1.4,
        readerParagraphSpacing: Double = 0.0,
        readerPageMargins: Double = 1.0,
        readerCustomBackgroundHex: String = "",
        readerCustomSelectionColorHex: String = "",
        readerSpeechRate: Double = 1.0,
        readerTextOrientation: String = ReaderTextOrientationChoice.publication.rawValue,
        readerPDFPaperModeEnabled: Bool = false,
        readerDetectedPublicationLayout: String = ""
    ) {
        self.id = id
        self.publicationIdentifier = publicationIdentifier
        self.title = title
        self.author = author
        self.language = language
        self.sourceFormat = sourceFormat
        self.localFileName = localFileName
        self.coverFileName = coverFileName
        self.fileFingerprint = fileFingerprint
        self.importedAt = importedAt
        self.lastOpenedAt = lastOpenedAt
        self.lastReadLocatorJSON = lastReadLocatorJSON
        self.lastReadProgress = min(max(lastReadProgress, 0), 1)
        self.totalReadingSeconds = max(totalReadingSeconds, 0)
        self.category = category.trimmingCharacters(in: .whitespacesAndNewlines)
        self.series = series.trimmingCharacters(in: .whitespacesAndNewlines)
        self.tags = Self.normalizedTags(tags)
        self.readerFontSize = readerFontSize
        self.readerTheme = readerTheme
        self.readerScrollEnabled = readerScrollEnabled
        self.readerFontFamily = readerFontFamily
        self.readerLineHeight = readerLineHeight
        self.readerParagraphSpacing = readerParagraphSpacing
        self.readerPageMargins = readerPageMargins
        self.readerCustomBackgroundHex =
            ReaderCustomBackground.normalizedHex(readerCustomBackgroundHex) ?? ""
        self.readerCustomSelectionColorHex =
            ReaderCustomBackground.normalizedHex(readerCustomSelectionColorHex) ?? ""
        self.readerSpeechRate = min(max(readerSpeechRate, 0.5), 2.0)
        self.readerTextOrientation = readerTextOrientation
        self.readerPDFPaperModeEnabled = readerPDFPaperModeEnabled
        self.readerDetectedPublicationLayout = readerDetectedPublicationLayout
    }

    func updateOrganization(
        category: String,
        series: String? = nil,
        tags: [String]
    ) {
        self.category = category.trimmingCharacters(in: .whitespacesAndNewlines)
        if let series {
            self.series = series.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        self.tags = Self.normalizedTags(tags)
    }

    static func normalizedTags(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.compactMap { value in
            let tag = value.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !tag.isEmpty else { return nil }
            let key = tag.folding(
                options: [.caseInsensitive, .diacriticInsensitive],
                locale: .current
            )
            guard seen.insert(key).inserted else { return nil }
            return tag
        }
    }
}
