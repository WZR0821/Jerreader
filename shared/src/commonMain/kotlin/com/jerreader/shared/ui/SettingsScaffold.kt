package com.jerreader.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

/**
 * Settings pages follow the iOS hierarchy: a root list of navigation rows
 * grouped into 偏好 / 支持 / 关于, each opening its own titled sub-page. The
 * previous Android build inlined everything onto one screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
            if (onBack == null) {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp)) {
                    Spacer(Modifier.heightIn(min = 44.dp))
                    Text(title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(bottom = 8.dp))
                }
            } else JerreaderNavigationBar(title, "设置", onBack)
        },
        bottomBar = bottomBar
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 800.dp).fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (onBack == null) 22.dp else 28.dp)
        ) { content() }
        }
    }
}

@Composable
fun SettingsRootLayout(preferences: @Composable () -> Unit, support: @Composable () -> Unit, about: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 658.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(22.dp)) { preferences(); about() }
                Column(Modifier.weight(1f)) { support() }
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(22.dp)) { preferences(); support(); about() }
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
    compact: Boolean = false,
    accentedHeader: Boolean = false,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title.isNotEmpty()) Row(
            modifier = Modifier.padding(start = if (compact) 5.dp else 20.dp, bottom = if (compact) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (compact || accentedHeader) JerreaderGlyph(icon,
                if (accentedHeader) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                Modifier.size(if (compact) 12.dp else 17.dp))
            Text(title, style = if (accentedHeader) MaterialTheme.typography.titleSmall else if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                color = if (accentedHeader) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(if (compact) 15.dp else 10.dp),
            border = if (compact) BorderStroke(0.7.dp, MaterialTheme.colorScheme.outline) else null,
            color = if (compact) MaterialTheme.colorScheme.surface else if (MaterialTheme.colorScheme.onSurface == Color.Black) Color.White else Color(0xFF1C1C1E)
        ) {
            Column { content() }
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
                .heightIn(min = 60.dp).padding(vertical = 8.dp, horizontal = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            JerreaderGlyph(
                icon = icon,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
                modifier = Modifier.size(13.dp)
            )
        }
        // iOS insets the separator so it starts past the leading icon.
        if (showDivider) {
            JerreaderListDivider(modifier = Modifier.padding(start = 45.dp))
        }
    }
}

/** Read-only values use compact cards at the root and standard Form rows below it. */
@Composable
fun SettingsValueRow(label: String, value: String, modifier: Modifier = Modifier, highlightsValue: Boolean = false, compact: Boolean = true) {
    val content: @Composable RowScope.() -> Unit = {
        val style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
        Text(label, modifier = Modifier.weight(1f), style = style)
        Text(value,
            modifier = if (compact) Modifier else Modifier.weight(1.2f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            style = style,
            fontFamily = if (highlightsValue) FontFamily.Serif else null,
            fontWeight = if (highlightsValue) FontWeight.SemiBold else null,
            color = if (highlightsValue) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (compact) Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 8.dp, horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically, content = content
    ) else SettingsRowFrame(modifier = modifier, content = content)
}

/**
 * The rows below give the settings sub-pages the same anatomy as an iOS `Form`
 * section — a label on the left and its control on the right, separated by
 * inset hairlines. The Android sub-pages previously scattered bare headings and
 * floating chips across the canvas with no card around them at all.
 */
private val rowPadding = PaddingValues(horizontal = 20.dp, vertical = 6.dp)

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
        if (showDivider) JerreaderListDivider(modifier = Modifier.padding(start = 16.dp))
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
    showDivider: Boolean = true
) {
    SettingsRowFrame(modifier = modifier, showDivider = showDivider) {
        Text(
            title,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        JerreaderToggle(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled, label = title)
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
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    options.firstOrNull { it.first == selected }?.second.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                JerreaderGlyph(
                    icon = JerreaderIcon.CHEVRON_UP_DOWN,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp).size(13.dp)
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                        onClick = {
                            expanded = false
                            onSelect(value)
                        }
                    )
                }
            }
        }
        if (showDivider) JerreaderListDivider(modifier = Modifier.padding(start = 16.dp))
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
    Column(modifier = modifier.fillMaxWidth().semantics { contentDescription = title }) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(rowPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            JerreaderSegmentedPicker(
                options = options,
                selected = selected,
                onSelect = { if (enabled) onSelect(it) }
            )
        }
        if (showDivider) JerreaderListDivider(modifier = Modifier.padding(start = 16.dp))
    }
}

