import CryptoKit
import Foundation
import ReadiumZIPFoundation
import SwiftData

/// Errors surfaced to the backup and restore UI.
enum LibraryBackupError: LocalizedError {
    case archiveCreationFailed
    case archiveUnreadable
    case incompatibleArchive
    case invalidSelection
    case missingBookFile(String)
    case archiveEntryTooLarge
    case storageUnavailable
    case backupFolderUnavailable
    case backupFolderReadOnly
    case operationInProgress

    var errorDescription: String? {
        switch self {
        case .archiveCreationFailed:
            return "备份文件没有生成成功，请重试。"
        case .archiveUnreadable:
            return "这个文件不是有效的Jerreader备份，或者已经损坏。"
        case .incompatibleArchive:
            return "这个备份来自更新版本的Jerreader，请先升级 App。"
        case .invalidSelection:
            return "请至少选择一项要备份的内容。"
        case let .missingBookFile(title):
            return "“\(title)”的本地书籍文件已经缺失，无法生成完整备份。"
        case .archiveEntryTooLarge:
            return "备份中包含异常大的文件，已停止恢复以保护本机存储。"
        case .storageUnavailable:
            return "本机存储空间不足或不可写入。"
        case .backupFolderUnavailable:
            return "无法访问已选择的备份文件夹。请在备份中心重新选择文件夹；如果使用 iCloud Drive，请确认 iCloud 已登录且文件夹仍然存在。"
        case .backupFolderReadOnly:
            return "所选文件夹不可写入，请选择 iCloud Drive 或“文件”中的其他文件夹。"
        case .operationInProgress:
            return "另一项备份操作正在进行，请稍后再试。"
        }
    }
}

struct LibraryBackupSummary: Equatable, Codable, Sendable {
    var books = 0
    var bookmarks = 0
    var annotations = 0
    var favorites = 0
    var words = 0

    var isEmpty: Bool {
        books == 0 && bookmarks == 0 && annotations == 0
            && favorites == 0 && words == 0
    }

    var description: String {
        var parts: [String] = []
        if books > 0 { parts.append("\(books) 本书") }
        if bookmarks > 0 { parts.append("\(bookmarks) 个书签") }
        if annotations > 0 { parts.append("\(annotations) 条批注") }
        if favorites > 0 { parts.append("\(favorites) 条翻译收藏") }
        if words > 0 { parts.append("\(words) 条生词/历史") }
        return parts.isEmpty ? "设置或已有内容" : parts.joined(separator: " · ")
    }
}

struct LibraryBackupArchiveInfo: Equatable, Sendable, Identifiable {
    var id: String { url.standardizedFileURL.path }
    let url: URL
    let createdAt: Date
    let appVersion: String
    let appBuild: String?
    let scopes: Set<LibraryBackupScope>
    let summary: LibraryBackupSummary
    let fileSize: Int64

    var versionText: String {
        return appVersion
    }
}

struct LibraryBackupRestoreResult: Equatable, Sendable {
    let archive: LibraryBackupArchiveInfo
    let restored: LibraryBackupSummary
    let didRestoreSettings: Bool
    /// The regimen the archive carried, already written to `UserDefaults`.
    /// Nil for archives written before archives carried one.
    let inheritedProfile: LibraryBackupProfile?

    init(
        archive: LibraryBackupArchiveInfo,
        restored: LibraryBackupSummary,
        didRestoreSettings: Bool,
        inheritedProfile: LibraryBackupProfile? = nil
    ) {
        self.archive = archive
        self.restored = restored
        self.didRestoreSettings = didRestoreSettings
        self.inheritedProfile = inheritedProfile
    }
}

/// Exports and restores everything the user selected.
///
/// Records are JSON rather than a raw SwiftData store so newer schema versions
/// can continue to restore older archives. API credentials never enter this
/// format.
struct LibraryBackupService: @unchecked Sendable {
    static let fileExtension = "jerreader-backup"
    static let typeIdentifier = "com.jerreader.backup"

