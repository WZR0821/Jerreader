package com.jerreader.shared.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jerreader.shared.library.ReaderInterfaceDefaults
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// The 2.0 reader bars, as iOS ReaderChromeViews.swift and the bars of
// EPUBReaderView. The sizes are iOS ReaderChromeMetrics.

/** The hit frame of every bar and card button. */
private val BarHitSize = 44.dp
/** The circle drawn behind a top-bar icon. */
private val BarIconCircle = 36.dp
/** Bar glyphs. */
private val BarIconSize = 22.dp
/** The bottom bar's 轻点翻译 circle; with the 44dp slots the bar stays as compact as in 1.7.0. */
private val BarTranslateCircle = 40.dp
private val BarThumbSize = 22.dp
private val BarTrackHeight = 4.dp
private val BarSliderHeight = 26.dp
/** How far beside the thumb a touch still picks it up. */
private val BarThumbSlop = 14.dp

/**
 * How much of the page colour a bar carries. iOS lays the page colour at 78%
 * over the system bar material, which Android does not have, so the bar is the
 * page colour at 92% instead; the user's 透明度 multiplies it as on iOS.
 */
private const val BarPageColorAlpha = 0.92f

/**
 * JerreaderMotion.stateChange for enter/exit transitions and content size
 * changes, which take a FiniteAnimationSpec.
 */
internal fun <T> readerTransitionSpec(): FiniteAnimationSpec<T> =
    (JerreaderMotion.stateChange<T>() as? FiniteAnimationSpec<T>) ?: spring(dampingRatio = 0.85f, stiffness = 500f)

/**
 * The reader's top bar, matching iOS `topChromeBar`: close, the book over its
 * chapter, then 目录, the bookmark, the AI companion and 阅读设置, on the
 * page colour with a hairline towards the page. [topInset] is the status-bar
 * height the bar paints behind, so the colour runs to the top edge.
 */
@Composable
fun ReaderTopChrome(
    chapterTitle: String,
    bookTitle: String,
    isBookmarked: Boolean,
    onClose: () -> Unit,
    onTableOfContents: () -> Unit,
    onToggleBookmark: () -> Unit,
    onAppearance: () -> Unit,
    pageColor: Color,
    modifier: Modifier = Modifier,
    transparency: Float = .15f,
    topInset: Dp = 0.dp,
    onCompanion: (() -> Unit)? = null,
    hasCompanionUpdate: Boolean = false,
    onInteraction: () -> Unit = {}
) {
    val haptics = LocalHapticFeedback.current
    // The bookmark is saved off the main thread, so the glyph bounces once
    // the page reports it bookmarked after a tap, not when a page turn lands
    // on an older bookmark.
    var bookmarkAdds by remember { mutableIntStateOf(0) }
    var awaitingBookmark by remember { mutableStateOf(false) }
    LaunchedEffect(isBookmarked) {
        if (isBookmarked && awaitingBookmark) bookmarkAdds += 1
        awaitingBookmark = false
    }
    ReaderBarSurface(
        pageColor = pageColor,
        transparency = transparency,
        edgeIsTop = true,
        maxContentWidth = 780.dp,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topInset)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ReaderBarIconButton(
                icon = JerreaderIcon.CLOSE,
                label = "关闭阅读器",
                onClick = {
                    onInteraction()
                    onClose()
                }
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp, end = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                Text(
                    text = bookTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = jerreaderPalette.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            ReaderBarIconButton(
                icon = JerreaderIcon.TABLE_OF_CONTENTS,
                label = "目录与阅读导航",
                onClick = {
                    onInteraction()
                    onTableOfContents()
                }
            )
            // Bookmarking is a one-tap, frequently repeated action whose state
            // is worth showing continuously. Adding one bounces the glyph with
            // a success haptic; removing one gives a light tap.
            ReaderBarIconButton(
                icon = if (isBookmarked) JerreaderIcon.BOOKMARK_FILLED else JerreaderIcon.BOOKMARK_OUTLINE,
                label = if (isBookmarked) "移除当前书签" else "添加当前书签",
                isOn = isBookmarked,
                selected = isBookmarked,
                bounceTrigger = bookmarkAdds,
                onClick = {
                    onInteraction()
                    if (isBookmarked) {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    } else {
                        awaitingBookmark = true
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    }
                    onToggleBookmark()
                }
            )
            if (onCompanion != null) {
                ReaderCompanionChromeButton(
                    hasUpdate = hasCompanionUpdate,
                    ringColor = pageColor,
                    onClick = {
                        onInteraction()
                        onCompanion()
                    }
                )
            }
            ReaderBarIconButton(
                icon = JerreaderIcon.TEXT_FORMAT,
                label = "阅读设置",
                onClick = {
                    onInteraction()
                    onAppearance()
                }
            )
        }
    }
}

