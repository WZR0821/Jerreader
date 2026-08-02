package com.jerreader.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.DirectAIProvider
import com.jerreader.shared.translation.QuickTranslationUnit
import com.jerreader.shared.translation.TranslationPreferences
import com.jerreader.shared.translation.TranslationProviderMode
import com.jerreader.shared.translation.TranslationFallbackMode
import com.jerreader.shared.translation.TranslationDisplayMode
import com.jerreader.shared.translation.TranslationSourceChoice
import com.jerreader.android.settings.AndroidAppPreferences
import com.jerreader.android.settings.AppThemeChoice
import com.jerreader.shared.ui.JerreaderRow
import com.jerreader.shared.ui.JerreaderSection
import com.jerreader.shared.library.ReaderAppearance
import com.jerreader.shared.ui.ReaderAppearancePanel
import kotlinx.coroutines.launch

data class TranslationSettingsActions(
    val updateProviderMode: (TranslationProviderMode) -> Unit,
    val updateDirectProvider: (DirectAIProvider) -> Unit,
    val updateDirectEndpoint: (String) -> Unit,
    val updateDirectModel: (String) -> Unit,
    val updateDirectApiKey: (String) -> Unit,
    val updateBackendEndpoint: (String) -> Unit,
    val updateBackendModel: (String) -> Unit,
    val updateBackendToken: (String) -> Unit,
    val updateSourceChoice: (TranslationSourceChoice) -> Unit,
    val updateTargetLanguage: (LanguageCode) -> Unit,
    val updateQuickEnabled: (Boolean) -> Unit,
    val updateQuickUnit: (QuickTranslationUnit) -> Unit,
    val updateDisablesTapPageTurns: (Boolean) -> Unit,
    val updateDisplayMode: (TranslationDisplayMode) -> Unit,
    val updateTranslationHaptics: (Boolean) -> Unit,
    val updateAutomaticRetry: (Boolean) -> Unit,
    val updateFallbackMode: (TranslationFallbackMode) -> Unit,
    val updatePrompt: (String) -> Unit,
    val updateGrammarPrompt: (String) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appPreferences: AndroidAppPreferences,
    onThemeChanged: (AppThemeChoice) -> Unit,
    onOpenGuide: () -> Unit,
    onDefaultReaderChanged: (ReaderAppearance) -> Unit,
    onShowProgressChanged: (Boolean) -> Unit,
    onApplyDefaultsAutomaticallyChanged: (Boolean) -> Unit,
    onApplyDefaultsToExistingBooks: () -> Unit,
    preferences: TranslationPreferences,
    directApiKey: String,
    backendToken: String,
    actions: TranslationSettingsActions,
    onTestConnection: suspend () -> String,
    bottomBar: @Composable () -> Unit
) {
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "—" }
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
        bottomBar = bottomBar
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SettingsCard("界面主题") {
                ChoiceRow {
                    AppThemeChoice.entries.forEach { choice ->
                        FilterChip(
                            selected = appPreferences.theme == choice,
                            onClick = { onThemeChanged(choice) },
                            label = { Text(choice.title) }
                        )
                    }
                }
            }
            SettingsCard("操作指南") {
                Text(
                    "覆盖导入途径、只读打开、点句/点段、日文版式、主题与选区配色、" +
                        "全书排版同步、PDF 与文字识别、学习功能，以及本机/直接 AI API/代理翻译配置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onOpenGuide) { Text("打开操作指南") }
            }
            SettingsCard("默认阅读排版") {
                SettingsNote("这里设定的是新导入书籍的初始排版；每本书都可以在阅读器里单独覆盖。")
                ToggleRow(
                    "同步应用到现有全部书籍",
                    appPreferences.applyReaderDefaultsToExistingBooks,
                    onApplyDefaultsAutomaticallyChanged
                )
                ToggleRow(
                    "显示阅读进度",
                    appPreferences.showReadingProgress,
                    onShowProgressChanged
                )
                ReaderAppearancePanel(
                    appearance = appPreferences.defaultReaderAppearance,
                    onChange = onDefaultReaderChanged,
                    embedded = true
                )
                if (!appPreferences.applyReaderDefaultsToExistingBooks) {
                    Button(onClick = onApplyDefaultsToExistingBooks) {
                        Text("应用到书架中的现有书籍")
                    }
                }
                SettingsNote("只覆盖阅读排版，不修改阅读位置、书签、划线、批注或笔记。")
            }
            SettingsCard("翻译与 AI") {
                TranslationSettings(
                    preferences = preferences,
                    directApiKey = directApiKey,
                    backendToken = backendToken,
                    actions = actions,
                    onTestConnection = onTestConnection
                )
            }
            SettingsCard("数据与隐私") {
                SettingsNote("书籍、书架与学习记录默认只保存在本机。")
                SettingsNote("API Key 使用 Android Keystore 加密，只保存在本机，不会写入书籍、日志或 APK。")
                SettingsNote("在线 AI 只接收你主动点按或框选的文字。")
            }
            SettingsCard("关于") {
                JerreaderRow("应用") { Text("Jerreader · 读鼠", style = MaterialTheme.typography.bodyMedium) }
                JerreaderRow("版本") { Text("$versionName（Android）", style = MaterialTheme.typography.bodyMedium) }
                JerreaderRow("最低系统") { Text("Android 6.0", style = MaterialTheme.typography.bodyMedium) }
                JerreaderRow("作者") { Text("WANG ZIRUI", style = MaterialTheme.typography.bodyMedium) }
                SettingsNote("Readium EPUB / PDF 阅读内核。当前构建不包含备份、登录、云同步、付费或 DRM 绕过。")
            }
        }
    }
}

