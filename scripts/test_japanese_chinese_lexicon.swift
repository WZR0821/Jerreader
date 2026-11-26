import Foundation
import CryptoKit

@main
enum JapaneseChineseLexiconTests {
    static func main() throws {
        let rules = JapaneseLookupInflection.rules.map { [$0.0, $0.1, $0.2].joined(separator: "\t") }.joined(separator: "\n")
        let digest = SHA256.hash(data: Data(rules.utf8)).map { String(format: "%02x", $0) }.joined()
        precondition(digest == "cc9579c6178c6f57aa03b3abbaf096fa9673ccd9427ab642550f122ae8019c10", "Inflection behavior or ambiguity priority changed")
        let lexicon = JapaneseChineseLexicon(tsv: try String(contentsOfFile: "shared/dictionaries/ja-zh-core.tsv", encoding: .utf8))
        precondition(lexicon.entries.count >= 100)
        precondition(Set(lexicon.entries.map(\.term)).count == lexicon.entries.count)
        precondition(lexicon.japanese("食べる")?.definition == "吃")
        precondition(lexicon.japanese(" たべる ")?.term == "食べる")
        precondition(lexicon.japanese("読んだ", candidates: ["読む"])?.term == "読む")
        precondition(lexicon.chinese("阅读") == "読む（よむ）")
        precondition(lexicon.japanese(lexicon.chinese("阅读")!)?.term == "読む")
        precondition(lexicon.chinese("阅读书籍") == nil)
        precondition(lexicon.japanese("毎日、本を読む。") == nil)
        precondition(lexicon.japanese("unknown") == nil)
        let full = JapaneseChineseLexicon(tsv: try String(contentsOfFile: "shared/dictionaries/ja-zh-core.tsv", encoding: .utf8),
            supplement: try String(contentsOfFile: "shared/dictionaries/ja-zh-wiktionary.tsv", encoding: .utf8))
        precondition(full.headwordCount > 3000)
        precondition(full.japanese("曖昧")!.definition.contains("含糊"))
        precondition(full.matches("含糊", chinese: true).contains { $0.term == "曖昧" })
        precondition(full.matches("頑張った").contains { $0.term == "頑張る" })
        precondition(full.japanese("曖昧")!.sourceURL != nil)
        let ambiguous = JapaneseChineseLexicon(tsv: "", supplement: "校正\tこうせい\t名词\t校对\t校对\t\t校正\n構成\tこうせい\t名词\t构成\t构成\t\t構成")
        precondition(ambiguous.japanese("こうせい") == nil)
        precondition(ambiguous.matches("こうせい").map(\.term) == ["校正", "構成"])
        precondition(full.matches("食べさせられた").contains { $0.term == "食べる" }, "食べさせられた")
        precondition(JapaneseLookupInflection.note("食べさせられた", lemma: "食べる")?.contains("使役被动") == true)
        precondition(full.matches("食べさせられませんでした").contains { $0.term == "食べる" }, "食べさせられませんでした")
        precondition(JapaneseLookupInflection.note("食べさせられませんでした", lemma: "食べる")?.contains("否定") == true)
        precondition(full.matches("読まなかった").contains { $0.term == "読む" }, "読まなかった")
        precondition(JapaneseLookupInflection.note("読まなかった", lemma: "読む")?.contains("否定") == true)
        precondition(full.matches("読んでいる").contains { $0.term == "読む" }, "読んでいる")
        precondition(JapaneseLookupInflection.note("読んでいる", lemma: "読む")?.contains("结果状态") == true)
        precondition(full.matches("高くなかった").contains { $0.term == "高い" }, "高くなかった")
        precondition(JapaneseLookupInflection.note("高くなかった", lemma: "高い")?.contains("形容词") == true)
        precondition(full.matches("しました").contains { $0.term == "する" }, "しました")
        precondition(JapaneseLookupInflection.note("しました", lemma: "する")?.contains("礼貌体") == true)
        precondition(full.matches("行った").contains { $0.term == "行く" }, "行った")
        precondition(JapaneseLookupInflection.note("行った", lemma: "行く")?.contains("过去") == true)
        precondition(full.matches("食べられた").contains { $0.term == "食べる" }, "食べられた")
        precondition(JapaneseLookupInflection.note("食べられた", lemma: "食べる")?.contains("可能 / 被动") == true)
        precondition(full.matches("架空語させられた").isEmpty)
        precondition(JapaneseLookupInflection.note("食べる", lemma: "食べる") == nil)
        precondition(JapaneseLookupInflection.candidates(String(repeating: "あ", count: 81) + "ました").isEmpty)
        // 2.1.0: the extended rules behind a short tap's whole word, the same
        // table as Android's (InflectionRuleParityTest).
        let all = JapaneseLookupInflection.allRules.map { [$0.0, $0.1, $0.2].joined(separator: "\t") }.joined(separator: "\n")
        let allDigest = SHA256.hash(data: Data(all.utf8)).map { String(format: "%02x", $0) }.joined()
        precondition(JapaneseLookupInflection.allRules.count == 1411, "Extended inflection rules changed")
        precondition(allDigest == "cd6e5829333e5499127bb162b7f4fc84cb8ab70c6efeaaaf4cb724824839cee5", "Extended inflection rules changed")
        // A short tap selects a whole word; it finds one headword or none.
        let whole: [(String, String)] = [
            ("勉強しました", "勉強"), ("食べたい", "食べる"), ("読んでいました", "読む"), ("子供たち", "子供"), ("ご主人", "主人"),
            ("田中さん", "田中"), ("しれない", "知れる"), ("飲みすぎた", "飲む"), ("使いやすい", "使う"), ("した", "する"),
            ("🈳", "🈳"), ("空", "空"), ("心配しないで", "心配"), ("静かそう", "静か"), ("だった", "だ"),
            ("なりたい", "なる"), ("合わなかった", "合う"), ("浮かんで", "浮かぶ"), ("聞こえて", "聞こえる"), ("できました", "できる"),
            ("入れて", "入れる"), ("いれて", "入れる"), ("開けた", "開ける"), ("ちゃった", "しまう"), ("じゃった", "しまう"),
            ("すみませんでした", "すみません"), ("走り去った", "走る"), ("読み終わった", "読む"), ("咲き始めた", "咲く"), ("悲し", "悲しい"),
            ("おいし", "美味しい"), ("お母様", "母"), ("よかった", "良い"), ("彼ら", "彼ら"), ("話していました", "話す"),
            ("話しましょう", "話す"), ("散歩した", "散歩"), ("時", "時"), ("こと", "こと"), ("から", "から"),
            ("らしい", "らしい"), ("みんな", "皆"), ("ねった", "練る"), ("出ていった", "出る"), ("持ってきた", "持つ"),
            ("言ってくれた", "言う"),
        ]
        for (word, term) in whole { precondition(full.japanese(word)?.term == term, word) }
        for (word, part) in [("走り去った", "复合动词"), ("お母様", "敬语后缀"), ("散歩した", "散歩する"), ("すみませんでした", "でした"),
            ("出ていった", "趋向（ていく）"), ("言ってくれた", "授受（てくれる）")] {
            precondition(full.inflectionNote(word, entry: full.japanese(word)!)?.contains(part) == true, word)
        }
        // A lone hiragana is a particle or an ending, not a reading (で is not 出).
        precondition(full.japanese("で") == nil && full.matches("で").isEmpty)
        precondition(full.japanese("ね") == nil && full.matches("ね").isEmpty)
        // Two headwords stay choices, and a tie finds none.
        precondition(full.japanese("きた") == nil && Set(full.matches("きた").map(\.term)) == ["北", "来る"])
        // Kana 行く and 言う share いった and いって, and a kana word the
        // supplement lists also offers the core's headword read that way.
        for word in ["いった", "いって"] {
            precondition(full.japanese(word) == nil && Set(full.matches(word).map(\.term)) == ["行く", "言う"], word)
        }
        precondition(full.japanese("いく") == nil && Set(full.matches("いく").map(\.term)) == ["行く", "いく"])
        // A form only a godan verb has is no 一段 verb's (いらない is not いる).
        for (word, term) in [("いらない", "いる"), ("いります", "いる"), ("きった", "着る")] {
            precondition(full.japanese(word)?.term != term && !full.matches(word).contains { $0.term == term }, word)
        }
        precondition(full.japanese("降り出した") == nil)
        precondition(full.japanese("毎日、本を読む。") == nil)
        print("Japanese–Chinese dictionary: \(full.headwordCount) headwords; bilingual, kana, inflection, ambiguity and miss checks passed.")
    }
}
