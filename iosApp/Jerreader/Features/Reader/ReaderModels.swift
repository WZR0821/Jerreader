import CoreGraphics
import JerreaderCore
import Foundation
import NaturalLanguage
@preconcurrency import ReadiumShared
import UIKit

enum ReaderThemeChoice: String, CaseIterable, Identifiable {
    case light
    case sepia
    case coolGray
    case dark

    var id: Self { self }

    var title: String {
        switch self {
        case .light: return "浅色"
        case .sepia: return "护眼"
        case .coolGray: return "冷灰"
        case .dark: return "深色"
        }
    }
}

enum ReaderQuickTranslationUnit: String, CaseIterable, Identifiable {
    case sentence
    case paragraph

    var id: Self { self }

    var title: String {
        switch self {
        case .sentence: return "句子"
        case .paragraph: return "段落"
        }
    }

    var shortTitle: String {
        switch self {
        case .sentence: return "点句"
        case .paragraph: return "点段"
        }
    }
}

enum ReaderFontChoice: String, CaseIterable, Identifiable {
    case serif
    case sansSerif
    case openDyslexic

    var id: Self { self }

    var title: String {
        switch self {
        case .serif: return "衬线 / 日文明朝"
        case .sansSerif: return "无衬线 / 日文黑体"
        case .openDyslexic: return "OpenDyslexic"
        }
    }
}

enum ReaderFontSizing {
    static let basePointSize = 17.0
    static let pointSizeRange = 12.0 ... 32.0
    static let pointSizeStep = 0.5

    static func pointSize(fromScale scale: Double) -> Double {
        let safeScale = scale.isFinite ? scale : 1
        return steppedPointSize(safeScale * basePointSize)
    }

    static func scale(fromPointSize pointSize: Double) -> Double {
        steppedPointSize(pointSize) / basePointSize
    }

    static func clampedScale(_ value: Double) -> Double {
        scale(fromPointSize: pointSize(fromScale: value))
    }

    static func clampedPointSize(_ pointSize: Double) -> Double {
        let safePointSize = pointSize.isFinite ? pointSize : basePointSize
        return min(max(safePointSize, pointSizeRange.lowerBound), pointSizeRange.upperBound)
    }

    private static func steppedPointSize(_ pointSize: Double) -> Double {
        let clamped = clampedPointSize(pointSize)
        let steps = ((clamped - pointSizeRange.lowerBound) / pointSizeStep).rounded()
        return pointSizeRange.lowerBound + steps * pointSizeStep
    }
}

enum ReaderTextOrientationChoice: String, CaseIterable, Hashable, Identifiable {
    case horizontal
    case publication

    var id: Self { self }

    var title: String {
        switch self {
        case .horizontal: return "横排"
        case .publication: return "原版"
        }
    }

    var detail: String {
        switch self {
        case .horizontal:
            return "将日文竖排转为横排，并统一按从左向右的方向逐页阅读。"
        case .publication:
            return "保留书籍原有的横竖排与翻页方向；日文竖排书通常会从右向左翻页。"
        }
    }
}

enum ReaderTextOrientationDefaults {
    static let storageKey = "reader.defaultJapaneseTextOrientation"

    static var standardChoice: ReaderTextOrientationChoice {
        choice(defaults: .standard)
    }

    static func choice(
        defaults: UserDefaults
    ) -> ReaderTextOrientationChoice {
        let rawValue = defaults.string(forKey: storageKey)
        return ReaderTextOrientationChoice(rawValue: rawValue ?? "") ?? .publication
    }
}

struct ReaderAppearancePreferences: Equatable {
    var fontSize: Double
    var theme: ReaderThemeChoice
    var readingMode: ReaderReadingMode
    var fontChoice: ReaderFontChoice
    var lineHeight: Double
    var paragraphSpacing: Double
    var pageMargins: Double
    var customBackgroundHex: String
    var customSelectionColorHex: String
    var japaneseTextOrientation: ReaderTextOrientationChoice
}

