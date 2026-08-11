package com.jerreader.unified.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jerreader.unified.domain.LanguageCode
import com.jerreader.unified.lexical.WordAnalysis
import com.jerreader.unified.lexical.WordExplanation
import com.jerreader.unified.lexical.WordSelectionSource

sealed interface WordLookupCardState {
    val analysis: WordAnalysis

    data class Loading(override val analysis: WordAnalysis) : WordLookupCardState
    data class Success(
        override val analysis: WordAnalysis,
        val explanation: WordExplanation
    ) : WordLookupCardState
    data class Failure(
        override val analysis: WordAnalysis,
        val message: String
    ) : WordLookupCardState
}

@Composable
fun WordLookupCard(
    state: WordLookupCardState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onFavorite: ((WordExplanation) -> Unit)? = null,
    onSpeak: ((WordExplanation) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.analysis.surfaceForm,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = buildString {
                        append(if (state.analysis.language == LanguageCode.JAPANESE) "日语" else "英语")
                        append(" · ")
                        append(
                            if (state.analysis.source == WordSelectionSource.SHORT_TAP) {
                                "点按查词"
                            } else {
                                "长按查词"
                            }
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onDismiss) { Text("关闭") }
        }

        when (state) {
            is WordLookupCardState.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator()
                Text("正在查询中文释义…")
            }

            is WordLookupCardState.Failure -> {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry) { Text("重试") }
            }

            is WordLookupCardState.Success -> SuccessContent(
                analysis = state.analysis,
                explanation = state.explanation,
                onCopy = onCopy,
                onFavorite = onFavorite,
                onSpeak = onSpeak
            )
        }
    }
}

@Composable
private fun SuccessContent(
    analysis: WordAnalysis,
    explanation: WordExplanation,
    onCopy: (String) -> Unit,
    onFavorite: ((WordExplanation) -> Unit)?,
    onSpeak: ((WordExplanation) -> Unit)?
) {
    val displayedLemma = explanation.lemma
        ?: analysis.lemma?.takeIf { it != analysis.normalizedForm }
    LazyColumn(
        modifier = Modifier.heightIn(max = 430.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (displayedLemma != null || explanation.reading != null || explanation.partOfSpeech != null) {
            item {
                Text(
                    listOfNotNull(
                        displayedLemma?.let { lemma ->
                            if (explanation.lemma != null) "词典形：$lemma" else "基本形候选：$lemma"
                        },
                        explanation.reading?.let { "读音：$it" },
                        explanation.partOfSpeech
                    ).joinToString(" · "),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item { HorizontalDivider() }
        itemsIndexed(explanation.definitions) { index, definition ->
            Text("${index + 1}. $definition", style = MaterialTheme.typography.bodyLarge)
        }
        explanation.inflectionNote?.let { note -> item { Text(note) } }
        explanation.sentenceContext?.takeIf(String::isNotBlank)?.let { context ->
            item {
                Text("书中语境", fontWeight = FontWeight.SemiBold)
                Text(context, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        explanation.usageNote?.let { note ->
            item {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = {
                        onCopy(
                            buildString {
                                append(explanation.surfaceForm)
                                displayedLemma?.let { append(" [$it]") }
                                append("：")
                                append(explanation.definitions.joinToString("；"))
                            }
                        )
                    }
                ) { Text("复制词条") }
                onFavorite?.let { favorite ->
                    Button(onClick = { favorite(explanation) }) { Text("加入生词本") }
                }
                onSpeak?.let { speak ->
                    TextButton(onClick = { speak(explanation) }) { Text("朗读") }
                }
            }
        }
    }
}
