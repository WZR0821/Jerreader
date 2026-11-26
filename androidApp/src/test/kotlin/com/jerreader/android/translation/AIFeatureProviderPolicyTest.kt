package com.jerreader.android.translation

import com.jerreader.android.translation.chatgptplan.ChatGPTPlan
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanEntries
import com.jerreader.android.translation.chatgptplan.ChatGPTPlanModel
import com.jerreader.shared.translation.TranslationProviderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「已配置 AI」 of 2.1.0 §7: a signed-in ChatGPT plan answers the AI features
 * when the settings give them nothing that can be called, and never takes
 * them from a service the user chose that works.
 */
class AIFeatureProviderPolicyTest {
    @Test fun aSignedInPlanAnswersWhenNothingElseIsSetUp() {
        // The plan is in the list but not in use: the keyless default still is.
        val settings = withPlan(ChatGPTPlanEntries.merged(null, listOf(ChatGPTPlanModel("gpt-a", "GPT A"))))
        assertEquals("deepSeek", settings.activeID)
        val plan = AIFeatureProviderPolicy.standInPlan(
            preferred = null, preferredCallable = false, providers = settings, planSignedIn = true
        )
        assertEquals(ChatGPTPlan.PROVIDER_ID, plan?.id)
        assertEquals(AIProviderKind.CHATGPT_PLAN, plan?.kind)
        assertEquals("gpt-a", plan?.activeModel?.id)
    }

    @Test fun anAIProviderInUseThatCannotBeCalledGivesWayToTheSignedInPlan() {
        val settings = withPlan(ChatGPTPlanEntries.merged(null, listOf(ChatGPTPlanModel("gpt-a", "GPT A"))))
        val plan = AIFeatureProviderPolicy.standInPlan(
            preferred = TranslationProviderMode.DIRECT_API, preferredCallable = false, providers = settings, planSignedIn = true
        )
        assertEquals(ChatGPTPlan.PROVIDER_ID, plan?.id)
    }

    @Test fun aServiceTheUserChoseKeepsTheAIFeatures() {
        val settings = withPlan(ChatGPTPlanEntries.merged(null, listOf(ChatGPTPlanModel("gpt-a", "GPT A"))))
        assertNull(
            AIFeatureProviderPolicy.standInPlan(
                preferred = TranslationProviderMode.DIRECT_API, preferredCallable = true, providers = settings, planSignedIn = true
            )
        )
        // Only the AI 大模型 service gives way; the AI 代理 answers for itself.
        for (callable in listOf(true, false)) {
            assertNull(
                AIFeatureProviderPolicy.standInPlan(
                    preferred = TranslationProviderMode.BACKEND_PROXY, preferredCallable = callable, providers = settings, planSignedIn = true
                )
            )
        }
    }

    @Test fun noPlanStandsInWhileSignedOutWithoutAModelOrMissing() {
        val usable = withPlan(ChatGPTPlanEntries.merged(null, listOf(ChatGPTPlanModel("gpt-a", "GPT A"))))
        assertNull(
            AIFeatureProviderPolicy.standInPlan(preferred = null, preferredCallable = false, providers = usable, planSignedIn = false)
        )
        // Signed in while the account's models could not be fetched: nothing to call yet.
        val withoutModel = withPlan(ChatGPTPlanEntries.merged(null, emptyList()))
        assertEquals("", withoutModel.entry(ChatGPTPlan.PROVIDER_ID)?.model)
        assertNull(
            AIFeatureProviderPolicy.standInPlan(preferred = null, preferredCallable = false, providers = withoutModel, planSignedIn = true)
        )
        assertNull(
            AIFeatureProviderPolicy.standInPlan(
                preferred = null, preferredCallable = false, providers = AIProviderSettings.fresh, planSignedIn = true
            )
        )
    }

    private fun withPlan(plan: AIProviderEntry): AIProviderSettings {
        val fresh = AIProviderSettings.fresh
        return AIProviderSettings(fresh.activeID, fresh.entries + plan)
    }
}
