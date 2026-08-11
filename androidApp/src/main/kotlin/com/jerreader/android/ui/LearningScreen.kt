package com.jerreader.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.statusBarsPadding
import com.jerreader.android.AppGraph
import com.jerreader.unified.design.JerreaderCopy
import com.jerreader.unified.ui.JerreaderDivider
import com.jerreader.unified.ui.JerreaderErrorCard
import com.jerreader.unified.ui.JerreaderGlyph
import com.jerreader.unified.ui.JerreaderCollectionControls
import com.jerreader.unified.ui.JerreaderCollectionSectionHeader
import com.jerreader.unified.ui.JerreaderCountBadge
import com.jerreader.unified.ui.JerreaderEmptyState
import com.jerreader.unified.ui.JerreaderIcon
import com.jerreader.unified.ui.JerreaderInlineAction
import com.jerreader.unified.ui.JerreaderInlineMenu
import com.jerreader.unified.ui.JerreaderLanguageBar
import com.jerreader.unified.ui.JerreaderMenuField
import com.jerreader.unified.ui.JerreaderSegmentedNav
import com.jerreader.unified.ui.JerreaderSegmentedPicker
import com.jerreader.unified.ui.JerreaderSwapButton
import com.jerreader.unified.ui.JerreaderTranslatorCard
import com.jerreader.unified.ui.JerreaderCard
import com.jerreader.unified.ui.JerreaderRecordCard
import com.jerreader.unified.ui.JerreaderRelativeTime
import com.jerreader.unified.ui.JerreaderRowIconButton
import com.jerreader.unified.ui.JerreaderSearchField
import com.jerreader.unified.ui.JerreaderToolbarIconButton
import com.jerreader.unified.ui.JerreaderTranslationFavoriteRow
import com.jerreader.unified.ui.JerreaderWordRecordRow
import com.jerreader.unified.ui.LocalJerreaderColors
import com.jerreader.android.data.TranslationFavoriteEntity
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.android.learning.LearningRecordRepository
import com.jerreader.unified.domain.LanguageCode
import com.jerreader.unified.lexical.WordExplanation
import com.jerreader.unified.lexical.VocabularyLearningPolicy
import com.jerreader.unified.lexical.VocabularyStatus
import com.jerreader.unified.lexical.VocabularyReviewQueueState
import com.jerreader.unified.lexical.VocabularyReviewRating
import com.jerreader.unified.lexical.VocabularyReviewScheduler
import com.jerreader.unified.translation.TranslationRequest
import com.jerreader.unified.translation.TranslationResult
import com.jerreader.unified.translation.ContextExplanationRequest
import com.jerreader.unified.translation.ContextExplanationService
import com.jerreader.unified.translation.StandaloneLexicalLookupPolicy
import com.jerreader.unified.translation.StandaloneTranslationMode
import com.jerreader.unified.translation.StandaloneTranslationRequestPolicy
import com.jerreader.unified.translation.TranslationProviderMode
import com.jerreader.unified.translation.TranslationSourceChoice
import com.jerreader.android.translation.StandaloneTranslationOverride
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
private enum class LearningTab(val title: String, val icon: JerreaderIcon) {
    TRANSLATE(JerreaderCopy.learningTranslateSection, JerreaderIcon.SPEECH_BUBBLE),
    REVIEW(JerreaderCopy.learningReviewSection, JerreaderIcon.CHECKLIST),
    VOCABULARY(JerreaderCopy.learningVocabularySection, JerreaderIcon.BOOKMARK_OUTLINE),
    HISTORY(JerreaderCopy.learningHistorySection, JerreaderIcon.CLOCK)
}

