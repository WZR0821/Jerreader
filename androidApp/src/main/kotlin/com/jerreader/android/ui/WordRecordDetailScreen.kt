package com.jerreader.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.jerreader.android.data.GrammarNoteEntity
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.android.learning.LearningRecordRepository
import com.jerreader.shared.lexical.WordExample
import com.jerreader.shared.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * A read-only view of the saved record; opening it never performs another
 * lookup. iOS `WordRecordDetailView`: the word card, then the dates panel;
 * VStack(16), padding h20, top 16, bottom 32, at most 760 wide.
 */
@Composable
fun WordRecordDetailScreen(record: WordLookupEntity, repository: LearningRecordRepository, onFavorite: suspend (Boolean) -> Unit) {
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var busy by remember(record.lookupKey) { mutableStateOf(false) }
    var notice by remember(record.lookupKey) { mutableStateOf<Pair<String, String>?>(null) }
    val language = when (record.language) { "ja" -> "日语"; "en" -> "英语"; else -> "简体中文" }
    val definitions = remember(record.definitionsText) {
        record.definitionsText.split(LearningRecordRepository.DEFINITION_SEPARATOR).filter(String::isNotBlank)
    }
    val favorite = record.isFavorite
    // iOS: success when the word is saved, a light tick when it is removed.
    var shownFavorite by remember(record.lookupKey) { mutableStateOf(favorite) }
    LaunchedEffect(favorite) {
        if (favorite != shownFavorite) {
            shownFavorite = favorite
            haptics.performHapticFeedback(if (favorite) HapticFeedbackType.Confirm else HapticFeedbackType.SegmentTick)
        }
    }
    val palette = jerreaderPalette
    val secondary = MaterialTheme.colorScheme.plainSecondary
    LearningTypography {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                WordEntryCard(
                    surfaceForm = record.surfaceForm,
                    languageName = language,
                    eyebrow = wordEntryEyebrow(record.usageNote),
                    definitions = definitions,
                    lemma = record.lemma,
                    reading = record.reading,
                    partOfSpeech = record.partOfSpeech
                ) {
                    record.usageNote?.takeIf(String::isNotBlank)?.let { WordEntrySection("来源与用法", JerreaderIcon.INFO, it) }
                    record.inflectionNote?.takeIf(String::isNotBlank)?.let { WordEntrySection("活用说明", JerreaderIcon.SWAP, it) }
                    record.aiAnalysis?.takeIf(String::isNotBlank)?.let { analysis ->
                        WordEntryDisclosure("展开 AI 语境建议") {
                            WordEntrySection("AI 语境建议（需结合原句核对）", JerreaderIcon.NOTE, analysis)
                            SaveGrammarNoteButton(GrammarNoteEntity(title = record.lemma ?: record.surfaceForm,
                                sentence = record.sentenceContext ?: record.surfaceForm, explanation = analysis,
                                language = record.language, bookId = record.sourceBookId, bookTitle = record.sourceBookTitle), repository)
                        }
                    }
                    record.sentenceContext?.takeIf(String::isNotBlank)?.let { WordEntrySection("查询上下文", JerreaderIcon.SENTENCES, it) }
                    record.sourceBookTitle?.takeIf(String::isNotBlank)?.let {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            JerreaderGlyph(JerreaderIcon.BOOK_CLOSED, secondary, Modifier.size(16.dp))
                            Text("来源：《$it》", style = JerreaderLearningType.subheadline, color = secondary)
                        }
                    }
                    WordEntryHairline()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WordEntryActionTile("复制", JerreaderIcon.COPY, modifier = Modifier.weight(1f), onClick = {
                            clipboard.setText(AnnotatedString(listOfNotNull(record.surfaceForm, record.reading,
                                record.definitionsText.replace(LearningRecordRepository.DEFINITION_SEPARATOR, "；"),
                                record.usageNote, record.inflectionNote, record.sentenceContext, record.aiAnalysis).joinToString("\n")))
                            notice = "已复制" to "词条内容已复制到剪贴板。"
                        })
                        WordEntryActionTile(
                            title = if (favorite) "已收藏" else "收藏",
                            icon = if (favorite) JerreaderIcon.STAR_FILLED else JerreaderIcon.STAR_OUTLINE,
                            modifier = Modifier.weight(1f),
                            active = favorite,
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    try { onFavorite(!record.isFavorite) }
                                    catch (error: CancellationException) { throw error }
                                    catch (_: Exception) { notice = "无法更新收藏" to "请稍后重试。" }
                                    finally { busy = false }
                                }
                            }
                        )
                    }
                }
                // Dates: two items with a hairline between, on a muted panel.
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                        .background(palette.mutedSurface, RoundedCornerShape(JerreaderRadius.control))
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SavedWordDate("首次查询", record.createdAtEpochMillis, Modifier.weight(1f))
                    Box(Modifier.width(0.5.dp).fillMaxHeight().background(palette.line))
                    SavedWordDate("最近查询 · ${record.lookupCount} 次", record.lastLookedUpAtEpochMillis, Modifier.weight(1f))
                }
            }
        }
        notice?.let { (title, message) -> AlertDialog(onDismissRequest = { notice = null },
            title = { Text(title) }, text = { Text(message) }, confirmButton = { TextButton(onClick = { notice = null }) { Text("好") } }) }
    }
}

