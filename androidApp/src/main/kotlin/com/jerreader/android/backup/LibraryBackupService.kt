package com.jerreader.android.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.jerreader.android.data.BookEntity
import com.jerreader.android.data.JerreaderDatabase
import com.jerreader.android.data.ReadingAnnotationEntity
import com.jerreader.android.data.ReadingBookmarkEntity
import com.jerreader.android.data.TranslationFavoriteEntity
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.android.library.ImmutablePublicationStore
import com.jerreader.android.library.PublicationIntegrity
import com.jerreader.android.reader.AndroidReaderRecordKeys
import com.jerreader.android.settings.AndroidAppSettingsStore
import com.jerreader.android.translation.AndroidTranslationSettingsStore
import com.jerreader.unified.library.LibraryBackupArchiveInfo
import com.jerreader.unified.library.LibraryBackupNaming
import com.jerreader.unified.library.LibraryBackupPolicy
import com.jerreader.unified.library.LibraryBackupProfile
import com.jerreader.unified.library.LibraryBackupPruner
import com.jerreader.unified.library.LibraryBackupScope
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class BackupFailure(message: String) : Exception(message)

data class BackupResult(
    val archiveName: String,
    val sizeBytes: Long,
    val removedCount: Int,
    val isOverLimit: Boolean
)

data class RestoreResult(
    val books: Int,
    val bookmarks: Int,
    val annotations: Int,
    val words: Int,
    val translationFavorites: Int,
    val restoredSettings: Boolean,
    val skippedExisting: Int,
    /** The regimen the archive carried, already applied. */
    val inheritedProfile: LibraryBackupProfile? = null,
    /** Set when the backup folder the archive named was taken over silently. */
    val adoptedFolderName: String? = null,
    /**
     * The folder the archive came from, when it could not be taken over: the
     * UI offers it to the user as a one-tap re-grant.
     */
    val suggestedFolderUri: Uri? = null,
    val suggestedFolderName: String? = null
) {
    val isEmpty: Boolean
        get() = books == 0 && bookmarks == 0 && annotations == 0 &&
            words == 0 && translationFavorites == 0 && !restoredSettings
}

/**
 * Writes and reads Jerreader backup archives in a folder the user picked.
 *
 * The archive is a plain zip holding one JSON file per scope plus, for the
 * library scope, the publication and cover files themselves. API keys, proxy
 * credentials, the folder grant and the regenerable translation cache are
 * deliberately never written: a backup should be safe to keep in a shared
 * cloud folder.
 */
