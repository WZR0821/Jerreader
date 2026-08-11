package com.jerreader.unified.reader.kernel

import com.jerreader.unified.domain.LanguageCode
import com.jerreader.unified.reader.geometry.ReaderRect
import com.jerreader.unified.reader.geometry.unionOrNull
import com.jerreader.unified.library.ReaderAppearance
import com.jerreader.unified.translation.QuickTranslationUnit
import com.jerreader.unified.library.ReaderTextOrientation
import com.jerreader.unified.reader.selection.ReaderSelectionDecoder
import com.jerreader.unified.reader.selection.ReaderSelectionGestureGate
import com.jerreader.unified.reader.selection.ReaderSelectionScripts
import com.jerreader.unified.reader.selection.ReaderSelectionSnapshot
import com.jerreader.unified.reader.selection.ReaderSelectionVisualStyle
import com.jerreader.unified.reader.selection.ReaderSpeechHighlight
import com.jerreader.unified.reader.selection.ReaderWritingMode
import com.jerreader.unified.translation.ReaderSentenceSegmenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the reader is currently highlighting, in overlay coordinates. */
data class ReaderSelectionState(
    val tiles: List<ReaderRect> = emptyList(),
    val anchorFrame: ReaderRect? = null,
    val text: String = "",
    val writingMode: ReaderWritingMode = ReaderWritingMode.HORIZONTAL,
    val speechHighlight: ReaderSpeechHighlight? = null
) {
    val isActive: Boolean get() = tiles.isNotEmpty()

    companion object {
        val Empty = ReaderSelectionState()
    }
}

/**
 * Runs one quick-translate gesture end to end, from a tap to a painted
 * highlight and an anchor rectangle for the translation card.
 *
 * The whole sequence used to exist twice, once per platform, and the order of
 * its steps was where the behaviour diverged: which coordinate space the tap
 * was in, whether the page or the app decided the sentence boundary, whether
 * the tiles were merged before or after painting. It is one sequence now, and
 * the platforms supply only a [ReaderWebViewBridge].
 */
class ReaderSelectionController(
    private val bridge: ReaderWebViewBridge,
    private val appearance: () -> ReaderAppearance,
    private val bookLanguage: () -> LanguageCode?,
    private val publicationIsVertical: () -> Boolean
) {
    private val gate = ReaderSelectionGestureGate()
    private val _state = MutableStateFlow(ReaderSelectionState.Empty)
    val state: StateFlow<ReaderSelectionState> = _state.asStateFlow()

    /**
     * The writing mode the *reader* configured, which wins over the page's
     * self-report. See [ReaderSelectionDecoder] for why the page cannot be
     * trusted here.
     */
    private fun forcedWritingMode(): ReaderWritingMode? = when (appearance().orientation) {
        ReaderTextOrientation.VERTICAL -> ReaderWritingMode.VERTICAL
        ReaderTextOrientation.HORIZONTAL -> ReaderWritingMode.HORIZONTAL
        ReaderTextOrientation.PUBLICATION ->
            if (publicationIsVertical()) ReaderWritingMode.VERTICAL else null
    }

    /**
     * Selects, paints and reports the sentence (or paragraph) under a tap.
     *
     * @param x overlay-space x of the tap.
     * @param y overlay-space y of the tap.
     * @return the selected text, or null when the tap did not land on prose.
     */
    suspend fun selectAt(
        x: Double,
        y: Double,
        unit: QuickTranslationUnit,
        uptimeMillis: Long
    ): String? {
        // A tap arriving right after a native long press is the long press's own
        // trailing callback. Honouring it would throw away the user's hand-made
        // selection and translate the whole sentence instead.
        if (gate.shouldSuppressTap(uptimeMillis)) return null

        val geometry = bridge.contentGeometry()
        val (cssX, cssY) = geometry.toCssPixels(x, y)

        // Tearing down the page overlay and dropping the published state have to
        // happen together. Clearing only the page leaves the native highlight
        // layer painting the previous sentence over a page that no longer has
        // it, and the two stay out of step until the next successful tap.
        bridge.evaluate(ReaderSelectionScripts.clearHighlightScript())
        _state.value = ReaderSelectionState.Empty

        val block = ReaderSelectionDecoder.decodeBlockText(
            bridge.evaluate(ReaderSelectionScripts.blockTextScript(cssX, cssY))
        ) ?: return null

        val range = when (unit) {
            QuickTranslationUnit.PARAGRAPH -> block.text.indices
            QuickTranslationUnit.SENTENCE ->
                ReaderSentenceSegmenter.sentenceRange(
                    block.text,
                    block.caretOffset,
                    bookLanguage()
                ) ?: return null
        }

        var start = range.first
        var end = range.last + 1
        while (start < end && block.text[start].isWhitespace()) start++
        while (end > start && block.text[end - 1].isWhitespace()) end--
        if (end <= start) return null

        val palette = ReaderSelectionVisualStyle.palette(appearance())
        val result = ReaderSelectionDecoder.decodeSelectionResult(
            bridge.evaluate(
                ReaderSelectionScripts.selectRangeScript(cssX, cssY, start, end, palette)
            ),
            forcedWritingMode = forcedWritingMode()
        ) ?: return null

        val tiles = result.mergedRects()
        if (tiles.isEmpty()) return null

        // Kotlin merged them; hand the finished tiles back for painting so the
        // page never re-derives line structure it has no tests for.
        bridge.evaluate(
            ReaderSelectionScripts.paintTilesScript(
                tiles = tiles,
                palette = palette,
                scrollX = result.scrollX,
                scrollY = result.scrollY
            )
        )

        // Re-read the geometry rather than reusing the one the tap was converted
        // with. `addRange` scrolls the selection into view, and on iOS the
        // safe-area compensation is expressed as a scroll offset — so the
        // transform that was correct for the tap can be stale by the time the
        // tiles come back, and the native highlight would sit off the text.
        val text = block.text.substring(start, end)
        publish(tiles, text, result.writingMode, bridge.contentGeometry())
        return text
    }

    /** Reads whatever the user selected by hand, without disturbing it. */
    suspend fun currentSelection(): ReaderSelectionSnapshot? {
        val snapshot = ReaderSelectionDecoder.decodeSnapshot(
            bridge.evaluate(ReaderSelectionScripts.currentSelectionScript()),
            forcedWritingMode = forcedWritingMode()
        ) ?: return null
        if (!snapshot.isUsable) return null

        val tiles = snapshot.mergedRects()
        if (tiles.isNotEmpty()) {
            publish(tiles, snapshot.baseText, snapshot.writingMode, bridge.contentGeometry())
        }
        return snapshot
    }

    /** Called when the platform reports a native long-press selection. */
    fun noteNativeSelection(uptimeMillis: Long) {
        gate.noteNativeSelection(uptimeMillis)
    }

    fun updateSpeechHighlight(highlight: ReaderSpeechHighlight?) {
        _state.value = _state.value.copy(speechHighlight = highlight)
    }

    suspend fun clear() {
        bridge.evaluate(ReaderSelectionScripts.clearHighlightScript())
        gate.reset()
        _state.value = ReaderSelectionState.Empty
    }

    private fun publish(
        cssTiles: List<ReaderRect>,
        text: String,
        mode: ReaderWritingMode,
        geometry: ReaderContentGeometry
    ) {
        val overlayTiles = cssTiles.map(geometry::toOverlay)
        _state.value = ReaderSelectionState(
            tiles = overlayTiles,
            anchorFrame = overlayTiles.unionOrNull(),
            text = text,
            writingMode = mode,
            speechHighlight = null
        )
    }
}