private enum class LearningExportFormat(val title: String, val mimeType: String) {
    CSV("CSV", "text/csv"),
    MARKDOWN("Markdown", "text/markdown"),
    ANKI_TSV("Anki TSV", "text/tab-separated-values")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LearningScreen(graph: AppGraph, bottomBar: @Composable () -> Unit) {
    var tab by remember { mutableStateOf(LearningTab.TRANSLATE) }
    val words by graph.learningRecords.observeWords()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val translationFavorites by graph.learningRecords.observeTranslationFavorites()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val context = LocalContext.current
    val exportScope = rememberCoroutineScope()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Text(
                JerreaderCopy.learningTitle,
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 6.dp, bottom = 10.dp),
                style = MaterialTheme.typography.displaySmall
            )
        },
        bottomBar = bottomBar
    ) { padding ->
        // The tab bar is chrome, not content: scrolling the whole page took it
        // off screen and left the reader looking at a section with no way back
        // to the others. It stays put, and only the section below it moves —
        // and only when that section is genuinely taller than the space it has.
        // Overscroll is off for the same reason as on the settings pages: a
        // rubber-banded drag on a page that fits reads as blank space above and
        // below the content.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(top = 8.dp)
        ) {
            // iOS uses a pill segmented navigation here, not a chip row. The
            // record tabs run their control bar edge to edge, so the page
            // padding is applied per section instead of to the whole column.
            JerreaderSegmentedNav(
                modifier = Modifier.padding(horizontal = 16.dp),
                // The labels come off the enum rather than being listed again
                // here — this list and the enum used to carry two copies of the
                // same three words.
                options = LearningTab.entries.map { Triple(it, it.title, it.icon) },
                selected = tab,
                onSelect = { tab = it }
            )
            Box(modifier = Modifier.weight(1f)) {
                CompositionLocalProvider(LocalOverscrollFactory provides null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 14.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                when (tab) {
                    LearningTab.TRANSLATE -> Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        TranslationTool(graph)
                    }
                    LearningTab.REVIEW -> ReviewSection(
                        words = words,
                        onReview = { key, rating ->
                            graph.applicationScope.launch {
                                graph.learningRecords.reviewWord(key, rating)
                            }
                        }
                    )
                    LearningTab.VOCABULARY -> VocabularySection(
                        words = words.filter(WordLookupEntity::isFavorite),
                        translations = translationFavorites,
                        onToggleWord = { key, favorite ->
                            graph.applicationScope.launch {
                                graph.learningRecords.setWordFavorite(key, favorite)
                            }
                        },
                        onStatusChange = { key, status ->
                            graph.applicationScope.launch {
                                graph.learningRecords.setVocabularyStatus(key, status)
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

@Composable
private fun ReviewSection(
    words: List<WordLookupEntity>,
    onReview: (String, VocabularyReviewRating) -> Unit
) {
    val now = rememberNowMillis()
    var reviewedKeys by remember { mutableStateOf(emptySet<String>()) }
    var reviewedInSession by remember { mutableStateOf(0) }

    val favoriteWords = words.filter(WordLookupEntity::isFavorite)
    val queueStates = favoriteWords.associateWith { record ->
        VocabularyReviewScheduler.queueState(
            isFavorite = record.isFavorite,
            status = VocabularyStatus.fromStorageId(record.vocabularyStatus),
            reviewCount = record.reviewCount,
            nextReviewAtEpochMillis = record.nextReviewAtEpochMillis,
            nowEpochMillis = now
        )
    }
    val dueWords = favoriteWords
        .filter { queueStates[it] == VocabularyReviewQueueState.DUE }
        .sortedWith(
            compareBy<WordLookupEntity> { it.nextReviewAtEpochMillis }
                .thenBy { if (it.language == LanguageCode.JAPANESE.tag) 0 else 1 }
        )
    val newWords = favoriteWords
        .filter { queueStates[it] == VocabularyReviewQueueState.UNSEEN }
        .sortedWith(
            compareBy<WordLookupEntity> {
                if (it.language == LanguageCode.JAPANESE.tag) 0 else 1
            }.thenByDescending(WordLookupEntity::lastLookedUpAtEpochMillis)
        )
    val queue = (
        dueWords.take(VocabularyReviewScheduler.DAILY_DUE_LIMIT) +
            newWords.take(VocabularyReviewScheduler.DAILY_NEW_LIMIT)
        ).filterNot { it.lookupKey in reviewedKeys }
    val current = queue.firstOrNull()
    var isRevealed by remember { mutableStateOf(false) }

    LaunchedEffect(current?.lookupKey) {
        isRevealed = false
    }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        JerreaderCard {
            Text(
                "今日学习",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "优先呈现日语词条；到期词先于新词。所有进度只保存在本机。",
                modifier = Modifier.padding(top = 5.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ReviewCountCard("到期", dueWords.size, Modifier.weight(1f))
                ReviewCountCard("新词", newWords.size, Modifier.weight(1f))
                ReviewCountCard("本次完成", reviewedInSession, Modifier.weight(1f))
            }
        }

        if (current == null) {
            JerreaderEmptyState(
                title = if (favoriteWords.isEmpty()) "先收藏一些日语词" else "今天的复习完成了",
                message = if (favoriteWords.isEmpty()) {
                    "在阅读或词语翻译中加入生词本，词条就会进入每日复习。"
                } else {
                    "到期词和今天的新词都已处理。继续阅读，遇到新词再回来。"
                },
                icon = if (favoriteWords.isEmpty()) {
                    JerreaderIcon.BOOKMARK_OUTLINE
                } else {
                    JerreaderIcon.CHECK_CIRCLE
                }
            )
        } else {
            ReviewCard(
                record = current,
                position = reviewedInSession + 1,
                remaining = queue.size,
                isRevealed = isRevealed,
                onReveal = { isRevealed = true },
                onRate = { rating ->
                    reviewedKeys = reviewedKeys + current.lookupKey
                    reviewedInSession += 1
                    isRevealed = false
                    onReview(current.lookupKey, rating)
                }
            )
        }
    }
}

@Composable
private fun ReviewCountCard(label: String, count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = LocalJerreaderColors.current.mutedSurface
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                count.toString(),
                style = MaterialTheme.typography.titleLarge,
                color = LocalJerreaderColors.current.accent,
                fontWeight = FontWeight.Bold
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ReviewCard(
    record: WordLookupEntity,
    position: Int,
    remaining: Int,
    isRevealed: Boolean,
    onReveal: () -> Unit,
    onRate: (VocabularyReviewRating) -> Unit
) {
    val contexts = VocabularyLearningPolicy.decodeContexts(record.contextHistoryText)
    val prompt = VocabularyReviewScheduler.prompt(
        surfaceForm = record.surfaceForm,
        lemma = record.lemma,
        sentenceContext = contexts.firstOrNull() ?: record.sentenceContext
    )
    val isJapanese = record.language == LanguageCode.JAPANESE.tag
    JerreaderCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isJapanese) "日语回想" else "词语回想",
                style = MaterialTheme.typography.labelLarge,
                color = LocalJerreaderColors.current.accent
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "第 $position 张 · 还剩 $remaining 张",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            prompt.text,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = if (prompt.isCloze) FontWeight.Medium else FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            if (prompt.isCloze) {
                "补全原句，并回想读音和中文释义。"
            } else if (isJapanese) {
                "读出这个词，并回想基本形与中文释义。"
            } else {
                "回想这个词的读音与中文释义。"
            },
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        if (!isRevealed) {
            TextButton(
                onClick = onReveal,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp)
            ) {
                Text("显示答案")
            }
        } else {
            JerreaderDivider(modifier = Modifier.padding(top = 18.dp, bottom = 14.dp))
            Text(
                record.surfaceForm,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            val answerMetadata = buildList {
                record.reading?.takeIf(String::isNotBlank)?.let { add(it) }
                record.lemma?.takeIf { it.isNotBlank() && it != record.surfaceForm }
                    ?.let { add("基本形：$it") }
                record.partOfSpeech?.takeIf(String::isNotBlank)?.let(::add)
            }
            if (answerMetadata.isNotEmpty()) {
                Text(
                    answerMetadata.joinToString(" · "),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            record.inflectionNote?.takeIf(String::isNotBlank)?.let {
                Text(
                    "活用：$it",
                    modifier = Modifier.padding(top = 7.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            record.definitionsText
                .split(LearningRecordRepository.DEFINITION_SEPARATOR)
                .filter(String::isNotBlank)
                .take(3)
                .forEachIndexed { index, definition ->
                    Text(
                        "${index + 1}. $definition",
                        modifier = Modifier.padding(top = if (index == 0) 12.dp else 4.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            record.usageNote?.takeIf(String::isNotBlank)?.let {
                Text(
                    "用法：$it",
                    modifier = Modifier.padding(top = 9.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LearningRecordRepository.decodeExamples(record.examplesText).firstOrNull()?.let { example ->
                Text(
                    "例句：${example.sourceText}",
                    modifier = Modifier.padding(top = 9.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                example.translatedText?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(top = 3.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "这次记得怎么样？",
                modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelLarge
            )
            VocabularyReviewRating.entries.chunked(2).forEach { rowRatings ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowRatings.forEach { rating ->
                        TextButton(
                            onClick = { onRate(rating) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(rating.title, fontWeight = FontWeight.SemiBold)
                                Text(
                                    rating.detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
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
    val preferences by graph.translationSettings.preferences.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(StandaloneTranslationMode.WORD) }
    var providerMode by remember { mutableStateOf(preferences.providerMode) }
    var sourceChoice by remember { mutableStateOf(preferences.sourceChoice) }
    var targetLanguage by remember { mutableStateOf(preferences.targetLanguage) }
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<TranslationResult?>(null) }
    var translatedSourceText by remember { mutableStateOf<String?>(null) }
    var explanation by remember { mutableStateOf<WordExplanation?>(null) }
    var dictionaryNote by remember { mutableStateOf<String?>(null) }
    var analysis by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var analyzing by remember { mutableStateOf(false) }
    var savedFavorite by remember { mutableStateOf(false) }
    var addedToVocabulary by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Follow the reader's languages until this page has produced something.
    LaunchedEffect(preferences.sourceChoice, preferences.targetLanguage) {
        if (!loading && result == null) {
            sourceChoice = preferences.sourceChoice
            targetLanguage = preferences.targetLanguage
        }
    }
    LaunchedEffect(preferences.providerMode) {
        if (!loading && result == null) providerMode = preferences.providerMode
    }

    val clearOutput = {
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
        scope.launch {
            loading = true
            clearOutput()
            val prepared = runCatching {
                StandaloneTranslationRequestPolicy.makeInput(
                    text = input,
                    sourceChoice = sourceChoice,
                    targetLanguage = targetLanguage,
                    mode = mode
                )
            }.getOrElse { error ->
                status = error.message ?: "请检查输入后重试。"
                loading = false
                return@launch
            }
            runCatching {
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
                if (StandaloneLexicalLookupPolicy.supports(
                        prepared.mode,
                        prepared.sourceLanguage
                    )
                ) {
                    runCatching {
                        graph.lexicalLookupService.lookup(
                            word = prepared.text,
                            sentenceContext = null,
                            language = prepared.sourceLanguage
                        )
                    }.onSuccess { found ->
                        explanation = found
                        runCatching { graph.learningRecords.recordLookup(found) }
                            .onFailure { dictionaryNote = "词典结果暂时无法写入历史。" }
                    }.onFailure {
                        // iOS keeps the translation as a degraded definition
                        // rather than dropping the word on the floor.
                        dictionaryNote = "联网词典暂时不可用，本次译文将作为释义保留。"
                    }
                }
            }.onFailure { status = it.message ?: "翻译失败，请重试。" }
            loading = false
        }
        Unit
    }

    // Mode and service sit above the card on iOS, not inside it.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        JerreaderSegmentedPicker(
            options = StandaloneTranslationMode.entries.map { it to it.title },
            selected = mode,
            onSelect = {
                if (it != mode) {
                    val previousMode = mode
                    mode = it
                    clearOutput()
                    input = if (
                        previousMode == StandaloneTranslationMode.SENTENCE &&
                        it == StandaloneTranslationMode.WORD
                    ) {
                        ""
                    } else {
                        input.take(it.maximumCharacterCount)
                    }
                }
            }
        )
        JerreaderMenuField(
            label = "服务",
            icon = providerSymbol(providerMode),
            options = listOf(
                TranslationProviderMode.ON_DEVICE to "本机翻译",
                TranslationProviderMode.DIRECT_API to "AI API",
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

    JerreaderTranslatorCard {
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

        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = if (mode == StandaloneTranslationMode.WORD) 46.dp else 84.dp)
            ) {
                if (input.isEmpty()) {
                    Text(
                        mode.placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = {
                        input = it.take(mode.maximumCharacterCount)
                        if (result != null && input != translatedSourceText) clearOutput()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    singleLine = mode == StandaloneTranslationMode.WORD
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "${input.length} / ${groupedCount(mode.maximumCharacterCount)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                if (input.isNotEmpty()) {
                    TextButton(onClick = {
                        input = ""
                        clearOutput()
                    }) { Text("清空") }
                }
                TranslateActionButton(
                    loading = loading,
                    enabled = input.isNotBlank() && !loading,
                    onClick = runTranslation
                )
            }
        }
        JerreaderDivider(modifier = Modifier.padding(horizontal = 14.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (mode == StandaloneTranslationMode.WORD) 104.dp else 126.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                if (result?.isFromCache == true) {
                    Text(
                        "缓存",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val translated = result
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
                    Text(translated.translatedText, style = MaterialTheme.typography.bodyLarge)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalAlignment = Alignment.CenterVertically
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
                                scope.launch {
                                    val sourceText = translatedSourceText ?: return@launch
                                    runCatching {
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
                    val hasContextExplanation =
                        graph.translationService is ContextExplanationService
                    if (mode == StandaloneTranslationMode.WORD || hasContextExplanation) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (mode == StandaloneTranslationMode.WORD) {
                                JerreaderInlineAction(
                                    text = if (addedToVocabulary) "已加入生词本" else "加入生词本",
                                    icon = JerreaderIcon.BOOKMARK_OUTLINE,
                                    enabled = !addedToVocabulary,
                                    onClick = {
                                        scope.launch {
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
                                            runCatching {
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
                                            scope.launch {
                                                analyzing = true
                                                runCatching {
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
                                                analyzing = false
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
                    }
                }
                else -> Text(
                    "译文会显示在这里",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    Row(verticalAlignment = Alignment.Top) {
        JerreaderGlyph(
            icon = providerSymbol(providerMode),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).size(13.dp)
        )
        Text(
            providerNote(providerMode),
            modifier = Modifier.padding(start = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    status?.let { JerreaderErrorCard(it) }

    dictionaryNote?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

private fun providerNote(mode: TranslationProviderMode): String = when (mode) {
    TranslationProviderMode.ON_DEVICE ->
        "Android 本机翻译，无需 API Key；首次使用某个语言方向会下载语言模型。"
    TranslationProviderMode.DIRECT_API ->
        "直接调用你在「设置 → 翻译与 AI」中配置的 AI API 与模型。"
    TranslationProviderMode.BACKEND_PROXY ->
        "使用你在「设置 → 翻译与 AI」中配置的 HTTPS 代理。"
}

private fun sourceChoiceName(choice: TranslationSourceChoice): String = when (choice) {
    TranslationSourceChoice.AUTOMATIC -> "自动识别"
    TranslationSourceChoice.JAPANESE -> "日语"
    TranslationSourceChoice.ENGLISH -> "英语"
    TranslationSourceChoice.CHINESE_SIMPLIFIED -> "中文"
}


@Composable
private fun VocabularySection(
    words: List<WordLookupEntity>,
    translations: List<TranslationFavoriteEntity>,
    onToggleWord: (String, Boolean) -> Unit,
    onStatusChange: (String, VocabularyStatus) -> Unit,
    onDeleteTranslation: (String) -> Unit,
    onExport: (LearningExportFormat) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf<String?>(null) }
    val term = search.trim()
    val visibleWords = words.filter { record ->
        val matchesSearch = term.isEmpty() ||
            record.surfaceForm.contains(term, ignoreCase = true) ||
            record.lemma?.contains(term, ignoreCase = true) == true ||
            record.reading?.contains(term, ignoreCase = true) == true ||
            record.definitionsText.contains(term, ignoreCase = true)
        matchesSearch && (statusFilter == null || record.vocabularyStatus == statusFilter)
    }
    val visibleTranslations = if (term.isEmpty()) translations else translations.filter { record ->
        record.sourceText.contains(term, ignoreCase = true) ||
            record.translatedText.contains(term, ignoreCase = true) ||
            record.bookTitle?.contains(term, ignoreCase = true) == true
    }
    val accent = LocalJerreaderColors.current.accent
    val now = rememberNowMillis()
    var statusMenuOpen by remember { mutableStateOf(false) }
    var exportMenuOpen by remember { mutableStateOf(false) }
    var selectedWordKey by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        JerreaderCollectionControls {
            JerreaderSearchField(
                value = search,
                onValueChange = { search = it },
                placeholder = "搜索词语或译文",
                modifier = Modifier.weight(1f)
            )
            JerreaderCountBadge(count = words.size + translations.size)
            Box {
                JerreaderToolbarIconButton(
                    icon = JerreaderIcon.SLIDERS,
                    enabled = words.isNotEmpty(),
                    contentDescription = "按学习状态筛选，当前为" +
                        (statusFilter?.let(VocabularyLearningPolicy::statusTitle) ?: "全部状态"),
                    onClick = { statusMenuOpen = true }
                )
                DropdownMenu(
                    expanded = statusMenuOpen,
                    onDismissRequest = { statusMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("全部状态") },
                        onClick = {
                            statusMenuOpen = false
                            statusFilter = null
                        }
                    )
                    VocabularyLearningPolicy.allStatuses().forEach { status ->
                        DropdownMenuItem(
                            text = { Text(status.title) },
                            onClick = {
                                statusMenuOpen = false
                                statusFilter = status.storageId
                            }
                        )
                    }
                }
            }
            Box {
                JerreaderToolbarIconButton(
                    icon = JerreaderIcon.SHARE,
                    enabled = words.isNotEmpty() || translations.isNotEmpty(),
                    contentDescription = "导出生词与译文收藏",
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
        if (visibleWords.isEmpty() && visibleTranslations.isEmpty()) {
            val isStatusOnlyEmpty = term.isEmpty() && statusFilter != null && words.isNotEmpty()
            JerreaderEmptyState(
                title = when {
                    term.isNotEmpty() -> "没有匹配内容"
                    isStatusOnlyEmpty -> "该状态下没有词条"
                    else -> "收藏还是空的"
                },
                message = when {
                    term.isNotEmpty() -> "请尝试搜索其他词语、原文、译文或书名。"
                    isStatusOnlyEmpty -> "请选择其他学习状态，或切换回全部状态。"
                    else -> "在翻译的词语模式收藏词典结果，或在阅读中的译文卡片点按星标，内容就会出现在这里。"
                },
                icon = if (term.isNotEmpty()) JerreaderIcon.SEARCH else JerreaderIcon.BOOKMARK_OUTLINE
            )
        }
        if (visibleWords.isNotEmpty()) {
            JerreaderCollectionSectionHeader(
                title = "收藏词语",
                detail = "词条收藏保存在本机，清空查词历史不会删除它们。"
            )
            visibleWords.forEach { record ->
                val status = VocabularyStatus.fromStorageId(record.vocabularyStatus)
                val contexts = VocabularyLearningPolicy.decodeContexts(record.contextHistoryText)
                JerreaderRecordCard(onClick = { selectedWordKey = record.lookupKey }) {
                    JerreaderWordRecordRow(
                        surfaceForm = record.surfaceForm,
                        lemma = record.lemma,
                        reading = record.reading,
                        definitions = record.definitionsText
                            .split(LearningRecordRepository.DEFINITION_SEPARATOR)
                            .joinToString("；"),
                        languageName = languageTagName(record.language),
                        partOfSpeech = record.partOfSpeech,
                        trailingNote = JerreaderRelativeTime.format(
                            record.lastLookedUpAtEpochMillis,
                            now
                        ),
                        trailing = {
                            JerreaderRowIconButton(
                                icon = JerreaderIcon.STAR_FILLED,
                                tint = accent,
                                onClick = { onToggleWord(record.lookupKey, false) }
                            )
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "查询 ${record.lookupCount} 次 · 保留 ${contexts.size} 条原文语境",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        JerreaderInlineMenu(
                            options = VocabularyLearningPolicy.allStatuses()
                                .map { it to it.title },
                            selected = status,
                            onSelect = { onStatusChange(record.lookupKey, it) },
                            modifier = Modifier.width(92.dp)
                        )
                    }
                    contexts.firstOrNull()?.let { context ->
                        Text(
                            context,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
        if (visibleTranslations.isNotEmpty()) {
            JerreaderCollectionSectionHeader(
                title = "收藏译文",
                detail = "阅读中点按译文卡片上的星标即可保存。"
            )
            visibleTranslations.forEach { favorite ->
                JerreaderRecordCard {
                    JerreaderTranslationFavoriteRow(
                        languagePair = "${languageTagName(favorite.sourceLanguage)} → " +
                            languageTagName(favorite.targetLanguage),
                        translatedText = favorite.translatedText,
                        sourceText = favorite.sourceText,
                        bookTitle = favorite.bookTitle,
                        trailingNote = JerreaderRelativeTime.format(
                            favorite.updatedAtEpochMillis,
                            now
                        ),
                        trailing = {
                            JerreaderRowIconButton(
                                icon = JerreaderIcon.STAR_FILLED,
                                tint = accent,
                                onClick = { onDeleteTranslation(favorite.favoriteKey) }
                            )
                        }
                    )
                }
            }
        }
    }
    selectedWordKey?.let { key ->
        words.firstOrNull { it.lookupKey == key }?.let { record ->
            VocabularyDetailDialog(
                record = record,
                onDismiss = { selectedWordKey = null },
                onStatusChange = { onStatusChange(record.lookupKey, it) },
                onRemoveFavorite = {
                    selectedWordKey = null
                    onToggleWord(record.lookupKey, false)
                }
            )
        }
    }
}

@Composable
private fun VocabularyDetailDialog(
    record: WordLookupEntity,
    onDismiss: () -> Unit,
    onStatusChange: (VocabularyStatus) -> Unit,
    onRemoveFavorite: () -> Unit
) {
    val status = VocabularyStatus.fromStorageId(record.vocabularyStatus)
    val contexts = VocabularyLearningPolicy.decodeContexts(record.contextHistoryText)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("词条详情") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    record.surfaceForm,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                record.lemma?.takeIf { it != record.surfaceForm }?.let {
                    Text("基本形：$it", style = MaterialTheme.typography.titleMedium)
                }
                record.reading?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                JerreaderDivider()
                Text("词典释义", style = MaterialTheme.typography.labelLarge)
                record.definitionsText
                    .split(LearningRecordRepository.DEFINITION_SEPARATOR)
                    .filter(String::isNotBlank)
                    .forEachIndexed { index, definition ->
                        Text("${index + 1}. $definition", style = MaterialTheme.typography.bodyLarge)
                    }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("学习状态", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.weight(1f))
                    JerreaderInlineMenu(
                        options = VocabularyLearningPolicy.allStatuses().map { it to it.title },
                        selected = status,
                        onSelect = onStatusChange,
                        modifier = Modifier.width(108.dp)
                    )
                }
                Text(
                    "累计查询 ${record.lookupCount} 次 · 保留 ${contexts.size} 条原文语境",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                contexts.forEachIndexed { index, context ->
                    if (index > 0) JerreaderDivider()
                    Text(
                        if (index == 0) "最近语境" else "较早语境",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(context, style = MaterialTheme.typography.bodyMedium)
                }
                record.sourceBookTitle?.let {
                    Text(
                        "来源：《$it》",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onRemoveFavorite) { Text("取消收藏") }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
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
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
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
        appendLine("- ${record.lemma ?: record.surfaceForm} [${VocabularyLearningPolicy.statusTitle(record.vocabularyStatus)}]：${record.definitionsText.replace(LearningRecordRepository.DEFINITION_SEPARATOR, "；")}")
        VocabularyLearningPolicy.decodeContexts(record.contextHistoryText).forEach {
            appendLine("  - 语境：$it")
        }
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
                    "Jerreader ${VocabularyLearningPolicy.statusTitle(record.vocabularyStatus)}"
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
    onFavorite: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit
) {
    var search by remember { mutableStateOf("") }
    val term = search.trim()
    val visibleRecords = if (term.isEmpty()) records else records.filter { record ->
        record.surfaceForm.contains(term, ignoreCase = true) ||
            record.lemma?.contains(term, ignoreCase = true) == true ||
            record.reading?.contains(term, ignoreCase = true) == true ||
            record.definitionsText.contains(term, ignoreCase = true) ||
            record.sentenceContext?.contains(term, ignoreCase = true) == true
    }
    val colors = LocalJerreaderColors.current
    val now = rememberNowMillis()
    var confirmingClear by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                enabled = records.isNotEmpty(),
                contentDescription = "清空查词历史",
                onClick = { confirmingClear = true }
            )
        }
        if (visibleRecords.isEmpty()) {
            JerreaderEmptyState(
                title = if (term.isEmpty()) "还没有查词记录" else "没有匹配记录",
                message = if (term.isEmpty()) {
                    "在“翻译”的词语模式完成一次词典查询后，结果会自动保存在这里。"
                } else {
                    "请尝试其他词语、读音、释义或上下文。"
                },
                icon = if (term.isEmpty()) JerreaderIcon.CLOCK else JerreaderIcon.SEARCH
            )
        } else {
            JerreaderCollectionSectionHeader(
                title = "最近查询",
                detail = "共 ${records.size} 个词条，按最近查询时间排列。"
            )
            visibleRecords.forEach { record ->
                JerreaderRecordCard {
                    JerreaderWordRecordRow(
                        surfaceForm = record.surfaceForm,
                        lemma = record.lemma,
                        reading = record.reading,
                        definitions = record.definitionsText
                            .split(LearningRecordRepository.DEFINITION_SEPARATOR)
                            .joinToString("；"),
                        languageName = languageTagName(record.language),
                        partOfSpeech = record.partOfSpeech,
                        trailingNote = JerreaderRelativeTime.format(
                            record.lastLookedUpAtEpochMillis,
                            now
                        ),
                        trailing = {
                            JerreaderRowIconButton(
                                icon = if (record.isFavorite) {
                                    JerreaderIcon.STAR_FILLED
                                } else {
                                    JerreaderIcon.STAR_OUTLINE
                                },
                                tint = if (record.isFavorite) {
                                    colors.accent
                                } else {
                                    colors.secondaryText
                                },
                                onClick = { onFavorite(record.lookupKey, !record.isFavorite) }
                            )
                            JerreaderRowIconButton(
                                icon = JerreaderIcon.TRASH,
                                tint = colors.secondaryText,
                                onClick = { onDelete(record.lookupKey) }
                            )
                        }
                    )
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

/** The record tables store a BCP-47 tag; iOS shows the language's own name. */
private fun languageTagName(tag: String): String =
    LanguageCode.fromTag(tag)?.let(::languageName) ?: tag

private fun languageName(language: LanguageCode): String = when (language) {
    LanguageCode.CHINESE_SIMPLIFIED -> "简体中文"
    LanguageCode.ENGLISH -> "英语"
    LanguageCode.JAPANESE -> "日语"
}
