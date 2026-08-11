import JerreaderCore
import PDFKit
import UIKit
@preconcurrency import Vision
import ReadiumAdapterGCDWebServer
@preconcurrency import ReadiumNavigator
@preconcurrency import ReadiumShared

@MainActor
final class PDFReaderViewController: UIViewController, PDFNavigatorDelegate,
    PublicationReaderController, @preconcurrency UIEditMenuInteractionDelegate,
    UIGestureRecognizerDelegate
{
    let navigator: PDFNavigatorViewController

    var onLocationChange: ((Locator) -> Void)?
    var onSelectedText: ((ReaderSelectionPayload) -> Void)?
    var onSelectionFrameChange: ((CGRect?) -> Void)?
    var onContentTap: (() -> Void)?
    var onReaderError: ((String) -> Void)?
    var onReaderActivityChange: ((String?) -> Void)?
    var onPaginationModeChange: ((Bool) -> Void)?
    var onAnnotationActivated: ((UUID) -> Void)?

    let isPaginated = true
    let supportsQuickSentenceTranslation = true

    var currentLocator: Locator? {
        navigator.currentLocation
    }

    private let httpServer: HTTPServer
    private let bookLanguage: String?
    private weak var pdfView: PDFDocumentView?
    private var isQuickSentenceTranslationEnabled = false
    private var quickTranslationUnit = ReaderQuickTranslationUnit.sentence
    private var disablesTapPageTurnsDuringQuickTranslation = true
    private var isPDFPaperModeEnabled: Bool
    private var ocrCache: [Int: [PDFOCRTextObservation]] = [:]
    private var ocrCacheOrder: [Int] = []
    private static let maximumCachedOCRPages = 12
    private var ocrTask: Task<Void, Never>?
    private var ocrRequestID: UUID?
    private let translationHighlightView = PDFTranslationHighlightView()
    private var translationHighlightAnchor: PDFTranslationHighlightAnchor?
    private var translationHighlightDisplayLink: CADisplayLink?
    private var lastHighlightLayoutSignature: PDFHighlightLayoutSignature?
    private var lastHighlightFrameInWindow: CGRect?
    private var ocrEditMenuInteraction: UIEditMenuInteraction?
    private weak var ocrLongPressGesture: UILongPressGestureRecognizer?
    private var pendingOCRMenuPoint: CGPoint?
    private var installedPDFAnnotations: [(annotation: PDFAnnotation, page: PDFPage)] = []
    private var annotationIDs: [ObjectIdentifier: UUID] = [:]
    private var annotationPresentations: [ReaderAnnotationPresentation] = []
    private var selectionGestureGate = EPUBSelectionGestureGate()

    init(
        publication: Publication,
        initialLocation: Locator?,
        theme: ReaderThemeChoice,
        customBackgroundHex: String = "",
        customSelectionColorHex: String = "",
        bookLanguage: String?,
        paperModeEnabled: Bool = false
    ) throws {
        self.bookLanguage = bookLanguage
        isPDFPaperModeEnabled = paperModeEnabled
        let assetRetriever = AssetRetriever(httpClient: DefaultHTTPClient())
        let server = GCDHTTPServer(assetRetriever: assetRetriever)
        httpServer = server
        navigator = try PDFNavigatorViewController(
            publication: publication,
            initialLocation: initialLocation,
            config: .init(
                preferences: Self.preferences(
                    theme: theme,
                    customBackgroundHex: customBackgroundHex
                ),
                editingActions: [
                    .copy,
                    .lookup,
                    .share,
                    EditingAction(
                        title: "翻译",
                        action: #selector(translateCurrentSelection(_:))
                    ),
                ]
            ),
            httpServer: server
        )
        super.init(nibName: nil, bundle: nil)

        translationHighlightView.updatePalette(
            ReaderSelectionVisualStyle.palette(
                theme: theme,
                customBackgroundHex: customBackgroundHex,
                customSelectionColorHex: customSelectionColorHex
            )
        )
        navigator.delegate = self
        addChild(navigator)
        navigator.view.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(navigator.view)
        NSLayoutConstraint.activate([
            navigator.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            navigator.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            navigator.view.topAnchor.constraint(equalTo: view.topAnchor),
            navigator.view.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        navigator.didMove(toParent: self)
        view.clipsToBounds = true
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        ocrTask?.cancel()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        onPaginationModeChange?(true)
        if translationHighlightAnchor != nil {
            startTranslationHighlightTracking()
            refreshTranslationHighlight(force: true)
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stopTranslationHighlightTracking()
        cancelOCRRecognition()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        guard let pdfView else { return }
        translationHighlightView.frame = pdfView.bounds
        refreshTranslationHighlight(force: true)
    }

    func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
        cancelOCRRecognition()
        ocrEditMenuInteraction?.dismissMenu()
        pendingOCRMenuPoint = nil
        selectionGestureGate.reset()
        clearTranslationHighlight()
        onLocationChange?(locator)
        onPaginationModeChange?(true)
        Task { @MainActor [weak self] in
            // PDFKit updates currentPage during this callback. Defer one turn
            // so the OCR-only recognizer is disabled before a selectable text
            // page receives its next native long press.
            await Task.yield()
            self?.updateOCRLongPressAvailability()
        }
    }

    nonisolated func navigator(
        _ navigator: PDFNavigatorViewController,
        setupPDFView view: PDFDocumentView
    ) {
        Task { @MainActor [weak self, weak view] in
            guard let self, let view else { return }
            configurePDFView(view)
        }
    }

    private func configurePDFView(_ view: PDFDocumentView) {
        stopTranslationHighlightTracking()
        translationHighlightAnchor = nil
        lastHighlightLayoutSignature = nil
        lastHighlightFrameInWindow = nil
        if let oldView = pdfView {
            oldView.highlightedSelections = nil
            if let ocrEditMenuInteraction {
                oldView.removeInteraction(ocrEditMenuInteraction)
            }
            if let ocrLongPressGesture {
                oldView.removeGestureRecognizer(ocrLongPressGesture)
            }
        }
        pdfView = view
        translationHighlightView.removeFromSuperview()
        translationHighlightView.frame = view.bounds
        translationHighlightView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(translationHighlightView)

        let menuInteraction = UIEditMenuInteraction(delegate: self)
        view.addInteraction(menuInteraction)
        ocrEditMenuInteraction = menuInteraction

        let longPress = UILongPressGestureRecognizer(
            target: self,
            action: #selector(handleOCRLongPress(_:))
        )
        longPress.minimumPressDuration = PDFSelectionGesturePolicy.ocrLongPressDuration
        longPress.allowableMovement = 36
        longPress.cancelsTouchesInView = false
        longPress.delaysTouchesBegan = false
        longPress.delegate = self
        view.addGestureRecognizer(longPress)
        ocrLongPressGesture = longPress
        updateOCRLongPressAvailability()
        renderInstalledAnnotations()
    }

    func navigator(_ navigator: VisualNavigator, didTapAt point: CGPoint) {
        // PDFKit can emit a normal tap immediately after the user releases a
        // native long-press selection. Without the same gate used by EPUB, that
        // trailing callback enters quick translation and clears the range
        // before the edit menu can be used.
        if selectionGestureGate.shouldSuppressTap(
            at: ProcessInfo.processInfo.systemUptime
        ) {
            return
        }

        let width = max(view.bounds.width, 1)
        let edge = max(72, width * 0.22)
        let isLeftEdge = point.x <= edge
        let isRightEdge = point.x >= width - edge
        let suppressesTapPageTurns = ReaderTapInteractionPolicy.suppressesPageTurn(
            quickSentenceTranslationEnabled: isQuickSentenceTranslationEnabled,
            disablesTapPageTurnsDuringQuickTranslation:
                disablesTapPageTurnsDuringQuickTranslation
        )

        if isQuickSentenceTranslationEnabled, suppressesTapPageTurns {
            translateSentence(at: point)
        } else if isLeftEdge {
            navigateBackward()
        } else if isRightEdge {
            navigateForward()
        } else if isQuickSentenceTranslationEnabled {
            translateSentence(at: point)
        } else {
            onContentTap?()
        }
    }

    func navigator(
        _ navigator: SelectableNavigator,
        shouldShowMenuForSelection selection: Selection
    ) -> Bool {
        selectionGestureGate.noteNativeSelection(
            at: ProcessInfo.processInfo.systemUptime
        )
        onSelectionFrameChange?(selectionFrameInWindow(selection.frame))
        return true
    }

    func navigator(
        _ navigator: SelectableNavigator,
        canPerformAction action: EditingAction,
        for selection: Selection
    ) -> Bool {
        let selectedText = PDFTranslationMenuPolicy.normalizedText(
            selection.locator.text.highlight
        ) ?? PDFTranslationMenuPolicy.normalizedText(
            pdfView?.currentSelection?.string
        )
        let count = selectedText?.count ?? 0
        if action == .lookup {
            return (1 ... 80).contains(count)
        }
        return selectedText != nil
    }

    func navigator(_ navigator: Navigator, presentError error: NavigatorError) {
        switch error {
        case .copyForbidden:
            onReaderError?("这份 PDF 不允许复制所选文字。")
        }
    }

    func navigateBackward() {
        Task { await navigator.goBackward(options: .animated) }
    }

    func navigateForward() {
        Task { await navigator.goForward(options: .animated) }
    }

    func navigate(to link: ReadiumShared.Link) {
        Task { await navigator.go(to: link, options: .animated) }
    }

    func navigate(to locator: Locator) {
        Task { await navigator.go(to: locator, options: .animated) }
    }

    func search(_ query: String) async throws -> [Locator] {
        let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { throw ReaderSearchError.emptyQuery }
        guard let document = pdfView?.document,
              let baseLocator = navigator.currentLocation
        else {
            throw ReaderSearchError.unavailable
        }

        var results: [Locator] = []
        for pageIndex in 0 ..< document.pageCount {
            try Task.checkCancellation()
            guard let pageText = document.page(at: pageIndex)?.string,
                  !pageText.isEmpty
            else {
                continue
            }

            let source = pageText as NSString
            var searchRange = NSRange(location: 0, length: source.length)
            while searchRange.length > 0 {
                let match = source.range(
                    of: normalized,
                    options: [.caseInsensitive, .diacriticInsensitive],
                    range: searchRange
                )
                guard match.location != NSNotFound else { break }

                let beforeStart = max(0, match.location - 70)
                let afterEnd = min(source.length, NSMaxRange(match) + 90)
                var locator = baseLocator
                locator.title = "第 \(pageIndex + 1) 页"
                locator.locations.fragments = ["page=\(pageIndex + 1)"]
                locator.locations.position = pageIndex + 1
                locator.locations.totalProgression = document.pageCount > 1
                    ? Double(pageIndex) / Double(document.pageCount - 1)
                    : 0
                locator.text = Locator.Text(
                    after: source.substring(
                        with: NSRange(
                            location: NSMaxRange(match),
                            length: afterEnd - NSMaxRange(match)
                        )
                    ),
                    before: source.substring(
                        with: NSRange(
                            location: beforeStart,
                            length: match.location - beforeStart
                        )
                    ),
                    highlight: source.substring(with: match)
                )
                results.append(locator)

                let nextLocation = NSMaxRange(match)
                guard nextLocation > searchRange.location else { break }
                searchRange = NSRange(
                    location: nextLocation,
                    length: source.length - nextLocation
                )
            }

            if pageIndex.isMultiple(of: 8) {
                await Task.yield()
            }
        }
        return results
    }

    func crossPageContext(for sourceText: String) async -> String? {
        crossPageContext(
            for: sourceText,
            localContext: currentTextSelectionContext(
                expectedText: sourceText
            ),
            anchor: translationHighlightAnchor
        )
    }

    func clearSelection() {
        cancelOCRRecognition()
        ocrEditMenuInteraction?.dismissMenu()
        pendingOCRMenuPoint = nil
        selectionGestureGate.reset()
        clearTranslationHighlight()
        onSelectionFrameChange?(nil)
        navigator.clearSelection()
    }

    func updateSpeechHighlight(_ highlight: ReaderSpeechHighlight?) {
        translationHighlightView.updateSpeechHighlight(highlight)
    }

    func applyAnnotations(_ annotations: [ReaderAnnotationPresentation]) {
        annotationPresentations = annotations
        renderInstalledAnnotations()
    }

    private func renderInstalledAnnotations() {
        removeInstalledPDFAnnotations()
        guard let document = pdfView?.document else { return }

        for item in annotationPresentations {
            guard let anchor = ReaderPDFAnnotationAnchor(jsonString: item.anchorJSON),
                  anchor.pageIndex >= 0,
                  anchor.pageIndex < document.pageCount,
                  let page = document.page(at: anchor.pageIndex)
            else { continue }

            let pageBounds = page.bounds(for: pdfView?.displayBox ?? .cropBox).standardized
            for normalized in anchor.rectangles {
                let rectangle = Self.pageRectangle(
                    for: normalized.cgRect,
                    coordinateSpace: anchor.coordinateSpace,
                    pageBounds: pageBounds
                )
                guard !rectangle.isEmpty else { continue }
                let annotation = PDFAnnotation(
                    bounds: rectangle.insetBy(dx: -1.5, dy: -0.75),
                    forType: .highlight,
                    withProperties: nil
                )
                annotation.color = item.color.pdfColor
                annotation.userName = "Jerreader:\(item.id.uuidString)"
                annotation.isReadOnly = true
                page.addAnnotation(annotation)
                installedPDFAnnotations.append((annotation, page))
                annotationIDs[ObjectIdentifier(annotation)] = item.id
            }
        }
        pdfView?.setNeedsDisplay()
    }

    func setQuickSentenceTranslationEnabled(_ enabled: Bool) {
        isQuickSentenceTranslationEnabled = enabled
        if !enabled {
            cancelOCRRecognition()
            clearTranslationHighlight()
        }
    }

    func setQuickTranslationUnit(_ unit: ReaderQuickTranslationUnit) {
        guard quickTranslationUnit != unit else { return }
        quickTranslationUnit = unit
        cancelOCRRecognition()
        clearTranslationHighlight()
    }

    func setQuickSentenceTapPageTurnsDisabled(_ disabled: Bool) {
        disablesTapPageTurnsDuringQuickTranslation = disabled
    }

    func setPDFPaperModeEnabled(_ enabled: Bool) {
        guard isPDFPaperModeEnabled != enabled else { return }
        isPDFPaperModeEnabled = enabled
        cancelOCRRecognition()
        clearTranslationHighlight()
    }

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
        customSelectionColorHex: String = ""
    ) {
        translationHighlightView.updatePalette(
            ReaderSelectionVisualStyle.palette(
                theme: theme,
                customBackgroundHex: customBackgroundHex,
                customSelectionColorHex: customSelectionColorHex
            )
        )
        navigator.submitPreferences(
            Self.preferences(
                theme: theme,
                customBackgroundHex: customBackgroundHex
            )
        )
        onPaginationModeChange?(true)
    }

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        if action == #selector(translateCurrentSelection(_:)) {
            return PDFTranslationMenuPolicy.normalizedText(
                pdfView?.currentSelection?.string
            ) != nil
                || PDFTranslationMenuPolicy.normalizedText(
                    navigator.currentSelection?.locator.text.highlight
                ) != nil
        }
        return super.canPerformAction(action, withSender: sender)
    }

    @objc func translateCurrentSelection(_ sender: Any?) {
        let readiumSelection = navigator.currentSelection
        let pdfSelection = pdfView?.currentSelection
        let pdfText = PDFTranslationMenuPolicy.normalizedText(pdfSelection?.string)
        let readiumText = PDFTranslationMenuPolicy.normalizedText(
            readiumSelection?.locator.text.highlight
        )
        guard let sourceText = pdfText ?? readiumText else { return }

        selectionGestureGate.noteNativeSelection(
            at: ProcessInfo.processInfo.systemUptime
        )
        let frame = pdfSelection
            .flatMap(currentTextSelectionFrameInWindow)
            ?? selectionFrameInWindow(readiumSelection?.frame)
        let readiumContext = readiumSelection.map {
            ReaderTranslationContext.window(
                highlight: sourceText,
                before: $0.locator.text.before,
                after: $0.locator.text.after
            )
        } ?? nil
        let localContext = currentTextSelectionContext(
            expectedText: sourceText
        ) ?? readiumContext
        let anchor = currentTextSelectionHighlightAnchor(
            expectedText: sourceText
        )
        if let anchor, let pdfView {
            _ = installTranslationHighlight(anchor, in: pdfView)
            pdfView.clearSelection()
            navigator.clearSelection()
        } else {
            onSelectionFrameChange?(frame)
        }
        onSelectedText?(
            ReaderSelectionPayload(
                text: sourceText,
                contextText: crossPageContext(
                    for: sourceText,
                    localContext: localContext,
                    anchor: anchor
                ),
                locatorJSON: readiumSelection?.locator.readerJSONString
                    ?? navigator.currentLocation?.readerJSONString,
                annotationAnchorJSON: annotationAnchorJSON(for: anchor),
                frame: frame,
                trigger: .preciseSelection
            )
        )
    }

    private func translateSentence(at navigatorPoint: CGPoint) {
        guard let pdfView else {
            onContentTap?()
            return
        }
        let point = pdfView.convert(navigatorPoint, from: navigator.view)
        guard let page = pdfView.page(for: point, nearest: false) else {
            onContentTap?()
            return
        }
        let pagePoint = pdfView.convert(point, to: page)
        if let annotation = page.annotation(at: pagePoint) {
            if let id = annotationIDs[ObjectIdentifier(annotation)] {
                onAnnotationActivated?(id)
            }
            return
        }

        if hasUsableTextLayer(page),
           translateTextLayerSentence(
               on: page,
               pagePoint: pagePoint,
               pdfView: pdfView
           )
        {
            return
        }

        if hasUsableTextLayer(page) {
            onContentTap?()
            return
        }
        translateOCRSentence(
            on: page,
            pointInPDFView: point,
            pdfView: pdfView
        )
    }

    private func translateTextLayerSentence(
        on page: PDFPage,
        pagePoint: CGPoint,
        pdfView: PDFDocumentView
    ) -> Bool {
        guard let pageText = page.string,
              !pageText.isEmpty
        else { return false }

        // A PDF glyph's tappable box is often much narrower than the visible
        // stroke, especially for CJK fonts and scanned documents with a hidden
        // text layer. Probe a fixed screen-space neighbourhood and convert it
        // to page coordinates for the current zoom level.
        let viewPoint = pdfView.convert(pagePoint, from: page)
        let tolerance = PDFTextLayerHitTolerance.pageSpaceTolerance(
            around: viewPoint,
            in: pdfView,
            on: page
        )
        guard let hit = PDFTextLayerHitResolver.hit(
            on: page,
            at: pagePoint,
            tolerance: tolerance
        ),
        hit.utf16Index >= 0,
        hit.utf16Index < pageText.utf16.count
        else { return false }

        let language = ReaderLanguageDetector.detect(
            text: pageText,
            bookLanguage: bookLanguage
        )
        let selectedText: String
        let contextText: String?
        let selection: PDFSelection
        if isPDFPaperModeEnabled,
           let paperSelection = paperModeTextSelection(
               on: page,
               pageText: pageText,
               pagePoint: pagePoint,
               utf16Offset: hit.utf16Index,
               language: language
           )
        {
            selectedText = paperSelection.result.text
            contextText = paperSelection.result.contextText
            selection = paperSelection.selection
        } else {
            let segment: ReaderSentenceSegment?
            switch quickTranslationUnit {
            case .sentence:
                segment = PDFTextLayerSentenceSelector.sentence(
                    in: pageText,
                    utf16Offset: hit.utf16Index,
                    language: language
                )
            case .paragraph:
                segment = PDFTextLayerSentenceSelector.paragraph(
                    in: pageText,
                    utf16Offset: hit.utf16Index
                )
            }
            guard let segment,
                  let standardSelection = page.selection(
                      for: NSRange(
                          location: segment.utf16Range.lowerBound,
                          length: segment.utf16Range.count
                      )
                  )
            else { return false }
            selectedText = segment.text
            contextText = segment.contextText
            selection = standardSelection
        }

        clearTranslationHighlight()
        // A quick tap is not a native text-selection gesture. Keeping the
        // sentence in PDFView.currentSelection lets PDFKit's own tap recognizer
        // race this callback and can leave an unrelated word or heading blue.
        // highlightedSelections retains semantic PDF ranges without exposing
        // draggable native selection state.
        pdfView.clearSelection()
        navigator.clearSelection()
        Task { @MainActor [weak self, weak pdfView] in
            // PDFKit completes its own single-tap recognizer after Readium's
            // didTap callback and may recreate that unrelated selection. Clear
            // once more after the gesture transaction, while keeping our
            // noninteractive sentence overlay visible.
            try? await Task.sleep(for: .milliseconds(60))
            guard !Task.isCancelled else { return }
            pdfView?.clearSelection()
            self?.navigator.clearSelection()
        }
        guard let document = page.document else { return false }
        let pageIndex = document.index(for: page)
        guard pageIndex != NSNotFound else { return false }
        let frame = installTranslationHighlight(
            .text(
                pageIndex: pageIndex,
                selection: (selection.copy() as? PDFSelection) ?? selection,
                expectedText: selectedText
            ),
            in: pdfView
        ) ?? pdfView.convert(selection.bounds(for: page), from: page)
        deliverSelection(
            text: selectedText,
            contextText: contextText,
            frameInPDFView: frame,
            pdfView: pdfView
        )
        return true
    }

    private func paperModeTextSelection(
        on page: PDFPage,
        pageText: String,
        pagePoint: CGPoint,
        utf16Offset: Int,
        language: LanguageCode?
    ) -> (result: PDFPaperTextSelection, selection: PDFSelection)? {
        guard let document = page.document else { return nil }
        let source = pageText as NSString
        guard source.length > 0,
              let entirePage = page.selection(
                  for: NSRange(location: 0, length: source.length)
              )
        else { return nil }

        let lines = entirePage.selectionsByLine().compactMap {
            paperTextLine(from: $0, on: page, source: source)
        }
        guard let result = PDFPaperTextSelector.selection(
            unit: quickTranslationUnit,
            pageBounds: page.bounds(for: pdfView?.displayBox ?? .cropBox),
            pagePoint: pagePoint,
            utf16Offset: utf16Offset,
            lines: lines,
            language: language
        ) else { return nil }

        let pieces = result.pageUTF16Ranges.compactMap {
            page.selection(for: $0)
        }
        guard pieces.count == result.pageUTF16Ranges.count,
              !pieces.isEmpty
        else { return nil }
        let combined = PDFSelection(document: document)
        for piece in pieces {
            combined.add(piece)
        }
        return (result, combined)
    }

    private func paperTextLine(
        from selection: PDFSelection,
        on page: PDFPage,
        source: NSString
    ) -> PDFPaperTextLine? {
        guard selection.numberOfTextRanges(on: page) > 0 else { return nil }
        let rawRange = selection.range(at: 0, on: page)
        guard rawRange.location != NSNotFound,
              rawRange.location >= 0,
              rawRange.length > 0,
              NSMaxRange(rawRange) <= source.length
        else { return nil }

        // PDFKit line ranges often include CR/LF separators. Strip only those
        // boundary code units so the fragment length continues to map exactly
        // back to page UTF-16 offsets; leading spaces remain available to the
        // existing paragraph-indentation policy.
        var lower = rawRange.location
        var upper = NSMaxRange(rawRange)
        while lower < upper {
            let unit = source.character(at: lower)
            guard unit == 10 || unit == 13 else { break }
            lower += 1
        }
        while lower < upper {
            let unit = source.character(at: upper - 1)
            guard unit == 10 || unit == 13 else { break }
            upper -= 1
        }
        guard lower < upper else { return nil }
        let range = NSRange(location: lower, length: upper - lower)
        let text = source.substring(with: range)
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return nil }
        let bounds = selection.bounds(for: page).standardized
        guard !bounds.isEmpty else { return nil }
        return PDFPaperTextLine(
            text: text,
            pageUTF16Range: range,
            bounds: bounds
        )
    }

    @objc private func handleOCRLongPress(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began,
              let pdfView,
              let page = pdfView.page(for: gesture.location(in: pdfView), nearest: false),
              !hasUsableTextLayer(page),
              let interaction = ocrEditMenuInteraction
        else { return }

        let point = gesture.location(in: pdfView)
        pendingOCRMenuPoint = point
        interaction.presentEditMenu(
            with: UIEditMenuConfiguration(
                identifier: nil,
                sourcePoint: point
            )
        )
    }

    func editMenuInteraction(
        _ interaction: UIEditMenuInteraction,
        menuFor configuration: UIEditMenuConfiguration,
        suggestedActions: [UIMenuElement]
    ) -> UIMenu? {
        guard let pdfView, let point = pendingOCRMenuPoint else { return nil }
        return UIMenu(children: [
            UIAction(
                title: "翻译",
                image: UIImage(systemName: "character.bubble")
            ) { [weak self, weak pdfView] _ in
                guard let self, let pdfView else { return }
                let navigatorPoint = pdfView.convert(point, to: self.navigator.view)
                self.pendingOCRMenuPoint = nil
                self.translateSentence(at: navigatorPoint)
            },
        ])
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        PDFSelectionGesturePolicy.allowsSimultaneousRecognition(
            otherGestureIsPagePan: otherGestureRecognizer is UIPanGestureRecognizer
        )
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard gestureRecognizer === ocrLongPressGesture,
              let pdfView,
              let page = pdfView.page(
                  for: gestureRecognizer.location(in: pdfView),
                  nearest: false
              )
        else { return false }
        return PDFSelectionGesturePolicy.usesCustomOCRLongPress(
            hasUsableTextLayer: hasUsableTextLayer(page)
        )
    }

    private func translateOCRSentence(
        on page: PDFPage,
        pointInPDFView: CGPoint,
        pdfView: PDFDocumentView
    ) {
        guard let document = page.document else {
            onReaderError?("无法读取这一页，请重新打开 PDF 后再试。")
            return
        }
        let pageIndex = document.index(for: page)
        guard pageIndex != NSNotFound,
              let normalizedPoint = PDFOCRPageCoordinateMapper.normalizedPoint(
                  pointInPDFView,
                  in: pdfView.convert(
                      page.bounds(for: pdfView.displayBox),
                      from: page
                  )
              )
        else {
            onContentTap?()
            return
        }

        if let cached = ocrCache[pageIndex] {
            deliverOCRSelection(
                at: normalizedPoint,
                observations: cached,
                page: page,
                pdfView: pdfView
            )
            return
        }

        let pageBounds = page.bounds(for: pdfView.displayBox).standardized
        let longestSide = max(max(pageBounds.width, pageBounds.height), 1)
        let renderScale = min(max(2_200 / longestSide, 1), 3.2)
        let image = page.thumbnail(
            of: CGSize(
                width: max(pageBounds.width * renderScale, 1),
                height: max(pageBounds.height * renderScale, 1)
            ),
            for: pdfView.displayBox
        )
        guard let cgImage = image.cgImage else {
            onReaderError?("无法生成这一页的 OCR 图像。")
            return
        }

        cancelOCRRecognition()
        pdfView.clearSelection()
        clearTranslationHighlight()
        let requestID = UUID()
        ocrRequestID = requestID
        onReaderActivityChange?("正在识别本页文字…")
        let languages = recognitionLanguages
        ocrTask = Task { @MainActor [weak self, weak pdfView, weak page] in
            guard let self else { return }
            do {
                let observations = try await PDFPageTextRecognizer.recognize(
                    cgImage: cgImage,
                    languages: languages
                )
                guard !Task.isCancelled,
                      ocrRequestID == requestID,
                      let pdfView,
                      let page,
                      pdfView.currentPage === page
                else { return }

                cacheOCR(observations, forPage: pageIndex)
                ocrRequestID = nil
                ocrTask = nil
                onReaderActivityChange?(nil)
                guard !observations.isEmpty else {
                    onReaderError?("本页没有识别出可翻译文字，请确认扫描内容清晰。")
                    return
                }
                deliverOCRSelection(
                    at: normalizedPoint,
                    observations: observations,
                    page: page,
                    pdfView: pdfView
                )
            } catch is CancellationError {
                guard ocrRequestID == requestID else { return }
                ocrRequestID = nil
                ocrTask = nil
                onReaderActivityChange?(nil)
            } catch {
                guard ocrRequestID == requestID else { return }
                ocrRequestID = nil
                ocrTask = nil
                onReaderActivityChange?(nil)
                onReaderError?("本页文字识别失败，请稍后重试。")
            }
        }
    }

    private func deliverOCRSelection(
        at point: CGPoint,
        observations: [PDFOCRTextObservation],
        page: PDFPage,
        pdfView: PDFDocumentView
    ) {
        let preferredLanguage: LanguageCode?
        switch bookLanguage?.lowercased() {
        case let language? where language.hasPrefix("ja"):
            preferredLanguage = .japanese
        case let language? where language.hasPrefix("zh"):
            preferredLanguage = .simplifiedChinese
        case let language? where language.hasPrefix("en"):
            preferredLanguage = .english
        default:
            preferredLanguage = nil
        }
        let selection: PDFOCRSentenceSelection?
        switch quickTranslationUnit {
        case .sentence:
            selection = PDFOCRTextSelector.sentence(
                at: point,
                observations: observations,
                preferredLanguage: preferredLanguage,
                paperMode: isPDFPaperModeEnabled
            )
        case .paragraph:
            selection = PDFOCRTextSelector.paragraph(
                at: point,
                observations: observations,
                preferredLanguage: preferredLanguage,
                paperMode: isPDFPaperModeEnabled
            )
        }
        guard let selection else {
            onReaderError?("没有点到可识别的文字，请直接点在文字行上。")
            return
        }

        guard let document = page.document else {
            onReaderError?("无法读取这一页，请重新打开 PDF 后再试。")
            return
        }
        let pageIndex = document.index(for: page)
        guard pageIndex != NSNotFound else {
            onReaderError?("无法定位识别到的 PDF 页面，请重新点击。")
            return
        }

        pdfView.clearSelection()
        guard let frame = installTranslationHighlight(
            .ocr(pageIndex: pageIndex, normalizedBoxes: selection.boundingBoxes),
            in: pdfView
        ) else {
            onReaderError?("无法定位识别到的文字，请重新点击。")
            return
        }
        deliverSelection(
            text: selection.text,
            contextText: selection.contextText,
            frameInPDFView: frame.insetBy(dx: -5, dy: -4),
            pdfView: pdfView
        )
    }

    private func deliverSelection(
        text: String,
        contextText: String?,
        frameInPDFView: CGRect,
        pdfView: PDFDocumentView
    ) {
        let frameInWindow = pdfView.convert(frameInPDFView.standardized, to: nil)
        onSelectionFrameChange?(frameInWindow)
        onSelectedText?(
            ReaderSelectionPayload(
                text: text,
                contextText: crossPageContext(
                    for: text,
                    localContext: contextText,
                    anchor: translationHighlightAnchor
                ),
                locatorJSON: navigator.currentLocation?.readerJSONString,
                annotationAnchorJSON: annotationAnchorJSON(
                    for: translationHighlightAnchor
                ),
                frame: frameInWindow,
                trigger: .quickSentence
            )
        )
    }

    private func annotationAnchorJSON(
        for anchor: PDFTranslationHighlightAnchor?
    ) -> String? {
        guard let anchor,
              let document = pdfView?.document,
              anchor.pageIndex >= 0,
              anchor.pageIndex < document.pageCount,
              let page = document.page(at: anchor.pageIndex)
        else { return nil }

        switch anchor {
        case let .text(pageIndex, selection, expectedText):
            let pageBounds = page.bounds(for: pdfView?.displayBox ?? .cropBox).standardized
            let normalized = PDFTranslationHighlightGeometry.pageRectangles(
                for: selection,
                on: page,
                expectedText: expectedText
            ).compactMap { Self.normalizedPageRectangle($0, pageBounds: pageBounds) }
            return ReaderPDFAnnotationAnchor(
                pageIndex: pageIndex,
                coordinateSpace: .pdfPage,
                rectangles: normalized
            ).jsonString

        case let .ocr(pageIndex, normalizedBoxes):
            return ReaderPDFAnnotationAnchor(
                pageIndex: pageIndex,
                coordinateSpace: .vision,
                rectangles: normalizedBoxes
            ).jsonString
        }
    }

    private func currentTextSelectionHighlightAnchor(
        expectedText: String
    ) -> PDFTranslationHighlightAnchor? {
        guard let pdfView,
              let selection = pdfView.currentSelection,
              let page = selection.pages.first,
              let document = page.document
        else { return nil }
        let pageIndex = document.index(for: page)
        guard pageIndex != NSNotFound else { return nil }
        return .text(
            pageIndex: pageIndex,
            selection: (selection.copy() as? PDFSelection) ?? selection,
            expectedText: expectedText
        )
    }

    private func currentTextSelectionFrameInWindow(
        _ selection: PDFSelection
    ) -> CGRect? {
        guard let pdfView else { return nil }
        let rectangles = selection.pages.compactMap { page -> CGRect? in
            let rectangle = pdfView.convert(
                selection.bounds(for: page),
                from: page
            ).standardized
            return rectangle.isEmpty ? nil : rectangle
        }
        guard let first = rectangles.first else { return nil }
        let union = rectangles.dropFirst().reduce(first) { $0.union($1) }
        return pdfView.convert(union, to: nil).standardized
    }

    private func currentTextSelectionContext(
        expectedText: String
    ) -> String? {
        guard let selection = pdfView?.currentSelection,
              let page = selection.pages.first,
              let pageText = page.string
        else { return nil }
        let range = selection.range(at: 0, on: page)
        let source = pageText as NSString
        guard range.location != NSNotFound,
              range.location >= 0,
              range.location <= source.length
        else { return nil }

        let beforeLength = min(range.location, 600)
        let afterStart = min(NSMaxRange(range), source.length)
        let afterLength = min(source.length - afterStart, 600)
        return ReaderTranslationContext.window(
            highlight: expectedText,
            before: source.substring(
                with: NSRange(
                    location: range.location - beforeLength,
                    length: beforeLength
                )
            ),
            after: source.substring(
                with: NSRange(location: afterStart, length: afterLength)
            )
        )
    }

    private func crossPageContext(
        for text: String,
        localContext: String?,
        anchor: PDFTranslationHighlightAnchor?
    ) -> String? {
        guard let anchor,
              let document = pdfView?.document
        else { return localContext }
        let pageIndex = anchor.pageIndex
        let previous = pageIndex > 0
            ? document.page(at: pageIndex - 1)?.string
            : nil
        let next = pageIndex + 1 < document.pageCount
            ? document.page(at: pageIndex + 1)?.string
            : nil
        return ReaderCrossPageContextBuilder.context(
            sourceText: text,
            previousPageText: previous,
            localContext: localContext,
            nextPageText: next
        ) ?? localContext
    }

    private static func normalizedPageRectangle(
        _ rectangle: CGRect,
        pageBounds: CGRect
    ) -> CGRect? {
        guard pageBounds.width > 0, pageBounds.height > 0 else { return nil }
        let value = CGRect(
            x: (rectangle.minX - pageBounds.minX) / pageBounds.width,
            y: (rectangle.minY - pageBounds.minY) / pageBounds.height,
            width: rectangle.width / pageBounds.width,
            height: rectangle.height / pageBounds.height
        ).standardized
        guard value.minX.isFinite,
              value.minY.isFinite,
              value.width.isFinite,
              value.height.isFinite,
              !value.isEmpty
        else { return nil }
        return value
    }

    private static func pageRectangle(
        for normalized: CGRect,
        coordinateSpace: ReaderPDFAnnotationAnchor.CoordinateSpace,
        pageBounds: CGRect
    ) -> CGRect {
        // PDF page and Vision rectangles both use a lower-left origin. Keeping
        // the coordinate-space tag makes future OCR rotation migrations
        // explicit without changing persisted records.
        CGRect(
            x: pageBounds.minX + normalized.minX * pageBounds.width,
            y: pageBounds.minY + normalized.minY * pageBounds.height,
            width: normalized.width * pageBounds.width,
            height: normalized.height * pageBounds.height
        ).standardized
    }

    private func removeInstalledPDFAnnotations() {
        for item in installedPDFAnnotations {
            item.page.removeAnnotation(item.annotation)
        }
        installedPDFAnnotations.removeAll()
        annotationIDs.removeAll()
    }

    private func pdfViewRectangle(
        for normalizedBox: CGRect,
        page: PDFPage,
        pdfView: PDFDocumentView
    ) -> CGRect? {
        let pageRect = pdfView.convert(
            page.bounds(for: pdfView.displayBox),
            from: page
        ).standardized
        return PDFOCRPageCoordinateMapper.viewRectangle(
            for: normalizedBox,
            in: pageRect
        )
    }

    private func hasUsableTextLayer(_ page: PDFPage) -> Bool {
        guard let text = page.string?.trimmingCharacters(in: .whitespacesAndNewlines),
              text.utf16.count >= 4
        else { return false }
        return text.unicodeScalars.contains { CharacterSet.letters.contains($0) }
    }

    private func updateOCRLongPressAvailability() {
        guard let gesture = ocrLongPressGesture,
              let pdfView,
              let interaction = ocrEditMenuInteraction
        else {
            ocrLongPressGesture?.isEnabled = false
            if let interaction = ocrEditMenuInteraction,
               let interactionView = interaction.view
            {
                interactionView.removeInteraction(interaction)
            }
            return
        }
        // Keep the recognizer available for every visible page. In an iPad
        // spread, `currentPage` can be a selectable page while the adjacent
        // page is scanned. The delegate checks the exact touched page and
        // declines immediately for a usable text layer, so native PDFKit
        // selection never competes with this OCR fallback.
        gesture.isEnabled = true
        if interaction.view !== pdfView {
            pdfView.addInteraction(interaction)
        }
    }

    private var recognitionLanguages: [String] {
        let preferred: String?
        switch bookLanguage?.lowercased() {
        case let language? where language.hasPrefix("ja"):
            preferred = "ja-JP"
        case let language? where language.hasPrefix("zh"):
            preferred = "zh-Hans"
        case let language? where language.hasPrefix("en"):
            preferred = "en-US"
        default:
            preferred = nil
        }
        return [preferred, "ja-JP", "en-US", "zh-Hans"]
            .compactMap { $0 }
            .reduce(into: []) { result, language in
                if !result.contains(language) {
                    result.append(language)
                }
            }
    }

    private func cancelOCRRecognition() {
        ocrTask?.cancel()
        ocrTask = nil
        ocrRequestID = nil
        onReaderActivityChange?(nil)
    }

    private func cacheOCR(
        _ observations: [PDFOCRTextObservation],
        forPage pageIndex: Int
    ) {
        ocrCache[pageIndex] = observations
        ocrCacheOrder.removeAll { $0 == pageIndex }
        ocrCacheOrder.append(pageIndex)
        while ocrCacheOrder.count > Self.maximumCachedOCRPages {
            let evicted = ocrCacheOrder.removeFirst()
            ocrCache.removeValue(forKey: evicted)
        }
    }

    @discardableResult
    private func installTranslationHighlight(
        _ anchor: PDFTranslationHighlightAnchor,
        in pdfView: PDFDocumentView
    ) -> CGRect? {
        translationHighlightAnchor = anchor
        lastHighlightLayoutSignature = nil
        lastHighlightFrameInWindow = nil
        startTranslationHighlightTracking()
        return refreshTranslationHighlight(force: true)
    }

    @discardableResult
    private func refreshTranslationHighlight(force: Bool) -> CGRect? {
        guard let pdfView,
              let anchor = translationHighlightAnchor,
              let document = pdfView.document
        else { return nil }

        let signature = highlightLayoutSignature(for: anchor, in: pdfView)
        if !force, signature == lastHighlightLayoutSignature {
            return lastHighlightFrameInWindow.map { pdfView.convert($0, from: nil) }
        }
        lastHighlightLayoutSignature = signature

        let pageIndex = anchor.pageIndex
        guard pageIndex >= 0,
              pageIndex < document.pageCount,
              let page = document.page(at: pageIndex)
        else {
            clearTranslationHighlight()
            return nil
        }

        let rectangles: [CGRect]
        switch anchor {
        case let .text(_, selection, expectedText):
            pdfView.highlightedSelections = nil
            rectangles = PDFTranslationHighlightGeometry.viewRectangles(
                for: selection,
                on: page,
                in: pdfView,
                expectedText: expectedText
            )
            translationHighlightView.show(rectangles: rectangles)
            pdfView.bringSubviewToFront(translationHighlightView)

        case let .ocr(_, normalizedBoxes):
            pdfView.highlightedSelections = nil
            rectangles = normalizedBoxes.compactMap {
                pdfViewRectangle(for: $0, page: page, pdfView: pdfView)
            }
            translationHighlightView.show(rectangles: rectangles)
            pdfView.bringSubviewToFront(translationHighlightView)
        }

        guard let frameInPDFView = rectangles.reduce(nil as CGRect?, { partial, rectangle in
            partial?.union(rectangle) ?? rectangle
        })?.standardized,
        !frameInPDFView.isEmpty
        else {
            return nil
        }

        let paddedFrame = frameInPDFView.insetBy(dx: -5, dy: -4)
        let frameInWindow = pdfView.convert(paddedFrame, to: nil).standardized
        if !Self.nearlyEqual(frameInWindow, lastHighlightFrameInWindow) {
            lastHighlightFrameInWindow = frameInWindow
            onSelectionFrameChange?(frameInWindow)
        }
        return paddedFrame
    }

    private func highlightLayoutSignature(
        for anchor: PDFTranslationHighlightAnchor,
        in pdfView: PDFDocumentView
    ) -> PDFHighlightLayoutSignature {
        let scrollView = firstDescendantScrollView(in: pdfView)
        return PDFHighlightLayoutSignature(
            pageIndex: anchor.pageIndex,
            viewBounds: pdfView.bounds,
            scaleFactor: pdfView.scaleFactor,
            contentOffset: scrollView?.contentOffset ?? .zero,
            zoomScale: scrollView?.zoomScale ?? 1
        )
    }

    private func firstDescendantScrollView(in view: UIView) -> UIScrollView? {
        for subview in view.subviews where subview !== translationHighlightView {
            if let scrollView = subview as? UIScrollView {
                return scrollView
            }
            if let nested = firstDescendantScrollView(in: subview) {
                return nested
            }
        }
        return nil
    }

    private func startTranslationHighlightTracking() {
        guard translationHighlightDisplayLink == nil else { return }
        let displayLink = CADisplayLink(
            target: self,
            selector: #selector(trackTranslationHighlightLayout(_:))
        )
        displayLink.preferredFrameRateRange = CAFrameRateRange(
            minimum: 15,
            maximum: 30,
            preferred: 30
        )
        displayLink.add(to: .main, forMode: .common)
        translationHighlightDisplayLink = displayLink
    }

    private func stopTranslationHighlightTracking() {
        translationHighlightDisplayLink?.invalidate()
        translationHighlightDisplayLink = nil
    }

    @objc private func trackTranslationHighlightLayout(_ displayLink: CADisplayLink) {
        refreshTranslationHighlight(force: false)
    }

    private func clearTranslationHighlight() {
        translationHighlightAnchor = nil
        lastHighlightLayoutSignature = nil
        lastHighlightFrameInWindow = nil
        stopTranslationHighlightTracking()
        pdfView?.highlightedSelections = nil
        translationHighlightView.clear()
        onSelectionFrameChange?(nil)
    }

    private static func nearlyEqual(_ lhs: CGRect, _ rhs: CGRect?) -> Bool {
        guard let rhs else { return false }
        return abs(lhs.minX - rhs.minX) < 0.5
            && abs(lhs.minY - rhs.minY) < 0.5
            && abs(lhs.width - rhs.width) < 0.5
            && abs(lhs.height - rhs.height) < 0.5
    }

    private func selectionFrameInWindow(_ frame: CGRect?) -> CGRect? {
        guard let frame,
              frame.minX.isFinite,
              frame.minY.isFinite,
              frame.width.isFinite,
              frame.height.isFinite,
              !frame.isEmpty
        else {
            return nil
        }
        return navigator.view.convert(frame.standardized, to: nil)
    }

    private static func preferences(
        theme: ReaderThemeChoice,
        customBackgroundHex: String = ""
    ) -> PDFPreferences {
        let backgroundHex = ReaderCustomBackground.normalizedHex(
            customBackgroundHex
        ) ?? theme.pdfBackgroundHex
        return PDFPreferences(
            backgroundColor: ReadiumNavigator.Color(hex: backgroundHex),
            fit: .page,
            pageSpacing: 12,
            readingProgression: .ltr,
            scroll: false,
            spread: .auto,
            visibleScrollbar: false
        )
    }
}

