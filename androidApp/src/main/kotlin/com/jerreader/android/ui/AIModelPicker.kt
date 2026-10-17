package com.jerreader.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.jerreader.android.translation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AIModelPicker(configuration: AIModelCatalogConfiguration, selectedModel: String, onSelect: (String) -> Unit,
                  service: AIModelCatalogService = remember { RemoteAIModelCatalogService() }) {
    // A different provider, endpoint or credential owns a completely new catalog and request scope.
    key(configuration) {
        val scope = rememberCoroutineScope()
        val session = remember(scope) { TranslationToolSession(scope) }
        var catalog by remember { mutableStateOf<AIModelCatalog?>(null) }
        var loading by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var showing by remember { mutableStateOf(false) }
        var search by remember { mutableStateOf("") }
        DisposableEffect(Unit) { onDispose { session.invalidate() } }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Text("当前模型：${selectedModel.ifBlank { "未填写" }}", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(modifier = Modifier.testTag("model-refresh"), enabled = !loading && configuration.key.isNotBlank(), onClick = {
                    session.invalidate(); loading = true; message = null
                    session.launch(timeoutMillis = 30_000,
                        onTimeout = { loading = false; message = "模型列表等待超时，请重试；当前模型保持不变。" },
                        onFinished = { loading = false }) {
                        try {
                            val result = service.fetch(configuration)
                            ensureActive()
                            catalog = result
                            if (result.models.isEmpty()) message = "服务未返回可选模型，可手动填写模型 ID。"
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            ensureActive()
                            message = (error as? AIModelCatalogException)?.message ?: "无法获取模型列表；可稍后刷新或手动填写模型 ID。"
                        }
                    }
                }) { Text(if (loading) "刷新中…" else "刷新模型列表") }
                TextButton(onClick = { search = ""; showing = true }, enabled = catalog?.models?.isNotEmpty() == true && !loading) { Text("选择模型") }
            }
            catalog?.let {
                Text("来源：${it.source} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.refreshedAt))} · ${it.models.size} 个模型",
                    style = MaterialTheme.typography.bodySmall)
                if (it.truncated) Text("列表较长，仅展示已获取的部分模型。", style = MaterialTheme.typography.bodySmall)
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("刷新获取当前账号可见的模型；选择后才会更换。列表不保证兼容翻译，请用连接测试确认。", style = MaterialTheme.typography.bodySmall)
        }
        if (showing) {
            AlertDialog(onDismissRequest = { showing = false }, title = { Text("选择模型") }, confirmButton = {
                TextButton(onClick = { showing = false }) { Text("关闭") }
            }, text = {
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(search, { search = it }, label = { Text("搜索模型名称或 ID") }, singleLine = true)
                    val filtered = catalog?.models.orEmpty().filter { it.id.contains(search, true) || it.displayName.contains(search, true) }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                        if (filtered.isEmpty()) item { Text("没有匹配的模型，可刷新或手动填写模型 ID。") }
                        items(filtered, key = { it.id }) { model ->
                            TextButton(onClick = { onSelect(model.id); showing = false }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(model.displayName)
                                    if (model.id != model.displayName) Text(model.id, style = MaterialTheme.typography.bodySmall)
                                    if (model.id == selectedModel) Text("✓ 当前模型", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            })
        }
    }
}
