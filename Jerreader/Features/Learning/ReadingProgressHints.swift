import Foundation

/// 本章还剩约 N 分钟 (2.1.0, EPUB only): the reader's own pace so far, applied
/// to what is left of the chapter.
///
/// Mirrors shared/src/commonMain/kotlin/com/jerreader/shared/learning/ReadingProgressHints.kt;
/// checked against shared/parity/chapter_estimate.json.
enum ChapterTimeEstimatePolicy {
    static let minimumReadingSeconds: Double = 600
    static let minimumProgression = 0.02
    static let maximumMinutes = 120
    static let underOneMinuteSeconds: Double = 30

    /// Whole minutes left, 0 for under half a minute, or nil when the pace is
    /// not known well enough, the chapter end is unknown, or the position is
    /// already past it.
    static func minutes(readingSeconds: Double, progression: Double, chapterEnd: Double?) -> Int? {
        guard let chapterEnd, readingSeconds.isFinite, progression.isFinite, chapterEnd.isFinite,
              readingSeconds >= minimumReadingSeconds, progression >= minimumProgression,
              chapterEnd >= progression
        else { return nil }
        let remainingSeconds = (chapterEnd - progression) * readingSeconds / progression
        if remainingSeconds < underOneMinuteSeconds { return 0 }
        let minutes = max(1, (remainingSeconds / 60 + 0.5).rounded(.down))
        guard minutes <= Double(maximumMinutes) else { return nil }
        return Int(minutes)
    }

    static func text(minutes: Int?) -> String? {
        guard let minutes else { return nil }
        return minutes <= 0 ? "本章还剩不到 1 分钟" : "本章还剩约 \(minutes) 分钟"
    }

    static func text(readingSeconds: Double, progression: Double, chapterEnd: Double?) -> String? {
        text(minutes: minutes(readingSeconds: readingSeconds, progression: progression, chapterEnd: chapterEnd))
    }

    /// Where the shelf finds the chapter end the reader last saw for a book.
    static func chapterEndKey(bookID: UUID) -> String {
        "reader.chapterEnd.\(bookID.uuidString)"
    }
}

/// 「第 N 次查 · 上次 MM-dd」 on the lookup card and on a one-word translation
/// card. N counts this lookup; the previous date must be read before this
/// lookup is recorded. Checked against shared/parity/lookup_hint.json.
enum LookupHintPolicy {
    static let minimumHintCount = 2
    static let primaryAddToVocabularyCount = 3

    static func hint(
        lookupCount: Int,
        previousLookupAt: Date?,
        now: Date = Date(),
        calendar: Calendar = .current
    ) -> String? {
        guard lookupCount >= minimumHintCount else { return nil }
        let count = "第 \(lookupCount) 次查"
        guard let previousLookupAt else { return count }
        var calendar = calendar
        if calendar.identifier != .gregorian {
            let timeZone = calendar.timeZone
            calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = timeZone
        }
        let previousDay = calendar.startOfDay(for: previousLookupAt)
        let today = calendar.startOfDay(for: now)
        let days = calendar.dateComponents([.day], from: previousDay, to: today).day
        let label: String
        switch days {
        case 0:
            label = "今天"
        case 1:
            label = "昨天"
        default:
            let parts = calendar.dateComponents([.year, .month, .day], from: previousLookupAt)
            let year = parts.year ?? 0
            let month = parts.month ?? 0
            let day = parts.day ?? 0
            label = year == calendar.component(.year, from: now)
                ? String(format: "%02d-%02d", month, day)
                : String(format: "%04d-%02d-%02d", year, month, day)
        }
        return count + " · 上次 " + label
    }

    /// From the third lookup 「加入生词本」 is the card's solid button.
    static func addToVocabularyIsPrimary(lookupCount: Int) -> Bool {
        lookupCount >= primaryAddToVocabularyCount
    }
}

/// The red dot on the reading-companion button when a book is opened. A book
/// reopened within six hours does not light it; finishing a chapter still
/// does, elsewhere. Checked against shared/parity/companion_dot.json.
enum ReaderCompanionDotPolicy {
    static let minimumProgress = 0.01
    static let quietHours = 6
    private static let quietInterval = TimeInterval(quietHours * 60 * 60)

    /// `lastOpenedAt` is the previous open, read before this open is recorded;
    /// nil means never, which counts as long ago. A time in the future, after
    /// a clock change, counts as recent.
    static func showsDot(progress: Double, lastOpenedAt: Date?, now: Date = Date()) -> Bool {
        guard progress > minimumProgress else { return false }
        guard let lastOpenedAt else { return true }
        return now.timeIntervalSince(lastOpenedAt) >= quietInterval
    }
}
