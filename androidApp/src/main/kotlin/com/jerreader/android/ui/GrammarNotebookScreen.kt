package com.jerreader.android.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jerreader.android.AppGraph
import com.jerreader.android.data.GrammarNoteEntity
import com.jerreader.android.learning.LearningRecordRepository
import com.jerreader.android.reader.ReaderActivity
import com.jerreader.shared.ui.JerreaderEyebrow
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderIconTile
import com.jerreader.shared.ui.JerreaderLearningType
import com.jerreader.shared.ui.JerreaderTintedButton
import com.jerreader.shared.ui.PageSheetShape
import com.jerreader.shared.ui.jerreaderCanvasBackground
import com.jerreader.shared.ui.jerreaderPalette
import com.jerreader.shared.ui.jerreaderQuoteBar
import com.jerreader.shared.ui.jerreaderSerifFamily
import com.jerreader.shared.ui.pageSheetHeight
import com.jerreader.shared.ui.pageSheetTop
import com.jerreader.shared.ui.plainSecondary
import kotlinx.coroutines.launch

/**
 * iOS `GrammarNotebookView`: the hint and the search field over one card per
 * saved explanation (its title, the sentence in serif behind a vermilion bar,
 * the explanation and where it came from) with tinted 编辑 · 回到原书原句 · 删除.
 */
@Composable
fun GrammarNotebookScreen(graph: AppGraph) {
    val notes by remember(graph) { graph.learningRecords.observeGrammarNotes() }.collectAsStateWithLifecycle(emptyList())
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<GrammarNoteEntity?>(null) }
    var pendingDeletion by remember { mutableStateOf<GrammarNoteEntity?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val palette = jerreaderPalette
    val secondary = MaterialTheme.colorScheme.plainSecondary
    val stagger = rememberLearningStaggerWindow()
    val books by remember(graph) { graph.database.bookDao().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val bookIDs = remember(books) { books.map { it.id }.toSet() }
    val filtered = remember(notes, search) { notes.filter { search.isBlank() || listOf(it.title, it.sentence, it.explanation, it.tags,
        it.language, learningLanguageName(it.language)).any { value -> value.contains(search, ignoreCase = true) } } }
    LearningPage {
        item(key = "hint") { LearningHint("在解析结果中保存已有解释，可在这里编辑标题、语言、解释和标签。") }
        item(key = "search") {
            LearningTextField("搜索标题、原句、解释或标签", search, icon = JerreaderIcon.SEARCH) { search = it }
        }
        error?.let { message ->
            item(key = "error") { Text(message, style = JerreaderLearningType.subheadline, color = palette.vermilion) }
        }
        itemsIndexed(filtered, key = { _, note -> note.id }) { index, note ->
            // Only a note with a locator in a book still on the shelf can go back to it.
            val bookId = note.bookId?.takeIf { note.locatorJson != null && it in bookIDs }
            LearningCard(Modifier.learningStagger(index, stagger)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    JerreaderGlyph(JerreaderIcon.NOTE, palette.vermilion, Modifier.size(15.dp).alignBy { it.measuredHeight })
                    Text(note.title, Modifier.weight(1f).alignByBaseline(), style = JerreaderLearningType.headline)
                }
                SelectionContainer(Modifier.jerreaderQuoteBar(palette.vermilion.copy(alpha = 0.4f))) {
                    Text(note.sentence, style = JerreaderLearningType.body.copy(fontFamily = jerreaderSerifFamily(note.sentence)))
                }
                SelectionContainer { Text(note.explanation, style = JerreaderLearningType.subheadline) }
                Text(
                    listOfNotNull(learningLanguageName(note.language), note.tags, note.bookTitle).filter(String::isNotBlank).joinToString(" · "),
                    style = JerreaderLearningType.caption,
                    color = secondary
                )
                if (bookId == null) {
                    Text("无可用原书位置；已保存的内容仍可阅读。", style = JerreaderLearningType.caption, color = secondary)
                }
                LearningActions {
                    LearningButton(onClick = { editing = note }, kind = LearningButtonKind.TINTED) { Text("编辑") }
                    if (bookId != null) {
                        LearningButton(onClick = {
                            scope.launch {
                                if (graph.repository.book(bookId) == null) error = "原书已不在书架，已保存的内容仍可阅读。"
                                else context.startActivity(Intent(context, ReaderActivity::class.java)
                                    .putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
                                    .putExtra(ReaderActivity.EXTRA_INITIAL_LOCATOR, note.locatorJson))
                            }
                        }, kind = LearningButtonKind.TINTED) { Text("回到原书原句") }
                    }
                    LearningButton(
                        onClick = {
                            pendingDeletion = note
                            // Deleting asks first: a warning tick as the question appears.
                            haptics.performHapticFeedback(HapticFeedbackType.Reject)
                        },
                        kind = LearningButtonKind.TINTED,
                        destructive = true
                    ) { Text("删除") }
                }
            }
        }
        if (filtered.isEmpty()) {
            item(key = "empty") {
                LearningCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        JerreaderIconTile(if (notes.isEmpty()) JerreaderIcon.NOTE else JerreaderIcon.SEARCH)
                        Text(if (notes.isEmpty()) "还没有语法收藏。" else "没有找到匹配的语法收藏。", color = secondary)
                    }
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
            }) { Text("删除", color = palette.vermilion) } })
    }
    editing?.let { draft -> GrammarNoteEditor(draft, graph.learningRecords) { editing = null } }
}

