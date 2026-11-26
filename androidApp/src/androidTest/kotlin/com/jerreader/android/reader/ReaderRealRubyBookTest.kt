package com.jerreader.android.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.jerreader.android.JerreaderApplication
import com.jerreader.android.library.PublicationIntegrity
import com.jerreader.shared.library.ReaderLayoutMode
import com.jerreader.shared.library.ReaderTextOrientation
import com.jerreader.shared.translation.MockTranslationService
import com.jerreader.shared.translation.QuickTranslationUnit
import com.jerreader.shared.ui.TranslationCardState
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * 2.1.0 on a real Japanese book with furigana: each reading is painted in one
 * region with the kanji it belongs to, in the book's own writing direction
 * and in the other one, for a selection set from script, the quick-tap
 * sentence highlight and real long presses: on a kanji, in the middle of the
 * ruby's kanji (between two of them that is the blank room a longer reading
 * leaves) and on the end of its last kanji.
 *
 * The book is the developer's own DRM-free copy and never enters the
 * repository. scripts/android_device_validation.py stages it in the
 * disposable guest at [STAGED_BOOK], and runs this class, only when
 * JERREADER_ANDROID_REAL_RUBY_EPUB names it. The synthetic cases are
 * [ReaderRubySelectionTest] and shared/parity/selection_tiles.json.
 *
 * Every check is collected and reported at the end, so one run captures all
 * six screenshots (and a crop of each selection) even when one step fails.
 * Captures and the summary go to learning-ui, with the other device evidence.
 */
@OptIn(ExperimentalReadiumApi::class)
class ReaderRealRubyBookTest {
    private val problems = mutableListOf<String>()
    private val summary = JSONArray()

