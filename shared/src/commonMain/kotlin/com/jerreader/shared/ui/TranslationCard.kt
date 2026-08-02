package com.jerreader.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jerreader.shared.translation.TranslationResult

sealed interface TranslationCardState {
    val sourceText: String

    data class Loading(override val sourceText: String) : TranslationCardState

    data class Success(
        override val sourceText: String,
        val result: TranslationResult,
        val analysisText: String? = null
    ) : TranslationCardState

    data class Failure(
        override val sourceText: String,
        val message: String
    ) : TranslationCardState
}

@Composable
fun TranslationCard(
    state: TranslationCardState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onFavorite: ((TranslationResult) -> Unit)? = null,
    onSpeakSource: (() -> Unit)? = null,
    onSpeakTranslation: ((TranslationResult) -> Unit)? = null,
    onExplain: ((TranslationResult) -> Unit)? = null,
    onAnnotate: ((String) -> Unit)? = null,
    onExpandCrossPage: (() -> Unit)? = null,
    crossPageMessage: String? = null,
    isExpandingCrossPage: Boolean = false,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("点按翻译", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onDismiss) { Text("关闭") }
        }

        Text(
            text = state.sourceText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when (state) {
            is TranslationCardState.Loading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator()
                Text("正在翻译…")
            }

            is TranslationCardState.Success -> {
                Text(
                    text = state.result.translatedText,
                    style = MaterialTheme.typography.bodyLarge
                )
                state.analysisText?.let { analysis ->
                    Text("句子结构", fontWeight = FontWeight.SemiBold)
                    Text(analysis, style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onCopy(state.result.translatedText) }) { Text("复制译文") }
                    onFavorite?.let { favorite ->
                        Button(onClick = { favorite(state.result) }) { Text("收藏") }
                    }
                    Text(
                        buildString {
                            append(state.result.providerIdentifier)
                            if (state.result.isFromCache) append(" · 缓存")
                        },
                        modifier = Modifier.align(Alignment.CenterVertically),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    onSpeakSource?.let { speak ->
                        TextButton(onClick = speak) { Text("朗读原文") }
                    }
                    onSpeakTranslation?.let { speak ->
                        TextButton(onClick = { speak(state.result) }) { Text("朗读译文") }
                    }
                    onExplain?.let { explain ->
                        TextButton(onClick = { explain(state.result) }) { Text("AI 解析") }
                    }
                    onAnnotate?.let { annotate ->
                        TextButton(onClick = { annotate(state.sourceText) }) { Text("添加笔记") }
                    }
                    onExpandCrossPage?.let { expand ->
                        TextButton(enabled = !isExpandingCrossPage, onClick = expand) {
                            Text(if (isExpandingCrossPage) "正在补齐…" else "扩展跨页句段")
                        }
                    }
                }
                crossPageMessage?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            is TranslationCardState.Failure -> {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRetry) { Text("重试") }
                    TextButton(onClick = onOpenSettings) { Text("打开翻译设置") }
                }
            }
        }
    }
}
