import Foundation

enum ReviewGrade: String, CaseIterable {
    case unknown, fuzzy, known
    var title: String {
        switch self {
        case .unknown: return "不认识"
        case .fuzzy: return "模糊"
        case .known: return "认识"
        }
    }
}

/// Stored with the word: deleting it also removes its schedule and history.
struct WordReviewState: Codable, Equatable, Sendable {
    var step = 0
    var dueAt: Date = .distantPast
    var paused = false
    var answers: [ReviewAnswer] = []

    mutating func grade(_ grade: ReviewGrade, at now: Date, bookID: UUID? = nil) {
        let days = [1, 3, 7, 14, 30, 60]
        step = min(max(step, 0), days.count - 1)
        let seconds: TimeInterval
        switch grade {
        case .known:
            seconds = Double(days[step]) * 86_400
            step = min(step + 1, days.count - 1)
        case .fuzzy:
            seconds = 86_400
            step = max(step - 1, 0)
        case .unknown:
            seconds = 600
            step = 0
        }
        dueAt = now.addingTimeInterval(seconds)
        answers.append(ReviewAnswer(at: now, known: grade == .known, bookID: bookID))
        // Reports cover recent weeks, so keep a bounded local history.
        answers = Array(answers.suffix(1000))
    }

    static func decode(_ data: Data?) -> Self {
        data.flatMap { try? JSONDecoder().decode(Self.self, from: $0) } ?? Self()
    }
}

struct ReviewAnswer: Codable, Equatable, Sendable {
    var at: Date
    var known: Bool
    var bookID: UUID? = nil
}

/// Immutable values can be prepared away from SwiftData and the main actor.
struct WordReviewSnapshot: Sendable {
    struct Input: Equatable, Sendable {
        let id: UUID
        let data: Data?
    }
    let states: [UUID: WordReviewState]
    let orderedIDs: [UUID]

    init(inputs: [Input], checkpoint: () throws -> Void = {},
         decode: (Data?) -> WordReviewState = WordReviewState.decode) throws {
        var decoded: [UUID: WordReviewState] = [:]
        decoded.reserveCapacity(inputs.count)
        for input in inputs {
            try checkpoint()
            decoded[input.id] = decode(input.data)
        }
        states = decoded
        orderedIDs = inputs.enumerated().sorted {
            let left = decoded[$0.element.id]!.dueAt
            let right = decoded[$1.element.id]!.dueAt
            return left == right ? $0.offset < $1.offset : left < right
        }.map(\.element.id)
    }

    func dueIDs(at date: Date) -> [UUID] {
        orderedIDs.filter { id in
            guard let state = states[id] else { return false }
            return !state.paused && state.dueAt <= date
        }
    }
}
