package com.jerreader.android.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jerreader.android.data.JerreaderDatabase
import com.jerreader.android.data.BookEntity
import com.jerreader.android.library.ImmutablePublicationStore
import com.jerreader.android.library.PublicationIntegrity
import com.jerreader.android.settings.AndroidAppSettingsStore
import com.jerreader.android.translation.AndroidTranslationSettingsStore
import com.jerreader.shared.library.LibraryBackupPolicy
import com.jerreader.shared.library.LibraryBackupScope
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
    private lateinit var originalPolicy: LibraryBackupPolicy
    private lateinit var publicationStore: ImmutablePublicationStore
    private lateinit var publicationRoot: File
    private val archives = mutableListOf<File>()

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, JerreaderDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        policyStore = BackupPolicyStore(context)
        originalPolicy = policyStore.policy.value
        publicationRoot = File(context.cacheDir, "backup-test-${System.nanoTime()}").apply { mkdirs() }
        publicationStore = ImmutablePublicationStore(context, publicationRoot)
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
        publicationRoot.deleteRecursively()
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

    @Test
    fun invalidManifestAfterReadingPayloadDoesNotWriteBookmarks() = runBlocking {
        val reading = JSONObject().put("bookmarks", org.json.JSONArray().put(
            JSONObject().put("id", "must-not-be-inserted").put("bookmarkKey", "invalid-archive")
        ))
        val archive = writeEntries(listOf(
            "reading/records.json" to reading.toString().toByteArray(),
            "manifest.json" to JSONObject().put("format", "another-app").toString().toByteArray()
        ))
        var rejected = false
        try { service.restore(Uri.fromFile(archive)) } catch (_: BackupFailure) { rejected = true }
        assertTrue("An invalid archive must be rejected", rejected)
        assertTrue("No payload may be written before validating the whole archive", database.readerRecordDao().allBookmarks().isEmpty())
        assertEquals(originalPolicy, policyStore.policy.value)
    }

    @Test
    fun restoringAnExistingBookPreservesItsBytesMtimeAndNewerProgress() = runBlocking {
        val source = publicationStore.resolvePublication("existing.epub").apply {
            parentFile!!.mkdirs(); writeText("immutable publication bytes"); setLastModified(1_700_000_000_000); setReadOnly()
        }
        val snapshot = PublicationIntegrity.capture(source)
        val current = BookEntity(id = "existing", title = "Current", author = null, language = "ja",
            publicationFileName = source.name, coverFileName = null, fingerprint = snapshot.sha256,
            fileSize = source.length(), publicationLastModified = source.lastModified(), importedAtEpochMillis = 1,
            lastOpenedAtEpochMillis = 20, locatorJson = "newer-locator", preferencesJson = null, lastReadProgress = 0.8)
        database.bookDao().insertIgnoringExisting(listOf(current))
        val old = JSONObject().put("id", current.id).put("title", "Old")
            .put("publicationFileName", source.name).put("fingerprint", snapshot.sha256)
            .put("sourceFingerprint", snapshot.sha256).put("publicationLastModified", 1L)
            .put("locatorJson", "older-locator").put("lastReadProgress", 0.1)
        val archive = writeEntries(listOf(
            "manifest.json" to validManifest(100).toString().toByteArray(),
            "library/books.json" to JSONObject().put("books", org.json.JSONArray().put(old)).toString().toByteArray(),
            "library/publications/${source.name}" to source.readBytes()
        ))
        val result = service.restore(Uri.fromFile(archive))
        assertEquals(0, result.books)
        assertTrue(PublicationIntegrity.isUnchanged(snapshot))
        val restored = database.bookDao().findByFingerprint(snapshot.sha256)!!
        assertEquals("newer-locator", restored.locatorJson)
        assertEquals(0.8, restored.lastReadProgress, 0.0)
    }

    @Test
    fun backupListingUsesManifestTimeAfterAProviderChangesTheMtime() {
        val archive = writeEntries(listOf("manifest.json" to validManifest(100).toString().toByteArray()))
        val info = service.inspectBackupArchive(Uri.fromFile(archive), archive.name, 999, archive.length())
        assertEquals(100L, info?.createdAtEpochMillis)
    }

    @Test
    fun aZipWithABackupNameButAnUnrelatedManifestIsNotListed() {
        val archive = writeEntries(listOf("manifest.json" to "{\"format\":\"unrelated\"}".toByteArray()))
        assertNull(service.inspectBackupArchive(Uri.fromFile(archive), archive.name, 999, archive.length()))
    }

    @Test
    fun clearedLastRunStaysClearedAfterReopeningThePolicyStore() {
        policyStore.recordAutomaticRun(100)
        policyStore.update { it.copy(lastAutomaticBackupAtEpochMillis = null) }
        assertNull(BackupPolicyStore(context).policy.value.lastAutomaticBackupAtEpochMillis)
    }

    @Test
    fun interfaceOptionsRestoreAndSurviveAnOlderSettingsPayload() = runBlocking {
        val original = AndroidAppSettingsStore(context).preferences.value
        try {
            val settings = JSONObject().put("topBarTransparency", 0.35).put("bottomBarTransparency", 0.75)
                .put("japaneseFuriganaEnabled", true).put("immersiveReading", false)
                .put("layoutMode", "immersive")
            val archive = writeEntries(listOf("manifest.json" to validManifest(100).toString().toByteArray(),
                "settings/preferences.json" to settings.toString().toByteArray()))
            service.restore(Uri.fromFile(archive))
            fun assertOptions() {
                val loaded = AndroidAppSettingsStore(context).preferences.value
                assertEquals(0.35f, loaded.topBarTransparency, 0.0001f)
                assertEquals(0.75f, loaded.bottomBarTransparency, 0.0001f)
                assertTrue(loaded.japaneseFuriganaEnabled)
                assertEquals(false, loaded.immersiveReading)
                assertEquals(com.jerreader.shared.library.ReaderLayoutMode.IMMERSIVE, loaded.layoutMode)
            }
            assertOptions()
            val old = writeEntries(listOf("manifest.json" to validManifest(200).toString().toByteArray(),
                "settings/preferences.json" to "{}".toByteArray()))
            service.restore(Uri.fromFile(old))
            assertOptions()
        } finally {
            AndroidAppSettingsStore(context).apply {
                updateTopBarTransparency(original.topBarTransparency)
                updateBottomBarTransparency(original.bottomBarTransparency)
                updateJapaneseFurigana(original.japaneseFuriganaEnabled)
                updateImmersiveReading(original.immersiveReading)
                updateLayoutMode(original.layoutMode)
            }
        }
    }

    private fun validManifest(createdAt: Long) = JSONObject().put("format", "jerreader.backup")
        .put("formatVersion", 1).put("createdAt", createdAt)

    private fun writeEntries(entries: List<Pair<String, ByteArray>>): File {
        val file = File.createTempFile("backup-regression-", ".jerbackup.zip", context.cacheDir)
        archives.add(file)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        return file
    }

    /** A minimal but genuine archive: the manifest alone, as the service reads it. */
    private fun writeArchive(createdAt: Long, profile: JSONObject?): File {
        val manifest = JSONObject()
            .put("format", "jerreader.backup")
            .put("formatVersion", 1)
            .put("createdAt", createdAt)
        profile?.let { manifest.put("profile", it) }

        val file = File.createTempFile("inheritance-", ".jerbackup.zip", context.cacheDir)
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()
        }
        archives.add(file)
        return file
    }
}
