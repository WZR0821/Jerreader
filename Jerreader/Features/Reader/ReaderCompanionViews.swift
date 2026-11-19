import SwiftUI

/// The reader's "AI" chrome button: an accent capsule (sparkles and "AI")
/// inside the bar's 44pt hit area. Its dot means there is something new to
/// look at (a recap after reopening, or a finished chapter's loop); tapping it
/// is the only way the companion ever appears.
struct ReaderCompanionChromeButton: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let companion: ReaderCompanionModel
    /// The bar behind the button, so the dot's ring cuts it off the capsule.
    var ringColor: Color = JerreaderTheme.paper
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 3) {
                JerreaderSymbol(name: "SPARKLES", size: 13)
                Text("AI")
                    .font(.subheadline.weight(.bold))
            }
            .foregroundStyle(JerreaderTheme.accent)
            .padding(.horizontal, 10)
            .frame(height: 30)
            .background(JerreaderTheme.accentFill, in: Capsule())
            .overlay(alignment: .topTrailing) {
                if companion.hasUpdate {
                    Circle()
                        .fill(JerreaderTheme.vermilion)
                        .frame(width: 8, height: 8)
                        .padding(1.5)
                        .background(Circle().fill(ringColor))
                        .offset(x: 3, y: -3)
                        .transition(dotTransition)
                }
            }
            .animation(
                JerreaderMotion.choose(JerreaderMotion.stateChange, reduceMotion: reduceMotion),
                value: companion.hasUpdate
            )
            // The bar is 44pt tall; the capsule does not grow with the text.
            .dynamicTypeSize(...DynamicTypeSize.large)
            .frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.90))
        .accessibilityLabel(companion.hasUpdate ? "阅读伴读，有新的回顾" : "阅读伴读")
        .accessibilityIdentifier("reader_companion_button")
    }

    private var dotTransition: AnyTransition {
        reduceMotion ? .opacity : .scale(scale: 0.4).combined(with: .opacity)
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
                VStack(alignment: .leading, spacing: JerreaderSpacing.md) {
                    JerreaderPillPicker(
                        options: ReadingCompanionMode.allCases.map { mode in
                            JerreaderPillPicker<ReadingCompanionMode>.Option(
                                value: mode,
                                title: "\(mode.title)伴读"
                            )
                        },
                        selection: Binding(
                            get: { companion.mode },
                            set: { companion.setMode($0) }
                        )
                    )
                    .accessibilityElement(children: .contain)
                    .accessibilityLabel("伴读模式")

                    recapCard
                        .jerreaderReveal(order: 0)

                    ReadingConfusionMapCard(confusion: companion.confusion) { term in
                        companion.requestSearch(term)
                    }
                    .jerreaderReveal(order: 1)

                    Button {
                        companion.startClosure()
                    } label: {
                        Label {
                            Text("90 秒章节闭环 · \(companion.closureChapterTitle)")
                        } icon: {
                            JerreaderSymbol(name: "timer", size: 17)
                        }
                        .lineLimit(1)
                    }
                    .buttonStyle(JerreaderPrimaryButtonStyle())
                    .accessibilityIdentifier("start_chapter_closure")
                    .jerreaderReveal(order: 2)
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

    private var recapCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(companion.recap.title)
                .font(.title3.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
            ForEach(Array(companion.recap.bullets.enumerated()), id: \.offset) { _, bullet in
                Label {
                    Text(bullet)
                } icon: {
                    Image(systemName: "arrow.forward.circle")
                        .foregroundStyle(JerreaderTheme.accent)
                }
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jerreaderPaperCard(padding: 18)
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
                        ForEach(Array(plan.prompts.enumerated()), id: \.element.id) { index, prompt in
                            VStack(alignment: .leading, spacing: 8) {
                                HStack(alignment: .firstTextBaseline, spacing: 10) {
                                    // The step number: a 24pt accent disc.
                                    Text(verbatim: "\(index + 1)")
                                        .font(.caption.weight(.bold))
                                        .monospacedDigit()
                                        .foregroundStyle(JerreaderTheme.accent)
                                        .frame(width: 24, height: 24)
                                        .background(JerreaderTheme.accentFill, in: Circle())
                                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                                        .accessibilityHidden(true)
                                    Text(prompt.title)
                                        .font(.headline)
                                        .foregroundStyle(JerreaderTheme.accent)
                                }
                                Text(prompt.prompt)
                                    .font(.body)
                                    .fixedSize(horizontal: false, vertical: true)
                                if let term = prompt.searchTerm {
                                    Button {
                                        companion.requestSearch(term)
                                    } label: {
                                        Label {
                                            Text("在书中查找“\(term)”")
                                        } icon: {
                                            JerreaderSymbol(name: "magnifyingglass", size: 14)
                                        }
                                        .lineLimit(1)
                                    }
                                    .buttonStyle(JerreaderTintedButtonStyle(height: 36))
                                    .padding(.top, 2)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .jerreaderPaperCard(padding: 18)
                            .jerreaderReveal(order: index)
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
            HStack(spacing: 8) {
                JerreaderSymbol(name: "SPARKLES", size: 16)
                    .foregroundStyle(JerreaderTheme.accent)
                    .accessibilityHidden(true)
                Text("AI 困惑地图")
                    .font(.title3.weight(.semibold))
            }
            Text(confusion.summary)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            ForEach(confusion.items) { item in
                Rectangle()
                    .fill(JerreaderTheme.line)
                    .frame(height: 0.5)
                    .padding(.vertical, 2)
                    .accessibilityHidden(true)
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(item.displayTerm).font(.body.weight(.medium))
                    Spacer(minLength: 8)
                    Text(item.kind.title)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(JerreaderTheme.accent)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(JerreaderTheme.accentFill, in: Capsule())
                }
                Text(
                    showsBookTitle && !item.sourceBookTitle.isEmpty
                        ? "《\(item.sourceBookTitle)》 · \(item.reason)"
                        : item.reason
                )
                .font(.caption)
                .foregroundStyle(.secondary)
                if !item.sentenceContext.isEmpty {
                    // The original sentence, set off like a quotation.
                    Text(item.sentenceContext)
                        .font(.footnote)
                        .lineLimit(2)
                        .padding(.leading, 10)
                        .overlay(alignment: .leading) {
                            Capsule()
                                .fill(JerreaderTheme.accent.opacity(0.35))
                                .frame(width: 2)
                                .accessibilityHidden(true)
                        }
                }
                if let onSearchTerm {
                    Button {
                        onSearchTerm(item.displayTerm)
                    } label: {
                        JerreaderLabel(title: "在书中查找", icon: "magnifyingglass", size: 14)
                    }
                    .buttonStyle(JerreaderTintedButtonStyle(height: 34))
                }
            }
            Text("只根据重复查词、真实原句和复习结果生成，不调用网络。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .padding(.top, 2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jerreaderPaperCard(padding: 18)
        .accessibilityIdentifier("reading_confusion_map")
    }
}
