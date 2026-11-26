package com.jerreader.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jerreader.android.learning.WordReviewSnapshot
import com.jerreader.shared.learning.ReadingConfusionItem
import com.jerreader.shared.learning.ReadingConfusionKind
import com.jerreader.shared.learning.ReadingConfusionMap
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderIconTile
import com.jerreader.shared.ui.JerreaderLearningChip
import com.jerreader.shared.ui.JerreaderLearningMotion
import com.jerreader.shared.ui.JerreaderLearningSectionHeading
import com.jerreader.shared.ui.JerreaderLearningType
import com.jerreader.shared.ui.JerreaderMotion
import com.jerreader.shared.ui.JerreaderProgressRing
import com.jerreader.shared.ui.JerreaderRadius
import com.jerreader.shared.ui.JerreaderStatValue
import com.jerreader.shared.ui.jerreaderHeroBackground
import com.jerreader.shared.ui.jerreaderPalette
import com.jerreader.shared.ui.jerreaderPaperCard
import com.jerreader.shared.ui.jerreaderQuoteBar
import com.jerreader.shared.ui.jerreaderTabularDigits
import com.jerreader.shared.ui.plainSecondary
import com.jerreader.shared.ui.plainTertiary
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * iOS `LearningPracticeView`: the 今日练习 hero with today's review ring, the
 * 困惑地图 when the reading left one, and the 练习与积累 tools in one card.
 * It reads the existing collections; there is no separate learning store.
 */
@Composable
fun LearningPracticeScreen(
    wordCount: Int,
    sentenceCount: Int,
    modifier: Modifier = Modifier,
    confusion: ReadingConfusionMap? = null,
    reviewInputs: List<Pair<String, String?>> = emptyList(),
    onOpen: (String) -> Unit
) {
    val palette = jerreaderPalette
    // The ring reads the schedules the review page uses, decoded off the main
    // thread, and again whenever one of them changes.
    val progress by produceState(LearningReviewProgress(), reviewInputs) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        value = withContext(Dispatchers.Default) {
            LearningReviewProgress.of(WordReviewSnapshot.create(reviewInputs, checkpoint = { ensureActive() }), now, zone)
        }
    }
    LearningPage(modifier) {
        item(key = "hero") {
            PracticeHero(
                wordCount = wordCount,
                sentenceCount = sentenceCount,
                progress = progress,
                onReview = { onOpen("review") }
            )
        }
        if (confusion != null) {
            item(key = "confusion") { LearningConfusionMapCard(confusion) }
        }
        item(key = "tools-heading") { JerreaderLearningSectionHeading(title = "练习与积累") }
        item(key = "tools") {
            val shape = RoundedCornerShape(JerreaderRadius.card)
            Column(Modifier.fillMaxWidth().jerreaderPaperCard(shape).clip(shape)) {
                PracticeToolRow("原句练习卡", "用收藏的原句回忆词语与表达", JerreaderIcon.SENTENCES, palette.accent, { onOpen("sentences") })
                PracticeToolDivider()
                PracticeToolRow("语法知识库", "整理解释、标签与原书出处", JerreaderIcon.NOTE, palette.vermilion, { onOpen("grammar") })
                PracticeToolDivider()
                PracticeToolRow("阅读成长报告", "回顾本周阅读与复习记录", JerreaderIcon.CHART, palette.jade, { onOpen("growth") })
            }
        }
    }
}

/**
 * iOS `LearningDailyReviewProgress`: the words reviewed today out of today's
 * goal, which is those words plus the ones still due.
 */
private data class LearningReviewProgress(val reviewed: Int = 0, val goal: Int = 0) {
    val fraction: Float
        get() = if (goal > 0) minOf(reviewed.toFloat() / goal, 1f) else 0f

    companion object {
        fun of(snapshot: WordReviewSnapshot, now: Long, zone: ZoneId): LearningReviewProgress {
            val start = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
            var reviewed = 0
            var goal = 0
            snapshot.states.values.forEach { state ->
                // Answers are appended in time order, so the last one is the latest.
                val reviewedToday = (state.answers.lastOrNull()?.at ?: Long.MIN_VALUE) >= start
                val due = !state.paused && state.dueAt <= now
                if (reviewedToday) reviewed++
                if (reviewedToday || due) goal++
            }
            return LearningReviewProgress(reviewed, goal)
        }
    }
}

