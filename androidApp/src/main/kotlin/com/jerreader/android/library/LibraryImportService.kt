package com.jerreader.android.library

import android.graphics.Bitmap
import android.net.Uri
import com.jerreader.unified.domain.BookFormat
import com.jerreader.android.reader.ReadiumEnvironment
import com.jerreader.unified.library.LibraryBook
import com.jerreader.unified.library.LibraryImportOutcome
import com.jerreader.unified.library.LibraryRepository
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
    private val converter: DocumentToEpubConverter = DocumentToEpubConverter(),
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun importEpub(source: Uri): LibraryImportOutcome = importPublication(source)

    suspend fun importPublication(source: Uri): LibraryImportOutcome = withContext(Dispatchers.IO) {
        var staged = store.copyToStaging(source)
        val sourceFormat = BookFormat.fromFileName(staged.displayName)
            ?: throw LibraryImportException.UnsupportedFormat
        val sourceFingerprint = staged.snapshot.sha256
        repository.bookWithSourceFingerprint(sourceFingerprint)?.let { existing ->
            store.discard(staged)
            return@withContext LibraryImportOutcome.AlreadyImported(existing)
        }

        if (sourceFormat == BookFormat.DOCX || sourceFormat == BookFormat.TXT) {
            try {
                val converted = converter.convert(staged, sourceFormat)
                val displayName = staged.displayName
                store.discard(staged)
                staged = store.stageGeneratedEpub(displayName, converted)
            } catch (error: Throwable) {
                store.discard(staged)
                throw error
            }
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
                val expectedProfile = if (sourceFormat == BookFormat.PDF) {
                    Publication.Profile.PDF
                } else {
                    Publication.Profile.EPUB
                }
                if (!publication.conformsTo(expectedProfile)) {
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
                    preferencesJson = null,
                    sourceFormat = sourceFormat.fileExtension,
                    sourceFingerprint = sourceFingerprint
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
    data object InvalidEpub : LibraryImportException("文件已损坏或无法解析。")
    data object InvalidDocument : LibraryImportException("DOCX 文档已损坏或无法读取。")
    data object EmptyDocument : LibraryImportException("文档中没有可阅读的正文。")
    data object RestrictedPublication : LibraryImportException("该出版物受保护，Jerreader 不会尝试绕过 DRM。")
    data object UnsupportedFormat : LibraryImportException("仅支持 EPUB、PDF、DOCX 和 TXT。")
}
