package com.jerreader.android.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jerreader.android.AppGraph
import com.jerreader.android.ui.AIBarAction
import com.jerreader.android.ui.AIModelsScreen
import com.jerreader.android.ui.AIProviderRequests
import com.jerreader.android.ui.AISheetBar
import com.jerreader.android.ui.OfflineDictionaryScreen
import com.jerreader.android.ui.ReaderInterfaceSection
import com.jerreader.android.ui.TranslationSettingsContent
import com.jerreader.android.ui.aiPageMargin
import com.jerreader.android.ui.testTranslationConnection
import com.jerreader.android.ui.translationSettingsActions
import com.jerreader.shared.library.ReaderAppearance
import com.jerreader.shared.library.ReaderFontOption
import com.jerreader.shared.library.ReaderTextOrientation
import com.jerreader.shared.library.ReaderThemeOption
import com.jerreader.shared.translation.QuickTranslationUnit
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.PageSheetShape
import com.jerreader.shared.ui.GroupedFormSpacing
import com.jerreader.shared.ui.LocalGroupedFormSpacing
import com.jerreader.shared.ui.PlainFormGroupSpacing
import com.jerreader.shared.ui.PlainFormSpacing
import com.jerreader.shared.ui.JerreaderRowSeparator
import com.jerreader.shared.ui.SettingsBareSliderRow
import com.jerreader.shared.ui.SettingsFootnote
import com.jerreader.shared.ui.SettingsGroup
import com.jerreader.shared.ui.SettingsMenuRow
import com.jerreader.shared.ui.SettingsNavigationRow
import com.jerreader.shared.ui.SettingsSegmentedRow
import com.jerreader.shared.ui.SettingsToggleRow
import com.jerreader.shared.ui.SettingsValueRow
import kotlin.math.roundToInt

/** The pages of the sheet, pushed like the iOS `NavigationStack` inside it. */
private enum class ReaderSettingsPage { ROOT, TRANSLATION, AI_MODELS, OFFLINE }

