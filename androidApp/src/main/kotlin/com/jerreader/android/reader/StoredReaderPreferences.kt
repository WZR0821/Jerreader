@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.jerreader.android.reader

import com.jerreader.unified.library.ReaderAppearance
import com.jerreader.unified.library.ReaderTextOrientation
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
        return StoredReaderPreferences(epub, fallback).withoutForcedVerticalText()
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
                customBackgroundHex = root.optString("customBackgroundHex"),
                customSelectionColorHex = root.optString("customSelectionColorHex"),
                pdfPaperModeEnabled = root.optBoolean("pdfPaperModeEnabled", false)
            )
        )
    }.getOrElse {
        val epub = fallback.toEpubPreferences()
        StoredReaderPreferences(epub, fallback)
    }.withoutForcedVerticalText()
}

/**
 * 竖排 was removed from the 版式 pickers, so a book last read in it would open
 * in a mode the segmented control cannot even show as selected, and forcing
 * `verticalText` on a horizontal book is what the reader was trying to leave.
 * 原书 already renders a vertically typeset book vertically, which is what that
 * setting was reached for, so the saved preference migrates to it.
 */
private fun StoredReaderPreferences.withoutForcedVerticalText(): StoredReaderPreferences {
    if (appearance.orientation != ReaderTextOrientation.VERTICAL) return this
    return StoredReaderPreferences(
        epub = epub.copy(verticalText = null),
        appearance = appearance.copy(orientation = ReaderTextOrientation.PUBLICATION)
    )
}
