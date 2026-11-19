package com.jerreader.shared.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jerreader.shared.learning.ChapterClosurePlan
import com.jerreader.shared.learning.ChapterClosurePrompt
import com.jerreader.shared.learning.ContinuationRecapPlan
import com.jerreader.shared.learning.ReadingCompanionMode
import com.jerreader.shared.learning.ReadingConfusionMap
import com.jerreader.shared.translation.AIReadingLensMode

/** The inner padding of every companion card, as iOS `jerreaderPaperCard(padding: 18)`. */
private val CompanionCardPadding = 18.dp

/**
 * Android counterpart of the iOS 2.0 ReaderCompanionSheet: the 伴读 mode as
 * pills, a recap of where the reader was, the confusion map for this book and
 * the 90-second chapter loop, the last three rising in one after another.
 * Words have no saved locator, so "back to the text" runs a book search.
 */
@Composable
fun ReaderCompanionPanel(
    mode: ReadingCompanionMode,
    recap: ContinuationRecapPlan,
    confusion: ReadingConfusionMap,
    closureChapterTitle: String,
    onModeChange: (ReadingCompanionMode) -> Unit,
    onSearchTerm: (String) -> Unit,
    onStartClosure: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    CompanionSheet(
        title = "${mode.title}伴读",
        actionTitle = "继续阅读",
        onAction = onDismiss,
        modifier = modifier.testTag("reader_companion_panel")
    ) {
        CompanionModePicker(mode = mode, onModeChange = onModeChange)
        CompanionRecapCard(recap = recap, modifier = Modifier.jerreaderReveal(order = 0))
        ReadingConfusionMapCard(
            confusion = confusion,
            onSearchTerm = onSearchTerm,
            modifier = Modifier.jerreaderReveal(order = 1)
        )
        JerreaderPrimaryButton(
            text = "90 秒章节闭环 · $closureChapterTitle",
            onClick = onStartClosure,
            modifier = Modifier.jerreaderReveal(order = 2).testTag("start_chapter_closure"),
            icon = JerreaderIcon.CLOCK
        )
    }
}