/**
 * The reader's bottom bar, matching iOS `bottomChromeBar` (compact, as in
 * 1.7.0): one progress row — the chapter, the slider, the percentage — over
 * the page buttons around the 轻点翻译 circle. About 78dp with progress, plus
 * [bottomInset], the navigation-bar height the bar paints behind.
 *
 * Dragging the thumb only previews the chapter under it; the book moves once
 * the finger lifts, so it does not jump around mid-drag.
 */
@Composable
fun ReaderBottomChrome(
    chapterLabel: String,
    progress: Double,
    showProgress: Boolean,
    chapterLabelAt: (Double) -> String,
    onSeek: (Double) -> Unit,
    canGoToAdjacentChapter: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    isQuickTranslationEnabled: Boolean,
    onToggleQuickTranslation: () -> Unit,
    pageColor: Color,
    modifier: Modifier = Modifier,
    transparency: Float = .15f,
    bottomInset: Dp = 0.dp,
    onInteraction: () -> Unit = {},
    onScrubbingChanged: (Boolean) -> Unit = {},
    /** 本章还剩约 N 分钟 under the progress row (2.1.0 §9); null takes no room. */
    chapterEstimate: String? = null
) {
    var scrubbedProgress by remember { mutableStateOf<Double?>(null) }
    var scrubStart by remember { mutableStateOf<Double?>(null) }
    // After a drag the bar keeps showing where it is going until the book
    // reports its new place, so the label and the percentage do not flash back.
    var pendingSeek by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(progress) { pendingSeek = null }
    LaunchedEffect(pendingSeek) {
        if (pendingSeek != null) {
            delay(1_200)
            pendingSeek = null
        }
    }
    ReaderBarSurface(
        pageColor = pageColor,
        transparency = transparency,
        edgeIsTop = false,
        maxContentWidth = 660.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = bottomInset)
        ) {
            if (showProgress) {
                val scrubbing = scrubbedProgress
                val target = scrubbing ?: pendingSeek
                val shown = clampedProgress(target ?: progress)
                val percent = (shown * 100).roundToInt()
                val label = target?.let(chapterLabelAt) ?: chapterLabel
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 32.dp)
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // While dragging, the chapter the thumb is over matters far
                    // more than the raw percentage, so it takes the accent colour.
                    Text(
                        text = label,
                        modifier = Modifier.widthIn(max = 104.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (scrubbing == null) jerreaderPalette.secondaryText else jerreaderPalette.accent,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis
                    )
                    ReaderProgressSlider(
                        value = shown,
                        onValueChange = { scrubbedProgress = clampedProgress(it) },
                        onEditingChanged = { editing ->
                            onScrubbingChanged(editing)
                            if (editing) {
                                val start = scrubbedProgress ?: clampedProgress(progress)
                                scrubbedProgress = start
                                scrubStart = start
                            } else {
                                val end = scrubbedProgress
                                val start = scrubStart
                                scrubbedProgress = null
                                scrubStart = null
                                // Picking the thumb up and putting it back is not a move.
                                if (end != null && end != start) {
                                    pendingSeek = end
                                    onSeek(end)
                                }
                            }
                        },
                        accessibilityLabel = "快速调整阅读进度",
                        accessibilityValue = "$label，$percent%",
                        modifier = Modifier.weight(1f)
                    )
                    ReaderProgressPercent(percent = percent, rolls = scrubbing == null)
                }
                chapterEstimate?.takeIf(String::isNotBlank)?.let { estimate ->
                    Text(
                        text = estimate,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 6.dp, end = 6.dp, bottom = 2.dp)
                            .testTag("reader_chapter_estimate"),
                        style = JerreaderLearningType.caption,
                        color = jerreaderPalette.secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                if (canGoToAdjacentChapter) {
                    ReaderPageButton(
                        icon = JerreaderIcon.CHEVRON_DOUBLE_LEFT,
                        label = "上一章",
                        onClick = {
                            onInteraction()
                            onPreviousChapter()
                        }
                    )
                }
                ReaderPageButton(
                    icon = JerreaderIcon.CHEVRON_LEFT,
                    label = "上一页",
                    onClick = {
                        onInteraction()
                        onPreviousPage()
                    }
                )
                ReaderTranslateToggle(
                    isOn = isQuickTranslationEnabled,
                    onClick = {
                        onInteraction()
                        onToggleQuickTranslation()
                    }
                )
                ReaderPageButton(
                    icon = JerreaderIcon.CHEVRON_RIGHT,
                    label = "下一页",
                    onClick = {
                        onInteraction()
                        onNextPage()
                    }
                )
                if (canGoToAdjacentChapter) {
                    ReaderPageButton(
                        icon = JerreaderIcon.CHEVRON_DOUBLE_RIGHT,
                        label = "下一章",
                        onClick = {
                            onInteraction()
                            onNextChapter()
                        }
                    )
                }
            }
        }
    }
}

