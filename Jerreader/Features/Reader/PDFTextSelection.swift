import CoreGraphics
import Foundation

// Sentence selection on PDF pages: the PDFKit text layer, paper-mode text
// lines and OCR observations mapped back to page coordinates.

/// PDF text layers encode visual line wraps as hard newlines and may place
/// unrelated columns or paragraphs next to each other in the extracted page
/// string. Resolve within the local paragraph window first, then map the
/// sentence range back to PDFKit's page-wide UTF-16 offsets.
enum PDFTextLayerSentenceSelector {
    private struct Line {
        let contentRange: NSRange
        let content: String
        let isBlank: Bool
        let leadingWhitespaceCount: Int
    }

    static func sentence(
        in pageText: String,
        utf16Offset: Int,
        language: LanguageCode?
    ) -> ReaderSentenceSegment? {
        let source = pageText as NSString
        guard source.length > 0 else { return nil }
        let offset = min(max(utf16Offset, 0), source.length - 1)
        let lines = physicalLines(in: source)
        guard let targetIndex = lines.indices.min(by: {
            distance(from: offset, to: lines[$0].contentRange)
                < distance(from: offset, to: lines[$1].contentRange)
        }), !lines[targetIndex].isBlank else {
            return ReaderSentenceSegmenter.sentence(
                in: pageText,
                utf16Offset: offset,
                language: language
            )
        }

        var lower = targetIndex
        var upper = targetIndex
        var characterBudget = lines[targetIndex].contentRange.length

        while lower > lines.startIndex {
            let candidate = lower - 1
            guard !lines[candidate].isBlank,
                  !startsNewParagraph(previous: lines[candidate], next: lines[lower]),
                  characterBudget + lines[candidate].contentRange.length
                    <= ReaderSentenceSegmenter.maximumContextCharacterCount
            else { break }
            lower = candidate
            characterBudget += lines[candidate].contentRange.length
        }
        while upper < lines.index(before: lines.endIndex) {
            let candidate = upper + 1
            guard !lines[candidate].isBlank,
                  !startsNewParagraph(previous: lines[upper], next: lines[candidate]),
                  characterBudget + lines[candidate].contentRange.length
                    <= ReaderSentenceSegmenter.maximumContextCharacterCount
            else { break }
            upper = candidate
            characterBudget += lines[candidate].contentRange.length
        }

        let windowStart = lines[lower].contentRange.location
        let windowEnd = NSMaxRange(lines[upper].contentRange)
        guard windowEnd > windowStart else { return nil }
        let windowRange = NSRange(location: windowStart, length: windowEnd - windowStart)
        let windowText = source.substring(with: windowRange)
        guard let local = ReaderSentenceSegmenter.sentence(
            in: windowText,
            utf16Offset: offset - windowStart,
            language: language
        ) else { return nil }

        let globalRange = (local.utf16Range.lowerBound + windowStart)
            ..< (local.utf16Range.upperBound + windowStart)

        // A PDF text layer frequently has no sentence terminators at all —
        // hidden OCR layers and CJK typesetting both produce long runs where
        // the tokenizer finds no boundary and hands back the entire window.
        // Translating a whole page because the reader tapped one line is worse
        // than translating slightly too little, so fall back to the tapped
        // line when the result is implausibly long.
        guard local.text.count > maximumTappedSentenceCharacterCount else {
            return ReaderSentenceSegment(
                text: local.text,
                contextText: local.contextText,
                utf16Range: globalRange
            )
        }
        return narrowedSegment(
            around: offset,
            line: lines[targetIndex],
            source: source,
            language: language,
            contextText: local.contextText
        ) ?? ReaderSentenceSegment(
            text: local.text,
            contextText: local.contextText,
            utf16Range: globalRange
        )
    }

