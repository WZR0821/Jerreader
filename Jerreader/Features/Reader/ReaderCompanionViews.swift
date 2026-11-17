import SwiftUI

/// The reader's "AI" chrome button. Its dot means there is something new to
/// look at (a recap after reopening, or a finished chapter's loop); tapping it
/// is the only way the companion ever appears.
struct ReaderCompanionChromeButton: View {
    let companion: ReaderCompanionModel
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text("AI")
                .font(.body.weight(.semibold))
                .foregroundStyle(JerreaderTheme.accent)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
                .overlay(alignment: .topTrailing) {
                    if companion.hasUpdate {
                        Circle()
                            .fill(JerreaderTheme.accent)
                            .frame(width: 7, height: 7)
                            .padding(5)
                    }
                }
        }
        .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.90))
        .accessibilityLabel(companion.hasUpdate ? "阅读伴读，有新的回顾" : "阅读伴读")
        .accessibilityIdentifier("reader_companion_button")
    }
}

/// Presents the companion and the chapter loop. `onSearch` runs once a sheet
/// has fully gone, so the reader can open its own search sheet in its place.
struct ReaderCompanionSheets: ViewModifier {
    let companion: ReaderCompanionModel
    let onSearch: (String) -> Void

    func body(content: Content) -> some View {
        content
            .sheet(
                isPresented: Binding(
                    get: { companion.isShowingCompanion },
                    set: { companion.isShowingCompanion = $0 }
                ),
                onDismiss: sheetDismissed
            ) {
                ReaderCompanionSheet(companion: companion)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
            .sheet(
                isPresented: Binding(
                    get: { companion.isShowingClosure },
                    set: { companion.isShowingClosure = $0 }
                ),
                onDismiss: sheetDismissed
            ) {
                ReaderChapterClosureSheet(companion: companion)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            }
    }

    private func sheetDismissed() {
        if let term = companion.sheetDismissed() {
            onSearch(term)
        }
    }
}

private struct ReaderCompanionSheet: View {
    let companion: ReaderCompanionModel

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Picker(
                        "伴读模式",
                        selection: Binding(
                            get: { companion.mode },
                            set: { companion.setMode($0) }
                        )
                    ) {
                        ForEach(ReadingCompanionMode.allCases) { mode in
                            Text("\(mode.title)伴读").tag(mode)
                        }
                    }
                    .pickerStyle(.segmented)

                    VStack(alignment: .leading, spacing: 10) {
                        Text(companion.recap.title)
                            .font(.title3.weight(.semibold))
                        ForEach(Array(companion.recap.bullets.enumerated()), id: \.offset) { _, bullet in
                            Label(bullet, systemImage: "arrow.forward.circle")
                                .font(.subheadline)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .jerreaderPaperCard(padding: 18)

                    ReadingConfusionMapCard(confusion: companion.confusion) { term in
                        companion.requestSearch(term)
                    }

                    Button {
                        companion.startClosure()
                    } label: {
                        Label("90 秒章节闭环 · \(companion.closureChapterTitle)", systemImage: "timer")
                            .lineLimit(1)
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("start_chapter_closure")
                }
                .padding(JerreaderTheme.pagePadding)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
            }
            .background(JerreaderCanvasBackground())
            .navigationTitle("\(companion.mode.title)伴读")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("继续阅读") { companion.isShowingCompanion = false }
                }
            }
        }
        .tint(JerreaderTheme.accent)
        .accessibilityIdentifier("reader_companion_sheet")
    }
}

private struct ReaderChapterClosureSheet: View {
    let companion: ReaderCompanionModel

    var body: some View {
        NavigationStack {
            ScrollView {
                if let plan = companion.closurePlan {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("\(plan.chapterTitle) · 每步 30 秒，先回想，再回到原文验证。")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        ForEach(plan.prompts) { prompt in
                            VStack(alignment: .leading, spacing: 8) {
                                Text(prompt.title)
                                    .font(.headline)
                                    .foregroundStyle(JerreaderTheme.accent)
                                Text(prompt.prompt)
                                    .font(.body)
                                    .fixedSize(horizontal: false, vertical: true)
                                if let term = prompt.searchTerm {
                                    Button("在书中查找“\(term)”") {
                                        companion.requestSearch(term)
                                    }
                                    .buttonStyle(.bordered)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .jerreaderPaperCard(padding: 18)
                        }
                    }
                    .padding(JerreaderTheme.pagePadding)
                    .frame(maxWidth: 720)
                    .frame(maxWidth: .infinity)
                }
            }
            .background(JerreaderCanvasBackground())
            .navigationTitle("\(companion.closurePlan?.estimatedSeconds ?? 90) 秒章节闭环")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { companion.isShowingClosure = false }
                }
            }
        }
        .tint(JerreaderTheme.accent)
        .accessibilityIdentifier("reader_chapter_closure_sheet")
    }
}

/// The confusion map, shared by the reader's companion and the learning hub.
/// With `showsBookTitle` each item names its book, for the page listing them all.
struct ReadingConfusionMapCard: View {
    let confusion: ReadingConfusionMap
    var showsBookTitle = false
    var onSearchTerm: ((String) -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("AI 困惑地图")
                .font(.title3.weight(.semibold))
            Text(confusion.summary)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            ForEach(confusion.items) { item in
                Divider()
                HStack(alignment: .firstTextBaseline) {
                    Text(item.displayTerm).font(.body.weight(.medium))
                    Spacer()
                    Text(item.kind.title)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(JerreaderTheme.accent)
                }
                Text(
                    showsBookTitle && !item.sourceBookTitle.isEmpty
                        ? "《\(item.sourceBookTitle)》 · \(item.reason)"
                        : item.reason
                )
                .font(.caption)
                .foregroundStyle(.secondary)
                if !item.sentenceContext.isEmpty {
                    Text(item.sentenceContext)
                        .font(.footnote)
                        .lineLimit(2)
                }
                if let onSearchTerm {
                    Button("在书中查找") { onSearchTerm(item.displayTerm) }
                        .buttonStyle(.bordered)
                }
            }
            Text("只根据重复查词、真实原句和复习结果生成，不调用网络。")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jerreaderPaperCard(padding: 18)
        .accessibilityIdentifier("reading_confusion_map")
    }
}
