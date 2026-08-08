import Combine
import Foundation
import SwiftData

struct LibraryBackupPolicy: Equatable, Sendable {
    var automaticEnabled: Bool
    var intervalDays: Int
    var retentionDays: Int
    var maximumBackupCount: Int
    var maximumTotalBytes: Int64
    var automaticScopes: Set<LibraryBackupScope>
    var lastAutomaticBackupAt: Date?

    var options: LibraryBackupOptions {
        LibraryBackupOptions(scopes: automaticScopes)
    }

    func isDue(at date: Date = Date()) -> Bool {
        guard automaticEnabled, !automaticScopes.isEmpty else { return false }
        guard let lastAutomaticBackupAt else { return true }
        let interval = TimeInterval(max(intervalDays, 1) * 24 * 60 * 60)
        return date.timeIntervalSince(lastAutomaticBackupAt) >= interval
    }

    func nextDate(from date: Date = Date()) -> Date? {
        guard automaticEnabled else { return nil }
        guard let lastAutomaticBackupAt else { return date }
        return Calendar.current.date(
            byAdding: .day,
            value: max(intervalDays, 1),
            to: lastAutomaticBackupAt
        )
    }
}

/// The backup regimen an archive carries with it.
///
/// A backup is only half useful if the next installation has to be told all
/// over again how often to back up, what to include and where to put it. This
/// travels inside every archive — independently of whether the user asked for
/// the settings scope — so importing one continues the same backup chain
/// instead of starting from the defaults.
///
/// The folder is a *hint*: a label and a path, never an authorization. The
/// security scoped bookmark stays on the device that granted it.
struct LibraryBackupProfile: Codable, Equatable, Sendable {
    var automaticEnabled: Bool
    var intervalDays: Int
    var retentionDays: Int
    var maximumBackupCount: Int
    var maximumTotalBytes: Int64
    var automaticScopes: [LibraryBackupScope]
    var folderDisplayName: String?
    var folderPath: String?

    var folderURL: URL? {
        guard let folderPath, !folderPath.isEmpty else { return nil }
        return URL(fileURLWithPath: folderPath, isDirectory: true)
    }
}

@MainActor
final class LibraryBackupPolicyStore: ObservableObject {
    // Nonisolated so an export or an import running off the main actor can
    // validate an inherited regimen without hopping back.
    nonisolated static let intervalChoices = [1, 3, 7, 14, 30]
    nonisolated static let retentionChoices = [7, 14, 30, 90, 365, 0]
    nonisolated static let countChoices = [2, 3, 5, 10, 20]
    nonisolated static let sizeChoices: [Int64] = [
        250 * 1_024 * 1_024,
        500 * 1_024 * 1_024,
        1 * 1_024 * 1_024 * 1_024,
        2 * 1_024 * 1_024 * 1_024,
        5 * 1_024 * 1_024 * 1_024,
        0,
    ]

