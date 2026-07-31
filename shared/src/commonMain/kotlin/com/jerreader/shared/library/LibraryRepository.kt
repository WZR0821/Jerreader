package com.jerreader.shared.library

import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    fun observeBooks(): Flow<List<LibraryBook>>

    suspend fun book(id: String): LibraryBook?

    suspend fun bookWithFingerprint(fingerprint: String): LibraryBook?

    suspend fun add(book: LibraryBook)

    suspend fun delete(id: String): Boolean

    suspend fun updateReadingProgress(
        id: String,
        locatorJson: String,
        openedAtEpochMillis: Long
    )

    suspend fun updatePreferences(id: String, preferencesJson: String)
}
