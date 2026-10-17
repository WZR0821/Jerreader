import Foundation
import Darwin
@preconcurrency import ReadiumShared
@preconcurrency import ReadiumStreamer

@MainActor
protocol ReaderPublicationOpening: AnyObject {
    func open(book: BookRecord) async throws -> Publication
    func reassertBookFileMetadata()
    func finishBookFileAccess()
}

extension ReaderPublicationOpening {
    func reassertBookFileMetadata() {}
    func finishBookFileAccess() {}
}

/// Makes the app-owned publication copy an input-only asset.
///
/// Readium is expected to open EPUB/PDF assets without writing them, but a
/// parser or system framework must not be allowed to update the copied book as
/// a side effect of opening it. Removing every POSIX write bit provides the
/// hard guarantee; restoring the captured modification date in `defer` also
/// protects older writable copies during the first open after this upgrade.
struct LibraryPublicationReadOnlyAccess {
    private let url: URL
    private let modificationTime: timespec

    static func prepare(
        url: URL,
        fileManager: FileManager = .default
    ) throws -> Self {
        let attributes = try fileManager.attributesOfItem(atPath: url.path)
        var metadata = stat()
        let status = url.withUnsafeFileSystemRepresentation { path in
            guard let path else { return Int32(-1) }
            return Darwin.lstat(path, &metadata)
        }
        guard status == 0 else {
            throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
        }
        let permissions = (attributes[.posixPermissions] as? NSNumber)?.intValue
            ?? 0o644
        let readOnlyPermissions = permissions & ~0o222
        if readOnlyPermissions != permissions {
            try fileManager.setAttributes(
                [.posixPermissions: readOnlyPermissions],
                ofItemAtPath: url.path
            )
        }
        return Self(
            url: url,
            modificationTime: metadata.st_mtimespec
        )
    }

    func restoreModificationDate() {
        var current = stat()
        url.withUnsafeFileSystemRepresentation { path in
            guard let path, Darwin.lstat(path, &current) == 0 else { return }
            // NSDate/TimeInterval round trips lose filesystem nanoseconds.
            // Do not touch unchanged metadata; repair actual framework writes
            // using the original POSIX timestamp and leave access time alone.
            guard current.st_mtimespec.tv_sec != modificationTime.tv_sec
                || current.st_mtimespec.tv_nsec != modificationTime.tv_nsec
            else { return }
            let times = [timespec(tv_sec: 0, tv_nsec: Int(UTIME_OMIT)), modificationTime]
            times.withUnsafeBufferPointer {
                _ = Darwin.utimensat(AT_FDCWD, path, $0.baseAddress, 0)
            }
        }
    }
}

@MainActor
final class EPUBPublicationService: ReaderPublicationOpening {
    private let fileManager: FileManager
    private let rootDirectoryURL: URL?
    private var activeReadOnlyAccess: LibraryPublicationReadOnlyAccess?

    init(
        fileManager: FileManager = .default,
        rootDirectoryURL: URL? = nil
    ) {
        self.fileManager = fileManager
        self.rootDirectoryURL = rootDirectoryURL
    }

    func open(book: BookRecord) async throws -> Publication {
        finishBookFileAccess()
        let url = try LibraryPaths.bookURL(
            fileName: book.localFileName,
            fileManager: fileManager,
            rootDirectoryURL: rootDirectoryURL
        )
        guard fileManager.isReadableFile(atPath: url.path) else {
            throw ReaderError.missingBookFile
        }
        let readOnlyAccess: LibraryPublicationReadOnlyAccess
        do {
            readOnlyAccess = try LibraryPublicationReadOnlyAccess.prepare(
                url: url,
                fileManager: fileManager
            )
        } catch {
            throw ReaderError.missingBookFile
        }
        defer { readOnlyAccess.restoreModificationDate() }

        let format = book.format
        try Task.checkCancellation()
        guard let readiumURL = FileURL(url: url) else {
            throw ReaderError.missingBookFile
        }

        // Keep construction, retrieval and opening on one actor. Readium's
        // Publication and its archive-backed resources are reference objects
        // which are consumed immediately by a MainActor navigator. Handing a
        // freshly opened publication across a detached-task boundary made the
        // first navigator creation intermittently fail even though retrying
        // the same file worked. Readium's APIs are already asynchronous, so
        // the UI remains responsive without transferring object ownership.
        let httpClient = DefaultHTTPClient()
        let assetRetriever = AssetRetriever(httpClient: httpClient)
        let publicationOpener = PublicationOpener(
            parser: DefaultPublicationParser(
                httpClient: httpClient,
                assetRetriever: assetRetriever,
                pdfFactory: DefaultPDFDocumentFactory()
            ),
            contentProtections: []
        )

        let asset: Asset
        do {
            asset = try await assetRetriever.retrieve(url: readiumURL).get()
        } catch {
            throw Self.invalidReaderError(for: format)
        }
        try Task.checkCancellation()

        let expectedFormat: FormatSpecification = format == .pdf ? .pdf : .epub
        guard asset.format.conformsTo(expectedFormat) else {
            throw ReaderError.unsupportedFormat
        }

        let publication: Publication
        do {
            publication = try await publicationOpener.open(
                asset: asset,
                allowUserInteraction: false
            ).get()
        } catch {
            throw Self.invalidReaderError(for: format)
        }
        guard !Task.isCancelled else {
            publication.close()
            throw CancellationError()
        }
        guard !publication.isRestricted else {
            publication.close()
            throw ReaderError.protectedPublication
        }
        // The Publication keeps archive resources alive after open() returns.
        // Retain the captured metadata until the reader closes so later
        // Readium/Navigator activity can never leave the library file looking
        // newly modified.
        activeReadOnlyAccess = readOnlyAccess
        return publication
    }

    func reassertBookFileMetadata() {
        activeReadOnlyAccess?.restoreModificationDate()
    }

    func finishBookFileAccess() {
        activeReadOnlyAccess?.restoreModificationDate()
        activeReadOnlyAccess = nil
    }

    private nonisolated static func invalidReaderError(for format: BookFormat) -> ReaderError {
        format == .pdf ? .invalidPDF : .invalidEPUB
    }
}
