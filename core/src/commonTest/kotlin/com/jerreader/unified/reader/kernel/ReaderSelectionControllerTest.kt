package com.jerreader.unified.reader.kernel

import com.jerreader.unified.domain.LanguageCode
import com.jerreader.unified.library.ReaderAppearance
import com.jerreader.unified.library.ReaderTextOrientation
import com.jerreader.unified.library.ReaderThemeOption
import com.jerreader.unified.reader.selection.ReaderSelectionGestureGate
import com.jerreader.unified.reader.selection.ReaderWritingMode
import com.jerreader.unified.translation.QuickTranslationUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Drives the whole quick-translate sequence against a scripted web view.
 *
 * The point is the *order and coupling* of the steps, which is what actually
 * diverged between the two apps: which coordinate space the tap is in, whether
 * the app or the page decides the sentence boundary, whether tiles are merged
 * before they are painted. A fake bridge pins all of that down without a
 * simulator or a publication.
 */
class ReaderSelectionControllerTest {

    /** Records every script it is asked to run and replays canned answers. */
    private class FakeBridge(
        private val geometry: ReaderContentGeometry = ReaderContentGeometry(),
        private val blockText: String? = """{"text":"これは文です。次の文もある。","offset":3,"vertical":false}""",
        private val selectResult: String? =
            """{"ok":true,"vertical":false,"rects":[
                 {"x":10,"y":100,"w":40,"h":18},{"x":50,"y":100,"w":30,"h":18}]}""",
        private val currentSelection: String? = null
    ) : ReaderWebViewBridge {
        val scripts = mutableListOf<String>()

        override fun evaluateJavaScript(script: String, callback: ReaderScriptCallback) {
            scripts.add(script)
            val answer = when {
                script.contains("__jerreaderQuickSelectionActive = false") -> """{"ok":true}"""
                script.contains("selection.addRange(range)") -> selectResult
                script.contains("paintTiles(") -> """{"ok":true}"""
                script.contains("normalizedText") -> currentSelection
                script.contains("offset: offset") || script.contains("offset:offset") -> blockText
                else -> blockText
            }
            callback.onResult(answer)
        }

        override fun contentGeometry(): ReaderContentGeometry = geometry
    }

    private fun controller(
        bridge: ReaderWebViewBridge,
        orientation: ReaderTextOrientation = ReaderTextOrientation.PUBLICATION,
        publicationIsVertical: Boolean = false
    ) = ReaderSelectionController(
        bridge = bridge,
        appearance = {
            ReaderAppearance.forSelection(
                theme = ReaderThemeOption.LIGHT,
                orientation = orientation
            )
        },
        bookLanguage = { LanguageCode.JAPANESE },
        publicationIsVertical = { publicationIsVertical }
    )

    @Test
    fun `a tap selects one sentence and publishes merged tiles`() = runTest {
        val bridge = FakeBridge()
        val controller = controller(bridge)

        val text = controller.selectAt(
            x = 20.0,
            y = 105.0,
            unit = QuickTranslationUnit.SENTENCE,
            uptimeMillis = 0
        )

        assertEquals("これは文です。", text)
        val state = controller.state.value
        assertTrue(state.isActive)
        // The two fragments are contiguous on one line, so they merge into one
        // tile before anything is painted.
        assertEquals(1, state.tiles.size)
        assertEquals(10.0, state.tiles[0].left)
        assertEquals(80.0, state.tiles[0].right)
        assertNotNull(state.anchorFrame)
    }

    @Test
    fun `merging happens before painting`() = runTest {
        val bridge = FakeBridge()

        controller(bridge).selectAt(20.0, 105.0, QuickTranslationUnit.SENTENCE, 0)

        val paint = bridge.scripts.last { it.contains("paintTiles(") }
        // One merged tile, not the two raw fragments the page reported.
        assertContains(paint, "{x:10.0,y:100.0,w:70.0,h:18.0}")
    }

    @Test
    fun `paragraph mode takes the whole block`() = runTest {
        val controller = controller(FakeBridge())

        val text = controller.selectAt(
            20.0,
            105.0,
            QuickTranslationUnit.PARAGRAPH,
            uptimeMillis = 0
        )

        assertEquals("これは文です。次の文もある。", text)
    }

    @Test
    fun `a tap right after a native selection is ignored`() = runTest {
        val bridge = FakeBridge()
        val controller = controller(bridge)

        controller.noteNativeSelection(uptimeMillis = 1_000)
        val text = controller.selectAt(20.0, 105.0, QuickTranslationUnit.SENTENCE, 1_200)

        assertNull(text, "the trailing tap would replace a hand-made selection")
        assertTrue(bridge.scripts.isEmpty(), "a suppressed tap must not touch the page")
    }

    @Test
    fun `the tap is converted into the page's own coordinate space`() = runTest {
        // An Android-shaped geometry: 3x density, web view offset inside the overlay.
        val bridge = FakeBridge(
            geometry = ReaderContentGeometry(scale = 3.0, originX = 30.0, originY = 150.0)
        )

        controller(bridge).selectAt(
            x = 90.0,
            y = 300.0,
            unit = QuickTranslationUnit.SENTENCE,
            uptimeMillis = 0
        )

        // (90 - 30) / 3 = 20, (300 - 150) / 3 = 50
        val probe = bridge.scripts.first { it.contains("blockAt(") }
        assertContains(probe, "blockAt(20.0, 50.0)")
    }