/**
 * 阅读与翻译, the reader's settings sheet, laid out as iOS `ReaderSettingsView`:
 * 阅读方式, 阅读界面, 阅读交互, 字体与字号 and 版面 (books that reflow only), 背景
 * and 翻译, under an inline title with 完成. It slides up over the page as a
 * large sheet does; 完成, back, or a tap above it closes it, and
 * [onDismissed] runs once it has slid away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderSettingsSheet(
    graph: AppGraph,
    bookTitle: String,
    isPdf: Boolean,
    publicationIsVertical: Boolean,
    appearance: ReaderAppearance,
    onAppearanceChange: (ReaderAppearance) -> Unit,
    onDismissed: () -> Unit
) {
    // One value drives the dimming and the slide, so the window goes away
    // only once both have finished.
    val presence = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val close = { closing = true }
    LaunchedEffect(closing) {
        if (closing) {
            presence.animateTo(0f, tween(durationMillis = 240, easing = FastOutLinearInEasing))
            onDismissed()
        } else {
            presence.animateTo(1f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
        }
    }
    var page by remember { mutableStateOf(ReaderSettingsPage.ROOT) }
    // Composed before the pages, so a handler inside one (a sheet on the AI
    // pages) is asked first.
    BackHandler {
        when (page) {
            ReaderSettingsPage.ROOT -> close()
            ReaderSettingsPage.TRANSLATION -> page = ReaderSettingsPage.ROOT
            ReaderSettingsPage.AI_MODELS, ReaderSettingsPage.OFFLINE -> page = ReaderSettingsPage.TRANSLATION
        }
    }
    // The iOS sheet keeps its place below the status bar's area when the
    // immersive layout hides the bar, so the inset ignores its visibility.
    val topInsets = WindowInsets.statusBarsIgnoringVisibility.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top)

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = presence.value }
                .background(Color.Black.copy(alpha = 0.32f))
        )
        Column(Modifier.fillMaxSize().graphicsLayer { translationY = (1f - presence.value) * size.height }) {
            // The strip above the sheet closes it, as a tap outside a sheet does.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "关闭"
                    ) { close() }
                    .windowInsetsPadding(topInsets)
                    .height(10.dp)
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .consumeWindowInsets(topInsets)
                    .clip(PageSheetShape)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                when (page) {
                    ReaderSettingsPage.ROOT -> Column(Modifier.fillMaxSize()) {
                        AISheetBar(
                            "阅读与翻译",
                            trailing = AIBarAction("完成", emphasized = true, onClick = close)
                        )
                        SheetList(spacing = 33.dp) {
                            ReadingModeSection(bookTitle, isPdf, publicationIsVertical, appearance, onAppearanceChange)
                            ReaderInterfaceSection(graph.appSettings, Modifier.padding(bottom = FooterGroupExtraSpace))
                            InteractionSection(graph, isReflowable = !isPdf)
                            if (!isPdf) {
                                TypographySection(appearance, onAppearanceChange)
                                SpacingSection(appearance, onAppearanceChange)
                            }
                            BackgroundSection(appearance, onAppearanceChange)
                            SettingsGroup(title = "翻译", footer = "翻译服务与密钥对所有书籍通用。") {
                                SettingsNavigationRow(
                                    title = "翻译与 AI",
                                    detail = "服务商、三语互译与提示词",
                                    icon = JerreaderIcon.SPEECH_BUBBLE,
                                    onClick = { page = ReaderSettingsPage.TRANSLATION },
                                    showDivider = false
                                )
                            }
                        }
                    }

                    ReaderSettingsPage.TRANSLATION -> Column(Modifier.fillMaxSize()) {
                        AISheetBar(
                            "翻译与 AI",
                            back = AIBarAction("阅读与翻译") { page = ReaderSettingsPage.ROOT }
                        )
                        // The same plain Form as 设置 › 翻译 on iOS, spaced the same way.
                        SheetList(spacing = PlainFormGroupSpacing, formSpacing = PlainFormSpacing) {
                            val preferences by graph.translationSettings.preferences.collectAsState()
                            val actions = remember { translationSettingsActions(graph.translationSettings) }
                            TranslationSettingsContent(
                                store = graph.translationSettings,
                                preferences = preferences,
                                backendToken = graph.translationSettings.backendAccessToken(),
                                actions = actions,
                                onTestConnection = { testTranslationConnection(graph, preferences) },
                                showsQuickSentenceToggle = false,
                                onOpenAIModels = { page = ReaderSettingsPage.AI_MODELS },
                                onOpenOffline = { page = ReaderSettingsPage.OFFLINE }
                            )
                        }
                    }

                    ReaderSettingsPage.AI_MODELS -> {
                        val requests = remember { AIProviderRequests.live(graph.configuredTranslationService) }
                        AIModelsScreen(
                            store = graph.translationSettings,
                            requests = requests,
                            backTitle = "翻译与 AI",
                            onBack = { page = ReaderSettingsPage.TRANSLATION }
                        )
                    }

                    ReaderSettingsPage.OFFLINE -> Column(Modifier.fillMaxSize()) {
                        AISheetBar(
                            "离线资源",
                            back = AIBarAction("翻译与 AI") { page = ReaderSettingsPage.TRANSLATION }
                        )
                        OfflineDictionaryScreen(
                            graph.offlineDictionary,
                            graph.localTranslationService,
                            Modifier.weight(1f).navigationBarsPadding()
                        )
                    }
                }
                // The sheet's grabber, over the top of its bar.
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 5.dp)
                        .size(36.dp, 5.dp)
                        .clip(RoundedCornerShape(2.5.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                )
            }
        }
    }
}

/** The sheet's grouped list, with the inset-grouped margins. */
@Composable
private fun ColumnScope.SheetList(
    spacing: androidx.compose.ui.unit.Dp,
    formSpacing: GroupedFormSpacing? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(horizontal = aiPageMargin())
                .padding(top = 15.dp, bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            if (formSpacing != null) {
                CompositionLocalProvider(LocalGroupedFormSpacing provides formSpacing) { content() }
            } else {
                content()
            }
        }
    }
}

