package com.jerreader.android.reader

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.jerreader.android.JerreaderApplication
import com.jerreader.android.library.PublicationIntegrity
import com.jerreader.android.test.createSyntheticEpub
import com.jerreader.shared.library.ReaderAppearance
import com.jerreader.shared.library.ReaderLayoutMode
import com.jerreader.shared.library.ReaderTextOrientation
import com.jerreader.shared.library.ReaderThemeOption
import com.jerreader.shared.translation.AIReadingAction
import com.jerreader.shared.translation.MockTranslationService
import com.jerreader.shared.translation.QuickTranslationUnit
import com.jerreader.shared.translation.TranslationProviderMode
import com.jerreader.shared.ui.AIResultState
import com.jerreader.shared.ui.TranslationCardState
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * 2.1.0 §11 on a device, offline with the mock service: tap a sentence, 读懂
 * streams its answer into the card, 追问 answers a preset, and the card stays
 * within half of the safe area without covering the selection. A typed 追问
 * then takes the focus from the page, 存为笔记 still keeps an answer as the
 * passage's note, and 添加笔记 opens that note.
 *
 * The other three of light and dark, horizontal and vertical follow, with
 * 读懂's answer on the card each time (§11).
 *
 * The chips are tapped through accessibility, the way TalkBack would, so the
 * test goes through the same click handlers as a finger.
 */