    @Published var automaticEnabled: Bool {
        didSet { defaults.set(automaticEnabled, forKey: Keys.enabled) }
    }
    @Published var intervalDays: Int {
        didSet {
            guard !isNormalizingPublishedValue else { return }
            let validated = Self.validChoice(
                intervalDays,
                choices: Self.intervalChoices,
                fallback: 7
            )
            if intervalDays != validated {
                isNormalizingPublishedValue = true
                intervalDays = validated
                isNormalizingPublishedValue = false
            }
            defaults.set(validated, forKey: Keys.intervalDays)
        }
    }
    @Published var retentionDays: Int {
        didSet {
            guard !isNormalizingPublishedValue else { return }
            let validated = Self.validChoice(
                retentionDays,
                choices: Self.retentionChoices,
                fallback: 30
            )
            if retentionDays != validated {
                isNormalizingPublishedValue = true
                retentionDays = validated
                isNormalizingPublishedValue = false
            }
            defaults.set(validated, forKey: Keys.retentionDays)
        }
    }
    @Published var maximumBackupCount: Int {
        didSet {
            guard !isNormalizingPublishedValue else { return }
            let validated = Self.validChoice(
                maximumBackupCount,
                choices: Self.countChoices,
                fallback: 5
            )
            if maximumBackupCount != validated {
                isNormalizingPublishedValue = true
                maximumBackupCount = validated
                isNormalizingPublishedValue = false
            }
            defaults.set(validated, forKey: Keys.maximumCount)
        }
    }
    @Published var maximumTotalBytes: Int64 {
        didSet {
            guard !isNormalizingPublishedValue else { return }
            let validated = Self.validChoice(
                maximumTotalBytes,
                choices: Self.sizeChoices,
                fallback: 2 * 1_024 * 1_024 * 1_024
            )
            if maximumTotalBytes != validated {
                isNormalizingPublishedValue = true
                maximumTotalBytes = validated
                isNormalizingPublishedValue = false
            }
            defaults.set(Double(validated), forKey: Keys.maximumBytes)
        }
    }
    @Published var automaticScopes: Set<LibraryBackupScope> {
        didSet {
            guard !isNormalizingPublishedValue else { return }
            let validated = automaticScopes.isEmpty
                ? Set([LibraryBackupScope.settings])
                : automaticScopes
            if automaticScopes != validated {
                isNormalizingPublishedValue = true
                automaticScopes = [.settings]
                isNormalizingPublishedValue = false
            }
            defaults.set(
                validated.map(\.rawValue).sorted(),
                forKey: Keys.scopes
            )
        }
    }
    @Published private(set) var lastAutomaticBackupAt: Date?
    @Published private(set) var lastAutomaticError: String?

