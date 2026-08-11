package com.jerreader.android.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
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
import com.jerreader.unified.design.JerreaderCopy
import com.jerreader.unified.design.JerreaderAppearanceMode
import com.jerreader.unified.domain.LanguageCode
import com.jerreader.unified.translation.DirectAIProvider
import com.jerreader.unified.translation.QuickTranslationUnit
import com.jerreader.unified.translation.TranslationPreferences
import com.jerreader.unified.translation.TranslationProviderMode
import com.jerreader.unified.translation.TranslationFallbackMode
import com.jerreader.unified.translation.TranslationDisplayMode
import com.jerreader.unified.translation.TranslationSourceChoice
import com.jerreader.android.settings.AndroidAppPreferences
import com.jerreader.android.settings.AppThemeChoice
import com.jerreader.unified.ui.AppGuideScreen
import com.jerreader.unified.ui.JerreaderIcon
import com.jerreader.unified.ui.JerreaderRow
import com.jerreader.unified.ui.ReaderColorPresetEditor
import com.jerreader.unified.ui.SettingsActionRow
import com.jerreader.unified.ui.SettingsDisclosureGroup
import com.jerreader.unified.ui.SettingsFootnote
import com.jerreader.unified.ui.SettingsGroup
import com.jerreader.unified.ui.SettingsInsetRow
import com.jerreader.unified.ui.SettingsLabelRow
import com.jerreader.unified.ui.SettingsMenuRow
import com.jerreader.unified.ui.SettingsNavigationRow
import com.jerreader.unified.ui.SettingsPage
import com.jerreader.unified.ui.SettingsSegmentedRow
import com.jerreader.unified.ui.SettingsSliderRow
import com.jerreader.unified.ui.SettingsSwatchRow
import com.jerreader.unified.ui.SettingsToggleRow
import com.jerreader.unified.ui.SettingsValueRow
import com.jerreader.unified.ui.SettingsWarningNote
import com.jerreader.unified.ui.jerreaderColors
import com.jerreader.unified.library.ReaderAppearance
import com.jerreader.unified.library.ReaderColorPreset
import com.jerreader.unified.library.ReaderColorPresetStore
import com.jerreader.unified.library.ReaderFontOption
import com.jerreader.unified.library.ReaderTextOrientation
import com.jerreader.unified.library.ReaderThemeOption
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
    val updatePreferAIWhenConfigured: (Boolean) -> Unit,
    val updatePrompt: (String) -> Unit,
    val updateGrammarPrompt: (String) -> Unit
)

/** Which settings page is showing; mirrors the iOS NavigationStack. */
enum class SettingsRoute {
    ROOT,
    THEME,
    READER_DEFAULTS,
    TRANSLATION,
    BACKUP,

    /** The backup centre reached from an empty shelf: opens the picker itself. */
    BACKUP_IMPORT,
    GUIDE,
    PRIVACY,
    DICTIONARY_SOURCES
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    graph: com.jerreader.android.AppGraph,
    appPreferences: AndroidAppPreferences,
    route: SettingsRoute,
    onRouteChanged: (SettingsRoute) -> Unit,
    onThemeChanged: (AppThemeChoice) -> Unit,
    onAppearanceModeChanged: (JerreaderAppearanceMode) -> Unit,
    onDefaultReaderChanged: (ReaderAppearance) -> Unit,
    onShowProgressChanged: (Boolean) -> Unit,
    onLearningModuleVisibleChanged: (Boolean) -> Unit,
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
    val back = { onRouteChanged(SettingsRoute.ROOT) }

