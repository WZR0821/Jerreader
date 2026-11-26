import Foundation
import Observation

/// Reading-companion state for one open book, split out of
/// `EPUBReaderViewModel` like `ReaderSearchModel`: the per-book 小说/非虚构
/// mode, the AI button's dot, and the sheets built from the reader's own
/// lookups. Mirrors the companion helpers of the Android ReaderActivity.
///
/// Nothing here pops up over the page. Reopening a book, or reading on into
/// the next chapter, only lights the dot; the reader opens the sheet.
@MainActor
@Observable
final class ReaderCompanionModel {
    /// Below this the book is effectively unread and there is nothing to recap.
    static let recapMinimumProgress = 0.01
    static let fallbackChapterTitle = "当前章节"

    let bookID: String
    let bookTitle: String
    private(set) var mode: ReadingCompanionMode
    private(set) var hasUpdate = false
    var isShowingCompanion = false
    var isShowingClosure = false
    private(set) var confusion: ReadingConfusionMap = .empty
    private(set) var recap = ContinuationRecapPlan(title: "", bullets: [])
    private(set) var closureChapterTitle = ReaderCompanionModel.fallbackChapterTitle
    private(set) var closurePlan: ChapterClosurePlan?

    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private var recapChapterTitle = ""
    @ObservationIgnored private var finishedChapterTitle: String?
    @ObservationIgnored private var pendingSearchTerm: String?
    @ObservationIgnored private var opensClosureAfterDismiss = false

    init(bookID: UUID, bookTitle: String, defaults: UserDefaults = .standard) {
        self.bookID = bookID.uuidString
        self.bookTitle = bookTitle
        self.defaults = defaults
        mode = ReadingCompanionMode.from(
            storageID: defaults.string(forKey: Self.modeKey(bookID.uuidString))
        )
    }

    /// The mode belongs to the book: a novel and an essay collection read differently.
    func setMode(_ newMode: ReadingCompanionMode) {
        guard newMode != mode else { return }
        mode = newMode
        defaults.set(newMode.rawValue, forKey: Self.modeKey(bookID))
        recap = ContinuationRecapPolicy.build(
            mode: newMode,
            bookTitle: bookTitle,
            chapterTitle: recapChapterTitle,
            confusionItems: confusion.items
        )
    }

    /// `lastOpenedAt` is the previous open, read before this one is
    /// recorded; a book reopened within six hours keeps the dot off (2.1.0).
    func bookOpened(atProgress progress: Double, lastOpenedAt: Date? = nil, now: Date = Date()) {
        hasUpdate = ReaderCompanionDotPolicy.showsDot(progress: progress, lastOpenedAt: lastOpenedAt, now: now)
    }

    /// Called when the reader moves from one outline entry straight into the next.
    func chapterFinished(_ title: String) {
        let trimmed = ReadingCompanionText.trimmed(title)
        guard !trimmed.isEmpty else { return }
        finishedChapterTitle = trimmed
        hasUpdate = true
    }

    /// `chapterTitle` is empty when the reader has no chapter to name.
    func present(signals: [ReadingErrorSignal], chapterTitle: String) {
        hasUpdate = false
        confusion = ReadingConfusionMapPolicy.build(signals, bookID: bookID)
        recapChapterTitle = chapterTitle
        recap = ContinuationRecapPolicy.build(
            mode: mode,
            bookTitle: bookTitle,
            chapterTitle: chapterTitle,
            confusionItems: confusion.items
        )
        let current = ReadingCompanionText.trimmed(chapterTitle)
        closureChapterTitle = finishedChapterTitle
            ?? (current.isEmpty ? Self.fallbackChapterTitle : current)
        isShowingCompanion = true
    }

    /// Builds the 90-second loop; it opens once the companion sheet has gone,
    /// because SwiftUI cannot present a sheet while another is still dismissing.
    func startClosure() {
        closurePlan = ChapterClosurePolicy.build(
            mode: mode,
            chapterTitle: closureChapterTitle,
            confusionItems: confusion.items
        )
        finishedChapterTitle = nil
        opensClosureAfterDismiss = true
        isShowingCompanion = false
    }

    /// Words keep no locator, so "back to the text" is a book search for them.
    func requestSearch(_ term: String) {
        let trimmed = ReadingCompanionText.trimmed(term)
        guard !trimmed.isEmpty else { return }
        pendingSearchTerm = trimmed
        opensClosureAfterDismiss = false
        isShowingCompanion = false
        isShowingClosure = false
    }

    /// Runs after either sheet has finished dismissing. Returns a term the
    /// reader asked to find, which the reader view turns into a search.
    func sheetDismissed() -> String? {
        if opensClosureAfterDismiss {
            opensClosureAfterDismiss = false
            isShowingClosure = closurePlan != nil
            return nil
        }
        defer { pendingSearchTerm = nil }
        return pendingSearchTerm
    }

    private static func modeKey(_ bookID: String) -> String {
        "jerreader.readerCompanion.mode.\(bookID)"
    }
}
