import SwiftData
import SwiftUI

struct WordReviewView: View {
    @Environment(\.modelContext) private var context
    @Query(filter: #Predicate<WordLookupRecord> { $0.isFavorite }, sort: \WordLookupRecord.createdAt)
    private var words: [WordLookupRecord]
    @State private var queue: [UUID] = []
    @State private var completed = 0
    @State private var revealed = false
    @State private var error: String?
    @State private var started = false
    @State private var prepared: (inputs: [WordReviewSnapshot.Input], value: WordReviewSnapshot)?

    private var current: WordLookupRecord? {
        queue.compactMap { id in words.first { $0.id == id } }
            .first { prepared?.value.states[$0.id]?.paused == false }
    }

    var body: some View {
        let inputs = words.map { WordReviewSnapshot.Input(id: $0.id, data: $0.reviewData) }
        let ready = prepared?.inputs == inputs
        LearningPage {
            LearningCard {
                Text("每组最多 10 个词。先回忆原句中的意思，再查看释义。")
                    .foregroundStyle(.secondary)
                if let error { Text(error).foregroundStyle(.red) }
                if !ready {
                    Text("正在准备复习…")
                } else if let word = current {
                    Text("本组已完成 \(completed) 个").font(.caption)
                    Text(word.surfaceForm).font(.title2.bold())
                    if let sentence = word.sentenceContext { Text(sentence).textSelection(.enabled) }
                    if let book = word.sourceBookTitle { Text(book).font(.caption).foregroundStyle(.secondary) }
                    if revealed {
                        if let reading = word.reading { Text(reading) }
                        Text(word.definitions.joined(separator: "；"))
                        LearningActions {
                          ForEach(ReviewGrade.allCases, id: \.rawValue) { grade in
                            Button(grade.title) {
                                var state = WordReviewState.decode(word.reviewData)
                                state.grade(grade, at: Date(), bookID: word.sourceBookID)
                                if save(state, word: word) {
                                    queue.removeAll { $0 == word.id }
                                    completed += 1
                                    revealed = false
                                }
                            }
                          }
                        }.buttonStyle(LearningActionStyle(kind: .tinted))
                    } else {
                        Button("显示释义") { revealed = true }
                    }
                } else {
                    Text(started ? "本组复习完成" : "今日复习").font(.headline)
                    TimelineView(.periodic(from: .now, by: 30)) { timeline in
                        let due = prepared?.value.dueIDs(at: timeline.date) ?? []
                        Text("当前待复习：\(due.count) 个")
                        Button(started ? "继续下一组" : "开始复习") {
                            queue = Array(due.prefix(10))
                            completed = 0
                            started = true
                            revealed = false
                        }.disabled(due.isEmpty)
                    }
                    if words.isEmpty { Text("先在生词本收藏词语，它们会自动加入复习。") }
                }
            }
            Text("管理复习").font(.headline)
                ForEach(ready ? words : []) { word in
                    let state = prepared?.value.states[word.id] ?? WordReviewState()
                    LearningCard {
                        Text(word.surfaceForm).font(.headline)
                        Text(state.paused ? "已暂停" : state.answers.isEmpty ? "新词" : "下次：\(state.dueAt.formatted(date: .abbreviated, time: .shortened))")
                            .font(.caption).foregroundStyle(.secondary)
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
        .onChange(of: current?.id) { _, _ in revealed = false }
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
