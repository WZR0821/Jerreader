import CoreGraphics
import Foundation
import NaturalLanguage

// Translation and explanation requests, their states and errors, cross-page
// context, and where the translation card is placed over the page.

enum ReaderTranslationContext {
    static func window(
        highlight: String,
        before: String?,
        after: String?,
        maximumCharacterCount: Int = ReaderSentenceSegmenter.maximumContextCharacterCount
    ) -> String? {
        let sourceText = TranslationCacheStore.normalizedText(highlight)
        guard !sourceText.isEmpty else { return nil }

        let remaining = max(maximumCharacterCount - sourceText.count, 0)
        let beforeCount = remaining / 2
        let afterCount = remaining - beforeCount
        let context = TranslationCacheStore.normalizedText(
            String((before ?? "").suffix(beforeCount))
                + highlight
                + String((after ?? "").prefix(afterCount))
        )
        return context.isEmpty || context == sourceText ? nil : context
    }
}

struct ReaderCrossPageExpansion: Equatable, Sendable {
    let text: String
    let contextText: String
}

/// Identifies text extracted from a rendered page without treating a visual
/// page as an EPUB chapter. `chapterIdentifier` should be the stable spine
/// resource or navigation-section identity; PDF callers can use one document
/// identity when no chapter map is available.
struct ReaderCrossPageTextFragment: Equatable, Sendable {
    let resourceIdentifier: String
    let chapterIdentifier: String?
    let text: String
}

/// Builds a bounded PDF context window while guaranteeing that the tapped
/// sentence remains inside it. Simply prefix-truncating previous/current/next
/// page text can drop a selection near the bottom of a long current page.
enum ReaderCrossPageContextBuilder {
    /// Boundary-aware variant for EPUB/PDF extractors that know resource or
    /// chapter identities. Neighbouring text never crosses a chapter boundary.
    /// When a chapter identity is unavailable, resource identity is the
    /// conservative fallback.
    static func context(
        sourceText: String,
        currentResourceIdentifier: String,
        currentChapterIdentifier: String?,
        previousPage: ReaderCrossPageTextFragment?,
        localContext: String?,
        nextPage: ReaderCrossPageTextFragment?,
        maximumCharacterCount: Int = ReaderSentenceSegmenter.maximumContextCharacterCount
    ) -> String? {
        func allowedText(_ fragment: ReaderCrossPageTextFragment?) -> String? {
            guard let fragment else { return nil }
            if let currentChapterIdentifier {
                guard fragment.chapterIdentifier == currentChapterIdentifier else {
                    return nil
                }
            } else {
                guard fragment.resourceIdentifier == currentResourceIdentifier else {
                    return nil
                }
            }
            return fragment.text
        }

        return context(
            sourceText: sourceText,
            previousPageText: allowedText(previousPage),
            localContext: localContext,
            nextPageText: allowedText(nextPage),
            maximumCharacterCount: maximumCharacterCount
        )
    }

    static func context(
        sourceText: String,
        previousPageText: String?,
        localContext: String?,
        nextPageText: String?,
        maximumCharacterCount: Int = ReaderSentenceSegmenter.maximumContextCharacterCount
    ) -> String? {
        let source = TranslationCacheStore.normalizedText(sourceText)
        guard !source.isEmpty, maximumCharacterCount > source.count else { return nil }

        let local = TranslationCacheStore.normalizedText(localContext ?? "")
        let previous = TranslationCacheStore.normalizedText(previousPageText ?? "")
        let next = TranslationCacheStore.normalizedText(nextPageText ?? "")

        let localBefore: String
        let localAfter: String
        if let range = sourceRangeClosestToMiddle(source, in: local) {
            localBefore = String(local[..<range.lowerBound])
            localAfter = String(local[range.upperBound...])
        } else {
            // The page text layer can normalize ligatures or OCR punctuation
            // differently from the selected text. Keep useful surrounding
            // pages without pretending that we can splice an uncertain local
            // range around the selection.
            localBefore = ""
            localAfter = ""
        }

        let before = [previous, localBefore]
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        let after = [localAfter, next]
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        let language = ReaderLanguageDetector.detect(text: source, bookLanguage: nil)
        let beforeWithBoundary = before.isEmpty
            ? nil
            : before + boundarySeparator(
                left: before,
                right: source,
                language: language
            )
        let afterWithBoundary = after.isEmpty
            ? nil
            : boundarySeparator(
                left: source,
                right: after,
                language: language
            ) + after
        return ReaderTranslationContext.window(
            highlight: source,
            before: beforeWithBoundary,
            after: afterWithBoundary,
            maximumCharacterCount: maximumCharacterCount
        )
    }

