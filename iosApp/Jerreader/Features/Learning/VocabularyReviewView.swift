import class JerreaderCore.VocabularyReviewQueueState
import class JerreaderCore.VocabularyReviewRating
import class JerreaderCore.VocabularyReviewScheduler
import SwiftData
import SwiftUI

struct VocabularyReviewView: View {
    @Environment(\.modelContext) private var modelContext
    @Query(
        filter: #Predicate<WordLookupRecord> { $0.isFavorite },
        sort: \WordLookupRecord.lastLookedUpAt,
        order: .reverse
    ) private var records: [WordLookupRecord]

    @State private var revealedRecordID: UUID?
    @State private var reviewedRecordIDs = Set<UUID>()
    @State private var reviewedInSession = 0
    @State private var alert: LearningAlert?
    @State private var now = Date()

    private var dueRecords: [WordLookupRecord] {
        records
            .filter { queueState(for: $0) === VocabularyReviewQueueState.due }
            .sorted {
                let leftDate = $0.nextReviewAt ?? .distantPast
                let rightDate = $1.nextReviewAt ?? .distantPast
                if leftDate != rightDate { return leftDate < rightDate }
                return languagePriority($0) < languagePriority($1)
            }
    }

    private var newRecords: [WordLookupRecord] {
        records
            .filter { queueState(for: $0) === VocabularyReviewQueueState.unseen }
            .sorted {
                let leftPriority = languagePriority($0)
                let rightPriority = languagePriority($1)
                if leftPriority != rightPriority { return leftPriority < rightPriority }
                return $0.lastLookedUpAt > $1.lastLookedUpAt
            }
    }

    private var queue: [WordLookupRecord] {
        let dueLimit = Int(VocabularyReviewScheduler.shared.DAILY_DUE_LIMIT)
        let newLimit = Int(VocabularyReviewScheduler.shared.DAILY_NEW_LIMIT)
        return (Array(dueRecords.prefix(dueLimit)) + Array(newRecords.prefix(newLimit)))
            .filter { !reviewedRecordIDs.contains($0.id) }
    }

    var body: some View {
        ScrollView {
            LazyVStack(spacing: 16) {
                dashboard

                if let record = queue.first {
                    reviewCard(record)
                } else {
                    JerreaderEmptyState(
                        title: records.isEmpty ? "先收藏一些日语词" : "今天的复习完成了",
                        message: records.isEmpty
                            ? "在阅读或词语翻译中加入生词本，词条就会进入每日复习。"
                            : "到期词和今天的新词都已处理。继续阅读，遇到新词再回来。",
                        systemImage: records.isEmpty
                            ? "bookmark"
                            : "checkmark.seal.fill"
                    )
                    .jerreaderPaperCard(padding: 24)
                }
            }
            .padding(.horizontal, JerreaderTheme.pagePadding)
            .padding(.vertical, 16)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(JerreaderCanvasBackground())
        .alert(item: $alert) { alert in
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                dismissButton: .default(Text("知道了"))
            )
        }
        .onReceive(
            Timer.publish(every: 60, on: .main, in: .common).autoconnect()
        ) { date in
            now = date
        }
        .task {
            seedReviewUITestRecordIfNeeded()
        }
    }

