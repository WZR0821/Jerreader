package com.jerreader.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.statusBarsPadding
import com.jerreader.android.AppGraph
import com.jerreader.shared.ui.JerreaderHintCard
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderSegmentedNav
import com.jerreader.shared.ui.JerreaderCard
import com.jerreader.shared.ui.JerreaderFieldLabel
import com.jerreader.shared.ui.JerreaderPrimaryButton
import com.jerreader.shared.ui.JerreaderRow
import com.jerreader.shared.ui.JerreaderSegmented
import com.jerreader.shared.ui.JerreaderSuggestionChip
import com.jerreader.android.data.TranslationFavoriteEntity
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.android.learning.LearningRecordRepository
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.WordExplanation
import com.jerreader.shared.translation.TranslationRequest
import com.jerreader.shared.translation.TranslationResult
import com.jerreader.shared.translation.ContextExplanationRequest
import com.jerreader.shared.translation.ContextExplanationService
import com.jerreader.shared.translation.TranslationSourceChoice
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class LearningTab(val title: String) {
    LOOKUP("查词"),
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
    var tab by remember { mutableStateOf(LearningTab.LOOKUP) }
    val words by graph.learningRecords.observeWords()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val translationFavorites by graph.learningRecords.observeTranslationFavorites()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val context = LocalContext.current
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // iOS uses a pill segmented navigation here, not a chip row.
            JerreaderSegmentedNav(
                options = listOf(
                    Triple(LearningTab.LOOKUP, "查词", JerreaderIcon.SEARCH),
                    Triple(LearningTab.TRANSLATE, "翻译", JerreaderIcon.SPEECH_BUBBLE),
                    Triple(LearningTab.VOCABULARY, "生词本", JerreaderIcon.BOOKMARK_OUTLINE),
                    Triple(LearningTab.HISTORY, "历史", JerreaderIcon.CLOCK)
                ),
                selected = tab,
                onSelect = { tab = it }
            )
            JerreaderHintCard(
                badge = if (tab == LearningTab.TRANSLATE) "译" else "あ",
                title = when (tab) {
                    LearningTab.LOOKUP -> "读懂一个词，也保留它的语境。"
                    LearningTab.TRANSLATE -> "翻译一段话，随手收藏。"
                    LearningTab.VOCABULARY -> "生词和译文都在这里。"
                    LearningTab.HISTORY -> "查过的词都留有记录。"
                },
                detail = "查询结果、收藏与上下文只保存在本机"
            )
            when (tab) {
                LearningTab.LOOKUP -> LookupTool(graph)
                LearningTab.TRANSLATE -> TranslationTool(graph)
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
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND)
                                    .setType(format.mimeType)
                                    .putExtra(Intent.EXTRA_SUBJECT, "Jerreader 学习导出·${format.title}")
                                    .putExtra(Intent.EXTRA_TEXT, text),
                                "导出学习记录"
                            )
                        )
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

