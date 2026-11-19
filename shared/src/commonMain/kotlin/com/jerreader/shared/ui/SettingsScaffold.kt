package com.jerreader.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
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
    // A pushed page is a plain iOS `Form`, spaced as measured there.
    val form = onBack != null
    Scaffold(
        // The 2.0 canvas, with its faint accent wash, as iOS draws behind every settings list.
        modifier = Modifier.jerreaderCanvasBackground(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
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
                .padding(top = if (form) 16.dp else 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (form) PlainFormGroupSpacing else 22.dp)
        ) {
            CompositionLocalProvider(LocalSettingsHeader provides true) {
                if (form) CompositionLocalProvider(LocalGroupedFormSpacing provides PlainFormSpacing) { content() } else content()
            }
        }
        }
    }
}

/**
 * The 2.0 group title of every settings page, iOS `settingsHeader()`:
 * footnote semibold with 0.6 tracking, in the secondary colour.
 */
val settingsHeaderStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)

/**
 * True on the pages of 设置 redesigned for 2.0 (and on 翻译与 AI wherever it
 * opens): [SettingsGroup] titles there use [settingsHeaderStyle]. The reader
 * and library panels that share [SettingsGroup] keep their own titles.
 */
val LocalSettingsHeader = staticCompositionLocalOf { false }

/**
 * How the groups of an iOS `Form` sit apart beyond the list's own spacing:
 * after a footer the next group starts [footerOverlap] sooner than after a
 * card, and a group without a title starts [untitledTop] lower.
 */
class GroupedFormSpacing(val footerOverlap: Dp, val untitledTop: Dp)

/** Provided by a page that lays its groups out like a plain `Form`; null elsewhere. */
val LocalGroupedFormSpacing = staticCompositionLocalOf<GroupedFormSpacing?> { null }

