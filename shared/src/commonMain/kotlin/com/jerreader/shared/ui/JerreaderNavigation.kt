package com.jerreader.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Shared app navigation geometry. System status/navigation insets stay platform owned.
 * [trailing] holds an optional toolbar control (a text button or icon) pinned to the end.
 */
@Composable
fun JerreaderNavigationBar(
    title: String,
    backTitle: String,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    val barFontSize = with(androidx.compose.ui.platform.LocalDensity.current) { 17.dp.toSp() }
    Column(Modifier.fillMaxWidth().statusBarsPadding()) {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
            val backWidth = maxWidth * .35f
            Text(title, fontSize = barFontSize, lineHeight = with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toSp() }, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).widthIn(max = maxWidth * .49f))
            Row(Modifier.align(Alignment.CenterStart).widthIn(max = backWidth)
                .clickable(role = Role.Button, onClick = onBack).heightIn(min = 44.dp)
                .padding(start = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                JerreaderGlyph(JerreaderIcon.CHEVRON_LEFT, MaterialTheme.colorScheme.primary, Modifier.size(22.dp))
                Text(backTitle, fontSize = barFontSize, lineHeight = with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toSp() }, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) {
                Box(Modifier.align(Alignment.CenterEnd).widthIn(max = maxWidth * .25f).heightIn(min = 44.dp).padding(end = 8.dp),
                    contentAlignment = Alignment.CenterEnd) { trailing() }
            }
        }
    }
}

/**
 * The app's tab bar as iOS 2.0 draws it: filled with the canvas colour and
 * always visible, the iOS hairline along its top, the selected tab in the
 * accent colour. Like the iOS bar it has no press highlight; a change of tab
 * plays the selection tick (iOS `.sensoryFeedback(.selection)`).
 */
@Composable
fun <T> JerreaderTabBar(
    tabs: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    title: (T) -> String,
    icon: (T) -> JerreaderIcon,
    modifier: Modifier = Modifier
) {
    val colors = jerreaderPalette
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    // iOS tab titles are 10 pt medium and keep their size at large text settings.
    val captionSize = with(density) { 10.dp.toSp() }
    val captionLineHeight = with(density) { 12.dp.toSp() }
    val hairline = with(density) { 1f.toDp() }
    Column(modifier.fillMaxWidth().background(colors.canvas)) {
        // The default iOS bar shadow: black 30% in light mode, white 15% in dark mode.
        Box(
            Modifier.fillMaxWidth().height(hairline)
                .background(if (colors.isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.3f))
        )
        Row(Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup()) {
            tabs.forEach { tab ->
                val isSelected = tab == selected
                val tint = if (isSelected) colors.accent else MaterialTheme.colorScheme.onSurfaceVariant
                // The compact icon-and-caption layout of the iOS tabs; the
                // minimum height can grow with accessibility text sizes.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .selectable(selected = isSelected, interactionSource = null, indication = null, role = Role.Tab) {
                            if (!isSelected) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(tab)
                        }
                        .heightIn(min = 49.dp)
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
                ) {
                    JerreaderGlyph(icon = icon(tab), tint = tint, modifier = Modifier.size(24.dp))
                    Text(
                        text = title(tab),
                        color = tint,
                        fontSize = captionSize,
                        lineHeight = captionLineHeight,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
