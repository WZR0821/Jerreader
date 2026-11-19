import SwiftUI
import SwiftData

/// Shared layout for the five learning tools: page margins of 20, paper
/// cards, bounded reading width, lazy rows, on the canvas with its wash.
struct LearningPage<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: JerreaderSpacing.md) { content }
                .padding(.horizontal, JerreaderTheme.pagePadding)
                .padding(.top, JerreaderSpacing.md)
                .padding(.bottom, JerreaderSpacing.xl)
                .frame(maxWidth: 720)
                .frame(maxWidth: .infinity)
        }
        .background(JerreaderCanvasBackground())
        .scrollDismissesKeyboard(.interactively)
        .navigationBarTitleDisplayMode(.inline)
        .buttonStyle(LearningActionStyle())
    }
}

/// A paper card: radius 20, inner padding 16, e1 in light mode and a 1pt
/// edge in dark mode.
struct LearningCard<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: JerreaderSpacing.sm) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .jerreaderPaperCard(padding: JerreaderSpacing.md, radius: JerreaderRadius.card)
    }
}

struct LearningHint: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
    }
}

struct LearningActions<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: JerreaderSpacing.xs) { content }.fixedSize(horizontal: true, vertical: false)
            VStack(alignment: .leading, spacing: JerreaderSpacing.xs) { content }
        }
    }
}

/// The buttons of the learning tools. `filled` is the page's one accent
/// capsule, `tinted` an accent-tinted capsule, `plain` accent text. A
/// destructive role turns them vermilion. Capsules press to 0.97.
struct LearningActionStyle: ButtonStyle {
    enum Kind { case plain, filled, tinted }
    var kind: Kind = .plain
    @Environment(\.isEnabled) private var enabled
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func makeBody(configuration: Configuration) -> some View {
        let destructive = configuration.role == .destructive
        let tint = destructive ? JerreaderTheme.vermilion : JerreaderTheme.accent
        let pressed = configuration.isPressed
        return configuration.label
            .font(font)
            .foregroundStyle(kind == .filled ? JerreaderTheme.onPrimaryAction : tint)
            .padding(.horizontal, kind == .plain ? 0 : 18)
            .padding(.vertical, 8)
            .frame(minHeight: 44)
            .background {
                switch kind {
                case .filled:
                    Capsule()
                        .fill(tint)
                        .overlay {
                            Capsule().fill(
                                LinearGradient(
                                    colors: [Color.white.opacity(0.10), Color.white.opacity(0)],
                                    startPoint: .top,
                                    endPoint: .bottom
                                )
                            )
                        }
                        .shadow(
                            color: colorScheme == .dark || !enabled ? .clear : tint.opacity(0.28),
                            radius: 12,
                            y: 6
                        )
                case .tinted:
                    Capsule().fill(tint.opacity(0.12))
                case .plain:
                    Color.clear
                }
            }
            .contentShape(Rectangle())
            .scaleEffect(kind != .plain && pressed ? 0.97 : 1)
            .opacity(enabled ? (kind == .plain && pressed ? 0.6 : 1) : 0.45)
            .animation(
                reduceMotion ? nil : (pressed ? JerreaderMotion.press : JerreaderMotion.release),
                value: pressed
            )
    }

    private var font: Font {
        switch kind {
        case .filled: return .headline
        case .tinted: return .subheadline.weight(.semibold)
        case .plain: return .body
        }
    }
}

/// A text field on mutedSurface, radius 14, optionally with a leading icon
/// (a magnifying glass for a search field).
struct LearningTextField: View {
    let title: String
    @Binding var text: String
    var systemImage: String?
    var body: some View {
        TextField(title, text: $text)
            .font(.body).textInputAutocapitalization(.never).autocorrectionDisabled()
            .padding(.leading, systemImage == nil ? 14 : 38)
            .padding(.trailing, 14)
            .padding(.vertical, 10).frame(minHeight: 44)
            .background(alignment: .leading) {
                if let systemImage {
                    JerreaderSymbol(name: systemImage, size: 16)
                        .foregroundStyle(.secondary)
                        .padding(.leading, 14)
                }
            }
            .background(JerreaderTheme.mutedSurface, in: RoundedRectangle(cornerRadius: JerreaderRadius.control, style: .continuous))
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
