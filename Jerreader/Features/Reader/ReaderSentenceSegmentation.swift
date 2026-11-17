import Foundation
import NaturalLanguage

// Paragraph and sentence segmentation shared by quick sentence translation,
// speech highlighting and cross-page context.

struct ReaderSentenceSegment: Equatable, Sendable {
    let text: String
    let contextText: String
    let utf16Range: Range<Int>
}

/// Returns exactly the containing EPUB block reported by the bridge, trimming
/// source indentation while preserving the original UTF-16 range used to draw
/// the non-destructive overlay.
enum ReaderParagraphSegmenter {
    static func paragraph(
        in source: String,
        utf16Offset: Int
    ) -> ReaderSentenceSegment? {
        guard !source.isEmpty else { return nil }
        let clampedOffset = min(max(utf16Offset, 0), source.utf16.count)
        guard clampedOffset <= source.utf16.count else { return nil }

        var lower = source.startIndex
        var upper = source.endIndex
        while lower < upper, source[lower].isWhitespace {
            lower = source.index(after: lower)
        }
        while lower < upper {
            let previous = source.index(before: upper)
            guard source[previous].isWhitespace else { break }
            upper = previous
        }
        guard lower < upper else { return nil }
        let lowerUTF16 = lower.utf16Offset(in: source)
        let upperUTF16 = upper.utf16Offset(in: source)
        // A hit just inside source indentation still belongs to this block.
        guard clampedOffset <= source.utf16.count else { return nil }
        let text = TranslationCacheStore.normalizedText(
            String(source[lower ..< upper])
        )
        guard !text.isEmpty else { return nil }
        return ReaderSentenceSegment(
            text: text,
            contextText: text,
            utf16Range: lowerUTF16 ..< upperUTF16
        )
    }
}

/// Resolves the sentence touched inside a paragraph without changing the EPUB
/// document. JavaScript only reports the paragraph and caret offset; language-
/// aware boundary decisions stay in native, testable code.
enum ReaderSentenceSegmenter {
    static let maximumContextCharacterCount = 1_200

    static func sentence(
        in paragraph: String,
        utf16Offset: Int,
        language: LanguageCode?
    ) -> ReaderSentenceSegment? {
        guard !paragraph.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }

        // EPUB source often contains indentation and hard wraps inside one
        // visual paragraph. NLTokenizer treats those raw line breaks as hard
        // sentence ends, so analyze an equal-length whitespace projection and
        // map its UTF-16 range back to the untouched publication text.
        let analysisText = sentenceAnalysisText(paragraph)
        let clampedOffset = min(max(utf16Offset, 0), analysisText.utf16.count)
        guard let caret = stringIndex(atUTF16Offset: clampedOffset, in: analysisText) else {
            return nil
        }

        let tokenizer = NLTokenizer(unit: .sentence)
        tokenizer.string = analysisText
        if let language {
            switch language {
            case .japanese: tokenizer.setLanguage(.japanese)
            case .english: tokenizer.setLanguage(.english)
            case .simplifiedChinese: tokenizer.setLanguage(.simplifiedChinese)
            }
        }

        var ranges: [Range<String.Index>] = []
        tokenizer.enumerateTokens(in: analysisText.startIndex ..< analysisText.endIndex) { range, _ in
            ranges.append(range)
            return true
        }
        if language == .english {
            ranges = refinedEnglishRanges(ranges, in: analysisText)
        } else if language == .japanese {
            ranges = refinedJapaneseRanges(ranges, in: analysisText)
        }

        let selectedRange = range(containing: caret, in: ranges)
            ?? fallbackRange(containing: caret, in: analysisText)
        guard let trimmedRange = trimmed(selectedRange, in: analysisText),
              !trimmedRange.isEmpty
        else {
            return nil
        }

