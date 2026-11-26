import Foundation

// Runs the cases in shared/parity/ against the Swift rules. The Android host
// test ParityFixturesTest runs the same files against the shared Kotlin rules,
// so a rule changed on one platform only fails one of the two.

private struct WordReviewFixture: Decodable {
    struct Step: Decodable { let grade: String; let step: Int; let delayMs: Int64 }
    struct Case: Decodable {
        let name: String
        let startStep: Int
        let paused: Bool
        let prefilledAnswers: Int
        let steps: [Step]
        let finalAnswerCount: Int
    }
    let nowMs: Int64
    let cases: [Case]
}

private struct SentencePracticeFixture: Decodable {
    struct Expected: Decodable { let answer: String; let prompt: String }
    struct Case: Decodable { let name: String; let source: String; let answer: String; let expected: Expected? }
    let cases: [Case]
}

private struct BackupPruneFixture: Decodable {
    struct Backup: Decodable { let name: String; let createdAtMs: Int64; let sizeBytes: Int64 }
    struct Case: Decodable {
        let name: String
        let retentionDays: Int
        let maximumBackupCount: Int
        let maximumTotalBytes: Int64
        let backups: [Backup]
        let removed: [String]
        let remaining: [String]
        let isOverLimit: Bool
    }
    let nowMs: Int64
    let cases: [Case]
}

private struct ReadingCompanionFixture: Decodable {
    struct Signal: Decodable {
        let lookupKey: String
        let displayTerm: String
        let lemma: String?
        let sentenceContext: String
        let sourceBookId: String
        let sourceBookTitle: String
        let lookupCount: Int
        let known: [Bool]
    }
    struct ReviewSummaryCase: Decodable {
        let name: String
        let known: [Bool]
        let failedAnswerCount: Int
        let lapseCount: Int
        let successStreak: Int
        let hasUnresolvedFailure: Bool
    }
    struct MapItem: Decodable { let lookupKey: String; let kind: String; let score: Int; let reason: String }
    struct ConfusionMapCase: Decodable {
        let name: String
        let limit: Int
        let bookId: String?
        let signals: [Signal]
        let items: [MapItem]
        let topKind: String?
        let summary: String
    }
    struct AssistCase: Decodable {
        let name: String
        let mode: String
        let bookId: String?
        let signals: [Signal]
        let level: String
        let focusTerms: [String]
        let instruction: String
        let reason: String
    }
    struct ClosureItem: Decodable { let lookupKey: String; let displayTerm: String; let sentenceContext: String }
    struct ClosurePrompt: Decodable { let title: String; let prompt: String; let searchTerm: String? }
    struct ClosureCase: Decodable {
        let name: String
        let mode: String
        let chapterTitle: String
        let items: [ClosureItem]
        let prompts: [ClosurePrompt]
    }
    struct RecapCase: Decodable {
        let name: String
        let mode: String
        let bookTitle: String
        let chapterTitle: String
        let terms: [String]
        let title: String
        let bullets: [String]
    }
    struct LensPromptCase: Decodable {
        let name: String
        let mode: String
        let companionMode: String
        let assistLevel: String
        let adaptiveInstruction: String?
        let focusTerms: [String]
        let sourceLanguage: String?
        let responseLanguage: String
        let grammarPrompt: String?
        let expected: String
    }
    let reviewSummary: [ReviewSummaryCase]
    let confusionMap: [ConfusionMapCase]
    let assist: [AssistCase]
    let closure: [ClosureCase]
    let recap: [RecapCase]
    let lensPrompt: [LensPromptCase]
}

private struct AIActionsFixture: Decodable {
    struct Action: Decodable { let id: String; let title: String }
    struct ActionsCase: Decodable { let sourceLanguage: String?; let expected: [Action] }
    struct PresetsCase: Decodable { let sourceLanguage: String?; let expected: [String] }
    struct InstructionCase: Decodable {
        let name: String
        let action: String
        let sourceLanguage: String?
        let responseLanguage: String
        let expected: String
    }
    struct MaterialCase: Decodable {
        let name: String
        let source: String
        let translation: String?
        let context: String?
        let expected: String
    }
    struct Turn: Decodable { let question: String; let answer: String? }
    struct Message: Decodable { let role: String; let text: String }
    struct FollowUpCase: Decodable {
        let name: String
        let source: String
        let translation: String?
        let context: String?
        let sourceLanguage: String?
        let responseLanguage: String
        let turns: [Turn]
        let messages: [Message]
        let flattened: String
    }
    struct BadgeCase: Decodable { let model: String?; let usedContext: Bool; let expected: String }
    let guardSentence: String
    let maximumFollowUpTurns: Int
    let actions: [ActionsCase]
    let presets: [PresetsCase]
    let instructions: [InstructionCase]
    let material: [MaterialCase]
    let followUp: [FollowUpCase]
    let badge: [BadgeCase]

