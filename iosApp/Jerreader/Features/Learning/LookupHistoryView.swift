import SwiftData
import SwiftUI

struct LookupHistoryView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.modelContext) private var modelContext

    @Query(
        filter: #Predicate<WordLookupRecord> { $0.isInHistory },
        sort: \WordLookupRecord.lastLookedUpAt,
        order: .reverse
    ) private var history: [WordLookupRecord]

    @State private var searchText = ""
    @State private var isConfirmingClear = false
    @State private var alert: LearningAlert?

    var body: some View {
        VStack(spacing: 0) {
            collectionControls

            Divider()
                .overlay(JerreaderTheme.line)

            Group {
                if filteredHistory.isEmpty {
                    emptyState
                } else {
                    historyList
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
        .confirmationDialog(
            "清空查词历史？",
            isPresented: $isConfirmingClear,
            titleVisibility: .visible
        ) {
            Button("清空历史", role: .destructive, action: clearHistory)
            Button("取消", role: .cancel) {}
        } message: {
            Text("未收藏的记录会被删除；已收藏词条仍会保留在生词本中。")
        }
        .alert(item: $alert) { item in
            Alert(
                title: Text(item.title),
                message: Text(item.message),
                dismissButton: .default(Text("好"))
            )
        }
    }

    private var collectionControls: some View {
        HStack(spacing: 10) {
            LearningSearchField(text: $searchText, prompt: "搜索查词历史")

            LearningCountBadge(count: history.count, noun: "记录")

            Button {
                isConfirmingClear = true
            } label: {
                LearningToolbarIcon(
                    systemImage: "trash",
                    accessibilityLabel: "清空查词历史"
                )
            }
            .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.94))
            .disabled(history.isEmpty)
        }
        .frame(maxWidth: 960)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, JerreaderTheme.pagePadding)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
    }

    private var filteredHistory: [WordLookupRecord] {
        let term = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else { return history }

        return history.filter { record in
            record.surfaceForm.localizedCaseInsensitiveContains(term)
                || (record.lemma?.localizedCaseInsensitiveContains(term) ?? false)
                || (record.reading?.localizedCaseInsensitiveContains(term) ?? false)
                || record.definitions.contains { $0.localizedCaseInsensitiveContains(term) }
                || (record.sentenceContext?.localizedCaseInsensitiveContains(term) ?? false)
        }
    }

    private var collectionStateIdentity: String {
        if filteredHistory.isEmpty {
            return searchText.isEmpty ? "empty" : "no-results"
        }
        return "results"
    }

    private var historyList: some View {
        List {
            Section {
                ForEach(filteredHistory) { record in
                    NavigationLink {
                        WordRecordDetailView(record: record)
                    } label: {
                        WordRecordRow(record: record)
                    }
                    .padding(16)
                    .background(JerreaderTheme.paper, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay {
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .stroke(JerreaderTheme.line, lineWidth: 0.75)
                    }
                    .listRowInsets(EdgeInsets(top: 6, leading: 20, bottom: 6, trailing: 20))
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                    .swipeActions(edge: .leading) {
                        Button {
                            toggleFavorite(record)
                        } label: {
                            Label(
                                record.isFavorite ? "取消收藏" : "收藏",
                                systemImage: record.isFavorite ? "star.slash" : "star"
                            )
                        }
                        .tint(JerreaderTheme.accent)
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            removeFromHistory(record)
                        } label: {
                            Label("删除历史", systemImage: "trash")
                        }
                    }
                }
            } header: {
                VStack(alignment: .leading, spacing: 4) {
                    Text("最近查询")
                        .font(.headline)
                        .foregroundStyle(.primary)
                    Text("共 \(history.count) 个词条，按最近查询时间排列。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .textCase(nil)
                .padding(.bottom, 4)
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
            title: searchText.isEmpty ? "还没有查词记录" : "没有匹配记录",
            message: searchText.isEmpty
                ? "在“翻译”的词语模式完成一次词典查询后，结果会自动保存在这里。"
                : "请尝试其他词语、读音、释义或上下文。",
            systemImage: searchText.isEmpty ? "clock" : "magnifyingglass"
        )
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func toggleFavorite(_ record: WordLookupRecord) {
        do {
            try WordLookupStore.setFavorite(!record.isFavorite, for: record, in: modelContext)
        } catch {
            alert = LearningAlert(
                title: "无法更新收藏",
                message: (error as? LocalizedError)?.errorDescription ?? "请稍后重试。"
            )
        }
    }

    private func removeFromHistory(_ record: WordLookupRecord) {
        do {
            try WordLookupStore.removeFromHistory(record, in: modelContext)
        } catch {
            alert = LearningAlert(
                title: "无法删除记录",
                message: (error as? LocalizedError)?.errorDescription ?? "请稍后重试。"
            )
        }
    }

    private func clearHistory() {
        do {
            try WordLookupStore.clearHistory(history, in: modelContext)
        } catch {
            alert = LearningAlert(
                title: "无法清空历史",
                message: (error as? LocalizedError)?.errorDescription ?? "请稍后重试。"
            )
        }
    }
}