/** 今日练习: the ink hero card with today's ring, the two collections and the white 开始复习 capsule. */
@Composable
private fun PracticeHero(
    wordCount: Int,
    sentenceCount: Int,
    progress: LearningReviewProgress,
    onReview: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .jerreaderHeroBackground()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "今日练习",
                style = JerreaderLearningType.title2.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "从阅读中积累，每天巩固一点。",
                style = JerreaderLearningType.subheadline,
                color = Color.White.copy(alpha = 0.75f)
            )
        }
        PracticeHeroStats {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                LearningReviewRing(progress)
                Text(
                    text = "今日已复习",
                    modifier = Modifier.clearAndSetSemantics { },
                    style = JerreaderLearningType.caption,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1
                )
            }
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                PracticeCount("收藏词语", wordCount, JerreaderIcon.BOOKMARK_FILLED)
                PracticeCount("原句收藏", sentenceCount, JerreaderIcon.SENTENCES)
            }
        }
        LearningHeroButton(text = "开始复习", icon = JerreaderIcon.REVIEW, onClick = onReview)
        Text(
            text = "先回忆，再看释义；每组最多 10 个词。",
            style = JerreaderLearningType.footnote,
            color = Color.White.copy(alpha = 0.7f)
        )
    }
}

/** iOS `ViewThatFits`: the ring and the counts side by side 20 apart when they fit, otherwise stacked 16 apart. */
@Composable
private fun PracticeHeroStats(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val gaps = (placeables.size - 1).coerceAtLeast(0)
        val rowGap = 20.dp.roundToPx()
        val rowWidth = placeables.sumOf { it.width } + rowGap * gaps
        if (rowWidth <= constraints.maxWidth) {
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout(rowWidth.coerceAtLeast(constraints.minWidth), height) {
                var x = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(x, (height - placeable.height) / 2)
                    x += placeable.width + rowGap
                }
            }
        } else {
            val columnGap = 16.dp.roundToPx()
            val width = placeables.maxOfOrNull { it.width } ?: 0
            val height = placeables.sumOf { it.height } + columnGap * gaps
            layout(width.coerceAtLeast(constraints.minWidth), height) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + columnGap
                }
            }
        }
    }
}

/** A collection count on the hero: 28 white over a 72% label, at least 64 wide. */
@Composable
private fun PracticeCount(label: String, value: Int, icon: JerreaderIcon, modifier: Modifier = Modifier) {
    JerreaderStatValue(
        value = value,
        label = label,
        modifier = modifier.widthIn(min = 64.dp),
        icon = icon,
        tint = Color.White.copy(alpha = 0.8f),
        foreground = Color.White,
        secondary = Color.White.copy(alpha = 0.72f),
        size = 28
    )
}

/**
 * iOS `LearningReviewRing`: a white ring on a 22% track with 已复习 / 目标
 * inside, 76 across at the default text size and growing with title2 up to 132.
 */
@Composable
private fun LearningReviewRing(progress: LearningReviewProgress, modifier: Modifier = Modifier) {
    val side = with(LocalDensity.current) { 22.sp.toDp() * (76f / 22f) }.coerceAtMost(132.dp)
    Box(
        modifier = modifier
            .size(side)
            .clearAndSetSemantics { contentDescription = "今日已复习 ${progress.reviewed} / ${progress.goal} 个" },
        contentAlignment = Alignment.Center
    ) {
        JerreaderProgressRing(
            progress = progress.fraction,
            modifier = Modifier.matchParentSize(),
            tint = Color.White,
            track = Color.White.copy(alpha = 0.22f),
            lineWidth = 6.dp
        )
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RingNumber(
                value = progress.reviewed,
                prefix = "",
                style = JerreaderLearningType.title2.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
            RingNumber(
                value = progress.goal,
                prefix = "/ ",
                style = JerreaderLearningType.caption.copy(fontWeight = FontWeight.SemiBold),
                color = Color.White.copy(alpha = 0.72f)
            )
        }
    }
}

/** A number in the ring: tabular digits that roll like `numericText` and shrink to half before clipping. */
@Composable
private fun RingNumber(value: Int, prefix: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            val up = targetState > initialState
            (slideInVertically(JerreaderMotion.stateChange()) { if (up) it / 2 else -it / 2 } + fadeIn())
                .togetherWith(slideOutVertically(JerreaderMotion.stateChange()) { if (up) -it / 2 else it / 2 } + fadeOut())
        },
        label = "ring-number"
    ) { shown ->
        Text(
            text = "$prefix$shown",
            color = color,
            autoSize = TextAutoSize.StepBased(minFontSize = style.fontSize * 0.5f, maxFontSize = style.fontSize),
            maxLines = 1,
            softWrap = false,
            style = style.jerreaderTabularDigits()
        )
    }
}

