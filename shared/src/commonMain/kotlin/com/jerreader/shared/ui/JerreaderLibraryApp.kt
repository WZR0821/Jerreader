package com.jerreader.shared.ui

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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jerreader.shared.library.LibraryBook

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
        }

    Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = bottomBar,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Jerreader", fontWeight = FontWeight.SemiBold)
                            Text(
                                "我的书架",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    },
                    actions = {
                        TextButton(
                            enabled = state.books.isNotEmpty(),
                            onClick = { isBatchEditing = true }
                        ) { Text("批量") }
                        Button(
                            enabled = !state.isImporting,
                            onClick = onImport
                        ) {
                            Text(if (state.isImporting) "导入中…" else "导入电子书")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        ) { contentPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    label = { Text("搜索书名、作者、系列、文件夹或标签") },
                    singleLine = true
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("全部") }
                    )
                    categories.forEach { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            label = { Text(category) }
                        )
                    }
                    FilterChip(
                        selected = selectedFormat == null,
                        onClick = { selectedFormat = null },
                        label = { Text("全部格式") }
                    )
                    listOf("epub", "pdf", "docx", "txt").forEach { format ->
                        FilterChip(
                            selected = selectedFormat == format,
                            onClick = { selectedFormat = format },
                            label = { Text(format.uppercase()) }
                        )
                    }
                    LibrarySortOrder.entries.forEach { order ->
                        FilterChip(
                            selected = sortOrder == order,
                            onClick = { sortOrder = order },
                            label = { Text(order.title) }
                        )
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when {
                    state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.books.isEmpty() -> EmptyLibrary(
                        onImport = onImport,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    displayedBooks.isEmpty() -> Text(
                        "没有符合筛选条件的书籍。",
                        modifier = Modifier.align(Alignment.Center)
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 148.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
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
                    }
                }

                state.message?.let { message ->
                    Card(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
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
                isBatchEditing = false
            }
        )
    }
}

@Composable
private fun EmptyLibrary(onImport: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("书架还是空的", style = MaterialTheme.typography.headlineSmall)
        Text(
            "导入你拥有的 EPUB、PDF、DOCX 或 TXT，阅读数据仅保存在本机。",
            color = MaterialTheme.colorScheme.secondary
        )
        Button(onClick = onImport) { Text("选择电子书") }
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
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f),
                contentAlignment = Alignment.Center
            ) {
                if (coverContent != null) {
                    coverContent(book)
                } else {
                    Text(
                        book.title.take(1).ifEmpty { "J" },
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = book.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = book.author ?: "未知作者",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onEdit) { Text("管理") }
                    TextButton(onClick = onChangeCover) { Text("封面") }
                    TextButton(onClick = onDelete) { Text("删除") }
                }
                if (book.lastReadProgress > 0) {
                    Text(
                        "已读 ${(book.lastReadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    buildString {
                        append(book.sourceFormat.uppercase())
                        if (book.totalReadingSeconds > 0) {
                            append(" · ")
                            append((book.totalReadingSeconds / 60).toInt())
                            append(" 分钟")
                        }
                        if (book.category.isNotBlank()) append(" · ${book.category}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

private enum class LibrarySortOrder(val title: String) {
    RECENT("最近"),
    TITLE("书名"),
    AUTHOR("作者")
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
        title = { Text("管理书籍") },
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
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
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
