package com.jerreader.android.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.jerreader.android.JerreaderApplication
import com.jerreader.android.data.ReadingAnnotationEntity
import com.jerreader.android.library.PublicationIntegrity
import com.jerreader.android.test.createSyntheticEpub
import com.jerreader.shared.library.ReaderLayoutMode
import com.jerreader.shared.library.ReaderTextOrientation
import com.jerreader.shared.translation.MockTranslationService
import com.jerreader.shared.translation.QuickTranslationUnit
import com.jerreader.shared.ui.TranslationCardState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * 2.1.0: furigana are painted as one region with the kanji they belong to.
 * The kanji and its reading used to get a strip each, with a gap and a second
 * outline between them; now the reading joins the kanji's tile, in the
 * quick-tap highlight and in the overlay that stands in for WebView's own
 * selection highlight, in horizontal and in vertical text. The tile rules
 * themselves are the shared cases in shared/parity/selection_tiles.json.
 * The selection menu's 划线/批注 keeps the sentence without its readings and
 * reopens the note the selection already has.
 */
@OptIn(ExperimentalReadiumApi::class)
class ReaderRubySelectionTest {

    @Test
    fun furiganaArePaintedAsOneRegionWithTheirKanji() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = (context.applicationContext as JerreaderApplication).graph
        val originalService = graph.translationService
        val source = File(context.cacheDir, "jerreader-ruby-selection-${UUID.randomUUID()}.epub")
        createSyntheticEpub(source, rubySentence = true)
        source.setLastModified(1_700_600_000_000)
        val sourceSnapshot = PublicationIntegrity.capture(source)
        val book = graph.importService.importPublication(Uri.fromFile(source)).book
        val stored = graph.publicationStore.resolvePublication(book.publicationFileName)
        val storedSnapshot = PublicationIntegrity.capture(stored)
        val originalLayout = graph.appSettings.preferences.value.layoutMode

        try {
            graph.appSettings.updateLayoutMode(ReaderLayoutMode.STANDARD)
            graph.translationService = MockTranslationService()
            graph.translationSettings.updateQuickTranslationEnabled(true)
            graph.translationSettings.updateQuickTranslationUnit(QuickTranslationUnit.SENTENCE)
            val intent = Intent(context, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)

            ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                val activity = waitForActivityAndNavigator(scenario)
                val original = withContext(Dispatchers.Main) { activity.currentAppearance }
                try {
                    for (orientation in listOf(ReaderTextOrientation.HORIZONTAL, ReaderTextOrientation.VERTICAL)) {
                        setOrientation(activity, orientation)
                        val label = orientation.name.lowercase()
                        verifyQuickTap(activity, label)
                        verifyNativeSelection(activity, label)
                        verifyDivParagraphTap(activity, label)
                    }
                    verifySelectionMenuNote(activity, book.id, graph.readerRecords)
                } finally {
                    withContext(Dispatchers.Main) { activity.applyAppearance(original) }
                }
            }