/**
 * What the iOS list adds below a footer on top of the section spacing: its
 * footer gap comes to about 50 points against 38 without one.
 */
private val FooterGroupExtraSpace = 11.dp

// MARK: 阅读方式

@Composable
private fun ReadingModeSection(
    bookTitle: String,
    isPdf: Boolean,
    publicationIsVertical: Boolean,
    appearance: ReaderAppearance,
    onChange: (ReaderAppearance) -> Unit
) {
    SettingsGroup(
        title = "阅读方式",
        modifier = Modifier.padding(bottom = FooterGroupExtraSpace),
        footer = "这一页的改动只作用于《$bookTitle》。要改所有新书的默认值，请到「设置 → 默认阅读排版」。"
    ) {
        if (isPdf) {
            SettingsFootnote("PDF 按单页显示：点按左右边缘翻页，点正文句子翻译；没有文字层的扫描页会在本机按页 OCR。")
            SettingsToggleRow(
                "论文双栏模式",
                appearance.pdfPaperModeEnabled,
                { onChange(appearance.copy(pdfPaperModeEnabled = it)) }
            )
            SettingsFootnote(
                "开启后，轻点识别句子、段落或 OCR 文字时只使用所点的左栏或右栏，避免选区横跨中缝。系统长按手柄仍保持原生规则。",
                showDivider = false
            )
        } else {
            // Offered only for books that render vertically, plus any book
            // already switched to 横排 so it can switch back (iOS
            // `showsTextOrientationPicker`).
            val showsOrientation = publicationIsVertical ||
                appearance.orientation != ReaderTextOrientation.PUBLICATION
            SettingsSegmentedRow(
                title = "翻页方式",
                options = listOf(false to "逐页", true to "滚动"),
                selected = appearance.scroll,
                onSelect = { onChange(appearance.copy(scroll = it)) }
            )
            SettingsFootnote(
                if (appearance.scroll) "上下连续滚动阅读" else "每次只显示一页，点击两侧或使用按钮翻页",
                showDivider = showsOrientation
            )
            if (showsOrientation) {
                SettingsSegmentedRow(
                    title = "文字方向",
                    options = listOf(
                        ReaderTextOrientation.PUBLICATION to "原版",
                        ReaderTextOrientation.HORIZONTAL to "横排"
                    ),
                    selected = appearance.orientation,
                    onSelect = { onChange(appearance.copy(orientation = it)) }
                )
                SettingsFootnote(
                    if (appearance.orientation == ReaderTextOrientation.HORIZONTAL) {
                        "将日文竖排转为横排，并统一按从左向右的方向逐页阅读。"
                    } else {
                        "保留书籍原有的横竖排与翻页方向；日文竖排书通常会从右向左翻页。"
                    },
                    showDivider = false
                )
            }
        }
    }
}

// MARK: 阅读交互

