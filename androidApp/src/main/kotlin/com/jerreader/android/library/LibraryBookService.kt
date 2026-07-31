package com.jerreader.android.library

import com.jerreader.shared.library.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LibraryBookService(
    private val store: ImmutablePublicationStore,
    private val repository: LibraryRepository
) {
    suspend fun delete(bookId: String) = withContext(Dispatchers.IO) {
        val book = repository.book(bookId) ?: return@withContext
        if (!repository.delete(bookId)) return@withContext

        val publicationDeleted = store.deletePublication(book.publicationFileName)
        val coverDeleted = store.deleteCover(book.coverFileName)
        if (!publicationDeleted || !coverDeleted) {
            repository.add(book)
            error("无法完整删除书籍，书架记录已恢复。")
        }
    }
}
