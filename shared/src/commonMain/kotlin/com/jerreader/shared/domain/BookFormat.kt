package com.jerreader.shared.domain

enum class BookFormat(val fileExtension: String) {
    EPUB("epub"),
    PDF("pdf"),
    DOCX("docx"),
    TXT("txt");

    companion object {
        fun fromFileName(fileName: String): BookFormat? {
            val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            return entries.firstOrNull { format ->
                format.fileExtension.equals(extension, ignoreCase = true)
            }
        }
    }
}
