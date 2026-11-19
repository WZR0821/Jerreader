package com.jerreader.shared.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.TranslationResult

sealed interface TranslationCardState {
    val sourceText: String

    data class Loading(override val sourceText: String) : TranslationCardState

    data class Success(
        override val sourceText: String,
        val result: TranslationResult,
        val analysisText: String? = null,
        /** "句子结构" for the grammar analysis, the lens question otherwise. */
        val analysisTitle: String = "句子结构",
        /** What the answer was based on, shown under the title. */
        val analysisDetail: String? = null,
        /** Only a sentence-structure answer belongs in the grammar notes. */
        val analysisSavable: Boolean = true
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

// iOS `ReaderTranslationOverlay` metrics.
private val CardHeaderHeight = 44.dp
private val CardActionRowHeight = 44.dp
private val CardMinimumHeight = 108.dp
private val CardHitSize = 44.dp

/** Below this width the eyebrow names each language with one character. */
private val CompactCardWidth = 238.dp

/**
 * Mirrors the iOS 2.0 `ReaderTranslationOverlay`: a 44dp header with the
 * language eyebrow over a drag zone and a close button, the translation, then
 * a 44dp action row under a hairline with the star on the left and 更多 on the
 * right. The source sentence is already highlighted on the page, so the card
 * shows only the translation.
 *
 * The caller draws the paper, border and shadow around it. Nothing in here
 * animates its size, because the reader places the card from its measured
 * frame: a new phase only fades its body in. There is no success haptic either;
 * the tap translation controllers already play one when a result lands.
 *
 * [requestSourceLanguage] and [requestTargetLanguage] name the direction while
 * a translation loads (null source: 自动识别); a result names its own.
 */
@Composable
fun TranslationCard(
    state: TranslationCardState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onFavorite: ((TranslationResult) -> Unit)? = null,
    onExplain: ((TranslationResult) -> Unit)? = null,
    onLens: ((TranslationResult) -> Unit)? = null,
    onSaveAnalysis: (() -> Unit)? = null,
    onAnnotate: ((String) -> Unit)? = null,
    onExpandCrossPage: (() -> Unit)? = null,
    onAddToVocabulary: (() -> Unit)? = null,
    onLookup: (() -> Unit)? = null,
    crossPageMessage: String? = null,
    isExpandingCrossPage: Boolean = false,
    onDrag: ((Float, Float) -> Unit)? = null,
    onDraggingChange: ((Boolean) -> Unit)? = null,
    translationContent: (@Composable (TranslationCardState.Success) -> Unit)? = null,
    japaneseFuriganaEnabled: Boolean = false,
    onToggleFurigana: (() -> Unit)? = null,
    requestSourceLanguage: LanguageCode? = null,
    requestTargetLanguage: LanguageCode? = null,
    loadingDetail: String = "正在安全请求所选 AI 服务"
) {
    val haptics = LocalHapticFeedback.current
    val success = state as? TranslationCardState.Success
    // Android only adds favourites, so the star fills once this card has saved
    // the translation and stays filled for it.
    var favorited by remember(state.sourceText, success?.result?.translatedText) {
        mutableStateOf(false)
    }
    var favoriteBounces by remember { mutableIntStateOf(0) }
    val favorite: ((Boolean) -> Unit)? = if (success != null && onFavorite != null) {
        { fromStar ->
            onFavorite(success.result)
            if (!favorited) {
                favorited = true
                // As on iOS, only the star bounces and confirms.
                if (fromStar) {
                    favoriteBounces += 1
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                }
            }
        }
    } else {
        null
    }
    val menuGroups = translationMenuGroups(
        state = state,
        favorite = favorite,
        onCopy = onCopy,
        onRetry = onRetry,
        onExplain = onExplain,
        onLens = onLens,
        onAnnotate = onAnnotate,
        onExpandCrossPage = onExpandCrossPage,
        onAddToVocabulary = onAddToVocabulary,
        onLookup = onLookup,
        isExpandingCrossPage = isExpandingCrossPage,
        japaneseFuriganaEnabled = japaneseFuriganaEnabled,
        onToggleFurigana = onToggleFurigana
    )

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val compact = maxWidth < CompactCardWidth
        val boundedHeight = constraints.hasBoundedHeight
        Column(modifier = Modifier.fillMaxWidth().heightIn(min = CardMinimumHeight)) {
            TranslationCardHeader(
                eyebrow = eyebrowText(state, compact, requestSourceLanguage, requestTargetLanguage),
                spokenEyebrow = spokenEyebrowText(state, requestSourceLanguage, requestTargetLanguage),
                isFailure = state is TranslationCardState.Failure,
                onDismiss = onDismiss,
                onDrag = onDrag,
                onDraggingChange = onDraggingChange
            )
            TranslationCardBody(
                state = state,
                compact = compact,
                loadingDetail = loadingDetail,
                onRetry = onRetry,
                onOpenSettings = onOpenSettings,
                onSaveAnalysis = onSaveAnalysis,
                translationContent = translationContent,
                // The header and the action row keep their 44dp; the body
                // scrolls in what is left, so a long card fills its maximum.
                modifier = if (boundedHeight) Modifier.weight(1f, fill = false) else Modifier
            )
            crossPageMessage?.let { message -> TranslationCardMessage(message) }
            TranslationCardActionRow(
                favorited = favorited,
                favoriteBounces = favoriteBounces,
                onStar = favorite?.let { save -> { save(true) } },
                menuGroups = menuGroups
            )
        }
    }
}

/**
 * The eyebrow over a drag zone that fills the header, with the close button
 * on top. The eyebrow has no pointer input, so touches on it reach the drag
 * zone and the two targets cannot overlap however narrow the card is.
 */
@Composable
private fun TranslationCardHeader(
    eyebrow: String,
    spokenEyebrow: String,
    isFailure: Boolean,
    onDismiss: () -> Unit,
    onDrag: ((Float, Float) -> Unit)?,
    onDraggingChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val palette = jerreaderPalette
    val dragHandler by rememberUpdatedState(onDrag)
    val draggingHandler by rememberUpdatedState(onDraggingChange)
    val eyebrowTint by animateColorAsState(
        targetValue = if (isFailure) palette.vermilion else palette.accent,
        animationSpec = JerreaderMotion.stateChange(),
        label = "translationEyebrow"
    )
    Box(modifier = modifier.fillMaxWidth().height(CardHeaderHeight)) {
        if (onDrag != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        try {
                            detectDragGestures(
                                onDragStart = { draggingHandler?.invoke(true) },
                                onDragEnd = { draggingHandler?.invoke(false) },
                                onDragCancel = { draggingHandler?.invoke(false) }
                            ) { change, amount ->
                                change.consume()
                                dragHandler?.invoke(amount.x, amount.y)
                            }
                        } finally {
                            // Leaving mid-drag must not keep the shadow off.
                            draggingHandler?.invoke(false)
                        }
                    }
                    .semantics { contentDescription = "移动翻译浮窗" }
            )
            // The 32×4 grabber that shows the card can be moved.
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .size(width = 32.dp, height = 4.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), CircleShape)
            )
        }
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(start = JerreaderSpacing.md, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(JerreaderSpacing.xs)
        ) {
            // `JerreaderEyebrow` clips; this one needs a tail ellipsis.
            Text(
                text = eyebrow,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = spokenEyebrow },
                color = eyebrowTint,
                fontSize = 12.sp,
                lineHeight = 14.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TranslationCardIconButton(
                icon = JerreaderIcon.CLOSE,
                label = "关闭翻译",
                onClick = onDismiss,
                circle = 28.dp,
                glyphSize = 15.dp
            )
        }
    }
}

