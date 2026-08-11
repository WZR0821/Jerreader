import JerreaderCore
import PhotosUI
import SwiftData
import SwiftUI
import UniformTypeIdentifiers
import UIKit

/// Every label this screen shares with the Android shelf, written once in
/// `core/design/JerreaderCopy.kt`. Reading it through a local shorthand keeps
/// the call sites as short as the string literals they replaced.
///
/// Computed rather than a stored `let`: a Kotlin object arrives in Swift as a
/// plain class, so it is not `Sendable`, and Swift 6 rejects it as a global
/// constant. Nothing here is mutable, so re-reading `shared` costs nothing.
private var copy: JerreaderCopy { JerreaderCopy.shared }

struct LibraryView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \BookRecord.importedAt, order: .reverse) private var books: [BookRecord]

    @Binding private var incomingBookURL: URL?
    @ObservedObject private var translationSettings: TranslationSettingsStore

    @State private var isShowingImportOptions = false
    @State private var isShowingBackupRestore = false
    @State private var isImporting = false
    @State private var incomingImportURLInProgress: URL?
    @State private var readerSession: LibraryReaderSession?
    @State private var selectedManagedBook: BookRecord?
    @State private var isShowingBatchManagement = false
    @State private var pendingDeletion: BookRecord?
    @State private var sortOrder: LibrarySortOrder = .recent
    @State private var searchText = ""
    @State private var selectedCategory: String?
    @State private var alert: LibraryAlert?
    @State private var isFreshInstallation = LibraryFirstRunStore()
        .isFreshInstallation
#if DEBUG
    @State private var didStartSelectionUITestImport = false