class LibraryBackupService(
    private val context: Context,
    private val database: JerreaderDatabase,
    private val publicationStore: ImmutablePublicationStore,
    private val appSettings: AndroidAppSettingsStore,
    private val translationSettings: AndroidTranslationSettingsStore,
    private val directoryStore: BackupDirectoryStore,
    private val policyStore: BackupPolicyStore
) {
    /** One archive operation at a time: two writers would fight over pruning. */
    private val lock = Mutex()

    suspend fun backup(
        scopes: Set<LibraryBackupScope>,
        nowEpochMillis: Long = System.currentTimeMillis()
    ): BackupResult = lock.withLock {
        withContext(Dispatchers.IO) {
            if (scopes.isEmpty()) throw BackupFailure("请至少选择一项要备份的内容。")
            val directory = directoryStore.directory()
                ?: throw BackupFailure("备份文件夹不可用，请重新选择文件夹。")

            val name = "Jerreader-${timestamp(nowEpochMillis)}.jerbackup.zip"
            val target = directory.createFile("application/zip", name)
                ?: throw BackupFailure("备份文件没有生成成功，请重试。")

            try {
                context.contentResolver.openOutputStream(target.uri)?.use { output ->
                    ZipOutputStream(output.buffered()).use { zip ->
                        writeArchive(zip, scopes, nowEpochMillis)
                    }
                } ?: throw BackupFailure("备份文件没有生成成功，请重试。")
            } catch (failure: BackupFailure) {
                target.delete()
                throw failure
            } catch (error: Exception) {
                target.delete()
                throw BackupFailure(error.message ?: "备份没有完成，请稍后重试。")
            }

            val plan = LibraryBackupPruner.plan(
                backups = listBackups(directory),
                policy = policyStore.policy.value,
                nowEpochMillis = nowEpochMillis
            )
            plan.removed.forEach { info ->
                directory.findFile(info.name)?.delete()
            }
            BackupResult(
                archiveName = name,
                sizeBytes = target.length(),
                removedCount = plan.removed.size,
                isOverLimit = plan.isOverLimit
            )
        }
    }

    suspend fun listBackups(): List<LibraryBackupArchiveInfo> = withContext(Dispatchers.IO) {
        val directory = directoryStore.directory() ?: return@withContext emptyList()
        listBackups(directory)
    }

    suspend fun deleteBackup(name: String) = withContext(Dispatchers.IO) {
        val directory = directoryStore.directory()
            ?: throw BackupFailure("备份文件夹不可用，请重新选择文件夹。")
        val removed = directory.findFile(name)?.delete() ?: false
        if (!removed) throw BackupFailure("删除没有完成，请稍后重试。")
    }

    /** Runs an automatic backup when the schedule says one is due. */
    suspend fun backupIfDue(nowEpochMillis: Long = System.currentTimeMillis()): BackupResult? {
        val policy = policyStore.policy.value
        if (!policy.isDue(nowEpochMillis)) return null
        if (directoryStore.directory() == null) return null
        val result = backup(policy.automaticScopes, nowEpochMillis)
        policyStore.recordAutomaticRun(nowEpochMillis)
        return result
    }

    suspend fun restore(source: Uri): RestoreResult = lock.withLock {
        withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openInputStream(source)
                ?: throw BackupFailure("无法读取这个备份文件，请重新选择。")
            stream.use { input ->
                try {
                    readArchive(input)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: BackupFailure) {
                    throw error
                } catch (_: Exception) {
                    throw BackupFailure("这个文件不是有效的 Jerreader 备份，或者已经损坏。")
                }
            }
        }
    }

    /** Restores an archive that is already in the selected folder, by name. */
    suspend fun restore(archiveName: String): RestoreResult {
        val uri = withContext(Dispatchers.IO) {
            val directory = directoryStore.directory()
                ?: throw BackupFailure("备份文件夹不可用，请重新选择文件夹。")
            directory.findFile(archiveName)?.uri
                ?: throw BackupFailure("这份备份已经不在当前文件夹里了。")
        }
        return restore(uri)
    }

    // MARK: - Writing

    private suspend fun writeArchive(
        zip: ZipOutputStream,
        scopes: Set<LibraryBackupScope>,
        nowEpochMillis: Long
    ) {
        val manifest = JSONObject()
            .put("format", ARCHIVE_FORMAT)
            .put("formatVersion", ARCHIVE_VERSION)
            .put("createdAt", nowEpochMillis)
            .put("appVersion", appVersionName())
            .put("scopes", JSONArray(scopes.map { it.name }))
            // Written whatever the scopes are: a reinstall needs the backup
            // regimen even when the user chose not to carry preferences over.
            .put("profile", profileJson())
        zip.writeJson(ENTRY_MANIFEST, manifest)

        if (LibraryBackupScope.LIBRARY in scopes) {
            val books = database.bookDao().allBooks()
            zip.writeJson(ENTRY_BOOKS, JSONObject().put("books", JSONArray(books.map(::bookJson))))
            books.forEach { book ->
                val publication = publicationStore.resolvePublication(book.publicationFileName)
                if (publication.isFile) {
                    zip.writeFile("$DIRECTORY_PUBLICATIONS${book.publicationFileName}", publication)
                }
                book.coverFileName?.let { coverName ->
                    val cover = publicationStore.resolveCover(coverName)
                    if (cover.isFile) zip.writeFile("$DIRECTORY_COVERS$coverName", cover)
                }
            }
        }

        if (LibraryBackupScope.READING in scopes) {
            val dao = database.readerRecordDao()
            zip.writeJson(
                ENTRY_READING,
                JSONObject()
                    .put("bookmarks", JSONArray(dao.allBookmarks().map(::bookmarkJson)))
                    .put("annotations", JSONArray(dao.allAnnotations().map(::annotationJson)))
            )
        }

        if (LibraryBackupScope.LEARNING in scopes) {
            val dao = database.learningDao()
            zip.writeJson(
                ENTRY_LEARNING,
                JSONObject()
                    .put("words", JSONArray(dao.allWords().map(::wordJson)))
                    .put("favorites", JSONArray(dao.allTranslationFavorites().map(::favoriteJson)))
            )
        }

        if (LibraryBackupScope.SETTINGS in scopes) {
            zip.writeJson(ENTRY_SETTINGS, settingsJson())
        }
    }

    // MARK: - The regimen that travels with the archive

    private fun profileJson(): JSONObject {
        val folder = directoryStore.folder.value
        return backupProfileJson(
            LibraryBackupProfile(
                policy = policyStore.policy.value,
                // A label and a URI, never the grant itself.
                folderDisplayName = folder?.displayName,
                folderUri = folder?.treeUri?.toString()
            )
        )
    }

    /**
     * Continues backing up where the archive came from.
     *
     * Taking the folder over silently only works when this installation
     * already holds that grant. A folder from a previous installation or
     * another device has to be re-granted by the user, so it comes back as a
     * suggestion the backup centre can turn into a single confirmation.
     */
    private fun inheritFolder(
        profile: LibraryBackupProfile
    ): Triple<String?, Uri?, String?> {
        val raw = profile.folderUri ?: return Triple(null, null, null)
        val treeUri = runCatching { Uri.parse(raw) }.getOrNull()
            ?: return Triple(null, null, null)
        val name = profile.folderDisplayName ?: treeUri.lastPathSegment ?: "备份文件夹"
        // Restoring a backup out of the folder already in use says nothing new.
        if (directoryStore.folder.value?.treeUri == treeUri) {
            return Triple(null, null, null)
        }
        directoryStore.adoptIfPermitted(treeUri)?.let {
            return Triple(it.displayName, null, null)
        }
        return Triple(null, treeUri, name)
    }

    // MARK: - Reading

    private suspend fun readArchive(stream: InputStream): RestoreResult {
        var manifest: JSONObject? = null
        var booksPayload: JSONObject? = null
        var readingPayload: JSONObject? = null
        var learningPayload: JSONObject? = null
        var settingsPayload: JSONObject? = null
        var extractedBytes = 0L
        val seenEntries = mutableSetOf<String>()

        // Nothing is written to Room, SharedPreferences or the immutable
        // publication store until the whole archive has been parsed and
        // validated. Zip entries may arrive in any order.
        val stagedFiles = mutableMapOf<String, File>()
        val stagingDirectory = File(context.cacheDir, "backup-restore-${System.nanoTime()}")
        check(stagingDirectory.mkdirs()) { "无法准备备份恢复。" }

        try {
            ZipInputStream(stream.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!seenEntries.add(name) || entry.isDirectory) {
                        throw BackupFailure("这个备份包含重复或无效的文件条目。")
                    }
                    when {
                        name == ENTRY_MANIFEST -> {
                            val bytes = zip.readEntryBytes(
                                AndroidBackupRestorePolicy.MAXIMUM_METADATA_BYTES
                            )
                            extractedBytes = checkedRestoreBytes(extractedBytes, bytes.size.toLong())
                            manifest = JSONObject(bytes.toString(Charsets.UTF_8))
                        }

                        name == ENTRY_BOOKS -> {
                            val bytes = zip.readEntryBytes(
                                AndroidBackupRestorePolicy.MAXIMUM_METADATA_BYTES
                            )
                            extractedBytes = checkedRestoreBytes(extractedBytes, bytes.size.toLong())
                            booksPayload = JSONObject(bytes.toString(Charsets.UTF_8))
                        }

                        name == ENTRY_READING -> {
                            val bytes = zip.readEntryBytes(
                                AndroidBackupRestorePolicy.MAXIMUM_METADATA_BYTES
                            )
                            extractedBytes = checkedRestoreBytes(extractedBytes, bytes.size.toLong())
                            readingPayload = JSONObject(bytes.toString(Charsets.UTF_8))
                        }

                        name == ENTRY_LEARNING -> {
                            val bytes = zip.readEntryBytes(
                                AndroidBackupRestorePolicy.MAXIMUM_METADATA_BYTES
                            )
                            extractedBytes = checkedRestoreBytes(extractedBytes, bytes.size.toLong())
                            learningPayload = JSONObject(bytes.toString(Charsets.UTF_8))
                        }

                        name == ENTRY_SETTINGS -> {
                            val bytes = zip.readEntryBytes(
                                AndroidBackupRestorePolicy.MAXIMUM_METADATA_BYTES
                            )
                            extractedBytes = checkedRestoreBytes(extractedBytes, bytes.size.toLong())
                            settingsPayload = JSONObject(bytes.toString(Charsets.UTF_8))
                        }

                        name.startsWith(DIRECTORY_PUBLICATIONS) -> {
                            val leafName = name.removePrefix(DIRECTORY_PUBLICATIONS)
                            if (!AndroidBackupRestorePolicy.isSafeLeafName(leafName)) {
                                throw BackupFailure("这个备份包含不安全的书籍文件名。")
                            }
                            val staged = File(stagingDirectory, UUID.randomUUID().toString())
                            val count = zip.copyEntryTo(
                                staged,
                                AndroidBackupRestorePolicy.MAXIMUM_PUBLICATION_BYTES
                            )
                            extractedBytes = checkedRestoreBytes(extractedBytes, count)
                            stagedFiles[name] = staged
                        }

                        name.startsWith(DIRECTORY_COVERS) -> {
                            val leafName = name.removePrefix(DIRECTORY_COVERS)
                            if (!AndroidBackupRestorePolicy.isSafeLeafName(leafName)) {
                                throw BackupFailure("这个备份包含不安全的封面文件名。")
                            }
                            val staged = File(stagingDirectory, UUID.randomUUID().toString())
                            val count = zip.copyEntryTo(
                                staged,
                                AndroidBackupRestorePolicy.MAXIMUM_COVER_BYTES
                            )
                            extractedBytes = checkedRestoreBytes(extractedBytes, count)
                            stagedFiles[name] = staged
                        }

                        else -> throw BackupFailure("这个备份包含无法识别的文件条目。")
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val resolvedManifest = manifest ?: run {
                throw BackupFailure("这个文件不是有效的 Jerreader 备份，或者已经损坏。")
            }
            if (resolvedManifest.optString("format") != ARCHIVE_FORMAT) {
                throw BackupFailure("这个文件不是有效的 Jerreader 备份，或者已经损坏。")
            }
            val archiveVersion = resolvedManifest.optInt("formatVersion", ARCHIVE_VERSION)
            if (!AndroidBackupRestorePolicy.isSupportedArchiveVersion(archiveVersion)) {
                if (archiveVersion > ARCHIVE_VERSION) {
                    throw BackupFailure("这个备份来自更新版本的 Jerreader，请先升级 App。")
                }
                throw BackupFailure("这个文件不是有效的 Jerreader 备份，或者已经损坏。")
            }

            val scopes = validateArchiveScopes(
                manifest = resolvedManifest,
                hasBooks = booksPayload != null,
                hasReading = readingPayload != null,
                hasLearning = learningPayload != null,
                hasSettings = settingsPayload != null
            )
            val pendingBooks = booksPayload?.optJSONArray("books")
                .toObjectList()
                .map(::bookEntity)
                .orEmpty()
            val pendingBookmarks = readingPayload?.optJSONArray("bookmarks")
                .toObjectList()
                .map(::bookmarkEntity)
                .orEmpty()
            val pendingAnnotations = readingPayload?.optJSONArray("annotations")
                .toObjectList()
                .map(::annotationEntity)
                .orEmpty()
            val pendingWords = learningPayload?.optJSONArray("words")
                .toObjectList()
                .map(::wordEntity)
                .orEmpty()
            val pendingFavorites = learningPayload?.optJSONArray("favorites")
                .toObjectList()
                .map(::favoriteEntity)
                .orEmpty()
            val totalRecords = pendingBooks.size.toLong() + pendingBookmarks.size +
                pendingAnnotations.size + pendingWords.size + pendingFavorites.size
            if (totalRecords > AndroidBackupRestorePolicy.MAXIMUM_RECORD_COUNT ||
                !AndroidBackupRestorePolicy.haveUniqueIds(pendingBooks) ||
                pendingBooks.any { !AndroidBackupRestorePolicy.isValidBook(it) }
            ) {
                throw BackupFailure("这个备份包含无效或过多的记录。")
            }

            val restored = restoreValidatedPayload(
                scopes = scopes,
                books = pendingBooks,
                bookmarks = pendingBookmarks,
                annotations = pendingAnnotations,
                words = pendingWords,
                favorites = pendingFavorites,
                stagedFiles = stagedFiles
            )

            if (LibraryBackupScope.SETTINGS in scopes && settingsPayload != null) {
                applySettings(settingsPayload)
            }

            val archiveCreatedAt = resolvedManifest.optLong("createdAt", 0L)
                .takeIf { it > 0L }
                ?: System.currentTimeMillis()
            val inheritedProfile = parseBackupProfile(resolvedManifest)
            var adoptedFolderName: String? = null
            var suggestedFolderUri: Uri? = null
            var suggestedFolderName: String? = null
            if (inheritedProfile != null) {
                policyStore.applyInherited(inheritedProfile, archiveCreatedAt)
                val (adopted, suggestedUri, suggestedName) = inheritFolder(inheritedProfile)
                adoptedFolderName = adopted
                suggestedFolderUri = suggestedUri
                suggestedFolderName = suggestedName
            }

            return restored.copy(
                restoredSettings = LibraryBackupScope.SETTINGS in scopes && settingsPayload != null,
                inheritedProfile = inheritedProfile,
                adoptedFolderName = adoptedFolderName,
                suggestedFolderUri = suggestedFolderUri,
                suggestedFolderName = suggestedFolderName
            )
        } finally {
            stagingDirectory.deleteRecursively()
        }
    }

    private suspend fun restoreValidatedPayload(
        scopes: Set<LibraryBackupScope>,
        books: List<BookEntity>,
        bookmarks: List<ReadingBookmarkEntity>,
        annotations: List<ReadingAnnotationEntity>,
        words: List<WordLookupEntity>,
        favorites: List<TranslationFavoriteEntity>,
        stagedFiles: Map<String, File>
    ): RestoreResult {
        data class NewBook(val entity: BookEntity, val publication: File, val cover: File?)

        val existingBooks = database.bookDao().allBooks()
        val knownByFingerprint = existingBooks.associateBy(BookEntity::fingerprint).toMutableMap()
        val knownBySourceFingerprint = existingBooks
            .associateBy(BookEntity::sourceFingerprint)
            .toMutableMap()
        val usedIds = existingBooks.mapTo(mutableSetOf(), BookEntity::id)
        val occupiedPublications = existingBooks.mapTo(mutableSetOf(), BookEntity::publicationFileName)
        val occupiedCovers = existingBooks.mapNotNullTo(mutableSetOf(), BookEntity::coverFileName)
        val bookIdMap = existingBooks.associate { it.id to it.id }.toMutableMap()
        val bookTitleMap = existingBooks.associate { it.id to it.title }.toMutableMap()
        val newBooks = mutableListOf<NewBook>()

        if (LibraryBackupScope.LIBRARY in scopes) {
            books.forEach { book ->
                val existing = knownByFingerprint[book.fingerprint]
                    ?: knownBySourceFingerprint[book.sourceFingerprint]
                if (existing != null) {
                    bookIdMap[book.id] = existing.id
                    bookTitleMap[book.id] = existing.title
                    return@forEach
                }

                val publication = stagedFiles[
                    "$DIRECTORY_PUBLICATIONS${book.publicationFileName}"
                ] ?: return@forEach
                if (publication.length() != book.fileSize ||
                    PublicationIntegrity.capture(publication).sha256 != book.fingerprint.lowercase()
                ) {
                    throw BackupFailure("备份中的书籍文件校验失败。")
                }

                val restoredId = runCatching { UUID.fromString(book.id).toString() }
                    .getOrNull()
                    ?.takeIf { usedIds.add(it) }
                    ?: generateSequence { UUID.randomUUID().toString() }
                        .first { usedIds.add(it) }
                val publicationExtension = book.publicationFileName.substringAfterLast('.')
                val publicationName = AndroidBackupRestorePolicy.uniqueLeafName(
                    restoredId,
                    publicationExtension,
                    occupiedPublications
                )
                val coverSource = book.coverFileName?.let { coverName ->
                    stagedFiles["$DIRECTORY_COVERS$coverName"]
                }
                val coverName = coverSource?.let {
                    AndroidBackupRestorePolicy.uniqueLeafName(
                        "$restoredId-cover",
                        book.coverFileName.orEmpty().substringAfterLast('.', "jpg"),
                        occupiedCovers
                    )
                }
                val restoredBook = book.copy(
                    id = restoredId,
                    publicationFileName = publicationName,
                    coverFileName = coverName
                )
                bookIdMap[book.id] = restoredId
                bookTitleMap[book.id] = restoredBook.title
                knownByFingerprint[restoredBook.fingerprint] = restoredBook
                knownBySourceFingerprint[restoredBook.sourceFingerprint] = restoredBook
                newBooks += NewBook(restoredBook, publication, coverSource)
            }
        }

        val createdFiles = mutableListOf<File>()
        try {
            newBooks.forEach { plan ->
                val publication = publicationStore.resolvePublication(plan.entity.publicationFileName)
                check(publication.parentFile?.let { it.exists() || it.mkdirs() } == true) {
                    "无法准备书籍目录。"
                }
                plan.publication.copyTo(publication, overwrite = false)
                createdFiles += publication
                if (!publication.setReadOnly()) {
                    throw BackupFailure("无法保护恢复后的书籍文件。")
                }
                plan.entity.coverFileName?.let { coverName ->
                    val cover = publicationStore.resolveCover(coverName)
                    check(cover.parentFile?.let { it.exists() || it.mkdirs() } == true) {
                        "无法准备封面目录。"
                    }
                    plan.cover?.copyTo(cover, overwrite = false)
                    if (cover.exists()) createdFiles += cover
                }
            }

            var insertedBookmarks = 0
            var insertedAnnotations = 0
            var insertedWords = 0
            var insertedFavorites = 0
            database.withTransaction {
                newBooks.forEach { database.bookDao().insert(it.entity) }

                if (LibraryBackupScope.READING in scopes) {
                    val mappedBookmarks = bookmarks.mapNotNull { record ->
                        val bookId = bookIdMap[record.bookId] ?: return@mapNotNull null
                        val key = AndroidReaderRecordKeys.bookmark(bookId, record.locatorJson)
                            ?: return@mapNotNull null
                        record.copy(
                            id = UUID.randomUUID().toString(),
                            bookmarkKey = key,
                            bookId = bookId,
                            bookTitle = bookTitleMap[record.bookId] ?: record.bookTitle
                        )
                    }
                    val mappedAnnotations = annotations.mapNotNull { record ->
                        val bookId = bookIdMap[record.bookId] ?: return@mapNotNull null
                        record.copy(
                            id = UUID.randomUUID().toString(),
                            annotationKey = AndroidReaderRecordKeys.annotation(
                                bookId,
                                record.locatorJson,
                                record.selectedText
                            ),
                            bookId = bookId,
                            bookTitle = bookTitleMap[record.bookId] ?: record.bookTitle
                        )
                    }
                    insertedBookmarks = database.readerRecordDao()
                        .insertBookmarksIgnoringExisting(mappedBookmarks)
                        .count { it >= 0 }
                    insertedAnnotations = database.readerRecordDao()
                        .insertAnnotationsIgnoringExisting(mappedAnnotations)
                        .count { it >= 0 }
                }

                if (LibraryBackupScope.LEARNING in scopes) {
                    val mappedWords = words.map { record ->
                        val mappedBookId = record.sourceBookId?.let(bookIdMap::get)
                        record.copy(
                            sourceBookId = mappedBookId,
                            sourceBookTitle = mappedBookId?.let {
                                bookTitleMap[record.sourceBookId]
                            }
                        )
                    }
                    val mappedFavorites = favorites.map { record ->
                        val mappedBookId = record.bookId?.let(bookIdMap::get)
                        record.copy(
                            bookId = mappedBookId,
                            bookTitle = mappedBookId?.let {
                                bookTitleMap[record.bookId]
                            }
                        )
                    }
                    insertedWords = database.learningDao()
                        .insertWordsIgnoringExisting(mappedWords)
                        .count { it >= 0 }
                    insertedFavorites = database.learningDao()
                        .insertTranslationFavoritesIgnoringExisting(mappedFavorites)
                        .count { it >= 0 }
                }
            }

            val insertedBooks = newBooks.size
            val totalExpected = books.size + bookmarks.size + annotations.size +
                words.size + favorites.size
            val totalInserted = insertedBooks + insertedBookmarks + insertedAnnotations +
                insertedWords + insertedFavorites
            return RestoreResult(
                books = insertedBooks,
                bookmarks = insertedBookmarks,
                annotations = insertedAnnotations,
                words = insertedWords,
                translationFavorites = insertedFavorites,
                restoredSettings = false,
                skippedExisting = (totalExpected - totalInserted).coerceAtLeast(0)
            )
        } catch (error: Throwable) {
            createdFiles.asReversed().forEach { file ->
                if (file.extension.lowercase() in setOf("epub", "pdf")) file.setWritable(true)
                file.delete()
            }
            throw error
        }
    }

    private fun validateArchiveScopes(
        manifest: JSONObject,
        hasBooks: Boolean,
        hasReading: Boolean,
        hasLearning: Boolean,
        hasSettings: Boolean
    ): Set<LibraryBackupScope> {
        val declared = manifest.optJSONArray("scopes")?.let { array ->
            val names = (0 until array.length()).map { array.optString(it) }
            val scopes = names.mapNotNull { name ->
                LibraryBackupScope.entries.firstOrNull { it.name == name }
            }.toSet()
            if (scopes.size != names.size) {
                throw BackupFailure("这个备份包含当前版本无法识别的数据范围。")
            }
            scopes
        }
        val inferred = buildSet {
            if (hasBooks) add(LibraryBackupScope.LIBRARY)
            if (hasReading) add(LibraryBackupScope.READING)
            if (hasLearning) add(LibraryBackupScope.LEARNING)
            if (hasSettings) add(LibraryBackupScope.SETTINGS)
        }
        if (declared != null && declared != inferred) {
            throw BackupFailure("备份声明的数据范围与实际内容不一致。")
        }
        return declared ?: inferred
    }

    private fun checkedRestoreBytes(current: Long, added: Long): Long {
        val total = current + added
        if (added < 0 || total < current ||
            total > AndroidBackupRestorePolicy.MAXIMUM_RESTORE_BYTES
        ) {
            throw BackupFailure("这个备份过大，无法安全恢复。")
        }
        return total
    }

    // MARK: - Settings, minus every secret

    private fun settingsJson(): JSONObject {
        val app = appSettings.preferences.value
        val appearance = app.defaultReaderAppearance
        val translation = translationSettings.preferences.value
        return JSONObject()
            .put("theme", app.theme.name)
            .put("appearanceMode", app.appearanceMode.id)
            .put("showReadingProgress", app.showReadingProgress)
            .put("learningModuleVisible", app.learningModuleVisible)
            .put("applyReaderDefaultsToExistingBooks", app.applyReaderDefaultsToExistingBooks)
            .put(
                "colorPresets",
                com.jerreader.unified.library.ReaderColorPresetStore.encode(app.colorPresets)
            )
            .put(
                "readerAppearance",
                JSONObject()
                    .put("fontScale", appearance.fontScale)
                    .put("theme", appearance.theme.name)
                    .put("scroll", appearance.scroll)
                    .put("font", appearance.font.name)
                    .put("lineHeight", appearance.lineHeight)
                    .put("paragraphSpacing", appearance.paragraphSpacing)
                    .put("pageMargins", appearance.pageMargins)
                    .put("orientation", appearance.orientation.name)
                    .put("customBackgroundHex", appearance.customBackgroundHex)
                    .put("customSelectionColorHex", appearance.customSelectionColorHex)
                    .put("pdfPaperModeEnabled", appearance.pdfPaperModeEnabled)
            )
            .put(
                "translation",
                JSONObject()
                    // Endpoints and model names travel; the API key and the
                    // proxy credential stay in the keystore on this device.
                    .put("providerMode", translation.providerMode.name)
                    .put("directProvider", translation.directProvider.name)
                    .put("directEndpoint", translation.directEndpoint)
                    .put("directModel", translation.directModel)
                    .put("backendEndpoint", translation.backendEndpoint)
                    .put("backendModel", translation.backendModel)
                    .put("sourceChoice", translation.sourceChoice.name)
                    .put("targetLanguage", translation.targetLanguage.name)
                    .put("quickTranslationEnabled", translation.quickTranslationEnabled)
                    .put("quickTranslationUnit", translation.quickTranslationUnit.name)
                    .put(
                        "disablesTapPageTurnsDuringQuickTranslation",
                        translation.disablesTapPageTurnsDuringQuickTranslation
                    )
                    .put("displayMode", translation.displayMode.name)
                    .put("translationHapticsEnabled", translation.translationHapticsEnabled)
                    .put("automaticRetryEnabled", translation.automaticRetryEnabled)
                    .put("fallbackMode", translation.fallbackMode.name)
                    .put("preferAIWhenConfigured", translation.preferAIWhenConfigured)
                    .put("translationPromptTemplate", translation.translationPromptTemplate)
                    .put("grammarAnalysisPromptTemplate", translation.grammarAnalysisPromptTemplate)
            )
    }

    private fun applySettings(payload: JSONObject) {
        appSettings.restoreFromBackup(payload)
        payload.optJSONObject("translation")?.let(translationSettings::restoreFromBackup)
    }

    // MARK: - Archive listing

    private fun listBackups(directory: DocumentFile): List<LibraryBackupArchiveInfo> =
        directory.listFiles()
            .filter { it.isFile && LibraryBackupNaming.isArchiveName(it.name.orEmpty()) }
            .map {
                LibraryBackupArchiveInfo(
                    name = it.name.orEmpty(),
                    createdAtEpochMillis = it.lastModified(),
                    sizeBytes = it.length()
                )
            }
            .sortedByDescending { it.createdAtEpochMillis }

    private fun appVersionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    private fun timestamp(epochMillis: Long): String {
        val format = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        return format.format(java.util.Date(epochMillis))
    }

    private companion object {
        const val ARCHIVE_FORMAT = "jerreader.backup"
        const val ARCHIVE_VERSION = AndroidBackupRestorePolicy.SUPPORTED_ARCHIVE_VERSION
        val ARCHIVE_SUFFIX = LibraryBackupNaming.ARCHIVE_SUFFIX
        const val ENTRY_MANIFEST = "manifest.json"
        const val ENTRY_BOOKS = "library/books.json"
        const val ENTRY_READING = "reading/records.json"
        const val ENTRY_LEARNING = "learning/records.json"
        const val ENTRY_SETTINGS = "settings/preferences.json"
        const val DIRECTORY_PUBLICATIONS = "library/publications/"
        const val DIRECTORY_COVERS = "library/covers/"
    }
}

