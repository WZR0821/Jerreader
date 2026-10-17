package com.jerreader.android.data

import com.jerreader.shared.library.LibraryBook
import com.jerreader.shared.library.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction

class RoomLibraryRepository(
    private val dao: BookDao,
    private val database: JerreaderDatabase? = null,
    private val growthEnabled: () -> Boolean = { true }
) : LibraryRepository {
    override fun observeBooks(): Flow<List<LibraryBook>> =
        dao.observeAll().map { books -> books.map(BookEntity::toDomain) }

    override suspend fun book(id: String): LibraryBook? = dao.findById(id)?.toDomain()

    override suspend fun bookWithFingerprint(fingerprint: String): LibraryBook? =
        dao.findByFingerprint(fingerprint)?.toDomain()

    override suspend fun bookWithSourceFingerprint(fingerprint: String): LibraryBook? =
        dao.findBySourceFingerprint(fingerprint)?.toDomain()

    override suspend fun add(book: LibraryBook) {
        dao.insert(book.toEntity())
    }

    override suspend fun delete(id: String): Boolean {
        val database = database
        if (database == null) return dao.deleteById(id) > 0
        return database.withTransaction {
            val deleted = dao.deleteById(id) > 0
            if (!deleted) return@withTransaction false
            database.readerRecordDao().deleteBookmarksByBookId(id)
            database.readerRecordDao().deleteAnnotationsByBookId(id)
            database.learningDao().deleteWordsByBookId(id)
            database.learningDao().deleteTranslationFavoritesByBookId(id)
            true
        }
    }

    suspend fun updateOrganizationBatch(
        books: List<LibraryBook>,
        category: String,
        series: String,
        language: String?,
        tagsToAdd: List<String>
    ) {
        val update = suspend {
            books.forEach { book ->
                dao.updateOrganization(
                    id = book.id,
                    title = book.title.trim().ifEmpty { "未命名书籍" },
                    author = book.author?.trim()?.takeIf(String::isNotBlank),
                    language = language?.trim()?.takeIf(String::isNotBlank) ?: book.language,
                    category = category.trim().ifEmpty { book.category },
                    series = series.trim().ifEmpty { book.series },
                    tagsText = (book.tags + tagsToAdd)
                        .map(String::trim)
                        .filter(String::isNotBlank)
                        .distinctBy(String::lowercase)
                        .joinToString("\u001F")
                )
            }
        }
        if (database == null) update() else database.withTransaction { update() }
    }

    override suspend fun updateReadingProgress(
        id: String,
        locatorJson: String,
        openedAtEpochMillis: Long,
        progress: Double
    ) {
        dao.updateReadingProgress(id, locatorJson, openedAtEpochMillis, progress.coerceIn(0.0, 1.0))
    }

    override suspend fun addReadingTime(id: String, seconds: Double) {
        if (!seconds.isFinite() || seconds <= 0) return
        val bounded = seconds.coerceAtMost(21_600.0)
        val update: suspend () -> Unit = {
            dao.addReadingTime(id, bounded)
            if (growthEnabled()) {
                val book = dao.findById(id)
                if (book != null) {
                    val end = System.currentTimeMillis()
                    val activity = com.jerreader.android.learning.ReadingActivity.decode(book.activityJson)
                        .record(end - (bounded * 1000).toLong(), end)
                    dao.updateActivity(id, activity.encode())
                }
            }
        }
        if (database == null) update() else database.withTransaction { update() }
    }

    suspend fun recordReadingQuery(id: String) {
        if (!growthEnabled()) return
        val update: suspend () -> Unit = {
            val book = dao.findById(id)
            if (book != null) dao.updateActivity(id,
                com.jerreader.android.learning.ReadingActivity.decode(book.activityJson)
                    .query(System.currentTimeMillis()).encode())
        }
        if (database == null) update() else database.withTransaction { update() }
    }

    override suspend fun updatePreferences(id: String, preferencesJson: String) {
        dao.updatePreferences(id, preferencesJson)
    }

    override suspend fun updateOrganization(
        id: String,
        title: String,
        author: String?,
        language: String?,
        category: String,
        series: String,
        tags: List<String>
    ) {
        dao.updateOrganization(
            id = id,
            title = title.trim().ifEmpty { "未命名书籍" },
            author = author?.trim()?.takeIf(String::isNotBlank),
            language = language?.trim()?.takeIf(String::isNotBlank),
            category = category.trim(),
            series = series.trim(),
            tagsText = tags.map(String::trim)
                .filter(String::isNotBlank)
                .distinctBy(String::lowercase)
                .joinToString("\u001F")
        )
    }

    override suspend fun updateCover(id: String, coverFileName: String?) {
        dao.updateCover(id, coverFileName)
    }
}
