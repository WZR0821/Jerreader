package com.jerreader.android.settings

import android.content.Context
import com.jerreader.unified.design.JerreaderAppearanceMode
import com.jerreader.unified.library.ReaderAppearance
import com.jerreader.unified.library.ReaderColorPreset
import com.jerreader.unified.library.ReaderColorPresetStore
import com.jerreader.unified.library.ReaderFontOption
import com.jerreader.unified.library.ReaderTextOrientation
import com.jerreader.unified.library.ReaderThemeOption
import com.jerreader.unified.ui.JerreaderAccent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// The visual identity is shared with iOS, so the five accents come straight
// from `JerreaderTheme` instead of a second Android-only palette.
typealias AppThemeChoice = JerreaderAccent

data class AndroidAppPreferences(
    val theme: AppThemeChoice = AppThemeChoice.OCEAN,
    val appearanceMode: JerreaderAppearanceMode = JerreaderAppearanceMode.SYSTEM,
    val defaultReaderAppearance: ReaderAppearance = ReaderAppearance(
        font = ReaderFontOption.SERIF
    ),
    val showReadingProgress: Boolean = true,
    /** Hides only the top-level learning module; records and reader lookup stay intact. */
    val learningModuleVisible: Boolean = true,
    val applyReaderDefaultsToExistingBooks: Boolean = false,
    /** User-saved background + selection colour sets, shared with iOS. */
    val colorPresets: List<ReaderColorPreset> = emptyList()
)

class AndroidAppSettingsStore(context: Context) {
    private val storage = context.getSharedPreferences("jerreader_app_settings", Context.MODE_PRIVATE)
    private val mutablePreferences = MutableStateFlow(load())
    val preferences: StateFlow<AndroidAppPreferences> = mutablePreferences.asStateFlow()

    fun updateTheme(theme: AppThemeChoice) {
        storage.edit().putString(KEY_THEME, theme.name).apply()
        mutablePreferences.value = mutablePreferences.value.copy(theme = theme)
    }

    fun updateAppearanceMode(mode: JerreaderAppearanceMode) {
        storage.edit().putString(KEY_APPEARANCE_MODE, mode.id).apply()
        mutablePreferences.value = mutablePreferences.value.copy(appearanceMode = mode)
    }

    fun updateReaderAppearance(appearance: ReaderAppearance) {
        storage.edit()
            .putFloat(KEY_FONT_SCALE, appearance.fontScale.toFloat())
            .putString(KEY_READER_THEME, appearance.theme.name)
            .putBoolean(KEY_SCROLL, appearance.scroll)
            .putString(KEY_FONT, appearance.font.name)
            .putFloat(KEY_LINE_HEIGHT, appearance.lineHeight.toFloat())
            .putFloat(KEY_PARAGRAPH_SPACING, appearance.paragraphSpacing.toFloat())
            .putFloat(KEY_PAGE_MARGINS, appearance.pageMargins.toFloat())
            .putString(KEY_ORIENTATION, appearance.orientation.name)
            .putString(KEY_CUSTOM_BACKGROUND, appearance.customBackgroundHex)
            .putString(KEY_CUSTOM_SELECTION, appearance.customSelectionColorHex)
            .putBoolean(KEY_PDF_PAPER_MODE, appearance.pdfPaperModeEnabled)
            .apply()
        mutablePreferences.value = mutablePreferences.value.copy(
            defaultReaderAppearance = appearance
        )
    }

    fun updateShowReadingProgress(value: Boolean) {
        storage.edit().putBoolean(KEY_SHOW_PROGRESS, value).apply()
        mutablePreferences.value = mutablePreferences.value.copy(showReadingProgress = value)
    }

    fun updateLearningModuleVisible(value: Boolean) {
        storage.edit().putBoolean(KEY_LEARNING_MODULE_VISIBLE, value).apply()
        mutablePreferences.value = mutablePreferences.value.copy(
            learningModuleVisible = value
        )
    }

