package com.jerreader.shared.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The learning tab's shared pieces, ported from the iOS 2.0 `LearningDesignViews`,
 * `WordRecordViews`, `VocabularyView` and `LookupHistoryView`: the iOS text
 * styles, chips, dots, headings, the search / count / toolbar controls on the
 * canvas, and the paper record cards of 生词本 and 历史.
 */

/** iOS Dynamic Type styles at the default size (pt = sp). */
object JerreaderLearningType {
    val largeTitle: TextStyle
        @Composable @ReadOnlyComposable get() = learningStyle(34f, 41f, FontWeight.Bold)
    val title2: TextStyle
        @Composable @ReadOnlyComposable get() = learningStyle(22f, 26.5f)
    val title3: TextStyle
        @Composable @ReadOnlyComposable get() = learningStyle(20f, 24f)
    val headline: TextStyle
        @Composable @ReadOnlyComposable get() = learningStyle(17f, 20.5f, FontWeight.SemiBold)
    val body: TextStyle
        @Composable @ReadOnlyComposable get() = learningStyle(17f, 20.5f)
    val subheadline: TextStyle
        @Composable @ReadOnlyComposable get() = learningStyle(15f, 18f)
    val footnote: TextStyle
        @Composable @ReadOnlyComposable get() = learningStyle(13f, 16f)
    val caption: TextStyle
        @Composable @ReadOnlyComposable get() = learningStyle(12f, 14.5f)
    val caption2: TextStyle
        @Composable @ReadOnlyComposable get() = learningStyle(11f, 13f)
}

@Composable
@ReadOnlyComposable
private fun learningStyle(size: Float, line: Float, weight: FontWeight = FontWeight.Normal): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = 0.sp
    )

/** SwiftUI `.monospacedDigit()`. */
fun TextStyle.jerreaderTabularDigits(): TextStyle = copy(fontFeatureSettings = "tnum")

/**
 * iOS serif text is New York, which has no CJK glyphs: Chinese and Japanese fall
 * back to the sans system font there, so they do here too (Android's CJK serif
 * fallback is a Mincho face iOS never shows).
 */
fun jerreaderSerifFamily(text: String): FontFamily =
    if (text.any { it in '⺀'..'鿿' }) FontFamily.SansSerif else FontFamily.Serif

/**
 * [JerreaderMotion.stateChange] (the spring enter / exit transitions take), plus
 * iOS `quick` (easeOut 0.16 s).
 */
object JerreaderLearningMotion {
    fun <T> stateChange(): FiniteAnimationSpec<T> = JerreaderMotion.stateChange()

    fun <T> quick(): FiniteAnimationSpec<T> = tween(durationMillis = 160, easing = EaseOut)
}

/**
 * A leading capsule bar beside quoted text: iOS `.padding(.leading, inset)` with
 * a `width`-wide capsule overlaid at the leading edge.
 */
fun Modifier.jerreaderQuoteBar(color: Color, inset: Dp = 12.dp, width: Dp = 3.dp): Modifier = this
    .drawBehind {
        val barWidth = width.toPx()
        val x = if (layoutDirection == LayoutDirection.Rtl) size.width - barWidth else 0f
        drawRoundRect(
            color = color,
            topLeft = Offset(x, 0f),
            size = Size(barWidth, size.height),
            cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
        )
    }
    .padding(start = inset)

/** Caption semibold in the tint on a 12% capsule of it, one line. */
@Composable
fun JerreaderLearningChip(text: String, modifier: Modifier = Modifier, tint: Color = jerreaderPalette.accent) {
    Text(
        text = text,
        modifier = modifier
            .background(tint.copy(alpha = tint.alpha * 0.12f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = tint,
        style = JerreaderLearningType.caption.copy(fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** The 8pt kind dot of the history rows and the review status. */
@Composable
fun JerreaderKindDot(tint: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).background(tint, CircleShape))
}

/** Title3 bold over an optional footnote detail, 8 above, as a header. */
@Composable
fun JerreaderLearningSectionHeading(title: String, modifier: Modifier = Modifier, detail: String? = null) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = title, style = JerreaderLearningType.title3.copy(fontWeight = FontWeight.Bold))
        if (!detail.isNullOrEmpty()) {
            Text(text = detail, style = JerreaderLearningType.footnote, color = MaterialTheme.colorScheme.plainSecondary)
        }
    }
}

