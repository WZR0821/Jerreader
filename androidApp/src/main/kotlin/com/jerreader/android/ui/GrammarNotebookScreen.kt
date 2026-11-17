package com.jerreader.android.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jerreader.android.AppGraph
import com.jerreader.android.data.GrammarNoteEntity
import com.jerreader.android.learning.LearningRecordRepository
import com.jerreader.android.reader.ReaderActivity
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.PageSheetShape
import com.jerreader.shared.ui.pageSheetHeight
import com.jerreader.shared.ui.pageSheetTop
import com.jerreader.shared.ui.JerreaderIcon
import kotlinx.coroutines.launch

@Composable
fun GrammarNotebookScreen(graph: AppGraph) {
    val notes by remember(graph) { graph.learningRecords.observeGrammarNotes() }.collectAsStateWithLifecycle(emptyList())
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<GrammarNoteEntity?>(null) }
    var pendingDeletion by remember { mutableStateOf<GrammarNoteEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val books by remember(graph) { graph.database.bookDao().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val bookIDs = remember(books) { books.map { it.id }.toSet() }
    val filtered = remember(notes, search) { notes.filter { search.isBlank() || listOf(it.title, it.sentence, it.explanation, it.tags, learningLanguageName(it.language))
        .any { value -> value.contains(search, ignoreCase = true) } } }
    LearningPage {
      item {
       Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("在解析结果中保存已有解释，可在这里编辑标题、语言、解释和标签。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LearningTextField("搜索标题、原句、解释或标签", search) { search = it }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
       }
      }
        if (filtered.isEmpty()) item { LearningCard {
            Text(if (notes.isEmpty()) "还没有语法收藏。" else "没有找到匹配的语法收藏。")
        } }
        items(filtered, key = { it.id }) { note ->
            LearningCard {
                Text(note.title, style = MaterialTheme.typography.titleMedium)
                Text(note.sentence)
                Text(note.explanation)
                Text(listOfNotNull(learningLanguageName(note.language), note.tags, note.bookTitle).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LearningButton(onClick = { editing = note }) { Text("编辑") }
                if (note.locatorJson == null || note.bookId !in bookIDs) {
                    Text("无可用原书位置；已保存的内容仍可阅读。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (note.bookId != null && note.locatorJson != null && note.bookId in bookIDs) {
                        LearningButton(onClick = {
                            scope.launch {
                                if (graph.repository.book(note.bookId) == null) error = "原书已不在书架，已保存的内容仍可阅读。"
                                else context.startActivity(Intent(context, ReaderActivity::class.java)
                                    .putExtra(ReaderActivity.EXTRA_BOOK_ID, note.bookId)
                                    .putExtra(ReaderActivity.EXTRA_INITIAL_LOCATOR, note.locatorJson))
                            }
                        }) { Text("回到原书原句") }
                    }
                    LearningButton(destructive = true, onClick = { pendingDeletion = note }) { Text("删除") }
                }
            }
        }
    }
    pendingDeletion?.let { note ->
        AlertDialog(onDismissRequest = { pendingDeletion = null }, title = { Text("删除这条语法收藏？") },
            text = { Text("只删除这条收藏，原书内容不会改变。") },
            dismissButton = { TextButton(onClick = { pendingDeletion = null }) { Text("取消") } },
            confirmButton = { TextButton(onClick = {
                pendingDeletion = null
                scope.launch {
                    try { graph.learningRecords.deleteGrammarNote(note.id) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { error = "删除失败，请重试。" }
                }
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } })
    }
    editing?.let { draft -> GrammarNoteEditor(draft, graph.learningRecords) { editing = null } }
}

@Composable
fun SaveGrammarNoteButton(draft: GrammarNoteEntity, repository: LearningRecordRepository) {
    var editing by remember { mutableStateOf(false) }
    LearningButton(onClick = { editing = true }) { Text("保存到语法知识库") }
    if (editing) GrammarNoteEditor(draft, repository) { editing = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarNoteEditor(draft: GrammarNoteEntity, repository: LearningRecordRepository, onClose: () -> Unit) {
    var title by remember(draft.id) { mutableStateOf(draft.title) }
    var language by remember(draft.id) { mutableStateOf(draft.language) }
    var languageMenu by remember { mutableStateOf(false) }
    var tags by remember(draft.id) { mutableStateOf(draft.tags) }
    var explanation by remember(draft.id) { mutableStateOf(draft.explanation) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheetTop = pageSheetTop()
    LearningTypography {
    ModalBottomSheet(onDismissRequest = { if (!saving) onClose() }, dragHandle = null,
        shape = PageSheetShape, containerColor = MaterialTheme.colorScheme.background,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
      Column(Modifier.pageSheetHeight(sheetTop).fillMaxWidth().imePadding().padding(horizontal = 16.dp)) {
        // The iOS sheet's inline bar: 56 tall, its buttons 20 points from the edges.
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            LearningButton(enabled = !saving, onClick = onClose) { Text("取消") }
            Text("保存语法", style = MaterialTheme.typography.titleMedium)
        LearningButton(enabled = !saving && title.isNotBlank() && explanation.isNotBlank(), onClick = {
            saving = true
            scope.launch {
                try {
                    repository.saveGrammarNote(draft.copy(title = title.trim(), language = language, tags = tags.trim(),
                        explanation = explanation.trim(), updatedAt = System.currentTimeMillis()))
                    onClose()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (_: Exception) { error = "语法收藏未能保存，请重试。"
                } finally { saving = false }
            }
        }) { Text("保存") }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LearningCard {
            LearningTextField("标题", title) { title = it }
            Box {
                LearningButton(onClick = { languageMenu = true }) {
                    Text(learningLanguageName(language))
                    JerreaderGlyph(JerreaderIcon.CHEVRON_DOWN, MaterialTheme.colorScheme.primary,
                        Modifier.padding(start = 6.dp).size(12.dp))
                }
                DropdownMenu(languageMenu, { languageMenu = false }) {
                    listOf("ja", "zh-Hans", "en").forEach { value ->
                        DropdownMenuItem(text = { Text(learningLanguageName(value)) }, onClick = { language = value; languageMenu = false })
                    }
                }
            }
            LearningTextField("标签，用逗号分隔", tags) { tags = it }
            }
            LearningCard {
                Text("原句", style = MaterialTheme.typography.titleMedium)
                Text(draft.sentence)
            }
            LearningCard {
                Text("解释，可编辑", style = MaterialTheme.typography.titleMedium)
                androidx.compose.foundation.text.BasicTextField(explanation, { explanation = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp))
            }
        }
      }
    }
    }
}

private fun learningLanguageName(value: String) = when (value) {
    "ja", "JAPANESE" -> "日语"
    "zh-Hans", "zh-CN", "CHINESE_SIMPLIFIED" -> "中文"
    "en", "ENGLISH" -> "英语"
    else -> value
}
