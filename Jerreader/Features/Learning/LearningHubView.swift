import SwiftUI

struct LearningHubView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var translationSettings: TranslationSettingsStore
    @State private var section: LearningSection = .lookup
    @Namespace private var sectionSelection

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                sectionPicker
                .padding(.horizontal, JerreaderTheme.pagePadding)
                .padding(.top, 8)
                .padding(.bottom, 12)
                .frame(maxWidth: 680)
                .frame(maxWidth: .infinity)
                .background(.ultraThinMaterial)

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
                        .foregroundStyle(isSelected ? Color.white : JerreaderTheme.accent)
                        .frame(maxWidth: .infinity)
                        .frame(height: 42)
                        .background {
                            if isSelected {
                                RoundedRectangle(cornerRadius: 13, style: .continuous)
                                    .fill(JerreaderTheme.primaryAction)
                                    .matchedGeometryEffect(
                                        id: "learning-section",
                                        in: sectionSelection
                                    )
                                    .shadow(
                                        color: JerreaderTheme.accent.opacity(0.18),
                                        radius: 7,
                                        y: 3
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
            JerreaderTheme.paper.opacity(0.94),
            in: RoundedRectangle(cornerRadius: 17, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 17, style: .continuous)
                .stroke(JerreaderTheme.line, lineWidth: 0.75)
        }
        .shadow(color: JerreaderTheme.shadow.opacity(0.6), radius: 10, y: 4)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("切换查词、生词本或历史")
    }

    @ViewBuilder
    private var sectionContent: some View {
        switch section {
        case .lookup:
            LookupView(translationSettings: translationSettings)
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
    case lookup
    case translate
    case vocabulary
    case history

    var id: Self { self }

    var title: String {
        switch self {
        case .lookup: return "查词"
        case .translate: return "翻译"
        case .vocabulary: return "生词本"
        case .history: return "历史"
        }
    }

    var systemImage: String {
        switch self {
        case .lookup: return "magnifyingglass"
        case .translate: return "character.bubble"
        case .vocabulary: return "bookmark.fill"
        case .history: return "clock.arrow.circlepath"
        }
    }
}
