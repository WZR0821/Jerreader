import Foundation

// Reading-driven learning: the confusion map, the hint level fed back into
// the AI reading lens, the chapter loop and the continuation recap.
//
// Mirrors shared/src/commonMain/kotlin/com/jerreader/shared/learning/ReadingCompanion.kt
// and is checked against the same cases in shared/parity/reading_companion.json.
// Ported from the unified line and adapted to what this app records: one
// record per lemma with its latest sentence, source book, lookup count and a
// known/not-known answer history, but no per-word locator and no per-question
// review modes. So there are three kinds instead of four, and "back to the
// text" searches the book for the word.
//
// Pure Foundation, so the host parity suite can compile it without SwiftData.

enum ReadingCompanionMode: String, CaseIterable, Codable, Sendable, Identifiable {
    case fiction
    case nonfiction

    var id: String { rawValue }

    var title: String {
        switch self {
        case .fiction: "小说"
        case .nonfiction: "非虚构"
        }
    }

    static func from(storageID: String?) -> Self {
        storageID.flatMap(Self.init(rawValue:)) ?? .fiction
    }
}

enum ReadingConfusionKind: String, CaseIterable, Sendable {
    case wordMeaning = "word_meaning"
    case grammar
    case recall

    var title: String {
        switch self {
        case .wordMeaning: "词义"
        case .grammar: "语法"
        case .recall: "回想"
        }
    }

    fileprivate var order: Int { Self.allCases.firstIndex(of: self) ?? 0 }
}

/// What a known/not-known review history says about a word right now.
struct ReadingReviewSummary: Equatable, Sendable {
    var failedAnswerCount: Int
    /// Answers that were wrong right after a right one: the word was forgotten.
    var lapseCount: Int
    /// Right answers since the newest wrong one.
    var successStreak: Int
    /// The newest answer was wrong.
    var hasUnresolvedFailure: Bool

    /// `knownOldestFirst` is the review history in the order it is stored.
    static func of(_ knownOldestFirst: [Bool]) -> Self {
        var lapses = 0
        if knownOldestFirst.count > 1 {
            for index in 1 ..< knownOldestFirst.count
            where !knownOldestFirst[index] && knownOldestFirst[index - 1] {
                lapses += 1
            }
        }
        return Self(
            failedAnswerCount: knownOldestFirst.filter { !$0 }.count,
            lapseCount: lapses,
            successStreak: knownOldestFirst.reversed().prefix { $0 }.count,
            hasUnresolvedFailure: knownOldestFirst.last == false
        )
    }
}

/// One saved word as the confusion map sees it.
struct ReadingErrorSignal: Equatable, Sendable {
    var lookupKey: String
    var displayTerm: String
    var lemma: String?
    var sentenceContext: String
    var sourceBookID: String
    var sourceBookTitle: String
    var lookupCount: Int
    var review: ReadingReviewSummary

    /// The reader looked up a conjugated or inflected form, not the dictionary form.
    var isInflectedForm: Bool {
        let base = ReadingCompanionText.trimmed(lemma ?? "")
        return !base.isEmpty && base.lowercased() != ReadingCompanionText.trimmed(displayTerm).lowercased()
    }
}

struct ReadingConfusionItem: Equatable, Sendable, Identifiable {
    var lookupKey: String
    var displayTerm: String
    var kind: ReadingConfusionKind
    var score: Int
    var reason: String
    var sentenceContext: String
    var sourceBookID: String
    var sourceBookTitle: String

    var id: String { lookupKey }
}

struct ReadingConfusionMap: Equatable, Sendable {
    var items: [ReadingConfusionItem]
    var topKind: ReadingConfusionKind?
    var summary: String

    static let empty = ReadingConfusionMapPolicy.build([])
}

/// Maps only friction the reader actually met. A word looked up outside a
/// book (in the lookup tool) is not a reading difficulty.
enum ReadingConfusionMapPolicy {
    static let defaultLimit = 5
    private static let resolvedSuccessStreak = 3

