package com.jerreader.android.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "translation_cache",
    indices = [
        Index(value = ["serviceNamespace"]),
        Index(value = ["lastAccessedAtEpochMillis"])
    ]
)
data class TranslationCacheEntity(
    @PrimaryKey val cacheKey: String,
    val normalizedSourceText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val serviceNamespace: String,
    val translatedText: String,
    val providerIdentifier: String,
    val createdAtEpochMillis: Long,
    val lastAccessedAtEpochMillis: Long
)
