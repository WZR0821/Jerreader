import Foundation
import SwiftData

@Model
final class WordLookupRecord: Identifiable {
    @Attribute(.unique) var id: UUID
    @Attribute(.unique) var lookupKey: String
    var surfaceForm: String
    var lemma: String?
    var reading: String?
    var languageRawValue: String
    var partOfSpeech: String?
    var definitionsData: Data
    var inflectionNote: String?
    var examplesData: Data = Data("[]".utf8)
    var usageNote: String?
    var aiAnalysis: String?
    var aiProviderIdentifier: String?
    var sentenceContext: String?
    var sourceBookID: UUID?
    var sourceBookTitle: String?
    var lookupCount: Int
    var createdAt: Date
    var lastLookedUpAt: Date
    var isFavorite: Bool
    var isInHistory: Bool

    var language: LanguageCode {
        LanguageCode(rawValue: languageRawValue) ?? .english
    }

    var definitions: [String] {
        (try? JSONDecoder().decode([String].self, from: definitionsData)) ?? []
    }

    var examples: [WordExample] {
        (try? JSONDecoder().decode([WordExample].self, from: examplesData)) ?? []
    }

    var displayTerm: String {
        lemma?.nilIfBlank ?? surfaceForm
    }

    var copyText: String {
        var lines = [surfaceForm]
        if let lemma = lemma?.nilIfBlank, lemma != surfaceForm {
            lines.append("基本形：\(lemma)")
        }
        if let reading = reading?.nilIfBlank {
            lines.append("读音：\(reading)")
        }
        if let partOfSpeech = partOfSpeech?.nilIfBlank {
            lines.append("词性：\(partOfSpeech)")
        }
        if !definitions.isEmpty {
            lines.append("释义：\(definitions.joined(separator: "；"))")
        }
        if let inflectionNote = inflectionNote?.nilIfBlank {
            lines.append("活用：\(inflectionNote)")
        }
        if let usageNote = usageNote?.nilIfBlank {
            lines.append("用法：\(usageNote)")
        }
        if !examples.isEmpty {
            lines.append("例句：")
            for example in examples {
                lines.append(example.sourceText)
                if let translatedText = example.translatedText?.nilIfBlank {
                    lines.append(translatedText)
                }
            }
        }
        if let aiAnalysis = aiAnalysis?.nilIfBlank {
            lines.append("AI 深度解析：\(aiAnalysis)")
        }
        if let sentenceContext = sentenceContext?.nilIfBlank {
            lines.append("上下文：\(sentenceContext)")
        }
        return lines.joined(separator: "\n")
    }

    init(
        id: UUID = UUID(),
        explanation: WordExplanation,
        sourceBookID: UUID? = nil,
        sourceBookTitle: String? = nil,
        lookupCount: Int = 1,
        createdAt: Date = Date(),
        lastLookedUpAt: Date = Date(),
        isFavorite: Bool = false,
        isInHistory: Bool = true
    ) {
        self.id = id
        lookupKey = Self.makeLookupKey(for: explanation)
        surfaceForm = explanation.surfaceForm
        lemma = explanation.lemma?.nilIfBlank
        reading = explanation.reading?.nilIfBlank
        languageRawValue = explanation.language.rawValue
        partOfSpeech = explanation.partOfSpeech?.nilIfBlank
        definitionsData = Self.encodeDefinitions(explanation.definitions)
        inflectionNote = explanation.inflectionNote?.nilIfBlank
        examplesData = Self.encodeExamples(explanation.examples)
        usageNote = explanation.usageNote?.nilIfBlank
        aiAnalysis = nil
        aiProviderIdentifier = nil
        sentenceContext = explanation.sentenceContext?.nilIfBlank
        self.sourceBookID = sourceBookID
        self.sourceBookTitle = sourceBookTitle?.nilIfBlank
        self.lookupCount = lookupCount
        self.createdAt = createdAt
        self.lastLookedUpAt = lastLookedUpAt
        self.isFavorite = isFavorite
        self.isInHistory = isInHistory
    }

    func recordLookup(
        explanation: WordExplanation,
        sourceBookID: UUID?,
        sourceBookTitle: String?,
        at date: Date
    ) {
        surfaceForm = explanation.surfaceForm
        lemma = explanation.lemma?.nilIfBlank
        reading = explanation.reading?.nilIfBlank
        languageRawValue = explanation.language.rawValue
        partOfSpeech = explanation.partOfSpeech?.nilIfBlank
        definitionsData = Self.encodeDefinitions(explanation.definitions)
        inflectionNote = explanation.inflectionNote?.nilIfBlank
        examplesData = Self.encodeExamples(explanation.examples)
        usageNote = explanation.usageNote?.nilIfBlank
        sentenceContext = explanation.sentenceContext?.nilIfBlank
        self.sourceBookID = sourceBookID
        self.sourceBookTitle = sourceBookTitle?.nilIfBlank

        lookupCount += 1
        lastLookedUpAt = date
        isInHistory = true
    }

    static func makeLookupKey(for explanation: WordExplanation) -> String {
        let canonicalTerm = explanation.lemma?.nilIfBlank ?? explanation.surfaceForm
        let normalizedTerm = canonicalTerm
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .precomposedStringWithCanonicalMapping
            .lowercased(with: Locale(identifier: "en_US_POSIX"))
        return "\(explanation.language.rawValue)|\(normalizedTerm)"
    }

    private static func encodeDefinitions(_ definitions: [String]) -> Data {
        (try? JSONEncoder().encode(definitions)) ?? Data("[]".utf8)
    }

    private static func encodeExamples(_ examples: [WordExample]) -> Data {
        (try? JSONEncoder().encode(examples)) ?? Data("[]".utf8)
    }
}

private extension String {
    var nilIfBlank: String? {
        let normalized = trimmingCharacters(in: .whitespacesAndNewlines)
        return normalized.isEmpty ? nil : normalized
    }
}
