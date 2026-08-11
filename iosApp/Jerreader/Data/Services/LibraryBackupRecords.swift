import Foundation
@preconcurrency import ReadiumShared
import SwiftData

// MARK: - Backup scope

enum LibraryBackupScope: String, CaseIterable, Codable, Identifiable, Sendable {
    case library
    case reading
    case learning
    case settings

    var id: Self { self }

    var title: String {
        switch self {
        case .library: return "书籍与封面"
        case .reading: return "进度、书签与批注"
        case .learning: return "生词与翻译收藏"
        case .settings: return "阅读与翻译偏好"
        }
    }

    var detail: String {
        switch self {
        case .library: return "包含书架元数据和实际 EPUB、PDF、DOCX/TXT 转换副本。"
        case .reading: return "包含每本书的阅读位置、排版、时长、书签和批注。"
        case .learning: return "包含生词本、复习进度、查词历史、AI 解析和译文收藏。"
        case .settings: return "包含非敏感的全局阅读与翻译设置，不包含 API Key。"
        }
    }
}

struct LibraryBackupOptions: Equatable, Codable, Sendable {
    var scopes: Set<LibraryBackupScope>

    static let full = LibraryBackupOptions(
        scopes: Set(LibraryBackupScope.allCases)
    )

    init(scopes: Set<LibraryBackupScope>) {
        self.scopes = scopes
    }

    func includes(_ scope: LibraryBackupScope) -> Bool {
        scopes.contains(scope)
    }
}

// MARK: - Archive payload

struct BackupManifest: Codable, Sendable {
    var version: Int
    var createdAt: Date
    var appVersion: String
    var appBuild: String?
    var summary: LibraryBackupSummary
    /// Missing in v1 archives, which always represented a full backup.
    var scopes: [LibraryBackupScope]?
    /// The backup regimen this archive was produced under. Written into every
    /// archive regardless of the selected scopes, because a reinstall needs it
    /// even when the user chose not to carry reading preferences across.
    /// Absent in archives written before this existed.
    var profile: LibraryBackupProfile?

    var resolvedScopes: Set<LibraryBackupScope> {
        guard let scopes else {
            return LibraryBackupOptions.full.scopes
        }
        return Set(scopes)
    }
}

struct BackupRecords: Codable, Sendable {
    var books: [BackupBook] = []
    var bookmarks: [BackupBookmark] = []
    var annotations: [BackupAnnotation] = []
    var favorites: [BackupFavorite] = []
    var words: [BackupWord] = []
}

/// A UserDefaults value restricted to the property-list types the reader uses.
enum BackupDefaultValue: Codable, Equatable, Sendable {
    case bool(Bool)
    case double(Double)
    case integer(Int)
    case string(String)
    case strings([String])

    init?(_ value: Any) {
        if let number = value as? NSNumber {
            if CFGetTypeID(number) == CFBooleanGetTypeID() {
                self = .bool(number.boolValue)
            } else {
                let type = String(cString: number.objCType)
                self = type == "f" || type == "d"
                    ? .double(number.doubleValue)
                    : .integer(number.intValue)
            }
            return
        }
        switch value {
        case let value as String: self = .string(value)
        case let value as [String]: self = .strings(value)
        default: return nil
        }
    }

    var rawValue: Any {
        switch self {
        case let .bool(value): return value
        case let .double(value): return value
        case let .integer(value): return value
        case let .string(value): return value
        case let .strings(value): return value
        }
    }
}