@Composable
private fun LookupTool(graph: AppGraph) {
    var word by remember { mutableStateOf("") }
    var context by remember { mutableStateOf("") }
    var language by remember { mutableStateOf<LanguageCode?>(null) }
    var result by remember { mutableStateOf<WordExplanation?>(null) }
    var addToVocabulary by remember { mutableStateOf(false) }
    var aiAnalysis by remember { mutableStateOf<String?>(null) }
    var analyzing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // iOS wraps the whole form in one white card with bold field labels.
    JerreaderCard {
        JerreaderSegmented(
            options = listOf<Pair<LanguageCode?, String>>(
                null to "自动",
                LanguageCode.JAPANESE to "日语",
                LanguageCode.ENGLISH to "英语"
            ),
            selected = language,
            onSelect = { language = it },
            modifier = Modifier.padding(bottom = 10.dp)
        )
        Text(
            "会结合词形和上下文自动选择日语或英语词典。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        JerreaderFieldLabel("日语或英语单词/词形")
        OutlinedTextField(
            value = word,
            onValueChange = { word = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("例如：食べました", style = MaterialTheme.typography.bodyMedium) },
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true
        )
        JerreaderFieldLabel("上下文（可选）", modifier = Modifier.padding(top = 14.dp))
        OutlinedTextField(
            value = context,
            onValueChange = { context = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text("输入词语所在的句子，有助于保留学习语境", style = MaterialTheme.typography.bodyMedium)
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            minLines = 2
        )
        Row(
            modifier = Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "试一试",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            JerreaderSuggestionChip(text = "食べました", onClick = { word = "食べました" })
        }
        JerreaderRow(
            label = "同时加入生词本",
            detail = "只查词并保留查询历史",
            modifier = Modifier.padding(top = 10.dp)
        ) {
            Switch(checked = addToVocabulary, onCheckedChange = { addToVocabulary = it })
        }
    }
    JerreaderPrimaryButton(
        text = if (loading) "查询中…" else "查询词语",
        icon = JerreaderIcon.SEARCH,
        enabled = word.isNotBlank() && !loading,
        onClick = {
            scope.launch {
                loading = true
                status = null
                result = null
                aiAnalysis = null
                val resolvedLanguage = language ?: detectLookupLanguage(word, context)
                runCatching {
                    graph.lexicalLookupService.lookup(
                        word = word,
                        sentenceContext = context.ifBlank { null },
                        language = resolvedLanguage
                    )
                }.onSuccess {
                    result = it
                    graph.learningRecords.recordLookup(it, favorite = addToVocabulary)
                }
                    .onFailure { status = it.message ?: "查词失败。" }
                loading = false
            }
        }
    )
    status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    result?.let { explanation ->
        Text(explanation.surfaceForm, style = MaterialTheme.typography.titleLarge)
        explanation.reading?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        explanation.partOfSpeech?.let { Text(it) }
        explanation.definitions.forEachIndexed { index, definition ->
            Text("${index + 1}. $definition")
        }
        explanation.usageNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    runCatching {
                        graph.speechService.speak(
                            explanation.reading ?: explanation.surfaceForm,
                            explanation.language
                        )
                    }.onFailure { status = it.message }
                }
            }) { Text("朗读") }
            Button(onClick = {
                scope.launch {
                    graph.learningRecords.setWordFavorite(
                        LearningRecordRepository.wordKey(explanation),
                        true
                    )
                }
            }) { Text("加入生词本") }
        }
        if (explanation.language == LanguageCode.JAPANESE) {
            Button(
                enabled = !analyzing && graph.translationService is ContextExplanationService,
                onClick = {
                    val service = graph.translationService as? ContextExplanationService
                        ?: return@Button
                    scope.launch {
                        analyzing = true
                        runCatching {
                            service.explain(
                                ContextExplanationRequest(
                                    focusedText = explanation.surfaceForm,
                                    contextText = explanation.sentenceContext,
                                    sourceLanguage = explanation.language
                                )
                            )
                        }.onSuccess { output ->
                            aiAnalysis = output.explanation
                            graph.learningRecords.updateWordAnalysis(
                                LearningRecordRepository.wordKey(explanation),
                                output.explanation,
                                output.providerIdentifier
                            )
                        }.onFailure { status = it.message ?: "AI 解析失败。" }
                        analyzing = false
                    }
                }
            ) { Text(if (analyzing) "解析中…" else "AI 日语深度解析") }
            aiAnalysis?.let { Text(it) }
        }
    }
}

private fun detectLookupLanguage(word: String, context: String): LanguageCode {
    val sample = word + context
    return if (sample.any { it in '\u3040'..'\u30ff' } ||
        word.any { it in '\u3400'..'\u9fff' }
    ) {
        LanguageCode.JAPANESE
    } else {
        LanguageCode.ENGLISH
    }
}