/**
 * The reader while Readium opens the book, as iOS: the pulsing book glyph,
 * 正在打开《…》 and the format line on a paper card.
 */
@Composable
fun ReaderLoadingView(title: String, detail: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(28.dp)
            .jerreaderPaperCard(RoundedCornerShape(JerreaderRadius.hero))
            .padding(26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ReaderChromeLoadingGlyph(icon = JerreaderIcon.BOOK_CLOSED, size = 68.dp)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.labelMedium,
            color = jerreaderPalette.secondaryText,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * A book that could not be opened, as iOS `readerFailure`: a vermilion tile,
 * 无法打开这本书, the reason, and 返回书架 beside 重新打开.
 */
@Composable
fun ReaderFailureView(
    message: String,
    onBackToLibrary: () -> Unit,
    onReopen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = jerreaderPalette
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically)
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .background(colors.vermilion.copy(alpha = 0.12f), RoundedCornerShape(JerreaderRadius.card)),
            contentAlignment = Alignment.Center
        ) {
            JerreaderGlyph(JerreaderIcon.WARNING, colors.vermilion, Modifier.size(30.dp))
        }
        Text(
            text = "无法打开这本书",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.secondaryText,
            textAlign = TextAlign.Center
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            JerreaderTintedButton(text = "返回书架", onClick = onBackToLibrary)
            JerreaderPrimaryButton(
                text = "重新打开",
                onClick = onReopen,
                icon = JerreaderIcon.RESTORE,
                height = 44.dp,
                fullWidth = false
            )
        }
    }
}

/**
 * iOS JerreaderLoadingGlyph: an accent halo that breathes (94%↔114%,
 * opacity 1↔0.46) around a tinted disc, the glyph rocking ±4°, 1.05 s each
 * way. With the system animations off (Reduce Motion) it stays still.
 */
@Composable
internal fun ReaderChromeLoadingGlyph(icon: JerreaderIcon, size: Dp, modifier: Modifier = Modifier) {
    val colors = jerreaderPalette
    val pulse = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        // A repeating animation at a zero duration scale would flicker every frame.
        if ((coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) <= 0f) return@LaunchedEffect
        pulse.animateTo(
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1_050, easing = LoadingGlyphEasing), RepeatMode.Reverse)
        )
    }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    val value = pulse.value
                    val scale = 0.94f + 0.20f * value
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - 0.54f * value
                }
                .background(colors.accent.copy(alpha = 0.10f), CircleShape)
        )
        Box(
            Modifier
                .size(size * 0.76f)
                .background(colors.accentFill, CircleShape)
        )
        JerreaderGlyph(
            icon = icon,
            tint = colors.accent,
            modifier = Modifier
                .size(size * 0.31f)
                .graphicsLayer { rotationZ = -4f + 8f * pulse.value }
        )
    }
}

/** SwiftUI's easeInOut. */
private val LoadingGlyphEasing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

private fun clampedProgress(value: Double): Double = if (value.isFinite()) value.coerceIn(0.0, 1.0) else 0.0

