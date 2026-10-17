package com.jerreader.android.reader

import android.content.Context
import android.content.Intent
import android.graphics.PointF
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
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
                captureReader("android-reader")

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
                captureReader("android-reader-translation")
                verifyOverlayMatrix(activity)
                if (android.os.Build.VERSION.SDK_INT >= 24) verifyReaderInterfaceControls(activity)
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

    private suspend fun verifyOverlayMatrix(activity: ReaderActivity) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = (instrumentation.targetContext.applicationContext as JerreaderApplication).graph
        val original = graph.appSettings.preferences.value
        val display = graph.translationSettings.preferences.value.displayMode
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val viewport = withContext(Dispatchers.Main) { android.graphics.RectF(checkNotNull(activity.translationViewportForTesting)) }
        val width = viewport.width() / density
        val height = viewport.height() / density
        val cases = listOf(
            Triple("horizontal-top", android.graphics.RectF(24f, 60f, width - 24f, 100f), false),
            Triple("horizontal-bottom", android.graphics.RectF(24f, height - 180f, width - 24f, height - 140f), false),
            Triple("horizontal-paragraph", android.graphics.RectF(24f, 190f, width - 24f, 390f), false),
            Triple("vertical-left", android.graphics.RectF(28f, 110f, 52f, height - 150f), true),
            Triple("vertical-right", android.graphics.RectF(width - 52f, 110f, width - 28f, height - 150f), true),
            Triple("vertical-middle", android.graphics.RectF(width / 2 - 12f, 110f, width / 2 + 12f, height - 150f), true)
        )
        val evidence = StringBuilder()
        val evidenceDirectory = File(instrumentation.targetContext.getExternalFilesDir(null), "learning-ui").apply { mkdirs() }
        val evidenceFile = File(evidenceDirectory, "translation-geometry-matrix.txt")
        try {
            for ((name, selection, vertical) in cases) for (long in listOf(false, true)) for (ruby in listOf(false, true)) {
                graph.appSettings.updateJapaneseFurigana(ruby)
                graph.translationSettings.updateDisplayMode(com.jerreader.shared.translation.TranslationDisplayMode.TOP_BANNER)
                val state = TranslationCardState.Success("毎朝、ご飯を食べました。",
                    com.jerreader.shared.translation.TranslationResult(
                        if (long) "这是用于离线验证浮窗自动避让的长译文，内容较多时应在卡片内滚动。".repeat(30) else "每天早晨吃了饭。",
                        com.jerreader.shared.domain.LanguageCode.JAPANESE,
                        com.jerreader.shared.domain.LanguageCode.CHINESE_SIMPLIFIED, "ui-geometry-mock"))
                withContext(Dispatchers.Main) { activity.showTranslationGeometryForTesting(selection, vertical, state) }
                var frame: android.graphics.RectF? = null
                var previous: android.graphics.RectF? = null
                var stable = 0
                val sampledFrames = mutableListOf<String>()
                repeat(30) {
                    kotlinx.coroutines.delay(150)
                    val next = withContext(Dispatchers.Main) { activity.translationCardFrameForTesting?.let { android.graphics.RectF(it) } }
                    sampledFrames += next?.toString() ?: "not-measured"
                    if (next != null && next.width() > 0 && next.height() > 0) {
                        stable = if (previous == next) stable + 1 else 0
                        previous = next
                        if (stable >= 3) frame = next
                    }
                }
                if (stable < 3 || frame == null) {
                    captureReader("android-translation-failure-$name-long-$long-ruby-$ruby")
                    File(instrumentation.targetContext.getExternalFilesDir(null), "learning-ui/translation-failure-frames.txt")
                        .writeText("$name long=$long ruby=$ruby\n" + sampledFrames.joinToString("\n"))
                }
                check(stable >= 3) { "Translation card kept moving: $name long=$long ruby=$ruby; samples=$sampledFrames" }
                val card = checkNotNull(frame) { "No stable translation card: $name long=$long ruby=$ruby" }
                val source = withContext(Dispatchers.Main) { checkNotNull(activity.translationSourceFrameForTesting) }
                val safe = android.graphics.RectF(viewport.left + 12 * density, viewport.top + 52 * density,
                    viewport.right - 12 * density, viewport.bottom - 128 * density)
                assertTrue("Overlaps selection $name long=$long ruby=$ruby: card=$card source=$source", !android.graphics.RectF.intersects(card, source))
                assertTrue("Outside safe viewport $name: card=$card safe=$safe", card.left >= safe.left - 2 && card.right <= safe.right + 2 && card.top >= safe.top - 2 && card.bottom <= safe.bottom + 2)
                if (vertical) assertTrue("Vertical card must stay beside the column", card.right <= source.left + 1 || card.left >= source.right - 1)
                val id = "$name-${if (long) "long" else "short"}-${if (ruby) "furigana" else "plain"}"
                evidence.appendLine("$id card=$card source=$source safe=$safe")
                evidenceFile.writeText(evidence.toString())
                captureReader("android-translation-$id")
            }
            check(evidence.lineSequence().count { it.isNotBlank() } == 24)
        } finally {
            graph.translationSettings.updateDisplayMode(display)
            graph.appSettings.updateJapaneseFurigana(original.japaneseFuriganaEnabled)
        }
    }

    @androidx.annotation.RequiresApi(24)
    private suspend fun verifyReaderInterfaceControls(activity: ReaderActivity) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph = (instrumentation.targetContext.applicationContext as JerreaderApplication).graph
        val original = graph.appSettings.preferences.value
        fun find(label: String, action: Int? = null): android.view.accessibility.AccessibilityNodeInfo? {
            if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
            val labels = if (label == "阅读设置") setOf(label, "格式") else setOf(label)
            fun actionable(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
                if (node.actionList.any { it.id == action }) return node
                for (index in 0 until node.childCount) node.getChild(index)?.let { actionable(it) }?.let { return it }
                return null
            }
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo?): android.view.accessibility.AccessibilityNodeInfo? {
                if (node == null) return null
                val named = node.text?.toString()?.lineSequence()?.any { it in labels } == true ||
                    node.contentDescription?.toString() in labels
                if (named) {
                    if (action == null) return node
                    // The captured Compose tree places a button's description
                    // below its clickable parent (the slider is similar).
                    var parent: android.view.accessibility.AccessibilityNodeInfo? = node
                    while (parent != null) {
                        if (parent.actionList.any { it.id == action }) return parent
                        parent = parent.parent
                    }
                    actionable(node)?.let { return it }
                }
                for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
                return null
            }
            return visit(instrumentation.uiAutomation.rootInActiveWindow)
        }
        suspend fun waitFor(label: String, action: Int? = null): android.view.accessibility.AccessibilityNodeInfo {
            repeat(50) {
                instrumentation.waitForIdleSync()
                find(label, action)?.let { return it }
                kotlinx.coroutines.delay(100)
            }
            val nodes = mutableListOf<String>()
            fun collect(node: android.view.accessibility.AccessibilityNodeInfo?) {
                if (node == null) return
                nodes += "text=${node.text}; description=${node.contentDescription}; actions=${node.actionList.map { it.id }}; visible=${node.isVisibleToUser}"
                for (index in 0 until node.childCount) collect(node.getChild(index))
            }
            collect(instrumentation.uiAutomation.rootInActiveWindow)
            File(instrumentation.targetContext.getExternalFilesDir(null), "learning-ui/reader-controls-accessibility.txt")
                .writeText(nodes.joinToString("\n"))
            captureReader("android-reader-controls-failure")
            error("Reader setting is missing: $label")
        }
        suspend fun click(label: String) {
            var node: android.view.accessibility.AccessibilityNodeInfo? = waitFor(label, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            while (node != null && !node.isClickable) node = node.parent
            check(node?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true)
            kotlinx.coroutines.delay(300)
        }
        fun back() {
            val time = SystemClock.uptimeMillis()
            for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP))
                check(instrumentation.uiAutomation.injectInputEvent(android.view.KeyEvent(time,
                    SystemClock.uptimeMillis(), action, android.view.KeyEvent.KEYCODE_BACK, 0), true))
        }
        try {
            click("阅读设置")
            waitFor("沉浸阅读（隐藏状态栏）")
            for ((label, value) in listOf("顶部栏透明度" to .35f, "底部栏透明度" to .75f)) {
                val slider = waitFor(label, android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id)
                val arguments = android.os.Bundle().apply { putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }
                check(slider.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, arguments))
            }
            waitFor("日语注音")
            val loaded = com.jerreader.android.settings.AndroidAppSettingsStore(instrumentation.targetContext).preferences.value
            assertEquals(.35f, loaded.topBarTransparency, .001f)
            assertEquals(.75f, loaded.bottomBarTransparency, .001f)
            click("沉浸阅读（隐藏状态栏）")
            assertEquals(!original.immersiveReading, graph.appSettings.preferences.value.immersiveReading)
            captureReader("android-reader-interface-controls")
            back()
            var matched = false
            repeat(40) {
                kotlinx.coroutines.delay(100)
                val visible = withContext(Dispatchers.Main) {
                    androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                }
                matched = visible == original.immersiveReading
            }
            assertTrue("Immersive preference must affect the real Activity status bar", matched)
            captureReader("android-reader-status-bar-option")
        } finally {
            if (find("顶部栏透明度") != null) back()
            graph.appSettings.updateTopBarTransparency(original.topBarTransparency)
            graph.appSettings.updateBottomBarTransparency(original.bottomBarTransparency)
            graph.appSettings.updateImmersiveReading(original.immersiveReading)
            graph.appSettings.updateJapaneseFurigana(original.japaneseFuriganaEnabled)
        }
    }

    private fun captureReader(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(500)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "learning-ui").apply { mkdirs() }
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
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
