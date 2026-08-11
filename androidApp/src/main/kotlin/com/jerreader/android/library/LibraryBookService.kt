package com.jerreader.android.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.jerreader.unified.library.LibraryRepository
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LibraryBookService(
    private val store: ImmutablePublicationStore,
    private val repository: LibraryRepository,
    private val context: Context? = null
) {
    suspend fun delete(bookId: String) = withContext(Dispatchers.IO) {
        store.reclaimAbandonedDeletions()
        val book = repository.book(bookId) ?: return@withContext
        val staged = store.stageDeletion(book.publicationFileName, book.coverFileName)
        var rowDeleted = false
        try {
            check(repository.delete(bookId)) { "书籍已不存在。" }
            rowDeleted = true
            store.finalizeDeletion(staged)
        } catch (error: Throwable) {
            if (!rowDeleted) {
                // The repository transaction did not commit, so restore the
                // live files and keep the row pointing at valid paths.
                runCatching { store.restoreDeletion(staged) }
            }
            // Once the row is gone, never restore the staged files into the
            // live directories: that would create orphan publications. A
            // failed final cleanup remains isolated under the staging root and
            // can be reclaimed by a later maintenance pass.
            throw error
        }
    }

    suspend fun updateCover(bookId: String, source: Uri) = withContext(Dispatchers.IO) {
        store.reclaimAbandonedDeletions()
        val resolvedContext = requireNotNull(context) { "封面服务尚未配置。" }
        val oldCover = repository.book(bookId)?.coverFileName
        val bytes = checkNotNull(resolvedContext.contentResolver.openInputStream(source)) {
            "无法读取所选图片。"
        }.use { it.readBytes() }
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            "所选文件不是可用图片。"
        }
        val output = ByteArrayOutputStream()
        try {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) { "无法编码封面。" }
        } finally {
            bitmap.recycle()
        }
        val coverFileName = store.writeCover(bookId, output.toByteArray())
        val stagedOldCover = try {
            oldCover?.let { store.stageDeletion(publicationFileName = null, coverFileName = it) }
        } catch (error: Throwable) {
            store.deleteCover(coverFileName)
            throw error
        }
        var rowUpdated = false
        try {
            repository.updateCover(bookId, coverFileName)
            val updatedBook = repository.book(bookId)
            if (updatedBook?.coverFileName != coverFileName) {
                throw IllegalStateException("书籍已不存在或封面更新未生效。")
            }
            rowUpdated = true
            // The database row now points at the new immutable cover. Cleanup
            // of the old staged file is best-effort: if it fails, keeping the
            // new cover is safer than reporting failure and deleting the file
            // that the committed row references.
            stagedOldCover?.let { staged ->
                runCatching { store.finalizeDeletion(staged) }
            }
        } catch (error: Throwable) {
            if (!rowUpdated) {
                val rowStillExists = runCatching { repository.book(bookId) }.getOrNull() != null
                stagedOldCover?.let { staged ->
                    if (rowStillExists) {
                        runCatching { store.restoreDeletion(staged) }
                    } else {
                        runCatching { store.finalizeDeletion(staged) }
                    }
                }
                store.deleteCover(coverFileName)
            }
            throw error
        }
    }
}