struct BackupBook: Codable, Sendable {
    var id: UUID
    var publicationIdentifier: String?
    var title: String
    var author: String
    var language: String?
    var sourceFormat: String
    var localFileName: String
    var coverFileName: String?
    var fileFingerprint: String
    var importedAt: Date
    var lastOpenedAt: Date?
    var lastReadLocatorJSON: String?
    var lastReadProgress: Double
    var totalReadingSeconds: Double
    var category: String
    var series: String
    var tags: [String]
    var readerFontSize: Double
    var readerTheme: String
    var readerScrollEnabled: Bool
    var readerFontFamily: String
    var readerLineHeight: Double
    var readerParagraphSpacing: Double
    var readerPageMargins: Double
    var readerCustomBackgroundHex: String
    /// Optional for compatibility with v1/v2 backups created before custom
    /// selection colors were introduced.
    var readerCustomSelectionColorHex: String?
    var readerSpeechRate: Double
    var readerTextOrientation: String
    /// Optional so archives created before PDF paper mode remain decodable.
    var readerPDFPaperModeEnabled: Bool?
    var readerDetectedPublicationLayout: String
    /// Added in archive v2. The stored publication can differ from
    /// `fileFingerprint` for DOCX/TXT, because those formats are converted to
    /// EPUB after the incoming source is fingerprinted.
    var archivedFileSHA256: String?
    var archivedFileSize: Int64?
    var archivedCoverSHA256: String?
    var archivedCoverSize: Int64?
}

struct BackupBookmark: Codable, Sendable {
    var id: UUID
    var bookmarkKey: String
    var bookID: UUID
    var bookTitle: String
    var locatorJSON: String
    var chapterTitle: String
    var excerpt: String?
    var progress: Double
    var createdAt: Date
}

struct BackupAnnotation: Codable, Sendable {
    var id: UUID
    var annotationKey: String
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
}

struct BackupFavorite: Codable, Sendable {
    var id: UUID
    var favoriteKey: String
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
}

struct BackupWord: Codable, Sendable {
    var id: UUID
    var lookupKey: String
    var surfaceForm: String
    var lemma: String?
    var reading: String?
    var languageRawValue: String
    var partOfSpeech: String?
    var definitionsData: Data
    var examplesData: Data
    var inflectionNote: String?
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
    /// Optional keeps 1.3 backup JSON decodable by 1.4.
    var vocabularyStatusRawValue: String?
    var contextHistoryText: String?
    /// Optional fields preserve decoding of backups created before 1.5.
    var reviewCount: Int?
    var reviewStage: Int?
    var reviewIntervalDays: Int?
    var reviewLapseCount: Int?
    var lastReviewedAt: Date?
    var nextReviewAt: Date?
}

// MARK: - Snapshot & merge

extension LibraryBackupService {
    /// UserDefaults keys worth carrying across a reinstall. Keychain accounts
    /// are deliberately absent.
    static func exportableDefaultKeys() -> [String] {
        [
            ReaderAppearanceDefaults.fontPointSizeKey,
            ReaderAppearanceDefaults.themeKey,
            ReaderAppearanceDefaults.readingModeKey,
            ReaderAppearanceDefaults.fontFamilyKey,
            ReaderAppearanceDefaults.lineHeightKey,
            ReaderAppearanceDefaults.paragraphSpacingKey,
            ReaderAppearanceDefaults.pageMarginsKey,
            ReaderAppearanceDefaults.pageMarginTopKey,
            ReaderAppearanceDefaults.pageMarginBottomKey,
            ReaderAppearanceDefaults.pageMarginHorizontalKey,
            ReaderAppearanceDefaults.customBackgroundHexKey,
            ReaderAppearanceDefaults.customSelectionColorHexKey,
            ReaderAppearanceDefaults.appliesToExistingBooksKey,
            ReaderAppearanceDefaults.showsProgressKey,
            ReaderTextOrientationDefaults.storageKey,
            ReaderColorPresetDefaults.storageKey,
            JerreaderThemePreferences.storageKey,
            JerreaderThemePreferences.appearanceModeKey,
            LearningModulePreferences.visibilityKey,
        ] + TranslationSettingsStore.backupDefaultKeys
            + LibraryBackupPolicyStore.backupDefaultKeys
    }

    static func exportableDefaults(
        defaults: UserDefaults = .standard
    ) -> [String: BackupDefaultValue] {
        var result: [String: BackupDefaultValue] = [:]
        for key in exportableDefaultKeys() {
            guard let value = defaults.object(forKey: key),
                  let wrapped = BackupDefaultValue(value)
            else { continue }
            result[key] = wrapped
        }
        return result
    }

