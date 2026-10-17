import Foundation

struct SentencePractice: Codable, Equatable {
    var answer: String
    var prompt: String

    static func make(source: String, answer: String) -> Self? {
        let term = answer.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty, source.contains(term) else { return nil }
        return Self(answer: term, prompt: source.replacingOccurrences(of: term, with: "［____］"))
    }

    static func decode(_ data: Data?) -> Self? {
        data.flatMap { try? JSONDecoder().decode(Self.self, from: $0) }
    }
}
