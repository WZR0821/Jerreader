package com.jerreader.shared.translation

import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.learning.AdaptiveReadingAssist
import com.jerreader.shared.learning.ReadingAssistLevel
import com.jerreader.shared.learning.ReadingCompanionMode

/** The bounded reading questions the in-reader AI lens offers. */
enum class AIReadingLensMode(
    val storageId: String,
    val title: String,
    val detail: String
) {
    COMPREHENSION("comprehension", "读懂这段", "说明这段在当前语境中真正表达了什么"),
    CONTEXT_MEANING("context_meaning", "语境词义", "解释关键词为何在这里采用这个含义"),
    REFERENCE("reference", "省略与指代", "还原省略成分、代词与人物指向"),
    GRAMMAR("grammar", "日语语法", "拆解句子结构、修饰关系与重点语法")
}

/** How a lens request is asked; part of the request so each variant caches apart. */
data class ReadingLensOptions(
    val mode: AIReadingLensMode,
    val companionMode: ReadingCompanionMode = ReadingCompanionMode.FICTION,
    val assistLevel: ReadingAssistLevel = ReadingAssistLevel.GUIDED,
    val adaptiveInstruction: String? = null,
    val focusTerms: List<String> = emptyList()
) {
    companion object {
        fun from(mode: AIReadingLensMode, companionMode: ReadingCompanionMode, assist: AdaptiveReadingAssist) =
            ReadingLensOptions(mode, companionMode, assist.level, assist.instruction, assist.focusTerms)
    }
}

/**
 * The shared safety and output contract of the reading lens. The provider is
 * still the user's own configured model; every mode gets the same
 * source-bounded, no-spoiler constraints. The iOS copy in
 * ContextExplanationService.swift renders the identical text, checked by
 * shared/parity/reading_companion.json.
 */
object AIReadingLensPromptPolicy {
    fun rendered(
        options: ReadingLensOptions,
        sourceLanguage: LanguageCode?,
        responseLanguage: LanguageCode,
        grammarPrompt: String? = null
    ): String {
        val task = when (options.mode) {
            AIReadingLensMode.COMPREHENSION ->
                "先给出一句自然释义，再说明语气、言外之意以及理解这段所需的最少信息。"
            AIReadingLensMode.CONTEXT_MEANING ->
                "找出最影响理解的词或短语，说明其基本义、当前语境义以及采用该义的文本证据。"
            AIReadingLensMode.REFERENCE ->
                "识别省略的主语、宾语、代词或人物指向；逐项给出依据，不能确认时明确写出不确定。"
            AIReadingLensMode.GRAMMAR -> grammarPrompt?.trim()?.takeIf(String::isNotEmpty)
                ?: "拆解句子主干、修饰关系、活用与重点日语语法，并说明这些结构如何影响句意。"
        }
        val structure = when (options.companionMode) {
            ReadingCompanionMode.FICTION -> "这是小说伴读：优先辨认人物、动机、语气、指代和情节因果。"
            ReadingCompanionMode.NONFICTION -> "这是非虚构伴读：优先辨认概念、定义、论点、证据和推理关系。"
        }
        val personalization = options.adaptiveInstruction?.trim()?.takeIf(String::isNotEmpty)
            ?: "采用${options.assistLevel.title}，帮助用户自己回到原文验证。"
        val focus = options.focusTerms.map(String::trim).filter(String::isNotEmpty).distinct().take(5)
            .joinToString("、")
            .let { if (it.isEmpty()) null else "用户近期薄弱点：$it。只在当前片段确有证据时提醒。" }
        return listOfNotNull(
            "你是 Jerreader 的 AI 阅读伴侣。请使用${languageName(responseLanguage)}回答，原文语言为${sourceLanguage?.let(::languageName) ?: "自动识别"}。",
            task,
            structure,
            personalization,
            focus,
            "只允许依据用户提供的 SELECTED TEXT 和 READING CONTEXT。不要声称读过整本书，不要补写未提供的情节、人物设定或后续内容，不要剧透。证据不足时写“仅凭当前片段无法确认”，并列出最可能的解释。优先帮助用户回到原文自行理解，回答保持清晰、简洁、可核对。"
        ).joinToString("\n")
    }

    private fun languageName(language: LanguageCode): String = when (language) {
        LanguageCode.CHINESE_SIMPLIFIED -> "简体中文"
        LanguageCode.ENGLISH -> "英语"
        LanguageCode.JAPANESE -> "日语"
    }
}
