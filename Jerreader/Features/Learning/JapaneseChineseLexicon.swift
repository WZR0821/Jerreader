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
            for value in entry.forms { f[Self.normalized(value), default: []].append(index) }
            for value in entry.aliases { c[Self.normalized(value), default: []].append(index) }
        }
        heads = h; readings = r; inflections = f; reverse = c
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
        return nil
    }

    func matches(_ word: String, chinese: Bool = false) -> [Entry] {
        let key = Self.normalized(word)
        if chinese { return (reverse[key] ?? []).prefix(20).map { entries[$0] } }
        if let exact = heads[key] { return exact.prefix(20).map { entries[$0] } }
        var indices = inflections[key] ?? readings[key] ?? []
        for candidate in JapaneseLookupInflection.candidates(key) {
            indices += (heads[candidate.lemma] ?? []).filter {
                let pos = entries[$0].partOfSpeech
                return pos.contains("动") || pos.contains("動") || pos.contains("形容")
            }
        }
        var seen = Set<Int>()
        return indices.filter { seen.insert($0).inserted }.prefix(20).map { entries[$0] }
    }

    func inflectionNote(_ word: String, entry: Entry) -> String? {
        JapaneseLookupInflection.note(Self.normalized(word), lemma: entry.term)
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
    struct Candidate { let lemma: String; let note: String }
    static func candidates(_ word: String) -> [Candidate] {
        guard !word.isEmpty, word.count <= 80,
              !word.contains(where: { $0.isWhitespace || "。！？、".contains($0) }) else { return [] }
        var seen = Set<String>()
        return rules.compactMap { suffix, ending, note in
            guard word.hasSuffix(suffix) else { return nil }
            let lemma = String(word.dropLast(suffix.count)) + ending
            guard lemma != word, seen.insert(lemma).inserted else { return nil }
            return Candidate(lemma: lemma, note: note)
        }
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
}