/** iOS `LearningHeroButtonStyle`: an accent-deep headline on a white capsule, pressed to 97% and 92% opacity. */
@Composable
private fun LearningHeroButton(text: String, icon: JerreaderIcon, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = jerreaderPalette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (pressed) JerreaderMotion.press() else JerreaderMotion.release(),
        label = "hero-button-press"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = 1f - 0.03f * press
                scaleY = 1f - 0.03f * press
                alpha = 1f - 0.08f * press
            }
            .background(Color.White, CircleShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        JerreaderGlyph(icon, palette.accentDeep, Modifier.size(18.dp))
        Text(
            text = text,
            style = JerreaderLearningType.headline,
            color = palette.accentDeep,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * iOS `LearningConfusionMapCard`: the map on the practice page, naming each
 * item's book. The reader keeps its own card.
 */
@Composable
private fun LearningConfusionMapCard(confusion: ReadingConfusionMap, modifier: Modifier = Modifier) {
    val palette = jerreaderPalette
    val secondary = MaterialTheme.colorScheme.plainSecondary
    Column(
        modifier = modifier
            .fillMaxWidth()
            .jerreaderPaperCard(RoundedCornerShape(JerreaderRadius.card))
            .padding(16.dp)
            .testTag("reading_confusion_map"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JerreaderIconTile(JerreaderIcon.QUESTION, tint = palette.vermilion)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "困惑地图", style = JerreaderLearningType.headline)
                Text(text = confusion.summary, style = JerreaderLearningType.subheadline, color = secondary)
            }
        }
        confusion.items.forEach { item ->
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(palette.line))
            ConfusionMapItem(item)
        }
        Text(
            text = "只根据重复查词、真实原句和复习结果生成，不调用网络。",
            style = JerreaderLearningType.caption2,
            color = secondary
        )
    }
}

/** One item of the map: the term and its kind chip, the book and reason, then the sentence behind a quote bar. */
@Composable
private fun ConfusionMapItem(item: ReadingConfusionItem, modifier: Modifier = Modifier) {
    val tint = confusionTint(item.kind)
    val secondary = MaterialTheme.colorScheme.plainSecondary
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = item.displayTerm,
                modifier = Modifier.weight(1f).alignByBaseline(),
                style = JerreaderLearningType.body.copy(fontWeight = FontWeight.SemiBold)
            )
            JerreaderLearningChip(text = item.kind.title, modifier = Modifier.alignByBaseline(), tint = tint)
        }
        Text(
            text = if (item.sourceBookTitle.isEmpty()) item.reason else "《${item.sourceBookTitle}》 · ${item.reason}",
            style = JerreaderLearningType.footnote,
            color = secondary
        )
        if (item.sentenceContext.isNotEmpty()) {
            Text(
                text = item.sentenceContext,
                modifier = Modifier.jerreaderQuoteBar(tint.copy(alpha = 0.45f), inset = 10.dp, width = 2.dp),
                style = JerreaderLearningType.footnote,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun confusionTint(kind: ReadingConfusionKind): Color {
    val palette = jerreaderPalette
    return when (kind) {
        ReadingConfusionKind.GRAMMAR -> palette.vermilion
        ReadingConfusionKind.RECALL -> palette.gold
        ReadingConfusionKind.WORD_MEANING -> palette.accent
    }
}

/**
 * A tool row inside its card: a 40 tile, headline over a footnote, and the
 * disclosure chevron; a muted wash while pressed and no scaling, as on iOS.
 */
@Composable
private fun PracticeToolRow(
    title: String,
    detail: String,
    icon: JerreaderIcon,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = jerreaderPalette
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val wash by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = JerreaderLearningMotion.quick(),
        label = "tool-row-wash"
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(palette.mutedSurface.copy(alpha = palette.mutedSurface.alpha * 0.7f * wash))
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .heightIn(min = 68.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        JerreaderIconTile(icon, tint = tint, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(text = title, style = JerreaderLearningType.headline)
            Text(text = detail, style = JerreaderLearningType.footnote, color = MaterialTheme.colorScheme.plainSecondary)
        }
        JerreaderGlyph(JerreaderIcon.CHEVRON_FORWARD, MaterialTheme.colorScheme.plainTertiary, Modifier.size(13.dp))
    }
}

/** The hairline between tool rows, inset past the tile. */
@Composable
private fun PracticeToolDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(start = 68.dp)
            .fillMaxWidth()
            .height(0.5.dp)
            .background(jerreaderPalette.line)
    )
}