/**
 * The background of a bar: the page colour at [BarPageColorAlpha] times the
 * user's opacity, with a 0.5dp `line` hairline along the edge that faces the
 * page. It only draws, so the bar's measured frame never changes, and it is
 * not part of the accessibility tree.
 */
@Composable
private fun ReaderBarSurface(
    pageColor: Color,
    transparency: Float,
    edgeIsTop: Boolean,
    maxContentWidth: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val line = jerreaderPalette.line
    val opacity = ReaderInterfaceDefaults.backgroundOpacity(transparency)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(pageColor.copy(alpha = pageColor.alpha * BarPageColorAlpha * opacity))
                val hairline = 0.5.dp.toPx()
                drawRect(
                    color = line.copy(alpha = line.alpha * opacity),
                    topLeft = Offset(0f, if (edgeIsTop) size.height - hairline else 0f),
                    size = Size(size.width, hairline)
                )
            },
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            Modifier
                .widthIn(max = maxContentWidth)
                .fillMaxWidth()
        ) { content() }
    }
}

/**
 * iOS ReaderBarIconButtonStyle: a 22dp glyph in a 36dp circle inside a 44dp
 * hit frame. The circle is clear at rest, an 8% wash while pressed, and the
 * accent tint with an accent glyph while the button is on.
 */
@Composable
private fun ReaderBarIconButton(
    icon: JerreaderIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isOn: Boolean = false,
    selected: Boolean? = null,
    bounceTrigger: Any? = null
) {
    val colors = jerreaderPalette
    val primary = MaterialTheme.colorScheme.onSurface
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill by animateColorAsState(
        targetValue = when {
            isOn && pressed -> colors.accent.copy(alpha = 0.24f)
            isOn -> colors.accentFill
            pressed -> primary.copy(alpha = 0.08f)
            else -> primary.copy(alpha = 0f)
        },
        animationSpec = if (pressed) JerreaderMotion.press() else JerreaderMotion.stateChange(),
        label = "readerBarIconFill"
    )
    val tint by animateColorAsState(
        targetValue = if (isOn) colors.accent else primary,
        animationSpec = JerreaderMotion.stateChange(),
        label = "readerBarIconTint"
    )
    Box(
        modifier = modifier
            .size(BarHitSize)
            .semantics {
                contentDescription = label
                if (selected != null) this.selected = selected
            }
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(BarIconCircle)
                .jerreaderPressScale(source, pressedScale = 0.92f)
                .drawBehind { drawCircle(fill) },
            contentAlignment = Alignment.Center
        ) {
            JerreaderGlyph(
                icon = icon,
                tint = tint,
                modifier = Modifier
                    .size(BarIconSize)
                    .jerreaderBounce(bounceTrigger)
            )
        }
    }
}

/**
 * iOS ReaderCompanionChromeButton: SPARKLES and 「AI」 on an accent capsule;
 * a vermilion dot, ringed in the page colour, pops up while a recap or a
 * finished chapter waits. The capsule keeps its size at large text.
 */
@Composable
private fun ReaderCompanionChromeButton(
    hasUpdate: Boolean,
    ringColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = jerreaderPalette
    val density = LocalDensity.current
    val source = remember { MutableInteractionSource() }
    val textSize = if (density.fontScale > 1f) with(density) { 15.dp.toSp() } else 15.sp
    val lineHeight = if (density.fontScale > 1f) with(density) { 18.dp.toSp() } else 18.sp
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = BarHitSize, minHeight = BarHitSize)
            .jerreaderPressScale(source, pressedScale = 0.90f)
            .semantics { contentDescription = if (hasUpdate) "阅读伴读，有新的回顾" else "阅读伴读" }
            .testTag("reader_companion_button")
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box {
            Row(
                modifier = Modifier
                    .height(30.dp)
                    .background(colors.accentFill, CircleShape)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                JerreaderGlyph(JerreaderIcon.SPARKLES, colors.accent, Modifier.size(13.dp))
                Text(
                    text = "AI",
                    color = colors.accent,
                    fontSize = textSize,
                    lineHeight = lineHeight,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
            AnimatedVisibility(
                visible = hasUpdate,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-3).dp),
                enter = scaleIn(readerTransitionSpec(), initialScale = 0.4f) + fadeIn(readerTransitionSpec()),
                exit = scaleOut(readerTransitionSpec(), targetScale = 0.4f) + fadeOut(readerTransitionSpec()),
                label = "readerCompanionDot"
            ) {
                Box(
                    Modifier
                        .size(11.dp)
                        .background(ringColor, CircleShape)
                        .padding(1.5.dp)
                        .background(colors.vermilion, CircleShape)
                )
            }
        }
    }
}