@Composable
private fun InteractionSection(graph: AppGraph, isReflowable: Boolean) {
    val store = graph.translationSettings
    val preferences by store.preferences.collectAsState()
    val quick = preferences.quickTranslationEnabled
    SettingsGroup(title = "阅读交互") {
        SettingsToggleRow("轻点正文快速翻译", quick, store::updateQuickTranslationEnabled)
        SettingsSegmentedRow(
            title = "翻译范围",
            options = listOf(
                QuickTranslationUnit.SENTENCE to "句子",
                QuickTranslationUnit.PARAGRAPH to "段落"
            ),
            selected = preferences.quickTranslationUnit,
            onSelect = store::updateQuickTranslationUnit,
            enabled = quick
        )
        SettingsToggleRow(
            "轻点翻译时禁用点按翻页",
            preferences.disablesTapPageTurnsDuringQuickTranslation,
            store::updateDisablesTapPageTurns,
            enabled = quick
        )
        val target = if (preferences.quickTranslationUnit == QuickTranslationUnit.SENTENCE) "当前句子" else "当前段落"
        SettingsFootnote(
            when {
                !quick && isReflowable ->
                    "已关闭：轻点词语直接查词并记入学习记录，轻点空白处显示或隐藏阅读界面；长按选词、拖动选区仍可翻译。"
                !quick -> "已关闭：轻点正文只显示或隐藏阅读界面；长按选词、拖动选区仍可翻译。"
                preferences.disablesTapPageTurnsDuringQuickTranslation ->
                    "轻点正文任意位置都会翻译$target；翻页请用横向轻扫或底部按钮。"
                else -> "轻点正文中间翻译$target；轻点左右边缘仍然翻页。"
            },
            showDivider = false
        )
    }
}

// MARK: 字体与字号, 版面

/** iOS `ReaderFontSizing`: 17 points at 100%, 12–32 points in half-point steps. */
private const val BASE_POINT_SIZE = 17.0

internal fun readerPointSize(fontScale: Double): Double {
    val clamped = ((if (fontScale.isFinite()) fontScale else 1.0) * BASE_POINT_SIZE).coerceIn(12.0, 32.0)
    return 12.0 + ((clamped - 12.0) / 0.5).roundToInt() * 0.5
}

@Composable
private fun TypographySection(appearance: ReaderAppearance, onChange: (ReaderAppearance) -> Unit) {
    val points = readerPointSize(appearance.fontScale)
    SettingsGroup(title = "字体与字号") {
        SettingsMenuRow(
            title = "字体",
            options = readerFontOptions(appearance.font),
            selected = appearance.font,
            onSelect = { onChange(appearance.copy(font = it)) }
        )
        SettingsValueRow(
            "字号",
            if (points % 1.0 == 0.0) "${points.toInt()} 号" else "$points 号",
            compact = false
        )
        SettingsBareSliderRow(
            value = points.toFloat(),
            range = 12f..32f,
            steps = 39,
            onValueChange = { value ->
                onChange(appearance.copy(fontScale = readerPointSize(value / BASE_POINT_SIZE) / BASE_POINT_SIZE))
            },
            accessibilityLabel = "字号",
            leading = { SizeGlyph("小", 10.5.sp) },
            trailing = { SizeGlyph("大", 14.sp) },
            showDivider = false
        )
    }
}

/**
 * iOS `ReaderFontChoice`: 衬线, 无衬线 and OpenDyslexic. 原书 is no longer
 * offered, but a book or default that still uses it keeps its name until
 * another font is picked.
 */
internal fun readerFontOptions(current: ReaderFontOption): List<Pair<ReaderFontOption, String>> = buildList {
    if (current == ReaderFontOption.PUBLICATION) add(ReaderFontOption.PUBLICATION to "原书")
    add(ReaderFontOption.SERIF to "衬线 / 日文明朝")
    add(ReaderFontOption.SANS_SERIF to "无衬线 / 日文黑体")
    add(ReaderFontOption.OPEN_DYSLEXIC to "OpenDyslexic")
}

/**
 * Stands in for `textformat.size.smaller` / `.larger`, which a Chinese
 * system draws as 「小」 and 「大」.
 */
@Composable
private fun SizeGlyph(text: String, size: TextUnit) {
    Text(
        text,
        modifier = Modifier.clearAndSetSemantics {},
        fontSize = size,
        fontWeight = FontWeight.Normal,
        // `.secondary` on a plain symbol: the label colour at half strength.
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    )
}

private fun roundedTenth(value: Float): Double = (value * 10f).roundToInt() / 10.0

private fun oneDecimal(value: Double): String {
    val tenths = (value * 10).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}

