package com.jerreader.android.translation

import com.jerreader.shared.lexical.LexicalLookupService
import com.jerreader.shared.lexical.WordExplanation
import com.jerreader.shared.translation.StandaloneTranslationInput
import kotlinx.coroutines.CoroutineScope

/** Dictionary retry has its own lifetime and never restarts the paid translation. */
internal class StandaloneWordLookupSession(
    parent: CoroutineScope,
    private val service: LexicalLookupService,
    private val record: suspend (WordExplanation) -> Unit,
    private val timeoutMillis: Long = 15_000
) {
    private val session = TranslationToolSession(parent)

    fun invalidate() = session.invalidate()

    fun start(
        input: StandaloneTranslationInput,
        available: WordExplanation?,
        onFound: (WordExplanation) -> Unit,
        onIssue: (String) -> Unit,
        onFinished: () -> Unit
    ) {
        invalidate()
        session.launch(
            timeoutMillis = timeoutMillis,
            onTimeout = { onIssue("词典查询等待超时，可重试；已有译文仍可使用。") },
            onFinished = onFinished
        ) {
            runCatchingActive {
                available?.takeIf {
                    it.surfaceForm == input.text && it.language == input.sourceLanguage &&
                        it.definitions.any(String::isNotBlank)
                } ?: service.lookup(input.text, null, input.sourceLanguage)
            }.onSuccess { found ->
                onFound(found)
                runCatchingActive { record(found) }
                    .onFailure { onIssue("词典结果暂时无法写入历史，可重试保存；释义仍可查看。") }
            }.onFailure {
                onIssue("词典暂时不可用，可重试；已有译文仍可使用。")
            }
        }
    }
}
