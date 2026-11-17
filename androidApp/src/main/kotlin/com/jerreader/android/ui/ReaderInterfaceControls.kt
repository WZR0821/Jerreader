package com.jerreader.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jerreader.android.settings.AndroidAppSettingsStore
import com.jerreader.shared.library.ReaderInterfaceDefaults
import com.jerreader.shared.library.ReaderLayoutMode
import com.jerreader.shared.ui.SettingsFootnote
import com.jerreader.shared.ui.SettingsGroup
import com.jerreader.shared.ui.SettingsSegmentedRow
import com.jerreader.shared.ui.SettingsSliderRow
import com.jerreader.shared.ui.SettingsToggleRow
import kotlin.math.roundToInt

/**
 * 阅读与翻译 › 阅读界面, as the iOS `ReaderSettingsView.interfaceSection`:
 * the layout picker and what it does, the status-bar switch (the immersive
 * layout always hides the status bar, so the switch only shows in the
 * standard one), the two bar transparencies and 日语注音. These apply to
 * every book.
 */
@Composable
fun ReaderInterfaceSection(store: AndroidAppSettingsStore, modifier: Modifier = Modifier) {
    val state by store.preferences.collectAsStateWithLifecycle()
    SettingsGroup(
        title = "阅读界面",
        modifier = modifier,
        footer = "只调整控制栏背景，按钮保持清晰。日语注音仅显示在翻译卡片中；这些选项对所有书生效。"
    ) {
        SettingsSegmentedRow(
            title = "界面布局",
            options = ReaderLayoutMode.entries.map { it to it.title },
            selected = state.layoutMode,
            onSelect = store::updateLayoutMode
        )
        SettingsFootnote(state.layoutMode.detail)
        if (state.layoutMode == ReaderLayoutMode.STANDARD) {
            SettingsToggleRow("隐藏状态栏", state.immersiveReading, store::updateImmersiveReading)
        }
        TransparencyRow("顶部栏透明度", state.topBarTransparency, store::updateTopBarTransparency)
        TransparencyRow("底部栏透明度", state.bottomBarTransparency, store::updateBottomBarTransparency)
        SettingsToggleRow(
            "日语注音",
            state.japaneseFuriganaEnabled,
            store::updateJapaneseFurigana,
            showDivider = false
        )
    }
}

/** The slider reads as the share of the bar left see-through, in steps of 5%. */
@Composable
private fun TransparencyRow(title: String, value: Float, onChange: (Float) -> Unit) {
    val shown = 1f - ReaderInterfaceDefaults.backgroundOpacity(value)
    SettingsSliderRow(
        title = title,
        value = shown,
        valueLabel = "${(shown * 100).roundToInt()}%",
        range = 0f..1f,
        steps = 19,
        onValueChange = { onChange((it * 20).roundToInt() / 20f) }
    )
}
