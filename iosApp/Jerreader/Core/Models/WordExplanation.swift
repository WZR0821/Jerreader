import Foundation

struct WordExample: Codable, Equatable, Sendable {
    let sourceText: String
    let translatedText: String?
    let sourceLabel: String?

    init(
        sourceText: String,
        translatedText: String? = nil,
        sourceLabel: String? = nil
    ) {
        self.sourceText = sourceText
        self.translatedText = translatedText
        self.sourceLabel = sourceLabel
    }
}

struct WordExplanation: Equatable, Sendable {
    let surfaceForm: String
    let lemma: String?
    let reading: String?
    let language: LanguageCode
    let partOfSpeech: String?
    let definitions: [String]
    let inflectionNote: String?
    let examples: [WordExample]
    let usageNote: String?
    let sentenceContext: String?

    init(
        surfaceForm: String,
        lemma: String?,
        reading: String?,
        language: LanguageCode,
        partOfSpeech: String?,
        definitions: [String],
        inflectionNote: String? = nil,
        examples: [WordExample] = [],
        usageNote: String?,
        sentenceContext: String?
    ) {
        self.surfaceForm = surfaceForm
        self.lemma = lemma
        self.reading = reading
        self.language = language
        self.partOfSpeech = partOfSpeech
        self.definitions = definitions
        self.inflectionNote = inflectionNote
        self.examples = examples
        self.usageNote = usageNote
        self.sentenceContext = sentenceContext
    }
}