// MARK: - The regimen that travels with the archive

internal fun backupProfileJson(profile: LibraryBackupProfile): JSONObject {
    val policy = profile.policy
    return JSONObject()
        .put("automaticEnabled", policy.automaticEnabled)
        .put("intervalDays", policy.intervalDays)
        .put("retentionDays", policy.retentionDays)
        .put("maximumBackupCount", policy.maximumBackupCount)
        .put("maximumTotalBytes", policy.maximumTotalBytes)
        .put("automaticScopes", JSONArray(policy.automaticScopes.map { it.name }))
        .put("folderDisplayName", profile.folderDisplayName)
        .put("folderUri", profile.folderUri)
}

/**
 * Reads the regimen out of a manifest, or null for archives written before
 * archives carried one. Anything this build cannot honour — a scope name it
 * does not know, an interval it no longer offers — falls back rather than
 * landing in the pickers as a row nothing can select.
 */
internal fun parseBackupProfile(manifest: JSONObject): LibraryBackupProfile? {
    val json = manifest.optJSONObject("profile") ?: return null
    val default = LibraryBackupPolicy()
    val scopes = json.optJSONArray("automaticScopes")
        ?.let { array -> (0 until array.length()).mapNotNull { array.optString(it) } }
        ?.mapNotNull { name -> LibraryBackupScope.entries.firstOrNull { it.name == name } }
        ?.toSet()
        .orEmpty()
    return LibraryBackupProfile(
        policy = LibraryBackupPolicy(
            automaticEnabled = json.optBoolean("automaticEnabled", default.automaticEnabled),
            intervalDays = json.optInt("intervalDays", default.intervalDays),
            retentionDays = json.optInt("retentionDays", default.retentionDays),
            maximumBackupCount = json.optInt("maximumBackupCount", default.maximumBackupCount),
            maximumTotalBytes = json.optLong("maximumTotalBytes", default.maximumTotalBytes),
            automaticScopes = scopes.ifEmpty { default.automaticScopes }
        ),
        folderDisplayName = json.optStringOrNull("folderDisplayName"),
        folderUri = json.optStringOrNull("folderUri")
    ).normalized()
}

