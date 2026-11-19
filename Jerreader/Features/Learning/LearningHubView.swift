import SwiftUI
import UIKit
import SwiftData

struct LearningHubView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var translationSettings: TranslationSettingsStore
    @State private var section: LearningSection = .practice
    @State private var translationDraft: TranslationToolDraft

    init(translationSettings: TranslationSettingsStore) {
        self.translationSettings = translationSettings
        _translationDraft = State(initialValue: TranslationToolDraft(settings: translationSettings))
#if DEBUG
        if ProcessInfo.processInfo.arguments
            .contains("--jerreader-standalone-translator-ui-test")
        {
            _section = State(initialValue: .translate)
        }
#endif
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                sectionPicker
                    .padding(.horizontal, JerreaderTheme.pagePadding)
                    .padding(.top, 8)
                    .padding(.bottom, 12)
                    .frame(maxWidth: 680)
                    .frame(maxWidth: .infinity)
                    .overlay(alignment: .bottom) {
                        Rectangle()
                            .fill(JerreaderTheme.line)
                            .frame(height: 0.5)
                    }

                ZStack(alignment: .top) {
                    sectionContent
                        .id(section)
                        .transition(sectionTransition)
                }
                .animation(
                    JerreaderMotion.choose(JerreaderMotion.stateChange, reduceMotion: reduceMotion),
                    value: section
                )
            }
            .background(JerreaderCanvasBackground())
            .navigationTitle("学习")
            .navigationBarTitleDisplayMode(.inline)
        }
        .tint(JerreaderTheme.accent)
    }

    /// The new section fades in and settles from a hair smaller; with Reduce
    /// Motion it only cross-fades.
    private var sectionTransition: AnyTransition {
        reduceMotion
            ? AnyTransition.opacity
            : AnyTransition.opacity.combined(with: .scale(scale: 0.988, anchor: .top))
    }

    /// The picker sets the section through here, so the keyboard is
    /// dismissed before the section changes.
    private var sectionSelection: Binding<LearningSection> {
        Binding(
            get: { section },
            set: { candidate in
                guard candidate != section else { return }
                // Dismiss before the animated replacement can retain the old responder.
                UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                section = candidate
            }
        )
    }

    /// The pill picker slides its capsule with the selection curve, ticks and
    /// bounces the new icon; it already gives the selection haptic.
    private var sectionPicker: some View {
        JerreaderPillPicker(
            options: LearningSection.allCases.map { candidate in
                JerreaderPillPicker<LearningSection>.Option(
                    value: candidate,
                    title: candidate.title,
                    systemImage: candidate.systemImage
                )
            },
            selection: sectionSelection,
            layout: .stacked,
            minHeight: 44
        )
        // The bar stays one height: its labels stop growing at xLarge.
        .dynamicTypeSize(...DynamicTypeSize.xLarge)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("切换翻译、生词本、练习与工具或历史")
    }

    @ViewBuilder
    private var sectionContent: some View {
        switch section {
        case .practice:
            LearningPracticeView(translationSettings: translationSettings)
        case .translate:
            TranslateToolView(translationSettings: translationSettings, draft: $translationDraft)
        case .vocabulary:
            VocabularyView()
        case .history:
            LookupHistoryView()
        }
    }
}

private enum LearningSection: String, CaseIterable, Identifiable {
    case translate
    case vocabulary
    case practice
    case history

    var id: Self { self }

    var title: String {
        switch self {
        case .practice: return "练习与工具"
        case .translate: return "翻译"
        case .vocabulary: return "生词本"
        case .history: return "历史"
        }
    }

    var systemImage: String {
        switch self {
        case .practice: return "rectangle.on.rectangle"
        case .translate: return "character.bubble"
        case .vocabulary: return "bookmark.fill"
        case .history: return "clock.arrow.circlepath"
        }
    }
}


