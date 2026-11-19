package com.jerreader.android.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jerreader.android.lexical.JapaneseChineseLexicon
import com.jerreader.android.lexical.OfflineDictionaryManifest
import com.jerreader.android.lexical.OfflineDictionaryPackageStore
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderIconTile
import com.jerreader.shared.ui.JerreaderLearningChip
import com.jerreader.shared.ui.JerreaderLearningType
import com.jerreader.shared.ui.jerreaderPalette
import com.jerreader.shared.ui.plainSecondary
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * iOS `OfflineDictionaryView` (离线资源): the guidance, the sentence models,
 * then the English dictionary card (drive tile 40, headline, a jade 已下载 or
 * grey 未下载 chip; filled 下载 beside a destructive tinted 卸载) and the two
 * licence links.
 */
@Composable
fun OfflineDictionaryScreen(store: OfflineDictionaryPackageStore, models: com.jerreader.android.translation.LocalTranslationModels, modifier: Modifier = Modifier) {
    val status by store.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val context = LocalContext.current
    val palette = jerreaderPalette
    val secondary = MaterialTheme.colorScheme.plainSecondary
    DisposableEffect(store) { onDispose { job?.cancel() } }
    LearningPage(modifier) {
        item { LearningHint("基础词典已内置，无需下载。句段翻译需先下载语言包。") }
        item { LocalTranslationModelsSection(models) }
        item { LearningCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                JerreaderIconTile(JerreaderIcon.EXTERNAL_DRIVE, size = 40.dp)
                Text("英语扩展词典", Modifier.weight(1f), style = JerreaderLearningType.headline)
                JerreaderLearningChip(
                    text = if (status.installed) "已下载" else "未下载",
                    tint = if (status.installed) palette.jade else palette.secondaryText
                )
            }
            LearningHint("下载 65.9 MB，安装需预留 300 MB，用于离线查词。")
            if (status.busy) {
                // iOS's ProgressView with a label: the spinner over the line.
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = palette.accent, strokeWidth = 2.dp)
                    Text("正在下载并建立索引…", style = JerreaderLearningType.subheadline, color = secondary)
                }
            }
            status.message?.let { Text(it, style = JerreaderLearningType.caption, color = secondary) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LearningButton(enabled = status.busy || !status.installed, kind = LearningButtonKind.FILLED, onClick = {
                    if (status.busy) job?.cancel() else job = scope.launch { store.install() }
                }) { Text(if (status.busy) "取消下载" else "下载") }
                LearningButton(enabled = !status.busy && status.installed, kind = LearningButtonKind.TINTED, destructive = true,
                    onClick = { job = scope.launch { store.remove() } }) { Text("卸载") }
            }
        } }
        // iOS asks for caption but its links still come out at body size.
        item { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LearningButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, JapaneseChineseLexicon.LICENSE.toUri())) }) {
                Text("中日词典 · CC BY-SA 4.0", style = JerreaderLearningType.body)
            }
            LearningButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, OfflineDictionaryManifest.LICENSE.toUri())) }) {
                Text("ECDICT · MIT", style = JerreaderLearningType.body)
            }
        } }
    }
}
