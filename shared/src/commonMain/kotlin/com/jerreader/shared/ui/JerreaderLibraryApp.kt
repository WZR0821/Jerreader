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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    modifier: Modifier = Modifier,
    coverContent: (@Composable (LibraryBook) -> Unit)? = null
) {
    var pendingDeletion by remember { mutableStateOf<LibraryBook?>(null) }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF3E668C),
            secondary = Color(0xFF607D98),
            background = Color(0xFFF4F6F8),
            surface = Color.White
        )
    ) {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
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
                        Button(
                            enabled = !state.isImporting,
                            onClick = onImport
                        ) {
                            Text(if (state.isImporting) "导入中…" else "导入 EPUB")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        ) { contentPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
            ) {
                when {
                    state.isLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.books.isEmpty() -> EmptyLibrary(
                        onImport = onImport,
                        modifier = Modifier.align(Alignment.Center)
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 148.dp),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(state.books, key = LibraryBook::id) { book ->
                            LibraryBookCard(
                                book = book,
                                onOpen = { onOpenBook(book.id) },
                                onDelete = { pendingDeletion = book },
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
            "导入你拥有的无 DRM EPUB，阅读数据仅保存在本机。",
            color = MaterialTheme.colorScheme.secondary
        )
        Button(onClick = onImport) { Text("选择 EPUB") }
    }
}

@Composable
private fun LibraryBookCard(
    book: LibraryBook,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
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
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDelete) { Text("删除") }
                }
            }
        }
    }
}
