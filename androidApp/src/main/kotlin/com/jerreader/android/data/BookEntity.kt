package com.jerreader.android.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.jerreader.shared.library.LibraryBook

@Entity(
    tableName = "books",
    indices = [Index(value = ["fingerprint"], unique = true)]
)
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val language: String?,
    val publicationFileName: String,
    val coverFileName: String?,
    val fingerprint: String,
    val fileSize: Long,
    val publicationLastModified: Long,
    val importedAtEpochMillis: Long,
    val lastOpenedAtEpochMillis: Long?,
    val locatorJson: String?,
    val preferencesJson: String?
)

fun BookEntity.toDomain(): LibraryBook = LibraryBook(
    id = id,
    title = title,
    author = author,
    language = language,
    publicationFileName = publicationFileName,
    coverFileName = coverFileName,
    fingerprint = fingerprint,
    fileSize = fileSize,
    publicationLastModified = publicationLastModified,
    importedAtEpochMillis = importedAtEpochMillis,
    lastOpenedAtEpochMillis = lastOpenedAtEpochMillis,
    locatorJson = locatorJson,
    preferencesJson = preferencesJson
)

fun LibraryBook.toEntity(): BookEntity = BookEntity(
    id = id,
    title = title,
    author = author,
    language = language,
    publicationFileName = publicationFileName,
    coverFileName = coverFileName,
    fingerprint = fingerprint,
    fileSize = fileSize,
    publicationLastModified = publicationLastModified,
    importedAtEpochMillis = importedAtEpochMillis,
    lastOpenedAtEpochMillis = lastOpenedAtEpochMillis,
    locatorJson = locatorJson,
    preferencesJson = preferencesJson
)
