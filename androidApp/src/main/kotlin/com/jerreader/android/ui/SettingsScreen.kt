package com.jerreader.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.jerreader.shared.ui.JerreaderAppearanceMode
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderIconTile
import com.jerreader.shared.ui.JerreaderListDivider
import com.jerreader.shared.ui.JerreaderMotion
import com.jerreader.shared.ui.JerreaderNavigationChevron
import com.jerreader.shared.ui.JerreaderPillOption
import com.jerreader.shared.ui.JerreaderPillPicker
import com.jerreader.shared.ui.JerreaderRadius
import com.jerreader.shared.ui.JerreaderRowSeparator
import com.jerreader.shared.ui.LocalJerreaderColors
import com.jerreader.shared.ui.LocalSettingsHeader
import com.jerreader.shared.ui.SettingsActionRow
import com.jerreader.shared.ui.SettingsDisclosureGroup
import com.jerreader.shared.ui.SettingsFootnote
import com.jerreader.shared.ui.SettingsGroup
import com.jerreader.shared.ui.SettingsInsetRow
import com.jerreader.shared.ui.SettingsValueRow
import com.jerreader.shared.ui.SettingsBareSliderRow
import com.jerreader.shared.ui.SettingsMenuRow
import com.jerreader.shared.ui.SettingsPage
import com.jerreader.shared.ui.SettingsToggleRow
import com.jerreader.shared.ui.SettingsWarningNote
import com.jerreader.shared.ui.jerreaderCanvasBackground
import com.jerreader.shared.ui.jerreaderColors
import com.jerreader.shared.ui.jerreaderPalette
import com.jerreader.shared.ui.jerreaderPaperCard
import com.jerreader.shared.ui.jerreaderPressScale
import com.jerreader.shared.ui.settingsHeaderStyle
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

    /** 默认阅读排版, opened from 界面主题 → 阅读纸张: back returns there. */
    THEME_READER_DEFAULTS,
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
    BackHandler(enabled = route == SettingsRoute.THEME_READER_DEFAULTS) {
        onRouteChanged(SettingsRoute.THEME)
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

        // 界面主题 (2.0.1), the same five blocks as iOS `AppThemeColorSettingsView`:
        // a live preview, 外观模式, the eight colours, 背景光晕, and a way to
        // the reader's paper. The paper is a link rather than a control so
        // that 默认阅读排版's 同步应用到现有书籍 rule stays the only way a
        // paper reaches the books.
        SettingsRoute.THEME -> SettingsPage(title = "界面主题", onBack = back, bottomBar = bottomBar) {
            SettingsGroup(title = "预览", icon = JerreaderIcon.PALETTE) {
                ThemePreviewCard(themeTitle = appPreferences.theme.title)
            }
            SettingsGroup(
                title = "外观模式",
                icon = JerreaderIcon.PALETTE,
                footer = "只影响书架、学习和设置等页面。阅读页的明暗跟随所选的阅读纸张。"
            ) {
                SettingsPillRow(
                    title = "外观模式",
                    options = JerreaderAppearanceMode.entries.map { it to it.title },
                    selected = appPreferences.appearanceMode,
                    onSelect = graph.appSettings::updateAppearanceMode,
                    showDivider = false
                )
            }
            SettingsGroup(
                title = "主题色系",
                icon = JerreaderIcon.PALETTE,
                footer = "色系会同时调整强调色、按钮和卡片上的点缀。"
            ) {
                ThemeSwatches(selected = appPreferences.theme, onSelect = onThemeChanged)
            }
            SettingsGroup(
                title = "背景",
                icon = JerreaderIcon.PALETTE,
                footer = "在每页顶部铺一层很淡的主题色。关闭后页面是纯净的纸色。"
            ) {
                SettingsToggleRow(
                    "背景光晕",
                    appPreferences.backdropGlow,
                    graph.appSettings::updateBackdropGlow,
                    modifier = Modifier.testTag("themeBackdropGlow"),
                    showDivider = false
                )
            }
            SettingsGroup(
                title = "阅读页",
                icon = JerreaderIcon.BOOKS,
                footer = "阅读页的纸张、字体和间距不随界面主题变化，在“默认阅读排版”里单独设置。"
            ) {
                SettingsLinkRow(
                    "阅读纸张",
                    readerThemeTitle(appPreferences.defaultReaderAppearance.theme),
                    { onRouteChanged(SettingsRoute.THEME_READER_DEFAULTS) },
                    showDivider = false
                )
            }
        }

        SettingsRoute.READER_DEFAULTS, SettingsRoute.THEME_READER_DEFAULTS -> SettingsPage(
            title = "默认阅读排版",
            onBack = if (route == SettingsRoute.THEME_READER_DEFAULTS) {
                { onRouteChanged(SettingsRoute.THEME) }
            } else {
                back
            },
            bottomBar = bottomBar,
            backTitle = if (route == SettingsRoute.THEME_READER_DEFAULTS) "界面主题" else "设置"
        ) {
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
                    SettingsSyncedRow("已自动同步到 $bookCount 本现有书籍")
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
                onShowProgressChanged = onShowProgressChanged,
                verticalMargins = appPreferences.verticalMargins,
                onVerticalMarginsChanged = graph.appSettings::updateVerticalMargins
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
            // iOS `DataPrivacySettingsView`: each promise on a tinted 30dp tile.
            SettingsGroup(
                title = "本机数据",
                icon = JerreaderIcon.SHIELD,
                footer = null
            ) {
                PrivacyFact("书籍、书架与学习记录默认只保存在本机", JerreaderIcon.BOOKS, jerreaderPalette.accent)
                PrivacyFact("API Key 只保存在本机安全存储", JerreaderIcon.LOCK, SettingsTint.guide)
                PrivacyFact("在线 AI 只接收你主动选择的文字与必要上下文", JerreaderIcon.SHIELD, SettingsTint.privacy)
                PrivacyFact(
                    "导入的书籍是只读输入，阅读与翻译不会改写它",
                    JerreaderIcon.DICTIONARY,
                    SettingsTint.reading,
                    showDivider = false
                )
            }
            SettingsGroup(
                title = "写出副本",
                icon = JerreaderIcon.DOWNLOAD,
                footer = "密钥不会写入书籍、日志或安装包。备份只写入你选择的位置。"
            ) {
                PrivacyFact("备份需要你先授权一个文件夹", JerreaderIcon.DOWNLOAD, SettingsTint.backup, showDivider = false)
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
    onShowProgressChanged: (Boolean) -> Unit,
    verticalMargins: Float,
    onVerticalMarginsChanged: (Float) -> Unit
) {
    SettingsGroup(
        title = "翻页与导航",
        icon = JerreaderIcon.BOOKS,
        footer = if (appliesToExistingBooks) "当前排版会同步到现有书籍；阅读位置、书签与批注不受影响。" else "当前排版只会自动用于以后导入的新书。"
    ) {
        SettingsPillRow(
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

    SettingsGroup(
        title = "间距",
        icon = JerreaderIcon.SORT,
        footer = "上下边距是正文离屏幕上下两端的留白，对所有书立即生效，不分单本；PDF 按页面本身显示，不受影响。"
    ) {
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
        SettingsValueRow("左右边距", oneDecimal(appearance.pageMargins), compact = false)
        SettingsBareSliderRow(
            value = appearance.pageMargins.toFloat().coerceIn(0.5f, 2f),
            range = 0.5f..2f,
            steps = 14,
            onValueChange = { onChange(appearance.copy(pageMargins = roundedTenth(it))) },
            accessibilityLabel = "左右边距"
        )
        // 2.0.1: the app's own setting, shared by every book (see the footer).
        SettingsValueRow("上下边距", oneDecimal(verticalMargins.toDouble()), compact = false)
        SettingsBareSliderRow(
            value = verticalMargins.coerceIn(
                com.jerreader.android.settings.VERTICAL_MARGINS_RANGE.start,
                com.jerreader.android.settings.VERTICAL_MARGINS_RANGE.endInclusive
            ),
            range = com.jerreader.android.settings.VERTICAL_MARGINS_RANGE,
            steps = 39,
            onValueChange = onVerticalMarginsChanged,
            accessibilityLabel = "上下边距",
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
        SettingsPillRow(
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

/**
 * 界面主题 as iOS `AppThemeColorSettingsView` draws it: the five colours as a
 * row of round swatches, then the chosen colour's name and what it is. The
 * chosen swatch carries a 2dp ring in its own colour and a check; the switch
 * springs (`stateChange`) and ticks.
 */
@Composable
private fun ThemeSwatches(
    selected: AppThemeChoice,
    onSelect: (AppThemeChoice) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    Column(modifier.fillMaxWidth()) {
        // Eight colours, four to a row, as on iOS.
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AppThemeChoice.entries.chunked(THEME_SWATCH_COLUMNS).forEach { rowChoices ->
                Row(Modifier.fillMaxWidth()) {
                    rowChoices.forEach { choice ->
                        ThemeSwatch(
                            choice = choice,
                            isSelected = choice == selected,
                            onClick = {
                                if (choice != selected) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    onSelect(choice)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // A short last row keeps the columns of the rows above it.
                    repeat(THEME_SWATCH_COLUMNS - rowChoices.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        JerreaderRowSeparator(start = 20.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(selected.title, style = MaterialTheme.typography.titleMedium)
            Text(
                selected.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private const val THEME_SWATCH_COLUMNS = 4

/**
 * The preview at the top of 界面主题: the continue-reading hero card and the
 * two kinds of button in the current colours, which follow a swatch the
 * moment it is tapped. Nothing here can be pressed; TalkBack reads it as one
 * description.
 */
@Composable
private fun ThemePreviewCard(themeTitle: String, modifier: Modifier = Modifier) {
    val colors = jerreaderPalette
    val heroShape = RoundedCornerShape(JerreaderRadius.control)
    Column(
        modifier
            .fillMaxWidth()
            .padding(14.dp)
            .clearAndSetSemantics { contentDescription = "${themeTitle}主题的界面预览" }
            .testTag("themePreview"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(heroShape)
                // The hero gradient of 继续阅读 and 今日练习.
                .background(
                    Brush.linearGradient(
                        colors = listOf(colors.accentDeep, colors.ink),
                        start = Offset.Zero,
                        end = Offset.Infinite
                    )
                )
                .drawWithContent {
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0f)),
                            center = Offset.Zero,
                            radius = maxOf(size.width * 0.9f, 1f)
                        )
                    )
                    drawContent()
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "继续阅读",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
                color = Color.White.copy(alpha = 0.76f),
                maxLines = 1
            )
            Text(
                "こころ",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1
            )
            Row(
                Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f))
                ) {
                    Box(Modifier.fillMaxWidth(0.42f).fillMaxHeight().background(Color.White, CircleShape))
                }
                Text(
                    "42%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.86f),
                    maxLines = 1
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemePreviewPill("开始练习", background = colors.accent, content = colors.onAccent)
            ThemePreviewPill("加入生词本", background = colors.accentFill, content = colors.accent)
        }
    }
}

/** A 34dp capsule of the preview: the filled action, or the tinted one. */
@Composable
private fun ThemePreviewPill(text: String, background: Color, content: Color) {
    Box(
        Modifier
            .heightIn(min = 34.dp)
            .background(background, CircleShape)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = content,
            maxLines = 1
        )
    }
}

/** A 44dp colour circle inside a 54dp ring, with the colour's name under it. */
@Composable
private fun ThemeSwatch(
    choice: AppThemeChoice,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = jerreaderColors(choice, jerreaderPalette.isDark).accent
    val check = jerreaderPalette.onAccent
    val source = remember { MutableInteractionSource() }
    val shown by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = JerreaderMotion.stateChange(),
        label = "themeSwatch"
    )
    Column(
        modifier
            .jerreaderPressScale(source, pressedScale = 0.94f)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "${choice.title}，${choice.detail}"
                selected = isSelected
            }
            .testTag("themeSwatch-${choice.name.lowercase()}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val scale = 0.84f + 0.16f * shown
                        scaleX = scale
                        scaleY = scale
                        alpha = shown.coerceIn(0f, 1f)
                    }
                    .border(2.dp, tint, CircleShape)
            )
            Box(
                Modifier
                    .size(44.dp)
                    .background(tint, CircleShape)
                    .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape)
            )
            JerreaderGlyph(
                JerreaderIcon.CHECK,
                check,
                Modifier
                    .size(20.dp)
                    .graphicsLayer {
                        val scale = 0.5f + 0.5f * shown
                        scaleX = scale
                        scaleY = scale
                        alpha = shown.coerceIn(0f, 1f)
                    }
            )
        }
        Text(
            choice.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/**
 * The 2.0 pill control on a Form row of its own, where iOS replaced the
 * system segments, so the chosen option carries the theme colour. [title]
 * names it for accessibility, as the row's label did. Switched off it dims to
 * iOS's 0.45 and takes no touches.
 */
@Composable
private fun <T> SettingsPillRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showDivider: Boolean = true
) {
    Column(modifier.fillMaxWidth().semantics { contentDescription = title }) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
            JerreaderPillPicker(
                options = options.map { (value, label) -> JerreaderPillOption(value, label) },
                selected = selected,
                onSelect = { if (enabled) onSelect(it) },
                modifier = Modifier.alpha(if (enabled) 1f else 0.45f)
            )
            if (!enabled) {
                // Over the picker, so not even its press or tick answers.
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                )
            }
        }
        if (showDivider) JerreaderRowSeparator(start = 20.dp)
    }
}

/** iOS's `Label` with a jade `checkmark.circle.fill`: the 已自动同步 line. */
@Composable
private fun SettingsSyncedRow(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 20.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                JerreaderGlyph(JerreaderIcon.CHECK_CIRCLE, jerreaderPalette.jade, Modifier.size(20.dp))
            }
            Text(
                text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        JerreaderRowSeparator(start = 64.dp)
    }
}

/** One promise about the data, as iOS `fact`: a 30dp tile and the sentence beside it. */
@Composable
private fun PrivacyFact(
    text: String,
    icon: JerreaderIcon,
    tint: Color,
    modifier: Modifier = Modifier,
    showDivider: Boolean = true
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 20.dp, vertical = 13.dp)
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            JerreaderIconTile(icon, tint = tint, size = 30.dp)
            Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
        // Level with the sentence, as iOS's `listRowSeparatorLeading` guide puts it.
        if (showDivider) JerreaderRowSeparator(start = 64.dp)
    }
}

/** The sliders step by 0.1; floating point drift would show up in the label. */
private fun roundedTenth(value: Float): Double = kotlin.math.round(value * 10.0) / 10.0

private fun oneDecimal(value: Double): String {
    val tenths = kotlin.math.round(value * 10.0).toInt()
    return "${tenths / 10}.${tenths % 10}"
}

// MARK: Settings home

/**
 * The rows of one settings-home group. Those after the first report their
 * heights, so that the first row can draw the group's one paper card behind
 * them all, as iOS `SettingsGroupCards` does from the rows' frames.
 */
private class HomeGroup(val size: Int) {
    private val heights = List(size) { mutableIntStateOf(0) }

    fun report(index: Int, height: Int) {
        val slot = heights[index]
        if (slot.intValue != height) slot.intValue = height
    }

    /** How far the card reaches below the first row, [first] pixels tall; a row not yet laid out counts as tall as it. */
    fun below(first: Int): Int = (1 until size).sumOf { index -> heights[index].intValue.takeIf { it > 0 } ?: first }
}

/**
 * The settings home, laid out as the iOS `AppSettingsView`: a brand card, the
 * rows of the system Settings app in three groups on paper cards, and the
 * credit. It fills one screen exactly: the brand card, group titles and rows
 * share whatever height a taller phone leaves over (up to 20, 8 and 22dp each,
 * as on iOS), so nothing scrolls and no gap is left above the tab bar. Large
 * text or a short screen still scrolls.
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
    val reading = remember { HomeGroup(2) }
    val translation = remember { HomeGroup(2) }
    val help = remember { HomeGroup(3) }
    Scaffold(
        // The 2.0 canvas with its faint accent wash, as iOS draws behind the page.
        modifier = Modifier.jerreaderCanvasBackground(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = { SettingsLargeTitle("设置", margin) },
        bottomBar = bottomBar
    ) { padding ->
        OneScreenPage(
            tag = "settingsHome",
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            horizontalPadding = margin
        ) {
            val row = Modifier.stretch(22.dp)
            val title = Modifier.stretch(8.dp)
            HomeBrand(Modifier.stretch(20.dp))
            HomeGroupTitle("阅读", title)
            HomeRow(
                title = "界面主题", value = appPreferences.theme.title,
                icon = JerreaderIcon.PALETTE_FILLED, tint = jerreaderPalette.accent,
                group = reading, index = 0, edge = margin, tag = "themeSettingsLink",
                onClick = { onRouteChanged(SettingsRoute.THEME) }, modifier = row
            )
            HomeRow(
                title = "默认阅读排版", value = readerSummary,
                icon = JerreaderIcon.TEXT_FORMAT, tint = SettingsTint.reading,
                group = reading, index = 1, edge = margin, tag = "readerDefaultsLink",
                onClick = { onRouteChanged(SettingsRoute.READER_DEFAULTS) }, modifier = row
            )
            HomeGroupTitle("翻译", title)
            HomeRow(
                title = "翻译与 AI", value = providerModeTitle(preferences.providerMode),
                icon = JerreaderIcon.TRANSLATION_BUBBLE_FILLED, tint = SettingsTint.translation,
                group = translation, index = 0, edge = margin, tag = "translationSettingsLink",
                onClick = { onRouteChanged(SettingsRoute.TRANSLATION) }, modifier = row
            )
            HomeRow(
                title = "AI 大模型", value = aiSummary,
                icon = JerreaderIcon.SPARKLES, tint = SettingsTint.models,
                group = translation, index = 1, edge = margin, tag = "aiModelsSettingsLink",
                onClick = { onRouteChanged(SettingsRoute.AI_MODELS) }, modifier = row, warn = aiNeedsKey
            )
            HomeGroupTitle("数据与帮助", title)
            HomeRow(
                title = "备份与恢复", value = null,
                icon = JerreaderIcon.EXTERNAL_DRIVE_FILLED, tint = SettingsTint.backup,
                group = help, index = 0, edge = margin, tag = "backupSettingsLink",
                onClick = { onRouteChanged(SettingsRoute.BACKUP) }, modifier = row
            )
            HomeRow(
                title = "数据与隐私", value = null,
                icon = JerreaderIcon.HAND_FILLED, tint = SettingsTint.privacy,
                group = help, index = 1, edge = margin, tag = "privacySettingsLink",
                onClick = { onRouteChanged(SettingsRoute.PRIVACY) }, modifier = row
            )
            HomeRow(
                title = "操作指南", value = null,
                icon = JerreaderIcon.QUESTION, tint = SettingsTint.guide,
                group = help, index = 2, edge = margin, tag = "guideSettingsLink",
                onClick = { onRouteChanged(SettingsRoute.GUIDE) }, modifier = row
            )
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

/**
 * The app's own card: its mark, name and what it does, on paper with a faint
 * wash of the theme colour from the top-leading corner. Its natural height
 * (80dp) is the old brand row's, so the page budget holds; a taller phone
 * gives it up to 20dp more.
 */
@Composable
private fun HomeBrand(modifier: Modifier = Modifier) {
    val colors = jerreaderPalette
    val markShape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .jerreaderPaperCard(RoundedCornerShape(JerreaderRadius.card))
            .drawBehind {
                drawRoundRect(
                    Brush.radialGradient(
                        colors = listOf(
                            colors.accent.copy(alpha = if (colors.isDark) 0.16f else 0.10f),
                            colors.accent.copy(alpha = 0f)
                        ),
                        center = Offset.Zero,
                        radius = 280.dp.toPx()
                    ),
                    cornerRadius = CornerRadius(JerreaderRadius.card.toPx())
                )
            }
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.brand_mark),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(markShape).border(0.5.dp, colors.line, markShape)
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Jerreader", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "原版阅读 · 查词 · 翻译",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * A group title, level with the icon tiles below it. On a taller phone it
 * takes up to 8dp more above itself, which separates the groups.
 */
@Composable
private fun HomeGroupTitle(title: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.BottomStart) {
        Text(
            title,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 21.dp, bottom = 5.dp)
                .semantics { heading() },
            style = settingsHeaderStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A row in the style of the system Settings app: a tinted 30dp tile, the
 * title, and the current value on the trailing side. It takes whatever height
 * the page hands it. The group's paper card lies behind the rows (the first
 * row draws it), so a row adds only its pressed tint and the hairline above
 * it. [edge] is the space after the chevron, which follows the page margin as
 * in a system list.
 */
@Composable
private fun HomeRow(
    title: String,
    value: String?,
    icon: JerreaderIcon,
    tint: Color,
    group: HomeGroup,
    index: Int,
    edge: Dp,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    warn: Boolean = false
) {
    val first = index == 0
    val top = if (first) JerreaderRadius.card else 0.dp
    val bottom = if (index == group.size - 1) JerreaderRadius.card else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // The tint comes at once and fades out after the finger lifts (iOS `quick`).
    val pressTint by animateFloatAsState(
        targetValue = if (pressed) 0.07f else 0f,
        animationSpec = if (pressed) snap() else tween(durationMillis = 160, easing = EaseOut),
        label = "homeRowPress"
    )
    val ink = MaterialTheme.colorScheme.onSurface
    val line = jerreaderPalette.line
    Box(
        modifier
            .fillMaxWidth()
            .then(if (first) Modifier else Modifier.onSizeChanged { group.report(index, it.height) })
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .testTag(tag)
    ) {
        if (first) {
            // The group's one card: paper, a 0.75dp hairline and, in light
            // mode, the e1 shadow, reaching down behind the rows below.
            Box(
                Modifier
                    .matchParentSize()
                    .reachDown(group::below)
                    .jerreaderPaperCard(RoundedCornerShape(JerreaderRadius.card))
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .drawWithContent {
                    if (pressTint > 0f) {
                        drawOutline(shape.createOutline(size, layoutDirection, this), ink.copy(alpha = pressTint))
                    }
                    drawContent()
                    if (!first) {
                        // One pixel, level with the title.
                        val inset = 60.dp.toPx()
                        drawRect(line, Offset(inset, 0f), Size(size.width - inset, 1f))
                    }
                }
                .heightIn(min = 44.dp)
                .padding(start = 16.dp, end = edge, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            JerreaderIconTile(icon, tint = tint, size = 30.dp)
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
            JerreaderGlyph(JerreaderIcon.CHEVRON_RIGHT, aiTertiary(), Modifier.size(16.dp))
        }
    }
}

/**
 * Lays the content out [extra] pixels taller than this element, hanging below
 * it, for the card the first row of a group draws behind the rest. Intrinsic
 * measurement asks with an open height; that passes straight through.
 */
private fun Modifier.reachDown(extra: (Int) -> Int): Modifier = this.layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val placeable = measurable.measure(Constraints.fixed(width, height + extra(height).coerceAtLeast(0)))
    layout(width, height) { placeable.place(0, 0) }
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
            modifier = Modifier.testTag("developerCredit"),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            color = jerreaderPalette.accent
        )
        Text(
            "Jerreader $versionName",
            modifier = Modifier.testTag("appVersionLabel"),
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
    onOpenOffline: () -> Unit,
    /** 翻译卡片显示 AI 按钮 (2.1.0 §7), shown by the reader's sheet only; null leaves the row out. */
    aiActionBarShown: Boolean? = null,
    onAIActionBarShownChange: (Boolean) -> Unit = {}
) {
    // The 2.0 section titles wherever this page opens, the reader's sheet included, as on iOS.
    CompositionLocalProvider(LocalSettingsHeader provides true) {
        TranslationSettings(
            store = store,
            preferences = preferences,
            backendToken = backendToken,
            actions = actions,
            onTestConnection = onTestConnection,
            onOpenAIModels = onOpenAIModels,
            showsQuickSentenceToggle = showsQuickSentenceToggle,
            aiActionBarShown = aiActionBarShown,
            onAIActionBarShownChange = onAIActionBarShownChange
        )
        SettingsGroup(title = "", footer = "基础查词无需下载；句段翻译语言包和扩展词典按需准备。") {
            SettingsLinkRow("离线资源", "", onOpenOffline, showDivider = false)
        }
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
    showsQuickSentenceToggle: Boolean = true,
    aiActionBarShown: Boolean? = null,
    onAIActionBarShownChange: (Boolean) -> Unit = {}
) {
    val providers by store.aiProviders.collectAsState()
    val keyed by store.keyedProviderIDs.collectAsState()
    var tokenText by remember { mutableStateOf(backendToken) }
    var testState by remember { mutableStateOf<ConnectionTestState>(ConnectionTestState.Idle) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

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
            // iOS gives the success haptic when the test passes.
            if (failure == null) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            testJob = null
        }
    }

    // iOS keeps every translation control in one Form section headed 「翻译」.
    SettingsGroup(title = "翻译", icon = JerreaderIcon.SPEECH_BUBBLE, accentedHeader = true) {
        if (showsQuickSentenceToggle) {
            SettingsToggleRow("轻点正文快速翻译", quick, actions.updateQuickEnabled)
            SettingsPillRow(
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
            onSelect = {
                // iOS ticks whenever the service changes.
                if (it != preferences.providerMode) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
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
                TranslationDisplayMode.NEAR_SELECTION -> "译文显示在选中文字附近，自动避开当前选区。"
                TranslationDisplayMode.TOP_BANNER -> "译文优先显示在页面上方；靠近顶部选词时会自动避让选区。"
            }
        )
        aiActionBarShown?.let { shown ->
            SettingsToggleRow("翻译卡片显示 AI 按钮", shown, onAIActionBarShownChange)
        }

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

        // The rows that follow the service; iOS springs them (`stateChange`) when it changes.
        Column(Modifier.fillMaxWidth().animateContentSize(JerreaderMotion.stateChange())) {
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

/**
 * Whether the service picked can be called: ready reads in jade (the success
 * colour), missing setup in orange, each on a 12% tint of itself.
 */
@Composable
private fun ConfigurationStatus(message: String, ready: Boolean) {
    val tint = if (ready) jerreaderPalette.jade else aiWarning()
    Row(
        Modifier
            .fillMaxWidth()
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(JerreaderRadius.small))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        JerreaderGlyph(
            if (ready) JerreaderIcon.SHIELD else JerreaderIcon.WARNING,
            tint,
            Modifier.size(16.dp)
        )
        Text(message, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/**
 * The 测试连接 capsule, as iOS `JerreaderTintedButtonStyle(height: 40)` draws
 * it (dimmed to 0.45 while it cannot run), and the line under it.
 */
@Composable
private fun ConnectionTest(
    state: ConnectionTestState,
    enabled: Boolean,
    showDivider: Boolean = true,
    onTest: () -> Unit
) {
    val accent = jerreaderPalette.accent
    val source = remember { MutableInteractionSource() }
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
                .heightIn(min = 40.dp)
                .jerreaderPressScale(source)
                .alpha(if (enabled) 1f else 0.45f)
                .background(accent.copy(alpha = 0.12f), CircleShape)
                .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onTest)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            when (state) {
                ConnectionTestState.Testing -> CircularProgressIndicator(Modifier.size(16.dp), color = accent, strokeWidth = 2.dp)
                ConnectionTestState.Success -> AIGlyphIcon(AIGlyph.CHECK_CIRCLE_FILLED, accent, Modifier.size(16.dp))
                is ConnectionTestState.Failure -> JerreaderGlyph(JerreaderIcon.RESTORE, accent, Modifier.size(16.dp))
                ConnectionTestState.Idle -> JerreaderGlyph(JerreaderIcon.SWAP, accent, Modifier.size(16.dp))
            }
            Text(title, color = accent, fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Text(
            detail,
            style = MaterialTheme.typography.labelMedium,
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
