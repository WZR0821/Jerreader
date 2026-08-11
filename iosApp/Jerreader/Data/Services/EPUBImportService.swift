import CryptoKit
import Foundation
import PDFKit
@preconcurrency import ReadiumShared
@preconcurrency import ReadiumStreamer
import UIKit

struct ImportedBook: Sendable {
    let sourceFormat: BookFormat
    let publicationIdentifier: String?
    let title: String
    let author: String
    let language: String?
    let localFileName: String
    let coverFileName: String?
    let fileFingerprint: String
}

private struct PreparedImportSource {
    let url: URL
    let cleanupDirectoryURL: URL?
    /// A single file the app owns and must delete, as opposed to a staging
    /// directory it created. Used for `Documents/Inbox` hand-offs.
    var cleanupFileURL: URL? = nil
}

/// The dates a file had before this app looked at it.
///
/// Restoring them is the difference between "the reader read my book" and "the
/// reader modified my book" in every date-sorted folder the user has.
struct SourceFileTimestamps {
    private let url: URL
    private let creationDate: Date?
    private let modificationDate: Date?
    private let fileManager: FileManager

    static func capture(url: URL, fileManager: FileManager = .default) -> Self? {
        guard let attributes = try? fileManager.attributesOfItem(atPath: url.path)
        else { return nil }
        return Self(
            url: url,
            creationDate: attributes[.creationDate] as? Date,
            modificationDate: attributes[.modificationDate] as? Date,
            fileManager: fileManager
        )
    }

    func restore() {
        guard let modificationDate else { return }
        let current = (try? fileManager.attributesOfItem(atPath: url.path))?[.modificationDate]
            as? Date
        // Only write when something actually moved. An unnecessary `setAttributes`
        // on a file provider item is itself a change the provider may sync.
        guard current == nil || abs(current!.timeIntervalSince(modificationDate)) > 0.5
        else { return }
        var attributes: [FileAttributeKey: Any] = [.modificationDate: modificationDate]
        if let creationDate { attributes[.creationDate] = creationDate }
        try? fileManager.setAttributes(attributes, ofItemAtPath: url.path)
    }
}

enum LibraryPaths {
    private static let rootDirectoryName = "Jerreader"
    private static let booksDirectoryName = "Books"
    private static let coversDirectoryName = "Covers"

    static func prepareDirectories(
        fileManager: FileManager = .default,
        rootDirectoryURL: URL? = nil
    ) throws -> (books: URL, covers: URL) {
        do {
            let root: URL
            if let rootDirectoryURL {
                root = rootDirectoryURL
            } else {
                let applicationSupport = try fileManager.url(
                    for: .applicationSupportDirectory,
                    in: .userDomainMask,
                    appropriateFor: nil,
                    create: true
                )
                root = applicationSupport.appendingPathComponent(rootDirectoryName, isDirectory: true)
            }
            let books = root.appendingPathComponent(booksDirectoryName, isDirectory: true)
            let covers = root.appendingPathComponent(coversDirectoryName, isDirectory: true)
            try fileManager.createDirectory(at: books, withIntermediateDirectories: true)
            try fileManager.createDirectory(at: covers, withIntermediateDirectories: true)
            return (books, covers)
        } catch {
            throw BookImportError.storageUnavailable
        }
    }

    static func bookURL(
        fileName: String,
        fileManager: FileManager = .default,
        rootDirectoryURL: URL? = nil
    ) throws -> URL {
        try prepareDirectories(
            fileManager: fileManager,
            rootDirectoryURL: rootDirectoryURL
        ).books.appendingPathComponent(fileName, isDirectory: false)
    }

    static func coverURL(
        fileName: String,
        fileManager: FileManager = .default,
        rootDirectoryURL: URL? = nil
    ) throws -> URL {
        try prepareDirectories(
            fileManager: fileManager,
            rootDirectoryURL: rootDirectoryURL
        ).covers.appendingPathComponent(fileName, isDirectory: false)
    }
}