    static func paragraph(
        in pageText: String,
        utf16Offset: Int
    ) -> ReaderSentenceSegment? {
        let source = pageText as NSString
        guard source.length > 0 else { return nil }
        let offset = min(max(utf16Offset, 0), source.length - 1)
        let lines = physicalLines(in: source)
        guard let targetIndex = lines.indices.min(by: {
            distance(from: offset, to: lines[$0].contentRange)
                < distance(from: offset, to: lines[$1].contentRange)
        }), !lines[targetIndex].isBlank else {
            return ReaderParagraphSegmenter.paragraph(
                in: pageText,
                utf16Offset: offset
            )
        }

        var lower = targetIndex
        var upper = targetIndex
        var characterBudget = lines[targetIndex].contentRange.length
        let maximumParagraphLength = 2_000
        while lower > lines.startIndex {
            let candidate = lower - 1
            guard !lines[candidate].isBlank,
                  !startsNewParagraph(
                      previous: lines[candidate],
                      next: lines[lower]
                  ),
                  characterBudget + lines[candidate].contentRange.length
                    <= maximumParagraphLength
            else { break }
            lower = candidate
            characterBudget += lines[candidate].contentRange.length
        }
        while upper < lines.index(before: lines.endIndex) {
            let candidate = upper + 1
            guard !lines[candidate].isBlank,
                  !startsNewParagraph(
                      previous: lines[upper],
                      next: lines[candidate]
                  ),
                  characterBudget + lines[candidate].contentRange.length
                    <= maximumParagraphLength
            else { break }
            upper = candidate
            characterBudget += lines[candidate].contentRange.length
        }

        let windowStart = lines[lower].contentRange.location
        let windowEnd = NSMaxRange(lines[upper].contentRange)
        guard windowEnd > windowStart else { return nil }
        let windowRange = NSRange(
            location: windowStart,
            length: windowEnd - windowStart
        )
        let windowText = source.substring(with: windowRange)
        guard let local = ReaderParagraphSegmenter.paragraph(
            in: windowText,
            utf16Offset: offset - windowStart
        ) else { return nil }
        return ReaderSentenceSegment(
            text: local.text,
            contextText: local.contextText,
            utf16Range: (local.utf16Range.lowerBound + windowStart)
                ..< (local.utf16Range.upperBound + windowStart)
        )
    }

    /// Longest run accepted as "one sentence" from a single tap.
    static let maximumTappedSentenceCharacterCount = 220

    /// Restricts the selection to the tapped physical line, then to a clause
    /// inside it when even the line is unreasonably long.
    private static func narrowedSegment(
        around offset: Int,
        line: Line,
        source: NSString,
        language: LanguageCode?,
        contextText: String?
    ) -> ReaderSentenceSegment? {
        let lineRange = line.contentRange
        guard lineRange.length > 0 else { return nil }
        let lineText = source.substring(with: lineRange)

        if let local = ReaderSentenceSegmenter.sentence(
            in: lineText,
            utf16Offset: offset - lineRange.location,
            language: language
        ), local.text.count <= maximumTappedSentenceCharacterCount {
            return ReaderSentenceSegment(
                text: local.text,
                contextText: contextText ?? lineText,
                utf16Range: (local.utf16Range.lowerBound + lineRange.location)
                    ..< (local.utf16Range.upperBound + lineRange.location)
            )
        }

        // Still too long: cut at the clause marks around the tap.
        let caret = min(max(offset - lineRange.location, 0), lineRange.length)
        let text = lineText as NSString
        var start = caret
        var end = caret
        while start > 0,
              caret - start < maximumTappedSentenceCharacterCount,
              !isClauseBoundary(text.character(at: start - 1)) {
            start -= 1
        }
        while end < text.length,
              end - caret < maximumTappedSentenceCharacterCount,
              !isClauseBoundary(text.character(at: end)) {
            end += 1
        }
        if end < text.length, isClauseBoundary(text.character(at: end)) {
            end += 1
        }
        guard end > start else { return nil }
        let clause = text.substring(with: NSRange(location: start, length: end - start))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clause.isEmpty else { return nil }
        return ReaderSentenceSegment(
            text: clause,
            contextText: contextText ?? lineText,
            utf16Range: (start + lineRange.location) ..< (end + lineRange.location)
        )
    }

    private static func isClauseBoundary(_ unit: unichar) -> Bool {
        guard let scalar = UnicodeScalar(unit) else { return false }
        return "。．.！!？?…‥；;：:、，,«»「」『』（）()".unicodeScalars
            .contains(scalar)
    }

    private static func physicalLines(in text: NSString) -> [Line] {
        var result: [Line] = []
        var cursor = 0
        while cursor < text.length {
            var start = 0
            var end = 0
            var contentsEnd = 0
            text.getLineStart(
                &start,
                end: &end,
                contentsEnd: &contentsEnd,
                for: NSRange(location: cursor, length: 0)
            )
            let contentRange = NSRange(
                location: start,
                length: max(contentsEnd - start, 0)
            )
            let content = text.substring(with: contentRange)
            result.append(
                Line(
                    contentRange: contentRange,
                    content: content,
                    isBlank: content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                    leadingWhitespaceCount: content.prefix { $0.isWhitespace }.count
                )
            )
            guard end > cursor else { break }
            cursor = end
        }
        return result
    }