    when (route) {
        SettingsRoute.ROOT -> SettingsPage(title = JerreaderCopy.settingsTitle, bottomBar = bottomBar) {
            SettingsGroup("偏好", icon = JerreaderIcon.SLIDERS) {
                SettingsToggleRow(
                    title = "显示学习模块",
                    checked = appPreferences.learningModuleVisible,
                    onCheckedChange = onLearningModuleVisibleChanged,
                    detail = if (appPreferences.learningModuleVisible) {
                        "主导航显示学习；生词可进入日语复习闭环"
                    } else {
                        "学习入口已隐藏，已有数据仍保留"
                    },
                    icon = JerreaderIcon.CHECKLIST
                )
                SettingsNavigationRow(
                    title = "界面主题",
                    detail = "选择 App 界面的整体色系",
                    icon = JerreaderIcon.PALETTE,
                    onClick = { onRouteChanged(SettingsRoute.THEME) }
                )
                SettingsNavigationRow(
                    title = "默认阅读排版",
                    detail = "字体、间距、背景、翻页与日文版式",
                    icon = JerreaderIcon.TEXT_FORMAT,
                    onClick = { onRouteChanged(SettingsRoute.READER_DEFAULTS) }
                )
                SettingsNavigationRow(
                    title = "翻译与 AI",
                    detail = "服务商、三语互译、提示词与交互",
                    icon = JerreaderIcon.SPEECH_BUBBLE,
                    onClick = { onRouteChanged(SettingsRoute.TRANSLATION) },
                    showDivider = false
                )
            }
            SettingsGroup("支持", icon = JerreaderIcon.INFO) {
                // Row order follows iOS: 操作指南 first, because it is what a new
                // installation needs and 备份与恢复 is what an old one does.
                SettingsNavigationRow(
                    title = "操作指南",
                    detail = "导入、阅读、PDF/文字识别与 API 配置",
                    icon = JerreaderIcon.QUESTION,
                    onClick = { onRouteChanged(SettingsRoute.GUIDE) }
                )
                SettingsNavigationRow(
                    title = "备份与恢复",
                    detail = "自选文件夹，备份书架、进度、生词与批注",
                    icon = JerreaderIcon.SHIELD,
                    onClick = { onRouteChanged(SettingsRoute.BACKUP) }
                )
                SettingsNavigationRow(
                    title = "数据与隐私",
                    detail = "本机数据、在线请求、密钥与词典来源",
                    icon = JerreaderIcon.SHIELD,
                    onClick = { onRouteChanged(SettingsRoute.PRIVACY) },
                    showDivider = false
                )
            }
            SettingsGroup("关于", icon = JerreaderIcon.INFO) {
                SettingsValueRow("Jerreader", versionName)
                SettingsValueRow("最低系统", "Android 6.0")
                SettingsValueRow("作者", "WANG ZIRUI")
            }
        }

        SettingsRoute.THEME -> SettingsPage(title = "界面主题", onBack = back) {
            val systemIsDark = isSystemInDarkTheme()
            val dark = appPreferences.appearanceMode.isDark(systemIsDark)
            SettingsGroup(
                title = "明暗模式",
                icon = JerreaderIcon.PALETTE,
                footer = "只影响 App 界面，不会改动手机的系统设置。" +
                    "阅读页的深浅仍由「默认阅读排版」里的纸张主题决定。"
            ) {
                JerreaderAppearanceMode.entries.forEach { mode ->
                    val swatchDark = when (mode) {
                        JerreaderAppearanceMode.SYSTEM -> systemIsDark
                        JerreaderAppearanceMode.LIGHT -> false
                        JerreaderAppearanceMode.DARK -> true
                    }
                    SettingsSwatchRow(
                        title = mode.title,
                        detail = mode.detail,
                        swatch = jerreaderColors(appPreferences.theme, swatchDark).paper,
                        selected = appPreferences.appearanceMode == mode,
                        onClick = { onAppearanceModeChanged(mode) },
                        showDivider = mode != JerreaderAppearanceMode.entries.last()
                    )
                }
            }
            SettingsGroup(
                title = "主题色系",
                icon = JerreaderIcon.PALETTE,
                footer = "色系会同时调整强调色、按钮、卡片与背景，并跟着上面的明暗模式走。" +
                    "阅读页的纸张主题仍在「默认阅读排版」中单独设置。"
            ) {
                AppThemeChoice.entries.forEach { choice ->
                    SettingsSwatchRow(
                        title = choice.title,
                        detail = choice.detail,
                        swatch = jerreaderColors(choice, dark).accent,
                        selected = appPreferences.theme == choice,
                        onClick = { onThemeChanged(choice) },
                        showDivider = choice != AppThemeChoice.entries.last()
                    )
                }
            }
        }

        SettingsRoute.READER_DEFAULTS -> SettingsPage(title = "默认阅读排版", onBack = back) {
            SettingsGroup(
                title = "应用范围",
                icon = JerreaderIcon.CHECKLIST,
                footer = "只覆盖阅读排版，不修改阅读位置、书签、划线、批注或笔记。"
            ) {
                SettingsToggleRow(
                    "同步应用到现有全部书籍",
                    appPreferences.applyReaderDefaultsToExistingBooks,
                    onApplyDefaultsAutomaticallyChanged
                )
                SettingsFootnote(
                    if (appPreferences.applyReaderDefaultsToExistingBooks) {
                        "已开启：本页每次修改都会立即同步到书架中的现有书籍；" +
                            "单本书之后仍可单独调整。"
                    } else {
                        "关闭时只作为新导入书籍的默认值；也可用下方按钮手动应用一次。"
                    },
                    showDivider = !appPreferences.applyReaderDefaultsToExistingBooks
                )
                if (!appPreferences.applyReaderDefaultsToExistingBooks) {
                    SettingsActionRow(
                        "立即应用到书架中的现有书籍",
                        onClick = onApplyDefaultsToExistingBooks,
                        showDivider = false
                    )
                }
            }
            ReaderDefaultsSections(
                appearance = appPreferences.defaultReaderAppearance,
                onChange = onDefaultReaderChanged,
                showsProgress = appPreferences.showReadingProgress,
                onShowProgressChanged = onShowProgressChanged,
                colorPresets = appPreferences.colorPresets,
                onSaveColorPreset = { name ->
                    graph.appSettings.saveColorPreset(
                        name,
                        appPreferences.defaultReaderAppearance
                    )
                },
                onDeleteColorPreset = graph.appSettings::removeColorPreset
            )
        }

        SettingsRoute.TRANSLATION -> SettingsPage(title = "翻译与 AI", onBack = back) {
            TranslationSettings(
                preferences = preferences,
                directApiKey = directApiKey,
                backendToken = backendToken,
                actions = actions,
                onTestConnection = onTestConnection
            )
        }

        SettingsRoute.BACKUP -> BackupCenterScreen(graph = graph, onBack = back)

        SettingsRoute.BACKUP_IMPORT -> BackupCenterScreen(
            graph = graph,
            onBack = back,
            startsWithArchivePicker = true
        )

        SettingsRoute.GUIDE -> AppGuideScreen(onBack = back)

        SettingsRoute.PRIVACY -> SettingsPage(title = "数据与隐私", onBack = back) {
            SettingsGroup(
                title = "本机数据",
                icon = JerreaderIcon.SHIELD,
                footer = "密钥不会写入书籍、日志或 APK。使用 Android 本机翻译时，" +
                    "不会调用你配置的第三方 AI 服务。"
            ) {
                SettingsLabelRow(
                    "书籍、书架与学习记录默认只保存在本机",
                    JerreaderIcon.BOOKS
                )
                SettingsLabelRow(
                    "API Key 使用 Android Keystore 加密保存",
                    JerreaderIcon.LOCK
                )
                SettingsLabelRow(
                    "在线 AI 只接收你主动点按或框选的文字",
                    JerreaderIcon.CHECK_CIRCLE
                )
                SettingsLabelRow(
                    "导入的书籍是只读输入，阅读与翻译不会改写它",
                    JerreaderIcon.DICTIONARY
                )
                SettingsLabelRow(
                    "受 DRM 保护的出版物会被拒绝打开，不做任何解密",
                    JerreaderIcon.CLOSE,
                    showDivider = false
                )
            }
            SettingsGroup(
                title = "写出副本",
                icon = JerreaderIcon.DOWNLOAD,
                footer = "只有你在「备份与恢复」中选择文件夹后，才会把书架与学习记录写到本机以外的位置。"
            ) {
                SettingsLabelRow(
                    "备份需要你先授权一个文件夹",
                    JerreaderIcon.DOWNLOAD,
                    showDivider = false
                )
            }
            SettingsGroup(
                title = "词典",
                icon = JerreaderIcon.DICTIONARY,
                footer = "可查看离线数据版本、许可和联网词典的隐私边界。"
            ) {
                SettingsNavigationRow(
                    title = "词典来源",
                    detail = "离线 JMdict 数据、联网词典与许可",
                    icon = JerreaderIcon.DICTIONARY,
                    onClick = { onRouteChanged(SettingsRoute.DICTIONARY_SOURCES) },
                    showDivider = false
                )
            }
        }

        SettingsRoute.DICTIONARY_SOURCES -> SettingsPage(title = "词典来源", onBack = back) {
            SettingsGroup(
                title = "离线日语词典",
                icon = JerreaderIcon.DICTIONARY,
                footer = "离线词条由 EDRDG 的 JMdict 数据生成，提供日语读音、词性和英文释义；" +
                    "联网时仍优先查询中文维基词典。"
            ) {
                SettingsValueRow("数据", "JMdict 常用词")
                SettingsValueRow("词典日期", "2026-07-20")
                SettingsValueRow("许可", "CC BY-SA 4.0")
                SettingsNavigationRow(
                    title = "JMdict / EDICT Dictionary Project",
                    detail = "打开 EDRDG 项目页",
                    icon = JerreaderIcon.DICTIONARY,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://www.edrdg.org/wiki/JMdict-EDICT_Dictionary_Project.html")
                                )
                            )
                        }
                    },
                    showDivider = false
                )
            }
            SettingsGroup(
                title = "联网词典",
                icon = JerreaderIcon.DICTIONARY,
                footer = "联网查询只发送用户主动查询的词语及必要语境。"
            ) {
                SettingsNavigationRow(
                    title = "中文维基词典",
                    detail = "打开词典网站",
                    icon = JerreaderIcon.DICTIONARY,
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://zh.wiktionary.org/"))
                            )
                        }
                    },
                    showDivider = false
                )
            }
        }
    }
}