actor LibraryCoverStore {
    static let shared = LibraryCoverStore()

    private let fileManager: FileManager
    private let rootDirectoryURL: URL?

    init(
        fileManager: FileManager = .default,
        rootDirectoryURL: URL? = nil
    ) {
        self.fileManager = fileManager
        self.rootDirectoryURL = rootDirectoryURL
    }

    func storeJPEGData(_ data: Data) throws -> String {
        guard !data.isEmpty else { throw BookImportError.storageUnavailable }
        let fileName = "\(UUID().uuidString.lowercased()).jpg"
        let url = try LibraryPaths.coverURL(
            fileName: fileName,
            fileManager: fileManager,
            rootDirectoryURL: rootDirectoryURL
        )
        do {
            try data.write(to: url, options: .atomic)
            return fileName
        } catch {
            throw BookImportError.storageUnavailable
        }
    }

    func removeCover(fileName: String?) {
        guard let fileName, !fileName.isEmpty,
              let url = try? LibraryPaths.coverURL(
                  fileName: fileName,
                  fileManager: fileManager,
                  rootDirectoryURL: rootDirectoryURL
              )
        else { return }
        try? fileManager.removeItem(at: url)
    }
}

/// Persists file cleanup work after the SwiftData row has been deleted. A
/// failed unlink is retried on the next library appearance instead of leaving
/// an orphan forever or immediately repeating the same failing operation.
actor LibraryPendingFileCleanupStore {
    static let shared = LibraryPendingFileCleanupStore()

    struct Item: Codable, Equatable, Sendable {
        let localFileName: String
        let coverFileName: String?
    }

    private static let storageKey = "library.pending-file-cleanup"
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func enqueue(localFileName: String, coverFileName: String?) {
        var items = load()
        let item = Item(
            localFileName: localFileName,
            coverFileName: coverFileName
        )
        if !items.contains(item) {
            items.append(item)
            save(items)
        }
    }

    func markCompleted(localFileName: String, coverFileName: String?) {
        let completed = Item(
            localFileName: localFileName,
            coverFileName: coverFileName
        )
        save(load().filter { $0 != completed })
    }

    func retry(using importer: EPUBImportService) async {
        for item in load() {
            do {
                try await importer.removeFiles(
                    localFileName: item.localFileName,
                    coverFileName: item.coverFileName
                )
                markCompleted(
                    localFileName: item.localFileName,
                    coverFileName: item.coverFileName
                )
            } catch {
                // Keep the item for a later launch/foreground retry.
            }
        }
    }

    private func load() -> [Item] {
        guard let data = defaults.data(forKey: Self.storageKey),
              let values = try? JSONDecoder().decode([Item].self, from: data)
        else { return [] }
        return values
    }

    private func save(_ items: [Item]) {
        if items.isEmpty {
            defaults.removeObject(forKey: Self.storageKey)
        } else if let data = try? JSONEncoder().encode(items) {
            defaults.set(data, forKey: Self.storageKey)
        }
    }
}