    private static func distance(from offset: Int, to range: NSRange) -> Int {
        if NSLocationInRange(offset, range) { return 0 }
        if offset < range.location { return range.location - offset }
        return max(offset - NSMaxRange(range), 0)
    }

    private static func startsNewParagraph(previous: Line, next: Line) -> Bool {
        if next.leadingWhitespaceCount >= previous.leadingWhitespaceCount + 2 {
            return true
        }
        return isShortHeading(previous.content, before: next.content)
    }

    /// PDF generators frequently omit a blank line between a heading and the
    /// first body line. Natural-language sentence tokenizers then merge both
    /// because the heading has no period. Keep ordinary visual line wraps
    /// joinable, but stop at a short title-like line followed by a new sentence.
    private static func isShortHeading(_ previous: String, before next: String) -> Bool {
        let heading = previous.trimmingCharacters(in: .whitespacesAndNewlines)
        let body = next.trimmingCharacters(in: .whitespacesAndNewlines)
        let endsSentence = heading.unicodeScalars.last.map {
            isSentenceTerminator($0)
        } ?? false
        guard !heading.isEmpty,
              !body.isEmpty,
              !endsSentence,
              beginsLikeNewSentence(body)
        else { return false }

        let headingLength = heading.count
        let bodyLength = body.count
        guard headingLength <= 64,
              headingLength <= max(28, Int(Double(bodyLength) * 0.72))
        else { return false }

        if heading.unicodeScalars.contains(where: isCJK(_:)) {
            return headingLength <= 24
        }

        let words = heading.split(whereSeparator: { $0.isWhitespace })
        guard !words.isEmpty, words.count <= 12 else { return false }
        let titleLikeWords = words.filter { word in
            let letters = word.unicodeScalars.filter {
                CharacterSet.letters.contains($0)
            }
            guard let first = letters.first else { return true }
            return CharacterSet.uppercaseLetters.contains(first)
                || letters.allSatisfy { CharacterSet.uppercaseLetters.contains($0) }
        }.count
        return titleLikeWords * 2 >= words.count
    }

    private static func beginsLikeNewSentence(_ text: String) -> Bool {
        guard let scalar = text.unicodeScalars.first(where: {
            CharacterSet.letters.contains($0)
        }) else { return false }
        return CharacterSet.uppercaseLetters.contains(scalar) || isCJK(scalar)
    }

    private static func isSentenceTerminator(_ scalar: UnicodeScalar) -> Bool {
        ".!?\u{3002}\u{ff01}\u{ff1f}".unicodeScalars.contains(scalar)
    }

    private static func isCJK(_ scalar: UnicodeScalar) -> Bool {
        switch scalar.value {
        case 0x3040 ... 0x30FF, 0x3400 ... 0x4DBF, 0x4E00 ... 0x9FFF,
             0xF900 ... 0xFAFF:
            true
        default:
            false
        }
    }
}

/// One physical text-layer line in PDF page coordinates. Paper mode uses
/// geometry only to choose a column; the character ranges still come from
/// PDFKit and are never inferred from rendered pixels.
struct PDFPaperTextLine: Equatable, Sendable {
    let text: String
    let pageUTF16Range: NSRange
    let bounds: CGRect
}

struct PDFPaperTextSelection: Equatable, Sendable {
    let text: String
    let contextText: String?
    let pageUTF16Ranges: [NSRange]
}

/// Builds a sentence or paragraph from the physical lines in the tapped
/// column. This is opt-in because ordinary PDFs can contain centered text,
/// forms or other layouts where a forced two-column interpretation is wrong.
enum PDFPaperTextSelector {
    private struct MappedLine {
        let source: PDFPaperTextLine
        let localRange: Range<Int>
    }