    enum CodingKeys: String, CodingKey {
        case guardSentence = "guard"
        case maximumFollowUpTurns, actions, presets, instructions, material, followUp, badge
    }
}

private struct ChapterEstimateFixture: Decodable {
    struct Rule: Decodable {
        let minimumReadingSeconds: Double
        let minimumProgression: Double
        let maximumMinutes: Int
        let underOneMinuteSeconds: Double
    }
    struct Case: Decodable {
        let name: String
        let readingSeconds: Double
        let progression: Double
        let chapterEnd: Double?
        let minutes: Int?
        let text: String?
    }
    let rule: Rule
    let cases: [Case]
}

private struct LookupHintFixture: Decodable {
    struct Case: Decodable {
        let name: String
        let lookupCount: Int
        let previousLookupAt: String?
        let hint: String?
        let addToVocabularyIsPrimary: Bool
    }
    let timeZone: String
    let now: String
    let cases: [Case]
}

private struct CompanionDotFixture: Decodable {
    struct Case: Decodable {
        let name: String
        let progress: Double
        let lastOpenedAt: String?
        let expected: Bool
    }
    let minimumProgress: Double
    let quietHours: Int
    let now: String
    let cases: [Case]
}

private var failures = 0

private func expect<T: Equatable>(_ actual: T, _ expected: T, _ label: String) {
    guard actual != expected else { return }
    failures += 1
    print("FAIL: \(label): expected \(expected), got \(actual)")
}

private func load<T: Decodable>(_ name: String, as type: T.Type) throws -> T {
    // safe_ios_validation.py runs host suites from the repository root.
    let url = URL(fileURLWithPath: "shared/parity/" + name)
    return try JSONDecoder().decode(T.self, from: Data(contentsOf: url))
}

private func date(_ milliseconds: Int64) -> Date {
    Date(timeIntervalSince1970: Double(milliseconds) / 1000)
}

private func fail(_ message: String) {
    failures += 1
    print("FAIL: " + message)
}

/// A language tag of a fixture; a tag the app does not know fails the case.
private func language(_ tag: String?, _ label: String) -> (ok: Bool, value: LanguageCode?) {
    guard let tag else { return (true, nil) }
    guard let value = LanguageCode(rawValue: tag) else {
        fail(label + ": unknown language " + tag)
        return (false, nil)
    }
    return (true, value)
}

private func isoDate(_ text: String, _ label: String) -> Date? {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime]
    guard let value = formatter.date(from: text) else {
        fail(label + ": unreadable date " + text)
        return nil
    }
    return value
}

private func signal(_ json: ReadingCompanionFixture.Signal) -> ReadingErrorSignal {
    ReadingErrorSignal(
        lookupKey: json.lookupKey,
        displayTerm: json.displayTerm,
        lemma: json.lemma,
        sentenceContext: json.sentenceContext,
        sourceBookID: json.sourceBookId,
        sourceBookTitle: json.sourceBookTitle,
        lookupCount: json.lookupCount,
        review: ReadingReviewSummary.of(json.known)
    )
}

/// The Kotlin test builds the same placeholder items for the closure and recap cases.
private func confusionItem(_ key: String, _ term: String, _ sentence: String) -> ReadingConfusionItem {
    ReadingConfusionItem(
        lookupKey: key,
        displayTerm: term,
        kind: .wordMeaning,
        score: 1,
        reason: "",
        sentenceContext: sentence,
        sourceBookID: "b1",
        sourceBookTitle: "书一"
    )
}