    private static let currentVersion = 2
    private static let manifestPath = "manifest.json"
    private static let recordsPath = "records.json"
    private static let defaultsPath = "defaults.json"
    private static let booksPrefix = "Books/"
    private static let coversPrefix = "Covers/"
    private static let maximumMetadataBytes: UInt64 = 32 * 1_024 * 1_024
    private static let maximumPublicationBytes: UInt64 = 300 * 1_024 * 1_024
    private static let maximumCoverBytes: UInt64 = 32 * 1_024 * 1_024
    private static let maximumRestoreBytes: UInt64 = 8 * 1_024 * 1_024 * 1_024
    private static let maximumRecordCount = 200_000

    /// The imported-library root, not the managed backup directory.
    private let rootDirectoryURL: URL?
    private let defaults: UserDefaults

    private var fileManager: FileManager { .default }

    init(
        rootDirectoryURL: URL? = nil,
        defaults: UserDefaults = .standard
    ) {
        self.rootDirectoryURL = rootDirectoryURL
        self.defaults = defaults
    }

    // MARK: - Export

    @MainActor
    func exportArchive(
        context: ModelContext,
        to destinationURL: URL,
        options: LibraryBackupOptions = .full
    ) async throws -> LibraryBackupArchiveInfo {
        guard !options.scopes.isEmpty else {
            throw LibraryBackupError.invalidSelection
        }

        let payload = try Self.snapshot(context: context, options: options)
        let directories = try directoriesOrThrow()
        let exportedDefaults = options.includes(.settings)
            ? Self.exportableDefaults(defaults: defaults)
            : nil
        let appVersion = Bundle.main.infoDictionary?[
            "CFBundleShortVersionString"
        ] as? String ?? ""
        let appBuild = Bundle.main.infoDictionary?["CFBundleVersion"] as? String
        let profile = LibraryBackupPolicyStore.currentProfile(defaults: defaults)

        // Snapshot SwiftData on the main actor, then do hashing and archive I/O
        // on a utility task so a large library cannot freeze the interface or
        // trigger the iOS watchdog.
        return try await Task.detached(priority: .utility) {
            try await exportPayload(
                payload,
                directories: directories,
                destinationURL: destinationURL,
                options: options,
                defaults: exportedDefaults,
                appVersion: appVersion,
                appBuild: appBuild,
                profile: profile
            )
        }.value
    }

    private func exportPayload(
        _ originalPayload: (records: BackupRecords, summary: LibraryBackupSummary),
        directories: (books: URL, covers: URL),
        destinationURL: URL,
        options: LibraryBackupOptions,
        defaults: [String: BackupDefaultValue]?,
        appVersion: String,
        appBuild: String?,
        profile: LibraryBackupProfile?
    ) async throws -> LibraryBackupArchiveInfo {
        var payload = originalPayload
        if options.includes(.library) {
            for index in payload.records.books.indices {
                var book = payload.records.books[index]
                guard Self.isSafeLeafName(book.localFileName) else {
                    throw LibraryBackupError.archiveCreationFailed
                }
                let bookURL = directories.books.appendingPathComponent(book.localFileName)
                guard fileManager.isReadableFile(atPath: bookURL.path) else {
                    throw LibraryBackupError.missingBookFile(book.title)
                }
                let bookDigest = try digest(at: bookURL)
                book.archivedFileSHA256 = bookDigest.sha256
                book.archivedFileSize = bookDigest.size

                if let coverName = book.coverFileName,
                   Self.isSafeLeafName(coverName)
                {
                    let coverURL = directories.covers.appendingPathComponent(coverName)
                    if fileManager.isReadableFile(atPath: coverURL.path) {
                        let coverDigest = try digest(at: coverURL)
                        book.archivedCoverSHA256 = coverDigest.sha256
                        book.archivedCoverSize = coverDigest.size
                    } else {
                        book.coverFileName = nil
                    }
                } else {
                    book.coverFileName = nil
                }
                payload.records.books[index] = book
            }
        }

        let parent = destinationURL.deletingLastPathComponent()
        let stagingURL = parent.appendingPathComponent(
            ".\(destinationURL.lastPathComponent).\(UUID().uuidString).partial"
        )
        do {
            try fileManager.createDirectory(at: parent, withIntermediateDirectories: true)
        } catch {
            throw LibraryBackupError.storageUnavailable
        }
        defer { try? fileManager.removeItem(at: stagingURL) }

        guard let archive = try? await Archive(url: stagingURL, accessMode: .create) else {
            throw LibraryBackupError.archiveCreationFailed
        }
        let createdAt = Date()
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]

