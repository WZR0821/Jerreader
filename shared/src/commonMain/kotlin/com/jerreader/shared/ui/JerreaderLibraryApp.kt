package com.jerreader.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.jerreader.shared.library.LibraryBook
import kotlinx.coroutines.delay

data class LibraryUiState(
    val books: List<LibraryBook> = emptyList(),
    val isLoading: Boolean = true,
    val isImporting: Boolean = false,
    val message: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JerreaderLibraryApp(
    state: LibraryUiState,
    onImport: () -> Unit,
    onOpenBook: (String) -> Unit,
    onDeleteBook: (String) -> Unit,
    /**
     * Offered on an empty shelf, which is what a reinstall looks like. Null
     * hides the entry point on hosts that have no backup centre.
     */
    onRestoreBackup: (() -> Unit)? = null,
    onDismissMessage: () -> Unit = {},
    onChangeCover: (String) -> Unit = {},
    onPickCover: (((String) -> Unit) -> Unit)? = null,
    coverDraftContent: (@Composable (String) -> Unit)? = null,
    onSaveCover: (String, String) -> Unit = { _, _ -> },
    onUpdateBook: (
        id: String,
        title: String,
        author: String?,
        language: String?,
        category: String,
        series: String,
        tags: List<String>
    ) -> Unit = { _, _, _, _, _, _, _ -> },
    onBatchUpdateBooks: (
        selectedIds: Set<String>,
        category: String,
        series: String,
        language: String,
        tags: List<String>
    ) -> Unit = { selectedIds, category, series, language, tags ->
        state.books.filter { it.id in selectedIds }.forEach { book ->
            onUpdateBook(
                book.id,
                book.title,
                book.author,
                language.ifBlank { book.language },
                category.ifBlank { book.category },
                series.ifBlank { book.series },
                (book.tags + tags).map(String::trim).filter(String::isNotBlank)
                    .distinctBy(String::lowercase)
            )
        }
    },
    modifier: Modifier = Modifier,
    coverContent: (@Composable (LibraryBook) -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {}
) {
    var pendingDeletion by remember { mutableStateOf<LibraryBook?>(null) }
    var editingBook by remember { mutableStateOf<LibraryBook?>(null) }
    var searchQuery by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var selectedCategory by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var selectedFormat by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var sortOrder by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(LibrarySortOrder.RECENT) }
    var isBatchEditing by remember { mutableStateOf(false) }
    var isLibraryMenuExpanded by remember { mutableStateOf(false) }
    val categories = remember(state.books) { state.books.map(LibraryBook::category)
        .filter(String::isNotBlank)
        .distinct()
        .sorted() }
    val displayedBooks = remember(state.books, searchQuery, selectedCategory, selectedFormat, sortOrder) { state.books
        .filter { book ->
            val matchesCategory = selectedCategory == null || book.category == selectedCategory
            val query = searchQuery.trim()
            val matchesQuery = query.isEmpty() || listOf(
                book.title,
                book.author.orEmpty(),
                book.category,
                book.series,
                book.tags.joinToString(" ")
            ).any { it.contains(query, ignoreCase = true) }
            val matchesFormat = selectedFormat == null || book.sourceFormat == selectedFormat
            matchesCategory && matchesFormat && matchesQuery
        }
        .let { books ->
            when (sortOrder) {
                LibrarySortOrder.RECENT -> books.sortedByDescending {
                    it.lastOpenedAtEpochMillis ?: it.importedAtEpochMillis
                }
                LibrarySortOrder.TITLE -> books.sortedBy { it.title.lowercase() }
                LibrarySortOrder.AUTHOR -> books.sortedBy { it.author.orEmpty().lowercase() }
            }
        } }

    val recentBook = remember(state.books) { state.books
        .filter { it.lastOpenedAtEpochMillis != null }
        .maxByOrNull { it.lastOpenedAtEpochMillis ?: 0L } }
    val startedBooks = remember(state.books) { state.books.filter { it.lastReadProgress > 0 } }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = bottomBar,
        topBar = {
            // Match the iOS library's large title and icon-only toolbar actions.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LibraryToolbarButton(
                        icon = JerreaderIcon.PLUS,
                        enabled = !state.isImporting,
                        onClick = onImport,
                        modifier = Modifier.semantics { contentDescription = "导入电子书" }
                    )
                    Box {
                        LibraryToolbarButton(
                            icon = JerreaderIcon.MORE_CIRCLE,
                            onClick = { isLibraryMenuExpanded = true },
                            modifier = Modifier.semantics { contentDescription = "书架更多操作" }
                        )
                        DropdownMenu(expanded = isLibraryMenuExpanded, onDismissRequest = { isLibraryMenuExpanded = false }) {
                            DropdownMenuItem(text = { Text("排序方式") }, onClick = {}, enabled = false)
                            LibrarySortOrder.entries.forEach { order ->
                                DropdownMenuItem(text = { Text(order.title) }, onClick = {
                                    sortOrder = order; isLibraryMenuExpanded = false
                                }, trailingIcon = {
                                    if (order == sortOrder) JerreaderGlyph(JerreaderIcon.CHECK, MaterialTheme.colorScheme.primary, Modifier.size(18.dp))
                                })
                            }
                            DropdownMenuItem(text = { Text("批量整理书籍") }, enabled = state.books.isNotEmpty(), onClick = {
                                isBatchEditing = true; isLibraryMenuExpanded = false
                            })
                            if (state.books.isNotEmpty()) {
                                DropdownMenuItem(text = { Text("全部书籍") }, onClick = { selectedCategory = null; isLibraryMenuExpanded = false })
                                categories.forEach { category ->
                                    DropdownMenuItem(text = { Text(category) }, onClick = { selectedCategory = category; isLibraryMenuExpanded = false })
                                }
                                DropdownMenuItem(text = { Text("全部格式") }, onClick = { selectedFormat = null; isLibraryMenuExpanded = false })
                                state.books.map { it.sourceFormat.lowercase() }.distinct().sorted().forEach { format ->
                                    DropdownMenuItem(text = { Text(format.uppercase()) }, onClick = { selectedFormat = format; isLibraryMenuExpanded = false })
                                }
                            }
                        }
                    }
                }
                Text(
                    "书架",
                    modifier = Modifier.padding(bottom = 8.dp),
                    style = MaterialTheme.typography.displaySmall
                )
                // iOS pins the search field directly under the large title.
                LibrarySearchField(searchQuery) { searchQuery = it }
                Spacer(Modifier.padding(bottom = 8.dp))
            }
        }
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.books.isEmpty() -> EmptyLibrary(
                    onImport = onImport,
                    onRestoreBackup = onRestoreBackup,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 145.dp),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 36.dp),
                    horizontalArrangement = Arrangement.spacedBy(26.dp),
                    verticalArrangement = Arrangement.spacedBy(26.dp)
                ) {
                    if (recentBook != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                LibrarySectionTitle(
                                    title = "继续阅读",
                                    detail = "上次打开的书",
                                    icon = JerreaderIcon.CLOCK
                                )
                                LibraryRecentlyViewedRow(
                                    title = recentBook.title,
                                    author = recentBook.author ?: "未知作者",
                                    format = recentBook.sourceFormat,
                                    lastOpenedLabel = null,
                                    progress = recentBook.lastReadProgress,
                                    onOpen = { onOpenBook(recentBook.id) },
                                    cover = { coverContent?.invoke(recentBook) }
                                )
                            }
                        }
                    }
                    if (categories.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                            LibrarySectionTitle("文件夹", "${categories.size} 个", JerreaderIcon.FOLDER)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                (listOf<String?>(null) + categories).forEach { folder ->
                                    val active = selectedCategory == folder
                                    Surface(onClick = { selectedCategory = folder }, shape = RoundedCornerShape(14.dp),
                                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                        border = if (active) null else BorderStroke(.75.dp, MaterialTheme.colorScheme.outline)) {
                                        Row(Modifier.widthIn(min = 160.dp).heightIn(min = 64.dp).padding(horizontal = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                                            val tint = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                                            JerreaderGlyph(if (folder == null) JerreaderIcon.GRID else JerreaderIcon.FOLDER, tint, Modifier.size(20.dp))
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(folder ?: "全部书籍", style = MaterialTheme.typography.titleSmall,
                                                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                                Text("${if (folder == null) state.books.size else state.books.count { it.category == folder }} 本",
                                                    style = MaterialTheme.typography.labelSmall, color = tint)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LibrarySectionTitle(
                            title = "所有书籍",
                            detail = "${displayedBooks.size} 本 · ${sortOrder.title}",
                            icon = JerreaderIcon.GRID
                        )
                    }
                    if (displayedBooks.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            JerreaderGlyph(JerreaderIcon.BOOKS, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(38.dp))
                            Text("没有匹配的书籍", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                            Text("请清除搜索文字或切换文件夹。", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(displayedBooks, key = LibraryBook::id) { book ->
                        LibraryBookCard(
                            book = book,
                            onOpen = { onOpenBook(book.id) },
                            onDelete = { pendingDeletion = book },
                            onEdit = { editingBook = book },
                            onChangeCover = { onChangeCover(book.id) },
                            coverContent = coverContent
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LibraryReadingOverview(
                            totalMinutes = (state.books.sumOf { it.totalReadingSeconds } / 60).toInt(),
                            startedCount = startedBooks.size,
                            averageProgress = if (startedBooks.isEmpty()) {
                                0.0
                            } else {
                                startedBooks.sumOf { it.lastReadProgress } / startedBooks.size
                            }
                        )
                    }
                }
            }

            state.message?.let { message ->
                LaunchedEffect(message) {
                    delay(MESSAGE_DISPLAY_MILLIS)
                    onDismissMessage()
                }
                Card(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.inverseSurface
                    )
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.inverseOnSurface
                    )
                }
            }
        }
    }

        pendingDeletion?.let { book ->
            AlertDialog(
                onDismissRequest = { pendingDeletion = null },
                title = { Text("删除《${book.title}》？") },
                text = { Text("将删除应用内的出版物副本和阅读位置，不会修改原始导入文件。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDeletion = null
                            onDeleteBook(book.id)
                        }
                    ) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeletion = null }) { Text("取消") }
                }
            )
        }

    editingBook?.let { book ->
        BookEditDialog(
            book = book,
            coverContent = coverContent,
            onPickCover = onPickCover,
            coverDraftContent = coverDraftContent,
            onSaveCover = { uri -> onSaveCover(book.id, uri) },
            onDismiss = { editingBook = null },
            onSave = { title, author, language, category, series, tags ->
                onUpdateBook(book.id, title, author, language, category, series, tags)
                editingBook = null
            }
        )
    }
    if (isBatchEditing) {
        BatchBookEditDialog(
            books = state.books,
            onDismiss = { isBatchEditing = false },
            onApply = { selectedIds, category, series, language, tags ->
                onBatchUpdateBooks(selectedIds, category, series, language, tags)
                isBatchEditing = false
            }
        )
    }
}

