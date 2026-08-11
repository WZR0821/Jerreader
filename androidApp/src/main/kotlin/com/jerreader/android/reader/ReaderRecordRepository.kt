package com.jerreader.android.reader

import com.jerreader.android.data.ReaderRecordDao
import com.jerreader.android.data.ReadingAnnotationEntity
import com.jerreader.android.data.ReadingBookmarkEntity
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class ReaderRecordRepository(
    private val dao: ReaderRecordDao,
    private val now: () -> Long = System::currentTimeMillis
) {
    fun observeBookmarks(bookId: String): Flow<List<ReadingBookmarkEntity>> =
        dao.observeBookmarks(bookId)

    fun observeAnnotations(bookId: String): Flow<List<ReadingAnnotationEntity>> =
        dao.observeAnnotations(bookId)

    suspend fun toggleBookmark(
        key: String,
        bookId: String,
        bookTitle: String,
        locatorJson: String,
        chapterTitle: String,
        excerpt: String?,
        progress: Double
    ): Boolean {
        if (dao.bookmark(key) != null) {
            dao.deleteBookmark(key)
            return false
        }
        dao.upsertBookmark(
            ReadingBookmarkEntity(
                id = UUID.randomUUID().toString(),
                bookmarkKey = key,
                bookId = bookId,
                bookTitle = bookTitle,
                locatorJson = locatorJson,
                chapterTitle = chapterTitle,
                excerpt = excerpt,
                progress = progress.coerceIn(0.0, 1.0),
                createdAtEpochMillis = now()
            )
        )
        return true
    }

    suspend fun saveAnnotation(
        bookId: String,
        bookTitle: String,
        locatorJson: String,
        selectedText: String,
        noteText: String,
        color: String,
        chapterTitle: String,
        progress: Double,
        geometryJson: String? = null
    ): ReadingAnnotationEntity {
        val timestamp = now()
        val key = AndroidReaderRecordKeys.annotation(bookId, locatorJson, selectedText)
        val record = ReadingAnnotationEntity(
            id = UUID.randomUUID().toString(),
            annotationKey = key,
            bookId = bookId,
            bookTitle = bookTitle,
            locatorJson = locatorJson,
            selectedText = selectedText,
            noteText = noteText.trim(),
            color = color,
            chapterTitle = chapterTitle,
            progress = progress.coerceIn(0.0, 1.0),
            createdAtEpochMillis = timestamp,
            updatedAtEpochMillis = timestamp,
            geometryJson = geometryJson
        )
        dao.upsertAnnotation(record)
        return record
    }

    suspend fun deleteAnnotation(id: String) {
        dao.deleteAnnotation(id)
    }

    suspend fun deleteBookmark(id: String) {
        dao.deleteBookmarkById(id)
    }

    suspend fun updateAnnotation(id: String, noteText: String, color: String) {
        dao.updateAnnotation(id, noteText.trim(), color, now())
    }

}