    private static func boundarySeparator(
        left: String,
        right: String,
        language: LanguageCode?
    ) -> String {
        guard language == .english,
              let leftCharacter = left.last,
              let rightCharacter = right.first,
              leftCharacter != "-",
              !rightCharacter.isPunctuation
        else { return "" }
        return " "
    }

    private static func sourceRangeClosestToMiddle(
        _ source: String,
        in context: String
    ) -> Range<String.Index>? {
        guard !context.isEmpty else { return nil }
        var ranges: [Range<String.Index>] = []
        var searchStart = context.startIndex
        while searchStart < context.endIndex,
              let range = context.range(
                  of: source,
                  range: searchStart ..< context.endIndex
              )
        {
            ranges.append(range)
            searchStart = range.upperBound
        }
        let midpoint = context.distance(
            from: context.startIndex,
            to: context.endIndex
        ) / 2
        return ranges.min { lhs, rhs in
            let lhsOffset = context.distance(from: context.startIndex, to: lhs.lowerBound)
            let rhsOffset = context.distance(from: context.startIndex, to: rhs.lowerBound)
            return abs(lhsOffset - midpoint) < abs(rhsOffset - midpoint)
        }
    }
}

/// Expands a visible selection to complete neighbouring sentence units. The
/// operation uses semantic text already supplied by Readium/PDFKit, so it does
/// not need a fragile native selection range spanning two rendered pages.
enum ReaderCrossPageTranslationResolver {
    static func expansion(
        sourceText: String,
        contextText: String?,
        language: LanguageCode?,
        maximumCharacterCount: Int = 2_000
    ) -> ReaderCrossPageExpansion? {
        let source = TranslationCacheStore.normalizedText(sourceText)
        let context = TranslationCacheStore.normalizedText(contextText ?? "")
        guard !source.isEmpty,
              context.count > source.count,
              source.count <= maximumCharacterCount,
              let sourceRange = bestSourceRange(source, in: context)
        else { return nil }

        let sourceLower = sourceRange.location
        let sourceUpper = NSMaxRange(sourceRange)
        guard let firstSentence = ReaderSentenceSegmenter.sentence(
            in: context,
            utf16Offset: sourceLower,
            language: language
        ),
        let lastSentence = ReaderSentenceSegmenter.sentence(
            in: context,
            utf16Offset: max(sourceUpper - 1, sourceLower),
            language: language
        )
        else { return nil }

        let lower = min(firstSentence.utf16Range.lowerBound, lastSentence.utf16Range.lowerBound)
        let upper = max(firstSentence.utf16Range.upperBound, lastSentence.utf16Range.upperBound)

        let lowerIndex = String.Index(utf16Offset: lower, in: context)
        let upperIndex = String.Index(utf16Offset: upper, in: context)
        var expanded = TranslationCacheStore.normalizedText(
            String(context[lowerIndex ..< upperIndex])
        )

        if expanded.count > maximumCharacterCount {
            let baseLower = min(
                firstSentence.utf16Range.lowerBound,
                lastSentence.utf16Range.lowerBound
            )
            let baseUpper = max(
                firstSentence.utf16Range.upperBound,
                lastSentence.utf16Range.upperBound
            )
            let baseStart = String.Index(utf16Offset: baseLower, in: context)
            let baseEnd = String.Index(utf16Offset: baseUpper, in: context)
            expanded = TranslationCacheStore.normalizedText(
                String(context[baseStart ..< baseEnd])
            )
        }

        guard expanded != source,
              expanded.count <= maximumCharacterCount
        else { return nil }
        return ReaderCrossPageExpansion(text: expanded, contextText: context)
    }

    private static func bestSourceRange(_ source: String, in context: String) -> NSRange? {
        let value = context as NSString
        let needle = source as NSString
        var searchRange = NSRange(location: 0, length: value.length)
        var candidates: [NSRange] = []
        while searchRange.length > 0 {
            let range = value.range(of: needle as String, options: [], range: searchRange)
            guard range.location != NSNotFound else { break }
            candidates.append(range)
            let next = NSMaxRange(range)
            guard next > searchRange.location else { break }
            searchRange = NSRange(location: next, length: value.length - next)
        }
        let midpoint = Double(value.length) / 2
        return candidates.min {
            abs(Double($0.location + $0.length / 2) - midpoint)
                < abs(Double($1.location + $1.length / 2) - midpoint)
        }
    }

}

