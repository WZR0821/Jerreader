package com.jerreader.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
