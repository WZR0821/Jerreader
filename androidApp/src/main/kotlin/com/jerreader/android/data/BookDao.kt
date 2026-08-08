package com.jerreader.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query(
        """
        SELECT * FROM books
        ORDER BY
            CASE WHEN lastOpenedAtEpochMillis IS NULL THEN 1 ELSE 0 END,
            lastOpenedAtEpochMillis DESC,
            importedAtEpochMillis DESC
        """
    )
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): BookEntity?

    @Query("SELECT * FROM books WHERE sourceFingerprint = :fingerprint LIMIT 1")
    suspend fun findBySourceFingerprint(fingerprint: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query(
        """
        UPDATE books
        SET locatorJson = :locatorJson,
            lastOpenedAtEpochMillis = :openedAtEpochMillis,
            lastReadProgress = :progress
        WHERE id = :id
        """
    )
    suspend fun updateReadingProgress(
        id: String,
        locatorJson: String,
        openedAtEpochMillis: Long,
        progress: Double
    )

    @Query("UPDATE books SET totalReadingSeconds = totalReadingSeconds + :seconds WHERE id = :id")
    suspend fun addReadingTime(id: String, seconds: Double)

    @Query("UPDATE books SET preferencesJson = :preferencesJson WHERE id = :id")
    suspend fun updatePreferences(id: String, preferencesJson: String)

    @Query(
        """
        UPDATE books SET
            title = :title,
            author = :author,
            language = :language,
            category = :category,
            series = :series,
            tagsText = :tagsText
        WHERE id = :id
        """
    )
    suspend fun updateOrganization(
        id: String,
        title: String,
        author: String?,
        language: String?,
        category: String,
        series: String,
        tagsText: String
    )

    @Query("UPDATE books SET coverFileName = :coverFileName WHERE id = :id")
    suspend fun updateCover(id: String, coverFileName: String?)

    // Backup reads and writes the whole table at once. Restoring ignores rows
    // that already exist so re-running a restore cannot duplicate a book.
    @Query("SELECT * FROM books")
    suspend fun allBooks(): List<BookEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringExisting(books: List<BookEntity>): List<Long>
}
