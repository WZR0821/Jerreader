import CoreFoundation
import CoreText
import SwiftData
import SwiftUI
import UIKit
@preconcurrency import Translation

enum StandaloneTranslationMode: String, CaseIterable, Identifiable, Sendable {
    case word
    case sentence

    var id: Self { self }

    var title: String {
        switch self {
        case .word: return "词语"
        case .sentence: return "句子"
        }
    }

    var symbol: String {
        switch self {
        case .word: return "textformat.abc"
        case .sentence: return "text.alignleft"
        }
    }

    var placeholder: String {
        switch self {
        case .word: return "输入一个词或短语"
        case .sentence: return "输入或粘贴要翻译的句子"
        }
    }

    var maximumCharacterCount: Int {
        switch self {
        case .word: return 80
        case .sentence: return 2_000
        }
    }

    /// Word mode asks an AI provider for a dictionary-style equivalent rather
    /// than letting it add explanations. Sentence mode respects the prompt the
    /// user configured in Settings.
    func translationPrompt(fallback: String) -> String {
        switch self {
        case .word:
            return """
            你是一部精确的中英日词典。把选中的 {source_language} 词语翻译成最贴合语境的 {target_language} 词或短语。只输出译词，不要解释、例句、标题、引号或备选列表。
            """
        case .sentence:
            return fallback
        }
    }
}

struct StandaloneTranslationRequest: Equatable, Sendable {
    let id: UUID
    let text: String
    let sourceLanguage: LanguageCode
    let targetLanguage: LanguageCode
    let mode: StandaloneTranslationMode
}

enum StandaloneTranslationValidationError: LocalizedError, Equatable {
    case emptyText
    case textTooLong(maximum: Int)
    case languageNotDetected
    case sameLanguage

    var errorDescription: String? {
        switch self {
        case .emptyText:
            return "请输入需要翻译的文字。"
        case let .textTooLong(maximum):
            return "当前模式一次最多翻译 \(maximum) 个字符。"
        case .languageNotDetected:
            return "无法自动识别原文语言，请手动选择中文、英语或日语。"
        case .sameLanguage:
            return "原文和译文不能选择相同的语言。"
        }
    }
}

enum StandaloneTranslationRequestPolicy {
    static func makeRequest(
        text: String,
        sourceChoice: TranslationSourceLanguageChoice,
        targetLanguage: LanguageCode,
        mode: StandaloneTranslationMode,
        id: UUID = UUID()
    ) throws -> StandaloneTranslationRequest {
        let normalized = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else {
            throw StandaloneTranslationValidationError.emptyText
        }
        guard normalized.count <= mode.maximumCharacterCount else {
            throw StandaloneTranslationValidationError.textTooLong(
                maximum: mode.maximumCharacterCount
            )
        }
        guard let sourceLanguage = sourceChoice.languageCode
                ?? ReaderLanguageDetector.detect(
                    text: normalized,
                    bookLanguage: nil
                )
        else {
            throw StandaloneTranslationValidationError.languageNotDetected
        }
        guard sourceLanguage != targetLanguage else {
            throw StandaloneTranslationValidationError.sameLanguage
        }
        return StandaloneTranslationRequest(
            id: id,
            text: normalized,
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            mode: mode
        )
    }
}

enum StandaloneLexicalLookupPolicy {
    static func supports(_ request: StandaloneTranslationRequest) -> Bool {
        request.mode == .word
            && (request.sourceLanguage == .japanese
                || request.sourceLanguage == .english)
    }
}

struct JapaneseRubySegment: Equatable, Sendable {
    let text: String
    let reading: String?
}

/// Builds Japanese ruby locally. No translated text is sent to another
/// service just to obtain readings, and the base string is never rewritten.
enum JapaneseFuriganaFormatter {
    static func segments(for text: String) -> [JapaneseRubySegment] {
        guard !text.isEmpty else { return [] }

        let source = text as CFString
        let fullRange = CFRange(location: 0, length: CFStringGetLength(source))
        guard let tokenizer = CFStringTokenizerCreate(
            kCFAllocatorDefault,
            source,
            fullRange,
            kCFStringTokenizerUnitWordBoundary,
            Locale(identifier: "ja_JP") as CFLocale
        ) else {
            return [JapaneseRubySegment(text: text, reading: nil)]
        }

        let sourceNSString = text as NSString
        var result: [JapaneseRubySegment] = []
        var cursor = 0
        var tokenType = CFStringTokenizerGoToTokenAtIndex(tokenizer, 0)

        while !tokenType.isEmpty {
            let tokenRange = CFStringTokenizerGetCurrentTokenRange(tokenizer)
            guard tokenRange.location != kCFNotFound,
                  tokenRange.length > 0
            else {
                tokenType = CFStringTokenizerAdvanceToNextToken(tokenizer)
                continue
            }

            if tokenRange.location > cursor {
                appendPlain(
                    sourceNSString.substring(
                        with: NSRange(
                            location: cursor,
                            length: tokenRange.location - cursor
                        )
                    ),
                    to: &result
                )
            }

            let token = sourceNSString.substring(
                with: NSRange(
                    location: tokenRange.location,
                    length: tokenRange.length
                )
            )
            if containsKanji(token),
               let latin = CFStringTokenizerCopyCurrentTokenAttribute(
                    tokenizer,
                    kCFStringTokenizerAttributeLatinTranscription
               ) as? String,
               let reading = hiraganaReading(from: latin),
               !reading.isEmpty
            {
                result.append(contentsOf: rubySegments(for: token, reading: reading))
            } else {
                appendPlain(token, to: &result)
            }

            cursor = tokenRange.location + tokenRange.length
            tokenType = CFStringTokenizerAdvanceToNextToken(tokenizer)
        }

        if cursor < sourceNSString.length {
            appendPlain(
                sourceNSString.substring(
                    with: NSRange(
                        location: cursor,
                        length: sourceNSString.length - cursor
                    )
                ),
                to: &result
            )
        }

        return result.isEmpty
            ? [JapaneseRubySegment(text: text, reading: nil)]
            : result
    }

