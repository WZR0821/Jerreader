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
    val preferencesJson: String?,
    val sourceFormat: String = "epub",
    val sourceFingerprint: String = fingerprint,
    val lastReadProgress: Double = 0.0,
    val totalReadingSeconds: Double = 0.0,
    val category: String = "",
    val series: String = "",
    val tags: List<String> = emptyList()
)

sealed interface LibraryImportOutcome {
    val book: LibraryBook

    data class Imported(override val book: LibraryBook) : LibraryImportOutcome
    data class AlreadyImported(override val book: LibraryBook) : LibraryImportOutcome
}

enum class ReaderThemeOption {
    LIGHT,
    SEPIA,
    COOL_GRAY,
    DARK
}

enum class ReaderFontOption {
    PUBLICATION,
    SERIF,
    SANS_SERIF,
    OPEN_DYSLEXIC
}

enum class ReaderTextOrientation {
    PUBLICATION,
    HORIZONTAL,
    VERTICAL
}

data class ReaderAppearance(
    val fontScale: Double = 1.0,
    val theme: ReaderThemeOption = ReaderThemeOption.LIGHT,
    val scroll: Boolean = false,
    val font: ReaderFontOption = ReaderFontOption.PUBLICATION,
    val lineHeight: Double = 1.4,
    val paragraphSpacing: Double = 0.0,
    val pageMargins: Double = 1.0,
    val orientation: ReaderTextOrientation = ReaderTextOrientation.PUBLICATION,
    val customBackgroundHex: String = "",
    val customSelectionColorHex: String = "",
    val pdfPaperModeEnabled: Boolean = false
)
