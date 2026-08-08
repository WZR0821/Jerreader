import Combine
import Foundation
@preconcurrency import ReadiumNavigator
@preconcurrency import ReadiumShared
import SwiftData
@preconcurrency import Translation

@MainActor
final class EPUBReaderViewModel: ObservableObject {
    @Published private(set) var loadState: ReaderLoadState = .loading
    @Published private(set) var controller: (any PublicationReaderController)?
    @Published private(set) var outline: [ReaderOutlineItem] = []
    @Published private(set) var chapterTitle: String
    @Published private(set) var progress: Double = 0
    @Published private(set) var bookmarks: [ReadingBookmarkRecord] = []
    @Published private(set) var annotations: [ReadingAnnotationRecord] = []
    @Published private(set) var isCurrentLocationBookmarked = false
    @Published private(set) var searchResults: [ReaderSearchResult] = []
    @Published private(set) var isSearching = false
    @Published private(set) var searchErrorMessage: String?
    @Published private(set) var lastSearchQuery = ""
    @Published var controlsVisible = true
    @Published var readerAlertMessage: String?
    @Published private(set) var readerActivityMessage: String?

    @Published private(set) var fontSize: Double
    @Published private(set) var theme: ReaderThemeChoice
    @Published private(set) var fontChoice: ReaderFontChoice
    @Published private(set) var lineHeight: Double
    @Published private(set) var paragraphSpacing: Double
    @Published private(set) var pageMargins: Double
    @Published private(set) var customBackgroundHex: String
    @Published private(set) var customSelectionColorHex: String
    @Published private(set) var textOrientation: ReaderTextOrientationChoice
    @Published private(set) var readingMode: ReaderReadingMode
    @Published private(set) var publicationLayout: ReaderPublicationLayout
    @Published private(set) var isPageLocked = true

    @Published private(set) var translationConfiguration: TranslationSession.Configuration?
    @Published private(set) var translationState: ReaderTranslationState = .idle
    @Published private(set) var translationAnchorFrame: CGRect?
    @Published private(set) var isSpeakingTranslationSource = false
    @Published private(set) var speechRate: Double
    @Published private(set) var isCurrentTranslationFavorite = false
    @Published private(set) var isCurrentSelectionInVocabulary = false
    @Published private(set) var isAddingCurrentSelectionToVocabulary = false
    @Published private(set) var isExpandingCrossPageTranslation = false
    @Published private(set) var isTranslationRetryCoolingDown = false
    @Published private(set) var isCurrentSelectionAnnotated = false
    @Published var presentedAnnotationEditor: ReaderAnnotationEditorDraft?
    @Published private(set) var contextExplanationState: ReaderContextExplanationState = .idle
    @Published var isShowingContextExplanation = false
    @Published private(set) var isQuickSentenceTranslationEnabled: Bool
    @Published private(set) var isQuickTranslationIndicatorVisible: Bool
    @Published private(set) var quickTranslationUnit: ReaderQuickTranslationUnit
    @Published private(set) var disablesTapPageTurnsDuringQuickTranslation: Bool
    @Published private(set) var isPDFPaperModeEnabled: Bool

    let book: BookRecord
    let translationSettings: TranslationSettingsStore
    static let maxTranslationCharacterCount = 2_000

    var presentedTranslationRequest: ReaderTranslationRequest? {
        translationState.request
    }

    var canRetryTranslation: Bool {
        guard !isTranslationRetryCoolingDown else { return false }
        switch translationState {
        case .success:
            return true
        case let .failure(_, error):
            return error.canRetry
        case .idle, .loading:
            return false
        }
    }

    var canAnnotateCurrentSelection: Bool {
        guard let request = translationState.request else { return false }
        return request.annotationLocatorJSON != nil
            && request.trigger != .crossPageExpansion
    }

    var canExpandTranslationAcrossPage: Bool {
        guard let request = translationState.request else { return false }
        return request.trigger != .crossPageExpansion
            && !isExpandingCrossPageTranslation
    }

    var canAddCurrentSelectionToVocabulary: Bool {
        guard case let .success(request, _, _) = translationState else {
            return false
        }
        return ReaderVocabularyCandidate.term(
            from: request.sourceText,
            language: request.sourceLanguage
        ) != nil
    }

    var isJapaneseBook: Bool {
        book.language?.lowercased().hasPrefix("ja") == true
    }

    var isReflowableBook: Bool {
        book.format.usesReflowableReader
    }

    var fontPointSize: Double {
        ReaderFontSizing.pointSize(fromScale: fontSize)
    }

    var supportsQuickSentenceTranslation: Bool {
        controller?.supportsQuickSentenceTranslation ?? book.format.usesReflowableReader
    }

    private let modelContext: ModelContext
    private let publicationServiceFactory: () -> any ReaderPublicationOpening
    private let loadTiming: ReaderLoadTiming
    private let translationCache: TranslationCacheStore
    private let translationFavorites: TranslationFavoriteStore
    private let bookmarkStore: ReadingBookmarkStore
    private let annotationStore: ReadingAnnotationStore
    private let speechService: any SpeechService
    private let lexicalLookupService: any LexicalLookupService
    private let translationTiming: ReaderTranslationTiming
    private let quickTranslationIndicatorTiming: ReaderQuickTranslationIndicatorTiming
    private let backendTranslationServiceFactory:
        (BackendTranslationConfiguration) -> any TranslationService
    private var publication: Publication?
    private var activePublicationService: (any ReaderPublicationOpening)?
    private var pendingLocator: Locator?
    private var currentLocator: Locator?
    private var saveTask: Task<Void, Never>?
    private var searchTask: Task<Void, Never>?
    private var backendTranslationTask: Task<Void, Never>?
    private var translationWatchdogTask: Task<Void, Never>?
    private var translationWatchdogRequestID: UUID?
    private var inFlightTranslationRequestID: UUID?
    private var translationRetryCooldownTask: Task<Void, Never>?
    private var quickTranslationIndicatorAutoExitTask: Task<Void, Never>?
    private var contextExplanationTask: Task<Void, Never>?
    private var contextExplanationWatchdogTask: Task<Void, Never>?
    private var crossPageExpansionTask: Task<Void, Never>?
    private var vocabularyLookupTask: Task<Void, Never>?
    private var speechPlaybackID: UUID?
    private var contextExplanationCache: [String: ContextExplanationResult] = [:]
    private var translationRetryDeadlines:
        [ReaderTranslationRetryIdentity: ContinuousClock.Instant] = [:]
    private var readingSessionStartedAt: Date?
    private var loadAttemptID: UUID?
    private var publicationOpenTask: Task<Publication, Error>?
    private var loadWatchdogTask: Task<Void, Never>?
    private var isClosed = false
    private var isReadingSceneActive = false
    private var hasReportedPersistenceFailure = false

    init(
        book: BookRecord,
        modelContext: ModelContext,
        speechService: any SpeechService = SystemSpeechService.shared,
        lexicalLookupService: any LexicalLookupService =
            WiktionaryLexicalLookupService(),
        translationSettings: TranslationSettingsStore? = nil,
        publicationService: (any ReaderPublicationOpening)? = nil,
        loadTiming: ReaderLoadTiming = .standard,
        translationTiming: ReaderTranslationTiming = .standard,
        quickTranslationIndicatorTiming: ReaderQuickTranslationIndicatorTiming = .standard,
        backendTranslationServiceFactory: @escaping
            (BackendTranslationConfiguration) -> any TranslationService = {
                BackendTranslationService(configuration: $0)
            }
    ) {
        self.book = book
        self.modelContext = modelContext
        self.speechService = speechService
        self.lexicalLookupService = lexicalLookupService
        if let publicationService {
            publicationServiceFactory = { publicationService }
        } else {
            // A retry must not reuse an AssetRetriever/PublicationOpener that
            // may still be unwinding from a timed-out Readium operation.
            publicationServiceFactory = { EPUBPublicationService() }
        }
        self.loadTiming = loadTiming
        self.translationTiming = translationTiming
        self.quickTranslationIndicatorTiming = quickTranslationIndicatorTiming
        self.backendTranslationServiceFactory = backendTranslationServiceFactory
        self.translationSettings = translationSettings ?? TranslationSettingsStore()
        let quickTranslationEnabled =
            self.translationSettings.quickSentenceTranslationEnabled
        isQuickSentenceTranslationEnabled = quickTranslationEnabled
        isQuickTranslationIndicatorVisible = quickTranslationEnabled
        quickTranslationUnit = self.translationSettings.quickTranslationUnit
        disablesTapPageTurnsDuringQuickTranslation = self.translationSettings
            .disablesTapPageTurnsDuringQuickTranslation
        isPDFPaperModeEnabled = book.readerPDFPaperModeEnabled
        translationCache = TranslationCacheStore(modelContext: modelContext)
        translationFavorites = TranslationFavoriteStore(modelContext: modelContext)
        bookmarkStore = ReadingBookmarkStore(modelContext: modelContext)
        annotationStore = ReadingAnnotationStore(modelContext: modelContext)
        chapterTitle = book.title
        fontSize = ReaderFontSizing.clampedScale(book.readerFontSize)
        theme = ReaderThemeChoice(rawValue: book.readerTheme) ?? .coolGray
        fontChoice = ReaderFontChoice(rawValue: book.readerFontFamily) ?? .serif
        lineHeight = min(max(book.readerLineHeight, 1.0), 2.2)
        paragraphSpacing = min(max(book.readerParagraphSpacing, 0.0), 2.0)
        pageMargins = min(max(book.readerPageMargins, 0.5), 2.0)
        customBackgroundHex = ReaderCustomBackground.normalizedHex(
            book.readerCustomBackgroundHex
        ) ?? ""
        customSelectionColorHex = ReaderCustomBackground.normalizedHex(
            book.readerCustomSelectionColorHex
        ) ?? ""
        speechRate = min(max(book.readerSpeechRate, 0.5), 2.0)
        textOrientation = ReaderTextOrientationChoice(
            rawValue: book.readerTextOrientation
        ) ?? .publication
        readingMode = ReaderReadingMode(
            scrollEnabled: book.readerScrollEnabled
        )
        publicationLayout = ReaderPublicationLayout(
            rawValue: book.readerDetectedPublicationLayout
        ) ?? .horizontalLTR
    }