/// The source of truth for a PDF translation highlight. Text PDFs retain the
/// semantic PDFSelection in page space; OCR pages retain normalized page
/// boxes. View-space rectangles are derived only for the current layout frame.
enum PDFTranslationHighlightAnchor {
    case text(
        pageIndex: Int,
        selection: PDFSelection,
        expectedText: String
    )
    case ocr(
        pageIndex: Int,
        normalizedBoxes: [CGRect]
    )

    var pageIndex: Int {
        switch self {
        case let .text(pageIndex, _, _),
             let .ocr(pageIndex, _):
            return pageIndex
        }
    }
}

private struct PDFHighlightLayoutSignature: Equatable {
    let pageIndex: Int
    let viewBounds: CGRect
    let scaleFactor: CGFloat
    let contentOffset: CGPoint
    let zoomScale: CGFloat
}

enum PDFSelectionGesturePolicy {
    static let ocrLongPressDuration: TimeInterval = 0.30

    static func usesCustomOCRLongPress(hasUsableTextLayer: Bool) -> Bool {
        !hasUsableTextLayer
    }

    static func allowsSimultaneousRecognition(
        otherGestureIsPagePan: Bool
    ) -> Bool {
        !otherGestureIsPagePan
    }
}

struct PDFTextLayerHit {
    let utf16Index: Int
    let bounds: CGRect
}

