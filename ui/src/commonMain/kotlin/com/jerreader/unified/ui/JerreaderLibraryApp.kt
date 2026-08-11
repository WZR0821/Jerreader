package com.jerreader.unified.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jerreader.unified.design.JerreaderCopy
import com.jerreader.unified.library.LibraryBook
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
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var selectedFormat by remember { mutableStateOf<String?>(null) }
    var sortOrder by remember { mutableStateOf(LibrarySortOrder.RECENT) }
    var isBatchEditing by remember { mutableStateOf(false) }
    var isShelfMenuOpen by remember { mutableStateOf(false) }
    val categories = state.books.map(LibraryBook::category)
        .filter(String::isNotBlank)
        .distinct()
        .sorted()
    val displayedBooks = state.books
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
            // The chips carry a lower-cased format; imported books have only
            // ever stored one lower-cased too, but a case mismatch here would
            // silently show an empty shelf rather than fail loudly.
            val matchesFormat = selectedFormat == null ||
                book.sourceFormat.equals(selectedFormat, ignoreCase = true)
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
        }

    val recentBook = state.books
        .filter { it.lastOpenedAtEpochMillis != null }
        .maxByOrNull { it.lastOpenedAtEpochMillis ?: 0L }
    val startedBooks = state.books.filter { it.lastReadProgress > 0 }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = bottomBar,
        topBar = {
            // iOS draws a large 「书架」 title with a ＋ and an overflow menu
            // above it. Sorting and batch editing live inside that menu rather
            // than as their own toolbar icons, which is what kept the two
            // shelves looking different at a glance.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LibraryToolbarButton(
                        icon = JerreaderIcon.PLUS,
                        enabled = !state.isImporting,
                        onClick = onImport
                    )
                    Box {
                        LibraryToolbarButton(
                            icon = JerreaderIcon.MORE,
                            onClick = { isShelfMenuOpen = true }
                        )
                        DropdownMenu(
                            expanded = isShelfMenuOpen,
                            onDismissRequest = { isShelfMenuOpen = false }
                        ) {
                            LibrarySortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (order == sortOrder) {
                                                "${JerreaderCopy.librarySortLabel}：${order.title}"
                                            } else {
                                                order.title
                                            }
                                        )
                                    },
                                    onClick = {
                                        sortOrder = order
                                        isShelfMenuOpen = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(JerreaderCopy.libraryBatchAction) },
                                enabled = state.books.isNotEmpty(),
                                onClick = {
                                    isShelfMenuOpen = false
                                    isBatchEditing = true
                                }
                            )
                        }
                    }
                }
                Text(
                    JerreaderCopy.libraryTitle,
                    modifier = Modifier.padding(bottom = 8.dp),
                    style = MaterialTheme.typography.displaySmall
                )
                // iOS pins the search field directly under the large title.
                LibrarySearchField(searchQuery) { searchQuery = it }
                // Folders moved into the grid as iOS-style cards; the chip row
                // now carries only the format filter, which iOS has no
                // equivalent for. See docs/PARITY.md.
                if (state.books.any { it.sourceFormat.isNotBlank() }) {
                    LibraryChipRow {
                        FilterChip(
                            selected = selectedFormat == null,
                            onClick = { selectedFormat = null },
                            label = { Text("全部格式") }
                        )
                        state.books.map { it.sourceFormat.lowercase() }
                            .distinct()
                            .sorted()
                            .forEach { format ->
                                FilterChip(
                                    selected = selectedFormat == format,
                                    onClick = {
                                        selectedFormat = format.takeUnless { selectedFormat == it }
                                    },
                                    label = { Text(format.uppercase()) }
                                )
                            }
                    }
                }
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
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Section order follows iOS: 继续阅读, then folders, then
                    // the grid, with the reading statistics closing the page.
                    // Android used to open with the statistics card, which put
                    // a summary above the books it summarises.
                    if (recentBook != null) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                LibrarySectionTitle(
                                    title = JerreaderCopy.libraryRecentSectionTitle,
                                    detail = JerreaderCopy.libraryRecentSectionDetail,
                                    icon = JerreaderIcon.CLOCK
                                )
                                LibraryRecentlyViewedRow(
                                    title = recentBook.title,
                                    author = recentBook.author ?: JerreaderCopy.unknownAuthor,
                                    format = recentBook.sourceFormat,
                                    lastOpenedLabel = null,
                                    progress = recentBook.lastReadProgress,
                                    onOpen = { onOpenBook(recentBook.id) },
                                    cover = { coverContent?.invoke(recentBook) }
                                )
                            }
                        }
                    }
                    if (categories.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LibraryFolderSection(
                                categories = categories,
                                bookCount = state.books.size,
                                countForCategory = { category ->
                                    state.books.count { it.category == category }
                                },
                                selectedCategory = selectedCategory,
                                onSelectCategory = { selectedCategory = it }
                            )
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LibrarySectionTitle(
                            title = JerreaderCopy.libraryAllBooksSectionTitle,
                            detail = JerreaderCopy.bookCountDetail(
                                displayedBooks.size,
                                sortOrder.title
                            ),
                            icon = JerreaderIcon.GRID
                        )
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
                    if (displayedBooks.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LibraryNoMatch()
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LibraryReadingOverview(
                            totalSeconds = state.books.sumOf { it.totalReadingSeconds },
                            startedCount = startedBooks.size,
                            averageProgress = if (startedBooks.isEmpty()) {
                                0.0
                            } else {
                                startedBooks.sumOf { it.lastReadProgress } / startedBooks.size
                            },
                            modifier = Modifier.padding(top = 10.dp)
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
                title = { Text(JerreaderCopy.bookDeleteConfirmTitle(book.title)) },
                text = { Text(JerreaderCopy.bookDeleteConfirmMessage) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingDeletion = null
                            onDeleteBook(book.id)
                        }
                    ) { Text(JerreaderCopy.bookDeleteAction) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDeletion = null }) {
                        Text(JerreaderCopy.cancel)
                    }
                }
            )
        }

    editingBook?.let { book ->
        BookEditDialog(
            book = book,
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
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
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
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(
                            "搜索书名、作者、系列、文件夹或标签",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    inner()
                }
            )
        }
    }
}

