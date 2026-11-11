import Foundation

@main
struct LearningFeatureTests {
    static func main() throws {
        let now = Date(timeIntervalSince1970: 100)
        var state = WordReviewState()
        for days in [1, 3, 7, 14, 30, 60, 60] {
            state.grade(.known, at: now)
            precondition(state.dueAt.timeIntervalSince(now) == Double(days) * 86_400)
        }
        precondition(state.answers.count == 7)
        state.grade(.fuzzy, at: now)
        precondition(state.step == 4 && state.dueAt.timeIntervalSince(now) == 86_400)
        state.grade(.unknown, at: now)
        precondition(state.step == 0 && state.dueAt.timeIntervalSince(now) == 600)
        state.paused = true
        let encoded = try JSONEncoder().encode(state)
        precondition(WordReviewState.decode(encoded) == state)
        precondition(WordReviewState.decode(nil) == WordReviewState())
        precondition(WordReviewState.decode(Data("broken".utf8)) == WordReviewState())
        print("PASS: review intervals, lapse, fuzzy, pause and backup round-trip")
        let sentence = "猫は猫です。"
        precondition(SentencePractice.make(source: sentence, answer: " 猫 ")?.prompt == "［____］は［____］です。")
        precondition(SentencePractice.make(source: sentence, answer: "犬") == nil)
        precondition(SentencePractice.make(source: sentence, answer: " ") == nil)
        precondition(SentencePractice.make(source: "a.*b", answer: ".*")?.prompt == "a［____］b")
        let card = SentencePractice(answer: "猫", prompt: "编辑后的题面")
        let savedCard = try JSONEncoder().encode(card)
        precondition(SentencePractice.decode(savedCard) == card)
        print("PASS: sentence validation, literal masking and edited-card persistence")
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Tokyo")!
        let iso = ISO8601DateFormatter()
        let from = iso.date(from: "2026-09-07T14:50:00Z")!
        let to = iso.date(from: "2026-09-07T15:10:00Z")!
        var activity = ReadingActivity()
        activity.record(from: from, to: to, calendar: calendar)
        activity.query(at: to, calendar: calendar)
        precondition(activity.days["2026-09-07"]?.seconds == 600)
        precondition(activity.days["2026-09-08"] == ReadingDay(seconds: 600, queries: 1))
        let savedActivity = try JSONEncoder().encode(activity)
        precondition(ReadingActivity.decode(savedActivity) == activity)
        var dst = ReadingActivity()
        calendar.timeZone = TimeZone(identifier: "America/New_York")!
        dst.record(from: iso.date(from: "2026-03-08T06:30:00Z")!, to: iso.date(from: "2026-03-08T07:30:00Z")!, calendar: calendar)
        precondition(dst.days.values.reduce(0) { $0 + $1.seconds } == 3600)
        print("PASS: midnight split, daylight saving, query counts and report backup")
        try snapshots()
        try snapshotBenchmark()
    }

