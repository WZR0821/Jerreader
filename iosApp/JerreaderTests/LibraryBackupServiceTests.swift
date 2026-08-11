import CryptoKit
import Foundation
@preconcurrency import ReadiumShared
import ReadiumZIPFoundation
import SwiftData
import UniformTypeIdentifiers
import XCTest
@testable import JerreaderUnified

@MainActor
final class LibraryBackupServiceTests: XCTestCase {
    func testBackupImportPickerAllowsICloudProviderDataFallback() throws {
        let types = LibraryBackupImportPolicy.allowedContentTypes

        // A backup is a zip and is now named as one, so the picker's first and
        // most precise type is the one every File Provider already resolves.
        // Widening the list alone did not fix 「备份还是不能手动选择文件」: the
        // custom extension still had to be resolved to the exported UTI first,
        // and iCloud often would not.
        XCTAssertEqual(types.first, .zip)
        XCTAssertTrue(types.contains(.jerreaderBackup))
        XCTAssertTrue(types.contains(.archive))
        XCTAssertTrue(types.contains(.data))
        XCTAssertTrue(
            types.contains(.item),
            "A File Provider dynamic UTI must not leave an existing backup greyed out."
        )
        XCTAssertTrue(
            UTType.jerreaderBackup.conforms(to: .archive),
            "The precise backup type must remain an archive while the picker also accepts provider fallbacks."
        )
    }

    func testBackupArchivesAreNamedAsZipsAndLegacyNamesStillRestore() throws {
        XCTAssertEqual(LibraryBackupService.fileNameExtension, "jerbackup.zip")
        XCTAssertTrue(
            LibraryBackupService.isBackupArchive(
                URL(fileURLWithPath: "/tmp/Jerreader-20260807.jerbackup.zip")
            )
        )
        XCTAssertTrue(
            LibraryBackupService.isBackupArchive(
                URL(fileURLWithPath: "/tmp/Jerreader备份-1.jerreader-backup")
            ),
            "Archives written before the rename must still be listed and restored."
        )
        XCTAssertFalse(
            LibraryBackupService.isBackupArchive(URL(fileURLWithPath: "/tmp/book.epub"))
        )
    }

