import PhotosUI
import SwiftData
import SwiftUI
import UniformTypeIdentifiers
import UIKit
import ImageIO

struct LibraryView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \BookRecord.importedAt, order: .reverse) private var books: [BookRecord]
    @Namespace private var coverTransition

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
    /// True only for the shelf's first second on screen: the one time the
    /// dashboard and the grid rise in one after another.
    @State private var isShelfRevealOpen = true
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

    /// Three columns on iPhone, as many 132–180 pt columns as fit on a wide
    /// screen, and one column of row-style tiles at accessibility text sizes.
    private var columns: [GridItem] {
        if dynamicTypeSize.isAccessibilitySize {
            return [GridItem(.flexible(), alignment: .top)]
        }
        if horizontalSizeClass == .regular {
            return [
                GridItem(
                    .adaptive(minimum: 132, maximum: 180),
                    spacing: JerreaderSpacing.md,
                    alignment: .top
                )
            ]
        }
        return Array(
            repeating: GridItem(.flexible(), spacing: JerreaderSpacing.md, alignment: .top),
            count: 3
        )
    }

    private var tileStyle: LibraryBookTile.Style {
        dynamicTypeSize.isAccessibilitySize ? .row : .grid
    }

    private var isSearchActive: Bool {
        !searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var gridItemTransition: AnyTransition {
        reduceMotion ? .opacity : .opacity.combined(with: .scale(scale: 0.96, anchor: .top))
    }

    private var importingOverlayTransition: AnyTransition {
        reduceMotion ? .opacity : .opacity.combined(with: .scale(scale: 0.985))
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
            .navigationTitle("书架")
            .searchable(text: $searchText, prompt: "搜索书名、作者、系列、文件夹或标签")
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        isShowingImportOptions = true
                    } label: {
                        JerreaderSymbol(name: "plus", size: 22)
                    }
                    .disabled(isImporting)
                    .accessibilityLabel("导入电子书")
                    .accessibilityHint("选择 EPUB、PDF、DOCX 或 TXT")

                    Menu {
                        if !categories.isEmpty {
                            Section("文件夹") {
                                Button {
                                    selectedCategory = nil
                                } label: {
                                    Label(
                                        "全部书籍",
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

                        Section("书架") {
                            Picker("排序方式", selection: $sortOrder) {
                                ForEach(LibrarySortOrder.allCases) { order in
                                    Label(order.title, systemImage: order.systemImage)
                                        .tag(order)
                                }
                            }

                            Button {
                                isShowingBatchManagement = true
                            } label: {
                                Label("批量整理书籍", systemImage: "checklist")
                            }
                            .disabled(books.isEmpty)
                        }
                    } label: {
                        JerreaderSymbol(name: "ellipsis.circle", size: 22)
                    }
                    .accessibilityLabel("书架更多操作")
                }
            }
            .overlay {
                if isImporting {
                    importingOverlay
                        .transition(importingOverlayTransition)
                }
            }
            .animation(
                JerreaderMotion.choose(JerreaderMotion.stateChange, reduceMotion: reduceMotion),
                value: isImporting
            )
            .task {
                // Only the shelf's first second on screen staggers in; tab
                // switches and a closed reader show it at once.
                try? await Task.sleep(for: .seconds(1))
                isShelfRevealOpen = false
            }
        }
        .tint(JerreaderTheme.accent)
        .sheet(isPresented: $isShowingImportOptions) {
            BookImportOptionsView(
                isImporting: isImporting,
                onImporterResult: handleImporterResult
            )
            // Full height from the start: the format grid comes first, and at
            // the medium detent it pushed the "选择文档" button below the fold.
            .presentationDetents([.large])
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
            readerScreen(for: session)
        }
        .sheet(item: $selectedManagedBook) { book in
            BookManagementView(book: book, existingFolders: categories)
            .presentationDetents([.large])
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
            "删除这本电子书？",
            isPresented: Binding(
                get: { pendingDeletion != nil },
                set: { if !$0 { pendingDeletion = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("删除本地副本", role: .destructive) {
                guard let book = pendingDeletion else { return }
                pendingDeletion = nil
                Task { await delete(book) }
            }
            Button("取消", role: .cancel) {
                pendingDeletion = nil
            }
        } message: {
            Text("将同时删除书架记录、本地阅读副本和封面。此操作不会影响“文件”App 中的原文件。")
        }
        .sensoryFeedback(.warning, trigger: pendingDeletion?.id) { _, newValue in
            newValue != nil
        }
        .alert(item: $alert) { item in
            Alert(
                title: Text(item.title),
                message: Text(item.message),
                dismissButton: .default(Text("好"))
            )
        }
        .onAppear {
            importIncomingBookIfNeeded()
            Task {
                await LibraryPendingFileCleanupStore.shared.retry(
                    using: importer
                )
            }
#if DEBUG
            startSelectionUITestImportIfNeeded()
            startSampleBookUITestImportIfNeeded()
#endif
        }
        .onChange(of: incomingBookURL) {
            importIncomingBookIfNeeded()
        }
        // The folder being filtered on can lose its last book (moved, renamed
        // or deleted). The folder row and the folder menu disappear with the
        // last folder, so the filter stayed on with nothing left to clear it:
        // an empty shelf saying “切换文件夹” and no way to do so.
        .onChange(of: categories) { _, current in
            if let selectedCategory, !current.contains(selectedCategory) {
                self.selectedCategory = nil
            }
        }
        .onChange(of: isImporting) {
            if !isImporting {
                importIncomingBookIfNeeded()
            }
        }
    }

    private var emptyLibrary: some View {
        LibraryEmptyShelfView(
            isImporting: isImporting,
            revealsOnAppear: isShelfRevealOpen,
            onImport: { isShowingImportOptions = true },
            onRestore: { isShowingBackupRestore = true }
        )
    }

    private var bookGrid: some View {
        let shownBooks = displayedBooks
        let gridAnimation = JerreaderMotion.choose(
            JerreaderMotion.stateChange,
            reduceMotion: reduceMotion
        )

        return ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                // The dashboard steps aside while searching, so the results
                // start right under the search field.
                if !isSearchActive {
                    shelfDashboard
                        .transition(.opacity)
                }

                LibraryShelfSectionHeader(
                    title: "所有书籍",
                    detail: "\(shownBooks.count) 本"
                ) {
                    sortMenu
                }
                .padding(.top, isSearchActive ? JerreaderSpacing.xxs : JerreaderSpacing.xl)
                .libraryShelfReveal(order: 3, isEnabled: isShelfRevealOpen)

                if !categories.isEmpty {
                    LibraryFolderChips(
                        categories: categories,
                        books: books,
                        selectedCategory: $selectedCategory
                    )
                    .padding(.top, JerreaderSpacing.sm)
                    .libraryShelfReveal(order: 3, isEnabled: isShelfRevealOpen)
                }

                if shownBooks.isEmpty {
                    LibraryNoMatchView()
                        .padding(.top, JerreaderSpacing.sm)
                } else {
                    LazyVGrid(columns: columns, alignment: .leading, spacing: JerreaderSpacing.xl) {
                        ForEach(
                            Array(shownBooks.enumerated()),
                            id: \.element.id
                        ) { index, book in
                            LibraryBookTile(
                                book: book,
                                style: tileStyle,
                                transitionNamespace: coverTransition,
                                onOpen: {
                                    open(
                                        book,
                                        from: LibraryCoverTransitionID(bookID: book.id, place: .grid)
                                    )
                                },
                                onManage: { selectedManagedBook = book },
                                onDelete: { pendingDeletion = book }
                            )
                            .libraryShelfReveal(order: index + 4, isEnabled: isShelfRevealOpen)
                            .transition(gridItemTransition)
                        }
                    }
                    .padding(.top, JerreaderSpacing.md)
                    .animation(gridAnimation, value: shownBooks.map(\.id))
                }
            }
            .animation(gridAnimation, value: isSearchActive)
            .frame(maxWidth: 1_120)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, JerreaderTheme.pagePadding)
            .padding(.top, JerreaderSpacing.xxs)
            .padding(.bottom, 36)
        }
    }

    /// The date line, the 继续阅读 hero and the 阅读统计 card.
    private var shelfDashboard: some View {
        VStack(alignment: .leading, spacing: JerreaderSpacing.sm) {
            LibraryShelfDateLine()
                .libraryShelfReveal(order: 0, isEnabled: isShelfRevealOpen)

            if let recentlyViewedBook {
                let source = LibraryCoverTransitionID(
                    bookID: recentlyViewedBook.id,
                    place: .continueReading
                )
                LibraryContinueReadingCard(
                    book: recentlyViewedBook,
                    transitionID: source,
                    transitionNamespace: coverTransition,
                    onOpen: { open(recentlyViewedBook, from: source) }
                )
                .frame(maxWidth: 760, alignment: .leading)
                .libraryShelfReveal(order: 1, isEnabled: isShelfRevealOpen)
            }

            LibraryReadingStatsCard(
                summary: LibraryReadingStatsSummary(books: books),
                rollsIn: isShelfRevealOpen
            )
            .frame(maxWidth: 760, alignment: .leading)
            .libraryShelfReveal(order: 2, isEnabled: isShelfRevealOpen)
        }
    }

    /// The sort order as a capsule beside 所有书籍; the same binding as the
    /// 排序方式 picker in the toolbar menu.
    private var sortMenu: some View {
        Menu {
            Picker("排序方式", selection: $sortOrder) {
                ForEach(LibrarySortOrder.allCases) { order in
                    Label(order.title, systemImage: order.systemImage)
                        .tag(order)
                }
            }
        } label: {
            HStack(spacing: JerreaderSpacing.xxs) {
                JerreaderSymbol(name: "arrow.up.arrow.down", size: 13)
                Text(sortOrder.title)
                    .font(.subheadline.weight(.semibold))
            }
            .foregroundStyle(JerreaderTheme.accent)
            .padding(.horizontal, JerreaderSpacing.sm)
            .frame(minHeight: 30)
            .background(JerreaderTheme.accentFill, in: Capsule())
            // A 44 pt tall target that still only takes 30 pt of the header.
            .padding(.vertical, 7)
            .contentShape(Rectangle())
        }
        .padding(.vertical, -7)
        .accessibilityLabel("排序方式")
        .accessibilityValue(sortOrder.title)
    }

    @ViewBuilder
    private func readerScreen(for session: LibraryReaderSession) -> some View {
        let reader = EPUBReaderScreen(
            book: session.book,
            modelContext: modelContext,
            translationSettings: translationSettings
        )
        .id(session.id)

        if let source = session.coverSource {
            // The reader zooms out of the tapped cover and back into it on
            // close. A zoom adds a swipe-down dismissal that the full-screen
            // cover never had; the reader still closes only through its own
            // close button.
            reader
                .navigationTransition(.zoom(sourceID: source, in: coverTransition))
                .interactiveDismissDisabled()
        } else {
            reader
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
              // The furigana-region check (2.1.0 §2) runs the selection
              // harness on the book a tester placed in tmp, which the
              // sample-book hook below imports.
              !arguments.contains("--jerreader-ruby-region-ui-test"),
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

    /// Opens a book for reader layout and tap-translation checks through the
    /// normal importer. A tester may first copy a DRM-free EPUB of their own
    /// into this app's `tmp/JerreaderUITestBook.epub` on the simulator; that
    /// copy is imported (a second launch reopens it). Without one, or with
    /// `--jerreader-sample-text-ui-test`, a short original Japanese text is
    /// imported as TXT. No book or host path is kept in the project, and the
    /// hook is compiled out of Release builds.
    @MainActor
    private func startSampleBookUITestImportIfNeeded() {
        guard ProcessInfo.processInfo.arguments.contains("--jerreader-sample-book-ui-test"),
              !didStartSelectionUITestImport
        else { return }

        didStartSelectionUITestImport = true
        // Every run starts from the shipped 上下边距, whatever the run before
        // it left on the simulator.
        UserDefaults.standard.removeObject(forKey: ReaderInterfaceDefaults.verticalMarginsKey)
        let fileManager = FileManager.default
        let temporary = fileManager.temporaryDirectory
        let placedBook = temporary.appendingPathComponent("JerreaderUITestBook.epub")
        let url: URL
        do {
            // A check that taps a sentence needs text on the first page, and
            // a placed book usually opens on its cover.
            let wantsWrittenText = ProcessInfo.processInfo.arguments
                .contains("--jerreader-sample-text-ui-test")
            if !wantsWrittenText, fileManager.fileExists(atPath: placedBook.path) {
                // Import a copy so the placed book survives for the next launch.
                url = temporary.appendingPathComponent("JerreaderUITestBook-import.epub")
                try? fileManager.removeItem(at: url)
                try fileManager.copyItem(at: placedBook, to: url)
            } else {
                url = temporary.appendingPathComponent("Jerreader 测试书.txt")
                try SampleBookUITestText.body.write(to: url, atomically: true, encoding: .utf8)
            }
        } catch {
            return
        }
        Task { @MainActor in
            await importBook(from: url, opensAfterImport: true)
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
    private func open(_ book: BookRecord, from coverSource: LibraryCoverTransitionID? = nil) {
        // A tap only starts a reader session. `lastOpenedAt` is persisted with
        // the first real reading location, so a failed or immediately closed
        // open does not mutate the book record either.
        // A same-book reopen must still create a new SwiftUI identity. Reusing
        // the BookRecord ID can resurrect a reader model whose onDisappear()
        // already closed it, leaving its initial `.loading` state unable to
        // start another attempt.
        readerSession = LibraryReaderSession(book: book, coverSource: coverSource)
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
    /// The cover the reader zooms out of; nil presents the plain full-screen
    /// cover (imports that open the new book, for one).
    var coverSource: LibraryCoverTransitionID? = nil
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
                                JerreaderLabel(title: "更换封面", icon: "PHOTO")
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
            .scrollContentBackground(.hidden)
            .background(JerreaderCanvasBackground())
            .navigationTitle("管理书籍")
            .navigationBarTitleDisplayMode(.inline)
            .disabled(isSaving)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(isSaving ? "保存中…" : "保存", action: save)
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
                .jerreaderBookCover(cornerRadius: JerreaderRadius.cover, elevated: false)
        } else {
            LibraryBookCoverView(book: book)
                .frame(width: 82, height: 123)
                .jerreaderBookCover(cornerRadius: JerreaderRadius.cover, elevated: false)
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
    // Off, like the other three: with it on and the field empty, applying a
    // batch that only added tags also moved every chosen book out of its folder.
    @State private var changesFolder = false
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
                            // A plain button only answers taps on what it
                            // draws, so the gap the Spacer leaves did nothing.
                            .contentShape(Rectangle())
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
        LibraryShelfFormat.progressText(lastReadProgress)
    }
}

private extension TimeInterval {
    var readerDurationText: String {
        LibraryShelfFormat.durationText(self)
    }
}

/// A shelf book's cover art: the stored cover once it has loaded, otherwise
/// the generated cover. Callers size and style it, usually with
/// `jerreaderBookCover`.
struct LibraryBookCoverView: View {
    let book: BookRecord
    @State private var cover: BookCoverThumbnail? = nil

    init(book: BookRecord) {
        self.book = book
    }

    var body: some View {
        Group {
            if let cover, cover.fileName == book.coverFileName {
                Image(decorative: cover.image, scale: 1)
                    .resizable()
                    .scaledToFill()
            } else {
                GeometryReader { geometry in
                    JerreaderGeneratedCover(
                        title: book.title,
                        author: book.author.nilIfEmpty,
                        compact: geometry.size.width < 150
                    )
                    .frame(width: geometry.size.width, height: geometry.size.height)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .accessibilityHidden(true)
        .task(id: book.coverFileName) {
            guard let fileName = book.coverFileName else { cover = nil; return }
            let thumbnail = await BookCoverThumbnailStore.shared.load(fileName: fileName)
            guard !Task.isCancelled else { return }
            cover = thumbnail
        }
    }
}

/// The CGImage is fully decoded before publication and remains immutable.
private final class BookCoverThumbnail: @unchecked Sendable {
    let fileName: String
    let image: CGImage
    var cost: Int { image.bytesPerRow * image.height }
    init(fileName: String, image: CGImage) { self.fileName = fileName; self.image = image }
}

/// Serial background decoding and a bounded LRU; no file access in View.body.
private actor BookCoverThumbnailStore {
    static let shared = BookCoverThumbnailStore()
    private var cached: [String: BookCoverThumbnail] = [:]
    private var recency: [String] = []
    private var cost = 0
    private let capacity = 24 * 1024 * 1024

    func load(fileName: String) -> BookCoverThumbnail? {
        guard !Task.isCancelled,
              let url = try? LibraryPaths.coverURL(fileName: fileName),
              let values = try? url.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey]) else { return nil }
        let key = "\(fileName):\(values.contentModificationDate?.timeIntervalSince1970 ?? 0):\(values.fileSize ?? 0)"
        if let thumbnail = cached[key] {
            recency.removeAll { $0 == key }; recency.append(key)
            return thumbnail
        }
        guard let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: 768,
                kCGImageSourceShouldCacheImmediately: true
              ] as CFDictionary), !Task.isCancelled else { return nil }
        let thumbnail = BookCoverThumbnail(fileName: fileName, image: image)
        while cost + thumbnail.cost > capacity, let oldest = recency.first {
            recency.removeFirst()
            cost -= cached.removeValue(forKey: oldest)?.cost ?? 0
        }
        if thumbnail.cost <= capacity {
            cached[key] = thumbnail; recency.append(key); cost += thumbnail.cost
        }
        return thumbnail
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
                        Text("系统文件选择器里，其他格式的文件会变灰、不能选。")
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
                                        JerreaderSymbol(name: format.icon, size: 20)
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
                                .accessibilityLabel("只选 \(format.accessibilityTitle)")
                            }
                        }
                    }
                    .jerreaderPaperCard(padding: 16)

                    Button {
                        isShowingFileImporter = true
                    } label: {
                        TransferMethodCard(
                            title: "选择\(selectedFormat.filePickerTitle)",
                            description: "从“文件”、iCloud Drive、下载目录或外接存储中选择。系统会交给Jerreader一个只读副本，不会直接打开或写回原文件；当前只能选\(selectedFormat.filePickerDescription)，其他格式会变灰。",
                            icon: "FOLDER",
                            showsDisclosure: true
                        )
                    }
                    .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.985))
                    .disabled(isImporting)

                    TransferMethodCard(
                        title: "其他 App 与隔空投送",
                        description: "在微信、Safari、邮件或 AirDrop 收到支持的文档后，选择“用Jerreader打开”即可导入。",
                        icon: "DOWNLOAD"
                    )

                    TransferMethodCard(
                        title: "电脑文件共享",
                        description: "用 Mac 的 Finder 或 Windows Apple Devices 将文档复制到Jerreader，然后在上方从“文件”里选择它。",
                        icon: "EXTERNAL_DRIVE"
                    )

                    JerreaderLabel(title: "支持 EPUB、PDF、DOCX 和 TXT；扫描 PDF 可按需使用本机 OCR。不支持受 DRM 保护的书籍、旧式 .doc 或整本上传翻译。", icon: "INFO", size: 13)
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
    /// A key from the shared icon library, so Android draws the same glyph.
    let icon: String
    var showsDisclosure = false

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            JerreaderSymbol(name: icon, size: 22)
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
                JerreaderSymbol(name: "chevron.right", size: 13)
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
        case .recent: "最近"
        case .title: "书名"
        case .author: "作者"
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

    /// Shared icon keys; the Android import sheet uses the same ones.
    var icon: String {
        switch self {
        case .all: return "GRID"
        case .epub: return "BOOKS"
        case .pdf: return "DOC_PDF"
        case .docx: return "DOC_WORD"
        case .text: return "DOC_PLAIN"
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

#if DEBUG
/// Original text written for UI automation; several sentences per paragraph so
/// sentence and paragraph translation can be told apart.
private enum SampleBookUITestText {
    static let body = """
    第一章　朝の図書館
    春の朝、ミナは駅前の小さな図書館へ向かった。空は薄い青で、風はまだ少し冷たかった。彼女は鞄の中の古いノートを何度も確かめた。
    図書館の扉を開けると、紙の匂いがふわりと広がった。受付の田中さんは「おはよう、今日も早いね」と笑った。ミナは小さく頭を下げて、窓際の席に座った。
    机の上には、昨日読みかけた本が置いてあった。表紙は色あせていたが、文字ははっきりと読めた。彼女はしおりを外して、続きのページを開いた。
    その本には、海の向こうの町の話が書かれていた。町の人々は毎朝パンを焼き、夕方には港で歌を歌った。ミナはいつかその町を訪ねてみたいと思った。
    昼が近づくと、館内は少しずつにぎやかになった。子どもたちは絵本の棚の前に座り込み、大人たちは新聞を広げた。ミナはノートに気になった言葉を書き写した。
    「この言葉はどういう意味だろう」と彼女はつぶやいた。辞書を引くと、思っていたより深い意味があった。彼女は鉛筆で丁寧に説明を書き加えた。
    午後になると、外では雨が降り始めた。窓ガラスを伝う水の筋が、ゆっくりと形を変えていった。ミナは雨の音を聞きながら、静かにページをめくった。
    第二章　雨の午後
    雨はなかなか止まなかった。田中さんは温かいお茶を入れて、ミナの机にそっと置いた。「無理をしないでね」と言われ、彼女は少し照れくさそうに笑った。
    本の中では、主人公が古い地図を見つけていた。地図には、誰も知らない小道が細い線で描かれていた。ミナは息をのんで、次の行を読み進めた。
    夕方、雨はようやく上がった。雲の切れ間から、やわらかな光が差し込んだ。ミナはノートを閉じて、明日もここに来ようと決めた。
    帰り道、水たまりに空が映っていた。彼女は一歩ずつ確かめるように歩いた。家に着くころには、町の灯りが一つ、また一つとともり始めた。
    """
}
#endif
