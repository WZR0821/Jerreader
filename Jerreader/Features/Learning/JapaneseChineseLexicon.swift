import Foundation

/// Shared, attributed dictionary data. Exact indexes keep large bundled packs
/// out of per-query scans; Chinese keys never use substring matches in prose.
struct JapaneseChineseLexicon: Sendable {
    struct Entry: Sendable {
        let term: String
        let reading: String
        let partOfSpeech: String
        let definition: String
        let aliases: [String]
        let forms: [String]
        let sourcePage: String
        var display: String { reading.isEmpty ? term : "\(term)（\(reading)）" }
        var attribution: String { sourcePage.isEmpty ? "Jerreader 基础词条" : "中文维基词典贡献者 · CC BY-SA 4.0" }
        var sourceURL: URL? {
            guard !sourcePage.isEmpty else { return nil }
            var url = URLComponents(string: "https://zh.wiktionary.org")!
            url.path = "/wiki/\(sourcePage)"
            url.fragment = "日語"
            return url.url
        }
    }

    static let version = "2026-09-01"
    static let sourceURL = URL(string: "https://kaikki.org/zhwiktionary/日语/")!
    static let licenseURL = URL(string: "https://creativecommons.org/licenses/by-sa/4.0/")!
    static let notice = bundled("ja-zh-NOTICE", extension: "txt")
    static let shared = JapaneseChineseLexicon(tsv: bundled("ja-zh-core"), supplement: bundled("ja-zh-wiktionary"))
    private static func bundled(_ name: String, extension ext: String = "tsv") -> String {
        Bundle.main.url(forResource: name, withExtension: ext)
            .flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? ""
    }
    let entries: [Entry]
    let headwordCount: Int
    private let heads: [String: [Int]]
    private let readings: [String: [Int]]
    private let inflections: [String: [Int]]
    private let reverse: [String: [Int]]
    /// Verbs the dictionary marks 一段; a form only a godan verb has never
    /// names one (いらない is not いる, きった is not 着る).
    private let ichidanTerms: Set<String>

    init(tsv: String, supplement: String = "") {
        let parsed = (tsv + "\n" + supplement).split(whereSeparator: \.isNewline).compactMap { line -> Entry? in
            guard !line.hasPrefix("#") else { return nil }
            let fields = line.components(separatedBy: "\t")
            guard [5, 7].contains(fields.count), !fields[0].isEmpty, !fields[3].isEmpty else { return nil }
            return Entry(term: fields[0], reading: fields[1], partOfSpeech: fields[2], definition: fields[3],
                aliases: fields[4].split(separator: "|").map(String.init),
                forms: fields.count == 7 ? fields[5].split(separator: "|").map(String.init) : [],
                sourcePage: fields.count == 7 ? fields[6] : "")
        }
        entries = parsed
        headwordCount = Set(parsed.map(\.term)).count
        var h: [String: [Int]] = [:], r: [String: [Int]] = [:], f: [String: [Int]] = [:], c: [String: [Int]] = [:]
        for (index, entry) in parsed.enumerated() {
            for value in Set([entry.term, entry.display]) { h[Self.normalized(value), default: []].append(index) }
            for value in entry.reading.split(separator: "／") { r[Self.normalized(String(value)), default: []].append(index) }
            // A kanji headword with no reading cannot conjugate into kana
            // alone; such rows carry する's own forms (安居 listed した).
            let kanaFormsFit = entry.reading.isEmpty == false || !Self.containsKanji(entry.term)
            for value in entry.forms where kanaFormsFit || Self.containsKanji(value) {
                f[Self.normalized(value), default: []].append(index)
            }
            for value in entry.aliases { c[Self.normalized(value), default: []].append(index) }
        }
        heads = h; readings = r; inflections = f; reverse = c
        ichidanTerms = Set(parsed.filter { $0.partOfSpeech.contains("一段") }.map(\.term))
    }

    /// Whether a candidate may name this entry: a form only a godan verb has
    /// never names a 一段 verb.
    private func fits(_ index: Int, godanOnly: Bool) -> Bool {
        !(godanOnly && ichidanTerms.contains(entries[index].term))
    }

    private static func containsKanji(_ value: String) -> Bool {
        value.unicodeScalars.contains { (0x3400 ... 0x9FFF).contains($0.value) }
    }

