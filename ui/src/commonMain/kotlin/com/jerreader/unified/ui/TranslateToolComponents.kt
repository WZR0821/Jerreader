package com.jerreader.unified.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * The controls the standalone translator is built from, ported from the iOS
 * `TranslateToolView`. The Android page used to stack labelled accent-filled
 * segments inside one big card, which read nothing like the iOS layout: a
 * segmented picker and a provider menu float above a single card that holds the
 * language bar, the input and the result.
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
    Surface(
        modifier = modifier.fillMaxWidth().height(34.dp),
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
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
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(7.dp))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            } else {
                                Color.Transparent
                            }
                        )
                        .clickable { onSelect(value) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * Grey caption on the left, current value and a chevron on the right, closed by
 * a hairline — the iOS `Menu` row that picks the translation service.
 */
@Composable
fun <T> JerreaderMenuField(
    label: String,
    icon: JerreaderIcon,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable { expanded = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                JerreaderGlyph(
                    icon = icon,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = label,
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = options.firstOrNull { it.first == selected }?.second.orEmpty(),
                    style = MaterialTheme.typography.titleSmall
                )
                JerreaderGlyph(
                    icon = JerreaderIcon.CHEVRON_DOWN,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp).size(13.dp)
                )
            }
            JerreaderMenuOptions(
                expanded = expanded,
                options = options,
                onDismiss = { expanded = false },
                onSelect = onSelect
            )
        }
        JerreaderDivider()
    }
}

/** Centred value and chevron, sized to share the language bar evenly. */
@Composable
fun <T> JerreaderInlineMenu(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = true },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = options.firstOrNull { it.first == selected }?.second.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1
            )
            JerreaderGlyph(
                icon = JerreaderIcon.CHEVRON_DOWN,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 5.dp).size(12.dp)
            )
        }
        JerreaderMenuOptions(
            expanded = expanded,
            options = options,
            onDismiss = { expanded = false },
            onSelect = onSelect
        )
    }
}

@Composable
private fun <T> JerreaderMenuOptions(
    expanded: Boolean,
    options: List<Pair<T, String>>,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        options.forEach { (value, title) ->
            DropdownMenuItem(
                text = { Text(title, style = MaterialTheme.typography.bodyMedium) },
                onClick = {
                    onDismiss()
                    onSelect(value)
                }
            )
        }
    }
}

/** The round accent-tinted button between the two language menus. */
@Composable
fun JerreaderSwapButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalJerreaderColors.current
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            // The language bar already sits on `mutedSurface`, so the button
            // needs a stronger accent wash than `accentFill` to read at all.
            .background(
                if (enabled) colors.accent.copy(alpha = 0.14f) else Color.Transparent
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        JerreaderGlyph(
            icon = JerreaderIcon.SWAP,
            tint = if (enabled) colors.accent else colors.secondaryText,
            modifier = Modifier.size(19.dp)
        )
    }
}

/**
 * The translator card itself. It draws no padding of its own so the language
 * bar can run edge to edge the way it does on iOS.
 */
@Composable
fun JerreaderTranslatorCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(content = content)
    }
}

/** Tinted strip at the top of the card holding the two language menus. */
@Composable
fun JerreaderLanguageBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(LocalJerreaderColors.current.mutedSurface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** Orange notice used for a failed translation, matching the iOS error card. */
@Composable
fun JerreaderErrorCard(message: String, modifier: Modifier = Modifier) {
    val warning = Color(0.85f, 0.45f, 0.10f)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = warning.copy(alpha = 0.10f)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            JerreaderGlyph(
                icon = JerreaderIcon.WARNING,
                tint = warning,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = warning
            )
        }
    }
}

/** Borderless text action with a leading glyph, as in the iOS result row. */
@Composable
fun JerreaderInlineAction(
    text: String,
    icon: JerreaderIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        JerreaderGlyph(icon = icon, tint = tint, modifier = Modifier.size(16.dp))
        Text(
            text = text,
            modifier = Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = tint
        )
    }
}
