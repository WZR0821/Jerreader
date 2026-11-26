package com.jerreader.android.reader

import com.jerreader.android.data.ReaderRecordDao
import com.jerreader.android.data.ReadingAnnotationEntity
import com.jerreader.android.data.ReadingBookmarkEntity
import com.jerreader.shared.translation.AIReadingActionPolicy
import java.util.UUID
import java.security.MessageDigest
import java.text.Normalizer
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
        val key = annotationKey(bookId, locatorJson, selectedText)
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

    /**
     * 存为笔记 of an AI result (2.1.0 §6). The highlight of this very
     * selection, found by its key, keeps its note and gets 「【title】」 and
     * [body] after a blank line; without one, a highlight in [defaultColor]
     * is made with the result as its note. Unlike [saveAnnotation], nothing
     * the note already says is replaced. [otherSelectedTexts] are other ways
     * the same selection may have been saved: the selection menu keeps the
     * highlighted text, the translation card's 添加笔记 the card's passage.
     */
    suspend fun appendToAnnotation(
        bookId: String,
        bookTitle: String,
        locatorJson: String,
        selectedText: String,
        title: String,
        body: String,
        defaultColor: String,
        chapterTitle: String,
        progress: Double,
        geometryJson: String? = null,
        otherSelectedTexts: List<String> = emptyList()
    ) {
        val existing = findAnnotation(bookId, locatorJson, listOf(selectedText) + otherSelectedTexts)
        if (existing != null) {
            updateAnnotation(existing.id, AIReadingActionPolicy.appendedNote(existing.noteText, title, body), existing.color)
            return
        }
        saveAnnotation(
            bookId = bookId,
            bookTitle = bookTitle,
            locatorJson = locatorJson,
            selectedText = selectedText,
            noteText = AIReadingActionPolicy.appendedNote(null, title, body),
            color = defaultColor,
            chapterTitle = chapterTitle,
            progress = progress,
            geometryJson = geometryJson
        )
    }

    /**
     * The highlight of this selection saved under the first of
     * [selectedTexts] that has one: the same selection may be kept under the
     * highlighted text (the selection menu) or the card's passage (the
     * translation card).
     */
    suspend fun findAnnotation(
        bookId: String,
        locatorJson: String,
        selectedTexts: List<String>
    ): ReadingAnnotationEntity? = selectedTexts
        .filter(String::isNotBlank)
        .distinct()
        .firstNotNullOfOrNull { text -> dao.annotation(annotationKey(bookId, locatorJson, text)) }

    private fun annotationKey(bookId: String, locatorJson: String, selectedText: String): String {
        val canonical = listOf(
            bookId.trim(),
            normalize(locatorJson),
            normalize(selectedText)
        ).joinToString("\u001F")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.encodeToByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value.trim(), Normalizer.Form.NFC)
        .replace(Regex("[\\t\\r\\n ]+"), " ")
}