    private static func normalized(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines).precomposedStringWithCompatibilityMapping
    }

    func japanese(_ word: String, candidates: [String] = []) -> Entry? {
        let local = matches(word)
        if !local.isEmpty { return Set(local.map(\.term)).count == 1 ? local.first : nil }
        for candidate in candidates {
            let values = matches(candidate)
            if Set(values.map(\.term)).count == 1 { return values.first }
        }
        return derived(Self.normalized(word))?.entry
    }

    func matches(_ word: String, chinese: Bool = false) -> [Entry] {
        matchIndices(word, chinese: chinese).map { entries[$0] }
    }

    private func matchIndices(_ word: String, chinese: Bool = false) -> [Int] {
        let key = Self.normalized(word)
        if chinese { return Array((reverse[key] ?? []).prefix(20)) }
        if let exact = heads[key] {
            // NFKC folds 🈳 into 空; the headword as written wins.
            let literal = word.trimmingCharacters(in: .whitespacesAndNewlines)
            let written = exact.filter { entries[$0].term == literal || entries[$0].display == literal }
            let chosen = written.isEmpty ? exact : written
            // A kana word the core lists under kanji may be that word, not
            // the supplement's kana headword (いく is 行く or 生く, とき is 時
            // or 鴇): both stay choices, the core's first. A particle keeps
            // its own entry (かな).
            if Self.isHiraganaWord(key), chosen.allSatisfy({
                !entries[$0].sourcePage.isEmpty && !entries[$0].partOfSpeech.contains("助词")
            }) {
                let core = (readings[key] ?? []).filter { entries[$0].sourcePage.isEmpty }
                if !core.isEmpty { return Array((core + chosen).prefix(20)) }
            }
            return Array(chosen.prefix(20))
        }
        // A lone hiragana that is no headword is a particle or an ending,
        // never a reading (で is not 出, ね is not 根).
        if Self.isLoneHiragana(key) { return [] }
        var indices = inflections[key] ?? readings[key] ?? []
        // A potential reading only counts when no other rule finds a
        // headword (入れて is 入れる, not 入る's potential).
        var plain: [Int] = [], potential: [Int] = []
        for candidate in JapaneseLookupInflection.candidates(key) {
            let found = (heads[candidate.lemma] ?? []).filter {
                Self.inflects(entries[$0]) && fits($0, godanOnly: candidate.godanOnly)
            }
            if candidate.note.hasPrefix("可能形候选") { potential += found } else { plain += found }
        }
        indices += plain.isEmpty ? potential : plain
        // A kana word may be a verb form the core rules leave out, next to
        // a word that reads the same (きた is 北 or 来る, くれ is 呉 or
        // くれる); both stay choices.
        if !JapaneseLookupInflection.hasKanjiOrKatakana(key), plain.isEmpty, potential.isEmpty {
            for candidate in JapaneseLookupInflection.allCandidates(key) {
                indices += (heads[candidate.lemma] ?? []).filter {
                    Self.inflects(entries[$0]) && fits($0, godanOnly: candidate.godanOnly)
                }
            }
        }
        var seen = Set<Int>()
        return Array(indices.filter { seen.insert($0).inserted }.prefix(20))
    }

    func inflectionNote(_ word: String, entry: Entry) -> String? {
        let key = Self.normalized(word)
        if let note = JapaneseLookupInflection.note(key, lemma: entry.term) { return note }
        // A する verb listed under its noun (散歩した is 散歩 + する).
        if let note = JapaneseLookupInflection.note(key, lemma: entry.term + "する") { return note }
        if !JapaneseLookupInflection.hasKanjiOrKatakana(key),
           let candidate = JapaneseLookupInflection.allCandidates(key).first(where: { $0.lemma == entry.term }) {
            return "词典形「\(entry.term)」；\(candidate.note)。规则分析候选，请结合原句确认。"
        }
        guard matches(key).isEmpty, let derived = derived(key), derived.entry.term == entry.term else { return nil }
        return derived.note
    }

    /// A whole word a short tap selects may not be in the dictionary as it
    /// stands: a する verb (勉強しました → 勉強), a form two rules from its
    /// headword (読んでいました → 読む), a desire, conditional or volitional
    /// form (食べたい → 食べる), a name or plural suffix (子供たち → 子供) or an
    /// honorific prefix (ご主人 → 主人). Only an answer with one headword
    /// counts, and only after the word itself and its candidates found none.
    private func derived(_ key: String) -> (entry: Entry, note: String)? {
        guard !Self.isLoneHiragana(key) else { return nil }
        if let found = resolve(key, prefix: true, suffix: true, exact: false) {
            return (entries[found.index], found.notes.joined(separator: "；") + "。规则分析候选，请结合原句确认。")
        }
        return compoundVerb(key)
    }

    private func resolve(_ key: String, prefix: Bool, suffix: Bool, exact: Bool) -> (index: Int, notes: [String])? {
        if exact {
            let found = matchIndices(key)
            if let first = found.first {
                guard Set(found.map { entries[$0].term }).count == 1 else { return nil }
                let note = JapaneseLookupInflection.candidates(key).first { $0.lemma == entries[first].term }
                return (first, note.map { ["词典形「\(entries[first].term)」", $0.note] } ?? [])
            }
        }
        // A set phrase in the past (すみませんでした).
        if key.hasSuffix("ませんでした"), let phrase = heads[String(key.dropLast(3))],
           Set(phrase.map { entries[$0].term }).count == 1 {
            return (phrase[0], ["「\(entries[phrase[0]].term)」＋过去「でした」"])
        }
        if let found = inflected(key) { return found }
        if suffix {
            for (ending, kind) in JapaneseLookupInflection.nounSuffixes where key.hasSuffix(ending) && key.count > ending.count {
                let noun = String(key.dropLast(ending.count))
                guard JapaneseLookupInflection.hasKanjiOrKatakana(noun), (ending != "か" || ["何", "誰"].contains(noun)) else { continue }
                // さま after お or ご is polite, not a name (お客様).
                let honorific = ["さま", "様"].contains(ending) && ["お", "ご", "御"].contains(where: { noun.hasPrefix($0) })
                let label = honorific ? "敬语后缀" : kind
                if kind == "称呼后缀", let named = (heads[noun] ?? []).first(where: { entries[$0].partOfSpeech.contains("专有") }) {
                    return (named, ["\(label)「\(ending)」"])
                }
                if let found = resolve(noun, prefix: prefix, suffix: false, exact: true) {
                    return (found.index, found.notes + ["\(label)「\(ending)」"])
                }
            }
        }
        if prefix {
            for honorific in ["お", "ご", "御"] where key.hasPrefix(honorific) {
                let rest = String(key.dropFirst(honorific.count))
                guard rest.unicodeScalars.first.map({ (0x3400 ... 0x9FFF).contains($0.value) }) ?? false,
                      let found = resolve(rest, prefix: false, suffix: suffix, exact: true) else { continue }
                return (found.index, ["美化语前缀「\(honorific)」"] + found.notes)
            }
        }
        return nil
    }

    /// A verb or adjective one or two rules from `key`, the forms listed
    /// under a headword, or the noun of a する verb.
    private func inflected(_ key: String) -> (index: Int, notes: [String])? {
        let first = JapaneseLookupInflection.allCandidates(key)
        let direct = first.map { (lemma: $0.lemma, note: $0.note, godanOnly: $0.godanOnly) }
        // A volitional form never inflects again (聞こえて is no form of 聞こう).
        // Nor does a form only a godan verb has: a rule's verb in る is a
        // 一段 one (開ける, 読ませる), so 開けった is no form of 開く. The
        // inner rule names the lemma, so its godan-only mark is the one kept.
        let twice = first.filter { !$0.godanOnly }.flatMap { outer in
            JapaneseLookupInflection.allCandidates(outer.lemma).filter { !$0.note.contains("意志") }
                .map { (lemma: $0.lemma, note: $0.note + " · " + outer.note, godanOnly: $0.godanOnly) }
        }
        // A potential reading only counts when nothing else does
        // (しれない is 知れる, not 知る's potential).
        func potential(_ path: (lemma: String, note: String, godanOnly: Bool)) -> Bool { path.note.contains("可能形") }
        let levels = [direct.filter { !potential($0) }, direct.filter(potential),
                      twice.filter { !potential($0) }, twice.filter(potential)]
        for paths in levels where !paths.isEmpty {
            // Headwords first, then forms listed under a headword, then the
            // reading of a kana lemma (しれる → 知れる); a later source never
            // turns an answer into a tie.
            for source in 0 ..< 3 {
                var found: [(index: Int, notes: [String])] = []
                // The noun of a する verb only counts when no verb does
                // (話していました is 話す, not 話 + する).
                var nouns: [(index: Int, notes: [String])] = []
                for path in paths {
                    switch source {
                    case 0:
                        for index in heads[path.lemma] ?? []
                        where Self.inflects(entries[index]) && fits(index, godanOnly: path.godanOnly) {
                            found.append((index, ["词典形「\(path.lemma)」", path.note]))
                        }
                        if path.lemma.hasSuffix("する") {
                            let noun = String(path.lemma.dropLast(2))
                            if JapaneseLookupInflection.hasKanjiOrKatakana(noun) {
                                for index in heads[noun] ?? [] {
                                    nouns.append((index, ["词典形「\(path.lemma)」", path.note]))
                                }
                            }
                        }
                    case 1:
                        // A potential reading names a headword itself, never
                        // a form listed under another one (聞こう under 聞く),
                        // and a 一段 verb has no godan form (入れった).
                        guard !potential(path), !(path.godanOnly && ichidanTerms.contains(path.lemma)) else { continue }
                        for index in inflections[path.lemma] ?? [] where fits(index, godanOnly: path.godanOnly) {
                            found.append((index, ["词典形「\(entries[index].term)」", "经由「\(path.lemma)」", path.note]))
                        }
                    default:
                        guard !JapaneseLookupInflection.hasKanjiOrKatakana(path.lemma) else { continue }
                        for index in readings[path.lemma] ?? []
                        where Self.inflects(entries[index]) && fits(index, godanOnly: path.godanOnly) {
                            found.append((index, ["词典形「\(entries[index].term)」", path.note]))
                        }
                    }
                }
                if found.isEmpty { found = nouns }
                if !found.isEmpty {
                    return Set(found.map { entries[$0.index].term }).count == 1 ? found[0] : nil
                }
            }
        }
        return nil
    }

    /// A compound verb a short tap selects whole when the dictionary lacks
    /// it (走り去った, 読み終わった): the first verb's entry, with the second
    /// verb and its form in the note.
    private func compoundVerb(_ key: String) -> (entry: Entry, note: String)? {
        let scalars = Array(key.unicodeScalars)
        guard let stemEnd = scalars.firstIndex(where: { !Self.isKanji($0) }), stemEnd > 0,
              stemEnd + 1 < scalars.count, Self.isKanji(scalars[stemEnd + 1]) else { return nil }
        var first = String.UnicodeScalarView(), second = String.UnicodeScalarView()
        first.append(contentsOf: scalars[...stemEnd])
        second.append(contentsOf: scalars[(stemEnd + 1)...])
        guard let head = inflected(String(first)), head.notes.contains("连用形"),
              let tail = resolve(String(second), prefix: false, suffix: false, exact: true),
              entries[tail.index].partOfSpeech.split(separator: "／").contains(where: {
                  $0.hasSuffix("动词") && !$0.hasPrefix("形容")
              }) else { return nil }
        let notes = ["复合动词：前项「\(entries[head.index].term)」＋后项「\(entries[tail.index].term)」"]
            + tail.notes.filter { !$0.hasPrefix("词典形") }
        return (entries[head.index], notes.joined(separator: "；") + "。规则分析候选，请结合原句确认。")
    }

    private static func isKanji(_ scalar: Unicode.Scalar) -> Bool { (0x3400 ... 0x9FFF).contains(scalar.value) }

    private static func isLoneHiragana(_ key: String) -> Bool {
        key.unicodeScalars.count == 1 && (0x3040 ... 0x309F).contains(key.unicodeScalars.first!.value)
    }

    private static func isHiraganaWord(_ key: String) -> Bool {
        key.unicodeScalars.count >= 2 && key.unicodeScalars.allSatisfy { (0x3040 ... 0x309F).contains($0.value) }
    }

    private static func inflects(_ entry: Entry) -> Bool {
        let pos = entry.partOfSpeech
        return pos.contains("动") || pos.contains("動") || pos.contains("形容")
    }

    func chinese(_ word: String) -> String? {
        let values = matches(word, chinese: true)
        let preferred = values.filter { $0.sourcePage.isEmpty }
        var seen = Set<String>()
        let displays = (preferred.isEmpty ? values : preferred).map(\.display).filter { seen.insert($0).inserted }
        return displays.isEmpty ? nil : displays.joined(separator: "；")
    }
}

