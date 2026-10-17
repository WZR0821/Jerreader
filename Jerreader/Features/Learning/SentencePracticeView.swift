import SwiftData
import SwiftUI

struct SentencePracticeView: View {
    @Query(sort: \TranslationFavoriteRecord.updatedAt, order: .reverse)
    private var sentences: [TranslationFavoriteRecord]

    var body: some View {
        LearningPage {
                Text("用收藏的句子制作练习。指定要挖空的词或短语，可自行修改题面；制作和答题都在本机完成。")
                    .foregroundStyle(.secondary)
            if sentences.isEmpty {
                Text("先在阅读器或翻译页收藏一句译文。")
            }
            ForEach(sentences) { sentence in
                NavigationLink {
                    SentencePracticeEditor(record: sentence)
                } label: {
                    LearningCard {
                        Text(sentence.sourceText).lineLimit(3)
                            .foregroundStyle(.primary)
                        Text(sentence.practiceData == nil ? "制作练习卡" : "开始练习 · 可编辑")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }.buttonStyle(.plain)
            }
        }.navigationTitle("原句练习卡")
    }
}

private struct SentencePracticeEditor: View {
    @Environment(\.modelContext) private var context
    @Query private var words: [WordLookupRecord]
    let record: TranslationFavoriteRecord
    @State private var answer: String
    @State private var prompt: String
    @State private var editing: Bool
    @State private var revealed = false
    @State private var error: String?

    init(record: TranslationFavoriteRecord) {
        self.record = record
        let card = SentencePractice.decode(record.practiceData)
        _answer = State(initialValue: card?.answer ?? "")
        _prompt = State(initialValue: card?.prompt ?? "")
        _editing = State(initialValue: card == nil)
    }

    var body: some View {
        LearningPage {
          LearningCard {
            if let error { Text(error).foregroundStyle(.red) }
            if editing {
                Text("原句").font(.headline)
                Text(record.sourceText).textSelection(.enabled)
                Text("挖空词或短语（全部相同文字）").font(.subheadline)
                TextField("输入原句中的词或短语", text: $answer)
                    .textFieldStyle(.roundedBorder)
                    .onChange(of: answer) { _, value in
                        prompt = SentencePractice.make(source: record.sourceText, answer: value)?.prompt ?? ""
                    }
                Text("题面，可编辑").font(.subheadline)
                TextEditor(text: $prompt).frame(minHeight: 120)
                    .accessibilityLabel("题面，可编辑")
                Button("保存并练习") {
                    guard let card = SentencePractice.make(source: record.sourceText, answer: answer),
                          !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    else { error = "请指定原句中存在的内容，并填写题面。"; return }
                    do {
                        record.practiceData = try JSONEncoder().encode(SentencePractice(answer: card.answer, prompt: prompt))
                        try context.save()
                        answer = card.answer
                        editing = false; revealed = false; error = nil
                    } catch { context.rollback(); self.error = "练习卡未能保存，请重试。" }
                }.buttonStyle(LearningActionStyle(kind: .filled))
            } else {
                Text(prompt).font(.title3).textSelection(.enabled)
                Button(revealed ? "隐藏答案" : "显示答案与译文") { revealed.toggle() }
                    .buttonStyle(LearningActionStyle(kind: .filled))
                if revealed {
                    Text("答案：\(answer)")
                    Text(record.sourceText)
                    Text(record.translatedText).foregroundStyle(.secondary)
                    ForEach(words.filter { $0.languageRawValue == record.sourceLanguageRawValue &&
                        ($0.surfaceForm.localizedCaseInsensitiveCompare(answer) == .orderedSame ||
                         $0.lemma?.localizedCaseInsensitiveCompare(answer) == .orderedSame) }) { word in
                        Text("已有释义：\(word.definitions.joined(separator: "；"))")
                    }
                }
                Button("编辑练习卡") { editing = true; revealed = false }
            }
            if let book = record.bookTitle { LearningHint(text: "来源：\(book)") }
          }
        }.navigationTitle("原句自测")
    }
}
