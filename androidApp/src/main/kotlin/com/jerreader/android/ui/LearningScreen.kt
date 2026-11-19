package com.jerreader.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jerreader.android.AppGraph
import com.jerreader.android.data.TranslationFavoriteEntity
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.android.learning.LearningRecordRepository
import com.jerreader.android.translation.AIProviderSettings
import com.jerreader.android.translation.StandaloneTranslationOverride
import com.jerreader.android.translation.StandaloneWordLookupSession
import com.jerreader.android.translation.TranslationToolSession
import com.jerreader.android.translation.runCatchingActive
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.WordExplanation
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ContextExplanationService
import com.jerreader.shared.translation.StandaloneLexicalLookupPolicy
import com.jerreader.shared.translation.StandaloneTranslationInput
import com.jerreader.shared.translation.StandaloneTranslationMode
import com.jerreader.shared.translation.StandaloneTranslationRequestPolicy
import com.jerreader.shared.translation.TranslationProviderMode
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationResult
import com.jerreader.shared.translation.TranslationSourceChoice
import com.jerreader.shared.translation.textCharacterCount
import com.jerreader.shared.ui.JerreaderCacheChip
import com.jerreader.shared.ui.JerreaderCollectionControls
import com.jerreader.shared.ui.JerreaderCountBadge
import com.jerreader.shared.ui.JerreaderEmptyState
import com.jerreader.shared.ui.JerreaderErrorCard
import com.jerreader.shared.ui.JerreaderFuriganaToggle
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderInlineAction
import com.jerreader.shared.ui.JerreaderInlineMenu
import com.jerreader.shared.ui.JerreaderLanguageBar
import com.jerreader.shared.ui.JerreaderLearningDayHeading
import com.jerreader.shared.ui.JerreaderLearningLegend
import com.jerreader.shared.ui.JerreaderLearningListHeading
import com.jerreader.shared.ui.JerreaderLearningMotion
import com.jerreader.shared.ui.JerreaderLearningSectionHeading
import com.jerreader.shared.ui.JerreaderLearningType
import com.jerreader.shared.ui.JerreaderMenuField
import com.jerreader.shared.ui.JerreaderNavigationBar
import com.jerreader.shared.ui.JerreaderPillOption
import com.jerreader.shared.ui.JerreaderPillPicker
import com.jerreader.shared.ui.JerreaderRadius
import com.jerreader.shared.ui.JerreaderRecordCard
import com.jerreader.shared.ui.JerreaderRelativeTime
import com.jerreader.shared.ui.JerreaderSearchField
import com.jerreader.shared.ui.JerreaderSegmentedPicker
import com.jerreader.shared.ui.JerreaderSwapButton
import com.jerreader.shared.ui.JerreaderToolbarIconButton
import com.jerreader.shared.ui.JerreaderTranslationFavoriteRow
import com.jerreader.shared.ui.JerreaderTranslationResultHeader
import com.jerreader.shared.ui.JerreaderTranslatorCard
import com.jerreader.shared.ui.JerreaderWordRecordRow
import com.jerreader.shared.ui.jerreaderCanvasBackground
import com.jerreader.shared.ui.jerreaderPalette
import com.jerreader.shared.ui.jerreaderTabularDigits
import com.jerreader.shared.ui.plainSecondary
import com.jerreader.shared.ui.plainTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

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

/** [menuTitle] and [icon] are the iOS export menu's (`tablecells` and `rectangle.stack` have no Android glyph). */
private enum class LearningExportFormat(
    val title: String,
    val mimeType: String,
    val menuTitle: String,
    val icon: JerreaderIcon
) {
    CSV("CSV", "text/csv", "CSV 表格", JerreaderIcon.GRID),
    MARKDOWN("Markdown", "text/markdown", "Markdown 笔记", JerreaderIcon.DOC_PLAIN),
    ANKI_TSV("Anki TSV", "text/tab-separated-values", "Anki 制卡文件", JerreaderIcon.REVIEW)
}

/** iOS stops the section bar's type at xLarge, so the bar stays 72 tall. */
private const val LEARNING_BAR_FONT_SCALE_CAP = 1.15f

