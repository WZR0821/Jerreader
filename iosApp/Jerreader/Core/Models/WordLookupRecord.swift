import Foundation
import class JerreaderCore.VocabularyLearningPolicy
import class JerreaderCore.VocabularyStatus
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
    /** Stable core storage id; old favourites default to learning at first access. */
    var vocabularyStatusRawValue: String = "new"
    /** Newest-first, bounded source contexts encoded by the shared core policy. */
    var contextHistoryText: String = ""
    var reviewCount: Int = 0
    var reviewStage: Int = 0
    var reviewIntervalDays: Int = 0
    var reviewLapseCount: Int = 0
    var lastReviewedAt: Date?
    var nextReviewAt: Date?

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

    var vocabularyStatus: VocabularyStatus {
        VocabularyStatus.companion.fromStorageId(id: vocabularyStatusRawValue)
    }

    var contextHistory: [String] {
        VocabularyLearningPolicy.shared.decodeContexts(raw: contextHistoryText)
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
        isInHistory: Bool = true,
        vocabularyStatusRawValue: String? = nil,
        contextHistoryText: String? = nil,
        reviewCount: Int = 0,
        reviewStage: Int = 0,
        reviewIntervalDays: Int = 0,
        reviewLapseCount: Int = 0,
        lastReviewedAt: Date? = nil,
        nextReviewAt: Date? = nil
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
        self.vocabularyStatusRawValue = vocabularyStatusRawValue
            ?? VocabularyLearningPolicy.shared.initialStatus(isFavorite: isFavorite).storageId
        self.contextHistoryText = contextHistoryText
            ?? VocabularyLearningPolicy.shared.encodeContexts(
                contexts: explanation.sentenceContext.map { [$0] } ?? []
            )
        self.reviewCount = reviewCount
        self.reviewStage = reviewStage
        self.reviewIntervalDays = reviewIntervalDays
        self.reviewLapseCount = reviewLapseCount
        self.lastReviewedAt = lastReviewedAt
        self.nextReviewAt = nextReviewAt
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
        if let context = explanation.sentenceContext?.nilIfBlank {
            sentenceContext = context
        }
        contextHistoryText = VocabularyLearningPolicy.shared.encodeContexts(
            contexts: VocabularyLearningPolicy.shared.contextsAfterEncounter(
                existing: contextHistory,
                newContext: explanation.sentenceContext
            )
        )
        if let sourceBookID {
            self.sourceBookID = sourceBookID
        }
        if let sourceBookTitle = sourceBookTitle?.nilIfBlank {
            self.sourceBookTitle = sourceBookTitle
        }

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
