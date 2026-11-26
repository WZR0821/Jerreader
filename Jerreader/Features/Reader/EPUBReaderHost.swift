import SwiftUI
import UIKit
@preconcurrency import WebKit
@preconcurrency import ReadiumNavigator
@preconcurrency import ReadiumShared

/// Keeps the KVO tokens that watch one spread's scroll view alive for as long as
/// the scroll view itself. `NSMapTable` needs a class to hold the observations
/// and the pending page-alignment task as one lifetime.
private final class ReaderScrollViewObservations {
    let offset: NSKeyValueObservation
    let size: NSKeyValueObservation
    let paging: NSKeyValueObservation
    var pageAlignmentTask: Task<Void, Never>?
    var wasUserPaging = false

    init(offset: NSKeyValueObservation, size: NSKeyValueObservation, paging: NSKeyValueObservation) {
        self.offset = offset
        self.size = size
        self.paging = paging
    }

    deinit { pageAlignmentTask?.cancel() }
}

@MainActor
final class EPUBReaderViewController: UIViewController, EPUBNavigatorDelegate,
    PublicationReaderController, UIGestureRecognizerDelegate
{
    let navigator: EPUBNavigatorViewController
    private let publication: Publication

    var onLocationChange: ((Locator) -> Void)?
    var onSelectedText: ((ReaderSelectionPayload) -> Void)?
    var onSelectionFrameChange: ((CGRect?) -> Void)?
    var onContentTap: (() -> Void)?
    var onReaderError: ((String) -> Void)?
    var onReaderActivityChange: ((String?) -> Void)?
    var onPaginationModeChange: ((Bool) -> Void)?
    var onAnnotationActivated: ((UUID) -> Void)?

    private let isFixedLayout: Bool
    private let bookLanguage: String?
    private let publicationLayout: ReaderPublicationLayout
    private var submittedPreferences: EPUBPreferences
    private var appliedTextOrientation: ReaderTextOrientationChoice
    private var appliedReadingMode: ReaderReadingMode
    private var needsOrientationReplayOnAppearance = false
    private var orientationReplayTask: Task<Void, Never>?
    private(set) var isPaginated: Bool
    let supportsQuickSentenceTranslation = true
    private var isQuickSentenceTranslationEnabled = false
    private var quickTranslationUnit = ReaderQuickTranslationUnit.sentence
    private var disablesTapPageTurnsDuringQuickTranslation = true
    private var pendingSelectionTask: Task<Void, Never>?
    private var pendingSelectionRequestID: UUID?
    private var selectionLocationValidationTask: Task<Void, Never>?
    private var selectionLocationValidationID: UUID?
    private var nativeSelectionFallbackTask: Task<Void, Never>?
    private weak var nativeSelectionFallbackGesture: UILongPressGestureRecognizer?
    private var nativeSelectionFallbackPoint: CGPoint?
    private var nativeSelectionFallbackStartSequence = 0
    private var nativeSelectionCallbackSequence = 0
    private var quickSentenceTask: Task<Void, Never>?
    private var quickSentenceRequestID: UUID?
    private var quickSentenceHighlightToken: String?
    private var quickSentenceSpeechSequence = 0
    private var lastDeliveredSelectionSignature: String?
    private var selectionIntentResolver = ReaderSelectionIntentResolver()
    private var selectionGestureGate = EPUBSelectionGestureGate()
    private let selectionBridge = EPUBSelectionBridge()
    private var selectionVisualPalette = ReaderSelectionVisualPalette.light
    private let selectionPolicyAppliedWebViews = NSHashTable<WKWebView>.weakObjects()
    private let writingModePolicyModes =
        NSMapTable<WKWebView, NSString>.weakToStrongObjects()
    private let verticalPaginationPolicyModes =
        NSMapTable<WKWebView, NSString>.weakToStrongObjects()
    private let verticalOffsetObservations =
        NSMapTable<UIScrollView, ReaderScrollViewObservations>.weakToStrongObjects()
    private static let annotationDecorationGroup = "jerreader-reading-annotations"
#if DEBUG
    private var selectionUITestTargetView: UIView?
    private var selectionUITestDiagnosticView: UIView?
    private var layoutUITestDiagnosticView: UIView?
    private var translationUITestSelectionView: UIView?
    private var translationUITestCardView: UIView?
    private var layoutUITestDiagnosticSequence = 0
    private var rubyRegionUITestView: UIView?
    private var rubyRegionUITestTask: Task<Void, Never>?
    private var rubyRegionUITestStart: CFTimeInterval = 0
    private var rubyRegionUITestOffsets: [[Double]] = []
#endif
    private let navigationAdapter = DirectionalNavigationAdapter(
        pointerPolicy: .init(
            types: [.touch, .mouse],
            edges: .horizontal,
            ignoreWhileScrolling: true,
            minimumHorizontalEdgeSize: 84,
            horizontalEdgeThresholdPercent: 0.22
        ),
        animatedTransition: false
    )

    var currentLocator: Locator? {
        navigator.currentLocation
    }

    init(
        publication: Publication,
        initialLocation: Locator?,
        fontSize: Double,
        theme: ReaderThemeChoice,
        fontChoice: ReaderFontChoice,
        lineHeight: Double,
        paragraphSpacing: Double,
        pageMargins: Double,
        textOrientation: ReaderTextOrientationChoice,
        publicationLayout: ReaderPublicationLayout = .horizontalLTR,
        readingMode: ReaderReadingMode = .paginated,
        customBackgroundHex: String = "",
        customSelectionColorHex: String = "",
        bookLanguage: String?
    ) throws {
        self.publication = publication
        isFixedLayout = publication.metadata.layout == .fixed
        self.bookLanguage = bookLanguage
        self.publicationLayout = publicationLayout
        let preferences = Self.preferences(
            fontSize: fontSize,
            theme: theme,
            fontChoice: fontChoice,
            lineHeight: lineHeight,
            paragraphSpacing: paragraphSpacing,
            pageMargins: pageMargins,
            textOrientation: textOrientation,
            publicationLayout: publicationLayout,
            readingMode: readingMode,
            customBackgroundHex: customBackgroundHex
        )
        submittedPreferences = preferences
        appliedTextOrientation = textOrientation
        appliedReadingMode = readingMode
        navigator = try EPUBNavigatorViewController(
            publication: publication,
            initialLocation: initialLocation,
            config: .init(
                preferences: preferences,
                editingActions: [.copy, .lookup, .share],
                disablePageTurnsWhileScrolling: true
            )
        )
        isPaginated = !navigator.settings.scroll
        super.init(nibName: nil, bundle: nil)
        isPaginated = resolvedIsPaginated

        selectionVisualPalette = ReaderSelectionVisualStyle.palette(
            theme: theme,
            customBackgroundHex: customBackgroundHex,
            customSelectionColorHex: customSelectionColorHex
        )
        selectionBridge.updateVisualStyle(
            theme: theme,
            customBackgroundHex: customBackgroundHex,
            customSelectionColorHex: customSelectionColorHex
        )
        selectionBridge.updateWritingMode(
            vertical: Self.resolvedVerticalText(
                textOrientation: textOrientation,
                publicationLayout: publicationLayout
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
            navigator.view.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        navigator.didMove(toParent: self)
        selectionBridge.attach(to: view)
        installNativeSelectionFallbackGesture()
        view.clipsToBounds = true
        navigator.view.clipsToBounds = true
        // This observer is deliberately registered before Readium's page-turn
        // adapter. When the user reserves taps for quick translation, consuming
        // the activation here sends a cancel phase to the adapter, so one touch
        // can never both translate and turn the page. Swipe pagination remains
        // owned by the navigator and is unaffected.
        navigator.addObserver(.tap { [weak self] event in
            await self?.handlePageTurnActivation(at: event.location) ?? false
        })
        navigator.addObserver(.click { [weak self] event in
            await self?.handlePageTurnActivation(at: event.location) ?? false
        })
        navigator.observeDecorationInteractions(
            inGroup: Self.annotationDecorationGroup
        ) { [weak self] event in
            guard let id = UUID(uuidString: event.decoration.id) else { return }
            self?.onAnnotationActivated?(id)
        }
        navigationAdapter.bind(to: navigator)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        configureViewport()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if needsOrientationReplayOnAppearance {
            needsOrientationReplayOnAppearance = false
            scheduleOrientationReplay()
        }
        reportPaginationMode()
    }

    deinit {
        orientationReplayTask?.cancel()
        selectionLocationValidationTask?.cancel()
        nativeSelectionFallbackTask?.cancel()
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
        selectionVisualPalette = ReaderSelectionVisualStyle.palette(
            theme: theme,
            customBackgroundHex: customBackgroundHex,
            customSelectionColorHex: customSelectionColorHex
        )
        selectionBridge.updateVisualStyle(
            theme: theme,
            customBackgroundHex: customBackgroundHex,
            customSelectionColorHex: customSelectionColorHex
        )
        selectionBridge.updateWritingMode(
            vertical: Self.resolvedVerticalText(
                textOrientation: textOrientation,
                publicationLayout: publicationLayout
            )
        )
        let preferences = Self.preferences(
            fontSize: fontSize,
            theme: theme,
            fontChoice: fontChoice,
            lineHeight: lineHeight,
            paragraphSpacing: paragraphSpacing,
            pageMargins: pageMargins,
            textOrientation: textOrientation,
            publicationLayout: publicationLayout,
            readingMode: readingMode,
            customBackgroundHex: customBackgroundHex
        )
        let orientationChanged = appliedTextOrientation != textOrientation
        submittedPreferences = preferences
        appliedTextOrientation = textOrientation
        appliedReadingMode = readingMode
        verticalPaginationPolicyModes.removeAllObjects()
        if orientationChanged {
            // Publish before submitting: Readium's invalidation reloads the
            // spine item, and the reloaded document must already see the new
            // writing mode at document-start.
            publishWritingModePreference()
        }
        navigator.submitPreferences(preferences)
        enforceWritingModePolicy()
        enforceVerticalPaginationPolicy()
#if DEBUG
        updateLayoutUITestDiagnosticAfterSettling()
#endif

        if orientationChanged {
            // A writing-mode change invalidates Readium's pagination and
            // reloads the current spine item. Verify the live document after
            // that reload; metadata-poor Japanese books can otherwise leave
            // the navigator settings and the actual WebKit writing mode out of
            // sync.
            needsOrientationReplayOnAppearance = true
            scheduleOrientationReplay()
        }
        isPaginated = resolvedIsPaginated
        reportPaginationMode()
        view.setNeedsLayout()
    }


    /// Readium refuses to paginate vertical text.
    ///
    /// `EPUBSettings` contains an unconditional `if verticalText { scroll = true }`,
    /// because ReadiumCSS columns do not lay out vertical writing correctly
    /// (readium/swift-toolkit#370). The consequence is that a Japanese book read
    /// in its original layout silently ignored the 逐页 setting and always
    /// scrolled — the reader had no way to turn one page at a time.
    ///
    /// The navigator keeps its vertical scroll pipeline, with native page
    /// snapping maintained after asynchronous settings updates. A WebKit-only
    /// CSS policy fragments Readium's vertical layout at complete page bounds
    /// instead of cutting continuous text columns at arbitrary pixel offsets.
    private var usesVerticalPageSnapping: Bool {
        !isFixedLayout && appliedReadingMode == .paginated && rendersVerticalText
    }

    /// Whether the page currently lays out as `vertical-rl`, which is what
    /// decides the scroll axis: vertical text overflows horizontally and is
    /// always exactly one viewport tall.
    private var rendersVerticalText: Bool {
        Self.resolvedVerticalText(
            textOrientation: appliedTextOrientation,
            publicationLayout: publicationLayout
        )
    }

    /// True when the reader should offer page-turn affordances.
    ///
    /// Readium's own answer (`!settings.scroll`) is false for every vertical
    /// book because of the override above, which is what disabled the tap zones
    /// and the page buttons there.
    private var resolvedIsPaginated: Bool {
        !navigator.settings.scroll || usesVerticalPageSnapping
    }

    private func scheduleOrientationReplay() {
        orientationReplayTask?.cancel()
        orientationReplayTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(420))
            guard !Task.isCancelled, let self else { return }
            await convergeSubmittedWritingModeIfNeeded()
        }
    }

    /// Readium 3.8's reflow invalidation can complete with the requested
    /// settings while the current WKWebView still displays a previously cached
    /// HTML response. The pinned upstream cache fix evicts Readium's transformed
    /// HTML resources; this final live-DOM check handles WebKit's response cache
    /// without changing the publication content or selection implementation.
    private func convergeSubmittedWritingModeIfNeeded() async {
        guard let expectsVerticalText = submittedPreferences.verticalText else {
            return
        }

        // Give the normal Readium invalidation path priority. A missing WebView
        // means the spread is between old and new instances, not that it failed.
        var lastLoadedVariant: Bool?
        for _ in 0 ..< 20 {
            guard !Task.isCancelled else { return }
            lastLoadedVariant = await currentDocumentServesVerticalCSS()
            if let servesVertical = lastLoadedVariant {
                if servesVertical == expectsVerticalText {
                    return
                }
            }
            try? await Task.sleep(for: .milliseconds(100))
        }
        // A missing CSS variant is a document still loading, including the
        // about:blank WebView Readium has just created for a changed spread.
        // Reloading that placeholder cancels Readium's pending navigation and
        // strands its activity indicator. Only recover a fully loaded, stale
        // document after the normal invalidation has had time to finish.
        guard lastLoadedVariant != nil else { return }

        let savedLocation = navigator.currentLocation
        for _ in 0 ..< 2 {
            guard !Task.isCancelled else { return }

            // Submitting the target again invokes Readium's official HTML-cache
            // eviction. `reloadFromOrigin` then prevents the current WebKit
            // instance from reusing its own stale response.
            let webViews = visibleContentWebViews().filter {
                !$0.isLoading && $0.url != nil && $0.url?.scheme != "about"
            }
            guard !webViews.isEmpty else { return }
            replaySubmittedPreferences()
            webViews.forEach { $0.reloadFromOrigin() }

            for _ in 0 ..< 35 {
                try? await Task.sleep(for: .milliseconds(100))
                guard !Task.isCancelled else { return }
                guard let servesVertical = await currentDocumentServesVerticalCSS()
                else { continue }
                if servesVertical == expectsVerticalText {
                    enforceWritingModePolicy()
                    if let savedLocation {
                        for _ in 0 ..< 20 {
                            if await navigator.go(
                                to: savedLocation,
                                options: .none
                            ) {
                                break
                            }
                            try? await Task.sleep(for: .milliseconds(50))
                        }
                    }
#if DEBUG
                    updateLayoutUITestDiagnosticAfterSettling()
#endif
                    return
                }
            }
        }
    }

    private func currentDocumentWritingMode() async -> String? {
        for webView in visibleContentWebViews() {
            if let value = try? await webView.evaluateJavaScript(
                """
                (() => {
                  if (document.readyState !== 'complete') return null;
                  if (!document.querySelector('link[href*="ReadiumCSS"]')) return null;
                  return getComputedStyle(document.documentElement).writingMode;
                })()
                """
            ) as? String,
            !value.isEmpty
            {
                return value
            }
        }
        return nil
    }

    /// Reports which Readium CSS variant the currently served HTML links.
    ///
    /// The computed writing mode can no longer answer this: the writing-mode
    /// policy pins the requested mode on the document, so it always matches.
    /// The variant folder in the injected stylesheet links is the honest
    /// signal for whether Readium's own reload actually produced the layout
    /// module — vertical and horizontal pagination differ — that the submitted
    /// preferences asked for.
    private func currentDocumentServesVerticalCSS() async -> Bool? {
        for webView in visibleContentWebViews() {
            if let value = try? await webView.evaluateJavaScript(
                """
                (() => {
                  if (document.readyState !== 'complete') return null;
                  const links = Array.from(
                    document.querySelectorAll('link[rel="stylesheet"]')
                  ).map((link) => link.getAttribute('href') || '');
                  if (!links.some((href) => href.includes('ReadiumCSS'))) {
                    return null;
                  }
                  return links.some((href) => href.includes('/cjk-vertical/'));
                })()
                """
            ) as? Bool {
                return value
            }
        }
        return nil
    }

    private func replaySubmittedPreferences() {
        navigator.submitPreferences(submittedPreferences)
        isPaginated = resolvedIsPaginated
        reportPaginationMode()
        view.setNeedsLayout()
#if DEBUG
        updateLayoutUITestDiagnosticAfterSettling()
#endif
    }

    func navigateBackward() {
        if stepVerticalPage(forward: false) { return }
        Task { await navigator.goBackward(options: .animated) }
    }

    func navigateForward() {
        if stepVerticalPage(forward: true) { return }
        Task { await navigator.goForward(options: .animated) }
    }

    /// Turns one page inside a vertical spread.
    ///
    /// `EPUBReflowableSpreadView.go(to:)` steps by a viewport only when it is
    /// paginated; in scroll mode — which is every vertical book, because Readium
    /// forces it — it falls through to the superclass and jumps to the next
    /// *spine item*. That is why a page-turn tap in a Japanese vertical book
    /// skipped a whole chapter instead of turning a page.
    ///
    /// Returns false at the edges of the spread so the caller still hands over
    /// to Readium, which is what moves between chapters.
    @discardableResult
    private func stepVerticalPage(forward: Bool) -> Bool {
        guard usesVerticalPageSnapping,
              let scrollView = visibleContentWebViews().first?.scrollView
        else { return false }

        let width = scrollView.bounds.width
        guard width > 1 else { return false }

        // `vertical-rl` begins at the right edge and advances leftwards, so for
        // a right-to-left publication "forward" means a decreasing x offset.
        let readsRightToLeft = navigator.settings.readingProgression == .rtl
        let maximumOffset = max(scrollView.contentSize.width - width, 0)
        let distance = readsRightToLeft
            ? maximumOffset - scrollView.contentOffset.x : scrollView.contentOffset.x
        let page = distance / width
        let nextDistance = forward
            ? min((floor(page + 0.001) + 1) * width, maximumOffset)
            : max((ceil(page - 0.001) - 1) * width, 0)
        let target = readsRightToLeft ? maximumOffset - nextDistance : nextDistance
        // A chapter rarely fills an exact number of screens. Its partial last
        // page is still a page; only leave the resource once its edge is reached.
        guard abs(target - scrollView.contentOffset.x) > 0.5 else { return false }

        scrollView.setContentOffset(
            CGPoint(x: target, y: scrollView.contentOffset.y),
            animated: true
        )
        return true
    }

    private func handlePageTurnActivation(at point: CGPoint) async -> Bool {
        if suppressesTapPageTurns { return true }
        guard usesVerticalPageSnapping, isPageTurnTap(point) else { return false }
        guard pendingSelectionRequestID == nil,
              !selectionGestureGate.shouldSuppressTap(at: ProcessInfo.processInfo.systemUptime)
        else { return true }
        let leftEdge = point.x < navigator.view.bounds.midX
        let forward = leftEdge == (navigator.settings.readingProgression == .rtl)
        if stepVerticalPage(forward: forward) { return true }
        // Readium's default adapter ignores taps while its settings say scroll.
        // In our vertical page mode only chapter transitions are handed to it.
        if forward { await navigator.goForward(options: .animated) }
        else { await navigator.goBackward(options: .animated) }
        return true
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

        let iterator: any SearchIterator
        switch await publication.search(query: normalized) {
        case let .success(value):
            iterator = value
        case .failure(.publicationNotSearchable):
            throw ReaderSearchError.unavailable
        case .failure:
            throw ReaderSearchError.failed
        }
        var results: [Locator] = []
        while true {
            try Task.checkCancellation()
            switch await iterator.next() {
            case let .success(collection):
                guard let collection else { return results }
                results.append(contentsOf: collection.locators)
            case .failure:
                throw ReaderSearchError.failed
            }
        }
    }

    func crossPageContext(for sourceText: String) async -> String? {
        let source = TranslationCacheStore.normalizedText(sourceText)
        guard !source.isEmpty else { return nil }
        let script = Self.crossPageDocumentContextScript(sourceText: source)

        for webView in visibleContentWebViews() {
            guard let rawValue = try? await webView.evaluateJavaScript(script),
                  let rawContext = rawValue as? String
            else { continue }
            let context = TranslationCacheStore.normalizedText(rawContext)
            if context.count > source.count,
               context.range(of: source) != nil
            {
                return context
            }
        }
        return nil
    }

    func clearSelection() {
        selectionLocationValidationTask?.cancel()
        selectionLocationValidationTask = nil
        selectionLocationValidationID = nil
        nativeSelectionFallbackTask?.cancel()
        nativeSelectionFallbackTask = nil
        nativeSelectionFallbackPoint = nil
        pendingSelectionTask?.cancel()
        pendingSelectionTask = nil
        pendingSelectionRequestID = nil
        quickSentenceTask?.cancel()
        quickSentenceTask = nil
        quickSentenceRequestID = nil
        let highlightToken = quickSentenceHighlightToken
        quickSentenceHighlightToken = nil
        lastDeliveredSelectionSignature = nil
        selectionIntentResolver.reset()
        selectionGestureGate.reset()
        onSelectionFrameChange?(nil)
        selectionBridge.clear()
        navigator.clearSelection()
        if let highlightToken {
            Task { [weak self] in
                await self?.clearQuickSentenceHighlight(matching: highlightToken)
            }
        }
    }

    func updateSpeechHighlight(_ highlight: ReaderSpeechHighlight?) {
        selectionBridge.updateSpeechHighlight(highlight)
        guard let requestToken = quickSentenceHighlightToken else { return }

        quickSentenceSpeechSequence += 1
        let sequence = quickSentenceSpeechSequence
        Task { @MainActor [weak self] in
            guard let self else { return }
            let didUpdate = await self.updateQuickSentenceSpeechHighlight(
                highlight,
                matching: requestToken,
                sequence: sequence
            )
#if DEBUG
            guard self.quickSentenceSpeechSequence == sequence else { return }
            self.updateSelectionUITestDiagnostic(
                didUpdate
                    ? (highlight == nil ? "speech-stopped" : "speech-highlight")
                    : "speech-highlight-missing"
            )
#endif
        }
    }

    func applyAnnotations(_ annotations: [ReaderAnnotationPresentation]) {
        let decorations = annotations.compactMap { annotation -> Decoration? in
            guard let locator = try? Locator(jsonString: annotation.locatorJSON) else {
                return nil
            }
            return Decoration(
                id: annotation.id.uuidString,
                locator: locator,
                style: .highlight(tint: annotation.color.uiColor)
            )
        }
        navigator.apply(
            decorations: decorations,
            in: Self.annotationDecorationGroup
        )
    }

    func setQuickSentenceTranslationEnabled(_ enabled: Bool) {
        guard isQuickSentenceTranslationEnabled != enabled else { return }
        isQuickSentenceTranslationEnabled = enabled
        guard !enabled else { return }

        quickSentenceTask?.cancel()
        quickSentenceTask = nil
        quickSentenceRequestID = nil
        let highlightToken = quickSentenceHighlightToken
        quickSentenceHighlightToken = nil
        if let highlightToken {
            Task { [weak self] in
                await self?.clearQuickSentenceHighlight(matching: highlightToken)
            }
        }
    }

    func setQuickTranslationUnit(_ unit: ReaderQuickTranslationUnit) {
        guard quickTranslationUnit != unit else { return }
        quickTranslationUnit = unit
        quickSentenceTask?.cancel()
        quickSentenceTask = nil
        quickSentenceRequestID = nil
        let highlightToken = quickSentenceHighlightToken
        quickSentenceHighlightToken = nil
        if let highlightToken {
            Task { [weak self] in
                await self?.clearQuickSentenceHighlight(matching: highlightToken)
            }
        }
    }

    func setQuickSentenceTapPageTurnsDisabled(_ disabled: Bool) {
        disablesTapPageTurnsDuringQuickTranslation = disabled
    }

    func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
        let shouldPreserveNativeSelection = pendingSelectionRequestID != nil
            || lastDeliveredSelectionSignature != nil
            || !selectionBridge.displayedRects.isEmpty

        if shouldPreserveNativeSelection {
            scheduleSelectionValidationAfterLocationChange()
        } else {
            resetNativeSelectionTracking()
        }
        quickSentenceTask?.cancel()
        quickSentenceTask = nil
        quickSentenceRequestID = nil
        if let highlightToken = quickSentenceHighlightToken {
            quickSentenceHighlightToken = nil
            Task { [weak self] in
                await self?.clearQuickSentenceHighlight(matching: highlightToken)
            }
        }

        onLocationChange?(locator)
        isPaginated = self.resolvedIsPaginated
        reportPaginationMode()
        view.setNeedsLayout()
#if DEBUG
        updateLayoutUITestDiagnosticAfterSettling()
#endif
    }

    /// Readium can publish a final locator update while WebKit is still
    /// settling the selection handles. Clearing immediately in
    /// `locationDidChange` makes a valid kanji highlight flash and disappear.
    /// Re-check the visible page after layout: keep and reproject a live Range,
    /// but clear it once a real page turn moves that Range off-screen.
    private func scheduleSelectionValidationAfterLocationChange() {
        selectionLocationValidationTask?.cancel()
        let validationID = UUID()
        selectionLocationValidationID = validationID
        selectionLocationValidationTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(for: .milliseconds(180))
            } catch {
                return
            }
            guard let self,
                  !Task.isCancelled,
                  self.selectionLocationValidationID == validationID
            else { return }

            self.selectionLocationValidationTask = nil
            self.selectionLocationValidationID = nil
            if let candidate = await self.nativeSelectionSnapshot(at: nil) {
                let frame = self.selectionBridge.display(
                    snapshot: candidate.snapshot,
                    from: candidate.webView
                )
                self.onSelectionFrameChange?(frame)
            } else if self.pendingSelectionRequestID == nil,
                      self.lastDeliveredSelectionSignature == nil {
                // A delivered selection belongs to the visible translation
                // card and must survive a transient loss of WebKit's native
                // Range (for example while the system resolves selection
                // handles). Real navigation dismisses the card through the
                // view model, which calls clearSelection() explicitly.
                self.resetNativeSelectionTracking()
            }
        }
    }

    private func resetNativeSelectionTracking() {
        selectionLocationValidationTask?.cancel()
        selectionLocationValidationTask = nil
        selectionLocationValidationID = nil
        nativeSelectionFallbackTask?.cancel()
        nativeSelectionFallbackTask = nil
        nativeSelectionFallbackPoint = nil
        pendingSelectionTask?.cancel()
        pendingSelectionTask = nil
        pendingSelectionRequestID = nil
        lastDeliveredSelectionSignature = nil
        selectionIntentResolver.reset()
        selectionGestureGate.reset()
        onSelectionFrameChange?(nil)
        selectionBridge.clear()
    }

    /// The SwiftUI container already keeps the navigator out of the safe area
    /// and, in the standard layout, from under the bars. Readium's default
    /// would add the window's safe area again (62pt a side on a phone for
    /// reflowable books), which was most of the empty band above and below
    /// the text, so it only gets the small gap from `ReaderInterfaceDefaults`.
    ///
    /// 2.0.1: that gap follows 上下边距. Fixed-layout pages are fitted to the
    /// screen, so they keep the plain one.
    func navigatorContentInset(_ navigator: VisualNavigator) -> UIEdgeInsets? {
        let inset = isFixedLayout
            ? ReaderInterfaceDefaults.contentEdgeInset
            : ReaderInterfaceDefaults.pageEdgeInset(
                verticalMargins: ReaderInterfaceDefaults.verticalMargins()
            )
        return UIEdgeInsets(top: inset, left: 0, bottom: inset, right: 0)
    }

    /// Readium asks for the inset when a document loads and when the safe
    /// area or the traits change — not when a preference does. Its spread
    /// view re-reads it from `safeAreaInsetsDidChange()`, so that is the one
    /// public hook that applies a new 上下边距 to the page already on screen
    /// without reloading it.
    func refreshContentInsets() {
        guard !isFixedLayout else { return }
        for webView in navigator.view.descendantWebViews {
            webView.superview?.safeAreaInsetsDidChange()
        }
        navigator.view.setNeedsLayout()
        enforceVerticalPaginationPolicy()
#if DEBUG
        updateLayoutUITestDiagnosticAfterSettling()
#endif
    }

    func navigator(_ navigator: VisualNavigator, didTapAt point: CGPoint) {
        // A long press can be followed by a synthetic tap callback when the
        // finger is released. Never let that trailing callback enter the quick
        // sentence path and erase the base-kanji highlight we just created.
        let uptime = ProcessInfo.processInfo.systemUptime
        if pendingSelectionRequestID != nil
            || selectionGestureGate.shouldSuppressTap(at: uptime) {
            return
        }

        guard isQuickSentenceTranslationEnabled else {
            // Edge taps are reserved for Readium's page-turn adapter. Keeping
            // this callback silent avoids also toggling the reader controls.
            guard !isPageTurnTap(point) else { return }
            // With quick translation off a tap on a word looks it up, as on
            // Android; a tap that lands on no word still toggles the controls.
            startQuickHit(at: point, granularity: .word)
            return
        }

        // With the compatibility option off, the edge remains a dedicated page
        // zone. With it on (the default), the observer installed before the
        // adapter consumes this same activation and text at any x-position can
        // safely participate in the sentence hit-test below.
        guard suppressesTapPageTurns || !isPageTurnTap(point) else { return }

        startQuickHit(at: point, granularity: .translationUnit)
    }

    private enum QuickHitGranularity {
        /// The sentence or paragraph chosen in the quick-translation settings.
        case translationUnit
        /// The whole word under the finger, for lookup.
        case word
    }

    private func startQuickHit(at point: CGPoint, granularity: QuickHitGranularity) {
        resetNativeSelectionTracking()
        self.navigator.clearSelection()

        quickSentenceTask?.cancel()
        let requestID = UUID()
        quickSentenceRequestID = requestID
        quickSentenceTask = Task { @MainActor [weak self] in
            guard let self else { return }
            let payload = await self.quickSentencePayload(
                at: point,
                requestID: requestID,
                granularity: granularity
            )
            guard !Task.isCancelled,
                  self.quickSentenceRequestID == requestID
            else { return }

            self.quickSentenceTask = nil
            self.quickSentenceRequestID = nil
            if let payload {
                self.onSelectedText?(payload)
            } else {
                self.onContentTap?()
            }
        }
    }

    func navigator(_ navigator: Navigator, presentError error: NavigatorError) {
        switch error {
        case .copyForbidden:
            onReaderError?("这本书不允许复制所选文字。")
        }
    }

    func navigator(
        _ navigator: EPUBNavigatorViewController,
        setupUserScripts userContentController: WKUserContentController
    ) {
        // Readium calls this before each content WebView starts loading. This
        // is the only reliable injection point for the first spine item and
        // for replacement WebViews created after rotation or pagination.
        // This controller belongs to Readium's spread view and already carries
        // Readium's own runtime script. Only ever *add* to it: removing
        // scripts here would also delete `readium-reflowable`, taking native
        // selection callbacks, decorations and gestures down with it.
        for source in [
            Self.selectionPolicyScript(detachRubyAnnotations: true),
            Self.writingModePolicyScript(
                forcesHorizontal: appliedTextOrientation == .horizontal
            ),
            Self.verticalPaginationPolicyScript(enabled: usesVerticalPageSnapping)
        ] {
            userContentController.addUserScript(
                WKUserScript(
                    source: source,
                    injectionTime: .atDocumentStart,
                    forMainFrameOnly: false
                )
            )
        }
    }

    /// Publishes the current orientation for documents that have not loaded
    /// yet.
    ///
    /// The document-start script bakes in whatever mode was active when its
    /// WebView was created, which goes stale as soon as the user switches. The
    /// script therefore prefers this value, stored per origin — Readium serves
    /// every spine item from the same local origin, so a document loading
    /// after a switch reads the new mode before its first layout pass.
    private func publishWritingModePreference() {
        let script = Self.writingModePolicyScript(
            forcesHorizontal: appliedTextOrientation == .horizontal,
            authoritative: true
        )
        writingModePolicyModes.removeAllObjects()
        for webView in navigator.view.descendantWebViews {
            Task { @MainActor [weak webView] in
                _ = try? await webView?.evaluateJavaScript(script)
            }
        }
    }

    /// Applies the policy to documents that are already loaded, so the switch
    /// is visible immediately instead of only after Readium's reload lands.
    ///
    /// The per-WebView bookkeeping matters: this runs from every layout pass,
    /// and re-evaluating on each one would spawn a WebKit round trip per
    /// spread while the user is simply turning pages.
    private func enforceWritingModePolicy() {
        let forcesHorizontal = appliedTextOrientation == .horizontal
        let mode = forcesHorizontal ? "horizontal" : "publication"
        let script = Self.writingModePolicyScript(
            forcesHorizontal: forcesHorizontal,
            authoritative: true
        )
        for webView in navigator.view.descendantWebViews {
            guard writingModePolicyModes.object(forKey: webView) as String?
                != mode
            else { continue }
            writingModePolicyModes.setObject(mode as NSString, forKey: webView)
            Task { @MainActor [weak self, weak webView] in
                guard let webView else { return }
                do {
                    _ = try await webView.evaluateJavaScript(script)
                } catch {
                    self?.writingModePolicyModes.removeObject(forKey: webView)
                }
            }
        }
    }

    private func enforceVerticalPaginationPolicy() {
        let enabled = usesVerticalPageSnapping
        let mode = enabled ? "page" : "scroll"
        let script = Self.verticalPaginationPolicyScript(enabled: enabled, authoritative: true)
        for webView in navigator.view.descendantWebViews {
            guard verticalPaginationPolicyModes.object(forKey: webView) as String? != mode
            else { continue }
            verticalPaginationPolicyModes.setObject(mode as NSString, forKey: webView)
            Task { @MainActor [weak self, weak webView] in
                guard let webView else { return }
                do {
                    _ = try await webView.evaluateJavaScript(script)
                } catch {
                    self?.verticalPaginationPolicyModes.removeObject(forKey: webView)
                }
            }
        }
    }

    func navigator(
        _ navigator: SelectableNavigator,
        canPerformAction action: EditingAction,
        for selection: Selection
    ) -> Bool {
        let count = selection.locator.text.highlight?.count ?? 0
        if action == .lookup {
            return (1 ... 80).contains(count)
        }
        return true
    }

    func navigator(
        _ navigator: SelectableNavigator,
        shouldShowMenuForSelection selection: Selection
    ) -> Bool {
#if DEBUG
        if ProcessInfo.processInfo.arguments.contains(
            "--jerreader-selection-force-native-callback-miss"
        ) {
            updateSelectionUITestDiagnostic("forced native callback miss")
            return false
        }
#endif
        nativeSelectionCallbackSequence &+= 1
        nativeSelectionFallbackTask?.cancel()
        nativeSelectionFallbackTask = nil
        selectionGestureGate.noteNativeSelection(
            at: ProcessInfo.processInfo.systemUptime
        )

        // Readium emits this callback repeatedly while either native handle is
        // moving. Its frame is only an immediate fallback; semantic text and
        // final geometry come from a fresh, non-mutating DOM snapshot below.
        let fallbackFrame = selectionFrameInWindow(selection.frame)
        onSelectionFrameChange?(fallbackFrame)

        quickSentenceTask?.cancel()
        quickSentenceTask = nil
        quickSentenceRequestID = nil
        if let highlightToken = quickSentenceHighlightToken {
            quickSentenceHighlightToken = nil
            Task { [weak self] in
                await self?.clearQuickSentenceHighlight(matching: highlightToken)
            }
        }

        // Do not reject an empty Readium highlight here. With `rt` excluded
        // from native selection, WebKit can briefly keep an annotation-owned
        // Range while Readium reports an empty string. The DOM snapshot below
        // is still able to resolve that Range to its matching kanji. Rejecting
        // it at this boundary was the last path that could make a physical
        // long press appear to produce no highlight at all.
        let readiumHighlight = selection.locator.text.highlight ?? ""

        let navigatorSelectionPoint = selection.frame.map {
            CGPoint(x: $0.midX, y: $0.midY)
        }
        let locatorJSON = selection.locator.readerJSONString
        let selectionLocator = selection.locator
        let readiumBefore = selection.locator.text.before
        let readiumAfter = selection.locator.text.after

        pendingSelectionTask?.cancel()
        let selectionRequestID = UUID()
        pendingSelectionRequestID = selectionRequestID

        pendingSelectionTask = Task { @MainActor [weak self] in
            guard let self,
                  !Task.isCancelled,
                  self.pendingSelectionRequestID == selectionRequestID
            else { return }

            // Paint the base text immediately. If WebKit still returns an old
            // annotation-only <rt> range, the snapshot maps it to its matching
            // kanji without changing the browser's range or native handles.
            let initialCandidate = await self.nativeSelectionSnapshot(
                at: navigatorSelectionPoint,
                pointFallbackText: readiumHighlight
            )
            var semanticHighlight = readiumHighlight
            var semanticBefore = readiumBefore
            var semanticAfter = readiumAfter
            var semanticFrame = fallbackFrame
            if let initialCandidate {
                semanticHighlight = initialCandidate.snapshot.baseText
                semanticBefore = initialCandidate.snapshot.before
                semanticAfter = initialCandidate.snapshot.after
                semanticFrame = self.selectionBridge.display(
                    snapshot: initialCandidate.snapshot,
                    from: initialCandidate.webView
                ) ?? fallbackFrame
                self.onSelectionFrameChange?(semanticFrame)
#if DEBUG
                self.updateSelectionUITestDiagnostic(
                    "initial base=\(initialCandidate.snapshot.baseText) "
                        + "local=\(initialCandidate.snapshot.localRects) "
                        + "frame=\(String(describing: semanticFrame))"
                )
#endif
            } else {
#if DEBUG
                self.updateSelectionUITestDiagnostic(
                    "initial snapshot=nil readium=\(readiumHighlight) "
                        + "point=\(String(describing: navigatorSelectionPoint))"
                )
#endif
            }

            guard !semanticHighlight.trimmingCharacters(
                in: .whitespacesAndNewlines
            ).isEmpty else {
                self.selectionBridge.clear()
                self.pendingSelectionTask = nil
                self.pendingSelectionRequestID = nil
                return
            }

            guard let resolution = self.selectionIntentResolver.resolve(
                highlight: semanticHighlight,
                before: semanticBefore,
                after: semanticAfter
            ) else {
                self.pendingSelectionTask = nil
                self.pendingSelectionRequestID = nil
                return
            }

            do {
                let delay: Duration = resolution.trigger == .smartSelection
                    ? .milliseconds(170)
                    : .milliseconds(280)
                try await Task.sleep(for: delay)
            } catch {
                return
            }
            guard !Task.isCancelled,
                  self.pendingSelectionRequestID == selectionRequestID
            else { return }

            // Long press produces intermediate ranges. Capture once more after
            // the handles settle so translation and the visible marker refer
            // to exactly the same final base-text selection.
            if let settledCandidate = await self.nativeSelectionSnapshot(
                at: navigatorSelectionPoint,
                pointFallbackText: readiumHighlight
            ) {
                semanticHighlight = settledCandidate.snapshot.baseText
                semanticBefore = settledCandidate.snapshot.before
                semanticAfter = settledCandidate.snapshot.after
                semanticFrame = self.selectionBridge.display(
                    snapshot: settledCandidate.snapshot,
                    from: settledCandidate.webView
                ) ?? semanticFrame
                self.onSelectionFrameChange?(semanticFrame)
            }

            let normalizedHighlight = TranslationCacheStore.normalizedText(
                semanticHighlight
            )
            guard !normalizedHighlight.isEmpty else { return }
            let deliveredText: String
            if resolution.trigger == .preciseSelection {
                deliveredText = normalizedHighlight
            } else {
                deliveredText = ReaderSmartSelection.resolvedText(
                    highlight: normalizedHighlight,
                    before: semanticBefore,
                    after: semanticAfter
                )
            }
            guard !deliveredText.isEmpty else { return }

            let deliveredPayload = ReaderSelectionPayload(
                text: deliveredText,
                contextText: ReaderTranslationContext.window(
                    highlight: normalizedHighlight,
                    before: semanticBefore,
                    after: semanticAfter
                ),
                locatorJSON: locatorJSON,
                annotationLocatorJSON: Self.annotationLocatorJSON(
                    base: selectionLocator,
                    highlight: normalizedHighlight,
                    before: semanticBefore,
                    after: semanticAfter
                ),
                frame: semanticFrame,
                trigger: resolution.trigger
            )
            let deliveredSignature = "\(locatorJSON ?? "")\u{1F}\(deliveredText)"
            guard !Task.isCancelled,
                  self.pendingSelectionRequestID == selectionRequestID,
                  deliveredSignature != self.lastDeliveredSelectionSignature
            else { return }
            self.lastDeliveredSelectionSignature = deliveredSignature
            self.pendingSelectionTask = nil
            self.pendingSelectionRequestID = nil
            self.onSelectedText?(deliveredPayload)
        }
        return false
    }

    private func installNativeSelectionFallbackGesture() {
        let gesture = UILongPressGestureRecognizer(
            target: self,
            action: #selector(handleNativeSelectionFallbackLongPress(_:))
        )
        gesture.minimumPressDuration = 0.42
        gesture.allowableMovement = 36
        gesture.cancelsTouchesInView = false
        gesture.delaysTouchesBegan = false
        gesture.delegate = self
        navigator.view.addGestureRecognizer(gesture)
        nativeSelectionFallbackGesture = gesture
    }

    @objc private func handleNativeSelectionFallbackLongPress(
        _ gesture: UILongPressGestureRecognizer
    ) {
        switch gesture.state {
        case .began:
            nativeSelectionFallbackTask?.cancel()
            nativeSelectionFallbackTask = nil
            nativeSelectionFallbackPoint = gesture.location(in: navigator.view)
            nativeSelectionFallbackStartSequence = nativeSelectionCallbackSequence
            selectionGestureGate.noteNativeSelection(
                at: ProcessInfo.processInfo.systemUptime
            )
        case .changed:
            nativeSelectionFallbackPoint = gesture.location(in: navigator.view)
        case .ended:
            selectionGestureGate.noteNativeSelection(
                at: ProcessInfo.processInfo.systemUptime
            )
            guard let point = nativeSelectionFallbackPoint else { return }
            let callbackSequence = nativeSelectionFallbackStartSequence
            nativeSelectionFallbackPoint = nil
            scheduleNativeSelectionFallback(
                at: point,
                callbackSequence: callbackSequence
            )
        case .cancelled, .failed:
            nativeSelectionFallbackPoint = nil
            nativeSelectionFallbackTask?.cancel()
            nativeSelectionFallbackTask = nil
        default:
            break
        }
    }

    private func scheduleNativeSelectionFallback(
        at point: CGPoint,
        callbackSequence: Int
    ) {
        nativeSelectionFallbackTask?.cancel()
        nativeSelectionFallbackTask = Task { @MainActor [weak self] in
            do {
                // Give Readium's native selection delegate one run-loop window
                // to win. This path is used only when that callback never came.
                try await Task.sleep(for: .milliseconds(120))
            } catch {
                return
            }
            guard let self,
                  !Task.isCancelled,
                  self.nativeSelectionCallbackSequence == callbackSequence,
                  self.pendingSelectionRequestID == nil
            else { return }

            guard let candidate = await self.nativeSelectionSnapshot(
                at: point,
                pointFallbackText: nil
            ),
            !Task.isCancelled,
            self.nativeSelectionCallbackSequence == callbackSequence
            else {
#if DEBUG
                self.updateSelectionUITestDiagnostic(
                    "native callback absent; point fallback=nil point=\(point)"
                )
#endif
                return
            }

            let selectedText = TranslationCacheStore.normalizedText(
                candidate.snapshot.baseText
            )
            guard !selectedText.isEmpty else { return }
            let frame = self.selectionBridge.display(
                snapshot: candidate.snapshot,
                from: candidate.webView
            )
            self.onSelectionFrameChange?(frame)

            let locatorJSON = self.currentLocator?.readerJSONString
            let signature = "\(locatorJSON ?? "")\u{1F}\(selectedText)"
            guard signature != self.lastDeliveredSelectionSignature else { return }
            self.lastDeliveredSelectionSignature = signature
            self.selectionIntentResolver.markPrecise(after: selectedText)
            self.nativeSelectionFallbackTask = nil
#if DEBUG
            self.updateSelectionUITestDiagnostic(
                "native callback absent; fallback base=\(selectedText) "
                    + "frame=\(String(describing: frame))"
            )
#endif
            self.onSelectedText?(
                ReaderSelectionPayload(
                    text: selectedText,
                    contextText: ReaderTranslationContext.window(
                        highlight: selectedText,
                        before: candidate.snapshot.before,
                        after: candidate.snapshot.after
                    ),
                    locatorJSON: locatorJSON,
                    annotationLocatorJSON: Self.annotationLocatorJSON(
                        base: self.currentLocator,
                        highlight: selectedText,
                        before: candidate.snapshot.before,
                        after: candidate.snapshot.after
                    ),
                    frame: frame,
                    trigger: .preciseSelection
                )
            )
        }
    }

    func gestureRecognizer(
        _ gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
        let includesFallback =
            gestureRecognizer === nativeSelectionFallbackGesture
                || otherGestureRecognizer === nativeSelectionFallbackGesture
        guard includesFallback else { return false }
        let otherGesture = gestureRecognizer === nativeSelectionFallbackGesture
            ? otherGestureRecognizer
            : gestureRecognizer
        return EPUBLongPressPageTurnPolicy.allowsSimultaneousRecognition(
            otherGestureIsPagePan: otherGesture is UIPanGestureRecognizer
        )
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard gestureRecognizer === nativeSelectionFallbackGesture else { return true }
        return webView(at: gestureRecognizer.location(in: navigator.view)) != nil
    }