private func checkReadingCompanion() throws {
    let root = try load("reading_companion.json", as: ReadingCompanionFixture.self)

    for testCase in root.reviewSummary {
        expect(
            ReadingReviewSummary.of(testCase.known),
            ReadingReviewSummary(
                failedAnswerCount: testCase.failedAnswerCount,
                lapseCount: testCase.lapseCount,
                successStreak: testCase.successStreak,
                hasUnresolvedFailure: testCase.hasUnresolvedFailure
            ),
            testCase.name
        )
    }

    for testCase in root.confusionMap {
        let map = ReadingConfusionMapPolicy.build(
            testCase.signals.map(signal),
            limit: testCase.limit,
            bookID: testCase.bookId
        )
        expect(map.items.map(\.lookupKey), testCase.items.map(\.lookupKey), testCase.name + ": order")
        for (want, got) in zip(testCase.items, map.items) {
            let label = testCase.name + ", " + got.lookupKey
            expect(got.kind.rawValue, want.kind, label + ": kind")
            expect(got.score, want.score, label + ": score")
            expect(got.reason, want.reason, label + ": reason")
        }
        expect(map.topKind?.rawValue, testCase.topKind, testCase.name + ": top kind")
        expect(map.summary, testCase.summary, testCase.name + ": summary")
    }

    for testCase in root.assist {
        let assist = AdaptiveReadingAssistPolicy.resolve(
            testCase.signals.map(signal),
            mode: ReadingCompanionMode.from(storageID: testCase.mode),
            bookID: testCase.bookId
        )
        expect(assist.level.rawValue, testCase.level, testCase.name + ": level")
        expect(assist.focusTerms, testCase.focusTerms, testCase.name + ": focus")
        expect(assist.instruction, testCase.instruction, testCase.name + ": instruction")
        expect(assist.reason, testCase.reason, testCase.name + ": reason")
    }

    for testCase in root.closure {
        let plan = ChapterClosurePolicy.build(
            mode: ReadingCompanionMode.from(storageID: testCase.mode),
            chapterTitle: testCase.chapterTitle,
            confusionItems: testCase.items.map { confusionItem($0.lookupKey, $0.displayTerm, $0.sentenceContext) }
        )
        expect(plan.chapterTitle, testCase.chapterTitle, testCase.name + ": chapter")
        expect(plan.estimatedSeconds, 90, testCase.name + ": seconds")
        expect(plan.prompts.count, testCase.prompts.count, testCase.name + ": prompt count")
        for (index, (want, got)) in zip(testCase.prompts, plan.prompts).enumerated() {
            let label = testCase.name + ", prompt \(index + 1)"
            expect(got.title, want.title, label + ": title")
            expect(got.prompt, want.prompt, label + ": text")
            expect(got.searchTerm, want.searchTerm, label + ": search")
        }
    }

    for testCase in root.recap {
        let recap = ContinuationRecapPolicy.build(
            mode: ReadingCompanionMode.from(storageID: testCase.mode),
            bookTitle: testCase.bookTitle,
            chapterTitle: testCase.chapterTitle,
            confusionItems: testCase.terms.map { confusionItem("k|" + $0, $0, "") }
        )
        expect(recap.title, testCase.title, testCase.name + ": title")
        expect(recap.bullets, testCase.bullets, testCase.name + ": bullets")
    }

    for testCase in root.lensPrompt {
        guard let mode = AIReadingLensMode(rawValue: testCase.mode),
              let level = ReadingAssistLevel(rawValue: testCase.assistLevel),
              let response = LanguageCode(rawValue: testCase.responseLanguage)
        else {
            fail(testCase.name + ": unknown mode, level or language")
            continue
        }
        let source: LanguageCode?
        if let tag = testCase.sourceLanguage {
            guard let parsed = LanguageCode(rawValue: tag) else {
                fail(testCase.name + ": unknown source language " + tag)
                continue
            }
            source = parsed
        } else {
            source = nil
        }
        let options = ReadingLensOptions(
            mode: mode,
            companionMode: ReadingCompanionMode.from(storageID: testCase.companionMode),
            assistLevel: level,
            adaptiveInstruction: testCase.adaptiveInstruction,
            focusTerms: testCase.focusTerms
        )
        expect(
            AIReadingLensPromptPolicy.rendered(
                options,
                sourceLanguage: source,
                responseLanguage: response,
                grammarPrompt: testCase.grammarPrompt
            ),
            testCase.expected,
            testCase.name
        )
    }

    let total = root.reviewSummary.count + root.confusionMap.count + root.assist.count
        + root.closure.count + root.recap.count + root.lensPrompt.count
    print("checked \(total) reading-companion cases")
}

