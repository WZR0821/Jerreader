package com.jerreader.shared.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.WordAnalysis
import com.jerreader.shared.lexical.WordExplanation
import com.jerreader.shared.lexical.WordSelectionSource

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

private val WordCardHeaderHeight = 44.dp
private val WordCardHitSize = 44.dp
private val WordCardButtonHeight = 36.dp
private val WordCardBodyMaxHeight = 430.dp

/**
 * The reader's word card, drawn like the translation card beside it: a 44dp
 * header with the accent eyebrow (日语 · 点按查词) and a round close button,
 * then a body that scrolls. iOS has no floating word card (its reader opens
 * 语境查词 instead), so the entry follows the iOS lookup result: the word in
 * serif bold, the facts in secondary, 词典释义 · 中文 over serif definitions,
 * 活用说明 and 来源与用法, then a tinted 复制词条 beside the filled 加入生词本,
 * in one row when they fit and stacked when they do not.
 *
 * The caller draws the paper, border and shadow and places the card from its
 * measured frame, so nothing here animates its size: a new phase only fades
 * its body in, growing from 98.5% at the top.
 */
@Composable
fun WordLookupCard(
    state: WordLookupCardState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onFavorite: ((WordExplanation) -> Unit)? = null,
    onSpeak: ((WordExplanation) -> Unit)? = null,
    onLookup: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        WordCardHeader(
            eyebrow = wordCardEyebrow(state.analysis),
            isFailure = state is WordLookupCardState.Failure,
            onDismiss = onDismiss
        )
        // The body is the last child, so the column hands it whatever height
        // the reader leaves for the card and it scrolls inside that.
        WordCardBody(
            state = state,
            onRetry = onRetry,
            onCopy = onCopy,
            onFavorite = onFavorite,
            onSpeak = onSpeak,
            onLookup = onLookup
        )
    }
}

/** The eyebrow, with a tail ellipsis on a narrow card, and the close button. */
@Composable
private fun WordCardHeader(
    eyebrow: String,
    isFailure: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = jerreaderPalette
    val eyebrowTint by animateColorAsState(
        targetValue = if (isFailure) palette.vermilion else palette.accent,
        animationSpec = JerreaderMotion.stateChange(),
        label = "wordCardEyebrow"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = WordCardHeaderHeight)
            .padding(start = JerreaderSpacing.md, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JerreaderSpacing.xs)
    ) {
        Text(
            text = eyebrow,
            modifier = Modifier.weight(1f),
            color = eyebrowTint,
            fontSize = 12.sp,
            lineHeight = 14.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        WordCardCloseButton(onClick = onDismiss)
    }
}

/**
 * iOS `ReaderCardIconButtonStyle` at the close size: a 28dp wash of the text
 * colour at 6%, 12% pressed, in a 44dp target, shrinking to 0.9 when pressed.
 */
@Composable
private fun WordCardCloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = jerreaderPalette
    val wash = MaterialTheme.colorScheme.onSurface
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill by animateColorAsState(
        targetValue = wash.copy(alpha = if (pressed) 0.12f else 0.06f),
        animationSpec = if (pressed) JerreaderMotion.press() else JerreaderMotion.stateChange(),
        label = "wordCardCloseFill"
    )
    Box(
        modifier = modifier
            .size(WordCardHitSize)
            .semantics { contentDescription = "关闭" }
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .jerreaderPressScale(source, pressedScale = 0.9f)
                .drawBehind { drawCircle(fill) },
            contentAlignment = Alignment.Center
        ) {
            JerreaderGlyph(JerreaderIcon.CLOSE, palette.secondaryText, Modifier.size(15.dp))
        }
    }
}

