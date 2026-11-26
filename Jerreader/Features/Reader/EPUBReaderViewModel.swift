import Foundation
import Observation
import OSLog
@preconcurrency import ReadiumNavigator
@preconcurrency import ReadiumShared
import SwiftData
@preconcurrency import Translation

/// Observed by SwiftUI through the Observation framework: a view redraws only
/// when a property it actually read changes, instead of on every change of
/// any published property as with `ObservableObject`. Bookkeeping state no
/// view reads (tasks, caches, identifiers) is `@ObservationIgnored`, just as
/// it was never published before.
@MainActor
@Observable
final class EPUBReaderViewModel {
    private(set) var loadState: ReaderLoadState = .loading
    private(set) var controller: (any PublicationReaderController)?
    private(set) var outline: [ReaderOutlineItem] = []
    private(set) var chapterTitle: String
    private(set) var progress: Double = 0
    private(set) var bookmarks: [ReadingBookmarkRecord] = []
    private(set) var annotations: [ReadingAnnotationRecord] = []
    private(set) var isCurrentLocationBookmarked = false
    let searchModel = ReaderSearchModel()
    let companion: ReaderCompanionModel
    var searchResults: [ReaderSearchResult] { searchModel.results }
    var isSearching: Bool { searchModel.isSearching }
    var searchErrorMessage: String? { searchModel.errorMessage }
    var lastSearchQuery: String { searchModel.lastQuery }
    /// Whether the reader asked for its bars. In the standard layout the bars
    /// never leave the screen, so read `isChromeVisible` to know what shows.
    var controlsVisible = true
    /// Standard keeps the bars and lays the page out between them; immersive
    /// gives the page the whole screen and shows the bars only on request.
    private(set) var layoutMode: ReaderLayoutMode
    var isChromeVisible: Bool {
        layoutMode == .standard || controlsVisible
    }
    var readerAlertMessage: String?
    private(set) var readerActivityMessage: String?

    private(set) var fontSize: Double
    private(set) var theme: ReaderThemeChoice
    private(set) var fontChoice: ReaderFontChoice
    private(set) var lineHeight: Double
    private(set) var paragraphSpacing: Double
    private(set) var pageMargins: Double
    private(set) var customBackgroundHex: String
    private(set) var customSelectionColorHex: String
    private(set) var textOrientation: ReaderTextOrientationChoice
    private(set) var readingMode: ReaderReadingMode
    private(set) var publicationLayout: ReaderPublicationLayout
    private(set) var isPageLocked = true

    private(set) var translationConfiguration: TranslationSession.Configuration?
    private(set) var translationState: ReaderTranslationState = .idle
    /// The translation so far while an AI model streams it (2.1.0). The card
    /// shows it while `translationState` stays `.loading`, so the card's
    /// phase, height animation and success haptic wait for the whole answer.
    private(set) var streamingTranslation: ReaderStreamingText?
    /// The translation card's AI bar (2.1.0).
    let cardAI = ReaderCardAIModel()
    /// 「第 N 次查 · 上次 MM-dd」 over a one-word card's translation (2.1.0 §8).
    private(set) var currentLookupHint: String?
    /// How often the one word of this card has been looked up, this card included.
    private(set) var currentLookupCount = 0
    /// 「本章还剩约 N 分钟」 under the progress row (2.1.0 §9); nil shows nothing.
    private(set) var chapterTimeEstimate: String?
    private(set) var translationAnchorFrame: CGRect?
    private(set) var isSpeakingTranslationSource = false
    private(set) var speechRate: Double
    private(set) var isCurrentTranslationFavorite = false
    private(set) var isCurrentSelectionInVocabulary = false
    private(set) var isAddingCurrentSelectionToVocabulary = false
    private(set) var isExpandingCrossPageTranslation = false
    private(set) var isTranslationRetryCoolingDown = false
    private(set) var isCurrentSelectionAnnotated = false
    var presentedAnnotationEditor: ReaderAnnotationEditorDraft?
    private(set) var contextExplanationState: ReaderContextExplanationState = .idle
    var isShowingContextExplanation = false
    private(set) var isQuickSentenceTranslationEnabled: Bool
    private(set) var isQuickTranslationIndicatorVisible: Bool
    private(set) var quickTranslationUnit: ReaderQuickTranslationUnit
    private(set) var disablesTapPageTurnsDuringQuickTranslation: Bool
    private(set) var isPDFPaperModeEnabled: Bool

    private let initialLocatorJSON: String?
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
    @ObservationIgnored private var publication: Publication?
    @ObservationIgnored private var activePublicationService: (any ReaderPublicationOpening)?
    @ObservationIgnored private var pendingLocator: Locator?
    @ObservationIgnored private var currentLocator: Locator?
    @ObservationIgnored private var saveTask: Task<Void, Never>?
    @ObservationIgnored private var backendTranslationTask: Task<Void, Never>?
    @ObservationIgnored private var translationWatchdogTask: Task<Void, Never>?
    @ObservationIgnored private var translationWatchdogRequestID: UUID?
    /// The deltas of the AI translation in flight, which keep its watchdog waiting.
    @ObservationIgnored private var translationStreamActivity: (requestID: UUID, activity: AIStreamActivity)?
    @ObservationIgnored private var translationThrottle: (requestID: UUID, throttle: AIPartialTextThrottle)?
    @ObservationIgnored private var inFlightTranslationRequestID: UUID?
    @ObservationIgnored private var translationRetryCooldownTask: Task<Void, Never>?
    @ObservationIgnored private var quickTranslationIndicatorAutoExitTask: Task<Void, Never>?
    @ObservationIgnored private var chromeAutoHideTask: Task<Void, Never>?
    /// Tests shorten this; the app always uses the shared default.
    @ObservationIgnored var chromeAutoHideDelay = ReaderInterfaceDefaults.immersiveChromeAutoHideDelay
    @ObservationIgnored private var isChromeAutoHideSuspended = false
    @ObservationIgnored private var contextExplanationTask: Task<Void, Never>?
    @ObservationIgnored private var contextExplanationWatchdogTask: Task<Void, Never>?
    @ObservationIgnored private var crossPageExpansionTask: Task<Void, Never>?
    var isShowingContextualLookup = false
    /// The word a short tap looked up. The lookup sheet opens on it instead
    /// of the translation card's text; nil once that sheet has gone.
    private(set) var shortTapLookup: ReaderShortTapLookup?
    @ObservationIgnored private var vocabularyLookupTask: Task<Void, Never>?
    @ObservationIgnored private var speechPlaybackID: UUID?
    @ObservationIgnored private var contextExplanationCache: [String: ContextExplanationResult] = [:]
    @ObservationIgnored private var translationRetryDeadlines:
        [ReaderTranslationRetryIdentity: ContinuousClock.Instant] = [:]
    @ObservationIgnored private var readingSessionStartedAt: Date?
    /// One per translation card: a retry or another source language keeps
    /// it, a new sentence or a closed card replaces it (2.1.0 §8).
    @ObservationIgnored private var translationCardID = UUID()
    @ObservationIgnored private var recordedLookupCardID: UUID?
    /// The lookup key of the word `recordedLookupCardID`'s card counted,
    /// once its lookup has been recorded.
    @ObservationIgnored private var countedCardLookupKey: String?
    /// The card's own dictionary lookup of its word, which 加入生词本 waits
    /// for so the word is counted once.
    @ObservationIgnored private var cardLookupTask: (cardID: UUID, task: Task<Void, Never>)?
    /// The book's previous open, read before this open is recorded (2.1.0 §10).
    private let previousOpenedAt: Date?
    /// Where each reading-order resource starts in the book (2.1.0 §9).
    @ObservationIgnored private var chapterStarts: [Double] = []
    @ObservationIgnored private var chapterEstimateTask: Task<Void, Never>?
    @ObservationIgnored private var loadAttemptID: UUID?
    @ObservationIgnored private var publicationOpenTask: Task<Publication, Error>?
    @ObservationIgnored private var loadWatchdogTask: Task<Void, Never>?
    @ObservationIgnored private var isClosed = false
    @ObservationIgnored private var isReadingSceneActive = false
    @ObservationIgnored private var hasReportedPersistenceFailure = false

