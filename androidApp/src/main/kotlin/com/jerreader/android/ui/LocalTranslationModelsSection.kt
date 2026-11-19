package com.jerreader.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jerreader.android.translation.LocalTranslationModels
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderIconTile
import com.jerreader.shared.ui.JerreaderLearningChip
import com.jerreader.shared.ui.JerreaderLearningType
import com.jerreader.shared.ui.jerreaderPalette
import com.jerreader.shared.ui.plainSecondary
import kotlinx.coroutines.launch

/**
 * The Chinese–Japanese sentence models (Android only: iOS leaves its language
 * packs to the system). Laid out like the 英语扩展词典 card under it — tile,
 * headline and status chip, the hint, filled 下载 beside a tinted 卸载 — so the
 * 离线资源 page reads as one list of resources.
 */
@Composable
fun LocalTranslationModelsSection(models: LocalTranslationModels) {
    val state by models.modelState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val palette = jerreaderPalette
    val secondary = MaterialTheme.colorScheme.plainSecondary
    val required = listOf(LanguageCode.CHINESE_SIMPLIFIED, LanguageCode.JAPANESE)
    val ready = required.all { it in state.installed }
    val working = state.workingLanguage != null || state.loading
    LaunchedEffect(models) { models.refreshModels() }
    LearningCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JerreaderIconTile(JerreaderIcon.TRANSLATION_BUBBLE_FILLED, size = 40.dp)
            Text("中日句段翻译", Modifier.weight(1f), style = JerreaderLearningType.headline)
            JerreaderLearningChip(
                text = when { working -> "正在处理…"; ready -> "已下载"; else -> "未下载" },
                tint = when { working -> palette.accent; ready -> palette.jade; else -> palette.secondaryText }
            )
        }
        LearningHint("首次通过 Wi-Fi 下载，之后可离线翻译。")
        state.message?.let { Text(it, style = JerreaderLearningType.caption, color = secondary) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LearningButton(enabled = !working && !ready, kind = LearningButtonKind.FILLED, onClick = { scope.launch {
                for (language in required) {
                    if (language !in models.modelState.value.installed) {
                        models.downloadModel(language, wifiOnly = true)
                        if (language !in models.modelState.value.installed) break
                    }
                }
            } }) { Text("下载") }
            LearningButton(enabled = !working && required.any { it in state.installed },
                kind = LearningButtonKind.TINTED, destructive = true,
                onClick = { scope.launch {
                    required.forEach { if (it in models.modelState.value.installed) models.deleteModel(it) }
                } }) { Text("卸载") }
        }
    }
}
