package com.jerreader.shared.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/**
 * The controls the standalone translator is built from, ported from the iOS 2.0
 * `TranslateToolView`: a segmented mode picker and a muted provider row above
 * three paper cards (language bar, input, result), each radius 20.
 */

/**
 * Full-width iOS segmented picker: a grey track with the selected segment
 * raised in paper white. Unlike [JerreaderSegmented] every option gets an equal
 * share of the width instead of scrolling.
 */
@Composable
fun <T> JerreaderSegmentedPicker(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
            shape = RoundedCornerShape(9.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        ) {
            Row(
                modifier = Modifier.padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                options.forEach { (value, title) ->
                    val isSelected = value == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 28.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(
                                if (isSelected) {
                                    if (MaterialTheme.colorScheme.onSurface == Color.Black) Color.White else LocalJerreaderColors.current.raisedPaper
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable { onSelect(value) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp),
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

/**
 * The iOS provider `Menu` row: the grey caption label on the left, the current
 * value and a chevron on the right, on a muted 14-radius ground, padding h16,
 * at least 44 tall.
 */
@Composable
fun <T> JerreaderMenuField(
    label: String,
    icon: JerreaderIcon,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    accessibilityLabel: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val secondary = MaterialTheme.colorScheme.plainSecondary
    val title = options.firstOrNull { it.first == selected }?.second.orEmpty()
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(JerreaderRadius.control))
                .background(LocalJerreaderColors.current.mutedSurface)
                .then(
                    if (accessibilityLabel != null) Modifier.semantics { contentDescription = accessibilityLabel }
                    else Modifier
                )
                .clickable(role = Role.Button) { expanded = true }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            JerreaderGlyph(icon = icon, tint = secondary, modifier = Modifier.size(13.dp))
            Text(
                text = label,
                style = JerreaderLearningType.caption.copy(fontWeight = FontWeight.SemiBold),
                color = secondary,
                maxLines = 1
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = title,
                style = JerreaderLearningType.subheadline.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            JerreaderGlyph(icon = JerreaderIcon.CHEVRON_DOWN, tint = secondary, modifier = Modifier.size(13.dp))
        }
        JerreaderMenuOptions(
            expanded = expanded,
            options = options,
            selected = selected,
            onDismiss = { expanded = false },
            onSelect = onSelect
        )
    }
}

/** A language menu of the language bar: the value and a 12 chevron, centred, 44 tall. */
@Composable
fun <T> JerreaderInlineMenu(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    accessibilityLabel: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val title = options.firstOrNull { it.first == selected }?.second.orEmpty()
    val foreground = MaterialTheme.colorScheme.onSurface
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(JerreaderRadius.control))
                .then(
                    if (accessibilityLabel != null) Modifier.semantics { contentDescription = accessibilityLabel }
                    else Modifier
                )
                .clickable(role = Role.Button) { expanded = true },
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = JerreaderLearningType.subheadline.copy(fontWeight = FontWeight.SemiBold),
                color = foreground,
                modifier = Modifier.weight(1f, fill = false),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            JerreaderGlyph(icon = JerreaderIcon.CHEVRON_DOWN, tint = foreground, modifier = Modifier.size(12.dp))
        }
        JerreaderMenuOptions(
            expanded = expanded,
            options = options,
            selected = selected,
            onDismiss = { expanded = false },
            onSelect = onSelect
        )
    }
}

/**
 * The menu's choices, the current one checked as in an iOS picker menu. The
 * popup is a raised-paper panel with a hairline instead of a Material shadow.
 */
@Composable
private fun <T> JerreaderMenuOptions(
    expanded: Boolean,
    options: List<Pair<T, String>>,
    selected: T,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit
) {
    val colors = LocalJerreaderColors.current
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(JerreaderRadius.control),
        containerColor = colors.raisedPaper,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(0.75.dp, colors.line)
    ) {
        options.forEach { (value, title) ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = title,
                        style = JerreaderLearningType.body,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                leadingIcon = {
                    Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                        if (value == selected) {
                            JerreaderGlyph(
                                icon = JerreaderIcon.CHECK,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                },
                onClick = {
                    onDismiss()
                    onSelect(value)
                }
            )
        }
    }
}

/**
 * The swap button between the two language menus: a 44 circle of accentFill
 * (muted when disabled) that presses to 0.9 and turns its arrows a further 180°
 * on every tap with the stateChange spring.
 */
@Composable
fun JerreaderSwapButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalJerreaderColors.current
    val interaction = remember { MutableInteractionSource() }
    var turns by remember { mutableIntStateOf(0) }
    val rotation by animateFloatAsState(
        targetValue = turns * 180f,
        animationSpec = JerreaderMotion.stateChange(),
        label = "swap-turn"
    )
    Box(
        modifier = modifier
            .size(44.dp)
            .jerreaderPressScale(interaction, pressedScale = 0.9f)
            .background(if (enabled) colors.accentFill else colors.mutedSurface, CircleShape)
            .clip(CircleShape)
            .semantics { contentDescription = "交换原文和译文语言" }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button
            ) {
                turns += 1
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        JerreaderGlyph(
            icon = JerreaderIcon.SWAP,
            tint = if (enabled) colors.accent else MaterialTheme.colorScheme.plainSecondary,
            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation }
        )
    }
}

