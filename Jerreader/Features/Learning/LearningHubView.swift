import SwiftUI
import UIKit
import SwiftData

struct LearningHubView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var translationSettings: TranslationSettingsStore
    @State private var section: LearningSection = .practice
    @State private var translationDraft: TranslationToolDraft
    @Namespace private var sectionSelection

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
                .background(JerreaderTheme.canvas)
                .overlay(alignment: .bottom) {
                    Rectangle()
                        .fill(JerreaderTheme.line)
                        .frame(height: 0.5)
                }

                ZStack(alignment: .top) {
                    sectionContent
                        .id(section)
                        .transition(
                            .opacity.combined(with: .scale(scale: 0.988, anchor: .top))
                        )
                }
                .animation(
                    reduceMotion ? nil : JerreaderMotion.stateChange,
                    value: section
                )
            }
            .background(JerreaderCanvasBackground())
            .navigationTitle("学习")
            .navigationBarTitleDisplayMode(.inline)
        }
        .tint(JerreaderTheme.accent)
        .sensoryFeedback(.selection, trigger: section)
    }

    private var sectionPicker: some View {
        HStack(spacing: 4) {
            ForEach(LearningSection.allCases) { candidate in
                let isSelected = section == candidate

                Button {
                    guard section != candidate else { return }
                    // Dismiss before the animated replacement can retain the old responder.
                    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
                    withAnimation(reduceMotion ? nil : JerreaderMotion.stateChange) {
                        section = candidate
                    }
                } label: {
                    VStack(spacing: 3) {
                        JerreaderSymbol(name: candidate.systemImage, size: 16)
                        Text(candidate.title)
                    }
                        .font(.system(size: 13, weight: .semibold))
                        .lineLimit(1)
                        .foregroundStyle(isSelected ? JerreaderTheme.onPrimaryAction : JerreaderTheme.accent)
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 48)
                        .background {
                            if isSelected {
                                RoundedRectangle(cornerRadius: 13, style: .continuous)
                                    .fill(JerreaderTheme.primaryAction)
                                    .matchedGeometryEffect(
                                        id: "learning-section",
                                        in: sectionSelection
                                    )
                            }
                        }
                        .contentShape(RoundedRectangle(cornerRadius: 13))
                }
                .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.985))
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
        .padding(4)
        .background(
            JerreaderTheme.paper.opacity(0.96),
            in: RoundedRectangle(cornerRadius: 15, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 15, style: .continuous)
                .stroke(JerreaderTheme.line, lineWidth: 0.75)
        }
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

    var body: some View {
        LearningPage {
            LearningCard {
                Text("今日练习").font(.title3.weight(.semibold))
                LearningHint(text: "从阅读中积累，每天巩固一点。")
                HStack(spacing: 12) {
                    count("收藏词语", value: words.count)
                    count("原句收藏", value: sentences.count)
                }
                NavigationLink { WordReviewView() } label: {
                    JerreaderLabel(title: "开始复习", icon: "rectangle.on.rectangle")
                        .frame(maxWidth: .infinity)
                }.buttonStyle(LearningActionStyle(kind: .filled))
                Text("先回忆，再看释义；每组最多 10 个词。")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let confusion {
                ReadingConfusionMapCard(confusion: confusion, showsBookTitle: true)
            }
            Text("练习与积累").font(.headline)
            VStack(spacing: 0) {
                NavigationLink { SentencePracticeView() } label: {
                    tool("原句练习卡", detail: "用收藏的原句回忆词语与表达", icon: "text.quote")
                }
                Divider().padding(.leading, 60)
                NavigationLink { GrammarNotebookView(translationSettings: translationSettings) } label: {
                    tool("语法知识库", detail: "整理解释、标签与原书出处", icon: "note.text")
                }
                Divider().padding(.leading, 60)
                NavigationLink { ReadingGrowthView() } label: {
                    tool("阅读成长报告", detail: "回顾本周阅读与复习记录", icon: "chart.bar")
                }
            }.buttonStyle(.plain).jerreaderPaperCard(padding: 0)
        }
        // Rebuilt each time the page appears, so a review just finished shows up.
        .task {
            confusion = ReadingConfusionMapPolicy.build(ReadingCompanionSignals.from(lookups))
        }
    }

    private func count(_ label: String, value: Int) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(value)").font(.title2.bold())
            Text(label).font(.caption).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading)
    }

    private func tool(_ title: String, detail: String, icon: String, inset: Bool = true) -> some View {
        HStack(spacing: 12) {
            JerreaderSymbol(name: icon, size: 21).foregroundStyle(JerreaderTheme.accent)
                .frame(width: 36, height: 36)
                .background(JerreaderTheme.accentFill, in: RoundedRectangle(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.headline).foregroundStyle(.primary)
                Text(detail).font(.caption).foregroundStyle(.secondary)
            }.frame(maxWidth: .infinity, alignment: .leading)
            JerreaderSymbol(name: "chevron.forward", size: 13).foregroundStyle(.secondary)
        }.padding(inset ? 16 : 0).frame(minHeight: 64).contentShape(Rectangle())
    }
}
