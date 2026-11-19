package com.jerreader.shared.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import com.jerreader.shared.library.LibraryBook
import kotlinx.coroutines.delay
import kotlin.time.Clock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextAlign
import com.jerreader.shared.library.LibraryNaturalOrder

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
    /** Opens the system picker limited to the MIME types chosen in the import sheet. */
    onImport: (mimeTypes: List<String>) -> Unit,
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
    /**
     * Opens a book from its cover, with the cover's bounds in window pixels
     * (`boundsInWindow`, so clipped to what is on screen) for an opening
     * animation that grows out of the cover, as the iOS zoom does. Null, or a
     * tap without known bounds, falls back to [onOpenBook].
     */
    onOpenBookAt: ((bookId: String, coverBoundsInWindow: Rect) -> Unit)? = null,
    /**
     * The device time zone's offset from UTC at an instant, in milliseconds,
     * for today's date under the 书架 title. Null leaves the date line out.
     */
    utcOffsetMillisAt: ((epochMillis: Long) -> Int)? = null,
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
    var isShowingImportOptions by rememberSaveable { mutableStateOf(false) }
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
                LibrarySortOrder.TITLE -> books.sortedWith(compareBy(LibraryNaturalOrder) { it.title })
                LibrarySortOrder.AUTHOR -> books.sortedWith(compareBy(LibraryNaturalOrder) { it.author.orEmpty() })
            }
        } }

    val recentBook = remember(state.books) { state.books
        .filter { it.lastOpenedAtEpochMillis != null }
        .maxByOrNull { it.lastOpenedAtEpochMillis ?: 0L } }
    // The shelf's first second on screen once loaded: the one time the
    // dashboard and the grid rise in one after another. Saveable, so a tab
    // switch, a closed reader or a rotation shows the shelf at once.
    var isShelfRevealOpen by rememberSaveable { mutableStateOf(true) }
    if (!state.isLoading && isShelfRevealOpen) {
        LaunchedEffect(Unit) {
            delay(SHELF_REVEAL_WINDOW_MILLIS)
            isShelfRevealOpen = false
        }
    }
    val gridState = rememberLazyGridState()
    val haptics = LocalHapticFeedback.current
    // Refreshed on the minute, so the date rolls over at midnight and the
    // hero's relative time keeps up.
    val nowMillis by produceState(currentEpochMillis()) {
        while (true) {
            delay(MINUTE_MILLIS - currentEpochMillis().mod(MINUTE_MILLIS))
            value = currentEpochMillis()
        }
    }
    val dateText = utcOffsetMillisAt?.let { offsetAt -> LibraryShelfDate.format(nowMillis, offsetAt(nowMillis)) }
    val isSearchActive = searchQuery.isNotBlank()
    val statsSummary = remember(state.books) { LibraryReadingStatsSummary.of(state.books) }
    val openBook: (String, Rect?) -> Unit = { bookId, coverBounds ->
        val openAt = onOpenBookAt
        val visibleCover = coverBounds?.takeIf { it.width > 0f && it.height > 0f }
        if (openAt != null && visibleCover != null) openAt(bookId, visibleCover) else onOpenBook(bookId)
    }
    // iOS plays the warning haptic as the delete confirmation appears.
    LaunchedEffect(pendingDeletion?.id) {
        if (pendingDeletion != null) haptics.performHapticFeedback(HapticFeedbackType.Reject)
    }

    Scaffold(
        modifier = modifier.fillMaxSize().jerreaderCanvasBackground(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
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
                        onClick = { isShowingImportOptions = true },
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
                                // The filter in effect is ticked, like the sort order above.
                                val filterMark: @Composable (Boolean) -> Unit = { chosen ->
                                    if (chosen) JerreaderGlyph(JerreaderIcon.CHECK, MaterialTheme.colorScheme.primary, Modifier.size(18.dp))
                                }
                                DropdownMenuItem(
                                    text = { Text("全部书籍") },
                                    onClick = { selectedCategory = null; isLibraryMenuExpanded = false },
                                    trailingIcon = { filterMark(selectedCategory == null) }
                                )
                                categories.forEach { category ->
                                    DropdownMenuItem(
                                        text = { Text(category) },
                                        onClick = { selectedCategory = category; isLibraryMenuExpanded = false },
                                        trailingIcon = { filterMark(selectedCategory == category) }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("全部格式") },
                                    onClick = { selectedFormat = null; isLibraryMenuExpanded = false },
                                    trailingIcon = { filterMark(selectedFormat == null) }
                                )
                                state.books.map { it.sourceFormat.lowercase() }.distinct().sorted().forEach { format ->
                                    DropdownMenuItem(
                                        text = { Text(format.uppercase()) },
                                        onClick = { selectedFormat = format; isLibraryMenuExpanded = false },
                                        trailingIcon = { filterMark(selectedFormat == format) }
                                    )
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
                LibrarySearchField(searchQuery) { query ->
                    val wasSearching = searchQuery.isNotBlank()
                    searchQuery = query
                    // The dashboard comes back above 所有书籍 when a search is
                    // cleared; at the top of the grid, scroll so it shows
                    // instead of staying pinned above the viewport.
                    if (wasSearching && query.isBlank() &&
                        gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset == 0
                    ) {
                        gridState.requestScrollToItem(0)
                    }
                }
                Spacer(Modifier.padding(bottom = 8.dp))
            }
        }
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
            when {
                state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.books.isEmpty() -> LibraryEmptyShelfView(
                    dateText = dateText,
                    isImporting = state.isImporting,
                    revealsOnAppear = isShelfRevealOpen,
                    onImport = { isShowingImportOptions = true },
                    onRestore = onRestoreBackup
                )
                else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    // iOS: at most 1120 wide and centred, never closer than 20 to the edges.
                    val sidePadding = ((maxWidth - SHELF_MAX_CONTENT_WIDTH) / 2).coerceAtLeast(JerreaderSpacing.lg)
                    // Three columns on a phone; on a wide window as many 132dp-or-wider ones as fit.
                    val columns = if (maxWidth >= 600.dp) GridCells.Adaptive(132.dp) else GridCells.Fixed(3)
                    val itemFade = JerreaderMotion.stateChange<Float>()
                    val itemPlacement = JerreaderMotion.stateChange<IntOffset>()
                    LazyVerticalGrid(
                        columns = columns,
                        modifier = Modifier.fillMaxSize(),
                        state = gridState,
                        contentPadding = PaddingValues(
                            start = sidePadding,
                            end = sidePadding,
                            top = JerreaderSpacing.xxs,
                            // 36 under the content; the last row of tiles brings 24 of it.
                            bottom = if (displayedBooks.isEmpty()) 36.dp else 12.dp
                        ),
                        horizontalArrangement = Arrangement.spacedBy(JerreaderSpacing.md)
                    ) {
                        // The dashboard steps aside while searching, so the results
                        // start right under the search field.
                        if (!isSearchActive) {
                            item(key = "shelf:dashboard", span = { GridItemSpan(maxLineSpan) }, contentType = "dashboard") {
                                LibraryShelfDashboard(
                                    dateText = dateText,
                                    recentBook = recentBook,
                                    summary = statsSummary,
                                    nowMillis = nowMillis,
                                    revealOpen = isShelfRevealOpen,
                                    onOpenRecent = { coverBounds -> recentBook?.let { openBook(it.id, coverBounds) } },
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = itemFade,
                                        placementSpec = itemPlacement,
                                        fadeOutSpec = itemFade
                                    ),
                                    coverContent = coverContent
                                )
                            }
                        }
                        item(key = "shelf:header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                            val headerTop by animateDpAsState(
                                targetValue = if (isSearchActive) JerreaderSpacing.xxs else JerreaderSpacing.xl,
                                animationSpec = JerreaderMotion.stateChange(),
                                label = "shelfHeaderTop"
                            )
                            LibraryShelfSectionHeader(
                                title = "所有书籍",
                                detail = "${displayedBooks.size} 本",
                                modifier = Modifier
                                    .animateItem(fadeInSpec = null, placementSpec = itemPlacement, fadeOutSpec = null)
                                    .libraryShelfReveal(order = 3, isEnabled = isShelfRevealOpen)
                                    .padding(top = headerTop)
                            ) {
                                LibrarySortCapsule(selected = sortOrder, onSelect = { sortOrder = it })
                            }
                        }
                        if (categories.isNotEmpty()) {
                            item(key = "shelf:folders", span = { GridItemSpan(maxLineSpan) }, contentType = "folders") {
                                LibraryFolderChips(
                                    categories = categories,
                                    books = state.books,
                                    selectedCategory = selectedCategory,
                                    onSelect = { selectedCategory = it },
                                    modifier = Modifier
                                        .animateItem(fadeInSpec = itemFade, placementSpec = itemPlacement, fadeOutSpec = itemFade)
                                        .libraryShelfReveal(order = 3, isEnabled = isShelfRevealOpen)
                                        .padding(top = JerreaderSpacing.sm)
                                )
                            }
                        }
                        if (displayedBooks.isEmpty()) {
                            item(key = "shelf:no-match", span = { GridItemSpan(maxLineSpan) }, contentType = "no-match") {
                                LibraryNoMatchView(
                                    Modifier
                                        .animateItem(fadeInSpec = itemFade, placementSpec = itemPlacement, fadeOutSpec = itemFade)
                                        .padding(top = JerreaderSpacing.sm)
                                )
                            }
                        } else {
                            item(key = "shelf:grid-top", span = { GridItemSpan(maxLineSpan) }, contentType = "grid-top") {
                                Spacer(
                                    Modifier
                                        .animateItem(fadeInSpec = null, placementSpec = itemPlacement, fadeOutSpec = null)
                                        .height(JerreaderSpacing.md)
                                )
                            }
                            itemsIndexed(
                                displayedBooks,
                                key = { _, book -> book.id },
                                contentType = { _, _ -> "book" }
                            ) { index, book ->
                                LibraryBookTile(
                                    book = book,
                                    onOpen = { coverBounds -> openBook(book.id, coverBounds) },
                                    onEdit = { editingBook = book },
                                    onChangeCover = { onChangeCover(book.id) },
                                    onDelete = { pendingDeletion = book },
                                    modifier = Modifier
                                        .animateItem(fadeInSpec = itemFade, placementSpec = itemPlacement, fadeOutSpec = itemFade)
                                        // iOS: 24 between rows, outside the tile's own reveal.
                                        .padding(bottom = JerreaderSpacing.xl)
                                        .libraryShelfReveal(order = index + 4, isEnabled = isShelfRevealOpen),
                                    coverContent = coverContent
                                )
                            }
                        }
                    }
                }
            }

            // iOS `importingOverlay`. Until 2.0.1 the plus button merely
            // greyed out, so importing a large book looked like nothing was
            // happening. Purely a notice: it takes no touches.
            if (state.isImporting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.16f))
                        .semantics(mergeDescendants = true) {},
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier
                            .padding(32.dp)
                            .widthIn(max = 280.dp)
                            .jerreaderPaperCard(raised = true)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        ReaderChromeLoadingGlyph(JerreaderIcon.BOOKS, 62.dp)
                        Text("正在整理这本书…", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "正在校验格式，并把阅读副本与封面保存到本机",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
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
    if (isShowingImportOptions) {
        BookImportOptionsSheet(
            isImporting = state.isImporting,
            onDismiss = { isShowingImportOptions = false },
            onChooseFile = { mimeTypes ->
                // The system picker covers the app anyway; iOS also closes this
                // sheet once its picker returns, picked or cancelled.
                isShowingImportOptions = false
                onImport(mimeTypes)
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

/** iOS staggers the shelf in only during its first second on screen. */
private const val SHELF_REVEAL_WINDOW_MILLIS = 1_000L
private const val MINUTE_MILLIS = 60_000L

/** iOS frames the shelf at most 1120 points wide. */
private val SHELF_MAX_CONTENT_WIDTH = 1_120.dp

private fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

internal enum class LibrarySortOrder(val title: String) {
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
    val sheetTop = pageSheetTop()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = PageSheetShape, dragHandle = null, containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.pageSheetHeight(sheetTop)) {
            // The inline navigation bar of an iOS sheet is 56 points tall.
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                PageSheetGrabber()
                // Bar buttons in body size, their text 20 points from the edges.
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) {
                    Text("取消", style = MaterialTheme.typography.bodyLarge)
                }
                Text("管理书籍", modifier = Modifier.align(Alignment.Center), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    pendingCover?.let(onSaveCover)
                    onSave(title.trim(), author.ifBlank { null }, language.ifBlank { null }, category, series,
                        // The same separators as iOS: both commas, 、 and a new line.
                        tags.split(',', '，', '、', '\n').map(String::trim).filter(String::isNotBlank))
                }, enabled = title.isNotBlank(),
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) {
                    Text("保存", style = MaterialTheme.typography.bodyLarge)
                }
            }
            // Spaced like the iOS `Form` this sheet holds.
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 14.6.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(PlainFormGroupSpacing)) {
              CompositionLocalProvider(LocalGroupedFormSpacing provides PlainFormSpacing) {
                SettingsGroup("书籍") {
                    SettingsInsetRow(showDivider = false) {
                        Row(Modifier.padding(vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(Modifier.width(82.dp).height(123.dp)
                                .jerreaderBookCover(RoundedCornerShape(JerreaderRadius.cover), elevated = false)) {
                                val candidate = pendingCover
                                if (candidate != null && coverDraftContent != null) coverDraftContent(candidate)
                                else if (coverContent != null) coverContent(book) else JerreaderBookPlaceholder(book)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Surface(shape = RoundedCornerShape(8.dp), color = LocalJerreaderColors.current.accentFill,
                                    modifier = Modifier.clickable(enabled = onPickCover != null) {
                                        onPickCover?.invoke { uri -> pendingCover = uri }
                                    }) {
                                    // A bordered iOS button around a `Label`: 135 by 34.3 points.
                                    Row(Modifier.heightIn(min = 34.dp).padding(start = 17.dp, end = 12.6.dp, top = 6.9.dp, bottom = 6.9.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.3.dp)) {
                                        JerreaderGlyph(JerreaderIcon.PHOTO, MaterialTheme.colorScheme.primary, Modifier.size(18.dp))
                                        Text("更换封面", style = MaterialTheme.typography.bodyLarge.copy(
                                            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
                                        ), color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                                if (pendingCover != null) TextButton(onClick = { pendingCover = null }) {
                                    Text("撤销新封面", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    // iOS starts this separator under the button's title.
                    JerreaderRowSeparator(start = 174.dp)
                    SettingsTextFieldRow("书名", title, { title = it })
                    SettingsTextFieldRow("作者", author, { author = it })
                    val languageOptions = listOf("" to "未指定", "ja" to "日语", "en" to "英语", "zh-Hans" to "简体中文")
                    SettingsMenuRow("语言", if (languageOptions.none { it.first == language }) languageOptions + (language to language) else languageOptions,
                        language, { language = it })
                    SettingsTextFieldRow("系列（可选）", series, { series = it })
                    SettingsValueRow("格式", book.sourceFormat.uppercase(), compact = false)
                    SettingsValueRow("阅读进度", LibraryShelfFormat.progressText(book.lastReadProgress), compact = false)
                    SettingsValueRow("累计阅读", LibraryShelfFormat.durationText(book.totalReadingSeconds), compact = false)
                }
                SettingsGroup("整理") {
                    SettingsTextFieldRow("文件夹，例如：日本文学", category, { category = it })
                    SettingsTextFieldRow("标签，用逗号分隔", tags, { tags = it }, minLines = 2, maxLines = 4)
                    SettingsFootnote("文件夹会显示在书架顶部；系列与标签都参与搜索。", showDivider = false)
                }
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
                OutlinedTextField(tags, { tags = it }, label = { Text("追加标签（逗号或顿号分隔）") })
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
