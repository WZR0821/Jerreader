package com.jerreader.android.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
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
import com.jerreader.shared.ui.GroupedFormSpacing
import com.jerreader.shared.ui.JerreaderDivider
import com.jerreader.shared.ui.JerreaderElevation
import com.jerreader.shared.ui.JerreaderGlyph
import com.jerreader.shared.ui.JerreaderIcon
import com.jerreader.shared.ui.JerreaderIconTile
import com.jerreader.shared.ui.JerreaderMotion
import com.jerreader.shared.ui.JerreaderPillOption
import com.jerreader.shared.ui.JerreaderPillPicker
import com.jerreader.shared.ui.JerreaderRadius
import com.jerreader.shared.ui.JerreaderRowSeparator
import com.jerreader.shared.ui.JerreaderSpacing
import com.jerreader.shared.ui.JerreaderToggle
import com.jerreader.shared.ui.JerreaderUpDownChevron
import com.jerreader.shared.ui.LocalGroupedFormSpacing
import com.jerreader.shared.ui.PageSheetShape
import com.jerreader.shared.ui.PlainFormGroupSpacing
import com.jerreader.shared.ui.PlainFormSpacing
import com.jerreader.shared.ui.jerreaderCanvasBackground
import com.jerreader.shared.ui.jerreaderElevation
import com.jerreader.shared.ui.jerreaderPalette
import com.jerreader.shared.ui.jerreaderPaperCard
import com.jerreader.shared.ui.jerreaderPressScale
import com.jerreader.shared.ui.plainTertiary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** The pages of the sheet, pushed like the iOS `NavigationStack` inside it. */
private enum class ReaderSettingsPage { ROOT, TRANSLATION, AI_MODELS, OFFLINE }

/**
 * 阅读与翻译, the reader's settings sheet, laid out as the iOS 2.0
 * `ReaderSettingsView`: titled paper cards for 阅读方式, 阅读界面, 阅读交互,
 * 字体与字号 and 版面 (books that reflow only), 背景 and 翻译 on the canvas,
 * under an inline title with 完成. It slides up over the page as a large
 * sheet does; 完成, back, or a tap above it closes it, and [onDismissed] runs
 * once it has slid away.
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
                    // Every page of the iOS sheet sits on the canvas.
                    .jerreaderCanvasBackground()
            ) {
                when (page) {
                    ReaderSettingsPage.ROOT -> Column(Modifier.fillMaxSize()) {
                        AISheetBar(
                            "阅读与翻译",
                            trailing = AIBarAction("完成", emphasized = true, onClick = close)
                        )
                        ReaderSheetList {
                            ReadingModeSection(bookTitle, isPdf, publicationIsVertical, appearance, onAppearanceChange)
                            ReaderInterfaceSection(graph.appSettings)
                            InteractionSection(graph, isReflowable = !isPdf)
                            if (!isPdf) {
                                TypographySection(appearance, onAppearanceChange)
                                SpacingSection(appearance, onAppearanceChange, graph.appSettings)
                            }
                            BackgroundSection(appearance, onAppearanceChange)
                            TranslationSection(onOpen = { page = ReaderSettingsPage.TRANSLATION })
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
                            val appPreferences by graph.appSettings.preferences.collectAsState()
                            val actions = remember { translationSettingsActions(graph.translationSettings) }
                            TranslationSettingsContent(
                                store = graph.translationSettings,
                                preferences = preferences,
                                backendToken = graph.translationSettings.backendAccessToken(),
                                actions = actions,
                                onTestConnection = { testTranslationConnection(graph, preferences) },
                                showsQuickSentenceToggle = false,
                                onOpenAIModels = { page = ReaderSettingsPage.AI_MODELS },
                                onOpenOffline = { page = ReaderSettingsPage.OFFLINE },
                                aiActionBarShown = !appPreferences.aiActionBarHidden,
                                onAIActionBarShownChange = { shown -> graph.appSettings.updateAIActionBarHidden(!shown) }
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
                // Three-button navigation: the buttons sit on the canvas rather
                // than on whichever row has scrolled under them. With gesture
                // navigation nothing there can be tapped, so the strip has no
                // height and the rows run to the edge of the screen.
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsBottomHeight(WindowInsets.tappableElement)
                        .background(jerreaderPalette.canvas)
                )
            }
        }
    }
}

/**
 * The root page's scroll of cards, as the iOS ScrollView: 24dp apart, 20dp
 * from the sides, 8dp below the bar and 32dp above the end, at most 680dp wide.
 */
