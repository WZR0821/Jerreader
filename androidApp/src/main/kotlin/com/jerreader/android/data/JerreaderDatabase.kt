package com.jerreader.android.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        BookEntity::class,
        WordLookupEntity::class,
        TranslationFavoriteEntity::class,
        ReadingBookmarkEntity::class,
        ReadingAnnotationEntity::class,
        TranslationCacheEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class JerreaderDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun learningDao(): LearningDao
    abstract fun readerRecordDao(): ReaderRecordDao
    abstract fun translationCacheDao(): TranslationCacheDao

    companion object {
        fun build(context: Context): JerreaderDatabase = Room.databaseBuilder(
            context.applicationContext,
            JerreaderDatabase::class.java,
            "jerreader-library.db"
        ).addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5
        ).build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN sourceFormat TEXT NOT NULL DEFAULT 'epub'")
                db.execSQL("ALTER TABLE books ADD COLUMN sourceFingerprint TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN lastReadProgress REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN totalReadingSeconds REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE books ADD COLUMN category TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN series TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN tagsText TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE books SET sourceFingerprint = fingerprint WHERE sourceFingerprint = ''")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_books_sourceFingerprint ON books(sourceFingerprint)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS word_lookup_records (
                        lookupKey TEXT NOT NULL PRIMARY KEY,
                        surfaceForm TEXT NOT NULL,
                        lemma TEXT,
                        reading TEXT,
                        language TEXT NOT NULL,
                        partOfSpeech TEXT,
                        definitionsText TEXT NOT NULL,
                        inflectionNote TEXT,
                        usageNote TEXT,
                        aiAnalysis TEXT,
                        aiProviderIdentifier TEXT,
                        sentenceContext TEXT,
                        sourceBookId TEXT,
                        sourceBookTitle TEXT,
                        providerIdentifier TEXT NOT NULL,
                        lookupCount INTEGER NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        lastLookedUpAtEpochMillis INTEGER NOT NULL,
                        isFavorite INTEGER NOT NULL,
                        isInHistory INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_word_lookup_records_lastLookedUpAtEpochMillis ON word_lookup_records(lastLookedUpAtEpochMillis)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS translation_favorites (
                        favoriteKey TEXT NOT NULL PRIMARY KEY,
                        sourceText TEXT NOT NULL,
                        translatedText TEXT NOT NULL,
                        sourceLanguage TEXT NOT NULL,
                        targetLanguage TEXT NOT NULL,
                        providerIdentifier TEXT NOT NULL,
                        bookId TEXT,
                        bookTitle TEXT,
                        locatorJson TEXT,
                        createdAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_translation_favorites_updatedAtEpochMillis ON translation_favorites(updatedAtEpochMillis)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS reading_bookmarks (
                        id TEXT NOT NULL PRIMARY KEY,
                        bookmarkKey TEXT NOT NULL,
                        bookId TEXT NOT NULL,
                        bookTitle TEXT NOT NULL,
                        locatorJson TEXT NOT NULL,
                        chapterTitle TEXT NOT NULL,
                        excerpt TEXT,
                        progress REAL NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_bookmarks_bookId ON reading_bookmarks(bookId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_reading_bookmarks_bookmarkKey ON reading_bookmarks(bookmarkKey)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS reading_annotations (
                        id TEXT NOT NULL PRIMARY KEY,
                        annotationKey TEXT NOT NULL,
                        bookId TEXT NOT NULL,
                        bookTitle TEXT NOT NULL,
                        locatorJson TEXT NOT NULL,
                        selectedText TEXT NOT NULL,
                        noteText TEXT NOT NULL,
                        color TEXT NOT NULL,
                        chapterTitle TEXT NOT NULL,
                        progress REAL NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_annotations_bookId ON reading_annotations(bookId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_reading_annotations_annotationKey ON reading_annotations(annotationKey)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS translation_cache (
                        cacheKey TEXT NOT NULL PRIMARY KEY,
                        normalizedSourceText TEXT NOT NULL,
                        sourceLanguage TEXT NOT NULL,
                        targetLanguage TEXT NOT NULL,
                        serviceNamespace TEXT NOT NULL,
                        translatedText TEXT NOT NULL,
                        providerIdentifier TEXT NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        lastAccessedAtEpochMillis INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_translation_cache_serviceNamespace ON translation_cache(serviceNamespace)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_translation_cache_lastAccessedAtEpochMillis ON translation_cache(lastAccessedAtEpochMillis)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Book-scoped learning and reader records are deleted explicitly
                // by the repository. Indexes keep that cleanup bounded for large
                // local libraries without changing the imported book bytes.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_word_lookup_records_sourceBookId " +
                        "ON word_lookup_records(sourceBookId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_translation_favorites_bookId " +
                        "ON translation_favorites(bookId)"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE reading_annotations ADD COLUMN geometryJson TEXT"
                )
            }
        }
    }
}
