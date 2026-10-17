package com.jerreader.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.JerreaderIcon

enum class AndroidAppTab(val title: String, internal val icon: JerreaderIcon) {
    LIBRARY("书架", JerreaderIcon.BOOKS),
    LEARNING("学习", JerreaderIcon.DICTIONARY),
    SETTINGS("设置", JerreaderIcon.GEAR)
}

@Composable
fun AppNavigationBar(selected: AndroidAppTab, onSelected: (AndroidAppTab) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().selectableGroup()) {
            AndroidAppTab.entries.forEach { tab ->
                val active = selected == tab
                val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                // Match the compact icon-and-caption layout of the native iOS tabs.
                // The minimum height can grow with accessibility text sizes.
                Column(
                    modifier = Modifier.weight(1f).selectable(
                        selected = active, role = Role.Tab, onClick = { onSelected(tab) }
                    ).heightIn(min = 49.dp).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
                ) {
                    JerreaderGlyph(
                        icon = tab.icon,
                        tint = tint,
                        modifier = Modifier.size(24.dp)
                    )
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    Text(tab.title, color = tint, fontSize = with(density) { 10.dp.toSp() }, lineHeight = with(density) { 12.dp.toSp() })
                }
            }
        }
    }
}