#endif

    private let importer = EPUBImportService.shared

    init(
        incomingBookURL: Binding<URL?> = .constant(nil),
        translationSettings: TranslationSettingsStore
    ) {
        _incomingBookURL = incomingBookURL
        _translationSettings = ObservedObject(wrappedValue: translationSettings)
    }

    private var columns: [GridItem] {
        if dynamicTypeSize.isAccessibilitySize {
            return [GridItem(.flexible())]
        }
        return [
            GridItem(
                .adaptive(minimum: 145, maximum: 220),
                spacing: 26,
                alignment: .top
            )
        ]
    }

    private var displayedBooks: [BookRecord] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        let filtered = books.filter { book in
            let matchesCategory = selectedCategory == nil || book.category == selectedCategory
            let matchesQuery = query.isEmpty
                || book.title.localizedCaseInsensitiveContains(query)
                || book.author.localizedCaseInsensitiveContains(query)
                || book.category.localizedCaseInsensitiveContains(query)
                || book.series.localizedCaseInsensitiveContains(query)
                || book.tags.contains { $0.localizedCaseInsensitiveContains(query) }
            return matchesCategory && matchesQuery
        }
        switch sortOrder {
        case .recent:
            return filtered.sorted {
                ($0.lastOpenedAt ?? $0.importedAt) > ($1.lastOpenedAt ?? $1.importedAt)
            }
        case .title:
            return filtered.sorted {
                $0.title.localizedStandardCompare($1.title) == .orderedAscending
            }
        case .author:
            return filtered.sorted {
                $0.author.localizedStandardCompare($1.author) == .orderedAscending
            }
        }
    }

    private var categories: [String] {
        Array(Set(books.map(\.category).filter { !$0.isEmpty }))
            .sorted { $0.localizedStandardCompare($1) == .orderedAscending }
    }

    private var recentlyViewedBook: BookRecord? {
        books
            .filter { $0.lastOpenedAt != nil }
            .max { ($0.lastOpenedAt ?? .distantPast) < ($1.lastOpenedAt ?? .distantPast) }
    }

    var body: some View {
        NavigationStack {
            Group {
                if books.isEmpty {
                    emptyLibrary
                } else {
                    bookGrid
                }
            }
            .background(JerreaderCanvasBackground())
            .navigationTitle(copy.libraryTitle)
            .searchable(text: $searchText, prompt: copy.librarySearchPrompt)
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        isShowingImportOptions = true
                    } label: {
                        Image(systemName: "plus")
                    }
                    .disabled(isImporting)
                    .accessibilityLabel(copy.libraryImportAction)
                    .accessibilityHint("选择 EPUB、PDF、DOCX 或 TXT")

                    Menu {
                        if !categories.isEmpty {
                            Section(copy.libraryFolderSectionTitle) {
                                Button {
                                    selectedCategory = nil
                                } label: {
                                    Label(
                                        copy.libraryAllBooksFilter,
                                        systemImage: selectedCategory == nil
                                            ? "checkmark"
                                            : "books.vertical"
                                    )
                                }

                                ForEach(categories, id: \.self) { category in
                                    Button {
                                        selectedCategory = category
                                    } label: {
                                        Label(
                                            category,
                                            systemImage: selectedCategory == category
                                                ? "checkmark"
                                                : "folder"
                                        )
                                    }
                                }
                            }
                        }

                        Section(copy.libraryTitle) {
                            Picker(copy.librarySortLabel, selection: $sortOrder) {
                                ForEach(LibrarySortOrder.allCases) { order in
                                    Label(order.title, systemImage: order.systemImage)
                                        .tag(order)
                                }
                            }

                            Button {
                                isShowingBatchManagement = true
                            } label: {
                                Label(copy.libraryBatchAction, systemImage: "checklist")
                            }
                            .disabled(books.isEmpty)
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel(copy.libraryMoreActions)
                }
            }
            .overlay {
                if isImporting {
                    importingOverlay
                        .transition(.opacity.combined(with: .scale(scale: 0.985)))
                }
            }
            .animation(
                reduceMotion ? nil : JerreaderMotion.stateChange,
                value: isImporting
            )
        }
        .tint(JerreaderTheme.accent)
        .sheet(isPresented: $isShowingImportOptions) {
            BookImportOptionsView(
                isImporting: isImporting,
                onImporterResult: handleImporterResult
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $isShowingBackupRestore) {
            NavigationStack {
                LibraryBackupSettingsView(
                    translationSettings: translationSettings,
                    automaticallyPresentsImport: true
                )
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("完成") { isShowingBackupRestore = false }
                    }
                }
            }
        }
        .fullScreenCover(item: $readerSession) { session in
            EPUBReaderScreen(
                book: session.book,
                modelContext: modelContext,
                translationSettings: translationSettings
            )
            .id(session.id)
        }
        .sheet(item: $selectedManagedBook) { book in
            BookManagementView(book: book, existingFolders: categories)
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $isShowingBatchManagement) {
            BatchBookManagementView(
                books: books,
                existingFolders: categories
            )
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
        .confirmationDialog(
            copy.bookDeleteConfirmTitle(bookTitle: pendingDeletion?.title ?? ""),
            isPresented: Binding(
                get: { pendingDeletion != nil },
                set: { if !$0 { pendingDeletion = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button(copy.bookDeleteAction, role: .destructive) {
                guard let book = pendingDeletion else { return }
                pendingDeletion = nil
                Task { await delete(book) }
            }
            Button(copy.cancel, role: .cancel) {
                pendingDeletion = nil
            }
        } message: {
            Text(copy.bookDeleteConfirmMessage)
        }
        .alert(item: $alert) { item in
            Alert(
                title: Text(item.title),
                message: Text(item.message),
                dismissButton: .default(Text("好"))
            )
        }
        .onAppear {
            retireFirstRunStateIfNeeded()
            importIncomingBookIfNeeded()
            Task {
                await LibraryPendingFileCleanupStore.shared.retry(
                    using: importer
                )
            }
#if DEBUG
            startSelectionUITestImportIfNeeded()
#endif
        }
        .onChange(of: books.count) {
            retireFirstRunStateIfNeeded()
        }
        .onChange(of: incomingBookURL) {
            importIncomingBookIfNeeded()
        }
        .onChange(of: isImporting) {
            if !isImporting {
                importIncomingBookIfNeeded()
            }
        }
    }

    /// The shelf holding a book is what ends the first run — including a book
    /// that arrived by restoring a backup, which is the entry's own success
    /// case.
    private func retireFirstRunStateIfNeeded() {
        guard isFreshInstallation, !books.isEmpty else { return }
        LibraryFirstRunStore().noteLibrary(bookCount: books.count)
        isFreshInstallation = false
    }

    private var emptyLibrary: some View {
        VStack(spacing: 4) {
            Spacer()

            JerreaderEmptyState(
                title: copy.libraryEmptyTitle,
                message: copy.libraryEmptyMessage,
                systemImage: "books.vertical.fill"
            )

            Button {
                isShowingImportOptions = true
            } label: {
                Label(copy.libraryImportAction, systemImage: "square.and.arrow.down")
                    .font(.headline)
                    .padding(.horizontal, 8)
            }
            .buttonStyle(.borderedProminent)
            .tint(JerreaderTheme.accent)
            .disabled(isImporting)

            // An empty shelf is what a reinstall looks like, so the restore
            // path belongs here rather than only three taps deep in settings —
            // but only until this installation has held its first book. After
            // that an empty shelf is one the user emptied, and the entry is
            // just a permanent button for something 设置 → 备份中心 already has.
            if isFreshInstallation {
                Button {
                    isShowingBackupRestore = true
                } label: {
                    Label(copy.libraryRestoreAction, systemImage: "arrow.uturn.backward.circle")
                        .font(.subheadline)
                        .padding(.horizontal, 8)
                }
                .buttonStyle(.bordered)
                .tint(JerreaderTheme.accent)
                .disabled(isImporting)
                .padding(.top, 6)
                .accessibilityIdentifier("library-restore-backup")

                Text(copy.libraryRestoreHint)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.top, 6)
            }

            Label(copy.libraryPrivacyNote, systemImage: "lock.fill")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.top, 10)

            Spacer()
            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(JerreaderTheme.pagePadding)
    }

    private var bookGrid: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 26) {
                if let recentlyViewedBook {
                    VStack(alignment: .leading, spacing: 12) {
                        JerreaderSectionTitle(
                            title: copy.libraryRecentSectionTitle,
                            detail: copy.libraryRecentSectionDetail,
                            systemImage: "clock.arrow.circlepath"
                        )

                        RecentlyViewedBookView(book: recentlyViewedBook) {
                            open(recentlyViewedBook)
                        }
                        .frame(maxWidth: 760)
                    }
                    .jerreaderReveal(order: 1)
                }

                if !categories.isEmpty {
                    LibraryFolderSection(
                        categories: categories,
                        books: books,
                        selectedCategory: $selectedCategory
                    )
                    .jerreaderReveal(order: 2)
                }

                VStack(alignment: .leading, spacing: 16) {
                    JerreaderSectionTitle(
                        title: copy.libraryAllBooksSectionTitle,
                        detail: copy.bookCountDetail(
                            count: Int32(displayedBooks.count),
                            sortTitle: sortOrder.title
                        ),
                        systemImage: "square.grid.2x2"
                    )

                    LazyVGrid(columns: columns, alignment: .leading, spacing: 34) {
                        ForEach(
                            Array(displayedBooks.enumerated()),
                            id: \.element.id
                        ) { index, book in
                            BookCardView(
                                book: book,
                                onOpen: { open(book) },
                                onManage: { selectedManagedBook = book },
                                onDelete: { pendingDeletion = book }
                            )
                        .jerreaderReveal(order: min(index + 3, 8))
                            .transition(
                                .opacity.combined(
                                    with: .scale(scale: 0.96, anchor: .top)
                                )
                            )
                        }
                    }
                    .animation(
                        reduceMotion ? nil : JerreaderMotion.stateChange,
                        value: displayedBooks.map(\.id)
                    )

                    if displayedBooks.isEmpty {
                        ContentUnavailableView(
                            copy.libraryNoMatchTitle,
                            systemImage: "books.vertical",
                            description: Text(copy.libraryNoMatchMessage)
                        )
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 20)
                    }
                }
                .jerreaderReveal(order: 2)

                LibraryReadingOverview(books: books)
                    .jerreaderReveal(order: 3)
            }
            .frame(maxWidth: 1_120)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, JerreaderTheme.pagePadding)
            .padding(.top, 14)
            .padding(.bottom, 36)
        }
    }

    private var importingOverlay: some View {
        ZStack {
            Color.black.opacity(0.16)
                .ignoresSafeArea()

            VStack(spacing: 14) {
                JerreaderLoadingGlyph(systemImage: "books.vertical.fill", size: 62)
                Text("正在整理这本书…")
                    .font(.headline)
                Text("正在校验格式，并把阅读副本与封面保存到本机")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
            .frame(maxWidth: 280)
            .jerreaderPaperCard(padding: 24, hasShadow: true)
            .padding(32)
        }
        .accessibilityElement(children: .combine)
    }

    private func handleImporterResult(_ result: Result<[URL], Error>) {
        isShowingImportOptions = false

        switch result {
        case let .success(urls):
            guard let url = urls.first else { return }
            Task { await importBook(from: url, opensAfterImport: true) }
        case let .failure(error):
            if (error as NSError).code != NSUserCancelledError {
                alert = LibraryAlert(
                    title: "无法选择文件",
                    message: "文件选择没有完成，请重新尝试。"
                )
            }
        }
    }

    @MainActor
    private func importIncomingBookIfNeeded() {
        guard !isImporting,
              incomingImportURLInProgress == nil,
              let url = incomingBookURL else {
            return
        }

        incomingImportURLInProgress = url

        Task { @MainActor in
            await importBook(from: url, opensAfterImport: true)
            if incomingBookURL == url {
                incomingBookURL = nil
            }
            incomingImportURLInProgress = nil
            importIncomingBookIfNeeded()
        }
    }

#if DEBUG
    /// UI automation injects the user's real ruby EPUB into the built Debug
    /// app bundle. This hook uses the normal importer and is compiled out of
    /// Release builds; no host path or test book is stored in the project.
    @MainActor
    private func startSelectionUITestImportIfNeeded() {
        let arguments = ProcessInfo.processInfo.arguments
        guard arguments.contains("--jerreader-selection-ui-test"),
              !didStartSelectionUITestImport,
              let fixtureURL = Bundle.main.url(
                  forResource: "UITestRubyBook",
                  withExtension: "epub"
              )
        else { return }

        didStartSelectionUITestImport = true
        Task { @MainActor in
            await importBook(from: fixtureURL, opensAfterImport: true)
        }
    }
#endif

    @MainActor
    private func importBook(from url: URL, opensAfterImport: Bool = false) async {
        guard !isImporting else { return }
        isImporting = true
        defer { isImporting = false }

        do {
            let imported = try await importer.importBook(
                from: url,
                existingFingerprints: Set(books.map(\.fileFingerprint))
            )
            let appearance = ReaderAppearanceDefaults.current()
            let record = BookRecord(
                publicationIdentifier: imported.publicationIdentifier,
                title: imported.title,
                author: imported.author,
                language: imported.language,
                sourceFormat: imported.sourceFormat.rawValue,
                localFileName: imported.localFileName,
                coverFileName: imported.coverFileName,
                fileFingerprint: imported.fileFingerprint,
                readerFontSize: appearance.fontSize,
                readerTheme: appearance.theme.rawValue,
                readerScrollEnabled: appearance.readingMode.scrollEnabled,
                readerFontFamily: appearance.fontChoice.rawValue,
                readerLineHeight: appearance.lineHeight,
                readerParagraphSpacing: appearance.paragraphSpacing,
                readerPageMargins: appearance.pageMargins,
                readerCustomBackgroundHex: appearance.customBackgroundHex,
                readerCustomSelectionColorHex:
                    appearance.customSelectionColorHex,
                readerTextOrientation: initialReaderTextOrientation(
                    for: imported.language,
                    appearance: appearance
                )
            )
            modelContext.insert(record)

            do {
                try modelContext.save()
            } catch {
                modelContext.rollback()
                await importer.discardImportedFiles(imported)
                throw BookImportError.persistenceFailed
            }

            if opensAfterImport {
                alert = nil
                open(record)
            } else {
                alert = LibraryAlert(
                    title: "导入成功",
                    message: "《\(record.title)》已作为 \(record.format.displayName) 加入书架。"
                )
            }
        } catch {
            if opensAfterImport,
               let duplicate = error as? DuplicateBookImportError,
               let existingBook = LibraryBookMatcher.matching(
                   fingerprint: duplicate.fingerprint,
                   in: books
               )
            {
#if DEBUG
                if let orientation = selectionUITestOrientation {
                    existingBook.readerTextOrientation = orientation.rawValue
                    try? modelContext.save()
                }
#endif
                alert = nil
                open(existingBook)
                return
            }

            alert = LibraryAlert(
                title: "无法导入文档",
                message: (error as? LocalizedError)?.errorDescription
                    ?? "导入过程中出现问题，请稍后重试。"
            )
        }
    }

    private func initialReaderTextOrientation(
        for language: String?,
        appearance: ReaderAppearancePreferences
    ) -> String {
#if DEBUG
        if let orientation = selectionUITestOrientation {
            return orientation.rawValue
        }
#endif
        return language?.lowercased().hasPrefix("ja") == true
            ? appearance.japaneseTextOrientation.rawValue
            : ReaderTextOrientationChoice.publication.rawValue
    }

#if DEBUG
    private var selectionUITestOrientation: ReaderTextOrientationChoice? {
        guard ProcessInfo.processInfo.arguments.contains("--jerreader-selection-ui-test")
        else { return nil }
        let rawValue = ProcessInfo.processInfo.arguments
            .first(where: { $0.hasPrefix("--jerreader-selection-orientation=") })?
            .split(separator: "=", maxSplits: 1)
            .last
            .map(String.init)
        return rawValue.flatMap(ReaderTextOrientationChoice.init(rawValue:))
    }
#endif

    @MainActor
    private func open(_ book: BookRecord) {
        // A tap only starts a reader session. `lastOpenedAt` is persisted with
        // the first real reading location, so a failed or immediately closed
        // open does not mutate the book record either.
        // A same-book reopen must still create a new SwiftUI identity. Reusing
        // the BookRecord ID can resurrect a reader model whose onDisappear()
        // already closed it, leaving its initial `.loading` state unable to
        // start another attempt.
        readerSession = LibraryReaderSession(book: book)
    }

    @MainActor
    private func delete(_ book: BookRecord) async {
        let localFileName = book.localFileName
        let coverFileName = book.coverFileName

        let bookID = book.id
        do {
            try LibraryBookDeletion.stageAssociatedRecords(
                for: bookID,
                in: modelContext
            )
            modelContext.delete(book)
            try modelContext.save()
        } catch {
            modelContext.rollback()
            alert = LibraryAlert(
                title: "无法删除电子书",
                message: BookImportError.deletionPersistenceFailed.localizedDescription
            )
            return
        }

        await LibraryPendingFileCleanupStore.shared.enqueue(
            localFileName: localFileName,
            coverFileName: coverFileName
        )
        do {
            try await importer.removeFiles(
                localFileName: localFileName,
                coverFileName: coverFileName
            )
            await LibraryPendingFileCleanupStore.shared.markCompleted(
                localFileName: localFileName,
                coverFileName: coverFileName
            )
        } catch {
            alert = LibraryAlert(
                title: "书籍已移除",
                message: BookImportError.deletionCleanupPending.localizedDescription
                    + " App 会在下次打开书架时自动重试。"
            )
        }
    }
}