/**
 * The reading-layout defaults, laid out as the iOS `GlobalReaderDefaultsView`
 * form rather than the reader's own appearance panel: 翻页与导航, 字体与字号,
 * 间距, 背景 and 日文原版排版, each its own grouped section with sliders for the
 * measurements instead of plus/minus steppers.
 */
@Composable
private fun ReaderDefaultsSections(
    appearance: ReaderAppearance,
    onChange: (ReaderAppearance) -> Unit,
    showsProgress: Boolean,
    onShowProgressChanged: (Boolean) -> Unit,
    colorPresets: List<ReaderColorPreset> = emptyList(),
    onSaveColorPreset: ((String) -> Unit)? = null,
    onDeleteColorPreset: ((String) -> Unit)? = null
) {
    SettingsGroup(
        title = "翻页与导航",
        icon = JerreaderIcon.BOOKS,
        footer = "当前排版只会自动用于以后导入的新书；阅读位置、书签与批注不受影响。"
    ) {
        SettingsSegmentedRow(
            title = "翻页方式",
            options = listOf(false to "分页", true to "滚动"),
            selected = appearance.scroll,
            onSelect = { onChange(appearance.copy(scroll = it)) }
        )
        SettingsFootnote(
            if (appearance.scroll) {
                "整章连续滚动，适合长段落与论文。"
            } else {
                "按屏分页，翻页位置与纸书一致。"
            }
        )
        SettingsToggleRow(
            "显示可拖动阅读进度",
            showsProgress,
            onShowProgressChanged,
            showDivider = false
        )
    }

    SettingsGroup(title = "字体与字号", icon = JerreaderIcon.TEXT_FORMAT) {
        SettingsMenuRow(
            title = "字体",
            options = listOf(
                ReaderFontOption.PUBLICATION to "原书",
                ReaderFontOption.SERIF to "衬线",
                ReaderFontOption.SANS_SERIF to "黑体"
            ),
            selected = appearance.font,
            onSelect = { onChange(appearance.copy(font = it)) }
        )
        SettingsSliderRow(
            title = "字号",
            value = appearance.fontScale.toFloat(),
            valueLabel = "${(appearance.fontScale * 100).toInt()}%",
            range = 0.8f..2.0f,
            steps = 0,
            onValueChange = { onChange(appearance.copy(fontScale = roundedTenth(it))) },
            showDivider = false
        )
    }

    SettingsGroup(title = "间距", icon = JerreaderIcon.SORT) {
        SettingsSliderRow(
            title = "行间距",
            value = appearance.lineHeight.toFloat(),
            valueLabel = oneDecimal(appearance.lineHeight),
            range = 1.0f..2.2f,
            steps = 0,
            onValueChange = { onChange(appearance.copy(lineHeight = roundedTenth(it))) }
        )
        SettingsSliderRow(
            title = "段间距",
            value = appearance.paragraphSpacing.toFloat(),
            valueLabel = oneDecimal(appearance.paragraphSpacing),
            range = 0f..2.0f,
            steps = 0,
            onValueChange = { onChange(appearance.copy(paragraphSpacing = roundedTenth(it))) }
        )
        SettingsSliderRow(
            title = "页边距",
            value = appearance.pageMargins.toFloat(),
            valueLabel = oneDecimal(appearance.pageMargins),
            range = 0.5f..2.0f,
            steps = 0,
            onValueChange = { onChange(appearance.copy(pageMargins = roundedTenth(it))) },
            showDivider = false
        )
    }

    SettingsGroup(
        title = "背景",
        icon = JerreaderIcon.PALETTE,
        footer = "留空表示使用预设背景；自定义颜色填 #RRGGBB。"
    ) {
        SettingsMenuRow(
            title = "预设背景",
            options = listOf(
                ReaderThemeOption.LIGHT to "浅色",
                ReaderThemeOption.SEPIA to "护眼",
                ReaderThemeOption.COOL_GRAY to "冷灰",
                ReaderThemeOption.DARK to "深色"
            ),
            selected = appearance.theme,
            onSelect = {
                // Same rule as the reader's own panel: a leftover custom
                // background would keep winning and make the choice look inert.
                onChange(
                    appearance.copy(
                        theme = it,
                        customBackgroundHex = "",
                        customSelectionColorHex = ""
                    )
                )
            }
        )
        SettingsInsetRow {
            OutlinedTextField(
                value = appearance.customBackgroundHex,
                onValueChange = {
                    onChange(appearance.copy(customBackgroundHex = normalizedHex(it)))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("自定义背景颜色") },
                singleLine = true
            )
        }
        SettingsInsetRow(showDivider = onSaveColorPreset != null || colorPresets.isNotEmpty()) {
            OutlinedTextField(
                value = appearance.customSelectionColorHex,
                onValueChange = {
                    onChange(appearance.copy(customSelectionColorHex = normalizedHex(it)))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("自定义选区颜色") },
                singleLine = true
            )
        }
        if (onSaveColorPreset != null || colorPresets.isNotEmpty()) {
            SettingsInsetRow(showDivider = false) {
                ReaderColorPresetEditor(
                    presets = colorPresets,
                    appearance = appearance,
                    onApply = { onChange(ReaderColorPresetStore.applied(appearance, it)) },
                    onSave = onSaveColorPreset,
                    onDelete = onDeleteColorPreset
                )
            }
        }
    }

    SettingsGroup(title = "日文原版排版", icon = JerreaderIcon.TEXT_FORMAT) {
        SettingsSegmentedRow(
            title = "默认版式",
            // See the reader's own 文字方向 row: 竖排 is not offered any more.
            options = listOf(
                ReaderTextOrientation.PUBLICATION to "原书",
                ReaderTextOrientation.HORIZONTAL to "横排"
            ),
            selected = appearance.orientation,
            onSelect = { onChange(appearance.copy(orientation = it)) }
        )
        SettingsFootnote(
            when (appearance.orientation) {
                ReaderTextOrientation.PUBLICATION -> "跟随出版物自身声明的横竖排与翻页方向。"
                ReaderTextOrientation.HORIZONTAL -> "强制横排，章节间与章节内都从左向右。"
                ReaderTextOrientation.VERTICAL -> "强制竖排，从右向左翻页。"
            },
            showDivider = false
        )
    }
}