    private let defaults: UserDefaults
    private var isNormalizingPublishedValue = false

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        automaticEnabled = defaults.bool(forKey: Keys.enabled)
        intervalDays = Self.validChoice(
            defaults.integer(forKey: Keys.intervalDays),
            choices: Self.intervalChoices,
            fallback: 7
        )
        retentionDays = Self.validChoice(
            defaults.object(forKey: Keys.retentionDays) == nil
                ? 30
                : defaults.integer(forKey: Keys.retentionDays),
            choices: Self.retentionChoices,
            fallback: 30
        )
        maximumBackupCount = Self.validChoice(
            defaults.integer(forKey: Keys.maximumCount),
            choices: Self.countChoices,
            fallback: 5
        )
        maximumTotalBytes = Self.validChoice(
            defaults.object(forKey: Keys.maximumBytes) == nil
                ? 2 * 1_024 * 1_024 * 1_024
                : Int64(defaults.double(forKey: Keys.maximumBytes)),
            choices: Self.sizeChoices,
            fallback: 2 * 1_024 * 1_024 * 1_024
        )
        let savedScopes = (defaults.stringArray(forKey: Keys.scopes) ?? [])
            .compactMap(LibraryBackupScope.init(rawValue:))
        automaticScopes = savedScopes.isEmpty
            ? LibraryBackupOptions.full.scopes
            : Set(savedScopes)
        lastAutomaticBackupAt = defaults.object(
            forKey: Keys.lastSuccessAt
        ) as? Date
        lastAutomaticError = defaults.string(forKey: Keys.lastError)
    }

    var policy: LibraryBackupPolicy {
        LibraryBackupPolicy(
            automaticEnabled: automaticEnabled,
            intervalDays: intervalDays,
            retentionDays: retentionDays,
            maximumBackupCount: maximumBackupCount,
            maximumTotalBytes: maximumTotalBytes,
            automaticScopes: automaticScopes,
            lastAutomaticBackupAt: lastAutomaticBackupAt
        )
    }

    func markAutomaticSuccess(at date: Date) {
        lastAutomaticBackupAt = date
        lastAutomaticError = nil
        defaults.set(date, forKey: Keys.lastSuccessAt)
        defaults.removeObject(forKey: Keys.lastError)
        NotificationCenter.default.post(name: .libraryBackupDidChange, object: nil)
    }

    func markAutomaticFailure(_ message: String) {
        lastAutomaticError = message
        defaults.set(message, forKey: Keys.lastError)
        NotificationCenter.default.post(name: .libraryBackupDidChange, object: nil)
    }

    func reloadStatus() {
        lastAutomaticBackupAt = defaults.object(
            forKey: Keys.lastSuccessAt
        ) as? Date
        lastAutomaticError = defaults.string(forKey: Keys.lastError)
    }

    func reloadPreferences() {
        automaticEnabled = defaults.bool(forKey: Keys.enabled)
        intervalDays = Self.validChoice(
            defaults.integer(forKey: Keys.intervalDays),
            choices: Self.intervalChoices,
            fallback: 7
        )
        retentionDays = Self.validChoice(
            defaults.object(forKey: Keys.retentionDays) == nil
                ? 30
                : defaults.integer(forKey: Keys.retentionDays),
            choices: Self.retentionChoices,
            fallback: 30
        )
        maximumBackupCount = Self.validChoice(
            defaults.integer(forKey: Keys.maximumCount),
            choices: Self.countChoices,
            fallback: 5
        )
        maximumTotalBytes = Self.validChoice(
            defaults.object(forKey: Keys.maximumBytes) == nil
                ? 2 * 1_024 * 1_024 * 1_024
                : Int64(defaults.double(forKey: Keys.maximumBytes)),
            choices: Self.sizeChoices,
            fallback: 2 * 1_024 * 1_024 * 1_024
        )
        let savedScopes = (defaults.stringArray(forKey: Keys.scopes) ?? [])
            .compactMap(LibraryBackupScope.init(rawValue:))
        automaticScopes = savedScopes.isEmpty
            ? LibraryBackupOptions.full.scopes
            : Set(savedScopes)
        reloadStatus()
    }

    func setAutomaticScope(
        _ scope: LibraryBackupScope,
        isIncluded: Bool
    ) {
        var updated = automaticScopes
        if isIncluded {
            updated.insert(scope)
        } else if updated.count > 1 {
            updated.remove(scope)
        }
        automaticScopes = updated
    }

    nonisolated static var backupDefaultKeys: [String] {
        [
            Keys.enabled,
            Keys.intervalDays,
            Keys.retentionDays,
            Keys.maximumCount,
            Keys.maximumBytes,
            Keys.scopes,
        ]
    }

    /// Reads the regimen straight out of `UserDefaults` so an export running
    /// off the main actor does not have to hop back for it.
    nonisolated static func currentProfile(
        defaults: UserDefaults = .standard
    ) -> LibraryBackupProfile {
        let directory = LibraryBackupDirectoryStore(defaults: defaults)
        let savedScopes = (defaults.stringArray(forKey: Keys.scopes) ?? [])
            .compactMap(LibraryBackupScope.init(rawValue:))
        return LibraryBackupProfile(
            automaticEnabled: defaults.bool(forKey: Keys.enabled),
            intervalDays: validChoice(
                defaults.integer(forKey: Keys.intervalDays),
                choices: intervalChoices,
                fallback: 7
            ),
            retentionDays: validChoice(
                defaults.object(forKey: Keys.retentionDays) == nil
                    ? 30
                    : defaults.integer(forKey: Keys.retentionDays),
                choices: retentionChoices,
                fallback: 30
            ),
            maximumBackupCount: validChoice(
                defaults.integer(forKey: Keys.maximumCount),
                choices: countChoices,
                fallback: 5
            ),
            maximumTotalBytes: validChoice(
                defaults.object(forKey: Keys.maximumBytes) == nil
                    ? 2 * 1_024 * 1_024 * 1_024
                    : Int64(defaults.double(forKey: Keys.maximumBytes)),
                choices: sizeChoices,
                fallback: 2 * 1_024 * 1_024 * 1_024
            ),
            automaticScopes: (
                savedScopes.isEmpty
                    ? Array(LibraryBackupOptions.full.scopes)
                    : savedScopes
            ).sorted { $0.rawValue < $1.rawValue },
            folderDisplayName: directory.hasCustomDirectory
                ? directory.displayName
                : nil,
            folderPath: directory.customDirectoryPath
        )
    }

    /// Adopts an imported regimen. Values that this build no longer offers fall
    /// back rather than sticking as an unselectable row, and the archive's own
    /// creation date becomes the last automatic run so the inherited schedule
    /// continues from where the previous installation left off instead of
    /// firing immediately.
    nonisolated static func apply(
        _ profile: LibraryBackupProfile,
        lastAutomaticBackupAt: Date?,
        defaults: UserDefaults = .standard
    ) {
        defaults.set(profile.automaticEnabled, forKey: Keys.enabled)
        defaults.set(
            validChoice(profile.intervalDays, choices: intervalChoices, fallback: 7),
            forKey: Keys.intervalDays
        )
        defaults.set(
            validChoice(profile.retentionDays, choices: retentionChoices, fallback: 30),
            forKey: Keys.retentionDays
        )
        defaults.set(
            validChoice(profile.maximumBackupCount, choices: countChoices, fallback: 5),
            forKey: Keys.maximumCount
        )
        defaults.set(
            Double(
                validChoice(
                    profile.maximumTotalBytes,
                    choices: sizeChoices,
                    fallback: 2 * 1_024 * 1_024 * 1_024
                )
            ),
            forKey: Keys.maximumBytes
        )
        let scopes = profile.automaticScopes.isEmpty
            ? Array(LibraryBackupOptions.full.scopes)
            : profile.automaticScopes
        defaults.set(scopes.map(\.rawValue).sorted(), forKey: Keys.scopes)
        if let lastAutomaticBackupAt {
            defaults.set(lastAutomaticBackupAt, forKey: Keys.lastSuccessAt)
        }
        defaults.removeObject(forKey: Keys.lastError)
    }

    nonisolated private static func validChoice<T: Equatable>(
        _ value: T,
        choices: [T],
        fallback: T
    ) -> T {
        choices.contains(value) ? value : fallback
    }

    private enum Keys {
        static let enabled = "backup.automatic.enabled"
        static let intervalDays = "backup.automatic.intervalDays"
        static let retentionDays = "backup.retention.days"
        static let maximumCount = "backup.retention.maximumCount"
        static let maximumBytes = "backup.retention.maximumBytes"
        static let scopes = "backup.automatic.scopes"
        static let lastSuccessAt = "backup.automatic.lastSuccessAt"
        static let lastError = "backup.automatic.lastError"
    }
}