struct LibraryReaderSession: Identifiable {
    let id = UUID()
    let book: BookRecord
}

enum LibraryBookMatcher {
    static func matching(fingerprint: String, in books: [BookRecord]) -> BookRecord? {
        books.first { $0.fileFingerprint == fingerprint }
    }
}

enum LibraryBookDeletion {
    /// Stages dependent reader records in the caller's current SwiftData
    /// transaction. The caller deletes the book and saves once, so a failed
    /// fetch or save cannot leave orphaned bookmarks or annotations.
    static func stageAssociatedRecords(
        for bookID: UUID,
        in modelContext: ModelContext
    ) throws {
        let bookmarkDescriptor = FetchDescriptor<ReadingBookmarkRecord>(
            predicate: #Predicate { $0.bookID == bookID }
        )
        let annotationDescriptor = FetchDescriptor<ReadingAnnotationRecord>(
            predicate: #Predicate { $0.bookID == bookID }
        )

        for bookmark in try modelContext.fetch(bookmarkDescriptor) {
            modelContext.delete(bookmark)
        }
        for annotation in try modelContext.fetch(annotationDescriptor) {
            modelContext.delete(annotation)
        }
    }
}

private struct RecentlyViewedBookView: View {
    let book: BookRecord
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            HStack(spacing: 16) {
                BookCoverView(book: book)
                    .frame(width: 72, height: 108)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .shadow(color: .black.opacity(0.16), radius: 7, y: 4)

                VStack(alignment: .leading, spacing: 6) {
                    Text(book.title)
                        .font(.system(.headline, design: .serif).weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(2)

                    Text(book.author)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)

                    Label(book.format.displayName, systemImage: book.format.systemImage)
                        .font(.caption2.weight(.medium))
                        .foregroundStyle(JerreaderTheme.accent)

                    if let lastOpenedAt = book.lastOpenedAt {
                        Label {
                            JerreaderRelativeTimeText(date: lastOpenedAt)
                        } icon: {
                            Image(systemName: "clock")
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.top, 2)
                    }

                    if book.lastReadProgress > 0 {
                        ProgressView(value: book.lastReadProgress)
                            .tint(JerreaderTheme.accent)
                            .accessibilityLabel("阅读进度")
                            .accessibilityValue(book.progressText)

                        Text(book.progressText)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer(minLength: 8)

                Image(systemName: "chevron.right")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.986))
        .jerreaderPaperCard(padding: 14, radius: 16, hasShadow: true)
        .accessibilityHint("轻点继续阅读")
    }
}

