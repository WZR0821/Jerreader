package com.jerreader.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.dp
import com.jerreader.android.R
import com.jerreader.android.translation.AndroidTranslationSettingsStore
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.translation.QuickTranslationUnit
import com.jerreader.shared.translation.TranslationPreferences
import com.jerreader.shared.translation.TranslationProviderMode
import com.jerreader.shared.translation.TranslationFallbackMode
import com.jerreader.shared.translation.TranslationDisplayMode
import com.jerreader.shared.translation.TranslationSourceChoice
import com.jerreader.android.settings.AndroidAppPreferences
import com.jerreader.android.settings.AppThemeChoice
import com.jerreader.shared.ui.AppGuideScreen
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderListDivider
import com.jerreader.shared.ui.JerreaderNavigationChevron
import com.jerreader.shared.ui.JerreaderRowChevron
import com.jerreader.shared.ui.JerreaderRowSeparator
import com.jerreader.shared.ui.LocalJerreaderColors
import com.jerreader.shared.ui.SettingsActionRow
import com.jerreader.shared.ui.SettingsDisclosureGroup
import com.jerreader.shared.ui.SettingsFootnote
import com.jerreader.shared.ui.SettingsGroup
import com.jerreader.shared.ui.SettingsInsetRow
import com.jerreader.shared.ui.SettingsLabelRow
import com.jerreader.shared.ui.SettingsValueRow
import com.jerreader.shared.ui.SettingsBareSliderRow
import com.jerreader.shared.ui.SettingsMenuRow
import com.jerreader.shared.ui.SettingsPage
import com.jerreader.shared.ui.SettingsSegmentedRow
import com.jerreader.shared.ui.SettingsSwatchRow
import com.jerreader.shared.ui.SettingsToggleRow
import com.jerreader.shared.ui.SettingsWarningNote
import com.jerreader.shared.ui.jerreaderColors
import com.jerreader.shared.library.ReaderAppearance
import com.jerreader.shared.library.ReaderTextOrientation
import com.jerreader.shared.library.ReaderThemeOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class TranslationSettingsActions(
    val updateProviderMode: (TranslationProviderMode) -> Unit,
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

/** Which settings page is showing; mirrors the iOS NavigationStack. */
enum class SettingsRoute {
    ROOT,
    THEME,
    READER_DEFAULTS,
    TRANSLATION,

    /** AI 大模型, opened from the settings home. */
    AI_MODELS,

    /** AI 大模型, opened from 翻译与 AI: back returns there. */
    TRANSLATION_AI_MODELS,
    OFFLINE,
    BACKUP,

    /** The backup centre reached from an empty shelf: opens the picker itself. */
    BACKUP_IMPORT,
    GUIDE,
    PRIVACY
}

/**
 * @param onTestConnection sends a fixed sentence through the translation
 *   service picked on 翻译与 AI, and returns why it failed, or `null` when it
 *   came back translated.
 */
@Composable
fun SettingsScreen(
    graph: com.jerreader.android.AppGraph,
    appPreferences: AndroidAppPreferences,
    route: SettingsRoute,
    onRouteChanged: (SettingsRoute) -> Unit,
    onThemeChanged: (AppThemeChoice) -> Unit,
    onDefaultReaderChanged: (ReaderAppearance) -> Unit,
    onShowProgressChanged: (Boolean) -> Unit,
    onApplyDefaultsAutomaticallyChanged: (Boolean) -> Unit,
    onApplyDefaultsToExistingBooks: () -> Unit,
    preferences: TranslationPreferences,
    backendToken: String,
    actions: TranslationSettingsActions,
    onTestConnection: suspend () -> String?,
    bottomBar: @Composable () -> Unit,
    bookCount: Int = 0
) {
    BackHandler(enabled = route == SettingsRoute.OFFLINE || route == SettingsRoute.TRANSLATION_AI_MODELS) {
        onRouteChanged(SettingsRoute.TRANSLATION)
    }
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "—" }
    }
    val back = { onRouteChanged(SettingsRoute.ROOT) }
    val requests = remember(graph) { AIProviderRequests.live(graph.configuredTranslationService) }

    when (route) {
        SettingsRoute.ROOT -> SettingsHome(
            store = graph.translationSettings,
            appPreferences = appPreferences,
            preferences = preferences,
            versionName = versionName,
            onRouteChanged = onRouteChanged,
            bottomBar = bottomBar
        )

        SettingsRoute.THEME -> SettingsPage(title = "界面主题", onBack = back, bottomBar = bottomBar) {
            val dark = isSystemInDarkTheme()
            SettingsGroup(
                title = "主题色系",
                icon = JerreaderIcon.PALETTE,
                footer = "色系会同时调整强调色、按钮、卡片与背景，并自动适配系统深色/浅色外观。" +
                    "阅读页的纸张主题仍在“默认阅读排版”中单独设置。"
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

        SettingsRoute.READER_DEFAULTS -> SettingsPage(title = "默认阅读排版", onBack = back, bottomBar = bottomBar) {
            var confirmingApply by remember { mutableStateOf(false) }
            var didApply by remember { mutableStateOf(false) }
            // iOS asks before overwriting every book's own layout, then says so.
            if (confirmingApply) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { confirmingApply = false },
                    title = { Text("将当前默认排版应用到所有现有书籍？") },
                    text = { Text("会覆盖现有书籍各自保存的排版，但不会修改阅读位置、书签、划线、批注或笔记。") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            confirmingApply = false
                            onApplyDefaultsToExistingBooks()
                            didApply = true
                        }) { Text("应用到 $bookCount 本书") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { confirmingApply = false }) { Text("取消") }
                    }
                )
            }
            if (didApply) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { didApply = false },
                    title = { Text("排版已应用") },
                    text = { Text("以后仍可在每本书的阅读设置中单独调整。") },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = { didApply = false }) { Text("好") }
                    }
                )
            }
            SettingsGroup(
                title = "应用范围",
                icon = JerreaderIcon.CHECKLIST
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
                if (appPreferences.applyReaderDefaultsToExistingBooks) {
                    SettingsLabelRow(
                        "已自动同步到 $bookCount 本现有书籍",
                        icon = JerreaderIcon.CHECK_CIRCLE,
                        secondary = true
                    )
                } else {
                    SettingsActionRow(
                        "立即应用到书架中的现有书籍",
                        onClick = { confirmingApply = true },
                        showDivider = true,
                        icon = JerreaderIcon.BOOKS
                    )
                }
                SettingsFootnote("只覆盖阅读排版，不会修改阅读位置、书签、划线、批注或笔记。", showDivider = false)
            }
            ReaderDefaultsSections(
                appearance = appPreferences.defaultReaderAppearance,
                onChange = onDefaultReaderChanged,
                appliesToExistingBooks = appPreferences.applyReaderDefaultsToExistingBooks,
                showsProgress = appPreferences.showReadingProgress,
                onShowProgressChanged = onShowProgressChanged
            )
        }

        SettingsRoute.TRANSLATION -> SettingsPage(title = "翻译与 AI", onBack = back, bottomBar = bottomBar) {
            TranslationSettingsContent(
                store = graph.translationSettings,
                preferences = preferences,
                backendToken = backendToken,
                actions = actions,
                onTestConnection = onTestConnection,
                showsQuickSentenceToggle = true,
                onOpenAIModels = { onRouteChanged(SettingsRoute.TRANSLATION_AI_MODELS) },
                onOpenOffline = { onRouteChanged(SettingsRoute.OFFLINE) }
            )
        }

        SettingsRoute.AI_MODELS -> AIModelsScreen(
            store = graph.translationSettings,
            requests = requests,
            backTitle = "设置",
            onBack = back,
            bottomBar = bottomBar
        )

        SettingsRoute.TRANSLATION_AI_MODELS -> AIModelsScreen(
            store = graph.translationSettings,
            requests = requests,
            backTitle = "翻译与 AI",
            onBack = { onRouteChanged(SettingsRoute.TRANSLATION) },
            bottomBar = bottomBar
        )

        SettingsRoute.OFFLINE -> Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { com.jerreader.shared.ui.JerreaderNavigationBar("离线资源", "翻译与 AI", { onRouteChanged(SettingsRoute.TRANSLATION) }) },
            bottomBar = bottomBar
        ) { padding -> OfflineDictionaryScreen(graph.offlineDictionary, graph.localTranslationService, Modifier.padding(padding)) }

        SettingsRoute.BACKUP -> BackupCenterScreen(graph = graph, onBack = back, bottomBar = bottomBar)

        SettingsRoute.BACKUP_IMPORT -> BackupCenterScreen(
            graph = graph,
            onBack = back,
            bottomBar = bottomBar, startsWithArchivePicker = true
        )

        SettingsRoute.GUIDE -> AppGuideScreen(onBack = back, bottomBar = bottomBar)

        SettingsRoute.PRIVACY -> SettingsPage(title = "数据与隐私", onBack = back, bottomBar = bottomBar) {
            SettingsGroup(
                title = "本机数据",
                icon = JerreaderIcon.SHIELD,
                footer = null
            ) {
                SettingsLabelRow(
                    "书籍、书架与学习记录默认只保存在本机",
                    JerreaderIcon.BOOKS
                )
                SettingsLabelRow(
                    "API Key 只保存在本机安全存储",
                    JerreaderIcon.LOCK
                )
                SettingsLabelRow(
                    "在线 AI 只接收你主动选择的文字与必要上下文",
                    JerreaderIcon.SHIELD
                )
                SettingsLabelRow(
                    "导入的书籍是只读输入，阅读与翻译不会改写它",
                    JerreaderIcon.DICTIONARY,
                    showDivider = false
                )
            }
            SettingsGroup(
                title = "写出副本",
                icon = JerreaderIcon.DOWNLOAD,
                footer = "密钥不会写入书籍、日志或安装包。备份只写入你选择的位置。"
            ) {
                SettingsLabelRow(
                    "备份需要你先授权一个文件夹",
                    JerreaderIcon.DOWNLOAD,
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
    appliesToExistingBooks: Boolean,
    showsProgress: Boolean,
    onShowProgressChanged: (Boolean) -> Unit
) {
    SettingsGroup(
        title = "翻页与导航",
        icon = JerreaderIcon.BOOKS,
        footer = if (appliesToExistingBooks) "当前排版会同步到现有书籍；阅读位置、书签与批注不受影响。" else "当前排版只会自动用于以后导入的新书。"
    ) {
        SettingsSegmentedRow(
            title = "翻页方式",
            options = listOf(false to "逐页", true to "滚动"),
            selected = appearance.scroll,
            onSelect = { onChange(appearance.copy(scroll = it)) }
        )
        SettingsFootnote(
            if (appearance.scroll) "上下连续滚动阅读" else "每次只显示一页，点击两侧或使用按钮翻页"
        )
        SettingsToggleRow(
            "显示可拖动阅读进度",
            showsProgress,
            onShowProgressChanged,
            showDivider = false
        )
    }

    // iOS shows each value on a `LabeledContent` row with the slider on the
    // row below it; this page keeps one decimal everywhere ("17.0 号").
    SettingsGroup(title = "字体与字号", icon = JerreaderIcon.TEXT_FORMAT) {
        SettingsMenuRow(
            title = "字体",
            options = com.jerreader.android.reader.readerFontOptions(appearance.font),
            selected = appearance.font,
            onSelect = { onChange(appearance.copy(font = it)) }
        )
        val points = com.jerreader.android.reader.readerPointSize(appearance.fontScale)
        SettingsValueRow("字号", "${oneDecimal(points)} 号", compact = false)
        SettingsBareSliderRow(
            value = points.toFloat(),
            range = 12f..32f,
            steps = 39,
            onValueChange = {
                onChange(appearance.copy(fontScale = com.jerreader.android.reader.readerPointSize(it / 17.0) / 17.0))
            },
            accessibilityLabel = "字号",
            showDivider = false
        )
    }

    SettingsGroup(title = "间距", icon = JerreaderIcon.SORT) {
        SettingsValueRow("行间距", oneDecimal(appearance.lineHeight), compact = false)
        SettingsBareSliderRow(
            value = appearance.lineHeight.toFloat().coerceIn(1f, 2.2f),
            range = 1f..2.2f,
            steps = 11,
            onValueChange = { onChange(appearance.copy(lineHeight = roundedTenth(it))) },
            accessibilityLabel = "行间距"
        )
        SettingsValueRow("段间距", oneDecimal(appearance.paragraphSpacing), compact = false)
        SettingsBareSliderRow(
            value = appearance.paragraphSpacing.toFloat().coerceIn(0f, 2f),
            range = 0f..2f,
            steps = 19,
            onValueChange = { onChange(appearance.copy(paragraphSpacing = roundedTenth(it))) },
            accessibilityLabel = "段间距"
        )
        SettingsValueRow("页边距", oneDecimal(appearance.pageMargins), compact = false)
        SettingsBareSliderRow(
            value = appearance.pageMargins.toFloat().coerceIn(0.5f, 2f),
            range = 0.5f..2f,
            steps = 14,
            onValueChange = { onChange(appearance.copy(pageMargins = roundedTenth(it))) },
            accessibilityLabel = "页边距",
            showDivider = false
        )
    }

    SettingsGroup(title = "背景", icon = JerreaderIcon.PALETTE) {
        val customBackground = appearance.customBackgroundHex.isNotEmpty()
        val customSelection = appearance.customSelectionColorHex.isNotEmpty()
        SettingsMenuRow(
            title = "预设背景",
            options = listOf(
                ReaderThemeOption.LIGHT to "浅色",
                ReaderThemeOption.SEPIA to "护眼",
                ReaderThemeOption.COOL_GRAY to "冷灰",
                ReaderThemeOption.DARK to "深色"
            ),
            selected = appearance.theme,
            // As on iOS, a preset replaces any custom background.
            onSelect = { onChange(appearance.copy(theme = it, customBackgroundHex = "")) }
        )
        SettingsToggleRow("自定义背景颜色", customBackground,
            { enabled -> onChange(appearance.copy(customBackgroundHex = if (enabled) "#EEF3F8" else "")) })
        if (customBackground) {
            com.jerreader.android.reader.ReaderColorRow("选择背景颜色", appearance.customBackgroundHex) {
                onChange(appearance.copy(customBackgroundHex = it))
            }
        }
        SettingsToggleRow("自定义选区颜色", customSelection,
            { enabled -> onChange(appearance.copy(customSelectionColorHex = if (enabled) "#317DC2" else "")) },
            showDivider = customSelection)
        if (customSelection) {
            com.jerreader.android.reader.ReaderColorRow(
                "选择选区颜色",
                appearance.customSelectionColorHex,
                showDivider = false
            ) { onChange(appearance.copy(customSelectionColorHex = it)) }
        }
    }

    // iOS offers 横排 and 原版 only, in that order here (the reader sheet puts
    // 原版 first); an old 竖排 default reads as 原版.
    SettingsGroup(title = "日文原版排版", icon = JerreaderIcon.TEXT_FORMAT) {
        val horizontal = appearance.orientation == ReaderTextOrientation.HORIZONTAL
        SettingsSegmentedRow(
            title = "默认版式",
            options = listOf(
                ReaderTextOrientation.HORIZONTAL to "横排",
                ReaderTextOrientation.PUBLICATION to "原版"
            ),
            selected = if (horizontal) ReaderTextOrientation.HORIZONTAL else ReaderTextOrientation.PUBLICATION,
            onSelect = { onChange(appearance.copy(orientation = it)) }
        )
        SettingsFootnote(
            if (horizontal) {
                "将日文竖排转为横排，并统一按从左向右的方向逐页阅读。"
            } else {
                "保留书籍原有的横竖排与翻页方向；日文竖排书通常会从右向左翻页。"
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

// MARK: Settings home

/** Where a row sits in its group, for a group drawn row by row. */
private enum class HomeRowPosition { FIRST, MIDDLE, LAST }

/** A system colour in its light and dark appearance. */
private fun systemColor(dark: Boolean, light: Long, darkValue: Long): Color = Color(if (dark) darkValue else light)

/**
 * The settings home, laid out as the iOS `AppSettingsView`: a brand card, the
 * rows of the system Settings app in three groups, and the credit. It fills
 * one screen exactly: its rows share whatever height a taller phone leaves
 * over, so nothing scrolls and no gap is left above the tab bar. Large text or
 * a short screen still scrolls.
 */
@Composable
private fun SettingsHome(
    store: AndroidTranslationSettingsStore,
    appPreferences: AndroidAppPreferences,
    preferences: TranslationPreferences,
    versionName: String,
    onRouteChanged: (SettingsRoute) -> Unit,
    bottomBar: @Composable () -> Unit
) {
    val providers by store.aiProviders.collectAsState()
    val keyed by store.keyedProviderIDs.collectAsState()
    val hasKey = providers.activeID in keyed
    // The provider in use, or 未设置 while it has no key; orange only when
    // translation already depends on the missing key.
    val aiSummary = if (hasKey) providers.displayName(providers.activeID) else "未设置"
    val aiNeedsKey = !hasKey && preferences.providerMode == TranslationProviderMode.DIRECT_API
    val appearance = appPreferences.defaultReaderAppearance
    val readerSummary = "${readerThemeTitle(appearance.theme)} · ${if (appearance.scroll) "滚动" else "逐页"}"
    val margin = aiPageMargin()
    val dark = aiIsDark()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { SettingsLargeTitle("设置", margin) },
        bottomBar = bottomBar
    ) { padding ->
        OneScreenPage(
            tag = "settingsHome",
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            horizontalPadding = margin
        ) {
            val row = Modifier.stretch(22.dp)
            HomeBrand()
            HomeGroupTitle("阅读")
            HomeRow(
                "界面主题", appPreferences.theme.title, MaterialTheme.colorScheme.primary,
                HomeRowPosition.FIRST, margin, row, icon = { HomeTileGlyph(JerreaderIcon.PALETTE_FILLED, 24.8.dp) }
            ) { onRouteChanged(SettingsRoute.THEME) }
            HomeRow(
                "默认阅读排版", readerSummary, systemColor(dark, 0xFF5856D6, 0xFF5E5CE6),
                HomeRowPosition.LAST, margin, row, icon = { HomeTileSizeGlyph() }
            ) { onRouteChanged(SettingsRoute.READER_DEFAULTS) }
            HomeGroupTitle("翻译")
            val translationTint = systemColor(dark, 0xFF007AFF, 0xFF0A84FF)
            HomeRow(
                "翻译与 AI", providerModeTitle(preferences.providerMode), translationTint,
                HomeRowPosition.FIRST, margin, row, icon = { HomeTileBubbleGlyph(translationTint) }
            ) { onRouteChanged(SettingsRoute.TRANSLATION) }
            HomeRow(
                "AI 大模型", aiSummary, systemColor(dark, 0xFF34C759, 0xFF30D158),
                HomeRowPosition.LAST, margin, row, warn = aiNeedsKey,
                icon = { HomeTileGlyph(JerreaderIcon.SPARKLES, 20.2.dp) }
            ) { onRouteChanged(SettingsRoute.AI_MODELS) }
            HomeGroupTitle("数据与帮助")
            HomeRow(
                "备份与恢复", null, systemColor(dark, 0xFFFF9500, 0xFFFF9F0A),
                HomeRowPosition.FIRST, margin, row, icon = { HomeTileGlyph(JerreaderIcon.EXTERNAL_DRIVE_FILLED, 23.2.dp) }
            ) { onRouteChanged(SettingsRoute.BACKUP) }
            HomeRow(
                "数据与隐私", null, systemColor(dark, 0xFF30B0C7, 0xFF40C8E0),
                HomeRowPosition.MIDDLE, margin, row, icon = { HomeTileGlyph(JerreaderIcon.HAND_FILLED, 21.9.dp) }
            ) { onRouteChanged(SettingsRoute.PRIVACY) }
            HomeRow(
                "操作指南", null, Color(0xFF8E8E93),
                HomeRowPosition.LAST, margin, row, icon = { HomeTileQuestionGlyph() }
            ) { onRouteChanged(SettingsRoute.GUIDE) }
            HomeCredit(versionName)
        }
    }
}

/** The large title of a root page, as `SettingsPage` draws it, at the page margin. */
@Composable
private fun SettingsLargeTitle(title: String, margin: Dp) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = margin)) {
        Spacer(Modifier.heightIn(min = 44.dp))
        Text(
            title,
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(bottom = 8.dp).semantics { heading() }
        )
    }
}

@Composable
private fun HomeBrand() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(aiGroupedColor())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.brand_mark),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.5.dp))
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Jerreader", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "原版阅读 · 查词 · 翻译",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HomeGroupTitle(title: String) {
    Text(
        title,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 21.dp, bottom = 5.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * The white glyph on a settings-home tile. Each size makes the glyph as wide
 * or as tall as the SF Symbol iOS draws at 17 pt medium in the same 32 pt
 * tile (measured on the iPhone screenshots).
 */
@Composable
private fun HomeTileGlyph(icon: JerreaderIcon, size: Dp) {
    JerreaderGlyph(icon, Color.White, Modifier.size(size))
}

/** Text drawn as a tile glyph: a fixed size that, like SF Symbols, ignores the font scale. */
@Composable
private fun HomeTileText(
    text: AnnotatedString,
    color: Color,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val fontSize = with(LocalDensity.current) { size.toSp() }
    Text(
        text,
        modifier,
        color = color,
        maxLines = 1,
        softWrap = false,
        style = TextStyle(
            fontSize = fontSize,
            lineHeight = fontSize,
            fontWeight = FontWeight.SemiBold,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)
        )
    )
}

