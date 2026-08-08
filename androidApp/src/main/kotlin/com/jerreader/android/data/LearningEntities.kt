package com.jerreader.android.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "word_lookup_records",
    indices = [
        Index(value = ["lastLookedUpAtEpochMillis"]),
        Index(value = ["sourceBookId"])
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
    val isInHistory: Boolean
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