// MARK: - Row mapping

private fun bookJson(book: BookEntity) = JSONObject()
    .put("id", book.id)
    .put("title", book.title)
    .put("author", book.author)
    .put("language", book.language)
    .put("publicationFileName", book.publicationFileName)
    .put("coverFileName", book.coverFileName)
    .put("fingerprint", book.fingerprint)
    .put("fileSize", book.fileSize)
    .put("publicationLastModified", book.publicationLastModified)
    .put("importedAtEpochMillis", book.importedAtEpochMillis)
    .put("lastOpenedAtEpochMillis", book.lastOpenedAtEpochMillis)
    .put("locatorJson", book.locatorJson)
    .put("preferencesJson", book.preferencesJson)
    .put("sourceFormat", book.sourceFormat)
    .put("sourceFingerprint", book.sourceFingerprint)
    .put("lastReadProgress", book.lastReadProgress)
    .put("totalReadingSeconds", book.totalReadingSeconds)
    .put("category", book.category)
    .put("series", book.series)
    .put("tagsText", book.tagsText)

private fun bookEntity(json: JSONObject) = BookEntity(
    id = json.getString("id"),
    title = json.optString("title"),
    author = json.optStringOrNull("author"),
    language = json.optStringOrNull("language"),
    publicationFileName = json.optString("publicationFileName"),
    coverFileName = json.optStringOrNull("coverFileName"),
    fingerprint = json.optString("fingerprint"),
    fileSize = json.optLong("fileSize"),
    publicationLastModified = json.optLong("publicationLastModified"),
    importedAtEpochMillis = json.optLong("importedAtEpochMillis"),
    lastOpenedAtEpochMillis = if (json.isNull("lastOpenedAtEpochMillis")) {
        null
    } else {
        json.optLong("lastOpenedAtEpochMillis")
    },
    locatorJson = json.optStringOrNull("locatorJson"),
    preferencesJson = json.optStringOrNull("preferencesJson"),
    sourceFormat = json.optString("sourceFormat", "epub"),
    sourceFingerprint = json.optString("sourceFingerprint"),
    lastReadProgress = json.optDouble("lastReadProgress", 0.0),
    totalReadingSeconds = json.optDouble("totalReadingSeconds", 0.0),
    category = json.optString("category"),
    series = json.optString("series"),
    tagsText = json.optString("tagsText")
)

