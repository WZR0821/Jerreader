package com.jerreader.unified.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jerreader.unified.library.ReaderAnnotationColor
import com.jerreader.unified.library.ReaderAppearance
import com.jerreader.unified.library.ReaderColorMath
import com.jerreader.unified.library.ReaderColorPreset
import com.jerreader.unified.library.ReaderColorPresetStore
import com.jerreader.unified.library.ReaderFontOption
import com.jerreader.unified.library.ReaderHsv
import com.jerreader.unified.library.ReaderPageBackground
import com.jerreader.unified.library.ReaderTextOrientation
import com.jerreader.unified.library.ReaderThemeOption

data class ReaderChapter(
    val id: String,
    val title: String,
    val depth: Int
)

data class ReaderBookmarkItem(
    val id: String,
    val title: String,
    val detail: String,
    val progress: Double
)

data class ReaderAnnotationItem(
    val id: String,
    val selectedText: String,
    val noteText: String,
    val color: String,
    val progress: Double
)

data class ReaderSearchResultItem(
    val id: String,
    val excerpt: String,
    val chapterTitle: String?
)

/** Slim reader header: only what has to stay visible while reading. */
@Composable
fun ReaderTopBar(
    title: String,
    isBookmarked: Boolean,
    onBack: () -> Unit,
    onToggleBookmark: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReaderBarButton("返回", onClick = onBack)
                Text(
                    text = title,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                ReaderBarButton(
                    text = if (isBookmarked) "★ 书签" else "☆ 书签",
                    onClick = onToggleBookmark,
                    highlighted = isBookmarked
                )
            }
            JerreaderDivider()
        }
    }
}

/**
 * Reading controls live at the bottom, like the iOS reader: progress first,
 * then navigation. Nothing here overlaps the first line of the page.
 */
@Composable
fun ReaderBottomBar(
    progress: Double,
    showProgress: Boolean,
    onProgressChange: (Double) -> Unit,
    canGoToPreviousChapter: Boolean,
    canGoToNextChapter: Boolean,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onTableOfContents: () -> Unit,
    onAppearance: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column {
            JerreaderDivider()
            if (showProgress) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = progress.toFloat().coerceIn(0f, 1f),
                        onValueChange = { onProgressChange(it.toDouble()) },
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .padding(horizontal = 2.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReaderBarButton("目录", onClick = onTableOfContents)
                ReaderBarButton("上一章", enabled = canGoToPreviousChapter, onClick = onPreviousChapter)
                ReaderBarButton("上一页", onClick = onPreviousPage)
                ReaderBarButton("下一页", onClick = onNextPage)
                ReaderBarButton("下一章", enabled = canGoToNextChapter, onClick = onNextChapter)
                ReaderBarButton("Aa", onClick = onAppearance)
            }
        }
    }
}

@Composable
private fun ReaderBarButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    highlighted: Boolean = false
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 8.dp,
            vertical = 4.dp
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
            color = if (highlighted) {
                MaterialTheme.colorScheme.primary
            } else {
                LocalContentColorOrDefault()
            }
        )
    }
}

@Composable
private fun LocalContentColorOrDefault() = androidx.compose.material3.LocalContentColor.current

