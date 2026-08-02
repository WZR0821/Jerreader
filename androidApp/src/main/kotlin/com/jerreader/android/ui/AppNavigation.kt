package com.jerreader.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

enum class AndroidAppTab(val title: String) {
    LIBRARY("书架"),
    LEARNING("学习"),
    SETTINGS("设置")
}

@Composable
fun AppNavigationBar(selected: AndroidAppTab, onSelected: (AndroidAppTab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        AndroidAppTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelected(tab) },
                // A single label reads better than the previous character-as-icon
                // with the same word repeated underneath it.
                icon = { Text(tab.title, style = MaterialTheme.typography.labelLarge) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer
                )
            )
        }
    }
}