    /// `bookID` limits the map to one book, as the reader does.
    static func build(
        _ signals: [ReadingErrorSignal],
        limit: Int = defaultLimit,
        bookID: String? = nil
    ) -> ReadingConfusionMap {
        let ranked = signals
            .filter { bookID == nil || $0.sourceBookID == bookID }
            .compactMap(item)
            .sorted { left, right in
                if left.score != right.score { return left.score > right.score }
                let leftTerm = left.displayTerm.lowercased()
                let rightTerm = right.displayTerm.lowercased()
                if leftTerm != rightTerm { return leftTerm < rightTerm }
                return left.lookupKey < right.lookupKey
            }
        let items = Array(ranked.prefix(min(max(limit, 0), 20)))
        // Most items wins; a tie goes to the kind listed first. Same ranking as
        // the shared Kotlin rule (count * 10 - ordinal). Written as a loop: the
        // chained map/filter/max form exceeded the Swift type checker's limit.
        var topKind: ReadingConfusionKind?
        var topRank = Int.min
        for kind in ReadingConfusionKind.allCases {
            let count: Int = items.filter { $0.kind == kind }.count
            guard count > 0 else { continue }
            let rank: Int = count * 10 - kind.order
            if rank > topRank {
                topRank = rank
                topKind = kind
            }
        }
        let summary: String
        if items.isEmpty {
            summary = "还没有足够的真实阅读困难，继续阅读和复习后会自动形成。"
        } else if let topKind {
            summary = "当前主要困惑集中在\(topKind.title)，共 \(items.count) 个可以回到书中核对的点。"
        } else {
            summary = "已从真实阅读记录找到 \(items.count) 个待巩固点。"
        }
        return ReadingConfusionMap(items: items, topKind: topKind, summary: summary)
    }

    private static func item(_ signal: ReadingErrorSignal) -> ReadingConfusionItem? {
        guard !ReadingCompanionText.isBlank(signal.sourceBookID),
              !ReadingCompanionText.isBlank(signal.displayTerm)
        else { return nil }
        let review = signal.review
        let repeated = max(signal.lookupCount, 0)
        let failures = max(review.failedAnswerCount, 0)
        let lapses = max(review.lapseCount, 0)
        let baseScore = min(max(repeated - 1, 0) * 16, 48)
            + min(lapses * 22, 66)
            + min(failures * 18, 72)
        guard baseScore > 0 else { return nil }

        // The map shows current friction, not a permanent record: a stable
        // streak removes the word, a short one keeps it as a low-priority
        // "improving" item that a new mistake brings straight back.
        let streak = max(review.successStreak, 0)
        if !review.hasUnresolvedFailure, streak >= resolvedSuccessStreak { return nil }
        let score = !review.hasUnresolvedFailure && streak > 0
            ? max(baseScore / (streak + 1), 1)
            : baseScore

        let kind: ReadingConfusionKind
        if failures > 0 || lapses > 0 {
            kind = .recall
        } else if signal.isInflectedForm {
            kind = .grammar
        } else {
            kind = .wordMeaning
        }
        var reasons: [String] = []
        if repeated > 1 { reasons.append("查过 \(repeated) 次") }
        if failures > 0 || lapses > 0 {
            reasons.append(
                review.hasUnresolvedFailure
                    ? "最近一次复习没想起来"
                    : "复习中忘记过 \(max(failures, lapses)) 次"
            )
        }
        if signal.isInflectedForm {
            reasons.append("查的是变形，基本形「\(ReadingCompanionText.trimmed(signal.lemma ?? ""))」")
        }
        if !review.hasUnresolvedFailure, streak > 0 { reasons.append("连续答对 \(streak) 次 · 正在改善") }
        return ReadingConfusionItem(
            lookupKey: signal.lookupKey,
            displayTerm: signal.displayTerm,
            kind: kind,
            score: score,
            reason: reasons.joined(separator: " · "),
            sentenceContext: signal.sentenceContext,
            sourceBookID: signal.sourceBookID,
            sourceBookTitle: signal.sourceBookTitle
        )
    }
}

enum ReadingAssistLevel: String, CaseIterable, Sendable {
    case hintFirst = "hint_first"
    case guided
    case explicit

    var title: String {
        switch self {
        case .hintFirst: "提示优先"
        case .guided: "分步引导"
        case .explicit: "明确讲解"
        }
    }
}

struct AdaptiveReadingAssist: Equatable, Sendable {
    var level: ReadingAssistLevel
    var focusTerms: [String]
    var instruction: String
    var reason: String
}

/// Review results flow back into how much the next AI explanation gives away.
enum AdaptiveReadingAssistPolicy {
    /// `bookID` keeps other books' records from raising the support in this one.
    static func resolve(
        _ allSignals: [ReadingErrorSignal],
        mode: ReadingCompanionMode,
        bookID: String? = nil
    ) -> AdaptiveReadingAssist {
        let signals = allSignals.filter { bookID == nil || $0.sourceBookID == bookID }
        let map = ReadingConfusionMapPolicy.build(signals, limit: 3)
        // Only the current state counts: a right answer after a mistake clears
        // hasUnresolvedFailure, and successStreak restarts at every mistake.
        let unresolved = signals.reduce(0) { total, signal in
            guard signal.review.hasUnresolvedFailure else { return total }
            return total + min(max(signal.review.failedAnswerCount, 1), 2)
        }
        let successes = signals.reduce(0) { $0 + max($1.review.successStreak, 0) }
        let level: ReadingAssistLevel
        if unresolved >= 2 {
            level = .explicit
        } else if unresolved > 0 {
            level = .guided
        } else if successes >= 3 {
            level = .hintFirst
        } else {
            level = .guided
        }
        let focus = switch mode {
        case .fiction: "人物动机、语气和指代"
        case .nonfiction: "核心概念、论点和证据关系"
        }
        let instruction = switch level {
        case .hintFirst:
            "先给一个可回到原文验证的提示和一个自检问题，不要立即给出完整结论；重点关注\(focus)。"
        case .guided:
            "分两步引导理解，先指出文本证据，再说明结论；重点关注\(focus)。"
        case .explicit:
            "对薄弱点给出明确、分项讲解，并在结尾给一道很短的原文自检题；重点关注\(focus)。"
        }
        return AdaptiveReadingAssist(
            level: level,
            focusTerms: map.items.map(\.displayTerm),
            instruction: instruction,
            reason: map.items.isEmpty
                ? "尚无明显薄弱点，采用\(level.title)。"
                : "依据近期复习与阅读困惑，采用\(level.title)。"
        )
    }
}

