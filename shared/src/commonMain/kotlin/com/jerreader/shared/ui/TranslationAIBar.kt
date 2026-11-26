package com.jerreader.shared.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jerreader.shared.translation.AIAnswerSource
import com.jerreader.shared.translation.AIReadingAction
import com.jerreader.shared.translation.AIReadingActionPolicy

/** The answer of one AI chip on the translation card (2.1.0). */
sealed interface AIResultState {
    /** The text so far; the whole answer once [Done]. */
    val text: String

    data class Generating(override val text: String = "") : AIResultState

    data class Done(override val text: String, val source: AIAnswerSource? = null) : AIResultState

    /** [text] keeps what had arrived before the failure, if anything. */
    data class Failed(
        val message: String,
        override val text: String = "",
        val canRetry: Boolean = true
    ) : AIResultState
}

/** One question of the 追问 conversation and its answer. */
data class AIFollowUpEntry(val question: String, val answer: AIResultState)

/**
 * What the card's AI action bar shows: its chips, the selected one and that
 * chip's answer. 追问 shows [followUps] and [presets] instead of [result].
 */
data class TranslationAIBarState(
    val actions: List<AIReadingAction>,
    val selected: AIReadingAction? = null,
    val result: AIResultState? = null,
    val followUps: List<AIFollowUpEntry> = emptyList(),
    val presets: List<String> = emptyList(),
    /** Why 存为笔记 is greyed out (这段选区不能存笔记); null while it works. */
    val noteUnavailableReason: String? = null
) {
    /** True while an answer is still being written. */
    val isGenerating: Boolean
        get() = result is AIResultState.Generating || followUps.any { it.answer is AIResultState.Generating }
}

private val ChipHeight = 30.dp
private val ChipShape = RoundedCornerShape(10.dp)

/**
 * The AI chips between the translation and the action row (2.1.0 §4), and
 * the selected chip's answer under them: an accent eyebrow with the result
 * title, the text as it streams, 生成中… and 停止 while it is written, the
 * error and 重试 when it fails, and 存为笔记, 展开看全文 and the source badge
 * once it is done. The chips wrap on a narrow card instead of scrolling.
 *
 * Each chip is 30dp high; Compose extends a touch target that small to the
 * platform minimum, so it still takes a 48dp tap.
 */
@Composable
fun TranslationAIBar(
    state: TranslationAIBarState,
    onSelect: (AIReadingAction) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onSaveNote: (title: String, text: String) -> Unit,
    onExpand: (title: String, text: String) -> Unit,
    onShowSource: (AIAnswerSource) -> Unit,
    onAsk: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSaveGrammar: ((String) -> Unit)? = null,
    onInputFocusChanged: (Boolean) -> Unit = {}
) {
    // A new result area scrolls into view, and so does each new piece of
    // text while it is written: the whole bar, so the card follows the
    // answer until the chips reach its top and then keeps them in view, as
    // iOS does (2.1.0 §4). 追问 keeps its latest answer in view instead.
    val barRequester = remember { BringIntoViewRequester() }
    val selected = state.selected
    val title = selected?.resultTitle
    Column(
        modifier = modifier.fillMaxWidth().bringIntoViewRequester(barRequester),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            state.actions.forEach { action ->
                AIChip(title = action.title, selected = action == state.selected, onClick = { onSelect(action) })
            }
        }
        if (selected != null && title != null) {
            key(selected) {
                val endRequester = remember { BringIntoViewRequester() }
                val followed = if (selected == AIReadingAction.FOLLOW_UP) {
                    state.followUps.size to (state.followUps.lastOrNull()?.answer?.text?.length ?: 0)
                } else {
                    0 to (state.result?.text?.length ?: 0)
                }
                LaunchedEffect(followed) {
                    if (selected == AIReadingAction.FOLLOW_UP) endRequester.bringIntoView() else barRequester.bringIntoView()
                }
                Box(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .jerreaderReveal(order = 0),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        JerreaderEyebrow(text = title, modifier = Modifier.semantics { heading() })
                        if (selected == AIReadingAction.FOLLOW_UP) {
                            FollowUpConversation(
                                state = state,
                                onAsk = onAsk,
                                onStop = onStop,
                                onRetry = onRetry,
                                onSaveNote = onSaveNote,
                                onShowSource = onShowSource,
                                onInputFocusChanged = onInputFocusChanged
                            )
                        } else {
                            state.result?.let { result ->
                                AIResultContent(
                                    action = selected,
                                    title = title,
                                    result = result,
                                    noteUnavailableReason = state.noteUnavailableReason,
                                    onStop = onStop,
                                    onRetry = onRetry,
                                    onSaveNote = onSaveNote,
                                    onExpand = onExpand,
                                    onShowSource = onShowSource,
                                    onSaveGrammar = onSaveGrammar?.takeIf { selected == AIReadingAction.STRUCTURE }
                                )
                            }
                        }
                    }
                    // The end of the answer, which 追问 keeps in view.
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(1.dp)
                            .bringIntoViewRequester(endRequester)
                    )
                }
            }
        }
    }
}