/** iOS: a note that mentions 译文 or AI marks the meanings as a reference, not a dictionary's. */
internal fun wordEntryEyebrow(usageNote: String?): String =
    if (usageNote?.contains("译文") == true || usageNote?.contains("AI") == true) "参考释义 · 中文" else "词典释义 · 中文"

/**
 * iOS `WordRecordCard`, shared by 词条详情 and the translator's 词典详情: a raised
 * paper card (e2), padding 20, radius 20, VStack(20) — the header, a hairline,
 * the numbered meanings, then [content] (sections, disclosures, actions).
 */
@Composable
internal fun WordEntryCard(
    surfaceForm: String,
    languageName: String,
    eyebrow: String,
    definitions: List<String>,
    modifier: Modifier = Modifier,
    lemma: String? = null,
    reading: String? = null,
    partOfSpeech: String? = null,
    content: @Composable ColumnScope.() -> Unit = {}
) {
    val palette = jerreaderPalette
    val secondary = MaterialTheme.colorScheme.plainSecondary
    Column(
        modifier = modifier
            .fillMaxWidth()
            .jerreaderPaperCard(RoundedCornerShape(JerreaderRadius.card), raised = true)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                SelectionContainer(Modifier.weight(1f).alignByBaseline()) {
                    Text(surfaceForm, style = JerreaderLearningType.largeTitle.copy(fontFamily = jerreaderSerifFamily(surfaceForm)))
                }
                Spacer(Modifier.width(12.dp))
                JerreaderLearningChip(languageName, Modifier.alignByBaseline())
            }
            if (!lemma.isNullOrBlank() && lemma != surfaceForm) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    JerreaderGlyph(JerreaderIcon.SWAP, MaterialTheme.colorScheme.onSurface, Modifier.size(18.dp))
                    SelectionContainer { Text("基本形：$lemma", style = JerreaderLearningType.headline) }
                }
            }
            val shownReading = reading?.takeIf(String::isNotBlank)
            val shownPartOfSpeech = partOfSpeech?.takeIf(String::isNotBlank)
            if (shownReading != null || shownPartOfSpeech != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (shownReading != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            JerreaderGlyph(JerreaderIcon.DICTIONARY, secondary, Modifier.size(18.dp))
                            Text(shownReading, style = JerreaderLearningType.title3, color = secondary)
                        }
                    }
                    // iOS tints this chip with `Color.secondary` itself, not the row's half-strength style.
                    if (shownPartOfSpeech != null) JerreaderLearningChip(shownPartOfSpeech, tint = palette.secondaryText)
                }
            }
        }
        WordEntryHairline()
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            JerreaderEyebrow(eyebrow)
            if (definitions.isEmpty()) Text("暂无释义", style = JerreaderLearningType.body, color = secondary)
            definitions.forEachIndexed { index, definition ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.alignByBaseline().size(24.dp).background(palette.accentFill, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${index + 1}", style = JerreaderLearningType.caption.copy(fontWeight = FontWeight.Bold), color = palette.accent)
                    }
                    SelectionContainer(Modifier.weight(1f).alignByBaseline()) {
                        Text(definition, style = JerreaderLearningType.title3.copy(fontFamily = jerreaderSerifFamily(definition)))
                    }
                }
            }
        }
        content()
    }
}

