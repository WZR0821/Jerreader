import Foundation

struct MockLexicalLookupService: LexicalLookupService {
    func lookup(
        word: String,
        sentenceContext: String?,
        language: LanguageCode
    ) async throws -> WordExplanation {
        let normalizedWord = word.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedWord.isEmpty else {
            throw ServiceError.emptyText
        }

        switch (normalizedWord.lowercased(), language) {
        case ("食べました", .japanese):
            return WordExplanation(
                surfaceForm: normalizedWord,
                lemma: "食べる",
                reading: "たべました",
                language: .japanese,
                partOfSpeech: "动词",
                definitions: ["吃", "食用"],
                inflectionNote: "「〜ました」礼貌体的过去式；词典形为「食べる」。",
                examples: [
                    WordExample(
                        sourceText: "昨日、家でご飯を食べました。",
                        translatedText: "昨天在家吃了饭。",
                        sourceLabel: "Mock 例句"
                    ),
                ],
                usageNote: "礼貌体过去式，表示已经吃过或完成了进食动作。",
                sentenceContext: sentenceContext
            )
        case ("went", .english):
            return WordExplanation(
                surfaceForm: normalizedWord,
                lemma: "go",
                reading: nil,
                language: .english,
                partOfSpeech: "动词",
                definitions: ["去", "前往"],
                usageNote: "go 的过去式，表示已经发生的移动。",
                sentenceContext: sentenceContext
            )
        default:
            return WordExplanation(
                surfaceForm: normalizedWord,
                lemma: nil,
                reading: nil,
                language: language,
                partOfSpeech: nil,
                definitions: ["Mock 释义：用于离线开发与界面测试。"],
                usageNote: "接入正式词典后将显示可靠的基本形、读音与语境说明。",
                sentenceContext: sentenceContext
            )
        }
    }
}
