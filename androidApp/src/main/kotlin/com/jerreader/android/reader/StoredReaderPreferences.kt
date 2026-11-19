@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.jerreader.android.reader

import com.jerreader.shared.library.ReaderAppearance
import com.jerreader.shared.library.ReaderThemeOption
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.EpubPreferencesSerializer

data class StoredReaderPreferences(
    val epub: EpubPreferences,
    val appearance: ReaderAppearance
)

fun encodeStoredReaderPreferences(
    appearance: ReaderAppearance,
    base: EpubPreferences = EpubPreferences()
): String {
    val epub = appearance.toEpubPreferences(base)
    return JSONObject()
        .put("jerreaderReaderPreferencesVersion", 1)
        .put("readium", JSONObject(EpubPreferencesSerializer().serialize(epub)))
        // The Readium preferences cannot say which paper is selected once a
        // custom colour covers it (and 冷灰 is itself only a colour), so the
        // choice is kept by name. Without it, switching the custom colour off
        // after reopening the book fell back to the white paper.
        .put("theme", appearance.theme.name)
        .put("customBackgroundHex", appearance.customBackgroundHex)
        .put("customSelectionColorHex", appearance.customSelectionColorHex)
        .put("pdfPaperModeEnabled", appearance.pdfPaperModeEnabled)
        .toString()
}

fun decodeStoredReaderPreferences(
    json: String?,
    fallback: ReaderAppearance
): StoredReaderPreferences {
    if (json.isNullOrBlank()) {
        val epub = fallback.toEpubPreferences()
        return StoredReaderPreferences(epub, fallback)
    }
    return runCatching {
        val root = JSONObject(json)
        val readiumObject = root.optJSONObject("readium")
        if (readiumObject == null) {
            val epub = EpubPreferencesSerializer().deserialize(json)
            return@runCatching StoredReaderPreferences(epub, epub.toReaderAppearance())
        }
        val epub = EpubPreferencesSerializer().deserialize(readiumObject.toString())
        val base = epub.toReaderAppearance()
        StoredReaderPreferences(
            epub = epub,
            appearance = base.copy(
                theme = ReaderThemeOption.entries
                    .firstOrNull { it.name == root.optString("theme") }
                    ?: base.theme,
                customBackgroundHex = root.optString("customBackgroundHex"),
                customSelectionColorHex = root.optString("customSelectionColorHex"),
                pdfPaperModeEnabled = root.optBoolean("pdfPaperModeEnabled", false)
            )
        )
    }.getOrElse {
        val epub = fallback.toEpubPreferences()
        StoredReaderPreferences(epub, fallback)
    }
}
