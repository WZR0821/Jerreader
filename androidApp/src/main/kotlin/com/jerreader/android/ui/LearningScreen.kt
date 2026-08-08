package com.jerreader.android.ui

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationResult
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ContextExplanationService
import com.jerreader.shared.translation.StandaloneLexicalLookupPolicy
import com.jerreader.shared.translation.StandaloneTranslationMode
import com.jerreader.shared.translation.StandaloneTranslationRequestPolicy
import com.jerreader.shared.translation.TranslationProviderMode
import com.jerreader.shared.translation.TranslationSourceChoice
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
private enum class LearningTab(val title: String) {
    TRANSLATE("翻译"),
    VOCABULARY("生词本"),
    HISTORY("历史")
}

private enum class LearningExportFormat(val title: String, val mimeType: String) {
    CSV("CSV", "text/csv"),
    MARKDOWN("Markdown", "text/markdown"),
    ANKI_TSV("Anki TSV", "text/tab-separated-values")
}

@OptIn(ExperimentalMaterial3Api::class)
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
                "学习",
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 6.dp, bottom = 10.dp),
                style = MaterialTheme.typography.displaySmall
            )
        },
        bottomBar = bottomBar
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // iOS uses a pill segmented navigation here, not a chip row. The
            // record tabs run their control bar edge to edge, so the page
            // padding is applied per section instead of to the whole column.
            JerreaderSegmentedNav(
                modifier = Modifier.padding(horizontal = 16.dp),
                options = listOf(
                    Triple(LearningTab.TRANSLATE, "翻译", JerreaderIcon.SPEECH_BUBBLE),
                    Triple(LearningTab.VOCABULARY, "生词本", JerreaderIcon.BOOKMARK_OUTLINE),
                    Triple(LearningTab.HISTORY, "历史", JerreaderIcon.CLOCK)
                ),
                selected = tab,
                onSelect = { tab = it }
            )
            when (tab) {
                LearningTab.TRANSLATE -> Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    TranslationTool(graph)
                }
                LearningTab.VOCABULARY -> VocabularySection(
                    words = words.filter(WordLookupEntity::isFavorite),
                    translations = translationFavorites,
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

/**
 * The standalone translator, ported from the iOS `TranslateToolView`. It owns
 * its direction, mode and provider so experimenting here never rewrites the
 * settings the reader uses, and word mode follows a successful translation with
 * a real dictionary entry instead of leaving the user to look the word up again.
 */
@Composable
private fun TranslationTool(graph: AppGraph) {
    val preferences by graph.translationSettings.preferences.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(StandaloneTranslationMode.SENTENCE) }
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
                    mode = it
                    clearOutput()
                    input = input.take(it.maximumCharacterCount)
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
    onDeleteTranslation: (String) -> Unit,
    onExport: (LearningExportFormat) -> Unit
) {
    var search by remember { mutableStateOf("") }
    val term = search.trim()
    val visibleWords = if (term.isEmpty()) words else words.filter { record ->
        record.surfaceForm.contains(term, ignoreCase = true) ||
            record.lemma?.contains(term, ignoreCase = true) == true ||
            record.reading?.contains(term, ignoreCase = true) == true ||
            record.definitionsText.contains(term, ignoreCase = true)
    }
    val visibleTranslations = if (term.isEmpty()) translations else translations.filter { record ->
        record.sourceText.contains(term, ignoreCase = true) ||
            record.translatedText.contains(term, ignoreCase = true) ||
            record.bookTitle?.contains(term, ignoreCase = true) == true
    }
    val accent = LocalJerreaderColors.current.accent
    val now = rememberNowMillis()
    var exportMenuOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        if (visibleWords.isEmpty() && visibleTranslations.isEmpty()) {
            JerreaderEmptyState(
                title = if (term.isEmpty()) "收藏还是空的" else "没有匹配内容",
                message = if (term.isEmpty()) {
                    "在翻译的词语模式收藏词典结果，或在阅读中的译文卡片点按星标，内容就会出现在这里。"
                } else {
                    "请尝试搜索其他词语、原文、译文或书名。"
                },
                icon = if (term.isEmpty()) JerreaderIcon.BOOKMARK_OUTLINE else JerreaderIcon.SEARCH
            )
        }
        if (visibleWords.isNotEmpty()) {
            JerreaderCollectionSectionHeader(
                title = "收藏词语",
                detail = "词条收藏保存在本机，清空查词历史不会删除它们。"
            )
            visibleWords.forEach { record ->
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
                                icon = JerreaderIcon.STAR_FILLED,
                                tint = accent,
                                onClick = { onToggleWord(record.lookupKey, false) }
                            )
                        }
                    )
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