    fun saveColorPreset(name: String, appearance: ReaderAppearance) {
        // The overload that resolves the pair from what the page is showing:
        // a set saved off a built-in theme, or off a background with no
        // hand-picked selection colour, used to be silently dropped here.
        updateColorPresets(
            ReaderColorPresetStore.added(
                existing = mutablePreferences.value.colorPresets,
                appearance = appearance,
                name = name
            )
        )
    }

    fun removeColorPreset(id: String) {
        updateColorPresets(
            ReaderColorPresetStore.removed(mutablePreferences.value.colorPresets, id)
        )
    }

    private fun updateColorPresets(presets: List<ReaderColorPreset>) {
        storage.edit()
            .putString(KEY_COLOR_PRESETS, ReaderColorPresetStore.encode(presets))
            .apply()
        mutablePreferences.value = mutablePreferences.value.copy(colorPresets = presets)
    }

    fun updateApplyReaderDefaultsToExistingBooks(value: Boolean) {
        storage.edit().putBoolean(KEY_APPLY_TO_EXISTING, value).apply()
        mutablePreferences.value = mutablePreferences.value.copy(
            applyReaderDefaultsToExistingBooks = value
        )
    }

    /**
     * Applies the non-sensitive preferences held in a backup archive. Anything
     * absent from the payload keeps its current value, so an archive written by
     * an older version cannot reset settings it never knew about.
     */
    fun restoreFromBackup(payload: org.json.JSONObject) {
        val current = mutablePreferences.value
        val appearance = payload.optJSONObject("readerAppearance")
        val restored = AndroidAppPreferences(
            theme = enumValue(payload.optString("theme"), current.theme),
            appearanceMode = JerreaderAppearanceMode.fromId(
                payload.optString("appearanceMode", current.appearanceMode.id)
            ),
            defaultReaderAppearance = appearance?.let {
                ReaderAppearance(
                    fontScale = it.optDouble("fontScale", current.defaultReaderAppearance.fontScale),
                    theme = enumValue(it.optString("theme"), current.defaultReaderAppearance.theme),
                    scroll = it.optBoolean("scroll", current.defaultReaderAppearance.scroll),
                    font = enumValue(it.optString("font"), current.defaultReaderAppearance.font),
                    lineHeight = it.optDouble(
                        "lineHeight",
                        current.defaultReaderAppearance.lineHeight
                    ),
                    paragraphSpacing = it.optDouble(
                        "paragraphSpacing",
                        current.defaultReaderAppearance.paragraphSpacing
                    ),
                    pageMargins = it.optDouble(
                        "pageMargins",
                        current.defaultReaderAppearance.pageMargins
                    ),
                    orientation = enumValue(
                        it.optString("orientation"),
                        current.defaultReaderAppearance.orientation
                    ),
                    customBackgroundHex = it.optString(
                        "customBackgroundHex",
                        current.defaultReaderAppearance.customBackgroundHex
                    ),
                    customSelectionColorHex = it.optString(
                        "customSelectionColorHex",
                        current.defaultReaderAppearance.customSelectionColorHex
                    ),
                    pdfPaperModeEnabled = it.optBoolean(
                        "pdfPaperModeEnabled",
                        current.defaultReaderAppearance.pdfPaperModeEnabled
                    )
                )
            } ?: current.defaultReaderAppearance,
            showReadingProgress = payload.optBoolean(
                "showReadingProgress",
                current.showReadingProgress
            ),
            learningModuleVisible = payload.optBoolean(
                "learningModuleVisible",
                current.learningModuleVisible
            ),
            applyReaderDefaultsToExistingBooks = payload.optBoolean(
                "applyReaderDefaultsToExistingBooks",
                current.applyReaderDefaultsToExistingBooks
            ),
            // Written by 1.3.3 and later. An older archive leaves the sets the
            // user already has on this install alone rather than clearing them.
            colorPresets = if (payload.has("colorPresets")) {
                ReaderColorPresetStore.decode(payload.optString("colorPresets"))
            } else {
                current.colorPresets
            }
        )
        updateTheme(restored.theme)
        updateAppearanceMode(restored.appearanceMode)
        updateReaderAppearance(restored.defaultReaderAppearance)
        updateShowReadingProgress(restored.showReadingProgress)
        updateLearningModuleVisible(restored.learningModuleVisible)
        updateApplyReaderDefaultsToExistingBooks(restored.applyReaderDefaultsToExistingBooks)
        updateColorPresets(restored.colorPresets)
    }