    static func restoreDefaults(
        _ values: [String: BackupDefaultValue],
        defaults: UserDefaults = .standard
    ) {
        let allowed = Set(exportableDefaultKeys())
        for (key, value) in values where allowed.contains(key) {
            defaults.set(value.rawValue, forKey: key)
        }
    }

    @MainActor
    static func snapshot(
        context: ModelContext,
        options: LibraryBackupOptions
    ) throws -> (records: BackupRecords, summary: LibraryBackupSummary) {
        var records = BackupRecords()
        let needsBookReferences =
            options.includes(.library)
            || options.includes(.reading)
            || options.includes(.learning)
        let books = needsBookReferences
            ? try context.fetch(FetchDescriptor<BookRecord>())
            : []
        records.books = books.map {
            BackupBook(
                id: $0.id,
                publicationIdentifier: $0.publicationIdentifier,
                title: $0.title,
                author: $0.author,
                language: $0.language,
                sourceFormat: $0.sourceFormat,
                localFileName: $0.localFileName,
                coverFileName: $0.coverFileName,
                fileFingerprint: $0.fileFingerprint,
                importedAt: $0.importedAt,
                lastOpenedAt: options.includes(.reading) ? $0.lastOpenedAt : nil,
                lastReadLocatorJSON: options.includes(.reading)
                    ? $0.lastReadLocatorJSON
                    : nil,
                lastReadProgress: options.includes(.reading)
                    ? $0.lastReadProgress
                    : 0,
                totalReadingSeconds: options.includes(.reading)
                    ? $0.totalReadingSeconds
                    : 0,
                category: $0.category,
                series: $0.series,
                tags: $0.tags,
                readerFontSize: options.includes(.reading) ? $0.readerFontSize : 1,
                readerTheme: options.includes(.reading) ? $0.readerTheme : "light",
                readerScrollEnabled: options.includes(.reading)
                    ? $0.readerScrollEnabled
                    : false,
                readerFontFamily: options.includes(.reading)
                    ? $0.readerFontFamily
                    : "serif",
                readerLineHeight: options.includes(.reading)
                    ? $0.readerLineHeight
                    : 1.4,
                readerParagraphSpacing: options.includes(.reading)
                    ? $0.readerParagraphSpacing
                    : 0,
                readerPageMargins: options.includes(.reading)
                    ? $0.readerPageMargins
                    : 1,
                readerCustomBackgroundHex: options.includes(.reading)
                    ? $0.readerCustomBackgroundHex
                    : "",
                readerCustomSelectionColorHex: options.includes(.reading)
                    ? $0.readerCustomSelectionColorHex
                    : "",
                readerSpeechRate: options.includes(.reading) ? $0.readerSpeechRate : 1,
                readerTextOrientation: options.includes(.reading)
                    ? $0.readerTextOrientation
                    : ReaderTextOrientationChoice.publication.rawValue,
                readerPDFPaperModeEnabled: options.includes(.reading)
                    ? $0.readerPDFPaperModeEnabled
                    : false,
                readerDetectedPublicationLayout: options.includes(.reading)
                    ? $0.readerDetectedPublicationLayout
                    : "",
                archivedFileSHA256: nil,
                archivedFileSize: nil,
                archivedCoverSHA256: nil,
                archivedCoverSize: nil
            )
        }

        if options.includes(.reading) {
            records.bookmarks = try context.fetch(
                FetchDescriptor<ReadingBookmarkRecord>()
            ).map {
                BackupBookmark(
                    id: $0.id,
                    bookmarkKey: $0.bookmarkKey,
                    bookID: $0.bookID,
                    bookTitle: $0.bookTitle,
                    locatorJSON: $0.locatorJSON,
                    chapterTitle: $0.chapterTitle,
                    excerpt: $0.excerpt,
                    progress: $0.progress,
                    createdAt: $0.createdAt
                )
            }
            records.annotations = try context.fetch(
                FetchDescriptor<ReadingAnnotationRecord>()
            ).map {
                BackupAnnotation(
                    id: $0.id,
                    annotationKey: $0.annotationKey,
                    bookID: $0.bookID,
                    bookTitle: $0.bookTitle,
                    locatorJSON: $0.locatorJSON,
                    anchorJSON: $0.anchorJSON,
                    selectedText: $0.selectedText,
                    noteText: $0.noteText,
                    colorRawValue: $0.colorRawValue,
                    chapterTitle: $0.chapterTitle,
                    progress: $0.progress,
                    createdAt: $0.createdAt,
                    updatedAt: $0.updatedAt
                )
            }
        }

        if options.includes(.learning) {
            records.favorites = try context.fetch(
                FetchDescriptor<TranslationFavoriteRecord>()
            ).map {
                BackupFavorite(
                    id: $0.id,
                    favoriteKey: $0.favoriteKey,
                    sourceText: $0.sourceText,
                    translatedText: $0.translatedText,
                    sourceLanguageRawValue: $0.sourceLanguageRawValue,
                    targetLanguageRawValue: $0.targetLanguageRawValue,
                    providerIdentifier: $0.providerIdentifier,
                    bookID: $0.bookID,
                    bookTitle: $0.bookTitle,
                    locatorJSON: $0.locatorJSON,
                    createdAt: $0.createdAt,
                    updatedAt: $0.updatedAt
                )
            }
            records.words = try context.fetch(
                FetchDescriptor<WordLookupRecord>()
            ).map {
                BackupWord(
                    id: $0.id,
                    lookupKey: $0.lookupKey,
                    surfaceForm: $0.surfaceForm,
                    lemma: $0.lemma,
                    reading: $0.reading,
                    languageRawValue: $0.languageRawValue,
                    partOfSpeech: $0.partOfSpeech,
                    definitionsData: $0.definitionsData,
                    examplesData: $0.examplesData,
                    inflectionNote: $0.inflectionNote,
                    usageNote: $0.usageNote,
                    aiAnalysis: $0.aiAnalysis,
                    aiProviderIdentifier: $0.aiProviderIdentifier,
                    sentenceContext: $0.sentenceContext,
                    sourceBookID: $0.sourceBookID,
                    sourceBookTitle: $0.sourceBookTitle,
                    lookupCount: $0.lookupCount,
                    createdAt: $0.createdAt,
                    lastLookedUpAt: $0.lastLookedUpAt,
                    isFavorite: $0.isFavorite,
                    isInHistory: $0.isInHistory,
                    vocabularyStatusRawValue: $0.vocabularyStatusRawValue,
                    contextHistoryText: $0.contextHistoryText,
                    reviewCount: $0.reviewCount,
                    reviewStage: $0.reviewStage,
                    reviewIntervalDays: $0.reviewIntervalDays,
                    reviewLapseCount: $0.reviewLapseCount,
                    lastReviewedAt: $0.lastReviewedAt,
                    nextReviewAt: $0.nextReviewAt
                )
            }
        }

        let summary = LibraryBackupSummary(
            books: options.includes(.library) ? records.books.count : 0,
            bookmarks: records.bookmarks.count,
            annotations: records.annotations.count,
            favorites: records.favorites.count,
            words: records.words.count
        )
        return (records, summary)
    }