enum ReaderAppearanceDefaults {
    static let fontPointSizeKey = "reader.defaults.fontPointSize"
    static let themeKey = "reader.defaults.theme"
    static let readingModeKey = "reader.defaults.readingMode"
    static let fontFamilyKey = "reader.defaults.fontFamily"
    static let lineHeightKey = "reader.defaults.lineHeight"
    static let paragraphSpacingKey = "reader.defaults.paragraphSpacing"
    static let pageMarginsKey = "reader.defaults.pageMargins"
    /// The per-side overrides set in 默认阅读排版 → 分别设置.
    static let pageMarginTopKey = "reader.defaults.pageMarginTop"
    static let pageMarginBottomKey = "reader.defaults.pageMarginBottom"
    static let pageMarginHorizontalKey = "reader.defaults.pageMarginHorizontal"
    static let customBackgroundHexKey = "reader.defaults.customBackgroundHex"
    static let customSelectionColorHexKey =
        "reader.defaults.customSelectionColorHex"
    static let appliesToExistingBooksKey =
        "reader.defaults.appliesToExistingBooks"
    static let showsProgressKey = "reader.showsProgress"

    static func current(
        defaults: UserDefaults = .standard
    ) -> ReaderAppearancePreferences {
        let pointSize = storedDouble(
            defaults,
            key: fontPointSizeKey,
            fallback: ReaderFontSizing.basePointSize,
            range: ReaderFontSizing.pointSizeRange
        )
        let lineHeight = storedDouble(
            defaults,
            key: lineHeightKey,
            fallback: 1.4,
            range: 1.0 ... 2.2
        )
        let paragraphSpacing = storedDouble(
            defaults,
            key: paragraphSpacingKey,
            fallback: 0,
            range: 0 ... 2
        )
        let pageMargins = storedDouble(
            defaults,
            key: pageMarginsKey,
            fallback: 1,
            range: 0.5 ... 2
        )
        return ReaderAppearancePreferences(
            fontSize: ReaderFontSizing.scale(fromPointSize: pointSize),
            theme: ReaderThemeChoice(
                rawValue: defaults.string(forKey: themeKey) ?? ""
            ) ?? .light,
            readingMode: ReaderReadingMode(
                rawValue: defaults.string(forKey: readingModeKey) ?? ""
            ) ?? .paginated,
            fontChoice: ReaderFontChoice(
                rawValue: defaults.string(forKey: fontFamilyKey) ?? ""
            ) ?? .serif,
            lineHeight: lineHeight,
            paragraphSpacing: paragraphSpacing,
            pageMargins: pageMargins,
            customBackgroundHex: ReaderCustomBackground.normalizedHex(
                defaults.string(forKey: customBackgroundHexKey) ?? ""
            ) ?? "",
            customSelectionColorHex: ReaderCustomBackground.normalizedHex(
                defaults.string(forKey: customSelectionColorHexKey) ?? ""
            ) ?? "",
            japaneseTextOrientation: ReaderTextOrientationDefaults.choice(
                defaults: defaults
            )
        )
    }

    static func apply(
        _ preferences: ReaderAppearancePreferences,
        to book: BookRecord
    ) {
        book.readerFontSize = ReaderFontSizing.clampedScale(preferences.fontSize)
        book.readerTheme = preferences.theme.rawValue
        book.readerScrollEnabled = preferences.readingMode.scrollEnabled
        book.readerFontFamily = preferences.fontChoice.rawValue
        book.readerLineHeight = min(max(preferences.lineHeight, 1), 2.2)
        book.readerParagraphSpacing = min(
            max(preferences.paragraphSpacing, 0),
            2
        )
        book.readerPageMargins = min(max(preferences.pageMargins, 0.5), 2)
        book.readerCustomBackgroundHex =
            ReaderCustomBackground.normalizedHex(
                preferences.customBackgroundHex
            ) ?? ""
        book.readerCustomSelectionColorHex =
            ReaderCustomBackground.normalizedHex(
                preferences.customSelectionColorHex
            ) ?? ""
        if book.language?.lowercased().hasPrefix("ja") == true {
            book.readerTextOrientation =
                preferences.japaneseTextOrientation.rawValue
        }
    }

    private static func storedDouble(
        _ defaults: UserDefaults,
        key: String,
        fallback: Double,
        range: ClosedRange<Double>
    ) -> Double {
        guard defaults.object(forKey: key) != nil else { return fallback }
        let value = defaults.double(forKey: key)
        guard value.isFinite else { return fallback }
        return min(max(value, range.lowerBound), range.upperBound)
    }
}

enum ReaderCustomBackground {
    static let initialHex = "#EEF3F8"

