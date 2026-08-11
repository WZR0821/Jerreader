import SwiftData
import SwiftUI
import UniformTypeIdentifiers
import UIKit

extension UTType {
    static let jerreaderBackup = UTType(
        exportedAs: LibraryBackupService.typeIdentifier,
        conformingTo: .archive
    )
}

enum LibraryBackupImportPolicy {
    /// File Provider extensions do not always resolve a custom filename
    /// extension to its exported UTI before the iCloud item is materialized,
    /// which is why new archives are named `.zip` — that one needs nothing
    /// resolved. The rest of the list keeps archives written by earlier
    /// versions selectable: the exported type when the provider does resolve
    /// it, then the generic types for when it hands back a dynamic UTI that
    /// conforms only to `public.item`. Import is safe at every level because
    /// `LibraryBackupService` validates the ZIP, the manifest version, the
    /// entry digests and the payload before it writes anything.
    static var allowedContentTypes: [UTType] {
        [.zip, .jerreaderBackup, .archive, .data, .item]
    }
}

/// A local backup center for side-loaded installations.
struct LibraryBackupSettingsView: View {
    @Environment(\.modelContext) private var modelContext

    @ObservedObject var translationSettings: TranslationSettingsStore
    /// Set by the empty-library entry point, so a fresh installation lands
    /// directly on the file picker instead of having to find the restore row.
    var automaticallyPresentsImport = false

    @StateObject private var policy = LibraryBackupPolicyStore()
    @StateObject private var operationCoordinator =
        LibraryBackupOperationCoordinator.shared

    @State private var manualScopes = LibraryBackupOptions.full.scopes
    @State private var backups: [LibraryBackupArchiveInfo] = []
    @State private var state: BackupUIState = .idle
    @State private var restoreImportRequest: BackupImportRequest?
    @State private var deferredBackupImportResult: Result<URL, Error>?
    @State private var isShowingFolderPicker = false
    @State private var folderPickerStart: URL?
    @State private var suggestedFolder: BackupFolderSuggestion?
    @State private var backupLocationName = LibraryBackupDirectoryStore().displayName
    @State private var hasCustomBackupLocation = LibraryBackupDirectoryStore()
        .hasCustomDirectory
    @State private var backupLocationError: String?
    @State private var sharedFile: BackupSharedFile?
    @State private var sharedDirectoryAccess: LibraryBackupDirectoryAccess?
    @State private var sharedTemporaryFileURL: URL?
    @State private var message: BackupMessage?
    @State private var pendingRestoreURL: URL?
    @State private var pendingDelete: LibraryBackupArchiveInfo?
    @State private var didPresentInitialImport = false

    private enum BackupUIState: Equatable {
        case idle
        case exporting
        case creatingManual
        case creatingLocal
        case importing
        case pruning

        var isBusy: Bool { self != .idle }
    }

    private struct BackupMessage: Identifiable {
        let id = UUID()
        let title: String
        let body: String
    }

    /// A folder an imported archive says it came from, which this installation
    /// has no grant for yet. Offered as a row rather than another alert: the
    /// restore result is already an alert, and stacking presentations here is
    /// exactly what broke the document picker before.
    private struct BackupFolderSuggestion: Equatable {
        let url: URL
        let name: String
    }