struct LibraryBackupPruneResult: Equatable, Sendable {
    let removedCount: Int
    let reclaimedBytes: Int64
    let remainingBytes: Int64
    let isOverLimit: Bool
}

/// Keeps a selected Files/iCloud Drive folder usable across app launches.
/// The bookmark itself is device-local authorization and is deliberately not
/// included in backup settings or restored onto another installation.
struct LibraryBackupDirectoryStore: @unchecked Sendable {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var hasCustomDirectory: Bool {
        defaults.data(forKey: Keys.bookmark) != nil
    }

    var displayName: String {
        defaults.string(forKey: Keys.displayName)
            ?? "App 本地/Jerreader Backups"
    }

    /// Where the selected folder currently sits. Recorded so an archive can
    /// name the folder it came from; it is a hint for the next installation's
    /// folder picker, never a grant.
    var customDirectoryPath: String? {
        guard hasCustomDirectory else { return nil }
        return defaults.string(forKey: Keys.path)
    }

    func isSelectedDirectory(_ url: URL) -> Bool {
        let wanted = url.standardizedFileURL.path
        // Installations that predate the recorded path still have to answer,
        // so fall back to resolving the bookmark.
        if let path = customDirectoryPath { return path == wanted }
        guard let access = try? selectedDirectoryAccess() else { return false }
        return access.url.standardizedFileURL.path == wanted
    }