struct ChapterClosurePrompt: Equatable, Sendable, Identifiable {
    var title: String
    var prompt: String
    /// A word to search the book for when the reader checks against the text.
    var searchTerm: String?

    var id: String { title }
}

struct ChapterClosurePlan: Equatable, Sendable {
    var chapterTitle: String
    var estimatedSeconds: Int
    var prompts: [ChapterClosurePrompt]
}

/// Three 30-second steps at the end of a chapter: recall, the real mistake, the text.
enum ChapterClosurePolicy {
    static let clozeBlank = "＿＿"

    static func build(
        mode: ReadingCompanionMode,
        chapterTitle: String,
        confusionItems: [ReadingConfusionItem]
    ) -> ChapterClosurePlan {
        let recall = switch mode {
        case .fiction: "不用看答案，用一句话回想本章人物做了什么、为什么这样做。"
        case .nonfiction: "不用看答案，用一句话复述本章核心论点和最关键的证据。"
        }
        let weakest = confusionItems.first
        let term = weakest.map { ReadingCompanionText.trimmed($0.displayTerm) }.flatMap { $0.isEmpty ? nil : $0 }
        // Only the first occurrence is blanked, matched literally.
        var cloze: String?
        if let weakest, let term,
           let range = weakest.sentenceContext.range(of: term, options: .literal)
        {
            cloze = weakest.sentenceContext.replacingCharacters(in: range, with: clozeBlank)
        }
        let mistake: String
        if let cloze {
            mistake = "补全真实原句：\(cloze)"
        } else if let term {
            mistake = "回想“\(term)”在本章语境中的含义，并说出判断依据。"
        } else {
            mistake = "选一个刚读过的关键句，复述它与本章主旨的关系。"
        }
        let check = term.map { "回到原文核对“\($0)”，只修正一个最影响理解的点。" }
            ?? "回到本章最后一页，确认刚才的概括能被原文支持。"
        return ChapterClosurePlan(
            chapterTitle: chapterTitle,
            estimatedSeconds: 90,
            prompts: [
                ChapterClosurePrompt(title: "30 秒 · 章节回想", prompt: recall, searchTerm: nil),
                ChapterClosurePrompt(title: "30 秒 · 阅读错误", prompt: mistake, searchTerm: term),
                ChapterClosurePrompt(title: "30 秒 · 回到原文", prompt: check, searchTerm: term),
            ]
        )
    }
}

struct ContinuationRecapPlan: Equatable, Sendable {
    var title: String
    var bullets: [String]
}

/// What to recall before reading on: where you were, how the book is built, what tripped you.
enum ContinuationRecapPolicy {
    static func build(
        mode: ReadingCompanionMode,
        bookTitle: String,
        chapterTitle: String,
        confusionItems: [ReadingConfusionItem]
    ) -> ContinuationRecapPlan {
        let focus = confusionItems.prefix(3).map(\.displayTerm).joined(separator: "、")
        let structure = switch mode {
        case .fiction: "续读前先回想人物关系、上一段行动与尚未确认的动机。"
        case .nonfiction: "续读前先回想上一节的核心概念、论点与证据链。"
        }
        let weakness = ReadingCompanionText.isBlank(focus)
            ? "目前没有明显阅读困难，先用章节标题恢复上下文。"
            : "你的近期薄弱点：\(focus)；遇到时先根据上下文判断，再决定是否查词。"
        let trimmedChapter = ReadingCompanionText.trimmed(chapterTitle)
        let position = trimmedChapter.isEmpty ? "上次的位置" : trimmedChapter
        return ContinuationRecapPlan(
            title: "继续《\(bookTitle)》",
            bullets: ["上次读到：\(position)", structure, weakness]
        )
    }
}

/// Kotlin's `trim()` and `isBlank()`, so both apps cut the same characters.
enum ReadingCompanionText {
    static func trimmed(_ text: String) -> String {
        text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func isBlank(_ text: String) -> Bool {
        trimmed(text).isEmpty
    }
}