/**
 * The translation, or the loading or failure state, with its iOS padding.
 * A new phase fades in and grows from 98.5% at the top; the body the card
 * opened with is already covered by the card's own reveal.
 */
@Composable
private fun TranslationCardBody(
    state: TranslationCardState,
    compact: Boolean,
    loadingDetail: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onSaveAnalysis: (() -> Unit)?,
    translationContent: (@Composable (TranslationCardState.Success) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val phase = phaseIdentity(state)
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
                .verticalScroll(rememberScrollState())
                .padding(
                    start = JerreaderSpacing.md,
                    end = JerreaderSpacing.md,
                    // The eyebrow already gives the text its top margin.
                    top = if (state is TranslationCardState.Success) 2.dp else 4.dp,
                    bottom = 14.dp
                ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (state) {
                is TranslationCardState.Loading -> TranslationLoadingContent(loadingDetail)

                is TranslationCardState.Success -> {
                    if (translationContent != null) {
                        translationContent(state)
                    } else {
                        Text(
                            text = state.result.translatedText,
                            modifier = Modifier.semantics {
                                contentDescription = "译文：${state.result.translatedText}"
                            },
                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.5.sp)
                        )
                    }
                    state.analysisText?.let { analysis ->
                        TranslationAnalysis(
                            title = state.analysisTitle,
                            detail = state.analysisDetail,
                            analysis = analysis,
                            onSave = onSaveAnalysis?.takeIf { state.analysisSavable }
                        )
                    }
                }

                is TranslationCardState.Failure -> TranslationFailureContent(
                    message = state.message,
                    compact = compact,
                    onRetry = onRetry,
                    onOpenSettings = onOpenSettings
                )
            }
        }
    }
}

