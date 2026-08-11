package com.jerreader.android.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jerreader.android.data.BookEntity
import com.jerreader.android.data.JerreaderDatabase
import com.jerreader.android.library.ImmutablePublicationStore
import com.jerreader.android.settings.AndroidAppSettingsStore
import com.jerreader.android.translation.AndroidTranslationSettingsStore
import com.jerreader.unified.library.LibraryBackupPolicy
import com.jerreader.unified.library.LibraryBackupScope
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Restoring a backup onto a fresh installation has to bring the backup regimen
 * with it, or the user has to set the schedule, the limits and the folder up
 * again from memory — which is exactly the state a reinstall leaves them in.
 *
 * This drives the real service against a real archive on the device, so it
 * covers what the JSON round trip in the unit tests cannot: that the policy
 * store actually ends up holding the inherited regimen, and that a folder this
 * installation has no grant for comes back as a suggestion rather than
 * silently failing.
 */
class BackupInheritanceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: JerreaderDatabase
    private lateinit var policyStore: BackupPolicyStore
    private lateinit var service: LibraryBackupService
    private lateinit var publicationStore: ImmutablePublicationStore
    private lateinit var originalPolicy: LibraryBackupPolicy
    private val archives = mutableListOf<File>()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, JerreaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        policyStore = BackupPolicyStore(context)
        originalPolicy = policyStore.policy.value
        publicationStore = ImmutablePublicationStore(context)
        service = LibraryBackupService(
            context = context,
            database = database,
            publicationStore = publicationStore,
            appSettings = AndroidAppSettingsStore(context),
            translationSettings = AndroidTranslationSettingsStore(context),
            directoryStore = BackupDirectoryStore(context),
            policyStore = policyStore
        )
    }

    @After
    fun tearDown() {
        // The policy store is the app's real one, so put back what the device
        // had before this test rewrote it.
        policyStore.update { originalPolicy }
        database.close()
        archives.forEach { it.delete() }
        listOf("existing.epub", "escaped.epub").forEach { name ->
            publicationStore.resolvePublication(name).apply {
                setWritable(true)
                delete()
            }
        }
        File(context.filesDir, "escaped.epub").delete()
    }

    @Test
    fun unsafePublicationNamesAreRejectedBeforeTheyCanEscapeTheLibrary() = runBlocking {
        val escaped = File(context.filesDir, "escaped.epub")
        escaped.delete()
        val bytes = "malicious".encodeToByteArray()
        val archive = writeArchive(
            createdAt = 1_754_000_000_000,
            profile = null,
            scopes = listOf("LIBRARY"),
            extraEntries = mapOf(
                "library/books.json" to booksPayload(
                    bookJson(
                        id = "bad-book",
                        publicationFileName = "../escaped.epub",
                        fingerprint = sha256(bytes),
                        sourceFingerprint = sha256(bytes),
                        fileSize = bytes.size.toLong()
                    )
                ),
                "library/publications/escaped.epub" to bytes
            )
        )

        var failed = false
        try {
            service.restore(Uri.fromFile(archive))
        } catch (_: BackupFailure) {
            failed = true
        }

        assertTrue(failed)
        assertFalse(escaped.exists())
        assertTrue(database.bookDao().allBooks().isEmpty())
    }

    @Test
    fun matchingBookIsNotOverwrittenAndReaderRecordsUseItsLocalId() = runBlocking {
        val originalBytes = "original immutable publication".encodeToByteArray()
        val archivedBytes = "different archived bytes".encodeToByteArray()
        val fingerprint = sha256(originalBytes)
        val existingFile = publicationStore.resolvePublication("existing.epub")
        existingFile.parentFile?.mkdirs()
        existingFile.writeBytes(originalBytes)
        existingFile.setReadOnly()
        database.bookDao().insert(
            BookEntity(
                id = "existing-id",
                title = "Existing",
                author = null,
                language = "en",
                publicationFileName = existingFile.name,
                coverFileName = null,
                fingerprint = fingerprint,
                fileSize = originalBytes.size.toLong(),
                publicationLastModified = existingFile.lastModified(),
                importedAtEpochMillis = 1,
                lastOpenedAtEpochMillis = null,
                locatorJson = null,
                preferencesJson = null,
                sourceFingerprint = "b".repeat(64)
            )
        )
        val locator = """{"href":"chapter.xhtml","locations":{"progression":0.42}}"""
        val reading = JSONObject()
            .put(
                "bookmarks",
                JSONArray().put(
                    JSONObject()
                        .put("id", "old-bookmark-id")
                        .put("bookmarkKey", "archive-id|chapter.xhtml|4200")
                        .put("bookId", "archive-id")
                        .put("bookTitle", "Archived")
                        .put("locatorJson", locator)
                        .put("chapterTitle", "Chapter")
                        .put("progress", 0.42)
                        .put("createdAtEpochMillis", 1)
                )
            )
            .put("annotations", JSONArray())
            .toString()
            .encodeToByteArray()
        val archive = writeArchive(
            createdAt = 1_754_000_000_000,
            profile = null,
            scopes = listOf("LIBRARY", "READING"),
            extraEntries = mapOf(
                "library/books.json" to booksPayload(
                    bookJson(
                        id = "archive-id",
                        publicationFileName = "existing.epub",
                        fingerprint = fingerprint,
                        sourceFingerprint = "b".repeat(64),
                        fileSize = archivedBytes.size.toLong()
                    )
                ),
                "library/publications/existing.epub" to archivedBytes,
                "reading/records.json" to reading
            )
        )

        val result = service.restore(Uri.fromFile(archive))

        assertEquals(0, result.books)
        assertEquals(1, result.bookmarks)
        assertArrayEquals(originalBytes, existingFile.readBytes())
        val bookmark = database.readerRecordDao().allBookmarks().single()
        assertEquals("existing-id", bookmark.bookId)
        assertEquals("existing-id|chapter.xhtml|4200", bookmark.bookmarkKey)
        existingFile.setWritable(true)
        existingFile.delete()
        Unit
    }

    @Test
    fun restoringAnArchiveAdoptsTheRegimenItWasWrittenUnder() = runBlocking {
        policyStore.update {
            LibraryBackupPolicy(
                automaticEnabled = false,
                intervalDays = 7,
                retentionDays = 30,
                maximumBackupCount = 5,
                automaticScopes = setOf(LibraryBackupScope.READING)
            )
        }

        val createdAt = 1_754_000_000_000
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ABackups"
        val archive = writeArchive(
            createdAt = createdAt,
            profile = JSONObject()
                .put("automaticEnabled", true)
                .put("intervalDays", 3)
                .put("retentionDays", 90)
                .put("maximumBackupCount", 10)
                .put("maximumTotalBytes", 500L * 1024 * 1024)
                .put("automaticScopes", org.json.JSONArray(listOf("LIBRARY", "SETTINGS")))
                .put("folderDisplayName", "书房备份")
                .put("folderUri", tree)
        )

        val result = service.restore(Uri.fromFile(archive))

        val policy = policyStore.policy.value
        assertEquals(true, policy.automaticEnabled)
        assertEquals(3, policy.intervalDays)
        assertEquals(90, policy.retentionDays)
        assertEquals(10, policy.maximumBackupCount)
        assertEquals(500L * 1024 * 1024, policy.maximumTotalBytes)
        assertEquals(
            setOf(LibraryBackupScope.LIBRARY, LibraryBackupScope.SETTINGS),
            policy.automaticScopes
        )
        // The inherited schedule continues from the archive rather than firing
        // the moment the restore finishes.
        assertEquals(createdAt, policy.lastAutomaticBackupAtEpochMillis)

        // No grant for that tree exists on a fresh install, so the folder comes
        // back as something to re-grant with one tap, not as a silent failure.
        assertNull(result.adoptedFolderName)
        assertEquals(Uri.parse(tree), result.suggestedFolderUri)
        assertEquals("书房备份", result.suggestedFolderName)
    }

    @Test
    fun anArchiveWithoutARegimenLeavesTheCurrentOneAlone() = runBlocking {
        val mine = LibraryBackupPolicy(
            automaticEnabled = true,
            intervalDays = 14,
            retentionDays = 7,
            maximumBackupCount = 20,
            automaticScopes = setOf(LibraryBackupScope.LEARNING)
        )
        policyStore.update { mine }

        val result = service.restore(
            Uri.fromFile(writeArchive(createdAt = 1_754_000_000_000, profile = null))
        )

        assertNull(result.inheritedProfile)
        assertNull(result.suggestedFolderUri)
        assertEquals(mine.copy(lastAutomaticBackupAtEpochMillis = null), policyStore.policy.value)
    }

    @Test
    fun aRegimenThisBuildCannotHonourFallsBackInsteadOfSticking() = runBlocking {
        val archive = writeArchive(
            createdAt = 1_754_000_000_000,
            profile = JSONObject()
                .put("automaticEnabled", true)
                .put("intervalDays", 2)
                .put("retentionDays", 12)
                .put("maximumBackupCount", 4)
                .put("maximumTotalBytes", 42)
                .put("automaticScopes", org.json.JSONArray(listOf("DREAMS")))
        )

        service.restore(Uri.fromFile(archive))

        val policy = policyStore.policy.value
        assertEquals(7, policy.intervalDays)
        assertEquals(30, policy.retentionDays)
        assertEquals(5, policy.maximumBackupCount)
        assertEquals(2L * 1024 * 1024 * 1024, policy.maximumTotalBytes)
        assertEquals(LibraryBackupPolicy().automaticScopes, policy.automaticScopes)
    }

    /** A minimal but genuine archive: the manifest alone, as the service reads it. */
    private fun writeArchive(
        createdAt: Long,
        profile: JSONObject?,
        scopes: List<String>? = null,
        extraEntries: Map<String, ByteArray> = emptyMap()
    ): File {
        val manifest = JSONObject()
            .put("format", "jerreader.backup")
            .put("formatVersion", 1)
            .put("createdAt", createdAt)
        scopes?.let { manifest.put("scopes", JSONArray(it)) }
        profile?.let { manifest.put("profile", it) }

        val file = File.createTempFile("inheritance-", ".jerbackup.zip", context.cacheDir)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
            extraEntries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        archives.add(file)
        return file
    }

    private fun booksPayload(book: JSONObject): ByteArray = JSONObject()
        .put("books", JSONArray().put(book))
        .toString()
        .encodeToByteArray()

    private fun bookJson(
        id: String,
        publicationFileName: String,
        fingerprint: String,
        sourceFingerprint: String,
        fileSize: Long
    ): JSONObject = JSONObject()
        .put("id", id)
        .put("title", "Archived")
        .put("publicationFileName", publicationFileName)
        .put("fingerprint", fingerprint)
        .put("sourceFingerprint", sourceFingerprint)
        .put("sourceFormat", "epub")
        .put("fileSize", fileSize)
        .put("publicationLastModified", 1)
        .put("importedAtEpochMillis", 1)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