/**
 * iOS ReaderPageButtonStyle: a page-turn glyph at 62% of the text colour,
 * with a 40dp wash while pressed. The whole slot is the hit area.
 */
@Composable
private fun RowScope.ReaderPageButton(
    icon: JerreaderIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.onSurface
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val wash by animateFloatAsState(
        targetValue = if (pressed) 0.08f else 0f,
        animationSpec = if (pressed) JerreaderMotion.press() else JerreaderMotion.release(),
        label = "readerPageButtonWash"
    )
    Box(
        modifier = modifier
            .weight(1f)
            .heightIn(min = BarHitSize)
            .semantics { contentDescription = label }
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .jerreaderPressScale(source, pressedScale = 0.92f)
                .drawBehind { drawCircle(primary.copy(alpha = wash.coerceIn(0f, 1f))) },
            contentAlignment = Alignment.Center
        ) {
            JerreaderGlyph(icon, primary.copy(alpha = 0.62f), Modifier.size(BarIconSize))
        }
    }
}

/**
 * iOS ReaderTranslateToggleButtonStyle, the bar's one solid accent control.
 * On: accent fill, the on-accent glyph and an accent-tinted e2 shadow. Off:
 * the accent tint with a 1dp accent ring and no shadow.
 */
@Composable
private fun RowScope.ReaderTranslateToggle(
    isOn: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = jerreaderPalette
    val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }
    val fill by animateColorAsState(
        targetValue = if (isOn) colors.accent else colors.accentFill,
        animationSpec = JerreaderMotion.stateChange(),
        label = "readerTranslateFill"
    )
    val glyphTint by animateColorAsState(
        targetValue = if (isOn) colors.onAccent else colors.accent,
        animationSpec = JerreaderMotion.stateChange(),
        label = "readerTranslateGlyph"
    )
    val ring by animateFloatAsState(
        targetValue = if (isOn) 0f else 0.28f,
        animationSpec = JerreaderMotion.stateChange(),
        label = "readerTranslateRing"
    )
    Box(
        modifier = modifier
            .weight(1f)
            .heightIn(min = BarHitSize)
            .semantics { contentDescription = if (isOn) "关闭轻点翻译" else "开启轻点翻译" }
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(BarTranslateCircle)
                .jerreaderPressScale(source, pressedScale = 0.92f)
                .then(
                    if (isOn) {
                        Modifier.jerreaderElevation(JerreaderElevation.RAISED, CircleShape, colors.accent)
                    } else {
                        Modifier
                    }
                )
                .background(fill, CircleShape)
                .border(1.dp, colors.accent.copy(alpha = ring.coerceIn(0f, 1f)), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            JerreaderGlyph(
                icon = if (isOn) JerreaderIcon.TRANSLATION_BUBBLE_FILLED else JerreaderIcon.SPEECH_BUBBLE,
                tint = glyphTint,
                modifier = Modifier
                    .size(BarIconSize)
                    .jerreaderBounce(isOn)
            )
        }
    }
}

/**
 * The percentage beside the slider, in tabular figures; it rolls like iOS
 * numericText when the page moves, and follows a drag without rolling.
 */
@Composable
private fun ReaderProgressPercent(percent: Int, rolls: Boolean, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = percent,
        modifier = modifier.widthIn(min = 34.dp),
        contentAlignment = Alignment.CenterEnd,
        transitionSpec = {
            if (!rolls) {
                (EnterTransition.None togetherWith ExitTransition.None).using(SizeTransform(clip = false))
            } else {
                val up = targetState > initialState
                (slideInVertically(readerTransitionSpec()) { if (up) it / 2 else -it / 2 } + fadeIn(readerTransitionSpec()))
                    .togetherWith(
                        slideOutVertically(readerTransitionSpec()) { if (up) -it / 2 else it / 2 } +
                            fadeOut(readerTransitionSpec())
                    )
                    .using(SizeTransform(clip = false))
            }
        },
        label = "readerProgressPercent"
    ) { shown ->
        Text(
            text = "$shown%",
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold,
            color = jerreaderPalette.secondaryText,
            textAlign = TextAlign.End,
            maxLines = 1
        )
    }
}

