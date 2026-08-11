package com.jerreader.unified.translation

/** Which service answers a translation, and what answers it if that one fails. */
data class TranslationProviderPlan(
    val primary: TranslationProviderMode,
    val fallback: TranslationProviderMode?
)

/**
 * Chooses the service a reader-initiated translation actually goes to.
 *
 * Android's `providerMode` defaults to the on-device ML Kit model, because that
 * is the one path that works before anything is configured. It then stayed the
 * chosen provider forever: a reader could enter a DeepSeek key, watch the
 * standalone translate page answer beautifully, and still get ML Kit's
 * 「几乎不可用」 output every time they tapped a sentence in a book — the key
 * they had entered was used for grammar explanations and nothing else. The
 * setting has no "automatic" case to select, so the promotion happens here.
 *
 * The on-device model is not discarded, it is demoted: it becomes the fallback,
 * which is what keeps tap-to-translate working on a train with no signal.
 */
object TranslationProviderPolicy {

    fun plan(
        preferences: TranslationPreferences,
        hasDirectApiCredential: Boolean,
        hasBackendEndpoint: Boolean
    ): TranslationProviderPlan {
        val chosen = preferences.providerMode
        val declaredFallback = preferences.fallbackMode.providerMode
            ?.takeIf { it != chosen }

        if (chosen != TranslationProviderMode.ON_DEVICE) {
            return TranslationProviderPlan(chosen, declaredFallback)
        }

        // An explicitly configured AI fallback is already the reader saying
        // which service should answer when the on-device model cannot; promote
        // that one rather than guessing from what happens to be configured.
        val promoted = preferences.fallbackMode.providerMode
            ?.takeIf { it != TranslationProviderMode.ON_DEVICE }
            ?: automaticPromotion(preferences, hasDirectApiCredential, hasBackendEndpoint)

        return if (promoted == null) {
            TranslationProviderPlan(TranslationProviderMode.ON_DEVICE, declaredFallback)
        } else {
            TranslationProviderPlan(promoted, TranslationProviderMode.ON_DEVICE)
        }
    }

    private fun automaticPromotion(
        preferences: TranslationPreferences,
        hasDirectApiCredential: Boolean,
        hasBackendEndpoint: Boolean
    ): TranslationProviderMode? {
        if (!preferences.preferAIWhenConfigured) return null
        // A key without an endpoint, or an endpoint without a model, is a
        // half-finished setup: promoting to it would replace poor translations
        // with an error, which is worse.
        if (hasDirectApiCredential &&
            preferences.directEndpoint.isNotBlank() &&
            preferences.directModel.isNotBlank()
        ) {
            return TranslationProviderMode.DIRECT_API
        }
        if (hasBackendEndpoint && preferences.backendEndpoint.isNotBlank()) {
            return TranslationProviderMode.BACKEND_PROXY
        }
        return null
    }
}