    private fun load(): AndroidAppPreferences = AndroidAppPreferences(
        theme = enumValue(storage.getString(KEY_THEME, null), JerreaderAccent.OCEAN),
        appearanceMode = JerreaderAppearanceMode.fromId(
            storage.getString(KEY_APPEARANCE_MODE, null).orEmpty()
        ),
        defaultReaderAppearance = ReaderAppearance(
            fontScale = storage.getFloat(KEY_FONT_SCALE, 1f).toDouble(),
            theme = enumValue(storage.getString(KEY_READER_THEME, null), ReaderThemeOption.LIGHT),
            scroll = storage.getBoolean(KEY_SCROLL, false),
            font = enumValue(storage.getString(KEY_FONT, null), ReaderFontOption.SERIF),
            lineHeight = storage.getFloat(KEY_LINE_HEIGHT, 1.4f).toDouble(),
            paragraphSpacing = storage.getFloat(KEY_PARAGRAPH_SPACING, 0f).toDouble(),
            pageMargins = storage.getFloat(KEY_PAGE_MARGINS, 1f).toDouble(),
            // 竖排 was removed from the picker, so a setting saved by an older
            // build would otherwise leave the reader in a mode with no control
            // to leave it by. 原书 already renders a vertically typeset book
            // vertically, which is what that setting was reached for.
            orientation = enumValue(
                storage.getString(KEY_ORIENTATION, null),
                ReaderTextOrientation.PUBLICATION
            ).takeUnless { it == ReaderTextOrientation.VERTICAL }
                ?: ReaderTextOrientation.PUBLICATION,
            customBackgroundHex = storage.getString(KEY_CUSTOM_BACKGROUND, "").orEmpty(),
            customSelectionColorHex = storage.getString(KEY_CUSTOM_SELECTION, "").orEmpty(),
            pdfPaperModeEnabled = storage.getBoolean(KEY_PDF_PAPER_MODE, false)
        ),
        showReadingProgress = storage.getBoolean(KEY_SHOW_PROGRESS, true),
        learningModuleVisible = storage.getBoolean(KEY_LEARNING_MODULE_VISIBLE, true),
        applyReaderDefaultsToExistingBooks = storage.getBoolean(KEY_APPLY_TO_EXISTING, false),
        colorPresets = ReaderColorPresetStore.decode(
            storage.getString(KEY_COLOR_PRESETS, "").orEmpty()
        )
    )

    private inline fun <reified T : Enum<T>> enumValue(raw: String?, default: T): T =
        raw?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default

    private companion object {
        const val KEY_THEME = "app.theme"
        const val KEY_APPEARANCE_MODE = "app.appearanceMode"
        const val KEY_FONT_SCALE = "reader.fontScale"
        const val KEY_READER_THEME = "reader.theme"
        const val KEY_SCROLL = "reader.scroll"
        const val KEY_FONT = "reader.font"
        const val KEY_LINE_HEIGHT = "reader.lineHeight"
        const val KEY_PARAGRAPH_SPACING = "reader.paragraphSpacing"
        const val KEY_PAGE_MARGINS = "reader.pageMargins"
        const val KEY_ORIENTATION = "reader.orientation"
        const val KEY_CUSTOM_BACKGROUND = "reader.customBackground"
        const val KEY_CUSTOM_SELECTION = "reader.customSelection"
        const val KEY_PDF_PAPER_MODE = "reader.pdfPaperMode"
        const val KEY_SHOW_PROGRESS = "reader.showProgress"
        const val KEY_LEARNING_MODULE_VISIBLE = "feature.learningModuleVisible"
        const val KEY_APPLY_TO_EXISTING = "reader.applyDefaultsToExisting"
        const val KEY_COLOR_PRESETS = "reader.colorPresets"
    }
}