@Composable
private fun LibrarySearchField(value: String, onValueChange: (String) -> Unit) {
    // iOS uses a filled, rounded search field rather than an outlined one.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            JerreaderGlyph(
                icon = JerreaderIcon.SEARCH,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).padding(vertical = 7.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            "搜索书名、作者、系列、文件夹或标签",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    inner()
                }
            )
            if (value.isNotEmpty()) Box(
                Modifier.size(28.dp).clickable { onValueChange("") }.semantics { contentDescription = "清除搜索" },
                contentAlignment = Alignment.Center
            ) { JerreaderGlyph(JerreaderIcon.CLOSE, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(16.dp)) }
        }
    }
}

private const val MESSAGE_DISPLAY_MILLIS = 3_000L

@Composable
private fun EmptyLibrary(
    onImport: () -> Unit,
    onRestoreBackup: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier.widthIn(max = 396.dp).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier.size(68.dp).clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                JerreaderGlyph(JerreaderIcon.BOOKS, MaterialTheme.colorScheme.primary, Modifier.size(30.dp))
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text("把下一本书放进来", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(
                    "导入 EPUB、PDF、DOCX 或 TXT。书籍只保存在这台设备上。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        EmptyLibraryAction(
            text = "导入书籍",
            icon = JerreaderIcon.DOWNLOAD,
            primary = true,
            onClick = onImport
        )
        if (onRestoreBackup != null) {
            EmptyLibraryAction(text = "从备份恢复", icon = JerreaderIcon.RESTORE, primary = false, onClick = onRestoreBackup)
            Text(
                "装过 Jerreader 的话，选择以前的备份文件夹即可恢复书架、进度和备份计划。",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Row(
            modifier = Modifier.padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            JerreaderGlyph(
                icon = JerreaderIcon.LOCK,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp)
            )
            Text(
                "本机保存，不会上传",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(2f))
    }
}

@Composable
private fun EmptyLibraryAction(text: String, icon: JerreaderIcon, primary: Boolean, onClick: () -> Unit) {
    val foreground = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    Box(Modifier.heightIn(min = 44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier.clip(RoundedCornerShape(7.dp))
                .background(if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 20.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            JerreaderGlyph(icon, foreground, Modifier.size(if (primary) 18.dp else 16.dp))
            Text(text, style = if (primary) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium, color = foreground)
        }
    }
}

@Composable
private fun LibraryBookCard(
    book: LibraryBook,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onChangeCover: () -> Unit,
    coverContent: (@Composable (LibraryBook) -> Unit)?
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).shadow(7.dp, RoundedCornerShape(9.dp))
            .clip(RoundedCornerShape(9.dp)).clickable(onClick = onOpen)
            .semantics { contentDescription = "${book.title}，作者 ${book.author ?: "未知作者"}" }) {
            if (coverContent != null) coverContent(book) else JerreaderBookPlaceholder(book)
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.author ?: "未知作者", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(book.sourceFormat.uppercase(), Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (book.lastReadProgress > 0) {
                    Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.primaryContainer)) {
                        Box(Modifier.fillMaxWidth(book.lastReadProgress.toFloat().coerceIn(0f, 1f)).height(4.dp)
                            .background(MaterialTheme.colorScheme.primary))
                    }
                }
                if (book.category.isNotEmpty()) Text(book.category, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Box {
                Box(Modifier.size(44.dp).clickable { menuExpanded = true }
                    .semantics { contentDescription = "${book.title}的更多操作" }, contentAlignment = Alignment.TopCenter) {
                    JerreaderGlyph(JerreaderIcon.MORE, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.padding(top = 4.dp).size(18.dp))
                }
                DropdownMenu(menuExpanded, { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("开始阅读", color = MaterialTheme.colorScheme.onSurface) },
                        leadingIcon = { JerreaderGlyph(JerreaderIcon.BOOKS, MaterialTheme.colorScheme.onSurface, Modifier.size(18.dp)) },
                        onClick = { menuExpanded = false; onOpen() })
                    DropdownMenuItem(text = { Text("编辑书籍信息", color = MaterialTheme.colorScheme.onSurface) },
                        leadingIcon = { JerreaderGlyph(JerreaderIcon.NOTE, MaterialTheme.colorScheme.onSurface, Modifier.size(18.dp)) },
                        onClick = { menuExpanded = false; onEdit() })
                    DropdownMenuItem(text = { Text("更换封面", color = MaterialTheme.colorScheme.onSurface) },
                        leadingIcon = { JerreaderGlyph(JerreaderIcon.PHOTO, MaterialTheme.colorScheme.onSurface, Modifier.size(18.dp)) },
                        onClick = { menuExpanded = false; onChangeCover() })
                    DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { JerreaderGlyph(JerreaderIcon.TRASH, MaterialTheme.colorScheme.error, Modifier.size(18.dp)) },
                        onClick = { menuExpanded = false; onDelete() })
                }
            }
        }
    }
}

