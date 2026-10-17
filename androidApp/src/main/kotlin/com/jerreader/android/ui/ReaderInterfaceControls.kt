package com.jerreader.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jerreader.android.settings.AndroidAppSettingsStore

@Composable
fun ReaderInterfaceControls(store: AndroidAppSettingsStore) {
    val state by store.preferences.collectAsStateWithLifecycle()
    LearningCard {
        Text("阅读界面", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("沉浸阅读（隐藏状态栏）", Modifier.weight(1f))
            LearningSwitch(state.immersiveReading, "沉浸阅读（隐藏状态栏）", onCheckedChange = store::updateImmersiveReading)
        }
        TransparencyControl("顶部栏透明度", state.topBarTransparency, store::updateTopBarTransparency)
        TransparencyControl("底部栏透明度", state.bottomBarTransparency, store::updateBottomBarTransparency)
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("日语注音", Modifier.weight(1f))
            LearningSwitch(state.japaneseFuriganaEnabled, "日语注音", onCheckedChange = store::updateJapaneseFurigana)
        }
        Text("只调整控制栏背景，按钮保持清晰。日语注音仅显示在翻译卡片中；这些选项对所有书生效。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TransparencyControl(title: String, value: Float, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, Modifier.weight(1f)); Text("${(value * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value, onChange, steps = 19,
            modifier = Modifier.fillMaxWidth().height(31.dp).semantics { contentDescription = title },
            thumb = { Box(Modifier.size(28.dp).shadow(3.dp, CircleShape).background(Color.White, CircleShape)) },
            track = { state ->
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .15f))) {
                    Box(Modifier.fillMaxWidth(state.value.coerceIn(0f, 1f)).fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary))
                }
            })
    }
}