@Composable
private fun TranslationTool(graph: AppGraph) {
    var input by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<TranslationResult?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val preferences by graph.translationSettings.preferences.collectAsStateWithLifecycle()
    var sourceChoice by remember { mutableStateOf(preferences.sourceChoice) }
    var targetLanguage by remember { mutableStateOf(preferences.targetLanguage) }
    val context = LocalContext.current

    Text("翻译", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Text("输入或粘贴文字，使用“设置 → 翻译与 AI”中的服务配置。")
    Text("原文语言")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TranslationSourceChoice.entries.forEach { choice ->
            FilterChip(
                selected = sourceChoice == choice,
                onClick = { sourceChoice = choice },
                label = {
                    Text(
                        when (choice) {
                            TranslationSourceChoice.AUTOMATIC -> "自动"
                            TranslationSourceChoice.JAPANESE -> "日语"
                            TranslationSourceChoice.ENGLISH -> "英语"
                            TranslationSourceChoice.CHINESE_SIMPLIFIED -> "中文"
                        }
                    )
                }
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text("译文语言")
        Button(
            enabled = sourceChoice.language != null,
            onClick = {
                val oldSource = sourceChoice.language ?: return@Button
                sourceChoice = when (targetLanguage) {
                    LanguageCode.CHINESE_SIMPLIFIED -> TranslationSourceChoice.CHINESE_SIMPLIFIED
                    LanguageCode.ENGLISH -> TranslationSourceChoice.ENGLISH
                    LanguageCode.JAPANESE -> TranslationSourceChoice.JAPANESE
                }
                targetLanguage = oldSource
                result?.let {
                    input = it.translatedText
                    result = null
                }
            }
        ) { Text("交换语言") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LanguageCode.entries.forEach { language ->
            FilterChip(
                selected = targetLanguage == language,
                onClick = { targetLanguage = language },
                label = { Text(languageName(language)) }
            )
        }
    }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it.take(2000) },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("原文") },
        minLines = 6
    )
    Text("${input.length} / 2000", style = MaterialTheme.typography.labelSmall)
    if (input.isNotEmpty()) {
        Button(onClick = {
            input = ""
            result = null
            status = null
        }) { Text("清空") }
    }
    Button(
        enabled = input.isNotBlank() && !loading,
        onClick = {
            scope.launch {
                loading = true
                result = null
                saved = false
                status = null
                runCatching {
                    graph.translationService.translate(
                        TranslationRequest(
                            text = input,
                            sourceLanguage = sourceChoice.language,
                            targetLanguage = targetLanguage
                        )
                    )
                }.onSuccess { result = it }
                    .onFailure { status = it.message ?: "翻译失败。" }
                loading = false
            }
        }
    ) { Text(if (loading) "翻译中…" else "翻译") }
    status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    result?.let {
        Text("译文", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(it.translatedText, style = MaterialTheme.typography.bodyLarge)
        Text(
            it.providerIdentifier + if (it.isFromCache) " · 缓存" else "",
            style = MaterialTheme.typography.labelSmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Jerreader 译文", it.translatedText))
            }) { Text("复制") }
            Button(
                enabled = !saved,
                onClick = {
                    scope.launch {
                        graph.learningRecords.saveTranslationFavorite(input, it)
                        saved = true
                    }
                }
            ) { Text(if (saved) "已收藏" else "收藏译文") }
        }
    }
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
    Text("生词本", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LearningExportFormat.entries.forEach { format ->
            Button(onClick = { onExport(format) }) { Text("导出 ${format.title}") }
        }
    }
    OutlinedTextField(
        value = search,
        onValueChange = { search = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("搜索词语、原文或译文") },
        singleLine = true
    )
    if (visibleWords.isEmpty() && visibleTranslations.isEmpty()) {
        Text("还没有收藏内容。查词或翻译后即可收藏到本机。")
    }
    visibleWords.forEach { record ->
        LearningRecordCard(
            title = record.lemma ?: record.surfaceForm,
            detail = record.definitionsText.split(LearningRecordRepository.DEFINITION_SEPARATOR)
                .joinToString("；"),
            meta = listOfNotNull(
                record.reading,
                record.partOfSpeech,
                record.inflectionNote,
                record.sourceBookTitle?.let { "《$it》" }
            ).joinToString(" · "),
            primaryAction = "取消收藏",
            onPrimaryAction = { onToggleWord(record.lookupKey, false) }
        )
    }
    visibleTranslations.forEach { favorite ->
        LearningRecordCard(
            title = favorite.sourceText,
            detail = favorite.translatedText,
            meta = favorite.providerIdentifier,
            primaryAction = "删除",
            onPrimaryAction = { onDeleteTranslation(favorite.favoriteKey) }
        )
    }
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
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("查询历史", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        if (records.isNotEmpty()) Button(onClick = onClear) { Text("清空历史") }
    }
    OutlinedTextField(
        value = search,
        onValueChange = { search = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("搜索查词历史") },
        singleLine = true
    )
    if (visibleRecords.isEmpty()) Text("还没有匹配的查词历史。")
    visibleRecords.forEach { record ->
        LearningRecordCard(
            title = record.lemma ?: record.surfaceForm,
            detail = record.definitionsText.split(LearningRecordRepository.DEFINITION_SEPARATOR)
                .joinToString("；"),
            meta = "查询 ${record.lookupCount} 次${record.sourceBookTitle?.let { " · 《$it》" }.orEmpty()}",
            primaryAction = if (record.isFavorite) "取消收藏" else "收藏",
            onPrimaryAction = { onFavorite(record.lookupKey, !record.isFavorite) },
            secondaryAction = "删除",
            onSecondaryAction = { onDelete(record.lookupKey) }
        )
    }
}

private fun languageName(language: LanguageCode): String = when (language) {
    LanguageCode.CHINESE_SIMPLIFIED -> "简体中文"
    LanguageCode.ENGLISH -> "英语"
    LanguageCode.JAPANESE -> "日语"
}

@Composable
private fun LearningRecordCard(
    title: String,
    detail: String,
    meta: String,
    primaryAction: String,
    onPrimaryAction: () -> Unit,
    secondaryAction: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    androidx.compose.material3.Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall)
            Text(detail)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onPrimaryAction) { Text(primaryAction) }
                if (secondaryAction != null && onSecondaryAction != null) {
                    Button(onClick = onSecondaryAction) { Text(secondaryAction) }
                }
            }
        }
    }
}