enum ReaderVocabularyCandidate {
    static func term(
        from text: String,
        language: LanguageCode?
    ) -> String? {
        guard let language else { return nil }
        let value = TranslationCacheStore.normalizedText(text)
        guard !value.isEmpty, value.count <= 48 else { return nil }

        switch language {
        case .english:
            return matches(
                value,
                pattern: #"^\p{L}[\p{L}\p{M}]*(?:['’\-][\p{L}\p{M}]+)*$"#
            ) ? value : nil
        case .japanese:
            guard !value.unicodeScalars.contains(where: {
                CharacterSet.punctuationCharacters.contains($0)
            }) else { return nil }
            return matches(
                value,
                pattern: #"^[\p{Han}\p{Hiragana}\p{Katakana}\p{M}ー々〆ヵヶ]+$"#
            ) ? value : nil
        case .simplifiedChinese:
            guard !value.unicodeScalars.contains(where: {
                CharacterSet.punctuationCharacters.contains($0)
            }) else { return nil }
            return matches(value, pattern: #"^[\p{Han}\p{M}]+$"#) ? value : nil
        }
    }

    private static func matches(_ value: String, pattern: String) -> Bool {
        value.range(of: pattern, options: .regularExpression) != nil
    }
}

struct ReaderTranslationRequest: Identifiable, Equatable, Sendable {
    let id: UUID
    let bookID: UUID
    let sourceText: String
    let contextText: String?
    let sourceLanguage: LanguageCode?
    let targetLanguage: LanguageCode
    let provider: TranslationProviderChoice
    let providerIdentifier: String
    let providerVersion: String
    let locatorJSON: String?
    let annotationLocatorJSON: String?
    let annotationAnchorJSON: String?
    let selectionFrame: CGRect?
    let focusFrame: CGRect?
    let trigger: ReaderTranslationTrigger
    let createdAt: Date
    var didUseFallback: Bool = false

    var interactionIdentity: ReaderTranslationInteractionIdentity {
        ReaderTranslationInteractionIdentity(
            bookID: bookID,
            sourceText: sourceText,
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            providerIdentifier: providerIdentifier,
            providerVersion: providerVersion,
            locatorJSON: locatorJSON
        )
    }

    var retryIdentity: ReaderTranslationRetryIdentity {
        ReaderTranslationRetryIdentity(
            bookID: bookID,
            sourceText: sourceText,
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            locatorJSON: locatorJSON
        )
    }
}

/// Identifies a user-visible translation independently of how it was invoked.
/// A quick sentence tap and a native long-press selection produce different
/// Locator JSON and context windows, even when they refer to the same sentence.
/// Collapsing them to the publication resource prevents a second provider call
/// while still allowing the same sentence in another chapter to be translated
/// with its own context.
struct ReaderTranslationInteractionIdentity: Hashable, Sendable {
    let bookID: UUID
    let sourceText: String
    let sourceLanguage: LanguageCode?
    let targetLanguage: LanguageCode
    let providerIdentifier: String
    let providerVersion: String
    let resourceIdentity: String?

    init(
        bookID: UUID,
        sourceText: String,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode,
        providerIdentifier: String,
        providerVersion: String,
        locatorJSON: String?
    ) {
        self.bookID = bookID
        self.sourceText = TranslationCacheStore.normalizedText(sourceText)
        self.sourceLanguage = sourceLanguage
        self.targetLanguage = targetLanguage
        self.providerIdentifier = providerIdentifier
        self.providerVersion = providerVersion
        resourceIdentity = ReaderLocatorIdentity.resource(from: locatorJSON)
    }
}

/// Manual retry throttling follows the selected text and publication resource,
/// not the concrete provider. Otherwise switching to a fallback provider could
/// accidentally bypass the five-second protection and create duplicate cost.
struct ReaderTranslationRetryIdentity: Hashable, Sendable {
    let bookID: UUID
    let sourceText: String
    let sourceLanguage: LanguageCode?
    let targetLanguage: LanguageCode
    let resourceIdentity: String?

    init(
        bookID: UUID,
        sourceText: String,
        sourceLanguage: LanguageCode?,
        targetLanguage: LanguageCode,
        locatorJSON: String?
    ) {
        self.bookID = bookID
        self.sourceText = TranslationCacheStore.normalizedText(sourceText)
        self.sourceLanguage = sourceLanguage
        self.targetLanguage = targetLanguage
        resourceIdentity = ReaderLocatorIdentity.resource(from: locatorJSON)
    }
}

enum ReaderLocatorIdentity {
    static func resource(from locatorJSON: String?) -> String? {
        guard let locatorJSON else { return nil }
        let trimmed = locatorJSON.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        guard let data = trimmed.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data),
              let locator = object as? [String: Any]
        else {
            // Tests and defensive callers can provide an opaque location. Keep
            // it distinct instead of collapsing every malformed value to nil.
            return "opaque:\(trimmed)"
        }

        if let href = locator["href"] as? String {
            let normalizedHref = href.trimmingCharacters(in: .whitespacesAndNewlines)
            if !normalizedHref.isEmpty {
                return "href:\(normalizedHref)"
            }
        }
        return "json:\(trimmed)"
    }
}

enum ReaderTranslationResultSource: Equatable, Sendable {
    case cache
    case appleTranslation
    case directAPI
    case backendProxy
    case mock
}