private struct LibraryFolderSection: View {
    let categories: [String]
    let books: [BookRecord]
    @Binding var selectedCategory: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 13) {
            JerreaderSectionTitle(
                title: copy.libraryFolderSectionTitle,
                detail: copy.folderCountDetail(count: Int32(categories.count)),
                systemImage: "folder.fill"
            )

            ScrollView(.horizontal) {
                HStack(spacing: 12) {
                    folderButton(
                        title: copy.libraryAllBooksFilter,
                        count: books.count,
                        isSelected: selectedCategory == nil,
                        systemImage: "square.grid.2x2.fill"
                    ) {
                        selectedCategory = nil
                    }

                    ForEach(categories, id: \.self) { category in
                        folderButton(
                            title: category,
                            count: books.filter { $0.category == category }.count,
                            isSelected: selectedCategory == category,
                            systemImage: "folder.fill"
                        ) {
                            selectedCategory = category
                        }
                    }
                }
                .padding(.vertical, 2)
            }
            .scrollIndicators(.hidden)
        }
    }

    private func folderButton(
        title: String,
        count: Int,
        isSelected: Bool,
        systemImage: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack(spacing: 11) {
                Image(systemName: systemImage)
                    .font(.title3)
                    .foregroundStyle(isSelected ? JerreaderTheme.onPrimaryAction : JerreaderTheme.accent)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                    Text(copy.bookCount(count: Int32(count)))
                        .font(.caption2)
                        .opacity(0.76)
                }
            }
            .foregroundStyle(isSelected ? JerreaderTheme.onPrimaryAction : Color.primary)
            .frame(minWidth: 132, alignment: .leading)
            .padding(.horizontal, 14)
            .frame(height: 64)
            .background(
                isSelected ? JerreaderTheme.primaryAction : JerreaderTheme.paper,
                in: RoundedRectangle(cornerRadius: 14, style: .continuous)
            )
            .overlay {
                if !isSelected {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .stroke(JerreaderTheme.line, lineWidth: 0.75)
                }
            }
        }
        .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.97))
        .accessibilityLabel("\(title)，\(copy.bookCount(count: Int32(count)))")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

