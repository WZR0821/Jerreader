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
        if continuesInLowerCase(previous: previous, next: next, in: text) {
            return true
        }
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

    /// `"Stop!" he said.` and `It was 5 p.m. and dark.`: a lower-case word
    /// after a sentence mark carries the sentence on, as in Android's
    /// `isEnglishBoundaryAfter`. Read from the last mark of `previous` on:
    /// its closing marks, then the spaces, then the first letter of `next`.
    private static func continuesInLowerCase(
        previous: Range<String.Index>,
        next: Range<String.Index>,
        in text: String
    ) -> Bool {
        var mark = previous.upperBound
        repeat {
            guard mark > previous.lowerBound else { return false }
            mark = text.index(before: mark)
        } while text[mark].isWhitespace || isEnglishClosingPunctuation(text[mark])
        guard englishSentenceMarks.contains(text[mark]) else { return false }

        var probe = text.index(after: mark)
        while probe < next.upperBound, isEnglishClosingPunctuation(text[probe]) {
            probe = text.index(after: probe)
        }
        while probe < next.upperBound, text[probe].isWhitespace {
            probe = text.index(after: probe)
        }
        guard probe >= next.lowerBound, probe < next.upperBound else { return false }
        return text[probe].isLowercase
    }

    private static let englishSentenceMarks = ".!?"

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
        tokens = repairedJapaneseTokens(in: paragraph, tokens: tokens)

        // WebKit puts the caret before the glyph under the finger, or after it
        // when the right half of a word's last glyph was hit.
        guard let index = tokens.firstIndex(where: { $0.contains(caret) })
            ?? tokens.firstIndex(where: { $0.upperBound == caret })
        else {
            return nil
        }
        var lower = tokens[index].lowerBound
        var upper = tokens[index].upperBound
        if isJapanese(paragraph[lower ..< upper]) {
            let bounds = japaneseWordBounds(in: paragraph, tokens: tokens, tapped: index)
            lower = bounds.lowerBound
            upper = bounds.upperBound
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

    // MARK: - Japanese words

    // The same rules as the short-tap script in Android's
    // ReaderWordInteractionController, so a tap selects the same word on
    // both. Tokenizers split a word differently (NLTokenizer gives
    // 食べ|まし|た, WebView's ICU 食|べ|ま|した), so a word is built from
    // tokens: an honorific prefix takes the word after it, a kanji the
    // okurigana after it, and a stem the endings that inflect it. Whichever
    // glyph of a word is tapped, the word is the same.

    /// Honorific prefixes a tokenizer gives a token of their own
    /// (昨日|お|茶). They belong to the word after them.
    private static let japaneseHonorificPrefixes: Set<String> = ["お", "ご", "御"]

    /// Particles: words of their own, never okurigana and never inflected
    /// (彼|は, 扉|を, 時間|が|ない, 東京|では). が, に, で and と can end a verb
    /// stem too (泳|が|ない), but after a noun, far more often, they are
    /// particles.
    private static let japaneseParticles: Set<String> = [
        "は", "を", "へ", "の", "も", "が", "に", "で", "と", "から", "まで",
        "より", "ので", "のに", "けど", "けれど", "けれども", "しか", "だけ", "ほど", "など",
        "ばかり", "くらい", "ぐらい", "こそ", "さえ", "かも", "では", "には", "とは", "でも",
        "にも", "とも", "へは", "へも", "からは", "までは", "のは", "ながら", "なのに", "なので",
        "として", "について", "にとって", "によって", "において", "という", "とか", "なら",
    ]

    /// The copula after a noun (雨|でし|た, 夜|だ): not okurigana, though
    /// endings still inflect it (でし|た).
    private static let japaneseCopulas: Set<String> = [
        "だ", "だっ", "だろ", "だろう", "です", "でし", "でしょ", "でしょう",
    ]

    /// A particle after a noun and okurigana after a verb stem (何|か|ある,
    /// 行|か|ない): okurigana only before kana that are not a particle
    /// (本|か|も stays apart), and then only a stem's endings follow.
    private static let japaneseAmbiguousParticles: Set<String> = ["か", "や"]

    /// Negatives that also follow a noun (問題|ない). They join a kanji only
    /// when it is a one-glyph stem (見|ない).
    private static let japaneseNounNegatives: Set<String> = ["ない", "なく", "なかっ", "なけれ"]

    /// Sentence-final particles, never part of the word before them.
    private static let japaneseSentenceFinals: Set<String> = ["よ", "ね", "ぞ", "ぜ", "わ", "さ"]

    /// One-glyph pronouns, never a verb stem (彼|って). Only plural and name
    /// suffixes join them (彼|ら, 私|たち), and か joins 何 and 誰 (何|か).
    private static let japanesePronouns: Set<String> = ["彼", "私", "僕", "俺", "君", "我", "誰", "何", "皆", "己"]
    private static let japanesePronounSuffixes: Set<String> = ["ら", "たち", "ども", "さん", "さま"]

    /// After a noun of several kanji only a する verb and name or plural
    /// suffixes join (勉強|して, 説明|できる, 学生|たち); 十分|かかる stays apart.
    private static let japaneseSuruStarts = ["し", "す", "さ", "せ", "でき"]
    private static let japaneseNounSuffixes: Set<String> = ["さん", "くん", "ちゃん", "さま", "たち", "ども"]

    /// Before a negative or a voice ending, わ, さ and から are okurigana
    /// (思|わ|ない, 報道|さ|れ|た, 分|から|ない), not a final or a particle.
    private static let japaneseNegativesAfterStem: Set<String> = [
        "ない", "なかっ", "なかった", "なく", "なくて", "なけれ", "ないで", "ず", "ずに",
    ]
    private static let japaneseVoicesAfterStem: Set<String> = [
        "せ", "せる", "せた", "せて", "せない", "せられ", "せられる",
        "れ", "れる", "れた", "れて", "れない", "れます", "れました",
    ]

    /// After に these end a verb (死|に|たい), so that に is okurigana.
    private static let japaneseEndingsAfterNi: Set<String> = [
        "たい", "たく", "たかっ", "たかった", "たくて", "たくない", "たくなかった",
        "ます", "まし", "ました", "ません", "ませ", "ましょう", "ましょ",
    ]

    /// Kanji of ぐ verbs, whose stems end in が and い (泳|が|ない, 脱い|で).
    private static let japaneseGuVerbKanji: Set<Unicode.Scalar> = [
        "泳", "急", "騒", "脱", "防", "注", "継", "稼", "嗅", "仰", "剥", "繋",
    ]

    /// Before these, な belongs to the word (死|な|ない, 少|な|かっ|た); before
    /// anything else it is the copula (静か|な|部屋).
    private static let japaneseNaContinuations: Set<String> = [
        "い", "ない", "なかっ", "なか", "なく", "なけれ", "かっ", "く", "くて", "けれ",
    ]

    /// A stem ending in one of these is a dictionary form or an adverb, and
    /// no single kana follows it as okurigana (待つ|べ, 強く|な).
    private static let japaneseDictionaryEndings: Set<Unicode.Scalar> = [
        "い", "う", "く", "ぐ", "す", "ず", "つ", "づ", "ぬ", "ふ", "ぶ", "ぷ", "む", "ゆ", "る",
    ]

    /// Single kana that still go on a stem ending in て or で: the rest of a
    /// verb (捨て|る) or a contracted ている (見て|る).
    private static let japaneseKanaAfterTe: Set<String> = ["る", "れ", "ろ"]

    /// Auxiliary verbs after a te-form (忘れて|しまった, 送って|もらった) and
    /// verbs often written in kana, each before its endings. A tokenizer may
    /// split one into kana (し|まっ|た), so they are matched over kana tokens.
    private static let japaneseAuxiliaryVerbs = [
        "しまう", "しまっ", "しまい", "しまわ", "しまえ", "しまおう",
        "くれる", "くれ", "もらう", "もらっ", "もらい", "もらわ", "もらえ", "もらおう",
        "あげる", "あげ", "おく", "おい", "おか", "おき", "おけ", "おこう",
        "いく", "いっ", "いか", "いき", "いけ", "いこう", "くる", "き", "こ", "こい", "こよう",
        "みる", "み", "みよう", "みろ", "ください", "くださ", "くださっ", "くださる",
        "ほしい", "ほしく", "ほしかっ", "いただく", "いただい", "いただき", "いただけ", "いただか",
        "やる", "やっ", "やり", "やら", "やろう", "ある", "あっ", "あり", "あれ", "おる", "おり", "おっ",
    ]

    private enum JapaneseStem {
        /// Any stem.
        case any
        /// A stem ending in kana, or a one-glyph kanji stem that is not a
        /// pronoun (来|た, 見|ない).
        case kana
        /// A stem ending in ん after a kanji (読ん|で, 飲ん|だ).
        case n
        /// A stem ending in て or で (書いて|いる).
        case te
        /// A stem ending in ま after a kanji (話しま|しょう).
        case ma
        /// A ぐ verb's kanji (泳|いで).
        case gu
        /// A ぐ verb's kanji and い (脱い|で).
        case guI
    }

    /// Endings that inflect a stem, longest first. One is joined only when it
    /// ends where a token ends.
    private static let japaneseEndings: [(form: String, stem: JapaneseStem)] = [
        ("いませんでした", .te), ("ませんでした", .any), ("たくなかった", .kana), ("ていました", .any),
        ("でいました", .n), ("させられる", .any), ("いましょう", .te), ("ています", .any), ("なかった", .kana),
        ("たかった", .kana), ("でいます", .n), ("いました", .te), ("いません", .te), ("ましょう", .any),
        ("させられ", .any), ("せられる", .kana), ("なければ", .kana), ("たくない", .kana), ("んだら", .any),
        ("ません", .any), ("ました", .any), ("すぎる", .kana), ("すぎた", .kana), ("すぎて", .kana),
        ("やすい", .kana), ("やすく", .kana), ("にくい", .kana), ("にくく", .kana), ("づらい", .kana),
        ("えて", .kana), ("えた", .kana), ("える", .kana), ("ている", .any), ("ていた", .any), ("かった", .any),
        ("でいる", .n), ("でいた", .n), ("います", .te), ("いない", .te), ("させる", .any), ("される", .any),
        ("せられ", .kana), ("られる", .kana), ("ったら", .any), ("なくて", .kana), ("たくて", .kana),
        ("なさい", .kana), ("すぎ", .kana), ("ます", .any), ("った", .any), ("って", .kana), ("いた", .any),
        ("いだ", .any), ("いで", .gu), ("んだ", .any), ("した", .any), ("ない", .kana), ("たい", .kana),
        ("いる", .te), ("させ", .any), ("され", .any), ("られ", .kana), ("せる", .kana), ("れる", .kana),
        ("たら", .kana), ("だら", .n), ("たり", .kana), ("だり", .n), ("しょう", .ma), ("ろう", .kana),
        ("んで", .kana), ("なく", .kana), ("たく", .kana), ("た", .kana), ("て", .kana), ("で", .n), ("だ", .n),
        ("で", .guI), ("だ", .guI), ("せ", .kana), ("れ", .kana), ("ず", .kana), ("ば", .kana),
    ]

    private struct JapaneseTokens {
        let paragraph: String
        let ranges: [Range<String.Index>]
        let ends: Set<String.Index>

        init(_ paragraph: String, _ ranges: [Range<String.Index>]) {
            self.paragraph = paragraph
            self.ranges = ranges
            ends = Set(ranges.map(\.upperBound))
        }

        func word(_ index: Int) -> String { String(paragraph[ranges[index]]) }

        func touching(_ first: Int, _ second: Int) -> Bool {
            first >= 0 && second < ranges.count && ranges[first].upperBound == ranges[second].lowerBound
        }

        func nextWord(_ index: Int) -> String { touching(index, index + 1) ? word(index + 1) : "" }

        /// The text right before token `index` ends a stem in ん (読ん|で).
        func followsNStem(_ index: Int) -> Bool {
            ReaderWordSegmenter.isNStem(String(paragraph[..<ranges[index].lowerBound].suffix(3)))
        }

        /// The text right before token `index` ends a ぐ verb's stem in い
        /// (脱い|で).
        func followsGuStem(_ index: Int) -> Bool {
            ReaderWordSegmenter.isGuIStem(String(paragraph[..<ranges[index].lowerBound].suffix(2)))
        }

        /// The copula: で before し or しょ (雨|で|した, ませ|ん|で|した) unless a
        /// stem in ん comes before it (読ん|で|しまう), な before anything but
        /// the rest of a word (静か|な|部屋), and だ, です and じゃ forms
        /// (嘘|だから, 本当|じゃない).
        func isCopula(_ index: Int) -> Bool {
            let word = word(index)
            if ReaderWordSegmenter.japaneseCopulas.contains(word) { return true }
            let next = nextWord(index)
            if word == "で" { return ["し", "した", "しょ", "しょう"].contains(next) && !followsNStem(index) }
            if word == "な" { return !ReaderWordSegmenter.japaneseNaContinuations.contains(next) }
            if ReaderWordSegmenter.japaneseParticles.contains(word) { return false }
            if word.hasPrefix("です") || word.hasPrefix("でしょ") || word.hasPrefix("じゃ") { return true }
            return word.hasPrefix("だ") && word.unicodeScalars.count >= 2 && word != "だら" && word != "だり"
        }

        /// わ, さ and から before a negative or a voice ending are okurigana
        /// (思|わ|ない, 報道|さ|れ|た, 分|から|ない).
        func isOkuriganaBeforeEnding(_ index: Int) -> Bool {
            guard ["わ", "さ", "から"].contains(word(index)) else { return false }
            // The ending may span tokens (合|わ|なか|っ|た) but must end on one.
            let after = ranges[index].upperBound
            let forms = japaneseNegativesAfterStem.union(japaneseVoicesAfterStem)
            return forms.contains { form in
                paragraph[after...].hasPrefix(form) && ends.contains(paragraph.index(after, offsetBy: form.count))
            }
        }

        func isFinal(_ index: Int) -> Bool {
            ReaderWordSegmenter.japaneseSentenceFinals.contains(word(index)) && !isOkuriganaBeforeEnding(index)
        }

        /// Kana that read as a particle but here are okurigana: no word starts
        /// with っ (上|が|っ|た, 美|しか|っ|た), a verb ending after が
        /// (下|が|ら|ない, 泳|が|ない), a polite or desire ending after に
        /// (死|に|たい), the te-form after a stem in ん or a ぐ verb's い
        /// (読ん|で, 脱い|で), もらう (も|ら|っ|た), a negative after から
        /// (分|から|ない) and ならない.
        func isParticle(_ index: Int) -> Bool {
            let word = word(index)
            guard ReaderWordSegmenter.japaneseParticles.contains(word), !isCopula(index) else { return false }
            let next = nextWord(index)
            if next.hasPrefix("っ") { return false }
            switch word {
            case "が":
                if ["ら", "り", "る", "れ", "ろ"].contains(next) { return false }
                if let before = paragraph[..<ranges[index].lowerBound].unicodeScalars.last,
                   ReaderWordSegmenter.japaneseGuVerbKanji.contains(before),
                   ["な", "せ", "れ", "ず", "ぬ"].contains(where: { next.hasPrefix($0) }) {
                    return false
                }
            case "に":
                if ReaderWordSegmenter.japaneseEndingsAfterNi.contains(next) { return false }
            case "で":
                if followsNStem(index) || followsGuStem(index) { return false }
            case "も":
                if next == "ら" { return false }
            case "から":
                if isOkuriganaBeforeEnding(index) { return false }
            case "なら":
                if ReaderWordSegmenter.japaneseNegativesAfterStem.contains(next) { return false }
            default:
                break
            }
            return true
        }

        /// Where the word whose head is token `index` starts: at an honorific
        /// prefix before it (お|茶, ご|主人).
        func wordStart(_ index: Int) -> String.Index {
            if touching(index - 1, index), ReaderWordSegmenter.japaneseHonorificPrefixes.contains(word(index - 1)),
               ReaderWordSegmenter.startsWithKanji(word(index)) {
                return ranges[index - 1].lowerBound
            }
            return ranges[index].lowerBound
        }
    }

    /// Stems that take ます (あり|ます, 食べ|ます).
    private static let japaneseMasuStems: Set<String> = [
        "い", "き", "ぎ", "し", "じ", "ち", "ぢ", "に", "ひ", "び", "ぴ", "み", "り",
        "え", "け", "げ", "せ", "ぜ", "て", "で", "ね", "へ", "べ", "ぺ", "め", "れ",
    ]

    /// What ICU leaves of ます, with the particle after it (ありま|すか).
    private static let japaneseMasuTails: Set<String> = ["す", "すか", "すね", "すよ", "すが", "すけど", "すから"]

    /// A kanji stem and one masu-stem kana (振り, 立ち, 食べ).
    private static func isMasuStem(_ text: String) -> Bool {
        let scalars = Array(text.unicodeScalars)
        return scalars.count >= 2 && scalars.dropLast().allSatisfy(isKanji)
            && japaneseMasuStems.contains(String(scalars[scalars.count - 1]))
    }

    /// The first half of a compound (振り|返った, 読み|方): a masu stem, not an
    /// adjective (高い), a noun and a particle (本当に, 全て) or an adverb (少し).
    private static func isCompoundStem(_ text: String) -> Bool {
        isMasuStem(text) && !["い", "に", "で", "て", "へ"].contains(where: { text.hasSuffix($0) })
            && !["少し", "同じ"].contains(text)
    }

    /// Kana an adjective ends in (早く, 高い), which no compound ends in.
    private static let japaneseAdjectiveTails: Set<String> = ["く", "い", "さ", "しい", "しく", "かった", "くて", "くない"]

    /// The second half of a compound (振り|返|っ|た, 読み|方): a kanji, not an
    /// adjective (少し|早く) or the stem of a compound of its own before
    /// another kanji (立ち|止まり|振り|返った).
    private static func isCompoundHalf(_ text: String, before next: String) -> Bool {
        guard startsWithKanji(text) else { return false }
        let scalars = Array(text.unicodeScalars)
        if let kana = scalars.firstIndex(where: isHiragana), scalars[kana...].allSatisfy(isHiragana) {
            var tail = String.UnicodeScalarView()
            tail.append(contentsOf: scalars[kana...])
            if japaneseAdjectiveTails.contains(String(tail)) { return false }
        }
        return !(isMasuStem(text) && startsWithKanji(next))
    }

    /// ICU, which segments text for Android's WebView, puts a boundary in the
    /// wrong place in a few common phrases: はい|け|ない (は|いけ|ない),
    /// 待つ|べ|きだ (べき|だ), ありま|すか (あり|ます|か), ご|ざ|いま|した
    /// (ござい|ま|した), 勉強|した|いと (し|たい|と), くだ|さい (ください),
    /// も|しそう (もし|そう), 悲|しそう (悲|し|そう), おい|し|そうですね
    /// (おいし|そう|です|ね), ことに|した (こと|に|した), 帰|ったらしい
    /// (帰|った|らしい), 読|んじゃ (読|ん|じゃ), よ|さ|そう (よさ|そう), すみません|で|
    /// した (すみませんでした) and a compound verb (振り|返った).
    /// Moving those boundaries gives the tokens NLTokenizer gives, so both
    /// platforms read the phrase the same way; Android's short-tap script
    /// repairs its segments with the same rules. NLTokenizer splits two set
    /// phrases ICU keeps whole (ご|ちそう|さま, お|めでとう), and those are joined.
    /// Internal so tests can hand it another tokenizer's tokens.
    static func repairedJapaneseTokens(
        in paragraph: String,
        tokens: [Range<String.Index>]
    ) -> [Range<String.Index>] {
        func word(_ index: Int) -> String {
            index >= 0 && index < tokens.count ? String(paragraph[tokens[index]]) : ""
        }
        func touching(_ first: Int, _ second: Int) -> Bool {
            first >= 0 && second < tokens.count && tokens[first].upperBound == tokens[second].lowerBound
        }
        /// The position `count` characters into token `index`.
        func at(_ index: Int, _ count: Int) -> String.Index {
            paragraph.index(tokens[index].lowerBound, offsetBy: count)
        }
        var repaired: [Range<String.Index>] = []
        /// The pieces between consecutive bounds, leaving out empty ones.
        func append(_ bounds: [String.Index]) {
            for (lower, upper) in zip(bounds, bounds.dropFirst()) where lower < upper {
                repaired.append(lower ..< upper)
            }
        }
        var index = 0
        while index < tokens.count {
            let current = word(index)
            let next = touching(index, index + 1) ? word(index + 1) : ""
            let start = tokens[index].lowerBound
            let beforeLast = current.count >= 2 ? String(current.dropLast().last!) : ""
            if current == "はい" || current == "ばい", next.hasPrefix("け") {
                append([start, at(index, 1), tokens[index + 1].upperBound])
                index += 2
            } else if next.hasPrefix("き"), current == "すべ" || (current == "べ" && touching(index - 1, index)
                && word(index - 1).unicodeScalars.last.map { japaneseDictionaryEndings.contains($0) } == true) {
                append([start, at(index, current.count - 1), at(index + 1, 1), tokens[index + 1].upperBound])
                index += 2
            } else if current.hasSuffix("ま"), japaneseMasuStems.contains(beforeLast),
                      japaneseMasuTails.contains(next) || (next.hasPrefix("しょう") && current != "いま") {
                let cut = next.hasPrefix("す") ? 1 : 3
                append([start, at(index, current.count - 1), at(index + 1, cut), tokens[index + 1].upperBound])
                index += 2
            } else if current == "ご", next == "ざ", touching(index + 1, index + 2),
                      word(index + 2).hasPrefix("い") {
                append([start, at(index + 2, 1), tokens[index + 2].upperBound])
                index += 3
            } else if current.hasSuffix("た"), next == "い" || next.hasPrefix("いと") {
                append([start, at(index, current.count - 1), at(index + 1, 1), tokens[index + 1].upperBound])
                index += 2
            } else if current == "くだ", next.hasPrefix("さい") {
                append([start, at(index + 1, 2), tokens[index + 1].upperBound])
                index += 2
            } else if next.hasPrefix("しそう"), isKana(current),
                      (current.count >= 2 && !japaneseParticles.contains(current))
                        || (current == "も" && !touching(index - 1, index)) {
                append([start, at(index + 1, 1), at(index + 1, 3), tokens[index + 1].upperBound])
                index += 2
            } else if current.hasPrefix("しそう") {
                append([start, at(index, 1), at(index, 3), tokens[index].upperBound])
                index += 1
            } else if next == "し", isKana(current), current.count >= 2, !japaneseParticles.contains(current),
                      touching(index + 1, index + 2), word(index + 2).hasPrefix("そう") {
                append([start, tokens[index + 1].upperBound])
                index += 2
            } else if current.hasPrefix("そう"), current.unicodeScalars.count > 2,
                      "だでなに".unicodeScalars.contains(current.unicodeScalars.dropFirst(2).first!) {
                // そう|です|ね: a final particle after the copula is a word too.
                let count = current.unicodeScalars.count
                let final = count > 3 && "ねよか".unicodeScalars.contains(current.unicodeScalars.last!)
                append([start, at(index, 2), at(index, final ? count - 1 : count), tokens[index].upperBound])
                index += 1
            } else if current == "よ" || current == "な", next == "さ", touching(index + 1, index + 2),
                      word(index + 2).hasPrefix("そう") {
                append([start, tokens[index + 1].upperBound])
                index += 2
            } else if current.hasSuffix("ません"), next == "で", touching(index + 1, index + 2), word(index + 2) == "した" {
                append([start, tokens[index + 2].upperBound])
                index += 3
            } else if current == "ことに", let first = next.unicodeScalars.first, "しすな".unicodeScalars.contains(first) {
                append([start, at(index, 2), tokens[index].upperBound])
                index += 1
            } else if current == "ったらしい" || current.hasPrefix("んじゃ"), touching(index - 1, index),
                      word(index - 1).unicodeScalars.last.map(isKanji) == true {
                append([start, at(index, current.hasPrefix("ん") ? 1 : 2), tokens[index].upperBound])
                index += 1
            } else if isCompoundStem(current),
                      isCompoundHalf(next, before: touching(index + 1, index + 2) ? word(index + 2) : "") {
                append([start, tokens[index + 1].upperBound])
                index += 2
            } else if (current == "ご" && next == "ちそう") || (current == "お" && next.hasPrefix("めでとう")) {
                let withSama = next == "ちそう" && touching(index + 1, index + 2) && word(index + 2) == "さま"
                let end = withSama ? index + 2 : index + 1
                append([start, tokens[end].upperBound])
                index = end + 1
            } else {
                repaired.append(tokens[index])
                index += 1
            }
        }
        return repaired
    }

    /// The word a tap on token `tapped` means. Internal so tests can hand it
    /// another tokenizer's tokens.
    static func japaneseWordBounds(
        in paragraph: String,
        tokens: [Range<String.Index>],
        tapped: Int
    ) -> Range<String.Index> {
        let tokenSet = JapaneseTokens(paragraph, tokens)
        let tappedWord = tokenSet.word(tapped)
        guard isJapanese(tappedWord) else { return tokens[tapped] }
        if japaneseHonorificPrefixes.contains(tappedWord), tokenSet.touching(tapped, tapped + 1),
           startsWithKanji(tokenSet.word(tapped + 1)) {
            // A tap on the お of お茶 means the word it starts.
            let start = tokens[tapped].lowerBound
            return start ..< japaneseWordEnd(
                from: start, to: tokens[tapped + 1].upperBound, head: tapped + 1, in: tokenSet
            )
        }
        // A tap on kana may be on the ending of a word before it
        // (食べ|まし|た): the word is the one, from as far back as it goes,
        // whose endings reach the tapped token.
        var head = tapped
        if isKana(tappedWord), !tokenSet.isParticle(tapped) {
            var candidate = tapped - 1
            while candidate >= 0, candidate >= tapped - 6 {
                let word = tokenSet.word(candidate)
                guard tokenSet.touching(candidate, candidate + 1), isKana(tokenSet.word(candidate + 1)),
                      isJapanese(word), !tokenSet.isParticle(candidate),
                      !japaneseHonorificPrefixes.contains(word)
                else { break }
                let end = japaneseWordEnd(
                    from: tokenSet.wordStart(candidate),
                    to: tokens[candidate].upperBound,
                    head: candidate,
                    in: tokenSet
                )
                if end >= tokens[tapped].upperBound {
                    head = candidate
                }
                candidate -= 1
            }
        }
        // An auxiliary verb after a te-form is a word of its own, however the
        // tokenizer split it (忘れて|し|まっ|た).
        if let auxiliary = japaneseAuxiliary(at: tapped, in: tokenSet), head >= auxiliary.lowerBound {
            return tokens[auxiliary.lowerBound].lowerBound ..< tokens[auxiliary.upperBound].upperBound
        }
        // A tap on the 茶 of お茶 means the word the prefix starts, as a tap
        // on お does.
        let start = tokenSet.wordStart(head)
        let end = japaneseWordEnd(from: start, to: tokens[head].upperBound, head: head, in: tokenSet)
        return start ..< max(end, tokens[tapped].upperBound)
    }

    /// Where the word that starts at `start` and so far ends at `upper`
    /// ends, with its okurigana and endings. A particle takes none
    /// (それ|は|ない).
    private static func japaneseWordEnd(
        from start: String.Index,
        to upper: String.Index,
        head: Int,
        in tokens: JapaneseTokens
    ) -> String.Index {
        guard !tokens.isParticle(head) else { return upper }
        let paragraph = tokens.paragraph
        var upper = upper
        var ambiguousOkurigana = false
        for _ in 0 ..< 8 {
            let stem = String(paragraph[start ..< upper])
            if let ending = japaneseEndings.first(where: { ending in
                paragraph[upper...].hasPrefix(ending.form)
                    && tokens.ends.contains(paragraph.index(upper, offsetBy: ending.form.count))
                    && joins(ending.stem, onto: stem)
                    && (!ambiguousOkurigana || ending.stem == .kana)
            }) {
                upper = paragraph.index(upper, offsetBy: ending.form.count)
                ambiguousOkurigana = false
                continue
            }
            if ambiguousOkurigana { break }
            guard let next = tokens.ranges.firstIndex(where: { $0.lowerBound == upper }),
                  let last = stem.unicodeScalars.last
            else { break }
            let kana = tokens.word(next)
            let single = kana.unicodeScalars.count == 1
            if !isKana(kana) || tokens.isParticle(next) || tokens.isCopula(next) || tokens.isFinal(next)
                || japaneseHonorificPrefixes.contains(kana)
                || (japaneseNounNegatives.contains(kana) && !isVerbKanji(stem)) {
                break
            }
            // A te-form ends the word (忘れて|しまう).
            if stem.hasSuffix("て") || stem.hasSuffix("で"), !japaneseKanaAfterTe.contains(kana) { break }
            if japanesePronouns.contains(stem) {
                guard japanesePronounSuffixes.contains(kana) || (kana == "か" && (stem == "何" || stem == "誰"))
                else { break }
                upper = tokens.ranges[next].upperBound
                continue
            }
            // ください is a word of its own, never okurigana (ご注意|ください).
            if kana.hasPrefix("くださ") { break }
            let afterKanji = isKanji(last)
            if afterKanji {
                if !single, stem.unicodeScalars.count > 1, stem.unicodeScalars.allSatisfy(isKanji),
                   !japaneseSuruStarts.contains(where: { kana.hasPrefix($0) }), !japaneseNounSuffixes.contains(kana) {
                    break
                }
            } else {
                // A single kana may also go on a stem that has a kanji
                // (上が|っ, 下が|ら) unless the stem ends like a dictionary
                // form (待つ|べ); so may から before a negative (見つ|から|ない).
                guard containsKanji(stem) else { break }
                // お願い|します: an お〜する humble verb.
                let humbleSuru = japaneseHonorificPrefixes.contains(where: { stem.hasPrefix($0) })
                    && japaneseSuruStarts.contains(where: { kana.hasPrefix($0) })
                if humbleSuru {
                } else if single {
                    if japaneseDictionaryEndings.contains(last) { break }
                } else if !tokens.isOkuriganaBeforeEnding(next) {
                    break
                }
            }
            if japaneseAmbiguousParticles.contains(kana) {
                guard afterKanji, tokens.touching(next, next + 1),
                      tokens.word(next + 1).unicodeScalars.first.map(isHiragana) ?? false,
                      !tokens.isParticle(next + 1)
                else { break }
                ambiguousOkurigana = true
            }
            upper = tokens.ranges[next].upperBound
        }
        return upper
    }

    /// The kana tokens, after a te-form, a particle or nothing, that make up
    /// the auxiliary verb token `tapped` is part of (忘れて|し|まっ|た,
    /// お金を|も|ら|っ|た).
    private static func japaneseAuxiliary(at tapped: Int, in tokens: JapaneseTokens) -> ClosedRange<Int>? {
        func stops(_ index: Int) -> Bool {
            let word = tokens.word(index)
            return !isKana(word) || tokens.isParticle(index) || tokens.isCopula(index) || tokens.isFinal(index)
                || japaneseAmbiguousParticles.contains(word)
        }
        func endsTe(_ index: Int) -> Bool {
            let word = tokens.word(index)
            return word.hasSuffix("て") || word.hasSuffix("で")
        }
        guard !stops(tapped) else { return nil }
        var start = tapped
        while start > tapped - 4, tokens.touching(start - 1, start), !stops(start - 1), !endsTe(start - 1) {
            start -= 1
        }
        if tokens.touching(start - 1, start), !endsTe(start - 1), !tokens.isParticle(start - 1) {
            return nil
        }
        var text = ""
        var best: Int?
        var end = start
        while end < tokens.ranges.count, end < start + 5 {
            if end > start, !tokens.touching(end - 1, end) || stops(end) { break }
            text += tokens.word(end)
            if isAuxiliaryVerb(text) { best = end }
            if endsTe(end) { break }
            end += 1
        }
        guard let best, best >= tapped else { return nil }
        return start ... best
    }

    private static func isAuxiliaryVerb(_ text: String) -> Bool {
        japaneseAuxiliaryVerbs.contains { verb in
            text.hasPrefix(verb)
                && (text.count == verb.count || isInflection(String(text.dropFirst(verb.count)), of: verb, depth: 0))
        }
    }

    private static func isInflection(_ rest: String, of stem: String, depth: Int) -> Bool {
        guard depth < 3 else { return false }
        return japaneseEndings.contains { ending in
            rest.hasPrefix(ending.form) && joins(ending.stem, onto: stem)
                && (rest.count == ending.form.count
                    || isInflection(
                        String(rest.dropFirst(ending.form.count)), of: stem + ending.form, depth: depth + 1))
        }
    }

    private static func joins(_ stemKind: JapaneseStem, onto stem: String) -> Bool {
        guard let last = stem.unicodeScalars.last else { return false }
        switch stemKind {
        case .any:
            return true
        case .kana:
            return isHiragana(last) || isVerbKanji(stem)
        case .n:
            return isNStem(stem)
        case .te:
            return stem.hasSuffix("て") || stem.hasSuffix("で")
        case .ma:
            return last == "ま" && containsKanji(stem)
        case .gu:
            return japaneseGuVerbKanji.contains(last)
        case .guI:
            return isGuIStem(stem)
        }
    }

    /// A stem ending in ん after a kanji, or after a kanji and し (楽しん).
    private static func isNStem(_ stem: String) -> Bool {
        let scalars = Array(stem.unicodeScalars)
        guard scalars.count >= 2, scalars[scalars.count - 1] == "ん" else { return false }
        if isKanji(scalars[scalars.count - 2]) { return true }
        // 楽しん, 浮かん, 膨らん, 黒ずん: one okurigana kana before ん.
        return scalars.count >= 3 && ["し", "か", "ら", "ず"].contains(scalars[scalars.count - 2])
            && isKanji(scalars[scalars.count - 3])
    }

    /// A stem ending in a ぐ verb's kanji and い (急い).
    private static func isGuIStem(_ stem: String) -> Bool {
        let scalars = Array(stem.unicodeScalars)
        return scalars.count >= 2 && scalars[scalars.count - 1] == "い"
            && japaneseGuVerbKanji.contains(scalars[scalars.count - 2])
    }

    /// A one-glyph kanji stem that is not a pronoun (見|ない; never 彼|って).
    private static func isVerbKanji(_ stem: String) -> Bool {
        isSingleKanji(stem) && !japanesePronouns.contains(stem)
    }

    private static func startsWithKanji(_ text: String) -> Bool {
        text.unicodeScalars.first.map(isKanji) ?? false
    }

    private static func isKana(_ text: String) -> Bool {
        !text.isEmpty && text.unicodeScalars.allSatisfy(isHiragana)
    }

    private static func isSingleKanji(_ text: String) -> Bool {
        text.unicodeScalars.count == 1 && text.unicodeScalars.allSatisfy(isKanji)
    }

    private static func containsKanji(_ text: String) -> Bool {
        text.unicodeScalars.contains(where: isKanji)
    }

    private static func isJapanese<Text: StringProtocol>(_ text: Text) -> Bool {
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
