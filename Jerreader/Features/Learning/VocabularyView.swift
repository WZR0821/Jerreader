import SwiftData
import SwiftUI
import UniformTypeIdentifiers

struct VocabularyView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.modelContext) private var modelContext

    @Query(
        filter: #Predicate<WordLookupRecord> { $0.isFavorite },
        sort: \WordLookupRecord.lastLookedUpAt,
        order: .reverse
    ) private var wordFavorites: [WordLookupRecord]

    @Query(
        sort: \TranslationFavoriteRecord.updatedAt,
        order: .reverse
    ) private var translationFavorites: [TranslationFavoriteRecord]

    @State private var searchText = ""
    @State private var alert: LearningAlert?
    @State private var exportDocument: LearningExportDocument?
    @State private var exportFormat: LearningExportFormat = .csv
    @State private var isExporting = false
    /// Open only just after the page appears; see `LearningStagger`.
    @State private var staggerActive = true

    var body: some View {
        VStack(spacing: 0) {
            collectionControls

            Group {
                if filteredWordFavorites.isEmpty, filteredTranslationFavorites.isEmpty {
                    emptyState
                } else {
                    favoritesList
                }
            }
            .id(collectionStateIdentity)
            .transition(stateTransition)
        }
        .background(Color.clear)
        .tint(JerreaderTheme.accent)
        // Scoped to switching between the list, 收藏还是空的 and 没有匹配内容.
        .animation(
            JerreaderMotion.choose(JerreaderMotion.stateChange, reduceMotion: reduceMotion),
            value: collectionStateIdentity
        )
        .task {
            try? await Task.sleep(for: LearningStagger.window)
            staggerActive = false
        }
        .alert(item: $alert) { item in
            Alert(
                title: Text(item.title),
                message: Text(item.message),
                dismissButton: .default(Text("好"))
            )
        }
        .fileExporter(
            isPresented: $isExporting,
            document: exportDocument,
            contentType: exportFormat.contentType,
            defaultFilename: "Jerreader-学习导出.\(exportFormat.fileExtension)"
        ) { result in
            if case let .failure(error) = result,
               (error as NSError).code != NSUserCancelledError
            {
                alert = LearningAlert(
                    title: "无法导出",
                    message: "文件没有保存成功，请重新选择位置。"
                )
            }
            exportDocument = nil
        }
    }

    /// The list and the empty states cross-fade, the new one settling from
    /// 99%; with Reduce Motion they only cross-fade.
    private var stateTransition: AnyTransition {
        reduceMotion
            ? AnyTransition.opacity
            : AnyTransition.opacity.combined(with: .scale(scale: 0.99, anchor: .top))
    }

    /// Search, count and export on the canvas, under the section switcher.
    private var collectionControls: some View {
        HStack(spacing: JerreaderSpacing.xs) {
            LearningSearchField(
                text: $searchText,
                prompt: "搜索词语、原文或译文"
            )

            LearningCountBadge(
                count: wordFavorites.count + translationFavorites.count,
                noun: "收藏"
            )

            Menu {
                ForEach(LearningExportFormat.allCases) { format in
                    Button {
                        prepareExport(format)
                    } label: {
                        Label(format.title, systemImage: format.systemImage)
                    }
                }
            } label: {
                LearningToolbarIcon(
                    systemImage: "square.and.arrow.up",
                    accessibilityLabel: "导出生词与译文收藏"
                )
            }
            .disabled(wordFavorites.isEmpty && translationFavorites.isEmpty)
        }
        .frame(maxWidth: 960)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, JerreaderTheme.pagePadding)
        .padding(.top, JerreaderSpacing.sm)
        .padding(.bottom, JerreaderSpacing.xxs)
    }

    private var filteredWordFavorites: [WordLookupRecord] {
        let term = normalizedSearchTerm
        guard !term.isEmpty else { return wordFavorites }

        return wordFavorites.filter { record in
            record.surfaceForm.localizedCaseInsensitiveContains(term)
                || (record.lemma?.localizedCaseInsensitiveContains(term) ?? false)
                || (record.reading?.localizedCaseInsensitiveContains(term) ?? false)
                || record.definitions.contains { $0.localizedCaseInsensitiveContains(term) }
        }
    }

    private var filteredTranslationFavorites: [TranslationFavoriteRecord] {
        let term = normalizedSearchTerm
        guard !term.isEmpty else { return translationFavorites }

        return translationFavorites.filter { record in
            record.sourceText.localizedCaseInsensitiveContains(term)
                || record.translatedText.localizedCaseInsensitiveContains(term)
                || (record.bookTitle?.localizedCaseInsensitiveContains(term) ?? false)
        }
    }

    private var normalizedSearchTerm: String {
        searchText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var collectionStateIdentity: String {
        if filteredWordFavorites.isEmpty, filteredTranslationFavorites.isEmpty {
            return normalizedSearchTerm.isEmpty ? "empty" : "no-results"
        }
        return "results"
    }

    private var favoritesList: some View {
        // Filtered once per update, not once per row.
        let words = filteredWordFavorites
        let translations = filteredTranslationFavorites
        // The translations continue the stagger where the words leave off.
        let translationOffset = min(words.count, LearningStagger.rowLimit)
        return List {
            if !words.isEmpty {
                LearningListHeading(
                    title: "收藏词语",
                    detail: "词条收藏保存在本机，清空查词历史不会删除它们。"
                )
                ForEach(words) { record in
                    NavigationLink {
                        WordRecordDetailView(record: record)
                    } label: {
                        WordRecordRow(record: record)
                    }
                    .learningListCard(
                        order: LearningStagger.order(of: record, in: words),
                        active: staggerActive
                    )
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            removeWordFavorite(record)
                        } label: {
                            Label("取消收藏", systemImage: "star.slash")
                        }
                    }
                }
            }

            if !translations.isEmpty {
                LearningListHeading(
                    title: "收藏译文",
                    detail: "阅读中点按译文卡片上的星标即可保存。"
                )
                ForEach(translations) { record in
                    TranslationFavoriteRow(record: record)
                        .learningListCard(
                            order: LearningStagger.order(of: record, in: translations).flatMap { order in
                                order + translationOffset < LearningStagger.rowLimit ? order + translationOffset : nil
                            },
                            active: staggerActive
                        )
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) {
                                removeTranslationFavorite(record)
                            } label: {
                                Label("取消收藏", systemImage: "star.slash")
                            }
                        }
                }
            }
        }
        .listStyle(.plain)
        .frame(maxWidth: 960)
        .frame(maxWidth: .infinity)
        .scrollContentBackground(.hidden)
        .contentMargins(.top, 8, for: .scrollContent)
        .scrollDismissesKeyboard(.interactively)
    }

    private var emptyState: some View {
        JerreaderEmptyState(
            title: searchText.isEmpty ? "收藏还是空的" : "没有匹配内容",
            message: searchText.isEmpty
                ? "在翻译的词语模式收藏词典结果，或在阅读中的译文卡片点按星标，内容就会出现在这里。"
                : "请尝试搜索其他词语、原文、译文或书名。",
            systemImage: searchText.isEmpty ? "bookmark" : "magnifyingglass"
        )
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func removeWordFavorite(_ record: WordLookupRecord) {
        do {
            try WordLookupStore.setFavorite(false, for: record, in: modelContext)
        } catch {
            alert = LearningAlert(
                title: "无法取消收藏",
                message: (error as? LocalizedError)?.errorDescription ?? "请稍后重试。"
            )
        }
    }

    private func removeTranslationFavorite(_ record: TranslationFavoriteRecord) {
        modelContext.delete(record)
        do {
            try modelContext.save()
        } catch {
            alert = LearningAlert(
                title: "无法取消收藏",
                message: "译文收藏暂时无法更新，请稍后重试。"
            )
        }
    }

    private func prepareExport(_ format: LearningExportFormat) {
        let words = wordFavorites.map { record in
            LearningExportEntry(
                kind: .word,
                front: record.surfaceForm,
                back: record.definitions.joined(separator: "；"),
                reading: record.reading,
                context: record.sentenceContext,
                language: record.language.displayName,
                bookTitle: record.sourceBookTitle,
                tags: ["生词"],
                date: record.lastLookedUpAt
            )
        }
        let translations = translationFavorites.map { record in
            LearningExportEntry(
                kind: .translation,
                front: record.sourceText,
                back: record.translatedText,
                reading: nil,
                context: nil,
                language: record.sourceLanguage?.displayName ?? "原文",
                bookTitle: record.bookTitle,
                tags: ["译文收藏"],
                date: record.updatedAt
            )
        }
        exportFormat = format
        exportDocument = LearningExportDocument(
            data: LearningExportService.data(entries: words + translations, format: format)
        )
        isExporting = true
    }
}

