package com.jerreader.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jerreader.android.translation.LocalTranslationModels
import com.jerreader.shared.domain.LanguageCode
import kotlinx.coroutines.launch

@Composable
fun LocalTranslationModelsSection(models: LocalTranslationModels) {
    val state by models.modelState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val required = listOf(LanguageCode.CHINESE_SIMPLIFIED, LanguageCode.JAPANESE)
    val ready = required.all { it in state.installed }
    val working = state.workingLanguage != null || state.loading
    LaunchedEffect(models) { models.refreshModels() }
    LearningCard {
        Text("中日句段翻译", style = MaterialTheme.typography.titleMedium)
        Text("首次通过 Wi-Fi 下载，之后可离线翻译。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(when { working -> "正在处理…"; ready -> "已下载"; else -> "未下载" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.message?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            LearningButton(enabled = !working && !ready, onClick = { scope.launch {
                for (language in required) {
                    if (language !in models.modelState.value.installed) {
                        models.downloadModel(language, wifiOnly = true)
                        if (language !in models.modelState.value.installed) break
                    }
                }
            } }) { Text("下载") }
            LearningButton(enabled = !working && required.any { it in state.installed }, destructive = true,
                onClick = { scope.launch {
                    required.forEach { if (it in models.modelState.value.installed) models.deleteModel(it) }
                } }) { Text("卸载") }
        }
    }
}