struct ReaderTranslationTiming: Sendable {
    let appleLaunchRetryDelay: Duration
    let appleLaunchTimeout: Duration
    let appleRequestTimeout: Duration
    let backendRequestTimeout: Duration
    let contextExplanationRequestTimeout: Duration
    let manualRetryCooldown: Duration

    init(
        appleLaunchRetryDelay: Duration,
        appleLaunchTimeout: Duration,
        appleRequestTimeout: Duration,
        backendRequestTimeout: Duration,
        contextExplanationRequestTimeout: Duration? = nil,
        manualRetryCooldown: Duration = .seconds(5)
    ) {
        self.appleLaunchRetryDelay = appleLaunchRetryDelay
        self.appleLaunchTimeout = appleLaunchTimeout
        self.appleRequestTimeout = appleRequestTimeout
        self.backendRequestTimeout = backendRequestTimeout
        self.contextExplanationRequestTimeout =
            contextExplanationRequestTimeout ?? backendRequestTimeout
        self.manualRetryCooldown = manualRetryCooldown
    }

    static let standard = ReaderTranslationTiming(
        appleLaunchRetryDelay: .milliseconds(1_500),
        appleLaunchTimeout: .seconds(5),
        // A first language-pack download can legitimately take several
        // minutes. Keep it bounded, but do not cancel a healthy system
        // download at the old 30-second translation limit.
        appleRequestTimeout: .seconds(180),
        backendRequestTimeout: .seconds(30),
        contextExplanationRequestTimeout: .seconds(70),
        manualRetryCooldown: .seconds(5)
    )
}

enum ReaderTranslationDisplayError: Equatable, Sendable {
    case emptySelection
    case textTooLong(limit: Int)
    case unsupportedLanguage
    case sameLanguage
    case languageDownloadDeclined
    case userCancelled
    case translationUnavailable
    case providerConfigurationInvalid
    case providerAuthenticationFailed
    case providerUnavailable
    case appleLanguagePreparationTimedOut
    case timedOut
    case persistenceFailed
    case unknown

    var message: String {
        switch self {
        case .emptySelection:
            return "请选择需要翻译的文字。"
        case let .textTooLong(limit):
            return "所选段落过长，一次最多翻译 \(limit) 个字符。请切换到“点句”，或长按选择较短内容。"
        case .unsupportedLanguage:
            return "当前文本语言暂不支持翻译。Jerreader 优先支持中文、英语和日语。"
        case .sameLanguage:
            return "原文与目标语言相同，请在设置中选择其他目标语言。"
        case .languageDownloadDeclined:
            return "系统语言包下载未完成。请重试并允许下载，下载期间保持 App 在前台并连接网络。"
        case .userCancelled:
            return "已取消翻译。"
        case .translationUnavailable:
            return "暂时无法使用系统翻译。请联网重试；若系统下载一直没有进展，可先在 Apple“翻译”App 中下载原文和目标语言。"
        case .providerConfigurationInvalid:
            return "AI 翻译配置无效，请在阅读设置中检查服务商、API Key、模型和 HTTPS 地址。"
        case .providerAuthenticationFailed:
            return "AI 翻译验证失败，请检查保存在钥匙串中的 API Key 或代理凭据。"
        case .providerUnavailable:
            return "AI 翻译服务暂时无法连接，请检查网络、模型或稍后重试。"
        case .appleLanguagePreparationTimedOut:
            return "Apple 语言包准备时间过长。请保持 App 在前台并连接网络；也可先在 Apple“翻译”App 中下载原文和目标语言，再回到 Jerreader 重试。"
        case .timedOut:
            return "翻译等待时间过长，本次请求已停止。请检查网络后重试。"
        case .persistenceFailed:
            return "译文已经生成，但暂时无法保存到本机缓存。"
        case .unknown:
            return "翻译失败，请重试。"
        }
    }

    var canRetry: Bool {
        switch self {
        case .emptySelection, .textTooLong, .unsupportedLanguage, .sameLanguage,
             .providerConfigurationInvalid, .providerAuthenticationFailed:
            return false
        case .languageDownloadDeclined, .userCancelled, .translationUnavailable,
             .providerUnavailable, .appleLanguagePreparationTimedOut,
             .timedOut, .persistenceFailed, .unknown:
            return true
        }
    }
}

enum ReaderTranslationState: Equatable, Sendable {
    case idle
    case loading(ReaderTranslationRequest)
    case success(
        request: ReaderTranslationRequest,
        result: TranslationResult,
        source: ReaderTranslationResultSource
    )
    case failure(
        request: ReaderTranslationRequest,
        error: ReaderTranslationDisplayError
    )

