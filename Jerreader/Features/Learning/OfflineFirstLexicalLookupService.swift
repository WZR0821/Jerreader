import Foundation

struct OfflineFirstLexicalLookupService: LexicalLookupService {
    var fallback: any LexicalLookupService = WiktionaryLexicalLookupService()
    func lookup(word: String, sentenceContext: String?, language: LanguageCode) async throws -> WordExplanation {
        try Task.checkCancellation()
        if language == .japanese, let entry = JapaneseChineseLexicon.shared.japanese(word,
            candidates: WiktionaryLexicalLookupService.lookupCandidates(for: word, language: language)) {
            return WordExplanation(surfaceForm: word, lemma: entry.term, reading: entry.reading,
                language: language, partOfSpeech: entry.partOfSpeech, definitions: [entry.definition],
                inflectionNote: JapaneseChineseLexicon.shared.inflectionNote(word, entry: entry),
                usageNote: entry.attribution + " · 离线释义", sentenceContext: sentenceContext)
        }
        if language == .english {
            let candidates = WiktionaryLexicalLookupService.lookupCandidates(for: word, language: language)
            let entry = await OfflineDictionaryPackageStore.shared.lookup(candidates: candidates)
            try Task.checkCancellation()
            if let entry {
                return WordExplanation(surfaceForm: word, lemma: entry.term, reading: entry.reading,
                    language: language, partOfSpeech: entry.partOfSpeech,
                    definitions: entry.definition.components(separatedBy: .newlines).filter { !$0.isEmpty },
                    usageNote: "离线词库：ECDICT · MIT", sentenceContext: sentenceContext)
            }
        }
        try Task.checkCancellation()
        return try await fallback.lookup(word: word, sentenceContext: sentenceContext, language: language)
    }
}