/** The word over the loading, failure or entry content of the current phase. */
@Composable
private fun WordCardBody(
    state: WordLookupCardState,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onFavorite: ((WordExplanation) -> Unit)?,
    onSpeak: ((WordExplanation) -> Unit)?,
    onLookup: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val phase = wordCardPhase(state)
    // The phase the card opened with is already covered by the card's reveal.
    val openingPhase = remember { phase }
    key(phase) {
        val entrance = remember { Animatable(if (phase == openingPhase) 1f else 0f) }
        LaunchedEffect(Unit) { entrance.animateTo(1f, JerreaderMotion.stateChange()) }
        Column(
            modifier = modifier
                .graphicsLayer {
                    val value = entrance.value
                    alpha = value.coerceIn(0f, 1f)
                    val scale = 0.985f + 0.015f * value
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .fillMaxWidth()
                .heightIn(max = WordCardBodyMaxHeight)
                .verticalScroll(rememberScrollState())
                // The eyebrow already gives the word its top margin.
                .padding(start = JerreaderSpacing.md, end = JerreaderSpacing.md, top = 2.dp, bottom = JerreaderSpacing.md),
            verticalArrangement = Arrangement.spacedBy(JerreaderSpacing.sm)
        ) {
            val word = state.analysis.surfaceForm
            Text(
                text = word,
                modifier = Modifier.semantics { heading() },
                style = JerreaderLearningType.title2.copy(
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = jerreaderSerifFamily(word)
                )
            )
            when (state) {
                is WordLookupCardState.Loading -> WordCardLoading(onLookup = onLookup)

                is WordLookupCardState.Failure -> WordCardFailure(
                    message = state.message,
                    onRetry = onRetry,
                    onLookup = onLookup
                )

                is WordLookupCardState.Success -> WordCardEntry(
                    analysis = state.analysis,
                    explanation = state.explanation,
                    onCopy = onCopy,
                    onFavorite = onFavorite,
                    onSpeak = onSpeak,
                    onLookup = onLookup
                )
            }
        }
    }
}

/** The pulsing dictionary glyph of the reader's loading cards, then 语境查词. */
@Composable
private fun WordCardLoading(onLookup: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(JerreaderSpacing.sm)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(JerreaderSpacing.sm)
        ) {
            ReaderChromeLoadingGlyph(JerreaderIcon.DICTIONARY, 38.dp)
            Text("正在查询中文释义…", style = MaterialTheme.typography.titleSmall)
        }
        onLookup?.let { lookup -> WordCardLookupButton(onClick = lookup) }
    }
}

/** The message, then a tinted 重试 beside 语境查词, as the translation card fails. */
@Composable
private fun WordCardFailure(
    message: String,
    onRetry: () -> Unit,
    onLookup: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        WordCardActions {
            JerreaderTintedButton(
                text = "重试",
                onClick = onRetry,
                icon = JerreaderIcon.RESTORE,
                height = WordCardButtonHeight
            )
            onLookup?.let { lookup -> WordCardLookupButton(onClick = lookup) }
        }
    }
}

/**
 * iOS `readerResult`. Android also keeps 书中语境 here, and 朗读 when the
 * caller can speak. 加入生词本 only adds, so once it has saved this entry it
 * reads 已加入生词本 and cannot save it twice, as in the translator.
 */
@Composable
private fun WordCardEntry(
    analysis: WordAnalysis,
    explanation: WordExplanation,
    onCopy: (String) -> Unit,
    onFavorite: ((WordExplanation) -> Unit)?,
    onSpeak: ((WordExplanation) -> Unit)?,
    onLookup: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val palette = jerreaderPalette
    val haptics = LocalHapticFeedback.current
    var favorited by remember(explanation) { mutableStateOf(false) }
    val displayedLemma = explanation.lemma
        ?: analysis.lemma?.takeIf { it != analysis.normalizedForm }
    val facts = listOfNotNull(
        displayedLemma?.let { lemma ->
            if (explanation.lemma != null) "词典形：$lemma" else "基本形候选：$lemma"
        },
        explanation.reading?.takeIf(String::isNotBlank)?.let { "读音：$it" },
        explanation.partOfSpeech?.takeIf(String::isNotBlank)
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(JerreaderSpacing.sm)) {
        if (facts.isNotEmpty()) {
            Text(
                text = facts.joinToString(" · "),
                style = JerreaderLearningType.subheadline,
                color = palette.secondaryText
            )
        }
        JerreaderEyebrow("词典释义 · 中文")
        explanation.definitions.forEach { definition ->
            Text(
                text = definition,
                style = JerreaderLearningType.body.copy(fontFamily = jerreaderSerifFamily(definition))
            )
        }
        explanation.inflectionNote?.let { note ->
            Text("活用说明\n$note", style = JerreaderLearningType.subheadline)
        }
        explanation.sentenceContext?.takeIf(String::isNotBlank)?.let { context ->
            WordCardContext(context)
        }
        explanation.usageNote?.let { note ->
            Text(
                text = "来源与用法\n$note",
                style = JerreaderLearningType.caption,
                color = palette.secondaryText
            )
        }
        // Where iOS puts 更换词条 / 释义: the way to a different word or sense.
        onLookup?.let { lookup -> WordCardLookupButton(onClick = lookup) }
        WordCardActions {
            JerreaderTintedButton(
                text = "复制词条",
                onClick = {
                    onCopy(
                        buildString {
                            append(explanation.surfaceForm)
                            displayedLemma?.let { append(" [$it]") }
                            append("：")
                            append(explanation.definitions.joinToString("；"))
                        }
                    )
                },
                height = WordCardButtonHeight
            )
            onFavorite?.let { favorite ->
                if (favorited) {
                    JerreaderTintedButton(
                        text = "已加入生词本",
                        onClick = {},
                        icon = JerreaderIcon.CHECK,
                        enabled = false,
                        height = WordCardButtonHeight
                    )
                } else {
                    JerreaderPrimaryButton(
                        text = "加入生词本",
                        onClick = {
                            favorite(explanation)
                            favorited = true
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        },
                        height = WordCardButtonHeight,
                        fullWidth = false
                    )
                }
            }
            onSpeak?.let { speak ->
                // There is no speaker glyph yet, so 朗读 goes without one.
                JerreaderTintedButton(text = "朗读", onClick = { speak(explanation) }, height = WordCardButtonHeight)
            }
        }
    }
}