@Composable
private fun SpacingSection(appearance: ReaderAppearance, onChange: (ReaderAppearance) -> Unit) {
    SettingsGroup(title = "版面") {
        SettingsValueRow("行间距", oneDecimal(appearance.lineHeight), compact = false)
        SettingsBareSliderRow(
            value = appearance.lineHeight.toFloat().coerceIn(1f, 2.2f),
            range = 1f..2.2f,
            steps = 11,
            onValueChange = { onChange(appearance.copy(lineHeight = roundedTenth(it))) },
            accessibilityLabel = "行间距"
        )
        SettingsValueRow(
            "段间距",
            if (appearance.paragraphSpacing == 0.0) "紧凑" else "${oneDecimal(appearance.paragraphSpacing)} em",
            compact = false
        )
        SettingsBareSliderRow(
            value = appearance.paragraphSpacing.toFloat().coerceIn(0f, 2f),
            range = 0f..2f,
            steps = 19,
            onValueChange = { onChange(appearance.copy(paragraphSpacing = roundedTenth(it))) },
            accessibilityLabel = "段间距"
        )
        SettingsValueRow(
            "页边距",
            when {
                appearance.pageMargins < 0.8 -> "窄"
                appearance.pageMargins < 1.3 -> "标准"
                else -> "宽"
            },
            compact = false
        )
        SettingsBareSliderRow(
            value = appearance.pageMargins.toFloat().coerceIn(0.5f, 2f),
            range = 0.5f..2f,
            steps = 14,
            onValueChange = { onChange(appearance.copy(pageMargins = roundedTenth(it))) },
            accessibilityLabel = "页边距",
            showDivider = false
        )
    }
}

// MARK: 背景

private class ThemeSwatch(val theme: ReaderThemeOption, val title: String, val color: Color)

private val themeSwatches = listOf(
    ThemeSwatch(ReaderThemeOption.LIGHT, "浅色", Color.White),
    ThemeSwatch(ReaderThemeOption.SEPIA, "护眼", Color(0.98f, 0.95f, 0.88f)),
    ThemeSwatch(ReaderThemeOption.COOL_GRAY, "冷灰", Color(0.93f, 0.96f, 0.98f)),
    ThemeSwatch(ReaderThemeOption.DARK, "深色", Color(0.08f, 0.10f, 0.13f))
)

@Composable
private fun BackgroundSection(appearance: ReaderAppearance, onChange: (ReaderAppearance) -> Unit) {
    SettingsGroup(title = "背景") {
        Column(Modifier.fillMaxWidth()) {
            Row(
                // iOS: circle 14.8 under the card's top, label 8.8 under the
                // circle, 16.7 from the label to the row's end; 88 in all.
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 15.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                themeSwatches.forEach { swatch ->
                    val selected = appearance.customBackgroundHex.isEmpty() && appearance.theme == swatch.theme
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "swatchPress")
                    Column(
                        Modifier
                            .weight(1f)
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .semantics(mergeDescendants = true) {
                                contentDescription = "${swatch.title}背景"
                                this.selected = selected
                            }
                            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                                onChange(appearance.copy(customBackgroundHex = "", theme = swatch.theme))
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.5.dp)
                    ) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(swatch.color)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
                                    },
                                    shape = CircleShape
                                )
                        )
                        Text(
                            swatch.title,
                            modifier = Modifier.clearAndSetSemantics {},
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            JerreaderRowSeparator(start = 20.dp)
        }
        val customBackground = appearance.customBackgroundHex.isNotEmpty()
        val customSelection = appearance.customSelectionColorHex.isNotEmpty()
        SettingsToggleRow(
            "自定义背景颜色",
            customBackground,
            { onChange(appearance.copy(customBackgroundHex = if (it) "#EEF3F8" else "")) }
        )
        if (customBackground) {
            ReaderColorRow("选择背景颜色", appearance.customBackgroundHex) {
                onChange(appearance.copy(customBackgroundHex = it))
            }
        }
        SettingsToggleRow(
            "自定义选区颜色",
            customSelection,
            { onChange(appearance.copy(customSelectionColorHex = if (it) "#317DC2" else "")) },
            showDivider = customSelection
        )
        if (customSelection) {
            ReaderColorRow("选择选区颜色", appearance.customSelectionColorHex, showDivider = false) {
                onChange(appearance.copy(customSelectionColorHex = it))
            }
        }
    }
}