    static func selection(
        unit: ReaderQuickTranslationUnit,
        pageBounds: CGRect,
        pagePoint: CGPoint,
        utf16Offset: Int,
        lines: [PDFPaperTextLine],
        language: LanguageCode?
    ) -> PDFPaperTextSelection? {
        let page = pageBounds.standardized
        guard page.width > 0, page.height > 0 else { return nil }

        let validLines = lines.filter {
            let range = $0.pageUTF16Range
            let box = $0.bounds.standardized
            return range.location != NSNotFound
                && range.location >= 0
                && range.length > 0
                && range.length == $0.text.utf16.count
                && !box.isEmpty
                && box.minX.isFinite
                && box.minY.isFinite
                && box.width.isFinite
                && box.height.isFinite
        }
        guard !validLines.isEmpty else { return nil }

        let target = validLines.first(where: {
            NSLocationInRange(utf16Offset, $0.pageUTF16Range)
        }) ?? validLines.min(by: {
            squaredDistance(from: pagePoint, to: $0.bounds)
                < squaredDistance(from: pagePoint, to: $1.bounds)
        })
        guard let target else { return nil }

        // Full-width titles, abstracts and captions are intentionally handled
        // by the normal PDF selector. Paper mode only takes over when the tap
        // is clearly inside a body column and another body column exists.
        let maximumColumnWidth = page.width * 0.72
        guard target.bounds.width < maximumColumnWidth else { return nil }
        let dividerX = page.midX
        let targetIsLeft = target.bounds.midX < dividerX
        let bodyLines = validLines.filter { $0.bounds.width < maximumColumnWidth }
        guard bodyLines.contains(where: {
            ($0.bounds.midX < dividerX) != targetIsLeft
        }) else { return nil }

        let columnLines = bodyLines.filter {
            ($0.bounds.midX < dividerX) == targetIsLeft
        }.sorted { lhs, rhs in
            let verticalThreshold = max(lhs.bounds.height, rhs.bounds.height) * 0.45
            if abs(lhs.bounds.midY - rhs.bounds.midY) > verticalThreshold {
                // PDF page coordinates use a lower-left origin.
                return lhs.bounds.midY > rhs.bounds.midY
            }
            return lhs.pageUTF16Range.location < rhs.pageUTF16Range.location
        }
        guard columnLines.contains(where: {
            $0.pageUTF16Range == target.pageUTF16Range
        }) else { return nil }

        var columnText = ""
        var mapped: [MappedLine] = []
        mapped.reserveCapacity(columnLines.count)
        for line in columnLines {
            if !columnText.isEmpty { columnText.append("\n") }
            let start = columnText.utf16.count
            columnText.append(line.text)
            mapped.append(
                MappedLine(
                    source: line,
                    localRange: start ..< columnText.utf16.count
                )
            )
        }
        guard let targetMap = mapped.first(where: {
            $0.source.pageUTF16Range == target.pageUTF16Range
        }) else { return nil }
        let offsetWithinTarget = min(
            max(utf16Offset - target.pageUTF16Range.location, 0),
            max(targetMap.localRange.count - 1, 0)
        )
        let localOffset = targetMap.localRange.lowerBound + offsetWithinTarget

        let segment: ReaderSentenceSegment?
        switch unit {
        case .sentence:
            segment = PDFTextLayerSentenceSelector.sentence(
                in: columnText,
                utf16Offset: localOffset,
                language: language
            )
        case .paragraph:
            segment = PDFTextLayerSentenceSelector.paragraph(
                in: columnText,
                utf16Offset: localOffset
            )
        }
        guard let segment else { return nil }

        let pageRanges = mapped.compactMap { item -> NSRange? in
            let lower = max(
                item.localRange.lowerBound,
                segment.utf16Range.lowerBound
            )
            let upper = min(
                item.localRange.upperBound,
                segment.utf16Range.upperBound
            )
            guard lower < upper else { return nil }
            return NSRange(
                location: item.source.pageUTF16Range.location
                    + lower - item.localRange.lowerBound,
                length: upper - lower
            )
        }
        guard !pageRanges.isEmpty else { return nil }
        return PDFPaperTextSelection(
            text: segment.text,
            contextText: segment.contextText,
            pageUTF16Ranges: pageRanges
        )
    }

    private static func squaredDistance(
        from point: CGPoint,
        to rectangle: CGRect
    ) -> CGFloat {
        let box = rectangle.standardized
        let dx = max(max(box.minX - point.x, 0), point.x - box.maxX)
        let dy = max(max(box.minY - point.y, 0), point.y - box.maxY)
        return dx * dx + dy * dy
    }
}

enum PDFTranslationMenuPolicy {
    static func normalizedText(_ text: String?) -> String? {
        guard let text else { return nil }
        let normalized = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return normalized.isEmpty ? nil : normalized
    }

    static func canTranslate(_ text: String?) -> Bool {
        normalizedText(text) != nil
    }
}

struct PDFOCRTextObservation: Equatable, Sendable {
    let text: String
    let boundingBox: CGRect
}

struct PDFOCRSentenceSelection: Equatable, Sendable {
    let text: String
    let contextText: String?
    let boundingBoxes: [CGRect]
}

