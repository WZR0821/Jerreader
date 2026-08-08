package com.jerreader.android.reader

import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.jerreader.android.JerreaderApplication
import com.jerreader.android.library.PublicationIntegrity
import com.jerreader.android.test.createSyntheticEpub
import com.jerreader.shared.translation.MockTranslationService
import com.jerreader.shared.translation.QuickTranslationUnit
import com.jerreader.shared.ui.TranslationCardState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFragment

class ReaderTapTranslationTest {
    @Test
    fun tapTranslatesSentenceOrParagraphWithoutChangingEpub() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context.applicationContext as JerreaderApplication
        val graph = application.graph
        val originalService = graph.translationService
        val source = File(context.cacheDir, "jerreader-tap-translation-${UUID.randomUUID()}.epub")
        createSyntheticEpub(source)
        source.setLastModified(1_700_500_000_000)
        val sourceSnapshot = PublicationIntegrity.capture(source)
        val book = graph.importService.importPublication(Uri.fromFile(source)).book
        val stored = graph.publicationStore.resolvePublication(book.publicationFileName)
        val storedSnapshot = PublicationIntegrity.capture(stored)

        try {
            graph.translationService = MockTranslationService()
            graph.translationSettings.updateQuickTranslationEnabled(true)
            graph.translationSettings.updateQuickTranslationUnit(QuickTranslationUnit.SENTENCE)
            val intent = Intent(context, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)

            ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                val activity = waitForActivityAndNavigator(scenario)
                val point = elementCenter(activity, "english-word")

                val sentence = withContext(Dispatchers.Main) {
                    activity.translateAtForTesting(point)
                }
                assertTrue("Expected sentence translation, got $sentence", sentence is TranslationCardState.Success)
                sentence as TranslationCardState.Success
                assertEquals("She went home.", sentence.sourceText)
                assertEquals("[测试译文] She went home.", sentence.result.translatedText)

                graph.translationSettings.updateQuickTranslationUnit(QuickTranslationUnit.PARAGRAPH)
                val paragraph = withContext(Dispatchers.Main) {
                    activity.translateAtForTesting(point)
                }
                assertTrue("Expected paragraph translation, got $paragraph", paragraph is TranslationCardState.Success)
                paragraph as TranslationCardState.Success
                assertEquals("She went home. Then she opened a book.", paragraph.sourceText)

                // Readium delivers taps in navigator pixels, not CSS pixels. The
                // reader must convert them, otherwise a real tap resolves no caret.
                graph.translationSettings.updateQuickTranslationUnit(QuickTranslationUnit.SENTENCE)
                val density = context.resources.displayMetrics.density
                val devicePoint = PointF(point.x * density, point.y * density)
                val fromDeviceTap = withContext(Dispatchers.Main) {
                    activity.translateAtDevicePointForTesting(devicePoint)
                }
                assertTrue(
                    "Expected a device-pixel tap to translate, got $fromDeviceTap",
                    fromDeviceTap is TranslationCardState.Success
                )
                fromDeviceTap as TranslationCardState.Success
                assertEquals("She went home.", fromDeviceTap.sourceText)
            }

            assertTrue(PublicationIntegrity.isUnchanged(sourceSnapshot))
            assertTrue(PublicationIntegrity.isUnchanged(storedSnapshot))
        } finally {
            graph.translationService = originalService
            graph.translationSettings.updateQuickTranslationUnit(QuickTranslationUnit.SENTENCE)
            graph.bookService.delete(book.id)
            source.delete()
        }
    }

    private fun waitForActivityAndNavigator(
        scenario: ActivityScenario<ReaderActivity>
    ): ReaderActivity {
        var ready: ReaderActivity? = null
        repeat(100) {
            scenario.onActivity { activity ->
                if (activity.supportFragmentManager.fragments.any {
                        it is EpubNavigatorFragment && it.view != null
                    }
                ) ready = activity
            }
            ready?.let { return it }
            SystemClock.sleep(100)
        }
        error("ReaderActivity did not attach Readium's EPUB navigator")
    }

    private suspend fun elementCenter(activity: ReaderActivity, id: String): PointF {
        repeat(100) {
            val raw = withContext(Dispatchers.Main) {
                val navigator = activity.supportFragmentManager.fragments
                    .filterIsInstance<EpubNavigatorFragment>()
                    .first()
                navigator.evaluateJavascript(
                    """
                    (() => {
                      const element = document.getElementById('$id');
                      if (!element) return null;
                      const rect = element.getBoundingClientRect();
                      return JSON.stringify({x: rect.left + rect.width / 2, y: rect.top + rect.height / 2});
                    })()
                    """.trimIndent()
                )
            }
            raw?.let(::decodeJsonObject)?.let { json ->
                return PointF(json.getDouble("x").toFloat(), json.getDouble("y").toFloat())
            }
            SystemClock.sleep(100)
        }
        error("Readium did not expose the test element position")
    }

    private fun decodeJsonObject(raw: String): JSONObject? {
        if (raw == "null") return null
        return when (val decoded = JSONTokener(raw).nextValue()) {
            is JSONObject -> decoded
            is String -> JSONObject(decoded)
            else -> null
        }
    }
}