@Composable
fun LearningScreen(graph: AppGraph, initialSection: String = "practice", bottomBar: @Composable () -> Unit) {
    val sectionStates = rememberSaveableStateHolder()
    var studyTool by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedSentence by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedWordKey by rememberSaveable { mutableStateOf<String?>(null) }
    val toolTitles = linkedMapOf("review" to "生词复习", "sentences" to "原句练习卡", "grammar" to "语法知识库", "growth" to "阅读成长报告")
    var tab by rememberSaveable { mutableStateOf(if (initialSection == "translate") LearningTab.TRANSLATE else LearningTab.PRACTICE) }
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
    // The 今日练习 ring reads the same schedules the review page does.
    val reviewInputs = remember(words) {
        words.filter(WordLookupEntity::isFavorite).map { it.lookupKey to it.reviewJson }
    }
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    Scaffold(
        // The hub and its tools sit on the canvas, as on iOS.
        modifier = Modifier.jerreaderCanvasBackground(),
        containerColor = Color.Transparent,
        topBar = {
            if (selectedWordKey != null) {
                JerreaderNavigationBar("词条详情", "学习", { selectedWordKey = null })
            } else if (studyTool != null) {
                JerreaderNavigationBar(
                    title = if (studyTool == "sentences" && selectedSentence != null) "原句自测" else toolTitles[studyTool].orEmpty(),
                    backTitle = if (studyTool == "sentences" && selectedSentence != null) "原句练习卡" else "学习",
                    onBack = { if (studyTool == "sentences" && selectedSentence != null) selectedSentence = null else studyTool = null })
            } else Box(Modifier.fillMaxWidth().statusBarsPadding().height(44.dp).padding(horizontal = 20.dp)) {
                val barSize = with(LocalDensity.current) { 17.dp.toSp() }
                Text("学习", fontSize = barSize, fontWeight = FontWeight.SemiBold,
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
                LearningSectionBar(
                    selected = tab,
                    onSelect = {
                        if (it != tab) {
                            // iOS dismisses the keyboard before the section changes.
                            focusManager.clearFocus()
                            tab = it
                            studyTool = null
                        }
                    }
                )
                // The new section fades in and grows from 98.8% at its top edge
                // with the state-change spring, as the iOS section transition.
                AnimatedContent(
                    targetState = tab,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    transitionSpec = {
                        val origin = TransformOrigin(0.5f, 0f)
                        (fadeIn(JerreaderLearningMotion.stateChange()) +
                            scaleIn(JerreaderLearningMotion.stateChange(), initialScale = 0.988f, transformOrigin = origin))
                            .togetherWith(
                                fadeOut(JerreaderLearningMotion.stateChange()) +
                                    scaleOut(JerreaderLearningMotion.stateChange(), targetScale = 0.988f, transformOrigin = origin)
                            )
                            .using(SizeTransform(clip = false))
                    },
                    label = "learning-section"
                ) { section ->
                    sectionStates.SaveableStateProvider(section.name) {
                        when (section) {
                            LearningTab.PRACTICE -> LearningPracticeScreen(
                                wordCount = words.count { it.isFavorite },
                                sentenceCount = translationFavorites.size,
                                confusion = confusion,
                                reviewInputs = reviewInputs,
                                onOpen = { studyTool = it }
                            )
                            LearningTab.TRANSLATE -> {
                                // As on iOS: the hint shows whenever the active AI
                                // provider is the ChatGPT plan, signed in or not.
                                val aiProviders by graph.translationSettings.aiProviders.collectAsStateWithLifecycle()
                                TranslationTool(
                                    graph,
                                    showsChatGPTPlanIndicator = aiProviders.active.kind.usesAccountSignIn
                                )
                            }
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
                        }
                    }
                }
            }
        }
    }
}

/**
 * The iOS hub's section bar: a stacked pill picker (min 44) padded h20, top 8,
 * bottom 12, at most 680 wide, over a 0.5 `line` hairline. Its type stops
 * growing at about xLarge, so the bar keeps one height.
 */
@Composable
private fun LearningSectionBar(
    selected: LearningTab,
    onSelect: (LearningTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val capped = remember(density) { Density(density.density, min(density.fontScale, LEARNING_BAR_FONT_SCALE_CAP)) }
    CompositionLocalProvider(LocalDensity provides capped) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            JerreaderPillPicker(
                options = listOf(
                    JerreaderPillOption(LearningTab.TRANSLATE, LearningTab.TRANSLATE.title, JerreaderIcon.SPEECH_BUBBLE),
                    JerreaderPillOption(LearningTab.VOCABULARY, LearningTab.VOCABULARY.title, JerreaderIcon.BOOKMARK_FILLED),
                    JerreaderPillOption(LearningTab.PRACTICE, LearningTab.PRACTICE.title, JerreaderIcon.REVIEW),
                    JerreaderPillOption(LearningTab.HISTORY, LearningTab.HISTORY.title, JerreaderIcon.CLOCK)
                ),
                selected = selected,
                onSelect = onSelect,
                modifier = Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 12.dp),
                stacked = true,
                minHeight = 44.dp
            )
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(jerreaderPalette.line)
            )
        }
    }
}