/** 书中语境 as on the iOS lookup sheet: the eyebrow over the quoted sentence. */
@Composable
private fun WordCardContext(context: String, modifier: Modifier = Modifier) {
    val palette = jerreaderPalette
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        JerreaderEyebrow("书中语境")
        Text(
            text = context,
            modifier = Modifier.jerreaderQuoteBar(palette.accent.copy(alpha = 0.35f)),
            style = JerreaderLearningType.subheadline.copy(fontFamily = jerreaderSerifFamily(context)),
            color = palette.secondaryText
        )
    }
}

/** Opens the lookup sheet (iOS reader menu: character.book.closed). */
@Composable
private fun WordCardLookupButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    JerreaderTintedButton(
        text = "语境查词 / 修改词语",
        onClick = onClick,
        modifier = modifier,
        icon = JerreaderIcon.DICTIONARY,
        height = WordCardButtonHeight
    )
}

/**
 * iOS `ViewThatFits(in: .horizontal)` over an HStack and a VStack: the buttons
 * in one row 8dp apart when they fit, otherwise stacked at the leading edge.
 */
@Composable
private fun WordCardActions(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = JerreaderSpacing.xs.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val gaps = gap * (placeables.size - 1).coerceAtLeast(0)
        val rowWidth = placeables.sumOf { it.width } + gaps
        val inRow = rowWidth <= constraints.maxWidth
        val width = if (inRow) rowWidth else (placeables.maxOfOrNull { it.width } ?: 0)
        val height = if (inRow) (placeables.maxOfOrNull { it.height } ?: 0) else placeables.sumOf { it.height } + gaps
        layout(
            width.coerceIn(constraints.minWidth, constraints.maxWidth),
            height.coerceIn(constraints.minHeight, constraints.maxHeight)
        ) {
            var offset = 0
            placeables.forEach { placeable ->
                if (inRow) {
                    placeable.placeRelative(offset, (height - placeable.height) / 2)
                    offset += placeable.width + gap
                } else {
                    placeable.placeRelative(0, offset)
                    offset += placeable.height + gap
                }
            }
        }
    }
}

private fun wordCardEyebrow(analysis: WordAnalysis): String = buildString {
    append(if (analysis.language == LanguageCode.JAPANESE) "日语" else "英语")
    append(" · ")
    append(if (analysis.source == WordSelectionSource.SHORT_TAP) "点按查词" else "长按查词")
}

private fun wordCardPhase(state: WordLookupCardState): String {
    val phase = when (state) {
        is WordLookupCardState.Loading -> "loading"
        is WordLookupCardState.Success -> "success"
        is WordLookupCardState.Failure -> "failure"
    }
    return "$phase:${state.analysis.surfaceForm}:${state.analysis.rangeStart}"
}
