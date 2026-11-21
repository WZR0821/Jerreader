package com.jerreader.android.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.github.barteksc.pdfviewer.PDFView
import com.jerreader.android.JerreaderApplication
import com.jerreader.shared.library.ReaderLayoutMode
import com.jerreader.shared.library.ReaderThemeOption
import com.jerreader.shared.translation.MockTranslationService
import com.jerreader.shared.translation.QuickTranslationUnit
import com.jerreader.shared.ui.TranslationCardState
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * 2.0.1's PDF fixes on Android had only been read through, not run:
 *
 * - a text PDF that paints no page background was rendered for OCR onto a
 *   transparent bitmap, which ML Kit reads as black, so no text was found;
 * - in continuous scroll only Readium's current page was looked at, so a tap
 *   on the end of the page before it or the start of the page after did
 *   nothing;
 * - the selection is put back on its sentence after the page is dragged or
 *   zoomed, on the page it was made on;
 * - the status and navigation bars follow the paper;
 * - closing the reader while a page is still being rendered for OCR used to
 *   throw from `PdfRenderer.close()`.
 *
 * The PDF is drawn here with no background, four pages, each naming itself in
 * a sentence at its top and its bottom, so the recognised text says which page
 * a tap reached. Whether the tap point really is on that page's text on screen
 * is checked separately against a screenshot.
 */
@OptIn(ExperimentalReadiumApi::class)
class ReaderPdfTapTranslationTest {
    private val notes = StringBuilder()

    /** Every step runs; what went wrong is listed together at the end. */
    private val problems = mutableListOf<String>()

    @Test
    fun textPdfTranslatesOnEveryVisiblePageAndTheSelectionFollowsTheView() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = (context.applicationContext as JerreaderApplication).graph
        val originalService = graph.translationService
        val originalTranslation = graph.translationSettings.preferences.value
        val originalLayout = graph.appSettings.preferences.value.layoutMode
        val source = File(context.cacheDir, "jerreader-pdf-tap-${UUID.randomUUID()}.pdf")
        createPdf(source)
        val book = graph.importService.importPublication(Uri.fromFile(source)).book
        var savedHighlightId: String? = null

