import SwiftData
import SwiftUI

struct ReadingGrowthView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Query private var books: [BookRecord]
    @Query private var words: [WordLookupRecord]
    @AppStorage(ReadingActivity.enabledKey) private var enabled = true
    @State private var selectedBook: UUID?
    @State private var weeksAgo = 0
    @State private var prepared: (request: ReadingGrowthSnapshot.Request, value: ReadingGrowthSnapshot)?

    private var calendar: Calendar { var value = Calendar(identifier: .iso8601); value.timeZone = .current; return value }
    private var start: Date {
        calendar.date(byAdding: .weekOfYear, value: -weeksAgo,
            to: calendar.dateInterval(of: .weekOfYear, for: Date())!.start)!
    }

    private var stateAnimation: Animation {
        JerreaderMotion.choose(JerreaderMotion.stateChange, reduceMotion: reduceMotion)
    }

    var body: some View {
        let request = ReadingGrowthSnapshot.Request(
            activities: books.filter { selectedBook == nil || $0.id == selectedBook }.map(\.activityData),
            reviews: words.map(\.reviewData), start: start, bookID: selectedBook, calendar: calendar, enabled: enabled)
        let report = prepared?.request == request ? prepared?.value : nil
        LearningPage {
            LearningCard {
                Toggle("记录并显示成长报告", isOn: $enabled)
                    .font(.body.weight(.semibold))
                    .tint(JerreaderTheme.accent)
                    .frame(minHeight: 44)
                Text("按本机日期记录。关闭后暂停采集并隐藏报告，既有记录保留。历史只从新版开始累计。")
                    .font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if enabled {
                LearningCard {
                    HStack(spacing: JerreaderSpacing.sm) {
                        JerreaderIconTile(systemImage: "books.vertical", tint: JerreaderTheme.jade, size: 32)
                        Text("书籍")
                            .font(.subheadline.weight(.semibold))
                            .accessibilityHidden(true)
                        Spacer(minLength: JerreaderSpacing.xs)
                        Picker("书籍", selection: $selectedBook) {
                            Text("全部书籍").tag(UUID?.none)
                            ForEach(books) { Text($0.title).tag(Optional($0.id)) }
                        }
                        .tint(JerreaderTheme.accent)
                    }
                    Rectangle()
                        .fill(JerreaderTheme.line)
                        .frame(height: 0.5)
                        .accessibilityHidden(true)
                    HStack(spacing: JerreaderSpacing.xs) {
                        Button { weeksAgo += 1 } label: {
                            HStack(spacing: 4) {
                                JerreaderSymbol(name: "chevron.left", size: 13)
                                Text("上一周")
                            }
                        }
                        .disabled(weeksAgo >= 103)
                        Spacer(minLength: JerreaderSpacing.xs)
                        Button { weeksAgo -= 1 } label: {
                            HStack(spacing: 4) {
                                Text("下一周")
                                JerreaderSymbol(name: "chevron.right", size: 13)
                            }
                        }
                        .disabled(weeksAgo == 0)
                    }
                    .buttonStyle(LearningActionStyle(kind: .tinted))
                    Text("\(ReadingActivity.key(start, calendar: calendar)) 起的一周")
                        .font(.headline)
                        .monospacedDigit()
                        .contentTransition(.numericText())
                        .animation(stateAnimation, value: weeksAgo)
                }
                .transition(.opacity)
                LearningCard {
                    if let report {
                        ReadingGrowthReport(report: report)
                    } else {
                        Text("正在汇总阅读记录…")
                            .foregroundStyle(.secondary)
                    }
                }
                .transition(.opacity)
            }
        }
        // Scoped to the report being switched on or off.
        .animation(stateAnimation, value: enabled)
        .navigationTitle("阅读成长报告")
        .task(id: request) {
            guard request.enabled else { return }
            let worker = Task.detached(priority: .userInitiated) {
                try ReadingGrowthSnapshot(request: request, checkpoint: { try Task.checkCancellation() })
            }
            let result = try? await withTaskCancellationHandler {
                try await worker.value
            } onCancel: { worker.cancel() }
            guard !Task.isCancelled, let result else { return }
            prepared = (request, result)
        }
    }
}

