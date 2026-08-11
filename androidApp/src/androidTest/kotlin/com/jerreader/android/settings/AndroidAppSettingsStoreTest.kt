package com.jerreader.android.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jerreader.unified.design.JerreaderAppearanceMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAppSettingsStoreTest {

    @Test
    fun appearanceModePersistsAndRestoresFromBackup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AndroidAppSettingsStore(context)
        val original = store.preferences.value.appearanceMode

        try {
            store.updateAppearanceMode(JerreaderAppearanceMode.DARK)
            assertEquals(
                JerreaderAppearanceMode.DARK,
                AndroidAppSettingsStore(context).preferences.value.appearanceMode
            )

            store.restoreFromBackup(JSONObject().put("appearanceMode", "light"))
            assertEquals(JerreaderAppearanceMode.LIGHT, store.preferences.value.appearanceMode)
            assertEquals(
                JerreaderAppearanceMode.LIGHT,
                AndroidAppSettingsStore(context).preferences.value.appearanceMode
            )
        } finally {
            store.updateAppearanceMode(original)
        }
    }

    @Test
    fun learningModuleVisibilityPersistsAndOldBackupKeepsCurrentChoice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = AndroidAppSettingsStore(context)
        val original = store.preferences.value.learningModuleVisible

        try {
            store.updateLearningModuleVisible(false)
            assertEquals(
                false,
                AndroidAppSettingsStore(context).preferences.value.learningModuleVisible
            )

            // A pre-1.5 settings payload must not unexpectedly show the module.
            store.restoreFromBackup(JSONObject())
            assertEquals(false, store.preferences.value.learningModuleVisible)

            store.restoreFromBackup(JSONObject().put("learningModuleVisible", true))
            assertEquals(true, store.preferences.value.learningModuleVisible)
        } finally {
            store.updateLearningModuleVisible(original)
        }
    }
}