/**
 * The standalone translator, ported from the iOS `TranslateToolView`. It owns
 * its direction, mode and provider so experimenting here never rewrites the
 * settings the reader uses, and word mode follows a successful translation with
 * a real dictionary entry instead of leaving the user to look the word up again.
 *
 * [showsChatGPTPlanIndicator]: true while the active AI provider is the ChatGPT
 * plan (`providers.active.kind.usesAccountSignIn`). The result header then shows
 * the plan indicator whenever this page translates with AI 大模型, as on iOS.
 */
@Composable
private fun TranslationTool(graph: AppGraph, showsChatGPTPlanIndicator: Boolean = false) {
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
    }

    val providers by graph.translationSettings.aiProviders.collectAsStateWithLifecycle()
    val wordMode = mode == StandaloneTranslationMode.WORD
    val haptics = LocalHapticFeedback.current
    val providerOptions = listOf(
        TranslationProviderMode.ON_DEVICE to "本机翻译",
        TranslationProviderMode.DIRECT_API to "AI 大模型",
        TranslationProviderMode.BACKEND_PROXY to "AI 代理"
    )
    val translated = result
    val output = when {
        loading -> TranslationOutput.Loading
        translated != null -> TranslationOutput.Shown(translated, translatedSourceText)
        else -> TranslationOutput.Placeholder
    }

    LearningTypography {
    val palette = jerreaderPalette
    val secondary = MaterialTheme.colorScheme.plainSecondary
    val noteStyle = JerreaderLearningType.caption
    val noteLine = with(LocalDensity.current) { noteStyle.lineHeight.toDp() }
    val resultLeast = if (wordMode) 104.dp else 126.dp

    // The page fills the room between the section bar and the tab bar
    // exactly, as on iOS: the result card takes whatever height is left over,
    // and a long result or dictionary entry scrolls inside it, so the page
    // itself never moves. With the keyboard up, large text or a short screen
    // it scrolls as usual.
    OneScreenPage(
        tag = "standalone-translation-page",
        modifier = Modifier.imePadding(),
        horizontalPadding = 20.dp
    ) {
        Spacer(Modifier.height(12.dp))

        // Mode and service sit above the cards on iOS.
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
                options = providerOptions,
                selected = providerMode,
                onSelect = {
                    if (it != providerMode) {
                        providerMode = it
                        clearOutput()
                    }
                },
                accessibilityLabel = "翻译服务：" + providerOptions.first { it.first == providerMode }.second
            )
        }

        // Three paper cards 8 apart: languages, input, result.
        JerreaderLanguageBar(Modifier.padding(top = 12.dp)) {
            JerreaderInlineMenu(
                options = TranslationSourceChoice.entries.map { it to sourceChoiceName(it) },
                selected = sourceChoice,
                onSelect = {
                    sourceChoice = it
                    clearOutput()
                },
                modifier = Modifier.weight(1f),
                accessibilityLabel = "原文语言：${sourceChoiceName(sourceChoice)}"
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
                modifier = Modifier.weight(1f),
                accessibilityLabel = "译文语言：${languageName(targetLanguage)}"
            )
        }

        // The input grows by at most 30; the result card takes the rest of
        // the spare height. The count and the buttons stay at the input's foot.
        JerreaderTranslatorCard(Modifier.padding(top = 8.dp).stretch(30.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .heightIn(min = if (wordMode) 118.dp else 142.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (input.isEmpty()) {
                        Text(
                            mode.placeholder,
                            style = JerreaderLearningType.body,
                            color = palette.secondaryText.copy(alpha = 0.3f)
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
                        textStyle = JerreaderLearningType.body.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(palette.accent),
                        singleLine = wordMode,
                        maxLines = if (wordMode) 1 else 10,
                        minLines = if (wordMode) 1 else 3
                    )
                }
                InputFooter(Modifier.fillMaxWidth()) {
                    Text(
                        "${inputCharacterCount} / ${groupedCount(mode.maximumCharacterCount)}",
                        style = JerreaderLearningType.caption.jerreaderTabularDigits(),
                        color = if (inputCharacterCount > mode.maximumCharacterCount) palette.vermilion else secondary
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (input.isNotEmpty()) {
                            LearningButton(onClick = {
                                input = ""
                                clearOutput()
                            }) { Text("清空", style = JerreaderLearningType.subheadline) }
                        }
                        // The screen's one filled accent action.
                        LearningButton(
                            onClick = runTranslation,
                            enabled = input.isNotBlank() &&
                                inputCharacterCount <= mode.maximumCharacterCount && !loading,
                            kind = LearningButtonKind.FILLED
                        ) {
                            if (loading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    color = palette.onAccent,
                                    strokeWidth = 2.dp
                                )
                            }
                            Text(if (loading) "翻译中" else "翻译")
                        }
                    }
                }
            }
            if (inputCharacterCount > mode.maximumCharacterCount) {
                Text(
                    "当前模式一次最多翻译 ${mode.maximumCharacterCount} 个字符，请缩短原文或切换模式。",
                    color = palette.vermilion,
                    style = JerreaderLearningType.footnote,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                )
            }
        }

        JerreaderTranslatorCard(Modifier.padding(top = 8.dp).stretch().squeezes(8.dp + resultLeast)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .heightIn(min = resultLeast)
                    .padding(top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // One line at least 28 high whichever of its parts show, so
                // nothing under it moves.
                JerreaderTranslationResultHeader(
                    languageLabel = languageName(targetLanguage),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    planIndicator = {
                        if (showsChatGPTPlanIndicator && providerMode == TranslationProviderMode.DIRECT_API) {
                            ChatGPTPlanIndicator()
                        }
                    },
                    accessory = {
                        if (translated?.sourceLanguage == LanguageCode.JAPANESE || targetLanguage == LanguageCode.JAPANESE) {
                            JerreaderFuriganaToggle(
                                checked = interfacePreferences.japaneseFuriganaEnabled,
                                onCheckedChange = { graph.appSettings.updateJapaneseFurigana(it) }
                            )
                        }
                    }
                )

                // A long result or dictionary entry scrolls here; the page
                // stays put. The scroll area spans the card's width so the
                // dictionary card's shadow is not cut off at its sides.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Crossfade(
                        targetState = output,
                        animationSpec = JerreaderLearningMotion.stateChange(),
                        label = "translation-output"
                    ) { shown ->
                        when (shown) {
                            TranslationOutput.Loading -> Row(
                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = palette.accent,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    loadingNote(providerMode, wordMode),
                                    style = JerreaderLearningType.subheadline,
                                    color = secondary
                                )
                            }
                            TranslationOutput.Placeholder -> Text(
                                "译文会显示在这里",
                                style = JerreaderLearningType.body,
                                color = secondary
                            )
                            is TranslationOutput.Shown -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                val furigana = interfacePreferences.japaneseFuriganaEnabled
                                if (furigana && shown.result.sourceLanguage == LanguageCode.JAPANESE) {
                                    Text("原文注音", style = JerreaderLearningType.caption, color = secondary)
                                    JapaneseFuriganaText(shown.sourceText.orEmpty(), graph.offlineDictionary, Modifier.fillMaxWidth())
                                }
                                if (furigana && shown.result.targetLanguage == LanguageCode.JAPANESE) {
                                    JapaneseFuriganaText(shown.result.translatedText, graph.offlineDictionary, Modifier.fillMaxWidth())
                                } else {
                                    SelectionContainer {
                                        Text(shown.result.translatedText, style = JerreaderLearningType.body)
                                    }
                                }
                            }
                        }
                    }

                    if (translated != null && !loading) {
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
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("语法与结构分析", style = JerreaderLearningType.headline)
                                SelectionContainer { Text(it, style = JerreaderLearningType.subheadline) }
                                SaveGrammarNoteButton(com.jerreader.android.data.GrammarNoteEntity(
                                    title = com.jerreader.android.data.grammarNoteTitle(translatedSourceText ?: input), sentence = translatedSourceText ?: input,
                                    explanation = it, language = translated.sourceLanguage.tag), graph.learningRecords)
                            }
                        }
                    }

                    if (dictionaryLoading) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    palette.mutedSurface.copy(alpha = palette.mutedSurface.alpha * 0.72f),
                                    RoundedCornerShape(14.dp)
                                )
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = palette.accent,
                                strokeWidth = 2.dp
                            )
                            Text("正在补充词典形、读音和释义…", style = JerreaderLearningType.subheadline, color = secondary)
                        }
                    }

                    dictionaryNote?.let { note ->
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                JerreaderGlyph(JerreaderIcon.BOOK_CLOSED, secondary, Modifier.size(15.dp))
                                Text(note, style = JerreaderLearningType.subheadline, color = secondary)
                            }
                            dictionaryInput?.let { prepared ->
                                LearningButton(onClick = { runWordLookup(prepared) }, enabled = !dictionaryLoading) {
                                    Text(
                                        "重试词典查询",
                                        style = JerreaderLearningType.subheadline.copy(fontWeight = FontWeight.SemiBold)
                                    )
                                }
                            }
                        }
                    }

                    // The same word card as 词条详情 (iOS `WordRecordCard`).
                    explanation?.let { entry ->
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                JerreaderGlyph(JerreaderIcon.DICTIONARY, palette.accent, Modifier.size(17.dp))
                                Text("词典详情", style = JerreaderLearningType.headline, color = palette.accent)
                            }
                            WordEntryCard(
                                surfaceForm = entry.surfaceForm,
                                languageName = languageName(entry.language),
                                eyebrow = wordEntryEyebrow(entry.usageNote),
                                definitions = entry.definitions,
                                lemma = entry.lemma,
                                reading = entry.reading,
                                partOfSpeech = entry.partOfSpeech
                            ) {
                                entry.usageNote?.takeIf(String::isNotBlank)?.let { WordEntrySection("来源与用法", JerreaderIcon.INFO, it) }
                                entry.inflectionNote?.takeIf(String::isNotBlank)?.let { WordEntrySection("活用说明", JerreaderIcon.SWAP, it) }
                                if (entry.examples.isNotEmpty()) WordEntryExamples(entry.examples)
                            }
                            Text(
                                "词典结果已自动记入历史；收藏后会保留在生词本。",
                                style = JerreaderLearningType.caption,
                                color = secondary,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }

                // Copy and favourite stay at the foot of the output, under a
                // result of any length; 缓存 is a chip at the trailing end.
                if (translated != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FlowRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            JerreaderInlineAction(
                                text = if (copied) "已复制" else "复制",
                                icon = if (copied) JerreaderIcon.CHECK else JerreaderIcon.COPY,
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
                            // Stays accent once saved, as on iOS; a second tap does nothing.
                            JerreaderInlineAction(
                                text = if (savedFavorite) "已收藏" else "收藏",
                                icon = if (savedFavorite) {
                                    JerreaderIcon.STAR_FILLED
                                } else {
                                    JerreaderIcon.STAR_OUTLINE
                                },
                                bounceTrigger = savedFavorite,
                                onClick = {
                                    if (!savedFavorite) session.launch {
                                        val sourceText = translatedSourceText ?: return@launch
                                        runCatchingActive {
                                            graph.learningRecords.saveTranslationFavorite(
                                                sourceText,
                                                translated
                                            )
                                        }.onSuccess {
                                            savedFavorite = true
                                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                        }.onFailure { error ->
                                            status = "收藏失败：${error.message ?: "请稍后重试。"}"
                                        }
                                    }
                                }
                            )
                        }
                        if (translated.isFromCache) JerreaderCacheChip()
                    }
                }
            }
        }

        status?.let { JerreaderErrorCard(it, Modifier.padding(top = 12.dp)) }

        // On a short screen the note keeps one line and ends in an ellipsis.
        Row(
            modifier = Modifier.padding(top = 12.dp).shrinks(12.dp + noteLine).padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top
        ) {
            JerreaderGlyph(
                icon = providerSymbol(providerMode),
                tint = secondary,
                modifier = Modifier.padding(top = 1.dp).size(13.dp)
            )
            Text(
                providerNote(providerMode, providers),
                style = noteStyle,
                color = secondary,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.height(16.dp))
    }
    }
}