    private struct ScriptRun {
        let text: String
        let isKanji: Bool
    }

    private static func rubySegments(
        for token: String,
        reading: String
    ) -> [JapaneseRubySegment] {
        let runs = scriptRuns(in: token)
        guard runs.contains(where: \.isKanji) else {
            return [JapaneseRubySegment(text: token, reading: nil)]
        }
        if runs.allSatisfy(\.isKanji) {
            return [JapaneseRubySegment(text: token, reading: reading)]
        }

        var output: [JapaneseRubySegment] = []
        var remainingReading = reading
        var pendingKanji: String?

        for (index, run) in runs.enumerated() {
            if run.isKanji {
                if pendingKanji != nil {
                    return [JapaneseRubySegment(text: token, reading: reading)]
                }
                pendingKanji = run.text
                continue
            }

            let kana = normalizedKana(in: run.text)
            if let kanji = pendingKanji {
                let hasLaterKanji = runs
                    .dropFirst(index + 1)
                    .contains(where: \.isKanji)
                guard !kana.isEmpty,
                      let anchorRange = remainingReading.range(
                        of: kana,
                        options: hasLaterKanji ? [] : .backwards
                      )
                else {
                    return [JapaneseRubySegment(text: token, reading: reading)]
                }
                let kanjiReading = String(remainingReading[..<anchorRange.lowerBound])
                guard !kanjiReading.isEmpty else {
                    return [JapaneseRubySegment(text: token, reading: reading)]
                }
                output.append(JapaneseRubySegment(text: kanji, reading: kanjiReading))
                appendPlain(run.text, to: &output)
                remainingReading = String(remainingReading[anchorRange.upperBound...])
                pendingKanji = nil
            } else {
                appendPlain(run.text, to: &output)
                if !kana.isEmpty, remainingReading.hasPrefix(kana) {
                    remainingReading.removeFirst(kana.count)
                } else if kana.isEmpty, !remainingReading.isEmpty {
                    return [JapaneseRubySegment(text: token, reading: reading)]
                }
            }
        }

        if let pendingKanji {
            guard !remainingReading.isEmpty else {
                return [JapaneseRubySegment(text: token, reading: reading)]
            }
            output.append(
                JapaneseRubySegment(text: pendingKanji, reading: remainingReading)
            )
        }
        return output
    }

    private static func scriptRuns(in text: String) -> [ScriptRun] {
        var result: [ScriptRun] = []
        for character in text {
            let isKanji = character.unicodeScalars.contains(where: isKanjiScalar)
            if let last = result.last, last.isKanji == isKanji {
                result[result.count - 1] = ScriptRun(
                    text: last.text + String(character),
                    isKanji: isKanji
                )
            } else {
                result.append(ScriptRun(text: String(character), isKanji: isKanji))
            }
        }
        return result
    }

    private static func containsKanji(_ text: String) -> Bool {
        text.unicodeScalars.contains(where: isKanjiScalar)
    }

    private static func isKanjiScalar(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.value {
        case 0x3400 ... 0x4DBF,
             0x4E00 ... 0x9FFF,
             0xF900 ... 0xFAFF,
             0x20000 ... 0x2FA1F:
            return true
        default:
            return false
        }
    }

    private static func hiraganaReading(from latin: String) -> String? {
        latin
            .lowercased()
            .applyingTransform(.latinToHiragana, reverse: false)?
            .replacingOccurrences(of: " ", with: "")
    }

    private static func normalizedKana(in text: String) -> String {
        let hiragana = text.applyingTransform(
            .hiraganaToKatakana,
            reverse: true
        ) ?? text
        return String(
            hiragana.unicodeScalars.filter { scalar in
                (0x3040 ... 0x309F).contains(scalar.value)
            }
        )
    }

    private static func appendPlain(
        _ text: String,
        to segments: inout [JapaneseRubySegment]
    ) {
        guard !text.isEmpty else { return }
        if let last = segments.last, last.reading == nil {
            segments[segments.count - 1] = JapaneseRubySegment(
                text: last.text + text,
                reading: nil
            )
        } else {
            segments.append(JapaneseRubySegment(text: text, reading: nil))
        }
    }
}

/// A free-form Chinese, English and Japanese translator. It deliberately owns
/// its direction, mode and provider choices so experimenting here does not
/// silently change the reader's translation settings.
struct TranslationToolDraft {
    var sourceChoice: TranslationSourceLanguageChoice
    var targetLanguage: LanguageCode
    var mode: StandaloneTranslationMode = .sentence
    var provider: TranslationProviderChoice
    var inputText = ""

