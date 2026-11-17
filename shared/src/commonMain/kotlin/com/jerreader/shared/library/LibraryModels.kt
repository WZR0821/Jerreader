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

/**
 * How the reader arranges its bars. Mirrors iOS `ReaderLayoutMode`: the same
 * stored values (`reader.layoutMode`), titles and explanations.
 */
enum class ReaderLayoutMode(
    val storageValue: String,
    val title: String,
    val detail: String
) {
    /** The bars stay on screen and the page is laid out between them. */
    STANDARD("standard", "标准", "上下栏常驻，正文排在两栏之间。"),

    /**
     * The bars start hidden and the page uses the whole screen; a tap on the
     * blank strip above or below the text, or on no text at all, shows them
     * until they hide again.
     */
    IMMERSIVE(
        "immersive",
        "沉浸",
        "上下栏默认收起，正文全屏。轻点屏幕顶部或底部的空白处显示，几秒后自动收起。"
    );

    companion object {
        fun fromStorage(raw: String?): ReaderLayoutMode =
            entries.firstOrNull { it.storageValue == raw } ?: STANDARD
    }
}

/** Reader chrome measurements shared with iOS `ReaderInterfaceDefaults`. */
object ReaderInterfaceDefaults {
    /**
     * The gap kept between the text and whatever is above and below it: a bar
     * in the standard layout, the system bars in the immersive one.
     */
    const val CONTENT_EDGE_INSET_DP = 14

    /** How long the immersive layout keeps the bars after the last touch. */
    const val IMMERSIVE_CHROME_AUTO_HIDE_MILLIS = 4_000L

    /** Space kept between a floating card and a bar or the screen edge. */
    const val FLOATING_CARD_EDGE_GAP_DP = 12

    /** Bar background opacity for a stored transparency, as on iOS. */
    fun backgroundOpacity(transparency: Float): Float =
        1f - (if (transparency.isFinite()) transparency else 0.15f).coerceIn(0f, 1f)

    /**
     * The colour Readium paints behind the page (ReadiumCSS light, sepia and
     * night backgrounds, the cool-grey theme or the custom colour), as ARGB, so
     * the bars can be drawn in the page's own colour.
     */
    fun pageColorArgb(appearance: ReaderAppearance): Long {
        customRgb(appearance.customBackgroundHex)?.let { return 0xFF000000L or it }
        return when (appearance.theme) {
            ReaderThemeOption.LIGHT -> 0xFFFFFFFFL
            ReaderThemeOption.SEPIA -> 0xFFFAF4E8L
            ReaderThemeOption.COOL_GRAY -> 0xFFEEF3F6L
            ReaderThemeOption.DARK -> 0xFF000000L
        }
    }

    /** iOS `prefersDarkReaderChrome`: light text on dark pages. */
    fun prefersDarkChrome(appearance: ReaderAppearance): Boolean {
        val rgb = customRgb(appearance.customBackgroundHex)
            ?: return appearance.theme == ReaderThemeOption.DARK
        val red = ((rgb shr 16) and 0xFF) / 255.0
        val green = ((rgb shr 8) and 0xFF) / 255.0
        val blue = (rgb and 0xFF) / 255.0
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue < 0.48
    }

    private fun customRgb(hex: String): Long? {
        val digits = hex.trim().removePrefix("#")
        if (digits.length != 6 || digits.any { it !in "0123456789abcdefABCDEF" }) return null
        return digits.toLongOrNull(16)
    }
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
