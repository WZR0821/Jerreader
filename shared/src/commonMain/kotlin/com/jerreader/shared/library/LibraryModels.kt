package com.jerreader.shared.library

data class LibraryBook(
    val id: String,
    val title: String,
    val author: String?,
    val language: String?,
    val publicationFileName: String,
    val coverFileName: String?,
    val fingerprint: String,
    val fileSize: Long,
    val publicationLastModified: Long,
    val importedAtEpochMillis: Long,
    val lastOpenedAtEpochMillis: Long?,
    val locatorJson: String?,
    val preferencesJson: String?
)

sealed interface LibraryImportOutcome {
    val book: LibraryBook

    data class Imported(override val book: LibraryBook) : LibraryImportOutcome
    data class AlreadyImported(override val book: LibraryBook) : LibraryImportOutcome
}

enum class ReaderThemeOption {
    LIGHT,
    SEPIA,
    DARK
}

data class ReaderAppearance(
    val fontScale: Double = 1.0,
    val theme: ReaderThemeOption = ReaderThemeOption.LIGHT
)