    @MainActor init(settings: TranslationSettingsStore) {
        sourceChoice = settings.sourceLanguageChoice
        targetLanguage = settings.targetLanguage
        provider = settings.provider
    }
}

struct TranslateToolView: View {
    @AppStorage(ReaderInterfaceDefaults.japaneseFuriganaKey)
    private var japaneseFuriganaEnabled = false
    @Environment(\.modelContext) private var modelContext
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Query private var lookupRecords: [WordLookupRecord]
    @ObservedObject var translationSettings: TranslationSettingsStore

    @Binding private var sourceChoice: TranslationSourceLanguageChoice
    @Binding private var targetLanguage: LanguageCode
    @Binding private var mode: StandaloneTranslationMode
    @Binding private var provider: TranslationProviderChoice
    @Binding private var inputText: String
    @State private var result: TranslationResult?
    @State private var errorMessage: String?
    @State private var isTranslating = false
    @State private var translateTask: Task<Void, Never>?
    @State private var appleConfiguration: TranslationSession.Configuration?
    @State private var pendingAppleRequest: StandaloneTranslationRequest?
    @State private var activeRequestID: UUID?
    @State private var translationWatchdog = StandaloneTranslationWatchdog()
    @State private var didCopy = false
    @State private var didSaveFavorite = false
    @State private var wordLookupResultID: UUID?
    @State private var wordLookupRequestID: UUID?
    @State private var wordLookupTask: Task<Void, Never>?
    @State private var wordLookupWatchdog = StandaloneTranslationWatchdog(timing: .init(network: .seconds(15)))
    @State private var wordLookupRetryRequest: StandaloneTranslationRequest?
    @State private var isLookingUpWord = false
    @State private var wordLookupErrorMessage: String?
    @State private var referenceDictionaryTerm: ReferenceDictionaryTerm?
    @State private var learningAlert: LearningAlert?
    @FocusState private var isInputFocused: Bool

    private let lexicalLookupService: any LexicalLookupService

    private static let standaloneBookID = UUID(
        uuidString: "00000000-0000-0000-0000-00000000A17E"
    ) ?? UUID()

    init(
        translationSettings: TranslationSettingsStore,
        draft: Binding<TranslationToolDraft>,
        lexicalLookupService: any LexicalLookupService =
            OfflineFirstLexicalLookupService()
    ) {
        self.translationSettings = translationSettings
        self.lexicalLookupService = lexicalLookupService
        _sourceChoice = draft.sourceChoice
        _targetLanguage = draft.targetLanguage
        _provider = draft.provider
        _mode = draft.mode
        _inputText = draft.inputText
    }

