package com.jerreader.android.translation

import com.jerreader.unified.translation.TranslationProviderMode

/**
 * A provider and prompt chosen on the "学习 → 翻译" page for one request. It is
 * deliberately passed per call rather than written to
 * [AndroidTranslationSettingsStore]: experimenting with a service there must not
 * silently change what the reader uses, which is how iOS behaves.
 */
data class StandaloneTranslationOverride(
    val providerMode: TranslationProviderMode,
    val promptTemplate: String? = null
)
