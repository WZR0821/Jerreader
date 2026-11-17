package com.jerreader.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import kotlin.math.max
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.jerreader.android.AppGraph
import com.jerreader.shared.ui.JerreaderDivider
import com.jerreader.shared.ui.JerreaderErrorCard
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.JerreaderCollectionControls
import com.jerreader.shared.ui.JerreaderCollectionSectionHeader
import com.jerreader.shared.ui.JerreaderCountBadge
import com.jerreader.shared.ui.JerreaderEmptyState
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderInlineAction
import com.jerreader.shared.ui.JerreaderInlineMenu
import com.jerreader.shared.ui.JerreaderLanguageBar
import com.jerreader.shared.ui.JerreaderMenuField
import com.jerreader.shared.ui.JerreaderSegmentedNav
import com.jerreader.shared.ui.JerreaderSegmentedPicker
import com.jerreader.shared.ui.JerreaderSwapButton
import com.jerreader.shared.ui.JerreaderTranslatorCard
import com.jerreader.shared.ui.JerreaderCard
import com.jerreader.shared.ui.JerreaderRecordCard
import com.jerreader.shared.ui.JerreaderRelativeTime
import com.jerreader.shared.ui.JerreaderRowIconButton
import com.jerreader.shared.ui.JerreaderSearchField
import com.jerreader.shared.ui.JerreaderToolbarIconButton
import com.jerreader.shared.ui.JerreaderTranslationFavoriteRow
import com.jerreader.shared.ui.JerreaderWordRecordRow
import com.jerreader.shared.ui.LocalJerreaderColors
import com.jerreader.android.data.TranslationFavoriteEntity
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.android.learning.LearningRecordRepository
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.WordExplanation
import com.jerreader.shared.translation.textCharacterCount
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationResult
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ContextExplanationService
import com.jerreader.shared.translation.StandaloneLexicalLookupPolicy
import com.jerreader.shared.translation.StandaloneTranslationMode
import com.jerreader.shared.translation.StandaloneTranslationInput
import com.jerreader.shared.translation.StandaloneTranslationRequestPolicy
import com.jerreader.shared.translation.TranslationProviderMode
import com.jerreader.shared.translation.TranslationSourceChoice
import com.jerreader.android.translation.AIProviderSettings
import com.jerreader.android.translation.StandaloneTranslationOverride
import com.jerreader.android.translation.TranslationToolSession
import com.jerreader.android.translation.StandaloneWordLookupSession
import com.jerreader.android.translation.runCatchingActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Matches the iOS learning hub: manual lookup is no longer its own tab, it is
 * the word mode of the translate page, so one entry point both translates a
 * term and shows its dictionary entry.
 */
private enum class LearningTab(val title: String) {
    TRANSLATE("翻译"),
    VOCABULARY("生词本"),
    PRACTICE("练习与工具"),
    HISTORY("历史")
}

