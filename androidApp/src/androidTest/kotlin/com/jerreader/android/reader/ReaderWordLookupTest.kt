package com.jerreader.android.reader

import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.net.Uri
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.jerreader.android.JerreaderApplication
import com.jerreader.android.lexical.DictionaryHttpClient
import com.jerreader.android.learning.LearningRecordRepository
import com.jerreader.android.lexical.WiktionaryLexicalLookupService
import com.jerreader.android.library.PublicationIntegrity
import com.jerreader.android.test.SHORT_TAP_PHRASES
import com.jerreader.android.test.createSyntheticEpub
import com.jerreader.shared.domain.LanguageCode
import com.jerreader.shared.lexical.MockLexicalLookupService
import com.jerreader.shared.lexical.WordSelectionSource
import com.jerreader.shared.library.ReaderTextOrientation
import com.jerreader.shared.translation.TranslationResult
import com.jerreader.shared.ui.TranslationCardState
import com.jerreader.shared.ui.WordLookupCardState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi

class ReaderWordLookupTest {
    @Test
    fun shortTapSelectsEnglishAndJapaneseWordsAndShowsChineseDefinitions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val application = context.applicationContext as JerreaderApplication
        val graph = application.graph
        val source = File(context.cacheDir, "jerreader-m4-${UUID.randomUUID()}.epub")
        createSyntheticEpub(source, shortTapPhrases = true)
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
                assertTrue(english is WordLookupCardState.Success)
                english as WordLookupCardState.Success
                assertEquals("went", english.analysis.surfaceForm.lowercase())
                assertEquals("go", english.analysis.lemma)
                assertEquals(WordSelectionSource.SHORT_TAP, english.analysis.source)
                // The bundled ECDICT entry gives whole lines, "vi. 去, 走, …".
                assertTrue(
                    "English definitions were ${english.explanation.definitions}",
                    english.explanation.definitions.any { "去" in it }
                )

                val japanesePoint = elementCenter(activity, "japanese-word")
                val japanese = withContext(Dispatchers.Main) {
                    activity.lookupAtForTesting(japanesePoint)
                }
                assertTrue("Japanese lookup returned $japanese", japanese is WordLookupCardState.Success)
                japanese as WordLookupCardState.Success
                assertEquals("食べました", japanese.analysis.surfaceForm)
                assertEquals("食べる", japanese.analysis.lemma)
                assertTrue("吃" in japanese.explanation.definitions)

                // The word is the one under the finger, wherever on the glyph
                // the tap lands. A tap on the trailing half of a glyph puts the
                // caret after it, where, with no space between, the next word
                // starts: 昨日ご飯を is one text node, 昨日|ご飯|を (this
                // WebView splits it 昨日|ご|飯|を, and a lone ご once read as
                // okurigana of 昨日). Each case is (glyph index, fraction along
                // it, the glyph across the edge the caret lands on), which must
                // stay out of the word. を is a particle, not okurigana of 飯.
                val englishEdge = glyphPoint(activity, "english-sentence", 2, 0.75)
                assertEquals(
                    "A tap on the trailing quarter of She's e",
                    "She",
                    withContext(Dispatchers.Main) { activity.shortTapWordAtForTesting(englishEdge) }
                )
                val japaneseText = "昨日ご飯を"
                for ((index, fraction, across) in listOf(Triple(1, 0.75, 2), Triple(2, 0.25, 1), Triple(3, 0.75, 4), Triple(4, 0.25, 3))) {
                    val point = glyphPoint(activity, "japanese-sentence", index, fraction)
                    val word = withContext(Dispatchers.Main) {
                        activity.shortTapWordAtForTesting(point)
                    }.orEmpty()
                    val label = "A tap $fraction along ${japaneseText[index]} selected '$word'"
                    assertTrue(label, japaneseText[index] in word)
                    assertTrue(label, japaneseText[across] !in word)
                }
                // Whichever glyph of 食べました is tapped, the word is the same,
                // however ICU splits it (食べ|ました, 食|べました). The
                // outer halves of its first and last glyphs are left out: there
                // the caret can land in the text node next to it.
                val inflected = "食べました"
                for (index in inflected.indices) {
                    for (fraction in listOf(0.25, 0.75)) {
                        if ((index == 0 && fraction < 0.5) || (index == inflected.lastIndex && fraction > 0.5)) continue
                        val point = glyphPoint(activity, "japanese-word", index, fraction)
                        val word = withContext(Dispatchers.Main) {
                            activity.shortTapWordAtForTesting(point)
                        }
                        assertEquals("A tap $fraction along ${inflected[index]}", inflected, word)
                    }
                }

