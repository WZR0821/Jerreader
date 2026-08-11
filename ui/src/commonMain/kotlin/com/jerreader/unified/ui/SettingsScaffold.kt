package com.jerreader.unified.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Settings pages follow the iOS hierarchy: a root list of navigation rows
 * grouped into 偏好 / 支持 / 关于, each opening its own titled sub-page. The
 * previous Android build inlined everything onto one screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsPage(
    title: String,
    onBack: (() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    onBack?.let {
                        TextButton(onClick = it) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                JerreaderGlyph(
                                    icon = JerreaderIcon.CHEVRON_LEFT,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text("设置", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = bottomBar
    ) { padding ->
        // A page whose content already fits must not move. The scroll range is
        // zero then, but Android's stretch overscroll still lets the whole page
        // be dragged against a rubber band, and what that reads as is blank
        // space above and below the settings rather than a page that ends where
        // it ends. Without the effect the page is fixed when it fits and scrolls
        // only when it genuinely has more than a screenful to show.
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) { content() }
        }
    }
}

/**
 * `SettingsSectionHeader`: an accent-tinted icon and an accent-tinted title
 * above a plain white card. iOS does not use a grey caption here.
 */
@Composable
fun SettingsGroup(
    title: String,
    icon: JerreaderIcon = JerreaderIcon.INFO,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            JerreaderGlyph(
                icon = icon,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(17.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.padding(vertical = 2.dp)) { content() }
        }
        footer?.let { SettingsGroupFooter(it) }
    }
}

/** Row that opens a sub-page: leading icon, title, detail, trailing chevron. */
@Composable
fun SettingsNavigationRow(
    title: String,
    detail: String,
    icon: JerreaderIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 11.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            JerreaderGlyph(
                icon = icon,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp).padding(top = 1.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            JerreaderGlyph(
                icon = JerreaderIcon.CHEVRON_FORWARD,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp).align(Alignment.CenterVertically)
            )
        }
        // iOS insets the separator so it starts past the leading icon.
        if (showDivider) {
            JerreaderDivider(modifier = Modifier.padding(start = 54.dp))
        }
    }
}

/** Read-only "label — value" row used by the 关于 group. */
@Composable
fun SettingsValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 9.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The rows below give the settings sub-pages the same anatomy as an iOS `Form`
 * section — a label on the left and its control on the right, separated by
 * inset hairlines. The Android sub-pages previously scattered bare headings and
 * floating chips across the canvas with no card around them at all.
 */
private val rowPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp)

@Composable
private fun SettingsRowFrame(
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 44.dp)
                .padding(rowPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
        if (showDivider) JerreaderDivider(modifier = Modifier.padding(start = 16.dp))
    }
}

/** `Toggle("…", isOn:)`. */
@Composable
fun SettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showDivider: Boolean = true,
    detail: String? = null,
    icon: JerreaderIcon? = null
) {
    SettingsRowFrame(modifier = modifier, showDivider = showDivider) {
        if (icon != null) {
            JerreaderGlyph(
                icon = icon,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
        }
        if (detail == null) {
            Text(
                title,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        } else {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** `Picker` in its default Form style: label, current value, chevron, menu. */
@Composable
fun <T> SettingsMenuRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showDivider: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { expanded = true }
                    .heightIn(min = 44.dp)
                    .padding(rowPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    options.firstOrNull { it.first == selected }?.second.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                JerreaderGlyph(
                    icon = JerreaderIcon.CHEVRON_DOWN,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp).size(13.dp)
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        }
                    )
                }
            }
        }
        if (showDivider) JerreaderDivider(modifier = Modifier.padding(start = 16.dp))
    }
}

/** `Picker(…).pickerStyle(.segmented)`: label above a full-width control. */
@Composable
fun <T> SettingsSegmentedRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showDivider: Boolean = true
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(rowPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            JerreaderSegmentedPicker(
                options = options,
                selected = selected,
                onSelect = { if (enabled) onSelect(it) }
            )
        }
        if (showDivider) JerreaderDivider(modifier = Modifier.padding(start = 16.dp))
    }
}

/**
 * `LabeledContent` over a `Slider`: title on the left, the current value on the
 * right, and the track underneath. iOS uses continuous sliders for the reading
 * measurements rather than the reader panel's plus/minus steppers.
 */
@Composable
fun SettingsSliderRow(
    title: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 4.dp
            )
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    valueLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = range,
                steps = steps
            )
        }
        if (showDivider) JerreaderDivider(modifier = Modifier.padding(start = 16.dp))
    }
}

/** The grey caption iOS drops between rows to explain the one above. */
@Composable
fun SettingsFootnote(
    text: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (showDivider) JerreaderDivider(modifier = Modifier.padding(start = 16.dp))
    }
}

/** A caption in the accent's warning tone, for an invalid combination. */
@Composable
fun SettingsWarningNote(
    text: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true
) {
    val warning = Color(0.85f, 0.45f, 0.10f)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            JerreaderGlyph(
                icon = JerreaderIcon.WARNING,
                tint = warning,
                modifier = Modifier.padding(top = 2.dp).size(14.dp)
            )
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = warning
            )
        }
        if (showDivider) JerreaderDivider(modifier = Modifier.padding(start = 16.dp))
    }
}

/** `DisclosureGroup`: a tappable title that reveals its content below. */
@Composable
fun SettingsDisclosureGroup(
    title: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .heightIn(min = 44.dp)
                .padding(rowPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            JerreaderGlyph(
                icon = if (expanded) JerreaderIcon.CHEVRON_DOWN else JerreaderIcon.CHEVRON_FORWARD,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp)
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
        if (showDivider) JerreaderDivider(modifier = Modifier.padding(start = 16.dp))
    }
}

/** Accent-coloured row that performs an action, like iOS's plain `Button`. */
@Composable
fun SettingsActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showDivider: Boolean = true
) {
    SettingsRowFrame(
        modifier = modifier,
        showDivider = showDivider,
        onClick = if (enabled) onClick else null
    ) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

/**
 * Selection row with a leading colour swatch and a trailing check, the shape
 * the iOS 界面主题 page uses.
 */
@Composable
fun SettingsSwatchRow(
    title: String,
    detail: String,
    swatch: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(swatch)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                JerreaderGlyph(
                    icon = JerreaderIcon.CHECK_CIRCLE,
                    tint = swatch,
                    modifier = Modifier.size(21.dp)
                )
            }
        }
        if (showDivider) JerreaderDivider(modifier = Modifier.padding(start = 62.dp))
    }
}

/**
 * Free-form row inset to the section's margins, for the text fields and other
 * platform controls a settings page needs to embed in the card.
 */
@Composable
fun SettingsInsetRow(
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
        if (showDivider) JerreaderDivider(modifier = Modifier.padding(start = 16.dp))
    }
}

/** `Label(…, systemImage:)` in a List: a tinted glyph beside a line of text. */
@Composable
fun SettingsLabelRow(
    text: String,
    icon: JerreaderIcon,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            JerreaderGlyph(
                icon = icon,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 1.dp).size(19.dp)
            )
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        if (showDivider) JerreaderDivider(modifier = Modifier.padding(start = 47.dp))
    }
}

/** Explanatory paragraph under a group, matching an iOS Section footer. */
@Composable
fun SettingsGroupFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
