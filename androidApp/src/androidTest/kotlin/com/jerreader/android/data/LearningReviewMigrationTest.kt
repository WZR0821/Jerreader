package com.jerreader.android.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LearningReviewMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        JerreaderDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrationSixToSevenKeepsVocabularyAndStartsReviewUnseen() {
        helper.createDatabase(DATABASE_NAME, 6).apply {
            execSQL(
                """
                INSERT INTO word_lookup_records (
                    lookupKey, surfaceForm, lemma, reading, language,
                    partOfSpeech, definitionsText, inflectionNote, usageNote,
                    aiAnalysis, aiProviderIdentifier, sentenceContext,
                    sourceBookId, sourceBookTitle, providerIdentifier,
                    lookupCount, createdAtEpochMillis, lastLookedUpAtEpochMillis,
                    isFavorite, isInHistory, vocabularyStatus, contextHistoryText
                ) VALUES (
                    'ja|食べる', '食べました', '食べる', 'たべました', 'ja',
                    '动词', '吃', '礼貌体过去式', NULL,
                    NULL, NULL, '寿司を食べました。',
                    NULL, NULL, 'fixture',
                    2, 1000, 2000,
                    1, 1, 'learning', '寿司を食べました。'
                )
                """.trimIndent()
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            7,
            true,
            JerreaderDatabase.MIGRATION_6_7
        )
        migrated.query(
            "SELECT vocabularyStatus, contextHistoryText, examplesText, " +
                "reviewCount, reviewStage, reviewIntervalDays, reviewLapseCount, " +
                "lastReviewedAtEpochMillis, nextReviewAtEpochMillis " +
                "FROM word_lookup_records WHERE lookupKey = 'ja|食べる'"
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("learning", cursor.getString(0))
            assertEquals("寿司を食べました。", cursor.getString(1))
            assertEquals("", cursor.getString(2))
            for (index in 3..8) {
                assertEquals(0L, cursor.getLong(index))
            }
        }
        migrated.close()
    }

    private companion object {
        const val DATABASE_NAME = "learning-review-migration"
    }
}
