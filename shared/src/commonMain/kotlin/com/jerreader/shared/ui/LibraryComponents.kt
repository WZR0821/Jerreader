package com.jerreader.shared.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import com.jerreader.shared.library.LibraryBook
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

// The 书架 (library) screen's 2.0 building blocks, mirroring iOS
// Jerreader/Features/Library/LibraryShelfViews.swift. JerreaderLibraryApp owns
// all state and every action; these views only draw and report taps back.

// region Shared rules

/**
 * Number and text rules shared by the hero, the grid, the statistics and the
 * book editor (iOS `LibraryShelfFormat`), so they always agree.
 */
internal object LibraryShelfFormat {
    fun clampedProgress(progress: Double): Double =
        if (progress.isFinite()) progress.coerceIn(0.0, 1.0) else 0.0

    fun percent(progress: Double): Int = (clampedProgress(progress) * 100).roundToInt()

    /** "37%". */
    fun progressText(progress: Double): String = "${percent(progress)}%"

    fun isStarted(progress: Double): Boolean = progress.isFinite() && progress > 0

    /** Finished once the rounded progress reads 100%. */
    fun isFinished(progress: Double): Boolean = isStarted(progress) && percent(progress) >= 100

    /** "<1 分钟", "25 分钟", "2 小时", "2小时5分". */
    fun durationText(seconds: Double): String {
        val clamped = safeSeconds(seconds)
        val totalMinutes = (clamped / 60).toInt()
        if (totalMinutes < 1) return if (clamped > 0) "<1 分钟" else "0 分钟"
        if (totalMinutes < 60) return "$totalMinutes 分钟"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (minutes == 0) "$hours 小时" else "${hours}小时${minutes}分"
    }

    /** The 累计阅读 statistic: whole minutes under an hour, then whole hours (rounded down). */
    fun readingTimeStat(seconds: Double): Pair<Int, String> {
        val totalMinutes = (safeSeconds(seconds) / 60).toInt()
        return if (totalMinutes < 60) totalMinutes to "分钟" else totalMinutes / 60 to "小时"
    }

    private fun safeSeconds(seconds: Double): Double =
        if (seconds.isFinite()) seconds.coerceIn(0.0, 1_000_000_000.0) else 0.0
}

/** "11月19日 星期四" on the Gregorian calendar (iOS `LibraryShelfDate`). */
internal object LibraryShelfDate {
    private val weekdays = listOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

    /** [utcOffsetMillis] is the device time zone's offset at [epochMillis]. */
    fun format(epochMillis: Long, utcOffsetMillis: Int): String {
        val days = (epochMillis + utcOffsetMillis).floorDiv(86_400_000L)
        // The civil date of a day count (H. Hinnant's civil_from_days).
        val z = days + 719_468L
        val era = z.floorDiv(146_097L)
        val dayOfEra = z - era * 146_097L
        val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
        val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
        val monthIndex = (5L * dayOfYear + 2L) / 153L
        val day = dayOfYear - (153L * monthIndex + 2L) / 5L + 1L
        val month = if (monthIndex < 10L) monthIndex + 3L else monthIndex - 9L
        // 1970-01-01 was a Thursday.
        val weekday = (days + 4L).mod(7L).toInt()
        return "${month}月${day}日 ${weekdays[weekday]}"
    }
}

/** The three numbers on the 阅读统计 card, from this device's books only. */
internal data class LibraryReadingStatsSummary(
    val totalSeconds: Double,
    val startedCount: Int,
    val averagePercent: Int
) {
    companion object {
        fun of(books: List<LibraryBook>): LibraryReadingStatsSummary {
            val started = books.filter { it.lastReadProgress > 0 }
            return LibraryReadingStatsSummary(
                totalSeconds = books.sumOf { max(it.totalReadingSeconds, 0.0) },
                startedCount = started.size,
                averagePercent = if (started.isEmpty()) {
                    0
                } else {
                    LibraryShelfFormat.percent(started.sumOf { it.lastReadProgress } / started.size)
                }
            )
        }
    }
}

/** iOS imports a book without an author as 未知作者, so the shelf shows that. */
internal val LibraryBook.shelfAuthor: String
    get() = author?.trim()?.takeIf(String::isNotEmpty) ?: "未知作者"

/** iOS `BookFormat.displayName`: EPUB, PDF, DOCX, TXT. */
private fun libraryFormatLabel(sourceFormat: String): String =
    if (sourceFormat.equals("text", ignoreCase = true)) "TXT" else sourceFormat.uppercase()

