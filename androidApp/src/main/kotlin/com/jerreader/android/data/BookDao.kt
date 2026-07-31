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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query(
        """
        UPDATE books
        SET locatorJson = :locatorJson,
            lastOpenedAtEpochMillis = :openedAtEpochMillis
        WHERE id = :id
        """
    )
    suspend fun updateReadingProgress(
        id: String,
        locatorJson: String,
        openedAtEpochMillis: Long
    )

    @Query("UPDATE books SET preferencesJson = :preferencesJson WHERE id = :id")
    suspend fun updatePreferences(id: String, preferencesJson: String)
}