private enum class LibrarySortOrder(val title: String) {
    RECENT("最近"),
    TITLE("书名"),
    AUTHOR("作者")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookEditDialog(
    book: LibraryBook,
    coverContent: (@Composable (LibraryBook) -> Unit)?,
    onPickCover: (((String) -> Unit) -> Unit)?,
    coverDraftContent: (@Composable (String) -> Unit)?,
    onSaveCover: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String?, String?, String, String, List<String>) -> Unit
) {
    var pendingCover by rememberSaveable(book.id) { mutableStateOf<String?>(null) }
    var title by remember(book.id) { mutableStateOf(book.title) }
    var author by remember(book.id) { mutableStateOf(book.author.orEmpty()) }
    var language by remember(book.id) { mutableStateOf(book.language.orEmpty()) }
    var category by remember(book.id) { mutableStateOf(book.category) }
    var series by remember(book.id) { mutableStateOf(book.series) }
    var tags by remember(book.id) { mutableStateOf(book.tags.joinToString("，")) }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = null, containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxHeight(.95f)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) { Text("取消") }
                Text("管理书籍", modifier = Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    pendingCover?.let(onSaveCover)
                    onSave(title.trim(), author.ifBlank { null }, language.ifBlank { null }, category, series,
                        tags.split(',', '，').map(String::trim).filter(String::isNotBlank))
                }, enabled = title.isNotBlank(),
                    modifier = Modifier.align(Alignment.CenterEnd)) { Text("保存") }
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp)) {
                SettingsGroup("书籍") {
                    SettingsInsetRow {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.width(82.dp).height(123.dp).clip(RoundedCornerShape(8.dp))) {
                                val candidate = pendingCover
                                if (candidate != null && coverDraftContent != null) coverDraftContent(candidate)
                                else if (coverContent != null) coverContent(book) else JerreaderBookPlaceholder(book)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(shape = RoundedCornerShape(8.dp), color = LocalJerreaderColors.current.accentFill,
                                    modifier = Modifier.clickable(enabled = onPickCover != null) {
                                        onPickCover?.invoke { uri -> pendingCover = uri }
                                    }) {
                                    Row(Modifier.heightIn(min = 34.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        JerreaderGlyph(JerreaderIcon.PHOTO, MaterialTheme.colorScheme.primary, Modifier.size(18.dp))
                                        Text("更换封面", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                if (pendingCover != null) TextButton(onClick = { pendingCover = null }) {
                                    Text("撤销新封面", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    SettingsTextFieldRow("书名", title, { title = it })
                    SettingsTextFieldRow("作者", author, { author = it })
                    val languageOptions = listOf("" to "未指定", "ja" to "日语", "en" to "英语", "zh-Hans" to "简体中文")
                    SettingsMenuRow("语言", if (languageOptions.none { it.first == language }) languageOptions + (language to language) else languageOptions,
                        language, { language = it })
                    SettingsTextFieldRow("系列（可选）", series, { series = it })
                    SettingsValueRow("格式", book.sourceFormat.uppercase(), compact = false)
                    SettingsValueRow("阅读进度", "${(book.lastReadProgress * 100).toInt()}%", compact = false)
                    SettingsValueRow("累计阅读", "${(book.totalReadingSeconds / 60).toInt()} 分钟", compact = false)
                }
                SettingsGroup("整理") {
                    SettingsTextFieldRow("文件夹，例如：日本文学", category, { category = it })
                    SettingsTextFieldRow("标签，用逗号分隔", tags, { tags = it })
                    SettingsFootnote("文件夹会显示在书架顶部；系列与标签都参与搜索。", showDivider = false)
                }
            }
        }
    }
}

@Composable
private fun BatchBookEditDialog(
    books: List<LibraryBook>,
    onDismiss: () -> Unit,
    onApply: (Set<String>, String, String, String, List<String>) -> Unit
) {
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var category by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    var series by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量整理") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("已选择 ${selected.size} 本")
                LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                    lazyItems(books, key = LibraryBook::id) { book ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = book.id in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + book.id else selected - book.id
                                }
                            )
                            Text(book.title, maxLines = 1)
                        }
                    }
                }
                OutlinedTextField(category, { category = it }, label = { Text("统一文件夹（留空不改）") })
                OutlinedTextField(series, { series = it }, label = { Text("统一系列（留空不改）") })
                OutlinedTextField(language, { language = it }, label = { Text("统一语言代码（留空不改）") })
                OutlinedTextField(tags, { tags = it }, label = { Text("追加标签（逗号分隔）") })
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = {
                    onApply(
                        selected,
                        category,
                        series,
                        language,
                        tags.split(',', '，').map(String::trim).filter(String::isNotBlank)
                    )
                }
            ) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