    /// Takes `url` over as the backup folder, but only when this installation
    /// can actually keep using it.
    ///
    /// Selecting proves the folder is writable right now; resolving the
    /// bookmark straight back is what proves the grant survives the next
    /// launch, which is the whole point of inheriting a folder. Anything short
    /// of that leaves the previous selection exactly as it was.
    @discardableResult
    func adoptDirectory(_ url: URL) -> Bool {
        if isSelectedDirectory(url) { return true }
        let previousBookmark = defaults.data(forKey: Keys.bookmark)
        let previousName = defaults.string(forKey: Keys.displayName)
        let previousPath = defaults.string(forKey: Keys.path)
        do {
            try selectDirectory(url)
            guard try selectedDirectoryAccess() != nil else {
                throw LibraryBackupError.backupFolderUnavailable
            }
            return true
        } catch {
            if let previousBookmark {
                defaults.set(previousBookmark, forKey: Keys.bookmark)
            } else {
                defaults.removeObject(forKey: Keys.bookmark)
            }
            if let previousName {
                defaults.set(previousName, forKey: Keys.displayName)
            } else {
                defaults.removeObject(forKey: Keys.displayName)
            }
            if let previousPath {
                defaults.set(previousPath, forKey: Keys.path)
            } else {
                defaults.removeObject(forKey: Keys.path)
            }
            NotificationCenter.default.post(
                name: .libraryBackupDidChange,
                object: nil
            )
            return false
        }
    }

    func selectDirectory(_ url: URL) throws {
        guard url.isFileURL else {
            throw LibraryBackupError.backupFolderUnavailable
        }
        let didStart = url.startAccessingSecurityScopedResource()
        defer {
            if didStart { url.stopAccessingSecurityScopedResource() }
        }
        try Self.validateDirectory(url)
        let bookmark: Data
        do {
            // iOS document-provider bookmarks carry an implicit persistent
            // security scope. The macOS-only `.withSecurityScope` option must
            // not be used by this iOS target.
            bookmark = try url.bookmarkData(
                options: .minimalBookmark,
                includingResourceValuesForKeys: [.nameKey, .isDirectoryKey],
                relativeTo: nil
            )
        } catch {
            throw LibraryBackupError.backupFolderUnavailable
        }
        defaults.set(bookmark, forKey: Keys.bookmark)
        defaults.set(Self.userFacingName(for: url), forKey: Keys.displayName)
        defaults.set(url.standardizedFileURL.path, forKey: Keys.path)
        NotificationCenter.default.post(name: .libraryBackupDidChange, object: nil)
    }

    func useDefaultDirectory() {
        defaults.removeObject(forKey: Keys.bookmark)
        defaults.removeObject(forKey: Keys.displayName)
        defaults.removeObject(forKey: Keys.path)
        NotificationCenter.default.post(name: .libraryBackupDidChange, object: nil)
    }

    func selectedDirectoryAccess() throws -> LibraryBackupDirectoryAccess? {
        guard let bookmark = defaults.data(forKey: Keys.bookmark) else {
            return nil
        }
        var isStale = false
        let url: URL
        do {
            url = try URL(
                resolvingBookmarkData: bookmark,
                options: [.withoutUI, .withoutImplicitStartAccessing],
                relativeTo: nil,
                bookmarkDataIsStale: &isStale
            )
        } catch {
            throw LibraryBackupError.backupFolderUnavailable
        }
        let access = LibraryBackupDirectoryAccess(
            url: url,
            startsSecurityScopedAccess: true
        )
        try Self.validateDirectory(url)
        if isStale {
            do {
                let refreshed = try url.bookmarkData(
                    options: .minimalBookmark,
                    includingResourceValuesForKeys: [.nameKey, .isDirectoryKey],
                    relativeTo: nil
                )
                defaults.set(refreshed, forKey: Keys.bookmark)
                defaults.set(
                    Self.userFacingName(for: url),
                    forKey: Keys.displayName
                )
                defaults.set(url.standardizedFileURL.path, forKey: Keys.path)
            } catch {
                throw LibraryBackupError.backupFolderUnavailable
            }
        }
        return access
    }

