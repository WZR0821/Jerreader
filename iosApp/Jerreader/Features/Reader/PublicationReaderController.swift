import UIKit
@preconcurrency import ReadiumShared

struct ReaderSpeechHighlight: Equatable {
    let lowerBound: Double
    let upperBound: Double

    init?(utf16Range: NSRange, totalLength: Int) {
        guard totalLength > 0,
              utf16Range.location != NSNotFound,
              utf16Range.location >= 0,
              utf16Range.length > 0
        else { return nil }
        lowerBound = min(max(Double(utf16Range.location) / Double(totalLength), 0), 1)
        upperBound = min(
            max(Double(NSMaxRange(utf16Range)) / Double(totalLength), lowerBound),
            1
        )
    }
}

@MainActor
protocol PublicationReaderController: AnyObject {
    var viewController: UIViewController { get }
    var onLocationChange: ((Locator) -> Void)? { get set }
    var onSelectedText: ((ReaderSelectionPayload) -> Void)? { get set }
    var onSelectionFrameChange: ((CGRect?) -> Void)? { get set }
    var onContentTap: (() -> Void)? { get set }
    var onReaderError: ((String) -> Void)? { get set }
    var onReaderActivityChange: ((String?) -> Void)? { get set }
    var onPaginationModeChange: ((Bool) -> Void)? { get set }
    var onAnnotationActivated: ((UUID) -> Void)? { get set }
    var isPaginated: Bool { get }
    var supportsQuickSentenceTranslation: Bool { get }
    var currentLocator: Locator? { get }

    func navigateBackward()
    func navigateForward()
    func navigate(to link: ReadiumShared.Link)
    func navigate(to locator: Locator)
    func search(_ query: String) async throws -> [Locator]
    func crossPageContext(for sourceText: String) async -> String?
    func clearSelection()
    func updateSpeechHighlight(_ highlight: ReaderSpeechHighlight?)
    func applyAnnotations(_ annotations: [ReaderAnnotationPresentation])
    func setQuickSentenceTranslationEnabled(_ enabled: Bool)
    func setQuickTranslationUnit(_ unit: ReaderQuickTranslationUnit)
    func setQuickSentenceTapPageTurnsDisabled(_ disabled: Bool)
    func setPDFPaperModeEnabled(_ enabled: Bool)
    func applyPreferences(
        fontSize: Double,
        theme: ReaderThemeChoice,
        fontChoice: ReaderFontChoice,
        lineHeight: Double,
        paragraphSpacing: Double,
        pageMargins: Double,
        textOrientation: ReaderTextOrientationChoice,
        readingMode: ReaderReadingMode,
        customBackgroundHex: String,
        customSelectionColorHex: String
    )
}

extension PublicationReaderController {
    /// EPUB and converted reflowable publications do not use PDF paper mode.
    func setPDFPaperModeEnabled(_ enabled: Bool) {}
}

extension PublicationReaderController where Self: UIViewController {
    var viewController: UIViewController { self }
}