enum PDFTextLayerHitTolerance {
    /// Keeps the touch target stable in screen points while PDFKit changes its
    /// page-space scale during zooming and device rotation.
    @MainActor
    static func pageSpaceTolerance(
        around viewPoint: CGPoint,
        in pdfView: PDFView,
        on page: PDFPage,
        screenRadius: CGFloat = 16
    ) -> CGSize {
        let radius = max(abs(screenRadius), 1)
        let pagePoint = pdfView.convert(viewPoint, to: page)
        let horizontalProbe = pdfView.convert(
            CGPoint(x: viewPoint.x + radius, y: viewPoint.y),
            to: page
        )
        let verticalProbe = pdfView.convert(
            CGPoint(x: viewPoint.x, y: viewPoint.y + radius),
            to: page
        )
        return CGSize(
            width: max(
                abs(horizontalProbe.x - pagePoint.x),
                abs(verticalProbe.x - pagePoint.x),
                0.5
            ),
            height: max(
                abs(horizontalProbe.y - pagePoint.y),
                abs(verticalProbe.y - pagePoint.y),
                0.5
            )
        )
    }
}

enum PDFTextLayerHitResolver {
    static func hit(
        on page: PDFPage,
        at point: CGPoint,
        tolerance: CGSize
    ) -> PDFTextLayerHit? {
        let horizontal = max(abs(tolerance.width), 1)
        let vertical = max(abs(tolerance.height), 1)
        let sampleOffsets = [
            CGPoint.zero,
            CGPoint(x: horizontal * 0.4, y: 0),
            CGPoint(x: -horizontal * 0.4, y: 0),
            CGPoint(x: horizontal * 0.8, y: 0),
            CGPoint(x: -horizontal * 0.8, y: 0),
            CGPoint(x: horizontal, y: 0),
            CGPoint(x: -horizontal, y: 0),
            CGPoint(x: 0, y: vertical * 0.5),
            CGPoint(x: 0, y: -vertical * 0.5),
            CGPoint(x: 0, y: vertical),
            CGPoint(x: 0, y: -vertical),
            CGPoint(x: horizontal * 0.45, y: vertical * 0.45),
            CGPoint(x: -horizontal * 0.45, y: vertical * 0.45),
            CGPoint(x: horizontal * 0.45, y: -vertical * 0.45),
            CGPoint(x: -horizontal * 0.45, y: -vertical * 0.45),
            CGPoint(x: horizontal * 0.75, y: vertical * 0.75),
            CGPoint(x: -horizontal * 0.75, y: vertical * 0.75),
            CGPoint(x: horizontal * 0.75, y: -vertical * 0.75),
            CGPoint(x: -horizontal * 0.75, y: -vertical * 0.75),
        ]

        var best: (hit: PDFTextLayerHit, score: CGFloat)?
        for offset in sampleOffsets {
            let samplePoint = CGPoint(x: point.x + offset.x, y: point.y + offset.y)
            guard let candidate = candidate(on: page, at: samplePoint) else { continue }
            let bounds = candidate.bounds.standardized
            guard !bounds.isEmpty,
                  bounds.minX.isFinite,
                  bounds.minY.isFinite,
                  bounds.width.isFinite,
                  bounds.height.isFinite
            else { continue }

            let dx = max(bounds.minX - point.x, 0, point.x - bounds.maxX)
            let dy = max(bounds.minY - point.y, 0, point.y - bounds.maxY)
            guard dx <= horizontal, dy <= vertical else { continue }
            let score = hypot(dx / horizontal, dy / vertical)
            if best == nil || score < best!.score {
                best = (candidate, score)
            }
        }
        return best?.hit
    }

