package com.jerreader.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jerreader.android.data.TranslationFavoriteEntity
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.android.learning.LearningRecordRepository
import com.jerreader.shared.learning.SentencePractice
import com.jerreader.shared.ui.JerreaderEyebrow
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderIconTile
import com.jerreader.shared.ui.JerreaderLearningChip
import com.jerreader.shared.ui.JerreaderLearningMotion
import com.jerreader.shared.ui.JerreaderLearningType
import com.jerreader.shared.ui.JerreaderRadius
import com.jerreader.shared.ui.jerreaderPalette
import com.jerreader.shared.ui.jerreaderPressScale
import com.jerreader.shared.ui.jerreaderSerifFamily
import com.jerreader.shared.ui.plainSecondary
import com.jerreader.shared.ui.plainTertiary
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * iOS `SentencePracticeView`: the saved sentences as cards that open the cloze
 * editor and self-test (titled 原句自测 by the learning tab's bar). The list
 * keeps its scroll position and does not stagger again when a sentence is
 * closed, as iOS's navigation stack does.
 */
@Composable
fun SentencePracticeScreen(sentences: List<TranslationFavoriteEntity>, repository: LearningRecordRepository,
    selected: String?, onSelect: (String?) -> Unit) {
    val record = sentences.firstOrNull { it.favoriteKey == selected }
    BackHandler(enabled = selected != null) { onSelect(null) }
    val listState = rememberLazyListState()
    val stagger = rememberLearningStaggerWindow()
    if (record != null) {
        LearningPage {
            item(key = record.favoriteKey) { SentencePracticeEditor(record, repository) }
        }
    } else {
        LearningPage(state = listState) {
            item(key = "intro", contentType = "intro") {
                LearningHint("用收藏的句子制作练习。指定要挖空的词或短语，可自行修改题面；制作和答题都在本机完成。")
            }
            if (sentences.isEmpty()) item(key = "empty", contentType = "empty") { SentenceListEmpty() }
            itemsIndexed(
                sentences,
                key = { _, sentence -> "sentence:${sentence.favoriteKey}" },
                contentType = { _, _ -> "sentence" }
            ) { index, sentence ->
                SentenceCard(
                    sentence = sentence,
                    onOpen = { onSelect(sentence.favoriteKey) },
                    modifier = Modifier.learningStagger(index, stagger)
                )
            }
        }
    }
}

/** iOS's empty list: the quote tile over centred subheadline guidance, 32 above and below. */
@Composable
private fun SentenceListEmpty(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        JerreaderIconTile(JerreaderIcon.SENTENCES, size = 52.dp)
        Text(
            text = "先在阅读器或翻译页收藏一句译文。",
            style = JerreaderLearningType.subheadline,
            color = MaterialTheme.colorScheme.plainSecondary,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * One saved sentence: the accent quote mark, three lines of the sentence over
 * its state chip (gold before a card is made, accent after), a tertiary
 * chevron. The card presses to 98%.
 */
@Composable
private fun SentenceCard(sentence: TranslationFavoriteEntity, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val palette = jerreaderPalette
    val interaction = remember { MutableInteractionSource() }
    val practiced = sentence.practiceJson != null
    LearningCard(
        modifier
            .jerreaderPressScale(interaction, pressedScale = 0.98f)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onOpen)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JerreaderGlyph(JerreaderIcon.SENTENCES, palette.accent, Modifier.padding(top = 3.dp).size(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sentence.sourceText, maxLines = 3, overflow = TextOverflow.Ellipsis)
                JerreaderLearningChip(
                    text = if (practiced) "开始练习 · 可编辑" else "制作练习卡",
                    tint = if (practiced) palette.accent else palette.gold
                )
            }
            JerreaderGlyph(
                JerreaderIcon.CHEVRON_FORWARD,
                MaterialTheme.colorScheme.plainTertiary,
                Modifier.padding(top = 4.dp).size(13.dp)
            )
        }
    }
}

/**
 * iOS `SentencePracticeEditor`: one card that makes the cloze (原句, the term
 * to blank, an editable prompt) or tests it. The answer panel fades in and
 * grows from 96% at its top while the card grows with it.
 */
@Composable
private fun SentencePracticeEditor(record: TranslationFavoriteEntity, repository: LearningRecordRepository) {
    val words by remember(repository) { repository.observeWords() }.collectAsStateWithLifecycle(emptyList())
    val card = remember { runCatching { JSONObject(record.practiceJson ?: "") }.getOrNull() }
    var answer by remember { mutableStateOf(card?.optString("answer") ?: "") }
    var prompt by remember { mutableStateOf(card?.optString("prompt") ?: "") }
    var editing by remember { mutableStateOf(card == null) }
    var revealed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val palette = jerreaderPalette
    val label = JerreaderLearningType.subheadline.copy(fontWeight = FontWeight.SemiBold)
    LearningCard {
        error?.let { Text(it, style = JerreaderLearningType.subheadline, color = palette.vermilion) }
        if (editing) {
            JerreaderEyebrow("原句")
            SelectionContainer { Text(record.sourceText) }
            Text("挖空词或短语（全部相同文字）", style = label)
            LearningTextField("输入原句中的词或短语", answer) {
                answer = it; prompt = SentencePractice.make(record.sourceText, it)?.prompt ?: ""
            }
            Text("题面，可编辑", style = label)
            LearningTextEditor("题面，可编辑", prompt) { prompt = it }
            LearningButton(kind = LearningButtonKind.FILLED, fullWidth = true, enabled = !saving, onClick = {
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
            SelectionContainer {
                Text(
                    text = prompt,
                    style = JerreaderLearningType.title3.copy(
                        fontFamily = jerreaderSerifFamily(prompt),
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
            // The panel's 12 above it grows with it, so nothing below jumps.
            Column {
                LearningButton(kind = LearningButtonKind.FILLED, fullWidth = true, onClick = { revealed = !revealed }) {
                    Text(if (revealed) "隐藏答案" else "显示答案与译文")
                }
                AnimatedVisibility(
                    visible = revealed,
                    enter = fadeIn(JerreaderLearningMotion.stateChange()) +
                        scaleIn(JerreaderLearningMotion.stateChange(), initialScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 0f)) +
                        expandVertically(JerreaderLearningMotion.stateChange(), expandFrom = Alignment.Top),
                    exit = fadeOut(JerreaderLearningMotion.stateChange()) +
                        scaleOut(JerreaderLearningMotion.stateChange(), targetScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 0f)) +
                        shrinkVertically(JerreaderLearningMotion.stateChange(), shrinkTowards = Alignment.Top),
                    label = "sentence-answer"
                ) {
                    SentenceAnswer(record, answer, words, Modifier.padding(top = 12.dp))
                }
            }
            LearningButton(onClick = { editing = true; revealed = false }) { Text("编辑练习卡") }
        }
        record.bookTitle?.let { LearningHint("来源：$it") }
    }
}

/** The answer on a jade 8% panel: the term, the sentence, its translation and any saved meanings. */
@Composable
private fun SentenceAnswer(
    record: TranslationFavoriteEntity,
    answer: String,
    words: List<WordLookupEntity>,
    modifier: Modifier = Modifier
) {
    val palette = jerreaderPalette
    val known = remember(words, answer, record.sourceLanguage) {
        words.filter { it.language == record.sourceLanguage &&
            (it.surfaceForm.equals(answer, ignoreCase = true) || it.lemma.equals(answer, ignoreCase = true))
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(palette.jade.copy(alpha = palette.jade.alpha * 0.08f), RoundedCornerShape(JerreaderRadius.control))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("答案：$answer", style = JerreaderLearningType.headline, color = palette.jade)
        Text(record.sourceText)
        Text(record.translatedText, color = MaterialTheme.colorScheme.plainSecondary)
        known.forEach {
            Text(
                text = "已有释义：" + it.definitionsText.replace(LearningRecordRepository.DEFINITION_SEPARATOR, "；"),
                style = JerreaderLearningType.subheadline
            )
        }
    }
}