/**
 * iOS ReaderProgressSlider: a 4dp `line` track, an accent fill and a 22dp
 * white thumb with e1. As with UISlider, only a touch that lands on the thumb
 * (with 14dp of slop either side) moves it, and the thumb keeps its offset
 * from the finger, so a tap on the track never moves the book.
 * [onEditingChanged] gets `true` when a drag picks the thumb up and `false`
 * once when it ends or is cancelled. TalkBack sees a slider with the same
 * label and value, and adjusting it seeks at once.
 */
@Composable
private fun ReaderProgressSlider(
    value: Double,
    onValueChange: (Double) -> Unit,
    onEditingChanged: (Boolean) -> Unit,
    accessibilityLabel: String,
    accessibilityValue: String,
    modifier: Modifier = Modifier
) {
    val colors = jerreaderPalette
    val fraction = clampedProgress(value).toFloat()
    val latestFraction by rememberUpdatedState(fraction)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnEditingChanged by rememberUpdatedState(onEditingChanged)
    var grabbed by remember { mutableStateOf(false) }
    val thumbScale by animateFloatAsState(
        targetValue = if (grabbed) 1.12f else 1f,
        animationSpec = if (grabbed) JerreaderMotion.press() else JerreaderMotion.release(),
        label = "readerProgressThumb"
    )
    BoxWithConstraints(
        modifier = modifier
            .height(BarSliderHeight)
            .semantics {
                contentDescription = accessibilityLabel
                stateDescription = accessibilityValue
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { target ->
                    latestOnEditingChanged(true)
                    latestOnValueChange(target.coerceIn(0f, 1f).toDouble())
                    latestOnEditingChanged(false)
                    true
                }
            }
            .pointerInput(Unit) {
                val thumb = BarThumbSize.toPx()
                val slop = BarThumbSlop.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val usable = max(size.width - thumb, 1f)
                    val center = thumb / 2 + usable * latestFraction
                    // A touch that misses the thumb is ignored until it ends.
                    if (abs(down.position.x - center) > thumb / 2 + slop) return@awaitEachGesture
                    val grabOffset = down.position.x - center
                    down.consume()
                    grabbed = true
                    latestOnEditingChanged(true)
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            if (change.positionChange() != Offset.Zero) change.consume()
                            val next = ((change.position.x - grabOffset - thumb / 2) / usable).coerceIn(0f, 1f)
                            if (next != latestFraction) latestOnValueChange(next.toDouble())
                        }
                    } finally {
                        grabbed = false
                        latestOnEditingChanged(false)
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val thumbPx = with(LocalDensity.current) { BarThumbSize.toPx() }
        val usablePx = max(constraints.maxWidth - thumbPx, 1f)
        Canvas(Modifier.matchParentSize()) {
            val thumb = BarThumbSize.toPx()
            val track = BarTrackHeight.toPx()
            val usable = max(size.width - thumb, 1f)
            val top = (size.height - track) / 2
            val radius = CornerRadius(track / 2, track / 2)
            drawRoundRect(colors.line, topLeft = Offset(thumb / 2, top), size = Size(usable, track), cornerRadius = radius)
            if (fraction > 0f) {
                drawRoundRect(
                    colors.accent,
                    topLeft = Offset(thumb / 2, top),
                    size = Size(usable * fraction, track),
                    cornerRadius = radius
                )
            }
        }
        Box(
            Modifier
                .offset { IntOffset((usablePx * fraction).roundToInt(), 0) }
                .size(BarThumbSize)
                .graphicsLayer {
                    scaleX = thumbScale
                    scaleY = thumbScale
                }
                .jerreaderElevation(JerreaderElevation.CARD, CircleShape)
                .background(Color.White, CircleShape)
                .border(0.5.dp, Color.Black.copy(alpha = 0.06f), CircleShape)
        )
    }
}
