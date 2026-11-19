package com.jerreader.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jerreader.android.AppGraph
import com.jerreader.android.learning.ReadingGrowthSnapshot
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderIconTile
import com.jerreader.shared.ui.JerreaderLearningMotion
import com.jerreader.shared.ui.JerreaderLearningType
import com.jerreader.shared.ui.JerreaderRadius
import com.jerreader.shared.ui.jerreaderPalette
import com.jerreader.shared.ui.jerreaderTabularDigits
import com.jerreader.shared.ui.plainSecondary
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * iOS `ReadingGrowthView`: the switch card, then — fading in and out with the
 * switch — the book and week card and the week's report.
 */
@Composable
fun ReadingGrowthScreen(graph: AppGraph) {
    val books by remember(graph) { graph.database.bookDao().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val words by remember(graph) { graph.learningRecords.observeWords() }.collectAsStateWithLifecycle(emptyList())
    val preferences by graph.appSettings.preferences.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    var weeksAgo by remember { mutableIntStateOf(0) }
    val zone = ZoneId.systemDefault()
    val start = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(weeksAgo.toLong())
    val activities = remember(books, selected) { books.filter { selected == null || it.id == selected }.map { it.activityJson } }
    val reviews = remember(words) { words.map { it.reviewJson } }
    val request = GrowthRequest(activities, reviews, start, selected, zone, preferences.growthEnabled)
    val prepared by produceState<Pair<GrowthRequest, ReadingGrowthSnapshot>?>(null, request) {
        if (request.enabled) value = request to withContext(Dispatchers.Default) {
            ReadingGrowthSnapshot.create(request.activities, request.reviews, request.start, request.book, request.zone,
                checkpoint = { ensureActive() })
        }
    }
    val report = prepared?.takeIf { it.first == request }?.second
    val palette = jerreaderPalette
    val bookChoices = remember(books) { listOf<String?>(null) + books.map { it.id } }
    LearningPage {
        item(key = "switch", contentType = "switch") {
            LearningCard {
                LearningToggleRow(
                    title = "记录并显示成长报告",
                    checked = preferences.growthEnabled,
                    onCheckedChange = graph.appSettings::updateGrowthEnabled
                )
                Text(
                    "按本机日期记录。关闭后暂停采集并隐藏报告，既有记录保留。历史只从新版开始累计。",
                    style = JerreaderLearningType.caption,
                    color = MaterialTheme.colorScheme.plainSecondary
                )
            }
        }
        if (preferences.growthEnabled) {
            item(key = "week", contentType = "week") {
                LearningCard(Modifier.animateItem(
                    fadeInSpec = JerreaderLearningMotion.stateChange(),
                    placementSpec = JerreaderLearningMotion.stateChange(),
                    fadeOutSpec = JerreaderLearningMotion.stateChange()
                )) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        JerreaderIconTile(JerreaderIcon.BOOKS, tint = palette.jade, size = 32.dp)
                        // The picker carries the label for TalkBack.
                        Text(
                            "书籍",
                            Modifier.clearAndSetSemantics { },
                            style = JerreaderLearningType.subheadline.copy(fontWeight = FontWeight.SemiBold)
                        )
                        // iOS's Spacer(minLength: 8) between two 12 gaps.
                        Box(Modifier.weight(1f).padding(start = 20.dp), contentAlignment = Alignment.CenterEnd) {
                            LearningMenuPicker(
                                selected = selected,
                                options = bookChoices,
                                description = "书籍",
                                label = { id -> books.firstOrNull { it.id == id }?.title ?: "全部书籍" },
                                onSelect = { selected = it }
                            )
                        }
                    }
                    GrowthHairline()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LearningButton(kind = LearningButtonKind.TINTED, enabled = weeksAgo < 103, onClick = { weeksAgo++ }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                JerreaderGlyph(JerreaderIcon.CHEVRON_LEFT, LocalContentColor.current, Modifier.size(13.dp))
                                Text("上一周")
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        LearningButton(kind = LearningButtonKind.TINTED, enabled = weeksAgo > 0, onClick = { weeksAgo-- }) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("下一周")
                                JerreaderGlyph(JerreaderIcon.CHEVRON_RIGHT, LocalContentColor.current, Modifier.size(13.dp))
                            }
                        }
                    }
                    GrowthRollingText(
                        text = "$start 起的一周",
                        style = JerreaderLearningType.headline.jerreaderTabularDigits()
                    )
                }
            }
            item(key = "report", contentType = "report") {
                LearningCard(Modifier.animateItem(
                    fadeInSpec = JerreaderLearningMotion.stateChange(),
                    placementSpec = JerreaderLearningMotion.stateChange(),
                    fadeOutSpec = JerreaderLearningMotion.stateChange()
                )) {
                    if (report == null) {
                        Text("正在汇总阅读记录…", color = MaterialTheme.colorScheme.plainSecondary)
                    } else {
                        GrowthReport(report)
                    }
                }
            }
        }
    }
}

