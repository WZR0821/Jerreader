package com.jerreader.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.android.learning.LearningRecordRepository
import com.jerreader.android.learning.WordReviewCodec
import com.jerreader.android.learning.WordReviewSnapshot
import com.jerreader.android.speech.AndroidSpeechService
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.learning.ReviewGrade
import com.jerreader.shared.learning.WordReviewState
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderKindDot
import com.jerreader.shared.ui.JerreaderLearningMotion
import com.jerreader.shared.ui.JerreaderLearningSectionHeading
import com.jerreader.shared.ui.JerreaderLearningType
import com.jerreader.shared.ui.JerreaderProgressBar
import com.jerreader.shared.ui.JerreaderTintedButton
import com.jerreader.shared.ui.jerreaderBounce
import com.jerreader.shared.ui.jerreaderPalette
import com.jerreader.shared.ui.jerreaderQuoteBar
import com.jerreader.shared.ui.jerreaderSerifFamily
import com.jerreader.shared.ui.plainSecondary
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iOS `WordReviewView`: one card for the group (progress, the word in serif,
 * its sentence and book, then the answer and the three grades) and a card per
 * saved word to pause or reset it.
 */
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
    val haptics = LocalHapticFeedback.current
    val palette = jerreaderPalette
    val secondary = MaterialTheme.colorScheme.plainSecondary
    val favorites = remember(words) { words.filter { it.isFavorite } }
    val byKey = remember(favorites) { favorites.associateBy { it.lookupKey } }
    val inputs = remember(favorites) { favorites.map { it.lookupKey to it.reviewJson } }
    val prepared by produceState<Pair<List<Pair<String, String?>>, WordReviewSnapshot>?>(null, inputs) {
        value = inputs to withContext(Dispatchers.Default) { WordReviewSnapshot.create(inputs, checkpoint = { ensureActive() }) }
    }
    // The last snapshot stays in use while the next one is worked out, as on
    // iOS. Dropping it the moment a grade or a pause was saved blanked the card
    // to 正在准备复习… and emptied 管理复习, which lost its scroll position.
    val snapshot = prepared?.second
    val current = queue.firstNotNullOfOrNull { key ->
        byKey[key]?.takeIf { snapshot?.states?.get(key)?.paused == false }
    }
    val dueKeys = remember(snapshot, now) { snapshot?.dueKeys(now).orEmpty() }
    // How far through this group: the answered words out of those answered and those still to come.
    val remaining = queue.count { key -> byKey.containsKey(key) && snapshot?.states?.get(key)?.paused == false }
    val sessionProgress = if (completed + remaining > 0) completed.toFloat() / (completed + remaining) else 0f
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
    fun save(word: WordLookupEntity, state: WordReviewState, graded: Boolean = false, onSaved: () -> Unit = {}) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                repository.saveReview(word.lookupKey, state)
                queue = queue.filterNot { it == word.lookupKey }
                if (graded) completed++
                revealed = false
                error = null
                onSaved()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: Exception) { error = "复习进度未能保存，请重试。"
            } finally { saving = false }
        }
    }
    fun gradeWord(word: WordLookupEntity, grade: ReviewGrade) {
        // An answer still fading out after its word was graded does nothing.
        if (current?.lookupKey != word.lookupKey) return
        save(word, WordReviewCodec.decode(word.reviewJson)
            .grade(grade, System.currentTimeMillis(), word.sourceBookId), graded = true) {
            // Success for 认识 and for the answer that ends the group, a warning for 不认识.
            val finishesGroup = queue.none { key -> byKey.containsKey(key) && snapshot?.states?.get(key)?.paused == false }
            when {
                finishesGroup || grade == ReviewGrade.KNOWN -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                grade == ReviewGrade.UNKNOWN -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
                else -> Unit
            }
        }
    }
    LearningPage {
        item(key = "review", contentType = "review-card") {
            LearningCard {
                Text(
                    text = "每组最多 10 个词。先回忆原句中的意思，再查看释义。",
                    style = JerreaderLearningType.subheadline,
                    color = secondary
                )
                error?.let { Text(it, style = JerreaderLearningType.subheadline, color = palette.vermilion) }
                if (snapshot == null) {
                    Text("正在准备复习…", style = JerreaderLearningType.body, color = secondary)
                } else if (current != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "本组已完成 $completed 个",
                            style = JerreaderLearningType.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = secondary
                        )
                        JerreaderProgressBar(progress = sessionProgress)
                    }
                    Text(
                        text = current.surfaceForm,
                        style = JerreaderLearningType.largeTitle.copy(fontFamily = jerreaderSerifFamily(current.surfaceForm))
                    )
                    current.sentenceContext?.let { sentence ->
                        SelectionContainer(Modifier.jerreaderQuoteBar(palette.accent.copy(alpha = 0.35f))) {
                            Text(sentence, style = JerreaderLearningType.body)
                        }
                    }
                    current.sourceBookTitle?.let { book ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            JerreaderGlyph(JerreaderIcon.BOOK_CLOSED, secondary, Modifier.size(12.dp))
                            Text(book, style = JerreaderLearningType.caption, color = secondary)
                        }
                    }
                    // The answer grows in from 96% at its top while 显示释义 fades; the
                    // word rides along so a fading answer keeps showing its own word.
                    AnimatedContent(
                        targetState = current.takeIf { revealed },
                        modifier = Modifier.fillMaxWidth(),
                        transitionSpec = {
                            val origin = TransformOrigin(0.5f, 0f)
                            val motion = if (targetState != null) {
                                (fadeIn(JerreaderLearningMotion.stateChange()) +
                                    scaleIn(JerreaderLearningMotion.stateChange(), initialScale = 0.96f, transformOrigin = origin))
                                    .togetherWith(fadeOut(JerreaderLearningMotion.stateChange()))
                            } else {
                                fadeIn(JerreaderLearningMotion.stateChange())
                                    .togetherWith(
                                        fadeOut(JerreaderLearningMotion.stateChange()) +
                                            scaleOut(JerreaderLearningMotion.stateChange(), targetScale = 0.96f, transformOrigin = origin)
                                    )
                            }
                            motion.using(SizeTransform { _, _ -> JerreaderLearningMotion.stateChange() })
                        },
                        label = "review-answer",
                        contentKey = { it?.lookupKey }
                    ) { word ->
                        if (word != null) {
                            ReviewAnswer(
                                word = word,
                                canSpeak = speech != null && LanguageCode.fromTag(word.language) == LanguageCode.JAPANESE,
                                saving = saving,
                                onSpeak = ::speak,
                                onGrade = { gradeWord(word, it) }
                            )
                        } else {
                            LearningButton(
                                onClick = { revealed = true },
                                kind = LearningButtonKind.FILLED,
                                fullWidth = true
                            ) { Text("显示释义") }
                        }
                    }
                } else {
                    if (started) ReviewGroupDone()
                    Text(if (started) "本组复习完成" else "今日复习", style = JerreaderLearningType.headline)
                    // Centred, as iOS lays out the TimelineView it sits in.
                    Text("当前待复习：${dueKeys.size} 个", Modifier.fillMaxWidth(), style = JerreaderLearningType.subheadline,
                        color = secondary, textAlign = TextAlign.Center)
                    LearningButton(
                        onClick = { queue = dueKeys.take(10); completed = 0; started = true; revealed = false },
                        enabled = dueKeys.isNotEmpty(),
                        kind = LearningButtonKind.FILLED,
                        fullWidth = true
                    ) { Text(if (started) "继续下一组" else "开始复习") }
                    if (favorites.isEmpty()) {
                        Text("先在生词本收藏词语，它们会自动加入复习。", style = JerreaderLearningType.subheadline, color = secondary)
                    }
                }
            }
        }
        item(key = "manage-heading", contentType = "heading") { JerreaderLearningSectionHeading(title = "管理复习") }
        items(if (snapshot != null) favorites else emptyList(), key = { it.lookupKey }, contentType = { "review" }) { word ->
            val state = snapshot?.states?.get(word.lookupKey) ?: WordReviewState()
            LearningCard {
                Text(word.surfaceForm, style = JerreaderLearningType.headline)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JerreaderKindDot(reviewStatusTint(state, now))
                    Text(
                        text = if (state.paused) "已暂停" else if (state.answers.isEmpty()) "新词"
                            else "下次：${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(state.dueAt))}",
                        style = JerreaderLearningType.caption,
                        color = secondary
                    )
                }
                LearningActions {
                    LearningButton(
                        onClick = { save(word, state.copy(paused = !state.paused)) },
                        enabled = !saving,
                        kind = LearningButtonKind.TINTED
                    ) { Text(if (state.paused) "恢复" else "暂停") }
                    LearningButton(
                        onClick = { save(word, WordReviewState()) },
                        enabled = !saving,
                        kind = LearningButtonKind.TINTED
                    ) { Text("重置进度") }
                }
            }
        }
    }
}

