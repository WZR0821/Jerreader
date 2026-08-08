package com.jerreader.android

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jerreader.android.library.LibraryBookService
import com.jerreader.android.library.LibraryImportService
import com.jerreader.android.data.RoomLibraryRepository
import com.jerreader.shared.library.LibraryBook
import com.jerreader.shared.library.LibraryImportOutcome
import com.jerreader.shared.library.LibraryRepository
import com.jerreader.shared.ui.LibraryUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

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
    private val eventChannel = Channel<LibraryEvent>(capacity = Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

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

    fun importEpub(source: Uri) = importPublication(source)

    fun importPublication(source: Uri) {
        if (operation.value.isImporting) return
        viewModelScope.launch {
            operation.value = LibraryOperationState(isImporting = true)
            runCatching { importService.importPublication(source) }
                .onSuccess { outcome ->
                    operation.value = LibraryOperationState(
                        message = when (outcome) {
                            is LibraryImportOutcome.Imported -> "《${outcome.book.title}》已导入。"
                            is LibraryImportOutcome.AlreadyImported -> "该文件已在书架中。"
                        }
                    )
                    eventChannel.send(LibraryEvent.OpenBook(outcome.book.id))
                }
                .onFailure { error ->
                    operation.value = LibraryOperationState(
                        message = error.message ?: "无法导入该电子书。"
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

    fun clearMessage() {
        operation.value = operation.value.copy(message = null)
    }

    override fun onCleared() {
        eventChannel.close()
        super.onCleared()
    }

    fun updateBook(
        bookId: String,
        title: String,
        author: String?,
        language: String?,
        category: String,
        series: String,
        tags: List<String>
    ) {
        viewModelScope.launch {
            runCatching {
                repository.updateOrganization(
                    id = bookId,
                    title = title,
                    author = author,
                    language = language,
                    category = category,
                    series = series,
                    tags = tags
                )
            }.onSuccess {
                operation.value = LibraryOperationState(message = "书籍信息已更新。")
            }.onFailure { error ->
                operation.value = LibraryOperationState(
                    message = error.message ?: "无法更新书籍信息。"
                )
            }
        }
    }

    /** Applies one batch edit inside the Room transaction when available. */
    fun updateBooks(
        books: List<LibraryBook>,
        category: String,
        series: String,
        language: String,
        tags: List<String>
    ) {
        if (books.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val roomRepository = repository as? RoomLibraryRepository
                if (roomRepository != null) {
                    roomRepository.updateOrganizationBatch(
                        books = books,
                        category = category,
                        series = series,
                        language = language,
                        tagsToAdd = tags
                    )
                } else {
                    books.forEach { book ->
                        repository.updateOrganization(
                            id = book.id,
                            title = book.title,
                            author = book.author,
                            language = language.ifBlank { book.language },
                            category = category.ifBlank { book.category },
                            series = series.ifBlank { book.series },
                            tags = (book.tags + tags).distinctBy(String::lowercase)
                        )
                    }
                }
            }.onSuccess {
                operation.value = LibraryOperationState(message = "批量编辑已完成。")
            }.onFailure { error ->
                operation.value = LibraryOperationState(
                    message = error.message ?: "批量编辑失败，未应用更改。"
                )
            }
        }
    }

    fun updateCover(bookId: String, source: Uri) {
        viewModelScope.launch {
            runCatching { bookService.updateCover(bookId, source) }
                .onSuccess {
                    operation.value = LibraryOperationState(message = "封面已更新。")
                }
                .onFailure { error ->
                    operation.value = LibraryOperationState(
                        message = error.message ?: "无法更新封面。"
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
