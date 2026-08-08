package com.jerreader.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReaderRecordDao {
    @Query("SELECT * FROM reading_bookmarks WHERE bookId = :bookId ORDER BY progress")
    fun observeBookmarks(bookId: String): Flow<List<ReadingBookmarkEntity>>

    @Query("SELECT * FROM reading_bookmarks WHERE bookmarkKey = :key LIMIT 1")
    suspend fun bookmark(key: String): ReadingBookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookmark(record: ReadingBookmarkEntity)

    @Query("DELETE FROM reading_bookmarks WHERE bookmarkKey = :key")
    suspend fun deleteBookmark(key: String)

    @Query("DELETE FROM reading_bookmarks WHERE id = :id")
    suspend fun deleteBookmarkById(id: String)

    @Query("DELETE FROM reading_bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarksByBookId(bookId: String)

    @Query("SELECT * FROM reading_annotations WHERE bookId = :bookId ORDER BY progress")
    fun observeAnnotations(bookId: String): Flow<List<ReadingAnnotationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnnotation(record: ReadingAnnotationEntity)

    @Query("DELETE FROM reading_annotations WHERE id = :id")
    suspend fun deleteAnnotation(id: String)

    @Query("DELETE FROM reading_annotations WHERE bookId = :bookId")
    suspend fun deleteAnnotationsByBookId(bookId: String)

    @Query(
        "UPDATE reading_annotations SET noteText = :noteText, color = :color, " +
            "updatedAtEpochMillis = :updatedAt WHERE id = :id"
    )
    suspend fun updateAnnotation(id: String, noteText: String, color: String, updatedAt: Long)

    @Query("SELECT * FROM reading_bookmarks")
    suspend fun allBookmarks(): List<ReadingBookmarkEntity>

    @Query("SELECT * FROM reading_annotations")
    suspend fun allAnnotations(): List<ReadingAnnotationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookmarksIgnoringExisting(records: List<ReadingBookmarkEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAnnotationsIgnoringExisting(records: List<ReadingAnnotationEntity>): List<Long>
}