private fun bookmarkJson(record: ReadingBookmarkEntity) = JSONObject()
    .put("id", record.id)
    .put("bookmarkKey", record.bookmarkKey)
    .put("bookId", record.bookId)
    .put("bookTitle", record.bookTitle)
    .put("locatorJson", record.locatorJson)
    .put("chapterTitle", record.chapterTitle)
    .put("excerpt", record.excerpt)
    .put("progress", record.progress)
    .put("createdAtEpochMillis", record.createdAtEpochMillis)

private fun bookmarkEntity(json: JSONObject) = ReadingBookmarkEntity(
    id = json.getString("id"),
    bookmarkKey = json.optString("bookmarkKey"),
    bookId = json.optString("bookId"),
    bookTitle = json.optString("bookTitle"),
    locatorJson = json.optString("locatorJson"),
    chapterTitle = json.optString("chapterTitle"),
    excerpt = json.optStringOrNull("excerpt"),
    progress = json.optDouble("progress", 0.0),
    createdAtEpochMillis = json.optLong("createdAtEpochMillis")
)

private fun annotationJson(record: ReadingAnnotationEntity) = JSONObject()
    .put("id", record.id)
    .put("annotationKey", record.annotationKey)
    .put("bookId", record.bookId)
    .put("bookTitle", record.bookTitle)
    .put("locatorJson", record.locatorJson)
    .put("selectedText", record.selectedText)
    .put("noteText", record.noteText)
    .put("color", record.color)
    .put("chapterTitle", record.chapterTitle)
    .put("progress", record.progress)
    .put("createdAtEpochMillis", record.createdAtEpochMillis)
    .put("updatedAtEpochMillis", record.updatedAtEpochMillis)
    .put("geometryJson", record.geometryJson)

