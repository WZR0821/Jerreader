package com.jerreader.android.reader

import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.jerreader.android.JerreaderApplication
import com.jerreader.android.lexical.DictionaryHttpClient
import com.jerreader.android.lexical.WiktionaryLexicalLookupService
import com.jerreader.android.library.PublicationIntegrity
import com.jerreader.android.test.createSyntheticEpub
import com.jerreader.unified.domain.LanguageCode
import com.jerreader.unified.lexical.MockLexicalLookupService
import com.jerreader.unified.lexical.WordSelectionSource
import com.jerreader.unified.ui.WordLookupCardState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFragment

class ReaderWordLookupTest {
    @Test
    fun shortTapSelectsEnglishAndJapaneseWordsAndShowsChineseDefinitions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context.applicationContext as JerreaderApplication
        val graph = application.graph
        val source = File(context.cacheDir, "jerreader-m4-${UUID.randomUUID()}.epub")
        createSyntheticEpub(source)
        source.setLastModified(1_700_400_000_000)
        val sourceSnapshot = PublicationIntegrity.capture(source)
        val imported = graph.importService.importEpub(Uri.fromFile(source))
        val book = imported.book
        val stored = graph.publicationStore.resolvePublication(book.publicationFileName)
        val storedSnapshot = PublicationIntegrity.capture(stored)
        val intent = Intent(context, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)

        try {
            ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                val activity = waitForActivityAndNavigator(scenario)

                val englishPoint = elementCenter(activity, "english-word")
                val english = withContext(Dispatchers.Main) {
                    activity.lookupAtForTesting(englishPoint)
                }
                assertTrue("English lookup returned $english", english is WordLookupCardState.Success)
                english as WordLookupCardState.Success
                assertEquals("went", english.analysis.surfaceForm.lowercase())
                assertEquals("go", english.analysis.lemma)
                assertEquals(WordSelectionSource.SHORT_TAP, english.analysis.source)
                assertTrue("去" in english.explanation.definitions)

                val readiumPixelPoint = elementCenter(
                    activity,
                    "english-word",
                    readiumPixels = true
                )
                val fromRealTapSpace = withContext(Dispatchers.Main) {
                    activity.lookupAtDevicePointForTesting(readiumPixelPoint)
                }
                assertTrue(
                    "Readium-pixel lookup returned $fromRealTapSpace",
                    fromRealTapSpace is WordLookupCardState.Success
                )
                fromRealTapSpace as WordLookupCardState.Success
                assertEquals("went", fromRealTapSpace.analysis.surfaceForm.lowercase())

                val japanesePoint = elementCenter(activity, "japanese-word")
                val japanese = withContext(Dispatchers.Main) {
                    activity.lookupAtForTesting(japanesePoint)
                }
                assertTrue("Japanese lookup returned $japanese", japanese is WordLookupCardState.Success)
                japanese as WordLookupCardState.Success
                assertEquals("食べました", japanese.analysis.surfaceForm)
                assertEquals("食べる", japanese.analysis.lemma)
                assertTrue("吃" in japanese.explanation.definitions)

                val longPressFallback = withContext(Dispatchers.Main) {
                    activity.analyzeSelectedTextForTesting(
                        text = "読みました",
                        language = LanguageCode.JAPANESE
                    )
                }
                assertNotNull(longPressFallback)
                assertEquals("読む", longPressFallback?.lemma)
                assertEquals(WordSelectionSource.NATIVE_SELECTION, longPressFallback?.source)
            }

            assertTrue(PublicationIntegrity.isUnchanged(sourceSnapshot))
            assertTrue(PublicationIntegrity.isUnchanged(storedSnapshot))
        } finally {
            graph.bookService.delete(book.id)
            source.delete()
        }
    }

    @Test
    fun androidTokenizerUsesLocaleBoundariesAndExcludesPunctuation() {
        val tokenizer = AndroidWordBoundaryTokenizer()
        val english = tokenizer.tokenize("Hello, quiet night!", LanguageCode.ENGLISH)
        assertEquals(listOf("Hello", "quiet", "night"), english.map { it.text })

        val japanese = tokenizer.tokenize("本を読みました。", LanguageCode.JAPANESE)
        assertTrue(japanese.isNotEmpty())
        assertTrue(japanese.none { token -> token.text == "。" })
        assertTrue(japanese.all { token -> token.text.any(Char::isLetter) })
    }

    @Test
    fun realDictionaryAdapterParsesStubbedChineseDefinitionsWithoutNetwork() = runBlocking {
        val wikitext = """
            ==英语==
            ===动词===
            # [[去]]；前往
            # 移动到另一个地点
        """.trimIndent()
        val service = WiktionaryLexicalLookupService(
            client = DictionaryHttpClient {
                """{"parse":{"title":"go","wikitext":${JSONObject.quote(wikitext)}}}"""
            }
        )

        val result = service.lookup(
            word = "go",
            sentenceContext = "We go home.",
            language = LanguageCode.ENGLISH
        )

        assertEquals("动词", result.partOfSpeech)
        assertEquals(listOf("去；前往", "移动到另一个地点"), result.definitions)
        assertEquals("zh-wiktionary-v1", result.providerIdentifier)
    }

    @Test
    fun mockDictionaryRemainsAvailableForOfflineTests() = runBlocking {
        val result = MockLexicalLookupService().lookup(
            word = "went",
            sentenceContext = "She went home.",
            language = LanguageCode.ENGLISH,
            candidates = listOf("go")
        )
        assertEquals("go", result.lemma)
        assertTrue("去" in result.definitions)
        assertEquals("mock-lexical-v1", result.providerIdentifier)
    }

    private fun waitForActivityAndNavigator(
        scenario: ActivityScenario<ReaderActivity>
    ): ReaderActivity {
        var ready: ReaderActivity? = null
        repeat(100) {
            scenario.onActivity { activity ->
                val navigatorReady = activity.supportFragmentManager.fragments.any { fragment ->
                    fragment is EpubNavigatorFragment && fragment.view != null
                }
                if (navigatorReady) ready = activity
            }
            ready?.let { return it }
            SystemClock.sleep(100)
        }
        error("ReaderActivity did not attach Readium's EPUB navigator")
    }

    private suspend fun elementCenter(
        activity: ReaderActivity,
        id: String,
        readiumPixels: Boolean = false
    ): PointF {
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
                      const scale = ${if (readiumPixels) "window.devicePixelRatio" else "1"};
                      return JSON.stringify({
                        x: (rect.left + rect.width / 2) * scale,
                        y: (rect.top + rect.height / 2) * scale
                      });
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