@Composable
private fun ColumnScope.ReaderSheetList(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            Modifier
                .widthIn(max = 680.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(top = JerreaderSpacing.xs, bottom = JerreaderSpacing.xxl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(JerreaderSpacing.xl),
            content = content
        )
    }
}

/** The 翻译与 AI page's grouped list, with the inset-grouped margins. */
@Composable
private fun ColumnScope.SheetList(
    spacing: Dp,
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

// MARK: Cards and rows, after iOS `ReaderSettingsCard` and its rows

/** `JerreaderMotion.stateChange` in a form that transitions and size changes take. */
private fun <T> readerSheetMotion(): FiniteAnimationSpec<T> = JerreaderMotion.stateChange()

/**
 * iOS `ReaderSettingsCard`, the 2.0 replacement for a Form section: a 13sp
 * semibold secondary title, the rows on a paper card (radius 20, `line`
 * hairline, e1 on light paper) and an optional 13sp secondary footer, 8dp
 * apart. A footer that changes cross-fades, as the card animates on iOS.
 */
@Composable
internal fun ReaderSheetCard(
    title: String,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = jerreaderPalette
    val shape = RoundedCornerShape(JerreaderRadius.card)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(JerreaderSpacing.xs)) {
        Text(
            title,
            modifier = Modifier.padding(horizontal = JerreaderSpacing.xxs).semantics { heading() },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = palette.secondaryText
        )
        Column(
            Modifier
                .fillMaxWidth()
                .jerreaderPaperCard(shape)
                .clip(shape),
            content = content
        )
        if (footer != null) {
            AnimatedContent(
                targetState = footer,
                transitionSpec = {
                    fadeIn(readerSheetMotion()) togetherWith fadeOut(readerSheetMotion()) using
                        SizeTransform(clip = false) { _, _ -> readerSheetMotion() }
                },
                label = "readerSheetFooter"
            ) { text ->
                Text(
                    text,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = JerreaderSpacing.xxs),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.secondaryText
                )
            }
        }
    }
}

/** iOS `ReaderSettingsDivider`: the hairline between two rows of a card, from 16dp in. */
@Composable
internal fun ReaderSheetDivider(modifier: Modifier = Modifier) {
    JerreaderDivider(modifier.padding(start = JerreaderSpacing.md), color = jerreaderPalette.line)
}

/** iOS `readerSettingsRow()`: 16dp at the sides, 11dp above and below, at least 44dp tall. */
internal fun Modifier.readerSheetRow(): Modifier = this
    .fillMaxWidth()
    .heightIn(min = 44.dp)
    .padding(horizontal = JerreaderSpacing.md, vertical = 11.dp)

/**
 * Rows that come and go inside a card, growing and fading with the card's
 * spring as iOS animates them (`rowAnimation`).
 */
@Composable
internal fun ColumnScope.ReaderSheetRows(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(readerSheetMotion()) + expandVertically(readerSheetMotion()),
        exit = fadeOut(readerSheetMotion()) + shrinkVertically(readerSheetMotion()),
        label = "readerSheetRows"
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/** A row's title over its control: iOS `.subheadline.weight(.semibold)`. */
@Composable
private fun ReaderSheetControlTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
}

/** What a choice does, in iOS `.caption` secondary text. */
@Composable
private fun ReaderSheetDetail(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelMedium, color = jerreaderPalette.secondaryText)
}

/**
 * A title over a [JerreaderPillPicker] and what the choice does, 10dp
 * apart, as 翻页方式 on iOS; the caption cross-fades when the choice changes.
 * A row that is off fades to 45% and takes no touches, as `.disabled` does.
 */
