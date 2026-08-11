package com.jerreader.unified.domain

enum class LanguageCode(val tag: String) {
    CHINESE_SIMPLIFIED("zh-Hans"),
    ENGLISH("en"),
    JAPANESE("ja");

    companion object {
        fun fromTag(tag: String): LanguageCode? =
            entries.firstOrNull { language -> language.tag.equals(tag, ignoreCase = true) }
    }
}