    func load() async {
        await load(allowsAutomaticCancellationRetry: true)
    }

#if DEBUG
    func prepareSelectionUITestIfNeeded() async {
        let arguments = ProcessInfo.processInfo.arguments
        guard arguments.contains("--jerreader-selection-ui-test"),
              let publication,
              let controller = controller as? EPUBReaderViewController
        else { return }

        let requestedHref = arguments
            .first(where: { $0.hasPrefix("--jerreader-selection-test-href=") })?
            .split(separator: "=", maxSplits: 1)
            .last
            .map(String.init) ?? "part0005"
        guard let link = publication.readingOrder.first(where: {
            $0.href.localizedCaseInsensitiveContains(requestedHref)
        }) else { return }

        // The SwiftUI full-screen cover can call this while Readium is still
        // creating its pagination view. A single early `go` then returns
        // false even though the requested link is valid. Retry only in this
        // explicit UI-test hook so the physical gesture test waits for the
        // same ready state a user naturally reaches after the opening frame.
        var didNavigate = false
        for _ in 0 ..< 120 {
            if await controller.navigator.go(to: link, options: .none) {
                didNavigate = true
                break
            }
            try? await Task.sleep(for: .milliseconds(50))
        }
        guard didNavigate else { return }
        controlsVisible = arguments.contains(
            "--jerreader-selection-controls-visible"
        )
        try? await Task.sleep(for: .milliseconds(300))
        await controller.prepareSelectionUITestTarget()
        prepareTranslationOverlayUITestIfNeeded(using: controller)
    }

    private func prepareTranslationOverlayUITestIfNeeded(
        using controller: EPUBReaderViewController
    ) {
        let arguments = ProcessInfo.processInfo.arguments
        guard let mode = arguments
            .first(where: {
                $0.hasPrefix("--jerreader-translation-overlay-ui-test=")
            })?
            .split(separator: "=", maxSplits: 1)
            .last
            .map(String.init),
            let windowBounds = controller.view.window?.bounds
        else { return }
        let targetFrame = controller.selectionUITestTargetFrameInWindow()
            ?? CGRect(
                x: windowBounds.width * 0.68,
                y: windowBounds.height * 0.34,
                width: 24,
                height: 260
            )

        let sourceFrame: CGRect
        switch mode {
        case "vertical":
            let width = max(targetFrame.width, 24)
            let height = min(max(windowBounds.height * 0.60, 420), 520)
            sourceFrame = CGRect(
                x: min(
                    max(targetFrame.midX - width / 2, 18),
                    windowBounds.width - width - 18
                ),
                y: max((windowBounds.height - height) / 2, 74),
                width: width,
                height: min(height, windowBounds.height - 148)
            )
            publicationLayout = .verticalRTL
            textOrientation = .publication
        case "paragraph":
            sourceFrame = CGRect(
                x: 28,
                y: windowBounds.height * 0.56,
                width: max(windowBounds.width - 56, 1),
                height: min(170, windowBounds.height * 0.22)
            )
            textOrientation = .horizontal
        default:
            return
        }

        let sourceText = String(
            repeating: "これは長い段落の翻译レイアウトを確認するための原文です。",
            count: 6
        )
        let translatedText = String(
            repeating: "这是用于验证长段落翻译浮窗的测试译文，内容超出固定视口后应在窗口内上下滚动，且浮窗不得遮挡原文。",
            count: 7
        )
        let request = ReaderTranslationRequest(
            id: UUID(),
            bookID: book.id,
            sourceText: sourceText,
            contextText: nil,
            sourceLanguage: .japanese,
            targetLanguage: .simplifiedChinese,
            provider: .apple,
            providerIdentifier: "ui-test",
            providerVersion: "1",
            locatorJSON: nil,
            annotationLocatorJSON: nil,
            annotationAnchorJSON: nil,
            selectionFrame: sourceFrame,
            focusFrame: mode == "vertical" ? targetFrame : nil,
            trigger: .quickSentence,
            createdAt: Date()
        )
        let result = TranslationResult(
            sourceText: sourceText,
            translatedText: translatedText,
            sourceLanguage: .japanese,
            targetLanguage: .simplifiedChinese,
            providerIdentifier: "ui-test",
            providerVersion: "1",
            isFromCache: false
        )

        quickTranslationUnit = .paragraph
        controlsVisible = false
        translationConfiguration = nil
        translationAnchorFrame = sourceFrame
        translationState = .success(
            request: request,
            result: result,
            source: .mock
        )
        controller.installTranslationUITestSelection(frameInWindow: sourceFrame)
    }

    func installTranslationUITestCard(frameInWindow: CGRect) {
        guard let controller = controller as? EPUBReaderViewController else {
            return
        }
        controller.installTranslationUITestCard(frameInWindow: frameInWindow)
    }
#endif

    private func load(allowsAutomaticCancellationRetry: Bool) async {
        // SwiftUI can reuse a presentation's StateObject after onDisappear()
        // has already called close(). Treat a later task as a fresh session
        // instead of leaving the old model permanently in `.loading`.
        if isClosed {
            isClosed = false
        }
        guard controller == nil, loadAttemptID == nil else { return }
        let attemptID = UUID()
        loadAttemptID = attemptID
        loadState = .loading
        startLoadWatchdog(for: attemptID)

        do {
            let publicationService = publicationServiceFactory()
            let openTask = Task { @MainActor [book] in
                try Task.checkCancellation()
                let publication = try await publicationService.open(book: book)
                guard !Task.isCancelled else {
                    publication.close()
                    publicationService.finishBookFileAccess()
                    throw CancellationError()
                }
                publicationService.reassertBookFileMetadata()
                return publication
            }
            publicationOpenTask = openTask
            let publication = try await withTaskCancellationHandler {
                try await openTask.value
            } onCancel: {
                openTask.cancel()
            }
            guard !isClosed, loadAttemptID == attemptID else {
                publication.close()
                publicationService.finishBookFileAccess()
                return
            }
            activePublicationService = publicationService
            let initialLocation: Locator?
            if let json = book.lastReadLocatorJSON {
                let saved = try? Locator(jsonString: json)
                if let saved,
                   publication.readingOrder.contains(where: {
                       $0.url().removingFragment().isEquivalentTo(
                           saved.href.removingFragment()
                       )
                   })
                {
                    initialLocation = saved
                } else {
                    // A stale locator can make Navigator initialization wait
                    // for a spine resource which no longer exists (for
                    // example after reimporting a converted document). Fall
                    // back to the first valid page and discard only the stale
                    // position, never the book itself.
                    initialLocation = nil
                    book.lastReadLocatorJSON = nil
                    book.lastReadProgress = 0
                }
            } else {
                initialLocation = nil
            }

            if book.format.usesReflowableReader {
                // Re-read the publication styles on open. Older releases can
                // have cached `.horizontalLTR` before vertical CSS detection
                // was complete; trusting that cache forever makes the new
                // adjacent floating-window layout impossible to reach for existing books.
                let detectedLayout =
                    await JapanesePublicationLayoutDetector.detect(
                        in: publication,
                        fallbackLanguage: book.language
                    )
                publicationLayout = detectedLayout
                book.readerDetectedPublicationLayout = detectedLayout.rawValue
            }

            let controller: any PublicationReaderController
            do {
                if book.format == .pdf {
                    controller = try PDFReaderViewController(
                        publication: publication,
                        initialLocation: initialLocation,
                        theme: theme,
                        customBackgroundHex: customBackgroundHex,
                        customSelectionColorHex: customSelectionColorHex,
                        bookLanguage: book.language,
                        paperModeEnabled: isPDFPaperModeEnabled
                    )
                } else {
                    controller = try EPUBReaderViewController(
                        publication: publication,
                        initialLocation: initialLocation,
                        fontSize: fontSize,
                        theme: theme,
                        fontChoice: fontChoice,
                        lineHeight: lineHeight,
                        paragraphSpacing: paragraphSpacing,
                        pageMargins: pageMargins,
                        textOrientation: textOrientation,
                        publicationLayout: publicationLayout,
                        readingMode: readingMode,
                        customBackgroundHex: customBackgroundHex,
                        customSelectionColorHex: customSelectionColorHex,
                        bookLanguage: book.language
                    )
                }
            } catch {
                publication.close()
                publicationService.finishBookFileAccess()
                activePublicationService = nil
                if let readerError = error as? ReaderError {
                    throw readerError
                }
                throw ReaderError.navigatorUnavailable
            }
            controller.onLocationChange = { [weak self] locator in
                self?.receive(locator: locator)
            }
            controller.onSelectedText = { [weak self] payload in
                self?.requestTranslation(for: payload)
            }
            controller.onSelectionFrameChange = { [weak self] frame in
                self?.receiveSelectionFrame(frame)
            }
            controller.onContentTap = { [weak self] in
                self?.controlsVisible.toggle()
            }
            controller.onReaderError = { [weak self] message in
                self?.readerAlertMessage = message
            }
            controller.onReaderActivityChange = { [weak self] message in
                self?.readerActivityMessage = message
            }
            controller.onPaginationModeChange = { [weak self] isPaginated in
                self?.receivePaginationMode(isPaginated)
            }
            controller.onAnnotationActivated = { [weak self] annotationID in
                self?.editAnnotation(withID: annotationID)
            }
            if controller.supportsQuickSentenceTranslation {
                controller.setQuickSentenceTranslationEnabled(
                    isQuickSentenceTranslationEnabled
                )
                controller.setQuickTranslationUnit(quickTranslationUnit)
                controller.setQuickSentenceTapPageTurnsDisabled(
                    disablesTapPageTurnsDuringQuickTranslation
                )
            } else {
                isQuickSentenceTranslationEnabled = false
            }

            self.publication = publication
            outline = ReaderOutlineBuilder.build(
                tableOfContents: publication.manifest.tableOfContents,
                readingOrder: publication.readingOrder
            )
            self.controller = controller
            currentLocator = initialLocation ?? controller.currentLocator
            if let currentLocator {
                pendingLocator = currentLocator
                progress = min(
                    max(currentLocator.locations.totalProgression ?? book.lastReadProgress, 0),
                    1
                )
            }
            refreshBookmarks()
            refreshAnnotations()
            receivePaginationMode(controller.isPaginated)
            saveModelChanges()
            activePublicationService?.reassertBookFileMetadata()
            finishLoadAttempt(attemptID)
            loadState = .ready
            if isReadingSceneActive {
                resumeReadingSession()
            }
            // Chapter boundaries are only needed once the book is on screen,
            // so they must never delay the first paint.
            Task { @MainActor [weak self] in
                await self?.resolveOutlineProgressions()
                await self?.repairMeaninglessOutlineTitles()
            }
        } catch is CancellationError {
            guard !isClosed, loadAttemptID == attemptID else { return }
            finishLoadAttempt(attemptID)

            if allowsAutomaticCancellationRetry {
                // A SwiftUI presentation task can be cancelled once while the
                // full-screen cover settles. Do not turn that transient event
                // into the generic “operation not completed” error. Retry in
                // an independent task, but only once; closing the reader or a
                // watchdog invalidates the attempt ID and prevents a restart.
                loadState = .loading
                Task { @MainActor [weak self] in
                    await Task.yield()
                    guard let self,
                          !self.isClosed,
                          self.controller == nil,
                          self.loadAttemptID == nil
                    else { return }
                    await self.load(allowsAutomaticCancellationRetry: false)
                }
            } else {
                loadState = .failed(ReaderError.openInterrupted.localizedDescription)
            }
        } catch {
            guard !isClosed, loadAttemptID == attemptID else { return }
            finishLoadAttempt(attemptID)
            loadState = .failed(Self.message(for: error))
        }
    }

