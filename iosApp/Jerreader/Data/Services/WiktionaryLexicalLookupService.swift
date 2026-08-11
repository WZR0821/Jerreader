import Foundation
import NaturalLanguage

protocol DictionaryHTTPClient: Sendable {
    func data(for request: URLRequest) async throws -> Data
}

struct URLSessionDictionaryHTTPClient: DictionaryHTTPClient {
    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func data(for request: URLRequest) async throws -> Data {
        let (data, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse,
              (200 ... 299).contains(response.statusCode)
        else {
            throw ServiceError.temporarilyUnavailable
        }
        return data
    }
}

/// A real, key-free dictionary adapter backed by Chinese Wiktionary.
///
/// The UI only receives `WordExplanation`; MediaWiki response types stay in
/// this file so changing providers does not leak into views or persistence.
struct WiktionaryLexicalLookupService: LexicalLookupService {
    private let client: any DictionaryHTTPClient

    init(client: any DictionaryHTTPClient = URLSessionDictionaryHTTPClient()) {
        self.client = client
    }

    func lookup(
        word: String,
        sentenceContext: String?,
        language: LanguageCode
    ) async throws -> WordExplanation {
        let normalizedWord = Self.normalizedLookupTerm(word)
        guard !normalizedWord.isEmpty else {
            throw ServiceError.emptyText
        }
        guard normalizedWord.count <= 80 else {
            throw ServiceError.resultNotFound
        }
        guard language == .japanese || language == .english else {
            throw ServiceError.unsupportedLanguage
        }

        var receivedValidResponse = false
        for candidate in Self.lookupCandidates(for: normalizedWord, language: language) {
            try Task.checkCancellation()

            do {
                let data = try await client.data(for: try Self.request(for: candidate))
                receivedValidResponse = true
                guard let page = try Self.decodePage(from: data),
                      let parsed = WiktionaryEntryParser.parse(
                          wikitext: page.wikitext,
                          language: language
                      )
                else {
                    continue
                }

                let inflectionNote = language == .japanese && candidate != normalizedWord
                    ? JapaneseMorphologyAnalyzer.inflectionNote(
                        for: normalizedWord,
                        resolvedLemma: candidate
                    )
                    : nil
                var examples = parsed.examples
                if let context = sentenceContext?
                    .trimmingCharacters(in: .whitespacesAndNewlines),
                   !context.isEmpty,
                   !examples.contains(where: { $0.sourceText == context })
                {
                    examples.insert(
                        WordExample(
                            sourceText: context,
                            sourceLabel: "书中原句"
                        ),
                        at: 0
                    )
                }
                return WordExplanation(
                    surfaceForm: normalizedWord,
                    lemma: candidate == normalizedWord ? nil : candidate,
                    reading: parsed.reading
                        ?? JapaneseMorphologyAnalyzer.kanaReadingFallback(
                            for: normalizedWord,
                            language: language
                        ),
                    language: language,
                    partOfSpeech: parsed.partOfSpeech,
                    definitions: parsed.definitions,
                    inflectionNote: inflectionNote,
                    examples: Array(examples.prefix(3)),
                    usageNote: "释义来自中文维基词典，请结合原句选择符合语境的义项。",
                    sentenceContext: sentenceContext
                )
            } catch is CancellationError {
                throw CancellationError()
            } catch let error as ServiceError {
                if error == .temporarilyUnavailable {
                    throw error
                }
            } catch {
                throw ServiceError.temporarilyUnavailable
            }
        }

        throw receivedValidResponse ? ServiceError.resultNotFound : ServiceError.temporarilyUnavailable
    }

    static func normalizedLookupTerm(_ word: String) -> String {
        word
            .trimmingCharacters(in: .whitespacesAndNewlines.union(.punctuationCharacters))
            .precomposedStringWithCanonicalMapping
    }