private enum class LearningExportFormat(val title: String, val mimeType: String) {
    CSV("CSV", "text/csv"),
    MARKDOWN("Markdown", "text/markdown"),
    ANKI_TSV("Anki TSV", "text/tab-separated-values")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningScreen(graph: AppGraph, initialSection: String = "practice", bottomBar: @Composable () -> Unit) {
    val sectionStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var studyTool by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSentence by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var selectedWordKey by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val toolTitles = linkedMapOf("review" to "生词复习", "sentences" to "原句练习卡", "grammar" to "语法知识库", "growth" to "阅读成长报告")
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(if (initialSection == "translate") LearningTab.TRANSLATE else LearningTab.PRACTICE) }
    BackHandler(enabled = studyTool != null || selectedWordKey != null) {
        if (selectedWordKey != null) selectedWordKey = null
        else if (studyTool == "sentences" && selectedSentence != null) selectedSentence = null else studyTool = null
    }
    val words by remember(graph) { graph.learningRecords.observeWords() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val translationFavorites by remember(graph) { graph.learningRecords.observeTranslationFavorites() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    // Decoding every review history is kept off the main thread, as the review screen does.
    val confusion by produceState<com.jerreader.shared.learning.ReadingConfusionMap?>(null, words) {
        value = withContext(Dispatchers.Default) {
            com.jerreader.shared.learning.ReadingConfusionMapPolicy.build(
                com.jerreader.android.learning.ReadingErrorSignals.from(words)
            )
        }
    }
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (selectedWordKey != null) {
                com.jerreader.shared.ui.JerreaderNavigationBar("词条详情", "学习", { selectedWordKey = null })
            } else if (studyTool != null) {
                com.jerreader.shared.ui.JerreaderNavigationBar(
                    title = if (studyTool == "sentences" && selectedSentence != null) "原句自测" else toolTitles[studyTool].orEmpty(),
                    backTitle = if (studyTool == "sentences" && selectedSentence != null) "原句练习卡" else "学习",
                    onBack = { if (studyTool == "sentences" && selectedSentence != null) selectedSentence = null else studyTool = null })
            } else Box(Modifier.fillMaxWidth().statusBarsPadding().height(44.dp).padding(horizontal = 20.dp)) {
                val barSize = with(androidx.compose.ui.platform.LocalDensity.current) { 17.dp.toSp() }
                Text("学习", fontSize = barSize, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center))
            }
        },
        bottomBar = bottomBar
    ) { padding ->
        if (selectedWordKey != null) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                val selected = words.firstOrNull { it.lookupKey == selectedWordKey }
                if (selected != null) WordRecordDetailScreen(selected, graph.learningRecords) { favorite ->
                    graph.learningRecords.setWordFavorite(selected.lookupKey, favorite)
                    if (!favorite && !selected.isInHistory) selectedWordKey = null
                } else Text("词条已不可用，请返回学习列表。", Modifier.padding(20.dp))
            }
        } else if (studyTool != null) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (studyTool) {
                    "review" -> WordReviewScreen(words, graph.learningRecords, graph.speechService)
                    "sentences" -> SentencePracticeScreen(translationFavorites, graph.learningRecords, selectedSentence) { selectedSentence = it }
                    "grammar" -> GrammarNotebookScreen(graph)
                    "growth" -> ReadingGrowthScreen(graph)
                }
            }
        } else {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // The translate page pads itself for the keyboard only by what
                // the tab bar does not already cover.
                .consumeWindowInsets(padding)
        ) {
            // iOS uses a pill segmented navigation here, not a chip row. The
            // record tabs run their control bar edge to edge, so the page
            // padding is applied per section instead of to the whole column.
            Box(Modifier.fillMaxWidth()) {
            JerreaderSegmentedNav(
                modifier = Modifier.padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 12.dp),
                options = listOf(
                    Triple(LearningTab.TRANSLATE, "翻译", JerreaderIcon.SPEECH_BUBBLE),
                    Triple(LearningTab.VOCABULARY, "生词本", JerreaderIcon.BOOKMARK_FILLED),
                    Triple(LearningTab.PRACTICE, "练习与工具", JerreaderIcon.REVIEW),
                    Triple(LearningTab.HISTORY, "历史", JerreaderIcon.CLOCK)
                ),
                selected = tab,
                onSelect = { tab = it; studyTool = null }
            )
            com.jerreader.shared.ui.JerreaderDivider(Modifier.align(Alignment.BottomCenter))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { sectionStates.SaveableStateProvider(tab.name) { when (tab) {
                LearningTab.PRACTICE -> LearningPracticeScreen(words.count { it.isFavorite }, translationFavorites.size, confusion) { studyTool = it }
                LearningTab.TRANSLATE -> TranslationTool(graph)
                LearningTab.VOCABULARY -> VocabularySection(
                    words = words.filter(WordLookupEntity::isFavorite),
                    translations = translationFavorites,
                    onOpenWord = { selectedWordKey = it.lookupKey },
                    onToggleWord = { key, favorite ->
                        graph.applicationScope.launch {
                            graph.learningRecords.setWordFavorite(key, favorite)
                        }
                    },
                    onDeleteTranslation = { key ->
                        graph.applicationScope.launch {
                            graph.learningRecords.deleteTranslationFavorite(key)
                        }
                    },
                    onExport = { format ->
                        val text = buildLearningExport(words, translationFavorites, format)
                        exportScope.launch {
                            val file = withContext(Dispatchers.IO) {
                                val extension = when (format) {
                                    LearningExportFormat.CSV -> "csv"
                                    LearningExportFormat.MARKDOWN -> "md"
                                    LearningExportFormat.ANKI_TSV -> "tsv"
                                }
                                File(
                                    context.cacheDir,
                                    "jerreader-learning-${System.currentTimeMillis()}.$extension"
                                ).apply { writeText(text, Charsets.UTF_8) }
                            }
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file
                            )
                            withContext(Dispatchers.Main.immediate) {
                                context.startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND)
                                            .setType(format.mimeType)
                                            .putExtra(
                                                Intent.EXTRA_SUBJECT,
                                                "Jerreader 学习导出·${format.title}"
                                            )
                                            .putExtra(Intent.EXTRA_STREAM, uri)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                        "导出学习记录"
                                    )
                                )
                            }
                        }
                    }
                )
                LearningTab.HISTORY -> HistorySection(
                    records = words.filter(WordLookupEntity::isInHistory),
                    onOpenWord = { selectedWordKey = it.lookupKey },
                    onFavorite = { key, favorite ->
                        graph.applicationScope.launch {
                            graph.learningRecords.setWordFavorite(key, favorite)
                        }
                    },
                    onDelete = { key ->
                        graph.applicationScope.launch { graph.learningRecords.removeFromHistory(key) }
                    },
                    onClear = {
                        graph.applicationScope.launch { graph.learningRecords.clearHistory() }
                    }
                )
            } }
            }
        }
    }
}
}

/**
 * The standalone translator, ported from the iOS `TranslateToolView`. It owns
 * its direction, mode and provider so experimenting here never rewrites the
 * settings the reader uses, and word mode follows a successful translation with
 * a real dictionary entry instead of leaving the user to look the word up again.
 */