private struct LearningExportDocument: FileDocument {
    static var readableContentTypes: [UTType] {
        LearningExportFormat.allCases.map(\.contentType)
    }

    static var writableContentTypes: [UTType] {
        LearningExportFormat.allCases.map(\.contentType)
    }

    let data: Data

    init(data: Data) {
        self.data = data
    }

    init(configuration: ReadConfiguration) throws {
        data = configuration.file.regularFileContents ?? Data()
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}

private struct TranslationFavoriteRow: View {
    let record: TranslationFavoriteRecord

    var body: some View {
        VStack(alignment: .leading, spacing: JerreaderSpacing.xs) {
            HStack(alignment: .firstTextBaseline, spacing: JerreaderSpacing.xs) {
                LearningChip(text: languagePair)
                Spacer(minLength: JerreaderSpacing.xs)
                Text(record.updatedAt, format: .dateTime.month().day())
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }

            Text(record.translatedText)
                .font(.body.weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(5)

            Text(record.sourceText)
                .font(.system(.subheadline, design: .serif))
                .foregroundStyle(.secondary)
                .lineLimit(3)
                .padding(.leading, 10)
                .overlay(alignment: .leading) {
                    Capsule()
                        .fill(JerreaderTheme.accent.opacity(0.35))
                        .frame(width: 3)
                        .accessibilityHidden(true)
                }

            if let bookTitle = record.bookTitle, !bookTitle.isEmpty {
                JerreaderLabel(title: bookTitle, icon: "book.closed", size: 14)
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var languagePair: String {
        let source = record.sourceLanguage?.displayName ?? "原文"
        let target = record.targetLanguage?.displayName ?? "译文"
        return "\(source) → \(target)"
    }
}