                // 2.1.0: phrases ICU splits apart. Chromium's Android ICU has no
                // Japanese dictionary (家|にいる), so the reader segments with the
                // platform's ICU (JapaneseWordBreaks) and repairs those segments
                // with iOS's rules: every glyph gives the word iOS gives
                // (ReaderServicesTests has this paragraph). Both segmentations
                // and the words go to learning-ui first.
                val expected = listOf(
                    "ご注意|ご注意|ご注意|ください|ください|ください|ください",
                    "おいし|おいし|おいし|そう|そう|です|です|ね",
                    "悲し|悲し|そう|そう|だ",
                    "帰った|帰った|帰った|らしい|らしい|らしい",
                    "本|を|読ん|読ん|じゃった|じゃった|じゃった|じゃった",
                    "彼|は|振り返った|振り返った|振り返った|振り返った|振り返った",
                    List(8) { "すみませんでした" }.joinToString("|"),
                    "よさ|よさ|そう|そう|だ",
                    "行く|行く|こと|こと|に|した|した",
                    "もし|もし|そう|そう|なら|なら|行く|行く",
                    "雨|が|降り出した|降り出した|降り出した|降り出した|降り出した",
                    "そう|そう|です|です|ね",
                ).flatMap { it.split('|') }
                val picked = mutableListOf<String>()
                for ((index, glyph) in SHORT_TAP_PHRASES.withIndex()) {
                    if (glyph == '\u3000') continue
                    val point = glyphPoint(activity, "tap-phrases", index, 0.4)
                    picked += withContext(Dispatchers.Main) { activity.shortTapWordAtForTesting(point) }.orEmpty()
                }
                val phrasesText = withContext(Dispatchers.Main) {
                    navigator(activity).evaluateJavascript("document.getElementById('tap-phrases').firstChild.data")
                }?.let { raw -> JSONTokener(raw).nextValue() as? String }.orEmpty()
                val webViewSegments = withContext(Dispatchers.Main) {
                    navigator(activity).evaluateJavascript(
                        "Array.from(new Intl.Segmenter('ja', {granularity: 'word'})" +
                            ".segment(document.getElementById('tap-phrases').firstChild.data))" +
                            ".map(s => s.segment + (s.isWordLike ? '' : '°')).join('|')"
                    )
                }?.let { raw -> JSONTokener(raw).nextValue() as? String }
                val platformSegments = JapaneseWordBreaks.describe(phrasesText)
                File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "learning-ui")
                    .apply { mkdirs() }
                    .resolve("short-tap-phrases.json")
                    .writeText(
                        JSONObject().put("segments", platformSegments).put("webViewSegments", webViewSegments)
                            .put("words", JSONArray(picked))
                            .put("probes", JSONObject().apply {
                                PLATFORM_SEGMENT_PROBES.forEach { put(it, JapaneseWordBreaks.describe(it)) }
                            })
                            .toString(2)
                    )
                assertEquals(
                    "Short-tap words over platform ICU segments $platformSegments (WebView: $webViewSegments)",
                    expected,
                    picked
                )

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

    /**
     * 2.1.0: a real long press selects the word that holds the glyph under
     * the finger, on either half of it, in horizontal and in vertical text,
     * and WebView's own selection menu opens. On the second half of a word's
     * last glyph WebView alone took the next word (昨日 gave ご飯), because
     * the caret lands after the glyph, where that word starts; the reader
     * now moves such a press a quarter of the way into the glyph first
     * (ReaderLongPressAim). A press on a first half is left as it is. A
     * press in the blank room a long reading leaves between two kanji of a
     * ruby (玄 くろう, 人 と) holds the nearer kanji, 玄: there a real
     * book's press selected nothing, or a word further on.
     */
    @Test
    fun longPressSelectsTheWordHoldingThePressedGlyph() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = (context.applicationContext as JerreaderApplication).graph
        val source = File(context.cacheDir, "jerreader-longpress-${UUID.randomUUID()}.epub")
        createSyntheticEpub(source, rubyRoomSentence = true)
        val book = graph.importService.importEpub(Uri.fromFile(source)).book
        val intent = Intent(context, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
        try {
            ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                val activity = waitForActivityAndNavigator(scenario)
                val japanese = "昨日ご飯を"
                for (orientation in listOf(ReaderTextOrientation.HORIZONTAL, ReaderTextOrientation.VERTICAL)) {
                    val vertical = setOrientation(activity, orientation)
                    assertEquals("排版方向 $orientation", orientation == ReaderTextOrientation.VERTICAL, vertical)
                    // (glyph, fraction along it, the glyph across the edge), as
                    // in the tap test: 日 and 飯 end their words, ご starts one.
                    for ((index, fraction, across) in listOf(Triple(1, 0.75, 2), Triple(2, 0.25, 1), Triple(3, 0.75, 4))) {
                        val label = "${orientation.name.lowercase()}: a long press $fraction along ${japanese[index]}"
                        val point = glyphPoint(activity, "japanese-sentence", index, fraction, vertical)
                        val (selected, outcome) = longPressAndRead(activity, point, label)
                        assertTrue("$label selected '$selected'", japanese[index] in selected)
                        assertTrue("$label selected '$selected'", japanese[across] !in selected)
                        assertEquals("$label: what the aim did", if (fraction > 0.5) "aimed" else "kept", outcome)
                    }
                    val roomLabel = "${orientation.name.lowercase()}: a long press in the room between 玄 and 人"
                    val (inRoom, roomOutcome) = longPressAndRead(activity, rubyRoomPoint(activity, vertical), roomLabel)
                    assertTrue("$roomLabel selected '$inRoom'", "玄" in inRoom)
                    assertTrue("$roomLabel selected '$inRoom'", "は" !in inRoom && "だ" !in inRoom)
                    assertEquals("$roomLabel: what the aim did", "aimed", roomOutcome)
                    if (!vertical) {
                        val label = "a long press on the trailing quarter of She's e"
                        val point = glyphPoint(activity, "english-sentence", 2, 0.75, vertical = false)
                        val (selected, outcome) = longPressAndRead(activity, point, label)
                        assertEquals(label, "She", selected.trim())
                        assertEquals("$label: what the aim did", "aimed", outcome)
                    }
                }
            }
        } finally {
            graph.bookService.delete(book.id)
            source.delete()
        }
    }

    /**
     * 2.1.0 §8, as iOS: a one-word translation card counts its word once,
     * whether 加入生词本 comes after the card's own lookup, while that lookup
     * is still on its way, or while the translation is still being written.
     */
    @Test
    fun addingACardsWordToTheVocabularyCountsItOnce() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = (context.applicationContext as JerreaderApplication).graph
        val source = File(context.cacheDir, "jerreader-vocabulary-${UUID.randomUUID()}.epub")
        createSyntheticEpub(source)
        val book = graph.importService.importEpub(Uri.fromFile(source)).book
        val intent = Intent(context, ReaderActivity::class.java)
            .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)
        val keys = mutableListOf<String>()
        try {
            ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                val activity = waitForActivityAndNavigator(scenario)
                for ((word, timing) in listOf("home" to "after", "book" to "racing", "night" to "streaming")) {
                    val entry = withContext(Dispatchers.IO) {
                        graph.lexicalLookupService.lookup(
                            word = word,
                            sentenceContext = null,
                            language = LanguageCode.ENGLISH,
                            candidates = emptyList()
                        )
                    }
                    val key = LearningRecordRepository.wordKey(entry)
                    keys += key
                    val before = graph.learningRecords.lookupRecord(key)?.lookupCount ?: 0
                    fun card(streaming: Boolean) = TranslationCardState.Success(
                        sourceText = word,
                        result = TranslationResult("译文", LanguageCode.ENGLISH, LanguageCode.CHINESE_SIMPLIFIED, "vocabulary-mock"),
                        isStreaming = streaming
                    )
                    when (timing) {
                        "after" -> {
                            withContext(Dispatchers.Main) { activity.showTranslationForTesting(card(streaming = false)) }
                            assertTrue(
                                "the card counts $word",
                                waitFor { graph.learningRecords.lookupRecord(key)?.lookupCount == before + 1 }
                            )
                            withContext(Dispatchers.Main) { activity.addTranslatedWordToVocabularyForTesting() }
                        }
                        "racing" -> withContext(Dispatchers.Main) {
                            activity.showTranslationForTesting(card(streaming = false))
                            activity.addTranslatedWordToVocabularyForTesting()
                        }
                        else -> {
                            withContext(Dispatchers.Main) {
                                activity.showTranslationForTesting(card(streaming = true))
                                activity.addTranslatedWordToVocabularyForTesting()
                            }
                            assertTrue(
                                "$word was not starred while its card was being written",
                                waitFor { graph.learningRecords.lookupRecord(key)?.isFavorite == true }
                            )
                            withContext(Dispatchers.Main) { activity.showTranslationForTesting(card(streaming = false)) }
                        }
                    }
                    assertTrue(
                        "$word ($timing) was not starred",
                        waitFor { graph.learningRecords.lookupRecord(key)?.isFavorite == true }
                    )
                    // Time for a second count, if there were one, to land.
                    delay(800)
                    assertEquals(
                        "$word ($timing) lookups",
                        before + 1,
                        graph.learningRecords.lookupRecord(key)?.lookupCount
                    )
                    withContext(Dispatchers.Main) { activity.dismissTranslationForTesting() }
                }
            }
        } finally {
            for (key in keys) {
                graph.learningRecords.setWordFavorite(key, false)
                graph.learningRecords.removeFromHistory(key)
            }
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

    /**
     * A point [fraction] of the way along glyph [index] of the first text
     * node in [id], in the middle across the line, in CSS pixels. Along is
     * left to right, or top to bottom in [vertical] text.
     */
    private suspend fun glyphPoint(
        activity: ReaderActivity,
        id: String,
        index: Int,
        fraction: Double,
        vertical: Boolean = false
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
                      const node = element && Array.from(element.childNodes)
                        .find(child => child.nodeType === Node.TEXT_NODE && child.data.trim());
                      if (!node || $index >= node.data.length) return null;
                      const glyph = document.createRange();
                      glyph.setStart(node, $index);
                      glyph.setEnd(node, ${index + 1});
                      const rect = glyph.getBoundingClientRect();
                      if (!(rect.width > 0 && rect.height > 0)) return null;
                      return JSON.stringify($vertical
                        ? {x: rect.left + rect.width / 2, y: rect.top + rect.height * $fraction}
                        : {x: rect.left + rect.width * $fraction, y: rect.top + rect.height / 2});
                    })()
                    """.trimIndent()
                )
            }
            raw?.let(::decodeJsonObject)?.let { json ->
                return PointF(json.getDouble("x").toFloat(), json.getDouble("y").toFloat())
            }
            SystemClock.sleep(100)
        }
        error("Readium did not expose glyph $index of #$id")
    }

    /**
     * A point in #ruby-room's blank room after 玄, in CSS pixels: 玄 sits in
     * the middle of its reading's column, so the room is the last
     * (column - 1 em) / 2 of it; the point is a quarter of the way into that
     * room, nearer 玄 than 人, on 玄's line.
     */
    private suspend fun rubyRoomPoint(activity: ReaderActivity, vertical: Boolean): PointF {
        repeat(100) {
            val raw = withContext(Dispatchers.Main) {
                navigator(activity).evaluateJavascript(
                    """
                    (() => {
                      const ruby = document.getElementById('ruby-room');
                      const reading = document.getElementById('ruby-room-reading');
                      if (!ruby || !reading) return null;
                      const column = reading.getBoundingClientRect();
                      const kanji = document.createRange();
                      kanji.setStart(ruby.firstChild, 0);
                      kanji.setEnd(ruby.firstChild, 1);
                      const rect = Array.from(kanji.getClientRects()).find((r) => r.width > 0.5 && r.height > 0.5);
                      const em = parseFloat(getComputedStyle(ruby).fontSize);
                      if (!rect || !(em > 0)) return null;
                      const end = $vertical ? column.bottom : column.right;
                      const room = (($vertical ? column.height : column.width) - em) / 2;
                      if (!(room >= 2)) return null;
                      const along = end - room * 0.75;
                      return JSON.stringify($vertical
                        ? { x: rect.left + rect.width / 2, y: along }
                        : { x: along, y: rect.top + rect.height / 2 });
                    })()
                    """.trimIndent()
                )
            }
            raw?.let(::decodeJsonObject)?.let { json ->
                return PointF(json.getDouble("x").toFloat(), json.getDouble("y").toFloat())
            }
            SystemClock.sleep(100)
        }
        error("Readium did not lay out #ruby-room with room beside 玄")
    }

    /**
     * Long-presses [css] for real and returns the text WebView selected,
     * once its selection menu has opened, with what the long-press aim did.
     * The selection and the menu are cleared again afterwards. A press the
     * page answered too late for (a busy emulator) is made once more.
     */
    private suspend fun longPressAndRead(
        activity: ReaderActivity,
        css: PointF,
        label: String
    ): Pair<String, String> {
        var result = "" to "none"
        for (attempt in 0 until 2) {
            val menus = withContext(Dispatchers.Main) { activity.selectionMenusOpenedForTesting() }
            val screen = screenPoint(activity, css) ?: error("$label: the page's WebView is not on screen")
            longPress(screen)
            val opened = waitFor {
                withContext(Dispatchers.Main) { activity.selectionMenusOpenedForTesting() } > menus
            }
            var selected = ""
            waitFor(3_000) {
                selected = withContext(Dispatchers.Main) {
                    navigator(activity).evaluateJavascript("String(getSelection())")
                }?.let { raw -> runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull() }.orEmpty()
                selected.isNotBlank()
            }
            val outcome = withContext(Dispatchers.Main) { activity.longPressAimForTesting() }.second
            withContext(Dispatchers.Main) {
                activity.closeSelectionMenuForTesting()
                navigator(activity).evaluateJavascript("(() => { getSelection().removeAllRanges(); return true; })()")
            }
            waitFor {
                withContext(Dispatchers.Main) {
                    navigator(activity).evaluateJavascript(
                        "document.getElementById('jerreader-native-selection-highlight') === null"
                    )
                } == "true"
            }
            delay(300)
            result = selected to outcome
            if (outcome == "late" && attempt == 0) continue
            assertTrue("$label: WebView's selection menu did not open (aim: $outcome)", opened)
            return result
        }
        return result
    }

    /**
     * A finger held still on [point] (screen pixels) for 1.2 s, longer than
     * any long-press timeout, with the gesture's own clock (see
     * ReaderScrollGestureTest).
     */
    private fun longPress(point: PointF) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, time: Long) {
            val event = MotionEvent.obtain(down, time, action, point.x, point.y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, down)
        SystemClock.sleep(LONG_PRESS_MILLIS)
        send(MotionEvent.ACTION_UP, down + LONG_PRESS_MILLIS)
        instrumentation.waitForIdleSync()
    }

    private fun navigator(activity: ReaderActivity): EpubNavigatorFragment =
        activity.supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().first()

    /** The WebView showing the current page. */
    private fun pageWebView(activity: ReaderActivity): WebView? {
        val found = mutableListOf<WebView>()
        fun visit(view: View) {
            if (view is WebView) found += view
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(navigator(activity).publicationView)
        val rect = Rect()
        return found
            .filter { it.isShown && it.getGlobalVisibleRect(rect) }
            .maxByOrNull { if (it.getGlobalVisibleRect(rect)) rect.width() * rect.height() else 0 }
    }

    /** A point in the page's CSS pixels, on the screen. */
    private suspend fun screenPoint(activity: ReaderActivity, css: PointF): PointF? {
        val cssWidth = withContext(Dispatchers.Main) {
            navigator(activity).evaluateJavascript("innerWidth")
        }?.toFloatOrNull()?.takeIf { it > 0f } ?: return null
        return withContext(Dispatchers.Main) {
            val webView = pageWebView(activity) ?: return@withContext null
            val at = IntArray(2).also(webView::getLocationOnScreen)
            val scale = webView.width / cssWidth
            PointF(at[0] + css.x * scale, at[1] + css.y * scale)
        }
    }

    /** Sets 排版方向 and waits for a page laid out with it; returns whether the text runs vertically. */
    @OptIn(ExperimentalReadiumApi::class)
    private suspend fun setOrientation(activity: ReaderActivity, orientation: ReaderTextOrientation): Boolean {
        val current = withContext(Dispatchers.Main) { activity.currentAppearance }
        if (current.orientation != orientation) {
            withContext(Dispatchers.Main) { activity.applyAppearance(current.copy(orientation = orientation)) }
        }
        val forced = orientation == ReaderTextOrientation.VERTICAL
        repeat(150) {
            val applied = withContext(Dispatchers.Main) {
                activity.supportFragmentManager.fragments
                    .filterIsInstance<EpubNavigatorFragment>()
                    .firstOrNull()
                    ?.settings
                    ?.value
            }
            val ready = withContext(Dispatchers.Main) {
                activity.supportFragmentManager.fragments
                    .filterIsInstance<EpubNavigatorFragment>()
                    .firstOrNull()
                    ?.evaluateJavascript(
                        "document.readyState === 'complete' && " +
                            "typeof globalThis.__jerreaderNativeSelectionSchedule === 'function' && " +
                            "!!document.getElementById('japanese-sentence')"
                    )
            } == "true"
            if (applied != null && applied.verticalText == forced && ready) {
                delay(SETTLE_MILLIS)
                return applied.verticalText
            }
            delay(100)
        }
        error("Readium did not lay the book out with orientation=$orientation")
    }

    private suspend fun waitFor(timeoutMillis: Long = 5_000, condition: suspend () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            delay(50)
        }
        return condition()
    }

    private fun decodeJsonObject(raw: String): JSONObject? {
        if (raw == "null") return null
        return when (val decoded = JSONTokener(raw).nextValue()) {
            is JSONObject -> decoded
            is String -> JSONObject(decoded)
            else -> null
        }
    }

    private companion object {
        const val LONG_PRESS_MILLIS = 1_200L
        const val SETTLE_MILLIS = 600L

        /**
         * Evidence only, never asserted: this device's platform segments for
         * the 50 sentences the two platforms' short-tap rules were compared on
         * for 2.1.0, so the Android words can be worked out off the device.
         */
        val PLATFORM_SEGMENT_PROBES = listOf(
            "食べました。", "食べませんでした。", "行きました。", "読んでいた。", "書いている。", "来ました。",
            "見ない。", "上がった。", "曲がる。", "下がらない。", "広がっている。", "時間がない。",
            "家にいる。", "学生である。", "雨でした。", "本かもしれない。", "何かある。", "昨日ご飯を食べた。",
            "お茶を飲んだ。", "静かだった。", "美しかった。", "寒くない。", "行かなかった。", "泳がない。",
            "死にたい。", "冷やす。", "問題ない。", "彼は来た。", "それはない。", "友達と遊んだ。",
            "先生に聞いた。", "東京で働く。", "電車で行く。", "猫が好きだ。", "雪が降っている。", "走って帰った。",
            "お母さんが来た。", "ご主人に会った。", "勉強しました。", "話していました。", "昨日お茶を飲んだ。", "昨日ご飯を食べました。",
            "日本は美しい。", "お金がない。", "友達といる。", "夜だ。", "行かない。", "彼はパンを食べました。",
            "それは問題ない。", "本を読んでいた。"
        )
    }
}