@Composable
fun ReaderNavigationPanel(
    chapters: List<ReaderChapter>,
    bookmarks: List<ReaderBookmarkItem>,
    annotations: List<ReaderAnnotationItem>,
    searchQuery: String,
    searchResults: List<ReaderSearchResultItem>,
    isSearching: Boolean,
    searchMessage: String?,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSelectSearchResult: (String) -> Unit,
    onSelectChapter: (String) -> Unit,
    onSelectBookmark: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onSelectAnnotation: (String) -> Unit,
    onEditAnnotation: (String) -> Unit,
    onDeleteAnnotation: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(vertical = 10.dp)) {
        Text(
            "目录与阅读导航",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        LazyColumn(modifier = Modifier.heightIn(max = 620.dp)) {
            item {
                Text(
                    "全文搜索",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("搜索书内文字") },
                        singleLine = true
                    )
                    Button(
                        enabled = searchQuery.isNotBlank() && !isSearching,
                        onClick = onSearch
                    ) { Text(if (isSearching) "搜索中…" else "搜索") }
                }
                searchMessage?.let {
                    Text(
                        it,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (searchResults.isNotEmpty()) {
                items(searchResults, key = ReaderSearchResultItem::id) { result ->
                    TextButton(
                        onClick = { onSelectSearchResult(result.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            result.chapterTitle?.takeIf(String::isNotBlank)?.let {
                                Text(it, fontWeight = FontWeight.SemiBold)
                            }
                            Text(result.excerpt, maxLines = 3)
                        }
                    }
                }
            }
            item {
                Text(
                    "目录",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (chapters.isEmpty()) {
                item { Text("没有可用目录。", modifier = Modifier.padding(horizontal = 20.dp)) }
            } else {
                items(chapters, key = ReaderChapter::id) { chapter ->
                    TextButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (chapter.depth * 18).dp),
                        onClick = { onSelectChapter(chapter.id) }
                    ) { Text(chapter.title, modifier = Modifier.fillMaxWidth(), maxLines = 2) }
                }
            }
            item {
                Text(
                    "书签",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (bookmarks.isEmpty()) {
                item { Text("还没有书签。", modifier = Modifier.padding(horizontal = 20.dp)) }
            } else {
                items(bookmarks, key = ReaderBookmarkItem::id) { bookmark ->
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        TextButton(
                            onClick = { onSelectBookmark(bookmark.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(bookmark.title, fontWeight = FontWeight.SemiBold)
                                Text(bookmark.detail, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton(onClick = { onDeleteBookmark(bookmark.id) }) { Text("删除") }
                    }
                }
            }
            item {
                Text(
                    "划线与批注",
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (annotations.isEmpty()) {
                item { Text("还没有划线或批注。", modifier = Modifier.padding(horizontal = 20.dp)) }
            } else {
                items(annotations, key = ReaderAnnotationItem::id) { annotation ->
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        TextButton(
                            onClick = { onSelectAnnotation(annotation.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(annotation.selectedText, maxLines = 3)
                                if (annotation.noteText.isNotBlank()) {
                                    Text(annotation.noteText, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        TextButton(onClick = { onEditAnnotation(annotation.id) }) { Text("编辑") }
                        TextButton(onClick = { onDeleteAnnotation(annotation.id) }) { Text("删除") }
                    }
                }
            }
        }
    }
}

@Composable
fun ReaderAnnotationEditor(
    selectedText: String,
    initialNote: String = "",
    initialColor: String = "yellow",
    onSave: (String, String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var note by remember { mutableStateOf(initialNote) }
    var color by remember { mutableStateOf(initialColor) }
    Column(
        modifier = modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("划线与批注", style = MaterialTheme.typography.headlineSmall)
        Text(selectedText, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5)
        // Five labels do not fit one row on a phone; without scrolling the last
        // chip wrapped its text vertically.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ReaderAnnotationColor.entries.forEach { option ->
                FilterChip(
                    selected = color == option.id,
                    onClick = { color = option.id },
                    label = { Text(option.title, maxLines = 1, softWrap = false) }
                )
            }
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("笔记（可选）") },
            minLines = 3
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(note, color) }) { Text("保存") }
            TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}

@Composable
fun ReaderTableOfContents(
    chapters: List<ReaderChapter>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(vertical = 10.dp)) {
        Text(
            "目录",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        if (chapters.isEmpty()) {
            Text("该 EPUB 没有可用目录。", modifier = Modifier.padding(20.dp))
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                items(chapters, key = ReaderChapter::id) { chapter ->
                    TextButton(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (chapter.depth * 18).dp),
                        onClick = { onSelect(chapter.id) }
                    ) {
                        Text(
                            text = chapter.title,
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Reading settings. The panel scrolls: text direction and the PDF paper switch
 * used to sit below the fold of a non-scrolling dialog, which made 横排/竖排
 * unreachable on a phone.
 */
@Composable
fun ReaderAppearancePanel(
    appearance: ReaderAppearance,
    onChange: (ReaderAppearance) -> Unit,
    isPdf: Boolean = false,
    /** Set when the panel sits inside a page that already scrolls. */
    embedded: Boolean = false,
    /** Saved background + selection colour sets; empty hides the row. */
    colorPresets: List<ReaderColorPreset> = emptyList(),
    onSaveColorPreset: ((String) -> Unit)? = null,
    onDeleteColorPreset: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = if (embedded) {
            modifier.fillMaxWidth()
        } else {
            modifier.fillMaxWidth().heightIn(max = 560.dp)
        }
    ) {
        if (!embedded) {
            Text(
                "阅读设置",
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 10.dp),
                style = MaterialTheme.typography.titleLarge
            )
        }
        Column(
            modifier = Modifier
                .then(
                    if (embedded) Modifier else Modifier.verticalScroll(rememberScrollState())
                )
                .padding(horizontal = if (embedded) 0.dp else 14.dp)
                .padding(bottom = if (embedded) 0.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isPdf) {
                Text(
                    "PDF 保留原文件版式，字号、行距和字体由 PDF 页面决定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            } else JerreaderSection("版式") {
                JerreaderRow("字号") {
                    JerreaderStepper(
                        value = "${(appearance.fontScale * 100).toInt()}%",
                        canDecrease = appearance.fontScale > 0.8,
                        canIncrease = appearance.fontScale < 2.0,
                        onDecrease = {
                            onChange(
                                appearance.copy(
                                    fontScale = (appearance.fontScale - 0.1).coerceAtLeast(0.8)
                                )
                            )
                        },
                        onIncrease = {
                            onChange(
                                appearance.copy(
                                    fontScale = (appearance.fontScale + 0.1).coerceAtMost(2.0)
                                )
                            )
                        }
                    )
                }
                NumericAppearanceRow("行距", appearance.lineHeight, 1.0, 2.0) {
                    onChange(appearance.copy(lineHeight = it))
                }
                NumericAppearanceRow("段间距", appearance.paragraphSpacing, 0.0, 2.0) {
                    onChange(appearance.copy(paragraphSpacing = it))
                }
                NumericAppearanceRow("页边距", appearance.pageMargins, 0.5, 2.0) {
                    onChange(appearance.copy(pageMargins = it))
                }
                JerreaderRow("字体") {
                    JerreaderSegmented(
                        options = listOf(
                            ReaderFontOption.PUBLICATION to "原书",
                            ReaderFontOption.SERIF to "衬线",
                            ReaderFontOption.SANS_SERIF to "黑体"
                        ),
                        selected = appearance.font,
                        onSelect = { onChange(appearance.copy(font = it)) }
                    )
                }
            }

            JerreaderSection(
                title = "翻页与方向",
                footnote = if (isPdf) {
                    "PDF 的翻页方向由原文件决定。"
                } else {
                    "「原书」保留出版物声明的横竖排与翻页方向；「横排」强制章节间与章节内都从左向右。"
                }
            ) {
                if (!isPdf) {
                    JerreaderRow("阅读方式") {
                        JerreaderSegmented(
                            options = listOf(false to "分页", true to "滚动"),
                            selected = appearance.scroll,
                            onSelect = { onChange(appearance.copy(scroll = it)) }
                        )
                    }
                    JerreaderRow("文字方向") {
                        // 竖排 is deliberately not offered. A book that is set
                        // vertically already reads that way under 原书, and
                        // forcing vertical onto a book that was not typeset for
                        // it is the one combination nobody wants. The enum keeps
                        // the case so a setting saved by an older build still
                        // loads; `AndroidAppSettingsStore` migrates it to 原书.
                        JerreaderSegmented(
                            options = listOf(
                                ReaderTextOrientation.PUBLICATION to "原书",
                                ReaderTextOrientation.HORIZONTAL to "横排"
                            ),
                            selected = appearance.orientation,
                            onSelect = { onChange(appearance.copy(orientation = it)) }
                        )
                    }
                }
                if (isPdf) {
                    JerreaderRow(
                        label = "论文双栏模式",
                        detail = "点按识别只使用所点的左栏或右栏。"
                    ) {
                        Switch(
                            checked = appearance.pdfPaperModeEnabled,
                            onCheckedChange = {
                                onChange(appearance.copy(pdfPaperModeEnabled = it))
                            }
                        )
                    }
                }
            }

            JerreaderSection(
                title = "主题与配色",
                footnote = "内置主题各自带一套配好的选区色；改成自定义颜色后，可以把这一对背景与选区" +
                    "存成自己的套系，之后一键套用。"
            ) {
                JerreaderRow("阅读主题") {
                    JerreaderSegmented(
                        options = listOf(
                            ReaderThemeOption.LIGHT to "浅色",
                            ReaderThemeOption.SEPIA to "护眼",
                            ReaderThemeOption.COOL_GRAY to "冷灰",
                            ReaderThemeOption.DARK to "深色"
                        ),
                        selected = appearance.theme,
                        onSelect = {
                            // Picking a built-in theme means picking its own
                            // background, so a custom one left in place would
                            // silently win and the tap would appear to do
                            // nothing.
                            onChange(
                                appearance.copy(
                                    theme = it,
                                    customBackgroundHex = "",
                                    customSelectionColorHex = ""
                                )
                            )
                        }
                    )
                }
                ReaderColorField(
                    label = "自定义背景色",
                    hex = appearance.customBackgroundHex,
                    fallbackHex = ReaderColorPresetStore.backgroundHexFor(appearance),
                    onChange = { onChange(appearance.copy(customBackgroundHex = it)) }
                )
                ReaderColorField(
                    label = "自定义选区色",
                    hex = appearance.customSelectionColorHex,
                    fallbackHex = ReaderColorPresetStore.selectionHexFor(appearance),
                    onChange = { onChange(appearance.copy(customSelectionColorHex = it)) }
                )
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
}

/**
 * Pick a colour by eye, the way iOS's system colour wheel lets you.
 *
 * This row used to be a `#RRGGBB` text field. Anyone who already knew the hex
 * of the paper they wanted was well served; everyone else had to guess at
 * six characters and watch the page change under them — 「安卓自定义背景色那块
 * 不能做到跟 iOS 一样吗」. Compose Multiplatform ships no colour picker, so it
 * is three sliders over their own gradients, with the hex kept alongside for
 * anyone who does know the number and for copying a colour between the two
 * apps.
 *
 * [fallbackHex] is the colour the page is showing while [hex] is empty, so
 * switching 自定义 on starts from what the reader is looking at rather than
 * from an arbitrary colour.
 */
@Composable
private fun ReaderColorField(
    label: String,
    hex: String,
    fallbackHex: String,
    onChange: (String) -> Unit
) {
    val isCustom = ReaderColorPresetStore.normalizedHex(hex).isNotEmpty()
    val effectiveHex = if (isCustom) ReaderColorPresetStore.normalizedHex(hex) else fallbackHex
    val hsv = ReaderColorMath.hsvOf(effectiveHex) ?: ReaderHsv(0.0, 0.0, 1.0)

    // The typed hex is held separately: writing every keystroke straight back
    // through `onChange` meant a half-typed `#F5F` was not a colour, so the
    // page reverted to the theme mid-word and the field emptied itself.
    var typedHex by remember(effectiveHex) { mutableStateOf(effectiveHex) }

    JerreaderRow(label) {
        Switch(
            checked = isCustom,
            onCheckedChange = { enabled -> onChange(if (enabled) fallbackHex else "") }
        )
    }

    if (!isCustom) return

    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Color(colorArgbOf(effectiveHex)))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        )
        OutlinedTextField(
            value = typedHex,
            onValueChange = { value ->
                typedHex = normalizedHexInput(value)
                val complete = ReaderColorPresetStore.normalizedHex(typedHex)
                if (complete.isNotEmpty()) onChange(complete)
            },
            modifier = Modifier.weight(1f),
            label = { Text("#RRGGBB", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = true
        )
    }

    ColorSlider(
        label = "色相",
        value = (hsv.hue / 360.0).toFloat(),
        // The full wheel, so the strip reads as the choice it offers.
        track = Brush.horizontalGradient(
            (0..6).map { Color(colorArgbOf(ReaderColorMath.hexOf(it * 60.0, 1.0, 1.0))) }
        ),
        onValueChange = {
            onChange(ReaderColorMath.hexOf(it * 360.0, hsv.saturation, hsv.value))
        }
    )
    ColorSlider(
        label = "饱和度",
        value = hsv.saturation.toFloat(),
        // Both remaining strips are drawn at the reader's current hue: a fixed
        // grey-to-red saturation bar shows a colour they are not choosing.
        track = Brush.horizontalGradient(
            listOf(
                Color(colorArgbOf(ReaderColorMath.hexOf(hsv.hue, 0.0, hsv.value))),
                Color(colorArgbOf(ReaderColorMath.hexOf(hsv.hue, 1.0, hsv.value)))
            )
        ),
        onValueChange = {
            onChange(ReaderColorMath.hexOf(hsv.hue, it.toDouble(), hsv.value))
        }
    )
    ColorSlider(
        label = "明度",
        value = hsv.value.toFloat(),
        track = Brush.horizontalGradient(
            listOf(
                Color(colorArgbOf(ReaderColorMath.hexOf(hsv.hue, hsv.saturation, 0.0))),
                Color(colorArgbOf(ReaderColorMath.hexOf(hsv.hue, hsv.saturation, 1.0)))
            )
        ),
        onValueChange = {
            onChange(ReaderColorMath.hexOf(hsv.hue, hsv.saturation, it.toDouble()))
        }
    )
}

/** One slider drawn over the range of colours it actually moves through. */
@Composable
private fun ColorSlider(
    label: String,
    value: Float,
    track: Brush,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(44.dp)
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(track)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
            Slider(
                value = value.coerceIn(0f, 1f),
                onValueChange = onValueChange,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent
                )
            )
        }
    }
}

private fun colorArgbOf(hex: String): Long =
    ReaderPageBackground.parseRgbHex(hex) ?: 0xFF000000L

/**
 * The saved colour sets, plus the control that creates one.
 */
@Composable
fun ReaderColorPresetEditor(
    presets: List<ReaderColorPreset>,
    appearance: ReaderAppearance,
    onApply: (ReaderColorPreset) -> Unit,
    onSave: ((String) -> Unit)?,
    onDelete: ((String) -> Unit)?
) {
    var pendingName by remember { mutableStateOf("") }
    var isNaming by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ReaderColorPreset?>(null) }

    if (presets.isNotEmpty()) {
        Text(
            "我的配色套系",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            presets.forEach { preset ->
                ReaderColorPresetSwatch(
                    preset = preset,
                    selected = ReaderColorPresetStore.matches(preset, appearance),
                    onClick = { onApply(preset) },
                    onLongClick = onDelete?.let { { pendingDelete = preset } }
                )
            }
        }
    }

    if (onSave != null) {
        if (isNaming) {
            // Empty means 「用这个建议的名字」. Refusing to save an unnamed set
            // is what made 保存 look like it did nothing at all.
            val suggested = ReaderColorPresetStore.defaultName(presets)
            OutlinedTextField(
                value = pendingName,
                onValueChange = {
                    // Only the limits that must hold *while* typing. Running
                    // the full `sanitizedName` on every keystroke also trims,
                    // so the space in 「夜 读」 vanished the moment it was
                    // typed and the second word could never be started.
                    // Trimming happens once, at save.
                    pendingName = it
                        .filterNot { char -> char.isISOControl() }
                        .take(ReaderColorPresetStore.MAXIMUM_NAME_LENGTH)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("套系名称", style = MaterialTheme.typography.bodySmall) },
                placeholder = { Text(suggested, style = MaterialTheme.typography.bodyMedium) },
                textStyle = MaterialTheme.typography.bodyMedium,
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { isNaming = false; pendingName = "" }) { Text("取消") }
                Button(
                    onClick = {
                        onSave(pendingName)
                        isNaming = false
                        pendingName = ""
                    }
                ) { Text("保存") }
            }
        } else {
            TextButton(onClick = { isNaming = true }) {
                Text("保存当前配色为套系")
            }
        }
    }

    pendingDelete?.let { preset ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "删除「${preset.name}」？",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            TextButton(onClick = {
                onDelete?.invoke(preset.id)
                pendingDelete = null
            }) { Text("删除") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderColorPresetSwatch(
    preset: ReaderColorPreset,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
) {
    Column(
        // Tap applies, long press asks to delete — the same pair of gestures
        // iOS offers, and the pair the section's help text promises. The whole
        // swatch carries both: an earlier version put a plain `clickable` on
        // the name alone, so tapping a set's label deleted it while tapping
        // its circle applied it.
        modifier = Modifier
            .width(64.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(parseHexColor(preset.backgroundHex) ?: MaterialTheme.colorScheme.surface)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    shape = CircleShape
                )
                ,
            contentAlignment = Alignment.Center
        ) {
            // The inner dot is the selection colour: a set is the pair, so the
            // swatch has to show both or two sets on the same paper look alike.
            Box(
                modifier = Modifier
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(
                        parseHexColor(preset.selectionHex)
                            ?: MaterialTheme.colorScheme.primary
                    )
            )
        }
        Text(
            text = preset.name,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun parseHexColor(value: String): Color? {
    val digits = ReaderColorPresetStore.normalizedHex(value).removePrefix("#")
    if (digits.isEmpty()) return null
    val raw = digits.toLongOrNull(16) ?: return null
    return Color(
        red = ((raw shr 16) and 0xFF) / 255f,
        green = ((raw shr 8) and 0xFF) / 255f,
        blue = (raw and 0xFF) / 255f
    )
}

private fun normalizedHexInput(value: String): String {
    val trimmed = value.trim().uppercase()
    if (trimmed.isEmpty()) return ""
    val digits = trimmed.removePrefix("#").take(6).filter { it in "0123456789ABCDEF" }
    return "#$digits"
}

@Composable
private fun NumericAppearanceRow(
    label: String,
    value: Double,
    minimum: Double,
    maximum: Double,
    onChange: (Double) -> Unit
) {
    JerreaderRow(label) {
        JerreaderStepper(
            value = value.toString().take(3),
            canDecrease = value > minimum,
            canIncrease = value < maximum,
            onDecrease = { onChange((value - 0.1).coerceAtLeast(minimum)) },
            onIncrease = { onChange((value + 0.1).coerceAtMost(maximum)) }
        )
    }
}