private fun annotationEntity(json: JSONObject) = ReadingAnnotationEntity(
    id = json.getString("id"),
    annotationKey = json.optString("annotationKey"),
    bookId = json.optString("bookId"),
    bookTitle = json.optString("bookTitle"),
    locatorJson = json.optString("locatorJson"),
    selectedText = json.optString("selectedText"),
    noteText = json.optString("noteText"),
    color = json.optString("color"),
    chapterTitle = json.optString("chapterTitle"),
    progress = json.optDouble("progress", 0.0),
    createdAtEpochMillis = json.optLong("createdAtEpochMillis"),
    updatedAtEpochMillis = json.optLong("updatedAtEpochMillis"),
    geometryJson = json.optStringOrNull("geometryJson")
)

private fun wordJson(record: WordLookupEntity) = JSONObject()
    .put("lookupKey", record.lookupKey)
    .put("surfaceForm", record.surfaceForm)
    .put("lemma", record.lemma)
    .put("reading", record.reading)
    .put("language", record.language)
    .put("partOfSpeech", record.partOfSpeech)
    .put("definitionsText", record.definitionsText)
    .put("inflectionNote", record.inflectionNote)
    .put("examplesText", record.examplesText)
    .put("usageNote", record.usageNote)
    .put("aiAnalysis", record.aiAnalysis)
    .put("aiProviderIdentifier", record.aiProviderIdentifier)
    .put("sentenceContext", record.sentenceContext)
    .put("sourceBookId", record.sourceBookId)
    .put("sourceBookTitle", record.sourceBookTitle)
    .put("providerIdentifier", record.providerIdentifier)
    .put("lookupCount", record.lookupCount)
    .put("createdAtEpochMillis", record.createdAtEpochMillis)
    .put("lastLookedUpAtEpochMillis", record.lastLookedUpAtEpochMillis)
    .put("isFavorite", record.isFavorite)
    .put("isInHistory", record.isInHistory)
    .put("vocabularyStatus", record.vocabularyStatus)
    .put("contextHistoryText", record.contextHistoryText)
    .put("reviewCount", record.reviewCount)
    .put("reviewStage", record.reviewStage)
    .put("reviewIntervalDays", record.reviewIntervalDays)
    .put("reviewLapseCount", record.reviewLapseCount)
    .put("lastReviewedAtEpochMillis", record.lastReviewedAtEpochMillis)
    .put("nextReviewAtEpochMillis", record.nextReviewAtEpochMillis)

