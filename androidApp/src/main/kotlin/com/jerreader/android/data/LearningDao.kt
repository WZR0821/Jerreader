package com.jerreader.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LearningDao {
    @Query("SELECT * FROM word_lookup_records ORDER BY lastLookedUpAtEpochMillis DESC")
    fun observeWords(): Flow<List<WordLookupEntity>>

    @Query("SELECT * FROM word_lookup_records WHERE lookupKey = :key LIMIT 1")
    suspend fun word(key: String): WordLookupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWord(record: WordLookupEntity)

    @Query(
        "UPDATE word_lookup_records SET isFavorite = :favorite, " +
            "vocabularyStatus = :status WHERE lookupKey = :key"
    )
    suspend fun setWordFavorite(key: String, favorite: Boolean, status: String)

    @Query(
        "UPDATE word_lookup_records SET isFavorite = 1, vocabularyStatus = :status " +
            "WHERE lookupKey = :key"
    )
    suspend fun setVocabularyStatus(key: String, status: String)

    @Query(
        "UPDATE word_lookup_records SET vocabularyStatus = :status, " +
            "reviewCount = :reviewCount, reviewStage = :reviewStage, " +
            "reviewIntervalDays = :intervalDays, reviewLapseCount = :lapseCount, " +
            "lastReviewedAtEpochMillis = :lastReviewedAt, " +
            "nextReviewAtEpochMillis = :nextReviewAt WHERE lookupKey = :key"
    )
    suspend fun updateReviewState(
        key: String,
        status: String,
        reviewCount: Int,
        reviewStage: Int,
        intervalDays: Int,
        lapseCount: Int,
        lastReviewedAt: Long,
        nextReviewAt: Long
    )

    @Query("UPDATE word_lookup_records SET aiAnalysis = :analysis, aiProviderIdentifier = :provider WHERE lookupKey = :key")
    suspend fun updateWordAnalysis(key: String, analysis: String, provider: String)

    @Query("UPDATE word_lookup_records SET isInHistory = 0 WHERE lookupKey = :key")
    suspend fun removeWordFromHistory(key: String)

    @Query("DELETE FROM word_lookup_records WHERE isFavorite = 0 AND isInHistory = 0")
    suspend fun removeOrphanedWords()

    @Query("UPDATE word_lookup_records SET isInHistory = 0 WHERE isFavorite = 1")
    suspend fun retainFavoritesOnlyInHistoryClear()

    @Query("DELETE FROM word_lookup_records WHERE isFavorite = 0")
    suspend fun deleteUnfavoritedWords()

    @Query("DELETE FROM word_lookup_records WHERE sourceBookId = :bookId")
    suspend fun deleteWordsByBookId(bookId: String)

    @Query("SELECT * FROM translation_favorites ORDER BY updatedAtEpochMillis DESC")
    fun observeTranslationFavorites(): Flow<List<TranslationFavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTranslationFavorite(record: TranslationFavoriteEntity)

    @Query("DELETE FROM translation_favorites WHERE favoriteKey = :key")
    suspend fun deleteTranslationFavorite(key: String)

    @Query("DELETE FROM translation_favorites WHERE bookId = :bookId")
    suspend fun deleteTranslationFavoritesByBookId(bookId: String)

    @Query("SELECT * FROM word_lookup_records")
    suspend fun allWords(): List<WordLookupEntity>

    @Query("SELECT * FROM translation_favorites")
    suspend fun allTranslationFavorites(): List<TranslationFavoriteEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWordsIgnoringExisting(records: List<WordLookupEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTranslationFavoritesIgnoringExisting(
        records: List<TranslationFavoriteEntity>
    ): List<Long>
}