/** A [delay] stretched by the system animator scale, like the animation that follows it. */
private suspend fun libraryMotionDelay(millis: Long) {
    val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
    if (millis > 0 && scale > 0f) delay((millis * scale).toLong())
}

// endregion

// region Modifiers

/**
 * The shelf's staggered rise-in (iOS `libraryShelfReveal`): fade in, rise 12dp
 * and grow from 98%. It runs only if [isEnabled] is true when the view first
 * appears (the shelf's first second on screen), so tiles composed later by
 * scrolling, a tab switch or a closed reader simply show.
 */
internal fun Modifier.libraryShelfReveal(order: Int, isEnabled: Boolean): Modifier = this.composed {
    val animates = remember { isEnabled }
    if (!animates) {
        Modifier
    } else {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            libraryMotionDelay(JerreaderMotion.revealDelayMillis(order))
            progress.animateTo(1f, JerreaderMotion.reveal())
        }
        Modifier.graphicsLayer {
            val value = progress.value
            alpha = value.coerceIn(0f, 1f)
            translationY = (1f - value) * 12.dp.toPx()
            val scale = 0.98f + 0.02f * value
            scaleX = scale
            scaleY = scale
        }
    }
}

/** iOS `JerreaderPressButtonStyle`: [pressedScale] and 94% opacity while pressed. */
private fun Modifier.libraryPress(interactionSource: InteractionSource, pressedScale: Float): Modifier =
    this.composed {
        val pressed by interactionSource.collectIsPressedAsState()
        val progress by animateFloatAsState(
            targetValue = if (pressed) 1f else 0f,
            animationSpec = if (pressed) JerreaderMotion.press() else JerreaderMotion.release(),
            label = "libraryPress"
        )
        Modifier.graphicsLayer {
            val scale = 1f - (1f - pressedScale) * progress
            scaleX = scale
            scaleY = scale
            alpha = (1f - 0.06f * progress).coerceIn(0f, 1f)
        }
    }

/**
 * SwiftUI's negative padding: the content is laid out [horizontal] and
 * [vertical] larger on each side than the size it reports, so a 44dp touch
 * target only takes the room of its visible part.
 */
private fun Modifier.libraryNegativePadding(horizontal: Dp = 0.dp, vertical: Dp = 0.dp): Modifier =
    this.layout { measurable, constraints ->
        val dx = horizontal.roundToPx()
        val dy = vertical.roundToPx()
        val placeable = measurable.measure(constraints.offset(horizontal = dx * 2, vertical = dy * 2))
        layout(
            constraints.constrainWidth(placeable.width - dx * 2),
            constraints.constrainHeight(placeable.height - dy * 2)
        ) {
            placeable.place(-dx, -dy)
        }
    }

/** Where a cover is on screen, read when it is tapped, for the reader's opening animation. */
private class LibraryCoverBounds {
    var coordinates: LayoutCoordinates? = null

    fun inWindow(): Rect? = coordinates?.takeIf { it.isAttached }?.boundsInWindow()
}

// endregion

// region Dashboard

/** The subtitle under the 书架 title: today's date. */
@Composable
internal fun LibraryShelfDateLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.plainSecondary,
        maxLines = 1
    )
}

/**
 * The date line, the 继续阅读 hero and the 阅读统计 card above the grid. A
 * null [dateText] leaves the date line out; a null [recentBook] the hero.
 */
@Composable
internal fun LibraryShelfDashboard(
    dateText: String?,
    recentBook: LibraryBook?,
    summary: LibraryReadingStatsSummary,
    nowMillis: Long,
    revealOpen: Boolean,
    onOpenRecent: (coverBoundsInWindow: Rect?) -> Unit,
    modifier: Modifier = Modifier,
    coverContent: (@Composable (LibraryBook) -> Unit)? = null,
    /** 本章还剩约 N 分钟 for the 继续阅读 book (2.1.0 §9). */
    recentChapterEstimate: String? = null
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(JerreaderSpacing.sm)
    ) {
        if (dateText != null) {
            LibraryShelfDateLine(
                text = dateText,
                modifier = Modifier.libraryShelfReveal(order = 0, isEnabled = revealOpen)
            )
        }
        if (recentBook != null) {
            LibraryContinueReadingCard(
                book = recentBook,
                nowMillis = nowMillis,
                onOpen = onOpenRecent,
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .libraryShelfReveal(order = 1, isEnabled = revealOpen),
                coverContent = coverContent,
                chapterEstimate = recentChapterEstimate
            )
        }
        LibraryReadingStatsCard(
            summary = summary,
            rollsIn = revealOpen,
            modifier = Modifier
                .widthIn(max = 760.dp)
                .libraryShelfReveal(order = 2, isEnabled = revealOpen)
        )
    }
}