    private static func candidate(
        on page: PDFPage,
        at point: CGPoint
    ) -> PDFTextLayerHit? {
        if let word = page.selectionForWord(at: point),
           word.numberOfTextRanges(on: page) > 0
        {
            let range = word.range(at: 0, on: page)
            if range.location != NSNotFound,
               range.length > 0,
               range.location < page.numberOfCharacters
            {
                return PDFTextLayerHit(
                    utf16Index: range.location,
                    bounds: word.bounds(for: page)
                )
            }
        }

        let index = page.characterIndex(at: point)
        guard index != NSNotFound,
              index >= 0,
              index < page.numberOfCharacters,
              let glyph = page.selection(
                  for: NSRange(location: index, length: 1)
              )
        else { return nil }
        return PDFTextLayerHit(
            utf16Index: index,
            bounds: glyph.bounds(for: page)
        )
    }
}

enum PDFPageTextRecognizer {
    static func recognize(
        cgImage: CGImage,
        languages: [String]
    ) async throws -> [PDFOCRTextObservation] {
        try await Task.detached(priority: .userInitiated) {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = true
            request.automaticallyDetectsLanguage = true
            request.recognitionLanguages = languages
            request.minimumTextHeight = 0.006

            let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
            try handler.perform([request])
            return (request.results ?? []).compactMap { observation in
                guard let candidate = observation.topCandidates(1).first,
                      candidate.confidence >= 0.18
                else { return nil }
                return PDFOCRTextObservation(
                    text: candidate.string,
                    boundingBox: observation.boundingBox
                )
            }
        }.value
    }
}

