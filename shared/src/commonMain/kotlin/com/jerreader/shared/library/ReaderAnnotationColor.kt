package com.jerreader.shared.library

/**
 * Mirrors iOS `ReadingAnnotationColor`. The stored identifier, the visible
 * title and the highlight tint all come from the iOS definition so a marker
 * saved on either platform means the same thing to the reader.
 */
enum class ReaderAnnotationColor(
    val id: String,
    val title: String,
    /** Highlight tint with the iOS alpha already applied. */
    val tintArgb: Long
) {
    YELLOW("yellow", "琥珀", 0x70E6A829),
    BLUE("blue", "海蓝", 0x57388CE0),
    MINT("mint", "薄荷", 0x6133B094),
    PINK("pink", "珊瑚", 0x52E86E85),
    PURPLE("purple", "紫罗兰", 0x4D876ED1);

    companion object {
        val DEFAULT = YELLOW

        fun fromId(value: String?): ReaderAnnotationColor =
            entries.firstOrNull { it.id.equals(value?.trim(), ignoreCase = true) } ?: DEFAULT
    }
}
