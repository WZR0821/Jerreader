package com.jerreader.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.JerreaderIcon

enum class AndroidAppTab(val title: String, internal val icon: JerreaderIcon) {
    LIBRARY("书架", JerreaderIcon.BOOKS),
    LEARNING("学习", JerreaderIcon.DICTIONARY),
    SETTINGS("设置", JerreaderIcon.GEAR)
}

@Composable
fun AppNavigationBar(selected: AndroidAppTab, onSelected: (AndroidAppTab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        AndroidAppTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelected(tab) },
                // iOS puts a glyph above the label in the tab bar.
                icon = {
                    JerreaderGlyph(
                        icon = tab.icon,
                        tint = if (selected == tab) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(24.dp)
                    )
                },
                label = { Text(tab.title, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                )
            )
        }
    }
}
