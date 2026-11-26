package com.jerreader.android.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.jerreader.android.JerreaderApplication
import com.jerreader.android.library.PublicationIntegrity
import com.jerreader.android.test.createSyntheticEpub
import com.jerreader.shared.library.ReaderLayoutMode
import com.jerreader.shared.library.ReaderTextOrientation
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * GitHub issue #2: while a book scrolled, any sideways finger movement jumped
 * to the neighbouring chapter and lost the reading position.
 */
@OptIn(ExperimentalReadiumApi::class)
class ReaderScrollGestureTest {
    @Test
    fun sidewaysSwipeTurnsPagesButNeverLeavesAScrollingChapter() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = (context.applicationContext as JerreaderApplication).graph
        val source = File(context.cacheDir, "jerreader-scroll-gesture-${UUID.randomUUID()}.epub")
        // The reported swipe happens while reading down a chapter, so chapter
        // one has to be several screens long.
        createSyntheticEpub(source, fillerParagraphs = 80)
        val book = graph.importService.importPublication(Uri.fromFile(source)).book
        val stored = graph.publicationStore.resolvePublication(book.publicationFileName)
        val storedSnapshot = PublicationIntegrity.capture(stored)
        val originalLayout = graph.appSettings.preferences.value.layoutMode
        val originalQuickTranslation = graph.translationSettings.preferences.value.quickTranslationEnabled

        try {
            graph.appSettings.updateLayoutMode(ReaderLayoutMode.STANDARD)
            // Only the swipe is under test; a tap card would cover the page.
            graph.translationSettings.updateQuickTranslationEnabled(false)
            val intent = Intent(context, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)

            ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                val activity = waitForActivityAndNavigator(scenario)
                awaitChapter(activity, "chapter-one", "The book did not open at its first chapter")
                val (width, height) = withContext(Dispatchers.Main) {
                    activity.window.decorView.width.toFloat() to activity.window.decorView.height.toFloat()
                }
                fun swipeLeft(driftY: Float = 0f) = swipe(width * .75f, width * .25f, height * .5f, driftY)
                fun swipeRight() = swipe(width * .25f, width * .75f, height * .5f, 0f)

                // Paged: a swipe turns the page and a swipe back returns. This
                // also proves the injected gesture reaches the chapter.
                setScroll(activity, false)
                assertFalse(withContext(Dispatchers.Main) { activity.scrollModeBlocksPageTurns() })
                val firstPage = awaitPage(activity, "The chapter was not laid out in pages") { it.paged }
                delay(SETTLE_MILLIS)
                awaitGesture(activity, "A swipe did not turn the page in paged mode", { swipeLeft() }) {
                    it.paged && abs(it.x - firstPage.x) > it.width * .5
                }
                delay(600)
                awaitGesture(activity, "A swipe back did not turn the page in paged mode", { swipeRight() }) {
                    it.paged && abs(it.x - firstPage.x) < 1
                }

                // Scrolling: a finger moving up still scrolls the chapter, so
                // touches reach it there too.
                setScroll(activity, true)
                assertTrue(withContext(Dispatchers.Main) { activity.scrollModeBlocksPageTurns() })
                val top = awaitPage(activity, "The chapter was not laid out for scrolling") { it.scrolling }
                delay(SETTLE_MILLIS)
                awaitGesture(
                    activity,
                    "A swipe up did not scroll the chapter",
                    { swipe(width * .5f, width * .5f, height * .7f, -height * .4f) }
                ) {
                    it.scrolling && it.y > top.y + SCROLLED_AWAY
                }
                delay(1_000)

                // The same swipes as before, and scroll flicks that drift
                // sideways, stay in the chapter and keep the place in it.
                val gestures = listOf(
                    "a swipe left" to { swipeLeft() },
                    "a slanted swipe left" to { swipeLeft(driftY = -150f) },
                    "a scroll flick drifting sideways" to {
                        swipe(width * .6f, width * .6f - 120f, height * .6f, -150f)
                    },
                    "a swipe right" to { swipeRight() }
                )
                for ((name, gesture) in gestures) {
                    gesture()
                    delay(1_200)
                    if (clearSelection(activity)) {
                        // The stall described at awaitGesture: the gesture
                        // selected a word, so it never tested the chapter.
                        Log.w(TAG, "$name selected text instead; clearing it and making it once more")
                        delay(SETTLE_MILLIS)
                        gesture()
                        delay(1_200)
                    }
                    val href = chapter(activity)
                    assertTrue("$name left the chapter while scrolling: $href", href.contains("chapter-one"))
                }
                awaitPage(activity, "Sideways swipes threw the chapter back to its top") {
                    it.scrolling && it.y > top.y + SCROLLED_AWAY
                }

                // 上一页 / 下一页 have no page to turn there and step one chapter,
                // as they did before the swipe was switched off.
                withContext(Dispatchers.Main) { activity.turnPageForTesting(forward = true) }
                awaitChapter(activity, "chapter-two", "下一页 did not reach the next chapter while scrolling")
                delay(800)
                withContext(Dispatchers.Main) { activity.turnPageForTesting(forward = false) }
                awaitChapter(activity, "chapter-one", "上一页 did not reach the previous chapter while scrolling")

                // Vertical text always scrolls, sideways, and its edge swipe is
                // the only gesture between chapters, so it keeps Readium's turns.
                delay(800)
                setOrientation(activity, ReaderTextOrientation.VERTICAL)
                assertFalse(
                    "Vertical text lost its chapter swipe",
                    withContext(Dispatchers.Main) { activity.scrollModeBlocksPageTurns() }
                )
                setOrientation(activity, ReaderTextOrientation.HORIZONTAL)
                assertTrue(
                    "Horizontal text got the chapter swipe back",
                    withContext(Dispatchers.Main) { activity.scrollModeBlocksPageTurns() }
                )
            }

