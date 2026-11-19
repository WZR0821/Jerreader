import SwiftUI

// 2.0 学习界面的视图组件，按 docs/DESIGN_2.0.md 实现。

// MARK: - First appearance of list rows

/// The staggered first appearance of list rows (DESIGN_2.0 §5). Only the
/// first rows of a page stagger, and only in the moment after the page
/// appears: scrolling back, or a list of a thousand records, never replays it.
enum LearningStagger {
    /// Rows after these appear at once.
    static let rowLimit = 8
    /// How long after a page appears its rows may still stagger in.
    static let window: Duration = .milliseconds(700)

    /// Where `item` stands among the first rows, or nil past them.
    static func order<Item: Identifiable>(of item: Item, in items: [Item]) -> Int? {
        items.prefix(rowLimit).firstIndex { $0.id == item.id }
    }
}

private struct LearningStaggerModifier: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown = false

    let order: Int?
    let active: Bool

    func body(content: Content) -> some View {
        let waiting = active && order != nil && !shown && !reduceMotion
        content
            .opacity(waiting ? 0 : 1)
            .offset(y: waiting ? 12 : 0)
            .scaleEffect(waiting ? 0.98 : 1)
            .onAppear {
                guard !shown else { return }
                guard active, let order, !reduceMotion else {
                    shown = true
                    return
                }
                withAnimation(JerreaderMotion.reveal.delay(JerreaderMotion.revealDelay(order))) {
                    shown = true
                }
            }
    }
}

extension View {
    /// Fades and lifts a row in while `active`, by its `order`; see `LearningStagger`.
    func learningStagger(order: Int?, active: Bool) -> some View {
        modifier(LearningStaggerModifier(order: order, active: active))
    }

    /// A record in a learning list as a paper card of its own: 生词本 and 历史.
    func learningListCard(order: Int? = nil, active: Bool = false) -> some View {
        padding(JerreaderSpacing.md)
            .frame(maxWidth: .infinity, alignment: .leading)
            .jerreaderPaperCard(padding: 0, radius: JerreaderRadius.card)
            .learningStagger(order: order, active: active)
            .listRowInsets(EdgeInsets(top: 6, leading: JerreaderTheme.pagePadding, bottom: 6, trailing: JerreaderTheme.pagePadding))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }
}

// MARK: - Small pieces

/// A small tinted tag: a language, a part of speech, a kind of difficulty.
struct LearningChip: View {
    let text: String
    var tint: Color = JerreaderTheme.accent

    var body: some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .foregroundStyle(tint)
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(tint.opacity(0.12), in: Capsule())
    }
}

/// The 8pt dot in front of a history record, coloured by its kind.
struct LearningKindDot: View {
    let tint: Color

    var body: some View {
        Circle()
            .fill(tint)
            .frame(width: 8, height: 8)
            .accessibilityHidden(true)
    }
}

/// A section title on a learning page: 20 Bold (DESIGN_2.0 §3).
struct LearningSectionHeading: View {
    let title: String
    var detail: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.title3.weight(.bold))
            if let detail {
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, JerreaderSpacing.xs)
    }
}

/// A heading as a row of a learning List (生词本, 历史). A row rather than a
/// section header, so it scrolls with the cards instead of pinning over them.
struct LearningListHeading: View {
    let title: String
    var detail: String?

    var body: some View {
        LearningSectionHeading(title: title, detail: detail)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            .listRowInsets(EdgeInsets(top: 8, leading: JerreaderTheme.pagePadding, bottom: 2, trailing: JerreaderTheme.pagePadding))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }
}

/// A day of 历史 as an eyebrow row: 今天, 昨天, 11月3日.
struct LearningDayHeading: View {
    let title: String

    var body: some View {
        JerreaderEyebrow(text: title)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
            .listRowInsets(EdgeInsets(top: 14, leading: JerreaderTheme.pagePadding + 4, bottom: 2, trailing: JerreaderTheme.pagePadding))
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
    }
}

// MARK: - 今日练习

/// 今日练习's ring: the words reviewed today out of today's goal, which is
/// those words plus the ones still due. Worked out away from the main actor
/// from the snapshot the review page uses.
struct LearningDailyReviewProgress: Equatable, Sendable {
    var reviewed = 0
    var goal = 0

    var fraction: Double {
        goal > 0 ? min(Double(reviewed) / Double(goal), 1) : 0
    }

    init() {}