/// Maps between UIKit's top-left page rectangle and Vision's normalized,
/// bottom-left OCR coordinates. Using the visible page rectangle keeps taps
/// and highlights aligned for cropped or rotated PDF pages.
enum PDFOCRPageCoordinateMapper {
    static func normalizedPoint(
        _ point: CGPoint,
        in pageRectangle: CGRect,
        tolerance: CGFloat = 4
    ) -> CGPoint? {
        let page = pageRectangle.standardized
        guard page.width > 0,
              page.height > 0,
              page.insetBy(dx: -tolerance, dy: -tolerance).contains(point)
        else { return nil }

        return CGPoint(
            x: min(max((point.x - page.minX) / page.width, 0), 1),
            y: min(max(1 - (point.y - page.minY) / page.height, 0), 1)
        )
    }

    static func viewRectangle(
        for normalizedRectangle: CGRect,
        in pageRectangle: CGRect
    ) -> CGRect? {
        let page = pageRectangle.standardized
        let box = normalizedRectangle.standardized.intersection(
            CGRect(x: 0, y: 0, width: 1, height: 1)
        )
        guard page.width > 0,
              page.height > 0,
              !box.isNull,
              !box.isEmpty
        else { return nil }

        return CGRect(
            x: page.minX + box.minX * page.width,
            y: page.minY + (1 - box.maxY) * page.height,
            width: box.width * page.width,
            height: box.height * page.height
        ).standardized
    }
}

/// Converts Vision's page-relative OCR lines into the sentence nearest a tap.
/// The selector is independent from Vision and PDFKit so its reading-order and
/// hit-testing behavior can be covered by offline unit tests.
enum PDFOCRTextSelector {
    static func sentence(
        at point: CGPoint,
        observations: [PDFOCRTextObservation],
        preferredLanguage: LanguageCode?,
        paperMode: Bool = false
    ) -> PDFOCRSentenceSelection? {
        let unitPage = CGRect(x: 0, y: 0, width: 1, height: 1)
        let recognizedLines = observations.enumerated().compactMap { index, observation -> IndexedLine? in
            let text = TranslationCacheStore.normalizedText(observation.text)
            let box = observation.boundingBox.standardized.intersection(unitPage)
            guard !text.isEmpty,
                  !box.isNull,
                  !box.isEmpty,
                  box.width.isFinite,
                  box.height.isFinite
            else { return nil }
            return IndexedLine(id: index, text: text, box: box)
        }
        let linesWithoutRuby = recognizedLines.filter {
            !isLikelyRubyAnnotation($0, among: recognizedLines)
        }
        // Vision may return furigana as a separate, much smaller text line.
        // Prefer the base line for both hit testing and sentence assembly so
        // tapping near a kanji never translates the pronunciation alone.
        let lines = linesWithoutRuby.isEmpty ? recognizedLines : linesWithoutRuby
        guard !lines.isEmpty,
              let target = nearestLine(to: point, in: lines)
        else { return nil }

        let language = preferredLanguage
            ?? ReaderLanguageDetector.detect(text: target.text, bookLanguage: nil)
        // A large share of scanned Japanese PDFs omit language metadata, and
        // a kanji-only OCR line can be classified as Chinese. Geometry is the
        // reliable source of truth for vertical reading order.
        let isVerticalText = isVerticalFlow(target, among: lines)
        let flowCandidates = paperMode && !isVerticalText
            ? paperColumnLines(containing: target, among: lines)
            : lines
        let sameFlowLines = flowCandidates.filter {
            belongsToSameFlow(
                $0.box,
                as: target.box,
                vertical: isVerticalText
            )
        }
        let ordered = sameFlowLines.sorted {
            readingOrder($0.box, before: $1.box, vertical: isVerticalText)
        }
        guard let targetIndex = ordered.firstIndex(where: { $0.id == target.id }) else {
            return nil
        }

        let window = paragraphWindow(
            around: targetIndex,
            in: ordered,
            vertical: isVerticalText
        )
        let separator = language == .japanese || language == .simplifiedChinese
            ? ""
            : " "

        var combined = ""
        var ranges: [(line: IndexedLine, range: Range<Int>)] = []
        for line in window {
            if !combined.isEmpty {
                combined += separator
            }
            let start = combined.utf16.count
            combined += line.text
            ranges.append((line, start ..< combined.utf16.count))
        }
        guard let targetRange = ranges.first(where: { $0.line.id == target.id })?.range else {
            return nil
        }

        let relativePosition: CGFloat
        if isVerticalText {
            relativePosition = (target.box.maxY - point.y) / max(target.box.height, 0.0001)
        } else {
            relativePosition = (point.x - target.box.minX) / max(target.box.width, 0.0001)
        }
        let clampedPosition = min(max(relativePosition, 0), 0.999)
        let localOffset = min(
            Int((CGFloat(targetRange.count) * clampedPosition).rounded(.down)),
            max(targetRange.count - 1, 0)
        )
        let probeOffset = targetRange.lowerBound + localOffset

        guard let segment = ReaderSentenceSegmenter.sentence(
            in: combined,
            utf16Offset: probeOffset,
            language: language
        ) else {
            return PDFOCRSentenceSelection(
                text: target.text,
                contextText: combined == target.text ? nil : combined,
                boundingBoxes: [target.box]
            )
        }

        let boxes = ranges.compactMap {
            clippedBoundingBox(
                lineBox: $0.line.box,
                lineRange: $0.range,
                selectedRange: segment.utf16Range,
                vertical: isVerticalText
            )
        }
        return PDFOCRSentenceSelection(
            text: segment.text,
            contextText: segment.contextText == segment.text ? nil : segment.contextText,
            boundingBoxes: boxes.isEmpty ? [target.box] : boxes
        )
    }

