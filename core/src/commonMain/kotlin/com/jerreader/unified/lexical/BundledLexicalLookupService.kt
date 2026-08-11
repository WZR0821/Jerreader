package com.jerreader.unified.lexical

import com.jerreader.unified.domain.LanguageCode

/**
 * A small, real offline safety net for common English and Japanese words.
 * It is deliberately separate from the Mock so tests can
 * verify the production fallback without pretending to cover a full dictionary.
 */
class BundledLexicalLookupService : LexicalLookupService {
    override suspend fun lookup(
        word: String,
        sentenceContext: String?,
        language: LanguageCode,
        candidates: List<String>
    ): WordExplanation {
        val surface = LexicalLookupPolicy.validate(word, language)
        val terms = buildList {
            add(surface)
            addAll(candidates)
            if (language == LanguageCode.ENGLISH) addAll(candidates.map(String::lowercase))
        }.distinct()
        val entry = terms.firstNotNullOfOrNull { term ->
            ENTRIES[Key(language, normalize(term))]?.let { term to it }
        } ?: throw LexicalLookupFailure.ResultNotFound

        return WordExplanation(
            surfaceForm = surface,
            lemma = entry.first.takeIf { normalize(it) != normalize(surface) },
            reading = entry.second.reading,
            language = language,
            partOfSpeech = entry.second.partOfSpeech,
            definitions = entry.second.definitions,
            inflectionNote = entry.second.inflectionNote,
            usageNote = entry.second.usageNote,
            sentenceContext = sentenceContext,
            providerIdentifier = "bundled-core-v1"
        )
    }

    private data class Key(val language: LanguageCode, val term: String)
    private data class Entry(
        val definitions: List<String>,
        val partOfSpeech: String? = null,
        val reading: String? = null,
        val inflectionNote: String? = null,
        val usageNote: String? = "内置基础词典释义。"
    )

    private companion object {
        fun normalize(value: String): String = value.trim().lowercase()

        fun english(term: String, part: String, vararg definitions: String) =
            Key(LanguageCode.ENGLISH, term) to Entry(definitions.toList(), part)

        fun japanese(
            term: String,
            reading: String,
            part: String,
            vararg definitions: String
        ) = Key(LanguageCode.JAPANESE, term) to Entry(
            definitions = definitions.toList(),
            partOfSpeech = part,
            reading = reading
        )

        val ENTRIES = mapOf(
            english("hello", "感叹词", "你好", "您好"),
            english("go", "动词", "去", "前往"),
            english("home", "名词 / 副词", "家", "回家；在家"),
            english("book", "名词", "书", "书籍"),
            english("read", "动词", "阅读", "读懂"),
            english("study", "动词 / 名词", "学习", "研究"),
            english("run", "动词", "跑", "运行"),
            english("eat", "动词", "吃", "进食"),
            english("quiet", "形容词", "安静的", "平静的"),
            english("night", "名词", "夜晚", "夜间"),
            english("see", "动词", "看见", "理解"),
            english("take", "动词", "拿；取", "带走"),
            english("think", "动词", "想", "认为"),
            english("say", "动词", "说", "表达"),
            japanese("こんにちは", "こんにちは", "感叹词", "你好"),
            japanese("ありがとう", "ありがとう", "感叹词", "谢谢"),
            japanese("食べる", "たべる", "动词", "吃", "食用"),
            japanese("読む", "よむ", "动词", "读", "阅读"),
            japanese("行く", "いく", "动词", "去", "前往"),
            japanese("来る", "くる", "动词", "来", "来到"),
            japanese("する", "する", "动词", "做", "进行"),
            japanese("見る", "みる", "动词", "看", "观看"),
            japanese("本", "ほん", "名词", "书", "书籍"),
            japanese("高い", "たかい", "形容词", "高的", "昂贵的"),
            japanese("静か", "しずか", "形容动词", "安静", "平静")
        )
    }
}