/**
 * `LabeledContent` over a `Slider`: title on the left, the current value on the
 * right, and the track underneath. iOS uses continuous sliders for the reading
 * measurements rather than the reader panel's plus/minus steppers.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    valueLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = range,
                steps = steps,
                thumb = {
                    Box(Modifier.size(28.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White))
                },
                track = {
                    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .12f))) {
                        Box(Modifier.fillMaxWidth(fraction).height(4.dp)
                            .background(MaterialTheme.colorScheme.primary))
                    }
                }
            )
        }
        if (showDivider) JerreaderListDivider(modifier = Modifier.padding(start = 16.dp))
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
            modifier = Modifier.heightIn(min = 44.dp).padding(horizontal = 20.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (showDivider) JerreaderListDivider(modifier = Modifier.padding(start = 16.dp))
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
        if (showDivider) JerreaderListDivider(modifier = Modifier.padding(start = 16.dp))
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
                style = MaterialTheme.typography.bodyLarge
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
        if (showDivider) JerreaderListDivider(modifier = Modifier.padding(start = 16.dp))
    }
}

/** Accent-coloured row that performs an action, like iOS's plain `Button`. */
@Composable
fun SettingsActionRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showDivider: Boolean = true,
    icon: JerreaderIcon? = null,
    disclosureExpanded: Boolean? = null
) {
    SettingsRowFrame(
        modifier = modifier,
        showDivider = showDivider,
        onClick = if (enabled) onClick else null
    ) {
        icon?.let {
            JerreaderGlyph(it, MaterialTheme.colorScheme.primary, Modifier.padding(end = 12.dp).size(20.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (disclosureExpanded != null) {
                MaterialTheme.colorScheme.onSurface
            } else if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        if (disclosureExpanded != null) {
            Spacer(Modifier.weight(1f))
            JerreaderGlyph(if (disclosureExpanded) JerreaderIcon.CHEVRON_DOWN else JerreaderIcon.CHEVRON_FORWARD,
                MaterialTheme.colorScheme.primary, Modifier.size(13.dp))
        }
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
                .heightIn(min = 78.dp)
                .padding(horizontal = 20.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .shadow(4.dp, CircleShape)
                    .clip(CircleShape)
                    .background(swatch)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Box(Modifier.size(18.dp).background(swatch, CircleShape), contentAlignment = Alignment.Center) {
                    JerreaderGlyph(JerreaderIcon.CHECK, Color.White, Modifier.size(12.dp))
                }
            }
        }
        if (showDivider) JerreaderListDivider(modifier = Modifier.padding(start = 66.dp))
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
        if (showDivider) JerreaderListDivider(modifier = Modifier.padding(start = 16.dp))
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
                .padding(horizontal = 20.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                JerreaderGlyph(icon, MaterialTheme.colorScheme.primary, Modifier.size(18.dp))
            }
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
        if (showDivider) JerreaderListDivider(modifier = Modifier.padding(start = 64.dp))
    }
}

/** Explanatory paragraph under a group, matching an iOS Section footer. */
@Composable
fun SettingsGroupFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}


@Composable
fun SettingsTextFieldRow(title: String, value: String, onChange: (String) -> Unit) {
    SettingsInsetRow {
        androidx.compose.foundation.text.BasicTextField(value, onChange,
            modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp).semantics { contentDescription = title },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true, decorationBox = { input ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(title, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    input()
                }
            })
    }
}
