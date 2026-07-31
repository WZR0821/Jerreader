package com.jerreader.android.library

import android.graphics.Bitmap
import android.net.Uri
import com.jerreader.android.reader.ReadiumEnvironment
import com.jerreader.shared.library.LibraryBook
import com.jerreader.shared.library.LibraryImportOutcome
import com.jerreader.shared.library.LibraryRepository
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.publication.services.isRestricted
import org.readium.r2.shared.util.getOrElse

class LibraryImportService(
    private val store: ImmutablePublicationStore,
    private val readium: ReadiumEnvironment,
    private val repository: LibraryRepository,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun importEpub(source: Uri): LibraryImportOutcome = withContext(Dispatchers.IO) {
        val staged = store.copyToStaging(source)
        repository.bookWithFingerprint(staged.snapshot.sha256)?.let { existing ->
            store.discard(staged)
            return@withContext LibraryImportOutcome.AlreadyImported(existing)
        }

        var stored: StoredPublication? = null
        var coverFileName: String? = null
        try {
            val asset = readium.assetRetriever.retrieve(staged.file).getOrElse {
                throw LibraryImportException.InvalidEpub
            }
            val publication = readium.publicationOpener.open(
                asset,
                allowUserInteraction = false
            ).getOrElse {
                throw LibraryImportException.InvalidEpub
            }

            try {
                if (publication.isRestricted) throw LibraryImportException.RestrictedPublication
                if (!publication.conformsTo(Publication.Profile.EPUB)) {
                    throw LibraryImportException.UnsupportedFormat
                }

                val id = UUID.randomUUID().toString()
                val coverBytes = publication.cover()?.toPngBytes()
                val metadata = publication.metadata

                check(PublicationIntegrity.isUnchanged(staged.snapshot)) {
                    "Readium 修改了待导入的 EPUB。"
                }
                stored = store.commit(staged, id)
                coverFileName = coverBytes?.let { bytes -> store.writeCover(id, bytes) }

                val book = LibraryBook(
                    id = id,
                    title = metadata.title.orEmpty().trim().ifEmpty {
                        staged.displayName.substringBeforeLast('.').ifEmpty { "未命名书籍" }
                    },
                    author = metadata.authors.joinToString(", ") { author -> author.name }
                        .takeIf(String::isNotBlank),
                    language = metadata.languages.firstOrNull(),
                    publicationFileName = checkNotNull(stored).fileName,
                    coverFileName = coverFileName,
                    fingerprint = staged.snapshot.sha256,
                    fileSize = staged.file.length().takeIf { it > 0 }
                        ?: checkNotNull(stored).let { storedPublication ->
                            store.resolvePublication(storedPublication.fileName).length()
                        },
                    publicationLastModified = checkNotNull(stored).snapshot.lastModified,
                    importedAtEpochMillis = now(),
                    lastOpenedAtEpochMillis = null,
                    locatorJson = null,
                    preferencesJson = null
                )
                repository.add(book)
                LibraryImportOutcome.Imported(book)
            } finally {
                publication.close()
            }
        } catch (error: Throwable) {
            if (stored == null) {
                store.discard(staged)
            } else {
                store.deleteCover(coverFileName)
                store.deletePublication(checkNotNull(stored).fileName)
            }
            throw error
        }
    }

    private fun Bitmap.toPngBytes(): ByteArray = ByteArrayOutputStream().use { output ->
        check(compress(Bitmap.CompressFormat.PNG, 100, output)) { "无法编码 EPUB 封面。" }
        output.toByteArray()
    }
}

sealed class LibraryImportException(message: String) : Exception(message) {
    data object InvalidEpub : LibraryImportException("EPUB 已损坏或无法解析。")
    data object RestrictedPublication : LibraryImportException("该 EPUB 受保护，Jerreader 不会尝试绕过 DRM。")
    data object UnsupportedFormat : LibraryImportException("当前文件不是可支持的 EPUB。")
}