    static func normalizedHex(_ value: String) -> String? {
        let trimmed = value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .uppercased()
        guard !trimmed.isEmpty else { return nil }
        let digits = trimmed.hasPrefix("#") ? String(trimmed.dropFirst()) : trimmed
        guard digits.count == 6,
              digits.allSatisfy({ $0.isHexDigit })
        else { return nil }
        return "#\(digits)"
    }

    static func uiColor(hex: String) -> UIColor? {
        guard let normalized = normalizedHex(hex),
              let value = UInt64(normalized.dropFirst(), radix: 16)
        else { return nil }
        return UIColor(
            red: CGFloat((value >> 16) & 0xFF) / 255,
            green: CGFloat((value >> 8) & 0xFF) / 255,
            blue: CGFloat(value & 0xFF) / 255,
            alpha: 1
        )
    }

    /// A `#RRGGBB` string for any colour the pickers can produce.
    ///
    /// `ColorPicker` hands back Display-P3 colours, and `getRed` reports those
    /// in *extended* sRGB — components legitimately outside 0...1. Feeding an
    /// out-of-range component to `%02X` printed three or more hex digits, so the
    /// result failed `normalizedHex` and the caller either ignored the change or
    /// stored an empty string. That is why picking a vivid colour appeared to do
    /// nothing while a muted one worked: the wide-gamut ones were the ones that
    /// overflowed. Clamping projects them onto the nearest sRGB colour instead.
    ///
    /// Grayscale colours do not answer `getRed` at all on some paths, so they
    /// fall back to `getWhite`.
    static func hex(from color: UIColor) -> String? {
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        if !color.getRed(&red, green: &green, blue: &blue, alpha: &alpha) {
            var white: CGFloat = 0
            guard color.getWhite(&white, alpha: &alpha) else { return nil }
            red = white
            green = white
            blue = white
        }

        func channel(_ value: CGFloat) -> Int {
            guard value.isFinite else { return 0 }
            return Int((min(max(value, 0), 1) * 255).rounded())
        }

        return String(
            format: "#%02X%02X%02X",
            channel(red),
            channel(green),
            channel(blue)
        )
    }

    static func prefersLightText(hex: String) -> Bool {
        guard let color = uiColor(hex: hex) else { return false }
        var red: CGFloat = 0
        var green: CGFloat = 0
        var blue: CGFloat = 0
        var alpha: CGFloat = 0
        guard color.getRed(
            &red,
            green: &green,
            blue: &blue,
            alpha: &alpha
        ) else { return false }
        let luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
        return luminance < 0.48
    }
}

enum ReaderPublicationLayout: String, Equatable, Sendable {
    case horizontalLTR
    case horizontalRTL
    case verticalLTR
    case verticalRTL

    var verticalText: Bool {
        self == .verticalLTR || self == .verticalRTL
    }

    var rightToLeft: Bool {
        self == .horizontalRTL || self == .verticalRTL
    }
}

enum JapanesePublicationLayoutDetector {
    private static let maximumScannedResourceCount = 24
    private static let maximumBytesPerResource: UInt64 = 256 * 1_024

    static func detect(
        in publication: Publication,
        fallbackLanguage: String?
    ) async -> ReaderPublicationLayout {
        let metadata = publication.metadata
        var samples: [String] = []
        let resources = (
            publication.manifest.resources.filter {
                $0.mediaType?.matches(.css) == true
                    || $0.mediaType?.isHTML == true
            }
            + publication.readingOrder.filter {
                $0.mediaType?.isHTML != false
            }
        )

        for link in resources.prefix(maximumScannedResourceCount) {
            guard let resource = publication.get(link) else { continue }
            let data = await resource.read(
                range: 0 ..< maximumBytesPerResource
            ).getOrNil()
            guard let data, !data.isEmpty else { continue }
            samples.append(String(decoding: data, as: UTF8.self))
        }

        return detect(
            markupSamples: samples,
            languages: metadata.languages + [fallbackLanguage].compactMap { $0 },
            metadataReadingProgression: metadata.readingProgression
        )
    }

