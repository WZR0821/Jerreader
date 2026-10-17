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
import com.jerreader.shared.ui.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    bottomBar: @Composable () -> Unit = {},
    /**
     * Set by the empty-library entry point so a fresh installation lands on the
     * folder picker instead of having to find the restore section.
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
    var restoringFolder by remember { mutableStateOf<Uri?>(null) }
    var selectForRestore by remember { mutableStateOf(false) }
    var selectForBackup by remember { mutableStateOf(false) }
    var showsAdvanced by remember { mutableStateOf(false) }
    var suggestedFolder by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        runCatching { graph.backupService.listBackups() }
            .onSuccess { backups = it }
            .onFailure { backups = emptyList(); error = it.message ?: "无法读取备份文件夹。" }
    }

    LaunchedEffect(folder?.treeUri) { reload() }

    fun createBackup() {
        if (busy || manualScopes.isEmpty()) return
        scope.launch {
            busy = true; error = null; message = null
            runCatching { graph.backupService.backup(manualScopes) }
                .onSuccess { result ->
                    message = buildString {
                        append("备份完成：${result.archiveName}（${formatBytes(result.sizeBytes)}）。")
                        if (result.removedCount > 0) append("同时清理了 ${result.removedCount} 份旧备份。")
                        if (result.isOverLimit) append("最新一份自身已超过容量上限，因此仍被保留。")
                    }
                }.onFailure { error = it.message ?: "备份没有完成，请稍后重试。" }
            reload(); busy = false
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val backupAfterSelection = selectForBackup
        selectForBackup = false
        if (uri == null) { selectForRestore = false; return@rememberLauncherForActivityResult }
        scope.launch {
            error = null
            if (selectForRestore) {
                selectForRestore = false
                busy = true
                runCatching { graph.backupService.listBackupsInFolder(uri).maxByOrNull { it.createdAtEpochMillis } }
                    .onSuccess { newest ->
                        if (newest == null) error = "所选文件夹中没有可用的 Jerreader 备份。"
                        else { restoringFolder = uri; pendingRestore = newest }
                    }.onFailure { error = it.message ?: "无法读取备份文件夹。" }
                busy = false
                return@launch
            }
            busy = true
            val selected = runCatching { withContext(Dispatchers.IO) { graph.backupDirectory.select(uri) } }
            busy = false
            selected
                .onSuccess {
                    suggestedFolder = null
                    if (backupAfterSelection) createBackup()
                    else message = "备份位置已更改：${it.displayName}"
                }
                .onFailure { error = "无法使用这个文件夹，请重新授权或选择其他位置。" }
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
        selectForRestore = true
        folderPicker.launch(folder?.treeUri)
    }

    SettingsPage(title = "备份中心", onBack = onBack, bottomBar = bottomBar) {
        SettingsGroup("备份位置", footer = "选择你自己的文件夹，备份直接保存在那里。重装或重签后点‘从备份文件夹恢复’，选择同一文件夹即可恢复并继续使用。") {
            SettingsValueRow("当前文件夹", folder?.displayName ?: "尚未选择外部文件夹", compact = false)
            suggestedFolder?.let { (uri, name) ->
                SettingsFootnote("刚恢复的备份来自「$name」。")
                SettingsActionRow("继续备份到这个文件夹", { selectForRestore = false; folderPicker.launch(uri) }, enabled = !busy)
            }
            SettingsActionRow("选择备份文件夹", { selectForRestore = false; folderPicker.launch(folder?.treeUri) }, enabled = !busy, showDivider = folder != null, icon = JerreaderIcon.FOLDER)
            if (folder != null) SettingsActionRow("停止使用此文件夹", {
                graph.backupDirectory.clear(); backups = emptyList(); message = "已停止使用该文件夹。"
            }, enabled = !busy, showDivider = false)
        }
        SettingsGroup("自动备份", footer = "在 App 运行时检查是否到期并补做；系统不保证 App 未运行时准点执行。") {
            SettingsToggleRow("自动备份", policy.automaticEnabled,
                { enabled -> graph.backupPolicy.update { it.copy(automaticEnabled = enabled) } }, showDivider = policy.automaticEnabled)
            if (policy.automaticEnabled) SettingsValueRow("上次完成",
                policy.lastAutomaticBackupAtEpochMillis?.let(::formatTimestamp) ?: "尚未备份", compact = false)
        }
        SettingsGroup("") {
            SettingsActionRow("立即备份", {
                if (folder == null) {
                    selectForRestore = false; selectForBackup = true; folderPicker.launch(null)
                } else createBackup()
            }, enabled = !busy && manualScopes.isNotEmpty())
            SettingsActionRow("更多备份选项", { showsAdvanced = !showsAdvanced }, showDivider = false, disclosureExpanded = showsAdvanced)
        }
        if (showsAdvanced) {
        SettingsGroup("自动策略", footer = "超出限制时从最旧的一份开始清理，并保留最新一份。") {
            SettingsMenuRow("备份间隔", LibraryBackupPolicy.INTERVAL_CHOICES.map { it to if (it == 1) "每天" else "每 $it 天" },
                policy.intervalDays, { days -> graph.backupPolicy.update { it.copy(intervalDays = days) } }, enabled = policy.automaticEnabled)
            SettingsMenuRow("保存时间", LibraryBackupPolicy.RETENTION_CHOICES.map { it to if (it == 0) "不按时间删除" else "保留 $it 天" },
                policy.retentionDays, { days -> graph.backupPolicy.update { it.copy(retentionDays = days) } }, enabled = policy.automaticEnabled)
            SettingsMenuRow("最多保留", LibraryBackupPolicy.COUNT_CHOICES.map { it to "$it 份" },
                policy.maximumBackupCount, { count -> graph.backupPolicy.update { it.copy(maximumBackupCount = count) } }, enabled = policy.automaticEnabled)
            SettingsMenuRow("总容量上限", LibraryBackupPolicy.SIZE_CHOICES.map { it to if (it == 0L) "不限容量" else formatBytes(it) },
                policy.maximumTotalBytes, { bytes -> graph.backupPolicy.update { it.copy(maximumTotalBytes = bytes) } },
                enabled = policy.automaticEnabled, showDivider = false)
        }
        SettingsGroup("自动备份范围", footer = "包含书籍文件会让备份明显变大，但重装后无需重新找书。") {
            LibraryBackupScope.entries.forEach { option ->
                BackupScopeRow(option, option in policy.automaticScopes, enabled = policy.automaticEnabled) { checked ->
                    graph.backupPolicy.update { current ->
                        val next = if (checked) current.automaticScopes + option else current.automaticScopes - option
                        if (next.isEmpty()) current else current.copy(automaticScopes = next)
                    }
                }
            }
        }
        SettingsGroup("手动备份") {
            LibraryBackupScope.entries.forEach { option ->
                BackupScopeRow(option, option in manualScopes, enabled = !busy) { checked ->
                    manualScopes = if (checked) manualScopes + option else manualScopes - option
                }
            }
            SettingsActionRow(if (busy) "处理中…" else "保存到当前文件夹", ::createBackup,
                enabled = folder != null && manualScopes.isNotEmpty() && !busy, showDivider = false)
        }
        }
        SettingsGroup("恢复") {
            SettingsActionRow("从备份文件夹恢复", { selectForRestore = true; folderPicker.launch(folder?.treeUri) }, enabled = !busy, showDivider = showsAdvanced)
            if (showsAdvanced) SettingsActionRow("选择单个备份文件", { archivePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                enabled = !busy, showDivider = false)
        }
        SettingsGroup("当前文件夹中的备份", footer = "列表直接读取当前文件夹。到期、超出份数或容量时会从最旧的开始清理，并尽量保留最新一份。") {
            if (backups.isEmpty()) SettingsFootnote(if (folder == null) "先选择一个备份文件夹。" else "当前文件夹还没有备份。", showDivider = false)
            backups.forEach { info ->
                SettingsInsetRow {
                    Text(info.name, style = MaterialTheme.typography.bodyLarge)
                    Text("${formatTimestamp(info.createdAtEpochMillis)} · ${formatBytes(info.sizeBytes)}", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        LearningButton(enabled = !busy, onClick = { restoringFolder = null; pendingRestore = info }) { Text("恢复") }
                        LearningButton(enabled = !busy, destructive = true, onClick = { pendingDeletion = info }) { Text("删除") }
                    }
                }
            }
        }
        SettingsGroup("重要说明") {
            SettingsFootnote("目录授权、API Key、代理凭据和可再生的翻译缓存都不会写入备份。", showDivider = false)
        }
        message?.let { JerreaderCard { Text(it) } }
        error?.let { JerreaderCard { Text(it, color = MaterialTheme.colorScheme.error) } }
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
            onDismissRequest = { pendingRestore = null; restoringFolder = null },
            title = { Text("从这份备份恢复？") },
            text = {
                Text("会把备份中的书籍与记录合并到本机，已有的内容保持不变。API Key 未包含在备份中，需要重新填写。")
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = info
                    val sourceFolder = restoringFolder
                    restoringFolder = null
                    pendingRestore = null
                    scope.launch {
                        error = null
                        message = null
                        restoreArchive(
                            onBusy = { busy = it },
                            onResult = { text, failed ->
                                if (failed) error = text else message = text
                            },
                            onRestored = { result ->
                                suggestedFolder = result.suggestedFolderUri?.let { uri ->
                                    uri to (result.suggestedFolderName ?: "刚恢复的备份文件夹")
                                }
                            }
                        ) {
                            if (sourceFolder != null) graph.backupService.restoreFromFolder(sourceFolder, target.name)
                            else graph.backupService.restore(target.name)
                        }
                        reload()
                    }
                }) { Text("开始恢复") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null; restoringFolder = null }) { Text("取消") }
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
                                result.grammarNotes.takeIf { it > 0 }?.let { "$it 条语法收藏" },
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


@Composable
private fun BackupScopeRow(option: LibraryBackupScope, selected: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    SettingsInsetRow(showDivider = option != LibraryBackupScope.entries.last()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(option.title, style = MaterialTheme.typography.bodyLarge)
                Text(option.detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            JerreaderToggle(selected, onChange, enabled = enabled, label = option.title)
        }
    }
}
