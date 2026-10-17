import SwiftData
import SwiftUI

struct ReadingGrowthView: View {
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

    var body: some View {
        let request = ReadingGrowthSnapshot.Request(
            activities: books.filter { selectedBook == nil || $0.id == selectedBook }.map(\.activityData),
            reviews: words.map(\.reviewData), start: start, bookID: selectedBook, calendar: calendar, enabled: enabled)
        let report = prepared?.request == request ? prepared?.value : nil
        LearningPage {
            LearningCard {
                Toggle("记录并显示成长报告", isOn: $enabled).tint(JerreaderTheme.accent).frame(minHeight: 44)
                Text("按本机日期记录。关闭后暂停采集并隐藏报告，既有记录保留。历史只从新版开始累计。")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if enabled {
                LearningCard {
                  Picker("书籍", selection: $selectedBook) {
                    Text("全部书籍").tag(UUID?.none)
                    ForEach(books) { Text($0.title).tag(Optional($0.id)) }
                  }
                    HStack {
                        Button("上一周") { weeksAgo += 1 }.disabled(weeksAgo >= 103)
                        Spacer()
                        Button("下一周") { weeksAgo -= 1 }.disabled(weeksAgo == 0)
                    }
                    Text("\(ReadingActivity.key(start, calendar: calendar)) 起的一周")
                }
                LearningCard {
                    if let report {
                    let seconds = report.seconds
                    let queries = report.queries
                    LabeledContent("阅读天数", value: "\(report.readingDays) / 7")
                    LabeledContent("阅读时长", value: String(format: "%.1f 分钟", seconds / 60))
                    LabeledContent("书内查询", value: "\(queries) 次")
                    LabeledContent("每 10 分钟查询", value: seconds > 0 ? String(format: "%.1f 次", Double(queries) * 600 / seconds) : "暂无阅读时长")
                    LabeledContent("复习正确率", value: report.answerCount == 0 ? "本周未评分" :
                        "\(report.knownAnswers * 100 / report.answerCount)%（\(report.answerCount) 次）")
                    Text("查询包含书内发起的翻译、查词及重试；复习正确率＝选择‘认识’的次数／全部评分。没有评分时不显示 0%。")
                        .font(.caption).foregroundStyle(.secondary)
                    ForEach(report.rows) { row in
                        VStack(alignment: .leading, spacing: 8) {
                            Text("\(row.id) · \(Int(row.value.seconds / 60)) 分钟").font(.caption)
                            GeometryReader { geometry in
                                ZStack(alignment: .leading) {
                                    RoundedRectangle(cornerRadius: 2).fill(JerreaderTheme.accentFill)
                                    RoundedRectangle(cornerRadius: 2).fill(JerreaderTheme.accent)
                                        .frame(width: geometry.size.width * row.value.seconds / report.maxSeconds)
                                }
                            }.frame(height: 4)
                        }.accessibilityElement(children: .combine)
                    }
                    } else { Text("正在汇总阅读记录…") }
                }
            }
        }.navigationTitle("阅读成长报告")
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