/** A chip: paper and a hairline, or the accent wash and accent text when selected. */
@Composable
private fun AIChip(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = jerreaderPalette
    val source = remember { MutableInteractionSource() }
    val fill by animateColorAsState(
        targetValue = if (selected) palette.accentFill else palette.paper,
        animationSpec = JerreaderMotion.selection(),
        label = "aiChipFill"
    )
    val edge by animateColorAsState(
        targetValue = if (selected) palette.accentFill else palette.line,
        animationSpec = JerreaderMotion.selection(),
        label = "aiChipEdge"
    )
    val ink by animateColorAsState(
        targetValue = if (selected) palette.accent else MaterialTheme.colorScheme.onSurface,
        animationSpec = JerreaderMotion.selection(),
        label = "aiChipText"
    )
    Box(
        modifier = modifier
            .height(ChipHeight)
            .jerreaderPressScale(source, pressedScale = 0.95f)
            .background(fill, ChipShape)
            .border(1.dp, edge, ChipShape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = ink,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun AIResultContent(
    action: AIReadingAction,
    title: String,
    result: AIResultState,
    noteUnavailableReason: String?,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onSaveNote: (String, String) -> Unit,
    onExpand: (String, String) -> Unit,
    onShowSource: (AIAnswerSource) -> Unit,
    onSaveGrammar: ((String) -> Unit)?
) {
    if (result.text.isNotEmpty()) AIAnswerText(action, result.text)
    when (result) {
        is AIResultState.Generating -> GeneratingRow(onStop)
        is AIResultState.Failed -> FailedRow(result, onRetry)
        is AIResultState.Done -> AIResultFooter(
            source = result.source,
            onShowSource = onShowSource,
            noteUnavailableReason = noteUnavailableReason
        ) {
            AIFooterButton("存为笔记", JerreaderIcon.NOTE, enabled = noteUnavailableReason == null) {
                onSaveNote(title, result.text)
            }
            AIFooterButton("展开看全文", JerreaderIcon.DOC_PLAIN) { onExpand(title, result.text) }
            onSaveGrammar?.let { save ->
                AIFooterButton("保存到语法知识库", JerreaderIcon.BOOKMARK_OUTLINE) { save(result.text) }
            }
        }
    }
}

/** The answer in body text; a 直译 answer that kept its format shows its two lines apart. */
@Composable
private fun AIAnswerText(action: AIReadingAction, text: String) {
    val literal = if (action == AIReadingAction.LITERAL) AIReadingActionPolicy.literalAnswer(text) else null
    val style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.5.sp)
    if (literal == null) {
        Text(text, style = style)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LabelledLine("直译", literal.literal, style)
        LabelledLine("差别", literal.difference, style)
    }
}

@Composable
private fun LabelledLine(label: String, text: String, style: TextStyle) {
    Column(
        modifier = Modifier.semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = jerreaderPalette.secondaryText, fontWeight = FontWeight.SemiBold)
        Text(text, style = style)
    }
}

/** 生成中… on the left and 停止 on the right. */
@Composable
private fun GeneratingRow(onStop: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JerreaderSpacing.xs)
    ) {
        Text(
            text = "生成中…",
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodySmall,
            color = jerreaderPalette.secondaryText
        )
        AIFooterButton("停止", null, onClick = onStop)
    }
}

