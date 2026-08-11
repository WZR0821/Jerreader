import JerreaderCore
import SwiftUI

/// Computed rather than a stored `let`: a Kotlin object arrives in Swift as a
/// plain class, so it is not `Sendable`, and Swift 6 rejects it as a global
/// constant. Nothing here is mutable, so re-reading `shared` costs nothing.
private var copy: JerreaderCopy { JerreaderCopy.shared }

struct LearningHubView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ObservedObject var translationSettings: TranslationSettingsStore
    @State private var section: LearningSection = .translate
    @Namespace private var sectionSelection

    init(translationSettings: TranslationSettingsStore) {
        self.translationSettings = translationSettings
#if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("--jerreader-vocabulary-ui-test") {
            _section = State(initialValue: .vocabulary)
        } else if arguments.contains("--jerreader-review-ui-test") {
            _section = State(initialValue: .review)
        } else if arguments.contains("--jerreader-standalone-translator-ui-test") {
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
            .navigationTitle(copy.learningTitle)
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
        .accessibilityLabel("切换翻译、复习、生词本或历史")
    }

    @ViewBuilder
    private var sectionContent: some View {
        switch section {
        case .translate:
            TranslateToolView(translationSettings: translationSettings)
        case .review:
            VocabularyReviewView()
        case .vocabulary:
            VocabularyView()
        case .history:
            LookupHistoryView()
        }
    }
}

private enum LearningSection: String, CaseIterable, Identifiable {
    case translate
    case review
    case vocabulary
    case history

    var id: Self { self }

    var title: String {
        switch self {
        case .translate: return copy.learningTranslateSection
        case .review: return copy.learningReviewSection
        case .vocabulary: return copy.learningVocabularySection
        case .history: return copy.learningHistorySection
        }
    }

    var systemImage: String {
        switch self {
        case .translate: return "character.bubble"
        case .review: return "rectangle.stack.badge.play"
        case .vocabulary: return "bookmark.fill"
        case .history: return "clock.arrow.circlepath"
        }
    }
}