        do {
            let manifest = BackupManifest(
                version: Self.currentVersion,
                createdAt: createdAt,
                appVersion: appVersion,
                appBuild: appBuild,
                summary: payload.summary,
                scopes: options.scopes.sorted { $0.rawValue < $1.rawValue },
                profile: profile
            )
            try await add(data: encoder.encode(manifest), at: Self.manifestPath, to: archive)
            try await add(data: encoder.encode(payload.records), at: Self.recordsPath, to: archive)
            if let defaults {
                try await add(data: encoder.encode(defaults), at: Self.defaultsPath, to: archive)
            }
            if options.includes(.library) {
                for book in payload.records.books {
                    try await addFile(
                        at: directories.books.appendingPathComponent(book.localFileName),
                        path: Self.booksPrefix + book.localFileName,
                        to: archive
                    )
                    if let coverName = book.coverFileName {
                        try await addFile(
                            at: directories.covers.appendingPathComponent(coverName),
                            path: Self.coversPrefix + coverName,
                            to: archive
                        )
                    }
                }
            }

            // A valid final-name archive appears only after every entry and
            // digest has completed. Existing destinations remain untouched if
            // any earlier step fails.
            if fileManager.fileExists(atPath: destinationURL.path) {
                _ = try fileManager.replaceItemAt(destinationURL, withItemAt: stagingURL)
            } else {
                try fileManager.moveItem(at: stagingURL, to: destinationURL)
            }
        } catch let error as LibraryBackupError {
            throw error
        } catch {
            throw LibraryBackupError.archiveCreationFailed
        }

