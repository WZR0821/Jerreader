package com.jerreader.android.learning

import com.jerreader.android.lexical.JapaneseChineseLexicon
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class JapaneseChineseLexiconTest {
    private val lexicon = JapaneseChineseLexicon(File("../shared/dictionaries/ja-zh-core.tsv").readText(Charsets.UTF_8))
    @Test fun japaneseAndKanaReturnChineseWithReading() {
        assertEquals("吃", lexicon.japanese("食べる")?.definition)
        assertEquals("食べる", lexicon.japanese(" たべる ")?.term)
        assertEquals("読む", lexicon.japanese("読んだ", listOf("読む"))?.term)
    }
    @Test fun reverseLookupUsesOnlyExplicitAliases() {
        assertEquals("読む（よむ）", lexicon.chinese("阅读"))
        assertNull(lexicon.chinese("阅读书籍"))
        assertNull(lexicon.japanese("毎日、本を読む。"))
        assertNull(lexicon.japanese("unknown"))
    }
    @Test fun sharedPackIsNonemptyAndHasUniqueHeadwords() {
        assertTrue(lexicon.entries.size >= 100)
        assertEquals(lexicon.entries.size, lexicon.entries.map { it.term }.toSet().size)
    }
    @Test fun reverseResultCanBeLookedUpAfterSwappingDirection() {
        assertEquals("読む", lexicon.japanese(checkNotNull(lexicon.chinese("阅读")))?.term)
    }
    @Test fun bundledDictionarySupportsRealTermsOutsideTheCore() {
        val full = JapaneseChineseLexicon(File("../shared/dictionaries/ja-zh-core.tsv").readText(Charsets.UTF_8),
            File("../shared/dictionaries/ja-zh-wiktionary.tsv").readText(Charsets.UTF_8))
        assertTrue(full.headwordCount > 3000)
        assertTrue(checkNotNull(full.japanese("曖昧")).definition.contains("含糊"))
        assertTrue(full.matches("含糊", chinese = true).any { it.term == "曖昧" })
        assertTrue(checkNotNull(full.japanese("頑張る")).definition.contains("努力"))
        assertTrue(full.matches("頑張った").any { it.term == "頑張る" })
        assertTrue(checkNotNull(full.japanese("曖昧")).sourceUrl!!.startsWith("https://zh.wiktionary.org/wiki/"))
        assertNull(full.chinese("今天需要努力学习日语"))
    }
    @Test fun ambiguousReadingsRemainSelectableWithoutGuessing() {
        val entries = "校正\tこうせい\t名词\t校对\t校对\t\t校正\n構成\tこうせい\t名词\t构成\t构成\t\t構成"
        val dictionary = JapaneseChineseLexicon("", entries)
        assertNull(dictionary.japanese("こうせい"))
        assertEquals(listOf("校正", "構成"), dictionary.matches("こうせい").map { it.term })
        assertEquals("構成", dictionary.japanese("構成（こうせい）")?.term)
        assertNull(dictionary.chinese("构成部分"))
    }
    // 2.1.0: a short tap selects a whole word; it finds one headword or none, as on iOS.
    @Test fun wholeWordsFromAShortTapFindOneHeadword() {
        val full = JapaneseChineseLexicon(File("../shared/dictionaries/ja-zh-core.tsv").readText(),
            File("../shared/dictionaries/ja-zh-wiktionary.tsv").readText())
        val whole = listOf(
            "勉強しました" to "勉強", "食べたい" to "食べる", "読んでいました" to "読む", "子供たち" to "子供", "ご主人" to "主人",
            "田中さん" to "田中", "しれない" to "知れる", "飲みすぎた" to "飲む", "使いやすい" to "使う", "した" to "する",
            "🈳" to "🈳", "空" to "空", "心配しないで" to "心配", "静かそう" to "静か", "だった" to "だ",
            "なりたい" to "なる", "合わなかった" to "合う", "浮かんで" to "浮かぶ", "聞こえて" to "聞こえる", "できました" to "できる",
            "入れて" to "入れる", "いれて" to "入れる", "開けた" to "開ける", "ちゃった" to "しまう", "じゃった" to "しまう",
            "すみませんでした" to "すみません", "走り去った" to "走る", "読み終わった" to "読む", "咲き始めた" to "咲く", "悲し" to "悲しい",
            "おいし" to "美味しい", "お母様" to "母", "よかった" to "良い", "彼ら" to "彼ら", "話していました" to "話す",
            "話しましょう" to "話す", "散歩した" to "散歩", "時" to "時", "こと" to "こと", "から" to "から",
            "らしい" to "らしい", "みんな" to "皆", "ねった" to "練る", "出ていった" to "出る", "持ってきた" to "持つ",
            "言ってくれた" to "言う",
        )
        for ((word, term) in whole) assertEquals(word, term, full.japanese(word)?.term)
        for ((word, part) in listOf("走り去った" to "复合动词", "お母様" to "敬语后缀", "散歩した" to "散歩する", "すみませんでした" to "でした",
            "出ていった" to "趋向（ていく）", "言ってくれた" to "授受（てくれる）")) {
            assertTrue(word, full.inflectionNote(word, checkNotNull(full.japanese(word)))!!.contains(part))
        }
        // A lone hiragana is a particle or an ending, not a reading (で is not 出).
        assertNull(full.japanese("で")); assertTrue(full.matches("で").isEmpty())
        assertNull(full.japanese("ね")); assertTrue(full.matches("ね").isEmpty())
        // Two headwords stay choices, and a tie finds none.
        assertNull(full.japanese("きた")); assertEquals(setOf("北", "来る"), full.matches("きた").map { it.term }.toSet())
        // Kana 行く and 言う share いった and いって, and a kana word the
        // supplement lists also offers the core's headword read that way.
        for (word in listOf("いった", "いって")) {
            assertNull(word, full.japanese(word)); assertEquals(word, setOf("行く", "言う"), full.matches(word).map { it.term }.toSet())
        }
        assertNull(full.japanese("いく")); assertEquals(setOf("行く", "いく"), full.matches("いく").map { it.term }.toSet())
        // A form only a godan verb has is no 一段 verb's (いらない is not いる).
        for ((word, term) in listOf("いらない" to "いる", "いります" to "いる", "きった" to "着る")) {
            assertTrue(word, full.japanese(word)?.term != term && full.matches(word).none { it.term == term })
        }
        assertNull(full.japanese("降り出した"))
        assertNull(full.japanese("毎日、本を読む。"))
    }
    @Test fun contextualInflectionsAreDictionaryValidated() {
        val full = JapaneseChineseLexicon(File("../shared/dictionaries/ja-zh-core.tsv").readText(),
            File("../shared/dictionaries/ja-zh-wiktionary.tsv").readText())
        assertTrue("食べさせられた", full.matches("食べさせられた").any { it.term == "食べる" })
        assertTrue(com.jerreader.android.lexical.JapaneseLookupInflection.note("食べさせられた", "食べる")!!.contains("使役被动"))
        assertTrue("食べさせられませんでした", full.matches("食べさせられませんでした").any { it.term == "食べる" })
        assertTrue(com.jerreader.android.lexical.JapaneseLookupInflection.note("食べさせられませんでした", "食べる")!!.contains("否定"))
        assertTrue("読まなかった", full.matches("読まなかった").any { it.term == "読む" })
        assertTrue(com.jerreader.android.lexical.JapaneseLookupInflection.note("読まなかった", "読む")!!.contains("否定"))
        assertTrue("読んでいる", full.matches("読んでいる").any { it.term == "読む" })
        assertTrue(com.jerreader.android.lexical.JapaneseLookupInflection.note("読んでいる", "読む")!!.contains("结果状态"))
        assertTrue("高くなかった", full.matches("高くなかった").any { it.term == "高い" })
        assertTrue(com.jerreader.android.lexical.JapaneseLookupInflection.note("高くなかった", "高い")!!.contains("形容词"))
        assertTrue("しました", full.matches("しました").any { it.term == "する" })
        assertTrue(com.jerreader.android.lexical.JapaneseLookupInflection.note("しました", "する")!!.contains("礼貌体"))
        assertTrue("行った", full.matches("行った").any { it.term == "行く" })
        assertTrue(com.jerreader.android.lexical.JapaneseLookupInflection.note("行った", "行く")!!.contains("过去"))
        assertTrue("食べられた", full.matches("食べられた").any { it.term == "食べる" })
        assertTrue(com.jerreader.android.lexical.JapaneseLookupInflection.note("食べられた", "食べる")!!.contains("可能 / 被动"))
        assertTrue(full.matches("架空語させられた").isEmpty())
        assertNull(com.jerreader.android.lexical.JapaneseLookupInflection.note("食べる", "食べる"))
        assertTrue(com.jerreader.android.lexical.JapaneseLookupInflection.candidates("あ".repeat(81) + "ました").isEmpty())
    }
}
