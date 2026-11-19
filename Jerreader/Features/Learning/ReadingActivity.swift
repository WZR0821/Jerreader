import Foundation

struct ReadingDay: Codable, Equatable, Sendable {
    var seconds: Double = 0
    var queries: Int = 0
}

struct ReadingActivity: Codable, Equatable, Sendable {
    static let enabledKey = "jerreader.growth.enabled"
    var days: [String: ReadingDay] = [:]

    /// Day keys are always Gregorian. `Calendar.current` follows the device's
    /// calendar setting; under the Japanese, Buddhist or ROC calendar it wrote
    /// years like 0008, 2569 or 0115, which the report (it reads ISO weeks)
    /// never found, so every reading time showed as zero.
    static var dayCalendar: Calendar {
        var value = Calendar(identifier: .iso8601)
        value.timeZone = .current
        return value
    }

    static func key(_ date: Date, calendar: Calendar = ReadingActivity.dayCalendar) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    mutating func record(from start: Date, to end: Date, calendar: Calendar = ReadingActivity.dayCalendar) {
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

    mutating func query(at date: Date, calendar: Calendar = ReadingActivity.dayCalendar) {
        days[Self.key(date, calendar: calendar), default: ReadingDay()].queries += 1
        trim()
    }

    private mutating func trim() {
        for key in days.keys.sorted().dropLast(730) { days.removeValue(forKey: key) }
    }

    static func decode(_ data: Data?) -> Self {
        var value = data.flatMap { try? JSONDecoder().decode(Self.self, from: $0) } ?? Self()
        value.repairNonGregorianKeys()
        return value
    }

    /// Brings back the days written under another calendar before 2.0.1. The
    /// app dates from 2026, so the year alone says which calendar wrote it:
    /// Reiwa counts from 2018, the ROC calendar from 1911, the Buddhist one
    /// runs 543 ahead. Their months and days are the Gregorian ones; calendars
    /// with other months cannot be mapped back and are left as they are.
    private mutating func repairNonGregorianKeys() {
        for (key, day) in days {
            guard let fixed = Self.gregorianKey(key), fixed != key else { continue }
            days.removeValue(forKey: key)
            days[fixed, default: ReadingDay()].seconds += day.seconds
            days[fixed, default: ReadingDay()].queries += day.queries
        }
    }

    static func gregorianKey(_ key: String) -> String? {
        let parts = key.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, let year = Int(parts[0]) else { return nil }
        let offset: Int
        switch year {
        case 1..<100: offset = 2018
        case 100..<1000: offset = 1911
        case 2500..<3200: offset = -543
        default: return key
        }
        return String(format: "%04d", year + offset) + "-\(parts[1])-\(parts[2])"
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