@Composable
private fun TranslationSettings(
    preferences: TranslationPreferences,
    directApiKey: String,
    backendToken: String,
    actions: TranslationSettingsActions,
    onTestConnection: suspend () -> String
) {
    var keyText by remember(preferences.directProvider) { mutableStateOf(directApiKey) }
    var tokenText by remember { mutableStateOf(backendToken) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(directApiKey, preferences.directProvider) { keyText = directApiKey }
    LaunchedEffect(backendToken) { tokenText = backendToken }

    Text("翻译服务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    ChoiceRow {
        FilterChip(
            selected = preferences.providerMode == TranslationProviderMode.ON_DEVICE,
            onClick = { actions.updateProviderMode(TranslationProviderMode.ON_DEVICE) },
            label = { Text("Android 本机翻译") }
        )
        FilterChip(
            selected = preferences.providerMode == TranslationProviderMode.DIRECT_API,
            onClick = { actions.updateProviderMode(TranslationProviderMode.DIRECT_API) },
            label = { Text("AI API（直接）") }
        )
        FilterChip(
            selected = preferences.providerMode == TranslationProviderMode.BACKEND_PROXY,
            onClick = { actions.updateProviderMode(TranslationProviderMode.BACKEND_PROXY) },
            label = { Text("AI 代理") }
        )
    }

    when (preferences.providerMode) {
    TranslationProviderMode.ON_DEVICE -> {
        Text("无需账号或 API Key。首次使用某个语言方向时会下载约 30 MB 的语言模型，之后在本机执行翻译。")
    }
    TranslationProviderMode.DIRECT_API -> {
        Text("选择服务商")
        ChoiceRow {
            DirectAIProvider.entries.forEach { provider ->
                FilterChip(
                    selected = preferences.directProvider == provider,
                    onClick = {
                        testStatus = null
                        actions.updateDirectProvider(provider)
                    },
                    label = { Text(provider.displayName) }
                )
            }
        }
        OutlinedTextField(
            value = keyText,
            onValueChange = {
                keyText = it
                testStatus = null
                actions.updateDirectApiKey(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("${preferences.directProvider.displayName} API Key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        OutlinedTextField(
            value = preferences.directModel,
            onValueChange = actions.updateDirectModel,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("模型名称") },
            singleLine = true
        )
        if (preferences.directProvider.usesCustomEndpoint) {
            OutlinedTextField(
                value = preferences.directEndpoint,
                onValueChange = actions.updateDirectEndpoint,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("完整 HTTPS Chat Completions 地址") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
        } else {
            Text(
                "请求地址：${preferences.directProvider.defaultEndpoint}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            if (preferences.directApiKeyPresent) "配置已保存到本机安全存储。" else "粘贴 API Key 后即可翻译。",
            color = if (preferences.directApiKeyPresent) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error
        )
    }
    TranslationProviderMode.BACKEND_PROXY -> {
        OutlinedTextField(
            value = preferences.backendEndpoint,
            onValueChange = actions.updateBackendEndpoint,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("HTTPS 翻译代理地址") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        OutlinedTextField(
            value = preferences.backendModel,
            onValueChange = actions.updateBackendModel,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("模型名称（可选）") },
            singleLine = true
        )
        OutlinedTextField(
            value = tokenText,
            onValueChange = {
                tokenText = it
                actions.updateBackendToken(it)
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("代理访问凭据（可选）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
    }
    }

    Button(
        enabled = !isTesting,
        onClick = {
            scope.launch {
                isTesting = true
                testStatus = onTestConnection()
                isTesting = false
            }
        }
    ) { Text(if (isTesting) "测试中…" else "测试连接") }
    testStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

    Text("语言", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text("原文语言")
    ChoiceRow {
        TranslationSourceChoice.entries.forEach { choice ->
            FilterChip(
                selected = preferences.sourceChoice == choice,
                onClick = { actions.updateSourceChoice(choice) },
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
    Text("译文语言")
    ChoiceRow {
        LanguageCode.entries.forEach { language ->
            FilterChip(
                selected = preferences.targetLanguage == language,
                onClick = { actions.updateTargetLanguage(language) },
                label = {
                    Text(
                        when (language) {
                            LanguageCode.CHINESE_SIMPLIFIED -> "简体中文"
                            LanguageCode.ENGLISH -> "英语"
                            LanguageCode.JAPANESE -> "日语"
                        }
                    )
                }
            )
        }
    }
    if (preferences.sourceChoice.language == preferences.targetLanguage) {
        Text(
            "原文和目标语言不能相同，请改用自动识别或更换语言。",
            color = MaterialTheme.colorScheme.error
        )
    }

    ToggleRow("轻点正文直接翻译", preferences.quickTranslationEnabled, actions.updateQuickEnabled)
    Text("轻点范围")
    ChoiceRow {
        FilterChip(
            selected = preferences.quickTranslationUnit == QuickTranslationUnit.SENTENCE,
            onClick = { actions.updateQuickUnit(QuickTranslationUnit.SENTENCE) },
            label = { Text("句子") }
        )
        FilterChip(
            selected = preferences.quickTranslationUnit == QuickTranslationUnit.PARAGRAPH,
            onClick = { actions.updateQuickUnit(QuickTranslationUnit.PARAGRAPH) },
            label = { Text("段落") }
        )
    }
    Text(
        "开启时，点正文翻译；左右滑动和阅读器底部按钮仍可翻页。关闭后恢复正文边缘点按翻页。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    ToggleRow(
        "轻点翻译时禁用点按翻页",
        preferences.disablesTapPageTurnsDuringQuickTranslation,
        actions.updateDisablesTapPageTurns
    )
    Text("译文位置")
    ChoiceRow {
        FilterChip(
            selected = preferences.displayMode == TranslationDisplayMode.NEAR_SELECTION,
            onClick = { actions.updateDisplayMode(TranslationDisplayMode.NEAR_SELECTION) },
            label = { Text("跟随选区") }
        )
        FilterChip(
            selected = preferences.displayMode == TranslationDisplayMode.TOP_BANNER,
            onClick = { actions.updateDisplayMode(TranslationDisplayMode.TOP_BANNER) },
            label = { Text("顶部悬浮") }
        )
    }
    ToggleRow("翻译完成时触感反馈", preferences.translationHapticsEnabled, actions.updateTranslationHaptics)
    ToggleRow("短暂失败自动重试一次", preferences.automaticRetryEnabled, actions.updateAutomaticRetry)
    Text("备用翻译服务")
    ChoiceRow {
        TranslationFallbackMode.entries.forEach { fallback ->
            FilterChip(
                selected = preferences.fallbackMode == fallback,
                enabled = fallback == TranslationFallbackMode.NONE ||
                    fallback.providerMode != preferences.providerMode,
                onClick = { actions.updateFallbackMode(fallback) },
                label = {
                    Text(
                        when (fallback) {
                            TranslationFallbackMode.NONE -> "不自动切换"
                            TranslationFallbackMode.ON_DEVICE -> "Android 本机"
                            TranslationFallbackMode.DIRECT_API -> "AI API"
                            TranslationFallbackMode.BACKEND_PROXY -> "AI 代理"
                        }
                    )
                }
            )
        }
    }

    Text("翻译提示词", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    OutlinedTextField(
        value = preferences.translationPromptTemplate,
        onValueChange = actions.updatePrompt,
        modifier = Modifier.fillMaxWidth(),
        minLines = 4,
        label = { Text("支持 {source_language} 与 {target_language}") }
    )
    TextButton(onClick = { actions.updatePrompt(TranslationPreferences.DEFAULT_TRANSLATION_PROMPT) }) {
        Text("恢复默认提示词")
    }
    Text("句子结构分析提示词", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    OutlinedTextField(
        value = preferences.grammarAnalysisPromptTemplate,
        onValueChange = actions.updateGrammarPrompt,
        modifier = Modifier.fillMaxWidth(),
        minLines = 4,
        label = { Text("AI 语法与语境解释") }
    )
    TextButton(onClick = { actions.updateGrammarPrompt(TranslationPreferences.DEFAULT_GRAMMAR_PROMPT) }) {
        Text("恢复默认解析提示词")
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    // One bordered section per group, so the settings page reads as distinct
    // cards instead of one continuous list of controls.
    JerreaderSection(title = title, content = content)
}

@Composable
private fun SettingsNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ChoiceRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    JerreaderRow(label) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