/** A labelled detail: icon 14 and caption semibold in accent over serif body, beside a 3pt accent 35% bar (leading 14). */
@Composable
internal fun WordEntrySection(title: String, icon: JerreaderIcon, text: String, modifier: Modifier = Modifier) {
    val accent = jerreaderPalette.accent
    Column(
        modifier = modifier.fillMaxWidth().jerreaderQuoteBar(accent.copy(alpha = 0.35f), inset = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            JerreaderGlyph(icon, accent, Modifier.size(14.dp))
            Text(title, style = JerreaderLearningType.caption.copy(fontWeight = FontWeight.SemiBold), color = accent)
        }
        SelectionContainer { Text(text, style = JerreaderLearningType.body.copy(fontFamily = jerreaderSerifFamily(text))) }
    }
}

/** The card's 0.5pt `line` hairline. */
@Composable
internal fun WordEntryHairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(0.5.dp).background(jerreaderPalette.line))
}

/** iOS `DisclosureGroup` tinted accent: the title with a turning chevron; the content opens under it. */
@Composable
internal fun WordEntryDisclosure(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val accent = jerreaderPalette.accent
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, Modifier.weight(1f), style = JerreaderLearningType.body, color = accent)
            JerreaderDisclosureChevron(expanded, tint = accent)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(JerreaderLearningMotion.stateChange()) + fadeIn(JerreaderLearningMotion.stateChange()),
            exit = shrinkVertically(JerreaderLearningMotion.stateChange()) + fadeOut(JerreaderLearningMotion.stateChange())
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/** 展开词典例句: the 例句 label, then each example (label, serif text, translation) beside a quote bar. */
@Composable
internal fun WordEntryExamples(examples: List<WordExample>, modifier: Modifier = Modifier) {
    val accent = jerreaderPalette.accent
    val secondary = MaterialTheme.colorScheme.plainSecondary
    WordEntryDisclosure("展开词典例句", modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            JerreaderGlyph(JerreaderIcon.SENTENCES, accent, Modifier.size(14.dp))
            Text("例句", style = JerreaderLearningType.caption.copy(fontWeight = FontWeight.SemiBold), color = accent)
        }
        examples.forEach { example ->
            Column(
                Modifier.fillMaxWidth().jerreaderQuoteBar(accent.copy(alpha = 0.35f), inset = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                example.sourceLabel?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = JerreaderLearningType.caption2.copy(fontWeight = FontWeight.SemiBold), color = secondary)
                }
                SelectionContainer {
                    Text(example.sourceText, style = JerreaderLearningType.body.copy(fontFamily = jerreaderSerifFamily(example.sourceText)))
                }
                example.translatedText?.takeIf(String::isNotBlank)?.let {
                    SelectionContainer { Text(it, style = JerreaderLearningType.subheadline, color = secondary) }
                }
            }
        }
    }
}

/**
 * A 44 × 44 tile (radius 14; muted, or accentFill when active) with its caption
 * semibold title under it, equal widths, pressing to 0.96. The icon bounces when
 * the state changes; colours follow with the quick curve.
 */
@Composable
private fun WordEntryActionTile(
    title: String,
    icon: JerreaderIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true
) {
    val palette = jerreaderPalette
    val interaction = remember { MutableInteractionSource() }
    val fill by animateColorAsState(
        if (active) palette.accentFill else palette.mutedSurface, JerreaderLearningMotion.quick(), label = "word-action-fill"
    )
    val tint by animateColorAsState(
        if (active) palette.accent else MaterialTheme.colorScheme.onSurface, JerreaderLearningMotion.quick(), label = "word-action-tint"
    )
    Column(
        modifier = modifier
            .jerreaderPressScale(interaction, pressedScale = 0.96f)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(44.dp).background(fill, RoundedCornerShape(JerreaderRadius.control)), contentAlignment = Alignment.Center) {
            JerreaderGlyph(icon, tint, Modifier.size(18.dp).jerreaderBounce(active))
        }
        Text(title, style = JerreaderLearningType.caption.copy(fontWeight = FontWeight.SemiBold), color = tint)
    }
}

/** Caption secondary over subheadline semibold with tabular digits (iOS `monospacedDigit`). */
@Composable
private fun SavedWordDate(title: String, timestamp: Long, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = JerreaderLearningType.caption, color = MaterialTheme.colorScheme.plainSecondary)
        Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.SIMPLIFIED_CHINESE).format(Date(timestamp)),
            style = JerreaderLearningType.subheadline.copy(fontWeight = FontWeight.SemiBold).jerreaderTabularDigits())
    }
}