#if DEBUG
    /// Exposes the geometry of a real furigana glyph to XCUITest while leaving
    /// the touch itself owned by the underlying WKWebView. The target is only
    /// installed for the explicit selection-test launch argument.
    func prepareSelectionUITestTarget() async {
        guard ProcessInfo.processInfo.arguments.contains("--jerreader-selection-ui-test")
        else { return }

        selectionUITestTargetView?.removeFromSuperview()
        selectionUITestTargetView = nil
        selectionUITestDiagnosticView?.removeFromSuperview()
        selectionUITestDiagnosticView = nil
        layoutUITestDiagnosticView?.removeFromSuperview()
        layoutUITestDiagnosticView = nil
        clearTranslationUITestDiagnostics()

        // A placed real book opens on a page of its own: the ruby is one
        // already on that page, and the page is never scrolled to one.
        let checksRubyRegions = ProcessInfo.processInfo.arguments
            .contains("--jerreader-ruby-region-ui-test")
        if checksRubyRegions {
            startRubyRegionUITestDiagnostic()
        }
        var rubyWebView: WKWebView?
        for _ in 0 ..< (checksRubyRegions ? 400 : 120) {
            for candidate in visibleContentWebViews() {
                let didFindRuby = try? await candidate.evaluateJavaScript(
                    checksRubyRegions
                        ? """
                        (() => {
                          \(Self.rubyRegionUITestPickScript)
                          const ruby = jerreaderUITestVisibleRuby();
                          if (!ruby) return false;
                          globalThis.__jerreaderReaderUITestRuby = ruby;
                          return true;
                        })()
                        """
                        : """
                        (() => {
                          const ruby = Array.from(document.querySelectorAll('ruby')).find(
                            (candidate) => candidate.querySelector('rt')
                          );
                          if (!ruby) return false;
                          ruby.scrollIntoView({ block: 'center', inline: 'center' });
                          globalThis.__jerreaderReaderUITestRuby = ruby;
                          return true;
                        })()
                        """
                ) as? Bool
                if didFindRuby == true {
                    rubyWebView = candidate
                    break
                }
            }
            if rubyWebView != nil { break }
            try? await Task.sleep(for: .milliseconds(25))
        }
        guard let rubyWebView else { return }

        try? await Task.sleep(for: .milliseconds(320))
        guard let rawRect = try? await rubyWebView.evaluateJavaScript(
            """
            (() => {
              const ruby = globalThis.__jerreaderReaderUITestRuby;
              if (!ruby) return null;
              const nodes = [];
              const walker = document.createTreeWalker(
                ruby,
                NodeFilter.SHOW_TEXT,
                {
                  acceptNode(node) {
                    if (!node.data?.trim() || node.parentElement?.closest('rt,rp')) {
                      return NodeFilter.FILTER_REJECT;
                    }
                    return NodeFilter.FILTER_ACCEPT;
                  }
                }
              );
              while (walker.nextNode()) nodes.push(walker.currentNode);
              const rects = nodes.flatMap((node) => {
                const range = document.createRange();
                range.selectNodeContents(node);
                return Array.from(range.getClientRects());
              }).filter((candidate) =>
                candidate.width > 0.5 && candidate.height > 0.5 &&
                candidate.right > 0 && candidate.bottom > 0 &&
                candidate.left < window.innerWidth && candidate.top < window.innerHeight
              );
              if (!rects.length) return null;
              const left = Math.min(...rects.map((candidate) => candidate.left));
              const top = Math.min(...rects.map((candidate) => candidate.top));
              const right = Math.max(...rects.map((candidate) => candidate.right));
              const bottom = Math.max(...rects.map((candidate) => candidate.bottom));
              const rect = {
                left,
                top,
                width: right - left,
                height: bottom - top
              };
              if (!rect.width || !rect.height) return null;
              return {
                x: rect.left,
                y: rect.top,
                width: rect.width,
                height: rect.height,
                text: nodes.map((node) => node.data).join('').normalize('NFC')
              };
            })()
            """
        ),
        let payload = rawRect as? [String: Any],
        let localRect = Self.rect(from: payload)
        else { return }

        let targetRect = EPUBSelectionBridge.convertViewportRect(
            localRect,
            from: rubyWebView,
            to: view
        )
            .insetBy(dx: -4, dy: -4)
            .intersection(view.bounds)
        guard !targetRect.isEmpty else { return }

        let target = UIView(frame: targetRect)
        target.backgroundColor = .clear
        target.isOpaque = false
        target.isUserInteractionEnabled = false
        target.isAccessibilityElement = true
        target.accessibilityIdentifier = "selection-ruby-target"
        target.accessibilityLabel = "真实汉字长按目标"
        target.accessibilityValue = payload["text"] as? String
        view.addSubview(target)
        selectionUITestTargetView = target

        let diagnostic = UIView(frame: CGRect(x: 0, y: 0, width: 1, height: 1))
        diagnostic.backgroundColor = .clear
        diagnostic.isUserInteractionEnabled = false
        diagnostic.isAccessibilityElement = true
        diagnostic.accessibilityIdentifier = "selection-diagnostic-status"
        diagnostic.accessibilityLabel = "选区调试状态"
        diagnostic.accessibilityValue = "ready"
        view.addSubview(diagnostic)
        selectionUITestDiagnosticView = diagnostic

        let layoutDiagnostic = UIView(
            frame: CGRect(x: 1, y: 0, width: 1, height: 1)
        )
        layoutDiagnostic.backgroundColor = .clear
        layoutDiagnostic.isUserInteractionEnabled = false
        layoutDiagnostic.isAccessibilityElement = true
        layoutDiagnostic.accessibilityIdentifier = "layout-diagnostic-status"
        layoutDiagnostic.accessibilityLabel = "排版调试状态"
        layoutDiagnostic.accessibilityValue = "pending"
        view.addSubview(layoutDiagnostic)
        layoutUITestDiagnosticView = layoutDiagnostic
        updateLayoutUITestDiagnosticAfterSettling()
    }

    func selectionUITestTargetFrameInWindow() -> CGRect? {
        guard let target = selectionUITestTargetView,
              target.window != nil
        else { return nil }
        return target.convert(target.bounds, to: nil).standardized
    }

    /// 2.1.0 §2 on a real book (`--jerreader-ruby-region-ui-test`): what a
    /// selection over furigana was painted as, kept current for the check
    /// that each kanji and its reading are one region. The value is JSON in
    /// window points:
    /// - `kind`: `quick` while a tapped sentence is highlighted, `native`
    ///   while a long-press selection is, else `none`;
    /// - `tiles`: what was painted, the page's own markers for a tapped
    ///   sentence and the selection view's tiles for a long press;
    /// - `rubies`: each kanji on the page with its reading, marked `whole`,
    ///   `partial` or `no` by the selection, or `unknown` when the
    ///   selection's range could not be read;
    /// - `text` and `lines`: the selection's base text, without readings,
    ///   and how many lines it takes (-1 without a range);
    /// - `target`: the ruby the check presses, measured now.
    private func startRubyRegionUITestDiagnostic() {
        rubyRegionUITestTask?.cancel()
        rubyRegionUITestView?.removeFromSuperview()
        let marker = UIView(frame: CGRect(x: 2, y: 0, width: 1, height: 1))
        marker.backgroundColor = .clear
        marker.isUserInteractionEnabled = false
        marker.isAccessibilityElement = true
        marker.accessibilityIdentifier = "ruby-region-geometry"
        marker.accessibilityLabel = "注音选区测试几何"
        marker.accessibilityValue = "pending"
        view.addSubview(marker)
        rubyRegionUITestView = marker
        rubyRegionUITestStart = CACurrentMediaTime()
        rubyRegionUITestOffsets = []
        rubyRegionUITestTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                guard let self, !Task.isCancelled else { return }
                let value = await self.rubyRegionUITestGeometry()
                self.rubyRegionUITestView?.accessibilityValue = value
            }
        }
    }

    private func rubyRegionUITestGeometry() async -> String {
        for webView in visibleContentWebViews() {
            guard let raw = try? await webView.evaluateJavaScript(
                Self.rubyRegionUITestGeometryScript
            ) as? String,
                let data = raw.data(using: .utf8),
                let page = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
            else { continue }
            let host: UIView = view
            func windowFrame(_ value: Any?) -> [Double]? {
                guard let numbers = value as? [NSNumber], numbers.count == 4 else { return nil }
                let local = CGRect(
                    x: numbers[0].doubleValue,
                    y: numbers[1].doubleValue,
                    width: numbers[2].doubleValue,
                    height: numbers[3].doubleValue
                )
                let inHost = EPUBSelectionBridge.convertViewportRect(local, from: webView, to: host)
                return Self.rubyRegionNumbers(host.convert(inHost, to: nil).standardized)
            }
            let markers = (page["markers"] as? [Any] ?? []).compactMap { windowFrame($0) }
            let selectionTiles = selectionBridge.displayedRects.map {
                Self.rubyRegionNumbers(host.convert($0, to: nil).standardized)
            }
            let kind = !markers.isEmpty ? "quick" : (selectionTiles.isEmpty ? "none" : "native")
            let rubies: [[String: Any]] = (page["rubies"] as? [[String: Any]] ?? []).compactMap { ruby in
                guard let kanji = windowFrame(ruby["kanji"]),
                      let reading = windowFrame(ruby["readingFrame"])
                else { return nil }
                return [
                    "base": ruby["base"] as? String ?? "",
                    "reading": ruby["reading"] as? String ?? "",
                    "kanji": kanji,
                    "readingFrame": reading,
                    "selected": ruby["selected"] as? String ?? "unknown"
                ]
            }
            var result: [String: Any] = [
                "kind": kind,
                "text": page["text"] as? String ?? "",
                "vertical": page["vertical"] as? Bool ?? false,
                "lines": (page["lines"] as? NSNumber)?.intValue ?? -1,
                "tiles": kind == "quick" ? markers : (kind == "native" ? selectionTiles : []),
                "rubies": rubies
            ]
            if let target = page["target"] as? [String: Any],
               let first = windowFrame(target["first"]),
               let whole = windowFrame(target["frame"]) {
                result["target"] = [
                    "base": target["base"] as? String ?? "",
                    "first": first,
                    "frame": whole
                ]
            }
            if let wide = page["twoKanji"] as? [String: Any],
               let whole = windowFrame(wide["frame"]) {
                result["twoKanji"] = [
                    "frame": whole,
                    "glyphs": (wide["glyphs"] as? [Any] ?? []).compactMap { windowFrame($0) }
                ]
            }
            // Where the page sat when it was measured, numbers only. The
            // selection view is drawn once in window points, so a page that
            // moves under it shows here and nowhere else.
            let scrollView = webView.scrollView
            let insets = scrollView.adjustedContentInset
            result["scroll"] = [
                scrollView.contentOffset.x, scrollView.contentOffset.y,
                scrollView.contentSize.width, scrollView.contentSize.height,
                scrollView.bounds.height, insets.top, insets.bottom
            ].map { (Double($0) * 10).rounded() / 10 }
            result["webFrame"] = Self.rubyRegionNumbers(webView.convert(webView.bounds, to: nil).standardized)
            if let numbers = page["page"] as? [NSNumber] {
                result["page"] = numbers.map { ($0.doubleValue * 10).rounded() / 10 }
            }
            result["offsets"] = rubyRegionUITestOffsets
            result["layers"] = Self.rubyRegionUITestLayers(of: webView)
            // Every web view Readium holds: window frame, shown (alpha, or 0
            // when hidden or off the window), 1 for the measured one, 1 when
            // it shows the measured one's resource, and its scroll offset.
            result["webViews"] = navigator.view.descendantWebViews.map { other -> [Double] in
                let frame = other.convert(other.bounds, to: nil).standardized
                let shown = other.isHidden || other.window == nil ? 0 : Double(other.alpha)
                let sameResource = other.url != nil && other.url == webView.url
                let numbers: [Double] = [
                    Double(frame.minX), Double(frame.minY), Double(frame.width), Double(frame.height),
                    shown,
                    other === webView ? 1 : 0,
                    sameResource ? 1 : 0,
                    Double(other.scrollView.contentOffset.x),
                    Double(other.scrollView.contentOffset.y)
                ]
                return numbers.map { ($0 * 10).rounded() / 10 }
            }
            guard let encoded = try? JSONSerialization.data(
                withJSONObject: result,
                options: [.sortedKeys]
            ),
                let string = String(data: encoded, encoding: .utf8)
            else { continue }
            return string
        }
        return "unavailable"
    }

    /// While the ruby-region check runs, the last eight vertical offsets the
    /// pin was asked to correct, each as seconds since the check began, the
    /// offset, the content and viewport heights, the top inset and 1 where
    /// the page fitted (so the pin put it back) or 0 where it did not.
    private func noteVerticalOffsetForRubyRegionUITest(_ scrollView: UIScrollView) {
        guard rubyRegionUITestView != nil, rendersVerticalText, scrollView.contentOffset.y != 0 else { return }
        let fits = scrollView.contentSize.height <= scrollView.bounds.height + 0.5
        let numbers: [Double] = [
            CACurrentMediaTime() - rubyRegionUITestStart,
            Double(scrollView.contentOffset.y),
            Double(scrollView.contentSize.height),
            Double(scrollView.bounds.height),
            Double(scrollView.adjustedContentInset.top),
            fits ? 1.0 : 0.0
        ]
        let entry = numbers.map { ($0 * 10).rounded() / 10 }
        rubyRegionUITestOffsets.append(entry)
        if rubyRegionUITestOffsets.count > 8 { rubyRegionUITestOffsets.removeFirst() }
    }

    /// How the measured page is put on screen, for the ruby-region check:
    /// lines starting `^` are the web view and its superviews up to the
    /// window (frame in the window); `snap` lines are image or snapshot
    /// views anywhere in the window over the web view; the rest walk the web
    /// view's layers (frame in the web view). Each gives its depth, class,
    /// frame, a bounds origin, a transform or sublayer transform that is not
    /// the identity (scale x, y, then translation x, y), a clip, hidden,
    /// opacity, running animations and, where the presentation layer differs
    /// from the model, where it is drawn. Layers whose children are all
    /// leaves (tiles) show their count and union instead. Class names and
    /// numbers only.
    private static func rubyRegionUITestLayers(of webView: WKWebView) -> [String] {
        var lines: [String] = []
        func text(_ value: CGFloat) -> String { String(format: "%.1f", Double(value)) }
        func rect(_ value: CGRect) -> String {
            let r = value.standardized
            return "\(text(r.minX)),\(text(r.minY)),\(text(r.width)),\(text(r.height))"
        }
        func transformText(_ t: CATransform3D) -> String? {
            CATransform3DIsIdentity(t)
                ? nil
                : "\(text(t.m11)),\(text(t.m22)),\(text(t.m41)),\(text(t.m42))"
        }
        func name(of layer: CALayer) -> String {
            if let view = layer.delegate as? UIView {
                return String(describing: type(of: view))
            }
            return "L:" + String(describing: type(of: layer))
        }
        func extras(_ layer: CALayer) -> String {
            var line = ""
            if layer.bounds.origin != .zero {
                line += " b=\(text(layer.bounds.minX)),\(text(layer.bounds.minY))"
            }
            if let t = transformText(layer.transform) { line += " t=\(t)" }
            if let t = transformText(layer.sublayerTransform) { line += " st=\(t)" }
            if layer.masksToBounds { line += " clip" }
            if layer.isHidden { line += " hidden" }
            if layer.opacity < 1 { line += " a=\(text(CGFloat(layer.opacity)))" }
            if let keys = layer.animationKeys(), !keys.isEmpty {
                line += " anim=" + keys.prefix(4).joined(separator: "|")
            }
            if let shown = layer.presentation(),
               shown.position != layer.position
                || shown.bounds != layer.bounds
                || !CATransform3DEqualToTransform(shown.transform, layer.transform) {
                line += " p=\(text(shown.position.x)),\(text(shown.position.y))"
                    + " pb=\(rect(shown.bounds))"
                if let t = transformText(shown.transform) { line += " pt=\(t)" }
                line += " m=\(text(layer.position.x)),\(text(layer.position.y))"
            }
            return line
        }

        // The web view and its superviews, nearest first.
        var ancestor: UIView? = webView
        var level = 0
        while let view = ancestor, level < 16 {
            let frame = view.superview == nil ? view.frame : view.convert(view.bounds, to: nil)
            lines.append("^\(level) \(String(describing: type(of: view))) f=\(rect(frame))"
                + extras(view.layer))
            ancestor = view.superview
            level += 1
        }

        // Image and snapshot views over the web view, anywhere in the window.
        if let window = webView.window {
            let webFrame = webView.convert(webView.bounds, to: nil)
            var queue: [UIView] = [window]
            var next = 0
            var found = 0
            while next < queue.count, next < 3000, found < 8 {
                let view = queue[next]
                next += 1
                if queue.count < 3000 { queue.append(contentsOf: view.subviews) }
                let className = String(describing: type(of: view))
                let isImage = (view as? UIImageView)?.image != nil
                guard isImage
                    || className.contains("Snapshot")
                    || className.contains("Replicant")
                    || className.contains("Portal")
                else { continue }
                let frame = view.convert(view.bounds, to: nil)
                guard frame.width * frame.height > 10_000,
                      frame.intersects(webFrame)
                else { continue }
                found += 1
                lines.append("snap \(className) f=\(rect(frame))"
                    + (view.isHidden ? " hidden" : "")
                    + (view.alpha < 1 ? " a=\(text(view.alpha))" : ""))
            }
        }

        // The web view's own layers.
        let root = webView.layer
        func walk(_ layer: CALayer, depth: Int) {
            guard lines.count < 90 else { return }
            var line = "\(depth) \(name(of: layer)) f=\(rect(layer.convert(layer.bounds, to: root)))"
                + extras(layer)
            let children = layer.sublayers ?? []
            if children.count > 3, children.allSatisfy({ ($0.sublayers ?? []).isEmpty }) {
                let union = children.reduce(CGRect.null) { $0.union($1.convert($1.bounds, to: root)) }
                let moving = children.filter { !($0.animationKeys() ?? []).isEmpty }.count
                line += " leaves=\(children.count) u=\(rect(union))"
                    + (moving > 0 ? " leavesAnimating=\(moving)" : "")
                lines.append(line)
                return
            }
            if children.count > 6 { line += " n=\(children.count)" }
            lines.append(line)
            guard depth < 14 else { return }
            for child in children.prefix(6) { walk(child, depth: depth + 1) }
        }
        walk(root, depth: 0)
        return lines
    }

    private static func rubyRegionNumbers(_ rect: CGRect) -> [Double] {
        [rect.minX, rect.minY, rect.width, rect.height].map {
            (Double($0) * 10).rounded() / 10
        }
    }

    /// Defines `jerreaderUITestVisibleRuby(minimumKanji)`: the first ruby
    /// whose base text is wholly on the page, preferring one away from the
    /// page-turn edges; with `minimumKanji`, only rubies of at least that many
    /// base characters. `jerreaderUITestBaseGlyphs(ruby)` gives each base
    /// character's box.
    nonisolated private static let rubyRegionUITestPickScript = #"""
    function jerreaderUITestBaseRects(ruby) {
      const rects = [];
      const walker = document.createTreeWalker(ruby, NodeFilter.SHOW_TEXT, {
        acceptNode: (node) => node.data?.trim() && !node.parentElement?.closest('rt,rp')
          ? NodeFilter.FILTER_ACCEPT
          : NodeFilter.FILTER_REJECT
      });
      while (walker.nextNode()) {
        const range = document.createRange();
        range.selectNodeContents(walker.currentNode);
        rects.push(...Array.from(range.getClientRects()));
      }
      return rects.filter((rect) => rect.width > 0.5 && rect.height > 0.5);
    }
    function jerreaderUITestBaseGlyphs(ruby) {
      const glyphs = [];
      const walker = document.createTreeWalker(ruby, NodeFilter.SHOW_TEXT, {
        acceptNode: (node) => node.data?.trim() && !node.parentElement?.closest('rt,rp')
          ? NodeFilter.FILTER_ACCEPT
          : NodeFilter.FILTER_REJECT
      });
      while (walker.nextNode()) {
        const node = walker.currentNode;
        for (let offset = 0; offset < node.data.length;) {
          const lead = node.data.charCodeAt(offset);
          const end = lead >= 0xD800 && lead <= 0xDBFF && offset + 1 < node.data.length ? offset + 2 : offset + 1;
          if (node.data.slice(offset, end).trim()) {
            const range = document.createRange();
            range.setStart(node, offset);
            range.setEnd(node, end);
            const rect = Array.from(range.getClientRects()).find((box) => box.width > 0.5 && box.height > 0.5);
            if (rect) glyphs.push(rect);
          }
          offset = end;
        }
      }
      return glyphs;
    }
    function jerreaderUITestOnPage(rect) {
      return rect.left >= 0 && rect.top >= 0 &&
        rect.right <= window.innerWidth && rect.bottom <= window.innerHeight;
    }
    function jerreaderUITestVisibleRuby(minimumKanji) {
      const rubies = Array.from(document.querySelectorAll('ruby')).filter((ruby) => {
        const rects = jerreaderUITestBaseRects(ruby);
        return ruby.querySelector('rt') && rects.length && rects.every(jerreaderUITestOnPage) &&
          (!(minimumKanji > 1) || jerreaderUITestBaseGlyphs(ruby).length >= minimumKanji);
      });
      const central = rubies.find((ruby) => jerreaderUITestBaseRects(ruby).every((rect) => {
        const x = (rect.left + rect.right) / 2 / window.innerWidth;
        const y = (rect.top + rect.bottom) / 2 / window.innerHeight;
        return x > 0.25 && x < 0.75 && y > 0.12 && y < 0.88;
      }));
      return central || rubies[0] || null;
    }
    """#

    /// The page's half of `ruby-region-geometry`, in client coordinates.
    nonisolated private static let rubyRegionUITestGeometryScript = #"""
    (() => {
      \#(rubyRegionUITestPickScript)
      const box = (rect) => [rect.left, rect.top, rect.width, rect.height];
      const visible = (rect) => rect.width > 0.5 && rect.height > 0.5 &&
        rect.right > 0 && rect.bottom > 0 &&
        rect.left < window.innerWidth && rect.top < window.innerHeight;
      const overlay = document.getElementById('jerreader-reader-quick-sentence-highlight');
      const markers = overlay
        ? Array.from(overlay.children)
            .map((marker) => marker.getBoundingClientRect())
            .filter(visible)
            .map(box)
        : [];

      // The selection: the tapped sentence's range, else the long press's.
      let range = null;
      const quick = globalThis.__jerreaderReaderQuickSentenceSelection;
      if (markers.length && quick && Array.isArray(quick.nodes) && quick.nodes.length) {
        const boundary = (requested) => {
          const offset = Math.max(0, requested);
          let consumed = 0;
          for (const node of quick.nodes) {
            const next = consumed + node.data.length;
            if (offset <= next) {
              return { node, offset: Math.min(offset - consumed, node.data.length) };
            }
            consumed = next;
          }
          const last = quick.nodes[quick.nodes.length - 1];
          return { node: last, offset: last.data.length };
        };
        try {
          const start = boundary(quick.lowerBound);
          const end = boundary(quick.upperBound);
          range = document.createRange();
          range.setStart(start.node, start.offset);
          range.setEnd(end.node, end.offset);
        } catch (_) {
          range = null;
        }
      } else if (!markers.length) {
        const selection = getSelection();
        if (selection && selection.rangeCount && !selection.isCollapsed) {
          range = selection.getRangeAt(0);
        }
      }

      const excluded = 'rt,rp,script,style,noscript,svg,[aria-hidden="true"]';
      const isBase = (node) => !!node?.data && !!node.parentElement &&
        !node.parentElement.closest(excluded);
      const selectedPart = (node) => {
        if (!range) return null;
        try {
          if (!range.intersectsNode(node)) return null;
        } catch (_) {
          return null;
        }
        const part = document.createRange();
        part.selectNodeContents(node);
        if (range.startContainer === node) {
          part.setStart(node, Math.min(range.startOffset, node.data.length));
        }
        if (range.endContainer === node) {
          part.setEnd(node, Math.min(range.endOffset, node.data.length));
        }
        return part.collapsed ? null : part;
      };

      let text = '';
      const baseRects = [];
      if (range) {
        const walker = document.createTreeWalker(
          document.body || document.documentElement,
          NodeFilter.SHOW_TEXT,
          {
            acceptNode: (node) => isBase(node) && selectedPart(node)
              ? NodeFilter.FILTER_ACCEPT
              : NodeFilter.FILTER_REJECT
          }
        );
        while (walker.nextNode()) {
          const part = selectedPart(walker.currentNode);
          text += part.toString();
          baseRects.push(...Array.from(part.getClientRects()).filter(visible));
        }
      }
      const anchor = range
        ? (range.startContainer.nodeType === Node.ELEMENT_NODE
            ? range.startContainer
            : range.startContainer.parentElement)
        : document.body;
      const style = anchor ? getComputedStyle(anchor) : null;
      const vertical = /^(vertical|sideways|tb)/i.test(
        (style && (style.writingMode || style.webkitWritingMode)) || ''
      );
      // Lines as the tiles group them: boxes sharing at least 55% of the
      // shorter cross extent are one line.
      const lines = [];
      baseRects.forEach((rect) => {
        const start = vertical ? rect.left : rect.top;
        const end = vertical ? rect.right : rect.bottom;
        const line = lines.find((candidate) => {
          const overlap = Math.min(end, candidate.end) - Math.max(start, candidate.start);
          const shorter = Math.min(end - start, candidate.end - candidate.start);
          return overlap > 0 && shorter > 0 && overlap / shorter >= 0.55;
        });
        if (line) {
          line.start = Math.min(line.start, start);
          line.end = Math.max(line.end, end);
        } else {
          lines.push({ start, end });
        }
      });

      // Each kanji's reading: the rt that follows it in its ruby, as the
      // reader pairs them.
      const pairedReading = (node, ruby) => {
        let top = node;
        while (top && top.parentNode && top.parentNode !== ruby) top = top.parentNode;
        for (let sibling = top?.nextSibling; sibling; sibling = sibling.nextSibling) {
          if (sibling.nodeType === Node.ELEMENT_NODE) {
            if (sibling.matches('rt')) return sibling;
            if (!sibling.matches('rp')) break;
          } else if (sibling.nodeType === Node.TEXT_NODE && sibling.data.trim()) {
            break;
          }
        }
        return ruby.querySelector('rt');
      };
      const rubies = [];
      document.querySelectorAll('ruby').forEach((ruby) => {
        const walker = document.createTreeWalker(ruby, NodeFilter.SHOW_TEXT, {
          acceptNode: (node) => isBase(node) && node.data.trim()
            ? NodeFilter.FILTER_ACCEPT
            : NodeFilter.FILTER_REJECT
        });
        while (walker.nextNode()) {
          const node = walker.currentNode;
          const reading = pairedReading(node, ruby);
          if (!reading) continue;
          const whole = document.createRange();
          whole.selectNodeContents(node);
          const kanji = whole.getBoundingClientRect();
          if (!visible(kanji)) continue;
          const part = selectedPart(node);
          let selected = range ? 'no' : 'unknown';
          if (part) {
            const lead = node.data.length - node.data.trimStart().length;
            const trail = node.data.trimEnd().length;
            selected = part.startOffset <= lead && part.endOffset >= trail
              ? 'whole'
              : 'partial';
          }
          rubies.push({
            base: node.data.trim(),
            reading: reading.getAttribute('data-jerreader-rt') || reading.textContent || '',
            kanji: box(kanji),
            readingFrame: box(reading.getBoundingClientRect()),
            selected
          });
        }
      });

      let target = null;
      const ruby = jerreaderUITestVisibleRuby();
      const rects = ruby ? jerreaderUITestBaseRects(ruby) : [];
      if (rects.length) {
        const left = Math.min(...rects.map((rect) => rect.left));
        const top = Math.min(...rects.map((rect) => rect.top));
        const right = Math.max(...rects.map((rect) => rect.right));
        const bottom = Math.max(...rects.map((rect) => rect.bottom));
        let base = '';
        const walker = document.createTreeWalker(ruby, NodeFilter.SHOW_TEXT, {
          acceptNode: (node) => isBase(node) && node.data.trim()
            ? NodeFilter.FILTER_ACCEPT
            : NodeFilter.FILTER_REJECT
        });
        while (walker.nextNode()) base += walker.currentNode.data.trim();
        target = {
          base: base.normalize('NFC'),
          first: box(rects[0]),
          frame: [left, top, right - left, bottom - top]
        };
      }
      // A ruby of two or more kanji, for a press in the middle of them: with
      // a reading longer than its first kanji that is the blank room the
      // reading leaves. Frames only, no text.
      let twoKanji = null;
      const wide = jerreaderUITestVisibleRuby(2);
      const wideRects = wide ? jerreaderUITestBaseRects(wide) : [];
      if (wideRects.length) {
        const left = Math.min(...wideRects.map((rect) => rect.left));
        const top = Math.min(...wideRects.map((rect) => rect.top));
        const right = Math.max(...wideRects.map((rect) => rect.right));
        const bottom = Math.max(...wideRects.map((rect) => rect.bottom));
        twoKanji = {
          frame: [left, top, right - left, bottom - top],
          glyphs: jerreaderUITestBaseGlyphs(wide).map(box)
        };
      }
      // Where the page sat: its scroll, viewport, height, the body's top,
      // and whether the root or the body scrolled inside itself.
      const viewport = window.visualViewport;
      const root = document.scrollingElement || document.documentElement;
      const body = document.body;
      const page = [
        window.scrollX, window.scrollY, window.innerWidth, window.innerHeight,
        viewport ? viewport.offsetTop : -1, viewport ? viewport.pageTop : -1,
        root ? root.scrollHeight : -1, root ? root.clientHeight : -1,
        body ? body.getBoundingClientRect().top : -1,
        document.documentElement.scrollTop,
        body ? body.scrollTop : -1, body ? body.scrollLeft : -1,
        body ? body.scrollHeight : -1, body ? body.clientHeight : -1
      ];
      return JSON.stringify({
        markers,
        text: text.normalize('NFC'),
        vertical,
        lines: range ? lines.length : -1,
        rubies,
        target,
        twoKanji,
        page
      });
    })()
    """#

    /// Installs a transparent, non-interactive source rectangle so XCUITest can
    /// compare the actual SwiftUI floating-window frame with the original
    /// text region. This exists only in Debug UI-test launches.
    func installTranslationUITestSelection(frameInWindow: CGRect) {
        translationUITestSelectionView?.removeFromSuperview()
        translationUITestSelectionView = nil
        guard let window = view.window else { return }
        let localFrame = window.convert(frameInWindow, from: nil).standardized
        guard !localFrame.isNull, !localFrame.isEmpty else { return }

        let marker = UIView(frame: localFrame)
        // Keep the diagnostic completely invisible. It supplies geometry to
        // UI automation but must never be mistaken for Readium's real text
        // selection or suggest that the selection rules have moved.
        marker.backgroundColor = .clear
        marker.layer.borderWidth = 0
        marker.isUserInteractionEnabled = false
        marker.isAccessibilityElement = true
        marker.accessibilityIdentifier = "translation-source-diagnostic"
        marker.accessibilityLabel = "翻译原文测试区域"
        window.addSubview(marker)
        translationUITestSelectionView = marker
    }

    /// Mirrors the actual SwiftUI card frame into UIKit accessibility so the
    /// geometry test can read it reliably. The view is transparent and cannot
    /// receive touches. The AI-bar check (2.1.0 §11) reads it too.
    func installTranslationUITestCard(frameInWindow: CGRect) {
        let arguments = ProcessInfo.processInfo.arguments
        guard arguments.contains("--jerreader-selection-ui-test")
                || arguments.contains("--jerreader-card-ai-ui-test"),
              let window = view.window else { return }
        let localFrame = window.convert(frameInWindow, from: nil).standardized
        guard !localFrame.isNull, !localFrame.isEmpty else { return }

        let marker = translationUITestCardView ?? UIView(frame: .zero)
        marker.frame = localFrame
        marker.backgroundColor = .clear
        marker.isUserInteractionEnabled = false
        marker.isAccessibilityElement = true
        marker.accessibilityIdentifier = "translation-card-diagnostic"
        marker.accessibilityLabel = "翻译浮窗测试区域"
        if marker.superview !== window {
            marker.removeFromSuperview()
            window.addSubview(marker)
        }
        translationUITestCardView = marker
    }

    func clearTranslationUITestDiagnostics() {
        translationUITestSelectionView?.removeFromSuperview()
        translationUITestSelectionView = nil
        translationUITestCardView?.removeFromSuperview()
        translationUITestCardView = nil
    }

    private func updateSelectionUITestDiagnostic(_ message: String) {
        guard ProcessInfo.processInfo.arguments.contains("--jerreader-selection-ui-test")
        else { return }
        selectionUITestDiagnosticView?.accessibilityValue = message
    }

    private func updateLayoutUITestDiagnosticAfterSettling() {
        guard ProcessInfo.processInfo.arguments.contains("--jerreader-selection-ui-test")
        else { return }
        layoutUITestDiagnosticSequence += 1
        let sequence = layoutUITestDiagnosticSequence
        Task { @MainActor [weak self] in
            // A writing-mode change rebuilds Readium's spread. During that
            // short interval there is intentionally no visible WKWebView.
            // Poll the final live DOM instead of publishing a one-shot
            // "unavailable" value which can make a successful reload look
            // like a layout failure. The sequence guard prevents an older
            // preference submission from overwriting a newer result.
            for attempt in 0 ..< 50 {
                try? await Task.sleep(
                    for: .milliseconds(attempt == 0 ? 180 : 100)
                )
                guard let self,
                      !Task.isCancelled,
                      self.layoutUITestDiagnosticSequence == sequence
                else { return }

                if let value = await self.currentDocumentWritingMode(),
                   let servesVertical = await self.currentDocumentServesVerticalCSS(),
                   servesVertical == self.rendersVerticalText {
                    guard self.layoutUITestDiagnosticSequence == sequence else { return }
                    let progression =
                        self.submittedPreferences.readingProgression?.rawValue
                            ?? "auto"
                    let mode = self.navigator.settings.scroll
                        ? "scroll"
                        : "paginated"
                    // The served Readium CSS variant is reported separately
                    // from the computed writing mode: the policy pins the
                    // latter, so only the variant shows whether Readium's own
                    // reload produced the matching pagination module.
                    let variant = servesVertical ? "cjk-vertical" : "cjk-horizontal"
                    let viewport = self.visibleContentWebViews().first?.scrollView
                    let x = viewport?.contentOffset.x ?? -1
                    let width = viewport?.bounds.width ?? 0
                    let maximum = max((viewport?.contentSize.width ?? 0) - width, 0)
                    let paging = viewport?.isPagingEnabled ?? false
                    self.layoutUITestDiagnosticView?.accessibilityValue =
                        "\(self.appliedTextOrientation.rawValue)|\(value)|\(progression)|\(mode)|\(variant)|x=\(x)|w=\(width)|max=\(maximum)|paging=\(paging)"
                    return
                }
            }

            guard let self,
                  self.layoutUITestDiagnosticSequence == sequence
            else { return }
            let progression =
                self.submittedPreferences.readingProgression?.rawValue ?? "auto"
            let mode = self.navigator.settings.scroll ? "scroll" : "paginated"
            let webView = self.visibleContentWebViews().first
            let ready = try? await webView?.evaluateJavaScript("document.readyState") as? String
            guard self.layoutUITestDiagnosticSequence == sequence else { return }
            let resource = webView?.url?.lastPathComponent ?? "none"
            let loading = webView?.isLoading ?? false
            self.layoutUITestDiagnosticView?.accessibilityValue =
                "\(self.appliedTextOrientation.rawValue)|unavailable|\(progression)|\(mode)|resource=\(resource)|loading=\(loading)|ready=\(ready ?? "none")"
        }
    }
#endif

    private func isPageTurnTap(_ point: CGPoint) -> Bool {
        guard isPaginated else { return false }
        let width = max(navigator.view.bounds.width, 1)
        let edge = max(84, width * 0.22)
        return point.x <= edge || point.x >= width - edge
    }

    private var suppressesTapPageTurns: Bool {
        ReaderTapInteractionPolicy.suppressesPageTurn(
            quickSentenceTranslationEnabled: isQuickSentenceTranslationEnabled,
            disablesTapPageTurnsDuringQuickTranslation:
                disablesTapPageTurnsDuringQuickTranslation
        )
    }

    private func quickSentencePayload(
        at point: CGPoint,
        requestID: UUID,
        granularity: QuickHitGranularity
    ) async -> ReaderSelectionPayload? {
        let requestToken = requestID.uuidString
        guard let (webView, localPoint) = webView(at: point),
              let rawHit = try? await webView.evaluateJavaScript(
                  Self.quickSentenceHitScript(
                      at: localPoint,
                      prefersNativeRubySelection: true
                  )
              ),
              let hit = rawHit as? [String: Any],
              let paragraph = hit["paragraph"] as? String,
              let offset = (hit["offset"] as? NSNumber)?.intValue,
              let probeRect = Self.rect(from: hit["rect"]),
              isActiveQuickSentenceRequest(requestID)
        else {
            return nil
        }
        // caretRangeFromPoint always answers, even for a margin or the blank
        // end of a paragraph. A lookup needs the finger on a glyph, so such a
        // tap falls through to toggling the controls instead.
        if granularity == .word,
           !Self.isNearGlyph(localPoint, glyphRect: probeRect) {
            return nil
        }

        let segment: ReaderSentenceSegment?
        switch (granularity, quickTranslationUnit) {
        case (.word, _):
            segment = ReaderWordSegmenter.word(
                in: paragraph,
                utf16Offset: offset,
                language: ReaderLanguageDetector.detect(
                    text: paragraph,
                    bookLanguage: bookLanguage
                )
            )
        case (.translationUnit, .sentence):
            let language = ReaderLanguageDetector.detect(
                text: paragraph,
                bookLanguage: bookLanguage
            )
            segment = ReaderSentenceSegmenter.sentence(
                in: paragraph,
                utf16Offset: offset,
                language: language
            )
        case (.translationUnit, .paragraph):
            segment = ReaderParagraphSegmenter.paragraph(
                in: paragraph,
                utf16Offset: offset
            )
        }
        guard let segment else {
            return nil
        }

        let highlightedRect: CGRect? = if let rawRect = try? await webView.evaluateJavaScript(
            Self.quickSentenceHighlightScript(
                at: localPoint,
                utf16Range: segment.utf16Range,
                requestToken: requestToken,
                palette: selectionVisualPalette
            )
        ) {
            Self.rect(from: rawRect)
        } else {
            nil
        }
        guard isActiveQuickSentenceRequest(requestID) else {
            // A dismissed or superseded request can finish its WebKit call
            // after the newer request has begun. Remove only its own marker,
            // never the marker installed by the newer tap.
            await clearQuickSentenceHighlight(matching: requestToken)
            return nil
        }

        quickSentenceHighlightToken = highlightedRect == nil ? nil : requestToken

        let localFrame = highlightedRect ?? probeRect
        let navigatorFrame = webView.convert(localFrame, to: navigator.view)
        let windowFrame = navigator.view.convert(navigatorFrame, to: nil)
        let focusNavigatorFrame = webView.convert(probeRect, to: navigator.view)
        let focusWindowFrame = navigator.view.convert(focusNavigatorFrame, to: nil)
        guard windowFrame.minX.isFinite,
              windowFrame.minY.isFinite,
              windowFrame.width.isFinite,
              windowFrame.height.isFinite,
              !windowFrame.isEmpty
        else {
            if quickSentenceHighlightToken == requestToken {
                quickSentenceHighlightToken = nil
            }
            await clearQuickSentenceHighlight(matching: requestToken)
            return nil
        }

        let context = TranslationCacheStore.normalizedText(segment.contextText)
        let annotationLocatorJSON: String?
        if let currentLocator = navigator.currentLocation {
            let lower = String.Index(
                utf16Offset: segment.utf16Range.lowerBound,
                in: paragraph
            )
            let upper = String.Index(
                utf16Offset: segment.utf16Range.upperBound,
                in: paragraph
            )
            annotationLocatorJSON = Self.annotationLocatorJSON(
                base: currentLocator,
                highlight: String(paragraph[lower ..< upper]),
                before: String(paragraph[..<lower].suffix(96)),
                after: String(paragraph[upper...].prefix(96))
            )
        } else {
            annotationLocatorJSON = nil
        }
        return ReaderSelectionPayload(
            text: segment.text,
            contextText: context == segment.text ? nil : context,
            locatorJSON: navigator.currentLocation?.readerJSONString,
            annotationLocatorJSON: annotationLocatorJSON,
            frame: windowFrame.standardized,
            focusFrame: focusWindowFrame.standardized,
            trigger: granularity == .word ? .quickWord : .quickSentence
        )
    }

    private static func annotationLocatorJSON(
        base: Locator?,
        highlight: String,
        before: String?,
        after: String?
    ) -> String? {
        guard let base else { return nil }
        let value = base.copy(text: { text in
            text = Locator.Text(
                after: after.map { String($0.prefix(128)) },
                before: before.map { String($0.suffix(128)) },
                highlight: highlight
            )
        })
        return value.readerJSONString
    }

    /// Same 28pt tolerance as the Android short-tap script.
    private static func isNearGlyph(
        _ point: CGPoint,
        glyphRect rect: CGRect,
        tolerance: CGFloat = 28
    ) -> Bool {
        let dx = max(rect.minX - point.x, 0, point.x - rect.maxX)
        let dy = max(rect.minY - point.y, 0, point.y - rect.maxY)
        return dx * dx + dy * dy <= tolerance * tolerance
    }

    private func isActiveQuickSentenceRequest(_ requestID: UUID) -> Bool {
        !Task.isCancelled && quickSentenceRequestID == requestID
    }

    private struct NativeSelectionCandidate {
        let webView: WKWebView
        let snapshot: EPUBSelectionSnapshot
    }

    private func nativeSelectionSnapshot(
        at point: CGPoint?,
        pointFallbackText: String? = nil
    ) async -> NativeSelectionCandidate? {
        var candidates: [WKWebView] = []
        var pointFallbackTarget: (webView: WKWebView, point: CGPoint)?
        if let point, let (webView, localPoint) = webView(at: point) {
            candidates.append(webView)
            pointFallbackTarget = (webView, localPoint)
        }
        for webView in visibleContentWebViews()
        where !candidates.contains(where: { $0 === webView }) {
            candidates.append(webView)
        }

        for webView in candidates {
            if let rawValue = try? await webView.evaluateJavaScript(
                Self.nativeSelectionSnapshotScript
            ),
            let snapshot = EPUBSelectionSnapshot(javaScriptValue: rawValue) {
                return NativeSelectionCandidate(
                    webView: webView,
                    snapshot: snapshot
                )
            }
        }

        // WebKit occasionally collapses the browser Selection immediately
        // after a horizontal long press on ruby text. Readium still provides
        // the selected text and frame, so resolve only the touched <ruby>'s
        // base nodes at that point. This is a semantic fallback; it never
        // writes a Range back into WebKit and therefore cannot steal handles.
        if let target = pointFallbackTarget {
            do {
                let rawValue = try await target.webView.evaluateJavaScript(
                    Self.selectionPointSnapshotScript(
                        at: target.point,
                        selectedText: pointFallbackText
                    )
                )
                guard let snapshot = EPUBSelectionSnapshot(javaScriptValue: rawValue) else {
#if DEBUG
                    updateSelectionUITestDiagnostic(
                        "point fallback rejected raw=\(String(describing: rawValue)) "
                            + "point=\(target.point) text=\(pointFallbackText ?? "nil")"
                    )
#endif
                    return nil
                }
                return NativeSelectionCandidate(
                    webView: target.webView,
                    snapshot: snapshot
                )
            } catch {
#if DEBUG
                updateSelectionUITestDiagnostic(
                    "point fallback error=\(error.localizedDescription) "
                        + "point=\(target.point)"
                )
#endif
            }
        }
        return nil
    }

    /// Readium keeps neighbouring spine WebViews mounted for fast paging.
    /// Only views intersecting the navigator viewport may own the user's
    /// current selection; querying an off-screen neighbour can otherwise pick
    /// up a stale Range from another chapter.
    private func visibleContentWebViews() -> [WKWebView] {
        let viewport = navigator.view.bounds.insetBy(dx: -1, dy: -1)
        return navigator.view.descendantWebViews
            .compactMap { webView -> (WKWebView, CGFloat)? in
                guard !webView.isHidden,
                      webView.alpha > 0.01,
                      webView.window != nil
                else { return nil }

                let frame = webView.convert(webView.bounds, to: navigator.view)
                    .standardized
                let intersection = frame.intersection(viewport)
                guard !intersection.isNull,
                      intersection.width > 1,
                      intersection.height > 1
                else { return nil }
                return (webView, intersection.width * intersection.height)
            }
            .sorted { $0.1 > $1.1 }
            .map(\.0)
    }

    private func webView(at point: CGPoint) -> (WKWebView, CGPoint)? {
        var hitView = navigator.view.hitTest(point, with: nil)
        while let view = hitView {
            if let webView = view as? WKWebView {
                let localPoint = navigator.view.convert(point, to: webView)
                if webView.bounds.insetBy(dx: -1, dy: -1).contains(localPoint) {
                    return (
                        webView,
                        EPUBSelectionBridge.domViewportPoint(localPoint, in: webView)
                    )
                }
            }
            hitView = view.superview
        }

        for webView in visibleContentWebViews() {
            let localPoint = navigator.view.convert(point, to: webView)
            if webView.bounds.insetBy(dx: -1, dy: -1).contains(localPoint) {
                return (
                    webView,
                    EPUBSelectionBridge.domViewportPoint(localPoint, in: webView)
                )
            }
        }
        return nil
    }

    private func clearQuickSentenceHighlight(matching requestToken: String? = nil) async {
        let script = Self.clearQuickSentenceHighlightScript(matching: requestToken)
        for webView in navigator.view.descendantWebViews {
            _ = try? await webView.evaluateJavaScript(script)
        }
    }

    private func updateQuickSentenceSpeechHighlight(
        _ highlight: ReaderSpeechHighlight?,
        matching requestToken: String,
        sequence: Int
    ) async -> Bool {
        let script = Self.quickSentenceSpeechHighlightScript(
            highlight,
            matching: requestToken,
            sequence: sequence,
            palette: selectionVisualPalette
        )
        var didUpdate = false
        for webView in navigator.view.descendantWebViews {
            let rawResult = try? await webView.evaluateJavaScript(script)
            if rawResult as? Bool == true {
                didUpdate = true
            }
        }
        return didUpdate
    }

    private static func rect(from value: Any?) -> CGRect? {
        guard let json = value as? [String: Any],
              let x = json["x"] as? NSNumber,
              let y = json["y"] as? NSNumber,
              let width = json["width"] as? NSNumber,
              let height = json["height"] as? NSNumber
        else {
            return nil
        }

        let rect = CGRect(
            x: CGFloat(truncating: x),
            y: CGFloat(truncating: y),
            width: CGFloat(truncating: width),
            height: CGFloat(truncating: height)
        ).standardized
        guard rect.minX.isFinite,
              rect.minY.isFinite,
              rect.width.isFinite,
              rect.height.isFinite,
              !rect.isEmpty
        else { return nil }
        return rect
    }

    private func selectionFrameInWindow(_ frame: CGRect?) -> CGRect? {
        guard let frame,
              frame.minX.isFinite,
              frame.minY.isFinite,
              frame.width.isFinite,
              frame.height.isFinite,
              !frame.isEmpty
        else { return nil }

        return navigator.view.convert(frame.standardized, to: nil)
    }

    /// Keeps Readium's horizontal paging and text selection intact while
    /// preventing the embedded page from drifting vertically or zooming.
    private func configureViewport() {
        enforceWritingModePolicy()
        enforceVerticalPaginationPolicy()
        for webView in navigator.view.descendantWebViews {
            installSelectionPolicy(in: webView)
            let scrollView = webView.scrollView
            scrollView.contentInsetAdjustmentBehavior = .never
            scrollView.isDirectionalLockEnabled = true
            scrollView.scrollsToTop = false
            scrollView.showsVerticalScrollIndicator = false
            scrollView.showsHorizontalScrollIndicator = false
            scrollView.bounces = false
            scrollView.alwaysBounceHorizontal = false
            scrollView.alwaysBounceVertical = false

            pinVerticalContentOffset(in: scrollView)
            observeVerticalContentOffset(in: scrollView)
            scheduleVerticalPageAlignment(in: scrollView)

            // Readium's horizontal paginator requires UIKit paging as well:
            // EPUBReflowableSpreadView sets it to !viewModel.scroll. Turning
            // it off here lets a drag stop between columns/pages.
            maintainPageSnapping(in: scrollView)
            scrollView.decelerationRate = usesVerticalPageSnapping
                ? .fast
                : .normal

            if isPaginated {
                scrollView.pinchGestureRecognizer?.isEnabled = false
                if isFixedLayout {
                    scrollView.panGestureRecognizer.isEnabled = false
                } else {
                    // Reflowable EPUBs use this pan recognizer for horizontal
                    // page swipes and for native text-selection handles.
                    scrollView.panGestureRecognizer.isEnabled = true
                }
            } else {
                scrollView.panGestureRecognizer.isEnabled = true
                scrollView.pinchGestureRecognizer?.isEnabled = isFixedLayout
            }
        }
    }

    /// Keeps a `vertical-rl` page from paying the safe-area inset twice.
    ///
    /// Readium reserves the notch and the status bar by insetting the spread's
    /// scroll view (62pt top and bottom on a phone) whenever the spread
    /// scrolls — and vertical text always scrolls, because ReadiumCSS refuses
    /// to paginate it. WebKit already lays the page out inside the resulting
    /// unobscured rect, so the columns clear the inset on their own. UIKit then
    /// *also* moves the scroll view to `contentOffset.y == -contentInset.top`,
    /// which displaces that same page by the same 62pt a second time: a dead
    /// band along the top and the bottom of every column cut off.
    ///
    /// A vertical page is exactly one viewport tall and overflows sideways, so
    /// it has nothing to reach on this axis and any non-zero vertical offset is
    /// that leftover displacement. Readium says as much in its own way —
    /// `scroll(toProgression:)` excludes `verticalText` from its inset-aware
    /// branch and hands the scroll to JS which, as its comment admits, does not
    /// know about the content inset.
    ///
    /// Horizontal books are left alone, including genuine vertical scrolling in
    /// 滚动 reading mode, which the content-height test also guards.
    private func pinVerticalContentOffset(in scrollView: UIScrollView) {
        guard rendersVerticalText, scrollView.contentOffset.y != 0 else { return }
        // Measured against the full height rather than the unobscured one: while
        // WebKit is still relaying out for a changed inset the content is
        // briefly the whole viewport tall, and testing against the smaller
        // number skipped the correction for exactly that window — which is when
        // the bad offset is set. Anything genuinely taller than the viewport is
        // still left alone.
        guard scrollView.contentSize.height <= scrollView.bounds.height + 0.5
        else { return }
        scrollView.contentOffset.y = 0
    }

    /// Watches for that displacement instead of only correcting it in the
    /// layout pass.
    ///
    /// Readium re-applies the inset from `applySettings`, `safeAreaInsetsDidChange`
    /// and `traitCollectionDidChange`, and UIKit's answering offset change does
    /// not schedule a layout pass of this controller's view. Correcting only in
    /// `viewDidLayoutSubviews` therefore won a race sometimes and lost it
    /// others, which is exactly how the bug presented: the same book came up
    /// flush on one launch and one inset low on the next.
    private func observeVerticalContentOffset(in scrollView: UIScrollView) {
        guard verticalOffsetObservations.object(forKey: scrollView) == nil else { return }
        // Both keys matter: UIKit's displacement is a `contentOffset` write, but
        // it can land while WebKit is still measuring, and the relayout that
        // follows arrives only as a `contentSize` change.
        let correct: @Sendable (UIScrollView, Any) -> Void = { [weak self] scrollView, _ in
            MainActor.assumeIsolated {
#if DEBUG
                self?.noteVerticalOffsetForRubyRegionUITest(scrollView)
#endif
                // Re-entrant by construction: the correction is itself a
                // `contentOffset` write. `pinVerticalContentOffset` returns
                // immediately once the offset is zero, so it settles in one step.
                self?.pinVerticalContentOffset(in: scrollView)
                self?.maintainPageSnapping(in: scrollView)
                self?.scheduleVerticalPageAlignment(in: scrollView)
#if DEBUG
                self?.updateLayoutUITestDiagnosticAfterSettling()
#endif
            }
        }
        let observations = ReaderScrollViewObservations(
            offset: scrollView.observe(\.contentOffset, options: [.new]) { view, change in
                correct(view, change)
            },
            size: scrollView.observe(\.contentSize, options: [.new]) { view, change in
                correct(view, change)
            },
            paging: scrollView.observe(\.isPagingEnabled, options: [.new]) { view, change in
                correct(view, change)
            }
        )
        verticalOffsetObservations.setObject(observations, forKey: scrollView)
    }

    private func maintainPageSnapping(in scrollView: UIScrollView) {
        let paging = resolvedIsPaginated
        guard scrollView.isPagingEnabled != paging else { return }
        // Readium re-applies its settings after the document finishes loading.
        // Its forced vertical scroll mode must not undo the user's page mode.
        // Keep its delegate intact; this observation also covers new spreads.
        scrollView.isPagingEnabled = paging
    }

    private func scheduleVerticalPageAlignment(in scrollView: UIScrollView) {
        guard let observation = verticalOffsetObservations.object(forKey: scrollView) else { return }
        observation.pageAlignmentTask?.cancel()
        guard usesVerticalPageSnapping else { observation.wasUserPaging = false; return }
        if scrollView.isTracking || scrollView.isDragging || scrollView.isDecelerating {
            observation.wasUserPaging = true
        }
        observation.pageAlignmentTask = Task { @MainActor [weak self, weak scrollView] in
            do { try await Task.sleep(for: .milliseconds(180)) } catch { return }
            guard let self, let scrollView,
                  self.usesVerticalPageSnapping,
                  !scrollView.isTracking, !scrollView.isDragging, !scrollView.isDecelerating,
                  self.pendingSelectionRequestID == nil,
                  self.selectionBridge.displayedRects.isEmpty,
                  self.navigator.currentSelection == nil
            else { return }
            let width = scrollView.bounds.width
            guard width > 1 else { return }
            let maximum = max(scrollView.contentSize.width - width, 0)
            // The final fragment can be shorter than the regular page stride.
            // Both resource edges are already valid resting positions.
            guard scrollView.contentOffset.x > 0.5,
                  scrollView.contentOffset.x < maximum - 0.5
            else {
                self.verticalOffsetObservations.object(forKey: scrollView)?.wasUserPaging = false
                return
            }
            let rtl = self.navigator.settings.readingProgression == .rtl
            let distance = rtl ? maximum - scrollView.contentOffset.x : scrollView.contentOffset.x
            // Readium restores vertical locators through its scroll branch,
            // which can stop inside a page. Keep the anchor on that page;
            // UIKit's own swipe/deceleration remains responsible during touch.
            let observation = self.verticalOffsetObservations.object(forKey: scrollView)
            let index = observation?.wasUserPaging == true
                ? round(distance / width) : floor(distance / width + 0.001)
            observation?.wasUserPaging = false
            let offset = rtl ? maximum - index * width : index * width
            let target = min(max(offset, 0), maximum)
            guard abs(target - scrollView.contentOffset.x) > 0.5 else { return }
            scrollView.setContentOffset(CGPoint(x: target, y: scrollView.contentOffset.y), animated: false)
        }
    }

    private func installSelectionPolicy(in webView: WKWebView) {
        guard !selectionPolicyAppliedWebViews.allObjects.contains(where: { $0 === webView })
        else { return }
        selectionPolicyAppliedWebViews.add(webView)

        // The delegate above owns future document-start injection. Evaluate
        // once as a recovery path for a WebView whose current document began
        // loading before the first layout pass discovered it.
        let script = Self.selectionPolicyScript(
            detachRubyAnnotations: true
        )
        Task { @MainActor [weak webView] in
            guard let webView else { return }
            do {
                _ = try await webView.evaluateJavaScript(script)
            } catch {
                self.selectionPolicyAppliedWebViews.remove(webView)
            }
        }
    }

    private func reportPaginationMode() {
        onPaginationModeChange?(isPaginated)
    }

    /// Whether the page will actually be laid out in columns of vertical text.
    ///
    /// A publication can declare vertical writing while the reader forces
    /// horizontal, so the *resolved* answer is the one that matters — both for
    /// the Readium preferences and for the page-turn workaround that depends on
    /// them agreeing.
    static func resolvedVerticalText(
        textOrientation: ReaderTextOrientationChoice,
        publicationLayout: ReaderPublicationLayout
    ) -> Bool {
        textOrientation == .horizontal ? false : publicationLayout.verticalText
    }

    /// Kept internal so the tests can verify the exact Readium preferences
    /// used at the original/horizontal mode boundary.
    static func preferences(
        fontSize: Double,
        theme: ReaderThemeChoice,
        fontChoice: ReaderFontChoice,
        lineHeight: Double,
        paragraphSpacing: Double,
        pageMargins: Double,
        textOrientation: ReaderTextOrientationChoice,
        publicationLayout: ReaderPublicationLayout = .horizontalLTR,
        readingMode: ReaderReadingMode = .paginated,
        customBackgroundHex: String = ""
    ) -> EPUBPreferences {
        let customBackground = ReaderCustomBackground.normalizedHex(
            customBackgroundHex
        )
        let usesLightText = customBackground.map(
            ReaderCustomBackground.prefersLightText(hex:)
        ) ?? false
        let isHorizontalOverride = textOrientation == .horizontal
        let resolvedProgression: ReadiumNavigator.ReadingProgression =
            isHorizontalOverride
                ? .ltr
                : (publicationLayout.rightToLeft ? .rtl : .ltr)
        let resolvedVerticalText = Self.resolvedVerticalText(
            textOrientation: textOrientation,
            publicationLayout: publicationLayout
        )
        return EPUBPreferences(
            backgroundColor: customBackground.map(ReadiumNavigator.Color.init(hex:))
                ?? theme.readiumBackgroundColor,
            columnCount: .auto,
            fit: .page,
            fontFamily: fontChoice.readiumFontFamily,
            fontSize: ReaderFontSizing.clampedScale(fontSize),
            lineHeight: min(max(lineHeight, 1.0), 2.2),
            pageMargins: min(max(pageMargins, 0.5), 2.0),
            paragraphSpacing: min(max(paragraphSpacing, 0.0), 2.0),
            publisherStyles: false,
            readingProgression: resolvedProgression,
            scroll: readingMode.scrollEnabled,
            spread: .auto,
            textColor: customBackground == nil
                ? theme.readiumTextColor
                : ReadiumNavigator.Color(
                    hex: usesLightText ? "#F4F7FA" : "#17212B"
                ),
            textNormalization: true,
            theme: customBackground == nil
                ? theme.readiumTheme
                : (usesLightText ? .dark : .light),
            verticalText: resolvedVerticalText
        )
    }
}

private extension UIView {
    var descendantWebViews: [WKWebView] {
        subviews.flatMap { view in
            if let webView = view as? WKWebView {
                return [webView]
            }
            return view.descendantWebViews
        }
    }
}

private extension ReaderThemeChoice {
    var readiumTheme: ReadiumNavigator.Theme {
        switch self {
        case .light: return .light
        case .sepia: return .sepia
        case .coolGray: return .light
        case .dark: return .dark
        }
    }

    var readiumBackgroundColor: ReadiumNavigator.Color? {
        switch self {
        case .light: return nil
        case .sepia: return nil
        case .coolGray: return ReadiumNavigator.Color(hex: "#EEF3F8")
        case .dark: return nil
        }
    }

    var readiumTextColor: ReadiumNavigator.Color? {
        switch self {
        case .light, .sepia, .dark: return nil
        case .coolGray: return ReadiumNavigator.Color(hex: "#17212B")
        }
    }
}

private extension ReadingAnnotationColor {
    var uiColor: UIColor {
        switch self {
        case .yellow: return UIColor.systemYellow.withAlphaComponent(0.44)
        case .blue: return UIColor.systemBlue.withAlphaComponent(0.34)
        case .mint: return UIColor.systemMint.withAlphaComponent(0.38)
        case .pink: return UIColor.systemPink.withAlphaComponent(0.32)
        case .purple: return UIColor.systemPurple.withAlphaComponent(0.30)
        }
    }
}

private extension ReaderFontChoice {
    var readiumFontFamily: FontFamily {
        switch self {
        case .serif: return .serif
        case .sansSerif: return .sansSerif
        case .openDyslexic: return .openDyslexic
        }
    }
}

struct PublicationReaderContainer: UIViewControllerRepresentable {
    let controller: any PublicationReaderController

    func makeUIViewController(context: Context) -> UIViewController {
        controller.viewController
    }

    func updateUIViewController(
        _ uiViewController: UIViewController,
        context: Context
    ) {}
}
