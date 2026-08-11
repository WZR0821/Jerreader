import SwiftUI
import UIKit
@preconcurrency import WebKit
@preconcurrency import ReadiumNavigator
@preconcurrency import ReadiumShared
import JerreaderCore

/// Keeps the KVO tokens that watch one spread's scroll view alive for as long as
/// the scroll view itself. `NSMapTable` needs a class to hold them, and both
/// observations share a lifetime, so they travel together.
private final class ReaderScrollViewObservations {
    let offset: NSKeyValueObservation
    let size: NSKeyValueObservation

    init(offset: NSKeyValueObservation, size: NSKeyValueObservation) {
        self.offset = offset
        self.size = size
    }
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
    /// The resource the quick-sentence highlight was painted into. The overlay
    /// lives in that document's DOM, so it stays anchored to its own text for
    /// as long as the reader is in it — see `locationDidChange`.
    private var quickSentenceHighlightHref: String?
    private var quickSentenceSpeechSequence = 0
    private var lastDeliveredSelectionSignature: String?
    private var selectionIntentResolver = ReaderSelectionIntentResolver()
    private var selectionGestureGate = EPUBSelectionGestureGate()
    private let selectionBridge = EPUBSelectionBridge()
    private var selectionVisualPalette = ReaderSelectionVisualPalette.light
    private let selectionPolicyAppliedWebViews = NSHashTable<WKWebView>.weakObjects()
    private let writingModePolicyModes =
        NSMapTable<WKWebView, NSString>.weakToStrongObjects()
    private let verticalOffsetObservations =
        NSMapTable<UIScrollView, ReaderScrollViewObservations>.weakToStrongObjects()
    /// Measured column geometry per spread, in points. See
    /// `measureVerticalColumns(in:)`.
    private let verticalColumnMetrics =
        NSMapTable<WKWebView, ReaderVerticalColumnMetrics>.weakToStrongObjects()
    private let verticalPagingDelegates =
        NSMapTable<UIScrollView, EPUBVerticalPagingScrollDelegate>.weakToStrongObjects()
    private let verticalGutterOverlay = EPUBVerticalGutterOverlay()
    /// Where an animating page turn is headed, so the gutters can show the page
    /// being arrived at instead of every offset between here and there.
    private var pendingVerticalPageTarget: CGFloat?
    private static let annotationDecorationGroup = "jerreader-reading-annotations"
#if DEBUG
    private var selectionUITestTargetView: UIView?
    private var selectionUITestDiagnosticView: UIView?
    private var layoutUITestDiagnosticView: UIView?
    private var translationUITestSelectionView: UIView?
    private var translationUITestCardView: UIView?
    private var layoutUITestDiagnosticSequence = 0
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
        // Seed the writing mode here as well as on every settings change. The
        // merger groups fragments along the line axis, so a vertical book whose
        // mode was only ever set by a later `apply` would have its very first
        // selection merged across columns instead of down them.
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
        // Above the selection highlight on purpose: a sliver of column that is
        // not shown must not show a selection either.
        verticalGutterOverlay.frame = view.bounds
        verticalGutterOverlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(verticalGutterOverlay)
        installNativeSelectionFallbackGesture()
        view.clipsToBounds = true
        navigator.view.clipsToBounds = true
        // This observer is deliberately registered before Readium's page-turn
        // adapter. When the user reserves taps for quick translation, consuming
        // the activation here sends a cancel phase to the adapter, so one touch
        // can never both translate and turn the page. Swipe pagination remains
        // owned by the navigator and is unaffected.
        navigator.addObserver(.tap { [weak self] _ in
            self?.suppressesTapPageTurns ?? false
        })
        navigator.addObserver(.click { [weak self] _ in
            self?.suppressesTapPageTurns ?? false
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
        // Font size, line height and margins all move the column advance, so
        // the cached measurement is stale the moment any of them is submitted.
        if preferences != submittedPreferences {
            verticalColumnMetrics.removeAllObjects()
            verticalGutterOverlay.hideGutters()
        }
        submittedPreferences = preferences
        appliedTextOrientation = textOrientation
        appliedReadingMode = readingMode
        if orientationChanged {
            // Publish before submitting: Readium's invalidation reloads the
            // spine item, and the reloaded document must already see the new
            // writing mode at document-start.
            publishWritingModePreference()
        }
        navigator.submitPreferences(preferences)
        enforceWritingModePolicy()
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
    /// Rather than fight ReadiumCSS, the spread keeps scrolling and the *scroll
    /// view* is made to snap: for vertical text the navigator's axis is already
    /// horizontal, so one viewport width is exactly one page of columns. Paging
    /// the scroll view therefore turns the same content one page at a time
    /// without touching the CSS Readium disabled pagination for.
    ///
    /// Horizontal books never reach this path: Readium paginates them properly,
    /// so `scroll` survives and nothing here changes.
    private var pagingPolicy: EPUBPagingPolicy {
        EPUBPagingPolicy.resolve(
            readingMode: appliedReadingMode,
            rendersVerticalText: rendersVerticalText,
            voiceOverRunning: UIAccessibility.isVoiceOverRunning
        )
    }

    private var usesVerticalPageSnapping: Bool {
        pagingPolicy.usesVerticalSnapping
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
    private var resolvedIsPaginated: Bool { pagingPolicy.isPaginated }

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
        for _ in 0 ..< 20 {
            guard !Task.isCancelled else { return }
            if let servesVertical = await currentDocumentServesVerticalCSS() {
                if servesVertical == expectsVerticalText {
                    return
                }
                break
            }
            try? await Task.sleep(for: .milliseconds(100))
        }

        let savedLocation = navigator.currentLocation
        for _ in 0 ..< 2 {
            guard !Task.isCancelled else { return }

            // Submitting the target again invokes Readium's official HTML-cache
            // eviction. `reloadFromOrigin` then prevents the current WebKit
            // instance from reusing its own stale response.
            replaySubmittedPreferences()
            let webViews = navigator.view.descendantWebViews.filter {
                !$0.isHidden && $0.alpha > 0.01 && $0.window != nil
            }
            guard !webViews.isEmpty else {
                try? await Task.sleep(for: .milliseconds(180))
                continue
            }
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
                "getComputedStyle(document.documentElement).writingMode"
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
              let webView = visibleContentWebViews().first
        else { return false }
        let scrollView = webView.scrollView

        let width = scrollView.bounds.width
        guard width > 1 else { return false }

        // `vertical-rl` begins at the right edge and advances leftwards, so for
        // a right-to-left publication "forward" means a decreasing x offset.
        let movesLeft = (forward == publicationLayout.rightToLeft)
        guard let target = verticalPageTarget(
            in: webView,
            from: scrollView.contentOffset.x,
            movesLeft: movesLeft
        ) else { return false }

        pendingVerticalPageTarget = target
        scrollView.setContentOffset(
            CGPoint(x: target, y: scrollView.contentOffset.y),
            animated: true
        )
        updateVerticalGutters()
        return true
    }

    /// Where a page turn from `origin` lands, or nil when this spread has no
    /// page left that way and the navigator must move to the next chapter.
    ///
    /// The rule lives in `core` so the Android reader turns pages the same way;
    /// this supplies the measurements it works from and the fallback for a
    /// spread that has not been measured yet.
    private func verticalPageTarget(
        in webView: WKWebView,
        from origin: CGFloat,
        movesLeft: Bool
    ) -> CGFloat? {
        let scrollView = webView.scrollView
        let width = scrollView.bounds.width
        guard width > 1 else { return nil }
        let maximumOffset = max(scrollView.contentSize.width - width, 0)
        let offset = Double(min(max(origin, 0), maximumOffset))

        // The ends of the spread belong to Readium: that is what moves between
        // chapters, and swallowing the turn here would strand the reader.
        if movesLeft, offset <= 0.5 { return nil }
        if !movesLeft, offset >= Double(maximumOffset) - 0.5 { return nil }

        let metrics = verticalColumnMetrics.object(forKey: webView)
        if let measured = metrics?.grid?.pageTarget(
            offset: offset,
            viewportWidth: Double(width),
            movesLeft: movesLeft,
            maximumOffset: Double(maximumOffset)
        )?.doubleValue {
            return CGFloat(measured)
        }

        // Not measured yet, or the measurement does not reach this far into a
        // long chapter. Step by the estimate rather than hand a mid-chapter tap
        // to the navigator, which would skip to the next spine item.
        let advance = ReaderVerticalPageStep.shared.advance(
            viewportWidth: Double(width),
            columnAdvance: metrics?.columnAdvance.map(KotlinDouble.init(value:))
        )
        let target = min(max(offset + (movesLeft ? -advance : advance), 0), Double(maximumOffset))
        guard abs(target - offset) > 0.5 else { return nil }
        return CGFloat(target)
    }

    /// Measures a vertical page, once per web view.
    ///
    /// Two things come back. The column boxes are the exact geometry — every
    /// line box in `vertical-rl` is a column, and their edges are where a page
    /// may begin and end without cutting one in half. The median advance is the
    /// estimate that covers the moment before they arrive, and a document too
    /// large or too odd to yield them.
    private func measureVerticalColumns(in webView: WKWebView) {
        guard rendersVerticalText else { return }
        if let existing = verticalColumnMetrics.object(forKey: webView) {
            guard !existing.describes(contentWidth: Double(webView.scrollView.contentSize.width))
            else { return }
            verticalColumnMetrics.removeObject(forKey: webView)
            verticalGutterOverlay.hideGutters()
        }
        // Claim the slot up front so a burst of layout passes cannot start the
        // same measurement several times over.
        verticalColumnMetrics.setObject(.pending, forKey: webView)
        Task { @MainActor [weak self, weak webView] in
            guard let self, let webView else { return }
            let rawAdvance = try? await webView.evaluateJavaScript(Self.columnAdvanceScript)
            let advance = (rawAdvance as? NSNumber)?.doubleValue
            let rawGeometry = try? await webView.evaluateJavaScript(Self.columnBoxesScript)
            let geometry = rawGeometry as? [String: Any]
            let edges = (geometry?["columns"] as? [NSNumber])?.map {
                KotlinDouble(value: $0.doubleValue)
            }
            let grid = edges.map(ReaderVerticalColumnGrid.init(columnEdges:))

            guard grid?.isUsable == true || (advance?.isFinite == true && advance! > 1) else {
                // Leave it unmeasured so a later layout pass can try again; an
                // empty spread measures nothing, which is not the same as a
                // spread that measures badly.
                self.verticalColumnMetrics.removeObject(forKey: webView)
                return
            }
            self.verticalColumnMetrics.setObject(
                ReaderVerticalColumnMetrics(
                    grid: grid?.isUsable == true ? grid : nil,
                    columnAdvance: (advance?.isFinite == true && advance! > 1) ? advance : nil,
                    pageColor: readerCSSColor(geometry?["background"] as? String),
                    contentWidth: Double(webView.scrollView.contentSize.width)
                ),
                forKey: webView
            )
            self.updateVerticalGutters()
        }
    }

    static let columnAdvanceScript = """
        (() => {
          const walker = document.createTreeWalker(
            document.body,
            NodeFilter.SHOW_TEXT,
            {
              acceptNode(node) {
                if (!node.data || !node.data.trim()) return NodeFilter.FILTER_REJECT;
                // Ruby annotations sit in their own, much narrower boxes.
                if (node.parentElement?.closest('rt,rp')) return NodeFilter.FILTER_REJECT;
                return NodeFilter.FILTER_ACCEPT;
              }
            }
          );
          const advances = [];
          const widths = [];
          let node;
          while ((node = walker.nextNode()) && advances.length < 400) {
            const range = document.createRange();
            range.selectNodeContents(node);
            // A vertical line box is tall and narrow. Anything else is a
            // horizontal run and would answer the wrong question.
            const rects = Array.from(range.getClientRects())
              .filter(r => r.width > 1 && r.height > r.width);
            for (const rect of rects) widths.push(rect.width);
            // What a page turn has to clear is the distance from one column to
            // the next, not the ink inside one. Glyphs fill about the font
            // size; the next column starts a whole line-height away, so
            // measuring the box would hold back far too little.
            for (let i = 1; i < rects.length; i++) {
              const delta = Math.abs(rects[i].left - rects[i - 1].left);
              if (delta > 1) advances.push(delta);
            }
          }
          const median = (xs) => {
            if (!xs.length) return null;
            xs.sort((a, b) => a - b);
            return xs[Math.floor(xs.length / 2)];
          };
          // A spread with a single line of text has no column-to-column
          // distance to measure; its ink width is the closest honest answer.
          return median(advances) ?? median(widths);
        })()
        """

    /// The column boxes of a vertical page, in scroll-view coordinates, and the
    /// colour of the paper behind them.
    ///
    /// A `vertical-rl` page is not the uniform grid the median advance assumes:
    /// paragraph spacing is a gap along the block axis, and a heading's line
    /// height is not the body's, so column edges drift within a single screen.
    /// The boxes are the truth, and cheap enough to take once per document —
    /// after that the reader answers every question about the page from them
    /// without going back to JavaScript.
    ///
    /// Rects are coalesced where they overlap: an `<em>` inside a line, or the
    /// base text of a `<ruby>`, produce several rects sharing one column, and a
    /// column is what the page turn cares about. Adjacent columns only *touch*,
    /// so they survive as the separate boxes they are.
    static let columnBoxesScript = """
        (() => {
          const walker = document.createTreeWalker(
            document.body,
            NodeFilter.SHOW_TEXT,
            {
              acceptNode(node) {
                if (!node.data || !node.data.trim()) return NodeFilter.FILTER_REJECT;
                // Ruby annotations sit beside the base text inside the same
                // line box; their own rects are not column edges.
                if (node.parentElement?.closest('rt,rp')) return NodeFilter.FILTER_REJECT;
                return NodeFilter.FILTER_ACCEPT;
              }
            }
          );
          const boxes = [];
          let node;
          while ((node = walker.nextNode())) {
            const range = document.createRange();
            range.selectNodeContents(node);
            for (const rect of range.getClientRects()) {
              if (rect.width > 1 && rect.height > 1) boxes.push([rect.left, rect.right]);
            }
          }
          const style = getComputedStyle(document.body);
          const rootStyle = getComputedStyle(document.documentElement);
          const background =
            style.backgroundColor && !style.backgroundColor.includes('rgba(0, 0, 0, 0)')
              ? style.backgroundColor
              : rootStyle.backgroundColor;
          boxes.sort((a, b) => a[0] - b[0]);
          const merged = [];
          for (const box of boxes) {
            const last = merged[merged.length - 1];
            if (last && box[0] < last[1] - 0.5) {
              if (box[1] > last[1]) last[1] = box[1];
              continue;
            }
            merged.push([box[0], box[1]]);
          }
          // Client rects are relative to the viewport; the page turn works in
          // the scroll view's coordinates, which start at the content origin.
          const scrolled = window.scrollX;
          const columns = [];
          for (const box of merged) columns.push(box[0] + scrolled, box[1] + scrolled);
          return { columns, background };
        })()
        """

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
        let highlightToken = takeQuickSentenceHighlight()
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

    /// Gives up the quick-sentence highlight and returns the token whose
    /// markers are still in the DOM, so the caller can remove exactly those and
    /// never the ones a newer tap installed.
    ///
    /// One function rather than two assignments at six call sites: the token
    /// and the resource it was painted into have to be forgotten together, and
    /// a site that forgot only one would either leak a highlight or clear one
    /// that is still on screen.
    private func takeQuickSentenceHighlight() -> String? {
        defer {
            quickSentenceHighlightToken = nil
            quickSentenceHighlightHref = nil
        }
        return quickSentenceHighlightToken
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
        let highlightToken = takeQuickSentenceHighlight()
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
        let highlightToken = takeQuickSentenceHighlight()
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
        // Only a change of resource ends a quick-sentence highlight here.
        //
        // Readium reports a location change for *any* movement of the spread,
        // and a vertical book is always in scroll mode, so a finger resting on
        // the page, the momentum after a page turn, or the reader's own
        // safe-area correction each publish one. Tearing the highlight down on
        // every one of those is what left a translation card standing over a
        // sentence with no colour on it — the recurring "选区没颜色", and always
        // worst on Japanese vertical books, which scroll the most.
        //
        // The overlay is in the document's own DOM at document coordinates, so
        // while the reader is still in that resource it stays on its sentence
        // no matter where the page has scrolled to. A different resource is a
        // different document, and then there is genuinely nothing to keep.
        // Dismissing the card calls `clearSelection()`, which is what normally
        // ends a highlight.
        let keepsPaintedSentence = quickSentenceHighlightHref
            .map { $0 == locator.href.string } ?? false
        if !keepsPaintedSentence {
            quickSentenceTask?.cancel()
            quickSentenceTask = nil
            quickSentenceRequestID = nil
            if let highlightToken = takeQuickSentenceHighlight() {
                Task { [weak self] in
                    await self?.clearQuickSentenceHighlight(matching: highlightToken)
                }
            }
        }

        onLocationChange?(locator)
        isPaginated = self.resolvedIsPaginated
        reportPaginationMode()
        view.setNeedsLayout()
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
            onContentTap?()
            return
        }

        // With the compatibility option off, the edge remains a dedicated page
        // zone. With it on (the default), the observer installed before the
        // adapter consumes this same activation and text at any x-position can
        // safely participate in the sentence hit-test below.
        guard suppressesTapPageTurns || !isPageTurnTap(point) else { return }

        resetNativeSelectionTracking()
        self.navigator.clearSelection()

        quickSentenceTask?.cancel()
        let requestID = UUID()
        quickSentenceRequestID = requestID
        quickSentenceTask = Task { @MainActor [weak self] in
            guard let self else { return }
            let payload = await self.quickSentencePayload(
                at: point,
                requestID: requestID
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
            )
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
        if let highlightToken = takeQuickSentenceHighlight() {
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
            self.selectionIntentResolver.reset()
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
        translationUITestSelectionView?.removeFromSuperview()
        translationUITestSelectionView = nil
        translationUITestCardView?.removeFromSuperview()
        translationUITestCardView = nil

        var rubyWebView: WKWebView?
        for _ in 0 ..< 120 {
            for candidate in visibleContentWebViews() {
                let didFindRuby = try? await candidate.evaluateJavaScript(
                    """
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

    /// Installs a transparent, non-interactive source rectangle so XCUITest can
    /// compare the actual SwiftUI floating-window frame with the original
    /// text region. This exists only in Debug UI-test launches.
    func installTranslationUITestSelection(frameInWindow: CGRect) {
        translationUITestSelectionView?.removeFromSuperview()
        translationUITestSelectionView = nil

        let localFrame = view.convert(frameInWindow, from: nil)
            .standardized
            .intersection(view.bounds)
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
        view.addSubview(marker)
        translationUITestSelectionView = marker
    }

    /// Mirrors the actual SwiftUI card frame into UIKit accessibility so the
    /// geometry test can read it reliably. The view is transparent and cannot
    /// receive touches.
    func installTranslationUITestCard(frameInWindow: CGRect) {
        guard ProcessInfo.processInfo.arguments.contains(where: {
            $0.hasPrefix("--jerreader-translation-overlay-ui-test=")
        }), frameInWindow.height >= 285 else { return }

        let localFrame = view.convert(frameInWindow, from: nil)
            .standardized
            .intersection(view.bounds)
        guard !localFrame.isNull, !localFrame.isEmpty else { return }

        let marker = translationUITestCardView ?? UIView(frame: .zero)
        marker.frame = localFrame
        marker.backgroundColor = .clear
        marker.isUserInteractionEnabled = false
        marker.isAccessibilityElement = true
        marker.accessibilityIdentifier = "translation-card-diagnostic"
        marker.accessibilityLabel = "翻译浮窗测试区域"
        if marker.superview == nil {
            view.addSubview(marker)
        }
        translationUITestCardView = marker
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

                if let value = await self.currentDocumentWritingMode() {
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
                    let variant = switch await self.currentDocumentServesVerticalCSS() {
                    case .some(true): "cjk-vertical"
                    case .some(false): "cjk-horizontal"
                    case nil: "unknown"
                    }
                    self.layoutUITestDiagnosticView?.accessibilityValue =
                        "\(self.appliedTextOrientation.rawValue)|\(value)|\(progression)|\(mode)|\(variant)"
                    return
                }
            }

            guard let self,
                  self.layoutUITestDiagnosticSequence == sequence
            else { return }
            let progression =
                self.submittedPreferences.readingProgression?.rawValue ?? "auto"
            let mode = self.navigator.settings.scroll ? "scroll" : "paginated"
            self.layoutUITestDiagnosticView?.accessibilityValue =
                "\(self.appliedTextOrientation.rawValue)|unavailable|\(progression)|\(mode)"
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
        requestID: UUID
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

        let segment: ReaderSentenceSegment?
        switch quickTranslationUnit {
        case .sentence:
            let language = ReaderLanguageDetector.detect(
                text: paragraph,
                bookLanguage: bookLanguage
            )
            segment = ReaderSentenceSegmenter.sentence(
                in: paragraph,
                utf16Offset: offset,
                language: language
            )
        case .paragraph:
            segment = ReaderParagraphSegmenter.paragraph(
                in: paragraph,
                utf16Offset: offset
            )
        }
        guard let segment else {
            return nil
        }

        let highlightedRect: CGRect? = try? await Self.paintQuickSentenceHighlight(
            in: webView,
            at: localPoint,
            utf16Range: segment.utf16Range,
            requestToken: requestToken,
            vertical: rendersVerticalText,
            palette: selectionVisualPalette
        )
        guard isActiveQuickSentenceRequest(requestID) else {
            // A dismissed or superseded request can finish its WebKit call
            // after the newer request has begun. Remove only its own marker,
            // never the marker installed by the newer tap.
            await clearQuickSentenceHighlight(matching: requestToken)
            return nil
        }

        quickSentenceHighlightToken = highlightedRect == nil ? nil : requestToken
        quickSentenceHighlightHref = highlightedRect == nil
            ? nil
            : navigator.currentLocation?.href.string

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
                _ = takeQuickSentenceHighlight()
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
            trigger: .quickSentence
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

    /// One tile per contiguous run per line, via the shared Kotlin merger.
    ///
    /// The same call `EPUBSelectionBridge` makes for a manual selection. Both
    /// paths have to agree, because a reader who taps a sentence and a reader
    /// who drags across it are looking at the same sentence.
    static func merged(_ rects: [CGRect], vertical: Bool) -> [CGRect] {
        ReaderSelectionRectMerger.shared.merge(
            rects: rects.map(ReaderRect.from),
            mode: ReaderWritingMode.of(vertical: vertical)
        ).map(\.cgRect)
    }

    static func quickSentenceHitScript(
        at point: CGPoint,
        prefersNativeRubySelection: Bool = false,
        selectedText: String? = nil
    ) -> String {
        let x = String(
            format: "%.3f",
            locale: Locale(identifier: "en_US_POSIX"),
            point.x
        )
        let y = String(
            format: "%.3f",
            locale: Locale(identifier: "en_US_POSIX"),
            point.y
        )
        let prefersNativeSelection = prefersNativeRubySelection ? "true" : "false"
        let selectedTextJSON: String
        if let selectedText {
            selectedTextJSON = javaScriptString(selectedText)
        } else {
            selectedTextJSON = "null"
        }
        return """
        (() => {
          \(quickSentenceDOMHelpers)
          const hit = jerreaderReaderTextHit(
            \(x),
            \(y),
            \(prefersNativeSelection),
            \(selectedTextJSON)
          );
          if (!hit) return null;
          return {
            paragraph: hit.paragraph,
            offset: hit.offset,
            rect: jerreaderReaderRectJSON(hit.probeRect),
            rubyBase: hit.rubyBase,
            rubyAnnotation: hit.rubyAnnotation,
            rubyText: hit.rubyText,
            rubyBaseStart: hit.rubyBaseStart,
            rubyBaseEnd: hit.rubyBaseEnd
          };
        })()
        """
    }

    /// Reads the browser's current native selection through the same base-text
    /// node model used by quick sentence hit-testing and highlighting. The raw
    /// WebKit selection can still contain visible ruby annotations, but the
    /// normalized text, context and geometry never use rt/rp nodes.
    static let nativeSelectionSnapshotScript = #"""
    (() => {
      \#(quickSentenceDOMHelpers)
      const selection = globalThis.getSelection?.();
      const snapshot = jerreaderReaderBaseSelectionSnapshot(selection);
      if (!snapshot) return null;
      return {
        rawText: snapshot.rawText,
        normalizedText: snapshot.normalizedText,
        before: snapshot.before,
        after: snapshot.after,
        rects: snapshot.rects.map(jerreaderReaderRectJSON),
        rect: jerreaderReaderUnionRectJSON(snapshot.rects),
        rectCount: snapshot.rects.length,
        recoveredFromRuby: snapshot.recoveredFromRuby,
        anchorRole: jerreaderReaderNodeRole(selection.anchorNode),
        focusRole: jerreaderReaderNodeRole(selection.focusNode),
        anchorOffset: selection.anchorOffset,
        focusOffset: selection.focusOffset,
        layout: {
          scrollX: window.scrollX,
          scrollY: window.scrollY,
          innerWidth: window.innerWidth,
          innerHeight: window.innerHeight
        }
      };
    })()
    """#

    static func crossPageDocumentContextScript(sourceText: String) -> String {
        let sourceJSON = javaScriptString(sourceText)
        return """
        (() => {
          \(quickSentenceDOMHelpers)
          const source = (\(sourceJSON) || '')
            .replace(/\\s+/gu, ' ')
            .trim()
            .normalize('NFC');
          if (!source) return null;

          const normalize = (value) => (value || '')
            .replace(/\\s+/gu, ' ')
            .trim()
            .normalize('NFC');
          const root = document.body || document.documentElement;
          const nodes = jerreaderReaderTextNodes(root);
          let rawDocumentText = '';
          let previousBlock = null;
          nodes.forEach((node) => {
            const block = jerreaderReaderBlock(node.parentElement) || root;
            if (rawDocumentText && previousBlock && block !== previousBlock) {
              rawDocumentText += ' ';
            }
            rawDocumentText += node.data;
            previousBlock = block;
          });
          const documentText = normalize(rawDocumentText);
          if (!documentText) return null;

          const matches = [];
          let cursor = 0;
          while (cursor <= documentText.length - source.length) {
            const index = documentText.indexOf(source, cursor);
            if (index < 0) break;
            matches.push(index);
            cursor = index + Math.max(source.length, 1);
          }
          if (!matches.length) return null;
          const middle = documentText.length / 2;
          matches.sort(
            (left, right) => Math.abs(left - middle) - Math.abs(right - middle)
          );

          const selectedIndex = matches[0];
          const maximum = Math.max(1200, Math.min(source.length + 240, 2000));
          const remaining = Math.max(maximum - source.length, 0);
          let start = Math.max(0, selectedIndex - Math.floor(remaining / 2));
          let end = Math.min(
            documentText.length,
            selectedIndex + source.length + (remaining - Math.floor(remaining / 2))
          );
          if (end - start < maximum) {
            start = Math.max(0, start - (maximum - (end - start)));
            end = Math.min(documentText.length, start + maximum);
          }
          return documentText.slice(start, end);
        })()
        """
    }

    /// Recovers the base glyph under a Readium selection frame when WebKit has
    /// already collapsed its native Range. The helper deliberately returns
    /// only a ruby base segment; ordinary non-ruby selections continue to use
    /// Readium's own selection text and geometry.
    static func selectionPointSnapshotScript(
        at point: CGPoint,
        selectedText: String?
    ) -> String {
        let x = String(
            format: "%.3f",
            locale: Locale(identifier: "en_US_POSIX"),
            point.x
        )
        let y = String(
            format: "%.3f",
            locale: Locale(identifier: "en_US_POSIX"),
            point.y
        )
        let selectedTextJSON: String
        if let selectedText {
            selectedTextJSON = javaScriptString(selectedText)
        } else {
            selectedTextJSON = "null"
        }
        return """
        (() => {
          \(quickSentenceDOMHelpers)
          const hit = jerreaderReaderTextHit(\(x), \(y), true, \(selectedTextJSON));
          if (!hit || hit.rubyBaseStart == null || hit.rubyBaseEnd == null) {
            return null;
          }
          const start = jerreaderReaderBoundary(hit.nodes, hit.rubyBaseStart);
          const end = jerreaderReaderBoundary(hit.nodes, hit.rubyBaseEnd);
          if (!start || !end) return null;

          const range = document.createRange();
          range.setStart(start.node, start.offset);
          range.setEnd(end.node, end.offset);
          const subRanges = jerreaderReaderBaseSubRanges(range, hit.block);
          const rects = jerreaderReaderSelectionRects(subRanges);
          if (!rects.length) return null;

          const normalizedText = subRanges
            .map((subRange) => subRange.toString())
            .join('')
            .normalize('NFC');
          if (!normalizedText.trim()) return null;
          return {
            rawText: \(selectedTextJSON) || '',
            normalizedText,
            before: hit.paragraph.slice(0, hit.rubyBaseStart).normalize('NFC'),
            after: hit.paragraph.slice(hit.rubyBaseEnd).normalize('NFC'),
            rects: rects.map(jerreaderReaderRectJSON),
            rect: jerreaderReaderUnionRectJSON(rects),
            rectCount: rects.length,
            recoveredFromRuby: true,
            anchorRole: 'pointFallback',
            focusRole: 'rubyBase'
          };
        })()
        """
    }

    private static func javaScriptString(_ value: String) -> String {
        guard let data = try? JSONSerialization.data(
            withJSONObject: value,
            options: [.fragmentsAllowed]
        ),
        let json = String(data: data, encoding: .utf8)
        else {
            return "null"
        }
        return json
    }

    /// Measures the sentence's tiles and installs the (still empty) overlay.
    ///
    /// Measuring and painting are two calls on purpose. The raw
    /// `getClientRects()` output is one box per text node plus one per ruby
    /// annotation, so a sentence containing 翔太 with its readings came out as
    /// separate boxes with gaps between them and floating boxes above — the
    /// selection looked chopped up on exactly the books this reader exists for,
    /// while Android drew one continuous shape. Android's shape is the merged
    /// one: `ReaderSelectionRectMerger` groups the fragments into one tile per
    /// contiguous run per line. Rather than write a second merger in
    /// JavaScript — which is how the two readers diverged in the first place —
    /// the boxes come back to Swift, go through the same shared Kotlin merger
    /// the manual-selection path already uses, and return as tiles to paint.
    ///
    /// Nothing scrolls between the two calls (this path never touches the
    /// document selection), so client coordinates measured here are still valid
    /// when `quickSentencePaintScript` runs.
    static func quickSentenceMeasureScript(
        at point: CGPoint,
        utf16Range: Range<Int>,
        requestToken: String
    ) -> String {
        let x = String(
            format: "%.3f",
            locale: Locale(identifier: "en_US_POSIX"),
            point.x
        )
        let y = String(
            format: "%.3f",
            locale: Locale(identifier: "en_US_POSIX"),
            point.y
        )
        return """
        (() => {
          \(quickSentenceDOMHelpers)
          const hit = jerreaderReaderTextHit(\(x), \(y));
          if (!hit) return null;
          const start = jerreaderReaderBoundary(hit.nodes, \(utf16Range.lowerBound));
          const end = jerreaderReaderBoundary(hit.nodes, \(utf16Range.upperBound));
          if (!start || !end) return null;

          const range = document.createRange();
          range.setStart(start.node, start.offset);
          range.setEnd(end.node, end.offset);
          const baseRanges = jerreaderReaderBaseSubRanges(range);
          const rects = jerreaderReaderSelectionRects(baseRanges);
          if (!rects.length) return null;

          globalThis.CSS?.highlights?.delete('jerreader-reader-quick-sentence');
          document.getElementById('jerreader-reader-quick-sentence-highlight')?.remove();
          document.getElementById(
            'jerreader-reader-quick-sentence-speech-highlight'
          )?.remove();
          document.getElementById('jerreader-reader-quick-sentence-style')?.remove();
          globalThis.__jerreaderReaderQuickSentenceSelection = {
            requestToken: '\(requestToken)',
            nodes: hit.nodes,
            lowerBound: \(utf16Range.lowerBound),
            upperBound: \(utf16Range.upperBound)
          };

          // CSS custom highlights are deliberately not used here: WebKit
          // skips painting them on text inside <ruby>, which is exactly the
          // text this reader must highlight. The overlay uses absolute
          // document coordinates because a fixed child can be displaced by
          // EPUB column paging. Keep the established ruby-aware geometry and
          // change painting only: the selection now matches other formats
          // with a light fill and a restrained outline.
          const overlay = document.createElement('div');
          overlay.id = 'jerreader-reader-quick-sentence-highlight';
          overlay.dataset.jerreaderReaderRequest = '\(requestToken)';
          overlay.setAttribute('aria-hidden', 'true');
          overlay.style.cssText =
            'position:absolute;left:0;top:0;width:0;height:0;' +
            'pointer-events:none;z-index:2147483646;';
          (document.body || document.documentElement).appendChild(overlay);

          // The translation card must avoid the whole visible translation
          // target, not only the line nearest to the tap. This is especially
          // important in paragraph mode, where a nearest-line anchor would
          // cover the remaining selected lines.
          const left = Math.min(...rects.map((rect) => rect.left));
          const top = Math.min(...rects.map((rect) => rect.top));
          const right = Math.max(...rects.map((rect) => rect.right));
          const bottom = Math.max(...rects.map((rect) => rect.bottom));
          return {
            bounds: jerreaderReaderRectJSON({
              left,
              top,
              width: right - left,
              height: bottom - top
            }),
            tiles: rects.slice(0, 160).map(jerreaderReaderRectJSON)
          };
        })()
        """
    }

    /// Measure, merge, paint — the whole quick-sentence highlight against a web
    /// view, in the order the reader performs it.
    ///
    /// The merge sits in Swift between two JavaScript calls, so neither script
    /// on its own paints what the reader paints. Tests drive this instead of the
    /// scripts directly, which is what keeps them honest about the tiles that
    /// actually land on the page.
    ///
    /// - Returns: the bounding box of the highlighted sentence, or `nil` when
    ///   the point did not resolve to text.
    @discardableResult
    static func paintQuickSentenceHighlight(
        in webView: WKWebView,
        at point: CGPoint,
        utf16Range: Range<Int>,
        requestToken: String,
        vertical: Bool,
        palette: ReaderSelectionVisualPalette = .light
    ) async throws -> CGRect? {
        let measured = try await webView.evaluateJavaScript(
            quickSentenceMeasureScript(
                at: point,
                utf16Range: utf16Range,
                requestToken: requestToken
            )
        )
        guard let json = measured as? [String: Any],
              let bounds = rect(from: json["bounds"])
        else { return nil }

        let tiles = merged(
            (json["tiles"] as? [Any] ?? []).compactMap { rect(from: $0) },
            vertical: vertical
        )
        _ = try await webView.evaluateJavaScript(
            quickSentencePaintScript(
                tiles: tiles,
                requestToken: requestToken,
                palette: palette
            )
        )
        return bounds
    }

    /// Paints the merged tiles into the overlay `quickSentenceMeasureScript`
    /// installed. `tiles` are in client coordinates, as measured there.
    static func quickSentencePaintScript(
        tiles: [CGRect],
        requestToken: String,
        palette: ReaderSelectionVisualPalette = .light
    ) -> String {
        let format = { (value: CGFloat) -> String in
            String(format: "%.3f", locale: Locale(identifier: "en_US_POSIX"), value)
        }
        let json = tiles.map { tile in
            "{left:\(format(tile.minX)),top:\(format(tile.minY))," +
                "width:\(format(tile.width)),height:\(format(tile.height))}"
        }.joined(separator: ",")

        return """
        (() => {
          const overlay = document.getElementById(
            'jerreader-reader-quick-sentence-highlight'
          );
          if (!overlay ||
              overlay.dataset.jerreaderReaderRequest !== '\(requestToken)') {
            return false;
          }
          while (overlay.firstChild) overlay.removeChild(overlay.firstChild);

          // Position the markers against the overlay's *measured* origin
          // rather than `window.scrollX/scrollY`. Those two only describe the
          // containing block in an ordinary horizontal-tb, ltr document; in
          // `vertical-rl` — every Japanese book read in its original layout —
          // they do not, so every marker landed off-screen and tapping a
          // sentence produced no visible highlight at all while the
          // translation itself was correct. Measuring is writing-mode,
          // direction and pagination agnostic.
          const origin = overlay.getBoundingClientRect();
          // `!important` on the fill and the outline is load-bearing. ReadiumCSS
          // ships these, and every theme except the light one turns one on:
          //   :root[style*=readium-night-on] :not(a) {
          //     background-color: transparent !important;
          //     border-color: currentColor !important; }
          //   :root[style*=readium-sepia-on] :not(a) {
          //     background-color: transparent !important; }
          //   :root[style*="--USER__backgroundColor"] * {
          //     background-color: transparent !important; }
          // A marker is a <span> inside <body>, so all three match it and erase
          // the fill — which is why the selection colour read as broken on
          // sepia, cool grey, dark and every custom background while working on
          // light. One bug, and never in the colour maths. An important
          // declaration in a style attribute outranks one in a stylesheet.
          // Mirrored in `ReaderSelectionScripts.painter`.
          [\(json)].forEach((rect) => {
            const marker = document.createElement('span');
            marker.style.cssText =
              'position:absolute;pointer-events:none;box-sizing:border-box;' +
              'border-radius:' + \(Self.cornerRadiusExpression("rect.height")) + 'px;' +
              'border:\(Self.selectionStrokeWidthCSS)px solid \(palette.strokeCSS) !important;' +
              'background:\(palette.fillCSS) !important;' +
              `left:${rect.left - origin.left}px;` +
              `top:${rect.top - origin.top}px;` +
              `width:${rect.width}px;height:${rect.height}px;`;
            overlay.appendChild(marker);
          });
          return true;
        })()
        """
    }

    static func quickSentenceSpeechHighlightScript(
        _ highlight: ReaderSpeechHighlight?,
        matching requestToken: String,
        sequence: Int,
        palette: ReaderSelectionVisualPalette = .light
    ) -> String {
        let lowerBound = highlight.map {
            String(
                format: "%.8f",
                locale: Locale(identifier: "en_US_POSIX"),
                $0.lowerBound
            )
        } ?? "null"
        let upperBound = highlight.map {
            String(
                format: "%.8f",
                locale: Locale(identifier: "en_US_POSIX"),
                $0.upperBound
            )
        } ?? "null"
        let requestTokenJSON = javaScriptString(requestToken)

        return """
        (() => {
          \(quickSentenceDOMHelpers)
          const incomingSequence = \(sequence);
          const previousSequence = Number(
            globalThis.__jerreaderReaderQuickSentenceSpeechSequence ?? -1
          );
          if (incomingSequence < previousSequence) return false;
          globalThis.__jerreaderReaderQuickSentenceSpeechSequence = incomingSequence;

          const state = globalThis.__jerreaderReaderQuickSentenceSelection;
          const selectionOverlay = document.getElementById(
            'jerreader-reader-quick-sentence-highlight'
          );
          const spokenOverlayID =
            'jerreader-reader-quick-sentence-speech-highlight';
          const requestToken = \(requestTokenJSON);
          if (
            !state ||
            state.requestToken !== requestToken ||
            selectionOverlay?.dataset.jerreaderReaderRequest !== requestToken
          ) {
            return false;
          }

          document.getElementById(spokenOverlayID)?.remove();
          const normalizedLower = \(lowerBound);
          const normalizedUpper = \(upperBound);
          if (normalizedLower === null || normalizedUpper === null) {
            return true;
          }

          const selectionLength = Math.max(
            0,
            state.upperBound - state.lowerBound
          );
          if (!selectionLength) return false;
          const spokenStart = Math.min(
            state.upperBound,
            Math.max(
              state.lowerBound,
              state.lowerBound +
                Math.floor(selectionLength * normalizedLower)
            )
          );
          let spokenEnd = Math.min(
            state.upperBound,
            Math.max(
              spokenStart,
              state.lowerBound +
                Math.ceil(selectionLength * normalizedUpper)
            )
          );
          if (spokenEnd <= spokenStart) {
            spokenEnd = Math.min(state.upperBound, spokenStart + 1);
          }
          if (spokenEnd <= spokenStart) return false;

          const start = jerreaderReaderBoundary(state.nodes, spokenStart);
          const end = jerreaderReaderBoundary(state.nodes, spokenEnd);
          if (!start || !end) return false;
          const range = document.createRange();
          range.setStart(start.node, start.offset);
          range.setEnd(end.node, end.offset);
          const rects = jerreaderReaderSelectionRects(
            jerreaderReaderBaseSubRanges(range)
          );
          if (!rects.length) return false;

          const overlay = document.createElement('div');
          overlay.id = spokenOverlayID;
          overlay.dataset.jerreaderReaderRequest = requestToken;
          overlay.setAttribute('aria-hidden', 'true');
          overlay.style.cssText =
            'position:absolute;left:0;top:0;width:0;height:0;' +
            'pointer-events:none;z-index:2147483647;opacity:0.52;';
          (document.body || document.documentElement).appendChild(overlay);

          // Same measured origin as the sentence overlay; see the comment
          // there for why `window.scrollX/scrollY` cannot be used.
          const origin = overlay.getBoundingClientRect();
          rects.slice(0, 80).forEach((rect) => {
            const marker = document.createElement('span');
            marker.style.cssText =
              'position:absolute;pointer-events:none;' +
              'border-radius:' + \(Self.cornerRadiusExpression("rect.height")) + 'px;' +
              // Same ReadiumCSS override as the sentence overlay; see there.
              'background:\(palette.spokenFillCSS) !important;' +
              `left:${rect.left - origin.left}px;` +
              `top:${rect.top - origin.top}px;` +
              `width:${rect.width}px;height:${rect.height}px;`;
            overlay.appendChild(marker);
          });
          return true;
        })()
        """
    }

    private static func clearQuickSentenceHighlightScript(
        matching requestToken: String?
    ) -> String {
        let predicate = requestToken.map {
            """
            overlay?.dataset.jerreaderReaderRequest === '\($0)' ||
              state?.requestToken === '\($0)'
            """
        } ?? "true"
        return """
        (() => {
          const style = document.getElementById('jerreader-reader-quick-sentence-style');
          const overlay = document.getElementById('jerreader-reader-quick-sentence-highlight');
          const spokenOverlay = document.getElementById(
            'jerreader-reader-quick-sentence-speech-highlight'
          );
          const state = globalThis.__jerreaderReaderQuickSentenceSelection;
          if (\(predicate)) {
            globalThis.CSS?.highlights?.delete('jerreader-reader-quick-sentence');
            overlay?.remove();
            spokenOverlay?.remove();
            style?.remove();
            delete globalThis.__jerreaderReaderQuickSentenceSelection;
          }
          return true;
        })()
        """
    }

    /// Removes ruby annotation text from the selectable DOM text flow
    /// entirely. Each `rt`'s reading is moved into a `data-jerreader-rt` attribute
    /// and rendered through a `::before` pseudo-element, which WebKit lays out
    /// identically but can never include in a Range: annotation text stops
    /// existing as text nodes, so a long press over furigana snaps to the
    /// nearest real text — the kanji base — and the native highlight, drag
    /// handles and copied text all operate on base text by construction.
    ///
    /// Scope: this script only ever runs inside the EPUB reader, and the
    /// annotation detachment additionally requires Japanese content — the
    /// book's metadata language, the document's `lang`/`xml:lang`, or kana
    /// inside the annotations themselves. Other publications keep the plain
    /// CSS policy and an untouched DOM.
    ///
    /// There are deliberately no touch or selectionchange listeners here:
    /// native ranges and handles remain fully owned by Readium/WebKit.
    /// Supplies the horizontal writing mode that Readium alone cannot deliver.
    ///
    /// Readium switches between the `cjk-vertical` and `cjk-horizontal` Readium
    /// CSS variants, but only the vertical variant ever declares `writing-mode`
    /// — the horizontal variant contains no such rule at all. Selecting it
    /// therefore only *stops enforcing* vertical text; it never enforces
    /// horizontal text. A publication that pins its own writing mode (the
    /// common EBPAJ pattern `.vrtl { -webkit-writing-mode: vertical-rl }` on
    /// `<html class="vrtl">`) consequently stays vertical no matter which
    /// preference is submitted or how often the resource is reloaded. This
    /// policy provides the missing declaration.
    ///
    /// In `publication` mode nothing is declared, so the book's own layout and
    /// Readium's vertical variant stay in charge.
    /// - Parameter authoritative: `true` when the host is applying the mode it
    ///   currently wants (and should record it for documents that load later);
    ///   `false` for the document-start injection, whose baked value goes stale
    ///   as soon as the user switches and must defer to the recorded one.
    static func writingModePolicyScript(
        forcesHorizontal: Bool,
        authoritative: Bool = false
    ) -> String {
        let mode = forcesHorizontal ? "horizontal" : "publication"
        let isAuthoritative = authoritative ? "true" : "false"
        return #"""
        (() => {
          let mode = '\#(mode)';
          const authoritative = \#(isAuthoritative);
          try {
            if (authoritative) {
              globalThis.localStorage?.setItem('jerreaderReaderWritingMode', mode);
            } else {
              const stored = globalThis.localStorage?.getItem(
                'jerreaderReaderWritingMode'
              );
              if (stored === 'horizontal' || stored === 'publication') {
                mode = stored;
              }
            }
          } catch (_) {}
          if (globalThis.__jerreaderReaderWritingModePolicy === mode) return mode;

          const styleID = 'jerreader-reader-writing-mode-policy';
          let style = document.getElementById(styleID);
          if (!style) {
            style = document.createElement('style');
            style.id = styleID;
          }

          // `-epub-` and `-ms-` aliases are emitted next to the standard
          // property because Japanese publications still ship them, and a
          // publisher rule that only sets an alias must lose to this one.
          const horizontal =
            '-ms-writing-mode:lr-tb !important;' +
            '-epub-writing-mode:horizontal-tb !important;' +
            '-webkit-writing-mode:horizontal-tb !important;' +
            'writing-mode:horizontal-tb !important;';
          style.textContent = mode === 'horizontal'
            ? ':root,body{' + horizontal +
              '-webkit-text-orientation:mixed !important;' +
              'text-orientation:mixed !important;}' +
              '*{-webkit-writing-mode:horizontal-tb !important;' +
              'writing-mode:horizontal-tb !important;}'
            : '';

          const attachStyle = () => {
            if (style.isConnected) return true;
            const root = document.head || document.documentElement;
            if (!root) return false;
            root.appendChild(style);
            return true;
          };

          // A declaration in the style attribute outranks every author
          // stylesheet rule, including a publisher's own `!important`. Applying
          // it to the elements that actually carry the publication's writing
          // mode is what makes the switch deterministic instead of a
          // specificity race against arbitrary publisher CSS.
          const pin = (element) => {
            if (!element?.style) return;
            if (!element.hasAttribute('data-jerreader-writing-mode')) {
              element.dataset.jerreaderOriginalWritingMode =
                element.style.getPropertyValue('writing-mode');
              element.dataset.jerreaderOriginalWritingModePriority =
                element.style.getPropertyPriority('writing-mode');
              element.dataset.jerreaderOriginalWebkitWritingMode =
                element.style.getPropertyValue('-webkit-writing-mode');
              element.dataset.jerreaderOriginalWebkitWritingModePriority =
                element.style.getPropertyPriority('-webkit-writing-mode');
            }
            element.style.setProperty(
              'writing-mode', 'horizontal-tb', 'important'
            );
            element.style.setProperty(
              '-webkit-writing-mode', 'horizontal-tb', 'important'
            );
            element.dataset.jerreaderWritingMode = 'horizontal';
          };

          const unpin = (element) => {
            if (!element?.style) return;
            if (!element.hasAttribute('data-jerreader-writing-mode')) return;
            const writingMode = element.dataset.jerreaderOriginalWritingMode || '';
            const writingPriority =
              element.dataset.jerreaderOriginalWritingModePriority || '';
            const webkitWritingMode =
              element.dataset.jerreaderOriginalWebkitWritingMode || '';
            const webkitPriority =
              element.dataset.jerreaderOriginalWebkitWritingModePriority || '';
            if (writingMode) {
              element.style.setProperty(
                'writing-mode', writingMode, writingPriority
              );
            } else {
              element.style.removeProperty('writing-mode');
            }
            if (webkitWritingMode) {
              element.style.setProperty(
                '-webkit-writing-mode', webkitWritingMode, webkitPriority
              );
            } else {
              element.style.removeProperty('-webkit-writing-mode');
            }
            delete element.dataset.jerreaderWritingMode;
            delete element.dataset.jerreaderOriginalWritingMode;
            delete element.dataset.jerreaderOriginalWritingModePriority;
            delete element.dataset.jerreaderOriginalWebkitWritingMode;
            delete element.dataset.jerreaderOriginalWebkitWritingModePriority;
          };

          // The `*` rule above already outranks every ordinary publisher
          // declaration. Only a publisher's own `!important` can still win,
          // and only on the elements its selector matches — which may be any
          // element, not just one carrying a class or inline style. Detecting
          // whether such a rule exists at all is far cheaper than resolving
          // the computed style of every element, so the expensive sweep runs
          // only for the rare books that need it.
          const verticalValue = /vertical|tb-/i;
          const hasImportantVerticalRule = () => {
            const scan = (rules) => {
              for (const rule of rules) {
                const declaration = rule.style;
                if (declaration) {
                  const isImportant =
                    declaration.getPropertyPriority('writing-mode') ===
                      'important' ||
                    declaration.getPropertyPriority('-webkit-writing-mode') ===
                      'important';
                  if (isImportant && verticalValue.test(
                    declaration.getPropertyValue('writing-mode') +
                      declaration.getPropertyValue('-webkit-writing-mode')
                  )) {
                    return true;
                  }
                }
                // @media and @supports wrap their own rule lists.
                if (rule.cssRules && scan(rule.cssRules)) return true;
              }
              return false;
            };

            for (const sheet of Array.from(document.styleSheets)) {
              if (sheet.ownerNode === style) continue;
              try {
                if (sheet.cssRules && scan(sheet.cssRules)) return true;
              } catch (_) {
                // An unreadable stylesheet cannot be cleared, so assume the
                // worst and let the sweep decide from the computed styles.
                return true;
              }
            }
            return document.querySelector('[style*="writing-mode"]') !== null;
          };

          // The marker attribute is what makes the reverse switch exact.
          const sweep = () => {
            if (mode !== 'horizontal') {
              document
                .querySelectorAll('[data-jerreader-writing-mode]')
                .forEach(unpin);
              return;
            }
            if (!hasImportantVerticalRule()) return;
            const candidates = document.querySelectorAll('*');
            const limit = Math.min(candidates.length, 20000);
            for (let index = 0; index < limit; index += 1) {
              const element = candidates[index];
              const writingMode =
                getComputedStyle(element).writingMode || '';
              if (verticalValue.test(writingMode)) {
                pin(element);
              }
            }
          };

          const apply = () => {
            attachStyle();
            if (mode === 'horizontal') {
              pin(document.documentElement);
              pin(document.body);
            } else {
              unpin(document.documentElement);
              unpin(document.body);
            }
            sweep();
          };

          apply();
          // At document-start there is no <body> yet. Re-running once the
          // document is parsed covers the body and any wrapper element.
          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', apply, { once: true });
          }
          globalThis.__jerreaderReaderWritingModePolicy = mode;
          return mode;
        })()
        """#
    }

    static func selectionPolicyScript(detachRubyAnnotations: Bool) -> String {
        let bookFlag = detachRubyAnnotations ? "true" : "false"
        return #"""
        (() => {
          const styleID = 'jerreader-reader-selection-policy-v3';
          let style = document.getElementById(styleID);
          if (!style) {
            style = document.createElement('style');
            style.id = styleID;
          }
          // WebKit paints its own system-blue selection under our overlay, so
          // every custom selection colour was really the user's colour mixed
          // with stock blue — and while the handles were still settling, or
          // whenever the overlay had nothing to paint yet, it was plain blue.
          // Suppressing the stock paint leaves the overlay as the only thing
          // colouring a selection, which is what the setting promises. This is
          // injected at document start, so there is no blue frame to see first.
          // The Android reader has done the same since it shipped — see
          // `ReaderSelectionScripts.suppressStockSelectionPaint`.
          style.textContent =
            '::selection {' +
            'background: transparent !important;' +
            'color: inherit !important;' +
            '}' +
            '::-moz-selection {' +
            'background: transparent !important;' +
            'color: inherit !important;' +
            '}' +
            'rt, rp {' +
            '-webkit-user-select:none !important;' +
            'user-select:none !important;' +
            'pointer-events:none !important;' +
            '}' +
            'rt[data-jerreader-rt]::before {' +
            'content: attr(data-jerreader-rt);' +
            '}';

          const attachStyle = () => {
            if (style.isConnected) return true;
            const root = document.head || document.documentElement;
            if (!root) return false;
            root.appendChild(style);
            return true;
          };

          const bookPrefersDetachment = \#(bookFlag);
          const kanaPattern = /[ぁ-ゟ゠-ヿ]/;
          const documentPrefersDetachment = () => {
            const lang = (
              document.documentElement?.getAttribute('lang') ||
              document.documentElement?.getAttribute('xml:lang') ||
              ''
            ).toLowerCase();
            if (lang.startsWith('ja') || lang.startsWith('jpn')) return true;
            return Array.from(document.querySelectorAll('rt')).some(
              (annotation) =>
                kanaPattern.test(annotation.textContent || '') ||
                kanaPattern.test(annotation.dataset.jerreaderRt || '')
            );
          };

          // Moves an annotation's text into its data attribute. Appending to
          // any previously captured value keeps a reading intact even if its
          // text nodes arrive in separate mutation batches.
          const detachAnnotationText = (annotation) => {
            const key = annotation.localName === 'rp' ? 'jerreaderRp' : 'jerreaderRt';
            if (!annotation.firstChild) {
              if (annotation.dataset[key] == null) annotation.dataset[key] = '';
              return;
            }
            const incoming = (annotation.textContent || '')
              .replace(/\s+/gu, ' ')
              .trim();
            const existing = annotation.dataset[key] || '';
            annotation.dataset[key] = existing + incoming;
            while (annotation.firstChild) {
              annotation.removeChild(annotation.firstChild);
            }
          };

          const sweep = (root) => {
            if (!root || root.nodeType !== Node.ELEMENT_NODE) return;
            if (root.matches?.('rt,rp')) detachAnnotationText(root);
            root.querySelectorAll?.('rt,rp').forEach(detachAnnotationText);
          };

          // The sweep waits for the parsed document: processing a half-parsed
          // rt could capture only the first chunk of its reading, and the
          // Japanese-content check needs the annotations present. Until then
          // the rt still holds real text, which renders identically, and the
          // style above already keeps it unselectable.
          const ready = () => {
            attachStyle();
            if (!(bookPrefersDetachment || documentPrefersDetachment())) {
              return;
            }
            sweep(document.documentElement);
            if (!globalThis.__jerreaderReaderAnnotationObserver) {
              const observer = new MutationObserver((mutations) => {
                for (const mutation of mutations) {
                  const target = mutation.target;
                  const host = target.nodeType === Node.ELEMENT_NODE
                    ? target
                    : target.parentElement;
                  const annotation = host?.closest?.('rt,rp');
                  if (annotation) {
                    detachAnnotationText(annotation);
                    continue;
                  }
                  mutation.addedNodes.forEach((node) => sweep(node));
                }
              });
              observer.observe(document, { childList: true, subtree: true });
              globalThis.__jerreaderReaderAnnotationObserver = observer;
            }
          };

          attachStyle();
          if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', ready, { once: true });
          } else {
            ready();
          }
          globalThis.__jerreaderReaderSelectionBridgeVersion = 3;
          return true;
        })()
        """#
    }

    /// The selection stroke, formatted for CSS.
    ///
    /// Both the marker painted in the page and the tile drawn in Core Graphics
    /// have to be the same weight, so neither of them gets to name its own
    /// number — see `ReaderSelectionHighlightMetrics`.
    static var selectionStrokeWidthCSS: String {
        String(
            format: "%g",
            locale: Locale(identifier: "en_US_POSIX"),
            ReaderSelectionHighlightMetrics.shared.STROKE_WIDTH
        )
    }

    /// The shared corner rule as a JavaScript expression over `heightExpression`.
    static func cornerRadiusExpression(_ heightExpression: String) -> String {
        ReaderSelectionHighlightMetrics.shared.cssCornerRadiusExpression(
            heightExpression: heightExpression
        )
    }

    private static let quickSentenceDOMHelpers = #"""
    const JERREADER_READER_EXCLUDED_SELECTOR = [
      'rt', 'rp', 'script', 'style', 'noscript', 'svg',
      '[aria-hidden="true"]',
      '.reader-highlight-layer', '.reader-ui',
      '#jerreader-reader-quick-sentence-highlight',
      '#jerreader-reader-quick-sentence-style',
      '#jerreader-reader-selection-policy-v3'
    ].join(',');

    function jerreaderReaderRectJSON(rect) {
      return { x: rect.left, y: rect.top, width: rect.width, height: rect.height };
    }

    function jerreaderReaderUnionRectJSON(rects) {
      if (!rects?.length) return null;
      const left = Math.min(...rects.map((rect) => rect.left));
      const top = Math.min(...rects.map((rect) => rect.top));
      const right = Math.max(...rects.map((rect) => rect.right));
      const bottom = Math.max(...rects.map((rect) => rect.bottom));
      if (![left, top, right, bottom].every(Number.isFinite) ||
          right <= left || bottom <= top) return null;
      return { x: left, y: top, width: right - left, height: bottom - top };
    }

    function jerreaderReaderIsBaseTextNode(node) {
      if (!node || node.nodeType !== Node.TEXT_NODE || !node.data) return false;
      const parent = node.parentElement;
      if (!parent || parent.closest(JERREADER_READER_EXCLUDED_SELECTOR)) return false;
      const style = getComputedStyle(parent);
      return style.display !== 'none' &&
        style.visibility !== 'hidden' &&
        style.opacity !== '0';
    }

    function jerreaderReaderNodeRole(node) {
      if (!node) return 'missing';
      const element = node.nodeType === Node.ELEMENT_NODE
        ? node
        : node.parentElement;
      if (element?.closest?.('rt,rp')) return 'rubyAnnotation';
      if (node.nodeType === Node.TEXT_NODE && jerreaderReaderIsBaseTextNode(node)) {
        return element?.closest?.('ruby') ? 'rubyBase' : 'baseText';
      }
      if (element?.closest?.(JERREADER_READER_EXCLUDED_SELECTOR)) return 'excluded';
      return 'container';
    }

    function jerreaderReaderTextNodes(block) {
      const nodes = [];
      const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT, {
        acceptNode(node) {
          return jerreaderReaderIsBaseTextNode(node)
            ? NodeFilter.FILTER_ACCEPT
            : NodeFilter.FILTER_REJECT;
        }
      });
      while (walker.nextNode()) nodes.push(walker.currentNode);
      return nodes;
    }

    function jerreaderReaderBaseSubRanges(range, root = document.body || document.documentElement) {
      if (!range || !root) return [];
      const subRanges = [];
      const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
        acceptNode(node) {
          if (!jerreaderReaderIsBaseTextNode(node)) return NodeFilter.FILTER_REJECT;
          try {
            return range.intersectsNode(node)
              ? NodeFilter.FILTER_ACCEPT
              : NodeFilter.FILTER_REJECT;
          } catch (_) {
            return NodeFilter.FILTER_REJECT;
          }
        }
      });

      while (walker.nextNode()) {
        const node = walker.currentNode;
        const subRange = document.createRange();
        subRange.selectNodeContents(node);

        if (range.startContainer === node) {
          subRange.setStart(node, Math.min(range.startOffset, node.data.length));
        }
        if (range.endContainer === node) {
          subRange.setEnd(node, Math.min(range.endOffset, node.data.length));
        }

        // `intersectsNode` above already applies DOM tree order, including
        // element-boundary selections such as selectNodeContents(ruby).
        // Comparing differently rooted boundary-point pairs here reverses the
        // operands in WebKit and can reject every node in a multi-ruby range.
        if (subRange.collapsed) {
          continue;
        }
        subRanges.push(subRange);
      }
      return subRanges;
    }

    function jerreaderReaderRubyRecoveryRanges(selection, range, existingRanges) {
      const annotations = new Set();
      const addAnnotationForNode = (node) => {
        const annotation = jerreaderReaderAnnotationForNode(node);
        if (annotation) annotations.add(annotation);
      };
      addAnnotationForNode(selection?.anchorNode);
      addAnnotationForNode(selection?.focusNode);

      // Some WebKit ruby ranges expose the <ruby> container as both endpoints
      // even though only its <rt> text is selected. Limit this wider scan to
      // the no-base case so an ordinary multi-word selection is never grown.
      if (!annotations.size && !existingRanges.length) {
        document.querySelectorAll('rt,rp').forEach((annotation) => {
          try {
            if (range.intersectsNode(annotation)) annotations.add(annotation);
          } catch (_) {}
        });
      }

      const recovered = [];
      const recoveredNodes = new Set();
      annotations.forEach((annotation) => {
        jerreaderReaderRubyBaseNodes(annotation).forEach((node) => {
          if (!node?.data?.length || recoveredNodes.has(node)) return;
          const alreadyCovered = existingRanges.some((candidate) => {
            try { return candidate.intersectsNode(node); } catch (_) { return false; }
          });
          if (alreadyCovered) return;
          recoveredNodes.add(node);
          const baseRange = document.createRange();
          baseRange.selectNodeContents(node);
          recovered.push(baseRange);
        });
      });
      return recovered;
    }

    function jerreaderReaderOrderedBaseRanges(ranges) {
      return ranges.slice().sort((left, right) => {
        try {
          return left.compareBoundaryPoints(Range.START_TO_START, right);
        } catch (_) {
          return 0;
        }
      });
    }

    function jerreaderReaderBaseSelectionSnapshot(selection) {
      if (!selection || selection.rangeCount === 0 || selection.isCollapsed) return null;
      const range = selection.getRangeAt(0).cloneRange();
      const nativeBaseRanges = jerreaderReaderBaseSubRanges(range);
      const recoveredRanges = jerreaderReaderRubyRecoveryRanges(
        selection,
        range,
        nativeBaseRanges
      );
      const subRanges = jerreaderReaderOrderedBaseRanges([
        ...nativeBaseRanges,
        ...recoveredRanges
      ]);
      if (!subRanges.length) return null;

      const normalizedText = subRanges
        .map((subRange) => subRange.toString())
        .join('')
        .normalize('NFC');
      if (!normalizedText.trim()) return null;

      const rects = jerreaderReaderSelectionRects(subRanges);

      let before = null;
      let after = null;
      const startElement = range.startContainer.nodeType === Node.ELEMENT_NODE
        ? range.startContainer
        : range.startContainer.parentElement;
      const endElement = range.endContainer.nodeType === Node.ELEMENT_NODE
        ? range.endContainer
        : range.endContainer.parentElement;
      const block = jerreaderReaderBlock(startElement);
      if (block && (endElement === block || block.contains(endElement))) {
        const blockNodes = jerreaderReaderTextNodes(block);
        const segmentByNode = new Map();
        subRanges.forEach((subRange) => {
          if (subRange.startContainer === subRange.endContainer &&
              subRange.startContainer.nodeType === Node.TEXT_NODE) {
            segmentByNode.set(subRange.startContainer, {
              start: subRange.startOffset,
              end: subRange.endOffset
            });
          }
        });
        let paragraph = '';
        let selectionStart = null;
        let selectionEnd = null;
        blockNodes.forEach((node) => {
          const segment = segmentByNode.get(node);
          if (segment) {
            if (selectionStart === null) selectionStart = paragraph.length + segment.start;
            selectionEnd = paragraph.length + segment.end;
          }
          paragraph += node.data;
        });
        if (selectionStart !== null && selectionEnd !== null) {
          before = paragraph.slice(0, selectionStart).normalize('NFC');
          after = paragraph.slice(selectionEnd).normalize('NFC');
        }
      }

      return {
        // `Selection.toString()` intentionally hides text styled with
        // user-select:none. Reading the cloned Range instead keeps the
        // diagnostic value useful when WebKit left an annotation endpoint,
        // without mutating the browser-owned selection or its handles.
        rawText: range.toString(),
        normalizedText,
        before,
        after,
        rects,
        recoveredFromRuby: recoveredRanges.length > 0
      };
    }

    function jerreaderReaderBlock(element) {
      if (!element) return null;
      const explicit = element.closest(
        'p,li,blockquote,dd,dt,figcaption,td,th,h1,h2,h3,h4,h5,h6,pre'
      );
      if (explicit) return explicit;

      let candidate = element;
      while (candidate && candidate !== document.body && candidate !== document.documentElement) {
        const display = getComputedStyle(candidate).display;
        if (['block', 'list-item', 'table-cell', 'flex', 'grid'].includes(display) &&
            candidate.textContent && candidate.textContent.trim()) {
          return candidate;
        }
        candidate = candidate.parentElement;
      }
      return null;
    }

    function jerreaderReaderCaretRange(x, y) {
      if (document.caretRangeFromPoint) return document.caretRangeFromPoint(x, y);
      if (!document.caretPositionFromPoint) return null;
      const position = document.caretPositionFromPoint(x, y);
      if (!position) return null;
      const range = document.createRange();
      range.setStart(position.offsetNode, position.offset);
      range.collapse(true);
      return range;
    }

    function jerreaderReaderNearestRect(rects, x, y) {
      let nearest = null;
      let bestDistance = Number.POSITIVE_INFINITY;
      rects.forEach((rect) => {
        const dx = Math.max(rect.left - x, 0, x - rect.right);
        const dy = Math.max(rect.top - y, 0, y - rect.bottom);
        const distance = dx * dx + dy * dy;
        if (distance < bestDistance) {
          bestDistance = distance;
          nearest = rect;
        }
      });
      return bestDistance <= 784 ? nearest : null;
    }

    function jerreaderReaderAnnotationForNode(node) {
      const element = node?.nodeType === Node.ELEMENT_NODE
        ? node
        : node?.parentElement;
      return element?.closest?.('rt,rp') || null;
    }

    function jerreaderReaderNormalizedRubyText(text) {
      return (text || '').replace(/\s+/gu, '');
    }

    function jerreaderReaderRubyAnnotationAtPoint(x, y, selectedText) {
      const selected = jerreaderReaderNormalizedRubyText(selectedText);
      let best = null;
      let bestDistance = Number.POSITIVE_INFINITY;
      document.querySelectorAll('rt,rp').forEach((candidate) => {
        const annotation = jerreaderReaderNormalizedRubyText(candidate.textContent);
        if (selected &&
            !annotation.includes(selected) &&
            !selected.includes(annotation)) {
          return;
        }
        Array.from(candidate.getClientRects()).forEach((rect) => {
          if (!rect.width || !rect.height ||
              rect.right < 0 || rect.bottom < 0 ||
              rect.left > window.innerWidth || rect.top > window.innerHeight) {
            return;
          }
          const dx = Math.max(rect.left - x, 0, x - rect.right);
          const dy = Math.max(rect.top - y, 0, y - rect.bottom);
          const distance = dx * dx + dy * dy;
          const allowedDistance = selected ? 1600 : 16;
          if (distance <= allowedDistance && distance < bestDistance) {
            bestDistance = distance;
            best = candidate;
          }
        });
      });
      return best;
    }

    function jerreaderReaderRubyBaseNodes(annotation) {
      const ruby = annotation?.closest?.('ruby');
      if (!ruby) return [];
      const directChild = Array.from(ruby.childNodes).find(
        (child) => child === annotation || child.contains?.(annotation)
      );
      const children = Array.from(ruby.childNodes);
      const annotationIndex = children.indexOf(directChild);
      let segmentStart = 0;
      for (let index = annotationIndex - 1; index >= 0; index -= 1) {
        const child = children[index];
        const element = child.nodeType === Node.ELEMENT_NODE ? child : null;
        if (element?.matches?.('rt,rp') || element?.querySelector?.('rt,rp')) {
          segmentStart = index + 1;
          break;
        }
      }
      const roots = annotationIndex > 0
        ? children.slice(segmentStart, annotationIndex)
        : children;
      const nodes = [];
      roots.forEach((root) => {
        if (root.nodeType === Node.TEXT_NODE) {
          if (root.data?.trim()) nodes.push(root);
          return;
        }
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
          acceptNode(node) {
            return node.parentElement?.closest?.('rt,rp')
              ? NodeFilter.FILTER_REJECT
              : NodeFilter.FILTER_ACCEPT;
          }
        });
        while (walker.nextNode()) {
          if (walker.currentNode.data?.trim()) nodes.push(walker.currentNode);
        }
      });
      return nodes.length ? nodes : jerreaderReaderTextNodes(ruby);
    }

    function jerreaderReaderRubyBase(
      caretNode,
      x,
      y,
      preferNativeSelection,
      selectedText
    ) {
      let annotation = null;
      if (preferNativeSelection) {
        const selection = globalThis.getSelection?.();
        const selectedNodes = [selection?.anchorNode, selection?.focusNode];
        for (const selectedNode of selectedNodes) {
          annotation = jerreaderReaderAnnotationForNode(selectedNode);
          if (annotation) break;
        }
      }

      annotation = annotation || jerreaderReaderRubyAnnotationAtPoint(
        x,
        y,
        selectedText
      );

      const caretElement = caretNode.nodeType === Node.ELEMENT_NODE
        ? caretNode
        : caretNode.parentElement;
      annotation = annotation || caretElement?.closest('rt,rp');
      if (!annotation && typeof document.elementsFromPoint === 'function') {
        for (const element of document.elementsFromPoint(x, y)) {
          annotation = element?.closest?.('rt,rp');
          if (annotation) break;
        }
      }
      let ruby = annotation?.closest('ruby') || caretElement?.closest?.('ruby');
      if (!ruby) return null;

      // With annotation hit-testing disabled, caretRangeFromPoint correctly
      // lands on the visible base glyph. Re-associate that base node with the
      // nearest annotation so the same mapping works whether Readium reported
      // the reading or the kanji.
      annotation = annotation || ruby.querySelector('rt,rp');
      if (!annotation) return null;

      const baseNodes = jerreaderReaderRubyBaseNodes(annotation);
      if (!baseNodes.length) return null;
      const allBaseNodes = jerreaderReaderTextNodes(ruby);
      if (!allBaseNodes.length) return null;
      let bestNode = null;
      let bestDistance = Number.POSITIVE_INFINITY;
      baseNodes.forEach((node) => {
        const range = document.createRange();
        range.selectNodeContents(node);
        Array.from(range.getClientRects()).forEach((rect) => {
          const dx = Math.max(rect.left - x, 0, x - rect.right);
          const dy = Math.max(rect.top - y, 0, y - rect.bottom);
          const distance = dx * dx + dy * dy;
          if (distance < bestDistance) {
            bestDistance = distance;
            bestNode = node;
          }
        });
      });

      const node = bestNode || baseNodes[0];
      return {
        node,
        offset: Math.floor(node.data.length / 2),
        ruby,
        baseNodes,
        allBaseNodes,
        annotationText: annotation.textContent || '',
        rubyText: ruby.textContent || ''
      };
    }

    function jerreaderReaderTextHit(
      x,
      y,
      preferNativeRubySelection = false,
      selectedText = null
    ) {
      if (!Number.isFinite(x) || !Number.isFinite(y) ||
          x < 0 || y < 0 || x > window.innerWidth || y > window.innerHeight) {
        return null;
      }
      const caret = jerreaderReaderCaretRange(x, y);
      if (!caret) return null;
      const rubyResolution = jerreaderReaderRubyBase(
        caret.startContainer,
        x,
        y,
        preferNativeRubySelection,
        selectedText
      );
      const caretNode = rubyResolution?.node || caret.startContainer;
      const caretOffset = rubyResolution?.offset ?? caret.startOffset;
      const element = caretNode.nodeType === Node.ELEMENT_NODE
        ? caretNode
        : caretNode.parentElement;
      const block = jerreaderReaderBlock(element);
      if (!block) return null;

      const nodes = jerreaderReaderTextNodes(block);
      if (!nodes.length) return null;
      let paragraph = '';
      let offset = 0;
      let foundCaret = false;
      let rubyBaseStart = -1;
      let rubyBaseEnd = -1;
      nodes.forEach((node) => {
        if (rubyResolution?.allBaseNodes.includes(node)) {
          if (rubyBaseStart < 0) rubyBaseStart = paragraph.length;
          rubyBaseEnd = paragraph.length + node.data.length;
        }
        if (!foundCaret && node === caretNode) {
          offset = paragraph.length + Math.min(caretOffset, node.data.length);
          foundCaret = true;
        }
        paragraph += node.data;
      });
      if (!foundCaret || !paragraph.trim() || paragraph.length > 50000) return null;

      let probeNode = caretNode;
      if (probeNode.nodeType !== Node.TEXT_NODE || !probeNode.data) return null;
      let probeOffset = Math.min(caretOffset, probeNode.data.length);
      if (probeOffset >= probeNode.data.length) probeOffset = Math.max(0, probeOffset - 1);
      if (!probeNode.data.length) return null;
      const probe = document.createRange();
      probe.setStart(probeNode, probeOffset);
      probe.setEnd(probeNode, Math.min(probeOffset + 1, probeNode.data.length));
      const probeRect = jerreaderReaderNearestRect(Array.from(probe.getClientRects()), x, y);
      if (!probeRect) return null;

      const rubyBase = rubyBaseStart >= 0 && rubyBaseEnd > rubyBaseStart
        ? paragraph.slice(rubyBaseStart, rubyBaseEnd)
        : null;
      return {
        block,
        nodes,
        paragraph,
        offset,
        probeRect,
        rubyBase,
        rubyAnnotation: rubyResolution?.annotationText || null,
        rubyText: rubyResolution?.rubyText || null,
        rubyBaseStart: rubyBase ? rubyBaseStart : null,
        rubyBaseEnd: rubyBase ? rubyBaseEnd : null
      };
    }

    function jerreaderReaderBoundary(nodes, requestedOffset) {
      const offset = Math.max(0, requestedOffset);
      let consumed = 0;
      for (const node of nodes) {
        const next = consumed + node.data.length;
        if (offset <= next) {
          return { node, offset: Math.min(offset - consumed, node.data.length) };
        }
        consumed = next;
      }
      const last = nodes[nodes.length - 1];
      return last ? { node: last, offset: last.data.length } : null;
    }

    function jerreaderReaderViewportRectFilter(rect) {
      return rect.width > 0.5 && rect.height > 0.5 &&
        rect.right > 0 && rect.bottom > 0 &&
        rect.left < window.innerWidth && rect.top < window.innerHeight;
    }

    // Finds the rt annotation paired with a base text node inside interleaved
    // mono ruby (base, rt, base, rt, ...): the first rt sibling that follows
    // the node's top-level child within the ruby element.
    function jerreaderReaderPairedAnnotation(node) {
      const parent = node?.nodeType === Node.TEXT_NODE ? node.parentElement : node;
      const ruby = parent?.closest?.('ruby');
      if (!ruby) return null;
      let top = node;
      while (top && top.parentNode && top.parentNode !== ruby) {
        top = top.parentNode;
      }
      let sibling = top?.nextSibling;
      while (sibling) {
        if (sibling.nodeType === Node.ELEMENT_NODE) {
          if (sibling.matches('rt')) return sibling;
          if (!sibling.matches('rp')) break;
        } else if (sibling.nodeType === Node.TEXT_NODE && sibling.data?.trim()) {
          break;
        }
        sibling = sibling.nextSibling;
      }
      return ruby.querySelector('rt');
    }

    // Selection geometry = base-text rectangles plus the rectangles of each
    // paired ruby annotation. The reading is not selectable text, but the
    // visible highlight must cover the whole ruby unit so a selected kanji
    // never looks "skipped" under its furigana.
    function jerreaderReaderSelectionRects(subRanges) {
      const rects = [];
      const annotations = new Set();
      subRanges.forEach((subRange) => {
        rects.push(...Array.from(subRange.getClientRects()));
        const annotation = jerreaderReaderPairedAnnotation(subRange.startContainer);
        if (annotation && !annotations.has(annotation)) {
          annotations.add(annotation);
          rects.push(...Array.from(annotation.getClientRects()));
        }
      });
      return rects.filter(jerreaderReaderViewportRectFilter);
    }
    """#

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

            measureVerticalColumns(in: webView)
            pinVerticalContentOffset(in: scrollView)
            observeVerticalContentOffset(in: webView, scrollView: scrollView)

            // The vertical-text page turn. Readium keeps this spread scrolling
            // (see `usesVerticalPageSnapping`), so the page turn is applied to
            // the scroll view instead.
            //
            // Not with `isPagingEnabled`, which was the first attempt: it snaps
            // to multiples of the viewport measured from the content origin,
            // and a page of columns is deliberately not a viewport — it holds
            // one column back so nothing is split at the boundary. Paging the
            // scroll view therefore swallowed the column straddling every
            // viewport edge, covered on one page and covered on the next.
            // `installVerticalPagingDelegate` snaps a drag to a real page.
            //
            // Turning it off is only ours to do for that spread. **Horizontal
            // pagination is `isPagingEnabled`** — `EPUBReflowableSpreadView`
            // sets `scrollView.isPagingEnabled = !viewModel.scroll` in
            // `setupWebView()` and again in `applySettings()`, and that is the
            // whole of Readium's page snapping there. Clearing it on every
            // layout pass, for every web view, is what left 横排「逐页」
            // scrolling freely and resting between pages: removing our own
            // delegate proxy handed the drag back to Readium, but Readium had
            // nothing left to snap with. Restore its own answer instead.
            // The user's selected mode is authoritative here. Readium's
            // `settings.scroll` is forced true for vertical text and can also
            // retain its previous value while an orientation reload settles;
            // using it here is what let 横排「逐页」coast continuously.
            scrollView.isPagingEnabled = pagingPolicy.usesNativePaging
            scrollView.decelerationRate = usesVerticalPageSnapping
                ? .fast
                : .normal
            if usesVerticalPageSnapping {
                installVerticalPagingDelegate(in: webView, scrollView: scrollView)
            } else {
                // The other half of the same 横排「逐页」 report. The proxy
                // answers `scrollViewWillEndDragging` unconditionally and
                // resolves the target against *vertical* column pages, so left
                // installed on a horizontal spread it either pinned every drag
                // back to where it started or sent it to an offset that is not
                // a page boundary. Both fixes are needed: this hands the drag
                // back to Readium, and the line above gives Readium the snap to
                // finish it with.
                removeVerticalPagingDelegate(from: scrollView)
            }

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
        updateVerticalGutters()
    }

    /// Puts a page-turn snap in front of Readium's own scroll-view delegate.
    ///
    /// Readium re-assigns the delegate in `didMoveToSuperview`, so this checks
    /// on every layout pass and steps back in front when it has been displaced,
    /// keeping whatever it displaced as the forwarding target.
    private func installVerticalPagingDelegate(in webView: WKWebView, scrollView: UIScrollView) {
        let existing = verticalPagingDelegates.object(forKey: scrollView)
        if let existing, scrollView.delegate === existing { return }

        let proxy = existing ?? EPUBVerticalPagingScrollDelegate()
        if let displaced = scrollView.delegate, displaced !== proxy {
            proxy.forwarding = displaced
        }
        proxy.onDragBegin = { [weak self] in
            self?.pendingVerticalPageTarget = nil
            self?.updateVerticalGutters()
        }
        proxy.pageTarget = { [weak self, weak webView] _, origin, movesLeft in
            guard let self, let webView else { return nil }
            let target = self.verticalPageTarget(in: webView, from: origin, movesLeft: movesLeft)
            // The scroll view is about to coast there on its own, so the
            // gutters should already be the ones that page will have.
            self.pendingVerticalPageTarget = target
            self.updateVerticalGutters()
            return target
        }
        verticalPagingDelegates.setObject(proxy, forKey: scrollView)
        scrollView.delegate = proxy
    }

    /// Hands the scroll view back to Readium.
    ///
    /// Called on every layout pass in which the spread is not vertically
    /// snapped, so switching a Japanese book to 横排 — which reuses the same
    /// WKWebView — puts Readium's own spread view back in charge of where a
    /// drag ends. Without this the proxy outlived the mode that needed it.
    private func removeVerticalPagingDelegate(from scrollView: UIScrollView) {
        guard let proxy = verticalPagingDelegates.object(forKey: scrollView) else { return }
        if scrollView.delegate === proxy {
            scrollView.delegate = proxy.forwarding
        }
        proxy.pageTarget = nil
        proxy.onDragBegin = nil
        proxy.forwarding = nil
        verticalPagingDelegates.removeObject(forKey: scrollView)
        pendingVerticalPageTarget = nil
    }

    /// Paints over the part-columns clipped by the two edges of the page.
    ///
    /// While a page turn is animating the gutters show the page being arrived
    /// at rather than the offsets it passes through: the widths change
    /// continuously in between, and following them would be a flicker down both
    /// edges of every turn. A drag is different — the reader is moving the page
    /// themselves, and the gutters follow their finger.
    private func updateVerticalGutters() {
        guard usesVerticalPageSnapping,
              let webView = visibleContentWebViews().first,
              let metrics = verticalColumnMetrics.object(forKey: webView),
              let grid = metrics.grid,
              grid.isUsable
        else {
            verticalGutterOverlay.hideGutters()
            return
        }
        let scrollView = webView.scrollView
        let width = Double(scrollView.bounds.width)
        guard width > 1 else {
            verticalGutterOverlay.hideGutters()
            return
        }
        let live = scrollView.contentOffset.x
        if abs(live - (pendingVerticalPageTarget ?? live)) <= 0.5 {
            pendingVerticalPageTarget = nil
        }
        let offset = Double(pendingVerticalPageTarget ?? live)
        let page = webView.convert(webView.bounds, to: view)
        verticalGutterOverlay.cover(
            page: page,
            leading: CGFloat(grid.leadingGutterWidth(offset: offset, viewportWidth: width)),
            trailing: CGFloat(grid.trailingGutterWidth(offset: offset, viewportWidth: width)),
            // The paper the document itself declares. A theme colour would be
            // one guess too many: a publication stylesheet can set its own
            // background, and a gutter in the wrong colour is a bar of paint
            // down the edge of the page.
            color: metrics.pageColor ?? webView.backgroundColor ?? view.backgroundColor ?? .clear
        )
        view.bringSubviewToFront(verticalGutterOverlay)
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
    private func observeVerticalContentOffset(in webView: WKWebView, scrollView: UIScrollView) {
        guard verticalOffsetObservations.object(forKey: scrollView) == nil else { return }
        // Both keys matter: UIKit's displacement is a `contentOffset` write, but
        // it can land while WebKit is still measuring, and the relayout that
        // follows arrives only as a `contentSize` change.
        let correct: @Sendable (UIScrollView, Any) -> Void = { [weak self] scrollView, _ in
            MainActor.assumeIsolated {
                // Re-entrant by construction: the correction is itself a
                // `contentOffset` write. `pinVerticalContentOffset` returns
                // immediately once the offset is zero, so it settles in one step.
                self?.pinVerticalContentOffset(in: scrollView)
                // The gutters describe where the page *is*, so they follow
                // every offset the spread takes — a drag, a bookmark, or
                // Readium's own progression scroll, none of which come through
                // the page-turn path.
                self?.updateVerticalGutters()
            }
        }
        let observations = ReaderScrollViewObservations(
            offset: scrollView.observe(\.contentOffset, options: [.new]) { view, change in
                correct(view, change)
            },
            size: scrollView.observe(\.contentSize, options: [.new]) { [weak self, weak webView] view, change in
                correct(view, change)
                // A content size change is WebKit reporting that it has laid
                // the document out. Until then there are no line boxes to
                // measure, and the column advance comes back empty — this is
                // the signal that it is worth asking again.
                MainActor.assumeIsolated {
                    guard let webView else { return }
                    self?.measureVerticalColumns(in: webView)
                }
            }
        )
        verticalOffsetObservations.setObject(observations, forKey: scrollView)
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
