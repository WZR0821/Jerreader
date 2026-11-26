package com.jerreader.android.translation

/**
 * The user message of a translation sent to a model called directly or to the
 * ChatGPT plan, word for word what iOS's `TranslationPrompt` sends
 * (BackendTranslationService.swift). The system instruction stays the
 * reader's own translation prompt.
 */
internal object TranslationPrompt {
    const val CONTEXT_INSTRUCTION =
        "只翻译 <source> 里的原文。<context> 是原文所在的上下文，只用来理解原文，不要翻译它，也不要把它写进输出。"

    /**
     * A custom prompt may not say what the context is for, and some models
     * then translate it as well, so the request itself says which part to
     * translate.
     */
    fun userContent(sourceText: String, context: String?): String =
        if (context != null) {
            "$CONTEXT_INSTRUCTION\n\n<source>\n$sourceText\n</source>\n\n<context>\n$context\n</context>"
        } else {
            "SOURCE:\n$sourceText"
        }
}