    func testFullBackupRoundTripRestoresPublicationReadingAndAllowedSettings() async throws {
        let workspace = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: workspace) }
        let sourceRoot = workspace.appendingPathComponent("source", isDirectory: true)
        let targetRoot = workspace.appendingPathComponent("target", isDirectory: true)
        let archiveURL = workspace.appendingPathComponent(
            "round-trip.\(LibraryBackupService.fileNameExtension)"
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
            readerTheme: "sepia",
            readerPDFPaperModeEnabled: true
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
        XCTAssertTrue(restoredBook.readerPDFPaperModeEnabled)
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
        let learningWord = WordLookupRecord(
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
                sourceBookTitle: backupBook.title,
                reviewCount: 4,
                reviewStage: 3,
                reviewIntervalDays: 8,
                reviewLapseCount: 1,
                lastReviewedAt: Date(timeIntervalSince1970: 700),
                nextReviewAt: Date(timeIntervalSince1970: 800)
            )
        sourceContext.insert(learningWord)
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
        let restoredWords = try targetContext.fetch(FetchDescriptor<WordLookupRecord>())
        XCTAssertTrue(restoredWords.allSatisfy { $0.sourceBookID == existingBookID })
        XCTAssertEqual(restoredWords.first?.reviewCount, 4)
        XCTAssertEqual(restoredWords.first?.reviewStage, 3)
        XCTAssertEqual(restoredWords.first?.reviewIntervalDays, 8)
        XCTAssertEqual(restoredWords.first?.reviewLapseCount, 1)
        XCTAssertEqual(restoredWords.first?.lastReviewedAt, Date(timeIntervalSince1970: 700))
        XCTAssertEqual(restoredWords.first?.nextReviewAt, Date(timeIntervalSince1970: 800))

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
                    "invalid.\(LibraryBackupService.fileNameExtension)"
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
            "existing.\(LibraryBackupService.fileNameExtension)"
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
            "corrupt-existing.\(LibraryBackupService.fileNameExtension)"
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
            ".interrupted.\(LibraryBackupService.fileNameExtension).partial"
        )
        let activePartial = backupDirectory.appendingPathComponent(
            ".active.\(LibraryBackupService.fileNameExtension).partial"
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

    /// The restore picker starts at `currentDirectoryAccess()`. It used to ask
    /// the directory store instead, which answers nil while the default local
    /// folder is in use — so the picker opened at Recents and showed none of
    /// the backups the settings list was displaying at the same moment.
    func testCurrentDirectoryAccessResolvesBothSelectedAndDefaultFolders() async throws {
        let workspace = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: workspace) }
        let defaults = isolatedDefaults()
        defer { clear(defaults) }

        let defaultFolder = try LibraryBackupVault(defaults: defaults)
            .currentDirectoryAccess()
            .url
        XCTAssertEqual(defaultFolder.lastPathComponent, "Jerreader Backups")
        XCTAssertTrue(
            FileManager.default.fileExists(atPath: defaultFolder.path),
            "The picker cannot open a folder that was never created."
        )

        let selectedFolder = workspace.appendingPathComponent(
            "chosen-folder",
            isDirectory: true
        )
        try FileManager.default.createDirectory(
            at: selectedFolder,
            withIntermediateDirectories: true
        )
        try LibraryBackupDirectoryStore(defaults: defaults)
            .selectDirectory(selectedFolder)

        XCTAssertEqual(
            try LibraryBackupVault(defaults: defaults)
                .currentDirectoryAccess()
                .url
                .standardizedFileURL,
            selectedFolder.standardizedFileURL
        )
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

    // MARK: - Inheriting the backup regimen

    /// The point of the manifest carrying the regimen: a user who backs up
    /// only their reading progress still gets their schedule, retention and
    /// folder back on the new installation.
    func testArchiveCarriesTheBackupRegimenEvenWithoutTheSettingsScope() async throws {
        let workspace = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: workspace) }
        let backupFolder = workspace.appendingPathComponent(
            "书房备份",
            isDirectory: true
        )
        try FileManager.default.createDirectory(
            at: backupFolder,
            withIntermediateDirectories: true
        )
        let archiveURL = workspace.appendingPathComponent(
            "regimen.\(LibraryBackupService.fileNameExtension)"
        )
        let sourceDefaults = isolatedDefaults()
        let targetDefaults = isolatedDefaults()
        defer {
            clear(sourceDefaults)
            clear(targetDefaults)
        }

        let sourcePolicy = LibraryBackupPolicyStore(defaults: sourceDefaults)
        sourcePolicy.automaticEnabled = true
        sourcePolicy.intervalDays = 3
        sourcePolicy.retentionDays = 90
        sourcePolicy.maximumBackupCount = 10
        sourcePolicy.maximumTotalBytes = 500 * 1_024 * 1_024
        sourcePolicy.automaticScopes = [.reading, .learning]
        try LibraryBackupDirectoryStore(defaults: sourceDefaults)
            .selectDirectory(backupFolder)

        let container = try makeContainer()
        let exported = try await LibraryBackupService(
            rootDirectoryURL: workspace.appendingPathComponent("source"),
            defaults: sourceDefaults
        ).exportArchive(
            context: container.mainContext,
            to: archiveURL,
            // Deliberately not `.settings`: the regimen must not ride on it.
            options: LibraryBackupOptions(scopes: [.reading])
        )

        let targetContainer = try makeContainer()
        let result = try await LibraryBackupService(
            rootDirectoryURL: workspace.appendingPathComponent("target"),
            defaults: targetDefaults
        ).importArchive(
            from: archiveURL,
            context: targetContainer.mainContext
        )

        XCTAssertFalse(result.didRestoreSettings)
        let profile = try XCTUnwrap(result.inheritedProfile)
        XCTAssertEqual(profile.folderDisplayName, "书房备份")
        XCTAssertEqual(
            profile.folderURL?.standardizedFileURL,
            backupFolder.standardizedFileURL
        )

        let targetPolicy = LibraryBackupPolicyStore(defaults: targetDefaults)
        XCTAssertTrue(targetPolicy.automaticEnabled)
        XCTAssertEqual(targetPolicy.intervalDays, 3)
        XCTAssertEqual(targetPolicy.retentionDays, 90)
        XCTAssertEqual(targetPolicy.maximumBackupCount, 10)
        XCTAssertEqual(targetPolicy.maximumTotalBytes, 500 * 1_024 * 1_024)
        XCTAssertEqual(targetPolicy.automaticScopes, [.reading, .learning])
        // The chain continues from the archive rather than firing at once.
        XCTAssertEqual(
            targetPolicy.lastAutomaticBackupAt?.timeIntervalSince1970 ?? 0,
            exported.createdAt.timeIntervalSince1970,
            accuracy: 1
        )
    }

    /// An archive from a build that offered other choices must not leave the
    /// pickers showing a row nothing can select.
    func testInheritedRegimenFallsBackForChoicesThisBuildNoLongerOffers() {
        let defaults = isolatedDefaults()
        defer { clear(defaults) }

        LibraryBackupPolicyStore.apply(
            LibraryBackupProfile(
                automaticEnabled: true,
                intervalDays: 2,
                retentionDays: 12,
                maximumBackupCount: 4,
                maximumTotalBytes: 42,
                automaticScopes: [],
                folderDisplayName: nil,
                folderPath: nil
            ),
            lastAutomaticBackupAt: nil,
            defaults: defaults
        )

        let store = LibraryBackupPolicyStore(defaults: defaults)
        XCTAssertTrue(store.automaticEnabled)
        XCTAssertEqual(store.intervalDays, 7)
        XCTAssertEqual(store.retentionDays, 30)
        XCTAssertEqual(store.maximumBackupCount, 5)
        XCTAssertEqual(store.maximumTotalBytes, 2 * 1_024 * 1_024 * 1_024)
        XCTAssertEqual(store.automaticScopes, LibraryBackupOptions.full.scopes)
        XCTAssertNil(store.lastAutomaticBackupAt)
    }

    func testAdoptingAReachableFolderTakesItOverAsTheBackupFolder() throws {
        let workspace = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: workspace) }
        let first = workspace.appendingPathComponent("first", isDirectory: true)
        let second = workspace.appendingPathComponent("second", isDirectory: true)
        for folder in [first, second] {
            try FileManager.default.createDirectory(
                at: folder,
                withIntermediateDirectories: true
            )
        }
        let defaults = isolatedDefaults()
        defer { clear(defaults) }
        let store = LibraryBackupDirectoryStore(defaults: defaults)
        try store.selectDirectory(first)

        XCTAssertTrue(store.adoptDirectory(second))
        XCTAssertEqual(store.displayName, "second")
        XCTAssertTrue(store.isSelectedDirectory(second))
        // Adopting what is already selected is a no-op success.
        XCTAssertTrue(store.adoptDirectory(second))
    }

    /// A folder named by an archive from another device is usually not
    /// reachable here. Failing to adopt it must not cost the user the folder
    /// they already had.
    func testAdoptingAnUnreachableFolderLeavesThePreviousSelectionIntact() throws {
        let workspace = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: workspace) }
        let kept = workspace.appendingPathComponent("kept", isDirectory: true)
        try FileManager.default.createDirectory(
            at: kept,
            withIntermediateDirectories: true
        )
        let defaults = isolatedDefaults()
        defer { clear(defaults) }
        let store = LibraryBackupDirectoryStore(defaults: defaults)
        try store.selectDirectory(kept)

        let missing = workspace.appendingPathComponent(
            "never-existed",
            isDirectory: true
        )
        XCTAssertFalse(store.adoptDirectory(missing))
        XCTAssertTrue(store.hasCustomDirectory)
        XCTAssertEqual(store.displayName, "kept")
        XCTAssertTrue(store.isSelectedDirectory(kept))
        XCTAssertNotNil(try store.selectedDirectoryAccess())
    }

    /// Failing to adopt a folder when none was selected must leave the app on
    /// its local folder rather than on a half-written selection.
    func testFailedAdoptionFromDefaultFolderStaysOnTheDefaultFolder() throws {
        let workspace = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: workspace) }
        let defaults = isolatedDefaults()
        defer { clear(defaults) }
        let store = LibraryBackupDirectoryStore(defaults: defaults)

        XCTAssertFalse(
            store.adoptDirectory(
                workspace.appendingPathComponent("nowhere", isDirectory: true)
            )
        )
        XCTAssertFalse(store.hasCustomDirectory)
        XCTAssertEqual(store.displayName, "App 本地/Jerreader Backups")
        XCTAssertNil(store.customDirectoryPath)
    }

    /// The app's own folder carries no folder hint: its path dies with the
    /// installation, so promising it to the next one would be a lie.
    func testDefaultFolderProducesNoFolderHint() {
        let defaults = isolatedDefaults()
        defer { clear(defaults) }
        let profile = LibraryBackupPolicyStore.currentProfile(defaults: defaults)
        XCTAssertNil(profile.folderDisplayName)
        XCTAssertNil(profile.folderPath)
        XCTAssertNil(profile.folderURL)
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
