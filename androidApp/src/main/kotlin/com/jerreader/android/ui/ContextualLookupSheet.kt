package com.jerreader.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.android.lexical.JapaneseChineseLexicon
import com.jerreader.android.translation.TranslationToolSession
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.LexicalLookupService
import com.jerreader.shared.lexical.WordExplanation
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ContextExplanationResult
import com.jerreader.shared.translation.ContextExplanationService
import com.jerreader.shared.translation.textCharacterCount
import com.jerreader.shared.ui.JerreaderEyebrow
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderIconTile
import com.jerreader.shared.ui.JerreaderLearningMotion
import com.jerreader.shared.ui.JerreaderLearningType
import com.jerreader.shared.ui.JerreaderMotion
import com.jerreader.shared.ui.JerreaderPillOption
import com.jerreader.shared.ui.JerreaderPillPicker
import com.jerreader.shared.ui.JerreaderRadius
import com.jerreader.shared.ui.PageSheetShape
import com.jerreader.shared.ui.jerreaderCanvasBackground
import com.jerreader.shared.ui.jerreaderPaperCard
import com.jerreader.shared.ui.jerreaderPalette
import com.jerreader.shared.ui.jerreaderPressScale
import com.jerreader.shared.ui.jerreaderQuoteBar
import com.jerreader.shared.ui.jerreaderSerifFamily
import com.jerreader.shared.ui.pageSheetHeight
import com.jerreader.shared.ui.pageSheetTop
import com.jerreader.shared.ui.plainSecondary
import com.jerreader.shared.ui.plainTertiary
import kotlinx.coroutines.*

/** Snapshot of selected text only. Never reads or writes the publication file. */
data class ContextualLookupInput(val word: String, val context: String?, val language: LanguageCode)

/** What the sheet shows below the form; a change fades the new state in. */
private enum class LookupContent { LOADING, RESULT, INTRODUCTION }

