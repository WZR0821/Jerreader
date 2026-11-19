import SwiftData
import SwiftUI

struct SentencePracticeView: View {
    @Query(sort: \TranslationFavoriteRecord.updatedAt, order: .reverse)
    private var sentences: [TranslationFavoriteRecord]
    /// Open only just after the page appears; see `LearningStagger`.
    @State private var staggerActive = true

    var body: some View {
        LearningPage {
                Text("用收藏的句子制作练习。指定要挖空的词或短语，可自行修改题面；制作和答题都在本机完成。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            if sentences.isEmpty {
                VStack(spacing: JerreaderSpacing.sm) {
                    JerreaderIconTile(systemImage: "text.quote", size: 52)
                    Text("先在阅读器或翻译页收藏一句译文。")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, JerreaderSpacing.xxl)
            }
            ForEach(sentences) { sentence in
                NavigationLink {
                    SentencePracticeEditor(record: sentence)
                } label: {
                    LearningCard {
                        HStack(alignment: .top, spacing: JerreaderSpacing.sm) {
                            JerreaderSymbol(name: "text.quote", size: 16)
                                .foregroundStyle(JerreaderTheme.accent)
                                .padding(.top, 3)
                            VStack(alignment: .leading, spacing: JerreaderSpacing.xs) {
                                Text(sentence.sourceText).lineLimit(3)
                                    .foregroundStyle(.primary)
                                    .multilineTextAlignment(.leading)
                                LearningChip(
                                    text: sentence.practiceData == nil ? "制作练习卡" : "开始练习 · 可编辑",
                                    tint: sentence.practiceData == nil ? JerreaderTheme.gold : JerreaderTheme.accent
                                )
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            JerreaderSymbol(name: "chevron.forward", size: 13)
                                .foregroundStyle(.tertiary)
                                .padding(.top, 4)
                        }
                    }
                }
                .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.98))
                .learningStagger(order: LearningStagger.order(of: sentence, in: sentences), active: staggerActive)
            }
        }
        .navigationTitle("原句练习卡")
        .task {
            try? await Task.sleep(for: LearningStagger.window)
            staggerActive = false
        }
    }
}

private struct SentencePracticeEditor: View {
    @Environment(\.modelContext) private var context
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
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

    /// The answer cross-fades in and grows a little; with Reduce Motion it
    /// only cross-fades.
    private var answerTransition: AnyTransition {
        reduceMotion
            ? AnyTransition.opacity
            : AnyTransition.opacity.combined(with: .scale(scale: 0.96, anchor: .top))
    }

    var body: some View {
        LearningPage {
          LearningCard {
            if let error { Text(error).font(.subheadline).foregroundStyle(JerreaderTheme.vermilion) }
            if editing {
                JerreaderEyebrow(text: "原句")
                Text(record.sourceText).textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                Text("挖空词或短语（全部相同文字）").font(.subheadline.weight(.semibold))
                TextField("输入原句中的词或短语", text: $answer)
                    .padding(.horizontal, 14)
                    .frame(minHeight: 44)
                    .background(JerreaderTheme.mutedSurface, in: RoundedRectangle(cornerRadius: JerreaderRadius.control, style: .continuous))
                    .onChange(of: answer) { _, value in
                        prompt = SentencePractice.make(source: record.sourceText, answer: value)?.prompt ?? ""
                    }
                Text("题面，可编辑").font(.subheadline.weight(.semibold))
                TextEditor(text: $prompt).frame(minHeight: 120)
                    .scrollContentBackground(.hidden)
                    .padding(8)
                    .background(JerreaderTheme.mutedSurface, in: RoundedRectangle(cornerRadius: JerreaderRadius.control, style: .continuous))
                    .accessibilityLabel("题面，可编辑")
                Button {
                    guard let card = SentencePractice.make(source: record.sourceText, answer: answer),
                          !prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    else { error = "请指定原句中存在的内容，并填写题面。"; return }
                    do {
                        record.practiceData = try JSONEncoder().encode(SentencePractice(answer: card.answer, prompt: prompt))
                        try context.save()
                        answer = card.answer
                        editing = false; revealed = false; error = nil
                    } catch { context.rollback(); self.error = "练习卡未能保存，请重试。" }
                } label: {
                    Text("保存并练习").frame(maxWidth: .infinity)
                }.buttonStyle(LearningActionStyle(kind: .filled))
            } else {
                Text(prompt)
                    .font(.system(.title3, design: .serif, weight: .semibold))
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
                Button { revealed.toggle() } label: {
                    Text(revealed ? "隐藏答案" : "显示答案与译文").frame(maxWidth: .infinity)
                }
                    .buttonStyle(LearningActionStyle(kind: .filled))
                if revealed {
                    VStack(alignment: .leading, spacing: JerreaderSpacing.sm) {
                        Text("答案：\(answer)")
                            .font(.headline)
                            .foregroundStyle(JerreaderTheme.jade)
                        Text(record.sourceText)
                            .fixedSize(horizontal: false, vertical: true)
                        Text(record.translatedText).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                        ForEach(words.filter { $0.languageRawValue == record.sourceLanguageRawValue &&
                            ($0.surfaceForm.localizedCaseInsensitiveCompare(answer) == .orderedSame ||
                             $0.lemma?.localizedCaseInsensitiveCompare(answer) == .orderedSame) }) { word in
                            Text("已有释义：\(word.definitions.joined(separator: "；"))")
                                .font(.subheadline)
                        }
                    }
                    .padding(JerreaderSpacing.sm)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(JerreaderTheme.jade.opacity(0.08), in: RoundedRectangle(cornerRadius: JerreaderRadius.control, style: .continuous))
                    .transition(answerTransition)
                }
                Button("编辑练习卡") { editing = true; revealed = false }
            }
            if let book = record.bookTitle { LearningHint(text: "来源：\(book)") }
          }
          // Scoped to the answer showing or hiding.
          .animation(JerreaderMotion.choose(JerreaderMotion.stateChange, reduceMotion: reduceMotion), value: revealed)
        }.navigationTitle("原句自测")
    }
}