    private var dashboard: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 5) {
                Text("今日学习")
                    .font(.title3.weight(.semibold))
                Text("优先呈现日语词条；到期词先于新词。所有进度只保存在本机。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            HStack(spacing: 10) {
                ReviewCountTile(title: "到期", count: dueRecords.count)
                ReviewCountTile(title: "新词", count: newRecords.count)
                ReviewCountTile(title: "本次完成", count: reviewedInSession)
            }
        }
        .jerreaderPaperCard(padding: 18)
    }

    private func reviewCard(_ record: WordLookupRecord) -> some View {
        let context = record.contextHistory.first ?? record.sentenceContext
        let prompt = VocabularyReviewScheduler.shared.prompt(
            surfaceForm: record.surfaceForm,
            lemma: record.lemma,
            sentenceContext: context
        )
        let isRevealed = revealedRecordID == record.id
        let isJapanese = record.language == .japanese

        return VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .firstTextBaseline) {
                Text(isJapanese ? "日语回想" : "词语回想")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(JerreaderTheme.accent)
                Spacer()
                Text("第 \(reviewedInSession + 1) 张 · 还剩 \(queue.count) 张")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Text(prompt.text)
                .font(prompt.isCloze ? .title2.weight(.medium) : .largeTitle.weight(.bold))
                .fontDesign(isJapanese ? .serif : .default)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.top, 30)
                .padding(.bottom, 12)
                .textSelection(.enabled)

            Text(
                prompt.isCloze
                    ? "补全原句，并回想读音和中文释义。"
                    : isJapanese
                        ? "读出这个词，并回想基本形与中文释义。"
                        : "回想这个词的读音与中文释义。"
            )
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)

            if isRevealed {
                Divider()
                    .padding(.top, 22)
                    .padding(.bottom, 16)
                answer(record)
                ratingGrid(record)
            } else {
                Button("显示答案") {
                    withAnimation(JerreaderMotion.stateChange) {
                        revealedRecordID = record.id
                    }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(maxWidth: .infinity)
                .padding(.top, 22)
                .accessibilityHint("显示读音、基本形、释义和记忆评价按钮")
            }
        }
        .jerreaderPaperCard(padding: 20, hasShadow: true)
        .id(record.id)
    }

    @ViewBuilder
    private func answer(_ record: WordLookupRecord) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(record.surfaceForm)
                .font(.title2.weight(.bold))
                .fontDesign(record.language == .japanese ? .serif : .default)

            let metadata = answerMetadata(record)
            if !metadata.isEmpty {
                Text(metadata.joined(separator: " · "))
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            if let inflection = record.inflectionNote?.nilIfReviewBlank {
                Text("活用：\(inflection)")
                    .font(.subheadline)
            }

            VStack(alignment: .leading, spacing: 5) {
                ForEach(Array(record.definitions.prefix(3).enumerated()), id: \.offset) {
                    index, definition in
                    Text("\(index + 1). \(definition)")
                        .font(.body)
                }
            }
            .padding(.top, 3)

            if let usage = record.usageNote?.nilIfReviewBlank {
                Text("用法：\(usage)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            if let example = record.examples.first {
                VStack(alignment: .leading, spacing: 3) {
                    Text("例句：\(example.sourceText)")
                        .font(.subheadline)
                        .fontDesign(record.language == .japanese ? .serif : .default)
                    if let translation = example.translatedText?.nilIfReviewBlank {
                        Text(translation)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            if record.reviewCount > 0 {
                Text(
                    "已复习 \(record.reviewCount) 次 · 当前间隔 \(record.reviewIntervalDays) 天"
                )
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.top, 2)
            }
        }
        .textSelection(.enabled)
    }

    private func ratingGrid(_ record: WordLookupRecord) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            Text("这次记得怎么样？")
                .font(.subheadline.weight(.semibold))

            LazyVGrid(
                columns: [GridItem(.flexible()), GridItem(.flexible())],
                spacing: 9
            ) {
                ForEach(ReviewChoice.allCases) { choice in
                    Button {
                        apply(choice.rating, to: record)
                    } label: {
                        VStack(spacing: 2) {
                            Text(choice.rating.title)
                                .font(.subheadline.weight(.semibold))
                            Text(choice.rating.detail)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                        }
                        .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("review-rating-\(choice.rawValue)")
                    .accessibilityLabel(
                        "\(choice.rating.title)，\(choice.rating.detail)"
                    )
                }
            }
        }
        .padding(.top, 20)
    }

    private func apply(_ rating: VocabularyReviewRating, to record: WordLookupRecord) {
        do {
            try WordLookupStore.review(rating, record: record, at: now, in: modelContext)
            reviewedRecordIDs.insert(record.id)
            reviewedInSession += 1
            revealedRecordID = nil
        } catch {
            alert = LearningAlert(title: "无法保存复习进度", message: "请稍后重试。")
        }
    }

    private func queueState(for record: WordLookupRecord) -> VocabularyReviewQueueState {
        VocabularyReviewScheduler.shared.queueState(
            isFavorite: record.isFavorite,
            status: record.vocabularyStatus,
            reviewCount: Int32(record.reviewCount),
            nextReviewAtEpochMillis: Int64(
                (record.nextReviewAt?.timeIntervalSince1970 ?? 0) * 1_000
            ),
            nowEpochMillis: Int64(now.timeIntervalSince1970 * 1_000)
        )
    }

    private func languagePriority(_ record: WordLookupRecord) -> Int {
        record.language == .japanese ? 0 : 1
    }

    private func answerMetadata(_ record: WordLookupRecord) -> [String] {
        var values: [String] = []
        if let reading = record.reading?.nilIfReviewBlank {
            values.append(reading)
        }
        if let lemma = record.lemma?.nilIfReviewBlank, lemma != record.surfaceForm {
            values.append("基本形：\(lemma)")
        }
        if let partOfSpeech = record.partOfSpeech?.nilIfReviewBlank {
            values.append(partOfSpeech)
        }
        return values
    }

    private func seedReviewUITestRecordIfNeeded() {
#if DEBUG
        guard ProcessInfo.processInfo.arguments.contains("--jerreader-review-ui-test")
        else { return }
        let explanation = WordExplanation(
            surfaceForm: "食べました",
            lemma: "食べる",
            reading: "たべました",
            language: .japanese,
            partOfSpeech: "动词",
            definitions: ["吃；食用", "摄取食物"],
            inflectionNote: "一段动词「食べる」的礼貌体过去式",
            examples: [
                WordExample(
                    sourceText: "家族と一緒に夕食を食べました。",
                    translatedText: "和家人一起吃了晚饭。"
                ),
            ],
            usageNote: "表示进食动作已经完成。",
            sentenceContext: "昨日、家族と寿司を食べました。"
        )
        let key = WordLookupRecord.makeLookupKey(for: explanation)
        var descriptor = FetchDescriptor<WordLookupRecord>(
            predicate: #Predicate { $0.lookupKey == key }
        )
        descriptor.fetchLimit = 1
        if let existing = try? modelContext.fetch(descriptor).first {
            existing.isFavorite = true
            existing.vocabularyStatusRawValue = "learning"
            existing.reviewCount = 0
            existing.reviewStage = 0
            existing.reviewIntervalDays = 0
            existing.reviewLapseCount = 0
            existing.lastReviewedAt = nil
            existing.nextReviewAt = nil
            existing.lastLookedUpAt = Date()
            try? modelContext.save()
            return
        }
        modelContext.insert(
            WordLookupRecord(
                explanation: explanation,
                lastLookedUpAt: Date(),
                isFavorite: true,
                vocabularyStatusRawValue: "learning"
            )
        )
        try? modelContext.save()
#endif
    }
}

private struct ReviewCountTile: View {
    let title: String
    let count: Int

    var body: some View {
        VStack(spacing: 3) {
            Text(count.formatted())
                .font(.title2.weight(.bold))
                .foregroundStyle(JerreaderTheme.accent)
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, minHeight: 64)
        .background(
            JerreaderTheme.mutedSurface,
            in: RoundedRectangle(cornerRadius: 12, style: .continuous)
        )
        .accessibilityElement(children: .combine)
    }
}

private enum ReviewChoice: String, CaseIterable, Identifiable {
    case again
    case hard
    case good
    case easy

    var id: Self { self }

    var rating: VocabularyReviewRating {
        switch self {
        case .again: return .again
        case .hard: return .hard
        case .good: return .good
        case .easy: return .easy
        }
    }
}

private extension String {
    var nilIfReviewBlank: String? {
        let value = trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }
}