@Composable
private fun TranslationLoadingContent(detail: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = "正在翻译" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JerreaderSpacing.sm)
    ) {
        ReaderChromeLoadingGlyph(JerreaderIcon.TRANSLATION_BUBBLE_FILLED, 38.dp)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("正在翻译…", style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = jerreaderPalette.secondaryText)
        }
    }
}

/**
 * The message and a 36dp tinted 重新翻译, as on iOS. Android keeps 翻译设置
 * beside it, because its failures often point at a missing service.
 */
@Composable
private fun TranslationFailureContent(
    message: String,
    compact: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        val settingsTint = MaterialTheme.colorScheme.onSurface
        val buttons: @Composable () -> Unit = {
            JerreaderTintedButton(text = "重新翻译", onClick = onRetry, icon = JerreaderIcon.RESTORE, height = 36.dp)
            JerreaderTintedButton(
                text = "翻译设置",
                onClick = onOpenSettings,
                icon = JerreaderIcon.GEAR,
                tint = settingsTint,
                height = 36.dp
            )
        }
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(JerreaderSpacing.xs)) { buttons() }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(JerreaderSpacing.xs)) { buttons() }
        }
    }
}

/**
 * Android shows the AI answer inside the card; it borrows the look of the iOS
 * explanation sheet: a 34dp accent tile with sparkles, the title and its basis,
 * then the answer in body text.
 */
@Composable
private fun TranslationAnalysis(
    title: String,
    detail: String?,
    analysis: String,
    onSave: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val palette = jerreaderPalette
    Column(
        modifier = modifier.fillMaxWidth().jerreaderReveal(order = 0),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        JerreaderDivider(color = palette.line)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(palette.accent, RoundedCornerShape(JerreaderRadius.small)),
                contentAlignment = Alignment.Center
            ) {
                JerreaderGlyph(JerreaderIcon.SPARKLES, palette.onAccent, Modifier.size(18.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium
                )
                detail?.let { basis ->
                    Text(basis, style = MaterialTheme.typography.labelSmall, color = palette.secondaryText)
                }
            }
        }
        Text(analysis, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.5.sp))
        onSave?.let { save ->
            JerreaderTintedButton(
                text = "保存到语法知识库",
                onClick = save,
                icon = JerreaderIcon.BOOKMARK_OUTLINE,
                height = 36.dp
            )
        }
    }
}

/** Short status lines (已复制, 已收藏…) stay in view above the action row. */
@Composable
private fun TranslationCardMessage(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = JerreaderSpacing.md, end = JerreaderSpacing.md, bottom = JerreaderSpacing.xs)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = jerreaderPalette.secondaryText,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold
    )
}

/** The star on the left and 更多 on the right, under a hairline inset 16dp. */
@Composable
private fun TranslationCardActionRow(
    favorited: Boolean,
    favoriteBounces: Int,
    onStar: (() -> Unit)?,
    menuGroups: List<List<CardMenuAction>>,
    modifier: Modifier = Modifier
) {
    val line = jerreaderPalette.line
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(CardActionRowHeight)
            .drawBehind {
                val inset = JerreaderSpacing.md.toPx()
                drawRect(
                    color = line,
                    topLeft = Offset(inset, 0f),
                    size = Size((size.width - inset * 2).coerceAtLeast(0f), 0.5.dp.toPx())
                )
            }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onStar != null) {
            TranslationCardIconButton(
                icon = if (favorited) JerreaderIcon.STAR_FILLED else JerreaderIcon.STAR_OUTLINE,
                label = if (favorited) "已收藏译文" else "收藏译文",
                onClick = onStar,
                isOn = favorited,
                bounceTrigger = favoriteBounces
            )
        }
        Spacer(Modifier.weight(1f))
        Box {
            TranslationCardIconButton(
                icon = JerreaderIcon.MORE,
                label = "更多翻译操作",
                onClick = { menuExpanded = true }
            )
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                menuGroups.forEachIndexed { index, group ->
                    if (index > 0) JerreaderDivider(color = line)
                    group.forEach { action ->
                        TranslationCardMenuItem(action = action, onDone = { menuExpanded = false })
                    }
                }
            }
        }
    }
}