/** iOS `textformat.size` in Chinese: a large 大 and a small 小 on one baseline. */
@Composable
private fun HomeTileSizeGlyph() {
    val small = with(LocalDensity.current) { 11.dp.toSp() }
    // CJK ink sits a little below the centre of the line box.
    HomeTileText(
        buildAnnotatedString {
            append("大")
            withStyle(SpanStyle(fontSize = small)) { append("小") }
        },
        Color.White,
        15.dp,
        Modifier.offset(y = (-1.2).dp)
    )
}

/** iOS `character.bubble.fill` in Chinese: 字 cut out of a speech bubble. */
@Composable
private fun HomeTileBubbleGlyph(tint: Color) {
    Box(contentAlignment = Alignment.Center) {
        HomeTileGlyph(JerreaderIcon.SPEECH_BUBBLE_FILLED, 22.4.dp)
        // Centred on the bubble body, which sits above the tail.
        HomeTileText(AnnotatedString("字"), lerp(tint, Color.White, 0.08f), 9.8.dp, Modifier.offset(y = (-1.9).dp))
    }
}

/** iOS `questionmark`: a bare question mark, without the circle of [JerreaderIcon.QUESTION]. */
@Composable
private fun HomeTileQuestionGlyph() {
    HomeTileText(AnnotatedString("?"), Color.White, 22.5.dp)
}