private func checkAIActions() throws {
    let root = try load("ai_actions.json", as: AIActionsFixture.self)
    expect(AIReadingActionPolicy.guardSentence, root.guardSentence, "ai actions: guard")
    expect(AIReadingActionPolicy.maximumFollowUpTurns, root.maximumFollowUpTurns, "ai actions: maximum turns")

    for testCase in root.actions {
        let label = "actions for " + (testCase.sourceLanguage ?? "auto")
        let source = language(testCase.sourceLanguage, label)
        guard source.ok else { continue }
        let actions = AIReadingActionPolicy.actions(for: source.value)
        expect(actions.map(\.rawValue), testCase.expected.map(\.id), label + ": ids")
        expect(actions.map(\.title), testCase.expected.map(\.title), label + ": titles")
    }

    for testCase in root.presets {
        let label = "presets for " + (testCase.sourceLanguage ?? "auto")
        let source = language(testCase.sourceLanguage, label)
        guard source.ok else { continue }
        expect(AIReadingActionPolicy.presets(for: source.value), testCase.expected, label)
    }

    for testCase in root.instructions {
        let source = language(testCase.sourceLanguage, testCase.name)
        let response = language(testCase.responseLanguage, testCase.name)
        guard source.ok, response.ok, let responseLanguage = response.value,
              let action = AIReadingAction(rawValue: testCase.action)
        else {
            fail(testCase.name + ": unknown action or language")
            continue
        }
        expect(
            AIReadingActionPolicy.instruction(
                for: action, sourceLanguage: source.value, responseLanguage: responseLanguage
            ),
            testCase.expected,
            testCase.name
        )
    }

    for testCase in root.material {
        expect(
            AIReadingActionPolicy.material(
                source: testCase.source, translation: testCase.translation, context: testCase.context
            ),
            testCase.expected,
            testCase.name
        )
    }

    for testCase in root.followUp {
        let source = language(testCase.sourceLanguage, testCase.name)
        let response = language(testCase.responseLanguage, testCase.name)
        guard source.ok, response.ok, let responseLanguage = response.value else { continue }
        let turns = testCase.turns.map { AIFollowUpTurn(question: $0.question, answer: $0.answer) }
        let messages = AIReadingActionPolicy.followUpMessages(
            source: testCase.source,
            translation: testCase.translation,
            context: testCase.context,
            sourceLanguage: source.value,
            responseLanguage: responseLanguage,
            turns: turns
        )
        expect(messages.map(\.role.rawValue), testCase.messages.map(\.role), testCase.name + ": roles")
        expect(messages.map(\.text), testCase.messages.map(\.text), testCase.name + ": messages")
        expect(
            AIReadingActionPolicy.followUpFlattened(
                source: testCase.source,
                translation: testCase.translation,
                context: testCase.context,
                turns: turns
            ),
            testCase.flattened,
            testCase.name + ": flattened"
        )
    }

    for testCase in root.badge {
        expect(
            AIReadingActionPolicy.badge(model: testCase.model, usedContext: testCase.usedContext),
            testCase.expected,
            "badge for " + (testCase.model ?? "nil")
        )
    }

    let total = root.actions.count + root.presets.count + root.instructions.count
        + root.material.count + root.followUp.count + root.badge.count
    print("checked \(total) ai-action cases")
}

private func checkChapterEstimate() throws {
    let root = try load("chapter_estimate.json", as: ChapterEstimateFixture.self)
    expect(ChapterTimeEstimatePolicy.minimumReadingSeconds, root.rule.minimumReadingSeconds, "chapter: minimum seconds")
    expect(ChapterTimeEstimatePolicy.minimumProgression, root.rule.minimumProgression, "chapter: minimum progression")
    expect(ChapterTimeEstimatePolicy.maximumMinutes, root.rule.maximumMinutes, "chapter: maximum minutes")
    expect(ChapterTimeEstimatePolicy.underOneMinuteSeconds, root.rule.underOneMinuteSeconds, "chapter: under a minute")
    for testCase in root.cases {
        let minutes = ChapterTimeEstimatePolicy.minutes(
            readingSeconds: testCase.readingSeconds,
            progression: testCase.progression,
            chapterEnd: testCase.chapterEnd
        )
        expect(minutes, testCase.minutes, testCase.name + ": minutes")
        expect(ChapterTimeEstimatePolicy.text(minutes: minutes), testCase.text, testCase.name + ": text")
    }
    print("checked \(root.cases.count) chapter-estimate cases")
}