    private var trimmedInput: String {
        inputText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var canTranslate: Bool {
        !trimmedInput.isEmpty
            && !isTranslating
            && trimmedInput.count <= mode.maximumCharacterCount
    }

    private var wordLookupResult: WordLookupRecord? {
        guard let wordLookupResultID else { return nil }
        return lookupRecords.first { $0.id == wordLookupResultID }
    }

    /// The page fills the room between the section bar and the tab bar
    /// exactly: the translator card takes whatever height is left over, and a
    /// long result or dictionary entry scrolls inside the card, so the page
    /// itself never moves. With the keyboard up, large text or a short screen
    /// it scrolls as usual.
    var body: some View {
        OneScreenPage(identifier: "standalone-translation-page") { _ in
            FillStack {
                modeAndProviderBar
                translatorCard
                    .padding(.top, 14)
                    .stretch(.infinity)
                    .squeezes()
                if let errorMessage {
                    errorCard(errorMessage)
                        .padding(.top, 12)
                }
                providerHelp
                    .padding(.top, 12)
                    .shrinks()
            }
            .padding(.horizontal, JerreaderTheme.pagePadding)
            .padding(.vertical, 16)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .translationTask(appleConfiguration) { session in
            await performAppleTranslation(
                using: AppleTranslationService(session: session)
            )
        }
        .sheet(item: $referenceDictionaryTerm) { item in
            SystemDictionaryView(term: item.term)
                .ignoresSafeArea()
        }
        .alert(item: $learningAlert) { item in
            Alert(
                title: Text(item.title),
                message: Text(item.message),
                dismissButton: .default(Text("好"))
            )
        }
        .onChange(of: mode) { cancelTranslation(clearResult: true) }
        .onChange(of: provider) { clearStaleOutput() }
        .onChange(of: inputText) { clearStaleOutput() }
        .onChange(of: sourceChoice) { clearStaleOutput() }
        .onChange(of: targetLanguage) { clearStaleOutput() }
        .onDisappear {
            isInputFocused = false
            cancelTranslation(clearResult: false)
            cancelWordLookup(clearResult: false)
        }
    }

    private var modeAndProviderBar: some View {
        VStack(alignment: .leading, spacing: 8) {
            Picker("翻译模式", selection: $mode) {
                ForEach(StandaloneTranslationMode.allCases) { option in
                    Label(option.title, systemImage: option.symbol)
                        .tag(option)
                }
            }
            .pickerStyle(.segmented)
            .frame(maxWidth: .infinity, minHeight: 44)
            .accessibilityIdentifier("standalone-translation-mode")

            Menu {
                ForEach(TranslationProviderChoice.allCases) { choice in
                    Button {
                        provider = choice
                    } label: {
                        if provider == choice {
                            Label(choice.title, systemImage: "checkmark")
                        } else {
                            Text(choice.title)
                        }
                    }
                }
            } label: {
                HStack(spacing: 6) {
                    Label("服务", systemImage: providerSymbol)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Spacer(minLength: 8)
                    Text(provider.shortTitle)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    JerreaderSymbol(name: "chevron.down", size: 13)
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, 4)
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
                .overlay(alignment: .bottom) {
                    Rectangle()
                        .fill(JerreaderTheme.line)
                        .frame(height: 0.5)
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("翻译服务：\(provider.title)")
            .accessibilityIdentifier("standalone-translation-provider")
        }
    }

    /// Spare height goes mostly to the result: the input grows a little, the
    /// output takes the rest. When height is short, the output gives way.
    private var translatorCard: some View {
        FillStack {
            languageBar

            Divider()

            sourceInput
                .stretch(30)

            Divider()
                .padding(.horizontal, 14)

            translationOutput
                .stretch(.infinity)
                .squeezes()
        }
        .background(
            JerreaderTheme.paper,
            in: RoundedRectangle(cornerRadius: 16, style: .continuous)
        )
        // The language bar's tint follows the card's rounded corners.
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(JerreaderTheme.line, lineWidth: 0.8)
        }
    }

    /// Shown under the result, inside the card's scrolling output.
    @ViewBuilder
    private var wordLookupContent: some View {
        if mode == .word, isLookingUpWord {
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Text("正在补充词典形、读音和释义…")
            }
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .background(
                    JerreaderTheme.mutedSurface.opacity(0.72),
                    in: RoundedRectangle(cornerRadius: 14, style: .continuous)
                )
        } else if mode == .word, let wordLookupResult {
            VStack(alignment: .leading, spacing: 10) {
                Label("词典详情", systemImage: "character.book.closed")
                    .font(.headline)
                    .foregroundStyle(JerreaderTheme.accent)

                WordRecordCard(
                    record: wordLookupResult,
                    onSpeak: { speakWordRecord(wordLookupResult) },
                    onCopy: { copyWordRecord(wordLookupResult) },
                    onToggleFavorite: {
                        toggleWordFavorite(wordLookupResult)
                    }
                )

                Text("词典结果已自动记入历史；收藏后会保留在生词本。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 4)
            }
        } else if mode == .word, let wordLookupErrorMessage {
            VStack(alignment: .leading, spacing: 10) {
                Label(wordLookupErrorMessage, systemImage: "book.closed")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)

                if let request = wordLookupRetryRequest {
                    Button("重试词典查询") { beginWordLookup(for: request) }
                        .font(.subheadline.weight(.semibold))
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("standalone-retry-dictionary")
                }

                if UIReferenceLibraryViewController.dictionaryHasDefinition(
                    forTerm: trimmedInput
                ) {
                    Button {
                        referenceDictionaryTerm = ReferenceDictionaryTerm(
                            term: trimmedInput
                        )
                    } label: {
                        Label("打开 iOS 系统词典", systemImage: "books.vertical")
                    }
                    .font(.subheadline.weight(.semibold))
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(
                JerreaderTheme.mutedSurface.opacity(0.72),
                in: RoundedRectangle(cornerRadius: 14, style: .continuous)
            )
        }
    }

    private var languageBar: some View {
        HStack(spacing: 8) {
            languageMenu(
                title: sourceChoice.title,
                accessibilityLabel: "原文语言"
            ) {
                ForEach(TranslationSourceLanguageChoice.allCases) { choice in
                    Button {
                        sourceChoice = choice
                    } label: {
                        if sourceChoice == choice {
                            Label(choice.title, systemImage: "checkmark")
                        } else {
                            Text(choice.title)
                        }
                    }
                }
            }

            Button(action: swapLanguages) {
                JerreaderSymbol(name: "arrow.left.arrow.right", size: 18)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(
                        canSwapLanguages ? JerreaderTheme.accent : Color.secondary
                    )
                    .frame(width: 44, height: 44)
                    .background(JerreaderTheme.accentFill.opacity(0.70), in: Circle())
            }
            .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.9))
            .disabled(!canSwapLanguages)
            .accessibilityLabel("互换原文与译文语言")

            languageMenu(
                title: targetLanguage.displayName,
                accessibilityLabel: "译文语言"
            ) {
                ForEach(LanguageCode.allCases, id: \.self) { language in
                    Button {
                        targetLanguage = language
                    } label: {
                        if targetLanguage == language {
                            Label(language.displayName, systemImage: "checkmark")
                        } else {
                            Text(language.displayName)
                        }
                    }
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(JerreaderTheme.mutedSurface.opacity(0.72))
    }

    private func languageMenu<Content: View>(
        title: String,
        accessibilityLabel: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        Menu(content: content) {
            HStack(spacing: 5) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                JerreaderSymbol(name: "chevron.down", size: 12)
            }
            .foregroundStyle(.primary)
            .frame(maxWidth: .infinity)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(accessibilityLabel)：\(title)")
    }

    private var sourceInput: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField(mode.placeholder, text: $inputText, axis: .vertical)
                .lineLimit(mode == .word ? 2 ... 4 : 3 ... 10)
                .focused($isInputFocused)
                .font(.body)
                .accessibilityIdentifier("standalone-translation-input")

            Spacer(minLength: 0)

            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) {
                    inputCount
                    Spacer()
                    inputActions
                }
                VStack(alignment: .leading, spacing: 6) {
                    inputCount
                    inputActions
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
            }
        }
        .padding(16)
        // The area takes the height the card hands it; the count and the
        // translate button stay at its foot.
        .frame(
            maxWidth: .infinity,
            minHeight: mode == .word ? 118 : 142,
            maxHeight: .infinity,
            alignment: .top
        )
        .background {
            // A tap on the empty part of the area starts typing, as a tap on
            // the field would. The field and the buttons sit in front of it.
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture { isInputFocused = true }
                .accessibilityHidden(true)
        }
    }

    private var translationOutput: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                JerreaderLabel(title: targetLanguage.displayName, icon: "text.bubble", size: 15)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(JerreaderTheme.accent)
                Spacer()
                if result?.sourceLanguage == .japanese || targetLanguage == .japanese {
                    Toggle("日语注音", isOn: $japaneseFuriganaEnabled)
                        .toggleStyle(.button).font(.caption)
                        .accessibilityIdentifier("japanese-furigana-toggle")
                }
                if result?.isFromCache == true {
                    Text("缓存")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 16)

            // A long result or dictionary entry scrolls here; the page stays
            // put. The scroll area spans the card's width so the dictionary
            // card's shadow is not cut off at its sides.
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    outputText
                    wordLookupContent
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.bottom, 4)
            }
            .scrollBounceBehavior(.basedOnSize)

