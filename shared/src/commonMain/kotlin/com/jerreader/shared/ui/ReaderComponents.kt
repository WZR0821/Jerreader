package com.jerreader.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jerreader.shared.library.ReaderAppearance
import com.jerreader.shared.library.ReaderFontOption
import com.jerreader.shared.library.ReaderTextOrientation
import com.jerreader.shared.library.ReaderThemeOption

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

@Composable
fun ReaderToolbar(
    title: String,
    onBack: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    canGoToPreviousChapter: Boolean,
    canGoToNextChapter: Boolean,
    isBookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    progress: Double,
    showProgress: Boolean,
    onProgressChange: (Double) -> Unit,
    onTableOfContents: () -> Unit,
    onAppearance: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) { Text("返回") }
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = onToggleBookmark) { Text(if (isBookmarked) "★" else "☆") }
                TextButton(onClick = onTableOfContents) { Text("导航") }
                TextButton(onClick = onAppearance) { Text("Aa") }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    enabled = canGoToPreviousChapter,
                    onClick = onPreviousChapter
                ) { Text("上一章") }
                TextButton(onClick = onPreviousPage) { Text("上一页") }
                TextButton(onClick = onNextPage) { Text("下一页") }
                TextButton(
                    enabled = canGoToNextChapter,
                    onClick = onNextChapter
                ) { Text("下一章") }
            }
            if (showProgress) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Slider(
                        value = progress.toFloat().coerceIn(0f, 1f),
                        onValueChange = { onProgressChange(it.toDouble()) },
                        modifier = Modifier.weight(1f)
                    )
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

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
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("yellow" to "琥珀", "blue" to "海蓝", "mint" to "薄荷", "pink" to "珊瑚").forEach {
                FilterChip(
                    selected = color == it.first,
                    onClick = { color = it.first },
                    label = { Text(it.second) }
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

@Composable
fun ReaderAppearancePanel(
    appearance: ReaderAppearance,
    onChange: (ReaderAppearance) -> Unit,
    isPdf: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("阅读设置", style = MaterialTheme.typography.headlineSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("字号")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = appearance.fontScale > 0.8,
                    onClick = {
                        onChange(appearance.copy(fontScale = (appearance.fontScale - 0.1).coerceAtLeast(0.8)))
                    }
                ) { Text("−") }
                Text(
                    text = "${(appearance.fontScale * 100).toInt()}%",
                    modifier = Modifier.padding(horizontal = 14.dp)
                )
                Button(
                    enabled = appearance.fontScale < 2.0,
                    onClick = {
                        onChange(appearance.copy(fontScale = (appearance.fontScale + 0.1).coerceAtMost(2.0)))
                    }
                ) { Text("+") }
            }
        }
        Text("阅读方式")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (appearance.scroll) {
                TextButton(onClick = { onChange(appearance.copy(scroll = false)) }) { Text("分页") }
                Button(onClick = { onChange(appearance.copy(scroll = true)) }) { Text("滚动") }
            } else {
                Button(onClick = { onChange(appearance.copy(scroll = false)) }) { Text("分页") }
                TextButton(onClick = { onChange(appearance.copy(scroll = true)) }) { Text("滚动") }
            }
        }
        Text("字体")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                ReaderFontOption.PUBLICATION to "原书",
                ReaderFontOption.SERIF to "衬线",
                ReaderFontOption.SANS_SERIF to "无衬线",
                ReaderFontOption.OPEN_DYSLEXIC to "易读"
            ).forEach { (font, label) ->
                if (appearance.font == font) {
                    Button(onClick = { onChange(appearance.copy(font = font)) }) { Text(label) }
                } else {
                    TextButton(onClick = { onChange(appearance.copy(font = font)) }) { Text(label) }
                }
            }
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
        Text("主题")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ThemeButton("浅色", ReaderThemeOption.LIGHT, appearance, onChange)
            ThemeButton("护眼", ReaderThemeOption.SEPIA, appearance, onChange)
            ThemeButton("冷灰", ReaderThemeOption.COOL_GRAY, appearance, onChange)
            ThemeButton("深色", ReaderThemeOption.DARK, appearance, onChange)
        }
        OutlinedTextField(
            value = appearance.customBackgroundHex,
            onValueChange = { value ->
                onChange(appearance.copy(customBackgroundHex = normalizedHexInput(value)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("自定义背景色（例如 #EEF3F8，留空关闭）") },
            singleLine = true
        )
        OutlinedTextField(
            value = appearance.customSelectionColorHex,
            onValueChange = { value ->
                onChange(appearance.copy(customSelectionColorHex = normalizedHexInput(value)))
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("自定义选区色（例如 #317DC2，留空关闭）") },
            singleLine = true
        )
        if (isPdf) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("论文双栏模式")
                    Text(
                        "点按识别只使用所点的左栏或右栏，避免跨越中缝。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = appearance.pdfPaperModeEnabled,
                    onCheckedChange = { onChange(appearance.copy(pdfPaperModeEnabled = it)) }
                )
            }
        }
        Text("文字方向")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                ReaderTextOrientation.PUBLICATION to "原书",
                ReaderTextOrientation.HORIZONTAL to "横排",
                ReaderTextOrientation.VERTICAL to "竖排"
            ).forEach { (orientation, label) ->
                if (appearance.orientation == orientation) {
                    Button(onClick = { onChange(appearance.copy(orientation = orientation)) }) {
                        Text(label)
                    }
                } else {
                    TextButton(onClick = { onChange(appearance.copy(orientation = orientation)) }) {
                        Text(label)
                    }
                }
            }
        }
        Spacer(Modifier.padding(bottom = 4.dp))
    }
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = value > minimum,
                onClick = { onChange((value - 0.1).coerceAtLeast(minimum)) }
            ) { Text("−") }
            Text(value.toString().take(3), modifier = Modifier.padding(horizontal = 10.dp))
            Button(
                enabled = value < maximum,
                onClick = { onChange((value + 0.1).coerceAtMost(maximum)) }
            ) { Text("+") }
        }
    }
}

@Composable
private fun ThemeButton(
    label: String,
    theme: ReaderThemeOption,
    appearance: ReaderAppearance,
    onChange: (ReaderAppearance) -> Unit
) {
    if (appearance.theme == theme) {
        Button(onClick = { onChange(appearance.copy(theme = theme)) }) { Text(label) }
    } else {
        TextButton(onClick = { onChange(appearance.copy(theme = theme)) }) { Text(label) }
    }
}