@Composable
private fun LibraryChipRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}

private const val MESSAGE_DISPLAY_MILLIS = 3_000L

@Composable
private fun EmptyLibrary(
    onImport: () -> Unit,
    onRestoreBackup: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // `JerreaderEmptyState` on iOS: a 68pt rounded tile with a 20pt radius
        // and a 30pt glyph. Android drew 96dp/24dp/46dp, which made the same
        // screen read as a much heavier illustration.
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            JerreaderGlyph(
                icon = JerreaderIcon.BOOKS,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(30.dp)
            )
        }
        Text(
            JerreaderCopy.libraryEmptyTitle,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )
        Text(
            JerreaderCopy.libraryEmptyMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        JerreaderPrimaryButton(
            text = JerreaderCopy.libraryImportAction,
            icon = JerreaderIcon.DOWNLOAD,
            onClick = onImport,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (onRestoreBackup != null) {
            TextButton(onClick = onRestoreBackup) {
                Text(JerreaderCopy.libraryRestoreAction)
            }
            Text(
                JerreaderCopy.libraryRestoreHint,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            JerreaderGlyph(
                icon = JerreaderIcon.LOCK,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp)
            )
            Text(
                JerreaderCopy.libraryPrivacyNote,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Shown when the shelf holds books but the search text or the selected folder
 * matches none of them — iOS's `ContentUnavailableView`. Android previously
 * rendered an empty grid, which looks the same as a library that lost its books.
 */
@Composable
private fun LibraryNoMatch() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        JerreaderGlyph(
            icon = JerreaderIcon.BOOKS,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(34.dp)
        )
        Text(
            JerreaderCopy.libraryNoMatchTitle,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            JerreaderCopy.libraryNoMatchMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
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
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.70f)
                    .clip(RoundedCornerShape(topStart = 15.dp, topEnd = 15.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (coverContent != null) {
                    coverContent(book)
                } else {
                    Text(
                        book.title.take(1).ifEmpty { "J" },
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Column(
                modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = book.author ?: "未知作者",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // A single overflow entry keeps the card readable; three text
                    // buttons in a row used to wrap "删除" onto two lines.
                    Box {
                        TextButton(
                            onClick = { menuExpanded = true },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("⋯", style = MaterialTheme.typography.titleMedium) }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(JerreaderCopy.bookManageAction) },
                                onClick = { menuExpanded = false; onEdit() }
                            )
                            DropdownMenuItem(
                                text = { Text(JerreaderCopy.bookChangeCoverAction) },
                                onClick = { menuExpanded = false; onChangeCover() }
                            )
                            DropdownMenuItem(
                                text = { Text(JerreaderCopy.deleteAction) },
                                onClick = { menuExpanded = false; onDelete() }
                            )
                        }
                    }
                }
                Text(
                    text = buildString {
                        append(book.sourceFormat.uppercase())
                        if (book.lastReadProgress > 0) {
                            append(" · 已读 ${(book.lastReadProgress * 100).toInt()}%")
                        }
                        if (book.totalReadingSeconds >= 60) {
                            append(" · ${(book.totalReadingSeconds / 60).toInt()} 分钟")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private enum class LibrarySortOrder(val title: String) {
    RECENT(JerreaderCopy.librarySortRecent),
    TITLE(JerreaderCopy.librarySortTitle),
    AUTHOR(JerreaderCopy.librarySortAuthor)
}

@Composable
private fun BookEditDialog(
    book: LibraryBook,
    onDismiss: () -> Unit,
    onSave: (String, String?, String?, String, String, List<String>) -> Unit
) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    var author by remember(book.id) { mutableStateOf(book.author.orEmpty()) }
    var language by remember(book.id) { mutableStateOf(book.language.orEmpty()) }
    var category by remember(book.id) { mutableStateOf(book.category) }
    var series by remember(book.id) { mutableStateOf(book.series) }
    var tags by remember(book.id) { mutableStateOf(book.tags.joinToString("，")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(JerreaderCopy.bookEditorTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("书名") })
                OutlinedTextField(author, { author = it }, label = { Text("作者") })
                OutlinedTextField(language, { language = it }, label = { Text("语言代码：ja / en / zh-Hans") })
                OutlinedTextField(category, { category = it }, label = { Text("文件夹") })
                OutlinedTextField(series, { series = it }, label = { Text("系列") })
                OutlinedTextField(tags, { tags = it }, label = { Text("标签（逗号分隔）") })
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    onSave(
                        title,
                        author.ifBlank { null },
                        language.ifBlank { null },
                        category,
                        series,
                        tags.split(',', '，').map(String::trim).filter(String::isNotBlank)
                    )
                }
            ) { Text(JerreaderCopy.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(JerreaderCopy.cancel) } }
    )
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