    func accessIfNeeded(
        for fileURL: URL
    ) throws -> LibraryBackupDirectoryAccess? {
        guard let access = try selectedDirectoryAccess() else { return nil }
        let directoryPath = access.url.standardizedFileURL.path
        let filePath = fileURL.standardizedFileURL.path
        guard filePath == directoryPath
            || filePath.hasPrefix(directoryPath + "/")
        else { return nil }
        return access
    }

    private static func validateDirectory(_ url: URL) throws {
        let values: URLResourceValues
        do {
            values = try url.resourceValues(
                forKeys: [.isDirectoryKey, .isWritableKey]
            )
        } catch {
            throw LibraryBackupError.backupFolderUnavailable
        }
        guard values.isDirectory == true else {
            throw LibraryBackupError.backupFolderUnavailable
        }
        if values.isWritable == false {
            throw LibraryBackupError.backupFolderReadOnly
        }
    }

    private static func userFacingName(for url: URL) -> String {
        let folderName = (try? url.resourceValues(forKeys: [.nameKey]).name)
            ?? url.lastPathComponent
        if url.path.contains("Mobile Documents/com~apple~CloudDocs") {
            return "iCloud Drive/\(folderName)"
        }
        return folderName.isEmpty ? "所选文件夹" : folderName
    }

    private enum Keys {
        static let bookmark = "backup.directory.bookmark"
        static let displayName = "backup.directory.displayName"
        static let path = "backup.directory.path"
    }
}

final class LibraryBackupDirectoryAccess: @unchecked Sendable {
    let url: URL
    private let didStartAccess: Bool

    init(url: URL, startsSecurityScopedAccess: Bool) {
        self.url = url
        didStartAccess = startsSecurityScopedAccess
            ? url.startAccessingSecurityScopedResource()
            : false
    }

    deinit {
        if didStartAccess {
            url.stopAccessingSecurityScopedResource()
        }
    }
}

enum LibraryBackupCreationKind: Sendable {
    case automatic
    case manual

    var fileNamePrefix: String {
        switch self {
        case .automatic: "自动备份"
        case .manual: "手动备份"
        }
    }
}

struct LibraryBackupVault: @unchecked Sendable {
    private static let partialFileMaximumAge: TimeInterval = 24 * 60 * 60
    private let backupDirectoryURL: URL?
    private let libraryRootDirectoryURL: URL?
    private let defaults: UserDefaults

    init(
        backupDirectoryURL: URL? = nil,
        libraryRootDirectoryURL: URL? = nil,
        defaults: UserDefaults = .standard
    ) {
        self.backupDirectoryURL = backupDirectoryURL
        self.libraryRootDirectoryURL = libraryRootDirectoryURL
        self.defaults = defaults
    }

    func listBackups() async throws -> [LibraryBackupArchiveInfo] {
        let access = try directoryAccess(create: true)
        return await listBackups(in: access.url)
    }

