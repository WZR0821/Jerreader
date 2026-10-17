package com.jerreader.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jerreader.android.data.TranslationFavoriteEntity
import com.jerreader.android.learning.LearningRecordRepository
import com.jerreader.shared.learning.SentencePractice
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun SentencePracticeScreen(sentences: List<TranslationFavoriteEntity>, repository: LearningRecordRepository,
    selected: String?, onSelect: (String?) -> Unit) {
    val record = sentences.firstOrNull { it.favoriteKey == selected }
    BackHandler(enabled = selected != null) { onSelect(null) }
    LearningPage {
        if (record == null) {
            item { Text("用收藏的句子制作练习。指定要挖空的词或短语，可自行修改题面；制作和答题都在本机完成。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (sentences.isEmpty()) item { Text("先在阅读器或翻译页收藏一句译文。") }
            items(sentences, key = { it.favoriteKey }) { sentence ->
                LearningCard(Modifier.clickable(role = Role.Button) { onSelect(sentence.favoriteKey) }) {
                    Text(sentence.sourceText, maxLines = 3)
                    Text(if (sentence.practiceJson == null) "制作练习卡" else "开始练习 · 可编辑", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            item(key = record.favoriteKey) { SentencePracticeEditor(record, repository) }
        }
    }
}

@Composable
private fun SentencePracticeEditor(record: TranslationFavoriteEntity, repository: LearningRecordRepository) {
    val words by repository.observeWords().collectAsStateWithLifecycle(emptyList())
    val card = remember { runCatching { JSONObject(record.practiceJson ?: "") }.getOrNull() }
    var answer by remember { mutableStateOf(card?.optString("answer") ?: "") }
    var prompt by remember { mutableStateOf(card?.optString("prompt") ?: "") }
    var editing by remember { mutableStateOf(card == null) }
    var revealed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LearningCard {
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (editing) {
            Text("原句", style = MaterialTheme.typography.titleMedium)
            Text(record.sourceText)
            OutlinedTextField(value = answer, onValueChange = {
                answer = it; prompt = SentencePractice.make(record.sourceText, it)?.prompt ?: ""
            }, label = { Text("挖空词或短语（全部相同文字）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("题面，可编辑") },
                minLines = 3, modifier = Modifier.fillMaxWidth())
            LearningButton(kind = LearningButtonKind.FILLED, enabled = !saving, onClick = {
                val generated = SentencePractice.make(record.sourceText, answer)
                if (generated == null || prompt.isBlank()) { error = "请指定原句中存在的内容，并填写题面。" }
                else {
                    saving = true
                    scope.launch {
                        try {
                            repository.savePractice(record.favoriteKey, JSONObject()
                                .put("answer", generated.answer).put("prompt", prompt).toString())
                            answer = generated.answer; editing = false; revealed = false; error = null
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e
                        } catch (_: Exception) { error = "练习卡未能保存，请重试。"
                        } finally { saving = false }
                    }
                }
            }) { Text("保存并练习") }
        } else {
            Text(prompt, style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal))
            LearningButton(kind = LearningButtonKind.FILLED, onClick = { revealed = !revealed }) { Text(if (revealed) "隐藏答案" else "显示答案与译文") }
            if (revealed) {
                Text("答案：$answer")
                Text(record.sourceText)
                Text(record.translatedText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                words.filter { it.language == record.sourceLanguage &&
                    (it.surfaceForm.equals(answer, ignoreCase = true) || it.lemma.equals(answer, ignoreCase = true))
                }.forEach { Text("已有释义：" + it.definitionsText.replace(LearningRecordRepository.DEFINITION_SEPARATOR, "；")) }
            }
            LearningButton(onClick = { editing = true; revealed = false }) { Text("编辑练习卡") }
        }
        record.bookTitle?.let { Text("来源：$it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
