package com.jerreader.android.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reading_bookmarks",
    indices = [Index(value = ["bookId"]), Index(value = ["bookmarkKey"], unique = true)]
)
data class ReadingBookmarkEntity(
    @PrimaryKey val id: String,
    val bookmarkKey: String,
    val bookId: String,
    val bookTitle: String,
    val locatorJson: String,
    val chapterTitle: String,
    val excerpt: String?,
    val progress: Double,
    val createdAtEpochMillis: Long
)

@Entity(
    tableName = "reading_annotations",
    indices = [Index(value = ["bookId"]), Index(value = ["annotationKey"], unique = true)]
)
data class ReadingAnnotationEntity(
    @PrimaryKey val id: String,
    val annotationKey: String,
    val bookId: String,
    val bookTitle: String,
    val locatorJson: String,
    val selectedText: String,
    val noteText: String,
    val color: String,
    val chapterTitle: String,
    val progress: Double,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val geometryJson: String? = null
)