    private func listBackups(
        in directory: URL
    ) async -> [LibraryBackupArchiveInfo] {
        guard let urls = try? FileManager.default.contentsOfDirectory(
                  at: directory,
                  includingPropertiesForKeys: [.fileSizeKey],
                  options: []
              )
        else { return [] }

        // Interrupted exports use a hidden `.partial` suffix and are never
        // presented as restorable backups. Clear only stale files so an active
        // export in another task is never removed.
        let cutoff = Date().addingTimeInterval(-Self.partialFileMaximumAge)
        for url in urls where url.lastPathComponent.hasSuffix(".partial") {
            let values = try? url.resourceValues(forKeys: [.contentModificationDateKey])
            if (values?.contentModificationDate ?? .distantPast) < cutoff {
                try? FileManager.default.removeItem(at: url)
            }
        }

        let service = LibraryBackupService(
            rootDirectoryURL: libraryRootDirectoryURL,
            defaults: defaults
        )
        var results: [LibraryBackupArchiveInfo] = []
        for url in urls where url.pathExtension == LibraryBackupService.fileExtension {
            if let info = try? await service.inspectArchive(at: url) {
                results.append(info)
            }
        }
        return results.sorted { $0.createdAt > $1.createdAt }
    }

    @MainActor
    func createBackup(
        context: ModelContext,
        options: LibraryBackupOptions,
        kind: LibraryBackupCreationKind = .automatic
    ) async throws -> LibraryBackupArchiveInfo {
        let access = try directoryAccess(create: true)
        let destination = access.url.appendingPathComponent(
            "\(kind.fileNamePrefix)-\(Self.fileStamp(for: Date()))-\(UUID().uuidString.prefix(6))."
                + LibraryBackupService.fileExtension,
            isDirectory: false
        )
        return try await LibraryBackupService(
            rootDirectoryURL: libraryRootDirectoryURL,
            defaults: defaults
        ).exportArchive(
            context: context,
            to: destination,
            options: options
        )
    }

    func delete(_ backup: LibraryBackupArchiveInfo) throws {
        let access = try directoryAccess(create: true)
        let directory = access.url.standardizedFileURL
        let target = backup.url.standardizedFileURL
        guard target.deletingLastPathComponent() == directory,
              target.pathExtension == LibraryBackupService.fileExtension
        else {
            throw LibraryBackupError.archiveUnreadable
        }
        try FileManager.default.removeItem(at: target)
        NotificationCenter.default.post(name: .libraryBackupDidChange, object: nil)
    }

    func prune(
        using policy: LibraryBackupPolicy
    ) async throws -> LibraryBackupPruneResult {
        let access = try directoryAccess(create: true)
        var backups = await listBackups(in: access.url)
        guard !backups.isEmpty else {
            return LibraryBackupPruneResult(
                removedCount: 0,
                reclaimedBytes: 0,
                remainingBytes: 0,
                isOverLimit: false
            )
        }

        var removedCount = 0
        var reclaimedBytes: Int64 = 0
        let now = Date()

        func removeOldest(where shouldRemove: (LibraryBackupArchiveInfo) -> Bool) {
            while backups.count > 1,
                  let index = backups.lastIndex(where: shouldRemove)
            {
                let backup = backups.remove(at: index)
                if (try? FileManager.default.removeItem(at: backup.url)) != nil {
                    removedCount += 1
                    reclaimedBytes += backup.fileSize
                }
            }
        }

        if policy.retentionDays > 0 {
            let cutoff = Calendar.current.date(
                byAdding: .day,
                value: -policy.retentionDays,
                to: now
            ) ?? .distantPast
            removeOldest { $0.createdAt < cutoff }
        }

        removeOldest { _ in backups.count > max(policy.maximumBackupCount, 1) }

        if policy.maximumTotalBytes > 0 {
            removeOldest { _ in
                backups.reduce(0) { $0 + $1.fileSize }
                    > policy.maximumTotalBytes
            }
        }

        // Re-read the directory so a file-system deletion failure is reflected
        // in the reported total instead of pretending the file disappeared.
        let remainingBackups = await listBackups(in: access.url)
        let remainingBytes = remainingBackups.reduce(0) { $0 + $1.fileSize }
        let isOverLimit = policy.maximumTotalBytes > 0
            && remainingBytes > policy.maximumTotalBytes
        if removedCount > 0 {
            NotificationCenter.default.post(
                name: .libraryBackupDidChange,
                object: nil
            )
        }
        return LibraryBackupPruneResult(
            removedCount: removedCount,
            reclaimedBytes: reclaimedBytes,
            remainingBytes: remainingBytes,
            isOverLimit: isOverLimit
        )
    }