private data class GrowthRequest(val activities: List<String?>, val reviews: List<String?>,
    val start: LocalDate, val book: String?, val zone: ZoneId, val enabled: Boolean)

/** One figure of a week: its label, value, icon and tint. */
private class GrowthFigure(val title: String, val value: String, val icon: JerreaderIcon, val tint: Color)

/**
 * iOS `ReadingGrowthReport`: four figures two to a row (one at accessibility
 * sizes), the full-width 复习正确率, the note, a hairline, then a bar per day.
 * When a week's report appears its numbers roll up from zero 50 ms later and
 * its bars grow from zero, each 30 ms after the one above.
 */
@Composable
private fun GrowthReport(report: ReadingGrowthSnapshot, modifier: Modifier = Modifier) {
    val palette = jerreaderPalette
    var counted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(50)
        counted = true
    }
    // Zero until the numbers have rolled in; the real figures after.
    val factor = if (counted) 1 else 0
    val seconds = report.seconds
    val queries = report.queries * factor
    val figures = listOf(
        GrowthFigure("阅读天数", "${report.readingDays * factor} / 7", JerreaderIcon.BOOK_CLOSED, palette.gold),
        GrowthFigure("阅读时长", "%.1f 分钟".format(Locale.ROOT, seconds * factor / 60), JerreaderIcon.CLOCK, palette.accent),
        GrowthFigure("书内查询", "$queries 次", JerreaderIcon.SEARCH, palette.vermilion),
        GrowthFigure(
            "每 10 分钟查询",
            if (seconds > 0) "%.1f 次".format(Locale.ROOT, queries * 600 / seconds) else "暂无阅读时长",
            JerreaderIcon.TREND,
            palette.vermilion
        )
    )
    val accuracy = GrowthFigure(
        "复习正确率",
        if (report.answerCount == 0) "本周未评分" else
            "${report.knownAnswers * factor * 100 / report.answerCount}%（${report.answerCount} 次）",
        JerreaderIcon.CHECK_CIRCLE,
        palette.jade
    )
    // iOS's accessibility sizes start at AX1 (body 28 pt); the theme's type cap
    // keeps the learning tab below it today.
    val columns = if (LocalDensity.current.fontScale >= 28f / 17f) 1 else 2
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        figures.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { GrowthTile(it, Modifier.weight(1f)) }
            }
        }
        GrowthTile(accuracy, Modifier.fillMaxWidth())
        Text(
            "查询包含书内发起的翻译、查词及重试；复习正确率＝选择‘认识’的次数／全部评分。没有评分时不显示 0%。",
            style = JerreaderLearningType.caption,
            color = MaterialTheme.colorScheme.plainSecondary
        )
        GrowthHairline(Modifier.padding(vertical = 4.dp))
        report.rows.forEachIndexed { index, (day, values) ->
            Column(Modifier.semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "$day · ${(values.seconds / 60).toInt()} 分钟",
                    style = JerreaderLearningType.caption.jerreaderTabularDigits()
                )
                GrowthBar(fraction = values.seconds / report.maxSeconds, order = index)
            }
        }
    }
}

