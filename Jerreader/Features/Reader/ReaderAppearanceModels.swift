import CoreGraphics
import Foundation
@preconcurrency import ReadiumShared
import UIKit

// Reader appearance: themes, fonts, sizing, writing direction, custom
// colours and the publication layout detected from the EPUB.

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

/// How the reader shares the screen between the book and its two bars.
///
/// Android mirrors this in `ReaderLayoutMode` (same raw values, same titles).
enum ReaderLayoutMode: String, CaseIterable, Identifiable {
    /// The bars stay on screen and the page is laid out between them.
    case standard
    /// The bars start hidden and the page uses the whole screen; a tap on the
    /// blank strip above or below the text, or on no text at all, shows them
    /// until they hide again.
    case immersive

    var id: Self { self }

    var title: String {
        switch self {
        case .standard: return "标准"
        case .immersive: return "沉浸"
        }
    }

    var detail: String {
        switch self {
        case .standard: return "上下栏常驻，正文排在两栏之间。"
        case .immersive: return "上下栏默认收起，正文全屏。轻点屏幕顶部或底部的空白处显示，几秒后自动收起。"
        }
    }
}

enum ReaderInterfaceDefaults {
    static let topTransparencyKey = "reader.topBarTransparency"
    static let bottomTransparencyKey = "reader.bottomBarTransparency"
    static let japaneseFuriganaKey = "translation.japaneseFuriganaEnabled"
    static let immersiveReadingKey = "reader.immersiveReading"
    static let layoutModeKey = "reader.layoutMode"
    static let barTransparency = 0.15
    /// The gap Readium keeps between the text and whatever is above and below
    /// it: a bar in the standard layout, the safe area in the immersive one.
    /// Readium's own default (62pt on a phone, on top of the safe area the
    /// SwiftUI container already leaves out) was most of the old dead band.
    static let contentEdgeInset: CGFloat = 14
    /// How long the immersive layout keeps the bars after the last touch.
    static let immersiveChromeAutoHideDelay: Duration = .seconds(4)

    static func backgroundOpacity(_ transparency: Double) -> Double {
        1 - min(max(transparency.isFinite ? transparency : barTransparency, 0), 1)
    }

    static func layoutMode(defaults: UserDefaults = .standard) -> ReaderLayoutMode {
        ReaderLayoutMode(rawValue: defaults.string(forKey: layoutModeKey) ?? "") ?? .standard
    }
}

enum ReaderAppearanceDefaults {
    static let fontPointSizeKey = "reader.defaults.fontPointSize"
    static let themeKey = "reader.defaults.theme"
    static let readingModeKey = "reader.defaults.readingMode"
    static let fontFamilyKey = "reader.defaults.fontFamily"
    static let lineHeightKey = "reader.defaults.lineHeight"
    static let paragraphSpacingKey = "reader.defaults.paragraphSpacing"
    static let pageMarginsKey = "reader.defaults.pageMargins"
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
