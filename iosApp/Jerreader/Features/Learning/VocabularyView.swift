import SwiftData
import SwiftUI
import UniformTypeIdentifiers
import class JerreaderCore.VocabularyLearningPolicy

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
    @State private var vocabularyStatusFilter = "all"

    var body: some View {
        VStack(spacing: 0) {
            collectionControls

            Divider()
                .overlay(JerreaderTheme.line)

            Group {
                if filteredWordFavorites.isEmpty, filteredTranslationFavorites.isEmpty {
                    emptyState
                } else {
                    favoritesList
                }
            }
            .id(collectionStateIdentity)
            .transition(.opacity.combined(with: .scale(scale: 0.99, anchor: .top)))
        }
        .background(Color.clear)
        .tint(JerreaderTheme.accent)
        .animation(
            reduceMotion ? nil : JerreaderMotion.stateChange,
            value: collectionStateIdentity
        )
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
        .task {
#if DEBUG
            seedVocabularyUITestRecordsIfNeeded()
#endif
        }
    }

    private var collectionControls: some View {
        HStack(spacing: 10) {
            LearningSearchField(
                text: $searchText,
                prompt: "搜索词语或译文"
            )

            LearningCountBadge(
                count: wordFavorites.count + translationFavorites.count,
                noun: "收藏"
            )

            Menu {
                Button("全部状态") { vocabularyStatusFilter = "all" }
                ForEach(
                    VocabularyLearningPolicy.shared.allStatuses(),
                    id: \.storageId
                ) { status in
                    Button(status.title) {
                        vocabularyStatusFilter = status.storageId
                    }
                }
            } label: {
                LearningToolbarIcon(
                    systemImage: "line.3.horizontal.decrease.circle",
                    accessibilityLabel: statusFilterAccessibilityLabel
                )
            }
            .disabled(wordFavorites.isEmpty)

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
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
    }

    private var filteredWordFavorites: [WordLookupRecord] {
        let term = normalizedSearchTerm
        return wordFavorites.filter { record in
            let matchesStatus = vocabularyStatusFilter == "all"
                || record.vocabularyStatusRawValue == vocabularyStatusFilter
            let matchesSearch = term.isEmpty
                || record.surfaceForm.localizedCaseInsensitiveContains(term)
                || (record.lemma?.localizedCaseInsensitiveContains(term) ?? false)
                || (record.reading?.localizedCaseInsensitiveContains(term) ?? false)
                || record.definitions.contains { $0.localizedCaseInsensitiveContains(term) }
            return matchesStatus && matchesSearch
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

    private var statusFilterAccessibilityLabel: String {
        guard vocabularyStatusFilter != "all" else {
            return "按学习状态筛选，当前为全部状态"
        }
        let title = VocabularyLearningPolicy.shared
            .statusTitle(storageId: vocabularyStatusFilter)
        return "按学习状态筛选，当前为\(title)"
    }

    private var collectionStateIdentity: String {
        if filteredWordFavorites.isEmpty, filteredTranslationFavorites.isEmpty {
            return normalizedSearchTerm.isEmpty
                ? "empty-\(vocabularyStatusFilter)"
                : "no-results-\(vocabularyStatusFilter)"
        }
        return "results-\(vocabularyStatusFilter)"
    }

    private var favoritesList: some View {
        List {
            if !filteredWordFavorites.isEmpty {
                Section {
                    ForEach(filteredWordFavorites) { record in
                        NavigationLink {
                            WordRecordDetailView(record: record)
                        } label: {
                            WordRecordRow(record: record)
                        }
                        .favoriteCardStyle()
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) {
                                removeWordFavorite(record)
                            } label: {
                                Label("取消收藏", systemImage: "star.slash")
                            }
                        }
                    }
                } header: {
                    FavoriteSectionHeader(
                        title: "收藏词语",
                        detail: "词条收藏保存在本机，清空查词历史不会删除它们。"
                    )
                }
            }

            if !filteredTranslationFavorites.isEmpty {
                Section {
                    ForEach(filteredTranslationFavorites) { record in
                        TranslationFavoriteRow(record: record)
                            .favoriteCardStyle()
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    removeTranslationFavorite(record)
                                } label: {
                                    Label("取消收藏", systemImage: "star.slash")
                                }
                            }
                    }
                } header: {
                    FavoriteSectionHeader(
                        title: "收藏译文",
                        detail: "阅读中点按译文卡片上的星标即可保存。"
                    )
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
        let hasSearch = !normalizedSearchTerm.isEmpty
        let isStatusOnlyEmpty = !hasSearch
            && vocabularyStatusFilter != "all"
            && !wordFavorites.isEmpty
        return JerreaderEmptyState(
            title: hasSearch
                ? "没有匹配内容"
                : (isStatusOnlyEmpty ? "该状态下没有词条" : "收藏还是空的"),
            message: hasSearch
                ? "请尝试搜索其他词语、原文、译文或书名。"
                : (isStatusOnlyEmpty
                    ? "请选择其他学习状态，或切换回全部状态。"
                    : "在翻译的词语模式收藏词典结果，或在阅读中的译文卡片点按星标，内容就会出现在这里。"),
            systemImage: hasSearch ? "magnifyingglass" : "bookmark"
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
                context: record.contextHistory.joined(separator: "\n"),
                language: record.language.displayName,
                bookTitle: record.sourceBookTitle,
                tags: [
                    "生词",
                    VocabularyLearningPolicy.shared.statusTitle(
                        storageId: record.vocabularyStatusRawValue
                    ),
                ],
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

#if DEBUG
    private func seedVocabularyUITestRecordsIfNeeded() {
        guard ProcessInfo.processInfo.arguments.contains("--jerreader-vocabulary-ui-test")
        else { return }

        let existingKeys = Set(
            (try? modelContext.fetch(FetchDescriptor<WordLookupRecord>()))?
                .map(\.lookupKey) ?? []
        )
        let fixtures: [(WordExplanation, String, Int, [String])] = [
            (
                WordExplanation(
                    surfaceForm: "本",
                    lemma: nil,
                    reading: "ほん",
                    language: .japanese,
                    partOfSpeech: "名词",
                    definitions: ["book; volume"],
                    usageNote: "离线 JMdict 英文释义",
                    sentenceContext: "本を読みます。"
                ),
                "learning",
                8,
                ["本を読みます。", "この本は面白い。"]
            ),
            (
                WordExplanation(
                    surfaceForm: "extraordinary",
                    lemma: nil,
                    reading: nil,
                    language: .english,
                    partOfSpeech: "形容词",
                    definitions: ["very unusual or remarkable"],
                    usageNote: nil,
                    sentenceContext: "An extraordinary result."
                ),
                "known",
                3,
                ["An extraordinary result."]
            ),
        ]

        for (offset, fixture) in fixtures.enumerated() {
            let (explanation, status, count, contexts) = fixture
            let key = WordLookupRecord.makeLookupKey(for: explanation)
            guard !existingKeys.contains(key) else { continue }
            let record = WordLookupRecord(
                explanation: explanation,
                lookupCount: count,
                createdAt: Date().addingTimeInterval(Double(-600 - offset)),
                lastLookedUpAt: Date().addingTimeInterval(Double(-60 - offset)),
                isFavorite: true,
                vocabularyStatusRawValue: status,
                contextHistoryText: VocabularyLearningPolicy.shared
                    .encodeContexts(contexts: contexts)
            )
            modelContext.insert(record)
        }
        try? modelContext.save()
    }
#endif
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

private struct FavoriteSectionHeader: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.headline)
                .foregroundStyle(.primary)
            Text(detail)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .textCase(nil)
        .padding(.bottom, 4)
    }
}

private struct TranslationFavoriteRow: View {
    let record: TranslationFavoriteRecord

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Label(languagePair, systemImage: "character.book.closed")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(JerreaderTheme.accent)
                Spacer()
                Text(record.updatedAt, format: .dateTime.month().day())
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }

            Text(record.translatedText)
                .font(.body.weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(5)

            Text(record.sourceText)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .lineLimit(3)

            if let bookTitle = record.bookTitle, !bookTitle.isEmpty {
                Label(bookTitle, systemImage: "book.closed")
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

private extension View {
    func favoriteCardStyle() -> some View {
        padding(16)
            .background(JerreaderTheme.paper, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(JerreaderTheme.line, lineWidth: 0.75)
            }
            .listRowInsets(EdgeInsets(top: 6, leading: 20, bottom: 6, trailing: 20))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }
}
