package com.jerreader.android

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jerreader.android.reader.ReaderActivity
import com.jerreader.shared.library.LibraryBook
import com.jerreader.shared.ui.JerreaderLibraryApp
import java.io.File
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val graph: AppGraph
        get() = (application as JerreaderApplication).graph

    private val viewModel by viewModels<LibraryViewModel> {
        LibraryViewModel.factory(graph.repository, graph.importService, graph.bookService)
    }

    private val choosePublication =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(viewModel::importEpub)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is LibraryEvent.OpenBook -> openBook(event.bookId)
                    }
                }
            }
        }

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            JerreaderLibraryApp(
                state = state,
                onImport = {
                    choosePublication.launch(arrayOf("application/epub+zip"))
                },
                onOpenBook = ::openBook,
                onDeleteBook = viewModel::deleteBook,
                coverContent = { book -> AndroidBookCover(book) }
            )
        }
    }

    private fun openBook(bookId: String) {
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId)
        )
    }

    @Composable
    private fun AndroidBookCover(book: LibraryBook) {
        val cover = remember(book.coverFileName) {
            book.coverFileName
                ?.let(graph.publicationStore::resolveCover)
                ?.takeIf(File::isFile)
                ?.let { file -> BitmapFactory.decodeFile(file.absolutePath) }
        }

        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = "${book.title}封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    book.title.take(1).ifEmpty { "J" },
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}