/** One of the translator's paper cards (radius 20, e1); it draws no padding of its own. */
@Composable
fun JerreaderTranslatorCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth().jerreaderPaperCard(), content = content)
}

/** The language bar card: padding h8 v4, the two menus either side of the swap button. */
@Composable
fun JerreaderLanguageBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .jerreaderPaperCard()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** Vermilion notice on vermilion 10%, radius 14, as on iOS. */
@Composable
fun JerreaderErrorCard(message: String, modifier: Modifier = Modifier) {
    val vermilion = jerreaderPalette.vermilion
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(vermilion.copy(alpha = 0.10f), RoundedCornerShape(JerreaderRadius.control))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        JerreaderGlyph(icon = JerreaderIcon.WARNING, tint = vermilion, modifier = Modifier.size(17.dp))
        Text(text = message, style = JerreaderLearningType.subheadline, color = vermilion)
    }
}

/**
 * Borderless accent action with a leading glyph, subheadline semibold, as in
 * the iOS result row. A new [icon] cross-fades in; [bounceTrigger] bounces it.
 */
@Composable
fun JerreaderInlineAction(
    text: String,
    icon: JerreaderIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    bounceTrigger: Any? = null
) {
    val colors = LocalJerreaderColors.current
    val tint = if (enabled) colors.accent else colors.secondaryText
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.6f else 1f,
        animationSpec = if (pressed) JerreaderMotion.press() else JerreaderMotion.release(),
        label = "inline-action-press"
    )
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .alpha(pressAlpha)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Crossfade(
            targetState = icon,
            animationSpec = JerreaderLearningMotion.quick(),
            modifier = Modifier.jerreaderBounce(bounceTrigger),
            label = "inline-action-icon"
        ) { shown ->
            JerreaderGlyph(icon = shown, tint = tint, modifier = Modifier.size(16.dp))
        }
        Text(
            text = text,
            style = JerreaderLearningType.subheadline.copy(fontWeight = FontWeight.SemiBold),
            color = tint,
            maxLines = 1
        )
    }
}

/**
 * The result card's header, always at least 28 tall: the target-language label
 * (speech bubble 13, caption semibold, tracking 0.6, accent), then the
 * [planIndicator] and the [accessory] (the 日语注音 toggle) at the trailing edge.
 * The indicator is a slot so the app's one `ChatGPTPlanIndicator` is drawn
 * here, not a second copy; leave it empty while the plan is not in use.
 *
 * As on iOS it stays one line: when everything doesn't fit it drops the label
 * (it repeats the target-language menu), and only when the indicator and the
 * accessory alone don't fit does it take two lines — label and accessory, then
 * the indicator under them, 6 apart, trailing.
 */