/// A week's figures and its daily bars. When a week's report appears its
/// numbers roll up from zero and its bars grow from zero, each bar 0.03 s
/// after the one above; with Reduce Motion they appear at their values.
private struct ReadingGrowthReport: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var counted = false

    let report: ReadingGrowthSnapshot

    var body: some View {
        // Zero until the numbers have rolled in; the real figures after.
        let factor = counted || reduceMotion ? 1 : 0
        let seconds = report.seconds
        let queries = report.queries * factor
        let columns = Array(
            repeating: GridItem(.flexible(), spacing: JerreaderSpacing.sm, alignment: .topLeading),
            count: dynamicTypeSize.isAccessibilitySize ? 1 : 2
        )
        VStack(alignment: .leading, spacing: JerreaderSpacing.sm) {
            LazyVGrid(columns: columns, alignment: .leading, spacing: JerreaderSpacing.sm) {
                statTile("阅读天数", value: "\(report.readingDays * factor) / 7",
                    icon: "book.closed", tint: JerreaderTheme.gold)
                statTile("阅读时长", value: String(format: "%.1f 分钟", seconds * Double(factor) / 60),
                    icon: "clock", tint: JerreaderTheme.accent)
                statTile("书内查询", value: "\(queries) 次",
                    icon: "magnifyingglass", tint: JerreaderTheme.vermilion)
                statTile("每 10 分钟查询",
                    value: seconds > 0 ? String(format: "%.1f 次", Double(queries) * 600 / seconds) : "暂无阅读时长",
                    icon: "chart.line.uptrend.xyaxis", tint: JerreaderTheme.vermilion)
            }
            statTile("复习正确率", value: report.answerCount == 0 ? "本周未评分" :
                "\(report.knownAnswers * factor * 100 / report.answerCount)%（\(report.answerCount) 次）",
                icon: "checkmark.circle", tint: JerreaderTheme.jade)
            Text("查询包含书内发起的翻译、查词及重试；复习正确率＝选择‘认识’的次数／全部评分。没有评分时不显示 0%。")
                .font(.caption).foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Rectangle()
                .fill(JerreaderTheme.line)
                .frame(height: 0.5)
                .padding(.vertical, JerreaderSpacing.xxs)
                .accessibilityHidden(true)
            ForEach(Array(report.rows.enumerated()), id: \.element.id) { index, row in
                VStack(alignment: .leading, spacing: 6) {
                    Text("\(row.id) · \(Int(row.value.seconds / 60)) 分钟")
                        .font(.caption)
                        .monospacedDigit()
                    ReadingGrowthBar(
                        fraction: report.maxSeconds > 0 ? row.value.seconds / report.maxSeconds : 0,
                        order: index
                    )
                }
                .accessibilityElement(children: .combine)
            }
        }
        .onAppear {
            guard !counted else { return }
            if reduceMotion {
                counted = true
            } else {
                withAnimation(JerreaderMotion.stateChange.delay(0.05)) { counted = true }
            }
        }
    }

    /// A figure on a tinted tile: icon and label over a rounded number that
    /// rolls when it changes.
    private func statTile(_ title: String, value: String, icon: String, tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                JerreaderSymbol(name: icon, size: 14)
                    .foregroundStyle(tint)
                Text(title)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            Text(value)
                .font(.system(.title3, design: .rounded, weight: .bold))
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .contentTransition(.numericText())
                .animation(JerreaderMotion.choose(JerreaderMotion.stateChange, reduceMotion: reduceMotion), value: value)
        }
        .padding(JerreaderSpacing.sm)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tint.opacity(0.08), in: RoundedRectangle(cornerRadius: JerreaderRadius.control, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// One day's bar: grows from zero when it appears, `order` × 0.03 s after
/// the first (at most 0.3 s), and slides to a new length when it changes.
private struct ReadingGrowthBar: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var shown: Double = 0

    let fraction: Double
    let order: Int

    private var target: Double { fraction.isFinite ? min(max(fraction, 0), 1) : 0 }

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                Capsule().fill(JerreaderTheme.accentFill)
                Capsule()
                    .fill(JerreaderTheme.accent)
                    .frame(width: max(proxy.size.width * CGFloat(shown), shown > 0 ? CGFloat(8) : CGFloat(0)))
            }
        }
        .frame(height: 8)
        .onAppear { grow(delay: min(Double(order) * 0.03, 0.3)) }
        .onChange(of: target) { _, _ in grow(delay: 0) }
        .accessibilityHidden(true)
    }

    private func grow(delay: Double) {
        withAnimation(reduceMotion ? JerreaderMotion.reduced : JerreaderMotion.stateChange.delay(delay)) {
            shown = target
        }
    }
}