            assertTrue(PublicationIntegrity.isUnchanged(storedSnapshot))
        } finally {
            graph.appSettings.updateLayoutMode(originalLayout)
            graph.translationSettings.updateQuickTranslationEnabled(originalQuickTranslation)
            graph.bookService.delete(book.id)
            source.delete()
        }
    }

    /**
     * A finger drag across the page, quick enough to count as a swipe.
     *
     * Each event carries the gesture's own clock, [STEP_MILLIS] apart, rather
     * than the moment it was delivered. A busy emulator delivers events late,
     * and Android's velocity tracker reads a long gap as the finger having
     * stopped, which leaves a swipe with no speed and no page turn.
     */
    private fun swipe(fromX: Float, toX: Float, fromY: Float, driftY: Float) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        var sent = 0
        fun send(action: Int, travelled: Float) {
            val event = MotionEvent.obtain(
                down, down + sent * STEP_MILLIS, action,
                fromX + (toX - fromX) * travelled, fromY + driftY * travelled, 0
            )
            sent += 1
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        // A finger starts slowly: three short moves, then an even pace.
        val lead = 3
        val steps = 12
        repeat(lead + steps) { index ->
            SystemClock.sleep(STEP_MILLIS)
            val travelled =
                if (index < lead) .01f * (index + 1)
                else .01f * lead + (1 - .01f * lead) * (index - lead + 1) / steps
            send(MotionEvent.ACTION_MOVE, travelled)
        }
        SystemClock.sleep(STEP_MILLIS)
        send(MotionEvent.ACTION_UP, 1f)
        instrumentation.waitForIdleSync()
    }

    private fun navigator(activity: ReaderActivity): EpubNavigatorFragment? =
        activity.supportFragmentManager.fragments
            .filterIsInstance<EpubNavigatorFragment>()
            .firstOrNull { it.view != null }

    private suspend fun chapter(activity: ReaderActivity): String = withContext(Dispatchers.Main) {
        navigator(activity)?.currentLocator?.value?.href?.toString().orEmpty()
    }

    private suspend fun awaitChapter(activity: ReaderActivity, name: String, failure: String) {
        var href = ""
        repeat(100) {
            href = chapter(activity)
            if (href.contains(name)) return
            delay(100)
        }
        error("$failure: expected $name, the reader is at $href")
    }

    /** How the chapter on screen is laid out and how far it has moved, in CSS pixels. */
    private class Page(
        val paged: Boolean,
        val scrolling: Boolean,
        val width: Double,
        val x: Double,
        val y: Double,
        val report: String
    )

    /** Readium runs the script only once the chapter on screen has loaded. */
    private suspend fun page(activity: ReaderActivity): Page? {
        val raw = withTimeoutOrNull(3_000) {
            withContext(Dispatchers.Main) { navigator(activity)?.evaluateJavascript(PAGE_SCRIPT) }
        } ?: return null
        if (raw == "null") return null
        val json = when (val decoded = JSONTokener(raw).nextValue()) {
            is JSONObject -> decoded
            is String -> JSONObject(decoded)
            else -> return null
        }
        val width = json.getDouble("width")
        val height = json.getDouble("height")
        if (json.getString("ready") != "complete" || width <= 0 || height <= 0) return null
        val wide = json.getDouble("scrollWidth") > width * 1.5
        val tall = json.getDouble("scrollHeight") > height * 1.5
        return Page(
            paged = wide,
            scrolling = tall && !wide,
            width = width,
            x = json.getDouble("x"),
            y = json.getDouble("y"),
            report = json.toString()
        )
    }

    /**
     * Makes [gesture] and waits for chapter one to reach the state [expected]
     * describes, making it once more if the emulator turned it into a long
     * press.
     *
     * On the software-rendered guest a frame can take a second. When one lands
     * right after the finger goes down, the long-press timer fires before the
     * first move is read, and the swipe selects a word instead of moving the
     * page (11-23 20:36: the TextClassifier ran during this test and the page
     * stayed put). A phone does not stall that long. Only a selection the
     * gesture left behind earns the second try; a gesture that still misses,
     * or that misses without selecting anything, fails as before.
     */
    private suspend fun awaitGesture(
        activity: ReaderActivity,
        failure: String,
        gesture: () -> Unit,
        expected: (Page) -> Boolean
    ): Page {
        gesture()
        repeat(150) {
            val current = page(activity)
            if (current != null && chapter(activity).contains("chapter-one") && expected(current)) return current
            if (clearSelection(activity)) {
                Log.w(TAG, "$failure: the gesture selected text instead; clearing it and making it once more")
                delay(SETTLE_MILLIS)
                gesture()
                return awaitPage(activity, failure, expected)
            }
            delay(100)
        }
        return awaitPage(activity, failure, expected)
    }

    /** Clears a text selection on the page; true when there was one. */
    private suspend fun clearSelection(activity: ReaderActivity): Boolean {
        val raw = withTimeoutOrNull(3_000) {
            withContext(Dispatchers.Main) { navigator(activity)?.evaluateJavascript(CLEAR_SELECTION_SCRIPT) }
        }
        return raw == "true"
    }

    /** Waits for chapter one to load and settle into the state [expected] describes. */
    private suspend fun awaitPage(activity: ReaderActivity, failure: String, expected: (Page) -> Boolean): Page {
        var report = "not loaded"
        repeat(150) {
            val current = page(activity)
            if (current != null) {
                report = current.report
                if (chapter(activity).contains("chapter-one") && expected(current)) return current
            }
            delay(100)
        }
        error("$failure: page=$report, the reader is at ${chapter(activity)}")
    }

    private suspend fun setScroll(activity: ReaderActivity, scroll: Boolean) {
        withContext(Dispatchers.Main) {
            activity.applyAppearance(activity.currentAppearance.copy(scroll = scroll))
        }
        repeat(100) {
            val applied = withContext(Dispatchers.Main) { navigator(activity)?.settings?.value?.scroll }
            if (applied == scroll) return
            delay(100)
        }
        error("Readium did not apply scroll=$scroll")
    }

    /** Changing 排版方向 rebuilds the navigator; wait for the new one. */
    private suspend fun setOrientation(activity: ReaderActivity, orientation: ReaderTextOrientation) {
        withContext(Dispatchers.Main) {
            activity.applyAppearance(activity.currentAppearance.copy(orientation = orientation))
        }
        val vertical = orientation == ReaderTextOrientation.VERTICAL
        repeat(100) {
            val applied = withContext(Dispatchers.Main) { navigator(activity)?.settings?.value }
            if (applied != null && applied.verticalText == vertical && applied.scroll) return
            delay(100)
        }
        error("Readium did not lay the book out with orientation=$orientation")
    }

    private fun waitForActivityAndNavigator(scenario: ActivityScenario<ReaderActivity>): ReaderActivity {
        var ready: ReaderActivity? = null
        repeat(100) {
            scenario.onActivity { activity -> if (navigator(activity) != null) ready = activity }
            ready?.let { return it }
            SystemClock.sleep(100)
        }
        error("ReaderActivity did not attach Readium's EPUB navigator")
    }

    private companion object {
        const val TAG = "JerreaderScrollGesture"

        const val CLEAR_SELECTION_SCRIPT = """
            (function () {
              var selection = window.getSelection();
              if (!selection || selection.isCollapsed) return false;
              selection.removeAllRanges();
              return true;
            })()
        """

        const val STEP_MILLIS = 10L

        /** Time a freshly laid out chapter gets before the first gesture. */
        const val SETTLE_MILLIS = 600L

        /** CSS pixels a chapter has to be scrolled to count as away from its top. */
        const val SCROLLED_AWAY = 50

        const val PAGE_SCRIPT = """
            (() => {
              const page = document.scrollingElement || document.documentElement;
              return JSON.stringify({
                ready: document.readyState,
                href: location.href,
                width: window.innerWidth,
                height: window.innerHeight,
                scrollWidth: page.scrollWidth,
                scrollHeight: page.scrollHeight,
                x: window.scrollX,
                y: window.scrollY
              });
            })()
        """
    }
}