/** What the result card's text area shows; the result cross-fade runs between these. */
private sealed interface TranslationOutput {
    data object Placeholder : TranslationOutput
    data object Loading : TranslationOutput
    data class Shown(val result: TranslationResult, val sourceText: String?) : TranslationOutput
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

/** `0 / 2,000`, the way the iOS counter groups its limit. */
private fun groupedCount(value: Int): String =
    value.toString().reversed().chunked(3).joinToString(",").reversed()

private fun providerSymbol(mode: TranslationProviderMode): JerreaderIcon = when (mode) {
    TranslationProviderMode.ON_DEVICE -> JerreaderIcon.LOCK
    TranslationProviderMode.DIRECT_API -> JerreaderIcon.SLIDERS
    TranslationProviderMode.BACKEND_PROXY -> JerreaderIcon.SHIELD
}

/** The line beside the result card's spinner, worded as on iOS. */
private fun loadingNote(mode: TranslationProviderMode, wordMode: Boolean): String = when {
    wordMode -> "正在查词或准备翻译…"
    mode == TranslationProviderMode.ON_DEVICE -> "正在使用本机翻译…"
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

/**
 * iOS `WordRecordRow` in a list card, with the list's disclosure chevron. On
 * Android the row's swipe actions are a long-press menu.
 */
@Composable
private fun LearningWordCollectionCard(
    record: WordLookupEntity,
    now: Long,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    dotTint: Color? = null
) {
    var menuOpen by remember(record.lookupKey) { mutableStateOf(false) }
    Box(modifier) {
        JerreaderRecordCard(onClick = onOpen, onLongClick = { menuOpen = true }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JerreaderWordRecordRow(
                    surfaceForm = record.surfaceForm,
                    lemma = record.lemma,
                    reading = record.reading,
                    definitions = record.definitionsText.split(LearningRecordRepository.DEFINITION_SEPARATOR).joinToString("；"),
                    languageName = languageTagName(record.language),
                    partOfSpeech = record.partOfSpeech,
                    trailingNote = JerreaderRelativeTime.format(record.lastLookedUpAtEpochMillis, now),
                    modifier = Modifier.weight(1f),
                    isFavorite = record.isFavorite,
                    dotTint = dotTint
                )
                JerreaderGlyph(JerreaderIcon.CHEVRON_FORWARD, MaterialTheme.colorScheme.plainTertiary, Modifier.size(13.dp))
            }
        }
        LearningMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
            LearningMenuItem(if (record.isFavorite) "取消收藏" else "收藏") {
                menuOpen = false
                onFavorite()
            }
            if (onDelete != null) {
                LearningMenuItem("删除查词记录", destructive = true) {
                    menuOpen = false
                    onDelete()
                }
            }
        }
    }
}