@Composable
private fun FailedRow(result: AIResultState.Failed, onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = result.message,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = jerreaderPalette.vermilion
        )
        if (result.canRetry) AIFooterButton("重试", JerreaderIcon.RESTORE, onClick = onRetry)
    }
}

/**
 * The buttons on the left and the source badge on the right, or the badge on
 * a line of its own when they do not fit beside each other. A greyed 存为笔记
 * explains itself in a caption under them.
 */
@Composable
private fun AIResultFooter(
    source: AIAnswerSource?,
    onShowSource: (AIAnswerSource) -> Unit,
    noteUnavailableReason: String?,
    buttons: @Composable () -> Unit
) {
    val buttonRow: @Composable () -> Unit = {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) { buttons() }
    }
    val sourceBadge: @Composable () -> Unit = { source?.let { AISourceBadge(it, onShowSource) } }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Layout(
            contents = listOf(buttonRow, sourceBadge),
            modifier = Modifier.fillMaxWidth()
        ) { (buttonMeasurables, badgeMeasurables), constraints ->
            val width = constraints.maxWidth
            val gap = 8.dp.roundToPx()
            val loose = constraints.copy(minWidth = 0, minHeight = 0)
            val badge = badgeMeasurables.firstOrNull()?.measure(loose)
            val row = buttonMeasurables.first()
            val badgeRoom = badge?.let { it.width + gap } ?: 0
            // The badge drops under the buttons only when they leave it no room beside them.
            val badgeBelow = badge != null && row.maxIntrinsicWidth(constraints.maxHeight) + badgeRoom > width
            val buttonsPlaced = row.measure(loose.copy(maxWidth = if (badgeBelow) width else (width - badgeRoom).coerceAtLeast(0)))
            val badgeHeight = badge?.height ?: 0
            val height = if (badgeBelow) buttonsPlaced.height + badgeHeight else maxOf(buttonsPlaced.height, badgeHeight)
            layout(width, height) {
                buttonsPlaced.place(0, if (badgeBelow) 0 else (height - buttonsPlaced.height) / 2)
                if (badge != null) {
                    badge.place(width - badge.width, if (badgeBelow) buttonsPlaced.height else (height - badge.height) / 2)
                }
            }
        }
        noteUnavailableReason?.let { reason ->
            Text(reason, style = MaterialTheme.typography.labelMedium, color = jerreaderPalette.secondaryText)
        }
    }
}