    /// Merges validated records and remaps book relationships when a matching
    /// publication already exists under another UUID.
    @MainActor
    static func merge(
        records: BackupRecords,
        scopes: Set<LibraryBackupScope>,
        into context: ModelContext
    ) throws -> LibraryBackupSummary {
        var restored = LibraryBackupSummary()
        var existingBooks = try context.fetch(FetchDescriptor<BookRecord>())
        var booksByFingerprint: [String: BookRecord] = [:]
        for book in existingBooks {
            // A legacy database may predate the uniqueness constraint. Keep
            // restore deterministic instead of trapping on duplicate keys.
            if booksByFingerprint[book.fileFingerprint] == nil {
                booksByFingerprint[book.fileFingerprint] = book
            }
        }
        var usedBookIDs = Set(existingBooks.map(\.id))
        var bookIDMap: [UUID: UUID] = [:]
        var bookTitleMap: [UUID: String] = [:]
        var restoredBookIDs = Set<UUID>()

        for book in records.books {
            let record: BookRecord?
            if let existing = booksByFingerprint[book.fileFingerprint] {
                record = existing
            } else if scopes.contains(.library) {
                let id = availableID(book.id, used: &usedBookIDs)
                let created = makeBookRecord(
                    from: book,
                    id: id,
                    includesReading: scopes.contains(.reading)
                )
                context.insert(created)
                existingBooks.append(created)
                booksByFingerprint[book.fileFingerprint] = created
                record = created
                restoredBookIDs.insert(created.id)
            } else {
                record = nil
            }

            guard let record else { continue }
            bookIDMap[book.id] = record.id
            bookTitleMap[book.id] = record.title

            if scopes.contains(.reading),
               shouldRestoreReading(from: book, over: record)
            {
                applyReading(from: book, to: record)
                restoredBookIDs.insert(record.id)
            }
        }
        restored.books = restoredBookIDs.count

        var usedBookmarkIDs = Set(
            try context.fetch(FetchDescriptor<ReadingBookmarkRecord>()).map(\.id)
        )
        var bookmarkKeys = Set(
            try context.fetch(FetchDescriptor<ReadingBookmarkRecord>())
                .map(\.bookmarkKey)
        )
        for item in records.bookmarks {
            guard let bookID = bookIDMap[item.bookID],
                  let locator = try? Locator(jsonString: item.locatorJSON)
            else { continue }
            let key = ReadingBookmarkStore.key(bookID: bookID, locator: locator)
            guard bookmarkKeys.insert(key).inserted else { continue }
            context.insert(
                ReadingBookmarkRecord(
                    id: availableID(item.id, used: &usedBookmarkIDs),
                    bookmarkKey: key,
                    bookID: bookID,
                    bookTitle: bookTitleMap[item.bookID] ?? item.bookTitle,
                    locatorJSON: item.locatorJSON,
                    chapterTitle: item.chapterTitle,
                    excerpt: item.excerpt,
                    progress: item.progress,
                    createdAt: item.createdAt
                )
            )
            restored.bookmarks += 1
        }

        var usedAnnotationIDs = Set(
            try context.fetch(FetchDescriptor<ReadingAnnotationRecord>()).map(\.id)
        )
        var annotationKeys = Set(
            try context.fetch(FetchDescriptor<ReadingAnnotationRecord>())
                .map(\.annotationKey)
        )
        for item in records.annotations {
            guard let bookID = bookIDMap[item.bookID] else { continue }
            let key = ReadingAnnotationStore.key(
                bookID: bookID,
                locatorJSON: item.locatorJSON,
                selectedText: item.selectedText
            )
            guard annotationKeys.insert(key).inserted else { continue }
            context.insert(
                ReadingAnnotationRecord(
                    id: availableID(item.id, used: &usedAnnotationIDs),
                    annotationKey: key,
                    bookID: bookID,
                    bookTitle: bookTitleMap[item.bookID] ?? item.bookTitle,
                    locatorJSON: item.locatorJSON,
                    anchorJSON: item.anchorJSON,
                    selectedText: item.selectedText,
                    noteText: item.noteText,
                    color: ReadingAnnotationColor(rawValue: item.colorRawValue)
                        ?? .yellow,
                    chapterTitle: item.chapterTitle,
                    progress: item.progress,
                    createdAt: item.createdAt,
                    updatedAt: item.updatedAt
                )
            )
            restored.annotations += 1
        }

        var usedFavoriteIDs = Set(
            try context.fetch(FetchDescriptor<TranslationFavoriteRecord>()).map(\.id)
        )
        var favoriteKeys = Set(
            try context.fetch(FetchDescriptor<TranslationFavoriteRecord>())
                .map(\.favoriteKey)
        )
        for item in records.favorites {
            guard let source = LanguageCode(rawValue: item.sourceLanguageRawValue),
                  let target = LanguageCode(rawValue: item.targetLanguageRawValue)
            else { continue }

            let mappedBookID = item.bookID.flatMap { bookIDMap[$0] }
            let key: String
            if let mappedBookID {
                key = TranslationFavoriteStore.favoriteKey(
                    bookID: mappedBookID,
                    sourceText: item.sourceText,
                    sourceLanguage: source,
                    targetLanguage: target
                )
            } else {
                key = item.favoriteKey
            }
            guard favoriteKeys.insert(key).inserted else { continue }
            context.insert(
                TranslationFavoriteRecord(
                    id: availableID(item.id, used: &usedFavoriteIDs),
                    favoriteKey: key,
                    sourceText: item.sourceText,
                    translatedText: item.translatedText,
                    sourceLanguage: source,
                    targetLanguage: target,
                    providerIdentifier: item.providerIdentifier,
                    bookID: mappedBookID,
                    bookTitle: item.bookID.flatMap { bookTitleMap[$0] }
                        ?? item.bookTitle,
                    locatorJSON: item.locatorJSON,
                    createdAt: item.createdAt,
                    updatedAt: item.updatedAt
                )
            )
            restored.favorites += 1
        }

        var usedWordIDs = Set(
            try context.fetch(FetchDescriptor<WordLookupRecord>()).map(\.id)
        )
        var wordKeys = Set(
            try context.fetch(FetchDescriptor<WordLookupRecord>()).map(\.lookupKey)
        )
        for item in records.words {
            guard wordKeys.insert(item.lookupKey).inserted,
                  let language = LanguageCode(rawValue: item.languageRawValue)
            else { continue }
            let sourceBookID = item.sourceBookID.flatMap { bookIDMap[$0] }
            let record = WordLookupRecord(
                id: availableID(item.id, used: &usedWordIDs),
                explanation: WordExplanation(
                    surfaceForm: item.surfaceForm,
                    lemma: item.lemma,
                    reading: item.reading,
                    language: language,
                    partOfSpeech: item.partOfSpeech,
                    definitions: [],
                    inflectionNote: item.inflectionNote,
                    examples: [],
                    usageNote: item.usageNote,
                    sentenceContext: item.sentenceContext
                ),
                sourceBookID: sourceBookID,
                sourceBookTitle: item.sourceBookID.flatMap { bookTitleMap[$0] }
                    ?? item.sourceBookTitle,
                lookupCount: item.lookupCount,
                createdAt: item.createdAt,
                lastLookedUpAt: item.lastLookedUpAt,
                isFavorite: item.isFavorite,
                isInHistory: item.isInHistory,
                vocabularyStatusRawValue: item.vocabularyStatusRawValue
                    ?? (item.isFavorite ? "learning" : "new"),
                contextHistoryText: item.contextHistoryText
                    ?? item.sentenceContext,
                reviewCount: item.reviewCount ?? 0,
                reviewStage: item.reviewStage ?? 0,
                reviewIntervalDays: item.reviewIntervalDays ?? 0,
                reviewLapseCount: item.reviewLapseCount ?? 0,
                lastReviewedAt: item.lastReviewedAt,
                nextReviewAt: item.nextReviewAt
            )
            record.lookupKey = item.lookupKey
            record.definitionsData = item.definitionsData
            record.examplesData = item.examplesData
            record.aiAnalysis = item.aiAnalysis
            record.aiProviderIdentifier = item.aiProviderIdentifier
            context.insert(record)
            restored.words += 1
        }

        return restored
    }