/** 生词本: the list, 收藏还是空的 and 没有匹配内容 cross-fade as on iOS. */
private enum class LearningListState { RESULTS, EMPTY, NO_RESULTS }

@Composable
private fun VocabularySection(
    words: List<WordLookupEntity>,
    translations: List<TranslationFavoriteEntity>,
    onOpenWord: (WordLookupEntity) -> Unit,
    onToggleWord: (String, Boolean) -> Unit,
    onDeleteTranslation: (String) -> Unit,
    onExport: (LearningExportFormat) -> Unit,
    modifier: Modifier = Modifier
) {
    var search by rememberSaveable { mutableStateOf("") }
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
    val now = rememberNowMillis()
    val stagger = rememberLearningStaggerWindow()
    var exportMenuOpen by remember { mutableStateOf(false) }
    val state = when {
        visibleWords.isNotEmpty() || visibleTranslations.isNotEmpty() -> LearningListState.RESULTS
        term.isEmpty() -> LearningListState.EMPTY
        else -> LearningListState.NO_RESULTS
    }
    LearningCollectionTypography {
    Column(modifier.fillMaxSize()) {
        JerreaderCollectionControls {
            JerreaderSearchField(
                value = search,
                onValueChange = { search = it },
                placeholder = "搜索词语、原文或译文",
                modifier = Modifier.weight(1f)
            )
            JerreaderCountBadge(count = words.size + translations.size, noun = "收藏")
            Box {
                JerreaderToolbarIconButton(
                    icon = JerreaderIcon.SHARE,
                    label = "导出生词与译文收藏",
                    enabled = words.isNotEmpty() || translations.isNotEmpty(),
                    onClick = { exportMenuOpen = true }
                )
                LearningMenu(expanded = exportMenuOpen, onDismiss = { exportMenuOpen = false }) {
                    LearningExportFormat.entries.forEach { format ->
                        LearningMenuItem(format.menuTitle, icon = format.icon) {
                            exportMenuOpen = false
                            onExport(format)
                        }
                    }
                }
            }
        }
        AnimatedContent(
            targetState = state,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = { learningStateTransition() },
            label = "vocabulary-state"
        ) { shown ->
            when (shown) {
                LearningListState.EMPTY -> LearningEmptyList(
                    title = "收藏还是空的",
                    message = "在翻译的词语模式收藏词典结果，或在阅读中的译文卡片点按星标，内容就会出现在这里。",
                    icon = JerreaderIcon.BOOKMARK_OUTLINE
                )
                LearningListState.NO_RESULTS -> LearningEmptyList(
                    title = "没有匹配内容",
                    message = "请尝试搜索其他词语、原文、译文或书名。",
                    icon = JerreaderIcon.SEARCH
                )
                LearningListState.RESULTS -> LearningRecordList {
                    if (visibleWords.isNotEmpty()) {
                        item(key = "heading:words", contentType = "heading") {
                            JerreaderLearningListHeading(
                                title = "收藏词语",
                                detail = "词条收藏保存在本机，清空查词历史不会删除它们。"
                            )
                        }
                        itemsIndexed(
                            visibleWords,
                            key = { _, record -> "word:${record.lookupKey}" },
                            contentType = { _, _ -> "word" }
                        ) { index, record ->
                            LearningWordCollectionCard(
                                record = record,
                                now = now,
                                onOpen = { onOpenWord(record) },
                                onFavorite = { onToggleWord(record.lookupKey, false) },
                                modifier = Modifier.learningStagger(index, stagger)
                            )
                        }
                    }
                    if (visibleTranslations.isNotEmpty()) {
                        item(key = "heading:translations", contentType = "heading") {
                            JerreaderLearningListHeading(
                                title = "收藏译文",
                                detail = "阅读中点按译文卡片上的星标即可保存。"
                            )
                        }
                        // The translations continue the words' stagger.
                        val offset = min(visibleWords.size, LEARNING_STAGGER_ROWS)
                        itemsIndexed(
                            visibleTranslations,
                            key = { _, favorite -> "translation:${favorite.favoriteKey}" },
                            contentType = { _, _ -> "translation" }
                        ) { index, favorite ->
                            var menuOpen by remember(favorite.favoriteKey) { mutableStateOf(false) }
                            Box(Modifier.learningStagger(index + offset, stagger)) {
                                JerreaderRecordCard(onLongClick = { menuOpen = true }) {
                                    JerreaderTranslationFavoriteRow(
                                        languagePair = "${languageTagName(favorite.sourceLanguage)} → ${languageTagName(favorite.targetLanguage)}",
                                        translatedText = favorite.translatedText,
                                        sourceText = favorite.sourceText,
                                        bookTitle = favorite.bookTitle,
                                        trailingNote = java.text.SimpleDateFormat("M月d日", java.util.Locale.SIMPLIFIED_CHINESE)
                                            .format(java.util.Date(favorite.updatedAtEpochMillis))
                                    )
                                }
                                LearningMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                                    LearningMenuItem("取消收藏", destructive = true) {
                                        menuOpen = false
                                        onDeleteTranslation(favorite.favoriteKey)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/** The list and the empty states cross-fade, the new one settling from 99% at its top. */
private fun learningStateTransition(): ContentTransform {
    val origin = TransformOrigin(0.5f, 0f)
    return ContentTransform(
        fadeIn(JerreaderLearningMotion.stateChange()) +
            scaleIn(JerreaderLearningMotion.stateChange(), initialScale = 0.99f, transformOrigin = origin),
        fadeOut(JerreaderLearningMotion.stateChange()) +
            scaleOut(JerreaderLearningMotion.stateChange(), targetScale = 0.99f, transformOrigin = origin),
        0f,
        SizeTransform(clip = false)
    )
}

/** The iOS plain list of record cards: top content margin 8, at most 960 wide. */
@Composable
private fun LearningRecordList(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = 960.dp).fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp),
            content = content
        )
    }
}

/** An empty state centred in the room left under the controls; it scrolls when large text needs more. */
@Composable
private fun LearningEmptyList(title: String, message: String, icon: JerreaderIcon, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val least = if (constraints.hasBoundedHeight) maxHeight else 0.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = least),
            contentAlignment = Alignment.Center
        ) {
            JerreaderEmptyState(title = title, message = message, icon = icon)
        }
    }
}

/**
 * The learning lists' menus, drawn like the translator's: raised paper with a
 * hairline and no Material shadow.
 */
@Composable
private fun LearningMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = jerreaderPalette
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = RoundedCornerShape(JerreaderRadius.control),
        containerColor = palette.raisedPaper,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(0.75.dp, palette.line),
        content = content
    )
}

/** One menu entry in body text; destructive ones are vermilion, as iOS draws them. */
@Composable
private fun LearningMenuItem(
    title: String,
    modifier: Modifier = Modifier,
    icon: JerreaderIcon? = null,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val palette = jerreaderPalette
    val tint = if (destructive) palette.vermilion else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(title, style = JerreaderLearningType.body, color = tint) },
        onClick = onClick,
        modifier = modifier,
        leadingIcon = if (icon != null) {
            { JerreaderGlyph(icon, tint, Modifier.size(17.dp)) }
        } else {
            null
        }
    )
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