actor EPUBImportService {
    static let shared = EPUBImportService()

    private let fileManager: FileManager
    private let rootDirectoryURL: URL?
    private let assetRetriever: AssetRetriever
    private let publicationOpener: PublicationOpener

    init(
        fileManager: FileManager = .default,
        rootDirectoryURL: URL? = nil
    ) {
        self.fileManager = fileManager
        self.rootDirectoryURL = rootDirectoryURL

        let httpClient = DefaultHTTPClient()
        let assetRetriever = AssetRetriever(httpClient: httpClient)
        self.assetRetriever = assetRetriever
        publicationOpener = PublicationOpener(
            parser: DefaultPublicationParser(
                httpClient: httpClient,
                assetRetriever: assetRetriever,
                pdfFactory: DefaultPDFDocumentFactory()
            ),
            contentProtections: []
        )
    }

    func importBook(
        from sourceURL: URL,
        existingFingerprints: Set<String>
    ) async throws -> ImportedBook {
        if sourceURL.pathExtension.lowercased() == "doc" {
            throw BookImportError.legacyWordUnsupported
        }
        guard let format = BookFormat(fileURL: sourceURL) else {
            throw BookImportError.unsupportedFormat
        }

        let preparedSource = try prepareSourceForReading(sourceURL, format: format)
        defer { discardPreparedSource(preparedSource) }

        try validateFileSize(preparedSource.url, format: format)

        switch format {
        case .epub:
            return try await importEPUB(
                from: preparedSource.url,
                existingFingerprints: existingFingerprints
            )
        case .pdf:
            return try await importPDF(
                from: preparedSource.url,
                existingFingerprints: existingFingerprints
            )
        case .docx, .text:
            return try await importReflowableDocument(
                from: preparedSource.url,
                sourceFormat: format,
                existingFingerprints: existingFingerprints
            )
        }
    }

    /// Returns a stable, locally readable snapshot for URLs handed to the app
    /// by Files or a third-party file provider. Keeping the coordinated read
    /// inside the security-scoped access window avoids losing permission while
    /// asynchronous EPUB/PDF parsing is still running.
    private func prepareSourceForReading(
        _ sourceURL: URL,
        format: BookFormat?
    ) throws -> PreparedImportSource {
        guard sourceURL.isFileURL else {
            throw BookImportError.inaccessibleFile
        }

        if isInsideApplicationContainer(sourceURL) {
            // A file iOS handed over through 「打开方式」 lands in `Documents/Inbox`,
            // which is the folder the Files app shows under 「读鼠」. It is the
            // app's to delete, and leaving it there both grows without bound
            // and shows the reader a second, stale copy of every book it was
            // ever given.
            return PreparedImportSource(
                url: sourceURL,
                cleanupDirectoryURL: nil,
                cleanupFileURL: isInsideDocumentInbox(sourceURL) ? sourceURL : nil
            )
        }

        let didAccessSecurityScope = sourceURL.startAccessingSecurityScopedResource()
        // Reading someone else's file is not supposed to change it, but asking
        // a file provider for an item that is not downloaded makes it
        // materialise the item, and iCloud Drive stamps what it materialises
        // with a fresh modification date. The library picker sidesteps this
        // entirely by running in copy mode (`CopyingDocumentPicker`), but a
        // book sent here from the Files app's 「打开方式」 arrives as an in-place
        // URL and has no such protection — which is why opening a book that way
        // still moved it to the top of a folder sorted by date.
        //
        // The scope is held for the whole read, so the original date can simply
        // be put back. Same shape as `LibraryPublicationReadOnlyAccess`, which
        // does this for the app's own copy while the reader has it open.
        let sourceTimestamps = SourceFileTimestamps.capture(
            url: sourceURL,
            fileManager: fileManager
        )
        defer {
            sourceTimestamps?.restore()
            if didAccessSecurityScope {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }

        let stagingDirectory = fileManager.temporaryDirectory
            .appendingPathComponent("JerreaderIncoming", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        let stagedURL = stagingDirectory.appendingPathComponent(
            sourceURL.lastPathComponent,
            isDirectory: false
        )

        do {
            try fileManager.createDirectory(
                at: stagingDirectory,
                withIntermediateDirectories: true
            )
        } catch {
            throw BookImportError.storageUnavailable
        }

        var coordinationError: NSError?
        var readError: Error?
        NSFileCoordinator().coordinate(
            readingItemAt: sourceURL,
            options: .withoutChanges,
            error: &coordinationError
        ) { coordinatedURL in
            do {
                try validateFileSize(coordinatedURL, format: format)
                guard fileManager.isReadableFile(atPath: coordinatedURL.path) else {
                    throw BookImportError.inaccessibleFile
                }
                try fileManager.copyItem(at: coordinatedURL, to: stagedURL)
            } catch {
                readError = error
            }
        }

        if let readError {
            try? fileManager.removeItem(at: stagingDirectory)
            if let importError = readError as? BookImportError {
                throw importError
            }
            throw BookImportError.inaccessibleFile
        }
        if coordinationError != nil || !fileManager.fileExists(atPath: stagedURL.path) {
            try? fileManager.removeItem(at: stagingDirectory)
            throw BookImportError.inaccessibleFile
        }

        return PreparedImportSource(
            url: stagedURL,
            cleanupDirectoryURL: stagingDirectory
        )
    }

    private func isInsideApplicationContainer(_ url: URL) -> Bool {
        let sourcePath = url.resolvingSymlinksInPath().standardizedFileURL.path
        let homePath = URL(fileURLWithPath: NSHomeDirectory(), isDirectory: true)
            .resolvingSymlinksInPath()
            .standardizedFileURL.path
        return sourcePath == homePath || sourcePath.hasPrefix(homePath + "/")
    }

    /// Whether [url] is the `Documents/Inbox` drop box iOS uses for 「打开方式」.
    private func isInsideDocumentInbox(_ url: URL) -> Bool {
        guard let documents = try? fileManager.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: false
        ) else { return false }
        let inboxPath = documents
            .appendingPathComponent("Inbox", isDirectory: true)
            .resolvingSymlinksInPath()
            .standardizedFileURL.path
        return url.resolvingSymlinksInPath().standardizedFileURL.path
            .hasPrefix(inboxPath + "/")
    }

    private func discardPreparedSource(_ source: PreparedImportSource) {
        if let cleanupFileURL = source.cleanupFileURL {
            try? fileManager.removeItem(at: cleanupFileURL)
        }
        guard let cleanupDirectoryURL = source.cleanupDirectoryURL else { return }
        try? fileManager.removeItem(at: cleanupDirectoryURL)
    }

    func fingerprintForIncomingDocument(at sourceURL: URL) throws -> String {
        let format = BookFormat(fileURL: sourceURL)
        let preparedSource = try prepareSourceForReading(sourceURL, format: format)
        defer { discardPreparedSource(preparedSource) }

        try validateFileSize(preparedSource.url, format: format)
        guard fileManager.isReadableFile(atPath: preparedSource.url.path) else {
            throw BookImportError.inaccessibleFile
        }
        return try fileFingerprint(for: preparedSource.url)
    }

    func importEPUB(
        from sourceURL: URL,
        existingFingerprints: Set<String>
    ) async throws -> ImportedBook {
        guard sourceURL.isFileURL,
              sourceURL.pathExtension.lowercased() == "epub"
        else {
            throw BookImportError.unsupportedFormat
        }

        let didAccessSecurityScope = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if didAccessSecurityScope {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }

        guard fileManager.isReadableFile(atPath: sourceURL.path) else {
            throw BookImportError.inaccessibleFile
        }

        let fingerprint = try fileFingerprint(for: sourceURL)
        guard !existingFingerprints.contains(fingerprint) else {
            throw DuplicateBookImportError(fingerprint: fingerprint)
        }

        let directories = try LibraryPaths.prepareDirectories(
            fileManager: fileManager,
            rootDirectoryURL: rootDirectoryURL
        )
        let localFileName = "\(UUID().uuidString.lowercased()).epub"
        let localURL = directories.books.appendingPathComponent(localFileName, isDirectory: false)
        var coverFileName: String?

        do {
            try fileManager.copyItem(at: sourceURL, to: localURL)

            guard let readiumURL = FileURL(url: localURL) else {
                throw BookImportError.invalidEPUB
            }

            let asset: Asset
            do {
                asset = try await assetRetriever.retrieve(url: readiumURL).get()
            } catch {
                throw BookImportError.invalidEPUB
            }

            guard asset.format.conformsTo(.epub) else {
                throw BookImportError.unsupportedFormat
            }

            let publication: Publication
            do {
                publication = try await publicationOpener.open(
                    asset: asset,
                    allowUserInteraction: false
                ).get()
            } catch {
                throw BookImportError.invalidEPUB
            }

            guard !publication.isRestricted else {
                publication.close()
                throw BookImportError.protectedPublication
            }
            defer { publication.close() }

            if let cover = try? await publication.coverFitting(
                maxSize: CGSize(width: 720, height: 1080)
            ).get(),
               let coverData = cover.jpegData(compressionQuality: 0.86)
            {
                let fileName = "\(UUID().uuidString.lowercased()).jpg"
                let coverURL = directories.covers.appendingPathComponent(fileName, isDirectory: false)
                try coverData.write(to: coverURL, options: .atomic)
                coverFileName = fileName
            }

            let fallbackTitle = sourceURL.deletingPathExtension().lastPathComponent
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let title = publication.metadata.title?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty ?? fallbackTitle.nilIfEmpty ?? "未命名电子书"
            let author = publication.metadata.authors
                .map(\.name)
                .joined(separator: "、")
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty ?? "未知作者"

            return ImportedBook(
                sourceFormat: .epub,
                publicationIdentifier: publication.metadata.identifier,
                title: title,
                author: author,
                language: publication.metadata.languages.first,
                localFileName: localFileName,
                coverFileName: coverFileName,
                fileFingerprint: fingerprint
            )
        } catch {
            try? fileManager.removeItem(at: localURL)
            if let coverFileName {
                let coverURL = directories.covers.appendingPathComponent(coverFileName, isDirectory: false)
                try? fileManager.removeItem(at: coverURL)
            }

            if let duplicateError = error as? DuplicateBookImportError {
                throw duplicateError
            }
            if let importError = error as? BookImportError {
                throw importError
            }
            throw BookImportError.storageUnavailable
        }
    }

    private func importPDF(
        from sourceURL: URL,
        existingFingerprints: Set<String>
    ) async throws -> ImportedBook {
        guard sourceURL.isFileURL,
              sourceURL.pathExtension.lowercased() == "pdf"
        else {
            throw BookImportError.unsupportedFormat
        }

        let didAccessSecurityScope = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if didAccessSecurityScope {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }

        guard fileManager.isReadableFile(atPath: sourceURL.path) else {
            throw BookImportError.inaccessibleFile
        }

        let fingerprint = try fileFingerprint(for: sourceURL)
        guard !existingFingerprints.contains(fingerprint) else {
            throw DuplicateBookImportError(fingerprint: fingerprint)
        }

        let directories = try LibraryPaths.prepareDirectories(
            fileManager: fileManager,
            rootDirectoryURL: rootDirectoryURL
        )
        let localFileName = "\(UUID().uuidString.lowercased()).pdf"
        let localURL = directories.books.appendingPathComponent(localFileName, isDirectory: false)
        var coverFileName: String?

        do {
            try fileManager.copyItem(at: sourceURL, to: localURL)
            let publication = try await openPublication(
                at: localURL,
                expectedFormat: .pdf,
                invalidError: .invalidPDF
            )
            defer { publication.close() }

            if let coverData = await publicationCoverData(publication)
                ?? pdfThumbnailData(at: localURL)
            {
                let fileName = "\(UUID().uuidString.lowercased()).jpg"
                let coverURL = directories.covers.appendingPathComponent(fileName, isDirectory: false)
                try coverData.write(to: coverURL, options: .atomic)
                coverFileName = fileName
            }

            let fallbackTitle = fallbackTitle(for: sourceURL)
            let title = publication.metadata.title?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty ?? fallbackTitle
            let author = publication.metadata.authors
                .map(\.name)
                .joined(separator: "、")
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty ?? "未知作者"

            return ImportedBook(
                sourceFormat: .pdf,
                publicationIdentifier: publication.metadata.identifier,
                title: title,
                author: author,
                language: publication.metadata.languages.first,
                localFileName: localFileName,
                coverFileName: coverFileName,
                fileFingerprint: fingerprint
            )
        } catch {
            try? fileManager.removeItem(at: localURL)
            if let coverFileName {
                let coverURL = directories.covers.appendingPathComponent(coverFileName, isDirectory: false)
                try? fileManager.removeItem(at: coverURL)
            }
            if let duplicateError = error as? DuplicateBookImportError {
                throw duplicateError
            }
            if let importError = error as? BookImportError {
                throw importError
            }
            throw BookImportError.storageUnavailable
        }
    }

    private func importReflowableDocument(
        from sourceURL: URL,
        sourceFormat: BookFormat,
        existingFingerprints: Set<String>
    ) async throws -> ImportedBook {
        guard sourceURL.isFileURL,
              sourceFormat == .docx || sourceFormat == .text
        else {
            throw BookImportError.unsupportedFormat
        }

        let didAccessSecurityScope = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if didAccessSecurityScope {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }

        guard fileManager.isReadableFile(atPath: sourceURL.path) else {
            throw BookImportError.inaccessibleFile
        }

        let fingerprint = try fileFingerprint(for: sourceURL)
        guard !existingFingerprints.contains(fingerprint) else {
            throw DuplicateBookImportError(fingerprint: fingerprint)
        }

        let directories = try LibraryPaths.prepareDirectories(
            fileManager: fileManager,
            rootDirectoryURL: rootDirectoryURL
        )
        let localFileName = "\(UUID().uuidString.lowercased()).epub"
        let localURL = directories.books.appendingPathComponent(localFileName, isDirectory: false)

        do {
            let fallbackTitle = fallbackTitle(for: sourceURL)
            let content: ReflowableDocumentContent
            do {
                switch sourceFormat {
                case .docx:
                    content = try await DOCXDocumentParser.parse(
                        url: sourceURL,
                        fallbackTitle: fallbackTitle
                    )
                case .text:
                    content = try PlainTextDocumentParser.parse(
                        url: sourceURL,
                        fallbackTitle: fallbackTitle
                    )
                default:
                    throw BookImportError.unsupportedFormat
                }
            } catch DocumentConversionError.invalidDOCX {
                throw BookImportError.invalidDOCX
            } catch DocumentConversionError.unreadableText {
                throw BookImportError.unreadableText
            } catch DocumentConversionError.emptyDocument {
                throw BookImportError.emptyDocument
            } catch DocumentConversionError.documentTooLarge {
                throw BookImportError.fileTooLarge
            } catch {
                throw sourceFormat == .docx
                    ? BookImportError.invalidDOCX
                    : BookImportError.unreadableText
            }

            try await ReflowableEPUBBuilder.build(
                content: content,
                destinationURL: localURL
            )

            let publication = try await openPublication(
                at: localURL,
                expectedFormat: .epub,
                invalidError: sourceFormat == .docx ? .invalidDOCX : .unreadableText
            )
            defer { publication.close() }

            return ImportedBook(
                sourceFormat: sourceFormat,
                publicationIdentifier: publication.metadata.identifier,
                title: content.title,
                author: content.author,
                language: content.language,
                localFileName: localFileName,
                coverFileName: nil,
                fileFingerprint: fingerprint
            )
        } catch {
            try? fileManager.removeItem(at: localURL)
            if let duplicateError = error as? DuplicateBookImportError {
                throw duplicateError
            }
            if let importError = error as? BookImportError {
                throw importError
            }
            throw BookImportError.storageUnavailable
        }
    }

    private func openPublication(
        at url: URL,
        expectedFormat: FormatSpecification,
        invalidError: BookImportError
    ) async throws -> Publication {
        guard let readiumURL = FileURL(url: url) else {
            throw invalidError
        }

        let asset: Asset
        do {
            asset = try await assetRetriever.retrieve(url: readiumURL).get()
        } catch {
            throw invalidError
        }
        guard asset.format.conformsTo(expectedFormat) else {
            throw BookImportError.unsupportedFormat
        }

        let publication: Publication
        do {
            publication = try await publicationOpener.open(
                asset: asset,
                allowUserInteraction: false
            ).get()
        } catch {
            throw invalidError
        }
        guard !publication.isRestricted else {
            publication.close()
            throw BookImportError.protectedPublication
        }
        return publication
    }

    private func publicationCoverData(_ publication: Publication) async -> Data? {
        guard let cover = try? await publication.coverFitting(
            maxSize: CGSize(width: 720, height: 1080)
        ).get() else {
            return nil
        }
        return cover.jpegData(compressionQuality: 0.86)
    }

    private func pdfThumbnailData(at url: URL) -> Data? {
        guard let document = PDFDocument(url: url),
              let page = document.page(at: 0)
        else {
            return nil
        }
        let image = page.thumbnail(
            of: CGSize(width: 720, height: 1080),
            for: .cropBox
        )
        return image.jpegData(compressionQuality: 0.86)
    }

    private func fallbackTitle(for url: URL) -> String {
        url.deletingPathExtension().lastPathComponent
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty ?? "未命名文档"
    }

    private func validateFileSize(_ url: URL, format: BookFormat?) throws {
        guard url.isFileURL else { throw BookImportError.inaccessibleFile }
        do {
            let values = try url.resourceValues(forKeys: [.fileSizeKey])
            let maximumBytes: Int
            switch format {
            case .text:
                maximumBytes = 20 * 1_024 * 1_024
            case .docx:
                maximumBytes = 100 * 1_024 * 1_024
            case .epub, .pdf, nil:
                maximumBytes = 300 * 1_024 * 1_024
            }
            if let size = values.fileSize, size > maximumBytes {
                throw BookImportError.fileTooLarge
            }
        } catch let error as BookImportError {
            throw error
        } catch {
            throw BookImportError.inaccessibleFile
        }
    }

    func fileFingerprint(for url: URL) throws -> String {
        do {
            let handle = try FileHandle(forReadingFrom: url)
            defer { try? handle.close() }

            var hasher = SHA256()
            while let data = try handle.read(upToCount: 1_048_576), !data.isEmpty {
                hasher.update(data: data)
            }
            return hasher.finalize().map { String(format: "%02x", $0) }.joined()
        } catch {
            throw BookImportError.inaccessibleFile
        }
    }

    func removeFiles(localFileName: String, coverFileName: String?) throws {
        do {
            let bookURL = try LibraryPaths.bookURL(
                fileName: localFileName,
                fileManager: fileManager,
                rootDirectoryURL: rootDirectoryURL
            )
            if fileManager.fileExists(atPath: bookURL.path) {
                try fileManager.removeItem(at: bookURL)
            }

            if let coverFileName {
                let coverURL = try LibraryPaths.coverURL(
                    fileName: coverFileName,
                    fileManager: fileManager,
                    rootDirectoryURL: rootDirectoryURL
                )
                if fileManager.fileExists(atPath: coverURL.path) {
                    try fileManager.removeItem(at: coverURL)
                }
            }
        } catch {
            throw BookImportError.deletionFailed
        }
    }

    func discardImportedFiles(_ book: ImportedBook) {
        try? removeFiles(
            localFileName: book.localFileName,
            coverFileName: book.coverFileName
        )
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