    static func lookupCandidates(
        for word: String,
        language: LanguageCode
    ) -> [String] {
        var candidates = [word]

        switch language {
        case .english:
            if let lemma = englishLemma(for: word), lemma != word.lowercased() {
                candidates.append(lemma)
            }
            candidates.append(contentsOf: englishFallbackCandidates(for: word))
        case .japanese:
            candidates.append(
                contentsOf: JapaneseMorphologyAnalyzer.candidates(for: word)
            )
        case .simplifiedChinese:
            break
        }

        var seen: Set<String> = []
        return candidates.filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    private static func request(for title: String) throws -> URLRequest {
        var components = URLComponents()
        components.scheme = "https"
        components.host = "zh.wiktionary.org"
        components.path = "/w/api.php"
        components.queryItems = [
            URLQueryItem(name: "action", value: "parse"),
            URLQueryItem(name: "page", value: title),
            URLQueryItem(name: "prop", value: "wikitext"),
            URLQueryItem(name: "format", value: "json"),
            URLQueryItem(name: "formatversion", value: "2"),
        ]

        guard let url = components.url else {
            throw ServiceError.temporarilyUnavailable
        }
        var request = URLRequest(url: url)
        request.timeoutInterval = 12
        request.cachePolicy = .returnCacheDataElseLoad
        request.setValue(
            "Jerreader/3.6 (iOS dictionary lookup; https://zh.wiktionary.org)",
            forHTTPHeaderField: "User-Agent"
        )
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        return request
    }

    private static func decodePage(from data: Data) throws -> WiktionaryPage? {
        let response = try JSONDecoder().decode(WiktionaryAPIResponse.self, from: data)
        return response.parse
    }

    private static func englishLemma(for word: String) -> String? {
        let normalized = word.lowercased(with: Locale(identifier: "en_US_POSIX"))
        guard let range = normalized.rangeOfCharacter(from: .letters) else { return nil }

        let tagger = NLTagger(tagSchemes: [.lemma])
        tagger.string = normalized
        tagger.setLanguage(.english, range: normalized.startIndex ..< normalized.endIndex)
        return tagger.tag(at: range.lowerBound, unit: .word, scheme: .lemma).0?.rawValue
    }

    private static func englishFallbackCandidates(for word: String) -> [String] {
        let word = word.lowercased(with: Locale(identifier: "en_US_POSIX"))
        let irregular: [String: String] = [
            "went": "go", "gone": "go", "was": "be", "were": "be",
            "did": "do", "done": "do", "had": "have", "made": "make",
            "took": "take", "taken": "take", "saw": "see", "seen": "see",
            "came": "come", "written": "write", "wrote": "write",
        ]

        var candidates: [String] = []
        if let irregular = irregular[word] {
            candidates.append(irregular)
        }
        if word.hasSuffix("ies"), word.count > 3 {
            candidates.append(String(word.dropLast(3)) + "y")
        }
        if word.hasSuffix("es"), word.count > 2 {
            candidates.append(String(word.dropLast(2)))
        }
        if word.hasSuffix("s"), word.count > 1 {
            candidates.append(String(word.dropLast()))
        }
        if word.hasSuffix("ied"), word.count > 3 {
            candidates.append(String(word.dropLast(3)) + "y")
        }
        if word.hasSuffix("ed"), word.count > 2 {
            let stem = String(word.dropLast(2))
            candidates.append(stem)
            candidates.append(stem + "e")
        }
        if word.hasSuffix("ing"), word.count > 3 {
            let stem = String(word.dropLast(3))
            candidates.append(stem)
            candidates.append(stem + "e")
        }
        return candidates
    }

}

struct WiktionaryParsedEntry: Equatable, Sendable {
    let reading: String?
    let partOfSpeech: String?
    let definitions: [String]
    let examples: [WordExample]
}

enum WiktionaryEntryParser {
    static func parse(
        wikitext: String,
        language: LanguageCode
    ) -> WiktionaryParsedEntry? {
        let acceptedLanguageHeadings: Set<String> = {
            switch language {
            case .japanese: return ["日语", "日語"]
            case .english: return ["英语", "英語"]
            case .simplifiedChinese: return []
            }
        }()

        var isInRequestedLanguage = false
        var currentPartOfSpeech: String?
        var resultPartOfSpeech: String?
        var reading: String?
        var definitions: [String] = []
        var examples: [WordExample] = []

        for rawLine in wikitext.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = String(rawLine).trimmingCharacters(in: .whitespacesAndNewlines)

            if let heading = heading(from: line) {
                if heading.level == 2 {
                    if isInRequestedLanguage {
                        break
                    }
                    isInRequestedLanguage = acceptedLanguageHeadings.contains(
                        heading.title.replacingOccurrences(of: " ", with: "")
                    )
                    currentPartOfSpeech = nil
                } else if isInRequestedLanguage {
                    currentPartOfSpeech = canonicalPartOfSpeech(heading.title)
                }
                continue
            }

            guard isInRequestedLanguage else { continue }

            if reading == nil {
                reading = extractReading(from: line, language: language)
            }

            if line.hasPrefix("#:") || line.hasPrefix("#*") {
                if let example = extractExample(from: line, language: language),
                   !examples.contains(example),
                   examples.count < 3
                {
                    examples.append(example)
                }
                continue
            }

            guard line.hasPrefix("#"),
                  !line.hasPrefix("##")
            else { continue }

            let definition = cleanWikiMarkup(String(line.dropFirst()))
            guard !definition.isEmpty,
                  !definitions.contains(definition)
            else { continue }

            if definitions.count < 3 {
                definitions.append(definition)
                if resultPartOfSpeech == nil {
                    resultPartOfSpeech = currentPartOfSpeech
                }
            }
        }

