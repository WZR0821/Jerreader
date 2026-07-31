package com.jerreader.android.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.jerreader.shared.domain.BookFormat
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ImportedPublication(
    val displayName: String,
    val snapshot: PublicationSnapshot
)

class ImmutablePublicationStore(private val context: Context) {
    fun importFrom(source: Uri): ImportedPublication {
        val displayName = sourceDisplayName(source)
        val format = BookFormat.fromFileName(displayName)
        require(format == BookFormat.EPUB || format == BookFormat.PDF) {
            "M0 只验证 EPUB 和 PDF。"
        }

        val publicationsDirectory = File(context.filesDir, "publications").apply {
            check(exists() || mkdirs()) { "无法创建出版物目录。" }
        }
        val destination = File(
            publicationsDirectory,
            "${UUID.randomUUID()}.${format.fileExtension}"
        )
        val temporary = File(publicationsDirectory, "${destination.name}.part")

        try {
            val input = checkNotNull(context.contentResolver.openInputStream(source)) {
                "无法读取所选文件。"
            }
            input.use { sourceStream ->
                FileOutputStream(temporary).use { output ->
                    sourceStream.copyTo(output)
                    output.fd.sync()
                }
            }
            check(temporary.renameTo(destination)) { "无法保存出版物副本。" }
            check(destination.setReadOnly()) { "无法将出版物副本设为只读。" }

            return ImportedPublication(
                displayName = displayName,
                snapshot = PublicationIntegrity.capture(destination)
            )
        } catch (error: Throwable) {
            temporary.delete()
            destination.delete()
            throw error
        }
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
