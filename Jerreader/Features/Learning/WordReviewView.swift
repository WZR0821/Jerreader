import SwiftData
import SwiftUI

struct WordReviewView: View {
    @Environment(\.modelContext) private var context
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Query(filter: #Predicate<WordLookupRecord> { $0.isFavorite }, sort: \WordLookupRecord.createdAt)
    private var words: [WordLookupRecord]
    @State private var queue: [UUID] = []
    @State private var completed = 0
    @State private var revealed = false
    @State private var error: String?
    @State private var started = false
    @State private var prepared: (inputs: [WordReviewSnapshot.Input], value: WordReviewSnapshot)?
    /// The last answer, for its haptic.
    @State private var gradeEvent = ReviewGradeEvent()
    /// Bumped when the finished-group check mark appears, to bounce it.
    @State private var celebration = 0

    private var current: WordLookupRecord? {
        queue.compactMap { id in words.first { $0.id == id } }
            .first { prepared?.value.states[$0.id]?.paused == false }
    }

    /// How far through this group: the answered words out of those answered
    /// and those still to come.
    private var sessionProgress: Double {
        let remaining = queue.filter { id in
            prepared?.value.states[id]?.paused == false && words.contains { $0.id == id }
        }.count
        let total = completed + remaining
        return total > 0 ? Double(completed) / Double(total) : 0
    }

    private var stateAnimation: Animation {
        JerreaderMotion.choose(JerreaderMotion.stateChange, reduceMotion: reduceMotion)
    }

    /// The answer cross-fades in and grows a little; with Reduce Motion it
    /// only cross-fades.
    private var answerTransition: AnyTransition {
        reduceMotion
            ? AnyTransition.opacity
            : AnyTransition.opacity.combined(with: .scale(scale: 0.96, anchor: .top))
    }

    var body: some View {
        let inputs = words.map { WordReviewSnapshot.Input(id: $0.id, data: $0.reviewData) }
        let ready = prepared?.inputs == inputs
        LearningPage {
            LearningCard {
                Text("每组最多 10 个词。先回忆原句中的意思，再查看释义。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if let error { Text(error).font(.subheadline).foregroundStyle(JerreaderTheme.vermilion) }
                if !ready {
                    Text("正在准备复习…")
                        .foregroundStyle(.secondary)
                } else if let word = current {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("本组已完成 \(completed) 个")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                        JerreaderProgressBar(progress: sessionProgress)
                    }
                    Text(word.surfaceForm)
                        .font(.system(.largeTitle, design: .serif, weight: .bold))
                        .fixedSize(horizontal: false, vertical: true)
                    if let sentence = word.sentenceContext {
                        Text(sentence)
                            .textSelection(.enabled)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.leading, 12)
                            .overlay(alignment: .leading) {
                                Capsule()
                                    .fill(JerreaderTheme.accent.opacity(0.35))
                                    .frame(width: 3)
                                    .accessibilityHidden(true)
                            }
                    }
                    if let book = word.sourceBookTitle {
                        HStack(spacing: 6) {
                            JerreaderSymbol(name: "book.closed", size: 12)
                            Text(book)
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    }
                    if revealed {
                        VStack(alignment: .leading, spacing: 12) {
                            if let reading = word.reading {
                                Text(reading)
                                    .font(.title3)
                                    .foregroundStyle(.secondary)
                            }
                            if word.language == .japanese,
                               SpeechFeatureAvailability.learningReviewIsEnabled
                            {
                                LearningActions {
                                    Button("朗读单词") { speak(word.surfaceForm) }
                                    if let sentence = word.sentenceContext,
                                       !ReadingCompanionText.isBlank(sentence)
                                    {
                                        Button("朗读原句·跟读") { speak(sentence) }
                                    }
                                }.buttonStyle(LearningActionStyle(kind: .tinted))
                            }
                            Text(word.definitions.joined(separator: "；"))
                                .fixedSize(horizontal: false, vertical: true)
                            gradeButtons(for: word)
                        }
                        .transition(answerTransition)
                    } else {
                        Button { revealed = true } label: {
                            Text("显示释义").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(LearningActionStyle(kind: .filled))
                        .transition(.opacity)
                    }
                } else {
                    if started {
                        HStack(spacing: 12) {
                            JerreaderSymbol(name: "checkmark.circle.fill", size: 40)
                                .foregroundStyle(JerreaderTheme.jade)
                                .jerreaderBounce(trigger: celebration)
                                .onAppear { celebration += 1 }
                            JerreaderProgressBar(progress: 1, tint: JerreaderTheme.jade)
                        }
                        .transition(answerTransition)
                    }
                    Text(started ? "本组复习完成" : "今日复习").font(.headline)
                    TimelineView(.periodic(from: .now, by: 30)) { timeline in
                        let due = prepared?.value.dueIDs(at: timeline.date) ?? []
                        Text("当前待复习：\(due.count) 个")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Button {
                            queue = Array(due.prefix(10))
                            completed = 0
                            started = true
                            revealed = false
                        } label: {
                            Text(started ? "继续下一组" : "开始复习").frame(maxWidth: .infinity)
                        }
                        .buttonStyle(LearningActionStyle(kind: .filled))
                        .disabled(due.isEmpty)
                    }
                    if words.isEmpty {
                        Text("先在生词本收藏词语，它们会自动加入复习。")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            // Scoped to the two changes that reshape the card.
            .animation(stateAnimation, value: revealed)
            .animation(stateAnimation, value: current?.id)
            LearningSectionHeading(title: "管理复习")
                ForEach(ready ? words : []) { word in
                    let state = prepared?.value.states[word.id] ?? WordReviewState()
                    LearningCard {
                        Text(word.surfaceForm).font(.headline)
                        HStack(spacing: 6) {
                            LearningKindDot(tint: statusTint(state))
                            Text(state.paused ? "已暂停" : state.answers.isEmpty ? "新词" : "下次：\(state.dueAt.formatted(date: .abbreviated, time: .shortened))")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        LearningActions {
                            Button(state.paused ? "恢复" : "暂停") {
                                var updated = state
                                updated.paused.toggle()
                                if save(updated, word: word) { queue.removeAll { $0 == word.id }; revealed = false }
                            }
                            Button("重置进度") {
                                if save(WordReviewState(), word: word) { queue.removeAll { $0 == word.id }; revealed = false }
                            }
                        }.buttonStyle(LearningActionStyle(kind: .tinted))
                    }
                }
        }
        .navigationTitle("生词复习")
        .sensoryFeedback(trigger: gradeEvent) { _, event in event.feedback }
        .onChange(of: current?.id) { _, _ in revealed = false }
        .onDisappear { SystemSpeechService.shared.stop() }
        .task(id: inputs) {
            let worker = Task.detached(priority: .userInitiated) {
                try WordReviewSnapshot(inputs: inputs, checkpoint: { try Task.checkCancellation() })
            }
            let result = try? await withTaskCancellationHandler {
                try await worker.value
            } onCancel: { worker.cancel() }
            guard !Task.isCancelled, let result else { return }
            prepared = (inputs, result)
        }
    }

    /// 不认识 · 模糊 · 认识 in vermilion, gold and jade, side by side when
    /// they fit and stacked when they do not.
    private func gradeButtons(for word: WordLookupRecord) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) { gradeButtonList(for: word) }
            VStack(spacing: 8) { gradeButtonList(for: word) }
        }
    }

    private func gradeButtonList(for word: WordLookupRecord) -> some View {
        ForEach(ReviewGrade.allCases, id: \.rawValue) { grade in
            Button(grade.title) {
                var state = WordReviewState.decode(word.reviewData)
                state.grade(grade, at: Date(), bookID: word.sourceBookID)
                if save(state, word: word) {
                    queue.removeAll { $0 == word.id }
                    completed += 1
                    revealed = false
                    gradeEvent = ReviewGradeEvent(
                        count: gradeEvent.count + 1,
                        grade: grade,
                        finishesGroup: current == nil
                    )
                }
            }
            .buttonStyle(JerreaderTintedButtonStyle(height: 48, fullWidth: true, tint: tint(for: grade)))
        }
    }

    private func tint(for grade: ReviewGrade) -> Color {
        switch grade {
        case .unknown: return JerreaderTheme.vermilion
        case .fuzzy: return JerreaderTheme.gold
        case .known: return JerreaderTheme.jade
        }
    }

    /// Paused grey, new accent, due vermilion, scheduled jade.
    private func statusTint(_ state: WordReviewState) -> Color {
        if state.paused { return Color.secondary.opacity(0.5) }
        if state.answers.isEmpty { return JerreaderTheme.accent }
        return state.dueAt <= Date() ? JerreaderTheme.vermilion : JerreaderTheme.jade
    }

    private func speak(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        Task { @MainActor in
            do {
                try await SystemSpeechService.shared.speak(trimmed, language: .japanese)
            } catch is CancellationError {
                return
            } catch {
                self.error = "系统语音暂时不可用，请根据读音继续复习。"
            }
        }
    }

    private func save(_ state: WordReviewState, word: WordLookupRecord) -> Bool {
        do {
            word.reviewData = try JSONEncoder().encode(state)
            try context.save()
            error = nil
            return true
        } catch {
            context.rollback()
            self.error = "复习进度未能保存，请重试。"
            return false
        }
    }
}

/// One answer in a review group, as the trigger of its haptic: success for
/// 认识, a warning for 不认识, and success for the answer that ends the group.
private struct ReviewGradeEvent: Equatable {
    var count = 0
    var grade: ReviewGrade?
    var finishesGroup = false

    var feedback: SensoryFeedback? {
        if finishesGroup { return .success }
        switch grade {
        case .known?: return .success
        case .unknown?: return .warning
        default: return nil
        }
    }
}