private struct BookCardView: View {
    let book: BookRecord
    let onOpen: () -> Void
    let onManage: () -> Void
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button(action: onOpen) {
                GeometryReader { geometry in
                    BookCoverView(book: book)
                        .frame(
                            width: geometry.size.width,
                            height: geometry.size.height
                        )
                        .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                        .shadow(color: .black.opacity(0.16), radius: 7, y: 4)
                }
                .aspectRatio(2 / 3, contentMode: .fit)
            }
            .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.972))
            .frame(maxWidth: .infinity)
            .accessibilityLabel("\(book.title)，作者 \(book.author)")
            .accessibilityHint("轻点打开阅读器")

            HStack(alignment: .top, spacing: 6) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(book.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(2)

                    Text(book.author)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)

                    Text(book.format.displayName)
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(JerreaderTheme.accent)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(JerreaderTheme.accentFill, in: Capsule())

                    if book.lastReadProgress > 0 {
                        ProgressView(value: book.lastReadProgress)
                            .tint(JerreaderTheme.accent)
                            .accessibilityLabel("阅读进度")
                            .accessibilityValue(book.progressText)
                    }

                    if !book.category.isEmpty {
                        Label(book.category, systemImage: "folder.fill")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }

                Spacer(minLength: 0)

                Menu {
                    Button(action: onOpen) {
                        Label("开始阅读", systemImage: "book.pages")
                    }

                    Button(action: onManage) {
                        Label("编辑书籍信息", systemImage: "pencil")
                    }

                    Button(role: .destructive, action: onDelete) {
                        Label("删除", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("\(book.title)的更多操作")
            }
        }
        .contextMenu {
            Button(action: onOpen) {
                Label("开始阅读", systemImage: "book.pages")
            }

            Button(action: onManage) {
                Label(copy.bookManageAction, systemImage: "pencil")
            }

            Button(role: .destructive, action: onDelete) {
                Label(copy.deleteAction, systemImage: "trash")
            }
        }
        // Flexible grid cells do not automatically constrain a Button's image
        // ideal size. Explicitly bind the whole card to its cell so oversized
        // source covers can never draw into a neighboring column.
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct LibraryReadingOverview: View {
    let books: [BookRecord]

    private var totalSeconds: TimeInterval {
        books.reduce(0) { $0 + max($1.totalReadingSeconds, 0) }
    }

    private var startedBooks: [BookRecord] {
        books.filter { $0.lastReadProgress > 0 }
    }

    private var averageProgress: Double {
        guard !startedBooks.isEmpty else { return 0 }
        return startedBooks.reduce(0) { $0 + $1.lastReadProgress }
            / Double(startedBooks.count)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Text(copy.libraryOverviewTitle)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)

                Spacer(minLength: 8)

                Text(
                    startedBooks.isEmpty
                        ? copy.libraryOverviewIdleDetail
                        : copy.libraryOverviewActiveDetail
                )
                .font(.caption2)
                .foregroundStyle(.tertiary)
                .lineLimit(1)
            }

            HStack(spacing: 0) {
                metric(
                    value: totalSeconds.readerDurationText,
                    label: copy.libraryOverviewTotalLabel,
                    systemImage: "clock"
                )

                Divider()
                    .frame(height: 30)

                metric(
                    value: "\(startedBooks.count)",
                    label: copy.libraryOverviewStartedLabel,
                    systemImage: "book.pages"
                )

                Divider()
                    .frame(height: 30)

                metric(
                    value: "\(Int((averageProgress * 100).rounded()))%",
                    label: copy.libraryOverviewAverageLabel,
                    systemImage: "chart.bar"
                )
            }
        }
        .frame(maxWidth: .infinity)
        .frame(maxWidth: 820)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(JerreaderTheme.paper, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(JerreaderTheme.line, lineWidth: 0.75)
        }
        .accessibilityElement(children: .combine)
    }

    private func metric(value: String, label: String, systemImage: String) -> some View {
        VStack(spacing: 5) {
            Label(value, systemImage: systemImage)
                .font(.subheadline.monospacedDigit().weight(.semibold))
                .foregroundStyle(JerreaderTheme.accent)
                .lineLimit(1)
                .minimumScaleFactor(0.72)
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

private struct BookManagementView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext

    let book: BookRecord
    let existingFolders: [String]

    @State private var title: String
    @State private var author: String
    @State private var languageCode: String
    @State private var series: String
    @State private var folder: String
    @State private var tagsText: String
    @State private var selectedCoverItem: PhotosPickerItem?
    @State private var pendingCoverImage: UIImage?
    @State private var isSaving = false
    @State private var alert: LibraryAlert?

    init(book: BookRecord, existingFolders: [String]) {
        self.book = book
        self.existingFolders = existingFolders
        _title = State(initialValue: book.title)
        _author = State(initialValue: book.author)
        _languageCode = State(initialValue: book.language ?? "")
        _series = State(initialValue: book.series)
        _folder = State(initialValue: book.category)
        _tagsText = State(initialValue: book.tags.joined(separator: "，"))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("书籍") {
                    HStack(alignment: .top, spacing: 16) {
                        coverPreview

                        VStack(alignment: .leading, spacing: 10) {
                            PhotosPicker(
                                selection: $selectedCoverItem,
                                matching: .images
                            ) {
                                Label("更换封面", systemImage: "photo")
                            }
                            .buttonStyle(.bordered)

                            if pendingCoverImage != nil {
                                Button("撤销新封面") {
                                    pendingCoverImage = nil
                                    selectedCoverItem = nil
                                }
                                .font(.caption)
                            }
                        }
                    }

                    TextField("书名", text: $title)
                    TextField("作者", text: $author)
                    Picker("语言", selection: $languageCode) {
                        Text("未指定").tag("")
                        Text("日语").tag("ja")
                        Text("英语").tag("en")
                        Text("简体中文").tag("zh-Hans")
                        if !Self.standardLanguageCodes.contains(languageCode),
                           !languageCode.isEmpty
                        {
                            Text(languageCode).tag(languageCode)
                        }
                    }
                    TextField("系列（可选）", text: $series)
                    LabeledContent("格式", value: book.format.displayName)
                    LabeledContent("阅读进度", value: book.progressText)
                    LabeledContent("累计阅读", value: book.totalReadingSeconds.readerDurationText)
                }

                Section("整理") {
                    TextField("文件夹，例如：日本文学", text: $folder)
                    if !existingFolders.isEmpty {
                        ScrollView(.horizontal) {
                            HStack {
                                ForEach(existingFolders, id: \.self) { item in
                                    Button(item) { folder = item }
                                        .buttonStyle(.bordered)
                                        .controlSize(.small)
                                }
                            }
                        }
                        .scrollIndicators(.hidden)
                    }
                    TextField("标签，用逗号分隔", text: $tagsText, axis: .vertical)
                        .lineLimit(2 ... 4)
                    Text("文件夹会显示在书架顶部；系列与标签都参与搜索。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle(copy.bookEditorTitle)
            .navigationBarTitleDisplayMode(.inline)
            .disabled(isSaving)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(copy.cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(isSaving ? "\(copy.save)中…" : copy.save, action: save)
                    .fontWeight(.semibold)
                    .disabled(
                        isSaving
                            || title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    )
                }
            }
        }
        .tint(JerreaderTheme.accent)
        .onChange(of: selectedCoverItem) {
            loadSelectedCover()
        }
        .alert(item: $alert) { item in
            Alert(
                title: Text(item.title),
                message: Text(item.message),
                dismissButton: .default(Text("好"))
            )
        }
    }

    @ViewBuilder
    private var coverPreview: some View {
        if let pendingCoverImage {
            Image(uiImage: pendingCoverImage)
                .resizable()
                .scaledToFill()
                .frame(width: 82, height: 123)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        } else {
            BookCoverView(book: book)
                .frame(width: 82, height: 123)
                .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
    }

    private var parsedTags: [String] {
        tagsText.components(separatedBy: CharacterSet(charactersIn: ",，、\n"))
    }

    private func loadSelectedCover() {
        guard let selectedCoverItem else { return }
        Task { @MainActor in
            guard let data = try? await selectedCoverItem.loadTransferable(type: Data.self),
                  let image = UIImage(data: data)
            else {
                alert = LibraryAlert(
                    title: "无法读取封面",
                    message: "请选择一张可读取的图片后重试。"
                )
                return
            }
            pendingCoverImage = image
        }
    }

    private func save() {
        let normalizedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedTitle.isEmpty else { return }
        isSaving = true

        Task { @MainActor in
            var newlyStoredCover: String?
            do {
                if let pendingCoverImage,
                   let data = BookCoverImageProcessor.jpegData(from: pendingCoverImage)
                {
                    newlyStoredCover = try await LibraryCoverStore.shared
                        .storeJPEGData(data)
                }

                let oldCover = book.coverFileName
                book.title = normalizedTitle
                book.author = author.trimmingCharacters(in: .whitespacesAndNewlines)
                    .nilIfEmpty ?? "未知作者"
                book.language = languageCode.nilIfEmpty
                book.series = series.trimmingCharacters(in: .whitespacesAndNewlines)
                book.updateOrganization(
                    category: folder,
                    tags: parsedTags
                )
                if let newlyStoredCover {
                    book.coverFileName = newlyStoredCover
                }
                try modelContext.save()

                if newlyStoredCover != nil, oldCover != newlyStoredCover {
                    await LibraryCoverStore.shared.removeCover(fileName: oldCover)
                }
                isSaving = false
                dismiss()
            } catch {
                modelContext.rollback()
                if let newlyStoredCover {
                    await LibraryCoverStore.shared.removeCover(
                        fileName: newlyStoredCover
                    )
                }
                isSaving = false
                alert = LibraryAlert(
                    title: "无法保存书籍信息",
                    message: "修改没有写入，请稍后重试。"
                )
            }
        }
    }

    private static let standardLanguageCodes = ["", "ja", "en", "zh-Hans"]
}

private struct BatchBookManagementView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext

    let books: [BookRecord]
    let existingFolders: [String]

    @State private var selectedBookIDs = Set<UUID>()
    @State private var changesFolder = true
    @State private var folder = ""
    @State private var changesSeries = false
    @State private var series = ""
    @State private var changesLanguage = false
    @State private var languageCode = "ja"
    @State private var addsTags = false
    @State private var tagsText = ""
    @State private var alert: LibraryAlert?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        Text("已选择 \(selectedBookIDs.count) 本")
                        Spacer()
                        Button(
                            selectedBookIDs.count == books.count ? "取消全选" : "全选"
                        ) {
                            selectedBookIDs = selectedBookIDs.count == books.count
                                ? []
                                : Set(books.map(\.id))
                        }
                    }

                    ForEach(books) { book in
                        Button {
                            if !selectedBookIDs.insert(book.id).inserted {
                                selectedBookIDs.remove(book.id)
                            }
                        } label: {
                            HStack {
                                Image(
                                    systemName: selectedBookIDs.contains(book.id)
                                        ? "checkmark.circle.fill"
                                        : "circle"
                                )
                                .foregroundStyle(
                                    selectedBookIDs.contains(book.id)
                                        ? JerreaderTheme.accent
                                        : Color.secondary
                                )
                                VStack(alignment: .leading) {
                                    Text(book.title)
                                        .foregroundStyle(.primary)
                                    Text(book.author)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if !book.category.isEmpty {
                                    Text(book.category)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                } header: {
                    Text("选择书籍")
                }

                Section("批量修改") {
                    Toggle("修改文件夹", isOn: $changesFolder)
                    if changesFolder {
                        TextField("新文件夹；留空可移出文件夹", text: $folder)
                        if !existingFolders.isEmpty {
                            Picker("使用已有文件夹", selection: $folder) {
                                Text("无").tag("")
                                ForEach(existingFolders, id: \.self) {
                                    Text($0).tag($0)
                                }
                            }
                        }
                    }

                    Toggle("修改系列", isOn: $changesSeries)
                    if changesSeries {
                        TextField("系列；留空可清除", text: $series)
                    }

                    Toggle("修改语言", isOn: $changesLanguage)
                    if changesLanguage {
                        Picker("语言", selection: $languageCode) {
                            Text("日语").tag("ja")
                            Text("英语").tag("en")
                            Text("简体中文").tag("zh-Hans")
                            Text("未指定").tag("")
                        }
                    }

                    Toggle("追加标签", isOn: $addsTags)
                    if addsTags {
                        TextField("标签，用逗号分隔", text: $tagsText)
                    }
                }
            }
            .navigationTitle("批量整理")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("应用", action: apply)
                        .fontWeight(.semibold)
                        .disabled(selectedBookIDs.isEmpty || !hasChanges)
                }
            }
        }
        .tint(JerreaderTheme.accent)
        .alert(item: $alert) { item in
            Alert(
                title: Text(item.title),
                message: Text(item.message),
                dismissButton: .default(Text("好"))
            )
        }
    }

    private var parsedTags: [String] {
        tagsText.components(separatedBy: CharacterSet(charactersIn: ",，、\n"))
    }

    private var hasChanges: Bool {
        changesFolder || changesSeries || changesLanguage || addsTags
    }

    private func apply() {
        let newTags = BookRecord.normalizedTags(parsedTags)
        for book in books where selectedBookIDs.contains(book.id) {
            if changesFolder {
                book.category = folder.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            if changesSeries {
                book.series = series.trimmingCharacters(in: .whitespacesAndNewlines)
            }
            if changesLanguage {
                book.language = languageCode.nilIfEmpty
            }
            if addsTags {
                book.tags = BookRecord.normalizedTags(book.tags + newTags)
            }
        }

        do {
            try modelContext.save()
            dismiss()
        } catch {
            modelContext.rollback()
            alert = LibraryAlert(
                title: "批量整理失败",
                message: "修改没有写入，请稍后重试。"
            )
        }
    }
}