    private static func makeBookRecord(
        from book: BackupBook,
        id: UUID,
        includesReading: Bool
    ) -> BookRecord {
        BookRecord(
            id: id,
            publicationIdentifier: book.publicationIdentifier,
            title: book.title,
            author: book.author,
            language: book.language,
            sourceFormat: book.sourceFormat,
            localFileName: book.localFileName,
            coverFileName: book.coverFileName,
            fileFingerprint: book.fileFingerprint,
            importedAt: book.importedAt,
            lastOpenedAt: includesReading ? book.lastOpenedAt : nil,
            lastReadLocatorJSON: includesReading ? book.lastReadLocatorJSON : nil,
            lastReadProgress: includesReading ? book.lastReadProgress : 0,
            totalReadingSeconds: includesReading ? book.totalReadingSeconds : 0,
            category: book.category,
            series: book.series,
            tags: book.tags,
            readerFontSize: includesReading ? book.readerFontSize : 1,
            readerTheme: includesReading ? book.readerTheme : "light",
            readerScrollEnabled: includesReading
                ? book.readerScrollEnabled
                : false,
            readerFontFamily: includesReading ? book.readerFontFamily : "serif",
            readerLineHeight: includesReading ? book.readerLineHeight : 1.4,
            readerParagraphSpacing: includesReading
                ? book.readerParagraphSpacing
                : 0,
            readerPageMargins: includesReading ? book.readerPageMargins : 1,
            readerCustomBackgroundHex: includesReading
                ? book.readerCustomBackgroundHex
                : "",
            readerCustomSelectionColorHex: includesReading
                ? (book.readerCustomSelectionColorHex ?? "")
                : "",
            readerSpeechRate: includesReading ? book.readerSpeechRate : 1,
            readerTextOrientation: includesReading
                ? book.readerTextOrientation
                : ReaderTextOrientationChoice.publication.rawValue,
            readerPDFPaperModeEnabled: includesReading
                ? (book.readerPDFPaperModeEnabled ?? false)
                : false,
            readerDetectedPublicationLayout: includesReading
                ? book.readerDetectedPublicationLayout
                : ""
        )
    }

