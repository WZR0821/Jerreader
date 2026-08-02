package com.jerreader.android.ui

import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

enum class AndroidAppTab(val title: String, val mark: String) {
    LIBRARY("书架", "书"),
    LEARNING("学习", "学"),
    SETTINGS("设置", "设")
}

@Composable
fun AppNavigationBar(selected: AndroidAppTab, onSelected: (AndroidAppTab) -> Unit) {
    NavigationBar {
        AndroidAppTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selected == tab,
                onClick = { onSelected(tab) },
                icon = { Text(tab.mark) },
                label = { Text(tab.title) }
            )
        }
    }
}