@MainActor
private enum BookCoverImageProcessor {
    static func jpegData(from image: UIImage) -> Data? {
        let maximumDimension: CGFloat = 1_200
        let sourceSize = image.size
        guard sourceSize.width > 0, sourceSize.height > 0 else { return nil }
        let scale = min(
            maximumDimension / max(sourceSize.width, sourceSize.height),
            1
        )
        let outputSize = CGSize(
            width: max((sourceSize.width * scale).rounded(), 1),
            height: max((sourceSize.height * scale).rounded(), 1)
        )
        let renderer = UIGraphicsImageRenderer(size: outputSize)
        let rendered = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: outputSize))
        }
        return rendered.jpegData(compressionQuality: 0.86)
    }
}

private extension BookRecord {
    var progressText: String {
        "\(Int((min(max(lastReadProgress, 0), 1) * 100).rounded()))%"
    }
}

private extension TimeInterval {
    /// Formatted by the shared module so the Android shelf reports the same
    /// total for the same reading history.
    var readerDurationText: String {
        copy.readingDuration(totalSeconds: self)
    }
}

private struct BookCoverView: View {
    let book: BookRecord

    var body: some View {
        Group {
            if let image = loadCover() {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                ZStack(alignment: .leading) {
                    LinearGradient(
                        colors: fallbackPalette,
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )

                    Rectangle()
                        .fill(.white.opacity(0.16))
                        .frame(width: 6)
                        .padding(.vertical, 6)

                    VStack(spacing: 13) {
                        Image(systemName: book.format.systemImage)
                            .font(.system(size: 27, weight: .medium))
                            .foregroundStyle(.white.opacity(0.88))

                        Text(book.title)
                            .font(.system(.headline, design: .serif).weight(.bold))
                            .multilineTextAlignment(.center)
                            .lineLimit(4)

                        Rectangle()
                            .fill(.white.opacity(0.55))
                            .frame(width: 34, height: 1)

                        Text(book.author)
                            .font(.caption)
                            .lineLimit(2)
                    }
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(18)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .overlay {
            RoundedRectangle(cornerRadius: 9, style: .continuous)
                .stroke(.white.opacity(0.18), lineWidth: 0.75)
        }
        .accessibilityHidden(true)
    }

    private var fallbackPalette: [Color] {
        let palettes: [[Color]] = [
            [Color(red: 0.08, green: 0.22, blue: 0.32), Color(red: 0.16, green: 0.42, blue: 0.56)],
            [Color(red: 0.10, green: 0.20, blue: 0.28), Color(red: 0.28, green: 0.47, blue: 0.60)],
            [Color(red: 0.13, green: 0.19, blue: 0.32), Color(red: 0.28, green: 0.40, blue: 0.62)],
            [Color(red: 0.15, green: 0.20, blue: 0.30), Color(red: 0.34, green: 0.40, blue: 0.57)]
        ]
        let scalarTotal = book.title.unicodeScalars.reduce(0) { $0 + Int($1.value) }
        return palettes[scalarTotal % palettes.count]
    }

    private func loadCover() -> UIImage? {
        guard let coverFileName = book.coverFileName,
              let url = try? LibraryPaths.coverURL(fileName: coverFileName)
        else {
            return nil
        }
        return UIImage(contentsOfFile: url.path)
    }
}

private struct BookImportOptionsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let isImporting: Bool
    let onImporterResult: (Result<[URL], Error>) -> Void

    @State private var isShowingFileImporter = false
    @State private var selectedFormat: BookImportFormatFilter = .all

    private var allowedContentTypes: [UTType] {
        switch selectedFormat {
        case .all:
            return BookImportFormatFilter.specificCases.flatMap(\.allowedContentTypes)
        case .epub, .pdf, .docx, .text:
            return selectedFormat.allowedContentTypes
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    VStack(alignment: .leading, spacing: 7) {
                        Text("选择文档的传输方式")
                            .font(.title2.weight(.bold))
                        Text("所有方式都只会将书籍复制或转换到Jerreader的本地空间，不会自动上传。")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    VStack(alignment: .leading, spacing: 10) {
                        Text("先选择文件格式")
                            .font(.headline)
                        Text("系统文件选择器会隐藏其他格式，查找大文件夹时更省时间。")
                            .font(.caption)
                            .foregroundStyle(.secondary)

                        LazyVGrid(
                            columns: [
                                GridItem(.flexible()),
                                GridItem(.flexible()),
                                GridItem(.flexible()),
                            ],
                            spacing: 10
                        ) {
                            ForEach(BookImportFormatFilter.allCases) { format in
                                Button {
                                    withAnimation(reduceMotion ? nil : JerreaderMotion.stateChange) {
                                        selectedFormat = format
                                    }
                                } label: {
                                    VStack(spacing: 7) {
                                        Image(systemName: format.systemImage)
                                            .font(.title3)
                                        Text(format.title)
                                            .font(.caption.weight(.semibold))
                                    }
                                    .foregroundStyle(
                                        selectedFormat == format ? JerreaderTheme.onPrimaryAction : JerreaderTheme.accent
                                    )
                                    .frame(maxWidth: .infinity)
                                    .frame(height: 66)
                                    .background(
                                        selectedFormat == format
                                            ? JerreaderTheme.accent
                                            : JerreaderTheme.accentFill,
                                        in: RoundedRectangle(cornerRadius: 13)
                                    )
                                }
                                .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.97))
                                .accessibilityLabel("只显示 \(format.accessibilityTitle)")
                            }
                        }
                    }
                    .jerreaderPaperCard(padding: 16)

                    Button {
                        isShowingFileImporter = true
                    } label: {
                        TransferMethodCard(
                            title: "选择\(selectedFormat.filePickerTitle)",
                            description: "从“文件”、iCloud Drive、下载目录或外接存储中选择。系统会交给Jerreader一个只读副本，不会直接打开或写回原文件；当前只显示\(selectedFormat.filePickerDescription)。",
                            systemImage: "folder.fill",
                            showsDisclosure: true
                        )
                    }
                    .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.985))
                    .disabled(isImporting)

                    TransferMethodCard(
                        title: "其他 App 与隔空投送",
                        description: "在微信、Safari、邮件或 AirDrop 收到支持的文档后，选择“用Jerreader打开”即可导入。",
                        systemImage: "square.and.arrow.down.fill"
                    )

                    TransferMethodCard(
                        title: "电脑文件共享",
                        description: "用 Mac 的 Finder 或 Windows Apple Devices 将文档复制到Jerreader，然后通过上方“文件与 iCloud Drive”选择它。",
                        systemImage: "laptopcomputer.and.iphone"
                    )

                    Label("支持 EPUB、PDF、DOCX 和 TXT；扫描 PDF 可按需使用本机 OCR。不支持受 DRM 保护的书籍、旧式 .doc 或整本上传翻译。", systemImage: "info.circle")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.horizontal, JerreaderTheme.pagePadding)
                .padding(.top, 10)
                .padding(.bottom, 28)
            }
            .background(JerreaderTheme.canvas)
            .navigationTitle("导入电子书")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
        .tint(JerreaderTheme.accent)
        // Deliberately not `.fileImporter`: that hands back an in-place
        // security-scoped URL to the user's own file. Merely opening such a URL
        // makes iCloud Drive and other file providers materialise the item and
        // stamp it with a fresh modification date, which is why importing used
        // to "touch" the books sitting in Files. `asCopy: true` makes iOS
        // produce the copy itself, so the original is never opened by this app.
        .sheet(isPresented: $isShowingFileImporter) {
            CopyingDocumentPicker(contentTypes: allowedContentTypes) { result in
                isShowingFileImporter = false
                dismiss()
                onImporterResult(result)
            }
            .ignoresSafeArea()
        }
    }
}