/**
 * iOS `LookupView` as the reader presents it: a page sheet on the canvas with
 * 语境查词 inline and 关闭 at the trailing edge, the reader form (language
 * pills, the quoted context, the query and 查询), then the dictionary card,
 * the AI card and the save status, 16 apart and 20 from the edges.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    fun copyEntry(value: WordExplanation) {
        clipboard.setText(AnnotatedString(value.surfaceForm + "\n" + value.contextualDictionaryEntry +
            value.sentenceContext?.let { "\n书中语境：$it" }.orEmpty()))
        status = "词条已复制。"
    }
    fun toggleFavorite(value: WordExplanation) {
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
    }
    fun analyse(value: WordExplanation) {
        val service = explanationService ?: return
        val snapshot = record ?: return
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
    }
    fun changeSense() {
        val available = choices
        invalidate()
        choices = available
    }

    fun dismiss() {
        revision++; lookupSession.invalidate(); aiSession.invalidate()
        onDismiss()
    }
    DisposableEffect(Unit) { onDispose { lookupSession.invalidate(); aiSession.invalidate() } }
    LaunchedEffect(Unit) { if (query.isNotBlank()) submit() }
    val palette = jerreaderPalette
    // Read on the reader: the sheet's own window may not report the status bar.
    val sheetTop = pageSheetTop()
    LearningTypography {
    ModalBottomSheet(onDismissRequest = ::dismiss, sheetMaxWidth = 720.dp, dragHandle = null,
        shape = PageSheetShape, containerColor = palette.canvas,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
      // The sheet is its own window, with its own focus, haptics and links.
      val sheetFocus = LocalFocusManager.current
      val haptics = LocalHapticFeedback.current
      val links = LocalUriHandler.current
      // iOS plays the success haptic when a lookup is saved.
      LaunchedEffect(record?.lookupKey) { if (record != null) haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
      Column(Modifier.pageSheetHeight(sheetTop).fillMaxWidth().jerreaderCanvasBackground().imePadding()) {
        LookupSheetBar(onClose = ::dismiss)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                // Tapping the page outside a control puts the keyboard away.
                .pointerInput(sheetFocus) { detectTapGestures(onTap = { sheetFocus.clearFocus() }) }
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                JerreaderPillPicker(
                    options = listOf(JerreaderPillOption(LanguageCode.JAPANESE, "日语"), JerreaderPillOption(LanguageCode.ENGLISH, "英语")),
                    selected = language,
                    onSelect = { if (language != it) { sheetFocus.clearFocus(); invalidate(); language = it } },
                    modifier = Modifier.semantics { contentDescription = "查询语言" },
                    minHeight = 40.dp
                )
                input.context?.takeIf { it.isNotBlank() }?.let { context ->
                    LookupContextCard(context, expanded = contextExpanded, onToggle = { contextExpanded = !contextExpanded })
                }
                LookupQueryField(
                    value = query,
                    onValueChange = { invalidate(); query = it },
                    onSearch = { sheetFocus.clearFocus(); submit() }
                )
                Text(
                    "可粘贴原句中的词语或短语，最多 80 个字符。",
                    style = JerreaderLearningType.caption,
                    color = MaterialTheme.colorScheme.plainSecondary
                )
                LearningButton(
                    onClick = { sheetFocus.clearFocus(); submit() },
                    modifier = Modifier.testTag("contextual-submit"),
                    enabled = textCharacterCount(query.trim()) in 1..80 && !loading,
                    kind = LearningButtonKind.FILLED,
                    fullWidth = true,
                    height = 48.dp
                ) { Text("查询") }
            }
            status?.let { LookupNote(it) }
            if (choices.isNotEmpty() && result == null && !loading) {
                LearningCard {
                    Text("选择词条 / 释义", style = JerreaderLearningType.headline)
                    Text(
                        "词典可能包含同音词或多个义项，请结合原句选择。",
                        style = JerreaderLearningType.caption,
                        color = MaterialTheme.colorScheme.plainSecondary
                    )
                    choices.forEach { entry ->
                        LookupChoiceRow(entry, onClick = { sheetFocus.clearFocus(); submit(entry) })
                    }
                }
            }
            // The result stays up while a favourite is saved; only a lookup shows 正在查询….
            val content = when {
                loading && result == null -> LookupContent.LOADING
                result != null -> LookupContent.RESULT
                else -> LookupContent.INTRODUCTION
            }
            key(content) {
                Column(Modifier.lookupContentEnter(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (content) {
                        LookupContent.LOADING -> LookupLoadingCard()
                        LookupContent.RESULT -> result?.let { value ->
                            val saved = record
                            LookupResultCard(
                                value = value,
                                favorite = saved?.isFavorite == true,
                                canChangeSense = choices.isNotEmpty(),
                                favoriteEnabled = !loading && !analyzing,
                                onChangeSense = ::changeSense,
                                onCopy = { copyEntry(value) },
                                onToggleFavorite = { toggleFavorite(value) }
                            )
                            LookupAICard(
                                analyzing = analyzing,
                                configured = explanationService != null,
                                enabled = explanationService != null && saved != null && !analyzing && !loading,
                                analysed = ai != null,
                                onAnalyse = { analyse(value) }
                            )
                            ai?.let { text -> LookupAIText(text, expanded = details, onToggle = { details = !details }) }
                            if (value.usageNote?.contains("中文维基词典") == true) {
                                LookupDictionaryCredit(onOpen = { runCatching { links.openUri("https://zh.wiktionary.org/") } })
                            }
                            // Hidden while the history is still being written, so it never reads as failed too early.
                            if (saved != null || !loading) {
                                LookupNote(if (saved == null) "历史保存失败，释义仍可查看。" else "已保存查词历史与本次语境。")
                            }
                        }
                        LookupContent.INTRODUCTION -> if (choices.isEmpty()) {
                            Text(
                                "输入原句中的词语或短语后查询。",
                                style = JerreaderLearningType.subheadline,
                                color = MaterialTheme.colorScheme.plainSecondary
                            )
                        }
                    }
                }
            }
        }
      }
    }
    }
}

/** The iOS sheet's inline bar: 语境查词 centred, 关闭 (a confirmation action, so semibold) 20 from the trailing edge, 56 tall. */
@Composable
private fun LookupSheetBar(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("语境查词", Modifier.semantics { heading() }, style = JerreaderLearningType.headline, maxLines = 1)
        LearningButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd)) {
            Text("关闭", fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 书中语境: the sentence in serif body beside an accent 35% capsule, three lines until 展开语境. */
@Composable
private fun LookupContextCard(context: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val palette = jerreaderPalette
    Column(
        modifier = modifier.fillMaxWidth().jerreaderPaperCard().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        JerreaderEyebrow("书中语境")
        SelectionContainer {
            Text(
                text = context,
                modifier = Modifier.jerreaderQuoteBar(palette.accent.copy(alpha = palette.accent.alpha * 0.35f)),
                style = JerreaderLearningType.body.copy(fontFamily = jerreaderSerifFamily(context)),
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (textCharacterCount(context) > 80) {
            LearningButton(onClick = onToggle) {
                Text(
                    if (expanded) "收起语境" else "展开语境",
                    style = JerreaderLearningType.subheadline.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

/**
 * iOS's vertical TextField: body on a muted 14-radius ground, padding h14 v12,
 * at least 48 tall and up to three lines; the keyboard's search key looks up.
 */
@Composable
private fun LookupQueryField(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    val palette = jerreaderPalette
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().testTag("contextual-query").semantics { contentDescription = "词语 / 修改选词" },
        textStyle = JerreaderLearningType.body.copy(color = MaterialTheme.colorScheme.onSurface),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Search
        ),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        maxLines = 3,
        cursorBrush = SolidColor(palette.accent),
        decorationBox = { field ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(palette.mutedSurface, RoundedCornerShape(JerreaderRadius.control))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text("词语 / 修改选词", style = JerreaderLearningType.body, color = palette.secondaryText.copy(alpha = .3f), maxLines = 1)
                }
                field()
            }
        }
    )
}

/** A status line under the form or the cards: caption secondary, 4 in. */
@Composable
private fun LookupNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 4.dp),
        style = JerreaderLearningType.caption,
        color = MaterialTheme.colorScheme.plainSecondary
    )
}

/** One sense to choose: the entry over its meaning on a muted 14-radius row, a tertiary chevron, pressed to 98%. */
@Composable
private fun LookupChoiceRow(entry: JapaneseChineseLexicon.Entry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .jerreaderPressScale(interaction, pressedScale = 0.98f)
            .background(jerreaderPalette.mutedSurface, RoundedCornerShape(JerreaderRadius.control))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.display, style = JerreaderLearningType.body.copy(fontWeight = FontWeight.SemiBold))
            Text(entry.definition, style = JerreaderLearningType.subheadline, color = MaterialTheme.colorScheme.plainSecondary)
        }
        JerreaderGlyph(JerreaderIcon.CHEVRON_FORWARD, MaterialTheme.colorScheme.plainTertiary, Modifier.size(13.dp))
    }
}

