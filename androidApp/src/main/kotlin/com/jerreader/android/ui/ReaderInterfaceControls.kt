package com.jerreader.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jerreader.android.reader.ReaderSheetCard
import com.jerreader.android.reader.ReaderSheetDivider
import com.jerreader.android.reader.ReaderSheetPillRow
import com.jerreader.android.reader.ReaderSheetRows
import com.jerreader.android.reader.ReaderSheetSliderRow
import com.jerreader.android.reader.ReaderSheetToggleRow
import com.jerreader.android.settings.AndroidAppSettingsStore
import com.jerreader.shared.library.ReaderInterfaceDefaults
import com.jerreader.shared.library.ReaderLayoutMode
import com.jerreader.shared.ui.JerreaderPillOption
import kotlin.math.roundToInt

/**
 * 阅读与翻译 › 阅读界面, as the iOS 2.0 `ReaderSettingsView.interfaceSection`:
 * a card with the 界面布局 pills (标准 and 沉浸, tagged `readerLayout-<mode>`
 * like the iOS identifiers) over what the layout does, the status-bar switch
 * (the immersive layout always hides the status bar, so the switch shows in
 * the standard one only, coming and going with the card's spring), the two
 * bar transparencies and 日语注音. These apply to every book.
 */
@Composable
fun ReaderInterfaceSection(store: AndroidAppSettingsStore, modifier: Modifier = Modifier) {
    val state by store.preferences.collectAsStateWithLifecycle()
    ReaderSheetCard(
        title = "阅读界面",
        modifier = modifier,
        footer = "只调整控制栏背景，按钮保持清晰。日语注音仅显示在翻译卡片中；这些选项对所有书生效。"
    ) {
        ReaderSheetPillRow(
            title = "界面布局",
            options = ReaderLayoutMode.entries.map { mode ->
                JerreaderPillOption(mode, mode.title, testTag = "readerLayout-${mode.storageValue}")
            },
            selected = state.layoutMode,
            onSelect = store::updateLayoutMode,
            detail = state.layoutMode.detail
        )
        ReaderSheetRows(visible = state.layoutMode == ReaderLayoutMode.STANDARD) {
            ReaderSheetDivider()
            ReaderSheetToggleRow("隐藏状态栏", state.immersiveReading, store::updateImmersiveReading)
        }
        ReaderSheetDivider()
        TransparencyRow("顶部栏透明度", state.topBarTransparency, store::updateTopBarTransparency)
        ReaderSheetDivider()
        TransparencyRow("底部栏透明度", state.bottomBarTransparency, store::updateBottomBarTransparency)
        ReaderSheetDivider()
        ReaderSheetToggleRow("日语注音", state.japaneseFuriganaEnabled, store::updateJapaneseFurigana)
    }
}

/** The slider reads as the share of the bar left see-through, in steps of 5%. */
@Composable
private fun TransparencyRow(title: String, value: Float, onChange: (Float) -> Unit) {
    val shown = 1f - ReaderInterfaceDefaults.backgroundOpacity(value)
    ReaderSheetSliderRow(
        title = title,
        valueText = "${(shown * 100).roundToInt()}%",
        value = shown,
        range = 0f..1f,
        steps = 19,
        onValueChange = { onChange((it * 20).roundToInt() / 20f) }
    )
}