@Composable
internal fun <T> ReaderSheetPillRow(
    title: String,
    options: List<JerreaderPillOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true
) {
    val strength by animateFloatAsState(if (enabled) 1f else 0.45f, JerreaderMotion.stateChange(), label = "pillRowStrength")
    Column(
        modifier.readerSheetRow().graphicsLayer { alpha = strength },
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ReaderSheetControlTitle(title)
        Box {
            JerreaderPillPicker(options = options, selected = selected, onSelect = { if (enabled) onSelect(it) })
            if (!enabled) {
                // The picker has no switch of its own, so a cover takes the touches.
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                )
            }
        }
        if (detail != null) {
            AnimatedContent(
                targetState = detail,
                transitionSpec = {
                    fadeIn(readerSheetMotion()) togetherWith fadeOut(readerSheetMotion()) using
                        SizeTransform(clip = false) { _, _ -> readerSheetMotion() }
                },
                label = "pillRowDetail"
            ) { text ->
                ReaderSheetDetail(text, Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * A switch row: the title and a [JerreaderToggle] that carries it as its
 * label, and an optional caption under both. Only the switch toggles, as on
 * iOS. A 31dp switch with 11dp above and below makes the row 53dp; the
 * switch's 44dp touch box takes 6.5dp of each margin.
 */
@Composable
internal fun ReaderSheetToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    detail: String? = null
) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 53.dp)
            .padding(
                start = JerreaderSpacing.md,
                end = JerreaderSpacing.md,
                top = 4.5.dp,
                bottom = if (detail == null) 4.5.dp else 11.dp
            ),
        verticalArrangement = Arrangement.Center
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                modifier = Modifier.weight(1f).padding(end = JerreaderSpacing.sm).padding(vertical = 6.5.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else jerreaderPalette.secondaryText
            )
            JerreaderToggle(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                label = title
            )
        }
        if (detail != null) {
            // 8dp under the switch itself, which ends 6.5dp above its box.
            Spacer(Modifier.height(1.5.dp))
            ReaderSheetDetail(detail)
        }
    }
}

/**
 * A title and its value over a slider, as the iOS 版面 and 透明度 rows. The
 * slider carries the title, so accessibility services find and set it by
 * name. A UISlider is 31dp tall; the Material one keeps its 48dp touch box,
 * which reaches 8.5dp past the row's slot above and below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSheetSliderRow(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null
) {
    Column(modifier.readerSheetRow(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                modifier = Modifier.weight(1f).padding(end = JerreaderSpacing.sm),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                valueText,
                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                color = jerreaderPalette.secondaryText
            )
        }
        Box(Modifier.fillMaxWidth().trimHeight(8.5.dp)) {
            Slider(
                value = value.coerceIn(range.start, range.endInclusive),
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = title },
                steps = steps,
                thumb = { ReaderSheetSliderThumb() },
                track = { state -> ReaderSheetSliderTrack(state) },
                valueRange = range
            )
        }
        if (detail != null) ReaderSheetDetail(detail)
    }
}

/** The UISlider thumb: a 28dp white disc with an e1 shadow. */
@Composable
private fun ReaderSheetSliderThumb(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(SliderThumbRadius * 2)
            .jerreaderElevation(JerreaderElevation.CARD, CircleShape)
            .background(Color.White, CircleShape)
    )
}

/**
 * UISlider's 4dp track: the accent up to the thumb, a faint fill after it.
 * The Material track stops at the thumb's centre at either end, so it is
 * drawn a thumb radius wider on each side to run under the thumb, as iOS does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSheetSliderTrack(state: SliderState, modifier: Modifier = Modifier) {
    val active = jerreaderPalette.accent
    val inactive = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .widenBy(SliderThumbRadius)
            .drawBehind {
                val span = state.valueRange.endInclusive - state.valueRange.start
                val fraction = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
                val corner = CornerRadius(size.height / 2)
                val inset = SliderThumbRadius.toPx()
                drawRoundRect(inactive, cornerRadius = corner)
                drawRoundRect(
                    active,
                    size = Size(inset + fraction * (size.width - 2 * inset), size.height),
                    cornerRadius = corner
                )
            }
    )
}

private val SliderThumbRadius = 14.dp

/** Lays the content out [each] wider on both sides than the space it is given. */
private fun Modifier.widenBy(each: Dp): Modifier = this.layout { measurable, constraints ->
    val extra = each.roundToPx() * 2
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + extra,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else Constraints.Infinity
        )
    )
    layout((placeable.width - extra).coerceAtLeast(0), placeable.height) { placeable.place(-extra / 2, 0) }
}