/// `UIDocumentPickerViewController` in copy mode.
///
/// The picker returns a URL inside the app's own temporary directory, so the
/// import path needs no security-scoped access and the user's original file is
/// never read, coordinated, or materialised by this app.
private struct CopyingDocumentPicker: UIViewControllerRepresentable {
    let contentTypes: [UTType]
    let onResult: (Result<[URL], Error>) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(
            forOpeningContentTypes: contentTypes,
            asCopy: true
        )
        picker.allowsMultipleSelection = false
        picker.shouldShowFileExtensions = true
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(
        _ uiViewController: UIDocumentPickerViewController,
        context: Context
    ) {
        context.coordinator.onResult = onResult
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onResult: onResult)
    }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        var onResult: (Result<[URL], Error>) -> Void
        private var didFinish = false

        init(onResult: @escaping (Result<[URL], Error>) -> Void) {
            self.onResult = onResult
        }

        func documentPicker(
            _ controller: UIDocumentPickerViewController,
            didPickDocumentsAt urls: [URL]
        ) {
            guard !didFinish else { return }
            didFinish = true
            onResult(.success(urls))
        }

        func documentPickerWasCancelled(
            _ controller: UIDocumentPickerViewController
        ) {
            guard !didFinish else { return }
            didFinish = true
            // Matches `.fileImporter`, which reports nothing on cancellation.
            onResult(.success([]))
        }
    }
}