/** Measured on the iOS settings pages: 37.7 points from a card to the next title's ink, 32.5 from a footer's. */
val PlainFormSpacing = GroupedFormSpacing(footerOverlap = 6.33.dp, untitledTop = 3.dp)
val PlainFormGroupSpacing = 32.33.dp

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
    headerDetail: String? = null,
    content: @Composable () -> Unit
) {
    val formSpacing = if (compact) null else LocalGroupedFormSpacing.current
    val header2 = !compact && LocalSettingsHeader.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (title.isNotEmpty() && header2) Row(
            // 2.0 `settingsHeader()`; an accented title (`SettingsSectionHeader`)
            // leads with its 15-point icon in the same grey. A [headerDetail]
            // sits at the far end in `.caption`, as the backup list's count does.
            modifier = Modifier
                .then(if (headerDetail != null) Modifier.fillMaxWidth().padding(end = 20.dp) else Modifier)
                .padding(start = 20.dp, bottom = 5.5.dp)
                .semantics { heading() },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (accentedHeader) JerreaderGlyph(icon, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(15.dp))
            Text(title, style = settingsHeaderStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (headerDetail != null) {
                Spacer(Modifier.weight(1f))
                Text(headerDetail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (title.isNotEmpty()) Row(
            // iOS sets an accented title 8 points above its card, a plain one 9.
            modifier = Modifier.padding(
                start = if (compact) 5.dp else 20.dp,
                bottom = if (compact) 4.dp else if (accentedHeader) 3.7.dp else 5.5.dp
            ),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (compact || accentedHeader) JerreaderGlyph(icon,
                if (accentedHeader) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                Modifier.size(if (compact) 12.dp else 17.dp))
            Text(title, style = if (accentedHeader) MaterialTheme.typography.titleSmall else if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                color = if (accentedHeader) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (formSpacing != null) Spacer(Modifier.height(formSpacing.untitledTop))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(if (compact) 15.dp else 10.dp),
            border = if (compact) BorderStroke(0.7.dp, MaterialTheme.colorScheme.outline) else null,
            color = if (compact) MaterialTheme.colorScheme.surface else if (MaterialTheme.colorScheme.onSurface == Color.Black) Color.White else Color(0xFF1C1C1E)
        ) {
            Column { content() }
        }
        footer?.let {
            SettingsGroupFooter(it, if (formSpacing != null) Modifier.trimHeight(bottom = formSpacing.footerOverlap) else Modifier)
        }
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
            // The row keeps a 13-point margin; the chevron still ends where a
            // NavigationLink's does, 0.7 inside the usual 20.
            JerreaderNavigationChevron(Modifier.padding(end = 7.dp))
        }
        // iOS insets the separator so it starts past the leading icon.
        if (showDivider) {
            JerreaderRowSeparator(start = 45.dp)
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
    verticalPadding: Dp = 6.dp,
    dividerStart: Dp = 20.dp,
    content: @Composable RowScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .heightIn(min = 44.dp)
                .padding(horizontal = 20.dp, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
        if (showDivider) JerreaderRowSeparator(start = dividerStart)
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
    // The switch's 44-point touch box is the whole row, as an iOS Form row
    // with a 31-point switch is 44 points; only a title that wraps gets the
    // 6-point insets.
    SettingsRowFrame(modifier = modifier, showDivider = showDivider, verticalPadding = 0.dp) {
        Text(
            title,
            modifier = Modifier.weight(1f).padding(end = 12.dp).padding(vertical = 6.dp),
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
                JerreaderUpDownChevron(
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.2.dp).size(13.dp)
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
        if (showDivider) JerreaderRowSeparator(start = 20.dp)
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
        // The picker's 44-point box is the row: iOS insets its 32-point
        // control by 6 points above and below.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                // `.disabled` dims an iOS segmented control; the switches here
                // use the same 0.4.
                .alpha(if (enabled) 1f else 0.4f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            JerreaderSegmentedPicker(
                options = options,
                selected = selected,
                onSelect = { if (enabled) onSelect(it) }
            )
        }
        if (showDivider) JerreaderRowSeparator(start = 20.dp)
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
                start = 20.dp,
                end = 20.dp,
                top = 8.dp,
                bottom = 1.5.dp
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
            // iOS stacks its slider 6 points under the label; the top of the
            // Material slider's 48-point touch box would sit the track 3.5
            // points lower than that.
            Box(Modifier.trimHeight(top = 3.5.dp)) {
                SettingsSlider(value, range, steps, onValueChange, accessibilityLabel = title)
            }
        }
        if (showDivider) JerreaderRowSeparator(start = 20.dp)
    }
}

/**
 * A `Slider` on a row of its own, as iOS puts one under the `LabeledContent`
 * row that shows its value, with optional glyphs at either end (the
 * `textformat.size.smaller` / `.larger` pair beside the font size).
 */
@Composable
fun SettingsBareSliderRow(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    accessibilityLabel: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    showDivider: Boolean = true
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            leading?.invoke()
            // The 48-point touch box overhangs the 44-point row by 2 points
            // at either edge, so the row keeps the iOS height.
            Box(Modifier.weight(1f).trimHeight(top = 2.dp, bottom = 2.dp)) {
                SettingsSlider(value, range, steps, onValueChange, accessibilityLabel)
            }
            trailing?.invoke()
        }
        if (showDivider) JerreaderRowSeparator(start = 20.dp)
    }
}

/** The settings slider: a white 28-point thumb on a 4-point accent track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    accessibilityLabel: String
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        steps = steps,
        modifier = Modifier.semantics { contentDescription = accessibilityLabel },
        thumb = {
            Box(Modifier.size(28.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White))
        },
        track = {
            val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
            val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = .12f)
            val active = MaterialTheme.colorScheme.primary
            // UISlider runs its track out to the control's edges, under the
            // thumb at either end. The Material track stops at the thumb's
            // centre, so it is drawn a thumb radius wider on each side.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .widenBy(SliderThumbRadius)
                    .drawBehind {
                        val corner = CornerRadius(size.height / 2)
                        val inset = SliderThumbRadius.toPx()
                        drawRoundRect(inactive, cornerRadius = corner)
                        drawRoundRect(
                            active,
                            size = Size(inset + fraction * (size.width - 2 * inset), size.height),
                            cornerRadius = corner
                        )
                    }
            )
        }
    )
}

/**
 * The grey caption iOS drops between rows to explain the one above: `.caption`
 * (12 points on a 16-point line) centred in a Form row, 44 points for one
 * line and 6-point insets once it wraps.
 */
@Composable
fun SettingsFootnote(
    text: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            // Every line exactly 16 tall, as SwiftUI sets Chinese, and the
            // label colour at half strength that `.secondary` gives plain text.
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
                ),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
        if (showDivider) JerreaderRowSeparator(start = 20.dp)
    }
}

/** A caption in the accent's warning tone, for an invalid combination. */
@Composable
fun SettingsWarningNote(
    text: String,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true
) {
    // iOS draws this note in `.orange`: systemOrange, lighter in dark mode.
    val warning = if (MaterialTheme.colorScheme.onSurface != Color.Black) Color(0xFFFF9F0A) else Color(0xFFFF9500)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 2.0: a 15-point triangle.
            JerreaderGlyph(
                icon = JerreaderIcon.WARNING,
                tint = warning,
                modifier = Modifier.padding(top = 1.5.dp).size(15.dp)
            )
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = warning
            )
        }
        if (showDivider) JerreaderRowSeparator(start = 20.dp)
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
            JerreaderDisclosureChevron(expanded)
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content
            )
        }
        if (showDivider) JerreaderRowSeparator(start = 20.dp)
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
        onClick = if (enabled) onClick else null,
        // A `Label` row on iOS: the icon centred 34 points in, the title and
        // the separator under it starting at 64, as in [SettingsLabelRow].
        dividerStart = if (icon != null) 64.dp else 20.dp
    ) {
        icon?.let {
            JerreaderGlyph(it, MaterialTheme.colorScheme.primary, Modifier.padding(start = 4.dp, end = 20.dp).size(20.dp))
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
            JerreaderDisclosureChevron(disclosureExpanded)
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
        if (showDivider) JerreaderRowSeparator(start = 66.dp)
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
        if (showDivider) JerreaderRowSeparator(start = 20.dp)
    }
}

