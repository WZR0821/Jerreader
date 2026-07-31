import CryptoKit
import Foundation
@preconcurrency import ReadiumShared
import ReadiumZIPFoundation
import SwiftData
import XCTest
@testable import Jerreader

@MainActor
final class LibraryBackupServiceTests: XCTestCase {
    func testFullBackupRoundTripRestoresPublicationReadingAndAllowedSettings() async throws {
        let workspace = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: workspace) }
        let sourceRoot = workspace.appendingPathComponent("source", isDirectory: true)
        let targetRoot = workspace.appendingPathComponent("target", isDirectory: true)
        let archiveURL = workspace.appendingPathComponent(
            "round-trip.\(LibraryBackupService.fileExtension)"
        )
        let sourceDefaults = isolatedDefaults()
        let targetDefaults = isolatedDefaults()
        defer {
            clear(sourceDefaults)
            clear(targetDefaults)
        }

        let sourceContainer = try makeContainer()
        let sourceContext = sourceContainer.mainContext
        let locator = makeLocator()
        let locatorJSON = try XCTUnwrap(locator.readerJSONString)
        let sourceBookID = UUID()
        let book = BookRecord(
            id: sourceBookID,
            title: "备份测试书",
            author: "测试作者",
            language: "ja",
            localFileName: "backup-test.epub",
            coverFileName: "backup-test.jpg",
            fileFingerprint: "backup-round-trip",
            lastOpenedAt: Date(timeIntervalSince1970: 1_700_000_000),
            lastReadLocatorJSON: locatorJSON,
            lastReadProgress: 0.42,
            totalReadingSeconds: 321,
            category: "小说",
            tags: ["日语"],
            readerTheme: "sepia"
        )
        sourceContext.insert(book)
        sourceContext.insert(
            ReadingBookmarkRecord(
                bookmarkKey: ReadingBookmarkStore.key(
                    bookID: sourceBookID,
                    locator: locator
                ),
                bookID: sourceBookID,
                bookTitle: book.title,
                locatorJSON: locatorJSON,
                chapterTitle: "第一章",
                excerpt: "吾輩は猫である。",
                progress: 0.42
            )
        )
        try sourceContext.save()

        let sourceDirectories = try LibraryPaths.prepareDirectories(
            rootDirectoryURL: sourceRoot
        )
        let publicationData = Data("test-publication".utf8)
        let coverData = Data("test-cover".utf8)
        try publicationData.write(
            to: sourceDirectories.books.appendingPathComponent(book.localFileName)
        )
        try coverData.write(
            to: sourceDirectories.covers.appendingPathComponent(
                try XCTUnwrap(book.coverFileName)
            )
        )
        sourceDefaults.set("dark", forKey: ReaderAppearanceDefaults.themeKey)
        sourceDefaults.set(
            "must-not-leave-device",
            forKey: "test.secret.apiKey"
        )

        let exported = try await LibraryBackupService(
            rootDirectoryURL: sourceRoot,
            defaults: sourceDefaults
        ).exportArchive(
            context: sourceContext,
            to: archiveURL,
            options: .full
        )

        XCTAssertEqual(exported.scopes, LibraryBackupOptions.full.scopes)
        XCTAssertEqual(exported.summary.books, 1)
        XCTAssertGreaterThan(exported.fileSize, 0)

        let targetContainer = try makeContainer()
        let targetContext = targetContainer.mainContext
        let result = try await LibraryBackupService(
            rootDirectoryURL: targetRoot,
            defaults: targetDefaults
        ).importArchive(from: archiveURL, context: targetContext)

        XCTAssertEqual(result.restored.books, 1)
        XCTAssertEqual(result.restored.bookmarks, 1)
        XCTAssertTrue(result.didRestoreSettings)
        let restoredBook = try XCTUnwrap(
            try targetContext.fetch(FetchDescriptor<BookRecord>()).first
        )
        XCTAssertEqual(restoredBook.lastReadProgress, 0.42, accuracy: 0.0001)
        XCTAssertEqual(restoredBook.totalReadingSeconds, 321)
        XCTAssertEqual(restoredBook.category, "小说")
        let restoredDirectories = try LibraryPaths.prepareDirectories(
            rootDirectoryURL: targetRoot
        )
        XCTAssertEqual(
            try Data(
                contentsOf: restoredDirectories.books.appendingPathComponent(
                    restoredBook.localFileName
                )
            ),
            publicationData
        )
        XCTAssertEqual(
            targetDefaults.string(forKey: ReaderAppearanceDefaults.themeKey),
            "dark"
        )
        XCTAssertNil(targetDefaults.object(forKey: "test.secret.apiKey"))
    }

    func testMergeRemapsEveryRelationshipToMatchingExistingBook() throws {
        let sourceContainer = try makeContainer()
        let sourceContext = sourceContainer.mainContext
        let backupBookID = UUID()
        let locator = makeLocator()
        let locatorJSON = try XCTUnwrap(locator.readerJSONString)
        let backupBook = BookRecord(
            id: backupBookID,
            title: "同一本书",
            author: "作者",
            localFileName: "source.epub",
            fileFingerprint: "same-fingerprint",
            lastOpenedAt: Date(timeIntervalSince1970: 2_000),
            lastReadLocatorJSON: locatorJSON,
            lastReadProgress: 0.7
        )
        sourceContext.insert(backupBook)
        sourceContext.insert(
            ReadingBookmarkRecord(
                bookmarkKey: "legacy-bookmark-key",
                bookID: backupBookID,
                bookTitle: backupBook.title,
                locatorJSON: locatorJSON,
                chapterTitle: "章节",
                progress: 0.7
            )
        )
        sourceContext.insert(
            ReadingAnnotationRecord(
                annotationKey: "legacy-annotation-key",
                bookID: backupBookID,
                bookTitle: backupBook.title,
                locatorJSON: locatorJSON,
                selectedText: "选中文字",
                chapterTitle: "章节",
                progress: 0.7
            )
        )
        sourceContext.insert(
            TranslationFavoriteRecord(
                favoriteKey: "legacy-favorite-key",
                sourceText: "Hello",
                translatedText: "你好",
                sourceLanguage: .english,
                targetLanguage: .simplifiedChinese,
                providerIdentifier: "mock",
                bookID: backupBookID,
                bookTitle: backupBook.title,
                locatorJSON: locatorJSON
            )
        )
        sourceContext.insert(
            WordLookupRecord(
                explanation: WordExplanation(
                    surfaceForm: "猫",
                    lemma: "猫",
                    reading: "ねこ",
                    language: .japanese,
                    partOfSpeech: "名词",
                    definitions: ["猫科动物"],
                    usageNote: nil,
                    sentenceContext: "吾輩は猫である。"
                ),
                sourceBookID: backupBookID,
                sourceBookTitle: backupBook.title
            )
        )
        try sourceContext.save()
        let snapshot = try LibraryBackupService.snapshot(
            context: sourceContext,
            options: LibraryBackupOptions(scopes: [.reading, .learning])
        )

        let targetContainer = try makeContainer()
        let targetContext = targetContainer.mainContext
        let existingBookID = UUID()
        targetContext.insert(
            BookRecord(
                id: existingBookID,
                title: "本机书名",
                author: "作者",
                localFileName: "existing.epub",
                fileFingerprint: "same-fingerprint",
                lastOpenedAt: Date(timeIntervalSince1970: 1_000),
                lastReadProgress: 0.1
            )
        )
        try targetContext.save()

        let first = try LibraryBackupService.merge(
            records: snapshot.records,
            scopes: [.reading, .learning],
            into: targetContext
        )
        try targetContext.save()

        XCTAssertEqual(first.books, 1)
        XCTAssertEqual(first.bookmarks, 1)
        XCTAssertEqual(first.annotations, 1)
        XCTAssertEqual(first.favorites, 1)
        XCTAssertEqual(first.words, 1)
        XCTAssertEqual(
            try targetContext.fetch(FetchDescriptor<BookRecord>()).count,
            1
        )
        XCTAssertTrue(
            try targetContext.fetch(FetchDescriptor<ReadingBookmarkRecord>())
                .allSatisfy { $0.bookID == existingBookID }
        )
        XCTAssertTrue(
            try targetContext.fetch(FetchDescriptor<ReadingAnnotationRecord>())
                .allSatisfy { $0.bookID == existingBookID }
        )
        XCTAssertTrue(
            try targetContext.fetch(FetchDescriptor<TranslationFavoriteRecord>())
                .allSatisfy { $0.bookID == existingBookID }
        )
        XCTAssertTrue(
            try targetContext.fetch(FetchDescriptor<WordLookupRecord>())
                .allSatisfy { $0.sourceBookID == existingBookID }
        )

        let second = try LibraryBackupService.merge(
            records: snapshot.records,
            scopes: [.reading, .learning],
            into: targetContext
        )
        try targetContext.save()
        XCTAssertTrue(second.isEmpty)
    }

    func testExportRefusesMissingRequiredPublication() async throws {
        let workspace = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: workspace) }
        let container = try makeContainer()
        let context = container.mainContext
        context.insert(
            BookRecord(
                title: "缺少文件",
                author: "作者",
                localFileName: "missing.epub",
                fileFingerprint: "missing-publication"
            )
        )
        try context.save()

        do {
            _ = try await LibraryBackupService(
                rootDirectoryURL: workspace.appendingPathComponent("library")
            ).exportArchive(
                context: context,
                to: workspace.appendingPathComponent(
                    "invalid.\(LibraryBackupService.fileExtension)"
                ),
                options: LibraryBackupOptions(scopes: [.library])
            )
            XCTFail("A full publication backup must not silently omit a book.")
        } catch let error as LibraryBackupError {
            guard case .missingBookFile = error else {
                return XCTFail("Unexpected backup error: \(error)")
            }
        }
    }

    func testFailedExportPreservesExistingDestinationAndLeavesNoPartialFile() async throws {
        let workspace = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: workspace) }
        let container = try makeContainer()
        let context = container.mainContext
        context.insert(
            BookRecord(
                title: "缺少文件",
                author: "作者",
                localFileName: "missing.epub",
                fileFingerprint: "missing-publication-existing-destination"
            )
        )
        try context.save()

        let destination = workspace.appendingPathComponent(
            "existing.\(LibraryBackupService.fileExtension)"
        )
        try FileManager.default.createDirectory(
            at: workspace,
            withIntermediateDirectories: true
        )
        let original = Data("previous-valid-backup".utf8)
        try original.write(to: destination)

        do {
            _ = try await LibraryBackupService(
                rootDirectoryURL: workspace.appendingPathComponent("library")
            ).exportArchive(
                context: context,
                to: destination,
                options: LibraryBackupOptions(scopes: [.library])
            )
            XCTFail("The missing publication must fail the replacement export.")
        } catch let error as LibraryBackupError {
            guard case .missingBookFile = error else {
                return XCTFail("Unexpected backup error: \(error)")
            }
        }

        XCTAssertEqual(try Data(contentsOf: destination), original)
        let partials = try FileManager.default.contentsOfDirectory(
            at: workspace,
            includingPropertiesForKeys: nil
        ).filter { $0.lastPathComponent.hasSuffix(".partial") }
        XCTAssertTrue(partials.isEmpty)
    }

    func testRestoreValidatesPublicationEvenWhenMatchingBookAlreadyExists() async throws {
        let workspace = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: workspace) }
        let sourceContainer = try makeContainer()
        sourceContainer.mainContext.insert(
            BookRecord(
                title: "同一本书",
                author: "作者",
                localFileName: "source.epub",
                fileFingerprint: "existing-corrupt-check"
            )
        )
        try sourceContainer.mainContext.save()
        var snapshot = try LibraryBackupService.snapshot(
            context: sourceContainer.mainContext,
            options: LibraryBackupOptions(scopes: [.library])
        )
        let expectedData = Data("expected-publication".utf8)
        let expectedHash = SHA256.hash(data: expectedData)
            .map { String(format: "%02x", $0) }
            .joined()
        snapshot.records.books[0].archivedFileSHA256 = expectedHash
        snapshot.records.books[0].archivedFileSize = Int64(expectedData.count)

        let archiveURL = workspace.appendingPathComponent(
            "corrupt-existing.\(LibraryBackupService.fileExtension)"
        )
        let manifest = BackupManifest(
            version: 2,
            createdAt: Date(),
            appVersion: "test",
            appBuild: "1",
            summary: snapshot.summary,
            scopes: [.library]
        )
        try await createArchive(
            at: archiveURL,
            entries: [
                "manifest.json": try backupEncoder().encode(manifest),
                "records.json": try backupEncoder().encode(snapshot.records),
                "Books/source.epub": Data("corrupted-publication".utf8),
            ]
        )

        let targetContainer = try makeContainer()
        targetContainer.mainContext.insert(
            BookRecord(
                title: "本机已有书",
                author: "作者",
                localFileName: "existing.epub",
                fileFingerprint: "existing-corrupt-check"
            )
        )
        try targetContainer.mainContext.save()

        do {
            _ = try await LibraryBackupService(
                rootDirectoryURL: workspace.appendingPathComponent("target")
            ).importArchive(
                from: archiveURL,
                context: targetContainer.mainContext
            )
            XCTFail("A matching local book must not bypass archive integrity checks.")
        } catch let error as LibraryBackupError {
            guard case .archiveUnreadable = error else {
                return XCTFail("Unexpected restore error: \(error)")
            }
        }
        XCTAssertEqual(
            try targetContainer.mainContext.fetch(
                FetchDescriptor<BookRecord>()
            ).count,
            1
        )
    }

    func testPolicyDueDateHonorsEnabledIntervalAndLastSuccess() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        var policy = LibraryBackupPolicy(
            automaticEnabled: true,
            intervalDays: 7,
            retentionDays: 30,
            maximumBackupCount: 5,
            maximumTotalBytes: 1_000,
            automaticScopes: [.settings],
            lastAutomaticBackupAt: nil
        )
        XCTAssertTrue(policy.isDue(at: now))

        policy.lastAutomaticBackupAt = now.addingTimeInterval(-6 * 86_400)
        XCTAssertFalse(policy.isDue(at: now))
        policy.lastAutomaticBackupAt = now.addingTimeInterval(-7 * 86_400)
        XCTAssertTrue(policy.isDue(at: now))
        policy.automaticEnabled = false
        XCTAssertFalse(policy.isDue(at: now))
    }

    func testManagedVaultPrunesOldestByCountAndPreservesNewestOverSizeLimit() async throws {
        let workspace = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: workspace) }
        let vault = LibraryBackupVault(
            backupDirectoryURL: workspace.appendingPathComponent(
                "backups",
                isDirectory: true
            ),
            libraryRootDirectoryURL: workspace.appendingPathComponent(
                "library",
                isDirectory: true
            ),
            defaults: isolatedDefaults()
        )
        let container = try makeContainer()
        for _ in 0 ..< 3 {
            _ = try await vault.createBackup(
                context: container.mainContext,
                options: LibraryBackupOptions(scopes: [.settings])
            )
        }
        var listedBackups = try await vault.listBackups()
        XCTAssertEqual(listedBackups.count, 3)

        let countResult = try await vault.prune(
            using: LibraryBackupPolicy(
                automaticEnabled: true,
                intervalDays: 7,
                retentionDays: 0,
                maximumBackupCount: 2,
                maximumTotalBytes: 0,
                automaticScopes: [.settings],
                lastAutomaticBackupAt: nil
            )
        )
        XCTAssertEqual(countResult.removedCount, 1)
        listedBackups = try await vault.listBackups()
        XCTAssertEqual(listedBackups.count, 2)

        let sizeResult = try await vault.prune(
            using: LibraryBackupPolicy(
                automaticEnabled: true,
                intervalDays: 7,
                retentionDays: 0,
                maximumBackupCount: 20,
                maximumTotalBytes: 1,
                automaticScopes: [.settings],
                lastAutomaticBackupAt: nil
            )
        )
        listedBackups = try await vault.listBackups()
        XCTAssertEqual(listedBackups.count, 1)
        XCTAssertTrue(sizeResult.isOverLimit)
        XCTAssertGreaterThan(sizeResult.remainingBytes, 1)
    }

    func testManagedVaultRemovesOnlyStalePartialExports() async throws {
        let workspace = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: workspace) }
        let backupDirectory = workspace.appendingPathComponent(
            "backups",
            isDirectory: true
        )
        try FileManager.default.createDirectory(
            at: backupDirectory,
            withIntermediateDirectories: true
        )
        let stalePartial = backupDirectory.appendingPathComponent(
            ".interrupted.\(LibraryBackupService.fileExtension).partial"
        )
        let activePartial = backupDirectory.appendingPathComponent(
            ".active.\(LibraryBackupService.fileExtension).partial"
        )
        try Data("stale".utf8).write(to: stalePartial)
        try Data("active".utf8).write(to: activePartial)
        try FileManager.default.setAttributes(
            [.modificationDate: Date().addingTimeInterval(-25 * 60 * 60)],
            ofItemAtPath: stalePartial.path
        )

        let vault = LibraryBackupVault(
            backupDirectoryURL: backupDirectory,
            libraryRootDirectoryURL: workspace.appendingPathComponent("library"),
            defaults: isolatedDefaults()
        )
        _ = try await vault.listBackups()

        XCTAssertFalse(FileManager.default.fileExists(atPath: stalePartial.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: activePartial.path))
    }

    func testBackupDefaultValuePreservesNumberKinds() {
        XCTAssertEqual(BackupDefaultValue(NSNumber(value: true)), .bool(true))
        XCTAssertEqual(BackupDefaultValue(NSNumber(value: 7)), .integer(7))
        XCTAssertEqual(
            BackupDefaultValue(NSNumber(value: 1.25)),
            .double(1.25)
        )
    }

    func testSelectedBackupFolderPersistsAndVaultUsesItForManualAndAutomaticFiles() async throws {
        let workspace = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: workspace) }
        let selectedFolder = workspace.appendingPathComponent(
            "iCloud-like-folder",
            isDirectory: true
        )
        try FileManager.default.createDirectory(
            at: selectedFolder,
            withIntermediateDirectories: true
        )
        let defaults = isolatedDefaults()
        defer { clear(defaults) }
        let directoryStore = LibraryBackupDirectoryStore(defaults: defaults)
        try directoryStore.selectDirectory(selectedFolder)

        XCTAssertTrue(directoryStore.hasCustomDirectory)
        XCTAssertEqual(directoryStore.displayName, "iCloud-like-folder")

        let vault = LibraryBackupVault(
            libraryRootDirectoryURL: workspace.appendingPathComponent("library"),
            defaults: defaults
        )
        let container = try makeContainer()
        let automatic = try await vault.createBackup(
            context: container.mainContext,
            options: LibraryBackupOptions(scopes: [.settings])
        )
        let manual = try await vault.createBackup(
            context: container.mainContext,
            options: LibraryBackupOptions(scopes: [.settings]),
            kind: .manual
        )

        XCTAssertEqual(
            automatic.url.deletingLastPathComponent().standardizedFileURL,
            selectedFolder.standardizedFileURL
        )
        XCTAssertTrue(automatic.url.lastPathComponent.hasPrefix("自动备份-"))
        XCTAssertTrue(manual.url.lastPathComponent.hasPrefix("手动备份-"))

        let relaunchedVault = LibraryBackupVault(
            libraryRootDirectoryURL: workspace.appendingPathComponent("library"),
            defaults: defaults
        )
        let relaunchedBackups = try await relaunchedVault.listBackups()
        XCTAssertEqual(relaunchedBackups.count, 2)

        directoryStore.useDefaultDirectory()
        XCTAssertFalse(directoryStore.hasCustomDirectory)
        XCTAssertEqual(directoryStore.displayName, "App 本地/Jerreader Backups")
    }

    func testMissingSelectedBackupFolderDoesNotSilentlyFallBackToAppStorage() async throws {
        let workspace = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: workspace) }
        let selectedFolder = workspace.appendingPathComponent(
            "folder-that-will-move",
            isDirectory: true
        )
        try FileManager.default.createDirectory(
            at: selectedFolder,
            withIntermediateDirectories: true
        )
        let defaults = isolatedDefaults()
        defer { clear(defaults) }
        try LibraryBackupDirectoryStore(defaults: defaults)
            .selectDirectory(selectedFolder)
        try FileManager.default.removeItem(at: selectedFolder)

        do {
            _ = try await LibraryBackupVault(defaults: defaults).listBackups()
            XCTFail("A missing selected folder must not fall back to local storage.")
        } catch LibraryBackupError.backupFolderUnavailable {
            // Expected: the UI asks the user to select the folder again.
        } catch {
            XCTFail("Unexpected error: \(error)")
        }
    }

    func testPolicyStoreReloadsPreferencesRestoredIntoDefaults() {
        let defaults = isolatedDefaults()
        defer { clear(defaults) }
        let store = LibraryBackupPolicyStore(defaults: defaults)

        LibraryBackupService.restoreDefaults(
            [
                "backup.automatic.enabled": .bool(true),
                "backup.automatic.intervalDays": .integer(14),
                "backup.retention.days": .integer(90),
                "backup.retention.maximumCount": .integer(10),
                "backup.retention.maximumBytes": .double(
                    Double(500 * 1_024 * 1_024)
                ),
                "backup.automatic.scopes": .strings([
                    LibraryBackupScope.learning.rawValue,
                    LibraryBackupScope.settings.rawValue,
                ]),
            ],
            defaults: defaults
        )
        store.reloadPreferences()

        XCTAssertTrue(store.automaticEnabled)
        XCTAssertEqual(store.intervalDays, 14)
        XCTAssertEqual(store.retentionDays, 90)
        XCTAssertEqual(store.maximumBackupCount, 10)
        XCTAssertEqual(store.maximumTotalBytes, 500 * 1_024 * 1_024)
        XCTAssertEqual(store.automaticScopes, [.learning, .settings])
    }

    func testPolicyStoreNormalizesInvalidRuntimeValuesWithoutRecursing() {
        let defaults = isolatedDefaults()
        defer { clear(defaults) }
        let store = LibraryBackupPolicyStore(defaults: defaults)

        store.intervalDays = 2
        store.retentionDays = 12
        store.maximumBackupCount = 4
        store.maximumTotalBytes = 42
        store.automaticScopes = []

        XCTAssertEqual(store.intervalDays, 7)
        XCTAssertEqual(store.retentionDays, 30)
        XCTAssertEqual(store.maximumBackupCount, 5)
        XCTAssertEqual(
            store.maximumTotalBytes,
            2 * 1_024 * 1_024 * 1_024
        )
        XCTAssertEqual(store.automaticScopes, [.settings])
        XCTAssertEqual(defaults.integer(forKey: "backup.automatic.intervalDays"), 7)
        XCTAssertEqual(defaults.integer(forKey: "backup.retention.days"), 30)
        XCTAssertEqual(defaults.integer(forKey: "backup.retention.maximumCount"), 5)
        XCTAssertEqual(
            Int64(defaults.double(forKey: "backup.retention.maximumBytes")),
            2 * 1_024 * 1_024 * 1_024
        )
        XCTAssertEqual(
            defaults.stringArray(forKey: "backup.automatic.scopes"),
            [LibraryBackupScope.settings.rawValue]
        )
    }

    private func makeContainer() throws -> ModelContainer {
        try ModelContainer(
            for: BookRecord.self,
            ReadingBookmarkRecord.self,
            ReadingAnnotationRecord.self,
            TranslationFavoriteRecord.self,
            WordLookupRecord.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true)
        )
    }

    private func makeLocator() -> Locator {
        Locator(
            href: AnyURL(string: "chapter.xhtml")!,
            mediaType: .xhtml,
            title: "第一章",
            locations: .init(progression: 0.42, totalProgression: 0.42)
        )
    }

    private func temporaryDirectory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent(
            "JerreaderBackupTests-\(UUID().uuidString)",
            isDirectory: true
        )
    }

    private func isolatedDefaults() -> UserDefaults {
        let suite = "JerreaderBackupTests.\(UUID().uuidString)"
        return UserDefaults(suiteName: suite)!
    }

    private func clear(_ defaults: UserDefaults) {
        for key in defaults.dictionaryRepresentation().keys {
            defaults.removeObject(forKey: key)
        }
    }

    private func backupEncoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }

    private func createArchive(
        at url: URL,
        entries: [String: Data]
    ) async throws {
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        let archive = try await Archive(url: url, accessMode: .create)
        for (path, data) in entries.sorted(by: { $0.key < $1.key }) {
            try await archive.addEntry(
                with: path,
                type: .file,
                uncompressedSize: Int64(data.count),
                compressionMethod: .deflate
            ) { position, size in
                let start = min(Int(position), data.count)
                let end = min(start + size, data.count)
                return data.subdata(in: start ..< end)
            }
        }
    }
}