    static func paragraph(
        at point: CGPoint,
        observations: [PDFOCRTextObservation],
        preferredLanguage: LanguageCode?,
        paperMode: Bool = false
    ) -> PDFOCRSentenceSelection? {
        let unitPage = CGRect(x: 0, y: 0, width: 1, height: 1)
        let recognizedLines = observations.enumerated().compactMap {
            index, observation -> IndexedLine? in
            let text = TranslationCacheStore.normalizedText(observation.text)
            let box = observation.boundingBox.standardized.intersection(unitPage)
            guard !text.isEmpty,
                  !box.isNull,
                  !box.isEmpty,
                  box.width.isFinite,
                  box.height.isFinite
            else { return nil }
            return IndexedLine(id: index, text: text, box: box)
        }
        let linesWithoutRuby = recognizedLines.filter {
            !isLikelyRubyAnnotation($0, among: recognizedLines)
        }
        let lines = linesWithoutRuby.isEmpty ? recognizedLines : linesWithoutRuby
        guard !lines.isEmpty,
              let target = nearestLine(to: point, in: lines)
        else { return nil }

        let language = preferredLanguage
            ?? ReaderLanguageDetector.detect(text: target.text, bookLanguage: nil)
        let vertical = isVerticalFlow(target, among: lines)
        let flowCandidates = paperMode && !vertical
            ? paperColumnLines(containing: target, among: lines)
            : lines
        let ordered = flowCandidates.filter {
            belongsToSameFlow($0.box, as: target.box, vertical: vertical)
        }.sorted {
            readingOrder($0.box, before: $1.box, vertical: vertical)
        }
        guard let targetIndex = ordered.firstIndex(where: {
            $0.id == target.id
        }) else { return nil }
        let window = fullParagraphWindow(
            around: targetIndex,
            in: ordered,
            vertical: vertical
        )
        let separator = language == .japanese
            || language == .simplifiedChinese ? "" : " "
        let text = TranslationCacheStore.normalizedText(
            window.map(\.text).joined(separator: separator)
        )
        guard !text.isEmpty,
              text.count <= 2_000
        else { return nil }
        return PDFOCRSentenceSelection(
            text: text,
            contextText: nil,
            boundingBoxes: window.map(\.box)
        )
    }

    private static func clippedBoundingBox(
        lineBox: CGRect,
        lineRange: Range<Int>,
        selectedRange: Range<Int>,
        vertical: Bool
    ) -> CGRect? {
        let lower = max(lineRange.lowerBound, selectedRange.lowerBound)
        let upper = min(lineRange.upperBound, selectedRange.upperBound)
        guard lower < upper, !lineRange.isEmpty else { return nil }

        let startRatio = CGFloat(lower - lineRange.lowerBound) / CGFloat(lineRange.count)
        let endRatio = CGFloat(upper - lineRange.lowerBound) / CGFloat(lineRange.count)
        if vertical {
            // Vision boxes use a bottom-left origin, while Japanese vertical
            // OCR strings run from the visual top toward the bottom.
            return CGRect(
                x: lineBox.minX,
                y: lineBox.maxY - endRatio * lineBox.height,
                width: lineBox.width,
                height: (endRatio - startRatio) * lineBox.height
            ).standardized
        }
        return CGRect(
            x: lineBox.minX + startRatio * lineBox.width,
            y: lineBox.minY,
            width: (endRatio - startRatio) * lineBox.width,
            height: lineBox.height
        ).standardized
    }