/**
 * iOS `LookupHistoryView`: the newest lookups grouped by day under accent
 * eyebrows, each row with a kind dot (saved, looked up again, looked up once).
 */
@Composable
private fun HistorySection(
    records: List<WordLookupEntity>,
    onOpenWord: (WordLookupEntity) -> Unit,
    onFavorite: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    var search by rememberSaveable { mutableStateOf("") }
    val term = search.trim()
    val visibleRecords = remember(records, term) { if (term.isEmpty()) records else records.filter { record ->
        record.surfaceForm.contains(term, ignoreCase = true) ||
            record.lemma?.contains(term, ignoreCase = true) == true ||
            record.reading?.contains(term, ignoreCase = true) == true ||
            record.definitionsText.contains(term, ignoreCase = true) ||
            record.sentenceContext?.contains(term, ignoreCase = true) == true
    } }
    val palette = jerreaderPalette
    val haptics = LocalHapticFeedback.current
    val now = rememberNowMillis()
    val stagger = rememberLearningStaggerWindow()
    val days = remember(visibleRecords, now) { historyDays(visibleRecords, now) }
    var confirmingClear by remember { mutableStateOf(false) }
    // iOS `Color.secondary.opacity(0.4)`.
    val quiet = palette.secondaryText.copy(alpha = palette.secondaryText.alpha * 0.4f)
    val state = when {
        visibleRecords.isNotEmpty() -> LearningListState.RESULTS
        term.isEmpty() -> LearningListState.EMPTY
        else -> LearningListState.NO_RESULTS
    }
    LearningCollectionTypography {
    Column(modifier.fillMaxSize()) {
        JerreaderCollectionControls {
            JerreaderSearchField(
                value = search,
                onValueChange = { search = it },
                placeholder = "搜索查词历史",
                modifier = Modifier.weight(1f)
            )
            JerreaderCountBadge(count = records.size, noun = "记录")
            JerreaderToolbarIconButton(
                icon = JerreaderIcon.TRASH,
                label = "清空查词历史",
                enabled = records.isNotEmpty(),
                tint = palette.vermilion,
                onClick = {
                    confirmingClear = true
                    // A warning tick as the question appears, as on iOS.
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                }
            )
        }
        AnimatedContent(
            targetState = state,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = { learningStateTransition() },
            label = "history-state"
        ) { shown ->
            when (shown) {
                LearningListState.EMPTY -> LearningEmptyList(
                    title = "还没有查词记录",
                    message = "在“翻译”的词语模式完成一次词典查询后，结果会自动保存在这里。",
                    icon = JerreaderIcon.CLOCK
                )
                LearningListState.NO_RESULTS -> LearningEmptyList(
                    title = "没有匹配记录",
                    message = "请尝试其他词语、读音、释义或上下文。",
                    icon = JerreaderIcon.SEARCH
                )
                LearningListState.RESULTS -> LearningRecordList {
                    item(key = "heading", contentType = "heading") {
                        Column(
                            modifier = Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 2.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            JerreaderLearningSectionHeading(
                                title = "最近查询",
                                detail = "共 ${records.size} 个词条，按最近查询时间排列。"
                            )
                            JerreaderLearningLegend(
                                listOf("已收藏" to palette.accent, "多次查询" to palette.vermilion, "查过一次" to quiet)
                            )
                        }
                    }
                    days.forEach { day ->
                        item(key = "day:${day.date.toEpochDay()}:${day.start}", contentType = "day") {
                            JerreaderLearningDayHeading(day.title)
                        }
                        itemsIndexed(
                            day.records,
                            key = { _, record -> "history:${record.lookupKey}" },
                            contentType = { _, _ -> "history" }
                        ) { index, record ->
                            LearningWordCollectionCard(
                                record = record,
                                now = now,
                                onOpen = { onOpenWord(record) },
                                onFavorite = { onFavorite(record.lookupKey, !record.isFavorite) },
                                modifier = Modifier.learningStagger(day.start + index, stagger),
                                onDelete = { onDelete(record.lookupKey) },
                                dotTint = when {
                                    record.isFavorite -> palette.accent
                                    record.lookupCount >= 2 -> palette.vermilion
                                    else -> quiet
                                }
                            )
                        }
                    }
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
                }) { Text("清空历史", color = palette.vermilion) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) { Text("取消") }
            }
        )
    }
    }
}

