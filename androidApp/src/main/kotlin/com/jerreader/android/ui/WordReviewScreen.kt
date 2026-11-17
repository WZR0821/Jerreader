package com.jerreader.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.android.learning.LearningRecordRepository
import com.jerreader.android.learning.WordReviewCodec
import com.jerreader.android.speech.AndroidSpeechService
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.learning.ReviewGrade
import com.jerreader.shared.learning.WordReviewState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import com.jerreader.android.learning.WordReviewSnapshot
import java.text.DateFormat
import java.util.Date

@Composable
fun WordReviewScreen(
    words: List<WordLookupEntity>,
    repository: LearningRecordRepository,
    speech: AndroidSpeechService? = null
) {
    var queue by remember { mutableStateOf<List<String>>(emptyList()) }
    var completed by remember { mutableIntStateOf(0) }
    var revealed by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val scope = rememberCoroutineScope()
    val favorites = remember(words) { words.filter { it.isFavorite } }
    val byKey = remember(favorites) { favorites.associateBy { it.lookupKey } }
    val inputs = remember(favorites) { favorites.map { it.lookupKey to it.reviewJson } }
    val prepared by produceState<Pair<List<Pair<String, String?>>, WordReviewSnapshot>?>(null, inputs) {
        value = inputs to withContext(Dispatchers.Default) { WordReviewSnapshot.create(inputs, checkpoint = { ensureActive() }) }
    }
    val snapshot = prepared?.takeIf { it.first == inputs }?.second
    val current = queue.firstNotNullOfOrNull { key ->
        byKey[key]?.takeIf { snapshot?.states?.get(key)?.paused == false }
    }
    val dueKeys = remember(snapshot, now) { snapshot?.dueKeys(now).orEmpty() }
    LaunchedEffect(current?.lookupKey) { revealed = false }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
    DisposableEffect(speech) { onDispose { speech?.stop() } }
    fun speak(text: String) {
        val service = speech ?: return
        if (text.isBlank()) return
        scope.launch {
            try {
                service.speak(text.trim(), LanguageCode.JAPANESE)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { error = e.message ?: "无法朗读，请检查系统语音。" }
        }
    }
    fun save(word: WordLookupEntity, state: WordReviewState, graded: Boolean = false) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                repository.saveReview(word.lookupKey, state)
                queue = queue.filterNot { it == word.lookupKey }
                if (graded) completed++
                revealed = false
                error = null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) { error = "复习进度未能保存，请重试。"
            } finally { saving = false }
        }
    }
    LearningPage {
      item {
        LearningCard {
            Text("每组最多 10 个词。先回忆原句中的意思，再查看释义。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (snapshot == null) {
                Text("正在准备复习…")
            } else if (current != null) {
                Text("本组已完成 $completed 个", style = MaterialTheme.typography.labelMedium)
                Text(current.surfaceForm, style = MaterialTheme.typography.headlineSmall)
                current.sentenceContext?.let { Text(it) }
                current.sourceBookTitle?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (revealed) {
                    current.reading?.let { Text(it) }
                    // Hear the word, then the real sentence, and say it back.
                    if (speech != null && LanguageCode.fromTag(current.language) == LanguageCode.JAPANESE) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LearningButton(kind = LearningButtonKind.TINTED, onClick = { speak(current.surfaceForm) }) {
                                Text("朗读单词")
                            }
                            current.sentenceContext?.takeIf(String::isNotBlank)?.let { sentence ->
                                LearningButton(kind = LearningButtonKind.TINTED, onClick = { speak(sentence) }) {
                                    Text("朗读原句·跟读")
                                }
                            }
                        }
                    }
                    Text(current.definitionsText.replace(LearningRecordRepository.DEFINITION_SEPARATOR, "；"))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReviewGrade.entries.forEach { grade ->
                            LearningButton(kind = LearningButtonKind.TINTED, enabled = !saving, onClick = {
                                save(current, WordReviewCodec.decode(current.reviewJson)
                                    .grade(grade, System.currentTimeMillis(), current.sourceBookId), graded = true)
                            }) { Text(grade.title) }
                        }
                    }
                } else { LearningButton(onClick = { revealed = true }) { Text("显示释义") } }
            } else {
                Text(if (started) "本组复习完成" else "今日复习", style = MaterialTheme.typography.titleMedium)
                Text("当前待复习：${dueKeys.size} 个")
                LearningButton(enabled = dueKeys.isNotEmpty(), onClick = {
                    queue = dueKeys.take(10); completed = 0; started = true; revealed = false
                }) { Text(if (started) "继续下一组" else "开始复习") }
                if (favorites.isEmpty()) Text("先在生词本收藏词语，它们会自动加入复习。")
            }
        }
      }
        item { Text("管理复习", style = MaterialTheme.typography.titleMedium) }
        items(if (snapshot != null) favorites else emptyList(), key = { it.lookupKey }, contentType = { "review" }) { word ->
            val state = snapshot!!.states.getValue(word.lookupKey)
            LearningCard(modifier = Modifier.fillMaxWidth()) {
                Text(word.surfaceForm, style = MaterialTheme.typography.titleMedium)
                Text(if (state.paused) "已暂停" else if (state.answers.isEmpty()) "新词"
                    else "下次：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(state.dueAt))}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LearningButton(kind = LearningButtonKind.TINTED, enabled = !saving, onClick = { save(word, state.copy(paused = !state.paused)) }) {
                        Text(if (state.paused) "恢复" else "暂停")
                    }
                    LearningButton(kind = LearningButtonKind.TINTED, enabled = !saving, onClick = { save(word, WordReviewState()) }) { Text("重置进度") }
                }
            }
        }
    }
}
