package com.jerreader.android

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jerreader.android.library.LibraryBookService
import com.jerreader.android.library.LibraryImportService
import com.jerreader.shared.library.LibraryImportOutcome
import com.jerreader.shared.library.LibraryRepository
import com.jerreader.shared.ui.LibraryUiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface LibraryEvent {
    data class OpenBook(val bookId: String) : LibraryEvent
}

private data class LibraryOperationState(
    val isImporting: Boolean = false,
    val message: String? = null
)

class LibraryViewModel(
    private val repository: LibraryRepository,
    private val importService: LibraryImportService,
    private val bookService: LibraryBookService
) : ViewModel() {
    private val operation = MutableStateFlow(LibraryOperationState())
    val events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 1)

    val uiState = combine(repository.observeBooks(), operation) { books, operationState ->
        LibraryUiState(
            books = books,
            isLoading = false,
            isImporting = operationState.isImporting,
            message = operationState.message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState()
    )

    fun importEpub(source: Uri) {
        if (operation.value.isImporting) return
        viewModelScope.launch {
            operation.value = LibraryOperationState(isImporting = true)
            runCatching { importService.importEpub(source) }
                .onSuccess { outcome ->
                    operation.value = LibraryOperationState(
                        message = when (outcome) {
                            is LibraryImportOutcome.Imported -> "《${outcome.book.title}》已导入。"
                            is LibraryImportOutcome.AlreadyImported -> "该文件已在书架中。"
                        }
                    )
                    events.emit(LibraryEvent.OpenBook(outcome.book.id))
                }
                .onFailure { error ->
                    operation.value = LibraryOperationState(
                        message = error.message ?: "无法导入该 EPUB。"
                    )
                }
        }
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            runCatching { bookService.delete(bookId) }
                .onSuccess {
                    operation.value = LibraryOperationState(message = "书籍已删除。")
                }
                .onFailure { error ->
                    operation.value = LibraryOperationState(
                        message = error.message ?: "无法删除该书籍。"
                    )
                }
        }
    }

    companion object {
        fun factory(
            repository: LibraryRepository,
            importService: LibraryImportService,
            bookService: LibraryBookService
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LibraryViewModel(repository, importService, bookService) as T
        }
    }
}
