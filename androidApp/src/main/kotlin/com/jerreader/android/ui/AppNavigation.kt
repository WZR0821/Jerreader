package com.jerreader.android.ui

import androidx.compose.runtime.Composable
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderTabBar

enum class AndroidAppTab(val title: String, internal val icon: JerreaderIcon) {
    LIBRARY("书架", JerreaderIcon.BOOKS),
    LEARNING("学习", JerreaderIcon.DICTIONARY),
    SETTINGS("设置", JerreaderIcon.GEAR)
}

/**
 * The 2.0 tab bar, drawn by the shared [JerreaderTabBar] like iOS: canvas
 * fill with a top hairline, accent for the selected tab, a selection tick.
 */
@Composable
fun AppNavigationBar(selected: AndroidAppTab, onSelected: (AndroidAppTab) -> Unit) {
    JerreaderTabBar(
        tabs = AndroidAppTab.entries,
        selected = selected,
        onSelect = onSelected,
        title = { it.title },
        icon = { it.icon }
    )
}