            if let result {
                resultActions(result)
                    .padding(.horizontal, 16)
            }
        }
        .padding(.vertical, 16)
        .frame(
            maxWidth: .infinity,
            minHeight: mode == .word ? 104 : 126,
            maxHeight: .infinity,
            alignment: .topLeading
        )
        .animation(
            reduceMotion ? nil : JerreaderMotion.stateChange,
            value: result?.translatedText
        )
    }

    @ViewBuilder
    private var outputText: some View {
        if let result {
            if japaneseFuriganaEnabled, result.sourceLanguage == .japanese {
                Text("原文注音").font(.caption).foregroundStyle(.secondary)
                JapaneseFuriganaText(text: result.sourceText)
                    .accessibilityIdentifier("japanese-source-furigana")
            }
            if japaneseFuriganaEnabled, targetLanguage == .japanese {
                JapaneseFuriganaText(text: result.translatedText)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("standalone-translation-result")
            } else {
                Text(result.translatedText)
                    .font(.body)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("standalone-translation-result")
            }
        } else if isTranslating {
            HStack(spacing: 9) {
                ProgressView().controlSize(.small)
                Text(
                    mode == .word
                        ? "正在查词或准备翻译…"
                        : provider == .apple
                        ? "正在准备 Apple 翻译…"
                        : "正在请求 AI 翻译…"
                )
                .foregroundStyle(.secondary)
            }
            .font(.subheadline)
        } else {
            Text("译文会显示在这里")
                .font(.body)
                .foregroundStyle(.secondary)
        }
    }

    private var inputCount: some View {
        Text("\(trimmedInput.count) / \(mode.maximumCharacterCount)")
            .font(.caption.monospacedDigit())
            .foregroundStyle(
                trimmedInput.count > mode.maximumCharacterCount
                    ? Color.red
                    : .secondary
            )
    }

    private var inputActions: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 6))
            : AnyLayout(HStackLayout(spacing: 10))
        return layout {
            if !inputText.isEmpty {
                Button("清空") {
                    inputText = ""
                    cancelTranslation(clearResult: true)
                }
                .font(.subheadline)
                .frame(minHeight: 44)
            }

            Button(action: translate) {
                HStack(spacing: 6) {
                    if isTranslating {
                        ProgressView()
                            .controlSize(.small)
                            .tint(JerreaderTheme.onPrimaryAction)
                    }
                    Text(isTranslating ? "翻译中" : "翻译")
                        .font(.subheadline.weight(.semibold))
                }
                .foregroundStyle(JerreaderTheme.onPrimaryAction)
                .padding(.horizontal, 18)
                .frame(minHeight: 44)
                .background(
                    canTranslate
                        ? AnyShapeStyle(JerreaderTheme.primaryAction)
                        : AnyShapeStyle(Color.gray.opacity(0.55)),
                    in: RoundedRectangle(
                        cornerRadius: 12,
                        style: .continuous
                    )
                )
            }
            .buttonStyle(JerreaderPressButtonStyle(pressedScale: 0.96))
            .disabled(!canTranslate)
            .accessibilityIdentifier("standalone-translate-button")
        }
        .fixedSize(horizontal: true, vertical: false)
    }

    private func resultActions(_ result: TranslationResult) -> some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 6))
            : AnyLayout(HStackLayout(spacing: 18))
        return layout {
            Button {
                UIPasteboard.general.string = result.translatedText
                didCopy = true
            } label: {
                Label(
                    didCopy ? "已复制" : "复制",
                    systemImage: didCopy ? "checkmark" : "doc.on.doc"
                )
                .font(.subheadline)
                .frame(minHeight: 44)
            }

            Button {
                saveToFavorites(result)
            } label: {
                Label(
                    didSaveFavorite ? "已收藏" : "收藏",
                    systemImage: didSaveFavorite ? "star.fill" : "star"
                )
                .font(.subheadline)
                .frame(minHeight: 44)
            }

        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .buttonStyle(.plain)
        .foregroundStyle(JerreaderTheme.accent)
    }

    private var providerHelp: some View {
        Label(providerDescription, systemImage: providerSymbol)
            .font(.caption)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 2)
            .accessibilityIdentifier("standalone-translation-provider-help")
    }

    private func errorCard(_ message: String) -> some View {
        Label(message, systemImage: "exclamationmark.triangle")
            .font(.subheadline)
            .foregroundStyle(.orange)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(
                Color.orange.opacity(0.10),
                in: RoundedRectangle(cornerRadius: 14, style: .continuous)
            )
    }

    private var canSwapLanguages: Bool {
        sourceChoice.languageCode != nil
    }

    private func swapLanguages() {
        guard let source = sourceChoice.languageCode else { return }
        withAnimation(reduceMotion ? nil : JerreaderMotion.stateChange) {
            sourceChoice = TranslationSourceLanguageChoice.allCases.first {
                $0.languageCode == targetLanguage
            } ?? .automatic
            targetLanguage = source
            if let result {
                inputText = result.translatedText
            }
            clearStaleOutput()
        }
    }

    private var providerSymbol: String {
        switch provider {
        case .apple: return "apple.logo"
        case .directAPI: return "sparkles"
        case .backendProxy: return "network"
        }
    }

    private var providerDescription: String {
        switch provider {
        case .apple:
            return "Apple 系统翻译；首次使用某个语言方向时会提示下载语言包，下载后可离线使用。"
        case .directAPI:
            let entry = translationSettings.aiProviders.active
            let model = entry.model.trimmingCharacters(in: .whitespacesAndNewlines)
            let name = model.isEmpty ? entry.displayName : "\(entry.displayName) · \(model)"
            return "AI 大模型 · \(name)；可在「设置 → AI 大模型」换模型或添加供应商。"
        case .backendProxy:
            return "AI 代理；使用你在「设置 → 翻译与 AI」中配置的 HTTPS 服务。"
        }
    }

    private func translate() {
        isInputFocused = false
        cancelTranslation(clearResult: true)

        let request: StandaloneTranslationRequest
        do {
            request = try StandaloneTranslationRequestPolicy.makeRequest(
                text: inputText,
                sourceChoice: sourceChoice,
                targetLanguage: targetLanguage,
                mode: mode
            )
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
                ?? "无法开始翻译，请检查输入和语言方向。"
            return
        }

        result = nil
        activeRequestID = request.id
        isTranslating = true
        translateTask = Task { @MainActor in
            if request.mode == .word {
                let lexicon: JapaneseChineseLexicon?
                if (request.sourceLanguage == .japanese && request.targetLanguage == .simplifiedChinese) ||
                   (request.sourceLanguage == .simplifiedChinese && request.targetLanguage == .japanese) {
                    lexicon = await Task.detached(priority: .userInitiated) { JapaneseChineseLexicon.shared }.value
                    guard !Task.isCancelled, activeRequestID == request.id else { return }
                } else { lexicon = nil }
                let translated: String?
                if request.sourceLanguage == .japanese, request.targetLanguage == .simplifiedChinese {
                    translated = lexicon?.japanese(request.text,
                        candidates: WiktionaryLexicalLookupService.lookupCandidates(for: request.text, language: .japanese))?.definition
                } else if request.sourceLanguage == .simplifiedChinese, request.targetLanguage == .japanese {
                    translated = lexicon?.chinese(request.text)
                } else { translated = nil }
                if let translated {
                    guard !Task.isCancelled, activeRequestID == request.id else { return }
                    result = TranslationResult(sourceText: request.text, translatedText: translated,
                        sourceLanguage: request.sourceLanguage, targetLanguage: request.targetLanguage,
                        providerIdentifier: "中日词典 · 离线", providerVersion: JapaneseChineseLexicon.version, isFromCache: false)
                    isTranslating = false; activeRequestID = nil
                    beginWordLookup(for: request)
                    return
                }
            }
            if request.mode == .word, request.sourceLanguage == .english,
               request.targetLanguage == .simplifiedChinese {
                let candidates = WiktionaryLexicalLookupService.lookupCandidates(for: request.text, language: .english)
                let entry = await OfflineDictionaryPackageStore.shared.lookup(candidates: candidates)
                guard !Task.isCancelled, activeRequestID == request.id else { return }
                if let entry {
                    result = TranslationResult(sourceText: request.text, translatedText: entry.definition,
                        sourceLanguage: .english, targetLanguage: .simplifiedChinese,
                        providerIdentifier: "ECDICT 离线词库", providerVersion: OfflineDictionaryManifest.version, isFromCache: false)
                    isTranslating = false; activeRequestID = nil
                    beginWordLookup(for: request)
                    return
                }
            }
            guard !Task.isCancelled, activeRequestID == request.id else { return }
            startConfiguredTranslation(request)
        }
    }

    private func startConfiguredTranslation(_ request: StandaloneTranslationRequest) {
        translationWatchdog.start(
            id: request.id,
            systemTranslation: provider == .apple,
            restart: {
                guard activeRequestID == request.id else { return }
                appleConfiguration?.invalidate()
            },
            timedOut: { timeout in
                guard activeRequestID == request.id else { return }
                cancelTranslation(clearResult: true)
                errorMessage = timeout.message
            }
        )

        switch provider {
        case .apple:
            pendingAppleRequest = request
            appleConfiguration = TranslationSession.Configuration(
                source: request.sourceLanguage.localeLanguage,
                target: request.targetLanguage.localeLanguage
            )

        case .directAPI, .backendProxy:
            guard let service = resolvedAIService(for: provider, mode: mode) else {
                translationWatchdog.complete(id: request.id)
                isTranslating = false
                activeRequestID = nil
                errorMessage = provider == .directAPI
                    ? "AI 大模型还没设置好，请先到「设置 → AI 大模型」填写 API Key 并选择模型。"
                    : "AI 代理尚未配置完成，请先到「设置 → 翻译与 AI」填写 HTTPS 地址。"
                return
            }
            translateTask = Task { @MainActor in
                await performTranslation(request, using: service)
            }
        }
    }

    private func resolvedAIService(
        for provider: TranslationProviderChoice,
        mode: StandaloneTranslationMode
    ) -> (any TranslationService)? {
        switch provider {
        case .apple:
            return nil
        case .directAPI:
            guard let base = translationSettings.directAPIConfiguration else {
                return nil
            }
            let configuration = DirectAITranslationConfiguration(
                provider: base.provider,
                endpoint: base.endpoint,
                model: base.model,
                apiKey: base.apiKey,
                style: base.style,
                reasoning: base.reasoning,
                translationPromptTemplate: mode.translationPrompt(
                    fallback: base.translationPromptTemplate
                ),
                grammarAnalysisPromptTemplate:
                    base.grammarAnalysisPromptTemplate
            )
            return DirectAITranslationService(configuration: configuration)
        case .backendProxy:
            guard let base = translationSettings.backendConfiguration else {
                return nil
            }
            let configuration = BackendTranslationConfiguration(
                endpoint: base.endpoint,
                model: base.model,
                accessToken: base.accessToken,
                translationPromptTemplate: mode.translationPrompt(
                    fallback: base.translationPromptTemplate
                ),
                grammarAnalysisPromptTemplate:
                    base.grammarAnalysisPromptTemplate
            )
            return BackendTranslationService(configuration: configuration)
        }
    }

    private func performAppleTranslation(
        using service: any TranslationService
    ) async {
        guard let request = pendingAppleRequest,
              activeRequestID == request.id
        else { return }
        translationWatchdog.sessionDidStart(id: request.id)
        await performTranslation(request, using: service)
        if pendingAppleRequest?.id == request.id {
            pendingAppleRequest = nil
            appleConfiguration = nil
        }
    }

    private func performTranslation(
        _ request: StandaloneTranslationRequest,
        using service: any TranslationService
    ) async {
        defer {
            translationWatchdog.complete(id: request.id)
            if activeRequestID == request.id {
                isTranslating = false
                activeRequestID = nil
            }
        }
        do {
            let output = try await service.translate(
                text: request.text,
                sourceLanguage: request.sourceLanguage,
                targetLanguage: request.targetLanguage
            )
            guard !Task.isCancelled, activeRequestID == request.id else {
                return
            }
            withAnimation(reduceMotion ? nil : JerreaderMotion.stateChange) {
                result = output
            }
            errorMessage = nil
            beginWordLookup(for: request)
        } catch is CancellationError {
            return
        } catch {
            guard !Task.isCancelled, activeRequestID == request.id else {
                return
            }
            errorMessage = (error as? LocalizedError)?.errorDescription
                ?? "翻译没有完成，请稍后重试。"
        }
    }

    private func clearStaleOutput() {
        cancelTranslation(clearResult: true)
        errorMessage = nil
    }

    private func cancelTranslation(clearResult: Bool) {
        translationWatchdog.cancel()
        translateTask?.cancel()
        translateTask = nil
        pendingAppleRequest = nil
        appleConfiguration = nil
        activeRequestID = nil
        isTranslating = false
        errorMessage = nil
        if clearResult {
            result = nil
            didCopy = false
            didSaveFavorite = false
            cancelWordLookup(clearResult: true)
        }
    }

    private func beginWordLookup(for request: StandaloneTranslationRequest) {
        cancelWordLookup(clearResult: true)
        guard StandaloneLexicalLookupPolicy.supports(request) else { return }

        let currentRequestID = UUID()
        wordLookupRequestID = currentRequestID
        wordLookupRetryRequest = request
        isLookingUpWord = true
        let service = lexicalLookupService
        wordLookupWatchdog.start(id: currentRequestID, systemTranslation: false, restart: {}) { _ in
            guard wordLookupRequestID == currentRequestID else { return }
            cancelWordLookup(clearResult: false)
            wordLookupErrorMessage = "词典查询等待超时，可重试；已有译文仍可使用。"
        }

        wordLookupTask = Task { @MainActor in
            defer {
                wordLookupWatchdog.complete(id: currentRequestID)
                if wordLookupRequestID == currentRequestID {
                    isLookingUpWord = false
                    wordLookupTask = nil
                    wordLookupRequestID = nil
                }
            }
            do {
                let explanation = try await service.lookup(
                    word: request.text,
                    sentenceContext: nil,
                    language: request.sourceLanguage
                )
                try Task.checkCancellation()
                guard wordLookupRequestID == currentRequestID else { return }
                let record = try WordLookupStore.record(
                    explanation,
                    in: modelContext
                )
                wordLookupResultID = record.id
                wordLookupErrorMessage = nil
            } catch is CancellationError {
                return
            } catch {
                guard wordLookupRequestID == currentRequestID else { return }
                wordLookupErrorMessage =
                    (error as? LocalizedError)?.errorDescription
                    ?? "暂时无法获取词典详情，译文仍可正常使用。"
            }
        }
    }

    private func cancelWordLookup(clearResult: Bool) {
        wordLookupWatchdog.cancel()
        wordLookupTask?.cancel()
        wordLookupTask = nil
        wordLookupRequestID = nil
        isLookingUpWord = false
        if clearResult {
            wordLookupRetryRequest = nil
            wordLookupResultID = nil
            wordLookupErrorMessage = nil
        }
    }

    private func copyWordRecord(_ record: WordLookupRecord) {
        UIPasteboard.general.string = record.copyText
        learningAlert = LearningAlert(
            title: "已复制",
            message: "词典详情已复制到剪贴板。"
        )
    }

    private func speakWordRecord(_ record: WordLookupRecord) {
        Task { @MainActor in
            do {
                try await SystemSpeechService.shared.speak(
                    record.reading ?? record.surfaceForm,
                    language: record.language
                )
            } catch {
                learningAlert = LearningAlert(
                    title: "无法播放发音",
                    message: (error as? LocalizedError)?.errorDescription
                        ?? "系统语音暂时不可用。"
                )
            }
        }
    }

    private func toggleWordFavorite(_ record: WordLookupRecord) {
        do {
            try WordLookupStore.setFavorite(
                !record.isFavorite,
                for: record,
                in: modelContext
            )
        } catch {
            learningAlert = LearningAlert(
                title: "无法更新收藏",
                message: (error as? LocalizedError)?.errorDescription
                    ?? "请稍后重试。"
            )
        }
    }

    private func saveToFavorites(_ result: TranslationResult) {
        let store = TranslationFavoriteStore(modelContext: modelContext)
        do {
            _ = try store.setFavorite(
                true,
                bookID: Self.standaloneBookID,
                bookTitle: "翻译工具",
                locatorJSON: nil,
                result: result
            )
            didSaveFavorite = true
        } catch {
            errorMessage = (error as? LocalizedError)?.errorDescription
                ?? "收藏没有保存成功，请稍后重试。"
        }
    }
}

