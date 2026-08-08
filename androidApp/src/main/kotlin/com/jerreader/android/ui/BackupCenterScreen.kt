package com.jerreader.android.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jerreader.android.AppGraph
import com.jerreader.android.backup.RestoreResult
import com.jerreader.shared.library.LibraryBackupArchiveInfo
import com.jerreader.shared.library.LibraryBackupPolicy
import com.jerreader.shared.library.LibraryBackupScope
import com.jerreader.shared.ui.JerreaderCard
import com.jerreader.shared.ui.JerreaderDivider
import com.jerreader.shared.ui.JerreaderFieldLabel
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderPrimaryButton
import com.jerreader.shared.ui.JerreaderRow
import com.jerreader.shared.ui.JerreaderSection
import com.jerreader.shared.ui.JerreaderSegmented
import com.jerreader.shared.ui.SettingsPage
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * Android counterpart of the iOS backup centre. The user picks a folder through
 * the Storage Access Framework — Google Drive, OneDrive, an SD card or on-device
 * storage all appear there — and archives are written into it. Without this a
 * reinstall loses the whole library, because the app deliberately opts out of
 * Android's own cloud backup for the sandbox.
 */
@Composable
fun BackupCenterScreen(
    graph: AppGraph,
    onBack: () -> Unit,
    /**
     * Set by the empty-library entry point so a fresh installation lands on the
     * archive picker instead of having to find the restore section.
     */
    startsWithArchivePicker: Boolean = false
) {
    val folder by graph.backupDirectory.folder.collectAsStateWithLifecycle()
    val policy by graph.backupPolicy.policy.collectAsStateWithLifecycle()
    var manualScopes by remember { mutableStateOf(LibraryBackupScope.entries.toSet()) }
    var backups by remember { mutableStateOf(emptyList<LibraryBackupArchiveInfo>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pendingDeletion by remember { mutableStateOf<LibraryBackupArchiveInfo?>(null) }
    var pendingRestore by remember { mutableStateOf<LibraryBackupArchiveInfo?>(null) }
    var suggestedFolder by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        backups = runCatching { graph.backupService.listBackups() }.getOrDefault(emptyList())
    }

    LaunchedEffect(folder?.treeUri) { reload() }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            error = null
            runCatching { graph.backupDirectory.select(uri) }
                .onSuccess {
                    message = "备份位置已更改：${it.displayName}"
                    suggestedFolder = null
                }
                .onFailure { error = "无法使用这个文件夹，请换一个位置。" }
            reload()
        }
    }

    // Restoring from a file outside the current folder — a backup copied from
    // another device, say — goes through a fresh picker each time, the way the
    // iOS restore flow opens a new document session.
    val archivePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            error = null
            message = null
            restoreArchive(
                onBusy = { busy = it },
                onResult = { text, failed -> if (failed) error = text else message = text },
                onRestored = { result ->
                    suggestedFolder = result.suggestedFolderUri?.let { folderUri ->
                        folderUri to (result.suggestedFolderName ?: "以前的备份文件夹")
                    }
                }
            ) { graph.backupService.restore(uri) }
            reload()
        }
    }

    LaunchedEffect(startsWithArchivePicker) {
        if (!startsWithArchivePicker) return@LaunchedEffect
        archivePicker.launch(
            arrayOf("application/zip", "application/octet-stream", "*/*")
        )
    }

    SettingsPage(title = "备份与恢复", onBack = onBack) {
        JerreaderSection(
            title = "备份位置",
            footnote = "目录授权、API Key、代理凭据和可再生的翻译缓存都不会写入备份。"
        ) {
            Text(
                folder?.displayName ?: "尚未选择文件夹",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "可以选择本机存储，也可以选择已装好的云盘目录；书籍与记录只写入你选的这个位置。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            suggestedFolder?.let { (treeUri, name) ->
                Text(
                    "刚恢复的备份来自「$name」。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { folderPicker.launch(treeUri) }) {
                    Text("继续备份到这个文件夹")
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = { folderPicker.launch(null) }) {
                    Text(if (folder == null) "选择备份文件夹" else "修改文件夹")
                }
                if (folder != null) {
                    TextButton(onClick = {
                        graph.backupDirectory.clear()
                        backups = emptyList()
                        message = "已停止使用该文件夹。"
                    }) { Text("停止使用") }
                }
            }
        }

        JerreaderSection(
            title = "手动备份",
            footnote = "包含书籍文件会让备份明显变大，但重装后无需重新找书。"
        ) {
            LibraryBackupScope.entries.forEach { scopeOption ->
                JerreaderRow(label = scopeOption.title, detail = scopeOption.detail) {
                    Switch(
                        checked = scopeOption in manualScopes,
                        onCheckedChange = { checked ->
                            manualScopes = if (checked) {
                                manualScopes + scopeOption
                            } else {
                                manualScopes - scopeOption
                            }
                        }
                    )
                }
                if (scopeOption != LibraryBackupScope.entries.last()) JerreaderDivider()
            }
            JerreaderPrimaryButton(
                text = if (busy) "处理中…" else "立即备份到当前文件夹",
                icon = JerreaderIcon.SHIELD,
                enabled = folder != null && manualScopes.isNotEmpty() && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        message = null
                        runCatching { graph.backupService.backup(manualScopes) }
                            .onSuccess { result ->
                                message = buildString {
                                    append("备份完成：${result.archiveName}（${formatBytes(result.sizeBytes)}）。")
                                    if (result.removedCount > 0) {
                                        append("同时清理了 ${result.removedCount} 份旧备份。")
                                    }
                                    if (result.isOverLimit) {
                                        append("最新一份自身已超过容量上限，因此仍被保留。")
                                    }
                                }
                            }
                            .onFailure { error = it.message ?: "备份没有完成，请稍后重试。" }
                        reload()
                        busy = false
                    }
                }
            )
        }

        JerreaderSection(
            title = "自动备份",
            footnote = "自动备份在打开书架时检查是否到期，只写入当前文件夹。"
        ) {
            JerreaderRow(label = "定期自动备份") {
                Switch(
                    checked = policy.automaticEnabled,
                    onCheckedChange = { enabled ->
                        graph.backupPolicy.update { it.copy(automaticEnabled = enabled) }
                    }
                )
            }
            JerreaderFieldLabel("备份间隔")
            JerreaderSegmented(
                options = LibraryBackupPolicy.INTERVAL_CHOICES.map { it to "$it 天" },
                selected = policy.intervalDays,
                onSelect = { days -> graph.backupPolicy.update { it.copy(intervalDays = days) } }
            )
            JerreaderFieldLabel("自动备份范围", modifier = Modifier.padding(top = 10.dp))
            LibraryBackupScope.entries.forEach { scopeOption ->
                JerreaderRow(label = scopeOption.title) {
                    Switch(
                        checked = scopeOption in policy.automaticScopes,
                        onCheckedChange = { checked ->
                            graph.backupPolicy.update { current ->
                                current.copy(
                                    automaticScopes = if (checked) {
                                        current.automaticScopes + scopeOption
                                    } else {
                                        current.automaticScopes - scopeOption
                                    }
                                )
                            }
                        }
                    )
                }
            }
        }

        JerreaderSection(
            title = "保留策略",
            footnote = "超出限制时从最旧的一份开始删除，但永远保留最新的一份。"
        ) {
            JerreaderFieldLabel("保存时间")
            JerreaderSegmented(
                options = LibraryBackupPolicy.RETENTION_CHOICES.map {
                    it to if (it == 0) "不按时间删除" else "保留 $it 天"
                },
                selected = policy.retentionDays,
                onSelect = { days -> graph.backupPolicy.update { it.copy(retentionDays = days) } }
            )
            JerreaderFieldLabel("最多份数", modifier = Modifier.padding(top = 10.dp))
            JerreaderSegmented(
                options = LibraryBackupPolicy.COUNT_CHOICES.map { it to "$it 份" },
                selected = policy.maximumBackupCount,
                onSelect = { count ->
                    graph.backupPolicy.update { it.copy(maximumBackupCount = count) }
                }
            )
            JerreaderFieldLabel("总容量上限", modifier = Modifier.padding(top = 10.dp))
            JerreaderSegmented(
                options = LibraryBackupPolicy.SIZE_CHOICES.map {
                    it to if (it == 0L) "不限容量" else formatBytes(it)
                },
                selected = policy.maximumTotalBytes,
                onSelect = { bytes ->
                    graph.backupPolicy.update { it.copy(maximumTotalBytes = bytes) }
                }
            )
        }

        JerreaderSection(title = "当前文件夹中的备份") {
            if (folder == null) {
                Text("先选择一个备份文件夹。", style = MaterialTheme.typography.bodyMedium)
            } else if (backups.isEmpty()) {
                Text("当前文件夹还没有备份。", style = MaterialTheme.typography.bodyMedium)
            }
            backups.forEach { info ->
                JerreaderRow(
                    label = info.name,
                    detail = "${formatTimestamp(info.createdAtEpochMillis)} · ${formatBytes(info.sizeBytes)}"
                ) {
                    Row {
                        TextButton(enabled = !busy, onClick = { pendingRestore = info }) {
                            Text("恢复")
                        }
                        TextButton(enabled = !busy, onClick = { pendingDeletion = info }) {
                            Text("删除")
                        }
                    }
                }
                if (info != backups.last()) JerreaderDivider()
            }
        }

        JerreaderSection(
            title = "从备份文件恢复",
            footnote = "恢复只做合并：备份中已存在的书籍与记录不会产生重复。"
        ) {
            TextButton(enabled = !busy, onClick = {
                archivePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            }) { Text("选择备份文件") }
        }

        message?.let {
            JerreaderCard { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        error?.let {
            JerreaderCard {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    pendingDeletion?.let { info ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("删除这份备份？") },
            text = { Text("只删除这一个备份文件，不会删除书架或阅读数据。") },
            confirmButton = {
                TextButton(onClick = {
                    val target = info
                    pendingDeletion = null
                    scope.launch {
                        busy = true
                        runCatching { graph.backupService.deleteBackup(target.name) }
                            .onSuccess { message = "已删除 ${target.name}。" }
                            .onFailure { error = it.message ?: "删除没有完成，请稍后重试。" }
                        backups = runCatching { graph.backupService.listBackups() }
                            .getOrDefault(emptyList())
                        busy = false
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) { Text("取消") }
            }
        )
    }

    pendingRestore?.let { info ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("从这份备份恢复？") },
            text = {
                Text("会把备份中的书籍与记录合并到本机，已有的内容保持不变。API Key 未包含在备份中，需要重新填写。")
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = info
                    pendingRestore = null
                    scope.launch {
                        error = null
                        message = null
                        restoreArchive(
                            onBusy = { busy = it },
                            onResult = { text, failed ->
                                if (failed) error = text else message = text
                            }
                        ) { graph.backupService.restore(target.name) }
                    }
                }) { Text("开始恢复") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("取消") }
            }
        )
    }
}