/// Bounded, dictionary-validated candidates; never a whole-sentence translator.
enum JapaneseLookupInflection {
    /// `godanOnly`: the form only a godan verb has (っ, り, ろ, or ら but not
    /// られ before the ending), so the lemma is never a 一段 verb.
    struct Candidate { let lemma: String; let note: String; var godanOnly = false }
    static func candidates(_ word: String) -> [Candidate] { candidates(word, from: rules) }

    /// The core rules and the forms a short tap selects as a whole word.
    static func allCandidates(_ word: String) -> [Candidate] { candidates(word, from: allRules) }

    private static func candidates(_ word: String, from table: [(String, String, String)]) -> [Candidate] {
        guard !word.isEmpty, word.count <= 80,
              !word.contains(where: { $0.isWhitespace || "。！？、".contains($0) }) else { return [] }
        var seen = Set<String>()
        // A kana word is never read as a potential form (いれる is not いる's).
        let kanaOnly = !hasKanjiOrKatakana(word)
        return table.compactMap { suffix, ending, note in
            guard word.hasSuffix(suffix), !(kanaOnly && note.hasPrefix("可能形候选")) else { return nil }
            let stem = word.dropLast(suffix.count)
            let lemma = String(stem) + ending
            // An empty stem never leaves a lone godan ending (した → す, not する).
            guard lemma != word, !lemma.isEmpty, !(stem.isEmpty && godanEndings.contains(lemma)),
                  seen.insert(lemma).inserted else { return nil }
            return Candidate(lemma: lemma, note: note, godanOnly: ending == "る" && godanOnly(suffix))
        }
    }

