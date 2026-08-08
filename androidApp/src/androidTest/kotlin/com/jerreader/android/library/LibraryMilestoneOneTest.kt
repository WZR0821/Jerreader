package com.jerreader.android.library

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jerreader.android.data.JerreaderDatabase
import com.jerreader.android.data.ReadingAnnotationEntity
import com.jerreader.android.data.ReadingBookmarkEntity
import com.jerreader.android.data.RoomLibraryRepository
import com.jerreader.android.data.TranslationFavoriteEntity
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.android.reader.ReadiumEnvironment
import com.jerreader.android.test.createSyntheticEpub
import com.jerreader.shared.library.LibraryBook
import com.jerreader.shared.library.LibraryImportOutcome
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMilestoneOneTest {
    @Test
    fun importDuplicateDetectionAndDeletePreserveSourceAndStoredPublication() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testId = UUID.randomUUID().toString()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            JerreaderDatabase::class.java
        ).build()
        val repository = RoomLibraryRepository(database.bookDao(), database)
        val root = File(context.cacheDir, "jerreader-m1-$testId").apply { mkdirs() }
        val source = File(root, "m1-source.epub")
        createSyntheticEpub(source, identifier = testId)
        source.setLastModified(1_700_100_000_000)
        val sourceSnapshot = PublicationIntegrity.capture(source)
        val store = ImmutablePublicationStore(context, root)
        val importer = LibraryImportService(
            store = store,
            readium = ReadiumEnvironment(context),
            repository = repository,
            now = { 1_700_200_000_000 }
        )
        val bookService = LibraryBookService(store, repository)

        try {
            val imported = importer.importEpub(Uri.fromFile(source))
            assertTrue(imported is LibraryImportOutcome.Imported)
            val book = imported.book
            assertEquals("Jerreader 合成测试书", book.title)
            assertEquals("读鼠测试", book.author)
            assertEquals("zh-CN", book.language)
            val coverFileName = checkNotNull(book.coverFileName)
            assertTrue(store.resolveCover(coverFileName).isFile)

            val storedFile = store.resolvePublication(book.publicationFileName)
            assertTrue(storedFile.isFile)
            val storedSnapshot = PublicationIntegrity.capture(storedFile)
            assertEquals(sourceSnapshot.sha256, storedSnapshot.sha256)
            assertTrue(PublicationIntegrity.isUnchanged(sourceSnapshot))

            database.readerRecordDao().upsertBookmark(
                ReadingBookmarkEntity(
                    id = "bookmark-$testId",
                    bookmarkKey = "bookmark-key-$testId",
                    bookId = book.id,
                    bookTitle = book.title,
                    locatorJson = "{}",
                    chapterTitle = "测试章节",
                    excerpt = "摘录",
                    progress = 0.2,
                    createdAtEpochMillis = 1_700_200_000_000
                )
            )
            database.readerRecordDao().upsertAnnotation(
                ReadingAnnotationEntity(
                    id = "annotation-$testId",
                    annotationKey = "annotation-key-$testId",
                    bookId = book.id,
                    bookTitle = book.title,
                    locatorJson = "{}",
                    selectedText = "批注",
                    noteText = "备注",
                    color = "yellow",
                    chapterTitle = "测试章节",
                    progress = 0.2,
                    createdAtEpochMillis = 1_700_200_000_000,
                    updatedAtEpochMillis = 1_700_200_000_000,
                    geometryJson = "[{\"x\":0.1,\"y\":0.1,\"w\":0.2,\"h\":0.1}]"
                )
            )
            database.learningDao().upsertWord(
                WordLookupEntity(
                    lookupKey = "word-$testId",
                    surfaceForm = "word",
                    lemma = "word",
                    reading = null,
                    language = "ENGLISH",
                    partOfSpeech = "noun",
                    definitionsText = "词",
                    inflectionNote = null,
                    usageNote = null,
                    aiAnalysis = null,
                    aiProviderIdentifier = null,
                    sentenceContext = null,
                    sourceBookId = book.id,
                    sourceBookTitle = book.title,
                    providerIdentifier = "test",
                    lookupCount = 1,
                    createdAtEpochMillis = 1_700_200_000_000,
                    lastLookedUpAtEpochMillis = 1_700_200_000_000,
                    isFavorite = true,
                    isInHistory = true
                )
            )
            database.learningDao().upsertTranslationFavorite(
                TranslationFavoriteEntity(
                    favoriteKey = "favorite-$testId",
                    sourceText = "source",
                    translatedText = "译文",
                    sourceLanguage = "ENGLISH",
                    targetLanguage = "CHINESE_SIMPLIFIED",
                    providerIdentifier = "test",
                    bookId = book.id,
                    bookTitle = book.title,
                    locatorJson = "{}",
                    createdAtEpochMillis = 1_700_200_000_000,
                    updatedAtEpochMillis = 1_700_200_000_000
                )
            )

            val duplicate = importer.importEpub(Uri.fromFile(source))
            assertTrue(duplicate is LibraryImportOutcome.AlreadyImported)
            assertEquals(book.id, duplicate.book.id)

            bookService.delete(book.id)
            assertNull(repository.book(book.id))
            assertTrue(database.readerRecordDao().observeBookmarks(book.id).first().isEmpty())
            assertTrue(database.readerRecordDao().observeAnnotations(book.id).first().isEmpty())
            assertTrue(database.learningDao().word("word-$testId") == null)
            assertTrue(database.learningDao().observeTranslationFavorites().first().isEmpty())
            assertFalse(storedFile.exists())
            assertTrue(PublicationIntegrity.isUnchanged(sourceSnapshot))
        } finally {
            database.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun roomLibraryRecordSurvivesDatabaseReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "jerreader-m1-${UUID.randomUUID()}.db"
        context.deleteDatabase(databaseName)
        val expected = testBook()

        try {
            Room.databaseBuilder(context, JerreaderDatabase::class.java, databaseName)
                .build()
                .also { database ->
                    RoomLibraryRepository(database.bookDao()).add(expected)
                    database.close()
                }

            Room.databaseBuilder(context, JerreaderDatabase::class.java, databaseName)
                .build()
                .also { reopened ->
                    assertEquals(
                        expected,
                        RoomLibraryRepository(reopened.bookDao()).book(expected.id)
                    )
                    reopened.close()
                }
            Unit
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun testBook(): LibraryBook = LibraryBook(
        id = UUID.randomUUID().toString(),
        title = "Jerreader Room Test",
        author = "Jerreader",
        language = "zh-CN",
        publicationFileName = "test.epub",
        coverFileName = null,
        fingerprint = "a".repeat(64),
        fileSize = 42,
        publicationLastModified = 1_700_000_000_000,
        importedAtEpochMillis = 1_700_000_000_001,
        lastOpenedAtEpochMillis = null,
        locatorJson = null,
        preferencesJson = null
    )
}