/**
 * A row in the style of the system Settings app: a coloured tile, the title,
 * and the current value on the trailing side. It takes whatever height the
 * page hands it. [edge] is the space after the chevron, which follows the
 * page margin as in a system list.
 */
@Composable
private fun HomeRow(
    title: String,
    value: String?,
    tint: Color,
    position: HomeRowPosition,
    edge: Dp,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    val top = if (position == HomeRowPosition.FIRST) 10.dp else 0.dp
    val bottom = if (position == HomeRowPosition.LAST) 10.dp else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(aiGroupedColor())
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .heightIn(min = 44.dp)
                .padding(start = 16.dp, end = edge, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Brush.verticalGradient(listOf(lerp(tint, Color.White, 0.16f), tint)))
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center
            ) { icon() }
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                if (value != null) {
                    Text(
                        value,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (warn) aiWarning() else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            JerreaderRowChevron(aiTertiary())
        }
        if (position != HomeRowPosition.FIRST) {
            JerreaderListDivider(Modifier.align(Alignment.TopStart).padding(start = 62.dp))
        }
    }
}

@Composable
private fun HomeCredit(versionName: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text(
            "Jerry Wong",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "Jerreader $versionName",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun readerThemeTitle(theme: ReaderThemeOption): String = when (theme) {
    ReaderThemeOption.LIGHT -> "浅色"
    ReaderThemeOption.SEPIA -> "护眼"
    ReaderThemeOption.COOL_GRAY -> "冷灰"
    ReaderThemeOption.DARK -> "深色"
}

// MARK: 翻译与 AI

private sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data object Success : ConnectionTestState
    data class Failure(val message: String) : ConnectionTestState
}

/**
 * The 翻译 section of 翻译与 AI, in the order of the iOS
 * `TranslationPreferencesSection`. Providers, keys and models live on the
 * AI 大模型 page; this page only links there.
 */
/**
 * 翻译与 AI as iOS `TranslationSettingsDetailView` lays it out: the 翻译
 * section, then a link to 离线资源. The settings tab shows it with the
 * quick-translation rows; the reader's 阅读与翻译 sheet without them.
 */
@Composable
internal fun TranslationSettingsContent(
    store: AndroidTranslationSettingsStore,
    preferences: TranslationPreferences,
    backendToken: String,
    actions: TranslationSettingsActions,
    onTestConnection: suspend () -> String?,
    showsQuickSentenceToggle: Boolean,
    onOpenAIModels: () -> Unit,
    onOpenOffline: () -> Unit
) {
    TranslationSettings(
        store = store,
        preferences = preferences,
        backendToken = backendToken,
        actions = actions,
        onTestConnection = onTestConnection,
        onOpenAIModels = onOpenAIModels,
        showsQuickSentenceToggle = showsQuickSentenceToggle
    )
    SettingsGroup(title = "", footer = "基础查词无需下载；句段翻译语言包和扩展词典按需准备。") {
        SettingsLinkRow("离线资源", "", onOpenOffline, showDivider = false)
    }
}

/** The setters 翻译与 AI drives, bound to the app's translation settings. */
internal fun translationSettingsActions(store: AndroidTranslationSettingsStore) = TranslationSettingsActions(
    updateProviderMode = store::updateProviderMode,
    updateBackendEndpoint = store::updateBackendEndpoint,
    updateBackendModel = store::updateBackendModel,
    updateBackendToken = store::updateBackendAccessToken,
    updateSourceChoice = store::updateSourceChoice,
    updateTargetLanguage = store::updateTargetLanguage,
    updateQuickEnabled = store::updateQuickTranslationEnabled,
    updateQuickUnit = store::updateQuickTranslationUnit,
    updateDisablesTapPageTurns = store::updateDisablesTapPageTurns,
    updateDisplayMode = store::updateDisplayMode,
    updateTranslationHaptics = store::updateTranslationHaptics,
    updateAutomaticRetry = store::updateAutomaticRetry,
    updateFallbackMode = store::updateFallbackMode,
    updatePrompt = store::updateTranslationPrompt,
    updateGrammarPrompt = store::updateGrammarPrompt
)

/**
 * 测试连接: a fixed short sentence through the service picked on 翻译与 AI
 * alone, with no fallback and no book text. Returns why it failed, or `null`
 * when it came back translated.
 */
internal suspend fun testTranslationConnection(
    graph: com.jerreader.android.AppGraph,
    preferences: TranslationPreferences
): String? {
    val target = preferences.targetLanguage
    val source = preferences.sourceChoice.language
        ?: if (target == LanguageCode.ENGLISH) LanguageCode.JAPANESE else LanguageCode.ENGLISH
    return try {
        graph.configuredTranslationService.translate(
            com.jerreader.shared.translation.TranslationRequest(
                text = if (source == LanguageCode.JAPANESE) "こんにちは。" else "Hello.",
                sourceLanguage = source,
                targetLanguage = target
            ),
            com.jerreader.android.translation.StandaloneTranslationOverride(preferences.providerMode)
        )
        null
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        error.message ?: "连接失败，请检查网络、模型和凭据。"
    }
}

/** iOS `quickSentenceInteractionDescription`. */
private fun quickTranslationDescription(preferences: TranslationPreferences): String {
    val target = if (preferences.quickTranslationUnit == QuickTranslationUnit.SENTENCE) "句子" else "段落"
    return if (preferences.disablesTapPageTurnsDuringQuickTranslation) {
        "轻点模式下单击翻译当前$target，滑动仍可翻页。关闭后，长按框选与左右点按翻页可同时使用。"
    } else {
        "轻点正文翻译当前$target；点按左右边缘翻页。长按可智能补全，拖动手柄后按最终框选内容翻译。"
    }
}

@Composable
private fun TranslationSettings(
    store: AndroidTranslationSettingsStore,
    preferences: TranslationPreferences,
    backendToken: String,
    actions: TranslationSettingsActions,
    onTestConnection: suspend () -> String?,
    onOpenAIModels: () -> Unit,
    showsQuickSentenceToggle: Boolean = true
) {
    val providers by store.aiProviders.collectAsState()
    val keyed by store.keyedProviderIDs.collectAsState()
    var tokenText by remember { mutableStateOf(backendToken) }
    var testState by remember { mutableStateOf<ConnectionTestState>(ConnectionTestState.Idle) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(backendToken) { tokenText = backendToken }
    // Any change to what the test would use makes its last result stale.
    LaunchedEffect(
        preferences.providerMode,
        preferences.sourceChoice,
        preferences.targetLanguage,
        providers,
        keyed,
        preferences.backendEndpoint,
        preferences.backendModel,
        tokenText,
        preferences.translationPromptTemplate,
        preferences.grammarAnalysisPromptTemplate
    ) {
        testJob?.cancel()
        testJob = null
        testState = ConnectionTestState.Idle
    }

    val quick = preferences.quickTranslationEnabled
    val sameLanguage = preferences.sourceChoice.language == preferences.targetLanguage
    val directReady = store.directConfiguration() != null
    val backendReady = store.backendConfigurationReady()
    val canTest = !sameLanguage && when (preferences.providerMode) {
        TranslationProviderMode.ON_DEVICE -> false
        TranslationProviderMode.DIRECT_API -> directReady
        TranslationProviderMode.BACKEND_PROXY -> backendReady
    }

    fun testConnection() {
        testJob?.cancel()
        val ready = when (preferences.providerMode) {
            TranslationProviderMode.ON_DEVICE -> false
            TranslationProviderMode.DIRECT_API -> directReady
            TranslationProviderMode.BACKEND_PROXY -> backendReady
        }
        if (!ready) {
            testState = ConnectionTestState.Failure("配置尚未完成。")
            return
        }
        testState = ConnectionTestState.Testing
        testJob = scope.launch {
            val failure = try {
                onTestConnection()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                error.message ?: "连接失败，请检查网络、模型和凭据。"
            }
            testState = if (failure == null) ConnectionTestState.Success else ConnectionTestState.Failure(failure)
            testJob = null
        }
    }

    // iOS keeps every translation control in one Form section headed 「翻译」.
    SettingsGroup(title = "翻译", icon = JerreaderIcon.SPEECH_BUBBLE, accentedHeader = true) {
        if (showsQuickSentenceToggle) {
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
                    quickTranslationDescription(preferences)
                } else {
                    "关闭后，EPUB 中轻点词语直接查词并记入学习记录，轻点空白处控制阅读界面；长按和拖动选区仍可翻译。"
                }
            )
        } else {
            // The reader's 阅读与翻译 sheet has these switches in its own
            // 阅读交互 section; iOS says this here instead.
            SettingsFootnote("PDF 可轻点句子或段落直接翻译；没有文字层的扫描页会在本机按需 OCR。长按仍可精确框选已有文字层的内容。")
        }

        SettingsMenuRow(
            title = "翻译服务",
            options = TranslationProviderMode.entries.map { it to providerModeTitle(it) },
            selected = preferences.providerMode,
            onSelect = actions.updateProviderMode
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
                TranslationDisplayMode.NEAR_SELECTION -> "译文显示在选中文字附近，自动避开当前选区。"
                TranslationDisplayMode.TOP_BANNER -> "译文优先显示在页面上方；靠近顶部选词时会自动避让选区。"
            }
        )

        SettingsToggleRow(
            "翻译完成时触感反馈",
            preferences.translationHapticsEnabled,
            actions.updateTranslationHaptics
        )
        SettingsFootnote(
            if (preferences.translationHapticsEnabled) {
                "译文或 AI 句子结构分析完成时提供轻触反馈。"
            } else {
                "翻译相关操作不会触发振动。"
            }
        )
        SettingsToggleRow(
            "短暂失败自动重试一次",
            preferences.automaticRetryEnabled,
            actions.updateAutomaticRetry
        )
        SettingsMenuRow(
            title = "备用翻译服务",
            options = TranslationFallbackMode.entries.map { it to fallbackTitle(it) },
            selected = preferences.fallbackMode,
            onSelect = actions.updateFallbackMode
        )
        SettingsFootnote(fallbackDescription(preferences, directReady, backendReady))

        if (preferences.providerMode != TranslationProviderMode.ON_DEVICE) {
            SettingsDisclosureGroup("提示词与 AI 行为") {
                PromptEditor(
                    title = "翻译提示词",
                    value = preferences.translationPromptTemplate,
                    onValueChange = actions.updatePrompt,
                    onReset = { actions.updatePrompt(TranslationPreferences.DEFAULT_TRANSLATION_PROMPT) }
                )
                JerreaderListDivider()
                PromptEditor(
                    title = "句子结构分析提示词",
                    value = preferences.grammarAnalysisPromptTemplate,
                    onValueChange = actions.updateGrammarPrompt,
                    onReset = { actions.updateGrammarPrompt(TranslationPreferences.DEFAULT_GRAMMAR_PROMPT) }
                )
                Text(
                    "可使用 {source_language}、{target_language} 和 {response_language} 占位符。" +
                        "修改提示词后会生成新的缓存版本，不会误用旧结果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        when (preferences.providerMode) {
            TranslationProviderMode.ON_DEVICE -> SettingsFootnote(
                "无需账号或 API Key。基础查词开箱可用；句段翻译请先在‘离线资源’中准备语言包。",
                showDivider = false
            )

            TranslationProviderMode.DIRECT_API -> {
                // Providers, keys and models live on their own page, as in the
                // settings home's AI 大模型 row.
                val active = providers.active
                val model = active.model.trim()
                SettingsLinkRow(
                    title = "AI 大模型",
                    value = if (model.isEmpty()) active.displayName else "${active.displayName} · $model",
                    onClick = onOpenAIModels
                )
                SettingsInsetRow {
                    ConfigurationStatus(store.directAPIConfigurationMessage(), ready = directReady)
                }
                ConnectionTest(testState, enabled = canTest && testState != ConnectionTestState.Testing, showDivider = false) {
                    testConnection()
                }
            }

            TranslationProviderMode.BACKEND_PROXY -> {
                SettingsFieldRow(
                    value = preferences.backendEndpoint,
                    onValueChange = actions.updateBackendEndpoint,
                    placeholder = "https://example.com/translate",
                    label = "HTTPS 翻译代理地址",
                    keyboardType = KeyboardType.Uri
                )
                SettingsFieldRow(
                    value = preferences.backendModel,
                    onValueChange = actions.updateBackendModel,
                    placeholder = "模型名称（可选）"
                )
                SettingsFieldRow(
                    value = tokenText,
                    onValueChange = {
                        tokenText = it
                        actions.updateBackendToken(it)
                    },
                    placeholder = "代理访问凭据（可选）",
                    keyboardType = KeyboardType.Password,
                    visualTransformation = PasswordVisualTransformation()
                )
                SettingsInsetRow {
                    ConfigurationStatus(store.backendConfigurationMessage(), ready = backendReady)
                }
                ConnectionTest(testState, enabled = canTest && testState != ConnectionTestState.Testing) {
                    testConnection()
                }
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
}

/** What 备用翻译服务 will do, as iOS words it, with the Android service names. */
private fun fallbackDescription(
    preferences: TranslationPreferences,
    directReady: Boolean,
    backendReady: Boolean
): String {
    val fallback = preferences.fallbackMode.providerMode
        ?: return "缓存始终优先；网络短暂中断时可自动重试一次，但不会把文字发送给其他服务。"
    if (fallback == preferences.providerMode) {
        return "备用服务与当前服务相同，不会重复切换；请选择另一个已配置的服务。"
    }
    return when (fallback) {
        TranslationProviderMode.ON_DEVICE -> "主服务短暂失败或超时后，会自动尝试 Android 本机翻译。"
        TranslationProviderMode.DIRECT_API -> if (directReady) {
            "主服务短暂失败或超时后，会把同一段选中文字发送给正在用的 AI 大模型。"
        } else {
            "请先在「设置 → AI 大模型」填好 API Key，否则备用切换不会执行。"
        }
        TranslationProviderMode.BACKEND_PROXY -> if (backendReady) {
            "主服务短暂失败或超时后，会把同一段选中文字发送给已配置的 AI 代理。"
        } else {
            "请先完成 AI 代理配置，否则备用切换不会执行。"
        }
    }
}

/** `NavigationLink { LabeledContent(title) { value } }`: a row that opens a page. */
@Composable
private fun SettingsLinkRow(title: String, value: String, onClick: () -> Unit, showDivider: Boolean = true) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 44.dp)
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                value,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            JerreaderNavigationChevron()
        }
        if (showDivider) JerreaderRowSeparator(start = 20.dp)
    }
}