private fun wordEntity(json: JSONObject) = WordLookupEntity(
    lookupKey = json.getString("lookupKey"),
    surfaceForm = json.optString("surfaceForm"),
    lemma = json.optStringOrNull("lemma"),
    reading = json.optStringOrNull("reading"),
    language = json.optString("language"),
    partOfSpeech = json.optStringOrNull("partOfSpeech"),
    definitionsText = json.optString("definitionsText"),
    inflectionNote = json.optStringOrNull("inflectionNote"),
    examplesText = json.optString("examplesText", ""),
    usageNote = json.optStringOrNull("usageNote"),
    aiAnalysis = json.optStringOrNull("aiAnalysis"),
    aiProviderIdentifier = json.optStringOrNull("aiProviderIdentifier"),
    sentenceContext = json.optStringOrNull("sentenceContext"),
    sourceBookId = json.optStringOrNull("sourceBookId"),
    sourceBookTitle = json.optStringOrNull("sourceBookTitle"),
    providerIdentifier = json.optString("providerIdentifier"),
    lookupCount = json.optInt("lookupCount", 1),
    createdAtEpochMillis = json.optLong("createdAtEpochMillis"),
    lastLookedUpAtEpochMillis = json.optLong("lastLookedUpAtEpochMillis"),
    isFavorite = json.optBoolean("isFavorite"),
    isInHistory = json.optBoolean("isInHistory", true),
    vocabularyStatus = json.optString(
        "vocabularyStatus",
        if (json.optBoolean("isFavorite")) "learning" else "new"
    ),
    contextHistoryText = json.optString(
        "contextHistoryText",
        json.optStringOrNull("sentenceContext").orEmpty()
    ),
    reviewCount = json.optInt("reviewCount", 0),
    reviewStage = json.optInt("reviewStage", 0),
    reviewIntervalDays = json.optInt("reviewIntervalDays", 0),
    reviewLapseCount = json.optInt("reviewLapseCount", 0),
    lastReviewedAtEpochMillis = json.optLong("lastReviewedAtEpochMillis", 0),
    nextReviewAtEpochMillis = json.optLong("nextReviewAtEpochMillis", 0)
)

