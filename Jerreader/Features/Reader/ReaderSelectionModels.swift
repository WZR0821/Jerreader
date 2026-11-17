import CoreGraphics
import Foundation
import NaturalLanguage
@preconcurrency import ReadiumShared

// What a tap or selection in the reader resolves to: selection payloads,
// PDF annotation anchors, tap policy and smart word/sentence selection.

struct ReaderSelectionPayload: Equatable, Sendable {
    let text: String
    let contextText: String?
    let locatorJSON: String?
    /// A selection locator suitable for a persistent Readium decoration.
    /// Quick sentence translation can differ from the page's current locator.
    let annotationLocatorJSON: String?
    /// Optional PDF page geometry. EPUB annotations use the locator only.
    let annotationAnchorJSON: String?
    let frame: CGRect?
    /// The glyph or column which initiated a quick translation. A paragraph
    /// selection can span several vertical columns, so this narrower anchor
    /// lets the overlay choose the opposite side without losing the complete
    /// selection rectangle used for highlighting and annotation.
    let focusFrame: CGRect?
    let trigger: ReaderTranslationTrigger

    init(
        text: String,
        contextText: String? = nil,
        locatorJSON: String?,
        annotationLocatorJSON: String? = nil,
        annotationAnchorJSON: String? = nil,
        allowsAnnotation: Bool = true,
        frame: CGRect?,
        focusFrame: CGRect? = nil,
        trigger: ReaderTranslationTrigger = .smartSelection
    ) {
        self.text = text
        self.contextText = contextText
        self.locatorJSON = locatorJSON
        self.annotationLocatorJSON = allowsAnnotation
            ? (annotationLocatorJSON ?? locatorJSON)
            : nil
        self.annotationAnchorJSON = annotationAnchorJSON
        self.frame = frame
        self.focusFrame = focusFrame
        self.trigger = trigger
    }
}

enum ReaderTranslationTrigger: String, Equatable, Sendable {
    case quickSentence
    /// A short tap on a word with quick translation off: opens the lookup
    /// sheet instead of a translation card.
    case quickWord
    case smartSelection
    case preciseSelection
    case crossPageExpansion
}

struct ReaderNormalizedRectangle: Codable, Equatable, Sendable {
    let x: Double
    let y: Double
    let width: Double
    let height: Double

    init(_ rectangle: CGRect) {
        x = rectangle.minX
        y = rectangle.minY
        width = rectangle.width
        height = rectangle.height
    }

    var cgRect: CGRect {
        CGRect(x: x, y: y, width: width, height: height)
    }
}

struct ReaderPDFAnnotationAnchor: Codable, Equatable, Sendable {
    enum CoordinateSpace: String, Codable, Sendable {
        /// Coordinates normalized against PDFPage.bounds(for:).
        case pdfPage
        /// Vision's normalized, lower-left-origin image coordinates.
        case vision
    }

    let pageIndex: Int
    let coordinateSpace: CoordinateSpace
    let rectangles: [ReaderNormalizedRectangle]

    var jsonString: String? {
        guard let data = try? JSONEncoder().encode(self) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    init(
        pageIndex: Int,
        coordinateSpace: CoordinateSpace,
        rectangles: [CGRect]
    ) {
        self.pageIndex = pageIndex
        self.coordinateSpace = coordinateSpace
        self.rectangles = rectangles.map(ReaderNormalizedRectangle.init)
    }

    init?(jsonString: String?) {
        guard let jsonString,
              let data = jsonString.data(using: .utf8),
              let value = try? JSONDecoder().decode(Self.self, from: data)
        else { return nil }
        self = value
    }
}

extension Locator {
    /// Keeps persistence call sites independent from Readium's JSON API shape.
    ///
    /// Readium's cache-fix revision replaced the optional `jsonString`
    /// property with a throwing `jsonString()` method. Keeping the adaptation
    /// here prevents storage and UI code from depending on that package detail.
    var readerJSONString: String? {
        try? jsonString()
    }
}

struct ReaderAnnotationPresentation: Identifiable {
    let id: UUID
    let locatorJSON: String
    let anchorJSON: String?
    let color: ReadingAnnotationColor

    init(_ record: ReadingAnnotationRecord) {
        id = record.id
        locatorJSON = record.locatorJSON
        anchorJSON = record.anchorJSON
        color = record.color
    }
}

struct ReaderAnnotationEditorDraft: Identifiable {
    let id = UUID()
    let annotationID: UUID?
    let locatorJSON: String
    let anchorJSON: String?
    let selectedText: String
    var noteText: String
    var color: ReadingAnnotationColor
    let chapterTitle: String
    let progress: Double
}

/// Keeps the quick-translation gesture policy independent from the concrete
/// Readium navigator. A user can reserve taps for sentence translation without
/// losing swipe pagination, then restore edge-tap pagination when returning to
/// native long-press selection.
enum ReaderTapInteractionPolicy {
    static func suppressesPageTurn(
        quickSentenceTranslationEnabled: Bool,
        disablesTapPageTurnsDuringQuickTranslation: Bool
    ) -> Bool {
        quickSentenceTranslationEnabled
            && disablesTapPageTurnsDuringQuickTranslation
    }
}

struct ReaderSelectionResolution: Equatable, Sendable {
    let text: String
    let trigger: ReaderTranslationTrigger
}

/// Keeps the first native long-press selection smart, while treating every
/// later text-range adjustment as an explicit request for the exact range.
/// Readium does not expose the private WebKit selection-handle gesture phase,
/// so the stable and testable signal is whether the selected text itself has
/// changed during the current selection session.
struct ReaderSelectionIntentResolver: Sendable {
    private var initialText: String?
    private var isPreciseSelection = false