private fun colorFromHex(hex: String): Color? {
    val digits = hex.trim().removePrefix("#")
    if (digits.length != 6) return null
    val value = digits.toLongOrNull(16) ?: return null
    return Color(0xFF000000L or value)
}

private fun hexFromColor(color: Color): String = "#%06X".format(color.toArgb() and 0xFFFFFF)

/** `ColorPicker`: the title and a colour well that opens the picker. */
@Composable
internal fun ReaderColorRow(
    title: String,
    hex: String,
    showDivider: Boolean = true,
    onHexChange: (String) -> Unit
) {
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { picking = true }
                .heightIn(min = 44.dp)
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            ColorWell(colorFromHex(hex) ?: Color.White)
        }
        if (showDivider) JerreaderRowSeparator(start = 20.dp)
    }
    if (picking) {
        ReaderColorPickerDialog(hex = hex, onHexChange = onHexChange, onDismiss = { picking = false })
    }
}

private val wellRing = listOf(
    Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFCC00), Color(0xFF34C759),
    Color(0xFF00C7BE), Color(0xFF007AFF), Color(0xFFAF52DE), Color(0xFFFF2D55), Color(0xFFFF3B30)
)

@Composable
private fun ColorWell(color: Color) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(Brush.sweepGradient(wellRing)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .padding(2.dp)
                .clip(CircleShape)
                .background(color)
        )
    }
}

/**
 * The system colour picker's grid: a grey ramp, then twelve hues from deep to
 * pale, plus the hexadecimal value. Opacity is not offered, as on iOS
 * (`supportsOpacity: false`).
 */
private val pickerGrid: List<List<Color>> = buildList {
    add(List(12) { column -> Color.hsv(0f, 0f, 1f - column / 11f) })
    val hues = List(12) { column -> ((195 + column * 30) % 360).toFloat() }
    for (row in 0 until 9) {
        val t = row / 8f
        val value = 0.35f + 0.65f * (t * 2f).coerceAtMost(1f)
        val saturation = if (t <= 0.5f) 1f else 1f - 0.85f * (t - 0.5f) * 2f
        add(hues.map { Color.hsv(it, saturation, value) })
    }
}

@Composable
private fun ReaderColorPickerDialog(hex: String, onHexChange: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(hex) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth()) {
                    Text(
                        "颜色",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "完成",
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .clickable(role = Role.Button, onClick = onDismiss)
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))) {
                    pickerGrid.forEach { rowColors ->
                        Row(Modifier.fillMaxWidth()) {
                            rowColors.forEach { color ->
                                val cellHex = hexFromColor(color)
                                val picked = cellHex.equals(text, ignoreCase = true)
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .background(color)
                                        .then(
                                            if (picked) {
                                                Modifier
                                                    .border(3.dp, Color.White)
                                                    .border(1.dp, Color.Black.copy(alpha = 0.5f))
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .semantics { contentDescription = cellHex; selected = picked }
                                        .clickable(role = Role.Button) {
                                            text = cellHex
                                            onHexChange(cellHex)
                                        }
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { value ->
                        val digits = value.trim().uppercase().removePrefix("#")
                            .filter { it in "0123456789ABCDEF" }.take(6)
                        text = "#$digits"
                        if (digits.length == 6) onHexChange(text)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("十六进制颜色 #RRGGBB", style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    singleLine = true
                )
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}