/** iOS `SaveGrammarNoteButton` in the tinted capsule style: a bookmark and 保存到语法知识库, opening the editor. */
@Composable
fun SaveGrammarNoteButton(draft: GrammarNoteEntity, repository: LearningRecordRepository, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    JerreaderTintedButton(
        text = "保存到语法知识库",
        onClick = { editing = true },
        modifier = modifier,
        icon = JerreaderIcon.BOOKMARK_OUTLINE
    )
    if (editing) GrammarNoteEditor(draft, repository) { editing = false }
}

/**
 * iOS `GrammarNoteEditor`: a page sheet on the canvas with 取消 · 保存语法 · 保存
 * inline, then three cards: the title, language and tags; the sentence; and
 * the editable explanation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarNoteEditor(draft: GrammarNoteEntity, repository: LearningRecordRepository, onClose: () -> Unit) {
    var title by remember(draft.id) { mutableStateOf(draft.title) }
    var language by remember(draft.id) { mutableStateOf(draft.language) }
    var tags by remember(draft.id) { mutableStateOf(draft.tags) }
    var explanation by remember(draft.id) { mutableStateOf(draft.explanation) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val palette = jerreaderPalette
    val sheetTop = pageSheetTop()
    LearningTypography {
    ModalBottomSheet(onDismissRequest = { if (!saving) onClose() }, dragHandle = null,
        shape = PageSheetShape, containerColor = palette.canvas,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
      Column(Modifier.pageSheetHeight(sheetTop).fillMaxWidth().jerreaderCanvasBackground().imePadding()) {
        // The iOS sheet's inline bar: 56 tall, its buttons 20 points from the edges.
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            LearningButton(enabled = !saving, onClick = onClose) { Text("取消") }
            Text("保存语法", style = JerreaderLearningType.headline)
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
            }) { Text("保存", fontWeight = FontWeight.SemiBold) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            error?.let { Text(it, style = JerreaderLearningType.subheadline, color = palette.vermilion) }
            LearningCard {
                LearningTextField("标题", title) { title = it }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // The picker carries the label for TalkBack.
                    Text(
                        "语言",
                        Modifier.weight(1f).clearAndSetSemantics { },
                        style = JerreaderLearningType.subheadline.copy(fontWeight = FontWeight.SemiBold)
                    )
                    GrammarLanguagePicker(language, onSelect = { language = it })
                }
                LearningTextField("标签，用逗号分隔", tags) { tags = it }
            }
            LearningCard {
                JerreaderEyebrow("原句")
                SelectionContainer {
                    Text(draft.sentence, style = JerreaderLearningType.body.copy(fontFamily = jerreaderSerifFamily(draft.sentence)))
                }
            }
            LearningCard {
                JerreaderEyebrow("解释，可编辑")
                LearningTextEditor("解释，可编辑", explanation, minHeight = 180.dp) { explanation = it }
            }
        }
      }
    }
    }
}

/**
 * The 语言 menu picker: 日语 · 中文 · 英语, keeping an unlisted current
 * language as a choice, as on iOS.
 */
@Composable
private fun GrammarLanguagePicker(language: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val options = remember(language) { listOf("ja", "zh-Hans", "en").let { if (language in it) it else it + language } }
    LearningMenuPicker(
        selected = language,
        options = options,
        description = "语言",
        label = ::learningLanguageName,
        onSelect = onSelect,
        modifier = modifier
    )
}

private fun learningLanguageName(value: String) = when (value) {
    "ja", "JAPANESE" -> "日语"
    "zh-Hans", "zh-CN", "CHINESE_SIMPLIFIED" -> "中文"
    "en", "ENGLISH" -> "英语"
    else -> value
}