/** 继续阅读: the most recently opened book on the ink hero gradient. The whole card is one button. */
@Composable
internal fun LibraryContinueReadingCard(
    book: LibraryBook,
    nowMillis: Long,
    onOpen: (coverBoundsInWindow: Rect?) -> Unit,
    modifier: Modifier = Modifier,
    coverContent: (@Composable (LibraryBook) -> Unit)? = null,
    /** A line under the progress row, as in the reader's bottom bar (2.1.0 §9). */
    chapterEstimate: String? = null
) {
    val source = remember { MutableInteractionSource() }
    val coverBounds = remember { LibraryCoverBounds() }
    val author = book.shelfAuthor
    val progressText = LibraryShelfFormat.progressText(book.lastReadProgress)
    val relative = book.lastOpenedAtEpochMillis?.let { JerreaderRelativeTime.format(it, nowMillis) }
    val estimate = chapterEstimate?.takeIf(String::isNotBlank)
    val caption = MaterialTheme.typography.bodySmall
    val captionColor = Color.White.copy(alpha = 0.75f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .libraryPress(source, pressedScale = 0.98f)
            .jerreaderHeroBackground(RoundedCornerShape(JerreaderRadius.hero))
            .clickable(
                interactionSource = source,
                indication = null,
                onClickLabel = "继续阅读",
                role = Role.Button
            ) { onOpen(coverBounds.inWindow()) }
            .clearAndSetSemantics {
                contentDescription = "继续阅读，${book.title}，作者 $author"
                stateDescription = listOfNotNull("已读 $progressText", relative, estimate).joinToString("，")
            }
            .padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(JerreaderSpacing.md),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(width = 84.dp, height = 120.dp)
                .onPlaced { coverBounds.coordinates = it }
                .jerreaderBookCover(RoundedCornerShape(JerreaderRadius.cover))
        ) {
            if (coverContent != null) coverContent(book) else JerreaderBookPlaceholder(book)
        }
        // At least as tall as the cover, so the progress row sits on the card's bottom edge.
        Column(modifier = Modifier.weight(1f).heightIn(min = 120.dp)) {
            JerreaderEyebrow(text = "继续阅读", tint = Color.White.copy(alpha = 0.72f))
            Text(
                text = book.title,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = author,
                modifier = Modifier.padding(top = JerreaderSpacing.xxs),
                style = MaterialTheme.typography.bodyMedium,
                color = captionColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // iOS Spacer(minLength: 12): at least 12dp, then whatever the column has left.
            Spacer(Modifier.height(JerreaderSpacing.sm))
            Spacer(Modifier.weight(1f))
            JerreaderProgressBar(
                progress = LibraryShelfFormat.clampedProgress(book.lastReadProgress).toFloat(),
                tint = Color.White,
                track = Color.White.copy(alpha = 0.25f)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = progressText,
                        style = caption.copy(fontFeatureSettings = "tnum"),
                        color = captionColor,
                        maxLines = 1
                    )
                    if (relative != null) {
                        Text(text = "·", style = caption, color = captionColor, maxLines = 1)
                        Text(
                            text = relative,
                            style = caption,
                            color = captionColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // iOS: 8dp stack spacing on each side of an 8dp minimum spacer.
                Spacer(Modifier.width(24.dp))
                Box(
                    modifier = Modifier
                        .heightIn(min = 34.dp)
                        .background(Color.White, CircleShape)
                        .padding(horizontal = JerreaderSpacing.md),
                    contentAlignment = Alignment.Center
                ) {
                    // accentDeep is the light-mode accent in both modes, readable on white.
                    Text(
                        text = "继续",
                        style = MaterialTheme.typography.titleSmall,
                        color = jerreaderPalette.accentDeep,
                        maxLines = 1
                    )
                }
            }
            if (estimate != null) {
                Text(
                    text = estimate,
                    modifier = Modifier.padding(top = 6.dp),
                    style = caption,
                    color = captionColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 阅读统计: a paper card with three statistics in gold, accent and jade. When
 * [rollsIn] (the shelf's first appearance) the numbers roll up from zero.
 */
@Composable
internal fun LibraryReadingStatsCard(
    summary: LibraryReadingStatsSummary,
    rollsIn: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = jerreaderPalette
    val secondary = MaterialTheme.colorScheme.plainSecondary
    var hasRolledIn by remember { mutableStateOf(!rollsIn) }
    if (!hasRolledIn) {
        LaunchedEffect(Unit) {
            // Starts just after the card's own reveal begins.
            libraryMotionDelay(200)
            hasRolledIn = true
        }
    }
    val readingTime = LibraryShelfFormat.readingTimeStat(summary.totalSeconds)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .jerreaderPaperCard(RoundedCornerShape(JerreaderRadius.card))
            .semantics(mergeDescendants = true) {}
            .padding(JerreaderSpacing.md),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "阅读统计",
                modifier = Modifier.alignByBaseline(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
            Spacer(Modifier.width(24.dp))
            Text(
                text = if (summary.startedCount == 0) "从一本喜欢的书开始" else "只记录本机阅读进度",
                modifier = Modifier.weight(1f).alignByBaseline(),
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(JerreaderSpacing.sm),
            verticalAlignment = Alignment.Top
        ) {
            JerreaderStatValue(
                value = if (hasRolledIn) readingTime.first else 0,
                label = "累计阅读",
                modifier = Modifier.weight(1f),
                unit = readingTime.second,
                icon = JerreaderIcon.CLOCK,
                tint = colors.gold,
                secondary = secondary,
                size = 28
            )
            JerreaderStatValue(
                value = if (hasRolledIn) summary.startedCount else 0,
                label = "已开始",
                modifier = Modifier.weight(1f),
                unit = "本",
                icon = JerreaderIcon.BOOKS,
                tint = colors.accent,
                secondary = secondary,
                size = 28
            )
            JerreaderStatValue(
                value = if (hasRolledIn) summary.averagePercent else 0,
                label = "平均进度",
                modifier = Modifier.weight(1f),
                unit = "%",
                icon = JerreaderIcon.TREND,
                tint = colors.jade,
                secondary = secondary,
                size = 28
            )
        }
    }
}

// endregion

// region Header, sort and folders

/** 所有书籍 with its count, and [content] (the sort capsule) on the right. */
@Composable
internal fun LibraryShelfSectionHeader(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(JerreaderSpacing.xs)
        ) {
            Text(
                text = title,
                modifier = Modifier.alignByBaseline().semantics { heading() },
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1
            )
            Text(
                text = detail,
                modifier = Modifier.alignByBaseline(),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.plainSecondary,
                maxLines = 1
            )
        }
        // iOS: 8dp stack spacing on each side of an 8dp minimum spacer.
        Spacer(Modifier.width(24.dp))
        content()
    }
}

/** The sort order as a capsule beside 所有书籍: the same choice as the toolbar menu's 排序方式. */
@Composable
internal fun LibrarySortCapsule(
    selected: LibrarySortOrder,
    onSelect: (LibrarySortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = jerreaderPalette
    var expanded by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.4f else 1f,
        animationSpec = if (pressed) JerreaderMotion.press() else JerreaderMotion.release(),
        label = "sortCapsulePress"
    )
    Box(modifier = modifier) {
        // A 44dp tall target that still only takes 30dp of the header.
        Box(
            modifier = Modifier
                .libraryNegativePadding(vertical = 7.dp)
                .clickable(interactionSource = source, indication = null, role = Role.Button) {
                    expanded = true
                }
                .clearAndSetSemantics {
                    contentDescription = "排序方式"
                    stateDescription = selected.title
                }
                .padding(vertical = 7.dp)
                .graphicsLayer { alpha = pressAlpha.coerceIn(0f, 1f) }
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 30.dp)
                    .background(colors.accentFill, CircleShape)
                    .padding(horizontal = JerreaderSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(JerreaderSpacing.xxs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                JerreaderGlyph(icon = JerreaderIcon.SORT, tint = colors.accent, modifier = Modifier.size(13.dp))
                Text(
                    text = selected.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.accent,
                    maxLines = 1
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LibrarySortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.title) },
                    onClick = {
                        expanded = false
                        onSelect(order)
                    },
                    trailingIcon = {
                        if (order == selected) {
                            JerreaderGlyph(JerreaderIcon.CHECK, MaterialTheme.colorScheme.primary, Modifier.size(18.dp))
                        }
                    }
                )
            }
        }
    }
}

private const val ALL_BOOKS_CHIP_KEY = "\u0000all-books"

/**
 * The folder filter: 全部书籍 plus one chip per folder, scrolling sideways edge
 * to edge. The selected chip's accent fill slides to the newly selected chip.
 */
@Composable
internal fun LibraryFolderChips(
    categories: List<String>,
    books: List<LibraryBook>,
    selectedCategory: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val isFirstSelection = remember { booleanArrayOf(true) }
    // iOS .sensoryFeedback(.selection, trigger: selectedCategory).
    LaunchedEffect(selectedCategory) {
        if (isFirstSelection[0]) {
            isFirstSelection[0] = false
        } else {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }
    val chipBounds = remember { mutableStateMapOf<String, Rect>() }
    val target = chipBounds[selectedCategory ?: ALL_BOOKS_CHIP_KEY]
    val fillLeft = remember { Animatable(0f) }
    val fillWidth = remember { Animatable(0f) }
    LaunchedEffect(target) {
        val bounds = target ?: return@LaunchedEffect
        if (fillWidth.value == 0f) {
            fillLeft.snapTo(bounds.left)
            fillWidth.snapTo(bounds.width)
        } else {
            coroutineScope {
                launch { fillLeft.animateTo(bounds.left, JerreaderMotion.selection()) }
                launch { fillWidth.animateTo(bounds.width, JerreaderMotion.selection()) }
            }
        }
    }
    val accent = jerreaderPalette.accent
    // Drawn by the selected chip under its label, at the sliding position.
    val drawFill: DrawScope.(Float) -> Unit = { chipLeft ->
        val width = fillWidth.value
        drawRoundRect(
            color = accent,
            topLeft = Offset(if (width > 0f) fillLeft.value - chipLeft else 0f, 0f),
            size = Size(if (width > 0f) width else size.width, size.height),
            cornerRadius = CornerRadius(size.height / 2)
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Bleeds to the screen edges; the row's own padding keeps the
            // first chip on the page margin.
            .libraryNegativePadding(horizontal = JerreaderSpacing.lg)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = JerreaderSpacing.lg, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(JerreaderSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LibraryFolderChip(
            title = "全部书籍",
            count = books.size,
            icon = JerreaderIcon.GRID,
            isSelected = selectedCategory == null,
            onClick = { onSelect(null) },
            onPlacedInRow = { bounds ->
                if (chipBounds[ALL_BOOKS_CHIP_KEY] != bounds) chipBounds[ALL_BOOKS_CHIP_KEY] = bounds
            },
            drawSelectedFill = drawFill
        )
        categories.forEach { category ->
            key(category) {
                LibraryFolderChip(
                    title = category,
                    count = books.count { it.category == category },
                    icon = JerreaderIcon.FOLDER,
                    isSelected = selectedCategory == category,
                    onClick = { onSelect(category) },
                    onPlacedInRow = { bounds ->
                        if (chipBounds[category] != bounds) chipBounds[category] = bounds
                    },
                    drawSelectedFill = drawFill
                )
            }
        }
    }
}

@Composable
private fun LibraryFolderChip(
    title: String,
    count: Int,
    icon: JerreaderIcon,
    isSelected: Boolean,
    onClick: () -> Unit,
    onPlacedInRow: (Rect) -> Unit,
    drawSelectedFill: DrawScope.(chipLeft: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = jerreaderPalette
    val source = remember { MutableInteractionSource() }
    val placedLeft = remember { floatArrayOf(0f) }
    val paperAlpha by animateFloatAsState(
        targetValue = if (isSelected) 0f else 1f,
        animationSpec = JerreaderMotion.selection(),
        label = "folderChipPaper"
    )
    val content by animateColorAsState(
        targetValue = if (isSelected) colors.onAccent else MaterialTheme.colorScheme.onSurface,
        animationSpec = JerreaderMotion.selection(),
        label = "folderChipContent"
    )
    Row(
        modifier = modifier
            .onPlaced {
                val bounds = Rect(it.positionInParent(), it.size.toSize())
                placedLeft[0] = bounds.left
                onPlacedInRow(bounds)
            }
            .libraryPress(source, pressedScale = 0.96f)
            .drawBehind {
                val alpha = paperAlpha.coerceIn(0f, 1f)
                if (alpha > 0f) {
                    drawRoundRect(colors.paper, cornerRadius = CornerRadius(size.height / 2), alpha = alpha)
                    val stroke = 0.75.dp.toPx()
                    drawRoundRect(
                        color = colors.line,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius((size.height - stroke) / 2),
                        style = Stroke(stroke),
                        alpha = alpha
                    )
                }
                if (isSelected) drawSelectedFill(placedLeft[0])
            }
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "$title，$count 本"
                selected = isSelected
            }
            .heightIn(min = 36.dp)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        JerreaderGlyph(icon = icon, tint = content, modifier = Modifier.size(14.dp))
        Text(text = title, style = MaterialTheme.typography.titleSmall, color = content, maxLines = 1)
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum"
            ),
            color = content.copy(alpha = content.alpha * 0.72f),
            maxLines = 1
        )
    }
}

// endregion

// region Book tile

/**
 * One book on the shelf: cover, title, author and format, with the 更多操作
 * menu beside the title and the same actions on a long press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LibraryBookTile(
    book: LibraryBook,
    onOpen: (coverBoundsInWindow: Rect?) -> Unit,
    onEdit: () -> Unit,
    onChangeCover: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    coverContent: (@Composable (LibraryBook) -> Unit)? = null
) {
    val colors = jerreaderPalette
    val haptics = LocalHapticFeedback.current
    var menuExpanded by remember { mutableStateOf(false) }
    val coverSource = remember { MutableInteractionSource() }
    val moreSource = remember { MutableInteractionSource() }
    val morePressed by moreSource.collectIsPressedAsState()
    val coverBounds = remember { LibraryCoverBounds() }
    val progress = book.lastReadProgress
    val isStarted = LibraryShelfFormat.isStarted(progress)
    val isFinished = LibraryShelfFormat.isFinished(progress)
    val secondary = MaterialTheme.colorScheme.plainSecondary
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .onPlaced { coverBounds.coordinates = it }
                .libraryPress(coverSource, pressedScale = 0.97f)
                .combinedClickable(
                    interactionSource = coverSource,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = "打开阅读器",
                    onLongClick = { menuExpanded = true },
                    onClick = { onOpen(coverBounds.inWindow()) }
                )
                .semantics {
                    contentDescription = "${book.title}，作者 ${book.shelfAuthor}"
                    if (isFinished) {
                        stateDescription = "已读完"
                    } else if (isStarted) {
                        stateDescription = "已读 ${LibraryShelfFormat.progressText(progress)}"
                    }
                }
        ) {
            Box(Modifier.fillMaxSize().jerreaderBookCover(RoundedCornerShape(JerreaderRadius.cover))) {
                // The cover button carries the title; the art itself stays silent.
                Box(Modifier.fillMaxSize().clearAndSetSemantics {}) {
                    if (coverContent != null) coverContent(book) else JerreaderBookPlaceholder(book)
                }
                if (isStarted) {
                    LibraryCoverProgressEdge(progress = progress, modifier = Modifier.align(Alignment.BottomStart))
                }
            }
            if (isFinished) {
                LibraryFinishedBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuExpanded = true
                    })
                }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(JerreaderSpacing.xxs),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = book.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Box {
                    // A 44dp target that only takes 16 × 20dp of the row, so the
                    // title keeps most of a narrow column.
                    Box(
                        modifier = Modifier
                            .libraryNegativePadding(horizontal = 14.dp, vertical = 12.dp)
                            .size(44.dp)
                            .clickable(interactionSource = moreSource, indication = null, role = Role.Button) {
                                menuExpanded = true
                            }
                            .semantics { contentDescription = "${book.title}的更多操作" },
                        contentAlignment = Alignment.Center
                    ) {
                        JerreaderGlyph(
                            icon = JerreaderIcon.MORE,
                            tint = if (morePressed) secondary.copy(alpha = secondary.alpha * 0.4f) else secondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("开始阅读", color = MaterialTheme.colorScheme.onSurface) },
                            leadingIcon = {
                                JerreaderGlyph(JerreaderIcon.BOOKS, MaterialTheme.colorScheme.onSurface, Modifier.size(18.dp))
                            },
                            onClick = {
                                menuExpanded = false
                                onOpen(coverBounds.inWindow())
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("编辑书籍信息", color = MaterialTheme.colorScheme.onSurface) },
                            leadingIcon = {
                                JerreaderGlyph(JerreaderIcon.NOTE, MaterialTheme.colorScheme.onSurface, Modifier.size(18.dp))
                            },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("更换封面", color = MaterialTheme.colorScheme.onSurface) },
                            leadingIcon = {
                                JerreaderGlyph(JerreaderIcon.PHOTO, MaterialTheme.colorScheme.onSurface, Modifier.size(18.dp))
                            },
                            onClick = {
                                menuExpanded = false
                                onChangeCover()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                JerreaderGlyph(JerreaderIcon.TRASH, MaterialTheme.colorScheme.error, Modifier.size(18.dp))
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
            Text(
                text = book.shelfAuthor,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = libraryFormatLabel(book.sourceFormat),
                modifier = Modifier
                    .padding(top = 6.dp)
                    .background(colors.accentFill, CircleShape)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.accent,
                maxLines = 1
            )
        }
    }
}

/** The 3dp reading-progress line along a grid cover's bottom edge. */
@Composable
private fun LibraryCoverProgressEdge(progress: Double, modifier: Modifier = Modifier) {
    val accent = jerreaderPalette.accent
    val fraction = LibraryShelfFormat.clampedProgress(progress).toFloat()
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        drawRect(Color.Black.copy(alpha = 0.32f))
        drawRect(accent, size = Size(size.width * fraction, size.height))
    }
}

/** The jade check on a finished book's cover. */
@Composable
private fun LibraryFinishedBadge(modifier: Modifier = Modifier) {
    val colors = jerreaderPalette
    Box(
        modifier = modifier
            .size(22.dp)
            .jerreaderElevation(JerreaderElevation.CARD, CircleShape, tint = Color.Black)
            .background(colors.jade, CircleShape)
            .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        JerreaderGlyph(icon = JerreaderIcon.CHECK, tint = colors.onAccent, modifier = Modifier.size(11.dp))
    }
}

// endregion

// region No match and empty shelf

/** Shown in place of the grid when the search or folder matches no book. */
@Composable
internal fun LibraryNoMatchView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .padding(vertical = JerreaderSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(JerreaderSpacing.sm)
    ) {
        JerreaderIconTile(icon = JerreaderIcon.SEARCH, tint = jerreaderPalette.accent, size = 52.dp)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(JerreaderSpacing.xxs)
        ) {
            Text(
                text = "没有匹配的书籍",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Text(
                text = "请清除搜索文字或切换文件夹。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.plainSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * The empty shelf: an icon block, a title, import and restore. The block sits
 * a third of the way down the free space, and the page scrolls instead when
 * large text makes it taller than the screen (iOS `ViewThatFits`). A null
 * [dateText] leaves the date line out; a null [onRestore] the restore path.
 */
@Composable
internal fun LibraryEmptyShelfView(
    dateText: String?,
    isImporting: Boolean,
    revealsOnAppear: Boolean,
    onImport: () -> Unit,
    onRestore: (() -> Unit)?,
    modifier: Modifier = Modifier
) = SystemLineHeights {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportHeight = constraints.maxHeight
        Layout(
            content = {
                if (dateText != null) LibraryShelfDateLine(dateText) else Spacer(Modifier)
                LibraryEmptyShelfContent(
                    isImporting = isImporting,
                    revealsOnAppear = revealsOnAppear,
                    onImport = onImport,
                    onRestore = onRestore
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = JerreaderSpacing.lg)
        ) { measurables, layoutConstraints ->
            val top = JerreaderSpacing.xxs.roundToPx()
            val gap = JerreaderSpacing.xl.roundToPx()
            val fitBottom = JerreaderSpacing.xl.roundToPx()
            val scrollBottom = JerreaderSpacing.xxl.roundToPx()
            val loose = layoutConstraints.copy(minWidth = 0, minHeight = 0)
            val date = measurables[0].measure(loose)
            val block = measurables[1].measure(loose)
            val width = layoutConstraints.maxWidth
            val bounded = viewportHeight != Constraints.Infinity
            // Fits: a 24dp minimum above and below the block, and the space
            // left split a third above, two thirds below.
            val free = viewportHeight - top - date.height - block.height - fitBottom
            val fits = bounded && free >= 2 * gap
            val blockTop = top + date.height + if (fits) max(gap, free / 3) else gap
            val height = if (fits) viewportHeight else blockTop + block.height + scrollBottom
            layout(width, height) {
                date.place(0, top)
                block.place((width - block.width) / 2, blockTop)
            }
        }
    }
}

@Composable
private fun LibraryEmptyShelfContent(
    isImporting: Boolean,
    revealsOnAppear: Boolean,
    onImport: () -> Unit,
    onRestore: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val secondary = MaterialTheme.colorScheme.plainSecondary
    Column(
        modifier = modifier.widthIn(max = 360.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LibraryEmptyShelfIcon(Modifier.libraryShelfReveal(order = 0, isEnabled = revealsOnAppear))
        Column(
            modifier = Modifier
                .libraryShelfReveal(order = 1, isEnabled = revealsOnAppear)
                .padding(top = JerreaderSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(JerreaderSpacing.xs)
        ) {
            Text(
                text = "把下一本书放进来",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )
            Text(
                text = "导入 EPUB、PDF、DOCX 或 TXT。书籍只保存在这台设备上。",
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
                textAlign = TextAlign.Center
            )
        }
        Column(
            modifier = Modifier
                .libraryShelfReveal(order = 2, isEnabled = revealsOnAppear)
                .padding(top = 28.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(JerreaderSpacing.sm)
        ) {
            JerreaderPrimaryButton(
                text = "导入书籍",
                onClick = onImport,
                icon = JerreaderIcon.DOWNLOAD,
                enabled = !isImporting
            )
            // An empty shelf is what a reinstall looks like, so the restore
            // path belongs here rather than only deep in settings.
            if (onRestore != null) {
                JerreaderTintedButton(
                    text = "从备份恢复",
                    onClick = onRestore,
                    modifier = Modifier.testTag("library-restore-backup"),
                    icon = JerreaderIcon.RESTORE,
                    enabled = !isImporting,
                    fullWidth = true
                )
            }
        }
        Column(
            modifier = Modifier
                .libraryShelfReveal(order = 3, isEnabled = revealsOnAppear)
                .padding(top = JerreaderSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(JerreaderSpacing.sm)
        ) {
            if (onRestore != null) {
                Text(
                    text = "装过 Jerreader 的话，选择以前的备份文件夹即可恢复书架、进度和备份计划。",
                    style = MaterialTheme.typography.bodySmall,
                    color = secondary,
                    textAlign = TextAlign.Center
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                JerreaderGlyph(icon = JerreaderIcon.LOCK, tint = secondary, modifier = Modifier.size(13.dp))
                Text(text = "本机保存，不会上传", style = MaterialTheme.typography.bodySmall, color = secondary)
            }
        }
    }
}

/** The 72dp accent-gradient block with the bookshelf glyph. */
@Composable
private fun LibraryEmptyShelfIcon(modifier: Modifier = Modifier) {
    val colors = jerreaderPalette
    val blockSize = 72.dp
    val shape = RoundedCornerShape(blockSize * 0.28f)
    Box(
        modifier = modifier
            .size(blockSize)
            .jerreaderElevation(JerreaderElevation.RAISED, shape, tint = colors.accent)
            .background(Brush.linearGradient(listOf(colors.accent, colors.accentDeep)), shape)
            // accent and accentDeep match in light mode; the gloss keeps it a gradient there too.
            .background(
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0f))),
                shape
            ),
        contentAlignment = Alignment.Center
    ) {
        JerreaderGlyph(icon = JerreaderIcon.BOOKS, tint = Color.White, modifier = Modifier.size(34.dp))
    }
}

// endregion

// region Shared building blocks used by other screens

/** Icon-only toolbar action, like the library's ⌄ / ↑↓ / ＋ buttons. */
@Composable
fun LibraryToolbarButton(
    icon: JerreaderIcon,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        JerreaderGlyph(
            icon = icon,
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * Segmented navigation used by the iOS 学习 page: a white pill container with
 * an icon and a label per item, the selected one filled with the accent.
 */
@Composable
fun <T> JerreaderSegmentedNav(
    options: List<Triple<T, String, JerreaderIcon>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(modifier = Modifier.selectableGroup().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { (value, title, icon) ->
                val isSelected = value == selected
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            }
                        )
                        .selectable(selected = isSelected, role = androidx.compose.ui.semantics.Role.Tab) { onSelect(value) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
                ) {
                    JerreaderGlyph(
                        icon = icon,
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(16.dp)
                    )
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
                        val labelSize = with(androidx.compose.ui.platform.LocalDensity.current) { 13.dp.toSp() }
                    Text(
                        text = title,
                        modifier = Modifier,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = labelSize, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    }
                }
            }
        }
    }
}

/** Hint banner: a rounded glyph tile, a bold headline and a grey subtitle. */
@Composable
fun JerreaderHintCard(
    badge: String,
    title: String,
    detail: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Bold field label above an input, as used across the iOS learning forms. */
@Composable
fun JerreaderFieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall
    )
}

/** Small tinted suggestion chip, like the iOS 「试一试」 example. */
@Composable
fun JerreaderSuggestionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        onClick = onClick
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// endregion