/// Uses the existing collections so practice and reading never diverge into separate data.
private struct LearningPracticeView: View {
    @ObservedObject var translationSettings: TranslationSettingsStore
    @Query(filter: #Predicate<WordLookupRecord> { $0.isFavorite }) private var words: [WordLookupRecord]
    @Query private var sentences: [TranslationFavoriteRecord]
    /// Every lookup, not just favourites: repeated lookups are what the map reads.
    @Query private var lookups: [WordLookupRecord]
    @State private var confusion: ReadingConfusionMap?
    @State private var reviewProgress = LearningDailyReviewProgress()

    var body: some View {
        let reviewInputs = words.map { WordReviewSnapshot.Input(id: $0.id, data: $0.reviewData) }
        LearningPage {
            practiceHero
            if let confusion {
                LearningConfusionMapCard(confusion: confusion)
            }
            LearningSectionHeading(title: "练习与积累")
            VStack(spacing: 0) {
                NavigationLink { SentencePracticeView() } label: {
                    tool("原句练习卡", detail: "用收藏的原句回忆词语与表达", icon: "text.quote", tint: JerreaderTheme.accent)
                }
                toolDivider
                NavigationLink { GrammarNotebookView(translationSettings: translationSettings) } label: {
                    tool("语法知识库", detail: "整理解释、标签与原书出处", icon: "note.text", tint: JerreaderTheme.vermilion)
                }
                toolDivider
                NavigationLink { ReadingGrowthView() } label: {
                    tool("阅读成长报告", detail: "回顾本周阅读与复习记录", icon: "chart.bar", tint: JerreaderTheme.jade)
                }
            }
            .buttonStyle(LearningToolRowStyle())
            .clipShape(RoundedRectangle(cornerRadius: JerreaderRadius.card, style: .continuous))
            .jerreaderPaperCard(padding: 0, radius: JerreaderRadius.card)
        }
        // Rebuilt each time the page appears, so a review just finished shows up.
        .task {
            confusion = ReadingConfusionMapPolicy.build(ReadingCompanionSignals.from(lookups))
        }
        // The ring reads the same schedules as the review page, decoded away
        // from the main actor; it runs again when a schedule changes.
        .task(id: reviewInputs) {
            let now = Date()
            let calendar = Calendar.current
            let worker = Task.detached(priority: .utility) {
                let snapshot = try WordReviewSnapshot(inputs: reviewInputs, checkpoint: { try Task.checkCancellation() })
                return LearningDailyReviewProgress(snapshot: snapshot, now: now, calendar: calendar)
            }
            let result = try? await withTaskCancellationHandler {
                try await worker.value
            } onCancel: { worker.cancel() }
            guard !Task.isCancelled, let result else { return }
            reviewProgress = result
        }
    }

    /// 今日练习: the ink hero card with today's ring, the two collections and
    /// the white 开始复习 capsule.
    private var practiceHero: some View {
        VStack(alignment: .leading, spacing: JerreaderSpacing.md) {
            VStack(alignment: .leading, spacing: 4) {
                Text("今日练习")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(Color.white)
                    .lineLimit(2)
                Text("从阅读中积累，每天巩固一点。")
                    .font(.subheadline)
                    .foregroundStyle(Color.white.opacity(0.75))
                    .fixedSize(horizontal: false, vertical: true)
            }

            ViewThatFits(in: .horizontal) {
                HStack(alignment: .center, spacing: JerreaderSpacing.lg) {
                    reviewRing
                    collectionCounts
                }
                VStack(alignment: .leading, spacing: JerreaderSpacing.md) {
                    reviewRing
                    collectionCounts
                }
            }

            NavigationLink { WordReviewView() } label: {
                JerreaderLabel(title: "开始复习", icon: "rectangle.on.rectangle")
            }
            .buttonStyle(LearningHeroButtonStyle())

            Text("先回忆，再看释义；每组最多 10 个词。")
                .font(.footnote)
                .foregroundStyle(Color.white.opacity(0.7))
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(JerreaderSpacing.lg)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background { JerreaderHeroBackground() }
    }

    private var reviewRing: some View {
        VStack(spacing: 6) {
            LearningReviewRing(progress: reviewProgress)
            Text("今日已复习")
                .font(.caption)
                .foregroundStyle(Color.white.opacity(0.72))
                .lineLimit(1)
                .accessibilityHidden(true)
        }
    }

    private var collectionCounts: some View {
        HStack(alignment: .top, spacing: JerreaderSpacing.lg) {
            count("收藏词语", value: words.count, icon: "bookmark.fill")
            count("原句收藏", value: sentences.count, icon: "text.quote")
        }
    }

    private func count(_ label: String, value: Int, icon: String) -> some View {
        JerreaderStatValue(
            value: value,
            label: label,
            systemImage: icon,
            tint: Color.white.opacity(0.8),
            foreground: .white,
            secondary: Color.white.opacity(0.72),
            size: 28
        )
        .frame(minWidth: 64, alignment: .leading)
    }

    private var toolDivider: some View {
        Rectangle()
            .fill(JerreaderTheme.line)
            .frame(height: 0.5)
            .padding(.leading, 68)
            .accessibilityHidden(true)
    }

    private func tool(_ title: String, detail: String, icon: String, tint: Color) -> some View {
        HStack(spacing: JerreaderSpacing.sm) {
            JerreaderIconTile(systemImage: icon, tint: tint, size: 40)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.headline).foregroundStyle(.primary)
                Text(detail).font(.footnote).foregroundStyle(.secondary)
            }.frame(maxWidth: .infinity, alignment: .leading)
            JerreaderSymbol(name: "chevron.forward", size: 13).foregroundStyle(.tertiary)
        }
        .padding(.horizontal, JerreaderSpacing.md)
        .padding(.vertical, JerreaderSpacing.sm)
        .frame(minHeight: 68)
        .contentShape(Rectangle())
    }
}

/// A tool row inside its card: a muted wash while pressed, no scaling.
private struct LearningToolRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? JerreaderTheme.mutedSurface.opacity(0.7) : Color.clear)
            .animation(JerreaderMotion.quick, value: configuration.isPressed)
    }
}