/** The revealed answer: the reading, 朗读 for Japanese, the definitions and the three grades. */
@Composable
private fun ReviewAnswer(
    word: WordLookupEntity,
    canSpeak: Boolean,
    saving: Boolean,
    onSpeak: (String) -> Unit,
    onGrade: (ReviewGrade) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = jerreaderPalette
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        word.reading?.let { Text(it, style = JerreaderLearningType.title3, color = MaterialTheme.colorScheme.plainSecondary) }
        // Hear the word, then the real sentence, and say it back.
        if (canSpeak) {
            LearningActions {
                LearningButton(onClick = { onSpeak(word.surfaceForm) }, kind = LearningButtonKind.TINTED) { Text("朗读单词") }
                word.sentenceContext?.takeIf(String::isNotBlank)?.let { sentence ->
                    LearningButton(onClick = { onSpeak(sentence) }, kind = LearningButtonKind.TINTED) { Text("朗读原句·跟读") }
                }
            }
        }
        Text(word.definitionsText.replace(LearningRecordRepository.DEFINITION_SEPARATOR, "；"), style = JerreaderLearningType.body)
        ReviewGradeLayout {
            ReviewGrade.entries.forEach { grade ->
                JerreaderTintedButton(
                    text = grade.title,
                    onClick = { onGrade(grade) },
                    enabled = !saving,
                    tint = when (grade) {
                        ReviewGrade.UNKNOWN -> palette.vermilion
                        ReviewGrade.FUZZY -> palette.gold
                        ReviewGrade.KNOWN -> palette.jade
                    },
                    height = 48.dp,
                    fullWidth = true
                )
            }
        }
    }
}

