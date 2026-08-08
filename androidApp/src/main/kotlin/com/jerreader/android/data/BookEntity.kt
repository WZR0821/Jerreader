package com.jerreader.android.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import com.jerreader.shared.library.LibraryBook

@Entity(
    tableName = "books",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["sourceFingerprint"], unique = true)
    ]
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
    val preferencesJson: String?,
    @ColumnInfo(defaultValue = "'epub'") val sourceFormat: String = "epub",
    @ColumnInfo(defaultValue = "''") val sourceFingerprint: String = fingerprint,
    @ColumnInfo(defaultValue = "0") val lastReadProgress: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val totalReadingSeconds: Double = 0.0,
    @ColumnInfo(defaultValue = "''") val category: String = "",
    @ColumnInfo(defaultValue = "''") val series: String = "",
    @ColumnInfo(defaultValue = "''") val tagsText: String = ""
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
    preferencesJson = preferencesJson,
    sourceFormat = sourceFormat,
    sourceFingerprint = sourceFingerprint,
    lastReadProgress = lastReadProgress,
    totalReadingSeconds = totalReadingSeconds,
    category = category,
    series = series,
    tags = tagsText.split(TAG_SEPARATOR).filter(String::isNotBlank)
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
    preferencesJson = preferencesJson,
    sourceFormat = sourceFormat,
    sourceFingerprint = sourceFingerprint,
    lastReadProgress = lastReadProgress,
    totalReadingSeconds = totalReadingSeconds,
    category = category,
    series = series,
    tagsText = tags
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
        .joinToString(TAG_SEPARATOR)
)

private const val TAG_SEPARATOR = "\u001F"
