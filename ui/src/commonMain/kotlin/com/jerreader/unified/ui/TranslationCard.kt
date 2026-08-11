package com.jerreader.unified.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.jerreader.unified.translation.TranslationResult

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

/**
 * A vocabulary entry only makes sense for a single word: one Latin token, or a
 * short run of CJK characters without punctuation.
 */
internal fun String.isSingleWord(): Boolean {
    val trimmed = trim()
    if (trimmed.isEmpty() || trimmed.length > 24) return false
    if (trimmed.any { it.isWhitespace() }) return false
    return trimmed.all { it.isLetter() || it == '\'' || it == '-' }
}

/**
 * Mirrors the iOS `ReaderTranslationOverlay`: a compact header carrying the
 * actions menu and a close button, a hairline divider, then a body that shows
 * only the translation. The source sentence is already highlighted on the page,
 * so repeating it here would just push the translation out of view.
 */
@Composable
fun TranslationCard(
    state: TranslationCardState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onFavorite: ((TranslationResult) -> Unit)? = null,
    onExplain: ((TranslationResult) -> Unit)? = null,
    onAnnotate: ((String) -> Unit)? = null,
    onExpandCrossPage: (() -> Unit)? = null,
    onAddToVocabulary: (() -> Unit)? = null,
    crossPageMessage: String? = null,
    isExpandingCrossPage: Boolean = false,
    onOpenSettings: () -> Unit,
    onDrag: ((Float, Float) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OverlayHeader(
            onDrag = onDrag,
            state = state,
            onDismiss = onDismiss,
            onRetry = onRetry,
            onCopy = onCopy,
            onFavorite = onFavorite,
            onExplain = onExplain,
            onAnnotate = onAnnotate,
            onExpandCrossPage = onExpandCrossPage,
            onAddToVocabulary = onAddToVocabulary,
            isExpandingCrossPage = isExpandingCrossPage
        )
        JerreaderDivider()
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (state) {
                is TranslationCardState.Loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在翻译…", style = MaterialTheme.typography.bodyMedium)
                }

                is TranslationCardState.Success -> {
                    // iOS marks the translation with a vertical accent rule.
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .heightIn(min = 22.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Text(
                            text = state.result.translatedText,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    state.analysisText?.let { analysis ->
                        Text(
                            "句子结构",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(analysis, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                is TranslationCardState.Failure -> {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onRetry) { Text("重试") }
                        TextButton(onClick = onOpenSettings) { Text("翻译设置") }
                    }
                }
            }
            crossPageMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun OverlayHeader(
    onDrag: ((Float, Float) -> Unit)?,
    state: TranslationCardState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onFavorite: ((TranslationResult) -> Unit)?,
    onExplain: ((TranslationResult) -> Unit)?,
    onAnnotate: ((String) -> Unit)?,
    onExpandCrossPage: (() -> Unit)?,
    onAddToVocabulary: (() -> Unit)?,
    isExpandingCrossPage: Boolean
) {
    var menuExpanded by remember { mutableStateOf(false) }

    // iOS header: a centred grabber on a row of its own, then a row carrying a
    // 字 badge with the 译文 label on the left and the more and close buttons on
    // the right. Whether the title fits depends on how wide the card is, so
    // measure before laying anything out — see `TranslationCardHeaderMetrics`.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardWidth = maxWidth

        // "…" and close, and neither of them comes and goes with the
        // translation's phase. The star used to appear here the moment a
        // translation succeeded, so the header gained an icon after the card
        // had already been read and everything shifted left; favouriting lives
        // in the actions menu now, which carried the same item all along.
        val buttonCount = 2
        val showsTitle = TranslationCardHeaderMetrics.fitsTitle(cardWidth, buttonCount)

        // The grabber alone is a 22dp strip, and missing it was most of what
        // "cannot drag the card" meant. The whole header takes the drag; the
        // buttons keep their taps, because a drag is only claimed once the
        // finger has passed touch slop.
        val dragModifier = if (onDrag == null) {
            Modifier
        } else {
            Modifier.pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    onDrag(amount.x, amount.y)
                }
            }
        }

        Column(modifier = Modifier.fillMaxWidth().then(dragModifier)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TranslationCardHeaderMetrics.handleRowHeight),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(
                            width = TranslationCardHeaderMetrics.handleWidth,
                            height = 4.dp
                        )
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outline)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TranslationCardHeaderMetrics.actionRowHeight)
                    .padding(horizontal = TranslationCardHeaderMetrics.horizontalPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showsTitle) {
                    Box(
                        modifier = Modifier
                            .size(TranslationCardHeaderMetrics.titleIconWidth)
                            .clip(RoundedCornerShape(7.dp))
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "字",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    Text(
                        "译文",
                        modifier = Modifier.padding(start = TranslationCardHeaderMetrics.titleGap),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Box {
                    HeaderIconButton(JerreaderIcon.MORE) { menuExpanded = true }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        if (state is TranslationCardState.Success) {
                            DropdownMenuItem(
                                text = { Text("复制译文") },
                                onClick = { menuExpanded = false; onCopy(state.result.translatedText) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("重新翻译") },
                            onClick = { menuExpanded = false; onRetry() }
                        )
                        if (state is TranslationCardState.Success) {
                            onExplain?.let { explain ->
                                DropdownMenuItem(
                                    text = { Text("AI 句子结构分析") },
                                    onClick = { menuExpanded = false; explain(state.result) }
                                )
                            }
                        }
                        // The only place favouriting lives; see the button count
                        // above for why it is no longer on the header too.
                        if (state is TranslationCardState.Success) {
                            onFavorite?.let { favorite ->
                                DropdownMenuItem(
                                    text = { Text("收藏译文") },
                                    onClick = { menuExpanded = false; favorite(state.result) }
                                )
                            }
                        }
                        onAnnotate?.let { annotate ->
                            DropdownMenuItem(
                                text = { Text("添加笔记") },
                                onClick = { menuExpanded = false; annotate(state.sourceText) }
                            )
                        }
                        onExpandCrossPage?.let { expand ->
                            DropdownMenuItem(
                                enabled = !isExpandingCrossPage,
                                text = { Text(if (isExpandingCrossPage) "正在补齐…" else "扩展跨页句段") },
                                onClick = { menuExpanded = false; expand() }
                            )
                        }
                        onAddToVocabulary?.takeIf { state.sourceText.isSingleWord() }?.let { add ->
                            DropdownMenuItem(
                                text = { Text("加入生词本") },
                                onClick = { menuExpanded = false; add() }
                            )
                        }
                    }
                }
                HeaderIconButton(JerreaderIcon.CLOSE, onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun HeaderIconButton(icon: JerreaderIcon, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        JerreaderGlyph(
            icon = icon,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}
