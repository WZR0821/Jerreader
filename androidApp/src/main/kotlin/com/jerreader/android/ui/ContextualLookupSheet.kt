package com.jerreader.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.android.lexical.JapaneseChineseLexicon
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.LexicalLookupService
import com.jerreader.shared.lexical.WordExplanation
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ContextExplanationResult
import com.jerreader.shared.translation.ContextExplanationService
import kotlinx.coroutines.*
import com.jerreader.android.translation.TranslationToolSession
import com.jerreader.shared.translation.textCharacterCount
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment

/** Snapshot of selected text only. Never reads or writes the publication file. */
data class ContextualLookupInput(val word: String, val context: String?, val language: LanguageCode)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ContextualLookupSheet(
    input: ContextualLookupInput,
    dictionary: suspend () -> JapaneseChineseLexicon,
    lookup: LexicalLookupService,
    explanationService: ContextExplanationService?,
    onRecord: suspend (WordExplanation) -> WordLookupEntity,
    onAnalysis: suspend (WordLookupEntity, ContextExplanationResult) -> Boolean,
    onFavorite: suspend (WordLookupEntity, Boolean) -> Boolean,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val lookupSession = remember(scope) { TranslationToolSession(scope) }
    val aiSession = remember(scope) { TranslationToolSession(scope) }
    val focus = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    var query by remember { mutableStateOf(input.word.takeIf { textCharacterCount(it.trim()) <= 80 && it.none { c -> c in "。！？!?" } }.orEmpty()) }
    var language by remember { mutableStateOf(if (input.language == LanguageCode.CHINESE_SIMPLIFIED) LanguageCode.JAPANESE else input.language) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var analyzing by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<JapaneseChineseLexicon.Entry>>(emptyList()) }
    var result by remember { mutableStateOf<WordExplanation?>(null) }
    var record by remember { mutableStateOf<WordLookupEntity?>(null) }
    var ai by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var contextExpanded by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }

    fun invalidate() {
        revision++; lookupSession.invalidate(); aiSession.invalidate()
        loading = false; analyzing = false; result = null; record = null
        ai = null; choices = emptyList(); status = null; details = false
    }
    fun submit(chosen: JapaneseChineseLexicon.Entry? = null) {
        val word = query.trim()
        if (word.isEmpty() || textCharacterCount(word) > 80) { status = "请输入 1–80 个字符的词语或短语。"; return }
        focus.clearFocus()
        invalidate()
        val token = revision
        val direction = language
        loading = true
        lookupSession.launch(timeoutMillis = 30_000,
            onTimeout = { if (revision == token) { revision++; loading = false; status = "查词等待超时，请重试。" } },
            onFinished = { if (revision == token) loading = false }) {
            try {
                val local = if (direction == LanguageCode.JAPANESE) withContext(Dispatchers.IO) { dictionary() } else null
                val matches = withContext(Dispatchers.Default) { local?.matches(word).orEmpty() }
                ensureActive()
                if (revision != token) return@launch
                choices = matches.takeIf { it.size > 1 }.orEmpty()
                if (chosen == null && matches.map { it.term }.distinct().size > 1) return@launch
                if (chosen != null && chosen !in matches) { status = "选词已变化，请重新查询。"; return@launch }
                val entry = chosen ?: matches.firstOrNull()
                val value = if (entry != null) WordExplanation(word, entry.term, entry.reading, direction,
                    entry.partOfSpeech, listOf(entry.definition), inflectionNote = local?.inflectionNote(word, entry),
                    usageNote = entry.attribution + " · 离线释义", sentenceContext = input.context,
                    providerIdentifier = "ja-zh-bundled-" + JapaneseChineseLexicon.VERSION)
                else withContext(Dispatchers.IO) { lookup.lookup(word, input.context, direction) }
                ensureActive()
                if (revision != token) return@launch
                result = value
                val saved = onRecord(value)
                ensureActive()
                if (revision == token) { record = saved; ai = saved.aiAnalysis }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (revision == token) status = if (result != null) "历史保存失败，释义仍可查看；可点击收藏重试。" else error.message ?: "查词失败，请修改词语后重试。" }
        }
    }

    fun dismiss() {
        revision++; lookupSession.invalidate(); aiSession.invalidate()
        onDismiss()
    }
    DisposableEffect(Unit) { onDispose { lookupSession.invalidate(); aiSession.invalidate() } }
    LaunchedEffect(Unit) { if (query.isNotBlank()) submit() }
    LearningTypography {
    ModalBottomSheet(onDismissRequest = ::dismiss, sheetMaxWidth = 720.dp,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("语境查词", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = ::dismiss) { Text("关闭") }
        }
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            com.jerreader.shared.ui.JerreaderSegmentedPicker(
                options = listOf(LanguageCode.JAPANESE to "日语", LanguageCode.ENGLISH to "英语"), selected = language,
                onSelect = { if (language != it) { invalidate(); language = it } })
            input.context?.takeIf { it.isNotBlank() }?.let { context ->
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("书中语境", style = MaterialTheme.typography.bodySmall)
                        SelectionContainer { Text(context, style = MaterialTheme.typography.bodyLarge,
                            maxLines = if (contextExpanded) Int.MAX_VALUE else 3,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                        if (textCharacterCount(context) > 80) TextButton(onClick = { contextExpanded = !contextExpanded }) {
                            Text(if (contextExpanded) "收起语境" else "展开语境")
                        }
                    }
                }
            }
            OutlinedTextField(value = query, onValueChange = { invalidate(); query = it },
                label = { Text("词语 / 修改选词") }, modifier = Modifier.fillMaxWidth().testTag("contextual-query"), maxLines = 3,
                supportingText = { Text("可粘贴原句中的词语或短语，最多 80 个字符。") })
            Button(modifier = Modifier.testTag("contextual-submit"), onClick = { submit() }, enabled = textCharacterCount(query.trim()) in 1..80 && !loading) { Text("查询") }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (choices.isNotEmpty() && result == null && !loading) {
                Text("选择词条 / 释义", style = MaterialTheme.typography.titleMedium)
                Text("词典可能包含同音词或多个义项，请结合原句选择。", style = MaterialTheme.typography.labelMedium)
                choices.forEach { entry ->
                    OutlinedButton(onClick = { submit(entry) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) { Text(entry.display); Text(entry.definition) }
                    }
                }
            }
            result?.let { value ->
                val saved = record
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(value.surfaceForm, style = MaterialTheme.typography.headlineSmall)
                        Text(listOfNotNull(value.lemma?.let { "词典形：$it" }, value.reading, value.partOfSpeech)
                        .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        Text("词典释义 · 中文", style = MaterialTheme.typography.bodySmall)
                        value.definitions.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                        value.inflectionNote?.let { Text("活用说明\n$it", style = MaterialTheme.typography.bodySmall) }
                        value.usageNote?.let { Text("来源与用法\n$it", style = MaterialTheme.typography.labelMedium) }
                        if (choices.isNotEmpty()) TextButton(onClick = {
                            val available = choices
                            invalidate()
                            choices = available
                        }) { Text("更换词条 / 释义") }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                clipboard.setText(AnnotatedString(value.surfaceForm + "\n" + value.contextualDictionaryEntry +
                                value.sentenceContext?.let { "\n书中语境：$it" }.orEmpty()))
                                status = "词条已复制。"
                            }) { Text("复制词条") }
                            Button(enabled = !loading && !analyzing, onClick = {
                                val token = revision
                                loading = true
                                lookupSession.launch(timeoutMillis = 30_000,
                                onTimeout = { if (revision == token) { revision++; loading = false; status = "保存等待超时，请重试。" } },
                                onFinished = { if (revision == token) loading = false }) {
                                    try {
                                        val snapshot = record ?: onRecord(value)
                                        ensureActive()
                                        val favorite = !snapshot.isFavorite
                                        check(onFavorite(snapshot, favorite)) { "词条已删除，请重新查询。" }
                                        ensureActive()
                                        if (revision == token) { record = snapshot.copy(isFavorite = favorite); status = if (favorite) "已加入生词本，保留本次原句。" else "已取消收藏。" }
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (error: Exception) { if (revision == token) status = error.message ?: "保存失败，请重试。" }
                                }
                            }) { Text(if (saved?.isFavorite == true) "取消收藏" else "加入生词本") }
                        }
                    }
                }
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("AI 语境建议", style = MaterialTheme.typography.titleMedium)
                        Text("按需分析本句；建议与词典释义分开保存。", style = MaterialTheme.typography.labelMedium)
                        if (explanationService == null) Text("请先在设置中配置 AI 服务。", style = MaterialTheme.typography.labelMedium)
                        TextButton(enabled = explanationService != null && saved != null && !analyzing && !loading, onClick = {
                            val service = explanationService ?: return@TextButton
                            val snapshot = record ?: return@TextButton
                            val token = revision
                            aiSession.invalidate(); analyzing = true
                            aiSession.launch(timeoutMillis = 70_000,
                            onTimeout = { if (revision == token) { analyzing = false; status = "AI 解析等待超时，请重试。" } },
                            onFinished = { if (revision == token) analyzing = false }) {
                                try {
                                    val response = withContext(Dispatchers.IO) {
                                        service.explain(ContextExplanationRequest(value.surfaceForm, value.sentenceContext, value.language, isWordLookup = true,
                                        dictionaryEntry = value.contextualDictionaryEntry))
                                    }
                                    ensureActive()
                                    if (revision != token) return@launch
                                    require(com.jerreader.shared.translation.TranslationInputPolicy.isVisiblyNonEmpty(response.explanation)) { "AI 返回了空白解释，请重试。" }
                                    val stored = onAnalysis(snapshot, response)
                                    ensureActive()
                                    if (revision == token) {
                                        if (stored) ai = response.explanation
                                        else status = "词条的原句已改变，请重新查询后解析。"
                                    }
                                } catch (timeout: TimeoutCancellationException) { if (revision == token) status = "AI 解析等待超时，请重试。" }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (error: Exception) { if (revision == token) status = error.message ?: "请先在设置中配置 AI 服务。" }
                            }
                        }) { Text(if (analyzing) "解析中…" else if (ai == null) "开始解析" else "重新解析") }
                        ai?.let { text ->
                            TextButton(onClick = { details = !details }) { Text(if (details) "收起 AI 语境建议" else "展开 AI 语境建议") }
                            if (details) Text(text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Text(if (saved == null) "历史保存失败，释义仍可查看。" else "已保存查词历史与本次语境。", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
}