/** The sliders step by 0.1; floating point drift would show up in the label. */
private fun roundedTenth(value: Float): Double = kotlin.math.round(value * 10.0) / 10.0

private fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * 10.0).toInt()
    return "${tenths / 10}.${tenths % 10}"
}

private fun normalizedHex(value: String): String {
    val trimmed = value.trim().uppercase()
    if (trimmed.isEmpty()) return ""
    val digits = trimmed.removePrefix("#").take(6).filter { it in "0123456789ABCDEF" }
    return "#$digits"
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

    val quick = preferences.quickTranslationEnabled
    val sameLanguage = preferences.sourceChoice.language == preferences.targetLanguage

    // iOS keeps every translation control in one Form section headed 「翻译」;
    // the Android page used to scatter bare headings and chips over the canvas.
    SettingsGroup(title = "翻译", icon = JerreaderIcon.SPEECH_BUBBLE) {
        SettingsToggleRow("轻点正文快速翻译", quick, actions.updateQuickEnabled)
        SettingsSegmentedRow(
            title = "翻译范围",
            options = listOf(
                QuickTranslationUnit.SENTENCE to "句子",
                QuickTranslationUnit.PARAGRAPH to "段落"
            ),
            selected = preferences.quickTranslationUnit,
            onSelect = actions.updateQuickUnit,
            enabled = quick
        )
        SettingsToggleRow(
            "轻点翻译时禁用点按翻页",
            preferences.disablesTapPageTurnsDuringQuickTranslation,
            actions.updateDisablesTapPageTurns,
            enabled = quick
        )
        SettingsFootnote(
            if (quick) {
                "开启时，点正文翻译；左右滑动和阅读器底部按钮仍可翻页。"
            } else {
                "关闭后，轻点正文只控制阅读界面；长按和拖动选区仍可翻译。"
            }
        )

        SettingsMenuRow(
            title = "翻译服务",
            options = listOf(
                TranslationProviderMode.ON_DEVICE to "Android 本机翻译",
                TranslationProviderMode.DIRECT_API to "AI API（直接）",
                TranslationProviderMode.BACKEND_PROXY to "AI 代理"
            ),
            selected = preferences.providerMode,
            onSelect = {
                testStatus = null
                actions.updateProviderMode(it)
            }
        )
        SettingsMenuRow(
            title = "原文语言",
            options = TranslationSourceChoice.entries.map { it to sourceLanguageTitle(it) },
            selected = preferences.sourceChoice,
            onSelect = actions.updateSourceChoice
        )
        SettingsMenuRow(
            title = "目标语言",
            options = LanguageCode.entries.map { it to targetLanguageTitle(it) },
            selected = preferences.targetLanguage,
            onSelect = actions.updateTargetLanguage
        )
        if (sameLanguage) {
            SettingsWarningNote("原文和目标语言不能相同，请选择另一个目标语言或使用自动识别。")
        } else {
            SettingsFootnote("支持中文、英语与日语之间任意方向互译；自动识别会结合选中文字和书籍语言。")
        }

        SettingsMenuRow(
            title = "译文位置",
            options = listOf(
                TranslationDisplayMode.NEAR_SELECTION to "跟随选区",
                TranslationDisplayMode.TOP_BANNER to "顶部悬浮"
            ),
            selected = preferences.displayMode,
            onSelect = actions.updateDisplayMode
        )
        SettingsFootnote(
            when (preferences.displayMode) {
                TranslationDisplayMode.NEAR_SELECTION -> "译文卡片贴着选中的文字出现，方便对照原文。"
                TranslationDisplayMode.TOP_BANNER -> "译文固定在页面顶部，不遮挡正文。"
            }
        )

        SettingsToggleRow(
            "翻译完成时触感反馈",
            preferences.translationHapticsEnabled,
            actions.updateTranslationHaptics
        )
        SettingsToggleRow(
            "短暂失败自动重试一次",
            preferences.automaticRetryEnabled,
            actions.updateAutomaticRetry
        )
        if (preferences.providerMode == TranslationProviderMode.ON_DEVICE) {
            SettingsToggleRow(
                "配好 AI 后优先用 AI 翻译",
                preferences.preferAIWhenConfigured,
                actions.updatePreferAIWhenConfigured
            )
            SettingsFootnote(
                if (preferences.preferAIWhenConfigured) {
                    "填好 API Key 或代理地址后，书里轻点翻译会直接用 AI；本机模型退为离线时的备用，" +
                        "断网也能继续读。AI 请求会消耗你自己的额度。"
                } else {
                    "书里轻点翻译只用本机模型。本机模型胜在离线，长句的译文会比 AI 生硬不少。"
                }
            )
        }
        SettingsMenuRow(
            title = "备用翻译服务",
            options = TranslationFallbackMode.entries
                .filter {
                    it == TranslationFallbackMode.NONE ||
                        it.providerMode != preferences.providerMode
                }
                .map { it to fallbackTitle(it) },
            selected = preferences.fallbackMode,
            onSelect = actions.updateFallbackMode
        )
        SettingsFootnote(
            if (preferences.fallbackMode == TranslationFallbackMode.NONE) {
                "主服务失败时直接报错，不会改用其他服务。"
            } else {
                "主服务失败时自动改用${fallbackTitle(preferences.fallbackMode)}再试一次。"
            },
            showDivider = true
        )

        when (preferences.providerMode) {
            TranslationProviderMode.ON_DEVICE -> SettingsFootnote(
                "无需账号或 API Key。首次使用某个语言方向时会下载约 30 MB 的语言模型，" +
                    "之后在本机执行翻译。",
                showDivider = false
            )

            TranslationProviderMode.DIRECT_API -> {
                SettingsMenuRow(
                    title = "AI 服务商",
                    options = DirectAIProvider.entries.map { it to it.displayName },
                    selected = preferences.directProvider,
                    onSelect = {
                        testStatus = null
                        actions.updateDirectProvider(it)
                    }
                )
                SettingsInsetRow {
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
                }
                SettingsFootnote(
                    if (preferences.directApiKeyPresent) {
                        "配置已保存到本机安全存储。"
                    } else {
                        "粘贴 API Key 后即可翻译。"
                    }
                )
                ConnectionTestRow(
                    isTesting = isTesting,
                    status = testStatus,
                    onTest = {
                        scope.launch {
                            isTesting = true
                            testStatus = onTestConnection()
                            isTesting = false
                        }
                    }
                )
                SettingsDisclosureGroup("高级配置") {
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
                    }
                    SettingsNote(
                        if (preferences.directProvider.usesCustomEndpoint) {
                            "兼容服务需要填写完整的 HTTPS Chat Completions 地址。"
                        } else {
                            "请求地址：${preferences.directProvider.defaultEndpoint}"
                        }
                    )
                }
                SettingsDisclosureGroup("直接 API 说明", showDivider = false) {
                    SettingsNote(
                        "选择服务商后粘贴对应 API Key 即可。官方请求地址与推荐模型会自动填写；" +
                            "只有兼容服务需要手动填写完整 HTTPS 地址。"
                    )
                    SettingsNote(
                        "Key 按服务商分别加密保存在本机。直接调用会把本次选中文字发送给所选服务商，" +
                            "并可能产生少量 API 费用。"
                    )
                }
            }

            TranslationProviderMode.BACKEND_PROXY -> {
                SettingsInsetRow {
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
                            testStatus = null
                            actions.updateBackendToken(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("代理访问凭据（可选）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
                ConnectionTestRow(
                    isTesting = isTesting,
                    status = testStatus,
                    onTest = {
                        scope.launch {
                            isTesting = true
                            testStatus = onTestConnection()
                            isTesting = false
                        }
                    }
                )
                SettingsDisclosureGroup("AI 代理接入说明", showDivider = false) {
                    SettingsNote(
                        "代理凭据只加密保存在本机。请填写你自己控制的 HTTPS 代理，" +
                            "不要直接填写供应商原始 API Key。"
                    )
                    SettingsNote(
                        "代理需接受 text、可选 context、sourceLanguage、targetLanguage、model，" +
                            "并返回 translatedText 或 translation。"
                    )
                }
            }
        }
    }

    if (preferences.providerMode != TranslationProviderMode.ON_DEVICE) {
        SettingsGroup(
            title = "提示词与 AI 行为",
            icon = JerreaderIcon.TEXT_FORMAT,
            footer = "可使用 {source_language}、{target_language} 占位符。" +
                "修改提示词后会生成新的缓存版本，不会误用旧结果。"
        ) {
            SettingsDisclosureGroup("翻译提示词") {
                OutlinedTextField(
                    value = preferences.translationPromptTemplate,
                    onValueChange = actions.updatePrompt,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4
                )
                TextButton(
                    onClick = {
                        actions.updatePrompt(TranslationPreferences.DEFAULT_TRANSLATION_PROMPT)
                    }
                ) { Text("恢复默认提示词") }
            }
            SettingsDisclosureGroup("句子结构分析提示词", showDivider = false) {
                OutlinedTextField(
                    value = preferences.grammarAnalysisPromptTemplate,
                    onValueChange = actions.updateGrammarPrompt,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4
                )
                TextButton(
                    onClick = {
                        actions.updateGrammarPrompt(TranslationPreferences.DEFAULT_GRAMMAR_PROMPT)
                    }
                ) { Text("恢复默认解析提示词") }
            }
        }
    }
}

@Composable
private fun ConnectionTestRow(isTesting: Boolean, status: String?, onTest: () -> Unit) {
    SettingsActionRow(
        title = if (isTesting) "测试中…" else "测试连接",
        onClick = onTest,
        enabled = !isTesting,
        showDivider = status == null
    )
    status?.let { SettingsFootnote(it) }
}

private fun sourceLanguageTitle(choice: TranslationSourceChoice): String = when (choice) {
    TranslationSourceChoice.AUTOMATIC -> "自动识别"
    TranslationSourceChoice.JAPANESE -> "日语"
    TranslationSourceChoice.ENGLISH -> "英语"
    TranslationSourceChoice.CHINESE_SIMPLIFIED -> "简体中文"
}

private fun targetLanguageTitle(language: LanguageCode): String = when (language) {
    LanguageCode.CHINESE_SIMPLIFIED -> "简体中文"
    LanguageCode.ENGLISH -> "英语"
    LanguageCode.JAPANESE -> "日语"
}

private fun fallbackTitle(fallback: TranslationFallbackMode): String = when (fallback) {
    TranslationFallbackMode.NONE -> "不自动切换"
    TranslationFallbackMode.ON_DEVICE -> "Android 本机翻译"
    TranslationFallbackMode.DIRECT_API -> "AI API"
    TranslationFallbackMode.BACKEND_PROXY -> "AI 代理"
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
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    JerreaderRow(label) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