    var request: ReaderTranslationRequest? {
        switch self {
        case .idle:
            return nil
        case let .loading(request),
             let .success(request, _, _),
             let .failure(request, _):
            return request
        }
    }
}

/// What a short tap on a word hands the lookup sheet.
struct ReaderShortTapLookup: Equatable, Sendable {
    let word: String
    /// The tapped sentence, saved with the word as its original sentence.
    let context: String
    let language: LanguageCode?
}

struct ReaderContextExplanationRequest: Identifiable, Equatable, Sendable {
    let id: UUID
    let focusedText: String
    let contextText: String?
    let sourceLanguage: LanguageCode?
    let responseLanguage: LanguageCode
    let providerIdentifier: String
    let providerVersion: String
    /// Nil for the plain sentence-structure analysis; set for an AI reading lens question.
    var lens: ReadingLensOptions? = nil
}

enum ReaderContextExplanationError: Equatable, Sendable {
    case configurationMissing
    case configurationInvalid
    case authenticationFailed
    case emptyResponse
    case timedOut
    case unavailable
    case invalidSelection

    var message: String {
        switch self {
        case .configurationMissing:
            return "请先在设置中设置 AI 大模型或 AI 代理。Apple 系统翻译不提供句子结构分析。"
        case .configurationInvalid:
            return "AI 语法分析配置无效，请检查 HTTPS 地址和模型名称。"
        case .authenticationFailed:
            return "AI 服务验证失败，请检查 API Key。"
        case .emptyResponse:
            return "AI 服务已返回，但没有可显示的语法分析。请重新生成。"
        case .timedOut:
            return "AI 语法分析等待超时，没有继续占用请求。请检查网络后重试。"
        case .unavailable:
            return "暂时无法生成语法分析，请检查网络或稍后重试。"
        case .invalidSelection:
            return "当前没有可以分析的句子。"
        }
    }

    var canRetry: Bool {
        switch self {
        case .emptyResponse, .timedOut, .unavailable:
            return true
        case .configurationMissing, .configurationInvalid,
             .authenticationFailed, .invalidSelection:
            return false
        }
    }
}

enum ReaderContextExplanationState: Equatable, Sendable {
    case idle
    case loading(ReaderContextExplanationRequest)
    case success(
        request: ReaderContextExplanationRequest,
        result: ContextExplanationResult
    )
    case failure(
        request: ReaderContextExplanationRequest?,
        error: ReaderContextExplanationError
    )
}

enum ReaderJapaneseSelection {
    static func isLikelyRubyAnnotation(_ text: String) -> Bool {
        let normalized = TranslationCacheStore.normalizedText(text)
        guard !normalized.isEmpty, normalized.count <= 32 else { return false }

        var containsKana = false
        for scalar in normalized.unicodeScalars {
            if CharacterSet.whitespacesAndNewlines.contains(scalar) {
                continue
            }
            let value = scalar.value
            let isKana = (0x3040 ... 0x30FF).contains(value)
                || (0x31F0 ... 0x31FF).contains(value)
                || (0xFF66 ... 0xFF9F).contains(value)
            if isKana {
                containsKana = true
                continue
            }
            // Ruby readings can include separators or punctuation. They are
            // still only treated as candidates here; the EPUB path confirms
            // the actual <rt>/<rp> DOM ancestry before replacing any text.
            guard CharacterSet.punctuationCharacters.contains(scalar)
                    || CharacterSet.symbols.contains(scalar)
            else { return false }
        }
        return containsKana
    }
}

enum ReaderLanguageDetector {
    static func detect(text: String, bookLanguage: String?) -> LanguageCode? {
        let containsKana = text.unicodeScalars.contains {
            (0x3040 ... 0x30FF).contains($0.value)
        }
        if containsKana {
            return .japanese
        }

        let containsLatin = text.unicodeScalars.contains {
            (0x0041 ... 0x005A).contains($0.value) ||
                (0x0061 ... 0x007A).contains($0.value)
        }
        let containsCJK = text.unicodeScalars.contains {
            (0x3400 ... 0x4DBF).contains($0.value) ||
                (0x4E00 ... 0x9FFF).contains($0.value)
        }
        if containsLatin, !containsCJK {
            return .english
        }

        if containsCJK {
            let recognizer = NLLanguageRecognizer()
            recognizer.processString(text)
            switch recognizer.dominantLanguage {
            case .japanese:
                return .japanese
            case .simplifiedChinese, .traditionalChinese:
                return .simplifiedChinese
            case .english where containsLatin:
                return .english
            default:
                break
            }

            if let language = supportedBookLanguage(bookLanguage),
               language == .japanese || language == .simplifiedChinese
            {
                return language
            }
            return .simplifiedChinese
        }

        if containsLatin {
            return .english
        }

        return supportedBookLanguage(bookLanguage)
    }