/** 正在查询…: the accent spinner on a 46 accent-fill tile beside the headline and its caption, on paper padded 20. */
@Composable
private fun LookupLoadingCard(modifier: Modifier = Modifier) {
    val palette = jerreaderPalette
    Row(
        modifier = modifier.fillMaxWidth().jerreaderPaperCard().padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            Modifier
                .size(46.dp)
                .background(palette.accentFill, RoundedCornerShape(JerreaderRadius.control))
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), color = palette.accent, strokeWidth = 2.dp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("正在查询…", style = JerreaderLearningType.headline)
            Text(
                "查询其他词语时，旧请求会自动取消。",
                style = JerreaderLearningType.caption,
                color = MaterialTheme.colorScheme.plainSecondary
            )
        }
    }
}

/**
 * iOS `readerResult`: the word in serif title bold, 词典形 · reading · part of
 * speech, 词典释义 · 中文 over the serif senses, the notes, 更换词条 / 释义, then
 * 复制词条 beside 加入生词本 (filled) or 取消收藏 (tinted), stacked when the
 * row does not fit.
 */
@Composable
private fun LookupResultCard(
    value: WordExplanation,
    favorite: Boolean,
    canChangeSense: Boolean,
    favoriteEnabled: Boolean,
    onChangeSense: () -> Unit,
    onCopy: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val secondary = MaterialTheme.colorScheme.plainSecondary
    LearningCard(modifier) {
        SelectionContainer {
            Text(
                text = value.surfaceForm,
                style = JerreaderLearningType.title2.copy(
                    fontSize = 28.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = jerreaderSerifFamily(value.surfaceForm)
                )
            )
        }
        val facts = listOfNotNull(value.lemma?.let { "词典形：$it" }, value.reading, value.partOfSpeech)
            .filter { it.isNotBlank() }.joinToString(" · ")
        if (facts.isNotEmpty()) Text(facts, style = JerreaderLearningType.subheadline, color = secondary)
        JerreaderEyebrow("词典释义 · 中文")
        value.definitions.forEach { definition ->
            SelectionContainer {
                Text(definition, style = JerreaderLearningType.body.copy(fontFamily = jerreaderSerifFamily(definition)))
            }
        }
        value.inflectionNote?.let { Text("活用说明\n$it", style = JerreaderLearningType.subheadline) }
        value.usageNote?.let { Text("来源与用法\n$it", style = JerreaderLearningType.caption, color = secondary) }
        if (canChangeSense) {
            LearningButton(onClick = onChangeSense, kind = LearningButtonKind.TINTED) { Text("更换词条 / 释义") }
        }
        LearningActions {
            LearningButton(onClick = onCopy, kind = LearningButtonKind.TINTED) { Text("复制词条") }
            if (favorite) {
                LearningButton(onClick = onToggleFavorite, enabled = favoriteEnabled, kind = LearningButtonKind.TINTED) { Text("取消收藏") }
            } else {
                LearningButton(onClick = onToggleFavorite, enabled = favoriteEnabled, kind = LearningButtonKind.FILLED) { Text("加入生词本") }
            }
        }
    }
}

