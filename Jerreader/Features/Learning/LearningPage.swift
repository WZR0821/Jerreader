import SwiftUI
import SwiftData

/// Shared layout for the five learning tools: 16-point margins and cards,
/// bounded reading width, lazy rows, and the existing app color palette.
struct LearningPage<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 12) { content }
                .padding(16)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
        }
        .background(JerreaderCanvasBackground())
        .scrollDismissesKeyboard(.interactively)
        .navigationBarTitleDisplayMode(.inline)
        .buttonStyle(LearningActionStyle())
    }
}

struct LearningCard<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 12) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .jerreaderPaperCard(padding: 16, radius: 16)
    }
}

struct LearningHint: View {
    let text: String
    var body: some View { Text(text).font(.subheadline).foregroundStyle(.secondary) }
}

struct LearningActions<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) { content }.fixedSize(horizontal: true, vertical: false)
            VStack(alignment: .leading, spacing: 8) { content }
        }
    }
}

struct LearningActionStyle: ButtonStyle {
    enum Kind { case plain, filled, tinted }
    var kind: Kind = .plain
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body)
            .foregroundStyle(configuration.role == .destructive ? Color.red : kind == .filled ? JerreaderTheme.onPrimaryAction : JerreaderTheme.accent)
            .padding(.horizontal, kind == .plain ? 0 : 12)
            .padding(.vertical, 8)
            .frame(minHeight: 44)
            .background(kind == .filled ? JerreaderTheme.accent : kind == .tinted ? JerreaderTheme.accentFill : .clear,
                in: RoundedRectangle(cornerRadius: 8))
            .contentShape(Rectangle())
            .opacity(enabled ? (configuration.isPressed ? 0.7 : 1) : 0.4)
    }
}

struct LearningTextField: View {
    let title: String
    @Binding var text: String
    var body: some View {
        TextField(title, text: $text)
            .font(.body).textInputAutocapitalization(.never).autocorrectionDisabled()
            .padding(.horizontal, 10).padding(.vertical, 10).frame(minHeight: 44)
            .background(JerreaderTheme.paper, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(JerreaderTheme.line, lineWidth: 0.75))
            .accessibilityLabel(title)
    }
}

#if DEBUG
@MainActor
enum LearningUIFixture {
    static func seed(_ context: ModelContext) throws {
        let book = BookRecord(title: "中日阅读练习", author: "Jerreader", language: "ja",
            localFileName: "ui-fixture-not-a-publication.epub", fileFingerprint: "ui-fixture")
        book.activityData = try JSONEncoder().encode(ReadingActivity(days: [ReadingActivity.key(Date()): ReadingDay(seconds: 1200, queries: 4)]))
        context.insert(book)
        context.insert(WordLookupRecord(explanation: WordExplanation(surfaceForm: "食べる", lemma: "食べる", reading: "たべる",
            language: .japanese, partOfSpeech: "一段动词", definitions: ["吃"], usageNote: nil, sentenceContext: "毎朝、ご飯を食べる。"),
            sourceBookID: book.id, sourceBookTitle: book.title, isFavorite: true))
        let sentence = TranslationFavoriteRecord(favoriteKey: "ui-sentence", sourceText: "毎日、日本語の本を読む。",
            translatedText: "每天读日语书。", sourceLanguage: .japanese, targetLanguage: .simplifiedChinese,
            providerIdentifier: "UI fixture", bookID: book.id, bookTitle: book.title, locatorJSON: nil)
        sentence.practiceData = try JSONEncoder().encode(SentencePractice(answer: "読む", prompt: "毎日、日本語の本を［____］。"))
        context.insert(sentence)
        context.insert(GrammarNoteRecord(GrammarNoteDraft(title: "〜ながら：同时进行的动作", sentence: "音楽を聞きながら、本を読む。",
            explanation: "动词ます形去掉ます后接「ながら」，表示同一个人同时进行两个动作，重点通常在后面的动作。",
            language: "ja", tags: "同时进行，接续", bookID: book.id, bookTitle: book.title)))
        try context.save()
    }
}
#endif
