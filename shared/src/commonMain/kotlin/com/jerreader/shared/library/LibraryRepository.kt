package com.jerreader.shared.library

import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    fun observeBooks(): Flow<List<LibraryBook>>

    suspend fun book(id: String): LibraryBook?

    suspend fun bookWithFingerprint(fingerprint: String): LibraryBook?

    suspend fun bookWithSourceFingerprint(fingerprint: String): LibraryBook?

    suspend fun add(book: LibraryBook)

    suspend fun delete(id: String): Boolean

    suspend fun updateReadingProgress(
        id: String,
        locatorJson: String,
        openedAtEpochMillis: Long,
        progress: Double = 0.0
    )

    suspend fun addReadingTime(id: String, seconds: Double)

    suspend fun updatePreferences(id: String, preferencesJson: String)

    suspend fun updateOrganization(
        id: String,
        title: String,
        author: String?,
        language: String?,
        category: String,
        series: String,
        tags: List<String>
    )

    suspend fun updateCover(id: String, coverFileName: String?)
}
