import class JerreaderCore.JmdictTsvLexicon
import Foundation

/// Native resource loading around the shared JMdict decoder and matching rules.
private struct JmdictOfflineLexicalLookupService: LexicalLookupService {
    // Kotlin exports immutable classes without a Swift Sendable conformance.
    // The index is built once and exposes read-only lookups, so this shared
    // reference is safe even though Swift cannot prove it across the boundary.
    nonisolated(unsafe) private static let lexicon: JmdictTsvLexicon? = {
        guard let url = Bundle.main.url(forResource: "jmdict-common", withExtension: "tsv"),
              let raw = try? String(contentsOf: url, encoding: .utf8)
        else { return nil }
        return JmdictTsvLexicon.companion.parse(raw: raw)
    }()

    func lookup(
        word: String,
        sentenceContext: String?,
        language: LanguageCode
    ) async throws -> WordExplanation {
        guard language == .japanese, let lexicon = Self.lexicon else {
            throw ServiceError.resultNotFound
        }
        let terms = WiktionaryLexicalLookupService.lookupCandidates(
            for: word,
            language: language
        )
        guard let entry = lexicon.firstEntry(terms: terms) else {
            throw ServiceError.resultNotFound
        }
        return WordExplanation(
            surfaceForm: word.trimmingCharacters(in: .whitespacesAndNewlines),
            lemma: entry.lemma == word ? nil : entry.lemma,
            reading: entry.reading,
            language: language,
            partOfSpeech: entry.partOfSpeech,
            definitions: entry.definitions,
            usageNote: "离线 JMdict 兜底词条，释义为英文；联网时优先显示中文维基词典。",
            sentenceContext: sentenceContext
        )
    }
}

/// Keeps the Chinese dictionary first and only falls back offline on failure.
struct DefaultLexicalLookupService: LexicalLookupService {
    private let remote: any LexicalLookupService
    private let offline: any LexicalLookupService

    init(
        remote: any LexicalLookupService = WiktionaryLexicalLookupService(),
        offline: any LexicalLookupService = JmdictOfflineLexicalLookupService()
    ) {
        self.remote = remote
        self.offline = offline
    }

    func lookup(
        word: String,
        sentenceContext: String?,
        language: LanguageCode
    ) async throws -> WordExplanation {
        do {
            return try await remote.lookup(
                word: word,
                sentenceContext: sentenceContext,
                language: language
            )
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            guard language == .japanese else { throw error }
            do {
                return try await offline.lookup(
                    word: word,
                    sentenceContext: sentenceContext,
                    language: language
                )
            } catch {
                throw error
            }
        }
    }
}