/**
 * A round icon action on the card (iOS `ReaderCardIconButtonStyle`): a 32dp
 * wash of the text colour at 6%, 12% pressed, in a 44dp target; an "on" button
 * uses the accent fill and an accent glyph. Pressing shrinks it to 0.9.
 */
@Composable
private fun TranslationCardIconButton(
    icon: JerreaderIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    circle: Dp = 32.dp,
    glyphSize: Dp = 18.dp,
    isOn: Boolean = false,
    bounceTrigger: Any? = null
) {
    val palette = jerreaderPalette
    val wash = MaterialTheme.colorScheme.onSurface
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill by animateColorAsState(
        targetValue = when {
            isOn -> palette.accentFill
            pressed -> wash.copy(alpha = 0.12f)
            else -> wash.copy(alpha = 0.06f)
        },
        animationSpec = if (pressed) JerreaderMotion.press() else JerreaderMotion.stateChange(),
        label = "cardIconFill"
    )
    val tint by animateColorAsState(
        targetValue = if (isOn) palette.accent else palette.secondaryText,
        animationSpec = JerreaderMotion.stateChange(),
        label = "cardIconTint"
    )
    Box(
        modifier = modifier
            .size(CardHitSize)
            .semantics { contentDescription = label }
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(circle)
                .jerreaderPressScale(source, pressedScale = 0.9f)
                .drawBehind { drawCircle(fill) },
            contentAlignment = Alignment.Center
        ) {
            JerreaderGlyph(icon, tint, Modifier.size(glyphSize).jerreaderBounce(bounceTrigger))
        }
    }
}

/** One 更多翻译操作 entry; [checked] makes it a toggle (日语注音). */
private class CardMenuAction(
    val title: String,
    val icon: JerreaderIcon,
    val enabled: Boolean = true,
    val checked: Boolean? = null,
    val run: () -> Unit
)