    init(snapshot: WordReviewSnapshot, now: Date, calendar: Calendar) {
        let start = calendar.startOfDay(for: now)
        var reviewed = 0
        var goal = 0
        for state in snapshot.states.values {
            // Answers are appended in time order, so the last one is the latest.
            let reviewedToday = (state.answers.last?.at ?? .distantPast) >= start
            let due = !state.paused && state.dueAt <= now
            if reviewedToday { reviewed += 1 }
            if reviewedToday || due { goal += 1 }
        }
        self.reviewed = reviewed
        self.goal = goal
    }
}

/// The white ring on the 今日练习 hero card, with 已复习 / 目标 inside.
struct LearningReviewRing: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .title2) private var diameter: CGFloat = 76

    let progress: LearningDailyReviewProgress

    var body: some View {
        let side = min(diameter, 132)
        ZStack {
            JerreaderProgressRing(
                progress: progress.fraction,
                tint: .white,
                track: Color.white.opacity(0.22),
                lineWidth: 6
            )
            VStack(spacing: 0) {
                Text(progress.reviewed, format: .number)
                    .font(.system(.title2, design: .rounded, weight: .bold))
                    .monospacedDigit()
                    .foregroundStyle(Color.white)
                    .contentTransition(.numericText(value: Double(progress.reviewed)))
                Text("/ \(progress.goal)")
                    .font(.system(.caption, design: .rounded, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(Color.white.opacity(0.72))
                    .contentTransition(.numericText(value: Double(progress.goal)))
            }
            .lineLimit(1)
            .minimumScaleFactor(0.5)
            .padding(12)
            .animation(JerreaderMotion.choose(JerreaderMotion.stateChange, reduceMotion: reduceMotion), value: progress)
        }
        .frame(width: side, height: side)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("今日已复习 \(progress.reviewed) / \(progress.goal) 个")
    }
}

/// The white capsule on a hero card: accent-deep text on white, pressed with
/// the shared press and release curves.
struct LearningHeroButtonStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var height: CGFloat = 44

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(JerreaderTheme.accentDeep)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .frame(maxWidth: .infinity, minHeight: height)
            .padding(.horizontal, JerreaderSpacing.lg)
            .background(Color.white, in: Capsule())
            .contentShape(Capsule())
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.92 : 1)
            .animation(
                reduceMotion ? nil : (configuration.isPressed ? JerreaderMotion.press : JerreaderMotion.release),
                value: configuration.isPressed
            )
    }
}

// MARK: - AI 困惑地图

/// The confusion map on the practice page, naming each item's book. The
/// reader shows the same map with its own card.
struct LearningConfusionMapCard: View {
    let confusion: ReadingConfusionMap

    var body: some View {
        VStack(alignment: .leading, spacing: JerreaderSpacing.sm) {
            HStack(alignment: .top, spacing: JerreaderSpacing.sm) {
                JerreaderIconTile(systemImage: "questionmark.circle", tint: JerreaderTheme.vermilion)
                VStack(alignment: .leading, spacing: 4) {
                    Text("AI 困惑地图")
                        .font(.headline)
                    Text(confusion.summary)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            ForEach(confusion.items) { item in
                Rectangle()
                    .fill(JerreaderTheme.line)
                    .frame(height: 0.5)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 6) {
                    HStack(alignment: .firstTextBaseline, spacing: JerreaderSpacing.xs) {
                        Text(item.displayTerm)
                            .font(.body.weight(.semibold))
                        Spacer(minLength: JerreaderSpacing.xs)
                        LearningChip(text: item.kind.title, tint: tint(for: item.kind))
                    }
                    Text(
                        item.sourceBookTitle.isEmpty
                            ? item.reason
                            : "《\(item.sourceBookTitle)》 · \(item.reason)"
                    )
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    if !item.sentenceContext.isEmpty {
                        Text(item.sentenceContext)
                            .font(.footnote)
                            .lineLimit(2)
                            .padding(.leading, 10)
                            .overlay(alignment: .leading) {
                                Capsule()
                                    .fill(tint(for: item.kind).opacity(0.45))
                                    .frame(width: 2)
                                    .accessibilityHidden(true)
                            }
                    }
                }
            }

            Text("只根据重复查词、真实原句和复习结果生成，不调用网络。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .jerreaderPaperCard(padding: JerreaderSpacing.md, radius: JerreaderRadius.card)
        .accessibilityIdentifier("reading_confusion_map")
    }

    private func tint(for kind: ReadingConfusionKind) -> Color {
        switch kind {
        case .grammar: return JerreaderTheme.vermilion
        case .recall: return JerreaderTheme.gold
        default: return JerreaderTheme.accent
        }
    }
}