private suspend fun restoreArchive(
    onBusy: (Boolean) -> Unit,
    onResult: (String, Boolean) -> Unit,
    onRestored: (RestoreResult) -> Unit = {},
    perform: suspend () -> RestoreResult
) {
    onBusy(true)
    runCatching { perform() }
        .onSuccess { result ->
            onRestored(result)
            onResult(
                if (result.isEmpty && result.skippedExisting > 0) {
                    "备份中的数据已存在，没有产生重复记录。"
                } else {
                    buildString {
                        append("恢复完成：")
                        append(
                            listOfNotNull(
                                result.books.takeIf { it > 0 }?.let { "$it 本书" },
                                result.bookmarks.takeIf { it > 0 }?.let { "$it 个书签" },
                                result.annotations.takeIf { it > 0 }?.let { "$it 条批注" },
                                result.words.takeIf { it > 0 }?.let { "$it 条生词/历史" },
                                result.translationFavorites.takeIf { it > 0 }
                                    ?.let { "$it 条翻译收藏" },
                                "阅读与翻译偏好".takeIf { result.restoredSettings }
                            ).ifEmpty { listOf("没有新增内容") }.joinToString("、")
                        )
                        append("。")
                        if (result.skippedExisting > 0) {
                            append("已存在的 ${result.skippedExisting} 条记录保持不变。")
                        }
                        if (result.inheritedProfile != null) {
                            append("备份计划（频率、保留、范围）已按这份备份继续。")
                        }
                        result.adoptedFolderName?.let {
                            append("备份文件夹已继续使用「$it」。")
                        }
                        result.suggestedFolderName?.let {
                            append("这份备份原本存放在「$it」，可在上方“备份位置”里一键继续用它。")
                        }
                    }
                },
                false
            )
        }
        .onFailure { onResult(it.message ?: "恢复没有完成，请稍后重试。", true) }
    onBusy(false)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)} GB"
    bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MB"
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

private fun formatTimestamp(epochMillis: Long): String {
    val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    return format.format(java.util.Date(epochMillis))
}