    private static func supportedBookLanguage(_ language: String?) -> LanguageCode? {
        guard let language = language?.lowercased() else { return nil }
        if language.hasPrefix("ja") {
            return .japanese
        }
        if language.hasPrefix("en") {
            return .english
        }
        if language.hasPrefix("zh") {
            return .simplifiedChinese
        }
        return nil
    }
}

enum ReaderTranslationAccentMetric {
    /// Long translations keep the original elastic accent. Only a one-line
    /// result gets a cap so the marker cannot stretch through the card's full
    /// proposed height.
    static func maximumHeight(for text: String) -> CGFloat? {
        let count = text.trimmingCharacters(in: .whitespacesAndNewlines).count
        return count <= 24 ? 22 : nil
    }
}

enum ReaderTranslationContentMetric {
    static func height(
        measured: CGFloat,
        maximum: CGFloat
    ) -> CGFloat {
        let safeMaximum = maximum.isFinite ? max(maximum, 54) : 54
        guard measured.isFinite, measured > 0 else { return 54 }
        return min(max(measured, 54), safeMaximum)
    }
}

enum ReaderTranslationViewportPolicy {
    /// Longer requests are allowed a larger maximum viewport. The actual card
    /// still hugs the measured result and only reaches this cap when its text
    /// genuinely needs to scroll.
    static func prefersExpandedMaximumHeight(
        sourceCharacterCount: Int,
        translatedCharacterCount: Int,
        isParagraph: Bool
    ) -> Bool {
        if isParagraph {
            return translatedCharacterCount >= 40
                || sourceCharacterCount >= 72
        }
        return translatedCharacterCount >= 64
            || sourceCharacterCount >= 96
    }
}

enum ReaderTranslationLayoutPolicy {
    static func usesVerticalSideAvoidance(
        isReflowable: Bool,
        preservesPublicationOrientation: Bool,
        publicationIsVertical: Bool,
        isJapaneseBook: Bool,
        selectionFrame: CGRect?
    ) -> Bool {
        guard isReflowable, preservesPublicationOrientation else {
            return false
        }
        if publicationIsVertical {
            return true
        }

        // EPUB language metadata is optional and frequently absent. A tall,
        // narrow live target is stronger evidence of a vertical column than
        // the language tag, while ordinary horizontal paragraphs are not
        // narrow enough to pass this fallback.
        _ = isJapaneseBook
        guard let frame = selectionFrame?.standardized,
              frame.width.isFinite,
              frame.height.isFinite,
              !frame.isEmpty
        else { return false }
        return frame.height >= 120 && frame.height >= frame.width * 1.6
    }

    static func selectionGap(
        isParagraph: Bool,
        usesVerticalSideAvoidance: Bool
    ) -> CGFloat {
        if usesVerticalSideAvoidance {
            // On a phone every point taken from the horizontal axis directly
            // reduces the usable adjacent floating-window width.
            return 12
        }
        return isParagraph ? 26 : 18
    }
}

enum ReaderTranslationOverlayPlacement {
    enum Edge: Equatable, Sendable {
        case above
        case below
        case left
        case right
        case top
    }

    struct Layout: Equatable, Sendable {
        let edge: Edge
        let position: CGPoint
        let cardWidth: CGFloat
        let maximumCardHeight: CGFloat
        let availableHeight: CGFloat
    }

    /// Converts a rectangle reported in the app window coordinate space into
    /// the local coordinate space of the SwiftUI overlay. Reader content can
    /// extend under the safe areas while the overlay itself starts inside
    /// them, so the origins must not be assumed to match.
    static func localSelectionFrame(
        _ windowFrame: CGRect?,
        relativeTo overlayFrame: CGRect
    ) -> CGRect? {
        guard let windowFrame,
              windowFrame.minX.isFinite,
              windowFrame.minY.isFinite,
              windowFrame.width.isFinite,
              windowFrame.height.isFinite,
              overlayFrame.minX.isFinite,
              overlayFrame.minY.isFinite,
              !windowFrame.isEmpty
        else { return nil }

        return windowFrame.standardized.offsetBy(
            dx: -overlayFrame.minX,
            dy: -overlayFrame.minY
        )
    }

    static func clampedPosition(
        _ position: CGPoint,
        viewportSize: CGSize,
        cardSize: CGSize,
        topInset: CGFloat,
        bottomInset: CGFloat,
        horizontalInset: CGFloat = 14
    ) -> CGPoint {
        let viewportWidth = max(viewportSize.width, 1)
        let viewportHeight = max(viewportSize.height, 1)
        let cardWidth = min(max(cardSize.width, 0), viewportWidth)
        let cardHeight = min(max(cardSize.height, 0), viewportHeight)
        let halfWidth = cardWidth / 2
        let halfHeight = cardHeight / 2
        let minimumX = min(viewportWidth / 2, horizontalInset + halfWidth)
        let maximumX = max(minimumX, viewportWidth - horizontalInset - halfWidth)
        let minimumY = min(viewportHeight / 2, topInset + halfHeight)
        let maximumY = max(minimumY, viewportHeight - bottomInset - halfHeight)

        return CGPoint(
            x: min(max(position.x, minimumX), maximumX),
            y: min(max(position.y, minimumY), maximumY)
        )
    }