            assertTrue(PublicationIntegrity.isUnchanged(sourceSnapshot))
            assertTrue(PublicationIntegrity.isUnchanged(storedSnapshot))
        } finally {
            graph.appSettings.updateLayoutMode(originalLayout)
            graph.translationService = originalService
            graph.translationSettings.updateQuickTranslationUnit(QuickTranslationUnit.SENTENCE)
            graph.bookService.delete(book.id)
            source.delete()
        }
    }

    /** A tap on a kanji highlights its sentence; each reading sits in its kanji's tile. */
    private suspend fun verifyQuickTap(activity: ReaderActivity, label: String) {
        val point = baseTextCenter(activity, "ruby-door")
        val state = withContext(Dispatchers.Main) { activity.translateAtForTesting(point) }
        assertTrue("Expected the ruby sentence to translate ($label), got $state", state is TranslationCardState.Success)
        state as TranslationCardState.Success
        // The readings are painted with the sentence but never translated with it.
        assertEquals("彼は扉を開けた。", state.sourceText)
        // The context is the sentence's paragraph, as on iOS; this one holds
        // only the sentence, so nothing else goes with it. Readium's text
        // around the selection held the readings and the other paragraphs.
        assertNull("quick tap context, $label", state.context)
        awaitCardState(activity, state, label)
        assertOneRegion(awaitGeometry(activity, QUICK_OVERLAY), "quick tap, $label")
        captureReader("android-ruby-quick-$label")
        withContext(Dispatchers.Main) { activity.dismissTranslationForTesting() }
        awaitScript(
            activity,
            "document.getElementById('$QUICK_OVERLAY') === null && globalThis.__jerreaderQuickSelectionActive !== true"
        )
    }

    /**
     * A paragraph set as div, with no p around it: a tap on its kanji takes the
     * whole sentence, as on iOS. The ruby under the finger used to be taken
     * for the paragraph, so the tap looked up 雨 alone.
     */
    private suspend fun verifyDivParagraphTap(activity: ReaderActivity, label: String) {
        val point = baseTextCenter(activity, "ruby-rain")
        val state = withContext(Dispatchers.Main) { activity.translateAtForTesting(point) }
        assertTrue("Expected the div sentence to translate ($label), got $state", state is TranslationCardState.Success)
        state as TranslationCardState.Success
        assertEquals("外は雨だった。", state.sourceText)
        assertNull("div paragraph context, $label", state.context)
        awaitCardState(activity, state, "div paragraph, $label")
        withContext(Dispatchers.Main) { activity.dismissTranslationForTesting() }
        awaitScript(
            activity,
            "document.getElementById('$QUICK_OVERLAY') === null && globalThis.__jerreaderQuickSelectionActive !== true"
        )
    }

    /** The card holds the final translation translateAt returned, not a piece streamed on the way. */
    private suspend fun awaitCardState(activity: ReaderActivity, expected: TranslationCardState, label: String) {
        repeat(60) {
            if (withContext(Dispatchers.Main) { activity.currentTranslationState } == expected) return
            delay(50)
        }
        val shown = withContext(Dispatchers.Main) { activity.currentTranslationState }
        fail("$label: the card shows $shown instead of the final translation")
    }

    /**
     * A selection made by hand (set from script here, where a long press would
     * set it) is painted by the overlay that replaces WebView's own highlight.
     */
    private suspend fun verifyNativeSelection(activity: ReaderActivity, label: String) {
        // The reader puts the bridge, and its style, into each page as it loads,
        // before any selection: a selection WebView paints first keeps its stock
        // blue on the ruby base text once the style comes.
        awaitScript(activity, "typeof globalThis.__jerreaderNativeSelectionSchedule === 'function'")
        evaluate(
            activity,
            """
            (() => {
              const range = document.createRange();
              range.selectNodeContents(document.getElementById('ruby-sentence'));
              const selection = getSelection();
              selection.removeAllRanges();
              selection.addRange(range);
              return true;
            })()
            """.trimIndent()
        )
        assertOneRegion(awaitGeometry(activity, NATIVE_OVERLAY), "native selection, $label")
        // Translation and lookup read this selection without its readings, and
        // the rest of its paragraph (nothing here) as its context, as on iOS.
        assertEquals(
            "native selection, $label",
            ReaderRubySelectionText.Page("彼は扉を開けた。", "", ""),
            ReaderRubySelectionText.decode(evaluate(activity, ReaderRubySelectionText.script()))
        )
        captureReader("android-ruby-native-$label")
        // The particle between the two rubies crosses no reading, so Readium's
        // own text stands; the text around it is the paragraph's base text,
        // without とびら or あ.
        evaluate(
            activity,
            """
            (() => {
              const particle = document.getElementById('ruby-door').nextSibling;
              const range = document.createRange();
              range.setStart(particle, 0);
              range.setEnd(particle, particle.data.length);
              const selection = getSelection();
              selection.removeAllRanges();
              selection.addRange(range);
              return true;
            })()
            """.trimIndent()
        )
        assertEquals(
            "selection between the rubies, $label",
            ReaderRubySelectionText.Page(null, "彼は扉", "開けた。"),
            ReaderRubySelectionText.decode(evaluate(activity, ReaderRubySelectionText.script()))
        )
        evaluate(activity, "(() => { getSelection().removeAllRanges(); return true; })()")
        awaitScript(activity, "document.getElementById('$NATIVE_OVERLAY') === null")
    }

    /**
     * The selection menu's 划线/批注 keeps the sentence without its readings,
     * as the card and iOS do; Readium's own text was 彼は扉とびらを開あけた。.
     * A second 划线/批注 on the same selection opens that note, as iOS
     * annotationDraftForCurrentSelection does, instead of a new one that
     * would replace it under the same key.
     */
    private suspend fun verifySelectionMenuNote(activity: ReaderActivity, bookId: String, records: ReaderRecordRepository) {
        selectRubySentence(activity)
        withContext(Dispatchers.Main) { activity.requestAnnotationForSelectionForTesting() }
        val first = awaitEditor(activity, "the new note")
        assertEquals("彼は扉を開けた。", first.first)
        assertNull("a new note edits nothing", first.second)
        withContext(Dispatchers.Main) { activity.saveAnnotationEditorForTesting("第一条") }
        val note = awaitNote(records, bookId)
        assertEquals("彼は扉を開けた。", note.selectedText)
        assertEquals("第一条", note.noteText)
        // Saving clears the selection; it has to be gone before it is made again.
        awaitScript(activity, "document.getElementById('$NATIVE_OVERLAY') === null")

        selectRubySentence(activity)
        withContext(Dispatchers.Main) { activity.requestAnnotationForSelectionForTesting() }
        val again = awaitEditor(activity, "the saved note")
        assertEquals("彼は扉を開けた。", again.first)
        assertEquals("the second 划线/批注 opens the saved note", note.id, again.second)
        assertEquals("第一条", again.third)
        withContext(Dispatchers.Main) { activity.dismissAnnotationEditorForTesting() }
        assertEquals(listOf(note), records.observeAnnotations(bookId).first())
        evaluate(activity, "(() => { getSelection().removeAllRanges(); return true; })()")
        awaitScript(activity, "document.getElementById('$NATIVE_OVERLAY') === null")
    }

    private suspend fun selectRubySentence(activity: ReaderActivity) {
        evaluate(
            activity,
            """
            (() => {
              const range = document.createRange();
              range.selectNodeContents(document.getElementById('ruby-sentence'));
              const selection = getSelection();
              selection.removeAllRanges();
              selection.addRange(range);
              return true;
            })()
            """.trimIndent()
        )
        awaitGeometry(activity, NATIVE_OVERLAY)
    }

    private suspend fun awaitNote(records: ReaderRecordRepository, bookId: String): ReadingAnnotationEntity {
        repeat(60) {
            records.observeAnnotations(bookId).first().singleOrNull()?.let { return it }
            delay(100)
        }
        error("The note was not saved")
    }

    private suspend fun awaitEditor(activity: ReaderActivity, label: String): Triple<String, String?, String> {
        repeat(60) {
            withContext(Dispatchers.Main) { activity.annotationEditorForTesting }?.let { return it }
            delay(100)
        }
        error("No note editor opened for $label")
    }

    /**
     * Every reading's centre lies in a tile that also holds its kanji's centre,
     * and the one-line sentence is one tile: kanji, readings and the kana
     * between them painted once, under a single outline.
     */
    private fun assertOneRegion(geometry: JSONObject, label: String) {
        val markers = geometry.getJSONArray("markers").boxes()
        assertTrue("$label: nothing was painted", markers.isNotEmpty())
        val pairs = geometry.getJSONArray("pairs")
        for (index in 0 until pairs.length()) {
            val pair = pairs.getJSONObject(index)
            val kanji = pair.getJSONObject("kanji").box()
            val reading = pair.getJSONObject("reading").box()
            assertTrue("$label: reading $index was not laid out: $reading", reading.width() > 0.5f && reading.height() > 0.5f)
            val holder = markers.firstOrNull { it.holds(reading.centerX(), reading.centerY()) }
            assertTrue("$label: reading $index $reading lies outside every tile $markers", holder != null)
            assertTrue(
                "$label: reading $index $reading and its kanji $kanji are in different tiles $markers",
                holder!!.holds(kanji.centerX(), kanji.centerY())
            )
        }
        assertEquals("$label: expected one tile, got $markers", 1, markers.size)
    }

    private suspend fun awaitGeometry(activity: ReaderActivity, overlayId: String): JSONObject {
        repeat(60) {
            evaluate(activity, geometryScript(overlayId))?.let(::decodeJsonObject)?.let { return it }
            delay(100)
        }
        error("Nothing was painted in #$overlayId")
    }

    /** The overlay's tiles and each ruby's kanji and reading, all in client coordinates. */
    private fun geometryScript(overlayId: String) = """
        (() => {
          const overlay = document.getElementById('$overlayId');
          if (!overlay || !overlay.children.length) return null;
          const box = (rect) => ({ left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom });
          const kanji = (id) => {
            const range = document.createRange();
            range.selectNodeContents(document.getElementById(id).firstChild);
            return box(range.getBoundingClientRect());
          };
          const reading = (id) => box(document.getElementById(id).getBoundingClientRect());
          return JSON.stringify({
            markers: Array.from(overlay.children).map((marker) => box(marker.getBoundingClientRect())),
            pairs: [
              { kanji: kanji('ruby-door'), reading: reading('ruby-door-reading') },
              { kanji: kanji('ruby-open'), reading: reading('ruby-open-reading') }
            ]
          });
        })()
    """.trimIndent()

    /** Centre, in CSS pixels, of a ruby element's base text. */
    private suspend fun baseTextCenter(activity: ReaderActivity, id: String): PointF {
        repeat(100) {
            evaluate(
                activity,
                """
                (() => {
                  const element = document.getElementById('$id');
                  if (!element || !element.firstChild) return null;
                  const range = document.createRange();
                  range.selectNodeContents(element.firstChild);
                  const rect = range.getBoundingClientRect();
                  if (rect.width < 1 || rect.height < 1) return null;
                  return JSON.stringify({ x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 });
                })()
                """.trimIndent()
            )?.let(::decodeJsonObject)?.let { json ->
                return PointF(json.getDouble("x").toFloat(), json.getDouble("y").toFloat())
            }
            delay(100)
        }
        error("Readium did not lay out #$id")
    }

    /** Changing 排版方向 rebuilds the navigator; wait for the new one to lay the chapter out. */
    private suspend fun setOrientation(activity: ReaderActivity, orientation: ReaderTextOrientation) {
        val current = withContext(Dispatchers.Main) { activity.currentAppearance }
        if (current.orientation != orientation) {
            withContext(Dispatchers.Main) { activity.applyAppearance(current.copy(orientation = orientation)) }
        }
        val vertical = orientation == ReaderTextOrientation.VERTICAL
        repeat(100) {
            val applied = withContext(Dispatchers.Main) { navigator(activity)?.settings?.value }
            if (applied != null && applied.verticalText == vertical &&
                evaluate(activity, "document.getElementById('ruby-sentence') !== null") == "true"
            ) {
                delay(SETTLE_MILLIS)
                return
            }
            delay(100)
        }
        error("Readium did not lay the book out with orientation=$orientation")
    }

    private suspend fun awaitScript(activity: ReaderActivity, condition: String) {
        repeat(60) {
            if (evaluate(activity, "($condition)") == "true") return
            delay(100)
        }
        error("Timed out waiting for: $condition")
    }

    private suspend fun evaluate(activity: ReaderActivity, script: String): String? =
        withContext(Dispatchers.Main) { navigator(activity)?.evaluateJavascript(script) }

    private fun navigator(activity: ReaderActivity): EpubNavigatorFragment? =
        activity.supportFragmentManager.fragments
            .filterIsInstance<EpubNavigatorFragment>()
            .firstOrNull { it.view != null }

    private fun waitForActivityAndNavigator(scenario: ActivityScenario<ReaderActivity>): ReaderActivity {
        var ready: ReaderActivity? = null
        repeat(100) {
            scenario.onActivity { activity -> if (navigator(activity) != null) ready = activity }
            ready?.let { return it }
            SystemClock.sleep(100)
        }
        error("ReaderActivity did not attach Readium's EPUB navigator")
    }

    private fun captureReader(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // An idle main thread is not a drawn frame: the emulator renders in
        // software, at 300 ms to over a second a frame here, and one capture
        // 500 ms after idle still showed the card's previous streamed piece.
        SystemClock.sleep(1_500)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "learning-ui").apply { mkdirs() }
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    private fun decodeJsonObject(raw: String): JSONObject? {
        if (raw == "null") return null
        return when (val decoded = JSONTokener(raw).nextValue()) {
            is JSONObject -> decoded
            is String -> JSONObject(decoded)
            else -> null
        }
    }

    private fun JSONObject.box() = RectF(
        getDouble("left").toFloat(),
        getDouble("top").toFloat(),
        getDouble("right").toFloat(),
        getDouble("bottom").toFloat()
    )

    private fun JSONArray.boxes(): List<RectF> = List(length()) { getJSONObject(it).box() }

    private fun RectF.holds(x: Float, y: Float) =
        x >= left - 0.5f && x <= right + 0.5f && y >= top - 0.5f && y <= bottom + 0.5f

    private companion object {
        const val QUICK_OVERLAY = "jerreader-quick-selection-highlight"
        const val NATIVE_OVERLAY = "jerreader-native-selection-highlight"

        /** Time a freshly laid out chapter gets before it is measured. */
        const val SETTLE_MILLIS = 600L
    }
}