        let lowerUTF16 = trimmedRange.lowerBound.utf16Offset(in: analysisText)
        let upperUTF16 = trimmedRange.upperBound.utf16Offset(in: analysisText)
        guard let originalLower = stringIndex(atUTF16Offset: lowerUTF16, in: paragraph),
              let originalUpper = stringIndex(atUTF16Offset: upperUTF16, in: paragraph)
        else {
            return nil
        }
        let originalRange = originalLower ..< originalUpper
        let text = TranslationCacheStore.normalizedText(String(paragraph[originalRange]))
        guard !text.isEmpty else { return nil }

        return ReaderSentenceSegment(
            text: text,
            contextText: contextWindow(
                in: paragraph,
                sentenceRange: originalRange,
                sentenceText: text
            ),
            utf16Range: lowerUTF16 ..< upperUTF16
        )
    }

    private static func sentenceAnalysisText(_ text: String) -> String {
        var result = ""
        result.reserveCapacity(text.utf8.count)
        for scalar in text.unicodeScalars {
            switch scalar.value {
            case 0x0009 ... 0x000D, 0x0085, 0x2028, 0x2029:
                // Every replaced scalar occupies one UTF-16 code unit, just
                // like U+0020, so WebKit offsets remain exactly aligned.
                result.unicodeScalars.append(UnicodeScalar(0x20)!)
            default:
                result.unicodeScalars.append(scalar)
            }
        }
        return result
    }

    private static func range(
        containing caret: String.Index,
        in ranges: [Range<String.Index>]
    ) -> Range<String.Index>? {
        if let direct = ranges.first(where: { $0.contains(caret) }) {
            return direct
        }
        if let following = ranges.first(where: { $0.lowerBound >= caret }) {
            return following
        }
        return ranges.last
    }

    /// NaturalLanguage handles most English punctuation well, but iOS 18 can
    /// split personal initials ("J. K. Rowling") and can merge a following
    /// sentence after an initialism ("the U.S. He ..."). Keep its linguistic
    /// ranges as the source of truth, then repair only these narrow ambiguous
    /// boundaries.
    private static func refinedEnglishRanges(
        _ systemRanges: [Range<String.Index>],
        in text: String
    ) -> [Range<String.Index>] {
        let splitRanges = systemRanges.flatMap { splitMissedEnglishBoundaries(in: $0, text: text) }
        var merged: [Range<String.Index>] = []

        for range in splitRanges where !range.isEmpty {
            if let previous = merged.last,
               shouldMergeEnglishBoundary(previous: previous, next: range, in: text)
            {
                merged[merged.count - 1] = previous.lowerBound ..< range.upperBound
            } else {
                merged.append(range)
            }
        }
        return merged
    }

    /// NaturalLanguage can split immediately after a question or exclamation
    /// inside Japanese quotation marks, even when the following quotative
    /// particle still belongs to the same sentence: `「本当？」と彼は聞いた。`.
    /// It also misses the end of a spoken line that has no 。. Split at those
    /// closing quotes first, then repair the quotative boundaries, so a
    /// genuinely new sentence beginning after `」` or `』` is not swallowed.
    private static func refinedJapaneseRanges(
        _ systemRanges: [Range<String.Index>],
        in text: String
    ) -> [Range<String.Index>] {
        let speechBoundaries = japaneseSpeechBoundaries(in: text)
        let splitRanges = systemRanges.flatMap { range in
            split(range, at: speechBoundaries)
        }
        var merged: [Range<String.Index>] = []
        for range in splitRanges where !range.isEmpty {
            if let previous = merged.last,
               shouldMergeJapaneseQuotedBoundary(
                   previous: previous,
                   next: range,
                   in: text
               )
            {
                merged[merged.count - 1] = previous.lowerBound ..< range.upperBound
            } else {
                merged.append(range)
            }
        }
        return merged
    }

    /// NaturalLanguage only ends a Japanese sentence after 。！？, but dialogue
    /// closes its quote without one: `「待てよ」「どういうことだ」` and
    /// `「待てよ」敦也がいった。` came back as one "sentence". Readium's
    /// selection context also joins neighbouring paragraphs with only a line
    /// break, which the analysis turns into a space, so a run of spoken lines
    /// became one "sentence" several paragraphs long, and a sentence tap or a
    /// smart selection translated all of it. Like Android's
    /// `ReaderSentenceSegmenter`, a matched closing quote ends the sentence
    /// unless punctuation or a particle carries it on.
    private static func japaneseSpeechBoundaries(in text: String) -> [String.Index] {
        let pairs: [Character: Character] = ["「": "」", "『": "』", "“": "”", "‘": "’"]
        var openers: [Character] = []
        var boundaries: [String.Index] = []
        var index = text.startIndex
        while index < text.endIndex {
            let character = text[index]
            if let opener = openers.last, pairs[opener] == character {
                openers.removeLast()
                var next = text.index(after: index)
                while next < text.endIndex,
                      text[next].isWhitespace || japaneseClosingMarks.contains(text[next])
                {
                    next = text.index(after: next)
                }
                if next < text.endIndex,
                   !japaneseQuoteFollowers.contains(text[next]),
                   !continuesAfterJapaneseQuote(text[next...])
                {
                    boundaries.append(next)
                }
            } else if pairs[character] != nil {
                openers.append(character)
            }
            index = text.index(after: index)
        }
        return boundaries
    }

    private static let japaneseClosingMarks = "」』”’)）】》"

    /// Punctuation right after a closing quote: `「待てよ」。` ends at the
    /// mark, and `「待てよ」、と…` keeps going.
    private static let japaneseQuoteFollowers = "。．.！!？?、，,…‥・：:；;"

    private static func split(
        _ range: Range<String.Index>,
        at boundaries: [String.Index]
    ) -> [Range<String.Index>] {
        var pieces: [Range<String.Index>] = []
        var start = range.lowerBound
        for boundary in boundaries where boundary > start && boundary < range.upperBound {
            pieces.append(start ..< boundary)
            start = boundary
        }
        pieces.append(start ..< range.upperBound)
        return pieces
    }

    /// Whether the text after a closing quote still belongs to the sentence:
    /// a quotative (`「本当？」と`, `って`) or a particle that makes the quote a
    /// noun (`「待てよ」の一言`, `「はい」を`, `「はい」だった`). Same lists as
    /// Android's `ReaderSentenceSegmenter`.
    private static func continuesAfterJapaneseQuote(_ continuation: Substring) -> Bool {
        if japaneseIndependentStarters.contains(where: { continuation.hasPrefix($0) }) {
            return false
        }
        return japaneseQuoteParticles.contains { continuation.hasPrefix($0) }
    }

    private static let japaneseQuoteParticles = [
        "って", "と", "など", "なんて", "なんか", "の", "を", "が", "は", "も",
        "に", "へ", "で", "や", "から", "まで", "より", "だ", "じゃ", "しか",
        "ばかり", "くらい", "ぐらい", "こそ", "さえ", "すら",
    ]

    /// Words that begin a new sentence although they start like a particle.
    private static let japaneseIndependentStarters = [
        "とても", "ところ", "とにかく", "とはいえ", "とは言え", "とりあえず",
        "とうとう", "ともかく", "とたん", "とつぜん", "ときどき",
        "もう", "もし", "もっと", "もちろん", "もともと",
        "やがて", "やっと", "やはり", "やっぱり", "はっと", "はっきり",
        "にっこり", "にやり", "しかし", "しかも",
        "だが", "だから", "だけど", "だけれど", "でも、",
    ]

    private static func shouldMergeJapaneseQuotedBoundary(
        previous: Range<String.Index>,
        next: Range<String.Index>,
        in text: String
    ) -> Bool {
        guard let previousRange = trimmed(previous, in: text),
              let nextRange = trimmed(next, in: text)
        else { return false }

        let previousText = text[previousRange]
        guard let closingQuote = previousText.last,
              "」』”’".contains(closingQuote),
              previousText.contains("「") || previousText.contains("『")
        else { return false }

        var beforeQuote = previousText.index(before: previousText.endIndex)
        while beforeQuote > previousText.startIndex {
            beforeQuote = previousText.index(before: beforeQuote)
            if !previousText[beforeQuote].isWhitespace { break }
        }
        guard "。！？?!…".contains(previousText[beforeQuote]) else {
            return false
        }

        return continuesAfterJapaneseQuote(text[nextRange])
    }

    private static func splitMissedEnglishBoundaries(
        in systemRange: Range<String.Index>,
        text: String
    ) -> [Range<String.Index>] {
        var ranges: [Range<String.Index>] = []
        var sentenceStart = systemRange.lowerBound
        var cursor = systemRange.lowerBound

        while cursor < systemRange.upperBound {
            let punctuation = text[cursor]
            guard punctuation == "." || punctuation == "?" || punctuation == "!" else {
                cursor = text.index(after: cursor)
                continue
            }

            var punctuationEnd = text.index(after: cursor)
            while punctuationEnd < systemRange.upperBound,
                  text[punctuationEnd] == "." ||
                  text[punctuationEnd] == "?" ||
                  text[punctuationEnd] == "!"
            {
                punctuationEnd = text.index(after: punctuationEnd)
            }

            var sentenceEnd = punctuationEnd
            while sentenceEnd < systemRange.upperBound,
                  isEnglishClosingPunctuation(text[sentenceEnd])
            {
                sentenceEnd = text.index(after: sentenceEnd)
            }

            var nextContent = sentenceEnd
            var hasSeparator = false
            while nextContent < systemRange.upperBound, text[nextContent].isWhitespace {
                hasSeparator = true
                nextContent = text.index(after: nextContent)
            }

            guard hasSeparator,
                  nextContent < systemRange.upperBound,
                  shouldSplitEnglishBoundary(
                      punctuation: punctuation,
                      periodIndex: cursor,
                      isPunctuationRun: punctuationEnd != text.index(after: cursor),
                      nextContent: nextContent,
                      sentenceStart: sentenceStart,
                      in: text
                  )
            else {
                cursor = punctuationEnd
                continue
            }

            ranges.append(sentenceStart ..< sentenceEnd)
            sentenceStart = nextContent
            cursor = nextContent
        }

        if sentenceStart < systemRange.upperBound {
            ranges.append(sentenceStart ..< systemRange.upperBound)
        }
        return ranges
    }

    private static func shouldSplitEnglishBoundary(
        punctuation: Character,
        periodIndex: String.Index,
        isPunctuationRun: Bool,
        nextContent: String.Index,
        sentenceStart: String.Index,
        in text: String
    ) -> Bool {
        guard punctuation == ".", !isPunctuationRun else {
            // Ellipses are intentionally ambiguous; retain NaturalLanguage's
            // decision, as well as its handling of emphatic punctuation.
            return false
        }

        let token = englishPeriodToken(
            endingAt: periodIndex,
            lowerBound: sentenceStart,
            in: text
        )
        let normalizedToken = token.lowercased()
        if englishNonTerminalAbbreviations.contains(normalizedToken) {
            return false
        }
        if isSingleEnglishInitial(token) {
            guard let nextWord = englishWord(startingAt: nextContent, in: text) else {
                return false
            }
            return englishStrongSentenceStarters.contains(nextWord.lowercased())
        }

        // A dotted abbreviation or initialism remains inside the sentence by
        // default ("U.S. Army"). It can end the sentence when what follows is
        // a strong grammatical starter ("U.S. He moved ...").
        if token.dropLast().contains(".") {
            guard let nextWord = englishWord(startingAt: nextContent, in: text) else {
                return false
            }
            return englishStrongSentenceStarters.contains(nextWord.lowercased())
        }
        // A plain abbreviation such as "No. 5" was deliberately kept inside
        // the system range. Do not second-guess that general decision here.
        return false
    }

    private static func shouldMergeEnglishBoundary(
        previous: Range<String.Index>,
        next: Range<String.Index>,
        in text: String
    ) -> Bool {
        guard let previousPeriod = lastEnglishPeriod(in: previous, text: text) else {
            return false
        }
        let token = englishPeriodToken(
            endingAt: previousPeriod,
            lowerBound: previous.lowerBound,
            in: text
        )
        let normalizedToken = token.lowercased()
        guard englishNonTerminalAbbreviations.contains(normalizedToken)
            || isSingleEnglishInitial(token)
        else {
            return false
        }

        guard let nextWordRange = englishWordRange(startingAt: next.lowerBound, in: text) else {
            return false
        }
        let nextWord = String(text[nextWordRange])
        let afterNextWord = nextWordRange.upperBound
        let nextIsInitial = nextWord.count == 1
            && nextWord.first?.isUppercase == true
            && afterNextWord < text.endIndex
            && text[afterNextWord] == "."

        if englishNonTerminalAbbreviations.contains(normalizedToken) {
            return nextWord.first?.isUppercase == true
        }
        if nextIsInitial {
            return true
        }
        return nextWord.first?.isUppercase == true
            && !englishStrongSentenceStarters.contains(nextWord.lowercased())
    }

    private static func englishPeriodToken(
        endingAt period: String.Index,
        lowerBound: String.Index,
        in text: String
    ) -> String {
        var tokenStart = period
        while tokenStart > lowerBound {
            let previous = text.index(before: tokenStart)
            let character = text[previous]
            guard character.isLetter || character == "." else { break }
            tokenStart = previous
        }
        return String(text[tokenStart ... period])
    }

    private static func lastEnglishPeriod(
        in range: Range<String.Index>,
        text: String
    ) -> String.Index? {
        var cursor = range.upperBound
        while cursor > range.lowerBound {
            cursor = text.index(before: cursor)
            let character = text[cursor]
            if character == "." {
                return cursor
            }
            if !character.isWhitespace && !isEnglishClosingPunctuation(character) {
                return nil
            }
        }
        return nil
    }

    private static func englishWord(
        startingAt index: String.Index,
        in text: String
    ) -> String? {
        englishWordRange(startingAt: index, in: text).map { String(text[$0]) }
    }

    private static func englishWordRange(
        startingAt index: String.Index,
        in text: String
    ) -> Range<String.Index>? {
        var start = index
        while start < text.endIndex,
              text[start].isWhitespace || isEnglishOpeningPunctuation(text[start])
        {
            start = text.index(after: start)
        }
        guard start < text.endIndex, text[start].isLetter else { return nil }

        var end = text.index(after: start)
        while end < text.endIndex, text[end].isLetter {
            end = text.index(after: end)
        }
        return start ..< end
    }

    private static func isSingleEnglishInitial(_ token: String) -> Bool {
        guard token.last == "." else { return false }
        let stem = token.dropLast()
        return stem.count == 1 && stem.first?.isUppercase == true
    }

    private static func isEnglishOpeningPunctuation(_ character: Character) -> Bool {
        "\"“‘'([{<«‹".contains(character)
    }

    private static func isEnglishClosingPunctuation(_ character: Character) -> Bool {
        "\"”’')]}>»›".contains(character)
    }

    private static let englishNonTerminalAbbreviations: Set<String> = [
        "mr.", "mrs.", "ms.", "dr.", "prof.", "rev.", "hon.",
        "pres.", "gov.", "sen.", "rep.", "gen.", "capt.", "cmdr.",
        "lt.", "col.", "sgt.", "st.", "mt.", "fig.", "ch.", "vol."
    ]

    private static let englishStrongSentenceStarters: Set<String> = [
        "a", "after", "afterward", "afterwards", "although", "an", "and",
        "are", "as", "before", "but", "can", "could", "did", "do", "does",
        "finally", "had", "has", "have", "he", "here", "however", "how",
        "i", "if", "in", "instead", "is", "it", "later", "meanwhile",
        "nevertheless", "nonetheless", "otherwise", "she", "should", "so",
        "still", "suddenly", "that", "the", "then", "there", "these", "they",
        "this", "those", "though", "we", "were", "what", "when", "where",
        "while", "who", "why", "will", "would", "yet", "you"
    ]

    private static func fallbackRange(
        containing caret: String.Index,
        in text: String
    ) -> Range<String.Index> {
        let terminators = CharacterSet(charactersIn: ".!?。！？\n\r")
        let before = text[..<caret]
        let after = text[caret...]
        let lower = before.rangeOfCharacter(from: terminators, options: .backwards)?.upperBound
            ?? text.startIndex
        var upper = after.rangeOfCharacter(from: terminators)?.upperBound
            ?? text.endIndex
        while upper < text.endIndex, "\"”’」』".contains(text[upper]) {
            upper = text.index(after: upper)
        }
        return lower ..< upper
    }

    private static func trimmed(
        _ range: Range<String.Index>,
        in text: String
    ) -> Range<String.Index>? {
        var lower = range.lowerBound
        var upper = range.upperBound
        while lower < upper, text[lower].isWhitespace {
            lower = text.index(after: lower)
        }
        while lower < upper {
            let previous = text.index(before: upper)
            guard text[previous].isWhitespace else { break }
            upper = previous
        }
        return lower < upper ? lower ..< upper : nil
    }

    private static func contextWindow(
        in paragraph: String,
        sentenceRange: Range<String.Index>,
        sentenceText: String
    ) -> String {
        let normalizedParagraph = TranslationCacheStore.normalizedText(paragraph)
        guard normalizedParagraph.count > maximumContextCharacterCount else {
            return normalizedParagraph
        }

        let remaining = max(maximumContextCharacterCount - sentenceText.count, 0)
        let beforeCount = remaining / 2
        let afterCount = remaining - beforeCount
        let before = paragraph[..<sentenceRange.lowerBound].suffix(beforeCount)
        let after = paragraph[sentenceRange.upperBound...].prefix(afterCount)
        return TranslationCacheStore.normalizedText(
            String(before) + String(paragraph[sentenceRange]) + String(after)
        )
    }

    fileprivate static func stringIndex(
        atUTF16Offset offset: Int,
        in text: String
    ) -> String.Index? {
        let utf16 = text.utf16
        guard offset <= utf16.count else { return nil }
        let index = utf16.index(utf16.startIndex, offsetBy: offset)
        if let exact = String.Index(index, within: text) {
            return exact
        }

        // A web caret should normally land on a valid UTF-16 boundary. If a
        // malformed publication reports the middle of a surrogate pair, use
        // the preceding valid boundary instead of dropping the interaction.
        guard offset > 0 else { return text.startIndex }
        let previous = utf16.index(before: index)
        return String.Index(previous, within: text)
    }
}