private func checkLookupHint() throws {
    let root = try load("lookup_hint.json", as: LookupHintFixture.self)
    guard let timeZone = TimeZone(identifier: root.timeZone) else {
        fail("lookup hint: unknown time zone " + root.timeZone)
        return
    }
    guard let now = isoDate(root.now, "lookup hint: now") else { return }
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = timeZone
    for testCase in root.cases {
        var previous: Date?
        if let text = testCase.previousLookupAt {
            guard let parsed = isoDate(text, testCase.name) else { continue }
            previous = parsed
        }
        expect(
            LookupHintPolicy.hint(
                lookupCount: testCase.lookupCount, previousLookupAt: previous, now: now, calendar: calendar
            ),
            testCase.hint,
            testCase.name + ": hint"
        )
        expect(
            LookupHintPolicy.addToVocabularyIsPrimary(lookupCount: testCase.lookupCount),
            testCase.addToVocabularyIsPrimary,
            testCase.name + ": primary"
        )
    }
    print("checked \(root.cases.count) lookup-hint cases")
}

private func checkCompanionDot() throws {
    let root = try load("companion_dot.json", as: CompanionDotFixture.self)
    expect(ReaderCompanionDotPolicy.minimumProgress, root.minimumProgress, "dot: minimum progress")
    expect(ReaderCompanionDotPolicy.quietHours, root.quietHours, "dot: quiet hours")
    guard let now = isoDate(root.now, "dot: now") else { return }
    for testCase in root.cases {
        var last: Date?
        if let text = testCase.lastOpenedAt {
            guard let parsed = isoDate(text, testCase.name) else { continue }
            last = parsed
        }
        expect(
            ReaderCompanionDotPolicy.showsDot(progress: testCase.progress, lastOpenedAt: last, now: now),
            testCase.expected,
            testCase.name
        )
    }
    print("checked \(root.cases.count) companion-dot cases")
}

@main
struct ParityFixtureTests {
    static func main() throws {
        let review = try load("word_review.json", as: WordReviewFixture.self)
        let now = date(review.nowMs)
        for testCase in review.cases {
            var state = WordReviewState()
            state.step = testCase.startStep
            state.paused = testCase.paused
            state.answers = Array(
                repeating: ReviewAnswer(at: Date(timeIntervalSince1970: 0), known: false),
                count: testCase.prefilledAnswers
            )
            for (index, step) in testCase.steps.enumerated() {
                guard let grade = ReviewGrade(rawValue: step.grade) else {
                    failures += 1
                    print("FAIL: \(testCase.name): unknown grade \(step.grade)")
                    continue
                }
                state.grade(grade, at: now)
                let label = "\(testCase.name), answer \(index + 1)"
                expect(state.step, step.step, label + ": step")
                expect(Int64((state.dueAt.timeIntervalSince(now) * 1000).rounded()), step.delayMs, label + ": delay")
                expect(state.answers.last?.known, grade == .known, label + ": recorded")
            }
            expect(state.paused, testCase.paused, testCase.name + ": paused")
            expect(state.answers.count, testCase.finalAnswerCount, testCase.name + ": history size")
        }
        print("checked \(review.cases.count) review cases")

        let practice = try load("sentence_practice.json", as: SentencePracticeFixture.self)
        for testCase in practice.cases {
            let made = SentencePractice.make(source: testCase.source, answer: testCase.answer)
            let expected = testCase.expected.map { SentencePractice(answer: $0.answer, prompt: $0.prompt) }
            expect(made, expected, testCase.name)
        }
        print("checked \(practice.cases.count) sentence-practice cases")

        let prune = try load("backup_prune.json", as: BackupPruneFixture.self)
        let pruneNow = date(prune.nowMs)
        for testCase in prune.cases {
            let plan = LibraryBackupPruner.plan(
                testCase.backups,
                limits: LibraryBackupPruner.Limits(
                    retentionDays: testCase.retentionDays,
                    maximumBackupCount: testCase.maximumBackupCount,
                    maximumTotalBytes: testCase.maximumTotalBytes
                ),
                now: pruneNow,
                createdAt: { date($0.createdAtMs) },
                size: \.sizeBytes
            )
            expect(plan.removed.map(\.name), testCase.removed, testCase.name + ": removed")
            expect(plan.remaining.map(\.name), testCase.remaining, testCase.name + ": remaining")
            expect(plan.isOverLimit, testCase.isOverLimit, testCase.name + ": over limit")
        }
        print("checked \(prune.cases.count) backup-prune cases")

        try checkReadingCompanion()
        try checkAIActions()
        try checkChapterEstimate()
        try checkLookupHint()
        try checkCompanionDot()

        guard failures == 0 else {
            print("FAIL: \(failures) parity mismatch(es)")
            exit(1)
        }
        print("PASS: Swift rules match shared/parity fixtures")
    }
}