        guard !definitions.isEmpty else { return nil }
        return WiktionaryParsedEntry(
            reading: reading,
            partOfSpeech: resultPartOfSpeech,
            definitions: definitions,
            examples: examples
        )
    }

    private static func extractExample(
        from line: String,
        language: LanguageCode
    ) -> WordExample? {
        let body = String(line.dropFirst(2))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if language == .japanese,
           let templateStart = body.range(of: "{{ja-usex|"),
           let templateEnd = body.range(
               of: "}}",
               range: templateStart.upperBound ..< body.endIndex
           )
        {
            let arguments = body[templateStart.upperBound ..< templateEnd.lowerBound]
                .split(separator: "|", omittingEmptySubsequences: false)
                .map {
                    cleanWikiMarkup(String($0))
                        .trimmingCharacters(in: .whitespacesAndNewlines)
                }
            guard let source = arguments.first, !source.isEmpty else { return nil }
            let translation = arguments.count > 2
                ? arguments[2].nilIfEmpty
                : nil
            return WordExample(
                sourceText: source,
                translatedText: translation,
                sourceLabel: "词典例句"
            )
        }

        let cleaned = cleanWikiMarkup(body)
        guard !cleaned.isEmpty else { return nil }
        return WordExample(sourceText: cleaned, sourceLabel: "词典例句")
    }

    private static func heading(from line: String) -> (level: Int, title: String)? {
        let leading = line.prefix { $0 == "=" }.count
        let trailing = line.reversed().prefix { $0 == "=" }.count
        guard (2 ... 6).contains(leading), leading == trailing else { return nil }
        let start = line.index(line.startIndex, offsetBy: leading)
        let end = line.index(line.endIndex, offsetBy: -trailing)
        return (
            leading,
            line[start ..< end].trimmingCharacters(in: .whitespacesAndNewlines)
        )
    }

