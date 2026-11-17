package com.jerreader.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The one card shape used across the app: paper surface, hairline outline and
 * the iOS 16dp corner. Explicit borders are what previously made the settings
 * and library screens read as an undifferentiated wall of controls.
 */
@Composable
fun JerreaderCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

/** Card with a section title, used for every settings group. */
@Composable
fun JerreaderSection(
    title: String,
    modifier: Modifier = Modifier,
    footnote: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    JerreaderCard(modifier = modifier) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Column(
            modifier = Modifier.padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
        footnote?.let {
            Text(
                text = it,
                modifier = Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Label on the left, control on the right, aligned across every row. */
@Composable
fun JerreaderRow(
    label: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    control: @Composable () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        control()
    }
}

@Composable
fun JerreaderDivider(modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.outline) {
    HorizontalDivider(
        modifier = modifier,
        color = color, thickness = 0.5.dp
    )
}

/**
 * Compact segmented control. Replaces the oversized filled/text button pairs
 * that made every choice look like a primary action.
 */
@Composable
fun <T> JerreaderSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.heightIn(min = 32.dp),
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        // Choice labels such as "AI 代理" and the Japanese language names are
        // intentionally kept on one line.  A horizontal scroll viewport is
        // preferable to letting a parent measure the children at a tiny width
        // (which used to clip the last button or push it into a second line in
        // narrow Android windows).
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(2.dp)
        ) {
            options.forEach { (value, title) ->
                val isSelected = value == selected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            }
                        )
                        .clickable { onSelect(value) }
                        .height(28.dp)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}

/** Small stepper for numeric reading preferences. */
@Composable
fun JerreaderStepper(
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.heightIn(min = 32.dp),
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperButton("−", canDecrease, onDecrease)
            Text(
                text = value,
                modifier = Modifier.padding(horizontal = 10.dp),
                style = MaterialTheme.typography.labelLarge
            )
            StepperButton("+", canIncrease, onIncrease)
        }
    }
}

@Composable
private fun StepperButton(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/** UIKit's `separator`: 60/60/67 at 29% in light, 84/84/88 at 60% in dark. */
@Composable
private fun listSeparatorColor(): androidx.compose.ui.graphics.Color {
    val dark = MaterialTheme.colorScheme.onSurface != androidx.compose.ui.graphics.Color.Black
    return if (dark) androidx.compose.ui.graphics.Color(0x99545458) else androidx.compose.ui.graphics.Color(0x4A3C3C43)
}

/** One device pixel, the width iOS draws its list separators at. */
@Composable
private fun hairline(): androidx.compose.ui.unit.Dp = with(androidx.compose.ui.platform.LocalDensity.current) { 1.toDp() }

/** SwiftUI Divider uses a neutral separator inside lists, distinct from the accent card outline. */
@Composable
fun JerreaderListDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, color = listSeparatorColor(), thickness = hairline())
}

/**
 * SwiftUI's `Divider().overlay(JerreaderTheme.line)` under the search bar of
 * the vocabulary and the lookup history: the system separator with the theme
 * line laid over it, one pixel thick and darker than the theme line alone.
 */
@Composable
fun JerreaderOverlaidDivider(modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().height(hairline())
            .background(listSeparatorColor())
            .background(LocalJerreaderColors.current.line)
    )
}

/**
 * A Form row's separator, inset by [start] to the row's text. UIKit draws it
 * over the bottom edge of the row above rather than below it, so a 44-point
 * row is still 44 points with its line; this takes no height either.
 */
@Composable
fun JerreaderRowSeparator(start: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
            }
            .padding(start = start),
        color = listSeparatorColor(),
        thickness = hairline()
    )
}

/**
 * The chevron at the end of an iOS list row (`chevron.right`, footnote,
 * semibold): 6.7 × 11.3 points of ink at [scale] 1. With [alignEnd] the ink
 * ends 0.7 inside the box, so a box that ends at the row's margin puts it
 * where iOS does, and the 10.1-point width leaves the text before it, after
 * the usual 8-point gap, where iOS ends it. Otherwise the ink is centred.
 * [rotation] turns it about the ink's centre: 90 points it down.
 */
@Composable
fun JerreaderRowChevron(
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    rotation: Float = 0f,
    scale: Float = 1f,
    alignEnd: Boolean = true
) {
    androidx.compose.foundation.Canvas(modifier.then(Modifier.size(width = 10.1.dp, height = 13.dp))) {
        val stroke = 1.9.dp.toPx() * scale
        val halfWidth = 4.8.dp.toPx() * scale / 2
        val halfHeight = 9.4.dp.toPx() * scale / 2
        val inkHalfWidth = halfWidth + stroke / 2
        val centerX = if (alignEnd) size.width - 0.7.dp.toPx() - inkHalfWidth else size.width / 2
        val center = androidx.compose.ui.geometry.Offset(centerX, size.height / 2)
        rotate(rotation, pivot = center) {
            drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(center.x - halfWidth, center.y - halfHeight)
                    lineTo(center.x + halfWidth, center.y)
                    lineTo(center.x - halfWidth, center.y + halfHeight)
                },
                tint,
                style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

/**
 * The menu-picker mark (`chevron.up.chevron.down`, footnote, semibold): two
 * chevrons 8.7 points wide, 5.3 tall each and 13 tall together. Centred in the
 * box, or with [endInset] ending that far inside its trailing edge, where it may
 * reach past the leading edge of a narrow box.
 */
@Composable
fun JerreaderUpDownChevron(
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    endInset: androidx.compose.ui.unit.Dp? = null
) {
    androidx.compose.foundation.Canvas(modifier) {
        val stroke = 1.6.dp.toPx()
        val inkWidth = 8.7.dp.toPx()
        val halfWidth = (inkWidth - stroke) / 2
        val armHeight = 5.3.dp.toPx() - stroke
        val cx = if (endInset == null) size.width / 2 else size.width - endInset.toPx() - inkWidth / 2
        val top = size.height / 2 - 6.5.dp.toPx() + stroke / 2
        val bottom = size.height / 2 + 6.5.dp.toPx() - stroke / 2
        val line = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(cx - halfWidth, top + armHeight)
                lineTo(cx, top)
                lineTo(cx + halfWidth, top + armHeight)
            },
            tint,
            style = line
        )
        drawPath(
            androidx.compose.ui.graphics.Path().apply {
                moveTo(cx - halfWidth, bottom - armHeight)
                lineTo(cx, bottom)
                lineTo(cx + halfWidth, bottom - armHeight)
            },
            tint,
            style = line
        )
    }
}

/** The indicator UIKit puts on a `NavigationLink` row: 7 × 12 points, black at 25 %. */
@Composable
fun JerreaderNavigationChevron(modifier: Modifier = Modifier) {
    JerreaderRowChevron(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f), modifier, scale = 1.062f)
}

/** A `DisclosureGroup`'s indicator: the accent chevron that turns down as the group opens. */
@Composable
fun JerreaderDisclosureChevron(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
) {
    val angle = androidx.compose.animation.core.animateFloatAsState(if (expanded) 90f else 0f, label = "disclosure").value
    JerreaderRowChevron(tint, modifier, rotation = angle, scale = 1.062f)
}