/**
 * iOS `aiAnalysisControls`: the gold sparkles tile beside AI 语境建议 and its
 * note, then 开始解析 / 重新解析 (the spinner over 解析中… while it runs).
 */
@Composable
private fun LookupAICard(
    analyzing: Boolean,
    configured: Boolean,
    enabled: Boolean,
    analysed: Boolean,
    onAnalyse: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = jerreaderPalette
    val secondary = MaterialTheme.colorScheme.plainSecondary
    LearningCard(modifier) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            JerreaderIconTile(JerreaderIcon.SPARKLES, tint = palette.gold)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("AI 语境建议", style = JerreaderLearningType.headline)
                Text("按需分析本句；建议与词典释义分开保存。", style = JerreaderLearningType.caption, color = secondary)
            }
        }
        if (analyzing) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), color = palette.accent, strokeWidth = 2.dp)
                Text("解析中…", color = secondary)
            }
        } else {
            LearningButton(onClick = onAnalyse, enabled = enabled, kind = LearningButtonKind.TINTED) {
                Text(if (analysed) "重新解析" else "开始解析")
            }
        }
        if (!configured) Text("请先在设置中配置 AI 服务。", style = JerreaderLearningType.caption, color = secondary)
    }
}

/**
 * iOS's DisclosureGroup on paper: an accent row whose chevron turns down to
 * open the selectable suggestion. The row is 44 tall inside a card padded
 * 4 above and below, so the label sits 16 in, as on iOS.
 */
@Composable
private fun LookupAIText(text: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val accent = jerreaderPalette.accent
    val turn by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = JerreaderLearningMotion.stateChange(),
        label = "ai-disclosure"
    )
    Column(modifier.fillMaxWidth().jerreaderPaperCard().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .lookupDimmingClick(onToggle)
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(if (expanded) "收起 AI 语境建议" else "展开 AI 语境建议", Modifier.weight(1f), color = accent)
            JerreaderGlyph(JerreaderIcon.CHEVRON_FORWARD, accent, Modifier.size(13.dp).graphicsLayer { rotationZ = turn })
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(JerreaderLearningMotion.stateChange()) +
                expandVertically(JerreaderLearningMotion.stateChange(), expandFrom = Alignment.Top),
            exit = fadeOut(JerreaderLearningMotion.stateChange()) +
                shrinkVertically(JerreaderLearningMotion.stateChange(), shrinkTowards = Alignment.Top),
            label = "ai-suggestion"
        ) {
            SelectionContainer { Text(text, Modifier.padding(bottom = 12.dp)) }
        }
    }
}

/** iOS's link under a Wiktionary-sourced result: caption accent with the info mark, 4 in. */
@Composable
private fun LookupDictionaryCredit(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val accent = jerreaderPalette.accent
    Row(
        modifier = modifier
            .padding(horizontal = 4.dp)
            .lookupDimmingClick(onOpen),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        JerreaderGlyph(JerreaderIcon.INFO, accent, Modifier.size(13.dp))
        Text("词典数据：中文维基词典（CC BY-SA）", style = JerreaderLearningType.caption, color = accent)
    }
}

/** A plain iOS button's press: no ripple, the content dims to 60% while held. */
private fun Modifier.lookupDimmingClick(onClick: () -> Unit): Modifier = this.composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (pressed) JerreaderMotion.press() else JerreaderMotion.release(),
        label = "lookup-press"
    )
    Modifier
        .graphicsLayer { alpha = 1f - 0.4f * press }
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
}

/**
 * iOS's lookup transition on insertion: a new state (正在查询…, a result, the
 * hint) fades in and settles from 98.5% at its top. The old state leaves at
 * once, so no stale card stays readable while the new one arrives.
 */
private fun Modifier.lookupContentEnter(): Modifier = this.composed {
    val shown = remember { Animatable(0f) }
    LaunchedEffect(shown) { shown.animateTo(1f, JerreaderLearningMotion.stateChange()) }
    graphicsLayer {
        val progress = shown.value
        alpha = progress.coerceIn(0f, 1f)
        val scale = 0.985f + 0.015f * progress
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(0.5f, 0f)
    }
}