    mutating func resolve(
        highlight: String,
        before: String?,
        after: String?
    ) -> ReaderSelectionResolution? {
        let rawText = TranslationCacheStore.normalizedText(highlight)
        guard !rawText.isEmpty else { return nil }

        if let initialText {
            if rawText != initialText {
                isPreciseSelection = true
            }
        } else {
            initialText = rawText
        }

        if isPreciseSelection {
            return ReaderSelectionResolution(
                text: rawText,
                trigger: .preciseSelection
            )
        }

        let smartText = ReaderSmartSelection.resolvedText(
            highlight: highlight,
            before: before,
            after: after
        )
        guard !smartText.isEmpty else { return nil }
        return ReaderSelectionResolution(
            text: smartText,
            trigger: .smartSelection
        )
    }

    /// The long-press fallback has already delivered `text` exactly as
    /// selected. Handle drags that follow belong to the same selection, so
    /// they stay exact; resetting here made the next drag count as a fresh
    /// long press, and smart completion grew it into the surrounding
    /// "sentence", sometimes the whole paragraph.
    mutating func markPrecise(after text: String) {
        initialText = TranslationCacheStore.normalizedText(text)
        isPreciseSelection = true
    }

    mutating func reset() {
        initialText = nil
        isPreciseSelection = false
    }
}

enum ReaderSmartSelection {
    /// Longest sentence a multi-token selection is completed to. Readium gives
    /// at most 200 characters on each side, so a longer "sentence" means the
    /// segmenter found no boundary nearby; translating it would translate the
    /// paragraph around the selection, so the exact range is kept instead.
    static let maximumCompletedSentenceLength = 220

    /// Snaps a partial single-token selection to its whole word. When several
    /// tokens are selected, available Readium context is used to complete the
    /// nearest language-aware sentence without modifying the publication or
    /// its highlight.
    static func resolvedText(
        highlight: String,
        before: String?,
        after: String?
    ) -> String {
        let normalizedHighlight = TranslationCacheStore.normalizedText(highlight)
        guard !normalizedHighlight.isEmpty else { return "" }

        let beforeText = before ?? ""
        let context = beforeText + highlight + (after ?? "")
        let selectionStart = context.index(
            context.startIndex,
            offsetBy: beforeText.count
        )
        let selectionEnd = context.index(
            selectionStart,
            offsetBy: highlight.count
        )

        let language = ReaderLanguageDetector.detect(text: context, bookLanguage: nil)
        let tokenizer = NLTokenizer(unit: .word)
        tokenizer.string = context
        if let language {
            switch language {
            case .japanese: tokenizer.setLanguage(.japanese)
            case .english: tokenizer.setLanguage(.english)
            case .simplifiedChinese: tokenizer.setLanguage(.simplifiedChinese)
            }
        }

        let selectionRange = selectionStart ..< selectionEnd
        var overlappingTokens: [Range<String.Index>] = []
        tokenizer.enumerateTokens(in: context.startIndex ..< context.endIndex) { range, _ in
            if range.overlaps(selectionRange) {
                overlappingTokens.append(range)
            }
            return true
        }

        if overlappingTokens.count == 1, let wordRange = overlappingTokens.first {
            return TranslationCacheStore.normalizedText(String(context[wordRange]))
        }

        guard overlappingTokens.count > 1 else { return normalizedHighlight }

        // Readium supplies up to 200 characters on each side of a native
        // selection. Treating every period in those fragments as a sentence
        // boundary breaks common English text such as "Dr.", "J. K.",
        // "U.S." and decimal numbers. Use the same sentence resolver as the
        // quick-tap path, probing the middle of the selected UTF-16 range.
        var contentStart = highlight.startIndex
        var contentEnd = highlight.endIndex
        while contentStart < contentEnd, highlight[contentStart].isWhitespace {
            contentStart = highlight.index(after: contentStart)
        }
        while contentStart < contentEnd {
            let previous = highlight.index(before: contentEnd)
            guard highlight[previous].isWhitespace else { break }
            contentEnd = previous
        }
        let selectionStartUTF16 = beforeText.utf16.count
            + highlight[..<contentStart].utf16.count
        let selectionEndUTF16 = selectionStartUTF16
            + highlight[contentStart ..< contentEnd].utf16.count
        let probeOffset = selectionStartUTF16
            + max((selectionEndUTF16 - selectionStartUTF16 - 1) / 2, 0)
        guard let sentence = ReaderSentenceSegmenter.sentence(
            in: context,
            utf16Offset: probeOffset,
            language: language
        ),
        sentence.utf16Range.lowerBound <= selectionStartUTF16,
        sentence.utf16Range.upperBound >= selectionEndUTF16
        else {
            // A selection crossing a real sentence boundary is intentional;
            // preserve it instead of guessing which sentence the user meant.
            return normalizedHighlight
        }
        guard sentence.text.count <= maximumCompletedSentenceLength else {
            return normalizedHighlight
        }
        return sentence.text
    }
}