/** A small accent text action of a result: 存为笔记, 展开看全文, 停止, 重试. */
@Composable
private fun AIFooterButton(
    text: String,
    icon: JerreaderIcon?,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val palette = jerreaderPalette
    val source = remember { MutableInteractionSource() }
    val tint = if (enabled) palette.accent else palette.secondaryText.copy(alpha = 0.6f)
    Row(
        modifier = Modifier
            .height(ChipHeight)
            .jerreaderPressScale(source, pressedScale = 0.95f)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        icon?.let { JerreaderGlyph(it, tint, Modifier.size(14.dp)) }
        Text(text, color = tint, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * 「模型名 · 含上下文」 or 「本机翻译」 under an answer. An AI model's badge opens
 * 「发送给模型的内容」; the system translator sent nothing, so its badge is text.
 */
@Composable
fun AISourceBadge(
    source: AIAnswerSource,
    onShowSource: ((AIAnswerSource) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val palette = jerreaderPalette
    val open = onShowSource?.takeIf { source.isAIModel }
    Row(
        modifier = modifier
            .heightIn(min = 24.dp)
            .then(
                if (open != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = "查看发送给模型的内容") { open(source) }
                } else {
                    Modifier
                }
            )
            .semantics(mergeDescendants = true) { contentDescription = "来源：${source.badge}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        if (source.isAIModel) JerreaderGlyph(JerreaderIcon.SPARKLES, palette.secondaryText, Modifier.size(11.dp))
        Text(
            text = source.badge,
            color = palette.secondaryText,
            fontSize = 12.sp,
            lineHeight = 14.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (open != null) JerreaderGlyph(JerreaderIcon.CHEVRON_FORWARD, palette.secondaryText, Modifier.size(10.dp))
    }
}

/** The presets while the conversation is empty, then each question and its answer, then the input. */
@Composable
private fun FollowUpConversation(
    state: TranslationAIBarState,
    onAsk: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onSaveNote: (String, String) -> Unit,
    onShowSource: (AIAnswerSource) -> Unit,
    onInputFocusChanged: (Boolean) -> Unit
) {
    val generating = state.isGenerating
    if (state.followUps.isEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            state.presets.forEach { preset -> AIChip(title = preset, selected = false, onClick = { onAsk(preset) }) }
        }
    }
    state.followUps.forEachIndexed { index, entry ->
        if (index > 0) JerreaderDivider(color = jerreaderPalette.line)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = entry.question,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            val answer = entry.answer
            if (answer.text.isNotEmpty()) AIAnswerText(AIReadingAction.FOLLOW_UP, answer.text)
            when (answer) {
                is AIResultState.Generating -> GeneratingRow(onStop)
                is AIResultState.Failed -> FailedRow(answer, onRetry)
                is AIResultState.Done -> AIResultFooter(
                    source = answer.source,
                    onShowSource = onShowSource,
                    noteUnavailableReason = state.noteUnavailableReason
                ) {
                    AIFooterButton("存为笔记", JerreaderIcon.NOTE, enabled = state.noteUnavailableReason == null) {
                        onSaveNote("追问", AIReadingActionPolicy.followUpNote(entry.question, answer.text))
                    }
                }
            }
        }
    }
    FollowUpInput(enabled = !generating, onAsk = onAsk, onFocusChanged = onInputFocusChanged)
}

/** One line: 问问这句话… and a round send button; sending puts the keyboard away. */
@Composable
private fun FollowUpInput(enabled: Boolean, onAsk: (String) -> Unit, onFocusChanged: (Boolean) -> Unit) {
    val palette = jerreaderPalette
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf("") }
    // When the card closes with the keyboard up, the field leaves without a focus change.
    val latestFocusChanged by rememberUpdatedState(onFocusChanged)
    DisposableEffect(Unit) { onDispose { latestFocusChanged(false) } }
    val question = text.trim()
    val canSend = enabled && question.isNotEmpty()
    val send = {
        if (canSend) {
            onAsk(question)
            text = ""
            focus.clearFocus()
        }
    }
    val shape = RoundedCornerShape(JerreaderRadius.small)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .background(palette.paper, shape)
            .border(1.dp, palette.line, shape)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            if (text.isEmpty()) {
                Text("问问这句话…", style = MaterialTheme.typography.bodyMedium, color = palette.secondaryText, maxLines = 1)
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onFocusChanged(it.isFocused) }
                    .semantics { contentDescription = "追问输入框" },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(palette.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                maxLines = 4
            )
        }
        val source = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(36.dp)
                .jerreaderPressScale(source, pressedScale = 0.9f)
                .clickable(interactionSource = source, indication = null, enabled = canSend, role = Role.Button) { send() }
                .semantics { contentDescription = "发送追问" },
            contentAlignment = Alignment.Center
        ) {
            val fill = if (canSend) palette.accent else palette.accent.copy(alpha = 0.3f)
            val arrow = palette.onAccent
            Canvas(Modifier.size(28.dp).background(fill, CircleShape)) {
                val stroke = 2.dp.toPx()
                val centerX = size.width / 2
                val top = size.height * 0.28f
                val bottom = size.height * 0.72f
                val wing = size.width * 0.2f
                drawLine(arrow, Offset(centerX, bottom), Offset(centerX, top), stroke, StrokeCap.Round)
                drawLine(arrow, Offset(centerX - wing, top + wing), Offset(centerX, top), stroke, StrokeCap.Round)
                drawLine(arrow, Offset(centerX + wing, top + wing), Offset(centerX, top), stroke, StrokeCap.Round)
            }
        }
    }
}