    private func startLoadWatchdog(for attemptID: UUID) {
        loadWatchdogTask?.cancel()
        let timeout = loadTiming.openTimeout
        loadWatchdogTask = Task { @MainActor [weak self] in
            do {
                try await Task.sleep(for: timeout)
            } catch {
                return
            }
            guard let self,
                  !self.isClosed,
                  self.controller == nil,
                  self.loadAttemptID == attemptID
            else { return }

            // A late Readium result is no longer allowed to replace a retry.
            // load() closes it as soon as it observes this invalidated ID.
            let openTask = self.publicationOpenTask
            self.loadAttemptID = nil
            self.publicationOpenTask = nil
            self.loadWatchdogTask = nil
            self.loadState = .failed(ReaderError.openTimedOut.localizedDescription)
            openTask?.cancel()
        }
    }

    private func finishLoadAttempt(_ attemptID: UUID) {
        guard loadAttemptID == attemptID else { return }
        loadWatchdogTask?.cancel()
        loadWatchdogTask = nil
        publicationOpenTask = nil
        loadAttemptID = nil
    }

    func goBackward() {
        guard let controller else { return }
        dismissTranslation()
        controller.navigateBackward()
    }

    func goForward() {
        guard let controller else { return }
        dismissTranslation()
        controller.navigateForward()
    }

    func go(to item: ReaderOutlineItem) {
        guard let controller else { return }
        dismissTranslation()
        controller.navigate(to: item.link)
    }

    /// The outline entry the reader is currently inside, used to highlight and
    /// scroll the contents list and to label the progress scrubber.
    var currentOutlineItem: ReaderOutlineItem? {
        guard !outline.isEmpty else { return nil }
        if let locator = currentLocator {
            let key = ReaderOutlineItem.resourceKey(for: locator.href.string)
            // A resource can hold several entries (`chapter.xhtml#part2`).
            // Prefer the last one that starts at or before the position.
            let matches = outline.filter { $0.resourceKey == key }
            if !matches.isEmpty {
                let progression = locator.locations.totalProgression
                if let progression {
                    let started = matches.filter {
                        ($0.startProgression ?? 0) <= progression + 0.0005
                    }
                    if let last = started.last { return last }
                }
                return matches.first
            }
        }
        return outlineItem(atProgression: progress)
    }

    /// The entry containing a given whole-publication progression. Used for the
    /// live chapter preview while the progress bar is being dragged, before any
    /// navigation actually happens.
    func outlineItem(atProgression progression: Double) -> ReaderOutlineItem? {
        let known = outline.filter { $0.startProgression != nil }
        guard !known.isEmpty else { return nil }
        var result = known.first
        for item in known {
            guard let start = item.startProgression else { continue }
            if start <= progression + 0.0005 {
                result = item
            } else {
                break
            }
        }
        return result
    }

    var canGoToAdjacentChapter: Bool {
        outline.count > 1 && controller != nil
    }

    func goToPreviousChapter() {
        guard let index = currentOutlineIndex else { return }
        // Land on the start of the current chapter first; only step back when
        // already at its beginning, which is how page-based readers behave.
        let atChapterStart = (currentOutlineItem?.startProgression).map {
            progress <= $0 + 0.005
        } ?? false
        let target = atChapterStart ? index - 1 : index
        guard outline.indices.contains(target) else { return }
        go(to: outline[target])
    }

    func goToNextChapter() {
        guard let index = currentOutlineIndex else { return }
        let target = index + 1
        guard outline.indices.contains(target) else { return }
        go(to: outline[target])
    }

    private var currentOutlineIndex: Int? {
        guard let item = currentOutlineItem else { return nil }
        return outline.firstIndex { $0.id == item.id }
    }

    /// Replaces junk chapter labels with the real heading from the document.
    ///
    /// Only entries the publication failed to label usefully are touched, and
    /// only their own resource is read, so a book with a good table of contents
    /// costs nothing here.
    private func repairMeaninglessOutlineTitles() async {
        guard let publication, !outline.isEmpty else { return }
        let needsRepair = outline.indices.filter {
            !ReaderChapterTitle.isMeaningful(
                outline[$0].title,
                bookTitle: book.title
            )
        }
        guard !needsRepair.isEmpty else { return }

        let titleByResource = await ReaderOutlineBuilder.repairedTitles(
            in: publication,
            links: needsRepair.prefix(60).map { outline[$0].link },
            bookTitle: book.title
        )
        guard !isClosed, !Task.isCancelled, !titleByResource.isEmpty else {
            return
        }

        var repaired = outline
        for index in needsRepair {
            if let title = titleByResource[repaired[index].resourceKey] {
                repaired[index].title = title
            }
        }
        outline = repaired
        // The header may currently be showing one of the labels just repaired.
        if let locator = currentLocator {
            chapterTitle = ReaderChapterTitle.display(
                outlineTitle: currentOutlineItem?.title,
                locatorTitle: locator.title,
                bookTitle: book.title
            )
        }
    }

    /// Resolves where each outline entry begins in the whole publication.
    ///
    /// Readium exposes positions per reading-order resource, so the first
    /// position of a resource is the start of every entry pointing at it. This
    /// runs once per opened book; the service caches its own computation.
    private func resolveOutlineProgressions() async {
        guard let publication, !outline.isEmpty else { return }
        guard let positions = await publication.positionsByReadingOrder().getOrNil()
        else { return }

        var startByResource: [String: Double] = [:]
        for (index, resourcePositions) in positions.enumerated() {
            guard let first = resourcePositions.first else { continue }
            let key = ReaderOutlineItem.resourceKey(for: first.href.string)
            let progression = first.locations.totalProgression
                ?? (positions.isEmpty ? 0 : Double(index) / Double(positions.count))
            if startByResource[key] == nil {
                startByResource[key] = progression
            }
        }
        guard !startByResource.isEmpty else { return }

        var resolved = outline
        for index in resolved.indices {
            resolved[index].startProgression = startByResource[resolved[index].resourceKey]
        }
        // Entries whose resource is missing from the reading order (rare, but
        // seen with stale NCX files) inherit the previous known start so the
        // scrubber never jumps backwards.
        var lastKnown: Double = 0
        for index in resolved.indices {
            if let start = resolved[index].startProgression {
                lastKnown = start
            } else {
                resolved[index].startProgression = lastKnown
            }
        }
        guard !isClosed else { return }
        outline = resolved
    }

    func go(to result: ReaderSearchResult) {
        navigate(to: result.locator)
    }

    func seek(to totalProgression: Double) {
        guard let publication else { return }
        let clampedProgression = min(max(totalProgression, 0), 1)
        Task { @MainActor [weak self] in
            guard let self,
                  let locator = await publication.locate(
                    progression: clampedProgression
                  ),
                  !Task.isCancelled
            else { return }
            self.navigate(to: locator)
        }
    }

    func go(to bookmark: ReadingBookmarkRecord) {
        guard let locator = try? Locator(jsonString: bookmark.locatorJSON) else {
            readerAlertMessage = "这个书签的位置已经无法读取。"
            return
        }
        navigate(to: locator)
    }