private fun favoriteJson(record: TranslationFavoriteEntity) = JSONObject()
    .put("favoriteKey", record.favoriteKey)
    .put("sourceText", record.sourceText)
    .put("translatedText", record.translatedText)
    .put("sourceLanguage", record.sourceLanguage)
    .put("targetLanguage", record.targetLanguage)
    .put("providerIdentifier", record.providerIdentifier)
    .put("bookId", record.bookId)
    .put("bookTitle", record.bookTitle)
    .put("locatorJson", record.locatorJson)
    .put("createdAtEpochMillis", record.createdAtEpochMillis)
    .put("updatedAtEpochMillis", record.updatedAtEpochMillis)

private fun favoriteEntity(json: JSONObject) = TranslationFavoriteEntity(
    favoriteKey = json.getString("favoriteKey"),
    sourceText = json.optString("sourceText"),
    translatedText = json.optString("translatedText"),
    sourceLanguage = json.optString("sourceLanguage"),
    targetLanguage = json.optString("targetLanguage"),
    providerIdentifier = json.optString("providerIdentifier"),
    bookId = json.optStringOrNull("bookId"),
    bookTitle = json.optStringOrNull("bookTitle"),
    locatorJson = json.optStringOrNull("locatorJson"),
    createdAtEpochMillis = json.optLong("createdAtEpochMillis"),
    updatedAtEpochMillis = json.optLong("updatedAtEpochMillis")
)

// MARK: - Small helpers

private fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf(String::isNotEmpty)

private fun JSONArray?.toObjectList(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull(::optJSONObject)
}

private fun ZipOutputStream.writeJson(name: String, payload: JSONObject) {
    putNextEntry(ZipEntry(name))
    write(payload.toString().toByteArray(Charsets.UTF_8))
    closeEntry()
}

private fun ZipOutputStream.writeFile(name: String, file: File) {
    putNextEntry(ZipEntry(name))
    file.inputStream().use { input -> input.copyTo(this) }
    closeEntry()
}

private fun ZipInputStream.readEntryBytes(maximumBytes: Long): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > maximumBytes) {
            throw BackupFailure("备份中的数据条目过大。")
        }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun ZipInputStream.copyEntryTo(destination: File, maximumBytes: Long): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    destination.outputStream().use { output ->
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > maximumBytes) {
                throw BackupFailure("备份中的文件条目过大。")
            }
            output.write(buffer, 0, count)
        }
        output.flush()
    }
    return total
}
