import Foundation

struct ReadingDay: Codable, Equatable, Sendable {
    var seconds: Double = 0
    var queries: Int = 0
}

struct ReadingActivity: Codable, Equatable, Sendable {
    static let enabledKey = "jerreader.growth.enabled"
    var days: [String: ReadingDay] = [:]

    static func key(_ date: Date, calendar: Calendar = .current) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    mutating func record(from start: Date, to end: Date, calendar: Calendar = .current) {
        guard end.timeIntervalSince(start).isFinite, end > start else { return }
        var cursor = max(start, end.addingTimeInterval(-21_600))
        while cursor < end {
            guard let next = calendar.date(byAdding: .day, value: 1, to: calendar.startOfDay(for: cursor)) else { break }
            let stop = min(next, end)
            days[Self.key(cursor, calendar: calendar), default: ReadingDay()].seconds += stop.timeIntervalSince(cursor)
            cursor = stop
        }
        trim()
    }

    mutating func query(at date: Date, calendar: Calendar = .current) {
        days[Self.key(date, calendar: calendar), default: ReadingDay()].queries += 1
        trim()
    }

    private mutating func trim() {
        for key in days.keys.sorted().dropLast(730) { days.removeValue(forKey: key) }
    }

    static func decode(_ data: Data?) -> Self {
        data.flatMap { try? JSONDecoder().decode(Self.self, from: $0) } ?? Self()
    }
}

struct ReadingGrowthSnapshot: Sendable {
    struct Request: Equatable, Sendable {
        let activities: [Data?]
        let reviews: [Data?]
        let start: Date
        let bookID: UUID?
        let calendar: Calendar
        let enabled: Bool
    }
    struct Row: Identifiable, Sendable {
        let id: String
        let value: ReadingDay
    }
    let rows: [Row]
    let seconds: Double
    let queries: Int
    let answerCount: Int
    let knownAnswers: Int
    let maxSeconds: Double
    var readingDays: Int { rows.filter { $0.value.seconds > 0 }.count }

    init(request: Request, checkpoint: () throws -> Void = {}) throws {
        let dates = (0..<7).map { offset in
            ReadingActivity.key(request.calendar.date(byAdding: .day, value: offset, to: request.start)!, calendar: request.calendar)
        }
        var days = Dictionary(uniqueKeysWithValues: dates.map { ($0, ReadingDay()) })
        for data in request.activities {
            try checkpoint()
            let activity = ReadingActivity.decode(data)
            for key in dates {
                if let day = activity.days[key] {
                    days[key]!.seconds += day.seconds
                    days[key]!.queries += day.queries
                }
            }
        }
        let end = request.calendar.date(byAdding: .day, value: 7, to: request.start)!
        var count = 0
        var known = 0
        for data in request.reviews {
            try checkpoint()
            for answer in WordReviewState.decode(data).answers {
                if answer.at >= request.start && answer.at < end && (request.bookID == nil || answer.bookID == request.bookID) {
                    count += 1
                    if answer.known { known += 1 }
                }
            }
        }
        rows = dates.map { Row(id: $0, value: days[$0]!) }
        seconds = rows.reduce(0) { $0 + $1.value.seconds }
        queries = rows.reduce(0) { $0 + $1.value.queries }
        maxSeconds = max(rows.map(\.value.seconds).max() ?? 1, 1)
        answerCount = count
        knownAnswers = known
    }
}