    var body: some View {
        Form {
            backupLocationSection
            automaticStatusSection
            automaticPolicySection
                .disabled(!policy.automaticEnabled)
            automaticScopeSection
                .disabled(!policy.automaticEnabled)
            manualBackupSection
            restoreSection
            managedBackupsSection
            safetySection
        }
        .scrollContentBackground(.hidden)
        .background(JerreaderCanvasBackground())
        .navigationTitle("备份中心")
        .navigationBarTitleDisplayMode(.inline)
        .background(
            // Not a `.sheet`: see `BackupImportPickerHost`. The picker is
            // presented through UIKit so it can wait for the presenting sheet
            // to settle instead of being silently dropped.
            BackupImportPickerHost(request: $restoreImportRequest) { result in
                deferredBackupImportResult = result
                finishBackupImportPresentation()
            }
            .frame(width: 0, height: 0)
            .accessibilityHidden(true)
        )
        .task { await refreshBackups() }
        .task { await presentInitialImportIfNeeded() }
        .onReceive(
            NotificationCenter.default.publisher(for: .libraryBackupDidChange)
        ) { _ in
            Task { await refreshBackups() }
        }
        .onChange(of: policy.automaticEnabled) { _, enabled in
            guard enabled else { return }
            Task {
                await LibraryBackupAutomation.shared.performIfDue(
                    context: modelContext
                )
                await refreshBackups()
            }
        }
        .onChange(of: policy.retentionDays) { _, _ in
            Task { await pruneAndRefresh() }
        }
        .onChange(of: policy.maximumBackupCount) { _, _ in
            Task { await pruneAndRefresh() }
        }
        .onChange(of: policy.maximumTotalBytes) { _, _ in
            Task { await pruneAndRefresh() }
        }
        .sheet(isPresented: $isShowingFolderPicker) {
            BackupFolderPicker(startDirectory: folderPickerStart) { result in
                isShowingFolderPicker = false
                switch result {
                case let .success(url):
                    Task { await selectBackupFolder(url) }
                case let .failure(error) where error is CancellationError:
                    break
                case let .failure(error):
                    message = BackupMessage(
                        title: "没有选择文件夹",
                        body: error.localizedDescription
                    )
                }
            }
            .ignoresSafeArea()
        }
        .sheet(item: $sharedFile, onDismiss: discardSharedTemporaryFile) {
            BackupShareSheet(fileURL: $0.url)
        }
        .alert(item: $message) { message in
            Alert(
                title: Text(message.title),
                message: Text(message.body),
                dismissButton: .default(Text("好"))
            )
        }
        .confirmationDialog(
            "从这份备份恢复？",
            isPresented: Binding(
                get: { pendingRestoreURL != nil },
                set: { if !$0 { pendingRestoreURL = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("开始恢复") {
                guard let url = pendingRestoreURL else { return }
                pendingRestoreURL = nil
                Task { await restoreBackup(from: url) }
            }
            Button("取消", role: .cancel) {
                pendingRestoreURL = nil
            }
        } message: {
            Text("恢复会校验文件并补齐缺少的数据。同一本书会按文件指纹合并，较新的本机阅读进度不会被较旧备份覆盖。")
        }
        .confirmationDialog(
            "删除这份备份？",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("删除", role: .destructive) {
                guard let backup = pendingDelete else { return }
                pendingDelete = nil
                Task { await deleteBackup(backup) }
            }
            Button("取消", role: .cancel) {
                pendingDelete = nil
            }
        } message: {
            Text("只删除这一个备份文件，不会删除书架或阅读数据。")
        }
    }

    private var backupLocationSection: some View {
        Section {
            LabeledContent("当前文件夹") {
                Text(backupLocationName)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.trailing)
            }

            if let backupLocationError {
                Label(backupLocationError, systemImage: "exclamationmark.triangle")
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }

            if let suggestedFolder {
                VStack(alignment: .leading, spacing: 6) {
                    Label(
                        "刚恢复的备份来自「\(suggestedFolder.name)」",
                        systemImage: "arrow.uturn.backward.circle"
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                    Button {
                        folderPickerStart = suggestedFolder.url
                        isShowingFolderPicker = true
                    } label: {
                        Label("继续备份到这个文件夹", systemImage: "folder.badge.gearshape")
                    }
                    .disabled(isBusy)
                }
            }

            Button {
                folderPickerStart = nil
                isShowingFolderPicker = true
            } label: {
                Label("选择备份文件夹", systemImage: "folder.badge.plus")
            }
            .disabled(isBusy)

            if hasCustomBackupLocation {
                Button {
                    useDefaultBackupFolder()
                } label: {
                    Label("改回 App 本地文件夹", systemImage: "iphone")
                }
                .disabled(isBusy)
            }
        } header: {
            Text("备份位置")
        } footer: {
            Text("可选择 iCloud Drive、“我的 iPhone”或其他文件提供商中的文件夹。自动备份和“保存到当前文件夹”都会写入这里；切换位置不会移动或删除旧文件。重装后重新选择同一文件夹，即可在下方直接看到并恢复备份。")
        }
    }

    private var automaticStatusSection: some View {
        Section {
            Toggle("自动备份", isOn: $policy.automaticEnabled)

            if policy.automaticEnabled {
                LabeledContent("上次完成") {
                    Text(
                        policy.lastAutomaticBackupAt.map {
                            $0.formatted(date: .abbreviated, time: .shortened)
                        } ?? "尚未备份"
                    )
                    .foregroundStyle(.secondary)
                }
                LabeledContent("下次到期") {
                    Text(
                        policy.policy.nextDate()?.formatted(
                            date: .abbreviated,
                            time: .shortened
                        ) ?? "—"
                    )
                    .foregroundStyle(.secondary)
                }
                if let error = policy.lastAutomaticError {
                    Label(error, systemImage: "exclamationmark.triangle")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }

                Button {
                    Task { await createLocalBackup() }
                } label: {
                    HStack {
                        Label("立即备份到当前文件夹", systemImage: "externaldrive.badge.plus")
                        Spacer()
                        if state == .creatingLocal {
                            ProgressView()
                        }
                    }
                }
                .disabled(isBusy)
            }
        } header: {
            Text("自动备份")
        } footer: {
            Text("iOS 会在 App 启动、回到前台或进入后台且备份已到期时补做；系统不保证 App 完全未运行时准点唤醒。")
        }
    }

    private var automaticPolicySection: some View {
        Section("自动策略") {
            Picker("备份间隔", selection: $policy.intervalDays) {
                ForEach(LibraryBackupPolicyStore.intervalChoices, id: \.self) {
                    Text($0 == 1 ? "每天" : "每 \($0) 天").tag($0)
                }
            }
            .accessibilityIdentifier("backup-interval-picker")

            Picker("保存时间", selection: $policy.retentionDays) {
                ForEach(LibraryBackupPolicyStore.retentionChoices, id: \.self) {
                    Text($0 == 0 ? "不按时间删除" : "保留 \($0) 天").tag($0)
                }
            }
            .accessibilityIdentifier("backup-retention-picker")

            Picker("最多保留", selection: $policy.maximumBackupCount) {
                ForEach(LibraryBackupPolicyStore.countChoices, id: \.self) {
                    Text("\($0) 份").tag($0)
                }
            }
            .accessibilityIdentifier("backup-count-picker")

            Picker("总容量上限", selection: $policy.maximumTotalBytes) {
                ForEach(LibraryBackupPolicyStore.sizeChoices, id: \.self) {
                    Text($0 == 0 ? "不限容量" : byteCount($0)).tag($0)
                }
            }
            .accessibilityIdentifier("backup-capacity-picker")
        }
    }

    private var automaticScopeSection: some View {
        Section {
            BackupScopePicker(scopes: Binding(
                get: { policy.automaticScopes },
                set: { updated in
                    if !updated.isEmpty {
                        policy.automaticScopes = updated
                    }
                }
            ))
        } header: {
            Text("自动备份范围")
        } footer: {
            Text("包含书籍文件会让备份明显变大，但它也是重装后无需重新找书的完整方案。")
        }
    }

    private var manualBackupSection: some View {
        Section {
            BackupScopePicker(scopes: $manualScopes)

            Button {
                Task { await saveManualBackupToCurrentFolder() }
            } label: {
                HStack {
                    Label("保存到当前文件夹", systemImage: "folder.badge.plus")
                    Spacer()
                    if state == .creatingManual {
                        ProgressView()
                    }
                }
            }
            .disabled(isBusy || manualScopes.isEmpty)

            Button {
                Task { await exportManualBackup() }
            } label: {
                HStack {
                    Label("导出到其他位置", systemImage: "square.and.arrow.up")
                    Spacer()
                    if state == .exporting {
                        ProgressView()
                    }
                }
            }
            .disabled(isBusy || manualScopes.isEmpty)
        } header: {
            Text("手动备份")
        } footer: {
            Text("“保存到当前文件夹”会直接生成一份可在下方恢复的备份；“导出到其他位置”适合临时另存一份。")
        }
    }

    private var restoreSection: some View {
        Section {
            Button {
                deferredBackupImportResult = nil
                restoreImportRequest = BackupImportRequest()
            } label: {
                HStack {
                    Label("从备份文件恢复", systemImage: "square.and.arrow.down")
                    Spacer()
                    if state == .importing {
                        ProgressView()
                    }
                }
            }
            .disabled(isBusy)
        } header: {
            Text("恢复")
        } footer: {
            Text("可恢复完整备份或部分备份。损坏、版本过新、文件缺失或摘要不一致的备份会在写入书架前被拒绝。")
        }
    }

    private var managedBackupsSection: some View {
        Section {
            if backups.isEmpty {
                ContentUnavailableView(
                    "当前文件夹还没有备份",
                    systemImage: "externaldrive",
                    description: Text("可立即备份，或重新选择以前保存备份的文件夹。")
                )
                .frame(maxWidth: .infinity)
            } else {
                ForEach(backups) { backup in
                    ManagedBackupRow(backup: backup) {
                        pendingRestoreURL = backup.url
                    } onShare: {
                        shareBackup(backup)
                    } onDelete: {
                        pendingDelete = backup
                    }
                }
            }
        } header: {
            HStack {
                Text("当前文件夹中的备份")
                Spacer()
                if state == .pruning {
                    ProgressView()
                } else if !backups.isEmpty {
                    Text("\(backups.count) 份 · \(byteCount(totalBackupBytes))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        } footer: {
            Text("列表直接读取当前文件夹。到期、超出份数或容量时会从最旧的开始清理，并尽量保留最新一份。")
        }
    }

    private var safetySection: some View {
        Section("重要说明") {
            Label(
                "API Key 和代理访问凭据不进入任何备份；重装后需要重新填写。",
                systemImage: "key"
            )
            Label(
                "App 本地文件夹会随卸载删除；iCloud Drive 等外部文件夹中的备份不会随 App 卸载删除。重装后需要再次选择该文件夹。",
                systemImage: "exclamationmark.shield"
            )
        }
        .font(.footnote)
    }

    private var totalBackupBytes: Int64 {
        backups.reduce(0) { $0 + $1.fileSize }
    }

    private var isBusy: Bool {
        state.isBusy || operationCoordinator.isRunning
    }

    @MainActor
    private func exportManualBackup() async {
        state = .exporting
        defer { state = .idle }
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent(
                "Jerreader备份-\(backupFileStamp(for: Date()))-\(UUID().uuidString.prefix(6))."
                    + LibraryBackupService.fileNameExtension,
                isDirectory: false
        )
        do {
            _ = try await operationCoordinator.perform(.exporting) {
                try await LibraryBackupService().exportArchive(
                    context: modelContext,
                    to: destination,
                    options: LibraryBackupOptions(scopes: manualScopes)
                )
            }
            sharedFile = BackupSharedFile(
                url: destination
            )
            sharedTemporaryFileURL = destination
        } catch {
            message = BackupMessage(
                title: "导出没有完成",
                body: backupErrorMessage(error)
            )
        }
    }

    @MainActor
    private func saveManualBackupToCurrentFolder() async {
        state = .creatingManual
        defer { state = .idle }
        do {
            let output = try await operationCoordinator.perform(.creatingManual) {
                let vault = LibraryBackupVault()
                let info = try await vault.createBackup(
                    context: modelContext,
                    options: LibraryBackupOptions(scopes: manualScopes),
                    kind: .manual
                )
                let result = try await vault.prune(using: policy.policy)
                return (info, result)
            }
            await refreshBackups()
            var body = "已保存 \(output.0.url.lastPathComponent)，文件大小 \(byteCount(output.0.fileSize))。"
            if output.1.removedCount > 0 {
                body += " 同时按保留规则清理了 \(output.1.removedCount) 份旧备份。"
            }
            message = BackupMessage(title: "手动备份完成", body: body)
        } catch {
            message = BackupMessage(
                title: "手动备份没有完成",
                body: backupErrorMessage(error)
            )
        }
    }

    @MainActor
    private func createLocalBackup() async {
        state = .creatingLocal
        defer { state = .idle }
        do {
            let output = try await operationCoordinator.perform(.creatingAutomatic) {
                let vault = LibraryBackupVault()
                let info = try await vault.createBackup(
                    context: modelContext,
                    options: policy.policy.options
                )
                policy.markAutomaticSuccess(at: info.createdAt)
                let result = try await vault.prune(using: policy.policy)
                return (info, result)
            }
            let (info, result) = output
            await refreshBackups()
            var body = "已保存 \(info.summary.description)，文件大小 \(byteCount(info.fileSize))。"
            if result.removedCount > 0 {
                body += " 同时清理了 \(result.removedCount) 份旧备份。"
            }
            if result.isOverLimit {
                body += " 最新一份自身已超过容量上限，因此仍被保留。"
            }
            message = BackupMessage(title: "备份完成", body: body)
        } catch {
            policy.markAutomaticFailure(backupErrorMessage(error))
            message = BackupMessage(
                title: "备份没有完成",
                body: backupErrorMessage(error)
            )
        }
    }

    @MainActor
    private func restoreBackup(from url: URL) async {
        state = .importing
        defer { state = .idle }
        do {
            let directoryAccess = try LibraryBackupDirectoryStore()
                .accessIfNeeded(for: url)
            let result = try await operationCoordinator.perform(.restoring) { [directoryAccess] in
                _ = directoryAccess
                return try await LibraryBackupService().importArchive(
                    from: url,
                    context: modelContext
                )
            }
            if result.didRestoreSettings {
                translationSettings.reloadNonSensitivePreferences()
            }
            // The regimen travels in the manifest whether or not the settings
            // scope was included, so the store is reloaded either way.
            policy.reloadPreferences()
            let folderText = inheritBackupFolder(from: result.inheritedProfile)
            await refreshBackups()
            let restoredText = result.restored.isEmpty
                ? "备份中的数据已存在，没有产生重复记录。"
                : "已恢复 \(result.restored.description)。"
            let settingsText = result.didRestoreSettings
                ? " 阅读与翻译偏好也已恢复；API Key 未包含在备份中。"
                : ""
            let policyText = result.inheritedProfile == nil
                ? ""
                : " 备份计划（频率、保留、范围）已按这份备份继续。"
            message = BackupMessage(
                title: "恢复完成",
                body: restoredText + settingsText + policyText + folderText
            )
        } catch {
            message = BackupMessage(
                title: "恢复没有完成",
                body: backupErrorMessage(error)
            )
        }
    }

    /// Opens the document picker for the empty-library entry point.
    ///
    /// No wait here any more: this screen is itself being presented as a sheet,
    /// and `BackupImportPickerHost` is what holds the request until that
    /// transition has finished. Sleeping a guessed number of milliseconds is
    /// what used to lose the race on a cold launch.
    @MainActor
    private func presentInitialImportIfNeeded() async {
        guard automaticallyPresentsImport, !didPresentInitialImport else { return }
        didPresentInitialImport = true
        guard restoreImportRequest == nil, !isBusy else { return }
        deferredBackupImportResult = nil
        restoreImportRequest = BackupImportRequest()
    }

    /// Tries to continue backing up where the archive came from, and returns
    /// the sentence describing what happened.
    ///
    /// Silent adoption only ever works when this installation can already
    /// reach that folder — the app's own container, or a folder still granted
    /// from before. iOS hands out per-item authorization, so a folder from a
    /// previous installation needs the user to confirm it once; the suggestion
    /// row turns that into a single tap on the right folder.
    @MainActor
    @discardableResult
    private func inheritBackupFolder(
        from profile: LibraryBackupProfile?
    ) -> String {
        guard let profile, let folder = profile.folderURL else { return "" }
        let name = profile.folderDisplayName ?? folder.lastPathComponent
        let store = LibraryBackupDirectoryStore()
        if store.isSelectedDirectory(folder) {
            suggestedFolder = nil
            return ""
        }
        if store.adoptDirectory(folder) {
            suggestedFolder = nil
            reloadBackupLocationDescription()
            return " 备份文件夹已继续使用「\(name)」。"
        }
        suggestedFolder = BackupFolderSuggestion(url: folder, name: name)
        return " 这份备份原本存放在「\(name)」，可在上方“备份位置”里一键继续用它。"
    }

    private func finishBackupImportPresentation() {
        guard let result = deferredBackupImportResult else { return }
        deferredBackupImportResult = nil
        switch result {
        case let .success(url):
            // A confirmation dialog cannot be presented while the document
            // picker is still dismissing. The host only reports a result once
            // its presenter is idle again, so promoting the selection here is
            // safe — doing it any earlier is how a picked backup could produce
            // no confirmation at all.
            pendingRestoreURL = url
        case let .failure(error) where error is CancellationError:
            break
        case let .failure(error):
            message = BackupMessage(
                title: "没有选择备份",
                body: error.localizedDescription
            )
        }
    }

    @MainActor
    private func deleteBackup(_ backup: LibraryBackupArchiveInfo) async {
        do {
            try await operationCoordinator.perform(.deleting) {
                try LibraryBackupVault().delete(backup)
            }
            await refreshBackups()
        } catch {
            message = BackupMessage(
                title: "删除没有完成",
                body: backupErrorMessage(error)
            )
        }
    }

    @MainActor
    private func refreshBackups() async {
        do {
            backups = try await LibraryBackupVault().listBackups()
            backupLocationError = nil
        } catch {
            backups = []
            backupLocationError = backupErrorMessage(error)
        }
        reloadBackupLocationDescription()
        policy.reloadStatus()
    }

    @MainActor
    private func pruneAndRefresh() async {
        guard state == .idle, !operationCoordinator.isRunning else { return }
        state = .pruning
        let result = await operationCoordinator.performIfAvailable(.pruning) {
            try await LibraryBackupVault().prune(using: policy.policy)
        }
        if case let .failure(error)? = result {
            backupLocationError = backupErrorMessage(error)
        }
        do {
            backups = try await LibraryBackupVault().listBackups()
            backupLocationError = nil
        } catch {
            backups = []
            backupLocationError = backupErrorMessage(error)
        }
        state = .idle
    }

    @MainActor
    private func selectBackupFolder(_ url: URL) async {
        folderPickerStart = nil
        do {
            try LibraryBackupDirectoryStore().selectDirectory(url)
            suggestedFolder = nil
            reloadBackupLocationDescription()
            await refreshBackups()
            guard backupLocationError == nil else { return }
            message = BackupMessage(
                title: "备份位置已更改",
                body: "以后自动备份和保存到当前文件夹都会写入 \(backupLocationName)。"
            )
        } catch {
            backupLocationError = backupErrorMessage(error)
            message = BackupMessage(
                title: "无法使用这个文件夹",
                body: backupErrorMessage(error)
            )
        }
    }

    private func useDefaultBackupFolder() {
        LibraryBackupDirectoryStore().useDefaultDirectory()
        reloadBackupLocationDescription()
        Task { await refreshBackups() }
    }

    private func reloadBackupLocationDescription() {
        let store = LibraryBackupDirectoryStore()
        backupLocationName = store.displayName
        hasCustomBackupLocation = store.hasCustomDirectory
    }

    private func shareBackup(_ backup: LibraryBackupArchiveInfo) {
        do {
            sharedDirectoryAccess = try LibraryBackupDirectoryStore()
                .accessIfNeeded(for: backup.url)
            sharedFile = BackupSharedFile(url: backup.url)
        } catch {
            message = BackupMessage(
                title: "无法导出备份",
                body: backupErrorMessage(error)
            )
        }
    }

    private func discardSharedTemporaryFile() {
        sharedDirectoryAccess = nil
        guard let sharedTemporaryFileURL else { return }
        try? FileManager.default.removeItem(at: sharedTemporaryFileURL)
        self.sharedTemporaryFileURL = nil
    }

    private func backupErrorMessage(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription
            ?? "请稍后重试，并确认本机有足够的可用空间。"
    }
}

private struct BackupScopePicker: View {
    @Binding var scopes: Set<LibraryBackupScope>

    var body: some View {
        ForEach(LibraryBackupScope.allCases) { scope in
            Toggle(isOn: binding(for: scope)) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(scope.title)
                    Text(scope.detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private func binding(for scope: LibraryBackupScope) -> Binding<Bool> {
        Binding(
            get: { scopes.contains(scope) },
            set: { included in
                if included {
                    scopes.insert(scope)
                } else if scopes.count > 1 {
                    scopes.remove(scope)
                }
            }
        )
    }
}

private struct ManagedBackupRow: View {
    let backup: LibraryBackupArchiveInfo
    let onRestore: () -> Void
    let onShare: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "externaldrive.fill")
                .font(.title3)
                .foregroundStyle(JerreaderTheme.accent)

            VStack(alignment: .leading, spacing: 4) {
                Text(
                    backup.createdAt.formatted(
                        date: .abbreviated,
                        time: .shortened
                    )
                )
                .font(.body.weight(.medium))
                Text(backup.url.lastPathComponent)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Text("\(backup.summary.description) · \(byteCount(backup.fileSize))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                Text(
                    backup.scopes
                        .sorted { $0.rawValue < $1.rawValue }
                        .map(\.title)
                        .joined(separator: "、")
                )
                .font(.caption2)
                .foregroundStyle(.tertiary)
                .lineLimit(2)
            }
            Spacer(minLength: 8)

            Button("恢复", action: onRestore)
                .font(.caption.weight(.semibold))
                .buttonStyle(.bordered)
                .accessibilityHint("直接恢复当前文件夹中的这份备份")

            Menu {
                Button("导出副本", systemImage: "square.and.arrow.up", action: onShare)
                Button(
                    "删除",
                    systemImage: "trash",
                    role: .destructive,
                    action: onDelete
                )
            } label: {
                Image(systemName: "ellipsis.circle")
                    .font(.title3)
            }
        }
        .padding(.vertical, 2)
    }
}

private struct BackupSharedFile: Identifiable {
    let id = UUID()
    let url: URL
}

private struct BackupImportRequest: Identifiable {
    let id = UUID()
}

private struct BackupShareSheet: UIViewControllerRepresentable {
    let fileURL: URL

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(
            activityItems: [fileURL],
            applicationActivities: nil
        )
    }

    func updateUIViewController(
        _ uiViewController: UIActivityViewController,
        context: Context
    ) {}
}

/// Presents the backup document picker through UIKit instead of a second
/// SwiftUI sheet.
///
/// The backup centre is itself a sheet — the shelf's 从备份恢复 entry presents
/// it — and a `.sheet` started while that presentation is still animating is
/// dropped by UIKit without telling SwiftUI. The `item` binding stayed non-nil
/// afterwards, so every later tap on 从备份文件恢复 assigned a value the binding
/// already considered presented and *nothing opened at all*: the file picker
/// could not be reached again without leaving the screen. That is the failure
/// the shelf entry hit most, because it is the one path that opens the picker
/// during a sheet transition.
///
/// A view controller can wait. This one holds the request until the top-most
/// presented controller is on screen and idle, then presents, and reports the
/// result only once that controller is gone again — a confirmation dialog
/// raised while the picker is still dismissing is dropped the same way.
private struct BackupImportPickerHost: UIViewControllerRepresentable {
    @Binding var request: BackupImportRequest?
    let onResult: (Result<URL, Error>) -> Void

    func makeUIViewController(context: Context) -> BackupImportPickerHostController {
        BackupImportPickerHostController()
    }

    func updateUIViewController(
        _ uiViewController: BackupImportPickerHostController,
        context: Context
    ) {
        uiViewController.onResult = { result in
            request = nil
            onResult(result)
        }
        uiViewController.present(request: request?.id)
    }
}

@MainActor
final class BackupImportPickerHostController: UIViewController, UIDocumentPickerDelegate {
    /// Long enough for a sheet transition plus a slow File Provider, short
    /// enough that a wedged presenter cannot hold a request forever.
    private static let retryInterval: TimeInterval = 0.1
    private static let maximumWait: TimeInterval = 6

    var onResult: ((Result<URL, Error>) -> Void)?

    private var pendingRequestID: UUID?
    private var presentedRequestID: UUID?
    private var waited: TimeInterval = 0
    /// Retained for the picker's lifetime so iCloud/File Provider has time to
    /// materialize a placeholder before `asCopy` returns the local copy.
    private var directoryAccess: LibraryBackupDirectoryAccess?

    override func loadView() {
        let view = UIView(frame: .zero)
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        self.view = view
    }

    func present(request id: UUID?) {
        guard id != pendingRequestID else { return }
        pendingRequestID = id
        waited = 0
        guard id != nil else { return }
        presentWhenReady()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        // The request can be set before this controller reaches a window, in
        // which case there was nothing to present from at the time.
        presentWhenReady()
    }

    private func presentWhenReady() {
        guard let id = pendingRequestID, id != presentedRequestID else { return }
        guard let presenter = idlePresenter() else {
            retry { $0.presentWhenReady() }
            return
        }
        presentedRequestID = id
        waited = 0

        let picker = UIDocumentPickerViewController(
            forOpeningContentTypes: LibraryBackupImportPolicy.allowedContentTypes,
            asCopy: true
        )
        picker.allowsMultipleSelection = false
        picker.shouldShowFileExtensions = true
        picker.delegate = self
        // Open at the folder backups are actually written to. When the user
        // picked one, that is it; otherwise it is the app's own local folder,
        // which is exposed to Files by `UIFileSharingEnabled` but is not where
        // the picker would land on its own.
        if let access = try? LibraryBackupVault().currentDirectoryAccess() {
            directoryAccess = access
            picker.directoryURL = access.url
        }
        presenter.present(picker, animated: true)
    }

    /// The controller a new presentation can start from, or nil while the
    /// hierarchy is still animating.
    private func idlePresenter() -> UIViewController? {
        guard var top = view.window?.rootViewController else { return nil }
        while let presented = top.presentedViewController {
            top = presented
        }
        guard top.view.window != nil,
              !top.isBeingPresented,
              !top.isBeingDismissed,
              top.transitionCoordinator == nil
        else { return nil }
        return top
    }

    private func retry(
        onTimeout: @escaping (BackupImportPickerHostController) -> Void = {
            // Give the request back rather than holding it: the button stays
            // live, and a later tap starts a fresh attempt.
            $0.pendingRequestID = nil
            $0.presentedRequestID = nil
            $0.waited = 0
            $0.onResult?(.failure(CancellationError()))
        },
        _ work: @escaping (BackupImportPickerHostController) -> Void
    ) {
        guard waited < Self.maximumWait else {
            onTimeout(self)
            return
        }
        waited += Self.retryInterval
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.retryInterval) {
            [weak self] in
            guard let self else { return }
            work(self)
        }
    }

    func documentPicker(
        _ controller: UIDocumentPickerViewController,
        didPickDocumentsAt urls: [URL]
    ) {
        guard let url = urls.first else { return }
        finish(with: .success(url))
    }

    func documentPickerWasCancelled(
        _ controller: UIDocumentPickerViewController
    ) {
        finish(with: .failure(CancellationError()))
    }

    private func finish(with result: Result<URL, Error>) {
        directoryAccess = nil
        presentedRequestID = nil
        pendingRequestID = nil
        waited = 0
        deliverWhenIdle(result)
    }

    /// UIKit calls the delegate while the picker is still on screen. The
    /// caller answers a successful pick with a confirmation dialog, which
    /// would be dropped if it were raised now.
    private func deliverWhenIdle(_ result: Result<URL, Error>) {
        guard idlePresenter() != nil else {
            // A pick that took the long way round is still a pick: report it
            // even if the hierarchy never went idle.
            retry(onTimeout: { $0.waited = 0; $0.onResult?(result) }) {
                $0.deliverWhenIdle(result)
            }
            return
        }
        waited = 0
        onResult?(result)
    }
}

private struct BackupFolderPicker: UIViewControllerRepresentable {
    var startDirectory: URL?
    let onResult: (Result<URL, Error>) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onResult: onResult)
    }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(
            forOpeningContentTypes: [.folder]
        )
        picker.allowsMultipleSelection = false
        picker.delegate = context.coordinator
        // Opening on the folder an imported archive names turns "find that
        // folder again" into a single confirmation. The picker runs out of
        // process, so it can start there without this app holding a grant.
        if let startDirectory {
            picker.directoryURL = startDirectory
        }
        return picker
    }

    func updateUIViewController(
        _ uiViewController: UIDocumentPickerViewController,
        context: Context
    ) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onResult: (Result<URL, Error>) -> Void

        init(onResult: @escaping (Result<URL, Error>) -> Void) {
            self.onResult = onResult
        }

        func documentPicker(
            _ controller: UIDocumentPickerViewController,
            didPickDocumentsAt urls: [URL]
        ) {
            guard let url = urls.first else { return }
            onResult(.success(url))
        }

        func documentPickerWasCancelled(
            _ controller: UIDocumentPickerViewController
        ) {
            onResult(.failure(CancellationError()))
        }
    }
}

private func backupFileStamp(for date: Date) -> String {
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

private func byteCount(_ bytes: Int64) -> String {
    ByteCountFormatter.string(
        fromByteCount: max(bytes, 0),
        countStyle: .file
    )
}