        try {
            graph.appSettings.updateLayoutMode(ReaderLayoutMode.STANDARD)
            graph.translationService = MockTranslationService()
            graph.translationSettings.updateQuickTranslationEnabled(true)
            graph.translationSettings.updateQuickTranslationUnit(QuickTranslationUnit.SENTENCE)
            val intent = Intent(context, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)

            ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                val activity = waitForPdf(scenario)
                SystemClock.sleep(SETTLE_MILLIS)
                capture("android-pdf-opened")

                // A text PDF with no background: OCR must find the words.
                step("page 1 top") { translate(activity, page = 0, top = true, expectedPage = 0) }
                capture("android-pdf-text-page-translated")

                // The end of page one in the upper third, page two below it:
                // page two is Readium's current page.
                step("boundary 1/2") { putBoundary(activity, upperPage = 0, fraction = 0.35f, expectedCurrent = 1) }

                // A saved highlight on page two, as 划线 from the card stores one.
                step("saved highlight") {
                    // Stored the way the reader stores its own: an unmarked
                    // position is read back as the old format, a page earlier.
                    val locator = withContext(Dispatchers.Main) {
                        PdfLocatorFormat.mark(checkNotNull(navigator(activity)?.currentLocator?.value))
                    }
                    assertEquals("the highlight goes on page two", 2, locator.locations.position)
                    savedHighlightId = graph.readerRecords.saveAnnotation(
                        bookId = book.id,
                        bookTitle = book.title,
                        locatorJson = locator.toJSON().toString(),
                        selectedText = "Page two opens here.",
                        noteText = "",
                        color = "yellow",
                        chapterTitle = "",
                        progress = locator.locations.totalProgression ?: 0.0,
                        geometryJson = JSONArray().put(
                            JSONObject()
                                .put("x", SAVED_HIGHLIGHT.left.toDouble())
                                .put("y", SAVED_HIGHLIGHT.top.toDouble())
                                .put("w", SAVED_HIGHLIGHT.width().toDouble())
                                .put("h", SAVED_HIGHLIGHT.height().toDouble())
                        ).toString()
                    ).id
                    checkSavedHighlight(activity, "saved highlight")
                }
                capture("android-pdf-boundary-1-2")
                step("page 1 bottom") { translate(activity, page = 0, top = false, expectedPage = 0) }
                capture("android-pdf-previous-page-translated")

                // A real finger drag: the paint has to stay on its sentence.
                step("drag") { checkSelectionFollows(activity, "drag") { drag(activity, distance = 240f) } }
                step("saved highlight after drag") { checkSavedHighlight(activity, "after drag") }
                capture("android-pdf-after-drag")

                step("page 2 top") { translate(activity, page = 1, top = true, expectedPage = 1) }

                // The end of page two in the lower third, page three below it.
                step("boundary 2/3") { putBoundary(activity, upperPage = 1, fraction = 0.65f, expectedCurrent = 1) }
                step("page 3 top") { translate(activity, page = 2, top = true, expectedPage = 2) }
                capture("android-pdf-next-page-translated")

                step("pinch") { checkSelectionFollows(activity, "pinch") { pinch(activity) } }
                capture("android-pdf-after-pinch")

                step("system bars") { checkSystemBars(activity) }

                // Leave while an unrecognised page is still being rendered.
                closeDuringOcr(scenario, activity)
            }
            assertTrue("${problems.size} step(s) failed:\n${problems.joinToString("\n")}", problems.isEmpty())
        } finally {
            writeNotes()
            savedHighlightId?.let { graph.readerRecords.deleteAnnotation(it) }
            graph.appSettings.updateLayoutMode(originalLayout)
            graph.translationService = originalService
            graph.translationSettings.updateQuickTranslationEnabled(originalTranslation.quickTranslationEnabled)
            graph.translationSettings.updateQuickTranslationUnit(originalTranslation.quickTranslationUnit)
            graph.bookService.delete(book.id)
            source.delete()
        }
    }

    private suspend fun step(name: String, block: suspend () -> Unit) {
        try {
            block()
            notes.append("[ok] $name\n")
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            if (failure !is AssertionError && failure !is IllegalStateException) throw failure
            notes.append("[FAILED] $name: ${failure.message}\n")
            problems += "$name: ${failure.message}"
        }
    }

    private suspend fun translate(activity: ReaderActivity, page: Int, top: Boolean, expectedPage: Int) {
        val point = withContext(Dispatchers.Main) {
            val rect = pageRect(activity, page)
            PointF(
                rect.left + SENTENCE_X * rect.width(),
                rect.top + (if (top) TOP_SENTENCE_Y else BOTTOM_SENTENCE_Y) * rect.height()
            )
        }
        // The card of the last tap can sit over the next sentence.
        withContext(Dispatchers.Main) { activity.dismissTranslationForTesting() }
        delay(300)
        val height = withContext(Dispatchers.Main) { checkNotNull(navigator(activity)).publicationView.height }
        assertTrue("page ${page + 1}'s sentence must be on screen at $point", point.y in 0f..height.toFloat())
        assertTrue(
            "the screen must show text under the tap on page ${page + 1} at $point",
            hasInkNear(activity, point)
        )
        val state = withContext(Dispatchers.Main) { activity.translateAtForTesting(point) }
        val word = PAGE_WORDS[page]
        val label = "page ${page + 1} ${if (top) "top" else "bottom"}"
        assertTrue("$label: expected a translation, got $state", state is TranslationCardState.Success)
        state as TranslationCardState.Success
        val recognised = state.sourceText.lowercase()
        assertTrue("$label: recognised \"${state.sourceText}\"", recognised.contains("page $word"))
        assertTrue(
            "$label: recognised \"${state.sourceText}\"",
            recognised.contains(if (top) "opens" else "ends")
        )
        val selectionPage = withContext(Dispatchers.Main) {
            activity.pdfTranslationControllerForTesting?.selectionPageIndex()
        }
        assertEquals("$label: the selection's page", expectedPage, selectionPage)
        val notePage = withContext(Dispatchers.Main) {
            activity.pdfAnnotationLocatorForTesting()?.locations?.position
        }
        assertEquals("$label: 加批注 from the card goes on the tapped page", expectedPage + 1, notePage)
        val current = withContext(Dispatchers.Main) { currentPage(activity) }
        notes.append("$label: \"${state.sourceText}\" on page index $selectionPage, Readium current $current\n")
    }

    /** Moves the view so the top of `upperPage + 1` sits at `fraction` of its height. */
    private suspend fun putBoundary(activity: ReaderActivity, upperPage: Int, fraction: Float, expectedCurrent: Int) {
        withContext(Dispatchers.Main) {
            val pdfView = checkNotNull(pdfView(activity))
            val height = checkNotNull(navigator(activity)).publicationView.height
            val lower = pageRect(activity, upperPage + 1)
            pdfView.moveTo(pdfView.currentXOffset, pdfView.currentYOffset + fraction * height - lower.top)
            // What a finger drag calls after each move (DragPinchManager.onScroll):
            // it works out the current page, and Readium's locator follows it.
            // moveTo alone leaves both on the old page. Package-private.
            PDFView::class.java.getDeclaredMethod("loadPageByOffset")
                .apply { isAccessible = true }
                .invoke(pdfView)
        }
        repeat(80) {
            if (withContext(Dispatchers.Main) { currentPage(activity) } == expectedCurrent) {
                // Rendering and Readium's locator settle a little later.
                delay(SETTLE_MILLIS)
                return
            }
            delay(100)
        }
        error("Readium's current page stayed ${withContext(Dispatchers.Main) { currentPage(activity) }}")
    }

    /**
     * Runs [gesture] and checks the painted selection: either it is back on
     * the sentence (as mapped from the view as it is now), or it went away
     * together with its card. Paint left where the sentence used to be fails.
     */
    private suspend fun checkSelectionFollows(activity: ReaderActivity, name: String, gesture: suspend () -> Unit) {
        val before = withContext(Dispatchers.Main) { activity.pdfSelectionRectsForTesting }
        assertEquals("$name: one selection before the gesture", 1, before.size)
        val (offsetBefore, zoomBefore) = withContext(Dispatchers.Main) {
            val view = checkNotNull(pdfView(activity))
            view.currentYOffset to view.zoom
        }
        gesture()
        var painted: List<RectF> = emptyList()
        var expected: RectF? = null
        for (attempt in 0 until 30) {
            delay(100)
            painted = withContext(Dispatchers.Main) { activity.pdfSelectionRectsForTesting }
            expected = withContext(Dispatchers.Main) { expectedSelection(activity) }
            val target = expected
            if (painted.isEmpty() || (target != null && painted.size == 1 && painted[0].near(target))) break
        }
        val (offsetAfter, zoomAfter) = withContext(Dispatchers.Main) {
            val view = checkNotNull(pdfView(activity))
            view.currentYOffset to view.zoom
        }
        val moved = abs(offsetAfter - offsetBefore) > 60f || abs(zoomAfter - zoomBefore) > 0.15f
        notes.append(
            "$name: offset $offsetBefore -> $offsetAfter, zoom $zoomBefore -> $zoomAfter, " +
                "selection ${before.single()} -> $painted, expected $expected, " +
                "card ${withContext(Dispatchers.Main) { activity.currentTranslationState?.javaClass?.simpleName }}\n"
        )
        assertTrue("$name: the gesture must move or zoom the page", moved)
        if (painted.isEmpty()) {
            assertEquals(
                "$name: the selection went away, so its card must too",
                null,
                withContext(Dispatchers.Main) { activity.currentTranslationState }
            )
            return
        }
        val target = checkNotNull(expected) { "$name: the selection's page is no longer laid out" }
        assertEquals("$name: one painted selection", 1, painted.size)
        assertTrue("$name: painted ${painted[0]}, the sentence is at $target", painted[0].near(target))
    }

    private suspend fun expectedSelection(activity: ReaderActivity): RectF? {
        val controller = activity.pdfTranslationControllerForTesting ?: return null
        val view = navigator(activity)?.publicationView ?: return null
        val geometry = controller.selectionGeometry() ?: return null
        val page = controller.selectionPageIndex() ?: return null
        return controller.mapPageGeometryToView(geometry, page, view.width, view.height)
    }

    /** The saved highlight is painted, once, where its text is now. */
    private suspend fun checkSavedHighlight(activity: ReaderActivity, name: String) {
        var painted: List<RectF> = emptyList()
        var expected: RectF? = null
        for (attempt in 0 until 30) {
            painted = withContext(Dispatchers.Main) { activity.pdfAnnotationRectsForTesting }
            expected = withContext(Dispatchers.Main) {
                val controller = activity.pdfTranslationControllerForTesting
                val view = navigator(activity)?.publicationView
                if (controller == null || view == null) {
                    null
                } else {
                    controller.mapPageGeometryToView(SAVED_HIGHLIGHT, 1, view.width, view.height)
                }
            }
            val target = expected
            if (target != null && painted.size == 1 && painted[0].near(target)) break
            delay(100)
        }
        notes.append("$name: highlight painted $painted, its text is at $expected\n")
        val target = checkNotNull(expected) { "$name: page two is not laid out" }
        assertEquals("$name: one painted highlight", 1, painted.size)
        assertTrue("$name: painted ${painted[0]}, its text is at $target", painted[0].near(target))
    }

    private fun RectF.near(other: RectF): Boolean =
        abs(left - other.left) < 4f && abs(top - other.top) < 4f &&
            abs(right - other.right) < 4f && abs(bottom - other.bottom) < 4f

    /** The status bar's icons and colour on light, dark and custom papers. */
    private suspend fun checkSystemBars(activity: ReaderActivity) {
        val original = withContext(Dispatchers.Main) { activity.currentAppearance }
        val cases = listOf(
            Triple("light", original.copy(theme = ReaderThemeOption.LIGHT, customBackgroundHex = ""), false),
            Triple("dark", original.copy(theme = ReaderThemeOption.DARK, customBackgroundHex = ""), true),
            Triple("sepia", original.copy(theme = ReaderThemeOption.SEPIA, customBackgroundHex = ""), false),
            Triple("custom-dark", original.copy(theme = ReaderThemeOption.LIGHT, customBackgroundHex = "#1F2A33"), true),
            Triple("custom-light", original.copy(theme = ReaderThemeOption.DARK, customBackgroundHex = "#F3E9D2"), false),
        )
        try {
            for ((name, appearance, dark) in cases) {
                withContext(Dispatchers.Main) { activity.applyAppearance(appearance) }
                delay(SETTLE_MILLIS)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                val (lightBars, lightNavigation) = withContext(Dispatchers.Main) {
                    val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    bars.isAppearanceLightStatusBars to bars.isAppearanceLightNavigationBars
                }
                val statusBar = withContext(Dispatchers.Main) { activity.systemBarInsetsForTesting().first }
                val brightness = statusBarBrightness(statusBar)
                notes.append(
                    "bars $name: lightStatusBars=$lightBars, lightNavigationBars=$lightNavigation, " +
                        "status bar brightness $brightness\n"
                )
                capture("android-pdf-bars-$name")
                assertEquals("$name: dark icons on a light page, light icons on a dark one", !dark, lightBars)
                assertEquals("$name: the navigation bar's icons go the same way", !dark, lightNavigation)
                if (dark) {
                    assertTrue("$name: the status bar must be dark, brightness $brightness", brightness < 0.35f)
                } else {
                    assertTrue("$name: the status bar must be light, brightness $brightness", brightness > 0.6f)
                }
            }
        } finally {
            withContext(Dispatchers.Main) { activity.applyAppearance(original) }
        }
    }

    private suspend fun closeDuringOcr(scenario: ActivityScenario<ReaderActivity>, activity: ReaderActivity) {
        withContext(Dispatchers.Main) {
            checkNotNull(pdfView(activity)).jumpTo(3)
        }
        for (attempt in 0 until 80) {
            if (withContext(Dispatchers.Main) { currentPage(activity) } == 3) break
            delay(100)
        }
        delay(SETTLE_MILLIS)
        withContext(Dispatchers.Main) {
            val rect = pageRect(activity, 3)
            val point = PointF(rect.left + SENTENCE_X * rect.width(), rect.top + TOP_SENTENCE_Y * rect.height())
            activity.lifecycleScope.launch { activity.translateAtForTesting(point) }
        }
        SystemClock.sleep(40)
        scenario.moveToState(Lifecycle.State.DESTROYED)
        // A throw from PdfRenderer.close() would have taken this process down.
        SystemClock.sleep(2_000)
        notes.append("closed the reader 40 ms into OCR of page 4: no crash\n")
    }

    /** A slow drag, so the page does not fling on after the finger lifts. */
    private suspend fun drag(activity: ReaderActivity, distance: Float) {
        val (x, y) = withContext(Dispatchers.Main) { screenPoint(activity, 0.5f, 0.75f) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val down = SystemClock.uptimeMillis()
        var sent = 0
        fun send(action: Int, travelled: Float) {
            val event = MotionEvent.obtain(down, down + sent * STEP_MILLIS, action, x, y - distance * travelled, 0)
            sent += 1
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        val steps = 24
        for (step in 1..steps) {
            SystemClock.sleep(STEP_MILLIS)
            send(MotionEvent.ACTION_MOVE, step.toFloat() / steps)
        }
        repeat(8) {
            SystemClock.sleep(STEP_MILLIS)
            send(MotionEvent.ACTION_MOVE, 1f)
        }
        send(MotionEvent.ACTION_UP, 1f)
        instrumentation.waitForIdleSync()
    }

    /**
     * Two fingers spreading apart low on the page, clear of the card: in the
     * middle they landed on the card, and the page never zoomed.
     */
    private suspend fun pinch(activity: ReaderActivity) {
        val (centerX, centerY) = withContext(Dispatchers.Main) { screenPoint(activity, 0.5f, 0.88f) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val properties = Array(2) { index ->
            MotionEvent.PointerProperties().apply {
                id = index
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        fun coordinates(span: Float) = Array(2) { index ->
            MotionEvent.PointerCoords().apply {
                x = centerX + (if (index == 0) -span else span) / 2f
                y = centerY
                pressure = 1f
                size = 1f
            }
        }
        val down = SystemClock.uptimeMillis()
        var sent = 0
        fun send(action: Int, pointers: Int, span: Float) {
            val event = MotionEvent.obtain(
                down, down + sent * STEP_MILLIS, action, pointers, properties, coordinates(span),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            sent += 1
            try { check(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            SystemClock.sleep(STEP_MILLIS)
        }
        val from = 260f
        val to = 620f
        send(MotionEvent.ACTION_DOWN, 1, from)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, from)
        val steps = 20
        for (step in 1..steps) send(MotionEvent.ACTION_MOVE, 2, from + (to - from) * step / steps)
        repeat(6) { send(MotionEvent.ACTION_MOVE, 2, to) }
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, to)
        send(MotionEvent.ACTION_UP, 1, to)
        instrumentation.waitForIdleSync()
    }

    /** A point of the page view, as fractions of its size, on the screen. */
    private fun screenPoint(activity: ReaderActivity, fx: Float, fy: Float): Pair<Float, Float> {
        val view = checkNotNull(navigator(activity)).publicationView
        val at = IntArray(2).also(view::getLocationOnScreen)
        return (at[0] + fx * view.width) to (at[1] + fy * view.height)
    }

    /**
     * Where a page sits in the navigator's view, from AndroidPdfViewer's own
     * offsets and zoom (the reader works it out the same way).
     */
    private fun pageRect(activity: ReaderActivity, index: Int): RectF {
        val root = checkNotNull(navigator(activity)).publicationView
        val pdfView = checkNotNull(findPdfView(root))
        val zoom = pdfView.zoom
        val size = pdfView.getPageSize(index)
        val widest = (0 until pdfView.pageCount).maxOf { pdfView.getPageSize(it).width }
        val before = (0 until index).sumOf { pdfView.getPageSize(it).height.toDouble() }.toFloat() * zoom +
            index * pdfView.spacingPx
        val left = pdfView.currentXOffset + (widest - size.width) * zoom / 2f
        val top = pdfView.currentYOffset + before
        val rect = RectF(left, top, left + size.width * zoom, top + size.height * zoom)
        val rootAt = IntArray(2).also(root::getLocationInWindow)
        val pdfAt = IntArray(2).also(pdfView::getLocationInWindow)
        rect.offset((pdfAt[0] - rootAt[0]).toFloat(), (pdfAt[1] - rootAt[1]).toFloat())
        return rect
    }

    /** Dark pixels on the screen near a point of the navigator's view. */
    private suspend fun hasInkNear(activity: ReaderActivity, point: PointF): Boolean {
        val (x, y) = withContext(Dispatchers.Main) {
            val view = checkNotNull(navigator(activity)).publicationView
            val at = IntArray(2).also(view::getLocationOnScreen)
            (at[0] + point.x).toInt() to (at[1] + point.y).toInt()
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            for (dx in -INK_RADIUS..INK_RADIUS step 2) for (dy in -INK_RADIUS / 2..INK_RADIUS / 2 step 2) {
                val px = (x + dx).coerceIn(0, bitmap.width - 1)
                val py = (y + dy).coerceIn(0, bitmap.height - 1)
                val color = bitmap.getPixel(px, py)
                if (Color.red(color) < 90 && Color.green(color) < 90 && Color.blue(color) < 90) return true
            }
            return false
        } finally {
            bitmap.recycle()
        }
    }

    private fun statusBarBrightness(statusBarHeight: Int): Float {
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val bottom = statusBarHeight.coerceIn(4, bitmap.height)
            var total = 0f
            var count = 0
            // The middle of the bar, clear of the clock and the status icons.
            for (x in (bitmap.width * 2 / 5) until (bitmap.width * 3 / 5) step 6) {
                for (y in 2 until bottom - 1 step 3) {
                    val color = bitmap.getPixel(x, y)
                    total += (Color.red(color) + Color.green(color) + Color.blue(color)) / (3f * 255f)
                    count += 1
                }
            }
            return if (count == 0) -1f else total / count
        } finally {
            bitmap.recycle()
        }
    }

    private fun currentPage(activity: ReaderActivity): Int? =
        navigator(activity)?.currentLocator?.value?.locations?.position?.minus(1)

    private fun navigator(activity: ReaderActivity): PdfNavigatorFragment<*, *>? =
        activity.supportFragmentManager.fragments
            .filterIsInstance<PdfNavigatorFragment<*, *>>()
            .firstOrNull { it.view != null }

    private fun pdfView(activity: ReaderActivity): PDFView? =
        navigator(activity)?.publicationView?.let(::findPdfView)

    private fun findPdfView(view: View): PDFView? {
        if (view is PDFView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) findPdfView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun waitForPdf(scenario: ActivityScenario<ReaderActivity>): ReaderActivity {
        var ready: ReaderActivity? = null
        repeat(200) {
            scenario.onActivity { activity ->
                val view = pdfView(activity)
                if (view != null && view.pageCount == PAGE_WORDS.size && view.zoom > 0f &&
                    view.getPageSize(0).width > 0f && view.height > 0 &&
                    activity.pdfTranslationControllerForTesting != null
                ) {
                    ready = activity
                }
            }
            ready?.let { return it }
            SystemClock.sleep(100)
        }
        error("ReaderActivity did not lay out the PDF")
    }

    private fun createPdf(file: File) {
        val document = PdfDocument()
        try {
            // No background is painted: the page stays transparent, as many
            // text PDFs are.
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 30f
            }
            PAGE_WORDS.forEachIndexed { index, word ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                page.canvas.drawText("Page $word opens here.", 60f, 80f, paint)
                page.canvas.drawText("Page $word ends here.", 60f, 790f, paint)
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(screenshotDirectory(), "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeNotes() {
        File(screenshotDirectory(), "android-pdf-notes.txt").writeText(notes.toString())
    }

    private fun screenshotDirectory(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.getExternalFilesDir(null), "learning-ui").apply { mkdirs() }
    }

    private companion object {
        val PAGE_WORDS = listOf("one", "two", "three", "four")

        /** Over "Page two opens here." (x 60…300, baseline 80 of 595 × 842). */
        val SAVED_HIGHLIGHT = RectF(0.10f, 0.066f, 0.50f, 0.104f)
        const val SETTLE_MILLIS = 800L
        const val STEP_MILLIS = 16L
        const val INK_RADIUS = 18

        /** Inside the word "Page" that starts every sentence (x 60…125 of 595). */
        const val SENTENCE_X = 0.16f

        /** The middle of the glyphs drawn on the 80 and 790 baselines of 842. */
        const val TOP_SENTENCE_Y = 0.084f
        const val BOTTOM_SENTENCE_Y = 0.928f
    }
}