/**
 * iOS `ViewThatFits` around the grades: side by side 8 apart and equally wide
 * when their natural widths fit, otherwise stacked 8 apart at full width.
 */
@Composable
private fun ReviewGradeLayout(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val gaps = gap * (measurables.size - 1).coerceAtLeast(0)
        val ideal = measurables.sumOf { it.maxIntrinsicWidth(Constraints.Infinity) } + gaps
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else ideal
        if (measurables.isNotEmpty() && ideal <= width) {
            val each = (width - gaps) / measurables.size
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(each)) }
            val height = placeables.maxOf { it.height }
            layout(width, height) {
                placeables.forEachIndexed { index, placeable ->
                    placeable.placeRelative(index * (each + gap), (height - placeable.height) / 2)
                }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints.fixedWidth(width)) }
            val height = placeables.sumOf { it.height } + gaps
            layout(width, height) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + gap
                }
            }
        }
    }
}

/** The finished group's jade check mark, bouncing once as it grows in, beside a full jade bar. */
@Composable
private fun ReviewGroupDone(modifier: Modifier = Modifier) {
    val palette = jerreaderPalette
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    var celebration by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { celebration++ }
    AnimatedVisibility(
        visibleState = shown,
        modifier = modifier,
        enter = fadeIn(JerreaderLearningMotion.stateChange()) +
            scaleIn(JerreaderLearningMotion.stateChange(), initialScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 0f)),
        exit = fadeOut(JerreaderLearningMotion.stateChange()),
        label = "review-done"
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JerreaderGlyph(JerreaderIcon.CHECK_CIRCLE, palette.jade, Modifier.size(40.dp).jerreaderBounce(celebration))
            JerreaderProgressBar(progress = 1f, modifier = Modifier.weight(1f), tint = palette.jade)
        }
    }
}

/** Paused grey, new accent, due vermilion, scheduled jade. */
@Composable
private fun reviewStatusTint(state: WordReviewState, now: Long): Color {
    val palette = jerreaderPalette
    return when {
        state.paused -> palette.secondaryText.copy(alpha = palette.secondaryText.alpha * 0.5f)
        state.answers.isEmpty() -> palette.accent
        state.dueAt <= now -> palette.vermilion
        else -> palette.jade
    }
}