    private struct IndexedLine {
        let id: Int
        let text: String
        let box: CGRect
    }

    private static func isLikelyRubyAnnotation(
        _ annotation: IndexedLine,
        among lines: [IndexedLine]
    ) -> Bool {
        guard ReaderJapaneseSelection.isLikelyRubyAnnotation(annotation.text) else {
            return false
        }

        return lines.contains { base in
            guard base.id != annotation.id,
                  containsCJKIdeograph(base.text)
            else { return false }

            let horizontalOverlap = overlap(
                annotation.box.minX ... annotation.box.maxX,
                base.box.minX ... base.box.maxX
            ) / max(annotation.box.width, 0.0001)
            let verticalOverlap = overlap(
                annotation.box.minY ... annotation.box.maxY,
                base.box.minY ... base.box.maxY
            ) / max(annotation.box.height, 0.0001)

            let horizontalRuby = annotation.box.height <= base.box.height * 0.72
                && annotation.box.midY > base.box.midY
                && horizontalOverlap >= 0.45
                && axisGap(
                    annotation.box.minY ... annotation.box.maxY,
                    base.box.minY ... base.box.maxY
                ) <= max(base.box.height * 0.75, 0.014)
            let verticalRuby = annotation.box.width <= base.box.width * 0.72
                && annotation.box.midX > base.box.midX
                && verticalOverlap >= 0.45
                && axisGap(
                    annotation.box.minX ... annotation.box.maxX,
                    base.box.minX ... base.box.maxX
                ) <= max(base.box.width * 0.75, 0.014)
            return horizontalRuby || verticalRuby
        }
    }

    private static func containsCJKIdeograph(_ text: String) -> Bool {
        text.unicodeScalars.contains {
            (0x3400 ... 0x4DBF).contains($0.value)
                || (0x4E00 ... 0x9FFF).contains($0.value)
        }
    }

    private static func isVerticalFlow(
        _ target: IndexedLine,
        among lines: [IndexedLine]
    ) -> Bool {
        guard target.text.count >= 2,
              target.box.height > target.box.width * 1.55
        else { return false }
        if target.box.height >= 0.08 {
            return true
        }
        return lines.contains {
            $0.id != target.id
                && $0.box.height > $0.box.width * 1.35
                && overlap(
                    $0.box.minY ... $0.box.maxY,
                    target.box.minY ... target.box.maxY
                ) > 0
        }
    }

    /// Vision normally returns one observation per printed line, but its
    /// reading order can still alternate between two academic-paper columns.
    /// In paper mode, full-width headings/captions fall back to the existing
    /// flow logic while body text is restricted to the tapped half-page.
    private static func paperColumnLines(
        containing target: IndexedLine,
        among lines: [IndexedLine]
    ) -> [IndexedLine] {
        guard target.box.width < 0.72 else { return lines }
        let targetIsLeft = target.box.midX < 0.5
        let bodyLines = lines.filter { $0.box.width < 0.72 }
        guard bodyLines.contains(where: {
            ($0.box.midX < 0.5) != targetIsLeft
        }) else { return lines }
        return bodyLines.filter {
            ($0.box.midX < 0.5) == targetIsLeft
        }
    }

    private static func paragraphWindow(
        around targetIndex: Int,
        in ordered: [IndexedLine],
        vertical: Bool
    ) -> [IndexedLine] {
        var lower = targetIndex
        var upper = targetIndex + 1

        while lower > 0, targetIndex - lower < 4 {
            guard !startsNewParagraph(
                previous: ordered[lower - 1],
                next: ordered[lower],
                vertical: vertical
            ) else { break }
            lower -= 1
        }
        while upper < ordered.count, upper - targetIndex <= 4 {
            guard !startsNewParagraph(
                previous: ordered[upper - 1],
                next: ordered[upper],
                vertical: vertical
            ) else { break }
            upper += 1
        }
        return Array(ordered[lower ..< upper])
    }