    /// Chooses a stable region before positioning the measured card. The
    /// region depends on the free space around the selection, not on the
    /// card's current height, so loading/result height changes cannot make the
    /// overlay repeatedly jump between the two sides.
    static func layout(
        selectionFrame: CGRect?,
        horizontalAvoidanceFrame: CGRect? = nil,
        viewportSize: CGSize,
        cardSize: CGSize,
        topInset: CGFloat = 58,
        bottomInset: CGFloat = 42,
        horizontalInset: CGFloat = 16,
        gap: CGFloat = 18,
        minimumCardHeight: CGFloat = 64,
        preferredMaximumCardHeight: CGFloat = 196,
        prefersTop: Bool = false,
        prefersHorizontalAvoidance: Bool = false,
        minimumHorizontalCardWidth: CGFloat = 144
    ) -> Layout {
        let viewportWidth = max(viewportSize.width, 1)
        let viewportHeight = max(viewportSize.height, 1)
        let safeTop = min(max(topInset, 0), viewportHeight)
        let safeBottom = max(safeTop, viewportHeight - max(bottomInset, 0))
        let safeHeight = max(safeBottom - safeTop, 1)
        let minimumHeight = min(max(minimumCardHeight, 1), safeHeight)
        let preferredHeight = min(
            max(preferredMaximumCardHeight, minimumHeight),
            safeHeight
        )
        let fallbackCardHeight = min(max(cardSize.height, 1), preferredHeight)
        let fallbackPosition = clampedPosition(
            CGPoint(
                x: viewportWidth / 2,
                y: safeTop + fallbackCardHeight / 2
            ),
            viewportSize: viewportSize,
            cardSize: CGSize(width: cardSize.width, height: fallbackCardHeight),
            topInset: safeTop,
            bottomInset: viewportHeight - safeBottom,
            horizontalInset: horizontalInset
        )
        let fallback = Layout(
            edge: .top,
            position: fallbackPosition,
            cardWidth: cardSize.width,
            maximumCardHeight: preferredHeight,
            availableHeight: safeHeight
        )

        guard let rawFrame = selectionFrame,
              rawFrame.minX.isFinite,
              rawFrame.minY.isFinite,
              rawFrame.width.isFinite,
              rawFrame.height.isFinite,
              !rawFrame.isEmpty
        else {
            return fallback
        }

        let viewportBounds = CGRect(
            x: 0,
            y: 0,
            width: viewportWidth,
            height: viewportHeight
        )
        let visibleFrame = rawFrame.standardized.intersection(viewportBounds)
        guard !visibleFrame.isNull, !visibleFrame.isEmpty else { return fallback }

        if prefersTop {
            let fallbackFrame = CGRect(
                x: fallback.position.x - cardSize.width / 2,
                y: fallback.position.y - fallbackCardHeight / 2,
                width: cardSize.width,
                height: fallbackCardHeight
            )
            // "顶部悬浮" is a preference, not permission to cover the
            // sentence being translated. Keep the top position when it is
            // clear and fall through to the normal above/below solver when it
            // would collide with the selected text.
            if !fallbackFrame.intersects(
                visibleFrame.insetBy(dx: -gap, dy: -gap)
            ) {
                return fallback
            }
        }

        // A vertical publication normally leaves usable space beside the
        // selected column while the selection itself can extend almost from
        // top to bottom. Prefer that horizontal space when the measured card
        // really fits; falling back to above/below remains safer on compact
        // screens or multi-column paragraph selections.
        if prefersHorizontalAvoidance {
            let safeHorizontalInset = min(
                max(horizontalInset, 0),
                viewportWidth / 2
            )
            let safeLeft = safeHorizontalInset
            let safeRight = viewportWidth - safeHorizontalInset
            let preferredWidth = min(
                max(cardSize.width, 1),
                max(safeRight - safeLeft, 1)
            )
            let requiredWidth = min(
                preferredWidth,
                max(minimumHorizontalCardWidth, 1)
            )
            let fallbackHorizontalFrame = horizontalAvoidanceFrame?
                .standardized
                .intersection(viewportBounds)
            let avoidanceFrames = [visibleFrame, fallbackHorizontalFrame]
                .compactMap { frame -> CGRect? in
                    guard let frame, !frame.isNull, !frame.isEmpty else {
                        return nil
                    }
                    return frame
                }

            for avoidanceFrame in avoidanceFrames {
                let roomLeft = max(
                    avoidanceFrame.minX - gap - safeLeft,
                    0
                )
                let roomRight = max(
                    safeRight - gap - avoidanceFrame.maxX,
                    0
                )
                let measuredWidth = min(
                    preferredWidth,
                    max(roomLeft, roomRight).rounded(.down)
                )
                let leftFits = roomLeft >= requiredWidth
                let rightFits = roomRight >= requiredWidth

                guard leftFits || rightFits else { continue }
                let edge: Edge = leftFits && (!rightFits || roomLeft >= roomRight)
                    ? .left
                    : .right
                let maximumCardHeight = preferredHeight
                let measuredHeight = min(
                    max(cardSize.height, 1),
                    maximumCardHeight
                )
                let proposedX = edge == .left
                    ? avoidanceFrame.minX - gap - measuredWidth / 2
                    : avoidanceFrame.maxX + gap + measuredWidth / 2
                let position = clampedPosition(
                    CGPoint(x: proposedX, y: avoidanceFrame.midY),
                    viewportSize: viewportSize,
                    cardSize: CGSize(
                        width: measuredWidth,
                        height: measuredHeight
                    ),
                    topInset: safeTop,
                    bottomInset: viewportHeight - safeBottom,
                    horizontalInset: horizontalInset
                )
                return Layout(
                    edge: edge,
                    position: position,
                    cardWidth: measuredWidth,
                    maximumCardHeight: maximumCardHeight,
                    availableHeight: safeHeight
                )
            }
        }

        let roomAbove = max(visibleFrame.minY - gap - safeTop, 0)
        let roomBelow = max(safeBottom - gap - visibleFrame.maxY, 0)
        let aboveFitsMinimum = roomAbove >= minimumHeight
        let belowFitsMinimum = roomBelow >= minimumHeight

        let edge: Edge
        switch (aboveFitsMinimum, belowFitsMinimum) {
        case (true, true):
            edge = roomAbove >= roomBelow ? .above : .below
        case (true, false):
            edge = .above
        case (false, true):
            edge = .below
        case (false, false):
            // A selection can cover most of a small screen. Use the larger
            // remaining region and keep the window compact instead of
            // expanding across the selected text.
            edge = roomAbove >= roomBelow ? .above : .below
        }

        let availableHeight = edge == .above ? roomAbove : roomBelow
        let maximumCardHeight = min(
            max(availableHeight, minimumHeight),
            preferredHeight
        )
        let measuredHeight = min(max(cardSize.height, 1), maximumCardHeight)
        let proposedY: CGFloat
        switch edge {
        case .above:
            proposedY = visibleFrame.minY - gap - measuredHeight / 2
        case .below:
            proposedY = visibleFrame.maxY + gap + measuredHeight / 2
        case .left, .right:
            proposedY = visibleFrame.midY
        case .top:
            proposedY = safeTop + measuredHeight / 2
        }

        let position = clampedPosition(
            CGPoint(x: visibleFrame.midX, y: proposedY),
            viewportSize: viewportSize,
            cardSize: CGSize(width: cardSize.width, height: measuredHeight),
            topInset: safeTop,
            bottomInset: viewportHeight - safeBottom,
            horizontalInset: horizontalInset
        )
        return Layout(
            edge: edge,
            position: position,
            cardWidth: cardSize.width,
            maximumCardHeight: maximumCardHeight,
            availableHeight: availableHeight
        )
    }