struct JapaneseFuriganaText: UIViewRepresentable {
    let text: String

    func makeUIView(context: Context) -> UITextView {
        let textView = UITextView()
        textView.backgroundColor = .clear
        textView.isEditable = false
        textView.isSelectable = true
        textView.isScrollEnabled = false
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.adjustsFontForContentSizeCategory = false
        textView.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        textView.accessibilityLabel = text
        return textView
    }

    func updateUIView(_ textView: UITextView, context: Context) {
        textView.attributedText = attributedText
        textView.accessibilityLabel = text
    }

    func sizeThatFits(
        _ proposal: ProposedViewSize,
        uiView: UITextView,
        context: Context
    ) -> CGSize? {
        guard let width = proposal.width else { return nil }
        let fittingSize = uiView.sizeThatFits(
            CGSize(width: width, height: .greatestFiniteMagnitude)
        )
        return CGSize(width: width, height: ceil(fittingSize.height))
    }

    private var attributedText: NSAttributedString {
        let bodyFont = UIFontMetrics(forTextStyle: .body).scaledFont(
            for: .systemFont(ofSize: 17), maximumPointSize: 19
        )
        let paragraphStyle = NSMutableParagraphStyle()
        paragraphStyle.lineSpacing = max(5, bodyFont.pointSize * 0.28)
        paragraphStyle.paragraphSpacing = 3

        let output = NSMutableAttributedString()
        for segment in JapaneseFuriganaFormatter.segments(for: text) {
            let attributedSegment = NSMutableAttributedString(
                string: segment.text,
                attributes: [
                    .font: bodyFont,
                    .foregroundColor: UIColor.label,
                    .paragraphStyle: paragraphStyle,
                ]
            )
            if let reading = segment.reading, !reading.isEmpty {
                let rubyString = reading as CFString
                var rubyText = [Unmanaged<CFString>?](
                    repeating: nil,
                    count: 4
                )
                rubyText[Int(CTRubyPosition.before.rawValue)] =
                    Unmanaged.passUnretained(rubyString)
                let annotation = CTRubyAnnotationCreate(
                    .auto,
                    .auto,
                    0.48,
                    &rubyText
                )
                attributedSegment.addAttribute(
                    NSAttributedString.Key(
                        kCTRubyAnnotationAttributeName as String
                    ),
                    value: annotation,
                    range: NSRange(location: 0, length: attributedSegment.length)
                )
            }
            output.append(attributedSegment)
        }
        return output
    }
}