/** Where the reader was: a title over bullets that each start with an accent arrow. */
@Composable
private fun CompanionRecapCard(recap: ContinuationRecapPlan, modifier: Modifier = Modifier) {
    val accent = jerreaderPalette.accent
    Column(
        modifier = modifier
            .fillMaxWidth()
            .jerreaderPaperCard()
            .padding(CompanionCardPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(recap.title, style = MaterialTheme.typography.titleLarge)
        recap.bullets.forEach { bullet ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecapBulletGlyph(tint = accent, modifier = Modifier.padding(top = 1.dp))
                Text(bullet, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * The iOS bullet `arrow.forward.circle`, which the drawn icon set lacks: a
 * ring around a forward arrow, sized to the subheadline's first line.
 */
@Composable
private fun RecapBulletGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val stroke = 1.25.dp.toPx()
        val extent = size.minDimension
        drawCircle(tint, radius = (extent - stroke) / 2f, style = Stroke(stroke))
        val half = extent * 0.21f
        val head = extent * 0.16f
        val tip = Offset(center.x + half, center.y)
        drawLine(tint, Offset(center.x - half, center.y), tip, stroke, StrokeCap.Round)
        drawLine(tint, tip, Offset(tip.x - head, tip.y - head), stroke, StrokeCap.Round)
        drawLine(tint, tip, Offset(tip.x - head, tip.y + head), stroke, StrokeCap.Round)
    }
}

/**
 * The confusion map, shared by the reader panel and the learning page. With
 * [showBookTitle] each item names its book, for the page that lists them all.
 */
@Composable
fun ReadingConfusionMapCard(
    confusion: ReadingConfusionMap,
    onSearchTerm: ((String) -> Unit)? = null,
    showBookTitle: Boolean = false,
    modifier: Modifier = Modifier
) {
    // The iOS 2.0 card: an 18dp paper card whose parts are all 10dp apart. It
    // sets its own ink, as the learning page puts it on no Surface.
    val colors = jerreaderPalette
    val type = MaterialTheme.typography
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .jerreaderPaperCard()
                .padding(CompanionCardPadding)
                .testTag("reading_confusion_map"),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JerreaderGlyph(JerreaderIcon.SPARKLES, colors.accent, Modifier.size(16.dp))
                Text("AI 困惑地图", style = type.titleLarge)
            }
            Text(confusion.summary, style = type.bodyMedium, color = colors.secondaryText)
            confusion.items.forEach { item ->
                JerreaderDivider(Modifier.padding(vertical = 2.dp), color = colors.line)
                // SwiftUI puts its 8pt spacing on both sides of the 8pt minimum spacer.
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(
                        item.displayTerm,
                        modifier = Modifier.weight(1f).alignByBaseline(),
                        style = type.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        item.kind.title,
                        modifier = Modifier
                            .alignByBaseline()
                            .background(colors.accentFill, CircleShape)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        style = type.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.accent
                    )
                }
                Text(
                    if (showBookTitle && item.sourceBookTitle.isNotBlank()) {
                        "《${item.sourceBookTitle}》 · ${item.reason}"
                    } else {
                        item.reason
                    },
                    style = type.labelMedium,
                    color = colors.secondaryText
                )
                if (item.sentenceContext.isNotBlank()) {
                    // The original sentence, set off like a quotation.
                    Text(
                        item.sentenceContext,
                        modifier = Modifier
                            .quotationBar(colors.accent.copy(alpha = 0.35f))
                            .padding(start = 10.dp),
                        style = type.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (onSearchTerm != null) {
                    JerreaderTintedButton(
                        text = "在书中查找",
                        onClick = { onSearchTerm(item.displayTerm) },
                        icon = JerreaderIcon.SEARCH,
                        height = 34.dp
                    )
                }
            }
            Text(
                "只根据重复查词、真实原句和复习结果生成，不调用网络。",
                modifier = Modifier.padding(top = 2.dp),
                style = type.labelSmall,
                color = colors.secondaryText
            )
        }
    }
}

/** A 2dp rounded bar down the leading edge, as the iOS quotation capsule. */
private fun Modifier.quotationBar(color: Color): Modifier = this.drawBehind {
    val width = 2.dp.toPx()
    drawRoundRect(color, size = Size(width, size.height), cornerRadius = CornerRadius(width / 2f))
}

/** Android counterpart of the iOS 2.0 ReaderChapterClosureSheet. */
@Composable
fun ReaderChapterClosurePanel(
    plan: ChapterClosurePlan,
    onSearchTerm: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    CompanionSheet(
        title = "${plan.estimatedSeconds} 秒章节闭环",
        actionTitle = "完成",
        onAction = onDismiss,
        modifier = modifier.testTag("reader_chapter_closure_panel"),
        spacing = 14.dp
    ) {
        Text(
            "${plan.chapterTitle} · 每步 30 秒，先回想，再回到原文验证。",
            style = MaterialTheme.typography.bodyMedium,
            color = jerreaderPalette.secondaryText
        )
        plan.prompts.forEachIndexed { index, prompt ->
            ClosurePromptCard(
                step = index + 1,
                prompt = prompt,
                onSearchTerm = onSearchTerm,
                modifier = Modifier.jerreaderReveal(order = index)
            )
        }
    }
}

/** One step of the loop: its number on an accent disc, the prompt, and a search back into the book. */
@Composable
private fun ClosurePromptCard(
    step: Int,
    prompt: ChapterClosurePrompt,
    onSearchTerm: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = jerreaderPalette
    Column(
        modifier = modifier
            .fillMaxWidth()
            .jerreaderPaperCard()
            .padding(CompanionCardPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // The step number: a 24dp accent disc on the title's baseline, silent to TalkBack.
            Text(
                "$step",
                modifier = Modifier
                    .alignByBaseline()
                    .sizeIn(minWidth = 24.dp, minHeight = 24.dp)
                    .background(colors.accentFill, CircleShape)
                    .wrapContentSize()
                    .clearAndSetSemantics { },
                style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.Bold,
                color = colors.accent
            )
            Text(
                prompt.title,
                modifier = Modifier.alignByBaseline(),
                style = MaterialTheme.typography.titleMedium,
                color = colors.accent
            )
        }
        Text(prompt.prompt, style = MaterialTheme.typography.bodyLarge)
        prompt.searchTerm?.let { term ->
            JerreaderTintedButton(
                text = "在书中查找“$term”",
                onClick = { onSearchTerm(term) },
                modifier = Modifier.padding(top = 2.dp),
                icon = JerreaderIcon.SEARCH,
                height = 36.dp
            )
        }
    }
}

/**
 * The lens questions offered on a translated passage. The companion mode is
 * switched here too, because it changes how every lens question is asked.
 * iOS asks for a lens from the selection menu and switches lenses with chips
 * in its explanation sheet; Android picks one here, from a 2.0 card list.
 */
@Composable
fun AIReadingLensChooser(
    companionMode: ReadingCompanionMode,
    onChoose: (AIReadingLensMode) -> Unit,
    onModeChange: (ReadingCompanionMode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = jerreaderPalette
    val shape = RoundedCornerShape(JerreaderRadius.card)
    CompanionSheet(
        title = "AI 阅读透镜",
        actionTitle = "取消",
        onAction = onDismiss,
        modifier = modifier.testTag("ai_reading_lens_chooser"),
        actionCancels = true
    ) {
        CompanionModePicker(mode = companionMode, onModeChange = onModeChange)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .jerreaderPaperCard(shape)
                .clip(shape)
        ) {
            AIReadingLensMode.entries.forEachIndexed { index, mode ->
                if (index > 0) {
                    JerreaderDivider(Modifier.padding(start = JerreaderSpacing.md), color = colors.line)
                }
                LensRow(mode = mode, onClick = { onChoose(mode) })
            }
        }
        Text(
            "只依据选中文字和有限前后文，不剧透。",
            modifier = Modifier.padding(horizontal = JerreaderSpacing.xxs),
            style = MaterialTheme.typography.labelSmall,
            color = colors.secondaryText
        )
    }
}

/** A lens as a 2.0 card row: its title over what it explains, and a chevron. */
@Composable
private fun LensRow(mode: AIReadingLensMode, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("ai_reading_lens_${mode.storageId}")
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = JerreaderSpacing.md, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(JerreaderSpacing.sm)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(mode.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                mode.detail,
                style = MaterialTheme.typography.labelMedium,
                color = jerreaderPalette.secondaryText
            )
        }
        JerreaderGlyph(JerreaderIcon.CHEVRON_FORWARD, MaterialTheme.colorScheme.plainTertiary, Modifier.size(14.dp))
    }
}

