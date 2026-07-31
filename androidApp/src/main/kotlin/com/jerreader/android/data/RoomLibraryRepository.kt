package com.jerreader.android.data

import com.jerreader.shared.library.LibraryBook
import com.jerreader.shared.library.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomLibraryRepository(private val dao: BookDao) : LibraryRepository {
    override fun observeBooks(): Flow<List<LibraryBook>> =
        dao.observeAll().map { books -> books.map(BookEntity::toDomain) }

    override suspend fun book(id: String): LibraryBook? = dao.findById(id)?.toDomain()

    override suspend fun bookWithFingerprint(fingerprint: String): LibraryBook? =
        dao.findByFingerprint(fingerprint)?.toDomain()

    override suspend fun add(book: LibraryBook) {
        dao.insert(book.toEntity())
    }

    override suspend fun delete(id: String): Boolean = dao.deleteById(id) > 0

    override suspend fun updateReadingProgress(
        id: String,
        locatorJson: String,
        openedAtEpochMillis: Long
    ) {
        dao.updateReadingProgress(id, locatorJson, openedAtEpochMillis)
    }

    override suspend fun updatePreferences(id: String, preferencesJson: String) {
        dao.updatePreferences(id, preferencesJson)
    }
}
