package com.jerreader.android.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.jerreader.shared.domain.BookFormat
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class StagedPublication(
    val displayName: String,
    val file: File,
    val snapshot: PublicationSnapshot
)

data class StoredPublication(
    val fileName: String,
    val snapshot: PublicationSnapshot
)

class ImmutablePublicationStore(
    private val context: Context,
    private val rootDirectory: File = context.filesDir
) {
    private val publicationsDirectory = File(rootDirectory, "publications")
    private val stagingDirectory = File(rootDirectory, "publication-staging")
    private val coversDirectory = File(rootDirectory, "covers")

    fun copyToStaging(source: Uri): StagedPublication {
        var displayName = sourceDisplayName(source)
        if (BookFormat.fromFileName(displayName) == null) {
            val inferredExtension = when (context.contentResolver.getType(source)) {
                "application/epub+zip" -> "epub"
                "application/pdf" -> "pdf"
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
                "text/plain" -> "txt"
                else -> null
            }
            if (inferredExtension != null) displayName = "$displayName.$inferredExtension"
        }
        val format = requireNotNull(BookFormat.fromFileName(displayName)) {
            "仅支持 EPUB、PDF、DOCX 和 TXT。"
        }

        stagingDirectory.apply {
            check(exists() || mkdirs()) { "无法创建出版物目录。" }
        }
        val stagedFile = File(
            stagingDirectory,
            "${UUID.randomUUID()}.${format.fileExtension}.part"
        )

        try {
            val input = checkNotNull(context.contentResolver.openInputStream(source)) {
                "无法读取所选文件。"
            }
            input.use { sourceStream ->
                FileOutputStream(stagedFile).use { output ->
                    sourceStream.copyTo(output)
                    output.fd.sync()
                }
            }
            check(stagedFile.setReadOnly()) { "无法将出版物验证副本设为只读。" }

            return StagedPublication(
                displayName = displayName,
                file = stagedFile,
                snapshot = PublicationIntegrity.capture(stagedFile)
            )
        } catch (error: Throwable) {
            stagedFile.setWritable(true)
            stagedFile.delete()
            throw error
        }
    }

    fun stageGeneratedEpub(displayName: String, bytes: ByteArray): StagedPublication {
        stagingDirectory.apply {
            check(exists() || mkdirs()) { "无法创建出版物目录。" }
        }
        val stagedFile = File(stagingDirectory, "${UUID.randomUUID()}.epub.part")
        try {
            FileOutputStream(stagedFile).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            check(stagedFile.setReadOnly()) { "无法将转换后的 EPUB 设为只读。" }
            return StagedPublication(
                displayName = displayName,
                file = stagedFile,
                snapshot = PublicationIntegrity.capture(stagedFile)
            )
        } catch (error: Throwable) {
            stagedFile.setWritable(true)
            stagedFile.delete()
            throw error
        }
    }

    fun commit(staged: StagedPublication, id: String): StoredPublication {
        publicationsDirectory.apply {
            check(exists() || mkdirs()) { "无法创建出版物目录。" }
        }
        require(PublicationIntegrity.isUnchanged(staged.snapshot)) {
            "EPUB 验证过程中发生了变化。"
        }
        val storedExtension = staged.file.name
            .substringBeforeLast(".part")
            .substringAfterLast('.')
            .lowercase()
        require(storedExtension == "epub" || storedExtension == "pdf") {
            "只有 EPUB 或 PDF 可进入书架。"
        }
        val destination = File(publicationsDirectory, "$id.$storedExtension")
        check(!destination.exists()) { "出版物目标已存在。" }
        check(staged.file.renameTo(destination)) { "无法保存出版物副本。" }
        check(destination.setReadOnly()) { "无法将出版物副本设为只读。" }
        return StoredPublication(
            fileName = destination.name,
            snapshot = PublicationIntegrity.capture(destination)
        )
    }

    fun discard(staged: StagedPublication) {
        staged.file.setWritable(true)
        staged.file.delete()
    }

    fun resolvePublication(fileName: String): File = File(publicationsDirectory, fileName)

    fun writeCover(bookId: String, bytes: ByteArray): String {
        coversDirectory.apply {
            check(exists() || mkdirs()) { "无法创建封面目录。" }
        }
        val cover = File(coversDirectory, "$bookId.png")
        val temporary = File(coversDirectory, "$bookId.png.part")
        FileOutputStream(temporary).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (cover.exists()) check(cover.delete()) { "无法替换旧封面。" }
        check(temporary.renameTo(cover)) { "无法保存封面。" }
        return cover.name
    }

    fun resolveCover(fileName: String): File = File(coversDirectory, fileName)

    fun deletePublication(fileName: String): Boolean {
        val file = resolvePublication(fileName)
        if (!file.exists()) return true
        file.setWritable(true)
        return file.delete()
    }

    fun deleteCover(fileName: String?): Boolean {
        if (fileName == null) return true
        val file = resolveCover(fileName)
        return !file.exists() || file.delete()
    }

    private fun sourceDisplayName(source: Uri): String {
        context.contentResolver.query(
            source,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    cursor.getString(index)?.takeIf(String::isNotBlank)?.let { return it }
                }
            }
        }
        return source.lastPathSegment?.substringAfterLast('/') ?: "publication"
    }
}
