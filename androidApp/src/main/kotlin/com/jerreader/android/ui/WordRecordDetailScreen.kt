package com.jerreader.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.android.data.GrammarNoteEntity
import com.jerreader.android.learning.LearningRecordRepository
import com.jerreader.shared.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** A read-only view of the saved record; opening it never performs another lookup. */
@Composable
fun WordRecordDetailScreen(record: WordLookupEntity, repository: LearningRecordRepository, onFavorite: suspend (Boolean) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var busy by remember(record.lookupKey) { mutableStateOf(false) }
    var notice by remember(record.lookupKey) { mutableStateOf<Pair<String, String>?>(null) }
    var aiExpanded by remember(record.lookupKey) { mutableStateOf(false) }
    val language = when (record.language) { "ja" -> "日语"; "en" -> "英语"; else -> "简体中文" }
    val accent = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    LearningTypography {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(top = 14.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(.75.dp, MaterialTheme.colorScheme.outline), shadowElevation = 4.dp) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                SelectionContainer(Modifier.weight(1f)) {
                                    Text(record.surfaceForm, fontSize = 36.sp, lineHeight = 43.sp, fontWeight = FontWeight.Bold,
                                        fontFamily = if (record.surfaceForm.any { it in '\u2E80'..'\u9FFF' }) FontFamily.SansSerif else FontFamily.Serif)
                                }
                                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                                    Text(language, Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = accent)
                                }
                            }
                            if (!record.lemma.isNullOrBlank() && record.lemma != record.surfaceForm)
                                Text("基本形：${record.lemma}", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                record.reading?.takeIf { it.isNotBlank() }?.let {
                                    JerreaderGlyph(JerreaderIcon.DICTIONARY, secondary, Modifier.size(18.dp))
                                    Text(it, style = MaterialTheme.typography.bodyMedium, color = secondary)
                                }
                                record.partOfSpeech?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium, color = secondary)
                                }
                            }
                        }
                        JerreaderListDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                            Text(if (record.usageNote?.contains("译文") == true || record.usageNote?.contains("AI") == true)
                                "参考释义 · 中文" else "词典释义 · 中文", style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold, color = secondary)
                            val definitions = record.definitionsText.split(LearningRecordRepository.DEFINITION_SEPARATOR).filter(String::isNotBlank)
                            if (definitions.isEmpty()) Text("暂无释义", color = secondary)
                            definitions.forEachIndexed { index, definition ->
                                Row(horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.Top) {
                                    Box(Modifier.size(24.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                        Text("${index + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = accent)
                                    }
                                    SelectionContainer { Text(definition, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal)) }
                                }
                            }
                        }
                        record.usageNote?.takeIf(String::isNotBlank)?.let { SavedWordSection("来源与用法", it) }
                        record.inflectionNote?.takeIf(String::isNotBlank)?.let { SavedWordSection("活用说明", it) }
                        record.aiAnalysis?.takeIf(String::isNotBlank)?.let { analysis ->
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { aiExpanded = !aiExpanded }, verticalAlignment = Alignment.CenterVertically) {
                                    Text("展开 AI 语境建议", Modifier.weight(1f))
                                    JerreaderGlyph(if (aiExpanded) JerreaderIcon.CHEVRON_DOWN else JerreaderIcon.CHEVRON_FORWARD, accent, Modifier.size(14.dp))
                                }
                                if (aiExpanded) {
                                    SavedWordSection("AI 语境建议（需结合原句核对）", analysis)
                                    SaveGrammarNoteButton(GrammarNoteEntity(title = record.lemma ?: record.surfaceForm,
                                        sentence = record.sentenceContext ?: record.surfaceForm, explanation = analysis,
                                        language = record.language, bookId = record.sourceBookId, bookTitle = record.sourceBookTitle), repository)
                                }
                            }
                        }
                        record.sentenceContext?.takeIf(String::isNotBlank)?.let { SavedWordSection("查询上下文", it) }
                        record.sourceBookTitle?.takeIf(String::isNotBlank)?.let {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                JerreaderGlyph(JerreaderIcon.BOOK_CLOSED, secondary, Modifier.size(16.dp))
                                Text("来源：《$it》", style = MaterialTheme.typography.bodyMedium, color = secondary)
                            }
                        }
                        JerreaderListDivider()
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SavedWordAction("复制", JerreaderIcon.COPY, modifier = Modifier.weight(1f)) {
                                clipboard.setText(AnnotatedString(listOfNotNull(record.surfaceForm, record.reading,
                                    record.definitionsText.replace(LearningRecordRepository.DEFINITION_SEPARATOR, "；"),
                                    record.usageNote, record.inflectionNote, record.sentenceContext, record.aiAnalysis).joinToString("\n")))
                                notice = "已复制" to "词条内容已复制到剪贴板。"
                            }
                            SavedWordAction(if (record.isFavorite) "已收藏" else "收藏",
                                if (record.isFavorite) JerreaderIcon.STAR_FILLED else JerreaderIcon.STAR_OUTLINE,
                                active = record.isFavorite, enabled = !busy, modifier = Modifier.weight(1f)) {
                                busy = true
                                scope.launch {
                                    try { onFavorite(!record.isFavorite) }
                                    catch (error: CancellationException) { throw error }
                                    catch (_: Exception) { notice = "无法更新收藏" to "请稍后重试。" }
                                    finally { busy = false }
                                }
                            }
                        }
                    }
                }
                Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    SavedWordDate("首次查询", record.createdAtEpochMillis, Modifier.weight(1f))
                    SavedWordDate("最近查询 · ${record.lookupCount} 次", record.lastLookedUpAtEpochMillis, Modifier.weight(1f))
                }
            }
        }
        notice?.let { (title, message) -> AlertDialog(onDismissRequest = { notice = null },
            title = { Text(title) }, text = { Text(message) }, confirmButton = { TextButton(onClick = { notice = null }) { Text("好") } }) }
    }
}

@Composable
private fun SavedWordSection(title: String, text: String) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(2.dp)))
        Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                val icon = when (title) { "来源与用法" -> JerreaderIcon.INFO; "活用说明" -> JerreaderIcon.SWAP
                    "查询上下文" -> JerreaderIcon.SENTENCES; else -> JerreaderIcon.NOTE }
                JerreaderGlyph(icon, MaterialTheme.colorScheme.primary, Modifier.size(14.dp))
                Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun SavedWordAction(title: String, icon: JerreaderIcon, active: Boolean = false, enabled: Boolean = true,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(38.dp).background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background,
            RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            JerreaderGlyph(icon, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, Modifier.size(17.dp))
        }
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SavedWordDate(title: String, timestamp: Long, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f))
        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE).format(Date(timestamp)),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