/// Resolves the whole word under a short tap, for lookup straight from the
/// page. JavaScript reports only the paragraph and caret offset; `NLTokenizer`
/// decides the word, and the containing sentence comes back as context for the
/// dictionary and the learning record. Mirrors the Android short-tap script.
enum ReaderWordSegmenter {
    /// Auxiliaries the tokenizer splits from their stem. A tap on one of these
    /// means the word it inflects.
    private static let japaneseAuxiliaries: Set<String> = [
        "まし", "ます", "ました", "ません", "た", "て", "ない",
    ]

    /// Inflection endings joined onto the tapped stem, longest first. One is
    /// joined only when it ends on a token boundary, so a particle followed by
    /// an unrelated word starting with "た" is left alone.
    private static let japaneseInflectionSuffixes = [
        "ませんでした", "ません", "ました", "ます", "ている", "ない",
        "かった", "った", "いた", "いだ", "んだ", "した", "た",
    ]

    /// Endings that can also follow a noun ("問題ない"). They join only a stem
    /// ending in hiragana, such as "食べ" or "行っ"; the polite forms join any stem.
    private static let kanaStemOnlyEndings: Set<String> = ["た", "て", "ない"]

    static func word(
        in paragraph: String,
        utf16Offset: Int,
        language: LanguageCode?
    ) -> ReaderSentenceSegment? {
        guard !paragraph.isEmpty else { return nil }
        let clampedOffset = min(max(utf16Offset, 0), paragraph.utf16.count)
        guard let caret = ReaderSentenceSegmenter.stringIndex(
            atUTF16Offset: clampedOffset,
            in: paragraph
        ) else {
            return nil
        }

        let tokenizer = NLTokenizer(unit: .word)
        tokenizer.string = paragraph
        switch language {
        case .japanese: tokenizer.setLanguage(.japanese)
        case .english: tokenizer.setLanguage(.english)
        case .simplifiedChinese: tokenizer.setLanguage(.simplifiedChinese)
        case nil: break
        }
        var tokens: [Range<String.Index>] = []
        tokenizer.enumerateTokens(in: paragraph.startIndex ..< paragraph.endIndex) { range, _ in
            tokens.append(range)
            return true
        }

        // WebKit puts the caret before the glyph under the finger, or after it
        // when the right half of a word's last glyph was hit.
        guard var index = tokens.firstIndex(where: { $0.contains(caret) })
            ?? tokens.firstIndex(where: { $0.upperBound == caret })
        else {
            return nil
        }
        var lower = tokens[index].lowerBound
        var upper = tokens[index].upperBound

        if isJapanese(paragraph[lower ..< upper]) {
            let tapped = String(paragraph[lower ..< upper])
            if japaneseAuxiliaries.contains(tapped),
               index > 0,
               tokens[index - 1].upperBound == lower,
               joins(tapped, onto: paragraph[tokens[index - 1]]) {
                index -= 1
                lower = tokens[index].lowerBound
            }
            // Some tokenizers split kanji from okurigana ("食" + "べ").
            if let first = paragraph[tokens[index]].unicodeScalars.first,
               isHiragana(first),
               index > 0,
               tokens[index - 1].upperBound == lower,
               let last = paragraph[tokens[index - 1]].unicodeScalars.last,
               isKanji(last) {
                lower = tokens[index - 1].lowerBound
            }
            let tokenEnds = Set(tokens.map(\.upperBound))
            for suffix in japaneseInflectionSuffixes
            where paragraph[upper...].hasPrefix(suffix)
                && joins(suffix, onto: paragraph[lower ..< upper]) {
                let end = paragraph.index(upper, offsetBy: suffix.count)
                if tokenEnds.contains(end) {
                    upper = end
                    break
                }
            }
        }

        let raw = paragraph[lower ..< upper]
        guard raw.unicodeScalars.contains(where: CharacterSet.letters.contains) else {
            return nil
        }
        let text = TranslationCacheStore.normalizedText(String(raw))
        guard !text.isEmpty else { return nil }

        let lowerUTF16 = lower.utf16Offset(in: paragraph)
        let upperUTF16 = upper.utf16Offset(in: paragraph)
        let sentence = ReaderSentenceSegmenter.sentence(
            in: paragraph,
            utf16Offset: lowerUTF16,
            language: language
        )
        return ReaderSentenceSegment(
            text: text,
            contextText: sentence?.text ?? TranslationCacheStore.normalizedText(paragraph),
            utf16Range: lowerUTF16 ..< upperUTF16
        )
    }

    private static func joins(_ ending: String, onto stem: Substring) -> Bool {
        guard kanaStemOnlyEndings.contains(ending) else { return true }
        return stem.unicodeScalars.last.map(isHiragana) ?? false
    }

    private static func isJapanese(_ text: Substring) -> Bool {
        text.unicodeScalars.contains { isHiragana($0) || isKatakana($0) || isKanji($0) }
    }

    private static func isHiragana(_ scalar: Unicode.Scalar) -> Bool {
        (0x3040 ... 0x309F).contains(scalar.value)
    }

    private static func isKatakana(_ scalar: Unicode.Scalar) -> Bool {
        (0x30A0 ... 0x30FF).contains(scalar.value)
    }

    private static func isKanji(_ scalar: Unicode.Scalar) -> Bool {
        (0x3400 ... 0x9FFF).contains(scalar.value)
    }
}