/** Reports [each] less height above and below than the content, which still draws and takes touches there. */
private fun Modifier.trimHeight(each: Dp): Modifier = this.layout { measurable, constraints ->
    val trim = each.roundToPx()
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
    layout(placeable.width, (placeable.height - trim * 2).coerceAtLeast(0)) { placeable.place(0, -trim) }
}

/**
 * iOS's menu-style Picker on a card row: the title, the choice in the accent
 * with ⌃⌄, and a menu that ticks the current choice.
 */
@Composable
private fun <T> ReaderSheetMenuRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = jerreaderPalette
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .clickable(role = Role.DropdownList) { expanded = true }
                .readerSheetRow(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f).padding(end = JerreaderSpacing.sm),
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                options.firstOrNull { it.first == selected }?.second.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = palette.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            JerreaderUpDownChevron(tint = palette.accent, modifier = Modifier.padding(start = 4.dp).size(13.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                    trailingIcon = if (value == selected) {
                        { JerreaderGlyph(JerreaderIcon.CHECK, palette.accent, Modifier.size(16.dp)) }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

// MARK: 阅读方式

@Composable
private fun ReadingModeSection(
    bookTitle: String,
    isPdf: Boolean,
    publicationIsVertical: Boolean,
    appearance: ReaderAppearance,
    onChange: (ReaderAppearance) -> Unit
) {
    ReaderSheetCard(
        "阅读方式",
        footer = "这一页的改动只作用于《$bookTitle》。要改所有新书的默认值，请到「设置 → 默认阅读排版」。"
    ) {
        if (isPdf) {
            ReaderSheetDetail(
                "PDF 按单页显示：点按左右边缘翻页，点正文句子翻译；没有文字层的扫描页会在本机按页 OCR。",
                Modifier.readerSheetRow()
            )
            ReaderSheetDivider()
            ReaderSheetToggleRow(
                "论文双栏模式",
                appearance.pdfPaperModeEnabled,
                { onChange(appearance.copy(pdfPaperModeEnabled = it)) },
                detail = "开启后，轻点识别句子、段落或 OCR 文字时只使用所点的左栏或右栏，避免选区横跨中缝。系统长按手柄仍保持原生规则。"
            )
        } else {
            ReaderSheetPillRow(
                title = "翻页方式",
                options = listOf(JerreaderPillOption(false, "逐页"), JerreaderPillOption(true, "滚动")),
                selected = appearance.scroll,
                onSelect = { onChange(appearance.copy(scroll = it)) },
                detail = if (appearance.scroll) "上下连续滚动阅读" else "每次只显示一页，点击两侧或使用按钮翻页"
            )
            // Offered only for books that render vertically, plus any book
            // already switched to 横排 so it can switch back (iOS
            // `showsTextOrientationPicker`).
            if (publicationIsVertical || appearance.orientation != ReaderTextOrientation.PUBLICATION) {
                ReaderSheetDivider()
                ReaderSheetPillRow(
                    title = "文字方向",
                    options = buildList {
                        add(JerreaderPillOption(ReaderTextOrientation.PUBLICATION, "原版"))
                        add(JerreaderPillOption(ReaderTextOrientation.HORIZONTAL, "横排"))
                        // 1.7 also offered 竖排. A book still set to it keeps
                        // the option, so the row shows what is in effect
                        // instead of two unselected choices and the wrong
                        // description.
                        if (appearance.orientation == ReaderTextOrientation.VERTICAL) {
                            add(JerreaderPillOption(ReaderTextOrientation.VERTICAL, "竖排"))
                        }
                    },
                    selected = appearance.orientation,
                    onSelect = { onChange(appearance.copy(orientation = it)) },
                    detail = when (appearance.orientation) {
                        ReaderTextOrientation.HORIZONTAL ->
                            "将日文竖排转为横排，并统一按从左向右的方向逐页阅读。"
                        ReaderTextOrientation.VERTICAL ->
                            "这本书此前被设为强制竖排。改选“原版”或“横排”后，这一项不再提供。"
                        ReaderTextOrientation.PUBLICATION ->
                            "保留书籍原有的横竖排与翻页方向；日文竖排书通常会从右向左翻页。"
                    }
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
    val target = if (preferences.quickTranslationUnit == QuickTranslationUnit.SENTENCE) "当前句子" else "当前段落"
    ReaderSheetCard(
        "阅读交互",
        // iOS `interactionDetail`: a book that reflows also looks words up.
        footer = when {
            !quick && isReflowable ->
                "已关闭：轻点词语直接查词并记入学习记录，轻点空白处显示或隐藏阅读界面；长按选词、拖动选区仍可翻译。"
            !quick -> "已关闭：轻点正文只显示或隐藏阅读界面；长按选词、拖动选区仍可翻译。"
            preferences.disablesTapPageTurnsDuringQuickTranslation ->
                "轻点正文任意位置都会翻译$target；翻页请用横向轻扫或底部按钮。"
            else -> "轻点正文中间翻译$target；轻点左右边缘仍然翻页。"
        }
    ) {
        ReaderSheetToggleRow("轻点正文快速翻译", quick, store::updateQuickTranslationEnabled)
        ReaderSheetDivider()
        ReaderSheetPillRow(
            title = "翻译范围",
            options = listOf(
                JerreaderPillOption(QuickTranslationUnit.SENTENCE, "句子"),
                JerreaderPillOption(QuickTranslationUnit.PARAGRAPH, "段落")
            ),
            selected = preferences.quickTranslationUnit,
            onSelect = store::updateQuickTranslationUnit,
            enabled = quick
        )
        ReaderSheetDivider()
        ReaderSheetToggleRow(
            "轻点翻译时禁用点按翻页",
            preferences.disablesTapPageTurnsDuringQuickTranslation,
            store::updateDisablesTapPageTurns,
            enabled = quick
        )
    }
}

// MARK: 字体与字号, 版面

/** iOS `ReaderFontSizing`: 17 points at 100%, 12–32 points in half-point steps. */
private const val BASE_POINT_SIZE = 17.0
private const val MIN_POINT_SIZE = 12.0
private const val MAX_POINT_SIZE = 32.0
private const val POINT_SIZE_STEP = 0.5

internal fun readerPointSize(fontScale: Double): Double {
    val clamped = ((if (fontScale.isFinite()) fontScale else 1.0) * BASE_POINT_SIZE).coerceIn(MIN_POINT_SIZE, MAX_POINT_SIZE)
    return MIN_POINT_SIZE + ((clamped - MIN_POINT_SIZE) / POINT_SIZE_STEP).roundToInt() * POINT_SIZE_STEP
}

@Composable
private fun TypographySection(appearance: ReaderAppearance, onChange: (ReaderAppearance) -> Unit) {
    ReaderSheetCard("字体与字号") {
        ReaderSheetMenuRow(
            title = "字体",
            options = readerFontOptions(appearance.font),
            selected = appearance.font,
            onSelect = { onChange(appearance.copy(font = it)) }
        )
        ReaderSheetDivider()
        // 字号 as "A − value + A", in the slider's old half-point steps.
        Row(
            Modifier.readerSheetRow(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(JerreaderSpacing.sm)
        ) {
            Text("字号", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            ReaderFontSizeStepper(
                points = readerPointSize(appearance.fontScale),
                onStep = { points ->
                    onChange(appearance.copy(fontScale = readerPointSize(points / BASE_POINT_SIZE) / BASE_POINT_SIZE))
                }
            )
        }
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

private fun fontSizeLabel(points: Double): String =
    if (points % 1.0 == 0.0) "${points.toInt()} 号" else "$points 号"

private const val STEPPER_REPEAT_DELAY_MILLIS = 450L
private const val STEPPER_REPEAT_INTERVAL_MILLIS = 100L

/**
 * iOS `ReaderFontSizeStepper`: 字号 as "A − value + A" on a muted capsule,
 * the size rolling up or down as it changes. Each tap moves one half-point
 * step, holding a key repeats it, and each step ticks.
 */
@Composable
private fun ReaderFontSizeStepper(
    points: Double,
    onStep: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val move: (Double) -> Unit = { delta ->
        val next = (points + delta).coerceIn(MIN_POINT_SIZE, MAX_POINT_SIZE)
        if (abs(next - points) > 0.0001) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onStep(next)
        }
    }
    Row(
        modifier
            .background(jerreaderPalette.mutedSurface, CircleShape)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ReaderStepperKey(
            label = "减小字号",
            enabled = points > MIN_POINT_SIZE + 0.001,
            onStep = { move(-POINT_SIZE_STEP) }
        ) {
            Text("A", modifier = Modifier.clearAndSetSemantics {}, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            ReaderStepGlyph(plus = false)
        }
        AnimatedContent(
            targetState = points,
            transitionSpec = {
                val rising = targetState > initialState
                (slideInVertically(readerSheetMotion()) { height -> if (rising) height / 2 else -height / 2 } +
                    fadeIn(readerSheetMotion())) togetherWith
                    (slideOutVertically(readerSheetMotion()) { height -> if (rising) -height / 2 else height / 2 } +
                        fadeOut(readerSheetMotion())) using
                    SizeTransform(clip = false)
            },
            contentAlignment = Alignment.Center,
            label = "fontSize"
        ) { value ->
            Text(
                fontSizeLabel(value),
                modifier = Modifier.widthIn(min = 62.dp),
                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
        ReaderStepperKey(
            label = "增大字号",
            enabled = points < MAX_POINT_SIZE - 0.001,
            onStep = { move(POINT_SIZE_STEP) }
        ) {
            ReaderStepGlyph(plus = true)
            Text("A", modifier = Modifier.clearAndSetSemantics {}, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * iOS `ReaderStepperButtonStyle`: a paper capsule at least 52×38 on the
 * stepper's track, which dims and shrinks to 94% while pressed. Holding it
 * repeats [onStep], as `buttonRepeatBehavior(.enabled)` does.
 */
@Composable
private fun ReaderStepperKey(
    label: String,
    enabled: Boolean,
    onStep: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val palette = jerreaderPalette
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val currentOnStep by rememberUpdatedState(onStep)
    val currentEnabled by rememberUpdatedState(enabled)
    // Set once a hold has begun to repeat, so letting go adds no extra step.
    val repeating = remember { mutableStateOf(false) }
    LaunchedEffect(source) {
        var hold: Job? = null
        source.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    repeating.value = false
                    hold?.cancel()
                    hold = launch {
                        delay(STEPPER_REPEAT_DELAY_MILLIS)
                        while (currentEnabled) {
                            repeating.value = true
                            currentOnStep()
                            delay(STEPPER_REPEAT_INTERVAL_MILLIS)
                        }
                    }
                }
                is PressInteraction.Release -> hold?.cancel()
                is PressInteraction.Cancel -> {
                    hold?.cancel()
                    repeating.value = false
                }
            }
        }
    }
    val fill by animateFloatAsState(
        targetValue = if (pressed) 0.55f else 1f,
        animationSpec = if (pressed) JerreaderMotion.press() else JerreaderMotion.release(),
        label = "stepperKeyFill"
    )
    Row(
        modifier
            .widthIn(min = 52.dp)
            .heightIn(min = 38.dp)
            .jerreaderPressScale(source, pressedScale = 0.94f)
            .drawBehind {
                drawRoundRect(palette.paper.copy(alpha = fill.coerceIn(0f, 1f)), cornerRadius = CornerRadius(size.height / 2))
            }
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button) {
                if (repeating.value) repeating.value = false else currentOnStep()
            }
            .semantics { contentDescription = label }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally)
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.86f else 0.28f)
        ) {
            content()
        }
    }
}

/**
 * iOS `ReaderStepGlyph`: a minus or a plus on the 24-unit icon grid (1.65
 * stroke, round caps, arms from 3 to 21). The icon set has no minus, so both
 * are drawn here to match each other.
 */
@Composable
private fun ReaderStepGlyph(plus: Boolean, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(11.dp)) {
        val stroke = max(size.width * 1.65f / 24f, 1.dp.toPx())
        val arm = size.width * 18f / 48f
        drawLine(color, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), stroke, StrokeCap.Round)
        if (plus) {
            drawLine(color, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), stroke, StrokeCap.Round)
        }
    }
}

private fun roundedTenth(value: Float): Double = (value * 10f).roundToInt() / 10.0

private fun oneDecimal(value: Double): String {
    val tenths = (value * 10).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}

@Composable
private fun SpacingSection(
    appearance: ReaderAppearance,
    onChange: (ReaderAppearance) -> Unit,
    appSettings: com.jerreader.android.settings.AndroidAppSettingsStore
) {
    val verticalMargins = appSettings.preferences.collectAsState().value.verticalMargins
    ReaderSheetCard("版面") {
        ReaderSheetSliderRow(
            title = "行间距",
            valueText = oneDecimal(appearance.lineHeight),
            value = appearance.lineHeight.toFloat(),
            range = 1f..2.2f,
            steps = 11,
            onValueChange = { onChange(appearance.copy(lineHeight = roundedTenth(it))) }
        )
        ReaderSheetDivider()
        ReaderSheetSliderRow(
            title = "段间距",
            valueText = if (appearance.paragraphSpacing == 0.0) "紧凑" else "${oneDecimal(appearance.paragraphSpacing)} em",
            value = appearance.paragraphSpacing.toFloat(),
            range = 0f..2f,
            steps = 19,
            onValueChange = { onChange(appearance.copy(paragraphSpacing = roundedTenth(it))) }
        )
        ReaderSheetDivider()
        ReaderSheetSliderRow(
            title = "左右边距",
            valueText = when {
                appearance.pageMargins < 0.8 -> "窄"
                appearance.pageMargins < 1.3 -> "标准"
                else -> "宽"
            },
            value = appearance.pageMargins.toFloat(),
            range = 0.5f..2f,
            steps = 14,
            onValueChange = { onChange(appearance.copy(pageMargins = roundedTenth(it))) }
        )
        ReaderSheetDivider()
        // 2.0.1. Shared by every book, like 界面布局, so it is the app's own
        // setting rather than part of this book's appearance.
        ReaderSheetSliderRow(
            title = "上下边距",
            valueText = com.jerreader.android.settings.verticalMarginsLabel(verticalMargins),
            value = verticalMargins,
            range = com.jerreader.android.settings.VERTICAL_MARGINS_RANGE,
            steps = 39,
            onValueChange = appSettings::updateVerticalMargins,
            detail = "正文离屏幕上下两端（标准布局下是上下栏）的留白，对所有书生效；PDF 按页面本身显示，不受影响。"
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
    val haptics = LocalHapticFeedback.current
    val customBackground = appearance.customBackgroundHex.isNotEmpty()
    val customSelection = appearance.customSelectionColorHex.isNotEmpty()
    // A colour row that is fading out still shows the colour it had.
    val backgroundHex = rememberLastNonEmpty(appearance.customBackgroundHex)
    val selectionHex = rememberLastNonEmpty(appearance.customSelectionColorHex)
    ReaderSheetCard("背景") {
        Row(Modifier.fillMaxWidth().padding(horizontal = JerreaderSpacing.xs, vertical = JerreaderSpacing.sm)) {
            themeSwatches.forEach { swatch ->
                val selected = !customBackground && appearance.theme == swatch.theme
                val source = remember { MutableInteractionSource() }
                ReaderThemeSwatch(
                    swatch = swatch,
                    selected = selected,
                    modifier = Modifier
                        .weight(1f)
                        .jerreaderPressScale(source, pressedScale = 0.94f)
                        .semantics(mergeDescendants = true) {
                            contentDescription = "${swatch.title}背景"
                            this.selected = selected
                        }
                        .clickable(interactionSource = source, indication = null, role = Role.Button) {
                            // iOS ticks when the theme itself changes.
                            if (appearance.theme != swatch.theme) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                            onChange(appearance.copy(customBackgroundHex = "", theme = swatch.theme))
                        }
                )
            }
        }
        ReaderSheetDivider()
        ReaderSheetToggleRow(
            "自定义背景颜色",
            customBackground,
            { onChange(appearance.copy(customBackgroundHex = if (it) "#EEF3F8" else "")) }
        )
        ReaderSheetRows(visible = customBackground) {
            ReaderSheetDivider()
            ColorPickerRow(
                title = "选择背景颜色",
                hex = backgroundHex,
                padding = ReaderSheetRowPadding,
                onHexChange = { onChange(appearance.copy(customBackgroundHex = it)) }
            )
        }
        ReaderSheetDivider()
        ReaderSheetToggleRow(
            "自定义选区颜色",
            customSelection,
            { onChange(appearance.copy(customSelectionColorHex = if (it) "#317DC2" else "")) }
        )
        ReaderSheetRows(visible = customSelection) {
            ReaderSheetDivider()
            ColorPickerRow(
                title = "选择选区颜色",
                hex = selectionHex,
                padding = ReaderSheetRowPadding,
                onHexChange = { onChange(appearance.copy(customSelectionColorHex = it)) }
            )
        }
    }
}

/** [value] while it is set, else the last value that was. */
@Composable
private fun rememberLastNonEmpty(value: String): String {
    val last = remember { mutableStateOf(value) }
    SideEffect { if (value.isNotEmpty()) last.value = value }
    return value.ifEmpty { last.value }
}

/**
 * iOS `ReaderThemeSwatch`: a 44dp disc with a 1dp hairline; the chosen one
 * gets a 2dp accent ring 2dp outside it and a check, white on the dark
 * swatch. The ring grows from 90% and the check from 60% on the state spring.
 */
@Composable
private fun ReaderThemeSwatch(swatch: ThemeSwatch, selected: Boolean, modifier: Modifier = Modifier) {
    val palette = jerreaderPalette
    val shown by animateFloatAsState(if (selected) 1f else 0f, JerreaderMotion.stateChange(), label = "swatch")
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(JerreaderSpacing.xs)
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val scale = 0.9f + 0.1f * shown
                        scaleX = scale
                        scaleY = scale
                        alpha = shown.coerceIn(0f, 1f)
                    }
                    .border(2.dp, palette.accent, CircleShape)
            )
            Box(
                Modifier
                    .size(44.dp)
                    .background(swatch.color, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), CircleShape)
            )
            JerreaderGlyph(
                icon = JerreaderIcon.CHECK,
                tint = if (swatch.theme == ReaderThemeOption.DARK) Color.White else palette.accent,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer {
                        val scale = 0.6f + 0.4f * shown
                        scaleX = scale
                        scaleY = scale
                        alpha = shown.coerceIn(0f, 1f)
                    }
            )
        }
        Text(
            swatch.title,
            modifier = Modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) palette.accent else MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

// MARK: 翻译

/** iOS `translationSection`: one row into 翻译与 AI, with its mark, a summary and a chevron. */
@Composable
private fun TranslationSection(onOpen: () -> Unit) {
    ReaderSheetCard("翻译", footer = "翻译服务与密钥对所有书籍通用。") {
        Row(
            Modifier
                .clickable(onClick = onOpen)
                .readerSheetRow(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(JerreaderSpacing.sm)
        ) {
            JerreaderIconTile(JerreaderIcon.SPEECH_BUBBLE, Modifier.clearAndSetSemantics {}, size = 32.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("翻译与 AI", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "服务商、三语互译与提示词",
                    style = MaterialTheme.typography.labelMedium,
                    color = jerreaderPalette.secondaryText
                )
            }
            JerreaderGlyph(JerreaderIcon.CHEVRON_FORWARD, MaterialTheme.colorScheme.plainTertiary, Modifier.size(14.dp))
        }
    }
}

// MARK: Colours

/** The padding of a card row, for rows that place it inside their own touch area. */
private val ReaderSheetRowPadding = PaddingValues(horizontal = 16.dp, vertical = 11.dp)

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
    modifier: Modifier = Modifier,
    showDivider: Boolean = true,
    onHexChange: (String) -> Unit
) {
    Column(modifier.fillMaxWidth()) {
        ColorPickerRow(title, hex, PaddingValues(horizontal = 20.dp, vertical = 6.dp), onHexChange)
        if (showDivider) JerreaderRowSeparator(start = 20.dp)
    }
}

/** A row with [title] and a colour well, at least 44dp tall, that opens the picker. */
@Composable
private fun ColorPickerRow(
    title: String,
    hex: String,
    padding: PaddingValues,
    onHexChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var picking by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { picking = true }
            .heightIn(min = 44.dp)
            .padding(padding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        ColorWell(colorFromHex(hex) ?: Color.White)
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