/** `Label(…, systemImage:)` in a List: a tinted glyph beside a line of text. */
@Composable
fun SettingsLabelRow(
    text: String,
    icon: JerreaderIcon,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    secondary: Boolean = false
) {
    val tint = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
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
                JerreaderGlyph(icon, tint, Modifier.size(18.dp))
            }
            Text(
                text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = if (secondary) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
        }
        if (showDivider) JerreaderRowSeparator(start = 64.dp)
    }
}

/** Explanatory paragraph under a group, matching an iOS Section footer. */
@Composable
fun SettingsGroupFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}


@Composable
fun SettingsTextFieldRow(title: String, value: String, onChange: (String) -> Unit, minLines: Int = 1, maxLines: Int = 1) {
    val multiline = maxLines > 1
    // `TextField(axis: .vertical)` on iOS: body lines 20.3 points apart, the
    // first as far below the row's top as a one-line field's text.
    val style = if (multiline) {
        MaterialTheme.typography.bodyLarge.copy(
            lineHeight = 20.3.sp,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
        )
    } else {
        MaterialTheme.typography.bodyLarge
    }
    SettingsInsetRow {
        androidx.compose.foundation.text.BasicTextField(value, onChange,
            modifier = Modifier.fillMaxWidth()
                .then(if (multiline) Modifier.padding(vertical = 1.85.dp) else Modifier.heightIn(min = 24.dp))
                .semantics { contentDescription = title },
            textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = !multiline, maxLines = maxLines, minLines = minLines, decorationBox = { input ->
                Box(contentAlignment = if (multiline) Alignment.TopStart else Alignment.CenterStart) {
                    if (value.isEmpty()) Text(title, style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    input()
                }
            })
    }
}


/** Half the settings slider's 28-point thumb. */
private val SliderThumbRadius = 14.dp

/**
 * Lays the child out [top] and [bottom] shorter than it measures, letting it
 * overhang its neighbours there: the Material slider's 48-point touch box is
 * taller than the iOS rows it sits in.
 */
private fun Modifier.trimHeight(top: Dp = 0.dp, bottom: Dp = 0.dp) = layout { measurable, constraints ->
    val trimmed = top.roundToPx() + bottom.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minHeight = 0,
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + trimmed else Constraints.Infinity
        )
    )
    val height = (placeable.height - trimmed).coerceAtLeast(constraints.minHeight)
    layout(placeable.width, height) { placeable.place(0, -top.roundToPx()) }
}

/** Measures the child [each] wider on both sides and centres it over this box. */
private fun Modifier.widenBy(each: Dp) = layout { measurable, constraints ->
    val extra = each.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + extra,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else Constraints.Infinity
        )
    )
    layout((placeable.width - extra).coerceAtLeast(0), placeable.height) { placeable.place(-extra / 2, 0) }
}

/**
 * The grabber iOS draws over the top of a sheet's navigation bar: 36 × 5, five
 * points down. It does not push the bar down, as Material's drag handle does.
 */
@Composable
fun BoxScope.PageSheetGrabber() {
    Box(
        Modifier.align(Alignment.TopCenter).padding(top = 5.dp).size(width = 36.dp, height = 5.dp)
            .background(LocalJerreaderColors.current.secondaryText.copy(alpha = .31f), RoundedCornerShape(2.5.dp))
    )
}

/** Top corners of an iOS page sheet: a 10-point radius, measured on the simulator. */
val PageSheetShape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)

/**
 * Where an iOS page sheet's top edge sits: 10 points below the status bar.
 * Read it on the presenting screen; the sheet's own window may not report the bar.
 */
@Composable
fun pageSheetTop(): Dp {
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.union(WindowInsets.displayCutout).getTop(density)
    return with(density) { top.toDp() } + 10.dp
}

/**
 * Sizes a ModalBottomSheet's content so the sheet's top edge lands at [top].
 * The sheet's window starts at the top of the screen, so the content takes
 * what is left of its maximum height (`fillMaxHeight(.95f)` left the edge at 5% of
 * the screen, half over the title of the page underneath).
 */
fun Modifier.pageSheetHeight(top: Dp): Modifier = layout { measurable, constraints ->
    val sized = if (constraints.hasBoundedHeight) {
        val height = (constraints.maxHeight - top.roundToPx()).coerceIn(constraints.minHeight, constraints.maxHeight)
        constraints.copy(minHeight = height, maxHeight = height)
    } else constraints
    val placeable = measurable.measure(sized)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}