/** A section heading as a list row: insets top 8, sides 20, bottom 2. */
@Composable
fun JerreaderLearningListHeading(title: String, detail: String?, modifier: Modifier = Modifier) {
    JerreaderLearningSectionHeading(
        title = title,
        detail = detail,
        modifier = modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 2.dp)
    )
}

/** The accent eyebrow over a day of history: insets top 14, leading 24, bottom 2, trailing 20. */
@Composable
fun JerreaderLearningDayHeading(title: String, modifier: Modifier = Modifier) {
    JerreaderEyebrow(
        text = title,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, top = 14.dp, end = 20.dp, bottom = 2.dp)
            .semantics { heading() }
    )
}

/** Dot + caption pairs, 12 apart; hidden from accessibility like iOS. */
@Composable
fun JerreaderLearningLegend(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (title, tint) ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                JerreaderKindDot(tint)
                Text(
                    text = title,
                    style = JerreaderLearningType.caption,
                    color = MaterialTheme.colorScheme.plainSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * iOS `LearningSearchField`: magnifier, field and a clear button on a muted
 * 14-radius ground. The clear button grows in from 60% with the quick curve.
 */
@Composable
fun JerreaderSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalJerreaderColors.current
    val secondary = MaterialTheme.colorScheme.plainSecondary
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .background(colors.mutedSurface, RoundedCornerShape(JerreaderRadius.control))
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        JerreaderGlyph(icon = JerreaderIcon.SEARCH, tint = secondary, modifier = Modifier.size(16.dp))
        Box(modifier = Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = JerreaderLearningType.body,
                    color = colors.secondaryText.copy(alpha = .3f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                textStyle = JerreaderLearningType.body.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(colors.accent)
            )
        }
        AnimatedVisibility(
            visible = value.isNotEmpty(),
            enter = fadeIn(JerreaderLearningMotion.quick()) + scaleIn(JerreaderLearningMotion.quick(), initialScale = 0.6f),
            exit = fadeOut(JerreaderLearningMotion.quick()) + scaleOut(JerreaderLearningMotion.quick(), targetScale = 0.6f)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .semantics { contentDescription = "清除搜索" }
                    .clickable(role = Role.Button) { onValueChange("") },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    JerreaderGlyph(icon = JerreaderIcon.CLOSE, tint = secondary, modifier = Modifier.size(10.dp))
                }
            }
        }
    }
}

/**
 * Accent number on an accentFill capsule, at least 32 × 32. It rolls when the
 * count changes; accessibility hears 「共 N 个<noun>」.
 */