    @Test
    fun readingsArePaintedWithTheirKanjiInARealBook() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = (context.applicationContext as JerreaderApplication).graph
        val originalService = graph.translationService
        val source = File(context.cacheDir, "jerreader-real-ruby-${UUID.randomUUID()}.epub")
        try {
            copyStagedBook(source)
        } catch (error: Throwable) {
            source.delete()
            throw error
        }
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
                    // 原版 first, then the other writing direction.
                    val publicationVertical = setOrientation(activity, ReaderTextOrientation.PUBLICATION)
                    val other = if (publicationVertical) ReaderTextOrientation.HORIZONTAL else ReaderTextOrientation.VERTICAL
                    for (orientation in listOf(ReaderTextOrientation.PUBLICATION, other)) {
                        val vertical = if (orientation == ReaderTextOrientation.PUBLICATION) {
                            publicationVertical
                        } else {
                            setOrientation(activity, orientation)
                        }
                        val label = (if (orientation == ReaderTextOrientation.PUBLICATION) "original-" else "") +
                            (if (vertical) "vertical" else "horizontal")
                        val ruby = findVisibleRuby(activity, label)
                        if (ruby.vertical != vertical) {
                            problems += "$label: Readium reports vertical=$vertical but the ruby is laid out vertical=${ruby.vertical}"
                        }
                        verifyScriptedSelection(activity, ruby, label)
                        verifyQuickTap(activity, ruby, label)
                        verifyLongPress(activity, ruby, label)
                    }
                } finally {
                    withContext(Dispatchers.Main) { activity.applyAppearance(original) }
                }
            }

            if (!PublicationIntegrity.isUnchanged(sourceSnapshot)) problems += "the staged copy changed"
            if (!PublicationIntegrity.isUnchanged(storedSnapshot)) problems += "the imported book file changed"
        } finally {
            graph.appSettings.updateLayoutMode(originalLayout)
            graph.translationService = originalService
            graph.translationSettings.updateQuickTranslationUnit(QuickTranslationUnit.SENTENCE)
            graph.bookService.delete(book.id)
            source.delete()
            writeSummary()
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /**
     * Copies the book the device script pushed, and checks it arrived whole.
     * Run without the script's argument (every androidTest from an IDE, say),
     * there is no book and the test is skipped.
     */
    private fun copyStagedBook(target: File) {
        val expected = InstrumentationRegistry.getArguments().getString(BYTES_ARGUMENT)?.toLongOrNull()
        assumeTrue(
            "Runs from scripts/safe_android_build.sh test with JERREADER_ANDROID_REAL_RUBY_EPUB",
            expected != null
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val descriptor = instrumentation.uiAutomation.executeShellCommand("cat $STAGED_BOOK")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        assertTrue(
            "The book staged at $STAGED_BOOK arrived with ${target.length()} of $expected bytes",
            target.length() == expected
        )
    }

    /**
     * A selection set from script, where a long press would set it: the ruby
     * and up to two characters on each side of it on its line. The overlay
     * that stands in for WebView's own highlight paints it as one tile with
     * the reading inside, and translation reads it without the reading.
     */
    private suspend fun verifyScriptedSelection(activity: ReaderActivity, ruby: Ruby, label: String) {
        val step = "native-$label"
        if (!awaitScript(activity, "typeof globalThis.__jerreaderNativeSelectionSchedule === 'function'")) {
            problems += "$step: the page has no selection bridge"
            return
        }
        evaluate(activity, selectAroundRubyScript(ruby.index))
        val geometry = awaitGeometry(activity, NATIVE_OVERLAY, step) ?: return
        checkJoined(geometry, step, expectOneTile = true)
        checkSelectedText(activity, geometry, step)
        capture(activity, "android-realruby-$step", geometry)
        clearSelection(activity, step)
    }

    /** A tap on the kanji highlights its sentence; every reading in it is painted with its kanji. */
    private suspend fun verifyQuickTap(activity: ReaderActivity, ruby: Ruby, label: String) {
        val step = "quick-$label"
        val state = withContext(Dispatchers.Main) { activity.translateAtForTesting(ruby.center) }
        if (state !is TranslationCardState.Success) {
            // Class names only: a card state can carry the book's sentence.
            problems += "$step: the tap gave ${state?.let { it::class.simpleName }} instead of a translated sentence"
            capture(activity, "android-realruby-$step-failure", null)
            withContext(Dispatchers.Main) { activity.dismissTranslationForTesting() }
            return
        }
        awaitCardState(activity, state, step)
        val geometry = awaitGeometry(activity, QUICK_OVERLAY, step)
        if (geometry != null) {
            checkJoined(geometry, step, expectOneTile = false)
            // The card translates exactly the highlighted sentence, without its readings.
            val highlighted = geometry.getString("text").compact()
            val sent = state.sourceText.compact()
            if (sent != highlighted) {
                problems += "$step: the card's sentence has ${sent.length} characters, " +
                    "the highlighted base text ${highlighted.length}${firstDifference(sent, highlighted)}"
            }
            capture(activity, "android-realruby-$step", geometry)
        }
        withContext(Dispatchers.Main) { activity.dismissTranslationForTesting() }
        if (!awaitScript(
                activity,
                "document.getElementById('$QUICK_OVERLAY') === null && globalThis.__jerreaderQuickSelectionActive !== true"
            )
        ) problems += "$step: the highlight stayed after the card closed"
    }

    /**
     * Real long presses on the ruby: on its first kanji, then in the middle
     * of its kanji. WebView selects a word holding one of them, the action
     * mode opens (翻译 / 查词 / 划线/批注), and the bridge paints the
     * selection with each reading inside its kanji's tile.
     *
     * The middle of two kanji whose first reading is longer than it lies in
     * the blank room that reading leaves. There, on 11-24, WebView alone
     * selected nothing, or a word further on; such a press now counts as the
     * ruby's nearest kanji (ReaderLongPressAim). A ruby of one kanji has no
     * middle of its own, and only the first press is made.
     */
    private suspend fun verifyLongPress(activity: ReaderActivity, ruby: Ruby, label: String) {
        val step = "longpress-$label"
        if (!awaitScript(activity, "typeof globalThis.__jerreaderNativeSelectionSchedule === 'function'")) {
            problems += "$step: the page has no selection bridge"
            return
        }
        pressRuby(activity, ruby, ruby.kanji, step)
        if (ruby.kanjiCount > 1) pressRuby(activity, ruby, ruby.center, "longpress-middle-$label")
        observeTailPress(activity, ruby, label)
    }

    /**
     * One long press at [css]: the selection must hold a kanji of [ruby],
     * be painted with its readings joined, and read without them. The
     * summary gets what the aim asked for before the press (a move in CSS
     * pixels, or none) and what it did, never the text.
     */
    private suspend fun pressRuby(activity: ReaderActivity, ruby: Ruby, css: PointF, step: String) {
        // The menu of an earlier press may still be open; a fresh press must make its own selection.
        withContext(Dispatchers.Main) { activity.closeSelectionMenuForTesting() }
        evaluate(activity, "(() => { getSelection().removeAllRanges(); return true; })()")
        delay(300)
        val point = screenPoint(activity, css)
        if (point == null) {
            problems += "$step: the page's WebView is not on screen"
            return
        }
        val asked = aimAnswer(activity, css)
        val traced = traceAim(activity, css)
        val place = evaluate(activity, pointScript(ruby.index, css))?.let(::decodeJsonString)
        longPress(point)
        val geometry = awaitGeometry(activity, NATIVE_OVERLAY, step)
        val aim = withContext(Dispatchers.Main) { activity.longPressAimForTesting() }.second
        val held = evaluate(activity, heldKanjiScript(ruby.index))?.let(::decodeJsonString) ?: "unknown"
        summary.put(
            JSONObject()
                .put("step", "$step-press")
                .put("aimAsked", asked)
                .put("aim", aim)
                .put("rubyKanji", held)
                .put("trace", jsonOrText(traced))
                .put("point", jsonOrText(place))
        )
        Log.i(TAG, "$step: aim asked $asked, did $aim; the selection $held a kanji of the ruby")
        Log.i(TAG, "$step: aim trace $traced")
        Log.i(TAG, "$step: point $place")
        if (held != "holds") problems += "$step: the long press selected no kanji of the pressed ruby ($held, aim: $aim)"
        if (geometry == null) return
        checkJoined(geometry, step, expectOneTile = false)
        checkSelectedText(activity, geometry, step)
        capture(activity, "android-realruby-$step", geometry)
        clearSelection(activity, step)
    }

    /**
     * What ReaderLongPressAim's page script answers for a press at [css],
     * asked before the press: the move in CSS pixels as "dx,dy", or "none".
     */
    private suspend fun aimAnswer(activity: ReaderActivity, css: PointF): String {
        val (_, scale) = pageMapping(activity) ?: return "unknown"
        val width = withContext(Dispatchers.Main) { pageWebView(activity)?.width } ?: return "unknown"
        val raw = evaluate(activity, ReaderLongPressAim.aimScript(css.x * scale, css.y * scale, width))
        return ReaderLongPressAim.decodeShift(raw)
            ?.let { (dx, dy) -> String.format(java.util.Locale.ROOT, "%.1f,%.1f", dx / scale, dy / scale) }
            ?: "none"
    }

    /**
     * The aim script's own account of a press at [css], asked before the
     * press with its trace on: which way it went and what it found there
     * (the caret, the hit, the rubies), in tags and numbers. A script that
     * throws says so, with the error's name and message.
     */
    private suspend fun traceAim(activity: ReaderActivity, css: PointF): String {
        val (_, scale) = pageMapping(activity) ?: return "unknown"
        val width = withContext(Dispatchers.Main) { pageWebView(activity)?.width } ?: return "unknown"
        val script = ReaderLongPressAim.aimScript(css.x * scale, css.y * scale, width, trace = true)
        val guarded = "(() => { try { return $script; } catch (error) { " +
            "return JSON.stringify({ exit: 'exception', name: String(error && error.name), " +
            "message: String(error && error.message).slice(0, 160) }); } })()"
        val raw = evaluate(activity, guarded) ?: return "unknown"
        return decodeJsonString(raw) ?: raw
    }

    /**
     * Where [css] is against the ruby at `index`, in tags and numbers only,
     * never the text: what the caret and the hit are, and where they are
     * against the ruby; the elements under the point; the ruby's boxes,
     * kanji ('b') and reading ('r') glyph by glyph, rounded to 0.01 px.
     */
    private fun pointScript(index: Int, css: PointF) = """
        (() => {
          const ruby = document.querySelectorAll('ruby')[$index];
          if (!ruby) return JSON.stringify({ ruby: 'gone' });
          const x = ${css.x};
          const y = ${css.y};
          const round = (value) => Math.round(value * 100) / 100;
          const box = (rect) => [rect.left, rect.top, rect.right, rect.bottom].map(round);
          const place = (item) => {
            if (!item) return 'none';
            const element = item.nodeType === Node.ELEMENT_NODE ? item : item.parentElement;
            if (!element) return 'detached';
            const tag = element.tagName.toLowerCase();
            if (ruby.contains(element) && element.closest('rt,rp')) return 'reading';
            if (element === ruby) return 'ruby';
            if (ruby.contains(element)) return 'in-ruby:' + tag;
            if (element.contains(ruby)) return 'around:' + tag;
            const order = ruby.compareDocumentPosition(item);
            return (order & Node.DOCUMENT_POSITION_PRECEDING ? 'before:' : 'after:') + tag;
          };
          const caret = document.caretRangeFromPoint(x, y);
          const node = caret && caret.startContainer;
          const text = !!node && node.nodeType === Node.TEXT_NODE;
          const glyphs = [];
          const walker = document.createTreeWalker(ruby, NodeFilter.SHOW_TEXT);
          while (walker.nextNode()) {
            const item = walker.currentNode;
            const kind = item.parentElement && item.parentElement.closest('rt,rp') ? 'r' : 'b';
            for (let offset = 0; offset < item.data.length; offset += 1) {
              const range = document.createRange();
              range.setStart(item, offset);
              range.setEnd(item, offset + 1);
              glyphs.push({ kind, boxes: Array.from(range.getClientRects()).map(box) });
            }
          }
          const style = getComputedStyle(ruby);
          const rt = ruby.querySelector('rt');
          const rtStyle = rt ? getComputedStyle(rt) : null;
          return JSON.stringify({
            point: [round(x), round(y)],
            viewport: [innerWidth, innerHeight],
            caret: !node ? null : {
              place: place(node), text, offset: caret.startOffset,
              length: text ? node.data.length : node.childNodes.length,
              previous: place(node.previousSibling), next: place(node.nextSibling)
            },
            hit: place(document.elementFromPoint(x, y)),
            stack: document.elementsFromPoint(x, y).slice(0, 6).map(place),
            ruby: {
              parent: ruby.parentElement ? ruby.parentElement.tagName.toLowerCase() : 'none',
              writingMode: style.writingMode, display: style.display,
              rtDisplay: rtStyle ? rtStyle.display : null, rtPointerEvents: rtStyle ? rtStyle.pointerEvents : null,
              bounds: box(ruby.getBoundingClientRect()),
              rects: Array.from(ruby.getClientRects()).map(box),
              glyphs
            }
          });
        })()
    """.trimIndent()

    /** A JSON object as such in the summary, anything else as text. */
    private fun jsonOrText(raw: String?): Any =
        raw?.let { runCatching { JSONObject(it) }.getOrNull() ?: it } ?: JSONObject.NULL

    /**
     * Whether the selection holds a kanji of the ruby at `index` ('holds',
     * 'misses' or 'none'). Answers only that, never the text.
     */
    private fun heldKanjiScript(index: Int) = """
        (() => {
          const ruby = document.querySelectorAll('ruby')[$index];
          if (!ruby) return 'gone';
          const selection = getSelection();
          if (!selection.rangeCount || selection.isCollapsed) return 'none';
          const range = selection.getRangeAt(0);
          const bases = document.createTreeWalker(ruby, NodeFilter.SHOW_TEXT);
          while (bases.nextNode()) {
            const node = bases.currentNode;
            if (node.parentElement?.closest('rt,rp')) continue;
            for (let offset = 0; offset < node.data.length; offset += 1) {
              if (/\s/.test(node.data.charAt(offset))) continue;
              if (range.comparePoint(node, offset) === 0 && range.comparePoint(node, offset + 1) === 0) return 'holds';
            }
          }
          return 'misses';
        })()
    """.trimIndent()

    /**
     * A long press three quarters of the way along the ruby's last kanji
     * holds that kanji (2.1.0). WebView alone takes the word that starts at
     * the caret nearest the finger, so such a press selected the word after
     * it wherever a word starts there; the reader now moves the press onto
     * the kanji first (ReaderLongPressAim). The summary also says whether a
     * word starts right after the kanji (where WebView's rule applied) and
     * what the aim did.
     */
    private suspend fun observeTailPress(activity: ReaderActivity, ruby: Ruby, label: String) {
        val step = "longpress-tail-$label"
        val point = ruby.tail?.let { screenPoint(activity, it) } ?: return
        // The centre press's menu may still be open; a fresh one must open.
        withContext(Dispatchers.Main) { activity.closeSelectionMenuForTesting() }
        delay(300)
        val menus = withContext(Dispatchers.Main) { activity.selectionMenusOpenedForTesting() }
        val traced = ruby.tail?.let { traceAim(activity, it) }
        longPress(point)
        var observed: JSONObject? = null
        for (attempt in 0 until 30) {
            observed = evaluate(activity, tailSelectionScript(ruby.index))?.let(::decodeJsonObject)
            val state = observed?.optString("state")
            if (state == "holds" || state == "misses") break
            delay(100)
        }
        val state = observed?.optString("state") ?: "unknown"
        val wordStartsAfter = observed?.opt("wordStartsAfter")
        val aim = withContext(Dispatchers.Main) { activity.longPressAimForTesting() }.second
        val menuOpened = withContext(Dispatchers.Main) { activity.selectionMenusOpenedForTesting() } > menus
        summary.put(
            JSONObject()
                .put("step", step)
                .put("pressedKanji", state)
                .put("wordStartsAfter", wordStartsAfter ?: JSONObject.NULL)
                .put("aim", aim)
                .put("menuOpened", menuOpened)
                .put("trace", jsonOrText(traced))
        )
        Log.i(TAG, "$step: the selection $state the pressed kanji; a word starts after it: $wordStartsAfter; aim: $aim")
        Log.i(TAG, "$step: aim trace $traced")
        if (state != "holds") problems += "$step: the long press selected a word without the pressed kanji ($state, aim: $aim)"
        if (!menuOpened) problems += "$step: WebView's selection menu did not open (aim: $aim)"
        withContext(Dispatchers.Main) { activity.closeSelectionMenuForTesting() }
        val geometry = evaluate(activity, geometryScript(NATIVE_OVERLAY))?.let(::decodeJsonObject)
        capture(activity, "android-realruby-$step", geometry)
        evaluate(activity, "(() => { getSelection().removeAllRanges(); return true; })()")
        awaitScript(activity, "document.getElementById('$NATIVE_OVERLAY') === null")
    }

    /**
     * Whether the selection holds the last kanji of the ruby at `index`
     * ('holds', 'misses' or 'none'), and whether a word starts right after
     * that kanji in its paragraph's base text, by ICU's word rules
     * (Intl.Segmenter). Answers only these, never the text.
     */
    private fun tailSelectionScript(index: Int) = """
        (() => {
          const ruby = document.querySelectorAll('ruby')[$index];
          if (!ruby) return JSON.stringify({ state: 'gone' });
          let last = null;
          const bases = document.createTreeWalker(ruby, NodeFilter.SHOW_TEXT);
          while (bases.nextNode()) {
            const node = bases.currentNode;
            if (!node.data.trim().length || node.parentElement?.closest('rt,rp')) continue;
            last = node;
          }
          if (!last) return JSON.stringify({ state: 'gone' });
          let end = last.data.length;
          while (end > 0 && /\s/.test(last.data.charAt(end - 1))) end -= 1;
          let start = end - 1;
          const unit = last.data.charCodeAt(start);
          if (unit >= 0xDC00 && unit <= 0xDFFF && start > 0) start -= 1;
          const selection = getSelection();
          let state = 'none';
          if (selection.rangeCount && !selection.isCollapsed) {
            const range = selection.getRangeAt(0);
            const holds = range.comparePoint(last, start) === 0 && range.comparePoint(last, end) === 0;
            state = holds ? 'holds' : 'misses';
          }
          const block = ruby.parentElement?.closest('p,div,li,blockquote,dd,td') || ruby.parentElement;
          let text = '';
          let tailEnd = -1;
          const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
          while (walker.nextNode()) {
            const node = walker.currentNode;
            if (node.parentElement?.closest('rt,rp')) continue;
            if (node === last) tailEnd = text.length + end;
            text += node.data;
          }
          let wordStartsAfter = null;
          if (tailEnd >= 0 && typeof Intl.Segmenter === 'function') {
            wordStartsAfter = false;
            for (const part of new Intl.Segmenter('ja', { granularity: 'word' }).segment(text)) {
              if (part.index > tailEnd) break;
              if (part.index === tailEnd) {
                wordStartsAfter = part.isWordLike === true;
                break;
              }
            }
          }
          return JSON.stringify({ state: state, wordStartsAfter: wordStartsAfter });
        })()
    """.trimIndent()

    /**
     * Each ruby the selection reaches: a single tile holds both the centre of
     * its selected kanji and the reading beside them (the same place along the
     * line, the reading's side across it). Kanji and reading in two tiles, or
     * a gap between them, fails this. Every tile also holds selected text, so
     * no reading is painted on its own.
     */
    private fun checkJoined(geometry: JSONObject, step: String, expectOneTile: Boolean) {
        val markers = geometry.getJSONArray("markers").boxes()
        val pieces = geometry.getJSONArray("pieces").boxes()
        val rubies = geometry.getJSONArray("rubies")
        var joined = 0
        var wrapped = 0
        for (index in 0 until rubies.length()) {
            val ruby = rubies.getJSONObject(index)
            if (ruby.getBoolean("wrapped")) {
                wrapped += 1
                continue
            }
            val base = ruby.getJSONObject("base").box()
            val reading = ruby.getJSONObject("reading").box()
            val beside = if (ruby.getBoolean("vertical")) {
                PointF(reading.centerX(), base.centerY())
            } else {
                PointF(base.centerX(), reading.centerY())
            }
            when {
                markers.none { it.holds(base.centerX(), base.centerY()) } ->
                    problems += "$step: ruby $index, kanji $base lies in no tile $markers"
                markers.none { it.holds(base.centerX(), base.centerY()) && it.holds(beside.x, beside.y) } ->
                    problems += "$step: ruby $index, reading $reading is not in the tile of its kanji $base: $markers"
                else -> joined += 1
            }
        }
        if (rubies.length() - wrapped == 0) problems += "$step: the selection reached no ruby on one line"
        markers.forEachIndexed { index, marker ->
            if (pieces.none { marker.holds(it.centerX(), it.centerY()) }) {
                problems += "$step: tile $index $marker holds no selected text"
            }
        }
        if (expectOneTile && markers.size != 1) {
            problems += "$step: expected one tile for a selection on one line, got ${markers.size}: $markers"
        }
        summary.put(
            JSONObject()
                .put("step", step)
                .put("tiles", markers.size)
                .put("rubies", rubies.length())
                .put("joined", joined)
                .put("wrapped", wrapped)
        )
        Log.i(TAG, "$step: ${markers.size} tile(s), ${rubies.length()} ruby(s), $joined joined, $wrapped wrapped")
    }

    /**
     * Translation and lookup read the selection without its readings, as on
     * iOS (ReaderRubySelectionText). A selection that crosses no reading
     * (one kanji of a ruby, say) gets no text of the page's own, and the
     * reader then takes Readium's, the selection's own text: so does this
     * check (11-25: a press that held one kanji read as null here).
     */
    private suspend fun checkSelectedText(activity: ReaderActivity, geometry: JSONObject, step: String) {
        if (geometry.getJSONArray("rubies").length() == 0) return
        val expected = geometry.getString("text").compact()
        val page = ReaderRubySelectionText.decode(evaluate(activity, ReaderRubySelectionText.script()))
        val read = (page?.text ?: evaluate(activity, "getSelection().toString()")?.let(::decodeJsonString))?.compact()
        if (read != expected) {
            problems += "$step: the selection reads ${read?.length} characters, " +
                "its base text has ${expected.length}${firstDifference(read.orEmpty(), expected)}"
        }
    }

    /** The card holds the final translation translateAt returned, not a piece streamed on the way. */
    private suspend fun awaitCardState(activity: ReaderActivity, expected: TranslationCardState, step: String) {
        repeat(60) {
            if (withContext(Dispatchers.Main) { activity.currentTranslationState } == expected) return
            delay(50)
        }
        val shown = withContext(Dispatchers.Main) { activity.currentTranslationState }
        problems += "$step: the card does not show the final translation (it shows a ${shown?.let { it::class.simpleName }})"
    }

    private suspend fun clearSelection(activity: ReaderActivity, step: String) {
        evaluate(activity, "(() => { getSelection().removeAllRanges(); return true; })()")
        if (!awaitScript(activity, "document.getElementById('$NATIVE_OVERLAY') === null")) {
            problems += "$step: the selection tiles stayed after the selection was cleared"
        }
    }

    /**
     * In CSS pixels: `kanji`, the middle of the ruby's first kanji;
     * `center`, the middle of all its kanji together; `tail`, three quarters
     * of the way along its last kanji.
     */
    private class Ruby(
        val index: Int,
        val kanji: PointF,
        val kanjiCount: Int,
        val center: PointF,
        val vertical: Boolean,
        val tail: PointF?
    )

    /** From the current page on: the first ruby in running text with its kanji and reading both in view. */
    private suspend fun findVisibleRuby(activity: ReaderActivity, label: String): Ruby {
        repeat(MAX_PAGE_TURNS) {
            evaluate(activity, VISIBLE_RUBY_SCRIPT)?.let(::decodeJsonObject)?.let { json ->
                return Ruby(
                    json.getInt("index"),
                    PointF(json.getDouble("kanjiX").toFloat(), json.getDouble("kanjiY").toFloat()),
                    json.getInt("kanjiCount"),
                    PointF(json.getDouble("x").toFloat(), json.getDouble("y").toFloat()),
                    json.getBoolean("vertical"),
                    if (json.isNull("tailX") || json.isNull("tailY")) {
                        null
                    } else {
                        PointF(json.getDouble("tailX").toFloat(), json.getDouble("tailY").toFloat())
                    }
                )
            }
            turnPage(activity)
        }
        capture(activity, "android-realruby-$label-no-ruby", null)
        error("$label: no ruby in view within $MAX_PAGE_TURNS pages")
    }

    /** 下一页, then wait for Readium to report the new position and for the page to be ready. */
    private suspend fun turnPage(activity: ReaderActivity) {
        val before = withContext(Dispatchers.Main) { navigator(activity)?.currentLocator?.value }
        withContext(Dispatchers.Main) { activity.turnPageForTesting(forward = true) }
        repeat(50) {
            val now = withContext(Dispatchers.Main) { navigator(activity)?.currentLocator?.value }
            if (now != null && now != before && pageReady(activity)) {
                delay(SETTLE_MILLIS)
                return
            }
            delay(100)
        }
        delay(SETTLE_MILLIS)
    }

    /** Sets 排版方向 and waits for a page laid out with it; returns whether the text runs vertically. */
    private suspend fun setOrientation(activity: ReaderActivity, orientation: ReaderTextOrientation): Boolean {
        val current = withContext(Dispatchers.Main) { activity.currentAppearance }
        if (current.orientation != orientation) {
            withContext(Dispatchers.Main) { activity.applyAppearance(current.copy(orientation = orientation)) }
        }
        val forced = when (orientation) {
            ReaderTextOrientation.HORIZONTAL -> false
            ReaderTextOrientation.VERTICAL -> true
            ReaderTextOrientation.PUBLICATION -> null
        }
        repeat(150) {
            val applied = withContext(Dispatchers.Main) { navigator(activity)?.settings?.value }
            if (applied != null && (forced == null || applied.verticalText == forced) && pageReady(activity)) {
                delay(SETTLE_MILLIS)
                return applied.verticalText
            }
            delay(100)
        }
        error("Readium did not lay the book out with orientation=$orientation")
    }

    private suspend fun pageReady(activity: ReaderActivity): Boolean = evaluate(
        activity,
        "document.readyState === 'complete' && typeof globalThis.__jerreaderNativeSelectionSchedule === 'function'"
    ) == "true"

    private suspend fun awaitGeometry(activity: ReaderActivity, overlayId: String, step: String): JSONObject? {
        repeat(60) {
            evaluate(activity, geometryScript(overlayId))?.let(::decodeJsonObject)?.let { return it }
            delay(100)
        }
        problems += "$step: nothing was painted in #$overlayId"
        capture(activity, "android-realruby-$step-failure", null)
        return null
    }

    private suspend fun awaitScript(activity: ReaderActivity, condition: String): Boolean {
        repeat(60) {
            if (evaluate(activity, "($condition)") == "true") return true
            delay(100)
        }
        return false
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

    /** The WebView showing the current page, with the factor from its CSS pixels to screen pixels. */
    private fun pageWebView(activity: ReaderActivity): WebView? {
        val root = navigator(activity)?.publicationView ?: return null
        val found = mutableListOf<WebView>()
        fun visit(view: View) {
            if (view is WebView) found += view
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        visit(root)
        val rect = Rect()
        return found
            .filter { it.isShown && it.getGlobalVisibleRect(rect) }
            .maxByOrNull { if (it.getGlobalVisibleRect(rect)) rect.width() * rect.height() else 0 }
    }

    /**
     * Where the page's WebView is on the screen, and how many screen pixels
     * one of its CSS pixels takes (its width over the page's innerWidth).
     */
    private suspend fun pageMapping(activity: ReaderActivity): Pair<IntArray, Float>? {
        val cssWidth = evaluate(activity, "innerWidth")?.toFloatOrNull()?.takeIf { it > 0f } ?: return null
        return withContext(Dispatchers.Main) {
            val webView = pageWebView(activity) ?: return@withContext null
            IntArray(2).also(webView::getLocationOnScreen) to webView.width / cssWidth
        }
    }

    /** A point in the page's CSS pixels, on the screen. */
    private suspend fun screenPoint(activity: ReaderActivity, css: PointF): PointF? {
        val (at, scale) = pageMapping(activity) ?: return null
        return PointF(at[0] + css.x * scale, at[1] + css.y * scale)
    }

    /**
     * A finger held still on the point for 1.2 s, longer than any long-press
     * timeout. The events carry the gesture's own clock (see
     * ReaderScrollGestureTest for why a busy emulator needs that).
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

    /**
     * The screen, and a crop around the selection tiles so the join between a
     * kanji and its reading can be seen at full resolution.
     */
    private suspend fun capture(activity: ReaderActivity, name: String, geometry: JSONObject?) {
        val crop = geometry?.let { json ->
            val markers = json.getJSONArray("markers").boxes()
            if (markers.isEmpty()) return@let null
            val (at, scale) = pageMapping(activity) ?: return@let null
            val margin = CROP_MARGIN_CSS * scale
            RectF(
                at[0] + markers.minOf { it.left } * scale - margin,
                at[1] + markers.minOf { it.top } * scale - margin,
                at[0] + markers.maxOf { it.right } * scale + margin,
                at[1] + markers.maxOf { it.bottom } * scale + margin
            )
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // An idle main thread is not a drawn frame on this software-rendered
        // emulator; see ReaderRubySelectionTest.captureReader.
        SystemClock.sleep(1_500)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "learning-ui").apply { mkdirs() }
        try {
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (crop != null) {
                val left = crop.left.toInt().coerceIn(0, bitmap.width - 1)
                val top = crop.top.toInt().coerceIn(0, bitmap.height - 1)
                val right = crop.right.toInt().coerceIn(left + 1, bitmap.width)
                val bottom = crop.bottom.toInt().coerceIn(top + 1, bitmap.height)
                val part = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                try {
                    File(directory, "$name-crop.png").outputStream().use { part.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    part.recycle()
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** Per step: tiles, rubies reached, rubies joined. No book text. */
    private fun writeSummary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "learning-ui").apply { mkdirs() }
        File(directory, "android-realruby-summary.json").writeText(
            JSONObject().put("steps", summary).put("problems", JSONArray(problems)).toString(2)
        )
    }

    /**
     * The overlay's tiles; every piece of base text the selection covers; and
     * each ruby it reaches, with the box of its selected kanji and of its
     * reading. A range that holds only a reading takes the kanji under it,
     * as the bridge does. All in client coordinates.
     */
    private fun geometryScript(overlayId: String) = """
        (() => {
          const overlay = document.getElementById('$overlayId');
          if (!overlay || !overlay.children.length) return null;
          const selection = getSelection();
          if (!selection || !selection.rangeCount || selection.isCollapsed) return null;
          const range = selection.getRangeAt(0);
          const box = (rect) => ({ left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom });
          const visible = (rect) => rect.width > 0.5 && rect.height > 0.5;
          const union = (rects) => ({
            left: Math.min(...rects.map((rect) => rect.left)),
            top: Math.min(...rects.map((rect) => rect.top)),
            right: Math.max(...rects.map((rect) => rect.right)),
            bottom: Math.max(...rects.map((rect) => rect.bottom))
          });
          const crosses = (node) => { try { return range.intersectsNode(node); } catch (_) { return false; } };
          const baseNodes = (root) => {
            const nodes = [];
            const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
            while (walker.nextNode()) {
              const node = walker.currentNode;
              const parent = node.parentElement;
              if (node.data.length && parent && !parent.closest('rt,rp,script,style,noscript')) nodes.push(node);
            }
            return nodes;
          };
          const bounds = (node) => {
            const start = range.startContainer === node ? range.startOffset : 0;
            const end = range.endContainer === node ? range.endOffset : node.data.length;
            return end > start ? [start, end] : null;
          };
          const rectsOf = (node, start, end) => {
            const piece = document.createRange();
            piece.setStart(node, start);
            piece.setEnd(node, end);
            return Array.from(piece.getClientRects()).filter(visible);
          };
          const common = range.commonAncestorContainer;
          const root = common.nodeType === Node.ELEMENT_NODE ? common : common.parentElement;
          const pieces = [];
          let text = '';
          baseNodes(root || document.body).forEach((node) => {
            if (!crosses(node)) return;
            const span = bounds(node);
            if (!span) return;
            text += node.data.slice(span[0], span[1]);
            pieces.push(...rectsOf(node, span[0], span[1]).map(box));
          });
          let recoveredText = '';
          const rubies = [];
          document.querySelectorAll('ruby').forEach((ruby) => {
            if (!crosses(ruby)) return;
            const annotations = Array.from(ruby.querySelectorAll('rt'));
            const readings = annotations.flatMap((rt) => Array.from(rt.getClientRects())).filter(visible);
            if (!readings.length) return;
            let selected = baseNodes(ruby).filter(crosses).flatMap((node) => {
              const span = bounds(node);
              return span ? rectsOf(node, span[0], span[1]) : [];
            });
            if (!selected.length && annotations.some(crosses)) {
              const nodes = baseNodes(ruby);
              selected = nodes.flatMap((node) => rectsOf(node, 0, node.data.length));
              recoveredText += nodes.map((node) => node.data).join('');
              pieces.push(...selected.map(box));
            }
            if (!selected.length) return;
            const vertical = (getComputedStyle(ruby).writingMode || '').startsWith('vertical');
            const first = selected[0];
            const oneLine = selected.every((rect) => vertical
              ? rect.left < first.right - 0.5 && rect.right > first.left + 0.5
              : rect.top < first.bottom - 0.5 && rect.bottom > first.top + 0.5);
            rubies.push({ base: union(selected), reading: union(readings), vertical: vertical, wrapped: !oneLine });
          });
          return JSON.stringify({
            markers: Array.from(overlay.children).map((marker) => box(marker.getBoundingClientRect())),
            pieces: pieces,
            rubies: rubies,
            text: text.length ? text : recoveredText
          });
        })()
    """.trimIndent()

    /** Selects the ruby and up to two characters of plain text on each side of it, all on its line. */
    private fun selectAroundRubyScript(index: Int) = """
        (() => {
          const ruby = document.querySelectorAll('ruby')[$index];
          if (!ruby) return false;
          const vertical = (getComputedStyle(ruby).writingMode || '').startsWith('vertical');
          const bases = [];
          const walker = document.createTreeWalker(ruby, NodeFilter.SHOW_TEXT);
          while (walker.nextNode()) {
            const node = walker.currentNode;
            if (!node.data.length || node.parentElement?.closest('rt,rp')) continue;
            const whole = document.createRange();
            whole.selectNodeContents(node);
            bases.push(...Array.from(whole.getClientRects()).filter((rect) => rect.width > 0.5 && rect.height > 0.5));
          }
          if (!bases.length) return false;
          const line = bases[0];
          const onLine = (node, offset) => {
            const character = document.createRange();
            character.setStart(node, offset);
            character.setEnd(node, offset + 1);
            const rect = character.getBoundingClientRect();
            if (rect.width < 0.5 || rect.height < 0.5 || /\s/.test(node.data[offset])) return false;
            return vertical
              ? rect.left < line.right - 0.5 && rect.right > line.left + 0.5
              : rect.top < line.bottom - 0.5 && rect.bottom > line.top + 0.5;
          };
          const range = document.createRange();
          range.setStartBefore(ruby);
          range.setEndAfter(ruby);
          const before = ruby.previousSibling;
          if (before && before.nodeType === Node.TEXT_NODE) {
            let start = before.data.length;
            while (start > 0 && before.data.length - start < 2 && onLine(before, start - 1)) start -= 1;
            if (start < before.data.length) range.setStart(before, start);
          }
          const after = ruby.nextSibling;
          if (after && after.nodeType === Node.TEXT_NODE) {
            let end = 0;
            while (end < after.data.length && end < 2 && onLine(after, end)) end += 1;
            if (end > 0) range.setEnd(after, end);
          }
          const selection = getSelection();
          selection.removeAllRanges();
          selection.addRange(range);
          return true;
        })()
    """.trimIndent()

    private fun decodeJsonString(raw: String): String? =
        runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull()

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

    private fun String.compact() = filterNot(Char::isWhitespace)

    /** Where two texts part, by index only, so no book text lands in the report. */
    private fun firstDifference(a: String, b: String): String {
        val index = a.zip(b).indexOfFirst { (x, y) -> x != y }.let { if (it < 0) minOf(a.length, b.length) else it }
        return " (they differ from index $index)"
    }

    private companion object {
        const val TAG = "JerreaderRealRuby"
        const val STAGED_BOOK = "/data/local/tmp/JerreaderRealRubyBook.epub"
        const val BYTES_ARGUMENT = "jerreaderRealRubyBytes"
        const val QUICK_OVERLAY = "jerreader-quick-selection-highlight"
        const val NATIVE_OVERLAY = "jerreader-native-selection-highlight"
        const val MAX_PAGE_TURNS = 40
        const val LONG_PRESS_MILLIS = 1_200L
        const val CROP_MARGIN_CSS = 28f

        /** Time a freshly laid out page gets before it is measured. */
        const val SETTLE_MILLIS = 600L

        /**
         * The first ruby on the page whose kanji and reading are both in view,
         * on one line, in running text (not a link or a heading).
         */
        val VISIBLE_RUBY_SCRIPT = """
            (() => {
              const width = innerWidth;
              const height = innerHeight;
              const visible = (rect) => rect.width > 0.5 && rect.height > 0.5;
              const inView = (rect) => rect.left >= 4 && rect.top >= 4 && rect.right <= width - 4 && rect.bottom <= height - 4;
              const rubies = Array.from(document.querySelectorAll('ruby'));
              for (let index = 0; index < rubies.length; index++) {
                const ruby = rubies[index];
                if (ruby.parentElement?.closest('a,h1,h2,h3,h4,h5,h6,rt,rp,ruby')) continue;
                const readings = Array.from(ruby.querySelectorAll('rt'))
                  .flatMap((rt) => Array.from(rt.getClientRects())).filter(visible);
                const bases = [];
                let lastBase = null;
                let firstKanji = null;
                let kanjiCount = 0;
                const walker = document.createTreeWalker(ruby, NodeFilter.SHOW_TEXT);
                while (walker.nextNode()) {
                  const node = walker.currentNode;
                  if (!node.data.trim().length || node.parentElement?.closest('rt,rp')) continue;
                  lastBase = node;
                  const whole = document.createRange();
                  whole.selectNodeContents(node);
                  bases.push(...Array.from(whole.getClientRects()).filter(visible));
                  for (let offset = 0; offset < node.data.length; offset += 1) {
                    const unit = node.data.charCodeAt(offset);
                    if (/\s/.test(node.data.charAt(offset)) || (unit >= 0xDC00 && unit <= 0xDFFF)) continue;
                    kanjiCount += 1;
                    if (firstKanji) continue;
                    const glyph = document.createRange();
                    glyph.setStart(node, offset);
                    glyph.setEnd(node, unit >= 0xD800 && unit <= 0xDBFF ? offset + 2 : offset + 1);
                    firstKanji = Array.from(glyph.getClientRects()).find(visible) || null;
                  }
                }
                if (!readings.length || !bases.length || !firstKanji) continue;
                if (!readings.every(inView) || !bases.every(inView)) continue;
                const vertical = (getComputedStyle(ruby).writingMode || '').startsWith('vertical');
                const first = bases[0];
                const oneLine = bases.every((rect) => vertical
                  ? rect.left < first.right - 0.5 && rect.right > first.left + 0.5
                  : rect.top < first.bottom - 0.5 && rect.bottom > first.top + 0.5);
                if (!oneLine) continue;
                const block = ruby.parentElement?.closest('p,div,li,blockquote,dd,td');
                if (!block || (block.textContent || '').replace(/\s/g, '').length < 12) continue;
                const left = Math.min(...bases.map((rect) => rect.left));
                const right = Math.max(...bases.map((rect) => rect.right));
                const top = Math.min(...bases.map((rect) => rect.top));
                const bottom = Math.max(...bases.map((rect) => rect.bottom));
                // Three quarters of the way along the last kanji.
                let tail = null;
                if (lastBase) {
                  let end = lastBase.data.length;
                  while (end > 0 && /\s/.test(lastBase.data.charAt(end - 1))) end -= 1;
                  let start = end - 1;
                  const unit = lastBase.data.charCodeAt(start);
                  if (unit >= 0xDC00 && unit <= 0xDFFF && start > 0) start -= 1;
                  const glyph = document.createRange();
                  glyph.setStart(lastBase, start);
                  glyph.setEnd(lastBase, end);
                  const rect = Array.from(glyph.getClientRects()).find(visible);
                  if (rect) {
                    tail = vertical
                      ? { x: rect.left + rect.width / 2, y: rect.top + rect.height * 0.75 }
                      : { x: rect.left + rect.width * 0.75, y: rect.top + rect.height / 2 };
                  }
                }
                return JSON.stringify({
                  index: index, x: (left + right) / 2, y: (top + bottom) / 2, vertical: vertical,
                  kanjiX: firstKanji.left + firstKanji.width / 2, kanjiY: firstKanji.top + firstKanji.height / 2,
                  kanjiCount: kanjiCount,
                  tailX: tail ? tail.x : null, tailY: tail ? tail.y : null
                });
              }
              return null;
            })()
        """.trimIndent()
    }
}