enum PDFTranslationHighlightGeometry {
    @MainActor
    static func viewRectangles(
        for selection: PDFSelection,
        on page: PDFPage,
        in pdfView: PDFView,
        expectedText: String? = nil
    ) -> [CGRect] {
        pageRectangles(
            for: selection,
            on: page,
            expectedText: expectedText
        ).map { pdfView.convert($0, from: page).standardized }
    }

    static func pageRectangles(
        for selection: PDFSelection,
        on page: PDFPage,
        expectedText: String? = nil
    ) -> [CGRect] {
        let lineSelections = selection.selectionsByLine()
        let expected = normalized(expectedText)
        let matchingSelections = lineSelections.filter { lineSelection in
            guard !expected.isEmpty else { return true }
            let line = normalized(lineSelection.string)
            guard !line.isEmpty else { return false }
            return expected.contains(line) || line.contains(expected)
        }
        let selections: [PDFSelection]
        if !matchingSelections.isEmpty {
            selections = matchingSelections
        } else if lineSelections.isEmpty || expected.isEmpty {
            selections = [selection]
        } else {
            // Some PDFs do not expose reliable strings for line selections, so
            // no line matched the translated sentence. Highlighting the raw
            // selection would then paint whatever PDFKit widened the range to —
            // occasionally most of the page — while only one sentence is being
            // translated. Bound it to roughly the sentence's own length so the
            // highlight can never contradict the translation card.
            var budget = expected.count
            var bounded: [PDFSelection] = []
            for lineSelection in lineSelections {
                guard budget > 0 else { break }
                bounded.append(lineSelection)
                budget -= max(normalized(lineSelection.string).count, 1)
            }
            selections = bounded.isEmpty ? [selection] : bounded
        }
        return selections.compactMap { lineSelection in
            let rectangle = lineSelection.bounds(for: page).standardized
            guard rectangle.minX.isFinite,
                  rectangle.minY.isFinite,
                  rectangle.width.isFinite,
                  rectangle.height.isFinite,
                  !rectangle.isEmpty
            else { return nil }
            return rectangle
        }
    }