@Composable
private fun TranslationCardMenuItem(action: CardMenuAction, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val iconTint = if (action.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.plainTertiary
    val checkTint = jerreaderPalette.accent
    DropdownMenuItem(
        text = { Text(action.title) },
        onClick = {
            onDone()
            action.run()
        },
        modifier = action.checked?.let { checked ->
            modifier.semantics { toggleableState = ToggleableState(checked) }
        } ?: modifier,
        leadingIcon = { JerreaderGlyph(action.icon, iconTint, Modifier.size(18.dp)) },
        trailingIcon = if (action.checked == true) {
            { JerreaderGlyph(JerreaderIcon.CHECK, checkTint, Modifier.size(16.dp)) }
        } else {
            null
        },
        enabled = action.enabled
    )
}

/**
 * The menu in the iOS order: the translation itself, then reading tools, then
 * 日语注音 and 重新翻译, with dividers between the groups that have entries.
 * Android keeps 复制译文 (iOS copies by selecting the text).
 */
private fun translationMenuGroups(
    state: TranslationCardState,
    favorite: ((Boolean) -> Unit)?,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onExplain: ((TranslationResult) -> Unit)?,
    onLens: ((TranslationResult) -> Unit)?,
    onAnnotate: ((String) -> Unit)?,
    onExpandCrossPage: (() -> Unit)?,
    onAddToVocabulary: (() -> Unit)?,
    onLookup: (() -> Unit)?,
    isExpandingCrossPage: Boolean,
    japaneseFuriganaEnabled: Boolean,
    onToggleFurigana: (() -> Unit)?
): List<List<CardMenuAction>> {
    val success = state as? TranslationCardState.Success
    val translation = buildList {
        if (success != null) {
            favorite?.let { save -> add(CardMenuAction("收藏译文", JerreaderIcon.STAR_OUTLINE) { save(false) }) }
            add(CardMenuAction("复制译文", JerreaderIcon.COPY) { onCopy(success.result.translatedText) })
        }
    }
    val tools = buildList {
        onAnnotate?.let { annotate ->
            add(CardMenuAction("添加笔记", JerreaderIcon.NOTE) { annotate(state.sourceText) })
        }
        if (success != null) {
            onExplain?.let { explain ->
                add(CardMenuAction("AI 句子结构分析", JerreaderIcon.SENTENCES) { explain(success.result) })
            }
            onLens?.let { lens ->
                add(CardMenuAction("AI 阅读透镜…", JerreaderIcon.SPARKLES) { lens(success.result) })
            }
        }
        onLookup?.let { lookup -> add(CardMenuAction("语境查词 / 修改词语", JerreaderIcon.DICTIONARY, run = lookup)) }
        onAddToVocabulary?.takeIf { state.sourceText.isSingleWord() }?.let { addWord ->
            add(CardMenuAction("加入生词本", JerreaderIcon.BOOKMARK_OUTLINE, run = addWord))
        }
        onExpandCrossPage?.let { expand ->
            add(
                CardMenuAction(
                    title = if (isExpandingCrossPage) "正在补齐…" else "扩展跨页句段",
                    icon = JerreaderIcon.CHEVRON_DOUBLE_RIGHT,
                    enabled = !isExpandingCrossPage,
                    run = expand
                )
            )
        }
    }
    val request = buildList {
        onToggleFurigana?.let { toggle ->
            add(CardMenuAction("日语注音", JerreaderIcon.TEXT_FORMAT, checked = japaneseFuriganaEnabled, run = toggle))
        }
        add(CardMenuAction("重新翻译", JerreaderIcon.RESTORE, run = onRetry))
    }
    return listOf(translation, tools, request).filter { it.isNotEmpty() }
}

private fun phaseIdentity(state: TranslationCardState): String {
    val phase = when (state) {
        is TranslationCardState.Loading -> "loading"
        is TranslationCardState.Success -> "success"
        is TranslationCardState.Failure -> "failure"
    }
    return "$phase:${state.sourceText}"
}

/** "日语 → 简体中文", "日 → 中" on a narrow card; 译文 before a direction is known. */
private fun eyebrowText(
    state: TranslationCardState,
    compact: Boolean,
    requestSource: LanguageCode?,
    requestTarget: LanguageCode?
): String = when (state) {
    is TranslationCardState.Loading ->
        requestTarget?.let { target -> directionText(requestSource, target, compact) } ?: "译文"
    is TranslationCardState.Success ->
        directionText(state.result.sourceLanguage, state.result.targetLanguage, compact)
    is TranslationCardState.Failure -> "翻译未完成"
}

/** "日语译为简体中文", or "自动识别语言，译为…". */
private fun spokenEyebrowText(
    state: TranslationCardState,
    requestSource: LanguageCode?,
    requestTarget: LanguageCode?
): String = when (state) {
    is TranslationCardState.Loading ->
        requestTarget?.let { target -> spokenDirection(requestSource, target) } ?: "译文"
    is TranslationCardState.Success ->
        spokenDirection(state.result.sourceLanguage, state.result.targetLanguage)
    is TranslationCardState.Failure -> "翻译未完成"
}

private fun directionText(source: LanguageCode?, target: LanguageCode, compact: Boolean): String {
    val from = source?.let { languageName(it, compact) } ?: "自动识别"
    return "$from → ${languageName(target, compact)}"
}

private fun spokenDirection(source: LanguageCode?, target: LanguageCode): String =
    if (source == null) {
        "自动识别语言，译为${languageName(target, compact = false)}"
    } else {
        "${languageName(source, compact = false)}译为${languageName(target, compact = false)}"
    }

/** The iOS `LanguageCode.displayName`, or one character on a narrow card. */
private fun languageName(language: LanguageCode, compact: Boolean): String = when (language) {
    LanguageCode.JAPANESE -> if (compact) "日" else "日语"
    LanguageCode.ENGLISH -> if (compact) "英" else "英语"
    LanguageCode.CHINESE_SIMPLIFIED -> if (compact) "中" else "简体中文"
}