@OptIn(ExperimentalReadiumApi::class)
class ReaderAIActionBarTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun comprehensionAndFollowUpStreamIntoAHalfScreenCard() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val graph = (context.applicationContext as JerreaderApplication).graph
        val originalService = graph.translationService
        val originalTranslation = graph.translationSettings.preferences.value
        val originalInterface = graph.appSettings.preferences.value
        val source = File(context.cacheDir, "jerreader-ai-actions-${UUID.randomUUID()}.epub")
        createSyntheticEpub(source)
        source.setLastModified(1_700_600_000_000)
        val sourceSnapshot = PublicationIntegrity.capture(source)
        val book = graph.importService.importPublication(Uri.fromFile(source)).book
        val stored = graph.publicationStore.resolvePublication(book.publicationFileName)
        val storedSnapshot = PublicationIntegrity.capture(stored)

        try {
            graph.appSettings.updateLayoutMode(ReaderLayoutMode.STANDARD)
            graph.appSettings.updateAIActionBarHidden(false)
            graph.translationService = MockTranslationService()
            // With a provider set up the chips answer instead of opening
            // 先接入一个 AI 模型. Every answer still comes from the mock.
            graph.translationSettings.updateProviderMode(TranslationProviderMode.DIRECT_API)
            graph.translationSettings.updateQuickTranslationEnabled(true)
            graph.translationSettings.updateQuickTranslationUnit(QuickTranslationUnit.SENTENCE)
            val intent = Intent(context, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id)

            ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                val activity = waitForActivityAndNavigator(scenario)
                val point = elementCenter(activity, "english-word")

                // 1. Tap a sentence.
                val sentence = withContext(Dispatchers.Main) { activity.translateAtForTesting(point) }
                assertTrue("Expected a sentence translation, got $sentence", sentence is TranslationCardState.Success)
                sentence as TranslationCardState.Success
                assertEquals("She went home.", sentence.sourceText)
                assertFalse("The card must hold the whole translation", sentence.isStreaming)

                val snapshots = withContext(Dispatchers.Main) { activity.aiActionsSnapshotsForTesting }
                val comprehension = Collections.synchronizedList(mutableListOf<AIResultState>())
                val watcher = launch(Dispatchers.Default) {
                    snapshots.collect { snapshot ->
                        snapshot.results[AIReadingAction.COMPREHENSION]?.let(comprehension::add)
                    }
                }
                try {
                    // 2. 读懂, and its answer arrives in pieces.
                    click("读懂")
                    val understood = waitForState("the 读懂 answer") {
                        when (val result = snapshots.value.results[AIReadingAction.COMPREHENSION]) {
                            is AIResultState.Done -> result
                            is AIResultState.Failed -> error("读懂 failed: ${result.message}")
                            else -> null
                        }
                    }
                    assertEquals(MockTranslationService.COMPREHENSION_TEXT, understood.text)
                    val pieces = synchronized(comprehension) { comprehension.toList() }
                    assertTrue(
                        "读懂 showed no partial text before its answer: $pieces",
                        pieces.any { piece ->
                            piece is AIResultState.Generating && piece.text.isNotEmpty() &&
                                piece.text.length < understood.text.length && understood.text.startsWith(piece.text)
                        }
                    )
                    waitForNode("the 读懂 answer on the card") { it.mentions(MockTranslationService.COMPREHENSION_TEXT) }
                    waitForNode("the 读懂 result title") { it.says("读懂这段") }
                    captureReader("android-ai-comprehension")
                    assertHalfScreenAndClear(activity, "读懂")

                    // 3. 追问, one of its presets, and the answer.
                    click("追问")
                    val preset = "这个从句修饰谁？"
                    click(preset)
                    val answer = waitForState("the 追问 answer") {
                        when (val result = snapshots.value.followUps.lastOrNull()?.answer) {
                            is AIResultState.Done -> result
                            is AIResultState.Failed -> error("追问 failed: ${result.message}")
                            else -> null
                        }
                    }
                    assertEquals(MockTranslationService.FOLLOW_UP_PREFIX + preset, answer.text)
                    assertEquals(listOf(preset), snapshots.value.followUps.map { it.question })
                    assertEquals(AIReadingAction.FOLLOW_UP, snapshots.value.selected)
                    waitForNode("the 追问 answer on the card") { it.mentions(answer.text) }
                    captureReader("android-ai-follow-up")

                    // 4. The card is at most half of the safe area and keeps clear of the selection.
                    assertHalfScreenAndClear(activity, "追问")

                    // 5. A typed 追问: the field takes the focus from the page,
                    // and the passage must keep its selection for 存为笔记.
                    val typed = "她为什么回家？"
                    check(
                        waitForNode("the 追问 field") { it.isFollowUpField() }
                            .performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ) { "Could not tap the 追问 field" }
                    val field = waitForNode("the focused 追问 field") { it.isFollowUpField() && it.isFocused }
                    check(
                        field.performAction(
                            AccessibilityNodeInfo.ACTION_SET_TEXT,
                            Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, typed)
                            }
                        )
                    ) { "Could not type the 追问" }
                    click("发送追问")
                    val typedAnswer = waitForState("the typed 追问 answer") {
                        val entry = snapshots.value.followUps.lastOrNull()?.takeIf { it.question == typed }
                        when (val result = entry?.answer) {
                            is AIResultState.Done -> result
                            is AIResultState.Failed -> error("追问 failed: ${result.message}")
                            else -> null
                        }
                    }
                    waitForNode("the typed 追问 answer on the card") { it.mentions(typedAnswer.text) }

                    // 6. 存为笔记 keeps an answer as the note of the passage.
                    click("存为笔记")
                    val saved = waitForState("the saved note") {
                        graph.readerRecords.observeAnnotations(book.id).first().singleOrNull()
                    }
                    assertEquals("She went home.", saved.selectedText)
                    assertTrue(saved.noteText, saved.noteText.startsWith("【追问】\n问："))

                    // 7. 添加笔记 then opens that note, as on iOS, instead of a
                    // new one that would replace it.
                    click("更多翻译操作")
                    click("添加笔记")
                    waitForNode("the saved note in the editor") { it.mentions("【追问】") }
                    captureReader("android-ai-note-editor")
                    click("取消")
                    assertEquals(
                        listOf(saved.noteText),
                        graph.readerRecords.observeAnnotations(book.id).first().map { it.noteText }
                    )

                    // 8. §11 also wants the card dark and in vertical text:
                    // the other three of light and dark, horizontal and
                    // vertical, each with 读懂's answer on it. Across the page
                    // the card keeps within half of the safe area; beside a
                    // vertical column its chips wrap onto more rows within the
                    // narrow card, and it keeps clear of the sentence.
                    val original = withContext(Dispatchers.Main) { activity.currentAppearance }
                    try {
                        for ((theme, orientation) in listOf(
                            ReaderThemeOption.DARK to ReaderTextOrientation.HORIZONTAL,
                            ReaderThemeOption.LIGHT to ReaderTextOrientation.VERTICAL,
                            ReaderThemeOption.DARK to ReaderTextOrientation.VERTICAL
                        )) {
                            val vertical = orientation == ReaderTextOrientation.VERTICAL
                            val label = "${theme.name.lowercase()}-${orientation.name.lowercase()}"
                            setAppearance(activity, original.copy(theme = theme, orientation = orientation))
                            val target = elementCenter(activity, if (vertical) "japanese-word" else "english-word")
                            val tapped = withContext(Dispatchers.Main) { activity.translateAtForTesting(target) }
                            assertTrue("$label: expected a sentence translation, got $tapped", tapped is TranslationCardState.Success)
                            if (vertical) assertChipsWrapWithinTheCard(activity, label)
                            click("读懂")
                            waitForNode("the 读懂 answer, $label") { it.mentions(MockTranslationService.COMPREHENSION_TEXT) }
                            assertChipStaysInView(activity, "读懂, $label")
                            if (vertical) assertClearOfTheSentence(activity, "读懂, $label")
                            else assertHalfScreenAndClear(activity, "读懂, $label")
                            captureReader("android-ai-$label")
                            withContext(Dispatchers.Main) { activity.dismissTranslationForTesting() }
                        }
                    } finally {
                        withContext(Dispatchers.Main) { activity.applyAppearance(original) }
                    }
                } finally {
                    watcher.cancel()
                }
            }

            assertTrue(PublicationIntegrity.isUnchanged(sourceSnapshot))
            assertTrue(PublicationIntegrity.isUnchanged(storedSnapshot))
        } finally {
            graph.translationService = originalService
            graph.translationSettings.updateProviderMode(originalTranslation.providerMode)
            graph.translationSettings.updateQuickTranslationEnabled(originalTranslation.quickTranslationEnabled)
            graph.translationSettings.updateQuickTranslationUnit(originalTranslation.quickTranslationUnit)
            graph.appSettings.updateLayoutMode(originalInterface.layoutMode)
            graph.appSettings.updateAIActionBarHidden(originalInterface.aiActionBarHidden)
            graph.bookService.delete(book.id)
            source.delete()
        }
    }

    /**
     * Waits until the card stops moving (an answer grows it, and the collision
     * step may move it once more), then checks it against the safe area and
     * the selection it was opened from.
     */
    private suspend fun assertHalfScreenAndClear(activity: ReaderActivity, step: String) {
        val (card, safe, _) = settledCard(activity, step)
        assertTrue(
            "$step: the card ($card) is taller than half of the safe area ($safe)",
            card.height() <= safe.height() / 2f + 2f
        )
        assertClearOfTheSentence(activity, step)
    }

    /** The settled card stays in the safe area and off the selection it was opened from. */
    private suspend fun assertClearOfTheSentence(activity: ReaderActivity, step: String) {
        val (card, safe, selection) = settledCard(activity, step)
        assertTrue(
            "$step: the card ($card) leaves the safe area ($safe)",
            card.top >= safe.top - 1f && card.bottom <= safe.bottom + 1f
        )
        checkNotNull(selection) { "$step: the card has no selection to keep clear of" }
        assertFalse(
            "$step: the card ($card) covers the selection ($selection)",
            RectF.intersects(card, selection)
        )
    }

    /**
     * The chips stay in view with the answer (2.1.0 §4): the card follows the
     * answer until its chips reach the top, as iOS does, so 读懂 is whole on
     * screen once it has answered. A chip scrolled even partly out of the
     * card is clipped, and one scrolled right out has no node at all.
     */
    private suspend fun assertChipStaysInView(activity: ReaderActivity, step: String) {
        settledCard(activity, step)
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val chip = Rect().also { bounds ->
            // The label is a node of its own, only as tall as its line; the
            // chip is the clickable node above it.
            var node: AccessibilityNodeInfo? = waitForNode("the 读懂 chip, $step") { it.says("读懂") }
            while (node != null && !node.isClickable) node = node.parent
            checkNotNull(node) { "$step: the 读懂 chip has no clickable node" }.getBoundsInScreen(bounds)
        }
        assertTrue(
            "$step: the 读懂 chip ($chip) is cut off at the edge of the card",
            chip.height() >= 30f * density * 0.9f
        )
    }

    /**
     * The side card beside a vertical column is narrow (§4, 144–220): its
     * chips wrap onto more than one row and stay within its width instead of
     * scrolling sideways. Checked before an answer opens, while the chips are
     * all in view. 查词 is left out, as the card's bottom row may say it too,
     * and so is 指代: the test book is zh-CN, so its sentences go out as
     * Chinese and the card shows the chips for other languages.
     */
    private suspend fun assertChipsWrapWithinTheCard(activity: ReaderActivity, step: String) {
        val (card, _, _) = settledCard(activity, step)
        val chips = listOf("结构", "读懂", "直译", "追问").map { label ->
            Rect().also { bounds -> waitForNode("the $label chip, $step") { it.says(label) }.getBoundsInScreen(bounds) }
        }
        assertTrue("$step: a chip is not laid out: $chips", chips.all { it.width() > 0 && it.height() > 0 })
        val centres = chips.map { it.exactCenterY() }.sorted()
        val rowGap = chips.minOf { it.height() } / 2f
        val rows = 1 + centres.zipWithNext().count { (upper, lower) -> lower - upper > rowGap }
        assertTrue("$step: the chips $chips sit on one row of a card ${card.width()} px wide", rows >= 2)
        val span = chips.maxOf { it.right } - chips.minOf { it.left }
        assertTrue("$step: the chips $chips are wider than the card (${card.width()} px)", span <= card.width() + 2f)
    }

    /**
     * Waits until the card stops moving and returns it with the safe area and
     * the selection, all in window pixels.
     */
    private suspend fun settledCard(activity: ReaderActivity, step: String): Triple<RectF, RectF, RectF?> {
        var previous: RectF? = null
        for (attempt in 0 until 30) {
            instrumentation.waitForIdleSync()
            delay(200)
            val frames = withContext(Dispatchers.Main) {
                val card = activity.translationCardFrameForTesting?.let { RectF(it) }
                val safe = activity.translationSafeAreaForTesting?.let { RectF(it) }
                if (card == null || safe == null) null
                else Triple(card, safe, activity.translationSelectionFrameForTesting())
            }
            if (frames != null && frames.first == previous) return frames
            previous = frames?.first
        }
        error("$step: the card did not settle")
    }

    /** Applies [appearance]; a new 排版方向 rebuilds the navigator, so the chapter is waited for again. */
    private suspend fun setAppearance(activity: ReaderActivity, appearance: ReaderAppearance) {
        withContext(Dispatchers.Main) { activity.applyAppearance(appearance) }
        val vertical = appearance.orientation == ReaderTextOrientation.VERTICAL
        repeat(100) {
            val ready = withContext(Dispatchers.Main) {
                val navigator = navigator(activity)
                navigator != null && navigator.settings.value.verticalText == vertical &&
                    navigator.evaluateJavascript("document.getElementById('japanese-word') !== null") == "true"
            }
            if (ready) {
                // A freshly laid out chapter gets a moment before it is measured.
                delay(600)
                return
            }
            delay(100)
        }
        error("Readium did not lay the book out as $appearance")
    }

    private fun navigator(activity: ReaderActivity): EpubNavigatorFragment? =
        activity.supportFragmentManager.fragments
            .filterIsInstance<EpubNavigatorFragment>()
            .firstOrNull { it.view != null }

    private fun AccessibilityNodeInfo.says(label: String): Boolean =
        text?.toString()?.lineSequence()?.any { it.trim() == label } == true ||
            contentDescription?.toString() == label

    /**
     * The 追问 field's editable node. Compose puts the field's 追问输入框 label
     * on a node of its own, which takes no taps and never has the focus, so the
     * label only tells which editable node is the field: the one whose own
     * subtree, or its parent's or grandparent's, holds the label.
     */
    private fun AccessibilityNodeInfo.isFollowUpField(): Boolean {
        if (!isEditable) return false
        fun labelled(node: AccessibilityNodeInfo?, depth: Int): Boolean =
            node != null && (node.says("追问输入框") ||
                depth > 0 && (0 until node.childCount).any { labelled(node.getChild(it), depth - 1) })
        var scope: AccessibilityNodeInfo? = this
        repeat(3) {
            if (labelled(scope, 3)) return true
            scope = scope?.parent
        }
        return false
    }

    private fun AccessibilityNodeInfo.mentions(value: String): Boolean =
        text?.toString()?.contains(value) == true || contentDescription?.toString()?.contains(value) == true

    private fun findNode(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (matches(node)) return node
            for (index in 0 until node.childCount) visit(node.getChild(index))?.let { return it }
            return null
        }
        return visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private suspend fun waitForNode(what: String, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        repeat(80) {
            instrumentation.waitForIdleSync()
            findNode(matches)?.let { return it }
            delay(100)
        }
        fail("Not on screen: $what")
    }

    /** Taps the control named [label] through accessibility. */
    private suspend fun click(label: String) {
        var node: AccessibilityNodeInfo? = waitForNode(label) { it.says(label) }
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Could not tap $label" }
        delay(300)
    }

    private suspend fun <T : Any> waitForState(what: String, read: suspend () -> T?): T {
        repeat(100) {
            read()?.let { return it }
            delay(100)
        }
        fail("Timed out waiting for $what")
    }

    /** Leaves the screen and the accessibility tree behind for the report, then fails. */
    private fun fail(message: String): Nothing {
        val nodes = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return
            nodes += "text=${node.text}; description=${node.contentDescription}; clickable=${node.isClickable}; visible=${node.isVisibleToUser}"
            for (index in 0 until node.childCount) collect(node.getChild(index))
        }
        runCatching {
            collect(instrumentation.uiAutomation.rootInActiveWindow)
            File(instrumentation.targetContext.getExternalFilesDir(null), "learning-ui").apply { mkdirs() }
                .resolve("ai-actions-accessibility.txt").writeText(nodes.joinToString("\n"))
            captureReader("android-ai-actions-failure")
        }
        error(message)
    }

    private fun captureReader(name: String) {
        instrumentation.waitForIdleSync()
        // An idle main thread is not a drawn frame: the emulator renders in
        // software, at 300 ms to over a second a frame.
        SystemClock.sleep(1_500)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "learning-ui").apply { mkdirs() }
        try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    private fun waitForActivityAndNavigator(scenario: ActivityScenario<ReaderActivity>): ReaderActivity {
        var ready: ReaderActivity? = null
        repeat(100) {
            scenario.onActivity { activity ->
                if (activity.supportFragmentManager.fragments.any { it is EpubNavigatorFragment && it.view != null }) {
                    ready = activity
                }
            }
            ready?.let { return it }
            SystemClock.sleep(100)
        }
        error("ReaderActivity did not attach Readium's EPUB navigator")
    }

    private suspend fun elementCenter(activity: ReaderActivity, id: String): PointF {
        repeat(100) {
            val raw = withContext(Dispatchers.Main) {
                navigator(activity)?.evaluateJavascript(
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
