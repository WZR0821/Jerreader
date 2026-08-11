package com.jerreader.android.backup

import com.jerreader.android.data.BookEntity

/** Pure validation rules applied before an Android backup can mutate local data. */
internal object AndroidBackupRestorePolicy {
    const val SUPPORTED_ARCHIVE_VERSION = 1
    const val MAXIMUM_METADATA_BYTES = 16L * 1_024 * 1_024
    const val MAXIMUM_RESTORE_BYTES = 8L * 1_024 * 1_024 * 1_024
    // Android previously allowed large local PDFs and wrote them into backups
    // without a per-book cap. Do not make a valid self-produced archive
    // unrestorable; the aggregate restore limit remains the safety boundary.
    const val MAXIMUM_PUBLICATION_BYTES = MAXIMUM_RESTORE_BYTES
    const val MAXIMUM_COVER_BYTES = 32L * 1_024 * 1_024
    const val MAXIMUM_RECORD_COUNT = 200_000

    fun isSafeLeafName(name: String): Boolean {
        if (name.isEmpty() || name == "." || name == "..") return false
        if ('/' in name || '\\' in name || '\u0000' in name) return false
        return java.io.File(name).name == name
    }

    fun isSHA256(value: String): Boolean = value.length == 64 && value.all { character ->
        character in '0'..'9' || character in 'a'..'f'
    }

    fun isSupportedArchiveVersion(version: Int): Boolean =
        version in 1..SUPPORTED_ARCHIVE_VERSION

    fun haveUniqueIds(books: List<BookEntity>): Boolean =
        books.map(BookEntity::id).toSet().size == books.size

    fun isValidBook(book: BookEntity): Boolean {
        val extension = book.publicationFileName.substringAfterLast('.', "").lowercase()
        return book.id.isNotBlank() && book.id.length <= 128 &&
            isSafeLeafName(book.publicationFileName) &&
            (book.coverFileName?.let(::isSafeLeafName) != false) &&
            extension in setOf("epub", "pdf") &&
            isSHA256(book.fingerprint) &&
            isSHA256(book.sourceFingerprint) &&
            book.fileSize in 1..MAXIMUM_PUBLICATION_BYTES
    }

    fun uniqueLeafName(
        stem: String,
        extension: String,
        occupied: MutableSet<String>
    ): String {
        val normalizedExtension = extension.lowercase().takeIf(String::isNotBlank)
        var suffix = 0
        while (true) {
            val candidate = buildString {
                append(stem)
                if (suffix > 0) append('-').append(suffix)
                normalizedExtension?.let { append('.').append(it) }
            }
            if (isSafeLeafName(candidate) && occupied.add(candidate)) return candidate
            suffix += 1
        }
    }
}