    static func position(
        selectionFrame: CGRect?,
        viewportSize: CGSize,
        cardSize: CGSize,
        topInset: CGFloat = 58,
        bottomInset: CGFloat = 42,
        horizontalInset: CGFloat = 14,
        gap: CGFloat = 12
    ) -> CGPoint {
        layout(
            selectionFrame: selectionFrame,
            viewportSize: viewportSize,
            cardSize: cardSize,
            topInset: topInset,
            bottomInset: bottomInset,
            horizontalInset: horizontalInset,
            gap: gap,
            minimumCardHeight: min(max(cardSize.height, 1), viewportSize.height),
            preferredMaximumCardHeight: max(cardSize.height, 1)
        ).position
    }

    static func centerY(
        selectionFrame: CGRect?,
        viewportHeight: CGFloat,
        cardHeight: CGFloat,
        topInset: CGFloat = 58,
        bottomInset: CGFloat = 42,
        gap: CGFloat = 12
    ) -> CGFloat {
        position(
            selectionFrame: selectionFrame,
            viewportSize: CGSize(width: 10_000, height: viewportHeight),
            cardSize: CGSize(width: 0, height: cardHeight),
            topInset: topInset,
            bottomInset: bottomInset,
            horizontalInset: 0,
            gap: gap
        ).y
    }
}
