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