@Composable
fun JerreaderCountBadge(count: Int, modifier: Modifier = Modifier, noun: String = "") {
    val colors = LocalJerreaderColors.current
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 32.dp, minHeight = 32.dp)
            .background(colors.accentFill, CircleShape)
            .padding(horizontal = 4.dp)
            .clearAndSetSemantics { contentDescription = "共 $count 个$noun" },
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = count,
            transitionSpec = {
                val rising = targetState > initialState
                (slideInVertically(JerreaderLearningMotion.quick()) { if (rising) it else -it } +
                    fadeIn(JerreaderLearningMotion.quick())) togetherWith
                    (slideOutVertically(JerreaderLearningMotion.quick()) { if (rising) -it else it } +
                        fadeOut(JerreaderLearningMotion.quick()))
            },
            contentAlignment = Alignment.Center,
            label = "count-badge"
        ) { value ->
            Text(
                text = value.toString(),
                style = JerreaderLearningType.caption.copy(fontWeight = FontWeight.Bold).jerreaderTabularDigits(),
                color = colors.accent,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

/** iOS `LearningToolbarIcon`: an 18 glyph in a 44 × 44 muted tile, radius 14, pressing to 0.94. */
@Composable
fun JerreaderToolbarIconButton(
    icon: JerreaderIcon,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = jerreaderPalette.accent
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(44.dp)
            .jerreaderPressScale(interaction, pressedScale = 0.94f)
            .alpha(if (enabled) 1f else 0.45f)
            .background(LocalJerreaderColors.current.mutedSurface, RoundedCornerShape(JerreaderRadius.control))
            .semantics { contentDescription = label }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        JerreaderGlyph(icon = icon, tint = tint, modifier = Modifier.size(18.dp))
    }
}

/**
 * Search, count and one toolbar tile directly on the canvas: HStack(8), padding
 * h20, top 12, bottom 4, at most 960 wide.
 */
@Composable
fun JerreaderCollectionControls(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Row(
            modifier = Modifier
                .widthIn(max = 960.dp)
                .fillMaxWidth()
                .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

/**
 * iOS `learningListCard`: a paper card (radius 20, padding 16) inset 6 / 20.
 * A press lays a 70% muted wash under the content with the quick curve, as the
 * iOS list row highlights; long press opens the row's menu.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun JerreaderRecordCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(JerreaderRadius.card)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val wash by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = JerreaderLearningMotion.quick(),
        label = "record-card-wash"
    )
    val washColor = LocalJerreaderColors.current.mutedSurface
    val click = when {
        onLongClick != null -> Modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            onLongClickLabel = "更多操作",
            onLongClick = onLongClick,
            onClick = onClick ?: {}
        )
        onClick != null -> Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
        else -> Modifier
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .jerreaderPaperCard(shape)
            .clip(shape)
            .drawBehind { if (wash > 0f) drawRect(washColor.copy(alpha = washColor.alpha * 0.7f * wash)) }
            .then(click)
            .padding(16.dp),
        content = content
    )
}

/**
 * iOS `WordRecordRow`: serif headword with an optional kind dot and lemma, the
 * favourite star, reading, two lines of meanings, then language and part-of-
 * speech chips with the relative time.
 */
@Composable
fun JerreaderWordRecordRow(
    surfaceForm: String,
    lemma: String?,
    reading: String?,
    definitions: String,
    languageName: String,
    partOfSpeech: String?,
    trailingNote: String,
    modifier: Modifier = Modifier,
    isFavorite: Boolean = false,
    dotTint: Color? = null
) {
    val colors = LocalJerreaderColors.current
    val secondary = MaterialTheme.colorScheme.plainSecondary
    val dotLift = with(LocalDensity.current) { 5.dp.roundToPx() }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (dotTint != null) {
                // Its bottom sits 5 above the word's baseline.
                JerreaderKindDot(tint = dotTint, modifier = Modifier.alignBy { it.measuredHeight + dotLift })
            }
            Row(
                modifier = Modifier.weight(1f).alignByBaseline(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = surfaceForm,
                    modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
                    style = JerreaderLearningType.title2.copy(
                        fontWeight = FontWeight.Bold,
                        fontFamily = jerreaderSerifFamily(surfaceForm)
                    )
                )
                if (!lemma.isNullOrEmpty() && lemma != surfaceForm) {
                    Text(
                        text = "→ $lemma",
                        modifier = Modifier.alignByBaseline(),
                        style = JerreaderLearningType.subheadline,
                        color = secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            AnimatedVisibility(
                visible = isFavorite,
                modifier = Modifier.alignBy { it.measuredHeight },
                enter = fadeIn(JerreaderLearningMotion.stateChange()) +
                    scaleIn(JerreaderLearningMotion.stateChange(), initialScale = 0.5f),
                exit = fadeOut(JerreaderLearningMotion.stateChange()) +
                    scaleOut(JerreaderLearningMotion.stateChange(), targetScale = 0.5f)
            ) {
                JerreaderGlyph(
                    icon = JerreaderIcon.STAR_FILLED,
                    tint = colors.accent,
                    modifier = Modifier.size(18.dp).semantics { contentDescription = "已收藏" }
                )
            }
        }
        if (!reading.isNullOrEmpty()) {
            Text(text = reading, style = JerreaderLearningType.subheadline, color = secondary)
        }
        if (definitions.isNotBlank()) {
            Text(
                text = definitions,
                style = JerreaderLearningType.body,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            JerreaderLearningChip(text = languageName)
            if (!partOfSpeech.isNullOrEmpty()) {
                JerreaderLearningChip(
                    text = partOfSpeech,
                    modifier = Modifier.weight(1f, fill = false),
                    tint = colors.secondaryText
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = trailingNote,
                style = JerreaderLearningType.caption,
                color = MaterialTheme.colorScheme.plainTertiary,
                maxLines = 1
            )
        }
    }
}

/**
 * iOS `TranslationFavoriteRow`: language-pair chip and date, the translation in
 * body semibold, the source in serif beside an accent bar, then the book.
 */
@Composable
fun JerreaderTranslationFavoriteRow(
    languagePair: String,
    translatedText: String,
    sourceText: String,
    bookTitle: String?,
    trailingNote: String,
    modifier: Modifier = Modifier
) {
    val colors = LocalJerreaderColors.current
    val tertiary = MaterialTheme.colorScheme.plainTertiary
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            JerreaderLearningChip(text = languagePair, modifier = Modifier.alignByBaseline())
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = trailingNote,
                modifier = Modifier.alignByBaseline(),
                style = JerreaderLearningType.caption,
                color = tertiary,
                maxLines = 1
            )
        }
        Text(
            text = translatedText,
            style = JerreaderLearningType.body.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 5,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = sourceText,
            modifier = Modifier.jerreaderQuoteBar(colors.accent.copy(alpha = 0.35f), inset = 10.dp),
            style = JerreaderLearningType.subheadline.copy(fontFamily = jerreaderSerifFamily(sourceText)),
            color = MaterialTheme.colorScheme.plainSecondary,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        if (!bookTitle.isNullOrEmpty()) {
            // An iOS list-row Label centres its icon 14 in and starts the title 44 in.
            Row(verticalAlignment = Alignment.CenterVertically) {
                JerreaderGlyph(
                    icon = JerreaderIcon.BOOK_CLOSED,
                    tint = tertiary,
                    modifier = Modifier.padding(start = 7.dp, end = 23.dp).size(14.dp)
                )
                Text(
                    text = bookTitle,
                    style = JerreaderLearningType.caption,
                    color = tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** iOS `JerreaderEmptyState`: a 68 accentFill tile over a title3 title and a message, revealed. */
@Composable
fun JerreaderEmptyState(
    title: String,
    message: String,
    icon: JerreaderIcon,
    modifier: Modifier = Modifier
) {
    val colors = LocalJerreaderColors.current
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 340.dp)
                .padding(28.dp)
                .jerreaderReveal()
                .semantics(mergeDescendants = true) { },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .background(colors.accentFill, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center
            ) {
                JerreaderGlyph(icon = icon, tint = colors.accent, modifier = Modifier.size(30.dp))
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    text = title,
                    style = JerreaderLearningType.title3.copy(fontWeight = FontWeight.SemiBold),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = message,
                    style = JerreaderLearningType.subheadline,
                    color = MaterialTheme.colorScheme.plainSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * The app's single relative-time policy, matching iOS `JerreaderRelativeTime`:
 * minute, hour and day buckets only, so a list of records never reads like a
 * running clock.
 */
object JerreaderRelativeTime {
    fun format(epochMillis: Long, nowMillis: Long): String {
        val elapsed = ((nowMillis - epochMillis) / 1000L).coerceAtLeast(0L)
        return when {
            elapsed < 60 -> "刚刚"
            elapsed < 3_600 -> "${(elapsed / 60).coerceAtLeast(1)} 分钟前"
            elapsed < 86_400 -> "${(elapsed / 3_600).coerceAtLeast(1)} 小时前"
            else -> "${(elapsed / 86_400).coerceAtLeast(1)} 天前"
        }
    }
}