    init(
        book: BookRecord,
        modelContext: ModelContext,
        speechService: any SpeechService = SystemSpeechService.shared,
        lexicalLookupService: any LexicalLookupService =
            OfflineFirstLexicalLookupService(),
        translationSettings: TranslationSettingsStore? = nil,
        initialLocatorJSON: String? = nil,
        publicationService: (any ReaderPublicationOpening)? = nil,
        loadTiming: ReaderLoadTiming = .standard,
        translationTiming: ReaderTranslationTiming = .standard,
        quickTranslationIndicatorTiming: ReaderQuickTranslationIndicatorTiming = .standard,
        backendTranslationServiceFactory: @escaping
            (BackendTranslationConfiguration) -> any TranslationService = {
                BackendTranslationService(configuration: $0)
            }
    ) {
        self.initialLocatorJSON = initialLocatorJSON
        self.book = book
        // Read before this open's first saved location overwrites it.
        previousOpenedAt = book.lastOpenedAt
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
        let layoutMode = ReaderInterfaceDefaults.layoutMode()
        self.layoutMode = layoutMode
        controlsVisible = layoutMode == .standard
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
        companion = ReaderCompanionModel(bookID: book.id, bookTitle: book.title)
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
        if arguments.contains("--jerreader-offline-translation-ui-test") {
            translationSettings.provider = .apple
            translationSettings.quickSentenceTranslationEnabled = true
            translationSettings.quickTranslationUnit = .sentence
            translationSettings.sourceLanguageChoice = .japanese
            translationSettings.targetLanguage = .simplifiedChinese
            UserDefaults.standard.set(false, forKey: ReaderInterfaceDefaults.japaneseFuriganaKey)
        }
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

        var sourceFrame: CGRect
        switch mode {
        case "vertical", "vertical-left", "vertical-right":
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
            if mode == "vertical-left" { sourceFrame = CGRect(x: 24, y: 180, width: 24, height: 360) }
            else if mode == "vertical-right" { sourceFrame = CGRect(x: windowBounds.width - 48, y: 180, width: 24, height: 360) }
            publicationLayout = .verticalRTL
            textOrientation = .publication
        case "paragraph", "paragraph-top", "paragraph-bottom":
            sourceFrame = CGRect(
                x: 28,
                y: windowBounds.height * 0.56,
                width: max(windowBounds.width - 56, 1),
                height: min(170, windowBounds.height * 0.22)
            )
            if mode == "paragraph-top" { sourceFrame = CGRect(x: 28, y: 100, width: windowBounds.width - 56, height: 60) }
            else if mode == "paragraph-bottom" { sourceFrame = CGRect(x: 28, y: windowBounds.height - 220, width: windowBounds.width - 56, height: 60) }
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
            focusFrame: mode.hasPrefix("vertical") ? sourceFrame : nil,
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
        controlsVisible = arguments.contains("--jerreader-selection-controls-visible")
        UserDefaults.standard.set(arguments.contains("--jerreader-furigana-ui-test"), forKey: ReaderInterfaceDefaults.japaneseFuriganaKey)
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
            if let json = initialLocatorJSON ?? book.lastReadLocatorJSON {
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
                    if initialLocatorJSON == nil {
                        book.lastReadLocatorJSON = nil
                        book.lastReadProgress = 0
                    } else {
                        readerAlertMessage = "保存的原句位置已失效，已打开书籍首页。"
                    }
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
#if DEBUG
                // The card-AI frames (2.1.0 §11) want the sample text in
                // vertical columns too, and it has no vertical styles of
                // its own: this launch reads it as a vertical publication
                // in its original layout. The book's record keeps what was
                // detected and its own 排版方向, so the next launch is as
                // before.
                if ProcessInfo.processInfo.arguments.contains("--jerreader-sample-vertical-ui-test") {
                    publicationLayout = .verticalRTL
                    textOrientation = .publication
                }
#endif
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
                if payload.trigger == .quickWord {
                    self?.requestWordLookup(for: payload)
                } else {
                    self?.requestTranslation(for: payload)
                }
            }
            controller.onSelectionFrameChange = { [weak self] frame in
                self?.receiveSelectionFrame(frame)
            }
            controller.onContentTap = { [weak self] in
                self?.handleContentTap()
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
            companion.bookOpened(atProgress: progress, lastOpenedAt: previousOpenedAt)
            if isReadingSceneActive {
                resumeReadingSession()
            }
            // Chapter boundaries are only needed once the book is on screen,
            // so they must never delay the first paint.
            Task { @MainActor [weak self] in
                await self?.resolvePDFPageCount()
                await self?.resolveChapterBoundaries()
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
        outlineJump = OutlineJump(itemID: item.id, startedAt: Date(), landing: nil)
        controller.navigate(to: item.link)
        // A jump inside the page already on screen moves nothing, so no new
        // position arrives to relabel the bars.
        if let locator = currentLocator {
            chapterTitle = ReaderChapterTitle.display(
                outlineTitle: currentOutlineItem?.title,
                locatorTitle: locator.title,
                bookTitle: book.title
            )
        }
    }

    /// The entry the reader last jumped to through the contents list or the
    /// chapter buttons. A section that starts halfway down a page shares that
    /// page with the end of the one before it, and a page's position is its
    /// top, so by position alone the jump would land "in" the previous
    /// section: the wrong row marked, and 下一章 stuck on the same page.
    /// The jumped-to entry stays current until the reader moves on.
    private struct OutlineJump {
        let itemID: UUID
        let startedAt: Date
        var landing: Locator?
    }

    @ObservationIgnored private var outlineJump: OutlineJump?
    /// A PDF's page count, known shortly after it opens; nil for other books.
    @ObservationIgnored private var pdfPageCount: Int?

    /// Keeps the jumped-to entry while the navigator settles (it may report
    /// more than once on the way) and drops it at the first move after that.
    private func settleOutlineJump(at locator: Locator) {
        guard var jump = outlineJump else { return }
        if Date().timeIntervalSince(jump.startedAt) < 2 {
            jump.landing = locator
            outlineJump = jump
        } else if let landing = jump.landing,
                  landing.href.string == locator.href.string,
                  abs((landing.locations.progression ?? 0)
                      - (locator.locations.progression ?? 0)) < 0.000_001,
                  landing.locations.position == locator.locations.position {
            return
        } else {
            outlineJump = nil
        }
    }

    /// The outline entry the reader is currently inside, used to highlight and
    /// scroll the contents list and to label the progress scrubber.
    var currentOutlineItem: ReaderOutlineItem? {
        guard !outline.isEmpty else { return nil }
        if let locator = currentLocator {
            let key = ReaderOutlineItem.resourceKey(for: locator.href.string)
            // A resource can hold several entries (`chapter.xhtml#part2`, or
            // every chapter of a PDF).
            let matches = outline.filter { $0.resourceKey == key }
            if !matches.isEmpty {
                if let jump = outlineJump,
                   let target = matches.first(where: { $0.id == jump.itemID }) {
                    return target
                }
                return ReaderOutlinePosition.current(
                    among: matches,
                    resourceProgression: locator.locations.progression,
                    totalProgression: locator.locations.totalProgression
                )
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
        let current = currentOutlineItem
        let atChapterStart = (current != nil && outlineJump?.itemID == current?.id)
            || (current?.startProgression).map { progress <= $0 + 0.005 } ?? false
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
    /// position of a resource is the start of an entry pointing at the whole
    /// of it. Entries that share a resource are placed inside it: a PDF
    /// chapter by its page, an EPUB section by where its anchor sits in the
    /// document. This runs once per opened book; the service caches its own
    /// computation.
    private func resolveOutlineProgressions() async {
        guard let publication, !outline.isEmpty else { return }
        guard let positions = await publication.positionsByReadingOrder().getOrNil()
        else { return }

        var startByResource: [String: Double] = [:]
        var endByResource: [String: Double] = [:]
        var positionsByResource: [String: [Locator]] = [:]
        var previousKey: String?
        for (index, resourcePositions) in positions.enumerated() {
            guard let first = resourcePositions.first else { continue }
            let key = ReaderOutlineItem.resourceKey(for: first.href.string)
            let progression = first.locations.totalProgression
                ?? (positions.isEmpty ? 0 : Double(index) / Double(positions.count))
            if startByResource[key] == nil {
                startByResource[key] = progression
                positionsByResource[key] = resourcePositions
                if let previousKey { endByResource[previousKey] = progression }
                previousKey = key
            }
        }
        guard !startByResource.isEmpty else { return }

        // Only documents that hold more than one entry need reading.
        var entriesPerResource: [String: Int] = [:]
        for item in outline {
            entriesPerResource[item.resourceKey, default: 0] += 1
        }
        let anchored = outline.filter { item in
            guard let fragment = item.fragment,
                  entriesPerResource[item.resourceKey, default: 0] > 1
            else { return false }
            return ReaderOutlinePosition.pdfPage(fragment: fragment) == nil
        }
        let anchorFractions = anchored.isEmpty
            ? [:]
            : await ReaderOutlineBuilder.anchorFractions(
                in: publication,
                links: anchored.map(\.link)
            )
        guard !isClosed, !Task.isCancelled else { return }

        var resolved = outline
        for index in resolved.indices {
            let item = resolved[index]
            let key = item.resourceKey
            guard let start = startByResource[key] else { continue }
            if let page = ReaderOutlinePosition.pdfPage(fragment: item.fragment),
               let pages = positionsByResource[key],
               pages.count > 1 {
                // A PDF outline entry: one position per page.
                let target = pages[min(page, pages.count) - 1].locations
                resolved[index].startProgression = target.totalProgression ?? start
                resolved[index].resourceFraction = target.progression
                    ?? Double(min(page, pages.count) - 1) / Double(pages.count)
            } else if let fraction = anchorFractions[item.link.href] {
                let end = max(endByResource[key] ?? 1, start)
                resolved[index].startProgression = start + fraction * (end - start)
                resolved[index].resourceFraction = fraction
            } else {
                resolved[index].startProgression = start
                resolved[index].resourceFraction = 0
            }
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
        searchModel.search(query, in: controller)
    }

    func cancelSearch() {
        searchModel.cancel()
    }

    /// Runs again the search that closing the panel cut short.
    func resumeInterruptedSearch() {
        guard searchModel.wasInterrupted else { return }
        searchModel.search(searchModel.lastQuery, in: controller)
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

    func annotationDraft(
        for annotation: ReadingAnnotationRecord
    ) -> ReaderAnnotationEditorDraft {
        ReaderAnnotationEditorDraft(
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

    func editAnnotation(_ annotation: ReadingAnnotationRecord) {
        presentedAnnotationEditor = annotationDraft(for: annotation)
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
        // Through the countdown, so the immersive layout still hides its bars.
        showReaderControls()
    }

    func setQuickTranslationUnit(_ unit: ReaderQuickTranslationUnit) {
        guard quickTranslationUnit != unit else { return }
        dismissTranslation()
        quickTranslationUnit = unit
        translationSettings.quickTranslationUnit = unit
        controller?.setQuickTranslationUnit(unit)
        showReaderControls()
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
        showReaderControls()
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
        scheduleChromeAutoHide()
    }

    func setLayoutMode(_ mode: ReaderLayoutMode) {
        guard layoutMode != mode else { return }
        layoutMode = mode
        cancelChromeAutoHide()
        // Switching happens from the settings sheet: the standard layout brings
        // the bars back for good, the immersive one starts with them away.
        controlsVisible = mode == .standard
    }

    /// A tap on the page that neither turned it nor hit text to translate.
    /// The standard layout keeps its bars, so only the immersive one reacts.
    func handleContentTap() {
        guard layoutMode == .immersive else {
            controlsVisible = true
            return
        }
        controlsVisible.toggle()
        if controlsVisible {
            scheduleChromeAutoHide()
        } else {
            cancelChromeAutoHide()
        }
    }

    /// Any touch on the bars restarts the immersive auto-hide countdown.
    func noteChromeInteraction() {
        guard layoutMode == .immersive, controlsVisible else { return }
        scheduleChromeAutoHide()
    }

    /// Dragging the progress slider must not lose the bars mid-drag.
    func setChromeAutoHideSuspended(_ suspended: Bool) {
        isChromeAutoHideSuspended = suspended
        if suspended {
            cancelChromeAutoHide()
        } else {
            noteChromeInteraction()
        }
    }

    private func scheduleChromeAutoHide() {
        cancelChromeAutoHide()
        guard layoutMode == .immersive,
              controlsVisible,
              !isChromeAutoHideSuspended,
              !isClosed
        else { return }
        let delay = chromeAutoHideDelay
        // Detached for the same reason as the quick-translation reminder: a
        // task inherited from a view transaction can be cancelled with it.
        chromeAutoHideTask = Task.detached { [weak self] in
            do {
                try await Task.sleep(for: delay)
            } catch {
                return
            }
            guard !Task.isCancelled else { return }
            await self?.hideChromeAfterIdle()
        }
    }

    private func hideChromeAfterIdle() {
        chromeAutoHideTask = nil
        guard layoutMode == .immersive, !isChromeAutoHideSuspended else { return }
        controlsVisible = false
    }

    private func cancelChromeAutoHide() {
        chromeAutoHideTask?.cancel()
        chromeAutoHideTask = nil
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
        beginNewTranslationCard()

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

    /// Short tap on a word: straight to the contextual lookup, which also
    /// writes the learning record, with the tapped sentence as context.
    func requestWordLookup(for payload: ReaderSelectionPayload) {
        let word = TranslationCacheStore.normalizedText(payload.text)
        guard !word.isEmpty else { return }
        let context = payload.contextText
            .map(TranslationCacheStore.normalizedText)
            .flatMap { $0.isEmpty ? nil : $0 } ?? word
        // Only an open card is dismissed: dismissing also clears the page
        // highlight, which should stay on the word while the sheet is up.
        if translationState != .idle {
            dismissTranslation()
        }
        controlsVisible = false
        shortTapLookup = ReaderShortTapLookup(
            word: word,
            context: context,
            language: preferredSourceLanguage(for: context)
        )
        isShowingContextualLookup = true
    }

    func contextualLookupDismissed() {
        guard shortTapLookup != nil else { return }
        shortTapLookup = nil
        controller?.clearSelection()
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
        // The AI answers went with the old translation.
        cardAI.reset()
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
        // The AI answers went with the old translation.
        cardAI.reset()
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
            endTranslationStream(for: requestID)
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
            recordCardLookupIfNeeded(for: request)
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
        let cardID = translationCardID
        let context = request.contextText ?? request.sourceText
        vocabularyLookupTask = Task { @MainActor [weak self] in
            guard let self else { return }
            defer {
                if self.translationState.request?.id == requestID {
                    self.isAddingCurrentSelectionToVocabulary = false
                }
                self.vocabularyLookupTask = nil
            }

            // The card counts its word once (2.1.0 §8). Its own lookup may
            // still be on its way: wait for it, and if it counted the word,
            // only star that record, as Android does.
            if let pending = self.cardLookupTask, pending.cardID == cardID {
                await pending.task.value
            }
            guard !Task.isCancelled,
                  self.translationState.request?.id == requestID
            else { return }
            if let counted = self.cardCountedLookup {
                do {
                    if try WordLookupStore.setFavorite(true, forKey: counted.key, in: self.modelContext) {
                        self.isCurrentSelectionInVocabulary = true
                        self.readerAlertMessage = "“\(term)”已加入生词本。"
                        return
                    }
                } catch {
                    self.readerAlertMessage = "暂时无法加入生词本，请稍后重试。"
                    return
                }
            }

            let explanation: WordExplanation
            do {
                explanation = try await self.lexicalLookupService.lookup(
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
                  self.translationState.request?.id == requestID
            else { return }
            do {
                // The card's own lookup counted nothing: this is the
                // card's one lookup, and its 查词 does not count it again.
                let record = try self.recordCardLookup(explanation, cardID: cardID)
                if !record.isFavorite {
                    try WordLookupStore.setFavorite(
                        true,
                        for: record,
                        in: self.modelContext
                    )
                }
                self.isCurrentSelectionInVocabulary = true
                self.readerAlertMessage = "“\(term)”已加入生词本。"
            } catch {
                self.readerAlertMessage = "暂时无法加入生词本，请稍后重试。"
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

    /// `lensMode` nil is the plain sentence-structure analysis; a mode asks
    /// one of the AI reading lens questions, shaped by the companion mode and
    /// by how this book's lookups and reviews have gone.
    func requestContextExplanation(
        ignoreCache: Bool = false,
        lensMode: AIReadingLensMode? = nil
    ) {
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
        let lens = lensMode.map { mode in
            ReadingLensOptions(
                mode: mode,
                companionMode: companion.mode,
                assist: AdaptiveReadingAssistPolicy.resolve(
                    readingSignals(),
                    mode: companion.mode,
                    bookID: companion.bookID
                )
            )
        }
        let request = ReaderContextExplanationRequest(
            id: UUID(),
            focusedText: prepared.focusedText,
            contextText: prepared.contextText,
            sourceLanguage: prepared.sourceLanguage,
            responseLanguage: prepared.responseLanguage,
            providerIdentifier: resolved.identifier,
            providerVersion: resolved.version,
            lens: lens
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
                        responseLanguage: request.responseLanguage,
                        lens: request.lens
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

    func grammarNoteDraft(request: ReaderContextExplanationRequest, result: ContextExplanationResult) -> GrammarNoteDraft {
        GrammarNoteDraft(title: String(request.focusedText.prefix(40)), sentence: request.focusedText,
            explanation: result.explanation, language: request.sourceLanguage?.rawValue ?? "auto",
            bookID: book.id, bookTitle: book.title,
            locatorJSON: currentLocator?.readerJSONString)
    }

    func retryContextExplanation() {
        requestContextExplanation(
            ignoreCache: true,
            lensMode: currentContextExplanationRequest?.lens?.mode
        )
    }

    private var currentContextExplanationRequest: ReaderContextExplanationRequest? {
        switch contextExplanationState {
        case .idle:
            return nil
        case let .loading(request), let .success(request, _):
            return request
        case let .failure(request, _):
            return request
        }
    }

    // MARK: - Reading companion

    func presentReadingCompanion() {
        companion.present(
            signals: readingSignals(),
            chapterTitle: chapterTitle == book.title ? "" : chapterTitle
        )
    }

    /// What a lens answer was based on, shown under its title.
    func readingLensBasis(for request: ReaderContextExplanationRequest) -> String? {
        guard let lens = request.lens else { return nil }
        var parts = [request.contextText == nil ? "依据：当前选区" : "依据：当前选区 + 有限前后文"]
        if chapterTitle != book.title, !ReadingCompanionText.isBlank(chapterTitle) {
            parts.append(chapterTitle)
        }
        parts.append("\(lens.companionMode.title)伴读")
        parts.append(lens.assistLevel.title)
        parts.append("防剧透")
        return parts.joined(separator: " · ")
    }

    /// The reader's lookups and review results; read on demand, never sent anywhere.
    private func readingSignals() -> [ReadingErrorSignal] {
        let records = (try? modelContext.fetch(FetchDescriptor<WordLookupRecord>())) ?? []
        return ReadingCompanionSignals.from(records)
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
        // Pieces of the closed card's answers must not reach the next card.
        beginNewTranslationCard()
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
#if DEBUG
        (controller as? EPUBReaderViewController)?.clearTranslationUITestDiagnostics()
#endif
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
        cancelChromeAutoHide()
        chapterEstimateTask?.cancel()
        chapterEstimateTask = nil
        loadAttemptID = nil
        publicationOpenTask?.cancel()
        publicationOpenTask = nil
        loadWatchdogTask?.cancel()
        loadWatchdogTask = nil
        dismissTranslation()
        flushProgress()
        searchModel.cancel()
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
        let previousOutlineIndex = currentOutlineIndex
        let previousChapterTitle = chapterTitle
        currentLocator = locator
        settleOutlineJump(at: locator)
        // The outline is checked first because its titles can be repaired from
        // document headings; a raw locator title is whatever the publication's
        // navigation document happened to contain, which is sometimes just a
        // running number.
        chapterTitle = ReaderChapterTitle.display(
            outlineTitle: currentOutlineItem?.title,
            locatorTitle: locator.title,
            bookTitle: book.title
        )
        progress = readingProgress(at: locator, fallback: 0)
        refreshChapterTimeEstimate()
        // Reading on into the next chapter offers its 90-second loop through
        // the AI button's dot; nothing pops up over the page.
        if let previousOutlineIndex,
           currentOutlineIndex == previousOutlineIndex + 1,
           previousChapterTitle != book.title,
           previousChapterTitle != chapterTitle
        {
            companion.chapterFinished(previousChapterTitle)
        }
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

    private func readingProgress(at locator: Locator, fallback: Double) -> Double {
        ReaderPDFProgress.adjusted(
            min(max(locator.locations.totalProgression ?? fallback, 0), 1),
            position: locator.locations.position,
            pageCount: pdfPageCount
        )
    }

    /// Runs once the PDF is on screen; see `ReaderPDFProgress`.
    private func resolvePDFPageCount() async {
        guard book.format == .pdf, let publication,
              let count = await publication.positions().getOrNil()?.count,
              count > 0, !isClosed
        else { return }
        pdfPageCount = count
        if let currentLocator {
            progress = readingProgress(at: currentLocator, fallback: progress)
        }
    }

    private func persist(locator: Locator) {
        book.lastReadLocatorJSON = locator.readerJSONString
        book.lastReadProgress = readingProgress(at: locator, fallback: progress)
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
        if UserDefaults.standard.object(forKey: ReadingActivity.enabledKey) as? Bool ?? true {
            var activity = ReadingActivity.decode(book.activityData)
            activity.record(from: startedAt, to: Date())
            book.activityData = try? JSONEncoder().encode(activity)
        }
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

#if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--jerreader-selection-ui-test") {
            translationConfiguration = nil
            if let frame = request.selectionFrame {
                (controller as? EPUBReaderViewController)?
                    .installTranslationUITestSelection(frameInWindow: frame)
            }
            backendTranslationTask = Task { [weak self] in
                await self?.performPendingTranslation(using: MockTranslationService())
            }
            return
        }
        // The sample-book checks tap a real sentence; the answer comes from
        // the Mock so the run needs neither a network nor a language pack.
        if ProcessInfo.processInfo.arguments.contains("--jerreader-mock-translation-ui-test") {
            translationConfiguration = nil
            // The AI-bar check streams the translation too, and marks where
            // the selection is so it can see the card keep clear of it.
            let streams = Self.isCardAIUITest
            if streams, let frame = request.selectionFrame {
                (controller as? EPUBReaderViewController)?
                    .installTranslationUITestSelection(frameInWindow: frame)
            }
            backendTranslationTask = Task { [weak self] in
                await self?.performPendingTranslation(
                    using: MockTranslationService(streamsPartialText: streams)
                )
            }
            return
        }
#endif

        if UserDefaults.standard.object(forKey: ReadingActivity.enabledKey) as? Bool ?? true {
            var activity = ReadingActivity.decode(book.activityData)
            activity.query(at: Date())
            book.activityData = try? JSONEncoder().encode(activity)
            saveModelChanges()
        }
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
            recordCardLookupIfNeeded(for: request)
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
        // Set before either task runs, so the request's first delta finds it.
        let startedAt = ContinuousClock.now
        let activity = AIStreamActivity(now: startedAt)
        if request.provider != .apple {
            translationStreamActivity = (requestID, activity)
        }
        translationWatchdogTask = Task { @MainActor [weak self] in
            guard let self else { return }
            var timeoutError = ReaderTranslationDisplayError.timedOut

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
                    // A streamed answer gets the same limit again after each
                    // delta, up to the total cap (2.1.0).
                    let total = translationTiming.streamingTotalTimeout
                    try await activity.waitUntilTimedOut(
                        idle: translationTiming.backendRequestTimeout,
                        total: total
                    )
                    // Still being written at the cap, rather than gone quiet.
                    if ContinuousClock.now >= startedAt + total {
                        timeoutError = .streamTimedOut
                    }
                }
            } catch {
                return
            }

            guard isLoadingTranslation(requestID) else { return }
            finishTranslationTimeout(for: request, error: timeoutError)
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
            // An AI feature: a signed-in plan counts (2.1.0 §7).
            guard let configuration = translationSettings.aiFeatureConfiguration else {
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
                responseLanguage: request.responseLanguage,
                lens: request.lens
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
                let rawResult: TranslationResult
                if let streaming = service as? any StreamingTranslationService {
                    // Each attempt starts its partial text afresh.
                    rawResult = try await streaming.translate(
                        text: request.sourceText,
                        context: request.contextText,
                        sourceLanguage: request.sourceLanguage,
                        targetLanguage: request.targetLanguage,
                        onPartialText: beginTranslationStream(for: request.id)
                    )
                } else {
                    rawResult = try await service.translate(
                        text: request.sourceText,
                        context: request.contextText,
                        sourceLanguage: request.sourceLanguage,
                        targetLanguage: request.targetLanguage
                    )
                }
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

    /// The handler a streamed translation reports to (2.1.0). Text reaches
    /// the card at most every 50 ms and only while the card still waits for
    /// this request; every delta also tells the watchdog the model is alive.
    private func beginTranslationStream(for requestID: UUID) -> AIPartialTextHandler {
        translationThrottle?.throttle.cancel()
        if streamingTranslation?.requestID == requestID {
            streamingTranslation = nil
        }
        let throttle = AIPartialTextThrottle { [weak self] text in
            guard let self, self.isLoadingTranslation(requestID) else { return }
            self.streamingTranslation = ReaderStreamingText(requestID: requestID, text: text)
        }
        translationThrottle = (requestID, throttle)
        let activity = translationStreamActivity?.requestID == requestID
            ? translationStreamActivity?.activity
            : nil
        return { text in
            activity?.touch()
            await throttle.submit(text)
        }
    }

    /// Drops the partial text of a request that has ended, whichever way.
    private func endTranslationStream(for requestID: UUID) {
        if translationThrottle?.requestID == requestID {
            translationThrottle?.throttle.cancel()
            translationThrottle = nil
        }
        if streamingTranslation?.requestID == requestID {
            streamingTranslation = nil
        }
        if translationStreamActivity?.requestID == requestID {
            translationStreamActivity = nil
        }
    }

    /// A new sentence or a closed card: the AI answers, the lookup count and
    /// any partial text belonged to the card before (2.1.0).
    private func beginNewTranslationCard() {
        translationCardID = UUID()
        currentLookupHint = nil
        currentLookupCount = 0
        cardAI.reset()
        translationThrottle?.throttle.cancel()
        translationThrottle = nil
        translationStreamActivity = nil
        streamingTranslation = nil
    }

    /// The word the card on screen has counted, for the lookup page its 查词
    /// opens: that page does not count it again (2.1.0 §8). Nil for a short
    /// tap's lookup, which has no card.
    var cardCountedLookup: CardCountedLookup? {
        guard shortTapLookup == nil,
              translationState.request != nil,
              recordedLookupCardID == translationCardID,
              let key = countedCardLookupKey
        else { return nil }
        return CardCountedLookup(key: key, hint: currentLookupHint)
    }

    /// A card for one word counts one lookup of it, once per card, as the
    /// lookup page does (2.1.0 §8); a retry or a redraw does not count again.
    /// As Android's `recordTranslatedWord`, the reader's dictionary looks the
    /// word up first, without its sentence, so the card counts it under the
    /// key 查词 uses (the lemma when the dictionary knows it). A lookup that
    /// fails counts nothing.
    private func recordCardLookupIfNeeded(for request: ReaderTranslationRequest) {
        guard recordedLookupCardID != translationCardID else { return }
        guard let language = request.sourceLanguage,
              let term = ReaderVocabularyCandidate.term(
                  from: request.sourceText,
                  language: language
              )
        else {
            currentLookupHint = nil
            currentLookupCount = 0
            return
        }
        let cardID = translationCardID
        recordedLookupCardID = cardID
        countedCardLookupKey = nil
        let service = lexicalLookupService
        // Not cancelled by the next card: the lookup counts either way, and
        // only the card that asked shows the count.
        let task = Task { [weak self] in
            let explanation: WordExplanation
            do {
                explanation = try await service.lookup(
                    word: term,
                    sentenceContext: nil,
                    language: language
                )
            } catch {
                Self.lookupLog.notice("A translation card's word lookup failed; nothing was counted.")
                return
            }
            guard let self, !self.isClosed else { return }
            do {
                try self.recordCardLookup(explanation, cardID: cardID)
            } catch {
                Self.lookupLog.notice("A translation card's word lookup could not be saved.")
            }
        }
        cardLookupTask = (cardID, task)
    }

    /// Records the card's lookup under the dictionary's key, reading 上次
    /// first. While that card is still on screen it shows 第 N 次查, and its
    /// 查词 does not count the word again.
    @discardableResult
    private func recordCardLookup(_ explanation: WordExplanation, cardID: UUID) throws -> WordLookupRecord {
        let previous = WordLookupStore.previousLookup(for: explanation, in: modelContext)
        let record = try WordLookupStore.record(
            explanation,
            sourceBookID: book.id,
            sourceBookTitle: book.title,
            in: modelContext
        )
        guard translationCardID == cardID, recordedLookupCardID == cardID else { return record }
        countedCardLookupKey = WordLookupRecord.makeLookupKey(for: explanation)
        currentLookupCount = record.lookupCount
        currentLookupHint = LookupHintPolicy.hint(
            lookupCount: record.lookupCount,
            previousLookupAt: previous?.date
        )
        return record
    }

    private static let lookupLog = Logger(
        subsystem: Bundle.main.bundleIdentifier ?? "Jerreader",
        category: "WordLookup"
    )

    /// The AI-bar UI test: the Mock streams its answers and stands in for an
    /// AI model, so the chips run without any provider set up.
    static var isCardAIUITest: Bool {
#if DEBUG
        ProcessInfo.processInfo.arguments.contains("--jerreader-card-ai-ui-test")
#else
        false
#endif
    }

    /// Where each reading-order resource starts in the book, for
    /// 本章还剩约 N 分钟 (2.1.0 §9, EPUB only); the estimate then follows the
    /// reading time every 30 seconds.
    private func resolveChapterBoundaries() async {
        guard isReflowableBook, let publication, !isClosed else { return }
        guard let positions = await publication.positionsByReadingOrder().getOrNil(),
              !isClosed
        else { return }
        var starts: [Double] = []
        for (index, resourcePositions) in positions.enumerated() {
            guard let first = resourcePositions.first else { continue }
            starts.append(
                first.locations.totalProgression
                    ?? Double(index) / Double(positions.count)
            )
        }
        chapterStarts = starts.sorted()
        refreshChapterTimeEstimate()
        chapterEstimateTask?.cancel()
        chapterEstimateTask = Task { @MainActor [weak self] in
            while true {
                do {
                    try await Task.sleep(for: .seconds(30))
                } catch {
                    return
                }
                guard let self, !self.isClosed else { return }
                self.refreshChapterTimeEstimate()
            }
        }
    }

    /// 本章还剩约 N 分钟 from this reader's pace so far; also keeps where the
    /// chapter ends for the shelf's 继续阅读 card.
    private func refreshChapterTimeEstimate() {
        guard isReflowableBook, !chapterStarts.isEmpty else {
            if chapterTimeEstimate != nil { chapterTimeEstimate = nil }
            return
        }
        let position = progress
        let chapterEnd = chapterStarts.first { $0 > position + 0.000_001 } ?? 1.0
        var readingSeconds = book.totalReadingSeconds
        if let startedAt = readingSessionStartedAt {
            let elapsed = Date().timeIntervalSince(startedAt)
            if elapsed.isFinite, elapsed > 0 {
                readingSeconds += min(elapsed, 6 * 60 * 60)
            }
        }
        let text = ChapterTimeEstimatePolicy.text(
            readingSeconds: readingSeconds,
            progression: position,
            chapterEnd: chapterEnd
        )
        if chapterTimeEstimate != text {
            chapterTimeEstimate = text
        }
        let key = ChapterTimeEstimatePolicy.chapterEndKey(bookID: book.id)
        if UserDefaults.standard.object(forKey: key) as? Double != chapterEnd {
            UserDefaults.standard.set(chapterEnd, forKey: key)
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
        case .planUsageLimitReached:
            return .providerPlanUsageLimit
        case .planSignInRequired:
            return .providerPlanSignInRequired
        case let .planRequestRejected(message):
            return .providerPlanRejected(message)
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
        case .planUsageLimitReached:
            return .planUsageLimit
        case .planSignInRequired:
            return .planSignInRequired
        case let .planRequestRejected(message):
            return .planRejected(message)
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

// MARK: - Translation card AI (2.1.0 §4–§7)

extension EPUBReaderViewModel {
    /// The models a chip asks: the explanation service for 结构, 读懂 and 指代,
    /// the action service for 直译 and 追问, and the name the badge shows.
    private struct CardAIProvider {
        let explanation: any ContextExplanationService
        let action: any AIActionService
        let identifier: String
        let version: String
        let modelName: String
    }

    /// The sentence and translation the chips answer about; nil unless the
    /// card shows a translation. Another card, or a retry that gives another
    /// translation, starts the chips afresh, as on Android.
    var cardAIKey: String? {
        guard case let .success(request, result, _) = translationState else { return nil }
        return [
            translationCardID.uuidString,
            request.sourceText,
            request.contextText ?? "",
            result.translatedText,
            result.sourceLanguage.rawValue,
            request.targetLanguage.rawValue,
        ].joined(separator: "\u{1F}")
    }

    var cardAIActions: [AIReadingAction] {
        AIReadingActionPolicy.actions(for: cardAISourceLanguage)
    }

    var cardAIPresets: [String] {
        AIReadingActionPolicy.presets(for: cardAISourceLanguage)
    }

    /// Whether an AI model is set up (2.1.0 §7); the chips show either way,
    /// and without one an AI chip opens 先接入一个 AI 模型.
    var isCardAIConfigured: Bool {
        Self.isCardAIUITest || Self.hasAIFeatureProvider(translationSettings)
    }

    /// Whether the AI features have a model to call (2.1.0 §7): an AI
    /// provider that can be used, a signed-in ChatGPT plan, or the AI proxy.
    /// Which service translates does not enter into it.
    static func hasAIFeatureProvider(_ settings: TranslationSettingsStore) -> Bool {
        settings.aiFeatureConfiguration != nil || settings.backendConfiguration != nil
    }

    /// Why 存为笔记 is greyed: the selection has no anchor (across pages,
    /// or a PDF without one).
    var cardAINoteUnavailableReason: String? {
        canAnnotateCurrentSelection ? nil : ReaderCardAICopy.noteUnavailable
    }

    /// A chip's result area is open: the card may then take up to half the
    /// screen (2.1.0 §4).
    var isCardAIResultShowing: Bool {
        guard cardAI.isShowing(card: cardAIKey) else { return false }
        return cardAI.selectedAction?.resultTitle != nil
    }

    /// The solid 加入生词本 of a one-word card, from its third lookup (2.1.0 §8).
    var showsPrimaryAddToVocabulary: Bool {
        guard case .success = translationState else { return false }
        return canAddCurrentSelectionToVocabulary
            && !isCurrentSelectionInVocabulary
            && LookupHintPolicy.addToVocabularyIsPrimary(lookupCount: currentLookupCount)
    }

    /// The badge under the translation (2.1.0 §6): the model that wrote it,
    /// or 「Apple 翻译」. The offline Mock has none, except in the AI-bar UI
    /// test, where it stands in for a model.
    var translationAnswerSource: ReaderAIAnswerSource? {
        guard case let .success(request, _, source) = translationState else { return nil }
        let provider: TranslationProviderChoice?
        switch source {
        case .appleTranslation:
            provider = .apple
        case .directAPI:
            provider = .directAPI
        case .backendProxy:
            provider = .backendProxy
        case .cache:
            provider = request.provider
        case .mock:
            provider = nil
        }
        let model: String?
        switch provider {
        case .apple?:
            return ReaderAIAnswerSource(
                modelName: "Apple 翻译",
                isAIModel: false,
                sentSource: request.sourceText,
                sentContext: nil
            )
        case .directAPI?:
            model = cardAIDirectModelName
        case .backendProxy?:
            model = cardAIBackendModelName
        case nil:
            model = Self.isCardAIUITest ? "Mock" : nil
        }
        guard let model else { return nil }
        return ReaderAIAnswerSource(
            modelName: model,
            isAIModel: true,
            sentSource: request.sourceText,
            sentContext: request.contextText
        )
    }

    /// A chip: 查词 opens the lookup sheet; the open chip closes its answer;
    /// another opens its answer, asking the model unless it has answered on
    /// this card already. 追问 only opens its conversation.
    func selectCardAIAction(_ action: AIReadingAction) {
        guard action.needsAI else {
            isShowingContextualLookup = true
            return
        }
        guard let key = cardAIKey else { return }
        cardAI.adopt(key)
        if cardAI.selectedAction == action {
            cardAI.collapse()
            return
        }
        guard isCardAIConfigured, resolvedCardAIProvider() != nil else {
            cardAI.requestSetup(for: action)
            return
        }
        cardAI.open(action)
        guard action != .followUp, !cardAI.hasCompletedAnswer(for: action) else { return }
        startCardAIAnswer(action)
    }

    /// Asks `question` about the card's sentence, after the questions answered.
    func sendCardAIFollowUp(_ question: String) {
        let asked = question.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !asked.isEmpty, let key = cardAIKey else { return }
        cardAI.adopt(key)
        guard case let .success(request, result, _) = translationState,
              let provider = resolvedCardAIProvider()
        else {
            cardAI.requestSetup(for: .followUp)
            return
        }
        let material = cardAIMaterial(request: request, result: result)
        let requestID = UUID()
        cardAI.beginFollowUp(
            question: asked,
            requestID: requestID,
            source: cardAIAnswerSource(provider, material: material)
        )
        startCardAIFollowUp(
            requestID: requestID,
            provider: provider,
            material: material,
            translation: result.translatedText
        )
    }

    /// 停止: the answer keeps the text it has and can be asked again.
    func stopCardAI() {
        cardAI.stop()
    }

    /// 重试 of the open chip, or of the newest question when it failed.
    func retryCardAI() {
        guard cardAI.isShowing(card: cardAIKey),
              let selected = cardAI.selectedAction
        else { return }
        switch selected {
        case .lookup:
            return
        case .followUp:
            guard case let .success(request, result, _) = translationState,
                  let provider = resolvedCardAIProvider()
            else { return }
            let material = cardAIMaterial(request: request, result: result)
            let requestID = UUID()
            guard cardAI.restartLastFollowUp(
                requestID: requestID,
                source: cardAIAnswerSource(provider, material: material)
            ) else { return }
            startCardAIFollowUp(
                requestID: requestID,
                provider: provider,
                material: material,
                translation: result.translatedText
            )
        case .structure, .comprehension, .reference, .literal:
            cardAI.open(selected)
            startCardAIAnswer(selected)
        }
    }

    /// 先接入一个 AI 模型 closed: the chip that opened it runs once a model is set up.
    func cardAISetupDismissed() {
        guard let action = cardAI.takePendingAction(),
              isCardAIConfigured,
              cardAIKey != nil
        else { return }
        selectCardAIAction(action)
    }

    /// 不再显示这些按钮: hides the whole bar until 阅读设置 → 翻译与 AI turns it on.
    func hideCardAIBar() {
        UserDefaults.standard.set(true, forKey: ReaderInterfaceDefaults.aiActionBarHiddenKey)
        cardAI.clearPendingAction()
        cardAI.collapse()
        cardAI.isShowingSetup = false
        cardAI.showToast(ReaderCardAICopy.buttonsHidden, duration: .seconds(3))
    }

    /// 存为笔记 (2.1.0 §6): adds the result to the sentence's note, or makes
    /// a highlight in the default colour with it as the note.
    func saveCardAIResultAsNote(title: String, body: String) {
        guard let draft = annotationDraftForCurrentSelection() else {
            readerAlertMessage = ReadingAnnotationError.invalidSelection.errorDescription
            return
        }
        do {
            try annotationStore.appendNote(
                book: book,
                locatorJSON: draft.locatorJSON,
                anchorJSON: draft.anchorJSON,
                selectedText: draft.selectedText,
                title: title,
                body: body,
                color: draft.color,
                chapterTitle: draft.chapterTitle,
                progress: draft.progress
            )
            refreshAnnotations()
            cardAI.showToast(ReaderCardAICopy.noteSaved)
        } catch let error as ReadingAnnotationError {
            readerAlertMessage = error.errorDescription
        } catch {
            readerAlertMessage = ReaderCardAICopy.noteFailed
        }
    }

    /// 展开看全文: the explanation page for 结构, 读懂 and 指代, which shows
    /// the answer as its own; a sheet with the whole text for 直译.
    func showCardAIFullText(for action: AIReadingAction) {
        guard let answer = cardAI.answers[action],
              let text = answer.phase.completedText
        else { return }
        if let explanation = answer.explanation {
            contextExplanationTask?.cancel()
            contextExplanationTask = nil
            cancelContextExplanationWatchdog()
            contextExplanationState = .success(
                request: explanation.request,
                result: explanation.result
            )
            isShowingContextExplanation = true
            return
        }
        cardAI.presentedFullText = ReaderCardAIFullText(
            title: action.resultTitle ?? action.title,
            text: text
        )
    }

    /// The card's text for a failed AI answer, and whether 重试 can help.
    /// Nil for a cancellation, which shows nothing.
    static func cardAIFailure(for error: Error) -> (message: String, canRetry: Bool)? {
        if error is CancellationError { return nil }
        guard let serviceError = error as? ServiceError else {
            return (ReaderCardAICopy.unavailable, true)
        }
        switch serviceError {
        case .planUsageLimitReached:
            return (ReaderContextExplanationError.planUsageLimit.message, false)
        case .planSignInRequired:
            return (ReaderContextExplanationError.planSignInRequired.message, false)
        case let .planRequestRejected(message):
            let reason = message.trimmingCharacters(in: .whitespacesAndNewlines)
            return (reason.isEmpty ? ReaderCardAICopy.unavailable : reason, false)
        case .authenticationFailed:
            return (ReaderContextExplanationError.authenticationFailed.message, true)
        case .invalidConfiguration:
            return (serviceError.errorDescription ?? ReaderCardAICopy.unavailable, true)
        case .emptyText, .textTooLong, .unsupportedLanguage:
            return (serviceError.errorDescription ?? ReaderCardAICopy.unavailable, false)
        case .resultNotFound, .temporarilyUnavailable, .translationUnavailable,
             .languageDownloadDeclined, .voiceUnavailable:
            return (ReaderCardAICopy.unavailable, true)
        }
    }

    /// The language the chips are offered for: the one the translation
    /// detected, as Android's card takes it.
    private var cardAISourceLanguage: LanguageCode? {
        switch translationState {
        case let .success(_, result, _):
            return result.sourceLanguage
        case let .loading(request), let .failure(request, _):
            return request.sourceLanguage
        case .idle:
            return nil
        }
    }

    /// The model that translates when the AI provider does.
    private var cardAIDirectModelName: String? {
        translationSettings.directAPIConfiguration.map(cardAIModelName(of:))
    }

    /// A plan model by the name the account gives it, others by their id.
    private func cardAIModelName(of configuration: DirectAITranslationConfiguration) -> String {
        configuration.provider.usesAccountSignIn
            ? translationSettings.chatGPTPlanModelName(configuration.model)
            : configuration.model
    }

    private var cardAIBackendModelName: String? {
        guard let configuration = translationSettings.backendConfiguration else { return nil }
        let model = configuration.model?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return model.isEmpty ? "AI 代理" : model
    }

    /// The AI model in use, in the order the translation service prefers;
    /// with Apple translating, the direct model first, as for the
    /// sentence-structure analysis.
    private func resolvedCardAIProvider() -> CardAIProvider? {
#if DEBUG
        if Self.isCardAIUITest {
            let mock = MockTranslationService(streamsPartialText: true, pieceDelay: .milliseconds(700))
            return CardAIProvider(
                explanation: mock,
                action: mock,
                identifier: "mock-explanation",
                version: "1.0",
                modelName: "Mock"
            )
        }
#endif
        func direct() -> CardAIProvider? {
            // A signed-in plan serves the AI buttons even while another AI
            // provider is in use without a key (2.1.0 §7).
            guard let configuration = translationSettings.aiFeatureConfiguration else { return nil }
            let service = DirectAITranslationService(configuration: configuration)
            return CardAIProvider(
                explanation: service,
                action: service,
                identifier: service.providerIdentifier,
                version: service.providerVersion,
                modelName: cardAIModelName(of: configuration)
            )
        }

        func backend() -> CardAIProvider? {
            guard let configuration = translationSettings.backendConfiguration else { return nil }
            let made = backendTranslationServiceFactory(configuration)
            let fallback = BackendTranslationService(configuration: configuration)
            return CardAIProvider(
                explanation: (made as? any ContextExplanationService) ?? fallback,
                action: (made as? any AIActionService) ?? fallback,
                identifier: BackendTranslationService.providerIdentifier,
                version: BackendTranslationService.providerVersion(for: configuration),
                modelName: cardAIBackendModelName ?? "AI 代理"
            )
        }

        switch translationSettings.provider {
        case .backendProxy:
            return backend() ?? direct()
        case .directAPI, .apple:
            return direct() ?? backend()
        }
    }

    /// What every chip sends: the sentence and its context as the
    /// explanation page prepares them.
    private func cardAIMaterial(
        request: ReaderTranslationRequest,
        result: TranslationResult
    ) -> ContextExplanationRequest {
        ContextExplanationInputPolicy.prepared(
            ContextExplanationRequest(
                focusedText: request.sourceText,
                contextText: request.contextText,
                sourceLanguage: result.sourceLanguage,
                responseLanguage: request.targetLanguage
            )
        )
    }

    private func cardAIAnswerSource(
        _ provider: CardAIProvider,
        material: ContextExplanationRequest
    ) -> ReaderAIAnswerSource {
        ReaderAIAnswerSource(
            modelName: provider.modelName,
            isAIModel: true,
            sentSource: material.focusedText,
            sentContext: material.contextText
        )
    }

    /// Asks the model for the answer of a chip. 结构, 读懂 and 指代 share the
    /// explanation page's cache, which keeps only whole, checked answers.
    private func startCardAIAnswer(_ action: AIReadingAction) {
        guard case let .success(request, result, _) = translationState,
              let provider = resolvedCardAIProvider()
        else { return }
        let material = cardAIMaterial(request: request, result: result)
        let requestID = UUID()
        cardAI.begin(
            action,
            requestID: requestID,
            source: cardAIAnswerSource(provider, material: material)
        )

        guard action.usesExplanationService else {
            let actionRequest = AIActionRequest.literal(
                source: material.focusedText,
                translation: result.translatedText,
                context: material.contextText,
                sourceLanguage: material.sourceLanguage,
                responseLanguage: material.responseLanguage
            )
            let service = provider.action
            runCardAIRequest(requestID) { onPartialText in
                let completion = try await service.respond(to: actionRequest, onPartialText: onPartialText)
                guard let text = TranslationOutputPolicy.displayText(completion.text) else {
                    throw ServiceError.resultNotFound
                }
                return (text, nil)
            }
            return
        }

        let lens = action.lensMode.map { mode in
            ReadingLensOptions(
                mode: mode,
                companionMode: companion.mode,
                assist: AdaptiveReadingAssistPolicy.resolve(
                    readingSignals(),
                    mode: companion.mode,
                    bookID: companion.bookID
                )
            )
        }
        let explanationRequest = ReaderContextExplanationRequest(
            id: requestID,
            focusedText: material.focusedText,
            contextText: material.contextText,
            sourceLanguage: material.sourceLanguage,
            responseLanguage: material.responseLanguage,
            providerIdentifier: provider.identifier,
            providerVersion: provider.version,
            lens: lens
        )
        let cacheKey = contextExplanationCacheKey(for: explanationRequest)
        if let cached = contextExplanationCache[cacheKey] {
            cardAI.complete(
                requestID: requestID,
                text: cached.explanation,
                explanation: ReaderCardAIExplanation(request: explanationRequest, result: cached)
            )
            return
        }
        let serviceRequest = ContextExplanationRequest(
            focusedText: explanationRequest.focusedText,
            contextText: explanationRequest.contextText,
            sourceLanguage: explanationRequest.sourceLanguage,
            responseLanguage: explanationRequest.responseLanguage,
            lens: lens
        )
        let service = provider.explanation
        runCardAIRequest(requestID) { [weak self] onPartialText in
            let raw: ContextExplanationResult
            if let streaming = service as? any StreamingContextExplanationService {
                raw = try await streaming.explain(serviceRequest, onPartialText: onPartialText)
            } else {
                raw = try await service.explain(serviceRequest)
            }
            guard let validated = ContextExplanationOutputPolicy.validated(raw) else {
                throw ServiceError.resultNotFound
            }
            try Task.checkCancellation()
            self?.contextExplanationCache[cacheKey] = validated
            return (
                validated.explanation,
                ReaderCardAIExplanation(request: explanationRequest, result: validated)
            )
        }
    }

    /// The newest question, with the answered ones before it as the conversation.
    private func startCardAIFollowUp(
        requestID: UUID,
        provider: CardAIProvider,
        material: ContextExplanationRequest,
        translation: String
    ) {
        let actionRequest = AIActionRequest.followUp(
            source: material.focusedText,
            translation: translation,
            context: material.contextText,
            sourceLanguage: material.sourceLanguage,
            responseLanguage: material.responseLanguage,
            turns: cardAI.followUpTurns()
        )
        let service = provider.action
        runCardAIRequest(requestID) { onPartialText in
            let completion = try await service.respond(to: actionRequest, onPartialText: onPartialText)
            guard let text = TranslationOutputPolicy.displayText(completion.text) else {
                throw ServiceError.resultNotFound
            }
            return (text, nil)
        }
    }

    /// Runs one AI answer of the card. Its text so far reaches the card at
    /// most every 50 ms and only while it is still the request in flight;
    /// every delta tells the watchdog the model is alive. After 70 seconds
    /// without one, or 3 minutes in all, the answer fails with its text so far.
    private func runCardAIRequest(
        _ requestID: UUID,
        work: @escaping @MainActor @Sendable (
            @escaping AIPartialTextHandler
        ) async throws -> (text: String, explanation: ReaderCardAIExplanation?)
    ) {
        let cardAI = self.cardAI
        let startedAt = ContinuousClock.now
        let activity = AIStreamActivity(now: startedAt)
        let throttle = AIPartialTextThrottle { [weak cardAI] text in
            cardAI?.show(partial: text, for: requestID)
        }
        let onPartialText: AIPartialTextHandler = { text in
            activity.touch()
            await throttle.submit(text)
        }
        let task = Task { @MainActor [weak self] in
            do {
                let answer = try await work(onPartialText)
                guard let self, !Task.isCancelled else { return }
                self.cardAI.complete(
                    requestID: requestID,
                    text: answer.text,
                    explanation: answer.explanation
                )
            } catch {
                guard let self, !Task.isCancelled,
                      let failure = Self.cardAIFailure(for: error)
                else { return }
                self.cardAI.fail(
                    requestID: requestID,
                    message: failure.message,
                    canRetry: failure.canRetry
                )
            }
        }
        let idle = translationTiming.contextExplanationRequestTimeout
        let total = translationTiming.streamingTotalTimeout
        // Off the main actor, as the explanation page's watchdog is: a busy
        // UI executor must not make the timeout late.
        let watchdog = Task.detached { [weak self] in
            do {
                try await activity.waitUntilTimedOut(idle: idle, total: total)
            } catch {
                return
            }
            guard !Task.isCancelled else { return }
            let wentQuiet = ContinuousClock.now < startedAt + total
            await self?.finishCardAITimeout(requestID: requestID, wentQuiet: wentQuiet)
        }
        cardAI.attach(requestID: requestID, task: task, watchdog: watchdog, throttle: throttle)
    }

    private func finishCardAITimeout(requestID: UUID, wentQuiet: Bool) {
        cardAI.fail(
            requestID: requestID,
            message: wentQuiet ? ReaderCardAICopy.quiet : ReaderCardAICopy.tooLong,
            canRetry: true
        )
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
