package com.jerreader.android.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.jerreader.android.data.BookEntity
import com.jerreader.android.data.JerreaderDatabase
import com.jerreader.android.data.ReadingAnnotationEntity
import com.jerreader.android.data.ReadingBookmarkEntity
import com.jerreader.android.data.TranslationFavoriteEntity
import com.jerreader.android.data.WordLookupEntity
import com.jerreader.android.library.ImmutablePublicationStore
import com.jerreader.android.settings.AndroidAppSettingsStore
import com.jerreader.android.translation.AndroidTranslationSettingsStore
import com.jerreader.shared.library.LibraryBackupArchiveInfo
import com.jerreader.shared.library.LibraryBackupPolicy
import com.jerreader.shared.library.LibraryBackupProfile
import com.jerreader.shared.library.LibraryBackupPruner
import com.jerreader.shared.library.LibraryBackupScope
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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
            stream.use { readArchive(it) }
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
        var manifestSeen = false
        var books = 0
        var bookmarks = 0
        var annotations = 0
        var words = 0
        var favorites = 0
        var restoredSettings = false
        var skipped = 0
        var inheritedProfile: LibraryBackupProfile? = null
        var archiveCreatedAt = System.currentTimeMillis()

        // Publication bytes are staged first because a book row is worthless
        // without its file, and zip entries arrive in whatever order they were
        // written.
        val stagedFiles = mutableMapOf<String, File>()
        val stagingDirectory = File(context.cacheDir, "backup-restore-${System.nanoTime()}")
        stagingDirectory.mkdirs()

        try {
            var pendingBooks: List<BookEntity> = emptyList()
            ZipInputStream(stream.buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name == ENTRY_MANIFEST -> {
                            val manifest = JSONObject(zip.readText())
                            if (manifest.optString("format") != ARCHIVE_FORMAT) {
                                throw BackupFailure("这个文件不是有效的 Jerreader 备份，或者已经损坏。")
                            }
                            if (manifest.optInt("formatVersion", 1) > ARCHIVE_VERSION) {
                                throw BackupFailure("这个备份来自更新版本的 Jerreader，请先升级 App。")
                            }
                            manifest.optLong("createdAt", 0L)
                                .takeIf { it > 0L }
                                ?.let { archiveCreatedAt = it }
                            inheritedProfile = parseBackupProfile(manifest)
                            manifestSeen = true
                        }

                        name == ENTRY_BOOKS -> {
                            pendingBooks = JSONObject(zip.readText())
                                .optJSONArray("books")
                                .toObjectList()
                                .map(::bookEntity)
                        }

                        name == ENTRY_READING -> {
                            val payload = JSONObject(zip.readText())
                            val dao = database.readerRecordDao()
                            val bookmarkRows = payload.optJSONArray("bookmarks")
                                .toObjectList()
                                .map(::bookmarkEntity)
                            val annotationRows = payload.optJSONArray("annotations")
                                .toObjectList()
                                .map(::annotationEntity)
                            val insertedBookmarks = dao
                                .insertBookmarksIgnoringExisting(bookmarkRows)
                                .count { it >= 0 }
                            val insertedAnnotations = dao
                                .insertAnnotationsIgnoringExisting(annotationRows)
                                .count { it >= 0 }
                            bookmarks = insertedBookmarks
                            annotations = insertedAnnotations
                            skipped += (bookmarkRows.size - insertedBookmarks) +
                                (annotationRows.size - insertedAnnotations)
                        }

                        name == ENTRY_LEARNING -> {
                            val payload = JSONObject(zip.readText())
                            val dao = database.learningDao()
                            val wordRows = payload.optJSONArray("words")
                                .toObjectList()
                                .map(::wordEntity)
                            val favoriteRows = payload.optJSONArray("favorites")
                                .toObjectList()
                                .map(::favoriteEntity)
                            val insertedWords = dao
                                .insertWordsIgnoringExisting(wordRows)
                                .count { it >= 0 }
                            val insertedFavorites = dao
                                .insertTranslationFavoritesIgnoringExisting(favoriteRows)
                                .count { it >= 0 }
                            words = insertedWords
                            favorites = insertedFavorites
                            skipped += (wordRows.size - insertedWords) +
                                (favoriteRows.size - insertedFavorites)
                        }

                        name == ENTRY_SETTINGS -> {
                            applySettings(JSONObject(zip.readText()))
                            restoredSettings = true
                        }

                        name.startsWith(DIRECTORY_PUBLICATIONS) ||
                            name.startsWith(DIRECTORY_COVERS) -> {
                            val staged = File(stagingDirectory, name.substringAfterLast('/'))
                            staged.outputStream().use { output -> zip.copyTo(output) }
                            stagedFiles[name] = staged
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            if (!manifestSeen) {
                throw BackupFailure("这个文件不是有效的 Jerreader 备份，或者已经损坏。")
            }

            if (pendingBooks.isNotEmpty()) {
                val restorable = pendingBooks.filter { book ->
                    stagedFiles.containsKey("$DIRECTORY_PUBLICATIONS${book.publicationFileName}")
                }
                restorable.forEach { book ->
                    stagedFiles["$DIRECTORY_PUBLICATIONS${book.publicationFileName}"]?.copyTo(
                        publicationStore.resolvePublication(book.publicationFileName)
                            .also { it.parentFile?.mkdirs() },
                        overwrite = true
                    )
                    book.coverFileName?.let { coverName ->
                        stagedFiles["$DIRECTORY_COVERS$coverName"]?.copyTo(
                            publicationStore.resolveCover(coverName)
                                .also { it.parentFile?.mkdirs() },
                            overwrite = true
                        )
                    }
                }
                val inserted = database.bookDao()
                    .insertIgnoringExisting(restorable)
                    .count { it >= 0 }
                books = inserted
                skipped += pendingBooks.size - inserted
            }
        } finally {
            stagingDirectory.deleteRecursively()
        }

        // Applied only once the payload is in: a restore that threw halfway
        // must not leave the user with someone else's backup schedule.
        val profile = inheritedProfile
        var adoptedFolderName: String? = null
        var suggestedFolderUri: Uri? = null
        var suggestedFolderName: String? = null
        if (profile != null) {
            policyStore.applyInherited(profile, archiveCreatedAt)
            val (adopted, suggestedUri, suggestedName) = inheritFolder(profile)
            adoptedFolderName = adopted
            suggestedFolderUri = suggestedUri
            suggestedFolderName = suggestedName
        }

        return RestoreResult(
            books = books,
            bookmarks = bookmarks,
            annotations = annotations,
            words = words,
            translationFavorites = favorites,
            restoredSettings = restoredSettings,
            skippedExisting = skipped,
            inheritedProfile = profile,
            adoptedFolderName = adoptedFolderName,
            suggestedFolderUri = suggestedFolderUri,
            suggestedFolderName = suggestedFolderName
        )
    }

    // MARK: - Settings, minus every secret

    private fun settingsJson(): JSONObject {
        val app = appSettings.preferences.value
        val appearance = app.defaultReaderAppearance
        val translation = translationSettings.preferences.value
        return JSONObject()
            .put("theme", app.theme.name)
            .put("showReadingProgress", app.showReadingProgress)
            .put("applyReaderDefaultsToExistingBooks", app.applyReaderDefaultsToExistingBooks)
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
            .filter { it.isFile && it.name?.endsWith(ARCHIVE_SUFFIX) == true }
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
        const val ARCHIVE_VERSION = 1
        const val ARCHIVE_SUFFIX = ".jerbackup.zip"
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

private fun wordEntity(json: JSONObject) = WordLookupEntity(
    lookupKey = json.getString("lookupKey"),
    surfaceForm = json.optString("surfaceForm"),
    lemma = json.optStringOrNull("lemma"),
    reading = json.optStringOrNull("reading"),
    language = json.optString("language"),
    partOfSpeech = json.optStringOrNull("partOfSpeech"),
    definitionsText = json.optString("definitionsText"),
    inflectionNote = json.optStringOrNull("inflectionNote"),
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
    isInHistory = json.optBoolean("isInHistory", true)
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

private fun ZipInputStream.readText(): String = readBytes().toString(Charsets.UTF_8)