    private static func shouldRestoreReading(
        from backup: BackupBook,
        over current: BookRecord
    ) -> Bool {
        guard current.lastReadLocatorJSON != nil || backup.lastReadLocatorJSON != nil else {
            return backup.totalReadingSeconds > current.totalReadingSeconds
        }
        let backupDate = backup.lastOpenedAt ?? backup.importedAt
        let currentDate = current.lastOpenedAt ?? .distantPast
        return current.lastReadLocatorJSON == nil || backupDate > currentDate
    }

    private static func applyReading(
        from book: BackupBook,
        to record: BookRecord
    ) {
        record.lastOpenedAt = book.lastOpenedAt
        record.lastReadLocatorJSON = book.lastReadLocatorJSON
        record.lastReadProgress = min(max(book.lastReadProgress, 0), 1)
        record.totalReadingSeconds = max(book.totalReadingSeconds, 0)
        record.readerFontSize = book.readerFontSize
        record.readerTheme = book.readerTheme
        record.readerScrollEnabled = book.readerScrollEnabled
        record.readerFontFamily = book.readerFontFamily
        record.readerLineHeight = book.readerLineHeight
        record.readerParagraphSpacing = book.readerParagraphSpacing
        record.readerPageMargins = book.readerPageMargins
        record.readerCustomBackgroundHex =
            ReaderCustomBackground.normalizedHex(book.readerCustomBackgroundHex) ?? ""
        record.readerCustomSelectionColorHex =
            ReaderCustomBackground.normalizedHex(
                book.readerCustomSelectionColorHex ?? ""
            ) ?? ""
        record.readerSpeechRate = min(max(book.readerSpeechRate, 0.5), 2)
        record.readerTextOrientation = book.readerTextOrientation
        record.readerPDFPaperModeEnabled =
            book.readerPDFPaperModeEnabled ?? false
        record.readerDetectedPublicationLayout =
            book.readerDetectedPublicationLayout
    }

    private static func availableID(
        _ preferred: UUID,
        used: inout Set<UUID>
    ) -> UUID {
        if used.insert(preferred).inserted {
            return preferred
        }
        var candidate = UUID()
        while !used.insert(candidate).inserted {
            candidate = UUID()
        }
        return candidate
    }
}