    private static func canonicalPartOfSpeech(_ heading: String) -> String? {
        let heading = heading.lowercased()
        let mappings: [(tokens: [String], label: String)] = [
            (["动词", "動詞", "verb"], "动词"),
            (["名词", "名詞", "noun"], "名词"),
            (["形容词", "形容詞", "adjective"], "形容词"),
            (["副词", "副詞", "adverb"], "副词"),
            (["介词", "介詞", "preposition"], "介词"),
            (["助词", "助詞", "particle"], "助词"),
            (["代词", "代詞", "pronoun"], "代词"),
            (["连词", "連詞", "conjunction"], "连词"),
            (["感叹词", "感嘆詞", "interjection"], "感叹词"),
        ]
        return mappings.first { mapping in
            mapping.tokens.contains { heading.contains($0) }
        }?.label
    }

    private static func extractReading(
        from line: String,
        language: LanguageCode
    ) -> String? {
        let pattern: String
        switch language {
        case .japanese:
            pattern = #"\{\{ja-pron\|([^|}]+)"#
        case .english:
            pattern = #"\{\{IPA\|en\|([^|}]+)"#
        case .simplifiedChinese:
            return nil
        }
        return firstCapture(in: line, pattern: pattern)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func cleanWikiMarkup(_ value: String) -> String {
        var value = removingBalancedTemplates(from: value)
        value = replacingRegex(
            #"\[\[([^\]|]+)\|([^\]]+)\]\]"#,
            in: value,
            with: "$2"
        )
        value = replacingRegex(#"\[\[([^\]]+)\]\]"#, in: value, with: "$1")
        value = replacingRegex(#"<!--.*?-->"#, in: value, with: "")
        value = replacingRegex(#"<[^>]+>"#, in: value, with: "")
        value = value
            .replacingOccurrences(of: "'''", with: "")
            .replacingOccurrences(of: "''", with: "")
            .replacingOccurrences(of: "-{", with: "")
            .replacingOccurrences(of: "}-", with: "")
            .replacingOccurrences(of: "&nbsp;", with: " ")
            .replacingOccurrences(of: "&amp;", with: "&")
        value = replacingRegex(#"\s+"#, in: value, with: " ")
        return value.trimmingCharacters(
            in: .whitespacesAndNewlines.union(CharacterSet(charactersIn: "；;,，"))
        )
    }

    private static func removingBalancedTemplates(from input: String) -> String {
        var output = input

        while let start = output.range(of: "{{")?.lowerBound {
            var cursor = start
            var depth = 0
            var end: String.Index?

            while cursor < output.endIndex {
                if output[cursor...].hasPrefix("{{") {
                    depth += 1
                    cursor = output.index(cursor, offsetBy: 2)
                    continue
                }
                if output[cursor...].hasPrefix("}}") {
                    depth -= 1
                    cursor = output.index(cursor, offsetBy: 2)
                    if depth == 0 {
                        end = cursor
                        break
                    }
                    continue
                }
                cursor = output.index(after: cursor)
            }

            guard let end else {
                output.removeSubrange(start ..< output.endIndex)
                break
            }
            output.removeSubrange(start ..< end)
        }

        return output
    }

    private static func firstCapture(in text: String, pattern: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(
                  in: text,
                  range: NSRange(text.startIndex..., in: text)
              ),
              let range = Range(match.range(at: 1), in: text)
        else { return nil }
        return String(text[range])
    }

    private static func replacingRegex(
        _ pattern: String,
        in text: String,
        with replacement: String
    ) -> String {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return text }
        return regex.stringByReplacingMatches(
            in: text,
            range: NSRange(text.startIndex..., in: text),
            withTemplate: replacement
        )
    }
}

enum JapaneseMorphologyAnalyzer {
    static func candidates(for word: String) -> [String] {
        var candidates: [String] = []

        for suffix in ["ませんでした", "ません", "ました", "ます"] {
            guard let stem = word.removingSuffix(suffix), !stem.isEmpty else { continue }
            if let godan = godanDictionaryForm(fromPoliteStem: stem) {
                candidates.append(godan)
            }
            candidates.append(stem + "る")
            return unique(candidates)
        }

        if let stem = word.removingSuffix("ている"), !stem.isEmpty {
            candidates.append(stem + "る")
        }
        if let stem = word.removingSuffix("ない"), !stem.isEmpty {
            if let godan = godanDictionaryForm(fromPoliteStem: stem) {
                candidates.append(godan)
            }
            candidates.append(stem + "る")
        }
        if let stem = word.removingSuffix("かった"), !stem.isEmpty {
            candidates.append(stem + "い")
        }
        if let stem = word.removingSuffix("した"), !stem.isEmpty {
            candidates.append(stem + "す")
        }
        if let stem = word.removingSuffix("いた"), !stem.isEmpty {
            candidates.append(stem + "く")
        }
        if let stem = word.removingSuffix("いだ"), !stem.isEmpty {
            candidates.append(stem + "ぐ")
        }
        if let stem = word.removingSuffix("んだ"), !stem.isEmpty {
            candidates.append(contentsOf: [stem + "む", stem + "ぶ", stem + "ぬ"])
        }
        if let stem = word.removingSuffix("った"), !stem.isEmpty {
            candidates.append(contentsOf: [stem + "う", stem + "つ", stem + "る"])
        }
        if let stem = word.removingSuffix("た"), !stem.isEmpty {
            candidates.append(stem + "る")
        }
        return unique(candidates)
    }

    static func inflectionNote(
        for surfaceForm: String,
        resolvedLemma: String
    ) -> String? {
        let description: String?
        if surfaceForm.hasSuffix("ませんでした") {
            description = "「〜ませんでした」礼貌体的否定过去式"
        } else if surfaceForm.hasSuffix("ません") {
            description = "「〜ません」礼貌体的否定式"
        } else if surfaceForm.hasSuffix("ました") {
            description = "「〜ました」礼貌体的过去式"
        } else if surfaceForm.hasSuffix("ます") {
            description = "「〜ます」礼貌体的非过去式"
        } else if surfaceForm.hasSuffix("ている") {
            description = "「〜ている」表示动作进行、反复或结果状态"
        } else if surfaceForm.hasSuffix("ない") {
            description = "未然形接「ない」构成否定式"
        } else if surfaceForm.hasSuffix("かった") {
            description = "い形容词的过去式"
        } else if surfaceForm.hasSuffix("た")
            || surfaceForm.hasSuffix("だ")
        {
            description = "动词的「た形」，通常表示过去或完成"
        } else {
            description = nil
        }
        guard let description else { return nil }
        return "\(description)；词典形为「\(resolvedLemma)」。"
    }

    static func kanaReadingFallback(
        for word: String,
        language: LanguageCode
    ) -> String? {
        guard language == .japanese,
              !word.isEmpty,
              word.unicodeScalars.allSatisfy({ scalar in
                  (0x3040 ... 0x30FF).contains(Int(scalar.value))
                      || scalar.value == 0x30FC
              })
        else { return nil }
        return word
    }

    private static func godanDictionaryForm(fromPoliteStem stem: String) -> String? {
        guard let last = stem.last else { return nil }
        let mapping: [Character: Character] = [
            "い": "う", "き": "く", "ぎ": "ぐ", "し": "す",
            "ち": "つ", "に": "ぬ", "び": "ぶ", "み": "む", "り": "る",
        ]
        guard let ending = mapping[last] else { return nil }
        return String(stem.dropLast()) + String(ending)
    }

    private static func unique(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values.filter { !$0.isEmpty && seen.insert($0).inserted }
    }
}

private struct WiktionaryAPIResponse: Decodable {
    let parse: WiktionaryPage?
}

private struct WiktionaryPage: Decodable {
    let title: String
    let wikitext: String
}

private extension String {
    func removingSuffix(_ suffix: String) -> String? {
        guard hasSuffix(suffix) else { return nil }
        return String(dropLast(suffix.count))
    }

    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
