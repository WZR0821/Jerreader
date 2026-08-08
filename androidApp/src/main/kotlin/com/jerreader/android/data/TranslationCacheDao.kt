package com.jerreader.android.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TranslationCacheDao {
    @Query("SELECT * FROM translation_cache WHERE cacheKey = :key LIMIT 1")
    suspend fun cache(key: String): TranslationCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: TranslationCacheEntity)

    @Query(
        "UPDATE translation_cache SET lastAccessedAtEpochMillis = :accessedAt " +
            "WHERE cacheKey = :key"
    )
    suspend fun touch(key: String, accessedAt: Long)

    @Query("DELETE FROM translation_cache WHERE cacheKey = :key")
    suspend fun delete(key: String)
}