@Composable
private fun TranslationTool(graph: AppGraph) {
    val interfacePreferences by graph.appSettings.preferences.collectAsStateWithLifecycle()
    val preferences by graph.translationSettings.preferences.collectAsStateWithLifecycle()
    var mode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(StandaloneTranslationMode.SENTENCE) }
    var providerMode by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(preferences.providerMode) }
    var sourceChoice by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(preferences.sourceChoice) }
    var targetLanguage by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(preferences.targetLanguage) }
    var input by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val inputCharacterCount = remember(input) { textCharacterCount(input.trim()) }
    var result by remember { mutableStateOf<TranslationResult?>(null) }
    var translatedSourceText by remember { mutableStateOf<String?>(null) }
    var explanation by remember { mutableStateOf<WordExplanation?>(null) }
    var dictionaryNote by remember { mutableStateOf<String?>(null) }
    var dictionaryLoading by remember { mutableStateOf(false) }
    var dictionaryInput by remember { mutableStateOf<StandaloneTranslationInput?>(null) }
    var analysis by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var analyzing by remember { mutableStateOf(false) }
    var savedFavorite by remember { mutableStateOf(false) }
    var addedToVocabulary by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val session = remember(scope) { TranslationToolSession(scope) }
    val wordLookupSession = remember(scope, graph) {
        StandaloneWordLookupSession(scope, graph.lexicalLookupService,
            record = { graph.learningRecords.recordLookup(it) })
    }
    val context = LocalContext.current

    val runWordLookup: (StandaloneTranslationInput) -> Unit = { prepared ->
        dictionaryInput = prepared
        dictionaryNote = null
        dictionaryLoading = true
        wordLookupSession.start(prepared, available = explanation,
            onFound = { explanation = it },
            onIssue = { dictionaryNote = it },
            onFinished = { dictionaryLoading = false })
    }

    val clearOutput = {
        session.invalidate()
        wordLookupSession.invalidate()
        dictionaryLoading = false
        dictionaryInput = null
        loading = false
        analyzing = false
        result = null
        translatedSourceText = null
        explanation = null
        dictionaryNote = null
        analysis = null
        savedFavorite = false
        addedToVocabulary = false
        copied = false
        status = null
    }

    val runTranslation: () -> Unit = {
        clearOutput()
        session.launch(
            timeoutMillis = 30_000,
            onTimeout = {
                status = if (providerMode == TranslationProviderMode.ON_DEVICE) {
                    "本机翻译等待超时，请检查离线语言包后重试。"
                } else {
                    "翻译等待超时，请检查网络后重试。"
                }
            },
            onFinished = { loading = false }
        ) {
            loading = true
            val prepared = runCatchingActive {
                StandaloneTranslationRequestPolicy.makeInput(
                    text = input,
                    sourceChoice = sourceChoice,
                    targetLanguage = targetLanguage,
                    mode = mode
                )
            }.getOrElse { error ->
                status = error.message ?: "请检查输入后重试。"
                return@launch
            }
            runCatchingActive {
                if (prepared.mode == StandaloneTranslationMode.WORD && prepared.sourceLanguage == LanguageCode.CHINESE_SIMPLIFIED &&
                    prepared.targetLanguage == LanguageCode.JAPANESE) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        graph.offlineDictionary.japaneseChinese.chinese(prepared.text)
                    }?.let {
                        return@runCatchingActive TranslationResult(it, prepared.sourceLanguage, prepared.targetLanguage, "中日词典 · 离线")
                    }
                }
                if (prepared.mode == StandaloneTranslationMode.WORD && prepared.sourceLanguage != LanguageCode.CHINESE_SIMPLIFIED &&
                    prepared.targetLanguage == LanguageCode.CHINESE_SIMPLIFIED) {
                    val local = try { graph.offlineDictionary.lookup(prepared.text, null, prepared.sourceLanguage, emptyList()) }
                    catch (_: com.jerreader.shared.lexical.LexicalLookupFailure.ResultNotFound) { null }
                    if (local != null) {
                        explanation = local
                        return@runCatchingActive TranslationResult(local.definitions.joinToString("；"), prepared.sourceLanguage,
                            LanguageCode.CHINESE_SIMPLIFIED, if (prepared.sourceLanguage == LanguageCode.JAPANESE) "中日词典 · 离线" else "英语词典 · 离线")
                    }
                }
                graph.cachedTranslationService.translate(
                    TranslationRequest(
                        text = prepared.text,
                        sourceLanguage = prepared.sourceLanguage,
                        targetLanguage = prepared.targetLanguage
                    ),
                    StandaloneTranslationOverride(
                        providerMode = providerMode,
                        promptTemplate = mode.promptTemplate(
                            preferences.translationPromptTemplate
                        )
                    )
                )
            }.onSuccess { translated ->
                result = translated
                translatedSourceText = prepared.text
                loading = false
                if (StandaloneLexicalLookupPolicy.supports(
                        prepared.mode,
                        prepared.sourceLanguage
                    )
                ) {
                    runWordLookup(prepared)
                }
            }.onFailure { status = it.message ?: "翻译失败，请重试。" }
        }
        Unit
    }

    val providers by graph.translationSettings.aiProviders.collectAsStateWithLifecycle()
    val wordMode = mode == StandaloneTranslationMode.WORD
    val noteStyle = MaterialTheme.typography.labelMedium
    val noteLine = with(LocalDensity.current) { noteStyle.lineHeight.toDp() }

    // The page fills the room between the section bar and the tab bar
    // exactly, as on iOS: the card takes whatever height is left over, and a
    // long result or dictionary entry scrolls inside the card, so the page
    // itself never moves. With the keyboard up, large text or a short screen
    // it scrolls as usual.
    OneScreenPage(
        tag = "standalone-translation-page",
        modifier = Modifier.imePadding(),
        horizontalPadding = 20.dp
    ) {
        Spacer(Modifier.height(16.dp))

        // Mode and service sit above the card on iOS, not inside it.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            JerreaderSegmentedPicker(
                options = StandaloneTranslationMode.entries.map { it to it.title },
                selected = mode,
                onSelect = {
                    if (it != mode) {
                        mode = it
                        clearOutput()
                    }
                }
            )
            JerreaderMenuField(
                label = "服务",
                icon = providerSymbol(providerMode),
                options = listOf(
                    TranslationProviderMode.ON_DEVICE to "本机翻译",
                    TranslationProviderMode.DIRECT_API to "AI 大模型",
                    TranslationProviderMode.BACKEND_PROXY to "AI 代理"
                ),
                selected = providerMode,
                onSelect = {
                    if (it != providerMode) {
                        providerMode = it
                        clearOutput()
                    }
                }
            )
        }

        JerreaderTranslatorCard(Modifier.padding(top = 14.dp).stretch().squeezes()) {
            // Spare height goes mostly to the result: the input grows a
            // little, the output takes the rest. When height is short, the
            // output gives way.
            FillColumn {
                JerreaderLanguageBar {
                    JerreaderInlineMenu(
                        options = TranslationSourceChoice.entries.map { it to sourceChoiceName(it) },
                        selected = sourceChoice,
                        onSelect = {
                            sourceChoice = it
                            clearOutput()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    JerreaderSwapButton(
                        enabled = sourceChoice.language != null,
                        onClick = onClick@{
                            val previousSource = sourceChoice.language ?: return@onClick
                            sourceChoice = when (targetLanguage) {
                                LanguageCode.CHINESE_SIMPLIFIED -> TranslationSourceChoice.CHINESE_SIMPLIFIED
                                LanguageCode.ENGLISH -> TranslationSourceChoice.ENGLISH
                                LanguageCode.JAPANESE -> TranslationSourceChoice.JAPANESE
                            }
                            targetLanguage = previousSource
                            // Swapping after a translation continues from the result,
                            // the way the iOS tool hands the译文 back as the new原文.
                            result?.let { input = it.translatedText }
                            clearOutput()
                        }
                    )
                    JerreaderInlineMenu(
                        options = LanguageCode.entries.map { it to languageName(it) },
                        selected = targetLanguage,
                        onSelect = {
                            targetLanguage = it
                            clearOutput()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                JerreaderDivider()

                // The area takes the height the card hands it; the count and
                // the translate button stay at its foot.
                Column(
                    modifier = Modifier
                        .stretch(30.dp)
                        .fillMaxWidth()
                        .heightIn(min = if (wordMode) 118.dp else 142.dp)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        if (input.isEmpty()) {
                            Text(
                                mode.placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = {
                                if (input != it) {
                                    input = it
                                    clearOutput()
                                }
                            },
                            // The field covers the whole area, so a tap on its
                            // empty part starts typing too.
                            modifier = Modifier.fillMaxSize(),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            singleLine = wordMode,
                            maxLines = if (wordMode) 1 else 10,
                            minLines = if (wordMode) 1 else 3
                        )
                    }
                    InputFooter(Modifier.fillMaxWidth()) {
                        Text(
                            "${inputCharacterCount} / ${groupedCount(mode.maximumCharacterCount)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (input.isNotEmpty()) {
                                TextButton(onClick = {
                                    input = ""
                                    clearOutput()
                                }) { Text("清空") }
                            }
                            TranslateActionButton(
                                loading = loading,
                                enabled = input.isNotBlank() &&
                                    inputCharacterCount <= mode.maximumCharacterCount && !loading,
                                onClick = runTranslation
                            )
                        }
                    }
                }
                if (inputCharacterCount > mode.maximumCharacterCount) {
                    Text(
                        "当前模式一次最多翻译 ${mode.maximumCharacterCount} 个字符，请缩短原文或切换模式。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
                JerreaderDivider(modifier = Modifier.padding(horizontal = 14.dp))

                Column(
                    modifier = Modifier
                        .stretch()
                        .squeezes(if (wordMode) 104.dp else 126.dp)
                        .fillMaxWidth()
                        .heightIn(min = if (wordMode) 104.dp else 126.dp)
                        .padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val translated = result
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        JerreaderGlyph(
                            icon = JerreaderIcon.SPEECH_BUBBLE,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            languageName(targetLanguage),
                            modifier = Modifier.padding(start = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (translated?.sourceLanguage == LanguageCode.JAPANESE || targetLanguage == LanguageCode.JAPANESE) {
                            TextButton(onClick = { graph.appSettings.updateJapaneseFurigana(!interfacePreferences.japaneseFuriganaEnabled) }) {
                                Text(if (interfacePreferences.japaneseFuriganaEnabled) "日语注音 ✓" else "日语注音", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (translated?.isFromCache == true) {
                            Text(
                                "缓存",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // A long result or dictionary entry scrolls here; the page
                    // stays put.
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        when {
                            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    loadingNote(providerMode),
                                    modifier = Modifier.padding(start = 10.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            translated != null -> {
                                if (interfacePreferences.japaneseFuriganaEnabled && translated.sourceLanguage == LanguageCode.JAPANESE) {
                                    Text("原文注音", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    JapaneseFuriganaText(translatedSourceText.orEmpty(), graph.offlineDictionary, Modifier.fillMaxWidth())
                                }
                                if (interfacePreferences.japaneseFuriganaEnabled && translated.targetLanguage == LanguageCode.JAPANESE)
                                    JapaneseFuriganaText(translated.translatedText, graph.offlineDictionary, Modifier.fillMaxWidth())
                                else Text(translated.translatedText, style = MaterialTheme.typography.bodyLarge)
                                val hasContextExplanation =
                                    graph.translationService is ContextExplanationService
                                if (mode == StandaloneTranslationMode.WORD || hasContextExplanation) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        if (mode == StandaloneTranslationMode.WORD) {
                                            JerreaderInlineAction(
                                                text = if (addedToVocabulary) "已加入生词本" else "加入生词本",
                                                icon = JerreaderIcon.BOOKMARK_OUTLINE,
                                                enabled = !addedToVocabulary,
                                                onClick = {
                                                    session.launch {
                                                        val sourceText = translatedSourceText
                                                            ?: return@launch
                                                        val entry = explanation ?: WordExplanation(
                                                            surfaceForm = sourceText,
                                                            lemma = null,
                                                            reading = null,
                                                            language = translated.sourceLanguage,
                                                            partOfSpeech = null,
                                                            definitions = listOf(translated.translatedText),
                                                            usageNote = "释义来自本次译文。",
                                                            providerIdentifier = translated.providerIdentifier
                                                        )
                                                        runCatchingActive {
                                                            graph.learningRecords.recordLookup(
                                                                entry,
                                                                favorite = true
                                                            )
                                                        }.onSuccess { addedToVocabulary = true }
                                                            .onFailure { error ->
                                                                status = "加入生词本失败：" +
                                                                    (error.message ?: "请稍后重试。")
                                                            }
                                                    }
                                                }
                                            )
                                        }
                                        if (hasContextExplanation) {
                                            JerreaderInlineAction(
                                                text = if (analyzing) "解析中…" else "AI 结构分析",
                                                icon = JerreaderIcon.TREND,
                                                enabled = !analyzing,
                                                onClick = {
                                                    val service = graph.translationService
                                                        as? ContextExplanationService
                                                    if (service != null) {
                                                        session.launch(
                                                            timeoutMillis = 70_000,
                                                            onTimeout = { status = "AI 解析等待超时，请稍后重试。" },
                                                            onFinished = { analyzing = false }
                                                        ) {
                                                            analyzing = true
                                                            runCatchingActive {
                                                                service.explain(
                                                                    ContextExplanationRequest(
                                                                        focusedText = translatedSourceText
                                                                            .orEmpty(),
                                                                        contextText = null,
                                                                        sourceLanguage =
                                                                            translated.sourceLanguage
                                                                    )
                                                                )
                                                            }.onSuccess { analysis = it.explanation }
                                                                .onFailure {
                                                                    status = it.message
                                                                        ?: "AI 解析不可用，请先配置 AI 服务。"
                                                                }
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                                analysis?.let {
                                    Text(
                                        "语法与结构分析",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(it, style = MaterialTheme.typography.bodyMedium)
                                    SaveGrammarNoteButton(com.jerreader.android.data.GrammarNoteEntity(
                                        title = com.jerreader.android.data.grammarNoteTitle(translatedSourceText ?: input), sentence = translatedSourceText ?: input,
                                        explanation = it, language = translated.sourceLanguage.tag), graph.learningRecords)
                                }
                            }
                            else -> Text(
                                "译文会显示在这里",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (dictionaryLoading) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("正在补充词典形、读音和释义…", modifier = Modifier.padding(start = 10.dp),
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }

                        dictionaryNote?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            dictionaryInput?.let { prepared ->
                                TextButton(onClick = { runWordLookup(prepared) }, enabled = !dictionaryLoading) {
                                    Text("重试词典查询")
                                }
                            }
                        }

                        explanation?.let { entry ->
                            JerreaderCard {
                                Text(
                                    "词典详情",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    entry.lemma ?: entry.surfaceForm,
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                                listOfNotNull(
                                    entry.reading?.let { "读音：$it" },
                                    entry.partOfSpeech?.let { "词性：$it" },
                                    entry.inflectionNote
                                ).forEach {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                entry.definitions.forEachIndexed { index, definition ->
                                    Text("${index + 1}. $definition", modifier = Modifier.padding(top = 4.dp))
                                }
                                entry.examples.forEach { example ->
                                    Text(
                                        example.sourceText,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                    example.translatedText?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    "词典结果已自动记入历史；收藏后会保留在生词本。",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 10.dp)
                                )
                            }
                        }
                    }

                    // Copy and favourite stay at the foot of the output, under
                    // a result of any length.
                    if (translated != null) {
                        FlowRow(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            JerreaderInlineAction(
                                text = if (copied) "已复制" else "复制",
                                icon = JerreaderIcon.COPY,
                                onClick = {
                                    val clipboard = context
                                        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(
                                        ClipData.newPlainText(
                                            "Jerreader 译文",
                                            translated.translatedText
                                        )
                                    )
                                    copied = true
                                }
                            )
                            JerreaderInlineAction(
                                text = if (savedFavorite) "已收藏" else "收藏",
                                icon = if (savedFavorite) {
                                    JerreaderIcon.STAR_FILLED
                                } else {
                                    JerreaderIcon.STAR_OUTLINE
                                },
                                enabled = !savedFavorite,
                                onClick = {
                                    session.launch {
                                        val sourceText = translatedSourceText ?: return@launch
                                        runCatchingActive {
                                            graph.learningRecords.saveTranslationFavorite(
                                                sourceText,
                                                translated
                                            )
                                        }.onSuccess { savedFavorite = true }
                                            .onFailure { error ->
                                                status = "收藏失败：${error.message ?: "请稍后重试。"}"
                                            }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        status?.let { JerreaderErrorCard(it, Modifier.padding(top = 12.dp)) }

        // On a short screen the note keeps one line and ends in an ellipsis.
        Row(
            modifier = Modifier.padding(top = 12.dp).shrinks(12.dp + noteLine),
            verticalAlignment = Alignment.Top
        ) {
            JerreaderGlyph(
                icon = providerSymbol(providerMode),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp).size(13.dp)
            )
            Text(
                providerNote(providerMode, providers),
                modifier = Modifier.padding(start = 6.dp),
                style = noteStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The count at the start and the actions at the end of one row; when that row
 * would be too wide, the count goes above the actions, which keep to the end.
 * This is the iOS tool's `ViewThatFits`. Its intrinsic height is the height it
 * is laid out at, so the one-screen stack never hands it too little. A
 * [FlowRow] holding a weighted item did not do that: it sized the item as if
 * both buttons wrapped onto lines of their own, found no room, and dropped
 * 清空 and 翻译 once a long dictionary entry took the spare height.
 */
@Composable
private fun InputFooter(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val policy = remember(density) {
        InputFooterPolicy(
            gap = with(density) { 10.dp.roundToPx() },
            stackGap = with(density) { 6.dp.roundToPx() }
        )
    }
    Layout(content = content, modifier = modifier, measurePolicy = policy)
}

/** Lays out exactly two children: the count, then the actions. */
private class InputFooterPolicy(private val gap: Int, private val stackGap: Int) : MeasurePolicy {
    private fun inline(countWidth: Int, actionsWidth: Int, width: Int) =
        countWidth + gap + actionsWidth <= width

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val count = measurables[0].measure(loose)
        val actions = measurables[1].measure(loose)
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            (count.width + gap + actions.width).coerceAtLeast(constraints.minWidth)
        }
        return if (inline(count.width, actions.width, width)) {
            val height = max(count.height, actions.height)
            layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
                count.place(0, (height - count.height) / 2)
                actions.place(width - actions.width, (height - actions.height) / 2)
            }
        } else {
            val height = count.height + stackGap + actions.height
            layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
                count.place(0, 0)
                actions.place(width - actions.width, count.height + stackGap)
            }
        }
    }

    private fun height(measurables: List<IntrinsicMeasurable>, width: Int): Int {
        val (count, actions) = measurables
        val countWidth = count.maxIntrinsicWidth(Constraints.Infinity)
        val actionsWidth = actions.maxIntrinsicWidth(Constraints.Infinity)
        return if (inline(countWidth, actionsWidth, width)) {
            max(count.maxIntrinsicHeight(countWidth), actions.maxIntrinsicHeight(actionsWidth))
        } else {
            count.maxIntrinsicHeight(width) + stackGap + actions.maxIntrinsicHeight(width)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        height(measurables, width)

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) =
        height(measurables, width)

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables.maxOf { it.minIntrinsicWidth(Constraints.Infinity) }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        measurables[0].maxIntrinsicWidth(Constraints.Infinity) + gap +
            measurables[1].maxIntrinsicWidth(Constraints.Infinity)
}

/** The filled 「翻译」 action inside the card, matching the iOS button. */
@Composable
private fun TranslateActionButton(loading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (enabled || loading) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        },
        onClick = onClick,
        enabled = enabled
    ) {
        Row(
            modifier = Modifier.heightIn(min = 44.dp).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.size(8.dp))
            }
            Text(
                if (loading) "翻译中" else "翻译",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}

/** `0 / 2,000`, the way the iOS counter groups its limit. */
private fun groupedCount(value: Int): String =
    value.toString().reversed().chunked(3).joinToString(",").reversed()

private fun providerSymbol(mode: TranslationProviderMode): JerreaderIcon = when (mode) {
    TranslationProviderMode.ON_DEVICE -> JerreaderIcon.LOCK
    TranslationProviderMode.DIRECT_API -> JerreaderIcon.SLIDERS
    TranslationProviderMode.BACKEND_PROXY -> JerreaderIcon.SHIELD
}

private fun loadingNote(mode: TranslationProviderMode): String = when (mode) {
    TranslationProviderMode.ON_DEVICE -> "正在使用本机翻译…"
    else -> "正在请求 AI 翻译…"
}

/** The line under the card, worded as on iOS; AI 大模型 names the provider and model in use. */
private fun providerNote(mode: TranslationProviderMode, providers: AIProviderSettings): String = when (mode) {
    TranslationProviderMode.ON_DEVICE ->
        "基础查词无需联网或 API Key。句段翻译请先在“设置 → 翻译与 AI”准备离线语言包。"
    TranslationProviderMode.DIRECT_API -> {
        val entry = providers.active
        val model = entry.model.trim()
        val name = if (model.isEmpty()) entry.displayName else "${entry.displayName} · $model"
        "AI 大模型 · $name；可在「设置 → AI 大模型」换模型或添加供应商。"
    }
    TranslationProviderMode.BACKEND_PROXY ->
        "AI 代理；使用你在「设置 → 翻译与 AI」中配置的 HTTPS 服务。"
}

private fun sourceChoiceName(choice: TranslationSourceChoice): String = when (choice) {
    TranslationSourceChoice.AUTOMATIC -> "自动识别"
    TranslationSourceChoice.JAPANESE -> "日语"
    TranslationSourceChoice.ENGLISH -> "英语"
    // iOS names it like the target menu does (TranslationSourceLanguageChoice.title).
    TranslationSourceChoice.CHINESE_SIMPLIFIED -> "简体中文"
}


/** Same tappable detail row on both platforms; editing actions stay out of the row. */
@Composable
private fun LearningWordCollectionCard(record: WordLookupEntity, now: Long, onOpen: () -> Unit,
    onFavorite: () -> Unit, onDelete: (() -> Unit)? = null) {
    var menuOpen by remember(record.lookupKey) { mutableStateOf(false) }
    Box {
        JerreaderRecordCard(onClick = onOpen, onLongClick = { menuOpen = true }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JerreaderWordRecordRow(surfaceForm = record.surfaceForm, lemma = record.lemma, reading = record.reading,
                    definitions = record.definitionsText.split(LearningRecordRepository.DEFINITION_SEPARATOR).joinToString("；"),
                    languageName = languageTagName(record.language), partOfSpeech = record.partOfSpeech,
                    trailingNote = JerreaderRelativeTime.format(record.lastLookedUpAtEpochMillis, now),
                    modifier = Modifier.weight(1f), trailing = {
                        if (record.isFavorite) JerreaderGlyph(JerreaderIcon.STAR_FILLED,
                            MaterialTheme.colorScheme.primary, Modifier.size(18.dp))
                    })
                JerreaderGlyph(JerreaderIcon.CHEVRON_FORWARD,
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f), Modifier.size(13.dp))
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text(if (record.isFavorite) "取消收藏" else "收藏词语") }, onClick = {
                menuOpen = false; onFavorite()
            })
            if (onDelete != null) DropdownMenuItem(text = { Text("删除查词记录") }, onClick = {
                menuOpen = false; onDelete()
            })
        }
    }
}

@Composable
private fun VocabularySection(
    words: List<WordLookupEntity>,
    translations: List<TranslationFavoriteEntity>,
    onOpenWord: (WordLookupEntity) -> Unit,
    onToggleWord: (String, Boolean) -> Unit,
    onDeleteTranslation: (String) -> Unit,
    onExport: (LearningExportFormat) -> Unit
) {
    var search by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val term = search.trim()
    val visibleWords = remember(words, term) { if (term.isEmpty()) words else words.filter { record ->
        record.surfaceForm.contains(term, ignoreCase = true) ||
            record.lemma?.contains(term, ignoreCase = true) == true ||
            record.reading?.contains(term, ignoreCase = true) == true ||
            record.definitionsText.contains(term, ignoreCase = true)
    } }
    val visibleTranslations = remember(translations, term) { if (term.isEmpty()) translations else translations.filter { record ->
        record.sourceText.contains(term, ignoreCase = true) ||
            record.translatedText.contains(term, ignoreCase = true) ||
            record.bookTitle?.contains(term, ignoreCase = true) == true
    } }
    val accent = LocalJerreaderColors.current.accent
    val now = rememberNowMillis()
    var exportMenuOpen by remember { mutableStateOf(false) }
    LearningCollectionTypography {
    Column(Modifier.fillMaxSize()) {
        JerreaderCollectionControls {
            JerreaderSearchField(
                value = search,
                onValueChange = { search = it },
                placeholder = "搜索词语、原文或译文",
                modifier = Modifier.weight(1f)
            )
            JerreaderCountBadge(count = words.size + translations.size)
            Box {
                JerreaderToolbarIconButton(
                    icon = JerreaderIcon.SHARE,
                    label = "导出生词与译文收藏",
                    enabled = words.isNotEmpty() || translations.isNotEmpty(),
                    onClick = { exportMenuOpen = true }
                )
                DropdownMenu(
                    expanded = exportMenuOpen,
                    onDismissRequest = { exportMenuOpen = false }
                ) {
                    LearningExportFormat.entries.forEach { format ->
                        DropdownMenuItem(
                            text = { Text("导出 ${format.title}") },
                            onClick = {
                                exportMenuOpen = false
                                onExport(format)
                            }
                        )
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (visibleWords.isEmpty() && visibleTranslations.isEmpty()) { item {
            JerreaderEmptyState(
                modifier = Modifier.fillParentMaxHeight(),
                title = if (term.isEmpty()) "收藏还是空的" else "没有匹配内容",
                message = if (term.isEmpty()) {
                    "在翻译的词语模式收藏词典结果，或在阅读中的译文卡片点按星标，内容就会出现在这里。"
                } else {
                    "请尝试搜索其他词语、原文、译文或书名。"
                },
                icon = if (term.isEmpty()) JerreaderIcon.BOOKMARK_OUTLINE else JerreaderIcon.SEARCH
            )
        } }
        if (visibleWords.isNotEmpty()) {
            item {
            JerreaderCollectionSectionHeader(
                title = "收藏词语",
                detail = "词条收藏保存在本机，清空查词历史不会删除它们。",
                leading = true
            )
            }
            items(visibleWords, key = { "word:${it.lookupKey}" }, contentType = { "word" }) { record ->
                LearningWordCollectionCard(record, now, onOpen = { onOpenWord(record) },
                    onFavorite = { onToggleWord(record.lookupKey, false) })
            }
        }
        if (visibleTranslations.isNotEmpty()) {
            item {
            JerreaderCollectionSectionHeader(
                title = "收藏译文",
                detail = "阅读中点按译文卡片上的星标即可保存。",
                leading = visibleWords.isEmpty()
            )
            }
            items(visibleTranslations, key = { "translation:${it.favoriteKey}" }, contentType = { "translation" }) { favorite ->
                var menuOpen by remember(favorite.favoriteKey) { mutableStateOf(false) }
                Box {
                    JerreaderRecordCard(onLongClick = { menuOpen = true }) {
                        JerreaderTranslationFavoriteRow(
                            languagePair = "${languageTagName(favorite.sourceLanguage)} → ${languageTagName(favorite.targetLanguage)}",
                            translatedText = favorite.translatedText, sourceText = favorite.sourceText,
                            bookTitle = favorite.bookTitle,
                            trailingNote = java.text.SimpleDateFormat("M月d日", java.util.Locale.SIMPLIFIED_CHINESE)
                                .format(java.util.Date(favorite.updatedAtEpochMillis)))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("取消收藏") }, onClick = {
                            menuOpen = false; onDeleteTranslation(favorite.favoriteKey)
                        })
                    }
                }
            }
        }
        }
    }
    }
}

/**
 * A clock that only ticks once a minute. The relative-time labels never need
 * finer resolution than that, and recomposing a whole list every frame to
 * redraw "3 分钟前" would be pure waste.
 */
@Composable
private fun rememberNowMillis(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private fun buildLearningExport(
    words: List<WordLookupEntity>,
    translations: List<TranslationFavoriteEntity>,
    format: LearningExportFormat
): String = when (format) {
    LearningExportFormat.MARKDOWN -> buildString {
    appendLine("# Jerreader 学习记录")
    appendLine()
    appendLine("## 生词")
    words.filter(WordLookupEntity::isFavorite).forEach { record ->
        appendLine("- ${record.lemma ?: record.surfaceForm}：${record.definitionsText.replace(LearningRecordRepository.DEFINITION_SEPARATOR, "；")}")
        record.sentenceContext?.let { appendLine("  - 语境：$it") }
        record.aiAnalysis?.let { appendLine("  - AI 解析：$it") }
    }
    appendLine()
    appendLine("## 收藏译文")
    translations.forEach { record ->
        appendLine("- 原文：${record.sourceText}")
        appendLine("  - 译文：${record.translatedText}")
    }
    }
    LearningExportFormat.CSV -> buildString {
        appendLine("type,front,back,reading,context,book")
        words.filter(WordLookupEntity::isFavorite).forEach { record ->
            appendLine(
                listOf(
                    "word",
                    record.lemma ?: record.surfaceForm,
                    record.definitionsText.replace(LearningRecordRepository.DEFINITION_SEPARATOR, "；"),
                    record.reading.orEmpty(),
                    record.sentenceContext.orEmpty(),
                    record.sourceBookTitle.orEmpty()
                ).joinToString(",", transform = ::csvField)
            )
        }
        translations.forEach { record ->
            appendLine(
                listOf("translation", record.sourceText, record.translatedText, "", "", record.bookTitle.orEmpty())
                    .joinToString(",", transform = ::csvField)
            )
        }
    }
    LearningExportFormat.ANKI_TSV -> buildString {
        words.filter(WordLookupEntity::isFavorite).forEach { record ->
            appendLine(
                listOf(
                    record.lemma ?: record.surfaceForm,
                    record.definitionsText.replace(LearningRecordRepository.DEFINITION_SEPARATOR, "；"),
                    record.reading.orEmpty(),
                    "Jerreader 生词"
                ).joinToString("\t", transform = ::tsvField)
            )
        }
        translations.forEach { record ->
            appendLine(
                listOf(record.sourceText, record.translatedText, "", "Jerreader 译文收藏")
                    .joinToString("\t", transform = ::tsvField)
            )
        }
    }
}

private fun csvField(value: String): String = "\"${value.replace("\"", "\"\"")}\""
private fun tsvField(value: String): String = value.replace(Regex("[\\t\\r\\n]+"), " ")

@Composable
private fun HistorySection(
    records: List<WordLookupEntity>,
    onOpenWord: (WordLookupEntity) -> Unit,
    onFavorite: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit
) {
    var search by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val term = search.trim()
    val visibleRecords = remember(records, term) { if (term.isEmpty()) records else records.filter { record ->
        record.surfaceForm.contains(term, ignoreCase = true) ||
            record.lemma?.contains(term, ignoreCase = true) == true ||
            record.reading?.contains(term, ignoreCase = true) == true ||
            record.definitionsText.contains(term, ignoreCase = true) ||
            record.sentenceContext?.contains(term, ignoreCase = true) == true
    } }
    val colors = LocalJerreaderColors.current
    val now = rememberNowMillis()
    var confirmingClear by remember { mutableStateOf(false) }
    LearningCollectionTypography {
    Column(Modifier.fillMaxSize()) {
        JerreaderCollectionControls {
            JerreaderSearchField(
                value = search,
                onValueChange = { search = it },
                placeholder = "搜索查词历史",
                modifier = Modifier.weight(1f)
            )
            JerreaderCountBadge(count = records.size)
            JerreaderToolbarIconButton(
                icon = JerreaderIcon.TRASH,
                label = "清空查词历史",
                enabled = records.isNotEmpty(),
                onClick = { confirmingClear = true }
            )
        }
        LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (visibleRecords.isEmpty()) { item {
            JerreaderEmptyState(
                modifier = Modifier.fillParentMaxHeight(),
                title = if (term.isEmpty()) "还没有查词记录" else "没有匹配记录",
                message = if (term.isEmpty()) {
                    "在“翻译”的词语模式完成一次词典查询后，结果会自动保存在这里。"
                } else {
                    "请尝试其他词语、读音、释义或上下文。"
                },
                icon = if (term.isEmpty()) JerreaderIcon.CLOCK else JerreaderIcon.SEARCH
            )
        } } else {
            item {
            JerreaderCollectionSectionHeader(
                title = "最近查询",
                detail = "共 ${records.size} 个词条，按最近查询时间排列。",
                leading = true
            )
            }
            items(visibleRecords, key = { it.lookupKey }, contentType = { "history" }) { record ->
                LearningWordCollectionCard(record, now, onOpen = { onOpenWord(record) },
                    onFavorite = { onFavorite(record.lookupKey, !record.isFavorite) },
                    onDelete = { onDelete(record.lookupKey) })
            }
        }
        }
    }
    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text("清空查词历史？") },
            text = { Text("未收藏的记录会被删除；已收藏词条仍会保留在生词本中。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = false
                    onClear()
                }) { Text("清空历史") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) { Text("取消") }
            }
        )
    }
    }
}

/** The record tables store a BCP-47 tag; iOS shows the language's own name. */
private fun languageTagName(tag: String): String =
    LanguageCode.fromTag(tag)?.let(::languageName) ?: tag

private fun languageName(language: LanguageCode): String = when (language) {
    LanguageCode.CHINESE_SIMPLIFIED -> "简体中文"
    LanguageCode.ENGLISH -> "英语"
    LanguageCode.JAPANESE -> "日语"
}
