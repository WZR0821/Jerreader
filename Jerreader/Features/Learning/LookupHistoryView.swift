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
    /// Open only just after the page appears; see `LearningStagger`.
    @State private var staggerActive = true

    var body: some View {
        VStack(spacing: 0) {
            collectionControls

            Group {
                if filteredHistory.isEmpty {
                    emptyState
                } else {
                    historyList
                }
            }
            .id(collectionStateIdentity)
            .transition(stateTransition)
        }
        .background(Color.clear)
        .tint(JerreaderTheme.accent)
        // Scoped to switching between the list and the two empty states.
        .animation(
            JerreaderMotion.choose(JerreaderMotion.stateChange, reduceMotion: reduceMotion),
            value: collectionStateIdentity
        )
        .task {
            try? await Task.sleep(for: LearningStagger.window)
            staggerActive = false
        }
        // Clearing asks first: a warning tick as the question appears.
        .sensoryFeedback(trigger: isConfirmingClear) { old, new in
            !old && new ? SensoryFeedback.warning : nil
        }
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

    /// The list and the empty states cross-fade, the new one settling from
    /// 99%; with Reduce Motion they only cross-fade.
    private var stateTransition: AnyTransition {
        reduceMotion
            ? AnyTransition.opacity
            : AnyTransition.opacity.combined(with: .scale(scale: 0.99, anchor: .top))
    }

    /// Search, count and clear on the canvas, under the section switcher.
    private var collectionControls: some View {
        HStack(spacing: JerreaderSpacing.xs) {
            LearningSearchField(text: $searchText, prompt: "搜索查词历史")

            LearningCountBadge(count: history.count, noun: "记录")

            Button {
                isConfirmingClear = true
            } label: {
                LearningToolbarIcon(
                    systemImage: "trash",
                    accessibilityLabel: "清空查词历史",
                    tint: JerreaderTheme.vermilion
                )
            }
            .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.94))
            .disabled(history.isEmpty)
        }
        .frame(maxWidth: 960)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, JerreaderTheme.pagePadding)
        .padding(.top, JerreaderSpacing.sm)
        .padding(.bottom, JerreaderSpacing.xxs)
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
        // Filtered and grouped once per update, not once per row.
        let records = filteredHistory
        let days = historyDays(records)
        return List {
            VStack(alignment: .leading, spacing: JerreaderSpacing.xs) {
                LearningSectionHeading(
                    title: "最近查询",
                    detail: "共 \(history.count) 个词条，按最近查询时间排列。"
                )
                .accessibilityElement(children: .combine)
                .accessibilityAddTraits(.isHeader)
                dotLegend
            }
            .listRowInsets(EdgeInsets(top: 8, leading: JerreaderTheme.pagePadding, bottom: 2, trailing: JerreaderTheme.pagePadding))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)

            ForEach(days) { day in
                LearningDayHeading(title: day.title)
                ForEach(day.records) { record in
                    NavigationLink {
                        WordRecordDetailView(record: record)
                    } label: {
                        WordRecordRow(record: record, dotTint: dotTint(for: record))
                    }
                    .learningListCard(
                        order: LearningStagger.order(of: record, in: records),
                        active: staggerActive
                    )
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

    /// What the dots mean. VoiceOver hears 已收藏 from the star instead.
    private var dotLegend: some View {
        HStack(spacing: JerreaderSpacing.sm) {
            legendItem("已收藏", tint: JerreaderTheme.accent)
            legendItem("多次查询", tint: JerreaderTheme.vermilion)
            legendItem("查过一次", tint: Color.secondary.opacity(0.4))
        }
        .font(.caption)
        .foregroundStyle(.secondary)
        .accessibilityHidden(true)
    }

    private func legendItem(_ title: String, tint: Color) -> some View {
        HStack(spacing: 5) {
            LearningKindDot(tint: tint)
            Text(title)
        }
    }

    /// Accent for a saved word, vermilion for one looked up more than once,
    /// a light grey for the rest.
    private func dotTint(for record: WordLookupRecord) -> Color {
        if record.isFavorite { return JerreaderTheme.accent }
        if record.lookupCount >= 2 { return JerreaderTheme.vermilion }
        return Color.secondary.opacity(0.4)
    }

    /// Records by the day they were last looked up, newest first. `records`
    /// is already sorted, so each day is one run of it.
    private func historyDays(_ records: [WordLookupRecord]) -> [LookupHistoryDay] {
        let calendar = Calendar.current
        let now = Date()
        var days: [LookupHistoryDay] = []
        for record in records {
            let day = calendar.startOfDay(for: record.lastLookedUpAt)
            if days.last?.id == day {
                days[days.count - 1].records.append(record)
            } else {
                days.append(LookupHistoryDay(id: day, title: dayTitle(day, now: now, calendar: calendar), records: [record]))
            }
        }
        return days
    }

    /// 今天, 昨天, 11月3日, or 2025年11月3日 for an earlier year.
    private func dayTitle(_ day: Date, now: Date, calendar: Calendar) -> String {
        if calendar.isDate(day, inSameDayAs: now) { return "今天" }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now),
           calendar.isDate(day, inSameDayAs: yesterday) {
            return "昨天"
        }
        let parts = calendar.dateComponents([.year, .month, .day], from: day)
        let monthDay = "\(parts.month ?? 1)月\(parts.day ?? 1)日"
        return parts.year == calendar.component(.year, from: now) ? monthDay : "\(parts.year ?? 0)年" + monthDay
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

/// One day of 历史.
private struct LookupHistoryDay: Identifiable {
    let id: Date
    let title: String
    var records: [WordLookupRecord]
}