    static func detect(
        markupSamples: [String],
        languages: [String],
        metadataReadingProgression: ReadiumShared.ReadingProgression
    ) -> ReaderPublicationLayout {
        let combined = markupSamples.joined(separator: "\n")
        if containsWritingMode("vertical-rl|tb-rl", in: combined) {
            return .verticalRTL
        }
        if containsWritingMode("vertical-lr|tb-lr", in: combined) {
            return .verticalLTR
        }

        let isRightToLeft = metadataReadingProgression == .rtl
        let isJapanese = languages.contains {
            let language = $0.lowercased()
            return language.hasPrefix("ja") || language.hasPrefix("jpn")
        }
        if isJapanese && isRightToLeft {
            return .verticalRTL
        }
        return isRightToLeft ? .horizontalRTL : .horizontalLTR
    }

    private static func containsWritingMode(
        _ values: String,
        in markup: String
    ) -> Bool {
        let pattern =
            #"(?i)(?:-epub-|-webkit-)?writing-mode\s*:\s*(?:"# +
            values +
            #")\b"#
        return markup.range(
            of: pattern,
            options: .regularExpression
        ) != nil
    }
}

enum ReaderReadingMode: String, CaseIterable, Identifiable {
    case paginated
    case scrolling

    var id: Self { self }

    var title: String {
        switch self {
        case .paginated: return "逐页"
        case .scrolling: return "滚动"
        }
    }

    var detail: String {
        switch self {
        case .paginated: return "每次只显示一页，点击两侧或使用按钮翻页"
        case .scrolling: return "上下连续滚动阅读"
        }
    }

    var scrollEnabled: Bool {
        self == .scrolling
    }

    init(scrollEnabled: Bool) {
        self = scrollEnabled ? .scrolling : .paginated
    }
}

struct ReaderOutlineItem: Identifiable {
    let id = UUID()
    /// Mutable so a junk label can be replaced with the document's own heading
    /// once the resource has been read.
    var title: String
    let link: Link
    let depth: Int
    /// Where this entry starts in the whole publication, once the positions
    /// service has been consulted. Drives both "which chapter am I in" and the
    /// chapter preview shown while the progress bar is being dragged.
    var startProgression: Double?
    /// True when the entry was synthesised from the reading order because the
    /// publication ships no usable table of contents.
    var isGenerated = false

    /// Resource identity used to match a reading position to an entry. Query
    /// and fragment are dropped: a table of contents routinely points at
    /// `chapter.xhtml#section-2` while the reading position reports the bare
    /// resource.
    var resourceKey: String {
        ReaderOutlineItem.resourceKey(for: link.href)
    }

    static func resourceKey(for href: String) -> String {
        var value = href
        if let hashIndex = value.firstIndex(of: "#") {
            value = String(value[value.startIndex ..< hashIndex])
        }
        if let queryIndex = value.firstIndex(of: "?") {
            value = String(value[value.startIndex ..< queryIndex])
        }
        while value.hasPrefix("/") {
            value.removeFirst()
        }
        return value.removingPercentEncoding ?? value
    }
}

/// Decides whether a chapter label is worth showing.
///
/// Publications routinely put junk in their navigation documents: a bare
/// running number (`１`), a generic `本文`, or the book's own title repeated on
/// every entry. Showing those verbatim gives the reader a header that reads
/// "1" for the entire book, which is worse than showing nothing.
enum ReaderChapterTitle {
    private static let genericTitles: Set<String> = [
        "本文", "正文", "目次", "目录", "扉", "表紙", "封面",
        "未命名章节", "untitled", "text", "body", "content", "contents"
    ]

    static func isMeaningful(_ title: String, bookTitle: String? = nil) -> Bool {
        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count > 1 else { return false }
        if genericTitles.contains(trimmed.lowercased()) { return false }
        if let bookTitle,
           trimmed.compare(
               bookTitle.trimmingCharacters(in: .whitespacesAndNewlines),
               options: [.caseInsensitive, .widthInsensitive]
           ) == .orderedSame {
            return false
        }
        // Bare numbering, half- or full-width, with or without punctuation.
        let stripped = trimmed.folding(
            options: [.widthInsensitive],
            locale: nil
        )
        .trimmingCharacters(
            in: CharacterSet(charactersIn: " .．、,，-–—_()（）[]【】")
        )
        if !stripped.isEmpty,
           stripped.allSatisfy({ $0.isNumber || $0.isPunctuation }) {
            return false
        }
        return true
    }

