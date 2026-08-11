package com.jerreader.unified.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranslationProviderPolicyTest {

    private fun plan(
        preferences: TranslationPreferences,
        key: Boolean = false,
        backend: Boolean = false
    ) = TranslationProviderPolicy.plan(preferences, key, backend)

    @Test
    fun `with nothing configured the on-device model still answers`() {
        val plan = plan(TranslationPreferences())
        assertEquals(TranslationProviderMode.ON_DEVICE, plan.primary)
        assertNull(plan.fallback)
    }

    @Test
    fun `a configured api key is preferred and the on-device model becomes the fallback`() {
        val plan = plan(TranslationPreferences(), key = true)
        assertEquals(TranslationProviderMode.DIRECT_API, plan.primary)
        assertEquals(TranslationProviderMode.ON_DEVICE, plan.fallback)
    }

    @Test
    fun `a configured backend is preferred when there is no api key`() {
        val preferences = TranslationPreferences(backendEndpoint = "https://example.test/translate")
        val plan = plan(preferences, backend = true)
        assertEquals(TranslationProviderMode.BACKEND_PROXY, plan.primary)
        assertEquals(TranslationProviderMode.ON_DEVICE, plan.fallback)
    }

    @Test
    fun `a half-finished setup is not promoted`() {
        // Replacing a poor translation with 「配置缺失」 is not an improvement.
        assertEquals(
            TranslationProviderMode.ON_DEVICE,
            plan(TranslationPreferences(directModel = ""), key = true).primary
        )
        assertEquals(
            TranslationProviderMode.ON_DEVICE,
            plan(TranslationPreferences(directEndpoint = ""), key = true).primary
        )
        assertEquals(
            TranslationProviderMode.ON_DEVICE,
            plan(TranslationPreferences(backendEndpoint = ""), backend = true).primary
        )
    }

    @Test
    fun `turning the preference off pins the reader to the on-device model`() {
        val preferences = TranslationPreferences(preferAIWhenConfigured = false)
        val plan = plan(preferences, key = true, backend = true)
        assertEquals(TranslationProviderMode.ON_DEVICE, plan.primary)
        assertNull(plan.fallback)
    }

    @Test
    fun `an explicitly chosen provider is never second-guessed`() {
        val preferences = TranslationPreferences(
            providerMode = TranslationProviderMode.BACKEND_PROXY,
            fallbackMode = TranslationFallbackMode.ON_DEVICE
        )
        val plan = plan(preferences, key = true)
        assertEquals(TranslationProviderMode.BACKEND_PROXY, plan.primary)
        assertEquals(TranslationProviderMode.ON_DEVICE, plan.fallback)
    }

    @Test
    fun `a declared ai fallback is what gets promoted`() {
        val preferences = TranslationPreferences(
            fallbackMode = TranslationFallbackMode.BACKEND_PROXY,
            backendEndpoint = "https://example.test/translate"
        )
        // Even with a direct key present: the reader named this one.
        val plan = plan(preferences, key = true, backend = true)
        assertEquals(TranslationProviderMode.BACKEND_PROXY, plan.primary)
        assertEquals(TranslationProviderMode.ON_DEVICE, plan.fallback)
    }

    @Test
    fun `a plan never falls back to the service that just failed`() {
        val preferences = TranslationPreferences(
            providerMode = TranslationProviderMode.DIRECT_API,
            fallbackMode = TranslationFallbackMode.DIRECT_API
        )
        assertNull(plan(preferences).fallback)
    }
}