    private static func fullParagraphWindow(
        around targetIndex: Int,
        in ordered: [IndexedLine],
        vertical: Bool
    ) -> [IndexedLine] {
        var lower = targetIndex
        var upper = targetIndex + 1
        while lower > 0 {
            guard !startsNewParagraph(
                previous: ordered[lower - 1],
                next: ordered[lower],
                vertical: vertical
            ) else { break }
            lower -= 1
        }
        while upper < ordered.count {
            guard !startsNewParagraph(
                previous: ordered[upper - 1],
                next: ordered[upper],
                vertical: vertical
            ) else { break }
            upper += 1
        }
        return Array(ordered[lower ..< upper])
    }

    private static func startsNewParagraph(
        previous: IndexedLine,
        next: IndexedLine,
        vertical: Bool
    ) -> Bool {
        if vertical {
            let columnGap = max(previous.box.minX - next.box.maxX, 0)
            let largeGap = columnGap > max(
                max(previous.box.width, next.box.width) * 1.15,
                0.026
            )
            let firstCharacterIndent = previous.box.maxY - next.box.maxY
            let indentThreshold = max(
                max(previous.box.width, next.box.width) * 0.55,
                0.018
            )
            return largeGap || firstCharacterIndent > indentThreshold
        }

        let lineGap = max(previous.box.minY - next.box.maxY, 0)
        let largeGap = lineGap > max(
            max(previous.box.height, next.box.height) * 1.15,
            0.028
        )
        let firstLineIndent = next.box.minX - previous.box.minX
        let indentThreshold = max(
            max(previous.box.height, next.box.height) * 0.55,
            0.018
        )
        return largeGap || firstLineIndent > indentThreshold
    }

    private static func overlap(
        _ lhs: ClosedRange<CGFloat>,
        _ rhs: ClosedRange<CGFloat>
    ) -> CGFloat {
        max(min(lhs.upperBound, rhs.upperBound) - max(lhs.lowerBound, rhs.lowerBound), 0)
    }

    private static func axisGap(
        _ lhs: ClosedRange<CGFloat>,
        _ rhs: ClosedRange<CGFloat>
    ) -> CGFloat {
        if lhs.upperBound < rhs.lowerBound {
            return rhs.lowerBound - lhs.upperBound
        }
        if rhs.upperBound < lhs.lowerBound {
            return lhs.lowerBound - rhs.upperBound
        }
        return 0
    }

    private static func nearestLine(
        to point: CGPoint,
        in lines: [IndexedLine]
    ) -> IndexedLine? {
        let expandedMatches = lines.filter {
            $0.box.insetBy(dx: -0.018, dy: -0.014).contains(point)
        }
        if let best = expandedMatches.min(by: { area($0.box) < area($1.box) }) {
            return best
        }

        guard let closest = lines.min(by: {
            squaredDistance(from: point, to: $0.box)
                < squaredDistance(from: point, to: $1.box)
        }),
        squaredDistance(from: point, to: closest.box) <= 0.008
        else {
            return nil
        }
        return closest
    }

    private static func belongsToSameFlow(
        _ box: CGRect,
        as target: CGRect,
        vertical: Bool
    ) -> Bool {
        if vertical {
            let overlap = max(
                min(box.maxY, target.maxY) - max(box.minY, target.minY),
                0
            )
            let ratio = overlap / max(min(box.height, target.height), 0.0001)
            return ratio >= 0.22
                || abs(box.midY - target.midY) <= max(box.height, target.height) * 0.48
        }

        let overlap = max(
            min(box.maxX, target.maxX) - max(box.minX, target.minX),
            0
        )
        let ratio = overlap / max(min(box.width, target.width), 0.0001)
        return ratio >= 0.22
            || abs(box.midX - target.midX) <= max(box.width, target.width) * 0.48
    }

    private static func readingOrder(
        _ lhs: CGRect,
        before rhs: CGRect,
        vertical: Bool
    ) -> Bool {
        if vertical {
            if abs(lhs.midX - rhs.midX) > max(lhs.width, rhs.width) * 0.55 {
                return lhs.midX > rhs.midX
            }
            return lhs.midY > rhs.midY
        }
        if abs(lhs.midY - rhs.midY) > max(lhs.height, rhs.height) * 0.55 {
            return lhs.midY > rhs.midY
        }
        return lhs.minX < rhs.minX
    }

    private static func squaredDistance(from point: CGPoint, to rect: CGRect) -> CGFloat {
        let dx = max(max(rect.minX - point.x, 0), point.x - rect.maxX)
        let dy = max(max(rect.minY - point.y, 0), point.y - rect.maxY)
        return dx * dx + dy * dy
    }

    private static func area(_ rect: CGRect) -> CGFloat {
        rect.width * rect.height
    }
}