private struct TransferMethodCard: View {
    let title: String
    let description: String
    let systemImage: String
    var showsDisclosure = false

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: systemImage)
                .font(.title3)
                .foregroundStyle(JerreaderTheme.accent)
                .frame(width: 44, height: 44)
                .background(
                    JerreaderTheme.accentFill,
                    in: RoundedRectangle(cornerRadius: 13, style: .continuous)
                )

            VStack(alignment: .leading, spacing: 5) {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(.primary)
                Text(description)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if showsDisclosure {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.tertiary)
                    .padding(.top, 15)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jerreaderPaperCard(padding: 16)
        .contentShape(Rectangle())
    }
}

private enum LibrarySortOrder: String, CaseIterable, Identifiable {
    case recent
    case title
    case author

    var id: Self { self }

    var title: String {
        switch self {
        case .recent: copy.librarySortRecent
        case .title: copy.librarySortTitle
        case .author: copy.librarySortAuthor
        }
    }

    var systemImage: String {
        switch self {
        case .recent: "clock"
        case .title: "textformat"
        case .author: "person"
        }
    }
}

private enum BookImportFormatFilter: String, CaseIterable, Identifiable {
    case all
    case epub
    case pdf
    case docx
    case text

    var id: Self { self }

    static let specificCases: [Self] = [.epub, .pdf, .docx, .text]

    var title: String {
        switch self {
        case .all: return "全部"
        case .epub: return "EPUB"
        case .pdf: return "PDF"
        case .docx: return "Word"
        case .text: return "TXT"
        }
    }

    var systemImage: String {
        switch self {
        case .all: return "square.grid.2x2"
        case .epub: return "books.vertical.fill"
        case .pdf: return "doc.richtext.fill"
        case .docx: return "doc.text.fill"
        case .text: return "text.document.fill"
        }
    }

    var allowedContentTypes: [UTType] {
        switch self {
        case .all:
            return Self.specificCases.flatMap(\.allowedContentTypes)
        case .epub:
            return [UTType(filenameExtension: "epub") ?? .data]
        case .pdf:
            return [.pdf]
        case .docx:
            return [UTType(filenameExtension: "docx") ?? .data]
        case .text:
            return [.plainText]
        }
    }

    var filePickerTitle: String {
        self == .all ? "文档" : " \(title) 文件"
    }

    var filePickerDescription: String {
        self == .all ? "支持的四种格式" : "\(title) 文件"
    }

    var accessibilityTitle: String {
        self == .all ? "支持的全部格式" : "\(title) 文件"
    }
}

private struct LibraryAlert: Identifiable {
    let id = UUID()
    let title: String
    let message: String
}

private extension String {
    var nilIfEmpty: String? {
        let normalized = trimmingCharacters(in: .whitespacesAndNewlines)
        return normalized.isEmpty ? nil : normalized
    }
}