    /// A 一段 verb keeps its stem before every ending (いる → いた, いない,
    /// います); っ, り, ろ and ら (but not られ) come only from a godan る verb
    /// (要る → いった, いらない, いります).
    private static func godanOnly(_ suffix: String) -> Bool {
        ["っ", "り", "ろ"].contains(where: { suffix.hasPrefix($0) }) || (suffix.hasPrefix("ら") && !suffix.hasPrefix("られ"))
    }

    private static let godanEndings: Set<String> = ["う", "く", "ぐ", "す", "つ", "ぬ", "ぶ", "む", "る"]

    /// Suffixes after a name or a noun; か only after 何 and 誰.
    static let nounSuffixes: [(String, String)] = [
        ("さん", "称呼后缀"), ("くん", "称呼后缀"), ("ちゃん", "称呼后缀"), ("さま", "称呼后缀"), ("様", "称呼后缀"),
        ("たち", "复数后缀"), ("達", "复数后缀"), ("か", "不定助词"),
    ]

    static func hasKanjiOrKatakana(_ text: String) -> Bool {
        text.unicodeScalars.contains { (0x3400 ... 0x9FFF).contains($0.value) || (0x30A0 ... 0x30FF).contains($0.value) }
    }
    static func note(_ word: String, lemma: String) -> String? {
        candidates(word).first { $0.lemma == lemma }.map {
            "词典形「\(lemma)」；\($0.note)。规则分析候选，请结合原句确认。"
        }
    }
    // Generate related endings from a single table instead of maintaining 492 literals.
    static let rules: [(String, String, String)] = {
        var result: [(String, String, String)] = []
        func add(_ suffix: String, _ lemmaEnding: String, _ note: String) { result.append((suffix, lemmaEnding, note)) }
        let polite = [("ませんでした", "礼貌体 · 否定 · 过去"), ("ません", "礼貌体 · 否定"),
                      ("ました", "礼貌体 · 过去"), ("ます", "礼貌体")]
        let dictionary = Array("うくぐすつぬぶむる").map(String.init)
        let politeStems = Array("いきぎしちにびみり").map(String.init)
        let negativeStems = Array("わかがさたなばまら").map(String.init)
        for (suffix, note) in polite {
            for (stem, ending) in zip(politeStems, dictionary) { add(stem + suffix, ending, note) }
            add(suffix, "る", note)
        }
        for (suffix, note) in [("なかった", "否定 · 过去"), ("ない", "否定")] {
            for (stem, ending) in zip(negativeStems, dictionary) { add(stem + suffix, ending, note) }
            add(suffix, "る", note)
        }
        let pastAndTe = [("った", "う"), ("った", "つ"), ("った", "る"), ("いた", "く"), ("いだ", "ぐ"),
            ("した", "す"), ("んだ", "む"), ("んだ", "ぶ"), ("んだ", "ぬ"), ("た", "る"),
            ("って", "う"), ("って", "つ"), ("って", "る"), ("いて", "く"), ("いで", "ぐ"),
            ("して", "す"), ("んで", "む"), ("んで", "ぶ"), ("んで", "ぬ"), ("て", "る")]
        for (suffix, ending) in pastAndTe {
            let isTe = suffix.hasSuffix("て") || suffix.hasSuffix("で")
            add(suffix, ending, isTe ? "て形 · 连接" : "过去 / 完成")
            if isTe {
                for (tail, note) in [("いる", "进行 / 结果状态"), ("いた", "进行 / 结果状态 · 过去"),
                    ("いない", "进行 / 结果状态 · 否定"), ("います", "进行 / 结果状态 · 礼貌体")] { add(suffix + tail, ending, note) }
            }
        }
        for (suffix, note) in [("かった", "い形容词 · 过去"), ("くない", "い形容词 · 否定"),
            ("くなかった", "い形容词 · 否定 · 过去")] { add(suffix, "い", note) }
        var voices = [("させられる", "る", "使役被动"), ("させる", "る", "使役"),
            ("られる", "る", "可能 / 被动 / 尊敬（需结合原句）")]
        for (stem, ending) in zip(negativeStems, dictionary) {
            voices += [(stem + "れる", ending, "被动 / 尊敬（需结合原句）"), (stem + "せる", ending, "使役"),
                       (stem + "せられる", ending, "使役被动")]
        }
        for (stem, ending) in zip(Array("えけげせてねべめれ").map(String.init), dictionary) {
            voices.append((stem + "る", ending, "可能形候选"))
        }
        for (suffix, ending, note) in voices {
            add(suffix, ending, note)
            for (tail, tense) in [("た", "过去"), ("て", "て形"), ("ない", "否定"), ("なかった", "否定 · 过去")] + polite {
                add(String(suffix.dropLast()) + tail, ending, note + " · " + tense)
            }
        }
        for (suffix, ending, note) in [("した", "する", "过去"), ("して", "する", "て形"), ("しない", "する", "否定"),
            ("しなかった", "する", "否定 · 过去"), ("できる", "する", "可能"), ("来た", "来る", "过去"),
            ("来て", "来る", "て形"), ("来ない", "来る", "否定"), ("行った", "行く", "过去"), ("行って", "行く", "て形")] { add(suffix, ending, note) }
        for (suffix, note) in polite { add("し" + suffix, "する", note); add("来" + suffix, "来る", note) }
        return result.enumerated().sorted {
            $0.element.0.count == $1.element.0.count ? $0.offset < $1.offset : $0.element.0.count > $1.element.0.count
        }.map(\.element)
    }()
    static let allRules: [(String, String, String)] = sortedByLength(rules + extendedRules)