    private func directoryAccess(
        create: Bool
    ) throws -> LibraryBackupDirectoryAccess {
        let directory: URL
        if let backupDirectoryURL {
            directory = backupDirectoryURL
        } else if let access = try LibraryBackupDirectoryStore(
            defaults: defaults
        ).selectedDirectoryAccess() {
            // Never recreate a missing user-selected folder at an obsolete
            // provider path. The user must explicitly grant a new location.
            return access
        } else {
            let documents = try FileManager.default.url(
                for: .documentDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            )
            directory = documents.appendingPathComponent(
                "Jerreader Backups",
                isDirectory: true
            )
        }
        if create {
            do {
                try FileManager.default.createDirectory(
                    at: directory,
                    withIntermediateDirectories: true
                )
            } catch {
                throw LibraryBackupError.storageUnavailable
            }
        }
        return LibraryBackupDirectoryAccess(
            url: directory,
            startsSecurityScopedAccess: false
        )
    }

    private static func fileStamp(for date: Date) -> String {
        let components = Calendar(identifier: .gregorian).dateComponents(
            [.year, .month, .day, .hour, .minute, .second],
            from: date
        )
        return String(
            format: "%04d%02d%02d-%02d%02d%02d",
            components.year ?? 0,
            components.month ?? 0,
            components.day ?? 0,
            components.hour ?? 0,
            components.minute ?? 0,
            components.second ?? 0
        )
    }
}

@MainActor
final class LibraryBackupOperationCoordinator: ObservableObject {
    enum Operation: String, Sendable {
        case exporting
        case creatingManual
        case creatingAutomatic
        case restoring
        case pruning
        case deleting
    }

    static let shared = LibraryBackupOperationCoordinator()

    @Published private(set) var currentOperation: Operation?

    var isRunning: Bool { currentOperation != nil }

    func perform<T: Sendable>(
        _ operation: Operation,
        body: @MainActor () async throws -> T
    ) async throws -> T {
        guard currentOperation == nil else {
            throw LibraryBackupError.operationInProgress
        }
        currentOperation = operation
        defer { currentOperation = nil }
        return try await body()
    }

    func performIfAvailable<T: Sendable>(
        _ operation: Operation,
        body: @MainActor () async throws -> T
    ) async -> Result<T, Error>? {
        guard currentOperation == nil else { return nil }
        do {
            return .success(try await perform(operation, body: body))
        } catch {
            return .failure(error)
        }
    }
}

@MainActor
final class LibraryBackupAutomation {
    static let shared = LibraryBackupAutomation()

    func performIfDue(context: ModelContext) async {
        let settings = LibraryBackupPolicyStore()
        let policy = settings.policy
        guard policy.isDue() else { return }

        let result = await LibraryBackupOperationCoordinator.shared.performIfAvailable(
            .creatingAutomatic
        ) {
            let vault = LibraryBackupVault()
            let info = try await vault.createBackup(
                context: context,
                options: policy.options
            )
            settings.markAutomaticSuccess(at: info.createdAt)
            _ = try await vault.prune(using: settings.policy)
        }
        if case let .failure(error)? = result {
            settings.markAutomaticFailure(
                (error as? LocalizedError)?.errorDescription
                    ?? "自动备份没有完成。"
            )
        }
    }
}

extension Notification.Name {
    static let libraryBackupDidChange = Notification.Name(
        "LibraryBackupDidChange"
    )
}