    /// Best label for the header, preferring the outline over the raw locator
    /// title because the outline can be repaired from document headings.
    static func display(
        outlineTitle: String?,
        locatorTitle: String?,
        bookTitle: String
    ) -> String {
        for candidate in [outlineTitle, locatorTitle] {
            guard let candidate = candidate?
                .trimmingCharacters(in: .whitespacesAndNewlines),
                !candidate.isEmpty
            else { continue }
            if isMeaningful(candidate, bookTitle: bookTitle) {
                return candidate
            }
        }
        return bookTitle
    }
}

/// Builds the outline shown in the navigation sheet.
///
/// Publications regularly ship an empty `<nav epub:type="toc">`, an NCX that
/// only lists the cover, or titles that are blank. Falling back to the reading
/// order keeps chapter navigation and the chapter scrubber usable for those
/// books instead of showing "this book has no contents".
enum ReaderOutlineBuilder {
    static func build(
        tableOfContents: [Link],
        readingOrder: [Link]
    ) -> [ReaderOutlineItem] {
        let declared = flatten(tableOfContents)
        let documents = readingOrder.filter { $0.mediaType?.isHTML != false }

        // A table of contents is only useful if it actually points into the
        // book. Publications exist whose navigation document lists nothing but
        // 表紙 / 目次 / 扉 / 本文 — technically several entries, but the whole
        // novel hides behind one of them, so it can navigate no better than an
        // empty one. Judge it by how many distinct documents it reaches.
        let reachedDocuments = Set(declared.map(\.resourceKey))
        let documentKeys = documents.map { ReaderOutlineItem.resourceKey(for: $0.href) }
        let reachedCount = Set(documentKeys).intersection(reachedDocuments).count
        let isUsable = declared.count > 1
            && (reachedCount > 1 || documents.count <= 2)
        if isUsable {
            return declared
        }

        guard documents.count > 1 else { return declared }

        var used = Set<String>()
        var generated: [ReaderOutlineItem] = []
        for (index, link) in documents.enumerated() {
            let key = ReaderOutlineItem.resourceKey(for: link.href)
            guard used.insert(key).inserted else { continue }
            let declaredTitle = link.title?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfBlank
            generated.append(
                ReaderOutlineItem(
                    title: declaredTitle ?? fallbackTitle(for: link, index: index),
                    link: link,
                    depth: 0,
                    isGenerated: declaredTitle == nil
                )
            )
        }
        return generated.count > declared.count ? generated : declared
    }

    /// Turns `text/part0007_split_002.html` into `第 8 节`-style labels. The
    /// file name is kept when it carries a human-readable name.
    private static func fallbackTitle(for link: Link, index: Int) -> String {
        let file = ReaderOutlineItem.resourceKey(for: link.href)
            .split(separator: "/")
            .last
            .map(String.init) ?? ""
        let stem = file.split(separator: ".").first.map(String.init) ?? file
        let isOpaque = stem.isEmpty
            || stem.range(
                of: #"^(?:[a-z_\-]*\d+[a-z0-9_\-]*)$"#,
                options: [.regularExpression, .caseInsensitive]
            ) != nil
        if isOpaque {
            return "第 \(index + 1) 节"
        }
        return stem.replacingOccurrences(of: "_", with: " ")
    }

    private static func flatten(
        _ links: [Link],
        depth: Int = 0
    ) -> [ReaderOutlineItem] {
        links.flatMap { link -> [ReaderOutlineItem] in
            let title = link.title?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfBlank ?? "未命名章节"
            return [ReaderOutlineItem(title: title, link: link, depth: depth)]
                + flatten(link.children, depth: depth + 1)
        }
    }

    /// Reads the given resources and returns a chapter name per resource key.
    ///
    /// Kept off the main actor — like the layout detector — because Readium's
    /// `Resource` is not `Sendable` and must not cross an actor boundary.
    static func repairedTitles(
        in publication: Publication,
        links: [Link],
        bookTitle: String,
        maximumBytesPerResource: UInt64 = 96 * 1_024
    ) async -> [String: String] {
        var titles: [String: String] = [:]
        for link in links {
            let key = ReaderOutlineItem.resourceKey(for: link.href)
            guard titles[key] == nil else { continue }
            guard let resource = publication.get(link) else { continue }
            let data = await resource.read(
                range: 0 ..< maximumBytesPerResource
            ).getOrNil()
            guard let data, !data.isEmpty else { continue }
            guard let title = extractedTitle(
                fromHTML: String(decoding: data, as: UTF8.self),
                bookTitle: bookTitle
            ) else { continue }
            titles[key] = title
        }
        return titles
    }