        return LibraryBackupArchiveInfo(
            url: destinationURL,
            createdAt: createdAt,
            appVersion: appVersion,
            appBuild: appBuild,
            scopes: options.scopes,
            summary: payload.summary,
            fileSize: fileSize(at: destinationURL)
        )
    }

    // MARK: - Inspect and restore

    func inspectArchive(at sourceURL: URL) async throws -> LibraryBackupArchiveInfo {
        let inspected = try await inspectPayload(at: sourceURL, includesRecords: false)
        return LibraryBackupArchiveInfo(
            url: sourceURL,
            createdAt: inspected.manifest.createdAt,
            appVersion: inspected.manifest.appVersion,
            appBuild: inspected.manifest.appBuild,
            scopes: inspected.manifest.resolvedScopes,
            summary: inspected.manifest.summary,
            fileSize: fileSize(at: sourceURL)
        )
    }

    @MainActor
    func importArchive(
        from sourceURL: URL,
        context: ModelContext
    ) async throws -> LibraryBackupRestoreResult {
        let inspected = try await inspectPayload(at: sourceURL, includesRecords: true)
        guard var records = inspected.records else {
            throw LibraryBackupError.archiveUnreadable
        }
        let declaredRestoreBytes = records.books.reduce(UInt64(0)) { partial, book in
            let publication = UInt64(max(book.archivedFileSize ?? 0, 0))
            let cover = UInt64(max(book.archivedCoverSize ?? 0, 0))
            return partial.addingReportingOverflow(publication).overflow
                ? Self.maximumRestoreBytes + 1
                : partial + publication + cover
        }
        guard declaredRestoreBytes <= Self.maximumRestoreBytes else {
            throw LibraryBackupError.archiveEntryTooLarge
        }
        let scopes = inspected.manifest.resolvedScopes
        let restoredDefaults: [String: BackupDefaultValue]?
        if scopes.contains(.settings) {
            let defaultsData = try await data(
                at: Self.defaultsPath,
                in: inspected.archive,
                maximumBytes: Self.maximumMetadataBytes
            )
            guard let values = try? Self.decoder.decode(
                [String: BackupDefaultValue].self,
                from: defaultsData
            ) else {
                throw LibraryBackupError.archiveUnreadable
            }
            restoredDefaults = values
        } else {
            restoredDefaults = nil
        }
        let directories = try directoriesOrThrow()
        let stagingRoot = fileManager.temporaryDirectory
            .appendingPathComponent("JerreaderRestore", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? fileManager.removeItem(at: stagingRoot) }

        var movedURLs: [URL] = []
        do {
            try fileManager.createDirectory(
                at: stagingRoot,
                withIntermediateDirectories: true
            )
            if scopes.contains(.library) {
                let existingFingerprints = Set(
                    try context.fetch(FetchDescriptor<BookRecord>())
                        .map(\.fileFingerprint)
                )
                var validatedFingerprints = Set<String>()
                for index in records.books.indices {
                    var book = records.books[index]
                    guard Self.isSafeLeafName(book.localFileName) else {
                        throw LibraryBackupError.archiveUnreadable
                    }
                    guard validatedFingerprints.insert(
                        book.fileFingerprint
                    ).inserted else {
                        continue
                    }
                    let needsImport = !existingFingerprints.contains(
                        book.fileFingerprint
                    )

                    let restoredBookName = uniqueRestoredName(
                        preservingExtensionOf: book.localFileName
                    )
                    let stagedBookURL = stagingRoot.appendingPathComponent(
                        restoredBookName,
                        isDirectory: false
                    )
                    try await extractValidated(
                        path: Self.booksPrefix + book.localFileName,
                        from: inspected.archive,
                        to: stagedBookURL,
                        maximumBytes: Self.maximumPublicationBytes,
                        expectedSHA256: book.archivedFileSHA256,
                        expectedSize: book.archivedFileSize
                    )
                    if needsImport {
                        book.localFileName = restoredBookName
                    }

                    var validatedCoverURL: URL?
                    if let coverName = book.coverFileName {
                        guard Self.isSafeLeafName(coverName) else {
                            throw LibraryBackupError.archiveUnreadable
                        }
                        if (try? await inspected.archive.get(
                            Self.coversPrefix + coverName
                        )) != nil {
                            let restoredCoverName = uniqueRestoredName(
                                preservingExtensionOf: coverName
                            )
                            let stagedCoverURL = stagingRoot.appendingPathComponent(
                                restoredCoverName,
                                isDirectory: false
                            )
                            try await extractValidated(
                                path: Self.coversPrefix + coverName,
                                from: inspected.archive,
                                to: stagedCoverURL,
                                maximumBytes: Self.maximumCoverBytes,
                                expectedSHA256: book.archivedCoverSHA256,
                                expectedSize: book.archivedCoverSize
                            )
                            validatedCoverURL = stagedCoverURL
                            if needsImport {
                                book.coverFileName = restoredCoverName
                            }
                        } else {
                            guard inspected.manifest.version < 2 else {
                                throw LibraryBackupError.archiveUnreadable
                            }
                            book.coverFileName = nil
                        }
                    }
                    if needsImport {
                        records.books[index] = book
                    } else {
                        try? fileManager.removeItem(at: stagedBookURL)
                        if let validatedCoverURL {
                            try? fileManager.removeItem(at: validatedCoverURL)
                        }
                    }
                }

                for book in records.books
                where !existingFingerprints.contains(book.fileFingerprint) {
                    let stagedBook = stagingRoot.appendingPathComponent(
                        book.localFileName,
                        isDirectory: false
                    )
                    guard fileManager.fileExists(atPath: stagedBook.path) else {
                        // Duplicate fingerprints inside a hand-edited archive
                        // are ignored by merge; only the first needs a file.
                        continue
                    }
                    let finalBook = directories.books.appendingPathComponent(
                        book.localFileName,
                        isDirectory: false
                    )
                    try fileManager.moveItem(at: stagedBook, to: finalBook)
                    movedURLs.append(finalBook)

                    if let coverName = book.coverFileName {
                        let stagedCover = stagingRoot.appendingPathComponent(
                            coverName,
                            isDirectory: false
                        )
                        if fileManager.fileExists(atPath: stagedCover.path) {
                            let finalCover = directories.covers.appendingPathComponent(
                                coverName,
                                isDirectory: false
                            )
                            try fileManager.moveItem(at: stagedCover, to: finalCover)
                            movedURLs.append(finalCover)
                        }
                    }
                }
            }

            let restored = try Self.merge(
                records: records,
                scopes: scopes,
                into: context
            )
            try context.save()

            let didRestoreSettings = restoredDefaults != nil
            if let values = restoredDefaults {
                Self.restoreDefaults(values, defaults: defaults)
            }

            // After the settings scope, so that an archive which carries both
            // cannot end up with two disagreeing copies of the schedule: the
            // manifest's regimen is the one that travels unconditionally, so
            // it is the one that wins.
            let inheritedProfile = inspected.manifest.profile
            if let inheritedProfile {
                LibraryBackupPolicyStore.apply(
                    inheritedProfile,
                    lastAutomaticBackupAt: inspected.manifest.createdAt,
                    defaults: defaults
                )
            }

            let info = LibraryBackupArchiveInfo(
                url: sourceURL,
                createdAt: inspected.manifest.createdAt,
                appVersion: inspected.manifest.appVersion,
                appBuild: inspected.manifest.appBuild,
                scopes: scopes,
                summary: inspected.manifest.summary,
                fileSize: fileSize(at: sourceURL)
            )
            return LibraryBackupRestoreResult(
                archive: info,
                restored: restored,
                didRestoreSettings: didRestoreSettings,
                inheritedProfile: inheritedProfile
            )
        } catch let error as LibraryBackupError {
            context.rollback()
            for url in movedURLs {
                try? fileManager.removeItem(at: url)
            }
            throw error
        } catch {
            context.rollback()
            for url in movedURLs {
                try? fileManager.removeItem(at: url)
            }
            throw LibraryBackupError.storageUnavailable
        }
    }

    // MARK: - Validation

    private struct InspectedPayload {
        let archive: Archive
        let manifest: BackupManifest
        let records: BackupRecords?
    }

    private func inspectPayload(
        at sourceURL: URL,
        includesRecords: Bool
    ) async throws -> InspectedPayload {
        guard sourceURL.isFileURL,
              let archive = try? await Archive(url: sourceURL, accessMode: .read)
        else {
            throw LibraryBackupError.archiveUnreadable
        }

        let manifestData = try await data(
            at: Self.manifestPath,
            in: archive,
            maximumBytes: Self.maximumMetadataBytes
        )
        guard let manifest = try? Self.decoder.decode(
            BackupManifest.self,
            from: manifestData
        ) else {
            throw LibraryBackupError.archiveUnreadable
        }
        guard manifest.version > 0,
              manifest.version <= Self.currentVersion
        else {
            throw LibraryBackupError.incompatibleArchive
        }
        guard !manifest.resolvedScopes.isEmpty else {
            throw LibraryBackupError.archiveUnreadable
        }

        var records: BackupRecords?
        if includesRecords {
            let recordsData = try await data(
                at: Self.recordsPath,
                in: archive,
                maximumBytes: Self.maximumMetadataBytes
            )
            guard let decoded = try? Self.decoder.decode(
                BackupRecords.self,
                from: recordsData
            ) else {
                throw LibraryBackupError.archiveUnreadable
            }
            try Self.validate(
                decoded,
                scopes: manifest.resolvedScopes,
                archiveVersion: manifest.version
            )
            records = decoded
        }
        return InspectedPayload(
            archive: archive,
            manifest: manifest,
            records: records
        )
    }

    private static func validate(
        _ records: BackupRecords,
        scopes: Set<LibraryBackupScope>,
        archiveVersion: Int
    ) throws {
        let totalCount = records.books.count
            + records.bookmarks.count
            + records.annotations.count
            + records.favorites.count
            + records.words.count
        guard totalCount <= maximumRecordCount else {
            throw LibraryBackupError.archiveUnreadable
        }
        guard records.books.allSatisfy({
            !$0.fileFingerprint.isEmpty
                && isSafeLeafName($0.localFileName)
                && ($0.coverFileName.map(isSafeLeafName) ?? true)
        }) else {
            throw LibraryBackupError.archiveUnreadable
        }
        if archiveVersion >= 2, scopes.contains(.library) {
            guard records.books.allSatisfy({ book in
                isSHA256(book.archivedFileSHA256)
                    && isValidSize(
                        book.archivedFileSize,
                        maximum: maximumPublicationBytes
                    )
                    && (
                        book.coverFileName == nil
                            || (
                                isSHA256(book.archivedCoverSHA256)
                                    && isValidSize(
                                        book.archivedCoverSize,
                                        maximum: maximumCoverBytes
                                    )
                            )
                    )
            }) else {
                throw LibraryBackupError.archiveUnreadable
            }
        }
        if !scopes.contains(.reading),
           !records.bookmarks.isEmpty || !records.annotations.isEmpty
        {
            throw LibraryBackupError.archiveUnreadable
        }
        if !scopes.contains(.learning),
           !records.favorites.isEmpty || !records.words.isEmpty
        {
            throw LibraryBackupError.archiveUnreadable
        }
    }

    private static func isSafeLeafName(_ name: String) -> Bool {
        guard !name.isEmpty,
              name != ".",
              name != "..",
              !name.contains("/"),
              !name.contains("\\"),
              !name.contains("\0")
        else { return false }
        return URL(fileURLWithPath: name).lastPathComponent == name
    }

    private static func isSHA256(_ value: String?) -> Bool {
        guard let value, value.count == 64 else { return false }
        return value.unicodeScalars.allSatisfy {
            (48 ... 57).contains($0.value)
                || (65 ... 70).contains($0.value)
                || (97 ... 102).contains($0.value)
        }
    }

    private static func isValidSize(
        _ value: Int64?,
        maximum: UInt64
    ) -> Bool {
        guard let value, value >= 0 else { return false }
        return UInt64(value) <= maximum
    }

    // MARK: - Archive helpers

    private static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return decoder
    }

    private func directoriesOrThrow() throws -> (books: URL, covers: URL) {
        do {
            return try LibraryPaths.prepareDirectories(
                fileManager: fileManager,
                rootDirectoryURL: rootDirectoryURL
            )
        } catch {
            throw LibraryBackupError.storageUnavailable
        }
    }

    private func add(
        data: Data,
        at path: String,
        to archive: Archive
    ) async throws {
        try await archive.addEntry(
            with: path,
            type: .file,
            uncompressedSize: Int64(data.count),
            compressionMethod: .deflate
        ) { position, size in
            let start = min(max(Int(position), 0), data.count)
            let end = min(start + size, data.count)
            return data.subdata(in: start ..< end)
        }
    }

    private func addFile(
        at url: URL,
        path: String,
        to archive: Archive
    ) async throws {
        guard fileManager.isReadableFile(atPath: url.path) else {
            throw LibraryBackupError.archiveCreationFailed
        }
        // Publications are already compressed containers in most cases.
        try await archive.addEntry(
            with: path,
            fileURL: url,
            compressionMethod: .none
        )
    }

    private func data(
        at path: String,
        in archive: Archive,
        maximumBytes: UInt64
    ) async throws -> Data {
        guard let entry = try? await archive.get(path),
              entry.type == .file,
              entry.uncompressedSize <= maximumBytes
        else {
            throw LibraryBackupError.archiveUnreadable
        }
        let buffer = ChunkBuffer(maximumBytes: maximumBytes)
        do {
            _ = try await archive.extract(entry) { chunk in
                try buffer.append(chunk)
            }
            return try buffer.result()
        } catch let error as LibraryBackupError {
            throw error
        } catch {
            throw LibraryBackupError.archiveUnreadable
        }
    }

    private func extractValidated(
        path: String,
        from archive: Archive,
        to destinationURL: URL,
        maximumBytes: UInt64,
        expectedSHA256: String?,
        expectedSize: Int64?
    ) async throws {
        guard let entry = try? await archive.get(path),
              entry.type == .file
        else {
            throw LibraryBackupError.archiveUnreadable
        }
        guard entry.uncompressedSize <= maximumBytes else {
            throw LibraryBackupError.archiveEntryTooLarge
        }
        do {
            _ = try await archive.extract(entry, to: destinationURL)
        } catch {
            throw LibraryBackupError.archiveUnreadable
        }
        let value = try digest(at: destinationURL)
        if let expectedSize, value.size != expectedSize {
            throw LibraryBackupError.archiveUnreadable
        }
        if let expectedSHA256,
           value.sha256.caseInsensitiveCompare(expectedSHA256) != .orderedSame
        {
            throw LibraryBackupError.archiveUnreadable
        }
    }

    private func digest(at url: URL) throws -> (sha256: String, size: Int64) {
        do {
            let handle = try FileHandle(forReadingFrom: url)
            defer { try? handle.close() }
            var hasher = SHA256()
            var size: Int64 = 0
            while let data = try handle.read(upToCount: 1_048_576), !data.isEmpty {
                hasher.update(data: data)
                size += Int64(data.count)
            }
            let hash = hasher.finalize()
                .map { String(format: "%02x", $0) }
                .joined()
            return (hash, size)
        } catch {
            throw LibraryBackupError.storageUnavailable
        }
    }

    private func uniqueRestoredName(
        preservingExtensionOf originalName: String
    ) -> String {
        let pathExtension = URL(fileURLWithPath: originalName).pathExtension
        let base = UUID().uuidString.lowercased()
        return pathExtension.isEmpty ? base : "\(base).\(pathExtension)"
    }

    private func fileSize(at url: URL) -> Int64 {
        let values = try? url.resourceValues(forKeys: [.fileSizeKey])
        return Int64(values?.fileSize ?? 0)
    }

    private final class ChunkBuffer: @unchecked Sendable {
        private let lock = NSLock()
        private let maximumBytes: UInt64
        private var storage = Data()
        private var overflowed = false

        init(maximumBytes: UInt64) {
            self.maximumBytes = maximumBytes
        }

        func append(_ chunk: Data) throws {
            lock.lock()
            defer { lock.unlock() }
            guard !overflowed,
                  UInt64(storage.count) + UInt64(chunk.count) <= maximumBytes
            else {
                overflowed = true
                throw LibraryBackupError.archiveEntryTooLarge
            }
            storage.append(chunk)
        }

        func result() throws -> Data {
            lock.lock()
            defer { lock.unlock() }
            guard !overflowed else {
                throw LibraryBackupError.archiveEntryTooLarge
            }
            return storage
        }
    }
}