@Composable
fun JerreaderTranslationResultHeader(
    languageLabel: String,
    modifier: Modifier = Modifier,
    planIndicator: @Composable () -> Unit = {},
    accessory: @Composable () -> Unit = {}
) {
    val accent = jerreaderPalette.accent
    val label: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            JerreaderGlyph(icon = JerreaderIcon.SPEECH_BUBBLE, tint = accent, modifier = Modifier.size(13.dp))
            Text(
                text = languageLabel,
                style = JerreaderLearningType.caption.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    Layout(
        contents = listOf(label, planIndicator, accessory),
        modifier = modifier.fillMaxWidth().heightIn(min = 28.dp)
    ) { (labelParts, indicatorParts, accessoryParts), constraints ->
        val gap = 8.dp.roundToPx()
        val labelGap = 16.dp.roundToPx()
        val lineGap = 6.dp.roundToPx()
        val labelPart = labelParts.firstOrNull()
        val indicatorPart = indicatorParts.firstOrNull()
        val accessoryPart = accessoryParts.firstOrNull()
        val trailingParts = listOfNotNull(indicatorPart, accessoryPart)
        val trailingWidth = trailingParts.sumOf { it.maxIntrinsicWidth(Constraints.Infinity) } +
            gap * (trailingParts.size - 1).coerceAtLeast(0)
        val labelWidth = labelPart?.maxIntrinsicWidth(Constraints.Infinity) ?: 0
        val natural = if (trailingParts.isEmpty()) labelWidth else labelWidth + labelGap + trailingWidth
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else max(natural, constraints.minWidth)
        val loose = Constraints(maxWidth = width)
        val oneLine = trailingParts.isEmpty() || labelWidth + labelGap + trailingWidth <= width
        when {
            oneLine || trailingWidth <= width -> {
                // One line; the label only if it fits beside the rest.
                val trailing = trailingParts.map { it.measure(loose) }
                val shownLabel = if (oneLine) {
                    val room = if (trailingParts.isEmpty()) width else (width - trailingWidth - labelGap).coerceAtLeast(0)
                    labelPart?.measure(Constraints(maxWidth = room))
                } else null
                val height = max(
                    constraints.minHeight,
                    max(shownLabel?.height ?: 0, trailing.maxOfOrNull(Placeable::height) ?: 0)
                )
                layout(width, height) {
                    shownLabel?.placeRelative(0, (height - shownLabel.height) / 2)
                    var x = width
                    trailing.asReversed().forEach { placeable ->
                        x -= placeable.width
                        placeable.placeRelative(x, (height - placeable.height) / 2)
                        x -= gap
                    }
                }
            }
            else -> {
                // Two lines: label and accessory, then the indicator, trailing.
                val accessoryPlaceable = accessoryPart?.measure(loose)
                val room = (width - (accessoryPlaceable?.width ?: 0) - labelGap).coerceAtLeast(0)
                val shownLabel = labelPart?.measure(Constraints(maxWidth = room))
                val indicatorPlaceable = indicatorPart?.measure(loose)
                val firstHeight = max(shownLabel?.height ?: 0, accessoryPlaceable?.height ?: 0)
                val secondHeight = indicatorPlaceable?.height ?: 0
                val height = max(constraints.minHeight, firstHeight + lineGap + secondHeight)
                layout(width, height) {
                    shownLabel?.placeRelative(0, (firstHeight - shownLabel.height) / 2)
                    accessoryPlaceable?.placeRelative(
                        width - accessoryPlaceable.width,
                        (firstHeight - accessoryPlaceable.height) / 2
                    )
                    indicatorPlaceable?.placeRelative(
                        width - indicatorPlaceable.width,
                        firstHeight + lineGap
                    )
                }
            }
        }
    }
}

/**
 * The iOS button-style 日语注音 toggle, small and capsule-shaped: accent text,
 * on an accent-tinted capsule while it is on.
 */
@Composable
fun JerreaderFuriganaToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalJerreaderColors.current
    Box(
        modifier = modifier
            .heightIn(min = 28.dp)
            .clip(CircleShape)
            .background(if (checked) colors.accent.copy(alpha = 0.15f) else Color.Transparent)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "日语注音",
            style = JerreaderLearningType.caption.copy(fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium),
            color = colors.accent,
            maxLines = 1
        )
    }
}

/** The trailing 缓存 chip of the result actions: caption2 semibold secondary on a muted capsule. */
@Composable
fun JerreaderCacheChip(modifier: Modifier = Modifier) {
    Text(
        text = "缓存",
        modifier = modifier
            .background(LocalJerreaderColors.current.mutedSurface, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        style = JerreaderLearningType.caption2.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.plainSecondary,
        maxLines = 1
    )
}
