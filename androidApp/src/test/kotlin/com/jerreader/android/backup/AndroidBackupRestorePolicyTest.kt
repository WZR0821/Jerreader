package com.jerreader.android.backup

import com.jerreader.android.data.BookEntity
import com.jerreader.android.reader.AndroidReaderRecordKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidBackupRestorePolicyTest {
    @Test
    fun archiveFileNamesMustBeLeafNames() {
        listOf("../book.epub", "folder/book.epub", "folder\\book.epub", ".", "..", "")
            .forEach { assertFalse(AndroidBackupRestorePolicy.isSafeLeafName(it)) }
        assertTrue(AndroidBackupRestorePolicy.isSafeLeafName("book-1.epub"))
    }

    @Test
    fun restoredBooksRequireSafeNamesAndVerifiableFingerprints() {
        val valid = book()
        assertTrue(AndroidBackupRestorePolicy.isValidBook(valid))
        assertFalse(
            AndroidBackupRestorePolicy.isValidBook(
                valid.copy(publicationFileName = "../../outside.epub")
            )
        )
        assertFalse(
            AndroidBackupRestorePolicy.isValidBook(valid.copy(fingerprint = "not-a-digest"))
        )
        assertFalse(
            AndroidBackupRestorePolicy.isValidBook(valid.copy(fingerprint = "A".repeat(64)))
        )
        assertFalse(AndroidBackupRestorePolicy.isValidBook(valid.copy(fileSize = 0)))
    }

    @Test
    fun archiveVersionsAndBookIdsMustBeUnambiguous() {
        assertTrue(AndroidBackupRestorePolicy.isSupportedArchiveVersion(1))
        assertFalse(AndroidBackupRestorePolicy.isSupportedArchiveVersion(0))
        assertFalse(AndroidBackupRestorePolicy.isSupportedArchiveVersion(2))
        assertTrue(AndroidBackupRestorePolicy.haveUniqueIds(listOf(book(), book().copy(id = "two"))))
        assertFalse(AndroidBackupRestorePolicy.haveUniqueIds(listOf(book(), book())))
    }

    @Test
    fun restoredNamesNeverOverwriteAnOccupiedPublication() {
        val occupied = mutableSetOf("restored.epub")
        assertEquals(
            "restored-1.epub",
            AndroidBackupRestorePolicy.uniqueLeafName("restored", "epub", occupied)
        )
    }

    @Test
    fun readerKeysAreRebuiltForTheMappedBookId() {
        val locator = """{"href":"chapter.xhtml","locations":{"progression":0.42}}"""
        assertEquals(
            "new-book|chapter.xhtml|4200",
            AndroidReaderRecordKeys.bookmark("new-book", locator)
        )
        assertNotEquals(
            AndroidReaderRecordKeys.annotation("old-book", locator, "selected text"),
            AndroidReaderRecordKeys.annotation("new-book", locator, "selected text")
        )
    }

    private fun book() = BookEntity(
        id = "book-id",
        title = "Book",
        author = null,
        language = "en",
        publicationFileName = "book.epub",
        coverFileName = "cover.jpg",
        fingerprint = "a".repeat(64),
        fileSize = 1,
        publicationLastModified = 1,
        importedAtEpochMillis = 1,
        lastOpenedAtEpochMillis = null,
        locatorJson = null,
        preferencesJson = null,
        sourceFingerprint = "b".repeat(64)
    )
}
