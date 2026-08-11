package com.jerreader.android.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "word_lookup_records",
    indices = [
        Index(value = ["lastLookedUpAtEpochMillis"]),
        Index(value = ["sourceBookId"]),
        Index(value = ["nextReviewAtEpochMillis"])
    ]
)
data class WordLookupEntity(
    @PrimaryKey val lookupKey: String,
    val surfaceForm: String,
    val lemma: String?,
    val reading: String?,
    val language: String,
    val partOfSpeech: String?,
    val definitionsText: String,
    val inflectionNote: String?,
    /** Encoded WordExample rows; added because 1.4 only persisted examples on iOS. */
    val examplesText: String = "",
    val usageNote: String?,
    val aiAnalysis: String?,
    val aiProviderIdentifier: String?,
    val sentenceContext: String?,
    val sourceBookId: String?,
    val sourceBookTitle: String?,
    val providerIdentifier: String,
    val lookupCount: Int,
    val createdAtEpochMillis: Long,
    val lastLookedUpAtEpochMillis: Long,
    val isFavorite: Boolean,
    val isInHistory: Boolean,
    /** Stable id from core VocabularyStatus; existing favourites migrate to learning. */
    val vocabularyStatus: String = "new",
    /** Newest-first, bounded source sentences encoded by VocabularyLearningPolicy. */
    val contextHistoryText: String = "",
    val reviewCount: Int = 0,
    val reviewStage: Int = 0,
    val reviewIntervalDays: Int = 0,
    val reviewLapseCount: Int = 0,
    /** Zero means never reviewed and therefore eligible for the new-card queue. */
    val lastReviewedAtEpochMillis: Long = 0,
    val nextReviewAtEpochMillis: Long = 0
)

@Entity(
    tableName = "translation_favorites",
    indices = [
        Index(value = ["updatedAtEpochMillis"]),
        Index(value = ["bookId"])
    ]
)
data class TranslationFavoriteEntity(
    @PrimaryKey val favoriteKey: String,
    val sourceText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val providerIdentifier: String,
    val bookId: String?,
    val bookTitle: String?,
    val locatorJson: String?,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long
)