    static func snapshots() throws {
        let inputs = (0..<1500).map { WordReviewSnapshot.Input(id: UUID(), data: Data(String($0 % 17).utf8)) }
        var decodes = 0
        let snapshot = try WordReviewSnapshot(inputs: inputs) { data in
            decodes += 1
            let value = Int(String(data: data!, encoding: .utf8)!)!
            return WordReviewState(dueAt: Date(timeIntervalSince1970: Double(value)), paused: value == 3)
        }
        let due = snapshot.dueIDs(at: Date(timeIntervalSince1970: 4))
        precondition(Array(due.prefix(3)) == [inputs[0].id, inputs[17].id, inputs[34].id])
        precondition(due.allSatisfy { !snapshot.states[$0]!.paused && snapshot.states[$0]!.dueAt.timeIntervalSince1970 <= 4 })
        _ = snapshot.dueIDs(at: Date(timeIntervalSince1970: 100))
        precondition(decodes == 1500, "Clock ticks must not decode JSON again")
        let id = UUID()
        let before = try WordReviewSnapshot(inputs: [.init(id: id, data: JSONEncoder().encode(WordReviewState(paused: true)))])
        let after = try WordReviewSnapshot(inputs: [.init(id: id, data: JSONEncoder().encode(WordReviewState(dueAt: Date(timeIntervalSince1970: 12))))])
        precondition(before.dueIDs(at: Date(timeIntervalSince1970: 12)).isEmpty)
        precondition(after.dueIDs(at: Date(timeIntervalSince1970: 11)).isEmpty)
        precondition(after.dueIDs(at: Date(timeIntervalSince1970: 12)) == [id])
        enum Stop: Error { case cancelled }
        var visited = 0
        do {
            _ = try WordReviewSnapshot(inputs: inputs, checkpoint: {
                visited += 1
                if visited == 3 { throw Stop.cancelled }
            })
            preconditionFailure("Cancelled work must stop")
        } catch Stop.cancelled { precondition(visited == 3) }

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/New_York")!
        let start = calendar.date(from: DateComponents(year: 2026, month: 3, day: 2))!
        let end = calendar.date(byAdding: .day, value: 7, to: start)!
        let otherBook = UUID()
        let review = try JSONEncoder().encode(WordReviewState(answers: [
            .init(at: start.addingTimeInterval(-1), known: true, bookID: id),
            .init(at: start, known: true, bookID: id),
            .init(at: end.addingTimeInterval(-1), known: false, bookID: id),
            .init(at: start, known: true, bookID: otherBook),
            .init(at: end, known: true, bookID: id)
        ]))
        let activity = try JSONEncoder().encode(ReadingActivity(days: [
            "2026-03-02": ReadingDay(seconds: 600, queries: 2),
            "2026-03-08": ReadingDay(seconds: 1200, queries: 3),
            "2026-03-09": ReadingDay(seconds: 9999, queries: 9)
        ]))
        let request = ReadingGrowthSnapshot.Request(activities: [activity], reviews: [review], start: start,
            bookID: id, calendar: calendar, enabled: true)
        let report = try ReadingGrowthSnapshot(request: request)
        precondition(report.rows.count == 7 && report.seconds == 1800 && report.queries == 5)
        precondition(report.readingDays == 2 && report.maxSeconds == 1200)
        precondition(report.answerCount == 2 && report.knownAnswers == 1)
        let all = try ReadingGrowthSnapshot(request: .init(activities: [], reviews: [review], start: start,
            bookID: nil, calendar: calendar, enabled: true))
        precondition(all.answerCount == 3)
        do {
            _ = try ReadingGrowthSnapshot(request: request, checkpoint: { throw Stop.cancelled })
            preconditionFailure("Cancelled aggregation must stop")
        } catch Stop.cancelled { }
        print("PASS: 1500-record snapshot, stable order, revision, cancellation, week boundaries and book filtering")
    }

    /// A bounded host comparison of the removed decode-in-sort path; not a device FPS measurement.
    static func snapshotBenchmark() throws {
        let inputs = try (0..<600).map { index in
            let state = WordReviewState(dueAt: Date(timeIntervalSince1970: Double(index % 31)),
                answers: (0..<20).map { ReviewAnswer(at: Date(timeIntervalSince1970: Double($0)), known: true) })
            return WordReviewSnapshot.Input(id: UUID(), data: try JSONEncoder().encode(state))
        }
        let now = Date(timeIntervalSince1970: 100)
        var oldDecodes = 0
        func decode(_ data: Data?) -> WordReviewState { oldDecodes += 1; return .decode(data) }
        let clock = ContinuousClock()
        let oldStart = clock.now
        let old = inputs.filter { !decode($0.data).paused && decode($0.data).dueAt <= now }
            .sorted { decode($0.data).dueAt < decode($1.data).dueAt }.map(\.id)
        let oldDuration = oldStart.duration(to: clock.now)
        let newStart = clock.now
        let snapshot = try WordReviewSnapshot(inputs: inputs)
        let revised = snapshot.dueIDs(at: now)
        let newDuration = newStart.duration(to: clock.now)
        precondition(old == revised)
        print("HOST BENCHMARK: 600 records × 20 answers; old \(oldDuration), \(oldDecodes) decodes; snapshot \(newDuration), 600 decodes")
    }
}