/** A plain text field in a Form row, as iOS draws `TextField` there. */
@Composable
private fun SettingsFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String = placeholder,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AITextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                modifier = Modifier.weight(1f),
                label = label,
                keyboardType = keyboardType,
                visualTransformation = visualTransformation
            )
        }
        JerreaderRowSeparator(start = 20.dp)
    }
}

/** Whether the service picked can be called, on a tinted card. */
@Composable
private fun ConfigurationStatus(message: String, ready: Boolean) {
    val tint = if (ready) MaterialTheme.colorScheme.primary else aiWarning()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (ready) LocalJerreaderColors.current.accentFill else tint.copy(alpha = 0.10f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        JerreaderGlyph(
            if (ready) JerreaderIcon.SHIELD else JerreaderIcon.WARNING,
            tint,
            Modifier.padding(top = 1.dp).size(14.dp)
        )
        Text(message, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/** A bordered 测试连接 button and the line under it. */
@Composable
private fun ConnectionTest(
    state: ConnectionTestState,
    enabled: Boolean,
    showDivider: Boolean = true,
    onTest: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val title = when (state) {
        ConnectionTestState.Idle -> "测试连接"
        ConnectionTestState.Testing -> "正在测试…"
        ConnectionTestState.Success -> "连接成功"
        is ConnectionTestState.Failure -> "重新测试"
    }
    val detail = when (state) {
        ConnectionTestState.Idle -> "测试只发送固定短句，不会发送书籍内容。"
        ConnectionTestState.Testing -> "正在验证地址、模型和凭据。"
        ConnectionTestState.Success -> "配置可正常返回译文。"
        is ConnectionTestState.Failure -> state.message
    }
    SettingsInsetRow(showDivider = showDivider) {
        Row(
            Modifier
                .clip(RoundedCornerShape(11.dp))
                .background(accent.copy(alpha = if (enabled) 0.12f else 0.06f))
                .clickable(enabled = enabled, role = Role.Button, onClick = onTest)
                .heightIn(min = 40.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            val tint = if (enabled || state == ConnectionTestState.Testing) accent else aiTertiary()
            when (state) {
                ConnectionTestState.Testing -> CircularProgressIndicator(Modifier.size(16.dp), color = accent, strokeWidth = 2.dp)
                ConnectionTestState.Success -> AIGlyphIcon(AIGlyph.CHECK_CIRCLE_FILLED, tint, Modifier.size(17.dp))
                is ConnectionTestState.Failure -> JerreaderGlyph(JerreaderIcon.RESTORE, tint, Modifier.size(17.dp))
                ConnectionTestState.Idle -> JerreaderGlyph(JerreaderIcon.SWAP, tint, Modifier.size(17.dp))
            }
            Text(title, style = MaterialTheme.typography.bodyLarge, color = tint)
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = if (state is ConnectionTestState.Failure) aiRed() else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One prompt template: its title, a 恢复默认 button, and the text. */
@Composable
private fun PromptEditor(title: String, value: String, onValueChange: (String) -> Unit, onReset: () -> Unit) {
    val colors = LocalJerreaderColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "恢复默认",
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(role = Role.Button, onClick = onReset)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 128.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.paper)
                .border(0.75.dp, colors.line, RoundedCornerShape(10.dp))
                .padding(10.dp)
                .semantics { contentDescription = title },
            textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
        )
    }
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
    TranslationFallbackMode.DIRECT_API -> "AI 大模型"
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