/** 小说伴读 and 非虚构伴读 as 2.0 pills, which tick their own selection haptic. */
@Composable
private fun CompanionModePicker(
    mode: ReadingCompanionMode,
    onModeChange: (ReadingCompanionMode) -> Unit,
    modifier: Modifier = Modifier
) {
    JerreaderPillPicker(
        options = ReadingCompanionMode.entries.map { JerreaderPillOption(it, "${it.title}伴读") },
        selected = mode,
        onSelect = onModeChange,
        modifier = modifier
    )
}

/**
 * The frame of a companion sheet, as the iOS NavigationStack sheets: an
 * inline title with its action, then the content scrolling on the canvas with
 * 20dp margins, no wider than 720dp.
 */
@Composable
private fun CompanionSheet(
    title: String,
    actionTitle: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionCancels: Boolean = false,
    spacing: Dp = JerreaderSpacing.md,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 720.dp)
            .jerreaderCanvasBackground()
    ) {
        CompanionSheetBar(title = title, actionTitle = actionTitle, onAction = onAction, actionCancels = actionCancels)
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(JerreaderSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing),
                content = content
            )
        }
    }
}

/**
 * The inline bar: the centred title, a confirming action at the end in
 * semibold accent, or a cancelling one at the start in regular weight, as
 * SwiftUI places `confirmationAction` and `cancellationAction`.
 */
@Composable
private fun CompanionSheetBar(
    title: String,
    actionTitle: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    actionCancels: Boolean = false
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = JerreaderSpacing.xs)
    ) {
        Text(
            title,
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 200.dp)
                .semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Box(
            modifier = Modifier
                .align(if (actionCancels) Alignment.CenterStart else Alignment.CenterEnd)
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClick = onAction)
                .padding(horizontal = JerreaderSpacing.xs),
            contentAlignment = Alignment.Center
        ) {
            Text(
                actionTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (actionCancels) FontWeight.Normal else FontWeight.SemiBold,
                color = jerreaderPalette.accent,
                maxLines = 1
            )
        }
    }
}