    /// Recovers a chapter name from the document itself.
    ///
    /// Used for entries the publication left unlabelled or labelled with a
    /// bare number. Real headings are checked first; Japanese ebooks that use
    /// styled paragraphs instead of `<h*>` are covered by the common
    /// 見出し/midashi class names, and `<title>` is the last resort.
    static func extractedTitle(fromHTML html: String, bookTitle: String?) -> String? {
        let patterns = [
            #"<h[1-6][^>]*>([\s\S]{1,200}?)</h[1-6]>"#,
            #"<[a-z]+[^>]*class="[^"]*(?:midashi|mihdashi|chapter|caption|title)[^"]*"[^>]*>([\s\S]{1,200}?)</[a-z]+>"#,
            #"<title[^>]*>([\s\S]{1,200}?)</title>"#
        ]
        for pattern in patterns {
            guard let range = html.range(
                of: pattern,
                options: [.regularExpression, .caseInsensitive]
            ) else { continue }
            let candidate = plainText(String(html[range]))
            if ReaderChapterTitle.isMeaningful(candidate, bookTitle: bookTitle) {
                return candidate
            }
        }
        return nil
    }

    private static func plainText(_ markup: String) -> String {
        let withoutTags = markup.replacingOccurrences(
            of: "<[^>]+>",
            with: " ",
            options: .regularExpression
        )
        let decoded = withoutTags
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&#160;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
            .replacingOccurrences(of: "&quot;", with: "\"")
        return decoded
            .replacingOccurrences(
                of: "\\s+",
                with: " ",
                options: .regularExpression
            )
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

private extension String {
    var nilIfBlank: String? {
        isEmpty ? nil : self
    }
}

struct ReaderSearchResult: Identifiable {
    let id = UUID()
    let locator: Locator

    var chapterTitle: String {
        let title = locator.title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return title.isEmpty ? "正文" : title
    }

    var progress: Double? {
        locator.locations.totalProgression
    }

    var text: Locator.Text {
        locator.text.sanitized()
    }
}

enum ReaderSearchError: LocalizedError {
    case emptyQuery
    case unavailable
    case failed

    var errorDescription: String? {
        switch self {
        case .emptyQuery:
            return "请输入要搜索的文字。"
        case .unavailable:
            return "这份文档暂时不支持全文搜索。扫描 PDF 需要先有可搜索文字层。"
        case .failed:
            return "搜索没有完成，请稍后重试。"
        }
    }
}

enum ReaderLoadState {
    case loading
    case ready
    case failed(String)
}

struct ReaderLoadTiming: Sendable {
    let openTimeout: Duration

    static let standard = ReaderLoadTiming(openTimeout: .seconds(25))
}

struct ReaderQuickTranslationIndicatorTiming: Sendable {
    let autoExitDelay: Duration

    static let standard = ReaderQuickTranslationIndicatorTiming(
        autoExitDelay: .seconds(5)
    )
}

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

    mutating func reset() {
        initialText = nil
        isPreciseSelection = false
    }
}

enum ReaderSmartSelection {
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
        return sentence.text
    }
}

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
    /// Repair only that narrow boundary so a genuinely new sentence beginning
    /// after `」` or `』` is not swallowed.
    private static func refinedJapaneseRanges(
        _ systemRanges: [Range<String.Index>],
        in text: String
    ) -> [Range<String.Index>] {
        var merged: [Range<String.Index>] = []
        for range in systemRanges where !range.isEmpty {
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

        let continuation = String(text[nextRange])
        if continuation.hasPrefix("って")
            || continuation.hasPrefix("などと")
            || continuation.hasPrefix("なんて")
        {
            return true
        }
        guard continuation.hasPrefix("と") else { return false }
        let independentStarters = [
            "とても", "ところ", "とにかく", "とはいえ", "とは言え",
            "とりあえず", "とうとう",
        ]
        return !independentStarters.contains { continuation.hasPrefix($0) }
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

    private static func stringIndex(
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

struct ReaderContextExplanationRequest: Identifiable, Equatable, Sendable {
    let id: UUID
    let focusedText: String
    let contextText: String?
    let sourceLanguage: LanguageCode?
    let responseLanguage: LanguageCode
    let providerIdentifier: String
    let providerVersion: String
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
            return "请先在设置中配置一个 AI API 或 AI 代理。Apple 系统翻译不提供句子结构分析。"
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
    /// Delegates to the shared policy. `isJapaneseBook` is still accepted so
    /// the call sites and their tests are untouched, but it was already unused
    /// here: EPUB language metadata is optional and frequently absent, so the
    /// fallback that matters is the tall-narrow selection shape, not the tag.
    static func usesVerticalSideAvoidance(
        isReflowable: Bool,
        preservesPublicationOrientation: Bool,
        publicationIsVertical: Bool,
        isJapaneseBook: Bool,
        selectionFrame: CGRect?
    ) -> Bool {
        _ = isJapaneseBook
        return JerreaderCore.ReaderTranslationLayoutPolicy.shared
            .usesVerticalSideAvoidance(
                isReflowable: isReflowable,
                preservesPublicationOrientation: preservesPublicationOrientation,
                publicationIsVertical: publicationIsVertical,
                selectionFrame: selectionFrame.map(ReaderRect.from)
            )
    }

    static func selectionGap(
        isParagraph: Bool,
        usesVerticalSideAvoidance: Bool
    ) -> CGFloat {
        CGFloat(
            JerreaderCore.ReaderTranslationLayoutPolicy.shared.selectionGap(
                isParagraph: isParagraph,
                usesVerticalSideAvoidance: usesVerticalSideAvoidance
            )
        )
    }
}

/// The reader's view of the shared placement solver.
///
/// Every rule about where the translation card may sit — which side of the
/// selection, how far off it, how wide and tall it may be, what happens when
/// neither side fits — now lives in `JerreaderCore.ReaderOverlayPlacement` and
/// is shared with Android. This enum keeps the CGRect-shaped API the SwiftUI
/// overlay is written against, so no view changed.
enum ReaderTranslationOverlayPlacement {
    enum Edge: Equatable, Sendable {
        case above
        case below
        case left
        case right
        case top

        fileprivate init(_ edge: ReaderOverlayEdge) {
            switch edge {
            case .above: self = .above
            case .below: self = .below
            case .left: self = .left
            case .right: self = .right
            default: self = .top
            }
        }
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
        JerreaderCore.ReaderOverlayPlacement.shared.localSelectionFrame(
            windowFrame: windowFrame.map(ReaderRect.from),
            overlayFrame: ReaderRect.from(overlayFrame)
        )?.cgRect
    }

    static func clampedPosition(
        _ position: CGPoint,
        viewportSize: CGSize,
        cardSize: CGSize,
        topInset: CGFloat,
        bottomInset: CGFloat,
        horizontalInset: CGFloat = 14
    ) -> CGPoint {
        JerreaderCore.ReaderOverlayPlacement.shared.clampedPosition(
            position: ReaderPoint.from(position),
            viewportSize: ReaderSize.from(viewportSize),
            cardSize: ReaderSize.from(cardSize),
            topInset: Double(topInset),
            bottomInset: Double(bottomInset),
            horizontalInset: Double(horizontalInset)
        ).cgPoint
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
        let solved = JerreaderCore.ReaderOverlayPlacement.shared.solve(
            request: ReaderOverlayRequest(
                selectionFrame: selectionFrame.map(ReaderRect.from),
                viewportSize: ReaderSize.from(viewportSize),
                cardSize: ReaderSize.from(cardSize),
                horizontalAvoidanceFrame: horizontalAvoidanceFrame.map(ReaderRect.from),
                topInset: Double(topInset),
                bottomInset: Double(bottomInset),
                horizontalInset: Double(horizontalInset),
                gap: Double(gap),
                minimumCardHeight: Double(minimumCardHeight),
                preferredMaximumCardHeight: Double(preferredMaximumCardHeight),
                prefersTop: prefersTop,
                prefersHorizontalAvoidance: prefersHorizontalAvoidance,
                minimumHorizontalCardWidth: Double(minimumHorizontalCardWidth)
            )
        )
        return Layout(
            edge: Edge(solved.edge),
            position: solved.position.cgPoint,
            cardWidth: CGFloat(solved.cardWidth),
            maximumCardHeight: CGFloat(solved.maximumCardHeight),
            availableHeight: CGFloat(solved.availableHeight)
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
