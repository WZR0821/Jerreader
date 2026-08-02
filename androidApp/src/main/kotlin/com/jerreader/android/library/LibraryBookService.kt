package com.jerreader.android.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.jerreader.shared.library.LibraryRepository
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LibraryBookService(
    private val store: ImmutablePublicationStore,
    private val repository: LibraryRepository,
    private val context: Context? = null
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

    suspend fun updateCover(bookId: String, source: Uri) = withContext(Dispatchers.IO) {
        val resolvedContext = requireNotNull(context) { "封面服务尚未配置。" }
        val bytes = checkNotNull(resolvedContext.contentResolver.openInputStream(source)) {
            "无法读取所选图片。"
        }.use { it.readBytes() }
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            "所选文件不是可用图片。"
        }
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) { "无法编码封面。" }
        val coverFileName = store.writeCover(bookId, output.toByteArray())
        repository.updateCover(bookId, coverFileName)
    }
}