/**
 * A figure on a tinted 14-radius tile: the 14 icon and caption label (shrinking
 * to 80%) over the bold tabular value (shrinking to 60%), which rolls when it
 * changes. TalkBack reads the tile as one.
 */
@Composable
private fun GrowthTile(figure: GrowthFigure, modifier: Modifier = Modifier) {
    val caption = JerreaderLearningType.caption
    Column(
        modifier = modifier
            .background(figure.tint.copy(alpha = figure.tint.alpha * 0.08f), RoundedCornerShape(JerreaderRadius.control))
            .padding(12.dp)
            .semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            JerreaderGlyph(figure.icon, figure.tint, Modifier.size(14.dp))
            Text(
                text = figure.title,
                color = MaterialTheme.colorScheme.plainSecondary,
                style = caption,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = caption.fontSize * 0.8f, maxFontSize = caption.fontSize)
            )
        }
        GrowthRollingText(
            text = figure.value,
            style = JerreaderLearningType.title3.copy(fontWeight = FontWeight.Bold).jerreaderTabularDigits(),
            minimumScale = 0.6f
        )
    }
}

/**
 * Text that rolls like iOS `numericText` counting up: the new text rises in
 * from below as the old one leaves upward, both fading. One line, shrinking to
 * [minimumScale] before it truncates.
 */
@Composable
private fun GrowthRollingText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    minimumScale: Float = 1f
) {
    AnimatedContent(
        targetState = text,
        modifier = modifier,
        transitionSpec = { growthRoll() },
        label = "growth-roll"
    ) { shown ->
        Text(
            text = shown,
            style = style,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            autoSize = if (minimumScale < 1f) {
                TextAutoSize.StepBased(minFontSize = style.fontSize * minimumScale, maxFontSize = style.fontSize)
            } else {
                null
            }
        )
    }
}

private fun growthRoll(): ContentTransform =
    (slideInVertically(JerreaderLearningMotion.stateChange()) { it / 2 } + fadeIn(JerreaderLearningMotion.stateChange()))
        .togetherWith(slideOutVertically(JerreaderLearningMotion.stateChange()) { -it / 2 } + fadeOut(JerreaderLearningMotion.stateChange()))

/**
 * One day's 8-high bar: an accent capsule on the accent-fill track, at least
 * 8 wide above zero. It grows from zero when it appears, [order] × 30 ms late
 * (at most 300 ms), and slides to a new length at once when it changes.
 */
@Composable
private fun GrowthBar(fraction: Double, order: Int, modifier: Modifier = Modifier) {
    val palette = jerreaderPalette
    val target = if (fraction.isFinite()) fraction.coerceIn(0.0, 1.0).toFloat() else 0f
    val shown = remember { Animatable(0f) }
    val appeared = remember { booleanArrayOf(false) }
    LaunchedEffect(target) {
        if (!appeared[0]) {
            appeared[0] = true
            delay(minOf(order * 30L, 300L))
        }
        shown.animateTo(target, JerreaderLearningMotion.stateChange())
    }
    Canvas(modifier.fillMaxWidth().height(8.dp)) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(palette.accentFill, cornerRadius = radius)
        val value = shown.value.coerceIn(0f, 1f)
        if (value > 0f) {
            drawRoundRect(
                palette.accent,
                size = Size(maxOf(size.width * value, 8.dp.toPx()), size.height),
                cornerRadius = radius
            )
        }
    }
}

/** The 0.5 hairline in the line colour, hidden from TalkBack. */
@Composable
private fun GrowthHairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(jerreaderPalette.line))
}