    func search(_ query: String) {
        let normalized = query.trimmingCharacters(in: .whitespacesAndNewlines)
        searchTask?.cancel()
        searchTask = nil
        searchErrorMessage = nil
        lastSearchQuery = normalized
        searchResults = []

        guard !normalized.isEmpty else {
            isSearching = false
            return
        }
        guard let controller else {
            searchErrorMessage = ReaderSearchError.unavailable.localizedDescription
            return
        }

        isSearching = true
        searchTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                if lastSearchQuery == normalized {
                    isSearching = false
                    searchTask = nil
                }
            }
            do {
                let locators = try await controller.search(normalized)
                try Task.checkCancellation()
                guard lastSearchQuery == normalized else { return }
                searchResults = locators.map(ReaderSearchResult.init(locator:))
            } catch is CancellationError {
                return
            } catch {
                guard lastSearchQuery == normalized else { return }
                searchErrorMessage = (error as? LocalizedError)?.errorDescription
                    ?? ReaderSearchError.failed.localizedDescription
            }
        }
    }

    func cancelSearch() {
        searchTask?.cancel()
        searchTask = nil
        isSearching = false
    }

    func toggleBookmark() {
        guard let locator = currentLocator ?? controller?.currentLocator else {
            readerAlertMessage = "当前位置还没有准备好，请翻一页后再添加书签。"
            return
        }
        do {
            isCurrentLocationBookmarked = try bookmarkStore.toggle(
                book: book,
                locator: locator,
                chapterTitle: chapterTitle
            )
            refreshBookmarks()
        } catch {
            readerAlertMessage = (error as? LocalizedError)?.errorDescription
                ?? "暂时无法更新书签，请稍后重试。"
        }
    }

    func deleteBookmark(_ bookmark: ReadingBookmarkRecord) {
        do {
            try bookmarkStore.delete(bookmark)
            refreshBookmarks()
        } catch {
            readerAlertMessage = "暂时无法删除书签，请稍后重试。"
        }
    }

    func addHighlightForCurrentSelection() {
        guard let draft = annotationDraftForCurrentSelection() else {
            readerAlertMessage = "当前选区暂时无法划线，请重新选择文字后再试。"
            return
        }
        do {
            _ = try annotationStore.save(
                book: book,
                locatorJSON: draft.locatorJSON,
                anchorJSON: draft.anchorJSON,
                selectedText: draft.selectedText,
                noteText: draft.noteText,
                color: draft.color,
                chapterTitle: draft.chapterTitle,
                progress: draft.progress
            )
            refreshAnnotations()
        } catch {
            readerAlertMessage = (error as? LocalizedError)?.errorDescription
                ?? "暂时无法保存划线，请稍后重试。"
        }
    }

    func editCurrentSelectionAnnotation() {
        guard let draft = annotationDraftForCurrentSelection() else {
            readerAlertMessage = "当前选区暂时无法添加笔记，请重新选择文字后再试。"
            return
        }
        presentedAnnotationEditor = draft
    }

    func editAnnotation(_ annotation: ReadingAnnotationRecord) {
        presentedAnnotationEditor = ReaderAnnotationEditorDraft(
            annotationID: annotation.id,
            locatorJSON: annotation.locatorJSON,
            anchorJSON: annotation.anchorJSON,
            selectedText: annotation.selectedText,
            noteText: annotation.noteText,
            color: annotation.color,
            chapterTitle: annotation.chapterTitle,
            progress: annotation.progress
        )
    }

    func saveAnnotation(
        _ draft: ReaderAnnotationEditorDraft,
        noteText: String,
        color: ReadingAnnotationColor
    ) {
        do {
            _ = try annotationStore.save(
                book: book,
                locatorJSON: draft.locatorJSON,
                anchorJSON: draft.anchorJSON,
                selectedText: draft.selectedText,
                noteText: noteText,
                color: color,
                chapterTitle: draft.chapterTitle,
                progress: draft.progress
            )
            presentedAnnotationEditor = nil
            refreshAnnotations()
        } catch {
            readerAlertMessage = (error as? LocalizedError)?.errorDescription
                ?? "暂时无法保存笔记，请稍后重试。"
        }
    }

    func deleteAnnotation(_ annotation: ReadingAnnotationRecord) {
        do {
            try annotationStore.delete(annotation)
            if presentedAnnotationEditor?.annotationID == annotation.id {
                presentedAnnotationEditor = nil
            }
            refreshAnnotations()
        } catch {
            readerAlertMessage = "暂时无法删除批注，请稍后重试。"
        }
    }

    func deletePresentedAnnotation() {
        guard let annotationID = presentedAnnotationEditor?.annotationID,
              let annotation = annotations.first(where: { $0.id == annotationID })
        else { return }
        deleteAnnotation(annotation)
    }

    func go(to annotation: ReadingAnnotationRecord) {
        guard let locator = try? Locator(jsonString: annotation.locatorJSON) else {
            readerAlertMessage = "这个批注的位置已经无法读取。"
            return
        }
        navigate(to: locator)
    }

    func updateFontSize(_ value: Double) {
        fontSize = ReaderFontSizing.clampedScale(value)
        book.readerFontSize = fontSize
        applyPreferencesAndSave()
    }

    func updateFontPointSize(_ value: Double) {
        updateFontSize(ReaderFontSizing.scale(fromPointSize: value))
    }

    func updateTheme(_ value: ReaderThemeChoice) {
        theme = value
        book.readerTheme = value.rawValue
        applyPreferencesAndSave()
    }

    func updateFontChoice(_ value: ReaderFontChoice) {
        fontChoice = value
        book.readerFontFamily = value.rawValue
        applyPreferencesAndSave()
    }

    func updateLineHeight(_ value: Double) {
        lineHeight = min(max(value, 1.0), 2.2)
        book.readerLineHeight = lineHeight
        applyPreferencesAndSave()
    }

    func updateParagraphSpacing(_ value: Double) {
        paragraphSpacing = min(max(value, 0.0), 2.0)
        book.readerParagraphSpacing = paragraphSpacing
        applyPreferencesAndSave()
    }

    func updatePageMargins(_ value: Double) {
        pageMargins = min(max(value, 0.5), 2.0)
        book.readerPageMargins = pageMargins
        applyPreferencesAndSave()
    }

    func updateReadingMode(_ value: ReaderReadingMode) {
        readingMode = value
        book.readerScrollEnabled = value.scrollEnabled
        applyPreferencesAndSave()
    }

    func updateCustomBackgroundHex(_ value: String) {
        customBackgroundHex = ReaderCustomBackground.normalizedHex(value) ?? ""
        book.readerCustomBackgroundHex = customBackgroundHex
        applyPreferencesAndSave()
    }

    func updateCustomSelectionColorHex(_ value: String) {
        customSelectionColorHex =
            ReaderCustomBackground.normalizedHex(value) ?? ""
        book.readerCustomSelectionColorHex = customSelectionColorHex
        applyPreferencesAndSave()
    }

    func updateSpeechRate(_ value: Double) {
        speechRate = min(max(value, 0.5), 2.0)
        book.readerSpeechRate = speechRate
        if isSpeakingTranslationSource {
            stopTranslationSpeech()
        }
        saveModelChanges()
    }

    func updateTextOrientation(_ value: ReaderTextOrientationChoice) {
        textOrientation = value
        book.readerTextOrientation = value.rawValue
        applyPreferencesAndSave()
    }

    func setQuickSentenceTranslationEnabled(_ enabled: Bool) {
        guard supportsQuickSentenceTranslation else { return }
        guard isQuickSentenceTranslationEnabled != enabled else { return }
        quickTranslationIndicatorDidDisappear()
        dismissTranslation()
        isQuickSentenceTranslationEnabled = enabled
        isQuickTranslationIndicatorVisible = enabled
        translationSettings.quickSentenceTranslationEnabled = enabled
        controller?.setQuickSentenceTranslationEnabled(enabled)
        controlsVisible = true
    }

    func setQuickTranslationUnit(_ unit: ReaderQuickTranslationUnit) {
        guard quickTranslationUnit != unit else { return }
        dismissTranslation()
        quickTranslationUnit = unit
        translationSettings.quickTranslationUnit = unit
        controller?.setQuickTranslationUnit(unit)
        controlsVisible = true
    }

    func setPDFPaperModeEnabled(_ enabled: Bool) {
        guard book.format == .pdf,
              isPDFPaperModeEnabled != enabled
        else { return }
        dismissTranslation()
        isPDFPaperModeEnabled = enabled
        book.readerPDFPaperModeEnabled = enabled
        controller?.setPDFPaperModeEnabled(enabled)
        saveModelChanges()
        controlsVisible = true
    }

    /// The compact right-edge affordance is intentionally temporary. It is
    /// only a reminder of the active gesture mode; hiding it must not silently
    /// disable point-to-translate.
    func quickTranslationIndicatorDidAppear() {
        guard isQuickSentenceTranslationEnabled,
              isQuickTranslationIndicatorVisible,
              presentedTranslationRequest == nil,
              readerActivityMessage == nil
        else { return }

        quickTranslationIndicatorAutoExitTask?.cancel()
        let delay = quickTranslationIndicatorTiming.autoExitDelay
        // Keep this tiny timer independent from the SwiftUI/MainActor task
        // which presented the affordance. Under heavy simulator or view
        // transitions an inherited task can be cancelled together with that
        // presentation transaction, leaving the reminder visible forever.
        quickTranslationIndicatorAutoExitTask = Task.detached { [weak self] in
            do {
                try await Task.sleep(for: delay)
            } catch {
                return
            }
            guard !Task.isCancelled else { return }
            await self?.hideQuickTranslationIndicatorIfIdle()
        }
    }

    private func hideQuickTranslationIndicatorIfIdle() {
        guard isQuickSentenceTranslationEnabled,
              isQuickTranslationIndicatorVisible,
              presentedTranslationRequest == nil,
              readerActivityMessage == nil
        else {
            quickTranslationIndicatorAutoExitTask = nil
            return
        }
        isQuickTranslationIndicatorVisible = false
        quickTranslationIndicatorAutoExitTask = nil
    }

    func quickTranslationIndicatorDidDisappear() {
        quickTranslationIndicatorAutoExitTask?.cancel()
        quickTranslationIndicatorAutoExitTask = nil
    }

    func setDisablesTapPageTurnsDuringQuickTranslation(_ disabled: Bool) {
        guard disablesTapPageTurnsDuringQuickTranslation != disabled else { return }
        disablesTapPageTurnsDuringQuickTranslation = disabled
        translationSettings.disablesTapPageTurnsDuringQuickTranslation = disabled
        controller?.setQuickSentenceTapPageTurnsDisabled(disabled)
    }

    func showReaderControls() {
        controlsVisible = true
    }

    func requestTranslation(for payload: ReaderSelectionPayload) {
        receiveSelectionFrame(payload.frame)
        let sourceText = TranslationCacheStore.normalizedText(payload.text)
        let normalizedContext = payload.contextText
            .map(TranslationCacheStore.normalizedText)
            .flatMap { context in
                context.isEmpty || context == sourceText ? nil : context
            }
        let provider = providerDescriptor()
        let targetLanguage = translationSettings.targetLanguage
        let sourceLanguage = preferredSourceLanguage(for: sourceText)
        let interactionIdentity = ReaderTranslationInteractionIdentity(
            bookID: book.id,
            sourceText: sourceText,
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            providerIdentifier: provider.identifier,
            providerVersion: provider.version,
            locatorJSON: payload.locatorJSON
        )

        switch translationState {
        case let .loading(request)
            where request.interactionIdentity == interactionIdentity
                && translationWatchdogRequestID == request.id:
            return
        case let .success(request, _, _)
            where request.interactionIdentity == interactionIdentity:
            return
        default:
            break
        }

        // Do not cancel an in-flight provider task until the new selection is
        // known to be a genuinely different request. Otherwise an equivalent
        // geometry callback could leave the existing request stuck loading.
        stopTranslationSpeech()
        cancelTranslationWatchdog()
        backendTranslationTask?.cancel()
        backendTranslationTask = nil

        let request = ReaderTranslationRequest(
            id: UUID(),
            bookID: book.id,
            sourceText: sourceText,
            contextText: normalizedContext,
            sourceLanguage: sourceLanguage,
            targetLanguage: targetLanguage,
            provider: provider.choice,
            providerIdentifier: provider.identifier,
            providerVersion: provider.version,
            locatorJSON: payload.locatorJSON,
            annotationLocatorJSON: payload.annotationLocatorJSON,
            annotationAnchorJSON: payload.annotationAnchorJSON,
            selectionFrame: translationAnchorFrame ?? payload.frame,
            focusFrame: payload.focusFrame,
            trigger: payload.trigger,
            createdAt: Date()
        )
        refreshCurrentAnnotationState(for: request)
        refreshTranslationRetryCooldown(for: request)

        guard !sourceText.isEmpty else {
            translationState = .failure(request: request, error: .emptySelection)
            return
        }
        guard sourceText.count <= Self.maxTranslationCharacterCount else {
            translationState = .failure(
                request: request,
                error: .textTooLong(limit: Self.maxTranslationCharacterCount)
            )
            return
        }
        guard let sourceLanguage else {
            translationState = .failure(request: request, error: .unsupportedLanguage)
            return
        }
        guard sourceLanguage != targetLanguage else {
            translationState = .failure(request: request, error: .sameLanguage)
            return
        }

        controlsVisible = false
        isCurrentTranslationFavorite = false
        isCurrentSelectionInVocabulary = false
        translationState = .loading(request)
        startTranslation(for: request)
    }

    func changeTranslationSource(_ language: LanguageCode?) {
        guard let current = translationState.request else { return }
        let resolvedLanguage = language ?? ReaderLanguageDetector.detect(
            text: current.sourceText,
            bookLanguage: nil
        )
        let provider = providerDescriptor()
        let request = ReaderTranslationRequest(
            id: UUID(),
            bookID: current.bookID,
            sourceText: current.sourceText,
            contextText: current.contextText,
            sourceLanguage: resolvedLanguage,
            targetLanguage: translationSettings.targetLanguage,
            provider: provider.choice,
            providerIdentifier: provider.identifier,
            providerVersion: provider.version,
            locatorJSON: current.locatorJSON,
            annotationLocatorJSON: current.annotationLocatorJSON,
            annotationAnchorJSON: current.annotationAnchorJSON,
            selectionFrame: translationAnchorFrame ?? current.selectionFrame,
            focusFrame: current.focusFrame,
            trigger: current.trigger,
            createdAt: Date()
        )
        refreshTranslationRetryCooldown(for: request)

        stopTranslationSpeech()
        cancelTranslationWatchdog()
        backendTranslationTask?.cancel()
        backendTranslationTask = nil
        guard let resolvedLanguage else {
            translationConfiguration = nil
            translationState = .failure(request: request, error: .unsupportedLanguage)
            return
        }
        guard resolvedLanguage != request.targetLanguage else {
            translationConfiguration = nil
            translationState = .failure(request: request, error: .sameLanguage)
            return
        }

        isCurrentTranslationFavorite = false
        isCurrentSelectionInVocabulary = false
        translationState = .loading(request)
        startTranslation(for: request)
    }

    func retryTranslation() {
        guard canRetryTranslation,
              let current = translationState.request
        else { return }
        beginTranslationRetryCooldown(for: current)
        let provider = providerDescriptor()
        let request = ReaderTranslationRequest(
            id: UUID(),
            bookID: current.bookID,
            sourceText: current.sourceText,
            contextText: current.contextText,
            sourceLanguage: current.sourceLanguage,
            targetLanguage: translationSettings.targetLanguage,
            provider: provider.choice,
            providerIdentifier: provider.identifier,
            providerVersion: provider.version,
            locatorJSON: current.locatorJSON,
            annotationLocatorJSON: current.annotationLocatorJSON,
            annotationAnchorJSON: current.annotationAnchorJSON,
            selectionFrame: translationAnchorFrame ?? current.selectionFrame,
            focusFrame: current.focusFrame,
            trigger: current.trigger,
            createdAt: Date()
        )
        guard let sourceLanguage = request.sourceLanguage else {
            translationState = .failure(request: request, error: .unsupportedLanguage)
            return
        }
        guard sourceLanguage != request.targetLanguage else {
            translationState = .failure(request: request, error: .sameLanguage)
            return
        }

        stopTranslationSpeech()
        cancelTranslationWatchdog()
        backendTranslationTask?.cancel()
        backendTranslationTask = nil
        isCurrentTranslationFavorite = false
        isCurrentSelectionInVocabulary = false
        translationState = .loading(request)
        startTranslation(for: request, ignoreCache: true)
    }

    func performPendingTranslation(using service: any TranslationService) async {
        guard case let .loading(request) = translationState,
              request.sourceLanguage != nil,
              inFlightTranslationRequestID != request.id
        else { return }

        let requestID = request.id
        inFlightTranslationRequestID = requestID
        defer {
            if inFlightTranslationRequestID == requestID {
                inFlightTranslationRequestID = nil
            }
        }

        do {
            let result = try await translateWithAutomaticRetry(
                using: service,
                request: request
            )
            guard isLoadingTranslation(requestID),
                  !Task.isCancelled
            else { return }

            let didCacheResult = translationCache.store(
                result,
                contextText: cacheContext(for: request)
            )
            guard isLoadingTranslation(requestID) else { return }
            cancelTranslationWatchdog(matching: requestID)
            translationState = .success(
                request: request,
                result: result,
                source: Self.resultSource(for: result)
            )
            refreshFavoriteState(for: result, request: request)
            refreshVocabularyState(for: request)
            if !didCacheResult {
                readerAlertMessage = ReaderTranslationDisplayError.persistenceFailed.message
            }
        } catch is CancellationError {
            guard isLoadingTranslation(requestID) else { return }
            cancelTranslationWatchdog(matching: requestID)
            translationState = .failure(request: request, error: .userCancelled)
        } catch {
            guard isLoadingTranslation(requestID) else { return }
            cancelTranslationWatchdog(matching: requestID)
            backendTranslationTask = nil
            if Self.isTransientTranslationError(error),
               beginAutomaticFallback(from: request)
            {
                return
            }
            translationState = .failure(
                request: request,
                error: Self.translationDisplayError(for: error, provider: request.provider)
            )
        }
    }

    func toggleTranslationFavorite() {
        guard case let .success(request, result, _) = translationState else { return }
        let newValue = !isCurrentTranslationFavorite
        do {
            isCurrentTranslationFavorite = try translationFavorites.setFavorite(
                newValue,
                bookID: book.id,
                bookTitle: book.title,
                locatorJSON: request.locatorJSON,
                result: result
            )
        } catch {
            readerAlertMessage = "暂时无法保存译文收藏，请稍后重试。"
        }
    }

    func addCurrentSelectionToVocabulary() {
        guard case let .success(request, result, _) = translationState,
              let sourceLanguage = request.sourceLanguage,
              let term = ReaderVocabularyCandidate.term(
                  from: request.sourceText,
                  language: sourceLanguage
              )
        else {
            readerAlertMessage = "请先选中一个完整的中、英或日文词语。"
            return
        }
        guard !isCurrentSelectionInVocabulary,
              !isAddingCurrentSelectionToVocabulary
        else { return }

        vocabularyLookupTask?.cancel()
        isAddingCurrentSelectionToVocabulary = true
        let requestID = request.id
        let context = request.contextText ?? request.sourceText
        vocabularyLookupTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                if self.translationState.request?.id == requestID {
                    self.isAddingCurrentSelectionToVocabulary = false
                }
                self.vocabularyLookupTask = nil
            }

            let explanation: WordExplanation
            do {
                explanation = try await lexicalLookupService.lookup(
                    word: term,
                    sentenceContext: context,
                    language: sourceLanguage
                )
            } catch is CancellationError {
                return
            } catch {
                explanation = WordExplanation(
                    surfaceForm: term,
                    lemma: nil,
                    reading: nil,
                    language: sourceLanguage,
                    partOfSpeech: nil,
                    definitions: [result.translatedText],
                    usageNote: "从阅读正文加入；释义来自本次译文。",
                    sentenceContext: context
                )
            }

            guard !Task.isCancelled,
                  translationState.request?.id == requestID
            else { return }
            do {
                let record = try WordLookupStore.record(
                    explanation,
                    sourceBookID: book.id,
                    sourceBookTitle: book.title,
                    in: modelContext
                )
                if !record.isFavorite {
                    try WordLookupStore.setFavorite(
                        true,
                        for: record,
                        in: modelContext
                    )
                }
                isCurrentSelectionInVocabulary = true
                readerAlertMessage = "“\(term)”已加入生词本。"
            } catch {
                readerAlertMessage = "暂时无法加入生词本，请稍后重试。"
            }
        }
    }

    func expandTranslationAcrossPage() {
        guard let current = translationState.request,
              current.trigger != .crossPageExpansion,
              !isExpandingCrossPageTranslation
        else { return }

        if let expansion = crossPageExpansion(
            for: current,
            contextText: current.contextText
        ) {
            requestTranslation(for: crossPagePayload(expansion, request: current))
            return
        }

        guard let controller else {
            readerAlertMessage = "当前选区附近没有更多可扩展的完整句段。"
            return
        }
        let requestID = current.id
        crossPageExpansionTask?.cancel()
        isExpandingCrossPageTranslation = true
        readerActivityMessage = "正在读取跨页句段…"
        crossPageExpansionTask = Task { @MainActor [weak self, weak controller] in
            guard let self, let controller else { return }
            let context = await controller.crossPageContext(
                for: current.sourceText
            )
            guard !Task.isCancelled,
                  self.translationState.request?.id == requestID
            else { return }

            self.crossPageExpansionTask = nil
            self.isExpandingCrossPageTranslation = false
            self.readerActivityMessage = nil
            guard let expansion = self.crossPageExpansion(
                for: current,
                contextText: context
            ) else {
                self.readerAlertMessage = "未找到可确认的跨页完整句段，请在页边界附近重新选择。"
                return
            }
            self.requestTranslation(
                for: self.crossPagePayload(expansion, request: current)
            )
        }
    }

    func requestContextExplanation(ignoreCache: Bool = false) {
        guard let translationRequest = translationState.request,
              !translationRequest.sourceText.isEmpty
        else {
            contextExplanationState = .failure(
                request: nil,
                error: .invalidSelection
            )
            isShowingContextExplanation = true
            return
        }
        guard let resolved = resolvedContextExplanationService() else {
            contextExplanationState = .failure(
                request: nil,
                error: .configurationMissing
            )
            isShowingContextExplanation = true
            return
        }

        contextExplanationTask?.cancel()
        cancelContextExplanationWatchdog()
        let prepared = ContextExplanationInputPolicy.prepared(
            ContextExplanationRequest(
                focusedText: translationRequest.sourceText,
                contextText: translationRequest.contextText,
                sourceLanguage: translationRequest.sourceLanguage,
                responseLanguage: translationSettings.targetLanguage
            )
        )
        let request = ReaderContextExplanationRequest(
            id: UUID(),
            focusedText: prepared.focusedText,
            contextText: prepared.contextText,
            sourceLanguage: prepared.sourceLanguage,
            responseLanguage: prepared.responseLanguage,
            providerIdentifier: resolved.identifier,
            providerVersion: resolved.version
        )
        let cacheKey = contextExplanationCacheKey(for: request)
        isShowingContextExplanation = true
        if !ignoreCache, let cached = contextExplanationCache[cacheKey] {
            contextExplanationState = .success(request: request, result: cached)
            return
        }

        contextExplanationState = .loading(request)
        scheduleContextExplanationWatchdog(for: request)
        contextExplanationTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                let rawResult = try await resolved.service.explain(
                    ContextExplanationRequest(
                        focusedText: request.focusedText,
                        contextText: request.contextText,
                        sourceLanguage: request.sourceLanguage,
                        responseLanguage: request.responseLanguage
                    )
                )
                guard let result = ContextExplanationOutputPolicy.validated(
                    rawResult
                ) else {
                    throw ServiceError.resultNotFound
                }
                guard !Task.isCancelled,
                      case let .loading(active) = contextExplanationState,
                      active.id == request.id
                else { return }
                cancelContextExplanationWatchdog(matching: request.id)
                contextExplanationCache[cacheKey] = result
                contextExplanationState = .success(request: request, result: result)
                contextExplanationTask = nil
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                cancelContextExplanationWatchdog(matching: request.id)
                contextExplanationState = .failure(
                    request: request,
                    error: Self.contextExplanationError(for: error)
                )
                contextExplanationTask = nil
            }
        }
    }

    func retryContextExplanation() {
        requestContextExplanation(ignoreCache: true)
    }

    func dismissContextExplanation() {
        contextExplanationTask?.cancel()
        contextExplanationTask = nil
        cancelContextExplanationWatchdog()
        isShowingContextExplanation = false
        contextExplanationState = .idle
    }

    func dismissTranslation() {
        dismissContextExplanation()
        crossPageExpansionTask?.cancel()
        crossPageExpansionTask = nil
        isExpandingCrossPageTranslation = false
        if readerActivityMessage == "正在读取跨页句段…" {
            readerActivityMessage = nil
        }
        cancelTranslationWatchdog()
        backendTranslationTask?.cancel()
        backendTranslationTask = nil
        translationConfiguration = nil
        inFlightTranslationRequestID = nil
        translationState = .idle
        translationAnchorFrame = nil
        isCurrentTranslationFavorite = false
        isCurrentSelectionInVocabulary = false
        vocabularyLookupTask?.cancel()
        vocabularyLookupTask = nil
        isAddingCurrentSelectionToVocabulary = false
        isCurrentSelectionAnnotated = false
        translationRetryCooldownTask?.cancel()
        translationRetryCooldownTask = nil
        isTranslationRetryCoolingDown = false
        stopTranslationSpeech()
        controller?.clearSelection()
    }

    func toggleTranslationSpeech() {
        guard let request = translationState.request,
              let sourceLanguage = request.sourceLanguage
        else { return }

        if isSpeakingTranslationSource {
            stopTranslationSpeech()
            return
        }

        let requestID = request.id
        let playbackID = UUID()
        let spokenText = request.sourceText.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        let totalLength = (spokenText as NSString).length
        guard totalLength > 0 else { return }
        speechPlaybackID = playbackID
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await speechService.speak(
                    spokenText,
                    language: sourceLanguage,
                    rate: speechRate,
                    onRangeChange: { [weak self] range in
                        guard let self,
                              self.speechPlaybackID == playbackID,
                              self.translationState.request?.id == requestID
                        else { return }
                        self.controller?.updateSpeechHighlight(
                            ReaderSpeechHighlight(
                                utf16Range: range,
                                totalLength: totalLength
                            )
                        )
                    },
                    onFinish: { [weak self] _ in
                        guard let self,
                              self.speechPlaybackID == playbackID
                        else { return }
                        self.speechPlaybackID = nil
                        self.isSpeakingTranslationSource = false
                        self.controller?.updateSpeechHighlight(nil)
                    }
                )
                guard translationState.request?.id == requestID,
                      speechPlaybackID == playbackID
                else { return }
                isSpeakingTranslationSource = true
            } catch {
                guard translationState.request?.id == requestID,
                      speechPlaybackID == playbackID
                else { return }
                speechPlaybackID = nil
                isSpeakingTranslationSource = false
                controller?.updateSpeechHighlight(nil)
                readerAlertMessage = Self.message(for: error)
            }
        }
    }

    func flushProgress() {
        saveTask?.cancel()
        saveTask = nil
        pauseReadingSession()
        if let pendingLocator {
            persist(locator: pendingLocator)
        } else {
            saveModelChanges()
            activePublicationService?.reassertBookFileMetadata()
        }
    }

    func close() {
        guard !isClosed else { return }
        isClosed = true
        quickTranslationIndicatorDidDisappear()
        loadAttemptID = nil
        publicationOpenTask?.cancel()
        publicationOpenTask = nil
        loadWatchdogTask?.cancel()
        loadWatchdogTask = nil
        dismissTranslation()
        flushProgress()
        searchTask?.cancel()
        searchTask = nil
        controller?.onLocationChange = nil
        controller?.onSelectedText = nil
        controller?.onSelectionFrameChange = nil
        controller?.onContentTap = nil
        controller?.onReaderError = nil
        controller?.onReaderActivityChange = nil
        controller?.onPaginationModeChange = nil
        controller?.onAnnotationActivated = nil
        controller = nil
        readerActivityMessage = nil
        publication?.close()
        publication = nil
        activePublicationService?.finishBookFileAccess()
        activePublicationService = nil
    }

    private func receive(locator: Locator) {
        if pendingLocator != nil, translationState.request != nil {
            dismissTranslation()
        }
        pendingLocator = locator
        currentLocator = locator
        // The outline is checked first because its titles can be repaired from
        // document headings; a raw locator title is whatever the publication's
        // navigation document happened to contain, which is sometimes just a
        // running number.
        chapterTitle = ReaderChapterTitle.display(
            outlineTitle: currentOutlineItem?.title,
            locatorTitle: locator.title,
            bookTitle: book.title
        )
        progress = min(max(locator.locations.totalProgression ?? 0, 0), 1)
        isCurrentLocationBookmarked = bookmarkStore.isBookmarked(
            bookID: book.id,
            locator: locator
        )

        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(700))
            guard !Task.isCancelled, let self, let locator = self.pendingLocator else { return }
            self.persist(locator: locator)
        }
    }

    private func receivePaginationMode(_ isPaginated: Bool) {
        isPageLocked = isPaginated
    }

    /// Updates only the pop-up anchor. This deliberately does not create a
    /// translation request, so dragging a handle can move the pop-up at the
    /// display refresh rate without repeatedly contacting a translation
    /// provider.
    func receiveSelectionFrame(_ frame: CGRect?) {
        guard let frame,
              frame.minX.isFinite,
              frame.minY.isFinite,
              frame.width.isFinite,
              frame.height.isFinite,
              !frame.isEmpty
        else {
            guard translationAnchorFrame != nil else { return }
            translationAnchorFrame = nil
            return
        }
        let standardized = frame.standardized
        if let current = translationAnchorFrame,
           abs(current.minX - standardized.minX) < 0.75,
           abs(current.minY - standardized.minY) < 0.75,
           abs(current.width - standardized.width) < 0.75,
           abs(current.height - standardized.height) < 0.75
        {
            return
        }
        translationAnchorFrame = standardized
    }

    private func persist(locator: Locator) {
        book.lastReadLocatorJSON = locator.readerJSONString
        book.lastReadProgress = min(max(locator.locations.totalProgression ?? progress, 0), 1)
        book.lastOpenedAt = Date()
        saveModelChanges()
        activePublicationService?.reassertBookFileMetadata()
    }

    func setReadingActive(_ active: Bool) {
        isReadingSceneActive = active
        if active {
            resumeReadingSession()
        } else {
            pauseReadingSession()
            flushProgress()
        }
    }

    private func resumeReadingSession() {
        guard case .ready = loadState,
              readingSessionStartedAt == nil,
              !isClosed
        else { return }
        readingSessionStartedAt = Date()
    }

    private func pauseReadingSession() {
        guard let startedAt = readingSessionStartedAt else { return }
        readingSessionStartedAt = nil
        let elapsed = Date().timeIntervalSince(startedAt)
        guard elapsed.isFinite, elapsed > 0 else { return }
        // Scene phase and reader dismissal normally bound a session. The cap
        // prevents an unexpected lifecycle callback gap from inflating stats.
        book.totalReadingSeconds += min(elapsed, 6 * 60 * 60)
        saveModelChanges()
    }

    private func navigate(to locator: Locator) {
        guard let controller else { return }
        dismissTranslation()
        controller.navigate(to: locator)
    }

    private func refreshBookmarks() {
        bookmarks = bookmarkStore.bookmarks(for: book.id)
        guard let locator = currentLocator ?? controller?.currentLocator else {
            isCurrentLocationBookmarked = false
            return
        }
        isCurrentLocationBookmarked = bookmarkStore.isBookmarked(
            bookID: book.id,
            locator: locator
        )
    }

    private func refreshAnnotations() {
        annotations = annotationStore.annotations(for: book.id)
        controller?.applyAnnotations(annotations.map(ReaderAnnotationPresentation.init))
        refreshCurrentAnnotationState(for: translationState.request)
    }

    private func refreshCurrentAnnotationState(
        for request: ReaderTranslationRequest?
    ) {
        guard let request,
              let locatorJSON = request.annotationLocatorJSON
        else {
            isCurrentSelectionAnnotated = false
            return
        }
        isCurrentSelectionAnnotated = annotationStore.annotation(
            bookID: book.id,
            locatorJSON: locatorJSON,
            selectedText: request.sourceText
        ) != nil
    }

    private func annotationDraftForCurrentSelection() -> ReaderAnnotationEditorDraft? {
        guard let request = translationState.request,
              let locatorJSON = request.annotationLocatorJSON,
              request.trigger != .crossPageExpansion
        else { return nil }
        if let existing = annotationStore.annotation(
            bookID: book.id,
            locatorJSON: locatorJSON,
            selectedText: request.sourceText
        ) {
            return ReaderAnnotationEditorDraft(
                annotationID: existing.id,
                locatorJSON: existing.locatorJSON,
                anchorJSON: request.annotationAnchorJSON ?? existing.anchorJSON,
                selectedText: existing.selectedText,
                noteText: existing.noteText,
                color: existing.color,
                chapterTitle: existing.chapterTitle,
                progress: existing.progress
            )
        }
        let locatorProgress = (try? Locator(jsonString: locatorJSON))?
            .locations.totalProgression
        return ReaderAnnotationEditorDraft(
            annotationID: nil,
            locatorJSON: locatorJSON,
            anchorJSON: request.annotationAnchorJSON,
            selectedText: request.sourceText,
            noteText: "",
            color: .yellow,
            chapterTitle: chapterTitle,
            progress: locatorProgress ?? progress
        )
    }

    private func editAnnotation(withID annotationID: UUID) {
        guard let annotation = annotations.first(where: { $0.id == annotationID }) else {
            return
        }
        editAnnotation(annotation)
    }

    private func applyPreferencesAndSave() {
        if translationState.request != nil {
            dismissTranslation()
        }
        controller?.applyPreferences(
            fontSize: fontSize,
            theme: theme,
            fontChoice: fontChoice,
            lineHeight: lineHeight,
            paragraphSpacing: paragraphSpacing,
            pageMargins: pageMargins,
            textOrientation: textOrientation,
            readingMode: readingMode,
            customBackgroundHex: customBackgroundHex,
            customSelectionColorHex: customSelectionColorHex
        )
        saveModelChanges()
    }

    private func saveModelChanges() {
        do {
            try modelContext.save()
            hasReportedPersistenceFailure = false
        } catch {
            guard !hasReportedPersistenceFailure else { return }
            hasReportedPersistenceFailure = true
            readerAlertMessage = "阅读进度或设置暂时无法保存。请检查本机存储空间；App 会在下一次进度更新时继续尝试。"
        }
    }

    private func startTranslation(
        for request: ReaderTranslationRequest,
        ignoreCache: Bool = false
    ) {
        guard translationState.request?.id == request.id,
              let sourceLanguage = request.sourceLanguage
        else { return }

        if !ignoreCache,
           let result = translationCache.result(
               for: request.sourceText,
               contextText: cacheContext(for: request),
               sourceLanguage: sourceLanguage,
               targetLanguage: request.targetLanguage,
               providerIdentifier: request.providerIdentifier,
               providerVersion: request.providerVersion
           )
        {
            cancelTranslationWatchdog(matching: request.id)
            translationConfiguration = nil
            translationState = .success(
                request: request,
                result: result,
                source: .cache
            )
            refreshFavoriteState(for: result, request: request)
            refreshVocabularyState(for: request)
            return
        }

        switch request.provider {
        case .apple:
            configureAppleTranslation(for: request, forceRestart: false)
            scheduleTranslationWatchdog(for: request)

        case .directAPI:
            translationConfiguration = nil
            guard let configuration = translationSettings.directAPIConfiguration else {
                translationState = .failure(
                    request: request,
                    error: .providerConfigurationInvalid
                )
                return
            }
            let service = DirectAITranslationService(configuration: configuration)
            backendTranslationTask = Task { [weak self] in
                guard let self else { return }
                await self.performPendingTranslation(using: service)
            }
            scheduleTranslationWatchdog(for: request)

        case .backendProxy:
            translationConfiguration = nil
            guard let configuration = translationSettings.backendConfiguration else {
                translationState = .failure(
                    request: request,
                    error: .providerConfigurationInvalid
                )
                return
            }
            let service = backendTranslationServiceFactory(configuration)
            backendTranslationTask = Task { [weak self] in
                guard let self else { return }
                await self.performPendingTranslation(using: service)
            }
            scheduleTranslationWatchdog(for: request)
        }
    }

    private func configureAppleTranslation(
        for request: ReaderTranslationRequest,
        forceRestart: Bool
    ) {
        guard let sourceLanguage = request.sourceLanguage else { return }

        if var configuration = translationConfiguration {
            configuration.source = sourceLanguage.localeLanguage
            configuration.target = request.targetLanguage.localeLanguage
            configuration.invalidate()
            translationConfiguration = configuration
        } else {
            var configuration = TranslationSession.Configuration(
                source: sourceLanguage.localeLanguage,
                target: request.targetLanguage.localeLanguage
            )
            if forceRestart {
                configuration.invalidate()
            }
            translationConfiguration = configuration
        }
    }

    private func scheduleTranslationWatchdog(for request: ReaderTranslationRequest) {
        cancelTranslationWatchdog()
        let requestID = request.id
        translationWatchdogRequestID = requestID
        translationWatchdogTask = Task { @MainActor [weak self] in
            guard let self else { return }

            do {
                if request.provider == .apple {
                    try await Task.sleep(for: translationTiming.appleLaunchRetryDelay)
                    guard isLoadingTranslation(requestID) else { return }

                    // SwiftUI can occasionally fail to materialize a new
                    // TranslationSession task after a rapid configuration
                    // change. Invalidate once if the action never entered.
                    if inFlightTranslationRequestID != requestID {
                        configureAppleTranslation(for: request, forceRestart: true)
                        try await Task.sleep(for: translationTiming.appleLaunchTimeout)
                        guard isLoadingTranslation(requestID) else { return }

                        // A session that still has not entered after the
                        // invalidation is a launch failure, not a language-pack
                        // download. Exit quickly instead of showing an endless
                        // loading card.
                        guard inFlightTranslationRequestID == requestID else {
                            finishTranslationTimeout(for: request)
                            return
                        }
                    }
                    try await Task.sleep(for: translationTiming.appleRequestTimeout)
                    guard isLoadingTranslation(requestID) else { return }
                    finishTranslationTimeout(
                        for: request,
                        error: .appleLanguagePreparationTimedOut
                    )
                    return
                } else {
                    try await Task.sleep(for: translationTiming.backendRequestTimeout)
                }
            } catch {
                return
            }

            guard isLoadingTranslation(requestID) else { return }
            finishTranslationTimeout(for: request)
        }
    }

    private func finishTranslationTimeout(
        for request: ReaderTranslationRequest,
        error: ReaderTranslationDisplayError = .timedOut
    ) {
        guard isLoadingTranslation(request.id) else { return }
        backendTranslationTask?.cancel()
        backendTranslationTask = nil
        translationConfiguration = nil
        if inFlightTranslationRequestID == request.id {
            inFlightTranslationRequestID = nil
        }
        if translationWatchdogRequestID == request.id {
            translationWatchdogRequestID = nil
            translationWatchdogTask = nil
        }
        if beginAutomaticFallback(from: request) {
            return
        }
        translationState = .failure(request: request, error: error)
    }

    private func cancelTranslationWatchdog(matching requestID: UUID? = nil) {
        guard requestID == nil || translationWatchdogRequestID == requestID else { return }
        translationWatchdogTask?.cancel()
        translationWatchdogTask = nil
        translationWatchdogRequestID = nil
    }

    private func isLoadingTranslation(_ requestID: UUID) -> Bool {
        guard case let .loading(request) = translationState else { return false }
        return request.id == requestID
    }

    private func refreshFavoriteState(
        for result: TranslationResult,
        request: ReaderTranslationRequest
    ) {
        isCurrentTranslationFavorite = translationFavorites.isFavorite(
            bookID: request.bookID,
            sourceText: result.sourceText,
            sourceLanguage: result.sourceLanguage,
            targetLanguage: result.targetLanguage
        )
    }

    private func refreshVocabularyState(for request: ReaderTranslationRequest) {
        guard let language = request.sourceLanguage,
              let term = ReaderVocabularyCandidate.term(
                  from: request.sourceText,
                  language: language
              )
        else {
            isCurrentSelectionInVocabulary = false
            return
        }
        isCurrentSelectionInVocabulary = WordLookupStore.isFavorite(
            word: term,
            language: language,
            in: modelContext
        )
    }

    private func crossPageExpansion(
        for request: ReaderTranslationRequest,
        contextText: String?
    ) -> ReaderCrossPageExpansion? {
        ReaderCrossPageTranslationResolver.expansion(
            sourceText: request.sourceText,
            contextText: contextText,
            language: request.sourceLanguage,
            maximumCharacterCount: Self.maxTranslationCharacterCount
        )
    }

    private func crossPagePayload(
        _ expansion: ReaderCrossPageExpansion,
        request: ReaderTranslationRequest
    ) -> ReaderSelectionPayload {
        ReaderSelectionPayload(
            text: expansion.text,
            contextText: expansion.contextText,
            locatorJSON: request.locatorJSON,
            annotationLocatorJSON: nil,
            annotationAnchorJSON: nil,
            allowsAnnotation: false,
            frame: translationAnchorFrame ?? request.selectionFrame,
            focusFrame: request.focusFrame,
            trigger: .crossPageExpansion
        )
    }

    /// Apple Translation does not consume the optional context, so including
    /// it in the persistent key would create duplicate cache entries for the
    /// same sentence selected through different interaction layers. AI proxy
    /// translations keep the context in their key because it can affect output.
    private func cacheContext(for request: ReaderTranslationRequest) -> String? {
        switch request.provider {
        case .apple:
            return nil
        case .directAPI, .backendProxy:
            return request.contextText
        }
    }

    private func providerDescriptor() -> (
        choice: TranslationProviderChoice,
        identifier: String,
        version: String
    ) {
        providerDescriptor(for: translationSettings.provider)
    }

    private func preferredSourceLanguage(
        for text: String
    ) -> LanguageCode? {
        translationSettings.sourceLanguageChoice.languageCode
            ?? ReaderLanguageDetector.detect(
                text: text,
                bookLanguage: book.language
            )
    }

    private func resolvedContextExplanationService() -> (
        service: any ContextExplanationService,
        identifier: String,
        version: String
    )? {
        func direct() -> (
            service: any ContextExplanationService,
            identifier: String,
            version: String
        )? {
            guard let configuration = translationSettings.directAPIConfiguration else {
                return nil
            }
            let service = DirectAITranslationService(configuration: configuration)
            return (
                service,
                service.providerIdentifier,
                service.providerVersion
            )
        }

        func backend() -> (
            service: any ContextExplanationService,
            identifier: String,
            version: String
        )? {
            guard let configuration = translationSettings.backendConfiguration else {
                return nil
            }
            let translationService = backendTranslationServiceFactory(configuration)
            let service = (translationService as? any ContextExplanationService)
                ?? BackendTranslationService(configuration: configuration)
            return (
                service,
                BackendTranslationService.providerIdentifier,
                BackendTranslationService.providerVersion(for: configuration)
            )
        }

        switch translationSettings.provider {
        case .directAPI:
            return direct() ?? backend()
        case .backendProxy:
            return backend() ?? direct()
        case .apple:
            return direct() ?? backend()
        }
    }

    private func contextExplanationCacheKey(
        for request: ReaderContextExplanationRequest
    ) -> String {
        ContextExplanationInputPolicy.semanticIdentity(
            for: ContextExplanationRequest(
                focusedText: request.focusedText,
                contextText: request.contextText,
                sourceLanguage: request.sourceLanguage,
                responseLanguage: request.responseLanguage
            ),
            providerIdentifier: request.providerIdentifier,
            providerVersion: request.providerVersion
        )
    }

    private func scheduleContextExplanationWatchdog(
        for request: ReaderContextExplanationRequest
    ) {
        cancelContextExplanationWatchdog()
        let requestID = request.id
        let timeout = translationTiming.contextExplanationRequestTimeout
        // Keep the timer off the main actor. Reader presentation, PDFKit and
        // SwiftData can briefly monopolize the UI executor while a sheet is
        // appearing; a main-actor sleep task may then wake much later than the
        // requested deadline and leave the explanation sheet spinning.
        contextExplanationWatchdogTask = Task.detached { [weak self] in
            do {
                try await Task.sleep(for: timeout)
            } catch {
                return
            }
            guard !Task.isCancelled else { return }
            await self?.finishContextExplanationTimeout(
                requestID: requestID,
                request: request
            )
        }
    }

    private func finishContextExplanationTimeout(
        requestID: UUID,
        request: ReaderContextExplanationRequest
    ) {
        guard !Task.isCancelled,
              case let .loading(active) = contextExplanationState,
              active.id == requestID
        else { return }

        contextExplanationTask?.cancel()
        contextExplanationTask = nil
        contextExplanationWatchdogTask = nil
        contextExplanationState = .failure(
            request: request,
            error: .timedOut
        )
    }

    private func cancelContextExplanationWatchdog(
        matching requestID: UUID? = nil
    ) {
        if let requestID,
           case let .loading(active) = contextExplanationState,
           active.id != requestID
        {
            return
        }
        contextExplanationWatchdogTask?.cancel()
        contextExplanationWatchdogTask = nil
    }

    private func providerDescriptor(
        for choice: TranslationProviderChoice
    ) -> (
        choice: TranslationProviderChoice,
        identifier: String,
        version: String
    ) {
        switch choice {
        case .apple:
            return (
                .apple,
                TranslationCacheStore.appleProviderIdentifier,
                TranslationCacheStore.appleProviderVersion
            )
        case .directAPI:
            let configuration = translationSettings.directAPIConfiguration
            let provider = configuration?.provider ?? translationSettings.directAPIProvider
            let version = configuration.map {
                DirectAITranslationService.providerVersion(for: $0)
            } ?? "unconfigured-v1"
            return (
                .directAPI,
                DirectAITranslationService.providerIdentifier(for: provider),
                version
            )
        case .backendProxy:
            let version = translationSettings.backendConfiguration.map {
                BackendTranslationService.providerVersion(for: $0)
            } ?? "unconfigured-v1"
            return (
                .backendProxy,
                BackendTranslationService.providerIdentifier,
                version
            )
        }
    }

    private func translateWithAutomaticRetry(
        using service: any TranslationService,
        request: ReaderTranslationRequest
    ) async throws -> TranslationResult {
        var didRetry = false
        while true {
            do {
                let rawResult = try await service.translate(
                    text: request.sourceText,
                    context: request.contextText,
                    sourceLanguage: request.sourceLanguage,
                    targetLanguage: request.targetLanguage
                )
                guard let result = TranslationOutputPolicy.validated(rawResult) else {
                    throw ServiceError.resultNotFound
                }
                return result
            } catch {
                guard translationSettings.automaticRetryEnabled,
                      !didRetry,
                      Self.isTransientTranslationError(error),
                      !Task.isCancelled
                else {
                    throw error
                }
                didRetry = true
                try await Task.sleep(for: .milliseconds(450))
            }
        }
    }

    private func beginAutomaticFallback(from request: ReaderTranslationRequest) -> Bool {
        guard !request.didUseFallback,
              let fallback = translationSettings.fallbackProvider.provider,
              fallback != request.provider,
              isProviderConfigured(fallback)
        else {
            return false
        }

        let provider = providerDescriptor(for: fallback)
        let fallbackRequest = ReaderTranslationRequest(
            id: UUID(),
            bookID: request.bookID,
            sourceText: request.sourceText,
            contextText: request.contextText,
            sourceLanguage: request.sourceLanguage,
            targetLanguage: request.targetLanguage,
            provider: provider.choice,
            providerIdentifier: provider.identifier,
            providerVersion: provider.version,
            locatorJSON: request.locatorJSON,
            annotationLocatorJSON: request.annotationLocatorJSON,
            annotationAnchorJSON: request.annotationAnchorJSON,
            selectionFrame: translationAnchorFrame ?? request.selectionFrame,
            focusFrame: request.focusFrame,
            trigger: request.trigger,
            createdAt: Date(),
            didUseFallback: true
        )
        translationConfiguration = nil
        isCurrentTranslationFavorite = false
        translationState = .loading(fallbackRequest)
        refreshTranslationRetryCooldown(for: fallbackRequest)
        startTranslation(for: fallbackRequest)
        return true
    }

    private func beginTranslationRetryCooldown(for request: ReaderTranslationRequest) {
        let clock = ContinuousClock()
        translationRetryDeadlines[request.retryIdentity] = clock.now.advanced(
            by: translationTiming.manualRetryCooldown
        )
        refreshTranslationRetryCooldown(for: request)
    }

    private func refreshTranslationRetryCooldown(
        for request: ReaderTranslationRequest?
    ) {
        translationRetryCooldownTask?.cancel()
        translationRetryCooldownTask = nil

        let clock = ContinuousClock()
        let now = clock.now
        translationRetryDeadlines = translationRetryDeadlines.filter { $0.value > now }
        guard let request,
              let deadline = translationRetryDeadlines[request.retryIdentity],
              deadline > now
        else {
            isTranslationRetryCoolingDown = false
            return
        }

        isTranslationRetryCoolingDown = true
        let retryIdentity = request.retryIdentity
        translationRetryCooldownTask = Task { @MainActor [weak self] in
            do {
                try await clock.sleep(until: deadline)
            } catch {
                return
            }
            guard let self,
                  self.translationState.request?.retryIdentity == retryIdentity
            else { return }
            self.translationRetryDeadlines.removeValue(forKey: retryIdentity)
            self.translationRetryCooldownTask = nil
            self.isTranslationRetryCoolingDown = false
        }
    }

    private func isProviderConfigured(_ provider: TranslationProviderChoice) -> Bool {
        switch provider {
        case .apple:
            return true
        case .directAPI:
            return translationSettings.directAPIConfiguration != nil
        case .backendProxy:
            return translationSettings.backendConfiguration != nil
        }
    }

    private static func isTransientTranslationError(_ error: Error) -> Bool {
        if let serviceError = error as? ServiceError {
            return serviceError == .temporarilyUnavailable
                || serviceError == .translationUnavailable
                || serviceError == .resultNotFound
        }
        let value = error as NSError
        guard value.domain == NSURLErrorDomain else { return false }
        return [
            NSURLErrorTimedOut,
            NSURLErrorCannotFindHost,
            NSURLErrorCannotConnectToHost,
            NSURLErrorNetworkConnectionLost,
            NSURLErrorNotConnectedToInternet,
            NSURLErrorDNSLookupFailed,
        ].contains(value.code)
    }

    private func stopTranslationSpeech() {
        speechPlaybackID = nil
        speechService.stop()
        isSpeakingTranslationSource = false
        controller?.updateSpeechHighlight(nil)
    }

    private static func resultSource(
        for result: TranslationResult
    ) -> ReaderTranslationResultSource {
        if result.isFromCache {
            return .cache
        }
        if result.providerIdentifier == "mock" {
            return .mock
        }
        if result.providerIdentifier.hasPrefix("direct-ai-") {
            return .directAPI
        }
        if result.providerIdentifier == BackendTranslationService.providerIdentifier {
            return .backendProxy
        }
        return .appleTranslation
    }

    private static func translationDisplayError(
        for error: Error,
        provider: TranslationProviderChoice
    ) -> ReaderTranslationDisplayError {
        if error is CancellationError {
            return .userCancelled
        }

        switch error as? ServiceError {
        case .emptyText:
            return .emptySelection
        case .textTooLong:
            return .textTooLong(limit: maxTranslationCharacterCount)
        case .unsupportedLanguage:
            return .unsupportedLanguage
        case .languageDownloadDeclined:
            return .languageDownloadDeclined
        case .invalidConfiguration:
            return .providerConfigurationInvalid
        case .authenticationFailed:
            return .providerAuthenticationFailed
        case .temporarilyUnavailable where provider.isNetworkProvider:
            return .providerUnavailable
        case .resultNotFound where provider.isNetworkProvider:
            return .providerUnavailable
        case .translationUnavailable, .temporarilyUnavailable, .resultNotFound:
            return .translationUnavailable
        case .voiceUnavailable:
            return .unknown
        case nil:
            return provider.isNetworkProvider ? .providerUnavailable : .unknown
        }
    }

    private static func contextExplanationError(
        for error: Error
    ) -> ReaderContextExplanationError {
        switch error as? ServiceError {
        case .invalidConfiguration:
            return .configurationInvalid
        case .authenticationFailed:
            return .authenticationFailed
        case .emptyText, .textTooLong, .unsupportedLanguage:
            return .invalidSelection
        case .resultNotFound:
            return .emptyResponse
        case .languageDownloadDeclined, .temporarilyUnavailable,
             .translationUnavailable, .voiceUnavailable, nil:
            return .unavailable
        }
    }

    private static func message(for error: Error) -> String {
        (error as? LocalizedError)?.errorDescription
            ?? "操作没有完成，请稍后重试。"
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