/** One day of 历史: a run of the newest-first records, from index [start]. */
private class HistoryDay(
    val date: LocalDate,
    val title: String,
    val start: Int,
    val records: List<WordLookupEntity>
)

/** Records by the day they were last looked up. They are newest first, so each day is one run. */
private fun historyDays(records: List<WordLookupEntity>, now: Long): List<HistoryDay> {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    fun dayOf(record: WordLookupEntity): LocalDate =
        Instant.ofEpochMilli(record.lastLookedUpAtEpochMillis).atZone(zone).toLocalDate()
    val days = mutableListOf<HistoryDay>()
    var start = 0
    while (start < records.size) {
        val date = dayOf(records[start])
        var end = start + 1
        while (end < records.size && dayOf(records[end]) == date) end++
        days += HistoryDay(date, historyDayTitle(date, today), start, records.subList(start, end))
        start = end
    }
    return days
}

/** 今天, 昨天, 11月3日, or 2025年11月3日 for an earlier year. */
private fun historyDayTitle(day: LocalDate, today: LocalDate): String = when {
    day == today -> "今天"
    day == today.minusDays(1) -> "昨天"
    day.year == today.year -> "${day.monthValue}月${day.dayOfMonth}日"
    else -> "${day.year}年${day.monthValue}月${day.dayOfMonth}日"
}

/** The record tables store a BCP-47 tag; iOS shows the language's own name. */
private fun languageTagName(tag: String): String =
    LanguageCode.fromTag(tag)?.let(::languageName) ?: tag

private fun languageName(language: LanguageCode): String = when (language) {
    LanguageCode.CHINESE_SIMPLIFIED -> "简体中文"
    LanguageCode.ENGLISH -> "英语"
    LanguageCode.JAPANESE -> "日语"
}