    private static func sortedByLength(_ table: [(String, String, String)]) -> [(String, String, String)] {
        table.enumerated().sorted {
            $0.element.0.count == $1.element.0.count ? $0.offset < $1.offset : $0.element.0.count > $1.element.0.count
        }.map(\.element)
    }

    /// Forms outside the core table: desire, conditional, listing,
    /// volitional and ず forms, more te-form tails and する after a noun.
    static let extendedRules: [(String, String, String)] = {
        var result: [(String, String, String)] = []
        func add(_ suffix: String, _ lemmaEnding: String, _ note: String) { result.append((suffix, lemmaEnding, note)) }
        let dictionary = Array("うくぐすつぬぶむる").map(String.init)
        let politeStems = Array("いきぎしちにびみり").map(String.init)
        let negativeStems = Array("わかがさたなばまら").map(String.init)
        let conditionalStems = Array("えけげせてねべめれ").map(String.init)
        let volitionalStems = Array("おこごそとのぼもろ").map(String.init)
        for (suffix, note) in [("たい", "愿望"), ("たかった", "愿望 · 过去"), ("たくない", "愿望 · 否定"),
            ("たくなかった", "愿望 · 否定 · 过去"), ("たくて", "愿望 · て形"), ("たく", "愿望 · 连用"),
            ("ましょう", "礼貌体 · 劝诱"), ("ながら", "同时进行"), ("なさい", "命令"), ("そう", "样态")] {
            for (stem, ending) in zip(politeStems, dictionary) { add(stem + suffix, ending, note) }
            add(suffix, "る", note)
            add("し" + suffix, "する", note)
            add("来" + suffix, "来る", note)
        }
        let past = [("った", "う"), ("った", "つ"), ("った", "る"), ("いた", "く"), ("いだ", "ぐ"),
            ("した", "す"), ("んだ", "む"), ("んだ", "ぶ"), ("んだ", "ぬ"), ("た", "る"),
            ("した", "する"), ("来た", "来る"), ("行った", "行く"), ("いった", "行く"), ("いった", "言う"), ("かった", "い")]
        for (suffix, ending) in past {
            add(suffix + "ら", ending, "条件（たら）")
            add(suffix + "り", ending, "列举（たり）")
        }
        for (stem, ending) in zip(conditionalStems, dictionary) { add(stem + "ば", ending, "条件（ば）") }
        for (suffix, ending) in [("ければ", "い"), ("なければ", "ない"), ("すれば", "する"), ("来れば", "来る")] {
            add(suffix, ending, "条件（ば）")
        }
        let negatives = [("ず", "否定（ず）"), ("ずに", "否定（ずに）"), ("なくて", "否定 · て形"), ("なく", "否定 · 连用"),
            ("なければ", "否定 · 条件（ば）"), ("ないで", "否定 · ないで")]
        for (suffix, note) in negatives {
            for (stem, ending) in zip(negativeStems, dictionary) { add(stem + suffix, ending, note) }
            add(suffix, "る", note)
            add((suffix.hasPrefix("ず") ? "せ" : "し") + suffix, "する", note)
            add("来" + suffix, "来る", note)
        }
        for (stem, ending) in zip(volitionalStems, dictionary) { add(stem + "う", ending, "意志 / 劝诱") }
        for (suffix, ending) in [("よう", "る"), ("しよう", "する"), ("来よう", "来る")] { add(suffix, ending, "意志 / 劝诱") }
        let teForms = [("って", "う"), ("って", "つ"), ("って", "る"), ("いて", "く"), ("いで", "ぐ"),
            ("して", "す"), ("んで", "む"), ("んで", "ぶ"), ("んで", "ぬ"), ("て", "る"), ("して", "する"),
            ("いって", "行く"), ("いって", "言う")]
        let tails = [("いる", "进行 / 结果状态"), ("いた", "进行 / 结果状态 · 过去"), ("いない", "进行 / 结果状态 · 否定"),
            ("います", "进行 / 结果状态 · 礼貌体"), ("いました", "进行 / 结果状态 · 礼貌体 · 过去"),
            ("いません", "进行 / 结果状态 · 礼貌体 · 否定"), ("いなかった", "进行 / 结果状态 · 否定 · 过去"),
            ("いませんでした", "进行 / 结果状态 · 礼貌体 · 否定 · 过去")]
        for (suffix, ending) in teForms { for (tail, note) in tails { add(suffix + tail, ending, note) } }
        // ていく, てくる and てくれる after a te-form (出ていった, 持ってきた).
        let directions = [("いく", "趋向（ていく）"), ("いった", "趋向（ていく） · 过去"), ("いって", "趋向（ていく） · て形"),
            ("いかない", "趋向（ていく） · 否定"), ("いきます", "趋向（ていく） · 礼貌体"), ("いきました", "趋向（ていく） · 礼貌体 · 过去"),
            ("いかなかった", "趋向（ていく） · 否定 · 过去"), ("いったら", "趋向（ていく） · 条件（たら）"),
            ("くる", "趋向（てくる）"), ("きた", "趋向（てくる） · 过去"), ("きて", "趋向（てくる） · て形"),
            ("こない", "趋向（てくる） · 否定"), ("きます", "趋向（てくる） · 礼貌体"), ("きました", "趋向（てくる） · 礼貌体 · 过去"),
            ("こなかった", "趋向（てくる） · 否定 · 过去"), ("きたら", "趋向（てくる） · 条件（たら）"), ("こよう", "趋向（てくる） · 意志 / 劝诱"),
            // てくれる, so 言ってくれた is never 言ってくる's potential.
            ("くれる", "授受（てくれる）"), ("くれた", "授受（てくれる） · 过去"), ("くれて", "授受（てくれる） · て形"),
            ("くれない", "授受（てくれる） · 否定"), ("くれなかった", "授受（てくれる） · 否定 · 过去"),
            ("くれます", "授受（てくれる） · 礼貌体"), ("くれました", "授受（てくれる） · 礼貌体 · 过去"),
            ("くれません", "授受（てくれる） · 礼貌体 · 否定"), ("くれ", "授受（てくれる） · 命令")]
        for (suffix, ending) in teForms { for (tail, note) in directions { add(suffix + tail, ending, note) } }
        // 行く and 言う written in kana: いった and いって are either one.
        for lemma in ["行く", "言う"] {
            add("いった", lemma, "过去 / 完成")
            add("いって", lemma, "て形 · 连接")
        }
        for (suffix, note) in [("される", "被动"), ("された", "被动 · 过去"), ("されて", "被动 · て形"),
            ("されない", "被动 · 否定"), ("されました", "被动 · 礼貌体 · 过去"), ("させる", "使役"),
            ("させた", "使役 · 过去"), ("させて", "使役 · て形"), ("させない", "使役 · 否定"),
            ("させられる", "使役被动"), ("させられた", "使役被动 · 过去"), ("できた", "可能 · 过去"),
            ("できない", "可能 · 否定"), ("できなかった", "可能 · 否定 · 过去"), ("できます", "可能 · 礼貌体"),
            ("できました", "可能 · 礼貌体 · 过去"), ("できません", "可能 · 礼貌体 · 否定")] {
            add(suffix, "する", note)
        }
        for (suffix, note) in [("そう", "い形容词 · 样态"), ("くて", "い形容词 · て形"), ("く", "い形容词 · 连用")] {
            add(suffix, "い", note)
        }
        add("そう", "", "形容动词 · 样态")
        // The stem a short tap selects before そう (悲し|そう, おいし|そう).
        add("し", "しい", "い形容词词干（后接そう、すぎる等）")
        // てしまう and でしまう said short (読ん|じゃった, 食べ|ちゃう).
        for (suffix, note) in [("ちゃう", "「てしまう」的缩约"), ("ちゃった", "「てしまう」的缩约 · 过去"),
            ("ちゃって", "「てしまう」的缩约 · て形"), ("じゃう", "「でしまう」的缩约"),
            ("じゃった", "「でしまう」的缩约 · 过去"), ("じゃって", "「でしまう」的缩约 · て形")] {
            add(suffix, "しまう", note)
        }
        for (suffix, note) in [("すぎる", "过度（すぎる）"), ("すぎた", "过度 · 过去"), ("すぎて", "过度 · て形"),
            ("すぎない", "过度 · 否定"), ("すぎます", "过度 · 礼貌体"), ("すぎました", "过度 · 礼貌体 · 过去"), ("すぎ", "过度")] {
            for (stem, ending) in zip(politeStems, dictionary) { add(stem + suffix, ending, note) }
            add(suffix, "る", note)
            add("し" + suffix, "する", note)
            add(suffix, "い", "い形容词 · " + note)
            add(suffix, "", "形容动词 · " + note)
        }
        for (suffix, note) in [("やすい", "容易……（やすい）"), ("やすく", "容易……（やすい） · 连用"),
            ("にくい", "难以……（にくい）"), ("にくく", "难以……（にくい） · 连用"), ("づらい", "难以……（づらい）")] {
            for (stem, ending) in zip(politeStems, dictionary) { add(stem + suffix, ending, note) }
            add(suffix, "る", note)
            add("し" + suffix, "する", note)
        }
        for (suffix, ending, note) in [("だった", "だ", "过去"), ("だったら", "だ", "条件（たら）"), ("だって", "だ", "て形"),
            ("だろう", "だ", "推量"), ("でしょう", "です", "推量"), ("じゃない", "だ", "否定"),
            ("じゃなかった", "だ", "否定 · 过去"), ("ではない", "だ", "否定"), ("ではなかった", "だ", "否定 · 过去")] {
            add(suffix, ending, note)
        }
        // 来る written in kana, as after a te-form (帰ってきます).
        for (form, note) in [("きます", "礼貌体"), ("きました", "礼貌体 · 过去"), ("きません", "礼貌体 · 否定"),
            ("きた", "过去"), ("きて", "て形"), ("きたら", "条件（たら）"), ("こない", "否定"),
            ("こなかった", "否定 · 过去"), ("こよう", "意志 / 劝诱"), ("くれば", "条件（ば）")] {
            add(form, "来る", note)
        }
        // A bare continuative stem before すぎ, そう or a compound (食べ → 食べる, 飲み → 飲む).
        for (stem, ending) in zip(politeStems, dictionary) { add(stem, ending, "连用形") }
        for stem in Array("いきぎしじちにびみりえけげせぜてでねべぺめれ").map(String.init) { add(stem, stem + "る", "连用形") }
        return result
    }()
}
