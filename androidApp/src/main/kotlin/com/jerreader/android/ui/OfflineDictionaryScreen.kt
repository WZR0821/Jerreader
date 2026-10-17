package com.jerreader.android.ui

import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jerreader.android.lexical.OfflineDictionaryManifest
import com.jerreader.android.lexical.OfflineDictionaryPackageStore
import com.jerreader.android.lexical.JapaneseChineseLexicon
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun OfflineDictionaryScreen(store: OfflineDictionaryPackageStore, models: com.jerreader.android.translation.LocalTranslationModels, modifier: Modifier = Modifier) {
    val status by store.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val context = LocalContext.current
    DisposableEffect(store) { onDispose { job?.cancel() } }
    LearningPage(modifier) {
        item { Text("基础词典已内置，无需下载。句段翻译需先下载语言包。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { LocalTranslationModelsSection(models) }
        item { LearningCard {
            Text("英语扩展词典", style = MaterialTheme.typography.titleMedium)
            Text("下载 65.9 MB，安装需预留 300 MB，用于离线查词。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (status.installed) "已下载" else "未下载", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status.busy) { CircularProgressIndicator(Modifier.size(20.dp)); Text("正在下载并建立索引…", style = MaterialTheme.typography.bodyMedium) }
            status.message?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                LearningButton(enabled = status.busy || !status.installed, onClick = {
                    if (status.busy) job?.cancel() else job = scope.launch { store.install() }
                }) { Text(if (status.busy) "取消下载" else "下载") }
                LearningButton(enabled = !status.busy && status.installed, destructive = true,
                    onClick = { job = scope.launch { store.remove() } }) { Text("卸载") }
            }
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LearningButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, JapaneseChineseLexicon.LICENSE.toUri())) }) {
                Text("中日词典 · CC BY-SA 4.0", style = MaterialTheme.typography.labelMedium)
            }
            LearningButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, OfflineDictionaryManifest.LICENSE.toUri())) }) {
                Text("ECDICT · MIT", style = MaterialTheme.typography.labelMedium)
            }
        } }
    }
}