    @Test
    fun `published tiles are in overlay space rather than CSS pixels`() = runTest {
        val bridge = FakeBridge(
            geometry = ReaderContentGeometry(scale = 2.0, originX = 5.0, originY = 7.0)
        )
        val controller = controller(bridge)

        controller.selectAt(20.0, 105.0, QuickTranslationUnit.SENTENCE, 0)

        val tile = controller.state.value.tiles.single()
        assertEquals(10.0 * 2 + 5, tile.left)
        assertEquals(100.0 * 2 + 7, tile.top)
    }

    @Test
    fun `the reader's vertical setting overrides what the page reports`() = runTest {
        // The page says horizontal; some web views misreport -epub-writing-mode.
        val bridge = FakeBridge(
            selectResult = """{"ok":true,"vertical":false,"rects":[
                {"x":200,"y":40,"w":20,"h":90},{"x":200,"y":130,"w":20,"h":70}]}"""
        )
        val controller = controller(bridge, orientation = ReaderTextOrientation.VERTICAL)

        controller.selectAt(20.0, 105.0, QuickTranslationUnit.SENTENCE, 0)

        val state = controller.state.value
        assertEquals(ReaderWritingMode.VERTICAL, state.writingMode)
        // Grouped as one column: the two runs are contiguous down the y axis.
        assertEquals(1, state.tiles.size)
        assertEquals(40.0, state.tiles[0].top)
        assertEquals(200.0, state.tiles[0].bottom)
    }

    @Test
    fun `a tap that misses prose selects nothing and paints nothing`() = runTest {
        val bridge = FakeBridge(blockText = null)
        val controller = controller(bridge)

        val text = controller.selectAt(20.0, 105.0, QuickTranslationUnit.SENTENCE, 0)

        assertNull(text)
        assertTrue(controller.state.value.tiles.isEmpty())
        assertTrue(bridge.scripts.none { it.contains("paintTiles(") })
    }

    @Test
    fun `a page that reports no usable rectangles publishes nothing`() = runTest {
        val bridge = FakeBridge(
            selectResult = """{"ok":true,"vertical":false,"rects":[{"x":1,"y":1,"w":0.1,"h":0.1}]}"""
        )
        val controller = controller(bridge)

        assertNull(controller.selectAt(20.0, 105.0, QuickTranslationUnit.SENTENCE, 0))
        assertTrue(controller.state.value.tiles.isEmpty())
    }

    @Test
    fun `a miss after a hit does not leave the old highlight on screen`() = runTest {
        // The page overlay is torn down at the start of every attempt. If the
        // published state is not torn down with it, the native highlight layer
        // keeps painting the previous sentence over a page that no longer has
        // it — the two layers disagree until the next successful tap.
        var blockAnswer: String? =
            """{"text":"これは文です。次の文もある。","offset":3,"vertical":false}"""
        val bridge = object : ReaderWebViewBridge {
            override fun evaluateJavaScript(script: String, callback: ReaderScriptCallback) {
                callback.onResult(
                    when {
                        script.contains("__jerreaderQuickSelectionActive = false") ->
                            """{"ok":true}"""
                        script.contains("selection.addRange(range)") ->
                            """{"ok":true,"vertical":false,"rects":[{"x":10,"y":100,"w":40,"h":18}]}"""
                        script.contains("paintTiles(") -> """{"ok":true}"""
                        else -> blockAnswer
                    }
                )
            }

            override fun contentGeometry() = ReaderContentGeometry()
        }
        val controller = controller(bridge)

        controller.selectAt(20.0, 105.0, QuickTranslationUnit.SENTENCE, 0)
        assertTrue(controller.state.value.isActive)

        blockAnswer = null
        assertNull(controller.selectAt(20.0, 900.0, QuickTranslationUnit.SENTENCE, 0))

        assertTrue(
            controller.state.value.tiles.isEmpty(),
            "the previous selection is still being painted"
        )
    }

    @Test
    fun `clearing resets both the page and the gate`() = runTest {
        val bridge = FakeBridge()
        val controller = controller(bridge)
        controller.selectAt(20.0, 105.0, QuickTranslationUnit.SENTENCE, 0)
        controller.noteNativeSelection(uptimeMillis = 1_000)

        controller.clear()

        assertEquals(ReaderSelectionState.Empty, controller.state.value)
        assertTrue(bridge.scripts.last().contains("__jerreaderQuickSelectionActive = false"))
        // The gate is reset, so the next tap is honoured.
        assertTrue(
            controller.selectAt(20.0, 105.0, QuickTranslationUnit.SENTENCE, 1_100) != null
        )
    }

    @Test
    fun `the gate expires on its own`() {
        val gate = ReaderSelectionGestureGate(suppressionMillis = 500)
        gate.noteNativeSelection(uptimeMillis = 0)

        assertTrue(gate.shouldSuppressTap(400))
        assertTrue(!gate.shouldSuppressTap(600))
    }
}
