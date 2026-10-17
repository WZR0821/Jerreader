import Foundation
@preconcurrency import SwiftData

// Frozen persisted properties of the shipping 1.3.5 schema. Keep these unchanged:
// migration must recognize the database written before learning features existed.
enum JerreaderSchemaV1: VersionedSchema {
    static let versionIdentifier = Schema.Version(1, 0, 0)
    static let models: [any PersistentModel.Type] = [
        BookRecord.self,
        WordLookupRecord.self,
        TranslationCacheRecord.self,
        TranslationFavoriteRecord.self,
        ReadingBookmarkRecord.self,
        ReadingAnnotationRecord.self,
    ]

    @Model
    final class BookRecord {
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
        var readerTextOrientation: String = ReaderTextOrientationChoice.publication.rawValue
        var readerPDFPaperModeEnabled: Bool = false
        var readerDetectedPublicationLayout: String = ""
        init() {
            id = UUID()
            title = ""
            author = ""
            localFileName = ""
            fileFingerprint = ""
            importedAt = Date()
        }
    }

    @Model
    final class WordLookupRecord {
        @Attribute(.unique) var id: UUID
        @Attribute(.unique) var lookupKey: String
        var surfaceForm: String
        var lemma: String?
        var reading: String?
        var languageRawValue: String
        var partOfSpeech: String?
        var definitionsData: Data
        var inflectionNote: String?
        var examplesData: Data = Data("[]".utf8)
        var usageNote: String?
        var aiAnalysis: String?
        var aiProviderIdentifier: String?
        var sentenceContext: String?
        var sourceBookID: UUID?
        var sourceBookTitle: String?
        var lookupCount: Int
        var createdAt: Date
        var lastLookedUpAt: Date
        var isFavorite: Bool
        var isInHistory: Bool
        init() {
            id = UUID()
            lookupKey = ""
            surfaceForm = ""
            languageRawValue = ""
            definitionsData = Data()
            lookupCount = 0
            createdAt = Date()
            lastLookedUpAt = Date()
            isFavorite = false
            isInHistory = false
        }
    }

    @Model
    final class TranslationCacheRecord {
        @Attribute(.unique) var cacheKey: String
        var sourceText: String
        var translatedText: String
        var sourceLanguageRawValue: String
        var targetLanguageRawValue: String
        var providerIdentifier: String
        var providerVersion: String
        var createdAt: Date
        var lastAccessedAt: Date
        var accessCount: Int
        init() {
            cacheKey = ""
            sourceText = ""
            translatedText = ""
            sourceLanguageRawValue = ""
            targetLanguageRawValue = ""
            providerIdentifier = ""
            providerVersion = ""
            createdAt = Date()
            lastAccessedAt = Date()
            accessCount = 0
        }
    }

    @Model
    final class TranslationFavoriteRecord {
        @Attribute(.unique) var id: UUID
        @Attribute(.unique) var favoriteKey: String
        var sourceText: String
        var translatedText: String
        var sourceLanguageRawValue: String
        var targetLanguageRawValue: String
        var providerIdentifier: String
        var bookID: UUID?
        var bookTitle: String?
        var locatorJSON: String?
        var createdAt: Date
        var updatedAt: Date
        init() {
            id = UUID()
            favoriteKey = ""
            sourceText = ""
            translatedText = ""
            sourceLanguageRawValue = ""
            targetLanguageRawValue = ""
            providerIdentifier = ""
            createdAt = Date()
            updatedAt = Date()
        }
    }

    @Model
    final class ReadingBookmarkRecord {
        @Attribute(.unique) var id: UUID
        @Attribute(.unique) var bookmarkKey: String
        var bookID: UUID
        var bookTitle: String
        var locatorJSON: String
        var chapterTitle: String
        var excerpt: String?
        var progress: Double
        var createdAt: Date
        init() {
            id = UUID()
            bookmarkKey = ""
            bookID = UUID()
            bookTitle = ""
            locatorJSON = ""
            chapterTitle = ""
            progress = 0
            createdAt = Date()
        }
    }

    @Model
    final class ReadingAnnotationRecord {
        @Attribute(.unique) var id: UUID
        @Attribute(.unique) var annotationKey: String
        var bookID: UUID
        var bookTitle: String
        var locatorJSON: String
        var anchorJSON: String?
        var selectedText: String
        var noteText: String
        var colorRawValue: String
        var chapterTitle: String
        var progress: Double
        var createdAt: Date
        var updatedAt: Date
        init() {
            id = UUID()
            annotationKey = ""
            bookID = UUID()
            bookTitle = ""
            locatorJSON = ""
            selectedText = ""
            noteText = ""
            colorRawValue = ""
            chapterTitle = ""
            progress = 0
            createdAt = Date()
            updatedAt = Date()
        }
    }
}

enum JerreaderSchemaV2: VersionedSchema {
    static let versionIdentifier = Schema.Version(2, 0, 0)
    static let models: [any PersistentModel.Type] = [
        BookRecord.self,
        WordLookupRecord.self,
        TranslationCacheRecord.self,
        TranslationFavoriteRecord.self,
        ReadingBookmarkRecord.self,
        ReadingAnnotationRecord.self,
        GrammarNoteRecord.self,
    ]
}

enum JerreaderMigrationPlan: SchemaMigrationPlan {
    static let schemas: [any VersionedSchema.Type] = [JerreaderSchemaV1.self, JerreaderSchemaV2.self]
    static let stages: [MigrationStage] = [.lightweight(fromVersion: JerreaderSchemaV1.self, toVersion: JerreaderSchemaV2.self)]
}
