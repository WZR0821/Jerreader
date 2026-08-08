import SwiftUI

struct LearningHubView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var translationSettings: TranslationSettingsStore
    @State private var section: LearningSection = .translate
    @Namespace private var sectionSelection

    init(translationSettings: TranslationSettingsStore) {
        self.translationSettings = translationSettings
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
            .navigationBarTitleDisplayMode(.large)
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
                    withAnimation(reduceMotion ? nil : JerreaderMotion.stateChange) {
                        section = candidate
                    }
                } label: {
                    Label(candidate.title, systemImage: candidate.systemImage)
                        .labelStyle(.titleAndIcon)
                        .font(.footnote.weight(.semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.85)
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
        .accessibilityLabel("切换翻译、生词本或历史")
    }

    @ViewBuilder
    private var sectionContent: some View {
        switch section {
        case .translate:
            TranslateToolView(translationSettings: translationSettings)
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
    case history

    var id: Self { self }

    var title: String {
        switch self {
        case .translate: return "翻译"
        case .vocabulary: return "生词本"
        case .history: return "历史"
        }
    }

    var systemImage: String {
        switch self {
        case .translate: return "character.bubble"
        case .vocabulary: return "bookmark.fill"
        case .history: return "clock.arrow.circlepath"
        }
    }
}