    private static func normalized(_ text: String?) -> String {
        (text ?? "")
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

final class PDFTranslationHighlightView: UIView {
    private let shapeLayer = CAShapeLayer()
    private let speechShapeLayer = CAShapeLayer()
    private(set) var highlightedRectangleCount = 0
    private var displayedRectangles: [CGRect] = []
    private var speechHighlight: ReaderSpeechHighlight?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        shapeLayer.fillColor = ReaderSelectionVisualStyle.fill.cgColor
        shapeLayer.strokeColor = ReaderSelectionVisualStyle.stroke.cgColor
        shapeLayer.lineWidth = CGFloat(
            ReaderSelectionHighlightMetrics.shared.STROKE_WIDTH
        )
        speechShapeLayer.fillColor = ReaderSelectionVisualStyle.spokenFill.cgColor
        layer.addSublayer(shapeLayer)
        layer.addSublayer(speechShapeLayer)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        shapeLayer.frame = bounds
        speechShapeLayer.frame = bounds
    }

    func updatePalette(_ palette: ReaderSelectionVisualPalette) {
        shapeLayer.fillColor = palette.fill.cgColor
        shapeLayer.strokeColor = palette.stroke.cgColor
        speechShapeLayer.fillColor = palette.spokenFill.cgColor
    }

    /// The same tile the EPUB reader paints — outset, stroke and corner all come
    /// from the shared metrics. This view had drifted to a 2×1pt outset and a
    /// flat 4pt corner, so a selection in a PDF was visibly fatter and blockier
    /// than the identical selection in an EPUB.
    private static func tilePath(for rectangle: CGRect) -> UIBezierPath {
        let outset = CGFloat(ReaderSelectionHighlightMetrics.shared.OUTSET)
        return UIBezierPath(
            roundedRect: rectangle.insetBy(dx: -outset, dy: -outset),
            cornerRadius: CGFloat(
                ReaderSelectionHighlightMetrics.shared
                    .cornerRadius(tileHeight: Double(rectangle.height))
            )
        )
    }

    func show(rectangles: [CGRect]) {
        let path = UIBezierPath()
        let visibleRectangles = rectangles.filter { rectangle in
            rectangle.minX.isFinite && rectangle.minY.isFinite
                && rectangle.width.isFinite && rectangle.height.isFinite
                && !rectangle.isEmpty
        }
        displayedRectangles = visibleRectangles
        for rectangle in visibleRectangles {
            path.append(Self.tilePath(for: rectangle))
        }
        shapeLayer.path = path.cgPath
        highlightedRectangleCount = visibleRectangles.count
        isHidden = visibleRectangles.isEmpty
        updateSpeechPath()
    }

    func updateSpeechHighlight(_ highlight: ReaderSpeechHighlight?) {
        guard speechHighlight != highlight else { return }
        speechHighlight = highlight
        updateSpeechPath()
    }

    func clear() {
        shapeLayer.path = nil
        speechShapeLayer.path = nil
        displayedRectangles.removeAll(keepingCapacity: true)
        speechHighlight = nil
        highlightedRectangleCount = 0
        isHidden = true
    }

    private func updateSpeechPath() {
        let path = UIBezierPath()
        for rectangle in ReaderSpeechHighlightGeometry.rectangles(
            in: displayedRectangles,
            highlight: speechHighlight
        ) {
            path.append(Self.tilePath(for: rectangle))
        }
        speechShapeLayer.path = path.cgPath
    }
}

private extension ReaderThemeChoice {
    var pdfBackgroundHex: String {
        switch self {
        case .light: return "F1F4F7"
        case .sepia: return "E9E1D2"
        case .coolGray: return "DCE5EC"
        case .dark: return "111820"
        }
    }
}

private extension ReadingAnnotationColor {
    var pdfColor: UIColor {
        switch self {
        case .yellow: return UIColor.systemYellow.withAlphaComponent(0.46)
        case .blue: return UIColor.systemBlue.withAlphaComponent(0.36)
        case .mint: return UIColor.systemMint.withAlphaComponent(0.40)
        case .pink: return UIColor.systemPink.withAlphaComponent(0.34)
        case .purple: return UIColor.systemPurple.withAlphaComponent(0.32)
        }
    }
}
